package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Step 3c: Pro+ defends only against a real threat (enemies at war, war the plan declares by the
 * next turn, war the map lets them declare; units that can reach). Global 1940, renamed as Europe
 * 1940 where a plan is needed (same European names).
 */
class RealThreatTest extends AbstractClientSettingTestCase {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private static final long SEED = 20261107L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = player("Germans");
  private final GamePlayer russians = player("Russians");
  private final GamePlayer british = player("British");
  private final GamePlayer americans = player("Americans");

  @Test
  void theBritishAreNoThreatToTheUsaBeforeItEntersTheWar() {
    data.getSequence().setRoundAndStep(1, "Purchase", americans);

    final ProData proPlus = proData(new ProPlusTuning(), americans);
    final Map<GamePlayer, String> real =
        proPlus.getTuning().realEnemies(proPlus, americans).orElseThrow();
    assertThat(real).doesNotContainKeys(british, russians);
    // Germany may declare war on the USA whenever it wants: it counts
    assertThat(real).containsKey(germans);
    assertThat(real.get(germans)).contains("regole della mappa");

    // the cause of the step 3b-ter defense: Hard moves the British in Canada as enemies
    assertThat(owners(enemyAttackers(ProTuning.DEFAULT, americans, "Central United States")))
        .contains("British");
    assertThat(owners(enemyAttackers(new ProPlusTuning(), americans, "Central United States")))
        .doesNotContain("British");
    assertThat(owners(enemyAttackers(new ProPlusTuning(), british, "Quebec")))
        .doesNotContain("Americans");
  }

  @Test
  void washingtonAndCentralUnitedStatesGetNoDefenseInRounds1To3() {
    for (int round = 1; round <= 3; round++) {
      data.getSequence().setRoundAndStep(round, "Purchase", americans);
      final Purchase pp = purchase(new ProPlusTuning(), americans);

      assertThat(pp.defendLand())
          .as("round %d", round)
          .doesNotContainKeys("Eastern United States", "Central United States");
      assertThat(pp.saved()).as("round %d", round).isNotNegative();
    }
  }

  @Test
  void noChannelGarrisonWithoutBritishTransportsInRange() {
    removeTransportsOf(british, player("Americans"), player("French"), player("ANZAC"));
    final Garrisons g = barbarossaRound3NonCombat();

    assertThat(g.results()).isEmpty();
    // Normandy Bordeaux, Holland Belgium, Denmark: own, none needed
    assertThat(g.notNeeded()).isEqualTo(3);
  }

  @Test
  void aChannelGarrisonWithBritishTransportsInRange() {
    // the British transport of 109 Sea Zone can load in the United Kingdom and land
    final Garrisons g = barbarossaRound3NonCombat();

    final Map<String, ProCapitalGuard.GarrisonResult> byName =
        g.results().stream()
            .collect(Collectors.toMap(r -> r.territory().getName(), r -> r, (a, b) -> a));
    // Denmark: 109 -> 111 -> 112 Sea Zone (Normandy and Holland: 110 Sea Zone has German ships,
    // which the Pro AI's model of enemy landings doesn't sail through)
    assertThat(byName).containsKey("Denmark");
    final ProCapitalGuard.GarrisonResult denmark = byName.get("Denmark");
    assertThat(denmark.threat().units()).containsOnlyKeys("British");
    assertThat(denmark.threat().why().get("British")).isEqualTo("in guerra");
    assertThat(denmark.after()).isEqualTo(2);
    assertThat(g.notNeeded()).isEqualTo(3 - g.results().size());
  }

  @Test
  void theGermanCapitalIsAtRiskTheTurnBeforeTheWarOnTheUssrThePlanDeclares() {
    data.setGameName(EUROPE);
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "A");
    // a Soviet army next to an empty Berlin, the two still at peace
    final Territory poland = territory("Poland");
    data.performChange(
        ChangeFactory.removeUnits(poland, new ArrayList<>(poland.getUnitCollection())));
    data.performChange(ChangeFactory.changeOwner(poland, russians));
    data.performChange(
        ChangeFactory.addUnits(
            poland, data.getUnitTypeList().getUnitTypeOrThrow("armour").create(10, russians)));
    final Territory germany = territory("Germany");
    data.performChange(
        ChangeFactory.removeUnits(germany, germany.getMatches(Matches.unitIsNotInfrastructure())));

    // round 1: the plan declares war from round 3, the USSR may not yet: no threat
    data.getSequence().setRoundAndStep(1, "Purchase", germans);
    final ProData round1 = proData(new ProPlusTuning(), germans);
    assertThat(round1.getTuning().realEnemies(round1, germans).orElseThrow())
        .doesNotContainKey(russians);
    assertThat(owners(enemyAttackers(new ProPlusTuning(), germans, "Germany")))
        .doesNotContain("Russians");

