package games.strategy.triplea.ai.proplus;

import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P14: the switch {@code aerei_corretti} (off = Hard's answers), the air base's bonus (the
 * bomber of London reaches the Italian fleet and Malta, as the engine allows once the combat move
 * gives the bonus; nothing with a damaged air base) and the carrier landing (a fighter attacks only
 * with its carrier, the review gives it the place, the engine accepts the attack and the landing).
 * Global 1940, same names as Europe 1940.
 */
class AereiCorrettiTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261123L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
    for (final String n : List.of("Americans", "British", "Russians", "Germans", "Italians")) {
      Stratega.registra(data, player(n));
    }
    for (final String e : List.of("Germans", "Italians")) {
      if (!player("British").isAtWar(player(e))) {
        data.performChange(
            ChangeFactory.relationshipChange(
                player("British"),
                player(e),
                data.getRelationshipTracker().getRelationshipType(player("British"), player(e)),
                data.getRelationshipTypeList().getDefaultWarRelationship()));
      }
    }
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

  @Test
  void offIsHardAndNoNameIsInTheCode() throws Exception {
    assertThat(StrategaInterruttori.AEREI_CORRETTI.predefinito()).isFalse();
    assertThat(StrategaInterruttori.of("aerei_corretti"))
        .contains(StrategaInterruttori.AEREI_CORRETTI);
    final ProData pd = proData(player("British"), new ProPlusTuning());
    final Unit bomber = unit("United Kingdom", "bomber");
    final BigDecimal hard = bomber.getMovementLeft();
    assertThat(AereiCorretti.attivo(data, player("British"))).isFalse();
    assertThat(pd.getTuning().ownAttackAirRange(pd, player("British"), bomber, uk(), hard))
        .isEqualTo(hard);
    assertThat(pd.getTuning().ownAttackCarrierLandings(pd, player("British"))).isEmpty();
    // the options of Pro+ with the switch off are Hard's
    assertThat(options(pd).get(bomber))
        .isEqualTo(options(proData(player("British"), null)).get(bomber));
    // on for the Allies only
    on("Allies");
    assertThat(AereiCorretti.attivo(data, player("British"))).isTrue();
    assertThat(AereiCorretti.attivo(data, player("Germans"))).isFalse();
    final String src =
        Files.readString(
            Path.of("src/main/java/games/strategy/triplea/ai/proplus/AereiCorretti.java"),
            StandardCharsets.UTF_8);
    for (final String word :
        List.of("\"British\"", "\"Malta\"", "\"United Kingdom\"", "Sea Zone\"", "\"fighter\"")) {
      assertThat(src).as(word).doesNotContain(word);
    }
  }

  @Test
  void theBomberOfLondonWithTheAirBaseReachesTheFleetAndMaltaAsTheEngineAllows() {
    on("Allies");
    final Unit bomber = unit("United Kingdom", "bomber");
    final Territory fleet = territory("97 Sea Zone");
    assertThat(fleet.anyUnitsMatch(Matches.enemyUnit(player("British")))).isTrue();
    final ProData hard = proData(player("British"), null);
    final ProData plus = proData(player("British"), new ProPlusTuning());
    final BigDecimal range =
        plus.getTuning()
            .ownAttackAirRange(plus, player("British"), bomber, uk(), bomber.getMovementLeft());
    assertThat(bomber.getMovementLeft()).isEqualTo(new BigDecimal(6));
    assertThat(range).isEqualTo(new BigDecimal(7));
    assertThat(options(hard).getOrDefault(bomber, Set.of())).doesNotContain(fleet);
    assertThat(options(plus).get(bomber)).contains(fleet);
    final Route route =
        data.getMap()
            .getRouteForUnit(
                uk(),
                fleet,
                ProMatches.territoryCanMoveAirUnitsAndNoAa(data, player("British"), true),
                bomber,
                player("British"))
            .orElseThrow();
    assertThat(route.getMovementCost(bomber)).isEqualTo(new BigDecimal(5));
    assertThat(
            data.getMap()
                .getNeighborsByMovementCost(
                    fleet,
                    new BigDecimal(2),
                    ProMatches.territoryCanMoveAirUnitsAndNoAa(data, player("British"), true)))
        .contains(territory("Malta"));

    // the engine: the combat move gives the bonus Pro+ predicted and accepts the attack
    final MoveDelegate del = combatMove("British");
    assertThat(bomber.getMovementLeft()).isEqualTo(range);
    assertThat(del.performMove(new MoveDescription(List.of(bomber), route))).isEmpty();
    del.end();
  }

  @Test
  void aDamagedAirBaseGivesNoBonus() {
    on("Allies");
    final Unit base = unit("United Kingdom", "airfield");
    data.performChange(
        ChangeFactory.bombingUnitDamage(
            new org.triplea.java.collections.IntegerMap<>(java.util.Map.of(base, 3)),
            List.of(uk())));
    assertThat(Matches.unitIsDisabled().test(base)).isTrue();
    final ProData plus = proData(player("British"), new ProPlusTuning());
    final Unit bomber = unit("United Kingdom", "bomber");
    assertThat(
            plus.getTuning()
                .ownAttackAirRange(plus, player("British"), bomber, uk(), bomber.getMovementLeft()))
        .isEqualTo(new BigDecimal(6));
    assertThat(options(plus).getOrDefault(bomber, Set.of()))
        .doesNotContain(territory("97 Sea Zone"));
    // the engine agrees
    final MoveDelegate del = combatMove("British");
    assertThat(bomber.getMovementLeft()).isEqualTo(new BigDecimal(6));
    del.end();
  }

  @Test
  void aFighterAttacksWithItsCarrierAndLandsOnIt() {
    on("Allies");
    final GamePlayer british = player("British");
    final Predicate<Territory> canFly =
        ProMatches.territoryCanMoveAirUnitsAndNoAa(data, british, true);
    final Predicate<Territory> canLand =
        ProMatches.territoryCanLandAirUnits(british, true, List.of(), List.of());
    // a target far from any land (none within 3) next to an empty zone
    Territory target = null;
    Territory zone = null;
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()
          || !t.getUnitCollection().isEmpty()
          || data.getMap().getNeighborsByMovementCost(t, new BigDecimal(3), canFly).stream()
              .anyMatch(canLand)) {
        continue;
      }
      final Optional<Territory> z =
          data.getMap().getNeighbors(t, Matches.territoryIsWater()).stream()
              .filter(n -> n.getUnitCollection().isEmpty())
              .findFirst();
      if (z.isPresent()) {
        target = t;
        zone = z.get();
        break;
      }
    }
    assertThat(target).isNotNull();
    data.performChange(
        ChangeFactory.addUnits(target, unitType("destroyer").create(1, player("Germans"))));
    data.performChange(ChangeFactory.addUnits(zone, unitType("carrier").create(1, british)));
    data.performChange(ChangeFactory.addUnits(zone, unitType("fighter").create(1, british)));
    final Unit fighter = zone.getMatches(Matches.unitIsAir()).get(0);

    final ProData hard = proData(british, null);
    assertThat(options(hard).getOrDefault(fighter, Set.of())).doesNotContain(target);
    final ProPlusTuning tuning = new ProPlusTuning();
    final ProData plus = proData(british, tuning);
    assertThat(plus.getTuning().ownAttackCarrierLandings(plus, british)).contains(zone);
    assertThat(options(plus).get(fighter)).contains(target);

    // the review gives the fighter its carrier's place
    final Map<Territory, ProTerritory> attacks = new HashMap<>();
    final ProTerritory patd = new ProTerritory(target, plus);
    patd.addUnit(fighter);
    attacks.put(target, patd);
    tuning.reviewAttacks(plus, calc(), attacks);
    assertThat(patd.getUnits()).containsExactly(fighter);

    // the engine accepts the attack (a carrier can catch it)
    final MoveDelegate del = combatMove("British");
    final Route out = new Route(zone, target);
    assertThat(del.performMove(new MoveDescription(List.of(fighter), out))).isEmpty();
    del.end();

    // the battle won: the fighter lands on its carrier in the non-combat move
    data.performChange(
        ChangeFactory.removeUnits(target, target.getMatches(Matches.enemyUnit(british))));
    final IDelegateBridge bridge = newDelegateBridge(british);
    advanceToStep(bridge, "britishNonCombatMove");
    del.setDelegateBridgeAndPlayer(bridge);
    del.start();
    final Set<Unit> held = AereiCorretti.atterra(tuning.p14(), data, british, del);
    assertThat(zone.getUnits()).contains(fighter);
    assertThat(held).contains(fighter);
    assertThat(del.getTerritoriesWhereAirCantLand(british)).isEmpty();
    del.end();
  }

  @Test
  void aFullCarrierIsNoLandingAndTheFighterLeavesTheAttack() {
    on("Allies");
    final GamePlayer british = player("British");
    final ProPlusTuning tuning = new ProPlusTuning();
    // a carrier in a zone with an enemy unit is never a landing
    final Territory fleet = territory("97 Sea Zone");
    data.performChange(ChangeFactory.addUnits(fleet, unitType("carrier").create(1, british)));
    final ProData plus = proData(british, tuning);
    assertThat(plus.getTuning().ownAttackCarrierLandings(plus, british)).doesNotContain(fleet);
    // no place: the fighter leaves the attack
    final Territory target = territory("97 Sea Zone");
    final Unit fighter = unit("United Kingdom", "fighter");
    final Map<Territory, ProTerritory> attacks = new HashMap<>();
    final ProTerritory patd = new ProTerritory(target, plus);
    patd.addUnit(fighter);
    attacks.put(target, patd);
    tuning.reviewAttacks(plus, calc(), attacks);
    // from London the fighter (range 5) can't come back nor reach land: out of the attack
    assertThat(patd.getUnits()).doesNotContain(fighter);
  }

  // ================================================================================== helpers

  private void on(final String alliance) {
    StrategaInterruttori.accendi(data.getProperties(), alliance, "aerei_corretti");
  }

  private Map<Unit, Set<Territory>> options(final ProData pd) {
    final ProTerritoryManager tm = new ProTerritoryManager(calc(), pd);
    tm.populateAttackOptions();
    return tm.getAttackOptions().getUnitMoveMap();
  }

  private MoveDelegate combatMove(final String nation) {
    final IDelegateBridge bridge = newDelegateBridge(player(nation));
    advanceToStep(bridge, nation.toLowerCase(java.util.Locale.ROOT) + "CombatMove");
    final MoveDelegate del = (MoveDelegate) data.getMoveDelegate();
    del.setDelegateBridgeAndPlayer(bridge);
    del.start();
    return del;
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrNull(name);
  }

  private Territory uk() {
    return territory("United Kingdom");
  }

  private games.strategy.engine.data.UnitType unitType(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  private Unit unit(final String territory, final String type) {
    return territory(territory).getMatches(u -> u.getType().getName().equals(type)).get(0);
  }

  private ProData proData(final GamePlayer player, final ProPlusTuning plus) {
    data.getSequence().setRoundAndStep(1, "Purchase", player);
    final ProData proData = new ProData(false, plus == null ? ProTuning.DEFAULT : plus);
    if (plus != null) {
      plus.attach(proData);
    }
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
