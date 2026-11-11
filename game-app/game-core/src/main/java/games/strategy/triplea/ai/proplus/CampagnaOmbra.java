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
import games.strategy.triplea.odds.calculator.IBattleCalculator;
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
      int maxPrevisioni,
      Bis bis) {

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
          o.get("max_previsioni").getAsInt(),
          Bis.parse(o.getAsJsonObject("r2a_bis")));
    }

    double fattore(final int margin) {
      return fattori.getOrDefault(margin, 1.0);
    }
  }

  /**
   * Step R2a-bis: values of {@code campagna_ombra.r2a_bis} (each with its origin in the file:
   * measured or chosen; none tuned by hand).
   *
   * @param simulazioni runs of the real calculator per battle
   * @param ripetizioni calls with one run each when the per-run results cannot be read
   * @param quotaM2Iniziale share of the predicted increase of M2 before any check in the game
   * @param quotaM2Min lowest share of M2
   * @param quotaM2Max highest share of M2
   * @param minConfrontiModello checks per model before the error picks the model of 1.5
   * @param modelloIniziale model of 1.5 before that
   * @param maxErroriModello abs errors kept per model in the state (the last ones)
   * @param maxPunte points kept for the counterattack check (1.3)
   * @param maxPersi lost rear territories kept (case b)
   */
  record Bis(
      int simulazioni,
      int ripetizioni,
      double quotaM2Iniziale,
      double quotaM2Min,
      double quotaM2Max,
      int minConfrontiModello,
      String modelloIniziale,
      int maxErroriModello,
      int maxPunte,
      int maxPersi) {

    static Bis parse(@Nullable final JsonObject o) {
      if (o == null) {
        return new Bis(100, 30, 0.5, 0, 2, 10, "m2", 200, 60, 100);
      }
      return new Bis(
          valore(o, "simulazioni_calcolatore").getAsInt(),
          valore(o, "ripetizioni_senza_lista").getAsInt(),
          valore(o, "quota_m2_iniziale").getAsDouble(),
          valore(o, "quota_m2_min").getAsDouble(),
          valore(o, "quota_m2_max").getAsDouble(),
          valore(o, "min_confronti_modello").getAsInt(),
          valore(o, "modello_iniziale").getAsString(),
          valore(o, "max_errori_modello").getAsInt(),
          valore(o, "max_punte").getAsInt(),
          valore(o, "max_persi").getAsInt());
    }

    /** {@code {"valore": x, "origine": "..."}}: the value. */
    private static JsonElement valore(final JsonObject o, final String key) {
      return o.getAsJsonObject(key).get("valore");
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
      if (alliance.isEmpty()
          || !Stratega.gioca(data, player)
          || !StrategaInterruttori.OMBRA.acceso(data.getProperties(), alliance.get())) {
        return;
      }
      final GameProperties props = data.getProperties();
      final JsonObject st = stato(props, alliance.get());
      final int round = data.getSequence().getRound();
      if (!st.has("retrovia")) {
        return;
      }
      // R2a-bis: the points taken this turn (1.3), the units Hard left behind (Measure 2), the
      // rear territories lost and retaken
      final Calcolo c =
          new Calcolo(
              data,
              new games.strategy.triplea.util.TuvCostsCalculator().getCostsForTuv(player),
              player,
              alliance.get(),
              Config.get());
      c.fineTurnoBis(st);
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
    for (final String k :
        List.of("statistiche", "fattibili", "round_con_blocco", "quote_m2", "misura2", "persi")) {
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
    @Nullable final ProData proData;
    @Nullable final ProOddsCalculator calc;
    final GamePlayer player;
    final String alliance;
    final Config cfg;
    final GameState data;
    final GameMap map;
    final int round;
    final IntegerMap<UnitType> costs;
    final Predicate<Territory> passable;
    final Map<String, Integer> distMemo = new HashMap<>();
    // step R2a-bis
    final Map<String, CampagnaOmbraMisure.Distribuzione> cacheCalcolatore = new HashMap<>();
    final Optional<IBattleCalculator> calcolatore;
    int chiamateCalcolatore;
    @Nullable String erroreCalcolatore;

    /** Share of M2 per horizon 1..3 (index 0 unused). */
    final double[] quoteM2 = new double[4];

    /** Model of the recomputed feasibility (1.5). */
    String modello;

    Calcolo(
        final ProData proData,
        final ProOddsCalculator calc,
        final GamePlayer player,
        final String alliance,
        final Config cfg) {
      this(proData, calc, proData.getData(), proData.getUnitValueMap(), player, alliance, cfg);
    }

    /** Without the AI's data (end of the turn): no calculator, no Hard's enemy attack options. */
    Calcolo(
        final GameState data,
        final IntegerMap<UnitType> costs,
        final GamePlayer player,
        final String alliance,
        final Config cfg) {
      this(null, null, data, costs, player, alliance, cfg);
    }

    private Calcolo(
        @Nullable final ProData proData,
        @Nullable final ProOddsCalculator calc,
        final GameState data,
        final IntegerMap<UnitType> costs,
        final GamePlayer player,
        final String alliance,
        final Config cfg) {
      this.proData = proData;
      this.calc = calc;
      this.player = player;
      this.alliance = alliance;
      this.cfg = cfg;
      this.data = data;
      this.map = data.getMap();
      this.round = data.getSequence().getRound();
      this.costs = costs;
      this.calcolatore = CampagnaOmbraMisure.calcolatore(calc);
      java.util.Arrays.fill(quoteM2, cfg.bis().quotaM2Iniziale());
      this.modello = cfg.bis().modelloIniziale();
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
      // R2a-bis: the share of M2 and the model of 1.5 from the checks so far; the start of the
      // turn for the end of the turn (1.3, Measure 2)
      leggiModelli(st);
      inizioTurno(st);
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
      out.addProperty("modello_difensori_v2", modello);
      final JsonObject q = new JsonObject();
      for (int k = 1; k <= 3; k++) {
        q.addProperty(String.valueOf(k), Math.round(quoteM2[k] * 1000) / 1000.0);
      }
      out.add("quote_m2", q);
      out.addProperty("chiamate_calcolatore", chiamateCalcolatore);
      if (erroreCalcolatore != null) {
        out.addProperty("errore_calcolatore", erroreCalcolatore);
      }
      lines.add(
          String.format(
              Locale.ROOT,
              "  R2a-bis: difensori del ricalcolo = %s (quota M2 +1/+2/+3: %.2f / %.2f / %.2f);"
                  + " chiamate al calcolatore %d",
              modello.toUpperCase(Locale.ROOT),
              quoteM2[1],
              quoteM2[2],
              quoteM2[3],
              chiamateCalcolatore));
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

    /**
     * Step R2b: the own territories with own land units (the fronts of 2.1), and the best land
     * route from one of them to {@code t} within the horizon (the same rule as {@link #obiettivi}).
     */
    List<Territory> fronti() {
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
      return fronts;
    }

    /** Step R2b: see {@link #fronti}. */
    Optional<Obiettivo> obiettivoPer(final Territory t, final List<Territory> fronts) {
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
      return Optional.ofNullable(best);
    }

    /**
     * Step R2b: the campaign of {@code o} at {@code margin} with the flow v2 and the defenders of
     * model {@code model} (the same computation as the shadow's {@code margini_v2}).
     */
    JsonObject valutaImpegno(
        final Obiettivo o, final int margin, final double perRound, final String model) {
      final String before = modello;
      modello = model;
      try {
        final List<Integer> rounds = new ArrayList<>();
        int stageRound = round;
        for (final Territory s : o.percorso()) {
          int r = stageRound;
          if (nemico(s.getOwner())
              && !data.getRelationshipTracker().isAtWar(player, s.getOwner())) {
            final int warRound = roundGuerra(s.getOwner());
            r = warRound < 0 ? Integer.MAX_VALUE / 2 : Math.max(r, warRound);
          }
          rounds.add(r);
          stageRound = r + 1;
        }
        final int deadline = rounds.isEmpty() ? round : rounds.get(rounds.size() - 1);
        final List<Unit> mine = unitaCampagna();
        final Map<Unit, Territory> where = new HashMap<>();
        for (final Territory t : map.getTerritories()) {
          t.getUnitCollection().stream().filter(mine::contains).forEach(u -> where.put(u, t));
        }
        return flussoV2(o, rounds, mine, where, margin, perRound, deadline);
      } finally {
        modello = before;
      }
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
      // R2a-bis 1.1 + 1.5: the same campaign with the model of the defenders of 1.5, no factors,
      // the real calculator on the first stage that blocks and on the target
      final JsonObject perMarginV2 = new JsonObject();
      boolean anyV2 = false;
      for (final int margin : cfg.margini()) {
        final JsonObject m = flussoV2(o, rounds, mine, where, margin, perRound, deadline);
        perMarginV2.add(String.valueOf(margin), m);
        anyV2 |= m.get("fattibile").getAsBoolean();
      }
      c.add("margini_v2", perMarginV2);
      c.addProperty("modello_v2", modello);
      c.addProperty("fattibile_v2_a_un_margine", anyV2);
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
      final JsonObject models = new JsonObject();
      for (final Territory s : o.percorso()) {
        if (!nemico(s.getOwner())) {
          continue;
        }
        final JsonObject byK = new JsonObject();
        // R2a-bis 1.2: the three models side by side (M1 = the present defenders)
        final JsonObject mx = new JsonObject();
        final double present = tuv(presenti(s));
        mx.addProperty("tipo", CampagnaOmbraMisure.tipo(s));
        mx.addProperty("guerra", data.getRelationshipTracker().isAtWar(player, s.getOwner()));
        mx.addProperty("presenti", Math.round(present));
        for (int k = 1; k <= 3; k++) {
          final double m0 = tuv(difensori(s, k));
          byK.addProperty(String.valueOf(round + k), Math.round(m0));
          final JsonArray pair = new JsonArray();
          pair.add(Math.round(m0));
          pair.add(Math.round(m2(present, m0, k)));
          mx.add(String.valueOf(round + k), pair);
        }
        defs.add(s.getName(), byK);
        models.add(s.getName(), mx);
      }
      prev.add("difensori", defs);
      prev.add("modelli", models);
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
      for (final int margin : cfg.margini()) {
        final JsonObject m = perMarginV2.getAsJsonObject(String.valueOf(margin));
        lines.add(
            String.format(
                Locale.ROOT,
                "    v2 (difensori %s, calcolatore) margine %d: fattibile %s; presa %.0f%%, tenuta"
                    + " %.0f%%%s",
                modello.toUpperCase(Locale.ROOT),
                margin,
                m.get("fattibile").getAsBoolean() ? "SI'" : "no",
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

    // ------------------------------------------------------------------ R2a-bis 1.1, 1.5

    /**
     * 1.1 + 1.5: the point as a flow with the model of the defenders {@link #modello}, no factor:
     * every stage estimated, except the first one the estimate blocks and the target, fought with
     * the real calculator ({@link CampagnaOmbraMisure#simula}): take = the attacker's win, losses =
     * the {@code margin} percentile of the attacker's losses, hold = 100 - the win of the
     * counterattack on the survivors. The force needed stays the estimate.
     */
    JsonObject flussoV2(
        final Obiettivo o,
        final List<Integer> rounds,
        final List<Unit> mine,
        final Map<Unit, Territory> where,
        final int margin,
        final double perRound,
        final int deadline) {
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
      boolean firstBlockDone = false;
      boolean gapUnknown = false;
      int calcStages = 0;
      final JsonArray stages = new JsonArray();
      final int last = o.percorso().size() - 1;
      for (int i = 0; i < o.percorso().size(); i++) {
        final Territory s = o.percorso().get(i);
        final int r = rounds.get(i);
        if (r > 99) {
          block = "guerra non possibile";
          break;
        }
        for (final Unit u : mine) {
          if (!used.contains(u) && where.get(u) != null && arrivo(u, where.get(u), s) <= r) {
            point.add(u);
            used.add(u);
          }
        }
        final JsonObject x = new JsonObject();
        x.addProperty("tappa", s.getName());
        x.addProperty("round", r);
        x.addProperty("forza_punta_tuv", Math.round(tuv(point)));
        if (!nemico(s.getOwner())) {
          x.addProperty("battaglia", false);
          stages.add(x);
          okUntil = i + 1;
          continue;
        }
        final List<Unit> defenders = difensoriModello(s, Math.max(1, r - round + 1));
        final double pointTuv = tuv(point);
        final double sAtt =
            point.isEmpty() ? 0 : ProBattleUtils.estimateStrength(s, point, defenders, true);
        final double sDef =
            defenders.isEmpty() ? 0 : ProBattleUtils.estimateStrength(s, defenders, point, false);
        final boolean landPoint = point.stream().anyMatch(Matches.unitIsLand());
        double stageTake = defenders.isEmpty() ? (landPoint ? 100 : 0) : diff(sAtt, sDef);
        double loss = sAtt + sDef <= 0 ? 0 : Math.min(pointTuv, pointTuv * sDef / (sAtt + sDef));
        List<Unit> survivors = superstiti(point, loss);
        final List<Unit> counter = contrattacco(s);
        final double sC =
            counter.isEmpty() ? 0 : ProBattleUtils.estimateStrength(s, counter, survivors, true);
        final double sS =
            survivors.isEmpty() ? 0 : ProBattleUtils.estimateStrength(s, survivors, counter, false);
        double stageHold =
            100
                - Math.min(
                    100,
                    Math.max(0, counter.isEmpty() ? 0 : survivors.isEmpty() ? 100 : diff(sC, sS)));
        final double needTake =
            sDef <= 0
                ? pointTuv
                : pointTuv * needed(sDef, cfg.sogliaPresa()) / Math.max(1e-6, sAtt);
        final double needHold =
            sC <= 0 ? 0 : loss + tuv(survivors) * neededHold(sC, sS) / Math.max(1e-6, sS);
        final boolean estimateBlocks =
            !(stageTake >= cfg.sogliaPresa()
                && stageHold >= cfg.sogliaTenuta()
                && !survivors.isEmpty());
        String source = "stima";
        if (calcolatore.isPresent()
            && erroreCalcolatore == null
            && landPoint
            && !defenders.isEmpty()
            && ((estimateBlocks && !firstBlockDone) || i == last)) {
          if (estimateBlocks) {
            firstBlockDone = true;
          }
          CampagnaOmbraMisure.Distribuzione d = null;
          double calcHold = 0;
          List<Unit> calcSurvivors = List.of();
          try {
            d = attacco(s, point, defenders);
            calcSurvivors = superstiti(point, Math.min(pointTuv, d.percentile(margin)));
            calcHold = tenutaCalcolatore(s, counter, calcSurvivors);
          } catch (final RuntimeException e) {
            // the estimate stays (declared in the output); no more calls this turn
            erroreCalcolatore = String.valueOf(e);
            x.addProperty("errore_calcolatore", erroreCalcolatore);
            d = null;
          }
          if (d != null) {
            stageTake = d.vittoriaPct();
            loss = Math.min(pointTuv, d.percentile(margin));
            survivors = calcSurvivors;
            stageHold = calcHold;
            calcStages++;
            source = "calcolatore";
            final JsonObject cx = new JsonObject();
            cx.addProperty("simulazioni", d.simulazioni());
            cx.addProperty("metodo", d.metodo());
            cx.addProperty("vittoria_pct", Math.round(d.vittoriaPct() * 10) / 10.0);
            cx.addProperty("perdite_media_tuv", Math.round(d.media() * 10) / 10.0);
            for (final int p : cfg.margini()) {
              cx.addProperty("perdite_p" + p + "_tuv", Math.round(d.percentile(p) * 10) / 10.0);
            }
            x.add("calcolatore", cx);
          }
        }
        final boolean pass =
            stageTake >= cfg.sogliaPresa()
                && stageHold >= cfg.sogliaTenuta()
                && !survivors.isEmpty();
        final double need = Math.max(needTake, needHold);
        if (!pass && need <= pointTuv) {
          // the calculator blocks where the estimate finds the force enough: no gap to buy
          gapUnknown = true;
        }
        take = stageTake;
        hold = stageHold;
        lossesTotal += loss;
        x.addProperty("battaglia", true);
        x.addProperty("fonte", source);
        x.addProperty("difensori_attesi_tuv", Math.round(tuv(defenders)));
        x.addProperty("presa_pct", Math.round(Math.min(100, Math.max(0, stageTake))));
        x.addProperty("perdite_tuv", Math.round(loss));
        x.addProperty("contrattacco_tuv", Math.round(tuv(counter)));
        x.addProperty("tenuta_pct", Math.round(stageHold));
        x.addProperty("forza_necessaria_tuv", Math.round(need));
        stages.add(x);
        if (block.isEmpty() && (need > needMax || !pass)) {
          needMax = need;
          availAtNeed = pointTuv;
        }
        point.clear();
        point.addAll(survivors);
        if (pass) {
          okUntil = i + 1;
        } else {
          block =
              (stageTake < cfg.sogliaPresa() ? "presa insufficiente a " : "tenuta insufficiente a ")
                  + s.getName()
                  + " ("
                  + source
                  + ")";
          break;
        }
      }
      final double gap = Math.max(0, needMax - availAtNeed);
      final int roundsLeft = Math.max(1, deadline - round);
      final double puAvail = perRound * roundsLeft;
      final boolean feasible =
          okUntil == o.percorso().size()
              || (!gapUnknown && gap > 0 && gap <= puAvail && !block.startsWith("guerra"));
      m.add("tappe", stages);
      m.addProperty("modello", modello);
      m.addProperty("tappe_col_calcolatore", calcStages);
      m.addProperty("scarto_tuv", gapUnknown ? -1 : Math.round(gap));
      m.addProperty("pu_disponibili_entro_scadenza", Math.round(puAvail));
      m.addProperty("fattibile", feasible);
      m.addProperty("presa_ok_fino_a_tappa", okUntil);
      m.addProperty("round_stimato", feasible ? deadline : -1);
      m.addProperty("perdite_attese_tuv", Math.round(lossesTotal));
      m.addProperty("presa_obiettivo_pct", Math.round(Math.min(100, Math.max(0, take))));
      m.addProperty("tenuta_obiettivo_pct", Math.round(hold));
      if (!block.isEmpty()) {
        m.addProperty(
            "blocco",
            block
                + (feasible
                    ? ", colmabile con acquisti"
                    : gapUnknown ? ", scarto non stimabile (la stima basta)" : ""));
      }
      return m;
    }

    /** One battle with the real calculator, cached on the same units (the margins share it). */
    CampagnaOmbraMisure.Distribuzione attacco(
        final Territory s, final List<Unit> attackers, final List<Unit> defenders) {
      return cacheCalcolatore.computeIfAbsent(
          CampagnaOmbraMisure.chiave(s, attackers, defenders),
          k -> {
            chiamateCalcolatore++;
            return CampagnaOmbraMisure.simula(
                calcolatore.orElseThrow(),
                s,
                attackers,
                defenders,
                cfg.bis().simulazioni(),
                cfg.bis().ripetizioni(),
                this::costoNome);
          });
    }

    /** 100 - the counterattack's win against the survivors (calculator). */
    double tenutaCalcolatore(final Territory s, final List<Unit> counter, final List<Unit> held) {
      if (counter.isEmpty()) {
        return 100;
      }
      if (held.isEmpty()) {
        return 0;
      }
      return 100 - attacco(s, counter, held).vittoriaPct();
    }

    private final Map<String, Integer> costByName = new HashMap<>();

    int costoNome(final String name) {
      if (costByName.isEmpty()) {
        costs.keySet().forEach(t -> costByName.put(t.getName(), costs.getInt(t)));
      }
      return costByName.getOrDefault(name, 0);
    }

    /** The enemy defenders present now (model M1). */
    List<Unit> presenti(final Territory s) {
      return new ArrayList<>(
          s.getMatches(Matches.unitIsNotInfrastructure().and(u -> nemico(u.getOwner()))));
    }

    /** Model M2 in TUV for horizon {@code k}. */
    double m2(final double present, final double m0, final int k) {
      return CampagnaOmbraMisure.m2(
          present, m0, quoteM2[k], cfg.bis().quotaM2Min(), cfg.bis().quotaM2Max());
    }

    /**
     * The defenders of model {@link #modello}: M1 the present ones, M0 those of {@link #difensori},
     * M2 the present ones + the first units of M0's increase up to the share.
     */
    List<Unit> difensoriModello(final Territory s, final int k) {
      final List<Unit> present = presenti(s);
      if ("m1".equals(modello)) {
        return present;
      }
      final List<Unit> all = difensori(s, k);
      if (!"m2".equals(modello)) {
        return all;
      }
      final List<Unit> extra = all.subList(Math.min(present.size(), all.size()), all.size());
      final double target = m2(tuv(present), tuv(all), Math.min(3, k)) - tuv(present);
      final List<Unit> out = new ArrayList<>(present);
      double sum = 0;
      for (final Unit u : extra) {
        if (sum >= target - 1e-9) {
          break;
        }
        out.add(u);
        sum += costs.getInt(u.getType());
      }
      return out;
    }

    /**
     * The share of M2 per horizon (real increase / increase M0 predicted, over the checks of the
     * game) and the model of 1.5: the lowest median absolute error with enough checks for each.
     */
    void leggiModelli(final JsonObject st) {
      final JsonObject q = st.has("quote_m2") ? st.getAsJsonObject("quote_m2") : new JsonObject();
      for (int k = 1; k <= 3; k++) {
        if (q.has(String.valueOf(k))) {
          final JsonArray a = q.getAsJsonArray(String.valueOf(k));
          quoteM2[k] =
              Math.max(
                  cfg.bis().quotaM2Min(),
                  Math.min(
                      cfg.bis().quotaM2Max(),
                      CampagnaOmbraMisure.quota(
                          a.get(0).getAsDouble(),
                          a.get(1).getAsDouble(),
                          cfg.bis().quotaM2Iniziale())));
        }
      }
      if (!st.has("errori_modelli")) {
        return;
      }
      final JsonObject e = st.getAsJsonObject("errori_modelli");
      String best = null;
      double bestMedian = Double.MAX_VALUE;
      for (final String mdl : List.of("m0", "m1", "m2")) {
        if (!e.has(mdl) || e.getAsJsonArray(mdl).size() < cfg.bis().minConfrontiModello()) {
          return;
        }
        final List<Double> v = new ArrayList<>();
        e.getAsJsonArray(mdl).forEach(x -> v.add(x.getAsDouble()));
        v.sort(Double::compare);
        final double med =
            v.size() % 2 == 1
                ? v.get(v.size() / 2)
                : (v.get(v.size() / 2 - 1) + v.get(v.size() / 2)) / 2;
        if (med < bestMedian) {
          bestMedian = med;
          best = mdl;
        }
      }
      if (best != null) {
        modello = best;
      }
    }

    /**
     * The start of the nation's turn (at the purchase, before any move): the enemy land territories
     * (1.3: which ones the nation takes) and the position of its land units (Measure 2).
     */
    void inizioTurno(final JsonObject st) {
      final JsonObject ini = st.has("inizio") ? st.getAsJsonObject("inizio") : new JsonObject();
      final JsonObject mine = new JsonObject();
      final JsonArray enemies = new JsonArray();
      final JsonObject units = new JsonObject();
      final List<Territory> all = territoriOrdinati();
      for (int i = 0; i < all.size(); i++) {
        final Territory t = all.get(i);
        if (t.isWater()) {
          continue;
        }
        if (nemico(t.getOwner())) {
          enemies.add(i);
        }
        for (final Unit u : t.getMatches(unitaTerraProprie())) {
          units.addProperty(CampagnaOmbraMisure.id(u), i);
        }
      }
      mine.addProperty("round", round);
      mine.add("nemici", enemies);
      mine.add("unita", units);
      ini.add(player.getName(), mine);
      st.add("inizio", ini);
    }

    /**
     * The territories sorted by name: the index in the compact state (the AI's copy of the game may
     * list them in another order than the real game).
     */
    List<Territory> territoriOrdinati() {
      final List<Territory> out = new ArrayList<>(map.getTerritories());
      out.sort(Comparator.comparing(Territory::getName));
      return out;
    }

    /** The nation's land units (infrastructure excluded, anti-aircraft included: counted apart). */
    Predicate<Unit> unitaTerraProprie() {
      return Matches.unitIsOwnedBy(player)
          .and(Matches.unitIsLand())
          .and(Matches.unitIsNotInfrastructure().or(Matches.unitIsAaForAnything()));
    }

    /**
     * End of the nation's turn (R2a-bis): 1.3 the enemy territories the nation took this turn (by
     * anyone of its units, Hard's moves too) with the strongest enemy force in reach and the
     * estimated hold; Measure 2 the class of every land unit of the nation in the rear territories
     * of rule 2.6 (cases a and b); the lost rear territories retaken within 2 rounds.
     */
    JsonArray fineTurnoBis(final JsonObject st) {
      final JsonObject ini = st.has("inizio") ? st.getAsJsonObject("inizio") : new JsonObject();
      final JsonObject mine =
          ini.has(player.getName()) ? ini.getAsJsonObject(player.getName()) : null;
      ritiri(st);
      if (mine == null || mine.get("round").getAsInt() != round) {
        return new JsonArray();
      }
      ini.remove(player.getName());
      final List<Territory> all = territoriOrdinati();
      final JsonArray rows = new JsonArray();
      // 1.3
      final JsonArray points = st.has("punte") ? st.getAsJsonArray("punte") : new JsonArray();
      for (final JsonElement e : mine.getAsJsonArray("nemici")) {
        final int i = e.getAsInt();
        if (i < 0 || i >= all.size() || !all.get(i).isOwnedBy(player)) {
          continue;
        }
        points.add(punta(all.get(i)));
      }
      while (points.size() > cfg.bis().maxPunte()) {
        points.remove(0);
      }
      st.add("punte", points);
      // Measure 2
      final Map<String, String> cases = new HashMap<>();
      for (final JsonElement e : st.getAsJsonArray("retrovia")) {
        final JsonObject r = e.getAsJsonObject();
        final String caso = r.get("caso").getAsString();
        if (r.get("round").getAsInt() == round
            && r.get("nazione").getAsString().equals(player.getName())
            && (CASO_A.equals(caso) || CASO_B.equals(caso))) {
          cases.put(r.get("territorio").getAsString(), caso);
        }
      }
      final Predicate<Territory> land =
          x -> !x.isWater() && !Matches.territoryIsImpassable().test(x);
      final Map<Territory, Integer> front =
          CampagnaOmbraMisure.distanzaFronte(map, x -> nemico(x.getOwner()), land);
      final Predicate<Territory> friendly = land.and(Matches.isTerritoryAllied(player));
      final JsonObject track = st.has("traccia") ? st.getAsJsonObject("traccia") : new JsonObject();
      final JsonObject prev =
          track.has(player.getName()) ? track.getAsJsonObject(player.getName()) : new JsonObject();
      final JsonObject next = new JsonObject();
      final JsonObject start = mine.getAsJsonObject("unita");
      final JsonObject agg = st.has("misura2") ? st.getAsJsonObject("misura2") : new JsonObject();
      final JsonObject nat =
          agg.has(player.getName()) ? agg.getAsJsonObject(player.getName()) : new JsonObject();
      inc(nat, "turni", 1);
      for (int i = 0; i < all.size(); i++) {
        final Territory t = all.get(i);
        if (t.isWater()) {
          continue;
        }
        for (final Unit u : t.getMatches(unitaTerraProprie())) {
          final String id = CampagnaOmbraMisure.id(u);
          final boolean placed = !start.has(id);
          final int from = placed ? -1 : start.get(id).getAsInt();
          final boolean moved = !placed && from != i;
          final JsonArray before = prev.has(id) ? prev.getAsJsonArray(id) : null;
          final int since =
              placed || moved
                  ? round
                  : before != null && before.get(0).getAsInt() == i ? before.get(1).getAsInt() : 0;
          final boolean closer =
              moved
                  && front.containsKey(t)
                  && (!front.containsKey(all.get(from)) || front.get(t) < front.get(all.get(from)));
          final JsonArray now = new JsonArray();
          now.add(i);
          now.add(since);
          now.add(closer ? 1 : 0);
          next.add(id, now);
          final String caso = cases.get(t.getName());
          if (caso == null) {
            continue;
          }
          final boolean aa = Matches.unitIsAaForAnything().test(u);
          final int stillFor = round - since;
          final boolean closerBefore = before != null && before.get(2).getAsInt() == 1;
          final int mv = Math.max(0, u.getUnitAttachment().getMovement(player));
          final boolean canApproach =
              CampagnaOmbraMisure.puoAvvicinarsi(map, t, mv, friendly, front);
          final String cls =
              CampagnaOmbraMisure.classe(aa, placed, moved, stillFor, closerBefore, canApproach);
          final double value = costs.getInt(u.getType());
          final JsonObject row = new JsonObject();
          row.addProperty("territorio", t.getName());
          row.addProperty("caso", caso);
          row.addProperty("tipo", u.getType().getName());
          row.addProperty("tuv", value);
          row.addProperty("classe", cls);
          row.addProperty("piazzata", placed);
          if (moved) {
            row.addProperty("da", all.get(from).getName());
          }
          row.addProperty("fabbrica", t.anyUnitsMatch(Matches.unitCanProduceUnits()));
          row.addProperty("ferma_da", placed || moved ? 0 : stillFor);
          row.addProperty("puo_avvicinarsi", canApproach);
          row.addProperty("distanza_fronte", front.getOrDefault(t, -1));
          rows.add(row);
          final JsonObject byCase = nat.has(caso) ? nat.getAsJsonObject(caso) : new JsonObject();
          final JsonArray cell = byCase.has(cls) ? byCase.getAsJsonArray(cls) : new JsonArray();
          final double n0 = cell.size() > 0 ? cell.get(0).getAsDouble() : 0;
          final double v0 = cell.size() > 1 ? cell.get(1).getAsDouble() : 0;
          final JsonArray cellNew = new JsonArray();
          cellNew.add(n0 + 1);
          cellNew.add(v0 + value);
          byCase.add(cls, cellNew);
          nat.add(caso, byCase);
          if (CampagnaOmbraMisure.FERMA_DAVVERO.equals(cls)) {
            final JsonObject types =
                nat.has("tipi_ferme") ? nat.getAsJsonObject("tipi_ferme") : new JsonObject();
            inc(types, u.getType().getName(), 1);
            nat.add("tipi_ferme", types);
          }
        }
      }
      agg.add(player.getName(), nat);
      st.add("misura2", agg);
      track.add(player.getName(), next);
      st.add("traccia", track);
      scriviFileRetrovie(rows);
      return rows;
    }

    /** 1.3: the point taken; enemy force in reach (max), estimated hold. */
    JsonObject punta(final Territory t) {
      final List<Unit> held =
          t.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotInfrastructure()));
      final List<Unit> counter = contrattacco(t);
      final List<Unit> max = forzaInPortata(t);
      final double sC =
          counter.isEmpty() ? 0 : ProBattleUtils.estimateStrength(t, counter, held, true);
      final double sD =
          held.isEmpty() ? 0 : ProBattleUtils.estimateStrength(t, held, counter, false);
      final double enemy = counter.isEmpty() ? 0 : held.isEmpty() ? 100 : diff(sC, sD);
      final JsonObject x = new JsonObject();
      x.addProperty("territorio", t.getName());
      x.addProperty("round", round);
      x.addProperty("nazione", player.getName());
      x.addProperty("tipo", CampagnaOmbraMisure.tipo(t));
      x.addProperty("massima_tuv", Math.round(tuv(max)));
      x.addProperty("contrattacco_previsto_tuv", Math.round(tuv(counter)));
      x.addProperty("difensori_tuv", Math.round(tuv(held)));
      x.addProperty("tenuta_stimata", Math.round(100 - Math.min(100, Math.max(0, enemy))));
      return x;
    }

    /**
     * Every enemy unit that can attack {@code s} next turn (as {@link #contrattacco}, no share).
     */
    List<Unit> forzaInPortata(final Territory s) {
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
      return all;
    }

    /** Lost rear territories: retaken by the alliance by the end of the nation's next turn. */
    void ritiri(final JsonObject st) {
      if (!st.has("persi")) {
        return;
      }
      for (final JsonElement e : st.getAsJsonArray("persi")) {
        final JsonObject x = e.getAsJsonObject();
        if (!x.get("nazione").getAsString().equals(player.getName())
            || !"in attesa".equals(x.get("ripreso").getAsString())) {
          continue;
        }
        final Territory t = map.getTerritoryOrNull(x.get("territorio").getAsString());
        if (t == null) {
          continue;
        }
        final int lostAt = x.get("round").getAsInt();
        if (Matches.isTerritoryAllied(player).test(t)) {
          x.addProperty("ripreso", "R" + round);
        } else if (round >= lostAt + 1) {
          x.addProperty("ripreso", "no");
        }
      }
    }

    void scriviFileRetrovie(final JsonArray rows) {
      if (!(data instanceof GameData gd)) {
        return;
      }
      final String name = String.format(Locale.ROOT, "retrovie-ombra-R%d-%s.json", round, alliance);
      final JsonObject file = new JsonObject();
      file.addProperty("round", round);
      file.addProperty("alleanza", alliance);
      file.addProperty(
          "nota", "IN OMBRA: unita' di terra lasciate da Hard nelle retrovie (casi a/b)");
      final JsonObject nations =
          FILE.computeIfAbsent(name + "|" + System.identityHashCode(gd), k -> new JsonObject());
      nations.add(player.getName(), rows);
      file.add("nazioni", nations);
      DiaryRecorder.operationInput(gd, name, file);
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
      final JsonArray models = new JsonArray();
      final JsonArray battlesCalc = new JsonArray();
      final Set<String> seen = new LinkedHashSet<>();
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
          // R2a-bis 1.2: the three models of the defenders (one row per territory and round)
          if (p.has("modelli")) {
            for (final var d : p.getAsJsonObject("modelli").entrySet()) {
              final JsonObject x = d.getValue().getAsJsonObject();
              final Territory t = map.getTerritoryOrNull(d.getKey());
              if (t == null
                  || !x.has(String.valueOf(round))
                  || !seen.add(made + "|" + d.getKey())) {
                continue;
              }
              final JsonArray pair = x.getAsJsonArray(String.valueOf(round));
              final double present = x.get("presenti").getAsDouble();
              final double m0 = pair.get(0).getAsDouble();
              final double mm2 = pair.get(1).getAsDouble();
              final double real = tuv(presenti(t));
              final JsonObject row = new JsonObject();
              row.addProperty("territorio", t.getName());
              row.addProperty("orizzonte", k);
              row.addProperty("tipo", x.get("tipo").getAsString());
              row.addProperty("guerra", x.get("guerra").getAsBoolean());
              row.addProperty("reali_tuv", Math.round(real));
              row.addProperty("m0", m0);
              row.addProperty("m1", present);
              row.addProperty("m2", mm2);
              models.add(row);
              aggiornaQuota(st, k, real - present, m0 - present);
              erroreModello(st, "m0", Math.abs(m0 - real));
              erroreModello(st, "m1", Math.abs(present - real));
              erroreModello(st, "m2", Math.abs(mm2 - real));
            }
          }
          // R2a-bis 1.4: the losses of the calculator's percentiles on the first stage
          if (k == 1 && p.has("perdite_calcolatore")) {
            final JsonObject pl = p.getAsJsonObject("perdite_calcolatore");
            final String site = pl.get("territorio").getAsString();
            final int lost = perditaReale(made, site);
            if (lost >= 0) {
              final JsonObject b = new JsonObject();
              b.addProperty("territorio", site);
              b.addProperty("perdita_reale_tuv", lost);
              for (final int margin : cfg.margini()) {
                final double pred = pl.get(String.valueOf(margin)).getAsDouble();
                b.addProperty("p" + margin, pred);
                conta(stats, "battaglie_calcolatore_" + margin, lost <= pred);
              }
              battlesCalc.add(b);
            }
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
          if (lost) {
            // Measure 2, case b: who took it, its value, Hard's units when it fell; retaken
            // within 2 rounds is checked at the end of the nation's next turns
            final JsonArray persi = st.has("persi") ? st.getAsJsonArray("persi") : new JsonArray();
            final JsonObject x = new JsonObject();
            x.addProperty("territorio", t.getName());
            x.addProperty("caso", caso);
            x.addProperty("nazione", player.getName());
            x.addProperty("round", round);
            x.addProperty(
                "valore", TerritoryAttachment.get(t).map(a -> a.getProduction()).orElse(0));
            x.addProperty("preso_da", t.getOwner().getName());
            x.addProperty(
                "unita_hard",
                r.has("unita_lasciate_da_hard") ? r.get("unita_lasciate_da_hard").getAsInt() : 0);
            x.addProperty("ripreso", "in attesa");
            persi.add(x);
            while (persi.size() > cfg.bis().maxPersi()) {
              persi.remove(0);
            }
            st.add("persi", persi);
          }
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
      // R2a-bis 1.3: the points taken in the nation's last turn against the enemy turn after
      final JsonArray points = verificaPunte(st);
      st.add("statistiche", stats);
      v.add("difensori", errs);
      v.add("battaglie", battles);
      v.add("difensori_modelli", models);
      v.add("battaglie_calcolatore", battlesCalc);
      v.add("contrattacco", points);
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

    /** 1.3: attacked, with how much of the maximum, held; one row per point of the last turn. */
    JsonArray verificaPunte(final JsonObject st) {
      final JsonArray out = new JsonArray();
      if (!st.has("punte")) {
        return out;
      }
      for (final JsonElement e : st.getAsJsonArray("punte")) {
        final JsonObject p = e.getAsJsonObject();
        if (!p.get("nazione").getAsString().equals(player.getName())
            || p.get("round").getAsInt() != round - 1
            || p.has("verificato")) {
          continue;
        }
        final Territory t = map.getTerritoryOrNull(p.get("territorio").getAsString());
        if (t == null) {
          continue;
        }
        final int made = p.get("round").getAsInt();
        final double committed =
            Math.max(0, forzaImpegnata(t, made)) + Math.max(0, forzaImpegnata(t, round));
        final boolean attacked = attaccato(t, made) || attaccato(t, round);
        final JsonObject row = p.deepCopy();
        row.remove("nazione");
        row.addProperty("attaccato", attacked);
        row.addProperty("impegnata_tuv", Math.round(committed));
        row.addProperty(
            "quota_impegnata",
            p.get("massima_tuv").getAsDouble() <= 0
                ? 0
                : Math.round(committed / p.get("massima_tuv").getAsDouble() * 1000) / 1000.0);
        row.addProperty("tenuto", Matches.isTerritoryAllied(player).test(t));
        out.add(row);
        p.addProperty("verificato", true);
      }
      return out;
    }

    /**
     * TUV an enemy attacker brought to {@code t} in {@code r}: lost + left at the end of the battle
     * (-1 = no battle). Retreated units are not counted when the results do not keep them.
     */
    double forzaImpegnata(final Territory t, final int r) {
      final var all = data.getBattleRecordsList();
      if (all == null || all.getBattleRecordsMapCopy().get(r) == null) {
        return -1;
      }
      double sum = -1;
      for (final BattleRecord b :
          BattleRecords.getAllRecords(all.getBattleRecordsMapCopy().get(r))) {
        final Territory site = field(b, "battleSite");
        final GamePlayer att = field(b, "attacker");
        if (site == null || !site.equals(t) || att == null || !nemico(att)) {
          continue;
        }
        final Integer lost = field(b, "attackerLostTuv");
        final games.strategy.triplea.delegate.battle.BattleResults res = field(b, "battleResults");
        sum =
            Math.max(0, sum)
                + (lost == null ? 0 : lost)
                + (res == null || res.getRemainingAttackingUnits() == null
                    ? 0
                    : tuvByName(res.getRemainingAttackingUnits()));
      }
      return sum;
    }

    double tuvByName(final Collection<Unit> units) {
      return CampagnaOmbraMisure.tuv(units, this::costoNome);
    }

    private void aggiornaQuota(
        final JsonObject st, final int k, final double realInc, final double predInc) {
      final JsonObject q = st.has("quote_m2") ? st.getAsJsonObject("quote_m2") : new JsonObject();
      final JsonArray a =
          q.has(String.valueOf(k)) ? q.getAsJsonArray(String.valueOf(k)) : new JsonArray();
      final double r0 = a.size() > 0 ? a.get(0).getAsDouble() : 0;
      final double p0 = a.size() > 1 ? a.get(1).getAsDouble() : 0;
      final JsonArray n = new JsonArray();
      n.add(r0 + realInc);
      n.add(p0 + Math.max(0, predInc));
      q.add(String.valueOf(k), n);
      st.add("quote_m2", q);
    }

    private void erroreModello(final JsonObject st, final String model, final double absErr) {
      final JsonObject e =
          st.has("errori_modelli") ? st.getAsJsonObject("errori_modelli") : new JsonObject();
      final JsonArray a = e.has(model) ? e.getAsJsonArray(model) : new JsonArray();
      a.add(Math.round(absErr));
      while (a.size() > cfg.bis().maxErroriModello()) {
        a.remove(0);
      }
      e.add(model, a);
      st.add("errori_modelli", e);
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
        p.add("modelli", pr.get("modelli"));
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
        // R2a-bis 1.4: the calculator's percentiles on the battle stage of this round (the first
        // stage: before it the point is the same for every margin)
        for (final JsonElement e :
            c.getAsJsonObject("margini_v2")
                .getAsJsonObject(String.valueOf(cfg.margini().get(0)))
                .getAsJsonArray("tappe")) {
          final JsonObject x = e.getAsJsonObject();
          if (x.get("battaglia").getAsBoolean()
              && x.get("round").getAsInt() == round
              && x.has("calcolatore")) {
            final JsonObject pc = new JsonObject();
            pc.addProperty("territorio", x.get("tappa").getAsString());
            for (final int margin : cfg.margini()) {
              pc.addProperty(
                  String.valueOf(margin),
                  x.getAsJsonObject("calcolatore")
                      .get("perdite_p" + margin + "_tuv")
                      .getAsDouble());
            }
            p.add("perdite_calcolatore", pc);
            break;
          }
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
      // R2a-bis 1.3: the points waiting for the check (the nation's last turn), pruned
      if (st.has("punte")) {
        final JsonArray points = new JsonArray();
        for (final JsonElement e : st.getAsJsonArray("punte")) {
          final JsonObject x = e.getAsJsonObject();
          if (!x.has("verificato") && x.get("round").getAsInt() >= round - 1) {
            points.add(x);
          }
        }
        st.add("punte", points);
      }
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
