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
    // avviate, completate, fallite, annullate, in corso, eseguite dal motore, prese da Hard
    final Map<String, int[]> byType = new TreeMap<>();
    final Map<String, Integer> causes = new TreeMap<>();
    final Map<String, Integer> blocks = new TreeMap<>();
    final List<Double> coherence = new ArrayList<>();
    final List<Double> coherenceHold = new ArrayList<>();
    final List<Double> coherenceNext = new ArrayList<>();
    final int[] planeTurns = new int[2];
    final int[] excludedUnits = new int[2];
    final List<String> excludedText = new ArrayList<>();
    int onlyPlanes = 0;
    final Map<String, double[][]> bandsByType = new TreeMap<>();
    final Map<String, double[][]> engineByType = new TreeMap<>();
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
        final int[] t = byType.computeIfAbsent(tipo, k -> new int[7]);
        t[0]++;
        switch (stato) {
          case "completata" -> t[1]++;
          case "fallita" -> t[2]++;
          case "annullata" -> {
            t[3]++;
            causes.merge(
                o.has("causa_annullamento")
                    ? RiepilogoGenerale.text(o, "causa_annullamento")
                    : "non classificata",
                1,
                Integer::sum);
          }
          default -> t[4]++;
        }
        if (eseguitaDalMotore(o)) {
          t[5]++;
        }
        if (flag(o, "presa_senza_operazione")) {
          t[6]++;
        }
        final double start = successo(o, "stima_avvio");
        final double first = successo(o, "stima_decisione_prima");
        final double next = successo(o, "stima_round_dopo");
        if (start >= 0 && next >= 0 && ("offensiva".equals(tipo) || "sbarco".equals(tipo))) {
          coherenceNext.add(Math.abs(start - next));
        }
        if (o.has("stima_avvio") && o.get("stima_avvio").isJsonObject()) {
          final JsonObject launch = o.getAsJsonObject("stima_avvio");
          if (launch.has("escluse_percorso_non_sicuro_n")) {
            excludedUnits[0] += launch.get("escluse_percorso_non_sicuro_n").getAsInt();
            excludedUnits[1]++;
            excludedText.add(
                RiepilogoGenerale.text(o, "bersaglio")
                    + ": "
                    + RiepilogoGenerale.text(launch, "escluse_percorso_non_sicuro"));
          }
          if (launch.has("bersaglio_vuoto")
              && launch.has("presa_pct")
              && launch.get("presa_pct").getAsDouble() <= 0) {
            onlyPlanes++;
          }
        }
        if (o.has("aerei_turni_fermi") && o.get("aerei_turni_fermi").isJsonPrimitive()) {
          planeTurns[0] += o.get("aerei_turni_fermi").getAsInt();
          planeTurns[1]++;
        }
        if (start >= 0 && first >= 0) {
          coherence.add(Math.abs(start - first));
          final double h0 = pct(o, "stima_avvio", "tenuta_pct");
          final double h1 = pct(o, "stima_decisione_prima", "tenuta_pct");
          if (h0 >= 0 && h1 >= 0) {
            coherenceHold.add(Math.abs(h0 - h1));
          }
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
              band(
                  bandsByType.computeIfAbsent(tipo, k -> new double[5][3]),
                  x.get("prob_tenuta_stimata").getAsDouble(),
                  x.get("esito_reale").getAsBoolean());
            }
          }
        }
        // the engine's single estimate at the launch against the same real outcome
        if (start >= 0 && o.has("previsioni")) {
          for (final JsonElement p : o.getAsJsonArray("previsioni")) {
            final JsonObject x = p.getAsJsonObject();
            if ("avvia".equals(RiepilogoGenerale.text(x, "azione")) && x.has("esito_reale")) {
              band(
                  engineByType.computeIfAbsent(tipo, k -> new double[5][3]),
                  start / 100,
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
      if (a.has("blocchi") && a.get("blocchi").isJsonObject()) {
        for (final var b : a.getAsJsonObject("blocchi").entrySet()) {
          if (b.getValue().isJsonPrimitive() && b.getValue().getAsJsonPrimitive().isNumber()) {
            blocks.merge(b.getKey(), b.getValue().getAsInt(), Integer::sum);
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
        "| Tipo | Avviate | Eseguite dal motore | Prese da Hard (senza operazione) | Completate |"
            + " Fallite | Annullate | Ancora in corso |\n");
    md.append("|---|---|---|---|---|---|---|---|\n");
    byType.forEach(
        (k, t) ->
            md.append("| ")
                .append(k)
                .append(" | ")
                .append(t[0])
                .append(" | ")
                .append(t[5])
                .append(" | ")
                .append(t[6])
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
    coerenza(md, byType, causes, blocks, coherence, coherenceHold);
    md.append(
        String.format(
            Locale.ROOT,
            "- Offensive e sbarchi, stima del motore al lancio contro quella del round dopo: mediana"
                + " **%s punti** di successo su %d operazioni, criterio <= 15 (step C1-bis).%n"
                + "- Aerei trattenuti per le tappe future: %d turni-aereo fermi in %d operazioni.%n"
                + "- Unita' escluse dalla stima al lancio per percorso non sicuro (step C1-ter): %d"
                + " in %d operazioni%s.%n"
                + "- Operazioni lanciate con presa 0 per \"solo aerei\" su un bersaglio vuoto: %d.%n%n",
            coherenceNext.isEmpty()
                ? "-"
                : String.format(Locale.ROOT, "%.1f", mediana(coherenceNext)),
            coherenceNext.size(),
            planeTurns[0],
            planeTurns[1],
            excludedUnits[0],
            excludedUnits[1],
            excludedText.isEmpty()
                ? ""
                : " ("
                    + String.join("; ", excludedText.subList(0, Math.min(8, excludedText.size())))
                    + ")",
            onlyPlanes));
    calibration(
        md,
        "livello strategico (avvia/continua/modifica; stima a inizio round, esito a fine durata)",
        bands);
    bandsByType.forEach(
        (k, b) -> calibration(md, "livello strategico, solo " + k + " (stima dell'LLM)", b));
    engineByType.forEach(
        (k, b) ->
            calibration(
                md,
                "stima unica del MOTORE al lancio, "
                    + k
                    + " (successo_pct, esito della previsione"
                    + " di avvio)",
                b));
    calibration(
        md,
        "ordini del turno (lancia/procedi/tieni; stima nel punto di decisione, esito al round dopo)",
        bandsOrders);
    difesa(md, llm, alliances);
  }

  /**
   * Step C2: the computed defense (PUs to defense / strategy / Hard per nation, territories
   * defended and given up, the cap, calibration of the defense estimate, capitals and irreplaceable
   * territories lost) and the causes of the estimate falls of 30 points or more (tracking).
   */
  static void difesa(
      final StringBuilder md,
      final List<RiepilogoGenerale.Game> llm,
      final List<String> alliances) {
    // per nation: turns, PU, defense, strategy, Hard, cap limits, PU cut
    final Map<String, double[]> byNation = new TreeMap<>();
    final Map<String, Integer> decisions = new TreeMap<>();
    final double[][] bands = new double[5][3];
    final Map<String, Integer> fallCauses = new TreeMap<>();
    final List<String> falls = new ArrayList<>();
    int capitalsLost = 0;
    int mustLost = 0;
    int mustDefended = 0;
    final List<String> capitalText = new ArrayList<>();
    for (final RiepilogoGenerale.Game g : llm) {
      if (g.json() == null) {
        continue;
      }
      final JsonObject j = g.json();
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
      if (!s.has(side) || !s.get(side).isJsonObject()) {
        continue;
      }
      final JsonObject a = s.getAsJsonObject(side);
      final List<String> nations = new ArrayList<>();
      if (a.has("nazioni")) {
        a.getAsJsonArray("nazioni").forEach(e -> nations.add(e.getAsString()));
      }
      if (j.has("capitali") && j.get("capitali").isJsonArray()) {
        for (final JsonElement e : j.getAsJsonArray("capitali")) {
          final JsonObject c = e.getAsJsonObject();
          if ("caduta".equals(RiepilogoGenerale.text(c, "evento"))
              && nations.contains(RiepilogoGenerale.text(c, "capitale_di"))) {
            capitalsLost++;
            capitalText.add(
                g.folder()
                    + " R"
                    + c.get("round").getAsInt()
                    + " "
                    + RiepilogoGenerale.text(c, "territorio"));
          }
        }
      }
      if (a.has("difesa") && a.get("difesa").isJsonArray()) {
        for (final JsonElement e : a.getAsJsonArray("difesa")) {
          final JsonObject t = e.getAsJsonObject();
          final double[] v =
              byNation.computeIfAbsent(
                  side + " " + RiepilogoGenerale.text(t, "nazione"), k -> new double[8]);
          v[0]++;
          v[1] += t.get("pu").getAsDouble();
          v[2] += t.get("pu_difesa").getAsDouble();
          v[3] += t.get("pu_strategia").getAsDouble();
          v[4] += t.get("pu_hard").getAsDouble();
          if (t.has("tetto_limita") && t.get("tetto_limita").getAsBoolean()) {
            v[5]++;
            v[6] += t.get("pu_tagliati_dal_tetto").getAsDouble();
          }
          v[7] += t.has("taglio_tetto") && t.get("taglio_tetto").getAsDouble() > 0 ? 1 : 0;
          for (final JsonElement x : t.getAsJsonArray("territori")) {
            final JsonObject y = x.getAsJsonObject();
            final String d = RiepilogoGenerale.text(y, "decisione");
            final String src = RiepilogoGenerale.text(y, "fonte");
            decisions.merge(d + " (" + src + ")", 1, Integer::sum);
            if (!y.has("esito_tenuto")) {
              continue;
            }
            final boolean held = y.get("esito_tenuto").getAsBoolean();
            band(bands, y.get("tenuta_stimata_pct").getAsDouble() / 100, held);
            if ("difeso".equals(d) && ("capitale".equals(src) || "llm".equals(src))) {
              mustDefended++;
              if (!held) {
                mustLost++;
              }
            }
          }
        }
      }
      if (a.has("operazioni")) {
        for (final JsonElement e : a.getAsJsonArray("operazioni")) {
          final JsonObject o = e.getAsJsonObject();
          if (!o.has("cali")) {
            continue;
          }
          for (final JsonElement c : o.getAsJsonArray("cali")) {
            final JsonObject x = c.getAsJsonObject();
            fallCauses.merge(RiepilogoGenerale.text(x, "causa"), 1, Integer::sum);
            if (falls.size() < 12) {
              falls.add(
                  g.folder()
                      + " "
                      + RiepilogoGenerale.text(o, "bersaglio")
                      + " R"
                      + x.get("round").getAsInt()
                      + ": "
                      + RiepilogoGenerale.text(x, "testo"));
            }
          }
        }
      }
    }
    md.append(
"""
### Budget difensivo calcolato (step C2)

| Nazione | Turni | PU medi | Difesa | Strategia | Hard | Strategia % | Tetto superato | Tetto limita le riserve | PU tagliati (tot / medi) |
|---|---|---|---|---|---|---|---|---|---|
""");
    byNation.forEach(
        (k, v) ->
            md.append(
                String.format(
                    Locale.ROOT,
                    "| %s | %.0f | %.1f | %.1f | %.1f | %.1f | %.0f%% (fisso prima: 30%%) | %.0f |"
                        + " %.0f | %.0f / %.1f |%n",
                    k,
                    v[0],
                    v[1] / v[0],
                    v[2] / v[0],
                    v[3] / v[0],
                    v[4] / v[0],
                    v[1] == 0 ? 0 : v[3] / v[1] * 100,
                    v[7],
                    v[5],
                    v[6],
                    v[5] == 0 ? 0 : v[6] / v[5])));
    md.append("\nTerritori minacciati valutati, per decisione (fonte): ")
        .append(decisions.isEmpty() ? "nessuno" : decisions.toString())
        .append(".\n\n");
    md.append(
        String.format(
            Locale.ROOT,
            "Capitali dello Stratega cadute: **%d**%s. Irrinunciabili difesi e persi al turno dopo:"
                + " **%d su %d**.%n%n",
            capitalsLost,
            capitalText.isEmpty() ? "" : " (" + String.join("; ", capitalText) + ")",
            mustLost,
            mustDefended));
    calibration(
        md,
        "stima della DIFESA (tenuta stimata del piano difensivo, esito al turno dopo della nazione;"
            + " criterio errore medio <= 20 punti)",
        bands);
    md.append("Cali della stima di 30 punti o piu' dal lancio (tracciamento), per causa: ")
        .append(fallCauses.isEmpty() ? "nessuno" : fallCauses.toString())
        .append(".\n\n");
    falls.forEach(f -> md.append("- ").append(f).append('\n'));
    if (!falls.isEmpty()) {
      md.append('\n');
    }
  }

  /** Artefact causes of a cancellation (analysis of run 42775993327): C1-C5. */
  static final Set<String> ARTEFATTI = Set.of("C1", "C2", "C3", "C4", "C5");

  /**
   * Step COERENZA STIME: an operation carried out by the ENGINE: a step of a conquest moved by the
   * engine, or a garrison kept / reinforced by the engine for a hold or reinforcement. A target
   * taken by Hard without the operation does not count.
   */
  static boolean eseguitaDalMotore(final JsonObject o) {
    final String tipo = RiepilogoGenerale.text(o, "tipo");
    return "tenuta".equals(tipo) || "rinforzo".equals(tipo)
        ? flag(o, "tenuta_motore")
        : flag(o, "tappa_motore");
  }

  private static boolean flag(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
  }

  private static double successo(final JsonObject o, final String key) {
    return pct(o, key, "successo_pct");
  }

  private static double pct(final JsonObject o, final String key, final String field) {
    return o.has(key) && o.get(key).isJsonObject() && o.getAsJsonObject(key).has(field)
        ? o.getAsJsonObject(key).get(field).getAsDouble()
        : -1;
  }

  static double mediana(final List<Double> values) {
    if (values.isEmpty()) {
      return Double.NaN;
    }
    final List<Double> v = new ArrayList<>(values);
    v.sort(Double::compare);
    final int n = v.size();
    return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
  }

  private static void coerenza(
      final StringBuilder md,
      final Map<String, int[]> byType,
      final Map<String, Integer> causes,
      final Map<String, Integer> blocks,
      final List<Double> coherence,
      final List<Double> coherenceHold) {
    final int launched = byType.values().stream().mapToInt(t -> t[0]).sum();
    final int artefacts =
        causes.entrySet().stream()
            .filter(e -> ARTEFATTI.contains(e.getKey()))
            .mapToInt(Map.Entry::getValue)
            .sum();
    int conquests = 0;
    int byEngine = 0;
    for (final String k : List.of("offensiva", "sbarco")) {
      if (byType.containsKey(k)) {
        conquests += byType.get(k)[0];
        byEngine += byType.get(k)[5];
      }
    }
    md.append("### Coerenza delle stime (step COERENZA STIME)\n\n");
    md.append("Cause degli annullamenti (registrate dal motore): ")
        .append(causes.isEmpty() ? "nessun annullamento" : causes.toString())
        .append("\n\n");
    md.append(
        String.format(
            Locale.ROOT,
            "- Annullamenti per artefatto (C1-C5): **%d su %d avviate (%.0f%%)**, criterio <= 10%%.%n"
                + "- Offensive e sbarchi eseguiti DAL MOTORE: **%d su %d avviati (%.0f%%)**,"
                + " criterio >= 30%%.%n"
                + "- Differenza tra la stima del candidato (lancio) e la prima stima al punto di"
                + " decisione: mediana **%s punti** di successo su %d operazioni (tenuta: %s punti),"
                + " criterio <= 15.%n"
                + "- Blocchi del motore: %s.%n%n",
            artefacts,
            launched,
            launched == 0 ? 0.0 : artefacts * 100.0 / launched,
            byEngine,
            conquests,
            conquests == 0 ? 0.0 : byEngine * 100.0 / conquests,
            coherence.isEmpty() ? "-" : String.format(Locale.ROOT, "%.1f", mediana(coherence)),
            coherence.size(),
            coherenceHold.isEmpty()
                ? "-"
                : String.format(Locale.ROOT, "%.1f", mediana(coherenceHold)),
            blocks.isEmpty() ? "nessuno" : blocks.toString()));
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
