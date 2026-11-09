package org.triplea.proplus.autoplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step CONFRONTO: Pro+ against the original Hard. Written only when the games include the
 * reference "Hard / Hard" and at least one configuration where one alliance is Pro+ and the other
 * Hard. For every configuration: VC at 1/3, 2/3 and end, wins, final PUs and income, TUV lost,
 * capitals lost, real landings (mean of the seeds); for the alliance that changes against Hard vs
 * Hard: the same values with min-max over the seeds, the difference from the reference and a
 * simple verdict on the final VC.
 */
final class ConfrontoHard {
  static final String HARD = "Hard";

  private ConfrontoHard() {}

  static void write(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final List<Integer> vcRounds) {
    final String reference = String.join(" / ", alliances.stream().map(a -> HARD).toList());
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
        || byConfig.keySet().stream().noneMatch(c -> changedSide(byConfig.get(c), alliances) != null)) {
      return;
    }
    final String labels =
        String.join(" / ", alliances.stream().map(Riepilogo::allianceLabel).toList());
    md.append("\n## Confronto contro Hard (riferimento: ")
        .append(reference)
        .append(")\n\nMedia dei semi, ")
        .append(labels)
        .append("; VC tra parentesi min-max. PU = cassa a fine partita, reddito = produzione;")
        .append(" TUV perso = somma delle perdite in tutte le battaglie.\n\n| Configurazione | Partite |");
    vcRounds.forEach(r -> md.append(" VC R").append(r).append(" |"));
    md.append(
        " Vittorie | PU finali | Reddito finale | TUV perso | Capitali perse | Sbarchi veri |\n|---|---|");
    vcRounds.forEach(r -> md.append("---|"));
    md.append("---|---|---|---|---|---|\n");
    byConfig.forEach(
        (config, list) -> {
          md.append("| ").append(config).append(" | ").append(list.size()).append(" |");
          for (final int r : vcRounds) {
            md.append(' ')
                .append(
                    join(alliances, a -> Stat.of(list, j -> vc(j, a, r)).meanAndRange()))
                .append(" |");
          }
          md.append(' ')
              .append(wins(list, alliances))
              .append(" | ")
              .append(join(alliances, a -> Stat.of(list, j -> finalSum(j, a, "pu")).mean()))
              .append(" | ")
              .append(join(alliances, a -> Stat.of(list, j -> finalSum(j, a, "reddito")).mean()))
              .append(" | ")
              .append(join(alliances, a -> Stat.of(list, j -> lostTuv(j, a)).mean()))
              .append(" | ")
              .append(join(alliances, a -> Stat.of(list, j -> capitalsLost(j, a)).mean()))
              .append(" | ")
              .append(
                  join(
                      alliances,
                      a -> Stat.of(list, j -> RiepilogoGenerale.landingsOf(j, a)).mean()))
              .append(" |\n");
        });

    md.append(
        "\nLato che cambia rispetto a Hard vs Hard (valore del lato: media (min-max), ds ="
            + " deviazione standard sui semi; Δ = differenza dalla media dello stesso lato in"
            + " Hard vs Hard; rapporto TUV = TUV perso dal nemico / TUV perso dal lato, piu' alto"
            + " = meglio):\n\n| Configurazione | Lato | AI |");
    vcRounds.forEach(r -> md.append(" VC R").append(r).append(" |"));
    md.append(
        " Δ VC fine | Vittorie del lato | PU finali | Reddito finale | Rapporto TUV | Capitali"
            + " perse | Sbarchi veri | Verdetto |\n|---|---|---|");
    vcRounds.forEach(r -> md.append("---|"));
    md.append("---|---|---|---|---|---|---|---|\n");
    final int last = vcRounds.get(vcRounds.size() - 1);
    final Map<String, List<JsonObject>> byKind = new LinkedHashMap<>();
    byConfig.forEach(
        (config, list) -> {
          final String side = changedSide(list, alliances);
          if (side == null) {
            return;
          }
          final String kind = kind(list.get(0), side);
          byKind.computeIfAbsent(side + "|" + kind, k -> new ArrayList<>()).addAll(list);
          md.append("| ")
              .append(config)
              .append(" | ")
              .append(Riepilogo.allianceLabel(side))
              .append(" | ")
              .append(kind)
              .append(" |");
          for (final int r : vcRounds) {
            final Stat s = Stat.of(list, j -> vc(j, side, r));
            md.append(' ')
                .append(r == last ? s.meanAndRange() + ", ds " + s.sd() : s.meanAndRange())
                .append(" |");
          }
          final Stat vcEnd = Stat.of(list, j -> vc(j, side, last));
          final Stat vcRef = Stat.of(ref, j -> vc(j, side, last));
          md.append(' ')
              .append(vcEnd.delta(vcRef))
              .append(" | ")
              .append(sideWins(list, side))
              .append(" | ")
              .append(compare(list, ref, j -> finalSum(j, side, "pu")))
              .append(" | ")
              .append(compare(list, ref, j -> finalSum(j, side, "reddito")))
              .append(" | ")
              .append(compare(list, ref, j -> tuvRatio(j, side, alliances)))
              .append(" | ")
              .append(compare(list, ref, j -> capitalsLost(j, side)))
              .append(" | ")
              .append(compare(list, ref, j -> RiepilogoGenerale.landingsOf(j, side)))
              .append(" | ")
              .append(verdict(vcEnd, vcRef))
              .append(" |\n");
        });

