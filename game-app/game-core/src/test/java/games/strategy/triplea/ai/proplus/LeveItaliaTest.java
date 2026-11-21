package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProTuning;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P12: the data of Italy's four levers, all off = nothing acts (no order, no weight, Hard buys
 * its ships, no register row), the pure rules of the balance (one sea task, one transport, the
 * warships' share capped) and the capital's minimum garrison on a built position (Global 1940, same
 * names as Europe 1940).
 */
class LeveItaliaTest extends AbstractClientSettingTestCase {
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
    final LeveItalia.Config c = LeveItalia.Config.get();
    assertThat(c.nazione()).isEqualTo("Italians");
    assertThat(c.alleato()).isEqualTo("Germans");
    assertThat(c.capitale()).isEqualTo("Southern Italy");
    assertThat(c.egitto()).isEqualTo("Egypt");
    assertThat(c.gibilterra()).isEqualTo("Gibraltar");
    assertThat(c.zonaStretto()).isEqualTo("92 Sea Zone");
    assertThat(c.nordAfrica()).hasSize(6).doesNotContain("Egypt");
    assertThat(c.sogliaMinaccia()).isEqualTo(30);
    assertThat(c.quotaNavi()).isEqualTo(30);
    assertThat(c.trasportiMinimi()).isEqualTo(2);
    for (final StrategaInterruttori s : LeveItalia.LEVE) {
      assertThat(s.predefinito()).as(s.id()).isFalse();
      assertThat(StrategaInterruttori.of(s.id())).contains(s);
    }
    final String src =
        Files.readString(
            Path.of("src/main/java/games/strategy/triplea/ai/proplus/LeveItalia.java"),
            StandardCharsets.UTF_8);
    for (final String word :
        List.of(
            "\"Italians\"",
            "\"Germans\"",
            "\"Southern Italy\"",
            "\"Northern Italy\"",
            "\"Egypt\"",
            "\"Gibraltar\"",
            "\"Russia\"",
            "Sea Zone",
            "\"infantry\"",
            "\"transport\"")) {
      assertThat(src).as("LeveItalia contiene " + word).doesNotContain(word);
    }
  }

  @Test
  void allOffNothingActs() {
    final GamePlayer italy = player("Italians");
    assertThat(LeveItalia.alcuna(data, italy)).isFalse();
    assertThat(LeveItalia.mare(data, italy)).isFalse();
    assertThat(LeveItalia.tedeschiPerRoma(data, player("Germans"))).isFalse();
    // the Axis' commitment and siege do not turn them on
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio");
    assertThat(LeveItalia.alcuna(data, italy)).isFalse();
    final ProData pd = proData(italy, 3);
    final LeveItalia.Stato st = new LeveItalia.Stato();
    assertThat(LeveItalia.ordini(pd, st, Optional::empty)).isEmpty();
    for (final Territory t : data.getMap().getTerritories()) {
      assertThat(LeveItalia.pesoAttacco(pd, t)).isZero();
    }
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(pd.getTuning());
    assertThat(tuning.buysOwnSeaUnits(pd)).isEqualTo(ProTuning.DEFAULT.buysOwnSeaUnits(pd));
    assertThat(tuning.purchaseOrders(pd)).isEqualTo(Impegno.ordini(pd));
    final int rows = RegistroDecisioni.righe(data).size();
    LeveItalia.registraAcquisto(data, italy, st);
    assertThat(RegistroDecisioni.righe(data)).hasSize(rows);
  }

  @Test
  void eachLeverIsOnlyItalysAndTheCapitalsAlsoTheGermanTurn() {
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "italia_est");
    assertThat(LeveItalia.alcuna(data, player("Italians"))).isTrue();
    assertThat(LeveItalia.mare(data, player("Italians"))).isFalse();
    assertThat(LeveItalia.alcuna(data, player("Germans"))).isFalse();
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "italia_africa");
    assertThat(LeveItalia.mare(data, player("Italians"))).isTrue();
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "italia_gibilterra");
    assertThat(LeveItalia.mare(data, player("Italians"))).isTrue();
    assertThat(LeveItalia.tedeschiPerRoma(data, player("Germans"))).isFalse();
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "capitale_minima");
    assertThat(LeveItalia.mare(data, player("Italians"))).isFalse();
    assertThat(LeveItalia.alcuna(data, player("Italians"))).isTrue();
    assertThat(LeveItalia.tedeschiPerRoma(data, player("Germans"))).isTrue();
    // the Allies' switches never turn on Italy's levers
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "");
    StrategaInterruttori.accendi(
        data.getProperties(),
        "Allies",
        "italia_est,italia_africa,capitale_minima,italia_gibilterra");
    assertThat(LeveItalia.alcuna(data, player("Italians"))).isFalse();
    // with the sea levers Hard buys no ship of its own for Italy
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "italia_africa");
    final ProData pd = proData(player("Italians"), 3);
    assertThat(((ProPlusTuning) ProPlusTuning.unwrap(pd.getTuning())).buysOwnSeaUnits(pd))
        .isFalse();
  }

  @Test
  void oneSeaTaskInOrderEgyptGibraltarThenTheMoreThreatened() {
    assertThat(LeveItalia.compitoMare(false, false, false, false, 0, 0)).isEmpty();
    assertThat(LeveItalia.compitoMare(true, true, false, false, 0, 0)).isEqualTo(LeveItalia.EGITTO);
    assertThat(LeveItalia.compitoMare(true, true, true, false, 0, 0))
        .isEqualTo(LeveItalia.GIBILTERRA);
    assertThat(LeveItalia.compitoMare(true, true, true, true, 40, 60))
        .isEqualTo(LeveItalia.GIBILTERRA);
    assertThat(LeveItalia.compitoMare(true, true, true, true, 60, 40)).isEqualTo(LeveItalia.EGITTO);
    assertThat(LeveItalia.compitoMare(false, true, false, false, 0, 0))
        .isEqualTo(LeveItalia.GIBILTERRA);
    assertThat(LeveItalia.compitoMare(true, false, true, true, 0, 0)).isEqualTo(LeveItalia.EGITTO);
  }

  @Test
  void theSeaSpendingIsOneTransportAndAtMostTheShareWhileTheFleetDoesNotHold() {
    assertThat(LeveItalia.trasportiDaComprare(0, 2)).isEqualTo(1);
    assertThat(LeveItalia.trasportiDaComprare(1, 2)).isEqualTo(1);
    assertThat(LeveItalia.trasportiDaComprare(2, 2)).isZero();
    assertThat(LeveItalia.quotaNavi(true, 30, 14, 30)).isZero();
    assertThat(LeveItalia.quotaNavi(false, 0, 14, 30)).isZero();
    assertThat(LeveItalia.quotaNavi(false, 100, 14, 30)).isEqualTo(30);
    assertThat(LeveItalia.quotaNavi(false, 2, 20, 30)).isEqualTo(10);
  }

  @Test
  void theMinimumIsTheSmallestCountUnderTheThreshold() {
    final double[] wins = {95, 90, 70, 45, 31, 29, 10, 5};
    assertThat(LeveItalia.minimo(k -> wins[Math.min(k, wins.length - 1)], 7, 30)).isEqualTo(5);
    assertThat(LeveItalia.minimo(k -> 20, 7, 30)).isZero();
    assertThat(LeveItalia.minimo(k -> 99, 7, 30)).isEqualTo(8);
    for (int target = 0; target <= 20; target++) {
      final int t = target;
      assertThat(LeveItalia.minimo(k -> k >= t ? 0 : 100, 20, 30)).isEqualTo(t);
    }
  }

  @Test
  void theCapitalsGarrisonIsZeroWithoutAThreatAndGrowsWithOne() {
    final ProData pd = proData(player("Italians"), 3);
    final Territory capital = territory("Southern Italy");
    final LeveItalia.Guarnigione none =
        LeveItalia.guarnigione(pd, calc(), capital, List.of(), List.of());
    // no threat = no garrison (the map's start may already have one in reach)
    if (!none.minaccia()) {
      assertThat(none.totale()).isZero();
    }
    atWar();
    final Territory north = territory("Northern Italy");
    data.performChange(ChangeFactory.changeOwner(north, player("British")));
    data.performChange(ChangeFactory.removeUnits(north, new ArrayList<>(north.getUnits())));
    data.performChange(
        ChangeFactory.addUnits(
            north,
            data.getUnitTypeList().getUnitTypeOrThrow("armour").createTemp(6, player("British"))));
    final ProData pd2 = proData(player("Italians"), 3);
    final LeveItalia.Guarnigione g =
        LeveItalia.guarnigione(pd2, calc(), capital, List.of(), List.of());
    assertThat(g.minaccia()).isTrue();
    assertThat(g.extra()).isPositive();
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
