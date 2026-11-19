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
import java.util.TreeSet;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P6: the block {@code p6} of each game's {@code riepilogo.json} (monitor snapshots {@code
 * round[].p6}, {@code round[].p5}, {@code round[].p4}, {@code round[].nazioni}, the landings, the
 * capitals' events and the register rows {@code sbarco_concentrato}, {@code norvegia_esposta}) and
 * the section "P6" of {@code riepilogo-generale.md}. Every configuration, Hard included (no
 * register rows: 0).
 */
final class ConfrontoP6 {
  private ConfrontoP6() {}

  static final List<String> INTERRUTTORI = List.of("sbarco_concentrato", "norvegia_esposta");
  static final String USA = "Americans";
  static final String UK = "British";
  static final String NORWAY = "Norway";
  static final List<String> TAGLIE = List.of("1-2", "3-5", "6-9", "10+");
  static final List<Integer> ROUND_SBARCHI = List.of(5, 6, 8, 10);
  static final List<Integer> ROUND_TEDESCHI = List.of(6, 8, 10);
  static final List<Integer> ROUND_NORVEGIA = List.of(2, 3, 4, 6, 8);
  static final List<Integer> ROUND_REDDITO = List.of(4, 6, 8);
  static final int ROUND_PERDITE_USA = 8;
  static final int ROUND_PERDITE_UK = 4;
  static final int ROUND_TAGLIE = 8;
  static final int ROUND_MOSCA = 14;
  static final int SBARCO_FORTE = 6;

