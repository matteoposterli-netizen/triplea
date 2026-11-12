package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonElement;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step R5 "ALLEATI RIFATTI" on built positions (Global 1940, the Allies): the US landing at 70% and
 * not at 69% without looking at the hold; the shuttle does not stop for the estimated
 * counterattack; the naval battle of Gibraltar at 70% and the waves to the United Kingdom below;
 * Hard can use a unit the program did not buy; the British transport bought with the port's zone
 * defended and blocked when not; the British attrition without a hold condition; South Africa
 * bought with an Italian African bonus active and not when no condition holds; switch off = today's
 * behavior.
 */
class AlleatiR5Test extends AbstractClientSettingTestCase {
  private static final long SEED = 20261112L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(
        games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.FOLDER_PROPERTY,
        profile.toString());
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    for (final String n : List.of("Americans", "British", "Russians")) {
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
  void theDataFileHasTheValuesOfStepR5AndTheSwitchIsOffByDefault() {
    final AlleatiR5.Config cfg = AlleatiR5.Config.get();
    assertThat(cfg.accesoDiDefault()).isFalse();
    assertThat(cfg.quota()).isEqualTo(70);
    assertThat(cfg.sogliaSbarco()).isEqualTo(70);
    assertThat(cfg.sogliaBattaglia()).isEqualTo(70);
    assertThat(cfg.sogliaMareDifeso()).isEqualTo(40);
    assertThat(cfg.zoneGibilterra()).containsExactly("91 Sea Zone", "92 Sea Zone");
    assertThat(cfg.bonusAfricani())
        .containsExactly(
            "objectiveAttachment_Italians_3_North_Africa",
            "objectiveAttachment_Italians_2_Roman_Empire");
    assertThat(StrategaInterruttori.ALLEATI_R5.acceso(data.getProperties(), "Allies")).isFalse();
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,alleati_r4");
    assertThat(AlleatiR5.attivo(data, player("Americans"))).isFalse();
    assertThat(SnodoAlleati.attivo(data, player("Americans"))).isTrue();
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,alleati_r4,alleati_r5");
    // R5 replaces R4 for the USA and the United Kingdom; Russia never plays it
    assertThat(AlleatiR5.attivo(data, player("Americans"))).isTrue();
    assertThat(AlleatiR5.attivo(data, player("British"))).isTrue();
    assertThat(AlleatiR5.attivo(data, player("Russians"))).isFalse();
    assertThat(SnodoAlleati.attivo(data, player("Americans"))).isFalse();
    // without the commitment nothing
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "alleati_r5");
    assertThat(AlleatiR5.attivo(data, player("Americans"))).isFalse();
  }

