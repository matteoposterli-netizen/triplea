package org.triplea.proplus.autoplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P5: the block {@code p5} of each game's {@code riepilogo.json} (monitor snapshots {@code
 * round[].p5}, {@code round[].p4}, {@code round[].nazioni}, the landings, the capitals' events and
 * the register rows {@code usa_proporzione}, {@code norvegia_scorta}) and the section "P5" of
 * {@code riepilogo-generale.md}. Every configuration, Hard included (no register rows: 0).
 */
final class ConfrontoP5 {
  private ConfrontoP5() {}

  static final List<String> INTERRUTTORI = List.of("usa_proporzione", "norvegia_scorta");
  static final String USA = "Americans";
  static final List<Integer> ROUND_ACQUISTI = List.of(1, 2, 3, 4, 5);
  static final List<String> TIPI = List.of("terra", "trasporti", "navi", "aerei");
  static final List<Integer> ROUND_FORZA = List.of(3, 4);
  static final List<Integer> ROUND_SBARCHI = List.of(5, 6, 8, 10);
  static final List<Integer> ROUND_NORVEGIA = List.of(2, 3, 4, 6);
  static final List<Integer> ROUND_REDDITO = List.of(4, 6);
  static final List<Integer> ROUND_ESERCITO = List.of(6, 8, 10);
  static final int ROUND_PERDITE_USA = 8;
  static final int ROUND_PERDITE_UK = 4;
  static final int ROUND_MOSCA = 14;
  static final int SBARCO_FORTE = 6;

  // ================================================================ block of one game

