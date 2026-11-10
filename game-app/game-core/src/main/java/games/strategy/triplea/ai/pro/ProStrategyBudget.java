package games.strategy.triplea.ai.pro;

/**
 * Pro+ step C2-bis: how a purchase split its PUs. Hard buys its defense first ({@code defense}
 * PUs), the purchase orders then get at most {@code budget} PUs (the PUs left, capped at {@code
 * capPercent}% of the PUs at the start of the purchase), the Pro AI spends the rest.
 *
 * @param pus PUs at the start of the purchase
 * @param defense PUs Hard spent on the defense of threatened land
 * @param capPercent the cap of the orders in percent of {@code pus}
 * @param budget PUs the orders could use
 * @param reserved PUs kept out of the orders by the cap (0 = the cap did not bite)
 * @param orders PUs the orders spent
 * @param limited the cap kept PUs out and the orders left mandatory purchases unbought
 */
public record ProStrategyBudget(
    int pus,
    int defense,
    double capPercent,
    int budget,
    int reserved,
    int orders,
    boolean limited) {}
