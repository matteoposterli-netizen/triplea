package org.triplea.proplus.autoplay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * The summary of one game: {@code riepilogo.json} (data) and {@code riepilogo.md} (for people),
 * from the {@link Monitor} snapshots and the decision diary ({@code decisioni.jsonl}: PU spent on
 * defense and on the plan, estimated win% of the battles).
 */
final class Riepilogo {
  static final String JSON_FILE = "riepilogo.json";
  static final String MD_FILE = "riepilogo.md";
  static final Set<String> DEFENSE_STEPS = Set.of("defend_land", "capital_emergency");
  static final String PLAN_STEP = "plan";
  static final int TOP_BATTLES = 5;
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private Riepilogo() {}

  /**
   * Builds the summary JSON.
   *
   * @param status "completata", "in corso", "parziale: ..." or "errore: ..."
   * @param diary folder with {@code decisioni.jsonl}, if any
   */
  static JsonObject build(
      final AutoplayOptions o,
      final String gameName,
      final Monitor monitor,
      final String status,
      final long seconds,
      final Optional<String> winner,
      final boolean gameOver,
      @Nullable final Path diary) {
    final JsonObject json = new JsonObject();
    json.addProperty("nome", name(o));
    json.add("parametri", parameters(o, gameName, monitor));
    json.addProperty("stato", status);
    json.addProperty("durata_s", seconds);
    final JsonObject alliances = new JsonObject();
    monitor
        .alliances()
        .forEach(
            (a, members) -> {
              final JsonArray list = new JsonArray();
              members.forEach(list::add);
              alliances.add(a, list);
            });
    json.add("alleanze", alliances);
    final JsonObject data = monitor.toJson();
    final DiaryData diaryData = DiaryData.read(diary, monitor.costs());
    final JsonArray rounds = data.getAsJsonArray("round");
    for (final JsonElement r : rounds) {
      final JsonObject round = r.getAsJsonObject();
      final int n = round.get("round").getAsInt();
      for (final Map.Entry<String, JsonElement> e : round.getAsJsonObject("nazioni").entrySet()) {
        final JsonObject nation = e.getValue().getAsJsonObject();
        final int[] spent = diaryData.spent.getOrDefault(n + "|" + e.getKey(), new int[3]);
        nation.addProperty("pu_difesa", spent[0]);
        nation.addProperty("pu_piano", spent[1]);
        nation.addProperty("pu_spesi", spent[2]);
      }
    }
    json.addProperty("round_giocati", rounds.size());
    json.add("round", rounds);
    json.add("dichiarazioni_guerra", data.get("dichiarazioni_guerra"));
    json.add("capitali", data.get("capitali"));
    json.add("battaglie_top", topBattles(data.getAsJsonArray("battaglie"), diaryData));
    firstLandingInEurope(data.getAsJsonArray("battaglie"), rounds)
        .ifPresent(l -> json.add("primo_sbarco_europa", l));
    json.add("esito", outcome(o, monitor, rounds, winner, gameOver));
    return json;
  }

  static String name(final AutoplayOptions o) {
    if (o.nome() != null && !o.nome().isBlank()) {
      return o.nome();
    }
    final StringBuilder sb = new StringBuilder();
    o.tipi().forEach((a, t) -> sb.append(a).append(' ').append(t).append(' '));
    o.piani().forEach((a, p) -> sb.append(a).append('=').append(p).append(' '));
    sb.append("seme ").append(o.seme());
    return sb.toString().trim();
  }

  private static JsonObject parameters(
      final AutoplayOptions o, final String gameName, final Monitor monitor) {
    final JsonObject p = new JsonObject();
    p.addProperty("mappa", o.mappa().getFileName().toString());
    p.addProperty("gioco", gameName);
    p.addProperty("round_max", o.round());
    p.addProperty("seme", o.seme());
    p.addProperty("minuti_max", o.minuti());
    final JsonObject types = new JsonObject();
    final JsonObject plans = new JsonObject();
    final JsonObject risks = new JsonObject();
    for (final String a : monitor.alliances().keySet()) {
      final String type = o.tipo(a);
      types.addProperty(a, type);
      plans.addProperty(a, AutoplayOptions.HARD.equals(type) ? "-" : o.piano(a).orElse("auto"));
      risks.addProperty(
          a, AutoplayOptions.HARD.equals(type) ? "-" : o.rischio(a).orElse("Equilibrato"));
    }
    p.add("tipi", types);
    p.add("piani", plans);
    p.add("rischio_base", risks);
    return p;
  }

