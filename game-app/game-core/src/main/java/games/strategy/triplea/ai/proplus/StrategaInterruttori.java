package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.properties.GameProperties;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Switches of the "Pro+ Stratega LLM" per alliance (steps R0 and R2a), each with its default. Game
 * properties {@code "Pro+ stratega llm spento: <alliance>"} (ids turned off) and {@code "Pro+
 * stratega llm acceso: <alliance>"} (ids turned on), comma separated; set by the autoplay ({@code
 * --stratega-spento}, {@code --stratega-acceso}), no panel.
 *
 * <ul>
 *   <li>{@link #LISTE} (default on) off: the LLM's irreplaceable/expendable lists are not applied
 *       to Hard's defense (the LLM is still called and still answers them);
 *   <li>{@link #TENUTE} (default OFF from step R2a, measured in R0: holds cost the Axis ~1.5 VC):
 *       off = no hold/reinforcement candidate and no hold/reinforcement outside the list. The hold
 *       AFTER a conquest is not concerned;
 *   <li>{@link #OMBRA} (default on) off: no shadow campaign planner ({@link CampagnaOmbra}, never
 *       an effect on the game anyway);
 *   <li>{@link #IMPEGNO} (step R2b, default from {@code impegno.acceso_di_default} of the data
 *       file: OFF) on: the first campaign that acts ({@link Impegno}).
 * </ul>
 */
public enum StrategaInterruttori {
  LISTE("liste", true),
  TENUTE("tenute", false),
  OMBRA("ombra", true),
  IMPEGNO("impegno", false);

  public static final String KEY = "Pro+ stratega llm spento: ";
  public static final String KEY_ACCESI = "Pro+ stratega llm acceso: ";

  private final String id;
  private final boolean predefinito;

  StrategaInterruttori(final String id, final boolean predefinito) {
    this.id = id;
    this.predefinito = predefinito;
  }

  public String id() {
    return id;
  }

  /** The default of the switch (no property). */
  public boolean predefinito() {
    if (this == IMPEGNO) {
      return Impegno.Config.get().accesoDiDefault();
    }
    return predefinito;
  }

  public static Optional<StrategaInterruttori> of(final String id) {
    return Arrays.stream(values())
        .filter(s -> s.id.equals(id.strip().toLowerCase(Locale.ROOT)))
        .findFirst();
  }

  /** On for {@code alliance}: turned on, else not turned off and on by default. */
  public boolean acceso(final GameProperties props, final String alliance) {
    if (lista(props, KEY_ACCESI + alliance).contains(id)) {
      return true;
    }
    if (lista(props, KEY + alliance).contains(id)) {
      return false;
    }
    return predefinito();
  }

  /** The ids turned off for {@code alliance}. */
  public static List<String> spenti(final GameProperties props, final String alliance) {
    return lista(props, KEY + alliance);
  }

  /** The ids turned on for {@code alliance}. */
  public static List<String> accesi(final GameProperties props, final String alliance) {
    return lista(props, KEY_ACCESI + alliance);
  }

  private static List<String> lista(final GameProperties props, final String key) {
    if (props.get(key) instanceof String s && !s.isBlank()) {
      return Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
    }
    return List.of();
  }

  /** Turns off the listed switches for {@code alliance} ("liste,tenute"; empty = none off). */
  public static void set(final GameProperties props, final String alliance, final String ids) {
    scrivi(props, KEY + alliance, ids);
  }

  /** Turns on the listed switches for {@code alliance} ("tenute"; empty = none forced on). */
  public static void accendi(final GameProperties props, final String alliance, final String ids) {
    scrivi(props, KEY_ACCESI + alliance, ids);
  }

  private static void scrivi(final GameProperties props, final String key, final String ids) {
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
                                        + " (liste, tenute, ombra, impegno)"))
                        .id())
            .distinct()
            .toList();
    props.set(key, (Serializable) null);
    if (!list.isEmpty()) {
      props.set(key, String.join(",", list));
    }
  }
}
