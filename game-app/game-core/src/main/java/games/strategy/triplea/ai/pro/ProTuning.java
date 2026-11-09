package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProOtherMoveOptions;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BiPredicate;

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

  /**
   * Whether an amphibious attack is dropped when enemy air can scramble into its unload zone and
   * sink the transports with their cargo. Only Hard Beta by default.
   */
  default boolean checksLandingScramble(final boolean beta) {
    return beta;
  }

  /**
   * Whether a strafing attack (to weaken the defenders for an ally, see {@code
   * ProTerritoryManager}) with units landing from transports is dropped at {@code winPercentage}
   * with the units assigned: landed units cannot retreat, so they fight to the end. Never by
   * default: Hard and Hard Beta keep every strafe.
   */
  default boolean dropsAmphibiousStrafe(
      final ProData proData, final Territory target, final double winPercentage) {
    return false;
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
   * A tuning's own check of a planned landing's transports, in place of "Hard (AI)"'s "transports
   * exposed" rule (and of {@link #keepsLandingWithExposedTransports}): {@code unloadZones} are the
   * sea zones the transports and bombarding ships end their move in, with those units; {@code
   * moving} every unit the combat move uses; {@code battle} the landing's battle with the units
   * assigned; {@code conquestValue} what taking the territory is worth; {@code lostToScramble}
   * whether enemy air scrambling into a zone would beat the units there. Empty by default: Hard's
   * rule.
   */
  default Optional<ProLandingTransports> checksLandingTransports(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritory landing,
      final Map<Territory, List<Unit>> unloadZones,
      final ProOtherMoveOptions enemyAttackOptions,
      final Collection<Unit> moving,
      final ProBattleResult battle,
      final double conquestValue,
      final BiPredicate<Territory, Collection<Unit>> lostToScramble) {
    return Optional.empty();
  }

  /**
   * The sea zone a tuning chose for {@code transport} to unload from onto {@code target} in the
   * combat move ({@link ProLandingTransports#unloadZones}), if any. Empty by default: Hard's route.
   */
  default Optional<Territory> unloadZone(
      final ProData proData, final Unit transport, final Territory target) {
    return Optional.empty();
  }

  /**
   * Whether the combat move keeps a landing that "Hard (AI)" drops in its last check (called after
   * {@link #keepsAttackNotWorthIt} found no reason) because allies are expected to reinforce the
   * territory before the enemy's counter-attack. {@code holders} are the land units left after the
   * battle, {@code attack} the planned attack (its max enemy units are the counter-attack). Returns
   * why it is kept, or empty to drop it as Hard does. Empty by default.
   */
  /**
   * Whether the combat move drops a planned landing (not a strafe) in its last check, also one
   * "Hard (AI)" would keep. {@code holders} are the land units left after the battle, {@code
   * planned} the attacks still planned. Returns the drop reason (a short code), or empty to go on
   * as Hard does. Empty by default.
   */
  default Optional<String> dropsLanding(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritory attack,
      final ProAttackWorth worth,
      final Collection<Unit> holders,
      final Collection<ProTerritory> planned) {
    return Optional.empty();
  }

  /**
   * Whether the combat move drops a planned land attack that is neither a strafe nor a landing in
   * its last check, also one "Hard (AI)" would keep (Pro+, step 3d-2: while falling back, only
   * isolated enemy stacks are counter-attacked). Returns the drop reason (a short code), or empty
   * to go on as Hard does. Empty by default.
   */
  default Optional<String> dropsLandAttack(final ProData proData, final ProAttackWorth worth) {
    return Optional.empty();
  }

  default Optional<String> keepsBridgehead(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritory attack,
      final ProAttackWorth worth,
      final Collection<Unit> holders) {
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

  /**
   * Enemy win% above which the non-combat move keeps units in, or brings units back to, each own
   * capital ({@link ProCapitalGuard}), and above which a factory or capital may take more than the
   * defense share a plan leaves ({@link ProPurchaseOrders#minimumShare}). Empty by default: Hard
   * and Hard Beta have no such guard.
   */
  default OptionalDouble capitalGuardThreshold(final ProData proData) {
    return OptionalDouble.empty();
  }

  /**
   * Whether the politics phase declares war on {@code enemy} (step 3b-ter). {@link
   * ProWarDecision#HARD} by default: the Pro AI's own random rule.
   */
  default ProWarDecision warDecision(final ProData proData, final GamePlayer enemy) {
    return ProWarDecision.HARD;
  }

  /**
   * Own territories that keep at least this many land units after the non-combat move (territory
   * name → units). None by default.
   */
  default Map<String, Integer> minimumGarrisons(final ProData proData) {
    return Map.of();
  }

  /**
   * The players whose units can really attack {@code player}'s territories before its next turn,
   * each with why (Pro+, step 3c: at war, war the plan declares, war the map lets them declare).
   * Empty by default: the Pro AI counts every player not allied to {@code player}, as if at war.
   */
  default Optional<Map<GamePlayer, String>> realEnemies(
      final ProData proData, final GamePlayer player) {
    return Optional.empty();
  }

  /**
   * Whether a plan's minimum garrison of {@code territory} applies this turn: {@code
   * landingEnemies} are the enemy units that can land there before the next turn (from transports
   * in range). Always by default (step 3b-ter garrisons were fixed).
   */
  default boolean garrisonNeeded(
      final ProData proData, final Territory territory, final List<Unit> landingEnemies) {
    return true;
  }

  /**
   * Units the non-combat move must leave where they are: moved or kept in place before it by a
   * plan's amphibious operations (Pro+, step OPERAZIONI ANFIBIE: transports, their cargo and
   * escorts). They still count as defenders where they are. None by default.
   */
  default Set<Unit> heldUnits(final ProData proData) {
    return Set.of();
  }

  /**
   * Units the combat move must not use: already sent into a battle before it by a hand-written
   * joint operation (Pro+, step PROVA DI VALORE). None by default.
   */
  default Set<Unit> combatHeldUnits(final ProData proData) {
    return Set.of();
  }
}
