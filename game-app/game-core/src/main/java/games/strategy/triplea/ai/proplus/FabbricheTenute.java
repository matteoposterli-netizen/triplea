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
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntToDoubleFunction;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step P13, lever {@link StrategaInterruttori#FABBRICHE_TENUTE} (Axis, the data's Germany, on top
 * of the siege): in the non-combat move, after Hard's attacks and before Hard's non-combat move,
 * every captured Russian factory gets the minimum garrison of land units with which the best enemy
 * counterattack of the next turn (Hard's estimate, {@link LeveItalia#minaccia}) wins under {@code
 * soglia_contrattacco_pct}: the German land units already there first, then the units the factory
 * will produce this turn (the siege places there), then the nearest German land units that did not
 * move this turn (never from the West). Units beyond the minimum stay free; no threat = no
 * garrison. The allied (Italian) units there count as defense and are never moved. Off: nothing.
 */
public final class FabbricheTenute {
  private FabbricheTenute() {}

  public static boolean attivo(@Nullable final GameState data, @Nullable final GamePlayer player) {
    return LeveP13.attiva(
            data,
            player,
            StrategaInterruttori.FABBRICHE_TENUTE,
            LeveP13.Config.get().fabbricheNazione())
        && Assedio.attivo(data, player);
  }

  /** The minimum garrison: {count among the candidates, missing, win before, win after}. */
  record Guarnigione(int richiesta, int mancanti, double prima, double dopo, boolean minaccia) {}

  /**
   * The smallest prefix of {@code candidates} (then hypothetical units of the garrison type) added
   * to {@code fixed} that brings the enemy's win under the threshold.
   */
  static Guarnigione guarnigione(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory t,
      final List<Unit> fixed,
      final List<Unit> candidates) {
    final LeveP13.Config cfg = LeveP13.Config.get();
    final LeveItalia.Minaccia m = LeveItalia.minaccia(proData, calc, t);
    if (m.vuota()) {
      return new Guarnigione(0, 0, 0, 0, false);
    }
    final UnitType type =
        proData.getData().getUnitTypeList().getUnitType(cfg.tipoGuarnigione()).orElse(null);
    final List<Unit> pool = new ArrayList<>(candidates);
    if (type != null) {
      pool.addAll(type.createTemp(cfg.maxGuarnigione(), proData.getPlayer()));
    }
    final int max = pool.size();
    final Map<Integer, Double> memo = new HashMap<>();
    final IntToDoubleFunction win =
        k ->
            memo.computeIfAbsent(
                k,
                kk -> {
                  final List<Unit> def = new ArrayList<>(fixed);
                  def.addAll(pool.subList(0, Math.min(kk, max)));
                  return LeveItalia.vittoria(proData, calc, t, m, def);
                });
    final int k = Math.min(LeveItalia.minimo(win, max, cfg.sogliaContrattacco()), max);
    final int fromCandidates = Math.min(k, candidates.size());
    return new Guarnigione(
        fromCandidates, k - fromCandidates, win.applyAsDouble(0), win.applyAsDouble(k), true);
  }

