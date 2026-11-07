package games.strategy.triplea.ai.pro;

/**
 * What a variant of the Pro AI decides about declaring war on one enemy player (Pro+ strategy
 * plans, step 3b-ter). {@link Kind#HARD} leaves the decision to the Pro AI's own random rule.
 *
 * @param kind the decision
 * @param reason why (Italian text for the diary), empty for {@link Kind#HARD}
 */
public record ProWarDecision(Kind kind, String reason) {
  public static final ProWarDecision HARD = new ProWarDecision(Kind.HARD, "");

  /** Decisions. */
  public enum Kind {
    /** The Pro AI decides as always. */
    HARD,
    /** No war declaration now. */
    FORBIDDEN,
    /** Declare war now. */
    DECLARE
  }
}
