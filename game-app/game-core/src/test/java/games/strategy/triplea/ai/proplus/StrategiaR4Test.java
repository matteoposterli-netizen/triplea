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
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step R4 "STRATEGIA COMPLETA" on built positions (Global 1940): Moscow not a target while a
 * Russian factory is Russian (unless 70% this turn); purchases in the captured factory nearest to
 * Moscow; the raid ordered when Moscow is in range and not when its damage is at the maximum; Italy
 * never toward Russia while North Africa is not all the Axis'; the higher gain per round wins
 * between targets of the same priority; Gibraltar usable or not; the fast US units to Africa while
 * the Axis is there; only the program's units held; South Africa's purchase; the southern barrier
 * with the Axis within 3; the switches off = nothing.
 */
class StrategiaR4Test extends AbstractClientSettingTestCase {
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
    for (final String n : List.of("Germans", "Italians", "Americans", "British", "Russians")) {
      Stratega.registra(data, player(n));
    }
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(
        games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.FOLDER_PROPERTY);
  }

  // ===================================================================== data and switches

  @Test
  void theDataFileHasTheMapValuesOfStepR4AndTheSwitchesAreOff() {
    final ConfigR4 cfg = ConfigR4.get();
    assertThat(cfg.assedioDiDefault()).isFalse();
    assertThat(cfg.alleatiDiDefault()).isFalse();
    assertThat(cfg.fabbricheRusse()).containsExactly("Novgorod", "Ukraine", "Volgograd");
    assertThat(cfg.produzioneFabbricaCatturata()).isEqualTo(3);
    assertThat(cfg.puTerritori()).containsEntry("Egypt", 2).containsEntry("Russia", 3);
    assertThat(cfg.obiettivi())
        .anySatisfy(
            o -> {
              assertThat(o.id()).isEqualTo("objectiveAttachment_Italians_3_North_Africa");
              assertThat(o.minimo()).isEqualTo(6);
              assertThat(o.diretto()).isFalse();
            });
    assertThat(cfg.bombardamento().dannoAtteso())
        .isCloseTo(5.5 * 5 / 6, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(StrategaInterruttori.ASSEDIO.acceso(data.getProperties(), "Axis")).isFalse();
    assertThat(StrategaInterruttori.ALLEATI_R4.acceso(data.getProperties(), "Allies")).isFalse();
  }

  @Test
  void switchesOffAreNothing() {
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno");
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno");
    assertThat(Assedio.attivo(data, player("Germans"))).isFalse();
    assertThat(Assedio.italia(data, player("Italians"))).isFalse();
    assertThat(SnodoAlleati.attivo(data, player("Americans"))).isFalse();
    final ProData pd = proData(player("Germans"), 3, "CombatMove");
    final Map<Territory, ProTerritory> attack = new HashMap<>();
    Assedio.bombarda(pd, attack);
    assertThat(attack).isEmpty();
    assertThat(
            Assedio.bersagli(pd, calc(), player("Germans"), calcolo(pd, player("Germans"), "Axis")))
        .isEmpty();
    final JsonObject order = new JsonObject();
    assertThat(
            SnodoAlleati.acquistiSudafrica(data, player("British"), 50, order, new ArrayList<>()))
        .isZero();
    assertThat(order.size()).isZero();
  }

  // ============================================================================= A1 Moscow

  @Test
  void moscowIsNotATargetWhileARussianFactoryIsRussianUnless70ThisTurn() {
    final String moscow = "Russia";
    assertThat(Assedio.livelliGermania(List.of("Novgorod"), true, 69.9, 70, moscow))
        .containsOnlyKeys("Novgorod");
    assertThat(Assedio.livelliGermania(List.of("Novgorod"), true, 70, 70, moscow))
        .containsEntry(moscow, 0)
        .containsEntry("Novgorod", 1);
    assertThat(Assedio.livelliGermania(List.of(), true, 0, 70, moscow)).containsEntry(moscow, 1);
    // on the board: Germany at war with Russia, the three factories Russian
    assedioOn();
    war("Germans", "Russians");
    final ProData pd = proData(player("Germans"), 3, "Purchase");
    final Optional<Map<Territory, Integer>> levels =
        Assedio.bersagli(pd, calc(), player("Germans"), calcolo(pd, player("Germans"), "Axis"));
    assertThat(levels).isPresent();
    assertThat(levels.get().keySet().stream().map(Territory::getName))
        .contains("Novgorod", "Ukraine", "Volgograd")
        .doesNotContain(moscow);
  }

  // ============================================================== A2 captured factories

  @Test
  void theOperationBuysInTheCapturedFactoryNearestToMoscow() {
    assedioOn();
    war("Germans", "Russians");
    // Germany holds Ukraine and Novgorod (their minor factories)
    own("Ukraine", "Germans");
    own("Novgorod", "Germans");
    final List<Territory> captured = Assedio.fabbricheCatturate(data, player("Germans"));
    assertThat(captured.stream().map(Territory::getName)).containsExactly("Ukraine", "Novgorod");
    data.getSequence().setRoundAndStep(4, "Purchase", player("Germans"));
    final JsonObject order = new JsonObject();
    final Optional<Territory> f =
        Assedio.fabbricaOperazione(data, player("Germans"), order, new ArrayList<>());
    assertThat(f.map(Territory::getName)).contains("Ukraine");
    assertThat(order.getAsJsonArray("fabbriche_extra").get(0).getAsString()).isEqualTo("Novgorod");
    assertThat(Assedio.capacita(territory("Ukraine"))).isEqualTo(3);
    assertThat(lastDecision("rinforzo_fabbrica").get("scelta").getAsString()).isEqualTo("Ukraine");
  }

  // ======================================================================== A3 bombing

  @Test
  void theRaidIsOrderedWhenMoscowIsInRangeAndNotWhenItsDamageIsAtTheMaximum() {
    assertThat(Assedio.ordinaBombardamento(10, 1)).isTrue();
    assertThat(Assedio.ordinaBombardamento(0, 3)).isFalse();
    assertThat(Assedio.ordinaBombardamento(10, 0)).isFalse();
    assertThat(Assedio.bombardieriNecessari(10, ConfigR4.get().bombardamento().dannoAtteso()))
        .isEqualTo(3);
    assedioOn();
    war("Germans", "Russians");
    own("Belarus", "Germans");
    own("Smolensk", "Germans");
    addUnits(territory("Belarus"), "Germans", "bomber", 2);
    final Territory russia = territory("Russia");
    data.performChange(
        ChangeFactory.removeUnits(russia, russia.getMatches(Matches.unitCanIntercept())));
    final ProData pd = proData(player("Germans"), 5, "CombatMove");
    final Map<Territory, ProTerritory> attack = new HashMap<>();
    Assedio.bombarda(pd, attack);
    assertThat(attack).containsKey(russia);
    // the bombers needed (3 for Moscow's 10 units), the 2 in Belarus among them
    assertThat(attack.get(russia).getBombers())
        .hasSize(3)
        .containsAll(territory("Belarus").getMatches(Matches.unitIsStrategicBomber()));
    // the factory at its maximum damage (production 0): no raid
    final Unit factory = russia.getMatches(Matches.unitCanProduceUnitsAndCanBeDamaged()).get(0);
    data.performChange(
        ChangeFactory.bombingUnitDamage(
            org.triplea.java.collections.IntegerMap.of(Map.of(factory, 20)), List.of(russia)));
    final Map<Territory, ProTerritory> attack2 = new HashMap<>();
    Assedio.bombarda(proData(player("Germans"), 5, "CombatMove"), attack2);
    assertThat(attack2.containsKey(russia) ? attack2.get(russia).getBombers() : List.of())
        .isEmpty();
  }

  // =========================================================================== A4 Italy

  @Test
  void italyChoosesNoTargetInRussiaWhileNorthAfricaIsNotAllTheAxis() {
    assedioOn();
    war("Italians", "Russians");
    war("Italians", "British");
    final ProData pd = proData(player("Italians"), 3, "Purchase");
    final Optional<Map<Territory, Integer>> levels =
        Assedio.bersagli(pd, calc(), player("Italians"), calcolo(pd, player("Italians"), "Axis"));
    assertThat(levels).isPresent();
    assertThat(levels.get().keySet())
        .allSatisfy(t -> assertThat(t.getOwner().getName()).isNotEqualTo("Russians"));
    assertThat(levels.get()).containsEntry(territory("Egypt"), 1);
    assertThat(Assedio.livello1Asse(data, player("Italians"))).isFalse();
  }

  // ========================================================================= C gain

  @Test
  void betweenTwoTargetsOfTheSamePriorityTheHigherGainPerRoundWins() {
    final Reddito.Guadagno low = new Reddito.Guadagno("Ukraine", 2, 0, 0);
    final Reddito.Guadagno high = new Reddito.Guadagno("Novgorod", 2, 5, 0);
    final Optional<Assedio.Scelta> chosen =
        Assedio.scegli(
            List.of(
                new Assedio.Scelta("Ukraine", 1, low, true, 1),
                new Assedio.Scelta("Novgorod", 1, high, false, 3)));
    assertThat(chosen.map(Assedio.Scelta::territorio)).contains("Novgorod");
    // a lower level wins whatever the gain
    assertThat(
            Assedio.scegli(
                    List.of(
                        new Assedio.Scelta("Russia", 0, low, false, 4),
                        new Assedio.Scelta("Novgorod", 1, high, true, 1)))
                .map(Assedio.Scelta::territorio))
        .contains("Russia");
    // the rule: the last North African territory gives Italy its bonus and takes no British one
    final Map<String, String> owners = new HashMap<>();
    for (final String t : List.of("Alexandria", "Morocco", "Algeria", "Tunisia", "Tobruk")) {
      owners.put(t, "Italians");
    }
    owners.put("Libya", "British");
    final Reddito.Guadagno g =
        Reddito.guadagno(
            "Libya",
            1,
            "Italians",
            owners,
            n -> List.of("Italians", "Germans").contains(n) ? "Axis" : "Allies",
            ConfigR4.get());
    assertThat(g.bonusOttenuti()).isEqualTo(5);
    assertThat(g.totale()).isEqualTo(6 + g.bonusTolti());
    // the real board: Germany taking Novgorod gets the objective's 5 PUs
    war("Germans", "Russians");
    assertThat(Reddito.guadagno(data, player("Germans"), territory("Novgorod")).bonusOttenuti())
        .isEqualTo(5);
  }

  // ====================================================================== B1 Gibraltar

  @Test
  void gibraltarIsUsableOnlyWhenTheAlliancesWithoutEnemyWarships() {
    assertThat(SnodoAlleati.gibilterraUtilizzabile(true, 0)).isTrue();
    assertThat(SnodoAlleati.gibilterraUtilizzabile(true, 1)).isFalse();
    assertThat(SnodoAlleati.gibilterraUtilizzabile(false, 0)).isFalse();
    alleatiOn();
    usaAtWar();
    Impegno.inizioTurno(proData(player("Americans"), 3, "Purchase"), calc(), player("Americans"));
    assertThat(SbarcoCoordinato.teatroCorrente(data, "Allies")).contains("SUD");
    assertThat(SbarcoCoordinato.baseCorrente(data, "Allies")).contains("Gibraltar");
    assertThat(lastDecision("teatro").getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("GIBILTERRA");
    addUnits(territory("92 Sea Zone"), "Italians", "cruiser", 1);
    assertThat(
            territory("92 Sea Zone")
                .getMatches(Matches.enemyUnit(player("Americans")).and(Matches.unitIsSea())))
        .isNotEmpty();
    Impegno.inizioTurno(proData(player("Americans"), 4, "Purchase"), calc(), player("Americans"));
    assertThat(SbarcoCoordinato.teatroCorrente(data, "Allies"))
        .as(lastDecision("teatro").toString())
        .contains("NORD");
    assertThat(lastDecision("teatro").getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("REGNO_UNITO");
  }

  // ============================================================================ B2 hub

  @Test
  void theFastUsUnitsGoToAfricaWhileTheAxisIsThere() {
    final ConfigR4 cfg = ConfigR4.get();
    assertThat(SnodoAlleati.sceltaSnodo(true, "Southern France", cfg)).contains("MISTO");
    assertThat(SnodoAlleati.sceltaSnodo(true, null, cfg)).contains("AFRICA");
    assertThat(SnodoAlleati.sceltaSnodo(true, "Sardinia", cfg)).contains("AFRICA");
    assertThat(SnodoAlleati.sceltaSnodo(false, "Normandy Bordeaux", cfg)).contains("FRANCIA");
    assertThat(SnodoAlleati.sceltaSnodo(false, null, cfg)).isEmpty();
    final Unit armour =
        data.getUnitTypeList().getUnitTypeOrThrow("armour").create(1, player("Americans")).get(0);
    final Unit infantry =
        data.getUnitTypeList().getUnitTypeOrThrow("infantry").create(1, player("Americans")).get(0);
    assertThat(SnodoAlleati.veloce(armour, cfg)).isTrue();
    assertThat(SnodoAlleati.veloce(infantry, cfg)).isFalse();
  }

  // ===================================================================== B3 held units

  @Test
  void onlyTheProgramsUnitsAreHeldAtTheHub() {
    alleatiOn();
    usaAtWar();
    final Territory gib = territory("Gibraltar");
    addUnits(gib, "Americans", "infantry", 2);
    final List<Unit> us = gib.getMatches(Matches.unitIsOwnedBy(player("Americans")));
    // the first is the program's (followed by id), the second Hard's
    final JsonObject imp = Impegno.stato(data.getProperties(), "Allies");
    final JsonObject ids = new JsonObject();
    ids.addProperty(us.get(0).getId().toString(), "infantry");
    Impegno.nazione(imp, "Americans").add("programma_ids", ids);
    Impegno.salva(data.getProperties(), "Allies", imp);
    Impegno.inizioTurno(proData(player("Americans"), 3, "Purchase"), calc(), player("Americans"));
    assertThat(SbarcoCoordinato.trattenute(data, player("Americans")))
        .contains(us.get(0))
        .doesNotContain(us.get(1));
  }

  // ==================================================================== B4 South Africa

  @Test
  void theUnitedKingdomBuysInSouthAfricaWhileTheAxisIsInAfrica() {
    assertThat(SnodoAlleati.unitaSudafrica(false, true, 3)).isZero();
    assertThat(SnodoAlleati.unitaSudafrica(true, true, 3)).isEqualTo(3);
    assertThat(SnodoAlleati.unitaSudafrica(false, false, 2)).isEqualTo(2);
    alleatiOn();
    war("British", "Italians");
    data.getSequence().setRoundAndStep(2, "Purchase", player("British"));
    final JsonObject order = new JsonObject();
    final int pu =
        SnodoAlleati.acquistiSudafrica(data, player("British"), 30, order, new ArrayList<>());
    assertThat(pu).isPositive();
    assertThat(order.get("fabbrica_sudafrica").getAsString()).isEqualTo("Union of South Africa");
    final int units =
        order.getAsJsonObject("tipi_sudafrica").entrySet().stream()
            .mapToInt(e -> e.getValue().getAsInt())
            .sum();
    assertThat(units).isBetween(1, 3);
    assertThat(
            lastDecision("africa_sudafrica").getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo("COMPRA_SUDAFRICA");
  }

  // ===================================================================== B6 the barrier

  @Test
  void theBarrierIsMannedWithTheAxisWithin3Moves() {
    assertThat(SnodoAlleati.sbarramentoAttivo(3, 3)).isTrue();
    assertThat(SnodoAlleati.sbarramentoAttivo(4, 3)).isFalse();
    assertThat(SnodoAlleati.sbarramentoAttivo(-1, 3)).isFalse();
    war("British", "Italians");
    final Territory transJordan = territory("Trans-Jordan");
    // at the start the nearest Axis land units are in Tobruk (3 moves)
    assertThat(SnodoAlleati.distanzaAsse(data, player("British"), transJordan, 3)).isEqualTo(3);
    assertThat(SnodoAlleati.distanzaAsse(data, player("British"), transJordan, 2)).isEqualTo(-1);
    // Italian infantry in Egypt (taken): one move from Trans-Jordan
    own("Egypt", "Italians");
    addUnits(territory("Egypt"), "Italians", "infantry", 2);
    assertThat(SnodoAlleati.distanzaAsse(data, player("British"), transJordan, 3)).isEqualTo(1);
  }

  // ========================================================================== helpers

  private void assedioOn() {
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio");
  }

  private void alleatiOn() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,alleati_r4");
  }

  private void war(final String a, final String b) {
    data.performChange(
        ChangeFactory.relationshipChange(
            player(a),
            player(b),
            data.getRelationshipTracker().getRelationshipType(player(a), player(b)),
            data.getRelationshipTypeList().getDefaultWarRelationship()));
  }

  private void usaAtWar() {
    final GamePlayer usa = player("Americans");
    for (final String a : List.of("British", "French", "Russians")) {
      data.performChange(
          ChangeFactory.relationshipChange(
              usa,
              player(a),
              data.getRelationshipTracker().getRelationshipType(usa, player(a)),
              data.getRelationshipTypeList().getDefaultAlliedRelationship()));
    }
    for (final String e : List.of("Germans", "Italians", "Japanese")) {
      war("Americans", e);
    }
  }

  private void own(final String territory, final String owner) {
    data.performChange(ChangeFactory.changeOwner(territory(territory), player(owner)));
  }

  private void addUnits(final Territory t, final String owner, final String type, final int n) {
    data.performChange(
        ChangeFactory.addUnits(
            t, data.getUnitTypeList().getUnitTypeOrThrow(type).create(n, player(owner))));
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

  private CampagnaOmbra.Calcolo calcolo(
      final ProData pd, final GamePlayer player, final String alliance) {
    return new CampagnaOmbra.Calcolo(pd, calc(), player, alliance, CampagnaOmbra.Config.get());
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrNull(name);
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
