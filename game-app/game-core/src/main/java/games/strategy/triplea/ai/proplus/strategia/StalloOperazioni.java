package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.properties.GameProperties;
import java.io.Serializable;

/**
 * Step OPERAZIONI ANFIBIE-BIS: an operation of the nation moving first in a combined operation
 * (Overlord USA) waits until its landing on the pair's target reaches the desired win% (accumulo).
 * From round {@link #DA_ROUND}, {@link #ROUND} rounds in a row without reaching it = "in stallo":
 * the plan's progress gets one level worse ({@link Valutazione}); the plan is not changed here.
 *
 * <p>Saved as the game property {@code "Pro+ operazione: <nome>"} = JSON {@code {"ultimo_pronto":
 * R, "stallo": false}} (JDK strings only).
 */
public final class StalloOperazioni {
  static final String PREFIX = "Pro+ operazione: ";

  /** First round counted. */
  public static final int DA_ROUND = 5;

  /** Rounds without reaching the threshold that make a stall. */
  public static final int ROUND = 3;

  /**
   * @param ultimoPronto last round the threshold was reached (0 = never)
   * @param roundSenza rounds from {@link #DA_ROUND} (or the last ready round) without reaching it
   * @param stallo whether the operation is in a stall
   */
  public record Stato(int ultimoPronto, int roundSenza, boolean stallo) {}

  private StalloOperazioni() {}

  /** Notes whether the operation is ready in {@code round} and returns its state. */
  public static Stato aggiorna(
      final GameProperties props, final String nome, final int round, final boolean pronto) {
    final int last = Math.max(ultimoPronto(props, nome), pronto ? round : 0);
    final int since = Math.max(DA_ROUND, last + 1);
    final int without = pronto ? 0 : Math.max(0, round - since + 1);
    final Stato state = new Stato(last, without, without >= ROUND);
    final JsonObject json = new JsonObject();
    json.addProperty("ultimo_pronto", last);
    json.addProperty("round_senza", without);
    json.addProperty("stallo", state.stallo());
    // unbind first: set() appends the key to the property ordering each time
    props.set(PREFIX + nome, (Serializable) null);
    props.set(PREFIX + nome, json.toString());
    return state;
  }

  /** Whether the operation named {@code nome} is in a stall (as last noted). */
  public static boolean inStallo(final GameProperties props, final String nome) {
    return read(props, nome)
        .map(j -> j.has("stallo") && j.get("stallo").getAsBoolean())
        .orElse(false);
  }

  private static int ultimoPronto(final GameProperties props, final String nome) {
    return read(props, nome)
        .map(j -> j.has("ultimo_pronto") ? j.get("ultimo_pronto").getAsInt() : 0)
        .orElse(0);
  }

  private static java.util.Optional<JsonObject> read(
      final GameProperties props, final String nome) {
    if (props.get(PREFIX + nome) instanceof String text) {
      try {
        return java.util.Optional.of(JsonParser.parseString(text).getAsJsonObject());
      } catch (final RuntimeException e) {
        return java.util.Optional.empty();
      }
    }
    return java.util.Optional.empty();
  }
}
