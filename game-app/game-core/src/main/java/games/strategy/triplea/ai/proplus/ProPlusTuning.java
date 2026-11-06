package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.ProAttackWorth;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.pro.util.ProNationalObjectiveUtils;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.util.Optional;

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
 *       score the combat move ranks its targets by;
 *   <li>last check of the planned attacks: some attacks Hard drops as not worth it are kept, by the
 *       risk level ({@link #keepsAttackNotWorthIt}).
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

  /** {@link #keepsAttackNotWorthIt}: kept by the risk level's tolerance. */
  public static final String KEPT_BY_RISK = "risk";

  /** {@link #keepsAttackNotWorthIt}: kept by the victory city / national objective rule. */
  public static final String KEPT_BY_OBJECTIVE = "objective";

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

  /**
   * Keeps an attack "Hard (AI)" drops only because its value is negative with the units assigned,
   * by one of two rules of the alliance's {@link RiskLevel} (never at {@link RiskLevel#PRUDENTE};
   * never for neutrals, nor below the desired win%):
   *
   * <ul>
   *   <li>{@value #KEPT_BY_RISK}: the attack {@linkplain #takesSomething takes something} and its
   *       value, with the level's counter-attack weight in place of Hard's 2/3, is at least minus
   *       the level's tolerance;
   *   <li>{@value #KEPT_BY_OBJECTIVE}: the target is an enemy land territory with a victory city or
   *       a national objective at stake that gives the enemy production or a victory city, the
   *       attack wins at least {@link RiskLevel#objectiveRuleMinWin} and the TUV expected to be
   *       lost ({@link ProAttackWorth#expectedTuvCost}) is at most the level's limit.
   * </ul>
   */
  @Override
  public Optional<String> keepsAttackNotWorthIt(final ProData proData, final ProAttackWorth worth) {
    return check(proData, worth).keptBy();
  }

  /**
   * Keeps a landing Hard drops because its transports are exposed, by the objective rule only: the
   * target is an {@linkplain #isObjectiveTarget objective target}, the landing wins at least {@link
   * RiskLevel#objectiveRuleMinWin} and the TUV expected to be lost (0.75 × the enemy's TUV swing
   * against the transports minus the landing's value) is at most the level's limit. Without it a
   * victory city landing kept by {@link #keepsAttackNotWorthIt} would be dropped here anyway, and
   * the units it held wasted. Never at {@link RiskLevel#PRUDENTE}.
   */
  @Override
  public Optional<String> keepsLandingWithExposedTransports(
      final ProData proData,
      final Territory territory,
      final double winPercentage,
      final double enemyTuvSwing,
      final double attackValue) {
    final GameState data = proData.getData();
    final RiskLevel level = ProPlusSettings.riskLevelFor(data, proData.getPlayer());
    final boolean kept =
        level.keepsAttacksNotWorthIt()
            && winPercentage >= level.objectiveRuleMinWin(isLowLuck(data))
            && exposedTransportsCost(enemyTuvSwing, attackValue) <= level.getObjectiveCostLimit()
            && isObjectiveTarget(proData, territory);
    return kept ? Optional.of(KEPT_BY_OBJECTIVE) : Optional.empty();
  }

  /** TUV expected to be lost by a landing with exposed transports (Hard's own weights). */
  public static double exposedTransportsCost(final double enemyTuvSwing, final double attackValue) {
    return 0.75 * enemyTuvSwing - attackValue;
  }

  /** The numbers of {@link #keepsAttackNotWorthIt}, also for the decision diary. */
  public static UnworthAttackCheck check(final ProData proData, final ProAttackWorth worth) {
    final GameState data = proData.getData();
    final RiskLevel level = ProPlusSettings.riskLevelFor(data, proData.getPlayer());
    final boolean lowLuck = isLowLuck(data);
    final double riskValue = worth.attackValue(level.getCounterAttackWeight());
    final boolean objectiveTarget = isObjectiveTarget(proData, worth.territory());
    final double objectiveMinWin = level.objectiveRuleMinWin(lowLuck);
    String keptBy = null;
    if (level.keepsAttacksNotWorthIt()
        && !worth.neutral()
        && !worth.capturesTooRarely()
        && worth.winPercentage() >= level.desiredWinPercentage(lowLuck)) {
      if (takesSomething(worth) && riskValue >= -level.getValueTolerance()) {
        keptBy = KEPT_BY_RISK;
      } else if (objectiveTarget
          && worth.winPercentage() >= objectiveMinWin
          && worth.expectedTuvCost() <= level.getObjectiveCostLimit()) {
        keptBy = KEPT_BY_OBJECTIVE;
      }
    }
    return new UnworthAttackCheck(
        level,
        level.desiredWinPercentage(lowLuck),
        riskValue,
        objectiveTarget,
        objectiveMinWin,
        Optional.ofNullable(keptBy));
  }

  /**
   * Whether the attack gains something besides trading units: land worth taking (production,
   * factory, capital) or a sea battle won on TUV. A land target worth nothing is only a TUV trade,
   * and an attack Hard finds negative there stays dropped.
   */
  static boolean takesSomething(final ProAttackWorth worth) {
    return worth.territoryValue() > 0 || (worth.territory().isWater() && worth.tuvSwing() > 0);
  }

  /**
   * Enemy land with a victory city, or with national objectives at stake for my side, that gives
   * the enemy production or a victory city (taking it removes them from the enemy).
   */
  static boolean isObjectiveTarget(final ProData proData, final Territory t) {
    if (t.isWater() || ProUtils.isNeutralLand(t)) {
      return false;
    }
    final int victoryCities =
        TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0);
    return victoryCities > 0
        || (TerritoryAttachment.getProduction(t) > 0
            && ProNationalObjectiveUtils.stake(proData.getPlayer(), t) > 0);
  }

  /**
   * What {@link #check} found.
   *
   * @param desired the level's desired win% (both rules need it)
   * @param riskValue the attack value with the level's counter-attack weight
   * @param objectiveTarget whether the target counts for the objective rule
   * @param objectiveMinWin win% the objective rule needs
   * @param keptBy {@value #KEPT_BY_RISK}, {@value #KEPT_BY_OBJECTIVE} or empty (dropped)
   */
  public record UnworthAttackCheck(
      RiskLevel level,
      double desired,
      double riskValue,
      boolean objectiveTarget,
      double objectiveMinWin,
      Optional<String> keptBy) {}

  static boolean isLowLuck(final GameState data) {
    return Properties.getLowLuck(data.getProperties());
  }
}
