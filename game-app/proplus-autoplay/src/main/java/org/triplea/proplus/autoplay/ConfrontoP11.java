package org.triplea.proplus.autoplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P11: the Allies' recommended combination against the Axis of Hard. The section "P11" of
 * {@code riepilogo-generale.md}: the games with the Axis played by Hard, by the Allies' switches
 * (h0 Hard, h1 niente_neutrali, h2 usa_proporzione + sbarco_concentrato, h3 all three), with the
 * three comparisons declared before the run. Read only, from the blocks {@code p7} and {@code p9}
 * and the fallen capitals of each {@code riepilogo.json}.
 */
final class ConfrontoP11 {
  private ConfrontoP11() {}

  /** The configurations by their Allied switches (code → switches). */
  static final Map<String, Set<String>> CODICI = new LinkedHashMap<>();

  static {
    CODICI.put("h0", Set.of());
    CODICI.put("h3", Set.of("usa_proporzione", "sbarco_concentrato", "niente_neutrali"));
    CODICI.put("h1", Set.of("niente_neutrali"));
    CODICI.put("h2", Set.of("usa_proporzione", "sbarco_concentrato"));
  }

  /** The seeds of h1 and h2 (their comparisons with h0 use only these seeds of h0). */
  static final int ULTIMO_SEME_CORTO = 1980;

  /** The capitals followed: territory → name in the table. */
  static final Map<String, String> CAPITALI = new LinkedHashMap<>();

  static {
    CAPITALI.put("Southern Italy", "Roma");
    CAPITALI.put("Germany", "Berlino");
    CAPITALI.put("Russia", "Mosca");
    CAPITALI.put("United Kingdom", "Londra");
  }

  /** The code of a game with the Axis of Hard, else null. */
  @Nullable
  static String codice(final JsonObject json) {
    if (!axisHard(json)) {
      return null;
    }
    final Set<String> sw = ConfrontoP6.interruttori(json);
    for (final var e : CODICI.entrySet()) {
      if (e.getValue().equals(sw)) {
        return e.getKey();
      }
    }
    return null;
  }

  private static boolean axisHard(final JsonObject json) {
    if (!json.has("parametri")) {
      return false;
    }
    final JsonObject p = json.getAsJsonObject("parametri");
    return p.has("tipi")
        && p.getAsJsonObject("tipi").has("Axis")
        && AutoplayOptions.HARD.equals(p.getAsJsonObject("tipi").get("Axis").getAsString())
        && p.has("fino_a_vittoria")
        && p.get("fino_a_vittoria").getAsBoolean();
  }

  static boolean applicabile(final List<Game> games) {
    return games.stream().anyMatch(g -> g.json() != null && codice(g.json()) != null);
  }

  static boolean valida(final JsonObject json) {
    final String stato = json.has("stato") ? json.get("stato").getAsString() : "";
    return stato.startsWith("completata") || stato.startsWith("stallo");
  }

  static int seme(final JsonObject json) {
    final JsonObject p = json.getAsJsonObject("parametri");
    return p.has("seme") ? p.get("seme").getAsInt() : 0;
  }

