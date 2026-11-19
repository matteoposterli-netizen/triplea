package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProNationalObjectiveUtils;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step P6, A ({@code sbarco_concentrato}): for the nation of the data, the whole game, its LOADED
 * transports (own cargo on board when the combat move starts) are moved by this lever, before Hard:
 *
 * <ol>
 *   <li>Valid targets: land held by an enemy at war (never an allied base, never a neutral).
 *   <li>If the cargo that can reach a valid target this turn wins together with at least {@code
 *       soglia_vittoria}, all of it lands there at once; among such targets Hard's values decide
 *       (production, factory, victory city, capital), not the easiest. Repeated for the transports
 *       left.
 *   <li>Else the force waits on board ({@link Decisione#ATTESA}) for at most {@code
 *       round_di_attesa} round: in the non-combat move it gathers in one sea zone from which a
 *       valid target is reachable next turn (the transports arriving join it), with the smallest
 *       escort that is enough (all the escort if none is enough). It never unloads on a friendly
 *       base.
 *   <li>After the wait it lands anyway ({@link Decisione#SBARCO_DOPO_ATTESA}) on the valid target
 *       with the best exchange: value x win + enemy TUV destroyed - own TUV lost - the expected
 *       loss of the transports exposed (with every escort that can reach their zone).
 *   <li>Exposed transports: the answer is the escort, never a dropped landing.
 *   <li>After landing the empty transports are Hard's again; the purchases are Hard's.
 * </ol>
 *
 * <p>Every decision goes to the register (type {@value #TIPO}).
 */
public final class SbarcoConcentrato {
  private SbarcoConcentrato() {}

  static final String TIPO = "sbarco_concentrato";

  /** The decision for a group of loaded transports this turn. */
  enum Decisione {
    SBARCO_IN_FORZA,
    ATTESA,
    SBARCO_DOPO_ATTESA,
    AVVICINAMENTO
  }

  // ======================================================================== activation

  static boolean attivo(final GameState data, @Nullable final GamePlayer player) {
    return IndicazioniP6.attivo(data, player, StrategaInterruttori.SBARCO_CONCENTRATO);
  }

  // ======================================================================== pure rules

  /**
   * A valid target: land held by an enemy at war that belongs to the other main alliance (the
   * configurable alliances of the map); never a neutral (its own alliance, even if at war), never
   * allied or own.
   */
  static boolean bersaglioValido(final GamePlayer player, final Territory t) {
    if (t.isWater()
        || t.getOwner().isNull()
        || t.isOwnedBy(player)
        || !player.isAtWar(t.getOwner())
        || ProUtils.isNeutralLand(t)) {
      return false;
    }
    final Optional<String> own = ProPlusSettings.allianceOf(t.getData(), player);
    final Optional<String> theirs = ProPlusSettings.allianceOf(t.getData(), t.getOwner());
    return own.isPresent() && theirs.isPresent() && !own.equals(theirs);
  }

  /** One target's estimate with the cargo that can reach it. */
  record Stima(
      String bersaglio,
      List<String> trasporti,
      int unita,
      double vittoria,
      double valore,
      double tuvSwing,
      double rischio) {

    double scambio() {
      return calcola(vittoria, valore, tuvSwing, rischio);
    }

    static double calcola(
        final double vittoria, final double valore, final double tuvSwing, final double rischio) {
      return vittoria / 100.0 * valore + tuvSwing - rischio;
    }
  }

  /**
   * The landing in force (pure rule): among the estimates that win at least {@code soglia}, the one
   * of highest value (ties: higher win, then the name). Empty if none wins enough.
   */
  static Optional<Stima> inForza(final Collection<Stima> stime, final double soglia) {
    return stime.stream()
        .filter(s -> s.unita() > 0 && s.vittoria() >= soglia)
        .max(
            Comparator.comparingDouble(Stima::valore)
                .thenComparingDouble(Stima::vittoria)
                .thenComparing(Stima::bersaglio, Comparator.reverseOrder()));
  }

  /**
   * The landing after the wait (pure rule): the best exchange, whatever the win and the exposure
   * (ties: higher value, then the name). Empty only without estimates.
   */
  static Optional<Stima> dopoAttesa(final Collection<Stima> stime) {
    return stime.stream()
        .filter(s -> s.unita() > 0)
        .max(
            Comparator.comparingDouble(Stima::scambio)
                .thenComparingDouble(Stima::valore)
                .thenComparing(Stima::bersaglio, Comparator.reverseOrder()));
  }

  /**
   * The decision for one loaded transport without a landing in force (pure rule): out of reach of
   * every valid target = approach; in reach and waited at least {@code attesa} rounds = land
   * anyway; else wait.
   */
  static Decisione decisione(
      final boolean inPortata,
      @Nullable final Integer attesoDal,
      final int round,
      final int attesa) {
    if (!inPortata) {
      return Decisione.AVVICINAMENTO;
    }
    return attesoDal != null && round - attesoDal >= attesa
        ? Decisione.SBARCO_DOPO_ATTESA
        : Decisione.ATTESA;
  }

  /**
   * Hard's value of a target: 3 x production (national objectives included) x (1 + enemy factory) +
   * 3 x victory cities x their value + 3 x production x the capital's multiplier.
   */
  static double valore(final ProData proData, final GamePlayer player, final Territory t) {
    final IndicazioniP6.Config cfg = IndicazioniP6.Config.get();
    final double prod =
        TerritoryAttachment.getProduction(t) + ProNationalObjectiveUtils.value(proData, player, t);
    final int factory = ProMatches.territoryHasInfraFactoryAndIsEnemyLand(player).test(t) ? 1 : 0;
    final Optional<TerritoryAttachment> ta = TerritoryAttachment.get(t);
    final int vc = ta.map(TerritoryAttachment::getVictoryCity).orElse(0);
    final int capital = ta.map(TerritoryAttachment::isCapital).orElse(false) ? 1 : 0;
    return 3.0 * prod * (1 + factory)
        + 3.0 * vc * cfg.valoreCittaVittoria()
        + 3.0 * prod * cfg.moltiplicatoreCapitale() * capital;
  }

  // ===================================================================== turn's state

  /** A landing made this turn (or last turn, for the follow-up). */
  static final class Sbarco {
    final String decisione;
    final String bersaglio;
    final List<String> trasporti;
    final Map<String, String> zone;
    final int unita;
    final int round;
    final JsonObject riga;
    int sbarcate;

    Sbarco(
        final String decisione,
        final String bersaglio,
        final List<String> trasporti,
        final Map<String, String> zone,
        final int unita,
        final int round,
        final JsonObject riga) {
      this.decisione = decisione;
      this.bersaglio = bersaglio;
      this.trasporti = trasporti;
      this.zone = zone;
      this.unita = unita;
      this.round = round;
      this.riga = riga;
    }
  }

  /** The state of one AI (one nation) across its turns. */
  public static final class Stato {
    /** Transport id → the round it first waited in reach of a valid target. */
    final Map<String, Integer> attese = new TreeMap<>();

    /** The landings of the current round (from the combat move). */
    final List<Sbarco> sbarchi = new ArrayList<>();

    /** Last round's landings, for the follow-up at the start of the next turn. */
    final List<Sbarco> daSeguire = new ArrayList<>();

    /** The loaded transports left on board this round (ids). */
    final Set<String> aBordo = new LinkedHashSet<>();

    /** The troops waiting at a base this round (ids): held in the non-combat move too. */
    final Set<String> truppeBase = new LinkedHashSet<>();

    int round = -1;

    void inizio(final int r) {
      if (r != round) {
        round = r;
        sbarchi.clear();
        aBordo.clear();
        truppeBase.clear();
      }
    }
  }

  // ===================================================================== combat move

  /**
   * The combat move before Hard's (real board): landings in force, landings after the wait, the
   * waits. Returns every loaded transport and its cargo (Hard must leave them in its combat move).
   */
  static Set<Unit> combattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate del,
      final Stato st) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final int round = data.getSequence().getRound();
    st.inizio(round);
    final IndicazioniP6.Config cfg = IndicazioniP6.Config.get();
    final List<Unit> loaded = caricati(data, player);
    // the force at the bases: empty transports next to a friendly base with own troops (planned
    // load)
    final Map<Unit, Carico> atBase = allaBase(data, player);
    final List<Unit> force = new ArrayList<>(loaded);
    force.addAll(atBase.keySet());
    final Forza f = new Forza(atBase);
    st.truppeBase.clear();
    if (force.isEmpty()) {
      st.attese.clear();
      return Set.of();
    }
    final Set<Unit> held = new LinkedHashSet<>();
    for (final Unit tr : loaded) {
      held.add(tr);
      held.addAll(tr.getTransporting());
    }
    final Map<Unit, Territory> where = posizioni(data, force);
    final Map<Territory, Map<Unit, Arrivo>> reach = raggiunge(data, player, force, where, f);
    final Set<Unit> inReach = new LinkedHashSet<>();
    reach.values().forEach(m -> inReach.addAll(m.keySet()));
    // the waits of transports no longer loaded and of bases without a force in reach are over
    final Set<String> keys = new HashSet<>();
    loaded.forEach(u -> keys.add(f.chiave(u)));
    inReach.forEach(u -> keys.add(f.chiave(u)));
    st.attese.keySet().retainAll(keys);

    // the turn's context for every row
    final JsonObject ctx = new JsonObject();
    final List<Unit> cargo = new ArrayList<>();
    loaded.forEach(u -> cargo.addAll(u.getTransporting()));
    ctx.add("carico_a_bordo", TrasportiProtetti.perTipo(cargo));
    ctx.addProperty("unita_a_bordo", cargo.size());
    ctx.addProperty("trasporti_carichi", loaded.size());
    final List<Unit> baseTroops = new ArrayList<>();
    atBase.values().forEach(c -> baseTroops.addAll(c.truppe()));
    ctx.add("carico_alle_basi", TrasportiProtetti.perTipo(baseTroops));
    ctx.addProperty("unita_alle_basi", baseTroops.size());
    ctx.addProperty("trasporti_alle_basi", atBase.size());
    final JsonArray basesJson = new JsonArray();
    atBase.values().stream()
        .map(c -> c.base().getName())
        .distinct()
        .sorted()
        .forEach(basesJson::add);
    ctx.add("basi", basesJson);
    ctx.addProperty("trasporti_in_portata", inReach.size());
    if (inReach.isEmpty() && loaded.isEmpty()) {
      return held;
    }
    ctx.add("scorta_in_mare", TrasportiProtetti.perTipo(ForzaSbarco.flotta(data, player)));
    ctx.addProperty("soglia_vittoria", cfg.sogliaVittoria());

    final Set<Unit> remaining = new LinkedHashSet<>(inReach);
    final JsonArray targets = new JsonArray();
    boolean first = true;
    // 1. landings in force (repeated for the transports left)
    while (!remaining.isEmpty()) {
      final List<Stima> est = new ArrayList<>();
      for (final var e : ordinati(reach)) {
        final List<Unit> group = gruppo(e.getValue(), remaining);
        if (!group.isEmpty()) {
          est.add(stima(proData, calc, player, e.getKey(), group, null, f));
        }
      }
      if (first) {
        est.forEach(s -> targets.add(json(s)));
        ctx.add("bersagli", targets);
        first = false;
      }
      final Optional<Stima> best = inForza(est, cfg.sogliaVittoria());
      if (best.isEmpty()) {
        break;
      }
      final Territory t = data.getMap().getTerritoryOrNull(best.get().bersaglio());
      final List<Unit> group = gruppo(reach.get(t), remaining);
      sbarca(
          data,
          player,
          del,
          st,
          Decisione.SBARCO_IN_FORZA,
          best.get(),
          t,
          group,
          reach.get(t),
          f,
          ctx,
          String.format(
              java.util.Locale.ROOT,
              "tutto il carico che arriva vince %.1f%% (>= %.0f%%): il bersaglio valido di valore"
                  + " piu' alto (%.1f)",
              best.get().vittoria(),
              cfg.sogliaVittoria(),
              best.get().valore()));
      remaining.removeAll(group);
    }
    if (first) {
      ctx.add("bersagli", targets);
    }
    // 2. landings after the wait: the best exchange, exposure included
    final Set<Unit> forced = new LinkedHashSet<>();
    for (final Unit tr : remaining) {
      if (decisione(true, st.attese.get(f.chiave(tr)), round, cfg.roundDiAttesa())
          == Decisione.SBARCO_DOPO_ATTESA) {
        forced.add(tr);
      }
    }
    if (!forced.isEmpty()) {
      final Rischio risk = new Rischio(proData, calc, player, reach, remaining);
      while (!forced.isEmpty()) {
        final List<Stima> est = new ArrayList<>();
        for (final var e : ordinati(reach)) {
          final List<Unit> group = gruppo(e.getValue(), remaining);
          if (group.stream().anyMatch(forced::contains)) {
            est.add(stima(proData, calc, player, e.getKey(), group, risk, f));
          }
        }
        final Optional<Stima> best = dopoAttesa(est);
        if (best.isEmpty()) {
          break;
        }
        final Territory t = data.getMap().getTerritoryOrNull(best.get().bersaglio());
        final List<Unit> group = gruppo(reach.get(t), remaining);
        if (best.get().scambio() < 0
            && group.stream().filter(forced::contains).allMatch(f::allaBase)) {
          // the troops still at a base are not thrown away: released to Hard, the wait is over
          final List<Unit> released = group.stream().filter(f::allaBase).toList();
          final JsonObject rel = ctx.deepCopy();
          rel.addProperty("bersaglio", t.getName());
          rel.addProperty("scambio", AlleatiR5.round1(best.get().scambio()));
          rel.addProperty("trasporti", released.size());
          rel.addProperty("unita", carico(released, f).size());
          rel.addProperty("rilasciate_a_hard", true);
          IndicazioniP6.registra(
              data,
              player,
              TIPO,
              Decisione.SBARCO_DOPO_ATTESA.name(),
              "dopo un round di attesa alla base lo scambio migliore e' negativo: le truppe restano"
                  + " alla base e tornano a Hard",
              rel);
          released.forEach(u -> st.attese.remove(f.chiave(u)));
          remaining.removeAll(released);
          forced.removeAll(released);
          continue;
        }
        final JsonObject c2 = ctx.deepCopy();
        final JsonArray alt = new JsonArray();
        est.forEach(s -> alt.add(json(s)));
        c2.add("bersagli_dopo_attesa", alt);
        sbarca(
            data,
            player,
            del,
            st,
            Decisione.SBARCO_DOPO_ATTESA,
            best.get(),
            t,
            group,
            reach.get(t),
            f,
            c2,
            String.format(
                java.util.Locale.ROOT,
                "dopo un round di attesa: lo scambio migliore (%.1f = %.1f%% x %.1f + TUV %.1f -"
                    + " rischio trasporti %.1f)",
                best.get().scambio(),
                best.get().vittoria(),
                best.get().valore(),
                best.get().tuvSwing(),
                best.get().rischio()));
        remaining.removeAll(group);
        forced.removeAll(group);
      }
    }
    // 3. the waits and the approaches
    for (final Unit tr : loaded) {
      final String id = tr.getId().toString();
      if (!tr.getUnloaded().isEmpty() || !tr.isTransporting()) {
        st.attese.remove(id);
      }
    }
    final List<Unit> waiting = new ArrayList<>();
    final List<Unit> approaching = new ArrayList<>();
    int beyond = 0;
    for (final Unit tr : atBase.keySet()) {
      if (remaining.contains(tr) && tr.getUnloaded().isEmpty()) {
        st.attese.putIfAbsent(f.chiave(tr), round);
        waiting.add(tr);
        // the troops wait at the base: out of Hard's combat and non-combat move this turn
        for (final Unit u : atBase.get(tr).truppe()) {
          held.add(u);
          st.truppeBase.add(u.getId().toString());
        }
      }
    }
    for (final Unit tr : loaded) {
      if (!tr.isTransporting()) {
        continue;
      }
      final String id = tr.getId().toString();
      final Integer since = st.attese.get(id);
      if (since != null && round - since >= cfg.roundDiAttesa()) {
        beyond++;
      }
      if (remaining.contains(tr)) {
        st.attese.putIfAbsent(id, round);
        waiting.add(tr);
      } else {
        approaching.add(tr);
      }
      st.aBordo.add(id);
    }
    if (!waiting.isEmpty()) {
      final JsonObject row = ctx.deepCopy();
      row.addProperty("trasporti", waiting.size());
      row.addProperty("unita", carico(waiting, f).size());
      row.addProperty("alle_basi", waiting.stream().filter(f::allaBase).count());
      row.addProperty("oltre_un_round", beyond);
      row.addProperty(
          "perche",
          "nessun bersaglio valido raggiungibile vince con tutto il carico: si resta a bordo e ci si"
              + " raduna (al massimo "
              + cfg.roundDiAttesa()
              + " round)");
      IndicazioniP6.registra(
          data, player, TIPO, Decisione.ATTESA.name(), row.get("perche").getAsString(), row);
    }
    if (!approaching.isEmpty()) {
      final JsonObject row = ctx.deepCopy();
      row.addProperty("trasporti", approaching.size());
      row.addProperty("unita", carico(approaching, f).size());
      row.addProperty("oltre_un_round", waiting.isEmpty() ? beyond : 0);
      row.addProperty(
          "perche",
          "nessun bersaglio valido raggiungibile in questo turno: ci si avvicina a bordo");
      IndicazioniP6.registra(
          data, player, TIPO, Decisione.AVVICINAMENTO.name(), row.get("perche").getAsString(), row);
    }
    return held;
  }

  /** Lands {@code group} on {@code t}; registers the decision; the landing for the outcome. */
  private static void sbarca(
      final GameData data,
      final GamePlayer player,
      final IMoveDelegate del,
      final Stato st,
      final Decisione d,
      final Stima s,
      final Territory t,
      final List<Unit> group,
      final Map<Unit, Arrivo> arrivals,
      final Forza f,
      final JsonObject ctx,
      final String why) {
    int landed = 0;
    final List<String> ids = new ArrayList<>();
    final Map<String, String> zones = new LinkedHashMap<>();
    final List<Unit> landedUnits = new ArrayList<>();
    for (final Unit tr : group) {
      final Arrivo a = arrivals.get(tr);
      final List<Unit> cargo = new ArrayList<>(f.carico(tr));
      if (a == null || cargo.isEmpty()) {
        continue;
      }
      if (f.allaBase(tr)) {
        // the load at the base, in the same combat move
        final Territory from = a.rotta().getStart();
        final Map<Unit, Unit> map = new HashMap<>();
        cargo.forEach(u -> map.put(u, tr));
        final Optional<String> err =
            del.performMove(new MoveDescription(cargo, new Route(f.base(tr), from), map));
        if (err.isPresent()) {
          ProLogger.warn("[Pro+] P6 sbarco: carico alla base non riuscito " + err.get());
          continue;
        }
      }
      if (a.rotta().numberOfSteps() > 0) {
        final List<Unit> units = new ArrayList<>(List.of(tr));
        units.addAll(cargo);
        final Optional<String> err = del.performMove(new MoveDescription(units, a.rotta()));
        if (err.isPresent()) {
          ProLogger.warn("[Pro+] P6 sbarco: rotta non riuscita " + err.get());
          continue;
        }
      }
      final Optional<String> err =
          del.performMove(new MoveDescription(cargo, new Route(a.zona(), t)));
      if (err.isPresent()) {
        ProLogger.warn("[Pro+] P6 sbarco: scarico non riuscito " + err.get());
        continue;
      }
      landed += cargo.size();
      landedUnits.addAll(cargo);
      ids.add(tr.getId().toString());
      zones.put(tr.getId().toString(), a.zona().getName());
      st.attese.remove(f.chiave(tr));
    }
    final JsonObject row = ctx.deepCopy();
    row.addProperty("bersaglio", t.getName());
    row.addProperty("perche", why);
    row.addProperty("vittoria_pct", AlleatiR5.round1(s.vittoria()));
    row.addProperty("valore", AlleatiR5.round1(s.valore()));
    row.addProperty("tuv_swing", AlleatiR5.round1(s.tuvSwing()));
    row.addProperty("rischio_trasporti", AlleatiR5.round1(s.rischio()));
    row.addProperty("scambio", AlleatiR5.round1(s.scambio()));
    row.addProperty("trasporti", ids.size());
    row.addProperty("unita_previste", s.unita());
    row.addProperty("unita_sbarcate", landed);
    row.add("sbarcate", TrasportiProtetti.perTipo(landedUnits));
    final JsonObject z = new JsonObject();
    zones.values().forEach(n -> z.addProperty(n, z.has(n) ? z.get(n).getAsInt() + 1 : 1));
    row.add("zone_di_scarico", z);
    IndicazioniP6.registra(data, player, TIPO, d.name(), why, row);
    if (landed > 0) {
      final Sbarco sb =
          new Sbarco(d.name(), t.getName(), List.copyOf(ids), zones, landed, st.round, row);
      sb.sbarcate = landed;
      st.sbarchi.add(sb);
    }
  }

  // ======================================================================== non combat

  /**
   * The non-combat move before Hard's: the outcome of the round's landings (with the smallest
   * escort that is enough for their transports), the gathering of the force left on board with its
   * escort. Returns the units Hard must leave where they are this turn.
   */
  static Set<Unit> nonCombattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate del,
      final Stato st) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final IndicazioniP6.Config cfg = IndicazioniP6.Config.get();
    final Set<Unit> held = new LinkedHashSet<>();
    if (st.round != data.getSequence().getRound()) {
      return held;
    }
    final Map<String, Unit> byId = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getMatches(Matches.unitIsOwnedBy(player))) {
        byId.put(u.getId().toString(), u);
      }
    }
    // the troops waiting at a base stay there
    for (final String id : st.truppeBase) {
      final Unit u = byId.get(id);
      if (u != null) {
        held.add(u);
      }
    }
    // the outcomes and the escort of the landings' unload zones
    final Set<Territory> unloadZones = new LinkedHashSet<>();
    for (final Sbarco s : st.sbarchi) {
      s.zone.values().forEach(n -> unloadZones.add(data.getMap().getTerritoryOrNull(n)));
    }
    unloadZones.remove(null);
    // the force left on board
    final List<Unit> onBoard = new ArrayList<>();
    for (final String id : st.aBordo) {
      final Unit tr = byId.get(id);
      if (tr != null && tr.isTransporting() && tr.getUnloaded().isEmpty()) {
        onBoard.add(tr);
      }
    }
    final Map<Unit, Territory> where = posizioni(data, onBoard);
    final Optional<Raduno> gather = raduno(proData, player, onBoard, where, cfg);
    final Set<Territory> threatened = new LinkedHashSet<>(unloadZones);
    gather.ifPresent(r -> threatened.addAll(r.candidate()));
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    if (!threatened.isEmpty()) {
      tm.populateEnemyAttackOptionsOfEveryEnemy(threatened, threatened);
    }
    final Set<GamePlayer> enemies = nemici(data, player);
    for (final Sbarco s : st.sbarchi) {
      final Territory t = data.getMap().getTerritoryOrNull(s.bersaglio);
      final JsonObject row = new JsonObject();
      row.addProperty("decisione", s.decisione);
      row.addProperty("bersaglio", s.bersaglio);
      row.addProperty("unita_sbarcate", s.sbarcate);
      row.addProperty(
          "preso",
          t != null
              && !t.getOwner().isNull()
              && (t.isOwnedBy(player) || player.isAllied(t.getOwner())));
      row.addProperty("proprietario", t == null ? "?" : t.getOwner().getName());
      row.addProperty(
          "superstiti",
          t == null
              ? 0
              : t.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsLand())).size());
      final JsonArray zj = new JsonArray();
      final Map<String, List<Unit>> byZone = new TreeMap<>();
      for (final var e : s.zone.entrySet()) {
        final Unit tr = byId.get(e.getKey());
        if (tr != null) {
          byZone.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(tr);
        }
      }
      for (final var e : byZone.entrySet()) {
        final Territory z = data.getMap().getTerritoryOrNull(e.getKey());
        if (z == null) {
          continue;
        }
        final JsonObject o =
            scorta(proData, calc, tm, enemies, del, z, e.getValue(), held, false, cfg);
        zj.add(o);
      }
      row.add("zone", zj);
      IndicazioniP6.registra(
          data,
          player,
          TIPO,
          "ESITO_SBARCO",
          "esito dello sbarco dopo le battaglie; scorta minima ai trasporti nella zona di scarico",
          row);
    }
    // the gathering of the force on board
    if (gather.isPresent()) {
      final Raduno r = gather.get();
      final Territory z = scegliRaduno(proData, calc, tm, enemies, r, onBoard, held, cfg);
      final JsonObject row = new JsonObject();
      row.addProperty("zona", z.getName());
      row.addProperty("bersaglio_vicino", r.bersaglio().getOrDefault(z, "-"));
      int arrived = 0;
      int transit = 0;
      final List<Unit> there = new ArrayList<>();
      for (final Unit tr : onBoard) {
        final Territory from = where.get(tr);
        held.add(tr);
        held.addAll(tr.getTransporting());
        if (from == null) {
          continue;
        }
        final List<Unit> units = new ArrayList<>(List.of(tr));
        units.addAll(tr.getTransporting());
        final Optional<Route> route = rottaVerso(data, player, from, z, units);
        if (route.isEmpty() || route.get().numberOfSteps() == 0) {
          if (from.equals(z)) {
            arrived++;
            there.add(tr);
          } else {
            transit++;
          }
          continue;
        }
        if (del.performMove(new MoveDescription(units, route.get())).isPresent()) {
          transit++;
          continue;
        }
        if (route.get().getEnd().equals(z)) {
          arrived++;
          there.add(tr);
        } else {
          transit++;
        }
      }
      row.addProperty("trasporti_arrivati", arrived);
      row.addProperty("trasporti_in_viaggio", transit);
      row.addProperty("unita_a_bordo", carico(onBoard).size());
      if (!there.isEmpty()) {
        row.add("scorta", scorta(proData, calc, tm, enemies, del, z, there, held, true, cfg));
      }
      IndicazioniP6.registra(
          data,
          player,
          TIPO,
          "RADUNO",
          "la forza resta a bordo e si raduna in una zona da cui un bersaglio valido e'"
              + " raggiungibile il turno dopo",
          row);
    } else {
      for (final Unit tr : onBoard) {
        held.add(tr);
        held.addAll(tr.getTransporting());
      }
    }
    st.daSeguire.clear();
    st.daSeguire.addAll(st.sbarchi);
    return held;
  }

  /**
   * At the start of the turn (purchase): last round's landings, after the enemy's turn: transports
   * lost, territory still allied.
   */
  static void inizioTurno(final GameState data, final GamePlayer player, final Stato st) {
    final int round = data.getSequence().getRound();
    if (st.daSeguire.isEmpty() || st.daSeguire.get(0).round >= round) {
      return;
    }
    final Set<String> alive = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getMatches(Matches.unitIsOwnedBy(player)).forEach(u -> alive.add(u.getId().toString()));
    }
    for (final Sbarco s : st.daSeguire) {
      final Territory t = data.getMap().getTerritoryOrNull(s.bersaglio);
      final JsonObject row = new JsonObject();
      row.addProperty("round_sbarco", s.round);
      row.addProperty("decisione", s.decisione);
      row.addProperty("bersaglio", s.bersaglio);
      row.addProperty("unita_sbarcate", s.sbarcate);
      row.addProperty("trasporti", s.trasporti.size());
      row.addProperty(
          "trasporti_persi", s.trasporti.stream().filter(id -> !alive.contains(id)).count());
      row.addProperty(
          "ancora_alleato",
          t != null
              && !t.getOwner().isNull()
              && (t.isOwnedBy(player) || player.isAllied(t.getOwner())));
      IndicazioniP6.registra(
          data,
          player,
          TIPO,
          "SEGUITO_SBARCO",
          "un round dopo lo sbarco: trasporti persi nel turno nemico, territorio ancora alleato",
          row);
    }
    st.daSeguire.clear();
  }

  // ====================================================================== the estimate

  /** A transport's way to a target this turn: its unload zone and the route there. */
  record Arrivo(Territory zona, Route rotta) {}

  private static Stima stima(
      final ProData proData,
      final ProOddsCalculator calc,
      final GamePlayer player,
      final Territory t,
      final List<Unit> group,
      @Nullable final Rischio risk,
      final Forza f) {
    final List<Unit> cargo = carico(group, f);
    final List<Unit> defenders = t.getMatches(Matches.enemyUnit(player));
    double win;
    double swing;
    if (defenders.stream().noneMatch(Matches.unitIsNotInfrastructure())) {
      win = cargo.isEmpty() ? 0 : 100;
      swing = 0;
    } else {
      final ProBattleResult r =
          calc.calculateBattleResults(proData, t, cargo, defenders, List.of());
      win = r.getWinPercentage();
      swing = r.getTuvSwing();
    }
    final double risk0 = risk == null ? 0 : risk.di(t, group);
    final List<String> ids = group.stream().map(u -> u.getId().toString()).toList();
    return new Stima(t.getName(), ids, cargo.size(), win, valore(proData, player, t), swing, risk0);
  }

  /**
   * The expected loss of the transports exposed after landing on a target: in each unload zone, the
   * enemy's realistic attack against the transports there and every escort that can reach it.
   */
  private static final class Rischio {
    private final ProData proData;
    private final ProOddsCalculator calc;
    private final GamePlayer player;
    private final Map<Territory, Map<Unit, Arrivo>> reach;
    private final ProTerritoryManager tm;
    private final Set<GamePlayer> enemies;

    Rischio(
        final ProData proData,
        final ProOddsCalculator calc,
        final GamePlayer player,
        final Map<Territory, Map<Unit, Arrivo>> reach,
        final Set<Unit> remaining) {
      this.proData = proData;
      this.calc = calc;
      this.player = player;
      this.reach = reach;
      final Set<Territory> zones = new LinkedHashSet<>();
      for (final Map<Unit, Arrivo> m : reach.values()) {
        for (final var e : m.entrySet()) {
          if (remaining.contains(e.getKey())) {
            zones.add(e.getValue().zona());
          }
        }
      }
      this.tm = new ProTerritoryManager(calc, proData);
      tm.populateEnemyAttackOptionsOfEveryEnemy(zones, zones);
      this.enemies = nemici(proData.getData(), player);
    }

    double di(final Territory t, final List<Unit> group) {
      final Map<Territory, List<Unit>> byZone = new LinkedHashMap<>();
      for (final Unit tr : group) {
        final Arrivo a = reach.get(t).get(tr);
        if (a != null) {
          byZone.computeIfAbsent(a.zona(), k -> new ArrayList<>()).add(tr);
        }
      }
      double out = 0;
      for (final var e : byZone.entrySet()) {
        final Territory z = e.getKey();
        final List<Unit> att = ForzaSbarco.minaccia(tm, z, enemies);
        if (att.isEmpty()) {
          continue;
        }
        final List<Unit> def = new ArrayList<>(e.getValue());
        ScortaSbarco.candidati(proData.getData(), player, z, Set.of(), Set.of())
            .forEach(def::addAll);
        alleatiInZona(player, z).stream().filter(u -> !def.contains(u)).forEach(def::add);
        def.removeIf(
            u ->
                !Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)
                    && !ForzaSbarco.copre(u.getUnitAttachment()));
        final double w = ForzaSbarco.vittoria(proData, calc, z, att, def);
        out += w / 100.0 * tuv(proData, e.getValue());
      }
      return out;
    }
  }

  // ======================================================================== gathering

  /**
   * The gathering: the attack positions (sea zones without enemies from which an unload zone of a
   * valid target is reachable next turn) the force can reach, best first, with the nearest target.
   */
  record Raduno(List<Territory> candidate, Map<Territory, String> bersaglio) {}

  private static Optional<Raduno> raduno(
      final ProData proData,
      final GamePlayer player,
      final List<Unit> onBoard,
      final Map<Unit, Territory> where,
      final IndicazioniP6.Config cfg) {
    if (onBoard.isEmpty()) {
      return Optional.empty();
    }
    final GameData data = proData.getData();
    final Predicate<Territory> free = libera(player);
    final int move =
        onBoard.stream().mapToInt(u -> u.getUnitAttachment().getMovement(player)).min().orElse(0);
    // the attack positions and their best target (Hard's value)
    final Map<Territory, Double> value = new HashMap<>();
    final Map<Territory, String> target = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!bersaglioValido(player, t)) {
        continue;
      }
      final double v = valore(proData, player, t);
      final List<Territory> unload =
          data.getMap().getNeighbors(t, Matches.territoryIsWater()).stream().filter(free).toList();
      if (unload.isEmpty()) {
        continue;
      }
      for (final var e : distanze(data, unload, move, free).entrySet()) {
        if (value.getOrDefault(e.getKey(), -1.0) < v) {
          value.put(e.getKey(), v);
          target.put(e.getKey(), t.getName());
        }
      }
    }
    if (value.isEmpty()) {
      return Optional.empty();
    }
    // the force's distances
    final Map<Unit, Map<Territory, Integer>> dist = new HashMap<>();
    for (final Unit tr : onBoard) {
      final Territory from = where.get(tr);
      if (from != null) {
        dist.put(tr, distanze(data, List.of(from), cfg.raggioAvvicinamento(), free));
      }
    }
    final List<Territory> cands = new ArrayList<>(value.keySet());
    final Map<Territory, Integer> reachNow = new HashMap<>();
    final Map<Territory, Integer> sum = new HashMap<>();
    for (final Territory q : cands) {
      int n = 0;
      int s = 0;
      for (final var e : dist.entrySet()) {
        final Integer d = e.getValue().get(q);
        if (d == null) {
          s += 1000;
          continue;
        }
        s += d;
        if (d <= e.getKey().getMovementLeft().intValue()) {
          n++;
        }
      }
      reachNow.put(q, n);
      sum.put(q, s);
    }
    cands.removeIf(q -> sum.get(q) >= 1000 * Math.max(1, dist.size()));
    if (cands.isEmpty()) {
      return Optional.empty();
    }
    cands.sort(
        Comparator.comparingInt((Territory q) -> -reachNow.get(q))
            .thenComparingInt(sum::get)
            .thenComparingDouble(q -> -value.get(q))
            .thenComparing(Territory::getName));
    final List<Territory> top =
        List.copyOf(cands.subList(0, Math.min(cfg.zoneRaduno(), cands.size())));
    return Optional.of(new Raduno(top, target));
  }

  /** The first candidate the force holds in (threat with every escort at most the threshold). */
  private static Territory scegliRaduno(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritoryManager tm,
      final Set<GamePlayer> enemies,
      final Raduno r,
      final List<Unit> onBoard,
      final Set<Unit> held,
      final IndicazioniP6.Config cfg) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    Territory best = r.candidate().get(0);
    double bestWin = Double.MAX_VALUE;
    for (final Territory q : r.candidate()) {
      final List<Unit> att = ForzaSbarco.minaccia(tm, q, enemies);
      final List<Unit> def = new ArrayList<>(onBoard);
      final Set<Unit> busy = new HashSet<>(held);
      busy.addAll(onBoard);
      ScortaSbarco.candidati(data, player, q, busy, Set.of()).forEach(def::addAll);
      alleatiInZona(player, q).stream().filter(u -> !def.contains(u)).forEach(def::add);
      def.removeIf(
          u ->
              !Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)
                  && !ForzaSbarco.copre(u.getUnitAttachment()));
      final double w = ForzaSbarco.vittoria(proData, calc, q, att, def);
      if (w <= cfg.sogliaScorta()) {
        return q;
      }
      if (w < bestWin - 1e-9) {
        bestWin = w;
        best = q;
      }
    }
    return best;
  }

  /**
   * The escort of {@code transports} in {@code z}: the smallest that is enough among the own
   * warships that reach it this turn (not held yet); if none is enough: every one of them when
   * {@code loaded} (the troops are on board), none for empty transports (they are Hard's again).
   * Moves it, adds it to {@code held}; returns the row.
   */
  private static JsonObject scorta(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritoryManager tm,
      final Set<GamePlayer> enemies,
      final IMoveDelegate del,
      final Territory z,
      final List<Unit> transports,
      final Set<Unit> held,
      final boolean loaded,
      final IndicazioniP6.Config cfg) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final List<Unit> att = ForzaSbarco.minaccia(tm, z, enemies);
    final List<Unit> base = new ArrayList<>(transports);
    alleatiInZona(player, z).stream().filter(u -> !base.contains(u)).forEach(base::add);
    base.removeIf(
        u ->
            !Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)
                && !ForzaSbarco.copre(u.getUnitAttachment()));
    final Set<Unit> busy = new HashSet<>(held);
    busy.addAll(transports);
    final List<List<Unit>> cand = ScortaSbarco.candidati(data, player, z, busy, Set.of());
    final List<Double> costs = new ArrayList<>();
    cand.forEach(g -> costs.add((double) tuv(proData, g)));
    final ScortaSbarco.Scelta s =
        ScortaSbarco.scortaMinima(
            costs,
            idx -> {
              final List<Unit> def = new ArrayList<>(base);
              idx.forEach(i -> def.addAll(cand.get(i)));
              return ForzaSbarco.vittoria(proData, calc, z, att, def);
            },
            cfg.sogliaScorta());
    final List<Unit> chosen = new ArrayList<>();
    if (s.basta()) {
      s.scelti().forEach(i -> chosen.addAll(cand.get(i)));
    } else if (loaded) {
      cand.forEach(chosen::addAll);
    }
    final List<Unit> moved = muovi(data, player, del, chosen, z);
    held.addAll(moved);
    final JsonObject o = new JsonObject();
    o.addProperty("zona", z.getName());
    o.addProperty("trasporti", transports.size());
    o.add("minaccia", TrasportiProtetti.perTipo(att));
    o.addProperty("minaccia_tuv", tuv(proData, att));
    o.addProperty("vittoria_nemica_prima", AlleatiR5.round1(s.prima()));
    o.addProperty("vittoria_nemica_con_tutta_la_scorta", AlleatiR5.round1(s.conTutti()));
    o.addProperty("scorta_basta", s.basta());
    o.add("scorta", TrasportiProtetti.perTipo(moved));
    o.addProperty(
        "vittoria_nemica_dopo",
        AlleatiR5.round1(s.basta() ? s.dopo() : loaded ? s.conTutti() : s.prima()));
    return o;
  }

  /** Moves {@code units} (ships first, then the planes) into {@code z}; returns those there. */
  private static List<Unit> muovi(
      final GameData data,
      final GamePlayer player,
      final IMoveDelegate del,
      final List<Unit> units,
      final Territory z) {
    final List<Unit> arrived = new ArrayList<>();
    final Map<Territory, List<Unit>> byFrom = new LinkedHashMap<>();
    final Map<Unit, Territory> at = posizioni(data, units);
    units.forEach(u -> byFrom.computeIfAbsent(at.get(u), k -> new ArrayList<>()).add(u));
    for (final var g : byFrom.entrySet()) {
      final Territory from = g.getKey();
      if (from == null) {
        continue;
      }
      if (from.equals(z)) {
        arrived.addAll(g.getValue());
        continue;
      }
      final Optional<Route> r =
          data.getMap()
              .getRoute(from, z, ProMatches.territoryCanMoveSeaUnitsThrough(player, false));
      if (r.isEmpty()) {
        continue;
      }
      final List<Unit> ships = g.getValue().stream().filter(Matches.unitIsSea()).toList();
      final List<Unit> planes = g.getValue().stream().filter(Matches.unitIsAir()).toList();
      if (!ships.isEmpty() && del.performMove(new MoveDescription(ships, r.get())).isEmpty()) {
        arrived.addAll(ships);
      }
      for (final Unit pl : planes) {
        if (del.performMove(new MoveDescription(List.of(pl), r.get())).isEmpty()) {
          arrived.add(pl);
        }
      }
    }
    return arrived;
  }

  // ========================================================================== helpers

  /** The nation's transports with only its own land units on board, in sea zones. */
  static List<Unit> caricati(final GameState data, final GamePlayer player) {
    final List<Unit> out = new ArrayList<>();
    for (final Unit tr : ForzaSbarco.trasporti(data, player)) {
      final List<Unit> cargo = tr.getTransporting();
      if (!cargo.isEmpty() && cargo.stream().allMatch(u -> u.isOwnedBy(player))) {
        out.add(tr);
      }
    }
    return out;
  }

  /** The cargo of {@code transports}. */
  static List<Unit> carico(final Collection<Unit> transports) {
    final List<Unit> out = new ArrayList<>();
    transports.forEach(t -> out.addAll(t.getTransporting()));
    return out;
  }

  static List<Unit> carico(final Collection<Unit> transports, final Forza f) {
    final List<Unit> out = new ArrayList<>();
    transports.forEach(t -> out.addAll(f.carico(t)));
    return out;
  }

  /** The planned load of an empty transport at a base. */
  record Carico(Territory base, List<Unit> truppe) {}

  /**
   * The force of the turn: loaded transports (real cargo) and transports at the bases (planned).
   */
  static final class Forza {
    private final Map<Unit, Carico> allaBase;

    Forza(final Map<Unit, Carico> allaBase) {
      this.allaBase = allaBase;
    }

    boolean allaBase(final Unit tr) {
      return allaBase.containsKey(tr);
    }

    Territory base(final Unit tr) {
      return allaBase.get(tr).base();
    }

    List<Unit> carico(final Unit tr) {
      final Carico c = allaBase.get(tr);
      return c == null ? tr.getTransporting() : c.truppe();
    }

    /** The key of the wait: the transport, or the base for a force still at a base. */
    String chiave(final Unit tr) {
      final Carico c = allaBase.get(tr);
      return c == null ? tr.getId().toString() : "base:" + c.base().getName();
    }
  }

  /**
   * The force at the bases (extension agreed with Matteo, step P6): own troops in a friendly base
   * (own or allied land, not an own capital, with no land neighbor held by an enemy at war: they
   * can act only by sea) and the own empty transports not moved yet in a sea zone next to it
   * without enemies; each transport filled with the base's troops (biggest transport cost first,
   * then the strongest attack). A transport serves one base.
   */
  static Map<Unit, Carico> allaBase(final GameState data, final GamePlayer player) {
    final Map<Unit, Carico> out = new LinkedHashMap<>();
    final Set<Territory> capitals =
        new HashSet<>(TerritoryAttachment.getAllCapitals(player, data.getMap()));
    final Predicate<Territory> free = libera(player);
    for (final Territory b :
        data.getMap().getTerritories().stream()
            .filter(t -> !t.isWater())
            .sorted(Comparator.comparing(Territory::getName))
            .toList()) {
      if (b.getOwner().isNull()
          || !(b.isOwnedBy(player) || player.isAllied(b.getOwner()))
          || capitals.contains(b)
          || data.getMap().getNeighbors(b, Matches.territoryIsLand()).stream()
              .anyMatch(n -> bersaglioValido(player, n))) {
        continue;
      }
      final List<Unit> troops =
          new ArrayList<>(
              b
                  .getMatches(
                      Matches.unitIsOwnedBy(player)
                          .and(Matches.unitIsLand())
                          .and(Matches.unitIsNotInfrastructure())
                          .and(Matches.unitIsAaForAnything().negate())
                          .and(u -> u.getUnitAttachment().getTransportCost() > 0)
                          .and(u -> u.getMovementLeft().signum() > 0))
                  .stream()
                  .sorted(
                      Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
                          .thenComparingInt(u -> -u.getUnitAttachment().getAttack(player))
                          .thenComparing(u -> u.getId().toString()))
                  .toList());
      if (troops.isEmpty()) {
        continue;
      }
      for (final Territory z :
          data.getMap().getNeighbors(b, Matches.territoryIsWater()).stream()
              .filter(free)
              .sorted(Comparator.comparing(Territory::getName))
              .toList()) {
        for (final Unit tr :
            z
                .getMatches(
                    Matches.unitIsOwnedBy(player)
                        .and(Matches.unitIsSeaTransportButNotCombatSeaTransport()))
                .stream()
                .sorted(Comparator.comparing(u -> u.getId().toString()))
                .toList()) {
          if (troops.isEmpty()
              || out.containsKey(tr)
              || tr.isTransporting()
              || !tr.getUnloaded().isEmpty()
              || tr.getAlreadyMoved().signum() != 0) {
            continue;
          }
          int room = tr.getUnitAttachment().getTransportCapacity();
          final List<Unit> picked = new ArrayList<>();
          for (final Unit u : new ArrayList<>(troops)) {
            final int c = u.getUnitAttachment().getTransportCost();
            if (c <= room) {
              picked.add(u);
              troops.remove(u);
              room -= c;
            }
          }
          if (!picked.isEmpty()) {
            out.put(tr, new Carico(b, List.copyOf(picked)));
          }
        }
      }
    }
    return out;
  }

  private static Map<Unit, Territory> posizioni(final GameState data, final Collection<Unit> us) {
    final Set<Unit> wanted = new HashSet<>(us);
    final Map<Unit, Territory> out = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        if (wanted.contains(u)) {
          out.put(u, t);
        }
      }
    }
    return out;
  }

  /** Sea zones a transport can cross or end in: no enemy unit, passable. */
  static Predicate<Territory> libera(final GamePlayer player) {
    return x ->
        x.isWater()
            && ProMatches.territoryCanMoveSeaUnitsThrough(player, false).test(x)
            && !x.anyUnitsMatch(Matches.enemyUnit(player));
  }

  /** Sea zones within {@code max} moves of {@code starts} through {@code free} zones. */
  static Map<Territory, Integer> distanze(
      final GameState data,
      final Collection<Territory> starts,
      final int max,
      final Predicate<Territory> free) {
    final Map<Territory, Integer> out = new LinkedHashMap<>();
    final Deque<Territory> queue = new ArrayDeque<>();
    for (final Territory s : starts) {
      if (out.putIfAbsent(s, 0) == null) {
        queue.add(s);
      }
    }
    while (!queue.isEmpty()) {
      final Territory x = queue.poll();
      final int d = out.get(x);
      if (d >= max) {
        continue;
      }
      for (final Territory n : data.getMap().getNeighbors(x, Matches.territoryIsWater())) {
        if (!out.containsKey(n) && free.test(n)) {
          out.put(n, d + 1);
          queue.add(n);
        }
      }
    }
    return out;
  }

  /**
   * For each valid target, the loaded transports that reach one of its unload zones this turn (a
   * sea zone next to it without enemies, through zones without enemies, canals checked).
   */
  private static Map<Territory, Map<Unit, Arrivo>> raggiunge(
      final GameData data,
      final GamePlayer player,
      final List<Unit> loaded,
      final Map<Unit, Territory> where,
      final Forza f) {
    final Predicate<Territory> free = libera(player);
    final Map<Territory, Map<Unit, Arrivo>> out = new LinkedHashMap<>();
    for (final Unit tr : loaded) {
      final Territory from = where.get(tr);
      if (from == null) {
        continue;
      }
      final int mv = tr.getMovementLeft().intValue();
      final List<Unit> units = new ArrayList<>(List.of(tr));
      units.addAll(f.carico(tr));
      final Map<Territory, Integer> d = distanze(data, List.of(from), mv, free);
      final Map<Territory, Arrivo> best = new HashMap<>();
      for (final var e : d.entrySet()) {
        final Territory u = e.getKey();
        if (!free.test(u)) {
          continue;
        }
        final List<Territory> targets =
            data.getMap().getNeighbors(u, Matches.territoryIsLand()).stream()
                .filter(t -> bersaglioValido(player, t))
                .toList();
        if (targets.isEmpty()) {
          continue;
        }
        final Route route;
        if (u.equals(from)) {
          route = new Route(from);
        } else {
          final Optional<Route> r = data.getMap().getRouteForUnits(from, u, free, units, player);
          if (r.isEmpty() || r.get().numberOfSteps() > mv) {
            continue;
          }
          route = r.get();
        }
        for (final Territory t : targets) {
          final Arrivo a = best.get(t);
          if (a == null || route.numberOfSteps() < a.rotta().numberOfSteps()) {
            best.put(t, new Arrivo(u, route));
          }
        }
      }
      best.forEach((t, a) -> out.computeIfAbsent(t, k -> new LinkedHashMap<>()).put(tr, a));
    }
    return out;
  }

  /** The route from {@code from} toward {@code z}: all of it if in reach, else as far as it can. */
  private static Optional<Route> rottaVerso(
      final GameData data,
      final GamePlayer player,
      final Territory from,
      final Territory z,
      final List<Unit> units) {
    if (from.equals(z)) {
      return Optional.of(new Route(from));
    }
    final Predicate<Territory> free = libera(player);
    final Optional<Route> r = data.getMap().getRouteForUnits(from, z, free, units, player);
    if (r.isEmpty()) {
      return Optional.empty();
    }
    final int mv = units.get(0).getMovementLeft().intValue();
    if (r.get().numberOfSteps() <= mv) {
      return r;
    }
    if (mv <= 0) {
      return Optional.empty();
    }
    final List<Territory> steps = r.get().getSteps();
    return Optional.of(new Route(from, steps.subList(0, mv)));
  }

  private static List<Map.Entry<Territory, Map<Unit, Arrivo>>> ordinati(
      final Map<Territory, Map<Unit, Arrivo>> reach) {
    return reach.entrySet().stream()
        .sorted(Comparator.comparing(e -> e.getKey().getName()))
        .toList();
  }

  private static List<Unit> gruppo(final Map<Unit, Arrivo> m, final Set<Unit> remaining) {
    return m.keySet().stream()
        .filter(remaining::contains)
        .sorted(Comparator.comparing(u -> u.getId().toString()))
        .toList();
  }

  private static Set<GamePlayer> nemici(final GameState data, final GamePlayer player) {
    final Set<GamePlayer> out = new LinkedHashSet<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (!p.equals(player) && player.isAtWar(p)) {
        out.add(p);
      }
    }
    return out;
  }

  private static List<Unit> alleatiInZona(final GamePlayer player, final Territory z) {
    final List<Unit> out = new ArrayList<>();
    for (final Unit u : z.getUnits()) {
      if (!u.isOwnedBy(player)
          && player.isAllied(u.getOwner())
          && (Matches.unitIsSea().test(u) || Matches.unitIsAir().test(u))) {
        out.add(u);
      }
    }
    return out;
  }

  private static int tuv(final ProData proData, final Collection<Unit> units) {
    int s = 0;
    for (final Unit u : units) {
      s += proData.getUnitValueMap().getInt(u.getType());
    }
    return s;
  }

  private static JsonObject json(final Stima s) {
    final JsonObject o = new JsonObject();
    o.addProperty("territorio", s.bersaglio());
    o.addProperty("valore", AlleatiR5.round1(s.valore()));
    o.addProperty("vittoria_pct", AlleatiR5.round1(s.vittoria()));
    o.addProperty("unita", s.unita());
    o.addProperty("trasporti", s.trasporti().size());
    o.addProperty("tuv_swing", AlleatiR5.round1(s.tuvSwing()));
    o.addProperty("rischio_trasporti", AlleatiR5.round1(s.rischio()));
    o.addProperty("scambio", AlleatiR5.round1(s.scambio()));
    return o;
  }
}
