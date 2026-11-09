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
import java.util.Set;
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
    plansAndPostures(md, games, alliances);
    landingsAndTransports(md, games, alliances, maxRound);
    alliedCoordination(md, games, alliances, maxRound);
    lostTransports(md, games, alliances, maxRound);
    delayTools(md, games, alliances, maxRound);
    contract(md, games, alliances);
    strategist(md, games, alliances, maxRound);
    meanRisk(md, games);

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
          if (AutoplayOptions.HARD.equals(type)) {
            return Riepilogo.typeLabel(type);
          }
          final String decider = decider(json, a);
          return Riepilogo.typeLabel(type)
              + " "
              + text(p.getAsJsonObject("piani"), a)
              + ("regole".equals(decider) ? "" : " (" + decider + ")");
        });
  }

  /** The decider of {@code alliance} in the game: regole, llm, file:... */
  static String decider(final JsonObject json, final String alliance) {
    final JsonObject p = json.getAsJsonObject("parametri");
    return p.has("decisori") && p.getAsJsonObject("decisori").has(alliance)
        ? p.getAsJsonObject("decisori").get(alliance).getAsString()
        : "regole";
  }

  /**
   * Step INTEGRAZIONE LLM: the games with the LLM strategist (one row per LLM alliance: who
   * answered, fallbacks, plan changes, postures, delay tools turned on, operations, calls, tokens,
   * cost, final VC) and the mean final VC of each configuration with the LLM against the rules.
   */
  static void strategist(
      final StringBuilder md, final List<Game> games, final List<String> alliances, final int maxRound) {
    final List<Game> llm =
        games.stream()
            .filter(g -> g.json() != null)
            .filter(g -> alliances.stream().anyMatch(a -> decider(g.json(), a).startsWith("llm")))
            .toList();
    md.append(
"""


## Stratega LLM

""");
    if (llm.isEmpty()) {
      md.append("Nessuna partita con lo stratega LLM (secret ANTHROPIC_API_KEY assente o partite"
          + " non richieste).\n");
      return;
    }
    md.append(
        "| Partita | Alleanza | Risposte | Ripieghi | Cambi piano | Posture | Strumenti accesi |"
            + " Operazioni | Chiamate | Token | Costo $ | Stratega: chiamate / $ | Ordini del turno:"
            + " chiamate / ripieghi / $ | VC R"
            + maxRound
            + " |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    double cost = 0;
    int calls = 0;
    final double[] byLevel = new double[2];
    final int[] callsByLevel = new int[2];
    for (final Game g : llm) {
      final JsonObject st =
          g.json().has("stratega") ? g.json().getAsJsonObject("stratega") : new JsonObject();
      final JsonObject last = round(g.json(), maxRound);
      for (final String a : alliances) {
        if (!decider(g.json(), a).startsWith("llm")) {
          continue;
        }
        final JsonObject x = st.has(a) ? st.getAsJsonObject(a) : new JsonObject();
        final JsonObject uso = x.has("uso") ? x.getAsJsonObject("uso") : new JsonObject();
        final double c = uso.has("costo_usd") ? uso.get("costo_usd").getAsDouble() : 0;
        final int n = uso.has("chiamate") ? uso.get("chiamate").getAsInt() : 0;
        cost += c;
        calls += n;
        final List<String> levels = List.of("stratega", "ordini");
        for (int i = 0; i < 2; i++) {
          if (uso.has("per_livello") && uso.getAsJsonObject("per_livello").has(levels.get(i))) {
            final JsonObject l = uso.getAsJsonObject("per_livello").getAsJsonObject(levels.get(i));
            byLevel[i] += l.has("costo_usd") ? l.get("costo_usd").getAsDouble() : 0;
            callsByLevel[i] += l.has("chiamate") ? l.get("chiamate").getAsInt() : 0;
          }
        }
        final long tokens =
            (uso.has("token_ingresso") ? uso.get("token_ingresso").getAsLong() : 0)
                + (uso.has("token_uscita") ? uso.get("token_uscita").getAsLong() : 0)
                + (uso.has("token_cache_lettura") ? uso.get("token_cache_lettura").getAsLong() : 0)
                + (uso.has("token_cache_scrittura")
                    ? uso.get("token_cache_scrittura").getAsLong()
                    : 0);
        md.append("| ")
            .append(g.folder())
            .append(" | ")
            .append(Riepilogo.allianceLabel(a))
            .append(" | ")
            .append(x.has("fonti") ? x.get("fonti").toString().replace("|", "/") : "-")
            .append(" | ")
            .append(x.has("ripieghi") ? x.get("ripieghi").getAsInt() : 0)
            .append('/')
            .append(x.has("decisioni") ? x.get("decisioni").getAsInt() : 0)
            .append(" | ")
            .append(x.has("cambi_piano") ? x.get("cambi_piano").getAsInt() : 0)
            .append(" | ")
            .append(x.has("posture") ? x.get("posture").toString() : "-")
            .append(" | ")
            .append(x.has("strumenti_accesi") ? x.get("strumenti_accesi").toString() : "-")
            .append(" | ")
            .append(x.has("operazioni") ? x.get("operazioni").toString() : "-")
            .append(" | ")
            .append(n)
            .append(" | ")
            .append(tokens)
            .append(" | ")
            .append(String.format(Locale.ROOT, "%.4f", c))
            .append(" | ")
            .append(level(uso, "stratega"))
            .append(" | ")
            .append(level(uso, "ordini"))
            .append(x.has("ordini_turno") && x.getAsJsonObject("ordini_turno").has("ripieghi")
                ? " (ripieghi " + x.getAsJsonObject("ordini_turno").get("ripieghi").getAsInt() + ")"
                : "")
            .append(" | ")
            .append(
                last == null
                    ? "-"
                    : join(
                        alliances,
                        al -> {
                          final JsonObject vc = last.getAsJsonObject("vc");
                          return vc.has(al)
                              ? vc.getAsJsonObject(al).get("numero").getAsString()
                              : "-";
                        }))
            .append(" |\n");
        if (x.has("motivi_ripiego")) {
          for (final JsonElement r : x.getAsJsonArray("motivi_ripiego")) {
            md.append("|  | ripiego: ").append(r.getAsString().replace("|", "/")).append(" |||||||||||||\n");
          }
        }
      }
    }
    md.append(
        String.format(
            Locale.ROOT,
            "%nTotale: %d chiamate, costo stimato $%.4f (%d partite LLM, $%.4f per partita);"
                + " Stratega %d chiamate $%.4f ($%.4f per partita), Ordini del turno %d chiamate"
                + " $%.4f ($%.4f per partita).%n",
            calls,
            cost,
            llm.size(),
            cost / llm.size(),
            callsByLevel[0],
            byLevel[0],
            byLevel[0] / llm.size(),
            callsByLevel[1],
            byLevel[1],
            byLevel[1] / llm.size()));
    md.append("\nVC finali (R")
        .append(maxRound)
        .append(") medie per configurazione, LLM contro regole con gli stessi piani:\n\n| Configurazione | Partite | ")
        .append(String.join(" | ", alliances.stream().map(Riepilogo::allianceLabel).toList()))
        .append(" |\n|---|---|")
        .append("---|".repeat(alliances.size()))
        .append('\n');
    final Map<String, List<Game>> byConfig = new LinkedHashMap<>();
    games.stream()
        .filter(g -> g.json() != null)
        .forEach(g -> byConfig.computeIfAbsent(configuration(g.json(), alliances), k -> new ArrayList<>()).add(g));
    final java.util.Set<String> plansWithLlm = new java.util.HashSet<>();
    llm.forEach(g -> plansWithLlm.add(plans(g.json(), alliances)));
    byConfig.forEach(
        (config, list) -> {
          if (!plansWithLlm.contains(plans(list.get(0).json(), alliances))) {
            return;
          }
          md.append("| ").append(config).append(" | ").append(list.size()).append(" | ");
          for (final String a : alliances) {
            final List<Double> values = new ArrayList<>();
            for (final Game g : list) {
              final JsonObject r = round(g.json(), maxRound);
              if (r != null && r.getAsJsonObject("vc").has(a)) {
                values.add(r.getAsJsonObject("vc").getAsJsonObject(a).get("numero").getAsDouble());
              }
            }
            md.append(mean(values)).append(" | ");
          }
          md.append('\n');
        });
    comparison(md, games, alliances, plansWithLlm);
  }

  /**
   * LLM step 3: per configuration with the same plans as an LLM game (rules, Haiku, Sonnet...): VC
   * at the end, real landings per alliance, operation decisions (strategist + turn orders, %
   * lancia / attendi / annulla), postures and cost per game, means of the seeds.
   */
  static void comparison(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final java.util.Set<String> plansWithLlm) {
    md.append(
        "\nConfronto per configurazione (media dei semi; operazioni = decisioni di stratega e ordini"
            + " del turno dell'alleanza LLM, o delle regole):\n\n| Configurazione | Partite | VC finali "
            + String.join(" / ", alliances.stream().map(Riepilogo::allianceLabel).toList())
            + " | Sbarchi veri "
            + String.join(" / ", alliances.stream().map(Riepilogo::allianceLabel).toList())
            + " | Lancia / attendi / annulla | Posture | Costo $ per partita |\n"
            + "|---|---|---|---|---|---|---|\n");
    final Map<String, List<Game>> byConfig = new LinkedHashMap<>();
    games.stream()
        .filter(g -> g.json() != null && plansWithLlm.contains(plans(g.json(), alliances)))
        .forEach(
            g ->
                byConfig
                    .computeIfAbsent(configuration(g.json(), alliances), k -> new ArrayList<>())
                    .add(g));
    byConfig.forEach(
        (config, list) -> {
          final java.util.List<String> vcs = new ArrayList<>();
          final java.util.List<String> landings = new ArrayList<>();
          for (final String a : alliances) {
            final List<Double> vc = new ArrayList<>();
            final List<Double> land = new ArrayList<>();
            for (final Game g : list) {
              final JsonObject last = lastCompleteRound(g.json());
              if (last != null && last.getAsJsonObject("vc").has(a)) {
                vc.add(last.getAsJsonObject("vc").getAsJsonObject(a).get("numero").getAsDouble());
              }
              land.add((double) landingsOf(g.json(), a));
            }
            vcs.add(mean(vc));
            landings.add(mean(land));
          }
          final Map<String, Integer> decisions = new LinkedHashMap<>();
          final Map<String, Integer> postures = new LinkedHashMap<>();
          double cost = 0;
          for (final Game g : list) {
            final String llmAlliance =
                alliances.stream()
                    .filter(a -> decider(g.json(), a).startsWith("llm"))
                    .findFirst()
                    .orElse(
                        alliances.contains("Allies")
                            ? "Allies"
                            : alliances.get(alliances.size() - 1));
            final JsonObject st =
                g.json().has("stratega") ? g.json().getAsJsonObject("stratega") : new JsonObject();
            final JsonObject x =
                st.has(llmAlliance) ? st.getAsJsonObject(llmAlliance) : new JsonObject();
            for (final String key : List.of("operazioni")) {
              if (x.has(key)) {
                x.getAsJsonObject(key)
                    .entrySet()
                    .forEach(e -> decisions.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum));
              }
            }
            if (x.has("ordini_turno") && x.getAsJsonObject("ordini_turno").has("operazioni")) {
              x.getAsJsonObject("ordini_turno")
                  .getAsJsonObject("operazioni")
                  .entrySet()
                  .forEach(e -> decisions.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum));
            }
            if (x.has("posture")) {
              x.getAsJsonObject("posture")
                  .entrySet()
                  .forEach(e -> postures.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum));
            }
            if (x.has("uso") && x.getAsJsonObject("uso").has("costo_usd")) {
              cost += x.getAsJsonObject("uso").get("costo_usd").getAsDouble();
            }
          }
          final int total = decisions.values().stream().mapToInt(Integer::intValue).sum();
          final String pct =
              total == 0
                  ? "-"
                  : String.format(
                      Locale.ROOT,
                      "%.0f%% / %.0f%% / %.0f%% (%d)",
                      100.0 * decisions.getOrDefault("lancia", 0) / total,
                      100.0 * decisions.getOrDefault("attendi", 0) / total,
                      100.0 * decisions.getOrDefault("annulla", 0) / total,
                      total);
          final List<String> post = new ArrayList<>();
          postures.forEach((k, v) -> post.add(k + " " + v));
          md.append("| ")
              .append(config)
              .append(" | ")
              .append(list.size())
              .append(" | ")
              .append(String.join(" / ", vcs))
              .append(" | ")
              .append(String.join(" / ", landings))
              .append(" | ")
              .append(pct)
              .append(" | ")
              .append(post.isEmpty() ? "-" : String.join(", ", post))
              .append(" | ")
              .append(cost == 0 ? "-" : String.format(Locale.ROOT, "%.4f", cost / list.size()))
              .append(" |\n");
        });
  }

  /** The last complete round of a game (null if none). */
  private static JsonObject lastCompleteRound(final JsonObject json) {
    JsonObject last = null;
    for (final JsonElement e : json.getAsJsonArray("round")) {
      if (e.getAsJsonObject().get("completo").getAsBoolean()) {
        last = e.getAsJsonObject();
      }
    }
    return last;
  }

  /** Real landings (the summary's {@code sbarchi}) of {@code alliance} in the game. */
  private static int landingsOf(final JsonObject json, final String alliance) {
    int n = 0;
    if (json.has("sbarchi")) {
      for (final JsonElement e : json.getAsJsonArray("sbarchi")) {
        if (alliance.equals(e.getAsJsonObject().get("alleanza").getAsString())) {
          n++;
        }
      }
    }
    return n;
  }

  /** Eg "12 / $0.0110" for the calls of one level of the LLM step. */
  private static String level(final JsonObject uso, final String level) {
    if (!uso.has("per_livello") || !uso.getAsJsonObject("per_livello").has(level)) {
      return "-";
    }
    final JsonObject l = uso.getAsJsonObject("per_livello").getAsJsonObject(level);
    return (l.has("chiamate") ? l.get("chiamate").getAsInt() : 0)
        + String.format(
            Locale.ROOT, " / $%.4f", l.has("costo_usd") ? l.get("costo_usd").getAsDouble() : 0);
  }

  /** LLM step: the mean effective risk (1-5) of each nation, per game (every Pro+ game). */
  static void meanRisk(final StringBuilder md, final List<Game> games) {
    final java.util.Set<String> nations = new java.util.LinkedHashSet<>();
    for (final Game g : games) {
      if (g.json() != null && g.json().has("stratega")) {
        g.json()
            .getAsJsonObject("stratega")
            .entrySet()
            .forEach(
                e -> {
                  if (e.getValue().isJsonObject()
                      && e.getValue().getAsJsonObject().has("rischio_medio")) {
                    nations.addAll(
                        e.getValue().getAsJsonObject().getAsJsonObject("rischio_medio").keySet());
                  }
                });
      }
    }
    if (nations.isEmpty()) {
      return;
    }
    md.append("\n\n## Rischio effettivo medio per nazione\n\n")
        .append("Livello 1-5 (cursore + scostamento delle direttive per nazione e fronte, -1 in")
        .append(" DIFENSIVA), media di tutti i round e fronti.\n\n| Partita | ")
        .append(String.join(" | ", nations))
        .append(" |\n|---|")
        .append("---|".repeat(nations.size()))
        .append('\n');
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("stratega")) {
        continue;
      }
      final JsonObject mean = new JsonObject();
      g.json()
          .getAsJsonObject("stratega")
          .entrySet()
          .forEach(
              e -> {
                if (e.getValue().isJsonObject()
                    && e.getValue().getAsJsonObject().has("rischio_medio")) {
                  e.getValue()
                      .getAsJsonObject()
                      .getAsJsonObject("rischio_medio")
                      .entrySet()
                      .forEach(n -> mean.add(n.getKey(), n.getValue()));
                }
              });
      if (mean.size() == 0) {
        continue;
      }
      md.append("| ").append(g.folder()).append(" | ");
      nations.forEach(
          n -> md.append(mean.has(n) ? mean.get(n).getAsString() : "-").append(" | "));
      md.append('\n');
    }
  }

  private static String plans(final JsonObject json, final List<String> alliances) {
    final JsonObject p = json.getAsJsonObject("parametri");
    return join(alliances, a -> text(p.getAsJsonObject("tipi"), a) + text(p.getAsJsonObject("piani"), a));
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
"""

Per configurazione: partite con primo sbarco vero alleato in Europa entro R6, round medio del primo sbarco vero (partite con sbarco), unita' sbarcate e trasporti inattivi per round (media dei semi, Asse / Alleati).

| Configurazione | Alleati in Europa entro R6 | Primo sbarco medio |""");
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

  /**
   * Step 3d-1-bis: per configuration and nation, the transports lost per round (sum of the games),
   * and the TUV lost in transports against the TUV gained in the battles of real landings.
   */
  static void lostTransports(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final int maxRound) {
    md.append(
"""

## Trasporti persi e sbarchi (step 3d-1-bis)

""")
        .append(
"""
Trasporti persi = trasporti sulla mappa dopo un passo e scomparsi dopo il successivo (somma delle partite della configurazione, per round). TUV sbarchi = TUV perso dal difensore meno TUV perso dall'attaccante nelle battaglie dove la nazione ha sbarcato truppe vere nel combattimento (valore dei territori non contato).

| Configurazione | Nazione | Partite |\
""");
    for (int r = 1; r <= maxRound; r++) {
      md.append(" R").append(r).append(" |");
    }
    md.append(" Totale | TUV trasporti persi | TUV sbarchi |\n|---|---|---|")
        .append("---|".repeat(maxRound + 3))
        .append('\n');
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() != null) {
        byConfig
            .computeIfAbsent(configuration(g.json(), alliances), k -> new ArrayList<>())
            .add(g.json());
      }
    }
    for (final Map.Entry<String, List<JsonObject>> c : byConfig.entrySet()) {
      final Set<String> nations = new TreeSet<>();
      for (final JsonObject j : c.getValue()) {
        for (final JsonElement e : j.getAsJsonArray("round")) {
          for (final Map.Entry<String, JsonElement> x :
              e.getAsJsonObject().getAsJsonObject("nazioni").entrySet()) {
            final JsonObject o = x.getValue().getAsJsonObject();
            if (number(o, "trasporti_persi") > 0 || number(o, "tuv_sbarchi") != 0) {
              nations.add(x.getKey());
            }
          }
        }
      }
      for (final String nation : nations) {
        final int[] perRound = new int[maxRound + 1];
        int tuvLost = 0;
        int tuvLanding = 0;
        for (final JsonObject j : c.getValue()) {
          for (final JsonElement e : j.getAsJsonArray("round")) {
            final JsonObject r = e.getAsJsonObject();
            final JsonObject n = r.getAsJsonObject("nazioni").getAsJsonObject(nation);
            if (n == null) {
              continue;
            }
            final int round = r.get("round").getAsInt();
            if (round >= 1 && round <= maxRound) {
              perRound[round] += number(n, "trasporti_persi");
            }
            tuvLost += number(n, "tuv_trasporti_persi");
            tuvLanding += number(n, "tuv_sbarchi");
          }
        }
        md.append("| ")
            .append(c.getKey())
            .append(" | ")
            .append(nation)
            .append(" | ")
            .append(c.getValue().size())
            .append(" |");
        int total = 0;
        for (int r = 1; r <= maxRound; r++) {
          md.append(' ').append(perRound[r]).append(" |");
          total += perRound[r];
        }
        md.append(' ')
            .append(total)
            .append(" | ")
            .append(tuvLost)
            .append(" | ")
            .append(tuvLanding)
            .append(" |\n");
      }
    }
  }

  /**
   * Step 3d-2: per game the round of the fall of Novgorod, Russia, Volgograd and of the capitals,
   * the Soviet income per round and the delay tools (placed and lost, hunted transports sunk); per
   * configuration the games where each key territory fell and the mean round.
   */
  /**
   * Strategic contract: decider per alliance, size of the decider's input (stato-R*.json, compact
   * JSON; tokens estimated as characters / {@link Riepilogo#CHARS_PER_TOKEN}), fallbacks to the
   * rules and directives with delay tools on.
   */
  static void contract(
      final StringBuilder md, final List<Game> games, final List<String> alliances) {
    md.append(
"""


## Contratto strategico (stato e direttive)

""")
        .append(
            "Ingresso del decisore = file stato-R<round>-<alleanza>.json nella cartella del diario"
                + " (JSON compatto); token stimati = caratteri / "
                + (int) Riepilogo.CHARS_PER_TOKEN
                + " (stima grezza, non il conteggio del modello).\n\n"
                + "| Partita | Decisori | File stato | Caratteri medi | Caratteri max | Token stimati"
                + " medi | Ripieghi sulle regole | Direttive con tattiche |\n"
                + "|---|---|---|---|---|---|---|---|\n");
    long files = 0;
    double chars = 0;
    long max = 0;
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("contratto")) {
        continue;
      }
      final JsonObject c = g.json().getAsJsonObject("contratto");
      final JsonObject d =
          g.json().getAsJsonObject("parametri").has("decisori")
              ? g.json().getAsJsonObject("parametri").getAsJsonObject("decisori")
              : new JsonObject();
      final List<String> who = new ArrayList<>();
      alliances.forEach(a -> who.add(a + " " + (d.has(a) ? d.get(a).getAsString() : "regole")));
      final long n = c.has("file_stato") ? c.get("file_stato").getAsLong() : 0;
      files += n;
      chars += n * (c.has("caratteri_medi") ? c.get("caratteri_medi").getAsDouble() : 0);
      max = Math.max(max, c.has("caratteri_max") ? c.get("caratteri_max").getAsLong() : 0);
      md.append("| ")
          .append(g.folder())
          .append(" | ")
          .append(String.join(", ", who))
          .append(" | ")
          .append(n)
          .append(" | ")
          .append(c.has("caratteri_medi") ? c.get("caratteri_medi").getAsLong() : 0)
          .append(" | ")
          .append(c.has("caratteri_max") ? c.get("caratteri_max").getAsLong() : 0)
          .append(" | ")
          .append(c.has("token_stimati_medi") ? c.get("token_stimati_medi").getAsLong() : 0)
          .append(" | ")
          .append(c.has("ripieghi_sulle_regole") ? c.get("ripieghi_sulle_regole").getAsInt() : 0)
          .append('/')
          .append(c.has("decisioni") ? c.get("decisioni").getAsInt() : 0)
          .append(" | ")
          .append(c.has("direttive_con_tattiche") ? c.get("direttive_con_tattiche").getAsInt() : 0)
          .append(" |\n");
    }
    if (files > 0) {
      md.append(
          String.format(
              Locale.ROOT,
              "%nMedia su tutte le partite: %.0f caratteri, ~%.0f token per ingresso (max %d"
                  + " caratteri, %d file).%n",
              chars / files,
              chars / files / Riepilogo.CHARS_PER_TOKEN,
              max,
              files));
    }
  }

  static void delayTools(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final int maxRound) {
    md.append(
"""


## Strumenti di ritardo e fronte russo (step 3d-2)

""")
        .append(
"""
Round di caduta = primo round in cui il territorio passa al nemico del proprietario originale ("-" = mai). Strumenti, per tutta la partita e tutte le nazioni: blocchi = fanterie di blocco terrestre, navi = navi di blocco navale, messi/persi (persi = scomparsi dalla mappa dopo essere stati messi); ritirata = unita' arretrate; caccia = zone di caccia ai trasporti / trasporti cacciati affondati nel turno.

| Partita | Configurazione |""");
    Monitor.KEY_TERRITORIES.forEach(t -> md.append(' ').append(t).append(" |"));
    md.append(
            " Capitali cadute | Blocchi messi/persi | Navi messe/perse | Ritirata |"
                + " Caccia zone/affondati |\n|---|---|")
        .append("---|".repeat(Monitor.KEY_TERRITORIES.size() + 5))
        .append('\n');
    final Map<String, List<int[]>> fallsByConfig = new LinkedHashMap<>();
    final StringBuilder income = new StringBuilder();
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      final JsonObject j = g.json();
      final String config = configuration(j, alliances);
      md.append("| ").append(g.folder()).append(" | ").append(config).append(" |");
      final int[] falls = new int[Monitor.KEY_TERRITORIES.size()];
      for (int i = 0; i < falls.length; i++) {
        falls[i] = fallRound(j, Monitor.KEY_TERRITORIES.get(i));
        md.append(' ').append(falls[i] > 0 ? "R" + falls[i] : "-").append(" |");
      }
      fallsByConfig.computeIfAbsent(config, k -> new ArrayList<>()).add(falls);
      final List<String> capitals = new ArrayList<>();
      if (j.has("capitali") && j.get("capitali").isJsonArray()) {
        for (final JsonElement e : j.getAsJsonArray("capitali")) {
          final JsonObject c = e.getAsJsonObject();
          if ("caduta".equals(text(c, "evento"))) {
            capitals.add(text(c, "territorio") + " R" + c.get("round").getAsInt());
          }
        }
      }
      final Map<String, Integer> tools = new TreeMap<>();
      final int[] soviet = new int[maxRound + 1];
      for (final JsonElement e : j.getAsJsonArray("round")) {
        final JsonObject r = e.getAsJsonObject();
        final int round = r.get("round").getAsInt();
        for (final Map.Entry<String, JsonElement> x : r.getAsJsonObject("nazioni").entrySet()) {
          final JsonObject n = x.getValue().getAsJsonObject();
          if ("Russians".equals(x.getKey()) && round >= 1 && round <= maxRound) {
            soviet[round] = number(n, "reddito");
          }
          if (n.has("ritardo")) {
            n.getAsJsonObject("ritardo")
                .entrySet()
                .forEach(d -> tools.merge(d.getKey(), d.getValue().getAsInt(), Integer::sum));
          }
        }
      }
      md.append(' ')
          .append(capitals.isEmpty() ? "-" : String.join(", ", capitals))
          .append(" | ")
          .append(tools.getOrDefault("blocchi_messi", 0))
          .append('/')
          .append(tools.getOrDefault("blocchi_persi", 0))
          .append(" | ")
          .append(tools.getOrDefault("navi_messi", 0))
          .append('/')
          .append(tools.getOrDefault("navi_persi", 0))
          .append(" | ")
          .append(tools.getOrDefault("ritirata_messi", 0))
          .append(" | ")
          .append(tools.getOrDefault("zone_caccia", 0))
          .append('/')
          .append(tools.getOrDefault("affondati_caccia", 0))
          .append(" |\n");
      income.append("| ").append(g.folder()).append(" |");
      for (int r = 1; r <= maxRound; r++) {
        income.append(' ').append(soviet[r] > 0 ? String.valueOf(soviet[r]) : "-").append(" |");
      }
      income.append('\n');
    }
    md.append(
"""

Per configurazione: partite con caduta / round medio di caduta.

| Configurazione | Partite |""");
    Monitor.KEY_TERRITORIES.forEach(t -> md.append(' ').append(t).append(" |"));
    md.append("\n|---|---|").append("---|".repeat(Monitor.KEY_TERRITORIES.size())).append('\n');
    fallsByConfig.forEach(
        (config, list) -> {
          md.append("| ").append(config).append(" | ").append(list.size()).append(" |");
          for (int i = 0; i < Monitor.KEY_TERRITORIES.size(); i++) {
            final int k = i;
            final List<Integer> rounds = list.stream().map(f -> f[k]).filter(r -> r > 0).toList();
            md.append(' ').append(rounds.size());
            if (!rounds.isEmpty()) {
              md.append(
                  String.format(
                      Locale.ROOT,
                      " / R%.1f",
                      rounds.stream().mapToInt(Integer::intValue).average().orElse(0)));
            }
            md.append(" |");
          }
          md.append('\n');
        });
    md.append("\nReddito russo per round (fine round).\n\n| Partita |");
    for (int r = 1; r <= maxRound; r++) {
      md.append(" R").append(r).append(" |");
    }
    md.append("\n|---|").append("---|".repeat(maxRound)).append('\n').append(income);
  }

  /**
   * First round {@code territory} fell (event "caduta" of {@code territori_chiave}), 0 if never.
   */
  static int fallRound(final JsonObject json, final String territory) {
    if (!json.has("territori_chiave") || !json.get("territori_chiave").isJsonArray()) {
      return 0;
    }
    for (final JsonElement e : json.getAsJsonArray("territori_chiave")) {
      final JsonObject o = e.getAsJsonObject();
      if (territory.equals(text(o, "territorio")) && "caduta".equals(text(o, "evento"))) {
        return o.get("round").getAsInt();
      }
    }
    return 0;
  }

  private static int number(final JsonObject o, final String key) {
    return o.has(key) ? o.get(key).getAsInt() : 0;
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

  /**
   * Step 3d-1: per game the history of the plans (round and reason of each change) and of the
   * postures; per configuration the plan changes and the final plans.
   */
  private static void plansAndPostures(
      final StringBuilder md, final List<Game> games, final List<String> alliances) {
    md.append(
"""

## Piani e posture dello stato maggiore (step 3d-1)

""")
        .append(
"""
| Partita | Alleanza | Piani (cambi) | Posture |
|---|---|---|---|
""");
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      byConfig
          .computeIfAbsent(configuration(g.json(), alliances), k -> new ArrayList<>())
          .add(g.json());
      for (final String a : alliances) {
        final List<String> postures = postures(g.json(), a);
        if (postures.stream().allMatch("-"::equals)) {
          continue;
        }
        md.append("| ")
            .append(g.folder())
            .append(" | ")
            .append(Riepilogo.allianceLabel(a))
            .append(" | ")
            .append(planHistory(g.json(), a))
            .append(" | ")
            .append(Riepilogo.runs(postures))
            .append(" |\n");
      }
    }
    md.append(
"""

| Configurazione | Alleanza | Partite | Cambi di piano (totale) | Partite con cambi |""")
        .append(
"""
 Piano finale |
|---|---|---|---|---|---|
""");
    for (final Map.Entry<String, List<JsonObject>> c : byConfig.entrySet()) {
      for (final String a : alliances) {
        if (c.getValue().stream().allMatch(j -> postures(j, a).stream().allMatch("-"::equals))) {
          continue;
        }
        final Map<String, Integer> finals = new TreeMap<>();
        int total = 0;
        int with = 0;
        for (final JsonObject j : c.getValue()) {
          final int n = changes(j, a).size();
          total += n;
          with += n > 0 ? 1 : 0;
          finals.merge(finalPlan(j, a), 1, Integer::sum);
        }
        md.append("| ")
            .append(c.getKey())
            .append(" | ")
            .append(Riepilogo.allianceLabel(a))
            .append(" | ")
            .append(c.getValue().size())
            .append(" | ")
            .append(total)
            .append(" | ")
            .append(with)
            .append(" | ")
            .append(
                String.join(
                    ", ",
                    finals.entrySet().stream().map(e -> e.getKey() + " x" + e.getValue()).toList()))
            .append(" |\n");
      }
    }
  }

  /** Posture of {@code alliance} per round ("-" without one). */
  static List<String> postures(final JsonObject json, final String alliance) {
    final List<String> list = new ArrayList<>();
    for (final JsonElement e : json.getAsJsonArray("round")) {
      final JsonObject staff = e.getAsJsonObject().getAsJsonObject("stato_maggiore");
      final JsonObject a = staff == null ? null : staff.getAsJsonObject(alliance);
      list.add(a != null && a.has("postura") ? a.get("postura").getAsString() : "-");
    }
    return list;
  }

  /** The plan changes of {@code alliance}: [{round, da, a, motivo}]. */
  static List<JsonObject> changes(final JsonObject json, final String alliance) {
    final List<JsonObject> list = new ArrayList<>();
    for (final JsonElement e : json.getAsJsonArray("round")) {
      final JsonObject r = e.getAsJsonObject();
      final JsonObject staff = r.getAsJsonObject("stato_maggiore");
      final JsonObject a = staff == null ? null : staff.getAsJsonObject(alliance);
      if (a != null && a.has("cambio")) {
        final JsonObject c = a.getAsJsonObject("cambio").deepCopy();
        c.addProperty("round", r.get("round").getAsInt());
        list.add(c);
      }
    }
    return list;
  }

  static String finalPlan(final JsonObject json, final String alliance) {
    final JsonObject last = lastRound(json);
    final JsonObject staff = last == null ? null : last.getAsJsonObject("stato_maggiore");
    final JsonObject a = staff == null ? null : staff.getAsJsonObject(alliance);
    return a != null && a.has("piano") ? a.get("piano").getAsString() : "-";
  }

  /** Eg "1 -> 2 (R8: piano 1 in crisi da 2 round ...)" or "A". */
  static String planHistory(final JsonObject json, final String alliance) {
    final List<JsonObject> list = changes(json, alliance);
    if (list.isEmpty()) {
      return finalPlan(json, alliance);
    }
    final StringBuilder sb = new StringBuilder(list.get(0).get("da").getAsString());
    for (final JsonObject c : list) {
      sb.append(" -> ")
          .append(c.get("a").getAsString())
          .append(" (R")
          .append(c.get("round").getAsInt())
          .append(": ")
          .append(c.get("motivo").getAsString().replace("|", "/"))
          .append(')');
    }
    return sb.toString();
  }
}