    md.append(
        "\nRisposta (tutte le partite dello stesso tipo di AI per lato, piani insieme; verdetto"
            + " sulle VC finali del lato: \"piu' forte\"/\"piu' debole\" se la differenza supera"
            + " la deviazione standard media dei due gruppi e mezza VC, altrimenti \"non"
            + " distinguibile\"; indice semplice, non un test statistico):\n\n");
    byKind.forEach(
        (key, list) -> {
          final String side = key.substring(0, key.indexOf('|'));
          final String kind = key.substring(key.indexOf('|') + 1);
          final Stat vcEnd = Stat.of(list, j -> vc(j, side, last));
          final Stat vcRef = Stat.of(ref, j -> vc(j, side, last));
          final Stat ratio = Stat.of(list, j -> tuvRatio(j, side, alliances));
          final Stat ratioRef = Stat.of(ref, j -> tuvRatio(j, side, alliances));
          md.append("- ")
              .append(Riepilogo.allianceLabel(side))
              .append(", ")
              .append(kind)
              .append(" contro Hard (")
              .append(list.size())
              .append(" partite, riferimento ")
              .append(ref.size())
              .append("): VC R")
              .append(last)
              .append(' ')
              .append(vcEnd.meanAndRange())
              .append(" contro ")
              .append(vcRef.meanAndRange())
              .append(" (")
              .append(vcEnd.delta(vcRef))
              .append("), rapporto TUV ")
              .append(ratio.mean())
              .append(" contro ")
              .append(ratioRef.mean())
              .append(", vittorie del lato ")
              .append(sideWins(list, side))
              .append(" contro ")
              .append(sideWins(ref, side))
              .append(" ⇒ **")
              .append(verdict(vcEnd, vcRef))
              .append("**\n");
        });
  }

  /** The only alliance that is not Hard in all the games of the configuration, else null. */
  static String changedSide(final List<JsonObject> games, final List<String> alliances) {
    final List<String> notHard =
        alliances.stream()
            .filter(
                a ->
                    games.stream()
                        .noneMatch(
                            j ->
                                AutoplayOptions.HARD.equals(
                                    RiepilogoGenerale.text(
                                        j.getAsJsonObject("parametri").getAsJsonObject("tipi"),
                                        a))))
            .toList();
    final boolean othersHard =
        alliances.stream()
            .filter(a -> !notHard.contains(a))
            .allMatch(
                a ->
                    games.stream()
                        .allMatch(
                            j ->
                                AutoplayOptions.HARD.equals(
                                    RiepilogoGenerale.text(
                                        j.getAsJsonObject("parametri").getAsJsonObject("tipi"),
                                        a))));
    return notHard.size() == 1 && othersHard ? notHard.get(0) : null;
  }

  /** "Pro+ regole", "Pro+ Haiku" (decider llm), "Pro+ LLM claude-sonnet-5-5". */
  static String kind(final JsonObject json, final String alliance) {
    final String decider = RiepilogoGenerale.decider(json, alliance);
    if ("regole".equals(decider)) {
      return "Pro+ regole";
    }
    if ("llm".equals(decider)) {
      return "Pro+ Haiku";
    }
    return decider.startsWith("llm:") ? "Pro+ LLM " + decider.substring(4) : "Pro+ " + decider;
  }

  static String verdict(final Stat value, final Stat reference) {
    if (value.count() < 2 || reference.count() < 2) {
      return "pochi dati";
    }
    final double d = value.meanValue() - reference.meanValue();
    final double threshold =
        Math.max(
            0.5, Math.sqrt((value.sdValue() * value.sdValue() + reference.sdValue() * reference.sdValue()) / 2));
    if (d > threshold) {
      return "piu' forte";
    }
    if (d < -threshold) {
      return "piu' debole";
    }
    return "non distinguibile";
  }

  private static String compare(
      final List<JsonObject> list,
      final List<JsonObject> ref,
      final ToDoubleFunction<JsonObject> value) {
    final Stat s = Stat.of(list, value);
    return s.meanAndRange() + " " + s.delta(Stat.of(ref, value));
  }

  static double vc(final JsonObject json, final String alliance, final int round) {
    final JsonObject r = RiepilogoGenerale.round(json, round);
    if (r == null || !r.getAsJsonObject("vc").has(alliance)) {
      return Double.NaN;
    }
    return r.getAsJsonObject("vc").getAsJsonObject(alliance).get("numero").getAsDouble();
  }

  static double finalSum(final JsonObject json, final String alliance, final String key) {
    final JsonObject last = RiepilogoGenerale.lastCompleteRound(json);
    return last == null ? Double.NaN : RiepilogoGenerale.sum(json, last, alliance, key);
  }

  static double lostTuv(final JsonObject json, final String alliance) {
    return json.has("tuv_perso") && json.getAsJsonObject("tuv_perso").has(alliance)
        ? json.getAsJsonObject("tuv_perso").get(alliance).getAsDouble()
        : Double.NaN;
  }

  /** TUV lost by the enemies of {@code alliance} / TUV lost by it (NaN when it lost nothing). */
  static double tuvRatio(final JsonObject json, final String alliance, final List<String> alliances) {
    final double own = lostTuv(json, alliance);
    final double enemy =
        alliances.stream()
            .filter(a -> !a.equals(alliance))
            .mapToDouble(a -> lostTuv(json, a))
            .sum();
    return own > 0 ? enemy / own : Double.NaN;
  }

  /** Falls ("caduta") of the capitals of the alliance's nations during the game. */
  static double capitalsLost(final JsonObject json, final String alliance) {
    final var members = json.getAsJsonObject("alleanze").getAsJsonArray(alliance);
    if (members == null || !json.has("capitali") || !json.get("capitali").isJsonArray()) {
      return 0;
    }
    final List<String> nations = new ArrayList<>();
    members.forEach(m -> nations.add(m.getAsString()));
    int n = 0;
    for (final JsonElement e : json.getAsJsonArray("capitali")) {
      final JsonObject c = e.getAsJsonObject();
      if ("caduta".equals(RiepilogoGenerale.text(c, "evento"))
          && nations.contains(RiepilogoGenerale.text(c, "capitale_di"))) {
        n++;
      }
    }
    return n;
  }

  /** "Asse 1 (R11), Alleati 0" or "nessuna". */
  private static String wins(final List<JsonObject> list, final List<String> alliances) {
    final List<String> parts = new ArrayList<>();
    for (final String a : alliances) {
      final List<Integer> rounds = winRounds(list, a);
      if (!rounds.isEmpty()) {
        parts.add(Riepilogo.allianceLabel(a) + " " + roundsText(rounds));
      }
    }
    return parts.isEmpty() ? "nessuna" : String.join(", ", parts);
  }

  private static String sideWins(final List<JsonObject> list, final String alliance) {
    final List<Integer> rounds = winRounds(list, alliance);
    return rounds.isEmpty() ? "0/" + list.size() : roundsText(rounds) + "/" + list.size();
  }

  private static String roundsText(final List<Integer> rounds) {
    return rounds.size()
        + " ("
        + rounds.stream().map(r -> "R" + r).collect(Collectors.joining(", "))
        + ")";
  }

  private static List<Integer> winRounds(final List<JsonObject> list, final String alliance) {
    final List<Integer> rounds = new ArrayList<>();
    for (final JsonObject j : list) {
      final JsonObject outcome = j.getAsJsonObject("esito");
      if (outcome != null
          && outcome.has("vincitore")
          && alliance.equals(outcome.get("vincitore").getAsString())) {
        rounds.add(j.get("round_giocati").getAsInt());
      }
    }
    return rounds;
  }

  private static String join(
      final List<String> alliances, final java.util.function.Function<String, String> value) {
    return String.join(" / ", alliances.stream().map(value).toList());
  }

  /** Mean, min, max and sample standard deviation of one value over the seeds (NaN skipped). */
  record Stat(int count, double meanValue, double min, double max, double sdValue) {
    static Stat of(final List<JsonObject> games, final ToDoubleFunction<JsonObject> value) {
      final List<Double> v = new ArrayList<>();
      for (final JsonObject j : games) {
        final double x = value.applyAsDouble(j);
        if (!Double.isNaN(x)) {
          v.add(x);
        }
      }
      if (v.isEmpty()) {
        return new Stat(0, Double.NaN, Double.NaN, Double.NaN, 0);
      }
      final double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
      final double ss = v.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum();
      return new Stat(
          v.size(),
          mean,
          v.stream().mapToDouble(Double::doubleValue).min().orElse(0),
          v.stream().mapToDouble(Double::doubleValue).max().orElse(0),
          v.size() > 1 ? Math.sqrt(ss / (v.size() - 1)) : 0);
    }

    String mean() {
      return count == 0 ? "-" : number(meanValue);
    }

    String sd() {
      return count == 0 ? "-" : String.format(Locale.ROOT, "%.1f", sdValue);
    }

    String meanAndRange() {
      if (count == 0) {
        return "-";
      }
      return min == max
          ? number(meanValue)
          : number(meanValue) + " (" + number(min) + "-" + number(max) + ")";
    }

    String delta(final Stat reference) {
      if (count == 0 || reference.count() == 0) {
        return "Δ -";
      }
      final double d = meanValue - reference.meanValue();
      return "Δ " + (d >= 0 ? "+" : "") + number(d);
    }

    private static String number(final double x) {
      if (Math.abs(x - Math.rint(x)) < 1e-9) {
        return String.valueOf((long) Math.rint(x));
      }
      return Math.abs(x) >= 10
          ? String.format(Locale.ROOT, "%.0f", x)
          : String.format(Locale.ROOT, "%.2f", x).replaceAll("0$", "");
    }
  }
}
