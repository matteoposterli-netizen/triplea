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
 */
public record ProStagingOrders(
    String label, String front, List<String> territories, double strength) {

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
}
