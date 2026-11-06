package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.ProTuning;

/**
 * Pro+ numbers. Changes only:
 *
 * <ul>
 *   <li>attack thresholds: from the alliance's {@link RiskLevel} (defense, purchase, scramble keep
 *       the "Hard (AI)" thresholds);
 *   <li>victory cities: valued with the Hard Beta formula, also when the game is won by "Triggered
 *       Victory" (WW2 Europe 1940 and Global 1940 count victory cities through triggers, so Hard
 *       Beta's own condition never applies there).
 * </ul>
 */
public final class ProPlusTuning implements ProTuning {

  @Override
  public double attackWinPercentage(
      final GameState data, final GamePlayer player, final double winPercentage) {
    return ProPlusSettings.riskLevelFor(data, player).desiredWinPercentage(isLowLuck(data));
  }

  @Override
  public double attackMinWinPercentage(
      final GameState data, final GamePlayer player, final double minWinPercentage) {
    return ProPlusSettings.riskLevelFor(data, player).minimumWinPercentage(isLowLuck(data));
  }

  @Override
  public boolean valuesVictoryCities(final boolean beta) {
    return true;
  }

  @Override
  public boolean hasVictoryCityWinCondition(final GameProperties props) {
    return ProTuning.super.hasVictoryCityWinCondition(props)
        || Properties.getTriggeredVictory(props);
  }

  static boolean isLowLuck(final GameState data) {
    return Properties.getLowLuck(data.getProperties());
  }
}
