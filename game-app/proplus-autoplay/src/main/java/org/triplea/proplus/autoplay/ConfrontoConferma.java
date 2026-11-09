package org.triplea.proplus.autoplay;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.triplea.proplus.autoplay.ConfrontoHard.Stat;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step CONFERMA: the statistics of the comparison against Hard. Written when "Hard / Hard" and at
 * least one configuration with a single non-Hard side are there. For every configuration: VC at
 * the end, final income and TUV lost per alliance with mean, standard deviation and standard
 * error; then the differences of the changed side's VC and income from Hard vs Hard and, for each
 * configuration with components off, from the same side with every component on ("Pro+ attuale"),
 * with a 95% confidence interval from Welch's t (unequal variances, Welch-Satterthwaite degrees of
 * freedom) and the seeds per configuration that an 80% power test would need for that difference.
 */
final class ConfrontoConferma {
  private ConfrontoConferma() {}

  /** z of 97.5% + z of 80%: two-sided 5% test with 80% power. */
  private static final double POWER_Z = 1.959964 + 0.841621;

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
    if (ref == null
        || byConfig.values().stream()
            .noneMatch(l -> ConfrontoHard.changedSide(l, alliances) != null)) {
      return;
    }
    final int last = vcRounds.get(vcRounds.size() - 1);
    md.append("\n## Statistica del confronto contro Hard (riferimento: ")
        .append(reference)
        .append(")\n\n");
    md.append(
        "Media ± deviazione standard [errore standard] sui semi. Intervalli di confidenza al 95% con"
            + " il t di Welch (varianze diverse, gradi di liberta' di Welch-Satterthwaite); \"esclude"
            + " lo 0\" = differenza confermata a quel livello. \"Semi per l'80%\" = semi per"
            + " configurazione che servirebbero per vedere quella differenza (se e' quella vera)"
            + " con un test al 5% e potenza 80%.\n\n");
    md.append("| Configurazione | Partite | ")
        .append(
            String.join(
                " | ",
                alliances.stream()
                    .map(a -> "VC R" + last + " " + Riepilogo.allianceLabel(a))
                    .toList()))
        .append(" | ")
        .append(
            String.join(
                " | ",
                alliances.stream()
                    .map(a -> "Reddito finale " + Riepilogo.allianceLabel(a))
                    .toList()))
        .append(" | ")
        .append(
            String.join(
                " | ",
                alliances.stream()
                    .map(a -> "TUV perso " + Riepilogo.allianceLabel(a))
                    .toList()))
        .append(" |\n|---|---|")
        .append("---|".repeat(alliances.size() * 3))
        .append('\n');
    byConfig.forEach(
        (config, list) -> {
          md.append("| ").append(config).append(" | ").append(list.size());
          for (final String a : alliances) {
            md.append(" | ").append(text(Stat.of(list, j -> ConfrontoHard.vc(j, a, last))));
          }
          for (final String a : alliances) {
            md.append(" | ")
                .append(text(Stat.of(list, j -> ConfrontoHard.finalSum(j, a, "reddito"))));
          }
          for (final String a : alliances) {
            md.append(" | ").append(text(Stat.of(list, j -> ConfrontoHard.lostTuv(j, a))));
          }
          md.append(" |\n");
        });
    md.append(
        "\nDifferenze del lato che cambia (A − B: positivo = A ha di piu'):\n\n"
            + "| Differenza (A − B) | Lato | Valore | Δ | IC 95% (Welch) | Esito | Semi per l'80% |\n"
            + "|---|---|---|---|---|---|---|\n");
    byConfig.forEach(
        (config, list) -> {
          final String side = ConfrontoHard.changedSide(list, alliances);
          if (side == null) {
            return;
          }
          differences(md, config, list, reference, ref, side, last);
          final String off = RiepilogoGenerale.ablation(list.get(0), side);
          if (off.isEmpty()) {
            return;
          }
          baseline(byConfig, list.get(0), side, alliances)
              .ifPresent(
                  base -> differences(md, base.getKey(), base.getValue(), config, list, side, last));
        });
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

  /** The same side, type and plan with every component on, the other side unchanged. */
  private static java.util.Optional<Map.Entry<String, List<JsonObject>>> baseline(
      final Map<String, List<JsonObject>> byConfig,
      final JsonObject game,
      final String side,
      final List<String> alliances) {
    final String plan = plan(game, side);
    final String kind = ConfrontoHard.kind(game, side);
    return byConfig.entrySet().stream()
        .filter(
            e -> {
              final JsonObject j = e.getValue().get(0);
              return side.equals(ConfrontoHard.changedSide(e.getValue(), alliances))
                  && RiepilogoGenerale.ablation(j, side).isEmpty()
                  && plan.equals(plan(j, side))
                  && kind.equals(ConfrontoHard.kind(j, side));
            })
        .findFirst();
  }

