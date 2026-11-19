package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P6: the data, the pure rules of the two levers (A: valid targets, the landing in force by
 * value at 70%, one round of wait, the best exchange after it whatever the exposure; B: the weights
 * in their rounds, the home waters left out of the escort), every switch off = nothing, and A's
 * combat move on a built position (Global 1940, same names as Europe 1940).
 */
class IndicazioniP6Test extends AbstractClientSettingTestCase {
  private static final long SEED = 20261120L;

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
    final IndicazioniP6.Config c = IndicazioniP6.Config.get();
    assertThat(c.sbarcoDiDefault()).isFalse();
    assertThat(c.espostaDiDefault()).isFalse();
    assertThat(c.sogliaVittoria()).isEqualTo(70);
    assertThat(c.roundDiAttesa()).isEqualTo(1);
    assertThat(c.nazioniSbarco()).containsExactly("Americans");
    assertThat(StrategaInterruttori.SBARCO_CONCENTRATO.predefinito()).isFalse();
    assertThat(StrategaInterruttori.NORVEGIA_ESPOSTA.predefinito()).isFalse();
    for (final String file : List.of("IndicazioniP6", "SbarcoConcentrato", "ScortaSbarco")) {
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
              "Gibraltar",
              "Morocco",
              "United Kingdom",
              "Sea Zone")) {
        assertThat(src).as(file + " contiene " + word).doesNotContain(word);
      }
    }
  }

  @Test
  void withTheSwitchesOffNothingActs() {
    atWar();
    for (final String n : List.of("Americans", "British")) {
      final GamePlayer p = player(n);
      final ProData proData = proData(p, 5);
      assertThat(SbarcoConcentrato.attivo(data, p)).isFalse();
      assertThat(ScortaSbarco.espostaAttiva(data, p)).isFalse();
      assertThat(ScortaSbarco.esposta(proData, territory("Norway"))).isFalse();
      assertThat(IndicazioniP6.pesoAttacco(proData, territory("Norway"))).isZero();
      assertThat(IndicazioniP6.pesoTenuta(proData, territory("United Kingdom"))).isZero();
      final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
      assertThat(ScortaSbarco.valuta(proData, tuning.p5b(), territory("Norway"), 90, 30, 3))
          .isEmpty();
      assertThat(tuning.defensePriorityBonus(proData, territory("Norway"))).isZero();
    }
  }

  // ============================================================================ A, pure rules

  @Test
  void theValidTargetsAreAxisLandNeverFriendlyBasesNorNeutrals() {
    atWar();
    final GamePlayer us = player("Americans");
    german(territory("Normandy Bordeaux"));
    assertThat(SbarcoConcentrato.bersaglioValido(us, territory("Normandy Bordeaux"))).isTrue();
    assertThat(SbarcoConcentrato.bersaglioValido(us, territory("Western Germany"))).isTrue();
    // a friendly base, an own territory, a neutral, a sea zone
    assertThat(SbarcoConcentrato.bersaglioValido(us, territory("Gibraltar"))).isFalse();
    assertThat(SbarcoConcentrato.bersaglioValido(us, territory("Eastern United States"))).isFalse();
    assertThat(SbarcoConcentrato.bersaglioValido(us, territory("Spain"))).isFalse();
    assertThat(SbarcoConcentrato.bersaglioValido(us, territory("Sweden"))).isFalse();
    final Territory sea =
        data.getMap().getTerritories().stream().filter(Territory::isWater).findFirst().get();
    assertThat(SbarcoConcentrato.bersaglioValido(us, sea)).isFalse();
  }

  @Test
  void theLandingInForceIsTheMostValuableTargetTheWholeCargoWinsAtSeventy() {
    final List<SbarcoConcentrato.Stima> est =
        List.of(
            stima("easy", 100, 3, 0, 0),
            stima("worth", 80, 30, 0, 0),
            stima("richest", 60, 50, 0, 0));
    assertThat(SbarcoConcentrato.inForza(est, 70))
        .map(SbarcoConcentrato.Stima::bersaglio)
        .contains("worth");
    assertThat(SbarcoConcentrato.inForza(List.of(stima("hard", 69.9, 50, 0, 0)), 70)).isEmpty();
  }

  @Test
  void theForceWaitsOneRoundAtMostThenLands() {
    // out of reach of every valid target: it approaches
    assertThat(SbarcoConcentrato.decisione(false, null, 5, 1))
        .isEqualTo(SbarcoConcentrato.Decisione.AVVICINAMENTO);
    // in reach, no target won: it waits (also in the round it started waiting)
    assertThat(SbarcoConcentrato.decisione(true, null, 5, 1))
        .isEqualTo(SbarcoConcentrato.Decisione.ATTESA);
    assertThat(SbarcoConcentrato.decisione(true, 5, 5, 1))
        .isEqualTo(SbarcoConcentrato.Decisione.ATTESA);
    // one round later: it lands anyway
    assertThat(SbarcoConcentrato.decisione(true, 5, 6, 1))
        .isEqualTo(SbarcoConcentrato.Decisione.SBARCO_DOPO_ATTESA);
    assertThat(SbarcoConcentrato.decisione(true, 4, 6, 1))
        .isEqualTo(SbarcoConcentrato.Decisione.SBARCO_DOPO_ATTESA);
  }

  @Test
  void afterTheWaitTheBestExchangeWinsAndTheExposureDoesNotDropTheLanding() {
    // exchange = win x value + TUV swing - risk of the transports
    final SbarcoConcentrato.Stima exposed = stima("exposed", 80, 30, 0, 10); // 14
    final SbarcoConcentrato.Stima safe = stima("safe", 40, 30, -5, 0); // 7
    final SbarcoConcentrato.Stima easy = stima("easy", 90, 10, 2, 20); // -9
    assertThat(exposed.scambio()).isEqualTo(14);
    assertThat(SbarcoConcentrato.dopoAttesa(List.of(safe, exposed, easy)))
        .map(SbarcoConcentrato.Stima::bersaglio)
        .contains("exposed");
    // every exchange negative: it still lands on the best one
    assertThat(
            SbarcoConcentrato.dopoAttesa(
                List.of(stima("a", 10, 3, -20, 30), stima("b", 20, 3, -10, 30))))
        .map(SbarcoConcentrato.Stima::bersaglio)
        .contains("b");
  }

  // ============================================================================ A on the map

  @Test
  void theWholeLoadLandsTogetherWhereItWins() {
    atWar();
    on("sbarco_concentrato");
    final GamePlayer us = player("Americans");
    final Territory target = german(territory("Normandy Bordeaux"));
    final Territory zone = zoneNextTo(target);
    clear(target);
    add(target, "Germans", "infantry", 1);
    for (int i = 0; i < 3; i++) {
      loadedTransport(zone, 0, "infantry", "artillery");
    }
    final ProData proData = proData(us, 5);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
    final List<MoveDescription> moves = new ArrayList<>();
    final Set<Unit> held =
        SbarcoConcentrato.combattimento(proData, calc(), recorder(moves), tuning.p6a());
    // every loaded transport and its cargo held out of Hard's combat move
    assertThat(held).hasSize(9);
    final List<MoveDescription> unloads =
        moves.stream().filter(m -> !m.getRoute().getEnd().isWater()).toList();
    assertThat(unloads).isNotEmpty();
    // one target, all six units
    assertThat(unloads.stream().map(m -> m.getRoute().getEnd()).distinct()).hasSize(1);
    assertThat(unloads.stream().mapToInt(m -> m.getUnits().size()).sum()).isEqualTo(6);
    assertThat(tuning.p6a().attese).isEmpty();
  }

  @Test
  void withNoTargetWonTheForceWaitsOneRoundThenLandsOnTheBestExchange() {
    atWar();
    on("sbarco_concentrato");
    final GamePlayer us = player("Americans");
    final Territory zone = zoneNextTo(german(territory("Normandy Bordeaux")));
    // the transports cannot move: only the targets next to their zone, all strongly held
    for (final Territory t : data.getMap().getNeighbors(zone, Matches.territoryIsLand())) {
      if (SbarcoConcentrato.bersaglioValido(us, t)) {
        add(t, "Germans", "infantry", 15);
      }
    }
    for (int i = 0; i < 2; i++) {
      loadedTransport(zone, 2, "infantry", "infantry");
    }
    final ProData first = proData(us, 5);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(first.getTuning());
    final List<MoveDescription> moves = new ArrayList<>();
    SbarcoConcentrato.combattimento(first, calc(), recorder(moves), tuning.p6a());
    assertThat(moves).isEmpty();
    assertThat(tuning.p6a().attese).hasSize(2).containsValues(5, 5);
    // the next round: it lands anyway, all of it on one target
    final ProData next = proData(us, 6);
    final ProPlusTuning t2 = (ProPlusTuning) ProPlusTuning.unwrap(next.getTuning());
    t2.p6a().attese.putAll(tuning.p6a().attese);
    SbarcoConcentrato.combattimento(next, calc(), recorder(moves), t2.p6a());
    final List<MoveDescription> unloads =
        moves.stream().filter(m -> !m.getRoute().getEnd().isWater()).toList();
    assertThat(unloads).hasSize(2);
    assertThat(unloads.stream().map(m -> m.getRoute().getEnd()).distinct()).hasSize(1);
    assertThat(t2.p6a().attese).isEmpty();
  }

  @Test
  void theLeverLeavesEmptyTransportsAndThePurchasesAlone() {
    atWar();
    on("sbarco_concentrato");
    final GamePlayer us = player("Americans");
    final Territory zone = zoneNextTo(territory("Normandy Bordeaux"));
    final Unit empty = data.getUnitTypeList().getUnitTypeOrThrow("transport").create(1, us).get(0);
    data.performChange(ChangeFactory.addUnits(zone, List.of(empty)));
    assertThat(SbarcoConcentrato.caricati(data, us)).doesNotContain(empty);
    final Unit loaded = loadedTransport(zone, 0, "infantry");
    assertThat(SbarcoConcentrato.caricati(data, us)).contains(loaded).doesNotContain(empty);
    final ProData proData = proData(us, 5);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
    // no purchase orders of its own (Hard buys)
    assertThat(tuning.purchaseOrders(proData)).isEmpty();
    final List<MoveDescription> moves = new ArrayList<>();
    final Set<Unit> held =
        SbarcoConcentrato.combattimento(proData, calc(), recorder(moves), tuning.p6a());
    assertThat(held).doesNotContain(empty);
    assertThat(moves.stream().flatMap(m -> m.getUnits().stream())).doesNotContain(empty);
  }

  @Test
  void theTroopsAtAFriendlyBaseLoadAndLandTogetherWhereTheyWin() {
    atWar();
    on("sbarco_concentrato");
    final GamePlayer us = player("Americans");
    final GamePlayer uk = player("British");
    if (!us.isAllied(uk)) {
      data.performChange(
          ChangeFactory.relationshipChange(
              us,
              uk,
              data.getRelationshipTracker().getRelationshipType(us, uk),
              data.getRelationshipTypeList().getDefaultAlliedRelationship()));
    }
    final Territory base = territory("United Kingdom");
    final Territory target = german(territory("Normandy Bordeaux"));
    clear(target);
    add(target, "Germans", "infantry", 1);
    // a sea zone next to the base and to the target, without enemies
    final Territory zone =
        data.getMap().getNeighbors(base, Matches.territoryIsWater()).stream()
            .filter(z -> data.getMap().getNeighbors(z).contains(target))
            .findFirst()
            .orElseThrow();
    clear(zone);
    add(base, "Americans", "infantry", 1);
    add(base, "Americans", "artillery", 1);
    final Unit empty = data.getUnitTypeList().getUnitTypeOrThrow("transport").create(1, us).get(0);
    data.performChange(ChangeFactory.addUnits(zone, List.of(empty)));
    assertThat(SbarcoConcentrato.allaBase(data, us)).containsKey(empty);
    final ProData proData = proData(us, 5);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(proData.getTuning());
    final List<MoveDescription> moves = new ArrayList<>();
    SbarcoConcentrato.combattimento(proData, calc(), recorder(moves), tuning.p6a());
    // the load at the base, then the unload on a valid target, all the troops
    assertThat(moves.get(0).getRoute().getStart()).isEqualTo(base);
    assertThat(moves.get(0).getUnitsToSeaTransports()).isNotEmpty();
    final List<MoveDescription> unloads =
        moves.stream()
            .filter(m -> !m.getRoute().getEnd().isWater() && m.getRoute().getStart().isWater())
            .toList();
    assertThat(unloads.stream().mapToInt(m -> m.getUnits().size()).sum()).isEqualTo(2);
    assertThat(SbarcoConcentrato.bersaglioValido(us, unloads.get(0).getRoute().getEnd())).isTrue();
  }

  @Test
  void aBaseNextToTheEnemyByLandOrTheCapitalIsNotABase() {
    atWar();
    final GamePlayer us = player("Americans");
    // the capital: troops and an empty transport next to it are left to Hard
    final Territory capital = territory("Eastern United States");
    add(capital, "Americans", "infantry", 2);
    final Territory z =
        data.getMap().getNeighbors(capital, Matches.territoryIsWater()).iterator().next();
    clear(z);
    final Unit empty = data.getUnitTypeList().getUnitTypeOrThrow("transport").create(1, us).get(0);
    data.performChange(ChangeFactory.addUnits(z, List.of(empty)));
    final var bases = SbarcoConcentrato.allaBase(data, us);
    // never the own capital, never a territory next to an enemy at war by land
    assertThat(bases.values()).noneMatch(c -> c.base().equals(capital));
    assertThat(bases.values())
        .noneMatch(
            c ->
                data.getMap().getNeighbors(c.base(), Matches.territoryIsLand()).stream()
                    .anyMatch(n -> SbarcoConcentrato.bersaglioValido(us, n)));
    // a friendly territory next to enemy land with troops and a transport is not a base
    final Territory front = territory("Normandy Bordeaux");
    data.performChange(ChangeFactory.changeOwner(front, us));
    add(front, "Americans", "infantry", 2);
    final Territory fz =
        data.getMap().getNeighbors(front, Matches.territoryIsWater()).iterator().next();
    clear(fz);
    final Unit t2 = data.getUnitTypeList().getUnitTypeOrThrow("transport").create(1, us).get(0);
    data.performChange(ChangeFactory.addUnits(fz, List.of(t2)));
    assertThat(SbarcoConcentrato.allaBase(data, us).values())
        .noneMatch(c -> c.base().equals(front));
  }

  // ============================================================================== B

  @Test
  void theWeightsHoldInTheirRoundsAttackWhileTheEnemysHoldWhileAllied() {
    final List<IndicazioniP6.Bersaglio> list =
        List.of(new IndicazioniP6.Bersaglio("N", 20, 1, 8, 20, 10));
    assertThat(IndicazioniP6.peso(list, "N", 1, false)).isEqualTo(20);
    assertThat(IndicazioniP6.peso(list, "N", 8, false)).isEqualTo(20);
    assertThat(IndicazioniP6.peso(list, "N", 9, false)).isZero();
    assertThat(IndicazioniP6.peso(list, "N", 10, true)).isEqualTo(20);
    assertThat(IndicazioniP6.peso(list, "N", 11, true)).isZero();
    assertThat(IndicazioniP6.peso(list, "X", 3, false)).isZero();
    // on the map: Norway is the Germans' (attack), with B and norvegia_presto on
    atWar();
    on("norvegia_esposta");
    final ProData off = proData(player("British"), 6);
    assertThat(IndicazioniP6.pesoAttacco(off, territory("Norway"))).isZero();
    on("norvegia_presto+norvegia_esposta");
    final ProData pd = proData(player("British"), 6);
    assertThat(IndicazioniP6.pesoAttacco(pd, territory("Norway"))).isEqualTo(20);
    assertThat(IndicazioniP6.pesoTenuta(pd, territory("Norway"))).isZero();
    assertThat(ScortaSbarco.esposta(pd, territory("Norway"))).isTrue();
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(pd.getTuning());
    assertThat(tuning.attackPriorityPlanBonus(pd, territory("Norway"))).isEqualTo(20);
    // allied: held up to round 10, no attack weight
    data.performChange(ChangeFactory.changeOwner(territory("Norway"), player("British")));
    final ProData held = proData(player("British"), 10);
    assertThat(IndicazioniP6.pesoAttacco(held, territory("Norway"))).isZero();
    assertThat(IndicazioniP6.pesoTenuta(held, territory("Norway"))).isEqualTo(20);
    assertThat(
            ((ProPlusTuning) ProPlusTuning.unwrap(held.getTuning()))
                .defensePriorityBonus(held, territory("Norway")))
        .isEqualTo(20);
    assertThat(IndicazioniP6.pesoTenuta(proData(player("British"), 11), territory("Norway")))
        .isZero();
  }

  @Test
  void theExposedTransportsDoNotDropTheWeightedLanding() {
    atWar();
    on("norvegia_presto+norvegia_esposta");
    final ProData pd = proData(player("British"), 3);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(pd.getTuning());
    // without the context of a landing being checked: nothing kept
    assertThat(ScortaSbarco.valuta(pd, tuning.p5b(), territory("Norway"), 90, 30, 3)).isEmpty();
    final Territory z = zoneNextTo(territory("Norway"));
    final ScortaSbarco.Contesto c =
        new ScortaSbarco.Contesto(
            "Norway",
            calc(),
            null,
            java.util.Map.of(z, List.of()),
            new games.strategy.triplea.ai.pro.data.ProOtherMoveOptions(),
            Set.of());
    final String kept = ScortaSbarco.decidiEsposta(pd, tuning.p5b(), c, 90, 30, 3);
    assertThat(kept).isEqualTo(ScortaSbarco.KEPT_ESPOSTA);
    assertThat(tuning.p5b().piani.get("Norway").esposta()).isTrue();
    assertThat(tuning.p5b().piani.get("Norway").eseguito()).isTrue();
    // the hook answers "kept" with the context set
    tuning.p5b().ultimo = c;
    assertThat(ScortaSbarco.valuta(pd, tuning.p5b(), territory("Norway"), 90, 30, 3))
        .contains(ScortaSbarco.KEPT_ESPOSTA);
  }

  @Test
  void theHomeWatersAreNotEmptiedForTheEscort() {
    atWar();
    final GamePlayer uk = player("British");
    final Set<Territory> home = ScortaSbarco.zoneCapitale(data, uk);
    assertThat(home).isNotEmpty().allMatch(Territory::isWater);
    final Territory homeZone = home.iterator().next();
    add(homeZone, "British", "destroyer", 2);
    final Territory z = zoneNextTo(territory("Norway"));
    final List<List<Unit>> all = ScortaSbarco.candidati(data, uk, z, Set.of(), Set.of());
    final List<List<Unit>> out =
        ScortaSbarco.candidatiFuoriCasa(data, uk, z, Set.of(), Set.of(), home);
    final List<Unit> atHome = homeZone.getMatches(Matches.unitIsOwnedBy(uk));
    assertThat(all.stream().flatMap(List::stream).anyMatch(atHome::contains)).isTrue();
    assertThat(out.stream().flatMap(List::stream).noneMatch(atHome::contains)).isTrue();
  }

  // ============================================================================== helpers

  private static SbarcoConcentrato.Stima stima(
      final String t, final double win, final double value, final double swing, final double risk) {
    return new SbarcoConcentrato.Stima(t, List.of("x"), 4, win, value, swing, risk);
  }

  private static IMoveDelegate recorder(final List<MoveDescription> moves) {
    final IMoveDelegate del = mock(IMoveDelegate.class);
    when(del.performMove(any()))
        .thenAnswer(
            inv -> {
              moves.add(inv.getArgument(0));
              return Optional.empty();
            });
    return del;
  }

  private Territory zoneNextTo(final Territory land) {
    final Territory z =
        data.getMap().getNeighbors(land, Matches.territoryIsWater()).stream()
            .sorted(java.util.Comparator.comparing(Territory::getName))
            .findFirst()
            .orElseThrow();
    clear(z);
    return z;
  }

  private void clear(final Territory t) {
    if (!t.getUnits().isEmpty()) {
      data.performChange(ChangeFactory.removeUnits(t, new ArrayList<>(t.getUnits())));
    }
  }

  private Unit loadedTransport(final Territory zone, final int moved, final String... cargo) {
    final GamePlayer us = player("Americans");
    final Unit tr = data.getUnitTypeList().getUnitTypeOrThrow("transport").create(1, us).get(0);
    final List<Unit> all = new ArrayList<>(List.of(tr));
    final List<Unit> load = new ArrayList<>();
    for (final String type : cargo) {
      load.add(data.getUnitTypeList().getUnitTypeOrThrow(type).create(1, us).get(0));
    }
    all.addAll(load);
    data.performChange(ChangeFactory.addUnits(zone, all));
    for (final Unit u : load) {
      data.performChange(ChangeFactory.unitPropertyChange(u, tr, Unit.PropertyName.TRANSPORTED_BY));
    }
    if (moved > 0) {
      data.performChange(
          ChangeFactory.unitPropertyChange(
              tr, BigDecimal.valueOf(moved), Unit.PropertyName.ALREADY_MOVED));
    }
    return tr;
  }

  private Territory german(final Territory t) {
    if (!t.isOwnedBy(player("Germans"))) {
      data.performChange(ChangeFactory.changeOwner(t, player("Germans")));
    }
    return t;
  }

  private void add(final Territory t, final String owner, final String type, final int n) {
    data.performChange(
        ChangeFactory.addUnits(
            t, data.getUnitTypeList().getUnitTypeOrThrow(type).create(n, player(owner))));
  }

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
