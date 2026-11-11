package org.triplea.proplus.autoplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step PARTITA COMPLETA: games played until an alliance wins (--fino-a-vittoria). Per game: winner
 * or "stallo", round of the victory, duration, seconds per round in the bands R1-R12, R13-R24,
 * R25-R36 and R37+, VC every 4 rounds, income and army (TUV) per nation every 4 rounds, fallen
 * capitals with their round; at the end the CI time of a run of 60 complete games. Written only
 * when a game has {@code parametri.fino_a_vittoria}.
 */
final class ConfrontoCompleta {
  private ConfrontoCompleta() {}

  /** Bands of the seconds per round: first and last round (0 = no limit). */
  static final int[][] BANDS = {{1, 12}, {13, 24}, {25, 36}, {37, 0}};

  /** Run of 60 games, at most 20 jobs at a time (the free limit of concurrent jobs). */
  static final int RUN_GAMES = 60;

  static final int PARALLEL = 20;

  static void write(final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final List<Game> complete =
        games.stream()
            .filter(g -> g.json() != null)
            .filter(
                g -> {
                  final JsonObject p = g.json().getAsJsonObject("parametri");
                  return p.has("fino_a_vittoria") && p.get("fino_a_vittoria").getAsBoolean();
                })
            .toList();
    if (complete.isEmpty()) {
      return;
    }
    md.append(
"""

## Partite complete (fino alla vittoria)

""");
    md.append(
        "Vittoria = condizioni dei trigger di vittoria della mappa vere a fine round per "
            + Vittoria.ROUNDS_HELD
            + " round interi dopo il primo (Asse: 8 VC d'Europa e una capitale propria; Alleati:"
            + " Berlino e Roma e una capitale propria), controllate dall'autoplay: il motore non"
            + " chiude mai la partita su questa mappa (il messaggio di vittoria del trigger non e'"
            + " in notifications.properties). Stallo = tetto di round o minuti senza vincitore"
            + " (non un errore). s/round = somma dei passi del round (tutte le nazioni).\n\n");
    md.append(
        "| Partita | Esito | Round vittoria | Round giocati | Durata | Condizione vera (round) |");
    for (final int[] b : BANDS) {
      md.append(" s/round ").append(bandLabel(b)).append(" |");
    }
    md.append("\n|---|---|---|---|---|---|");
    for (final int[] ignored : BANDS) {
      md.append("---|");
    }
    md.append('\n');
    for (final Game g : complete) {
      final JsonObject j = g.json();
      final JsonObject outcome = j.getAsJsonObject("esito");
      md.append("| ")
          .append(g.folder())
          .append(" | ")
          .append(RiepilogoGenerale.text(outcome, "testo"))
          .append(" | ")
          .append(outcome.has("round_vittoria") ? outcome.get("round_vittoria").getAsString() : "-")
          .append(" | ")
          .append(j.get("round_giocati").getAsInt())
          .append(" | ")
          .append(Riepilogo.duration(j.get("durata_s").getAsLong()))
          .append(" | ")
          .append(conditionText(j, alliances))
          .append(" |");
      for (final int[] b : BANDS) {
        md.append(' ').append(bandMean(j, b)).append(" |");
      }
      md.append('\n');
    }

    for (final Game g : complete) {
      final JsonObject j = g.json();
      final List<Integer> rounds = everyFour(j);
      md.append("\n### ").append(g.folder()).append("\n\n");
      md.append("VC (")
          .append(String.join(" / ", alliances.stream().map(Riepilogo::allianceLabel).toList()))
          .append(") e, per nazione, reddito / TUV ogni 4 round:\n\n| Nazione |");
      rounds.forEach(r -> md.append(" R").append(r).append(" |"));
      md.append("\n|---|");
      rounds.forEach(r -> md.append("---|"));
      md.append("\n| **VC** |");
      for (final int r : rounds) {
        final JsonObject round = roundOf(j, r);
        md.append(' ')
            .append(
                String.join(
                    " / ",
                    alliances.stream()
                        .map(
                            a ->
                                round != null && round.getAsJsonObject("vc").has(a)
                                    ? round
                                        .getAsJsonObject("vc")
                                        .getAsJsonObject(a)
                                        .get("numero")
                                        .getAsString()
                                    : "-")
                        .toList()))
            .append(" |");
      }
      md.append('\n');
      for (final String nation : nations(j, alliances)) {
        md.append("| ").append(nation).append(" |");
        for (final int r : rounds) {
          final JsonObject round = roundOf(j, r);
          final JsonObject n =
              round == null ? null : round.getAsJsonObject("nazioni").getAsJsonObject(nation);
          md.append(' ')
              .append(n == null ? "-" : value(n, "reddito") + " / " + value(n, "tuv"))
              .append(" |");
        }
        md.append('\n');
      }
      md.append("\nCapitali cadute: ").append(capitals(j)).append('\n');
    }

    // CI time of a run of complete games: waves of PARALLEL jobs, each as long as its slowest game
    final List<Long> durations =
        complete.stream().map(g -> g.json().get("durata_s").getAsLong()).sorted().toList();
    final double mean = durations.stream().mapToLong(Long::longValue).average().orElse(0);
    final long max = durations.get(durations.size() - 1);
    final int waves = (RUN_GAMES + PARALLEL - 1) / PARALLEL;
    md.append("\nStima per una run di ")
        .append(RUN_GAMES)
        .append(" partite complete (")
        .append(PARALLEL)
        .append(" in parallelo, ")
        .append(waves)
        .append(" ondate, +2 min per job tra JDK e kit): durata media ")
        .append(Riepilogo.duration(Math.round(mean)))
        .append(", massima ")
        .append(Riepilogo.duration(max))
        .append(" ⇒ tempo di CI tra ")
        .append(minutes(waves * (mean + 120)))
        .append(" (media) e ")
        .append(minutes(waves * (max + 120)))
        .append(" (ondate lunghe come la piu' lenta); minuti di runner ")
        .append(minutes(RUN_GAMES * (mean + 120)))
        .append(".\n");
  }

