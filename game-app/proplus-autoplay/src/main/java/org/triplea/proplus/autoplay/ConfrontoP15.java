package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P15: the block {@code p15} of one game and the section "P15" of the general summary. Every
 * nation "Pro+ (Europe 1940)" with the two boxes (Allies with 8 switches); the configuration code
 * is the 2x2 design of the Allies' switches: first digit ucraina_contrattacco (A), second
 * ucraina_presidio (B). Also the point 1.3 (read only): the US turns with many empty transports at
 * the port and no land unit in Eastern/Central US, from the games of 00.
 */
final class ConfrontoP15 {
  private ConfrontoP15() {}

  static final String A = "ucraina_contrattacco";
  static final String B = "ucraina_presidio";
  static final List<String> CONFIG = List.of("00", "10", "01", "11");
  static final String RUSSIA = "Russians";
  static final String GERMANIA = "Germans";

  /** The code of the games of the step, else null. */
  @Nullable
  static String codice(final JsonObject json) {
    if (!json.has("parametri")
        || !json.getAsJsonObject("parametri").has("fino_a_vittoria")
        || !json.getAsJsonObject("parametri").get("fino_a_vittoria").getAsBoolean()) {
      return null;
    }
    final JsonObject p = json.getAsJsonObject("parametri");
    final JsonObject tipi = p.has("tipi") ? p.getAsJsonObject("tipi") : new JsonObject();
    for (final String alliance : List.of("Axis", "Allies")) {
      if (!tipi.has(alliance)
          || !AutoplayOptions.EUROPA1940.equals(tipi.get(alliance).getAsString())) {
        return null;
      }
    }
    final Set<String> on = ConfrontoP12.accesi(json, "Allies");
    if (!ConfrontoP12.accesi(json, "Axis").isEmpty()) {
      return null;
    }
    final Set<String> other = new java.util.TreeSet<>(on);
    other.remove(A);
    other.remove(B);
    if (!other.isEmpty()) {
      return null;
    }
    return (on.contains(A) ? "1" : "0") + (on.contains(B) ? "1" : "0");
  }

  static boolean applicabile(final List<Game> games) {
    return games.stream()
        .anyMatch(
            g -> g.json() != null && codice(g.json()) != null && !"00".equals(codice(g.json())));
  }

  // ============================================================================== block

