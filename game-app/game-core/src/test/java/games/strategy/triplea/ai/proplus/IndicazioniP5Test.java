package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P5: the data, the pure rules of the two levers (A: the order of the three steps and the
 * proportion, nothing after the first war turn; B: the smallest escort that is enough, the landing
 * dropped if not even every escort is), every switch off = nothing, and A's plan on a built
 * position (Global 1940, same names as Europe 1940).
 */
class IndicazioniP5Test extends AbstractClientSettingTestCase {
  private static final long SEED = 20261119L;

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

  // ============================================================================ data, code

  @Test
  void theDataHaveTheThresholdsTheSwitchesAreOffAndNoNameIsInTheCode() throws Exception {
    final IndicazioniP5.Config c = IndicazioniP5.Config.get();
    assertThat(c.usaDiDefault()).isFalse();
    assertThat(c.scortaDiDefault()).isFalse();
    assertThat(c.sogliaConvoglio()).isEqualTo(40);
    assertThat(c.sogliaTrasporti()).isEqualTo(40);
    assertThat(StrategaInterruttori.USA_PROPORZIONE.predefinito()).isFalse();
    assertThat(StrategaInterruttori.NORVEGIA_SCORTA.predefinito()).isFalse();
    for (final String file : List.of("IndicazioniP5", "ForzaSbarco", "ScortaSbarco")) {
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
              "Italians",
              "Norway",
              "United Kingdom",
              "Sea Zone")) {
        assertThat(src).as(file + " contiene " + word).doesNotContain(word);
      }
    }
  }

  @Test
  void withTheSwitchesOffNothingActs() {
    for (final String n : List.of("Americans", "British")) {
      final GamePlayer p = player(n);
      final ProData proData = proData(p, 2);
      assertThat(ForzaSbarco.fase(data, p)).isEmpty();
      assertThat(ScortaSbarco.attivo(data, p)).isFalse();
      assertThat(ScortaSbarco.attivo(proData, territory("Norway"))).isFalse();
      final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
      assertThat(ForzaSbarco.ordini(proData, tuning.p5a())).isEmpty();
      assertThat(ScortaSbarco.valuta(proData, tuning.p5b(), territory("Norway"), 90, 30, 3))
          .isEmpty();
    }
  }

  // ============================================================================ A, pure rules

  @Test
  void theLeverHoldsInTheNeutralRoundsAndTheFirstWarTurnOnly() {
    assertThat(ForzaSbarco.fase(1, 3, true)).contains(ForzaSbarco.Fase.NEUTRALE);
    assertThat(ForzaSbarco.fase(3, 3, true)).contains(ForzaSbarco.Fase.ULTIMO_NEUTRALE);
    assertThat(ForzaSbarco.fase(4, 3, false)).contains(ForzaSbarco.Fase.PRIMO_GUERRA);
    assertThat(ForzaSbarco.fase(5, 3, false)).isEmpty();
    assertThat(ForzaSbarco.fase(9, 3, false)).isEmpty();
  }

  @Test
  void theTransportsAreOnlyThoseTheTroopsAtHomeNeed() {
    // 2 infantry (2) + 2 artillery (3) in transports of capacity 5: 2 transports
    assertThat(ForzaSbarco.trasportiNecessari(List.of(2, 3, 2, 3), List.of(), 5)).isEqualTo(2);
    // 2 artillery never share a transport: 2 transports
    assertThat(ForzaSbarco.trasportiNecessari(List.of(3, 3), List.of(), 5)).isEqualTo(2);
    // the owned free places first
    assertThat(ForzaSbarco.trasportiNecessari(List.of(2, 3, 2, 3), List.of(5), 5)).isEqualTo(1);
    assertThat(ForzaSbarco.trasportiNecessari(List.of(2, 3), List.of(5, 5), 5)).isZero();
    assertThat(ForzaSbarco.trasportiNecessari(List.of(), List.of(), 5)).isZero();
  }

  @Test
  void theThreeStepsComeInOrderAndInProportion() {
    final List<String> order = new ArrayList<>();
    final ForzaSbarco.Passi p =
        ForzaSbarco.dividi(
            60,
            2,
            7,
            (budget, n1) -> {
              order.add("scorta con " + budget + " PU e " + n1 + " trasporti");
              return new ForzaSbarco.Scorta(Map.of("destroyer", 2), 16, 70, 35, true, "");
            },
            List.of("infantry", "artillery"),
            7 + 3 + 4,
            20);
    // 1: 2 transports (14), 2: escort with the 46 left (16), 3: 30 / 14 = 2 loads (28), 2 left
    assertThat(order).containsExactly("scorta con 46 PU e 2 trasporti");
    assertThat(p.trasporti()).isEqualTo(2);
    assertThat(p.spesiTrasporti()).isEqualTo(14);
    assertThat(p.scorta().spesi()).isEqualTo(16);
    assertThat(p.carichi()).isEqualTo(2);
    assertThat(p.spesiCarichi()).isEqualTo(28);
    assertThat(p.restano()).isEqualTo(2);
    // no PUs for the transports the troops need: all of them to step 1, nothing after
    final ForzaSbarco.Passi poor =
        ForzaSbarco.dividi(
            20,
            5,
            7,
            (budget, n1) -> new ForzaSbarco.Scorta(Map.of(), 0, 0, 0, true, ""),
            List.of("infantry", "artillery"),
            14,
            20);
    assertThat(poor.trasporti()).isEqualTo(2);
    assertThat(poor.carichi()).isZero();
    // the place left limits the loads
    final ForzaSbarco.Passi full =
        ForzaSbarco.dividi(
            200,
            0,
            7,
            (budget, n1) -> new ForzaSbarco.Scorta(Map.of(), 0, 0, 0, true, ""),
            List.of("infantry", "artillery"),
            14,
            7);
    assertThat(full.carichi()).isEqualTo(2);
  }

  @Test
  void noEscortIfTheOwnedOneHoldsElseTheBestPerPuUntilItHolds() {
    final AtomicInteger calls = new AtomicInteger();
    final ForzaSbarco.Scorta none =
        ForzaSbarco.scorta(
            Map.of("destroyer", 8, "cruiser", 12),
            types -> {
              calls.incrementAndGet();
              return 35.0;
            },
            100,
            40,
            12);
    assertThat(none.unita()).isEmpty();
    assertThat(none.spesi()).isZero();
    assertThat(none.regge()).isTrue();
    assertThat(calls.get()).isEqualTo(1);
    // a destroyer lowers 10 points (8 PU), a cruiser 12 (12 PU): destroyers first, until <= 40
    final ForzaSbarco.Scorta some =
        ForzaSbarco.scorta(
            Map.of("destroyer", 8, "cruiser", 12),
            types -> {
              double w = 70;
              for (final String t : types) {
                w -= "destroyer".equals(t) ? 10 : 12;
              }
              return w;
            },
            100,
            40,
            12);
    assertThat(some.unita()).containsExactlyEntriesOf(Map.of("destroyer", 3));
    assertThat(some.spesi()).isEqualTo(24);
    assertThat(some.dopo()).isEqualTo(40);
    assertThat(some.regge()).isTrue();
    // too few PUs: what the PUs allow, the convoy does not hold
    final ForzaSbarco.Scorta poor =
        ForzaSbarco.scorta(Map.of("destroyer", 8), types -> 70.0 - 10 * types.size(), 10, 40, 12);
    assertThat(poor.unita()).containsExactlyEntriesOf(Map.of("destroyer", 1));
    assertThat(poor.regge()).isFalse();
  }

  @Test
  void aTransportIsFilledWithHardsMostEfficientTroopsThatFit() {
    final List<ForzaSbarco.Opzione> options =
        List.of(
            new ForzaSbarco.Opzione("infantry", 3, 2, 1.0),
            new ForzaSbarco.Opzione("artillery", 4, 3, 1.5),
            new ForzaSbarco.Opzione("armour", 6, 3, 1.2));
    assertThat(ForzaSbarco.riempi(5, options)).containsExactly("artillery", "infantry");
    assertThat(ForzaSbarco.riempi(4, options)).containsExactly("artillery");
    assertThat(ForzaSbarco.riempi(0, options)).isEmpty();
  }

  // ============================================================================ B, pure rule

  @Test
  void theSmallestEscortThatIsEnoughOrNoneIfNotEvenAllAre() {
    // candidates: 0 destroyer (8, -15), 1 cruiser (12, -20), 2 battleship (20, -40)
    final List<Double> costs = List.of(8.0, 12.0, 20.0);
    final double[] drop = {15, 20, 40};
    final ScortaSbarco.Scelta s =
        ScortaSbarco.scortaMinima(
            costs,
            idx -> {
              double w = 70;
              for (final int i : idx) {
                w -= drop[i];
              }
              return Math.max(0, w);
            },
            40);
    assertThat(s.basta()).isTrue();
    // the battleship lowers most per cost (40/20 > 15/8 > 20/12): alone it is enough (30 <= 40)
    assertThat(s.scelti()).containsExactly(2);
    assertThat(s.dopo()).isEqualTo(30);
    // a cheap first pick not needed in the end is left out again
    final double[] drop2 = {5, 25, 30};
    final ScortaSbarco.Scelta pruned =
        ScortaSbarco.scortaMinima(
            List.of(1.0, 12.0, 12.0),
            idx -> {
              double w = 70;
              for (final int i : idx) {
                w -= drop2[i];
              }
              return w;
            },
            40);
    // the cheap one first (65), then the third (35): without the first still 40, so left out
    assertThat(pruned.scelti()).containsExactly(2);
    // already safe: no escort
    final ScortaSbarco.Scelta safe = ScortaSbarco.scortaMinima(costs, idx -> 30.0, 40);
    assertThat(safe.basta()).isTrue();
    assertThat(safe.scelti()).isEmpty();
    // not even every escort is enough: none, the landing stays dropped
    final ScortaSbarco.Scelta short_ =
        ScortaSbarco.scortaMinima(costs, idx -> 95.0 - 5 * idx.size(), 40);
    assertThat(short_.basta()).isFalse();
    assertThat(short_.scelti()).isEmpty();
    assertThat(short_.conTutti()).isEqualTo(80);
  }

  // ============================================================================ A on the map

  @Test
  void thePlanBuysTransportsForTheTroopsAtHomeThenEscortThenLoads() {
    on("usa_proporzione");
    final GamePlayer us = player("Americans");
    final ProData proData = proData(us, 1);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
    assertThat(ForzaSbarco.fase(data, us)).contains(ForzaSbarco.Fase.NEUTRALE);
    ForzaSbarco.piano(proData, calc(), tuning.p5a(), ForzaSbarco.Fase.NEUTRALE);
    final var row = tuning.p5a().riga;
    assertThat(row).isNotNull();
    final int need = row.get("trasporti_mancanti").getAsInt();
    final int bought = row.getAsJsonObject("passo_1_trasporti").get("trasporti").getAsInt();
    assertThat(bought).isLessThanOrEqualTo(need);
    final ProPurchaseOrders orders = ForzaSbarco.ordini(proData, tuning.p5a()).orElseThrow();
    assertThat(orders.minimumShare()).isEqualTo(100);
    // the categories in the order of the three steps
    String last = "p5 0";
    for (final ProPurchaseOrders.Mandatory m : orders.mandatory()) {
      assertThat(m.category().substring(0, 4)).isGreaterThanOrEqualTo(last.substring(0, 4));
      last = m.category();
    }
    // after the first war turn: Hard alone
    atWar();
    assertThat(ForzaSbarco.fase(proData(us, 5).getData(), us)).isEmpty();
    assertThat(ForzaSbarco.ordini(proData(us, 5), tuning.p5a())).isEmpty();
  }

  // ============================================================================== helpers

  private void on(final String ids) {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", ids);
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
