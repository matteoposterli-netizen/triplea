package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lombok.experimental.UtilityClass;

/**
 * Applies a strategy plan's {@link ProStagingOrders} to the non-combat move: each destination gets
 * a bonus on the value the move ranks destinations by, {@code strength × scale / (1 + distance)},
 * where distance is to the nearest gathering territory (land territories by land, sea zones by sea,
 * up to {@link ProStagingOrders#RANGE}) and scale the best destination value of the turn (land or
 * sea). Defense comes first: the moves that defend threatened territories are planned before and
 * don't read these values. Only used when the tuning gives orders ({@link
 * ProTuning#stagingOrders}); Hard never gets here.
 */
@UtilityClass
class ProStaging {

  /**
   * Adds the bonus to {@code landValues} (land destinations) and {@code seaValues} (sea
   * destinations) for the territories in {@code destinations}.
   */
  static ProStagingOrders.Result apply(
      final ProData proData,
      final ProStagingOrders orders,
      final Collection<Territory> destinations,
      final Map<Territory, Double> landValues,
      final Map<Territory, Double> seaValues) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final List<Territory> land = new ArrayList<>();
    final List<Territory> sea = new ArrayList<>();
    for (final String name : orders.territories()) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t != null) {
        (t.isWater() ? sea : land).add(t);
      }
    }
    final double landScale = scale(destinations, landValues, false);
    final double seaScale = scale(destinations, seaValues, true);
    final Map<Territory, Integer> distances = new HashMap<>();
    final Map<Territory, Double> bonuses = new HashMap<>();
    final Predicate<Territory> landPath = ProMatches.territoryCanMoveLandUnits(player, false);
    final Predicate<Territory> seaPath = ProMatches.territoryCanMoveSeaUnits(player, true);
    for (final Territory t : destinations) {
      final boolean water = t.isWater();
      final Map<Territory, Double> values = water ? seaValues : landValues;
      if (!values.containsKey(t)) {
        continue;
      }
      final int distance = distance(data, t, water ? sea : land, water ? seaPath : landPath);
      if (distance < 0 || distance > ProStagingOrders.RANGE) {
        continue;
      }
      final double bonus = orders.strength() * (water ? seaScale : landScale) / (1.0 + distance);
      values.put(t, values.get(t) + bonus);
      distances.put(t, distance);
      bonuses.put(t, bonus);
    }
    ProLogger.info(
        "[Pro+] Staging "
            + orders.label()
            + " towards "
            + orders.territories()
            + ", land scale "
            + landScale
            + ", sea scale "
            + seaScale
            + ", destinations with bonus "
            + bonuses.size());
    return new ProStagingOrders.Result(landScale, seaScale, distances, bonuses);
  }

  /** Best value among the destinations of one kind; at least 1 so the pull never vanishes. */
  private static double scale(
      final Collection<Territory> destinations,
      final Map<Territory, Double> values,
      final boolean water) {
    return Math.max(
        1,
        destinations.stream()
            .filter(t -> t.isWater() == water)
            .map(values::get)
            .filter(v -> v != null && v > 0)
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(1));
  }

  /** Shortest distance to one of {@code targets} through {@code path}; -1 if none is reachable. */
  static int distance(
      final GameData data,
      final Territory from,
      final List<Territory> targets,
      final Predicate<Territory> path) {
    int best = -1;
    for (final Territory target : targets) {
      final int d = data.getMap().getDistance(from, target, path);
      if (d >= 0 && (best < 0 || d < best)) {
        best = d;
      }
    }
    return best;
  }
}
