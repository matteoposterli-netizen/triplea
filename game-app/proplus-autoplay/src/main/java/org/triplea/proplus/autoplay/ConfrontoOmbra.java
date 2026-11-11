package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step R2a: "Campagne in ombra". From {@code stratega_llm.<alliance>.ombra} of every game (state of
 * {@code CampagnaOmbra}): feasible candidates per alliance, round and margin; error of the
 * predictions (defenders at +1/+2/+3 rounds, income, own force); losses of the real battles under
 * the predicted ones per margin; rule 2.6 per case (attacked, lost, units Hard left against the
 * predicted garrison); share of rounds with the shadow block.
 */
final class ConfrontoOmbra {
  private ConfrontoOmbra() {}

  static final List<String> MARGINI = List.of("60", "70", "80");

  static void write(final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final Map<String, List<JsonObject>> byAlliance = new LinkedHashMap<>();
    final Map<String, int[]> blocks = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("stratega_llm")) {
        continue;
      }
      final JsonObject s = g.json().getAsJsonObject("stratega_llm");
      for (final String a : alliances) {
        if (!s.has(a) || !s.getAsJsonObject(a).has("ombra")) {
          continue;
        }
        final JsonObject o = s.getAsJsonObject(a).getAsJsonObject("ombra");
        if (o.size() == 0) {
          continue;
        }
        byAlliance.computeIfAbsent(a, k -> new ArrayList<>()).add(o);
        final int rounds = roundsPlayed(g.json());
        final int with =
            o.has("round_con_blocco") ? o.getAsJsonArray("round_con_blocco").size() : 0;
        final int[] b = blocks.computeIfAbsent(a, k -> new int[2]);
        b[0] += Math.min(with, rounds);
        b[1] += rounds;
      }
    }
    if (byAlliance.isEmpty()) {
      return;
    }
    md.append("\n## Campagne in ombra (step R2a, nessun effetto sul gioco)\n\n");
    md.append(
        "Pianificatore di campagna calcolato e scritto nel diario senza agire (forze stimate con"
            + " `estimateStrength`, nessun dado; margini 60/70/80 = forza nemica e perdite"
            + " moltiplicate per i fattori del file dati). Blocco dell'ombra presente: ");
    final List<String> parts = new ArrayList<>();
    blocks.forEach(
        (a, b) ->
            parts.add(
                String.format(
                    Locale.ROOT,
                    "%s %d/%d round (%.0f%%)",
                    Riepilogo.allianceLabel(a),
                    b[0],
                    b[1],
                    b[1] == 0 ? 0 : 100.0 * b[0] / b[1])));
    md.append(String.join(", ", parts)).append(".\n\n");

    // 1) feasible candidates per alliance, round and margin
    md.append(
        "Candidati fattibili per alleanza, round e margine (somma sulle partite; obiettivi piu'"
            + " frequenti; round stimato e quota dei PU medi dei fattibili):\n\n"
            + "| Alleanza | Margine | Round | Fattibili / valutati | Obiettivi | Round stimato |"
            + " Quota PU |\n|---|---|---|---|---|---|---|\n");
    byAlliance.forEach(
        (a, list) -> {
          for (final String m : MARGINI) {
            final Map<Integer, List<JsonObject>> perRound = new TreeMap<>();
            for (final JsonObject o : list) {
              if (!o.has("fattibili")) {
                continue;
              }
              for (final JsonElement e : o.getAsJsonArray("fattibili")) {
                final JsonObject x = e.getAsJsonObject();
                if (x.get("margine").getAsString().equals(m)) {
                  perRound
                      .computeIfAbsent(x.get("round").getAsInt(), k -> new ArrayList<>())
                      .add(x);
                }
              }
            }
            perRound.forEach(
                (r, xs) -> {
                  final List<JsonObject> ok =
                      xs.stream().filter(x -> x.get("fattibile").getAsBoolean()).toList();
                  final Map<String, Integer> targets = new TreeMap<>();
                  ok.forEach(x -> targets.merge(x.get("obiettivo").getAsString(), 1, Integer::sum));
                  final String top =
                      targets.entrySet().stream()
                          .sorted(
                              Comparator.comparingInt(
                                      (Map.Entry<String, Integer> e) -> -e.getValue())
                                  .thenComparing(Map.Entry::getKey))
                          .limit(3)
                          .map(e -> e.getKey() + " " + e.getValue())
                          .reduce((x, y) -> x + ", " + y)
                          .orElse("-");
                  md.append("| ")
                      .append(Riepilogo.allianceLabel(a))
                      .append(" | ")
                      .append(m)
                      .append(" | R")
                      .append(r)
                      .append(" | ")
                      .append(ok.size())
                      .append(" / ")
                      .append(xs.size())
                      .append(" | ")
                      .append(top)
                      .append(" | ")
                      .append(
                          ok.isEmpty()
                              ? "-"
                              : num(
                                  ok.stream()
                                      .mapToDouble(x -> x.get("round_stimato").getAsDouble())
                                      .average()
                                      .orElse(0)))
                      .append(" | ")
                      .append(
                          ok.isEmpty()
                              ? "-"
                              : num(
                                      ok.stream()
                                          .mapToDouble(x -> x.get("quota_pu_pct").getAsDouble())
                                          .average()
                                          .orElse(0))
                                  + "%")
                      .append(" |\n");
                });
          }
        });

    // 2) errors of the predictions
    md.append(
        "\nErrore delle previsioni (mediana dell'errore assoluto; % = errore / valore reale, solo"
            + " con valore reale > 0):\n\n"
            + "| Alleanza | Previsione | Orizzonte | Confronti | Mediana errore | Mediana % |\n"
            + "|---|---|---|---|---|---|\n");
    byAlliance.forEach(
        (a, list) -> {
          for (final String kind : List.of("difensori", "reddito", "forza")) {
            for (int k = 1; k <= 3; k++) {
              final List<Double> abs = new ArrayList<>();
              final List<Double> pct = new ArrayList<>();
              for (final JsonObject o : list) {
                final JsonObject st =
                    o.has("statistiche") ? o.getAsJsonObject("statistiche") : null;
                if (st == null || !st.has(kind + "_" + k)) {
                  continue;
                }
                for (final JsonElement e : st.getAsJsonArray(kind + "_" + k)) {
                  final double err = e.getAsJsonArray().get(0).getAsDouble();
                  final double real = e.getAsJsonArray().get(1).getAsDouble();
                  abs.add(err);
                  if (real > 0) {
                    pct.add(100 * err / real);
                  }
                }
              }
              if (abs.isEmpty()) {
                continue;
              }
              md.append("| ")
                  .append(Riepilogo.allianceLabel(a))
                  .append(" | ")
                  .append(
                      switch (kind) {
                        case "difensori" -> "difensori (TUV)";
                        case "reddito" -> "reddito (PU)";
                        default -> "forza propria (TUV)";
                      })
                  .append(" | +")
                  .append(k)
                  .append(" | ")
                  .append(abs.size())
                  .append(" | ")
                  .append(num(median(abs)))
                  .append(" | ")
                  .append(pct.isEmpty() ? "-" : num(median(pct)) + "%")
                  .append(" |\n");
            }
          }
        });

    // 3) battles: real loss under the predicted one, per margin
    md.append(
        "\nBattaglie reali della nazione sulla prima tappa prevista: quota con perdita reale <="
            + " prevista (attese circa 60%, 70%, 80%):\n\n"
            + "| Alleanza | Margine | Battaglie | Sotto il previsto |\n|---|---|---|---|\n");
    byAlliance.forEach(
        (a, list) -> {
          for (final String m : MARGINI) {
            int n = 0;
            int under = 0;
            for (final JsonObject o : list) {
              final JsonObject st = o.has("statistiche") ? o.getAsJsonObject("statistiche") : null;
              if (st != null && st.has("battaglie_" + m)) {
                n += st.getAsJsonObject("battaglie_" + m).get("n").getAsInt();
                under += st.getAsJsonObject("battaglie_" + m).get("sotto_il_previsto").getAsInt();
              }
            }
            md.append("| ")
                .append(Riepilogo.allianceLabel(a))
                .append(" | ")
                .append(m)
                .append(" | ")
                .append(n)
                .append(" | ")
                .append(
                    n == 0
                        ? "-"
                        : String.format(Locale.ROOT, "%d (%.0f%%)", under, 100.0 * under / n))
                .append(" |\n");
          }
        });

    // 4) rule 2.6
    md.append(
        "\nRegola 2.6 (retrovie e percorso): territori classificati, attaccati e persi nel turno"
            + " nemico dopo; unita' di terra lasciate da Hard a fine turno contro il presidio"
            + " previsto (medie per territorio):\n\n"
            + "| Alleanza | Caso | Territori | Attaccati | Persi | Presidio previsto | Lasciate da"
            + " Hard |\n|---|---|---|---|---|---|---|\n");
    byAlliance.forEach(
        (a, list) -> {
          for (final String caso : List.of("a", "b", "c")) {
            final int[] s = new int[5];
            for (final JsonObject o : list) {
              final JsonObject st = o.has("statistiche") ? o.getAsJsonObject("statistiche") : null;
              if (st == null || !st.has("caso_" + caso)) {
                continue;
              }
              final JsonObject c = st.getAsJsonObject("caso_" + caso);
              s[0] += c.get("n").getAsInt();
              s[1] += c.get("attaccati").getAsInt();
              s[2] += c.get("persi").getAsInt();
              s[3] += c.get("presidio_previsto").getAsInt();
              s[4] += c.get("unita_lasciate_da_hard").getAsInt();
            }
            md.append("| ")
                .append(Riepilogo.allianceLabel(a))
                .append(" | ")
                .append(caso)
                .append(" | ")
                .append(s[0])
                .append(" | ")
                .append(pct(s[1], s[0]))
                .append(" | ")
                .append(pct(s[2], s[0]))
                .append(" | ")
                .append(s[0] == 0 ? "-" : num((double) s[3] / s[0]))
                .append(" | ")
                .append(s[0] == 0 ? "-" : num((double) s[4] / s[0]))
                .append(" |\n");
          }
        });
  }

  private static int roundsPlayed(final JsonObject json) {
    return json.has("round") && json.get("round").isJsonArray()
        ? (int)
            json.getAsJsonArray("round").asList().stream()
                .filter(e -> e.getAsJsonObject().get("completo").getAsBoolean())
                .count()
        : 0;
  }

  static double median(final List<Double> v) {
    final List<Double> s = new ArrayList<>(v);
    s.sort(Double::compare);
    final int n = s.size();
    return n == 0 ? Double.NaN : n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
  }

  private static String pct(final int a, final int n) {
    return n == 0 ? "-" : String.format(Locale.ROOT, "%d (%.0f%%)", a, 100.0 * a / n);
  }

  private static String num(final double x) {
    return String.format(Locale.ROOT, "%.1f", x);
  }

  @SuppressWarnings("unused")
  private static JsonArray empty() {
    return new JsonArray();
  }
}
