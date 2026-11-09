package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.triplea.ai.proplus.Ablazione;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import org.triplea.proplus.autoplay.ConfrontoConferma.Welch;
import org.triplea.proplus.autoplay.ConfrontoHard.Stat;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step PROVA DI VALORE: Pro+ "nudo" against Pro+ "nudo" + a hand-written joint operation, each on
 * one side against Hard. Written only when some game has a joint operation on. Per configuration:
 * VC at the end of the side with a 95% interval, final income, TUV lost, the check of the nudo
 * tuning against Hard; per side the effect of the operation (Welch) with an honest verdict; per
 * operation how often it started, finished, was cancelled or postponed, the losses of each step,
 * whether the target was taken and held after 1 and 2 rounds.
 */
final class ConfrontoProva {
  private ConfrontoProva() {}

  /** One game of the test: the Pro+ side, its operation ("" = none). */
  record Entry(String side, String operation, JsonObject json, boolean llm) {
    Entry(final String side, final String operation, final JsonObject json) {
      this(side, operation, json, false);
    }

    String key() {
      return Riepilogo.allianceLabel(side)
          + " Pro+ nudo"
          + (operation.isEmpty()
              ? ""
              : " + operazione " + operation + (llm ? " decisa da LLM" : ""));
    }
  }

  static void write(
      final StringBuilder md,
      final List<Game> games,
      final List<String> alliances,
      final List<Integer> vcRounds) {
    final List<Entry> entries = new ArrayList<>();
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      final JsonObject p = g.json().getAsJsonObject("parametri");
      final List<String> sides =
          alliances.stream()
              .filter(
                  a ->
                      !AutoplayOptions.HARD.equals(
                          RiepilogoGenerale.text(p.getAsJsonObject("tipi"), a)))
              .toList();
      if (sides.size() != 1) {
        continue;
      }
      final String side = sides.get(0);
      if (!Ablazione.NUDO.equals(RiepilogoGenerale.ablation(g.json(), side))) {
        continue;
      }
      final String op =
          p.has("operazione") && p.getAsJsonObject("operazione").has(side)
              ? p.getAsJsonObject("operazione").get(side).getAsString()
              : "";
      // step PROVALLM: an LLM at the operation's decision points
      final boolean llm =
          !op.isEmpty()
              && p.has("operazione_decisore")
              && p.getAsJsonObject("operazione_decisore").has(side);
      entries.add(new Entry(side, op, g.json(), llm));
    }
    if (entries.stream().noneMatch(e -> !e.operation().isEmpty())) {
      return;
    }
    final int last = vcRounds.get(vcRounds.size() - 1);
    final Map<String, List<Entry>> groups = new TreeMap<>();
    entries.forEach(e -> groups.computeIfAbsent(e.key(), k -> new ArrayList<>()).add(e));

    md.append(
"""

## Prova di valore: operazioni congiunte (Pro+ nudo contro nudo + operazione)

""");
    md.append(
        "Ogni configurazione: un lato Pro+ \"nudo\" (tutti i componenti spenti), con o senza"
            + " l'operazione congiunta scritta a mano, contro Hard. Media ± deviazione standard"
            + " [errore standard] sui semi; IC 95% con il t di Student (gruppo) e di Welch"
            + " (differenze). Controllo nudo = risposte del tuning Pro+ diverse da quelle di Hard"
            + " FUORI dai punti in cui interviene l'operazione (atteso 0).\n\n");
    md.append("| Configurazione | Partite | VC R")
        .append(last)
        .append(
"""
 del lato | IC 95% | Reddito finale | TUV perso | Controllo nudo: differenze (domande; risposte dell'operazione) |
|---|---|---|---|---|---|---|
""");
    groups.forEach(
        (key, list) -> {
          final String side = list.get(0).side();
          final List<JsonObject> js = list.stream().map(Entry::json).toList();
          final Stat vc = Stat.of(js, j -> ConfrontoHard.vc(j, side, last));
          final long[] check = check(js);
          md.append("| ")
              .append(key)
              .append(" | ")
              .append(js.size())
              .append(" | ")
              .append(ConfrontoConferma.text(vc))
              .append(" | ")
              .append(ConfrontoVarianza.interval(vc))
              .append(" | ")
              .append(
                  ConfrontoConferma.text(
                      Stat.of(js, j -> ConfrontoHard.finalSum(j, side, "reddito"))))
              .append(" | ")
              .append(ConfrontoConferma.text(Stat.of(js, j -> ConfrontoHard.lostTuv(j, side))))
              .append(" | ")
              .append(check[0] == 0 ? "**0**" : "⚠ **" + check[0] + "**")
              .append(" (")
              .append(check[1])
              .append("; ")
              .append(check[2])
              .append(") |\n");
        });

