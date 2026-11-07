package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
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
import games.strategy.triplea.ai.proplus.OperazioniAnfibie;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step OPERAZIONI ANFIBIE: the amphibious operations of the plans (Pro+ only). Global 1940 renamed
 * as Europe 1940 (same European names), real move delegate, board set up per test.
 */
class AmphibiousOperationsTest extends AbstractClientSettingTestCase {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private static final long SEED = 20261107L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer british = player("British");
  private final GamePlayer germans = player("Germans");
  private final GamePlayer italians = player("Italians");

  @BeforeEach
  void setUp() {
    data.setGameName(EUROPE);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "D");
  }

  // ----------------------------------------------------------------------------- the plan

  @Test
  void theTargetsOfAnOperationAreObjectivesOfItsNationsOnly() {
    data.getSequence().setRoundAndStep(3, "Combat Move", player("Americans"));
    final PianoAttivo americans = PianoAttivo.of(data, player("Americans")).orElseThrow();

    assertThat(americans.getOperazioni()).extracting(o -> o.nome()).containsExactly("Overlord USA");
    assertThat(americans.peso(territory("Normandy Bordeaux"))).isEqualTo(15);
    assertThat(americans.peso(territory("Holland Belgium"))).isEqualTo(12, within());
    assertThat(americans.peso(territory("Norway"))).isEqualTo(9.6, within());
    assertThat(americans.isBersaglioOperazione(territory("Normandy Bordeaux"))).isTrue();

    // round 2: the American operation starts in round 3
    data.getSequence().setRoundAndStep(2, "Combat Move", player("Americans"));
    assertThat(PianoAttivo.of(data, player("Americans")).orElseThrow().getOperazioni()).isEmpty();
  }

  @Test
  void aTargetOnlyALandingReachesIsAttackedOnlyAtTheDesiredWin() {
    data.getSequence().setRoundAndStep(3, "Combat Move", player("Americans"));
    final ProData proData = proPlus(player("Americans"));
    final Territory normandy = territory("Normandy Bordeaux");
    ownedBy(normandy, germans);

    assertThat(proData.getAttackMinWinPercentage(normandy))
        .isEqualTo(proData.getAttackWinPercentage(normandy));
    // not a target: the front's minimum, below the desired
    final Territory westernGermany = territory("Western Germany");
    assertThat(proData.getAttackMinWinPercentage(westernGermany))
        .isLessThan(proData.getAttackWinPercentage(westernGermany));
    // Hard: none of it
    final ProData hard = new ProData();
    hard.initializeSimulation(null, data, player("Americans"));
    assertThat(hard.getTuning().heldUnits(hard)).isEmpty();
    assertThat(hard.getAttackMinWinPercentage(normandy)).isEqualTo(65);
  }

  // ----------------------------------------------------------------------- British, plan 1

  @Test
  void troopsBoardAtTheGatheringZoneAndWaitThereForTheLanding() {
    final Unit transport = britishFleetAt110(4);
    nonCombat(british, 2);

    final OperazioniAnfibie.Esito result = play(british);

    assertThat(transport.getTransporting()).isNotEmpty().allMatch(u -> u.isOwnedBy(british));
    assertThat(where(transport)).isEqualTo(territory("110 Sea Zone"));
    assertThat(result.tenute()).contains(transport).containsAll(transport.getTransporting());
    final OperazioniAnfibie.Rapporto r = report(result, "Overlord UK");
    assertThat(r.stato()).isEqualTo(OperazioniAnfibie.Stato.IN_ROTTA);
    assertThat(r.truppeABordo()).isEqualTo(transport.getTransporting().size());
    assertThat(r.mosse()).anyMatch(m -> m.startsWith("imbarco a United Kingdom"));
    assertThat(r.bersaglio()).contains("Normandy Bordeaux");
    assertThat(r.prossimoPasso()).contains("sbarco su Normandy Bordeaux");
  }

  @Test
  void aFleetThatWaitedWithoutLandingPutsTheTroopsAshoreInThePort() {
    final Unit transport = britishFleetAt110(4);
    nonCombat(british, 2);
    play(british);
    final List<Unit> cargo = List.copyOf(transport.getTransporting());

    // the next turn: loaded since the last one, no landing in the combat move
    final OperazioniAnfibie.Esito result = play(british);

    assertThat(transport.getTransporting()).isEmpty();
    assertThat(territory("United Kingdom").getUnits()).containsAll(cargo);
    final OperazioniAnfibie.Rapporto r = report(result, "Overlord UK");
    assertThat(r.stato()).isEqualTo(OperazioniAnfibie.Stato.BLOCCATA);
    assertThat(r.motivo()).contains("bersaglio troppo forte");
  }

  @Test
  void noTroopsBoardWhereTheFleetIsNotSafe() {
    final Unit transport = britishFleetAt110(0);
    add("112 Sea Zone", "battleship", 4, germans);
    nonCombat(british, 2);

    final OperazioniAnfibie.Esito result = play(british);

    assertThat(transport.getTransporting()).isEmpty();
    assertThat(result.tenute()).doesNotContain(transport);
    final OperazioniAnfibie.Rapporto r = report(result, "Overlord UK");
    assertThat(r.stato()).isEqualTo(OperazioniAnfibie.Stato.BLOCCATA);
    assertThat(r.motivo()).contains("nessun imbarco a United Kingdom");
  }

  @Test
  void theNonCombatMoveLeavesTheHeldUnitsWhereTheyAre() {
    final Unit transport = britishFleetAt110(4);
    nonCombat(british, 2);
    final OperazioniAnfibie.Esito result = play(british);

    final ProData proData = new ProData(false, new ProPlusTuning());
    final TestAi ai = new TestAi(proData, "British");
    proData.initializeSimulation(ai, data, british);
    ai.prepareData(data);
    ((ProPlusTuning) proData.getTuning()).hold(data, british, result.tenute());
    final List<MoveDescription> moves = new ArrayList<>();
    final IMoveDelegate recording = mock(IMoveDelegate.class);
    when(recording.performMove(any()))
        .thenAnswer(
            i -> {
              moves.add(i.getArgument(0));
              return Optional.empty();
            });
    new ProNonCombatMoveAi(ai).doNonCombatMove(null, null, recording);

    assertThat(moves).noneMatch(m -> m.getUnits().stream().anyMatch(result.tenute()::contains));
    assertThat(transport.getTransporting()).isNotEmpty();
  }

  @Test
  void theOperationAsksForTransportsWhileTroopsWait() {
    britishFleetAt110(4);
    nonCombat(british, 2);
    final ProData proData = proPlus(british);

    final List<OperazioniAnfibie.Acquisto> asked =
        OperazioniAnfibie.acquisti(proData, PianoAttivo.of(data, british).orElseThrow());

    assertThat(asked)
        .anySatisfy(
            a -> {
              assertThat(a.operazione()).isEqualTo("Overlord UK");
              assertThat(a.categoria()).isEqualTo("trasporti");
              assertThat(a.numero()).isEqualTo(2);
            })
        .noneMatch(a -> a.categoria().equals("cacciatorpediniere"));
    final ProPurchaseOrders orders = proData.getTuning().purchaseOrders(proData).orElseThrow();
    assertThat(orders.label()).contains("operazione Overlord UK: 2 trasporti");
    assertThat(orders.mandatory()).anySatisfy(m -> assertThat(m.category()).isEqualTo("trasporti"));
  }

  // ------------------------------------------------------------------- Axis, plan D

  @Test
  void germanTroopsCrossToAfricaOnItalianTransports() {
    clearAlliedAirAndNavy();
    clearTransports(italians);
    clearSea("97 Sea Zone");
    clearSea("96 Sea Zone");
    final Unit transport = add("97 Sea Zone", "transport", 1, italians).get(0);
    add("97 Sea Zone", "battleship", 4, italians);
    add("96 Sea Zone", "battleship", 4, italians);
    removeLand("Southern Italy", germans);
    final List<Unit> afrika = add("Southern Italy", "infantry", 2, germans);

    // German turn: they board the Italian transport in the port
    nonCombat(germans, 2);
    final OperazioniAnfibie.Rapporto boarding = report(play(germans), "Afrika Korps");
    assertThat(transport.getTransporting()).containsExactlyInAnyOrderElementsOf(afrika);
    assertThat(boarding.mosse()).anyMatch(m -> m.contains("su Italians in 97 Sea Zone"));

    // Italian turn: the transport sails to the gathering zone with them
    nonCombat(italians, 2);
    play(italians);
    assertThat(where(transport)).isEqualTo(territory("96 Sea Zone"));
    assertThat(transport.getTransporting()).containsExactlyInAnyOrderElementsOf(afrika);

    // next German turn: they go ashore in Libya
    nonCombat(germans, 3);
    afrika.forEach(this::newTurn);
    final OperazioniAnfibie.Rapporto landing = report(play(germans), "Afrika Korps");
    assertThat(territory("Libya").getUnits()).containsAll(afrika);
    assertThat(landing.mosse()).anyMatch(m -> m.startsWith("sbarco da trasporto alleato"));
  }

  // ------------------------------------------------------------------------------- helpers

  /** A British transport in 110 Sea Zone with {@code escort} battleships, Normandy German. */
  private Unit britishFleetAt110(final int escort) {
    clearAxisAirAndNavy();
    clearTransports(british);
    clearSea("110 Sea Zone");
    final Unit transport = add("110 Sea Zone", "transport", 1, british).get(0);
    if (escort > 0) {
      add("110 Sea Zone", "battleship", escort, british);
    }
    add("United Kingdom", "infantry", 12, british);
    add("United Kingdom", "armour", 2, british);
    ownedBy(territory("Normandy Bordeaux"), germans);
    return transport;
  }

  private OperazioniAnfibie.Esito play(final GamePlayer player) {
    final ProData proData = new ProData(false, new ProPlusTuning());
    final TestAi ai = new TestAi(proData, player.getName());
    proData.initializeSimulation(ai, data, player);
    ai.prepareData(data);
    final MoveDelegate moveDelegate = (MoveDelegate) data.getMoveDelegate();
    moveDelegate.setDelegateBridgeAndPlayer(newDelegateBridge(player));
    return OperazioniAnfibie.esegui(
        proData, ai.getCalc(), moveDelegate, PianoAttivo.of(data, player).orElseThrow());
  }

  private ProData proPlus(final GamePlayer player) {
    final ProData proData = new ProData(false, new ProPlusTuning());
    proData.initializeSimulation(null, data, player);
    return proData;
  }

  private void nonCombat(final GamePlayer player, final int round) {
    data.getSequence().setRoundAndStep(round, "Non Combat Move", player);
  }

  private static OperazioniAnfibie.Rapporto report(
      final OperazioniAnfibie.Esito result, final String operation) {
    return result.rapporti().stream()
        .filter(r -> r.operazione().equals(operation))
        .findFirst()
        .orElseThrow();
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

  /** No Axis ship or plane anywhere: only what a test adds threatens the fleet. */
  private void clearAxisAirAndNavy() {
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> units =
          t.getMatches(
              Matches.unitIsSea()
                  .or(Matches.unitIsAir())
                  .and(u -> u.isOwnedBy(germans) || u.isOwnedBy(italians)));
      if (!units.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(t, units));
      }
    }
  }

  /** No Allied ship or plane anywhere. */
  private void clearAlliedAirAndNavy() {
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> units =
          t.getMatches(
              Matches.unitIsSea()
                  .or(Matches.unitIsAir())
                  .and(
                      u ->
                          !u.getOwner().isNull()
                              && data.getRelationshipTracker().isAtWar(u.getOwner(), italians)));
      if (!units.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(t, units));
      }
    }
  }

  private void removeLand(final String land, final GamePlayer owner) {
    final Territory t = territory(land);
    final List<Unit> units = t.getMatches(Matches.unitIsOwnedBy(owner));
    if (!units.isEmpty()) {
      data.performChange(ChangeFactory.removeUnits(t, units));
    }
  }

  private void clearTransports(final GamePlayer owner) {
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> transports =
          t.getMatches(
              Matches.unitIsSeaTransportButNotCombatSeaTransport()
                  .and(Matches.unitIsOwnedBy(owner)));
      if (!transports.isEmpty()) {
        final List<Unit> all = new ArrayList<>(transports);
        transports.forEach(tr -> all.addAll(tr.getTransporting()));
        data.performChange(ChangeFactory.removeUnits(t, all));
      }
    }
  }

  private void ownedBy(final Territory t, final GamePlayer owner) {
    data.performChange(ChangeFactory.changeOwner(t, owner));
  }

  /** What the move delegate resets at the start of a turn, for one unit. */
  private void newTurn(final Unit u) {
    data.performChange(
        ChangeFactory.unitPropertyChange(
            u, java.math.BigDecimal.ZERO, Unit.PropertyName.ALREADY_MOVED));
    data.performChange(
        ChangeFactory.unitPropertyChange(u, false, Unit.PropertyName.LOADED_THIS_TURN));
  }

  private Territory where(final Unit u) {
    return data.getMap().getTerritories().stream()
        .filter(t -> t.getUnitCollection().contains(u))
        .findFirst()
        .orElseThrow();
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private static org.assertj.core.data.Offset<Double> within() {
    return org.assertj.core.data.Offset.offset(0.01);
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