    // round 2: the plan declares war at the start of the next turn: the Soviets count
    data.getSequence().setRoundAndStep(2, "Purchase", germans);
    final ProData round2 = proData(new ProPlusTuning(), germans);
    assertThat(round2.getTuning().realEnemies(round2, germans).orElseThrow())
        .containsEntry(russians, "guerra prevista dal piano dal round 3");
    assertThat(owners(enemyAttackers(new ProPlusTuning(), germans, "Germany")))
        .contains("Russians");
  }

  // ------------------------------------------------------------------------------- helpers

  private record Purchase(Map<String, Integer> defendLand, int saved) {}

  private record Garrisons(List<ProCapitalGuard.GarrisonResult> results, int notNeeded) {}

  private Garrisons barbarossaRound3NonCombat() {
    data.setGameName(EUROPE);
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "A");
    final Territory normandy = territory("Normandy Bordeaux");
    data.performChange(
        ChangeFactory.removeUnits(normandy, new ArrayList<>(normandy.getUnitCollection())));
    data.performChange(ChangeFactory.changeOwner(normandy, germans));
    data.getSequence().setRoundAndStep(3, "Non Combat Move", germans);
    final ProData proData = new ProData(false, new ProPlusTuning());
    final TestAi ai = new TestAi("Germans", proData, new SeededBattleCalculator(SEED));
    final GarrisonCapture capture = new GarrisonCapture();
    proData.setRecorder(capture);
    proData.initializeSimulation(ai, data, germans);
    ai.prepareData(data);
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any())).thenReturn(Optional.empty());
    new ProNonCombatMoveAi(ai).doNonCombatMove(null, null, moveDel);
    return new Garrisons(capture.results, capture.notNeeded);
  }

  private Purchase purchase(final ProTuning tuning, final GamePlayer player) {
    final ProData proData = new ProData(false, tuning);
    final TestAi ai = new TestAi(player.getName(), proData, new SeededBattleCalculator(SEED));
    final PurchaseCapture capture = new PurchaseCapture();
    proData.setRecorder(capture);
    proData.initializeSimulation(ai, data, player);
    ai.prepareData(data);
    final IPurchaseDelegate purchaseDel = mock(IPurchaseDelegate.class);
    when(purchaseDel.purchase(any()))
        .thenAnswer(
            invocation -> {
              final IntegerMap<ProductionRule> rules = invocation.getArgument(0);
              rules.keySet().forEach(rules::getInt);
              return null;
            });
    new ProPurchaseAi(ai).purchase(purchaseDel, data);
    return new Purchase(capture.defendLand, capture.saved);
  }

  private List<Unit> enemyAttackers(
      final ProTuning tuning, final GamePlayer player, final String territory) {
    final ProData proData = proData(tuning, player);
    final ProTerritoryManager manager =
        new ProTerritoryManager(new ProOddsCalculator(new SeededBattleCalculator(SEED)), proData);
    manager.populateEnemyAttackOptions(List.of(), List.of(territory(territory)));
    final ProTerritory max = manager.getEnemyAttackOptions().getMax(territory(territory));
    if (max == null) {
      return List.of();
    }
    final List<Unit> units = new ArrayList<>(max.getMaxUnits());
    units.addAll(max.getMaxAmphibUnits());
    return units;
  }

  private ProData proData(final ProTuning tuning, final GamePlayer player) {
    final ProData proData = new ProData(false, tuning);
    proData.initializeSimulation(null, data, player);
    return proData;
  }

  private static Set<String> owners(final List<Unit> units) {
    return units.stream().map(u -> u.getOwner().getName()).collect(Collectors.toSet());
  }

  private void removeTransportsOf(final GamePlayer... players) {
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> transports =
          t.getMatches(
              Matches.unitIsSeaTransport().and(Matches.unitIsOwnedByAnyOf(List.of(players))));
      if (!transports.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(t, transports));
      }
    }
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  /** Units the defense step bought per place territory, and the PUs saved. */
  private static final class PurchaseCapture implements ProDecisionRecorder {
    private Map<String, Integer> defendLand = Map.of();
    private int saved = -1;

    @Override
    public void purchaseStep(
        final ProData proData,
        final String step,
        final Map<Territory, ProPurchaseTerritory> territories,
        final Map<Territory, ProPurchaseTerritory> factories) {
      if ("defend_land".equals(step)) {
        final Map<String, Integer> units = new TreeMap<>();
        territories
            .values()
            .forEach(
                ppt ->
                    ppt.getCanPlaceTerritories()
                        .forEach(
                            place -> {
                              if (!place.getPlaceUnits().isEmpty()) {
                                units.merge(
                                    place.getTerritory().getName(),
                                    place.getPlaceUnits().size(),
                                    Integer::sum);
                              }
                            }));
        defendLand = units;
      }
    }

    @Override
    public void realThreatDefense(
        final ProData proData,
        final List<ProThreat> defended,
        final int pusSaved,
        final int territoriesSaved) {
      saved = pusSaved;
    }
  }

  private static final class GarrisonCapture implements ProDecisionRecorder {
    private List<ProCapitalGuard.GarrisonResult> results = List.of();
    private int notNeeded;

    @Override
    public void minimumGarrisons(
        final ProData proData, final List<ProCapitalGuard.GarrisonResult> results) {
      this.results = results;
    }

    @Override
    public void garrisonsNotNeeded(final ProData proData, final int count) {
      notNeeded = count;
    }
  }

  /** A Pro AI with seeded battle dice; the dice restart on every {@link #prepareData}. */
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
