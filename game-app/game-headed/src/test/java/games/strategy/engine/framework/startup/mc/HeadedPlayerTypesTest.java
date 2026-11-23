package games.strategy.engine.framework.startup.mc;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusEuropa1940Ai;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

public class HeadedPlayerTypesTest {

  @BeforeEach
  public final void initializeClientSettingPreferences() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @AfterEach
  @SuppressWarnings("static-method")
  public final void uninitializeClientSettingPreferences() {
    ClientSetting.resetPreferences();
  }

  @Test
  void playerTypes() {
    final PlayerTypes playerTypes = new PlayerTypes(HeadedPlayerTypes.getPlayerTypes());
    assertThat(List.of(playerTypes.getAvailablePlayerLabels()))
        .as("Ensure we do not have an example invisible player type in the selection list")
        .doesNotContain(HeadedPlayerTypes.CLIENT_PLAYER.getLabel());

    assertThat(List.of(playerTypes.getAvailablePlayerLabels()))
        .as("Ensure we have a visible player type in the selection list")
        .contains(PlayerTypes.WEAK_AI.getLabel());
  }

  @Test
  void hardBetaAiIsHiddenWithoutBetaFeatures() {
    ClientSetting.showBetaFeatures.setValue(false);

    final PlayerTypes playerTypes = new PlayerTypes(HeadedPlayerTypes.getPlayerTypes());

    assertThat(playerTypes.getAvailablePlayerLabels()).doesNotContain("Hard Beta (AI)");
  }

  @Test
  void hardBetaAiIsSelectableWithBetaFeatures() {
    ClientSetting.showBetaFeatures.setValue(true);

    final PlayerTypes playerTypes = new PlayerTypes(HeadedPlayerTypes.getPlayerTypes());

    assertThat(playerTypes.getAvailablePlayerLabels()).contains("Hard Beta (AI)");
  }

  @Test
  void proPlusEuropa1940IsTheOnlyProPlusTypeOfTheList() {
    ClientSetting.showBetaFeatures.setValue(true);

    final PlayerTypes playerTypes = new PlayerTypes(HeadedPlayerTypes.getPlayerTypes());

    final List<String> labels = List.of(playerTypes.getAvailablePlayerLabels());
    assertThat(labels)
        .containsSubsequence(PlayerTypes.PRO_AI.getLabel(), "Pro+ (Europe 1940)")
        .doesNotContain("Pro+ (AI)", "Pro+ Stratega LLM (Europe 1940)");
    assertThat(labels.indexOf("Pro+ (Europe 1940)"))
        .isEqualTo(labels.indexOf(PlayerTypes.PRO_AI.getLabel()) + 1);
  }

  @Test
  void proPlusEuropa1940IsInstantiable() {
    final PlayerTypes playerTypes = new PlayerTypes(HeadedPlayerTypes.getPlayerTypes());

    final Player player = playerTypes.fromLabel("Pro+ (Europe 1940)").newPlayerWithName("British");

    assertThat(player)
        .isInstanceOf(ProPlusEuropa1940Ai.class)
        .isInstanceOf(games.strategy.triplea.ai.proplus.ProPlusStrategaAi.class);
    assertThat(player.getPlayerLabel()).isEqualTo("Pro+ (Europe 1940)");
    assertThat(player.isAi()).isTrue();
  }

  @Test
  void theOldProPlusTypesAreStillLoadedFromSavedGames() {
    final PlayerTypes playerTypes = new PlayerTypes(HeadedPlayerTypes.getPlayerTypes());

    assertThat(HeadedPlayerTypes.LEGACY_LABELS)
        .containsExactlyInAnyOrder("Pro+ (AI)", "Pro+ Stratega LLM (Europe 1940)");
    assertThat(playerTypes.fromLabel("Pro+ (AI)").newPlayerWithName("Germans"))
        .isInstanceOf(ProPlusAi.class)
        .isNotInstanceOf(games.strategy.triplea.ai.proplus.ProPlusStrategaAi.class);
    assertThat(
            playerTypes.fromLabel("Pro+ Stratega LLM (Europe 1940)").newPlayerWithName("Italians"))
        .isInstanceOf(games.strategy.triplea.ai.proplus.ProPlusStrategaAi.class)
        .isNotInstanceOf(ProPlusEuropa1940Ai.class);
  }
}
