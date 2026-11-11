package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step R2b "IMPEGNO" (Global 1940, Germans) on built positions: the target is not cancelled when
 * its estimate drops; 70% of the PUs go to the operation after the capital's garrison; a unit not
 * used in battle steps toward the gathering territory; in the combat move Hard has the same units
 * with the switch on and off; a forced attack at 70% and not at 69%; switch off = Hard.
 */
class ImpegnoTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261111L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(
        games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.FOLDER_PROPERTY,
        profile.toString());
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    Stratega.registra(data, player("Germans"));
    Stratega.registra(data, player("Italians"));
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(
        games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.FOLDER_PROPERTY);
  }

  @Test
  void theDataFileHasTheChosenValuesAndTheSwitchOff() {
    final Impegno.Config cfg = Impegno.Config.get();
    assertThat(cfg.accesoDiDefault()).isFalse();
    assertThat(cfg.quotaPu()).isEqualTo(70);
    assertThat(cfg.sogliaForzato()).isEqualTo(70);
    assertThat(cfg.durataMinima()).isEqualTo(3);
    assertThat(cfg.margineScelta()).isEqualTo(80);
    assertThat(StrategaInterruttori.IMPEGNO.acceso(data.getProperties(), "Axis")).isFalse();
    assertThat(StrategaInterruttori.of("impegno")).contains(StrategaInterruttori.IMPEGNO);
  }

  @Test
  void switchOffIsHard() {
    final var props = data.getProperties();
    final ProData off = proData(player("Germans"), 2);
    final List<String> answersBefore = answers(off);
    final Map<String, String> before = snapshot();
    Impegno.inizioTurno(off, calc(), player("Germans"));
    assertThat(snapshot()).isEqualTo(before);
    assertThat(Impegno.attivo(data, player("Germans"))).isFalse();
    assertThat(Impegno.ordini(off)).isEmpty();
    assertThat(Impegno.forzaQualcosa(off)).isFalse();
    assertThat(Impegno.primaDelPiazzamento(data, player("Germans"))).isEmpty();
    assertThat(answers(off)).isEqualTo(answersBefore);
    assertThat(props.get(Impegno.KEY_STATO + "Axis")).isNull();
    // and the forcing hooks answer as Hard
    final Territory france = territory("France");
    assertThat(OperazioniCongiunte.forzaBersaglio(off, france)).isFalse();
    assertThat(((ProPlusTuning) off.getTuning()).attackPriorityPlanBonus(off, france)).isZero();
  }

  @Test
  void theTargetIsNotCancelledWhenTheEstimateDrops() {
    on();
    Impegno.inizioTurno(proData(player("Germans"), 2), calc(), player("Germans"));
    final String target = nation().get("obiettivo").getAsString();
    assertThat(target).isNotBlank();
    // the estimate drops: a large army joins the defenders of the target and of the next stage
    final Territory t = territory(target);
    addUnits(t, t.getOwner().getName(), "infantry", 40);
    final String stage = nation().get("tappa").getAsString();
    addUnits(territory(stage), territory(stage).getOwner().getName(), "infantry", 40);
    Impegno.inizioTurno(proData(player("Germans"), 3), calc(), player("Germans"));
    assertThat(nation().get("obiettivo").getAsString()).isEqualTo(target);
    assertThat(nation().get("dal_round").getAsInt()).isEqualTo(2);
    assertThat(state().has("storia") && !state().getAsJsonArray("storia").isEmpty()).isFalse();
    // the attack is no longer forced: below the threshold Hard decides, the operation goes on
    assertThat(Impegno.forzato(proData(player("Germans"), 3), t)).isFalse();
    // the rule itself: no estimate is an input, only taken / not enemy / route / capital
    assertThat(Impegno.motivoFine(false, true, true, false, 10, 3)).isEmpty();
    assertThat(Impegno.motivoFine(false, true, false, false, 2, 3)).isEmpty();
    assertThat(Impegno.motivoFine(false, true, false, false, 3, 3)).contains(Impegno.ROTTA_ASSENTE);
    assertThat(Impegno.motivoFine(false, true, true, true, 2, 3)).isEmpty();
    assertThat(Impegno.motivoFine(false, true, true, true, 3, 3)).contains(Impegno.CAPITALE_C);
    assertThat(Impegno.motivoFine(true, true, true, false, 0, 3)).contains(Impegno.PRESO);
    assertThat(Impegno.motivoFine(false, false, true, false, 0, 3)).contains(Impegno.NON_NEMICO);
  }

  @Test
  void seventyPercentOfThePusGoToTheOperationAfterTheCapitalGarrison() {
    assertThat(Impegno.puOperazione(50, 10, 70)).isEqualTo(28);
    assertThat(Impegno.puOperazione(30, 0, 70)).isEqualTo(21);
    assertThat(Impegno.puOperazione(10, 15, 70)).isZero();
    on();
    Impegno.inizioTurno(proData(player("Germans"), 2), calc(), player("Germans"));
    assertThat(nation().has("obiettivo")).isTrue();
    // enough PUs for the capital's garrison and the operation
    data.performChange(
        ChangeFactory.changeResourcesChange(
            player("Germans"),
            data.getResourceList().getResourceOrThrow(games.strategy.triplea.Constants.PUS),
            100));
    // a real land threat on the capital (case c of rule 2.6) in the next round
    final Territory capital = territory("Germany");
    for (final Territory n : data.getMap().getNeighbors(capital, Matches.territoryIsLand())) {
      if (!n.isOwnedBy(player("Germans"))) {
        continue;
      }
      addUnits(n, "Russians", "armour", 8);
      addUnits(n, "Russians", "infantry", 8);
      break;
    }
    final ProData pd = proData(player("Germans"), 3);
    Impegno.inizioTurno(pd, calc(), player("Germans"));
    final JsonObject turn = lastTurn();
    assertThat(turn.get("capitale_caso_c").getAsBoolean()).isTrue();
    final int puTurn = turn.get("pu_turno").getAsInt();
    final int puC = turn.get("pu_caso_c").getAsInt();
    assertThat(puC).isPositive();
    assertThat(turn.get("pu_op").getAsInt())
        .isEqualTo((int) Math.floor((puTurn - puC) * 0.7))
        .isPositive();
    // the target stays (duration 1 < 3) and the orders reserve the operation's share
    assertThat(nation().get("dal_round").getAsInt()).isEqualTo(2);
    final Optional<ProPurchaseOrders> orders = ((ProPlusTuning) pd.getTuning()).purchaseOrders(pd);
    assertThat(orders).isPresent();
    assertThat(orders.get().minimumShare())
        .isCloseTo(
            100.0 * turn.get("pu_op").getAsInt() / puTurn,
            org.assertj.core.data.Offset.offset(0.01));
    assertThat(((ProPlusTuning) pd.getTuning()).strategyCapPercent(pd)).isEmpty();
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, player("Germans"));
    int ordered = 0;
    for (final ProPurchaseOrders.Mandatory m : orders.get().mandatory()) {
      ordered += m.minimum() * costs.get(m.unitTypes().get(0));
    }
    assertThat(ordered).isLessThanOrEqualTo(turn.get("pu_op").getAsInt());
    assertThat(ordered).isGreaterThan(turn.get("pu_op").getAsInt() - 5);
  }

  @Test
  void aUnitNotUsedInBattleStepsTowardTheGatheringTerritory() {
    on();
    final ProData pd = proData(player("Germans"), 2);
    Impegno.inizioTurno(pd, calc(), player("Germans"));
    final Territory gather = territory(nation().get("raduno").getAsString());
    data.getSequence().setRoundAndStep(2, "Non Combat Move", player("Germans"));
    final List<MoveDescription> moves = new ArrayList<>();
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any()))
        .thenAnswer(
            invocation -> {
              moves.add(invocation.getArgument(0));
              return Optional.empty();
            });
    final ProData nc = proData(player("Germans"), 2, "Non Combat Move");
    final Set<Unit> held = Impegno.nonCombattimento(nc, calc(), moveDel, player("Germans"));
    assertThat(moves).isNotEmpty();
    for (final MoveDescription m : moves) {
      final Territory from = m.getRoute().getStart();
      final Territory to = m.getRoute().getEnd();
      assertThat(friendlyDistance(to, gather))
          .as(m.getUnits() + " " + from + " -> " + to + " toward " + gather)
          .isLessThan(friendlyDistance(from, gather));
      assertThat(Matches.isTerritoryAllied(player("Germans")).test(to)).isTrue();
      assertThat(held).containsAll(m.getUnits());
    }
    assertThat(lastTurn().get("instradate").getAsInt()).isEqualTo(moves.size());
  }

  @Test
  void inTheCombatMoveHardHasTheSameUnitsWithTheSwitchOnAndOff() {
    final ProData off = proData(player("Germans"), 2);
    Impegno.inizioTurno(off, calc(), player("Germans"));
    toCombat(off);
    final Set<Unit> heldOff = ((ProPlusTuning) off.getTuning()).combatHeldUnits(off);
    final Set<Unit> movableOff = movable(off);
    on();
    final ProData onData = proData(player("Germans"), 2);
    Impegno.inizioTurno(onData, calc(), player("Germans"));
    assertThat(nation().has("obiettivo")).as("the commitment ran").isTrue();
    toCombat(onData);
    final ProPlusTuning tuning = (ProPlusTuning) onData.getTuning();
    assertThat(tuning.combatHeldUnits(onData)).isEqualTo(heldOff).isEmpty();
    assertThat(tuning.heldUnits(onData)).isEmpty();
    assertThat(movable(onData)).isEqualTo(movableOff).isNotEmpty();
  }

  @Test
  void aForcedAttackAtSeventyAndNotAtSixtyNine() {
    final double threshold = Impegno.Config.get().sogliaForzato();
    assertThat(Impegno.forza(70, threshold)).isTrue();
    assertThat(Impegno.forza(69, threshold)).isFalse();
    assertThat(Impegno.forza(69.99, threshold)).isFalse();
    assertThat(Impegno.forza(100, threshold)).isTrue();
    // the forced target reaches Hard's combat through the operations' hooks
    on();
    final ProData pd = proData(player("Germans"), 2);
    final Territory france = territory("France");
    data.getProperties().set(Impegno.KEY_FORZATI + "Germans", "2|France");
    assertThat(Impegno.forzato(pd, france)).isTrue();
    assertThat(Impegno.forzato(pd, territory("Poland"))).isFalse();
    assertThat(OperazioniCongiunte.forzaBersaglio(pd, france)).isTrue();
    assertThat(OperazioniCongiunte.forzaUnBersaglio(pd)).isTrue();
    assertThat(OperazioniCongiunte.sogliaForzata(pd, france)).isEqualTo(30);
    final ProPlusTuning tuning = (ProPlusTuning) pd.getTuning();
    assertThat(tuning.attackPriorityPlanBonus(pd, france)).isEqualTo(1000);
    assertThat(tuning.targetAttackMinWinPercentage(pd, france, 60)).isEqualTo(30);
    // another round: nothing forced
    assertThat(Impegno.forzato(proData(player("Germans"), 3), france)).isFalse();
  }

  @Test
  void theRuleOfTheChoice() {
    final var a = new Impegno.Candidato("A", 3, true, 0, 10);
    final var b = new Impegno.Candidato("B", 1, true, 0, 5);
    final var c = new Impegno.Candidato("C", 1, false, 5, 30);
    final var d = new Impegno.Candidato("D", 2, false, 2, 1);
    final var e = new Impegno.Candidato("E", 1, false, -1, 99);
    assertThat(Impegno.scegli(List.of(a, b, c, d, e)).map(Impegno.Candidato::territorio))
        .contains("B");
    assertThat(Impegno.scegli(List.of(c, d, e)).map(Impegno.Candidato::territorio)).contains("D");
    assertThat(Impegno.scegli(List.of(e)).map(Impegno.Candidato::territorio)).contains("E");
    assertThat(Impegno.scegli(List.of())).isEmpty();
    // types by distance and the mix within the PUs
    final Impegno.Config cfg = Impegno.Config.get();
    assertThat(Impegno.tipi(1, cfg)).isEqualTo(cfg.tipiVicino());
    assertThat(Impegno.tipi(5, cfg)).isEqualTo(cfg.tipiLontano());
    assertThat(
            Impegno.composizione(
                21, List.of("infantry", "artillery"), Map.of("infantry", 3, "artillery", 4)))
        .containsEntry("infantry", 3)
        .containsEntry("artillery", 3);
  }

  // ================================================================================ helpers

  private void toCombat(final ProData pd) {
    data.getSequence().setRoundAndStep(2, "Combat Move", player("Germans"));
    pd.initializeSimulation(
        new TestAi("Germans", pd, new SeededBattleCalculator(SEED)), data, player("Germans"));
  }

  private void on() {
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno");
  }

  private JsonObject state() {
    final Object s = data.getProperties().get(Impegno.KEY_STATO + "Axis");
    return s == null ? new JsonObject() : JsonParser.parseString((String) s).getAsJsonObject();
  }

  private JsonObject nation() {
    return state().getAsJsonObject("nazioni").getAsJsonObject("Germans");
  }

  private JsonObject lastTurn() {
    final var turns = state().getAsJsonArray("turni");
    return turns.get(turns.size() - 1).getAsJsonObject();
  }

  private int friendlyDistance(final Territory from, final Territory gather) {
    if (from.equals(gather)) {
      return 0;
    }
    return data.getMap()
        .getDistance(
            from,
            gather,
            x ->
                !x.isWater()
                    && (x.equals(gather) || Matches.isTerritoryAllied(player("Germans")).test(x)));
  }

  private Set<Unit> movable(final ProData pd) {
    final ProTerritoryManager tm = new ProTerritoryManager(calc(), pd);
    tm.populateAttackOptions();
    tm.getAttackOptions().removeUnits(((ProPlusTuning) pd.getTuning()).combatHeldUnits(pd));
    return new HashSet<>(tm.getAttackOptions().getUnitMoveMap().keySet());
  }

  private void addUnits(final Territory t, final String owner, final String type, final int n) {
    data.performChange(
        ChangeFactory.addUnits(
            t, data.getUnitTypeList().getUnitTypeOrThrow(type).create(n, player(owner))));
  }

  private static List<String> answers(final ProData pd) {
    final ProPlusTuning t = (ProPlusTuning) pd.getTuning();
    final List<String> out = new ArrayList<>();
    out.add(String.valueOf(t.strategyCapPercent(pd)));
    out.add(String.valueOf(t.defenseLists(pd)));
    out.add(String.valueOf(t.combatHeldUnits(pd).size()));
    out.add(String.valueOf(t.heldUnits(pd).size()));
    out.add(String.valueOf(t.purchaseOrders(pd).isPresent()));
    out.add(String.valueOf(t.stagingOrders(pd).isPresent()));
    return out;
  }

  private Map<String, String> snapshot() {
    final Map<String, String> out = new TreeMap<>();
    data.getProperties()
        .getConstantPropertiesByName()
        .forEach((k, v) -> out.put(k, String.valueOf(v)));
    return out;
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrNull(name);
  }

  private ProData proData(final GamePlayer player, final int round) {
    return proData(player, round, "Purchase");
  }

  private ProData proData(final GamePlayer player, final int round, final String step) {
    data.getSequence().setRoundAndStep(round, step, player);
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

  /** An AI with seeded battle dice. */
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
