package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.Territory;

/**
 * The numbers behind the combat move's last check of a planned attack ("Determine if all attacks
 * are worth it" in {@code ProCombatMoveAi.determineUnitsToAttackWith}), with the units assigned to
 * it. "Hard (AI)" drops the attack when any of these holds (strafing attacks excepted):
 *
 * <ul>
 *   <li>{@link #capturesTooRarely}: win% below the minimum, or (normal dice, not Beta) on average
 *       no land unit survives to take the territory;
 *   <li>a neutral that can't be held ({@link #neutral} and not {@link #canHold});
 *   <li>{@link #attackValue} below 0 (for a neutral only when its units have other targets or the
 *       battle lasts 4+ rounds): {@code 1 + tuvSwing + territoryValue × win% - 2/3 ×
 *       counterTuvSwing}.
 * </ul>
 *
 * @param winPercentage win% with the units assigned
 * @param landUnitRemaining whether on average a land unit survives the battle
 * @param counterWinPercentage enemy win% in its strongest counter-attack, NaN if none is possible
 * @param tuvSwing expected TUV swing of the battle (positive: good for the attacker)
 * @param territoryValue what taking the territory is worth (production, factory, capital, ...)
 * @param counterTuvSwing enemy TUV swing in its counter-attack, 0 if it gains nothing
 * @param attackValue Hard's value of the attack, below 0 = not worth it
 * @param neutralRuleApplies for a neutral: whether a negative value counts (see above)
 */
public record ProAttackWorth(
    Territory territory,
    double winPercentage,
    boolean landUnitRemaining,
    boolean capturesTooRarely,
    boolean neutral,
    boolean canHold,
    double counterWinPercentage,
    double tuvSwing,
    double territoryValue,
    double counterTuvSwing,
    double attackValue,
    boolean neutralRuleApplies) {

  /** Weight of the enemy counter-attack TUV swing in "Hard (AI)"'s attack value. */
  public static final double HARD_COUNTER_ATTACK_WEIGHT = 2.0 / 3;

  /** Whether Hard counts the attack as not worth it because of its value. */
  public boolean negativeValue() {
    return attackValue < 0 && (!neutral || neutralRuleApplies);
  }

  /** Whether Hard drops the attack (strafing attacks are never dropped here). */
  public boolean hardDrops() {
    return capturesTooRarely || (neutral && !canHold) || negativeValue();
  }

  /** The attack value with {@code counterWeight} in place of Hard's 2/3. */
  public double attackValue(final double counterWeight) {
    return 1 + tuvSwing + territoryValue * winPercentage / 100 - counterTuvSwing * counterWeight;
  }

  /**
   * TUV expected to be lost overall: TUV lost to the enemy counter-attack minus the battle's own
   * TUV swing (no discount on the counter-attack). Below 0 = TUV gained.
   */
  public double expectedTuvCost() {
    return counterTuvSwing - tuvSwing;
  }
}
