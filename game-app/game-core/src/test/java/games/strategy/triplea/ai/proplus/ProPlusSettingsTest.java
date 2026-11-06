package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.Serializable;
import org.junit.jupiter.api.Test;
import org.triplea.io.IoUtils;

class ProPlusSettingsTest {
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  @Test
  void slidersAreTheMapAlliancesWithRequiredPlayers() {
    // Global 1940 also has optional one-player "alliances" (Dutch, Mongolians, neutrals)
    assertThat(ProPlusSettings.configurableAlliances(data)).containsExactly("Allies", "Axis");
  }

  @Test
  void playerGetsItsAllianceFromTheMap() {
    assertThat(ProPlusSettings.allianceOf(data, player("Germans"))).contains("Axis");
    assertThat(ProPlusSettings.allianceOf(data, player("Japanese"))).contains("Axis");
    assertThat(ProPlusSettings.allianceOf(data, player("Russians"))).contains("Allies");
    assertThat(ProPlusSettings.allianceOf(data, player("ANZAC"))).contains("Allies");
    assertThat(ProPlusSettings.allianceOf(data, player("Dutch"))).isEmpty();
  }

  @Test
  void levelIsEquilibratoUntilSet() {
    assertThat(ProPlusSettings.riskLevelFor(data, player("Germans")))
        .isEqualTo(RiskLevel.EQUILIBRATO);
    assertThat(ProPlusSettings.riskLevelFor(data, player("Dutch")))
        .isEqualTo(RiskLevel.EQUILIBRATO);
  }

  @Test
  void eachAllianceGetsItsOwnLevel() {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.SPERICOLATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.PRUDENTE);

    assertThat(ProPlusSettings.riskLevelFor(data, player("Germans")))
        .isEqualTo(RiskLevel.SPERICOLATO);
    assertThat(ProPlusSettings.riskLevelFor(data, player("Italians")))
        .isEqualTo(RiskLevel.SPERICOLATO);
    assertThat(ProPlusSettings.riskLevelFor(data, player("British"))).isEqualTo(RiskLevel.PRUDENTE);
  }

  @Test
  void settingAgainReplacesTheLevel() {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.AUDACE);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.CAUTO);

    assertThat(ProPlusSettings.getRiskLevel(data.getProperties(), "Axis"))
        .isEqualTo(RiskLevel.CAUTO);
  }

  @Test
  void levelIsStoredAsPlainInteger() {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.AUDACE);

    final Serializable stored = data.getProperties().get("Pro+ risk: Axis");
    assertThat(stored).isEqualTo(4).isExactlyInstanceOf(Integer.class);
  }

  @Test
  void invalidStoredValueFallsBackToDefault() {
    data.getProperties().set("Pro+ risk: Axis", (Serializable) "boh");
    assertThat(ProPlusSettings.getRiskLevel(data.getProperties(), "Axis"))
        .isEqualTo(RiskLevel.DEFAULT);
    data.getProperties().set("Pro+ risk: Axis", (Serializable) null);
    data.getProperties().set("Pro+ risk: Axis", Integer.valueOf(9));
    assertThat(ProPlusSettings.getRiskLevel(data.getProperties(), "Axis"))
        .isEqualTo(RiskLevel.DEFAULT);
  }

  @Test
  void levelsSurviveSaveAndLoad() throws Exception {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.SPERICOLATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.CAUTO);

    final byte[] bytes = IoUtils.writeToMemory(os -> GameDataManager.saveGame(os, data));
    final GameData loaded = IoUtils.readFromMemory(bytes, GameDataManager::loadGame).orElseThrow();

    assertThat(ProPlusSettings.riskLevelFor(loaded, loaded.getPlayerList().getPlayerId("Germans")))
        .isEqualTo(RiskLevel.SPERICOLATO);
    assertThat(ProPlusSettings.riskLevelFor(loaded, loaded.getPlayerList().getPlayerId("Russians")))
        .isEqualTo(RiskLevel.CAUTO);
  }
}
