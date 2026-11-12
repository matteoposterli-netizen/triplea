package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step R6 "SQUADRA DI GIBILTERRA" on built positions (Global 1940, the USA): in rounds 1-4 all the
 * PUs by the purchase order with the initial ships counted; the squadron leaves as one group; the
 * battle for Gibraltar's sea at 70% and the wait next to it below; from round 5 80% and complete
 * pairs, never stuck with troops waiting; the landing without a hold condition; Hard can use a unit
 * the program did not buy; the Axis share at 80 only where asked; switch off = today's behavior.
 */
class SquadraGibilterraTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261112L;
  private static final Map<String, Integer> COSTS =
      Map.of(
          "infantry", 3,
          "artillery", 4,
          "transport", 7,
          "destroyer", 8,
          "carrier", 16,
          "fighter", 10,
          "cruiser", 12,
          "submarine", 6,
          "battleship", 20);
  private static final Map<String, Integer> SEATS = Map.of("infantry", 2, "artillery", 3);

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(
        games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.FOLDER_PROPERTY,
        profile.toString());
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    for (final String n : List.of("Americans", "British", "Russians", "Germans", "Italians")) {
      Stratega.registra(data, player(n));
    }
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(
        games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.FOLDER_PROPERTY);
  }

  // ===================================================================== data and switch

  @Test
  void theDataFileHasTheValuesOfStepR6AndTheSwitchIsOffByDefault() {
    final SquadraGibilterra.Config cfg = SquadraGibilterra.Config.get();
    assertThat(cfg.accesoDiDefault()).isFalse();
    assertThat(cfg.roundCostruzione()).isEqualTo(4);
    assertThat(cfg.roundArrivo()).isEqualTo(5);
    assertThat(cfg.quotaCostruzione()).isEqualTo(100);
    assertThat(cfg.quotaPressione()).isEqualTo(80);
    assertThat(cfg.sogliaBattaglia()).isEqualTo(70);
    assertThat(cfg.sogliaSostegno()).isEqualTo(40);
    assertThat(cfg.obiettivo())
        .containsEntry("transport", 4)
        .containsEntry("carrier", 1)
        .containsEntry("fighter", 2)
        .containsEntry("battleship", 1)
        .containsEntry("cruiser", 1)
        .containsEntry("destroyer", 1)
        .containsEntry("submarine", 1);
    assertThat(cfg.ordine())
        .containsExactly("transport", "destroyer", "carrier", "cruiser", "submarine", "battleship");
    assertThat(StrategaInterruttori.SQUADRA_GIBILTERRA.acceso(data.getProperties(), "Allies"))
        .isFalse();
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,squadra_gibilterra");
    assertThat(SquadraGibilterra.attivo(data, player("Americans"))).isTrue();
    // R6 uses R5's machinery for the USA only; the United Kingdom and Russia play as Hard
    assertThat(AlleatiR5.attivo(data, player("Americans"))).isTrue();
    assertThat(Impegno.attivo(data, player("British"))).isFalse();
    assertThat(Impegno.attivo(data, player("Russians"))).isFalse();
    assertThat(AlleatiR5.attivo(data, player("British"))).isFalse();
    // without the commitment nothing
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "squadra_gibilterra");
    assertThat(SquadraGibilterra.attivo(data, player("Americans"))).isFalse();
  }

  @Test
  void switchOffIsTodaysBehavior() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno");
    assertThat(Impegno.attivo(data, player("British"))).isTrue();
    assertThat(SquadraGibilterra.attivo(data, player("Americans"))).isFalse();
    atWar();
    Impegno.inizioTurno(proData(player("Americans"), 3), calc(), player("Americans"));
    // R3a decides as today, nothing of R6 nor R5
    assertThat(decisions("teatro")).isNotEmpty();
    assertThat(decisions("via_libera_o_attesa")).isNotEmpty();
    for (final String r6 :
        List.of(
            "squadra_costruzione",
            "squadra_partenza",
            "squadra_arrivo",
            "acquisto_pressione",
            "rotta_usa")) {
      assertThat(decisions(r6)).as(r6).isEmpty();
    }
    assertThat(state().has("r6")).isFalse();
  }

  // =========================================================== A rounds 1-4: all the PUs

  @Test
  void theBuildingOrderSpendsThePusInOrderAndTheInitialShipsCount() {
    final SquadraGibilterra.Config cfg = SquadraGibilterra.Config.get();
    // 35 PUs, the initial cruiser and transport (empty): the load of the transport owned, then
    // two transports with their load (no PU left)
    final SquadraGibilterra.Costruzione r1 =
        SquadraGibilterra.costruisci(
            35, 10, Map.of("cruiser", 1, "transport", 1), 5, COSTS, SEATS, 5, 2, cfg);
    assertThat(r1.acquisto())
        .containsExactly(
            Map.entry("infantry", 3), Map.entry("artillery", 3), Map.entry("transport", 2));
    assertThat(r1.spesi()).isEqualTo(35);
    assertThat(r1.mancanti()).doesNotContainKey("cruiser").containsEntry("transport", 1);
    // the order: transports first, then destroyer, carrier with its planes, ..., battleship
    final Map<String, Integer> have = new LinkedHashMap<>(Map.of("cruiser", 1, "transport", 4));
    final SquadraGibilterra.Costruzione r2 =
        SquadraGibilterra.costruisci(60, 10, have, 0, COSTS, SEATS, 5, 2, cfg);
    assertThat(r2.acquisto())
        .containsExactly(
            Map.entry("destroyer", 1),
            Map.entry("carrier", 1),
            Map.entry("fighter", 2),
            Map.entry("submarine", 1));
    assertThat(r2.spesi()).isEqualTo(8 + 16 + 20 + 6);
    assertThat(r2.mancanti()).containsOnlyKeys("battleship");
    // complete: the PUs over the target buy more full transports
    final Map<String, Integer> all = new LinkedHashMap<>(cfg.obiettivo());
    final SquadraGibilterra.Costruzione r3 =
        SquadraGibilterra.costruisci(30, 10, all, 0, COSTS, SEATS, 5, 2, cfg);
    assertThat(r3.completa()).isTrue();
    assertThat(r3.acquisto())
        .containsEntry("transport", 2)
        .containsEntry("infantry", 2)
        .containsEntry("artillery", 2);
  }

  @Test
  void inTheBuildingRoundsAllThePusGoToTheSquadronAndTheInitialShipsJoinIt() {
    on();
    final GamePlayer usa = player("Americans");
    final Programmi.Turno p =
        new Programmi.Turno(
            proData(usa, 1), calc(), usa, "Allies", calcolo(usa), new ArrayList<>());
    final List<Unit> initial =
        new ArrayList<>(
            p.zone(p.porto()).stream()
                .flatMap(
                    z -> z.getMatches(Matches.unitIsOwnedBy(usa).and(Matches.unitIsSea())).stream())
                .toList());
    final ProData pd = proData(usa, 1);
    Impegno.inizioTurno(pd, calc(), usa);
    final JsonObject row = lastDecision("squadra_costruzione");
    final JsonObject a = row.getAsJsonArray("alternative").get(0).getAsJsonObject();
    final int pus = usa.getResources().getQuantity("PUs");
    assertThat(a.get("pu").getAsInt()).isEqualTo(pus);
    // nothing left that the next item of the order (a load, 7 PUs) could still buy
    assertThat(pus - a.get("pu_spesi").getAsInt()).isLessThan(7);
    final Optional<ProPurchaseOrders> orders = Impegno.ordini(pd);
    assertThat(orders).isPresent();
    assertThat(orders.get().minimumShare()).isGreaterThan(80);
    // no capital or factory defended before the squadron in the building rounds
    assertThat(pd.getTuning().capitalGuardThreshold(pd)).hasValue(100);
    // the initial ships of the port's zones are the squadron's (and the program's)
    final Set<String> program = SnodoAlleati.programma(data, usa);
    assertThat(initial).isNotEmpty();
    assertThat(program).containsAll(initial.stream().map(u -> u.getId().toString()).toList());
    final JsonObject r6 = SquadraGibilterra.r6(state());
    assertThat(SquadraGibilterra.squadra(data, r6)).containsAll(initial);
    // Hard never uses them before the squadron leaves
    SbarcoCoordinato.inizio(pd, calc(), usa, calcolo(usa), new ArrayList<>());
    assertThat(SbarcoCoordinato.trattenute(data, usa)).containsAll(initial);
  }

  // ================================================================ B1 one group, B2 battle

  @Test
  void theSquadronLeavesAsOneGroup() {
    assertThat(SquadraGibilterra.parte(true, true, 3, 5, 1)).isTrue();
    assertThat(SquadraGibilterra.parte(true, false, 4, 5, 1)).isFalse();
    assertThat(SquadraGibilterra.parte(true, false, 5, 5, 1)).isTrue();
    assertThat(SquadraGibilterra.parte(true, false, 4, 5, 2)).isTrue();
    assertThat(SquadraGibilterra.parte(false, true, 5, 5, 1)).isFalse();
    on();
    atWar();
    final GamePlayer usa = player("Americans");
    final Territory port = territory("101 Sea Zone");
    final Unit tr = loadedTransport(port, "Americans", "infantry", "artillery");
    addUnits(port, "Americans", "destroyer", 1);
    addUnits(port, "Americans", "battleship", 1);
    final List<Unit> ships = port.getMatches(Matches.unitIsOwnedBy(usa).and(Matches.unitIsSea()));
    squadra(ships);
    Impegno.inizioTurno(proData(usa, 5), calc(), usa);
    final List<MoveDescription> moves = new ArrayList<>();
    Impegno.nonCombattimento(proData(usa, 5, "Non Combat Move"), calc(), recording(moves), usa);
    final List<MoveDescription> fromPort =
        moves.stream().filter(m -> m.getRoute().getStart().equals(port)).toList();
    assertThat(fromPort).as(moves.toString()).hasSize(1);
    assertThat(fromPort.get(0).getUnits()).containsAll(ships).containsAll(tr.getTransporting());
    assertThat(lastDecision("squadra_partenza").get("round").getAsInt()).isEqualTo(5);
  }

  @Test
  void theBattleForGibraltarsSeaAt70AndTheWaitNextToItBelow() {
    assertThat(SquadraGibilterra.vince(70, 70)).isTrue();
    assertThat(SquadraGibilterra.vince(69.99, 70)).isFalse();
    on();
    atWar();
    final GamePlayer usa = player("Americans");
    final Territory port = territory("101 Sea Zone");
    // a strong squadron at the port, one Italian destroyer in 91: battle
    addUnits(port, "Americans", "battleship", 2);
    addUnits(port, "Americans", "cruiser", 2);
    squadra(port.getMatches(Matches.unitIsOwnedBy(usa).and(Matches.unitIsSea())));
    addUnits(territory("91 Sea Zone"), "Italians", "destroyer", 1);
    Impegno.inizioTurno(proData(usa, 5), calc(), usa);
    JsonObject battle = lastDecision("battaglia_gibilterra");
    assertThat(battle.getAsJsonObject("motivo").get("codice").getAsString())
        .as(battle.toString())
        .isEqualTo("BATTAGLIA");
    final List<MoveDescription> moves = new ArrayList<>();
    SbarcoCoordinato.combattimento(proData(usa, 5, "Combat Move"), calc(), recording(moves), usa);
    assertThat(moves).anyMatch(m -> m.getRoute().getEnd().equals(territory("91 Sea Zone")));
    // a big Italian fleet: no battle, the squadron waits in the zone next to Gibraltar's
    addUnits(territory("91 Sea Zone"), "Italians", "battleship", 6);
    SquadraGibilterra.r6(state());
    final JsonObject st = state();
    SquadraGibilterra.r6(st).addProperty("fase", SquadraGibilterra.COSTRUZIONE);
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    Impegno.inizioTurno(proData(usa, 6), calc(), usa);
    battle = lastDecision("battaglia_gibilterra");
    assertThat(battle.getAsJsonObject("motivo").get("codice").getAsString())
        .as(battle.toString())
        .isEqualTo("ATTESA_ZONA_ACCANTO");
    final String wait =
        battle
            .getAsJsonArray("alternative")
            .get(0)
            .getAsJsonObject()
            .get("zona_attesa")
            .getAsString();
    assertThat(data.getMap().getNeighbors(territory("91 Sea Zone"))).contains(territory(wait));
    final List<MoveDescription> combat = new ArrayList<>();
    SbarcoCoordinato.combattimento(proData(usa, 6, "Combat Move"), calc(), recording(combat), usa);
    assertThat(combat).noneMatch(m -> m.getRoute().getEnd().equals(territory("91 Sea Zone")));
  }

  // ===================================================== C from round 5: 80%, complete pairs

  @Test
  void fromRound5TheProgramUses80PercentAndBuysCompletePairsNeverStuck() {
    final SquadraGibilterra.Config cfg = SquadraGibilterra.Config.get();
    data.getSequence().setRoundAndStep(5, "Purchase", player("Americans"));
    // 80% after the capital's garrison of case c
    assertThat(SquadraGibilterra.pu(data, 60, 10)).isEqualTo(40);
    data.getSequence().setRoundAndStep(4, "Purchase", player("Americans"));
    assertThat(SquadraGibilterra.pu(data, 60, 10)).isEqualTo(60);
    // troops waiting fill the seats: complete pairs (a transport with its load), first an escort
    final Map<String, Integer> pairs =
        SquadraGibilterra.spola(40, 10, 0, 6, true, COSTS, SEATS, 5, cfg);
    assertThat(pairs).containsEntry("destroyer", 1).containsEntry("transport", 2);
    assertThat(pairs.get("infantry")).isEqualTo(pairs.get("transport"));
    assertThat(pairs.get("artillery")).isEqualTo(pairs.get("transport"));
    // more seats than troops: troops, never an idle transport
    final Map<String, Integer> troops =
        SquadraGibilterra.spola(14, 10, 10, 0, false, COSTS, SEATS, 5, cfg);
    assertThat(troops).doesNotContainKey("transport");
    final int seats =
        troops.getOrDefault("infantry", 0) * 2 + troops.getOrDefault("artillery", 0) * 3;
    assertThat(seats).isBetween(1, 10);
    // the support of the squadron: the cheapest effective unit first, down to <= 40
    final Map<String, Integer> support =
        SquadraGibilterra.sostegno(
            m -> 80.0 - 15 * m.getOrDefault("destroyer", 0) - 25 * m.getOrDefault("cruiser", 0),
            cfg.tipiSostegno(),
            COSTS,
            Set.of("fighter"),
            0,
            100,
            10,
            4,
            40);
    // the cruiser lowers it more per PU (25/12 > 15/8): two cruisers bring it to 30
    assertThat(support).containsOnlyKeys("cruiser").containsEntry("cruiser", 2);
    assertThat(80.0 - 25 * support.get("cruiser")).isLessThanOrEqualTo(40);
  }

  @Test
  void thePressurePurchaseOfRound5BuysPairsWhenTroopsWait() {
    on();
    atWar();
    final GamePlayer usa = player("Americans");
    final Territory port = territory("Eastern United States");
    // six program troops waiting at the port, no free seat
    final List<Unit> waiting = data.getUnitTypeList().getUnitTypeOrThrow("infantry").create(6, usa);
    data.performChange(ChangeFactory.addUnits(port, waiting));
    programma("Americans", waiting);
    final JsonObject st = state();
    SquadraGibilterra.r6(st).addProperty("fase", SquadraGibilterra.ARRIVATA);
    SquadraGibilterra.r6(st).addProperty("iniziali", "fatto");
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    final ProData pd = proData(usa, 5);
    Impegno.inizioTurno(pd, calc(), usa);
    final JsonObject row = lastDecision("acquisto_pressione");
    final JsonObject a = row.getAsJsonArray("alternative").get(0).getAsJsonObject();
    assertThat(a.getAsJsonObject("spola").has("transport")).as(row.toString()).isTrue();
    final int pu = a.get("pu").getAsInt();
    final int turn = a.get("pu_turno").getAsInt();
    final int c = a.get("pu_caso_c").getAsInt();
    assertThat(pu).isEqualTo((int) Math.floor((turn - c) * 0.8));
  }

  @Test
  void theSquadronGetsSupportWhenTheRealisticEnemyAttackWinsMoreThan40() {
    on();
    atWar();
    final GamePlayer usa = player("Americans");
    final Territory base = territory("91 Sea Zone");
    addUnits(base, "Americans", "destroyer", 1);
    squadra(base.getMatches(Matches.unitIsOwnedBy(usa)));
    // a German fleet next to the base on the Atlantic side (from 92 the strait of Gibraltar is
    // closed to the Axis while Gibraltar is allied)
    addUnits(territory("104 Sea Zone"), "Germans", "battleship", 2);
    addUnits(territory("104 Sea Zone"), "Germans", "cruiser", 2);
    final JsonObject st = state();
    SquadraGibilterra.r6(st).addProperty("fase", SquadraGibilterra.ARRIVATA);
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    Impegno.inizioTurno(proData(usa, 6), calc(), usa);
    final JsonObject row = lastDecision("acquisto_pressione");
    final JsonObject a = row.getAsJsonArray("alternative").get(0).getAsJsonObject();
    assertThat(a.get("vittoria_nemica_prima_pct").getAsDouble())
        .as(row.toString())
        .isGreaterThan(40);
    assertThat(a.getAsJsonObject("sostegno").size()).as(row.toString()).isPositive();
    assertThat(a.get("vittoria_nemica_dopo_pct").getAsDouble())
        .isLessThan(a.get("vittoria_nemica_prima_pct").getAsDouble());
  }

  // ================================================== C3 no hold; C4 Hard keeps the others

  @Test
  void theLandingFromGibraltarHasNoHoldCondition() {
    on();
    atWar();
    for (final String n : List.of("Southern France", "France")) {
      final Territory t = territory(n);
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
      data.performChange(ChangeFactory.changeOwner(t, player("Germans")));
    }
    addUnits(territory("Southern France"), "Germans", "infantry", 1);
    addUnits(territory("France"), "Germans", "armour", 25);
    addUnits(territory("92 Sea Zone"), "Americans", "transport", 2);
    addUnits(territory("Gibraltar"), "Americans", "infantry", 2);
    addUnits(territory("Gibraltar"), "Americans", "artillery", 2);
    final List<Unit> troops =
        territory("Gibraltar").getMatches(Matches.unitIsOwnedBy(player("Americans")));
    final List<Unit> program = new ArrayList<>(troops);
    program.addAll(territory("92 Sea Zone").getMatches(Matches.unitIsOwnedBy(player("Americans"))));
    programma("Americans", program);
    final JsonObject st = state();
    SquadraGibilterra.r6(st).addProperty("fase", SquadraGibilterra.ARRIVATA);
    SquadraGibilterra.r6(st).addProperty("iniziali", "fatto");
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    Impegno.inizioTurno(proData(player("Americans"), 6), calc(), player("Americans"));
    final JsonObject hub = lastDecision("snodo_usa");
    assertThat(hub.getAsJsonObject("motivo").get("codice").getAsString())
        .as(hub.toString())
        .isEqualTo("FRANCIA");
    final JsonObject alt = alternative(hub, "Southern France");
    assertThat(alt.get("vittoria_pct").getAsDouble()).isGreaterThanOrEqualTo(70);
    // the hold is low: R3a would wait, R6 lands
    assertThat(alt.get("tenuta_stimata_pct_info").getAsDouble()).isLessThan(60);
    final List<MoveDescription> moves = new ArrayList<>();
    SbarcoCoordinato.combattimento(
        proData(player("Americans"), 6, "Combat Move"),
        calc(),
        recording(moves),
        player("Americans"));
    final Set<Unit> landed = new HashSet<>();
    for (final MoveDescription m : moves) {
      if (m.getRoute().getEnd().equals(territory("Southern France"))) {
        landed.addAll(m.getUnits());
      }
    }
    assertThat(landed).containsAll(troops);
  }

  @Test
  void hardCanUseAUnitTheProgramDidNotBuy() {
    on();
    atWar();
    final Territory gib = territory("Gibraltar");
    addUnits(gib, "Americans", "infantry", 2);
    final List<Unit> us = gib.getMatches(Matches.unitIsOwnedBy(player("Americans")));
    programma("Americans", List.of(us.get(0)));
    final ProData pd = proData(player("Americans"), 6);
    Impegno.inizioTurno(pd, calc(), player("Americans"));
    final Set<Unit> held = SbarcoCoordinato.trattenute(data, player("Americans"));
    assertThat(held).contains(us.get(0)).doesNotContain(us.get(1));
  }

  // ============================================================== D the Axis share

  @Test
  void theAxisShareIs80OnlyInTheConfigurationThatAsksForIt() {
    final GamePlayer germans = player("Germans");
    assertThat(Impegno.quotaOperazione(data, germans)).isEqualTo(70);
    Impegno.setQuota(data.getProperties(), "Axis", 80.0);
    assertThat(Impegno.quotaOperazione(data, germans)).isEqualTo(80);
    assertThat(Impegno.quotaOperazione(data, player("Italians"))).isEqualTo(80);
    assertThat(Impegno.quotaOperazione(data, player("Americans"))).isEqualTo(70);
    Impegno.setQuota(data.getProperties(), "Axis", null);
    assertThat(Impegno.quotaOperazione(data, germans)).isEqualTo(70);
  }

  // ================================================================== helpers

  private void on() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,squadra_gibilterra");
    // as on the Europe 1940 map: a major factory (10 units) at the port (Global 1940: minor, 3)
    final Territory port = territory("Eastern United States");
    data.performChange(
        ChangeFactory.removeUnits(port, port.getMatches(Matches.unitCanProduceUnits())));
    addUnits(port, "Americans", "factory_major", 1);
  }

  /** {@code ships} are the squadron (and the program's): the initial adoption is done. */
  private void squadra(final List<Unit> ships) {
    final JsonObject st = state();
    final JsonObject r6 = SquadraGibilterra.r6(st);
    final JsonObject ids = SquadraGibilterra.ids(r6);
    ships.forEach(u -> ids.addProperty(u.getId().toString(), u.getType().getName()));
    r6.addProperty("iniziali", "fatto");
    r6.addProperty("completa", true);
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    final List<Unit> program = new ArrayList<>(ships);
    ships.forEach(s -> program.addAll(s.getTransporting()));
    programma("Americans", program);
  }

  /** The USA at war with the Axis, allied to the British; no Axis ship or plane anywhere. */
  private void atWar() {
    final GamePlayer usa = player("Americans");
    final var types = data.getRelationshipTypeList();
    for (final String a : List.of("British", "French", "Russians")) {
      final GamePlayer ally = player(a);
      data.performChange(
          ChangeFactory.relationshipChange(
              usa,
              ally,
              data.getRelationshipTracker().getRelationshipType(usa, ally),
              types.getDefaultAlliedRelationship()));
    }
    for (final String e : List.of("Germans", "Italians", "Japanese")) {
      war("Americans", e);
      war("British", e);
    }
    if (usa.getRulesAttachment() != null) {
      data.performChange(
          ChangeFactory.attachmentPropertyReset(
              usa.getRulesAttachment(), "movementRestrictionTerritories"));
    }
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> axis =
          t.getMatches(
              Matches.isUnitAllied(usa).negate().and(Matches.unitIsSea().or(Matches.unitIsAir())));
      if (!axis.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(t, axis));
      }
    }
  }

  private void war(final String a, final String b) {
    if (data.getRelationshipTracker().isAtWar(player(a), player(b))) {
      return;
    }
    data.performChange(
        ChangeFactory.relationshipChange(
            player(a),
            player(b),
            data.getRelationshipTracker().getRelationshipType(player(a), player(b)),
            data.getRelationshipTypeList().getDefaultWarRelationship()));
  }

  private void programma(final String nation, final List<Unit> units) {
    final JsonObject imp = Impegno.stato(data.getProperties(), "Allies");
    final JsonObject ids =
        Impegno.nazione(imp, nation).has("programma_ids")
            ? Impegno.nazione(imp, nation).getAsJsonObject("programma_ids")
            : new JsonObject();
    units.forEach(u -> ids.addProperty(u.getId().toString(), u.getType().getName()));
    Impegno.nazione(imp, nation).add("programma_ids", ids);
    Impegno.salva(data.getProperties(), "Allies", imp);
  }

  private Unit loadedTransport(final Territory zone, final String owner, final String... cargo) {
    final Unit tr =
        data.getUnitTypeList().getUnitTypeOrThrow("transport").create(1, player(owner)).get(0);
    final List<Unit> all = new ArrayList<>(List.of(tr));
    final List<Unit> load = new ArrayList<>();
    for (final String type : cargo) {
      load.add(data.getUnitTypeList().getUnitTypeOrThrow(type).create(1, player(owner)).get(0));
    }
    all.addAll(load);
    data.performChange(ChangeFactory.addUnits(zone, all));
    for (final Unit u : load) {
      data.performChange(ChangeFactory.unitPropertyChange(u, tr, Unit.PropertyName.TRANSPORTED_BY));
    }
    return tr;
  }

  private void addUnits(final Territory t, final String owner, final String type, final int n) {
    data.performChange(
        ChangeFactory.addUnits(
            t, data.getUnitTypeList().getUnitTypeOrThrow(type).create(n, player(owner))));
  }

  private JsonObject state() {
    return SbarcoCoordinato.stato(data.getProperties(), "Allies");
  }

  private List<JsonObject> decisions(final String tipo) {
    return RegistroDecisioni.righe(data).stream()
        .filter(r -> "decisione".equals(r.get("riga").getAsString()))
        .filter(r -> tipo.equals(r.get("tipo").getAsString()))
        .toList();
  }

  private JsonObject lastDecision(final String tipo) {
    final List<JsonObject> rows = decisions(tipo);
    assertThat(rows).as("decisioni " + tipo).isNotEmpty();
    return rows.get(rows.size() - 1);
  }

  private static JsonObject alternative(final JsonObject row, final String territory) {
    for (final var e : row.getAsJsonArray("alternative")) {
      final JsonObject a = e.getAsJsonObject();
      if (a.has("territorio") && territory.equals(a.get("territorio").getAsString())) {
        return a;
      }
    }
    throw new AssertionError(territory + " non tra le alternative: " + row);
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

  private CampagnaOmbra.Calcolo calcolo(final GamePlayer player) {
    return new CampagnaOmbra.Calcolo(
        proData(player, 3, "Purchase"), calc(), player, "Allies", CampagnaOmbra.Config.get());
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
