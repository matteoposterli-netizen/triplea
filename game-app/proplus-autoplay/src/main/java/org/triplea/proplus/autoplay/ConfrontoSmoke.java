package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Step STRATEGA LLM, mode [smoke]: a check that the "Pro+ Stratega LLM" player works (not a measure
 * of strength). Written only when some game has a strategist alliance: games completed, calls and
 * cost per game, fallbacks and why, operations by type (launched, completed, failed, cancelled,
 * rejected), calibration of the estimated hold against the real outcome, VC at the end for
 * information only.
 */
final class ConfrontoSmoke {
  private ConfrontoSmoke() {}

  /** Hard cap of the [smoke] run in USD. */
  static final double TETTO_RUN_USD = 30;

  /**
   * Actions left out of the calibration: their estimate (~0) is not a forecast of an operation the
   * engine carries out, it only says why the operation stops; counting them fills the low bands
   * with stops whose target was held anyway (run 42775993327: error 30.1 instead of 18.7 points).
   */
  static final Set<String> NON_ESEGUITE = Set.of("annulla", "rinvia", "ritira");

  static void write(
      final StringBuilder md,
      final List<RiepilogoGenerale.Game> games,
      final List<String> alliances) {
    final List<RiepilogoGenerale.Game> llm = new ArrayList<>();
    for (final RiepilogoGenerale.Game g : games) {
      if (g.json() == null) {
        if (g.folder().toLowerCase(Locale.ROOT).contains("stratega")) {
          llm.add(g);
        }
        continue;
      }
      final JsonObject types = g.json().getAsJsonObject("parametri").getAsJsonObject("tipi");
      if (alliances.stream()
          .anyMatch(a -> AutoplayOptions.STRATEGA.equals(RiepilogoGenerale.text(types, a)))) {
        llm.add(g);
      }
    }
    if (llm.isEmpty()) {
      return;
    }
    md.append(
"""

## Stratega LLM: controllo [smoke] (non e' una misura di forza)

""");
    md.append(
        "Partite con un'alleanza \"Pro+ Stratega LLM (Europe 1940)\" e l'altra Hard. VC a fine partita"
            + " solo a titolo informativo.\n\n");
    md.append(
        "| Partita | Stato | Stratega | Round decisi / senza LLM | Chiamate | Costo $ | Ripieghi |"
            + " Operazioni (avviate) | VC finali (stratega / Hard) |\n");
    md.append("|---|---|---|---|---|---|---|---|---|\n");
    int completed = 0;
    double cost = 0;
    int calls = 0;
    final Map<String, Integer> reasons = new TreeMap<>();
    final Map<String, int[]> byType =
        new TreeMap<>(); // avviate, completate, fallite, annullate, in corso, eseguite
    final Map<String, Integer> rejected = new TreeMap<>();
    final double[][] bands = new double[5][3]; // sum estimated, sum real, n
    final double[][] bandsOrders = new double[5][3];
    for (final RiepilogoGenerale.Game g : llm) {
      if (g.json() == null) {
        md.append("| ")
            .append(g.folder())
            .append(" | FALLITA (nessun riepilogo) | | | | | | | |\n");
        continue;
      }
      final JsonObject j = g.json();
      final String status = RiepilogoGenerale.text(j, "stato");
      if ("completata".equals(status)) {
        completed++;
      }
      final JsonObject types = j.getAsJsonObject("parametri").getAsJsonObject("tipi");
      final String side =
          alliances.stream()
              .filter(a -> AutoplayOptions.STRATEGA.equals(RiepilogoGenerale.text(types, a)))
              .findFirst()
              .orElse("");
      final JsonObject s =
          j.has("stratega_llm") && j.get("stratega_llm").isJsonObject()
              ? j.getAsJsonObject("stratega_llm")
              : new JsonObject();
      final JsonObject uso = s.has("uso") ? s.getAsJsonObject("uso") : new JsonObject();
      final double c = uso.has("costo_usd") ? uso.get("costo_usd").getAsDouble() : 0;
      final int n = uso.has("chiamate") ? uso.get("chiamate").getAsInt() : 0;
      cost += c;
      calls += n;
      final Map<String, Integer> gameReasons = new TreeMap<>();
      for (final String level : List.of("strategico", "ordini_del_turno")) {
        if (uso.has(level) && uso.getAsJsonObject(level).has("ripieghi")) {
          for (final var r : uso.getAsJsonObject(level).getAsJsonObject("ripieghi").entrySet()) {
            gameReasons.merge(level + ": " + r.getKey(), r.getValue().getAsInt(), Integer::sum);
            reasons.merge(level + ": " + r.getKey(), r.getValue().getAsInt(), Integer::sum);
          }
        }
      }
      final JsonObject a = s.has(side) ? s.getAsJsonObject(side) : new JsonObject();
      final JsonArray ops = a.has("operazioni") ? a.getAsJsonArray("operazioni") : new JsonArray();
      final List<String> opText = new ArrayList<>();
      for (final JsonElement e : ops) {
        final JsonObject o = e.getAsJsonObject();
        final String tipo = RiepilogoGenerale.text(o, "tipo");
        final String stato = RiepilogoGenerale.text(o, "stato");
        final int[] t = byType.computeIfAbsent(tipo, k -> new int[6]);
        t[0]++;
        switch (stato) {
          case "completata" -> t[1]++;
          case "fallita" -> t[2]++;
          case "annullata" -> t[3]++;
          default -> t[4]++;
        }
        if (o.has("tappa1_round")
            || "tenuta".equals(tipo)
            || "rinforzo".equals(tipo)
            || (o.has("presa") && o.get("presa").getAsBoolean())) {
          t[5]++;
        }
        opText.add(tipo + " " + RiepilogoGenerale.text(o, "bersaglio") + " (" + stato + ")");
        if (o.has("previsioni")) {
          for (final JsonElement p : o.getAsJsonArray("previsioni")) {
            final JsonObject x = p.getAsJsonObject();
            if (x.has("esito_reale") && eseguita(x)) {
              band(
                  bands,
                  x.get("prob_tenuta_stimata").getAsDouble(),
                  x.get("esito_reale").getAsBoolean());
            }
          }
        }
        if (o.has("decisioni_ordini")) {
          for (final JsonElement p : o.getAsJsonArray("decisioni_ordini")) {
            final JsonObject x = p.getAsJsonObject();
            if (x.has("prob_llm") && x.has("esito_reale") && eseguita(x)) {
              band(
                  bandsOrders,
                  x.get("prob_llm").getAsDouble(),
                  x.get("esito_reale").getAsBoolean());
            }
          }
        }
      }
      if (a.has("rifiutate")) {
        for (final JsonElement e : a.getAsJsonArray("rifiutate")) {
          final String why = RiepilogoGenerale.text(e.getAsJsonObject(), "motivo");
          rejected.merge(why.replaceAll("[0-9]+", "N"), 1, Integer::sum);
        }
      }
      md.append("| ")
          .append(g.folder())
          .append(" | ")
          .append(status)
          .append(" | ")
          .append(Riepilogo.allianceLabel(side))
          .append(" | ")
          .append(a.has("round_decisi") ? a.get("round_decisi").getAsInt() : 0)
          .append(" / ")
          .append(a.has("round_senza_llm") ? a.get("round_senza_llm").getAsInt() : 0)
          .append(" | ")
          .append(n)
          .append(" | ")
          .append(String.format(Locale.ROOT, "%.3f", c))
          .append(" | ")
          .append(gameReasons.isEmpty() ? "nessuno" : gameReasons.toString())
          .append(" | ")
          .append(opText.isEmpty() ? "nessuna" : String.join("; ", opText))
          .append(" | ")
          .append(vc(j, side, alliances))
          .append(" |\n");
    }
    md.append(
        String.format(
            Locale.ROOT,
            "%nPartite completate: **%d/%d**. Chiamate: %d (%.1f per partita). Costo totale **$%.3f**"
                + " (tetto della run $%.0f), medio per partita **$%.3f**.%n%n",
            completed,
            llm.size(),
            calls,
            llm.isEmpty() ? 0.0 : calls / (double) llm.size(),
            cost,
            TETTO_RUN_USD,
            llm.isEmpty() ? 0.0 : cost / llm.size()));
    md.append("Ripieghi (paracadute = Hard) per motivo: ")
        .append(reasons.isEmpty() ? "nessuno" : reasons.toString())
        .append("\n\n");
    md.append(
        "| Tipo | Avviate | Eseguite (tappe o tenuta) | Completate | Fallite | Annullate | Ancora in corso |\n");
    md.append("|---|---|---|---|---|---|---|\n");
    byType.forEach(
        (k, t) ->
            md.append("| ")
                .append(k)
                .append(" | ")
                .append(t[0])
                .append(" | ")
                .append(t[5])
                .append(" | ")
                .append(t[1])
                .append(" | ")
                .append(t[2])
                .append(" | ")
                .append(t[3])
                .append(" | ")
                .append(t[4])
                .append(" |\n"));
    final long used = byType.values().stream().filter(t -> t[5] > 0).count();
    md.append("\nTipi di operazione usati realmente: **").append(used).append("**. ");
    md.append("Rifiutate dal motore: ")
        .append(rejected.isEmpty() ? "nessuna" : rejected.toString())
        .append("\n\n");
    calibration(
        md,
        "livello strategico (avvia/continua/modifica; stima a inizio round, esito a fine durata)",
        bands);
    calibration(
        md,
        "ordini del turno (lancia/procedi/tieni; stima nel punto di decisione, esito al round dopo)",
        bandsOrders);
  }

