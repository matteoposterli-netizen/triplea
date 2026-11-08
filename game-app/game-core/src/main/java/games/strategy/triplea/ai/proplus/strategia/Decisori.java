package games.strategy.triplea.ai.proplus.strategia;

import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.proplus.strategia.llm.Modalita;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import lombok.experimental.UtilityClass;

/**
 * The decider chosen per alliance, saved in the game as constant game properties holding only JDK
 * strings (like the risk level): {@code "Pro+ decisore: <alliance>"} = a {@link Modalita} id
 * ({@value Decisore#REGOLE}, {@value Decisore#FILE}, "auto", "claude", "chiave"); {@code "Pro+
 * decisore file: <alliance>"} = path of the directives file or folder ({@link DecisoreFile}). A
 * game without the property uses the user's preferred mode ({@link
 * ProfiloStratega#modalitaPredefinita()}, "regole" until the user picks one in the panel).
 */
@UtilityClass
public final class Decisori {
  static final String CHOICE_PREFIX = "Pro+ decisore: ";
  static final String FILE_PREFIX = "Pro+ decisore file: ";

  /** The deciders offered in the settings dialog and the autoplay (mode ids). */
  public static final List<String> SCELTE =
      Arrays.stream(Modalita.values()).map(Modalita::id).toList();

  /** The mode id of {@code alliance}. */
  public static String getScelta(final GameProperties props, final String alliance) {
    return props.get(CHOICE_PREFIX + alliance) instanceof String s && SCELTE.contains(s)
        ? s
        : ProfiloStratega.carica().modalitaPredefinita().id();
  }

  public static Modalita getModalita(final GameProperties props, final String alliance) {
    return Modalita.daId(getScelta(props, alliance)).orElse(Modalita.REGOLE);
  }

  /** Path of the directives file or folder ("" if none). */
  public static String getFile(final GameProperties props, final String alliance) {
    return props.get(FILE_PREFIX + alliance) instanceof String s ? s : "";
  }

  public static void set(
      final GameProperties props, final String alliance, final String choice, final String file) {
    put(props, CHOICE_PREFIX + alliance, SCELTE.contains(choice) ? choice : Decisore.REGOLE);
    put(props, FILE_PREFIX + alliance, file == null ? "" : file);
  }

  /** Makes the LLM strategists (tests replace it with fake transports: no network). */
  static Function<Modalita, ? extends Decisore> fabbricaLlm = DecisoreLlm::reale;

  /** The decider of {@code alliance}. */
  public static Decisore per(final GameProperties props, final String alliance) {
    return per(props, alliance, fabbricaLlm);
  }

  /** The decider of {@code alliance}, LLM strategists made by {@code llm} (tests). */
  public static Decisore per(
      final GameProperties props,
      final String alliance,
      final Function<Modalita, ? extends Decisore> llm) {
    final Modalita mode = getModalita(props, alliance);
    return switch (mode) {
      case FILE -> new DecisoreFile(getFile(props, alliance));
      case REGOLE -> new DecisoreRegole();
      case AUTOMATICO, CLAUDE_CODE, CHIAVE_API -> llm.apply(mode);
    };
  }

  private static void put(final GameProperties props, final String key, final String value) {
    // unbind first: set() appends the key to the property ordering each time
    props.set(key, (Serializable) null);
    props.set(key, value);
  }
}
