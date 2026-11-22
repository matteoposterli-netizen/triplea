package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P13: the data of the three levers, all off = nothing acts (no switch, no raid, no purchase,
 * no register row), each lever only for its nation, the pure rules (fighters of the capital, one
 * bomber) and the factory's minimum garrison on a built position (Global 1940, same names as Europe
 * 1940).
 */
class LeveP13Test extends AbstractClientSettingTestCase {
  private static final long SEED = 20261122L;

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
    final LeveP13.Config c = LeveP13.Config.get();
    assertThat(c.tarantoNazione()).isEqualTo("British");
    assertThat(c.tarantoRound()).isEqualTo(1);
    assertThat(c.zonaBersaglio()).isEqualTo("97 Sea Zone");
    assertThat(c.zonaFlotta()).isEqualTo("98 Sea Zone");
    assertThat(c.baseAerea()).isEqualTo("Malta");
    assertThat(c.maxCacciaCapitale()).isEqualTo(2);
    assertThat(c.fabbricheNazione()).isEqualTo("Germans");
    assertThat(c.bombNemico()).isEqualTo("Russians");
    assertThat(c.bombardieriMinimi()).isEqualTo(3);
    assertThat(c.sogliaCapitale()).isEqualTo(30);
    assertThat(c.sogliaAttacco()).isEqualTo(75);
    assertThat(c.sogliaContrattacco()).isEqualTo(30);
    assertThat(c.sogliaScortaHard()).isEqualTo(65);
    for (final StrategaInterruttori s : LeveP13.LEVE) {
      assertThat(s.predefinito()).as(s.id()).isFalse();
      assertThat(StrategaInterruttori.of(s.id())).contains(s);
    }
    for (final String file :
        List.of("LeveP13", "Taranto", "FabbricheTenute", "BombardamentoMosca")) {
      final String src =
          Files.readString(
              Path.of("src/main/java/games/strategy/triplea/ai/proplus/" + file + ".java"),
              StandardCharsets.UTF_8);
      for (final String word :
          List.of(
              "\"British\"",
              "\"Germans\"",
              "\"Russians\"",
              "\"Italians\"",
              "\"Malta\"",
              "\"United Kingdom\"",
              "\"Russia\"",
              "Sea Zone\"",
              "\"bomber\"",
              "\"fighter\"",
              "\"infantry\"")) {
        assertThat(src).as(file + " contiene " + word).doesNotContain(word);
      }
    }
  }

  @Test
  void allOffNothingActs() {
    for (final String n : List.of("British", "Germans", "Italians", "Russians", "Americans")) {
      assertThat(Taranto.attivo(data, player(n))).as(n).isFalse();
      assertThat(FabbricheTenute.attivo(data, player(n))).as(n).isFalse();
      assertThat(BombardamentoMosca.attivo(data, player(n))).as(n).isFalse();
    }
    // the Axis' commitment and siege and the final Allies do not turn them on
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio");
    StrategaInterruttori.accendi(
        data.getProperties(),
        "Allies",
        "usa_proporzione,sbarco_concentrato,niente_neutrali,norvegia_presto,norvegia_esposta,"
            + "ponte_concentrato");
    data.getSequence().setRoundAndStep(1, "Purchase", player("British"));
    assertThat(Taranto.attivo(data, player("British"))).isFalse();
    assertThat(FabbricheTenute.attivo(data, player("Germans"))).isFalse();
    assertThat(BombardamentoMosca.attivo(data, player("Germans"))).isFalse();
    final ProData pd = proData(player("Germans"), 3);
    assertThat(BombardamentoMosca.trattenute(pd)).isEmpty();
    final Map<Territory, ProTerritory> attacks = new HashMap<>();
    BombardamentoMosca.bombarda(pd, attacks);
    assertThat(attacks).isEmpty();
    final int rows = RegistroDecisioni.righe(data).size();
    BombardamentoMosca.esito(data, player("Germans"));
    FabbricheTenute.dopoPiazzamento(data, player("Germans"));
    assertThat(RegistroDecisioni.righe(data)).hasSize(rows);
  }

  @Test
  void eachLeverIsOnlyItsNations() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "taranto");
    data.getSequence().setRoundAndStep(1, "Purchase", player("British"));
    assertThat(Taranto.attivo(data, player("British"))).isTrue();
    assertThat(Taranto.attivo(data, player("Americans"))).isFalse();
    data.getSequence().setRoundAndStep(2, "Purchase", player("British"));
    assertThat(Taranto.attivo(data, player("British"))).as("solo al primo round").isFalse();
    // the German levers need the siege
    StrategaInterruttori.accendi(
        data.getProperties(), "Axis", "fabbriche_tenute,bombardamento_mosca");
    assertThat(FabbricheTenute.attivo(data, player("Germans"))).isFalse();
    StrategaInterruttori.accendi(
        data.getProperties(), "Axis", "impegno,assedio,fabbriche_tenute,bombardamento_mosca");
    assertThat(FabbricheTenute.attivo(data, player("Germans"))).isTrue();
    assertThat(BombardamentoMosca.attivo(data, player("Germans"))).isTrue();
    assertThat(FabbricheTenute.attivo(data, player("Italians"))).isFalse();
    assertThat(BombardamentoMosca.attivo(data, player("Italians"))).isFalse();
    // the Allies' switches never turn on the Axis' levers, nor the other way round
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio,taranto");
    StrategaInterruttori.accendi(
        data.getProperties(), "Allies", "fabbriche_tenute,bombardamento_mosca");
    data.getSequence().setRoundAndStep(1, "Purchase", player("British"));
    assertThat(Taranto.attivo(data, player("British"))).isFalse();
    assertThat(FabbricheTenute.attivo(data, player("Germans"))).isFalse();
  }

  @Test
  void theCapitalsFightersAndTheBomberPurchaseFollowTheRules() {
    assertThat(Taranto.cacciaDaMandare(2, 10, 5, 30)).isEqualTo(2);
    assertThat(Taranto.cacciaDaMandare(2, 40, 20, 30)).isEqualTo(1);
    assertThat(Taranto.cacciaDaMandare(2, 40, 35, 30)).isZero();
    assertThat(Taranto.cacciaDaMandare(1, 10, 5, 30)).isEqualTo(1);
    assertThat(Taranto.cacciaDaMandare(0, 0, 0, 30)).isZero();
    assertThat(BombardamentoMosca.daComprare(0, 3, 0)).isEqualTo(1);
    assertThat(BombardamentoMosca.daComprare(2, 3, 0)).isEqualTo(1);
    assertThat(BombardamentoMosca.daComprare(3, 3, 0)).isZero();
    assertThat(BombardamentoMosca.daComprare(1, 3, 1)).isZero();
  }

  @Test
  void theFactorysGarrisonIsZeroWithoutAThreatAndGrowsWithOne() {
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio,fabbriche_tenute");
    atWar();
    final Territory ukraine = territory("Ukraine");
    data.performChange(ChangeFactory.changeOwner(ukraine, player("Germans")));
    data.performChange(ChangeFactory.removeUnits(ukraine, new ArrayList<>(ukraine.getUnits())));
    data.performChange(
        ChangeFactory.addUnits(
            ukraine,
            data.getUnitTypeList()
                .getUnitTypeOrThrow("factory_minor")
                .createTemp(1, player("Germans"))));
    final Territory next = territory("Rostov");
    data.performChange(ChangeFactory.changeOwner(next, player("Russians")));
    data.performChange(ChangeFactory.removeUnits(next, new ArrayList<>(next.getUnits())));
    data.performChange(
        ChangeFactory.addUnits(
            next,
            data.getUnitTypeList().getUnitTypeOrThrow("armour").createTemp(5, player("Russians"))));
    final ProData pd = proData(player("Germans"), 4);
    final FabbricheTenute.Guarnigione g =
        FabbricheTenute.guarnigione(pd, calc(), ukraine, List.of(), List.of());
    assertThat(g.minaccia()).isTrue();
    assertThat(g.mancanti()).isPositive();
    assertThat(g.dopo()).isLessThan(30);
    assertThat(g.prima()).isGreaterThanOrEqualTo(g.dopo());
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

  private ProOddsCalculator calc() {
    final SeededBattleCalculator calculator = new SeededBattleCalculator(SEED);
    calculator.setGameData(data).join();
    return new ProOddsCalculator(calculator);
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
