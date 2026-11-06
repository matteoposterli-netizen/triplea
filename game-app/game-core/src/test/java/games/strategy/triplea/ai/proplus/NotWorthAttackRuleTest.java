package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProAttackWorth;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.Test;

/**
 * Pro+ rules for attacks "Hard (AI)" drops in the combat move's last check because their value is
 * negative with the units assigned ({@link ProPlusTuning#keepsAttackNotWorthIt}).
 */
class NotWorthAttackRuleTest extends AbstractClientSettingTestCase {
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final Territory normandy = data.getMap().getTerritoryOrThrow("Normandy Bordeaux");
  private final Territory egypt = data.getMap().getTerritoryOrThrow("Egypt");
  private final Territory seaZone = data.getMap().getTerritoryOrThrow("110 Sea Zone");

  private ProData proPlus(final RiskLevel axisRisk) {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", axisRisk);
    final ProData proData = new ProData(false, new ProPlusTuning());
    proData.initializeSimulation(null, data, germans);
    return proData;
  }

  /** A not holdable attack that wins {@code win}% (no land unit lost, enemy can counter). */
  private static ProAttackWorth worth(
      final Territory t,
      final double win,
      final double tuvSwing,
      final double territoryValue,
      final double counterTuv) {
    final double hardValue =
        1
            + tuvSwing
            + territoryValue * win / 100
            - counterTuv * ProAttackWorth.HARD_COUNTER_ATTACK_WEIGHT;
    return new ProAttackWorth(
        t,
        win,
        true,
        false,
        false,
        false,
        100,
        tuvSwing,
        territoryValue,
        counterTuv,
        hardValue,
        false);
  }

  // Germany round 1 in the sandbox game (Europe 1940): Normandy Bordeaux, Hard value -2.6
  private ProAttackWorth normandyRound1() {
    return worth(normandy, 78, -4.8, 6, 5.4);
  }

  @Test
  void hardNeverKeepsThem() {
    final ProData hard = new ProData();
    hard.initializeSimulation(null, data, germans);

    assertThat(normandyRound1().hardDrops()).isTrue();
    assertThat(ProTuning.DEFAULT.keepsAttackNotWorthIt(hard, normandyRound1())).isEmpty();
  }

  @Test
  void prudenteDropsThemAsHard() {
    final ProData proData = proPlus(RiskLevel.PRUDENTE);

    assertThat(proData.getTuning().keepsAttackNotWorthIt(proData, normandyRound1())).isEmpty();
    assertThat(proData.getTuning().keepsAttackNotWorthIt(proData, worth(egypt, 100, 0, 4, 12)))
        .isEmpty();
  }

  @Test
  void riskRuleKeepsASlightlyNegativeAttackOnlyAtHighRisk() {
    // 1 - 4.8 + 6 x 0.78 = 0.88 before the counter-attack (5.4)
    assertThat(keptBy(RiskLevel.SPERICOLATO, normandyRound1())).isEqualTo("risk"); // -1.28 >= -3
    assertThat(keptBy(RiskLevel.AUDACE, normandyRound1())).isEqualTo("risk"); // -1.55 >= -2
    assertThat(keptBy(RiskLevel.EQUILIBRATO, normandyRound1())).isEqualTo("-"); // -2.09 < -1
    assertThat(keptBy(RiskLevel.CAUTO, normandyRound1())).isEqualTo("-");
  }

  @Test
  void riskRuleKeepsASeaBattleWonOnTuv() {
    // Germany round 1 in the sandbox game: 110 Sea Zone, Hard value -0.1
    final ProAttackWorth sea = worth(seaZone, 68, 7.7, 0, 13.2);

    assertThat(keptBy(RiskLevel.SPERICOLATO, sea)).isEqualTo("risk");
    assertThat(keptBy(RiskLevel.CAUTO, sea)).isEqualTo("-"); // win 68% < desired 85%
  }

