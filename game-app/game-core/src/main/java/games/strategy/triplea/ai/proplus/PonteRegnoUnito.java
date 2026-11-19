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
  private static final class Viaggio {
    final Unit trasporto;
    final Territory da;
    final Route rotta;
    final Territory zona;
    final List<Unit> carico;

    /** Land territory → units loaded from there now (empty if already on board). */
    final Map<Territory, List<Unit>> caricoDa = new LinkedHashMap<>();

    Viaggio(
        final Unit trasporto,
        final Territory da,
        final Route rotta,
        final Territory zona,
        final List<Unit> carico) {
      this.trasporto = trasporto;
      this.da = da;
      this.rotta = rotta;
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
    final List<Viaggio> trips = new ArrayList<>();
    int reach = 0;
    for (final Unit tr : ForzaSbarco.trasporti(data, player)) {
      if (zones.isEmpty() || used.contains(tr) || !tr.getUnloaded().isEmpty()) {
        continue;
      }
      final Territory from = where.get(tr);
      final int mv = tr.getMovementLeft().intValue();
      if (from == null || mv < 0) {
        continue;
      }
      final List<Unit> cargo = new ArrayList<>(tr.getTransporting());
      if (!cargo.stream().allMatch(u -> u.isOwnedBy(player))) {
        continue;
      }
      final List<Unit> moving = new ArrayList<>(List.of(tr));
      moving.addAll(cargo);
      Route best = null;
      Territory bestZone = null;
      for (final Territory z : zones) {
        final Route r;
        if (z.equals(from)) {
          r = new Route(from);
        } else {
          final Optional<Route> o = data.getMap().getRouteForUnits(from, z, free, moving, player);
          if (o.isEmpty() || o.get().numberOfSteps() > mv) {
            continue;
          }
          r = o.get();
        }
        if (best == null || r.numberOfSteps() < best.numberOfSteps()) {
          best = r;
          bestZone = z;
        }
      }
      if (best == null) {
        continue;
      }
      reach++;
      final Viaggio v = new Viaggio(tr, from, best, bestZone, cargo);
      if (cargo.isEmpty()) {
        if (!ProMatches.transportCanLoad().test(tr)) {
          continue;
        }
        final Set<Territory> sources = new LinkedHashSet<>();
        for (final Territory l : data.getMap().getNeighbors(from, Matches.territoryIsLand())) {
          if (!l.equals(t) && (l.isOwnedBy(player) || player.isAllied(l.getOwner()))) {
            sources.add(l);
          }
        }
        final List<Unit> load =
            ProTransportUtils.getUnitsToTransportFromTerritories(
                player,
                tr,
                sources,
                used,
                ProMatches.unitIsOwnedTransportableUnitAndCanBeLoaded(player, tr, false));
        for (final Unit u : load) {
          final Territory src = where.get(u);
          if (src != null && !src.isWater()) {
            v.carico.add(u);
            v.caricoDa.computeIfAbsent(src, k -> new ArrayList<>()).add(u);
          }
        }
      }
      if (v.carico.isEmpty()) {
        continue;
      }
      used.add(tr);
      used.addAll(v.carico);
      trips.add(v);
    }
    row.addProperty("trasporti_che_arrivano", reach);
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
    final Set<GamePlayer> enemies = new LinkedHashSet<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (!p.equals(player) && player.isAtWar(p)) {
        enemies.add(p);
      }
    }
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
    // Hard's check of exposed transports, per unload zone
    final ProCombatMoveAi.ProductionAndIsCapital pc = ProCombatMoveAi.getProductionAndIsCapital(t);
    final Map<Territory, List<Viaggio>> byZone = new LinkedHashMap<>();
    trips.forEach(v -> byZone.computeIfAbsent(v.zona, k -> new ArrayList<>()).add(v));
    final List<Viaggio> go = new ArrayList<>();
    final com.google.gson.JsonArray zj = new com.google.gson.JsonArray();
    for (final var e : byZone.entrySet()) {
      final Territory z = e.getKey();
      final List<Unit> def = new ArrayList<>();
      final List<Unit> cargo = new ArrayList<>();
      for (final Viaggio v : e.getValue()) {
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
      final boolean exposed = esposti(swing, value, cfg.fattoreEsposizione());
      final JsonObject zo = new JsonObject();
      zo.addProperty("zona", z.getName());
      zo.addProperty("trasporti", e.getValue().size());
      zo.addProperty("unita", cargo.size());
      zo.addProperty("navi_alleate_altrui", def.stream().filter(u -> !u.isOwnedBy(player)).count());
      zo.addProperty("minaccia_tuv", tuv(proData, att));
      zo.addProperty("vittoria_nemica", AlleatiR5.round1(win));
      zo.addProperty("tuv_nemico", AlleatiR5.round1(swing));
      zo.addProperty("valore", AlleatiR5.round1(value));
      zo.addProperty("esposti", exposed);
      zj.add(zo);
      if (!exposed) {
        go.addAll(e.getValue());
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

  /** Load (if needed), move, unload; returns the units ashore in {@code t}. */
  private static int esegui(final IMoveDelegate del, final Viaggio v, final Territory t) {
    final List<Unit> aboard = new ArrayList<>(v.trasporto.getTransporting());
    for (final var e : v.caricoDa.entrySet()) {
      if (e.getValue().isEmpty()) {
        continue;
      }
      final Map<Unit, Unit> map = new HashMap<>();
      e.getValue().forEach(u -> map.put(u, v.trasporto));
      final Optional<String> err =
          del.performMove(new MoveDescription(e.getValue(), new Route(e.getKey(), v.da), map));
      if (err.isPresent()) {
        ProLogger.warn("[Pro+] P7 ponte: carico non riuscito " + err.get());
        continue;
      }
      aboard.addAll(e.getValue());
    }
    if (aboard.isEmpty()) {
      return 0;
    }
    if (v.rotta.numberOfSteps() > 0) {
      final List<Unit> units = new ArrayList<>(List.of(v.trasporto));
      units.addAll(aboard);
      final Optional<String> err = del.performMove(new MoveDescription(units, v.rotta));
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

  private static int tuv(final ProData proData, final Collection<Unit> units) {
    int s = 0;
    for (final Unit u : units) {
      s += proData.getUnitValueMap().getInt(u.getType());
    }
    return s;
  }
}
