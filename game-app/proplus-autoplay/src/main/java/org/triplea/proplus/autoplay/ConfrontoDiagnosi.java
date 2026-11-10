package org.triplea.proplus.autoplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.triplea.proplus.autoplay.ConfrontoConferma.Welch;
import org.triplea.proplus.autoplay.ConfrontoHard.Stat;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step R0: where the Axis "Pro+ Stratega LLM" loses VC against Hard. Written when "Hard / Hard" and
 * at least one configuration with the strategist on one side and Hard on the other are there. Per
 * configuration: final VC of the strategist's side (mean, sd, min-max), final income, TUV lost,
 * capitals of the side fallen; the differences of every strategist configuration from Hard vs Hard
 * and of the configurations with a switch off ("[senza liste]", "[senza tenute]") from the full
 * strategist on the same side, with Welch's 95% interval ("esclude / include lo 0"); for the
 * strategist without a key the check against Hard ({@code controllo_nudo}).
 */
final class ConfrontoDiagnosi {
  private ConfrontoDiagnosi() {}

  static final String SENZA_CHIAVE = "[senza chiave]";

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
    final Map<String, String> strategist = new LinkedHashMap<>();
    byConfig.forEach(
        (config, list) -> {
          final String side = ConfrontoHard.changedSide(list, alliances);
          if (side != null
              && AutoplayOptions.STRATEGA.equals(
                  RiepilogoGenerale.text(
                      list.get(0).getAsJsonObject("parametri").getAsJsonObject("tipi"), side))) {
            strategist.put(config, side);
          }
        });
    if (ref == null || strategist.isEmpty()) {
      return;
    }
    final int last = vcRounds.get(vcRounds.size() - 1);
    md.append("\n## Diagnosi Stratega (R0)\n\n");
    md.append(
        "Lato dello Stratega contro Hard. VC R"
            + last
            + " = media ± ds (min-max); reddito finale e TUV perso = media ± ds; capitali cadute ="
            + " eventi \"caduta\" delle capitali del lato in tutte le partite (media per partita)."
            + " Intervalli al 95% con il t di Welch. **Con 10 semi per configurazione si"
            + " distinguono solo differenze di circa 1,2 VC o piu'** (ds ~1,3): \"include lo 0\""
            + " non vuol dire \"uguale\".\n\n");
    md.append(
        "| Configurazione | Lato | Partite | VC R"
            + last
            + " | Reddito finale | TUV perso | Capitali cadute (totale, per partita) |\n"
            + "|---|---|---|---|---|---|---|\n");
    final Map<String, String> rows = new LinkedHashMap<>();
    strategist.forEach((config, side) -> rows.put(config, side));
    // Hard vs Hard once per side that some strategist configuration changes
    strategist.values().stream().distinct().forEach(side -> row(md, reference, side, ref, last));
    rows.forEach((config, side) -> row(md, config, side, byConfig.get(config), last));

    md.append(
        "\nDifferenze (A − B, lato dello Stratega; positivo = A ha di piu'):\n\n"
            + "| Differenza (A − B) | Lato | Valore | Δ | IC 95% (Welch) | Esito | Semi per"
            + " l'80% |\n|---|---|---|---|---|---|---|\n");
    strategist.forEach(
        (config, side) -> {
          differences(md, config, byConfig.get(config), reference, ref, side, last);
          if (config.contains("[senza ") && !config.contains(SENZA_CHIAVE)) {
            final String full = full(config);
            if (byConfig.containsKey(full) && side.equals(strategist.get(full))) {
              differences(md, config, byConfig.get(config), full, byConfig.get(full), side, last);
            }
          }
        });

