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
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step R6b "TRASPORTI PROTETTI" on built positions (Global 1940, the USA with the squadron at
 * Gibraltar): a wave toward an exposed zone takes the minimum escort and leaves the rest in zone
 * 91; without enough escort it unloads from another safe zone next to the target; with none it is
 * given up and the next target is chosen; the empty transports come back with their escort to zone
 * 91 and load there (or go back to the port); no program transport ends the turn in a zone where
 * the enemy wins more than 40%; the program's troops at the port (and at the second factory) leave
 * when there are seats; the units bought in round 4 reach the squadron in round 5 in one group; the
 * two boxes of the strategist's panel turn the switches on.
 */
class TrasportiProtettiTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261113L;

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
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

  @Test
  void theDataFileHasTheValuesOfStepR6b() {
    final TrasportiProtetti.Config cfg = TrasportiProtetti.Config.get();
    assertThat(cfg.soglia()).isEqualTo(40);
    assertThat(cfg.roundPericolo()).isEqualTo(3);
    assertThat(cfg.zonaRientro()).isEqualTo("91 Sea Zone");
    assertThat(cfg.secondaFabbrica()).isEqualTo("Central United States");
    assertThat(TrasportiProtetti.sicura(40, 40)).isTrue();
    assertThat(TrasportiProtetti.sicura(40.01, 40)).isFalse();
    assertThat(TrasportiProtetti.soglia(true, cfg)).isEqualTo(20);
  }

  // ======================================================== A3/B1 the waves and their escort

  @Test
  void aWaveTowardAnExposedZoneTakesTheMinimumEscortAndLeavesTheRestAtGibraltar() {
    arrived();
    final GamePlayer usa = player("Americans");
    enemyCoast("Southern France", 1);
    clearSea("93 Sea Zone", "95 Sea Zone", "92 Sea Zone");
    final List<Unit> troops = programTroops(territory("Gibraltar"), 2);
    final List<Unit> transports = add(territory("91 Sea Zone"), "Americans", "transport", 1);
    programma(transports);
    programma(troops);
    final List<Unit> squad = new ArrayList<>();
    squad.addAll(add(territory("91 Sea Zone"), "Americans", "destroyer", 3));
    squad.addAll(add(territory("91 Sea Zone"), "Americans", "cruiser", 2));
    squadra(squad);
    // an Italian destroyer in 94 reaches 93 (and 95): the transports alone would be sunk
    add(territory("94 Sea Zone"), "Italians", "destroyer", 1);
    final SbarcoCoordinato.Turno t = turno(usa, 6, "Purchase");
    final Territory target = territory("Southern France");
    final SbarcoCoordinato.Turno.Piano plan = plan(t, target);
    assertThat(plan.carico()).isNotEmpty();
    final SbarcoCoordinato.Turno.Stima s = t.stima(plan, false);
    final Optional<TrasportiProtetti.Protezione> pr =
        TrasportiProtetti.proteggi(t, target, plan, s, t.delProgramma(), "RESTO");
    final JsonObject row = lastDecision("trasporti_sicurezza");
    assertThat(pr).as(row.toString()).isPresent();
    assertThat(pr.get().esito()).as(row.toString()).isEqualTo(TrasportiProtetti.SCORTATA);
    assertThat(pr.get().sicurezza().vittoria()).isLessThanOrEqualTo(40);
    // the minimum: fewer ships than the squadron, the rest stays in 91
    assertThat(pr.get().scorta()).isNotEmpty().hasSizeLessThan(squad.size());
    final JsonObject alone =
        row.getAsJsonArray("alternative").get(0).getAsJsonObject().getAsJsonObject("senza_scorta");
    assertThat(alone.get("vittoria_nemica_pct").getAsDouble()).isGreaterThan(40);
    // one ship less would not be enough (greedy order: each one was needed)
    if (pr.get().scorta().size() > 1) {
      final List<Unit> fewer = new ArrayList<>(transports);
      fewer.addAll(pr.get().scorta().subList(0, pr.get().scorta().size() - 1));
      assertThat(TrasportiProtetti.valuta(t.p, pr.get().zona(), fewer, List.of()).sicura())
          .isFalse();
    }
    // in the combat the escort sails to the unload zone, the rest stays in 91
    final JsonObject w = new JsonObject();
    w.addProperty("bersaglio", target.getName());
    TrasportiProtetti.salvaInOnda(w, pr.get());
    final List<MoveDescription> moves = new ArrayList<>();
    t.moveDel = recording(moves);
    TrasportiProtetti.dopoLoSbarco(t, w, pr.get().piano(), t.moveDel);
    final Set<Unit> moved = new HashSet<>();
    moves.forEach(m -> moved.addAll(m.getUnits()));
    assertThat(moved).containsExactlyInAnyOrderElementsOf(pr.get().scorta());
    final JsonObject escort = lastDecision("scorta_ondata");
    final JsonObject a = escort.getAsJsonArray("alternative").get(0).getAsJsonObject();
    assertThat(a.get("navi_andate").getAsInt()).isEqualTo(pr.get().scorta().size());
  }

  @Test
  void withoutEnoughEscortTheWaveUnloadsFromAnotherSafeZoneNextToTheTarget() {
    arrived();
    final GamePlayer usa = player("Americans");
    // Morocco of the Axis: unload zones 91 (next to the transports) and 92 (behind the strait)
    enemyCoast("Morocco", 1);
    final List<Unit> troops = programTroops(territory("Gibraltar"), 2);
    final List<Unit> transports = add(territory("91 Sea Zone"), "Americans", "transport", 1);
    programma(transports);
    programma(troops);
    squadra(add(territory("91 Sea Zone"), "Americans", "destroyer", 1));
    // a German fleet in 104 reaches 91, not 92 (the strait of Gibraltar is allied)
    add(territory("104 Sea Zone"), "Germans", "battleship", 4);
    final SbarcoCoordinato.Turno t = turno(usa, 6, "Purchase");
    final Territory target = territory("Morocco");
    final SbarcoCoordinato.Turno.Piano plan = plan(t, target);
    assertThat(SquadraGibilterra.zonaSbarco(plan)).isEqualTo(territory("91 Sea Zone"));
    final Optional<TrasportiProtetti.Protezione> pr =
        TrasportiProtetti.proteggi(
            t, target, plan, t.stima(plan, false), t.delProgramma(), "VELOCI_AFRICA");
    final JsonObject row = lastDecision("trasporti_sicurezza");
    assertThat(pr).as(row.toString()).isPresent();
    assertThat(pr.get().esito()).isEqualTo(TrasportiProtetti.ZONA_CAMBIATA);
    assertThat(pr.get().zona()).isEqualTo(territory("92 Sea Zone"));
    assertThat(pr.get().piano().viaggi())
        .allMatch(v -> v.rotta().getEnd().equals(territory("92 Sea Zone")));
    // the first zone was tried with all the escort and stayed unsafe
    final JsonObject first = row.getAsJsonArray("alternative").get(0).getAsJsonObject();
    assertThat(first.get("zona").getAsString()).isEqualTo("91 Sea Zone");
    assertThat(first.get("esito").getAsString()).isEqualTo("NON_SICURA");
  }

  @Test
  void withNoSafeZoneTheTargetIsGivenUpAndTheNextByValueIsChosen() {
    arrived();
    final GamePlayer usa = player("Americans");
    enemyCoast("Southern France", 1);
    programma(programTroops(territory("Gibraltar"), 2));
    programma(add(territory("92 Sea Zone"), "Americans", "transport", 1));
    squadra(add(territory("91 Sea Zone"), "Americans", "destroyer", 1));
    // a big Italian fleet reaches 93 and 95: nothing protects the transports
    add(territory("96 Sea Zone"), "Italians", "battleship", 4);
    add(territory("96 Sea Zone"), "Italians", "cruiser", 4);
    final SbarcoCoordinato.Turno t = turno(usa, 6, "Purchase");
    final Territory target = territory("Southern France");
    final SbarcoCoordinato.Turno.Piano plan = plan(t, target);
    final Optional<TrasportiProtetti.Protezione> pr =
        TrasportiProtetti.proteggi(
            t, target, plan, t.stima(plan, false), t.delProgramma(), "RESTO");
    assertThat(pr).isEmpty();
    final JsonObject row = lastDecision("trasporti_sicurezza");
    assertThat(row.getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo(TrasportiProtetti.RINUNCIA);
    assertThat(row.getAsJsonArray("alternative")).isNotEmpty();
    // the choice: the first protected target by value, the given up ones skipped
    final List<String> tried = new ArrayList<>();
    final Optional<Map.Entry<String, String>> chosen =
        AlleatiR5.primoProtetto(
            List.of("Southern France", "Sardinia", "Sicily"),
            x -> {
              tried.add(x);
              return "Southern France".equals(x) ? Optional.empty() : Optional.of("93");
            });
    assertThat(chosen).isPresent();
    assertThat(chosen.get().getKey()).isEqualTo("Sardinia");
    assertThat(tried).containsExactly("Southern France", "Sardinia");
    assertThat(AlleatiR5.primoProtetto(List.of("a"), x -> Optional.empty())).isEmpty();
  }

  // ===================================================== B2-B3 the way back, A1 everywhere

  @Test
  void theEmptyTransportsComeBackWithTheirEscortTo91AndLoadThere() {
    arrived();
    final GamePlayer usa = player("Americans");
    final List<Unit> empty = add(territory("93 Sea Zone"), "Americans", "transport", 2);
    programma(empty);
    final List<Unit> escort = add(territory("93 Sea Zone"), "Americans", "destroyer", 1);
    final List<Unit> squad = new ArrayList<>(escort);
    squad.addAll(add(territory("91 Sea Zone"), "Americans", "cruiser", 1));
    squadra(squad);
    programma(programTroops(territory("Gibraltar"), 2));
    // the wave of the last round: transports and escort in 93
    final JsonObject st = state();
    final JsonObject e = new JsonObject();
    e.addProperty("round", 5);
    e.addProperty("zona", "93 Sea Zone");
    e.addProperty("bersaglio", "Southern France");
    final com.google.gson.JsonArray tr = new com.google.gson.JsonArray();
    empty.forEach(u -> tr.add(u.getId().toString()));
    e.add("trasporti", tr);
    final com.google.gson.JsonArray es = new com.google.gson.JsonArray();
    escort.forEach(u -> es.add(u.getId().toString()));
    e.add("scorta", es);
    TrasportiProtetti.scorte(st).add(e);
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    final SbarcoCoordinato.Turno t = turno(usa, 6, "Non Combat Move");
    final List<MoveDescription> moves = new ArrayList<>();
    TrasportiProtetti.raduna(t, territory("91 Sea Zone"), recording(moves));
    // one group: the two transports with their escort, to 91
    final List<MoveDescription> back =
        moves.stream()
            .filter(m -> m.getRoute().getStart().equals(territory("93 Sea Zone")))
            .toList();
    assertThat(back).as(moves.toString()).hasSize(1);
    assertThat(back.get(0).getUnits()).containsAll(empty).containsAll(escort);
    assertThat(back.get(0).getRoute().getEnd()).isEqualTo(territory("91 Sea Zone"));
    assertThat(
            lastDecision("rientro_trasporti").getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("RIENTRO");
    // next turn, in 91 with the program's troops at Gibraltar: the wave's plan loads them there
    final List<Unit> at91 = add(territory("91 Sea Zone"), "Americans", "transport", 1);
    programma(at91);
    enemyCoast("Southern France", 1);
    final SbarcoCoordinato.Turno next = turno(usa, 7, "Purchase");
    final SbarcoCoordinato.Turno.Piano plan = plan(next, territory("Southern France"));
    assertThat(plan.viaggi())
        .anyMatch(v -> at91.contains(v.trasporto()) && !v.daCaricare().isEmpty());
  }

  @Test
  void withoutTroopsAtGibraltarTheEmptyTransportsGoBackToThePortOnlyThroughSafeStops() {
    arrived();
    final GamePlayer usa = player("Americans");
    final List<Unit> empty = add(territory("91 Sea Zone"), "Americans", "transport", 2);
    programma(empty);
    squadra(add(territory("91 Sea Zone"), "Americans", "cruiser", 1));
    SbarcoCoordinato.Turno t = turno(usa, 6, "Non Combat Move");
    List<MoveDescription> moves = new ArrayList<>();
    TrasportiProtetti.raduna(t, territory("91 Sea Zone"), recording(moves));
    final List<MoveDescription> toPort =
        moves.stream().filter(m -> m.getUnits().containsAll(empty)).toList();
    assertThat(toPort).as(moves.toString()).hasSize(1);
    final Territory end = toPort.get(0).getRoute().getEnd();
    assertThat(TrasportiProtetti.valuta(t.p, end, empty, List.of()).sicura()).isTrue();
    // a German fleet in the Atlantic: every stop is exposed, the transports stay in 91
    add(territory("102 Sea Zone"), "Germans", "battleship", 2);
    add(territory("103 Sea Zone"), "Germans", "battleship", 2);
    t = turno(usa, 7, "Non Combat Move");
    moves = new ArrayList<>();
    TrasportiProtetti.raduna(t, territory("91 Sea Zone"), recording(moves));
    assertThat(moves).noneMatch(m -> m.getUnits().stream().anyMatch(empty::contains));
    assertThat(
            lastDecision("rientro_trasporti").getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("FERMO");
  }

  @Test
  void noProgramTransportEndsTheTurnInAZoneWhereTheEnemyWinsMoreThan40() {
    arrived();
    final GamePlayer usa = player("Americans");
    final Territory port = territory("101 Sea Zone");
    final Unit loaded = loadedTransport(port, "infantry", "artillery");
    programma(List.of(loaded));
    programma(loaded.getTransporting());
    // zone 91 (the arrival) and every zone on the way are within reach of a German fleet in 104
    add(territory("104 Sea Zone"), "Germans", "battleship", 3);
    add(territory("90 Sea Zone"), "Germans", "battleship", 3);
    final SbarcoCoordinato.Turno t = turno(usa, 6, "Non Combat Move");
    final List<MoveDescription> moves = new ArrayList<>();
    final Set<Unit> held = new LinkedHashSet<>();
    final Optional<Territory> to =
        TrasportiProtetti.naviga(
            t.p,
            List.of(loaded),
            port,
            List.of(territory("91 Sea Zone")),
            held,
            recording(moves),
            "spola",
            t.st);
    for (final MoveDescription m : moves) {
      assertThat(
              TrasportiProtetti.valuta(t.p, m.getRoute().getEnd(), m.getUnits(), List.of())
                  .sicura())
          .as(m.toString())
          .isTrue();
    }
    if (to.isEmpty()) {
      assertThat(moves).isEmpty();
      assertThat(
              lastDecision("trasporti_sicurezza")
                  .getAsJsonObject("motivo")
                  .get("codice")
                  .getAsString())
          .isEqualTo("FERMO");
    }
    assertThat(held).contains(loaded);
  }

  // ============================================================== C the shuttle and the port

  @Test
  void theProgramTroopsAtThePortAndAtTheSecondFactoryLeaveWhenThereAreSeats() {
    arrived();
    final GamePlayer usa = player("Americans");
    final Territory central = territory("Central United States");
    addFactory(central);
    final List<Unit> atPort = programTroops(territory("Eastern United States"), 2);
    final List<Unit> atSecond = programTroops(central, 1);
    programma(atPort);
    programma(atSecond);
    programma(add(territory("101 Sea Zone"), "Americans", "transport", 3));
    final ProData pd = proData(usa, 6, "Non Combat Move");
    final Programmi.Turno p =
        new Programmi.Turno(pd, calc(), usa, "Allies", calcolo(usa), new ArrayList<>());
    assertThat(TrasportiProtetti.secondaFabbrica(p)).isEqualTo(central);
    final List<MoveDescription> moves = new ArrayList<>();
    final JsonObject turn = new JsonObject();
    p.spola(recording(moves), 0, turn);
    final Set<Unit> loadedUnits = new HashSet<>();
    for (final MoveDescription m : moves) {
      if (m.getRoute().getEnd().equals(territory("101 Sea Zone"))
          && !m.getUnitsToSeaTransports().isEmpty()) {
        loadedUnits.addAll(m.getUnits());
      }
    }
    // 3 transports, 15 seats: the 4 troops of the port and the 2 of the second factory
    assertThat(loadedUnits).containsAll(atSecond).containsAll(atPort);
    assertThat(turn.getAsJsonObject("spola").get("truppe_rimaste_al_porto").getAsInt())
        .isEqualTo(atPort.size() + atSecond.size() - loadedUnits.size());
    // the purchase split: the transports and what fits at the port there, the rest at the second
    // factory
    final Map<String, Integer> extra =
        TrasportiProtetti.eccedenza(
            Map.of("transport", 4, "infantry", 4, "artillery", 4),
            10,
            Set.of("infantry", "artillery"));
    assertThat(extra.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
  }

  @Test
  void theShuttleBuysAnEscortOnlyWhenTheArrivalZoneIsNotSafe() {
    arrived();
    final GamePlayer usa = player("Americans");
    squadra(add(territory("91 Sea Zone"), "Americans", "cruiser", 2));
    Programmi.Turno p =
        new Programmi.Turno(
            proData(usa, 6), calc(), usa, "Allies", calcolo(usa), new ArrayList<>());
    assertThat(TrasportiProtetti.serveScortaSpola(p, territory("91 Sea Zone"))).isFalse();
    add(territory("104 Sea Zone"), "Germans", "battleship", 4);
    p =
        new Programmi.Turno(
            proData(usa, 6), calc(), usa, "Allies", calcolo(usa), new ArrayList<>());
    assertThat(TrasportiProtetti.serveScortaSpola(p, territory("91 Sea Zone"))).isTrue();
  }

  @Test
  void theRound4ReinforcementsReachTheSquadronInRound5InOneGroup() {
    arrived();
    final GamePlayer usa = player("Americans");
    final Territory port = territory("101 Sea Zone");
    squadra(add(territory("91 Sea Zone"), "Americans", "carrier", 1));
    // bought in round 4 (squadron): a battleship and two transports, loaded at the port
    final List<Unit> battleship = add(port, "Americans", "battleship", 1);
    final Unit t1 = loadedTransport(port, "infantry", "artillery");
    final Unit t2 = loadedTransport(port, "infantry", "artillery");
    final List<Unit> r4 = new ArrayList<>(battleship);
    r4.add(t1);
    r4.add(t2);
    squadra(r4);
    programma(t1.getTransporting());
    programma(t2.getTransporting());
    final List<MoveDescription> moves = new ArrayList<>();
    Impegno.nonCombattimento(proData(usa, 5, "Non Combat Move"), calc(), recording(moves), usa);
    final List<MoveDescription> fromPort =
        moves.stream().filter(m -> m.getRoute().getStart().equals(port)).toList();
    assertThat(fromPort).as(moves.toString()).hasSize(1);
    assertThat(fromPort.get(0).getUnits())
        .containsAll(battleship)
        .contains(t1, t2)
        .containsAll(t1.getTransporting());
    // toward 91 as far as the movement goes (no harbour bonus on this test map), a safe stop
    final Territory end = fromPort.get(0).getRoute().getEnd();
    assertThat(data.getMap().getDistance(end, territory("91 Sea Zone"), Matches.territoryIsWater()))
        .isLessThan(
            data.getMap().getDistance(port, territory("91 Sea Zone"), Matches.territoryIsWater()));
  }

  // ===================================================================== D the panel's boxes

  @Test
  void theTwoBoxesOfThePanelTurnTheSwitchesOn() throws Exception {
    final GameData fresh = TestMapGameData.GLOBAL1940.getGameData();
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setAssedio(true);
    p.setSquadraGibilterra(true);
    p.salva();
    Stratega.registra(fresh, fresh.getPlayerList().getPlayerId("Germans"));
    Stratega.registra(fresh, fresh.getPlayerList().getPlayerId("Americans"));
    assertThat(StrategaInterruttori.ASSEDIO.acceso(fresh.getProperties(), "Axis")).isTrue();
    assertThat(StrategaInterruttori.IMPEGNO.acceso(fresh.getProperties(), "Axis")).isTrue();
    assertThat(StrategaInterruttori.SQUADRA_GIBILTERRA.acceso(fresh.getProperties(), "Allies"))
        .isTrue();
    assertThat(StrategaInterruttori.IMPEGNO.acceso(fresh.getProperties(), "Allies")).isTrue();
    // each box only for its alliance
    assertThat(StrategaInterruttori.ASSEDIO.acceso(fresh.getProperties(), "Allies")).isFalse();
    assertThat(StrategaInterruttori.SQUADRA_GIBILTERRA.acceso(fresh.getProperties(), "Axis"))
        .isFalse();
    // boxes off (the default): nothing turned on
    final GameData other = TestMapGameData.GLOBAL1940.getGameData();
    final ProfiloStratega q = ProfiloStratega.carica();
    q.setAssedio(false);
    q.setSquadraGibilterra(false);
    q.salva();
    Stratega.registra(other, other.getPlayerList().getPlayerId("Germans"));
    Stratega.registra(other, other.getPlayerList().getPlayerId("Americans"));
    assertThat(StrategaInterruttori.accesi(other.getProperties(), "Axis")).isEmpty();
    assertThat(StrategaInterruttori.accesi(other.getProperties(), "Allies")).isEmpty();
  }

  // ================================================================================ helpers

  /** Switch on, at war, the squadron arrived in 91 and Gibraltar the alliance's base. */
  private void arrived() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,squadra_gibilterra");
    final Territory port = territory("Eastern United States");
    data.performChange(
        ChangeFactory.removeUnits(port, port.getMatches(Matches.unitCanProduceUnits())));
    add(port, "Americans", "factory_major", 1);
    atWar();
    final JsonObject st = state();
    SquadraGibilterra.r6(st).addProperty("fase", SquadraGibilterra.ARRIVATA);
    SquadraGibilterra.r6(st).addProperty("iniziali", "fatto");
    st.addProperty("teatro", SbarcoCoordinato.SUD);
    st.addProperty("base", "Gibraltar");
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
  }

  private void clearSea(final String... zones) {
    for (final String n : zones) {
      final Territory z = territory(n);
      final List<Unit> ships = z.getMatches(Matches.unitIsSea().or(Matches.unitIsAir()));
      if (!ships.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(z, ships));
      }
    }
  }

  private void addFactory(final Territory t) {
    data.performChange(ChangeFactory.removeUnits(t, t.getMatches(Matches.unitCanProduceUnits())));
    data.performChange(ChangeFactory.changeOwner(t, player("Americans")));
    add(t, "Americans", "factory_major", 1);
  }

  private void enemyCoast(final String name, final int infantry) {
    final Territory t = territory(name);
    data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    data.performChange(ChangeFactory.changeOwner(t, player("Germans")));
    add(t, "Germans", "infantry", infantry);
  }

  private List<Unit> programTroops(final Territory t, final int pairs) {
    final List<Unit> out = new ArrayList<>(add(t, "Americans", "infantry", pairs));
    out.addAll(add(t, "Americans", "artillery", pairs));
    return out;
  }

  private SbarcoCoordinato.Turno.Piano plan(final SbarcoCoordinato.Turno t, final Territory x) {
    t.filtro = t.delProgramma();
    try {
      return t.piano(x, t.player, 0);
    } finally {
      t.filtro = null;
    }
  }

  private SbarcoCoordinato.Turno turno(final GamePlayer p, final int round, final String step) {
    return new SbarcoCoordinato.Turno(
        proData(p, round, step), calc(), p, calcolo(p), new ArrayList<>());
  }

  private void squadra(final List<Unit> units) {
    final JsonObject st = state();
    final JsonObject ids = SquadraGibilterra.ids(SquadraGibilterra.r6(st));
    units.forEach(u -> ids.addProperty(u.getId().toString(), u.getType().getName()));
    SquadraGibilterra.r6(st).addProperty("completa", true);
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    programma(units);
  }

  private void programma(final List<Unit> units) {
    final JsonObject imp = Impegno.stato(data.getProperties(), "Allies");
    final JsonObject ids =
        Impegno.nazione(imp, "Americans").has("programma_ids")
            ? Impegno.nazione(imp, "Americans").getAsJsonObject("programma_ids")
            : new JsonObject();
    units.forEach(u -> ids.addProperty(u.getId().toString(), u.getType().getName()));
    Impegno.nazione(imp, "Americans").add("programma_ids", ids);
    Impegno.salva(data.getProperties(), "Allies", imp);
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

  private Unit loadedTransport(final Territory zone, final String... cargo) {
    final Unit tr =
        data.getUnitTypeList()
            .getUnitTypeOrThrow("transport")
            .create(1, player("Americans"))
            .get(0);
    final List<Unit> all = new ArrayList<>(List.of(tr));
    final List<Unit> load = new ArrayList<>();
    for (final String type : cargo) {
      load.add(
          data.getUnitTypeList().getUnitTypeOrThrow(type).create(1, player("Americans")).get(0));
    }
    all.addAll(load);
    data.performChange(ChangeFactory.addUnits(zone, all));
    for (final Unit u : load) {
      data.performChange(ChangeFactory.unitPropertyChange(u, tr, Unit.PropertyName.TRANSPORTED_BY));
    }
    return tr;
  }

  private List<Unit> add(final Territory t, final String owner, final String type, final int n) {
    final List<Unit> units =
        data.getUnitTypeList().getUnitTypeOrThrow(type).create(n, player(owner));
    data.performChange(ChangeFactory.addUnits(t, units));
    return units;
  }

  private JsonObject state() {
    return SbarcoCoordinato.stato(data.getProperties(), "Allies");
  }

  private JsonObject lastDecision(final String tipo) {
    final List<JsonObject> rows =
        RegistroDecisioni.righe(data).stream()
            .filter(r -> "decisione".equals(r.get("riga").getAsString()))
            .filter(r -> tipo.equals(r.get("tipo").getAsString()))
            .toList();
    assertThat(rows).as("decisioni " + tipo).isNotEmpty();
    return rows.get(rows.size() - 1);
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
