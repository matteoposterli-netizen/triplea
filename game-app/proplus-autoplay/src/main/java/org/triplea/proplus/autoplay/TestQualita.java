package org.triplea.proplus.autoplay;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.triplea.ai.proplus.strategia.llm.ClientApi;
import games.strategy.triplea.ai.proplus.strategia.llm.ErroreStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Fornitore;
import games.strategy.triplea.ai.proplus.strategia.llm.Http;
import games.strategy.triplea.ai.proplus.strategia.llm.PromptStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Richiesta;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import games.strategy.triplea.ai.proplus.strategia.llm.UscitaLlm;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Offline quality test of the LLM strategist: {@code qualita <folder> [--n 30] [--output DIR]
 * [--fornitore anthropic] [--fantasia 2]}. Takes N strategic states ({@code stato-R*-*.json}, eg
 * from the autoplay diaries; spread evenly over the sorted list) and asks the provider for each,
 * with the real prompt. Reports: valid JSON at the first try (parsed and usable: plan admitted,
 * known posture), directives the guard rails would limit, mean latency, total cost, 5 example
 * motivations. Writes {@code qualita.md} and {@code qualita.json}. The key comes from the
 * provider's environment variable (ANTHROPIC_API_KEY); without it nothing is called (exit 0).
 */
@Slf4j
final class TestQualita {
  static final String STRATEGY_RESOURCE = "proplus/strategia/ww2eur40_2nd_edition.json";

  private TestQualita() {}

