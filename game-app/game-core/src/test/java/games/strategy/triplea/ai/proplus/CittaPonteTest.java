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
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P3: the city to hold (minimum reserve and the attack on the threat, reinforcement by sea
 * with a transport in reach, the city taken back, the purchase at the nearest factory with its cap)
 * and the bridgehead weight (only with an allied bridgehead of the round); the switch off = P2-bis.
 * Built positions on Global 1940 (the same names as Europe 1940 around Egypt).
 */
class CittaPonteTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261118L;

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

  // ============================================================================ data, code

  @Test
  void theDataHaveTheCityTheWeightsAndNoNameInTheCode() throws Exception {
    final CittaPonte.Config c = CittaPonte.Config.get();
    assertThat(c.accesoDiDefault()).isFalse();
    assertThat(StrategaInterruttori.REGNO_UNITO_P3.predefinito()).isFalse();
    assertThat(c.nazioni().get("British").citta()).isEqualTo("Egypt");
    assertThat(c.nazioni().get("British").sostituisce()).containsExactly("U1", "U2");
    assertThat(c.pesoPonteAttacco()).isEqualTo(20);
    assertThat(c.forzaPonteRinforzo()).isEqualTo(0.5);
    assertThat(c.vittoriaAttaccoMinaccia()).isEqualTo(70);
    final String src =
        Files.readString(
            Path.of("src/main/java/games/strategy/triplea/ai/proplus/CittaPonte.java"),
            StandardCharsets.UTF_8);
    for (final String word :
        List.of(
            "Americans", "British", "Egypt", "United Kingdom", "Normandy", "Denmark", "Sea Zone")) {
      assertThat(src).as("CittaPonte contiene " + word).doesNotContain(word);
    }
  }

  // ============================================================================ pure rules

  @Test
  void theReserveIsTheMinimumThatHoldsAndAllWhenNothingHolds() {
    // threat = 100 - 20 per defender
    final List<String> res =
        CittaPonte.riservaMinima(
            List.of("alleato"), List.of("a", "b", "c", "d", "e"), d -> 100 - 20.0 * d.size(), 40);
    assertThat(res).containsExactly("a", "b");
    assertThat(CittaPonte.riservaMinima(List.of(), List.of("a"), d -> 10.0, 40)).isEmpty();
    assertThat(CittaPonte.riservaMinima(List.of(), List.of("a", "b"), d -> 90.0, 40))
        .containsExactly("a", "b");
  }

  @Test
  void theAttackOnTheThreatNeedsSeventyAndTheCityHoldingAfterIt() {
    assertThat(CittaPonte.attaccoMinacciaPermesso(75, 30, 70, 40)).isTrue();
    assertThat(CittaPonte.attaccoMinacciaPermesso(65, 10, 70, 40)).isFalse();
    assertThat(CittaPonte.attaccoMinacciaPermesso(95, 55, 70, 40)).isFalse();
  }

  @Test
  void theNavalScreenLowersOnlyTheAmphibiousPart() {
    assertThat(CittaPonte.minacciaConSchermo(20, 80, 1)).isEqualTo(20);
    assertThat(CittaPonte.minacciaConSchermo(20, 80, 0)).isEqualTo(80);
    assertThat(CittaPonte.minacciaConSchermo(20, 80, 0.5)).isEqualTo(50);
  }

  @Test
  void theNearestFactoryAndThePurchaseCappedByItsProduction() {
    assertThat(CittaPonte.fabbricaPiuVicina(Map.of("A", 3, "B", 2, "C", 2), 4)).contains("B");
    assertThat(CittaPonte.fabbricaPiuVicina(Map.of("A", 5, "B", -1), 4)).isEmpty();
    assertThat(CittaPonte.truppeDaComprare(6, 3, 100, 3)).isEqualTo(3);
    assertThat(CittaPonte.truppeDaComprare(6, 10, 10, 3)).isEqualTo(3);
    assertThat(CittaPonte.truppeDaComprare(2, 10, 100, 3)).isEqualTo(2);
    assertThat(CittaPonte.truppeDaComprare(0, 10, 100, 3)).isZero();
    assertThat(CittaPonte.turniMare(3, 2)).isEqualTo(2);
    assertThat(CittaPonte.turniMare(0, 2)).isEqualTo(1);
  }

  // ============================================================================ on the board

  @Test
  void theCityReservesOnlyTheMinimumAndTheAttackOnAWeakThreatIsAllowed() {
    on();
    atWar();
    final Territory egypt = territory("Egypt");
    final Territory alex = territory("Alexandria");
    // the threat: an Italian army in Alexandria
    owner(alex, "Italians");
    data.performChange(
        ChangeFactory.removeUnits(alex, alex.getMatches(Matches.unitIsNotInfrastructure())));
    add(alex, "Italians", "infantry", 4);
    add(alex, "Italians", "armour", 4);
    add(territory("Trans-Jordan"), "British", "infantry", 6);
    final Compiti.Turno t = turno(player("British"), 6);
    final JsonObject x = CittaPonte.acquisto(t, t.cassa());
    assertThat(x.get("citta").getAsString()).isEqualTo("Egypt");
    assertThat(x.get("stato").getAsString()).isEqualTo("MINACCIATO");
    final int reserved = x.get("riservate").getAsInt();
    final int candidates =
        egypt.getMatches(Matches.unitIsOwnedBy(player("British")).and(Matches.unitIsLand())).size()
            + 6;
    assertThat(reserved).isPositive().isLessThanOrEqualTo(candidates);
    Compiti.salva(data.getProperties(), "Allies", t.st);
    final Set<Unit> held = CittaPonte.riservate(data, player("British"));
    assertThat(held).hasSizeGreaterThanOrEqualTo(reserved);
    // a weak threat next to strong British units: the attack on it is planned
    for (final Territory l : data.getMap().getTerritories()) {
      final List<Unit> axis =
          l.getMatches(
              Matches.unitIsLand()
                  .and(Matches.unitIsNotInfrastructure())
                  .and(u -> u.isOwnedBy(player("Italians")) || u.isOwnedBy(player("Germans"))));
      if (!l.isWater() && !axis.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(l, axis));
      }
    }
    data.performChange(
        ChangeFactory.removeUnits(egypt, egypt.getMatches(Matches.unitIsNotInfrastructure())));
    add(egypt, "British", "infantry", 1);
    add(alex, "Italians", "infantry", 2);
    add(alex, "Italians", "artillery", 1);
    final Territory tobruk = territory("Tobruk");
    owner(tobruk, "British");
    add(tobruk, "British", "armour", 5);
    final Compiti.Turno t2 = turno(player("British"), 6);
    final JsonObject y = CittaPonte.acquisto(t2, t2.cassa());
    assertThat(y.get("stato").getAsString()).as(y.toString()).isEqualTo("MINACCIATO");
    assertThat(y.has("attacco_minaccia")).as(y.toString()).isTrue();
    final JsonObject h = y.getAsJsonObject("attacco_minaccia");
    assertThat(h.get("bersaglio").getAsString()).isEqualTo("Alexandria");
    assertThat(h.get("vittoria_pct").getAsDouble()).isGreaterThanOrEqualTo(70);
    assertThat(h.get("attacco_realistico_dopo_pct").getAsDouble()).isLessThanOrEqualTo(40);
  }

  @Test
  void anOwnTransportInReachBringsTroopsFromAnIslandToTheCity() {
    on();
    atWar();
    final Territory z98 = territory("98 Sea Zone");
    final List<Unit> tr =
        z98.getMatches(Matches.unitIsOwnedBy(player("British")).and(Compiti::trasporto));
    if (tr.isEmpty()) {
      add(z98, "British", "transport", 1);
    }
    final Territory cyprus = territory("Cyprus");
    add(cyprus, "British", "infantry", 2);
    final Compiti.Turno t = turno(player("British"), 6);
    final List<CittaPonte.Carico> plan =
        CittaPonte.pianoMare(t, territory("Egypt"), new java.util.LinkedHashSet<>(), false);
    assertThat(plan).isNotEmpty();
    final CittaPonte.Carico k =
        plan.stream().filter(c -> cyprus.equals(c.terra())).findFirst().orElseThrow();
    assertThat(k.truppe()).hasSize(2);
    assertThat(data.getMap().getNeighbors(territory("Egypt"))).contains(k.arrivo());
    assertThat(k.sbarco()).isEqualTo(territory("Egypt"));
  }

  @Test
  void theLostCityIsTakenBackWhenTheWinIsAtLeastSeventy() {
    on();
    atWar();
    final Territory egypt = territory("Egypt");
    owner(egypt, "Italians");
    data.performChange(
        ChangeFactory.removeUnits(egypt, egypt.getMatches(Matches.unitIsNotInfrastructure())));
    add(egypt, "Italians", "infantry", 1);
    add(territory("Trans-Jordan"), "British", "infantry", 3);
    add(territory("Trans-Jordan"), "British", "armour", 2);
    final Compiti.Turno t = turno(player("British"), 6);
    final JsonObject x = CittaPonte.acquisto(t, t.cassa());
    assertThat(x.get("stato").getAsString()).isEqualTo("PERSO");
    assertThat(x.get("ripresa").getAsBoolean()).as(x.toString()).isTrue();
    assertThat(x.get("vittoria_ripresa_pct").getAsDouble()).isGreaterThanOrEqualTo(70);
    assertThat(x.getAsJsonArray("trattenute")).isNotEmpty();
    // no PU when the city is taken back
    assertThat(x.getAsJsonObject("acquisto").get("pu").getAsInt()).isZero();
  }

  @Test
  void theTroopsMissingAreBoughtAtTheNearestFactoryUpToItsProduction() {
    on();
    atWar();
    final Compiti.Turno t = turno(player("British"), 6);
    final Compiti.Turno.Cassa cassa = t.cassa();
    final int before = cassa.left;
    final JsonObject x = new JsonObject();
    final Map<String, Integer> turns = CittaPonte.fabbriche(t, territory("Egypt"));
    assertThat(turns).containsKeys("United Kingdom", "Union of South Africa");
    CittaPonte.compra(t, territory("Egypt"), 20, cassa, x);
    final JsonObject buy = x.getAsJsonObject("acquisto");
    final String f = buy.get("fabbrica").getAsString();
    assertThat(CittaPonte.fabbricaPiuVicina(turns, 4)).contains(f);
    final int cap = t.capienza(territory(f));
    final int n = buy.get("infantry").getAsInt();
    assertThat(n + buy.get("trasporto").getAsInt()).isLessThanOrEqualTo(cap);
    assertThat(n).isPositive();
    assertThat(cassa.left).isEqualTo(before - buy.get("pu").getAsInt());
    assertThat(cassa.perFabbrica.get(f)).containsEntry("infantry", n);
    // a transport only by sea and without one in the factory's zone
    final boolean sea = CittaPonte.viaMare(t, territory(f), territory("Egypt"));
    assertThat(buy.get("via_mare").getAsBoolean()).isEqualTo(sea);
    final boolean have =
        data.getMap().getNeighbors(territory(f), Matches.territoryIsWater()).stream()
            .anyMatch(
                z ->
                    z.anyUnitsMatch(
                        Matches.unitIsOwnedBy(player("British")).and(Compiti::trasporto)));
    assertThat(buy.get("trasporto").getAsInt() == 1).isEqualTo(sea && !have);
  }

  @Test
  void theBridgeheadWeighsOnlyWithAnAlliedBridgeheadOfTheRound() {
    on();
    atWar();
    final GamePlayer uk = player("British");
    final Territory nb = territory("Normandy Bordeaux");
    // no bridgehead: no weight, no pull
    final Compiti.Turno t0 = turno(uk, 6);
    CittaPonte.fotografiaRound(t0);
    assertThat(CittaPonte.teste(t0)).isEmpty();
    CittaPonte.ponte(t0);
    assertThat(CittaPonte.rinforzo(t0.proData)).isEmpty();
    assertThat(CittaPonte.pesoAttacco(t0.proData, nb)).isZero();
    // the round's snapshot: Normandy of the Germans; the USA take it in the round
    owner(nb, "Germans");
    final Compiti.Turno t1 = turno(uk, 7);
    t1.st.remove("p3_proprietari");
    CittaPonte.fotografiaRound(t1);
    Compiti.salva(data.getProperties(), "Allies", t1.st);
    owner(nb, "Americans");
    data.performChange(
        ChangeFactory.removeUnits(nb, nb.getMatches(Matches.unitIsNotInfrastructure())));
    add(nb, "Americans", "infantry", 3);
    final Compiti.Turno t2 = turno(uk, 7);
    final List<CittaPonte.Testa> heads = CittaPonte.teste(t2);
    assertThat(heads).extracting(CittaPonte.Testa::territorio).containsExactly("Normandy Bordeaux");
    assertThat(heads.get(0).presa()).isTrue();
    CittaPonte.ponte(t2);
    final var orders = CittaPonte.rinforzo(t2.proData).orElseThrow();
    assertThat(orders.territories()).contains("Normandy Bordeaux");
    assertThat(orders.strength()).isEqualTo(0.5);
    // taken: the weight is the pull, not an attack bonus
    assertThat(CittaPonte.pesoAttacco(t2.proData, nb)).isZero();
    // the tuning gives the pull to Hard's non-combat move
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(t2.proData.getTuning());
    assertThat(tuning.stagingOrders(t2.proData).orElseThrow().territories())
        .contains("Normandy Bordeaux");
  }

  @Test
  void withTheSwitchOffTheUkPlaysAsInP2Bis() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,compiti");
    atWar();
    final GamePlayer uk = player("British");
    assertThat(CittaPonte.attivo(data, uk)).isFalse();
    final Compiti.Turno t = turno(uk, 6);
    assertThat(CittaPonte.sostituito(t, "U1")).isFalse();
    assertThat(CittaPonte.sostituito(t, "U2")).isFalse();
    t.inizio();
    // U0 and U1 served as in P2-bis, no P3 state, no weight for Hard
    final JsonObject piano = t.ns.getAsJsonObject("piano");
    assertThat(piano.getAsJsonArray("tenute")).hasSize(2);
    assertThat(t.ns.has("p3")).isFalse();
    assertThat(data.getProperties().get(CittaPonte.KEY_PONTE + "British")).isNull();
    assertThat(CittaPonte.riservate(data, uk)).isEmpty();
    assertThat(CittaPonte.pesoAttacco(t.proData, territory("Normandy Bordeaux"))).isZero();
    assertThat(CittaPonte.rinforzo(t.proData)).isEmpty();
    // on: U1 and U2 are replaced (only U0 left), the city's state is written
    on();
    final Compiti.Turno t2 = turno(uk, 6);
    assertThat(CittaPonte.sostituito(t2, "U1")).isTrue();
    t2.inizio();
    final JsonObject p2 = t2.ns.getAsJsonObject("piano");
    assertThat(p2.getAsJsonArray("tenute")).hasSize(1);
    assertThat(p2.has("risultato")).isFalse();
    assertThat(t2.ns.has("p3")).isTrue();
  }

  @Test
  void theBoxOfThePanelTurnsTheP3OnForTheAllies() throws Exception {
    final GameData fresh = TestMapGameData.GLOBAL1940.getGameData();
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setRegnoUnitoP3(true);
    p.salva();
    Stratega.registra(fresh, fresh.getPlayerList().getPlayerId("British"));
    assertThat(StrategaInterruttori.REGNO_UNITO_P3.acceso(fresh.getProperties(), "Allies"))
        .isTrue();
    assertThat(StrategaInterruttori.COMPITI.acceso(fresh.getProperties(), "Allies")).isTrue();
    assertThat(StrategaInterruttori.IMPEGNO.acceso(fresh.getProperties(), "Allies")).isTrue();
    assertThat(StrategaInterruttori.REGNO_UNITO_P3.acceso(fresh.getProperties(), "Axis")).isFalse();
  }

  // ============================================================================== helpers

  private void on() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,compiti,regno_unito_p3");
  }

  private void owner(final Territory t, final String who) {
    data.performChange(ChangeFactory.changeOwner(t, player(who)));
  }

  /** The Allies at war with the Axis; no Axis ship or plane anywhere. */
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

  private Compiti.Turno turno(final GamePlayer p, final int round) {
    return new Compiti.Turno(proData(p, round), calc(), p);
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