  /**
   * The first British/American landing in continental Europe: the earliest round with a battle of
   * theirs (attack, not a bombing raid) in a European land territory, or with their land units in
   * one at the end of the round (a landing without battle). A battle wins a tie.
   */
  static Optional<JsonObject> firstLandingInEurope(
      final JsonArray battles, final JsonArray rounds) {
    JsonObject best = null;
    for (final JsonElement e : battles) {
      final JsonObject b = e.getAsJsonObject();
      if (Monitor.WESTERN_ALLIES.contains(b.get("attaccante").getAsString())
          && Monitor.EUROPE.contains(b.get("territorio").getAsString())
          && "NORMAL".equals(text(b, "tipo"))
          && (best == null || b.get("round").getAsInt() < best.get("round").getAsInt())) {
        best = new JsonObject();
        best.addProperty("round", b.get("round").getAsInt());
        best.addProperty("territorio", b.get("territorio").getAsString());
        best.addProperty("nazione", b.get("attaccante").getAsString());
        best.addProperty("come", "attacco (" + text(b, "risultato") + ")");
      }
    }
    for (final JsonElement e : rounds) {
      final JsonObject r = e.getAsJsonObject();
      final int round = r.get("round").getAsInt();
      if (!r.has("alleati_in_europa")
          || r.getAsJsonObject("alleati_in_europa").isEmpty()
          || (best != null && best.get("round").getAsInt() <= round)) {
        continue;
      }
      final Map.Entry<String, JsonElement> first =
          r.getAsJsonObject("alleati_in_europa").entrySet().iterator().next();
      best = new JsonObject();
      best.addProperty("round", round);
      best.addProperty("territorio", first.getKey());
      best.addProperty("nazione", strings(first.getValue().getAsJsonArray()).get(0));
      best.addProperty("come", "unita' presenti a fine round");
      break;
    }
    return Optional.ofNullable(best);
  }

  /** Eg: "R5 Normandy Bordeaux (Americans, attacco (CONQUERED))", or "nessuno". */
  static String firstLandingText(final JsonObject json) {
    if (!json.has("primo_sbarco_europa")) {
      return "nessuno";
    }
    final JsonObject l = json.getAsJsonObject("primo_sbarco_europa");
    return "R"
        + l.get("round").getAsInt()
        + " "
        + l.get("territorio").getAsString()
        + " ("
        + l.get("nazione").getAsString()
        + ", "
        + l.get("come").getAsString()
        + ")";
  }

  private static JsonArray topBattles(final JsonArray all, final DiaryData diary) {
    final List<JsonObject> list = new ArrayList<>();
    all.forEach(b -> list.add(b.getAsJsonObject()));
    list.sort(
        Comparator.comparingInt((JsonObject b) -> -b.get("tuv_totale").getAsInt())
            .thenComparingInt(b -> b.get("round").getAsInt()));
    final JsonArray top = new JsonArray();
    for (final JsonObject b : list.subList(0, Math.min(TOP_BATTLES, list.size()))) {
      final JsonObject estimate =
          diary.battles.get(
              b.get("round").getAsInt()
                  + "|"
                  + b.get("territorio").getAsString()
                  + "|"
                  + b.get("attaccante").getAsString());
      if (estimate != null) {
        b.addProperty("win_stimata", estimate.get("win_stimata").getAsDouble());
        b.addProperty("esito_diario", estimate.get("esito").getAsString());
        if (estimate.has("tuv_swing_stimato") && !estimate.get("tuv_swing_stimato").isJsonNull()) {
          b.add("tuv_swing_stimato", estimate.get("tuv_swing_stimato"));
        }
        b.add("tuv_swing_reale", estimate.get("tuv_swing_reale"));
      }
      top.add(b);
    }
    return top;
  }

  private static JsonObject outcome(
      final AutoplayOptions o,
      final Monitor monitor,
      final JsonArray rounds,
      final Optional<String> winner,
      final boolean gameOver) {
    final JsonObject json = new JsonObject();
    if (winner.isPresent()) {
      json.addProperty("vincitore", winner.get());
      json.addProperty("testo", "vittoria " + allianceLabel(winner.get()));
    } else if (gameOver) {
      json.addProperty("testo", "partita finita senza vincitore riconosciuto");
    } else {
      json.addProperty("testo", "nessuna entro " + o.round() + " round");
    }
    final JsonObject vc = new JsonObject();
    if (!rounds.isEmpty()) {
      final JsonObject last = rounds.get(rounds.size() - 1).getAsJsonObject();
      for (final String a : monitor.alliances().keySet()) {
        vc.addProperty(a, last.getAsJsonObject("vc").getAsJsonObject(a).get("numero").getAsInt());
      }
    }
    json.add("vc_finali", vc);
    return json;
  }

