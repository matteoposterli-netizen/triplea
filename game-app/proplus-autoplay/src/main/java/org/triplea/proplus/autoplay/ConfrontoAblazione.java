package org.triplea.proplus.autoplay;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.triplea.proplus.autoplay.ConfrontoHard.Stat;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step ABLAZIONE: what each Pro+ component is worth. Written only when some game turns Pro+
 * components off ({@code --ablazione}) and the reference "Hard / Hard" is there. For every
 * configuration with one Pro+ side: the side's VC at the end, TUV lost by both alliances, final
 * income, real landings and rounds in RALLENTAMENTO, the difference from Hard vs Hard and from the
 * same side with every component on ("Pro+ attuale", same plan), with a simple noise check; then
 * the components ranked from the most harmful to the most useful.
 */
final class ConfrontoAblazione {
  private ConfrontoAblazione() {}

  static void write(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final List<Integer> vcRounds) {
    final boolean anyAblation =
        games.stream()
            .filter(g -> g.json() != null)
            .anyMatch(
                g ->
                    alliances.stream()
                        .anyMatch(a -> !RiepilogoGenerale.ablation(g.json(), a).isEmpty()));
    if (!anyAblation) {
      return;
    }
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
    if (ref == null) {
      return;
    }
    final int last = vcRounds.get(vcRounds.size() - 1);
    final String labels =
        String.join(" / ", alliances.stream().map(Riepilogo::allianceLabel).toList());
    md.append("\n## Ablazione dei componenti Pro+ (riferimento: ")
        .append(reference)
        .append(")\n\n");
    md.append("Rumore (")
        .append(reference)
        .append(", ")
        .append(ref.size())
        .append(" partite): VC R")
        .append(last)
        .append(' ');
    md.append(
        String.join(
            ", ",
            alliances.stream()
                .map(
                    a -> {
                      final Stat s = Stat.of(ref, j -> ConfrontoHard.vc(j, a, last));
                      return Riepilogo.allianceLabel(a) + " " + s.meanAndRange() + " ds " + s.sd();
                    })
                .toList()));
    md.append(
        ". Una differenza e' \"rumore\" se |Δ| <= max(0,5, deviazione standard media dei due"
            + " gruppi); indice semplice, non un test statistico.\n\n");
    md.append("| Configurazione | Lato | Partite | VC R")
        .append(last)
        .append(" del lato (min-max), ds | Δ da Hard vs Hard | Δ da Pro+ attuale | TUV perso ")
        .append(labels)
        .append(" | Reddito finale del lato | Sbarchi veri del lato | Round in RALLENTAMENTO |\n")
        .append("|---|---|---|---|---|---|---|---|---|---|\n");
    final List<Effect> effects = new ArrayList<>();
    byConfig.forEach(
        (config, list) -> {
          final String side = ConfrontoHard.changedSide(list, alliances);
          if (side == null) {
            return;
          }
          final ToDoubleFunction<JsonObject> vcEnd = j -> ConfrontoHard.vc(j, side, last);
          final Stat vc = Stat.of(list, vcEnd);
          final Stat vcRef = Stat.of(ref, vcEnd);
          final String off = RiepilogoGenerale.ablation(list.get(0), side);
          final List<JsonObject> base = baseline(byConfig, list.get(0), side, alliances);
          String fromBase = "-";
          if (!off.isEmpty() && base != null) {
            final Stat vcBase = Stat.of(base, vcEnd);
            fromBase = vc.delta(vcBase) + " (" + noise(vc, vcBase) + ")";
            effects.add(new Effect(side, off, vcBase.meanValue() - vc.meanValue(), vc, vcBase));
          }
          final String hardVerdict = ConfrontoHard.verdict(vc, vcRef);
          md.append("| ")
              .append(config)
              .append(" | ")
              .append(Riepilogo.allianceLabel(side))
              .append(" | ")
              .append(list.size())
              .append(" | ")
              .append(vc.meanAndRange())
              .append(", ds ")
              .append(vc.sd())
              .append(" | ")
              .append(vc.delta(vcRef))
              .append(" (")
              .append(
                  "non distinguibile".equals(hardVerdict)
                      ? "rumore"
                      : hardVerdict.replace("piu' ", ""))
              .append(") | ")
              .append(fromBase)
              .append(" | ")
              .append(
                  String.join(
                      " / ",
                      alliances.stream()
                          .map(a -> Stat.of(list, j -> ConfrontoHard.lostTuv(j, a)).mean())
                          .toList()))
              .append(" | ")
              .append(Stat.of(list, j -> ConfrontoHard.finalSum(j, side, "reddito")).meanAndRange())
              .append(" | ")
              .append(Stat.of(list, j -> RiepilogoGenerale.landingsOf(j, side)).meanAndRange())
              .append(" | ")
              .append(Stat.of(list, j -> postureRounds(j, side, "RALLENTAMENTO")).meanAndRange())
              .append(" |\n");
          if (Ablazioni.NUDO.equals(off) && hardVerdict.startsWith("piu' ")) {
            md.append("| ⚠ ")
                .append(config)
                .append(": \"Pro+ nudo\" distinguibile da Hard (")
                .append(hardVerdict)
                .append("): possibile errore negli agganci |||||||||\n");
          }
        });
    if (effects.isEmpty()) {
      return;
    }
    md.append(
"""

Effetto dei componenti (VC del lato con il componente acceso − senza; negativo = il componente costa VC), dal piu' dannoso al piu' utile:

""");
    effects.sort(Comparator.comparingDouble(Effect::effect));
    for (final Effect e : effects) {
      md.append("- ")
          .append(Riepilogo.allianceLabel(e.side()))
          .append(", ")
          .append(Ablazioni.NUDO.equals(e.off()) ? "tutti i componenti (nudo)" : e.off())
          .append(": ")
          .append(String.format(Locale.ROOT, "%+.2f", e.effect()))
          .append(" VC ⇒ **")
          .append(componentVerdict(e))
          .append("**\n");
    }
  }

