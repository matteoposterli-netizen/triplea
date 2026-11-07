package games.strategy.triplea.ai.proplus.diario;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.Getter;
import org.triplea.config.product.ProductVersionReader;

/**
 * The diary of one game: a folder {@code <date-time>_<map>} with {@code diario.txt} (Italian, for
 * people) and {@code decisioni.jsonl} (one JSON object per line). Shared by all AI players of the
 * game; also keeps each nation's previous-turn objectives for the turn-to-turn comparison.
 */
final class GameDiary {
  static final String TEXT_FILE = "diario.txt";
  static final String JSON_FILE = "decisioni.jsonl";
  private static final String CRLF = "\r\n";
  private static final DateTimeFormatter FOLDER_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
  private static final Pattern GAME_FOLDER =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_.*");
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  /** One diary per running game (a loaded save is a new game, so a new folder). */
  private static final Map<GameData, GameDiary> DIARIES = new WeakHashMap<>();

  @Getter private final Path folder;

  /** Nation -> last turn's objectives and every candidate's priority value. */
  private final Map<String, Objectives> previousObjectives = new HashMap<>();

  /** Closes the nation turn still open in the diary, null if none. */
  @Nullable private Runnable openTurnCloser;

  record Objectives(int round, List<String> top, Map<String, Double> values) {}

  private GameDiary(final Path folder) {
    this.folder = folder;
  }

  /** The diary of {@code data}, created on first use; empty if the folder can't be written. */
  static synchronized Optional<GameDiary> forGame(final GameData data) {
    final GameDiary existing = DIARIES.get(data);
    if (existing != null) {
      return Optional.of(existing);
    }
    final Optional<Path> root = DiarySettings.folder();
    if (root.isEmpty()) {
      return Optional.empty();
    }
    try {
      Files.createDirectories(root.get());
      final Path folder = newGameFolder(root.get(), data.getGameName());
      final GameDiary diary = new GameDiary(folder);
      diary.writeHeader(data);
      DIARIES.put(data, diary);
      prune(root.get(), DiarySettings.MAX_GAMES);
      return Optional.of(diary);
    } catch (final IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  private static Path newGameFolder(final Path root, final String gameName) throws IOException {
    final String base = LocalDateTime.now().format(FOLDER_TIME) + "_" + safeName(gameName);
    Path folder = root.resolve(base);
    for (int i = 2; Files.exists(folder); i++) {
      folder = root.resolve(base + "_" + i);
    }
    return Files.createDirectories(folder);
  }

  static String safeName(final String name) {
    final String safe =
        (name == null ? "partita" : name).replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_");
    return safe.length() > 60 ? safe.substring(0, 60) : safe;
  }

  /** Deletes the oldest game folders so that at most {@code keep} remain. */
  static void prune(final Path root, final int keep) throws IOException {
    final List<Path> games;
    try (Stream<Path> children = Files.list(root)) {
      games =
          children
              .filter(Files::isDirectory)
              .filter(p -> GAME_FOLDER.matcher(p.getFileName().toString()).matches())
              .sorted(Comparator.comparing(p -> p.getFileName().toString()))
              .toList();
    }
    for (int i = 0; i < games.size() - keep; i++) {
      deleteFolder(games.get(i));
    }
  }

  private static void deleteFolder(final Path folder) throws IOException {
    try (Stream<Path> walk = Files.walk(folder)) {
      for (final Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  private void writeHeader(final GameData data) {
    String version;
    try {
      version = ProductVersionReader.getCurrentVersion().toString();
    } catch (final RuntimeException e) {
      version = "?";
    }
    text(
        "DIARIO DELLE DECISIONI DELL'AI - "
            + data.getGameName()
            + CRLF
            + "Partita iniziata (o caricata) il "
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            + ", build "
            + version
            + CRLF
            + "Legenda: win% = probabilita' di vittoria stimata dall'AI (simulazione); soglie"
            + " desiderata/minima = rischio; priorita' = punteggio con cui l'AI ordina i bersagli"
            + " (quota VC = parte dovuta alle victory city, 0 per Hard;"
            + " quota piano = parte dovuta agli obiettivi del piano dello stato maggiore);"
            + " fronte/rischio = fronte del bersaglio e rischio effettivo (cursore + piano);"
            + " valore = valore strategico del territorio (produzione + victory city)."
            + CRLF
            + "Dettagli completi (una riga per evento) in "
            + JSON_FILE
            + "."
            + CRLF);
    final JsonObject json = new JsonObject();
    json.addProperty("evento", "inizio_partita");
    json.addProperty("mappa", data.getGameName());
    json.addProperty("build", version);
    json.addProperty("ora", LocalDateTime.now().toString());
    json(json);
  }

  /** Appends {@code text} (lines separated by "\n") to the Italian diary, with Windows ends. */
  synchronized void text(final String text) {
    append(TEXT_FILE, text.replace("\r\n", "\n").replace("\n", CRLF));
  }

  synchronized void json(final JsonObject event) {
    append(JSON_FILE, GSON.toJson(event) + "\n");
  }

  /**
   * A nation's turn starts in the diary: first closes the turn still open in another nation's
   * recorder (a nation with nothing to place gets no place phase, so its turn would otherwise close
   * only when its next turn starts, with its "Tempo AI" line under another nation's turn).
   */
  void turnStarted(final Runnable closeThisTurn) {
    final Runnable previous;
    synchronized (this) {
      previous = openTurnCloser;
      openTurnCloser = closeThisTurn;
    }
    if (previous != null && previous != closeThisTurn) {
      previous.run();
    }
  }

  /** The turn opened with {@code closeThisTurn} was closed by its own recorder. */
  synchronized void turnEnded(final Runnable closeThisTurn) {
    if (openTurnCloser == closeThisTurn) {
      openTurnCloser = null;
    }
  }

  synchronized Optional<Objectives> previousObjectives(final String player) {
    return Optional.ofNullable(previousObjectives.get(player));
  }

  synchronized void setObjectives(final String player, final Objectives objectives) {
    previousObjectives.put(player, objectives);
  }

  private void append(final String file, final String content) {
    try {
      Files.writeString(
          folder.resolve(file),
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (final IOException e) {
      throw new DiaryException(e);
    }
  }

  /** A diary write failed; caught by the recorder, never reaches the AI. */
  static final class DiaryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DiaryException(final Throwable cause) {
      super(cause);
    }
  }
}
