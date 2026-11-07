package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import java.io.Serializable;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * The general staff of an alliance, saved in the game as constant game properties holding only JDK
 * strings (official TripleA opens the save and ignores them):
 *
 * <ul>
 *   <li>{@code "Pro+ piano: <alliance>"} = the plan chosen in the settings dialog: a plan id or
 *       {@value #AUTOMATICO};
 *   <li>{@code "Pro+ stato maggiore: <alliance>"} = JSON {@code {"piano": "A", "round_inizio": 1,
 *       "storico": []}}: the plan fixed for the game, the round it was fixed in and a history per
 *       round (empty in step 3a; later steps write progress, guesses about the enemy and posture).
 * </ul>
 *
 * <p>The plan is fixed the first time an alliance's Pro+ player plays ({@link #fix}) and never
 * changes afterwards (step 3a).
 */
@UtilityClass
public final class StatoMaggiore {
  public static final String AUTOMATICO = "auto";
  static final String CHOICE_PREFIX = "Pro+ piano: ";
  static final String STATE_PREFIX = "Pro+ stato maggiore: ";

  /**
   * The saved state.
   *
   * @param piano id of the plan in force
   * @param roundInizio round the plan was fixed in
   * @param storico one entry per round ({@link Valutazione})
   */
  public record Stato(String piano, int roundInizio, JsonArray storico) {
    String toJson() {
      final JsonObject json = new JsonObject();
      json.addProperty("piano", piano);
      json.addProperty("round_inizio", roundInizio);
      json.add("storico", storico);
      return json.toString();
    }

    static Optional<Stato> fromJson(final String text) {
      try {
        final JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        return Optional.of(
            new Stato(
                json.get("piano").getAsString(),
                json.get("round_inizio").getAsInt(),
                json.has("storico") ? json.getAsJsonArray("storico") : new JsonArray()));
      } catch (final RuntimeException e) {
        return Optional.empty();
      }
    }
  }

  /** The plan chosen in the dialog: a plan id or {@link #AUTOMATICO} (the default). */
  public static String getChoice(final GameProperties props, final String alliance) {
    return props.get(CHOICE_PREFIX + alliance) instanceof String choice && !choice.isBlank()
        ? choice
        : AUTOMATICO;
  }

  public static void setChoice(
      final GameProperties props, final String alliance, final String choice) {
    set(props, CHOICE_PREFIX + alliance, choice);
  }

  /** The state saved in the game, if the plan was already fixed. */
  public static Optional<Stato> getState(final GameProperties props, final String alliance) {
    return props.get(STATE_PREFIX + alliance) instanceof String text
        ? Stato.fromJson(text)
        : Optional.empty();
  }

  /**
   * The plan in force for {@code alliance}: the fixed one if any, else the chosen one, else
   * "Automatico". A fixed or chosen plan the file no longer has falls back to "Automatico".
   */
  public static Optional<Strategia.Piano> activePlan(
      final GameProperties props, final String alliance, final Strategia strategia) {
    final Optional<Strategia.Alleanza> plans = strategia.alleanza(alliance);
    if (plans.isEmpty()) {
      return Optional.empty();
    }
    final String id =
        getState(props, alliance).map(Stato::piano).orElseGet(() -> getChoice(props, alliance));
    return plans.get().piano(id).or(plans.get()::pianoAutomatico);
  }

  /**
   * Fixes the plan of {@code alliance} if it is not fixed yet: writes the state with the plan in
   * force and the current round. Returns the state.
   */
  public static Optional<Stato> fix(
      final GameState data, final String alliance, final Strategia strategia) {
    final GameProperties props = data.getProperties();
    final Optional<Stato> existing = getState(props, alliance);
    if (existing.isPresent()) {
      return existing;
    }
    final Optional<Strategia.Piano> plan = activePlan(props, alliance, strategia);
    if (plan.isEmpty()) {
      return Optional.empty();
    }
    final Stato state = new Stato(plan.get().id(), data.getSequence().getRound(), new JsonArray());
    set(props, STATE_PREFIX + alliance, state.toJson());
    return Optional.of(state);
  }

  /**
   * Appends {@code entry} to the history ({@code storico}) of {@code alliance}'s fixed plan (step
   * 3c: progress and reading of the enemy, one entry per round). Nothing without a fixed plan.
   */
  public static void aggiungiStorico(
      final GameState data, final String alliance, final JsonObject entry) {
    final GameProperties props = data.getProperties();
    getState(props, alliance)
        .ifPresent(
            state -> {
              final JsonArray storico = state.storico().deepCopy();
              storico.add(entry);
              set(
                  props,
                  STATE_PREFIX + alliance,
                  new Stato(state.piano(), state.roundInizio(), storico).toJson());
            });
  }

  private static void set(final GameProperties props, final String key, final String value) {
    // unbind first: set() appends the key to the property ordering each time
    props.set(key, (Serializable) null);
    props.set(key, value);
  }
}
