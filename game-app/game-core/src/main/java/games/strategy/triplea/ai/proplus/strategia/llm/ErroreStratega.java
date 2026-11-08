package games.strategy.triplea.ai.proplus.strategia.llm;

/**
 * Why the strategist gave no usable answer (Italian text for the diary and the panel). Never
 * contains an API key.
 */
public final class ErroreStratega extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ErroreStratega(final String message) {
    super(message);
  }

  public ErroreStratega(final String message, final Throwable cause) {
    super(message, cause);
  }
}
