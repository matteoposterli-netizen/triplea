package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Directives read from a JSON file (tests, hand trials). The path is a folder or a file:
 *
 * <ul>
 *   <li>a folder: {@code direttive-R<round>-<alliance>.json} in it (the names of the files the
 *       diary writes, so a logged game can be edited and played again);
 *   <li>a file: {@code {"<alliance>": {"<round>": {directives}}}} or {@code {"<round>":
 *       {directives}}} (round as "3" or "R3"), or one directive object whose {@code round} and
 *       {@code alleanza} match.
 * </ul>
 *
 * <p>No directive for the round (missing file or key) = no decision: the rules decide, with a
 * notice. A file that is not JSON throws, so the directives count as invalid (same fallback, with
 * the error in the notice).
 */
public final class DecisoreFile implements Decisore {
  private final String percorso;

  public DecisoreFile(final String percorso) {
    this.percorso = percorso == null ? "" : percorso.strip();
  }

  @Override
  public String nome() {
    return FILE;
  }

  @Override
  public Optional<JsonObject> decidi(final JsonObject stato, final Contesto contesto) {
    if (percorso.isEmpty()) {
      return Optional.empty();
    }
    final Path path = Path.of(percorso);
    final String alliance = contesto.alleanza();
    final int round = contesto.round();
    if (Files.isDirectory(path)) {
      return read(path.resolve(fileName(round, alliance)));
    }
    return read(path).flatMap(json -> select(json, alliance, round));
  }

  /** Eg "direttive-R3-Allies.json". */
  public static String fileName(final int round, final String alliance) {
    return "direttive-R" + round + "-" + alliance + ".json";
  }

  private static Optional<JsonObject> read(final Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      final JsonElement json =
          JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
      if (!json.isJsonObject()) {
        throw new IllegalArgumentException(file.getFileName() + " non e' un oggetto JSON");
      }
      return Optional.of(json.getAsJsonObject());
    } catch (final IOException | com.google.gson.JsonParseException e) {
      throw new IllegalArgumentException(
          "file " + file.getFileName() + " non leggibile: " + e.getMessage(), e);
    }
  }

  static Optional<JsonObject> select(
      final JsonObject json, final String alliance, final int round) {
    if (json.has("piano") || json.has("postura")) {
      final boolean sameRound = !json.has("round") || json.get("round").getAsInt() == round;
      final boolean sameAlliance =
          !json.has("alleanza") || json.get("alleanza").getAsString().equals(alliance);
      return sameRound && sameAlliance ? Optional.of(json) : Optional.empty();
    }
    final JsonObject rounds =
        json.has(alliance) && json.get(alliance).isJsonObject()
            ? json.getAsJsonObject(alliance)
            : json;
    for (final String key : new String[] {String.valueOf(round), "R" + round}) {
      if (rounds.has(key) && rounds.get(key).isJsonObject()) {
        return Optional.of(rounds.getAsJsonObject(key));
      }
    }
    return Optional.empty();
  }
}
