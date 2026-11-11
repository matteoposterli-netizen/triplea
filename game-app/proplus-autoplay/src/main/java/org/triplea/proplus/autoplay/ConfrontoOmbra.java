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
    md.append(
"""

## Campagne in ombra (step R2a, nessun effetto sul gioco)

""");
    md.append(
        "Pianificatore di campagna calcolato e scritto nel diario senza agire. Tabelle R2a (forze"
            + " stimate con `estimateStrength`; margini 60/70/80 = forza nemica e perdite"
            + " moltiplicate per i fattori del file dati, modello dei difensori M0) e, sotto,"
            + " quelle R2a-bis (calcolatore vero, percentili, tre modelli, retrovie). Blocco"
            + " dell'ombra presente: ");
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
"""
Candidati fattibili per alleanza, round e margine (somma sulle partite; obiettivi piu' frequenti; round stimato e quota dei PU medi dei fattibili):

| Alleanza | Margine | Round | Fattibili / valutati | Obiettivi | Round stimato | Quota PU |
|---|---|---|---|---|---|---|
""");
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
"""

Errore delle previsioni (mediana dell'errore assoluto; % = errore / valore reale, solo con valore reale > 0):

| Alleanza | Previsione | Orizzonte | Confronti | Mediana errore | Mediana % |
|---|---|---|---|---|---|
""");
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
"""

Battaglie reali della nazione sulla prima tappa prevista: quota con perdita reale <= prevista (attese circa 60%, 70%, 80%):

| Alleanza | Margine | Battaglie | Sotto il previsto |
|---|---|---|---|
""");
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
"""

Regola 2.6 (retrovie e percorso): territori classificati, attaccati e persi nel turno nemico dopo; unita' di terra lasciate da Hard a fine turno contro il presidio previsto (medie per territorio):

| Alleanza | Caso | Territori | Attaccati | Persi | Presidio previsto | Lasciate da Hard |
|---|---|---|---|---|---|---|
""");
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
    writeBis(md, games, alliances);
  }

  // ============================================================================ R2a-bis

  /**
   * Step R2a-bis: the tables of the two measures (rows of {@code ombra_dettagli}, complete from the
   * diary; Measure 2 and the lost rear territories from {@code stratega_llm.<a>.ombra}).
   */
  static void writeBis(
      final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final Map<String, JsonObject> rows = new LinkedHashMap<>();
    final Map<String, List<JsonObject>> shadows = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      if (g.json().has("ombra_dettagli")) {
        for (final String a : alliances) {
          final JsonObject d = g.json().getAsJsonObject("ombra_dettagli");
          if (!d.has(a)) {
            continue;
          }
          final JsonObject src = d.getAsJsonObject(a);
          final JsonObject acc = rows.computeIfAbsent(a, k -> new JsonObject());
          for (final String k :
              List.of(
                  "difensori", "contrattacco", "battaglie_calcolatore", "fattibili", "retrovie")) {
            final JsonArray to = acc.has(k) ? acc.getAsJsonArray(k) : new JsonArray();
            if (src.has(k)) {
              to.addAll(src.getAsJsonArray(k));
            }
            acc.add(k, to);
          }
          for (final String k : List.of("chiamate_calcolatore", "errori_calcolatore")) {
            acc.addProperty(
                k,
                (acc.has(k) ? acc.get(k).getAsInt() : 0)
                    + (src.has(k) ? src.get(k).getAsInt() : 0));
          }
          final JsonObject mt =
              acc.has("metodi") ? acc.getAsJsonObject("metodi") : new JsonObject();
          if (src.has("metodi")) {
            src.getAsJsonObject("metodi")
                .entrySet()
                .forEach(
                    e ->
                        mt.addProperty(
                            e.getKey(),
                            (mt.has(e.getKey()) ? mt.get(e.getKey()).getAsInt() : 0)
                                + e.getValue().getAsInt()));
          }
          acc.add("metodi", mt);
          acc.addProperty("partite", (acc.has("partite") ? acc.get("partite").getAsInt() : 0) + 1);
        }
      }
      if (g.json().has("stratega_llm")) {
        final JsonObject s = g.json().getAsJsonObject("stratega_llm");
        for (final String a : alliances) {
          if (s.has(a) && s.getAsJsonObject(a).has("ombra")) {
            shadows
                .computeIfAbsent(a, k -> new ArrayList<>())
                .add(s.getAsJsonObject(a).getAsJsonObject("ombra"));
          }
        }
      }
    }
    if (rows.isEmpty() && shadows.isEmpty()) {
      return;
    }
    md.append(
"""

### R2a-bis: previsioni ricalibrate e retrovie (ancora in ombra)

""");
    rows.forEach(
        (a, r) ->
            md.append(
                String.format(
                    Locale.ROOT,
                    "%s: %d partite, chiamate al calcolatore %d (%.0f a partita), errori del"
                        + " calcolatore %d; perdite per simulazione lette: %s.\n",
                    Riepilogo.allianceLabel(a),
                    r.get("partite").getAsInt(),
                    r.get("chiamate_calcolatore").getAsInt(),
                    r.get("chiamate_calcolatore").getAsDouble()
                        / Math.max(1, r.get("partite").getAsInt()),
                    r.get("errori_calcolatore").getAsInt(),
                    r.getAsJsonObject("metodi").entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue())
                        .reduce((x, y) -> x + ", " + y)
                        .orElse("-"))));

    // 1.2 defenders: three models
    md.append(
"""

**1.2 Difensori futuri, tre modelli affiancati** (errore = previsto - reale, TUV; mediana con segno / mediana dell'errore assoluto; M0 = presenti + rinforzi + acquisti, M1 = presenti, M2 = presenti + quota osservata nella partita dell'aumento di M0). Tutti i tipi:

| Alleanza | Orizzonte | Confronti | M0 segno / ass. | M1 segno / ass. | M2 segno / ass. | Migliore (ass.) |
|---|---|---|---|---|---|---|
""");
    rows.forEach(
        (a, r) -> {
          for (int k = 1; k <= 3; k++) {
            final int kk = k;
            md.append(
                rigaModelli(
                    a,
                    "+" + k,
                    filter(r.getAsJsonArray("difensori"), x -> x.get(0).getAsInt() == kk)));
          }
        });
    md.append(
"""

Per tipo di territorio e guerra gia' in corso al momento della previsione:

| Alleanza | Orizzonte | Tipo | Guerra | Confronti | M0 segno / ass. | M1 segno / ass. | M2 segno / ass. | Migliore (ass.) |
|---|---|---|---|---|---|---|---|---|
""");
    rows.forEach(
        (a, r) -> {
          for (int k = 1; k <= 3; k++) {
            for (final String tipo : List.of("capitale", "VC", "fabbrica", "altro")) {
              for (final int war : List.of(1, 0)) {
                final int kk = k;
                final List<JsonArray> sel =
                    filter(
                        r.getAsJsonArray("difensori"),
                        x ->
                            x.get(0).getAsInt() == kk
                                && x.get(1).getAsString().equals(tipo)
                                && x.get(2).getAsInt() == war);
                if (sel.isEmpty()) {
                  continue;
                }
                md.append(
                    rigaModelli(
                        a, "+" + k + " | " + tipo + " | " + (war == 1 ? "si'" : "no"), sel));
              }
            }
          }
        });
    shadows.forEach(
        (a, list) -> {
          final double[][] q = new double[4][2];
          for (final JsonObject o : list) {
            if (!o.has("quote_m2")) {
              continue;
            }
            for (int k = 1; k <= 3; k++) {
              if (o.getAsJsonObject("quote_m2").has(String.valueOf(k))) {
                final JsonArray x = o.getAsJsonObject("quote_m2").getAsJsonArray(String.valueOf(k));
                q[k][0] += x.get(0).getAsDouble();
                q[k][1] += x.get(1).getAsDouble();
              }
            }
          }
          md.append(
              String.format(
                  Locale.ROOT,
"""

Quota M2 osservata (aumento reale / aumento previsto da M0, somma sulle partite) %s: +1 %s, +2 %s, +3 %s.
""",
                  Riepilogo.allianceLabel(a),
                  q[1][1] > 0 ? num2(q[1][0] / q[1][1]) : "-",
                  q[2][1] > 0 ? num2(q[2][0] / q[2][1]) : "-",
                  q[3][1] > 0 ? num2(q[3][0] / q[3][1]) : "-"));
        });

    // 1.3 counterattack on the point
    md.append(
"""

**1.3 Contrattacco sulla punta** (territori nemici presi dalla nazione nel turno, da chiunque; verifica nel turno nemico dopo). Calibrazione a fasce del 20% della tenuta stimata:

| Alleanza | Tenuta stimata | Territori | Tenuta stimata media | Tenuti davvero | Attaccati |
|---|---|---|---|---|---|
""");
    rows.forEach(
        (a, r) -> {
          for (int b = 0; b < 5; b++) {
            final int lo = b * 20;
            final List<JsonArray> sel =
                filter(r.getAsJsonArray("contrattacco"), x -> fascia(x.get(1).getAsDouble()) == lo);
            if (sel.isEmpty()) {
              continue;
            }
            md.append("| ")
                .append(Riepilogo.allianceLabel(a))
                .append(" | ")
                .append(lo)
                .append("-")
                .append(lo + 20)
                .append("% | ")
                .append(sel.size())
                .append(" | ")
                .append(
                    num(sel.stream().mapToDouble(x -> x.get(1).getAsDouble()).average().orElse(0)))
                .append("% | ")
                .append(
                    pct(
                        (int) sel.stream().filter(x -> x.get(5).getAsInt() == 1).count(),
                        sel.size()))
                .append(" | ")
                .append(
                    pct(
                        (int) sel.stream().filter(x -> x.get(3).getAsInt() == 1).count(),
                        sel.size()))
                .append(" |\n");
          }
        });
    md.append(
"""

Forza davvero impegnata contro la massima in portata, per tipo di territorio (forza impegnata = TUV perso dall'attaccante + rimasto a fine battaglia):

| Alleanza | Tipo | Territori | Attaccati | Quota impegnata media (attaccati) | Quota media (tutti, 0 se non attaccato) | Tenuti |
|---|---|---|---|---|---|---|
""");
    rows.forEach(
        (a, r) -> {
          for (final String tipo : List.of("capitale", "VC", "fabbrica", "altro")) {
            final List<JsonArray> sel =
                filter(r.getAsJsonArray("contrattacco"), x -> x.get(0).getAsString().equals(tipo));
            if (sel.isEmpty()) {
              continue;
            }
            final List<Double> share = new ArrayList<>();
            final List<Double> shareAll = new ArrayList<>();
            for (final JsonArray x : sel) {
              final double max = x.get(2).getAsDouble();
              final double q = max <= 0 ? 0 : x.get(4).getAsDouble() / max;
              shareAll.add(x.get(3).getAsInt() == 1 ? q : 0);
              if (x.get(3).getAsInt() == 1) {
                share.add(q);
              }
            }
            md.append("| ")
                .append(Riepilogo.allianceLabel(a))
                .append(" | ")
                .append(tipo)
                .append(" | ")
                .append(sel.size())
                .append(" | ")
                .append(pct(share.size(), sel.size()))
                .append(" | ")
                .append(share.isEmpty() ? "-" : num(100 * mean(share)) + "%")
                .append(" | ")
                .append(num(100 * mean(shareAll)))
                .append("% | ")
                .append(
                    pct(
                        (int) sel.stream().filter(x -> x.get(5).getAsInt() == 1).count(),
                        sel.size()))
                .append(" |\n");
          }
        });

    // 1.4 losses at the percentiles
    md.append(
"""

**1.4 Perdite ai percentili del calcolatore** (battaglie reali della nazione sulla prima tappa, quando il calcolatore l'ha valutata: quota con perdita reale <= percentile; attese circa 60 / 70 / 80%):

| Alleanza | Battaglie | Sotto P60 | Sotto P70 | Sotto P80 |
|---|---|---|---|---|
""");
    rows.forEach(
        (a, r) -> {
          final List<JsonArray> sel = filter(r.getAsJsonArray("battaglie_calcolatore"), x -> true);
          md.append("| ").append(Riepilogo.allianceLabel(a)).append(" | ").append(sel.size());
          for (int i = 1; i <= 3; i++) {
            final int ii = i;
            md.append(" | ")
                .append(
                    pct(
                        (int)
                            sel.stream()
                                .filter(x -> x.get(0).getAsDouble() <= x.get(ii).getAsDouble())
                                .count(),
                        sel.size()));
          }
          md.append(" |\n");
        });

    // 1.5 feasibility recomputed
    md.append(
"""

**1.5 Fattibilita' ricalcolata** (difensori del modello con l'errore assoluto piu' basso nelle verifiche della partita, calcolatore sulla prima tappa che blocca e sull'obiettivo; accanto i fattibili con M0 e i fattori R2a, stesse campagne):

| Alleanza | Round | Valutati | Fattibili v2 60 / 70 / 80 | Fattibili M0 60 / 70 / 80 | Modello usato |
|---|---|---|---|---|---|
""");
    rows.forEach(
        (a, r) -> {
          final Map<Integer, List<JsonArray>> byRound = new TreeMap<>();
          for (final JsonArray x : filter(r.getAsJsonArray("fattibili"), x -> true)) {
            byRound.computeIfAbsent(x.get(0).getAsInt(), k -> new ArrayList<>()).add(x);
          }
          final int[] tot = new int[7];
          byRound.forEach(
              (round, xs) -> {
                final int[] c = fattibili(xs);
                for (int i = 0; i < 7; i++) {
                  tot[i] += c[i];
                }
                md.append(rigaFattibili(a, "R" + round, c, modelli(xs)));
              });
          md.append(
              rigaFattibili(
                  a, "**tutti**", tot, modelli(filter(r.getAsJsonArray("fattibili"), x -> true))));
        });

    // Measure 2
    md.append(
"""

**Misura 2 - cosa lascia Hard nelle retrovie** (unita' di terra proprie a fine turno nei territori dei casi a e b della regola 2.6; unita'-turno e TUV per round della nazione = somma / turni giocati; "ferma davvero" = ferma da 2 o piu' turni e spostabile verso il fronte):

| Alleanza | Nazione | Caso | Turni |""");
    for (final String cls : CLASSI) {
      md.append(' ').append(cls).append(" (unita' / TUV per round) |");
    }
    md.append("\n|---|---|---|---|");
    CLASSI.forEach(c -> md.append("---|"));
    md.append('\n');
    shadows.forEach(
        (a, list) -> {
          final Map<String, Map<String, Map<String, double[]>>> agg = new TreeMap<>();
          final Map<String, Integer> turns = new TreeMap<>();
          for (final JsonObject o : list) {
            if (!o.has("misura2")) {
              continue;
            }
            for (final var n : o.getAsJsonObject("misura2").entrySet()) {
              final JsonObject nat = n.getValue().getAsJsonObject();
              turns.merge(
                  n.getKey(), nat.has("turni") ? nat.get("turni").getAsInt() : 0, Integer::sum);
              for (final String caso : List.of("a", "b")) {
                if (!nat.has(caso)) {
                  continue;
                }
                for (final var c : nat.getAsJsonObject(caso).entrySet()) {
                  final double[] v =
                      agg.computeIfAbsent(n.getKey(), k -> new TreeMap<>())
                          .computeIfAbsent(caso, k -> new TreeMap<>())
                          .computeIfAbsent(c.getKey(), k -> new double[2]);
                  v[0] += c.getValue().getAsJsonArray().get(0).getAsDouble();
                  v[1] += c.getValue().getAsJsonArray().get(1).getAsDouble();
                }
              }
            }
          }
          agg.forEach(
              (nation, byCase) ->
                  byCase.forEach(
                      (caso, byClass) -> {
                        final int t = Math.max(1, turns.getOrDefault(nation, 0));
                        md.append("| ")
                            .append(Riepilogo.allianceLabel(a))
                            .append(" | ")
                            .append(nation)
                            .append(" | ")
                            .append(caso)
                            .append(" | ")
                            .append(turns.getOrDefault(nation, 0))
                            .append(" |");
                        for (final String cls : CLASSI) {
                          final double[] v = byClass.getOrDefault(cls, new double[2]);
                          md.append(' ')
                              .append(num(v[0] / t))
                              .append(" / ")
                              .append(num(v[1] / t))
                              .append(" |");
                        }
                        md.append('\n');
                      }));
          final Map<String, Integer> types = new TreeMap<>();
          for (final JsonObject o : list) {
            if (o.has("misura2")) {
              for (final var n : o.getAsJsonObject("misura2").entrySet()) {
                final JsonObject nat = n.getValue().getAsJsonObject();
                if (nat.has("tipi_ferme")) {
                  nat.getAsJsonObject("tipi_ferme")
                      .entrySet()
                      .forEach(
                          e ->
                              types.merge(
                                  n.getKey() + " " + e.getKey(),
                                  e.getValue().getAsInt(),
                                  Integer::sum));
                }
              }
            }
          }
          if (!types.isEmpty()) {
            md.append("\n")
                .append(Riepilogo.allianceLabel(a))
                .append(", tipi delle unita' \"ferme davvero\" (unita'-turno, tutte le partite): ")
                .append(
                    types.entrySet().stream()
                        .sorted(
                            Comparator.comparingInt(
                                (Map.Entry<String, Integer> e) -> -e.getValue()))
                        .map(e -> e.getKey() + " " + e.getValue())
                        .reduce((x, y) -> x + ", " + y)
                        .orElse("-"))
                .append(".\n\n");
          }
        });

    // case b: lost rear territories
    md.append(
"""

**Caso b: territori di retrovia persi** (nome, valore in PU, chi li ha presi, ripresi dall'alleanza entro la fine del turno successivo della nazione, unita' di Hard presenti a fine turno prima della caduta):

| Alleanza | Territorio | Valore | Persi | Presi da | Ripresi entro 2 round | Non ripresi | Non verificabili | Unita' di Hard (media) |
|---|---|---|---|---|---|---|---|---|
""");
    shadows.forEach(
        (a, list) -> {
          final Map<String, List<JsonObject>> byT = new TreeMap<>();
          for (final JsonObject o : list) {
            if (!o.has("persi")) {
              continue;
            }
            for (final JsonElement e : o.getAsJsonArray("persi")) {
              final JsonObject x = e.getAsJsonObject();
              if ("b".equals(x.get("caso").getAsString())) {
                byT.computeIfAbsent(x.get("territorio").getAsString(), k -> new ArrayList<>())
                    .add(x);
              }
            }
          }
          byT.entrySet().stream()
              .sorted(
                  Comparator.comparingInt(
                          (Map.Entry<String, List<JsonObject>> e) -> -e.getValue().size())
                      .thenComparing(Map.Entry::getKey))
              .limit(15)
              .forEach(
                  e -> {
                    final List<JsonObject> xs = e.getValue();
                    final Map<String, Integer> by = new TreeMap<>();
                    xs.forEach(x -> by.merge(x.get("preso_da").getAsString(), 1, Integer::sum));
                    final long back =
                        xs.stream()
                            .filter(x -> x.get("ripreso").getAsString().startsWith("R"))
                            .count();
                    final long no =
                        xs.stream()
                            .filter(x -> "no".equals(x.get("ripreso").getAsString()))
                            .count();
                    md.append("| ")
                        .append(Riepilogo.allianceLabel(a))
                        .append(" | ")
                        .append(e.getKey())
                        .append(" | ")
                        .append(xs.get(0).get("valore").getAsInt())
                        .append(" | ")
                        .append(xs.size())
                        .append(" | ")
                        .append(
                            by.entrySet().stream()
                                .map(b -> b.getKey() + " " + b.getValue())
                                .reduce((x, y) -> x + ", " + y)
                                .orElse("-"))
                        .append(" | ")
                        .append(back)
                        .append(" | ")
                        .append(no)
                        .append(" | ")
                        .append(xs.size() - back - no)
                        .append(" | ")
                        .append(
                            num(
                                xs.stream()
                                    .mapToDouble(x -> x.get("unita_hard").getAsDouble())
                                    .average()
                                    .orElse(0)))
                        .append(" |\n");
                  });
        });
  }

  static final List<String> CLASSI =
      List.of(
          "appena piazzata",
          "mossa nel turno",
          "in transito",
          "antiaerea",
          "ferma davvero",
          "ferma da 1 turno",
          "bloccata");

  private static String rigaModelli(final String a, final String label, final List<JsonArray> sel) {
    final StringBuilder sb = new StringBuilder();
    sb.append("| ").append(Riepilogo.allianceLabel(a)).append(" | ").append(label).append(" | ");
    sb.append(sel.size());
    String best = "-";
    double bestAbs = Double.MAX_VALUE;
    for (int m = 0; m < 3; m++) {
      final int col = 4 + m;
      final List<Double> signed = new ArrayList<>();
      final List<Double> abs = new ArrayList<>();
      for (final JsonArray x : sel) {
        final double e = x.get(col).getAsDouble() - x.get(3).getAsDouble();
        signed.add(e);
        abs.add(Math.abs(e));
      }
      final double ma = median(abs);
      sb.append(" | ").append(num(median(signed))).append(" / ").append(num(ma));
      if (!sel.isEmpty() && ma < bestAbs) {
        bestAbs = ma;
        best = "M" + m;
      }
    }
    return sb.append(" | ").append(best).append(" |\n").toString();
  }

  /** [evaluated, v2 60, v2 70, v2 80, m0 60, m0 70, m0 80] (one row per campaign and margin). */
  private static int[] fattibili(final List<JsonArray> xs) {
    final int[] c = new int[7];
    for (final JsonArray x : xs) {
      final int m = x.get(1).getAsInt();
      final int i = m == 60 ? 0 : m == 70 ? 1 : 2;
      if (i == 0) {
        c[0]++;
      }
      c[1 + i] += x.get(3).getAsInt();
      c[4 + i] += x.get(2).getAsInt();
    }
    return c;
  }

  private static String modelli(final List<JsonArray> xs) {
    final Map<String, Integer> by = new TreeMap<>();
    xs.stream()
        .filter(x -> x.get(1).getAsInt() == 60)
        .forEach(x -> by.merge(x.get(4).getAsString().toUpperCase(Locale.ROOT), 1, Integer::sum));
    return by.entrySet().stream()
        .map(e -> e.getKey() + " " + e.getValue())
        .reduce((x, y) -> x + ", " + y)
        .orElse("-");
  }

  private static String rigaFattibili(
      final String a, final String round, final int[] c, final String models) {
    return "| "
        + Riepilogo.allianceLabel(a)
        + " | "
        + round
        + " | "
        + c[0]
        + " | "
        + c[1]
        + " / "
        + c[2]
        + " / "
        + c[3]
        + " | "
        + c[4]
        + " / "
        + c[5]
        + " / "
        + c[6]
        + " | "
        + models
        + " |\n";
  }

  /** Lower bound of the 20% band (100 goes in the last band). */
  static int fascia(final double holdPct) {
    return (int) Math.min(80, Math.max(0, Math.floor(holdPct / 20) * 20));
  }

  private static List<JsonArray> filter(
      final JsonArray rows, final java.util.function.Predicate<JsonArray> p) {
    final List<JsonArray> out = new ArrayList<>();
    if (rows == null) {
      return out;
    }
    for (final JsonElement e : rows) {
      if (e.isJsonArray() && p.test(e.getAsJsonArray())) {
        out.add(e.getAsJsonArray());
      }
    }
    return out;
  }

  private static String num2(final double x) {
    return String.format(Locale.ROOT, "%.2f", x);
  }

  private static double mean(final List<Double> v) {
    return v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
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
