package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProCombatMoveAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.pro.util.ProTransportUtils;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Step P7, B ({@code ponte_uk}) while the ally's bridgehead T of this round is allied: in the
 * non-combat move, after Hard's combat move (its attacks and landings are done) and before Hard's
 * non-combat move, the own transports that reach a sea zone next to T this turn bring there the own
 * land units they can load now (already on board, or in a land territory next to the transport's
 * zone). No unit is held for later, nothing waits.
 *
 * <ul>
 *   <li>The capital and the factories keep Hard's defense: a unit leaves them only while the
 *       enemy's realistic attack wins there at most {@code 100 - } Hard's win%.
 *   <li>Exposed transports (Hard's check): dropped if {@code fattore_esposizione} x the TUV the
 *       enemy destroys in the unload zone (against the transports and every allied sea unit there,
 *       the ally's warships included) is more than the value (TUV landed + production of T x (1 + 3
 *       x capital)). No way around the check.
 *   <li>If no own unit can reach T this turn, nothing happens.
 * </ul>
 */
final class PonteRegnoUnito {
  private PonteRegnoUnito() {}

  /** One transport's trip to T. */
  static final class Viaggio {
    final Unit trasporto;
    final Territory da;

    /** Start → the sea zone where it loads (empty route if it loads where it is). */
    final Route rotta;

    /** The sea zone where it loads. */
    final Territory sosta;

    /** The loading zone → the unload zone. */
    final Route rotta2;

    final Territory zona;
    final List<Unit> carico;

    /** Land territory → units loaded from there now (empty if already on board). */
    final Map<Territory, List<Unit>> caricoDa = new LinkedHashMap<>();

    Viaggio(
        final Unit trasporto,
        final Territory da,
        final Route rotta,
        final Territory sosta,
        final Route rotta2,
        final Territory zona,
        final List<Unit> carico) {
      this.trasporto = trasporto;
      this.da = da;
      this.rotta = rotta;
      this.sosta = sosta;
      this.rotta2 = rotta2;
      this.zona = zona;
      this.carico = new ArrayList<>(carico);
    }
  }

  /** The value of the reinforcement (pure rule): TUV landed + production x (1 + 3 x capital). */
  static double valore(final int tuvSbarcato, final int produzione, final boolean capitale) {
    return tuvSbarcato + produzione * (1.0 + 3.0 * (capitale ? 1 : 0));
  }

  /** Hard's check of exposed transports (pure rule). */
  static boolean esposti(final double tuvNemico, final double valore, final double fattore) {
    return fattore * tuvNemico > valore;
  }

