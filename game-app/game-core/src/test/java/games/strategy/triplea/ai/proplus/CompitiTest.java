package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
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
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P1 "COMPITO": the generic mechanism. Pure rules ({@link Risposta}): response chosen from the
 * obstacles, no new transports while the owned ones (also empty and idle) can carry the ready
 * troops, the direction held 3 rounds; the data file; no nation in the engine's code; on built
 * positions (Global 1940): the obstacles computed with their numbers, an enemy fleet beaten at >=
 * 70% attacked, no transport bought with enough empty ones, the switch off = nothing.
 */
class CompitiTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261117L;

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

  // =========================================================================== data, code

  @Test
  void theDataFileHasTheTaskOfTheUsaAsData() {
    final Compito.Dati d = Compito.dati();
    assertThat(d.accesoDiDefault()).isFalse();
    assertThat(StrategaInterruttori.COMPITI.predefinito()).isFalse();
    assertThat(d.nazioniAttive().get("Allies")).containsExactly("Americans");
    final Compito usa = d.di("Allies", "Americans").orElseThrow();
    assertThat(usa.risultati()).extracting(Compito.Risultato::id).containsExactly("R1", "R2", "R3");
    assertThat(usa.risultati().get(0).tipo()).isEqualTo(Compito.CONTROLLO_E_FLOTTA);
    assertThat(usa.risultati().get(0).traguardo()).isEqualTo(6);
    assertThat(usa.risultati().get(1).opzioni())
        .containsEntry("ITALIA", "Southern Italy")
        .containsEntry("FRANCIA", "France");
    assertThat(usa.risultati().get(1).tenutaRound()).isEqualTo(3);
    assertThat(d.parametri().sogliaAttacco()).isEqualTo(70);
    assertThat(d.parametri().sogliaTrasporti()).isEqualTo(40);
    assertThat(d.parametri().quotaPrima()).isEqualTo(100);
    assertThat(d.parametri().quotaDopo()).isEqualTo(80);
    assertThat(d.di("Allies", "British")).isEmpty();
    assertThat(d.di("Axis", "Germans")).isEmpty();
  }

  @Test
  void theEngineKnowsNoNationAndNoMapName() throws Exception {
    for (final String file : List.of("Compiti.java", "Risposta.java", "Ostacolo.java", "Compito.java")) {
      final String src =
          Files.readString(
              Path.of("src/main/java/games/strategy/triplea/ai/proplus/" + file),
              StandardCharsets.UTF_8);
      for (final String word :
          List.of("Americans", "\"USA\"", "Gibraltar", "Southern Italy", "\"France\"", "91 Sea")) {
        assertThat(src).as(file + " contiene " + word).doesNotContain(word);
      }
    }
  }

  // ========================================================================= pure rules

  @Test
  void anObtainableTargetIsExecuted() {
    final Risposta.Scelta s =
        Risposta.decidi(
            List.of(
                target("A", false, ostacolo(Ostacolo.Tipo.MARE, 20)),
                target("B", true),
                target("C", true, ostacolo(Ostacolo.Tipo.DISTANZA, 10))),
            "A",
            10);
    assertThat(s.bersaglio().nome()).isEqualTo("B");
    assertThat(s.risposte()).containsExactly(Risposta.Tipo.ESEGUI);
    assertThat(s.scartate()).hasSize(2);
  }

  @Test
  void aSeaObstacleIsAnsweredWithPurchasesAndEscortAndABeatableFleetIsAttacked() {
    final Risposta.Bersaglio b =
        new Risposta.Bersaglio(
            "A",
            "z",
            false,
            List.of(ostacolo(Ostacolo.Tipo.TERRA, 20), ostacolo(Ostacolo.Tipo.MARE, 60)),
            "zona nemica",
            82);
    final Risposta.Scelta s = Risposta.decidi(List.of(b), null, 10);
    assertThat(s.risposte())
        .containsExactly(
            Risposta.Tipo.ATTACCO_FLOTTA,
            Risposta.Tipo.ACQUISTO_MARE,
            Risposta.Tipo.SCORTA,
            Risposta.Tipo.ACQUISTO_TERRA);
  }

  @Test
  void theTargetChangesOnlyWithAnObstacleSmallerByTheMargin() {
    final List<Risposta.Bersaglio> close =
        List.of(
            target("A", false, ostacolo(Ostacolo.Tipo.MARE, 50)),
            target("B", false, ostacolo(Ostacolo.Tipo.TERRA, 45)));
    assertThat(Risposta.decidi(close, "A", 10).bersaglio().nome()).isEqualTo("A");
    final List<Risposta.Bersaglio> far =
        List.of(
            target("A", false, ostacolo(Ostacolo.Tipo.MARE, 50)),
            target("B", false, ostacolo(Ostacolo.Tipo.TERRA, 30)));
    final Risposta.Scelta s = Risposta.decidi(far, "A", 10);
    assertThat(s.bersaglio().nome()).isEqualTo("B");
    assertThat(s.risposte()).contains(Risposta.Tipo.CAMBIO_BERSAGLIO, Risposta.Tipo.ACQUISTO_TERRA);
    assertThat(Risposta.decidi(List.of(), "A", 10).risposte()).containsExactly(Risposta.Tipo.ATTESA);
  }

  @Test
  void noNewTransportsWhileTheOwnedOnesCanCarryTheReadyTroops() {
    // 3 owned transports (also empty and idle) = 15 places: 14 places of troops, none bought
    assertThat(Risposta.trasportiDaComprare(14, 15, 5)).isZero();
    assertThat(Risposta.trasportiDaComprare(15, 15, 5)).isZero();
    assertThat(Risposta.trasportiDaComprare(16, 15, 5)).isEqualTo(1);
    assertThat(Risposta.trasportiDaComprare(26, 15, 5)).isEqualTo(3);
    assertThat(Risposta.trasportiDaComprare(0, 0, 5)).isZero();
  }

  @Test
  void theDirectionIsHeldThreeRoundsThenCheckedAgain() {
    assertThat(Risposta.ricontrollaDirezione(5, 0, 3)).isTrue();
    assertThat(Risposta.ricontrollaDirezione(5, 5, 3)).isFalse();
    assertThat(Risposta.ricontrollaDirezione(7, 5, 3)).isFalse();
    assertThat(Risposta.ricontrollaDirezione(8, 5, 3)).isTrue();
    final Map<String, Double> sums = new LinkedHashMap<>();
    sums.put("ITALIA", 80.0);
    sums.put("FRANCIA", 60.0);
    assertThat(Risposta.scegliDirezione(sums)).isEqualTo("FRANCIA");
    sums.put("FRANCIA", 80.0);
    assertThat(Risposta.scegliDirezione(sums)).isEqualTo("ITALIA");
  }

  @Test
  void theSeaPurchaseTakesTheUnitThatLowersTheObstacleMostPerPu() {
    final Map<String, double[]> c = new LinkedHashMap<>();
    c.put("destroyer", new double[] {8, 8});
    c.put("battleship", new double[] {30, 20});
    c.put("cruiser", new double[] {0, 12});
    assertThat(Risposta.miglioreAcquisto(c)).contains("battleship");
    c.clear();
    c.put("destroyer", new double[] {0, 8});
    assertThat(Risposta.miglioreAcquisto(c)).isEmpty();
  }

  // =================================================================== built positions

  @Test
  void theSwitchOffChangesNothing() {
    assertThat(Compiti.attivo(data, player("Americans"))).isFalse();
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno");
    assertThat(Compiti.attivo(data, player("Americans"))).isFalse();
    assertThat(Compiti.escludeImpegno(data, "Allies")).isFalse();
    assertThat(Compiti.trattenute(data, player("Americans"))).isEmpty();
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,compiti");
    assertThat(Compiti.attivo(data, player("Americans"))).isTrue();
    assertThat(Compiti.attivo(data, player("British"))).isFalse();
    // with the tasks the commitment of the alliance is replaced (the others play as Hard)
    assertThat(Impegno.attivo(data, player("British"))).isFalse();
    assertThat(Impegno.attivo(data, player("Americans"))).isFalse();
  }

  @Test
  void theObstaclesOfTheBaseAreComputedWithTheirNumbersAndABeatableFleetIsAttacked() {
    on();
    atWar();
    final Territory z = territory("91 Sea Zone");
    clear(z);
    // an Italian destroyer in 91, a strong US fleet next to it
    add(z, "Italians", "destroyer", 1);
    final Territory from = territory("103 Sea Zone");
    clear(from);
    add(from, "Americans", "cruiser", 2);
    add(from, "Americans", "destroyer", 2);
    final Compiti.Turno t = turno(4);
    final Compito.Risultato r1 = t.task.risultati().get(0);
    final Risposta.Bersaglio b = t.controllo(r1);
    assertThat(b.ottenibile()).isFalse();
    assertThat(b.ostacoli()).extracting(Ostacolo::tipo).contains(Ostacolo.Tipo.MARE);
    final Ostacolo sea =
        b.ostacoli().stream().filter(o -> o.tipo() == Ostacolo.Tipo.MARE).findFirst().orElseThrow();
    assertThat(sea.luogo()).isEqualTo("91 Sea Zone");
    assertThat(sea.numeri().getAsJsonObject("nemiche_in_zona").get("destroyer").getAsInt())
        .isEqualTo(1);
    assertThat(sea.numeri().has("vittoria_nemica_fine_turno_pct")).isTrue();
    assertThat(b.flottaBattibile()).isEqualTo("91 Sea Zone");
    assertThat(b.vittoriaFlotta()).isGreaterThanOrEqualTo(70);
    final Risposta.Scelta s = Risposta.decidi(List.of(b), null, 10);
    assertThat(s.risposte()).contains(Risposta.Tipo.ATTACCO_FLOTTA);
  }

  @Test
  void noTransportIsBoughtWithEnoughEmptyOnesAndHardBuysNone() {
    on();
    atWar();
    final Territory port = territory("Eastern United States");
    final Territory zone = territory("101 Sea Zone");
    clear(zone);
    for (final Territory t : List.of(port, territory("Central United States"))) {
      t.getMatches(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()))
          .forEach(u -> data.performChange(ChangeFactory.removeUnits(t, List.of(u))));
    }
    data.getMap().getTerritories().stream()
        .filter(Territory::isWater)
        .forEach(
            z ->
                z.getMatches(Matches.unitIsOwnedBy(player("Americans")))
                    .forEach(u -> data.performChange(ChangeFactory.removeUnits(z, List.of(u)))));
    // 3 empty transports, 4 infantry ready (8 places) at the port
    add(zone, "Americans", "transport", 3);
    add(port, "Americans", "infantry", 4);
    final Compiti.Turno t = turno(6);
    final Compito.Risultato r3 = t.task.risultati().get(2);
    final Ostacolo capacity =
        new Ostacolo(Ostacolo.Tipo.CAPACITA, "porto", 7, -1, new JsonObject());
    final Risposta.Bersaglio b =
        new Risposta.Bersaglio("Southern Italy", null, false, List.of(capacity), null, -1);
    final List<String> responses = new ArrayList<>(List.of("ACQUISTO_CAPACITA"));
    final JsonArray bought = t.acquisti(r3, b, List.of(Risposta.Tipo.ACQUISTO_CAPACITA), responses);
    assertThat(bought.toString()).doesNotContain("\"transport\"");
    assertThat(responses).doesNotContain("ACQUISTO_CAPACITA");
    assertThat(Compiti.ordini(t.proData)).isEmpty();
    assertThat(Compiti.vietaTrasporti(t.proData)).isTrue();
    // more troops than places: the transports that the missing places need, no more
    final int cap = t.capacita(zone.getMatches(Matches.unitIsSeaTransport()).get(0));
    final int perInfantry =
        port.getMatches(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()))
            .get(0)
            .getUnitAttachment()
            .getTransportCost();
    add(port, "Americans", "infantry", 3 * cap / perInfantry + 1);
    final Compiti.Turno t2 = turno(6);
    final JsonArray bought2 =
        t2.acquisti(r3, b, List.of(Risposta.Tipo.ACQUISTO_CAPACITA), new ArrayList<>());
    final JsonObject row = bought2.get(0).getAsJsonObject();
    final int expected =
        Risposta.trasportiDaComprare(
            row.get("posti_da_portare").getAsInt(),
            row.get("posti_liberi_posseduti").getAsInt(),
            cap);
    assertThat(expected).as(row.toString() + " cap " + cap + " per " + perInfantry).isPositive();
    assertThat(row.getAsJsonObject("comprato").get("transport").getAsInt()).isEqualTo(expected);
  }

  @Test
  void theDirectionIsChosenOnTheBoardAndHeldThreeRounds() {
    on();
    atWar();
    final Compiti.Turno t = turno(5);
    final Compito.Risultato r2 = t.task.risultati().get(1);
    t.direzione(r2, true);
    assertThat(t.ns.has("direzione")).isTrue();
    final JsonObject d = t.ns.getAsJsonObject("direzione");
    assertThat(d.get("dal_round").getAsInt()).isEqualTo(5);
    assertThat(d.getAsJsonObject("conti").keySet()).containsExactlyInAnyOrder("ITALIA", "FRANCIA");
    Compiti.salva(data.getProperties(), "Allies", t.st);
    final long rows = rows("compito_direzione");
    // rounds 6 and 7: held, no new row
    turno(6).direzione(r2, false);
    turno(7).direzione(r2, false);
    assertThat(rows("compito_direzione")).isEqualTo(rows);
    // round 8: checked again
    final Compiti.Turno t8 = turno(8);
    t8.direzione(r2, false);
    assertThat(rows("compito_direzione")).isEqualTo(rows + 1);
    assertThat(t8.ns.getAsJsonObject("direzione").get("dal_round").getAsInt()).isEqualTo(8);
  }

  @Test
  void theBoxOfThePanelTurnsTheTasksOnForTheAlliesOnly() throws Exception {
    final GameData fresh = TestMapGameData.GLOBAL1940.getGameData();
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setCompiti(true);
    p.salva();
    Stratega.registra(fresh, fresh.getPlayerList().getPlayerId("Germans"));
    Stratega.registra(fresh, fresh.getPlayerList().getPlayerId("Americans"));
    assertThat(StrategaInterruttori.COMPITI.acceso(fresh.getProperties(), "Allies")).isTrue();
    assertThat(StrategaInterruttori.IMPEGNO.acceso(fresh.getProperties(), "Allies")).isTrue();
    assertThat(StrategaInterruttori.COMPITI.acceso(fresh.getProperties(), "Axis")).isFalse();
    final ProfiloStratega q = ProfiloStratega.carica();
    q.setCompiti(false);
    q.salva();
    final GameData other = TestMapGameData.GLOBAL1940.getGameData();
    Stratega.registra(other, other.getPlayerList().getPlayerId("Americans"));
    assertThat(StrategaInterruttori.accesi(other.getProperties(), "Allies")).isEmpty();
  }

  // ============================================================================== helpers

  private static Risposta.Bersaglio target(
      final String name, final boolean obtainable, final Ostacolo... obstacles) {
    return new Risposta.Bersaglio(name, null, obtainable, List.of(obstacles), null, -1);
  }

  private static Ostacolo ostacolo(final Ostacolo.Tipo t, final double pu) {
    return new Ostacolo(t, "x", pu, -1, new JsonObject());
  }

  private long rows(final String tipo) {
    return RegistroDecisioni.righe(data).stream()
        .filter(r -> "decisione".equals(r.get("riga").getAsString()))
        .filter(r -> tipo.equals(r.get("tipo").getAsString()))
        .count();
  }

  private void on() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,compiti");
  }

  private Compiti.Turno turno(final int round) {
    final GamePlayer usa = player("Americans");
    return new Compiti.Turno(proData(usa, round), calc(), usa);
  }

  private void clear(final Territory z) {
    final List<Unit> units = z.getMatches(Matches.unitIsSea().or(Matches.unitIsAir()));
    if (!units.isEmpty()) {
      data.performChange(ChangeFactory.removeUnits(z, units));
    }
  }

  /** The USA at war with the Axis; no Axis ship or plane anywhere. */
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

  private List<Unit> add(final Territory t, final String owner, final String type, final int n) {
    final List<Unit> units =
        data.getUnitTypeList().getUnitTypeOrThrow(type).create(n, player(owner));
    data.performChange(ChangeFactory.addUnits(t, units));
    return units;
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
