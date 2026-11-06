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
 *       Beta's own condition never applies there);
 *   <li>attack priority: a victory city adds {@link #ATTACK_PRIORITY_VICTORY_CITY_WEIGHT} to the
 *       score the combat move ranks its targets by.
 * </ul>
 */
public final class ProPlusTuning implements ProTuning {
  /**
   * Priority points per victory city of a target that can be held (a quarter if it can't), added
   * where the Pro AI adds TUV swing and production value, so the capital (x5) and
   * next-to-my-capital (x3) multipliers apply to it too. A holdable land target with production P
   * scores 3·P (6·P if empty, x2 with a factory), so 10 ≈ 3 PUs of production: it puts a victory
   * city ahead of a non-victory-city target of similar production and TUV swing, but not ahead of a
   * target worth clearly more (Western Germany, 5 PUs + factory: 30) nor of an enemy capital (x5:
   * 60 or more).
   */
  static final double ATTACK_PRIORITY_VICTORY_CITY_WEIGHT = 10;

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

  @Override
  public double attackPriorityVictoryCityWeight() {
    return ATTACK_PRIORITY_VICTORY_CITY_WEIGHT;
  }

  static boolean isLowLuck(final GameState data) {
    return Properties.getLowLuck(data.getProperties());
  }
}
