package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Definizione;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.triplea.proplus.autoplay.ConfrontoConferma.Welch;
import org.triplea.proplus.autoplay.ConfrontoHard.Stat;
import org.triplea.proplus.autoplay.ConfrontoProva.Entry;

/**
 * Step PROVA2: the "take and hold" variant of the joint operations. Written after the "Prova di
 * valore" section when an operation of the games has a hold ({@link Definizione#tenuta()}): who
 * holds the targets round by round with and without the operation (difference of proportions with a
 * 95% interval), how the holds ended, the operations postponed for the expected counterattack, the
 * steps on the final target, the TUV lost by both alliances and the calibration of the estimated
 * hold against the real one.
 */
final class ConfrontoTenuta {
  private ConfrontoTenuta() {}

  /** Rounds at which the targets are read: a single target, a bridgehead with a final target. */
  static List<Integer> rounds(final Definizione def, final int last) {
    final List<Integer> wanted =
        def.bersaglioFinale().isEmpty() ? List.of(3, 6, 9, 12) : List.of(8, 10, 12);
    final List<Integer> rounds = new ArrayList<>(wanted.stream().filter(r -> r < last).toList());
    rounds.add(last);
    return rounds.stream().distinct().toList();
  }

  static void write(
      final StringBuilder md,
      final List<Entry> entries,
      final List<String> alliances,
      final int last) {
    final Map<String, Definizione> ops = new TreeMap<>();
    entries.stream()
        .map(Entry::operation)
        .filter(o -> !o.isEmpty())
        .distinct()
        .forEach(
            o ->
                OperazioneCongiunta.definizione(o)
                    .filter(d -> d.tenuta().attiva())
                    .ifPresent(d -> ops.put(o, d)));
    if (ops.isEmpty()) {
      return;
    }
    md.append(
"""

## Presa e tenuta (variante v2 delle operazioni congiunte)

""");
    md.append(
        "Lancio solo con valore d'alleanza positivo contando il contrattacco atteso (unita' nemiche"
            + " in portata + acquisti possibili alle fabbriche vicine), poi tenuta con presidio"
            + " bloccato, rinforzi e copertura aerea. Proporzioni di partite; Δ = operazione −"
            + " nudo con IC 95% (approssimazione normale della differenza di due proporzioni).\n");

    ops.forEach(
        (op, def) -> {
          final String side = def.alleanza();
          final List<JsonObject> nudo = games(entries, side, "");
          final List<JsonObject> withOp = games(entries, side, op);
          if (withOp.isEmpty()) {
            return;
          }
          md.append("\n### ")
              .append(def.nome())
              .append(" (")
              .append(Riepilogo.allianceLabel(side))
              .append(", ")
              .append(withOp.size())
              .append(" partite con l'operazione, ")
              .append(nudo.size())
              .append(" nudo)\n\n");
          // the targets round by round
          final List<String> territories = new ArrayList<>();
          territories.add(def.bersaglio());
          if (!def.bersaglioFinale().isEmpty()) {
            territories.add(def.bersaglioFinale());
          }
          final List<Integer> rounds = rounds(def, last);
          md.append("| Territorio | Configurazione |");
          rounds.forEach(r -> md.append(" R").append(r).append(" |"));
          md.append(" Δ R").append(last).append(" [IC 95%] |\n|---|---|");
          rounds.forEach(r -> md.append("---|"));
          md.append("---|\n");
          for (final String t : territories) {
            for (final boolean on : new boolean[] {false, true}) {
              final List<JsonObject> js = on ? withOp : nudo;
              md.append("| ")
                  .append(t)
                  .append(" | ")
                  .append(on ? "nudo + " + op : "nudo")
                  .append(" |");
              for (final int r : rounds) {
                md.append(' ').append(share(heldCount(js, side, t, r), js.size())).append(" |");
              }
              md.append(' ')
                  .append(
                      on
                          ? proportionDelta(
                              heldCount(withOp, side, t, last),
                              withOp.size(),
                              heldCount(nudo, side, t, last),
                              nudo.size())
                          : "")
                  .append(" |\n");
            }
          }
          outcomes(md, op, def, withOp, side, last);
          lostTuv(md, alliances, nudo, withOp);
        });
    calibration(md, entries, ops);
  }

