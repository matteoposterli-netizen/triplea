package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.triplea.ai.pro.ProAttackWorth;
import games.strategy.triplea.ai.pro.ProTuning;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RiskLevelTest {

  @ParameterizedTest
  @CsvSource({
    "1, PRUDENTE, Prudente, 90, 65, 95, 75",
    "2, CAUTO, Cauto, 85, 62, 90, 67",
    "3, EQUILIBRATO, Equilibrato, 80, 60, 85, 65",
    "4, AUDACE, Audace, 72, 52, 77, 57",
    "5, SPERICOLATO, Spericolato, 65, 45, 70, 50"
  })
  void levelMapsToThresholds(
      final int level,
      final RiskLevel expected,
      final String label,
      final double desired,
      final double minimum,
      final double desiredLowLuck,
      final double minimumLowLuck) {
    final RiskLevel risk = RiskLevel.fromLevel(level).orElseThrow();

    assertThat(risk).isEqualTo(expected);
    assertThat(risk.getLabel()).isEqualTo(label);
    assertThat(risk.desiredWinPercentage(false)).isEqualTo(desired);
    assertThat(risk.minimumWinPercentage(false)).isEqualTo(minimum);
    assertThat(risk.desiredWinPercentage(true)).isEqualTo(desiredLowLuck);
    assertThat(risk.minimumWinPercentage(true)).isEqualTo(minimumLowLuck);
  }

  @ParameterizedTest
  @CsvSource({"0", "6", "-1"})
  void unknownLevelHasNoRiskLevel(final int level) {
    assertThat(RiskLevel.fromLevel(level)).isEmpty();
  }

  @org.junit.jupiter.api.Test
  void defaultIsEquilibratoAndPrudenteIsHard() {
    assertThat(RiskLevel.DEFAULT).isEqualTo(RiskLevel.EQUILIBRATO);
    assertThat(RiskLevel.PRUDENTE.desiredWinPercentage(false)).isEqualTo(90);
    assertThat(RiskLevel.PRUDENTE.minimumWinPercentage(false)).isEqualTo(65);
  }

  @org.junit.jupiter.api.Test
  void prudenteEqualsHardAlsoWithLowLuck() {
    final ProTuning hard = ProTuning.DEFAULT;
    assertThat(RiskLevel.PRUDENTE.desiredWinPercentage(true)).isEqualTo(hard.winPercentage(true));
    assertThat(RiskLevel.PRUDENTE.minimumWinPercentage(true))
        .isEqualTo(hard.minWinPercentage(true));
    assertThat(RiskLevel.PRUDENTE.desiredWinPercentage(false)).isEqualTo(hard.winPercentage(false));
    assertThat(RiskLevel.PRUDENTE.minimumWinPercentage(false))
        .isEqualTo(hard.minWinPercentage(false));
  }

  @org.junit.jupiter.api.Test
  void prudenteDropsAttacksNotWorthItAsHard() {
    assertThat(RiskLevel.PRUDENTE.keepsAttacksNotWorthIt()).isFalse();
    assertThat(RiskLevel.PRUDENTE.getCounterAttackWeight())
        .isEqualTo(ProAttackWorth.HARD_COUNTER_ATTACK_WEIGHT);
    assertThat(RiskLevel.PRUDENTE.getValueTolerance()).isZero();
    assertThat(RiskLevel.PRUDENTE.getObjectiveCostLimit()).isZero();
  }

  @ParameterizedTest
  @CsvSource({
    "CAUTO, PRUDENTE",
    "EQUILIBRATO, CAUTO",
    "AUDACE, EQUILIBRATO",
    "SPERICOLATO, AUDACE"
  })
  void moreRiskToleratesMoreAttacksNotWorthIt(final RiskLevel level, final RiskLevel lower) {
    assertThat(level.keepsAttacksNotWorthIt()).isTrue();
    assertThat(level.getCounterAttackWeight()).isLessThan(lower.getCounterAttackWeight());
    assertThat(level.getValueTolerance()).isGreaterThan(lower.getValueTolerance());
    assertThat(level.getObjectiveCostLimit()).isGreaterThan(lower.getObjectiveCostLimit());
    assertThat(level.objectiveRuleMinWin(false))
        .isLessThanOrEqualTo(lower.objectiveRuleMinWin(false));
    assertThat(level.objectiveRuleMinWin(false)).isGreaterThanOrEqualTo(75);
  }
}
