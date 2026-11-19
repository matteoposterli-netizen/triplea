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
 *       file: OFF) on: the first campaign that acts ({@link Impegno});
 *   <li>{@link #ASSEDIO} and {@link #ALLEATI_R4} (step R4, defaults from {@code impegno.r4}: OFF),
 *       on top of {@link #IMPEGNO}: the Axis' siege and Africa ({@link Assedio}), the Allies'
 *       Gibraltar hub and Africa ({@link SnodoAlleati});
 *   <li>{@link #ALLEATI_R5} (step R5, default from {@code impegno.r5}: OFF), on top of {@link
 *       #IMPEGNO}: the Allies remade ({@link AlleatiR5}); for the USA and the United Kingdom it
 *       replaces R3a and {@link #ALLEATI_R4}.
 *   <li>{@link #SQUADRA_GIBILTERRA} (step R6, default from {@code impegno.r6}: OFF), on top of
 *       {@link #IMPEGNO}: the USA's Gibraltar squadron ({@link SquadraGibilterra}); only the USA
 *       play the commitment, the other Allies play as Hard.
 *   <li>{@link #ITALIA_CONVOGLIO} (step R6c, default from {@code impegno.r6c}: OFF), on top of
 *       {@link #ASSEDIO}: Italy's Africa shuttle in convoy ({@link ConvoglioItalia}).
 *   <li>{@link #COMPITI} (step P1, default from {@code compiti.json}: OFF), on top of {@link
 *       #IMPEGNO}: the generic task mechanism ({@link Compiti}); it replaces the commitment of the
 *       alliance: the nations with a task play it, the others play as Hard.
 *   <li>{@link #REGNO_UNITO_P3} (step P3, default from {@code compiti.json} {@code p3}: OFF), on
 *       top of {@link #COMPITI}: the city to hold and the bridgehead weight ({@link CittaPonte}).
 *   <li>{@link #USA_PRONTI}, {@link #NORVEGIA_PRESTO}, {@link #RUSSIA_FRONTE} (step P4, defaults
 *       from {@code p4.json}: OFF), independent of {@link #COMPITI} and {@link #IMPEGNO}: three
 *       indications to Hard ({@link IndicazioniP4}).
 *   <li>{@link #USA_PROPORZIONE}, {@link #NORVEGIA_SCORTA} (step P5, defaults from {@code p5.json}:
 *       OFF): the USA's landing force in proportion in the neutral rounds ({@link ForzaSbarco}) and
 *       the escorted landing of the UK ({@link ScortaSbarco}, with {@link #NORVEGIA_PRESTO}).
 * </ul>
 */
public enum StrategaInterruttori {
  LISTE("liste", true),
  TENUTE("tenute", false),
  OMBRA("ombra", true),
  IMPEGNO("impegno", false),
  ASSEDIO("assedio", false),
  ALLEATI_R4("alleati_r4", false),
  ALLEATI_R5("alleati_r5", false),
  SQUADRA_GIBILTERRA("squadra_gibilterra", false),
  ITALIA_CONVOGLIO("italia_convoglio", false),
  COMPITI("compiti", false),
  REGNO_UNITO_P3("regno_unito_p3", false),
  USA_PRONTI("usa_pronti", false),
  NORVEGIA_PRESTO("norvegia_presto", false),
  RUSSIA_FRONTE("russia_fronte", false),
  USA_PROPORZIONE("usa_proporzione", false),
  NORVEGIA_SCORTA("norvegia_scorta", false);

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
    if (this == ASSEDIO) {
      return ConfigR4.get().assedioDiDefault();
    }
    if (this == ALLEATI_R4) {
      return ConfigR4.get().alleatiDiDefault();
    }
    if (this == ALLEATI_R5) {
      return AlleatiR5.Config.get().accesoDiDefault();
    }
    if (this == SQUADRA_GIBILTERRA) {
      return SquadraGibilterra.Config.get().accesoDiDefault();
    }
    if (this == ITALIA_CONVOGLIO) {
      return ConvoglioItalia.Config.get().accesoDiDefault();
    }
    if (this == COMPITI) {
      return Compito.dati().accesoDiDefault();
    }
    if (this == REGNO_UNITO_P3) {
      return CittaPonte.Config.get().accesoDiDefault();
    }
    if (this == USA_PRONTI) {
      return IndicazioniP4.Config.get().usaDiDefault();
    }
    if (this == NORVEGIA_PRESTO) {
      return IndicazioniP4.Config.get().norvegiaDiDefault();
    }
    if (this == RUSSIA_FRONTE) {
      return IndicazioniP4.Config.get().fronteDiDefault();
    }
    if (this == USA_PROPORZIONE) {
      return IndicazioniP5.Config.get().usaDiDefault();
    }
    if (this == NORVEGIA_SCORTA) {
      return IndicazioniP5.Config.get().scortaDiDefault();
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
                                        + " (liste, tenute, ombra, impegno, assedio, alleati_r4,"
                                        + " alleati_r5, squadra_gibilterra, italia_convoglio,"
                                        + " compiti, regno_unito_p3, usa_pronti,"
                                        + " norvegia_presto, russia_fronte, usa_proporzione,"
                                        + " norvegia_scorta)"))
                        .id())
            .distinct()
            .toList();
    props.set(key, (Serializable) null);
    if (!list.isEmpty()) {
      props.set(key, String.join(",", list));
    }
  }
}