  /** How the operations ended, the postponements for the counterattack, the final target. */
  private static void outcomes(
      final StringBuilder md,
      final String op,
      final Definizione def,
      final List<JsonObject> games,
      final String side,
      final int last) {
    int holds = 0;
    int completed = 0;
    int lost = 0;
    int notTaken = 0;
    int cancelled = 0;
    int stillHeld = 0;
    int takenAlready = 0;
    int counterGames = 0;
    int counterDelays = 0;
    int counterCancelled = 0;
    int retreats = 0;
    int bridgeheads = 0;
    int finalEvaluated = 0;
    int finalLaunched = 0;
    int finalApproved = 0;
    int finalTaken = 0;
    int finalHeldAtEnd = 0;
    final List<Double> heldRounds = new ArrayList<>();
    final Map<String, Integer> reasons = new TreeMap<>();
    for (final JsonObject g : games) {
      final JsonObject s = state(g, op);
      if (s == null) {
        continue;
      }
      final String stato = text(s, "stato");
      if (intOf(s, "tenute") > 0) {
        holds++;
      }
      if (flag(s, "presa_senza_operazione")) {
        takenAlready++;
      }
      if ("completata".equals(text(s, "tenuta_esito"))) {
        completed++;
      } else if ("fallita".equals(text(s, "tenuta_esito"))) {
        lost++;
        if (s.has("tenuta_persa_round") && s.has("tenuta_dal")) {
          heldRounds.add((double) (intOf(s, "tenuta_persa_round") - intOf(s, "tenuta_dal")));
        }
      } else if ("fallita".equals(stato)) {
        notTaken++;
      }
      if ("annullata".equals(stato)) {
        cancelled++;
        reasons.merge(
            "annullata: " + ConfrontoProva.reason(text(s, "motivo_fine")), 1, Integer::sum);
      }
      if ("tenuta".equals(stato)) {
        stillHeld++;
      }
      retreats += intOf(s, "ritirate");
      if (flag(s, "testa_di_ponte_tenuta")) {
        bridgeheads++;
      }
      finalEvaluated += intOf(s, "finale_tentativi_valutati");
      finalLaunched += intOf(s, "finale_avvii");
      if (flag(s, "finale_presa")) {
        finalTaken++;
        if (heldAt(g, side, def.bersaglioFinale(), last)) {
          finalHeldAtEnd++;
        }
      }
      int delays = 0;
      for (final JsonElement ev : events(s)) {
        final JsonObject x = ev.getAsJsonObject();
        final String type = text(x, "tipo");
        final String why = text(x, "motivo");
        if ("tappa2".equals(type) && flag(x, "eseguita") && "finale".equals(text(x, "fase"))) {
          finalApproved++;
        }
        if (why.contains("contrattacco")) {
          if ("rinvio".equals(type) || "rinvio_finale".equals(type)) {
            delays++;
          }
          if ("annullata".equals(type)) {
            counterCancelled++;
          }
        }
        if (("tappa1".equals(type) || "tappa2".equals(type))
            && x.has("eseguita")
            && !x.get("eseguita").getAsBoolean()
            && !why.isEmpty()) {
          reasons.merge(
              ("finale".equals(text(x, "fase")) ? "su " + def.bersaglioFinale() + ", " : "")
                  + type.replace("tappa", "tappa ")
                  + " non eseguita: "
                  + ConfrontoProva.reason(why),
              1,
              Integer::sum);
        }
      }
      if (delays > 0) {
        counterGames++;
        counterDelays += delays;
      }
    }
    final int n = games.size();
    md.append(
"""

| Esito | Partite |
|---|---|
""");
    md.append("| Tenuta iniziata (bersaglio preso) | ").append(share(holds, n)).append(" |\n");
    md.append("| ... di cui bersaglio gia' dell'alleanza (preso senza operazione) | ")
        .append(takenAlready)
        .append(" |\n");
    md.append("| Tenuta COMPLETATA (")
        .append(def.tenuta().round())
        .append(" round")
        .append(def.bersaglioFinale().isEmpty() ? "" : ", anche su " + def.bersaglioFinale())
        .append(") | ")
        .append(share(completed, n))
        .append(" |\n");
    md.append("| Tenuta FALLITA (bersaglio perso; round tenuti in media ")
        .append(mean(heldRounds))
        .append(") | ")
        .append(share(lost, n))
        .append(" |\n");
    md.append("| FALLITA senza presa (tappa 2 eseguita, bersaglio non preso) | ")
        .append(share(notTaken, n))
        .append(" |\n");
    md.append("| Ancora in tenuta a fine partita | ").append(share(stillHeld, n)).append(" |\n");
    md.append("| ANNULLATA (rinvii finiti o round massimo) | ")
        .append(share(cancelled, n))
        .append(" |\n");
    md.append("| Rinviata per contrattacco atteso: partite (rinvii); annullate per contrattacco | ")
        .append(counterGames)
        .append(" (")
        .append(counterDelays)
        .append("); ")
        .append(counterCancelled)
        .append(" |\n");
    md.append("| Presidio lasciato a Hard (tenuta persa per la stima): volte | ")
        .append(retreats)
        .append(" |\n");
    if (!def.bersaglioFinale().isEmpty()) {
      md.append("| Testa di ponte ")
          .append(def.bersaglio())
          .append(" tenuta ")
          .append(def.tenuta().round())
          .append(" round | ")
          .append(share(bridgeheads, n))
          .append(" |\n");
      md.append("| Tappe su ")
          .append(def.bersaglioFinale())
          .append(": valutate / tappa 1 eseguita / tappa 2 approvata | ")
          .append(finalEvaluated)
          .append(" / ")
          .append(finalLaunched)
          .append(" / ")
          .append(finalApproved)
          .append(" |\n");
      md.append("| ")
          .append(def.bersaglioFinale())
          .append(" presa dall'operazione; tenuta fino al R")
          .append(last)
          .append(" | ")
          .append(share(finalTaken, n))
          .append("; ")
          .append(finalTaken == 0 ? "-" : finalHeldAtEnd + "/" + finalTaken)
          .append(" |\n");
    }
    if (!reasons.isEmpty()) {
      md.append("\nMotivi (tappe non eseguite, annullamenti): ");
      final List<String> parts = new ArrayList<>();
      reasons.entrySet().stream()
          .sorted((a, b) -> b.getValue() - a.getValue())
          .limit(8)
          .forEach(e -> parts.add(e.getKey() + " (" + e.getValue() + ")"));
      md.append(String.join("; ", parts)).append('\n');
    }
  }

