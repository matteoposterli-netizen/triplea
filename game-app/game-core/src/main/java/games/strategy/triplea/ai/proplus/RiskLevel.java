package games.strategy.triplea.ai.proplus;

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
 */
@Getter
public enum RiskLevel {
  PRUDENTE(1, "Prudente", 90, 65) {
    @Override
    public double minimumWinPercentage(final boolean lowLuck) {
      return lowLuck ? HARD_LOW_LUCK_MINIMUM : super.minimumWinPercentage(false);
    }
  },
  CAUTO(2, "Cauto", 85, 62),
  EQUILIBRATO(3, "Equilibrato", 80, 60),
  AUDACE(4, "Audace", 72, 52),
  SPERICOLATO(5, "Spericolato", 65, 45);

  public static final RiskLevel DEFAULT = EQUILIBRATO;
  static final double LOW_LUCK_BONUS = 5;
  static final double LOW_LUCK_CAP = 95;

  /** "Hard (AI)" minimum win% with Low Luck (ProTuning.DEFAULT). */
  static final double HARD_LOW_LUCK_MINIMUM = 75;

  private final int level;
  private final String label;
  private final double desired;
  private final double minimum;

  RiskLevel(final int level, final String label, final double desired, final double minimum) {
    this.level = level;
    this.label = label;
    this.desired = desired;
    this.minimum = minimum;
  }

  public double desiredWinPercentage(final boolean lowLuck) {
    return lowLuck ? Math.min(LOW_LUCK_CAP, desired + LOW_LUCK_BONUS) : desired;
  }

  public double minimumWinPercentage(final boolean lowLuck) {
    return lowLuck ? Math.min(LOW_LUCK_CAP, minimum + LOW_LUCK_BONUS) : minimum;
  }

  public static Optional<RiskLevel> fromLevel(final int level) {
    return Arrays.stream(values()).filter(r -> r.level == level).findAny();
  }
}
