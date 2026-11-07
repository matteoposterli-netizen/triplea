package games.strategy.triplea.ai.pro;

import java.util.List;
import java.util.Map;

/**
 * Purchase orders a variant of the Pro AI gives its purchase (Pro+ strategy plans, step 3b). They
 * never replace urgent defense: the Pro purchase first buys the defenders of the threatened land
 * territories, then spends the PUs left following {@link #mix}, then goes on as usual with whatever
 * is left (rounding, shares that sum to less than 100%).
 *
 * @param label where the orders come from, for logs and the diary (eg: "A Barbarossa, R1-3")
 * @param mix target share of the PUs left after urgent defense, per category, in order
 * @param mandatory units to buy before the mix, whatever it says
 * @param preferredPlacement territory names (factories or sea zones) to place in first, in order
 * @param stagingTerritories territory names the units are meant to gather at: among the other place
 *     territories, the nearest to them is used first
 * @param minimumShare percent of the turn's PUs reserved for the plan (mandatory purchases and
 *     mix): the defense of threatened land may spend at most the rest, unless a factory or the
 *     capital risks being taken ({@link ProTuning#capitalGuardThreshold}); 0 = no reserve
 */
public record ProPurchaseOrders(
    String label,
    List<Category> mix,
    List<Mandatory> mandatory,
    List<String> preferredPlacement,
    List<String> stagingTerritories,
    double minimumShare) {

  /**
   * A category of the mix: {@code percent} of the budget, spent on {@code unitTypes} in rotation (a
   * name may repeat to buy it more often).
   */
  public record Category(String name, double percent, List<String> unitTypes) {}

  /** At least {@code minimum} units of category {@code category} (a name in {@code unitTypes}). */
  public record Mandatory(String category, List<String> unitTypes, int minimum) {}

  /**
   * What the purchase did with the orders.
   *
   * @param budget PUs left after urgent defense (the base of the mix)
   * @param targets PUs per category the mix asks for
   * @param spent PUs per category spent by the orders (mandatory purchases included)
   * @param units units bought by the orders per category (unit type → count)
   * @param placements units bought by the orders per place territory (territory → type → count)
   * @param exposed sea zones the orders placed ships in although the enemy can attack them with
   *     more than will defend them (none was safe): zone → TUV beyond the defense
   * @param mandatoryMissing mandatory purchases not made, with the reason (Italian text)
   * @param left PUs left for the rest of the Pro purchase
   */
  public record Result(
      int budget,
      Map<String, Double> targets,
      Map<String, Integer> spent,
      Map<String, Map<String, Integer>> units,
      Map<String, Map<String, Integer>> placements,
      Map<String, Double> exposed,
      List<String> mandatoryMissing,
      int left) {}

  /**
   * How the defense of threatened land was limited by {@link #minimumShare}.
   *
   * @param pus PUs at the start of the purchase
   * @param minimumShare percent reserved for the plan
   * @param defenseCap PUs the defense may spend on territories not at risk of being taken
   * @param threshold enemy win% above which a factory or the capital is at risk
   * @param critical territories at risk (name → enemy win% before the purchase), defended first
   *     without limit
   * @param spentCritical PUs spent defending them
   * @param limited other threatened territories (name → enemy win% before the purchase), defended
   *     within the cap
   * @param spentLimited PUs spent defending them
   * @param pusLeft PUs left for the plan after the defense
   */
  public record DefenseShare(
      int pus,
      double minimumShare,
      int defenseCap,
      double threshold,
      Map<String, Double> critical,
      int spentCritical,
      Map<String, Double> limited,
      int spentLimited,
      int pusLeft) {}
}