  static String taglia(final int units) {
    if (units <= 2) {
      return "1-2";
    }
    if (units <= 5) {
      return "3-5";
    }
    return units <= 9 ? "6-9" : "10+";
  }

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
    // US landings on Axis land (the monitor's landings: land of an enemy at war at turn start)
    final Map<String, Integer> byPlace = new TreeMap<>();
    final Map<Integer, Integer> perRound = new TreeMap<>();
    final Map<Integer, Map<String, Integer>> whereByRound = new TreeMap<>();
    final JsonObject norway = new JsonObject();
    int norwayLandings = 0;
    int norwayUnits = 0;
    if (json.has("sbarchi") && json.get("sbarchi").isJsonArray()) {
      for (final JsonElement e : json.getAsJsonArray("sbarchi")) {
        final JsonObject l = e.getAsJsonObject();
        final String nation = l.get("nazione").getAsString();
        final int r = l.get("round").getAsInt();
        final String where = l.get("territorio").getAsString();
        final int n = l.get("unita").getAsInt();
        if (UK.equals(nation) && NORWAY.equals(where)) {
          norwayLandings++;
          norwayUnits += n;
          norway.addProperty(String.valueOf(r), n + num(norway, String.valueOf(r)));
        }
        if (!USA.equals(nation)) {
          continue;
        }
        byPlace.merge(r + "|" + where, n, Integer::sum);
        perRound.merge(r, n, Integer::sum);
        whereByRound.computeIfAbsent(r, k -> new TreeMap<>()).merge(where, n, Integer::sum);
      }
    }
    // landings by round and size (one landing = one territory in one round)
    final Map<Integer, Map<String, Integer>> sizes = new TreeMap<>();
    final Map<String, Integer> sizesR8 = new LinkedHashMap<>();
    TAGLIE.forEach(t -> sizesR8.put(t, 0));
    int first = -1;
    int strongR8 = 0;
    for (final var e : byPlace.entrySet()) {
      final int r = Integer.parseInt(e.getKey().split("\\|")[0]);
      final String size = taglia(e.getValue());
      sizes.computeIfAbsent(r, k -> new TreeMap<>()).merge(size, 1, Integer::sum);
      if (r <= ROUND_TAGLIE) {
        sizesR8.merge(size, 1, Integer::sum);
        if (e.getValue() >= SBARCO_FORTE) {
          strongR8++;
        }
      }
      if (e.getValue() >= SBARCO_FORTE && (first < 0 || r < first)) {
        first = r;
      }
    }
    final JsonObject perSize = new JsonObject();
    sizes.forEach(
        (r, m) -> {
          final JsonObject x = new JsonObject();
          m.forEach(x::addProperty);
          perSize.add(String.valueOf(r), x);
        });
    o.add("sbarchi_usa_per_round", perSize);
    final JsonObject r8 = new JsonObject();
    sizesR8.forEach(r8::addProperty);
    o.add("sbarchi_usa_taglie_r8", r8);
    o.addProperty("sbarchi_usa_6_entro_r8", strongR8);
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
    // US transports lost by R8 and in the whole game; British transports and warships by R4
    o.addProperty("usa_trasporti_persi_r8", persiP5(snaps, "usa_trasporti", ROUND_PERDITE_USA));
    o.addProperty("usa_trasporti_persi_totale", persiP5(snaps, "usa_trasporti", Integer.MAX_VALUE));
    final JsonObject uk = new JsonObject();
    int ukTr = 0;
    for (final var e : snaps.entrySet()) {
      final JsonObject p6 = sub(e.getValue(), "p6");
      if (e.getKey() <= ROUND_PERDITE_UK && p6 != null) {
        ukTr += num(p6, "uk_trasporti_persi");
      }
    }
    uk.addProperty("trasporti", ukTr);
    uk.addProperty("navi_da_guerra", persiP5(snaps, "uk_navi_da_guerra", ROUND_PERDITE_UK));
    o.add("uk_perse_r4", uk);
    // US troops on board at 3+ round ends in a row (the most in a round)
    int longAboard = 0;
    for (final JsonObject s : snaps.values()) {
      final JsonObject p6 = sub(s, "p6");
      if (p6 != null) {
        longAboard = Math.max(longAboard, num(p6, "usa_a_bordo_3_round"));
      }
    }
    o.addProperty("usa_a_bordo_3_round_max", longAboard);
    // German land units, in all and in Western Europe; land armies (TUV)
    final JsonObject ger = new JsonObject();
    final JsonObject army = new JsonObject();
    for (final int r : ROUND_TEDESCHI) {
      final JsonObject p6 = sub(snaps.get(r), "p6");
      if (p6 != null) {
        final JsonObject x = new JsonObject();
        x.addProperty("totale", num(p6, "tedeschi_terra"));
        x.addProperty("ovest", num(p6, "tedeschi_terra_ovest"));
        ger.add(String.valueOf(r), x);
      }
      final JsonObject p4 = sub(snaps.get(r), "p4");
      if (p4 != null && p4.has("esercito_tuv")) {
        army.add(String.valueOf(r), p4.getAsJsonObject("esercito_tuv").deepCopy());
      }
    }
    o.add("tedeschi", ger);
    o.add("esercito_tuv", army);
    // Norway allied at the end of R2, R3, R4, R6, R8; British landings there
    final JsonObject nor = new JsonObject();
    for (final int r : ROUND_NORVEGIA) {
      final JsonObject p4 = sub(snaps.get(r), "p4");
      if (p4 != null && p4.has("proprietari")) {
        final JsonObject ow = p4.getAsJsonObject("proprietari");
        if (ow.has(NORWAY)) {
          nor.addProperty(String.valueOf(r), allies.contains(ow.get(NORWAY).getAsString()));
        }
      }
    }
    o.add("norway_alleata", nor);
    final JsonObject nl = new JsonObject();
    nl.addProperty("sbarchi", norwayLandings);
    nl.addProperty("unita", norwayUnits);
    nl.add("per_round", norway);
    o.add("norway_sbarchi_uk", nl);
    // German income at R4, R6, R8
    final JsonObject inc = new JsonObject();
    for (final int r : ROUND_REDDITO) {
      final JsonObject n = sub(snaps.get(r), "nazioni");
      if (n != null && n.has("Germans")) {
        inc.addProperty(String.valueOf(r), num(n.getAsJsonObject("Germans"), "reddito"));
      }
    }
    o.add("reddito_tedesco", inc);
    // falls of Moscow, Rome (Southern Italy), Berlin (Germany)
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

