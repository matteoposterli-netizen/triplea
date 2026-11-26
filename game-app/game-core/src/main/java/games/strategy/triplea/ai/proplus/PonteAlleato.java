package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step PV1 (C, switch {@code ponte_alleato}; data: the UK and its ally the USA).
 *
 * <ul>
 *   <li>C1, the UK's non-combat move (before Hard's): its land units in America next to a sea zone
 *       with empty transports of the ally load on them (the engine allows it, it only forbids the
 *       unload in the same turn: {@code MoveValidator} "Cannot load and unload an allied transport
 *       in the same round");
 *   <li>C1, the ally's turn: those transports are out of Hard's combat and non-combat moves; after
 *       Hard's non-combat move they sail to a sea zone next to the allied bridgehead with the most
 *       US land units (else next to the reserve, London), the nearest safe zone on the way if not
 *       reachable this turn (safe = the enemy's best attack wins at most {@code soglia_zona_pct}
 *       against the allied units there);
 *   <li>C1, the UK's next turn: the British units on board unload into that territory (non-combat,
 *       before Hard's);
 *   <li>C2: the other British land units in America never march away: in a port they stay, else
 *       they go back toward the nearest port by land. Hard's combat move is unchanged.
 * </ul>
 */
public final class PonteAlleato {
  private PonteAlleato() {}

  static boolean attivo(final GameState data, @Nullable final GamePlayer player) {
    if (player == null) {
      return false;
    }
    final LevePv1.Config cfg = LevePv1.Config.get();
    return (cfg.ponteNazione().equals(player.getName())
            || cfg.ponteAlleato().equals(player.getName()))
        && LevePv1.accesa(data, player, StrategaInterruttori.PONTE_ALLEATO);
  }

  static boolean britannico(final GamePlayer player) {
    return LevePv1.Config.get().ponteNazione().equals(player.getName());
  }

  // ======================================================================== pure rules

  /** The bridgehead (pure rule): the most US land units (> 0), ties by name; else empty. */
  static Optional<String> testaDiPonte(final Map<String, Integer> usLandUnits) {
    return usLandUnits.entrySet().stream()
        .filter(e -> e.getValue() > 0)
        .min(
            Comparator.comparingInt((Map.Entry<String, Integer> e) -> -e.getValue())
                .thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey);
  }

  // ======================================================================== the board

  private static @Nullable GamePlayer nazione(final GameState data, final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private static boolean amica(final GamePlayer player, final Territory t) {
    return !t.isWater()
        && !t.getOwner().isNull()
        && (t.isOwnedBy(player) || player.isAllied(t.getOwner()));
  }

  /** The land units of the UK in America (not on board). */
  static List<Unit> inAmerica(final GameState data, final GamePlayer uk) {
    final List<Unit> out = new ArrayList<>();
    for (final String n : LevePv1.Config.get().america()) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null) {
        out.addAll(t.getMatches(terra(uk)));
      }
    }
    return out;
  }

  private static Predicate<Unit> terra(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player)
        .and(Matches.unitIsLand())
        .and(Matches.unitIsNotInfrastructure())
        .and(Matches.unitIsAaForAnything().negate());
  }

  /** The ally's transports with cargo of another nation on board (anywhere at sea). */
  static Set<Unit> trasportiConCaricoAlleato(final GameState data, final GamePlayer ally) {
    final Set<Unit> out = new LinkedHashSet<>();
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit t :
          z.getMatches(
              Matches.unitIsOwnedBy(ally)
                  .and(Matches.unitIsSeaTransportButNotCombatSeaTransport()))) {
        if (t.getTransporting().stream().anyMatch(u -> !u.isOwnedBy(ally))) {
          out.add(t);
        }
      }
    }
    return out;
  }

  /** The UK's land units on board of the ally's transports. */
  static Set<Unit> aBordoAlleato(final GameState data, final GamePlayer uk) {
    final Set<Unit> out = new LinkedHashSet<>();
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit u : z.getMatches(terra(uk))) {
        final Unit tr = u.getTransportedBy();
        if (tr != null && !tr.isOwnedBy(uk)) {
          out.add(u);
        }
      }
    }
    return out;
  }

  /**
   * The units held from Hard this turn: in the ally's turn its transports with British cargo; in
   * the UK's turn its units on the ally's transports (combat and non-combat).
   */
  static Set<Unit> trattenute(final ProData proData) {
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    if (!attivo(data, player)) {
      return Set.of();
    }
    return britannico(player)
        ? aBordoAlleato(data, player)
        : trasportiConCaricoAlleato(data, player);
  }

  /** The bridgehead now: the allied territory of the data with the most US land units, or none. */
  static Optional<Territory> destinazione(final GameState data, final GamePlayer player) {
    final LevePv1.Config cfg = LevePv1.Config.get();
    final GamePlayer us = nazione(data, cfg.ponteAlleato());
    final Map<String, Integer> counts = new LinkedHashMap<>();
    for (final String n : cfg.testeDiPonte()) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null && us != null && amica(player, t)) {
        counts.put(n, t.getMatches(terra(us)).size());
      }
    }
    final Optional<String> best = testaDiPonte(counts);
    if (best.isPresent()) {
      return Optional.ofNullable(data.getMap().getTerritoryOrNull(best.get()));
    }
    final Territory reserve = data.getMap().getTerritoryOrNull(cfg.riserva());
    return reserve != null && amica(player, reserve) ? Optional.of(reserve) : Optional.empty();
  }

  // ================================================================ C1: the UK loads

  /**
   * The UK's non-combat move, before Hard's: its land units in America that have not moved load on
   * the empty transports of the ally in a sea zone next to them without enemy units. Returns the
   * loaded units.
   */
  static Set<Unit> carica(final GameData data, final GamePlayer uk, final IMoveDelegate del) {
    final LevePv1.Config cfg = LevePv1.Config.get();
    final GamePlayer us = nazione(data, cfg.ponteAlleato());
    final Set<Unit> loaded = new LinkedHashSet<>();
    if (us == null) {
      return loaded;
    }
    final Set<Territory> zones = new LinkedHashSet<>();
    for (final String n : cfg.america()) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null) {
        zones.addAll(data.getMap().getNeighbors(t, Matches.territoryIsWater()));
      }
    }
    final List<Territory> sorted = new ArrayList<>(zones);
    sorted.sort(Comparator.comparing(Territory::getName));
    for (final Territory z : sorted) {
      if (z.anyUnitsMatch(Matches.enemyUnit(uk))) {
        continue;
      }
      final List<Unit> transports =
          z.getMatches(
              Matches.unitIsOwnedBy(us)
                  .and(Matches.unitIsSeaTransportButNotCombatSeaTransport())
                  .and(u -> loadedByUk(u, uk)));
      if (transports.isEmpty()) {
        continue;
      }
      transports.sort(Comparator.comparing(u -> u.getId().toString()));
      final List<Territory> lands =
          new ArrayList<>(data.getMap().getNeighbors(z, Matches.territoryIsLand()));
      lands.removeIf(l -> !cfg.america().contains(l.getName()));
      lands.sort(Comparator.comparing(Territory::getName));
      for (final Territory land : lands) {
        final List<Unit> troops =
            land.getMatches(
                terra(uk)
                    .and(u -> !u.hasMoved())
                    .and(u -> u.getUnitAttachment().getTransportCost() > 0));
        troops.sort(
            Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
                .thenComparing(u -> u.getId().toString()));
        for (final Unit u : troops) {
          final int cost = u.getUnitAttachment().getTransportCost();
          for (final Unit tr : transports) {
            if (TransportTracker.getAvailableCapacity(tr) < cost) {
              continue;
            }
            if (del.performMove(new MoveDescription(List.of(u), new Route(land, z), Map.of(u, tr)))
                .isEmpty()) {
              loaded.add(u);
            }
            break;
          }
        }
      }
    }
    return loaded;
  }

  /** {@code tr} is empty or carries only units the UK loaded (it may take more). */
  private static boolean loadedByUk(final Unit tr, final GamePlayer uk) {
    return tr.getTransporting().stream().allMatch(u -> u.isOwnedBy(uk));
  }

  // ============================================================= C1: the ally sails

  /**
   * The ally's non-combat move, after Hard's: its transports with British cargo sail to a safe sea
   * zone next to the bridgehead (or the safe zone nearest to it on the way). Returns the number of
   * transports moved.
   */
  static int naviga(final ProData proData, final ProOddsCalculator calc, final IMoveDelegate del) {
    final GameData data = proData.getData();
    final GamePlayer us = proData.getPlayer();
    final LevePv1.Config cfg = LevePv1.Config.get();
    final Set<Unit> transports = trasportiConCaricoAlleato(data, us);
    final JsonObject row = new JsonObject();
    row.addProperty("trasporti_con_truppe_britanniche", transports.size());
    if (transports.isEmpty()) {
      return 0;
    }
    final Optional<Territory> target = destinazione(data, us);
    row.addProperty("destinazione", target.map(Territory::getName).orElse("-"));
    int moved = 0;
    int cargo = 0;
    if (target.isPresent()) {
      final Map<Territory, List<Unit>> byZone = new LinkedHashMap<>();
      for (final Territory z : data.getMap().getTerritories()) {
        if (z.isWater()) {
          final List<Unit> here = z.getMatches(transports::contains);
          if (!here.isEmpty()) {
            byZone.put(z, here);
          }
        }
      }
      final Predicate<Territory> sea = x -> x.isWater() && !x.anyUnitsMatch(Matches.enemyUnit(us));
      for (final var e : byZone.entrySet()) {
        final Territory from = e.getKey();
        if (data.getMap().getNeighbors(from).contains(target.get())) {
          continue; // already next to the target: the UK unloads
        }
        final List<Unit> group =
            e.getValue().stream().filter(Matches.unitHasMovementLeft()).toList();
        if (group.isEmpty()) {
          continue;
        }
        final List<Unit> all = new ArrayList<>(group);
        group.forEach(t -> all.addAll(t.getTransporting()));
        final Optional<Route> go = rotta(proData, calc, from, target.get(), group, all, sea);
        if (go.isPresent() && del.performMove(new MoveDescription(all, go.get())).isEmpty()) {
          moved += group.size();
          cargo += all.size() - group.size();
        }
      }
    }
    row.addProperty("trasporti_mossi", moved);
    row.addProperty("truppe_britanniche_mosse", cargo);
    LevePv1.registra(
        data,
        us,
        LevePv1.TIPO_PONTE,
        moved > 0 ? "PV1_PONTE_NAVIGA" : "PV1_PONTE_FERMI",
        "trasporti USA con truppe britanniche verso la testa di ponte",
        row);
    return moved;
  }

  /**
   * The route of {@code group} toward a zone next to {@code target}: a safe zone next to it
   * reachable this turn, else the furthest safe zone on the shortest sea route; empty = stay.
   */
  private static Optional<Route> rotta(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory from,
      final Territory target,
      final List<Unit> group,
      final List<Unit> all,
      final Predicate<Territory> sea) {
    final GameData data = proData.getData();
    final GamePlayer us = proData.getPlayer();
    final int movement =
        group.stream().mapToInt(u -> u.getMovementLeft().intValue()).min().orElse(0);
    final List<Territory> ends =
        new ArrayList<>(data.getMap().getNeighbors(target, Matches.territoryIsWater()));
    ends.removeIf(z -> z.anyUnitsMatch(Matches.enemyUnit(us)));
    Route shortest = null;
    final List<Route> direct = new ArrayList<>();
    for (final Territory z : ends) {
      final Optional<Route> r = data.getMap().getRouteForUnits(from, z, sea, all, us);
      if (r.isEmpty()) {
        continue;
      }
      if (r.get().numberOfSteps() <= movement) {
        direct.add(r.get());
      }
      if (shortest == null || r.get().numberOfSteps() < shortest.numberOfSteps()) {
        shortest = r.get();
      }
    }
    direct.sort(
        Comparator.comparingInt(Route::numberOfSteps).thenComparing(r -> r.getEnd().getName()));
    for (final Route r : direct) {
      if (sicura(proData, calc, r.getEnd(), group)) {
        return Optional.of(r);
      }
    }
    if (shortest == null) {
      return Optional.empty();
    }
    for (int n = Math.min(movement, shortest.numberOfSteps()); n >= 1; n--) {
      final Territory z = shortest.getSteps().get(n - 1);
      if (sicura(proData, calc, z, group)) {
        return Optional.of(
            new Route(from, shortest.getSteps().subList(0, n).toArray(new Territory[0])));
      }
    }
    return Optional.empty();
  }

  /** The enemy's best attack on {@code z} wins at most the threshold against the allied units. */
  static boolean sicura(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory z,
      final List<Unit> group) {
    final GamePlayer us = proData.getPlayer();
    final LeveItalia.Minaccia m = LeveItalia.minaccia(proData, calc, z);
    if (m.vuota()) {
      return true;
    }
    final List<Unit> def = new ArrayList<>(z.getMatches(Matches.alliedUnit(us)));
    group.stream().filter(u -> !def.contains(u)).forEach(def::add);
    return LeveItalia.vittoria(proData, calc, z, m, def) <= LevePv1.Config.get().sogliaZona();
  }

  // =========================================================== C1: the UK unloads

  /**
   * The UK's non-combat move, before Hard's: its units on the ally's transports (not loaded this
   * turn) unload into the bridgehead (or the reserve) next to their zone. Returns the unloaded.
   */
  static Set<Unit> scarica(final GameData data, final GamePlayer uk, final IMoveDelegate del) {
    final Set<Unit> out = new LinkedHashSet<>();
    final Set<Unit> aboard = aBordoAlleato(data, uk);
    if (aboard.isEmpty()) {
      return out;
    }
    final Optional<Territory> target = destinazione(data, uk);
    final Territory reserve = data.getMap().getTerritoryOrNull(LevePv1.Config.get().riserva());
    final Map<Territory, List<Unit>> byZone = new LinkedHashMap<>();
    for (final Territory z : data.getMap().getTerritories()) {
      if (z.isWater()) {
        final List<Unit> here = z.getMatches(u -> aboard.contains(u) && !u.getWasLoadedThisTurn());
        if (!here.isEmpty()) {
          byZone.put(z, here);
        }
      }
    }
    for (final var e : byZone.entrySet()) {
      final Territory z = e.getKey();
      final Set<Territory> next = data.getMap().getNeighbors(z);
      Territory to = null;
      if (target.isPresent() && next.contains(target.get()) && amica(uk, target.get())) {
        to = target.get();
      } else if (reserve != null && next.contains(reserve) && amica(uk, reserve)) {
        to = reserve;
      }
      if (to == null) {
        continue;
      }
      if (del.performMove(new MoveDescription(e.getValue(), new Route(z, to))).isEmpty()) {
        out.addAll(e.getValue());
      }
    }
    return out;
  }

  // =========================================================== C2: wait at the port

  /**
   * The UK's non-combat move, before Hard's: its land units in America outside the ports go back
   * toward the nearest port by land; every British land unit in America is then held (returned).
   */
  static Set<Unit> alPorto(final GameData data, final GamePlayer uk, final IMoveDelegate del) {
    final LevePv1.Config cfg = LevePv1.Config.get();
    final List<Territory> all = new ArrayList<>();
    cfg.porti()
        .forEach(n -> Optional.ofNullable(data.getMap().getTerritoryOrNull(n)).ifPresent(all::add));
    // the ports next to a sea zone with transports of the ally come first: the troops gather
    // where they can load (the other ports are used only without such a port)
    final GamePlayer us = nazione(data, cfg.ponteAlleato());
    final List<Territory> withTransports =
        all.stream()
            .filter(
                p ->
                    us != null
                        && amica(uk, p)
                        && data.getMap().getNeighbors(p, Matches.territoryIsWater()).stream()
                            .anyMatch(
                                z ->
                                    z.anyUnitsMatch(
                                        Matches.unitIsOwnedBy(us)
                                            .and(
                                                Matches
                                                    .unitIsSeaTransportButNotCombatSeaTransport()))))
            .toList();
    final List<Territory> ports = withTransports.isEmpty() ? all : withTransports;
    final Predicate<Territory> through =
        x -> amica(uk, x) && Matches.territoryIsPassableAndNotRestricted(uk).test(x);
    for (final String n : cfg.america()) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t == null || ports.contains(t)) {
        continue;
      }
      for (final Unit u : t.getMatches(terra(uk).and(Matches.unitHasMovementLeft()))) {
        Route best = null;
        for (final Territory p : ports) {
          if (!amica(uk, p)) {
            continue;
          }
          final Optional<Route> r = data.getMap().getRouteForUnits(t, p, through, List.of(u), uk);
          if (r.isPresent() && (best == null || r.get().numberOfSteps() < best.numberOfSteps())) {
            best = r.get();
          }
        }
        if (best == null) {
          continue;
        }
        final int steps = Math.min(best.numberOfSteps(), u.getMovementLeft().intValue());
        if (steps <= 0) {
          continue;
        }
        final Route go = new Route(t, best.getSteps().subList(0, steps).toArray(new Territory[0]));
        del.performMove(new MoveDescription(List.of(u), go));
      }
    }
    return new LinkedHashSet<>(inAmerica(data, uk));
  }

  // ======================================================================= register

  /** The row of the UK's turn: loaded, unloaded, held, in America, on board. */
  static void registraTurno(
      final GameState data,
      final GamePlayer uk,
      final int loaded,
      final int unloaded,
      final int held) {
    final JsonObject row = new JsonObject();
    row.addProperty("caricate_su_trasporti_usa", loaded);
    row.addProperty("scaricate", unloaded);
    row.addProperty("ferme_al_porto", held);
    row.addProperty("in_america", inAmerica(data, uk).size());
    row.addProperty("a_bordo_usa", aBordoAlleato(data, uk).size());
    LevePv1.registra(
        data,
        uk,
        LevePv1.TIPO_PONTE,
        "PV1_PONTE_REGNO_UNITO",
        "truppe britanniche in America: caricate sui trasporti USA, scaricate, ferme al porto",
        row);
  }
}