  static String allianceLabel(final String alliance) {
    return switch (alliance) {
      case "Axis" -> "Asse";
      case "Allies" -> "Alleati";
      default -> alliance;
    };
  }

  // ------------------------------------------------------------------------------- writing

  static void write(final Path folder, final JsonObject json) throws IOException {
    Files.createDirectories(folder);
    Files.writeString(folder.resolve(JSON_FILE), GSON.toJson(json), StandardCharsets.UTF_8);
    Files.writeString(folder.resolve(MD_FILE), markdown(json), StandardCharsets.UTF_8);
  }

  static String markdown(final JsonObject json) {
    final StringBuilder md = new StringBuilder();
    final JsonObject p = json.getAsJsonObject("parametri");
    final List<String> alliances = new ArrayList<>(json.getAsJsonObject("alleanze").keySet());
    md.append("# Partita autoplay: ").append(json.get("nome").getAsString()).append("\n\n");
    md.append("- Esito: **")
        .append(json.getAsJsonObject("esito").get("testo").getAsString())
        .append("**");
    final JsonObject vcFinal = json.getAsJsonObject("esito").getAsJsonObject("vc_finali");
    if (!vcFinal.isEmpty()) {
      md.append(" - VC finali: ").append(perAlliance(alliances, a -> num(vcFinal, a)));
    }
    md.append('\n');
    md.append("- Stato: ")
        .append(json.get("stato").getAsString())
        .append(", round giocati ")
        .append(json.get("round_giocati").getAsInt())
        .append(" su ")
        .append(p.get("round_max").getAsInt())
        .append(", durata ")
        .append(duration(json.get("durata_s").getAsLong()))
        .append('\n');
    md.append("- Primo sbarco alleato in Europa: ").append(firstLandingText(json)).append('\n');
    md.append("- Mappa: ")
        .append(p.get("gioco").getAsString())
        .append(" (")
        .append(p.get("mappa").getAsString())
        .append("), seme dadi ")
        .append(p.get("seme").getAsLong())
        .append('\n');
    for (final String a : alliances) {
      md.append("- ")
          .append(allianceLabel(a))
          .append(": ")
          .append(typeLabel(p.getAsJsonObject("tipi").get(a).getAsString()))
          .append(", piano ")
          .append(p.getAsJsonObject("piani").get(a).getAsString())
          .append(", rischio base ")
          .append(p.getAsJsonObject("rischio_base").get(a).getAsString())
          .append(" - ")
          .append(String.join(", ", strings(json.getAsJsonObject("alleanze").getAsJsonArray(a))))
          .append('\n');
    }

    final JsonArray rounds = json.getAsJsonArray("round");
    md.append("\n## Victory city a fine round\n\n| Round |");
    alliances.forEach(a -> md.append(' ').append(allianceLabel(a)).append(" |"));
    md.append('\n').append("|---|");
    alliances.forEach(a -> md.append("---|"));
    md.append('\n');
    for (final JsonElement e : rounds) {
      final JsonObject r = e.getAsJsonObject();
      md.append("| ").append(roundLabel(r)).append(" |");
      for (final String a : alliances) {
        final JsonObject vc = r.getAsJsonObject("vc").getAsJsonObject(a);
        md.append(' ')
            .append(vc.get("numero").getAsInt())
            .append(" (")
            .append(String.join(", ", strings(vc.getAsJsonArray("citta"))))
            .append(") |");
      }
      md.append('\n');
    }

    md.append(
"""

## Nazioni per round

""");
    md.append(
        "PU = cassa a fine round; difesa = PU spesi nei passi di difesa (defend_land,"
            + " capital_emergency); piano = PU spesi nel mix del piano; spesi = totale acquisti;"
            + " trasporti = trasporti della nazione a fine round.\n\n");
    md.append(
        "| Round | Nazione | PU | Reddito | PU difesa | PU piano | PU spesi | TUV | Territori |"
            + " Trasporti |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|\n");
    for (final JsonElement e : rounds) {
      final JsonObject r = e.getAsJsonObject();
      for (final Map.Entry<String, JsonElement> n : r.getAsJsonObject("nazioni").entrySet()) {
        final JsonObject v = n.getValue().getAsJsonObject();
        md.append("| ").append(roundLabel(r)).append(" | ").append(n.getKey()).append(" | ");
        for (final String k :
            List.of(
                "pu",
                "reddito",
                "pu_difesa",
                "pu_piano",
                "pu_spesi",
                "tuv",
                "territori",
                "trasporti")) {
          md.append(num(v, k)).append(" | ");
        }
        md.setLength(md.length() - 1);
        md.append('\n');
      }
    }

    md.append(
"""

## Stato maggiore (piano e lettura dell'avversario)

""");
    boolean any = false;
    final StringBuilder staff = new StringBuilder();
    staff.append(
"""
| Round | Alleanza | Piano | Stato | Piano nemico piu' probabile | Piano reale | Prob. piano reale | Corretta |
|---|---|---|---|---|---|---|---|
""");
    for (final JsonElement e : rounds) {
      final JsonObject r = e.getAsJsonObject();
      for (final Map.Entry<String, JsonElement> s :
          r.getAsJsonObject("stato_maggiore").entrySet()) {
        any = true;
        final JsonObject v = s.getValue().getAsJsonObject();
        final JsonObject l = v.has("lettura") ? v.getAsJsonObject("lettura") : null;
        staff
            .append("| ")
            .append(roundLabel(r))
            .append(" | ")
            .append(allianceLabel(s.getKey()))
            .append(" | ")
            .append(v.get("piano").getAsString())
            .append(" | ")
            .append(text(v, "stato"))
            .append(" | ")
            .append(l == null ? "-" : text(l, "piu_probabile"))
            .append(" | ")
            .append(l == null ? "-" : text(l, "piano_reale"))
            .append(" | ")
            .append(
                l == null || !l.has("prob_piano_reale")
                    ? "-"
                    : percent(l.get("prob_piano_reale").getAsDouble()))
            .append(" | ")
            .append(
                l == null || !l.has("corretta")
                    ? "-"
                    : l.get("corretta").getAsBoolean() ? "si'" : "no")
            .append(" |\n");
      }
    }
    md.append(any ? staff : "Nessun piano (giocatori Hard).\n");

    md.append(
"""

## Dichiarazioni di guerra

""");
    final JsonArray wars = json.getAsJsonArray("dichiarazioni_guerra");
    if (wars.isEmpty()) {
      md.append("Nessuna.\n");
    }
    for (final JsonElement e : wars) {
      final JsonObject w = e.getAsJsonObject();
      md.append("- R")
          .append(w.get("round").getAsInt())
          .append(": ")
          .append(w.get("tra").getAsString())
          .append(" (nel turno di ")
          .append(w.get("nazione_di_turno").getAsString())
          .append(")\n");
    }

    md.append(
"""

## Capitali cadute

""");
    final JsonArray capitals = json.getAsJsonArray("capitali");
    if (capitals.isEmpty()) {
      md.append("Nessuna.\n");
    }
    for (final JsonElement e : capitals) {
      final JsonObject c = e.getAsJsonObject();
      md.append("- R")
          .append(c.get("round").getAsInt())
          .append(": ")
          .append(c.get("territorio").getAsString())
          .append(" (capitale di ")
          .append(c.get("capitale_di").getAsString())
          .append(") ")
          .append(c.get("evento").getAsString())
          .append(", ora di ")
          .append(c.get("proprietario").getAsString())
          .append('\n');
    }

    md.append(
"""

## Battaglie piu' grandi (TUV perso da entrambe le parti)

""");
    final JsonArray top = json.getAsJsonArray("battaglie_top");
    if (top.isEmpty()) {
      md.append("Nessuna.\n");
    } else {
      md.append(
"""
| Round | Territorio | Attaccante | Difensore | TUV perso att./dif. | Risultato | Win% stimata | Esito (diario) |
|---|---|---|---|---|---|---|---|
""");
      for (final JsonElement e : top) {
        final JsonObject b = e.getAsJsonObject();
        md.append("| R")
            .append(b.get("round").getAsInt())
            .append(" | ")
            .append(b.get("territorio").getAsString())
            .append(" | ")
            .append(b.get("attaccante").getAsString())
            .append(" | ")
            .append(b.get("difensore").getAsString())
            .append(" | ")
            .append(b.get("tuv_perso_attaccante").getAsInt())
            .append(" / ")
            .append(b.get("tuv_perso_difensore").getAsInt())
            .append(" | ")
            .append(b.get("risultato").getAsString())
            .append(" | ")
            .append(
                b.has("win_stimata")
                    ? String.format(Locale.ROOT, "%.0f%%", b.get("win_stimata").getAsDouble())
                    : "-")
            .append(" | ")
            .append(text(b, "esito_diario"))
            .append(" |\n");
      }
    }
    return md.toString();
  }

