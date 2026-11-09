package games.strategy.triplea.ai.proplus.strategia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.proplus.Ablazione;
import games.strategy.triplea.ai.proplus.Ablazione.Componente;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.TettoTrasporti;
import games.strategy.triplea.ai.proplus.strategia.Strategia.Obiettivo;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step ABLAZIONE: the switches that turn one Pro+ component off. Without the property every
 * component is on (the behavior before the step, kept by the golden tests); each switch turns its
 * hook back to Hard's value. Global 1940 renamed as Europe 1940 (same European names).
 */
class AblazioneTest {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
  private final GamePlayer americans = data.getPlayerList().getPlayerId("Americans");
  private Strategia strategia;

  @BeforeEach
  void setUp() {
    data.setGameName(EUROPE);
    strategia = Strategie.forGame(data).orElseThrow();
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "A");
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    StatoMaggiore.fix(data, "Axis", strategia);
    StatoMaggiore.fix(data, "Allies", strategia);
  }

  @Test
  void theIdsParseAndNudoTurnsEverythingOff() {
    assertThat(Ablazione.parse("")).isEmpty();
    assertThat(Ablazione.parse("rallentamento, rischio_fronte"))
        .containsExactlyInAnyOrder(Componente.RALLENTAMENTO, Componente.RISCHIO_FRONTE);
    assertThat(Ablazione.parse("nudo")).isEqualTo(EnumSet.allOf(Componente.class));
    assertThatThrownBy(() -> Ablazione.parse("rallentamento,boh"))
        .hasMessageContaining("componente sconosciuto: boh")
        .hasMessageContaining("soste_sicure");
    assertThatThrownBy(() -> Ablazione.set(data.getProperties(), "Axis", "boh"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withoutThePropertyEveryComponentIsOn() {
    for (final Componente c : Componente.values()) {
      assertThat(Ablazione.attivo(data.getProperties(), "Axis", c)).isTrue();
      assertThat(Ablazione.attivo(data, russians, c)).isTrue();
    }
    // set and cleared again: back to every component on
    Ablazione.set(data.getProperties(), "Axis", "nudo");
    assertThat(Ablazione.spenti(data.getProperties(), "Axis")).hasSize(Componente.values().length);
    Ablazione.set(data.getProperties(), "Axis", "");
    assertThat(Ablazione.spenti(data.getProperties(), "Axis")).isEmpty();
  }

  @Test
  void withoutTheGeneralStaffThereIsNoPlan() {
    data.getSequence().setRoundAndStep(1, "Purchase", germans);
    assertThat(PianoAttivo.of(data, germans)).isPresent();
    Ablazione.set(data.getProperties(), "Axis", "piano");
    assertThat(PianoAttivo.of(data, germans)).isEmpty();
    // the other alliance keeps its plan
    data.getSequence().setRoundAndStep(1, "Purchase", russians);
    assertThat(PianoAttivo.of(data, russians)).isPresent();
  }

  @Test
  void withoutTheFrontModifiersEveryFrontIsAtTheSliderLevel() {
    data.getSequence().setRoundAndStep(1, "Purchase", germans);
    // plan A, R1-2: Ovest +1, Est -1
    final PianoAttivo before = PianoAttivo.of(strategia, data, germans, "Axis").orElseThrow();
    assertThat(before.livelliPerFronte()).containsEntry("Ovest", RiskLevel.AUDACE);
    assertThat(before.livelliPerFronte()).containsEntry("Est", RiskLevel.CAUTO);
    Ablazione.set(data.getProperties(), "Axis", "rischio_fronte");
    final PianoAttivo after = PianoAttivo.of(strategia, data, germans, "Axis").orElseThrow();
    assertThat(after.livelliPerFronte().values()).containsOnly(RiskLevel.EQUILIBRATO);
    // the objectives stay
    assertThat(after.getObiettivi()).isEqualTo(before.getObiettivi());
  }

  @Test
  void withoutFleetObjectivesTheBritishFleetIsNoTarget() {
    data.getSequence().setRoundAndStep(1, "Purchase", germans);
    final PianoAttivo before = PianoAttivo.of(strategia, data, germans, "Axis").orElseThrow();
    assertThat(before.getObiettivi()).extracting(Obiettivo::flotta).contains("British");
    Ablazione.set(data.getProperties(), "Axis", "bersagli_flotta");
    final PianoAttivo after = PianoAttivo.of(strategia, data, germans, "Axis").orElseThrow();
    assertThat(after.getObiettivi()).extracting(Obiettivo::flotta).containsOnlyNulls();
    assertThat(after.getObiettivi()).extracting(Obiettivo::territorio).contains("France");
    // the front modifiers stay
    assertThat(after.livelliPerFronte()).isEqualTo(before.livelliPerFronte());
  }

  @Test
  void withoutAmphibiousOperationsThePlanHasNone() {
    data.getSequence().setRoundAndStep(4, "Purchase", americans);
    assertThat(PianoAttivo.of(strategia, data, americans, "Allies").orElseThrow().getOperazioni())
        .isNotEmpty();
    Ablazione.set(data.getProperties(), "Allies", "operazioni_anfibie");
    assertThat(PianoAttivo.of(strategia, data, americans, "Allies").orElseThrow().getOperazioni())
        .isEmpty();
  }

  @Test
  void eachTuningSwitchGivesBackHardsValue() {
    data.getSequence().setRoundAndStep(1, "Combat Move", germans);
    final ProPlusTuning tuning = new ProPlusTuning();
    final ProData proData = new ProData(false, tuning);
    tuning.attach(proData);
    proData.initializeSimulation(null, data, germans);
    final Territory france = data.getMap().getTerritoryOrThrow("France");
    final ProTuning hard = ProTuning.DEFAULT;

    // every component on: Pro+'s values
    assertThat(tuning.attackWinPercentage(data, germans, 90)).isEqualTo(80);
    assertThat(tuning.attackPriorityVictoryCityWeight()).isPositive();
    assertThat(tuning.capitalGuardThreshold(proData)).isPresent();
    assertThat(tuning.realEnemies(proData, germans)).isPresent();
    assertThat(tuning.checksLandingScramble(false)).isTrue();
    assertThat(tuning.dropsAmphibiousStrafe(proData, france, 1)).isTrue();
    assertThat(tuning.purchaseOrders(proData)).isPresent();
    assertThat(tuning.stagingOrders(proData)).isPresent();

    Ablazione.set(
        data.getProperties(),
        "Axis",
        "soglie_rischio,victory_city,guarnigione_capitale,minaccia_reale,scramble_sbarchi,"
            + "strafe_anfibio,logistica_acquisti,ammassamento");
    assertThat(tuning.attackWinPercentage(data, germans, 90)).isEqualTo(90);
    assertThat(tuning.attackMinWinPercentage(data, germans, 65)).isEqualTo(65);
    assertThat(tuning.targetAttackWinPercentage(proData, france, 90)).isEqualTo(90);
    assertThat(tuning.targetAttackMinWinPercentage(proData, france, 65)).isEqualTo(65);
    assertThat(tuning.attackWinPercentageCeiling(proData, 90)).isEqualTo(90);
    assertThat(tuning.attackPriorityVictoryCityWeight())
        .isEqualTo(hard.attackPriorityVictoryCityWeight());
    assertThat(tuning.valuesVictoryCities(false)).isEqualTo(hard.valuesVictoryCities(false));
    assertThat(tuning.hasVictoryCityWinCondition(data.getProperties()))
        .isEqualTo(hard.hasVictoryCityWinCondition(data.getProperties()));
    assertThat(tuning.capitalGuardThreshold(proData)).isEmpty();
    assertThat(tuning.realEnemies(proData, germans)).isEmpty();
    assertThat(tuning.checksLandingScramble(false)).isFalse();
    assertThat(tuning.dropsAmphibiousStrafe(proData, france, 1)).isFalse();
    assertThat(tuning.purchaseOrders(proData)).isEmpty();
    assertThat(tuning.stagingOrders(proData)).isEmpty();
  }

  @Test
  void withoutTheGuardRailTheAllianceCapIsGone() {
    data.getSequence().setRoundAndStep(3, "Combat Move", americans);
    final Territory normandy = data.getMap().getTerritoryOrThrow("Normandy Bordeaux");
    TettoTrasporti.registra(data, americans, data.getMap().getTerritoryOrThrow("Norway"), 30);
    assertThat(TettoTrasporti.residuo(data, americans, normandy)).isZero();
    Ablazione.set(data.getProperties(), "Allies", "tetto_trasporti");
    assertThat(TettoTrasporti.residuo(data, americans, normandy)).isEqualTo(Double.MAX_VALUE);
  }

  @Test
  void aGameSavedWithAnUnknownIdKeepsTheKnownOnes() {
    data.getProperties().set(Ablazione.PROPERTY_PREFIX + "Axis", "rallentamento,futuro");
    assertThat(Ablazione.spenti(data.getProperties(), "Axis"))
        .isEqualTo(Set.of(Componente.RALLENTAMENTO));
  }
}