  /** The block {@code p15} of one game (after the block p13 of the same game). */
  static JsonObject blocco(final JsonObject json) {
    final JsonObject o = new JsonObject();
    final String code = codice(json);
    o.addProperty("configurazione", code == null ? "" : code);
    final JsonObject p13 = sub(json, "p13");
    o.add(
        "cadute",
        p13 != null && p13.has("cadute")
            ? p13.getAsJsonObject("cadute").deepCopy()
            : new JsonObject());
    final JsonArray rounds = json.has("round") ? json.getAsJsonArray("round") : new JsonArray();
    final Map<Integer, JsonObject> perRound = new TreeMap<>();
    for (final JsonElement e : rounds) {
      final JsonObject r = e.getAsJsonObject();
      if (r.has("round")) {
        perRound.put(r.get("round").getAsInt(), r);
      }
    }
    int killedR4R6 = 0;
    for (final var e : perRound.entrySet()) {
      final JsonObject r = e.getValue();
      final JsonObject p4 = sub(r, "p4");
      final int round = e.getKey();
      if (p4 != null) {
        final JsonObject owners = sub(p4, "proprietari");
        if (owners != null) {
          if (round == 4 || round == 5 || round == 6) {
            o.addProperty("ucraina_russa_r" + round, RUSSIA.equals(s(owners, "Ukraine")));
            o.addProperty("novgorod_russa_r" + round, RUSSIA.equals(s(owners, "Novgorod")));
          }
        }
        final JsonObject army = sub(p4, "esercito_tuv");
        if (army != null && (round == 6 || round == 8)) {
          o.addProperty("tuv_tedesco_r" + round, num(army, GERMANIA));
          o.addProperty("tuv_russo_r" + round, num(army, RUSSIA));
        }
        if (round >= 4 && round <= 6) {
          killedR4R6 += (int) num(sub(p4, "attacchi_russi"), "nemici_uccisi");
        }
      }
      if (round == 6) {
        final JsonObject nations = sub(r, "nazioni");
        final JsonObject ru = nations == null ? null : sub(nations, RUSSIA);
        if (ru != null) {
          o.addProperty("reddito_russo_r6", num(ru, "reddito"));
        }
      }
    }
    o.addProperty("asse_uccise_da_russi_r4_r6", killedR4R6);
    // point 1.3: the US turns with >= 10 empty transports at the port and no land unit east
    final JsonArray turns = new JsonArray();
    for (final var e : perRound.entrySet()) {
      final JsonObject u = sub(e.getValue(), "p15usa");
      if (u == null || !u.has("vuoti_porto")) {
        continue;
      }
      if (num(u, "vuoti_porto") >= 10 && num(u, "terra_est") == 0) {
        final JsonObject t = u.deepCopy();
        t.addProperty("round", e.getKey());
        final JsonObject p5 = sub(e.getValue(), "p5");
        final JsonObject buy = p5 == null ? null : sub(p5, "usa_acquisti_pu");
        t.add("acquisti_pu", buy == null ? new JsonObject() : buy.deepCopy());
        turns.add(t);
      }
    }
    o.add("usa_turni_vuoti", turns);
    // the levers' register
    final Map<String, Double> reg = new LinkedHashMap<>();
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if (!"decisione".equals(s(d, "riga")) || !s(d, "tipo").startsWith("p15_")) {
        continue;
      }
      final String c = s(d, "scelta");
      final JsonObject par = d.has("parametri") ? d.getAsJsonObject("parametri") : new JsonObject();
      reg.merge("righe_" + c, 1.0, Double::sum);
      final int round = d.has("round") ? d.get("round").getAsInt() : 0;
      switch (c) {
        case "P15_A_ESEGUITO" -> {
          reg.merge("a_vittoria_stimata", num(par, "vittoria_stimata"), Double::sum);
          reg.merge("a_ucciso_stimato", num(par, "tuv_asse_ucciso_stimato"), Double::sum);
          reg.merge("a_perso_stimato", num(par, "tuv_russo_perso_stimato"), Double::sum);
          reg.merge("a_unita", num(par, "unita"), Double::sum);
          if (round >= 4 && round <= 6) {
            reg.merge("a_eseguiti_r4_r6", 1.0, Double::sum);
          }
        }
        case "P15_A_ESITO" -> {
          reg.merge("a_ucciso_reale", num(par, "tuv_asse_ucciso"), Double::sum);
          reg.merge("a_perso_reale", num(par, "tuv_russo_perso"), Double::sum);
          reg.merge(
              "a_presi",
              par.has("preso") && par.get("preso").getAsBoolean() ? 1.0 : 0.0,
              Double::sum);
        }
        case "P15_B_SPOSTATE" -> {
          reg.merge("b_spostate", num(par, "spostate"), Double::sum);
          reg.merge("b_minaccia_prima", num(par, "minaccia_prima_pct"), Double::sum);
          reg.merge("b_minaccia_dopo", num(par, "minaccia_dopo_pct"), Double::sum);
        }
        case "P15_B_RINUNCIA" ->
            reg.merge("b_minaccia_rinuncia", num(par, "minaccia_prima_pct"), Double::sum);
        case "P15_B_PIAZZATE" -> reg.merge("b_piazzate", num(par, "piazzate"), Double::sum);
        default -> {}
      }
    }
    final JsonObject r = new JsonObject();
    reg.forEach(r::addProperty);
    o.add("registro", r);
    return o;
  }

  // ============================================================================== section

  static void write(final StringBuilder md, final List<Game> games) {
    final Map<String, List<JsonObject>> by = new TreeMap<>();
    final Map<String, int[]> invalid = new TreeMap<>();
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      final String code = codice(g.json());
      if (code == null) {
        continue;
      }
      final JsonObject b = sub(g.json(), "p15");
      if (b == null || !ConfrontoP12.valida(g.json())) {
        invalid.computeIfAbsent(code, k -> new int[1])[0]++;
        continue;
      }
      b.addProperty("vincitore", ConfrontoP12.vincitore(g.json()));
      b.addProperty("stallo", s(g.json(), "stato").startsWith("stallo"));
      by.computeIfAbsent(code, k -> new ArrayList<>()).add(b);
    }
    if (by.isEmpty() && invalid.isEmpty()) {
      return;
    }
    md.append(
"""


### P15

""");
    md.append(
        "Step P15: tutte le nazioni \"Pro+ (Europe 1940)\" (Asse consigliato contro Alleati"
            + " consigliati, 8 interruttori con ponte_alleato); disegno 2x2 sugli interruttori"
            + " russi: prima cifra ucraina_contrattacco (A), seconda ucraina_presidio (B). Valide ="
            + " completate o in stallo. Confronti dichiarati: Fisher esatto, soglia 0,025.\n\n");
    md.append(
        "| Config | Valide | Stalli | Errori | Vittorie Asse | Vittorie Alleati | Ucraina russa R5"
            + " / R6 | Novgorod russa R4 / R6 | Mosca <= R12 | Round medio caduta Mosca | TUV"
            + " tedesco R6 / R8 | TUV russo R6 / R8 | Asse uccise da attacchi russi R4-R6 |"
            + " Reddito russo R6 |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (final String c : CONFIG) {
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      md.append("| ")
          .append(c)
          .append(" | ")
          .append(l.size())
          .append(" | ")
          .append(count(l, b -> b.get("stallo").getAsBoolean()))
          .append(" | ")
          .append(invalid.getOrDefault(c, new int[1])[0])
          .append(" | ")
          .append(frac(l, ConfrontoP14::axisWin))
          .append(" | ")
          .append(frac(l, ConfrontoP14::alliesWin))
          .append(" | ")
          .append(frac(l, b -> flag(b, "ucraina_russa_r5")))
          .append(" / ")
          .append(frac(l, b -> flag(b, "ucraina_russa_r6")))
          .append(" | ")
          .append(frac(l, b -> flag(b, "novgorod_russa_r4")))
          .append(" / ")
          .append(frac(l, b -> flag(b, "novgorod_russa_r6")))
          .append(" | ")
          .append(frac(l, b -> ConfrontoP14.entro(b, "Mosca", 12)))
          .append(" | ")
          .append(f(meanIf(l, b -> caduta(b) > 0, b -> caduta(b))))
          .append(" | ")
          .append(f(mean(l, "tuv_tedesco_r6")))
          .append(" / ")
          .append(f(mean(l, "tuv_tedesco_r8")))
          .append(" | ")
          .append(f(mean(l, "tuv_russo_r6")))
          .append(" / ")
          .append(f(mean(l, "tuv_russo_r8")))
          .append(" | ")
          .append(f(mean(l, "asse_uccise_da_russi_r4_r6")))
          .append(" | ")
          .append(f(mean(l, "reddito_russo_r6")))
          .append(" |\n");
    }
    md.append(
"""

#### Confronti dichiarati (Fisher esatto, soglia 0,05/2 = 0,025)

| Confronto | Acceso | Spento | p |
|---|---|---|---|
""");
    confronto(
        md, "A: contrattacco (10+11 contro 00+01), vittorie Alleati", by, "10", "11", "00", "01");
    confronto(md, "B: presidio (01+11 contro 00+10), vittorie Alleati", by, "01", "11", "00", "10");
    md.append(
"""

#### Leve P15 (registro, somme per configurazione)

| Config | A eseguiti | di cui R4-R6 | A rinunciati | A gia' di Hard | Vittoria stimata media | TUV Asse ucciso stimato / reale | TUV russo perso stimato / reale | Bersagli presi | B turni con spostamenti | B unita' spostate | B rinunce (B2) | B piazzate |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final String c : CONFIG) {
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      final double exe = sum(l, "righe_P15_A_ESEGUITO");
      md.append("| ")
          .append(c)
          .append(" | ")
          .append((long) exe)
          .append(" | ")
          .append((long) sum(l, "a_eseguiti_r4_r6"))
          .append(" | ")
          .append((long) sum(l, "righe_P15_A_RINUNCIA"))
          .append(" | ")
          .append((long) sum(l, "righe_P15_A_HARD"))
          .append(" | ")
          .append(exe == 0 ? "-" : f(sum(l, "a_vittoria_stimata") / exe))
          .append(" | ")
          .append((long) sum(l, "a_ucciso_stimato"))
          .append(" / ")
          .append((long) sum(l, "a_ucciso_reale"))
          .append(" | ")
          .append((long) sum(l, "a_perso_stimato"))
          .append(" / ")
          .append((long) sum(l, "a_perso_reale"))
          .append(" | ")
          .append((long) sum(l, "a_presi"))
          .append(" | ")
          .append((long) sum(l, "righe_P15_B_SPOSTATE"))
          .append(" | ")
          .append((long) sum(l, "b_spostate"))
          .append(" | ")
          .append((long) sum(l, "righe_P15_B_RINUNCIA"))
          .append(" | ")
          .append((long) sum(l, "b_piazzate"))
          .append(" |\n");
    }
    usa(md, by.getOrDefault("00", List.of()));
  }

  /** Point 1.3: the US turns with >= 10 empty transports at the port and no land unit east. */
  private static void usa(final StringBuilder md, final List<JsonObject> l) {
    int turns = 0;
    int games = 0;
    final Map<String, Double> pu = new TreeMap<>();
    final Map<String, double[]> zones = new TreeMap<>();
    for (final JsonObject b : l) {
      final JsonArray t = b.has("usa_turni_vuoti") ? b.getAsJsonArray("usa_turni_vuoti") : null;
      if (t == null || t.isEmpty()) {
        continue;
      }
      games++;
      for (final JsonElement e : t) {
        turns++;
        final JsonObject x = e.getAsJsonObject();
        final JsonObject buy = sub(x, "acquisti_pu");
        if (buy != null) {
          buy.entrySet()
              .forEach(k -> pu.merge(k.getKey(), k.getValue().getAsDouble(), Double::sum));
        }
        final JsonArray z =
            x.has("zone_flotta") ? x.getAsJsonArray("zone_flotta") : new JsonArray();
        for (final JsonElement ze : z) {
          final JsonObject zz = ze.getAsJsonObject();
          final double[] a = zones.computeIfAbsent(s(zz, "zona"), k -> new double[6]);
          a[0]++;
          a[1] += num(zz, "pu_nuove");
          a[2] += num(zz, "navi_nemiche");
          a[3] += num(zz, "tuv_navi_nemiche");
          a[4] += num(zz, "aerei_nemici");
          a[5] += num(zz, "tuv_aerei_nemici");
        }
      }
    }
    md.append(
"""

#### Punto 1.3 (sola lettura, configurazione 00): USA a fine piazzamento con >= 10 trasporti vuoti al porto e 0 unita' di terra in Eastern/Central US

""");
    md.append("Turni: ")
        .append(turns)
        .append(" in ")
        .append(games)
        .append(" partite su ")
        .append(l.size())
        .append(". PU spesi in quei turni per tipo: ")
        .append(pu.toString())
        .append(".\n\n");
    md.append(
"""
| Zona delle navi nuove | Turni | PU medi | Navi nemiche vicine (media) | TUV navi nemiche (media) | Aerei nemici vicini (media) | TUV aerei nemici (media) |
|---|---|---|---|---|---|---|
""");
    zones.entrySet().stream()
        .sorted((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0]))
        .forEach(
            e -> {
              final double[] a = e.getValue();
              md.append("| ")
                  .append(e.getKey())
                  .append(" | ")
                  .append((long) a[0])
                  .append(" | ")
                  .append(f(a[1] / a[0]))
                  .append(" | ")
                  .append(f(a[2] / a[0]))
                  .append(" | ")
                  .append(f(a[3] / a[0]))
                  .append(" | ")
                  .append(f(a[4] / a[0]))
                  .append(" | ")
                  .append(f(a[5] / a[0]))
                  .append(" |\n");
            });
  }

  private static void confronto(
      final StringBuilder md,
      final String name,
      final Map<String, List<JsonObject>> by,
      final String on1,
      final String on2,
      final String off1,
      final String off2) {
    final List<JsonObject> on = new ArrayList<>(by.getOrDefault(on1, List.of()));
    on.addAll(by.getOrDefault(on2, List.of()));
    final List<JsonObject> off = new ArrayList<>(by.getOrDefault(off1, List.of()));
    off.addAll(by.getOrDefault(off2, List.of()));
    md.append("| ")
        .append(name)
        .append(" | ")
        .append(frac(on, ConfrontoP14::alliesWin))
        .append(" | ")
        .append(frac(off, ConfrontoP14::alliesWin))
        .append(" | ")
        .append(
            on.isEmpty() || off.isEmpty()
                ? "-"
                : ConfrontoR5.pText(
                    ConfrontoR4.fisher(
                        count(on, ConfrontoP14::alliesWin),
                        on.size(),
                        count(off, ConfrontoP14::alliesWin),
                        off.size())))
        .append(" |\n");
  }

  // ================================================================================ helpers

  private static boolean flag(final JsonObject b, final String k) {
    return b.has(k) && b.get(k).getAsBoolean();
  }

  private static int caduta(final JsonObject b) {
    final JsonObject k = b.has("cadute") ? b.getAsJsonObject("cadute") : new JsonObject();
    return k.has("Mosca") ? k.get("Mosca").getAsInt() : 0;
  }

  private static double sum(final List<JsonObject> l, final String k) {
    double s = 0;
    for (final JsonObject b : l) {
      s += num(sub(b, "registro"), k);
    }
    return s;
  }

  private static double mean(final List<JsonObject> l, final String k) {
    return meanIf(l, b -> b.has(k), b -> num(b, k));
  }

  private static double meanIf(
      final List<JsonObject> l,
      final Predicate<JsonObject> p,
      final ToDoubleFunction<JsonObject> v) {
    double s = 0;
    int n = 0;
    for (final JsonObject b : l) {
      if (p.test(b)) {
        s += v.applyAsDouble(b);
        n++;
      }
    }
    return n == 0 ? Double.NaN : s / n;
  }

  @Nullable
  private static JsonObject sub(@Nullable final JsonObject j, final String key) {
    return j != null
            && j.has(key)
            && j.get(key).isJsonObject()
            && !j.getAsJsonObject(key).has("errore")
        ? j.getAsJsonObject(key)
        : null;
  }

  private static double num(@Nullable final JsonObject o, final String k) {
    return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
  }

  private static String s(@Nullable final JsonObject o, final String k) {
    return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
  }

  private static int count(final List<JsonObject> l, final Predicate<JsonObject> p) {
    return (int) l.stream().filter(p).count();
  }

  private static String frac(final List<JsonObject> l, final Predicate<JsonObject> p) {
    if (l.isEmpty()) {
      return "-";
    }
    final int n = count(l, p);
    return n + "/" + l.size() + " " + Math.round(100.0 * n / l.size()) + "%";
  }

  private static String f(final double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
  }
}