  static String roundLabel(final JsonObject round) {
    return "R"
        + round.get("round").getAsInt()
        + (round.has("completo") && !round.get("completo").getAsBoolean() ? " (parziale)" : "");
  }

  static String typeLabel(final String type) {
    return AutoplayOptions.HARD.equals(type) ? "Hard" : "Pro+";
  }

  static String duration(final long seconds) {
    return String.format(Locale.ROOT, "%d min %02d s", seconds / 60, seconds % 60);
  }

  static String percent(final double p) {
    return String.format(Locale.ROOT, "%.0f%%", p * 100);
  }

  static String perAlliance(
      final List<String> alliances, final java.util.function.Function<String, String> value) {
    final List<String> parts = new ArrayList<>();
    for (final String a : alliances) {
      parts.add(allianceLabel(a) + " " + value.apply(a));
    }
    return String.join(", ", parts);
  }

  private static String num(final JsonObject o, final String key) {
    return o.has(key) ? o.get(key).getAsString() : "-";
  }

  private static String text(final JsonObject o, final String key) {
    return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "-";
  }

  private static List<String> strings(final JsonArray array) {
    final List<String> list = new ArrayList<>();
    array.forEach(e -> list.add(e.getAsString()));
    return list;
  }

  // --------------------------------------------------------------------------- diary data

