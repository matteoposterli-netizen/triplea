package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P14: the block {@code p14} of one game and the section "P14" of the general summary. Every
 * nation "Pro+ (Europe 1940)" with the two boxes (Axis and Allies recommended), plus the switch
 * {@code aerei_corretti} per alliance in a 2x2 design: 00 off for both, 10 Axis only, 01 Allies
 * only, 11 both (digits: Axis, Allies).
 */
final class ConfrontoP14 {
  private ConfrontoP14() {}

  static final String SWITCH = "aerei_corretti";
  static final List<String> CONFIG = List.of("00", "10", "01", "11");
  static final String LONDRA = "United Kingdom";
  static final String ZONA_TARANTO = "97 Sea Zone";
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

  /** "00".."11" for the games of the step (both alliances "Pro+ (Europe 1940)"), else null. */
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
    return (ConfrontoP12.accesi(json, "Axis").contains(SWITCH) ? "1" : "0")
        + (ConfrontoP12.accesi(json, "Allies").contains(SWITCH) ? "1" : "0");
  }

  static boolean applicabile(final List<Game> games) {
    return games.stream().anyMatch(g -> g.json() != null && codice(g.json()) != null);
  }

  // ============================================================================== block

  /** The block {@code p14} of one game ({@code data} = the monitor's json, with the battles). */
  static JsonObject blocco(final JsonObject json, final JsonObject data) {
    final JsonObject o = new JsonObject();
    final String code = codice(json);
    o.addProperty("configurazione", code == null ? "" : code);
    // the battles: "round|attacker|territory" -> result
    final Map<String, String> battles = new TreeMap<>();
    if (data != null && data.has("battaglie")) {
      for (final JsonElement e : data.getAsJsonArray("battaglie")) {
        final JsonObject b = e.getAsJsonObject();
        battles.put(
            s(b, "round") + "|" + s(b, "attaccante") + "|" + s(b, "territorio"), s(b, "risultato"));
      }
    }
    final JsonArray turns = new JsonArray();
    final JsonArray attacks = new JsonArray();
    int tarantoHard = -1;
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if (!"decisione".equals(s(d, "riga")) || !"p14_aerei".equals(s(d, "tipo"))) {
        continue;
      }
      final int round = d.has("round") ? d.get("round").getAsInt() : 0;
      final String nation = s(d, "nazione");
      final JsonObject par = d.has("parametri") ? d.getAsJsonObject("parametri") : new JsonObject();
      final JsonObject t = new JsonObject();
      t.addProperty("round", round);
      t.addProperty("nazione", nation);
      if ("combattimento".equals(s(par, "fase"))) {
        t.addProperty("fase", "combattimento");
        for (final String k :
            List.of(
                "opzioni_aeroporto",
                "opzioni_portaerei",
                "aerei_aeroporto",
                "aerei_portaerei",
                "assegnati_portaerei")) {
          t.addProperty(k, (int) num(par, k));
        }
        t.addProperty("tolti", par.has("tolti") ? par.getAsJsonArray("tolti").size() : 0);
        int n = 0;
        if (par.has("attacchi")) {
          for (final JsonElement a : par.getAsJsonArray("attacchi")) {
            final JsonObject at = a.getAsJsonObject();
            final JsonObject x = new JsonObject();
            x.addProperty("round", round);
            x.addProperty("nazione", nation);
            x.addProperty("bersaglio", s(at, "bersaglio"));
            x.addProperty("vittoria", num(at, "vittoria"));
            int airBase = 0;
            int carrier = 0;
            for (final JsonElement p : at.getAsJsonArray("aerei")) {
              final String m = s(p.getAsJsonObject(), "motivo");
              airBase += "aeroporto".equals(m) ? 1 : 0;
              carrier += "portaerei".equals(m) ? 1 : 0;
            }
            if (airBase + carrier == 0) {
              continue; // only Hard's air units with a carrier place
            }
            x.addProperty("aerei_aeroporto", airBase);
            x.addProperty("aerei_portaerei", carrier);
            final String result =
                battles.getOrDefault(round + "|" + nation + "|" + s(at, "bersaglio"), "");
            x.addProperty("esito", esito(result));
            attacks.add(x);
            n++;
          }
        }
        t.addProperty("attacchi", n);
        if (round == 1 && UK.equals(nation) && par.has("primo_piano")) {
          tarantoHard = 0;
          for (final JsonElement p : par.getAsJsonArray("primo_piano")) {
            if (p.getAsString().startsWith(LONDRA + ">" + ZONA_TARANTO + ":")) {
              tarantoHard++;
            }
          }
        }
      } else {
        t.addProperty("fase", "atterraggio");
        for (final String k :
            List.of("assegnati", "atterrati", "gia_a_posto", "distrutti_prima", "rifiutati")) {
          t.addProperty(k, (int) num(par, k));
        }
      }
      turns.add(t);
    }
    o.add("turni", turns);
    o.add("attacchi", attacks);
    o.addProperty("taranto_hard_aerei_londra", tarantoHard);
    // the air units that could not land (every configuration)
    final JsonArray lost = new JsonArray();
    int lostUnits = 0;
    if (data != null && data.has("aerei_non_atterrati")) {
      for (final JsonElement e : data.getAsJsonArray("aerei_non_atterrati")) {
        lost.add(e);
        lostUnits += (int) num(e.getAsJsonObject(), "unita");
      }
    }
    o.add("non_atterrati", lost);
    o.addProperty("aerei_non_atterrati", lostUnits);
    // Moscow, Rome and the Taranto lever (from the block p13)
    final JsonObject p13 = sub(json, "p13");
    final JsonObject cadute =
        p13 != null && p13.has("cadute") ? p13.getAsJsonObject("cadute") : new JsonObject();
    o.add("cadute", cadute.deepCopy());
    final JsonObject taranto =
        p13 != null && p13.has("taranto") ? p13.getAsJsonObject("taranto") : new JsonObject();
    o.addProperty("taranto_leva", s(taranto, "leva"));
    return o;
  }

  private static String esito(final String result) {
    return switch (result) {
      case "CONQUERED", "WON_WITHOUT_CONQUERING", "WON_WITH_ENEMY_LEFT", "BLITZED" -> "vinta";
      case "LOST" -> "persa";
      case "" -> "nessuna battaglia";
      default -> "altro";
    };
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
      final JsonObject b = sub(g.json(), "p14");
      if (b == null || !ConfrontoP12.valida(g.json())) {
        invalid.computeIfAbsent(code, k -> new int[1])[0]++;
        continue;
      }
      b.addProperty("configurazione", code);
      b.addProperty("vincitore", ConfrontoP12.vincitore(g.json()));
      b.addProperty("stallo", s(g.json(), "stato").startsWith("stallo"));
      by.computeIfAbsent(code, k -> new ArrayList<>()).add(b);
    }
    if (by.isEmpty() && invalid.isEmpty()) {
      return;
    }
    md.append(
"""


### P14

""");
    md.append(
        "Step P14: tutte le nazioni \"Pro+ (Europe 1940)\" (Asse consigliato contro Alleati"
            + " consigliati, 7 interruttori compreso taranto) + aerei_corretti per alleanza (cifre:"
            + " Asse, Alleati). Valide = completate o in stallo. Soglia dei due confronti dichiarati"
            + " 0,025 (0,05/2).\n\n");
    md.append(
"""
| Config | Valide | Stalli | Errori | Vittorie Asse | Vittorie Alleati | Mosca <= R12 | Roma <= R16 | Taranto R1 |
|---|---|---|---|---|---|---|---|---|
""");
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
          .append(frac(l, b -> entro(b, "Mosca", 12)))
          .append(" | ")
          .append(frac(l, b -> entro(b, "Roma", 16)))
          .append(" | ")
          .append(frac(l, b -> "P13_TARANTO".equals(s(b, "taranto_leva"))))
          .append(" |\n");
    }
    // the two declared comparisons
    final List<JsonObject> axisOn = new ArrayList<>();
    final List<JsonObject> axisOff = new ArrayList<>();
    final List<JsonObject> alliesOn = new ArrayList<>();
    final List<JsonObject> alliesOff = new ArrayList<>();
    for (final String c : CONFIG) {
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      (c.charAt(0) == '1' ? axisOn : axisOff).addAll(l);
      (c.charAt(1) == '1' ? alliesOn : alliesOff).addAll(l);
    }
    md.append(
"""

#### Confronti dichiarati (Fisher esatto, soglia 0,025)

| Confronto | Acceso | Spento | p |
|---|---|---|---|
""");
    confronto(
        md,
        "A. vittorie Asse, Asse acceso (10+11) contro spento (00+01)",
        axisOn,
        axisOff,
        ConfrontoP14::axisWin);
    confronto(
        md,
        "B. vittorie Alleati, Alleati acceso (01+11) contro spento (00+10)",
        alliesOn,
        alliesOff,
        ConfrontoP14::alliesWin);
    md.append(
"""

#### Secondari (solo descrittivi)

| Misura | Asse acceso | Asse spento | Alleati acceso | Alleati spento |
|---|---|---|---|---|
""");
    final Map<String, Predicate<JsonObject>> sec = new java.util.LinkedHashMap<>();
    sec.put("Mosca entro R12", b -> entro(b, "Mosca", 12));
    sec.put("Roma entro R16", b -> entro(b, "Roma", 16));
    sec.put("Taranto eseguito al R1", b -> "P13_TARANTO".equals(s(b, "taranto_leva")));
    for (final var m : sec.entrySet()) {
      md.append("| ")
          .append(m.getKey())
          .append(" | ")
          .append(frac(axisOn, m.getValue()))
          .append(" | ")
          .append(frac(axisOff, m.getValue()))
          .append(" | ")
          .append(frac(alliesOn, m.getValue()))
          .append(" | ")
          .append(frac(alliesOff, m.getValue()))
          .append(" |\n");
    }
    // the diagnostics
    md.append(
"""

#### Diagnostica (opzioni aggiunte, attacchi con quegli aerei, aerei senza atterraggio)

| Config | Partite | Opzioni aeroporto | Opzioni portaerei | Aerei aeroporto | Aerei portaerei | Tolti dalla revisione | Attacchi con aerei aggiunti | Vittoria stimata media | Vinti / persi / altro | Atterrati sulle portaerei | Rifiutati dal motore | Aerei non atterrati (tutti) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final String c : CONFIG) {
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      final int[] sums = new int[8];
      double win = 0;
      int nAtt = 0;
      int won = 0;
      int lostB = 0;
      int other = 0;
      int lostAir = 0;
      for (final JsonObject b : l) {
        for (final JsonElement e : b.getAsJsonArray("turni")) {
          final JsonObject t = e.getAsJsonObject();
          sums[0] += (int) num(t, "opzioni_aeroporto");
          sums[1] += (int) num(t, "opzioni_portaerei");
          sums[2] += (int) num(t, "aerei_aeroporto");
          sums[3] += (int) num(t, "aerei_portaerei");
          sums[4] += (int) num(t, "tolti");
          sums[5] += (int) num(t, "atterrati");
          sums[6] += (int) num(t, "rifiutati");
        }
        for (final JsonElement e : b.getAsJsonArray("attacchi")) {
          final JsonObject a = e.getAsJsonObject();
          nAtt++;
          win += num(a, "vittoria");
          switch (s(a, "esito")) {
            case "vinta" -> won++;
            case "persa" -> lostB++;
            default -> other++;
          }
        }
        lostAir += (int) num(b, "aerei_non_atterrati");
      }
      md.append("| ")
          .append(c)
          .append(" | ")
          .append(l.size())
          .append(" | ")
          .append(sums[0])
          .append(" | ")
          .append(sums[1])
          .append(" | ")
          .append(sums[2])
          .append(" | ")
          .append(sums[3])
          .append(" | ")
          .append(sums[4])
          .append(" | ")
          .append(nAtt)
          .append(" | ")
          .append(nAtt == 0 ? "-" : f(win / nAtt) + "%")
          .append(" | ")
          .append(won)
          .append(" / ")
          .append(lostB)
          .append(" / ")
          .append(other)
          .append(" | ")
          .append(sums[5])
          .append(" | ")
          .append(sums[6])
          .append(" | ")
          .append(lostAir)
          .append(" |\n");
    }
    // per nation and round (configurations with the switch on for the nation's alliance)
    md.append(
"""

#### Per nazione e round (somma sulle partite con l'interruttore acceso per la nazione)

| Nazione | Round | Opzioni aeroporto | Opzioni portaerei | Attacchi con aerei aggiunti | Vinti | Aerei non atterrati |
|---|---|---|---|---|---|---|
""");
    final Map<String, int[]> perNation = new TreeMap<>();
    for (final String c : CONFIG) {
      for (final JsonObject b : by.getOrDefault(c, List.of())) {
        for (final JsonElement e : b.getAsJsonArray("turni")) {
          final JsonObject t = e.getAsJsonObject();
          final int[] v = perNation.computeIfAbsent(key(t), k -> new int[5]);
          v[0] += (int) num(t, "opzioni_aeroporto");
          v[1] += (int) num(t, "opzioni_portaerei");
          v[2] += (int) num(t, "attacchi");
        }
        for (final JsonElement e : b.getAsJsonArray("attacchi")) {
          final JsonObject a = e.getAsJsonObject();
          if ("vinta".equals(s(a, "esito"))) {
            perNation.computeIfAbsent(key(a), k -> new int[5])[3]++;
          }
        }
        for (final JsonElement e : b.getAsJsonArray("non_atterrati")) {
          final JsonObject a = e.getAsJsonObject();
          perNation.computeIfAbsent(key(a), k -> new int[5])[4] += (int) num(a, "unita");
        }
      }
    }
    perNation.forEach(
        (k, v) -> {
          final String[] p = k.split("\\|");
          md.append("| ")
              .append(p[0])
              .append(" | ")
              .append(Integer.parseInt(p[1]))
              .append(" | ")
              .append(v[0])
              .append(" | ")
              .append(v[1])
              .append(" | ")
              .append(v[2])
              .append(" | ")
              .append(v[3])
              .append(" | ")
              .append(v[4])
              .append(" |\n");
        });
    // Taranto: Hard alone (first plan of the British round 1) with the switch on for the Allies
    md.append(
"""

#### Taranto: aerei di Londra sulla zona 97 nel primo piano di Hard al R1 (Alleati accesi)

""");
    int games0 = 0;
    int withPlanes = 0;
    int planes = 0;
    for (final JsonObject b : alliesOn) {
      final int n = (int) num(b, "taranto_hard_aerei_londra");
      if (n >= 0) {
        games0++;
        withPlanes += n > 0 ? 1 : 0;
        planes += n;
      }
    }
    md.append("Partite con il primo piano registrato: ")
        .append(games0)
        .append("; con almeno un aereo di Londra sulla 97: ")
        .append(withPlanes)
        .append(" (aerei in tutto: ")
        .append(planes)
        .append(").\n");
  }

  private static String key(final JsonObject t) {
    return s(t, "nazione") + "|" + String.format(Locale.ROOT, "%03d", (int) num(t, "round"));
  }

  private static void confronto(
      final StringBuilder md,
      final String label,
      final List<JsonObject> on,
      final List<JsonObject> off,
      final Predicate<JsonObject> p) {
    final int a = count(on, p);
    final int b = count(off, p);
    md.append("| ")
        .append(label)
        .append(" | ")
        .append(frac(on, p))
        .append(" | ")
        .append(frac(off, p))
        .append(" | ")
        .append(
            on.isEmpty() || off.isEmpty()
                ? "-"
                : ConfrontoR5.pText(ConfrontoR4.fisher(a, on.size(), b, off.size())))
        .append(" |\n");
  }

  // ================================================================================ helpers

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

  static boolean entro(final JsonObject b, final String c, final int r) {
    final JsonObject k = b.has("cadute") ? b.getAsJsonObject("cadute") : new JsonObject();
    final int x = k.has(c) ? k.get(c).getAsInt() : 0;
    return x > 0 && x <= r;
  }

  static boolean axisWin(final JsonObject b) {
    return "Axis".equals(s(b, "vincitore"));
  }

  static boolean alliesWin(final JsonObject b) {
    return "Allies".equals(s(b, "vincitore"));
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
