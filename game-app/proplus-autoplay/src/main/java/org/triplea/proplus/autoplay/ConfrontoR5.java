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
import java.util.function.ToDoubleFunction;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step R5 "ALLEATI RIFATTI": the subsection "R5" of "Partite complete": wins per configuration with
 * Wilson's interval and Fisher's exact test (e1 against a, e3 against e2), round of the victory;
 * the Allied landings per period and region (program and Hard); transports, blocked purchases and
 * losses at sea; land units per region; Gibraltar; Russia; Rome, Berlin, Paris; Italy and Africa;
 * the program's share of the PUs and the hub's waits. From the games' summaries (Monitor's {@code
 * r4}/{@code r5}, the landings and the register).
 */
final class ConfrontoR5 {
  private ConfrontoR5() {}

  static final String R5 = "[con alleati_r5]";
  static final String ASSEDIO = "[con assedio]";
  static final List<Integer> ROUNDS_TRASPORTI = List.of(4, 8, 12, 16);
  static final List<Integer> ROUNDS_TERRA = List.of(6, 9, 12, 16);
  static final List<Integer> ROUNDS_RUSSIA = List.of(8, 12, 16);
  static final List<String> PERIODI = List.of("R1-6", "R7-12", "R13-18", "R19+");
  static final List<String> REGIONI = List.of("europa_occidentale", "italia_isole", "nord_africa");
  static final List<String> ALLEATI_OVEST = List.of("Americans", "British");

