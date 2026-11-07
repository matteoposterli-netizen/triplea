package games.strategy.engine.framework.startup.ui;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.ai.proplus.strategia.Strategie;
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

  @Test
  void withoutAStrategyFileThereIsNoPlanChoice() {
    final var form = new ProPlusSettingsDialog.Form(data);

    assertThat(form.getPlans()).isEmpty();
  }

  @Test
  void planChoicePerAllianceStartsAtAutomaticoAndIsWrittenBack() {
    data.setGameName("World War II Europe 1940 2nd Edition");
    final var form = new ProPlusSettingsDialog.Form(data);

    assertThat(form.getPlans()).containsOnlyKeys("Allies", "Axis");
    final var axis = form.getPlans().get("Axis");
    assertThat(axis.getSelectedItem()).hasToString("Automatico (= A Barbarossa)");
    assertThat(axis.getItemCount()).isEqualTo(6);
    axis.setSelectedIndex(2); // B Sea Lion
    form.apply(data.getProperties());

    assertThat(StatoMaggiore.getChoice(data.getProperties(), "Axis")).isEqualTo("B");
    assertThat(StatoMaggiore.getChoice(data.getProperties(), "Allies"))
        .isEqualTo(StatoMaggiore.AUTOMATICO);
  }

  @Test
  void aFixedPlanCannotBeChangedAnyMore() {
    data.setGameName("World War II Europe 1940 2nd Edition");
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "D");
    StatoMaggiore.fix(data, "Axis", Strategie.forGame(data).orElseThrow());

    final var form = new ProPlusSettingsDialog.Form(data);

    assertThat(form.getPlans()).containsOnlyKeys("Allies");
  }
}
