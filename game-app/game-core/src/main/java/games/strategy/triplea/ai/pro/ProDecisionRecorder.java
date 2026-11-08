package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Observer of the Pro AI's decisions (the Pro+ decision diary). Every method is a no-op by default
 * and {@link #NONE} is what every {@link ProData} starts with.
 *
 * <p>Contract for implementations: only read what they get, never change it, never run a battle
 * calculation or anything else that changes an AI decision, and never throw. The Pro classes call
 * these methods as plain logging lines.
 */
public interface ProDecisionRecorder {
  ProDecisionRecorder NONE = new ProDecisionRecorder() {};

  /** An AI phase of the real game starts ({@code phase}: purchase, combat move, ...). */
  default void phaseStart(GameState data, GamePlayer player, String phase) {}

  /** An AI phase of the real game ended after {@code millis}. */
  default void phaseEnd(GameState data, GamePlayer player, String phase, long millis) {}

  /** The combat move planning starts (also when it runs in the purchase simulation). */
  default void combatPlanStart(ProData proData) {}

  /**
   * Every territory the combat move could attack ({@code all}) and those left after dropping the
   * ones that can't be conquered with all possible attackers ({@code kept}).
   */
  default void attackCandidates(
      ProData proData, Collection<ProTerritory> all, Collection<ProTerritory> kept) {}

  /** The attack on {@code patd} is dropped for {@code reason} (a reason code). */
  default void attackDropped(ProData proData, ProTerritory patd, String reason) {}

  /**
   * The combat move ranked {@code patd} with priority {@code patd.getValue()}, of which {@code
   * victoryCityPart} comes from its victory cities (0 unless the tuning weighs them) and {@code
   * planPart} from the strategy plan's objectives (0 unless the tuning follows a plan).
   */
  default void attackPriority(
      ProData proData, ProTerritory patd, double victoryCityPart, double planPart) {}

  /**
   * The combat move checked whether the attack on {@code patd} is worth it with the units assigned
   * ({@code worth}); {@code keptBy} is the tuning's reason to keep an attack Hard would drop, null
   * otherwise. Called for every planned attack each time the check runs.
   */
  default void attackWorth(
      ProData proData, ProTerritory patd, ProAttackWorth worth, @Nullable String keptBy) {}

  /**
   * A late check of the combat move took every unit off the attack on {@code patd} (the territory
   * stays in the list, so the attack just isn't made): {@code reason} is a code; for
   * "transports_exposed", {@code enemyTuvSwing} is the TUV the enemy is expected to win against the
   * transports and {@code attackValue} the attack's TUV swing plus production (dropped when 0.75 ×
   * enemyTuvSwing is more), NaN otherwise.
   */
  default void attackUnitsCleared(
      ProData proData,
      ProTerritory patd,
      String reason,
      double enemyTuvSwing,
      double attackValue) {}

  /**
   * A tuning's own check of the landing's transports on {@code patd} ({@link
   * ProTuning#checksLandingTransports}): its numbers as text.
   */
  default void landingTransports(ProData proData, ProTerritory patd, String detail) {}

  /** The attacks still planned after the combat move step {@code stage}. */
  default void attackStage(ProData proData, String stage, Collection<ProTerritory> planned) {}

  /** The final attack plan: territories with the units assigned to attack them. */
  default void attackPlan(ProData proData, Map<Territory, ProTerritory> attackMap) {}

  /**
   * The purchase step {@code step} ended; units bought so far are in the place territories of
   * {@code purchaseTerritories} and of {@code factoryTerritories} (new factories, may be empty).
   */
  default void purchaseStep(
      ProData proData,
      String step,
      Map<Territory, ProPurchaseTerritory> purchaseTerritories,
      Map<Territory, ProPurchaseTerritory> factoryTerritories) {}

  /**
   * The purchase followed a strategy plan's {@code orders} (called after the plan's purchases,
   * before the rest of the Pro purchase).
   */
  default void purchaseOrders(
      ProData proData, ProPurchaseOrders orders, ProPurchaseOrders.Result result) {}

  /**
   * The non-combat move followed a strategy plan's staging {@code orders}: {@code moveMap} holds
   * the units assigned to each destination (also those that stay), just before the moves are made.
   */
  default void staging(
      ProData proData,
      ProStagingOrders orders,
      ProStagingOrders.Result result,
      Map<Territory, ProTerritory> moveMap) {}

  /**
   * The defense of threatened land in the purchase was limited by a plan's minimum share ({@code
   * share}), before the plan's purchases.
   */
  default void purchaseDefenseShare(
      ProData proData, ProPurchaseOrders orders, ProPurchaseOrders.DefenseShare share) {}

  /** The air units of the non-combat move landed with a plan's staging pull. */
  default void airStaging(
      ProData proData, ProStagingOrders orders, ProStagingOrders.AirResult result) {}

  /**
   * The capital guard of the non-combat move checked the own capitals ({@code results}, one per
   * capital), just before the moves are made.
   */
  default void capitalGuard(ProData proData, List<ProCapitalGuard.Result> results) {}

  /** The politics phase followed a plan's war orders for {@code enemy}. */
  default void warDecision(ProData proData, GamePlayer enemy, ProWarDecision decision) {}

  /**
   * The non-combat move kept the minimum garrisons of a plan ({@code results}, one per territory),
   * just before the moves are made.
   */
  default void minimumGarrisons(ProData proData, List<ProCapitalGuard.GarrisonResult> results) {}

  /**
   * Pro+ (step 3c): the purchase defended {@code defended} against real threats; the defense of
   * Hard would have spent about {@code pusSaved} more PUs on {@code territoriesSaved} territories
   * that only players unable to attack before the next turn threaten.
   */
  default void realThreatDefense(
      ProData proData, List<ProThreat> defended, int pusSaved, int territoriesSaved) {}

  /**
   * Pro+ (step 3c): {@code count} minimum garrisons of a plan were not kept: no enemy can land
   * there before the next turn.
   */
  default void garrisonsNotNeeded(ProData proData, int count) {}
}
