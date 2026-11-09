package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.proplus.Ablazione;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.ai.proplus.strategia.Strategie;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Step ABLAZIONE: "Pro+ nudo" (every Pro+ component off) plays as "Hard (AI)". This is the check
 * that the hooks in the Pro classes add no bug. Global 1940 renamed as Europe 1940, so the strategy
 * file (plan A / plan 1) is there and must be ignored too.
 *
 * <p>The check: one whole turn (attack plan as the purchase simulates it, purchase with seeded
 * choices, combat move, non-combat move) with seeded battle dice, played by a Pro+ nudo whose every
 * {@link ProTuning} answer is compared with the answer of Hard's {@link ProTuning#DEFAULT} to the
 * same question: the Pro classes see exactly Hard's numbers, so they take Hard's decisions. A
 * literal comparison of two turns is not a reliable test: Hard itself does not repeat its attack
 * plan from one turn to the next in the same JVM (the order of some hash sets of fresh objects
 * changes), measured while writing this test (8 different plans out of 8 replays after other
 * turns).
 *
 * <p>Control: with every component on, the same turn gets answers different from Hard's.
 */
class NudoHardTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261109L;
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();

  @BeforeEach
  void setUp() {
    data.setGameName(EUROPE);
    final var strategia = Strategie.forGame(data).orElseThrow();
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "A");
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    StatoMaggiore.fix(data, "Axis", strategia);
    StatoMaggiore.fix(data, "Allies", strategia);
  }

  @Test
  void axisNudoAnswersAsHardThroughAWholeTurn() {
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final Check full = turn(germans);
    assertThat(full.mismatches()).as("control: Pro+ with every component on").isNotEmpty();
    assertThat(full.decisions()).contains("attack ").contains("buy ");

    Ablazione.set(data.getProperties(), "Axis", Ablazione.NUDO);
    final Check nudo = turn(germans);
    assertThat(nudo.mismatches()).isEmpty();
    assertThat(nudo.calls()).as("the Pro classes asked the tuning").isGreaterThan(1000);
    assertThat(nudo.decisions()).contains("attack ").contains("buy ").contains("move ");
    assertThat(nudo.choices()).as("seeded random purchase choices (Hard's purchase)").isPositive();
  }

  @Test
  void alliesNudoAnswersAsHardThroughAWholeTurn() {
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    assertThat(turn(british).mismatches()).as("control").isNotEmpty();

    Ablazione.set(data.getProperties(), "Allies", Ablazione.NUDO);
    final Check nudo = turn(british);
    assertThat(nudo.mismatches()).isEmpty();
    assertThat(nudo.decisions()).contains("buy ");
    // only the Allies are nudo: the Axis keeps every component
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    assertThat(turn(germans).mismatches()).isNotEmpty();
  }

  /**
   * @param decisions what the turn did (attacks, purchases, moves)
   * @param mismatches the tuning answers different from Hard's (method: Pro+ vs Hard)
   * @param calls tuning questions asked
   * @param choices seeded random purchase choices
   */
  record Check(String decisions, List<String> mismatches, int calls, int choices) {}

  /** One turn of {@code player} with the Pro+ tuning checked against Hard's on every answer. */
  private Check turn(final GamePlayer player) {
    final ProPlusTuning plus = new ProPlusTuning();
    final List<String> mismatches = new ArrayList<>();
    final int[] calls = {0};
    final ProTuning checked =
        (ProTuning)
            Proxy.newProxyInstance(
                ProTuning.class.getClassLoader(),
                new Class<?>[] {ProTuning.class},
                (proxy, method, args) -> {
                  if (method.getDeclaringClass() == Object.class) {
                    return method.invoke(plus, args);
                  }
                  calls[0]++;
                  final Object mine = invoke(method, plus, args);
                  final Object hard = invoke(method, ProTuning.DEFAULT, args);
                  if (!Objects.equals(mine, hard) && mismatches.size() < 50) {
                    mismatches.add(method.getName() + ": " + mine + " vs " + hard);
                  }
                  return mine;
                });
    final ProData proData = new ProData(false, checked);
    plus.attach(proData);
    final String decisions = SeededPurchaseChoice.with(SEED, () -> seededTurn(player, proData));
    return new Check(
        decisions, List.copyOf(mismatches), calls[0], SeededPurchaseChoice.choicesOfLastRun());
  }

  private static Object invoke(final Method method, final ProTuning target, final Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (final InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private String seededTurn(final GamePlayer player, final ProData proData) {
    final TestAi ai = new TestAi(player.getName(), proData, new SeededBattleCalculator(SEED));
    final List<String> decisions = new ArrayList<>();
    data.getSequence().setRoundAndStep(1, "Combat Move", player);
    proData.initializeSimulation(ai, data, player);
    ai.prepareData(data);
    final Map<?, ProTerritory> plan =
        new ProCombatMoveAi(ai).doCombatMove(recordingMoves(decisions));
    plan.values().stream()
        .filter(p -> !p.getUnits().isEmpty())
        .map(p -> "attack " + p.getTerritory().getName() + " " + unitTypes(p.getUnits()))
        .sorted()
        .forEach(decisions::add);
    proData.initializeSimulation(ai, data, player);
    ai.prepareData(data);
    new ProPurchaseAi(ai).purchase(recordingPurchase(decisions), data);
    data.getSequence().setRoundAndStep(1, "Non Combat Move", player);
    proData.initializeSimulation(ai, data, player);
    ai.prepareData(data);
    new ProNonCombatMoveAi(ai).doNonCombatMove(null, null, recordingMoves(decisions));
    return String.join("\n", decisions);
  }

  private static IMoveDelegate recordingMoves(final List<String> decisions) {
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any()))
        .thenAnswer(
            invocation -> {
              final MoveDescription move = invocation.getArgument(0);
              decisions.add(
                  "move "
                      + unitTypes(move.getUnits())
                      + " "
                      + move.getRoute().getAllTerritories().stream()
                          .map(t -> t.getName())
                          .collect(Collectors.joining(">")));
              return Optional.empty();
            });
    return moveDel;
  }

  private static IPurchaseDelegate recordingPurchase(final List<String> decisions) {
    final IPurchaseDelegate purchaseDel = mock(IPurchaseDelegate.class);
    when(purchaseDel.purchase(any()))
        .thenAnswer(
            invocation -> {
              final IntegerMap<ProductionRule> bought = invocation.getArgument(0);
              bought.keySet().stream()
                  .map(r -> "buy " + r.getName() + " x" + bought.getInt(r))
                  .sorted()
                  .forEach(decisions::add);
              return null;
            });
    return purchaseDel;
  }

  private static String unitTypes(final Collection<Unit> units) {
    return units.stream()
        .map(u -> u.getType().getName() + "@" + u.getOwner().getName())
        .sorted()
        .collect(Collectors.joining(","));
  }

  /** An AI with seeded battle dice; the dice restart on every {@link #prepareData}. */
  private static final class TestAi extends AbstractProAi {
    private final SeededBattleCalculator calculator;

    TestAi(final String name, final ProData proData, final SeededBattleCalculator calculator) {
      super(name, calculator, proData, ProPlusAi.PLAYER_LABEL);
      this.calculator = calculator;
    }

    @Override
    protected void prepareData(final GameData data) {
      calculator.setGameData(data).join();
    }
  }
}