    // effect of each operation against nudo on the same side
    md.append(
"""

### Effetto dell'operazione (stessa run, stessi semi)

""");
    md.append(
        "| Lato | Operazione | Δ VC R"
            + last
            + " (operazione − nudo) | IC 95% Welch | Δ reddito finale [IC] | Δ TUV perso [IC] |"
            +
"""
 Semi per 80% di potenza | Verdetto |
|---|---|---|---|---|---|---|---|
""");
    final Map<String, List<Entry>> bySide = new LinkedHashMap<>();
    entries.forEach(e -> bySide.computeIfAbsent(e.side(), k -> new ArrayList<>()).add(e));
    bySide.forEach(
        (side, list) -> {
          final List<JsonObject> nudo =
              list.stream().filter(e -> e.operation().isEmpty()).map(Entry::json).toList();
          final Map<String, List<JsonObject>> byOp = new TreeMap<>();
          list.stream()
              .filter(e -> !e.operation().isEmpty())
              .forEach(
                  e ->
                      byOp.computeIfAbsent(
                              e.operation() + (e.llm() ? " (decisa da LLM)" : ""),
                              k -> new ArrayList<>())
                          .add(e.json()));
          byOp.forEach(
              (op, withOp) -> {
                final ToDoubleFunction<JsonObject> vc = j -> ConfrontoHard.vc(j, side, last);
                final Welch w = Welch.of(Stat.of(withOp, vc), Stat.of(nudo, vc));
                final Welch income =
                    Welch.of(
                        Stat.of(withOp, j -> ConfrontoHard.finalSum(j, side, "reddito")),
                        Stat.of(nudo, j -> ConfrontoHard.finalSum(j, side, "reddito")));
                final Welch lost =
                    Welch.of(
                        Stat.of(withOp, j -> ConfrontoHard.lostTuv(j, side)),
                        Stat.of(nudo, j -> ConfrontoHard.lostTuv(j, side)));
                md.append("| ")
                    .append(Riepilogo.allianceLabel(side))
                    .append(" | ")
                    .append(op)
                    .append(" | ")
                    .append(w.deltaText())
                    .append(" | ")
                    .append(w.intervalText())
                    .append(" | ")
                    .append(income.deltaText())
                    .append(' ')
                    .append(shortInterval(income))
                    .append(" | ")
                    .append(lost.deltaText())
                    .append(' ')
                    .append(shortInterval(lost))
                    .append(" | ")
                    .append(w.seedsText())
                    .append(" | ")
                    .append(verdict(w))
                    .append(" |\n");
              });
        });
    md.append(
"""

Verdetto: **vale** = Δ ≥ 1 VC e IC che esclude lo 0; **promettente** = Δ > 0 e IC che arriva a +1 (non escluso un effetto di 1 VC); **non vale** = altrimenti (Δ ≤ 0, o IC tutto sotto +1).
""");

