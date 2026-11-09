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
    assertThat(ProPlusTuning.unwrap(ai.getProData().getTuning())).isInstanceOf(ProPlusTuning.class);
    assertThat(ControlloTuning.of(ai.getProData().getTuning()))
        .as("every answer checked against Hard's (step PROVA DI VALORE)")
        .isNotNull();
  }

  @Test
  void turnStartLineShowsAllianceRiskAndThresholds() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.AUDACE);

    assertThat(ProPlusAi.turnStartLine(data, data.getPlayerList().getPlayerId("Germans")))
        .isEqualTo(
            "[Pro+] Germans round 1, alliance Axis, risk 4 Audace,"
                + " attack win% desired 72 / minimum 52 (normal dice), no plan");
    assertThat(ProPlusAi.turnStartLine(data, data.getPlayerList().getPlayerId("British")))
        .contains("alliance Allies, risk 3 Equilibrato, attack win% desired 80 / minimum 60");
  }

  @Test
  void firstTurnFixesThePlanAndTheLineShowsIt() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    data.setGameName("World War II Europe 1940 2nd Edition");
    final var germans = data.getPlayerList().getPlayerId("Germans");

    ProPlusAi.fixPlan(data, germans);

    assertThat(ProPlusAi.turnStartLine(data, germans))
        .contains(
            ", plan A Barbarossa, fase R1-2 (fixed in round 1), fronts Ovest 4 Audace,"
                + " Est 2 Cauto, Mediterraneo-Africa 3 Equilibrato, Mari 3 Equilibrato,"
                + " Altro 3 Equilibrato, objectives France 15.0, flotta British 10.0");
    assertThat(data.getProperties().get("Pro+ stato maggiore: Axis"))
        .isEqualTo("{\"piano\":\"A\",\"round_inizio\":1,\"storico\":[]}");
    assertThat(data.getProperties().get("Pro+ stato maggiore: Allies")).isNull();
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