  static JsonObject blocco(final JsonObject json) {
    final JsonObject o = new JsonObject();
    final Map<Integer, JsonObject> snaps = new TreeMap<>();
    if (json.has("round")) {
      for (final JsonElement e : json.getAsJsonArray("round")) {
        final JsonObject r = e.getAsJsonObject();
        snaps.put(r.get("round").getAsInt(), r);
      }
    }
    final List<String> allies = alleati(json);
    // US purchases R1-R5 (PUs by kind)
    final JsonObject buy = new JsonObject();
    for (final int r : ROUND_ACQUISTI) {
      final JsonObject p5 = sub(snaps.get(r), "p5");
      if (p5 == null) {
        continue;
      }
      final JsonObject a = p5.has("usa_acquisti_pu") ? p5.getAsJsonObject("usa_acquisti_pu") : null;
      final JsonObject x = new JsonObject();
      for (final String t : TIPI) {
        x.addProperty(t, a == null ? 0 : num(a, t));
      }
      buy.add(String.valueOf(r), x);
    }
    o.add("usa_acquisti_pu", buy);
    // US force at the end of R3 and R4
    final JsonObject force = new JsonObject();
    for (final int r : ROUND_FORZA) {
      final JsonObject p5 = sub(snaps.get(r), "p5");
      if (p5 == null) {
        continue;
      }
      final JsonObject x = new JsonObject();
      x.addProperty("trasporti", num(p5, "usa_trasporti"));
      x.addProperty("navi_da_guerra", num(p5, "usa_navi_da_guerra"));
      x.addProperty("terra_in_patria", num(p5, "usa_terra_in_patria"));
      x.addProperty("terra_a_bordo", num(p5, "usa_terra_a_bordo"));
      x.addProperty(
          "terra_patria_o_bordo", num(p5, "usa_terra_in_patria") + num(p5, "usa_terra_a_bordo"));
      force.add(String.valueOf(r), x);
    }
    o.add("usa_forza", force);
    // US landings: first of >= 6 units, cumulated at R5/6/8/10 and where
    final Map<String, Integer> byPlace = new TreeMap<>();
    final Map<Integer, Integer> perRound = new TreeMap<>();
    final Map<Integer, Map<String, Integer>> whereByRound = new TreeMap<>();
    if (json.has("sbarchi") && json.get("sbarchi").isJsonArray()) {
      for (final JsonElement e : json.getAsJsonArray("sbarchi")) {
        final JsonObject l = e.getAsJsonObject();
        if (!USA.equals(l.get("nazione").getAsString())) {
          continue;
        }
        final int r = l.get("round").getAsInt();
        final String where = l.get("territorio").getAsString();
        final int n = l.get("unita").getAsInt();
        byPlace.merge(r + "|" + where, n, Integer::sum);
        perRound.merge(r, n, Integer::sum);
        whereByRound.computeIfAbsent(r, k -> new TreeMap<>()).merge(where, n, Integer::sum);
      }
    }
    int first = -1;
    for (final var e : byPlace.entrySet()) {
      if (e.getValue() >= SBARCO_FORTE) {
        final int r = Integer.parseInt(e.getKey().split("\\|")[0]);
        if (first < 0 || r < first) {
          first = r;
        }
      }
    }
    o.addProperty("round_primo_sbarco_usa_6", first);
    final JsonObject cum = new JsonObject();
    for (final int r : ROUND_SBARCHI) {
      int n = 0;
      final Map<String, Integer> where = new TreeMap<>();
      for (final var e : perRound.entrySet()) {
        if (e.getKey() <= r) {
          n += e.getValue();
          whereByRound.get(e.getKey()).forEach((k, v) -> where.merge(k, v, Integer::sum));
        }
      }
      final JsonObject x = new JsonObject();
      x.addProperty("totale", n);
      final JsonObject w = new JsonObject();
      where.forEach(w::addProperty);
      x.add("dove", w);
      cum.add(String.valueOf(r), x);
    }
    o.add("usa_sbarcate_cumulate", cum);
    // losses: US transports by R8, British warships by R4
    o.addProperty("usa_trasporti_persi_r8", persi(snaps, "usa_trasporti", ROUND_PERDITE_USA));
    o.addProperty("uk_navi_perse_r4", persi(snaps, "uk_navi_da_guerra", ROUND_PERDITE_UK));
    // Norway allied at the end of R2, R3, R4, R6
    final JsonObject nor = new JsonObject();
    for (final int r : ROUND_NORVEGIA) {
      final JsonObject p4 = sub(snaps.get(r), "p4");
      if (p4 != null && p4.has("proprietari")) {
        final JsonObject ow = p4.getAsJsonObject("proprietari");
        if (ow.has("Norway")) {
          nor.addProperty(String.valueOf(r), allies.contains(ow.get("Norway").getAsString()));
        }
      }
    }
    o.add("norway_alleata", nor);
    // German income at R4, R6
    final JsonObject inc = new JsonObject();
    for (final int r : ROUND_REDDITO) {
      final JsonObject n = sub(snaps.get(r), "nazioni");
      if (n != null && n.has("Germans")) {
        inc.addProperty(String.valueOf(r), num(n.getAsJsonObject("Germans"), "reddito"));
      }
    }
    o.add("reddito_tedesco", inc);
    // land armies (TUV) at R6, R8, R10
    final JsonObject army = new JsonObject();
    for (final int r : ROUND_ESERCITO) {
      final JsonObject p4 = sub(snaps.get(r), "p4");
      if (p4 != null && p4.has("esercito_tuv")) {
        army.add(String.valueOf(r), p4.getAsJsonObject("esercito_tuv").deepCopy());
      }
    }
    o.add("esercito_tuv", army);
    // first falls of Moscow, Rome, Berlin
    final JsonObject falls = new JsonObject();
    if (json.has("capitali") && json.get("capitali").isJsonArray()) {
      for (final JsonElement e : json.getAsJsonArray("capitali")) {
        final JsonObject c = e.getAsJsonObject();
        final String key = ConfrontoP4.CADUTE.get(c.get("territorio").getAsString());
        if (key != null && "caduta".equals(c.get("evento").getAsString()) && !falls.has(key)) {
          falls.addProperty(key, c.get("round").getAsInt());
        }
      }
    }
    o.add("cadute", falls);
    o.addProperty(
        "mosca_oltre_r14", !falls.has("mosca") || falls.get("mosca").getAsInt() > ROUND_MOSCA);
    o.addProperty("regno_unito_perso", caduta(json, "United Kingdom"));
    o.add("registro", registro(json));
    return o;
  }

  private static int persi(final Map<Integer, JsonObject> snaps, final String key, final int to) {
    int n = 0;
    for (final var e : snaps.entrySet()) {
      final JsonObject p5 = sub(e.getValue(), "p5");
      if (e.getKey() <= to && p5 != null && p5.has("perse")) {
        n += num(p5.getAsJsonObject("perse"), key);
      }
    }
    return n;
  }

