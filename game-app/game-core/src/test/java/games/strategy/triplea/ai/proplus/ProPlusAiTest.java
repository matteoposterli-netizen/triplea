package games.strategy.triplea.ai.proplus;

import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.GameDataTestUtil.unitType;
import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplea.io.IoUtils;

class ProPlusAiTest {

  @Test
  void isAStockHardAi() {
    final ProPlusAi ai = new ProPlusAi("Germans", ProPlusAi.PLAYER_LABEL);

    assertThat(ai).isInstanceOf(ProAi.class);
    assertThat(ai.isAi()).isTrue();
    assertThat(ai.getPlayerLabel()).isEqualTo("Pro+ (AI)");
    assertThat(ai.getProData().isBeta()).as("Pro+ must not switch on Hard Beta").isFalse();
  }

  @Test
  void answersLikeHardAi() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    final Territory unitedKingdom = territory("United Kingdom", data);
    final List<Unit> fighters = unitType("fighter", data).create(3, british(data));

    assertThat(
            new ProPlusAi("British", ProPlusAi.PLAYER_LABEL)
                .selectUnitsQuery(unitedKingdom, fighters, "Select Air to Intercept"))
        .isEqualTo(
            new ProAi("British", "Hard (AI)")
                .selectUnitsQuery(unitedKingdom, fighters, "Select Air to Intercept"));
  }

  @Test
  void labelIsUnknownToOfficialBuiltInTypes() {
    // Official TripleA only knows the built-in types (plus Human, Hard Beta, ...): a save that says
    // "Pro+ (AI)" must go through the setup screen's fallback for unknown labels.
    assertThat(new PlayerTypes(PlayerTypes.getBuiltInPlayerTypes()).getAvailablePlayerLabels())
        .doesNotContain(ProPlusAi.PLAYER_LABEL);
  }

  @Test
  void savegameKeepsProPlusPlayerType() throws Exception {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    germans.setWhoAmI("AI:" + ProPlusAi.PLAYER_LABEL);

    final byte[] bytes = IoUtils.writeToMemory(os -> GameDataManager.saveGame(os, data));
    final GameData loaded = IoUtils.readFromMemory(bytes, GameDataManager::loadGame).orElseThrow();

    assertThat(GamePlayer.currentPlayers(loaded)).containsEntry("Germans", "Pro+ (AI)");
    assertThat(loaded.getPlayerList().getPlayerId("Germans").isAi()).isTrue();
  }
}
