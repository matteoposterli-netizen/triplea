package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.Territory;
import java.util.List;
import java.util.Map;

/**
 * Staging orders a variant of the Pro AI gives its non-combat move (Pro+ strategy plans, step 3b):
 * after the moves that defend threatened territories, the units left move towards the gathering
 * territories. Applied as a bonus to the value the non-combat move ranks destinations by.
 *
 * @param label where the orders come from, for logs and the diary (eg: "A Barbarossa, R1-3")
 * @param front the front the reserves move towards (text for the diary), may be empty
 * @param territories names of the gathering territories (land) and sea zones (fleet)
 * @param strength 1 = a gathering territory gets as much value as the best destination of the turn,
 *     half at distance 1, a third at distance 2...; 0 = no pull
 * @param weights pull of each gathering territory relative to the strongest (0..1; missing = 1): a
 *     destination gets the best {@code weight / (1 + distance)} among the gathering territories
 * @param air whether air units land towards the gathering territories too (among the territories
 *     where Hard would find them safe); otherwise air units land as Hard decides
 */
public record ProStagingOrders(
    String label,
    String front,
    List<String> territories,
    double strength,
    boolean air,
    Map<String, Double> weights) {

  /** Orders where every gathering territory pulls the same. */
  public ProStagingOrders(
      final String label,
      final String front,
      final List<String> territories,
      final double strength,
      final boolean air) {
    this(label, front, territories, strength, air, Map.of());
  }

  /** Weight of the pull of gathering territory {@code name} (0..1, 1 if not given). */
  public double weight(final String name) {
    return weights.getOrDefault(name, 1.0);
  }

  /** Farthest distance (in moves) from a gathering territory that still gets a bonus. */
  public static final int RANGE = 8;

  /**
   * What the orders did to the destination values.
   *
   * @param landScale value of the best land destination before the bonus (land bonus base)
   * @param seaScale value of the best sea destination before the bonus (sea bonus base)
   * @param distance distance of each destination from the nearest gathering territory (land
   *     territories by land, sea zones by sea), only those within {@link #RANGE}
   * @param bonus value added to each destination
   */
  public record Result(
      double landScale,
      double seaScale,
      Map<Territory, Integer> distance,
      Map<Territory, Double> bonus) {}

  /**
   * Where the air units landed with the pull (see {@link #air}).
   *
   * @param units air units moved (or staying), per destination name → type → count
   * @param closer units that land nearer to the gathering territories than they started
   * @param same units at the same distance
   * @param farther units farther away
   */
  public record AirResult(
      Map<String, Map<String, Integer>> units, int closer, int same, int farther) {}
}
