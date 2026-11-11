package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.triplea.proplus.autoplay.ConfrontoConferma.Welch;
import org.triplea.proplus.autoplay.ConfrontoHard.Stat;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step R2b "IMPEGNO": the first campaign that acts, against Hard vs Hard. Written when "Hard /
 * Hard" and at least one configuration with the commitment on ("[con impegno]") are there. Axis VC
 * at 1/3, 2/3 and the end with Welch's 95% interval from Hard vs Hard, income and TUV at the end;
 * the cities that change hands; per nation the targets chosen, their duration, the changes and
 * their reason, the targets taken; PUs of the operation; units routed; forced attacks and their
 * outcome; the operation's units lost while gathering or on the way. The game duration against (a).
 */
final class ConfrontoImpegno {
  private ConfrontoImpegno() {}

  static final String CON_IMPEGNO = "[con impegno]";

  /**
   * The cities of the step's table (label → territory) and the alliance that holds them at start.
   */
  static final Map<String, String[]> CITTA = new LinkedHashMap<>();

  static {
    CITTA.put("Mosca", new String[] {"Russia", "Allies"});
    CITTA.put("Stalingrado", new String[] {"Volgograd", "Allies"});
    CITTA.put("Leningrado", new String[] {"Novgorod", "Allies"});
    CITTA.put("Egitto", new String[] {"Egypt", "Allies"});
    CITTA.put("Parigi", new String[] {"France", "Allies"});
    CITTA.put("Berlino", new String[] {"Germany", "Axis"});
    CITTA.put("Roma", new String[] {"Southern Italy", "Axis"});
  }