  /** TUV lost by every alliance, with and without the operation. */
  private static void lostTuv(
      final StringBuilder md,
      final List<String> alliances,
      final List<JsonObject> nudo,
      final List<JsonObject> withOp) {
    md.append(
"""

| Alleanza | TUV perso nudo | TUV perso con l'operazione | Δ [IC 95% Welch] |
|---|---|---|---|
""");
    for (final String a : alliances) {
      final Stat n = Stat.of(nudo, j -> ConfrontoHard.lostTuv(j, a));
      final Stat o = Stat.of(withOp, j -> ConfrontoHard.lostTuv(j, a));
      final Welch w = Welch.of(o, n);
      md.append("| ")
          .append(Riepilogo.allianceLabel(a))
          .append(" | ")
          .append(ConfrontoConferma.text(n))
          .append(" | ")
          .append(ConfrontoConferma.text(o))
          .append(" | ")
          .append(w.deltaText())
          .append(' ')
          .append(w.intervalText())
          .append(" |\n");
    }
  }

  /**
   * Estimated hold against the real one, by bands of 20%: at the launch of a step (the target taken
   * in that round; real = still the alliance's {@code tenuta.round} rounds later) and at every
   * non-combat move of the hold (real = still the alliance's at the end of the next round, after a
   * turn of every enemy).
   */
  private static void calibration(
      final StringBuilder md, final List<Entry> entries, final Map<String, Definizione> ops) {
    final double[][] launch = new double[5][3];
    final double[][] turn = new double[5][3];
    for (final Entry e : entries) {
      final Definizione def = ops.get(e.operation());
      if (def == null) {
        continue;
      }
      final JsonObject s = state(e.json(), e.operation());
      if (s == null) {
        continue;
      }
      final int played =
          e.json().has("round_giocati") ? e.json().get("round_giocati").getAsInt() : 0;
      for (final JsonElement ev : events(s)) {
        final JsonObject x = ev.getAsJsonObject();
        if (!x.has("tenuta_stimata")) {
          continue;
        }
        final String type = text(x, "tipo");
        final int r = intOf(x, "round");
        final double p = x.get("tenuta_stimata").getAsDouble();
        final boolean finale = "finale".equals(text(x, "fase"));
        if ("tappa1".equals(type) && flag(x, "eseguita")) {
          final String prefix = finale ? "finale_" : "";
          final String t = finale ? def.bersaglioFinale() : def.bersaglio();
          if (intOf(s, prefix + "round_presa") != r || flag(s, prefix + "presa_senza_operazione")) {
            continue;
          }
          final int check = r + def.tenuta().round();
          if (check > played) {
            continue;
          }
          add(launch, p, heldAt(e.json(), e.side(), t, check));
        } else if ("stima_tenuta".equals(type)) {
          final String t = text(x, "bersaglio");
          if (r + 1 > played) {
            continue;
          }
          add(turn, p, heldAt(e.json(), e.side(), t, r + 1));
        }
      }
    }
    md.append(
"""

### Calibrazione: tenuta stimata contro tenuta reale

Al lancio (tappa 1 eseguita e bersaglio preso nello stesso round; reale = ancora dell'alleanza dopo i round di tenuta) e a ogni stima della tenuta nel non combattimento (reale = ancora dell'alleanza a fine del round dopo, cioe' dopo un turno di ogni nemico).

| Fascia stimata | Al lancio: stime | media stimata | tenuta reale | Durante la tenuta: stime | media stimata | tenuta reale |
|---|---|---|---|---|---|---|
""");
    for (int b = 0; b < 5; b++) {
      md.append("| ").append(b * 20).append('-').append(b == 4 ? 100 : b * 20 + 19).append("% | ");
      band(md, launch[b]);
      md.append(" | ");
      band(md, turn[b]);
      md.append(" |\n");
    }
  }

