package org.triplea.proplus.autoplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.triplea.proplus.autoplay.ConfrontoConferma.Welch;
import org.triplea.proplus.autoplay.ConfrontoHard.Stat;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step VARIANZA: where the difference between runs comes from. Written only for games whose
 * folder carries a replica and a seed ({@code ...-r<replica>-seme<seed>}). Games are grouped by
 * configuration, group of seeds (below 100 or from 100) and replica; for each group the VC at the
 * end of the changed side with a 95% interval, the length of the game and of the side's turns; then
 * the seed-group effect, the difference from Hard vs Hard, the replica effect (same seeds played
 * again), the correlation between replicas on the same seed, the correlation between length and VC
 * and the VC per machine.
 */
final class ConfrontoVarianza {
  private ConfrontoVarianza() {}

  private static final Pattern ID = Pattern.compile("-r(\\d+)-seme(\\d+)$");

  /** One game with its group. */
  record Entry(String config, int replica, long seed, JsonObject json) {
    String seeds() {
      return seed < 100 ? "1-99" : "100+";
    }
  }

  static void write(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final List<Integer> vcRounds) {
    final List<Entry> entries = new ArrayList<>();
    for (final Game g : games) {
      final Matcher m = ID.matcher(g.folder());
      if (g.json() != null && m.find()) {
        entries.add(
            new Entry(
                RiepilogoGenerale.configuration(g.json(), alliances),
                Integer.parseInt(m.group(1)),
                Long.parseLong(m.group(2)),
                g.json()));
      }
    }
    if (entries.isEmpty()) {
      return;
    }
    final String reference =
        String.join(" / ", alliances.stream().map(a -> ConfrontoHard.HARD).toList());
    final String side = side(entries, alliances, reference);
    final int last = vcRounds.get(vcRounds.size() - 1);
    final ToDoubleFunction<JsonObject> vc = j -> ConfrontoHard.vc(j, side, last);
    final ToDoubleFunction<JsonObject> length = j -> j.get("durata_s").getAsDouble();
    final ToDoubleFunction<JsonObject> turn = j -> sideTurn(j, side);

    // configuration -> seeds -> replica -> games
    final Map<String, Map<String, Map<Integer, List<JsonObject>>>> groups = new LinkedHashMap<>();
    entries.forEach(
        e ->
            groups
                .computeIfAbsent(e.config(), k -> new TreeMap<>())
                .computeIfAbsent(e.seeds(), k -> new TreeMap<>())
                .computeIfAbsent(e.replica(), k -> new ArrayList<>())
                .add(e.json()));

    final String label = Riepilogo.allianceLabel(side);
    md.append("\n## Varianza tra run: semi, repliche, tempo e macchina (lato ")
        .append(label)
        .append(")\n\n");
    md.append(
        "Media ± deviazione standard [errore standard] sui semi; IC 95% con il t di Student"
            + " (gruppo) e di Welch (differenze). Semi \"1-99\" e \"100+\" = semi bassi e alti;"
            + " replica = stessa configurazione e stessi semi giocati di nuovo nella stessa run (le"
            + " simulazioni dell'AI non sono seminate, quindi una replica non ripete la"
            + " partita).\n\n");
    md.append("| Configurazione | Semi | Replica | Partite | VC R")
        .append(last)
        .append(' ')
        .append(label)
        .append(" | IC 95% | Durata partita (s) | Turno medio ")
        .append(label)
        .append(" (s) |\n|---|---|---|---|---|---|---|---|\n");
    groups.forEach(
        (config, bySeeds) ->
            bySeeds.forEach(
                (seeds, byReplica) ->
                    byReplica.forEach(
                        (replica, list) -> {
                          final Stat s = Stat.of(list, vc);
                          md.append("| ")
                              .append(config)
                              .append(" | ")
                              .append(seeds)
                              .append(" | ")
                              .append(replica)
                              .append(" | ")
                              .append(list.size())
                              .append(" | ")
                              .append(ConfrontoConferma.text(s))
                              .append(" | ")
                              .append(interval(s))
                              .append(" | ")
                              .append(ConfrontoConferma.text(Stat.of(list, length)))
                              .append(" | ")
                              .append(ConfrontoConferma.text(Stat.of(list, turn)))
                              .append(" |\n");
                        })));

    md.append(
        "\n| Differenza (A − B) | Δ VC | IC 95% (Welch) | Esito | Semi per l'80% |\n"
            + "|---|---|---|---|---|\n");
    groups.forEach(
        (config, bySeeds) -> {
          // seed-group effect, replica 1
          final List<JsonObject> low = games(bySeeds, "1-99", 1);
          final List<JsonObject> high = games(bySeeds, "100+", 1);
          if (!low.isEmpty() && !high.isEmpty()) {
            difference(md, config + " semi 100+ − " + config + " semi 1-99", high, low, vc);
          }
          // replica effect
          bySeeds.forEach(
              (seeds, byReplica) -> {
                final List<JsonObject> first = byReplica.get(1);
                byReplica.forEach(
                    (replica, list) -> {
                      if (replica > 1 && first != null) {
                        difference(
                            md,
                            config + " semi " + seeds + " replica " + replica + " − replica 1",
                            list,
                            first,
                            vc);
                      }
                    });
              });
          // against Hard vs Hard, same seeds and replica 1
          if (!config.equals(reference) && groups.containsKey(reference)) {
            bySeeds.forEach(
                (seeds, byReplica) -> {
                  final List<JsonObject> ref = games(groups.get(reference), seeds, 1);
                  final List<JsonObject> mine = byReplica.get(1);
                  if (mine != null && !ref.isEmpty()) {
                    difference(
                        md,
                        config + " − " + reference + " (semi " + seeds + ")",
                        mine,
                        ref,
                        vc);
                  }
                });
          }
        });

    pairedReplicas(md, entries, side, last);
    correlations(md, entries, reference, vc, length);
    machines(md, entries, reference, vc);
  }

