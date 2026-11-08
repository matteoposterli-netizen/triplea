package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameState;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * Finds the strategy file of a game: the index {@value #INDEX} maps the game name (the map XML's
 * {@code <info name>}) to a file in {@value #FOLDER}. Files are read once, warnings (no file,
 * malformed file, names not in the map, territories in no front) go once to the AI log.
 */
@UtilityClass
public final class Strategie {
  static final String FOLDER = "proplus/strategia/";
  static final String INDEX = FOLDER + "indice.json";

  private static final Map<String, Optional<Strategia>> byGame = new ConcurrentHashMap<>();
  private static final Set<String> checkedGames = ConcurrentHashMap.newKeySet();

  /** The strategy of {@code data}'s game, empty (with a warning) if the map has none. */
  public static Optional<Strategia> forGame(final GameState data) {
    final String game = data.getGameName();
    if (game == null) {
      return Optional.empty();
    }
    final Optional<Strategia> strategia = byGame.computeIfAbsent(game, Strategie::load);
    if (strategia.isPresent() && checkedGames.add(game)) {
      warnAboutNames(strategia.get(), data);
    }
    return strategia;
  }

  /**
   * LLM strategist: the strategy file of {@code data}'s game as raw JSON (the plans the fixed part
   * of the prompt describes), empty if the map has none.
   */
  public static Optional<JsonObject> json(final GameState data) {
    final @Nullable String file = data.getGameName() == null ? null : fileFor(data.getGameName());
    if (file == null) {
      return Optional.empty();
    }
    try (InputStream in = Strategie.class.getClassLoader().getResourceAsStream(FOLDER + file)) {
      return in == null
          ? Optional.empty()
          : Optional.of(
              JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                  .getAsJsonObject());
    } catch (final IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  private static Optional<Strategia> load(final String game) {
    final @Nullable String file = fileFor(game);
    if (file == null) {
      ProLogger.warn(
          "[Pro+] Nessun file di strategia per \""
              + game
              + "\": Pro+ gioca senza piano (stato maggiore spento).");
      return Optional.empty();
    }
    try (InputStream in = Strategie.class.getClassLoader().getResourceAsStream(FOLDER + file)) {
      if (in == null) {
        ProLogger.warn("[Pro+] File di strategia mancante: " + FOLDER + file + ". Nessun piano.");
        return Optional.empty();
      }
      final Strategia strategia =
          Strategia.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
      ProLogger.info("[Pro+] Strategia caricata per \"" + game + "\": " + FOLDER + file);
      return Optional.of(strategia);
    } catch (final IOException | RuntimeException e) {
      ProLogger.warn(
          "[Pro+] File di strategia "
              + FOLDER
              + file
              + " non valido ("
              + e.getMessage()
              + ")."
              + " Nessun piano.");
      return Optional.empty();
    }
  }

  private static @Nullable String fileFor(final String game) {
    try (InputStream in = Strategie.class.getClassLoader().getResourceAsStream(INDEX)) {
      if (in == null) {
        return null;
      }
      final Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
      final JsonElement file = JsonParser.parseReader(reader).getAsJsonObject().get(game);
      return file == null || !file.isJsonPrimitive() ? null : file.getAsString();
    } catch (final IOException | RuntimeException e) {
      ProLogger.warn("[Pro+] Indice delle strategie " + INDEX + " non valido: " + e.getMessage());
      return null;
    }
  }

  private static void warnAboutNames(final Strategia strategia, final GameState data) {
    final Set<String> unknown = strategia.unknownTerritories(data.getMap());
    if (!unknown.isEmpty()) {
      ProLogger.warn("[Pro+] Strategia: nomi che non sono territori della mappa: " + unknown);
    }
    final Set<String> unknownUnits = strategia.unknownUnitTypes(data);
    if (!unknownUnits.isEmpty()) {
      ProLogger.warn("[Pro+] Strategia: unita' che non esistono nella mappa: " + unknownUnits);
    }
    final Set<String> unmapped = strategia.unmappedTerritories(data.getMap());
    if (!unmapped.isEmpty()) {
      ProLogger.warn(
          "[Pro+] Strategia: territori in nessun fronte (fronte \""
              + Strategia.ALTRO
              + "\", modificatore 0): "
              + unmapped);
    }
  }
}
