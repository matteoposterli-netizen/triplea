package org.triplea.proplus.autoplay;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step R6b: the subsection "R6b" of "Partite complete": outcomes (f1 against a, f3 against e2, g
 * against e2), the US transports (bought, lost loaded and empty, back to load), the attacks on US
 * ships, the waves (escort, safety, given up, zone changed), the port, the squadron, the pressure
 * on the Axis and Russia. Next to every row of a configuration the row of step R6 (run
 * 43264777394), from the resource {@value #RIFERIMENTO} computed with this same code on R6's games.
 */
final class ConfrontoR6b {
  private ConfrontoR6b() {}

  static final String RIFERIMENTO = "r6-riferimento.json";

  /** System property: write the reference rows of the games compared to this file (offline). */
  static final String ESPORTA = "r6b.esporta";

  static final List<String> BANDE =
      List.of("R1-4", "R5", "R6", "R7", "R8", "R9-12", "R13-18", "R19+");
  static final List<Integer> ROUNDS_GERMANIA = List.of(5, 7, 10, 12, 14);
  static final List<Integer> ROUNDS_RUSSIA = List.of(8, 12, 16);

  /** True when the games are of step R6b (rows of its register types) or an export is asked. */
  static boolean applicabile(final List<Game> games) {
    if (System.getProperty(ESPORTA) != null) {
      return true;
    }
    for (final Game g : games) {
      if (g.json() != null
          && ConfrontoR5.decisioni(g.json(), "trasporti_sicurezza").stream()
              .anyMatch(d -> "Americans".equals(d.get("nazione").getAsString()))) {
        return true;
      }
    }
    return false;
  }

  private static final JsonObject EXPORT = new JsonObject();

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
    configs.sort((a, b) -> Integer.compare(ordine(a, reference), ordine(b, reference)));
    final List<String> squad =
        configs.stream().filter(c -> c.contains(ConfrontoR6.SQUADRA)).toList();
    final JsonObject ref = riferimento();
    md.append(
"""

### R6b

Configurazioni: (a) Hard vs Hard; (f1) Hard vs Alleati con impegno + squadra_gibilterra (trasporti protetti); (e2) Asse con impegno + assedio (quota 70) vs Hard; (f3) Asse con impegno + assedio vs Alleati con impegno + squadra_gibilterra; (g) Asse con impegno + assedio a quota 80 vs Hard. Sotto ogni configurazione la riga "(R6)" = stessa misura nella run di R6 (43264777394, semi 1221-1260), dove esiste. Medie a partita salvo dove indicato.

""");
    tabella(
        md,
        ref,
        "esiti",
        "Vittorie, IC 95% di Wilson, Fisher a due code (f1 contro a e f3 contro e2 sugli Alleati, g contro e2 sull'Asse), round della vittoria.",
        List.of(
            "Partite",
            "Asse",
            "Alleati",
            "Stalli",
            "Quota Asse",
            "Quota Alleati",
            "Confronto",
            "p",
            "Round vittoria mediana",
            "Alleati mediana",
            "Asse mediana"),
        configs,
        byConfig,
        (c, list) -> esiti(c, list, configs, byConfig, reference));
    tabella(
        md,
        ref,
        "trasporti_round",
        "Trasporti USA per round (somme a partita): comprati dal programma (registro), persi (tutti i trasporti USA, monitor), rientrati a caricare (registro rientro_trasporti arrivati nella 91 o al porto).",
        bande("comprati", "persi", "rientrati"),
        configs,
        byConfig,
        ConfrontoR6b::trasportiRound);
    tabella(
        md,
        ref,
        "trasporti_partita",
        "Trasporti USA a partita: comprati dal programma; persi (monitor, tutti i trasporti USA); persi negli attacchi subiti, carichi e vuoti, e unita' di carico perse (righe attacco_subito); trasporti e carico del programma persi in mare (righe perdite_in_mare).",
        List.of(
            "Comprati programma",
            "Persi (monitor)",
            "Persi carichi",
            "Persi vuoti",
            "Carico perso (attacchi)",
            "Trasporti programma persi in mare",
            "Carico programma perso in mare",
            "Rientrati"),
        configs,
        byConfig,
        ConfrontoR6b::trasportiPartita);
    tabellaAttacchi(md, configs, byConfig);
    tabella(
        md,
        ref,
        "ondate",
        "Ondate del programma (registro sbarco eseguito): numero a partita per round e unita' per ondata (in fondo).",
        bande("ondate", null, null),
        squad,
        byConfig,
        ConfrontoR6b::ondate);
    tabella(
        md,
        ref,
        "ondate_esiti",
        "Ondate: unita' per ondata, bersagli presi e tenuti dopo 1 round (esiti), scorta (registro scorta_ondata: navi andate e rimaste nella 91, medie per ondata), controlli dei trasporti (registro trasporti_sicurezza: sicure, scortate, zona cambiata, rinunce, a partita).",
        List.of(
            "Unita' per ondata",
            "Presi / ondate",
            "Tenuti 1 round / presi",
            "Navi di scorta per ondata",
            "Navi rimaste nella 91",
            "Sicure",
            "Scortate",
            "Zona cambiata",
            "Rinunce",
            "Bersagli piu' frequenti"),
        squad,
        byConfig,
        ConfrontoR6b::ondateEsiti);
    tabella(
        md,
        ref,
        "porto",
        "Porto (registro spola, dopo il carico del turno): truppe del programma rimaste al porto e trasporti vuoti del programma nelle zone del porto, medie per turno nei round della banda; quota dei PU del turno spesi dal programma dal round 5 (acquisto_pressione).",
        bande("truppe ferme", "trasporti fermi", null, "Quota PU dal R5"),
        squad,
        byConfig,
        ConfrontoR6b::porto);
    tabella(
        md,
        ref,
        "squadra",
        "Squadra: composizione all'inizio del turno USA a R4 e R5 (tutta la squadra, navi e aerei; registro), navi da guerra USA perse negli attacchi subiti per banda (somma a partita), turni USA dal R5 iniziati senza navi USA nella zona 91.",
        bandeSquadra(),
        squad,
        byConfig,
        ConfrontoR6b::squadra);
    tabella(
        md,
        ref,
        "pressione",
        "Pressione sull'Asse: unita' di terra tedesche in Europa occidentale / Italia e isole / altrove (mare escluso) per round; esercito russo.",
        pressioneHeader(),
        configs,
        byConfig,
        ConfrontoR6b::pressione);
    tabella(
        md,
        ref,
        "citta",
        "Cadute (passaggio all'altra alleanza) e liberazione di Parigi: partite / partite e round mediano.",
        List.of("Mosca cade", "Roma cade", "Berlino cade", "Parigi liberata"),
        configs,
        byConfig,
        (c, list) ->
            List.of(
                ConfrontoR5.cambio(list, "Mosca", 1),
                ConfrontoR5.cambio(list, "Roma", 1),
                ConfrontoR5.cambio(list, "Berlino", 1),
                ConfrontoR5.cambio(list, "Parigi", 2)));
    ConfrontoR5.sbarchi(md, configs, byConfig);
    final String out = System.getProperty(ESPORTA);
    if (out != null) {
      try {
        Files.writeString(
            Path.of(out),
            new GsonBuilder().setPrettyPrinting().create().toJson(EXPORT),
            StandardCharsets.UTF_8);
      } catch (final IOException e) {
        md.append("\n(riferimento non scritto: ").append(e).append(")\n");
      }
    }
  }

  private static int ordine(final String config, final String reference) {
    if (config.equals(reference)) {
      return 0;
    }
    final boolean squadra = config.contains(ConfrontoR6.SQUADRA);
    final boolean assedio = config.contains(ConfrontoR6.ASSEDIO);
    if (squadra && !assedio) {
      return 1;
    }
    if (assedio && !squadra && !config.contains(ConfrontoR6.QUOTA80)) {
      return 2;
    }
    if (assedio && squadra) {
      return 3;
    }
    return config.contains(ConfrontoR6.QUOTA80) ? 4 : 5;
  }

  // ============================================================================ the tables

  private static void tabella(
      final StringBuilder md,
      final JsonObject ref,
      final String key,
      final String intro,
      final List<String> header,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig,
      final BiFunction<String, List<JsonObject>, List<String>> row) {
    if (configs.isEmpty()) {
      return;
    }
    md.append('\n').append(intro).append("\n\n| Configurazione |");
    header.forEach(h -> md.append(' ').append(h).append(" |"));
    md.append("\n|---|");
    header.forEach(h -> md.append("---|"));
    md.append('\n');
    final JsonObject exp = new JsonObject();
    for (final String config : configs) {
      final List<String> cells = row.apply(config, byConfig.get(config));
      riga(md, config, cells);
      final JsonArray a = new JsonArray();
      cells.forEach(a::add);
      exp.add(config, a);
      if (ref.has(key) && ref.getAsJsonObject(key).has(config)) {
        final List<String> old = new ArrayList<>();
        ref.getAsJsonObject(key).getAsJsonArray(config).forEach(e -> old.add(e.getAsString()));
        riga(md, "(R6) " + config, old);
      }
    }
    EXPORT.add(key, exp);
  }

  private static void riga(final StringBuilder md, final String label, final List<String> cells) {
    md.append("| ").append(label);
    cells.forEach(c -> md.append(" | ").append(c));
    md.append(" |\n");
  }

  private static JsonObject riferimento() {
    try (InputStream in = ConfrontoR6b.class.getClassLoader().getResourceAsStream(RIFERIMENTO)) {
      if (in == null || System.getProperty(ESPORTA) != null) {
        return new JsonObject();
      }
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonObject();
    } catch (final IOException | RuntimeException e) {
      return new JsonObject();
    }
  }

  private static List<String> bande(
      final String a, @Nullable final String b, @Nullable final String c, final String... extra) {
    final List<String> out = new ArrayList<>();
    for (final String what : new String[] {a, b, c}) {
      if (what != null) {
        BANDE.forEach(x -> out.add(what + " " + x));
        out.add(what + " totale");
      }
    }
    out.addAll(List.of(extra));
    return out;
  }

  // =============================================================================== outcomes

  private static List<String> esiti(
      final String config,
      final List<JsonObject> list,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig,
      final String reference) {
    final int n = list.size();
    final int axis = ConfrontoR4.wins(list, "Axis");
    final int allies = ConfrontoR4.wins(list, "Allies");
    final String[] other = ConfrontoR6.confronto(config, configs, reference);
    String p = "-";
    String vs = "-";
    if (other != null) {
      final List<JsonObject> o = byConfig.get(other[0]);
      final int mine = "Axis".equals(other[1]) ? axis : allies;
      p = ConfrontoR5.pText(ConfrontoR4.fisher(mine, n, ConfrontoR4.wins(o, other[1]), o.size()));
      vs = other[2];
    }
    return List.of(
        String.valueOf(n),
        String.valueOf(axis),
        String.valueOf(allies),
        String.valueOf(n - axis - allies),
        ConfrontoR4.wilsonText(axis, n),
        ConfrontoR4.wilsonText(allies, n),
        vs,
        p,
        ConfrontoR5.roundText(list, null),
        ConfrontoR5.roundText(list, "Allies"),
        ConfrontoR5.roundText(list, "Axis"));
  }

  // ============================================================================ transports

  private static List<String> trasportiRound(final String config, final List<JsonObject> list) {
    final double[] bought = new double[BANDE.size()];
    final double[] lost = new double[BANDE.size()];
    final double[] back = new double[BANDE.size()];
    for (final JsonObject j : list) {
      comprati(j, bought);
      for (final JsonElement e : ConfrontoR5.rounds(j)) {
        final int r = e.getAsJsonObject().get("round").getAsInt();
        final double x = ConfrontoR5.nazione(j, r, "Americans", "trasporti_persi");
        if (!Double.isNaN(x)) {
          lost[BANDE.indexOf(ConfrontoR6.banda(r))] += x;
        }
      }
      rientrati(j, back);
    }
    final List<String> out = new ArrayList<>();
    for (final double[] v : List.of(bought, lost, back)) {
      double tot = 0;
      for (final double x : v) {
        out.add(ConfrontoR5.num1(x / Math.max(1, list.size())));
        tot += x;
      }
      out.add(ConfrontoR5.num1(tot / Math.max(1, list.size())));
    }
    return out;
  }

  private static void comprati(final JsonObject j, final double[] bands) {
    for (final JsonObject d : ConfrontoR5.decisioni(j, "squadra_costruzione")) {
      final JsonObject a = ConfrontoR5.alt0(d);
      if (a != null && a.has("comprato") && a.getAsJsonObject("comprato").has("transport")) {
        bands[BANDE.indexOf(ConfrontoR6.banda(d.get("round").getAsInt()))] +=
            a.getAsJsonObject("comprato").get("transport").getAsDouble();
      }
    }
    for (final JsonObject d : ConfrontoR5.decisioni(j, "acquisto_pressione")) {
      final JsonObject a = ConfrontoR5.alt0(d);
      if (a != null && a.has("spola") && a.getAsJsonObject("spola").has("transport")) {
        bands[BANDE.indexOf(ConfrontoR6.banda(d.get("round").getAsInt()))] +=
            a.getAsJsonObject("spola").get("transport").getAsDouble();
      }
    }
  }

  private static void rientrati(final JsonObject j, final double[] bands) {
    for (final JsonObject d : ConfrontoR5.decisioni(j, "rientro_trasporti")) {
      final String code = ConfrontoR5.codice(d);
      if (!"RIENTRO".equals(code)) {
        continue;
      }
      final JsonObject p = d.getAsJsonObject("parametri");
      bands[BANDE.indexOf(ConfrontoR6.banda(d.get("round").getAsInt()))] +=
          p != null && p.has("trasporti") ? p.get("trasporti").getAsDouble() : 0;
    }
  }

  private static List<String> trasportiPartita(final String config, final List<JsonObject> list) {
    final double n = Math.max(1, list.size());
    final double[] bought = new double[BANDE.size()];
    final double[] back = new double[BANDE.size()];
    double lost = 0;
    double loaded = 0;
    double empty = 0;
    double cargo = 0;
    double seaTr = 0;
    double seaCargo = 0;
    for (final JsonObject j : list) {
      comprati(j, bought);
      rientrati(j, back);
      lost += ConfrontoR5.sommaNazione(j, "Americans", "trasporti_persi");
      for (final JsonObject a : attacchi(j)) {
        loaded += num(a, "trasporti_usa_persi_carichi");
        empty += num(a, "trasporti_usa_persi_vuoti");
        cargo += num(a, "carico_usa_perso");
      }
      for (final JsonObject d : ConfrontoR5.decisioni(j, "perdite_in_mare")) {
        final JsonObject a = ConfrontoR5.alt0(d);
        seaTr += num(a, "trasporti_persi");
        seaCargo += num(a, "carico_perso");
      }
    }
    final boolean attacks = list.stream().anyMatch(j -> !attacchi(j).isEmpty());
    return List.of(
        ConfrontoR5.num1(sum(bought) / n),
        ConfrontoR5.num1(lost / n),
        attacks ? ConfrontoR5.num1(loaded / n) : "-",
        attacks ? ConfrontoR5.num1(empty / n) : "-",
        attacks ? ConfrontoR5.num1(cargo / n) : "-",
        ConfrontoR5.num1(seaTr / n),
        ConfrontoR5.num1(seaCargo / n),
        ConfrontoR5.num1(sum(back) / n));
  }

  private static double sum(final double[] v) {
    double s = 0;
    for (final double x : v) {
      s += x;
    }
    return s;
  }

  // ============================================================================ attacks

  static List<JsonObject> attacchi(final JsonObject j) {
    return attacchi(j, "Americans");
  }

  /** The {@code attacco_subito} rows of {@code nation}'s ships. */
  static List<JsonObject> attacchi(final JsonObject j, final String nation) {
    final List<JsonObject> out = new ArrayList<>();
    for (final JsonObject d : ConfrontoR5.decisioni(j, "attacco_subito")) {
      final JsonObject a = ConfrontoR5.alt0(d);
      if (a != null && nation.equals(d.get("nazione").getAsString())) {
        a.addProperty("_round", d.get("round").getAsInt());
        out.add(a);
      }
    }
    return out;
  }

  private static void tabellaAttacchi(
      final StringBuilder md,
      final List<String> configs,
      final Map<String, List<JsonObject>> byConfig) {
    md.append(
"""

Attacchi subiti dalle navi USA (registro attacco_subito, scritto dal monitor dell'autoplay intorno al passo di battaglia del nemico), per zona: attacchi a partita, attaccanti tipici e difensori trovati (unita' medie per attacco), perdite medie per attacco. Le 6 zone piu' attaccate per configurazione; non c'e' in R6.

| Configurazione | Zona | Attacchi a partita | Attaccanti tipici | Difensori trovati | Perdite USA | Perdite attaccante (TUV) | Trasporti USA persi carichi / vuoti |
|---|---|---|---|---|---|---|---|
""");
    for (final String config : configs) {
      final List<JsonObject> list = byConfig.get(config);
      final Map<String, List<JsonObject>> byZone = new TreeMap<>();
      for (final JsonObject j : list) {
        for (final JsonObject a : attacchi(j)) {
          byZone.computeIfAbsent(a.get("zona").getAsString(), k -> new ArrayList<>()).add(a);
        }
      }
      if (byZone.isEmpty()) {
        md.append("| ").append(config).append(" | - | 0 | - | - | - | - | - |\n");
        continue;
      }
      final List<String> zones = new ArrayList<>(byZone.keySet());
      zones.sort((x, y) -> Integer.compare(byZone.get(y).size(), byZone.get(x).size()));
      int total = 0;
      for (final String z : zones) {
        total += byZone.get(z).size();
      }
      for (final String z : zones.subList(0, Math.min(6, zones.size()))) {
        final List<JsonObject> rows = byZone.get(z);
        double tl = 0;
        double te = 0;
        double attTuv = 0;
        for (final JsonObject a : rows) {
          tl += num(a, "trasporti_usa_persi_carichi");
          te += num(a, "trasporti_usa_persi_vuoti");
          attTuv += num(a, "tuv_perso_attaccante");
        }
        md.append("| ")
            .append(config)
            .append(" | ")
            .append(z)
            .append(" | ")
            .append(ConfrontoR5.num1((double) rows.size() / Math.max(1, list.size())))
            .append(" | ")
            .append(medieTipo(rows, "attaccanti"))
            .append(" | ")
            .append(medieTipo(rows, "difensori"))
            .append(" | ")
            .append(medieTipo(rows, "perdite_usa"))
            .append(" | ")
            .append(ConfrontoR5.num1(attTuv / rows.size()))
            .append(" | ")
            .append(ConfrontoR5.num1(tl / rows.size()))
            .append(" / ")
            .append(ConfrontoR5.num1(te / rows.size()))
            .append(" |\n");
      }
      md.append("| ")
          .append(config)
          .append(" | tutte | ")
          .append(ConfrontoR5.num1((double) total / Math.max(1, list.size())))
          .append(" | | | | | |\n");
    }
  }

  static String medieTipo(final List<JsonObject> rows, final String key) {
    final Map<String, Double> m = new TreeMap<>();
    for (final JsonObject a : rows) {
      if (a.has(key)) {
        a.getAsJsonObject(key)
            .entrySet()
            .forEach(e -> m.merge(e.getKey(), e.getValue().getAsDouble(), Double::sum));
      }
    }
    final List<String> parts = new ArrayList<>();
    m.entrySet().stream()
        .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
        .limit(5)
        .forEach(e -> parts.add(ConfrontoR5.num1(e.getValue() / rows.size()) + " " + e.getKey()));
    return parts.isEmpty() ? "-" : String.join(", ", parts);
  }

  // ============================================================================== waves

  private static List<String> ondate(final String config, final List<JsonObject> list) {
    final double[] waves = new double[BANDE.size()];
    for (final JsonObject j : list) {
      for (final JsonObject d : ConfrontoR5.decisioni(j, "sbarco")) {
        final JsonObject a = ConfrontoR5.alt0(d);
        if (a != null && num(a, "unita_sbarcate") > 0) {
          waves[BANDE.indexOf(ConfrontoR6.banda(d.get("round").getAsInt()))]++;
        }
      }
    }
    final List<String> out = new ArrayList<>();
    for (final double x : waves) {
      out.add(ConfrontoR5.num1(x / Math.max(1, list.size())));
    }
    out.add(ConfrontoR5.num1(sum(waves) / Math.max(1, list.size())));
    return out;
  }

  private static List<String> ondateEsiti(final String config, final List<JsonObject> list) {
    final double n = Math.max(1, list.size());
    double waves = 0;
    double units = 0;
    double taken = 0;
    double held = 0;
    double escorts = 0;
    double left = 0;
    double escortRows = 0;
    final Map<String, Double> codes = new TreeMap<>();
    final Map<String, Integer> targets = new TreeMap<>();
    for (final JsonObject j : list) {
      for (final JsonObject d : ConfrontoR5.decisioni(j, "sbarco")) {
        final JsonObject a = ConfrontoR5.alt0(d);
        if (a != null && num(a, "unita_sbarcate") > 0) {
          waves++;
          units += num(a, "unita_sbarcate");
          if (a.has("territorio")) {
            targets.merge(a.get("territorio").getAsString(), 1, Integer::sum);
          }
        }
      }
      for (final JsonElement e : ConfrontoR3a.registro(j)) {
        final JsonObject d = e.getAsJsonObject();
        if (!"esito".equals(d.get("riga").getAsString())) {
          continue;
        }
        final JsonObject x = d.getAsJsonObject("esito");
        if ("sbarco".equals(d.get("tipo").getAsString())
            && x.has("preso")
            && x.get("preso").getAsBoolean()) {
          taken++;
        }
        if ("sbarco".equals(d.get("tipo").getAsString())
            && x.has("round_dopo")
            && x.get("round_dopo").getAsInt() == 1
            && x.has("tenuto")
            && x.get("tenuto").getAsBoolean()) {
          held++;
        }
      }
      for (final JsonObject d : ConfrontoR5.decisioni(j, "scorta_ondata")) {
        final JsonObject a = ConfrontoR5.alt0(d);
        escortRows++;
        escorts += num(a, "navi_andate");
        left += num(a, "navi_rimaste");
      }
      for (final JsonObject d : ConfrontoR5.decisioni(j, "trasporti_sicurezza")) {
        final JsonObject p = d.getAsJsonObject("parametri");
        if (p != null && p.has("tipo") && p.get("tipo").getAsString().startsWith("ondata")) {
          codes.merge(ConfrontoR5.codice(d), 1.0, Double::sum);
        }
      }
    }
    final List<String> top = new ArrayList<>();
    targets.entrySet().stream()
        .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
        .limit(3)
        .forEach(e -> top.add(e.getKey() + " " + e.getValue()));
    final boolean r6b = codes.values().stream().mapToDouble(d -> d).sum() > 0 || escortRows > 0;
    return List.of(
        waves == 0 ? "-" : ConfrontoR5.num1(units / waves),
        ConfrontoR5.num0(taken) + " / " + ConfrontoR5.num0(waves),
        ConfrontoR5.num0(held) + " / " + ConfrontoR5.num0(taken),
        escortRows == 0 ? "-" : ConfrontoR5.num1(escorts / escortRows),
        escortRows == 0 ? "-" : ConfrontoR5.num1(left / escortRows),
        r6b ? ConfrontoR5.num1(codes.getOrDefault("SICURA", 0.0) / n) : "-",
        r6b ? ConfrontoR5.num1(codes.getOrDefault("SCORTATA", 0.0) / n) : "-",
        r6b ? ConfrontoR5.num1(codes.getOrDefault("ZONA_CAMBIATA", 0.0) / n) : "-",
        r6b ? ConfrontoR5.num1(codes.getOrDefault("RINUNCIA", 0.0) / n) : "-",
        top.isEmpty() ? "-" : String.join(", ", top));
  }

  // =============================================================================== port

  private static List<String> porto(final String config, final List<JsonObject> list) {
    final double[] troops = new double[BANDE.size()];
    final double[] idle = new double[BANDE.size()];
    final double[] turns = new double[BANDE.size()];
    for (final JsonObject j : list) {
      for (final JsonObject d : ConfrontoR5.decisioni(j, "spola")) {
        final JsonObject a = ConfrontoR5.alt0(d);
        if (a == null || !"Americans".equals(d.get("nazione").getAsString())) {
          continue;
        }
        final int b = BANDE.indexOf(ConfrontoR6.banda(d.get("round").getAsInt()));
        turns[b]++;
        // R6 has no count after the loading: the troops ready minus those loaded
        troops[b] +=
            a.has("truppe_rimaste_al_porto")
                ? num(a, "truppe_rimaste_al_porto")
                : Math.max(0, num(a, "truppe_pronte") - num(a, "caricate"));
        idle[b] +=
            a.has("trasporti_vuoti_programma_al_porto")
                ? num(a, "trasporti_vuoti_programma_al_porto")
                : Math.max(0, num(a, "vuoti_al_porto"));
      }
    }
    final List<String> out = new ArrayList<>();
    for (final double[] v : List.of(troops, idle)) {
      double s = 0;
      double t = 0;
      for (int i = 0; i < v.length; i++) {
        out.add(turns[i] == 0 ? "-" : ConfrontoR5.num1(v[i] / turns[i]));
        s += v[i];
        t += turns[i];
      }
      out.add(t == 0 ? "-" : ConfrontoR5.num1(s / t));
    }
    double spent = 0;
    double turn = 0;
    for (final JsonObject j : list) {
      for (final JsonObject d : ConfrontoR5.decisioni(j, "acquisto_pressione")) {
        final JsonObject a = ConfrontoR5.alt0(d);
        if (a != null) {
          spent += num(a, "pu_sostegno") + num(a, "pu_spola");
          turn += num(a, "pu_turno");
        }
      }
    }
    out.add(turn > 0 ? ConfrontoR5.num0(100 * spent / turn) + "%" : "-");
    return out;
  }

  // ============================================================================ squadron

  private static List<String> bandeSquadra() {
    final List<String> out = new ArrayList<>(List.of("Squadra a R4", "Squadra a R5"));
    BANDE.forEach(b -> out.add("Navi perse " + b));
    out.add("Turni senza navi nella 91");
    return out;
  }

  private static List<String> squadra(final String config, final List<JsonObject> list) {
    final Map<String, Double> r4 = new TreeMap<>();
    final Map<String, Double> r5 = new TreeMap<>();
    final double[] lost = new double[BANDE.size()];
    double empty91 = 0;
    double seen = 0;
    boolean attacks = false;
    for (final JsonObject j : list) {
      for (final String tipo : List.of("squadra_costruzione", "acquisto_pressione")) {
        for (final JsonObject d : ConfrontoR5.decisioni(j, tipo)) {
          final JsonObject a = ConfrontoR5.alt0(d);
          final int r = d.get("round").getAsInt();
          if (a == null || !a.has("squadra_ora")) {
            continue;
          }
          final Map<String, Double> m = r == 4 ? r4 : r == 5 ? r5 : null;
          if (m != null) {
            a.getAsJsonObject("squadra_ora")
                .entrySet()
                .forEach(e -> m.merge(e.getKey(), e.getValue().getAsDouble(), Double::sum));
          }
          if (r >= 5 && a.has("navi_usa_in_91")) {
            seen++;
            empty91 += a.get("navi_usa_in_91").getAsInt() == 0 ? 1 : 0;
          }
        }
      }
      for (final JsonObject a : attacchi(j)) {
        attacks = true;
        if (a.has("perdite_usa")) {
          for (final var e : a.getAsJsonObject("perdite_usa").entrySet()) {
            if (!"transport".equals(e.getKey()) && NAVI.contains(e.getKey())) {
              lost[BANDE.indexOf(ConfrontoR6.banda(a.get("_round").getAsInt()))] +=
                  e.getValue().getAsDouble();
            }
          }
        }
      }
    }
    final List<String> out = new ArrayList<>();
    out.add(media(r4, list.size()));
    out.add(media(r5, list.size()));
    for (final double x : lost) {
      out.add(attacks ? ConfrontoR5.num1(x / Math.max(1, list.size())) : "-");
    }
    out.add(seen == 0 ? "-" : ConfrontoR5.num1(empty91 / Math.max(1, list.size())));
    return out;
  }

  private static final List<String> NAVI =
      List.of("destroyer", "cruiser", "battleship", "carrier", "submarine", "transport");

  private static String media(final Map<String, Double> m, final int n) {
    if (m.isEmpty()) {
      return "-";
    }
    final List<String> parts = new ArrayList<>();
    m.forEach((k, v) -> parts.add(ConfrontoR5.num1(v / Math.max(1, n)) + " " + k));
    return String.join(", ", parts);
  }

  // =========================================================================== pressure

  private static List<String> pressioneHeader() {
    final List<String> out = new ArrayList<>();
    for (final int r : ROUNDS_GERMANIA) {
      out.add("Germania R" + r + " ovest / Italia / altrove");
    }
    ROUNDS_RUSSIA.forEach(r -> out.add("Esercito russo R" + r));
    return out;
  }

  private static List<String> pressione(final String config, final List<JsonObject> list) {
    final List<String> out = new ArrayList<>();
    for (final int r : ROUNDS_GERMANIA) {
      final double w =
          ConfrontoR5.mean(
              ConfrontoR5.values(
                  list, j -> ConfrontoR5.regioneR5(j, r, "Germans", "europa_occidentale")));
      final double i =
          ConfrontoR5.mean(
              ConfrontoR5.values(
                  list, j -> ConfrontoR5.regioneR5(j, r, "Germans", "italia_isole")));
      final double o =
          ConfrontoR5.mean(
              ConfrontoR5.values(
                  list,
                  j ->
                      ConfrontoR5.regioneR5(j, r, "Germans", "altrove")
                          + ConfrontoR5.regioneR5(j, r, "Germans", "nord_africa")));
      out.add(ConfrontoR5.num1(w) + " / " + ConfrontoR5.num1(i) + " / " + ConfrontoR5.num1(o));
    }
    for (final int r : ROUNDS_RUSSIA) {
      out.add(
          ConfrontoR5.num1(
              ConfrontoR5.mean(
                  ConfrontoR5.values(list, j -> ConfrontoR5.r4(j, r, "esercito", "Russians")))));
    }
    return out;
  }

  private static double num(@Nullable final JsonObject a, final String key) {
    return a != null && a.has(key) && a.get(key).isJsonPrimitive() ? a.get(key).getAsDouble() : 0;
  }
}
