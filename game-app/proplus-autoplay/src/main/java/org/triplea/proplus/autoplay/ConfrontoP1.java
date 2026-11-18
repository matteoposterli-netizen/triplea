package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P1: the block {@code p1} of each game's {@code riepilogo.json} (from the register {@code
 * impegno_decisioni} and the monitor's {@code p1} data) and the section "P1" of {@code
 * riepilogo-generale.md}: means per configuration of the success criteria of the step, the same
 * measures for the USA of Hard on the same seeds, the wins with the 95% interval and Fisher.
 */
final class ConfrontoP1 {
  private ConfrontoP1() {}

  static final String COMPITI = "[con compiti]";
  static final List<Integer> ROUNDS = List.of(8, 12, 16);
  static final Set<String> NAVI =
      Set.of("destroyer", "cruiser", "battleship", "carrier", "submarine");
  static final Set<String> AEREI = Set.of("fighter", "tactical_bomber", "bomber");
  static final Set<String> RISPOSTE_MARE =
      Set.of("ATTACCO_FLOTTA", "SCORTA", "ACQUISTO_MARE", "CAMBIO_BERSAGLIO");

  /** The capitals of the directions and their coastal neighbours (first landing "toward"). */
  static final Map<String, List<String>> VERSO =
      Map.of(
          "Southern Italy",
          List.of("Southern Italy", "Northern Italy"),
          // France: the capital and its coastal land neighbours
          "France",
          List.of(
              "France",
              "Normandy Bordeaux",
              "Southern France",
              "Holland Belgium",
              "Western Germany",
              "Northern Italy"));

  // ================================================================ block of one game