  /** The first round each followed capital fell ("caduta" in {@code capitali}). */
  static Map<String, Integer> cadute(final JsonObject json) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    if (json.has("capitali") && json.get("capitali").isJsonArray()) {
      for (final JsonElement e : json.getAsJsonArray("capitali")) {
        final JsonObject c = e.getAsJsonObject();
        final String name = CAPITALI.get(c.get("territorio").getAsString());
        if (name != null && "caduta".equals(c.get("evento").getAsString())) {
          out.putIfAbsent(name, c.get("round").getAsInt());
        }
      }
    }
    return out;
  }

  static void write(final StringBuilder md, final List<Game> games) {
    final Map<String, List<JsonObject>> byCode = new LinkedHashMap<>();
    final Map<String, int[]> invalid = new LinkedHashMap<>();
    CODICI.keySet().forEach(k -> byCode.put(k, new ArrayList<>()));
    for (final Game g : games) {
      if (g.json() == null) {
        continue;
      }
      final String code = codice(g.json());
      if (code == null) {
        continue;
      }
      if (!valida(g.json())) {
        invalid.computeIfAbsent(code, k -> new int[1])[0]++;
        continue;
      }
      byCode.get(code).add(g.json());
    }
    byCode.values().removeIf(List::isEmpty);
    if (byCode.isEmpty()) {
      return;
    }
    md.append(
"""


### P11

""");
    md.append(
        "Step P11: Asse = Hard puro in tutte. Configurazioni per interruttori degli Alleati: h0"
            + " Hard; h3 consigliati (usa_proporzione + sbarco_concentrato + niente_neutrali); h1"
            + " solo niente_neutrali; h2 solo usa_proporzione + sbarco_concentrato. Valide ="
            + " completate o in stallo; errori contati a parte. Round di fine = round giocati."
            + " Cadute: partite (round mediano). Sbarcate = unita' USA sbarcate su territori non"
            + " alleati a inizio turno USA (blocco `p9`, media). Trasporti persi entro R8 (blocco"
            + " `p7`, media). p > 0,05 non e' una conclusione.\n\n");
    md.append(
        "| Config | Valide | Errori | Stalli | Alleati vince [IC 95%] | Roma cade | Berlino cade |"
            + " Mosca cade | Londra cade | Round di fine (mediana) | USA sbarcate R6 / R8 / R10 |"
            + " Trasporti persi R8 USA / UK |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> l = e.getValue();
      final int n = l.size();
      final int allies = ConfrontoR4.wins(l, "Allies");
      final int axis = ConfrontoR4.wins(l, "Axis");
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(n)
          .append(" | ")
          .append(invalid.getOrDefault(e.getKey(), new int[1])[0])
          .append(" | ")
          .append(n - allies - axis)
          .append(" | ")
          .append(allies)
          .append("/")
          .append(n)
          .append(" ")
          .append(ConfrontoR4.wilsonText(allies, n));
      for (final String cap : CAPITALI.values()) {
        md.append(" | ").append(caduteText(l, cap));
      }
      final List<Double> ends = new ArrayList<>();
      l.forEach(j -> ends.add(j.has("round_giocati") ? j.get("round_giocati").getAsDouble() : 0));
      md.append(" | ")
          .append(f0(ConfrontoR4.median(ends)))
          .append(" | ")
          .append(f(mean(l, j -> landed(j, "6"))))
          .append(" / ")
          .append(f(mean(l, j -> landed(j, "8"))))
          .append(" / ")
          .append(f(mean(l, j -> landed(j, "10"))))
          .append(" | ")
          .append(f(mean(l, j -> p7(j, "usa_trasporti_persi_r8"))))
          .append(" / ")
          .append(f(mean(l, j -> p7(j, "uk_trasporti_persi_r8"))))
          .append(" |\n");
    }
    md.append(
"""

Neutrali veri: partite con guerra a Neutral_True aperta da un alleato; attacchi alleati a territori Neutral_True (somma). h1 e h3 devono avere 0 e 0.

| Config | Guerra aperta da alleati | Attacchi alleati |
|---|---|---|
""");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> l = e.getValue();
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(count(l, j -> neutrals(j, "aperta_da_alleati") > 0))
          .append("/")
          .append(l.size())
          .append(" | ")
          .append(
              Math.round(
                  l.stream().mapToDouble(j -> neutrals(j, "attacchi_alleati_neutrali")).sum()))
          .append(" |\n");
    }
    md.append(
"""

I tre confronti dichiarati prima (Fisher esatto a due code sulle vittorie alleate). h1 e h2 contro h0 sugli stessi semi (fino a 1980).

| Confronto | Vittorie alleate | p |
|---|---|---|
""");
    confronto(md, byCode, "h3", "h0", "consigliati contro Hard (principale)", Integer.MAX_VALUE);
    confronto(md, byCode, "h1", "h0", "solo niente_neutrali contro Hard", ULTIMO_SEME_CORTO);
    confronto(md, byCode, "h2", "h0", "solo proporzione + sbarco contro Hard", ULTIMO_SEME_CORTO);
    md.append(
"""

Fuori dai dichiarati (stessi semi fino a 1980):

| Confronto | Vittorie alleate | p |
|---|---|---|
""");
    confronto(md, byCode, "h3", "h0", "consigliati contro Hard", ULTIMO_SEME_CORTO);
  }

  private static void confronto(
      final StringBuilder md,
      final Map<String, List<JsonObject>> byCode,
      final String a,
      final String b,
      final String label,
      final int lastSeed) {
    final List<JsonObject> la = upTo(byCode.get(a), lastSeed);
    final List<JsonObject> lb = upTo(byCode.get(b), lastSeed);
    md.append("| ").append(a).append(" vs ").append(b).append(" (").append(label).append(") | ");
    if (la.isEmpty() || lb.isEmpty()) {
      md.append("- | - |\n");
      return;
    }
    final int wa = ConfrontoR4.wins(la, "Allies");
    final int wb = ConfrontoR4.wins(lb, "Allies");
    md.append(wa)
        .append("/")
        .append(la.size())
        .append(" vs ")
        .append(wb)
        .append("/")
        .append(lb.size())
        .append(" | ")
        .append(ConfrontoR5.pText(ConfrontoR4.fisher(wa, la.size(), wb, lb.size())))
        .append(" |\n");
  }

  private static List<JsonObject> upTo(@Nullable final List<JsonObject> l, final int lastSeed) {
    return l == null ? List.of() : l.stream().filter(j -> seme(j) <= lastSeed).toList();
  }

  private static String caduteText(final List<JsonObject> l, final String cap) {
    final List<Double> rounds = new ArrayList<>();
    for (final JsonObject j : l) {
      final Integer r = cadute(j).get(cap);
      if (r != null) {
        rounds.add((double) r);
      }
    }
    return rounds.size() + (rounds.isEmpty() ? "" : " (R" + f0(ConfrontoR4.median(rounds)) + ")");
  }

  private static double landed(final JsonObject j, final String round) {
    final JsonObject p9 = sub(j, "p9");
    if (p9 == null || !p9.has("usa_sbarcate")) {
      return Double.NaN;
    }
    final JsonObject c = p9.getAsJsonObject("usa_sbarcate");
    return c.has(round) ? c.get(round).getAsDouble() : Double.NaN;
  }

  private static double p7(final JsonObject j, final String key) {
    final JsonObject p7 = sub(j, "p7");
    return p7 != null && p7.has(key) ? p7.get(key).getAsDouble() : Double.NaN;
  }

  private static double neutrals(final JsonObject j, final String key) {
    final JsonObject p7 = sub(j, "p7");
    if (p7 == null || !p7.has("f2_neutrali")) {
      return 0;
    }
    final JsonObject f2 = p7.getAsJsonObject("f2_neutrali");
    if (!f2.has(key)) {
      return 0;
    }
    final JsonElement v = f2.get(key);
    return v.getAsJsonPrimitive().isBoolean() ? (v.getAsBoolean() ? 1 : 0) : v.getAsDouble();
  }

  @Nullable
  private static JsonObject sub(final JsonObject j, final String key) {
    return j.has(key) && j.get(key).isJsonObject() && !j.getAsJsonObject(key).has("errore")
        ? j.getAsJsonObject(key)
        : null;
  }

  private static double mean(final List<JsonObject> l, final ToDoubleFunction<JsonObject> f) {
    return l.stream().mapToDouble(f).filter(d -> !Double.isNaN(d)).average().orElse(Double.NaN);
  }

  private static int count(final List<JsonObject> l, final Predicate<JsonObject> p) {
    return (int) l.stream().filter(p).count();
  }

  private static String f(final double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
  }

  private static String f0(final double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.0f", v);
  }
}
