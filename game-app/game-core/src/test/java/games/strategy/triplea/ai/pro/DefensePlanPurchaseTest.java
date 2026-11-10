package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Step C2: a computed defense plan ({@link ProTuning#defensePlan}) replaces the "defend land" part
 * of the purchase, caps the PUs of the purchase orders, and the Pro AI spends the rest; without it
 * (Hard) nothing changes.
 */
class DefensePlanPurchaseTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261111L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");

  @Test
  void theDefensePlanIsBoughtFirstThenTheStrategyBudgetThenHardSpendsTheRest() {
    final int pus = germans.getResources().getQuantity(Constants.PUS);
    final Capture c = purchase(new PlanTuning(6));
    // defend land = exactly the plan (3 infantry in Germany), nothing of Hard's defense
    assertThat(c.steps.get("defend_land")).isEqualTo(Map.of("Germany infantry", 3));
    // the orders (10 armour asked) get only the strategy budget: 1 armour (6 PU)
    final Map<String, Integer> plan = diff(c.steps.get("plan"), c.steps.get("defend_land"));
    assertThat(plan.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
    assertThat(plan.keySet()).allMatch(k -> k.endsWith("armour"));
    // Hard spends what is left with its own logic
    assertThat(c.spent).isGreaterThan(9 + 6);
    assertThat(c.spent).isLessThanOrEqualTo(pus);
  }

  @Test
  void withoutADefensePlanTheStrategyBudgetDoesNotCapTheOrders() {
    final Capture c = purchase(new PlanTuning(-1));
    final Map<String, Integer> plan = diff(c.steps.get("plan"), c.steps.get("defend_land"));
    assertThat(plan.values().stream().mapToInt(Integer::intValue).sum()).isGreaterThan(1);
    assertThat(ProTuning.DEFAULT.defensePlan(new ProData())).isEmpty();
  }

  private static Map<String, Integer> diff(
      final Map<String, Integer> after, final Map<String, Integer> before) {
    final Map<String, Integer> out = new TreeMap<>();
    after.forEach(
        (k, v) -> {
          final int d = v - before.getOrDefault(k, 0);
          if (d > 0) {
            out.put(k, d);
          }
        });
    return out;
  }

  /** Orders that ask 10 armour; a defense plan of 3 infantry in Germany (budget < 0: no plan). */
  private static final class PlanTuning implements ProTuning {
    private final int budget;

    PlanTuning(final int budget) {
      this.budget = budget;
    }

    @Override
    public Optional<ProPurchaseOrders> purchaseOrders(final ProData proData) {
      return Optional.of(
          new ProPurchaseOrders(
              "test",
              List.of(),
              List.of(new ProPurchaseOrders.Mandatory("terra", List.of("armour"), 10)),
              List.of(),
              List.of(),
              0));
    }

    @Override
    public Optional<ProDefensePlan> defensePlan(final ProData proData) {
      return budget < 0
          ? Optional.empty()
          : Optional.of(
              new ProDefensePlan(
                  "test", List.of(new ProDefensePlan.Purchase("Germany", "infantry", 3)), budget));
    }
  }

  private Capture purchase(final ProTuning tuning) {
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
              for (final ProductionRule r : rules.keySet()) {
                capture.spent +=
                    rules.getInt(r)
                        * r.getCosts()
                            .getInt(data.getResourceList().getResourceOrThrow(Constants.PUS));
              }
              return null;
            });
    SeededPurchaseChoice.with(SEED, () -> new ProPurchaseAi(ai).purchase(purchaseDel, data));
    return capture;
  }

  private static final class Capture implements ProDecisionRecorder {
    private final Map<String, Map<String, Integer>> steps = new TreeMap<>();
    private int spent;

    @Override
    public void purchaseStep(
        final ProData proData,
        final String step,
        final Map<Territory, ProPurchaseTerritory> territories,
        final Map<Territory, ProPurchaseTerritory> factories) {
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
      steps.put(step, units);
    }
  }

  /** A Pro AI with seeded battle dice. */
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