  static String bandLabel(final int[] band) {
    return band[1] == 0 ? "R" + band[0] + "+" : "R" + band[0] + "-R" + band[1];
  }

  /** Mean seconds per round of the band, from tempi.per_round_s ("-" without rounds). */
  static String bandMean(final JsonObject json, final int[] band) {
    if (!json.has("tempi") || !json.getAsJsonObject("tempi").has("per_round_s")) {
      return "-";
    }
    final JsonObject perRound = json.getAsJsonObject("tempi").getAsJsonObject("per_round_s");
    double total = 0;
    int n = 0;
    for (final var e : perRound.entrySet()) {
      final int r = Integer.parseInt(e.getKey());
      if (r >= band[0] && (band[1] == 0 || r <= band[1]) && r <= lastPlayed(json)) {
        total += e.getValue().getAsDouble();
        n++;
      }
    }
    return n == 0 ? "-" : String.format(Locale.ROOT, "%.0f (%d)", total / n, n);
  }

  /** Last round in the summary (the round whose end decided the game). */
  private static int lastPlayed(final JsonObject json) {
    int last = 0;
    for (final JsonElement e : json.getAsJsonArray("round")) {
      last = Math.max(last, e.getAsJsonObject().get("round").getAsInt());
    }
    return last;
  }

  /** Rounds 4, 8, ... up to the last one played (always included). */
  static List<Integer> everyFour(final JsonObject json) {
    final int last = lastPlayed(json);
    final List<Integer> rounds = new ArrayList<>();
    for (int r = 4; r < last; r += 4) {
      rounds.add(r);
    }
    if (last > 0) {
      rounds.add(last);
    }
    return rounds;
  }

  private static JsonObject roundOf(final JsonObject json, final int round) {
    for (final JsonElement e : json.getAsJsonArray("round")) {
      if (e.getAsJsonObject().get("round").getAsInt() == round) {
        return e.getAsJsonObject();
      }
    }
    return null;
  }

  private static List<String> nations(final JsonObject json, final List<String> alliances) {
    final List<String> list = new ArrayList<>();
    for (final String a : alliances) {
      final var members = json.getAsJsonObject("alleanze").getAsJsonArray(a);
      if (members != null) {
        members.forEach(m -> list.add(m.getAsString()));
      }
    }
    return list;
  }

  private static String value(final JsonObject o, final String key) {
    return o.has(key) ? o.get(key).getAsString() : "-";
  }

  private static String conditionText(final JsonObject json, final List<String> alliances) {
    if (!json.has("vittoria")) {
      return "-";
    }
    final JsonObject met = json.getAsJsonObject("vittoria").getAsJsonObject("condizione");
    final List<String> parts = new ArrayList<>();
    for (final String a : alliances) {
      if (met.has(a) && !met.getAsJsonArray(a).isEmpty()) {
        final List<String> rounds = new ArrayList<>();
        met.getAsJsonArray(a).forEach(r -> rounds.add(r.getAsString()));
        parts.add(Riepilogo.allianceLabel(a) + " " + String.join(",", rounds));
      }
    }
    return parts.isEmpty() ? "mai" : String.join("; ", parts);
  }

  private static String capitals(final JsonObject json) {
    final List<String> parts = new ArrayList<>();
    if (json.has("capitali")) {
      for (final JsonElement e : json.getAsJsonArray("capitali")) {
        final JsonObject c = e.getAsJsonObject();
        parts.add(
            "R"
                + c.get("round").getAsInt()
                + " "
                + c.get("territorio").getAsString()
                + " "
                + c.get("evento").getAsString()
                + " (ora di "
                + c.get("proprietario").getAsString()
                + ")");
      }
    }
    return parts.isEmpty() ? "nessuna" : String.join("; ", parts);
  }

  private static String minutes(final double seconds) {
    final long m = Math.round(seconds / 60);
    return m >= 60 ? String.format(Locale.ROOT, "%d h %02d min", m / 60, m % 60) : m + " min";
  }
}
