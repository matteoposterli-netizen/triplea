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
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P12: Italy's four levers in all 16 combinations, against the Allies of Hard (H) and the
 * final Allies with six switches (F), plus the Allied references (a3, h3, h5). The block {@code
 * p12} of each {@code riepilogo.json} (read only: the board per round from {@link MonitorP12} and
 * {@link MonitorR4}, the register rows of the step) and the section "P12" of {@code
 * riepilogo-generale.md} with the analysis declared before the run.
 */
final class ConfrontoP12 {
  private ConfrontoP12() {}

  static final List<String> LEVE =
      List.of("italia_est", "italia_africa", "capitale_minima", "italia_gibilterra");
  static final Set<String> NUCLEO =
      Set.of("usa_proporzione", "sbarco_concentrato", "niente_neutrali");
  static final Set<String> FINALI =
      Set.of(
          "usa_proporzione",
          "sbarco_concentrato",
          "niente_neutrali",
          "norvegia_presto",
          "norvegia_esposta",
          "ponte_concentrato");

  static final int MAX_ROUND = 20;

  /** The Mediterranean landing territories of the US count (WW2 Europe 1940). */
  static final Set<String> MEDITERRANEO =
      Set.of(
          "Morocco",
          "Algeria",
          "Tunisia",
          "Libya",
          "Tobruk",
          "Alexandria",
          "Egypt",
          "Sicily",
          "Sardinia",
          "Southern Italy",
          "Northern Italy",
          "Southern France",
          "Greece",
          "Albania",
          "Yugoslavia",
          "Crete",
          "Gibraltar",
          "Syria",
          "Trans-Jordan");

  // ====================================================================== configuration

  static Set<String> accesi(final JsonObject json, final String alliance) {
    final Set<String> out = new TreeSet<>();
    if (!json.has("parametri")) {
      return out;
    }
    final JsonObject p = json.getAsJsonObject("parametri");
    if (p.has("stratega_acceso") && p.getAsJsonObject("stratega_acceso").has(alliance)) {
      for (final String s :
          p.getAsJsonObject("stratega_acceso").get(alliance).getAsString().split("[,+]")) {
        if (!s.isBlank()) {
          out.add(s.strip());
        }
      }
    }
    return out;
  }

  private static String tipo(final JsonObject json, final String alliance) {
    if (!json.has("parametri")) {
      return "";
    }
    final JsonObject p = json.getAsJsonObject("parametri");
    return p.has("tipi") && p.getAsJsonObject("tipi").has(alliance)
        ? p.getAsJsonObject("tipi").get(alliance).getAsString()
        : "";
  }

  /**
   * The code of a complete game of the step: H/F + four digits (east, Africa, capital, Gibraltar)
   * for the Axis with the siege; a3 (siege vs the core), h3 (Axis Hard vs the core), h5 (Axis Hard
   * vs the final Allies); null otherwise.
   */
  @Nullable
  static String codice(final JsonObject json) {
    if (!json.has("parametri")
        || !json.getAsJsonObject("parametri").has("fino_a_vittoria")
        || !json.getAsJsonObject("parametri").get("fino_a_vittoria").getAsBoolean()) {
      return null;
    }
    final Set<String> allies = accesi(json, "Allies");
    final boolean alliesHard = AutoplayOptions.HARD.equals(tipo(json, "Allies"));
    final String opp =
        alliesHard ? "H" : allies.equals(FINALI) ? "F" : allies.equals(NUCLEO) ? "N" : null;
    if (opp == null) {
      return null;
    }
    if (AutoplayOptions.HARD.equals(tipo(json, "Axis"))) {
      return "N".equals(opp) ? "h3" : "F".equals(opp) ? "h5" : null;
    }
    final Set<String> axis = accesi(json, "Axis");
    if (!axis.contains("impegno") || !axis.contains("assedio")) {
      return null;
    }
    final Set<String> rest = new TreeSet<>(axis);
    rest.remove("impegno");
    rest.remove("assedio");
    final StringBuilder digits = new StringBuilder();
    for (final String l : LEVE) {
      digits.append(rest.remove(l) ? '1' : '0');
    }
    if (!rest.isEmpty()) {
      return null;
    }
    if ("N".equals(opp)) {
      return "0000".contentEquals(digits) ? "a3" : null;
    }
    return opp + digits;
  }

  static boolean applicabile(final List<Game> games) {
    return games.stream().anyMatch(g -> g.json() != null && codice(g.json()) != null);
  }

  // ============================================================================== block

