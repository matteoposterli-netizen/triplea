package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.properties.IEditableProperty;
import games.strategy.triplea.Constants;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.Test;

/** The default tuning must return exactly the numbers the Pro AI used before ProTuning existed. */
class ProTuningTest {
  private final ProTuning tuning = ProTuning.DEFAULT;
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();

  @Test
  void defaultThresholdsAreTheOriginalOnes() {
    assertThat(tuning.winPercentage(true)).isEqualTo(95);
    assertThat(tuning.minWinPercentage(true)).isEqualTo(75);
    assertThat(tuning.winPercentage(false)).isEqualTo(90);
    assertThat(tuning.minWinPercentage(false)).isEqualTo(65);
  }

  @Test
  void defaultAttackThresholdsAreTheSharedOnes() {
    assertThat(tuning.attackWinPercentage(data, germans(data), 90)).isEqualTo(90);
    assertThat(tuning.attackMinWinPercentage(data, germans(data), 65)).isEqualTo(65);
    assertThat(tuning.attackWinPercentage(data, germans(data), 95)).isEqualTo(95);
    assertThat(tuning.attackMinWinPercentage(data, germans(data), 75)).isEqualTo(75);
  }

  @Test
  void defaultVictoryCityValueIsTheOriginalOne() {
    assertThat(tuning.valuesVictoryCities(true)).as("Hard Beta").isTrue();
    assertThat(tuning.valuesVictoryCities(false)).as("Hard").isFalse();
    assertThat(tuning.victoryCityValue()).isEqualTo(5);
    // Global 1940 is won by "Triggered Victory": not a victory-city condition for Hard Beta
    assertThat(tuning.hasVictoryCityWinCondition(data.getProperties())).isFalse();
    data.performChange(ChangeFactory.setProperty(Constants.TOTAL_VICTORY, true, data));
    assertThat(tuning.hasVictoryCityWinCondition(data.getProperties())).isTrue();
  }

  @Test
  void hardProDataHasTheOriginalThresholds() {
    final ProData proData = new ProData();
    assertThat(proData.getTuning()).isSameAs(ProTuning.DEFAULT);
    assertThat(proData.getWinPercentage()).as("before initialize").isEqualTo(95);
    assertThat(proData.getMinWinPercentage()).isEqualTo(75);
    assertThat(proData.getAttackWinPercentage()).isEqualTo(95);
    assertThat(proData.getAttackMinWinPercentage()).isEqualTo(75);

    proData.initializeSimulation(null, data, germans(data));

    assertThat(proData.getWinPercentage()).as("normal dice").isEqualTo(90);
    assertThat(proData.getMinWinPercentage()).isEqualTo(65);
    assertThat(proData.getAttackWinPercentage()).isEqualTo(90);
    assertThat(proData.getAttackMinWinPercentage()).isEqualTo(65);
  }

  @Test
  void hardProDataHasTheOriginalLowLuckThresholds() {
    setLowLuck(data);
    final ProData proData = new ProData(false);

    proData.initializeSimulation(null, data, germans(data));

    assertThat(proData.getWinPercentage()).isEqualTo(95);
    assertThat(proData.getMinWinPercentage()).isEqualTo(75);
    assertThat(proData.getAttackWinPercentage()).isEqualTo(95);
    assertThat(proData.getAttackMinWinPercentage()).isEqualTo(75);
  }

  @Test
  void hardAndHardBetaUseTheDefaultTuning() {
    assertThat(new ProAi("Germans", "Hard (AI)").getProData().getTuning())
        .isSameAs(ProTuning.DEFAULT);
    assertThat(new ProAi("Germans", "Hard Beta (AI)", true).getProData().getTuning())
        .isSameAs(ProTuning.DEFAULT);
  }

  @SuppressWarnings("unchecked")
  private static void setLowLuck(final GameData data) {
    ((IEditableProperty<Boolean>)
            data.getProperties().getEditablePropertiesByName().get(Constants.LOW_LUCK))
        .setValue(true);
  }
}
