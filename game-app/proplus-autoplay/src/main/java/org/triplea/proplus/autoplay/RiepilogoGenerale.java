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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
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

    configurations(md, games, alliances, vcRounds, maxRound);
    landingsAndTransports(md, games, alliances, maxRound);
    alliedCoordination(md, games, alliances, maxRound);

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

  /** Rounds for the VC columns: a third, two thirds and the end (12 rounds: 4, 8, 12). */
  static List<Integer> vcRounds(final int maxRound) {
    final TreeSet<Integer> set = new TreeSet<>();
    set.add(Math.max(1, (maxRound + 2) / 3));
    set.add(Math.max(1, (2 * maxRound + 2) / 3));
    set.add(maxRound);
    return new ArrayList<>(set);
  }

  /** "Pro+ A / Pro+ 1", "Hard / Hard": the game's configuration (its seed aside). */
  static String configuration(final JsonObject json, final List<String> alliances) {
    final JsonObject p = json.getAsJsonObject("parametri");
    return join(
        alliances,
        a -> {
          final String type = text(p.getAsJsonObject("tipi"), a);
          return AutoplayOptions.HARD.equals(type)
              ? Riepilogo.typeLabel(type)
              : Riepilogo.typeLabel(type) + " " + text(p.getAsJsonObject("piani"), a);
        });
  }

  /**
   * One row per configuration, the mean of its games (seeds): VC at the VC rounds, victories with
   * their round, final PUs; then the reading of the enemy per round (mean probability of the real
   * plan, games where it was the most probable).
   */
  private static void configurations(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final List<Integer> vcRounds,
      final int maxRound) {
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() != null) {
        byConfig
            .computeIfAbsent(configuration(g.json(), alliances), k -> new ArrayList<>())
            .add(g.json());
      }
    }
    md.append("\n## Per configurazione (media dei semi)\n\n| Configurazione | Partite | ");
    vcRounds.forEach(r -> md.append("VC R").append(r).append(" | "));
    md.append("Vittoria (round) | PU finali |\n|---|---|");
    vcRounds.forEach(r -> md.append("---|"));
    md.append("---|---|\n");
    for (final Map.Entry<String, List<JsonObject>> c : byConfig.entrySet()) {
      final List<JsonObject> list = c.getValue();
      md.append("| ").append(c.getKey()).append(" | ").append(list.size()).append(" | ");
      for (final int r : vcRounds) {
        md.append(
                join(
                    alliances,
                    a ->
                        mean(
                            list.stream()
                                .map(j -> round(j, r))
                                .filter(Objects::nonNull)
                                .map(
                                    round ->
                                        round
                                            .getAsJsonObject("vc")
                                            .getAsJsonObject(a)
                                            .get("numero")
                                            .getAsDouble())
                                .toList())))
            .append(" | ");
      }
      final List<String> wins = new ArrayList<>();
      for (final JsonObject j : list) {
        final JsonObject outcome = j.getAsJsonObject("esito");
        final JsonObject last = lastRound(j);
        if (outcome.has("vincitore") && last != null) {
          wins.add(
              Riepilogo.allianceLabel(outcome.get("vincitore").getAsString())
                  + " R"
                  + last.get("round").getAsInt());
        }
      }
      md.append(wins.isEmpty() ? "nessuna" : String.join(", ", wins)).append(" | ");
      md.append(
              join(
                  alliances,
                  a ->
                      mean(
                          list.stream()
                              .filter(j -> lastRound(j) != null)
                              .map(j -> (double) sum(j, lastRound(j), a, "pu"))
                              .toList())))
          .append(" |\n");
    }

    md.append(
        "\nLettura dell'avversario per configurazione: probabilita' media del piano reale e"
            + " partite in cui era il piu' probabile.\n\n| Configurazione | Alleanza |");
    for (int r = 1; r <= maxRound; r++) {
      md.append(" R").append(r).append(" |");
    }
    md.append(" Media |\n|---|---|");
    for (int r = 1; r <= maxRound; r++) {
      md.append("---|");
    }
    md.append("---|\n");
    for (final Map.Entry<String, List<JsonObject>> c : byConfig.entrySet()) {
      for (final String a : alliances) {
        final StringBuilder row = new StringBuilder();
        double sumAll = 0;
        int nAll = 0;
        int okAll = 0;
        for (int r = 1; r <= maxRound; r++) {
          double sumRound = 0;
          int n = 0;
          int ok = 0;
          for (final JsonObject j : c.getValue()) {
            final JsonObject l = reading(j, r, a);
            if (l != null && l.has("prob_piano_reale")) {
              sumRound += l.get("prob_piano_reale").getAsDouble();
              n++;
              ok += l.get("corretta").getAsBoolean() ? 1 : 0;
            }
          }
          sumAll += sumRound;
          nAll += n;
          okAll += ok;
          row.append(' ')
              .append(n == 0 ? "-" : Riepilogo.percent(sumRound / n) + " " + ok + "/" + n)
              .append(" |");
        }
        if (nAll > 0) {
          md.append("| ")
              .append(c.getKey())
              .append(" | ")
              .append(Riepilogo.allianceLabel(a))
              .append(" |")
              .append(row)
              .append(' ')
              .append(Riepilogo.percent(sumAll / nAll))
              .append(" (OK ")
              .append(Riepilogo.percent((double) okAll / nAll))
              .append(") |\n");
        }
      }
    }
  }

  /**
   * Per game: first real landing per alliance and of the Western Allies in Europe, landed units and
   * idle transports per alliance over the game, transports at the end, blocked operations. Per
   * configuration: games with an Allied landing by round 6, mean first-landing round, landed units
   * and idle transports per round (mean of the seeds), blocked operations with their reasons.
   */
  private static void landingsAndTransports(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final int maxRound) {
    md.append(
"""

## Sbarchi veri e trasporti

""");
    md.append(
        "Sbarco vero = unita' di terra scaricate da trasporti su un territorio nemico a inizio"
            + " turno (assalto anfibio o territorio preso nel turno). Trasporti inattivi = fermi e"
            + " senza carico a fine movimento della nazione (somma dei round). Operazioni bloccate"
            + " = turni con stato \"bloccata\" nel diario.\n\n| Partita |");
    alliances.forEach(
        a -> md.append(" Primo sbarco vero ").append(Riepilogo.allianceLabel(a)).append(" |"));
    md.append(
        " Primo sbarco alleato in Europa | Unita' sbarcate | Trasporti inattivi |"
            + " Trasporti a fine partita | Operazioni bloccate |\n|---|");
    alliances.forEach(a -> md.append("---|"));
    md.append("---|---|---|---|---|\n");
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      final JsonObject j = g.json();
      final JsonObject first =
          j.has("primo_sbarco_vero") ? j.getAsJsonObject("primo_sbarco_vero") : new JsonObject();
      md.append("| ").append(g.folder()).append(" |");
      for (final String a : alliances) {
        md.append(' ')
            .append(Riepilogo.landingText(first.has(a) ? first.getAsJsonObject(a) : null))
            .append(" |");
      }
      md.append(' ').append(Riepilogo.firstLandingText(j)).append(" | ");
      md.append(join(alliances, a -> String.valueOf(landedTotal(j, a)))).append(" | ");
      md.append(join(alliances, a -> String.valueOf(idleTotal(j, a)))).append(" | ");
      final JsonObject last = lastRound(j);
      final List<String> transports = new ArrayList<>();
      if (last != null) {
        for (final String a : alliances) {
          for (final JsonElement m : j.getAsJsonObject("alleanze").getAsJsonArray(a)) {
            final JsonObject n = last.getAsJsonObject("nazioni").getAsJsonObject(m.getAsString());
            if (n != null && n.has("trasporti")) {
              transports.add(m.getAsString() + " " + n.get("trasporti").getAsInt());
            }
          }
        }
      }
      md.append(transports.isEmpty() ? "-" : String.join(", ", transports)).append(" | ");
      md.append(blockedText(List.of(j), 3)).append(" |\n");
    }

    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() != null) {
        byConfig
            .computeIfAbsent(configuration(g.json(), alliances), k -> new ArrayList<>())
            .add(g.json());
      }
    }
    md.append(
        "\nPer configurazione: partite con primo sbarco vero alleato in Europa entro R6, round medio del"
            + " primo sbarco vero (partite con sbarco), unita' sbarcate e trasporti inattivi per"
            + " round (media dei semi, Asse / Alleati).\n\n| Configurazione | Alleati in Europa entro R6 |"
            + " Primo sbarco medio |");
    for (int r = 1; r <= maxRound; r++) {
      md.append(" R").append(r).append(" sbarcate | R").append(r).append(" inattivi |");
    }
    md.append("\n|---|---|---|");
    for (int r = 1; r <= maxRound; r++) {
      md.append("---|---|");
    }
    md.append('\n');
    for (final Map.Entry<String, List<JsonObject>> c : byConfig.entrySet()) {
      final List<JsonObject> list = c.getValue();
      final long byR6 =
          list.stream().filter(j -> europeRound(j) > 0 && europeRound(j) <= 6).count();
      md.append("| ")
          .append(c.getKey())
          .append(" | ")
          .append(byR6)
          .append('/')
          .append(list.size())
          .append(" | ")
          .append(
              join(
                  alliances,
                  a ->
                      mean(
                          list.stream()
                              .map(j -> (double) firstRound(j, a))
                              .filter(x -> x > 0)
                              .toList())))
          .append(" |");
      for (int r = 1; r <= maxRound; r++) {
        final int rr = r;
        md.append(' ')
            .append(
                join(
                    alliances,
                    a ->
                        mean(
                            list.stream()
                                .map(j -> round(j, rr))
                                .filter(Objects::nonNull)
                                .map(
                                    round ->
                                        round.has("unita_sbarcate")
                                                && round.getAsJsonObject("unita_sbarcate").has(a)
                                            ? round
                                                .getAsJsonObject("unita_sbarcate")
                                                .get(a)
                                                .getAsDouble()
                                            : 0.0)
                                .toList())))
            .append(" | ")
            .append(
                join(
                    alliances,
                    a ->
                        mean(
                            list.stream()
                                .filter(j -> round(j, rr) != null)
                                .map(j -> (double) Riepilogo.idleTransports(j, round(j, rr), a))
                                .toList())))
            .append(" |");
      }
      md.append('\n');
    }
    md.append(
"""

Attacchi tedeschi alle isole britanniche (Sea Lion), per partita:

""");
    boolean anySeaLion = false;
    for (final Game g : games) {
      if (g.json() != null
          && g.json().has("attacchi_regno_unito")
          && !g.json().getAsJsonArray("attacchi_regno_unito").isEmpty()) {
        anySeaLion = true;
        md.append("- ")
            .append(g.folder())
            .append(": ")
            .append(Riepilogo.seaLionText(g.json().getAsJsonArray("attacchi_regno_unito")))
            .append('\n');
      }
    }
    if (!anySeaLion) {
      md.append("- nessuno\n");
    }
    md.append(
"""

Operazioni bloccate per configurazione (turni bloccati, motivi piu' frequenti):

""");
    for (final Map.Entry<String, List<JsonObject>> c : byConfig.entrySet()) {
      md.append("- ")
          .append(c.getKey())
          .append(": ")
          .append(blockedText(c.getValue(), 4))
          .append('\n');
    }
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS: American landings in western Europe and the first bridgehead held
   * for 2 rounds, per game and per configuration.
   */
  private static void alliedCoordination(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final int maxRound) {
    md.append(
"""

## Coordinamento alleato: sbarchi USA e teste di ponte

""");
    md.append(
"""
Europa occidentale = Francia (Normandy Bordeaux, France, Southern France), Benelux, Norvegia, Danimarca, Germania. Testa di ponte tenuta = territorio dello sbarco vero britannico/americano ancora degli Alleati a fine del suo round e del successivo.

| Partita | Primo sbarco USA in Europa occ. | Unita' USA sbarcate per round | Prima testa di ponte tenuta >= 2 round | Operazione in stallo |
|---|---|---|---|---|
""");
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      final JsonObject j = g.json();
      final int first = usWestRound(j);
      md.append("| ")
          .append(g.folder())
          .append(" | ")
          .append(first > 0 ? "R" + first : "nessuno")
          .append(" | ")
          .append(Riepilogo.usLandedText(j))
          .append(" | ")
          .append(
              Riepilogo.landingText(
                  j.has("prima_testa_di_ponte") ? j.getAsJsonObject("prima_testa_di_ponte") : null))
          .append(" | ")
          .append(stallText(j))
          .append(" |\n");
    }
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() != null) {
        byConfig
            .computeIfAbsent(configuration(g.json(), alliances), k -> new ArrayList<>())
            .add(g.json());
      }
    }
    md.append(
        "\nPer configurazione: partite con sbarco USA in Europa occidentale entro R7, partite con"
            + " testa di ponte tenuta >= 2 round (round medio), unita' USA sbarcate in Europa"
            + " occidentale per round (media dei semi).\n\n| Configurazione | Sbarco USA entro R7 |"
            + " Sbarco USA entro R9 | Testa di ponte >= 2 round | Round medio | In stallo |");
    for (int r = 1; r <= maxRound; r++) {
      md.append(" R").append(r).append(" |");
    }
    md.append("\n|---|---|---|---|---|---|");
    for (int r = 1; r <= maxRound; r++) {
      md.append("---|");
    }
    md.append('\n');
    for (final Map.Entry<String, List<JsonObject>> c : byConfig.entrySet()) {
      final List<JsonObject> list = c.getValue();
      final long byR7 =
          list.stream().filter(j -> usWestRound(j) > 0 && usWestRound(j) <= 7).count();
      final long byR9 =
          list.stream().filter(j -> usWestRound(j) > 0 && usWestRound(j) <= 9).count();
      final long stalled = list.stream().filter(j -> stallRound(j) > 0).count();
      final List<Double> heads =
          list.stream()
              .filter(j -> j.has("prima_testa_di_ponte"))
              .map(j -> j.getAsJsonObject("prima_testa_di_ponte").get("round").getAsDouble())
              .toList();
      md.append("| ")
          .append(c.getKey())
          .append(" | ")
          .append(byR7)
          .append('/')
          .append(list.size())
          .append(" | ")
          .append(byR9)
          .append('/')
          .append(list.size())
          .append(" | ")
          .append(heads.size())
          .append('/')
          .append(list.size())
          .append(" | ")
          .append(mean(heads))
          .append(" | ")
          .append(stalled)
          .append('/')
          .append(list.size())
          .append(" |");
      for (int r = 1; r <= maxRound; r++) {
        final String key = String.valueOf(r);
        md.append(' ')
            .append(
                mean(
                    list.stream()
                        .filter(j -> j.has("usa_sbarcate_ovest"))
                        .map(
                            j ->
                                j.getAsJsonObject("usa_sbarcate_ovest").has(key)
                                    ? j.getAsJsonObject("usa_sbarcate_ovest").get(key).getAsDouble()
                                    : 0.0)
                        .toList()))
            .append(" |");
      }
      md.append('\n');
    }
  }

  /** First round an amphibious operation of the game was in a stall (diary), 0 if never. */
  static int stallRound(final JsonObject json) {
    if (!json.has("operazioni")) {
      return 0;
    }
    int first = 0;
    for (final JsonElement e : json.getAsJsonArray("operazioni")) {
      final JsonObject o = e.getAsJsonObject();
      if (o.has("accumulo")
          && o.getAsJsonObject("accumulo").has("stallo")
          && o.getAsJsonObject("accumulo").get("stallo").getAsBoolean()) {
        final int r = o.get("round").getAsInt();
        first = first == 0 ? r : Math.min(first, r);
      }
    }
    return first;
  }

  /** Eg "dal R7", or "no". */
  static String stallText(final JsonObject json) {
    final int r = stallRound(json);
    return r > 0 ? "dal R" + r : "no";
  }

  /** Round of the first American landing in western Europe, 0 if none. */
  static int usWestRound(final JsonObject json) {
    if (!json.has("usa_sbarcate_ovest")) {
      return 0;
    }
    for (final Map.Entry<String, JsonElement> e :
        json.getAsJsonObject("usa_sbarcate_ovest").entrySet()) {
      if (e.getValue().getAsInt() > 0) {
        return Integer.parseInt(e.getKey());
      }
    }
    return 0;
  }

  /** Round of the first real British/American landing in Europe, 0 if none. */
  static int europeRound(final JsonObject json) {
    return json.has("primo_sbarco_europa")
        ? json.getAsJsonObject("primo_sbarco_europa").get("round").getAsInt()
        : 0;
  }

  /** Round of the alliance's first real landing, 0 if none. */
  static int firstRound(final JsonObject json, final String alliance) {
    if (!json.has("primo_sbarco_vero")
        || !json.getAsJsonObject("primo_sbarco_vero").has(alliance)) {
      return 0;
    }
    return json.getAsJsonObject("primo_sbarco_vero")
        .getAsJsonObject(alliance)
        .get("round")
        .getAsInt();
  }

  private static int landedTotal(final JsonObject json, final String alliance) {
    int total = 0;
    for (final JsonElement e : json.getAsJsonArray("round")) {
      final JsonObject r = e.getAsJsonObject();
      if (r.has("unita_sbarcate") && r.getAsJsonObject("unita_sbarcate").has(alliance)) {
        total += r.getAsJsonObject("unita_sbarcate").get(alliance).getAsInt();
      }
    }
    return total;
  }

  private static int idleTotal(final JsonObject json, final String alliance) {
    int total = 0;
    for (final JsonElement e : json.getAsJsonArray("round")) {
      total += Riepilogo.idleTransports(json, e.getAsJsonObject(), alliance);
    }
    return total;
  }

  /**
   * Eg "Overlord USA 5 (scorta insufficiente per 110 Sea Zone 3; ...)": blocked turns per operation
   * and its most frequent reasons (numbers left out, so the same reason counts once).
   */
  static String blockedText(final List<JsonObject> games, final int reasons) {
    final Map<String, Integer> turns = new TreeMap<>();
    final Map<String, Map<String, Integer>> why = new TreeMap<>();
    for (final JsonObject j : games) {
      if (!j.has("operazioni")) {
        continue;
      }
      for (final JsonElement e : j.getAsJsonArray("operazioni")) {
        final JsonObject o = e.getAsJsonObject();
        if (!"bloccata".equals(text(o, "stato"))) {
          continue;
        }
        final String op = text(o, "nazione") + " " + text(o, "operazione");
        turns.merge(op, 1, Integer::sum);
        for (final String r : text(o, "motivo").split("; ")) {
          final String key = r.replaceAll("[0-9]+%?", "N").replaceAll("\\(.*\\)", "").trim();
          if (!key.isEmpty() && !"-".equals(key)) {
            why.computeIfAbsent(op, k -> new TreeMap<>()).merge(key, 1, Integer::sum);
          }
        }
      }
    }
    if (turns.isEmpty()) {
      return "nessuna";
    }
    final List<String> parts = new ArrayList<>();
    turns.forEach(
        (op, n) -> {
          final List<String> top =
              why.getOrDefault(op, Map.of()).entrySet().stream()
                  .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                  .limit(reasons)
                  .map(e -> e.getKey() + " " + e.getValue())
                  .toList();
          parts.add(op + " " + n + (top.isEmpty() ? "" : " (" + String.join("; ", top) + ")"));
        });
    return String.join("; ", parts);
  }

  private static JsonObject reading(final JsonObject json, final int r, final String alliance) {
    final JsonObject round = round(json, r);
    final JsonObject staff =
        round == null ? null : round.getAsJsonObject("stato_maggiore").getAsJsonObject(alliance);
    return staff != null && staff.has("lettura") ? staff.getAsJsonObject("lettura") : null;
  }

  private static String mean(final List<Double> values) {
    if (values.isEmpty()) {
      return "-";
    }
    final double m = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    return m == Math.rint(m) ? String.valueOf((long) m) : String.format(Locale.ROOT, "%.1f", m);
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