  /** The reinforcements of this turn; nothing if B is off or the ally landed nowhere in force. */
  static void nonCombattimento(
      final ProData proData, final ProOddsCalculator calc, final IMoveDelegate del) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final Map<String, Integer> landed = IndicazioniP7.sbarchiDelRound(data, player);
    if (landed.isEmpty()) {
      return;
    }
    final IndicazioniP7.Config cfg = IndicazioniP7.Config.get();
    final Set<Unit> used = new HashSet<>();
    final List<Map.Entry<String, Integer>> order = new ArrayList<>(landed.entrySet());
    order.sort(
        Map.Entry.<String, Integer>comparingByValue()
            .reversed()
            .thenComparing(Map.Entry.comparingByKey()));
    for (final Map.Entry<String, Integer> e : order) {
      final Territory t = data.getMap().getTerritoryOrNull(e.getKey());
      if (t == null || t.getOwner().isNull()) {
        continue;
      }
      final JsonObject row = new JsonObject();
      row.addProperty("territorio", t.getName());
      row.addProperty("unita_sbarcate_alleato", e.getValue());
      row.addProperty("proprietario", t.getOwner().getName());
      if (!(t.isOwnedBy(player) || player.isAllied(t.getOwner()))) {
        // the ally's landing failed: the attack weight acted in the combat move
        row.addProperty(
            "unita_proprie_in_t",
            t.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsLand())).size());
        IndicazioniP7.registra(
            data,
            player,
            IndicazioniP7.TIPO_B,
            "PONTE_ESITO",
            "territorio ancora del nemico dopo il combattimento (peso d'attacco)",
            row);
        continue;
      }
      try {
        rinforza(proData, calc, del, t, cfg, used, row);
      } catch (final RuntimeException ex) {
        ProLogger.warn("[Pro+] P7 ponte: " + ex);
      }
    }
  }

  private static void rinforza(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate del,
      final Territory t,
      final IndicazioniP7.Config cfg,
      final Set<Unit> used,
      final JsonObject row) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final Predicate<Territory> free = SbarcoConcentrato.libera(player);
    final Set<Territory> zones = new LinkedHashSet<>();
    for (final Territory z : data.getMap().getNeighbors(t, Matches.territoryIsWater())) {
      if (free.test(z)) {
        zones.add(z);
      }
    }
    final Map<Unit, Territory> where = new HashMap<>();
    for (final Territory x : data.getMap().getTerritories()) {
      x.getUnits().forEach(u -> where.put(u, x));
    }
    final Set<GamePlayer> enemies = new LinkedHashSet<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (!p.equals(player) && player.isAtWar(p)) {
        enemies.add(p);
      }
    }
    // the unload zones, the least threatened first (enemy TUV in reach - allied cover there)
    final ProTerritoryManager tmz = new ProTerritoryManager(calc, proData);
    if (!zones.isEmpty()) {
      tmz.populateEnemyAttackOptionsOfEveryEnemy(zones, zones);
    }
    final Map<Territory, Integer> risk = new HashMap<>();
    for (final Territory z : zones) {
      risk.put(
          z,
          tuv(proData, ForzaSbarco.minaccia(tmz, z, enemies)) - tuv(proData, copertura(player, z)));
    }
    final List<Territory> ordered = new ArrayList<>(zones);
    ordered.sort(
        java.util.Comparator.comparingInt((Territory z) -> risk.get(z))
            .thenComparing(Territory::getName));
    final List<Viaggio> trips = new ArrayList<>();
    final Set<Unit> reached = new LinkedHashSet<>();
    for (final Unit tr : ForzaSbarco.trasporti(data, player)) {
      if (zones.isEmpty() || used.contains(tr) || !tr.getUnloaded().isEmpty()) {
        continue;
      }
      final Viaggio v = pianifica(data, player, tr, where, ordered, Set.of(), used, t, reached);
      if (v != null) {
        used.add(tr);
        used.addAll(v.carico);
        trips.add(v);
      }
    }
    row.addProperty("trasporti_che_arrivano", reached.size());
    if (trips.isEmpty()) {
      row.addProperty("unita_caricabili", 0);
      IndicazioniP7.registra(
          data,
          player,
          IndicazioniP7.TIPO_B,
          "PONTE_NON_RAGGIUNGIBILE",
          "nessuna unita' propria puo' arrivare nel territorio in questo turno",
          row);
      return;
    }
    // the enemy's realistic attacks on the sources at risk and on the unload zones
    final Set<Territory> guarded = new LinkedHashSet<>();
    for (final Viaggio v : trips) {
      for (final Territory src : v.caricoDa.keySet()) {
        if (src.equals(proData.getMyCapital())
            || ProMatches.territoryHasInfraFactoryAndIsLand().test(src)) {
          guarded.add(src);
        }
      }
    }
    final Set<Territory> threatened = new LinkedHashSet<>(guarded);
    trips.forEach(v -> threatened.add(v.zona));
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateEnemyAttackOptionsOfEveryEnemy(threatened, threatened);
    final double limit = 100 - proData.getWinPercentage();
    int keptHome = 0;
    for (final Territory src : guarded) {
      final List<Unit> att = ForzaSbarco.minaccia(tm, src, enemies);
      while (true) {
        final Set<Unit> leaving = new LinkedHashSet<>();
        trips.forEach(v -> leaving.addAll(v.caricoDa.getOrDefault(src, List.of())));
        if (leaving.isEmpty()) {
          break;
        }
        final List<Unit> def =
            new ArrayList<>(
                src.getMatches(
                    Matches.isUnitAllied(player)
                        .and(Matches.unitIsLand())
                        .or(Matches.isUnitAllied(player).and(Matches.unitIsAir()))));
        def.removeAll(leaving);
        if (ForzaSbarco.vittoria(proData, calc, src, att, def) <= limit) {
          break;
        }
        // the last unit planned from there stays home
        for (int i = trips.size() - 1; i >= 0; i--) {
          final List<Unit> l = trips.get(i).caricoDa.get(src);
          if (l != null && !l.isEmpty()) {
            final Unit u = l.remove(l.size() - 1);
            trips.get(i).carico.remove(u);
            used.remove(u);
            keptHome++;
            break;
          }
        }
      }
    }
    trips.removeIf(v -> v.carico.isEmpty());
    row.addProperty("rimaste_per_la_difesa", keptHome);
    // Hard's check of exposed transports, per unload zone; the trips of an exposed zone try the
    // other zones next to T (up to three passes), the check itself is never skipped
    final ProCombatMoveAi.ProductionAndIsCapital pc = ProCombatMoveAi.getProductionAndIsCapital(t);
    final Set<Territory> bad = new LinkedHashSet<>();
    com.google.gson.JsonArray zj = new com.google.gson.JsonArray();
    for (int pass = 0; pass < 3; pass++) {
      zj = new com.google.gson.JsonArray();
      final Map<Territory, List<Viaggio>> byZone = new LinkedHashMap<>();
      trips.forEach(v -> byZone.computeIfAbsent(v.zona, k -> new ArrayList<>()).add(v));
      final Set<Territory> exposedNow = new LinkedHashSet<>();
      for (final var e : byZone.entrySet()) {
        final JsonObject zo =
            esposizione(proData, calc, tmz, enemies, e.getKey(), e.getValue(), pc, cfg);
        zj.add(zo);
        if (zo.get("esposti").getAsBoolean()) {
          exposedNow.add(e.getKey());
        }
      }
      if (exposedNow.isEmpty()) {
        break;
      }
      bad.addAll(exposedNow);
      final List<Viaggio> moved = new ArrayList<>();
      for (final java.util.Iterator<Viaggio> it = trips.iterator(); it.hasNext(); ) {
        final Viaggio v = it.next();
        if (bad.contains(v.zona)) {
          it.remove();
          used.removeAll(v.carico);
          final Viaggio again =
              pianifica(data, player, v.trasporto, where, ordered, bad, used, t, new HashSet<>());
          if (again != null) {
            used.addAll(again.carico);
            moved.add(again);
          } else {
            used.remove(v.trasporto);
          }
        }
      }
      if (moved.isEmpty()) {
        break;
      }
      trips.addAll(moved);
    }
    final List<Viaggio> go = new ArrayList<>();
    for (final Viaggio v : trips) {
      if (!bad.contains(v.zona)) {
        go.add(v);
      }
    }
    row.add("zone", zj);
    if (go.isEmpty()) {
      IndicazioniP7.registra(
          data,
          player,
          IndicazioniP7.TIPO_B,
          "PONTE_ESPOSTO",
          "trasporti esposti nella zona di scarico (controllo di Hard): nessun rinforzo",
          row);
      return;
    }
    int ashore = 0;
    int trs = 0;
    for (final Viaggio v : go) {
      final int n = esegui(del, v, t);
      if (n > 0) {
        ashore += n;
        trs++;
      }
    }
    row.addProperty("trasporti", trs);
    row.addProperty("unita_sbarcate", ashore);
    IndicazioniP7.registra(
        data,
        player,
        IndicazioniP7.TIPO_B,
        ashore > 0 ? "PONTE_RINFORZO" : "PONTE_NON_RAGGIUNGIBILE",
        ashore > 0
            ? "rinforzo della testa di ponte alleata nello stesso round"
            : "mosse rifiutate dal motore: nessun rinforzo",
        row);
  }

  /**
   * One transport's best trip to T avoiding {@code excluded} zones: the least threatened unload
   * zone it reaches (with its own cargo, or with the units it loads in a sea zone on the way: start
   * → loading zone → unload zone within its movement), null if none. {@code reached} gets it if it
   * reaches any zone.
   */
  static @javax.annotation.Nullable Viaggio pianifica(
      final GameData data,
      final GamePlayer player,
      final Unit tr,
      final Map<Unit, Territory> where,
      final List<Territory> ordered,
      final Set<Territory> excluded,
      final Set<Unit> used,
      final Territory t,
      final Set<Unit> reached) {
    final Predicate<Territory> free = SbarcoConcentrato.libera(player);
    final Territory from = where.get(tr);
    final int mv = tr.getMovementLeft().intValue();
    if (from == null || mv < 0) {
      return null;
    }
    final List<Unit> cargo = new ArrayList<>(tr.getTransporting());
    if (!cargo.stream().allMatch(u -> u.isOwnedBy(player))) {
      return null;
    }
    final Map<Territory, Integer> fromStart =
        SbarcoConcentrato.distanze(data, List.of(from), mv, free);
    final boolean canLoad = cargo.isEmpty() && ProMatches.transportCanLoad().test(tr);
    for (final Territory z : ordered) {
      if (excluded.contains(z)) {
        continue;
      }
      final Map<Territory, Integer> toZone = SbarcoConcentrato.distanze(data, List.of(z), mv, free);
      Viaggio best = null;
      for (final var e : fromStart.entrySet()) {
        final Territory y = e.getKey();
        final Integer back = toZone.get(y);
        if (back == null || e.getValue() + back > mv) {
          continue;
        }
        reached.add(tr);
        if (!cargo.isEmpty() && !y.equals(from)) {
          continue;
        }
        final List<Unit> load = new ArrayList<>();
        final Map<Territory, List<Unit>> loadFrom = new LinkedHashMap<>();
        if (canLoad) {
          final Set<Territory> sources = new LinkedHashSet<>();
          for (final Territory l : data.getMap().getNeighbors(y, Matches.territoryIsLand())) {
            if (!l.equals(t) && (l.isOwnedBy(player) || player.isAllied(l.getOwner()))) {
              sources.add(l);
            }
          }
          for (final Unit u :
              ProTransportUtils.getUnitsToTransportFromTerritories(
                  player,
                  tr,
                  sources,
                  used,
                  ProMatches.unitIsOwnedTransportableUnitAndCanBeLoaded(player, tr, false))) {
            final Territory src = where.get(u);
            if (src != null && !src.isWater()) {
              load.add(u);
              loadFrom.computeIfAbsent(src, k -> new ArrayList<>()).add(u);
            }
          }
        }
        final int n = cargo.size() + load.size();
        if (n == 0 || (best != null && n <= best.carico.size())) {
          continue;
        }
        final List<Unit> moving = new ArrayList<>(List.of(tr));
        moving.addAll(cargo);
        final Optional<Route> r1 =
            y.equals(from)
                ? Optional.of(new Route(from))
                : data.getMap().getRouteForUnits(from, y, free, moving, player);
        moving.addAll(load);
        final Optional<Route> r2 =
            y.equals(z)
                ? Optional.of(new Route(y))
                : data.getMap().getRouteForUnits(y, z, free, moving, player);
        if (r1.isEmpty()
            || r2.isEmpty()
            || r1.get().numberOfSteps() + r2.get().numberOfSteps() > mv) {
          continue;
        }
        final List<Unit> all = new ArrayList<>(cargo);
        all.addAll(load);
        best = new Viaggio(tr, from, r1.get(), y, r2.get(), z, all);
        best.caricoDa.putAll(loadFrom);
      }
      if (best != null) {
        return best;
      }
    }
    return null;
  }

  /** Allied sea units in {@code z} that cover transports (warships, transports). */
  static List<Unit> copertura(final GamePlayer player, final Territory z) {
    final List<Unit> out = new ArrayList<>();
    for (final Unit u : z.getUnits()) {
      if (player.isAllied(u.getOwner())
          && Matches.unitIsSea().test(u)
          && !Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)
          && ForzaSbarco.copre(u.getUnitAttachment())) {
        out.add(u);
      }
    }
    return out;
  }

  /** Hard's check of exposed transports in {@code z} for {@code trips} (a row of the register). */
  static JsonObject esposizione(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritoryManager tm,
      final Set<GamePlayer> enemies,
      final Territory z,
      final List<Viaggio> trips,
      final ProCombatMoveAi.ProductionAndIsCapital pc,
      final IndicazioniP7.Config cfg) {
    final GamePlayer player = proData.getPlayer();
    final List<Unit> def = new ArrayList<>();
    final List<Unit> cargo = new ArrayList<>();
    for (final Viaggio v : trips) {
      def.add(v.trasporto);
      cargo.addAll(v.carico);
    }
    for (final Unit u : z.getUnits()) {
      if (!def.contains(u)
          && player.isAllied(u.getOwner())
          && Matches.unitIsSea().test(u)
          && (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)
              || ForzaSbarco.copre(u.getUnitAttachment()))) {
        def.add(u);
      }
    }
    final List<Unit> att = ForzaSbarco.minaccia(tm, z, enemies);
    double swing = 0;
    double win = 0;
    if (!att.isEmpty()) {
      final ProBattleResult r = calc.calculateBattleResults(proData, z, att, def, List.of());
      swing = Math.max(0, r.getTuvSwing());
      win = r.getWinPercentage();
    }
    final double value = valore(tuv(proData, cargo), pc.production, pc.isCapital == 1);
    final JsonObject zo = new JsonObject();
    zo.addProperty("zona", z.getName());
    zo.addProperty("trasporti", trips.size());
    zo.addProperty("unita", cargo.size());
    zo.addProperty("navi_alleate_altrui", def.stream().filter(u -> !u.isOwnedBy(player)).count());
    zo.addProperty("minaccia_tuv", tuv(proData, att));
    zo.addProperty("vittoria_nemica", AlleatiR5.round1(win));
    zo.addProperty("tuv_nemico", AlleatiR5.round1(swing));
    zo.addProperty("valore", AlleatiR5.round1(value));
    zo.addProperty("esposti", esposti(swing, value, cfg.fattoreEsposizione()));
    return zo;
  }

  /** Load (if needed), move, unload; returns the units ashore in {@code t}. */
  static int esegui(final IMoveDelegate del, final Viaggio v, final Territory t) {
    final List<Unit> aboard = new ArrayList<>(v.trasporto.getTransporting());
    if (v.rotta.numberOfSteps() > 0) {
      final List<Unit> units = new ArrayList<>(List.of(v.trasporto));
      units.addAll(aboard);
      final Optional<String> err = del.performMove(new MoveDescription(units, v.rotta));
      if (err.isPresent()) {
        ProLogger.warn("[Pro+] P7 ponte: rotta verso il carico non riuscita " + err.get());
        return 0;
      }
    }
    for (final var e : v.caricoDa.entrySet()) {
      if (e.getValue().isEmpty()) {
        continue;
      }
      final Map<Unit, Unit> map = new HashMap<>();
      e.getValue().forEach(u -> map.put(u, v.trasporto));
      final Optional<String> err =
          del.performMove(new MoveDescription(e.getValue(), new Route(e.getKey(), v.sosta), map));
      if (err.isPresent()) {
        ProLogger.warn("[Pro+] P7 ponte: carico non riuscito " + err.get());
        continue;
      }
      aboard.addAll(e.getValue());
    }
    if (aboard.isEmpty()) {
      return 0;
    }
    if (v.rotta2.numberOfSteps() > 0) {
      final List<Unit> units = new ArrayList<>(List.of(v.trasporto));
      units.addAll(aboard);
      final Optional<String> err = del.performMove(new MoveDescription(units, v.rotta2));
      if (err.isPresent()) {
        ProLogger.warn("[Pro+] P7 ponte: rotta non riuscita " + err.get());
        return 0;
      }
    }
    final Optional<String> err = del.performMove(new MoveDescription(aboard, new Route(v.zona, t)));
    if (err.isPresent()) {
      ProLogger.warn("[Pro+] P7 ponte: scarico non riuscito " + err.get());
      return 0;
    }
    return aboard.size();
  }

  static int tuv(final ProData proData, final Collection<Unit> units) {
    int s = 0;
    for (final Unit u : units) {
      s += proData.getUnitValueMap().getInt(u.getType());
    }
    return s;
  }
}