  /** The block {@code p12} of one game. */
  static JsonObject blocco(final JsonObject json) {
    final JsonObject o = new JsonObject();
    final String code = codice(json);
    o.addProperty("configurazione", code == null ? "" : code);
    final String stato = json.has("stato") ? json.get("stato").getAsString() : "";
    o.addProperty("valida", stato.startsWith("completata") || stato.startsWith("stallo"));
    o.addProperty("stallo", stato.startsWith("stallo"));
    final String winner =
        json.has("vittoria") && json.getAsJsonObject("vittoria").has("vincitore")
            ? json.getAsJsonObject("vittoria").get("vincitore").getAsString()
            : "";
    o.addProperty("vincitore", winner);
    // per round: the board
    final JsonArray rounds = json.has("round") ? json.getAsJsonArray("round") : new JsonArray();
    final JsonObject cadute = new JsonObject();
    final JsonArray per = new JsonArray();
    for (final JsonElement e : rounds) {
      final JsonObject r = e.getAsJsonObject();
      final int round = r.has("round") ? r.get("round").getAsInt() : 0;
      if (r.has("r4") && r.getAsJsonObject("r4").has("proprietari")) {
        final JsonObject own = r.getAsJsonObject("r4").getAsJsonObject("proprietari");
        for (final String c : List.of("Ucraina", "Leningrado", "Stalingrado", "Mosca")) {
          if (!cadute.has(c) && own.has(c) && asse(own.get(c).getAsString())) {
            cadute.addProperty(c, round);
          }
        }
        for (final String c : List.of("Roma", "Berlino")) {
          if (!cadute.has(c) && own.has(c) && !asse(own.get(c).getAsString())) {
            cadute.addProperty(c, round);
          }
        }
      }
      if (round > MAX_ROUND) {
        continue;
      }
      final JsonObject x = new JsonObject();
      x.addProperty("round", round);
      if (r.has("p12") && r.get("p12").isJsonObject()) {
        r.getAsJsonObject("p12").entrySet().forEach(en -> x.add(en.getKey(), en.getValue()));
      }
      if (r.has("r4") && r.getAsJsonObject("r4").has("italiani_sud_russia")) {
        x.addProperty(
            "italiani_sud_russia_r4",
            r.getAsJsonObject("r4").get("italiani_sud_russia").getAsInt());
      }
      x.addProperty("ita_bonus", bonus(r));
      per.add(x);
    }
    o.add("cadute", cadute);
    o.addProperty("mosca_r12", cadute.has("Mosca") && cadute.get("Mosca").getAsInt() <= 12);
    o.addProperty("roma_r16", cadute.has("Roma") && cadute.get("Roma").getAsInt() <= 16);
    o.add("per_round", per);
    // the register of the step
    final JsonObject bil = new JsonObject();
    final JsonObject turni = new JsonObject();
    int assalti = 0;
    int assaltiSbarcate = 0;
    int germania = 0;
    int africaR8 = 0;
    int gibR8 = 0;
    int turniFuoriTetto = 0;
    int turniBilancio = 0;
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if (!"decisione".equals(s(d, "riga"))) {
        continue;
      }
      final String t = s(d, "tipo");
      final int round = d.has("round") ? d.get("round").getAsInt() : 0;
      final JsonObject par = d.has("parametri") ? d.getAsJsonObject("parametri") : new JsonObject();
      switch (t) {
        case "p12_bilancio" -> {
          bil.add(String.valueOf(round), par);
          turniBilancio++;
          if (par.has("mare_entro_tetto") && !par.get("mare_entro_tetto").getAsBoolean()) {
            turniFuoriTetto++;
          }
        }
        case "p12_turno" -> {
          turni.add(String.valueOf(round), par);
          final JsonObject m = par.has("mare") ? par.getAsJsonObject("mare") : new JsonObject();
          final int sc = m.has("scaricate") ? m.get("scaricate").getAsInt() : 0;
          if (round <= 8) {
            if ("EGITTO".equals(s(m, "compito"))) {
              africaR8 += sc;
            } else if ("GIBILTERRA".equals(s(m, "compito"))) {
              gibR8 += sc;
            }
          }
        }
        case "p12_assalto" -> {
          if ("P12_ASSALTO".equals(d.getAsJsonObject("motivo").get("codice").getAsString())) {
            assalti++;
            final int n = par.has("unita_sbarcate") ? par.get("unita_sbarcate").getAsInt() : 0;
            assaltiSbarcate += n;
            if (round <= 8) {
              if ("EGITTO".equals(s(par, "compito"))) {
                africaR8 += n;
              } else {
                gibR8 += n;
              }
            }
          }
        }
        case "p12_germania" -> germania++;
        default -> {}
      }
    }
    o.add("bilancio", bil);
    o.add("turni", turni);
    o.addProperty("turni_bilancio", turniBilancio);
    o.addProperty("turni_mare_fuori_tetto", turniFuoriTetto);
    o.addProperty("assalti", assalti);
    o.addProperty("assalti_sbarcate", assaltiSbarcate);
    o.addProperty("turni_tedeschi_per_roma", germania);
    o.addProperty("portate_africa_r8", africaR8);
    o.addProperty("portate_gibilterra_r8", gibR8);
    // the US landings in the Mediterranean by R8
    int usaMed = 0;
    if (json.has("sbarchi") && json.get("sbarchi").isJsonArray()) {
      for (final JsonElement e : json.getAsJsonArray("sbarchi")) {
        final JsonObject l = e.getAsJsonObject();
        if ("Americans".equals(s(l, "nazione"))
            && l.has("round")
            && l.get("round").getAsInt() <= 8
            && MEDITERRANEO.contains(s(l, "territorio"))) {
          usaMed += l.has("unita") ? l.get("unita").getAsInt() : 0;
        }
      }
    }
    o.addProperty("usa_mediterraneo_r8", usaMed);
    // the Allies (from the blocks p6, p7, p9 written before this one)
    final JsonObject p6 = sub(json, "p6");
    final JsonObject p7 = sub(json, "p7");
    final JsonObject p9 = sub(json, "p9");
    o.addProperty(
        "norvegia_alleata_r4",
        p6 != null
            && p6.has("norway_alleata")
            && p6.getAsJsonObject("norway_alleata").has("4")
            && p6.getAsJsonObject("norway_alleata").get("4").getAsBoolean());
    o.addProperty(
        "ponte_rinforzi",
        p9 != null
                && p9.has("ponte_concentrato")
                && p9.getAsJsonObject("ponte_concentrato").has("rinforzi")
            ? p9.getAsJsonObject("ponte_concentrato").get("rinforzi").getAsInt()
            : 0);
    o.addProperty("usa_trasporti_persi_r8", num(p7, "usa_trasporti_persi_r8"));
    o.addProperty("uk_trasporti_persi_r8", num(p7, "uk_trasporti_persi_r8"));
    final JsonObject f2 =
        p7 != null && p7.has("f2_neutrali") ? p7.getAsJsonObject("f2_neutrali") : new JsonObject();
    o.addProperty(
        "neutrali_guerra_alleati",
        f2.has("aperta_da_alleati") && f2.get("aperta_da_alleati").getAsBoolean());
    o.addProperty(
        "neutrali_attacchi_alleati",
        f2.has("attacchi_alleati_neutrali")
            ? f2.get("attacchi_alleati_neutrali").getAsDouble()
            : 0);
    return o;
  }

  @Nullable
  private static JsonObject sub(final JsonObject j, final String key) {
    return j.has(key) && j.get(key).isJsonObject() && !j.getAsJsonObject(key).has("errore")
        ? j.getAsJsonObject(key)
        : null;
  }

  private static double num(@Nullable final JsonObject o, final String k) {
    return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
  }

  static boolean valida(final JsonObject json) {
    final String stato = s(json, "stato");
    return stato.startsWith("completata") || stato.startsWith("stallo");
  }

  static String vincitore(final JsonObject json) {
    for (final String k : List.of("vittoria", "esito")) {
      if (json.has(k)
          && json.get(k).isJsonObject()
          && json.getAsJsonObject(k).has("vincitore")
          && json.getAsJsonObject(k).get("vincitore").isJsonPrimitive()) {
        return json.getAsJsonObject(k).get("vincitore").getAsString();
      }
    }
    return "";
  }

  private static boolean asse(final String owner) {
    return "Germans".equals(owner) || "Italians".equals(owner) || "Neutral_Axis".equals(owner);
  }

  private static int bonus(final JsonObject r) {
    if (!r.has("r4") || !r.getAsJsonObject("r4").has("obiettivi")) {
      return 0;
    }
    final JsonObject ob = r.getAsJsonObject("r4").getAsJsonObject("obiettivi");
    if (!ob.has("Italians") || !ob.get("Italians").isJsonObject()) {
      return 0;
    }
    int n = 0;
    for (final var e : ob.getAsJsonObject("Italians").entrySet()) {
      if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
        n += e.getValue().getAsInt();
      }
    }
    return n;
  }

  private static String s(final JsonObject o, final String k) {
    return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
  }

  // ===================================================================== reading blocks

  /** A game's block, null if absent or broken. */
  @Nullable
  static JsonObject p12(final JsonObject json) {
    return json.has("p12")
            && json.get("p12").isJsonObject()
            && !json.getAsJsonObject("p12").has("errore")
        ? json.getAsJsonObject("p12")
        : null;
  }

  static boolean axisWin(final JsonObject b) {
    return "Axis".equals(s(b, "vincitore"));
  }

  static boolean alliesWin(final JsonObject b) {
    return "Allies".equals(s(b, "vincitore"));
  }

  static boolean flag(final JsonObject b, final String k) {
    return b.has(k) && b.get(k).getAsBoolean();
  }

  /** The value at round {@code r} of a per-round key (path "a.b"), NaN if absent. */
  static double alRound(final JsonObject b, final int r, final String path) {
    for (final JsonElement e : b.getAsJsonArray("per_round")) {
      final JsonObject x = e.getAsJsonObject();
      if (x.get("round").getAsInt() == r) {
        JsonElement v = x;
        for (final String k : path.split("\\.")) {
          if (!v.isJsonObject() || !v.getAsJsonObject().has(k)) {
            return Double.NaN;
          }
          v = v.getAsJsonObject().get(k);
        }
        return v.getAsJsonPrimitive().isBoolean() ? (v.getAsBoolean() ? 1 : 0) : v.getAsDouble();
      }
    }
    return Double.NaN;
  }

  /** Rounds in [from, to] with the per-round flag true. */
  static int roundCon(final JsonObject b, final int from, final int to, final String key) {
    int n = 0;
    for (int r = from; r <= to; r++) {
      if (alRound(b, r, key) == 1) {
        n++;
      }
    }
    return n;
  }

  /** The longest run of rounds with the flag true. */
  static int filaMax(final JsonObject b, final String key) {
    int best = 0;
    int cur = 0;
    for (final JsonElement e : b.getAsJsonArray("per_round")) {
      final JsonObject x = e.getAsJsonObject();
      if (x.has(key) && x.get(key).getAsBoolean()) {
        cur++;
        best = Math.max(best, cur);
      } else {
        cur = 0;
      }
    }
    return best;
  }

  /** Mean over the Italian turns of a balance key. */
  static double mediaBilancio(final JsonObject b, final String k) {
    final JsonObject bil = b.getAsJsonObject("bilancio");
    if (bil.size() == 0) {
      return Double.NaN;
    }
    double sum = 0;
    for (final var e : bil.entrySet()) {
      final JsonObject x = e.getValue().getAsJsonObject();
      sum += x.has(k) ? x.get(k).getAsDouble() : 0;
    }
    return sum / bil.size();
  }

  static double sommaTurni(final JsonObject b, final String k, final int maxRound) {
    double sum = 0;
    for (final var e : b.getAsJsonObject("turni").entrySet()) {
      if (Integer.parseInt(e.getKey()) <= maxRound) {
        final JsonObject x = e.getValue().getAsJsonObject();
        sum += x.has(k) ? x.get(k).getAsDouble() : 0;
      }
    }
    return sum;
  }

  // ========================================================================== statistics

  /** Logistic regression by IRLS: coefficients, standard errors (Wald). */
  static double[][] logistica(final List<double[]> x, final List<Integer> y) {
    final int p = x.get(0).length;
    final double[] beta = new double[p];
    double[][] cov = new double[p][p];
    for (int it = 0; it < 50; it++) {
      final double[][] h = new double[p][p];
      final double[] g = new double[p];
      for (int i = 0; i < x.size(); i++) {
        final double[] xi = x.get(i);
        double eta = 0;
        for (int j = 0; j < p; j++) {
          eta += beta[j] * xi[j];
        }
        final double mu = 1 / (1 + Math.exp(-eta));
        final double w = Math.max(1e-9, mu * (1 - mu));
        for (int j = 0; j < p; j++) {
          g[j] += (y.get(i) - mu) * xi[j];
          for (int k = 0; k < p; k++) {
            h[j][k] += w * xi[j] * xi[k];
          }
        }
      }
      cov = inversa(h);
      double change = 0;
      for (int j = 0; j < p; j++) {
        double d = 0;
        for (int k = 0; k < p; k++) {
          d += cov[j][k] * g[k];
        }
        beta[j] += d;
        change = Math.max(change, Math.abs(d));
      }
      if (change < 1e-8) {
        break;
      }
    }
    final double[] se = new double[p];
    for (int j = 0; j < p; j++) {
      se[j] = Math.sqrt(Math.max(0, cov[j][j]));
    }
    return new double[][] {beta, se};
  }

  static double[][] inversa(final double[][] m) {
    final int n = m.length;
    final double[][] a = new double[n][2 * n];
    for (int i = 0; i < n; i++) {
      System.arraycopy(m[i], 0, a[i], 0, n);
      a[i][n + i] = 1;
      a[i][i] += 1e-10;
    }
    for (int c = 0; c < n; c++) {
      int piv = c;
      for (int r = c + 1; r < n; r++) {
        if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) {
          piv = r;
        }
      }
      final double[] tmp = a[c];
      a[c] = a[piv];
      a[piv] = tmp;
      final double d = a[c][c];
      for (int k = 0; k < 2 * n; k++) {
        a[c][k] /= d;
      }
      for (int r = 0; r < n; r++) {
        if (r != c) {
          final double f = a[r][c];
          for (int k = 0; k < 2 * n; k++) {
            a[r][k] -= f * a[c][k];
          }
        }
      }
    }
    final double[][] inv = new double[n][n];
    for (int i = 0; i < n; i++) {
      System.arraycopy(a[i], n, inv[i], 0, n);
    }
    return inv;
  }

  /** Two-sided p of a standard normal z. */
  static double pNormale(final double z) {
    return 2 * (1 - phi(Math.abs(z)));
  }

  private static double phi(final double z) {
    // Abramowitz-Stegun 26.2.17
    final double t = 1 / (1 + 0.231_641_9 * z);
    final double d = 0.398_942_280_401_432_7 * Math.exp(-z * z / 2);
    final double p =
        d
            * t
            * (0.319_381_530
                + t
                    * (-0.356_563_782
                        + t * (1.781_477_937 + t * (-1.821_255_978 + t * 1.330_274_429))));
    return 1 - p;
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
      final JsonObject b = p12(g.json());
      if (code == null) {
        continue;
      }
      if (b == null || !valida(g.json())) {
        invalid.computeIfAbsent(code, k -> new int[1])[0]++;
        continue;
      }
      // the block is written before the end of the game: the winner and the state from the summary
      b.addProperty("configurazione", code);
      b.addProperty("valida", true);
      b.addProperty("vincitore", vincitore(g.json()));
      b.addProperty("stallo", s(g.json(), "stato").startsWith("stallo"));
      by.computeIfAbsent(code, k -> new ArrayList<>()).add(b);
    }
    if (by.isEmpty()) {
      return;
    }
    md.append(
"""


### P12

""");
    md.append(
        "Step P12: leve dell'Italia sopra impegno + assedio (cifre: est, africa, capitale,"
            + " gibilterra) contro gli Alleati di Hard (H) e gli Alleati finali a sei interruttori"
            + " (F); a3 = assedio contro il nucleo alleato, h3 = Asse Hard contro il nucleo, h5 ="
            + " Asse Hard contro gli Alleati finali. Valide = completate o in stallo; errori a"
            + " parte. Mosca entro R12, Roma entro R16: round di caduta dai proprietari a fine"
            + " round. p > 0,05 non e' una conclusione.\n\n");
    md.append(
"""
| Config | Valide | Errori | Stalli | Asse vince [IC 95%] | Alleati vince | Mosca entro R12 | Roma entro R16 |
|---|---|---|---|---|---|---|---|
""");
    for (final var e : by.entrySet()) {
      final List<JsonObject> l = e.getValue();
      final int n = l.size();
      final int ax = count(l, ConfrontoP12::axisWin);
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(n)
          .append(" | ")
          .append(invalid.getOrDefault(e.getKey(), new int[1])[0])
          .append(" | ")
          .append(count(l, b -> flag(b, "stallo")))
          .append(" | ")
          .append(ax)
          .append("/")
          .append(n)
          .append(" ")
          .append(ConfrontoR4.wilsonText(ax, n))
          .append(" | ")
          .append(count(l, ConfrontoP12::alliesWin))
          .append(" | ")
          .append(count(l, b -> flag(b, "mosca_r12")))
          .append(" | ")
          .append(count(l, b -> flag(b, "roma_r16")))
          .append(" |\n");
    }
    invalid.forEach(
        (k, v) -> {
          if (!by.containsKey(k)) {
            md.append("| ")
                .append(k)
                .append(" | 0 | ")
                .append(v[0])
                .append(" | - | - | - | - | - |\n");
          }
        });
    // the 16 combinations summed over the two opponents
    final List<String> combos = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      combos.add(
          String.format(Locale.ROOT, "%d%d%d%d", (i >> 3) & 1, (i >> 2) & 1, (i >> 1) & 1, i & 1));
    }
    md.append(
"""

#### Le 16 combinazioni (H, F e sommate)

Classifica per vittorie dell'Asse sommate (indicativa: la migliore di 16 e' gonfiata dal caso e va confermata).

""");
    md.append(
"""
| Combinazione | H: Asse vince | H: Mosca R12 | H: Roma R16 | F: Asse vince | F: Mosca R12 | F: Roma R16 | Somma: Asse vince [IC 95%] | Somma: Mosca R12 | Somma: Roma R16 |
|---|---|---|---|---|---|---|---|---|---|
""");
    final List<String> ranked = new ArrayList<>(combos);
    ranked.sort(
        (a, b) ->
            Double.compare(
                rate(both(by, b), ConfrontoP12::axisWin),
                rate(both(by, a), ConfrontoP12::axisWin)));
    for (final String c : ranked) {
      final List<JsonObject> h = by.getOrDefault("H" + c, List.of());
      final List<JsonObject> f = by.getOrDefault("F" + c, List.of());
      final List<JsonObject> s = both(by, c);
      if (s.isEmpty()) {
        continue;
      }
      md.append("| ")
          .append(c)
          .append(" | ")
          .append(frac(h, ConfrontoP12::axisWin))
          .append(" | ")
          .append(frac(h, b -> flag(b, "mosca_r12")))
          .append(" | ")
          .append(frac(h, b -> flag(b, "roma_r16")))
          .append(" | ")
          .append(frac(f, ConfrontoP12::axisWin))
          .append(" | ")
          .append(frac(f, b -> flag(b, "mosca_r12")))
          .append(" | ")
          .append(frac(f, b -> flag(b, "roma_r16")))
          .append(" | ")
          .append(count(s, ConfrontoP12::axisWin))
          .append("/")
          .append(s.size())
          .append(" ")
          .append(ConfrontoR4.wilsonText(count(s, ConfrontoP12::axisWin), s.size()))
          .append(" | ")
          .append(frac(s, b -> flag(b, "mosca_r12")))
          .append(" | ")
          .append(frac(s, b -> flag(b, "roma_r16")))
          .append(" |\n");
    }
    // the effect of each lever: on vs off (Fisher), pooled and per opponent
    final Map<String, Predicate<JsonObject>> esiti = new LinkedHashMap<>();
    esiti.put("Asse vince", ConfrontoP12::axisWin);
    esiti.put("Mosca entro R12", b -> flag(b, "mosca_r12"));
    esiti.put("Roma entro R16", b -> flag(b, "roma_r16"));
    md.append(
"""

#### Effetto di ogni leva (accesa contro spenta)

Fisher esatto a due code. Quattro confronti principali (vittorie dell'Asse, tutte le partite): soglia corretta 0,0125.

| Leva | Esito | Tutte: accesa | Tutte: spenta | p | H: accesa | H: spenta | p | F: accesa | F: spenta | p |
|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (int i = 0; i < 4; i++) {
      final int idx = i;
      for (final var es : esiti.entrySet()) {
        md.append("| ").append(LEVE.get(i)).append(" | ").append(es.getKey());
        for (final String opp : List.of("", "H", "F")) {
          final List<JsonObject> on = new ArrayList<>();
          final List<JsonObject> off = new ArrayList<>();
          for (final String c : combos) {
            for (final String o2 : List.of("H", "F")) {
              if (!opp.isEmpty() && !opp.equals(o2)) {
                continue;
              }
              (c.charAt(idx) == '1' ? on : off).addAll(by.getOrDefault(o2 + c, List.of()));
            }
          }
          final int a = count(on, es.getValue());
          final int b = count(off, es.getValue());
          md.append(" | ")
              .append(a)
              .append("/")
              .append(on.size())
              .append(" | ")
              .append(b)
              .append("/")
              .append(off.size())
              .append(" | ")
              .append(
                  on.isEmpty() || off.isEmpty()
                      ? "-"
                      : ConfrontoR5.pText(ConfrontoR4.fisher(a, on.size(), b, off.size())));
        }
        md.append(" |\n");
      }
    }
    // interactions: logistic regression with the opponent, the four levers and the six pairs
    final String[] names = {
      "intercetta",
      "avversario F",
      "est",
      "africa",
      "capitale",
      "gibilterra",
      "est x africa",
      "est x capitale",
      "est x gibilterra",
      "africa x capitale",
      "africa x gibilterra",
      "capitale x gibilterra"
    };
    md.append(
"""

#### Interazioni (regressione logistica)

""");
    for (final var es : esiti.entrySet()) {
      final List<double[]> x = new ArrayList<>();
      final List<Integer> y = new ArrayList<>();
      for (final String c : combos) {
        for (final String o2 : List.of("H", "F")) {
          for (final JsonObject b : by.getOrDefault(o2 + c, List.of())) {
            final double[] l = new double[4];
            for (int i = 0; i < 4; i++) {
              l[i] = c.charAt(i) == '1' ? 1 : 0;
            }
            x.add(
                new double[] {
                  1,
                  "F".equals(o2) ? 1 : 0,
                  l[0],
                  l[1],
                  l[2],
                  l[3],
                  l[0] * l[1],
                  l[0] * l[2],
                  l[0] * l[3],
                  l[1] * l[2],
                  l[1] * l[3],
                  l[2] * l[3]
                });
            y.add(es.getValue().test(b) ? 1 : 0);
          }
        }
      }
      if (x.size() < 30) {
        continue;
      }
      final double[][] fit = logistica(x, y);
      md.append("Esito: ")
          .append(es.getKey())
          .append(" (")
          .append(x.size())
          .append(
"""
 partite)