  private static List<JsonObject> games(
      final Map<String, Map<Integer, List<JsonObject>>> bySeeds,
      final String seeds,
      final int replica) {
    return bySeeds.getOrDefault(seeds, Map.of()).getOrDefault(replica, List.of());
  }

  private static void difference(
      final StringBuilder md,
      final String name,
      final List<JsonObject> a,
      final List<JsonObject> b,
      final ToDoubleFunction<JsonObject> value) {
    final Welch w = Welch.of(Stat.of(a, value), Stat.of(b, value));
    md.append("| ")
        .append(name)
        .append(" | ")
        .append(w.deltaText())
        .append(" | ")
        .append(w.intervalText())
        .append(" | ")
        .append(w.outcome())
        .append(" | ")
        .append(w.seedsText())
        .append(" |\n");
  }

  /** Same configuration and seed in two replicas: does the seed fix the outcome? */
  private static void pairedReplicas(
      final StringBuilder md, final List<Entry> entries, final String side, final int last) {
    final Map<String, Map<Integer, Double>> bySeed = new TreeMap<>();
    entries.forEach(
        e ->
            bySeed
                .computeIfAbsent(e.config() + " seme " + e.seed(), k -> new TreeMap<>())
                .put(e.replica(), ConfrontoHard.vc(e.json(), side, last)));
    final List<double[]> pairs = new ArrayList<>();
    bySeed.values().stream()
        .filter(m -> m.containsKey(1) && m.containsKey(2))
        .forEach(m -> pairs.add(new double[] {m.get(1), m.get(2)}));
    if (pairs.size() < 2) {
      return;
    }
    final double meanAbs =
        pairs.stream().mapToDouble(p -> Math.abs(p[0] - p[1])).average().orElse(Double.NaN);
    md.append("\nStesso seme giocato due volte (")
        .append(pairs.size())
        .append(" coppie): differenza media assoluta ")
        .append(String.format(Locale.ROOT, "%.2f", meanAbs))
        .append(" VC, ")
        .append(correlationText(pairs))
        .append(
            ". Correlazione vicina a 0 = il seme dei dadi non fissa l'esito (le simulazioni"
                + " dell'AI decidono); vicina a 1 = il seme conta.\n");
  }

  /** Length of the game against the side's VC, for the non-reference games and for all. */
  private static void correlations(
      final StringBuilder md,
      final List<Entry> entries,
      final String reference,
      final ToDoubleFunction<JsonObject> vc,
      final ToDoubleFunction<JsonObject> length) {
    final List<double[]> pro =
        entries.stream()
            .filter(e -> !e.config().equals(reference))
            .map(e -> new double[] {length.applyAsDouble(e.json()), vc.applyAsDouble(e.json())})
            .toList();
    final List<double[]> all =
        entries.stream()
            .map(e -> new double[] {length.applyAsDouble(e.json()), vc.applyAsDouble(e.json())})
            .toList();
    md.append("\nDurata della partita e VC del lato: partite non Hard vs Hard ")
        .append(correlationText(pro))
        .append("; tutte ")
        .append(correlationText(all))
        .append(
            ". Un limite di tempo che pesa sulle decisioni darebbe una correlazione negativa netta"
                + " (partite lente = AI piu' debole).\n");
  }

