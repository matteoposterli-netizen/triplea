package games.strategy.triplea.ai.proplus.strategia;

import games.strategy.engine.data.properties.GameProperties;
import java.io.Serializable;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The decider chosen per alliance, saved in the game as constant game properties holding only JDK
 * strings (like the risk level): {@code "Pro+ decisore: <alliance>"} = {@value Decisore#REGOLE}
 * (default) or {@value Decisore#FILE}; {@code "Pro+ decisore file: <alliance>"} = path of the
 * directives file or folder ({@link DecisoreFile}).
 */
@UtilityClass
public final class Decisori {
  static final String CHOICE_PREFIX = "Pro+ decisore: ";
  static final String FILE_PREFIX = "Pro+ decisore file: ";

  /** The deciders offered in the settings dialog and the autoplay. */
  public static final List<String> SCELTE = List.of(Decisore.REGOLE, Decisore.FILE);

  /** {@value Decisore#REGOLE} or {@value Decisore#FILE}. */
  public static String getScelta(final GameProperties props, final String alliance) {
    return props.get(CHOICE_PREFIX + alliance) instanceof String s && SCELTE.contains(s)
        ? s
        : Decisore.REGOLE;
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

  /** The decider of {@code alliance}. */
  public static Decisore per(final GameProperties props, final String alliance) {
    return Decisore.FILE.equals(getScelta(props, alliance))
        ? new DecisoreFile(getFile(props, alliance))
        : new DecisoreRegole();
  }

  private static void put(final GameProperties props, final String key, final String value) {
    // unbind first: set() appends the key to the property ordering each time
    props.set(key, (Serializable) null);
    props.set(key, value);
  }
}