  private static int persiP5(final Map<Integer, JsonObject> snaps, final String key, final int to) {
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

  /** The register's counts (0 without the levers). */
  static JsonObject registro(final JsonObject json) {
    final Map<String, Integer> c = new TreeMap<>();
    for (final String k :
        List.of(
            "sc_in_forza",
            "sc_in_forza_unita",
            "sc_dopo_attesa",
            "sc_dopo_attesa_unita",
            "sc_attese",
            "sc_avvicinamenti",
            "sc_turni_oltre_un_round",
            "sc_trasporti_oltre_un_round",
            "sc_raduni",
            "sc_presi",
            "sc_esiti",
            "sc_seguiti",
            "sc_ancora_alleati",
            "sc_trasporti_persi_dopo",
            "es_sbarchi",
            "es_presi",
            "es_non_eseguiti",
            "es_unita",
            "es_scorta_basta",
            "es_perdite_righe",
            "es_trasporti",
            "es_trasporti_persi",
            "es_navi_scorta",
            "es_navi_perse")) {
      c.put(k, 0);
    }
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if (!"decisione".equals(d.get("riga").getAsString())) {
        continue;
      }
      final String tipo = d.get("tipo").getAsString();
      final String code = ConfrontoR5.codice(d);
      final JsonObject p = d.has("parametri") ? d.getAsJsonObject("parametri") : new JsonObject();
      if ("sbarco_concentrato".equals(tipo)) {
        switch (code) {
          case "SBARCO_IN_FORZA" -> {
            c.merge("sc_in_forza", 1, Integer::sum);
            c.merge("sc_in_forza_unita", num(p, "unita_sbarcate"), Integer::sum);
          }
          case "SBARCO_DOPO_ATTESA" -> {
            c.merge("sc_dopo_attesa", 1, Integer::sum);
            c.merge("sc_dopo_attesa_unita", num(p, "unita_sbarcate"), Integer::sum);
          }
          case "ATTESA", "AVVICINAMENTO" -> {
            c.merge("ATTESA".equals(code) ? "sc_attese" : "sc_avvicinamenti", 1, Integer::sum);
            final int beyond = num(p, "oltre_un_round");
            if (beyond > 0) {
              c.merge("sc_turni_oltre_un_round", 1, Integer::sum);
              c.merge("sc_trasporti_oltre_un_round", beyond, Integer::sum);
            }
          }
          case "RADUNO" -> c.merge("sc_raduni", 1, Integer::sum);
          case "ESITO_SBARCO" -> {
            c.merge("sc_esiti", 1, Integer::sum);
            if (flag(p, "preso")) {
              c.merge("sc_presi", 1, Integer::sum);
            }
          }
          case "SEGUITO_SBARCO" -> {
            c.merge("sc_seguiti", 1, Integer::sum);
            if (flag(p, "ancora_alleato")) {
              c.merge("sc_ancora_alleati", 1, Integer::sum);
            }
            c.merge("sc_trasporti_persi_dopo", num(p, "trasporti_persi"), Integer::sum);
          }
          default -> {
            // other rows
          }
        }
      } else if ("norvegia_esposta".equals(tipo)) {
        switch (code) {
          case "SBARCO_ESPOSTO" -> {
            c.merge("es_sbarchi", 1, Integer::sum);
            c.merge("es_unita", num(p, "truppe_sbarcate"), Integer::sum);
            if (flag(p, "preso")) {
              c.merge("es_presi", 1, Integer::sum);
            }
            boolean enough = true;
            if (p.has("zone")) {
              for (final JsonElement z : p.getAsJsonArray("zone")) {
                enough &= flag(z.getAsJsonObject(), "scorta_basta");
              }
            }
            if (enough) {
              c.merge("es_scorta_basta", 1, Integer::sum);
            }
          }
          case "ESITO_ESPOSTO" -> c.merge("es_non_eseguiti", 1, Integer::sum);
          case "PERDITE_ESPOSTO" -> {
            c.merge("es_perdite_righe", 1, Integer::sum);
            c.merge("es_trasporti", num(p, "trasporti"), Integer::sum);
            c.merge("es_trasporti_persi", num(p, "trasporti_persi"), Integer::sum);
            c.merge("es_navi_scorta", num(p, "navi_scorta"), Integer::sum);
            c.merge("es_navi_perse", num(p, "navi_scorta_perse"), Integer::sum);
          }
          default -> {
            // other rows
          }
        }
      }
    }
    final JsonObject o = new JsonObject();
    c.forEach(o::addProperty);
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

  private static boolean flag(final JsonObject o, final String k) {
    return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
  }

  // ======================================================================= the section

  static boolean applicabile(final List<Game> games) {
    return games.stream()
        .anyMatch(
            g ->
                g.json() != null
                    && g.json().has("p6")
                    && INTERRUTTORI.stream()
                        .anyMatch(
                            s ->
                                RiepilogoGenerale.strategistLabel(g.json(), "Allies").contains(s)));
  }

  /** The Allies' switches of a game (from its label). */
  static Set<String> interruttori(final JsonObject json) {
    final Set<String> out = new TreeSet<>();
    final String label = RiepilogoGenerale.strategistLabel(json, "Allies");
    int i = label.indexOf("[con ");
    while (i >= 0) {
      final int j = label.indexOf(']', i);
      if (j < 0) {
        break;
      }
      for (final String s : label.substring(i + 5, j).split("[+,]")) {
        if (!s.isBlank()) {
          out.add(s.trim());
        }
      }
      i = label.indexOf("[con ", j);
    }
    return out;
  }

  /** The reference: the configuration with the Allies of Hard and no Allied switch. */
  @Nullable
  static String riferimento(final Map<String, List<JsonObject>> byConfig) {
    for (final String c : byConfig.keySet()) {
      if (c.endsWith("Hard") && interruttori(byConfig.get(c).get(0)).isEmpty()) {
        return c;
      }
    }
    return null;
  }

  /** The configuration with the switches of {@code config} minus {@code switchOff}, if any. */
  @Nullable
  static String senza(
      final Map<String, List<JsonObject>> byConfig, final String config, final String switchOff) {
    final Set<String> want = new TreeSet<>(interruttori(byConfig.get(config).get(0)));
    if (!want.remove(switchOff)) {
      return null;
    }
    for (final String c : byConfig.keySet()) {
      if (!c.equals(config)
          && !c.endsWith("Hard")
          && interruttori(byConfig.get(c).get(0)).equals(want)
          && !want.isEmpty()) {
        return c;
      }
    }
    return null;
  }

  static void write(final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("p6") || g.json().getAsJsonObject("p6").has("errore")) {
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
    final String ref = riferimento(byConfig);
    md.append(
"""


### P6

""");
    md.append(
        "Due leve (sbarco_concentrato, norvegia_esposta). Somme e medie del blocco `p6` dei"
            + " `riepilogo.json` (campo tra parentesi). Riferimento: "
            + (ref == null ? "-" : ref)
            + ". Mosca oltre R14 = Russia non caduta entro il round 14 (`mosca_oltre_r14`);"
            + " vittorie da `vittoria.vincitore`. Una configurazione 5 o piu' vittorie sotto il"
            + " riferimento e' FALLITA; p > 0,05 non e' una conclusione.\n\n");
    md.append(
"""
| Configurazione | Partite | Asse | Alleati | Stalli | Alleati [IC 95%] | contro riferimento (Fisher) | Mosca oltre R14 [IC 95%] | contro riferimento (Fisher) | Esito |
|---|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> list = e.getValue();
      final int n = list.size();
      final int axis = ConfrontoR4.wins(list, "Axis");
      final int allies = ConfrontoR4.wins(list, "Allies");
      final int moscow = mosca(list);
      final List<JsonObject> base =
          ref == null || ref.equals(e.getKey()) ? null : byConfig.get(ref);
      final int baseAllies = base == null ? 0 : ConfrontoR4.wins(base, "Allies");
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
          .append(base == null ? "-" : fisherText(allies, n, baseAllies, base.size()))
          .append(" | ")
          .append(moscow)
          .append("/")
          .append(n)
          .append(" ")
          .append(ConfrontoR4.wilsonText(moscow, n))
          .append(" | ")
          .append(base == null ? "-" : fisherText(moscow, n, mosca(base), base.size()))
          .append(" | ")
          .append(base != null && baseAllies - allies >= 5 ? "FALLITA" : "-")
          .append(" |\n");
    }
    // the concentrated landing against the same configuration without it
    final List<String> pairs = new ArrayList<>();
    for (final String c : byConfig.keySet()) {
      final String without = senza(byConfig, c, "sbarco_concentrato");
      if (without != null) {
        final List<JsonObject> a = byConfig.get(c);
        final List<JsonObject> b = byConfig.get(without);
        pairs.add(
            "| "
                + c
                + " | "
                + without
                + " | "
                + fisherText(
                    ConfrontoR4.wins(a, "Allies"),
                    a.size(),
                    ConfrontoR4.wins(b, "Allies"),
                    b.size())
                + " | "
                + fisherText(mosca(a), a.size(), mosca(b), b.size())
                + " |\n");
      }
    }
    if (!pairs.isEmpty()) {
      md.append(
"""

Con sbarco_concentrato contro la stessa configurazione senza (vittorie alleate, Mosca oltre R14; Fisher).

| Configurazione | contro | Alleati | Mosca oltre R14 |
|---|---|---|---|
""");
      pairs.forEach(md::append);
    }
    // A: landings
    md.append(
"""

A, sbarchi USA su territorio dell'Asse entro R8 per taglia 1-2 / 3-5 / 6-9 / 10+ unita' (`sbarchi_usa_taglie_r8`, media a partita); sbarchi di almeno 6 entro R8 (`sbarchi_usa_6_entro_r8`, media); round mediano del primo sbarco di almeno 6 (`round_primo_sbarco_usa_6`, partite senza: escluse); sbarcate cumulate R5 / R6 / R8 / R10 (`usa_sbarcate_cumulate.N.totale`, media); trasporti USA persi entro R8 / in tutta la partita (`usa_trasporti_persi_r8`, `usa_trasporti_persi_totale`, media); massimo di unita' USA a bordo da 3+ fine round (`usa_a_bordo_3_round_max`, media).

| Configurazione | Taglie entro R8 | >= 6 entro R8 | Primo >= 6: mediana (partite) | Sbarcate R5 / R6 / R8 / R10 | Trasporti persi R8 / tutta | A bordo 3+ round |
|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      final List<String> sizes = new ArrayList<>();
      for (final String t : TAGLIE) {
        sizes.add(
            f(
                ConfrontoP4.mean(
                    b, x -> x.getAsJsonObject("sbarchi_usa_taglie_r8").get(t).getAsDouble())));
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
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(String.join(" / ", sizes))
          .append(" | ")
          .append(f(ConfrontoP4.mean(b, x -> x.get("sbarchi_usa_6_entro_r8").getAsDouble())))
          .append(" | ")
          .append(firsts.isEmpty() ? "-" : f(ConfrontoP4.median(firsts)))
          .append(" (")
          .append(firsts.size())
          .append(") | ")
          .append(String.join(" / ", landed))
          .append(" | ")
          .append(f(ConfrontoP4.mean(b, x -> x.get("usa_trasporti_persi_r8").getAsDouble())))
          .append(" / ")
          .append(f(ConfrontoP4.mean(b, x -> x.get("usa_trasporti_persi_totale").getAsDouble())))
          .append(" | ")
          .append(f(ConfrontoP4.mean(b, x -> x.get("usa_a_bordo_3_round_max").getAsDouble())))
          .append(" |\n");
    }
    // A: register
    md.append(
"""

A, registro (`registro`, somme): sbarchi in forza (unita') / dopo l'attesa (unita') / righe di attesa / avvicinamento / raduni; turni con truppe a bordo oltre un round (trasporti); esiti presi su esiti; un round dopo: ancora alleati su seguiti, trasporti persi.

| Configurazione | In forza | Dopo attesa | Attese / avvicinamenti / raduni | Oltre un round | Presi | Un round dopo |
|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(sum(b, x -> reg(x, "sc_in_forza")))
          .append(" (")
          .append(sum(b, x -> reg(x, "sc_in_forza_unita")))
          .append(") | ")
          .append(sum(b, x -> reg(x, "sc_dopo_attesa")))
          .append(" (")
          .append(sum(b, x -> reg(x, "sc_dopo_attesa_unita")))
          .append(") | ")
          .append(sum(b, x -> reg(x, "sc_attese")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "sc_avvicinamenti")))
          .append(" / ")
          .append(sum(b, x -> reg(x, "sc_raduni")))
          .append(" | ")
          .append(sum(b, x -> reg(x, "sc_turni_oltre_un_round")))
          .append(" (")
          .append(sum(b, x -> reg(x, "sc_trasporti_oltre_un_round")))
          .append(") | ")
          .append(sum(b, x -> reg(x, "sc_presi")))
          .append(" su ")
          .append(sum(b, x -> reg(x, "sc_esiti")))
          .append(" | ")
          .append(sum(b, x -> reg(x, "sc_ancora_alleati")))
          .append(" su ")
          .append(sum(b, x -> reg(x, "sc_seguiti")))
          .append(", trasporti persi ")
          .append(sum(b, x -> reg(x, "sc_trasporti_persi_dopo")))
          .append(" |\n");
    }
    // A: where by R10
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
    // the effect on the Axis
    md.append(
"""

Effetto sull'Asse: unita' di terra tedesche in tutto / in Europa occidentale a R6, R8, R10 (`tedeschi.N`, media); esercito di terra russo e tedesco in TUV a R6 / R8 / R10 (`esercito_tuv`, media); reddito tedesco R4 / R6 / R8 (`reddito_tedesco`, media); cadute (`cadute`): Mosca, Roma (Southern Italy), Berlino (Germany) in partite e round mediano.

| Configurazione | Tedeschi R6 | R8 | R10 | Russo R6 / R8 / R10 | Tedesco R6 / R8 / R10 | Reddito tedesco | Mosca | Roma | Berlino |
|---|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      md.append("| ").append(e.getKey());
      for (final int r : ROUND_TEDESCHI) {
        md.append(" | ")
            .append(f(ConfrontoP4.mean(b, x -> path(x, "tedeschi", r, "totale"))))
            .append(" / ")
            .append(f(ConfrontoP4.mean(b, x -> path(x, "tedeschi", r, "ovest"))));
      }
      final List<String> inc = new ArrayList<>();
      for (final int r : ROUND_REDDITO) {
        inc.add(f(ConfrontoP4.mean(b, x -> single(x, "reddito_tedesco", r))));
      }
      md.append(" | ")
          .append(eserciti(b, "Russians"))
          .append(" | ")
          .append(eserciti(b, "Germans"))
          .append(" | ")
          .append(String.join(" / ", inc));
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
    // B
    md.append(
"""

B, Norway alleata a fine R2 / R3 / R4 / R6 / R8 in partite (`norway_alleata`); sbarchi britannici su Norway (`norway_sbarchi_uk`, somma: sbarchi / unita'); trasporti / navi da guerra britannici persi entro R4 (`uk_perse_r4`, media); United Kingdom perso (`regno_unito_perso`); registro (`registro`, somme): sbarchi esposti (presi, unita'), con scorta che basta, non eseguiti da Hard; nel turno nemico dopo: trasporti persi su trasporti, navi di scorta perse su navi.

| Configurazione | Norway R2 / R3 / R4 / R6 / R8 | Sbarchi UK su Norway | Perse UK entro R4 | UK perso | Registro B |
|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = blocchi(e.getValue());
      final List<String> nor = new ArrayList<>();
      for (final int r : ROUND_NORVEGIA) {
        nor.add(
            String.valueOf(
                count(b, x -> flag(x.getAsJsonObject("norway_alleata"), String.valueOf(r)))));
      }
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(String.join(" / ", nor))
          .append(" su ")
          .append(b.size())
          .append(" | ")
          .append(sum(b, x -> x.getAsJsonObject("norway_sbarchi_uk").get("sbarchi").getAsDouble()))
          .append(" / ")
          .append(sum(b, x -> x.getAsJsonObject("norway_sbarchi_uk").get("unita").getAsDouble()))
          .append(" | ")
          .append(
              f(
                  ConfrontoP4.mean(
                      b, x -> x.getAsJsonObject("uk_perse_r4").get("trasporti").getAsDouble())))
          .append(" / ")
          .append(
              f(
                  ConfrontoP4.mean(
                      b,
                      x -> x.getAsJsonObject("uk_perse_r4").get("navi_da_guerra").getAsDouble())))
          .append(" | ")
          .append(count(b, x -> x.get("regno_unito_perso").getAsBoolean()))
          .append(" | ")
          .append(sum(b, x -> reg(x, "es_sbarchi")))
          .append(" (presi ")
          .append(sum(b, x -> reg(x, "es_presi")))
          .append(", unita' ")
          .append(sum(b, x -> reg(x, "es_unita")))
          .append("), scorta basta ")
          .append(sum(b, x -> reg(x, "es_scorta_basta")))
          .append(", non eseguiti ")
          .append(sum(b, x -> reg(x, "es_non_eseguiti")))
          .append("; dopo: trasporti ")
          .append(sum(b, x -> reg(x, "es_trasporti_persi")))
          .append(" su ")
          .append(sum(b, x -> reg(x, "es_trasporti")))
          .append(", navi ")
          .append(sum(b, x -> reg(x, "es_navi_perse")))
          .append(" su ")
          .append(sum(b, x -> reg(x, "es_navi_scorta")))
          .append(" |\n");
    }
  }

  static int mosca(final List<JsonObject> list) {
    int n = 0;
    for (final JsonObject j : list) {
      final JsonObject p = j.getAsJsonObject("p6");
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
    return games.stream().map(j -> j.getAsJsonObject("p6")).toList();
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

  private static double reg(final JsonObject x, final String k) {
    final JsonObject o = x.getAsJsonObject("registro");
    return o != null && o.has(k) ? o.get(k).getAsDouble() : 0;
  }

  private static String eserciti(final List<JsonObject> b, final String nation) {
    final List<String> out = new ArrayList<>();
    for (final int r : ROUND_TEDESCHI) {
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
