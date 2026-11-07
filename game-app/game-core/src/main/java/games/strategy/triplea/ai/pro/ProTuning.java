package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import java.util.Optional;

/**
 * The Pro AI numbers that a variant of the AI may change. Every default returns exactly the value
 * the Pro AI ("Hard (AI)" and "Hard Beta (AI)") used before these numbers were moved here, so
 * {@link #DEFAULT} keeps the Pro AI's behavior.
 *
 * <p>Read by {@link ProData}, which every Pro AI module already receives.
 */
public interface ProTuning {
  ProTuning DEFAULT = new ProTuning() {};

  /** Win% an attack should reach, and a defense should deny the enemy (as 100 - this). */
  default double winPercentage(final boolean lowLuck) {
    return lowLuck ? 95 : 90;
  }

  /** Lowest win% worth considering (attacks), and the enemy win% a defense can accept. */
  default double minWinPercentage(final boolean lowLuck) {
    return lowLuck ? 75 : 65;
  }

  /**
   * Win% the combat move plans its attacks to reach. Defaults to {@code winPercentage}, the
   * threshold shared with defense, purchase and scramble.
   */
  default double attackWinPercentage(
      final GameState data, final GamePlayer player, final double winPercentage) {
    return winPercentage;
  }

  /** Lowest win% at which the combat move keeps an attack. Defaults to {@code minWinPercentage}. */
  default double attackMinWinPercentage(
      final GameState data, final GamePlayer player, final double minWinPercentage) {
    return minWinPercentage;
  }

  /**
   * Win% the combat move plans the attack on {@code target} to reach. Defaults to {@code
   * attackWinPercentage}, the same for every target ({@link #attackWinPercentage}).
   */
  default double targetAttackWinPercentage(
      final ProData proData, final Territory target, final double attackWinPercentage) {
    return attackWinPercentage;
  }

  /**
   * Lowest win% at which the combat move keeps the attack on {@code target}. Defaults to {@code
   * attackMinWinPercentage}, the same for every target ({@link #attackMinWinPercentage}).
   */
  default double targetAttackMinWinPercentage(
      final ProData proData, final Territory target, final double attackMinWinPercentage) {
    return attackMinWinPercentage;
  }

  /**
   * Highest {@link #targetAttackWinPercentage} of any target. Must be at least every value it can
   * return. Defaults to {@code attackWinPercentage}.
   */
  default double attackWinPercentageCeiling(
      final ProData proData, final double attackWinPercentage) {
    return attackWinPercentage;
  }

  /**
   * Points the strategy plan adds to the combat move's priority of {@code target} (before the
   * priority's multipliers). 0 by default: Hard and Hard Beta follow no plan.
   */
  default double attackPriorityPlanBonus(final ProData proData, final Territory target) {
    return 0;
  }

  /** Whether territories are worth more for their victory cities. Only Hard Beta by default. */
  default boolean valuesVictoryCities(final boolean beta) {
    return beta;
  }

  /** Whether the game has a win condition that counts victory cities. */
  default boolean hasVictoryCityWinCondition(final GameProperties props) {
    return Properties.getTotalVictory(props)
        || Properties.getHonorableSurrender(props)
        || Properties.getProjectionOfPower(props);
  }

  /** PUs a victory city is worth when victory cities are valued. */
  default double victoryCityValue() {
    return 5;
  }

  /**
   * Points a victory city adds to the score the combat move ranks its attack targets by, when
   * victory cities are valued ({@link #valuesVictoryCities}, {@link #hasVictoryCityWinCondition}).
   * 0 by default: Hard and Hard Beta rank targets without victory cities.
   */
  default double attackPriorityVictoryCityWeight() {
    return 0;
  }

  /**
   * Whether the combat move keeps a landing that "Hard (AI)" drops because its transports are
   * exposed: 0.75 × {@code enemyTuvSwing} (TUV the enemy is expected to win against the transports)
   * is more than {@code attackValue} (battle TUV swing plus production). Returns why it is kept, or
   * empty to drop it as Hard does. Empty by default.
   */
  default Optional<String> keepsLandingWithExposedTransports(
      final ProData proData,
      final Territory territory,
      final double winPercentage,
      final double enemyTuvSwing,
      final double attackValue) {
    return Optional.empty();
  }

  /**
   * Whether the combat move keeps an attack that "Hard (AI)" drops in its last check because its
   * value is negative with the units assigned ({@link ProAttackWorth#negativeValue}; called only
   * when that is the sole reason). Returns why it is kept (a short code), or empty to drop it as
   * Hard does. Empty by default.
   */
  default Optional<String> keepsAttackNotWorthIt(
      final ProData proData, final ProAttackWorth worth) {
    return Optional.empty();
  }

  /**
   * Purchase orders of a strategy plan for {@code proData}'s player now (mix per category,
   * mandatory purchases, placement). Empty by default: Hard and Hard Beta buy as always.
   */
  default Optional<ProPurchaseOrders> purchaseOrders(final ProData proData) {
    return Optional.empty();
  }

  /**
   * Staging orders of a strategy plan for {@code proData}'s player now (where the non-combat move
   * gathers the units not needed for defense). Empty by default: Hard and Hard Beta move as always.
   */
  default Optional<ProStagingOrders> stagingOrders(final ProData proData) {
    return Optional.empty();
  }
}
