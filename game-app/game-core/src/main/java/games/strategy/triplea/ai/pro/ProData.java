package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProPurchaseOptionMap;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;
import org.triplea.java.collections.CollectionUtils;
import org.triplea.java.collections.IntegerMap;

/** Pro AI data. */
@Getter
public final class ProData {
  /** Turns on behavior still under evaluation; only the "Hard Beta" AI sets it. */
  private final boolean beta;

  /** Numbers a Pro AI variant may change; {@link ProTuning#DEFAULT} for Hard and Hard Beta. */
  private final ProTuning tuning;

  // Default values
  private boolean isSimulation = false;
  private double winPercentage;
  private double minWinPercentage;

  /**
   * Win% thresholds the combat move plans attacks with. Equal to {@link #winPercentage} and {@link
   * #minWinPercentage} unless {@link #tuning} changes them.
   */
  private double attackWinPercentage;

  private double attackMinWinPercentage;
  private @Nullable Territory myCapital = null;
  private List<Territory> myUnitTerritories = new ArrayList<>();
  private Map<Unit, Territory> unitTerritoryMap = new HashMap<>();
  private IntegerMap<UnitType> unitValueMap = new IntegerMap<>();
  private @Nullable ProPurchaseOptionMap purchaseOptions = null;
  // If we purchased units this turn that consume other units, these are the units selected to be
  // consumed. These are already located at the factory locations, so should not be moved. In the
  // future, we could add logic about moving such units to factory territories from elsewhere.
  private final Set<Unit> unitsToBeConsumed = new HashSet<>();
  private double minCostPerHitPoint = Double.MAX_VALUE;

  /** Decision diary observer; {@link ProDecisionRecorder#NONE} unless a diary is attached. */
  private ProDecisionRecorder recorder = ProDecisionRecorder.NONE;

  private AbstractProAi proAi;
  private GameData data;
  private GamePlayer player;

  public ProData() {
    this(false);
  }

  public ProData(final boolean beta) {
    this(beta, ProTuning.DEFAULT);
  }

  public ProData(final boolean beta, final ProTuning tuning) {
    this.beta = beta;
    this.tuning = tuning;
    // Low Luck values until initialized, as before the tuning existed
    winPercentage = tuning.winPercentage(true);
    minWinPercentage = tuning.minWinPercentage(true);
    attackWinPercentage = winPercentage;
    attackMinWinPercentage = minWinPercentage;
  }

  /**
   * Win% the combat move plans the attack on {@code target} to reach: {@link
   * #getAttackWinPercentage()} unless the tuning sets it per target.
   */
  public double getAttackWinPercentage(final Territory target) {
    return tuning.targetAttackWinPercentage(this, target, attackWinPercentage);
  }

  /**
   * Lowest win% at which the combat move keeps the attack on {@code target}: {@link
   * #getAttackMinWinPercentage()} unless the tuning sets it per target.
   */
  public double getAttackMinWinPercentage(final Territory target) {
    return tuning.targetAttackMinWinPercentage(this, target, attackMinWinPercentage);
  }

  /**
   * Highest {@link #getAttackWinPercentage(Territory)} any target can have: the starting point of
   * the combat move's searches for the attack furthest below its desired win%.
   */
  public double getAttackWinPercentageCeiling() {
    return tuning.attackWinPercentageCeiling(this, attackWinPercentage);
  }

  public void setRecorder(final ProDecisionRecorder recorder) {
    this.recorder = recorder;
  }

  public void initialize(final AbstractProAi proAi) {
    hiddenInitialize(proAi, proAi.getGameData(), proAi.getGamePlayer(), false);
  }

  public void initializeSimulation(
      final AbstractProAi proAi, final GameData data, final GamePlayer player) {
    hiddenInitialize(proAi, data, player, true);
  }

  /**
   * Whether combat move may land behind a sea battle it fights in the same turn. Only Hard Beta
   * does this, for every player.
   */
  public boolean canLandBehindSeaBattle() {
    return beta;
  }

  public Territory getUnitTerritory(final Unit unit) {
    return unitTerritoryMap.get(unit);
  }

  public int getUnitValue(final UnitType type) {
    return unitValueMap.getInt(type);
  }

  private void hiddenInitialize(
      final AbstractProAi proAi,
      final GameData data,
      final GamePlayer player,
      final boolean isSimulation) {
    this.proAi = proAi;
    this.data = data;
    this.player = player;
    this.isSimulation = isSimulation;

    if (!Properties.getLowLuck(data.getProperties())) {
      winPercentage = tuning.winPercentage(false);
      minWinPercentage = tuning.minWinPercentage(false);
    }
    attackWinPercentage = tuning.attackWinPercentage(data, player, winPercentage);
    attackMinWinPercentage = tuning.attackMinWinPercentage(data, player, minWinPercentage);
    myCapital =
        TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data.getMap())
            .orElse(null);
    myUnitTerritories =
        CollectionUtils.getMatches(
            data.getMap().getTerritories(), Matches.territoryHasUnitsOwnedBy(player));
    unitTerritoryMap = newUnitTerritoryMap(data);
    unitValueMap = new TuvCostsCalculator().getCostsForTuv(player);
    purchaseOptions = new ProPurchaseOptionMap(player, data, beta);
    minCostPerHitPoint = getMinCostPerHitPoint(purchaseOptions.getLandOptions());
  }

  private static Map<Unit, Territory> newUnitTerritoryMap(final GameState data) {
    final Map<Unit, Territory> unitTerritoryMap = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        unitTerritoryMap.put(u, t);
      }
    }
    return unitTerritoryMap;
  }

  private double getMinCostPerHitPoint(final List<ProPurchaseOption> landPurchaseOptions) {
    double minCostPerHitPoint = Double.MAX_VALUE;
    for (final ProPurchaseOption ppo : landPurchaseOptions) {
      if (ppo.getCostPerHitPoint() < minCostPerHitPoint) {
        minCostPerHitPoint = ppo.getCostPerHitPoint();
      }
    }
    return minCostPerHitPoint;
  }

  public ProTerritory getProTerritory(Map<Territory, ProTerritory> moveMap, Territory t) {
    return moveMap.computeIfAbsent(t, k -> new ProTerritory(t, this));
  }
}
