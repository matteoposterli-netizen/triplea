package games.strategy.triplea.ai.proplus.diario;

import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.engine.framework.GameRunner;
import java.nio.file.Path;
import java.util.Optional;
import java.util.prefs.Preferences;
import lombok.experimental.UtilityClass;

/**
 * Where the decision diary goes and whether it is written.
 *
 * <p>Folder: {@code diario} next to the jar (the TripleA root folder, marked by {@code
 * .triplea-root}), one sub-folder per game; the system property {@value #FOLDER_PROPERTY} replaces
 * it (tests). Not written by the headless bot server unless that property is set. On/off is a user
 * preference (default on), not a game setting.
 */
@UtilityClass
public final class DiarySettings {
  public static final String FOLDER_NAME = "diario";
  public static final String FOLDER_PROPERTY = "proplus.diario.dir";

  /** Games kept in the diary folder; older ones are deleted when a new game starts. */
  public static final int MAX_GAMES = 20;

  private static final String ENABLED_KEY = "diarioDecisioniAttivo";

  public static boolean isEnabled() {
    try {
      return preferences().getBoolean(ENABLED_KEY, true);
    } catch (final RuntimeException e) {
      return true;
    }
  }

  public static void setEnabled(final boolean enabled) {
    try {
      final Preferences prefs = preferences();
      prefs.putBoolean(ENABLED_KEY, enabled);
      prefs.flush();
    } catch (final Exception e) {
      // preference not saved: the diary keeps its previous state
    }
  }

  /** The diary folder, if it can be located. It may not exist yet. */
  public static Optional<Path> folder() {
    final String override = System.getProperty(FOLDER_PROPERTY);
    if (override != null && !override.isBlank()) {
      return Optional.of(Path.of(override));
    }
    if (Boolean.parseBoolean(System.getProperty(GameRunner.TRIPLEA_HEADLESS))) {
      return Optional.empty();
    }
    try {
      return Optional.of(ClientFileSystemHelper.getRootFolder().resolve(FOLDER_NAME));
    } catch (final RuntimeException | LinkageError e) {
      return Optional.empty();
    }
  }

  private static Preferences preferences() {
    return Preferences.userNodeForPackage(DiarySettings.class);
  }
}
