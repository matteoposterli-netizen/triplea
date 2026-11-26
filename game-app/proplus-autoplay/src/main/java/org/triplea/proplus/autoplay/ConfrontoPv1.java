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
 * Step PV1: the block {@code pv1} of one game and the section "PV1" of the general summary. Every
 * nation "Pro+ (Europe 1940)" with the two boxes (Axis and Allies recommended); the configuration
 * code is the set of PV1 switches on for the Allies: "base" (none), "pv1" (all three), else the
 * letters of the switches on (A usa_carico, B capitale_oltranza, C ponte_alleato).
 */
final class ConfrontoPv1 {
  private ConfrontoPv1() {}

  static final List<String> SWITCHES = List.of("usa_carico", "capitale_oltranza", "ponte_alleato");
  static final List<String> CONFIG = List.of("base", "pv1", "A", "B", "C");
  static final String USA = "Americans";
  static final String UK = "British";

  // ====================================================================== configuration

  private static String tipo(final JsonObject json, final String alliance) {
    if (!json.has("parametri")) {
      return "";
    }
    final JsonObject p = json.getAsJsonObject("parametri");
    return p.has("tipi") && p.getAsJsonObject("tipi").has(alliance)
        ? p.getAsJsonObject("tipi").get(alliance).getAsString()
        : "";
  }

  /** The code of the games of the step, else null (other steps, Axis switches of P14). */
  @Nullable
  static String codice(final JsonObject json) {
    if (!json.has("parametri")
        || !json.getAsJsonObject("parametri").has("fino_a_vittoria")
        || !json.getAsJsonObject("parametri").get("fino_a_vittoria").getAsBoolean()) {
      return null;
    }
    if (!AutoplayOptions.EUROPA1940.equals(tipo(json, "Axis"))
        || !AutoplayOptions.EUROPA1940.equals(tipo(json, "Allies"))) {
      return null;
    }
    final Set<String> on = ConfrontoP12.accesi(json, "Allies");
    if (!ConfrontoP12.accesi(json, "Axis").isEmpty() || on.contains("aerei_corretti")) {
      return null;
    }
    return codice(
        on.contains(SWITCHES.get(0)), on.contains(SWITCHES.get(1)), on.contains(SWITCHES.get(2)));
  }

  static String codice(final boolean a, final boolean b, final boolean c) {
    if (a && b && c) {
      return "pv1";
    }
    final String s = (a ? "A" : "") + (b ? "B" : "") + (c ? "C" : "");
    return s.isEmpty() ? "base" : s;
  }

  static boolean applicabile(final List<Game> games) {
    return games.stream()
        .anyMatch(
            g -> g.json() != null && codice(g.json()) != null && !"base".equals(codice(g.json())));
  }

  // ============================================================================== block