    // the operations themselves
    md.append(
"""

### Andamento delle operazioni

""");
    md.append(
"""
| Configurazione | Partite | Avviate (tappa 1 eseguita) | Completate | Annullate | Con rinvii (rinvii totali) | Attese (bersaglio gia' dell'alleanza) | Tappa 2 approvata / attacco fatto | TUV perso tappa 1 | TUV perso tappa 2 | Bersaglio preso | Tenuto dopo 1 round | Tenuto dopo 2 round | Round obiettivo iniziale |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    final Map<String, Map<String, Integer>> reasons = new TreeMap<>();
    groups.forEach(
        (key, list) -> {
          final String op = list.get(0).operation();
          if (op.isEmpty()) {
            return;
          }
          int started = 0;
          int done = 0;
          int cancelled = 0;
          int withDelays = 0;
          int delays = 0;
          int waits = 0;
          int approved = 0;
          int attacked = 0;
          int taken = 0;
          int held1 = 0;
          int held2 = 0;
          final List<Double> lost1 = new ArrayList<>();
          final List<Double> lost2 = new ArrayList<>();
          final Map<Integer, Integer> targetRounds = new TreeMap<>();
          for (final Entry e : list) {
            final JsonObject all = e.json().getAsJsonObject("operazioni_congiunte");
            if (all == null || !all.has(op) || !all.get(op).isJsonObject()) {
              continue;
            }
            final JsonObject s = all.getAsJsonObject(op);
            final String state = s.has("stato") ? s.get("stato").getAsString() : "";
            if (flag(s, "avviata")) {
              started++;
            }
            if ("completata".equals(state)) {
              done++;
            }
            if ("annullata".equals(state)) {
              cancelled++;
              reasons
                  .computeIfAbsent(key, k -> new TreeMap<>())
                  .merge(
                      "annullata: "
                          + reason(s.has("motivo_fine") ? s.get("motivo_fine").getAsString() : "?"),
                      1,
                      Integer::sum);
            }
            final int d = s.has("rinvii") ? s.get("rinvii").getAsInt() : 0;
            delays += d;
            if (d > 0) {
              withDelays++;
            }
            waits += s.has("attese") ? s.get("attese").getAsInt() : 0;
            if (s.has("round_obiettivo_iniziale")) {
              targetRounds.merge(s.get("round_obiettivo_iniziale").getAsInt(), 1, Integer::sum);
            }
            for (final JsonElement ev : events(s)) {
              final JsonObject x = ev.getAsJsonObject();
              final String type = x.get("tipo").getAsString();
              if ("tappa2".equals(type) && flag(x, "eseguita")) {
                approved++;
              }
              if (("tappa1".equals(type) || "tappa2".equals(type))
                  && x.has("eseguita")
                  && !x.get("eseguita").getAsBoolean()
                  && x.has("motivo")) {
                reasons
                    .computeIfAbsent(key, k -> new TreeMap<>())
                    .merge(
                        type.replace("tappa", "tappa ")
                            + " non eseguita: "
                            + reason(x.get("motivo").getAsString()),
                        1,
                        Integer::sum);
              }
            }
            if (s.has("tappa2_tuv_perso")) {
              attacked++;
              lost2.add(s.get("tappa2_tuv_perso").getAsDouble());
            }
            if (s.has("tappa1_tuv_perso")) {
              lost1.add(s.get("tappa1_tuv_perso").getAsDouble());
            }
            if (flag(s, "presa")) {
              taken++;
              if (flag(s, "tenuto_dopo_1")) {
                held1++;
              }
              if (flag(s, "tenuto_dopo_2")) {
                held2++;
              }
            }
          }
          final int n = list.size();
          md.append("| ")
              .append(key)
              .append(" | ")
              .append(n)
              .append(" | ")
              .append(share(started, n))
              .append(" | ")
              .append(share(done, n))
              .append(" | ")
              .append(share(cancelled, n))
              .append(" | ")
              .append(withDelays)
              .append(" (")
              .append(delays)
              .append(") | ")
              .append(waits)
              .append(" | ")
              .append(approved)
              .append(" / ")
              .append(attacked)
              .append(" | ")
              .append(mean(lost1))
              .append(" | ")
              .append(mean(lost2))
              .append(" | ")
              .append(share(taken, n))
              .append(" | ")
              .append(taken == 0 ? "-" : held1 + "/" + taken)
              .append(" | ")
              .append(taken == 0 ? "-" : held2 + "/" + taken)
              .append(" | ")
              .append(rounds(targetRounds))
              .append(" |\n");
        });
    if (!reasons.isEmpty()) {
      md.append(
"""

Motivi (tappe non eseguite, annullamenti), partite o tappe per motivo:

""");
      reasons.forEach(
          (key, map) -> {
            md.append("- ").append(key).append(": ");
            final List<String> parts = new ArrayList<>();
            map.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(8)
                .forEach(e -> parts.add(e.getKey() + " (" + e.getValue() + ")"));
            md.append(String.join("; ", parts)).append('\n');
          });
    }

    // the target at the end of the game, with and without the operation
    md.append(
"""

### Bersaglio dell'operazione a fine partita (con e senza operazione)

""");
    md.append("| Configurazione | Bersaglio | Dell'alleanza al R")
        .append(last)
        .append(
"""
 | Round medi in mano all'alleanza |
|---|---|---|---|
""");
    final Set<String> ops =
        new java.util.TreeSet<>(
            entries.stream().map(Entry::operation).filter(o -> !o.isEmpty()).toList());
    groups.forEach(
        (key, list) -> {
          final String side = list.get(0).side();
          for (final String op : ops) {
            final var def = OperazioneCongiunta.definizione(op);
            if (def.isEmpty() || !def.get().alleanza().equals(side)) {
              continue;
            }
            int heldAtEnd = 0;
            final List<Double> roundsHeld = new ArrayList<>();
            for (final Entry e : list) {
              final Set<String> members = members(e.json(), side);
              int heldRounds = 0;
              String lastOwner = "";
              for (final JsonElement r : e.json().getAsJsonArray("round")) {
                final JsonObject t = r.getAsJsonObject().getAsJsonObject("bersagli_operazioni");
                if (t != null && t.has(op)) {
                  lastOwner = t.get(op).getAsString();
                  if (members.contains(lastOwner)) {
                    heldRounds++;
                  }
                }
              }
              if (members.contains(lastOwner)) {
                heldAtEnd++;
              }
              roundsHeld.add((double) heldRounds);
            }
            md.append("| ")
                .append(key)
                .append(" | ")
                .append(def.get().bersaglio())
                .append(" | ")
                .append(share(heldAtEnd, list.size()))
                .append(" | ")
                .append(mean(roundsHeld))
                .append(" |\n");
          }
        });

    // check of the nudo tuning: rounds with differences
    final List<String> problems = new ArrayList<>();
    groups.forEach(
        (key, list) -> {
          for (final Entry e : list) {
            final JsonObject c = e.json().getAsJsonObject("controllo_nudo");
            if (c == null) {
              continue;
            }
            for (final Map.Entry<String, JsonElement> nation : c.entrySet()) {
              for (final Map.Entry<String, JsonElement> r :
                  nation.getValue().getAsJsonObject().entrySet()) {
                final JsonObject o = r.getValue().getAsJsonObject();
                if (o.get("differenze").getAsInt() > 0 && problems.size() < 12) {
                  problems.add(
                      key
                          + ", "
                          + e.json().get("nome").getAsString()
                          + ", "
                          + nation.getKey()
                          + " "
                          + r.getKey()
                          + ": "
                          + o.get("differenze").getAsInt()
                          + " ("
                          + o.getAsJsonArray("esempi")
                          + ")");
                }
              }
            }
          }
        });
    md.append(
"""

### Controllo nudo contro Hard

""");
    if (problems.isEmpty()) {
      md.append(
          "Nessuna risposta del tuning nudo diversa da Hard fuori dai punti dell'operazione, in"
              + " nessun round di nessuna partita.\n");
    } else {
      md.append("⚠ Differenze fuori dall'operazione (primo problema da guardare):\n\n");
      problems.forEach(p -> md.append("- ").append(p).append('\n'));
    }
    // step PROVA2: the take-and-hold variant
    ConfrontoTenuta.write(md, entries, alliances, last);
    ConfrontoLlm.write(md, entries);
  }

  /** {differences out of the operation, questions, operation's answers} over the games. */
  static long[] check(final List<JsonObject> games) {
    final long[] sum = new long[3];
    for (final JsonObject j : games) {
      final JsonObject c = j.getAsJsonObject("controllo_nudo");
      if (c == null) {
        continue;
      }
      for (final Map.Entry<String, JsonElement> nation : c.entrySet()) {
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

  static String verdict(final Welch w) {
    if (!w.known()) {
      return "pochi dati";
    }
    if (w.delta() >= 1 && w.low() > 0) {
      return "**vale**";
    }
    if (w.delta() > 0 && w.high() >= 1) {
      return "promettente";
    }
    return "non vale";
  }

  private static String shortInterval(final Welch w) {
    if (!w.known()) {
      return "";
    }
    return String.format(Locale.ROOT, "[%.1f, %.1f]", w.low(), w.high());
  }

  private static Set<String> members(final JsonObject json, final String alliance) {
    final Set<String> set = new java.util.HashSet<>();
    final JsonObject a = json.getAsJsonObject("alleanze");
    if (a != null && a.has(alliance)) {
      a.getAsJsonArray(alliance).forEach(e -> set.add(e.getAsString()));
    }
    return set;
  }

  private static JsonArray events(final JsonObject state) {
    return state.has("eventi") ? state.getAsJsonArray("eventi") : new JsonArray();
  }

  private static boolean flag(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
  }

  /** A reason without its numbers (so equal reasons count together). */
  static String reason(final String text) {
    return text.replaceAll("-?\\d+(\\.\\d+)?", "N").replaceAll("\\s+", " ").trim();
  }

  private static String share(final int k, final int n) {
    return n == 0 ? "-" : k + "/" + n + " (" + Math.round(100.0 * k / n) + "%)";
  }

  private static String mean(final List<Double> values) {
    if (values.isEmpty()) {
      return "-";
    }
    return String.format(
        Locale.ROOT,
        "%.1f (n %d)",
        values.stream().mapToDouble(Double::doubleValue).average().orElse(0),
        values.size());
  }

  private static String rounds(final Map<Integer, Integer> counts) {
    if (counts.isEmpty()) {
      return "-";
    }
    final List<String> parts = new ArrayList<>();
    counts.forEach((r, n) -> parts.add("R" + r + " x" + n));
    return String.join(", ", parts);
  }
}
