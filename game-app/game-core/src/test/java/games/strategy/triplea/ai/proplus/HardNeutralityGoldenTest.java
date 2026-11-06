package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Proves the Pro+ refactoring left "Hard (AI)" unchanged: it replays Hard's real first-turn German
 * attack planning on Global 1940 (battle simulations with seeded dice) and compares it with a
 * report recorded on the code before the refactoring ({@code proplus/hard-golden-*.txt}).
 *
 * <p>The report holds the risk thresholds, every attack Hard keeps after dropping the ones it can't
 * win (with the simulated win% and TUV swing) and the attack value of every territory.
 */
class HardNeutralityGoldenTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261106L;
  private static final String GOLDEN = "/proplus/hard-golden-global1940-germans.txt";

  @Test
  void hardAttackPlanningIsTheOneRecordedBeforeTheRefactoring() throws IOException {
    final String actual = germanAttackReport(new ProData());
    final Path out = Path.of("build", "proplus", "hard-golden-actual.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, actual);

    try (InputStream golden = HardNeutralityGoldenTest.class.getResourceAsStream(GOLDEN)) {
      assertThat(golden).as("golden report " + GOLDEN).isNotNull();
      assertThat(actual).isEqualTo(new String(golden.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void replayIsReproducible() {
    assertThat(germanAttackReport(new ProData())).isEqualTo(germanAttackReport(new ProData()));
  }

  static String germanAttackReport(final ProData proData) {
    return germanAttackReport(proData, data -> {});
  }

  /** As {@link #germanAttackReport(ProData)}, with {@code setup} applied to the map first. */
  static String germanAttackReport(final ProData proData, final Consumer<GameData> setup) {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    setup.accept(data);
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    data.getSequence().setRoundAndStep(1, "Combat Move", germans);
    proData.initializeSimulation(null, data, germans);
    final SeededBattleCalculator battleCalculator = new SeededBattleCalculator(SEED);
    battleCalculator.setGameData(data).join();
    final ProTerritoryManager manager =
        new ProTerritoryManager(new ProOddsCalculator(battleCalculator), proData);
    manager.populateAttackOptions();
    final List<ProTerritory> attacks = manager.removeTerritoriesThatCantBeConquered();

    final StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            Locale.ROOT,
            "win%%=%.1f minWin%%=%.1f%n",
            proData.getWinPercentage(),
            proData.getMinWinPercentage()));
    attacks.stream()
        .sorted(Comparator.comparing(a -> a.getTerritory().getName()))
        .forEach(
            a ->
                sb.append(
                    String.format(
                        Locale.ROOT,
                        "attack %s maxWin%%=%.2f swing=%.2f%n",
                        a.getTerritory().getName(),
                        a.getMaxBattleResult().getWinPercentage(),
                        a.getMaxBattleResult().getTuvSwing())));
    data.getMap().getTerritories().stream()
        .sorted(Comparator.comparing(Territory::getName))
        .forEach(
            t -> {
              final double value =
                  ProTerritoryValueUtils.findTerritoryAttackValue(proData, germans, t);
              if (value != 0) {
                sb.append(String.format(Locale.ROOT, "value %s %.3f%n", t.getName(), value));
              }
            });
    return sb.toString();
  }
}