  /** The block {@code p1} of a game (also for the USA of Hard: register rows are then absent). */
  static JsonObject blocco(final JsonObject json, @Nullable final JsonObject monitor) {
    final JsonObject o = new JsonObject();
    final List<JsonObject> rows = righe(json);
    // results
    final JsonObject reached = new JsonObject();
    final JsonObject missed = new JsonObject();
    for (final JsonObject d : decisioni(rows, "compito_risultato")) {
      final JsonObject p = d.getAsJsonObject("parametri");
      final String id = p.get("risultato").getAsString();
      final String esito = p.get("esito").getAsString();
      final int round = d.get("round").getAsInt();
      if ("RAGGIUNTO".equals(esito) && !reached.has(id)) {
        reached.addProperty(id, round);
      } else if ("MANCATO".equals(esito) && !missed.has(id)) {
        missed.addProperty(id, round);
      }
    }
    o.add("risultati_raggiunti", reached);
    o.add("traguardi_mancati", missed);
    if (reached.has("R1")) {
      o.addProperty("r1_round", reached.get("R1").getAsInt());
    }
    // direction
    final JsonArray dirs = new JsonArray();
    for (final JsonObject d : decisioni(rows, "compito_direzione")) {
      final JsonObject p = d.getAsJsonObject("parametri");
      final JsonObject x = new JsonObject();
      x.addProperty("round", d.get("round").getAsInt());
      x.addProperty("scelta", p.get("scelta").getAsString());
      x.addProperty("codice", ConfrontoR5.codice(d));
      x.add("conti", p.get("conti"));
      x.addProperty("fino_al_round", p.get("fino_al_round").getAsInt());
      dirs.add(x);
    }
    o.add("direzioni", dirs);
    if (!dirs.isEmpty()) {
      final JsonObject first = dirs.get(0).getAsJsonObject();
      o.addProperty("direzione", first.get("scelta").getAsString());
      o.addProperty("direzione_round", first.get("round").getAsInt());
      o.addProperty(
          "cambi_direzione",
          (int)
              dirs.asList().stream()
                  .filter(
                      e ->
                          "DIREZIONE_CAMBIATA"
                              .equals(e.getAsJsonObject().get("codice").getAsString()))
                  .count());
    }
    // first landing of the task toward the direction's capital
    Integer firstCapital = null;
    int landings = 0;
    for (final JsonObject d : decisioni(rows, "sbarco")) {
      final JsonObject p = d.getAsJsonObject("parametri");
      if (!p.has("compito") || !"ESEGUITO".equals(ConfrontoR5.codice(d))) {
        continue;
      }
      landings++;
      if (p.has("verso_capitale") && p.get("verso_capitale").getAsBoolean()) {
        final int r = d.get("round").getAsInt();
        firstCapital = firstCapital == null ? r : Math.min(firstCapital, r);
      }
    }
    o.addProperty("sbarchi_compito", landings);
    if (firstCapital != null) {
      o.addProperty("primo_sbarco_capitale_round", firstCapital);
    }
    // first US landing toward Southern Italy / France (monitor: also the USA of Hard)
    final JsonObject usFirst = new JsonObject();
    if (json.has("sbarchi")) {
      for (final JsonElement e : json.getAsJsonArray("sbarchi")) {
        final JsonObject l = e.getAsJsonObject();
        if (!MonitorP1.USA.equals(l.get("nazione").getAsString())) {
          continue;
        }
        final String t = l.get("territorio").getAsString();
        for (final var v : VERSO.entrySet()) {
          if (v.getValue().contains(t) && !usFirst.has(v.getKey())) {
            usFirst.addProperty(v.getKey(), l.get("round").getAsInt());
          }
        }
      }
    }
    o.add("primo_sbarco_usa_verso", usFirst);
    if (monitor != null) {
      o.add("attacchi_usa", monitor.getAsJsonObject("attacchi_usa"));
      o.add("prese_usa", monitor.getAsJsonObject("prese_usa"));
      // purchases from R5
      int ships = 0;
      int planes = 0;
      int transports = 0;
      int troops = 0;
      final JsonObject buy = monitor.getAsJsonObject("acquisti_usa");
      for (final var e : buy.entrySet()) {
        if (Integer.parseInt(e.getKey()) < 5) {
          continue;
        }
        for (final var t : e.getValue().getAsJsonObject().entrySet()) {
          final int n = t.getValue().getAsInt();
          if (NAVI.contains(t.getKey())) {
            ships += n;
          } else if (AEREI.contains(t.getKey())) {
            planes += n;
          } else if ("transport".equals(t.getKey())) {
            transports += n;
          } else {
            troops += n;
          }
        }
      }
      final JsonObject from5 = new JsonObject();
      from5.addProperty("navi_da_guerra", ships);
      from5.addProperty("aerei", planes);
      from5.addProperty("trasporti", transports);
      from5.addProperty("truppe", troops);
      final int played = json.has("round_giocati") ? json.get("round_giocati").getAsInt() : 0;
      from5.addProperty("round_dal_r5", Math.max(0, played - 4));
      o.add("acquisti_usa_dal_r5", from5);
    }
    // MARE turns and their responses (purchase rows of compito_stato)
    int turns = 0;
    int sea = 0;
    int seaAnswered = 0;
    int seaBuyOrAttack = 0;
    final JsonArray unanswered = new JsonArray();
    for (final JsonObject d : decisioni(rows, "compito_stato")) {
      final String code = ConfrontoR5.codice(d);
      if (!"OTTENIBILE".equals(code) && !"OSTACOLI".equals(code)) {
        continue;
      }
      turns++;
      final JsonObject p = d.getAsJsonObject("parametri");
      final List<String> types = strings(p.getAsJsonArray("tipi_ostacolo"));
      if (!types.contains("MARE")) {
        continue;
      }
      sea++;
      final List<String> responses = strings(p.getAsJsonArray("risposte"));
      if (responses.stream().anyMatch(RISPOSTE_MARE::contains)) {
        seaAnswered++;
      } else {
        unanswered.add(d.get("round").getAsInt());
      }
      if (responses.contains("ACQUISTO_MARE") || responses.contains("ATTACCO_FLOTTA")) {
        seaBuyOrAttack++;
      }
    }
    o.addProperty("turni_compito", turns);
    o.addProperty("turni_mare", sea);
    o.addProperty("turni_mare_con_risposta", seaAnswered);
    o.addProperty("turni_mare_con_acquisto_o_attacco", seaBuyOrAttack);
    o.add("turni_mare_senza_risposta", unanswered);
    // naval battles of the task
    int won = 0;
    int lost = 0;
    for (final JsonObject r : rows) {
      if ("esito".equals(r.get("riga").getAsString())
          && "compito_battaglia".equals(r.get("tipo").getAsString())) {
        if (r.getAsJsonObject("esito").get("vinta").getAsBoolean()) {
          won++;
        } else {
          lost++;
        }
      }
    }
    final JsonObject battles = new JsonObject();
    battles.addProperty(
        "attaccate",
        decisioni(rows, "compito_battaglia").stream()
            .filter(d -> "ATTACCO_FLOTTA".equals(ConfrontoR5.codice(d)))
            .count());
    battles.addProperty("vinte", won);
    battles.addProperty("perse", lost);
    o.add("battaglie_navali_usa", battles);
    // US transports lost (enemy attacks: loaded / empty; monitor: total)
    int loadedLost = 0;
    int emptyLost = 0;
    for (final JsonObject d : decisioni(rows, "attacco_subito")) {
      final JsonObject a = ConfrontoR5.alt0(d);
      if (a != null && a.has("trasporti_usa_persi_carichi")) {
        loadedLost += a.get("trasporti_usa_persi_carichi").getAsInt();
        emptyLost += a.get("trasporti_usa_persi_vuoti").getAsInt();
      }
    }
    int total = 0;
    if (json.has("round")) {
      for (final JsonElement e : json.getAsJsonArray("round")) {
        final JsonObject n = e.getAsJsonObject().getAsJsonObject("nazioni");
        if (n != null && n.has(MonitorP1.USA)) {
          total += n.getAsJsonObject(MonitorP1.USA).get("trasporti_persi").getAsInt();
        }
      }
    }
    final JsonObject tl = new JsonObject();
    tl.addProperty("totale", total);
    tl.addProperty("carichi_attacchi_subiti", loadedLost);
    tl.addProperty("vuoti_attacchi_subiti", emptyLost);
    o.add("trasporti_usa_persi", tl);
    // per round R8, R12, R16
    final JsonObject per = new JsonObject();
    if (json.has("round")) {
      for (final JsonElement e : json.getAsJsonArray("round")) {
        final JsonObject r = e.getAsJsonObject();
        final int n = r.get("round").getAsInt();
        if (!ROUNDS.contains(n) || !r.has("p1")) {
          continue;
        }
        final JsonObject x = r.getAsJsonObject("p1").deepCopy();
        final JsonObject reg = x.getAsJsonObject("regioni_usa");
        final int idle =
            (reg.has("patria") ? reg.get("patria").getAsInt() : 0)
                + (reg.has("base") ? reg.get("base").getAsInt() : 0);
        x.addProperty("ferme_patria_base", idle);
        final JsonObject us = r.getAsJsonObject("nazioni").getAsJsonObject(MonitorP1.USA);
        x.addProperty("trasporti", us.get("trasporti").getAsInt());
        x.addProperty("trasporti_inattivi", us.get("trasporti_inattivi").getAsInt());
        per.add(String.valueOf(n), x);
      }
    }
    o.add("per_round", per);
    return o;
  }

