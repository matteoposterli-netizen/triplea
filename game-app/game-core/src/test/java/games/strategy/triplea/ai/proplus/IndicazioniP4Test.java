package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.java.collections.IntegerMap;

/**
 * Step P4: the data, the pure rules, every switch off = Hard (each hook answers as {@link
 * ProTuning#DEFAULT}), and on: the war view of A on a copy (the real game untouched, the purchase
 * translated to the real rules), the weight of B in its rounds only, the front factories, the lower
 * thresholds and the repair of C. Built positions on Global 1940 (same names as Europe 1940).
 */
class IndicazioniP4Test extends AbstractClientSettingTestCase {
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
    // the game's start sets the original owners (the test map is not initialized)
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
  void theDataHaveTheNationsAndTheValuesAndNoNameIsInTheCode() throws Exception {
    final IndicazioniP4.Config c = IndicazioniP4.Config.get();
    assertThat(c.usaDiDefault()).isFalse();
    assertThat(c.norvegiaDiDefault()).isFalse();
    assertThat(c.fronteDiDefault()).isFalse();
    assertThat(StrategaInterruttori.USA_PRONTI.predefinito()).isFalse();
    assertThat(StrategaInterruttori.NORVEGIA_PRESTO.predefinito()).isFalse();
    assertThat(StrategaInterruttori.RUSSIA_FRONTE.predefinito()).isFalse();
    assertThat(c.guerra().get("Americans").guerraDopoIlRound()).isEqualTo(3);
    assertThat(c.guerra().get("Americans").relazioni())
        .containsEntry("Germans", "War")
        .containsEntry("British", "Allied");
    assertThat(c.bersagli().get("British"))
        .containsExactly(new IndicazioniP4.Bersaglio("Norway", 20, 1, 4));
    assertThat(c.fronte().sogliaMinima()).isEqualTo(55);
    assertThat(c.fronte().sogliaDesiderata()).isEqualTo(80);
    assertThat(c.fronte().sogliaTenutaCapitale()).isEqualTo(40);
    assertThat(c.nazioniFronte()).containsExactly("Russians");
    for (final String file : List.of("IndicazioniP4", "GuerraVicina", "FronteCasa")) {
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
              "Novgorod",
              "Ukraine",
              "Volgograd",
              "Sea Zone")) {
        assertThat(src).as(file + " contiene " + word).doesNotContain(word);
      }
    }
  }

  // ============================================================================ pure rules

  @Test
  void theWeightHoldsOnlyInItsRoundsAndTheCoastsHalveWithDistance() {
    final List<IndicazioniP4.Bersaglio> list = List.of(new IndicazioniP4.Bersaglio("N", 20, 1, 4));
    assertThat(IndicazioniP4.peso(list, "N", 1)).isEqualTo(20);
    assertThat(IndicazioniP4.peso(list, "N", 4)).isEqualTo(20);
    assertThat(IndicazioniP4.peso(list, "N", 5)).isZero();
    assertThat(IndicazioniP4.peso(list, "X", 2)).isZero();
    assertThat(GuerraVicina.valoreCosta(8, 0)).isEqualTo(8);
    assertThat(GuerraVicina.valoreCosta(8, 3)).isEqualTo(1);
  }

  @Test
  void theRepairIsOnlyWhatThePlacementNeeds() {
    // factory 10, damage 8 (2 left), 30 PUs, 3 a unit: 6 repaired = 8 places, 24 PUs = 8 units
    assertThat(FronteCasa.riparazione(8, 10, 30, 3)).isEqualTo(6);
    // poor: nothing to repair beyond what the PUs can fill
    assertThat(FronteCasa.riparazione(8, 10, 6, 3)).isZero();
    // rich: everything
    assertThat(FronteCasa.riparazione(8, 10, 100, 3)).isEqualTo(8);
    assertThat(FronteCasa.riparazione(0, 10, 30, 3)).isZero();
    // damage beyond the capacity (17 on 10): the first 7 points give no place
    assertThat(FronteCasa.riparazione(17, 10, 21, 3)).isEqualTo(10);
    // a minor factory (3) with damage 5: 2 points to start placing
    assertThat(FronteCasa.riparazione(5, 3, 36, 3)).isEqualTo(5);
  }

  // ======================================================================== switches off

  @Test
  void withEverySwitchOffTheHooksAnswerAsHard() {
    atWar();
    for (final String n : List.of("Americans", "British", "Russians")) {
      final GamePlayer p = player(n);
      final ProData proData = proData(p, 2);
      final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
      assertThat(IndicazioniP4.attivo(data, p, StrategaInterruttori.USA_PRONTI)).isFalse();
      assertThat(IndicazioniP4.attivo(data, p, StrategaInterruttori.NORVEGIA_PRESTO)).isFalse();
      assertThat(FronteCasa.attivo(data, p)).isFalse();
      for (final Territory t : data.getMap().getTerritories()) {
        assertThat(tuning.placeTerritoryValue(proData, t, 1.5))
            .isEqualTo(ProTuning.DEFAULT.placeTerritoryValue(proData, t, 1.5));
        assertThat(tuning.defensePriorityBonus(proData, t)).isZero();
        assertThat(IndicazioniP4.pesoBersaglio(proData, t)).isZero();
        assertThat(FronteCasa.bonusRipresa(proData, t)).isZero();
      }
      final Territory moscow = territory("Russia");
      final Unit factory = moscow.getUnits().iterator().next();
      assertThat(tuning.repairDamage(proData, factory, 7, 30)).isEqualTo(7);
    }
    assertThat(GuerraVicina.vista(data, player("Americans"))).isEmpty();
  }

  // ======================================================================== A, war view

  @Test
  void theWarViewChangesOnlyTheCopyAndThePurchaseGoesToTheRealRules() {
    on("usa_pronti");
    data.getSequence().setRoundAndStep(1, "Purchase", player("Americans"));
    final GamePlayer usa = player("Americans");
    assertThat(usa.isAtWar(player("Germans"))).isFalse();
    final GuerraVicina.Vista view = GuerraVicina.vista(data, usa).orElseThrow();
    final GamePlayer copy = view.player();
    assertThat(copy.getData()).isNotSameAs(data);
    assertThat(copy.isAtWar(view.data().getPlayerList().getPlayerId("Germans"))).isTrue();
    assertThat(copy.isAllied(view.data().getPlayerList().getPlayerId("British"))).isTrue();
    // the real game is untouched
    assertThat(usa.isAtWar(player("Germans"))).isFalse();
    assertThat(usa.isAllied(player("British"))).isFalse();
    // the purchase planned with the copy's rules reaches the delegate with the real ones
    final List<Object> received = new ArrayList<>();
    final IPurchaseDelegate real =
        (IPurchaseDelegate)
            Proxy.newProxyInstance(
                IPurchaseDelegate.class.getClassLoader(),
                new Class<?>[] {IPurchaseDelegate.class},
                (proxy, m, args) -> {
                  if ("purchase".equals(m.getName())) {
                    received.add(args[0]);
                  }
                  return null;
                });
    final ProductionRule copyRule = copy.getProductionFrontier().getRules().getFirst();
    final IntegerMap<ProductionRule> bought = new IntegerMap<>();
    bought.add(copyRule, 2);
    GuerraVicina.delegato(real, data, usa).purchase(bought);
    @SuppressWarnings("unchecked")
    final IntegerMap<ProductionRule> sent = (IntegerMap<ProductionRule>) received.getFirst();
    final ProductionRule realRule = sent.keySet().iterator().next();
    assertThat(realRule).isSameAs(usa.getProductionFrontier().getRules().getFirst());
    assertThat(sent.getInt(realRule)).isEqualTo(2);
    // after the war's round: no view, the sea value only in the first war turn
    data.getSequence().setRoundAndStep(4, "Purchase", usa);
    assertThat(GuerraVicina.vista(data, usa)).isEmpty();
    assertThat(GuerraVicina.soloValoreMare(data, usa)).isTrue();
    data.getSequence().setRoundAndStep(5, "Purchase", usa);
    assertThat(GuerraVicina.soloValoreMare(data, usa)).isFalse();
  }

  @Test
  void inTheWarViewTheHomeSeaZoneIsWorthTheEnemyCoastsInReach() {
    on("usa_pronti");
    data.getSequence().setRoundAndStep(1, "Purchase", player("Americans"));
    final GuerraVicina.Vista view = GuerraVicina.vista(data, player("Americans")).orElseThrow();
    final ProData proData = proDataOn(view.data(), view.player(), 1);
    final Territory zone = view.data().getMap().getTerritoryOrNull("101 Sea Zone");
    assertThat(GuerraVicina.valoreMare(proData, zone)).isPositive();
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
    // only during a purchase in the war view
    assertThat(tuning.placeTerritoryValue(proData, zone, 0)).isZero();
    tuning.p4().vista = view;
    assertThat(tuning.placeTerritoryValue(proData, zone, 0)).isPositive();
    assertThat(tuning.p4().valoriMare).containsKey("101 Sea Zone");
  }

  // ======================================================================== B, weight

  @Test
  void theTargetWeighsOnlyForTheNationInItsRounds() {
    on("norvegia_presto");
    final Territory norway = territory("Norway");
    assertThat(IndicazioniP4.pesoBersaglio(proData(player("British"), 2), norway)).isEqualTo(20);
    assertThat(IndicazioniP4.pesoBersaglio(proData(player("British"), 5), norway)).isZero();
    assertThat(IndicazioniP4.pesoBersaglio(proData(player("Americans"), 2), norway)).isZero();
    final ProData uk = proData(player("British"), 3);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(uk.getTuning());
    assertThat(tuning.attackPriorityPlanBonus(uk, norway)).isEqualTo(20);
    assertThat(tuning.attackPriorityPlanBonus(uk, territory("Denmark"))).isZero();
  }

  // ======================================================================== C, front

  @Test
  void theFrontFactoriesAreFoundOnTheMapAndValued() {
    on("russia_fronte");
    atWar();
    final GamePlayer ru = player("Russians");
    final ProData proData = proData(ru, 3);
    assertThat(FronteCasa.fabbricaAlFronte(territory("Novgorod"), ru)).isTrue();
    assertThat(FronteCasa.fabbricaAlFronte(territory("Ukraine"), ru)).isTrue();
    assertThat(FronteCasa.fabbricaAlFronte(territory("Russia"), ru)).as("capitale").isFalse();
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
    assertThat(tuning.defensePriorityBonus(proData, territory("Novgorod"))).isEqualTo(20);
    assertThat(tuning.defensePriorityBonus(proData, territory("Russia"))).isZero();
    // lost to Germany: no defense bonus, an attack bonus to take it back
    data.performChange(ChangeFactory.changeOwner(territory("Ukraine"), player("Germans")));
    final ProData later = proData(ru, 3);
    final ProPlusTuning t2 = (ProPlusTuning) ProPlusTuning.unwrap(later.getTuning());
    assertThat(t2.defensePriorityBonus(later, territory("Ukraine"))).isZero();
    assertThat(t2.attackPriorityPlanBonus(later, territory("Ukraine"))).isEqualTo(20);
  }

  @Test
  void aCandidateHasTheLowerThresholdsAndTheOthersTheStandardOnes() {
    on("russia_fronte");
    final GamePlayer ru = player("Russians");
    final ProData proData = proData(ru, 3);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
    final Territory near = territory("Belarus");
    final Territory far = territory("Egypt");
    assertThat(tuning.targetAttackMinWinPercentage(proData, near, 65)).isEqualTo(65);
    tuning.p4().candidati.put("Belarus", new FronteCasa.Candidato("Belarus", 70, 5, true, 10));
    assertThat(tuning.targetAttackMinWinPercentage(proData, near, 65)).isEqualTo(55);
    assertThat(tuning.targetAttackWinPercentage(proData, near, 90)).isEqualTo(80);
    assertThat(tuning.targetAttackMinWinPercentage(proData, far, 65)).isEqualTo(65);
    assertThat(tuning.targetAttackWinPercentage(proData, far, 90)).isEqualTo(90);
    assertThat(FronteCasa.vicinoACasa(data, near, ru)).isTrue();
    assertThat(FronteCasa.vicinoACasa(data, far, ru)).isFalse();
  }

  @Test
  void theDamagedFactoryIsRepairedOnlyForTheAffordablePlacement() {
    on("russia_fronte");
    final GamePlayer ru = player("Russians");
    final Territory moscow = territory("Russia");
    final Unit factory =
        moscow.getUnits().stream()
            .filter(u -> u.getType().getName().startsWith("factory"))
            .findFirst()
            .orElseThrow();
    data.performChange(
        ChangeFactory.bombingUnitDamage(
            new IntegerMap<>(java.util.Map.of(factory, 8)), List.of(moscow)));
    final ProData proData = proData(ru, 5);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
    final int r = tuning.repairDamage(proData, factory, 8, 30);
    assertThat(r).isLessThan(8);
    assertThat(tuning.p4().riparazioni).hasSize(1);
    assertThat(tuning.p4().riparazioni.get(0).getAsJsonObject().get("risparmiato").getAsInt())
        .isEqualTo(8 - r);
  }

  @Test
  void theBoxesOfThePanelTurnTheSwitchesOnAlone() throws Exception {
    final GameData fresh = TestMapGameData.GLOBAL1940.getGameData();
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setUsaPronti(true);
    p.setNorvegiaPresto(true);
    p.setRussiaFronte(true);
    p.salva();
    Stratega.registra(fresh, fresh.getPlayerList().getPlayerId("British"));
    final var props = fresh.getProperties();
    assertThat(StrategaInterruttori.USA_PRONTI.acceso(props, "Allies")).isTrue();
    assertThat(StrategaInterruttori.NORVEGIA_PRESTO.acceso(props, "Allies")).isTrue();
    assertThat(StrategaInterruttori.RUSSIA_FRONTE.acceso(props, "Allies")).isTrue();
    assertThat(StrategaInterruttori.COMPITI.acceso(props, "Allies")).isFalse();
    assertThat(StrategaInterruttori.IMPEGNO.acceso(props, "Allies")).isFalse();
    assertThat(StrategaInterruttori.USA_PRONTI.acceso(props, "Axis")).isFalse();
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
    return proDataOn(data, player, round);
  }

  private static ProData proDataOn(final GameData d, final GamePlayer player, final int round) {
    d.getSequence().setRoundAndStep(round, "Purchase", player);
    final ProPlusTuning plus = new ProPlusTuning();
    final ProData proData = new ProData(false, plus);
    plus.attach(proData);
    proData.initializeSimulation(
        new TestAi(player.getName(), proData, new SeededBattleCalculator(SEED)), d, player);
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
