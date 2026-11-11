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
import org.triplea.proplus.autoplay.ConfrontoConferma.Welch;
import org.triplea.proplus.autoplay.ConfrontoHard.Stat;

/**
 * Step R3a "SBARCO COORDINATO E TEATRI": the subsection "R3a" of the section "Impegno". Axis VC at
 * R4/R8/R12 against (a) with Welch's 95% interval; income and army per nation; the theater; the
 * coordinated landings and their outcome (per theater); where the US and British land units are;
 * North Africa; transports and the cap; the units held; the register (counts per type and reason,
 * calibration of the landings).
 */
final class ConfrontoR3a {
  private ConfrontoR3a() {}

  private static final List<String> NATIONS =
      List.of("Germans", "Italians", "Russians", "Americans", "British");
  private static final List<String> REGIONS =
      List.of(
          "base", "patria", "mare", "europa_occidentale", "mediterraneo_nord_africa", "altrove");

  static void write(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig,
      final String reference,
      final List<Integer> vcRounds) {
    final boolean any =
        configs.stream()
            .anyMatch(k -> byConfig.get(k).stream().anyMatch(j -> !registro(j).isEmpty()));
    if (!any) {
      return;
    }
    md.append(
"""

### R3a: sbarco coordinato e teatri

""");
    md.append(
        "Riferimento (a) = Hard vs Hard della stessa run. Il codice dell'Asse con impegno e' quello"
            + " di R2c (invariato). Le citta' passate di mano sono nella tabella della sezione"
            + " sopra.\n\n");
    final List<JsonObject> ref = byConfig.get(reference);
    final String axis = "Axis";

    // 1 VC and Δ
    md.append("| Configurazione | Partite |");
    vcRounds.forEach(r -> md.append(" VC Asse R").append(r).append(" | Δ da (a) [IC 95%] |"));
    md.append("\n|---|---|");
    vcRounds.forEach(r -> md.append("---|---|"));
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" | ").append(list.size()).append(" |");
      for (final int r : vcRounds) {
        final Stat s = Stat.of(list, j -> ConfrontoImpegno.vcAt(j, axis, r));
        md.append(' ').append(num(s.meanValue())).append(" ± ").append(s.sd()).append(" |");
        if (config.equals(reference)) {
          md.append(" - |");
        } else {
          final Welch w = Welch.of(s, Stat.of(ref, j -> ConfrontoImpegno.vcAt(j, axis, r)));
          md.append(' ')
              .append(w.deltaText())
              .append(' ')
              .append(w.intervalText())
              .append(
                  w.known() ? (w.low() > 0 || w.high() < 0 ? " esclude lo 0" : " include lo 0") : "")
              .append(" |");
        }
      }
      md.append('\n');
    }

    // 2 income and army per nation
    md.append("\nReddito / esercito (TUV) per nazione, media sui semi:\n\n| Configurazione | Nazione |");
    vcRounds.forEach(r -> md.append(" R").append(r).append(" reddito / TUV |"));
    md.append("\n|---|---|");
    vcRounds.forEach(r -> md.append("---|"));
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      for (final String n : NATIONS) {
        md.append("| ").append(config).append(" | ").append(n).append(" |");
        for (final int r : vcRounds) {
          md.append(' ')
              .append(num1(mean(list, j -> ConfrontoImpegno.nation(j, n, r, "reddito"))))
              .append(" / ")
              .append(num0(mean(list, j -> ConfrontoImpegno.nation(j, n, r, "tuv"))))
              .append(" |");
        }
        md.append('\n');
      }
    }

    // configurations with R3a decisions (the Allies with the commitment)
    final List<String> r3a =
        configs.stream()
            .filter(k -> byConfig.get(k).stream().anyMatch(j -> !registro(j).isEmpty()))
            .toList();

    // 3 theater
    md.append(
        "\nTeatro (scelta d'alleanza nel turno USA). Round per teatro e round in cui era aperto"
            + " (somma sulle partite); cambi col motivo:\n\n| Configurazione | Partite | Round NORD |"
            + " Round SUD | NORD aperto | SUD aperto | Cambi | Motivi delle scelte |\n"
            + "|---|---|---|---|---|---|---|---|\n");
    for (final String config : r3a) {
      final List<JsonObject> list = byConfig.get(config);
      int nord = 0;
      int sud = 0;
      int openN = 0;
      int openS = 0;
      int changes = 0;
      final Map<String, Integer> codes = new TreeMap<>();
      for (final JsonObject j : list) {
        String prev = null;
        for (final JsonObject d : decisioni(j, "teatro")) {
          final String t = d.get("scelta").getAsString();
          nord += "NORD".equals(t) ? 1 : 0;
          sud += "SUD".equals(t) ? 1 : 0;
          for (final JsonElement a : d.getAsJsonArray("alternative")) {
            final JsonObject x = a.getAsJsonObject();
            if (x.get("aperto").getAsBoolean()) {
              openN += "NORD".equals(x.get("teatro").getAsString()) ? 1 : 0;
              openS += "SUD".equals(x.get("teatro").getAsString()) ? 1 : 0;
            }
          }
          if (prev != null && !prev.equals(t)) {
            changes++;
          }
          prev = t;
          codes.merge(codice(d), 1, Integer::sum);
        }
      }
      md.append("| ")
          .append(config)
          .append(" | ")
          .append(list.size())
          .append(" | ")
          .append(nord)
          .append(" | ")
          .append(sud)
          .append(" | ")
          .append(openN)
          .append(" | ")
          .append(openS)
          .append(" | ")
          .append(changes)
          .append(" | ")
          .append(text(codes))
          .append(" |\n");
    }

    // 4 coordinated landings per theater
    md.append(
        "\nSbarchi coordinati USA per teatro (round del teatro a quel momento). Unita' per ondata,"
            + " presi, tenuti dopo il turno nemico e dopo 2 round, rinforzi britannici arrivati:\n\n"
            + "| Configurazione | Teatro | Sbarchi | Partite con sbarco | Round del primo (mediana) |"
            + " Unita' per ondata | Presi | Tenuti 1 round | Tenuti 2 round | Perdite reali / P80 |"
            + " Rinforzi UK (sbarchi rinforzati, unita') | Completamenti / sbarchi UK da solo |\n"
            + "|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (final String config : r3a) {
      final List<JsonObject> list = byConfig.get(config);
      for (final String theater : List.of("NORD", "SUD")) {
        int landings = 0;
        int games = 0;
        final List<Double> first = new ArrayList<>();
        double units = 0;
        int taken = 0;
        int held1 = 0;
        int held2 = 0;
        double lost = 0;
        double p80 = 0;
        int reinforced = 0;
        int reinforcements = 0;
        int completions = 0;
        int alone = 0;
        for (final JsonObject j : list) {
          final Map<Integer, String> th = teatroPerRound(j);
          final Map<String, JsonObject> outcomes = esiti(j);
          boolean has = false;
          for (final JsonObject d : decisioni(j, "sbarco")) {
            final int r = d.get("round").getAsInt();
            if (!theater.equals(th.get(r)) || !"ESEGUITO".equals(codice(d))) {
              continue;
            }
            landings++;
            if (!has) {
              first.add((double) r);
            }
            has = true;
            final JsonObject a = d.getAsJsonArray("alternative").get(0).getAsJsonObject();
            units += a.get("unita_sbarcate").getAsDouble();
            final JsonObject e = outcomes.get(d.get("id").getAsString() + "|preso");
            if (e != null && e.get("preso").getAsBoolean()) {
              taken++;
            }
            if (e != null) {
              lost += e.get("perdite_reali_tuv").getAsDouble();
              p80 += e.get("perdite_p80_tuv").getAsDouble();
            }
            final JsonObject h1 = outcomes.get(d.get("id").getAsString() + "|tenuta1");
            final JsonObject h2 = outcomes.get(d.get("id").getAsString() + "|tenuta2");
            held1 += h1 != null && h1.get("tenuto").getAsBoolean() ? 1 : 0;
            held2 += h2 != null && h2.get("tenuto").getAsBoolean() ? 1 : 0;
          }
          for (final JsonObject d : decisioni(j, "rinforzo_testa_di_ponte")) {
            final int r = d.get("round").getAsInt();
            if (!theater.equals(th.get(r))) {
              continue;
            }
            final String code = codice(d);
            if ("RINFORZO".equals(code)) {
              final JsonObject e = outcomes.get(d.get("id").getAsString() + "|rinforzo");
              if (e != null && e.get("rinforzi_arrivati").getAsInt() > 0) {
                reinforced++;
                reinforcements += e.get("rinforzi_arrivati").getAsInt();
              }
            } else if ("COMPLETAMENTO".equals(code) && isLanding(d)) {
              completions++;
            } else if ("SOLO_UK".equals(code) && isLanding(d)) {
              alone++;
            }
          }
          games += has ? 1 : 0;
        }
        md.append("| ")
            .append(config)
            .append(" | ")
            .append(theater)
            .append(" | ")
            .append(landings)
            .append(" | ")
            .append(games)
            .append('/')
            .append(list.size())
            .append(" | ")
            .append(first.isEmpty() ? "-" : "R" + num1(ConfrontoOmbra.median(first)))
            .append(" | ")
            .append(landings == 0 ? "-" : num1(units / landings))
            .append(" | ")
            .append(taken)
            .append(" | ")
            .append(held1)
            .append(" | ")
            .append(held2)
            .append(" | ")
            .append(landings == 0 ? "-" : num0(lost / landings) + " / " + num0(p80 / landings))
            .append(" | ")
            .append(reinforced)
            .append(", ")
            .append(reinforcements)
            .append(" | ")
            .append(completions)
            .append(" / ")
            .append(alone)
            .append(" |\n");
      }
    }
    md.append(
        "\nAttese del via libera USA (round senza via libera) per teatro e motivo; cosa mancava"
            + " (esempi):\n\n| Configurazione | Teatro | Via libera | Attese per motivo | Esempi |\n"
            + "|---|---|---|---|---|\n");
    for (final String config : r3a) {
      final List<JsonObject> list = byConfig.get(config);
      for (final String theater : List.of("NORD", "SUD")) {
        int go = 0;
        final Map<String, Integer> waits = new TreeMap<>();
        final List<String> examples = new ArrayList<>();
        for (final JsonObject j : list) {
          final Map<Integer, String> th = teatroPerRound(j);
          for (final JsonObject d : decisioni(j, "via_libera_o_attesa")) {
            if (!theater.equals(th.get(d.get("round").getAsInt()))) {
              continue;
            }
            if ("VIA_LIBERA".equals(codice(d))) {
              go++;
            } else {
              waits.merge(codice(d), 1, Integer::sum);
              if (examples.size() < 3
                  && d.getAsJsonObject("parametri").has("manca")
                  && !d.getAsJsonObject("parametri").get("manca").getAsString().isEmpty()) {
                examples.add(
                    "R"
                        + d.get("round").getAsInt()
                        + " "
                        + d.getAsJsonObject("parametri").get("manca").getAsString());
              }
            }
          }
        }
        md.append("| ")
            .append(config)
            .append(" | ")
            .append(theater)
            .append(" | ")
            .append(go)
            .append(" | ")
            .append(waits.isEmpty() ? "-" : text(waits))
            .append(" | ")
            .append(examples.isEmpty() ? "-" : String.join("; ", examples).replace("|", "/"))
            .append(" |\n");
      }
    }

    // 5 where the US and British land units are
    md.append(
        "\nUnita' di terra USA e britanniche per regione a fine round (media sui semi; base = base"
            + " della spola del teatro, United Kingdom senza R3a):\n\n| Configurazione | Nazione |"
            + " Round |");
    REGIONS.forEach(k -> md.append(' ').append(k).append(" |"));
    md.append("\n|---|---|---|");
    REGIONS.forEach(k -> md.append("---|"));
    md.append('\n');
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      for (final String n : List.of("Americans", "British")) {
        for (final int r : List.of(6, 9, 12)) {
          md.append("| ").append(config).append(" | ").append(n).append(" | R").append(r).append(" |");
          for (final String k : REGIONS) {
            md.append(' ').append(num1(mean(list, j -> regione(j, n, r, k)))).append(" |");
          }
          md.append('\n');
        }
      }
    }

    // 6 North Africa
    md.append(
        "\nNord Africa (territori dell'obiettivo italiano: Morocco, Algeria, Tunisia, Libya, Tobruk,"
            + " Alexandria): dell'Asse a fine round (media), reddito italiano, round in cui l'Asse"
            + " non ne ha piu':\n\n| Configurazione |");
    vcRounds.forEach(r -> md.append(" Asse R").append(r).append(" |"));
    vcRounds.forEach(r -> md.append(" Reddito ITA R").append(r).append(" |"));
    md.append(" Asse a zero (partite, round mediano) |\n|---|");
    vcRounds.forEach(r -> md.append("---|---|"));
    md.append("---|\n");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      md.append("| ").append(config).append(" |");
      for (final int r : vcRounds) {
        md.append(' ').append(num1(mean(list, j -> nordAfrica(j, r)))).append(" |");
      }
      for (final int r : vcRounds) {
        md.append(' ')
            .append(num1(mean(list, j -> ConfrontoImpegno.nation(j, "Italians", r, "reddito"))))
            .append(" |");
      }
      final List<Double> zero = new ArrayList<>();
      for (final JsonObject j : list) {
        for (int r = 1; r <= vcRounds.get(vcRounds.size() - 1); r++) {
          if (nordAfrica(j, r) == 0) {
            zero.add((double) r);
            break;
          }
        }
      }
      md.append(' ')
          .append(zero.size())
          .append('/')
          .append(list.size())
          .append(zero.isEmpty() ? "" : ", R" + num1(ConfrontoOmbra.median(zero)))
          .append(" |\n");
    }

    // 7 transports, the cap, the units held
    md.append(
        "\nTrasporti USA e britannici (media per partita): totali e fermi vuoti a fine round;"
            + " turni con il tetto attivo e trasporti comprati da Hard in quei turni (deve essere"
            + " 0); unita' trattenute alla base per round (media sui turni):\n\n| Configurazione |"
            + " Nazione |");
    vcRounds.forEach(r -> md.append(" Trasporti R").append(r).append(" (fermi vuoti) |"));
    md.append(
        " Turni col tetto | Trasporti comprati col tetto | Trattenute per turno |\n|---|---|");
    vcRounds.forEach(r -> md.append("---|"));
    md.append("---|---|---|\n");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      for (final String n : List.of("Americans", "British")) {
        md.append("| ").append(config).append(" | ").append(n).append(" |");
        for (final int r : vcRounds) {
          md.append(' ')
              .append(num1(mean(list, j -> ConfrontoImpegno.nation(j, n, r, "trasporti"))))
              .append(" (")
              .append(
                  num1(mean(list, j -> ConfrontoImpegno.nation(j, n, r, "trasporti_inattivi"))))
              .append(") |");
        }
        int capped = 0;
        int boughtCapped = 0;
        int heldTurns = 0;
        double held = 0;
        for (final JsonObject j : list) {
          final Map<String, JsonObject> outcomes = esiti(j);
          for (final JsonObject d : decisioni(j, "tetto_trasporti")) {
            if (!n.equals(d.get("nazione").getAsString())
                || !"TRASPORTO_FERMO_VUOTO".equals(codice(d))) {
              continue;
            }
            capped++;
            final JsonObject e = outcomes.get(d.get("id").getAsString() + "|tetto");
            if (e != null) {
              boughtCapped += e.get("trasporti_comprati").getAsInt();
            }
          }
          for (final JsonObject d : decisioni(j, "unita_trattenute")) {
            if (n.equals(d.get("nazione").getAsString())) {
              heldTurns++;
              held += Double.parseDouble(d.get("scelta").getAsString());
            }
          }
        }
        md.append(' ')
            .append(r3a.contains(config) ? num1((double) capped / list.size()) : "-")
            .append(" | ")
            .append(r3a.contains(config) ? String.valueOf(boughtCapped) : "-")
            .append(" | ")
            .append(heldTurns == 0 ? "-" : num1(held / heldTurns))
            .append(" |\n");
      }
    }
    if (!r3a.isEmpty()) {
      md.append("\nUnita' trattenute alla base per round (media sulle partite, USA + Regno Unito):\n\n");
      md.append("| Configurazione |");
      for (int r = 1; r <= vcRounds.get(vcRounds.size() - 1); r++) {
        md.append(" R").append(r).append(" |");
      }
      md.append("\n|---|");
      for (int r = 1; r <= vcRounds.get(vcRounds.size() - 1); r++) {
        md.append("---|");
      }
      md.append('\n');
      for (final String config : r3a) {
        final List<JsonObject> list = byConfig.get(config);
        md.append("| ").append(config).append(" |");
        for (int r = 1; r <= vcRounds.get(vcRounds.size() - 1); r++) {
          double sum = 0;
          for (final JsonObject j : list) {
            for (final JsonObject d : decisioni(j, "unita_trattenute")) {
              if (d.get("round").getAsInt() == r) {
                sum += Double.parseDouble(d.get("scelta").getAsString());
              }
            }
          }
          md.append(' ').append(num1(sum / list.size())).append(" |");
        }
        md.append('\n');
      }
    }

    // 8 the register
    for (final String config : r3a) {
      final List<JsonObject> list = byConfig.get(config);
      final Map<String, Map<String, Integer>> counts = new TreeMap<>();
      int outcomes = 0;
      for (final JsonObject j : list) {
        for (final JsonElement e : registro(j)) {
          final JsonObject d = e.getAsJsonObject();
          if ("esito".equals(d.get("riga").getAsString())) {
            outcomes++;
            continue;
          }
          counts
              .computeIfAbsent(d.get("tipo").getAsString(), k -> new TreeMap<>())
              .merge(codice(d), 1, Integer::sum);
        }
      }
      md.append("\nRegistro delle decisioni (")
          .append(config)
          .append("): conteggio per tipo e motivo; righe esito ")
          .append(outcomes)
          .append(".\n\n| Tipo | Decisioni | Per motivo |\n|---|---|---|\n");
      counts.forEach(
          (tipo, m) ->
              md.append("| ")
                  .append(tipo)
                  .append(" | ")
                  .append(m.values().stream().mapToInt(Integer::intValue).sum())
                  .append(" | ")
                  .append(text(m))
                  .append(" |\n"));
      // calibration of the landings (USA and United Kingdom combat landings)
      final int[][] win = new int[5][2];
      final int[][] hold = new int[5][2];
      for (final JsonObject j : list) {
        for (final JsonElement e : registro(j)) {
          final JsonObject d = e.getAsJsonObject();
          if (!"esito".equals(d.get("riga").getAsString())) {
            continue;
          }
          final JsonObject x = d.getAsJsonObject("esito");
          if (x.has("preso") && x.has("vittoria_stimata_pct")) {
            final double est = x.get("vittoria_stimata_pct").getAsDouble();
            if (est >= 0) {
              final int b = band(est);
              win[b][0]++;
              win[b][1] += x.get("preso").getAsBoolean() ? 1 : 0;
            }
          }
          if (x.has("tenuto")
              && x.has("tenuta_stimata")
              && x.get("round_dopo").getAsInt() == 1) {
            final int b = band(x.get("tenuta_stimata").getAsDouble());
            hold[b][0]++;
            hold[b][1] += x.get("tenuto").getAsBoolean() ? 1 : 0;
          }
        }
      }
      md.append(
          "\nCalibrazione degli sbarchi ("
              + config
              + "): vittoria stimata a fasce contro presi; tenuta stimata a fasce contro tenuti"
              + " dopo il turno nemico:\n\n| Fascia | Sbarchi | Presi | Tenute stimate | Tenuti |\n"
              + "|---|---|---|---|---|\n");
      for (int b = 0; b < 5; b++) {
        md.append("| ")
            .append(b * 20)
            .append('-')
            .append(b * 20 + 20)
            .append("% | ")
            .append(win[b][0])
            .append(" | ")
            .append(win[b][0] == 0 ? "-" : win[b][1] + " (" + pct(win[b][1], win[b][0]) + ")")
            .append(" | ")
            .append(hold[b][0])
            .append(" | ")
            .append(
                hold[b][0] == 0 ? "-" : hold[b][1] + " (" + pct(hold[b][1], hold[b][0]) + ")")
            .append(" |\n");
      }
    }
  }

  // ================================================================================ helpers

  static JsonArray registro(final JsonObject j) {
    return j.has("impegno_decisioni") && j.get("impegno_decisioni").isJsonArray()
        ? j.getAsJsonArray("impegno_decisioni")
        : new JsonArray();
  }

  private static List<JsonObject> decisioni(final JsonObject j, final String tipo) {
    final List<JsonObject> out = new ArrayList<>();
    for (final JsonElement e : registro(j)) {
      final JsonObject d = e.getAsJsonObject();
      if ("decisione".equals(d.get("riga").getAsString())
          && tipo.equals(d.get("tipo").getAsString())) {
        out.add(d);
      }
    }
    return out;
  }

  /**
   * The outcomes by "id|kind": "preso" (a landing), "tenuta1"/"tenuta2" (held after 1 or 2
   * rounds), "rinforzo", "tetto".
   */
  private static Map<String, JsonObject> esiti(final JsonObject j) {
    final Map<String, JsonObject> out = new HashMap<>();
    for (final JsonElement e : registro(j)) {
      final JsonObject d = e.getAsJsonObject();
      if (!"esito".equals(d.get("riga").getAsString())) {
        continue;
      }
      final JsonObject x = d.getAsJsonObject("esito");
      final String id = d.get("id").getAsString();
      if (x.has("tenuto") && x.has("round_dopo")) {
        out.put(id + "|tenuta" + x.get("round_dopo").getAsInt(), x);
      } else if (x.has("preso") && x.has("vittoria_stimata_pct")) {
        out.put(id + "|preso", x);
      } else if (x.has("rinforzi_arrivati")) {
        out.put(id + "|rinforzo", x);
      } else if (x.has("trasporti_comprati")) {
        out.put(id + "|tetto", x);
      }
    }
    return out;
  }

  /** The decisions of the United Kingdom that are a combat landing (not the plan's choice). */
  private static boolean isLanding(final JsonObject d) {
    return d.getAsJsonArray("alternative").size() == 1
        && d.getAsJsonArray("alternative").get(0).getAsJsonObject().has("unita_sbarcate")
        && d.getAsJsonArray("alternative")
                .get(0)
                .getAsJsonObject()
                .get("unita_sbarcate")
                .getAsInt()
            > 0;
  }

  private static Map<Integer, String> teatroPerRound(final JsonObject j) {
    final Map<Integer, String> out = new LinkedHashMap<>();
    for (final JsonObject d : decisioni(j, "teatro")) {
      out.put(d.get("round").getAsInt(), d.get("scelta").getAsString());
    }
    return out;
  }

  private static String codice(final JsonObject d) {
    return d.getAsJsonObject("motivo").get("codice").getAsString();
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
    if (round == null || !round.has("nord_africa_asse")) {
      return Double.NaN;
    }
    return round.getAsJsonArray("nord_africa_asse").size();
  }

  private static JsonObject round(final JsonObject json, final int r) {
    if (!json.has("round")) {
      return null;
    }
    for (final JsonElement e : json.getAsJsonArray("round")) {
      if (e.getAsJsonObject().get("round").getAsInt() == r) {
        return e.getAsJsonObject();
      }
    }
    return null;
  }

  private static double mean(
      final List<JsonObject> list, final java.util.function.ToDoubleFunction<JsonObject> f) {
    return Stat.of(list, f).meanValue();
  }

  private static int band(final double pct) {
    return Math.max(0, Math.min(4, (int) Math.floor(pct / 20)));
  }

  private static String pct(final int a, final int b) {
    return b == 0 ? "-" : Math.round(100.0 * a / b) + "%";
  }

  private static String text(final Map<String, Integer> m) {
    return String.join(
        ", ", m.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList());
  }

  private static String num(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.2f", x);
  }

  private static String num1(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.1f", x);
  }

  private static String num0(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.0f", x);
  }
}
