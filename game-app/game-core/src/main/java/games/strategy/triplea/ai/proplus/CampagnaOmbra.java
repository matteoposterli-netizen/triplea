package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProBattleUtils;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.ai.proplus.strategia.Strategia;
import games.strategy.triplea.ai.proplus.strategia.Strategie;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.BattleRecord;
import games.strategy.triplea.delegate.data.BattleRecords;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.triplea.java.collections.IntegerMap;

/**
 * Step R2a: shadow campaign planner of the "Pro+ Stratega LLM". Computed at the purchase of each
 * nation of the strategist and written to the diary, WITHOUT any effect on the game: no purchase,
 * no unit held, no LLM call, no field in the contract; the only state written is its own game
 * property ({@value #KEY_STATO}{@code <alliance>}, read by nothing else than this class and the
 * summaries). Land campaigns of ONE nation with its own planes, toward an enemy VC or capital
 * reachable by land within {@code orizzonte_round} rounds.
 *
 * <p>No dice: every strength is {@link ProBattleUtils#estimateStrength} (the battle calculator does
 * not expose a distribution and is not touched, so the AI's simulations are not consumed). The
 * margins 60/70/80 are an approximation, declared in the data file ({@code campagna_ombra}): the
 * enemy strength (defenders, counterattack) and the expected losses are multiplied by {@code
 * fattori_percentile} of the margin; "take" = strength difference of {@link
 * ProBattleUtils#estimateStrengthDifference}'s formula >= {@code soglia_presa}, "hold" = the
 * counterattack's difference <= 100 - {@code soglia_tenuta}.
 */
public final class CampagnaOmbra {
  private CampagnaOmbra() {}

  /** Game property with the compact state of the alliance (predictions to verify, counters). */
  public static final String KEY_STATO = "Pro+ stratega ombra: ";

  static final String CASO_A = "a";
  static final String CASO_B = "b";
  static final String CASO_C = "c";

  // ================================================================================ config

