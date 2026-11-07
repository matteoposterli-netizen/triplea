package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Step 3b-bis: the plan's minimum share of the purchase. Global 1940, Germans round 1 (30 PUs),
 * Poland taken by a British army that threatens Germany (capital and factory). The defense may take
 * more than the share leaves only for a factory or the capital at risk of being taken.
 */
class PlanShareTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261107L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");

  @BeforeEach
  void setUp() {
    data.getSequence().setRoundAndStep(1, "Purchase", germans);
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    final Territory poland = data.getMap().getTerritoryOrThrow("Poland");
    data.performChange(
        ChangeFactory.removeUnits(poland, new ArrayList<>(poland.getUnitCollection())));
    data.performChange(ChangeFactory.changeOwner(poland, british));
    final List<Unit> army = new ArrayList<>();
    army.addAll(data.getUnitTypeList().getUnitTypeOrThrow("armour").create(16, british));
    army.addAll(data.getUnitTypeList().getUnitTypeOrThrow("infantry").create(10, british));
    data.performChange(ChangeFactory.addUnits(poland, army));
  }

  @Test
  void aCapitalAtRiskMayTakeMoreThanTheDefenseShare() {
    final Result result = purchase(new ShareTuning(70, 25));

    assertThat(result.share()).isNotNull();
    assertThat(result.share().critical()).containsKey("Germany");
    assertThat(result.share().critical().get("Germany")).isGreaterThan(25);
    assertThat(result.share().defenseCap()).isEqualTo(9);
    assertThat(result.share().spentCritical()).isGreaterThan(9);
  }

  @Test
  void otherwiseTheDefenseStaysWithinItsShare() {
    // nothing counts as at risk: the capital too is defended within the share
    final Result result = purchase(new ShareTuning(70, 100));

    assertThat(result.share().critical()).isEmpty();
    assertThat(result.share().limited()).containsKey("Germany");
    assertThat(result.share().spentLimited()).isLessThanOrEqualTo(9);
    assertThat(result.share().pusLeft()).isGreaterThanOrEqualTo(21);
  }

  @Test
  void withoutAShareTheDefenseIsHards() {
    // (Hard picks defenders with Math.random: only the path can be compared, not the units)
    assertThat(purchase(new ShareTuning(0, 25)).share()).isNull();
    assertThat(purchase(ProTuning.DEFAULT).share()).isNull();
  }

  // ------------------------------------------------------------------------------- helpers

  private record Result(ProPurchaseOrders.DefenseShare share, Map<String, Integer> defendLand) {}

  /** A plan that only buys infantry, with {@code share}% reserved and the guard {@code risk}. */
  private record ShareTuning(double share, double risk) implements ProTuning {
    @Override
    public Optional<ProPurchaseOrders> purchaseOrders(final ProData proData) {
      return Optional.of(
          new ProPurchaseOrders(
              "test",
              List.of(new ProPurchaseOrders.Category("fanteria", 100, List.of("infantry"))),
              List.of(),
              List.of(),
              List.of(),
              share));
    }

    @Override
    public OptionalDouble capitalGuardThreshold(final ProData proData) {
      return OptionalDouble.of(risk);
    }
  }

  private Result purchase(final ProTuning tuning) {
    final ProData proData = new ProData(false, tuning);
    final TestAi ai = new TestAi(proData, new SeededBattleCalculator(SEED));
    final Capture capture = new Capture();
    proData.setRecorder(capture);
    proData.initializeSimulation(ai, data, germans);
    ai.prepareData(data);
    final IPurchaseDelegate purchaseDel = mock(IPurchaseDelegate.class);
    when(purchaseDel.purchase(any()))
        .thenAnswer(
            invocation -> {
              final IntegerMap<ProductionRule> rules = invocation.getArgument(0);
              rules.keySet().forEach(r -> rules.getInt(r));
              return null;
            });
    new ProPurchaseAi(ai).purchase(purchaseDel, data);
    return new Result(capture.share, capture.defendLand);
  }

  private static final class Capture implements ProDecisionRecorder {
    private ProPurchaseOrders.DefenseShare share;
    private Map<String, Integer> defendLand = Map.of();

    @Override
    public void purchaseDefenseShare(
        final ProData proData,
        final ProPurchaseOrders orders,
        final ProPurchaseOrders.DefenseShare share) {
      this.share = share;
    }

    @Override
    public void purchaseStep(
        final ProData proData,
        final String step,
        final Map<Territory, games.strategy.triplea.ai.pro.data.ProPurchaseTerritory> territories,
        final Map<Territory, games.strategy.triplea.ai.pro.data.ProPurchaseTerritory> factories) {
      if ("defend_land".equals(step)) {
        final Map<String, Integer> units = new TreeMap<>();
        territories
            .values()
            .forEach(
                ppt ->
                    ppt.getCanPlaceTerritories()
                        .forEach(
                            place ->
                                place
                                    .getPlaceUnits()
                                    .forEach(
                                        u ->
                                            units.merge(
                                                place.getTerritory().getName()
                                                    + " "
                                                    + u.getType().getName(),
                                                1,
                                                Integer::sum))));
        defendLand = units;
      }
    }
  }

  /** A Pro AI with seeded battle dice; the dice restart on every {@link #prepareData}. */
  private static final class TestAi extends AbstractProAi {
    private final SeededBattleCalculator calculator;

    TestAi(final ProData proData, final SeededBattleCalculator calculator) {
      super("Germans", calculator, proData, ProPlusAi.PLAYER_LABEL);
      this.calculator = calculator;
    }

    @Override
    protected void prepareData(final GameData data) {
      calculator.setGameData(data).join();
    }
  }
}