| Termine | Odds ratio | p |
|---|---|---|
""");
      for (int j = 1; j < names.length; j++) {
        final double se = fit[1][j];
        md.append("| ")
            .append(names[j])
            .append(" | ")
            .append(f2(Math.exp(fit[0][j])))
            .append(" | ")
            .append(se > 0 ? ConfrontoR5.pText(pNormale(fit[0][j] / se)) : "-")
            .append(" |\n");
      }
      md.append("\n");
    }
    md.append(
"""
Tabelle a doppia entrata (vittorie dell'Asse, H e F insieme): righe = prima leva spenta/accesa, colonne = seconda leva spenta/accesa.

| Coppia | 0/0 | 0/1 | 1/0 | 1/1 |
|---|---|---|---|---|
""");
    for (int i = 0; i < 4; i++) {
      for (int j = i + 1; j < 4; j++) {
        md.append("| ").append(LEVE.get(i)).append(" x ").append(LEVE.get(j));
        for (final int[] cell :
            List.of(new int[] {0, 0}, new int[] {0, 1}, new int[] {1, 0}, new int[] {1, 1})) {
          final List<JsonObject> l = new ArrayList<>();
          for (final String c : combos) {
            if (c.charAt(i) - '0' == cell[0] && c.charAt(j) - '0' == cell[1]) {
              l.addAll(both(by, c));
            }
          }
          md.append(" | ").append(frac(l, ConfrontoP12::axisWin));
        }
        md.append(" |\n");
      }
    }
    // balance and lever measures per combination
    md.append(
"""

#### Bilancio e misure delle leve per combinazione (H e F insieme, medie per partita)

PU per turno italiano: difesa di Roma (acquisti di capitale_minima) / trasporti / navi / terra / avanzati. Turni fuori tetto = turni con spesa per il mare sopra il 30% + un trasporto (deve essere 0). Sud Russia = regione P12 (Bessarabia, Ucraina e oltre); tra parentesi la regione di R4.

| Comb. | PU Roma | PU trasporti | PU navi | PU terra | PU avanzati | Turni fuori tetto | Unita' a est (somma registro) | Italiani sud Russia R6 | Partite con >= 3 a R6 | Portate in Africa entro R8 | Round Egitto Asse R2-R8 | Italiani in Italia R6 / R12 | Garnigione Roma richiesta / presente (media turni) | Round Gibilterra Asse R1-R10 | Partite con Gibilterra presa | Tenuta > 2 round |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final String c : combos) {
      final List<JsonObject> s = both(by, c);
      if (s.isEmpty()) {
        continue;
      }
      md.append("| ")
          .append(c)
          .append(" | ")
          .append(f(mean(s, b -> mediaBilancio(b, "pu_difesa_roma"))))
          .append(" | ")
          .append(f(mean(s, b -> mediaBilancio(b, "pu_trasporti"))))
          .append(" | ")
          .append(f(mean(s, b -> mediaBilancio(b, "pu_navi"))))
          .append(" | ")
          .append(f(mean(s, b -> mediaBilancio(b, "pu_terra"))))
          .append(" | ")
          .append(f(mean(s, b -> mediaBilancio(b, "pu_avanzati"))))
          .append(" | ")
          .append(
              Math.round(
                  s.stream().mapToDouble(b -> b.get("turni_mare_fuori_tetto").getAsDouble()).sum()))
          .append(" | ")
          .append(f(mean(s, b -> sommaTurni(b, "unita_est", 99))))
          .append(" | ")
          .append(f(mean(s, b -> alRound(b, 6, "ita_terra.sud_russia"))))
          .append(" (")
          .append(f(mean(s, b -> alRound(b, 6, "italiani_sud_russia_r4"))))
          .append(") | ")
          .append(count(s, b -> alRound(b, 6, "ita_terra.sud_russia") >= 3))
          .append("/")
          .append(s.size())
          .append(" | ")
          .append(f(mean(s, b -> b.get("portate_africa_r8").getAsDouble())))
          .append(" | ")
          .append(f(mean(s, b -> roundCon(b, 2, 8, "egitto_asse"))))
          .append(" | ")
          .append(f(mean(s, b -> alRound(b, 6, "ita_terra.italia"))))
          .append(" / ")
          .append(f(mean(s, b -> alRound(b, 12, "ita_terra.italia"))))
          .append(" | ")
          .append(f(mean(s, b -> mediaTurni(b, "roma", "richiesta"))))
          .append(" / ")
          .append(f(mean(s, b -> mediaTurni(b, "roma", "presente"))))
          .append(" | ")
          .append(f(mean(s, b -> roundCon(b, 1, 10, "gibilterra_asse"))))
          .append(" | ")
          .append(count(s, b -> roundCon(b, 1, MAX_ROUND, "gibilterra_asse") > 0))
          .append(" | ")
          .append(count(s, b -> filaMax(b, "gibilterra_asse") > 2))
          .append(" |\n");
    }
    // diagnostics per round for the 0000 and 1111 of each opponent
    md.append(
"""

#### Italia per round (medie): unita' di terra Italia / Nord Africa / Balcani-MO / sud Russia / Gibilterra; trasporti vivi; navi; reddito + bonus; Egitto e Gibilterra dell'Asse (partite)

| Config | R | Italia | N. Africa | Balcani-MO | Sud Russia | Gibilterra | Trasporti | Persi | Navi | Reddito | Bonus | Egitto Asse | Gibilterra Asse | Ted. ovest | Ted. Italia |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final String code : by.keySet()) {
      if (!(code.endsWith("0000")
          || code.endsWith("1111")
          || code.length() == 5 && Integer.bitCount(Integer.parseInt(code.substring(1), 2)) == 1)) {
        continue;
      }
      final List<JsonObject> l = by.get(code);
      for (final int r : List.of(1, 4, 6, 8, 10, 12, 16)) {
        md.append("| ").append(code).append(" | ").append(r);
        for (final String k :
            List.of(
                "ita_terra.italia",
                "ita_terra.nord_africa",
                "ita_terra.balcani_mo",
                "ita_terra.sud_russia",
                "ita_terra.gibilterra",
                "ita_trasporti",
                "ita_trasporti_persi",
                "ita_navi",
                "ita_reddito",
                "ita_bonus")) {
          md.append(" | ").append(f(mean(l, b -> alRound(b, r, k))));
        }
        md.append(" | ")
            .append(count(l, b -> alRound(b, r, "egitto_asse") == 1))
            .append(" | ")
            .append(count(l, b -> alRound(b, r, "gibilterra_asse") == 1))
            .append(" | ")
            .append(f(mean(l, b -> alRound(b, r, "tedeschi_ovest"))))
            .append(" | ")
            .append(f(mean(l, b -> alRound(b, r, "tedeschi_italia"))))
            .append(" |\n");
      }
    }
    md.append(
"""

#### Round di caduta (mediana, partite) e USA nel Mediterraneo

| Config | Ucraina | Leningrado | Stalingrado | Mosca | Roma | Berlino | USA sbarcate nel Mediterraneo entro R8 |
|---|---|---|---|---|---|---|---|
""");
    for (final var e : by.entrySet()) {
      md.append("| ").append(e.getKey());
      for (final String c :
          List.of("Ucraina", "Leningrado", "Stalingrado", "Mosca", "Roma", "Berlino")) {
        final List<Double> rs = new ArrayList<>();
        e.getValue()
            .forEach(
                b -> {
                  final JsonObject cd = b.getAsJsonObject("cadute");
                  if (cd.has(c)) {
                    rs.add(cd.get(c).getAsDouble());
                  }
                });
        md.append(" | ")
            .append(rs.size())
            .append(rs.isEmpty() ? "" : " (R" + f0(ConfrontoR4.median(rs)) + ")");
      }
      md.append(" | ")
          .append(f(mean(e.getValue(), b -> b.get("usa_mediterraneo_r8").getAsDouble())))
          .append(" |\n");
    }
    // the Allies
    md.append(
"""

#### Alleati (vittorie alleate)

| Config | Valide | Alleati vince [IC 95%] | Roma cade | Berlino cade | Mosca cade | Norvegia alleata a R4 | Ponte concentrato: rinforzi per partita | Trasporti persi entro R8 USA / UK | Guerre a Neutral_True aperte da alleati |
|---|---|---|---|---|---|---|---|---|---|
""");
    for (final String code : List.of("H0000", "F0000", "a3", "h3", "h5")) {
      final List<JsonObject> l = by.getOrDefault(code, List.of());
      if (l.isEmpty()) {
        continue;
      }
      final int w = count(l, ConfrontoP12::alliesWin);
      md.append("| ")
          .append(code)
          .append(" | ")
          .append(l.size())
          .append(" | ")
          .append(w)
          .append("/")
          .append(l.size())
          .append(" ")
          .append(ConfrontoR4.wilsonText(w, l.size()))
          .append(" | ")
          .append(count(l, b -> b.getAsJsonObject("cadute").has("Roma")))
          .append(" | ")
          .append(count(l, b -> b.getAsJsonObject("cadute").has("Berlino")))
          .append(" | ")
          .append(count(l, b -> b.getAsJsonObject("cadute").has("Mosca")))
          .append(" | ")
          .append(count(l, b -> flag(b, "norvegia_alleata_r4")))
          .append(" | ")
          .append(f(mean(l, b -> b.get("ponte_rinforzi").getAsDouble())))
          .append(" | ")
          .append(f(mean(l, b -> b.get("usa_trasporti_persi_r8").getAsDouble())))
          .append(" / ")
          .append(f(mean(l, b -> b.get("uk_trasporti_persi_r8").getAsDouble())))
          .append(" | ")
          .append(count(l, b -> flag(b, "neutrali_guerra_alleati")))
          .append(" |\n");
    }
    md.append(
            "\nNeutrali veri nelle configurazioni con nucleo o Alleati finali (devono essere 0):"
                + " partite con guerra aperta da alleati ")
        .append(
            by.entrySet().stream()
                .filter(
                    e ->
                        e.getKey().startsWith("F")
                            || List.of("a3", "h3", "h5").contains(e.getKey()))
                .mapToLong(e -> count(e.getValue(), b -> flag(b, "neutrali_guerra_alleati")))
                .sum())
        .append(", attacchi alleati ")
        .append(
            Math.round(
                by.entrySet().stream()
                    .filter(
                        e ->
                            e.getKey().startsWith("F")
                                || List.of("a3", "h3", "h5").contains(e.getKey()))
                    .flatMap(e -> e.getValue().stream())
                    .mapToDouble(b -> b.get("neutrali_attacchi_alleati").getAsDouble())
                    .sum()))
        .append(".\n");
    md.append(
"""

| Confronto | Vittorie alleate | p |
|---|---|---|
""");
    for (final String[] cmp :
        List.of(
            new String[] {"h5", "h3", "Alleati finali contro nucleo (Asse Hard)"},
            new String[] {"F0000", "a3", "Alleati finali contro nucleo (Asse assedio)"},
            new String[] {"F0000", "H0000", "Alleati finali contro Hard (Asse assedio)"})) {
      final List<JsonObject> a = by.getOrDefault(cmp[0], List.of());
      final List<JsonObject> b = by.getOrDefault(cmp[1], List.of());
      md.append("| ")
          .append(cmp[0])
          .append(" vs ")
          .append(cmp[1])
          .append(" (")
          .append(cmp[2])
          .append(") | ");
      if (a.isEmpty() || b.isEmpty()) {
        md.append("- | - |\n");
        continue;
      }
      final int wa = count(a, ConfrontoP12::alliesWin);
      final int wb = count(b, ConfrontoP12::alliesWin);
      md.append(wa)
          .append("/")
          .append(a.size())
          .append(" vs ")
          .append(wb)
          .append("/")
          .append(b.size())
          .append(" | ")
          .append(ConfrontoR5.pText(ConfrontoR4.fisher(wa, a.size(), wb, b.size())))
          .append(" |\n");
    }
  }

  static double mediaTurni(final JsonObject b, final String part, final String k) {
    final JsonObject t = b.getAsJsonObject("turni");
    double sum = 0;
    int n = 0;
    for (final var e : t.entrySet()) {
      final JsonObject x = e.getValue().getAsJsonObject();
      if (x.has(part) && x.getAsJsonObject(part).has(k)) {
        sum += x.getAsJsonObject(part).get(k).getAsDouble();
        n++;
      }
    }
    return n == 0 ? Double.NaN : sum / n;
  }

  private static List<JsonObject> both(final Map<String, List<JsonObject>> by, final String combo) {
    final List<JsonObject> out = new ArrayList<>(by.getOrDefault("H" + combo, List.of()));
    out.addAll(by.getOrDefault("F" + combo, List.of()));
    return out;
  }

  private static double rate(final List<JsonObject> l, final Predicate<JsonObject> p) {
    return l.isEmpty() ? -1 : (double) count(l, p) / l.size();
  }

  private static String frac(final List<JsonObject> l, final Predicate<JsonObject> p) {
    return l.isEmpty() ? "-" : count(l, p) + "/" + l.size();
  }

  static int count(final List<JsonObject> l, final Predicate<JsonObject> p) {
    return (int) l.stream().filter(p).count();
  }

  static double mean(final List<JsonObject> l, final ToDoubleFunction<JsonObject> f) {
    return l.stream().mapToDouble(f).filter(d -> !Double.isNaN(d)).average().orElse(Double.NaN);
  }

  private static String f(final double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
  }

  private static String f2(final double v) {
    return Double.isNaN(v) || Double.isInfinite(v)
        ? "-"
        : String.format(Locale.ROOT, "%.2f", v).replace('.', ',');
  }

  private static String f0(final double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.0f", v);
  }
}