  private static String plan(final JsonObject json, final String side) {
    return RiepilogoGenerale.text(json.getAsJsonObject("parametri").getAsJsonObject("piani"), side);
  }

  /** "4.5 ± 1.2 [0.49]". */
  static String text(final Stat s) {
    if (s.count() == 0) {
      return "-";
    }
    return number(s.meanValue())
        + " ± "
        + number(s.sdValue())
        + " ["
        + number(s.count() > 0 ? s.sdValue() / Math.sqrt(s.count()) : 0)
        + "]";
  }

  private static String number(final double x) {
    if (Double.isNaN(x) || Double.isInfinite(x)) {
      return "-";
    }
    return Math.abs(x) >= 100
        ? String.format(Locale.ROOT, "%.0f", x)
        : String.format(Locale.ROOT, "%.2f", x);
  }

  /**
   * Welch's two-sample comparison: difference of the means, its standard error, the
   * Welch-Satterthwaite degrees of freedom and the 95% interval.
   */
  record Welch(double delta, double se, double df, int n1, int n2, double pooledSd) {
    static Welch of(final Stat a, final Stat b) {
      final int n1 = a.count();
      final int n2 = b.count();
      if (n1 < 2 || n2 < 2) {
        return new Welch(
            n1 > 0 && n2 > 0 ? a.meanValue() - b.meanValue() : Double.NaN,
            Double.NaN,
            Double.NaN,
            n1,
            n2,
            Double.NaN);
      }
      final double v1 = a.sdValue() * a.sdValue() / n1;
      final double v2 = b.sdValue() * b.sdValue() / n2;
      final double se = Math.sqrt(v1 + v2);
      final double df =
          v1 + v2 == 0
              ? n1 + n2 - 2.0
              : (v1 + v2) * (v1 + v2) / (v1 * v1 / (n1 - 1) + v2 * v2 / (n2 - 1));
      final double pooled =
          Math.sqrt((a.sdValue() * a.sdValue() + b.sdValue() * b.sdValue()) / 2);
      return new Welch(a.meanValue() - b.meanValue(), se, df, n1, n2, pooled);
    }

    double low() {
      return delta - tQuantile975(df) * se;
    }

    double high() {
      return delta + tQuantile975(df) * se;
    }

    boolean known() {
      return !Double.isNaN(se);
    }

    String deltaText() {
      return Double.isNaN(delta) ? "-" : (delta >= 0 ? "+" : "") + number(delta);
    }

    String intervalText() {
      if (!known()) {
        return "-";
      }
      return "["
          + number(low())
          + ", "
          + number(high())
          + "] (t "
          + number(tQuantile975(df))
          + ", gl "
          + String.format(Locale.ROOT, "%.1f", df)
          + ")";
    }

    String outcome() {
      if (!known()) {
        return "pochi dati";
      }
      if (low() > 0) {
        return "A > B (esclude lo 0)";
      }
      if (high() < 0) {
        return "A < B (esclude lo 0)";
      }
      return "include lo 0";
    }

    /** Seeds per configuration for a 5% two-sided test with 80% power at this difference. */
    int seedsForPower() {
      if (!known() || delta == 0) {
        return -1;
      }
      return (int) Math.ceil(2 * POWER_Z * POWER_Z * pooledSd * pooledSd / (delta * delta));
    }

    String seedsText() {
      final int n = seedsForPower();
      return n < 0 ? "-" : String.valueOf(Math.max(2, n));
    }
  }

  /**
   * 97.5% quantile of Student's t with {@code df} degrees of freedom (Cornish-Fisher expansion
   * around the normal: 2.5677 at 5 degrees for the exact 2.5706, 2.2281 at 10, 1.96 at infinity;
   * below 3 degrees the exact values of the table).
   */
  static double tQuantile975(final double df) {
    if (Double.isNaN(df) || df <= 0) {
      return Double.NaN;
    }
    if (df < 1.5) {
      return 12.706;
    }
    if (df < 2.5) {
      return 4.303;
    }
    if (df < 3) {
      return 3.182;
    }
    final double z = 1.959964;
    final double z3 = z * z * z;
    final double z5 = z3 * z * z;
    final double z7 = z5 * z * z;
    return z
        + (z3 + z) / (4 * df)
        + (5 * z5 + 16 * z3 + 3 * z) / (96 * df * df)
        + (3 * z7 + 19 * z5 + 17 * z3 - 15 * z) / (384 * df * df * df);
  }
}
