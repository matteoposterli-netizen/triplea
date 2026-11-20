package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P9+P10: the data of {@code ponte_concentrato}, the switch off = nothing changes (ponte_uk as
 * before), on = it replaces ponte_uk, the pure rules of the choice (one target, below the threshold
 * after, the largest drop; already safe is no target) and the counterattack estimate on a built
 * position (Global 1940, same names as Europe 1940).
 */
class PonteConcentratoTest extends AbstractClientSettingTestCase {
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
  void theDataHaveTheValuesTheSwitchIsOffAndNoNameIsInTheCode() throws Exception {
    final PonteConcentrato.Config c = PonteConcentrato.Config.get();
    assertThat(c.diDefault()).isFalse();
    assertThat(c.nazioni()).containsExactly("British");
    assertThat(c.alleatoSbarco()).isEqualTo("Americans");
    assertThat(c.nazioniContrattacco()).containsExactly("Germans", "Italians");
    assertThat(c.unitaMinime()).isEqualTo(6);
    assertThat(c.soglia()).isEqualTo(65);
    assertThat(StrategaInterruttori.PONTE_CONCENTRATO.predefinito()).isFalse();
    assertThat(StrategaInterruttori.of("ponte_concentrato"))
        .contains(StrategaInterruttori.PONTE_CONCENTRATO);
    final String src =
        Files.readString(
            Path.of("src/main/java/games/strategy/triplea/ai/proplus/PonteConcentrato.java"),
            StandardCharsets.UTF_8);
    for (final String word :
        List.of(
            "Americans",
            "British",
            "Russians",
            "Germans",
            "Italians",
            "Neutral_True",
            "United Kingdom",
            "Normandy",
            "Sea Zone")) {
      assertThat(src).as("PonteConcentrato contiene " + word).doesNotContain(word);
    }
  }

  @Test
  void offNothingChangesOnItReplacesPonteUkForTheUkOnly() {
    // off: ponte_uk answers as before, the concentrated bridge never acts
    for (final String n : List.of("Americans", "British", "Russians")) {
      assertThat(PonteConcentrato.attivo(data, player(n))).isFalse();
      assertThat(IndicazioniP7.attivo(data, player(n), StrategaInterruttori.PONTE_UK)).isFalse();
    }
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "ponte_uk");
    assertThat(IndicazioniP7.attivo(data, player("British"), StrategaInterruttori.PONTE_UK))
        .isTrue();
    assertThat(PonteConcentrato.attivo(data, player("British"))).isFalse();
    // both on: ponte_concentrato wins, only for the UK
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "ponte_uk,ponte_concentrato");
    assertThat(PonteConcentrato.attivo(data, player("British"))).isTrue();
    assertThat(IndicazioniP7.attivo(data, player("British"), StrategaInterruttori.PONTE_UK))
        .isFalse();
    assertThat(PonteConcentrato.attivo(data, player("Americans"))).isFalse();
    assertThat(PonteConcentrato.attivo(data, player("Germans"))).isFalse();
    // alone
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "ponte_concentrato");
    assertThat(PonteConcentrato.attivo(data, player("British"))).isTrue();
    // the Axis' switches do not turn it on
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "");
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "ponte_concentrato");
    assertThat(PonteConcentrato.attivo(data, player("British"))).isFalse();
  }

  @Test
  void oneTargetBelowTheThresholdAfterWithTheLargestDrop() {
    final List<PonteConcentrato.Candidato> c =
        List.of(
            new PonteConcentrato.Candidato("A", 90, 60, 8, "sotto_soglia"),
            new PonteConcentrato.Candidato("B", 95, 40, 10, "sotto_soglia"),
            new PonteConcentrato.Candidato("C", 99, 70, 12, "sopra_soglia"),
            new PonteConcentrato.Candidato("D", 50, 10, 6, "gia_sicuro"));
    assertThat(PonteConcentrato.scegli(c, 65))
        .map(PonteConcentrato.Candidato::territorio)
        .contains("B");
    // already below the threshold before: never a target, even with the largest drop
    assertThat(
            PonteConcentrato.scegli(
                List.of(new PonteConcentrato.Candidato("D", 64, 0, 6, "gia_sicuro")), 65))
        .isEmpty();
    // no unit to send: no target
    assertThat(
            PonteConcentrato.scegli(
                List.of(new PonteConcentrato.Candidato("E", 90, 50, 0, "esposti")), 65))
        .isEmpty();
    // ties: by name
    assertThat(
            PonteConcentrato.scegli(
                List.of(
                    new PonteConcentrato.Candidato("Z", 80, 50, 4, "sotto_soglia"),
                    new PonteConcentrato.Candidato("Y", 80, 50, 4, "sotto_soglia")),
                65))
        .map(PonteConcentrato.Candidato::territorio)
        .contains("Y");
  }

  @Test
  void theReasonWhenNothingActs() {
    assertThat(PonteConcentrato.motivo(List.of())).isEqualTo(PonteConcentrato.NESSUN_CANDIDATO);
    assertThat(
            PonteConcentrato.motivo(
                List.of(new PonteConcentrato.Candidato("A", 90, 90, 0, "esposti"))))
        .isEqualTo(PonteConcentrato.ESPOSTI);
    assertThat(
            PonteConcentrato.motivo(
                List.of(new PonteConcentrato.Candidato("A", 30, 10, 5, "gia_sicuro"))))
        .isEqualTo(PonteConcentrato.GIA_SICURO);
    assertThat(
            PonteConcentrato.motivo(
                List.of(
                    new PonteConcentrato.Candidato("A", 30, 10, 5, "gia_sicuro"),
                    new PonteConcentrato.Candidato("B", 99, 90, 5, "sopra_soglia"))))
        .isEqualTo(PonteConcentrato.NESSUNO_SOTTO_SOGLIA);
  }

  @Test
  void theCounterattackDropsWithTheCargo() {
    atWar();
    // an allied bridgehead in Normandy Bordeaux (own units: the test map's alliances at the
    // start are not the ones of a game at war), the Germans next to it
    final Territory t = territory("Normandy Bordeaux");
    data.performChange(ChangeFactory.changeOwner(t, player("British")));
    data.performChange(ChangeFactory.removeUnits(t, new ArrayList<>(t.getUnits())));
    data.performChange(
        ChangeFactory.addUnits(t, unitType("infantry").createTemp(3, player("British"))));
    final ProData uk = proData(player("British"), 6);
    final List<Unit> cargo =
        new ArrayList<>(unitType("infantry").createTemp(40, player("British")));
    cargo.addAll(unitType("armour").createTemp(20, player("British")));
    final JsonObject row = new JsonObject();
    final double[] w =
        PonteConcentrato.contrattacco(
            uk, calc(), t, cargo, Set.of(player("Germans"), player("Italians")), row);
    assertThat(row.get("unita_asse_in_portata").getAsInt()).isPositive();
    assertThat(row.get("difensori_alleati").getAsInt()).isEqualTo(3);
    assertThat(w[0]).isGreaterThan(w[1]);
    assertThat(row.get("contrattacco_nazione").getAsString()).isIn("Germans", "Italians");
    // the defenders count only the allied land and air units there
    assertThat(t.getMatches(Matches.unitIsOwnedBy(player("British")))).hasSize(3);
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

  private games.strategy.engine.data.UnitType unitType(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
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
