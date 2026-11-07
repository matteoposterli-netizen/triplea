package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 3b-bis: minimum garrison of the capital (Pro+ only). Global 1940, Germans round 1: every
 * German land unit of Germany and Western Germany is planned to Western Germany, and a British army
 * can attack Germany.
 */
class CapitalGuardTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261107L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final GamePlayer british = data.getPlayerList().getPlayerId("British");
  private final Territory germany = data.getMap().getTerritoryOrThrow("Germany");
  private final Territory westernGermany = data.getMap().getTerritoryOrThrow("Western Germany");
  private ProData proData;
  private ProOddsCalculator calc;
  private ProTerritory cap;
  private ProTerritory west;
  private Map<Territory, ProTerritory> moveMap;
  private List<Unit> leavingCapital;

  @BeforeEach
  void setUp() {
    data.getSequence().setRoundAndStep(1, "Non Combat Move", germans);
    proData = new ProData(false, new ProPlusTuning());
    proData.initializeSimulation(null, data, germans);
    final SeededBattleCalculator battleCalculator = new SeededBattleCalculator(SEED);
    battleCalculator.setGameData(data).join();
    calc = new ProOddsCalculator(battleCalculator);
    cap = new ProTerritory(germany, proData);
    west = new ProTerritory(westernGermany, proData);
    leavingCapital = landUnits(germany);
    west.addUnits(leavingCapital);
    west.addUnits(landUnits(westernGermany));
    cap.getMaxUnits().addAll(leavingCapital);
    cap.getMaxUnits().addAll(landUnits(westernGermany));
    moveMap = new LinkedHashMap<>();
    moveMap.put(germany, cap);
    moveMap.put(westernGermany, west);
  }

  @Test
  void onlyProPlusHasTheGuard() {
    assertThat(ProTuning.DEFAULT.capitalGuardThreshold(proData)).isEmpty();
    assertThat(proData.getTuning().capitalGuardThreshold(proData)).hasValue(25);
  }

  @Test
  void unitsLeavingTheCapitalStayUntilTheRiskIsBelowTheThreshold() {
    cap.setMaxEnemyUnits(british("armour", 4));

    final ProCapitalGuard.Result result = guard();

    assertThat(result.riskBefore()).isGreaterThan(25);
    assertThat(result.riskAfter()).isLessThanOrEqualTo(25);
    // air units of Germany that no move takes away count too
    assertThat(result.garrisonAfter() - result.garrisonBefore())
        .isEqualTo(cap.getUnits().size())
        .isPositive();
    // the units that were leaving the capital come first
    assertThat(result.kept()).isNotEmpty();
    assertThat(leavingCapital).containsAll(cap.getUnits());
    assertThat(west.getUnits()).doesNotContainAnyElementsOf(cap.getUnits());
    assertThat(result.hopeless()).isFalse();
  }

  @Test
  void unitsAreBroughtBackWhenThoseLeavingTheCapitalAreNotEnough() {
    cap.setMaxEnemyUnits(british("armour", 20));

    final ProCapitalGuard.Result result = guard();

    assertThat(result.riskAfter()).isLessThan(result.riskBefore());
    assertThat(result.kept().values().stream().mapToInt(Integer::intValue).sum())
        .isEqualTo(leavingCapital.size());
    assertThat(result.broughtBack()).containsOnlyKeys("Western Germany");
  }

  @Test
  void aCapitalThatFallsAnywayKeepsTheMovesOfTheNonCombatMove() {
    cap.setMaxEnemyUnits(british("armour", 80));
    final List<Unit> westBefore = new ArrayList<>(west.getUnits());

    final ProCapitalGuard.Result result = guard();

    assertThat(result.hopeless()).isTrue();
    assertThat(result.riskAfter()).isEqualTo(result.riskBefore());
    assertThat(cap.getUnits()).isEmpty();
    assertThat(west.getUnits()).containsExactlyInAnyOrderElementsOf(westBefore);
    assertThat(result.kept()).isEmpty();
    assertThat(result.broughtBack()).isEmpty();
  }

  @Test
  void noThreatNoChange() {
    final ProCapitalGuard.Result result = guard();

    assertThat(result.riskBefore()).isZero();
    assertThat(cap.getUnits()).isEmpty();
    assertThat(result.kept()).isEmpty();
  }

  @Test
  void theDiaryLineSaysRiskGarrisonAndActions() {
    cap.setMaxEnemyUnits(british("armour", 4));

    final String line = DiaryRecorder.capitalGuardText(guard());

    assertThat(line)
        .startsWith("  Capitale Germany: rischio stimato ")
        .contains("% -> ")
        .contains("(soglia 25%), guarnigione ")
        .contains("trattenute: ");
  }

  private ProCapitalGuard.Result guard() {
    final List<ProCapitalGuard.Result> results =
        new ProCapitalGuard(proData, calc).apply(moveMap, ProPlusTuning.CAPITAL_GUARD_THRESHOLD);
    assertThat(results).hasSize(1);
    return results.get(0);
  }

  private List<Unit> landUnits(final Territory t) {
    return t.getMatches(
        Matches.unitIsOwnedBy(germans)
            .and(Matches.unitIsLand())
            .and(Matches.unitIsNotInfrastructure()));
  }

  private List<Unit> british(final String type, final int count) {
    return data.getUnitTypeList().getUnitTypeOrThrow(type).create(count, british);
  }
}
