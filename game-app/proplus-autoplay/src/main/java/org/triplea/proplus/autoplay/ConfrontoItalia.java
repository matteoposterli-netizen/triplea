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
 * Steps R6c and R6b-bis: Italy's Africa shuttle in convoy ({@code italia_convoglio}). Outcomes with
 * Fisher on the Axis (h against e2, i against f3), then per configuration with the siege: units
 * brought to Africa by sea, turns with the route safe, purchases by type, Italian transports and
 * losses, troops left at the port, the Axis' African territories, North Africa whole, Egypt,
 * Italy's income and bonuses, Rome; in the configurations with the US squadron the US attacks on
 * the Italian convoys. Subsection "R6c" (seeds below 1301) or "R6b-bis".
 */
final class ConfrontoItalia {
  private ConfrontoItalia() {}

  static final String CONVOGLIO = "[con italia_convoglio]";
  static final List<Integer> ROUNDS = List.of(4, 8, 12, 16);

  static boolean applicabile(final List<Game> games) {
    for (final Game g : games) {
      if (g.json() != null && !ConfrontoR5.decisioni(g.json(), "italia_convoglio").isEmpty()) {
        return true;
      }
    }
    return false;
  }

  static void write(final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    int minSeed = Integer.MAX_VALUE;
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      final JsonObject p = g.json().getAsJsonObject("parametri");
      if (!p.has("fino_a_vittoria") || !p.get("fino_a_vittoria").getAsBoolean()) {
        continue;
      }
      if (p.has("seme")) {
        minSeed = Math.min(minSeed, p.get("seme").getAsInt());
      }
      byConfig
          .computeIfAbsent(
              RiepilogoGenerale.configuration(g.json(), alliances), k -> new ArrayList<>())
          .add(g.json());
    }
    final List<String> configs =
        byConfig.keySet().stream().filter(c -> c.contains(ConfrontoR6.ASSEDIO)).toList();
    if (configs.isEmpty()) {
      return;
    }
    final String title = minSeed >= 1301 ? "R6b-bis" : "R6c";
    md.append("\n### ").append(title).append("\n\n");
    md.append(
        "Asse con impegno + assedio (quota 70), con e senza italia_convoglio, contro Hard"
            + (configs.stream().anyMatch(c -> c.contains(ConfrontoR6.SQUADRA))
                ? " e contro gli Alleati con impegno + squadra_gibilterra"
                : "")
            + ". Vittorie con IC 95% di Wilson; p = Fisher a due code sulle vittorie dell'Asse:"
            + " convoglio contro la stessa configurazione senza convoglio.\n\n");
    md.append(
        "| Configurazione | Partite | Asse | Alleati | Stalli | Quota Asse | Confronto | p | Round"
            + " vittoria mediana |\n|---|---|---|---|---|---|---|---|---|\n");
    for (final String c : configs) {
      final List<JsonObject> list = byConfig.get(c);
      final int n = list.size();
      final int axis = ConfrontoR4.wins(list, "Axis");
      final int allies = ConfrontoR4.wins(list, "Allies");
      String vs = "-";
      String p = "-";
      if (c.contains(CONVOGLIO)) {
        final String base = senzaConvoglio(c);
        if (byConfig.containsKey(base)) {
          final List<JsonObject> o = byConfig.get(base);
          p =
              ConfrontoR5.pText(
                  ConfrontoR4.fisher(axis, n, ConfrontoR4.wins(o, "Axis"), o.size()));
          vs = "contro senza convoglio";
        }
      }
      md.append("| ")
          .append(c)
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
          .append(vs)
          .append(" | ")
          .append(p)
          .append(" | ")
          .append(ConfrontoR5.roundText(list, null))
          .append(" |\n");
    }
    italia(md, configs, byConfig);
    attacchi(md, configs, byConfig);
  }

  /** The same configuration without the convoy (h -> e2, i -> f3). */
  static String senzaConvoglio(final String config) {
    return config.replace(" " + CONVOGLIO, "").replace(CONVOGLIO, "");
  }

  // ================================================================================ Italy

  private static void italia(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
        "\nItalia, medie a partita: unita' portate in Africa via mare (monitor, scaricate da"
            + " trasporti in territori africani) per banda e totale; turni con la rotta sicura /"
            + " turni di acquisto del convoglio; acquisti della quota per tipo (registro"
            + " italia_africa); trasporti italiani a R8/R16 e persi; partenze rinunciate (convoglio"
            + " FERMO); truppe ferme al porto dopo il carico (media per turno).\n\n");
    final List<String> bands = ConfrontoR6b.BANDE;
    md.append("| Configurazione |");
    bands.forEach(b -> md.append(" Africa ").append(b).append(" |"));
    md.append(
        " Africa totale | Rotta sicura | Acquisti per tipo | Trasporti R8 / R16 | Trasporti persi |"
            + " Convogli fermi | Truppe ferme al porto |\n|---|");
    bands.forEach(b -> md.append("---|"));
    md.append("---|---|---|---|---|---|---|\n");
    for (final String c : configs) {
      final List<JsonObject> list = byConfig.get(c);
      final double n = Math.max(1, list.size());
      final double[] africa = new double[bands.size()];
      double safe = 0;
      double buyTurns = 0;
      double stopped = 0;
      double left = 0;
      double moveTurns = 0;
      final Map<String, Double> bought = new TreeMap<>();
      for (final JsonObject j : list) {
        for (final JsonElement e : ConfrontoR5.rounds(j)) {
          final int r = e.getAsJsonObject().get("round").getAsInt();
          final double x = ConfrontoR5.nazione(j, r, "Italians", "portate_africa");
          if (!Double.isNaN(x)) {
            africa[bands.indexOf(ConfrontoR6.banda(r))] += x;
          }
        }
        for (final JsonObject d : ConfrontoR5.decisioni(j, "italia_convoglio")) {
          final JsonObject a = ConfrontoR5.alt0(d);
          if (a == null || !a.has("fase")) {
            continue;
          }
          if ("acquisto".equals(a.get("fase").getAsString())) {
            buyTurns++;
            safe += a.has("rotta_sicura") && a.get("rotta_sicura").getAsBoolean() ? 1 : 0;
          } else if ("movimento".equals(a.get("fase").getAsString())) {
            moveTurns++;
            left += a.has("truppe_rimaste_al_porto") ? a.get("truppe_rimaste_al_porto").getAsDouble() : 0;
          }
        }
        for (final JsonObject d : ConfrontoR5.decisioni(j, "trasporti_sicurezza")) {
          if ("Italians".equals(d.get("nazione").getAsString()) && "FERMO".equals(ConfrontoR5.codice(d))) {
            stopped++;
          }
        }
        for (final JsonObject d : ConfrontoR5.decisioni(j, "italia_africa")) {
          final JsonObject a = ConfrontoR5.alt0(d);
          if (a != null && a.has("ordine")) {
            a.getAsJsonObject("ordine")
                .entrySet()
                .forEach(e -> bought.merge(e.getKey(), e.getValue().getAsDouble(), Double::sum));
          }
        }
      }
      md.append("| ").append(c);
      double tot = 0;
      for (final double x : africa) {
        md.append(" | ").append(ConfrontoR5.num1(x / n));
        tot += x;
      }
      final List<String> buys = new ArrayList<>();
      bought.forEach((k, v) -> buys.add(ConfrontoR5.num1(v / n) + " " + k));
      md.append(" | ")
          .append(ConfrontoR5.num1(tot / n))
          .append(" | ")
          .append(buyTurns == 0 ? "-" : ConfrontoR5.num0(safe) + " / " + ConfrontoR5.num0(buyTurns))
          .append(" | ")
          .append(buys.isEmpty() ? "-" : String.join(", ", buys))
          .append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(list, j -> ConfrontoR5.nazione(j, 8, "Italians", "trasporti")))))
          .append(" / ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(list, j -> ConfrontoR5.nazione(j, 16, "Italians", "trasporti")))))
          .append(" | ")
          .append(
              ConfrontoR5.num1(
                  ConfrontoR5.mean(
                      ConfrontoR5.values(
                          list, j -> ConfrontoR5.sommaNazione(j, "Italians", "trasporti_persi")))))
          .append(" | ")
          .append(buyTurns == 0 ? "-" : ConfrontoR5.num1(stopped / n))
          .append(" | ")
          .append(moveTurns == 0 ? "-" : ConfrontoR5.num1(left / moveTurns))
          .append(" |\n");
    }
    md.append(
        "\nAfrica e reddito: territori africani dell'Asse (livello 1 + livello 2 dell'assedio) a"
            + " R4/R8/R12/R16, round con l'obiettivo italiano North Africa attivo (media a"
            + " partita), partite con l'Egitto dell'Asse a R4/R8/R12/R16, reddito italiano e bonus"
            + " italiani attivi (PU) a R8/R12/R16, caduta di Roma.\n\n| Configurazione |");
    ROUNDS.forEach(r -> md.append(" Africa Asse R").append(r).append(" |"));
    md.append(" Round Nord Africa intero |");
    ROUNDS.forEach(r -> md.append(" Egitto Asse R").append(r).append(" |"));
    List.of(8, 12, 16).forEach(r -> md.append(" Reddito / bonus R").append(r).append(" |"));
    md.append(" Roma cade |\n|---|");
    for (int i = 0; i < ROUNDS.size() * 2 + 1 + 3 + 1; i++) {
      md.append("---|");
    }
    md.append('\n');
    for (final String c : configs) {
      final List<JsonObject> list = byConfig.get(c);
      md.append("| ").append(c);
      for (final int r : ROUNDS) {
        md.append(" | ")
            .append(
                ConfrontoR5.num1(
                    ConfrontoR5.mean(
                        ConfrontoR5.values(
                            list,
                            j ->
                                ConfrontoR5.r4(j, r, "africa_asse_livello1", null)
                                    + ConfrontoR5.r4(j, r, "africa_asse_livello2", null)))));
      }
      md.append(" | ")
          .append(ConfrontoR5.num1(ConfrontoR5.mean(ConfrontoR5.values(list, ConfrontoItalia::nordAfrica))));
      for (final int r : ROUNDS) {
        int axis = 0;
        int seen = 0;
        for (final JsonObject j : list) {
          final String o = ConfrontoR5.proprietario(j, r, "Egitto");
          if (o != null) {
            seen++;
            axis += "Axis".equals(ConfrontoR5.alleanza(j, o)) ? 1 : 0;
          }
        }
        md.append(" | ").append(axis).append('/').append(seen);
      }
      for (final int r : List.of(8, 12, 16)) {
        md.append(" | ")
            .append(
                ConfrontoR5.num1(
                    ConfrontoR5.mean(
                        ConfrontoR5.values(list, j -> ConfrontoR5.nazione(j, r, "Italians", "reddito")))))
            .append(" / ")
            .append(ConfrontoR5.num1(ConfrontoR5.mean(ConfrontoR5.values(list, j -> bonus(j, r)))));
      }
      md.append(" | ").append(ConfrontoR5.cambio(list, "Roma", 1)).append(" |\n");
    }
  }

  /** Rounds with Italy's "North Africa" objective active (all North Africa held). */
  static double nordAfrica(final JsonObject j) {
    double n = 0;
    for (final JsonElement e : ConfrontoR5.rounds(j)) {
      final JsonObject r = e.getAsJsonObject();
      if (!r.has("r4") || !r.getAsJsonObject("r4").has("obiettivi")) {
        continue;
      }
      final JsonObject it =
          r.getAsJsonObject("r4").getAsJsonObject("obiettivi").getAsJsonObject("Italians");
      if (it == null) {
        continue;
      }
      for (final var o : it.entrySet()) {
        if (o.getKey().contains("North_Africa") && o.getValue().getAsDouble() > 0) {
          n++;
        }
      }
    }
    return n;
  }

  static double bonus(final JsonObject j, final int round) {
    final JsonObject r = ConfrontoR5.round(j, round);
    if (r == null || !r.has("r4") || !r.getAsJsonObject("r4").has("obiettivi")) {
      return Double.NaN;
    }
    final JsonObject it = r.getAsJsonObject("r4").getAsJsonObject("obiettivi").getAsJsonObject("Italians");
    if (it == null) {
      return Double.NaN;
    }
    double s = 0;
    for (final var o : it.entrySet()) {
      s += o.getValue().getAsDouble();
    }
    return s;
  }

  // ================================================================ US attacks on convoys

  private static void attacchi(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
        "\nAttacchi subiti dalle navi italiane (registro attacco_subito del monitor), per"
            + " attaccante: attacchi a partita, attaccanti e difensori medi per attacco, perdite"
            + " italiane medie, trasporti italiani persi carichi / vuoti (somma a partita). Le zone"
            + " piu' colpite.\n\n| Configurazione | Attaccante | Attacchi a partita | Zone |"
            + " Attaccanti | Difensori | Perdite italiane | Trasporti persi carichi / vuoti |\n"
            + "|---|---|---|---|---|---|---|---|\n");
    for (final String c : configs) {
      final List<JsonObject> list = byConfig.get(c);
      final Map<String, List<JsonObject>> byAttacker = new TreeMap<>();
      for (final JsonObject j : list) {
        for (final JsonObject a : ConfrontoR6b.attacchi(j, "Italians")) {
          byAttacker
              .computeIfAbsent(a.get("attaccante").getAsString(), k -> new ArrayList<>())
              .add(a);
        }
      }
      if (byAttacker.isEmpty()) {
        md.append("| ").append(c).append(" | - | 0 | - | - | - | - | - |\n");
        continue;
      }
      for (final var e : byAttacker.entrySet()) {
        final List<JsonObject> rows = e.getValue();
        final Map<String, Integer> zones = new TreeMap<>();
        double loaded = 0;
        double empty = 0;
        for (final JsonObject a : rows) {
          zones.merge(a.get("zona").getAsString(), 1, Integer::sum);
          loaded += a.has("trasporti_persi_carichi") ? a.get("trasporti_persi_carichi").getAsDouble() : 0;
          empty += a.has("trasporti_persi_vuoti") ? a.get("trasporti_persi_vuoti").getAsDouble() : 0;
        }
        final List<String> top = new ArrayList<>();
        zones.entrySet().stream()
            .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
            .limit(3)
            .forEach(z -> top.add(z.getKey() + " " + z.getValue()));
        md.append("| ")
            .append(c)
            .append(" | ")
            .append(e.getKey())
            .append(" | ")
            .append(ConfrontoR5.num1(rows.size() / (double) Math.max(1, list.size())))
            .append(" | ")
            .append(String.join(", ", top))
            .append(" | ")
            .append(ConfrontoR6b.medieTipo(rows, "attaccanti"))
            .append(" | ")
            .append(ConfrontoR6b.medieTipo(rows, "difensori"))
            .append(" | ")
            .append(ConfrontoR6b.medieTipo(rows, "perdite_nazione"))
            .append(" | ")
            .append(ConfrontoR5.num1(loaded / Math.max(1, list.size())))
            .append(" / ")
            .append(ConfrontoR5.num1(empty / Math.max(1, list.size())))
            .append(" |\n");
      }
    }
  }
}