  static void write(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final List<Integer> vcRounds) {
    final String reference =
        String.join(" / ", alliances.stream().map(a -> ConfrontoHard.HARD).toList());
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    games.stream()
        .filter(g -> g.json() != null)
        .forEach(
            g ->
                byConfig
                    .computeIfAbsent(
                        RiepilogoGenerale.configuration(g.json(), alliances),
                        k -> new ArrayList<>())
                    .add(g.json()));
    final List<JsonObject> ref = byConfig.get(reference);
    // step R4: games played until the victory have their own tables ("Partite complete")
    if (byConfig.values().stream()
        .flatMap(List::stream)
        .anyMatch(
            j ->
                j.getAsJsonObject("parametri").has("fino_a_vittoria")
                    && j.getAsJsonObject("parametri").get("fino_a_vittoria").getAsBoolean())) {
      return;
    }
    final Map<String, String> committed = new LinkedHashMap<>();
    byConfig.forEach(
        (config, list) -> {
          if (config.contains(CON_IMPEGNO)) {
            final String side = ConfrontoHard.changedSide(list, alliances);
            committed.put(config, side == null ? alliances.get(0) : side);
          }
        });
    if (ref == null || committed.isEmpty()) {
      return;
    }
    final String axis = alliances.contains("Axis") ? "Axis" : alliances.get(0);
    md.append(
"""

## Impegno (step R2b, R2c, R3a)

""");
    md.append(
        "Prima campagna che AGISCE (una nazione, via terra, un obiettivo alla volta; Stratega senza"
            + " chiave = Hard + impegno). Riferimento (a) = Hard vs Hard della stessa run. IC 95%"
            + " con il t di Welch: con 10 semi si distinguono solo differenze di circa 1,2 VC o"
            + " piu'; \"include lo 0\" non vuol dire \"uguale\".\n\n");
    final List<String> configs = new ArrayList<>();
    configs.add(reference);
    configs.addAll(committed.keySet());

    // 1. Axis VC at R4/R8/R12, income and TUV at the end, duration
    md.append("| Configurazione | Partite | ");
    for (final int r : vcRounds) {
      md.append("VC Asse R").append(r).append(" | Δ da (a) R").append(r).append(" [IC 95%] | ");
    }
    md.append("Reddito R")
        .append(vcRounds.get(vcRounds.size() - 1))
        .append(" Asse / Alleati | TUV R")
        .append(vcRounds.get(vcRounds.size() - 1))
        .append(" Asse / Alleati | Durata media | Durata / (a) |\n|---|---|");
    vcRounds.forEach(r -> md.append("---|---|"));
    md.append("---|---|---|---|\n");
    final double refDuration = Stat.of(ref, j -> j.get("durata_s").getAsDouble()).meanValue();
    final int last = vcRounds.get(vcRounds.size() - 1);
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" | ").append(list.size()).append(" | ");
      for (final int r : vcRounds) {
        final Stat s = Stat.of(list, j -> vcAt(j, axis, r));
        md.append(num(s.meanValue())).append(" ± ").append(s.sd()).append(" | ");
        if (config.equals(reference)) {
          md.append("- | ");
        } else {
          final Welch w = Welch.of(s, Stat.of(ref, j -> vcAt(j, axis, r)));
          md.append(w.deltaText())
              .append(' ')
              .append(w.intervalText())
              .append(
                  w.known()
                      ? (w.low() > 0 || w.high() < 0 ? " esclude lo 0" : " include lo 0")
                      : "")
              .append(" | ");
        }
      }
      md.append(
              String.join(
                  " / ",
                  alliances.stream()
                      .map(a -> num(Stat.of(list, j -> atRound(j, a, last, "reddito")).meanValue()))
                      .toList()))
          .append(" | ")
          .append(
              String.join(
                  " / ",
                  alliances.stream()
                      .map(a -> num(Stat.of(list, j -> atRound(j, a, last, "tuv")).meanValue()))
                      .toList()))
          .append(" | ");
      final double duration = Stat.of(list, j -> j.get("durata_s").getAsDouble()).meanValue();
      md.append(Math.round(duration))
          .append(" s | ")
          .append(
              refDuration > 0
                  ? String.format(Locale.ROOT, "%+.0f%%", (duration / refDuration - 1) * 100)
                  : "-")
          .append(" |\n");
    }

    // 2. cities that change hands
    md.append(
        "\nCitta' che passano di mano entro R"
            + last
            + " (partite, round mediano del primo passaggio):\n\n| Configurazione |");
    CITTA.keySet().forEach(k -> md.append(' ').append(k).append(" |"));
    md.append("\n|---|");
    CITTA.keySet().forEach(k -> md.append("---|"));
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" |");
      for (final String[] city : CITTA.values()) {
        final List<Double> rounds = new ArrayList<>();
        for (final JsonObject j : list) {
          final int r = firstChange(j, city[0], city[1], last);
          if (r > 0) {
            rounds.add((double) r);
          }
        }
        md.append(' ')
            .append(rounds.size())
            .append('/')
            .append(list.size())
            .append(rounds.isEmpty() ? "" : ", R" + num(ConfrontoOmbra.median(rounds)))
            .append(" |");
      }
      md.append('\n');
    }

    // 3-5 per configuration with the commitment
    committed.forEach((config, side) -> perConfig(md, config, side, byConfig.get(config)));
    writeR2c(md, configs, byConfig, committed, alliances, vcRounds);
    // step R3a: coordinated landing and theaters
    ConfrontoR3a.write(md, configs, byConfig, reference, vcRounds);
  }

  // ===================================================================== step R2c tables

  /**
   * Step R2c: income and army value per alliance at R4/R8/R12; the operations' phases and forced
   * attacks; the US and UK transports per round, the units brought to the United Kingdom and landed
   * in western Europe, the first US landing and the first bridgehead held 2 rounds; Russia.
   */
  private static void writeR2c(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig,
      final Map<String, String> committed,
      final List<String> alliances,
      final List<Integer> vcRounds) {
    md.append(
"""

### Step R2c: preparazione e programmi

""");
    // income and army value per alliance
    md.append("Reddito e valore dell'esercito (TUV) per alleanza, media sulle partite.\n\n");
    md.append("| Configurazione |");
    for (final int r : vcRounds) {
      for (final String a : alliances) {
        md.append(" Reddito ").append(a).append(" R").append(r).append(" |");
      }
      for (final String a : alliances) {
        md.append(" TUV ").append(a).append(" R").append(r).append(" |");
      }
    }
    md.append("\n|---|");
    vcRounds.forEach(r -> md.append("---|".repeat(2 * alliances.size())));
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" |");
      for (final int r : vcRounds) {
        for (final String key : List.of("reddito", "tuv")) {
          for (final String a : alliances) {
            md.append(' ')
                .append(num(Stat.of(list, j -> atRound(j, a, r, key)).meanValue()))
                .append(" |");
          }
        }
      }
      md.append('\n');
    }
    // Russia
    md.append(
        "\nRussia: valore dell'esercito (TUV) e reddito, media sulle partite.\n\n| Configurazione |");
    for (final int r : vcRounds) {
      md.append(" TUV R").append(r).append(" | Reddito R").append(r).append(" |");
    }
    md.append("\n|---|");
    vcRounds.forEach(r -> md.append("---|---|"));
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" |");
      for (final int r : vcRounds) {
        md.append(' ')
            .append(num(Stat.of(list, j -> nation(j, "Russians", r, "tuv")).meanValue()))
            .append(" | ")
            .append(num(Stat.of(list, j -> nation(j, "Russians", r, "reddito")).meanValue()))
            .append(" |");
      }
      md.append('\n');
    }
    // phases and forced attacks per operation (nation)
    md.append(
"""

Per operazione (nazione, somma sulle partite): turni con obiettivo in PREPARAZIONE e in ESECUZIONE, turni sospesi (la Russia senza obiettivo fattibile gioca come Hard), obiettivi presi; attacchi forzati decisi, eseguiti come mandati da Hard, completati con unita' aggiunte, rinunciati, non attaccati da Hard, vinti.

| Configurazione | Nazione | Preparazione | Esecuzione | Sospesi | Presi | Forzati | Eseguiti | Completati | Rinunciati | Non attaccati | Vinti |
|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    committed.forEach(
        (config, side) -> {
          final Map<String, int[]> per = new TreeMap<>();
          for (final JsonObject j : byConfig.get(config)) {
            final JsonObject imp = impegno(j, side);
            for (final JsonElement e : arr(imp, "turni")) {
              final JsonObject t = e.getAsJsonObject();
              final int[] x = per.computeIfAbsent(t.get("nazione").getAsString(), k -> new int[11]);
              final String phase = t.has("fase") ? t.get("fase").getAsString() : "";
              x[0] += "PREPARAZIONE".equals(phase) ? 1 : 0;
              x[1] += "ESECUZIONE".equals(phase) ? 1 : 0;
              x[2] += t.has("sospeso") ? 1 : 0;
              x[3] += t.has("obiettivo_preso") ? 1 : 0;
              for (final JsonElement ae : arr(t, "attacchi")) {
                final JsonObject a = ae.getAsJsonObject();
                if (!a.get("forzato").getAsBoolean()) {
                  continue;
                }
                x[4]++;
                final String rev = a.has("revisione") ? a.get("revisione").getAsString() : "";
                x[5] += "eseguito".equals(rev) ? 1 : 0;
                x[6] += "completato".equals(rev) ? 1 : 0;
                x[7] += "rinunciato".equals(rev) ? 1 : 0;
                final String outcome = a.has("esito") ? a.get("esito").getAsString() : "";
                x[8] += "non attaccato".equals(outcome) ? 1 : 0;
                x[9] += "vinto".equals(outcome) ? 1 : 0;
              }
            }
          }
          per.forEach(
              (n, x) ->
                  md.append(
                      String.format(
                          Locale.ROOT,
                          "| %s | %s | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d |%n",
                          config,
                          n,
                          x[0],
                          x[1],
                          x[2],
                          x[3],
                          x[4],
                          x[5],
                          x[6],
                          x[7],
                          x[8],
                          x[9])));
        });
    // USA and UK per round
    final int last = vcRounds.get(vcRounds.size() - 1);
    for (final String n : List.of("Americans", "British")) {
      md.append("\n")
          .append(n)
          .append(
              " per round (media per partita): trasporti totali / fermi vuoti a fine movimento /"
                  + " unita' portate nel Regno Unito / unita' sbarcate in Europa occidentale.\n\n");
      md.append("| Configurazione |");
      for (int r = 1; r <= last; r++) {
        md.append(" R").append(r).append(" |");
      }
      md.append("\n|---|");
      for (int r = 1; r <= last; r++) {
        md.append("---|");
      }
      md.append('\n');
      for (final String config : configs) {
        final List<JsonObject> list = byConfig.get(config);
        md.append("| ").append(config).append(" |");
        for (int r = 1; r <= last; r++) {
          final int round = r;
          md.append(' ')
              .append(num1(Stat.of(list, j -> nation(j, n, round, "trasporti")).meanValue()))
              .append(" / ")
              .append(
                  num1(Stat.of(list, j -> nation(j, n, round, "trasporti_inattivi")).meanValue()))
              .append(" / ")
              .append(
                  num1(Stat.of(list, j -> nation(j, n, round, "portate_regno_unito")).meanValue()))
              .append(" / ")
              .append(num1(Stat.of(list, j -> sbarcateOvest(j, n, round)).meanValue()))
              .append(" |");
        }
        md.append('\n');
      }
    }
    md.append(
"""

| Configurazione | Partite con sbarco USA in Europa occidentale | Round mediano del primo sbarco USA | Partite con testa di ponte tenuta 2 round | Round mediano |
|---|---|---|---|---|
""");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      final List<Double> us = new ArrayList<>();
      final List<Double> heads = new ArrayList<>();
      for (final JsonObject j : list) {
        int first = -1;
        for (final JsonElement e : arr(j, "sbarchi")) {
          final JsonObject l = e.getAsJsonObject();
          if ("Americans".equals(l.get("nazione").getAsString())
              && Monitor.WEST_EUROPE.contains(l.get("territorio").getAsString())
              && (first < 0 || l.get("round").getAsInt() < first)) {
            first = l.get("round").getAsInt();
          }
        }
        if (first > 0) {
          us.add((double) first);
        }
        if (j.has("prima_testa_di_ponte")) {
          heads.add(j.getAsJsonObject("prima_testa_di_ponte").get("round").getAsDouble());
        }
      }
      md.append("| ")
          .append(config)
          .append(" | ")
          .append(us.size())
          .append('/')
          .append(list.size())
          .append(" | ")
          .append(us.isEmpty() ? "-" : "R" + num(ConfrontoOmbra.median(us)))
          .append(" | ")
          .append(heads.size())
          .append('/')
          .append(list.size())
          .append(" | ")
          .append(heads.isEmpty() ? "-" : "R" + num(ConfrontoOmbra.median(heads)))
          .append(" |\n");
    }
  }

  /** {@code key} of {@code nation} at the end of round {@code r} (NaN if missing). */
  static double nation(final JsonObject json, final String nation, final int r, final String key) {
    final JsonObject round = round(json, r);
    if (round == null
        || !round.has("nazioni")
        || !round.getAsJsonObject("nazioni").has(nation)
        || !round.getAsJsonObject("nazioni").getAsJsonObject(nation).has(key)) {
      return Double.NaN;
    }
    return round.getAsJsonObject("nazioni").getAsJsonObject(nation).get(key).getAsDouble();
  }

  /** Units of {@code nation} really landed in western Europe in round {@code r}. */
  static double sbarcateOvest(final JsonObject json, final String nation, final int r) {
    if (round(json, r) == null) {
      return Double.NaN;
    }
    double n = 0;
    for (final JsonElement e : arr(json, "sbarchi")) {
      final JsonObject l = e.getAsJsonObject();
      if (l.get("round").getAsInt() == r
          && nation.equals(l.get("nazione").getAsString())
          && Monitor.WEST_EUROPE.contains(l.get("territorio").getAsString())) {
        n += l.get("unita").getAsDouble();
      }
    }
    return n;
  }

  private static String num1(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.1f", x);
  }

  private static void perConfig(
      final StringBuilder md, final String config, final String side, final List<JsonObject> list) {
    final List<JsonObject> imp = new ArrayList<>();
    for (final JsonObject j : list) {
      imp.add(impegno(j, side));
    }
    md.append("\n### ").append(config).append(" (lato ").append(side).append(")\n\n");
    // 3. per nation: targets, duration, changes, taken
    final Map<String, Map<String, Integer>> chosen = new TreeMap<>();
    final Map<String, List<Double>> durations = new TreeMap<>();
    final Map<String, Map<String, Integer>> reasons = new TreeMap<>();
    final Map<String, List<String>> taken = new TreeMap<>();
    final Map<String, Integer> open = new TreeMap<>();
    for (final JsonObject o : imp) {
      for (final JsonElement e : arr(o, "storia")) {
        final JsonObject h = e.getAsJsonObject();
        final String n = h.get("nazione").getAsString();
        chosen
            .computeIfAbsent(n, k -> new TreeMap<>())
            .merge(h.get("obiettivo").getAsString(), 1, Integer::sum);
        durations.computeIfAbsent(n, k -> new ArrayList<>()).add(h.get("durata").getAsDouble());
        reasons
            .computeIfAbsent(n, k -> new TreeMap<>())
            .merge(h.get("motivo_fine").getAsString(), 1, Integer::sum);
        if (h.has("preso_round")) {
          taken
              .computeIfAbsent(n, k -> new ArrayList<>())
              .add(h.get("obiettivo").getAsString() + " R" + h.get("preso_round").getAsInt());
        }
      }
      if (o.has("nazioni")) {
        for (final var e : o.getAsJsonObject("nazioni").entrySet()) {
          final JsonObject n = e.getValue().getAsJsonObject();
          if (n.has("obiettivo")) {
            chosen
                .computeIfAbsent(e.getKey(), k -> new TreeMap<>())
                .merge(n.get("obiettivo").getAsString(), 1, Integer::sum);
            open.merge(e.getKey(), 1, Integer::sum);
          }
        }
      }
    }
    md.append(
"""
Per nazione (somma sulle partite): obiettivi scelti (volte), durata media degli obiettivi chiusi in round, cambi per motivo, obiettivi presi con il round, aperti a fine partita.

| Nazione | Obiettivi scelti | Durata media (chiusi) | Cambi (motivo) | Presi (round) | Aperti a fine partita |
|---|---|---|---|---|---|
""");
    for (final String n : chosen.keySet()) {
      md.append("| ")
          .append(n)
          .append(" | ")
          .append(text(chosen.get(n)))
          .append(" | ")
          .append(
              durations.containsKey(n)
                  ? num(
                          durations.get(n).stream()
                              .mapToDouble(Double::doubleValue)
                              .average()
                              .orElse(0))
                      + " ("
                      + durations.get(n).size()
                      + ")"
                  : "-")
          .append(" | ")
          .append(reasons.containsKey(n) ? text(reasons.get(n)) : "-")
          .append(" | ")
          .append(taken.containsKey(n) ? String.join(", ", taken.get(n)) : "-")
          .append(" | ")
          .append(open.getOrDefault(n, 0))
          .append(" |\n");
    }
    if (chosen.isEmpty()) {
      md.append("| - | nessun obiettivo scelto | - | - | - | - |\n");
    }
    // 4. PUs, routing, forced attacks; 5. losses on the way
    long puTurn = 0;
    long puOp = 0;
    long puOpPlanned = 0;
    int forced = 0;
    int won = 0;
    int notTaken = 0;
    int notAttacked = 0;
    int underP80 = 0;
    int withP80 = 0;
    double lossSum = 0;
    double p80Sum = 0;
    final Map<Integer, double[]> perRound = new TreeMap<>();
    final Map<String, Long> puByNation = new TreeMap<>();
    final Map<String, Long> puOpByNation = new TreeMap<>();
    for (final JsonObject o : imp) {
      for (final JsonElement e : arr(o, "turni")) {
        final JsonObject t = e.getAsJsonObject();
        final int r = t.get("round").getAsInt();
        final String n = t.get("nazione").getAsString();
        final long pt = t.has("pu_turno") ? t.get("pu_turno").getAsLong() : 0;
        final long ps = t.has("pu_op_spesi") ? t.get("pu_op_spesi").getAsLong() : 0;
        puTurn += pt;
        puOp += ps;
        puOpPlanned += t.has("pu_op") ? t.get("pu_op").getAsLong() : 0;
        puByNation.merge(n, pt, Long::sum);
        puOpByNation.merge(n, ps, Long::sum);
        final double[] x = perRound.computeIfAbsent(r, k -> new double[4]);
        x[0] += t.has("instradate") ? t.get("instradate").getAsDouble() : 0;
        x[1] += t.has("perse_viaggio") ? t.get("perse_viaggio").getAsDouble() : 0;
        x[2] += t.has("perse_viaggio_tuv") ? t.get("perse_viaggio_tuv").getAsDouble() : 0;
        x[3] += ps;
        if (t.has("attacchi")) {
          for (final JsonElement ae : t.getAsJsonArray("attacchi")) {
            final JsonObject a = ae.getAsJsonObject();
            if (!a.get("forzato").getAsBoolean()) {
              continue;
            }
            forced++;
            final String outcome = a.has("esito") ? a.get("esito").getAsString() : "?";
            if ("vinto".equals(outcome)) {
              won++;
            } else if ("non preso".equals(outcome)) {
              notTaken++;
            } else if ("non attaccato".equals(outcome)) {
              notAttacked++;
            }
            if (a.has("perdite_reali_tuv")
                && !"non attaccato".equals(outcome)
                && a.get("perdite_p80_tuv").getAsDouble() >= 0) {
              withP80++;
              final double loss = a.get("perdite_reali_tuv").getAsDouble();
              final double p80 = a.get("perdite_p80_tuv").getAsDouble();
              lossSum += loss;
              p80Sum += p80;
              if (loss <= p80) {
                underP80++;
              }
            }
          }
        }
      }
    }
    final int games = Math.max(1, imp.size());
    md.append(
        String.format(
            Locale.ROOT,
            "%nPU spesi per l'operazione: %d su %d PU dei turni (%.1f%%; previsti dall'ordine %d)."
                + " Per nazione: %s.%n",
            puOp,
            puTurn,
            puTurn > 0 ? 100.0 * puOp / puTurn : 0,
            puOpPlanned,
            String.join(
                ", ",
                puByNation.keySet().stream()
                    .map(
                        n ->
                            n
                                + " "
                                + puOpByNation.getOrDefault(n, 0L)
                                + "/"
                                + puByNation.get(n)
                                + String.format(
                                    Locale.ROOT,
                                    " (%.0f%%)",
                                    puByNation.get(n) > 0
                                        ? 100.0
                                            * puOpByNation.getOrDefault(n, 0L)
                                            / puByNation.get(n)
                                        : 0))
                    .toList())));
    md.append(
        String.format(
            Locale.ROOT,
            "%nAttacchi forzati: %d (%.1f per partita): vinti %d, non presi %d, non attaccati da Hard"
                + " %d. Perdite reali contro il P80 del calcolatore (tutte le unita' in portata, al"
                + " momento della decisione): %d su %d sotto o uguali al P80; media reale %.1f TUV,"
                + " media P80 %.1f TUV.%n",
            forced,
            forced / (double) games,
            won,
            notTaken,
            notAttacked,
            underP80,
            withP80,
            withP80 > 0 ? lossSum / withP80 : 0,
            withP80 > 0 ? p80Sum / withP80 : 0));
    md.append(
"""

Per round (media per partita): PU all'operazione, unita' instradate verso il raduno, unita' dell'operazione perse nei turni nemici (raduno o viaggio) e loro TUV.

| Round | PU all'operazione | Instradate | Perse in viaggio | TUV perso in viaggio |
|---|---|---|---|---|
""");
    perRound.forEach(
        (r, x) ->
            md.append("| R")
                .append(r)
                .append(" | ")
                .append(num(x[3] / games))
                .append(" | ")
                .append(num(x[0] / games))
                .append(" | ")
                .append(num(x[1] / games))
                .append(" | ")
                .append(num(x[2] / games))
                .append(" |\n"));
  }

  /** The commitment's summary of {@code alliance} in the game (empty if none). */
  static JsonObject impegno(final JsonObject json, final String alliance) {
    if (json.has("stratega_llm")
        && json.get("stratega_llm").isJsonObject()
        && json.getAsJsonObject("stratega_llm").has(alliance)
        && json.getAsJsonObject("stratega_llm").getAsJsonObject(alliance).has("impegno")) {
      return json.getAsJsonObject("stratega_llm")
          .getAsJsonObject(alliance)
          .getAsJsonObject("impegno");
    }
    return new JsonObject();
  }

  /** VC of {@code alliance} at the end of round {@code r} (NaN if the game did not get there). */
  static double vcAt(final JsonObject json, final String alliance, final int r) {
    final JsonObject round = round(json, r);
    if (round == null || !round.getAsJsonObject("vc").has(alliance)) {
      return Double.NaN;
    }
    return round.getAsJsonObject("vc").getAsJsonObject(alliance).get("numero").getAsDouble();
  }

  private static double atRound(
      final JsonObject json, final String alliance, final int r, final String key) {
    final JsonObject round = round(json, r);
    return round == null ? Double.NaN : RiepilogoGenerale.sum(json, round, alliance, key);
  }

  private static JsonObject round(final JsonObject json, final int r) {
    if (!json.has("round")) {
      return null;
    }
    for (final JsonElement e : json.getAsJsonArray("round")) {
      if (e.getAsJsonObject().get("round").getAsInt() == r) {
        return e.getAsJsonObject();
      }
    }
    return null;
  }

  /**
   * First round (1..last) at whose end {@code territory} is not in the VC list of {@code
   * startAlliance}, -1 if never. Only for the victory cities (the VC list is what the summary
   * keeps).
   */
  static int firstChange(
      final JsonObject json, final String territory, final String startAlliance, final int last) {
    for (int r = 1; r <= last; r++) {
      final JsonObject round = round(json, r);
      if (round == null || !round.getAsJsonObject("vc").has(startAlliance)) {
        continue;
      }
      boolean held = false;
      for (final JsonElement c :
          round.getAsJsonObject("vc").getAsJsonObject(startAlliance).getAsJsonArray("citta")) {
        held |= c.getAsString().equals(territory);
      }
      if (!held) {
        return r;
      }
    }
    return -1;
  }

  private static JsonArray arr(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
  }

  private static String text(final Map<String, Integer> m) {
    return String.join(
        ", ", m.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList());
  }

  private static String num(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.2f", x);
  }
}