  static int run(final String[] args) throws IOException {
    if (args.length == 0) {
      throw new IllegalArgumentException(
          "Uso: qualita <cartella> [--n 30] [--output DIR] [--fornitore anthropic] [--fantasia 2]");
    }
    final Path folder = Path.of(args[0]);
    final Map<String, String> opts = new LinkedHashMap<>();
    for (int i = 1; i + 1 < args.length; i += 2) {
      opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
    }
    final int n = Integer.parseInt(opts.getOrDefault("n", "30"));
    final Path output = Path.of(opts.getOrDefault("output", folder.toString()));
    final Fornitore provider =
        Fornitore.daId(opts.getOrDefault("fornitore", "anthropic"))
            .orElseThrow(() -> new IllegalArgumentException("fornitore sconosciuto"));
    final int fantasy = Integer.parseInt(opts.getOrDefault("fantasia", "2"));
    final String key = System.getenv(provider.variabile());
    if (key == null || key.isBlank()) {
      log.warn("AUTOPLAY qualita: {} assente, test saltato", provider.variabile());
      return 0;
    }
    final List<Path> states = pick(states(folder), n);
    final JsonObject report =
        valuta(states, provider, fantasy, r -> new ClientApi(Http.REALE).chiama(provider, key, r));
    Files.createDirectories(output);
    Files.writeString(
        output.resolve("qualita.json"),
        new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report),
        StandardCharsets.UTF_8);
    Files.writeString(output.resolve("qualita.md"), markdown(report), StandardCharsets.UTF_8);
    log.info("AUTOPLAY qualita: {}", report.get("sintesi").getAsString());
    return 0;
  }

  static List<Path> states(final Path folder) throws IOException {
    try (Stream<Path> s = Files.walk(folder)) {
      return s.filter(p -> p.getFileName().toString().matches("stato-R\\d+-.+\\.json"))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    }
  }

  /** {@code n} paths spread evenly over {@code all} (all of them if fewer). */
  static List<Path> pick(final List<Path> all, final int n) {
    if (all.size() <= n) {
      return all;
    }
    final List<Path> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(all.get((int) ((long) i * all.size() / n)));
    }
    return out;
  }

  /** Asks {@code call} for each state; the report (no key in it). */
  static JsonObject valuta(
      final List<Path> states,
      final Fornitore provider,
      final int fantasy,
      final Function<Richiesta, Risposta> call)
      throws IOException {
    final String system = PromptStratega.sistema(strategy());
    int valid = 0;
    int limited = 0;
    int answered = 0;
    long millis = 0;
    double cost = 0;
    long tokens = 0;
    final JsonArray cases = new JsonArray();
    final List<String> motivations = new ArrayList<>();
    for (final Path p : states) {
      final JsonObject state =
          JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
      final JsonObject c = new JsonObject();
      c.addProperty("file", p.getParent().getParent() == null
          ? p.getFileName().toString()
          : p.getParent().getFileName() + "/" + p.getFileName());
      try {
        final Risposta r =
            call.apply(
                new Richiesta(
                    system, PromptStratega.utente(state, fantasy), PromptStratega.schema(), fantasy));
        answered++;
        millis += r.millis();
        cost += r.costoUsd();
        tokens += r.tokenTotali();
        c.addProperty("ms", r.millis());
        c.addProperty("costo_usd", r.costoUsd());
        c.addProperty("token", r.tokenTotali());
        c.addProperty("cache_letta", r.cacheLettura());
        final int round = state.has("round") ? state.get("round").getAsInt() : 0;
        final String alliance = state.has("alleanza") ? state.get("alleanza").getAsString() : "";
        final JsonObject d = UscitaLlm.direttive(UscitaLlm.leggi(r.testo()), round, alliance);
        final UscitaLlm.Controllo check = UscitaLlm.controlla(d, state);
        c.addProperty("json_valido", check.valido());
        c.add("errori", strings(check.errori()));
        c.add("limitati", strings(check.limitati()));
        c.addProperty("postura", d.has("postura") ? d.get("postura").getAsString() : "");
        c.addProperty("piano", d.has("piano") ? d.get("piano").getAsString() : "");
        if (check.valido()) {
          valid++;
          if (!check.limitati().isEmpty()) {
            limited++;
          }
          final String m = d.has("motivazione") ? d.get("motivazione").getAsString() : "";
          if (motivations.size() < 5 && !m.isBlank()) {
            motivations.add("R" + round + " " + alliance + ": " + m);
          }
        }
      } catch (final ErroreStratega e) {
        c.addProperty("json_valido", false);
        c.addProperty("errore", e.getMessage());
      }
      cases.add(c);
    }
    final int total = states.size();
    final JsonObject out = new JsonObject();
    out.addProperty("fornitore", provider.etichetta());
    out.addProperty("modello", provider.modello());
    out.addProperty("prompt", PromptStratega.VERSIONE);
    out.addProperty("fantasia", fantasy);
    out.addProperty("stati", total);
    out.addProperty("risposte", answered);
    out.addProperty("json_validi_primo_colpo", valid);
    out.addProperty("pct_json_validi", total == 0 ? 0 : 100.0 * valid / total);
    out.addProperty("direttive_limitate_dai_paletti", limited);
    out.addProperty("pct_limitate", valid == 0 ? 0 : 100.0 * limited / valid);
    out.addProperty("latenza_media_ms", answered == 0 ? 0 : millis / answered);
    out.addProperty("token_totali", tokens);
    out.addProperty("costo_totale_usd", cost);
    final JsonArray m = new JsonArray();
    motivations.forEach(m::add);
    out.add("motivazioni", m);
    out.add("casi", cases);
    out.addProperty(
        "sintesi",
        String.format(
            Locale.ROOT,
            "%s (%s): %d stati, JSON validi al primo colpo %d (%.0f%%), limitate dai paletti %d"
                + " (%.0f%% delle valide), latenza media %.1f s, costo totale $%.4f",
            provider.etichetta(),
            provider.modello(),
            total,
            valid,
            out.get("pct_json_validi").getAsDouble(),
            limited,
            out.get("pct_limitate").getAsDouble(),
            (answered == 0 ? 0 : millis / answered) / 1000.0,
            cost));
    return out;
  }

  static String markdown(final JsonObject r) {
    final StringBuilder md = new StringBuilder("# Test di qualita' dello stratega LLM\n\n");
    md.append(r.get("sintesi").getAsString()).append(".\n\n");
    md.append("- Prompt: ")
        .append(r.get("prompt").getAsString())
        .append(", Fantasia ")
        .append(r.get("fantasia").getAsInt())
        .append(", token totali ")
        .append(r.get("token_totali").getAsLong())
        .append("\n- JSON valido = risposta letta, piano ammesso, postura nota (altrimenti nel gioco"
            + " decidono le regole); limitata = almeno un valore tagliato o ignorato dai paletti.\n\n");
    md.append("## 5 motivazioni\n\n");
    r.getAsJsonArray("motivazioni").forEach(m -> md.append("- ").append(m.getAsString()).append('\n'));
    md.append("\n## Casi\n\n| Stato | Valido | Piano | Postura | Limitazioni / errore | ms | Token | $ |\n");
    md.append("|---|---|---|---|---|---|---|---|\n");
    r.getAsJsonArray("casi")
        .forEach(
            e -> {
              final JsonObject c = e.getAsJsonObject();
              final String notes =
                  c.has("errore")
                      ? c.get("errore").getAsString()
                      : String.join(
                          "; ",
                          concat(c.getAsJsonArray("errori"), c.getAsJsonArray("limitati")));
              md.append("| ")
                  .append(c.get("file").getAsString())
                  .append(" | ")
                  .append(c.get("json_valido").getAsBoolean() ? "si'" : "NO")
                  .append(" | ")
                  .append(c.has("piano") ? c.get("piano").getAsString() : "-")
                  .append(" | ")
                  .append(c.has("postura") ? c.get("postura").getAsString() : "-")
                  .append(" | ")
                  .append(notes.replace("|", "/"))
                  .append(" | ")
                  .append(c.has("ms") ? c.get("ms").getAsLong() : 0)
                  .append(" | ")
                  .append(c.has("token") ? c.get("token").getAsLong() : 0)
                  .append(" | ")
                  .append(
                      String.format(
                          Locale.ROOT,
                          "%.4f",
                          c.has("costo_usd") ? c.get("costo_usd").getAsDouble() : 0))
                  .append(" |\n");
            });
    return md.toString();
  }

  private static List<String> concat(final JsonArray a, final JsonArray b) {
    final List<String> out = new ArrayList<>();
    Optional.ofNullable(a).ifPresent(x -> x.forEach(e -> out.add(e.getAsString())));
    Optional.ofNullable(b).ifPresent(x -> x.forEach(e -> out.add(e.getAsString())));
    return out;
  }

  private static JsonArray strings(final List<String> list) {
    final JsonArray a = new JsonArray();
    list.forEach(a::add);
    return a;
  }

  private static JsonObject strategy() throws IOException {
    try (InputStream in = TestQualita.class.getClassLoader().getResourceAsStream(STRATEGY_RESOURCE)) {
      if (in == null) {
        throw new IOException("manca " + STRATEGY_RESOURCE);
      }
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }
}
