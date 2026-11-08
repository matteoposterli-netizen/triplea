package games.strategy.engine.framework.startup.ui;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.strategia.Decisori;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.ai.proplus.strategia.Strategie;
import games.strategy.triplea.ai.proplus.strategia.llm.ClaudeCode;
import games.strategy.triplea.ai.proplus.strategia.llm.Modalita;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Uso;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProPlusSettingsDialogTest {
  @TempDir static Path profile;
  private final GameData data = new GameData();

  @BeforeAll
  static void noRealStrategist() {
    // the panel never looks for Claude Code nor reads the user's real profile in these tests
    System.setProperty(ClaudeCode.DISABLED_PROPERTY, "true");
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
  }

  @AfterAll
  static void cleanUp() {
    System.clearProperty(ClaudeCode.DISABLED_PROPERTY);
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

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
  void strategistPerAllianceStartsAtRulesAndIsWrittenBackWithFileAndFantasy() {
    data.setGameName("World War II Europe 1940 2nd Edition");
    final ProPlusSettingsDialog.Form form = new ProPlusSettingsDialog.Form(data);

    assertThat(form.getDeciders()).containsOnlyKeys("Allies", "Axis");
    assertThat(form.getDeciders().get("Axis").getSelectedItem()).isEqualTo(Modalita.REGOLE);
    assertThat(form.getFantasies().get("Axis").getValue()).isEqualTo(2);
    form.getDeciders().get("Allies").setSelectedItem(Modalita.FILE);
    form.getDeciderFiles().get("Allies").setText(" C:\\direttive ");
    form.getDeciders().get("Axis").setSelectedItem(Modalita.AUTOMATICO);
    form.getFantasies().get("Axis").setValue(4);
    form.getStrategist().getCap().setValue(20);
    form.apply(data.getProperties());

    assertThat(Decisori.getModalita(data.getProperties(), "Allies")).isEqualTo(Modalita.FILE);
    assertThat(Decisori.getFile(data.getProperties(), "Allies")).isEqualTo("C:\\direttive");
    assertThat(Decisori.getModalita(data.getProperties(), "Axis")).isEqualTo(Modalita.AUTOMATICO);
    assertThat(Uso.fantasia(data.getProperties(), "Axis")).isEqualTo(4);
    assertThat(Uso.tetto(data.getProperties())).isEqualTo(20);
    // the LLM mode becomes the user's default for new games; no key in the game
    assertThat(ProfiloStratega.carica().modalitaPredefinita()).isEqualTo(Modalita.AUTOMATICO);
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