  static void write(final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      final JsonObject p = g.json().getAsJsonObject("parametri");
      if (!p.has("fino_a_vittoria") || !p.get("fino_a_vittoria").getAsBoolean()) {
        continue;
      }
      byConfig
          .computeIfAbsent(
              RiepilogoGenerale.configuration(g.json(), alliances), k -> new ArrayList<>())
          .add(g.json());
    }
    if (byConfig.keySet().stream().noneMatch(c -> c.contains(R5))) {
      return;
    }
    final String reference =
        String.join(" / ", alliances.stream().map(a -> ConfrontoHard.HARD).toList());
    final List<String> configs = new ArrayList<>(byConfig.keySet());
    configs.sort((a, b) -> Integer.compare(ordine(a, reference), ordine(b, reference)));
    md.append(
"""

### R5

Configurazioni: (a) Hard vs Hard; (e1) Hard vs Alleati con impegno + alleati_r5; (e2) Asse con \
impegno + assedio vs Hard; (e3) Asse con impegno + assedio vs Alleati con impegno + alleati_r5. \
"Programma" = sbarchi del registro (ondate USA, logoramento e rinforzi britannici); "Hard" = sbarchi \
alleati contati dal monitor meno quelli del programma (il monitor conta solo gli scarichi in territori \
nemici a inizio turno).

""");
    esiti(md, configs, byConfig, reference);
    sbarchi(md, configs, byConfig);
    trasporti(md, configs, byConfig);
    terra(md, configs, byConfig);
    gibilterra(md, configs, byConfig);
    russiaCitta(md, configs, byConfig);
    italia(md, configs, byConfig);
    programma(md, configs, byConfig);
  }

  static int ordine(final String config, final String reference) {
    if (config.equals(reference)) {
      return 0;
    }
    if (config.contains(R5) && !config.contains(ASSEDIO)) {
      return 1;
    }
    if (config.contains(ASSEDIO) && !config.contains(R5)) {
      return 2;
    }
    return config.contains(R5) ? 3 : 4;
  }

  /** e1 against a, e3 against e2 (the Allies' wins); null when not one of them. */
  static String confronto(final String config, final List<String> configs, final String ref) {
    if (config.contains(R5) && !config.contains(ASSEDIO)) {
      return configs.contains(ref) ? ref : null;
    }
    if (config.contains(R5) && config.contains(ASSEDIO)) {
      return configs.stream()
          .filter(c -> c.contains(ASSEDIO) && !c.contains(R5))
          .findFirst()
          .orElse(null);
    }
    return null;
  }

  // ============================================================================== outcomes

  static void esiti(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig,
      final String reference) {
    md.append(
"""
Vittorie con l'intervallo al 95% di Wilson; p = test esatto di Fisher a due code sulle vittorie alleate: e1 contro (a), e3 contro e2.

| Configurazione | Partite | Vittorie Asse | Vittorie Alleati | Stalli | Quota Asse [IC 95%] | Quota Alleati [IC 95%] | Confronto | p (Alleati) | Round vittoria mediana (min-max) | Round vittoria Alleati mediana | Round vittoria Asse mediana |
|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      final int n = list.size();
      final int axis = ConfrontoR4.wins(list, "Axis");
      final int allies = ConfrontoR4.wins(list, "Allies");
      final String other = confronto(config, configs, reference);
      String p = "-";
      String vs = "-";
      if (other != null) {
        final List<JsonObject> o = byConfig.get(other);
        p = pText(ConfrontoR4.fisher(allies, n, ConfrontoR4.wins(o, "Allies"), o.size()));
        vs = other.equals(reference) ? "contro (a)" : "contro e2";
      }
      md.append("| ")
          .append(config)
          .append(" | ")
          .append(n)
          .append(" | ")
          .append(axis)
          .append(" | ")
          .append(allies)
          .append(" | ")
          .append(n - axis - allies)
          .append(" | ")
          .append(ConfrontoR4.wilsonText(axis, n))
          .append(" | ")
          .append(ConfrontoR4.wilsonText(allies, n))
          .append(" | ")
          .append(vs)
          .append(" | ")
          .append(p)
          .append(" | ")
          .append(roundText(list, null))
          .append(" | ")
          .append(roundText(list, "Allies"))
          .append(" | ")
          .append(roundText(list, "Axis"))
          .append(" |\n");
    }
  }

  static String roundText(final List<JsonObject> list, final String winner) {
    final List<Double> rounds = new ArrayList<>();
    for (final JsonObject j : list) {
      final JsonObject o = j.getAsJsonObject("esito");
      if (!o.has("round_vittoria")) {
        continue;
      }
      if (winner != null
          && !(j.has("vittoria")
              && j.getAsJsonObject("vittoria").has("vincitore")
              && winner.equals(j.getAsJsonObject("vittoria").get("vincitore").getAsString()))) {
        continue;
      }
      rounds.add(o.get("round_vittoria").getAsDouble());
    }
    if (rounds.isEmpty()) {
      return "-";
    }
    return num0(ConfrontoR4.median(rounds))
        + (winner != null
            ? ""
            : " ("
                + num0(rounds.stream().mapToDouble(d -> d).min().orElse(0))
                + "-"
                + num0(rounds.stream().mapToDouble(d -> d).max().orElse(0))
                + ")");
  }

  static String pText(final double p) {
    return p < 0.001 ? "< 0,001" : String.format(Locale.ROOT, "%.3f", p).replace('.', ',');
  }

  // ============================================================================== landings

  static String periodo(final int round) {
    if (round <= 6) {
      return PERIODI.get(0);
    }
    if (round <= 12) {
      return PERIODI.get(1);
    }
    if (round <= 18) {
      return PERIODI.get(2);
    }
    return PERIODI.get(3);
  }

  static String regione(final String territory) {
    for (final var e :
        games.strategy.triplea.ai.proplus.AlleatiR5.Config.get().regioni().entrySet()) {
      if (e.getValue().contains(territory)) {
        return e.getKey();
      }
    }
    return "altrove";
  }

  /** Per game: "fonte|periodo|regione" → units landed (fonte = programma, totale). */
  static Map<String, Double> sbarchiPartita(final JsonObject j) {
    final Map<String, Double> out = new TreeMap<>();
    if (j.has("sbarchi") && j.get("sbarchi").isJsonArray()) {
      for (final JsonElement e : j.getAsJsonArray("sbarchi")) {
        final JsonObject l = e.getAsJsonObject();
        if (!ALLEATI_OVEST.contains(l.get("nazione").getAsString())) {
          continue;
        }
        out.merge(
            "totale|"
                + periodo(l.get("round").getAsInt())
                + "|"
                + regione(l.get("territorio").getAsString()),
            l.get("unita").getAsDouble(),
            Double::sum);
      }
    }
    for (final JsonObject d : decisioni(j, "sbarco")) {
      final JsonObject a = alt0(d);
      if (a == null || !a.has("unita_sbarcate") || !a.has("territorio")) {
        continue;
      }
      out.merge(
          "programma|"
              + periodo(d.get("round").getAsInt())
              + "|"
              + regione(a.get("territorio").getAsString()),
          a.get("unita_sbarcate").getAsDouble(),
          Double::sum);
    }
    for (final JsonObject d : decisioni(j, "rinforzo_testa_di_ponte")) {
      final JsonObject a = alt0(d);
      if (a == null || !a.has("unita_arrivate") || !a.has("territorio")) {
        continue;
      }
      out.merge(
          "programma|"
              + periodo(d.get("round").getAsInt())
              + "|"
              + regione(a.get("territorio").getAsString()),
          a.get("unita_arrivate").getAsDouble(),
          Double::sum);
    }
    return out;
  }

  static void sbarchi(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
"""

Unita' alleate (USA + Regno Unito) sbarcate a partita, media sulle partite, per periodo e regione. Hard = monitor - programma (minimo 0 per partita).

| Configurazione | Fonte | Regione |""");
    PERIODI.forEach(p -> md.append(' ').append(p).append(" |"));
    md.append(" Totale |\n|---|---|---|");
    PERIODI.forEach(p -> md.append("---|"));
    md.append("---|\n");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      final List<Map<String, Double>> per = list.stream().map(ConfrontoR5::sbarchiPartita).toList();
      for (final String fonte : List.of("programma", "Hard", "totale")) {
        for (final String reg : REGIONI) {
          md.append("| ").append(config).append(" | ").append(fonte).append(" | ").append(reg);
          double tot = 0;
          for (final String period : PERIODI) {
            double s = 0;
            for (final Map<String, Double> m : per) {
              s += valore(m, fonte, period, reg);
            }
            final double mean = list.isEmpty() ? Double.NaN : s / list.size();
            tot += list.isEmpty() ? 0 : mean;
            md.append(" | ").append(num1(mean));
          }
          md.append(" | ").append(num1(tot)).append(" |\n");
        }
      }
    }
  }

  static double valore(
      final Map<String, Double> m, final String fonte, final String period, final String reg) {
    final double total = m.getOrDefault("totale|" + period + "|" + reg, 0.0);
    final double prog = m.getOrDefault("programma|" + period + "|" + reg, 0.0);
    return switch (fonte) {
      case "programma" -> prog;
      case "Hard" -> Math.max(0, total - prog);
      default -> total;
    };
  }

  // ============================================================================ transports

  static void trasporti(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
        "\nTrasporti USA e britannici (media sulle partite che arrivano al round), acquisti di"
            + " trasporti bloccati dal mare non difeso (turni britannici a partita), trasporti persi"
            + " in tutta la partita (monitor) e perdite in mare del programma (registro: unita' e"
            + " trasporti).\n\n| Configurazione | Nazione |");
    ROUNDS_TRASPORTI.forEach(r -> md.append(" R").append(r).append(" |"));
    md.append(
        " Trasporti persi | Bloccati (turni) | Programma: unita' perse in mare | Programma:"
            + " trasporti persi |\n|---|---|");
    ROUNDS_TRASPORTI.forEach(r -> md.append("---|"));
    md.append("---|---|---|---|\n");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      for (final String nation : ALLEATI_OVEST) {
        md.append("| ").append(config).append(" | ").append(nation);
        for (final int r : ROUNDS_TRASPORTI) {
          md.append(" | ")
              .append(num1(mean(values(list, j -> nazione(j, r, nation, "trasporti")))));
        }
        md.append(" | ")
            .append(num1(mean(values(list, j -> sommaNazione(j, nation, "trasporti_persi")))))
            .append(" | ")
            .append(
                nation.equals("British")
                    ? num1(
                        mean(
                            values(
                                list,
                                j -> conta(j, "trasporti_mare_difeso", nation, "MARE_NON_DIFESO"))))
                    : "-")
            .append(" | ")
            .append(
                num1(
                    mean(values(list, j -> sommaAlt(j, "perdite_in_mare", nation, "unita_perse")))))
            .append(" | ")
            .append(
                num1(
                    mean(
                        values(
                            list, j -> sommaAlt(j, "perdite_in_mare", nation, "trasporti_persi")))))
            .append(" |\n");
      }
    }
  }

  // ============================================================================ land units

  static void terra(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
"""

Unita' di terra per regione (media; EO = Europa occidentale, IT = Italia e isole, NA = Nord Africa). Tedeschi e italiani in EO e IT = misura della pressione.

| Configurazione | Nazione | Regione |""");
    ROUNDS_TERRA.forEach(r -> md.append(" R").append(r).append(" |"));
    md.append("\n|---|---|---|");
    ROUNDS_TERRA.forEach(r -> md.append("---|"));
    md.append('\n');
    final Map<String, List<String>> what = new LinkedHashMap<>();
    what.put("Americans", REGIONI);
    what.put("British", REGIONI);
    what.put("Germans", List.of("europa_occidentale", "italia_isole"));
    what.put("Italians", List.of("europa_occidentale", "italia_isole"));
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      for (final var e : what.entrySet()) {
        for (final String reg : e.getValue()) {
          md.append("| ").append(config).append(" | ").append(e.getKey()).append(" | ").append(reg);
          for (final int r : ROUNDS_TERRA) {
            md.append(" | ")
                .append(num1(mean(values(list, j -> regioneR5(j, r, e.getKey(), reg)))));
          }
          md.append(" |\n");
        }
      }
    }
  }

  // ============================================================================= Gibraltar

  static void gibilterra(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
        "\nGibilterra: partite con Gibilterra alleata a fine round; battaglie navali decise e"
            + " sbarchi per prenderla (a partita); round in cui diventa snodo (primo \"rotta_usa\""
            + " = Gibraltar, mediana sulle partite che lo hanno).\n\n| Configurazione |");
    ROUNDS_TRASPORTI.forEach(r -> md.append(" Alleata R").append(r).append(" |"));
    md.append(
        " Cade all'Asse (partite, round mediano) | Battaglie decise | Mare liberato | Sbarchi per"
            + " prenderla | Snodo dal round |\n|---|");
    ROUNDS_TRASPORTI.forEach(r -> md.append("---|"));
    md.append("---|---|---|---|---|\n");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config);
      for (final int r : ROUNDS_TRASPORTI) {
        int allied = 0;
        int seen = 0;
        for (final JsonObject j : list) {
          final String owner = proprietario(j, r, "Gibilterra");
          if (owner != null) {
            seen++;
            allied += "Allies".equals(alleanza(j, owner)) ? 1 : 0;
          }
        }
        md.append(" | ").append(allied).append('/').append(seen);
      }
      final List<Double> falls = new ArrayList<>();
      for (final JsonObject j : list) {
        final int r = ConfrontoR4.caduta(j, "Gibilterra");
        if (r > 0) {
          falls.add((double) r);
        }
      }
      final List<Double> hub = values(list, ConfrontoR5::snodoDal);
      md.append(" | ")
          .append(falls.size())
          .append(falls.isEmpty() ? "" : " R" + num0(ConfrontoR4.median(falls)))
          .append(" | ")
          .append(
              num1(mean(values(list, j -> conta(j, "battaglia_gibilterra", null, "BATTAGLIA")))))
          .append(" | ")
          .append(num1(mean(values(list, j -> esiti(j, "battaglia_gibilterra", "mare_liberato")))))
          .append(" | ")
          .append(num1(mean(values(list, j -> conta(j, "presa_gibilterra", null, "PRESA")))))
          .append(" | ")
          .append(hub.isEmpty() ? "-" : hub.size() + " partite, R" + num0(ConfrontoR4.median(hub)))
          .append(" |\n");
    }
  }

  static double snodoDal(final JsonObject j) {
    for (final JsonObject d : decisioni(j, "rotta_usa")) {
      if ("Gibraltar".equals(d.get("scelta").getAsString())) {
        return d.get("round").getAsInt();
      }
    }
    return Double.NaN;
  }

  // ===================================================================== Russia and cities

  static void russiaCitta(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append("\nRussia: esercito (unita' di terra) e reddito, media.\n\n| Configurazione |");
    ROUNDS_RUSSIA.forEach(r -> md.append(" Esercito R").append(r).append(" |"));
    ROUNDS_RUSSIA.forEach(r -> md.append(" Reddito R").append(r).append(" |"));
    md.append("\n|---|");
    ROUNDS_RUSSIA.forEach(r -> md.append("---|---|"));
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config);
      for (final int r : ROUNDS_RUSSIA) {
        md.append(" | ").append(num1(mean(values(list, j -> r4(j, r, "esercito", "Russians")))));
      }
      for (final int r : ROUNDS_RUSSIA) {
        md.append(" | ")
            .append(num1(mean(values(list, j -> nazione(j, r, "Russians", "reddito")))));
      }
      md.append(" |\n");
    }
    md.append(
"""

Caduta (passaggio all'altra alleanza) e liberazione (ritorno), partite / partite e round mediano.

| Configurazione | Mosca cade | Stalingrado cade | Leningrado cade | Roma cade | Berlino cade | Parigi cade | Parigi liberata |
|---|---|---|---|---|---|---|---|
""");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config);
      for (final String city :
          List.of("Mosca", "Stalingrado", "Leningrado", "Roma", "Berlino", "Parigi")) {
        md.append(" | ").append(cambio(list, city, 1));
      }
      md.append(" | ").append(cambio(list, "Parigi", 2)).append(" |\n");
    }
  }

  static String cambio(final List<JsonObject> list, final String city, final int nth) {
    final List<Double> rounds = new ArrayList<>();
    for (final JsonObject j : list) {
      final int r = cambioRound(j, city, nth);
      if (r > 0) {
        rounds.add((double) r);
      }
    }
    return rounds.size()
        + "/"
        + list.size()
        + (rounds.isEmpty() ? "" : " R" + num0(ConfrontoR4.median(rounds)));
  }

  /** The round of the {@code nth} change of alliance of {@code city} (end of round), -1 if none. */
  static int cambioRound(final JsonObject j, final String city, final int nth) {
    String last = null;
    int changes = 0;
    for (final JsonElement e : rounds(j)) {
      final JsonObject round = e.getAsJsonObject();
      if (!round.has("r4")) {
        continue;
      }
      final JsonObject owners = round.getAsJsonObject("r4").getAsJsonObject("proprietari");
      if (!owners.has(city)) {
        continue;
      }
      final String a = alleanza(j, owners.get(city).getAsString());
      if (last != null && !a.equals(last)) {
        changes++;
        if (changes == nth) {
          return round.get("round").getAsInt();
        }
      }
      last = a;
    }
    return -1;
  }

  // ============================================================================== Italy

  static void italia(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
        "\nItalia e Africa: reddito italiano, PU dei bonus italiani attivi, territori dell'Asse in"
            + " Africa (livello 1 + 2 di R4) per round; unita' comprate in Sudafrica a partita e"
            + " round in cui la condizione B5 smette di valere (primo \"africa_sudafrica\" con"
            + " CONDIZIONE_FALSA, mediana).\n\n| Configurazione |");
    ROUNDS_TRASPORTI.forEach(r -> md.append(" Reddito R").append(r).append(" |"));
    ROUNDS_TRASPORTI.forEach(r -> md.append(" Bonus R").append(r).append(" |"));
    ROUNDS_TRASPORTI.forEach(r -> md.append(" Africa Asse R").append(r).append(" |"));
    md.append(" Sudafrica (unita') | B5 falsa dal round |\n|---|");
    for (int i = 0; i < 3 * ROUNDS_TRASPORTI.size() + 2; i++) {
      md.append("---|");
    }
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config);
      for (final int r : ROUNDS_TRASPORTI) {
        md.append(" | ")
            .append(num1(mean(values(list, j -> nazione(j, r, "Italians", "reddito")))));
      }
      for (final int r : ROUNDS_TRASPORTI) {
        md.append(" | ").append(num1(mean(values(list, j -> bonusItalia(j, r)))));
      }
      for (final int r : ROUNDS_TRASPORTI) {
        md.append(" | ")
            .append(
                num1(
                    mean(
                        values(
                            list,
                            j ->
                                r4(j, r, "africa_asse_livello1", null)
                                    + r4(j, r, "africa_asse_livello2", null)))));
      }
      final List<Double> stop = values(list, ConfrontoR5::b5Falsa);
      md.append(" | ")
          .append(num1(mean(values(list, ConfrontoR5::sudafrica))))
          .append(" | ")
          .append(
              stop.isEmpty() ? "-" : stop.size() + " partite, R" + num0(ConfrontoR4.median(stop)))
          .append(" |\n");
    }
  }

  static double bonusItalia(final JsonObject j, final int r) {
    final JsonObject round = round(j, r);
    if (round == null
        || !round.has("r4")
        || !round.getAsJsonObject("r4").getAsJsonObject("obiettivi").has("Italians")) {
      return Double.NaN;
    }
    double s = 0;
    for (final var e :
        round
            .getAsJsonObject("r4")
            .getAsJsonObject("obiettivi")
            .getAsJsonObject("Italians")
            .entrySet()) {
      s += e.getValue().getAsDouble();
    }
    return s;
  }

  static double sudafrica(final JsonObject j) {
    if (ConfrontoR3a.registro(j).isEmpty()) {
      return Double.NaN;
    }
    double s = 0;
    for (final JsonObject d : decisioni(j, "africa_sudafrica")) {
      final JsonObject a = alt0(d);
      if (a != null && a.has("ordine")) {
        for (final var e : a.getAsJsonObject("ordine").entrySet()) {
          s += e.getValue().getAsDouble();
        }
      }
    }
    return s;
  }

  static double b5Falsa(final JsonObject j) {
    for (final JsonObject d : decisioni(j, "africa_sudafrica")) {
      if ("CONDIZIONE_FALSA".equals(codice(d))) {
        return d.get("round").getAsInt();
      }
    }
    return Double.NaN;
  }

  // ============================================================================= program

  static void programma(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
"""

Programma: quota dei PU del turno ordinata dal programma (somma ordini / somma PU dei turni), attese dello snodo USA (round senza bersaglio al 70%) a partita e cosa mancava (totale sulle partite).

| Configurazione | Quota PU USA | Quota PU Regno Unito | Attese snodo | Manca vittoria | Manca carico | Nessun bersaglio | Logoramento UK eseguito (turni) |
|---|---|---|---|---|---|---|---|
""");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      if (list.stream().allMatch(j -> ConfrontoR3a.registro(j).isEmpty())) {
        md.append("| ").append(config).append(" | - | - | - | - | - | - | - |\n");
        continue;
      }
      final Map<String, Integer> missing = new TreeMap<>();
      for (final JsonObject j : list) {
        for (final JsonObject d : decisioni(j, "snodo_usa")) {
          if (!"ATTESA_ACCUMULO".equals(codice(d))) {
            continue;
          }
          final String m =
              d.getAsJsonObject("parametri").has("manca")
                  ? d.getAsJsonObject("parametri").get("manca").getAsString()
                  : "";
          missing.merge(
              m.contains("vittoria") ? "vittoria" : m.contains("carico") ? "carico" : "bersaglio",
              1,
              Integer::sum);
        }
      }
      md.append("| ")
          .append(config)
          .append(" | ")
          .append(quota(list, "Americans"))
          .append(" | ")
          .append(quota(list, "British"))
          .append(" | ")
          .append(num1(mean(values(list, j -> conta(j, "snodo_usa", null, "ATTESA_ACCUMULO")))))
          .append(" | ")
          .append(missing.getOrDefault("vittoria", 0))
          .append(" | ")
          .append(missing.getOrDefault("carico", 0))
          .append(" | ")
          .append(missing.getOrDefault("bersaglio", 0))
          .append(" | ")
          .append(num1(mean(values(list, j -> conta(j, "logoramento_uk", null, "LOGORAMENTO")))))
          .append(" |\n");
    }
  }

  static String quota(final List<JsonObject> list, final String nation) {
    double ordered = 0;
    double turn = 0;
    for (final JsonObject j : list) {
      for (final JsonObject d : decisioni(j, "acquisto_programma")) {
        final JsonObject a = alt0(d);
        if (!nation.equals(d.get("nazione").getAsString())
            || a == null
            || !a.has("pu_turno")
            || !a.has("pu_ordine")) {
          continue;
        }
        ordered += a.get("pu_ordine").getAsDouble();
        turn += a.get("pu_turno").getAsDouble();
      }
    }
    return turn <= 0 ? "-" : num0(100 * ordered / turn) + "%";
  }

  // ============================================================================= helpers

  static List<JsonObject> decisioni(final JsonObject j, final String tipo) {
    final List<JsonObject> out = new ArrayList<>();
    for (final JsonElement e : ConfrontoR3a.registro(j)) {
      final JsonObject d = e.getAsJsonObject();
      if ("decisione".equals(d.get("riga").getAsString())
          && tipo.equals(d.get("tipo").getAsString())) {
        out.add(d);
      }
    }
    return out;
  }

  static JsonObject alt0(final JsonObject d) {
    final JsonArray a = d.getAsJsonArray("alternative");
    return a == null || a.isEmpty() || !a.get(0).isJsonObject() ? null : a.get(0).getAsJsonObject();
  }

  static String codice(final JsonObject d) {
    return d.has("motivo") ? d.getAsJsonObject("motivo").get("codice").getAsString() : "";
  }

  /** Decisions of {@code tipo} (of {@code nation} if not null) with {@code code}; NaN without. */
  static double conta(
      final JsonObject j, final String tipo, final String nation, final String code) {
    if (ConfrontoR3a.registro(j).isEmpty()) {
      return Double.NaN;
    }
    return decisioni(j, tipo).stream()
        .filter(d -> nation == null || nation.equals(d.get("nazione").getAsString()))
        .filter(d -> code.equals(codice(d)))
        .count();
  }

  static double sommaAlt(
      final JsonObject j, final String tipo, final String nation, final String key) {
    if (ConfrontoR3a.registro(j).isEmpty()) {
      return Double.NaN;
    }
    double s = 0;
    for (final JsonObject d : decisioni(j, tipo)) {
      final JsonObject a = alt0(d);
      if (nation.equals(d.get("nazione").getAsString()) && a != null && a.has(key)) {
        s += a.get(key).getAsDouble();
      }
    }
    return s;
  }

  /** Outcome rows of {@code tipo} with a true boolean {@code key}. */
  static double esiti(final JsonObject j, final String tipo, final String key) {
    if (ConfrontoR3a.registro(j).isEmpty()) {
      return Double.NaN;
    }
    int n = 0;
    for (final JsonElement e : ConfrontoR3a.registro(j)) {
      final JsonObject d = e.getAsJsonObject();
      if ("esito".equals(d.get("riga").getAsString())
          && tipo.equals(d.get("tipo").getAsString())
          && d.getAsJsonObject("esito").has(key)
          && d.getAsJsonObject("esito").get(key).getAsBoolean()) {
        n++;
      }
    }
    return n;
  }

  static JsonArray rounds(final JsonObject j) {
    return j.has("round") ? j.getAsJsonArray("round") : new JsonArray();
  }

  static JsonObject round(final JsonObject j, final int r) {
    for (final JsonElement e : rounds(j)) {
      if (e.getAsJsonObject().get("round").getAsInt() == r) {
        return e.getAsJsonObject();
      }
    }
    return null;
  }

  static double nazione(final JsonObject j, final int r, final String nation, final String key) {
    final JsonObject round = round(j, r);
    if (round == null
        || !round.has("nazioni")
        || !round.getAsJsonObject("nazioni").has(nation)
        || !round.getAsJsonObject("nazioni").getAsJsonObject(nation).has(key)) {
      return Double.NaN;
    }
    return round.getAsJsonObject("nazioni").getAsJsonObject(nation).get(key).getAsDouble();
  }

  static double sommaNazione(final JsonObject j, final String nation, final String key) {
    double s = 0;
    for (final JsonElement e : rounds(j)) {
      final JsonObject n = e.getAsJsonObject().getAsJsonObject("nazioni");
      if (n != null && n.has(nation) && n.getAsJsonObject(nation).has(key)) {
        s += n.getAsJsonObject(nation).get(key).getAsDouble();
      }
    }
    return s;
  }

  static double r4(final JsonObject j, final int r, final String key, final String sub) {
    final JsonObject round = round(j, r);
    if (round == null || !round.has("r4") || !round.getAsJsonObject("r4").has(key)) {
      return Double.NaN;
    }
    final JsonElement v = round.getAsJsonObject("r4").get(key);
    if (sub == null) {
      return v.getAsDouble();
    }
    return v.getAsJsonObject().has(sub) ? v.getAsJsonObject().get(sub).getAsDouble() : Double.NaN;
  }

  static double regioneR5(final JsonObject j, final int r, final String nation, final String key) {
    final JsonObject round = round(j, r);
    if (round == null || !round.has("r5")) {
      return Double.NaN;
    }
    final JsonObject reg = round.getAsJsonObject("r5").getAsJsonObject("regioni");
    if (reg == null || !reg.has(nation)) {
      return Double.NaN;
    }
    final JsonObject n = reg.getAsJsonObject(nation);
    return n.has(key) ? n.get(key).getAsDouble() : 0;
  }

  static String proprietario(final JsonObject j, final int r, final String city) {
    final JsonObject round = round(j, r);
    if (round == null || !round.has("r4")) {
      return null;
    }
    final JsonObject owners = round.getAsJsonObject("r4").getAsJsonObject("proprietari");
    return owners.has(city) ? owners.get(city).getAsString() : null;
  }

  static String alleanza(final JsonObject j, final String nation) {
    if (j.has("alleanze")) {
      for (final var e : j.getAsJsonObject("alleanze").entrySet()) {
        for (final JsonElement m : e.getValue().getAsJsonArray()) {
          if (m.getAsString().equals(nation)) {
            return e.getKey();
          }
        }
      }
    }
    return nation;
  }

  static List<Double> values(final List<JsonObject> list, final ToDoubleFunction<JsonObject> f) {
    final List<Double> out = new ArrayList<>();
    for (final JsonObject j : list) {
      final double v = f.applyAsDouble(j);
      if (!Double.isNaN(v)) {
        out.add(v);
      }
    }
    return out;
  }

  static double mean(final List<Double> v) {
    return v.stream().mapToDouble(d -> d).average().orElse(Double.NaN);
  }

  static String num1(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.1f", x).replace('.', ',');
  }

  static String num0(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.0f", x);
  }
}
