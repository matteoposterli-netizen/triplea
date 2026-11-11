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
    assertThat(((ProPlusTuning) pd.getTuning()).strategyCapPercent(pd)).isEmpty();
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, player("Germans"));
    int ordered = 0;
    for (final ProPurchaseOrders.Mandatory m : orders.get().mandatory()) {
      ordered += m.minimum() * costs.get(m.unitTypes().get(0));
    }
    // step R2c: the share reserves the PUs really ordered
    assertThat(orders.get().minimumShare())
        .isCloseTo(100.0 * ordered / puTurn, org.assertj.core.data.Offset.offset(0.01));
    assertThat(ordered).isLessThanOrEqualTo(turn.get("pu_op").getAsInt());
    assertThat(ordered).isGreaterThan(turn.get("pu_op").getAsInt() - 5);
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

  // ======================================================================== step R2c

  @Test
  void theDataFileHasTheValuesOfStepR2c() {
    final Programmi.Config r2c = Programmi.Config.get();
    assertThat(r2c.sogliaEsecuzione()).isEqualTo(70);
    assertThat(r2c.sogliaBaseSicura()).isEqualTo(40);
    assertThat(r2c.nazioniCampagnaTerra())
        .containsExactly("Germans", "Italians", "Russians", "British");
    assertThat(r2c.nazioniSoloFattibili()).containsExactly("Russians");
    assertThat(r2c.quotaProgrammaUsa()).isEqualTo(70);
    assertThat(r2c.baseAvanzataUsa()).isEqualTo("United Kingdom");
    assertThat(r2c.occupaSenzaCombattere()).contains("Persia");
    assertThat(r2c.attacca()).containsExactly("Iraq");
    assertThat(r2c.esclusi()).contains("Turkey", "Saudi Arabia");
  }

  @Test
  void executionAtSeventyAndNotAtSixtyNine() {
    final double threshold = Programmi.Config.get().sogliaEsecuzione();
    assertThat(Impegno.fase(70, threshold)).isEqualTo(Impegno.ESECUZIONE);
    assertThat(Impegno.fase(100, threshold)).isEqualTo(Impegno.ESECUZIONE);
    assertThat(Impegno.fase(69, threshold)).isEqualTo(Impegno.PREPARAZIONE);
    assertThat(Impegno.fase(69.99, threshold)).isEqualTo(Impegno.PREPARAZIONE);
    assertThat(Impegno.fase(0, threshold)).isEqualTo(Impegno.PREPARAZIONE);
  }

  @Test
  void inPreparationNoPreexistingUnitMovesAndNoAttackIsForced() {
    on();
    Impegno.inizioTurno(proData(player("Germans"), 2), calc(), player("Germans"));
    final String stage = nation().get("tappa").getAsString();
    // the next stage out of reach: a large army defends it
    addUnits(territory(stage), territory(stage).getOwner().getName(), "infantry", 60);
    addUnits(territory(stage), territory(stage).getOwner().getName(), "armour", 20);
    final ProData pd = proData(player("Germans"), 2);
    Impegno.inizioTurno(pd, calc(), player("Germans"));
    assertThat(nation().get("fase").getAsString()).isEqualTo(Impegno.PREPARAZIONE);
    assertThat(lastTurn().get("fase").getAsString()).isEqualTo(Impegno.PREPARAZIONE);
    assertThat(Impegno.forzaQualcosa(pd)).isFalse();
    for (final var a : lastTurn().getAsJsonArray("attacchi")) {
      assertThat(a.getAsJsonObject().get("forzato").getAsBoolean()).isFalse();
    }
    // no unit bought yet: nothing moves in the non-combat move
    final List<MoveDescription> moves = new ArrayList<>();
    final IMoveDelegate moveDel = recording(moves);
    final ProData nc = proData(player("Germans"), 2, "Non Combat Move");
    assertThat(Impegno.nonCombattimento(nc, calc(), moveDel, player("Germans"))).isEmpty();
    assertThat(moves).isEmpty();
    // a unit bought for the operation (followed by id) may move; the others never
    final Territory factory = territory("Germany");
    final Unit bought =
        data.getUnitTypeList().getUnitTypeOrThrow("infantry").create(1, player("Germans")).get(0);
    data.performChange(ChangeFactory.addUnits(factory, List.of(bought)));
    follow(bought);
    final ProData nc2 = proData(player("Germans"), 2, "Non Combat Move");
    Impegno.nonCombattimento(nc2, calc(), moveDel, player("Germans"));
    for (final MoveDescription m : moves) {
      assertThat(m.getUnits()).containsOnly(bought);
    }
  }

  @Test
  void aForcedAttackIsGivenUpWhenTheUnitsHardSendsAreNotEnough() {
    // the rule: as sent, completed with extra units, or given up (never half-done)
    assertThat(Impegno.completa(75, List.of(), 70)).contains(0);
    assertThat(Impegno.completa(50, List.of(60.0, 72.0, 90.0), 70)).contains(2);
    assertThat(Impegno.completa(50, List.of(60.0, 69.0), 70)).isEmpty();
    assertThat(Impegno.completa(50, List.of(), 70)).isEmpty();
    // on the board: France forced, Hard sends one infantry and nothing else can join
    on();
    final ProData pd = proData(player("Germans"), 2, "Combat Move");
    data.getProperties().set(Impegno.KEY_FORZATI + "Germans", "2|France");
    final Territory france = territory("France");
    final Unit one =
        territory("Western Germany")
            .getMatches(
                Matches.unitIsOwnedBy(player("Germans")).and(Matches.unitIsLandTransportable()))
            .get(0);
    final games.strategy.triplea.ai.pro.data.ProTerritory attack =
        new games.strategy.triplea.ai.pro.data.ProTerritory(france, pd);
    attack.addUnit(one);
    attack.getMaxUnits().add(one);
    final Map<Territory, games.strategy.triplea.ai.pro.data.ProTerritory> map =
        new java.util.HashMap<>(Map.of(france, attack));
    Impegno.rivedi(pd, calc(), map);
    assertThat(attack.getUnits()).isEmpty();
    // switch off: Hard's attack untouched
    final games.strategy.triplea.ai.pro.data.ProTerritory other =
        new games.strategy.triplea.ai.pro.data.ProTerritory(france, pd);
    other.addUnit(one);
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "");
    Impegno.rivedi(proData(player("Germans"), 2, "Combat Move"), calc(), Map.of(france, other));
    assertThat(other.getUnits()).containsExactly(one);
  }

  @Test
  void russiaWithoutAFeasibleTargetMovesAsHard() {
    final var feasible = new Impegno.Candidato("A", 3, true, 0, 1);
    final var near = new Impegno.Candidato("B", 1, false, 2, 9);
    assertThat(Impegno.scegliFattibile(List.of(near))).isEmpty();
    assertThat(Impegno.scegliFattibile(List.of(near, feasible)).map(Impegno.Candidato::territorio))
        .contains("A");
    // the general rule keeps the smallest gap
    assertThat(Impegno.scegli(List.of(near)).map(Impegno.Candidato::territorio)).contains("B");
    // on the board (Allies with the commitment): no target, no orders, no move
    Stratega.registra(data, player("Russians"));
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno");
    final ProData pd = proData(player("Russians"), 2);
    final List<String> before = answers(pd);
    Impegno.inizioTurno(pd, calc(), player("Russians"));
    final JsonObject russia = alliesNation("Russians");
    assertThat(russia.has("obiettivo")).isFalse();
    assertThat(Impegno.ordini(pd)).isEmpty();
    assertThat(Impegno.forzaQualcosa(pd)).isFalse();
    assertThat(answers(pd)).isEqualTo(before);
    final List<MoveDescription> moves = new ArrayList<>();
    final ProData nc = proData(player("Russians"), 2, "Non Combat Move");
    assertThat(Impegno.nonCombattimento(nc, calc(), recording(moves), player("Russians")))
        .isEmpty();
    assertThat(moves).isEmpty();
  }

  @Test
  void theBalancedLoadBuysNoTransportWithAnIdleEmptyOne() {
    final Programmi.Config cfg = Programmi.Config.get();
    final Map<String, Integer> costs =
        Map.of("infantry", 3, "artillery", 4, "armour", 6, "transport", 7, "destroyer", 8);
    final Map<String, Integer> tc = Map.of("infantry", 2, "artillery", 3, "armour", 3);
    // an idle empty transport: troops up to its room (5), never a transport
    final Map<String, Integer> idle = Programmi.caricoBilanciato(60, 1, 5, 0, 0, costs, tc, 5, cfg);
    assertThat(idle).doesNotContainKey("transport");
    assertThat(load(idle, tc)).isLessThanOrEqualTo(5).isPositive();
    // troops already waiting fill the room: nothing more
    assertThat(Programmi.caricoBilanciato(60, 1, 5, 5, 0, costs, tc, 5, cfg)).isEmpty();
    // no transport at the port: transports and the troops they carry next turn
    final Map<String, Integer> none = Programmi.caricoBilanciato(60, 0, 0, 0, 0, costs, tc, 5, cfg);
    assertThat(none).containsKey("transport");
    assertThat(load(none, tc)).isLessThanOrEqualTo(5 * none.get("transport")).isPositive();
    // escort only as needed, at most max_scorta_per_turno
    assertThat(Programmi.caricoBilanciato(60, 1, 0, 0, 1, costs, tc, 5, cfg))
        .containsEntry("destroyer", 1);
    assertThat(Programmi.caricoBilanciato(60, 1, 0, 0, 5, costs, tc, 5, cfg))
        .containsEntry("destroyer", cfg.maxScortaPerTurno());
    assertThat(Programmi.caricoBilanciato(60, 1, 0, 0, 0, costs, tc, 5, cfg)).isEmpty();
  }

  @Test
  void aLoadedUsTransportGoesTowardTheUnitedKingdomAndAnEmptyOneComesBack() {
    final GamePlayer usa = player("Americans");
    Stratega.registra(data, usa);
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno");
    // at war: allied to the British, no enemy at sea or in the air
    final var types = data.getRelationshipTypeList();
    final GamePlayer uk = player("British");
    data.performChange(
        ChangeFactory.relationshipChange(
            usa,
            uk,
            data.getRelationshipTracker().getRelationshipType(usa, uk),
            types.getDefaultAlliedRelationship()));
    // the map's trigger at war: no movement restriction any more
    if (usa.getRulesAttachment() != null) {
      data.performChange(
          ChangeFactory.attachmentPropertyReset(
              usa.getRulesAttachment(), "movementRestrictionTerritories"));
    }
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> enemy =
          t.getMatches(
              Matches.isUnitAllied(usa).negate().and(Matches.unitIsSea().or(Matches.unitIsAir())));
      if (!enemy.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(t, enemy));
      }
    }
    final Territory port = territory("Eastern United States");
    final Territory base = territory("United Kingdom");
    final Territory portZone =
        data.getMap().getNeighbors(port, Matches.territoryIsWater()).stream()
            .sorted(java.util.Comparator.comparing(Territory::getName))
            .findFirst()
            .orElseThrow();
    final Territory baseZone =
        data.getMap().getNeighbors(base, Matches.territoryIsWater()).stream()
            .filter(z -> data.getMap().getDistance(portZone, z, Matches.territoryIsWater()) > 2)
            .sorted(java.util.Comparator.comparing(Territory::getName))
            .findFirst()
            .orElseThrow();
    final Unit loaded =
        data.getUnitTypeList().getUnitTypeOrThrow("transport").create(1, usa).get(0);
    final Unit cargo = data.getUnitTypeList().getUnitTypeOrThrow("infantry").create(1, usa).get(0);
    final Unit empty = data.getUnitTypeList().getUnitTypeOrThrow("transport").create(1, usa).get(0);
    data.performChange(ChangeFactory.addUnits(portZone, List.of(loaded, cargo)));
    data.performChange(
        ChangeFactory.unitPropertyChange(cargo, loaded, Unit.PropertyName.TRANSPORTED_BY));
    data.performChange(ChangeFactory.addUnits(baseZone, List.of(empty)));
    final List<MoveDescription> moves = new ArrayList<>();
    final ProData nc = proData(usa, 2, "Non Combat Move");
    final Set<Unit> held = Impegno.nonCombattimento(nc, calc(), recording(moves), usa);
    final List<Territory> baseZones =
        data.getMap().getNeighbors(base, Matches.territoryIsWater()).stream().toList();
    final List<Territory> portZones =
        data.getMap().getNeighbors(port, Matches.territoryIsWater()).stream().toList();
    final MoveDescription out =
        moves.stream().filter(m -> m.getUnits().contains(loaded)).findFirst().orElseThrow();
    assertThat(out.getUnits()).contains(cargo);
    assertThat(seaDistance(out.getRoute().getEnd(), baseZones))
        .isLessThan(seaDistance(portZone, baseZones));
    final MoveDescription back =
        moves.stream().filter(m -> m.getUnits().contains(empty)).findFirst().orElseThrow();
    assertThat(seaDistance(back.getRoute().getEnd(), portZones))
        .isLessThan(seaDistance(baseZone, portZones));
    assertThat(held).contains(loaded, cargo, empty);
  }

  private int seaDistance(final Territory from, final List<Territory> to) {
    int best = Integer.MAX_VALUE;
    for (final Territory t : to) {
      final int d =
          from.equals(t) ? 0 : data.getMap().getDistance(from, t, Matches.territoryIsWater());
      if (d >= 0) {
        best = Math.min(best, d);
      }
    }
    return best;
  }

  private static int load(final Map<String, Integer> mix, final Map<String, Integer> tc) {
    int n = 0;
    for (final var e : mix.entrySet()) {
      n += tc.getOrDefault(e.getKey(), 0) * e.getValue();
    }
    return n;
  }

  private static IMoveDelegate recording(final List<MoveDescription> moves) {
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any()))
        .thenAnswer(
            invocation -> {
              moves.add(invocation.getArgument(0));
              return Optional.empty();
            });
    return moveDel;
  }

  private void follow(final Unit u) {
    final JsonObject st = state();
    final JsonObject n = st.getAsJsonObject("nazioni").getAsJsonObject("Germans");
    if (!n.has("unita")) {
      n.add("unita", new JsonObject());
    }
    n.getAsJsonObject("unita").addProperty(u.getId().toString(), u.getType().getName());
    Impegno.salva(data.getProperties(), "Axis", st);
  }

  private JsonObject alliesNation(final String name) {
    final Object s = data.getProperties().get(Impegno.KEY_STATO + "Allies");
    final JsonObject st =
        s == null ? new JsonObject() : JsonParser.parseString((String) s).getAsJsonObject();
    return st.has("nazioni") && st.getAsJsonObject("nazioni").has(name)
        ? st.getAsJsonObject("nazioni").getAsJsonObject(name)
        : new JsonObject();
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
