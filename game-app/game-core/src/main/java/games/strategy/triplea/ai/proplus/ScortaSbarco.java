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
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProOtherMoveOptions;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Step P5, B ({@code norvegia_scorta}): for the targets weighted by {@code norvegia_presto} (Hard
 * of the nation of the data, its rounds), a landing Hard would drop because its transports are
 * exposed is escorted instead: the nation's warships (with their planes on carriers) that reach the
 * unload zone this turn and are not in Hard's combat move. If with them the transports hold the
 * realistic attack of the enemy (Hard's own maximum enemy attack on the zone, win at most {@code
 * soglia_trasporti}), the smallest escort that is enough is chosen, the landing is made and the
 * escort moves to the zone in the non-combat move (before Hard's, held out of it for that turn). If
 * not even every escort is enough, the landing stays dropped (the transports are protected, not
 * sacrificed) and the register tells how much escort was missing. The defense of the capital is
 * Hard's, unchanged.
 */
public final class ScortaSbarco {
  private ScortaSbarco() {}

  static final String TIPO = "norvegia_scorta";
  static final String KEPT = "p5_scorta";

  /** Step P6 (B, {@code norvegia_esposta}): the register type and the answer that keeps. */
  static final String TIPO_ESPOSTA = "norvegia_esposta";

  static final String KEPT_ESPOSTA = "p6_esposta";

  // ======================================================================== activation

  /** The switch is on (with {@code norvegia_presto}) and {@code target} is weighted this round. */
  static boolean attivo(final ProData proData, final Territory target) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    return player != null
        && data != null
        && IndicazioniP4.attivo(data, player, StrategaInterruttori.NORVEGIA_SCORTA)
        && IndicazioniP4.pesoBersaglio(proData, target) > 0;
  }

  static boolean attivo(final GameState data, final GamePlayer player) {
    return IndicazioniP4.attivo(data, player, StrategaInterruttori.NORVEGIA_SCORTA)
            && IndicazioniP4.attivo(data, player, StrategaInterruttori.NORVEGIA_PRESTO)
        || espostaAttiva(data, player);
  }

  /** Step P6 (B): the switch {@code norvegia_esposta} is on (with {@code norvegia_presto}). */
  static boolean espostaAttiva(final GameState data, final GamePlayer player) {
    return IndicazioniP6.attivo(data, player, StrategaInterruttori.NORVEGIA_ESPOSTA);
  }

  /** Step P6 (B): {@code target} has B's attack weight this round (the exposed rule applies). */
  static boolean esposta(final ProData proData, final Territory target) {
    return proData.getPlayer() != null
        && proData.getData() != null
        && IndicazioniP6.pesoAttacco(proData, target) > 0;
  }

  // ======================================================================== pure rule

  /** The escort chosen for one zone (indexes of the candidates) or why there is none. */
  record Scelta(List<Integer> scelti, double prima, double dopo, double conTutti, boolean basta) {}

  /**
   * The smallest escort that is enough (pure rule): none if the zone already holds ({@code win} of
   * no candidate at most {@code soglia}); nothing if not even every candidate is enough; else one
   * candidate at a time, the one that lowers the enemy's win most per cost (ties: the first), then
   * every chosen candidate that is not needed is left out again (last chosen first).
   */
  static Scelta scortaMinima(
      final List<Double> costs, final Function<List<Integer>, Double> win, final double soglia) {
    final double before = win.apply(List.of());
    if (before <= soglia) {
      return new Scelta(List.of(), before, before, before, true);
    }
    final List<Integer> all = new ArrayList<>();
    for (int i = 0; i < costs.size(); i++) {
      all.add(i);
    }
    final double withAll = all.isEmpty() ? before : win.apply(all);
    if (withAll > soglia) {
      return new Scelta(List.of(), before, before, withAll, false);
    }
    final List<Integer> chosen = new ArrayList<>();
    double now = before;
    while (now > soglia) {
      int best = -1;
      double bestScore = Double.NEGATIVE_INFINITY;
      double bestWin = now;
      for (final int i : all) {
        if (chosen.contains(i)) {
          continue;
        }
        final List<Integer> f = new ArrayList<>(chosen);
        f.add(i);
        final double w = win.apply(f);
        final double score = (now - w) / Math.max(1e-9, costs.get(i));
        if (score > bestScore + 1e-12) {
          bestScore = score;
          best = i;
          bestWin = w;
        }
      }
      if (best < 0) {
        break;
      }
      chosen.add(best);
      now = bestWin;
      if (chosen.size() == all.size()) {
        now = withAll;
        break;
      }
    }
    if (now > soglia) {
      // one at a time did not get there (a combined effect): every candidate, then pruned
      chosen.clear();
      chosen.addAll(all);
      now = withAll;
    }
    for (int k = chosen.size() - 1; k >= 0; k--) {
      final List<Integer> f = new ArrayList<>(chosen);
      f.remove(k);
      final double w = win.apply(f);
      if (w <= soglia) {
        chosen.remove(k);
        now = w;
      }
    }
    return new Scelta(List.copyOf(chosen), before, now, withAll, true);
  }

  // ===================================================================== turn's state

  /** What the combat move gave the tuning for the landing being checked. */
  record Contesto(
      String bersaglio,
      ProOddsCalculator calc,
      ProTerritory landing,
      Map<Territory, List<Unit>> zone,
      ProOtherMoveOptions nemico,
      Set<Unit> muovono) {}

  /** The escort of one landing: zone → escort unit ids, the landing's transports, the row. */
  record Piano(
      String bersaglio,
      Map<String, List<String>> scorta,
      Set<String> trasporti,
      boolean eseguito,
      JsonObject riga,
      boolean esposta) {

    Piano(
        final String bersaglio,
        final Map<String, List<String>> scorta,
        final Set<String> trasporti,
        final boolean eseguito,
        final JsonObject riga) {
      this(bersaglio, scorta, trasporti, eseguito, riga, false);
    }
  }

  /** The state of one AI's turn (one AI = one nation). */
  public static final class Stato {
    int round = -1;
    @Nullable Contesto ultimo;

    /** Target → the last decision of the round (the plan may be made on a copy, then again). */
    final Map<String, Piano> piani = new TreeMap<>();

    /** The escorts moved last turn (ids) and the round, for the losses. */
    final Set<String> scorteMosse = new LinkedHashSet<>();

    int roundScorte = -1;
    @Nullable String bersaglioScorte;

    /** Step P6 (B): the transports of last turn's exposed landing (ids) and the row's mode. */
    final Set<String> trasportiEsposti = new LinkedHashSet<>();

    boolean scorteEsposte;

    void inizio(final int r) {
      if (r != round) {
        round = r;
        ultimo = null;
        piani.clear();
      }
    }
  }

  /** Hard's combat move is checking the transports of {@code landing} (kept for the decision). */
  static void contesto(
      final ProData proData,
      final Stato st,
      final ProOddsCalculator calc,
      final ProTerritory landing,
      final Map<Territory, List<Unit>> zones,
      final ProOtherMoveOptions enemy,
      final Collection<Unit> moving) {
    if (!attivo(proData, landing.getTerritory()) && !esposta(proData, landing.getTerritory())) {
      return;
    }
    st.inizio(proData.getData().getSequence().getRound());
    st.ultimo =
        new Contesto(
            landing.getTerritory().getName(),
            calc,
            landing,
            Map.copyOf(zones),
            enemy,
            Set.copyOf(moving));
  }

  // ===================================================================== the decision

  /**
   * Hard drops the landing on {@code target} because its transports are exposed: the escort that is
   * enough, if any (the landing is then kept: {@value #KEPT}); empty = dropped as Hard does.
   */
  static Optional<String> valuta(
      final ProData proData,
      final Stato st,
      final Territory target,
      final double winPercentage,
      final double enemyTuvSwing,
      final double attackValue) {
    // step P6 (B): on a weighted target the exposed transports do not drop the landing
    if (esposta(proData, target)
        && st.ultimo != null
        && st.ultimo.bersaglio().equals(target.getName())) {
      try {
        return Optional.of(
            decidiEsposta(proData, st, st.ultimo, winPercentage, enemyTuvSwing, attackValue));
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] P6 esposta: " + e);
        return Optional.of(KEPT_ESPOSTA);
      }
    }
    if (!attivo(proData, target)
        || st.ultimo == null
        || !st.ultimo.bersaglio().equals(target.getName())) {
      return Optional.empty();
    }
    try {
      return decidi(proData, st, st.ultimo, winPercentage, enemyTuvSwing, attackValue);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P5 scorta: " + e);
      return Optional.empty();
    }
  }

  private static Optional<String> decidi(
      final ProData proData,
      final Stato st,
      final Contesto c,
      final double winPercentage,
      final double enemyTuvSwing,
      final double attackValue) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final IndicazioniP5.Config cfg = IndicazioniP5.Config.get();
    final Set<Unit> taken = new HashSet<>();
    final Map<String, List<String>> escortIds = new LinkedHashMap<>();
    final JsonArray zonesJson = new JsonArray();
    boolean ok = true;
    final Set<String> transports = new LinkedHashSet<>();
    for (final var e :
        c.zone().entrySet().stream()
            .sorted(Comparator.comparing(x -> x.getKey().getName()))
            .toList()) {
      final Territory z = e.getKey();
      e.getValue().stream()
          .filter(Matches.unitIsSeaTransport())
          .forEach(u -> transports.add(u.getId().toString()));
      final ProTerritory max = c.nemico().getMax(z);
      final List<Unit> att = max == null ? List.of() : new ArrayList<>(max.getMaxUnits());
      final List<Unit> base = base(data, player, z, e.getValue(), c.muovono());
      final List<List<Unit>> cand = candidati(data, player, z, c.muovono(), taken);
      final List<Double> costs = new ArrayList<>();
      cand.forEach(g -> costs.add((double) tuv(proData, g)));
      final Scelta s =
          scortaMinima(
              costs,
              idx -> {
                final List<Unit> def = new ArrayList<>(base);
                idx.forEach(i -> def.addAll(cand.get(i)));
                return vittoria(proData, c.calc(), z, att, def);
              },
              cfg.sogliaTrasporti());
      final JsonObject zj = new JsonObject();
      zj.addProperty("zona", z.getName());
      zj.add("minaccia", TrasportiProtetti.perTipo(att));
      zj.addProperty("minaccia_tuv", tuv(proData, att));
      zj.add("difensori_senza_scorta", TrasportiProtetti.perTipo(base));
      final List<Unit> allCand = new ArrayList<>();
      cand.forEach(allCand::addAll);
      zj.add("scorta_disponibile", TrasportiProtetti.perTipo(allCand));
      zj.addProperty("vittoria_nemica_prima", AlleatiR5.round1(s.prima()));
      zj.addProperty("vittoria_nemica_con_tutta_la_scorta", AlleatiR5.round1(s.conTutti()));
      if (s.basta()) {
        final List<Unit> chosen = new ArrayList<>();
        s.scelti().forEach(i -> chosen.addAll(cand.get(i)));
        taken.addAll(chosen);
        zj.add("scorta", TrasportiProtetti.perTipo(chosen));
        zj.addProperty("vittoria_nemica_dopo", AlleatiR5.round1(s.dopo()));
        escortIds.put(z.getName(), chosen.stream().map(u -> u.getId().toString()).toList());
      } else {
        ok = false;
        zj.add("mancante", mancante(proData, c.calc(), z, att, base, allCand, cfg));
      }
      zonesJson.add(zj);
    }
    final JsonObject row = new JsonObject();
    row.addProperty("bersaglio", c.bersaglio());
    row.addProperty("vittoria_sbarco_pct", AlleatiR5.round1(winPercentage));
    row.addProperty("scambio_nemico_trasporti", AlleatiR5.round1(enemyTuvSwing));
    row.addProperty("valore_attacco", AlleatiR5.round1(attackValue));
    row.addProperty("soglia", cfg.sogliaTrasporti());
    row.add("zone", zonesJson);
    row.addProperty("eseguito", ok);
    st.piani.put(c.bersaglio(), new Piano(c.bersaglio(), escortIds, transports, ok, row));
    return ok ? Optional.of(KEPT) : Optional.empty();
  }

  /**
   * Step P6 (B): the landing is kept whatever the exposure; the escort is the smallest that is
   * enough among the warships that arrive from outside the capital's sea zones (the home waters are
   * not emptied), none if not even every one of them is enough (the transports are risked, not the
   * fleet).
   */
  static String decidiEsposta(
      final ProData proData,
      final Stato st,
      final Contesto c,
      final double winPercentage,
      final double enemyTuvSwing,
      final double attackValue) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final double soglia = IndicazioniP6.Config.get().sogliaScortaEsposta();
    final Set<Territory> home = zoneCapitale(data, player);
    final Set<Unit> taken = new HashSet<>();
    final Map<String, List<String>> escortIds = new LinkedHashMap<>();
    final JsonArray zonesJson = new JsonArray();
    final Set<String> transports = new LinkedHashSet<>();
    for (final var e :
        c.zone().entrySet().stream()
            .sorted(Comparator.comparing(x -> x.getKey().getName()))
            .toList()) {
      final Territory z = e.getKey();
      e.getValue().stream()
          .filter(Matches.unitIsSeaTransport())
          .forEach(u -> transports.add(u.getId().toString()));
      final ProTerritory max = c.nemico().getMax(z);
      final List<Unit> att = max == null ? List.of() : new ArrayList<>(max.getMaxUnits());
      final List<Unit> base = base(data, player, z, e.getValue(), c.muovono());
      final List<List<Unit>> cand = candidatiFuoriCasa(data, player, z, c.muovono(), taken, home);
      final List<Double> costs = new ArrayList<>();
      cand.forEach(g -> costs.add((double) tuv(proData, g)));
      final Scelta s =
          scortaMinima(
              costs,
              idx -> {
                final List<Unit> def = new ArrayList<>(base);
                idx.forEach(i -> def.addAll(cand.get(i)));
                return vittoria(proData, c.calc(), z, att, def);
              },
              soglia);
      final JsonObject zj = new JsonObject();
      zj.addProperty("zona", z.getName());
      zj.add("minaccia", TrasportiProtetti.perTipo(att));
      zj.addProperty("minaccia_tuv", tuv(proData, att));
      zj.add("difensori_senza_scorta", TrasportiProtetti.perTipo(base));
      final List<Unit> allCand = new ArrayList<>();
      cand.forEach(allCand::addAll);
      zj.add("scorta_disponibile", TrasportiProtetti.perTipo(allCand));
      zj.addProperty("vittoria_nemica_prima", AlleatiR5.round1(s.prima()));
      zj.addProperty("vittoria_nemica_con_tutta_la_scorta", AlleatiR5.round1(s.conTutti()));
      final List<Unit> chosen = new ArrayList<>();
      if (s.basta()) {
        s.scelti().forEach(i -> chosen.addAll(cand.get(i)));
        taken.addAll(chosen);
        escortIds.put(z.getName(), chosen.stream().map(u -> u.getId().toString()).toList());
      }
      zj.add("scorta", TrasportiProtetti.perTipo(chosen));
      zj.addProperty("scorta_basta", s.basta());
      zj.addProperty("vittoria_nemica_dopo", AlleatiR5.round1(s.basta() ? s.dopo() : s.prima()));
      zonesJson.add(zj);
    }
    final JsonObject row = new JsonObject();
    row.addProperty("bersaglio", c.bersaglio());
    row.addProperty("vittoria_sbarco_pct", AlleatiR5.round1(winPercentage));
    row.addProperty("scambio_nemico_trasporti", AlleatiR5.round1(enemyTuvSwing));
    row.addProperty("valore_attacco", AlleatiR5.round1(attackValue));
    row.addProperty("soglia_scorta", soglia);
    row.add("zone", zonesJson);
    row.addProperty("eseguito", true);
    st.piani.put(c.bersaglio(), new Piano(c.bersaglio(), escortIds, transports, true, row, true));
    return KEPT_ESPOSTA;
  }

  /** Step P6 (B): the sea zones next to the player's capitals (the home waters). */
  static Set<Territory> zoneCapitale(final GameState data, final GamePlayer player) {
    final Set<Territory> out = new HashSet<>();
    for (final Territory cap :
        games.strategy.triplea.attachments.TerritoryAttachment.getAllCapitals(
            player, data.getMap())) {
      out.addAll(data.getMap().getNeighbors(cap, Matches.territoryIsWater()));
    }
    return out;
  }

  /** Step P6 (B): {@link #candidati} without the ships that are in {@code home}. */
  static List<List<Unit>> candidatiFuoriCasa(
      final GameState data,
      final GamePlayer player,
      final Territory z,
      final Set<Unit> moving,
      final Set<Unit> taken,
      final Set<Territory> home) {
    final Set<Unit> atHome = new HashSet<>(taken);
    for (final Territory h : home) {
      atHome.addAll(h.getMatches(Matches.unitIsOwnedBy(player)));
    }
    return candidati(data, player, z, moving, atHome);
  }

  /**
   * The defenders of {@code z} without escort: allied sea and air there not moving, the landing.
   */
  private static List<Unit> base(
      final GameState data,
      final GamePlayer player,
      final Territory z,
      final Collection<Unit> landing,
      final Set<Unit> moving) {
    final List<Unit> out = new ArrayList<>();
    for (final Unit u : z.getUnits()) {
      if (Matches.isUnitAllied(player).test(u)
          && (Matches.unitIsSea().test(u) || Matches.unitIsAir().test(u))
          && !u.isOwnedBy(player)
          && ForzaSbarco.copre(u.getUnitAttachment())) {
        out.add(u);
      }
    }
    landing.stream().filter(u -> !out.contains(u)).forEach(out::add);
    return out;
  }

  /**
   * The escort candidates for {@code z}: groups of the nation's warships not moving in the combat
   * move that reach {@code z} by sea this turn (a carrier with the nation's planes on it in its
   * zone), the own ships already in {@code z} included.
   */
  static List<List<Unit>> candidati(
      final GameState data,
      final GamePlayer player,
      final Territory z,
      final Set<Unit> moving,
      final Set<Unit> taken) {
    final List<List<Unit>> out = new ArrayList<>();
    for (final Territory from : data.getMap().getTerritories()) {
      if (!from.isWater()) {
        continue;
      }
      final List<Unit> ships =
          from
              .getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsSea())
                      .and(Matches.unitIsSeaTransportButNotCombatSeaTransport().negate()))
              .stream()
              .filter(u -> !moving.contains(u) && !taken.contains(u))
              .filter(u -> ForzaSbarco.copre(u.getUnitAttachment()))
              .sorted(Comparator.comparing(u -> u.getId().toString()))
              .toList();
      if (ships.isEmpty()) {
        continue;
      }
      final int steps = passi(data, player, from, z);
      if (steps < 0) {
        continue;
      }
      final List<Unit> planes =
          new ArrayList<>(
              from.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsAir())).stream()
                  .filter(u -> !moving.contains(u) && !taken.contains(u))
                  .filter(u -> u.getMovementLeft().intValue() >= steps)
                  .sorted(Comparator.comparing(u -> u.getId().toString()))
                  .toList());
      for (final Unit s : ships) {
        if (s.getMovementLeft().intValue() < steps) {
          continue;
        }
        final List<Unit> group = new ArrayList<>(List.of(s));
        if (Matches.unitIsCarrier().test(s)) {
          int room = s.getUnitAttachment().getCarrierCapacity();
          for (final java.util.Iterator<Unit> it = planes.iterator(); it.hasNext(); ) {
            final Unit p = it.next();
            final int cost = p.getUnitAttachment().getCarrierCost();
            if (cost > 0 && cost <= room) {
              group.add(p);
              room -= cost;
              it.remove();
            }
          }
        }
        out.add(group);
      }
    }
    return out;
  }

  /** Sea moves from {@code from} to {@code z} through zones without enemies (-1: no route). */
  static int passi(
      final GameState data, final GamePlayer player, final Territory from, final Territory z) {
    if (from.equals(z)) {
      return 0;
    }
    final Optional<Route> r =
        data.getMap().getRoute(from, z, ProMatches.territoryCanMoveSeaUnitsThrough(player, false));
    return r.map(Route::numberOfSteps).orElse(-1);
  }

  /**
   * How much escort was missing: units of the warship type with the best defense per PU (ties: the
   * cheaper) added until it holds.
   */
  private static JsonObject mancante(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory z,
      final List<Unit> att,
      final List<Unit> base,
      final List<Unit> allCand,
      final IndicazioniP5.Config cfg) {
    final JsonObject o = new JsonObject();
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
    String type = null;
    int cost = Integer.MAX_VALUE;
    double best = -1;
    for (final String t : ForzaSbarco.tipiScorta(data, player, costs)) {
      final Optional<UnitType> ut = data.getUnitTypeList().getUnitType(t);
      if (ut.isEmpty() || !ut.get().getUnitAttachment().isSea() || costs.getOrDefault(t, 0) <= 0) {
        continue;
      }
      final double perPu = (double) ut.get().getUnitAttachment().getDefense(player) / costs.get(t);
      if (perPu > best + 1e-9 || (Math.abs(perPu - best) <= 1e-9 && costs.get(t) < cost)) {
        best = perPu;
        type = t;
        cost = costs.get(t);
      }
    }
    if (type == null) {
      o.addProperty("tipo", "-");
      return o;
    }
    final List<Unit> def = new ArrayList<>(base);
    def.addAll(allCand);
    int n = 0;
    double w = vittoria(proData, calc, z, att, def);
    final UnitType ut = data.getUnitTypeList().getUnitType(type).orElseThrow();
    while (w > cfg.sogliaTrasporti() && n < cfg.maxUnitaMancanti()) {
      n++;
      def.addAll(ut.createTemp(1, player));
      w = vittoria(proData, calc, z, att, def);
    }
    o.addProperty("tipo", type);
    o.addProperty("unita", n);
    o.addProperty("pu", n * cost);
    o.addProperty("vittoria_nemica_con_le_mancanti", AlleatiR5.round1(w));
    o.addProperty("oltre_il_limite", w > cfg.sogliaTrasporti());
    return o;
  }

  private static double vittoria(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory z,
      final List<Unit> att,
      final List<Unit> def) {
    if (att.isEmpty()) {
      return 0;
    }
    if (def.isEmpty()) {
      return 100;
    }
    return calc.calculateBattleResults(proData, z, att, def, List.of()).getWinPercentage();
  }

  private static int tuv(final ProData proData, final Collection<Unit> units) {
    int s = 0;
    for (final Unit u : units) {
      s += proData.getUnitValueMap().getInt(u.getType());
    }
    return s;
  }

  // ===================================================================== the moves

  /**
   * At the start of the turn (purchase, real board): the losses of last turn's escort and, for an
   * exposed landing (step P6), of its transports (the enemy's turn is over); a new turn's state.
   */
  static void inizioTurno(final GameState data, final GamePlayer player, final Stato st) {
    final int round = data.getSequence().getRound();
    final boolean pending =
        (!st.scorteMosse.isEmpty() || !st.trasportiEsposti.isEmpty()) && st.roundScorte < round;
    if (pending) {
      final Set<String> alive = new HashSet<>();
      for (final Territory t : data.getMap().getTerritories()) {
        t.getMatches(Matches.unitIsOwnedBy(player)).forEach(u -> alive.add(u.getId().toString()));
      }
      final long lost = st.scorteMosse.stream().filter(id -> !alive.contains(id)).count();
      final JsonObject row = new JsonObject();
      row.addProperty("round_scorta", st.roundScorte);
      row.addProperty("bersaglio", st.bersaglioScorte);
      row.addProperty("navi_scorta", st.scorteMosse.size());
      row.addProperty("navi_scorta_perse", lost);
      if (st.scorteEsposte) {
        row.addProperty("trasporti", st.trasportiEsposti.size());
        row.addProperty(
            "trasporti_persi",
            st.trasportiEsposti.stream().filter(id -> !alive.contains(id)).count());
        IndicazioniP6.registra(
            data,
            player,
            TIPO_ESPOSTA,
            "PERDITE_ESPOSTO",
            "trasporti e navi di scorta persi nel turno nemico dopo lo sbarco esposto",
            row);
      } else {
        IndicazioniP5.registra(
            data,
            player,
            TIPO,
            "PERDITE_SCORTA",
            "unita' di scorta perse nel turno nemico dopo lo sbarco scortato",
            row);
      }
      st.scorteMosse.clear();
      st.trasportiEsposti.clear();
      st.scorteEsposte = false;
      st.roundScorte = -1;
    }
    st.inizio(round);
  }

  /**
   * The non-combat move before Hard's: the decisions of the round in the register and, for the
   * landings made, the escort moved to the unload zone; returns the units held out of Hard's move.
   */
  static Set<Unit> nonCombattimento(
      final GameData data, final GamePlayer player, final IMoveDelegate del, final Stato st) {
    final Set<Unit> held = new LinkedHashSet<>();
    if (st.round != data.getSequence().getRound()) {
      return held;
    }
    final Map<String, Unit> byId = new java.util.HashMap<>();
    final Map<Unit, Territory> where = new java.util.HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getMatches(Matches.unitIsOwnedBy(player))) {
        byId.put(u.getId().toString(), u);
        where.put(u, t);
      }
    }
    for (final Piano p : st.piani.values()) {
      final JsonObject row = p.riga().deepCopy();
      final Territory target = data.getMap().getTerritoryOrNull(p.bersaglio());
      row.addProperty(
          "proprietario_dopo_le_battaglie", target == null ? "?" : target.getOwner().getName());
      row.addProperty(
          "preso",
          target != null
              && (target.isOwnedBy(player) || player.isAllied(target.getOwner()))
              && !target.getOwner().isNull());
      if (!p.eseguito()) {
        IndicazioniP5.registra(
            data,
            player,
            TIPO,
            "SCORTA_MANCANTE",
            "sbarco esposto annullato: nemmeno con tutta la scorta i trasporti reggono",
            row);
        continue;
      }
      final JsonObject moved = new JsonObject();
      final Map<String, Integer> notMoved = new TreeMap<>();
      int landed = 0;
      for (final String id : p.trasporti()) {
        final Unit tr = byId.get(id);
        if (tr != null && !tr.getUnloaded().isEmpty()) {
          landed += tr.getUnloaded().size();
        }
      }
      row.addProperty("truppe_sbarcate", landed);
      if (p.esposta() && landed == 0) {
        // step P6 (B): kept in the plan, but Hard did not land (no escort moved)
        IndicazioniP6.registra(
            data,
            player,
            TIPO_ESPOSTA,
            "ESITO_ESPOSTO",
            "sbarco esposto tenuto nel piano ma non eseguito da Hard",
            row);
        continue;
      }
      for (final var e : p.scorta().entrySet()) {
        final Territory z = data.getMap().getTerritoryOrNull(e.getKey());
        if (z == null) {
          continue;
        }
        final List<Unit> group = new ArrayList<>();
        for (final String id : e.getValue()) {
          final Unit u = byId.get(id);
          if (u == null) {
            notMoved.merge("perse_o_assenti", 1, Integer::sum);
          } else {
            group.add(u);
          }
        }
        final List<Unit> arrived = new ArrayList<>();
        // the ships first (from each zone), then the planes onto the carriers
        final Map<Territory, List<Unit>> byFrom = new LinkedHashMap<>();
        group.forEach(u -> byFrom.computeIfAbsent(where.get(u), k -> new ArrayList<>()).add(u));
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
            g.getValue().forEach(u -> notMoved.merge(u.getType().getName(), 1, Integer::sum));
            continue;
          }
          final List<Unit> ships = g.getValue().stream().filter(Matches.unitIsSea()).toList();
          final List<Unit> planes = g.getValue().stream().filter(Matches.unitIsAir()).toList();
          if (!ships.isEmpty() && del.performMove(new MoveDescription(ships, r.get())).isEmpty()) {
            arrived.addAll(ships);
          } else {
            ships.forEach(u -> notMoved.merge(u.getType().getName(), 1, Integer::sum));
          }
          for (final Unit pl : planes) {
            if (del.performMove(new MoveDescription(List.of(pl), r.get())).isEmpty()) {
              arrived.add(pl);
            } else {
              notMoved.merge(pl.getType().getName(), 1, Integer::sum);
            }
          }
        }
        held.addAll(arrived);
        moved.add(e.getKey(), TrasportiProtetti.perTipo(arrived));
        arrived.forEach(u -> st.scorteMosse.add(u.getId().toString()));
      }
      row.add("scorta_mossa", moved);
      final JsonObject nm = new JsonObject();
      notMoved.forEach(nm::addProperty);
      row.add("scorta_non_mossa", nm);
      st.roundScorte = data.getSequence().getRound();
      st.bersaglioScorte = p.bersaglio();
      if (p.esposta()) {
        // step P6 (B): the landing's transports for the losses of the enemy's turn
        for (final String id : p.trasporti()) {
          final Unit tr = byId.get(id);
          if (tr != null && !tr.getUnloaded().isEmpty()) {
            st.trasportiEsposti.add(id);
          }
        }
        st.scorteEsposte = true;
        row.addProperty("trasporti_sbarco", st.trasportiEsposti.size());
        IndicazioniP6.registra(
            data,
            player,
            TIPO_ESPOSTA,
            "SBARCO_ESPOSTO",
            "sbarco con i trasporti esposti eseguito (scarto di Hard non applicato), scorta"
                + " minima se basta",
            row);
        continue;
      }
      IndicazioniP5.registra(
          data,
          player,
          TIPO,
          "SCORTA_TROVATA",
          "sbarco esposto eseguito con la scorta minima che basta; scorta mossa nella zona",
          row);
    }
    st.piani.clear();
    return held;
  }
}