  /** The side's VC per machine (CPU model and processors), reference and the others apart. */
  private static void machines(
      final StringBuilder md,
      final List<Entry> entries,
      final String reference,
      final ToDoubleFunction<JsonObject> vc) {
    final Map<String, List<JsonObject>> ref = new TreeMap<>();
    final Map<String, List<JsonObject>> other = new TreeMap<>();
    entries.forEach(
        e ->
            (e.config().equals(reference) ? ref : other)
                .computeIfAbsent(machine(e.json()), k -> new ArrayList<>())
                .add(e.json()));
    md.append("\n| Macchina | ")
        .append(reference)
        .append(": partite, VC | Altre configurazioni: partite, VC |\n|---|---|---|\n");
    final java.util.Set<String> keys = new java.util.TreeSet<>(ref.keySet());
    keys.addAll(other.keySet());
    for (final String k : keys) {
      md.append("| ")
          .append(k)
          .append(" | ")
          .append(cell(ref.get(k), vc))
          .append(" | ")
          .append(cell(other.get(k), vc))
          .append(" |\n");
    }
  }

  private static String cell(final List<JsonObject> list, final ToDoubleFunction<JsonObject> vc) {
    if (list == null || list.isEmpty()) {
      return "-";
    }
    return list.size() + ", " + ConfrontoConferma.text(Stat.of(list, vc));
  }

  static String machine(final JsonObject json) {
    if (!json.has("tempi")) {
      return "non registrata";
    }
    final JsonObject env = json.getAsJsonObject("tempi").getAsJsonObject("ambiente");
    return RiepilogoGenerale.text(env, "cpu")
        + ", "
        + env.get("processori").getAsInt()
        + " processori";
  }

  /** Mean seconds per turn of the side (its nations' turns added up). */
  static double sideTurn(final JsonObject json, final String side) {
    if (!json.has("tempi") || !json.has("alleanze")) {
      return Double.NaN;
    }
    final JsonObject nations = json.getAsJsonObject("tempi").getAsJsonObject("per_nazione");
    double total = 0;
    boolean any = false;
    for (final JsonElement n : json.getAsJsonObject("alleanze").getAsJsonArray(side)) {
      if (nations.has(n.getAsString())) {
        total += nations.getAsJsonObject(n.getAsString()).get("media_s").getAsDouble();
        any = true;
      }
    }
    return any ? total : Double.NaN;
  }

  /** The side that changes in the non-reference configurations (the first alliance otherwise). */
  private static String side(
      final List<Entry> entries, final List<String> alliances, final String reference) {
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    entries.forEach(
        e -> byConfig.computeIfAbsent(e.config(), k -> new ArrayList<>()).add(e.json()));
    return byConfig.entrySet().stream()
        .filter(e -> !e.getKey().equals(reference))
        .map(e -> ConfrontoHard.changedSide(e.getValue(), alliances))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(alliances.get(0));
  }

  /** "95% CI [low, high]" of one group's mean (Student's t, n − 1 degrees of freedom). */
  static String interval(final Stat s) {
    if (s.count() < 2) {
      return "-";
    }
    final double half =
        ConfrontoConferma.tQuantile975(s.count() - 1.0) * s.sdValue() / Math.sqrt(s.count());
    return String.format(
        Locale.ROOT, "[%.2f, %.2f]", s.meanValue() - half, s.meanValue() + half);
  }

  /** "r 0.12 (IC 95% -0.30, 0.50, n 30)" with Fisher's z. */
  static String correlationText(final List<double[]> pairs) {
    final List<double[]> ok =
        pairs.stream().filter(p -> !Double.isNaN(p[0]) && !Double.isNaN(p[1])).toList();
    final int n = ok.size();
    if (n < 4) {
      return "r - (n " + n + ")";
    }
    final double mx = ok.stream().mapToDouble(p -> p[0]).average().orElse(0);
    final double my = ok.stream().mapToDouble(p -> p[1]).average().orElse(0);
    double sxy = 0;
    double sxx = 0;
    double syy = 0;
    for (final double[] p : ok) {
      sxy += (p[0] - mx) * (p[1] - my);
      sxx += (p[0] - mx) * (p[0] - mx);
      syy += (p[1] - my) * (p[1] - my);
    }
    if (sxx == 0 || syy == 0) {
      return "r - (valori costanti, n " + n + ")";
    }
    final double r = sxy / Math.sqrt(sxx * syy);
    final double z = 0.5 * Math.log((1 + r) / (1 - r));
    final double half = 1.959964 / Math.sqrt(n - 3.0);
    return String.format(
        Locale.ROOT,
        "r %.2f (IC 95%% %.2f, %.2f, n %d)",
        r,
        Math.tanh(z - half),
        Math.tanh(z + half),
        n);
  }
}
