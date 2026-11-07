package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Comparison of several autoplay games: {@code riepilogo-generale.md} from the {@code
 * riepilogo.json} of every sub-folder (one per game). A sub-folder without summary is listed as
 * failed.
 *
 * <p>Command line: {@code confronto <folder> [--titolo TEXT]}; the file is written in the folder.
 */
@Slf4j
final class RiepilogoGenerale {
  static final String FILE = "riepilogo-generale.md";

  private RiepilogoGenerale() {}

  static int run(final String[] args) throws IOException {
    if (args.length == 0) {
      throw new IllegalArgumentException("Uso: confronto <cartella> [--titolo TESTO]");
    }
    final Path folder = Path.of(args[0]);
    String title = "Autoplay Pro+";
    for (int i = 1; i + 1 < args.length; i += 2) {
      if ("--titolo".equals(args[i])) {
        title = args[i + 1];
      }
    }
    final List<Path> games;
    try (Stream<Path> s = Files.list(folder)) {
      games = s.filter(Files::isDirectory).sorted(Comparator.comparing(Path::getFileName)).toList();
    }
    final List<Game> list = new ArrayList<>();
    for (final Path g : games) {
      final Path json = g.resolve(Riepilogo.JSON_FILE);
      if (Files.exists(json)) {
        list.add(
            new Game(
                g.getFileName().toString(),
                JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8))
                    .getAsJsonObject()));
      } else {
        list.add(new Game(g.getFileName().toString(), null));
      }
    }
    Files.writeString(folder.resolve(FILE), markdown(title, list), StandardCharsets.UTF_8);
    log.info("Scritto {} ({} partite)", folder.resolve(FILE), list.size());
    return 0;
  }

  record Game(String folder, JsonObject json) {}

  static String markdown(final String title, final List<Game> games) {
    final StringBuilder md = new StringBuilder();
    md.append("# ").append(title).append("\n\n");
    final int maxRound =
        games.stream()
            .filter(g -> g.json() != null)
            .mapToInt(g -> g.json().getAsJsonObject("parametri").get("round_max").getAsInt())
            .max()
            .orElse(8);
    final List<Integer> vcRounds = vcRounds(maxRound);
    final List<String> alliances = alliances(games);

    md.append("## Confronto\n\n| Partita | ");
    alliances.forEach(a -> md.append(Riepilogo.allianceLabel(a)).append(" | "));
    md.append("Esito | ");
    vcRounds.forEach(r -> md.append("VC R").append(r).append(" | "));
    md.append("PU finali | Stato | Durata |\n|---|");
    alliances.forEach(a -> md.append("---|"));
    md.append("---|");
    vcRounds.forEach(r -> md.append("---|"));
    md.append("---|---|---|\n");
    for (final Game g : games) {
      md.append("| ").append(g.folder()).append(" | ");
      if (g.json() == null) {
        alliances.forEach(a -> md.append("- | "));
        md.append("**FALLITA** (nessun riepilogo) | ");
        vcRounds.forEach(r -> md.append("- | "));
        md.append("- | - | - |\n");
        continue;
      }
      final JsonObject j = g.json();
      final JsonObject p = j.getAsJsonObject("parametri");
      for (final String a : alliances) {
        final String type = text(p.getAsJsonObject("tipi"), a);
        md.append(Riepilogo.typeLabel(type));
        if (!AutoplayOptions.HARD.equals(type)) {
          md.append(' ').append(text(p.getAsJsonObject("piani"), a));
        }
        md.append(" | ");
      }
      md.append(j.getAsJsonObject("esito").get("testo").getAsString()).append(" | ");
      for (final int r : vcRounds) {
        final JsonObject round = round(j, r);
        md.append(
                round == null
                    ? "-"
                    : join(
                        alliances,
                        a -> {
                          final JsonObject vc = round.getAsJsonObject("vc");
                          return vc.has(a)
                              ? vc.getAsJsonObject(a).get("numero").getAsString()
                              : "-";
                        }))
            .append(" | ");
      }
      final JsonObject last = lastRound(j);
      md.append(last == null ? "-" : join(alliances, a -> String.valueOf(sum(j, last, a, "pu"))))
          .append(" | ")
          .append(j.get("stato").getAsString())
          .append(" | ")
          .append(Riepilogo.duration(j.get("durata_s").getAsLong()))
          .append(" |\n");
    }
    md.append("\nVC e PU finali: ")
        .append(String.join(" / ", alliances.stream().map(Riepilogo::allianceLabel).toList()))
        .append(" (PU = somma della cassa delle nazioni a fine round).\n");

    md.append(
"""

## Lettura dell'avversario per round

""");
    md.append(
        "Probabilita' assegnata al piano reale dell'avversario (OK = piano piu' probabile ="
            + " piano reale). Solo partite Pro+ contro Pro+.\n\n| Partita | Alleanza |");
    for (int r = 1; r <= maxRound; r++) {
      md.append(" R").append(r).append(" |");
    }
    md.append("\n|---|---|");
    for (int r = 1; r <= maxRound; r++) {
      md.append("---|");
    }
    md.append('\n');
    boolean anyReading = false;
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      for (final String a : alliances) {
        final StringBuilder row = new StringBuilder();
        boolean any = false;
        for (int r = 1; r <= maxRound; r++) {
          final JsonObject round = round(g.json(), r);
          final JsonObject staff =
              round == null ? null : round.getAsJsonObject("stato_maggiore").getAsJsonObject(a);
          final JsonObject l =
              staff != null && staff.has("lettura") ? staff.getAsJsonObject("lettura") : null;
          if (l != null && l.has("prob_piano_reale")) {
            any = true;
            row.append(' ')
                .append(Riepilogo.percent(l.get("prob_piano_reale").getAsDouble()))
                .append(l.get("corretta").getAsBoolean() ? " OK" : "")
                .append(" |");
          } else {
            row.append(" - |");
          }
        }
        if (any) {
          anyReading = true;
          md.append("| ")
              .append(g.folder())
              .append(" | ")
              .append(Riepilogo.allianceLabel(a))
              .append(" |")
              .append(row)
              .append('\n');
        }
      }
    }
    if (!anyReading) {
      md.append("| - | - |");
      for (int r = 1; r <= maxRound; r++) {
        md.append(" - |");
      }
      md.append('\n');
    }

    md.append("\n## PU in difesa vs piano (somma di tutti i round)\n\n| Partita |");
    alliances.forEach(
        a -> md.append(' ').append(Riepilogo.allianceLabel(a)).append(" difesa / piano / spesi |"));
    md.append("\n|---|");
    alliances.forEach(a -> md.append("---|"));
    md.append('\n');
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      md.append("| ").append(g.folder()).append(" |");
      for (final String a : alliances) {
        int defense = 0;
        int plan = 0;
        int spent = 0;
        for (final JsonElement e : g.json().getAsJsonArray("round")) {
          final JsonObject r = e.getAsJsonObject();
          defense += sum(g.json(), r, a, "pu_difesa");
          plan += sum(g.json(), r, a, "pu_piano");
          spent += sum(g.json(), r, a, "pu_spesi");
        }
        md.append(String.format(Locale.ROOT, " %d / %d / %d |", defense, plan, spent));
      }
      md.append('\n');
    }
    return md.toString();
  }

  /** Rounds for the VC columns: half, three quarters and the end (8 rounds: 4, 6, 8). */
  static List<Integer> vcRounds(final int maxRound) {
    final TreeSet<Integer> set = new TreeSet<>();
    set.add(Math.max(1, (maxRound + 1) / 2));
    set.add(Math.max(1, (3 * maxRound + 3) / 4));
    set.add(maxRound);
    return new ArrayList<>(set);
  }

  private static List<String> alliances(final List<Game> games) {
    final TreeSet<String> set = new TreeSet<>();
    games.stream()
        .filter(g -> g.json() != null)
        .forEach(g -> set.addAll(g.json().getAsJsonObject("alleanze").keySet()));
    // Axis first, as in the plans' names (Asse / Alleati)
    return new ArrayList<>(set.descendingSet());
  }

  private static JsonObject round(final JsonObject json, final int round) {
    for (final JsonElement e : json.getAsJsonArray("round")) {
      final JsonObject r = e.getAsJsonObject();
      if (r.get("round").getAsInt() == round && r.get("completo").getAsBoolean()) {
        return r;
      }
    }
    return null;
  }

  private static JsonObject lastRound(final JsonObject json) {
    final JsonArray rounds = json.getAsJsonArray("round");
    return rounds.isEmpty() ? null : rounds.get(rounds.size() - 1).getAsJsonObject();
  }

  private static int sum(
      final JsonObject json, final JsonObject round, final String alliance, final String key) {
    final JsonArray members = json.getAsJsonObject("alleanze").getAsJsonArray(alliance);
    if (members == null) {
      return 0;
    }
    int total = 0;
    for (final JsonElement m : members) {
      final JsonObject n = round.getAsJsonObject("nazioni").getAsJsonObject(m.getAsString());
      if (n != null && n.has(key)) {
        total += n.get(key).getAsInt();
      }
    }
    return total;
  }

  private static String join(
      final List<String> alliances, final java.util.function.Function<String, String> value) {
    return String.join(" / ", alliances.stream().map(value).toList());
  }

  private static String text(final JsonObject o, final String key) {
    return o != null && o.has(key) ? o.get(key).getAsString() : "-";
  }
}