  private static boolean caduta(final JsonObject json, final String territory) {
    if (json.has("capitali") && json.get("capitali").isJsonArray()) {
      for (final JsonElement e : json.getAsJsonArray("capitali")) {
        final JsonObject c = e.getAsJsonObject();
        if (territory.equals(c.get("territorio").getAsString())
            && "caduta".equals(c.get("evento").getAsString())) {
          return true;
        }
      }
    }
    return false;
  }

  private static JsonObject registro(final JsonObject json) {
    final JsonObject o = new JsonObject();
    int plans = 0;
    int puTransports = 0;
    int puEscort = 0;
    int loads = 0;
    int loaded = 0;
    int holds = 0;
    int found = 0;
    int missing = 0;
    int taken = 0;
    int escorts = 0;
    int escortsLost = 0;
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if (!"decisione".equals(d.get("riga").getAsString())) {
        continue;
      }
      final String tipo = d.get("tipo").getAsString();
      final String code = ConfrontoR5.codice(d);
      final JsonObject p = d.has("parametri") ? d.getAsJsonObject("parametri") : new JsonObject();
      if ("usa_proporzione".equals(tipo)) {
        if ("CARICATE".equals(code)) {
          loaded += num(p, "truppe_caricate_n");
        } else {
          plans++;
          puTransports +=
              p.has("passo_1_trasporti") ? num(p.getAsJsonObject("passo_1_trasporti"), "pu") : 0;
          puEscort += p.has("passo_2_scorta") ? num(p.getAsJsonObject("passo_2_scorta"), "pu") : 0;
          loads +=
              p.has("passo_3_carichi") ? num(p.getAsJsonObject("passo_3_carichi"), "carichi") : 0;
          if (p.has("convoglio_regge") && p.get("convoglio_regge").getAsBoolean()) {
            holds++;
          }
        }
      } else if ("norvegia_scorta".equals(tipo)) {
        switch (code) {
          case "SCORTA_TROVATA" -> {
            found++;
            if (p.has("preso") && p.get("preso").getAsBoolean()) {
              taken++;
            }
          }
          case "SCORTA_MANCANTE" -> missing++;
          case "PERDITE_SCORTA" -> {
            escorts += num(p, "navi_scorta");
            escortsLost += num(p, "navi_scorta_perse");
          }
          default -> {
            // other rows
          }
        }
      }
    }
    o.addProperty("usa_turni_piano", plans);
    o.addProperty("usa_pu_trasporti_piano", puTransports);
    o.addProperty("usa_pu_scorta_piano", puEscort);
    o.addProperty("usa_carichi_piano", loads);
    o.addProperty("usa_turni_convoglio_regge", holds);
    o.addProperty("usa_truppe_caricate", loaded);
    o.addProperty("norway_scorta_trovata", found);
    o.addProperty("norway_scorta_mancante", missing);
    o.addProperty("norway_scortata_presa", taken);
    o.addProperty("norway_navi_scorta", escorts);
    o.addProperty("norway_navi_scorta_perse", escortsLost);
    return o;
  }

  private static List<String> alleati(final JsonObject json) {
    final List<String> out = new ArrayList<>();
    if (json.has("alleanze") && json.getAsJsonObject("alleanze").has("Allies")) {
      json.getAsJsonObject("alleanze")
          .getAsJsonArray("Allies")
          .forEach(e -> out.add(e.getAsString()));
    }
    return out;
  }

  private static @Nullable JsonObject sub(@Nullable final JsonObject snap, final String key) {
    return snap != null && snap.has(key) && snap.get(key).isJsonObject()
        ? snap.getAsJsonObject(key)
        : null;
  }

  private static int num(final JsonObject o, final String k) {
    return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
  }

  // ======================================================================= the section

  static boolean applicabile(final List<Game> games) {
    return games.stream()
        .anyMatch(
            g ->
                g.json() != null
                    && g.json().has("p5")
                    && INTERRUTTORI.stream()
                        .anyMatch(
                            s ->
                                RiepilogoGenerale.strategistLabel(g.json(), "Allies").contains(s)));
  }

  /** The reference: the configuration with none of the switches and the Allies of Hard. */
  @Nullable
  static String riferimento(final Set<String> configs) {
    final List<String> all = new ArrayList<>(INTERRUTTORI);
    all.addAll(ConfrontoP4.INTERRUTTORI);
    for (final String c : configs) {
      if (all.stream().noneMatch(c::contains) && c.endsWith("Hard")) {
        return c;
      }
    }
    for (final String c : configs) {
      if (all.stream().noneMatch(c::contains)) {
        return c;
      }
    }
    return null;
  }

  static void write(final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("p5") || g.json().getAsJsonObject("p5").has("errore")) {
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
    final String ref = riferimento(byConfig.keySet());
    md.append(
"""


### P5

""");
    md.append(
        "Due leve (usa_proporzione, norvegia_scorta). Somme e medie del blocco `p5` dei"
            + " `riepilogo.json` (campo tra parentesi). Riferimento: "
            + (ref == null ? "-" : ref)
            + ". Mosca oltre R14 = Russia non caduta entro il round 14 (`mosca_oltre_r14`).\n\n");
    md.append(
"""
| Configurazione | Partite | Asse | Alleati | Stalli | Quota Alleati [IC 95%] | contro riferimento (Fisher) | Mosca oltre R14 [IC 95%] | contro riferimento (Fisher) |
|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> list = e.getValue();
      final int n = list.size();
      final int axis = ConfrontoR4.wins(list, "Axis");
      final int allies = ConfrontoR4.wins(list, "Allies");
      final int moscow = mosca(list);
      final List<JsonObject> base =
          ref == null || ref.equals(e.getKey()) ? null : byConfig.get(ref);
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
          .append(
              base == null
                  ? "-"
                  : fisherText(allies, n, ConfrontoR4.wins(base, "Allies"), base.size()))
          .append(" | ")
          .append(moscow)
          .append("/")
          .append(n)
          .append(" ")
          .append(ConfrontoR4.wilsonText(moscow, n))
          .append(" | ")
          .append(base == null ? "-" : fisherText(moscow, n, mosca(base), base.size()))
          .append(" |\n");
    }
    // A: purchases
    md.append(
"""

A, acquisti USA per round in PU, terra / trasporti / navi / aerei (`usa_acquisti_pu.N`, media).

| Configurazione | R1 | R2 | R3 | R4 | R5 |
|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      md.append("| ").append(e.getKey());
      for (final int r : ROUND_ACQUISTI) {
        final List<String> k = new ArrayList<>();
        for (final String t : TIPI) {
          k.add(f(ConfrontoP4.mean(b, x -> path(x, "usa_acquisti_pu", r, t))));
        }
        md.append(" | ").append(String.join(" / ", k));
      }
      md.append(" |\n");
    }
    // A: force and landings
    md.append(
"""

A, forza USA a fine R3 e R4: trasporti / navi da guerra / unita' di terra in patria / a bordo (`usa_forza.N`, media); partite con almeno 6 trasporti a fine R3; round mediano del primo sbarco USA di almeno 6 unita' (`round_primo_sbarco_usa_6`, partite senza: escluse); sbarcate cumulate R5 / R6 / R8 / R10 (`usa_sbarcate_cumulate.N.totale`, media); trasporti USA persi entro R8 (`usa_trasporti_persi_r8`, media); registro (`registro`): turni di piano / PU trasporti / PU scorta / carichi / truppe caricate.

| Configurazione | R3 | R4 | >= 6 trasporti a R3 | Primo sbarco >= 6: mediana (partite) | Sbarcate R5 / R6 / R8 / R10 | Trasporti persi entro R8 | Registro A |
|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      md.append("| ").append(e.getKey());
      for (final int r : ROUND_FORZA) {
        md.append(" | ")
            .append(f(ConfrontoP4.mean(b, x -> path(x, "usa_forza", r, "trasporti"))))
            .append(" / ")
            .append(f(ConfrontoP4.mean(b, x -> path(x, "usa_forza", r, "navi_da_guerra"))))
            .append(" / ")
            .append(f(ConfrontoP4.mean(b, x -> path(x, "usa_forza", r, "terra_in_patria"))))
            .append(" / ")
            .append(f(ConfrontoP4.mean(b, x -> path(x, "usa_forza", r, "terra_a_bordo"))));
      }
      final List<Integer> firsts = new ArrayList<>();
      b.forEach(
          x -> {
            final int r = x.get("round_primo_sbarco_usa_6").getAsInt();
            if (r > 0) {
              firsts.add(r);
            }
          });
      final List<String> landed = new ArrayList<>();
      for (final int r : ROUND_SBARCHI) {
        landed.add(f(ConfrontoP4.mean(b, x -> path(x, "usa_sbarcate_cumulate", r, "totale"))));
      }
      md.append(" | ")
          .append(count(b, x -> path(x, "usa_forza", 3, "trasporti") >= 6))
          .append(" su ")
          .append(b.size())
          .append(" | ")
          .append(firsts.isEmpty() ? "-" : f(ConfrontoP4.median(firsts)))
          .append(" (")
          .append(firsts.size())
          .append(") | ")
          .append(String.join(" / ", landed))
          .append(" | ")
          .append(f(ConfrontoP4.mean(b, x -> x.get("usa_trasporti_persi_r8").getAsDouble())))
          .append(" | ")
          .append(sum(b, x -> reg(x, "usa_turni_piano")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "usa_pu_trasporti_piano")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "usa_pu_scorta_piano")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "usa_carichi_piano")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "usa_truppe_caricate")))
          .append(" |\n");
    }
    // where the USA landed by R10
    md.append(
"""

A, dove sbarcano gli USA entro R10 (`usa_sbarcate_cumulate.10.dove`, somma sulle partite, i 6 territori principali).

| Configurazione | Territori (unita') |
|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final Map<String, Integer> where = new TreeMap<>();
      for (final JsonObject x : blocchi(e.getValue())) {
        try {
          x.getAsJsonObject("usa_sbarcate_cumulate")
              .getAsJsonObject("10")
              .getAsJsonObject("dove")
              .entrySet()
              .forEach(w -> where.merge(w.getKey(), w.getValue().getAsInt(), Integer::sum));
        } catch (final RuntimeException ex) {
          // game ended before R10
        }
      }
      final List<String> top =
          where.entrySet().stream()
              .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
              .limit(6)
              .map(w -> w.getKey() + " " + w.getValue())
              .toList();
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(String.join(", ", top))
          .append(" |\n");
    }
    // B
    md.append(
"""

B, Norway alleata a fine R2 / R3 / R4 / R6 in partite (`norway_alleata`), navi da guerra britanniche perse entro R4 (`uk_navi_perse_r4`, media), United Kingdom perso (`regno_unito_perso`), reddito tedesco R4 / R6 (`reddito_tedesco`, media); registro (`registro`): sbarchi scortati / annullati per scorta mancante / scortati e presi, navi di scorta / perse nel turno nemico.

| Configurazione | Norway R2 / R3 / R4 / R6 | Navi UK perse entro R4 | UK perso | Reddito tedesco R4 / R6 | Registro B |
|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      final List<String> nor = new ArrayList<>();
      for (final int r : ROUND_NORVEGIA) {
        nor.add(String.valueOf(count(b, x -> flag(x, "norway_alleata", r))));
      }
      final List<String> inc = new ArrayList<>();
      for (final int r : ROUND_REDDITO) {
        inc.add(f(ConfrontoP4.mean(b, x -> single(x, "reddito_tedesco", r))));
      }
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(String.join(" / ", nor))
          .append(" su ")
          .append(b.size())
          .append(" | ")
          .append(f(ConfrontoP4.mean(b, x -> x.get("uk_navi_perse_r4").getAsDouble())))
          .append(" | ")
          .append(count(b, x -> x.get("regno_unito_perso").getAsBoolean()))
          .append(" | ")
          .append(String.join(" / ", inc))
          .append(" | ")
          .append(sum(b, x -> reg(x, "norway_scorta_trovata")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "norway_scorta_mancante")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "norway_scortata_presa")))
          .append(", ")
          .append(sum(b, x -> reg(x, "norway_navi_scorta")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "norway_navi_scorta_perse")))
          .append(" |\n");
    }
    // the war
    md.append(
"""

Esercito di terra russo e tedesco in TUV a R6 / R8 / R10 (`esercito_tuv`, media); cadute (`cadute`): Mosca, Roma (Southern Italy), Berlino (Germany) in partite e round mediano.

| Configurazione | Russo R6 / R8 / R10 | Tedesco R6 / R8 / R10 | Mosca | Roma | Berlino |
|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(eserciti(b, "Russians"))
          .append(" | ")
          .append(eserciti(b, "Germans"));
      for (final String k : List.of("mosca", "roma", "berlino")) {
        final List<Integer> rounds = new ArrayList<>();
        b.forEach(
            x -> {
              if (x.getAsJsonObject("cadute").has(k)) {
                rounds.add(x.getAsJsonObject("cadute").get(k).getAsInt());
              }
            });
        md.append(" | ")
            .append(rounds.size())
            .append(rounds.isEmpty() ? "" : " (R" + f(ConfrontoP4.median(rounds)) + ")");
      }
      md.append(" |\n");
    }
  }

  static int mosca(final List<JsonObject> list) {
    int n = 0;
    for (final JsonObject j : list) {
      final JsonObject p = j.getAsJsonObject("p5");
      if (p != null && p.has("mosca_oltre_r14") && p.get("mosca_oltre_r14").getAsBoolean()) {
        n++;
      }
    }
    return n;
  }

  private static String fisherText(final int k, final int n, final int k2, final int n2) {
    return (k - k2 >= 0 ? "+" : "")
        + (k - k2)
        + " (rif. "
        + k2
        + "/"
        + n2
        + ", p "
        + ConfrontoR5.pText(ConfrontoR4.fisher(k, n, k2, n2))
        + ")";
  }

  private static List<JsonObject> blocchi(final List<JsonObject> games) {
    return games.stream().map(j -> j.getAsJsonObject("p5")).toList();
  }

  private static double path(final JsonObject x, final String key, final int r, final String k) {
    final JsonObject o = x.getAsJsonObject(key);
    if (o == null || !o.has(String.valueOf(r))) {
      return Double.NaN;
    }
    final JsonObject y = o.getAsJsonObject(String.valueOf(r));
    return y.has(k) ? y.get(k).getAsDouble() : Double.NaN;
  }

  private static double single(final JsonObject x, final String key, final int r) {
    final JsonObject o = x.getAsJsonObject(key);
    return o != null && o.has(String.valueOf(r))
        ? o.get(String.valueOf(r)).getAsDouble()
        : Double.NaN;
  }

  private static boolean flag(final JsonObject x, final String key, final int r) {
    final JsonObject o = x.getAsJsonObject(key);
    return o != null && o.has(String.valueOf(r)) && o.get(String.valueOf(r)).getAsBoolean();
  }

  private static double reg(final JsonObject x, final String k) {
    final JsonObject o = x.getAsJsonObject("registro");
    return o != null && o.has(k) ? o.get(k).getAsDouble() : 0;
  }

  private static String eserciti(final List<JsonObject> b, final String nation) {
    final List<String> out = new ArrayList<>();
    for (final int r : ROUND_ESERCITO) {
      out.add(
          f(
              ConfrontoP4.mean(
                  b,
                  x -> {
                    final JsonObject o = x.getAsJsonObject("esercito_tuv");
                    return o != null
                            && o.has(String.valueOf(r))
                            && o.getAsJsonObject(String.valueOf(r)).has(nation)
                        ? o.getAsJsonObject(String.valueOf(r)).get(nation).getAsDouble()
                        : Double.NaN;
                  })));
    }
    return String.join(" / ", out);
  }

  private static long sum(final List<JsonObject> b, final Function<JsonObject, Double> f) {
    double s = 0;
    for (final JsonObject x : b) {
      try {
        s += f.apply(x);
      } catch (final RuntimeException e) {
        // missing field = 0
      }
    }
    return Math.round(s);
  }

  private static long count(
      final List<JsonObject> b, final java.util.function.Predicate<JsonObject> p) {
    return b.stream()
        .filter(
            x -> {
              try {
                return p.test(x);
              } catch (final RuntimeException e) {
                return false;
              }
            })
        .count();
  }

  private static String f(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.1f", x).replace('.', ',');
  }
}