  /** What the summary takes from {@code decisioni.jsonl}. */
  static final class DiaryData {
    /** "round|nation" -> {PU on defense, PU on the plan, PU spent in total}. */
    final Map<String, int[]> spent = new HashMap<>();

    /** "round|territory|attacker" -> the {@code esito_battaglia} event. */
    final Map<String, JsonObject> battles = new HashMap<>();

    static DiaryData read(
        @Nullable final Path folder, final Map<String, Map<String, Integer>> costs) {
      final DiaryData d = new DiaryData();
      if (folder == null) {
        return d;
      }
      final Path file = folder.resolve("decisioni.jsonl");
      if (!Files.exists(file)) {
        return d;
      }
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          d.add(line, costs);
        }
      } catch (final IOException | RuntimeException e) {
        // a summary without the diary's numbers is still useful
      }
      return d;
    }

    void add(final String line, final Map<String, Map<String, Integer>> costs) {
      if (line.isBlank()) {
        return;
      }
      final JsonObject e;
      try {
        e = JsonParser.parseString(line).getAsJsonObject();
      } catch (final RuntimeException ex) {
        return;
      }
      if (!e.has("evento") || !e.has("round") || !e.has("nazione")) {
        return;
      }
      final String key = e.get("round").getAsInt() + "|" + e.get("nazione").getAsString();
      switch (e.get("evento").getAsString()) {
        case "acquisto" -> {
          final String step = e.get("passo").getAsString();
          final int index = DEFENSE_STEPS.contains(step) ? 0 : PLAN_STEP.equals(step) ? 1 : -1;
          if (index >= 0) {
            final Map<String, Integer> c =
                costs.getOrDefault(e.get("nazione").getAsString(), Map.of());
            int pus = 0;
            for (final Map.Entry<String, JsonElement> u : e.getAsJsonObject("unita").entrySet()) {
              pus += c.getOrDefault(u.getKey(), 0) * u.getValue().getAsInt();
            }
            spent.computeIfAbsent(key, k -> new int[3])[index] += pus;
          }
        }
        case "acquisti_totale" ->
            spent.computeIfAbsent(key, k -> new int[3])[2] = e.get("pu_spesi").getAsInt();
        case "esito_battaglia" ->
            battles.put(
                e.get("round").getAsInt()
                    + "|"
                    + e.get("territorio").getAsString()
                    + "|"
                    + e.get("nazione").getAsString(),
                e);
        default -> {
          // other events are not summarized
        }
      }
    }
  }

  /** The game's diary folder under {@code root} (the newest one), if any. */
  static Optional<Path> diaryFolder(final Path root) {
    if (!Files.isDirectory(root)) {
      return Optional.empty();
    }
    try (Stream<Path> s = Files.list(root)) {
      return s.filter(Files::isDirectory).max(Comparator.comparing(Path::getFileName));
    } catch (final IOException e) {
      return Optional.empty();
    }
  }
}
