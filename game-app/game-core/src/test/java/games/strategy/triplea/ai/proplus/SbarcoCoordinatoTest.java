package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
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
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
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
 * Step R3a "SBARCO COORDINATO E TEATRI" (Global 1940, the Allies) on built positions: the only
 * open theater is chosen; no change before 3 rounds or with a wave at sea; green light at 70 and
 * not at 69; denied when the alliance's hold is not enough, given when the British reinforcements
 * are; the landing with all the load; Hard does not use the force held in preparation; the cap on
 * Hard's transports; Russia = Hard; every decision has its row in the register and its outcome
 * when it can be checked; switch off = nothing.
 */
class SbarcoCoordinatoTest extends AbstractClientSettingTestCase {
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
    for (final String n : List.of("Americans", "British", "Russians")) {
      Stratega.registra(data, player(n));
    }
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(
        games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.FOLDER_PROPERTY);
  }

  @Test
  void theDataFileHasTheValuesOfStepR3a() {
    final SbarcoCoordinato.Config cfg = SbarcoCoordinato.Config.get();
    assertThat(cfg.nazioni()).containsExactly("Americans", "British", "Russians");
    assertThat(cfg.nazioniCampagnaTerra()).doesNotContain("Russians").contains("Americans");
    assertThat(cfg.teatri().get("NORD").basi()).containsExactly("United Kingdom");
    assertThat(cfg.teatri().get("SUD").basi()).containsExactly("Morocco", "Gibraltar");
    assertThat(cfg.teatri().get("NORD").bersagli()).contains("Normandy Bordeaux", "Norway");
    assertThat(cfg.teatri().get("SUD").bersagli()).contains("Algeria", "Southern France");
    assertThat(cfg.sogliaMareAperto()).isEqualTo(40);
    assertThat(cfg.roundMinimi()).isEqualTo(3);
    assertThat(cfg.sogliaViaLibera()).isEqualTo(70);
    assertThat(cfg.sogliaTenuta()).isEqualTo(40);
    assertThat(cfg.sogliaCompletaUk()).isEqualTo(70);
    assertThat(cfg.campagneTestaDiPonte()).containsExactly("France", "Germany", "Southern Italy");
  }

  @Test
  void switchOffIsNothing() {
    final ProData pd = proData(player("Americans"), 2);
    final Map<String, String> before = snapshot();
    Impegno.inizioTurno(pd, calc(), player("Americans"));
    assertThat(SbarcoCoordinato.attivo(data, player("Americans"))).isFalse();
    assertThat(snapshot()).isEqualTo(before);
    assertThat(SbarcoCoordinato.trattenute(data, player("Americans"))).isEmpty();
    assertThat(SbarcoCoordinato.tetto(pd)).isFalse();
    assertThat(pd.getTuning().buysSeaTransports(pd)).isTrue();
    assertThat(RegistroDecisioni.righe(data)).isEmpty();
  }

  @Test
  void theOnlyOpenTheaterIsChosen() {
    on();
    atWar();
    // SOUTH closed: an Axis fleet next to Gibraltar and Morocco
    addUnits(territory("92 Sea Zone"), "Italians", "battleship", 4);
    addUnits(territory("92 Sea Zone"), "Italians", "cruiser", 4);
    Impegno.inizioTurno(proData(player("Americans"), 2), calc(), player("Americans"));
    assertThat(SbarcoCoordinato.teatroCorrente(data, "Allies")).contains("NORD");
    final JsonObject first = lastDecision("teatro");
    assertThat(open(first, "NORD")).isTrue();
    assertThat(open(first, "SUD")).isFalse();
    assertThat(first.getAsJsonObject("motivo").get("frase").getAsString()).contains("solo NORD");
    assertThat(SbarcoCoordinato.baseCorrente(data, "Allies")).contains("United Kingdom");
    // later: NORTH closed (its base no longer usable), SOUTH open, 3 rounds since the choice,
    // no wave at sea -> SOUTH
    data.performChange(
        ChangeFactory.removeUnits(
            territory("92 Sea Zone"),
            territory("92 Sea Zone").getMatches(Matches.unitIsOwnedBy(player("Italians")))));
    closeTheNorth();
    Impegno.inizioTurno(proData(player("Americans"), 5), calc(), player("Americans"));
    assertThat(SbarcoCoordinato.teatroCorrente(data, "Allies")).contains("SUD");
    final JsonObject change = lastDecision("teatro");
    assertThat(change.getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("UNICO_APERTO");
    assertThat(open(change, "NORD")).isFalse();
    assertThat(open(change, "SUD")).isTrue();
    // the base follows the theater (Morocco, allied)
    assertThat(SbarcoCoordinato.baseCorrente(data, "Allies")).contains("Morocco");
    assertThat(decisions("cambio_base")).isNotEmpty();
  }

  @Test
  void noTheaterChangeBeforeThreeRoundsNorWithAWaveAtSea() {
    // first choice: no inertia
    assertThat(
            SbarcoCoordinato.sceltaTeatro(null, 1, 1, true, false, true, 0, 0, 3, "NORD")
                .teatro())
        .isEqualTo("SUD");
    // only SOUTH open, 2 rounds since the change: stays NORTH
    final var early =
        SbarcoCoordinato.sceltaTeatro("NORD", 3, 5, false, false, true, 0, 0, 3, "NORD");
    assertThat(early.teatro()).isEqualTo("NORD");
    assertThat(early.codice()).isEqualTo("INERZIA_ROUND");
    // 3 rounds: changes
    final var later =
        SbarcoCoordinato.sceltaTeatro("NORD", 3, 6, false, false, true, 0, 0, 3, "NORD");
    assertThat(later.teatro()).isEqualTo("SUD");
    assertThat(later.codice()).isEqualTo("UNICO_APERTO");
    // a wave at sea: never
    final var wave =
        SbarcoCoordinato.sceltaTeatro("NORD", 1, 9, true, false, true, 0, 0, 3, "NORD");
    assertThat(wave.teatro()).isEqualTo("NORD");
    assertThat(wave.codice()).isEqualTo("ONDATA_IN_MARE");
    // both open: the higher score; none open: the current one
    assertThat(
            SbarcoCoordinato.sceltaTeatro("NORD", 1, 9, false, true, true, 2, 5, 3, "NORD")
                .teatro())
        .isEqualTo("SUD");
    final var none =
        SbarcoCoordinato.sceltaTeatro("SUD", 1, 9, false, false, false, 0, 0, 3, "NORD");
    assertThat(none.teatro()).isEqualTo("SUD");
    assertThat(none.codice()).isEqualTo("NESSUNO_APERTO");
    // on a built board: a loaded US transport outside the port's zones is a wave at sea
    on();
    atWar();
    addUnits(territory("92 Sea Zone"), "Italians", "battleship", 4);
    Impegno.inizioTurno(proData(player("Americans"), 2), calc(), player("Americans"));
    assertThat(SbarcoCoordinato.teatroCorrente(data, "Allies")).contains("NORD");
    data.performChange(
        ChangeFactory.removeUnits(
            territory("92 Sea Zone"),
            territory("92 Sea Zone").getMatches(Matches.unitIsOwnedBy(player("Italians")))));
    closeTheNorth();
    loadedTransport(territory("102 Sea Zone"), "Americans", "infantry");
    Impegno.inizioTurno(proData(player("Americans"), 6), calc(), player("Americans"));
    assertThat(SbarcoCoordinato.teatroCorrente(data, "Allies")).contains("NORD");
    assertThat(lastDecision("teatro").getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("ONDATA_IN_MARE");
  }

  @Test
  void greenLightAtSeventyAndNotAtSixtyNine() {
    assertThat(SbarcoCoordinato.viaLibera(70, 40, 70, 40).via()).isTrue();
    assertThat(SbarcoCoordinato.viaLibera(69, 40, 70, 40).via()).isFalse();
    assertThat(SbarcoCoordinato.viaLibera(69.99, 0, 70, 40).codice()).isEqualTo("MANCA_VITTORIA");
    assertThat(SbarcoCoordinato.viaLibera(90, 40.01, 70, 40).codice()).isEqualTo("MANCA_TENUTA");
    assertThat(SbarcoCoordinato.viaLibera(50, 60, 70, 40).codice())
        .isEqualTo("MANCANO_VITTORIA_E_TENUTA");
    assertThat(SbarcoCoordinato.viaLibera(70, 40, 70, 40).codice()).isEqualTo("VIA_LIBERA");
  }

  @Test
  void deniedWithoutTheAllianceHoldAndGivenWithTheBritishReinforcements() {
    on();
    atWar();
    landingBoard();
    // the US load in position: 2 loaded transports next to Normandy (a base zone)
    for (int i = 0; i < 2; i++) {
      loadedTransport(territory("110 Sea Zone"), "Americans", "infantry", "armour");
    }
    // the British reinforcements: transports in the base's zones, troops in the United Kingdom
    addUnits(territory("110 Sea Zone"), "British", "transport", 3);
    addUnits(territory("United Kingdom"), "British", "infantry", 6);
    addUnits(territory("United Kingdom"), "British", "armour", 3);
    final Territory normandy = territory("Normandy Bordeaux");
    final ProData pd = proData(player("Americans"), 3);
    boolean found = false;
    for (int k = 1; k <= 14 && !found; k++) {
      // a growing German counterattack from France
      addUnits(territory("France"), "Germans", "infantry", 1);
      final SbarcoCoordinato.Turno t = turno(pd, player("Americans"));
      final SbarcoCoordinato.Turno.Piano plan = t.piano(normandy, player("Americans"), 0);
      assertThat(plan.carico()).hasSize(4);
      final SbarcoCoordinato.Turno.Stima alone = t.stima(plan, false);
      final SbarcoCoordinato.Turno.Stima alliance = t.stima(plan, true);
      assertThat(alliance.rinforzi()).isNotEmpty();
      assertThat(alliance.vittoria()).isGreaterThanOrEqualTo(70);
      final var denied =
          SbarcoCoordinato.viaLibera(alone.vittoria(), alone.contrattaccoSolo(), 70, 40);
      final var given =
          SbarcoCoordinato.viaLibera(
              alliance.vittoria(), alliance.contrattaccoAlleanza(), 70, 40);
      if (!denied.via() && given.via()) {
        assertThat(denied.codice()).isEqualTo("MANCA_TENUTA");
        found = true;
      }
    }
    assertThat(found)
        .as("a German counterattack the USA alone do not hold and the alliance holds")
        .isTrue();
  }

  @Test
  void theWaveLandsWithAllItsLoadAtOnce() {
    on();
    atWar();
    landingBoard();
    // empty US transports in the base's zone, US troops in the United Kingdom (the base)
    addUnits(territory("110 Sea Zone"), "Americans", "transport", 3);
    addUnits(territory("United Kingdom"), "Americans", "infantry", 3);
    addUnits(territory("United Kingdom"), "Americans", "armour", 3);
    final List<Unit> troops =
        territory("United Kingdom").getMatches(Matches.unitIsOwnedBy(player("Americans")));
    assertThat(troops).hasSize(6);
    final JsonObject st = state();
    st.addProperty("teatro", "NORD");
    st.addProperty("teatro_dal", 1);
    st.addProperty("base", "United Kingdom");
    final JsonObject wave = new JsonObject();
    wave.addProperty("round", 3);
    wave.addProperty("nazione", "Americans");
    wave.addProperty("bersaglio", "Normandy Bordeaux");
    wave.addProperty("id", "R3-Americans-via_libera_o_attesa-1");
    wave.addProperty("vittoria_pct", 95);
    wave.addProperty("perdite_p80_tuv", 3);
    wave.addProperty("tenuta_alleanza_pct", 80);
    wave.addProperty("tenuta_usa_pct", 70);
    wave.addProperty("presidio_base", 0);
    wave.addProperty("eseguito", false);
    st.add("ondata", wave);
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    final List<MoveDescription> moves = new ArrayList<>();
    final ProData pd = proData(player("Americans"), 3, "Combat Move");
    final Set<Unit> moved =
        SbarcoCoordinato.combattimento(pd, calc(), recording(moves), player("Americans"));
    final Territory normandy = territory("Normandy Bordeaux");
    final Set<Unit> loaded = new HashSet<>();
    final Set<Unit> landed = new HashSet<>();
    for (final MoveDescription m : moves) {
      if (m.getRoute().getEnd().equals(normandy)) {
        landed.addAll(m.getUnits());
      } else if (m.getRoute().getStart().equals(territory("United Kingdom"))) {
        loaded.addAll(m.getUnits());
      }
    }
    assertThat(loaded).containsExactlyInAnyOrderElementsOf(troops);
    assertThat(landed).containsExactlyInAnyOrderElementsOf(troops);
    assertThat(moved).containsAll(troops);
    // all in the same combat move: one wave, then never again in the round
    assertThat(state().getAsJsonObject("ondata").get("eseguito").getAsBoolean()).isTrue();
    assertThat(state().getAsJsonObject("ondata").get("unita_sbarcate").getAsInt()).isEqualTo(6);
    moves.clear();
    assertThat(SbarcoCoordinato.combattimento(pd, calc(), recording(moves), player("Americans")))
        .isEmpty();
    assertThat(moves).isEmpty();
    final JsonObject landing = lastDecision("sbarco");
    assertThat(landing.getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("ESEGUITO");
  }

  @Test
  void hardDoesNotUseTheForceHeldInPreparation() {
    on();
    atWar();
    // the British landing force in the United Kingdom, an enemy coast in reach by sea
    final GamePlayer uk = player("British");
    // the United Kingdom is the only source of troops for the transports near it
    final Territory scotland = territory("Scotland");
    data.performChange(
        ChangeFactory.removeUnits(
            scotland, scotland.getMatches(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()))));
    addUnits(territory("110 Sea Zone"), "British", "transport", 2);
    final ProData pd = proData(uk, 2);
    Impegno.inizioTurno(pd, calc(), uk);
    final Set<Unit> held = SbarcoCoordinato.trattenute(data, uk);
    final List<Unit> atBase =
        territory("United Kingdom")
            .getMatches(
                Matches.unitIsOwnedBy(uk)
                    .and(Matches.unitIsLand())
                    .and(Matches.unitIsNotInfrastructure()));
    assertThat(atBase).isNotEmpty();
    assertThat(held).containsAll(atBase);
    // Hard's attack options without them (as ProPlusAi holds them at the purchase)
    final ProTerritoryManager tm = new ProTerritoryManager(calc(), pd);
    tm.populateAttackOptions();
    final Set<Unit> before = usable(tm);
    assertThat(before).as("the British force could attack").containsAnyElementsOf(atBase);
    tm.getAttackOptions().removeUnits(held);
    assertThat(usable(tm)).doesNotContainAnyElementsOf(atBase);
    // and never loaded on a transport for an assault (Hard's amphibious loading)
    final ProPlusTuning tuning = (ProPlusTuning) pd.getTuning();
    tuning.holdCombatForPurchase(data, uk, held);
    assertThat(tuning.amphibiousHeldUnits(pd)).containsAll(atBase);
    assertThat(new ProTuning() {}.amphibiousHeldUnits(pd)).as("Hard").isEmpty();
    // the count is in the register
    final JsonObject row = lastDecision("unita_trattenute");
    assertThat(Integer.parseInt(row.get("scelta").getAsString())).isEqualTo(held.size());
    // in the non-combat move they stay at the base
    final ProData nc = proData(uk, 2, "Non Combat Move");
    final Set<Unit> kept =
        Impegno.nonCombattimento(nc, calc(), recording(new ArrayList<>()), uk);
    assertThat(kept).containsAll(atBase);
  }

  @Test
  void theCapOnHardsTransports() {
    assertThat(SbarcoCoordinato.tettoTrasporti(1)).isTrue();
    assertThat(SbarcoCoordinato.tettoTrasporti(0)).isFalse();
    on();
    atWar();
    // the US transport of the map waits empty at the port (zone 101)
    final GamePlayer usa = player("Americans");
    final ProData pd = proData(usa, 2);
    assertThat(new ProTuning() {}.buysSeaTransports(pd)).as("Hard").isTrue();
    Impegno.inizioTurno(pd, calc(), usa);
    assertThat(SbarcoCoordinato.tetto(pd)).isTrue();
    assertThat(pd.getTuning().buysSeaTransports(pd)).isFalse();
    assertThat(pd.getPurchaseOptions().getSeaTransportOptions()).isNotEmpty();
    final var capped = pd.getPurchaseOptions().withoutSeaTransports();
    assertThat(capped.getSeaTransportOptions()).isEmpty();
    assertThat(capped.getAllOptions())
        .noneMatch(o -> o.isTransport() && Matches.unitTypeIsSea().test(o.getUnitType()));
    assertThat(capped.getLandOptions()).hasSameSizeAs(pd.getPurchaseOptions().getLandOptions());
    assertThat(lastDecision("tetto_trasporti").getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("TRASPORTO_FERMO_VUOTO");
    // the outcome: what Hard bought
    final Set<String> before = Impegno.primaDelPiazzamento(data, usa);
    addUnits(territory("Eastern United States"), "Americans", "infantry", 2);
    Impegno.dopoIlPiazzamento(data, usa, before);
    final JsonObject outcome = outcome(lastDecision("tetto_trasporti").get("id").getAsString());
    assertThat(outcome.getAsJsonObject("esito").get("trasporti_comprati").getAsInt()).isZero();
    assertThat(outcome.getAsJsonObject("esito").get("tetto_attivo").getAsBoolean()).isTrue();
    // no idle empty transport any more: Hard buys as always
    data.performChange(
        ChangeFactory.removeUnits(
            territory("101 Sea Zone"),
            territory("101 Sea Zone")
                .getMatches(Matches.unitIsSeaTransport().and(Matches.unitIsOwnedBy(usa)))));
    final ProData next = proData(usa, 3);
    Impegno.inizioTurno(next, calc(), usa);
    assertThat(SbarcoCoordinato.tetto(next)).isFalse();
    assertThat(next.getTuning().buysSeaTransports(next)).isTrue();
  }

  @Test
  void russiaPlaysAsHard() {
    final GamePlayer russia = player("Russians");
    final ProData off = proData(russia, 2);
    final List<String> answersOff = answers(off);
    on();
    final ProData pd = proData(russia, 2);
    Impegno.inizioTurno(pd, calc(), russia);
    assertThat(Impegno.attivo(data, russia)).isTrue();
    assertThat(Impegno.ordini(pd)).isEmpty();
    assertThat(Impegno.forzaQualcosa(pd)).isFalse();
    assertThat(SbarcoCoordinato.trattenute(data, russia)).isEmpty();
    assertThat(answers(pd)).isEqualTo(answersOff);
    final JsonObject imp =
        JsonParser.parseString((String) data.getProperties().get(Impegno.KEY_STATO + "Allies"))
            .getAsJsonObject();
    assertThat(Impegno.nazione(imp, "Russians").has("obiettivo")).isFalse();
    assertThat(
            Impegno.nonCombattimento(
                proData(russia, 2, "Non Combat Move"),
                calc(),
                recording(new ArrayList<>()),
                russia))
        .isEmpty();
  }

  @Test
  void everyDecisionHasItsRowAndTheOutcomeWhenItCanBeChecked() {
    on();
    atWar();
    // a bridgehead taken in round 1 (Normandy, the alliance's): its hold is checked
    final JsonObject st = state();
    st.addProperty("teatro", "NORD");
    st.addProperty("teatro_dal", 1);
    st.addProperty("base", "United Kingdom");
    final JsonObject pend = new JsonObject();
    pend.addProperty("id", "R1-Americans-sbarco-1");
    pend.addProperty("tipo", "sbarco");
    pend.addProperty("nazione", "Americans");
    pend.addProperty("round", 1);
    pend.addProperty("bersaglio", "Normandy Bordeaux");
    pend.addProperty("controllo", "tenuta");
    pend.addProperty("tenuta_stimata", 75);
    final JsonArray pending = new JsonArray();
    pending.add(pend);
    st.add("pendenti", pending);
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    final GamePlayer usa = player("Americans");
    Impegno.inizioTurno(proData(usa, 2), calc(), usa);
    final List<JsonObject> rows = RegistroDecisioni.righe(data);
    final Set<String> types = new HashSet<>();
    for (final JsonObject r : rows) {
      assertThat(r.has("id")).isTrue();
      if ("decisione".equals(r.get("riga").getAsString())) {
        types.add(r.get("tipo").getAsString());
        assertThat(RegistroDecisioni.MOTIVI)
            .contains(r.getAsJsonObject("motivo").get("codice").getAsString());
        assertThat(r.getAsJsonObject("motivo").get("frase").getAsString()).isNotBlank();
        assertThat(r.has("alternative") && r.has("parametri") && r.has("scelta")).isTrue();
        assertThat(r.get("nazione").getAsString()).isEqualTo("Americans");
        assertThat(r.get("round").getAsInt()).isEqualTo(2);
      }
    }
    assertThat(types)
        .contains(
            "teatro",
            "bersaglio_sbarco",
            "via_libera_o_attesa",
            "unita_trattenute",
            "tetto_trasporti",
            "acquisto_programma");
    // the bridgehead's hold after the enemy turn
    final JsonObject held = outcome("R1-Americans-sbarco-1");
    assertThat(held.getAsJsonObject("esito").get("tenuto").getAsBoolean()).isTrue();
    assertThat(held.getAsJsonObject("esito").get("round_dopo").getAsInt()).isEqualTo(1);
    // the shuttle has its row and the check of the losses at sea
    final ProData nc = proData(usa, 2, "Non Combat Move");
    Impegno.nonCombattimento(nc, calc(), recording(new ArrayList<>()), usa);
    assertThat(decisions("spola")).isNotEmpty();
    final String shuttle = decisions("spola").get(0).get("id").getAsString();
    Impegno.inizioTurno(proData(usa, 3), calc(), usa);
    assertThat(outcome(shuttle).getAsJsonObject("esito").has("unita_perse_in_mare")).isTrue();
  }

  // ================================================================================ helpers

  /** The NORTH closed: its base (United Kingdom) is no longer the alliance's. */
  private void closeTheNorth() {
    final Territory uk = territory("United Kingdom");
    data.performChange(ChangeFactory.removeUnits(uk, uk.getUnits()));
    data.performChange(ChangeFactory.changeOwner(uk, player("Germans")));
  }

  /** Normandy Bordeaux German with one infantry, France German and empty; no Axis at sea/air. */
  private void landingBoard() {
    final Territory normandy = territory("Normandy Bordeaux");
    final Territory france = territory("France");
    for (final Territory t : List.of(normandy, france)) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
      data.performChange(ChangeFactory.changeOwner(t, player("Germans")));
    }
    addUnits(normandy, "Germans", "infantry", 1);
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
      final GamePlayer enemy = player(e);
      data.performChange(
          ChangeFactory.relationshipChange(
              usa,
              enemy,
              data.getRelationshipTracker().getRelationshipType(usa, enemy),
              types.getDefaultWarRelationship()));
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

  private void loadedTransport(final Territory zone, final String owner, final String... cargo) {
    final Unit tr = data.getUnitTypeList().getUnitTypeOrThrow("transport").create(1, player(owner)).get(0);
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
  }

  private static Set<Unit> usable(final ProTerritoryManager tm) {
    final Set<Unit> out = new HashSet<>(tm.getAttackOptions().getUnitMoveMap().keySet());
    for (final var t : tm.getAttackOptions().getTerritoryMap().values()) {
      out.addAll(t.getMaxUnits());
      out.addAll(t.getMaxAmphibUnits());
      t.getAmphibAttackMap().values().forEach(out::addAll);
    }
    return out;
  }

  private SbarcoCoordinato.Turno turno(final ProData pd, final GamePlayer player) {
    final JsonObject st = state();
    st.addProperty("teatro", "NORD");
    st.addProperty("teatro_dal", 1);
    st.addProperty("base", "United Kingdom");
    SbarcoCoordinato.salva(data.getProperties(), "Allies", st);
    final CampagnaOmbra.Calcolo c =
        new CampagnaOmbra.Calcolo(pd, calc(), player, "Allies", CampagnaOmbra.Config.get());
    return new SbarcoCoordinato.Turno(pd, calc(), player, c, new ArrayList<>());
  }

  private boolean open(final JsonObject theaterRow, final String theater) {
    for (final var e : theaterRow.getAsJsonArray("alternative")) {
      final JsonObject a = e.getAsJsonObject();
      if (theater.equals(a.get("teatro").getAsString())) {
        return a.get("aperto").getAsBoolean();
      }
    }
    throw new AssertionError("teatro " + theater + " assente");
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

  private JsonObject outcome(final String id) {
    return RegistroDecisioni.righe(data).stream()
        .filter(r -> "esito".equals(r.get("riga").getAsString()))
        .filter(r -> id.equals(r.get("id").getAsString()))
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("nessun esito per " + id));
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

  private void on() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno");
  }

  private JsonObject state() {
    return SbarcoCoordinato.stato(data.getProperties(), "Allies");
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
    out.add(String.valueOf(t.buysSeaTransports(pd)));
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