    // the strategist without a key must be Hard: the check against Hard
    final List<String> noKey =
        strategist.keySet().stream().filter(c -> c.contains(SENZA_CHIAVE)).toList();
    if (!noKey.isEmpty()) {
      md.append(
          "\nControllo contro Hard dello Stratega senza chiave (`ControlloTuning`: domande del"
              + " tuning con risposta diversa da Hard; \"operazione\" = risposte marcate come"
              + " dell'operazione/strategia, es. il tetto della strategia):\n\n"
              + "| Configurazione | Partite | Domande | Differenze | Risposte \"operazione\""
              + " diverse | Esempi |\n|---|---|---|---|---|---|\n");
      for (final String config : noKey) {
        final List<JsonObject> list = byConfig.get(config);
        final long[] c = check(list, strategist.get(config));
        md.append("| ")
            .append(config)
            .append(" | ")
            .append(list.size())
            .append(" | ")
            .append(c[1])
            .append(" | ")
            .append(c[0])
            .append(" | ")
            .append(c[2])
            .append(" | ")
            .append(String.join("; ", examples(list, strategist.get(config), 3)))
            .append(" |\n");
      }
    }
  }

  /** The configuration with every switch on and the key ("Pro+ Stratega LLM / Hard"). */
  static String full(final String config) {
    return config.replaceAll(" \\[senza [a-z]+\\]", "");
  }

  private static void row(
      final StringBuilder md,
      final String config,
      final String side,
      final List<JsonObject> list,
      final int last) {
    final Stat vc = Stat.of(list, j -> ConfrontoHard.vc(j, side, last));
    final Stat income = Stat.of(list, j -> ConfrontoHard.finalSum(j, side, "reddito"));
    final Stat lost = Stat.of(list, j -> ConfrontoHard.lostTuv(j, side));
    final double capitals =
        list.stream().mapToDouble(j -> ConfrontoHard.capitalsLost(j, side)).sum();
    md.append("| ")
        .append(config)
        .append(" | ")
        .append(Riepilogo.allianceLabel(side))
        .append(" | ")
        .append(list.size())
        .append(" | ")
        .append(vc.count() == 0 ? "-" : num(vc.meanValue()) + " ± " + num(vc.sdValue()))
        .append(vc.count() == 0 ? "" : " (" + num(vc.min()) + "-" + num(vc.max()) + ")")
        .append(" | ")
        .append(income.count() == 0 ? "-" : num(income.meanValue()) + " ± " + num(income.sdValue()))
        .append(" | ")
        .append(lost.count() == 0 ? "-" : num(lost.meanValue()) + " ± " + num(lost.sdValue()))
        .append(" | ")
        .append(String.format(Locale.ROOT, "%.0f, %.2f", capitals, capitals / list.size()))
        .append(" |\n");
  }

  private static void differences(
      final StringBuilder md,
      final String nameA,
      final List<JsonObject> a,
      final String nameB,
      final List<JsonObject> b,
      final String side,
      final int last) {
    final Map<String, ToDoubleFunction<JsonObject>> values = new LinkedHashMap<>();
    values.put("VC R" + last, j -> ConfrontoHard.vc(j, side, last));
    values.put("reddito finale", j -> ConfrontoHard.finalSum(j, side, "reddito"));
    values.put("TUV perso", j -> ConfrontoHard.lostTuv(j, side));
    values.put("capitali cadute", j -> ConfrontoHard.capitalsLost(j, side));
    values.forEach(
        (label, f) -> {
          final Welch w = Welch.of(Stat.of(a, f), Stat.of(b, f));
          md.append("| ")
              .append(nameA)
              .append(" − ")
              .append(nameB)
              .append(" | ")
              .append(Riepilogo.allianceLabel(side))
              .append(" | ")
              .append(label)
              .append(" | ")
              .append(w.deltaText())
              .append(" | ")
              .append(w.intervalText())
              .append(" | ")
              .append(w.outcome())
              .append(" | ")
              .append(w.seedsText())
              .append(" |\n");
        });
  }

  /** {differences, questions, operation's answers} of the side's nations over the games. */
  static long[] check(final List<JsonObject> games, final String side) {
    final long[] sum = new long[3];
    for (final JsonObject j : games) {
      final JsonObject c = j.getAsJsonObject("controllo_nudo");
      if (c == null) {
        continue;
      }
      final List<String> nations = nations(j, side);
      for (final Map.Entry<String, JsonElement> nation : c.entrySet()) {
        if (!nations.contains(nation.getKey())) {
          continue;
        }
        for (final Map.Entry<String, JsonElement> r :
            nation.getValue().getAsJsonObject().entrySet()) {
          final JsonObject o = r.getValue().getAsJsonObject();
          sum[0] += o.get("differenze").getAsLong();
          sum[1] += o.get("domande").getAsLong();
          sum[2] += o.get("operazione").getAsLong();
        }
      }
    }
    return sum;
  }

  /** Up to {@code max} distinct examples of differences ("method: answer instead of Hard's"). */
  private static List<String> examples(
      final List<JsonObject> games, final String side, final int max) {
    final List<String> out = new ArrayList<>();
    final java.util.Set<String> methods = new java.util.HashSet<>();
    for (final JsonObject j : games) {
      final JsonObject c = j.getAsJsonObject("controllo_nudo");
      if (c == null) {
        continue;
      }
      final List<String> nations = nations(j, side);
      for (final Map.Entry<String, JsonElement> nation : c.entrySet()) {
        if (!nations.contains(nation.getKey())) {
          continue;
        }
        for (final Map.Entry<String, JsonElement> r :
            nation.getValue().getAsJsonObject().entrySet()) {
          final JsonObject o = r.getValue().getAsJsonObject();
          if (!o.has("esempi")) {
            continue;
          }
          for (final JsonElement e : o.getAsJsonArray("esempi")) {
            final String text = e.getAsString().replace('|', '/');
            final String method = text.contains(":") ? text.substring(0, text.indexOf(':')) : text;
            if (out.size() < max && methods.add(method)) {
              out.add(nation.getKey() + " " + r.getKey() + " " + text);
            }
          }
        }
      }
    }
    return out;
  }

  private static List<String> nations(final JsonObject json, final String side) {
    final List<String> nations = new ArrayList<>();
    if (json.has("alleanze") && json.getAsJsonObject("alleanze").has(side)) {
      json.getAsJsonObject("alleanze")
          .getAsJsonArray(side)
          .forEach(m -> nations.add(m.getAsString()));
    }
    return nations;
  }

  private static String num(final double x) {
    if (Double.isNaN(x)) {
      return "-";
    }
    return Math.abs(x) >= 100
        ? String.format(Locale.ROOT, "%.0f", x)
        : String.format(Locale.ROOT, "%.2f", x);
  }
}
