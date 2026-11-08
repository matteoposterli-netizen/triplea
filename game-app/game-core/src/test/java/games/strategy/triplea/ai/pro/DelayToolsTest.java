package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.StrumentiRitardo;
import games.strategy.triplea.ai.proplus.strategia.Contromosse;
import games.strategy.triplea.ai.proplus.strategia.DecisoreRegole;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.ai.proplus.strategia.Strategia;
import games.strategy.triplea.ai.proplus.strategia.Strategie;
import games.strategy.triplea.ai.proplus.strategia.Tattiche;
import games.strategy.triplea.ai.proplus.strategia.Valutazione;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 3d-2: the delay tools (Pro+ only) and their switches. Global 1940 renamed as Europe 1940
 * (same European names), real move delegate, board set up per test, the general staff's state
 * written directly (posture, reading, switches by the decider by rules).
 */
class DelayToolsTest extends AbstractClientSettingTestCase {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private static final long SEED = 20261108L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer russians = player("Russians");
  private final GamePlayer germans = player("Germans");
  private final GamePlayer british = player("British");
  private Strategia strategia;

  @BeforeEach
  void setUp() {
    data.setGameName(EUROPE);
    strategia = Strategie.forGame(data).orElseThrow();
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "A");
    StatoMaggiore.fix(data, "Allies", strategia);
    StatoMaggiore.fix(data, "Axis", strategia);
    atWar(germans, russians);
  }

  // ------------------------------------------------------------------------------ the decider

  @Test
  void theDeciderTurnsTheToolsOnForTheDelayingNationAndForTheSlowingPosture() {
    final Strategia.Piano plan1 = strategia.alleanza("Allies").orElseThrow().piano("1").get();

    final JsonObject offensive =
        Tattiche.perRuoloEPostura(data, "Allies", plan1, Contromosse.OFFENSIVA);
    assertThat(Tattiche.perche(offensive, Tattiche.BLOCCO_TERRESTRE, "Russians", "Est"))
        .contains("ruolo ritarda nel piano 1");
    assertThat(Tattiche.perche(offensive, Tattiche.CACCIA, "British", null)).isEmpty();

    final JsonObject slowing =
        Tattiche.perRuoloEPostura(data, "Allies", plan1, Contromosse.RALLENTAMENTO);
    assertThat(Tattiche.perche(slowing, Tattiche.BLOCCO_NAVALE, "British", "Mari"))
        .contains("postura RALLENTAMENTO");
    assertThat(Tattiche.perche(slowing, Tattiche.RITIRATA, "Russians", null))
        .contains("ruolo ritarda nel piano 1");
    assertThat(
            Tattiche.perche(
                Tattiche.perRuoloEPostura(data, "Allies", plan1, Contromosse.DIFENSIVA),
                Tattiche.RITIRATA,
                "Americans",
                null))
        .contains("postura DIFENSIVA");
    // a front switch: only on the fronts listed
    slowing
        .getAsJsonObject(Tattiche.BLOCCO_TERRESTRE)
        .getAsJsonObject("British")
        .getAsJsonArray("fronti")
        .add("Ovest");
    assertThat(Tattiche.perche(slowing, Tattiche.BLOCCO_TERRESTRE, "British", "Ovest")).isPresent();
    assertThat(Tattiche.perche(slowing, Tattiche.BLOCCO_TERRESTRE, "British", "Est")).isEmpty();
  }

  @Test
  void planNAndTheAxisPlansWithoutDelayRolesHaveNoTools() {
    final Strategia.Piano planN = strategia.alleanza("Axis").orElseThrow().piano("N").get();
    final Strategia.Piano planA = strategia.alleanza("Axis").orElseThrow().piano("A").get();

    assertThat(Tattiche.perRuoloEPostura(data, "Axis", planN, Contromosse.RALLENTAMENTO).toString())
        .doesNotContain("Germans");
    assertThat(Tattiche.perRuoloEPostura(data, "Axis", planA, Contromosse.OFFENSIVA).toString())
        .doesNotContain("Germans")
        .doesNotContain("Italians");
    assertThat(
            Tattiche.testo(Tattiche.perRuoloEPostura(data, "Axis", planA, Contromosse.OFFENSIVA)))
        .endsWith("nessuna");
  }

  // ----------------------------------------------------------------------------- land blocks

  @Test
  void anInfantryBlocksTheEmptyTerritoryOnTheWayOfEnemyTanksToAKeyTerritory() {
    setUpEastFront();
    staff("Allies", "1", Contromosse.OFFENSIVA, "A", 0.6);
    nonCombat(russians, 3);

    final StrumentiRitardo.Esito result = play(russians);

    final List<StrumentiRitardo.Azione> blocks =
        result.azioni().stream()
            .filter(a -> a.strumento().equals(Tattiche.BLOCCO_TERRESTRE))
            .toList();
    assertThat(blocks)
        .as(result.note().toString())
        .extracting(StrumentiRitardo.Azione::territorio)
        .contains("Belarus");
    final StrumentiRitardo.Azione belarus =
        blocks.stream().filter(a -> a.territorio().equals("Belarus")).findFirst().orElseThrow();
    assertThat(belarus.unita()).hasSize(1);
    assertThat(belarus.unita().get(0).getType().getName()).isEqualTo("infantry");
    assertThat(territory("Belarus").getUnitCollection().contains(belarus.unita().get(0))).isTrue();
    assertThat(belarus.perche()).isEqualTo("ruolo ritarda nel piano 1");
    assertThat(belarus.testo()).startsWith("Ritardo: blocco_terrestre in Belarus");
    assertThat(result.tenute()).containsAll(belarus.unita());
    assertThat(blocks).hasSizeLessThanOrEqualTo(3);
  }

  @Test
  void noToolForANationWithoutDelayRoleNorWithPlanN() {
    staff("Axis", "A", Contromosse.OFFENSIVA, "1", 0.6);
    nonCombat(germans, 3);
    assertThat(
            PianoAttivo.of(data, germans).orElseThrow().tattica(Tattiche.RITIRATA, (String) null))
        .isEmpty();

    staff("Axis", "N", Contromosse.RALLENTAMENTO, "1", 0.6);
    assertThat(
            PianoAttivo.of(data, germans)
                .orElseThrow()
                .tattica(Tattiche.BLOCCO_TERRESTRE, (String) null))
        .isEmpty();
  }

  // -------------------------------------------------------------------------- fighting retreat

  @Test
  void theMainStackFallsBackTowardsTheNearestKeyTerritoryWhenTheEnemyWouldWin() {
    setUpEastFront();
    clearLand("Belarus");
    // the largest Russian stack of the East
    add("Belarus", "infantry", 12, russians);
    add("Belarus", "artillery", 6, russians);
    add("Eastern Poland", "armour", 20, germans);
    add("Eastern Poland", "infantry", 20, germans);
    add("Eastern Poland", "artillery", 10, germans);
    staff("Allies", "1", Contromosse.OFFENSIVA, "A", 0.6);
    nonCombat(russians, 3);

    final StrumentiRitardo.Esito result = play(russians);

    final Optional<StrumentiRitardo.Azione> retreat =
        result.azioni().stream().filter(a -> a.strumento().equals(Tattiche.RITIRATA)).findFirst();
    assertThat(retreat).isPresent();
    assertThat(retreat.get().territorio()).isEqualTo("Belarus");
    assertThat(retreat.get().dettaglio()).contains("verso Novgorod");
    assertThat(territory("Novgorod").getUnitCollection().containsAll(retreat.get().unita()))
        .isTrue();
  }

  @Test
  void onlyIsolatedEnemyStacksAreCounterAttacked() {
    setUpEastFront();
    staff("Allies", "1", Contromosse.OFFENSIVA, "A", 0.6);
    data.getSequence().setRoundAndStep(3, "Combat Move", russians);
    final ProData proData = new ProData(false, new ProPlusTuning());
    proData.initializeSimulation(null, data, russians);
    final Territory poland = territory("Eastern Poland");

    // isolated: no counter-attack possible, win above the desired
    assertThat(proData.getTuning().dropsLandAttack(proData, worth(poland, 95, Double.NaN)))
        .isEmpty();
    // too weak
    assertThat(proData.getTuning().dropsLandAttack(proData, worth(poland, 70, Double.NaN)))
        .contains(ProPlusTuning.DROP_NOT_ISOLATED);
    // the enemy takes it back
    assertThat(proData.getTuning().dropsLandAttack(proData, worth(poland, 95, 80)))
        .contains(ProPlusTuning.DROP_NOT_ISOLATED);
    // the British have no tool on: Hard's rule
    data.getSequence().setRoundAndStep(3, "Combat Move", british);
    final ProData uk = new ProData(false, new ProPlusTuning());
    uk.initializeSimulation(null, data, british);
    assertThat(uk.getTuning().dropsLandAttack(uk, worth(poland, 70, 80))).isEmpty();
    // Hard: never
    final ProData hard = new ProData();
    hard.initializeSimulation(null, data, russians);
    assertThat(hard.getTuning().dropsLandAttack(hard, worth(poland, 10, 99))).isEmpty();
  }

  // ----------------------------------------------------------------------------- naval blocks

  @Test
  void aDestroyerGoesToThePassageZoneOfTheEnemyPlanRead() {
    for (final String z : List.of("109 Sea Zone", "110 Sea Zone", "111 Sea Zone", "112 Sea Zone")) {
      clearSea(z);
    }
    final Unit destroyer = add("109 Sea Zone", "destroyer", 1, british).get(0);
    // posture RALLENTAMENTO: every Allied nation, Sea Lion read at 70%
    staff("Allies", "1", Contromosse.RALLENTAMENTO, "B", 0.7);
    nonCombat(british, 3);

    final StrumentiRitardo.Esito result = play(british);

    final List<StrumentiRitardo.Azione> naval =
        result.azioni().stream().filter(a -> a.strumento().equals(Tattiche.BLOCCO_NAVALE)).toList();
    assertThat(naval).hasSize(1);
    assertThat(naval.get(0).territorio()).isEqualTo("111 Sea Zone");
    assertThat(naval.get(0).unita()).containsExactly(destroyer);
    assertThat(naval.get(0).perche()).isEqualTo("postura RALLENTAMENTO");
    assertThat(territory("111 Sea Zone").getUnitCollection().contains(destroyer)).isTrue();
  }

  @Test
  void noNavalBlockBelowTheReadingThreshold() {
    clearSea("109 Sea Zone");
    add("109 Sea Zone", "destroyer", 1, british);
    staff("Allies", "1", Contromosse.RALLENTAMENTO, "B", 0.45);
    nonCombat(british, 3);

    final StrumentiRitardo.Esito result = play(british);

    assertThat(result.conta(Tattiche.BLOCCO_NAVALE)).isZero();
    assertThat(result.note()).anyMatch(n -> n.startsWith("blocco navale: nessun piano nemico"));
  }

  // ---------------------------------------------------------------------------- transport hunt

  @Test
  void loadedEnemyTransportsInReachAreCounterObjectivesWithTheFixedWeight() {
    clearSea("112 Sea Zone");
    clearSea("111 Sea Zone");
    final Unit transport = add("112 Sea Zone", "transport", 1, germans).get(0);
    final List<Unit> cargo = add("112 Sea Zone", "infantry", 2, germans);
    data.performChange(ChangeFactory.addUnits(territory("112 Sea Zone"), List.of()));
    cargo.forEach(
        u -> data.performChange(ChangeFactory.unitPropertyChange(u, transport, "transportedBy")));
    add("111 Sea Zone", "destroyer", 1, british);
    staff("Allies", "1", Contromosse.RALLENTAMENTO, "B", 0.7);
    data.getSequence().setRoundAndStep(3, "Purchase", british);

    final PianoAttivo plan = PianoAttivo.of(data, british).orElseThrow();

    assertThat(plan.getCaccia()).extracting(PianoAttivo.Caccia::zona).contains("112 Sea Zone");
    assertThat(plan.peso(territory("112 Sea Zone"))).isEqualTo(15);
    assertThat(plan.isObiettivo(territory("112 Sea Zone"))).isTrue();
    // the Russians (role "ritarda") have no ship or plane near it
    data.getSequence().setRoundAndStep(3, "Purchase", russians);
    assertThat(PianoAttivo.of(data, russians).orElseThrow().getCaccia())
        .extracting(PianoAttivo.Caccia::zona)
        .doesNotContain("112 Sea Zone");
  }

  @Test
  void theRecordCountsTheToolUnitsLostSinceTheLastTurn() {
    setUpEastFront();
    staff("Allies", "1", Contromosse.OFFENSIVA, "A", 0.6);
    nonCombat(russians, 3);
    final StrumentiRitardo.Esito result = play(russians);
    assertThat(result.conta(Tattiche.BLOCCO_TERRESTRE)).isPositive();
    StrumentiRitardo.registra(data, russians, result, List.of());
    // the block is destroyed
    final Unit block =
        result.azioni().stream()
            .filter(a -> a.strumento().equals(Tattiche.BLOCCO_TERRESTRE))
            .findFirst()
            .orElseThrow()
            .unita()
            .get(0);
    data.performChange(ChangeFactory.removeUnits(territory("Belarus"), List.of(block)));

    final StrumentiRitardo.Perdite lost =
        StrumentiRitardo.registra(
            data, russians, new StrumentiRitardo.Esito(Set.of(), List.of(), List.of()), List.of());

    assertThat(lost.blocchi()).isEqualTo(1);
    assertThat(StrumentiRitardo.totale(result, List.of(), lost))
        .contains("perse negli strumenti dal turno prima: 1 (blocchi 1");
  }

  // --------------------------------------------------------------------------------- helpers

  /**
   * Eastern Poland and the Baltic States German with a German tank in Eastern Poland; Belarus
   * Russian and empty; Russian infantry in Smolensk.
   */
  private void setUpEastFront() {
    ownedBy(territory("Eastern Poland"), germans);
    ownedBy(territory("Baltic States"), germans);
    for (final String t : List.of("Eastern Poland", "Baltic States", "Belarus", "Smolensk")) {
      clearLand(t);
    }
    for (final String t : List.of("Belarus", "Smolensk", "Novgorod", "Archangel", "Russia")) {
      ownedBy(territory(t), russians);
    }
    add("Eastern Poland", "armour", 1, germans);
    add("Smolensk", "infantry", 15, russians);
  }

  /** The general staff's state of the round: posture, enemy plan read, switches by the decider. */
  private void staff(
      final String alliance,
      final String planId,
      final String posture,
      final String enemyPlan,
      final double probability) {
    if (!StatoMaggiore.getState(data.getProperties(), alliance)
        .orElseThrow()
        .piano()
        .equals(planId)) {
      StatoMaggiore.cambia(data, alliance, planId, 1, "test");
    }
    final Strategia.Piano plan = strategia.alleanza(alliance).orElseThrow().piano(planId).get();
    final JsonObject s = new JsonObject();
    s.addProperty("piano", planId);
    s.addProperty("postura", posture);
    final JsonObject reading = new JsonObject();
    reading.addProperty("piu_probabile", enemyPlan);
    reading.addProperty("probabilita", probability);
    s.add("lettura", reading);
    s.add("contro_obiettivi", new JsonArray());
    s.add(Tattiche.KEY, Tattiche.perRuoloEPostura(data, alliance, plan, posture));
    final JsonObject entry = new JsonObject();
    entry.addProperty("round", 3);
    entry.addProperty("piano", planId);
    entry.add("stato_strategico", s);
    entry.add(
        Valutazione.KEY_DIRETTIVE, DecisoreRegole.direttive(strategia, alliance, plan, 3, s, null));
    StatoMaggiore.aggiungiStorico(data, alliance, entry);
  }

  private StrumentiRitardo.Esito play(final GamePlayer player) {
    final ProData proData = new ProData(false, new ProPlusTuning());
    final TestAi ai = new TestAi(proData, player.getName());
    proData.initializeSimulation(ai, data, player);
    ai.prepareData(data);
    final MoveDelegate moveDelegate = (MoveDelegate) data.getMoveDelegate();
    moveDelegate.setDelegateBridgeAndPlayer(newDelegateBridge(player));
    return StrumentiRitardo.esegui(
        proData, ai.getCalc(), moveDelegate, PianoAttivo.of(data, player).orElseThrow(), Set.of());
  }

  private static ProAttackWorth worth(final Territory t, final double win, final double counter) {
    return new ProAttackWorth(t, win, true, false, false, true, counter, 2, 3, 0, 5, false);
  }

  private void nonCombat(final GamePlayer player, final int round) {
    data.getSequence().setRoundAndStep(round, "Non Combat Move", player);
  }

  private List<Unit> add(
      final String territory, final String type, final int count, final GamePlayer owner) {
    final List<Unit> units = data.getUnitTypeList().getUnitTypeOrThrow(type).create(count, owner);
    data.performChange(ChangeFactory.addUnits(territory(territory), units));
    return units;
  }

  private void clearSea(final String zone) {
    final Territory t = territory(zone);
    if (!t.getUnits().isEmpty()) {
      data.performChange(ChangeFactory.removeUnits(t, List.copyOf(t.getUnits())));
    }
  }

  private void clearLand(final String land) {
    final Territory t = territory(land);
    final List<Unit> units = t.getMatches(Matches.unitIsNotInfrastructure());
    if (!units.isEmpty()) {
      data.performChange(ChangeFactory.removeUnits(t, units));
    }
  }

  private void ownedBy(final Territory t, final GamePlayer owner) {
    data.performChange(ChangeFactory.changeOwner(t, owner));
  }

  private void atWar(final GamePlayer a, final GamePlayer b) {
    if (data.getRelationshipTracker().isAtWar(a, b)) {
      return;
    }
    data.performChange(
        ChangeFactory.relationshipChange(
            a,
            b,
            data.getRelationshipTracker().getRelationshipType(a, b),
            data.getRelationshipTypeList().getDefaultWarRelationship()));
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  /** A Pro+ AI with seeded battle dice. */
  private static final class TestAi extends AbstractProAi {
    private final SeededBattleCalculator calculator;

    TestAi(final ProData proData, final String name) {
      this(proData, name, new SeededBattleCalculator(SEED));
    }

    private TestAi(
        final ProData proData, final String name, final SeededBattleCalculator calculator) {
      super(name, calculator, proData, ProPlusAi.PLAYER_LABEL);
      this.calculator = calculator;
    }

    @Override
    protected void prepareData(final GameData data) {
      calculator.setGameData(data).join();
    }
  }
}
