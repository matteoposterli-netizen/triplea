package games.strategy.engine.framework.startup.ui;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import org.junit.jupiter.api.Test;

class ProPlusSettingsDialogTest {
  private final GameData data = new GameData();

  ProPlusSettingsDialogTest() {
    addPlayer("Germans", "Axis", false);
    addPlayer("Italians", "Axis", false);
    addPlayer("British", "Allies", false);
    addPlayer("Russians", "Allies", false);
    addPlayer("Neutral_True", "Neutral_True", true);
  }

  private void addPlayer(final String name, final String alliance, final boolean optional) {
    final GamePlayer player = new GamePlayer(name, optional, false, null, false, data);
    data.getPlayerList().addPlayerId(player);
    data.getAllianceTracker().addToAlliance(player, alliance);
  }

  @Test
  void oneSliderPerAllianceOfTheMapAtEquilibrato() {
    final var form = new ProPlusSettingsDialog.Form(data);

    assertThat(form.getSliders()).containsOnlyKeys("Allies", "Axis");
    assertThat(form.getSliders().values())
        .allSatisfy(slider -> assertThat(slider.getValue()).isEqualTo(3));
  }

  @Test
  void slidersShowTheSavedLevelsAndWriteThemBack() {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.SPERICOLATO);
    final var form = new ProPlusSettingsDialog.Form(data);
    assertThat(form.getSliders().get("Axis").getValue()).isEqualTo(5);

    form.getSliders().get("Axis").setValue(2);
    form.getSliders().get("Allies").setValue(4);
    form.apply(data.getProperties());

    assertThat(ProPlusSettings.getRiskLevel(data.getProperties(), "Axis"))
        .isEqualTo(RiskLevel.CAUTO);
    assertThat(ProPlusSettings.getRiskLevel(data.getProperties(), "Allies"))
        .isEqualTo(RiskLevel.AUDACE);
  }
}
