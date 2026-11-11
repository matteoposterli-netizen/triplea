package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step R4 "STRATEGIA COMPLETA": the tables of the complete games per configuration (inside "Partite
 * complete"): wins with Wilson's 95% interval and Fisher's exact test, round of the victory,
 * duration; Axis VC, income and army per nation; round of fall of the cities; the siege (factories,
 * Moscow's factory, bombing); Italy in Africa; the national objectives; the Allies' hub. Every
 * table is written from the games' summaries (Monitor's {@code r4} and the register).
 */
final class ConfrontoR4 {
  private ConfrontoR4() {}

  static final List<Integer> ROUNDS = List.of(4, 8, 12, 16, 20);
  static final List<Integer> ROUNDS_ALLEATI = List.of(6, 9, 12, 16);
  static final List<Integer> ROUNDS_ITALIA = List.of(4, 8, 12, 16);
  static final List<String> NATIONS =
      List.of("Germans", "Italians", "Russians", "British", "Americans", "French");
  static final String ASSEDIO = " [con assedio]";
  static final String ALLEATI = " [con alleati_r4]";

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
    if (byConfig.isEmpty()) {
      return;
    }
    final String reference =
        String.join(" / ", alliances.stream().map(a -> ConfrontoHard.HARD).toList());
    final List<String> configs = new ArrayList<>(byConfig.keySet());
    configs.sort((a, b) -> a.equals(reference) ? -1 : b.equals(reference) ? 1 : 0);
    md.append(
"""

### Per configurazione (step R4)

""");
    esiti(md, configs, byConfig, reference, alliances);
    andamento(md, configs, byConfig);
    cadute(md, configs, byConfig);
    assedio(md, configs, byConfig);
    italia(md, configs, byConfig);
    bonus(md, configs, byConfig);
    alleati(md, configs, byConfig);
  }

  // ============================================================================== outcomes

  private static void esiti(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig,
      final String reference,
      final List<String> alliances) {
    md.append(
        "Quota di vittorie con l'intervallo al 95% di Wilson; p = test esatto di Fisher a due code"
            + " sulle vittorie del lato che cambia (Asse per le configurazioni dell'Asse, Alleati per"
            + " quelle alleate) contro (a) Hard vs Hard e contro la versione attuale (senza"
            + " l'interruttore R4). Round della vittoria = lettura delle note (condizione tenuta un"
            + " round intero); \"XML\" = primo fine round con la condizione vera.\n\n");
    md.append(
"""
| Configurazione | Partite | Vittorie Asse | Vittorie Alleati | Stalli | Quota Asse [IC 95%] | Quota Alleati [IC 95%] | p da (a) | p nuova vs attuale | Round vittoria mediana (min-max) | Round XML mediana | Durata media |
|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    final List<JsonObject> ref = byConfig.get(reference);
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      final int n = list.size();
      final int axis = wins(list, "Axis");
      final int allies = wins(list, "Allies");
      final String side = sideOf(config, list, alliances);
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
          .append(wilsonText(axis, n))
          .append(" | ")
          .append(wilsonText(allies, n))
          .append(" | ");
      if (config.equals(reference) || ref == null) {
        md.append("- | ");
      } else {
        md.append(pText(fisher(wins(list, side), n, wins(ref, side), ref.size())))
            .append(" (")
            .append(Riepilogo.allianceLabel(side))
            .append(") | ");
      }
      final String current = attuale(config);
      final List<JsonObject> cur = current == null ? null : byConfig.get(current);
      if (cur == null) {
        md.append("- | ");
      } else {
        md.append(pText(fisher(wins(list, side), n, wins(cur, side), cur.size())))
            .append(" (")
            .append(Riepilogo.allianceLabel(side))
            .append(") | ");
      }
      final List<Double> rounds = new ArrayList<>();
      final List<Double> xml = new ArrayList<>();
      for (final JsonObject j : list) {
        final JsonObject o = j.getAsJsonObject("esito");
        if (o.has("round_vittoria")) {
          rounds.add(o.get("round_vittoria").getAsDouble());
        }
        final double x = roundXml(j);
        if (x > 0) {
          xml.add(x);
        }
      }
      md.append(
              rounds.isEmpty()
                  ? "-"
                  : num0(median(rounds))
                      + " ("
                      + num0(rounds.stream().mapToDouble(d -> d).min().orElse(0))
                      + "-"
                      + num0(rounds.stream().mapToDouble(d -> d).max().orElse(0))
                      + ")")
          .append(" | ")
          .append(xml.isEmpty() ? "-" : num0(median(xml)))
          .append(" | ")
          .append(
              Riepilogo.duration(
                  Math.round(
                      list.stream()
                          .mapToDouble(j -> j.get("durata_s").getAsDouble())
                          .average()
                          .orElse(0))))
          .append(" |\n");
    }
  }

  /** The configuration without the R4 switch ("versione attuale"), if it is a new one. */
  static String attuale(final String config) {
    if (config.contains(ASSEDIO)) {
      return config.replace(ASSEDIO, "");
    }
    if (config.contains(ALLEATI)) {
      return config.replace(ALLEATI, "");
    }
    return null;
  }

  private static String sideOf(
      final String config, final List<JsonObject> list, final List<String> alliances) {
    final String side = ConfrontoHard.changedSide(list, alliances);
    if (side != null) {
      return side;
    }
    return config.contains(ALLEATI) ? "Allies" : "Axis";
  }

  static int wins(final List<JsonObject> list, final String alliance) {
    int n = 0;
    for (final JsonObject j : list) {
      if (j.has("vittoria")
          && j.getAsJsonObject("vittoria").has("vincitore")
          && alliance.equals(j.getAsJsonObject("vittoria").get("vincitore").getAsString())) {
        n++;
      }
    }
    return n;
  }

  private static double roundXml(final JsonObject j) {
    if (!j.has("vittoria") || !j.getAsJsonObject("vittoria").has("lettura_xml")) {
      return -1;
    }
    double best = -1;
    for (final var e : j.getAsJsonObject("vittoria").getAsJsonObject("lettura_xml").entrySet()) {
      final double r = e.getValue().getAsDouble();
      if (r > 0 && (best < 0 || r < best)) {
        best = r;
      }
    }
    return best;
  }

  // ======================================================================== statistics

  /** Wilson's 95% interval of {@code k} successes in {@code n}: {low, high} in [0, 1]. */
  static double[] wilson(final int k, final int n) {
    if (n == 0) {
      return new double[] {0, 0};
    }
    final double z = 1.959_963_984_540_054;
    final double p = (double) k / n;
    final double den = 1 + z * z / n;
    final double centre = (p + z * z / (2.0 * n)) / den;
    final double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / den;
    return new double[] {Math.max(0, centre - half), Math.min(1, centre + half)};
  }

  static String wilsonText(final int k, final int n) {
    if (n == 0) {
      return "-";
    }
    final double[] w = wilson(k, n);
    return String.format(Locale.ROOT, "%.0f%% [%.0f; %.0f]", 100.0 * k / n, 100 * w[0], 100 * w[1]);
  }

  /**
   * Fisher's exact test, two-sided, on the table [[a, n1-a], [b, n2-b]]: the sum of the
   * probabilities of the tables with the same margins no more likely than the observed one.
   */
  static double fisher(final int a, final int n1, final int b, final int n2) {
    final int k = a + b;
    final int n = n1 + n2;
    final double observed = hyper(a, n1, n2, k, n);
    double p = 0;
    for (int x = Math.max(0, k - n2); x <= Math.min(k, n1); x++) {
      final double px = hyper(x, n1, n2, k, n);
      if (px <= observed * (1 + 1e-7)) {
        p += px;
      }
    }
    return Math.min(1, p);
  }

  private static double hyper(final int x, final int n1, final int n2, final int k, final int n) {
    return Math.exp(logC(n1, x) + logC(n2, k - x) - logC(n, k));
  }

  private static double logC(final int n, final int k) {
    if (k < 0 || k > n) {
      return Double.NEGATIVE_INFINITY;
    }
    return logFact(n) - logFact(k) - logFact(n - k);
  }

  private static double logFact(final int n) {
    double s = 0;
    for (int i = 2; i <= n; i++) {
      s += Math.log(i);
    }
    return s;
  }

  private static String pText(final double p) {
    return p < 0.001 ? "p < 0,001" : String.format(Locale.ROOT, "p = %.3f", p).replace('.', ',');
  }

  static double median(final List<Double> values) {
    final List<Double> s = new ArrayList<>(values);
    s.sort(Double::compare);
    final int n = s.size();
    return n == 0 ? Double.NaN : n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
  }

  // ======================================================================= by round

  private static void andamento(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
        "\nVC dell'Asse, poi per nazione reddito / esercito (unita' di terra), media delle partite"
            + " arrivate a quel round (n tra parentesi):\n\n| Configurazione | Riga |");
    ROUNDS.forEach(r -> md.append(" R").append(r).append(" |"));
    md.append("\n|---|---|");
    ROUNDS.forEach(r -> md.append("---|"));
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" | VC Asse |");
      for (final int r : ROUNDS) {
        final List<Double> v = values(list, j -> ConfrontoImpegno.vcAt(j, "Axis", r));
        md.append(' ')
            .append(v.isEmpty() ? "-" : num1(mean(v)) + " (" + v.size() + ")")
            .append(" |");
      }
      md.append('\n');
      for (final String nation : NATIONS) {
        md.append("| ").append(config).append(" | ").append(nation).append(" |");
        for (final int r : ROUNDS) {
          final List<Double> inc =
              values(list, j -> ConfrontoImpegno.nation(j, nation, r, "reddito"));
          final List<Double> army = values(list, j -> r4(j, r, "esercito", nation));
          md.append(' ')
              .append(inc.isEmpty() ? "-" : num1(mean(inc)) + " / " + num1(mean(army)))
              .append(" |");
        }
        md.append('\n');
      }
    }
  }

  private static void cadute(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
        "\nRound di caduta (primo fine round in mano all'altra alleanza): partite in cui cade /"
            + " partite, mediana del round:\n\n| Configurazione |");
    MonitorR4.CITTA.keySet().forEach(c -> md.append(' ').append(c).append(" |"));
    md.append("\n|---|");
    MonitorR4.CITTA.keySet().forEach(c -> md.append("---|"));
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" |");
      for (final String city : MonitorR4.CITTA.keySet()) {
        final List<Double> rounds = new ArrayList<>();
        for (final JsonObject j : list) {
          final int r = caduta(j, city);
          if (r > 0) {
            rounds.add((double) r);
          }
        }
        md.append(' ')
            .append(rounds.size())
            .append('/')
            .append(list.size())
            .append(rounds.isEmpty() ? "" : " R" + num0(median(rounds)))
            .append(" |");
      }
      md.append('\n');
    }
  }

  /** First round whose end saw {@code city} in the hands of the alliance not holding it at R1. */
  static int caduta(final JsonObject j, final String city) {
    String first = null;
    for (final JsonElement e : rounds(j)) {
      final JsonObject round = e.getAsJsonObject();
      if (!round.has("r4")) {
        continue;
      }
      final JsonObject owners = round.getAsJsonObject("r4").getAsJsonObject("proprietari");
      if (!owners.has(city)) {
        continue;
      }
      final String alliance = alleanza(j, owners.get(city).getAsString());
      if (first == null) {
        first = alliance;
        continue;
      }
      if (!alliance.equals(first)) {
        return round.get("round").getAsInt();
      }
    }
    return -1;
  }

  private static String alleanza(final JsonObject j, final String nation) {
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

  // ========================================================================== siege

  private static void assedio(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
"""

Assedio: round di presa (primo fine round dell'Asse) delle tre fabbriche e di Mosca, partite / mediana; unita' piazzate dall'Asse nelle fabbriche catturate (media a partita); PU spesi in bombardieri e scorta (registro):

| Configurazione | Leningrado | Ucraina | Stalingrado | Mosca | Unita' nelle fabbriche catturate | PU in bombardieri |
|---|---|---|---|---|---|---|
""");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" |");
      for (final String city : List.of("Leningrado", "Ucraina", "Stalingrado", "Mosca")) {
        final List<Double> rounds = new ArrayList<>();
        list.forEach(
            j -> {
              final int r = caduta(j, city);
              if (r > 0) {
                rounds.add((double) r);
              }
            });
        md.append(' ')
            .append(rounds.size())
            .append('/')
            .append(list.size())
            .append(rounds.isEmpty() ? "" : " R" + num0(median(rounds)))
            .append(" |");
      }
      md.append(' ')
          .append(num1(mean(values(list, j -> sumR4(j, "prodotte_fabbriche_catturate")))))
          .append(" | ")
          .append(num1(mean(values(list, j -> sumRegistro(j, "bombardamento", "pu_spesi")))))
          .append(" |\n");
    }
    md.append(
        "\nPer round: danno fatto dai raid sulla fabbrica di Mosca (registro) / unita' prodotte da Mosca /"
            + " bombardieri mandati / persi (medie sulle partite arrivate al round):\n\n|"
            + " Configurazione |");
    final List<Integer> rr = List.of(2, 4, 6, 8, 10, 12, 14, 16, 18, 20);
    rr.forEach(r -> md.append(" R").append(r).append(" |"));
    md.append("\n|---|");
    rr.forEach(r -> md.append("---|"));
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" |");
      for (final int r : rr) {
        final List<Double> dmg =
            values(list, j -> roundEsito(j, r, "bombardamento", "danno_fatto"));
        final List<Double> prod = values(list, j -> r4(j, r, "prodotte_mosca", null));
        final List<Double> sent =
            values(list, j -> roundEsito(j, r, "bombardamento", "bombardieri"));
        final List<Double> lost =
            values(list, j -> roundEsito(j, r, "bombardamento", "bombardieri_persi"));
        md.append(' ')
            .append(
                prod.isEmpty()
                    ? "-"
                    : num1(mean(dmg))
                        + " / "
                        + num1(mean(prod))
                        + " / "
                        + num1(mean(sent))
                        + " / "
                        + num1(mean(lost)))
            .append(" |");
      }
      md.append('\n');
    }
  }

  // ========================================================================== Italy

  private static void italia(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
        "\nItalia: territori dell'Asse in Africa e a est dell'Egitto (livello 1 Egitto e Nord"
            + " Africa / livello 2 resto) e reddito italiano / britannico; PU spesi in navi"
            + " (registro, a partita); Gibilterra all'Asse:\n\n| Configurazione |");
    ROUNDS_ITALIA.forEach(
        r ->
            md.append(" R")
                .append(r)
                .append(" liv.1 / liv.2 | R")
                .append(r)
                .append(" reddito IT / UK |"));
    md.append(" PU in navi | Gibilterra all'Asse |\n|---|");
    ROUNDS_ITALIA.forEach(r -> md.append("---|---|"));
    md.append("---|---|\n");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" |");
      for (final int r : ROUNDS_ITALIA) {
        final List<Double> l1 = values(list, j -> r4(j, r, "africa_asse_livello1", null));
        final List<Double> l2 = values(list, j -> r4(j, r, "africa_asse_livello2", null));
        final List<Double> it =
            values(list, j -> ConfrontoImpegno.nation(j, "Italians", r, "reddito"));
        final List<Double> uk =
            values(list, j -> ConfrontoImpegno.nation(j, "British", r, "reddito"));
        md.append(' ')
            .append(l1.isEmpty() ? "-" : num1(mean(l1)) + " / " + num1(mean(l2)))
            .append(" | ")
            .append(it.isEmpty() ? "-" : num1(mean(it)) + " / " + num1(mean(uk)))
            .append(" |");
      }
      final List<Double> gib = new ArrayList<>();
      list.forEach(
          j -> {
            final int r = caduta(j, "Gibilterra");
            if (r > 0) {
              gib.add((double) r);
            }
          });
      md.append(' ')
          .append(num1(mean(values(list, j -> sumRegistroAlt(j, "italia_africa", "pu_navi")))))
          .append(" | ")
          .append(gib.size())
          .append('/')
          .append(list.size())
          .append(gib.isEmpty() ? "" : " R" + num0(median(gib)))
          .append(" |\n");
    }
  }

  // ========================================================================== bonus

  private static void bonus(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    final Map<String, String> objectives = new TreeMap<>();
    for (final List<JsonObject> list : byConfig.values()) {
      for (final JsonObject j : list) {
        for (final JsonElement e : rounds(j)) {
          final JsonObject round = e.getAsJsonObject();
          if (!round.has("r4") || !round.getAsJsonObject("r4").has("obiettivi")) {
            continue;
          }
          for (final var n : round.getAsJsonObject("r4").getAsJsonObject("obiettivi").entrySet()) {
            if (n.getValue().isJsonObject()) {
              n.getValue().getAsJsonObject().keySet().forEach(o -> objectives.put(o, n.getKey()));
            }
          }
        }
      }
    }
    if (objectives.isEmpty()) {
      return;
    }
    md.append(
        "\nObiettivi nazionali: round in cui l'obiettivo e' attivo a fine round / PU a partita"
            + " (somma dei valori attivi a fine round: approssimazione dell'incasso, che avviene a"
            + " fine turno della nazione), media sulle partite:\n\n| Nazione | Obiettivo |");
    configs.forEach(c -> md.append(' ').append(c).append(" |"));
    md.append("\n|---|---|");
    configs.forEach(c -> md.append("---|"));
    md.append('\n');
    for (final var e : objectives.entrySet()) {
      md.append("| ").append(e.getValue()).append(" | ").append(e.getKey()).append(" |");
      for (final String config : configs) {
        final List<JsonObject> list = byConfig.get(config);
        final List<Double> active = new ArrayList<>();
        final List<Double> pus = new ArrayList<>();
        for (final JsonObject j : list) {
          int a = 0;
          double pu = 0;
          for (final JsonElement re : rounds(j)) {
            final JsonObject round = re.getAsJsonObject();
            if (!round.has("r4")) {
              continue;
            }
            final JsonObject obj = round.getAsJsonObject("r4").getAsJsonObject("obiettivi");
            if (obj != null
                && obj.has(e.getValue())
                && obj.get(e.getValue()).isJsonObject()
                && obj.getAsJsonObject(e.getValue()).has(e.getKey())) {
              final double v = obj.getAsJsonObject(e.getValue()).get(e.getKey()).getAsDouble();
              if (v > 0) {
                a++;
                pu += v;
              }
            }
          }
          active.add((double) a);
          pus.add(pu);
        }
        md.append(' ')
            .append(num1(mean(active)))
            .append(" / ")
            .append(num0(mean(pus)))
            .append(" |");
      }
      md.append('\n');
    }
  }

  // ========================================================================= Allies

  private static void alleati(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
"""

Alleati: scelte dello snodo USA (round, somma sulle partite); sbarchi coordinati USA (numero, unita' sbarcate, presi, tenuti 2 round); unita' comprate in Sudafrica; neutrali presi; turni con lo sbarramento a sud attivo; unita' italiane nel sud della Russia (massimo a partita, media):

| Configurazione | Snodo AFRICA / FRANCIA / MISTO / attesa | Sbarchi | Unita' sbarcate | Presi | Tenuti 2 round | Comprate in Sudafrica | Neutrali presi | Sbarramento attivo | Italiani a sud della Russia |
|---|---|---|---|---|---|---|---|---|---|
""");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      final Map<String, Integer> hub = new LinkedHashMap<>();
      for (final String k : List.of("AFRICA", "FRANCIA", "MISTO", "attesa")) {
        hub.put(k, 0);
      }
      int landings = 0;
      double landed = 0;
      int taken = 0;
      int held2 = 0;
      double southAfrica = 0;
      int neutrals = 0;
      int barrier = 0;
      final List<Double> italians = new ArrayList<>();
      for (final JsonObject j : list) {
        final Map<String, JsonObject> outcomes = new HashMap<>();
        final TreeSet<String> neutralsTaken = new TreeSet<>();
        for (final JsonElement e : ConfrontoR3a.registro(j)) {
          final JsonObject d = e.getAsJsonObject();
          final String type = d.get("tipo").getAsString();
          if ("esito".equals(d.get("riga").getAsString())) {
            if ("sbarco".equals(type)) {
              final JsonObject x = d.getAsJsonObject("esito");
              if (x.has("preso") && x.get("preso").getAsBoolean() && !x.has("controllo")) {
                taken++;
              }
              if (x.has("round_dopo")
                  && x.get("round_dopo").getAsInt() == 2
                  && x.get("tenuto").getAsBoolean()) {
                held2++;
              }
            }
            outcomes.put(d.get("id").getAsString(), d);
            continue;
          }
          switch (type) {
            case "snodo_usa" ->
                hub.merge(
                    hub.containsKey(d.get("scelta").getAsString())
                        ? d.get("scelta").getAsString()
                        : "attesa",
                    1,
                    Integer::sum);
            case "sbarco" -> {
              final JsonObject a = d.getAsJsonArray("alternative").get(0).getAsJsonObject();
              if (a.has("unita_sbarcate")
                  && a.get("unita_sbarcate").getAsInt() > 0
                  && "Americans".equals(d.get("nazione").getAsString())) {
                landings++;
                landed += a.get("unita_sbarcate").getAsInt();
              }
            }
            case "africa_sudafrica" -> {
              final JsonObject o =
                  d.getAsJsonArray("alternative")
                      .get(0)
                      .getAsJsonObject()
                      .getAsJsonObject("ordine");
              if (o != null) {
                for (final var u : o.entrySet()) {
                  southAfrica += u.getValue().getAsInt();
                }
              }
            }
            case "neutrali_est" -> {
              for (final JsonElement a : d.getAsJsonArray("alternative")) {
                if (a.getAsJsonObject().get("preso").getAsBoolean()) {
                  neutralsTaken.add(a.getAsJsonObject().get("territorio").getAsString());
                }
              }
            }
            case "sbarramento_sud" -> barrier += "PRESIDIO".equals(codice(d)) ? 1 : 0;
            default -> {
              // other types are in the other tables
            }
          }
        }
        neutrals += neutralsTaken.size();
        double max = 0;
        for (final JsonElement e : rounds(j)) {
          final JsonObject round = e.getAsJsonObject();
          if (round.has("r4")) {
            max =
                Math.max(max, round.getAsJsonObject("r4").get("italiani_sud_russia").getAsDouble());
          }
        }
        italians.add(max);
      }
      md.append("| ")
          .append(config)
          .append(" | ")
          .append(String.join(" / ", hub.values().stream().map(String::valueOf).toList()))
          .append(" | ")
          .append(landings)
          .append(" | ")
          .append(num0(landed))
          .append(" | ")
          .append(taken)
          .append(" | ")
          .append(held2)
          .append(" | ")
          .append(num0(southAfrica))
          .append(" | ")
          .append(neutrals)
          .append(" | ")
          .append(barrier)
          .append(" | ")
          .append(num1(mean(italians)))
          .append(" |\n");
    }
    md.append(
        "\nUnita' di terra USA e britanniche per regione (base dello snodo o United Kingdom /"
            + " patria / mare / Europa occidentale / Mediterraneo e Nord Africa / altrove), media"
            + " delle partite arrivate al round; Nord Africa = territori dell'Asse:\n\n|"
            + " Configurazione | Nazione |");
    ROUNDS_ALLEATI.forEach(r -> md.append(" R").append(r).append(" |"));
    md.append("\n|---|---|");
    ROUNDS_ALLEATI.forEach(r -> md.append("---|"));
    md.append('\n');
    final List<String> regions =
        List.of(
            "base", "patria", "mare", "europa_occidentale", "mediterraneo_nord_africa", "altrove");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      for (final String nation : List.of("Americans", "British")) {
        md.append("| ").append(config).append(" | ").append(nation).append(" |");
        for (final int r : ROUNDS_ALLEATI) {
          final List<String> parts = new ArrayList<>();
          boolean any = false;
          for (final String reg : regions) {
            final List<Double> v = values(list, j -> regione(j, nation, r, reg));
            any |= !v.isEmpty();
            parts.add(v.isEmpty() ? "-" : num1(mean(v)));
          }
          md.append(' ').append(any ? String.join(" / ", parts) : "-").append(" |");
        }
        md.append('\n');
      }
      md.append("| ").append(config).append(" | Nord Africa |");
      for (final int r : ROUNDS_ALLEATI) {
        final List<Double> v = values(list, j -> nordAfrica(j, r));
        md.append(' ').append(v.isEmpty() ? "-" : num1(mean(v))).append(" |");
      }
      md.append('\n');
    }
  }

  // ======================================================================== helpers

  private static JsonArray rounds(final JsonObject j) {
    return j.has("round") ? j.getAsJsonArray("round") : new JsonArray();
  }

  private static JsonObject round(final JsonObject j, final int r) {
    for (final JsonElement e : rounds(j)) {
      if (e.getAsJsonObject().get("round").getAsInt() == r) {
        return e.getAsJsonObject();
      }
    }
    return null;
  }

  /**
   * A value of Monitor's r4 at round {@code r} ({@code sub}: a key inside an object), NaN if none.
   */
  private static double r4(final JsonObject j, final int r, final String key, final String sub) {
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

  private static double sumR4(final JsonObject j, final String key) {
    double s = 0;
    for (final JsonElement e : rounds(j)) {
      final JsonObject round = e.getAsJsonObject();
      if (round.has("r4") && round.getAsJsonObject("r4").has(key)) {
        s += round.getAsJsonObject("r4").get(key).getAsDouble();
      }
    }
    return s;
  }

  /** Sum of {@code parametri.key} over the decisions of {@code type}. */
  private static double sumRegistro(final JsonObject j, final String type, final String key) {
    double s = 0;
    for (final JsonElement e : ConfrontoR3a.registro(j)) {
      final JsonObject d = e.getAsJsonObject();
      if ("decisione".equals(d.get("riga").getAsString())
          && type.equals(d.get("tipo").getAsString())
          && d.has("parametri")
          && d.getAsJsonObject("parametri").has(key)) {
        s += d.getAsJsonObject("parametri").get(key).getAsDouble();
      }
    }
    return s;
  }

  /** Sum of {@code alternative[0].key} over the decisions of {@code type}. */
  private static double sumRegistroAlt(final JsonObject j, final String type, final String key) {
    double s = 0;
    for (final JsonElement e : ConfrontoR3a.registro(j)) {
      final JsonObject d = e.getAsJsonObject();
      if ("decisione".equals(d.get("riga").getAsString())
          && type.equals(d.get("tipo").getAsString())
          && d.getAsJsonArray("alternative").size() > 0) {
        final JsonObject a = d.getAsJsonArray("alternative").get(0).getAsJsonObject();
        if (a.has(key)) {
          s += a.get(key).getAsDouble();
        }
      }
    }
    return s;
  }

  /**
   * The outcome value {@code key} of the {@code type} rows of round {@code r} (summed); NaN if no
   * register.
   */
  private static double roundEsito(
      final JsonObject j, final int r, final String type, final String key) {
    final JsonArray reg = ConfrontoR3a.registro(j);
    if (reg.isEmpty() || round(j, r) == null) {
      return Double.NaN;
    }
    double s = 0;
    for (final JsonElement e : reg) {
      final JsonObject d = e.getAsJsonObject();
      if ("esito".equals(d.get("riga").getAsString())
          && type.equals(d.get("tipo").getAsString())
          && d.get("round").getAsInt() == r
          && d.getAsJsonObject("esito").has(key)) {
        s += d.getAsJsonObject("esito").get(key).getAsDouble();
      }
    }
    return s;
  }

  private static String codice(final JsonObject d) {
    return d.has("motivo") ? d.getAsJsonObject("motivo").get("codice").getAsString() : "";
  }

  private static double regione(
      final JsonObject j, final String nation, final int r, final String key) {
    final JsonObject round = round(j, r);
    if (round == null
        || !round.getAsJsonObject("nazioni").has(nation)
        || !round.getAsJsonObject("nazioni").getAsJsonObject(nation).has("regioni")) {
      return Double.NaN;
    }
    final JsonObject reg =
        round.getAsJsonObject("nazioni").getAsJsonObject(nation).getAsJsonObject("regioni");
    return reg.has(key) ? reg.get(key).getAsDouble() : 0;
  }

  private static double nordAfrica(final JsonObject j, final int r) {
    final JsonObject round = round(j, r);
    return round == null || !round.has("nord_africa_asse")
        ? Double.NaN
        : round.getAsJsonArray("nord_africa_asse").size();
  }

  private static List<Double> values(
      final List<JsonObject> list, final ToDoubleFunction<JsonObject> f) {
    final List<Double> out = new ArrayList<>();
    for (final JsonObject j : list) {
      final double v = f.applyAsDouble(j);
      if (!Double.isNaN(v)) {
        out.add(v);
      }
    }
    return out;
  }

  private static double mean(final List<Double> v) {
    return v.stream().mapToDouble(d -> d).average().orElse(Double.NaN);
  }

  private static String num1(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.1f", x).replace('.', ',');
  }

  private static String num0(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.0f", x);
  }
}