  /** Values of {@code campagna_ombra} in {@code stratega-llm.json} (NOT tuned). */
  record Config(
      int orizzonteRound,
      int maxObiettivi,
      List<Integer> margini,
      Map<Integer, Double> fattori,
      double sogliaPresa,
      double sogliaTenuta,
      double forzaPiccolaTuv,
      double quotaAcquistiNemici,
      double quotaPuStrategia,
      int maxPresidio,
      int roundRiprendere,
      int marginePresidio,
      int maxTerritoriRetrovia,
      int maxPrevisioni) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in =
            CampagnaOmbra.class.getClassLoader().getResourceAsStream(StrategaConfig.RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + StrategaConfig.RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject()
                      .getAsJsonObject("campagna_ombra"));
        } catch (final IOException e) {
          throw new IllegalStateException("File dello stratega non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject o) {
      final List<Integer> margins = new ArrayList<>();
      o.getAsJsonArray("margini_percentile").forEach(e -> margins.add(e.getAsInt()));
      final Map<Integer, Double> factors = new LinkedHashMap<>();
      o.getAsJsonObject("fattori_percentile")
          .entrySet()
          .forEach(e -> factors.put(Integer.parseInt(e.getKey()), e.getValue().getAsDouble()));
      return new Config(
          o.get("orizzonte_round").getAsInt(),
          o.get("max_obiettivi_per_nazione").getAsInt(),
          List.copyOf(margins),
          Map.copyOf(factors),
          o.get("soglia_presa").getAsDouble(),
          o.get("soglia_tenuta").getAsDouble(),
          o.get("forza_piccola_tuv").getAsDouble(),
          o.get("quota_acquisti_nemici").getAsDouble(),
          o.get("quota_pu_strategia").getAsDouble(),
          o.get("max_presidio").getAsInt(),
          o.get("round_per_riprendere").getAsInt(),
          o.get("margine_presidio").getAsInt(),
          o.get("max_territori_retrovia").getAsInt(),
          o.get("max_previsioni").getAsInt());
    }

    double fattore(final int margin) {
      return fattori.getOrDefault(margin, 1.0);
    }
  }

  // ============================================================================== entry points

  /**
   * At the purchase of a nation of the strategist (real board, {@code proData} initialized): the
   * shadow campaigns of the nation, the check of the earlier predictions, the diary. Never throws;
   * never changes anything but {@value #KEY_STATO}.
   */
  public static Optional<JsonObject> calcola(
      final ProData proData, final ProOddsCalculator calc, final GamePlayer player) {
    try {
      final GameState data = proData.getData();
      final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
      if (alliance.isEmpty()
          || !Stratega.gioca(data, player)
          || !StrategaInterruttori.OMBRA.acceso(data.getProperties(), alliance.get())
          || StrategaConfig.get().nazioniSenzaRuoli().contains(player.getName())) {
        return Optional.empty();
      }
      final Calcolo c = new Calcolo(proData, calc, player, alliance.get(), Config.get());
      final JsonObject out = c.esegui();
      return Optional.of(out);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Campagne in ombra non calcolate: " + e);
      return Optional.empty();
    }
  }

  /**
   * End of the turn of a nation of the strategist: how many land units Hard really left in the
   * territories classified by rule 2.6 this turn (the predicted garrison is in the state).
   */
  public static void fineTurno(final GameState data, final GamePlayer player) {
    try {
      final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
      if (alliance.isEmpty() || !Stratega.gioca(data, player)) {
        return;
      }
      final GameProperties props = data.getProperties();
      final JsonObject st = stato(props, alliance.get());
      final int round = data.getSequence().getRound();
      if (!st.has("retrovia")) {
        return;
      }
      for (final JsonElement e : st.getAsJsonArray("retrovia")) {
        final JsonObject r = e.getAsJsonObject();
        if (r.get("round").getAsInt() != round
            || !r.get("nazione").getAsString().equals(player.getName())) {
          continue;
        }
        final Territory t = data.getMap().getTerritoryOrNull(r.get("territorio").getAsString());
        if (t != null) {
          r.addProperty(
              "unita_lasciate_da_hard",
              t.getUnitCollection()
                  .countMatches(
                      Matches.unitIsOwnedBy(player)
                          .and(Matches.unitIsLand())
                          .and(Matches.unitIsNotInfrastructure())));
        }
      }
      salva(props, alliance.get(), st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Campagne in ombra, fine turno: " + e);
    }
  }

  /** The summary of the shadow for the strategist's summary file (counters, feasible list). */
  static JsonObject riepilogo(final GameProperties props, final String alliance) {
    final JsonObject st = stato(props, alliance);
    final JsonObject o = new JsonObject();
    for (final String k : List.of("statistiche", "fattibili", "round_con_blocco")) {
      if (st.has(k)) {
        o.add(k, st.get(k).deepCopy());
      }
    }
    return o;
  }

  /** Rule 2.6: a garrison (case c) is released in the round its case is a) or b) again. */
  static boolean rilasciato(@Nullable final String before, final String now) {
    return CASO_C.equals(before) && !CASO_C.equals(now);
  }

  static JsonObject stato(final GameProperties props, final String alliance) {
    if (props.get(KEY_STATO + alliance) instanceof String s && !s.isBlank()) {
      try {
        return JsonParser.parseString(s).getAsJsonObject();
      } catch (final RuntimeException e) {
        // a fresh state
      }
    }
    return new JsonObject();
  }

  static void salva(final GameProperties props, final String alliance, final JsonObject st) {
    props.set(KEY_STATO + alliance, (Serializable) null);
    props.set(KEY_STATO + alliance, st.toString());
  }

  // ============================================================================ computation

  /** One computation (one nation, one turn). */
  static final class Calcolo {
    final ProData proData;
    final ProOddsCalculator calc;
    final GamePlayer player;
    final String alliance;
    final Config cfg;
    final GameState data;
    final GameMap map;
    final int round;
    final IntegerMap<UnitType> costs;
    final Predicate<Territory> passable;
    final Map<String, Integer> distMemo = new HashMap<>();

    Calcolo(
        final ProData proData,
        final ProOddsCalculator calc,
        final GamePlayer player,
        final String alliance,
        final Config cfg) {
      this.proData = proData;
      this.calc = calc;
      this.player = player;
      this.alliance = alliance;
      this.cfg = cfg;
      this.data = proData.getData();
      this.map = data.getMap();
      this.round = data.getSequence().getRound();
      this.costs = proData.getUnitValueMap();
      this.passable =
          x ->
              !x.isWater()
                  && !Matches.territoryIsImpassable().test(x)
                  && !x.getOwner().isNull()
                  && ProPlusSettings.allianceOf(data, x.getOwner()).isPresent();
    }

    JsonObject esegui() {
      final GameProperties props = data.getProperties();
      final JsonObject st = stato(props, alliance);
      final List<String> lines = new ArrayList<>();
      lines.add("");
      lines.add(
          "=== R"
              + round
              + " "
              + player.getName()
              + " - Campagne IN OMBRA (nessun effetto sul gioco) ===");
      // 2.8 first: the predictions of the earlier rounds against what happened
      final JsonObject verifica = verifica(st, lines);
      // 2.1-2.7
      final double income = ProUtils.getPlayerProduction(player, data);
      final double hardDefense = difesaHardUltimoTurno(props);
      final double perRound = Math.max(0, income - hardDefense) * cfg.quotaPuStrategia() / 100.0;
      final List<JsonObject> campaigns = new ArrayList<>();
      final List<String> landings = new ArrayList<>();
      for (final Obiettivo o : obiettivi(landings)) {
        campaigns.add(campagna(o, perRound, income, lines));
      }
      if (!landings.isEmpty()) {
        lines.add(
            "  Non valutati (sbarco): "
                + String.join(", ", landings.subList(0, Math.min(10, landings.size()))));
      }
      if (campaigns.isEmpty()) {
        lines.add(
            "  Nessuna VC o capitale nemica raggiungibile via terra entro "
                + cfg.orizzonteRound()
                + " round");
      }
      // 2.6 behind the point and along the paths (current round)
      final JsonArray retrovia = retrovia(campaigns, st, lines);
      // output
      final JsonObject out = new JsonObject();
      out.addProperty("round", round);
      out.addProperty("nazione", player.getName());
      out.addProperty("alleanza", alliance);
      out.addProperty("nota", "IN OMBRA: nessun effetto sul gioco");
      out.addProperty("reddito", Math.round(income));
      out.addProperty("pu_difesa_hard_ultimo_turno", Math.round(hardDefense));
      out.addProperty("pu_per_round_strategia", Math.round(perRound * 10) / 10.0);
      final JsonArray cs = new JsonArray();
      campaigns.forEach(cs::add);
      out.add("campagne", cs);
      final JsonArray land = new JsonArray();
      landings.forEach(land::add);
      out.add("non_valutati_sbarco", land);
      out.add("retrovia", retrovia);
      out.add("verifica", verifica);
      aggiornaStato(st, campaigns, retrovia, income);
      salva(props, alliance, st);
      scriviFile(out);
      Stratega.diarioTurnoOmbra(data, lines);
      return out;
    }

    // ------------------------------------------------------------------------- 2.1 targets

    record Obiettivo(Territory bersaglio, Territory partenza, List<Territory> percorso) {}

    /** Enemy VC and capitals reachable by land within the horizon (closest first, at most N). */
    List<Obiettivo> obiettivi(final List<String> landings) {
      final List<Territory> fronts = new ArrayList<>();
      for (final Territory t : map.getTerritories()) {
        if (!t.isWater()
            && Matches.isTerritoryAllied(player).test(t)
            && t.anyUnitsMatch(
                Matches.unitIsOwnedBy(player)
                    .and(Matches.unitIsLand())
                    .and(Matches.unitIsNotInfrastructure()))) {
          fronts.add(t);
        }
      }
      final List<Obiettivo> out = new ArrayList<>();
      if (fronts.isEmpty()) {
        return out;
      }
      for (final Territory t : map.getTerritories()) {
        if (t.isWater() || !nemico(t.getOwner())) {
          continue;
        }
        final Optional<TerritoryAttachment> ta = TerritoryAttachment.get(t);
        if (ta.isEmpty() || (ta.get().getVictoryCity() <= 0 && !ta.get().isCapital())) {
          continue;
        }
        Obiettivo best = null;
        for (final Territory f : fronts) {
          final int d = distanza(f, t);
          if (d < 1 || d > cfg.orizzonteRound()) {
            continue;
          }
          if (best == null
              || d < best.percorso().size()
              || (d == best.percorso().size() && tuvProprio(f) > tuvProprio(best.partenza()))) {
            final Optional<Route> r = map.getRoute(f, t, passable);
            if (r.isPresent() && r.get().numberOfSteps() == d) {
              best = new Obiettivo(t, f, List.copyOf(r.get().getSteps()));
            }
          }
        }
        if (best != null) {
          out.add(best);
        } else if (map.getNeighbors(t).stream().anyMatch(Territory::isWater)) {
          landings.add(t.getName());
        }
      }
      out.sort(
          Comparator.comparingInt((Obiettivo o) -> o.percorso().size())
              .thenComparing(o -> -valoreIndice(o.bersaglio()))
              .thenComparing(o -> o.bersaglio().getName()));
      return out.subList(0, Math.min(cfg.maxObiettivi(), out.size()));
    }

    // -------------------------------------------------------------------- 2.2-2.5, 2.7

    JsonObject campagna(
        final Obiettivo o, final double perRound, final double income, final List<String> lines) {
      final JsonObject c = new JsonObject();
      c.addProperty("obiettivo", o.bersaglio().getName());
      c.addProperty("proprietario", o.bersaglio().getOwner().getName());
      c.addProperty("partenza", o.partenza().getName());
      c.add("piani_dottrina", piani(o.bersaglio()));
      // 2.2 route and war
      final JsonArray tappe = new JsonArray();
      int stageRound = round;
      final List<Integer> rounds = new ArrayList<>();
      String guerraNota = "";
      for (final Territory s : o.percorso()) {
        int r = stageRound;
        if (nemico(s.getOwner()) && !data.getRelationshipTracker().isAtWar(player, s.getOwner())) {
          final int warRound = roundGuerra(s.getOwner());
          if (warRound < 0) {
            guerraNota =
                "guerra con " + s.getOwner().getName() + " non possibile entro l'orizzonte";
            r = Integer.MAX_VALUE / 2;
          } else {
            r = Math.max(r, warRound);
            guerraNota =
                "guerra con "
                    + s.getOwner().getName()
                    + (warRound <= round
                        ? " possibile ora: la dichiara il tiro casuale di Hard"
                            + " (ProPoliticsAi:153-160)"
                        : " possibile dal R" + warRound);
          }
        }
        rounds.add(r);
        stageRound = r + 1;
      }
      if (!guerraNota.isEmpty()) {
        c.addProperty("guerra", guerraNota);
      }
      final int deadline = rounds.isEmpty() ? round : rounds.get(rounds.size() - 1);
      // 2.3 arrival of the existing units
      final List<Unit> mine = unitaCampagna();
      final Map<Unit, Territory> where = new HashMap<>();
      for (final Territory t : map.getTerritories()) {
        t.getUnitCollection().stream().filter(mine::contains).forEach(u -> where.put(u, t));
      }
      // 2.4-2.5 per margin
      final JsonObject perMargin = new JsonObject();
      boolean anyFeasible = false;
      for (final int margin : cfg.margini()) {
        final JsonObject m = flusso(o, rounds, mine, where, margin, perRound, deadline);
        perMargin.add(String.valueOf(margin), m);
        anyFeasible |= m.get("fattibile").getAsBoolean();
      }
      for (int i = 0; i < o.percorso().size(); i++) {
        final Territory s = o.percorso().get(i);
        final JsonObject x = new JsonObject();
        x.addProperty("territorio", s.getName());
        x.addProperty("proprietario", s.getOwner().getName());
        x.addProperty("round_stimato", rounds.get(i) > 99 ? -1 : rounds.get(i));
        x.addProperty("conquista", nemico(s.getOwner()));
        tappe.add(x);
      }
      c.add("tappe", tappe);
      c.add("margini", perMargin);
      c.addProperty("fattibile_a_un_margine", anyFeasible);
      // 2.7 purchases (margin of the garrison rule, the middle one)
      c.add(
          "acquisti",
          acquisti(
              o,
              rounds,
              perMargin.getAsJsonObject(String.valueOf(cfg.marginePresidio())),
              perRound));
      // predictions to verify (2.8): defenders on the stages and the target at +1..+3, income,
      // own force
      final JsonObject prev = new JsonObject();
      final JsonObject defs = new JsonObject();
      for (final Territory s : o.percorso()) {
        if (!nemico(s.getOwner())) {
          continue;
        }
        final JsonObject byK = new JsonObject();
        for (int k = 1; k <= 3; k++) {
          byK.addProperty(String.valueOf(round + k), Math.round(tuv(difensori(s, k))));
        }
        defs.add(s.getName(), byK);
      }
      prev.add("difensori", defs);
      final JsonObject inc = new JsonObject();
      final JsonObject force = new JsonObject();
      final JsonObject mid = perMargin.getAsJsonObject(String.valueOf(cfg.marginePresidio()));
      final double ownNow = tuv(tutteMie());
      for (int k = 1; k <= 3; k++) {
        double extra = 0;
        for (int i = 0; i < o.percorso().size(); i++) {
          if (rounds.get(i) < round + k
              && mid.has("presa_ok_fino_a_tappa")
              && i < mid.get("presa_ok_fino_a_tappa").getAsInt()
              && nemico(o.percorso().get(i).getOwner())) {
            extra +=
                TerritoryAttachment.get(o.percorso().get(i)).map(a -> a.getProduction()).orElse(0);
          }
        }
        inc.addProperty(String.valueOf(round + k), Math.round(income + extra));
        force.addProperty(
            String.valueOf(round + k),
            Math.round(
                ownNow + perRound * k - mid.get("perdite_attese_tuv").getAsDouble() * k / 3));
      }
      prev.add("reddito", inc);
      prev.add("forza_propria_tuv", force);
      c.add("previsioni", prev);
      // diary
      final StringBuilder sb = new StringBuilder();
      sb.append("  ")
          .append(o.bersaglio().getName())
          .append(" (")
          .append(o.bersaglio().getOwner().getName())
          .append(", piani ")
          .append(c.getAsJsonArray("piani_dottrina").isEmpty() ? "-" : c.get("piani_dottrina"))
          .append("): da ")
          .append(o.partenza().getName())
          .append(", tappe ")
          .append(String.join(" > ", o.percorso().stream().map(Territory::getName).toList()))
          .append(guerraNota.isEmpty() ? "" : " [" + guerraNota + "]");
      lines.add(sb.toString());
      for (final int margin : cfg.margini()) {
        final JsonObject m = perMargin.getAsJsonObject(String.valueOf(margin));
        lines.add(
            String.format(
                Locale.ROOT,
                "    margine %d: forza necessaria %.0f TUV, disponibile %.0f, scarto %.0f;"
                    + " fattibile %s, round stimato %s, quota PU %.0f%%; presa %.0f%%, tenuta"
                    + " %.0f%%%s",
                margin,
                m.get("forza_necessaria_tuv").getAsDouble(),
                m.get("forza_disponibile_tuv").getAsDouble(),
                m.get("scarto_tuv").getAsDouble(),
                m.get("fattibile").getAsBoolean() ? "SI'" : "no",
                m.get("round_stimato").getAsInt() < 0
                    ? "-"
                    : "R" + m.get("round_stimato").getAsInt(),
                m.get("quota_pu_pct").getAsDouble(),
                m.get("presa_obiettivo_pct").getAsDouble(),
                m.get("tenuta_obiettivo_pct").getAsDouble(),
                m.has("blocco") ? " (" + m.get("blocco").getAsString() + ")" : ""));
      }
      final JsonObject buy = c.getAsJsonObject("acquisti");
      lines.add(
          "    acquisti (margine "
              + cfg.marginePresidio()
              + "): "
              + buy.get("testo").getAsString());
      return c;
    }

    /**
     * 2.4 the point as a flow: at each stage previous point - expected losses - detachments +
     * arrivals of that round; it must take the stage and hold it against the next enemy turn.
     */
    JsonObject flusso(
        final Obiettivo o,
        final List<Integer> rounds,
        final List<Unit> mine,
        final Map<Unit, Territory> where,
        final int margin,
        final double perRound,
        final int deadline) {
      final double f = cfg.fattore(margin);
      final JsonObject m = new JsonObject();
      final List<Unit> point = new ArrayList<>();
      final Set<Unit> used = new LinkedHashSet<>();
      double lossesTotal = 0;
      double needMax = 0;
      double availAtNeed = 0;
      int okUntil = 0;
      String block = "";
      double take = 0;
      double hold = 0;
      final JsonArray stages = new JsonArray();
      for (int i = 0; i < o.percorso().size(); i++) {
        final Territory s = o.percorso().get(i);
        final int r = rounds.get(i);
        if (r > 99) {
          block = "guerra non possibile";
          break;
        }
        // arrivals
        for (final Unit u : mine) {
          if (used.contains(u) || where.get(u) == null) {
            continue;
          }
          if (arrivo(u, where.get(u), s) <= r) {
            point.add(u);
            used.add(u);
          }
        }
        final JsonObject x = new JsonObject();
        x.addProperty("tappa", s.getName());
        x.addProperty("round", r);
        x.addProperty("forza_punta_tuv", Math.round(tuv(point)));
        if (!nemico(s.getOwner())) {
          // own/allied territory on the way: no battle
          x.addProperty("battaglia", false);
          stages.add(x);
          okUntil = i + 1;
          continue;
        }
        final List<Unit> defenders = difensori(s, Math.max(1, r - round + 1));
        final double sAtt =
            point.isEmpty() ? 0 : ProBattleUtils.estimateStrength(s, point, defenders, true);
        final double sDef =
            defenders.isEmpty()
                ? 0
                : ProBattleUtils.estimateStrength(s, defenders, point, false) * f;
        take =
            defenders.isEmpty()
                ? (point.stream().anyMatch(Matches.unitIsLand()) ? 100 : 0)
                : diff(sAtt, sDef);
        final double pointTuv = tuv(point);
        final double loss =
            sAtt + sDef <= 0 ? 0 : Math.min(pointTuv, pointTuv * sDef / (sAtt + sDef) * f);
        lossesTotal += loss;
        final List<Unit> survivors = superstiti(point, loss);
        // hold against the counterattack of the next enemy turn
        final List<Unit> counter = contrattacco(s);
        final double sC =
            counter.isEmpty()
                ? 0
                : ProBattleUtils.estimateStrength(s, counter, survivors, true) * f;
        final double sS =
            survivors.isEmpty() ? 0 : ProBattleUtils.estimateStrength(s, survivors, counter, false);
        final double enemy = counter.isEmpty() ? 0 : survivors.isEmpty() ? 100 : diff(sC, sS);
        hold = 100 - Math.min(100, Math.max(0, enemy));
        // force needed (strength ~ linear in TUV)
        final double needTake =
            sDef <= 0
                ? pointTuv
                : pointTuv * needed(sDef, cfg.sogliaPresa()) / Math.max(1e-6, sAtt);
        final double needHold =
            sC <= 0 ? 0 : loss + tuv(survivors) * neededHold(sC, sS) / Math.max(1e-6, sS);
        final double need = Math.max(needTake, needHold);
        x.addProperty("battaglia", true);
        x.addProperty("difensori_attesi_tuv", Math.round(tuv(defenders) * f));
        x.addProperty("presa_pct", Math.round(Math.min(100, Math.max(0, take))));
        x.addProperty("perdite_attese_tuv", Math.round(loss));
        x.addProperty("contrattacco_tuv", Math.round(tuv(counter) * f));
        x.addProperty("tenuta_pct", Math.round(hold));
        x.addProperty("forza_necessaria_tuv", Math.round(need));
        stages.add(x);
        if (block.isEmpty()) {
          // the force needed and available at the first stage that blocks (or the hardest one)
          if (need > needMax || !(take >= cfg.sogliaPresa() && hold >= cfg.sogliaTenuta())) {
            needMax = need;
            availAtNeed = pointTuv;
          }
        }
        // the point goes on with the survivors (they defend the stage: no separate garrison)
        point.clear();
        point.addAll(survivors);
        if (take >= cfg.sogliaPresa() && hold >= cfg.sogliaTenuta() && !survivors.isEmpty()) {
          okUntil = i + 1;
        } else if (block.isEmpty()) {
          block =
              take < cfg.sogliaPresa()
                  ? "presa insufficiente a " + s.getName()
                  : "tenuta insufficiente a " + s.getName();
          break; // the campaign stops at the first stage it cannot take and hold
        }
      }
      final double gap = Math.max(0, needMax - availAtNeed);
      // 2.7 feasibility: the gap filled by purchases within the deadline with at most the share
      // of the PUs after Hard's defense
      final int roundsLeft = Math.max(1, deadline - round);
      final double puNeeded = gap;
      final double puAvail = perRound * roundsLeft;
      final boolean bought =
          block.isEmpty() || (gap > 0 && puNeeded <= puAvail && !block.startsWith("guerra"));
      final boolean feasible =
          okUntil == o.percorso().size() || (bought && !block.startsWith("guerra"));
      m.add("tappe", stages);
      m.addProperty("forza_necessaria_tuv", Math.round(needMax));
      m.addProperty("forza_disponibile_tuv", Math.round(availAtNeed));
      m.addProperty("scarto_tuv", Math.round(gap));
      m.addProperty("pu_necessari", Math.round(puNeeded));
      m.addProperty("pu_disponibili_entro_scadenza", Math.round(puAvail));
      final double income = Math.max(1, ProUtils.getPlayerProduction(player, data));
      m.addProperty("quota_pu_pct", Math.round(puNeeded / (income * roundsLeft) * 1000) / 10.0);
      m.addProperty("fattibile", feasible);
      m.addProperty("presa_ok_fino_a_tappa", okUntil);
      m.addProperty("round_stimato", feasible ? deadline : -1);
      m.addProperty("perdite_attese_tuv", Math.round(lossesTotal));
      m.addProperty("presa_obiettivo_pct", Math.round(Math.min(100, Math.max(0, take))));
      m.addProperty("tenuta_obiettivo_pct", Math.round(hold));
      if (!block.isEmpty()) {
        m.addProperty("blocco", block + (feasible ? ", colmabile con acquisti" : ""));
      }
      return m;
    }

    /** {@link ProBattleUtils#estimateStrengthDifference}'s formula on strengths. */
    static double diff(final double att, final double def) {
      if (att <= 0) {
        return 0;
      }
      if (def <= 0) {
        return 100;
      }
      return (att - def) / Math.pow(def, 0.85) * 50 + 50;
    }

    /** Attacker strength for {@code diff >= threshold} against {@code def}. */
    static double needed(final double def, final double threshold) {
      return def + (threshold - 50) / 50 * Math.pow(def, 0.85);
    }

    /** Factor on the defenders' strength for {@code diff(counter, def) <= 100 - soglia_tenuta}. */
    double neededHold(final double counter, final double def) {
      double k = 1;
      for (int i = 0; i < 40 && diff(counter, def * k) > 100 - cfg.sogliaTenuta(); i++) {
        k *= 1.15;
      }
      return k;
    }

    /** The cheapest units of {@code point} lost first, up to {@code loss} TUV. */
    List<Unit> superstiti(final List<Unit> point, final double loss) {
      final List<Unit> sorted = new ArrayList<>(point);
      sorted.sort(Comparator.comparingInt((Unit u) -> costs.getInt(u.getType())));
      double left = loss;
      final List<Unit> out = new ArrayList<>();
      for (final Unit u : sorted) {
        final int c = costs.getInt(u.getType());
        if (left >= c * 0.5 && left > 0) {
          left -= c;
        } else {
          out.add(u);
        }
      }
      return out;
    }

    /**
     * Defenders expected at {@code s} in {@code k} rounds: present + the enemy land units next to
     * it (share = commitment of a conquest, A3) + enemy purchases at factories within 2, k rounds.
     */
    List<Unit> difensori(final Territory s, final int k) {
      final List<Unit> out =
          new ArrayList<>(
              s.getMatches(Matches.unitIsNotInfrastructure().and(u -> nemico(u.getOwner()))));
      final double share =
          StimaUnica.impegno(data, alliance, s, StimaUnica.TipoBersaglio.CONQUISTA).frazione();
      final List<Unit> near = new ArrayList<>();
      for (final Territory n : map.getNeighbors(s, Matches.territoryIsLand())) {
        near.addAll(
            n.getMatches(
                Matches.unitIsLand()
                    .and(Matches.unitIsNotInfrastructure())
                    .and(u -> nemico(u.getOwner()))));
      }
      near.sort(Comparator.comparingInt((Unit u) -> -costs.getInt(u.getType())));
      final double total = tuv(near);
      double sum = 0;
      for (final Unit u : near) {
        if (sum >= total * share) {
          break;
        }
        out.add(u);
        sum += costs.getInt(u.getType());
      }
      out.addAll(acquistiNemici(s, k));
      return out;
    }

    /**
     * The enemy counterattack on {@code s} after the take: land units within 2, planes within 3.
     */
    List<Unit> contrattacco(final Territory s) {
      final List<Unit> all = new ArrayList<>();
      for (final Territory n : map.getNeighbors(s, 3)) {
        if (n.equals(s)) {
          continue;
        }
        final int d = map.getDistance(n, s);
        for (final Unit u :
            n.getMatches(Matches.unitIsNotInfrastructure().and(x -> nemico(x.getOwner())))) {
          final int mv = u.getUnitAttachment().getMovement(u.getOwner());
          if (Matches.unitIsLand().test(u) && !n.isWater() && d <= Math.min(2, mv)) {
            all.add(u);
          } else if (Matches.unitIsAir().test(u) && d <= Math.max(1, mv / 2)) {
            all.add(u);
          }
        }
      }
      final double share =
          StimaUnica.impegno(data, alliance, s, StimaUnica.TipoBersaglio.CONQUISTA).frazione();
      all.sort(
          Comparator.comparingInt((Unit u) -> map.getDistance(locate(u, s), s))
              .thenComparing(u -> -costs.getInt(u.getType())));
      final double total = tuv(all);
      final List<Unit> out = new ArrayList<>();
      double sum = 0;
      for (final Unit u : all) {
        if (sum >= total * share) {
          break;
        }
        out.add(u);
        sum += costs.getInt(u.getType());
      }
      out.addAll(acquistiNemici(s, 1));
      return out;
    }

    private Territory locate(final Unit u, final Territory fallback) {
      for (final Territory n : map.getNeighbors(fallback, 3)) {
        if (n.getUnitCollection().contains(u)) {
          return n;
        }
      }
      return fallback;
    }

    /** Temporary infantry the enemies may buy at their factories within 2 of {@code s}. */
    List<Unit> acquistiNemici(final Territory s, final int k) {
      final List<Unit> out = new ArrayList<>();
      final Optional<UnitType> inf = data.getUnitTypeList().getUnitType("infantry");
      if (inf.isEmpty() || cfg.quotaAcquistiNemici() <= 0) {
        return out;
      }
      final int cost = Math.max(1, costs.getInt(inf.get()));
      final Set<GamePlayer> seen = new LinkedHashSet<>();
      for (final Territory n : map.getNeighbors(s, 2)) {
        if (n.isWater()
            || !nemico(n.getOwner())
            || !n.anyUnitsMatch(Matches.unitCanProduceUnits())) {
          continue;
        }
        seen.add(n.getOwner());
      }
      for (final GamePlayer e : seen) {
        final int pus = e.getResources().getQuantity(Constants.PUS);
        final int n = (int) Math.floor(pus * cfg.quotaAcquistiNemici() * k / cost);
        if (n > 0) {
          out.addAll(inf.get().createTemp(Math.min(n, 30), e));
        }
      }
      return out;
    }

    // -------------------------------------------------------------------------- 2.6

    /**
     * Rule 2.6 for the own territories on the paths and behind the points (current round), with the
     * release of a garrison whose threat is gone.
     */
    JsonArray retrovia(
        final List<JsonObject> campaigns, final JsonObject st, final List<String> lines) {
      final Set<Territory> on = new LinkedHashSet<>();
      final Set<Territory> path = new LinkedHashSet<>();
      for (final JsonObject c : campaigns) {
        final Territory start = map.getTerritoryOrNull(c.get("partenza").getAsString());
        if (start != null) {
          on.add(start);
          for (final Territory n : map.getNeighbors(start, 2)) {
            if (!n.isWater() && n.isOwnedBy(player)) {
              on.add(n);
            }
          }
        }
        for (final JsonElement e : c.getAsJsonArray("tappe")) {
          final Territory t =
              map.getTerritoryOrNull(e.getAsJsonObject().get("territorio").getAsString());
          if (t != null) {
            path.add(t);
            if (t.isOwnedBy(player)) {
              on.add(t);
            }
          }
        }
      }
      final List<Territory> list = new ArrayList<>(on);
      list.sort(
          Comparator.comparingDouble((Territory t) -> -valoreIndice(t))
              .thenComparing(Territory::getName));
      final List<Territory> chosen =
          list.subList(0, Math.min(cfg.maxTerritoriRetrovia(), list.size()));
      final JsonArray out = new JsonArray();
      if (chosen.isEmpty()) {
        return out;
      }
      final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
      tm.populateEnemyAttackOptions(List.of(), new ArrayList<>(chosen));
      final double campaignValue =
          campaigns.stream()
              .mapToDouble(
                  c -> valoreIndice(map.getTerritoryOrNull(c.get("obiettivo").getAsString())))
              .max()
              .orElse(0);
      final double probTake =
          campaigns.stream()
              .mapToDouble(
                  c ->
                      c.getAsJsonObject("margini")
                          .getAsJsonObject(String.valueOf(cfg.marginePresidio()))
                          .get("presa_obiettivo_pct")
                          .getAsDouble())
              .max()
              .orElse(0);
      final double pointTuv =
          campaigns.stream()
              .mapToDouble(
                  c ->
                      c.getAsJsonObject("margini")
                          .getAsJsonObject(String.valueOf(cfg.marginePresidio()))
                          .get("forza_disponibile_tuv")
                          .getAsDouble())
              .max()
              .orElse(0);
      final Map<String, String> before = casiPrima(st);
      lines.add("  Regola 2.6 (retrovie e percorso, R" + round + "):");
      for (final Territory t : chosen) {
        final ProTerritory max = tm.getEnemyAttackOptions().getMax(t);
        final List<Unit> enemyLand = new ArrayList<>();
        if (max != null) {
          max.getMaxUnits().stream()
              .filter(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()))
              .forEach(enemyLand::add);
          max.getMaxAmphibUnits().stream()
              .filter(Matches.unitIsLand().and(u -> !enemyLand.contains(u)))
              .forEach(enemyLand::add);
        }
        final JsonObject x =
            classifica(t, enemyLand, path.contains(t), campaignValue, probTake, pointTuv);
        final String prev = before.get(t.getName());
        if (rilasciato(prev, x.get("caso").getAsString())) {
          x.addProperty("rilasciato", true);
        }
        out.add(x);
        lines.add(
            "    "
                + t.getName()
                + ": caso "
                + x.get("caso").getAsString()
                + ", "
                + x.get("unita").getAsInt()
                + " unita' ("
                + x.get("composizione").getAsString()
                + ") - "
                + x.get("motivo").getAsString()
                + String.format(
                    Locale.ROOT,
                    " [perdita attesa senza presidio %.1f, costo del presidio %.1f]",
                    x.get("perdita_attesa_senza_presidio").getAsDouble(),
                    x.get("costo_presidio").getAsDouble())
                + (x.has("rilasciato") ? " RILASCIATO (minaccia sparita)" : ""));
      }
      return out;
    }

    /**
     * The three cases of rule 2.6 for {@code t} with the enemy land units that can reach it before
     * the nation's next turn ({@code enemyLand}, landings included, planes alone never).
     */
    JsonObject classifica(
        final Territory t,
        final List<Unit> enemyLand,
        final boolean onPath,
        final double campaignValue,
        final double probTake,
        final double pointTuv) {
      final JsonObject x = new JsonObject();
      x.addProperty("territorio", t.getName());
      x.addProperty("round", round);
      x.addProperty("nazione", player.getName());
      final boolean counts = conta(t, onPath);
      x.addProperty("conta", counts);
      final double valuePerRound =
          TerritoryAttachment.get(t).map(a -> a.getProduction()).orElse(0) * 2.0;
      final double lossWithout = valuePerRound * cfg.roundRiprendere();
      final double enemyTuv = tuv(enemyLand);
      x.addProperty("nemici_terra_tuv", Math.round(enemyTuv));
      if (enemyLand.isEmpty()) {
        return caso(x, CASO_A, List.of(), "nessuna unita' di terra nemica puo' arrivarci", 0, 0);
      }
      final Optional<UnitType> inf = data.getUnitTypeList().getUnitType("infantry");
      final double infCost = inf.map(costs::getInt).orElse(3);
      if (enemyTuv <= cfg.forzaPiccolaTuv() || !counts) {
        final double garrisonCost =
            pointTuv <= 0 ? infCost : infCost / pointTuv * probTake / 100 * campaignValue;
        final boolean blitz =
            enemyLand.stream().anyMatch(u -> u.getUnitAttachment().getMovement(u.getOwner()) >= 2)
                && map.getNeighbors(t, Matches.territoryIsLand()).stream()
                    .anyMatch(n -> n.isOwnedBy(player) && conta(n, false));
        if (enemyTuv <= cfg.forzaPiccolaTuv() && blitz && inf.isPresent()) {
          return caso(
              x,
              CASO_B,
              List.of("infantry"),
              "forza piccola con carri in portata di blitz verso un territorio che conta",
              lossWithout,
              garrisonCost);
        }
        if (enemyTuv <= cfg.forzaPiccolaTuv()) {
          return caso(
              x,
              CASO_B,
              List.of(),
              lossWithout <= garrisonCost
                  ? "forza piccola: riprenderlo costa meno che presidiarlo"
                  : "forza piccola, nessun blitz verso un territorio che conta",
              lossWithout,
              garrisonCost);
        }
        return caso(
            x,
            CASO_B,
            List.of(),
            "minaccia reale su un territorio che non conta: si accetta di perderlo",
            lossWithout,
            garrisonCost);
      }
      // c) the minimal garrison of infantry and artillery that holds at the margin
      final List<String> types = presidioMinimo(t, enemyLand);
      final double garrisonTuv =
          types.stream()
              .mapToDouble(n -> data.getUnitTypeList().getUnitType(n).map(costs::getInt).orElse(3))
              .sum();
      final double garrisonCost =
          pointTuv <= 0 ? garrisonTuv : garrisonTuv / pointTuv * probTake / 100 * campaignValue;
      return caso(
          x,
          CASO_C,
          types,
          "minaccia di terra reale su un territorio che conta: presidio minimo (fanteria e"
              + " artiglieria, mai carri ne' aerei; da unita' prodotte li', rinforzi in transito,"
              + " unita' lente della punta)"
              + (types.size() >= cfg.maxPresidio()
                  ? "; con il massimo di " + cfg.maxPresidio() + " unita' NON regge al margine"
                  : ""),
          lossWithout + valoreIndice(t),
          garrisonCost);
    }

    private JsonObject caso(
        final JsonObject x,
        final String caso,
        final List<String> types,
        final String why,
        final double lossWithout,
        final double garrisonCost) {
      x.addProperty("caso", caso);
      x.addProperty("unita", types.size());
      final Map<String, Integer> byType = new LinkedHashMap<>();
      types.forEach(n -> byType.merge(n, 1, Integer::sum));
      x.addProperty(
          "composizione",
          types.isEmpty()
              ? "nessuna"
              : String.join(
                  ", ",
                  byType.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList()));
      x.addProperty("motivo", why);
      x.addProperty("perdita_attesa_senza_presidio", Math.round(lossWithout * 10) / 10.0);
      x.addProperty("costo_presidio", Math.round(garrisonCost * 10) / 10.0);
      return x;
    }

    /** Fewest infantry/artillery (alternating, infantry first) that hold at the garrison margin. */
    List<String> presidioMinimo(final Territory t, final List<Unit> enemyLand) {
      final double f = cfg.fattore(cfg.marginePresidio());
      // the garrison is counted from scratch in infantry and artillery (never tanks nor planes);
      // the allies' units there stay and count
      final List<Unit> others =
          t.getMatches(
              Matches.isUnitAllied(player)
                  .and(Matches.unitIsOwnedBy(player).negate())
                  .and(Matches.unitIsNotInfrastructure()));
      final List<String> names = new ArrayList<>();
      final List<Unit> temp = new ArrayList<>(others);
      for (int i = 0; i < cfg.maxPresidio(); i++) {
        final double sC = ProBattleUtils.estimateStrength(t, enemyLand, temp, true) * f;
        final double sD =
            temp.isEmpty() ? 0 : ProBattleUtils.estimateStrength(t, temp, enemyLand, false);
        if (!temp.isEmpty() && diff(sC, sD) <= 100 - cfg.sogliaTenuta()) {
          break;
        }
        final String name = names.size() % 2 == 1 ? "artillery" : "infantry";
        final Optional<UnitType> type = data.getUnitTypeList().getUnitType(name);
        if (type.isEmpty()) {
          break;
        }
        names.add(name);
        temp.addAll(type.get().createTemp(1, player));
      }
      return names;
    }

    /** VC/capital, own factory, on the line of the reinforcements, or a national objective. */
    boolean conta(final Territory t, final boolean onPath) {
      final Optional<TerritoryAttachment> ta = TerritoryAttachment.get(t);
      if (ta.isPresent() && (ta.get().getVictoryCity() > 0 || ta.get().isCapital())) {
        return true;
      }
      if (t.anyUnitsMatch(Matches.unitCanProduceUnits()) && t.isOwnedBy(player)) {
        return true;
      }
      if (onPath) {
        return true;
      }
      return games.strategy.triplea.ai.pro.util.ProNationalObjectiveUtils.stake(player, t) > 0;
    }

    // -------------------------------------------------------------------------- 2.7

    JsonObject acquisti(
        final Obiettivo o, final List<Integer> rounds, final JsonObject m, final double perRound) {
      final JsonObject b = new JsonObject();
      final double gap = m.get("scarto_tuv").getAsDouble();
      final int deadline =
          rounds.isEmpty() ? round : Math.min(round + 20, rounds.get(rounds.size() - 1));
      final List<Territory> factories =
          map.getTerritories().stream()
              .filter(ProMatches.territoryHasFactoryAndIsNotConqueredOwnedLand(player))
              .toList();
      final JsonArray perRoundOut = new JsonArray();
      final StringBuilder text = new StringBuilder();
      double left = gap;
      for (int r = round; r < deadline && left > 0; r++) {
        // the staging point of the round: the last stage reached before r + 1
        Territory stage = o.percorso().get(o.percorso().size() - 1);
        for (int i = 0; i < o.percorso().size(); i++) {
          if (rounds.get(i) >= r + 1) {
            stage = o.percorso().get(i);
            break;
          }
        }
        Territory bestF = null;
        String bestType = null;
        double bestScore = -1;
        for (final Territory fct : factories) {
          for (final String name : List.of("infantry", "artillery", "armour")) {
            final Optional<UnitType> type = data.getUnitTypeList().getUnitType(name);
            if (type.isEmpty()) {
              continue;
            }
            final int mv = Math.max(1, type.get().getUnitAttachment().getMovement(player));
            final int d = distanza(fct, stage);
            if (d < 0 || r + 1 + (int) Math.ceil(d / (double) mv) - 1 > deadline) {
              continue; // does not arrive in time
            }
            final int cost = Math.max(1, costs.getInt(type.get()));
            // early: cheap and slow; near the deadline: fast
            final double att = type.get().getUnitAttachment().getAttack(player) + 2.0;
            final double score = att / cost * (deadline - r <= 1 ? mv : 1) - d * 0.01;
            if (score > bestScore) {
              bestScore = score;
              bestF = fct;
              bestType = name;
            }
          }
        }
        if (bestF == null) {
          continue;
        }
        final int cost =
            Math.max(1, costs.getInt(data.getUnitTypeList().getUnitTypeOrThrow(bestType)));
        final int n =
            (int) Math.max(0, Math.min(Math.ceil(left / cost), Math.floor(perRound / cost)));
        if (n <= 0) {
          continue;
        }
        left -= n * cost;
        final JsonObject x = new JsonObject();
        x.addProperty("round", r);
        x.addProperty("tipo", bestType);
        x.addProperty("numero", n);
        x.addProperty("fabbrica", bestF.getName());
        x.addProperty("ammassamento", stage.getName());
        x.addProperty("indicativo", r > round);
        perRoundOut.add(x);
        text.append(r == round ? "ora " : "R" + r + " ")
            .append(n)
            .append(' ')
            .append(bestType)
            .append(" a ")
            .append(bestF.getName())
            .append(" -> ")
            .append(stage.getName())
            .append("; ");
      }
      b.add("per_round", perRoundOut);
      b.addProperty("scarto_residuo_tuv", Math.round(Math.max(0, left)));
      b.addProperty(
          "testo",
          gap <= 0
              ? "nessun acquisto necessario"
              : text.length() == 0 ? "nessuna unita' arriva in tempo" : text.toString().trim());
      return b;
    }

    // -------------------------------------------------------------------------- 2.8

    JsonObject verifica(final JsonObject st, final List<String> lines) {
      final JsonObject stats =
          st.has("statistiche") ? st.getAsJsonObject("statistiche") : new JsonObject();
      final JsonObject v = new JsonObject();
      final JsonArray errs = new JsonArray();
      final JsonArray battles = new JsonArray();
      if (st.has("previsioni")) {
        for (final JsonElement e : st.getAsJsonArray("previsioni")) {
          final JsonObject p = e.getAsJsonObject();
          if (!p.get("nazione").getAsString().equals(player.getName())) {
            continue;
          }
          final int made = p.get("round").getAsInt();
          final int k = round - made;
          if (k < 1 || k > 3) {
            continue;
          }
          // defenders on the target and the stages
          final JsonObject defs = p.getAsJsonObject("difensori");
          for (final var d : defs.entrySet()) {
            final JsonObject byRound = d.getValue().getAsJsonObject();
            if (!byRound.has(String.valueOf(round))) {
              continue;
            }
            final Territory t = map.getTerritoryOrNull(d.getKey());
            if (t == null) {
              continue;
            }
            final double pred = byRound.get(String.valueOf(round)).getAsDouble();
            final double real =
                tuv(t.getMatches(Matches.unitIsNotInfrastructure().and(u -> nemico(u.getOwner()))));
            final JsonObject x = new JsonObject();
            x.addProperty("territorio", t.getName());
            x.addProperty("orizzonte", k);
            x.addProperty("previsti_tuv", pred);
            x.addProperty("reali_tuv", Math.round(real));
            errs.add(x);
            aggiungi(stats, "difensori_" + k, Math.abs(pred - real), real);
          }
          if (p.has("reddito") && p.getAsJsonObject("reddito").has(String.valueOf(round))) {
            final double pred =
                p.getAsJsonObject("reddito").get(String.valueOf(round)).getAsDouble();
            final double real = ProUtils.getPlayerProduction(player, data);
            aggiungi(stats, "reddito_" + k, Math.abs(pred - real), real);
          }
          if (p.has("forza") && p.getAsJsonObject("forza").has(String.valueOf(round))) {
            final double pred = p.getAsJsonObject("forza").get(String.valueOf(round)).getAsDouble();
            final double real = tuv(tutteMie());
            aggiungi(stats, "forza_" + k, Math.abs(pred - real), real);
          }
          // battles of the nation on the first stage predicted for the round the prediction
          // was made (k == 1: the turn is over)
          if (k == 1 && p.has("perdite_tappa1")) {
            final JsonObject pl = p.getAsJsonObject("perdite_tappa1");
            final String site = pl.get("territorio").getAsString();
            final int lost = perditaReale(made, site);
            if (lost >= 0) {
              final JsonObject b = new JsonObject();
              b.addProperty("territorio", site);
              b.addProperty("perdita_reale_tuv", lost);
              for (final int margin : cfg.margini()) {
                final double pred = pl.get(String.valueOf(margin)).getAsDouble();
                b.addProperty("prevista_" + margin, pred);
                conta(stats, "battaglie_" + margin, lost <= pred);
              }
              battles.add(b);
            }
          }
        }
      }
      // rule 2.6: attacked / lost in the enemy turns after the classification, units left
      if (st.has("retrovia")) {
        for (final JsonElement e : st.getAsJsonArray("retrovia")) {
          final JsonObject r = e.getAsJsonObject();
          if (!r.get("nazione").getAsString().equals(player.getName())
              || r.get("round").getAsInt() != round - 1
              || r.has("verificato")) {
            continue;
          }
          final Territory t = map.getTerritoryOrNull(r.get("territorio").getAsString());
          if (t == null) {
            continue;
          }
          final boolean attacked = attaccato(t, round - 1) || attaccato(t, round);
          final boolean lost = !Matches.isTerritoryAllied(player).test(t);
          final String caso = r.get("caso").getAsString();
          final JsonObject c =
              stats.has("caso_" + caso) ? stats.getAsJsonObject("caso_" + caso) : new JsonObject();
          inc(c, "n", 1);
          inc(c, "attaccati", attacked ? 1 : 0);
          inc(c, "persi", lost ? 1 : 0);
          inc(c, "presidio_previsto", r.get("unita").getAsInt());
          inc(
              c,
              "unita_lasciate_da_hard",
              r.has("unita_lasciate_da_hard") ? r.get("unita_lasciate_da_hard").getAsInt() : 0);
          stats.add("caso_" + caso, c);
          r.addProperty("verificato", true);
        }
      }
      st.add("statistiche", stats);
      v.add("difensori", errs);
      v.add("battaglie", battles);
      if (errs.size() > 0 || battles.size() > 0) {
        lines.add(
            "  Verifica delle previsioni: "
                + errs.size()
                + " difensori confrontati, "
                + battles.size()
                + " battaglie confrontate");
      }
      return v;
    }

    private static void aggiungi(
        final JsonObject stats, final String key, final double absErr, final double real) {
      final JsonArray a = stats.has(key) ? stats.getAsJsonArray(key) : new JsonArray();
      final JsonArray x = new JsonArray();
      x.add(Math.round(absErr * 10) / 10.0);
      x.add(Math.round(real * 10) / 10.0);
      a.add(x);
      while (a.size() > 200) {
        a.remove(0);
      }
      stats.add(key, a);
    }

    private static void conta(final JsonObject stats, final String key, final boolean under) {
      final JsonObject c = stats.has(key) ? stats.getAsJsonObject(key) : new JsonObject();
      inc(c, "n", 1);
      inc(c, "sotto_il_previsto", under ? 1 : 0);
      stats.add(key, c);
    }

    private static void inc(final JsonObject o, final String key, final int by) {
      o.addProperty(key, (o.has(key) ? o.get(key).getAsInt() : 0) + by);
    }

    /** TUV lost by the nation attacking {@code site} in {@code r} (-1 = no battle there). */
    int perditaReale(final int r, final String site) {
      final var all = data.getBattleRecordsList();
      if (all == null || all.getBattleRecordsMapCopy().get(r) == null) {
        return -1;
      }
      int lost = -1;
      for (final BattleRecord b :
          BattleRecords.getAllRecords(all.getBattleRecordsMapCopy().get(r))) {
        final Territory t = field(b, "battleSite");
        final GamePlayer att = field(b, "attacker");
        if (t != null && att != null && t.getName().equals(site) && att.equals(player)) {
          final Integer l = field(b, "attackerLostTuv");
          lost = Math.max(0, lost) + (l == null ? 0 : l);
        }
      }
      return lost;
    }

    /** A battle in {@code t} in round {@code r} with an attacker of another alliance. */
    boolean attaccato(final Territory t, final int r) {
      final var all = data.getBattleRecordsList();
      if (all == null || all.getBattleRecordsMapCopy().get(r) == null) {
        return false;
      }
      for (final BattleRecord b :
          BattleRecords.getAllRecords(all.getBattleRecordsMapCopy().get(r))) {
        final Territory site = field(b, "battleSite");
        final GamePlayer att = field(b, "attacker");
        if (site != null && site.equals(t) && att != null && nemico(att)) {
          return true;
        }
      }
      return false;
    }

    // ------------------------------------------------------------------------- state

    void aggiornaStato(
        final JsonObject st,
        final List<JsonObject> campaigns,
        final JsonArray retrovia,
        final double income) {
      // predictions of this turn (verified at +1..+3), old ones pruned
      final JsonArray prev = new JsonArray();
      if (st.has("previsioni")) {
        for (final JsonElement e : st.getAsJsonArray("previsioni")) {
          final JsonObject p = e.getAsJsonObject();
          if (p.get("round").getAsInt() >= round - 3
              && !(p.get("nazione").getAsString().equals(player.getName())
                  && p.get("round").getAsInt() == round)) {
            prev.add(p);
          }
        }
      }
      for (final JsonObject c : campaigns) {
        final JsonObject p = new JsonObject();
        p.addProperty("round", round);
        p.addProperty("nazione", player.getName());
        p.addProperty("obiettivo", c.get("obiettivo").getAsString());
        final JsonObject pr = c.getAsJsonObject("previsioni");
        p.add("difensori", pr.get("difensori"));
        p.add("reddito", pr.get("reddito"));
        p.add("forza", pr.get("forza_propria_tuv"));
        // expected losses of the first battle stage this round, per margin
        final JsonObject pl = new JsonObject();
        boolean has = false;
        for (final int margin : cfg.margini()) {
          for (final JsonElement s :
              c.getAsJsonObject("margini")
                  .getAsJsonObject(String.valueOf(margin))
                  .getAsJsonArray("tappe")) {
            final JsonObject x = s.getAsJsonObject();
            if (x.get("battaglia").getAsBoolean() && x.get("round").getAsInt() == round) {
              pl.addProperty("territorio", x.get("tappa").getAsString());
              pl.addProperty(String.valueOf(margin), x.get("perdite_attese_tuv").getAsDouble());
              has = true;
              break;
            }
          }
        }
        if (has && pl.size() == cfg.margini().size() + 1) {
          p.add("perdite_tappa1", pl);
        }
        prev.add(p);
      }
      while (prev.size() > cfg.maxPrevisioni()) {
        prev.remove(0);
      }
      st.add("previsioni", prev);
      // rule 2.6 records: this turn and the last one (for the check)
      final JsonArray keep = new JsonArray();
      if (st.has("retrovia")) {
        for (final JsonElement e : st.getAsJsonArray("retrovia")) {
          final JsonObject r = e.getAsJsonObject();
          if (r.get("round").getAsInt() >= round - 1
              && !(r.get("nazione").getAsString().equals(player.getName())
                  && r.get("round").getAsInt() == round)) {
            keep.add(r);
          }
        }
      }
      for (final JsonElement e : retrovia) {
        final JsonObject r = e.getAsJsonObject().deepCopy();
        for (final String k : List.of("motivo", "composizione")) {
          r.remove(k);
        }
        keep.add(r);
      }
      st.add("retrovia", keep);
      // feasible candidates per round and margin (the summary)
      final JsonArray feas = st.has("fattibili") ? st.getAsJsonArray("fattibili") : new JsonArray();
      for (final JsonObject c : campaigns) {
        for (final int margin : cfg.margini()) {
          final JsonObject m = c.getAsJsonObject("margini").getAsJsonObject(String.valueOf(margin));
          final JsonObject x = new JsonObject();
          x.addProperty("round", round);
          x.addProperty("nazione", player.getName());
          x.addProperty("obiettivo", c.get("obiettivo").getAsString());
          x.addProperty("margine", margin);
          x.addProperty("fattibile", m.get("fattibile").getAsBoolean());
          x.addProperty("round_stimato", m.get("round_stimato").getAsInt());
          x.addProperty("quota_pu_pct", m.get("quota_pu_pct").getAsDouble());
          feas.add(x);
        }
      }
      while (feas.size() > 300) {
        feas.remove(0);
      }
      st.add("fattibili", feas);
      final JsonArray rounds =
          st.has("round_con_blocco") ? st.getAsJsonArray("round_con_blocco") : new JsonArray();
      if (!rounds.contains(new com.google.gson.JsonPrimitive(round))) {
        rounds.add(round);
      }
      st.add("round_con_blocco", rounds);
      st.addProperty("ultimo_reddito_" + player.getName(), Math.round(income));
    }

    private Map<String, String> casiPrima(final JsonObject st) {
      final Map<String, String> out = new HashMap<>();
      if (st.has("retrovia")) {
        for (final JsonElement e : st.getAsJsonArray("retrovia")) {
          final JsonObject r = e.getAsJsonObject();
          if (r.get("nazione").getAsString().equals(player.getName())
              && r.get("round").getAsInt() == round - 1) {
            out.put(r.get("territorio").getAsString(), r.get("caso").getAsString());
          }
        }
      }
      return out;
    }

    void scriviFile(final JsonObject out) {
      if (!(data instanceof GameData gd)) {
        return;
      }
      // one file per alliance and round: the nations of the round together
      final String name = String.format(Locale.ROOT, "campagna-ombra-R%d-%s.json", round, alliance);
      final JsonObject file = new JsonObject();
      file.addProperty("round", round);
      file.addProperty("alleanza", alliance);
      file.addProperty("nota", "IN OMBRA: nessun effetto sul gioco");
      final JsonObject nations =
          FILE.computeIfAbsent(name + "|" + System.identityHashCode(gd), k -> new JsonObject());
      nations.add(player.getName(), out);
      file.add("nazioni", nations);
      DiaryRecorder.operationInput(gd, name, file);
    }

    // ------------------------------------------------------------------------- helpers

    /** An owner of another alliance (not a neutral) that a nation of the alliance may fight. */
    boolean nemico(final GamePlayer owner) {
      return owner != null
          && !owner.isNull()
          && ProPlusSettings.allianceOf(data, owner).filter(a -> !a.equals(alliance)).isPresent();
    }

    /** First round the nation can be at war with {@code enemy} (-1 = not within the horizon). */
    int roundGuerra(final GamePlayer enemy) {
      if (data.getRelationshipTracker().isAtWar(player, enemy)) {
        return round;
      }
      if (OperazioniCongiunte.guerraPossibile(data, player, enemy)) {
        return round;
      }
      final Optional<Strategia> s = Strategie.forGame(data);
      if (s.isPresent() && s.get().alleanza(alliance).isPresent()) {
        int best = -1;
        for (final Strategia.Piano p : s.get().alleanza(alliance).get().piani()) {
          final Optional<Strategia.Dichiarazione> d =
              p.dichiarazione(player.getName(), enemy.getName());
          if (d.isPresent() && (best < 0 || d.get().dalRound() < best)) {
            best = d.get().dalRound();
          }
        }
        if (best >= 0 && best <= round + cfg.orizzonteRound()) {
          return Math.max(round + 1, best);
        }
      }
      return -1;
    }

    /** Plans of the doctrine with {@code t} as an objective or a milestone. */
    JsonArray piani(final Territory t) {
      final JsonArray out = new JsonArray();
      final Optional<Strategia> s = Strategie.forGame(data);
      if (s.isEmpty() || s.get().alleanza(alliance).isEmpty()) {
        return out;
      }
      for (final Strategia.Piano p : s.get().alleanza(alliance).get().piani()) {
        final boolean obj =
            p.fasi().stream()
                .flatMap(f -> f.obiettivi().stream())
                .anyMatch(o -> t.getName().equals(o.territorio()));
        final boolean milestone =
            p.tappe().stream().anyMatch(x -> x.territori().contains(t.getName()));
        if (obj || milestone) {
          out.add(p.id());
        }
      }
      return out;
    }

    /** The nation's land units able to fight and its attacking planes. */
    List<Unit> unitaCampagna() {
      final List<Unit> out = new ArrayList<>();
      for (final Territory t : map.getTerritories()) {
        out.addAll(
            t.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(Matches.unitIsNotInfrastructure())
                    .and(
                        u ->
                            (Matches.unitIsLand().test(u) && !t.isWater())
                                || (Matches.unitIsAir().test(u)
                                    && u.getUnitAttachment().getAttack(player) > 0))
                    .and(
                        u ->
                            u.getUnitAttachment().getAttack(player) > 0
                                || u.getUnitAttachment().getDefense(player) > 0)));
      }
      return out;
    }

    List<Unit> tutteMie() {
      final List<Unit> out = new ArrayList<>();
      map.getTerritories()
          .forEach(
              t ->
                  out.addAll(
                      t.getMatches(
                          Matches.unitIsOwnedBy(player).and(Matches.unitIsNotInfrastructure()))));
      return out;
    }

    /** Round a unit at {@code from} can be at {@code stage} (2.3: distance / movement). */
    int arrivo(final Unit u, final Territory from, final Territory stage) {
      final int mv = Math.max(1, u.getUnitAttachment().getMovement(u.getOwner()));
      if (Matches.unitIsAir().test(u)) {
        final int d = from.equals(stage) ? 0 : map.getDistance(from, stage);
        if (d < 0) {
          return Integer.MAX_VALUE / 2;
        }
        final int perRound = Math.max(1, mv - 1);
        return round + Math.max(0, (int) Math.ceil(d / (double) perRound) - 1);
      }
      final int d = distanza(from, stage);
      if (d < 0) {
        return Integer.MAX_VALUE / 2;
      }
      return round + Math.max(0, (int) Math.ceil(d / (double) mv) - 1);
    }

    /** Land distance through conquerable territories (memoized), -1 if none. */
    int distanza(final Territory a, final Territory b) {
      if (a.equals(b)) {
        return 0;
      }
      return distMemo.computeIfAbsent(
          a.getName() + ">" + b.getName(), k -> map.getDistance(a, b, passable));
    }

    double tuv(final Collection<Unit> units) {
      double sum = 0;
      for (final Unit u : units) {
        sum += costs.getInt(u.getType());
      }
      return sum;
    }

    double tuvProprio(final Territory t) {
      return tuv(t.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsLand())));
    }

    static double valoreIndice(@Nullable final Territory t) {
      if (t == null) {
        return 0;
      }
      return TerritoryAttachment.get(t)
          .map(
              a ->
                  a.getProduction()
                      + (a.getVictoryCity() > 0 ? 10.0 : 0)
                      + (a.isCapital() ? 10.0 : 0))
          .orElse(0.0);
    }

    /** PUs of Hard's defense in this nation's last recorded turn (C2-bis budget), 0 if none. */
    double difesaHardUltimoTurno(final GameProperties props) {
      final JsonObject reg = Difesa.registro(props, alliance);
      if (!reg.has("turni")) {
        return 0;
      }
      final JsonArray turns = reg.getAsJsonArray("turni");
      for (int i = turns.size() - 1; i >= 0; i--) {
        final JsonObject t = turns.get(i).getAsJsonObject();
        if (t.has("nazione")
            && t.get("nazione").getAsString().equals(player.getName())
            && t.has("reale")
            && t.getAsJsonObject("reale").has("pu_difesa_hard")) {
          return t.getAsJsonObject("reale").get("pu_difesa_hard").getAsDouble();
        }
      }
      return 0;
    }
  }

  /** The nations of a round's file so far (diary only). */
  private static final Map<String, JsonObject> FILE =
      java.util.Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, JsonObject> e) {
              return size() > 8;
            }
          });

  /** The battle record's data has no public getters: read the fields. */
  @SuppressWarnings("unchecked")
  @Nullable
  private static <T> T field(final BattleRecord record, final String name) {
    try {
      final Field f = BattleRecord.class.getDeclaredField(name);
      f.setAccessible(true);
      return (T) f.get(record);
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }
}
