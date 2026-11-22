package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P13: the block {@code p13} of one game and the section "P13" of the general summary: three
 * levers in a factorial design (digits: taranto, fabbriche_tenute, bombardamento_mosca; Axis =
 * commitment + siege + German levers, Allies = the final Allies + taranto) and the references r0 /
 * r1 (Axis of Hard against the final Allies without / with taranto).
 */
final class ConfrontoP13 {
  private ConfrontoP13() {}

  static final List<String> LEVE = List.of("taranto", "fabbriche_tenute", "bombardamento_mosca");
  static final List<String> FATTORIALE =
      List.of("000", "001", "010", "011", "100", "101", "110", "111");
  static final List<String> CITTA = List.of("Ucraina", "Leningrado", "Stalingrado");
  static final String ZONA_TARANTO = "97 Sea Zone";
  static final int MAX_ROUND = 20;

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

  /** "000".."111" for the factorial games, r0 / r1 for the references, null otherwise. */
  @Nullable
  static String codice(final JsonObject json) {
    if (!json.has("parametri")
        || !json.getAsJsonObject("parametri").has("fino_a_vittoria")
        || !json.getAsJsonObject("parametri").get("fino_a_vittoria").getAsBoolean()) {
      return null;
    }
    if (AutoplayOptions.HARD.equals(tipo(json, "Allies"))) {
      return null;
    }
    final Set<String> allies = new TreeSet<>(ConfrontoP12.accesi(json, "Allies"));
    final boolean taranto = allies.remove("taranto");
    if (!allies.equals(ConfrontoP12.FINALI)) {
      return null;
    }
    if (AutoplayOptions.HARD.equals(tipo(json, "Axis"))) {
      return taranto ? "r1" : "r0";
    }
    final Set<String> axis = new TreeSet<>(ConfrontoP12.accesi(json, "Axis"));
    if (!axis.remove("impegno") || !axis.remove("assedio")) {
      return null;
    }
    final boolean f = axis.remove("fabbriche_tenute");
    final boolean b = axis.remove("bombardamento_mosca");
    if (!axis.isEmpty()) {
      return null;
    }
    return (taranto ? "1" : "0") + (f ? "1" : "0") + (b ? "1" : "0");
  }

  static boolean applicabile(final List<Game> games) {
    return games.stream().anyMatch(g -> g.json() != null && codice(g.json()) != null);
  }

  // ============================================================================== block

