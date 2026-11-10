package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Step C2-bis: the "Pro+ Stratega LLM" player defends as Hard ({@link ProTuning#strategyCapPercent}
 * only caps the purchase orders after Hard's own defense, then Hard spends the rest) and the LLM's
 * lists steer Hard's defense ({@link ProTuning#defenseLists}); without them (Hard) nothing changes.
 */
class StrategyBudgetDefenseTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261111L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");

  @Test
  void hardDefendsFirstThenTheOrdersWithinTheCapThenHardSpendsTheRest() {
    exposeWesternGermany();
    final int pus = germans.getResources().getQuantity(Constants.PUS);
    final Capture hard = purchase(new TestTuning(-1, Set.of(), Set.of(), false));
    final Capture capped = purchase(new TestTuning(30, Set.of(), Set.of(), true));
    // the defense is Hard's own (same purchase as without orders and cap)
    assertThat(hard.steps.get("defend_land")).isNotEmpty();
    assertThat(capped.steps.get("defend_land")).isEqualTo(hard.steps.get("defend_land"));
    // the orders (10 armour) get at most 30% of the turn's PUs
    final ProStrategyBudget b = capped.budget;
    assertThat(b).isNotNull();
    assertThat(b.pus()).isEqualTo(pus);
    assertThat(b.defense()).isEqualTo(cost(capped.steps.get("defend_land")));
    assertThat(b.budget()).isEqualTo(Math.min(pus - b.defense(), (int) Math.floor(pus * 0.3)));
    assertThat(b.orders()).isLessThanOrEqualTo(b.budget()).isPositive();
    assertThat(cost(diff(capped.steps.get("plan"), capped.steps.get("defend_land"))))
        .isEqualTo(b.orders());
    assertThat(b.reserved()).isPositive();
    assertThat(b.limited()).isTrue();
    // Hard spends what is left with its own logic
    assertThat(capped.spent).isGreaterThan(b.defense() + b.orders());
    assertThat(hard.budget).isNull();
    assertThat(ProTuning.DEFAULT.strategyCapPercent(new ProData())).isEmpty();
    assertThat(ProTuning.DEFAULT.defenseLists(new ProData())).isEmpty();
  }

  @Test
  void anExpendableTerritoryGetsNoPurchaseOfHard() {
    exposeWesternGermany();
    final Capture hard = purchase(new TestTuning(-1, Set.of(), Set.of(), false));
    assertThat(placedIn(hard, "Western Germany")).isPositive();
    final Capture given = purchase(new TestTuning(70, Set.of("Western Germany"), Set.of(), false));
    assertThat(placedIn(given, "Western Germany")).isZero();
  }

  @Test
  void anIrreplaceableTerritoryIsDefendedAlsoWhenHardWouldGiveItUp() {
    exposeWesternGermany();
    overwhelm("France", "Western Germany");
    final Capture hard = purchase(new TestTuning(-1, Set.of(), Set.of(), false));
    final Capture must = purchase(new TestTuning(70, Set.of(), Set.of("Western Germany"), false));
    assertThat(defendLandIn(hard, "Western Germany")).isZero();
    assertThat(defendLandIn(must, "Western Germany")).isPositive();
  }

  @Test
  void theNonCombatMoveSendsNoUnitToAnExpendableTerritory() {
    exposeWesternGermany();
    final List<String> hard = nonCombat(new TestTuning(-1, Set.of(), Set.of(), false));
    assertThat(endingIn(hard, "Western Germany")).isPositive();
    final List<String> given =
        nonCombat(new TestTuning(70, Set.of("Western Germany"), Set.of(), false));
    assertThat(endingIn(given, "Western Germany")).isLessThan(endingIn(hard, "Western Germany"));
  }

  // ----------------------------------------------------------------------------------- helpers

  /** Western Germany without its land units: Hard has to defend it. */
  private void exposeWesternGermany() {
    final Territory t = data.getMap().getTerritoryOrNull("Western Germany");
    final List<Unit> units =
        t.getMatches(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()));
    data.performChange(ChangeFactory.removeUnits(t, units));
  }

  /** So many French infantry next to {@code target} that Hard gives it up. */
  private void overwhelm(final String from, final String target) {
    final GamePlayer french = data.getPlayerList().getPlayerId("French");
    final Territory t = data.getMap().getTerritoryOrNull(from);
    assertThat(data.getMap().getNeighbors(t)).contains(data.getMap().getTerritoryOrNull(target));
    data.performChange(
        ChangeFactory.addUnits(
            t, data.getUnitTypeList().getUnitTypeOrThrow("infantry").createTemp(60, french)));
  }

  private int cost(final Map<String, Integer> units) {
    int c = 0;
    for (final Map.Entry<String, Integer> e : units.entrySet()) {
      final String type = e.getKey().substring(e.getKey().lastIndexOf(' ') + 1);
      c +=
          e.getValue()
              * germans.getProductionFrontier().getRules().stream()
                  .filter(r -> r.getAnyResultKey().getName().equals(type))
                  .findFirst()
                  .orElseThrow()
                  .getCosts()
                  .getInt(data.getResourceList().getResourceOrThrow(Constants.PUS));
    }
    return c;
  }

  private static int placedIn(final Capture c, final String territory) {
    return c.steps.values().stream()
        .flatMap(m -> m.entrySet().stream())
        .filter(e -> e.getKey().startsWith(territory + " "))
        .mapToInt(Map.Entry::getValue)
        .max()
        .orElse(0);
  }

  private static int defendLandIn(final Capture c, final String territory) {
    return c.steps.get("defend_land").entrySet().stream()
        .filter(e -> e.getKey().startsWith(territory + " "))
        .mapToInt(Map.Entry::getValue)
        .sum();
  }

  private static long endingIn(final List<String> moves, final String territory) {
    return moves.stream().filter(m -> m.endsWith(">" + territory)).count();
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

  /** A cap (< 0: none), the lists and, if asked, orders of 10 armour. */
  private record TestTuning(
      double cap, Set<String> expendable, Set<String> mustHold, boolean orders)
      implements ProTuning {
    @Override
    public Optional<ProPurchaseOrders> purchaseOrders(final ProData proData) {
      return orders
          ? Optional.of(
              new ProPurchaseOrders(
                  "test",
                  List.of(),
                  List.of(new ProPurchaseOrders.Mandatory("terra", List.of("armour"), 10)),
                  List.of(),
                  List.of(),
                  70))
          : Optional.empty();
    }

    @Override
    public OptionalDouble strategyCapPercent(final ProData proData) {
      return cap < 0 ? OptionalDouble.empty() : OptionalDouble.of(cap);
    }

    @Override
    public Optional<ProDefenseLists> defenseLists(final ProData proData) {
      return cap < 0 ? Optional.empty() : Optional.of(new ProDefenseLists(expendable, mustHold));
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

  private List<String> nonCombat(final ProTuning tuning) {
    data.getSequence().getSteps().stream()
        .filter(s -> germans.equals(s.getPlayerId()))
        .filter(s -> games.strategy.engine.data.GameStep.isNonCombatMoveStepName(s.getName()))
        .findFirst()
        .ifPresent(s -> data.getSequence().setRoundAndStep(1, s.getDisplayName(), germans));
    final ProData proData = new ProData(false, tuning);
    final TestAi ai = new TestAi(proData, new SeededBattleCalculator(SEED));
    proData.initializeSimulation(ai, data, germans);
    ai.prepareData(data);
    final List<String> moves = new ArrayList<>();
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any()))
        .thenAnswer(
            invocation -> {
              final MoveDescription move = invocation.getArgument(0);
              moves.add(
                  String.join(
                      ">",
                      move.getRoute().getAllTerritories().stream()
                          .map(Territory::getName)
                          .toList()));
              return Optional.empty();
            });
    new ProNonCombatMoveAi(ai).doNonCombatMove(null, null, moveDel);
    return moves;
  }

  private static final class Capture implements ProDecisionRecorder {
    private final Map<String, Map<String, Integer>> steps = new TreeMap<>();
    private int spent;
    private ProStrategyBudget budget;

    @Override
    public void strategyBudget(final ProData proData, final ProStrategyBudget budget) {
      this.budget = budget;
    }

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