  /** The block {@code pv1} of one game (after the block p13 of the same game). */
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
    // per round: the board read by MonitorPv1
    final Map<Integer, JsonObject> perRound = new TreeMap<>();
    final JsonArray rounds = json.has("round") ? json.getAsJsonArray("round") : new JsonArray();
    for (final JsonElement e : rounds) {
      final JsonObject r = e.getAsJsonObject();
      final JsonObject pv = sub(r, "pv1");
      if (pv != null && r.has("round")) {
        perRound.put(r.get("round").getAsInt(), pv);
      }
    }
    for (final int r : List.of(8, 12)) {
      final JsonObject pv = perRound.get(r);
      if (pv != null) {
        o.addProperty("tuv_tedesco_r" + r, num(pv, "tuv_terra_tedesco"));
        o.addProperty("usa_vuoti_r" + r, num(pv, "usa_vuoti_porto"));
        o.addProperty("uk_america_r" + r, num(pv, "uk_in_america"));
      }
    }
    // the true landings of the USA and the UK, Normandy and Norway held the round after
    int usLanded = 0;
    int ukLanded = 0;
    final Map<String, Boolean> held = new TreeMap<>();
    final JsonArray landings =
        json.has("sbarchi") ? json.getAsJsonArray("sbarchi") : new JsonArray();
    for (final JsonElement e : landings) {
      final JsonObject l = e.getAsJsonObject();
      final String nation = s(l, "nazione");
      final int units = (int) num(l, "unita");
      if (USA.equals(nation)) {
        usLanded += units;
      } else if (UK.equals(nation)) {
        ukLanded += units;
      } else {
        continue;
      }
      final String t = s(l, "territorio");
      if (!MonitorPv1.SBARCHI.contains(t)) {
        continue;
      }
      final int round = (int) num(l, "round");
      final JsonObject next = perRound.get(round + 1);
      if (next == null || !next.has("proprietari")) {
        continue;
      }
      final String owner = s(next.getAsJsonObject("proprietari"), t);
      held.putIfAbsent(round + "|" + t, !asse(owner));
    }
    o.addProperty("usa_sbarcate", usLanded);
    o.addProperty("uk_sbarcate", ukLanded);
    for (final String t : MonitorPv1.SBARCHI) {
      int n = 0;
      int k = 0;
      for (final var e : held.entrySet()) {
        if (e.getKey().endsWith("|" + t)) {
          n++;
          k += e.getValue() ? 1 : 0;
        }
      }
      final JsonObject x = new JsonObject();
      x.addProperty("sbarchi", n);
      x.addProperty("tenuti", k);
      o.add(t, x);
    }
    // the levers' register
    final Map<String, Double> reg = new LinkedHashMap<>();
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if (!"decisione".equals(s(d, "riga")) || !s(d, "tipo").startsWith("pv1_")) {
        continue;
      }
      final String code0 = s(d, "scelta");
      final JsonObject par = d.has("parametri") ? d.getAsJsonObject("parametri") : new JsonObject();
      reg.merge("righe_" + code0, 1.0, Double::sum);
      switch (code0) {
        case "PV1_CARICO_COMPRATO" ->
            reg.merge("carico_comprate", num(par, "unita_comprate"), Double::sum);
        case "PV1_CARICO_FINE_TURNO" ->
            reg.merge("carico_vuoti_fine_turno", num(par, "trasporti_vuoti_al_porto"), Double::sum);
        case "PV1_CAPITALE_MINACCIATA" ->
            reg.merge("capitale_comprate", num(par, "unita_comprate"), Double::sum);
        case "PV1_CAPITALE_TENUTA" -> {
          reg.merge("capitale_rientrate", num(par, "rientrate"), Double::sum);
          reg.merge("capitale_attacchi_tolti", num(par, "attacchi_tolti"), Double::sum);
        }
        case "PV1_PONTE_REGNO_UNITO" -> {
          reg.merge("ponte_caricate", num(par, "caricate_su_trasporti_usa"), Double::sum);
          reg.merge("ponte_scaricate", num(par, "scaricate"), Double::sum);
        }
        case "PV1_PONTE_NAVIGA" ->
            reg.merge("ponte_trasporti_mossi", num(par, "trasporti_mossi"), Double::sum);
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
      final JsonObject b = sub(g.json(), "pv1");
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
    final List<String> configs = new ArrayList<>(CONFIG);
    by.keySet().stream().filter(k -> !configs.contains(k)).forEach(configs::add);
    configs.removeIf(c -> !by.containsKey(c) && !invalid.containsKey(c));
    md.append(
"""


### PV1

""");
    md.append(
        "Step PV1: tutte le nazioni \"Pro+ (Europe 1940)\" (Asse consigliato contro Alleati"
            + " consigliati, 7 interruttori) + interruttori alleati: base = nessuno, pv1 = usa_carico +"
            + " capitale_oltranza + ponte_alleato, A/B/C = uno solo. Valide = completate o in"
            + " stallo. Confronto dichiarato: vittorie Alleati pv1 contro base, Fisher esatto, soglia"
            + " 0,05.\n\n");
    md.append(
"""
| Config | Valide | Stalli | Errori | Vittorie Asse | Vittorie Alleati | Mosca <= R12 | Round medio caduta Mosca | TUV terra tedesco R8 / R12 | Trasporti USA vuoti al porto R8 / R12 | USA sbarcate / partita | UK sbarcate / partita | Normandy tenuta al round dopo | Norway tenuta al round dopo | UK in America R8 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final String c : configs) {
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
          .append(frac(l, b -> ConfrontoP14.entro(b, "Mosca", 12)))
          .append(" | ")
          .append(f(meanIf(l, b -> caduta(b) > 0, b -> caduta(b))))
          .append(" | ")
          .append(f(mean(l, "tuv_tedesco_r8")))
          .append(" / ")
          .append(f(mean(l, "tuv_tedesco_r12")))
          .append(" | ")
          .append(f(mean(l, "usa_vuoti_r8")))
          .append(" / ")
          .append(f(mean(l, "usa_vuoti_r12")))
          .append(" | ")
          .append(f(mean(l, "usa_sbarcate")))
          .append(" | ")
          .append(f(mean(l, "uk_sbarcate")))
          .append(" | ")
          .append(tenuta(l, "Normandy Bordeaux"))
          .append(" | ")
          .append(tenuta(l, "Norway"))
          .append(" | ")
          .append(f(mean(l, "uk_america_r8")))
          .append(" |\n");
    }
    md.append(
"""

#### Confronto dichiarato (Fisher esatto, soglia 0,05) e ablazioni (solo descrittive)

| Confronto | Configurazione | base | p |
|---|---|---|---|
""");
    final List<JsonObject> base = by.getOrDefault("base", List.of());
    for (final String c : configs) {
      if ("base".equals(c)) {
        continue;
      }
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      final int a = count(l, ConfrontoP14::alliesWin);
      final int b = count(base, ConfrontoP14::alliesWin);
      md.append("| vittorie Alleati, ")
          .append(c)
          .append(" contro base")
          .append("pv1".equals(c) ? " (dichiarato)" : "")
          .append(" | ")
          .append(frac(l, ConfrontoP14::alliesWin))
          .append(" | ")
          .append(frac(base, ConfrontoP14::alliesWin))
          .append(" | ")
          .append(
              l.isEmpty() || base.isEmpty()
                  ? "-"
                  : ConfrontoR5.pText(ConfrontoR4.fisher(a, l.size(), b, base.size())))
          .append(" |\n");
    }
    md.append(
"""

#### Leve PV1 (registro, somme per configurazione)

| Config | Carico comprato (unita') | Trasporti vuoti a fine turno USA (somma) | Turni Russia minacciata | Rientrate in capitale | Attacchi tolti alla capitale | Difese comprate in capitale | UK caricate su trasporti USA | UK scaricate | Trasporti USA mossi con truppe UK |
|---|---|---|---|---|---|---|---|---|---|
""");
    for (final String c : configs) {
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      md.append("| ").append(c);
      for (final String k :
          List.of(
              "carico_comprate",
              "carico_vuoti_fine_turno",
              "righe_PV1_CAPITALE_TENUTA",
              "capitale_rientrate",
              "capitale_attacchi_tolti",
              "capitale_comprate",
              "ponte_caricate",
              "ponte_scaricate",
              "ponte_trasporti_mossi")) {
        double sum = 0;
        for (final JsonObject b : l) {
          sum += num(sub(b, "registro"), k);
        }
        md.append(" | ").append((long) sum);
      }
      md.append(" |\n");
    }
  }

  // ================================================================================ helpers

  private static boolean asse(final String owner) {
    return "Germans".equals(owner) || "Italians".equals(owner) || "Neutral_Axis".equals(owner);
  }

  private static int caduta(final JsonObject b) {
    final JsonObject k = b.has("cadute") ? b.getAsJsonObject("cadute") : new JsonObject();
    return k.has("Mosca") ? k.get("Mosca").getAsInt() : 0;
  }

  private static String tenuta(final List<JsonObject> l, final String t) {
    int n = 0;
    int k = 0;
    for (final JsonObject b : l) {
      final JsonObject x = sub(b, t);
      n += (int) num(x, "sbarchi");
      k += (int) num(x, "tenuti");
    }
    return n == 0 ? "-" : k + "/" + n;
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
  private static JsonObject sub(final JsonObject j, final String key) {
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

  private static String s(final JsonObject o, final String k) {
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