  /** The block {@code p13} of one game ({@code data} = the monitor's json, with the battles). */
  static JsonObject blocco(final JsonObject json, final JsonObject data) {
    final JsonObject o = new JsonObject();
    final String code = codice(json);
    o.addProperty("configurazione", code == null ? "" : code);
    final JsonArray rounds = json.has("round") ? json.getAsJsonArray("round") : new JsonArray();
    // falls and the captured factories
    final JsonObject cadute = new JsonObject();
    final JsonObject fab = new JsonObject();
    final Map<String, Boolean> prev = new TreeMap<>();
    final JsonArray per = new JsonArray();
    for (final JsonElement e : rounds) {
      final JsonObject r = e.getAsJsonObject();
      final int round = r.has("round") ? r.get("round").getAsInt() : 0;
      final JsonObject r4 = sub(r, "r4");
      if (r4 != null && r4.has("proprietari")) {
        final JsonObject own = r4.getAsJsonObject("proprietari");
        for (final String c : List.of("Mosca", "Ucraina", "Leningrado", "Stalingrado")) {
          if (!cadute.has(c) && own.has(c) && asse(own.get(c).getAsString())) {
            cadute.addProperty(c, round);
          }
        }
        if (!cadute.has("Londra") && own.has("Londra") && asse(own.get("Londra").getAsString())) {
          cadute.addProperty("Londra", round);
        }
        for (final String c : List.of("Roma", "Berlino")) {
          if (!cadute.has(c) && own.has(c) && !asse(own.get(c).getAsString())) {
            cadute.addProperty(c, round);
          }
        }
        for (final String c : CITTA) {
          final boolean axis = own.has(c) && asse(own.get(c).getAsString());
          final JsonObject f = fab.has(c) ? fab.getAsJsonObject(c) : new JsonObject();
          if (axis && !f.has("cattura")) {
            f.addProperty("cattura", round);
          }
          if (!axis && Boolean.TRUE.equals(prev.get(c))) {
            if (!f.has("persa")) {
              f.addProperty("persa", round);
            }
            f.addProperty("perdite", (f.has("perdite") ? f.get("perdite").getAsInt() : 0) + 1);
          }
          prev.put(c, axis);
          fab.add(c, f);
        }
      }
      if (round > MAX_ROUND) {
        continue;
      }
      final JsonObject x = new JsonObject();
      x.addProperty("round", round);
      final JsonObject p13 = sub(r, "p13");
      if (p13 != null) {
        for (final String k : List.of("tedeschi_est", "riparazioni_russe", "danno_tedesco")) {
          if (p13.has(k)) {
            x.add(k, p13.get(k));
          }
        }
      }
      if (r4 != null) {
        if (r4.has("esercito") && r4.getAsJsonObject("esercito").has("Russians")) {
          x.add("russi", r4.getAsJsonObject("esercito").get("Russians"));
        }
        if (r4.has("esercito") && r4.getAsJsonObject("esercito").has("Germans")) {
          x.add("tedeschi", r4.getAsJsonObject("esercito").get("Germans"));
        }
        if (r4.has("prodotte_mosca")) {
          x.add("prodotte_mosca", r4.get("prodotte_mosca"));
        }
        if (r4.has("danno_mosca")) {
          x.add("danno_mosca", r4.get("danno_mosca"));
        }
      }
      final JsonObject naz = sub(r, "nazioni");
      if (naz != null && naz.has("Russians") && naz.get("Russians").isJsonObject()) {
        final JsonObject ru = naz.getAsJsonObject("Russians");
        if (ru.has("pu_riparazioni")) {
          x.add("pu_riparazioni_russe", ru.get("pu_riparazioni"));
        }
      }
      final JsonObject p12 = sub(r, "p12");
      if (p12 != null) {
        for (final String k : List.of("ita_navi", "ita_reddito", "egitto_asse")) {
          if (p12.has(k)) {
            x.add(k, p12.get(k));
          }
        }
      }
      x.addProperty("ita_bonus", ConfrontoP12.bonus(r));
      per.add(x);
    }
    o.add("cadute", cadute);
    o.add("fabbriche", fab);
    o.add("per_round", per);
    // round 1: Taranto and the German battles
    final JsonObject taranto = new JsonObject();
    final JsonArray germ = new JsonArray();
    final JsonArray battles =
        data != null && data.has("battaglie") ? data.getAsJsonArray("battaglie") : new JsonArray();
    for (final JsonElement e : battles) {
      final JsonObject b = e.getAsJsonObject();
      if (!b.has("round") || b.get("round").getAsInt() != 1) {
        continue;
      }
      final String att = s(b, "attaccante");
      final String def = s(b, "difensore");
      if (ZONA_TARANTO.equals(s(b, "territorio")) && "British".equals(att)) {
        taranto.addProperty("eseguito", true);
        taranto.addProperty("risultato", s(b, "risultato"));
        taranto.addProperty("tuv_perso_uk", num(b, "tuv_perso_attaccante"));
        taranto.addProperty("tuv_perso_italia", num(b, "tuv_perso_difensore"));
      }
      if ("Germans".equals(att) || "Germans".equals(def)) {
        final boolean ga = "Germans".equals(att);
        final JsonObject g = new JsonObject();
        g.addProperty("territorio", s(b, "territorio"));
        g.addProperty("attaccante", att);
        g.addProperty("difensore", def);
        g.addProperty("tedeschi_attaccano", ga);
        g.addProperty("tuv_tedesco", num(b, ga ? "tuv_perso_attaccante" : "tuv_perso_difensore"));
        g.addProperty(
            "tuv_avversario", num(b, ga ? "tuv_perso_difensore" : "tuv_perso_attaccante"));
        g.addProperty("risultato", s(b, "risultato"));
        germ.add(g);
      }
    }
    if (!taranto.has("eseguito")) {
      taranto.addProperty("eseguito", false);
    }
    boolean scramble = false;
    int ukMed = -1;
    if (!rounds.isEmpty()) {
      final JsonObject p13 = sub(rounds.get(0).getAsJsonObject(), "p13");
      if (p13 != null) {
        if (p13.has("scramble")) {
          for (final JsonElement e : p13.getAsJsonArray("scramble")) {
            if (e.getAsString().contains(ZONA_TARANTO)) {
              scramble = true;
            }
          }
        }
        ukMed =
            p13.has("uk_navi_med_dopo_italia") ? p13.get("uk_navi_med_dopo_italia").getAsInt() : -1;
      }
    }
    taranto.addProperty("scramble", scramble);
    taranto.addProperty("uk_navi_med_dopo_italia", ukMed);
    o.add("germania_r1", germ);
    // the register of the step
    int bombTurns = 0;
    int bombDone = 0;
    int bombBought = 0;
    final JsonObject bomb = new JsonObject();
    final JsonArray fabRows = new JsonArray();
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if (!"decisione".equals(s(d, "riga"))) {
        continue;
      }
      final String t = s(d, "tipo");
      final int round = d.has("round") ? d.get("round").getAsInt() : 0;
      final JsonObject par = d.has("parametri") ? d.getAsJsonObject("parametri") : new JsonObject();
      final String codice = d.has("motivo") ? s(d.getAsJsonObject("motivo"), "codice") : "";
      switch (t) {
        case "p13_taranto" -> {
          if (!par.has("fase")) {
            taranto.addProperty("leva", codice);
            for (final String k :
                List.of(
                    "caccia_londra_mandati",
                    "caccia_londra_partiti",
                    "vittoria_stimata",
                    "vittoria_solo_mediterraneo",
                    "caccia_in_difesa_possibili",
                    "vittoria_tedesca_londra_senza_1",
                    "vittoria_tedesca_londra_senza_2",
                    "rifiutate",
                    "rifiutata")) {
              if (par.has(k)) {
                taranto.add(k, par.get(k));
              }
            }
          } else {
            taranto.add("atterrati_alla_base", par.get("atterrati_alla_base"));
          }
        }
        case "p13_fabbriche" -> {
          if ("non_combattimento".equals(s(par, "fase")) && par.has("fabbriche")) {
            for (final JsonElement f : par.getAsJsonArray("fabbriche")) {
              final JsonObject fr = f.getAsJsonObject().deepCopy();
              fr.addProperty("round", round);
              fabRows.add(fr);
            }
          }
        }
        case "p13_bombardamento" -> {
          if ("P13_BOMBARDIERE_ACQUISTO".equals(codice)) {
            bombBought += par.has("comprati_leva") ? par.get("comprati_leva").getAsInt() : 0;
          } else if ("P13_BOMBARDAMENTO_ESITO".equals(codice)
              || "P13_BOMBARDAMENTO_NO".equals(codice)) {
            bombTurns++;
            if ("P13_BOMBARDAMENTO_ESITO".equals(codice)) {
              bombDone++;
              bomb.add(String.valueOf(round), par);
            }
          }
        }
        default -> {}
      }
    }
    o.add("taranto", taranto);
    o.add("registro_fabbriche", fabRows);
    o.add("bombardamenti", bomb);
    o.addProperty("turni_leva_bombardamento", bombTurns);
    o.addProperty("turni_bombardamento", bombDone);
    o.addProperty("bombardieri_comprati_leva", bombBought);
    o.addProperty("guerra_germania_russia", guerra(json));
    return o;
  }

  /** The first German turn at war with Russia (0 = never). */
  static int guerra(final JsonObject json) {
    if (!json.has("dichiarazioni_guerra") || !json.get("dichiarazioni_guerra").isJsonArray()) {
      return 0;
    }
    for (final JsonElement e : json.getAsJsonArray("dichiarazioni_guerra")) {
      final JsonObject w = e.getAsJsonObject();
      final String between = s(w, "tra");
      if (between.contains("Germans") && between.contains("Russians")) {
        final int r = w.has("round") ? w.get("round").getAsInt() : 0;
        return "Germans".equals(s(w, "nazione_di_turno")) ? r : r + 1;
      }
    }
    return 0;
  }

  // ===================================================================== reading helpers

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

  private static boolean asse(final String owner) {
    return "Germans".equals(owner) || "Italians".equals(owner) || "Neutral_Axis".equals(owner);
  }

  static int caduta(final JsonObject b, final String c) {
    final JsonObject k = b.getAsJsonObject("cadute");
    return k.has(c) ? k.get(c).getAsInt() : 0;
  }

  static boolean entro(final JsonObject b, final String c, final int r) {
    final int x = caduta(b, c);
    return x > 0 && x <= r;
  }

  /** The value at round {@code r} of a per-round key, NaN if absent. */
  static double alRound(final JsonObject b, final int r, final String k) {
    for (final JsonElement e : b.getAsJsonArray("per_round")) {
      final JsonObject x = e.getAsJsonObject();
      if (x.get("round").getAsInt() == r) {
        if (!x.has(k)) {
          return Double.NaN;
        }
        final var v = x.get(k).getAsJsonPrimitive();
        return v.isBoolean() ? (v.getAsBoolean() ? 1 : 0) : v.getAsDouble();
      }
    }
    return Double.NaN;
  }

  static double somma(final JsonObject b, final int from, final int to, final String k) {
    double s = 0;
    for (int r = from; r <= to; r++) {
      final double v = alRound(b, r, k);
      if (!Double.isNaN(v)) {
        s += v;
      }
    }
    return s;
  }

  static boolean ripresa(final JsonObject b, final String c) {
    final JsonObject f = b.getAsJsonObject("fabbriche");
    return f.has(c) && f.getAsJsonObject(c).has("persa");
  }

  static boolean presa(final JsonObject b, final String c) {
    final JsonObject f = b.getAsJsonObject("fabbriche");
    return f.has(c) && f.getAsJsonObject(c).has("cattura");
  }

  static boolean axisWin(final JsonObject b) {
    return "Axis".equals(s(b, "vincitore"));
  }

  static boolean alliesWin(final JsonObject b) {
    return "Allies".equals(s(b, "vincitore"));
  }

  private static boolean bit(final JsonObject b, final int i) {
    final String c = s(b, "configurazione");
    return c.length() == 3 && c.charAt(i) == '1';
  }

  // ============================================================================== section

  static void write(final StringBuilder md, final List<Game> games) {
    final Map<String, List<JsonObject>> by = new TreeMap<>();
    final Map<String, int[]> invalid = new TreeMap<>();
    final Map<String, int[]> stalls = new TreeMap<>();
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      final String code = codice(g.json());
      if (code == null) {
        continue;
      }
      final JsonObject b = sub(g.json(), "p13");
      if (b == null || !ConfrontoP12.valida(g.json())) {
        invalid.computeIfAbsent(code, k -> new int[1])[0]++;
        continue;
      }
      b.addProperty("configurazione", code);
      b.addProperty("vincitore", ConfrontoP12.vincitore(g.json()));
      final boolean stall = s(g.json(), "stato").startsWith("stallo");
      b.addProperty("stallo", stall);
      if (stall) {
        stalls.computeIfAbsent(code, k -> new int[1])[0]++;
      }
      by.computeIfAbsent(code, k -> new ArrayList<>()).add(b);
    }
    if (by.isEmpty() && invalid.isEmpty()) {
      return;
    }
    md.append(
"""


### P13

""");
    md.append(
        "Step P13: tre leve in disegno fattoriale (cifre: taranto, fabbriche_tenute,"
            + " bombardamento_mosca). Asse = impegno + assedio (+ leve tedesche), Alleati = Alleati"
            + " finali (+ taranto). r0 = Asse Hard contro Alleati finali, r1 = Asse Hard contro"
            + " Alleati finali + taranto. Valide = completate o in stallo; errori a parte. Cadute"
            + " dai proprietari a fine round. p > 0,05 non e' una conclusione; soglia corretta per"
            + " i tre confronti principali 0,0167.\n\n");
    // 1. table per configuration
    md.append(
        "| Config | Valide | Stalli | Errori | Vittorie Asse | Vittorie Alleati | Mosca <= R10 |"
            + " Mosca <= R12 | Roma <= R16 | Berlino cade |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|\n");
    final List<String> order = new ArrayList<>(FATTORIALE);
    order.add("r0");
    order.add("r1");
    for (final String c : order) {
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      md.append("| ")
          .append(c)
          .append(" | ")
          .append(l.size())
          .append(" | ")
          .append(stalls.getOrDefault(c, new int[1])[0])
          .append(" | ")
          .append(invalid.getOrDefault(c, new int[1])[0])
          .append(" | ")
          .append(frac(l, ConfrontoP13::axisWin))
          .append(" | ")
          .append(frac(l, ConfrontoP13::alliesWin))
          .append(" | ")
          .append(frac(l, b -> entro(b, "Mosca", 10)))
          .append(" | ")
          .append(frac(l, b -> entro(b, "Mosca", 12)))
          .append(" | ")
          .append(frac(l, b -> entro(b, "Roma", 16)))
          .append(" | ")
          .append(frac(l, b -> caduta(b, "Berlino") > 0))
          .append(" |\n");
    }
    final List<JsonObject> fact = new ArrayList<>();
    FATTORIALE.forEach(c -> fact.addAll(by.getOrDefault(c, List.of())));
    // 2. effect of each lever (on vs off over the factorial games)
    md.append(
"""

#### Effetto di ogni leva (accesa contro spenta, partite fattoriali)

""");
    md.append(
"""
| Leva | Misura | Accesa | Spenta | p (Fisher) |
|---|---|---|---|---|
""");
    final Map<String, Predicate<JsonObject>> measures = new java.util.LinkedHashMap<>();
    measures.put("vittorie Asse", ConfrontoP13::axisWin);
    measures.put("Mosca entro R12", b -> entro(b, "Mosca", 12));
    measures.put("Roma entro R16", b -> entro(b, "Roma", 16));
    for (int i = 0; i < 3; i++) {
      final int ii = i;
      final List<JsonObject> on = fact.stream().filter(b -> bit(b, ii)).toList();
      final List<JsonObject> off = fact.stream().filter(b -> !bit(b, ii)).toList();
      for (final var m : measures.entrySet()) {
        final int a = count(on, m.getValue());
        final int bb = count(off, m.getValue());
        md.append("| ")
            .append(LEVE.get(i))
            .append(" | ")
            .append(m.getKey())
            .append(" | ")
            .append(frac(on, m.getValue()))
            .append(" | ")
            .append(frac(off, m.getValue()))
            .append(" | ")
            .append(
                on.isEmpty() || off.isEmpty()
                    ? "-"
                    : ConfrontoR5.pText(ConfrontoR4.fisher(a, on.size(), bb, off.size())))
            .append(" |\n");
      }
    }
    // 3. interactions: logistic regression and 2x2 tables
    if (fact.size() >= 16) {
      md.append(
"""

#### Interazioni (regressione logistica sulle vittorie dell'Asse, IRLS, Wald)

""");
      final List<double[]> x = new ArrayList<>();
      final List<Integer> y = new ArrayList<>();
      for (final JsonObject b : fact) {
        final double t = bit(b, 0) ? 1 : 0;
        final double f = bit(b, 1) ? 1 : 0;
        final double bo = bit(b, 2) ? 1 : 0;
        x.add(new double[] {1, t, f, bo, t * f, t * bo, f * bo});
        y.add(axisWin(b) ? 1 : 0);
      }
      final double[][] fit = ConfrontoP12.logistica(x, y);
      final String[] names = {
        "costante",
        "taranto",
        "fabbriche",
        "bombardamento",
        "taranto x fabbriche",
        "taranto x bombardamento",
        "fabbriche x bombardamento"
      };
      md.append(
"""
| Termine | Coefficiente | OR | ES | p |
|---|---|---|---|---|
""");
      for (int j = 0; j < names.length; j++) {
        final double beta = fit[0][j];
        final double se = fit[1][j];
        md.append("| ")
            .append(names[j])
            .append(" | ")
            .append(f2(beta))
            .append(" | ")
            .append(f2(Math.exp(beta)))
            .append(" | ")
            .append(f2(se))
            .append(" | ")
            .append(se > 0 ? ConfrontoR5.pText(ConfrontoP12.pNormale(beta / se)) : "-")
            .append(" |\n");
      }
      md.append(
"""

Tabelle a doppia entrata (vittorie dell'Asse):

""");
      final int[][] pairs = {{0, 1}, {0, 2}, {1, 2}};
      for (final int[] p : pairs) {
        md.append("| ")
            .append(LEVE.get(p[0]))
            .append(" \\ ")
            .append(LEVE.get(p[1]))
            .append(
"""
 | spenta | accesa |
|---|---|---|
""");
        for (final boolean a : new boolean[] {false, true}) {
          md.append("| ").append(a ? "accesa" : "spenta");
          for (final boolean c : new boolean[] {false, true}) {
            final List<JsonObject> cell =
                fact.stream().filter(b -> bit(b, p[0]) == a && bit(b, p[1]) == c).toList();
            md.append(" | ").append(frac(cell, ConfrontoP13::axisWin));
          }
          md.append(" |\n");
        }
        md.append("\n");
      }
    }
    // 4. Taranto against the Axis of Hard
    final List<JsonObject> r0 = by.getOrDefault("r0", List.of());
    final List<JsonObject> r1 = by.getOrDefault("r1", List.of());
    if (!r0.isEmpty() && !r1.isEmpty()) {
      md.append(
"""

#### Taranto contro l'Asse di Hard (r1 contro r0)

""");
      md.append("Vittorie alleate: r1 ")
          .append(frac(r1, ConfrontoP13::alliesWin))
          .append(", r0 ")
          .append(frac(r0, ConfrontoP13::alliesWin))
          .append(", p (Fisher) ")
          .append(
              ConfrontoR5.pText(
                  ConfrontoR4.fisher(
                      count(r1, ConfrontoP13::alliesWin),
                      r1.size(),
                      count(r0, ConfrontoP13::alliesWin),
                      r0.size())))
          .append(".\n");
    }
    // 5. mechanism
    md.append(
"""

#### Meccanismo

""");
    md.append(
        "| Config | Taranto eseguito | Caccia di Londra 0/1/2 | Scramble su 97 | UK vince a"
            + " 97 | Navi UK nel Med dopo l'Italia R1 | Flotta italiana R2 | Reddito ITA R4-R6 |"
            + " Bonus ITA R4-R6 | Egitto Asse R2-R8 (round) |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|\n");
    for (final String c : order) {
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      if (l.isEmpty()) {
        continue;
      }
      final int[] fighters = new int[3];
      for (final JsonObject b : l) {
        final JsonObject t = b.getAsJsonObject("taranto");
        final int n =
            t.has("caccia_londra_partiti") ? t.get("caccia_londra_partiti").getAsInt() : -1;
        if (n >= 0 && n <= 2 && "P13_TARANTO".equals(s(t, "leva"))) {
          fighters[n]++;
        }
      }
      md.append("| ")
          .append(c)
          .append(" | ")
          .append(frac(l, b -> b.getAsJsonObject("taranto").get("eseguito").getAsBoolean()))
          .append(" | ")
          .append(fighters[0])
          .append("/")
          .append(fighters[1])
          .append("/")
          .append(fighters[2])
          .append(" | ")
          .append(frac(l, b -> b.getAsJsonObject("taranto").get("scramble").getAsBoolean()))
          .append(" | ")
          .append(frac(l, b -> vinceUk(b.getAsJsonObject("taranto"))))
          .append(" | ")
          .append(f(mean(l, b -> num(b.getAsJsonObject("taranto"), "uk_navi_med_dopo_italia"))))
          .append(" | ")
          .append(f(mean(l, b -> nz(alRound(b, 2, "ita_navi")))))
          .append(" | ")
          .append(f(mean(l, b -> somma(b, 4, 6, "ita_reddito") / 3)))
          .append(" | ")
          .append(f(mean(l, b -> somma(b, 4, 6, "ita_bonus") / 3)))
          .append(" | ")
          .append(f(mean(l, b -> somma(b, 2, 8, "egitto_asse"))))
          .append(" |\n");
    }
    md.append(
"""

| Config | Ucraina presa / ripresa dai russi | Leningrado presa / ripresa | Stalingrado presa / ripresa | Tedeschi a est R4/R6/R8 | Russi R4/R6/R8 | Prodotte a Mosca R4-R8 | Riparazioni russe R4-R8 (danno) | PU riparazioni russe R4-R8 | Danno tedesco R4-R8 |
""");
    md.append("|---|---|---|---|---|---|---|---|---|---|\n");
    for (final String c : order) {
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      if (l.isEmpty()) {
        continue;
      }
      md.append("| ").append(c);
      for (final String city : CITTA) {
        md.append(" | ")
            .append(count(l, b -> presa(b, city)))
            .append(" / ")
            .append(count(l, b -> ripresa(b, city)));
      }
      md.append(" | ");
      for (final int r : new int[] {4, 6, 8}) {
        md.append(f(mean(l, b -> nz(alRound(b, r, "tedeschi_est"))))).append(r < 8 ? "/" : "");
      }
      md.append(" | ");
      for (final int r : new int[] {4, 6, 8}) {
        md.append(f(mean(l, b -> nz(alRound(b, r, "russi"))))).append(r < 8 ? "/" : "");
      }
      md.append(" | ")
          .append(f(mean(l, b -> somma(b, 4, 8, "prodotte_mosca"))))
          .append(" | ")
          .append(f(mean(l, b -> somma(b, 4, 8, "riparazioni_russe"))))
          .append(" | ")
          .append(f(mean(l, b -> somma(b, 4, 8, "pu_riparazioni_russe"))))
          .append(" | ")
          .append(f(mean(l, b -> somma(b, 4, 8, "danno_tedesco"))))
          .append(" |\n");
    }
    // lever on vs off: recaptures and Moscow
    md.append(
"""

Riprese russe tra le partite in cui la citta' e' stata presa (accesa contro spenta):

""");
    md.append(
"""
| Leva | Misura | Accesa | Spenta | p (Fisher) |
|---|---|---|---|---|
""");
    for (final String city : List.of("Ucraina", "Leningrado")) {
      final List<JsonObject> on = fact.stream().filter(b -> bit(b, 1) && presa(b, city)).toList();
      final List<JsonObject> off = fact.stream().filter(b -> !bit(b, 1) && presa(b, city)).toList();
      final int a = count(on, b -> ripresa(b, city));
      final int bb = count(off, b -> ripresa(b, city));
      md.append("| fabbriche_tenute | ")
          .append(city)
          .append(" ripresa | ")
          .append(frac(on, b -> ripresa(b, city)))
          .append(" | ")
          .append(frac(off, b -> ripresa(b, city)))
          .append(" | ")
          .append(
              on.isEmpty() || off.isEmpty()
                  ? "-"
                  : ConfrontoR5.pText(ConfrontoR4.fisher(a, on.size(), bb, off.size())))
          .append(" |\n");
    }
    {
      final List<JsonObject> on = fact.stream().filter(b -> bit(b, 2)).toList();
      final List<JsonObject> off = fact.stream().filter(b -> !bit(b, 2)).toList();
      md.append("| bombardamento_mosca | prodotte a Mosca R4-R8 (media) | ")
          .append(f(mean(on, b -> somma(b, 4, 8, "prodotte_mosca"))))
          .append(" | ")
          .append(f(mean(off, b -> somma(b, 4, 8, "prodotte_mosca"))))
          .append(" | - |\n");
      md.append("| bombardamento_mosca | riparazioni russe R4-R8 (media, danno) | ")
          .append(f(mean(on, b -> somma(b, 4, 8, "riparazioni_russe"))))
          .append(" | ")
          .append(f(mean(off, b -> somma(b, 4, 8, "riparazioni_russe"))))
          .append(" | - |\n");
      md.append("| bombardamento_mosca | danno tedesco R4-R8 (media) | ")
          .append(f(mean(on, b -> somma(b, 4, 8, "danno_tedesco"))))
          .append(" | ")
          .append(f(mean(off, b -> somma(b, 4, 8, "danno_tedesco"))))
          .append(" | - |\n");
      final List<JsonObject> ton = fact.stream().filter(b -> bit(b, 0)).toList();
      final List<JsonObject> toff = fact.stream().filter(b -> !bit(b, 0)).toList();
      md.append("| taranto | flotta italiana R2 (media) | ")
          .append(f(mean(ton, b -> nz(alRound(b, 2, "ita_navi")))))
          .append(" | ")
          .append(f(mean(toff, b -> nz(alRound(b, 2, "ita_navi")))))
          .append(" | - |\n");
      md.append("| taranto | Egitto dell'Asse R2-R8 (round, media) | ")
          .append(f(mean(ton, b -> somma(b, 2, 8, "egitto_asse"))))
          .append(" | ")
          .append(f(mean(toff, b -> somma(b, 2, 8, "egitto_asse"))))
          .append(" | - |\n");
    }
    // register of the levers
    md.append(
"""

#### Registro delle leve

""");
    for (final String c : order) {
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      if (l.isEmpty()) {
        continue;
      }
      final boolean t = c.startsWith("1") || "r1".equals(c);
      final boolean fb = c.length() == 3 && c.charAt(1) == '1';
      final boolean bb = c.length() == 3 && c.charAt(2) == '1';
      if (!(t || fb || bb)) {
        continue;
      }
      md.append("- ").append(c).append(": ");
      if (t) {
        md.append("taranto ")
            .append(count(l, b -> "P13_TARANTO".equals(s(b.getAsJsonObject("taranto"), "leva"))))
            .append(" eseguiti su ")
            .append(l.size())
            .append(" (vittoria stimata media ")
            .append(f(mean(l, b -> num(b.getAsJsonObject("taranto"), "vittoria_stimata"))))
            .append("%, solo Mediterraneo ")
            .append(
                f(mean(l, b -> num(b.getAsJsonObject("taranto"), "vittoria_solo_mediterraneo"))))
            .append("%, rifiuti del motore ")
            .append(
                count(
                    l,
                    b ->
                        b.getAsJsonObject("taranto").has("rifiutate")
                            || b.getAsJsonObject("taranto").has("rifiutata")))
            .append("); ");
      }
      if (fb) {
        int rows = 0;
        double need = 0;
        double present = 0;
        double moved = 0;
        double missing = 0;
        int threat = 0;
        for (final JsonObject b : l) {
          for (final JsonElement e : b.getAsJsonArray("registro_fabbriche")) {
            final JsonObject r = e.getAsJsonObject();
            rows++;
            need += num(r, "richiesta");
            present += num(r, "guarnigione_presente") + num(r, "da_piazzare");
            moved += num(r, "spostate");
            missing += num(r, "mancanti");
            if (r.has("minaccia") && r.get("minaccia").getAsBoolean()) {
              threat++;
            }
          }
        }
        md.append("fabbriche: ")
            .append(rows)
            .append(" righe (turno x fabbrica), con minaccia ")
            .append(threat)
            .append(", guarnigione richiesta media ")
            .append(f(rows == 0 ? 0 : need / rows))
            .append(", presente (tenute + spostate + da piazzare) ")
            .append(f(rows == 0 ? 0 : present / rows))
            .append(", spostate ")
            .append(f(rows == 0 ? 0 : moved / rows))
            .append(", mancanti ")
            .append(f(rows == 0 ? 0 : missing / rows))
            .append("; ");
      }
      if (bb) {
        int turns = 0;
        double used = 0;
        double dmg = 0;
        double lost = 0;
        for (final JsonObject b : l) {
          for (final var e : b.getAsJsonObject("bombardamenti").entrySet()) {
            final JsonObject r = e.getValue().getAsJsonObject();
            turns++;
            used += num(r, "bombardieri_usati");
            dmg += num(r, "danno_inflitto");
            lost += num(r, "bombardieri_persi");
          }
        }
        md.append("bombardamento: turni con raid ")
            .append(turns)
            .append(" su ")
            .append(l.stream().mapToInt(b -> b.get("turni_leva_bombardamento").getAsInt()).sum())
            .append(" turni in guerra con la leva; per raid bombardieri ")
            .append(f(turns == 0 ? 0 : used / turns))
            .append(", danno ")
            .append(f(turns == 0 ? 0 : dmg / turns))
            .append(", persi ")
            .append(f(turns == 0 ? 0 : lost / turns))
            .append("; bombardieri comprati dalla leva ")
            .append(l.stream().mapToInt(b -> b.get("bombardieri_comprati_leva").getAsInt()).sum());
      }
      md.append("\n");
    }
    // German losses in round 1 per battle (every configuration)
    md.append(
"""

#### Perdite tedesche al primo round per battaglia (tutte le configurazioni)

""");
    final Map<String, List<double[]>> per = new TreeMap<>();
    for (final List<JsonObject> l : by.values()) {
      for (final JsonObject b : l) {
        for (final JsonElement e : b.getAsJsonArray("germania_r1")) {
          final JsonObject g = e.getAsJsonObject();
          final String key =
              s(g, "territorio")
                  + (g.get("tedeschi_attaccano").getAsBoolean()
                      ? " (attacco tedesco)"
                      : " (contrattacco di " + s(g, "attaccante") + ")");
          per.computeIfAbsent(key, k -> new ArrayList<>())
              .add(new double[] {num(g, "tuv_tedesco"), num(g, "tuv_avversario")});
        }
      }
    }
    final int total = by.values().stream().mapToInt(List::size).sum();
    md.append(
"""
| Battaglia | Partite | TUV tedesco perso (media ± ds) | TUV avversario perso (media ± ds) |
|---|---|---|---|
""");
    per.entrySet().stream()
        .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
        .forEach(
            e -> {
              final List<double[]> v = e.getValue();
              md.append("| ")
                  .append(e.getKey())
                  .append(" | ")
                  .append(v.size())
                  .append("/")
                  .append(total)
                  .append(" | ")
                  .append(ms(v, 0))
                  .append(" | ")
                  .append(ms(v, 1))
                  .append(" |\n");
            });
    // Moscow per round
    md.append(
"""

#### Mosca per round (media: unita' prodotte / riparazioni russe in danno)

""");
    md.append("| Config | Caduta (mediana) |");
    for (int r = 1; r <= 12; r++) {
      md.append(" R").append(r).append(" |");
    }
    md.append("\n|---|---|");
    for (int r = 1; r <= 12; r++) {
      md.append("---|");
    }
    md.append("\n");
    for (final String c : order) {
      final List<JsonObject> l = by.getOrDefault(c, List.of());
      if (l.isEmpty()) {
        continue;
      }
      final List<Integer> falls = new ArrayList<>();
      l.forEach(
          b -> {
            if (caduta(b, "Mosca") > 0) {
              falls.add(caduta(b, "Mosca"));
            }
          });
      falls.sort(Integer::compare);
      md.append("| ")
          .append(c)
          .append(" | ")
          .append(
              falls.isEmpty() ? "-" : "R" + falls.get(falls.size() / 2) + " (" + falls.size() + ")")
          .append(" |");
      for (int r = 1; r <= 12; r++) {
        final int rr = r;
        md.append(" ")
            .append(f(mean(l, b -> nz(alRound(b, rr, "prodotte_mosca")))))
            .append(" / ")
            .append(f(mean(l, b -> nz(alRound(b, rr, "riparazioni_russe")))))
            .append(" |");
      }
      md.append("\n");
    }
  }

  // ============================================================================== helpers

  /** The British attack on the Taranto zone was won (the Italian defenders gone). */
  static boolean vinceUk(final JsonObject t) {
    return t.get("eseguito").getAsBoolean()
        && Set.of("CONQUERED", "WON_WITHOUT_CONQUERING", "BLITZED", "WON_WITH_ENEMY_LEFT")
            .contains(s(t, "risultato"));
  }

  private static double nz(final double v) {
    return Double.isNaN(v) ? 0 : v;
  }

  private static String ms(final List<double[]> v, final int i) {
    double m = 0;
    for (final double[] x : v) {
      m += x[i];
    }
    m /= Math.max(1, v.size());
    double s = 0;
    for (final double[] x : v) {
      s += (x[i] - m) * (x[i] - m);
    }
    final double sd = v.size() > 1 ? Math.sqrt(s / (v.size() - 1)) : 0;
    return f(m) + " ± " + f(sd);
  }

  static int count(final List<JsonObject> l, final Predicate<JsonObject> p) {
    return (int) l.stream().filter(p).count();
  }

  private static String frac(final List<JsonObject> l, final Predicate<JsonObject> p) {
    if (l.isEmpty()) {
      return "-";
    }
    final int n = count(l, p);
    return n + "/" + l.size() + " " + Math.round(100.0 * n / l.size()) + "%";
  }

  static double mean(final List<JsonObject> l, final ToDoubleFunction<JsonObject> f) {
    return l.stream().mapToDouble(f).average().orElse(Double.NaN);
  }

  private static String f(final double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
  }

  private static String f2(final double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f", v).replace('.', ',');
  }
}
