package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Proves "Hard (AI)" ranks its attacks as before step 2c (victory cities in the Pro+ attack
 * priority): it replays Hard's first-turn combat move planning on Global 1940 for three nations
 * (seeded battle dice) and compares the priority ranking (territory and value, in the AI's order)
 * and the attacks dropped while ranking with a report recorded on the code before step 2c ({@code
 * proplus/hard-golden-priority-global1940.txt}).
 *
 * <p>Moves and purchase are not in the report: unit ids are random UUIDs, so which unit goes where
 * changes between two loads of the same map even on the same code (verified); the ranking does not.
 * {@code DiaryNeutralityTest} compares whole plans on one loaded map.
 */
class HardPriorityGoldenTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261106L;
  private static final String GOLDEN = "/proplus/hard-golden-priority-global1940.txt";
  private static final List<String> NATIONS = List.of("Germans", "Italians", "British");

  @Test
  void hardAttackPriorityIsTheOneRecordedBeforeStep2c() throws IOException {
    final String actual = report(ProData::new);
    final Path out = Path.of("build", "proplus", "hard-golden-priority-actual.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, actual);

    try (InputStream golden = HardPriorityGoldenTest.class.getResourceAsStream(GOLDEN)) {
      assertThat(golden).as("golden report " + GOLDEN).isNotNull();
      assertThat(actual).isEqualTo(new String(golden.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /** The report for every nation in {@link #NATIONS}, each on a freshly loaded map. */
  static String report(final Supplier<ProData> proDataFactory) {
    final StringBuilder sb = new StringBuilder();
    for (final String nation : NATIONS) {
      final GameData data = TestMapGameData.GLOBAL1940.getGameData();
      sb.append(turn(proDataFactory.get(), data, nation));
    }
    return sb.toString();
  }

  /** One nation's round 1 attack ranking (as the purchase simulation makes it). */
  static String turn(final ProData proData, final GameData data, final String nation) {
    final GamePlayer player = data.getPlayerList().getPlayerId(nation);
    data.getSequence().setRoundAndStep(1, "Combat Move", player);
    final List<String> lines = new ArrayList<>();
    lines.add("== " + nation);
    proData.setRecorder(new PriorityRecorder(lines));
    final TestAi ai = new TestAi(nation, proData, new SeededBattleCalculator(SEED));

    proData.initializeSimulation(ai, data, player);
    ai.prepareData(data);
    new ProCombatMoveAi(ai).doCombatMove(mock(IMoveDelegate.class));
    return String.join("\n", lines) + "\n";
  }

  /** Records the ranking after the priority stage and the attacks dropped while ranking. */
  private record PriorityRecorder(List<String> lines) implements ProDecisionRecorder {
    private static final List<String> RANKING_REASONS =
        List.of("negative_value", "defensive_stance");

    @Override
    public void attackDropped(final ProData proData, final ProTerritory patd, final String reason) {
      if (RANKING_REASONS.contains(reason)) {
        lines.add("dropped " + patd.getTerritory().getName() + " " + reason);
      }
    }

    @Override
    public void attackStage(
        final ProData proData, final String stage, final Collection<ProTerritory> planned) {
      if (!"priority".equals(stage)) {
        return;
      }
      for (final ProTerritory patd : planned) {
        lines.add(
            String.format(
                Locale.ROOT, "priority %s %.3f", patd.getTerritory().getName(), patd.getValue()));
      }
    }
  }

  /** A Pro AI with seeded battle dice; the dice restart on every {@link #prepareData}. */
  private static final class TestAi extends AbstractProAi {
    private final SeededBattleCalculator calculator;

    TestAi(final String name, final ProData proData, final SeededBattleCalculator calculator) {
      super(name, calculator, proData, "Hard (AI)");
      this.calculator = calculator;
    }

    @Override
    protected void prepareData(final GameData data) {
      calculator.setGameData(data).join();
    }
  }
}