  /** True when the decision is one the engine carries out (not annulla / rinvia / ritira). */
  static boolean eseguita(final JsonObject decision) {
    return !NON_ESEGUITE.contains(RiepilogoGenerale.text(decision, "azione"));
  }

  private static void band(final double[][] b, final double p, final boolean real) {
    final int i = Math.min(4, (int) Math.floor(p * 5));
    b[i][0] += p;
    b[i][1] += real ? 1 : 0;
    b[i][2]++;
  }

  private static void calibration(final StringBuilder md, final String title, final double[][] b) {
    md.append("Calibrazione prob_tenuta stimata contro reale, ").append(title).append(":\n\n");
    md.append(
"""
| Fascia | Stime | Stimata media | Reale |
|---|---|---|---|
""");
    double err = 0;
    double n = 0;
    for (int i = 0; i < 5; i++) {
      if (b[i][2] == 0) {
        continue;
      }
      final double est = b[i][0] / b[i][2];
      final double real = b[i][1] / b[i][2];
      err += Math.abs(est - real) * b[i][2];
      n += b[i][2];
      md.append(
          String.format(
              Locale.ROOT,
              "| %d-%d%% | %.0f | %.0f%% | %.0f%% |%n",
              i * 20,
              i * 20 + 19 + (i == 4 ? 1 : 0),
              b[i][2],
              est * 100,
              real * 100));
    }
    md.append(
        n == 0
            ?
"""

Nessuna stima con esito.

"""
            : String.format(
                Locale.ROOT,
                "%nErrore medio pesato: %.1f punti (%d stime).%n%n",
                err / n * 100,
                (int) n));
  }

  private static String vc(final JsonObject j, final String side, final List<String> alliances) {
    final JsonArray rounds = j.getAsJsonArray("round");
    if (rounds == null || rounds.isEmpty()) {
      return "-";
    }
    final JsonObject last = rounds.get(rounds.size() - 1).getAsJsonObject();
    if (!last.has("vc")) {
      return "-";
    }
    final JsonObject vc = last.getAsJsonObject("vc");
    final String other = alliances.stream().filter(a -> !a.equals(side)).findFirst().orElse("");
    return num(vc, side) + " / " + num(vc, other);
  }

  private static String num(final JsonObject vc, final String a) {
    return vc.has(a) && vc.get(a).isJsonObject() && vc.getAsJsonObject(a).has("numero")
        ? String.valueOf(vc.getAsJsonObject(a).get("numero").getAsInt())
        : "-";
  }
}