  /** The ids written by the game for every component off. */
  private static final class Ablazioni {
    static final String NUDO = games.strategy.triplea.ai.proplus.Ablazione.NUDO;
  }

  /**
   * @param effect VC of the side with the component on minus without it
   */
  record Effect(String side, String off, double effect, Stat without, Stat with) {}

  /** "costa", "aiuta" or "rumore" for the component's effect. */
  static String componentVerdict(final Effect e) {
    final String v = ConfrontoHard.verdict(e.with(), e.without());
    return switch (v) {
      case "piu' forte" -> "aiuta";
      case "piu' debole" -> "costa";
      case "non distinguibile" -> "rumore";
      default -> v;
    };
  }

  private static String noise(final Stat value, final Stat reference) {
    final String v = ConfrontoHard.verdict(value, reference);
    return "non distinguibile".equals(v) ? "rumore" : v.replace("piu' ", "");
  }

  /**
   * The games of the same side, type and plan with every component on ("Pro+ attuale"), the other
   * side unchanged; null if there are none.
   */
  private static List<JsonObject> baseline(
      final Map<String, List<JsonObject>> byConfig,
      final JsonObject game,
      final String side,
      final List<String> alliances) {
    final String plan = plan(game, side);
    final String kind = ConfrontoHard.kind(game, side);
    for (final List<JsonObject> list : byConfig.values()) {
      final JsonObject j = list.get(0);
      if (side.equals(ConfrontoHard.changedSide(list, alliances))
          && RiepilogoGenerale.ablation(j, side).isEmpty()
          && plan.equals(plan(j, side))
          && kind.equals(ConfrontoHard.kind(j, side))) {
        return list;
      }
    }
    return null;
  }

  private static String plan(final JsonObject json, final String side) {
    return RiepilogoGenerale.text(json.getAsJsonObject("parametri").getAsJsonObject("piani"), side);
  }

  /**
   * Rounds of {@code posture} for the alliance's general staff (0 without a strategist summary).
   */
  static double postureRounds(final JsonObject json, final String alliance, final String posture) {
    if (!json.has("stratega") || !json.getAsJsonObject("stratega").has(alliance)) {
      return 0;
    }
    final JsonObject s = json.getAsJsonObject("stratega").getAsJsonObject(alliance);
    return s.has("posture") && s.getAsJsonObject("posture").has(posture)
        ? s.getAsJsonObject("posture").get(posture).getAsDouble()
        : 0;
  }
}
