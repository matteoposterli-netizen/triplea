package org.triplea.proplus.autoplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
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
 * Step P4: the block {@code p4} of each game's {@code riepilogo.json} (monitor snapshots {@code
 * round[].p4}, {@code round[].p1}, {@code round[].nazioni}, the landings, the capitals' events and
 * the register rows {@code usa_pronti}, {@code norvegia_presto}, {@code russia_fronte}) and the
 * section "P4" of {@code riepilogo-generale.md}. Every configuration, Hard included (the register
 * rows are then absent: 0).
 */
final class ConfrontoP4 {
  private ConfrontoP4() {}

  static final List<String> INTERRUTTORI =
      List.of("usa_pronti", "norvegia_presto", "russia_fronte");
  static final String USA = "Americans";
  static final List<Integer> ROUND_SBARCHI = List.of(5, 6, 8, 10);
  static final List<Integer> ROUND_FLOTTA = List.of(3, 4, 5);
  static final List<Integer> ROUND_NORVEGIA = List.of(3, 4, 6);
  static final List<Integer> ROUND_REDDITO = List.of(4, 6);
  static final List<String> FRONTE = List.of("Novgorod", "Ukraine", "Volgograd");
  static final List<Integer> ROUND_FRONTE = List.of(4, 5, 6, 8);
  static final List<Integer> ROUND_ESERCITO = List.of(6, 8, 10);
  static final Map<String, String> CADUTE =
      Map.of("Russia", "mosca", "Southern Italy", "roma", "Germany", "berlino");
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
    // US landings: cumulated at R5/6/8/10 (north / Mediterranean), first landing of >= 6 units
    final Map<Integer, Map<String, Integer>> perRound = new TreeMap<>();
    final Map<String, Integer> byPlace = new TreeMap<>();
    if (json.has("sbarchi") && json.get("sbarchi").isJsonArray()) {
      for (final JsonElement e : json.getAsJsonArray("sbarchi")) {
        final JsonObject l = e.getAsJsonObject();
        if (!USA.equals(l.get("nazione").getAsString())) {
          continue;
        }
        final int r = l.get("round").getAsInt();
        final String where = l.get("territorio").getAsString();
        final int n = l.get("unita").getAsInt();
        perRound.computeIfAbsent(r, k -> new TreeMap<>()).merge(regione(where), n, Integer::sum);
        byPlace.merge(r + "|" + where, n, Integer::sum);
      }
    }
    final JsonObject cum = new JsonObject();
    for (final int r : ROUND_SBARCHI) {
      int north = 0;
      int med = 0;
      for (final var e : perRound.entrySet()) {
        if (e.getKey() <= r) {
          north += e.getValue().getOrDefault("nord", 0);
          med += e.getValue().getOrDefault("mediterraneo", 0);
        }
      }
      final JsonObject x = new JsonObject();
      x.addProperty("nord", north);
      x.addProperty("mediterraneo", med);
      x.addProperty("totale", north + med);
      cum.add(String.valueOf(r), x);
    }
    o.add("usa_sbarcate_cumulate", cum);
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
    // US transports and warships at the end of R3-R5
    final JsonObject tr = new JsonObject();
    final JsonObject ws = new JsonObject();
    for (final int r : ROUND_FLOTTA) {
      final JsonObject p1 = sub(snaps.get(r), "p1");
      if (p1 != null) {
        tr.addProperty(String.valueOf(r), num(p1, "trasporti_usa"));
        ws.addProperty(String.valueOf(r), num(p1, "navi_da_guerra_usa"));
      }
    }
    o.add("trasporti_usa", tr);
    o.add("navi_da_guerra_usa", ws);
    // Norway allied at the end of R3, R4, R6
    final JsonObject nor = new JsonObject();
    for (final int r : ROUND_NORVEGIA) {
      owner(snaps.get(r), "Norway")
          .ifPresent(w -> nor.addProperty(String.valueOf(r), allies.contains(w)));
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
    // Russian front factories at the end of R4, R5, R6, R8
    final JsonObject front = new JsonObject();
    for (final String t : FRONTE) {
      final JsonObject x = new JsonObject();
      for (final int r : ROUND_FRONTE) {
        owner(snaps.get(r), t)
            .ifPresent(w -> x.addProperty(String.valueOf(r), "Russians".equals(w)));
      }
      front.add(t, x);
    }
    o.add("russe_fine_round", front);
    // Russian attacks R3-R8 and Russian repairs per round
    final JsonObject att = new JsonObject();
    final JsonObject rep = new JsonObject();
    for (final var e : snaps.entrySet()) {
      final int r = e.getKey();
      final JsonObject p4 = sub(e.getValue(), "p4");
      if (r >= 3 && r <= 8 && p4 != null && p4.has("attacchi_russi")) {
        final JsonObject a = p4.getAsJsonObject("attacchi_russi");
        final JsonObject x = new JsonObject();
        x.addProperty("attaccanti", num(a, "attaccanti"));
        x.addProperty("nemici_uccisi", num(a, "nemici_uccisi"));
        att.add(String.valueOf(r), x);
      }
      final JsonObject n = sub(e.getValue(), "nazioni");
      if (n != null && n.has("Russians")) {
        rep.addProperty(String.valueOf(r), num(n.getAsJsonObject("Russians"), "pu_riparazioni"));
      }
    }
    o.add("attacchi_russi", att);
    o.add("pu_riparazioni_russe", rep);
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
        final String key = CADUTE.get(c.get("territorio").getAsString());
        if (key != null && "caduta".equals(c.get("evento").getAsString()) && !falls.has(key)) {
          falls.addProperty(key, c.get("round").getAsInt());
        }
      }
    }
    o.add("cadute", falls);
    o.addProperty(
        "mosca_oltre_r14", !falls.has("mosca") || falls.get("mosca").getAsInt() > ROUND_MOSCA);
    // United Kingdom lost
    o.addProperty("regno_unito_perso", caduta(json, "United Kingdom"));
    // the register rows of the switches
    o.add("registro", registro(json));
    return o;
  }

  /** North (Western Europe, elsewhere) or Mediterranean (Italy and islands, North Africa). */
  static String regione(final String territory) {
    final String r = ConfrontoR5.regione(territory);
    return "italia_isole".equals(r) || "nord_africa".equals(r) ? "mediterraneo" : "nord";
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
    int neutral = 0;
    final Map<String, Integer> bought = new TreeMap<>();
    int norway = 0;
    int norwayAttacked = 0;
    int norwayTaken = 0;
    int counters = 0;
    int counterAttacked = 0;
    int counterBelow = 0;
    int counterTaken = 0;
    int stance = 0;
    int repaired = 0;
    int saved = 0;
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if (!"decisione".equals(d.get("riga").getAsString())) {
        continue;
      }
      final String tipo = d.get("tipo").getAsString();
      final JsonObject p = d.has("parametri") ? d.getAsJsonObject("parametri") : new JsonObject();
      switch (tipo) {
        case "usa_pronti" -> {
          if ("ACQUISTO_NEUTRALE".equals(ConfrontoR5.codice(d)) && p.has("acquisti")) {
            neutral++;
            p.getAsJsonObject("acquisti")
                .entrySet()
                .forEach(x -> bought.merge(x.getKey(), x.getValue().getAsInt(), Integer::sum));
          }
        }
        case "norvegia_presto" -> {
          for (final JsonElement b : p.getAsJsonArray("bersagli")) {
            final JsonObject x = b.getAsJsonObject();
            norway++;
            if (x.get("attaccato").getAsBoolean()) {
              norwayAttacked++;
            }
            if (x.get("alleato_fine_turno").getAsBoolean()) {
              norwayTaken++;
            }
          }
        }
        case "russia_fronte" -> {
          for (final JsonElement b : p.getAsJsonArray("contrattacchi")) {
            final JsonObject x = b.getAsJsonObject();
            counters++;
            if (x.get("attaccato").getAsBoolean()) {
              counterAttacked++;
              if (x.has("sotto_soglia_standard") && x.get("sotto_soglia_standard").getAsBoolean()) {
                counterBelow++;
              }
              if (x.get("preso").getAsBoolean()) {
                counterTaken++;
              }
            }
            if (x.get("tenuto_in_difensiva").getAsBoolean()) {
              stance++;
            }
          }
          repaired += num(p, "pu_riparati");
          saved += num(p, "pu_risparmiati");
        }
        default -> {
          // other steps' rows
        }
      }
    }
    o.addProperty("usa_turni_neutrali", neutral);
    final JsonObject b = new JsonObject();
    bought.forEach(b::addProperty);
    o.add("usa_acquisti_neutrali", b);
    o.addProperty("norway_turni_peso", norway);
    o.addProperty("norway_attaccata", norwayAttacked);
    o.addProperty("norway_alleata_dopo", norwayTaken);
    o.addProperty("contrattacchi_candidati", counters);
    o.addProperty("contrattacchi_fatti", counterAttacked);
    o.addProperty("contrattacchi_sotto_soglia_standard", counterBelow);
    o.addProperty("contrattacchi_presi", counterTaken);
    o.addProperty("tenuti_in_difensiva", stance);
    o.addProperty("pu_riparati_registro", repaired);
    o.addProperty("pu_risparmiati", saved);
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

  private static java.util.Optional<String> owner(
      @Nullable final JsonObject snap, final String territory) {
    final JsonObject p4 = sub(snap, "p4");
    if (p4 == null || !p4.has("proprietari")) {
      return java.util.Optional.empty();
    }
    final JsonObject ow = p4.getAsJsonObject("proprietari");
    return ow.has(territory)
        ? java.util.Optional.of(ow.get(territory).getAsString())
        : java.util.Optional.empty();
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
                    && g.json().has("p4")
                    && INTERRUTTORI.stream()
                        .anyMatch(
                            s ->
                                RiepilogoGenerale.strategistLabel(g.json(), "Allies").contains(s)));
  }

  /** The reference: the configuration with none of the switches and the Allies of Hard. */
  @Nullable
  static String riferimento(final Set<String> configs) {
    for (final String c : configs) {
      if (INTERRUTTORI.stream().noneMatch(c::contains) && c.endsWith("Hard")) {
        return c;
      }
    }
    for (final String c : configs) {
      if (INTERRUTTORI.stream().noneMatch(c::contains)) {
        return c;
      }
    }
    return null;
  }

  static void write(final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("p4") || g.json().getAsJsonObject("p4").has("errore")) {
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


### P4

""");
    md.append(
        "Tre indicazioni a Hard (usa_pronti, norvegia_presto, russia_fronte). Somme e medie del"
            + " blocco `p4` dei `riepilogo.json` (campo tra parentesi). Riferimento: "
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
    // A
    md.append(
"""

A (`usa_pronti`): unita' USA sbarcate cumulate a R5 / R6 / R8 / R10 (`usa_sbarcate_cumulate.N.totale`, media; tra parentesi nord / Mediterraneo a R8), round mediano del primo sbarco USA di almeno 6 unita' (`round_primo_sbarco_usa_6`; partite senza: escluse, conteggio), trasporti e navi da guerra USA a R3 / R4 / R5 (`trasporti_usa`, `navi_da_guerra_usa`).

| Configurazione | Sbarcate R5 / R6 / R8 / R10 | R8 nord / Med | Primo sbarco >= 6: mediana (partite) | Trasporti R3 / R4 / R5 | Navi da guerra R3 / R4 / R5 |
|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      final List<String> landed = new ArrayList<>();
      for (final int r : ROUND_SBARCHI) {
        landed.add(f(mean(b, x -> path(x, "usa_sbarcate_cumulate", r, "totale"))));
      }
      final List<Integer> firsts = new ArrayList<>();
      b.forEach(
          x -> {
            final int r = x.get("round_primo_sbarco_usa_6").getAsInt();
            if (r > 0) {
              firsts.add(r);
            }
          });
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(String.join(" / ", landed))
          .append(" | ")
          .append(f(mean(b, x -> path(x, "usa_sbarcate_cumulate", 8, "nord"))))
          .append(" / ")
          .append(f(mean(b, x -> path(x, "usa_sbarcate_cumulate", 8, "mediterraneo"))))
          .append(" | ")
          .append(firsts.isEmpty() ? "-" : f(median(firsts)))
          .append(" (")
          .append(firsts.size())
          .append(") | ")
          .append(serie(b, "trasporti_usa", ROUND_FLOTTA))
          .append(" | ")
          .append(serie(b, "navi_da_guerra_usa", ROUND_FLOTTA))
          .append(" |\n");
    }
    // B
    md.append(
"""

B (`norvegia_presto`): Norway alleata a fine R3 / R4 / R6 in partite (`norway_alleata`), reddito tedesco R4 / R6 (`reddito_tedesco`, media), United Kingdom perso (`regno_unito_perso`), turni con il peso / Norway attaccata da Hard britannico / alleata a fine turno (`registro.norway_*`).

| Configurazione | Norway alleata R3 / R4 / R6 | Reddito tedesco R4 / R6 | UK perso | Registro: turni / attaccata / alleata |
|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      final List<String> nor = new ArrayList<>();
      for (final int r : ROUND_NORVEGIA) {
        nor.add(String.valueOf(count(b, x -> flag(x, "norway_alleata", r))));
      }
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(String.join(" / ", nor))
          .append(" su ")
          .append(b.size())
          .append(" | ")
          .append(serie(b, "reddito_tedesco", ROUND_REDDITO))
          .append(" | ")
          .append(count(b, x -> x.get("regno_unito_perso").getAsBoolean()))
          .append(" | ")
          .append(sum(b, x -> reg(x, "norway_turni_peso")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "norway_attaccata")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "norway_alleata_dopo")))
          .append(" |\n");
    }
    // C
    md.append(
"""

C (`russia_fronte`): Novgorod / Ukraine / Volgograd russe a fine R4, R5, R6, R8 in partite (`russe_fine_round`), PU russi in riparazioni R4-R6 e R1-R12 (media, `pu_riparazioni_russe`), esercito di terra russo e tedesco in TUV a R6 / R8 / R10 (`esercito_tuv`, media).

| Configurazione | Novgorod R4/5/6/8 | Ukraine R4/5/6/8 | Volgograd R4/5/6/8 | Riparazioni R4-R6 / R1-R12 | Esercito russo R6 / R8 / R10 | Esercito tedesco R6 / R8 / R10 |
|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      md.append("| ").append(e.getKey());
      for (final String t : FRONTE) {
        final List<String> k = new ArrayList<>();
        for (final int r : ROUND_FRONTE) {
          k.add(String.valueOf(count(b, x -> fronte(x, t, r))));
        }
        md.append(" | ").append(String.join("/", k));
      }
      md.append(" | ")
          .append(f(mean(b, x -> riparazioni(x, 4, 6))))
          .append(" / ")
          .append(f(mean(b, x -> riparazioni(x, 1, 12))))
          .append(" | ")
          .append(eserciti(b, "Russians"))
          .append(" | ")
          .append(eserciti(b, "Germans"))
          .append(" |\n");
    }
    md.append(
"""

C, attacchi russi per round R3-R8: unita' russe attaccanti / unita' nemiche uccise (`attacchi_russi.N`, media); cadute (`cadute`): Mosca, Roma, Berlino in partite e round mediano; registro di `russia_fronte` (`registro`: contrattacchi candidati / fatti / sotto la soglia standard / presi, tenuti in difensiva, PU risparmiati).

| Configurazione | R3 | R4 | R5 | R6 | R7 | R8 | Mosca | Roma | Berlino | Registro C |
|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      md.append("| ").append(e.getKey());
      for (int r = 3; r <= 8; r++) {
        final int rr = r;
        md.append(" | ")
            .append(f(mean(b, x -> path(x, "attacchi_russi", rr, "attaccanti"))))
            .append(" / ")
            .append(f(mean(b, x -> path(x, "attacchi_russi", rr, "nemici_uccisi"))));
      }
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
            .append(rounds.isEmpty() ? "" : " (R" + f(median(rounds)) + ")");
      }
      md.append(" | ")
          .append(sum(b, x -> reg(x, "contrattacchi_candidati")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "contrattacchi_fatti")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "contrattacchi_sotto_soglia_standard")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "contrattacchi_presi")))
          .append(", ")
          .append(sum(b, x -> reg(x, "tenuti_in_difensiva")))
          .append(", ")
          .append(sum(b, x -> reg(x, "pu_risparmiati")))
          .append(" PU |\n");
    }
  }

  static int mosca(final List<JsonObject> list) {
    int n = 0;
    for (final JsonObject j : list) {
      final JsonObject p = j.getAsJsonObject("p4");
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
    return games.stream().map(j -> j.getAsJsonObject("p4")).toList();
  }

  private static double path(final JsonObject x, final String key, final int r, final String k) {
    final JsonObject o = x.getAsJsonObject(key);
    if (o == null || !o.has(String.valueOf(r))) {
      return Double.NaN;
    }
    final JsonObject y = o.getAsJsonObject(String.valueOf(r));
    return y.has(k) ? y.get(k).getAsDouble() : Double.NaN;
  }

  private static boolean flag(final JsonObject x, final String key, final int r) {
    final JsonObject o = x.getAsJsonObject(key);
    return o != null && o.has(String.valueOf(r)) && o.get(String.valueOf(r)).getAsBoolean();
  }

  private static boolean fronte(final JsonObject x, final String t, final int r) {
    final JsonObject o = x.getAsJsonObject("russe_fine_round");
    return o != null && o.has(t) && flag(o, t, r);
  }

  private static double riparazioni(final JsonObject x, final int from, final int to) {
    final JsonObject o = x.getAsJsonObject("pu_riparazioni_russe");
    double s = 0;
    for (int r = from; r <= to; r++) {
      if (o != null && o.has(String.valueOf(r))) {
        s += o.get(String.valueOf(r)).getAsDouble();
      }
    }
    return s;
  }

  private static double reg(final JsonObject x, final String k) {
    final JsonObject o = x.getAsJsonObject("registro");
    return o != null && o.has(k) ? o.get(k).getAsDouble() : 0;
  }

  private static String serie(final List<JsonObject> b, final String key, final List<Integer> rs) {
    final List<String> out = new ArrayList<>();
    for (final int r : rs) {
      out.add(
          f(
              mean(
                  b,
                  x -> {
                    final JsonObject o = x.getAsJsonObject(key);
                    return o != null && o.has(String.valueOf(r))
                        ? o.get(String.valueOf(r)).getAsDouble()
                        : Double.NaN;
                  })));
    }
    return String.join(" / ", out);
  }

  private static String eserciti(final List<JsonObject> b, final String nation) {
    final List<String> out = new ArrayList<>();
    for (final int r : ROUND_ESERCITO) {
      out.add(
          f(
              mean(
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

  /** Mean over the games with the value (NaN = missing, e.g. a game ended before the round). */
  static double mean(final List<JsonObject> b, final Function<JsonObject, Double> f) {
    double s = 0;
    int n = 0;
    for (final JsonObject x : b) {
      try {
        final double v = f.apply(x);
        if (!Double.isNaN(v)) {
          s += v;
          n++;
        }
      } catch (final RuntimeException e) {
        // missing field
      }
    }
    return n == 0 ? Double.NaN : s / n;
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

  static double median(final List<Integer> values) {
    final List<Integer> v = new ArrayList<>(values);
    Collections.sort(v);
    final int n = v.size();
    return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
  }

  private static String f(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.1f", x).replace('.', ',');
  }
}
