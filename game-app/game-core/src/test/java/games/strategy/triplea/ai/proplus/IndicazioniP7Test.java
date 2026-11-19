package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P7: the data of the three levers, every switch off = Hard's answers, the pure rules (war on
 * the true neutrals, their land, the ally's landings from the register, the capacity at home, the
 * exposure check) and the levers on (Global 1940, same names as Europe 1940).
 */
class IndicazioniP7Test extends AbstractClientSettingTestCase {
  private static final long SEED = 20261121L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    for (final String n : List.of("Americans", "British", "Russians", "Germans", "Italians")) {
      Stratega.registra(data, player(n));
    }
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && !t.getOwner().isNull()) {
        data.performChange(OriginalOwnerTracker.addOriginalOwnerChange(t, t.getOwner()));
      }
    }
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

  @Test
  void theDataHaveTheValuesTheSwitchesAreOffAndNoNameIsInTheCode() throws Exception {
    final IndicazioniP7.Config c = IndicazioniP7.Config.get();
    assertThat(c.neutraliDiDefault()).isFalse();
    assertThat(c.ponteDiDefault()).isFalse();
    assertThat(c.prontiDiDefault()).isFalse();
    assertThat(c.nazioniNeutrali()).containsExactly("Americans", "British", "French", "Russians");
    assertThat(c.nazioniPonte()).containsExactly("British");
    assertThat(c.unitaMinime()).isEqualTo(6);
    assertThat(c.quotaPct()).isEqualTo(40);
    assertThat(c.pesoAttacco()).isEqualTo(40);
    assertThat(StrategaInterruttori.NIENTE_NEUTRALI.predefinito()).isFalse();
    assertThat(StrategaInterruttori.PONTE_UK.predefinito()).isFalse();
    assertThat(StrategaInterruttori.PONTE_PRONTI.predefinito()).isFalse();
    for (final String file : List.of("IndicazioniP7", "PonteRegnoUnito")) {
      final String src =
          Files.readString(
              Path.of("src/main/java/games/strategy/triplea/ai/proplus/" + file + ".java"),
              StandardCharsets.UTF_8);
      for (final String word :
          List.of(
              "Americans",
              "British",
              "Russians",
              "Germans",
              "Neutral_True",
              "United Kingdom",
              "Spain",
              "Sea Zone")) {
        assertThat(src).as(file + " contiene " + word).doesNotContain(word);
      }
    }
  }

  @Test
  void withTheSwitchesOffEveryAnswerIsHards() {
    final PoliticalActionAttachment war = neutralWar("British");
    for (final String n : List.of("Americans", "British", "Russians")) {
      final GamePlayer p = player(n);
      final ProData proData = proData(p, 5);
      final ProTuning tuning = proData.getTuning();
      for (final StrategaInterruttori s :
          List.of(
              StrategaInterruttori.NIENTE_NEUTRALI,
              StrategaInterruttori.PONTE_UK,
              StrategaInterruttori.PONTE_PRONTI)) {
        assertThat(IndicazioniP7.attivo(data, p, s)).isFalse();
      }
      assertThat(tuning.allowsPoliticalAction(proData, war)).isTrue();
      assertThat(tuning.excludesAttackTarget(proData, territory("Spain"))).isFalse();
      final IndicazioniP7.Ponte st = new IndicazioniP7.Ponte();
      st.imposta(5, Map.of("Normandy Bordeaux", 8));
      assertThat(IndicazioniP7.pesoAttacco(proData, territory("Normandy Bordeaux"), st)).isZero();
      assertThat(IndicazioniP7.ordini(proData)).isEmpty();
      assertThat(IndicazioniP7.sbarchiDelRound(data, p)).isEmpty();
    }
    assertThat(ProTuning.DEFAULT.allowsPoliticalAction(null, war)).isTrue();
    assertThat(ProTuning.DEFAULT.excludesAttackTarget(null, territory("Spain"))).isFalse();
  }

  @Test
  void noNeutralsLeavesOutTheWarOnTheTrueNeutralsAndTheirLandOnly() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "niente_neutrali");
    final PoliticalActionAttachment war = neutralWar("British");
    assertThat(IndicazioniP7.guerraAlNeutrale(war, "Neutral_True")).isTrue();
    final ProData uk = proData(player("British"), 3);
    assertThat(uk.getTuning().allowsPoliticalAction(uk, war)).isFalse();
    assertThat(uk.getTuning().excludesAttackTarget(uk, territory("Spain"))).isTrue();
    assertThat(uk.getTuning().excludesAttackTarget(uk, territory("Sweden"))).isTrue();
    // a former neutral of the Axis stays a target; the Axis' own land too
    data.performChange(ChangeFactory.changeOwner(territory("Spain"), player("Germans")));
    assertThat(uk.getTuning().excludesAttackTarget(uk, territory("Spain"))).isFalse();
    assertThat(uk.getTuning().excludesAttackTarget(uk, territory("Normandy Bordeaux"))).isFalse();
    // other political actions are still allowed
    for (final PoliticalActionAttachment a :
        PoliticalActionAttachment.getPoliticalActionAttachments(player("British"))) {
      if (!IndicazioniP7.guerraAlNeutrale(a, "Neutral_True")) {
        assertThat(uk.getTuning().allowsPoliticalAction(uk, a)).isTrue();
      }
    }
    // the Axis is not concerned
    final ProData de = proData(player("Germans"), 3);
    assertThat(de.getTuning().allowsPoliticalAction(de, neutralWar("Germans"))).isTrue();
  }

  @Test
  void theAllysLandingsComeFromTheRegisterOfTheRoundWithAtLeastSixUnits() {
    final List<JsonObject> rows =
        List.of(
            row("Americans", 5, "SBARCO_IN_FORZA", "Normandy Bordeaux", 4),
            row("Americans", 5, "SBARCO_DOPO_ATTESA", "Normandy Bordeaux", 3),
            row("Americans", 5, "SBARCO_IN_FORZA", "Holland Belgium", 5),
            row("Americans", 4, "SBARCO_IN_FORZA", "France", 9),
            row("British", 5, "SBARCO_IN_FORZA", "Norway", 9),
            row("Americans", 5, "ATTESA", "Denmark", 9));
    assertThat(IndicazioniP7.sbarchi(rows, "Americans", 5, 6))
        .isEqualTo(Map.of("Normandy Bordeaux", 7));
  }

  @Test
  void theBridgeheadWeightActsOnTheRoundsEnemyLandingOnly() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "ponte_uk");
    atWar();
    data.performChange(
        ChangeFactory.changeOwner(territory("Normandy Bordeaux"), player("Germans")));
    final ProData uk = proData(player("British"), 5);
    final IndicazioniP7.Ponte st = new IndicazioniP7.Ponte();
    st.imposta(5, Map.of("Normandy Bordeaux", 8));
    assertThat(IndicazioniP7.pesoAttacco(uk, territory("Normandy Bordeaux"), st)).isEqualTo(40);
    // another territory, another round, an allied one: no weight
    assertThat(IndicazioniP7.pesoAttacco(uk, territory("Holland Belgium"), st)).isZero();
    st.imposta(4, Map.of("Normandy Bordeaux", 8));
    assertThat(IndicazioniP7.pesoAttacco(uk, territory("Normandy Bordeaux"), st)).isZero();
    st.imposta(5, Map.of("Gibraltar", 8));
    assertThat(IndicazioniP7.pesoAttacco(uk, territory("Gibraltar"), st)).isZero();
    // the USA are not concerned
    assertThat(
            IndicazioniP7.pesoAttacco(
                proData(player("Americans"), 5), territory("Normandy Bordeaux"), st))
        .isZero();
  }

  @Test
  void theIndicationStopsOverTheCapacityAndTheExposureIsHardsCheck() {
    assertThat(IndicazioniP7.sottoCapacita(10, 10)).isTrue();
    assertThat(IndicazioniP7.sottoCapacita(11, 10)).isFalse();
    assertThat(IndicazioniP7.sottoCapacita(0, 0)).isTrue();
    assertThat(PonteRegnoUnito.valore(20, 3, false)).isEqualTo(23);
    assertThat(PonteRegnoUnito.valore(20, 3, true)).isEqualTo(32);
    assertThat(PonteRegnoUnito.esposti(40, 29, 0.75)).isTrue();
    assertThat(PonteRegnoUnito.esposti(40, 30, 0.75)).isFalse();
  }

  @Test
  void theReadyTroopsAreAnIndicationAtHomeWithTheUsaAtWar() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "ponte_pronti");
    final ProData neutral = proData(player("British"), 2);
    if (!IndicazioniP7.alleatoInGuerra(data)) {
      assertThat(IndicazioniP7.ordini(neutral)).isEmpty();
    }
    atWar();
    final ProData uk = proData(player("British"), 3);
    final Territory home = territory("United Kingdom");
    final int places = IndicazioniP7.postiInPatria(home, player("British"));
    final int cap = IndicazioniP7.capacitaAccanto(data, home, player("British"));
    final var orders = IndicazioniP7.ordini(uk);
    assertThat(orders.isPresent()).isEqualTo(places <= cap);
    orders.ifPresent(
        o -> {
          assertThat(o.mix()).hasSize(1);
          assertThat(o.mix().get(0).percent()).isEqualTo(40);
          assertThat(o.mandatory()).isEmpty();
          assertThat(o.minimumShare()).isZero();
          assertThat(o.preferredPlacement()).containsExactly("United Kingdom");
        });
    // the USA's purchase is not concerned
    assertThat(IndicazioniP7.ordini(proData(player("Americans"), 3))).isEmpty();
  }

  // ============================================================================ helpers

  private static JsonObject row(
      final String nation, final int round, final String code, final String t, final int n) {
    final JsonObject p = new JsonObject();
    p.addProperty("bersaglio", t);
    p.addProperty("unita_sbarcate", n);
    return RegistroDecisioni.decisione(
        "x",
        round,
        nation,
        SbarcoConcentrato.TIPO,
        new com.google.gson.JsonArray(),
        code,
        code,
        "",
        p);
  }

  private PoliticalActionAttachment neutralWar(final String nation) {
    return PoliticalActionAttachment.getPoliticalActionAttachments(player(nation)).stream()
        .filter(a -> IndicazioniP7.guerraAlNeutrale(a, "Neutral_True"))
        .findFirst()
        .orElseThrow();
  }

  private void atWar() {
    for (final String a : List.of("Americans", "British", "Russians")) {
      for (final String e : List.of("Germans", "Italians")) {
        if (!player(a).isAtWar(player(e))) {
          data.performChange(
              ChangeFactory.relationshipChange(
                  player(a),
                  player(e),
                  data.getRelationshipTracker().getRelationshipType(player(a), player(e)),
                  data.getRelationshipTypeList().getDefaultWarRelationship()));
        }
      }
    }
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrNull(name);
  }

  private ProData proData(final GamePlayer player, final int round) {
    data.getSequence().setRoundAndStep(round, "Purchase", player);
    final ProPlusTuning plus = new ProPlusTuning();
    final ProData proData = new ProData(false, plus);
    plus.attach(proData);
    proData.initializeSimulation(
        new TestAi(player.getName(), proData, new SeededBattleCalculator(SEED)), data, player);
    return proData;
  }

  private static final class TestAi extends AbstractProAi {
    private final SeededBattleCalculator calculator;

    TestAi(final String name, final ProData proData, final SeededBattleCalculator calculator) {
      super(name, calculator, proData, ProPlusAi.PLAYER_LABEL);
      this.calculator = calculator;
    }

    @Override
    protected void prepareData(final GameData data) {
      calculator.setGameData(data).join();
    }
  }
}
