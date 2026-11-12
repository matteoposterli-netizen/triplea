package org.triplea.proplus.autoplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step R6: the subsection "R6" of "Partite complete" (games played until the victory): outcomes
 * with f1 against (a), f3 against e2 and g against e2; the squadron (departure, arrival,
 * composition, PUs of rounds 1-4); Gibraltar; the program's landings; the pressure on the Axis;
 * Russia; the transports and the losses at sea; the program's share of the PUs from round 5. The
 * siege tables of the Axis at share 80 are R4's ("Per configurazione (step R4)"), next to share 70.
 */
final class ConfrontoR6 {
  private ConfrontoR6() {}

  static final String SQUADRA = "[con squadra_gibilterra]";
  static final String ASSEDIO = "[con assedio]";
  static final String QUOTA80 = "[quota 80]";
  static final List<Integer> ROUNDS_GIBILTERRA = List.of(4, 5, 6, 8, 12, 16, 20);

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
    if (byConfig.keySet().stream().noneMatch(c -> c.contains(SQUADRA) || c.contains(QUOTA80))) {
      return;
    }
    final String reference =
        String.join(" / ", alliances.stream().map(a -> ConfrontoHard.HARD).toList());
    final List<String> configs = new ArrayList<>(byConfig.keySet());
    configs.sort((a, b) -> Integer.compare(ordine(a, reference), ordine(b, reference)));
    md.append(
"""

### R6

Configurazioni: (a) Hard vs Hard; (f1) Hard vs Alleati con impegno + squadra_gibilterra; (e2) Asse \
con impegno + assedio (quota 70) vs Hard; (f3) Asse con impegno + assedio vs Alleati con impegno + \
squadra_gibilterra; (g) Asse con impegno + assedio a quota 80 vs Hard. Con squadra_gibilterra solo gli \
USA giocano l'impegno: Regno Unito, Russia e Francia giocano come Hard. "Programma" = sbarchi del \
registro (ondate USA); "Hard" = sbarchi alleati contati dal monitor meno quelli del programma.

""");
    esiti(md, configs, byConfig, reference);
    final List<String> withSquadron = configs.stream().filter(c -> c.contains(SQUADRA)).toList();
    squadra(md, withSquadron, byConfig);
    gibilterra(md, configs, byConfig);
    ondate(md, withSquadron, byConfig);
    ConfrontoR5.sbarchi(md, configs, byConfig);
    ConfrontoR5.terra(md, configs, byConfig);
    ConfrontoR5.russiaCitta(md, configs, byConfig);
    ConfrontoR5.trasporti(md, configs, byConfig);
    quota(md, withSquadron, byConfig);
  }

  private static int ordine(final String config, final String reference) {
    if (config.equals(reference)) {
      return 0;
    }
    final boolean squadra = config.contains(SQUADRA);
    final boolean assedio = config.contains(ASSEDIO);
    if (squadra && !assedio) {
      return 1;
    }
    if (assedio && !squadra && !config.contains(QUOTA80)) {
      return 2;
    }
    if (assedio && squadra) {
      return 3;
    }
    return config.contains(QUOTA80) ? 4 : 5;
  }

  /** The configuration f1, f3 or g is compared with, and the side; null when none. */
  static String[] confronto(final String config, final List<String> configs, final String ref) {
    final boolean squadra = config.contains(SQUADRA);
    final boolean assedio = config.contains(ASSEDIO);
    if (squadra && !assedio) {
      return configs.contains(ref) ? new String[] {ref, "Allies", "f1 contro (a)"} : null;
    }
    final String e2 =
        configs.stream()
            .filter(c -> c.contains(ASSEDIO) && !c.contains(SQUADRA) && !c.contains(QUOTA80))
            .findFirst()
            .orElse(null);
    if (e2 == null) {
      return null;
    }
    if (squadra) {
      return new String[] {e2, "Allies", "f3 contro e2"};
    }
    if (config.contains(QUOTA80)) {
      return new String[] {e2, "Axis", "g contro e2"};
    }
    return null;
  }

  // ============================================================================== outcomes

  private static void esiti(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig,
      final String reference) {
    md.append(
"""
Vittorie con l'intervallo al 95% di Wilson; p = test esatto di Fisher a due code sulle vittorie del lato che cambia: f1 contro (a) e f3 contro e2 sugli Alleati, g contro e2 sull'Asse.

| Configurazione | Partite | Vittorie Asse | Vittorie Alleati | Stalli | Quota Asse [IC 95%] | Quota Alleati [IC 95%] | Confronto | p | Round vittoria mediana (min-max) | Round vittoria Alleati mediana | Round vittoria Asse mediana |
|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      final int n = list.size();
      final int axis = ConfrontoR4.wins(list, "Axis");
      final int allies = ConfrontoR4.wins(list, "Allies");
      final String[] other = confronto(config, configs, reference);
      String p = "-";
      String vs = "-";
      if (other != null) {
        final List<JsonObject> o = byConfig.get(other[0]);
        final int mine = "Axis".equals(other[1]) ? axis : allies;
        p = ConfrontoR5.pText(ConfrontoR4.fisher(mine, n, ConfrontoR4.wins(o, other[1]), o.size()));
        vs = other[2] + " (" + ("Axis".equals(other[1]) ? "Asse" : "Alleati") + ")";
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
          .append(ConfrontoR5.roundText(list, null))
          .append(" | ")
          .append(ConfrontoR5.roundText(list, "Allies"))
          .append(" | ")
          .append(ConfrontoR5.roundText(list, "Axis"))
          .append(" |\n");
    }
  }

  // ============================================================================== squadron

  /** The first decision of {@code tipo} in the game's register, null without. */
  static JsonObject prima(final JsonObject j, final String tipo) {
    final List<JsonObject> d = ConfrontoR5.decisioni(j, tipo);
    return d.isEmpty() ? null : d.get(0);
  }

  /** PUs of the program and of the turn in the building rounds (squadra_costruzione rows). */
  static double[] puCostruzione(final JsonObject j) {
    double spent = 0;
    double turn = 0;
    for (final JsonObject d : ConfrontoR5.decisioni(j, "squadra_costruzione")) {
      final JsonObject a = ConfrontoR5.alt0(d);
      if (a != null) {
        spent += a.has("pu_spesi") ? a.get("pu_spesi").getAsDouble() : 0;
        turn += a.has("pu_turno") ? a.get("pu_turno").getAsDouble() : 0;
      }
    }
    return new double[] {spent, turn};
  }

  static String composizione(final JsonObject a) {
    if (a == null) {
      return "-";
    }
    final List<String> parts = new ArrayList<>();
    if (a.has("navi")) {
      a.getAsJsonObject("navi")
          .entrySet()
          .forEach(e -> parts.add(e.getValue().getAsInt() + " " + e.getKey()));
    }
    if (a.has("aerei")) {
      a.getAsJsonObject("aerei")
          .entrySet()
          .forEach(e -> parts.add(e.getValue().getAsInt() + " " + e.getKey()));
    }
    return String.join(", ", parts);
  }

  private static double num(final JsonObject a, final String key) {
    return a != null && a.has(key) ? a.get(key).getAsDouble() : Double.NaN;
  }

  private static void squadra(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    if (configs.isEmpty()) {
      return;
    }
    md.append(
"""

Squadra di Gibilterra per partita: round di partenza e di arrivo nella zona di Gibraltar, composizione all'arrivo (navi e aerei nella zona, trasporti carichi, unita' di terra a bordo), PU spesi dal programma nei round 1-4 sul totale dei PU dei round 1-4.

| Configurazione | Seme | Partenza | Arrivo | Composizione all'arrivo | Trasporti carichi | Unita' di terra | PU R1-4 programma / totale | Esito |
|---|---|---|---|---|---|---|---|---|
""");
    for (final String config : configs) {
      for (final JsonObject j : byConfig.get(config)) {
        final JsonObject dep = prima(j, "squadra_partenza");
        final JsonObject arr = prima(j, "squadra_arrivo");
        final JsonObject a = arr == null ? null : ConfrontoR5.alt0(arr);
        final double[] pu = puCostruzione(j);
        md.append("| ")
            .append(config)
            .append(" | ")
            .append(seme(j))
            .append(" | ")
            .append(dep == null ? "-" : "R" + dep.get("round").getAsInt())
            .append(" | ")
            .append(arr == null ? "mai" : "R" + arr.get("round").getAsInt())
            .append(" | ")
            .append(composizione(a))
            .append(" | ")
            .append(ConfrontoR5.num0(num(a, "trasporti_carichi")))
            .append(" | ")
            .append(ConfrontoR5.num0(num(a, "unita_di_terra")))
            .append(" | ")
            .append(ConfrontoR5.num0(pu[0]))
            .append(" / ")
            .append(ConfrontoR5.num0(pu[1]))
            .append(" | ")
            .append(vincitore(j))
            .append(" |\n");
      }
    }
    md.append(
"""

Distribuzione del round di arrivo (partite) e medie della composizione all'arrivo.

""");
    md.append(
"""
| Configurazione | Arrivo per round | Mai arrivata | Navi e aerei (media) | Trasporti carichi (media) | Unita' di terra (media) | PU R1-4 al programma |
|---|---|---|---|---|---|---|
""");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      final Map<Integer, Integer> dist = new TreeMap<>();
      int never = 0;
      final List<Double> ships = new ArrayList<>();
      final List<Double> loaded = new ArrayList<>();
      final List<Double> land = new ArrayList<>();
      double spent = 0;
      double total = 0;
      for (final JsonObject j : list) {
        final JsonObject arr = prima(j, "squadra_arrivo");
        final double[] pu = puCostruzione(j);
        spent += pu[0];
        total += pu[1];
        if (arr == null) {
          never++;
          continue;
        }
        dist.merge(arr.get("round").getAsInt(), 1, Integer::sum);
        final JsonObject a = ConfrontoR5.alt0(arr);
        double n = 0;
        if (a != null) {
          for (final String k : List.of("navi", "aerei")) {
            if (a.has(k)) {
              for (final var e : a.getAsJsonObject(k).entrySet()) {
                n += e.getValue().getAsInt();
              }
            }
          }
        }
        ships.add(n);
        loaded.add(num(a, "trasporti_carichi"));
        land.add(num(a, "unita_di_terra"));
      }
      final StringBuilder d = new StringBuilder();
      dist.forEach(
          (r, n) -> d.append(d.isEmpty() ? "" : ", ").append("R").append(r).append(": ").append(n));
      md.append("| ")
          .append(config)
          .append(" | ")
          .append(d.isEmpty() ? "-" : d)
          .append(" | ")
          .append(never)
          .append(" | ")
          .append(ConfrontoR5.num1(ConfrontoR5.mean(ships)))
          .append(" | ")
          .append(ConfrontoR5.num1(ConfrontoR5.mean(loaded)))
          .append(" | ")
          .append(ConfrontoR5.num1(ConfrontoR5.mean(land)))
          .append(" | ")
          .append(total > 0 ? ConfrontoR5.num0(100 * spent / total) + "%" : "-")
          .append(" |\n");
    }
  }

  private static String seme(final JsonObject j) {
    final JsonObject p = j.getAsJsonObject("parametri");
    return p.has("seme") ? p.get("seme").getAsString() : "-";
  }

  private static String vincitore(final JsonObject j) {
    if (j.has("vittoria") && j.getAsJsonObject("vittoria").has("vincitore")) {
      final String w = j.getAsJsonObject("vittoria").get("vincitore").getAsString();
      final JsonObject e = j.getAsJsonObject("esito");
      return ("Axis".equals(w) ? "Asse" : "Alleati")
          + (e.has("round_vittoria") ? " R" + e.get("round_vittoria").getAsInt() : "");
    }
    return "stallo";
  }

  // ============================================================================= Gibraltar

  private static void gibilterra(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
        "\nGibilterra: proprietario a fine round (partite con Gibilterra alleata / partite al"
            + " round); battaglie navali della squadra e dello snodo (decise, mare liberato, unita'"
            + " USA perse, TUV nemico perso: medie a partita); sbarchi per prenderla; round in cui"
            + " diventa base (arrivo della squadra, mediana).\n\n| Configurazione |");
    ROUNDS_GIBILTERRA.forEach(r -> md.append(" Alleata R").append(r).append(" |"));
    md.append(
        " Battaglie decise | Mare liberato | Unita' USA perse | TUV nemico perso | Sbarchi per"
            + " prenderla | Base dal round |\n|---|");
    ROUNDS_GIBILTERRA.forEach(r -> md.append("---|"));
    md.append("---|---|---|---|---|---|\n");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config);
      for (final int r : ROUNDS_GIBILTERRA) {
        int allied = 0;
        int seen = 0;
        for (final JsonObject j : list) {
          final String owner = ConfrontoR5.proprietario(j, r, "Gibilterra");
          if (owner != null) {
            seen++;
            allied += "Allies".equals(ConfrontoR5.alleanza(j, owner)) ? 1 : 0;
          }
        }
        md.append(" | ").append(allied).append('/').append(seen);
      }
      final List<Double> base = new ArrayList<>();
      for (final JsonObject j : list) {
        final JsonObject arr = prima(j, "squadra_arrivo");
        if (arr != null) {
          base.add(arr.get("round").getAsDouble());
        }
      }
      md.append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(
                          list,
                          j -> ConfrontoR5.conta(j, "battaglia_gibilterra", null, "BATTAGLIA")))))
          .append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(
                          list,
                          j -> ConfrontoR5.esiti(j, "battaglia_gibilterra", "mare_liberato")))))
          .append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(
                          list,
                          j ->
                              esitiSomma(j, "battaglia_gibilterra", "unita_usa_perse")
                                  + esitiSomma(j, "battaglia_gibilterra", "navi_usa_perse")))))
          .append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(
                          list, j -> esitiSomma(j, "battaglia_gibilterra", "tuv_nemico_perso")))))
          .append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(
                          list, j -> ConfrontoR5.conta(j, "presa_gibilterra", null, "PRESA")))))
          .append(" | ")
          .append(
              base.isEmpty()
                  ? "-"
                  : base.size() + " partite, R" + ConfrontoR5.num0(ConfrontoR4.median(base)))
          .append(" |\n");
    }
  }

  /** Sum of the numeric {@code key} of the outcome rows of {@code tipo} (>= 0 only). */
  static double esitiSomma(final JsonObject j, final String tipo, final String key) {
    if (ConfrontoR3a.registro(j).isEmpty()) {
      return Double.NaN;
    }
    double s = 0;
    for (final JsonElement e : ConfrontoR3a.registro(j)) {
      final JsonObject d = e.getAsJsonObject();
      if ("esito".equals(d.get("riga").getAsString())
          && tipo.equals(d.get("tipo").getAsString())
          && d.getAsJsonObject("esito").has(key)) {
        s += Math.max(0, d.getAsJsonObject("esito").get(key).getAsDouble());
      }
    }
    return s;
  }

  // ============================================================================== landings

  private static void ondate(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    if (configs.isEmpty()) {
      return;
    }
    final List<String> bands = List.of("R1-4", "R5", "R6", "R7", "R8", "R9-12", "R13-18", "R19+");
    md.append(
        "\nSbarchi USA del programma (registro, tipo \"sbarco\" eseguito): ondate a partita e"
            + " unita' per ondata, per round.\n\n| Configurazione | Misura |");
    bands.forEach(b -> md.append(' ').append(b).append(" |"));
    md.append(" Totale |\n|---|---|");
    bands.forEach(b -> md.append("---|"));
    md.append("---|\n");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      final Map<String, double[]> sum = new LinkedHashMap<>();
      bands.forEach(b -> sum.put(b, new double[2]));
      for (final JsonObject j : list) {
        for (final JsonObject d : ConfrontoR5.decisioni(j, "sbarco")) {
          final JsonObject a = ConfrontoR5.alt0(d);
          if (a == null || !a.has("unita_sbarcate") || a.get("unita_sbarcate").getAsInt() <= 0) {
            continue;
          }
          final double[] x = sum.get(banda(d.get("round").getAsInt()));
          x[0]++;
          x[1] += a.get("unita_sbarcate").getAsDouble();
        }
      }
      double waves = 0;
      double units = 0;
      md.append("| ").append(config).append(" | ondate a partita");
      for (final String b : bands) {
        waves += sum.get(b)[0];
        units += sum.get(b)[1];
        md.append(" | ").append(ConfrontoR5.num1(sum.get(b)[0] / Math.max(1, list.size())));
      }
      md.append(" | ").append(ConfrontoR5.num1(waves / Math.max(1, list.size()))).append(" |\n");
      md.append("| ").append(config).append(" | unita' per ondata");
      for (final String b : bands) {
        md.append(" | ")
            .append(sum.get(b)[0] == 0 ? "-" : ConfrontoR5.num1(sum.get(b)[1] / sum.get(b)[0]));
      }
      md.append(" | ").append(waves == 0 ? "-" : ConfrontoR5.num1(units / waves)).append(" |\n");
    }
  }

  static String banda(final int round) {
    if (round <= 4) {
      return "R1-4";
    }
    if (round <= 8) {
      return "R" + round;
    }
    if (round <= 12) {
      return "R9-12";
    }
    return round <= 18 ? "R13-18" : "R19+";
  }

  // ===================================================================== share of the PUs

  private static void quota(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    if (configs.isEmpty()) {
      return;
    }
    md.append(
"""

Programma USA dal round 5 (registro "acquisto_pressione"): quota dei PU del turno spesi dal programma (sostegno + spola), PU a sostegno e a spola, turni con sostegno, turni con coppie complete, vittoria nemica media sulla squadra prima e dopo il sostegno.

| Configurazione | Quota PU programma | PU sostegno a partita | PU spola a partita | Turni con sostegno | Turni con coppie | Vittoria nemica prima | Vittoria nemica dopo |
|---|---|---|---|---|---|---|---|
""");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      double spent = 0;
      double turn = 0;
      final List<Double> before = new ArrayList<>();
      final List<Double> after = new ArrayList<>();
      for (final JsonObject j : list) {
        for (final JsonObject d : ConfrontoR5.decisioni(j, "acquisto_pressione")) {
          final JsonObject a = ConfrontoR5.alt0(d);
          if (a == null) {
            continue;
          }
          spent += num0(a, "pu_sostegno") + num0(a, "pu_spola");
          turn += num0(a, "pu_turno");
          if (a.has("vittoria_nemica_prima_pct")) {
            before.add(a.get("vittoria_nemica_prima_pct").getAsDouble());
            after.add(a.get("vittoria_nemica_dopo_pct").getAsDouble());
          }
        }
      }
      md.append("| ")
          .append(config)
          .append(" | ")
          .append(turn > 0 ? ConfrontoR5.num0(100 * spent / turn) + "%" : "-")
          .append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(
                          list,
                          j ->
                              ConfrontoR5.sommaAlt(
                                  j, "acquisto_pressione", "Americans", "pu_sostegno")))))
          .append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(
                          list,
                          j ->
                              ConfrontoR5.sommaAlt(
                                  j, "acquisto_pressione", "Americans", "pu_spola")))))
          .append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(
                          list,
                          j -> ConfrontoR5.conta(j, "acquisto_pressione", null, "SOSTEGNO")))))
          .append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(
                          list,
                          j -> ConfrontoR5.conta(j, "acquisto_pressione", null, "SPOLA_COPPIE")))))
          .append(" | ")
          .append(ConfrontoR5.num1(ConfrontoR5.mean(before)))
          .append(" | ")
          .append(ConfrontoR5.num1(ConfrontoR5.mean(after)))
          .append(" |\n");
    }
  }

  private static double num0(final JsonObject a, final String key) {
    return a.has(key) ? a.get(key).getAsDouble() : 0;
  }
}
