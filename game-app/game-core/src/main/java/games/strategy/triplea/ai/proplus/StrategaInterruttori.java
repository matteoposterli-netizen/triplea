package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.properties.GameProperties;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Step R0 (diagnosis): two switches of the "Pro+ Stratega LLM" per alliance, default on (= the
 * behaviour before the step). Game property {@code "Pro+ stratega llm spento: <alliance>"} with the
 * ids turned off, comma separated; set by the autoplay ({@code --stratega-spento}), no panel.
 *
 * <ul>
 *   <li>{@link #LISTE} off: the LLM's irreplaceable/expendable lists are not applied to Hard's
 *       defense (the LLM is still called and still answers them);
 *   <li>{@link #TENUTE} off: no hold/reinforcement candidate and no hold/reinforcement outside the
 *       list.
 * </ul>
 */
public enum StrategaInterruttori {
  LISTE("liste"),
  TENUTE("tenute");

  public static final String KEY = "Pro+ stratega llm spento: ";

  private final String id;

  StrategaInterruttori(final String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  public static Optional<StrategaInterruttori> of(final String id) {
    return Arrays.stream(values())
        .filter(s -> s.id.equals(id.strip().toLowerCase(Locale.ROOT)))
        .findFirst();
  }

  /** True unless the switch is off for {@code alliance}. */
  public boolean acceso(final GameProperties props, final String alliance) {
    return !spenti(props, alliance).contains(id);
  }

  /** The ids turned off for {@code alliance}. */
  public static List<String> spenti(final GameProperties props, final String alliance) {
    if (props.get(KEY + alliance) instanceof String s && !s.isBlank()) {
      return Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
    }
    return List.of();
  }

  /** Turns off the listed switches for {@code alliance} ("liste,tenute"; empty = all on). */
  public static void set(final GameProperties props, final String alliance, final String ids) {
    final List<String> list =
        Arrays.stream(ids.split("[,+]"))
            .map(String::strip)
            .filter(x -> !x.isEmpty())
            .map(
                x ->
                    of(x)
                        .orElseThrow(
                            () ->
                                new IllegalArgumentException(
                                    "Interruttore dello Stratega sconosciuto: "
                                        + x
                                        + " (liste, tenute)"))
                        .id())
            .distinct()
            .toList();
    props.set(KEY + alliance, (Serializable) null);
    if (!list.isEmpty()) {
      props.set(KEY + alliance, String.join(",", list));
    }
  }
}
