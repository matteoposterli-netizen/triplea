package games.strategy.triplea.ai.proplus.strategia;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.Serializable;
import org.junit.jupiter.api.Test;
import org.triplea.io.IoUtils;

/**
 * The general staff: plan chosen, fixed once and saved in the game; the orders it gives per round
 * and per front ({@link PianoAttivo}). Global 1940 renamed as Europe 1940: same European names.
 */
class StatoMaggioreTest {
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final GamePlayer italians = data.getPlayerList().getPlayerId("Italians");
  private final GamePlayer british = data.getPlayerList().getPlayerId("British");
  private final Strategia strategia;

  StatoMaggioreTest() {
    data.setGameName(StrategiaFileTest.EUROPE);
    data.getSequence().setRoundAndStep(1, "Combat Move", germans);
    strategia = Strategie.forGame(data).orElseThrow();
  }

  private PianoAttivo orders(final GamePlayer player) {
    return PianoAttivo.of(data, player).orElseThrow();
  }

  @Test
  void automaticoIsBarbarossaForTheAxisAndGermanyFirstForTheAllies() {
    assertThat(orders(germans).getPiano().id()).isEqualTo("A");
    assertThat(orders(british).getPiano().id()).isEqualTo("1");
    assertThat(orders(germans).getRoundInizio()).isEmpty();
  }

  @Test
  void theChosenPlanIsFixedOnceAndNeverChanges() {
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "B");
    final var fixed = StatoMaggiore.fix(data, "Axis", strategia).orElseThrow();

    assertThat(fixed.piano()).isEqualTo("B");
    assertThat(fixed.roundInizio()).isEqualTo(1);
    assertThat(fixed.storico()).isEmpty();

    StatoMaggiore.setChoice(data.getProperties(), "Axis", "D");
    data.getSequence().setRoundAndStep(4, "Combat Move", germans);
    assertThat(StatoMaggiore.fix(data, "Axis", strategia).orElseThrow()).isEqualTo(fixed);
    assertThat(orders(germans).getPiano().id()).isEqualTo("B");
    assertThat(orders(germans).getRoundInizio()).contains(1);
  }

  @Test
  void stateIsAJsonStringThatSurvivesSaveAndLoad() throws Exception {
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "2");
    StatoMaggiore.fix(data, "Allies", strategia);

    final Serializable stored = data.getProperties().get("Pro+ stato maggiore: Allies");
    assertThat(stored)
        .isExactlyInstanceOf(String.class)
        .isEqualTo("{\"piano\":\"2\",\"round_inizio\":1,\"storico\":[]}");
    assertThat(data.getProperties().get("Pro+ piano: Allies")).isEqualTo("2");

    final byte[] bytes = IoUtils.writeToMemory(os -> GameDataManager.saveGame(os, data));
    final GameData loaded = IoUtils.readFromMemory(bytes, GameDataManager::loadGame).orElseThrow();
    final var state = StatoMaggiore.getState(loaded.getProperties(), "Allies").orElseThrow();
    assertThat(state.piano()).isEqualTo("2");
    assertThat(state.roundInizio()).isEqualTo(1);
  }

  @Test
  void anUnknownPlanFallsBackToAutomatico() {
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "Z");

    assertThat(orders(germans).getPiano().id()).isEqualTo("A");
  }

  @Test
  void effectiveRiskIsTheSliderMovedByTheFront() {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    final var map = data.getMap();
    final PianoAttivo barbarossa = orders(germans);

    assertThat(barbarossa.getFase().label()).isEqualTo("R1-2");
    assertThat(barbarossa.livelloEffettivo(map.getTerritoryOrThrow("France")))
        .isEqualTo(RiskLevel.AUDACE);
    assertThat(barbarossa.livelloEffettivo(map.getTerritoryOrThrow("Poland")))
        .isEqualTo(RiskLevel.CAUTO);
    assertThat(barbarossa.livelloEffettivo(map.getTerritoryOrThrow("Egypt")))
        .isEqualTo(RiskLevel.EQUILIBRATO);
    assertThat(barbarossa.livelloEffettivo(map.getTerritoryOrThrow("110 Sea Zone")))
        .isEqualTo(RiskLevel.EQUILIBRATO);
    assertThat(barbarossa.livelloEffettivo(map.getTerritoryOrThrow("India")))
        .isEqualTo(RiskLevel.EQUILIBRATO);

    data.getSequence().setRoundAndStep(3, "Combat Move", germans);
    final PianoAttivo late = orders(germans);
    assertThat(late.getFase().label()).isEqualTo("R3+");
    assertThat(late.livelloEffettivo(map.getTerritoryOrThrow("France"))).isEqualTo(RiskLevel.CAUTO);
    assertThat(late.livelloEffettivo(map.getTerritoryOrThrow("Poland")))
        .isEqualTo(RiskLevel.AUDACE);
  }

  @Test
  void effectiveRiskStaysOnTheScale() {
    final var map = data.getMap();
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.SPERICOLATO);
    assertThat(orders(germans).livelloEffettivo(map.getTerritoryOrThrow("France")))
        .isEqualTo(RiskLevel.SPERICOLATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.PRUDENTE);
    assertThat(orders(germans).livelloEffettivo(map.getTerritoryOrThrow("Poland")))
        .isEqualTo(RiskLevel.PRUDENTE);
    assertThat(orders(germans).livelloEffettivo(map.getTerritoryOrThrow("France")))
        .isEqualTo(RiskLevel.CAUTO);
  }

  @Test
  void objectivesPerNationAndFleet() {
    final var map = data.getMap();
    final PianoAttivo germanOrders = orders(germans);

    assertThat(germanOrders.peso(map.getTerritoryOrThrow("France"))).isEqualTo(15);
    assertThat(germanOrders.isObiettivo(map.getTerritoryOrThrow("Egypt"))).isFalse();
    final var britishFleetZone =
        map.getTerritories().stream()
            .filter(t -> t.isWater())
            .filter(t -> t.getUnitCollection().anyMatch(u -> u.getOwner().equals(british)))
            .findFirst()
            .orElseThrow();
    assertThat(germanOrders.peso(britishFleetZone)).isEqualTo(10);
    final var emptyZone =
        map.getTerritories().stream()
            .filter(t -> t.isWater() && t.getUnitCollection().isEmpty())
            .findFirst()
            .orElseThrow();
    assertThat(germanOrders.isObiettivo(emptyZone)).isFalse();

    data.getSequence().setRoundAndStep(3, "Combat Move", italians);
    assertThat(orders(italians).isObiettivo(map.getTerritoryOrThrow("Egypt"))).isTrue();
    assertThat(orders(germans).isObiettivo(map.getTerritoryOrThrow("Egypt"))).isFalse();
  }
}