  private static List<JsonObject> righe(final JsonObject json) {
    final List<JsonObject> out = new ArrayList<>();
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      out.add(e.getAsJsonObject());
    }
    return out;
  }

  private static List<JsonObject> decisioni(final List<JsonObject> rows, final String tipo) {
    return rows.stream()
        .filter(d -> "decisione".equals(d.get("riga").getAsString()))
        .filter(d -> tipo.equals(d.get("tipo").getAsString()))
        .toList();
  }

  private static List<String> strings(@Nullable final JsonArray a) {
    final List<String> out = new ArrayList<>();
    if (a != null) {
      a.forEach(x -> out.add(x.getAsString()));
    }
    return out;
  }

  // ======================================================================= the section

  static boolean applicabile(final List<Game> games) {
    return games.stream()
        .anyMatch(
            g ->
                g.json() != null
                    && !RiepilogoGenerale.configuration(g.json(), alleanze(g.json())).isEmpty()
                    && RiepilogoGenerale.configuration(g.json(), alleanze(g.json()))
                        .contains(COMPITI));
  }

  private static List<String> alleanze(final JsonObject json) {
    return json.has("alleanze")
        ? new ArrayList<>(json.getAsJsonObject("alleanze").keySet())
        : List.of("Axis", "Allies");
  }

  static void write(final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("p1")) {
        continue;
      }
      byConfig
          .computeIfAbsent(
              RiepilogoGenerale.configuration(g.json(), alliances), k -> new ArrayList<>())
          .add(g.json());
    }
    if (byConfig.isEmpty()) {
      return;
    }
    md.append(
"""

### P1

""");
    md.append(
        "Impianto \"compito\" degli USA (interruttore `compiti`). Ogni numero e' la media per partita"
            + " del blocco `p1` dei `riepilogo.json` (campo tra parentesi); le configurazioni senza"
            + " `compiti` sono gli USA di Hard sugli stessi semi (le righe del registro del compito"
            + " non esistono: \"-\").\n\n");
    // wins
    md.append(
"""
| Configurazione | Partite | Asse | Alleati | Stalli | Quota Alleati [IC 95%] | Confronto | p (Fisher) |
|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> list = e.getValue();
      final int n = list.size();
      final int axis = ConfrontoR4.wins(list, "Axis");
      final int allies = ConfrontoR4.wins(list, "Allies");
      String vs = "-";
      String p = "-";
      if (e.getKey().contains(COMPITI)) {
        final String base = senzaCompiti(e.getKey(), byConfig.keySet());
        if (base != null) {
          final List<JsonObject> o = byConfig.get(base);
          vs = "contro " + base;
          p =
              ConfrontoR5.pText(
                  ConfrontoR4.fisher(allies, n, ConfrontoR4.wins(o, "Allies"), o.size()));
        }
      }
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(n)
          .append(" | ")
          .append(axis)
          .append(" | ")
          .append(allies)
          .append(" | ")
          .append(n - axis - allies)
          .append(" | ")
          .append(ConfrontoR4.wilsonText(allies, n))
          .append(" | ")
          .append(vs)
          .append(" | ")
          .append(p)
          .append(" |\n");
    }
    // criteria
    md.append(
"""

| Configurazione | 1 R1 entro R6 (`r1_round`) | Direzione ITALIA/FRANCIA (`direzione`) | 2 turni MARE / con risposta / con acquisto o attacco (`turni_mare*`) | 3 navi+aerei dal R5 a round (`acquisti_usa_dal_r5`) | 3 trasporti comprati dal R5 | 3 trasporti inattivi R12 (`per_round.12.trasporti_inattivi`) | 4 primo sbarco verso la capitale entro R8 (`primo_sbarco_capitale_round`) | 4 SI o France attaccate dagli USA (`attacchi_usa`) | 5 ferme patria+base R12 (`per_round.12.ferme_patria_base`) | 6 trasporti USA persi totale / carichi / vuoti (`trasporti_usa_persi`) | Battaglie navali vinte / perse (`battaglie_navali_usa`) |
|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> list = e.getValue();
      final int n = list.size();
      final List<JsonObject> b = list.stream().map(j -> j.getAsJsonObject("p1")).toList();
      final boolean task = e.getKey().contains(COMPITI);
      final long r1 =
          b.stream().filter(x -> x.has("r1_round") && x.get("r1_round").getAsInt() <= 6).count();
      final long it =
          b.stream()
              .filter(x -> x.has("direzione") && "ITALIA".equals(x.get("direzione").getAsString()))
              .count();
      final long fr =
          b.stream()
              .filter(x -> x.has("direzione") && "FRANCIA".equals(x.get("direzione").getAsString()))
              .count();
      final double seaTurns = mean(b, x -> (double) x.get("turni_mare").getAsInt());
      final double seaAns = mean(b, x -> (double) x.get("turni_mare_con_risposta").getAsInt());
      final double seaBuy =
          mean(b, x -> (double) x.get("turni_mare_con_acquisto_o_attacco").getAsInt());
      final double shipsPerRound =
          mean(
              b,
              x -> {
                final JsonObject a = x.getAsJsonObject("acquisti_usa_dal_r5");
                final int rr = a.get("round_dal_r5").getAsInt();
                return rr <= 0
                    ? 0.0
                    : (a.get("navi_da_guerra").getAsInt() + a.get("aerei").getAsInt())
                        / (double) rr;
              });
      final double trBought =
          mean(
              b,
              x -> (double) x.getAsJsonObject("acquisti_usa_dal_r5").get("trasporti").getAsInt());
      final double idle12 = meanRound(b, "12", "trasporti_inattivi");
      final long landing8 =
          task
              ? b.stream()
                  .filter(
                      x ->
                          x.has("primo_sbarco_capitale_round")
                              && x.get("primo_sbarco_capitale_round").getAsInt() <= 8)
                  .count()
              : b.stream()
                  .filter(
                      x ->
                          x.getAsJsonObject("primo_sbarco_usa_verso").entrySet().stream()
                              .anyMatch(v -> v.getValue().getAsInt() <= 8))
                  .count();
      final long attacked =
          b.stream()
              .filter(x -> x.has("attacchi_usa") && !x.getAsJsonObject("attacchi_usa").isEmpty())
              .count();
      final double stuck12 = meanRound(b, "12", "ferme_patria_base");
      final double lostTot =
          mean(b, x -> (double) x.getAsJsonObject("trasporti_usa_persi").get("totale").getAsInt());
      final double lostLoaded =
          mean(
              b,
              x ->
                  (double)
                      x.getAsJsonObject("trasporti_usa_persi")
                          .get("carichi_attacchi_subiti")
                          .getAsInt());
      final double lostEmpty =
          mean(
              b,
              x ->
                  (double)
                      x.getAsJsonObject("trasporti_usa_persi")
                          .get("vuoti_attacchi_subiti")
                          .getAsInt());
      final double won =
          mean(b, x -> (double) x.getAsJsonObject("battaglie_navali_usa").get("vinte").getAsInt());
      final double lostB =
          mean(b, x -> (double) x.getAsJsonObject("battaglie_navali_usa").get("perse").getAsInt());
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(task ? r1 + "/" + n + " (" + pct(r1, n) + ")" : "-")
          .append(" | ")
          .append(task ? it + " / " + fr : "-")
          .append(" | ")
          .append(task ? f(seaTurns) + " / " + f(seaAns) + " / " + f(seaBuy) : "-")
          .append(" | ")
          .append(f(shipsPerRound))
          .append(" | ")
          .append(f(trBought))
          .append(" | ")
          .append(f(idle12))
          .append(" | ")
          .append(landing8 + "/" + n + (task ? "" : " (sbarco USA del monitor)"))
          .append(" | ")
          .append(attacked + "/" + n)
          .append(" | ")
          .append(f(stuck12))
          .append(" | ")
          .append(f(lostTot) + " / " + f(lostLoaded) + " / " + f(lostEmpty))
          .append(" | ")
          .append(task ? f(won) + " / " + f(lostB) : "-")
          .append(" |\n");
    }
    // per round
    md.append(
        "\nUnita' di terra USA per regione e trasporti USA (media per partita, `per_round`): patria /"
            + " base Gibilterra / mare / Europa occ. / Mediterraneo e Nord Africa / altrove; trasporti"
            + " totali / inattivi.\n\n| Configurazione |");
    ROUNDS.forEach(r -> md.append(" R").append(r).append(" |"));
    md.append("\n|---|");
    ROUNDS.forEach(r -> md.append("---|"));
    md.append('\n');
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = e.getValue().stream().map(j -> j.getAsJsonObject("p1")).toList();
      md.append("| ").append(e.getKey()).append(" |");
      for (final int r : ROUNDS) {
        final String k = String.valueOf(r);
        md.append(' ')
            .append(f(meanReg(b, k, "patria")))
            .append(" / ")
            .append(f(meanReg(b, k, "base")))
            .append(" / ")
            .append(f(meanReg(b, k, "mare")))
            .append(" / ")
            .append(f(meanReg(b, k, "europa_occidentale")))
            .append(" / ")
            .append(f(meanReg(b, k, "mediterraneo_nord_africa")))
            .append(" / ")
            .append(f(meanReg(b, k, "altrove")))
            .append("; ")
            .append(f(meanRound(b, k, "trasporti")))
            .append(" / ")
            .append(f(meanRound(b, k, "trasporti_inattivi")))
            .append(" |");
      }
      md.append('\n');
    }
  }

  /** The same configuration without the task (the USA of Hard), if present. */
  @Nullable
  static String senzaCompiti(final String config, final Set<String> configs) {
    final String[] sides = config.split(" / ", 2);
    if (sides.length < 2) {
      return null;
    }
    final String axis = sides[0];
    for (final String c : configs) {
      if (!c.contains(COMPITI) && c.startsWith(axis + " / ") && c.endsWith("Hard")) {
        return c;
      }
    }
    return null;
  }

  private static double mean(final List<JsonObject> b, final Function<JsonObject, Double> f) {
    if (b.isEmpty()) {
      return 0;
    }
    double s = 0;
    for (final JsonObject x : b) {
      try {
        s += f.apply(x);
      } catch (final RuntimeException e) {
        // missing field = 0
      }
    }
    return s / b.size();
  }

  private static double meanRound(final List<JsonObject> b, final String round, final String key) {
    final List<JsonObject> with =
        b.stream().filter(x -> x.getAsJsonObject("per_round").has(round)).toList();
    return mean(
        with, x -> x.getAsJsonObject("per_round").getAsJsonObject(round).get(key).getAsDouble());
  }

  private static double meanReg(final List<JsonObject> b, final String round, final String key) {
    final List<JsonObject> with =
        b.stream().filter(x -> x.getAsJsonObject("per_round").has(round)).toList();
    return mean(
        with,
        x -> {
          final JsonObject reg =
              x.getAsJsonObject("per_round").getAsJsonObject(round).getAsJsonObject("regioni_usa");
          return reg.has(key) ? reg.get(key).getAsDouble() : 0.0;
        });
  }

  private static String f(final double x) {
    return String.format(Locale.ROOT, "%.1f", x).replace('.', ',');
  }

  private static String pct(final long k, final int n) {
    return n == 0 ? "-" : Math.round(100.0 * k / n) + "%";
  }
}
