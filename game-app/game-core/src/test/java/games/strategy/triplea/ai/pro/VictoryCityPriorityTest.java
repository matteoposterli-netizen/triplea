package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step 2c: Pro+ ranks a victory city target ahead of an equivalent target without one; Hard does
 * not. Global 1940, German round 1: Hard ranks Normandy Bordeaux (16.7) just ahead of Yugoslavia
 * (16.3), neither a victory city. The test makes Yugoslavia a victory city and replays the ranking
 * with the same thresholds for both AIs (Pro+ "Prudente" = Hard's).
 */
class VictoryCityPriorityTest extends AbstractClientSettingTestCase {

  @Test
  void proPlusRanksAVictoryCityAheadOfAnEquivalentTarget() {
    final List<String> hard = ranking(new ProData());
    final List<String> proPlus = ranking(new ProData(false, new ProPlusTuning()));

    // Hard: Yugoslavia's victory city changes nothing
    assertThat(hard.indexOf("Normandy Bordeaux")).isLessThan(hard.indexOf("Yugoslavia"));
    // Pro+: Yugoslavia moves ahead, but the enemy capital stays first
    assertThat(proPlus.indexOf("Yugoslavia")).isLessThan(proPlus.indexOf("Normandy Bordeaux"));
    assertThat(proPlus.get(0)).isEqualTo("France");
    assertThat(hard.get(0)).isEqualTo("France");
  }

  @Test
  void victoryCityPartIsZeroForHardAndTheWeightForProPlus() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final Territory unitedKingdom = data.getMap().getTerritoryOrThrow("United Kingdom");
    final Territory normandy = data.getMap().getTerritoryOrThrow("Normandy Bordeaux");
    final ProData hard = new ProData();
    hard.initializeSimulation(null, data, germans);
    final ProData proPlus = new ProData(false, new ProPlusTuning());
    proPlus.initializeSimulation(null, data, germans);

    assertThat(ProTerritoryValueUtils.victoryCityAttackPriority(hard, unitedKingdom, true))
        .isZero();
    assertThat(ProTerritoryValueUtils.victoryCityAttackPriority(proPlus, unitedKingdom, true))
        .isEqualTo(10);
    assertThat(ProTerritoryValueUtils.victoryCityAttackPriority(proPlus, unitedKingdom, false))
        .isEqualTo(2.5);
    assertThat(ProTerritoryValueUtils.victoryCityAttackPriority(proPlus, normandy, true)).isZero();
  }

  /** German round 1 targets in the AI's priority order, with Yugoslavia a victory city. */
  private static List<String> ranking(final ProData proData) {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.PRUDENTE);
    makeVictoryCity(data.getMap().getTerritoryOrThrow("Yugoslavia"));
    final String report = HardPriorityGoldenTest.turn(proData, data, "Germans");
    return report
        .lines()
        .filter(l -> l.startsWith("priority "))
        .map(l -> l.substring("priority ".length(), l.lastIndexOf(' ')))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static void makeVictoryCity(final Territory t) {
    try {
      ((MutableProperty<Integer>)
              TerritoryAttachment.get(t).orElseThrow().getPropertyOrThrow("victoryCity"))
          .setValue(1);
    } catch (final MutableProperty.InvalidValueException e) {
      throw new AssertionError(e);
    }
  }
}
