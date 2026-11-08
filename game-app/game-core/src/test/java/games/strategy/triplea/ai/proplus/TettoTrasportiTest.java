package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.Test;

/**
 * Guard rail of the strategic contract: the transport loss accepted beyond the safety rule is at
 * most 30 TUV per alliance per round, every landing of every nation summed.
 */
class TettoTrasportiTest {
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer british = data.getPlayerList().getPlayerId("British");
  private final GamePlayer americans = data.getPlayerList().getPlayerId("Americans");
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final Territory normandy = data.getMap().getTerritoryOrThrow("Normandy Bordeaux");
  private final Territory norway = data.getMap().getTerritoryOrThrow("Norway");

  @Test
  void theCapIsSharedByEveryLandingOfTheAllianceInTheRound() {
    data.getSequence().setRoundAndStep(5, "Combat Move", british);
    assertThat(TettoTrasporti.residuo(data, british, normandy)).isEqualTo(30.0);

    TettoTrasporti.registra(data, british, normandy, 20);

    // another landing of the same nation and a landing of another nation of the alliance
    assertThat(TettoTrasporti.residuo(data, british, norway)).isEqualTo(10.0);
    assertThat(TettoTrasporti.residuo(data, americans, normandy)).isEqualTo(10.0);
    // the same landing checked again by the planning: not counted twice
    assertThat(TettoTrasporti.residuo(data, british, normandy)).isEqualTo(30.0);
    TettoTrasporti.registra(data, british, normandy, 25);
    assertThat(TettoTrasporti.usato(data, americans)).isEqualTo(25.0);
    // the enemy alliance has its own cap
    assertThat(TettoTrasporti.residuo(data, germans, normandy)).isEqualTo(30.0);
  }

  @Test
  void aNewRoundStartsFromZero() {
    data.getSequence().setRoundAndStep(5, "Combat Move", british);
    TettoTrasporti.registra(data, americans, normandy, 30);
    assertThat(TettoTrasporti.residuo(data, british, norway)).isZero();

    data.getSequence().setRoundAndStep(6, "Combat Move", british);

    assertThat(TettoTrasporti.residuo(data, british, norway)).isEqualTo(30.0);
  }
}
