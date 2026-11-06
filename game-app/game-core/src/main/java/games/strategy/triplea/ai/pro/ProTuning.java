package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;

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
}