  /**
   * The garrisons (real board, Germany's non-combat move before Hard's). Returns the units Hard
   * must leave where they are. Never throws.
   */
  public static Set<Unit> nonCombattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate del,
      final Set<Unit> alreadyHeld) {
    final Set<Unit> held = new LinkedHashSet<>();
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    try {
      final List<Territory> factories = Assedio.fabbricheCatturate(data, player);
      if (factories.isEmpty()) {
        return held;
      }
      final LeveP13.Config cfg = LeveP13.Config.get();
      final Set<Unit> used = new LinkedHashSet<>(alreadyHeld);
      // the land units to place this turn (the siege places them in the captured factories)
      final List<Unit> toPlace =
          new ArrayList<>(
              player
                  .getUnitCollection()
                  .getMatches(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure())));
      toPlace.sort(perDifesa(proData));
      final JsonArray rows = new JsonArray();
      for (final Territory t : factories) {
        final List<Unit> fixed = new ArrayList<>();
        final List<Unit> inside = new ArrayList<>();
        for (final Unit u : t.getUnits()) {
          if (!Matches.isUnitAllied(player).test(u) || Matches.unitIsInfrastructure().test(u)) {
            continue;
          }
          if (u.isOwnedBy(player) && Matches.unitIsLand().test(u) && !used.contains(u)) {
            inside.add(u);
          } else if (!u.isOwnedBy(player)) {
            fixed.add(u);
          }
        }
        inside.sort(perDifesa(proData));
        final int capacity = Math.min(Assedio.capacita(t), toPlace.size());
        final List<Unit> placed = new ArrayList<>(toPlace.subList(0, Math.max(0, capacity)));
        final List<Unit> near = vicine(data, player, t, used, cfg.ovest());
        final List<Unit> candidates = new ArrayList<>(inside);
        candidates.addAll(placed);
        candidates.addAll(near);
        final Guarnigione g = guarnigione(proData, calc, t, fixed, candidates);
        final JsonObject r = new JsonObject();
        r.addProperty("fabbrica", t.getName());
        r.addProperty("minaccia", g.minaccia());
        r.addProperty("vittoria_prima", LeveP13.r1(g.prima()));
        r.addProperty("vittoria_dopo", LeveP13.r1(g.dopo()));
        r.addProperty("richiesta", g.richiesta() + g.mancanti());
        r.addProperty("presenti", inside.size());
        r.addProperty("alleati_presenti", fixed.size());
        // present units first, then the placement, then the moves
        int need = g.richiesta();
        int keptInside = 0;
        for (int i = 0; i < Math.min(need, inside.size()); i++) {
          held.add(inside.get(i));
          used.add(inside.get(i));
          keptInside++;
        }
        need -= keptInside;
        final int fromPlacement = Math.min(need, placed.size());
        for (int i = 0; i < fromPlacement; i++) {
          toPlace.remove(placed.get(i));
        }
        need -= fromPlacement;
        int moved = 0;
        final Map<Unit, Territory> where = posizioni(data, near);
        for (final Unit u : near) {
          if (need <= 0) {
            break;
          }
          final Territory from = where.get(u);
          final Optional<Route> route = rottaTerra(data, player, from, t, u);
          if (route.isPresent()
              && route.get().numberOfSteps() <= u.getMovementLeft().intValue()
              && del.performMove(new MoveDescription(List.of(u), route.get())).isEmpty()) {
            held.add(u);
            used.add(u);
            moved++;
            need--;
          }
        }
        r.addProperty("tenute_dentro", keptInside);
        r.addProperty("da_piazzare", fromPlacement);
        r.addProperty("spostate", moved);
        r.addProperty("mancanti", Math.max(0, need) + g.mancanti());
        r.addProperty("guarnigione_presente", keptInside + moved);
        rows.add(r);
      }
      final JsonObject row = new JsonObject();
      row.add("fabbriche", rows);
      row.addProperty("fase", "non_combattimento");
      LeveP13.registra(
          data,
          player,
          LeveP13.TIPO_FABBRICHE,
          "P13_FABBRICA",
          "guarnigione minima delle fabbriche russe catturate",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P13, fabbriche: " + e);
    }
    return held;
  }

  /** After the placement: the German land units really in each captured factory. Never throws. */
  public static void dopoPiazzamento(final GameState data, final GamePlayer player) {
    try {
      final List<Territory> factories = Assedio.fabbricheCatturate(data, player);
      if (factories.isEmpty()) {
        return;
      }
      final JsonArray rows = new JsonArray();
      for (final Territory t : factories) {
        final JsonObject r = new JsonObject();
        r.addProperty("fabbrica", t.getName());
        r.addProperty(
            "terra_propria",
            t.getMatches(
                    Matches.unitIsOwnedBy(player)
                        .and(Matches.unitIsLand())
                        .and(Matches.unitIsNotInfrastructure()))
                .size());
        r.addProperty(
            "terra_alleata",
            t.getMatches(
                    Matches.isUnitAllied(player)
                        .and(Matches.unitIsOwnedBy(player).negate())
                        .and(Matches.unitIsLand())
                        .and(Matches.unitIsNotInfrastructure()))
                .size());
        rows.add(r);
      }
      final JsonObject row = new JsonObject();
      row.add("fabbriche", rows);
      row.addProperty("fase", "dopo_piazzamento");
      LeveP13.registra(
          data,
          player,
          LeveP13.TIPO_FABBRICHE,
          "P13_FABBRICA",
          "unita' nelle fabbriche catturate dopo il piazzamento",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P13, fabbriche (dopo il piazzamento): " + e);
    }
  }

  /**
   * Own land units that did not move this turn, outside the West, reaching {@code t}; nearest
   * first.
   */
  private static List<Unit> vicine(
      final GameState data,
      final GamePlayer player,
      final Territory t,
      final Set<Unit> excluded,
      final List<String> west) {
    final List<Unit> out = new ArrayList<>();
    final Map<Unit, Integer> steps = new HashMap<>();
    for (final Territory n : data.getMap().getNeighbors(t, 3)) {
      if (n.isWater() || n.equals(t) || west.contains(n.getName()) || !alleato(player, n)) {
        continue;
      }
      for (final Unit u :
          n.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsLand())
                  .and(Matches.unitIsNotInfrastructure())
                  .and(Matches.unitHasNotMoved())
                  .and(Matches.unitHasMovementLeft())
                  .and(Matches.unitIsBeingTransported().negate()))) {
        if (excluded.contains(u)) {
          continue;
        }
        final Optional<Route> r = rottaTerra(data, player, n, t, u);
        if (r.isPresent() && r.get().numberOfSteps() <= u.getMovementLeft().intValue()) {
          out.add(u);
          steps.put(u, r.get().numberOfSteps());
        }
      }
    }
    out.sort(
        Comparator.comparingInt((Unit u) -> steps.get(u))
            .thenComparing(perDifesa(null))
            .thenComparing(u -> u.getId().toString()));
    return out;
  }

  private static boolean alleato(final GamePlayer player, final Territory t) {
    return !t.getOwner().isNull() && (t.isOwnedBy(player) || player.isAllied(t.getOwner()));
  }

  private static Optional<Route> rottaTerra(
      final GameState data,
      final GamePlayer player,
      final Territory from,
      final Territory to,
      final Unit u) {
    if (from == null || from.equals(to)) {
      return Optional.empty();
    }
    final Predicate<Territory> ok =
        x ->
            !x.isWater()
                && alleato(player, x)
                && Matches.territoryIsPassableAndNotRestricted(player).test(x);
    return data.getMap().getRouteForUnits(from, to, ok, List.of(u), player);
  }

  private static Map<Unit, Territory> posizioni(final GameState data, final List<Unit> units) {
    final Set<Unit> wanted = new LinkedHashSet<>(units);
    final Map<Unit, Territory> out = new LinkedHashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnits().stream().filter(wanted::contains).forEach(u -> out.put(u, t));
    }
    return out;
  }

  /** Defensive strength first (fewest units), then the cheapest. */
  private static Comparator<Unit> perDifesa(@Nullable final ProData proData) {
    return Comparator.comparingInt(
            (Unit u) ->
                -u.getUnitAttachment().getDefense(u.getOwner())
                    * Math.max(1, u.getUnitAttachment().getDefenseRolls(u.getOwner())))
        .thenComparingInt(u -> proData == null ? 0 : proData.getUnitValueMap().getInt(u.getType()));
  }
}
