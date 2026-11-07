package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.ProAttackWorth;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.ProStagingOrders;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.pro.util.ProNationalObjectiveUtils;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.ai.proplus.strategia.Strategia;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

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
 *       risk level ({@link #keepsAttackNotWorthIt});
 *   <li>strategy (step 3a): with a strategy file for the map, the alliance's plan in force ({@link
 *       PianoAttivo}) sets the risk level per target (base level + the front's modifier) and its
 *       objectives get a priority bonus and replace the victory city / national objective rule.
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

  /** Cache of {@link #plan}: the orders are the same for one game state, player and round. */
  private @Nullable GameState planData;

  private @Nullable GamePlayer planPlayer;
  private int planRound = -1;
  private Optional<PianoAttivo> plan = Optional.empty();

  /** The general staff's orders for {@code player} now; empty without a strategy for the map. */
  public Optional<PianoAttivo> plan(final GameState data, final GamePlayer player) {
    final int round = data.getSequence().getRound();
    if (data != planData || player != planPlayer || round != planRound) {
      plan = PianoAttivo.of(data, player);
      planData = data;
      planPlayer = player;
      planRound = round;
    }
    return plan;
  }

  /** Forgets the cached orders (the plan was just fixed in the game). */
  void forgetPlan() {
    planData = null;
  }

  /** {@link #plan} of {@code proData}'s player, also when its tuning is not this class. */
  public static Optional<PianoAttivo> planOf(final ProData proData) {
    return proData.getTuning() instanceof ProPlusTuning tuning
        ? tuning.plan(proData.getData(), proData.getPlayer())
        : PianoAttivo.of(proData.getData(), proData.getPlayer());
  }

  /**
   * Risk level of an attack on {@code target}: the alliance's level moved by the plan's modifier
   * for the target's front; the alliance's level without a plan.
   */
  public static RiskLevel riskLevel(final ProData proData, final Territory target) {
    return planOf(proData)
        .map(p -> p.livelloEffettivo(target))
        .orElseGet(() -> ProPlusSettings.riskLevelFor(proData.getData(), proData.getPlayer()));
  }

  /**
   * Desired win% of an attack on {@code target}: the effective level's (base level + the front's
   * modifier), but for an objective of the plan in force never below the base level's. The front's
   * modifier decides whether to try an objective (minimum win%), not how few units to commit to it:
   * with Ovest +1 France is tried from Audace's minimum but planned to Equilibrato's desired win%.
   */
  @Override
  public double targetAttackWinPercentage(
      final ProData proData, final Territory target, final double attackWinPercentage) {
    return desiredWinPercentage(proData, target);
  }

  /** See {@link #targetAttackWinPercentage}. */
  public static double desiredWinPercentage(final ProData proData, final Territory target) {
    final boolean lowLuck = isLowLuck(proData.getData());
    final double desired = riskLevel(proData, target).desiredWinPercentage(lowLuck);
    return planOf(proData)
        .filter(p -> !ProUtils.isNeutralLand(target) && p.isObiettivo(target))
        .map(p -> Math.max(desired, p.getLivelloBase().desiredWinPercentage(lowLuck)))
        .orElse(desired);
  }

  @Override
  public double targetAttackMinWinPercentage(
      final ProData proData, final Territory target, final double attackMinWinPercentage) {
    return riskLevel(proData, target).minimumWinPercentage(isLowLuck(proData.getData()));
  }

  @Override
  public double attackWinPercentageCeiling(
      final ProData proData, final double attackWinPercentage) {
    final boolean lowLuck = isLowLuck(proData.getData());
    return Math.max(
        attackWinPercentage,
        planOf(proData).map(p -> p.maxDesiredWinPercentage(lowLuck)).orElse(attackWinPercentage));
  }

  /** The plan's objective weight for {@code target} (never for neutrals); 0 without a plan. */
  @Override
  public double attackPriorityPlanBonus(final ProData proData, final Territory target) {
    if (ProUtils.isNeutralLand(target)) {
      return 0;
    }
    return planOf(proData).map(p -> p.peso(target)).orElse(0.0);
  }

  /**
   * The plan's purchase orders for this nation and round (step 3b): the mix per category with the
   * unit types of each category from the strategy file, mandatory purchases, preferred placement
   * and the gathering territories (placement nearest to them). Empty without orders (plan N, a
   * nation or round the plan has none for, no strategy file).
   */
  @Override
  public Optional<ProPurchaseOrders> purchaseOrders(final ProData proData) {
    return planOf(proData)
        .flatMap(
            plan ->
                plan.getLogistica()
                    .filter(l -> !l.mix().isEmpty() || !l.obbligati().isEmpty())
                    .map(l -> purchaseOrders(plan, l)));
  }

  static ProPurchaseOrders purchaseOrders(final PianoAttivo plan, final Strategia.Logistica l) {
    final Map<String, List<String>> categories = plan.getStrategia().getCategorie();
    final List<ProPurchaseOrders.Category> mix = new ArrayList<>();
    l.mix()
        .forEach(
            (name, percent) ->
                mix.add(
                    new ProPurchaseOrders.Category(
                        name, percent, categories.getOrDefault(name, List.of()))));
    final List<ProPurchaseOrders.Mandatory> mandatory =
        l.obbligati().stream()
            .map(
                o ->
                    new ProPurchaseOrders.Mandatory(
                        o.categoria(),
                        categories.getOrDefault(o.categoria(), List.of()),
                        o.minimo()))
            .toList();
    return new ProPurchaseOrders(
        ordersLabel(plan, l), mix, mandatory, l.piazzamento(), plan.territoriAmmassamento(l));
  }

  /**
   * The plan's staging orders for this nation and round (step 3b): the gathering territories (or
   * the front's land territories) and the pull strength. Empty without orders or with strength 0.
   */
  @Override
  public Optional<ProStagingOrders> stagingOrders(final ProData proData) {
    return planOf(proData)
        .flatMap(
            plan ->
                plan.getLogistica()
                    .filter(l -> l.forza() > 0 && !plan.territoriAmmassamento(l).isEmpty())
                    .map(
                        l ->
                            new ProStagingOrders(
                                ordersLabel(plan, l),
                                Optional.ofNullable(l.fronteAmmassamento()).orElse(""),
                                plan.territoriAmmassamento(l),
                                l.forza())));
  }

  /** Eg: "A Barbarossa, Germans R1-3". */
  private static String ordersLabel(final PianoAttivo plan, final Strategia.Logistica l) {
    return plan.getPiano().label() + ", " + plan.getNazione() + " " + l.label();
  }

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
   * by one of two rules of the {@linkplain #riskLevel risk level of the target} (never at {@link
   * RiskLevel#PRUDENTE}; never for neutrals, nor below the desired win%):
   *
   * <ul>
   *   <li>{@value #KEPT_BY_RISK}: the attack {@linkplain #takesSomething takes something} and its
   *       value, with the level's counter-attack weight in place of Hard's 2/3, is at least minus
   *       the level's tolerance;
   *   <li>{@value #KEPT_BY_OBJECTIVE}: the target is an {@linkplain #isObjectiveTarget objective
   *       target}, the attack wins at least {@link RiskLevel#objectiveRuleMinWin} and the TUV
   *       expected to be lost ({@link ProAttackWorth#expectedTuvCost}) is at most the level's
   *       limit.
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
    final RiskLevel level = riskLevel(proData, territory);
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
    final RiskLevel level = riskLevel(proData, worth.territory());
    final boolean lowLuck = isLowLuck(data);
    final double riskValue = worth.attackValue(level.getCounterAttackWeight());
    final boolean objectiveTarget = isObjectiveTarget(proData, worth.territory());
    final double objectiveMinWin = level.objectiveRuleMinWin(lowLuck);
    String keptBy = null;
    final double desired = desiredWinPercentage(proData, worth.territory());
    if (level.keepsAttacksNotWorthIt()
        && !worth.neutral()
        && !worth.capturesTooRarely()
        && worth.winPercentage() >= desired) {
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
        desired,
        riskValue,
        objectiveTarget,
        planOf(proData).isPresent(),
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
   * Target of the objective rule. With a strategy plan: an objective of the plan's current phase
   * for this nation (land or, for a fleet objective, a sea zone; never a neutral). Without a plan
   * (no strategy file for the map, step 2d rule): enemy land with a victory city, or with national
   * objectives at stake for my side, that gives the enemy production or a victory city.
   */
  static boolean isObjectiveTarget(final ProData proData, final Territory t) {
    if (ProUtils.isNeutralLand(t)) {
      return false;
    }
    final Optional<PianoAttivo> plan = planOf(proData);
    if (plan.isPresent()) {
      return plan.get().isObiettivo(t);
    }
    if (t.isWater()) {
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
   * @param planRule whether the objective rule is the plan's ({@code false}: step 2d victory city /
   *     national objective rule, no strategy for the map)
   * @param objectiveMinWin win% the objective rule needs
   * @param keptBy {@value #KEPT_BY_RISK}, {@value #KEPT_BY_OBJECTIVE} or empty (dropped)
   */
  public record UnworthAttackCheck(
      RiskLevel level,
      double desired,
      double riskValue,
      boolean objectiveTarget,
      boolean planRule,
      double objectiveMinWin,
      Optional<String> keptBy) {}

  static boolean isLowLuck(final GameState data) {
    return Properties.getLowLuck(data.getProperties());
  }
}
