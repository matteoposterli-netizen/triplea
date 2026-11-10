package games.strategy.triplea.ai.pro;

import java.util.List;

/**
 * A computed defense plan a variant of the Pro AI gives its purchase (Pro+, step C2, only for the
 * "Pro+ Stratega LLM" player): it replaces the purchase's "defend land" part. The planned defensive
 * units are bought first, then at most {@code strategyBudget} PUs go to the plan's purchase orders
 * (the strategist's reserves), then the Pro AI spends the rest with its own logic.
 *
 * @param label where the plan comes from, for logs and the diary
 * @param purchases units to buy and place in a territory (by name)
 * @param strategyBudget PUs the purchase orders may use after the defensive purchases
 */
public record ProDefensePlan(String label, List<Purchase> purchases, int strategyBudget) {

  /** {@code count} units of {@code unitType} placed in {@code territory}. */
  public record Purchase(String territory, String unitType, int count) {}
}
