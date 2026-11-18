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
import java.util.function.Function;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P2: the block {@code p2} of each game's {@code riepilogo.json} (register rows {@code
 * alleanza_*} and {@code sbarco}, the monitor's round snapshots {@code p2}, {@code p1}, {@code
 * nazioni}, the capitals' events) and the section "P2" of {@code riepilogo-generale.md}: the effect
 * on the alliance's objective first (Egypt, Rome, Moscow, Italian income, wins), then the behaviour
 * (direction, joint landings, attrition, troops on board, transports). Every configuration, Hard
 * included (the rows of the register are then absent).
 */
final class ConfrontoP2 {
  private ConfrontoP2() {}

  static final List<Integer> ROUNDS = List.of(8, 12, 16);
  static final String FRANCIA = "FRANCIA";
  static final String MEDITERRANEO = "MEDITERRANEO";

  /** The capitals whose fall the block records (round of the first fall). */
  static final Map<String, String> CADUTE =
      Map.of("Southern Italy", "roma", "Germany", "berlino", "Russia", "mosca");

  // ================================================================ block of one game

  static JsonObject blocco(final JsonObject json) {
    final JsonObject o = new JsonObject();
    final List<JsonObject> rows = new ArrayList<>();
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      rows.add(e.getAsJsonObject());
    }
    final int played = json.has("round_giocati") ? json.get("round_giocati").getAsInt() : 0;
    // direction per round (the alliance's rows; held until the next row)
    final TreeMap<Integer, String> dirRows = new TreeMap<>();
    final JsonArray reasons = new JsonArray();
    for (final JsonObject d : decisioni(rows, "alleanza_direzione")) {
      final JsonObject p = d.getAsJsonObject("parametri");
      dirRows.put(d.get("round").getAsInt(), p.get("scelta").getAsString());
      final JsonObject x = new JsonObject();
      x.addProperty("round", d.get("round").getAsInt());
      x.addProperty("scelta", p.get("scelta").getAsString());
      x.addProperty("codice", ConfrontoR5.codice(d));
      x.add("conti", p.get("conti"));
      reasons.add(x);
    }
    final JsonObject perRound = new JsonObject();
    final Map<String, Integer> count = new LinkedHashMap<>();
    for (int r = 1; r <= played; r++) {
      final Map.Entry<Integer, String> e = dirRows.floorEntry(r);
      if (e != null) {
        perRound.addProperty(String.valueOf(r), e.getValue());
        count.merge(e.getValue(), 1, Integer::sum);
      }
    }
    o.add("direzione_per_round", perRound);
    final JsonObject cj = new JsonObject();
    count.forEach(cj::addProperty);
    o.add("round_per_direzione", cj);
    o.add("scelte_direzione", reasons);
    if (!dirRows.isEmpty()) {
      o.addProperty("prima_direzione", dirRows.firstEntry().getValue());
      // the prevailing direction: the one held for most rounds
      count.entrySet().stream()
          .max(Map.Entry.comparingByValue())
          .ifPresent(e -> o.addProperty("direzione_prevalente", e.getKey()));
    }
    // decisions of the shared result
    final JsonObject decisions = new JsonObject();
    for (final JsonObject d : decisioni(rows, "alleanza_risultato_condiviso")) {
      final String code = ConfrontoR5.codice(d);
      decisions.addProperty(code, (decisions.has(code) ? decisions.get(code).getAsInt() : 0) + 1);
    }
    o.add("decisioni_condivise", decisions);
    // attrition landings (task landings executed with the alliance's decision LOGORAMENTO)
    int attrition = 0;
    int jointTask = 0;
    for (final JsonObject d : decisioni(rows, "sbarco")) {
      final JsonObject p = d.getAsJsonObject("parametri");
      if (!"ESEGUITO".equals(ConfrontoR5.codice(d)) || !p.has("decisione_alleanza")) {
        continue;
      }
      final String dec = p.get("decisione_alleanza").getAsString();
      if ("LOGORAMENTO".equals(dec)) {
        attrition++;
      } else if ("CONGIUNTO".equals(dec)) {
        jointTask++;
      }
    }
    o.addProperty("sbarchi_logoramento", attrition);
    o.addProperty("sbarchi_compito_congiunto", jointTask);
    // US landings (monitor): British on the same territory at the end of the round, still
    // allied one round later
    final Map<Integer, JsonObject> snaps = new TreeMap<>();
    if (json.has("round")) {
      for (final JsonElement e : json.getAsJsonArray("round")) {
        final JsonObject r = e.getAsJsonObject();
        snaps.put(r.get("round").getAsInt(), r);
      }
    }
    int usLandings = 0;
    int usLandingsFr = 0;
    int withUkFr = 0;
    int joint = 0;
    int jointHeld = 0;
    int jointFr = 0;
    int jointHeldFr = 0;
    int heldAll = 0;
    final JsonArray list = new JsonArray();
    for (final var e : snaps.entrySet()) {
      final JsonObject p2 = e.getValue().has("p2") ? e.getValue().getAsJsonObject("p2") : null;
      if (p2 == null || !p2.has("sbarchi_usa")) {
        continue;
      }
      final JsonObject next =
          snaps.containsKey(e.getKey() + 1) && snaps.get(e.getKey() + 1).has("p2")
              ? snaps.get(e.getKey() + 1).getAsJsonObject("p2")
              : null;
      final String dir =
          perRound.has(String.valueOf(e.getKey()))
              ? perRound.get(String.valueOf(e.getKey())).getAsString()
              : "";
      for (final JsonElement x : p2.getAsJsonArray("sbarchi_usa")) {
        final JsonObject l = x.getAsJsonObject();
        final String t = l.get("territorio").getAsString();
        final boolean uk = l.get("britannici_fine_round").getAsInt() > 0;
        Boolean held = null;
        if (next != null) {
          for (final JsonElement y : next.getAsJsonArray("sbarchi_usa_round_prima")) {
            if (y.getAsJsonObject().get("territorio").getAsString().equals(t)) {
              held = y.getAsJsonObject().get("alleato").getAsBoolean();
            }
          }
        }
        usLandings++;
        if (Boolean.TRUE.equals(held)) {
          heldAll++;
        }
        if (uk) {
          joint++;
          if (Boolean.TRUE.equals(held)) {
            jointHeld++;
          }
        }
        if (FRANCIA.equals(dir)) {
          usLandingsFr++;
          if (uk) {
            withUkFr++;
            jointFr++;
            if (Boolean.TRUE.equals(held)) {
              jointHeldFr++;
            }
          }
        }
        final JsonObject y = l.deepCopy();
        y.addProperty("round", e.getKey());
        y.addProperty("direzione", dir);
        if (held != null) {
          y.addProperty("alleato_round_dopo", held);
        }
        list.add(y);
      }
    }
    o.add("sbarchi_usa", list);
    o.addProperty("sbarchi_usa_totale", usLandings);
    o.addProperty("sbarchi_usa_tenuti_round_dopo", heldAll);
    o.addProperty("sbarchi_congiunti", joint);
    o.addProperty("sbarchi_congiunti_tenuti_round_dopo", jointHeld);
    o.addProperty("sbarchi_usa_con_francia", usLandingsFr);
    o.addProperty("sbarchi_usa_con_francia_britannici_fine_round", withUkFr);
    o.addProperty("sbarchi_congiunti_con_francia", jointFr);
    o.addProperty("sbarchi_congiunti_con_francia_tenuti_round_dopo", jointHeldFr);
    // Egypt of the Axis
    int egypt = 0;
    int withSnap = 0;
    for (final JsonObject r : snaps.values()) {
      if (r.has("p2") && r.getAsJsonObject("p2").has("egitto_asse")) {
        withSnap++;
        if (r.getAsJsonObject("p2").get("egitto_asse").getAsBoolean()) {
          egypt++;
        }
      }
    }
    o.addProperty("round_egitto_asse", egypt);
    o.addProperty("round_con_fotografia", withSnap);
    // per round R8, R12, R16
    final JsonObject per = new JsonObject();
    for (final int n : ROUNDS) {
      final JsonObject r = snaps.get(n);
      if (r == null) {
        continue;
      }
      final JsonObject x = new JsonObject();
      if (r.has("p2")) {
        final JsonObject b = r.getAsJsonObject("p2").getAsJsonObject("a_bordo");
        x.addProperty("a_bordo_usa", b.get(MonitorP2.USA).getAsInt());
        x.addProperty("a_bordo_uk", b.get(MonitorP2.UK).getAsInt());
        x.addProperty(
            "a_bordo_totale", b.get(MonitorP2.USA).getAsInt() + b.get(MonitorP2.UK).getAsInt());
      }
      if (r.has("p1") && r.getAsJsonObject("p1").has("regioni_usa")) {
        final JsonObject reg = r.getAsJsonObject("p1").getAsJsonObject("regioni_usa");
        x.addProperty(
            "usa_mediterraneo_nord_africa",
            reg.has("mediterraneo_nord_africa")
                ? reg.get("mediterraneo_nord_africa").getAsInt()
                : 0);
      }
      final JsonObject nat = r.getAsJsonObject("nazioni");
      if (nat != null && nat.has(MonitorP2.UK)) {
        x.addProperty(
            "trasporti_uk", nat.getAsJsonObject(MonitorP2.UK).get("trasporti").getAsInt());
        x.addProperty(
            "trasporti_uk_inattivi",
            nat.getAsJsonObject(MonitorP2.UK).get("trasporti_inattivi").getAsInt());
      }
      if (nat != null && nat.has("Italians")) {
        x.addProperty("reddito_italia", nat.getAsJsonObject("Italians").get("reddito").getAsInt());
      }
      per.add(String.valueOf(n), x);
    }
    o.add("per_round", per);
    // the capitals' first fall
    final JsonObject falls = new JsonObject();
    if (json.has("capitali") && json.get("capitali").isJsonArray()) {
      for (final JsonElement e : json.getAsJsonArray("capitali")) {
        final JsonObject c = e.getAsJsonObject();
        final String key = CADUTE.get(c.get("territorio").getAsString());
        if (key != null && "caduta".equals(c.get("evento").getAsString()) && !falls.has(key)) {
          falls.addProperty(key, c.get("round").getAsInt());
        }
      }
    }
    o.add("cadute", falls);
    // US transports lost (monitor)
    int lost = 0;
    for (final JsonObject r : snaps.values()) {
      final JsonObject n = r.getAsJsonObject("nazioni");
      if (n != null && n.has(MonitorP2.USA)) {
        lost += n.getAsJsonObject(MonitorP2.USA).get("trasporti_persi").getAsInt();
      }
    }
    o.addProperty("trasporti_usa_persi", lost);
    o.add("p2bis", p2bis(json, rows, snaps));
    return o;
  }

  /** Step P2-bis: the nations whose transports and landings are split between the task and Hard. */
  static final List<String> NAZIONI_P2BIS = List.of(MonitorP2.USA, MonitorP2.UK);

  static final List<Integer> ROUND_TRASPORTI = List.of(4, 8, 12);

  /**
   * Step P2-bis: per nation (USA, United Kingdom) the transports bought by the task (its orders,
   * register rows {@code compito_acquisto}, at most the transports placed that round) and by Hard
   * (the rest of the placed ones, monitor {@code p2.trasporti_piazzati}), the land units landed by
   * the task (monitor {@code sbarchi[].unita_compito}) and by Hard (the rest), the transports owned
   * at R4, R8, R12 ({@code nazioni.N.trasporti}).
   */
  static JsonObject p2bis(
      final JsonObject json, final List<JsonObject> rows, final Map<Integer, JsonObject> snaps) {
    // the task's ordered transports, round -> nation -> n
    final Map<Integer, Map<String, Integer>> ordered = new TreeMap<>();
    for (final JsonObject d : decisioni(rows, "compito_acquisto")) {
      final JsonObject p = d.getAsJsonObject("parametri");
      if (p == null || !p.has("comprato") || !p.get("comprato").isJsonObject()) {
        continue;
      }
      final JsonObject c = p.getAsJsonObject("comprato");
      if (c.has("transport")) {
        ordered
            .computeIfAbsent(d.get("round").getAsInt(), k -> new TreeMap<>())
            .merge(d.get("nazione").getAsString(), c.get("transport").getAsInt(), Integer::sum);
      }
    }
    final JsonObject out = new JsonObject();
    final JsonObject bought = new JsonObject();
    final JsonObject landed = new JsonObject();
    final JsonObject owned = new JsonObject();
    for (final String n : NAZIONI_P2BIS) {
      int task = 0;
      int hard = 0;
      for (final var e : snaps.entrySet()) {
        final JsonObject p2 = e.getValue().has("p2") ? e.getValue().getAsJsonObject("p2") : null;
        if (p2 == null || !p2.has("trasporti_piazzati")) {
          continue;
        }
        final JsonObject pl = p2.getAsJsonObject("trasporti_piazzati");
        final int placed = pl.has(n) ? pl.get(n).getAsInt() : 0;
        final int t =
            Math.min(placed, ordered.getOrDefault(e.getKey(), Map.of()).getOrDefault(n, 0));
        task += t;
        hard += placed - t;
      }
      final JsonObject b = new JsonObject();
      b.addProperty("compito", task);
      b.addProperty("hard", hard);
      b.addProperty("totale", task + hard);
      bought.add(n, b);
      int lTask = 0;
      int lAll = 0;
      if (json.has("sbarchi") && json.get("sbarchi").isJsonArray()) {
        for (final JsonElement e : json.getAsJsonArray("sbarchi")) {
          final JsonObject l = e.getAsJsonObject();
          if (n.equals(l.get("nazione").getAsString())) {
            lAll += l.get("unita").getAsInt();
            lTask += l.has("unita_compito") ? l.get("unita_compito").getAsInt() : 0;
          }
        }
      }
      final JsonObject l = new JsonObject();
      l.addProperty("compito", lTask);
      l.addProperty("hard", lAll - lTask);
      l.addProperty("totale", lAll);
      landed.add(n, l);
      final JsonObject w = new JsonObject();
      for (final int r : ROUND_TRASPORTI) {
        final JsonObject snap = snaps.get(r);
        final JsonObject nat = snap == null ? null : snap.getAsJsonObject("nazioni");
        if (nat != null && nat.has(n) && nat.getAsJsonObject(n).has("trasporti")) {
          w.addProperty(String.valueOf(r), nat.getAsJsonObject(n).get("trasporti").getAsInt());
        }
      }
      owned.add(n, w);
    }
    out.add("trasporti_comprati", bought);
    out.add("unita_sbarcate", landed);
    out.add("trasporti_posseduti", owned);
    return out;
  }

  private static List<JsonObject> decisioni(final List<JsonObject> rows, final String tipo) {
    return rows.stream()
        .filter(d -> "decisione".equals(d.get("riga").getAsString()))
        .filter(d -> tipo.equals(d.get("tipo").getAsString()))
        .toList();
  }

  // ======================================================================= the section

  static boolean applicabile(final List<Game> games) {
    return games.stream().anyMatch(g -> g.json() != null && g.json().has("p2"))
        && ConfrontoP1.applicabile(games);
  }

  static void write(final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("p2") || g.json().getAsJsonObject("p2").has("errore")) {
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

### P2

""");
    md.append(
        "Livello alleanza (direzione valore/ostacoli, testa di ponte condivisa, compito del Regno"
            + " Unito). Medie per partita del blocco `p2` dei `riepilogo.json` (campo tra"
            + " parentesi); le configurazioni senza `compiti` sono gli Alleati di Hard sugli"
            + " stessi semi. Confronto: con compiti contro la stessa Asse senza compiti.\n\n");
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
      final String base = base(e.getKey(), byConfig);
      if (base != null) {
        final List<JsonObject> o = byConfig.get(base);
        vs = "contro " + base;
        p =
            ConfrontoR5.pText(
                ConfrontoR4.fisher(allies, n, ConfrontoR4.wins(o, "Allies"), o.size()));
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
    // effect on the objective
    md.append(
"""

Effetto sull'obiettivo dell'alleanza: 1 round con Egypt dell'Asse / round fotografati (`round_egitto_asse`); 2 Southern Italy cade (`cadute.roma`); reddito italiano R8 / R12 / R16 (`per_round.N.reddito_italia`); 3 Russia cade (`cadute.mosca`); Germany cade (`cadute.berlino`).

| Configurazione | 1 Egypt Asse | 2 Roma cade | 2 reddito Italia R8 / R12 / R16 | 3 Mosca cade | Berlino cade |
|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocks(e.getValue());
      final int n = b.size();
      final double eg = sum(b, x -> x.get("round_egitto_asse").getAsDouble());
      final double snap = sum(b, x -> x.get("round_con_fotografia").getAsDouble());
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(f(eg / Math.max(1, n)))
          .append(" / ")
          .append(f(snap / Math.max(1, n)))
          .append(" (")
          .append(snap <= 0 ? "-" : Math.round(100 * eg / snap) + "%")
          .append(") | ")
          .append(falls(b, "roma"))
          .append("/")
          .append(n)
          .append(" | ")
          .append(f(meanRound(b, "8", "reddito_italia")))
          .append(" / ")
          .append(f(meanRound(b, "12", "reddito_italia")))
          .append(" / ")
          .append(f(meanRound(b, "16", "reddito_italia")))
          .append(" | ")
          .append(falls(b, "mosca"))
          .append("/")
          .append(n)
          .append(" | ")
          .append(falls(b, "berlino"))
          .append("/")
          .append(n)
          .append(" |\n");
    }
    // behaviour
    md.append(
"""

Comportamento: 5 round per direzione MEDITERRANEO / FRANCIA (`round_per_direzione`) e partite per direzione prevalente (`direzione_prevalente`); decisioni del risultato condiviso (`decisioni_condivise`); 6 sbarchi USA con FRANCIA / con britannici a fine round (`sbarchi_usa_con_francia*`), congiunti con FRANCIA / tenuti un round dopo (`sbarchi_congiunti_con_francia*`), tutti gli sbarchi USA tenuti un round dopo (`sbarchi_usa_tenuti_round_dopo`/`sbarchi_usa_totale`); sbarchi di logoramento (`sbarchi_logoramento`); 7 a bordo USA+UK R8 / R12 / R16 (`per_round.N.a_bordo_totale`), trasporti UK totali / inattivi R12 (`per_round.12.trasporti_uk*`), USA in Mediterraneo/Nord Africa R8 / R12 / R16 (`per_round.N.usa_mediterraneo_nord_africa`); 8 trasporti USA persi a partita (`trasporti_usa_persi`).

| Configurazione | 5 round MED / FR | prevalente MED / FR | decisioni | 6 USA FR / con UK | 6 congiunti FR / tenuti | USA tenuti / totale | logoramento | 7 a bordo R8 / R12 / R16 | 7 trasporti UK R12 tot / inattivi | USA Med R8 / R12 / R16 | 8 trasporti USA persi |
|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocks(e.getValue());
      final int n = Math.max(1, b.size());
      final double med =
          sum(
              b,
              x ->
                  x.getAsJsonObject("round_per_direzione").has(MEDITERRANEO)
                      ? x.getAsJsonObject("round_per_direzione").get(MEDITERRANEO).getAsDouble()
                      : 0);
      final double fr =
          sum(
              b,
              x ->
                  x.getAsJsonObject("round_per_direzione").has(FRANCIA)
                      ? x.getAsJsonObject("round_per_direzione").get(FRANCIA).getAsDouble()
                      : 0);
      final long pMed =
          b.stream()
              .filter(
                  x ->
                      x.has("direzione_prevalente")
                          && MEDITERRANEO.equals(x.get("direzione_prevalente").getAsString()))
              .count();
      final long pFr =
          b.stream()
              .filter(
                  x ->
                      x.has("direzione_prevalente")
                          && FRANCIA.equals(x.get("direzione_prevalente").getAsString()))
              .count();
      final Map<String, Double> dec = new TreeMap<>();
      b.forEach(
          x ->
              x.getAsJsonObject("decisioni_condivise")
                  .entrySet()
                  .forEach(d -> dec.merge(d.getKey(), d.getValue().getAsDouble(), Double::sum)));
      final double usFr = sum(b, x -> x.get("sbarchi_usa_con_francia").getAsDouble());
      final double usFrUk =
          sum(b, x -> x.get("sbarchi_usa_con_francia_britannici_fine_round").getAsDouble());
      final double jFr = sum(b, x -> x.get("sbarchi_congiunti_con_francia").getAsDouble());
      final double jFrHeld =
          sum(b, x -> x.get("sbarchi_congiunti_con_francia_tenuti_round_dopo").getAsDouble());
      final double usAll = sum(b, x -> x.get("sbarchi_usa_totale").getAsDouble());
      final double usHeld = sum(b, x -> x.get("sbarchi_usa_tenuti_round_dopo").getAsDouble());
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(f(med / n))
          .append(" / ")
          .append(f(fr / n))
          .append(" | ")
          .append(pMed)
          .append(" / ")
          .append(pFr)
          .append(" | ")
          .append(dec.isEmpty() ? "-" : dec.toString().replace("=", " ").replace(".0", ""))
          .append(" | ")
          .append(Math.round(usFr))
          .append(" / ")
          .append(Math.round(usFrUk))
          .append(" (")
          .append(pct(usFrUk, usFr))
          .append(") | ")
          .append(Math.round(jFr))
          .append(" / ")
          .append(Math.round(jFrHeld))
          .append(" (")
          .append(pct(jFrHeld, jFr))
          .append(") | ")
          .append(Math.round(usHeld))
          .append(" / ")
          .append(Math.round(usAll))
          .append(" (")
          .append(pct(usHeld, usAll))
          .append(") | ")
          .append(f(sum(b, x -> x.get("sbarchi_logoramento").getAsDouble()) / n))
          .append(" | ")
          .append(f(meanRound(b, "8", "a_bordo_totale")))
          .append(" / ")
          .append(f(meanRound(b, "12", "a_bordo_totale")))
          .append(" / ")
          .append(f(meanRound(b, "16", "a_bordo_totale")))
          .append(" | ")
          .append(f(meanRound(b, "12", "trasporti_uk")))
          .append(" / ")
          .append(f(meanRound(b, "12", "trasporti_uk_inattivi")))
          .append(" | ")
          .append(f(meanRound(b, "8", "usa_mediterraneo_nord_africa")))
          .append(" / ")
          .append(f(meanRound(b, "12", "usa_mediterraneo_nord_africa")))
          .append(" / ")
          .append(f(meanRound(b, "16", "usa_mediterraneo_nord_africa")))
          .append(" | ")
          .append(f(sum(b, x -> x.get("trasporti_usa_persi").getAsDouble()) / n))
          .append(" |\n");
    }
    // step P2-bis: transports and landings, task / Hard
    md.append(
"""

P2-bis, medie per partita (`p2bis`): trasporti comprati compito / Hard (`trasporti_comprati.N.compito/hard`), unita' sbarcate compito / Hard (`unita_sbarcate.N.compito/hard`), trasporti posseduti R4 / R8 / R12 (`trasporti_posseduti.N.4/8/12`); N = Americans, British.

| Configurazione | USA trasporti compito / Hard | UK trasporti compito / Hard | USA sbarcate compito / Hard | UK sbarcate compito / Hard | USA+UK sbarcate | USA trasporti R4 / R8 / R12 | UK trasporti R4 / R8 / R12 |
|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b =
          blocks(e.getValue()).stream()
              .filter(x -> x.has("p2bis"))
              .map(x -> x.getAsJsonObject("p2bis"))
              .toList();
      if (b.isEmpty()) {
        continue;
      }
      final int n = b.size();
      md.append("| ").append(e.getKey());
      for (final String what : List.of("trasporti_comprati", "unita_sbarcate")) {
        for (final String nat : NAZIONI_P2BIS) {
          md.append(" | ")
              .append(
                  f(
                      sum(
                              b,
                              x ->
                                  x.getAsJsonObject(what)
                                      .getAsJsonObject(nat)
                                      .get("compito")
                                      .getAsDouble())
                          / n))
              .append(" / ")
              .append(
                  f(
                      sum(
                              b,
                              x ->
                                  x.getAsJsonObject(what)
                                      .getAsJsonObject(nat)
                                      .get("hard")
                                      .getAsDouble())
                          / n));
        }
      }
      md.append(" | ")
          .append(
              f(
                  sum(
                          b,
                          x ->
                              x.getAsJsonObject("unita_sbarcate")
                                      .getAsJsonObject(MonitorP2.USA)
                                      .get("totale")
                                      .getAsDouble()
                                  + x.getAsJsonObject("unita_sbarcate")
                                      .getAsJsonObject(MonitorP2.UK)
                                      .get("totale")
                                      .getAsDouble())
                      / n));
      for (final String nat : NAZIONI_P2BIS) {
        md.append(" | ");
        final List<String> parts = new ArrayList<>();
        for (final int r : ROUND_TRASPORTI) {
          final List<JsonObject> with =
              b.stream()
                  .filter(
                      x ->
                          x.getAsJsonObject("trasporti_posseduti")
                              .getAsJsonObject(nat)
                              .has(String.valueOf(r)))
                  .toList();
          parts.add(
              with.isEmpty()
                  ? "-"
                  : f(
                      sum(
                              with,
                              x ->
                                  x.getAsJsonObject("trasporti_posseduti")
                                      .getAsJsonObject(nat)
                                      .get(String.valueOf(r))
                                      .getAsDouble())
                          / with.size()));
        }
        md.append(String.join(" / ", parts));
      }
      md.append(" |\n");
    }
  }

  /** The same Axis without the task (the Allies of Hard), if present. */
  @Nullable
  static String base(final String config, final Map<String, List<JsonObject>> byConfig) {
    if (!config.contains(ConfrontoP1.COMPITI)) {
      return null;
    }
    return ConfrontoP1.senzaCompiti(config, byConfig.keySet());
  }

  private static List<JsonObject> blocks(final List<JsonObject> games) {
    return games.stream().map(j -> j.getAsJsonObject("p2")).toList();
  }

  private static long falls(final List<JsonObject> b, final String key) {
    return b.stream().filter(x -> x.getAsJsonObject("cadute").has(key)).count();
  }

  private static double sum(final List<JsonObject> b, final Function<JsonObject, Double> f) {
    double s = 0;
    for (final JsonObject x : b) {
      try {
        s += f.apply(x);
      } catch (final RuntimeException e) {
        // missing field = 0
      }
    }
    return s;
  }

  private static double meanRound(final List<JsonObject> b, final String round, final String key) {
    final List<JsonObject> with =
        b.stream()
            .filter(
                x ->
                    x.getAsJsonObject("per_round").has(round)
                        && x.getAsJsonObject("per_round").getAsJsonObject(round).has(key))
            .toList();
    if (with.isEmpty()) {
      return 0;
    }
    return sum(
            with, x -> x.getAsJsonObject("per_round").getAsJsonObject(round).get(key).getAsDouble())
        / with.size();
  }

  private static String f(final double x) {
    return String.format(Locale.ROOT, "%.1f", x).replace('.', ',');
  }

  private static String pct(final double k, final double n) {
    return n <= 0 ? "-" : Math.round(100.0 * k / n) + "%";
  }
}
