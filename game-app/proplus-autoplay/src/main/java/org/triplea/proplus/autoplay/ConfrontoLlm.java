package org.triplea.proplus.autoplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import org.triplea.proplus.autoplay.ConfrontoProva.Entry;

/**
 * Step PROVALLM: the LLM at the decision points of the joint operations v2. Written only when some
 * game has an operation decided by an LLM. Decisions by type and action, how often they differ from
 * the formula, fallbacks and why, calibration of the estimated hold (LLM against the formula's on
 * the SAME decisions, against the real outcome), cost per game and of the run.
 */
final class ConfrontoLlm {
  private ConfrontoLlm() {}

  /** Hard cap of the run in USD (step PROVALLM). */
  static final double TETTO_RUN_USD = 40;

  /** Actions whose estimate can be checked against the outcome (the action was carried out). */
  static final Set<String> CALIBRABILI = Set.of("lancia", "procedi", "tieni", "ritira");

  /** One saved decision of a game. */
  record Decisione(String config, JsonObject rec) {
    String tipo() {
      return rec.get("punto").getAsString()
          + ("finale".equals(text(rec, "fase")) ? " (finale)" : "");
    }

    boolean llm() {
      return rec.has("prob_llm");
    }
  }

  static void write(final StringBuilder md, final List<Entry> entries) {
    final List<Entry> games = entries.stream().filter(Entry::llm).toList();
    if (games.isEmpty()) {
      return;
    }
    final List<Decisione> all = new ArrayList<>();
    for (final Entry e : games) {
      final JsonObject ops = e.json().getAsJsonObject("operazioni_congiunte");
      if (ops == null || !ops.has(e.operation()) || !ops.get(e.operation()).isJsonObject()) {
        continue;
      }
      final JsonObject op = ops.getAsJsonObject(e.operation());
      if (op.has("decisioni")) {
        for (final JsonElement d : op.getAsJsonArray("decisioni")) {
          all.add(new Decisione(e.key(), d.getAsJsonObject()));
        }
      }
    }
    md.append(
"""

## Decisioni LLM delle operazioni congiunte (step PROVALLM)

""");
    md.append(
        "Nei punti di decisione della v2 (tappa 1, tappa 2, tappe su Parigi, tenuta sotto il 70%"
            + " stimato) decide l'LLM; con risposta non valida, errore, timeout, tetto chiamate o"
            + " budget esaurito decide la formula v2 (ripiego). Esecuzione, presidio e rinforzi"
            + " sono del motore.\n\n");

    // decisions by type
    md.append(
"""
| Configurazione | Punto | Decisioni | Dall'LLM | Azioni LLM | Diverse dalla formula | Azioni della formula (stesse decisioni) | Ripieghi |
|---|---|---|---|---|---|---|---|
""");
    final Map<String, List<Decisione>> byType = new TreeMap<>();
    all.forEach(
        d -> byType.computeIfAbsent(d.config() + "|" + d.tipo(), k -> new ArrayList<>()).add(d));
    byType.forEach(
        (key, list) -> {
          final List<Decisione> llm = list.stream().filter(Decisione::llm).toList();
          final long differ =
              llm.stream()
                  .filter(d -> !text(d.rec(), "azione_llm").equals(text(d.rec(), "azione_formula")))
                  .count();
          md.append("| ")
              .append(key.substring(0, key.indexOf('|')))
              .append(" | ")
              .append(list.get(0).tipo())
              .append(" | ")
              .append(list.size())
              .append(" | ")
              .append(llm.size())
              .append(" | ")
              .append(counts(llm, "azione_llm"))
              .append(" | ")
              .append(differ)
              .append(llm.isEmpty() ? "" : " (" + pct(differ, llm.size()) + ")")
              .append(" | ")
              .append(counts(llm, "azione_formula"))
              .append(" | ")
              .append(list.size() - llm.size())
              .append(" |\n");
        });

    // fallbacks
    final Map<String, Integer> reasons = new TreeMap<>();
    int calls = 0;
    int errors = 0;
    int invalid = 0;
    double cost = 0;
    double maxCost = 0;
    long tokIn = 0;
    long tokOut = 0;
    long cacheRead = 0;
    long cacheWrite = 0;
    int budgetGames = 0;
    double budget = 0;
    for (final Entry e : games) {
      final JsonObject u = e.json().getAsJsonObject("operazioni_llm_uso");
      if (u == null) {
        continue;
      }
      calls += num(u, "chiamate");
      errors += num(u, "errori");
      invalid += num(u, "risposte_non_valide");
      final double c = u.has("costo_usd") ? u.get("costo_usd").getAsDouble() : 0;
      cost += c;
      maxCost = Math.max(maxCost, c);
      tokIn += num(u, "token_ingresso");
      tokOut += num(u, "token_uscita");
      cacheRead += num(u, "cache_lettura");
      cacheWrite += num(u, "cache_scrittura");
      if (u.has("ripieghi")) {
        for (final var r : u.getAsJsonObject("ripieghi").entrySet()) {
          reasons.merge(r.getKey(), r.getValue().getAsInt(), Integer::sum);
          if (r.getKey().equals("budget esaurito")) {
            budgetGames++;
          }
        }
      }
      final JsonObject p = e.json().getAsJsonObject("parametri");
      if (p != null && p.has("budget_operazioni_usd")) {
        budget = p.get("budget_operazioni_usd").getAsDouble();
      }
    }
    md.append("\n**Ripieghi sulla formula**: ");
    if (reasons.isEmpty()) {
      md.append("nessuno");
    } else {
      final List<String> r = new ArrayList<>();
      reasons.forEach((k, v) -> r.add(k + " " + v));
      md.append(String.join(", ", r));
    }
    md.append(
        String.format(
            Locale.ROOT,
            " (chiamate %d, errori o timeout %d, risposte non valide %d; partite con budget"
                + " esaurito almeno una volta: %d). Motivi dettagliati (primi 5): %s\n",
            calls,
            errors,
            invalid,
            budgetGames,
            details(all)));

    // calibration
    md.append(
"""

### Calibrazione: tenuta stimata contro tenuta reale

Decisioni eseguite (lancia, procedi, tieni, ritira) prese dall'LLM, con esito noto: reale = bersaglio dell'alleanza all'inizio del turno di chi chiude nel round dopo la decisione (un giro completo di turni nemici). Stesse decisioni per le due colonne; fasce per la stima di ciascuno.

| Fascia | LLM: decisioni | LLM: stimata | LLM: reale | Formula v2: decisioni | Formula: stimata | Formula: reale |
|---|---|---|---|---|---|---|
""");
    final List<JsonObject> cal =
        all.stream()
            .filter(Decisione::llm)
            .map(Decisione::rec)
            .filter(r -> r.has("esito_reale") && CALIBRABILI.contains(text(r, "azione")))
            .toList();
    final double[] eceLlm = new double[1];
    final double[] eceFormula = new double[1];
    for (int band = 0; band < 5; band++) {
      final int b = band;
      final List<JsonObject> l = cal.stream().filter(r -> band(r, "prob_llm") == b).toList();
      final List<JsonObject> f = cal.stream().filter(r -> band(r, "prob_formula") == b).toList();
      eceLlm[0] += gap(l, "prob_llm") * l.size();
      eceFormula[0] += gap(f, "prob_formula") * f.size();
      md.append("| ")
          .append(b * 20)
          .append('-')
          .append(b == 4 ? 100 : b * 20 + 19)
          .append("% | ")
          .append(l.size())
          .append(" | ")
          .append(meanPct(l, r -> r.get("prob_llm").getAsDouble()))
          .append(" | ")
          .append(meanPct(l, r -> r.get("esito_reale").getAsBoolean() ? 1 : 0))
          .append(" | ")
          .append(f.size())
          .append(" | ")
          .append(meanPct(f, r -> r.get("prob_formula").getAsDouble()))
          .append(" | ")
          .append(meanPct(f, r -> r.get("esito_reale").getAsBoolean() ? 1 : 0))
          .append(" |\n");
    }
    if (cal.isEmpty()) {
      md.append(
"""

Nessuna decisione eseguita con esito noto: calibrazione non calcolabile.
""");
    } else {
      final double ece1 = eceLlm[0] / cal.size() * 100;
      final double ece2 = eceFormula[0] / cal.size() * 100;
      final double brier1 = brier(cal, "prob_llm");
      final double brier2 = brier(cal, "prob_formula");
      md.append(
          String.format(
              Locale.ROOT,
"""

Errore medio per fascia (|stimata − reale| pesato sulle decisioni): LLM %.1f punti, formula %.1f punti; Brier LLM %.3f, formula %.3f (%d decisioni). **L'LLM stima la tenuta meglio della formula: %s**.
""",
              ece1,
              ece2,
              brier1,
              brier2,
              cal.size(),
              ece1 < ece2 && brier1 < brier2
                  ? "si'"
                  : ece1 < ece2 || brier1 < brier2
                      ? "in parte (un indice si', l'altro no)"
                      : "no"));
      final List<JsonObject> launched =
          cal.stream()
              .filter(
                  r -> !"tieni".equals(text(r, "azione")) && !"ritira".equals(text(r, "azione")))
              .toList();
      if (!launched.isEmpty()) {
        md.append(
            String.format(
                Locale.ROOT,
                "Solo tappe lanciate/procedute: %d, stimata LLM %s, formula %s, reale %s.\n",
                launched.size(),
                meanPct(launched, r -> r.get("prob_llm").getAsDouble()),
                meanPct(launched, r -> r.get("prob_formula").getAsDouble()),
                meanPct(launched, r -> r.get("esito_reale").getAsBoolean() ? 1 : 0)));
      }
    }

    // cost
    md.append(
"""

### Costo dell'LLM

""");
    md.append(
        String.format(
            Locale.ROOT,
            "Partite con LLM: %d. Costo stimato totale **$%.2f** (tetto della run $%.0f%s), per"
                + " partita $%.3f in media, massimo $%.3f (budget per partita $%.3f). Token:"
                + " ingresso %,d, uscita %,d, cache letta %,d, cache scritta %,d; chiamate %d"
                + " (%.1f per partita). Prezzi in `prezzi-stratega.json` (listino ufficiale"
                + " Anthropic).\n",
            games.size(),
            cost,
            TETTO_RUN_USD,
            cost > TETTO_RUN_USD ? " ⚠ SUPERATO" : ": rispettato",
            games.isEmpty() ? 0 : cost / games.size(),
            maxCost,
            budget,
            tokIn,
            tokOut,
            cacheRead,
            cacheWrite,
            calls,
            games.isEmpty() ? 0 : (double) calls / games.size()));
  }