  @Test
  void switchOffIsTodaysBehavior() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno");
    atWar();
    Impegno.inizioTurno(proData(player("Americans"), 3), calc(), player("Americans"));
    // R3a decides as today: a theater and a green light, nothing of R5
    assertThat(decisions("teatro")).isNotEmpty();
    assertThat(decisions("via_libera_o_attesa")).isNotEmpty();
    assertThat(decisions("tetto_trasporti")).isNotEmpty();
    for (final String r5 :
        List.of("rotta_usa", "battaglia_gibilterra", "presa_gibilterra", "trasporti_mare_difeso")) {
      assertThat(decisions(r5)).as(r5).isEmpty();
    }
  }

  // ============================================================ A5-A6 landing at 70, no hold

  @Test
  void theUsLandsAt70AndNotAt69WithoutLookingAtTheHold() {
    assertThat(AlleatiR5.sbarca(70, 70)).isTrue();
    assertThat(AlleatiR5.sbarca(69.99, 70)).isFalse();
    assertThat(AlleatiR5.sbarca(69, 70)).isFalse();
    on();
    atWar();
    // Southern France German with one infantry, a huge German army next to it in France
    for (final String n : List.of("Southern France", "France")) {
      final Territory t = territory(n);
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
      data.performChange(ChangeFactory.changeOwner(t, player("Germans")));
    }
    addUnits(territory("Southern France"), "Germans", "infantry", 1);
    addUnits(territory("France"), "Germans", "armour", 25);
    // the program at the hub: 2 transports in 92 Sea Zone, 4 slow units at Gibraltar
    addUnits(territory("92 Sea Zone"), "Americans", "transport", 2);
    addUnits(territory("Gibraltar"), "Americans", "infantry", 2);
    addUnits(territory("Gibraltar"), "Americans", "artillery", 2);
    final List<Unit> troops =
        territory("Gibraltar").getMatches(Matches.unitIsOwnedBy(player("Americans")));
    final List<Unit> program = new ArrayList<>(troops);
    program.addAll(territory("92 Sea Zone").getMatches(Matches.unitIsOwnedBy(player("Americans"))));
    programma("Americans", program);
    Impegno.inizioTurno(proData(player("Americans"), 3), calc(), player("Americans"));
    assertThat(lastDecision("rotta_usa").get("scelta").getAsString()).isEqualTo("Gibraltar");
    final JsonObject hub = lastDecision("snodo_usa");
    assertThat(hub.getAsJsonObject("motivo").get("codice").getAsString())
        .as(hub.toString())
        .isEqualTo("FRANCIA");
    final JsonObject alt = alternative(hub, "Southern France");
    final double win = alt.get("vittoria_pct").getAsDouble();
    final double hold = alt.get("tenuta_stimata_pct_info").getAsDouble();
    assertThat(win).isGreaterThanOrEqualTo(70);
    // R3a would wait for the hold; R5 lands
    assertThat(hold).isLessThan(60);
    assertThat(SbarcoCoordinato.viaLibera(win, 100 - hold, 70, 40).codice())
        .isEqualTo("MANCA_TENUTA");
    // the landing with all the load, before Hard
    final List<MoveDescription> moves = new ArrayList<>();
    SbarcoCoordinato.combattimento(
        proData(player("Americans"), 3, "Combat Move"),
        calc(),
        recording(moves),
        player("Americans"));
    final Set<Unit> landed = new HashSet<>();
    for (final MoveDescription m : moves) {
      if (m.getRoute().getEnd().equals(territory("Southern France"))) {
        landed.addAll(m.getUnits());
      }
    }
    assertThat(landed).containsExactlyInAnyOrderElementsOf(troops);
    // gone: they are Hard's from now on
    assertThat(SnodoAlleati.programma(data, player("Americans")))
        .doesNotContainAnyElementsOf(troops.stream().map(u -> u.getId().toString()).toList());
  }

  // ===================================================================== A2 the shuttle

  @Test
  void theShuttleDoesNotStopForTheEstimatedCounterattack() {
    on();
    atWar();
    final JsonObject st = state();
    st.addProperty("teatro", "SUD");
    st.addProperty("base", "Gibraltar");
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    // a loaded program transport with one destroyer at the port (101), a German fleet next to
    // the route (90 Sea Zone touches 102 and 103)
    final Territory port = territory("101 Sea Zone");
    final Unit tr = loadedTransport(port, "Americans", "infantry", "artillery");
    addUnits(port, "Americans", "destroyer", 1);
    for (final String z : List.of("90 Sea Zone", "87 Sea Zone")) {
      addUnits(territory(z), "Germans", "battleship", 4);
    }
    final List<Unit> program = new ArrayList<>(List.of(tr));
    program.addAll(tr.getTransporting());
    programma("Americans", program);
    final List<MoveDescription> moves = new ArrayList<>();
    Impegno.nonCombattimento(
        proData(player("Americans"), 3, "Non Combat Move"),
        calc(),
        recording(moves),
        player("Americans"));
    final MoveDescription sail =
        moves.stream()
            .filter(m -> m.getUnits().contains(tr) && m.getRoute().getStart().equals(port))
            .findFirst()
            .orElseThrow(() -> new AssertionError("la spola non e' partita: " + moves));
    // the whole movement (2 zones) toward Gibraltar, with its escort
    assertThat(sail.getRoute().numberOfSteps()).isEqualTo(2);
    assertThat(data.getMap().getDistance(sail.getRoute().getEnd(), territory("91 Sea Zone")))
        .isEqualTo(1);
    assertThat(sail.getUnits()).anyMatch(Matches.unitIsDestroyer());
    // the R2c rule would have stopped: the zones of the route are not safe
    final Programmi.Turno p =
        new Programmi.Turno(
            proData(player("Americans"), 3, "Non Combat Move"),
            calc(),
            player("Americans"),
            "Allies",
            calcolo(player("Americans")),
            new ArrayList<>());
    assertThat(p.vittoriaNemicaMare(sail.getRoute().getEnd(), List.of())).isGreaterThan(40);
  }

  @Test
  void aLoadedGroupWithoutAWarshipWaits() {
    on();
    atWar();
    final JsonObject st = state();
    st.addProperty("teatro", "SUD");
    st.addProperty("base", "Gibraltar");
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    final Territory port = territory("101 Sea Zone");
    final Unit tr = loadedTransport(port, "Americans", "infantry", "artillery");
    final List<Unit> warships =
        port.getMatches(Matches.unitIsOwnedBy(player("Americans")).and(AlleatiR5.naveDaGuerra()));
    assertThat(warships).as("navi da guerra al porto").isNotEmpty();
    data.performChange(ChangeFactory.removeUnits(port, warships));
    assertThat(port.getMatches(AlleatiR5.naveDaGuerra())).isEmpty();
    final List<Unit> program = new ArrayList<>(List.of(tr));
    program.addAll(tr.getTransporting());
    programma("Americans", program);
    final List<MoveDescription> moves = new ArrayList<>();
    Impegno.nonCombattimento(
        proData(player("Americans"), 3, "Non Combat Move"),
        calc(),
        recording(moves),
        player("Americans"));
    assertThat(moves).noneMatch(m -> m.getUnits().contains(tr));
  }

  // ================================================================ A3 Gibraltar's sea

  @Test
  void theNavalBattleOfGibraltarAt70AndTheWavesToTheUnitedKingdomBelow() {
    assertThat(AlleatiR5.scortaPerVittoria(k -> 50 + 10 * k, 70, 4)).isEqualTo(2);
    assertThat(AlleatiR5.scortaPerVittoria(k -> 70, 70, 4)).isZero();
    assertThat(AlleatiR5.scortaPerVittoria(k -> 10, 70, 4)).isEqualTo(4);
    on();
    atWar();
    // strong US fleet in 91, one Italian destroyer in 92
    addUnits(territory("92 Sea Zone"), "Italians", "destroyer", 1);
    addUnits(territory("91 Sea Zone"), "Americans", "cruiser", 3);
    addUnits(territory("91 Sea Zone"), "Americans", "destroyer", 2);
    Impegno.inizioTurno(proData(player("Americans"), 3), calc(), player("Americans"));
    assertThat(lastDecision("rotta_usa").getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("MARE_GIBILTERRA_OCCUPATO");
    assertThat(SbarcoCoordinato.baseCorrente(data, "Allies")).contains("United Kingdom");
    final JsonObject battle = lastDecision("battaglia_gibilterra");
    assertThat(battle.getAsJsonObject("motivo").get("codice").getAsString())
        .as(battle.toString())
        .isEqualTo("BATTAGLIA");
    final List<MoveDescription> moves = new ArrayList<>();
    SbarcoCoordinato.combattimento(
        proData(player("Americans"), 3, "Combat Move"),
        calc(),
        recording(moves),
        player("Americans"));
    assertThat(moves)
        .anyMatch(m -> m.getRoute().getEnd().equals(territory("92 Sea Zone")))
        .allMatch(m -> m.getUnits().stream().allMatch(Matches.unitIsSea()));
  }

  @Test
  void belowTheThresholdNoBattleTheProgramBuysEscortsAndTheWavesGoToTheUnitedKingdom() {
    on();
    atWar();
    addUnits(territory("92 Sea Zone"), "Italians", "battleship", 2);
    addUnits(territory("92 Sea Zone"), "Italians", "cruiser", 2);
    addUnits(territory("91 Sea Zone"), "Americans", "destroyer", 1);
    final ProData pd = proData(player("Americans"), 3);
    Impegno.inizioTurno(pd, calc(), player("Americans"));
    final JsonObject battle = lastDecision("battaglia_gibilterra");
    assertThat(battle.getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("SOTTO_SOGLIA");
    assertThat(SbarcoCoordinato.baseCorrente(data, "Allies")).contains("United Kingdom");
    final Optional<ProPurchaseOrders> orders = Impegno.ordini(pd);
    assertThat(orders).isPresent();
    assertThat(orders.get().mandatory())
        .anyMatch(m -> m.category().equals("programma destroyer") && m.minimum() > 0);
    // no battle in the combat move
    final List<MoveDescription> moves = new ArrayList<>();
    SbarcoCoordinato.combattimento(
        proData(player("Americans"), 3, "Combat Move"),
        calc(),
        recording(moves),
        player("Americans"));
    assertThat(moves).noneMatch(m -> m.getRoute().getEnd().equals(territory("92 Sea Zone")));
  }

  // ======================================================= A6 Hard keeps the other units

  @Test
  void hardCanUseAUnitTheProgramDidNotBuy() {
    on();
    atWar();
    final Territory gib = territory("Gibraltar");
    addUnits(gib, "Americans", "infantry", 2);
    final List<Unit> us = gib.getMatches(Matches.unitIsOwnedBy(player("Americans")));
    programma("Americans", List.of(us.get(0)));
    final ProData pd = proData(player("Americans"), 3);
    Impegno.inizioTurno(pd, calc(), player("Americans"));
    final Set<Unit> held = SbarcoCoordinato.trattenute(data, player("Americans"));
    assertThat(held).contains(us.get(0)).doesNotContain(us.get(1));
    final ProPlusTuning tuning = (ProPlusTuning) pd.getTuning();
    tuning.holdCombatForPurchase(data, player("Americans"), held);
    assertThat(tuning.amphibiousHeldUnits(pd)).contains(us.get(0)).doesNotContain(us.get(1));
    // no cap on the transports for the USA
    assertThat(tuning.buysSeaTransports(pd)).isTrue();
  }

  // ============================================================== B2 British transports

  @Test
  void aBritishTransportIsBoughtWithThePortDefendedAndBlockedWhenNot() {
    assertThat(AlleatiR5.mareDifeso(40, 40)).isTrue();
    assertThat(AlleatiR5.mareDifeso(40.01, 40)).isFalse();
    on();
    atWar();
    // no Axis ship or plane anywhere: the port's zone is defended
    final ProData pd = proData(player("British"), 3);
    Impegno.inizioTurno(pd, calc(), player("British"));
    final JsonObject ok = lastDecision("trasporti_mare_difeso");
    assertThat(ok.getAsJsonObject("motivo").get("codice").getAsString()).isEqualTo("MARE_DIFESO");
    assertThat(((ProPlusTuning) pd.getTuning()).buysSeaTransports(pd)).isTrue();
    // the planes that can scramble from the United Kingdom's airfield are counted (map rule)
    assertThat(alternative(ok, "zona", "110 Sea Zone").get("aerei_scramble").getAsInt())
        .isPositive();
    // a big German fleet next to the port's zones, no British ship there
    for (final String z : List.of("109 Sea Zone", "110 Sea Zone")) {
      final Territory t = territory(z);
      data.performChange(ChangeFactory.removeUnits(t, t.getMatches(Matches.unitIsSea())));
    }
    addUnits(territory("111 Sea Zone"), "Germans", "battleship", 6);
    addUnits(territory("104 Sea Zone"), "Germans", "battleship", 6);
    final ProData pd2 = proData(player("British"), 4);
    Impegno.inizioTurno(pd2, calc(), player("British"));
    final JsonObject no = lastDecision("trasporti_mare_difeso");
    assertThat(no.getAsJsonObject("motivo").get("codice").getAsString())
        .as(no.toString())
        .isEqualTo("MARE_NON_DIFESO");
    assertThat(no.get("scelta").getAsString()).isEqualTo("bloccato");
    assertThat(((ProPlusTuning) pd2.getTuning()).buysSeaTransports(pd2)).isFalse();
    Impegno.ordini(pd2)
        .ifPresent(
            o ->
                assertThat(o.mandatory())
                    .noneMatch(m -> m.category().equals("programma transport")));
    // with PUs the program buys first what defends the zone (a warship or a fighter)
    final Programmi.Turno p =
        new Programmi.Turno(
            pd2,
            calc(),
            player("British"),
            "Allies",
            calcolo(player("British")),
            new ArrayList<>());
    final AlleatiR5.Difesa d = AlleatiR5.difesaPorto(pd2, p, 40);
    assertThat(d.difeso()).isFalse();
    assertThat(d.acquisto()).isNotEmpty();
    assertThat(d.acquisto().keySet()).allMatch(k -> List.of("destroyer", "fighter").contains(k));
  }

  // ================================================================== B3 attrition

  @Test
  void theBritishAttritionLandsWithoutAHoldCondition() {
    on();
    atWar();
    // Normandy German with one infantry, a huge German army in France; the other coasts strong
    for (final String n : List.of("Normandy Bordeaux", "France")) {
      final Territory t = territory(n);
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
      data.performChange(ChangeFactory.changeOwner(t, player("Germans")));
    }
    addUnits(territory("Normandy Bordeaux"), "Germans", "infantry", 1);
    addUnits(territory("France"), "Germans", "armour", 25);
    for (final String n : List.of("Holland Belgium", "Norway", "Denmark", "Western Germany")) {
      addUnits(territory(n), "Germans", "armour", 25);
    }
    // the program's force at home: 2 transports in 110, 4 land units in the United Kingdom
    addUnits(territory("110 Sea Zone"), "British", "transport", 2);
    final Territory uk = territory("United Kingdom");
    final List<Unit> before = uk.getMatches(Matches.unitIsOwnedBy(player("British")));
    addUnits(uk, "British", "infantry", 2);
    addUnits(uk, "British", "armour", 2);
    final List<Unit> troops =
        new ArrayList<>(uk.getMatches(Matches.unitIsOwnedBy(player("British"))));
    troops.removeAll(before);
    final List<Unit> program = new ArrayList<>(troops);
    program.addAll(
        territory("110 Sea Zone")
            .getMatches(
                Matches.unitIsOwnedBy(player("British")).and(Matches.unitIsSeaTransport())));
    programma("British", program);
    Impegno.inizioTurno(proData(player("British"), 3), calc(), player("British"));
    final JsonObject row = lastDecision("logoramento_uk");
    assertThat(row.getAsJsonObject("motivo").get("codice").getAsString())
        .as(row.toString())
        .isEqualTo("LOGORAMENTO");
    assertThat(row.get("scelta").getAsString()).isEqualTo("Normandy Bordeaux");
    final JsonObject alt = alternative(row, "Normandy Bordeaux");
    assertThat(alt.get("vittoria_pct").getAsDouble()).isGreaterThanOrEqualTo(70);
    assertThat(alt.get("tenuta_stimata_pct_info").getAsDouble()).isLessThan(60);
    final List<MoveDescription> moves = new ArrayList<>();
    SbarcoCoordinato.combattimento(
        proData(player("British"), 3, "Combat Move"), calc(), recording(moves), player("British"));
    final Set<Unit> landed = new HashSet<>();
    moves.stream()
        .filter(m -> m.getRoute().getEnd().equals(territory("Normandy Bordeaux")))
        .forEach(m -> landed.addAll(m.getUnits()));
    assertThat(landed).isNotEmpty();
    assertThat(troops).containsAll(landed);
  }

  // ================================================================ B5 South Africa

  @Test
  void southAfricaIsBoughtWithAnItalianAfricanBonusActiveAndNotWhenNoConditionHolds() {
    assertThat(AlleatiR5.condizioneSudafrica(true, true, false)).contains("bonus_africano");
    assertThat(AlleatiR5.condizioneSudafrica(false, false, false)).contains("egitto_non_alleato");
    assertThat(AlleatiR5.condizioneSudafrica(false, true, true)).contains("egitto_caso_c");
    assertThat(AlleatiR5.condizioneSudafrica(false, true, false)).isEmpty();
    on();
    war("British", "Italians");
    data.getSequence().setRoundAndStep(3, "Purchase", player("British"));
    // the Axis holds all of North Africa: the Italian bonus "North Africa" is active
    for (final String n :
        List.of("Morocco", "Algeria", "Tunisia", "Libya", "Tobruk", "Alexandria")) {
      data.performChange(ChangeFactory.changeOwner(territory(n), player("Italians")));
    }
    assertThat(AlleatiR5.bonusAfricaniAttivi(data))
        .containsKey("objectiveAttachment_Italians_3_North_Africa");
    final JsonObject order = new JsonObject();
    assertThat(
            AlleatiR5.acquistiSudafrica(
                data, player("British"), 30, order, new ArrayList<>(), false))
        .isPositive();
    assertThat(order.get("fabbrica_sudafrica").getAsString()).isEqualTo("Union of South Africa");
    assertThat(
            lastDecision("africa_sudafrica").getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("CONDIZIONE_VERA");
    // North Africa allied again, Egypt allied and not in case c: no condition
    for (final String n :
        List.of("Morocco", "Algeria", "Tunisia", "Libya", "Tobruk", "Alexandria")) {
      data.performChange(ChangeFactory.changeOwner(territory(n), player("British")));
    }
    assertThat(AlleatiR5.bonusAfricaniAttivi(data)).isEmpty();
    final JsonObject none = new JsonObject();
    assertThat(
            AlleatiR5.acquistiSudafrica(
                data, player("British"), 30, none, new ArrayList<>(), false))
        .isZero();
    assertThat(none.has("tipi_sudafrica")).isFalse();
    final JsonObject row = lastDecision("africa_sudafrica");
    assertThat(row.getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("CONDIZIONE_FALSA");
  }

  // ========================================================================== helpers

  private void on() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,alleati_r5");
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

  /** {@code units} are the program's of {@code nation} (followed by id). */
  private void programma(final String nation, final List<Unit> units) {
    final JsonObject imp = Impegno.stato(data.getProperties(), "Allies");
    final JsonObject ids = new JsonObject();
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
    return alternative(row, "territorio", territory);
  }

  private static JsonObject alternative(
      final JsonObject row, final String key, final String value) {
    for (final JsonElement e : row.getAsJsonArray("alternative")) {
      final JsonObject a = e.getAsJsonObject();
      if (a.has(key) && value.equals(a.get(key).getAsString())) {
        return a;
      }
    }
    throw new AssertionError(value + " non tra le alternative: " + row);
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