  @Test
  void riskRuleIgnoresLandWorthNothing() {
    // a pure TUV trade (no production): Hard's verdict stays
    final ProAttackWorth trade = worth(normandy, 97, 1.1, 0, 4.2);

    assertThat(keptBy(RiskLevel.SPERICOLATO, trade)).isEqualTo("-");
  }

  @Test
  void nothingIsKeptBelowTheDesiredWinPercentage() {
    assertThat(keptBy(RiskLevel.SPERICOLATO, worth(normandy, 60, -4.8, 6, 5.4))).isEqualTo("-");
  }

  @Test
  void objectiveRuleKeepsAVictoryCityWonWellAtALimitedCost() {
    // Egypt (victory city): risk value 1 - 2 + 4 x 0.8 - 0.4 x 14 = -3.4 < -3, expected cost 16
    final ProAttackWorth egyptAttack = worth(egypt, 80, -2, 4, 14);

    assertThat(ProPlusTuning.isObjectiveTarget(proPlus(RiskLevel.SPERICOLATO), egypt)).isTrue();
    assertThat(egyptAttack.expectedTuvCost()).isEqualTo(16);
    assertThat(keptBy(RiskLevel.SPERICOLATO, egyptAttack)).isEqualTo("objective");
    assertThat(keptBy(RiskLevel.AUDACE, egyptAttack)).isEqualTo("-"); // cost 16 > 12
    // below 75% the objective rule doesn't apply, even at Spericolato (desired 65%)
    assertThat(keptBy(RiskLevel.SPERICOLATO, worth(egypt, 70, -2, 4, 14))).isEqualTo("-");
    // a higher cost is too much
    assertThat(keptBy(RiskLevel.SPERICOLATO, worth(egypt, 80, -2, 4, 20))).isEqualTo("-");
  }

  @Test
  void objectiveRuleNeedsAVictoryCityOrAnObjective() {
    final ProData proData = proPlus(RiskLevel.SPERICOLATO);

    assertThat(ProPlusTuning.isObjectiveTarget(proData, seaZone)).isFalse();
  }

  @Test
  void exposedTransportsAreAcceptedOnlyForAnObjectiveAtALimitedCost() {
    // 0.75 x 20 - 2 = 13 TUV expected to be lost
    assertThat(ProPlusTuning.exposedTransportsCost(20, 2)).isEqualTo(13);
    assertThat(landingKeptBy(RiskLevel.SPERICOLATO, egypt, 90, 20, 2)).isEqualTo("objective");
    assertThat(landingKeptBy(RiskLevel.AUDACE, egypt, 90, 20, 2)).isEqualTo("-"); // 13 > 12
    assertThat(landingKeptBy(RiskLevel.SPERICOLATO, egypt, 70, 20, 2)).isEqualTo("-"); // < 75%
    assertThat(landingKeptBy(RiskLevel.SPERICOLATO, seaZone, 90, 4, 1)).isEqualTo("-");
    assertThat(landingKeptBy(RiskLevel.PRUDENTE, egypt, 100, 4, 2)).isEqualTo("-");
    final ProData hard = new ProData();
    hard.initializeSimulation(null, data, germans);
    assertThat(ProTuning.DEFAULT.keepsLandingWithExposedTransports(hard, egypt, 100, 4, 2))
        .isEmpty();
  }

  private String landingKeptBy(
      final RiskLevel level,
      final Territory t,
      final double win,
      final double enemyTuvSwing,
      final double attackValue) {
    final ProData proData = proPlus(level);
    return proData
        .getTuning()
        .keepsLandingWithExposedTransports(proData, t, win, enemyTuvSwing, attackValue)
        .orElse("-");
  }

  private String keptBy(final RiskLevel level, final ProAttackWorth worth) {
    final ProData proData = proPlus(level);
    return proData.getTuning().keepsAttackNotWorthIt(proData, worth).orElse("-");
  }
}