  private static String details(final List<Decisione> all) {
    final List<String> out = new ArrayList<>();
    for (final Decisione d : all) {
      if (d.rec().has("dettaglio_ripiego") && out.size() < 5) {
        out.add(
            "R"
                + d.rec().get("round").getAsInt()
                + " "
                + text(d.rec(), "motivo_ripiego")
                + ": "
                + text(d.rec(), "dettaglio_ripiego").replace('|', '/'));
      }
    }
    return out.isEmpty() ? "nessuno" : String.join("; ", out);
  }

  private static int band(final JsonObject r, final String key) {
    if (!r.has(key)) {
      return -1;
    }
    return Math.min(4, (int) Math.floor(r.get(key).getAsDouble() * 100 / 20));
  }

  private static double gap(final List<JsonObject> l, final String key) {
    if (l.isEmpty()) {
      return 0;
    }
    final double est = l.stream().mapToDouble(r -> r.get(key).getAsDouble()).average().orElse(0);
    final double real =
        l.stream()
            .mapToDouble(r -> r.get("esito_reale").getAsBoolean() ? 1 : 0)
            .average()
            .orElse(0);
    return Math.abs(est - real);
  }

  static double brier(final List<JsonObject> l, final String key) {
    return l.stream()
        .mapToDouble(
            r -> {
              final double y = r.get("esito_reale").getAsBoolean() ? 1 : 0;
              final double p = r.get(key).getAsDouble();
              return (p - y) * (p - y);
            })
        .average()
        .orElse(0);
  }

  private static String meanPct(final List<JsonObject> l, final ToDoubleFunction<JsonObject> f) {
    return l.isEmpty()
        ? "-"
        : String.format(Locale.ROOT, "%.0f%%", l.stream().mapToDouble(f).average().orElse(0) * 100);
  }

  private static String counts(final List<Decisione> l, final String key) {
    final Map<String, Integer> c = new TreeMap<>();
    l.forEach(d -> c.merge(text(d.rec(), key), 1, Integer::sum));
    if (c.isEmpty()) {
      return "-";
    }
    final List<String> out = new ArrayList<>();
    c.forEach((k, v) -> out.add(k + " " + v));
    return String.join(", ", out);
  }

  private static String pct(final long n, final int of) {
    return String.format(Locale.ROOT, "%.0f%%", of == 0 ? 0 : 100.0 * n / of);
  }

  private static long num(final JsonObject o, final String key) {
    return o.has(key) ? o.get(key).getAsLong() : 0;
  }

  static String text(final JsonObject o, final String key) {
    return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
  }
}
