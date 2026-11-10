package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Step STRATEGA LLM: the candidate operations the engine computes at the start of a round for an
 * alliance (short list, at most {@code max_candidati}), with the numbers the strategist reads, and
 * the definitions the engine of the joint operations ({@link OperazioniCongiunte}) runs for them.
 *
 * <p>Four general types, built on the joint operations v2 (steps, reserves, gathering with safe
 * stops, garrison held, reinforcements, air cover, retreat):
 *
 * <ul>
 *   <li>{@link Tipo#OFFENSIVA}: two nations on an enemy land territory in the same round (who
 *       weakens plays earlier in the turn order than who takes);
 *   <li>{@link Tipo#SBARCO}: landing from a sea zone next to the target and to a land of the
 *       alliance where the troops gather and load; an optional next target;
 *   <li>{@link Tipo#TENUTA}: hold an own or allied territory really threatened (garrison held,
 *       reinforcements while threatened);
 *   <li>{@link Tipo#RINFORZO}: a nation sends units/planes to an ally's territory really
 *       threatened, then holds it.
 * </ul>
 */
public final class StrategaCandidati {

  /** General type of an operation of the strategist. */
  public enum Tipo {
    OFFENSIVA,
    SBARCO,
    TENUTA,
    RINFORZO;

    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Tipo> of(final String id) {
      for (final Tipo t : values()) {
        if (t.id().equals(id == null ? "" : id.strip().toLowerCase(Locale.ROOT))) {
          return Optional.of(t);
        }
      }
      return Optional.empty();
    }

    public boolean conquista() {
      return this == OFFENSIVA || this == SBARCO;
    }
  }

  /**
   * One candidate.
   *
   * @param id short id for the LLM ("C1"...)
   * @param apre nation that weakens / lands first / reinforces / holds
   * @param chiude nation that takes / completes / holds with it (may equal {@code apre} for hold
   *     and reinforcement)
   * @param roundMinimo first round the steps are feasible
   * @param raccolta land territory where the troops gather (landing: next to {@code zona})
   * @param zona sea zone of the landing ("" otherwise)
   * @param valore value of the target in PUs (production, VC, capital, national objectives)
   * @param punteggio rank of the cheap screening (higher first)
   * @param json the numbers for the LLM
   */
  public record Candidato(
      String id,
      Tipo tipo,
      String bersaglio,
      String apre,
      String chiude,
      int roundMinimo,
      String raccolta,
      String zona,
      List<String> successivi,
      double valore,
      double punteggio,
      JsonObject json) {}

  private final ProData proData;
  private final ProOddsCalculator calc;
  private final GameData data;
  private final GameMap map;
  private final String alliance;
  private final List<String> nations;
  private final int round;
  private final StrategaConfig cfg;

  StrategaCandidati(
      final ProData proData,
      final ProOddsCalculator calc,
      final String alliance,
      final List<String> nations) {
    this.proData = proData;
    this.calc = calc;
    this.data = proData.getData();
    this.map = data.getMap();
    this.alliance = alliance;
    this.round = data.getSequence().getRound();
    this.cfg = StrategaConfig.get();
    // nations with a role, in turn order (France: none, as for the turn orders)
    this.nations =
        ordine(data).stream()
            .filter(nations::contains)
            .filter(n -> !cfg.nazioniSenzaRuoli().contains(n))
            .toList();
  }

  /** Nations in turn order of a round (first appearance in the game sequence). */
  static List<String> ordine(final GameData data) {
    final List<String> list = new ArrayList<>();
    for (final GameStep step : data.getSequence()) {
      final GamePlayer p = step.getPlayerId();
      if (p != null && !p.isNull() && !list.contains(p.getName())) {
        list.add(p.getName());
      }
    }
    return list;
  }

  // =========================================================================== candidates

  /** Candidates discarded this round by the engine (target, type, why), for the state and diary. */
  private final JsonArray scartati = new JsonArray();

  JsonArray scartati() {
    return scartati;
  }

  /** Diary only: the exchange implied by the single estimate of every evaluated conquest. */
  private final JsonArray analisi = new JsonArray();

  /** The single estimate of the candidate being evaluated (diary of the discards). */
  private @javax.annotation.Nullable StimaUnica.Stima stimaCorrente;

  JsonArray analisi() {
    return analisi;
  }

  private void annota(final Candidato c, final StimaUnica.Stima st, final String esito) {
    if (!c.tipo().conquista()) {
      return;
    }
    final JsonObject x = st.jsonAnalisi();
    x.addProperty("tipo", c.tipo().id());
    x.addProperty("bersaglio", c.bersaglio());
    x.addProperty("nazioni", c.apre() + ">" + c.chiude());
    x.addProperty("per_il_round", st.roundObiettivo());
    x.addProperty("esito_motore", esito);
    analisi.add(x);
  }

  /**
   * The candidates of the round, best first, at most {@code max_candidati}. A cheap screening ranks
   * them; the best {@code candidati_valutati} get the single estimate ({@link StimaUnica}, the same
   * function as the decision points) and the feasibility at the launch; the infeasible ones, the
   * conquests whose step 1 is over the loss cap, the holds under {@code tenuta_minima_candidato}
   * even with the reinforcements and the targets Hard takes alone this round are discarded with
   * their reason.
   */
  List<Candidato> calcola() {
    final List<Candidato> all = new ArrayList<>();
    if (nations.isEmpty()) {
      return List.of();
    }
    all.addAll(offensive());
    all.addAll(sbarchi());
    all.addAll(tenute());
    all.sort(
        Comparator.comparingDouble((Candidato c) -> -c.punteggio())
            .thenComparing(Candidato::bersaglio));
    // a short, varied list: at most 3 of a type, one per target and type
    final List<Candidato> chosen = new ArrayList<>();
    final Map<Tipo, Integer> perType = new LinkedHashMap<>();
    int evaluated = 0;
    for (final Candidato c : all) {
      if (chosen.size() >= cfg.maxCandidati() || evaluated >= cfg.candidatiValutati()) {
        break;
      }
      if (perType.getOrDefault(c.tipo(), 0) >= 3
          || chosen.stream()
              .anyMatch(x -> x.tipo() == c.tipo() && x.bersaglio().equals(c.bersaglio()))
          || scartato(c)) {
        continue;
      }
      evaluated++;
      final Optional<Candidato> ok = valuta(c);
      if (ok.isPresent()) {
        perType.merge(c.tipo(), 1, Integer::sum);
        chosen.add(ok.get());
      }
    }
    final List<Candidato> out = new ArrayList<>();
    for (final Candidato c : chosen) {
      final String id = "C" + (out.size() + 1);
      final JsonObject j = c.json().deepCopy();
      j.addProperty("id", id);
      out.add(
          new Candidato(
              id,
              c.tipo(),
              c.bersaglio(),
              c.apre(),
              c.chiude(),
              c.roundMinimo(),
              c.raccolta(),
              c.zona(),
              c.successivi(),
              c.valore(),
              c.punteggio(),
              j));
    }
    return List.copyOf(out);
  }

  private boolean scartato(final Candidato c) {
    for (final var e : scartati) {
      final JsonObject x = e.getAsJsonObject();
      if (x.get("bersaglio").getAsString().equals(c.bersaglio())
          && x.get("tipo").getAsString().equals(c.tipo().id())) {
        return true;
      }
    }
    return false;
  }

  private void scarta(final Candidato c, final String why) {
    final JsonObject x = new JsonObject();
    x.addProperty("tipo", c.tipo().id());
    x.addProperty("bersaglio", c.bersaglio());
    x.addProperty("nazioni", c.apre().equals(c.chiude()) ? c.apre() : c.apre() + ">" + c.chiude());
    x.addProperty("motivo", why);
    scartati.add(x);
    if (stimaCorrente != null) {
      annota(c, stimaCorrente, "scartato: " + why);
    }
  }

  /**
   * The single estimate, the feasibility and the full numbers of a candidate; empty when discarded
   * (reason in {@link #scartati}).
   */
  private Optional<Candidato> valuta(final Candidato c) {
    stimaCorrente = null;
    final Territory t = map.getTerritoryOrNull(c.bersaglio());
    if (t == null) {
      return Optional.empty();
    }
    try {
      int roundMin = c.roundMinimo();
      final OperazioneCongiunta.Definizione def0 = definizioneTmp(c, roundMin);
      if (c.tipo().conquista()) {
        final Optional<String> hard = presaDaHard(t);
        if (hard.isPresent()) {
          scarta(c, hard.get());
          return Optional.empty();
        }
        final OperazioniCongiunte.Lancio l =
            OperazioniCongiunte.lancio(proData, calc, def0, round + cfg.orizzonteRound() - 1);
        if (l.motivo().isPresent()) {
          scarta(c, "infattibile: " + l.motivo().get());
          return Optional.empty();
        }
        roundMin = Math.max(roundMin, l.round());
      }
      final OperazioneCongiunta.Definizione def =
          roundMin == c.roundMinimo() ? def0 : definizioneTmp(c, roundMin);
      final StimaUnica.Stima st =
          c.tipo().conquista()
              ? StimaUnica.conquista(proData, calc, def, t, roundMin - round + 1)
              : StimaUnica.tenuta(proData, calc, def, t);
      stimaCorrente = st;
      if (c.tipo().conquista() && !difensori(t).isEmpty() && st.tappa1().isEmpty()) {
        scarta(
            c,
            "nessuna unita' di " + c.apre() + " puo' essere sul bersaglio entro il R" + roundMin);
        return Optional.empty();
      }
      if (c.tipo().conquista() && st.perdita1() > def.tettoPerdita()) {
        scarta(
            c,
            String.format(
                Locale.ROOT,
                "perdita attesa della tappa 1 %.0f TUV > tetto %.0f",
                st.perdita1(),
                def.tettoPerdita()));
        return Optional.empty();
      }
      if (!c.tipo().conquista() && st.tenuta() < cfg.tenutaMinimaCandidato()) {
        scarta(
            c,
            String.format(
                Locale.ROOT,
                "tenuta stimata %.0f%% < %.0f%% anche con i rinforzi possibili",
                st.tenuta(),
                cfg.tenutaMinimaCandidato()));
        return Optional.empty();
      }
      final JsonObject j = c.json().deepCopy();
      j.addProperty("round_minimo", roundMin);
      for (final String k :
          List.of(
              "tappa1",
              "tappa2",
              "presa_stimata_pct",
              "tenuta_ora_50_pct",
              "tenuta_ora_100_pct",
              "tenuta_50_con_rinforzi_pct",
              "nemici_in_portata_TUV",
              "rinforzi_possibili")) {
        j.remove(k);
      }
      j.add("stima_motore", st.json());
      j.addProperty("tetto_perdita_TUV", Math.round(def.tettoPerdita()));
      if (!c.tipo().conquista()) {
        j.addProperty(
            "rinforzi_possibili",
            st.tappa2().isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(st.tappa2()));
      }
      j.addProperty("presidio_atteso", OperazioniAnfibie.unitsText(st.presidio()));
      final JsonObject h = OperazioniCongiunte.valutaTenuta(proData, calc, def, t, st.presidio());
      j.add("contrattacco_massimo", h.get("contrattacco_massimo"));
      j.add("tenuta_per_impegno", h.get("tenuta_per_impegno"));
      annota(c, st, "in lista");
      stimaCorrente = null;
      return Optional.of(
          new Candidato(
              c.id(),
              c.tipo(),
              c.bersaglio(),
              c.apre(),
              c.chiude(),
              roundMin,
              c.raccolta(),
              c.zona(),
              c.successivi(),
              c.valore(),
              c.punteggio(),
              j));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Stratega: numeri del candidato " + c.bersaglio() + ": " + e);
      scarta(c, "numeri non calcolati (" + e + ")");
      return Optional.empty();
    }
  }

  private OperazioneCongiunta.Definizione definizioneTmp(final Candidato c, final int roundMin) {
    return OperazioneCongiunta.parse(
            wrap(
                definizione(
                    c,
                    OperazioneCongiunta.PREFISSO_STRATEGA + "candidato-" + c.tipo().id(),
                    roundMin,
                    0,
                    0,
                    "")))
        .get(0);
  }

  /**
   * Step COERENZA STIME: a nation of the alliance can take {@code t} alone this round with its
   * ordinary forces (Hard), at least {@code soglia_presa_hard}% win: an operation would be
   * redundant.
   */
  private Optional<String> presaDaHard(final Territory t) {
    final List<Unit> defenders = difensori(t);
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (p.isNull()
          || !ProPlusSettings.allianceOf(data, p).filter(alliance::equals).isPresent()
          || !inGuerra(p, t)) {
        continue;
      }
      final List<Unit> own = attaccanti(p, t, 1);
      if (own.stream().noneMatch(Matches.unitIsLand())) {
        continue;
      }
      final double win = defenders.isEmpty() ? 100 : stima(t, own, defenders).getWinPercentage();
      if (win >= cfg.sogliaPresaHard()) {
        return Optional.of(
            String.format(
                Locale.ROOT,
                "Hard lo prende da solo in questo round (%s, win %.0f%% >= %.0f%%)",
                p.getName(),
                win,
                cfg.sogliaPresaHard()));
      }
    }
    return Optional.empty();
  }

  // ------------------------------------------------------------------------------- offensive

  private List<Candidato> offensive() {
    final List<Candidato> list = new ArrayList<>();
    if (nations.size() < 2) {
      return list;
    }
    for (final Territory t : map.getTerritories()) {
      if (t.isWater() || t.getOwner().isNull() || !nemico(t)) {
        continue;
      }
      final double value = valore(t);
      if (value <= 0) {
        continue;
      }
      for (int i = 0; i < nations.size(); i++) {
        for (int k = i + 1; k < nations.size(); k++) {
          final GamePlayer a = player(nations.get(i));
          final GamePlayer b = player(nations.get(k));
          if (a == null || b == null || !inGuerra(a, t) || !inGuerra(b, t)) {
            continue;
          }
          final int ra = roundsTo(a, t, false);
          final int rb = roundsTo(b, t, true);
          if (ra > cfg.orizzonteRound() || rb > cfg.orizzonteRound()) {
            continue;
          }
          final int k0 = Math.max(1, Math.max(ra, rb));
          final List<Unit> a1 = attaccanti(a, t, k0);
          final List<Unit> a2 = attaccanti(b, t, k0);
          if (a1.isEmpty() || a2.stream().noneMatch(Matches.unitIsLand())) {
            continue;
          }
          list.add(offerta(Tipo.OFFENSIVA, t, a, b, round + k0 - 1, "", "", a1, a2, value));
        }
      }
    }
    return list;
  }

  /** Numbers of the steps (fast estimate) and the candidate of a conquest. */
  private Candidato offerta(
      final Tipo tipo,
      final Territory t,
      final GamePlayer a,
      final GamePlayer b,
      final int roundMin,
      final String gather,
      final String zone,
      final List<Unit> a1,
      final List<Unit> a2,
      final double value) {
    final List<Unit> defenders = difensori(t);
    final ProBattleResult r1 = stima(t, a1, defenders);
    final List<Unit> left =
        defenders.isEmpty() ? List.of() : new ArrayList<>(r1.getAverageDefendersRemaining());
    final ProBattleResult r2 = stima(t, a2, left);
    final double w1 = defenders.isEmpty() ? 100 : r1.getWinPercentage();
    final double w2 = left.isEmpty() ? 100 : r2.getWinPercentage();
    final double take = Math.min(1, w1 / 100 + (1 - w1 / 100) * w2 / 100);
    final double loss1 = tuv(a1) - tuv(r1.getAverageAttackersRemaining());
    final double loss2 = tuv(a2) - tuv(r2.getAverageAttackersRemaining());
    final List<String> next = tipo == Tipo.SBARCO ? successivi(t, a) : List.of();
    final JsonObject j = base(tipo, t, a.getName(), b.getName(), roundMin, value);
    if (!zone.isEmpty()) {
      j.addProperty("zona_raduno", zone);
      j.addProperty("raduno", gather);
    }
    j.addProperty("difensori", OperazioniAnfibie.unitsText(defenders));
    j.addProperty("tuv_difensori", Math.round(tuv(defenders)));
    final JsonObject s1 = new JsonObject();
    s1.addProperty("nazione", a.getName());
    s1.addProperty("attaccanti", OperazioniAnfibie.unitsText(a1));
    s1.addProperty("win_pct", Math.round(w1));
    s1.addProperty("perdita_attesa_TUV", Math.round(Math.max(0, loss1)));
    j.add("tappa1", s1);
    final JsonObject s2 = new JsonObject();
    s2.addProperty("nazione", b.getName());
    s2.addProperty("attaccanti", OperazioniAnfibie.unitsText(a2));
    s2.addProperty("win_pct", Math.round(w2));
    s2.addProperty("perdita_attesa_TUV", Math.round(Math.max(0, loss2)));
    j.add("tappa2", s2);
    j.addProperty("presa_stimata_pct", Math.round(take * 100));
    if (!next.isEmpty()) {
      j.add("bersagli_successivi_possibili", array(next));
    }
    final double score =
        value * take - Math.max(0, loss1) * 0.2 - (roundMin - round) * 2.0 + (take > 0.5 ? 5 : 0);
    return new Candidato(
        "",
        tipo,
        t.getName(),
        a.getName(),
        b.getName(),
        roundMin,
        gather,
        zone,
        next,
        value,
        score,
        j);
  }

  // --------------------------------------------------------------------------------- landing

  private List<Candidato> sbarchi() {
    final List<Candidato> list = new ArrayList<>();
    for (final Territory t : map.getTerritories()) {
      if (t.isWater() || t.getOwner().isNull() || !nemico(t)) {
        continue;
      }
      final double value = valore(t);
      if (value <= 0) {
        continue;
      }
      for (final Territory zone : map.getNeighbors(t, Matches.territoryIsWater())) {
        if (zone.anyUnitsMatch(
            Matches.unitIsEnemyOf(player(nations.get(0))).and(Matches.unitIsSea()))) {
          continue;
        }
        final Optional<Territory> gather =
            map.getNeighbors(zone, Matches.territoryIsLand()).stream()
                .filter(x -> !x.equals(t))
                .filter(x -> nations.contains(x.getOwner().getName()))
                .max(
                    Comparator.comparingInt(
                            (Territory x) ->
                                x.getUnitCollection().countMatches(Matches.unitIsLand()))
                        .thenComparing(Territory::getName, Comparator.reverseOrder()));
        if (gather.isEmpty()) {
          continue;
        }
        // who lands first: the nation with transports nearest to the zone, then another one
        String opener = null;
        int best = 99;
        for (final String n : nations) {
          final GamePlayer p = player(n);
          if (p == null || !inGuerra(p, t)) {
            continue;
          }
          final int r = roundsTransports(p, zone);
          if (r < best) {
            best = r;
            opener = n;
          }
        }
        if (opener == null || best > cfg.orizzonteRound()) {
          continue;
        }
        final GamePlayer a = player(opener);
        final int iA = nations.indexOf(opener);
        final int roundsA = best;
        final Optional<String> closer =
            nations.stream()
                .filter(n -> nations.indexOf(n) > iA)
                .filter(n -> inGuerra(player(n), t))
                .filter(
                    n ->
                        roundsTransports(player(n), zone) <= cfg.orizzonteRound()
                            || roundsTo(player(n), t, true) <= cfg.orizzonteRound())
                .findFirst();
        if (closer.isEmpty()) {
          continue;
        }
        final GamePlayer b = player(closer.get());
        final int k0 =
            Math.max(
                1, Math.max(roundsA, Math.min(roundsTransports(b, zone), roundsTo(b, t, true))));
        final List<Unit> a1 = aBordo(a, gather.get(), zone);
        final List<Unit> a2 = new ArrayList<>(aBordo(b, gather.get(), zone));
        attaccanti(b, t, k0).stream().filter(u -> !a2.contains(u)).forEach(a2::add);
        if (a1.isEmpty() || a2.isEmpty()) {
          continue;
        }
        list.add(
            offerta(
                Tipo.SBARCO,
                t,
                a,
                b,
                round + k0 - 1,
                gather.get().getName(),
                zone.getName(),
                a1,
                a2,
                value));
        break; // one landing zone per target
      }
    }
    return list;
  }

  /** Land units of {@code p} at or next to the gathering place, as many as its transports carry. */
  private List<Unit> aBordo(final GamePlayer p, final Territory gather, final Territory zone) {
    final long transports =
        map.getNeighbors(zone, 4).stream()
                .filter(Territory::isWater)
                .mapToLong(
                    z ->
                        z.getUnitCollection()
                            .countMatches(
                                Matches.unitIsOwnedBy(p).and(Matches.unitIsSeaTransport())))
                .sum()
            + zone.getUnitCollection()
                .countMatches(Matches.unitIsOwnedBy(p).and(Matches.unitIsSeaTransport()));
    final int capacity = (int) Math.max(transports, 1) * 2;
    final List<Unit> units = new ArrayList<>();
    for (final Territory x :
        concat(List.of(gather), map.getNeighbors(gather, Matches.territoryIsLand()))) {
      if (!Matches.isTerritoryAllied(p).test(x)) {
        continue;
      }
      units.addAll(
          x.getMatches(
              Matches.unitIsOwnedBy(p)
                  .and(Matches.unitIsLand())
                  .and(Matches.unitIsNotInfrastructure())
                  .and(attacca(p))));
    }
    units.sort(
        Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getAttack(p))
            .thenComparing(u -> u.getId().toString()));
    return units.stream().limit(capacity).toList();
  }

  /** Rounds for transports of {@code p} to reach {@code zone} (2 zones a round); 99 if none. */
  private int roundsTransports(final GamePlayer p, final Territory zone) {
    if (p == null) {
      return 99;
    }
    int best = 99;
    for (final Territory z : map.getTerritories()) {
      if (!z.isWater()
          || !z.anyUnitsMatch(Matches.unitIsOwnedBy(p).and(Matches.unitIsSeaTransport()))) {
        continue;
      }
      final int d = z.equals(zone) ? 0 : map.getDistance(z, zone, Matches.territoryIsWater());
      if (d >= 0) {
        best = Math.min(best, (d + 1) / 2);
      }
    }
    return best;
  }

  /** Enemy land territories next to the landing target, by value (next target of a bridgehead). */
  private List<String> successivi(final Territory t, final GamePlayer p) {
    return map.getNeighbors(t, Matches.territoryIsLand()).stream()
        .filter(x -> !x.getOwner().isNull() && nemico(x) && inGuerra(p, x))
        .filter(x -> valore(x) > 0)
        .sorted(
            Comparator.comparingDouble((Territory x) -> -valore(x))
                .thenComparing(Territory::getName))
        .limit(3)
        .map(Territory::getName)
        .toList();
  }

  // ----------------------------------------------------------------------- hold, reinforcement

  private List<Candidato> tenute() {
    final List<Candidato> list = new ArrayList<>();
    final GamePlayer any = player(nations.get(0));
    if (any == null) {
      return list;
    }
    for (final Territory t : map.getTerritories()) {
      if (t.isWater()
          || t.getOwner().isNull()
          || !dellAlleanza(t)
          || valore(t) < cfg.valoreMinimoTenuta()) {
        continue;
      }
      final List<Unit> enemies = nemiciVicini(t, any);
      if (enemies.stream().noneMatch(Matches.unitIsLand())) {
        continue; // planes alone do not take a territory
      }
      final List<Unit> defenders =
          t.getMatches(Matches.isUnitAllied(any).and(Matches.unitIsNotInfrastructure()));
      final double hold100 = tenuta(t, enemies, defenders, 100);
      final double hold50 = tenuta(t, enemies, defenders, 50);
      if (hold50 >= cfg.minacciaTenuta50Max() || hold100 >= 95) {
        continue; // no real threat
      }
      final String owner = t.getOwner().getName();
      final double value = valore(t);
      if (nations.contains(owner)) {
        // hold: the owner, with the nearest other nation of the alliance that can reinforce
        final String ally =
            nations.stream()
                .filter(n -> !n.equals(owner))
                .filter(n -> !rinforzi(player(n), t).isEmpty())
                .findFirst()
                .orElse(owner);
        list.add(tenuta(Tipo.TENUTA, t, owner, ally, defenders, enemies, hold50, hold100, value));
      }
      for (final String n : nations) {
        if (n.equals(owner)) {
          continue;
        }
        final List<Unit> help = rinforzi(player(n), t);
        if (help.isEmpty()) {
          continue;
        }
        final double withHelp = tenuta(t, enemies, concat(defenders, help), 50);
        if (withHelp <= hold50 + 5) {
          continue; // the help does not change the hold
        }
        final Candidato c =
            tenuta(Tipo.RINFORZO, t, n, n, defenders, enemies, hold50, hold100, value);
        c.json().addProperty("rinforzi_possibili", OperazioniAnfibie.unitsText(help));
        c.json().addProperty("tenuta_50_con_rinforzi_pct", Math.round(withHelp));
        list.add(c);
        break;
      }
    }
    return list;
  }

  private Candidato tenuta(
      final Tipo tipo,
      final Territory t,
      final String apre,
      final String chiude,
      final List<Unit> defenders,
      final List<Unit> enemies,
      final double hold50,
      final double hold100,
      final double value) {
    final JsonObject j = base(tipo, t, apre, chiude, round, value);
    j.addProperty("proprietario", t.getOwner().getName());
    j.addProperty("difensori", OperazioniAnfibie.unitsText(defenders));
    j.addProperty("tuv_difensori", Math.round(tuv(defenders)));
    j.addProperty("nemici_in_portata_TUV", Math.round(tuv(enemies)));
    j.addProperty("tenuta_ora_50_pct", Math.round(hold50));
    j.addProperty("tenuta_ora_100_pct", Math.round(hold100));
    final double score = value * (1 - hold50 / 100) + (isCapital(t) ? 10 : 0);
    return new Candidato(
        "", tipo, t.getName(), apre, chiude, round, "", "", List.of(), value, score, j);
  }

  /** Units of {@code p} that can reach {@code t} this turn (land within 2, planes in range). */
  private List<Unit> rinforzi(final GamePlayer p, final Territory t) {
    if (p == null) {
      return List.of();
    }
    final List<Unit> list = new ArrayList<>();
    for (final Territory x : map.getNeighbors(t, 4)) {
      if (x.isWater() || !Matches.isTerritoryAllied(p).test(x)) {
        continue;
      }
      final int land = map.getDistance(x, t, Matches.territoryIsLand());
      final int any = map.getDistance(x, t);
      for (final Unit u :
          x.getMatches(Matches.unitIsOwnedBy(p).and(Matches.unitIsNotInfrastructure()))) {
        final int move = u.getUnitAttachment().getMovement(p);
        if (Matches.unitIsLand().test(u) && land >= 1 && land <= Math.min(2, move)) {
          list.add(u);
        } else if (Matches.unitIsAir().test(u) && any >= 1 && any <= move) {
          list.add(u);
        }
      }
    }
    return list;
  }

  /** Enemy units (at war with the alliance) that can reach {@code t} next turn, rough. */
  private List<Unit> nemiciVicini(final Territory t, final GamePlayer p) {
    final List<Unit> list = new ArrayList<>();
    for (final Territory x : map.getNeighbors(t, 6)) {
      final int land = x.isWater() ? -1 : map.getDistance(x, t, Matches.territoryIsLand());
      final int any = map.getDistance(x, t);
      for (final Unit u :
          x.getMatches(Matches.unitIsEnemyOf(p).and(Matches.unitIsNotInfrastructure()))) {
        if (!data.getRelationshipTracker().isAtWar(p, u.getOwner())) {
          continue;
        }
        final int move = u.getUnitAttachment().getMovement(u.getOwner());
        if (Matches.unitIsLand().test(u) && land >= 1 && land <= move) {
          list.add(u);
        } else if (Matches.unitIsAir().test(u)
            && any >= 1
            && any <= move / 2 + 1
            && u.getUnitAttachment().getAttack(u.getOwner()) > 0) {
          list.add(u);
        }
      }
    }
    return list;
  }

  /** Hold% of {@code t} if the enemy commits {@code pct}% of {@code enemies}, nearest first. */
  private double tenuta(
      final Territory t, final List<Unit> enemies, final List<Unit> defenders, final int pct) {
    final List<Unit> sorted = new ArrayList<>(enemies);
    sorted.sort(Comparator.comparingDouble(u -> -tuv(List.of(u))));
    final double total = tuv(sorted);
    final List<Unit> part = new ArrayList<>();
    double sum = 0;
    for (final Unit u : sorted) {
      if (pct < 100 && sum >= total * pct / 100.0) {
        break;
      }
      part.add(u);
      sum += tuv(List.of(u));
    }
    if (part.isEmpty()) {
      return 100;
    }
    if (defenders.isEmpty()) {
      return 0;
    }
    return 100 - stima(t, part, defenders).getWinPercentage();
  }

  // ===================================================================== reading the enemy

  /**
   * The reading of the opponent: enemy land TUV per front of the strategy file and its change from
   * the last round, threats on the alliance's capitals and VC at the next turn, the enemy's real
   * answers in this game per kind of target, fronts the enemy must hold.
   */
  JsonObject letturaAvversario(final JsonObject previous) {
    final JsonObject o = new JsonObject();
    final GamePlayer me = player(nations.get(0));
    // fronts: enemy land TUV in each front's territories (strategy file), change since last round
    final JsonArray fronts = new JsonArray();
    final JsonObject now = new JsonObject();
    games.strategy.triplea.ai.proplus.strategia.Strategie.forGame(data)
        .ifPresent(
            s -> {
              for (final String f : s.getFronti()) {
                double tuvEnemy = 0;
                double tuvOwn = 0;
                for (final String name : s.territoriDelFronte(f)) {
                  final Territory t = map.getTerritoryOrNull(name);
                  if (t == null) {
                    continue;
                  }
                  tuvEnemy +=
                      tuv(
                          t.getMatches(
                              Matches.unitIsEnemyOf(me).and(Matches.unitIsNotInfrastructure())));
                  tuvOwn +=
                      tuv(
                          t.getMatches(
                              Matches.isUnitAllied(me).and(Matches.unitIsNotInfrastructure())));
                }
                final JsonObject x = new JsonObject();
                x.addProperty("fronte", f);
                x.addProperty("tuv_nemico", Math.round(tuvEnemy));
                x.addProperty("tuv_alleanza", Math.round(tuvOwn));
                if (previous.has(f)) {
                  x.addProperty(
                      "variazione_tuv_nemico",
                      Math.round(tuvEnemy - previous.get(f).getAsDouble()));
                }
                now.addProperty(f, Math.round(tuvEnemy));
                fronts.add(x);
              }
            });
    o.add("ammassamenti_per_fronte", fronts);
    o.add("_fronti_ora", now);
    // threats on own capitals and VC at the next enemy turn
    final JsonArray threats = new JsonArray();
    for (final Territory t : map.getTerritories()) {
      if (t.isWater() || t.getOwner().isNull() || !Matches.isTerritoryAllied(me).test(t)) {
        continue;
      }
      final boolean vc = TerritoryAttachment.get(t).map(a -> a.getVictoryCity() > 0).orElse(false);
      if (!vc && !isCapital(t)) {
        continue;
      }
      final List<Unit> enemies = nemiciVicini(t, me);
      if (enemies.isEmpty()) {
        continue;
      }
      final List<Unit> defenders =
          t.getMatches(Matches.isUnitAllied(me).and(Matches.unitIsNotInfrastructure()));
      final JsonObject x = new JsonObject();
      x.addProperty("territorio", t.getName());
      x.addProperty("capitale", isCapital(t));
      x.addProperty("tuv_nemico_in_portata", Math.round(tuv(enemies)));
      x.addProperty("tuv_difensori", Math.round(tuv(defenders)));
      x.addProperty("tenuta_100_pct", Math.round(tenuta(t, enemies, defenders, 100)));
      threats.add(x);
    }
    o.add("minacce_capitali_vc", threats);
    o.add("storico_nemico_per_tipo", storicoPerTipo());
    return o;
  }

  /**
   * The enemy's real answers (fraction of the forces in reach really committed) on the targets of
   * the alliance's operations in this game, per kind of target: land, coast/landing, capital.
   */
  private JsonObject storicoPerTipo() {
    final Map<String, double[]> acc = new LinkedHashMap<>();
    acc.put("terra", new double[2]);
    acc.put("costa_sbarco", new double[2]);
    acc.put("capitale", new double[2]);
    final List<String> ids = new ArrayList<>();
    OperazioneCongiunta.dinamicheJson(data.getProperties(), alliance)
        .forEach(e -> ids.add(e.getAsJsonObject().get("id").getAsString()));
    for (final JsonObject past : Stratega.storicoOperazioni(data.getProperties(), alliance)) {
      final String id = past.get("id").getAsString();
      if (!ids.contains(id)) {
        ids.add(id);
      }
    }
    for (final String id : ids) {
      final JsonObject s = StoricoNemico.riassunto(data.getProperties(), id);
      if (!s.has("osservazioni") || s.get("osservazioni").getAsInt() == 0) {
        continue;
      }
      final String tipo = OperazioneCongiunta.tipoStratega(id);
      final Territory t =
          map.getTerritoryOrNull(Stratega.bersaglioDi(data.getProperties(), alliance, id));
      final String kind =
          t != null && isCapital(t) ? "capitale" : "sbarco".equals(tipo) ? "costa_sbarco" : "terra";
      final double[] a = acc.get(kind);
      a[0] += s.get("frazione_impegnata_media").getAsDouble();
      a[1]++;
    }
    final JsonObject o = new JsonObject();
    acc.forEach(
        (k, a) ->
            o.addProperty(
                k,
                a[1] == 0
                    ? "nessun dato"
                    : String.format(Locale.ROOT, "%.2f (%d op.)", a[0] / a[1], (int) a[1])));
    return o;
  }

  // =========================================================================== definitions

  /**
   * The definition (data-file format) the engine of the joint operations runs for a candidate.
   *
   * @param roundObiettivo the target round (minimum: the candidate's)
   * @param tettoPerdita loss cap of a step (0 = default of the data file)
   * @param durataTenuta rounds to hold (0 = default)
   * @param successivo next target of a landing ("" = none)
   */
  JsonObject definizione(
      final Candidato c,
      final String id,
      final int roundObiettivo,
      final double tettoPerdita,
      final int durataTenuta,
      final String successivo) {
    final JsonObject o = new JsonObject();
    o.addProperty("id", id);
    o.addProperty("nome", nome(c.tipo(), c.bersaglio()));
    o.addProperty("alleanza", alliance);
    o.addProperty("bersaglio", c.bersaglio());
    o.addProperty("apre", c.apre());
    o.addProperty("chiude", c.chiude());
    final Territory t = map.getTerritoryOrNull(c.bersaglio());
    final List<String> near = t == null ? List.of() : vicini(t, c.apre());
    final List<String> nearB = t == null ? List.of() : vicini(t, c.chiude());
    o.add("raccolta_apre", array(c.raccolta().isEmpty() ? near : List.of(c.raccolta())));
    o.add("raccolta_chiude", array(c.raccolta().isEmpty() ? nearB : List.of(c.raccolta())));
    if (!c.zona().isEmpty()) {
      o.addProperty("zona_raccolta", c.zona());
    }
    o.add("accesso", new JsonArray());
    // an offensive whose opener strikes with planes: they gather next to the target first
    int planes = 0;
    if (c.tipo() == Tipo.OFFENSIVA && t != null) {
      planes =
          (int)
              Math.min(
                  4,
                  attaccanti(player(c.apre()), t, Math.max(1, c.roundMinimo() - round + 1)).stream()
                      .filter(Matches.unitIsAir())
                      .count());
      if (planes > 0) {
        o.add("raccolta_apre", array(raccoltaAerei(t, c.apre())));
      }
    }
    o.addProperty("aerei_apre", planes);
    final int target = Math.max(roundObiettivo, c.roundMinimo());
    o.addProperty("round_min", target);
    o.addProperty("round_max", target + cfg.durataOperazioneRound() - 1);
    if (c.tipo() == Tipo.SBARCO) {
      final JsonObject tr = new JsonObject();
      tr.addProperty(c.apre(), cfg.trasportiSbarco());
      if (!c.chiude().equals(c.apre())) {
        tr.addProperty(c.chiude(), cfg.trasportiSbarco());
      }
      o.add("trasporti", tr);
    }
    final JsonObject reserves = new JsonObject();
    for (final String n : List.of(c.apre(), c.chiude())) {
      if (reserves.has(n)) {
        continue;
      }
      final JsonObject r = new JsonObject();
      final JsonArray buy = new JsonArray();
      if (c.tipo() == Tipo.SBARCO) {
        buy.add(acquisto("trasporti", cfg.tipiTrasporto(), 1));
      }
      buy.add(acquisto("terra", cfg.tipiTerra(), cfg.riserveTerra()));
      r.add("acquisti", buy);
      r.add("piazzamento", new JsonArray());
      r.addProperty("quota", cfg.quotaPuMaxPct());
      reserves.add(n, r);
    }
    o.add("riserve", reserves);
    // step C1-bis: the value d'alleanza uses the target's value in PU equivalents (components
    // below); the old index (production + VC + capital) only ranks candidates and scales the cap
    final int holdRounds =
        Math.max(
            1,
            Math.min(
                cfg.durataTenutaMax(), durataTenuta > 0 ? durataTenuta : cfg.tenutaRoundDefault()));
    final JsonObject parts =
        t == null ? new JsonObject() : valoreBersaglioPu(t, c.apre(), holdRounds);
    o.addProperty(
        "valore",
        Math.max(1, parts.has("totale_PU") ? parts.get("totale_PU").getAsDouble() : c.valore()));
    o.addProperty("indice_valore", Math.max(1, c.valore()));
    o.add("valore_componenti", parts);
    // step COERENZA STIME: proportional to the value, the LLM may ask another cap up to a maximum
    o.addProperty(
        "tetto_perdita", Math.round(cfg.tettoPerdita(Math.max(1, c.valore()), tettoPerdita)));
    o.addProperty("tolleranza_difensori", cfg.tolleranzaDifensori());
    o.addProperty("soglia_chiusura", cfg.sogliaChiusura());
    o.addProperty("max_rinvii", cfg.maxRinvii());
    if (!successivo.isEmpty()) {
      o.addProperty("bersaglio_finale", successivo);
    }
    final JsonObject h = new JsonObject();
    final int hold =
        Math.min(cfg.durataTenutaMax(), durataTenuta > 0 ? durataTenuta : cfg.tenutaRoundDefault());
    h.addProperty("round", Math.max(1, hold));
    h.addProperty("soglia_rinforzi", cfg.sogliaRinforzi());
    h.addProperty("soglia_ritirata", cfg.sogliaRitirata());
    h.addProperty("aerei_copertura", cfg.aereiCopertura());
    h.addProperty("quota_acquisti_nemici", cfg.quotaAcquistiNemici());
    h.addProperty("riserve", cfg.riserveTerra());
    o.add("tenuta", h);
    return o;
  }

  static String nome(final Tipo tipo, final String target) {
    return switch (tipo) {
      case OFFENSIVA -> "Offensiva su " + target;
      case SBARCO -> "Sbarco su " + target;
      case TENUTA -> "Tenuta di " + target;
      case RINFORZO -> "Rinforzo di " + target;
    };
  }

  static JsonObject wrap(final JsonObject def) {
    final JsonObject root = new JsonObject();
    final JsonArray a = new JsonArray();
    a.add(def);
    root.add("operazioni", a);
    return root;
  }

  private static JsonObject acquisto(final String cat, final List<String> types, final int min) {
    final JsonObject a = new JsonObject();
    a.addProperty("categoria", cat);
    a.add("tipi", array(types));
    a.addProperty("minimo", min);
    return a;
  }

  /** Territories of the alliance next to {@code t}, where {@code nation}'s troops gather. */
  private List<String> vicini(final Territory t, final String nation) {
    final GamePlayer p = player(nation);
    if (p == null) {
      return List.of();
    }
    final List<Territory> list =
        new ArrayList<>(
            map.getNeighbors(t, Matches.territoryIsLand()).stream()
                .filter(Matches.isTerritoryAllied(p))
                .toList());
    list.sort(
        Comparator.comparingInt(
                (Territory x) -> -x.getUnitCollection().countMatches(Matches.unitIsOwnedBy(p)))
            .thenComparing(Territory::getName));
    return list.stream().limit(3).map(Territory::getName).toList();
  }

  /** Land of the alliance within 2 of {@code t} where {@code nation}'s planes can gather. */
  private List<String> raccoltaAerei(final Territory t, final String nation) {
    final GamePlayer p = player(nation);
    if (p == null) {
      return List.of();
    }
    return map.getNeighbors(t, 2).stream()
        .filter(x -> !x.isWater() && !x.equals(t) && Matches.isTerritoryAllied(p).test(x))
        .sorted(
            Comparator.comparingInt((Territory x) -> map.getDistance(x, t))
                .thenComparing(Territory::getName))
        .limit(3)
        .map(Territory::getName)
        .toList();
  }

  // ================================================================================ helpers

  private JsonObject base(
      final Tipo tipo,
      final Territory t,
      final String apre,
      final String chiude,
      final int roundMin,
      final double value) {
    final JsonObject j = new JsonObject();
    j.addProperty("tipo", tipo.id());
    j.addProperty("bersaglio", t.getName());
    j.addProperty("proprietario", t.getOwner().getName());
    j.add("nazioni", array(apre.equals(chiude) ? List.of(apre) : List.of(apre, chiude)));
    j.addProperty(
        "ruoli",
        switch (tipo) {
          case OFFENSIVA -> apre + " indebolisce (tappa 1), " + chiude + " prende (tappa 2)";
          case SBARCO -> apre + " sbarca per primo, " + chiude + " completa";
          case TENUTA ->
              apre.equals(chiude)
                  ? apre + " presidia"
                  : apre + " presidia, " + chiude + " rinforza";
          case RINFORZO -> apre + " rinforza il territorio di " + t.getOwner().getName();
        });
    j.addProperty("round_minimo", roundMin);
    final JsonObject v = new JsonObject();
    v.addProperty("indice_valore_PU", Math.round(value));
    final JsonObject parts = valoreBersaglioPu(t, apre, Math.max(1, cfg.tenutaRoundDefault()));
    v.addProperty("valore_bersaglio_PU", parts.get("totale_PU").getAsLong());
    v.add("componenti_PU", parts);
    TerritoryAttachment.get(t)
        .ifPresent(
            a -> {
              v.addProperty("produzione_PU", a.getProduction());
              v.addProperty("victory_city", a.getVictoryCity() > 0);
              v.addProperty("capitale", a.isCapital());
            });
    j.add("valore", v);
    return j;
  }

  /**
   * Step C1-bis: the value of {@code t} for the alliance in PU equivalents over {@code holdRounds}
   * rounds of hold (formula and rationale in {@code stratega-llm.json}, {@code valore_pu}): income
   * swing {@code fattore_reddito} x production, national objectives at stake, VC weight per round,
   * and for the owner's own capital its cash plus its income.
   */
  JsonObject valoreBersaglioPu(final Territory t, final String nation, final int holdRounds) {
    final JsonObject o = new JsonObject();
    final TerritoryAttachment a = TerritoryAttachment.get(t).orElse(null);
    final int production = a == null ? 0 : a.getProduction();
    final int vc = a == null ? 0 : a.getVictoryCity();
    final GamePlayer me = player(nation);
    final double objectives =
        me == null ? 0 : games.strategy.triplea.ai.pro.util.ProNationalObjectiveUtils.stake(me, t);
    // the owner's own capital (rules of the map: ProUtils, as Hard): its cash when it falls and
    // its income while it is held
    final int cash = games.strategy.triplea.ai.pro.util.ProUtils.capturedTreasury(t, data);
    final double ownerIncome = games.strategy.triplea.ai.pro.util.ProUtils.capitalValue(t, data);
    final double capital = cash + ownerIncome * holdRounds;
    if (capital > 0) {
      o.addProperty("capitale_cassa_PU", cash);
      o.addProperty("capitale_reddito_per_round_PU", Math.round(ownerIncome));
    }
    final double income = cfg.fattoreReddito() * production * holdRounds;
    final double objectivesPu = objectives * holdRounds;
    final double vcPu = vc > 0 ? cfg.valoreVcPerRound() * vc * holdRounds : 0;
    o.addProperty("round_tenuta", holdRounds);
    o.addProperty("reddito_PU", Math.round(income));
    o.addProperty("obiettivi_nazionali_PU", Math.round(objectivesPu));
    o.addProperty("victory_city_PU", Math.round(vcPu));
    o.addProperty("capitale_PU", Math.round(capital));
    o.addProperty("totale_PU", Math.round(income + objectivesPu + vcPu + capital));
    return o;
  }

  /** Index of the value in PUs (production, VC, capital): ranks the candidates, scales the cap. */
  double valore(final Territory t) {
    return TerritoryAttachment.get(t)
        .map(
            a ->
                a.getProduction()
                    + (a.getVictoryCity() > 0 ? cfg.valoreVc() : 0)
                    + (a.isCapital() ? cfg.valoreCapitale() : 0))
        .orElse(0.0);
  }

  private boolean nemico(final Territory t) {
    final GamePlayer p = player(nations.get(0));
    return p != null
        && Matches.isTerritoryEnemy(p).test(t)
        && !Matches.isTerritoryAllied(p).test(t)
        && ProPlusSettings.allianceOf(data, t.getOwner())
            .filter(a -> !a.equals(alliance))
            .isPresent();
  }

  /** A territory of a nation of the alliance (not a neutral's). */
  private boolean dellAlleanza(final Territory t) {
    return ProPlusSettings.allianceOf(data, t.getOwner()).filter(alliance::equals).isPresent();
  }

  private boolean inGuerra(final GamePlayer p, final Territory t) {
    return p != null
        && !t.getOwner().isNull()
        && data.getRelationshipTracker().isAtWar(p, t.getOwner());
  }

  /**
   * Rounds for {@code p}'s attacking units to reach {@code t} (land by land distance and movement,
   * planes within half their range if {@code !needLand}); 99 if none within the horizon.
   */
  private int roundsTo(final GamePlayer p, final Territory t, final boolean needLand) {
    if (p == null) {
      return 99;
    }
    int best = 99;
    for (final Territory x : map.getNeighbors(t, 3 * cfg.orizzonteRound())) {
      if (x.isWater()) {
        continue;
      }
      final int land = map.getDistance(x, t, Matches.territoryIsLand());
      final int any = map.getDistance(x, t);
      for (final Unit u : x.getMatches(Matches.unitIsOwnedBy(p).and(attacca(p)))) {
        final int move = Math.max(1, u.getUnitAttachment().getMovement(p));
        if (Matches.unitIsLand().test(u) && land >= 1) {
          best = Math.min(best, (land + move - 1) / move);
        } else if (!needLand && Matches.unitIsAir().test(u) && any >= 1) {
          best = Math.min(best, any <= move / 2 ? 1 : 1 + (any - move / 2 + move - 1) / move);
        }
      }
    }
    return best;
  }

  /** Attacking units of {@code p} that can be on {@code t} within {@code rounds} rounds. */
  private List<Unit> attaccanti(final GamePlayer p, final Territory t, final int rounds) {
    if (p == null) {
      return List.of();
    }
    final int k = Math.max(1, rounds);
    final List<Unit> list = new ArrayList<>();
    for (final Territory x : map.getNeighbors(t, 3 * k)) {
      if (x.isWater()) {
        continue;
      }
      final int land = map.getDistance(x, t, Matches.territoryIsLand());
      final int any = map.getDistance(x, t);
      for (final Unit u : x.getMatches(Matches.unitIsOwnedBy(p).and(attacca(p)))) {
        final int move = Math.max(1, u.getUnitAttachment().getMovement(p));
        if (Matches.unitIsLand().test(u) && land >= 1 && land <= move * k) {
          list.add(u);
        } else if (Matches.unitIsAir().test(u) && any >= 1 && any <= move / 2 + move * (k - 1)) {
          list.add(u);
        }
      }
    }
    // the capital's guard stays home (rough: half of the land units in an own capital)
    return list;
  }

  private List<Unit> difensori(final Territory t) {
    return t.getMatches(Matches.unitIsNotInfrastructure().and(u -> !u.getOwner().isNull()));
  }

  private ProBattleResult stima(
      final Territory t, final Collection<Unit> attackers, final Collection<Unit> defenders) {
    if (attackers.isEmpty()) {
      return new ProBattleResult();
    }
    return calc.estimateAttackBattleResults(
        proData, t, new ArrayList<>(attackers), new ArrayList<>(defenders), List.of());
  }

  private static Predicate<Unit> attacca(final GamePlayer p) {
    return Matches.unitIsNotInfrastructure()
        .and(u -> u.getUnitAttachment().getAttack(p) > 0)
        .and(Matches.unitIsSea().negate());
  }

  private static boolean isCapital(final Territory t) {
    return TerritoryAttachment.get(t).map(TerritoryAttachment::isCapital).orElse(false);
  }

  private @javax.annotation.Nullable GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private double tuv(final Collection<Unit> units) {
    return TuvUtils.getTuv(units, proData.getUnitValueMap());
  }

  static JsonArray array(final Collection<String> list) {
    final JsonArray a = new JsonArray();
    list.stream().filter(Objects::nonNull).forEach(a::add);
    return a;
  }

  private static <T> List<T> concat(final Collection<T> a, final Collection<T> b) {
    final List<T> l = new ArrayList<>(a);
    l.addAll(b);
    return l;
  }
}
