package games.strategy.triplea.ai.proplus;

import games.strategy.triplea.ai.pro.ProAttackWorth;
import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;

/**
 * Pro+ "Propensione al rischio": how sure an attack must be before Pro+ plans it.
 *
 * <p>{@code desired} is the win% the combat move tries to reach when it assigns units to an attack
 * (Pro AI "winPercentage"); {@code minimum} is the win% below which an attack is dropped (Pro AI
 * "minWinPercentage"). Values are for normal dice; Low Luck adds {@link #LOW_LUCK_BONUS} to both,
 * capped at {@link #LOW_LUCK_CAP}. {@link #PRUDENTE} equals "Hard (AI)" with both dice types: with
 * Low Luck it uses Hard's own 95/75 instead of the +5 rule.
 *
 * <p>The level also sets how the combat move's last check treats an attack whose value is negative
 * with the units assigned (not holdable, TUV lost to the counter-attack): see {@link
 * ProPlusTuning#keepsAttackNotWorthIt}. {@link #PRUDENTE} drops them as Hard does.
 */
@Getter
public enum RiskLevel {
  PRUDENTE(1, "Prudente", 90, 65, ProAttackWorth.HARD_COUNTER_ATTACK_WEIGHT, 0, 0) {
    @Override
    public double minimumWinPercentage(final boolean lowLuck) {
      return lowLuck ? HARD_LOW_LUCK_MINIMUM : super.minimumWinPercentage(false);
    }

    @Override
    public boolean keepsAttacksNotWorthIt() {
      return false;
    }
  },
  CAUTO(2, "Cauto", 85, 62, 0.6, 0.5, 4),
  EQUILIBRATO(3, "Equilibrato", 80, 60, 0.55, 1, 8),
  AUDACE(4, "Audace", 72, 52, 0.45, 2, 12),
  SPERICOLATO(5, "Spericolato", 65, 45, 0.4, 3, 16);

  public static final RiskLevel DEFAULT = EQUILIBRATO;
  static final double LOW_LUCK_BONUS = 5;
  static final double LOW_LUCK_CAP = 95;

  /** "Hard (AI)" minimum win% with Low Luck (ProTuning.DEFAULT). */
  static final double HARD_LOW_LUCK_MINIMUM = 75;

  /** Lowest win% at which the objective rule keeps an attack (with the desired win%, if higher). */
  static final double OBJECTIVE_RULE_MIN_WIN = 75;

  private final int level;
  private final String label;
  private final double desired;
  private final double minimum;

  /**
   * Weight of the enemy counter-attack TUV swing in the attack value of the combat move's last
   * check ("Hard (AI)": 2/3). Lower = the counter-attack matters less.
   */
  private final double counterAttackWeight;

  /** How far below 0 that attack value may go and the attack is still kept ("Hard (AI)": 0). */
  private final double valueTolerance;

  /**
   * Objective rule: most TUV expected to be lost (counter-attack minus the battle's TUV swing) for
   * an attack on a victory city or national-objective territory that wins well; 0 = rule off.
   */
  private final double objectiveCostLimit;

  RiskLevel(
      final int level,
      final String label,
      final double desired,
      final double minimum,
      final double counterAttackWeight,
      final double valueTolerance,
      final double objectiveCostLimit) {
    this.level = level;
    this.label = label;
    this.desired = desired;
    this.minimum = minimum;
    this.counterAttackWeight = counterAttackWeight;
    this.valueTolerance = valueTolerance;
    this.objectiveCostLimit = objectiveCostLimit;
  }

  /**
   * Whether the level keeps some attacks "Hard (AI)" drops as not worth it (negative value with the
   * units assigned). {@link #PRUDENTE}: never, as Hard.
   */
  public boolean keepsAttacksNotWorthIt() {
    return true;
  }

  /** Lowest win% for the objective rule: {@link #OBJECTIVE_RULE_MIN_WIN} or the desired win%. */
  public double objectiveRuleMinWin(final boolean lowLuck) {
    return Math.max(OBJECTIVE_RULE_MIN_WIN, desiredWinPercentage(lowLuck));
  }

  public double desiredWinPercentage(final boolean lowLuck) {
    return lowLuck ? Math.min(LOW_LUCK_CAP, desired + LOW_LUCK_BONUS) : desired;
  }

  public double minimumWinPercentage(final boolean lowLuck) {
    return lowLuck ? Math.min(LOW_LUCK_CAP, minimum + LOW_LUCK_BONUS) : minimum;
  }

  /**
   * This level moved by a strategy plan's front modifier: {@code modifier} limited to -1..+1, the
   * result to {@link #PRUDENTE}..{@link #SPERICOLATO}.
   */
  public RiskLevel withModifier(final int modifier) {
    final int bounded = Math.max(-1, Math.min(1, modifier));
    final RiskLevel[] levels = values();
    final int moved =
        Math.max(levels[0].level, Math.min(levels[levels.length - 1].level, level + bounded));
    return fromLevel(moved).orElse(this);
  }

  public static Optional<RiskLevel> fromLevel(final int level) {
    return Arrays.stream(values()).filter(r -> r.level == level).findAny();
  }
}