  private static void add(final double[][] bands, final double p, final boolean held) {
    final int b = Math.max(0, Math.min(4, (int) Math.floor(p / 20)));
    bands[b][0]++;
    bands[b][1] += p;
    bands[b][2] += held ? 1 : 0;
  }

  private static void band(final StringBuilder md, final double[] b) {
    if (b[0] == 0) {
      md.append("0 | - | -");
      return;
    }
    md.append((int) b[0])
        .append(" | ")
        .append(String.format(Locale.ROOT, "%.0f%%", b[1] / b[0]))
        .append(" | ")
        .append(String.format(Locale.ROOT, "%.0f%%", 100 * b[2] / b[0]));
  }

  // ------------------------------------------------------------------------------------ helpers

  private static List<JsonObject> games(
      final List<Entry> entries, final String side, final String op) {
    return entries.stream()
        .filter(e -> e.side().equals(side) && e.operation().equals(op))
        .map(Entry::json)
        .toList();
  }

  /** Games where {@code territory} is the alliance's at the end of {@code round}. */
  static int heldCount(
      final List<JsonObject> games, final String side, final String territory, final int round) {
    return (int) games.stream().filter(g -> heldAt(g, side, territory, round)).count();
  }

  /** Whether {@code territory} is {@code side}'s at the end of {@code round} (or the last one). */
  static boolean heldAt(
      final JsonObject game, final String side, final String territory, final int round) {
    final Set<String> members = members(game, side);
    String owner = "";
    for (final JsonElement r : game.getAsJsonArray("round")) {
      final JsonObject o = r.getAsJsonObject();
      if (o.get("round").getAsInt() > round) {
        break;
      }
      final JsonObject t = o.getAsJsonObject("territori_operazioni");
      if (t != null && t.has(territory)) {
        owner = t.get(territory).getAsString();
      }
    }
    return members.contains(owner);
  }

  /** "k/n (p%)" and, for the operation, the difference with nudo and its 95% interval. */
  static String proportionDelta(final int k1, final int n1, final int k2, final int n2) {
    if (n1 == 0 || n2 == 0) {
      return "-";
    }
    final double p1 = (double) k1 / n1;
    final double p2 = (double) k2 / n2;
    final double se = Math.sqrt(p1 * (1 - p1) / n1 + p2 * (1 - p2) / n2);
    final double d = 100 * (p1 - p2);
    return String.format(Locale.ROOT, "%+.0f punti [%+.0f, %+.0f]", d, d - 196 * se, d + 196 * se);
  }

  private static Set<String> members(final JsonObject json, final String alliance) {
    final Set<String> set = new HashSet<>();
    final JsonObject a = json.getAsJsonObject("alleanze");
    if (a != null && a.has(alliance)) {
      a.getAsJsonArray(alliance).forEach(e -> set.add(e.getAsString()));
    }
    return set;
  }

  private static JsonObject state(final JsonObject game, final String op) {
    final JsonObject all = game.getAsJsonObject("operazioni_congiunte");
    return all == null || !all.has(op) || !all.get(op).isJsonObject()
        ? null
        : all.getAsJsonObject(op);
  }

  private static JsonArray events(final JsonObject state) {
    return state.has("eventi") ? state.getAsJsonArray("eventi") : new JsonArray();
  }

  private static String text(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
  }

  private static int intOf(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
  }

  private static boolean flag(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
  }

  private static String share(final int k, final int n) {
    return n == 0 ? "-" : k + "/" + n + " (" + Math.round(100.0 * k / n) + "%)";
  }

  private static String mean(final List<Double> values) {
    return values.isEmpty()
        ? "-"
        : String.format(
            Locale.ROOT,
            "%.1f",
            values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
  }
}
