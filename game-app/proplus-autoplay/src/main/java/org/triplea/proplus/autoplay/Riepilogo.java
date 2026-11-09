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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

  /** Targets of Sea Lion (WW2 Europe 1940). */
  static final Set<String> BRITISH_ISLES = Set.of("United Kingdom", "Scotland");

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
        final int[] spent = diaryData.spent.getOrDefault(n + "|" + e.getKey(), new int[4]);
        nation.addProperty("pu_difesa", spent[0]);
        nation.addProperty("pu_piano", spent[1]);
        nation.addProperty("pu_spesi", spent[2]);
        nation.addProperty("pu_riparazioni", spent[3]);
      }
    }
    json.addProperty("round_giocati", rounds.size());
    json.add("round", rounds);
    json.add("dichiarazioni_guerra", data.get("dichiarazioni_guerra"));
    json.add("capitali", data.get("capitali"));
    json.add("territori_chiave", data.get("territori_chiave"));
    json.add("battaglie_top", topBattles(data.getAsJsonArray("battaglie"), diaryData));
    json.add("tuv_perso", lostTuv(data.getAsJsonArray("battaglie"), monitor));
    final JsonArray landings = data.getAsJsonArray("sbarchi");
    json.add("sbarchi", landings);
    firstLandingInEurope(landings).ifPresent(l -> json.add("primo_sbarco_europa", l));
    json.add("primo_sbarco_vero", firstLandings(landings, monitor.alliances().keySet()));
    json.add("usa_sbarcate_ovest", usLandedInWestEurope(landings, rounds.size()));
    firstHeldBridgehead(landings, rounds).ifPresent(b -> json.add("prima_testa_di_ponte", b));
    json.add("operazioni", diaryData.operations);
    json.add("attacchi_regno_unito", diaryData.attacksOnBritain);
    json.add("esito", outcome(o, monitor, rounds, winner, gameOver));
    json.add("contratto", contract(diary));
    json.add("stratega", strategist(diary));
    return json;
  }

  /**
   * LLM strategist (and, for comparison, any decider) per alliance, from the general staff events
   * of decisioni.jsonl: decisions, who decided (fonte), fallbacks with their first reasons, plan
   * changes, postures, delay tools turned on, operation decisions, calls/tokens/cost of the game.
   */
  static JsonObject strategist(@Nullable final Path diary) {
    final JsonObject out = new JsonObject();
    if (diary == null || !Files.isRegularFile(diary.resolve("decisioni.jsonl"))) {
      return out;
    }
    try {
      for (final String line :
          Files.readAllLines(diary.resolve("decisioni.jsonl"), StandardCharsets.UTF_8)) {
        final boolean orders = line.contains("\"evento\":\"ordini_turno\"");
        if (!line.contains("\"stato_maggiore_round\"") && !orders) {
          continue;
        }
        final JsonObject e = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
        final String alliance = e.has("alleanza") ? e.get("alleanza").getAsString() : "?";
        if (!out.has(alliance)) {
          out.add(alliance, new JsonObject());
        }
        final JsonObject a = out.getAsJsonObject(alliance);
        if (orders) {
          turnOrders(a, e);
          continue;
        }
        if (e.has("rischio_effettivo")) {
          for (final Map.Entry<String, JsonElement> n :
              e.getAsJsonObject("rischio_effettivo").entrySet()) {
            for (final Map.Entry<String, JsonElement> f : n.getValue().getAsJsonObject().entrySet()) {
              final JsonObject sums = child(child(a, "rischio_somme"), n.getKey());
              sums.addProperty(
                  "somma",
                  (sums.has("somma") ? sums.get("somma").getAsDouble() : 0)
                      + f.getValue().getAsDouble());
              increment(sums, "n", 1);
            }
          }
        }
        increment(a, "decisioni", 1);
        final JsonObject who =
            e.has("decisore") ? e.getAsJsonObject("decisore") : new JsonObject();
        final String name = who.has("nome") ? who.get("nome").getAsString() : "regole";
        a.addProperty("decisore", name);
        final boolean fallback = who.has("ripiego") && who.get("ripiego").getAsBoolean();
        final String source =
            who.has("fonte") ? who.get("fonte").getAsString() : fallback ? "regole" : name;
        increment(child(a, "fonti"), source, 1);
        if (fallback) {
          increment(a, "ripieghi", 1);
          final JsonArray reasons =
              a.has("motivi_ripiego") ? a.getAsJsonArray("motivi_ripiego") : new JsonArray();
          if (reasons.size() < 3 && who.has("avvisi") && !who.getAsJsonArray("avvisi").isEmpty()) {
            reasons.add(
                "R" + e.get("round").getAsInt() + ": " + who.getAsJsonArray("avvisi").get(0).getAsString());
          }
          a.add("motivi_ripiego", reasons);
        }
        if (who.has("uso")) {
          a.add("uso", who.get("uso"));
        }
        final String plan = e.has("piano") ? e.get("piano").getAsString() : "";
        if (a.has("ultimo_piano") && !a.get("ultimo_piano").getAsString().equals(plan)) {
          increment(a, "cambi_piano", 1);
        }
        a.addProperty("ultimo_piano", plan);
        if (e.has("direttive")) {
          final JsonObject d = e.getAsJsonObject("direttive");
          if (d.has("postura")) {
            increment(child(a, "posture"), d.get("postura").getAsString(), 1);
          }
          if (d.has("tattiche")) {
            for (final JsonElement t : d.getAsJsonArray("tattiche")) {
              final JsonObject tool = t.getAsJsonObject();
              if (!tool.has("attiva") || tool.get("attiva").getAsBoolean()) {
                increment(child(a, "strumenti_accesi"), tool.get("strumento").getAsString(), 1);
              }
            }
          }
          if (d.has("operazioni")) {
            for (final JsonElement o : d.getAsJsonArray("operazioni")) {
              increment(
                  child(a, "operazioni"), o.getAsJsonObject().get("decisione").getAsString(), 1);
            }
          }
        }
      }
    } catch (final IOException | RuntimeException e) {
      out.addProperty("errore", e.toString());
    }
    // mean effective risk per nation (all rounds and fronts)
    out.entrySet()
        .forEach(
            x -> {
              if (!x.getValue().isJsonObject() || !x.getValue().getAsJsonObject().has("rischio_somme")) {
                return;
              }
              final JsonObject a = x.getValue().getAsJsonObject();
              final JsonObject mean = new JsonObject();
              a.getAsJsonObject("rischio_somme")
                  .entrySet()
                  .forEach(
                      n -> {
                        final JsonObject v = n.getValue().getAsJsonObject();
                        mean.addProperty(
                            n.getKey(),
                            Math.round(v.get("somma").getAsDouble() / v.get("n").getAsDouble() * 100)
                                / 100.0);
                      });
              a.remove("rischio_somme");
              a.add("rischio_medio", mean);
            });
    return out;
  }

  /** LLM step: one turn orders event of a nation of the alliance. */
  private static void turnOrders(final JsonObject a, final JsonObject e) {
    final String nation = e.has("nazione") ? e.get("nazione").getAsString() : "?";
    if (!e.has("chiamato") || !e.get("chiamato").getAsBoolean()) {
      return;
    }
    final JsonObject o = child(a, "ordini_turno");
    increment(o, "chiamate", 1);
    increment(child(o, "per_nazione"), nation, 1);
    final JsonObject own = e.has("ordini") ? e.getAsJsonObject("ordini") : new JsonObject();
    final JsonObject who = own.has("decisore") ? own.getAsJsonObject("decisore") : new JsonObject();
    if (who.has("ripiego") && who.get("ripiego").getAsBoolean()) {
      increment(o, "ripieghi", 1);
      final JsonArray reasons =
          o.has("motivi_ripiego") ? o.getAsJsonArray("motivi_ripiego") : new JsonArray();
      if (reasons.size() < 3 && who.has("avvisi") && !who.getAsJsonArray("avvisi").isEmpty()) {
        reasons.add(
            "R"
                + e.get("round").getAsInt()
                + " "
                + nation
                + ": "
                + who.getAsJsonArray("avvisi").get(0).getAsString());
      }
      o.add("motivi_ripiego", reasons);
    }
    if (own.has("ordini")) {
      final JsonObject orders = own.getAsJsonObject("ordini");
      if (orders.has("bersagli")) {
        increment(o, "bersagli", orders.getAsJsonArray("bersagli").size());
      }
      if (orders.has("operazioni")) {
        for (final JsonElement x : orders.getAsJsonArray("operazioni")) {
          increment(
              child(o, "operazioni"), x.getAsJsonObject().get("decisione").getAsString(), 1);
        }
      }
      if (orders.has("tattiche")) {
        for (final JsonElement x : orders.getAsJsonArray("tattiche")) {
          final JsonObject t = x.getAsJsonObject();
          if (!t.has("attiva") || t.get("attiva").getAsBoolean()) {
            increment(child(o, "strumenti_accesi"), t.get("strumento").getAsString(), 1);
          }
        }
      }
    }
    if (e.has("uso")) {
      a.add("uso", e.get("uso"));
    }
  }

  private static JsonObject child(final JsonObject o, final String key) {
    if (!o.has(key)) {
      o.add(key, new JsonObject());
    }
    return o.getAsJsonObject(key);
  }

  private static void increment(final JsonObject o, final String key, final int by) {
    o.addProperty(key, (o.has(key) ? o.get(key).getAsInt() : 0) + by);
  }

  /** Characters per estimated token of the decider's input (rough rule for JSON text). */
  static final double CHARS_PER_TOKEN = 4.0;

  /**
   * Strategic contract: size of the decider's input (the {@code stato-R<N>-<alliance>.json} files
   * of the diary, measured as compact JSON) and the fallbacks to the rules (decisioni.jsonl).
   */
  static JsonObject contract(@Nullable final Path diary) {
    final JsonObject out = new JsonObject();
    if (diary == null || !Files.isDirectory(diary)) {
      return out;
    }
    int files = 0;
    long chars = 0;
    long max = 0;
    try (Stream<Path> s = Files.list(diary)) {
      for (final Path f :
          s.filter(f -> f.getFileName().toString().matches("stato-R\\d+-.+\\.json")).toList()) {
        final long n =
            com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
                .toString()
                .length();
        files++;
        chars += n;
        max = Math.max(max, n);
      }
    } catch (final IOException | RuntimeException e) {
      out.addProperty("errore", e.toString());
    }
    int decisions = 0;
    int fallbacks = 0;
    int withTools = 0;
    final Path jsonl = diary.resolve("decisioni.jsonl");
    if (Files.isRegularFile(jsonl)) {
      try {
        for (final String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
          if (!line.contains("\"stato_maggiore_round\"")) {
            continue;
          }
          final JsonObject e = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
          decisions++;
          if (e.has("decisore") && e.getAsJsonObject("decisore").get("ripiego").getAsBoolean()) {
            fallbacks++;
          }
          if (e.has("direttive")
              && !e.getAsJsonObject("direttive").getAsJsonArray("tattiche").isEmpty()) {
            withTools++;
          }
        }
      } catch (final IOException | RuntimeException e) {
        out.addProperty("errore_decisioni", e.toString());
      }
    }
    out.addProperty("file_stato", files);
    out.addProperty("caratteri_medi", files == 0 ? 0 : Math.round((double) chars / files));
    out.addProperty("caratteri_max", max);
    out.addProperty(
        "token_stimati_medi", files == 0 ? 0 : Math.round(chars / CHARS_PER_TOKEN / files));
    out.addProperty("decisioni", decisions);
    out.addProperty("ripieghi_sulle_regole", fallbacks);
    out.addProperty("direttive_con_tattiche", withTools);
    return out;
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
    final JsonObject deciders = new JsonObject();
    for (final String a : monitor.alliances().keySet()) {
      deciders.addProperty(
          a,
          AutoplayOptions.HARD.equals(o.tipo(a))
              ? "-"
              // the path of a directives file is not shown; the model of an LLM is (llm:MODELLO)
              : o.decisore(a).startsWith("file:") ? "file" : o.decisore(a));
    }
    p.add("decisori", deciders);
    return p;
  }

  /**
   * The first real British/American landing in continental Europe (step OPERAZIONI ANFIBIE): land
   * units unloaded from transports on a European territory an enemy held when the turn started
   * ({@link Monitor} records them; walking into a French territory or being there is not a
   * landing). The earliest round, the most units on a tie.
   */
  static Optional<JsonObject> firstLandingInEurope(final JsonArray landings) {
    JsonObject best = null;
    for (final JsonElement e : landings) {
      final JsonObject l = e.getAsJsonObject();
      if (!Monitor.WESTERN_ALLIES.contains(l.get("nazione").getAsString())
          || !Monitor.EUROPE.contains(l.get("territorio").getAsString())) {
        continue;
      }
      if (best == null
          || l.get("round").getAsInt() < best.get("round").getAsInt()
          || (l.get("round").getAsInt() == best.get("round").getAsInt()
              && l.get("unita").getAsInt() > best.get("unita").getAsInt())) {
        best = landingJson(l);
      }
    }
    return Optional.ofNullable(best);
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS: American land units of real landings in {@link
   * Monitor#WEST_EUROPE}, per round ("1".."N", 0 in a round without).
   */
  static JsonObject usLandedInWestEurope(final JsonArray landings, final int rounds) {
    final JsonObject json = new JsonObject();
    for (int r = 1; r <= rounds; r++) {
      json.addProperty(String.valueOf(r), 0);
    }
    for (final JsonElement e : landings) {
      final JsonObject l = e.getAsJsonObject();
      if ("Americans".equals(l.get("nazione").getAsString())
          && Monitor.WEST_EUROPE.contains(l.get("territorio").getAsString())) {
        final String r = String.valueOf(l.get("round").getAsInt());
        json.addProperty(r, (json.has(r) ? json.get(r).getAsInt() : 0) + l.get("unita").getAsInt());
      }
    }
    return json;
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS: the first real British/American landing in {@link
   * Monitor#WEST_EUROPE} whose territory the Allies still hold at the end of its round and of the
   * next one (held for at least 2 rounds). Rounds without the "ovest_alleato" list never count.
   */
  static Optional<JsonObject> firstHeldBridgehead(
      final JsonArray landings, final JsonArray rounds) {
    final Map<Integer, Set<String>> held = new HashMap<>();
    for (final JsonElement e : rounds) {
      final JsonObject r = e.getAsJsonObject();
      if (r.has("ovest_alleato")) {
        held.put(
            r.get("round").getAsInt(), new HashSet<>(strings(r.getAsJsonArray("ovest_alleato"))));
      }
    }
    JsonObject best = null;
    for (final JsonElement e : landings) {
      final JsonObject l = e.getAsJsonObject();
      final int round = l.get("round").getAsInt();
      final String territory = l.get("territorio").getAsString();
      if (!Monitor.WESTERN_ALLIES.contains(l.get("nazione").getAsString())
          || !Monitor.WEST_EUROPE.contains(territory)
          || !held.getOrDefault(round, Set.of()).contains(territory)
          || !held.getOrDefault(round + 1, Set.of()).contains(territory)) {
        continue;
      }
      if (best == null || round < best.get("round").getAsInt()) {
        best = landingJson(l);
      }
    }
    return Optional.ofNullable(best);
  }

  /** Alliance -> its first real landing anywhere (round, territory, nation, units), if any. */
  static JsonObject firstLandings(final JsonArray landings, final Collection<String> alliances) {
    final JsonObject json = new JsonObject();
    for (final String a : alliances) {
      JsonObject best = null;
      for (final JsonElement e : landings) {
        final JsonObject l = e.getAsJsonObject();
        if (a.equals(l.get("alleanza").getAsString())
            && (best == null
                || l.get("round").getAsInt() < best.get("round").getAsInt()
                || (l.get("round").getAsInt() == best.get("round").getAsInt()
                    && l.get("unita").getAsInt() > best.get("unita").getAsInt()))) {
          best = landingJson(l);
        }
      }
      if (best != null) {
        json.add(a, best);
      }
    }
    return json;
  }

  private static JsonObject landingJson(final JsonObject l) {
    final JsonObject j = new JsonObject();
    j.addProperty("round", l.get("round").getAsInt());
    j.addProperty("territorio", l.get("territorio").getAsString());
    j.addProperty("nazione", l.get("nazione").getAsString());
    j.addProperty("unita", l.get("unita").getAsInt());
    j.addProperty(
        "come",
        l.get("unita").getAsInt()
            + " unita' sbarcate ("
            + ("movimento".equals(text(l, "fase"))
                ? "su territorio preso nel turno"
                : "assalto anfibio")
            + ")");
    return j;
  }

  /** Eg "R6 4, R7 2 (totale 6)", or "nessuna". */
  static String usLandedText(final JsonObject json) {
    if (!json.has("usa_sbarcate_ovest")) {
      return "n/d";
    }
    final List<String> parts = new ArrayList<>();
    int total = 0;
    for (final Map.Entry<String, JsonElement> e :
        json.getAsJsonObject("usa_sbarcate_ovest").entrySet()) {
      final int n = e.getValue().getAsInt();
      if (n > 0) {
        parts.add("R" + e.getKey() + " " + n);
        total += n;
      }
    }
    return parts.isEmpty() ? "nessuna" : String.join(", ", parts) + " (totale " + total + ")";
  }

  /** Eg "R5 Normandy Bordeaux (Americans, 6 unita' sbarcate (assalto anfibio))", or "nessuno". */
  static String landingText(@Nullable final JsonObject l) {
    if (l == null) {
      return "nessuno";
    }
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

  /**
   * Step CONFRONTO: TUV lost per alliance in all the battles of the game (attacker's losses to the
   * attacker's alliance, defender's losses to the defender's alliance).
   */
  static JsonObject lostTuv(final JsonArray battles, final Monitor monitor) {
    final JsonObject lost = new JsonObject();
    monitor.alliances().keySet().forEach(a -> lost.addProperty(a, 0));
    for (final JsonElement e : battles) {
      final JsonObject b = e.getAsJsonObject();
      addLost(lost, monitor.allianceOf(b.get("attaccante").getAsString()), b, "tuv_perso_attaccante");
      addLost(lost, monitor.allianceOf(b.get("difensore").getAsString()), b, "tuv_perso_difensore");
    }
    return lost;
  }

  private static void addLost(
      final JsonObject lost, final String alliance, final JsonObject battle, final String key) {
    if (lost.has(alliance)) {
      lost.addProperty(alliance, lost.get(alliance).getAsInt() + battle.get(key).getAsInt());
    }
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
    md.append("- Prima testa di ponte in Europa occidentale tenuta almeno 2 round: ")
        .append(
            landingText(
                json.has("prima_testa_di_ponte")
                    ? json.getAsJsonObject("prima_testa_di_ponte")
                    : null))
        .append('\n');
    md.append("- Unita' USA sbarcate in Europa occidentale (Francia/Benelux/Norvegia/Danimarca/")
        .append("Germania) per round: ")
        .append(usLandedText(json))
        .append('\n');
    if (json.has("primo_sbarco_vero")) {
      final JsonObject first = json.getAsJsonObject("primo_sbarco_vero");
      md.append("- Primo sbarco vero: ")
          .append(
              String.join(
                  "; ",
                  alliances.stream()
                      .map(
                          a ->
                              allianceLabel(a)
                                  + " "
                                  + landingText(first.has(a) ? first.getAsJsonObject(a) : null))
                      .toList()))
          .append('\n');
    }
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
    if (json.has("contratto") && json.getAsJsonObject("contratto").has("file_stato")) {
      final JsonObject c = json.getAsJsonObject("contratto");
      md.append("- Contratto strategico: ")
          .append(c.get("file_stato").getAsInt())
          .append(" ingressi (stato-R*.json), in media ")
          .append(c.get("caratteri_medi").getAsLong())
          .append(" caratteri = ~")
          .append(c.get("token_stimati_medi").getAsLong())
          .append(" token stimati (max ")
          .append(c.get("caratteri_max").getAsLong())
          .append("); ripieghi sulle regole ")
          .append(c.get("ripieghi_sulle_regole").getAsInt())
          .append(" su ")
          .append(c.get("decisioni").getAsInt())
          .append(" decisioni\n");
    }

    if (json.has("stratega")) {
      for (final Map.Entry<String, JsonElement> e : json.getAsJsonObject("stratega").entrySet()) {
        if (!e.getValue().isJsonObject()) {
          continue;
        }
        final JsonObject a = e.getValue().getAsJsonObject();
        if (!a.has("decisore") || "regole".equals(a.get("decisore").getAsString())) {
          continue;
        }
        md.append("- Stratega ")
            .append(allianceLabel(e.getKey()))
            .append(": ")
            .append(a.has("fonti") ? a.get("fonti").toString() : "{}")
            .append(", ripieghi ")
            .append(a.has("ripieghi") ? a.get("ripieghi").getAsInt() : 0)
            .append('/')
            .append(a.get("decisioni").getAsInt())
            .append(", posture ")
            .append(a.has("posture") ? a.get("posture").toString() : "{}")
            .append(", strumenti ")
            .append(a.has("strumenti_accesi") ? a.get("strumenti_accesi").toString() : "{}")
            .append(", operazioni ")
            .append(a.has("operazioni") ? a.get("operazioni").toString() : "{}")
            .append(
                a.has("ordini_turno")
                    ? "; ordini del turno "
                        + a.getAsJsonObject("ordini_turno").get("chiamate").getAsInt()
                        + " (ripieghi "
                        + (a.getAsJsonObject("ordini_turno").has("ripieghi")
                            ? a.getAsJsonObject("ordini_turno").get("ripieghi").getAsInt()
                            : 0)
                        + ")"
                    : "")
            .append(
                a.has("uso")
                    ? "; uso: "
                        + games.strategy.triplea.ai.proplus.strategia.llm.Uso.testo(
                            a.getAsJsonObject("uso"))
                    : "")
            .append('\n');
      }
    }

    if (json.has("stratega")) {
      final List<String> risk = new ArrayList<>();
      for (final Map.Entry<String, JsonElement> e : json.getAsJsonObject("stratega").entrySet()) {
        if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("rischio_medio")) {
          e.getValue()
              .getAsJsonObject()
              .getAsJsonObject("rischio_medio")
              .entrySet()
              .forEach(n -> risk.add(n.getKey() + " " + n.getValue().getAsString()));
        }
      }
      if (!risk.isEmpty()) {
        md.append("- Rischio effettivo medio per nazione (1-5, tutti i round e fronti): ")
            .append(String.join(", ", risk))
            .append('\n');
      }
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
            + " capital_emergency); piano = PU spesi nel mix del piano; spesi = totale acquisti di"
            + " unita'; riparazioni = fabbriche riparate; trasporti = trasporti della nazione a"
            + " fine round.\n\n");
    md.append(
        "| Round | Nazione | PU | Reddito | PU difesa | PU piano | PU spesi | PU riparazioni | TUV"
            + " | Territori | Trasporti |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
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
                "pu_riparazioni",
                "tuv",
                "territori",
                "trasporti")) {
          md.append(num(v, k)).append(" | ");
        }
        md.setLength(md.length() - 1);
        md.append('\n');
      }
    }

    md.append(landingsAndOperationsMarkdown(json, alliances));

    md.append(
"""

## Stato maggiore (piano e lettura dell'avversario)

""");
    boolean any = false;
    final StringBuilder staff = new StringBuilder();
    staff.append(
"""
| Round | Alleanza | Piano | Stato | Postura | Piano nemico piu' probabile | Piano reale | Prob. piano reale | Corretta |
|---|---|---|---|---|---|---|---|---|
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
            .append(postureCell(v))
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
    if (any) {
      md.append(plansMarkdown(rounds));
    }

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

  /**
   * Per round: real landings (units per alliance, where), idle transports per alliance (empty and
   * unmoved at the end of the nation's non-combat move); then the blocked operations of the diary.
   */
  static String landingsAndOperationsMarkdown(final JsonObject json, final List<String> alliances) {
    final StringBuilder md =
        new StringBuilder(
"""

## Sbarchi e trasporti per round

""");
    md.append(
        "Sbarco vero = unita' di terra scaricate da trasporti su un territorio nemico a inizio"
            + " turno (assalto anfibio, o territorio preso nel turno); trasporti inattivi = fermi e"
            + " senza carico a fine movimento della nazione.\n\n| Round |");
    alliances.forEach(a -> md.append(" Sbarcate ").append(allianceLabel(a)).append(" |"));
    alliances.forEach(a -> md.append(" Trasporti inattivi ").append(allianceLabel(a)).append(" |"));
    md.append(" Dove |\n|---|");
    alliances.forEach(a -> md.append("---|---|"));
    md.append("---|\n");
    final JsonArray landings =
        json.has("sbarchi") ? json.getAsJsonArray("sbarchi") : new JsonArray();
    for (final JsonElement e : json.getAsJsonArray("round")) {
      final JsonObject r = e.getAsJsonObject();
      final int n = r.get("round").getAsInt();
      md.append("| ").append(roundLabel(r)).append(" |");
      for (final String a : alliances) {
        final JsonObject landed =
            r.has("unita_sbarcate") ? r.getAsJsonObject("unita_sbarcate") : new JsonObject();
        md.append(' ').append(num(landed, a)).append(" |");
      }
      for (final String a : alliances) {
        md.append(' ').append(idleTransports(json, r, a)).append(" |");
      }
      final List<String> where = new ArrayList<>();
      for (final JsonElement le : landings) {
        final JsonObject l = le.getAsJsonObject();
        if (l.get("round").getAsInt() == n) {
          where.add(
              l.get("nazione").getAsString()
                  + " "
                  + l.get("unita").getAsInt()
                  + " su "
                  + l.get("territorio").getAsString());
        }
      }
      md.append(' ').append(where.isEmpty() ? "-" : String.join(", ", where)).append(" |\n");
    }
    final JsonArray britain =
        json.has("attacchi_regno_unito") ? json.getAsJsonArray("attacchi_regno_unito") : null;
    if (britain != null && !britain.isEmpty()) {
      md.append(
"""

### Attacchi tedeschi alle isole britanniche (Sea Lion)

""");
      md.append(seaLionText(britain)).append('\n');
    }
    final JsonArray ops = json.has("operazioni") ? json.getAsJsonArray("operazioni") : null;
    if (ops != null && !ops.isEmpty()) {
      md.append(
"""

### Operazioni anfibie bloccate

""");
      boolean any = false;
      for (final JsonElement e : ops) {
        final JsonObject o = e.getAsJsonObject();
        if ("bloccata".equals(text(o, "stato"))) {
          any = true;
          md.append("- R")
              .append(o.get("round").getAsInt())
              .append(' ')
              .append(text(o, "nazione"))
              .append(" - ")
              .append(text(o, "operazione"))
              .append(": ")
              .append(text(o, "motivo"))
              .append('\n');
        }
      }
      if (!any) {
        md.append("Nessuna.\n");
      }
    }
    return md.toString();
  }

  /**
   * Eg "R2 United Kingdom scartato (win% max 0%); R3 Scotland eseguito (win% 84%, max 90%)": the
   * German attacks on the British isles of the diary, in order.
   */
  static String seaLionText(final JsonArray attacks) {
    final List<String> parts = new ArrayList<>();
    for (final JsonElement e : attacks) {
      final JsonObject a = e.getAsJsonObject();
      final StringBuilder sb =
          new StringBuilder("R")
              .append(a.get("round").getAsInt())
              .append(' ')
              .append(text(a, "territorio"))
              .append(' ')
              .append(text(a, "stato"))
              .append(" (");
      if (a.has("win_stimata") && !a.get("win_stimata").isJsonNull()) {
        sb.append(String.format(Locale.ROOT, "win%% %.0f%%, ", a.get("win_stimata").getAsDouble()));
      }
      sb.append(
              a.has("win_max") && !a.get("win_max").isJsonNull()
                  ? String.format(Locale.ROOT, "max %.0f%%", a.get("win_max").getAsDouble())
                  : "max -")
          .append(')');
      parts.add(sb.toString());
    }
    return parts.isEmpty() ? "nessuno" : String.join("; ", parts);
  }

  /** Idle transports of the alliance's nations in round {@code r}. */
  static int idleTransports(final JsonObject json, final JsonObject r, final String alliance) {
    int idle = 0;
    final JsonArray members = json.getAsJsonObject("alleanze").getAsJsonArray(alliance);
    if (members == null) {
      return 0;
    }
    for (final JsonElement m : members) {
      final JsonObject n = r.getAsJsonObject("nazioni").getAsJsonObject(m.getAsString());
      if (n != null && n.has("trasporti_inattivi")) {
        idle += n.get("trasporti_inattivi").getAsInt();
      }
    }
    return idle;
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

    /** The {@code operazione_anfibia} events (round, nation, operation, state, reason...). */
    final JsonArray operations = new JsonArray();

    /**
     * German attacks considered on the British isles (Sea Lion): round, territory, state, win% with
     * the units assigned and the best possible.
     */
    final JsonArray attacksOnBritain = new JsonArray();

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
            spent.computeIfAbsent(key, k -> new int[4])[index] += pus;
          }
        }
        case "acquisti_totale" -> {
          final int[] s = spent.computeIfAbsent(key, k -> new int[4]);
          s[2] = e.get("pu_spesi").getAsInt();
          s[3] = e.has("pu_riparazioni") ? e.get("pu_riparazioni").getAsInt() : 0;
        }
        case "attacco" -> {
          if ("Germans".equals(e.get("nazione").getAsString())
              && e.has("territorio")
              && BRITISH_ISLES.contains(e.get("territorio").getAsString())) {
            final JsonObject a = new JsonObject();
            for (final String k :
                List.of(
                    "round", "territorio", "stato", "motivo_codice", "win_stimata", "win_max")) {
              if (e.has(k)) {
                a.add(k, e.get(k));
              }
            }
            attacksOnBritain.add(a);
          }
        }
        case "operazione_anfibia" -> {
          final JsonObject o = new JsonObject();
          for (final String k :
              List.of(
                  "round",
                  "nazione",
                  "operazione",
                  "stato",
                  "trasporti",
                  "truppe_a_bordo",
                  "scorta",
                  "sbarcate",
                  "motivo",
                  "prossimo_passo",
                  "accumulo")) {
            if (e.has(k)) {
              o.add(k, e.get(k));
            }
          }
          operations.add(o);
        }
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

  /** Eg "CONTRASTO (Romania, Egypt)" or "RALLENTAMENTO"; with the plan change of the round. */
  static String postureCell(final JsonObject v) {
    if (!v.has("postura")) {
      return "-";
    }
    final StringBuilder sb = new StringBuilder(v.get("postura").getAsString());
    if (v.has("contro_obiettivi") && !v.getAsJsonArray("contro_obiettivi").isEmpty()) {
      final List<String> names = new ArrayList<>();
      v.getAsJsonArray("contro_obiettivi").forEach(e -> names.add(e.getAsString()));
      sb.append(" (").append(String.join(", ", names)).append(')');
    }
    if (v.has("cambio")) {
      final JsonObject c = v.getAsJsonObject("cambio");
      sb.append(" - **cambio piano ")
          .append(c.get("da").getAsString())
          .append(" -> ")
          .append(c.get("a").getAsString())
          .append("**");
    }
    return sb.toString();
  }

  /**
   * Step 3d-1: per alliance, the plans of the game (round and reason of each change) and the
   * postures by runs of rounds.
   */
  static String plansMarkdown(final JsonArray rounds) {
    final Map<String, List<String>> changes = new LinkedHashMap<>();
    final Map<String, String> first = new LinkedHashMap<>();
    final Map<String, List<String>> postures = new LinkedHashMap<>();
    for (final JsonElement e : rounds) {
      final JsonObject r = e.getAsJsonObject();
      final int round = r.get("round").getAsInt();
      for (final Map.Entry<String, JsonElement> s :
          r.getAsJsonObject("stato_maggiore").entrySet()) {
        final JsonObject v = s.getValue().getAsJsonObject();
        first.putIfAbsent(s.getKey(), text(v, "piano"));
        if (v.has("cambio")) {
          final JsonObject c = v.getAsJsonObject("cambio");
          changes
              .computeIfAbsent(s.getKey(), k -> new ArrayList<>())
              .add(
                  "R"
                      + round
                      + " "
                      + c.get("da").getAsString()
                      + " -> "
                      + c.get("a").getAsString()
                      + " ("
                      + c.get("motivo").getAsString()
                      + ")");
          if (changes.get(s.getKey()).size() == 1) {
            first.put(s.getKey(), c.get("da").getAsString());
          }
        }
        postures
            .computeIfAbsent(s.getKey(), k -> new ArrayList<>())
            .add(v.has("postura") ? v.get("postura").getAsString() : "-");
      }
    }
    final StringBuilder md =
        new StringBuilder(
"""

### Piani e posture (step 3d-1)

""");
    for (final String alliance : first.keySet()) {
      md.append("- ")
          .append(allianceLabel(alliance))
          .append(": piano iniziale ")
          .append(first.get(alliance))
          .append(", cambi: ")
          .append(
              changes.containsKey(alliance) ? String.join("; ", changes.get(alliance)) : "nessuno")
          .append("; posture: ")
          .append(runs(postures.get(alliance)))
          .append('\n');
    }
    return md.toString();
  }

  /** Eg ["A", "A", "B"] -> "R1-2 A, R3 B". */
  static String runs(final List<String> values) {
    final List<String> parts = new ArrayList<>();
    int start = 0;
    for (int i = 1; i <= values.size(); i++) {
      if (i == values.size() || !values.get(i).equals(values.get(start))) {
        parts.add(
            (i - 1 == start ? "R" + (start + 1) : "R" + (start + 1) + "-" + i)
                + " "
                + values.get(start));
        start = i;
      }
    }
    return String.join(", ", parts);
  }
}
