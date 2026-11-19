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
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P7: the block {@code p7} of each game's {@code riepilogo.json} (monitor events {@code
 * p7_eventi}, snapshots {@code round[].p7}, the wars, the block {@code p6} and the register rows
 * {@code niente_neutrali}, {@code ponte_uk}, {@code ponte_pronti}) and the section "P7" of {@code
 * riepilogo-generale.md}: the nine configurations, the seven comparisons declared before the run,
 * the tables F1-F4. Read only, every configuration.
 */
final class ConfrontoP7 {
  private ConfrontoP7() {}

  static final String USA = "Americans";
  static final String UK = "British";
  static final String NEUTRALE = "Neutral_True";
  static final int FORTE = 6;
  static final List<Integer> ROUND_SBARCATE = List.of(6, 8, 10);
  static final int ROUND_PERDITE = 8;
  static final int ROUND_F4 = 12;

  /** The configurations of the run by their Allied switches (code → switches). */
  static final Map<String, Set<String>> CODICI = new LinkedHashMap<>();

  static {
    final Set<String> s2 = Set.of("usa_proporzione", "sbarco_concentrato");
    CODICI.put("e2", Set.of());
    CODICI.put("s1", Set.of("usa_proporzione"));
    CODICI.put("s2", s2);
    CODICI.put("s5", plus(s2, "niente_neutrali"));
    CODICI.put("c1", plus(s2, "ponte_uk"));
    CODICI.put("c2", plus(s2, "ponte_uk", "ponte_pronti"));
    CODICI.put("c3", plus(s2, "niente_neutrali", "ponte_uk"));
    CODICI.put("c4", plus(s2, "niente_neutrali", "ponte_uk", "ponte_pronti"));
    CODICI.put(
        "c5",
        plus(
            s2,
            "niente_neutrali",
            "ponte_uk",
            "ponte_pronti",
            "norvegia_presto",
            "norvegia_esposta"));
  }

  /** The seven comparisons declared before the run. */
  static final List<String[]> CONFRONTI =
      List.of(
          new String[] {"s2", "e2", "conferma"},
          new String[] {"s2", "s1", "sbarco concentrato"},
          new String[] {"s5", "s2", "neutrali"},
          new String[] {"c1", "s2", "ponte"},
          new String[] {"c2", "c1", "truppe pronte"},
          new String[] {"c4", "s2", "tutto insieme"},
          new String[] {"c5", "c4", "Norvegia"});

  private static Set<String> plus(final Set<String> base, final String... more) {
    final Set<String> out = new TreeSet<>(base);
    out.addAll(List.of(more));
    return Set.copyOf(out);
  }

  // ================================================================ block of one game

  static JsonObject blocco(final JsonObject json) {
    final JsonObject o = new JsonObject();
    final Set<String> sw = ConfrontoP6.interruttori(json);
    o.addProperty("configurazione", codice(sw));
    final List<String> allies = alleati(json);
    final JsonObject ev =
        json.has("p7_eventi") ? json.getAsJsonObject("p7_eventi") : new JsonObject();
    final List<JsonObject> landings = new ArrayList<>();
    if (ev.has("sbarchi")) {
      ev.getAsJsonArray("sbarchi").forEach(e -> landings.add(e.getAsJsonObject()));
    }
    // US units landed by R6 / R8 / R10: in all and on Axis land
    final JsonObject cum = new JsonObject();
    for (final int r : ROUND_SBARCATE) {
      int all = 0;
      int axis = 0;
      for (final JsonObject l : landings) {
        if (USA.equals(s(l, "nazione")) && n(l, "round") <= r) {
          all += n(l, "unita");
          if ("Asse".equals(s(l, "categoria")) || "nemico".equals(s(l, "categoria"))) {
            axis += n(l, "unita");
          }
        }
      }
      final JsonObject x = new JsonObject();
      x.addProperty("totale", all);
      x.addProperty("asse", axis);
      cum.add(String.valueOf(r), x);
    }
    o.add("usa_sbarcate", cum);
    // F1 and F3: the US landings of at least 6 units
    final JsonArray f1 = new JsonArray();
    int strong = 0;
    int taken = 0;
    int held = 0;
    int heldKnown = 0;
    int reach2 = 0;
    int reach4 = 0;
    int reinf2 = 0;
    int reinf4 = 0;
    int heldWith = 0;
    int takenWith = 0;
    int heldWithout = 0;
    int takenWithout = 0;
    for (final JsonObject l : landings) {
      if (!USA.equals(s(l, "nazione")) || n(l, "unita") < FORTE) {
        continue;
      }
      strong++;
      final JsonObject x = new JsonObject();
      for (final String k :
          List.of(
              "round",
              "territorio",
              "proprietario_prima",
              "categoria",
              "unita",
              "presa",
              "alleato_round_dopo",
              "trasporti_usa_persi",
              "navi_alleate_zona",
              "uk_trasporti_raggiungono",
              "uk_unita_caricabili",
              "uk_sbarcate")) {
        if (l.has(k)) {
          x.add(k, l.get(k));
        }
      }
      f1.add(x);
      final boolean p = flag(l, "presa");
      final boolean reinforced = n(l, "uk_sbarcate") > 0;
      if (p) {
        taken++;
        if (l.has("alleato_round_dopo")) {
          heldKnown++;
          final boolean h = flag(l, "alleato_round_dopo");
          if (h) {
            held++;
          }
          if (reinforced) {
            takenWith++;
            heldWith += h ? 1 : 0;
          } else {
            takenWithout++;
            heldWithout += h ? 1 : 0;
          }
        }
      }
      if (n(l, "uk_unita_caricabili") >= 2) {
        reach2++;
        reinf2 += reinforced ? 1 : 0;
      }
      if (n(l, "uk_unita_caricabili") >= 4) {
        reach4++;
        reinf4 += reinforced ? 1 : 0;
      }
    }
    o.add("f1_sbarchi_usa_6", f1);
    final JsonObject t = new JsonObject();
    t.addProperty("sbarchi", strong);
    t.addProperty("presi", taken);
    t.addProperty("presi_con_esito_noto", heldKnown);
    t.addProperty("tenuti_round_dopo", held);
    t.addProperty("raggiungibili_2", reach2);
    t.addProperty("rinforzati_2", reinf2);
    t.addProperty("raggiungibili_4", reach4);
    t.addProperty("rinforzati_4", reinf4);
    t.addProperty("presi_con_rinforzo", takenWith);
    t.addProperty("tenuti_con_rinforzo", heldWith);
    t.addProperty("presi_senza_rinforzo", takenWithout);
    t.addProperty("tenuti_senza_rinforzo", heldWithout);
    o.add("teste_di_ponte", t);
    // F2: the true neutrals
    final JsonObject f2 = new JsonObject();
    final JsonArray wars = new JsonArray();
    int alliedWars = 0;
    int firstAllied = -1;
    String opener = "";
    if (json.has("dichiarazioni_guerra") && json.get("dichiarazioni_guerra").isJsonArray()) {
      for (final JsonElement e : json.getAsJsonArray("dichiarazioni_guerra")) {
        final JsonObject w = e.getAsJsonObject();
        final String between = s(w, "tra");
        if (between == null || !between.contains(NEUTRALE)) {
          continue;
        }
        wars.add(w.deepCopy());
        final String other = between.replace(NEUTRALE, "").replace(" - ", "").trim();
        if (allies.contains(other) && allies.contains(s(w, "nazione_di_turno"))) {
          alliedWars++;
          if (firstAllied < 0) {
            firstAllied = n(w, "round");
            opener = s(w, "nazione_di_turno");
          }
        }
      }
    }
    f2.add("guerre", wars);
    f2.addProperty("guerre_alleati_neutrali", alliedWars);
    f2.addProperty("aperta_da_alleati", firstAllied > 0);
    f2.addProperty("round_apertura_alleati", firstAllied);
    f2.addProperty("nazione_apertura_alleati", opener);
    final JsonArray attacks =
        ev.has("attacchi_neutrali_alleati")
            ? ev.getAsJsonArray("attacchi_neutrali_alleati")
            : new JsonArray();
    f2.addProperty("attacchi_alleati_neutrali", attacks.size());
    int usOnNeutral = 0;
    int ukOnNeutral = 0;
    for (final JsonObject l : landings) {
      if ("Neutral_True".equals(s(l, "categoria"))) {
        if (USA.equals(s(l, "nazione"))) {
          usOnNeutral += n(l, "unita");
        } else if (UK.equals(s(l, "nazione"))) {
          ukOnNeutral += n(l, "unita");
        }
      }
    }
    f2.addProperty("usa_sbarcate_neutrali", usOnNeutral);
    f2.addProperty("uk_sbarcate_neutrali", ukOnNeutral);
    final Map<Integer, JsonObject> snaps = snaps(json);
    final JsonObject ex = new JsonObject();
    for (final int r : List.of(6, 10)) {
      final JsonObject p7 = sub(snaps.get(r), "p7");
      if (p7 != null) {
        ex.addProperty(String.valueOf(r), n(p7, "ex_neutrali_asse"));
      }
    }
    if (!snaps.isEmpty()) {
      final JsonObject last =
          sub(snaps.get(((TreeMap<Integer, JsonObject>) snaps).lastKey()), "p7");
      if (last != null) {
        ex.addProperty("fine", n(last, "ex_neutrali_asse"));
      }
    }
    f2.add("ex_neutrali_asse", ex);
    f2.addProperty("neutrali_catturate_asse", n(ev, "neutrali_catturate_asse"));
    o.add("f2_neutrali", f2);
    // F4: the United Kingdom per round
    final JsonObject f4 = new JsonObject();
    int ukLostR8 = 0;
    for (final var e : snaps.entrySet()) {
      final JsonObject p7 = sub(e.getValue(), "p7");
      if (p7 == null || !p7.has("uk")) {
        continue;
      }
      final JsonObject u = p7.getAsJsonObject("uk");
      f4.add(String.valueOf(e.getKey()), u.deepCopy());
      if (e.getKey() <= ROUND_PERDITE) {
        ukLostR8 += n(u, "trasporti_persi");
      }
    }
    o.add("f4_regno_unito", f4);
    o.addProperty("uk_trasporti_persi_r8", ukLostR8);
    final JsonObject p6 = json.has("p6") ? json.getAsJsonObject("p6") : new JsonObject();
    o.addProperty("usa_trasporti_persi_r8", n(p6, "usa_trasporti_persi_r8"));
    o.add("cadute", p6.has("cadute") ? p6.get("cadute").deepCopy() : new JsonObject());
    o.addProperty("mosca_oltre_r14", flag(p6, "mosca_oltre_r14"));
    o.add("registro", registro(json));
    return o;
  }

  /** The register's counts of the three levers (0 without them). */
  static JsonObject registro(final JsonObject json) {
    final Map<String, Integer> c = new TreeMap<>();
    for (final String k :
        List.of(
            "GUERRA_NEUTRALI_ESCLUSA",
            "BERSAGLIO_NEUTRALE_ESCLUSO",
            "PONTE_PESO_ATTACCO",
            "PONTE_ESITO",
            "PONTE_RINFORZO",
            "PONTE_NON_RAGGIUNGIBILE",
            "PONTE_ESPOSTO",
            "PRONTI_INDICAZIONE",
            "PRONTI_OLTRE_CAPACITA",
            "ponte_unita_sbarcate")) {
      c.put(k, 0);
    }
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if (!"decisione".equals(s(d, "riga"))) {
        continue;
      }
      final String tipo = s(d, "tipo");
      if (!"niente_neutrali".equals(tipo)
          && !"ponte_uk".equals(tipo)
          && !"ponte_pronti".equals(tipo)) {
        continue;
      }
      final String code = ConfrontoR5.codice(d);
      c.merge(code, 1, Integer::sum);
      if ("PONTE_RINFORZO".equals(code) && d.has("parametri")) {
        c.merge(
            "ponte_unita_sbarcate",
            n(d.getAsJsonObject("parametri"), "unita_sbarcate"),
            Integer::sum);
      }
    }
    final JsonObject o = new JsonObject();
    c.forEach(o::addProperty);
    return o;
  }

  static String codice(final Set<String> switches) {
    for (final var e : CODICI.entrySet()) {
      if (e.getValue().equals(switches)) {
        return e.getKey();
      }
    }
    return switches.isEmpty() ? "e2" : String.join("+", switches);
  }

  // ======================================================================= the section

  static boolean applicabile(final List<Game> games) {
    return games.stream().anyMatch(g -> g.json() != null && g.json().has("p7"));
  }

  static void write(final StringBuilder md, final List<Game> games) {
    final Map<String, List<JsonObject>> byCode = new LinkedHashMap<>();
    CODICI.keySet().forEach(k -> byCode.put(k, new ArrayList<>()));
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("p7") || g.json().getAsJsonObject("p7").has("errore")) {
        continue;
      }
      final String code = g.json().getAsJsonObject("p7").get("configurazione").getAsString();
      byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(g.json());
    }
    byCode.values().removeIf(List::isEmpty);
    if (byCode.isEmpty()) {
      return;
    }
    md.append(
"""


### P7

""");
    md.append(
        "Step P7 (niente_neutrali, ponte_uk, ponte_pronti). Configurazioni per interruttori degli"
            + " Alleati: e2 Hard; s1 usa_proporzione; s2 = Base (usa_proporzione +"
            + " sbarco_concentrato); s5 Base + niente_neutrali; c1 Base + ponte_uk; c2 c1 +"
            + " ponte_pronti; c3 Base + niente_neutrali + ponte_uk; c4 c3 + ponte_pronti; c5 c4 +"
            + " norvegia_presto + norvegia_esposta. Dal blocco `p7` dei `riepilogo.json` (sola"
            + " lettura). p > 0,05 non e' una conclusione.\n\n");
    // the numbers per configuration
    md.append(
        "| Config | Partite | Alleati vince [IC 95%] | Stalli | Mosca oltre R14 | Roma cade |"
            + " Berlino cade | USA sbarcate R6 / R8 / R10 (tot.) | idem su Asse | Trasporti persi"
            + " entro R8 USA / UK (media) | Teste di ponte USA >= 6 tenute un round dopo |"
            + " Sbarchi USA >= 6 raggiungibili dal UK con >= 2: rinforzati |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> l = e.getValue();
      final List<JsonObject> b = blocks(l);
      final int n = l.size();
      final int allies = ConfrontoR4.wins(l, "Allies");
      final int axis = ConfrontoR4.wins(l, "Axis");
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(n)
          .append(" | ")
          .append(allies)
          .append("/")
          .append(n)
          .append(" ")
          .append(ConfrontoR4.wilsonText(allies, n))
          .append(" | ")
          .append(n - allies - axis)
          .append(" | ")
          .append(count(b, x -> flag(x, "mosca_oltre_r14")))
          .append(" | ")
          .append(count(b, x -> x.getAsJsonObject("cadute").has("roma")))
          .append(" | ")
          .append(count(b, x -> x.getAsJsonObject("cadute").has("berlino")))
          .append(" | ")
          .append(cum(b, "totale"))
          .append(" | ")
          .append(cum(b, "asse"))
          .append(" | ")
          .append(f(mean(b, x -> (double) n(x, "usa_trasporti_persi_r8"))))
          .append(" / ")
          .append(f(mean(b, x -> (double) n(x, "uk_trasporti_persi_r8"))))
          .append(" | ")
          .append(sumT(b, "tenuti_round_dopo"))
          .append(" su ")
          .append(sumT(b, "presi_con_esito_noto"))
          .append(" presi (")
          .append(sumT(b, "sbarchi"))
          .append(" sbarchi) | ")
          .append(sumT(b, "rinforzati_2"))
          .append(" su ")
          .append(sumT(b, "raggiungibili_2"))
          .append(" ")
          .append(pct(sumT(b, "rinforzati_2"), sumT(b, "raggiungibili_2")))
          .append(" |\n");
    }
    // the seven declared comparisons
    md.append(
"""

I sette confronti dichiarati prima (Fisher esatto a due code; differenza di vittorie alleate e di partite con Mosca non caduta entro R14).

| Confronto | Vittorie alleate | p | Mosca oltre R14 | p |
|---|---|---|---|---|
""");
    for (final String[] c : CONFRONTI) {
      final List<JsonObject> a = byCode.get(c[0]);
      final List<JsonObject> b = byCode.get(c[1]);
      if (a == null || b == null) {
        md.append("| ").append(c[0]).append(" vs ").append(c[1]).append(" | - | - | - | - |\n");
        continue;
      }
      final int wa = ConfrontoR4.wins(a, "Allies");
      final int wb = ConfrontoR4.wins(b, "Allies");
      final int ma = count(blocks(a), x -> flag(x, "mosca_oltre_r14"));
      final int mb = count(blocks(b), x -> flag(x, "mosca_oltre_r14"));
      md.append("| ")
          .append(c[0])
          .append(" vs ")
          .append(c[1])
          .append(" (")
          .append(c[2])
          .append(") | ")
          .append(wa)
          .append("/")
          .append(a.size())
          .append(" vs ")
          .append(wb)
          .append("/")
          .append(b.size())
          .append(" | ")
          .append(ConfrontoR5.pText(ConfrontoR4.fisher(wa, a.size(), wb, b.size())))
          .append(" | ")
          .append(ma)
          .append(" vs ")
          .append(mb)
          .append(" | ")
          .append(ConfrontoR5.pText(ConfrontoR4.fisher(ma, a.size(), mb, b.size())))
          .append(" |\n");
    }
    // the register: the levers acted?
    md.append(
"""

Registro delle leve (somme): guerre ai neutrali escluse / bersagli neutrali esclusi; ponte: peso d'attacco, esiti col territorio nemico, rinforzi (unita'), non raggiungibile, esposti; pronti: indicazioni / oltre la capacita'.

| Config | Guerre escluse | Bersagli esclusi | Peso | Esiti nemico | Rinforzi (unita') | Non raggiungibile | Esposti | Pronti indicazioni | Oltre capacita' |
|---|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> b = blocks(e.getValue());
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(reg(b, "GUERRA_NEUTRALI_ESCLUSA"))
          .append(" | ")
          .append(reg(b, "BERSAGLIO_NEUTRALE_ESCLUSO"))
          .append(" | ")
          .append(reg(b, "PONTE_PESO_ATTACCO"))
          .append(" | ")
          .append(reg(b, "PONTE_ESITO"))
          .append(" | ")
          .append(reg(b, "PONTE_RINFORZO"))
          .append(" (")
          .append(reg(b, "ponte_unita_sbarcate"))
          .append(") | ")
          .append(reg(b, "PONTE_NON_RAGGIUNGIBILE"))
          .append(" | ")
          .append(reg(b, "PONTE_ESPOSTO"))
          .append(" | ")
          .append(reg(b, "PRONTI_INDICAZIONE"))
          .append(" | ")
          .append(reg(b, "PRONTI_OLTRE_CAPACITA"))
          .append(" |\n");
    }
    f1(md, byCode);
    f2(md, byCode);
    f3(md, byCode);
    f4(md, byCode);
  }

  private static void f1(final StringBuilder md, final Map<String, List<JsonObject>> byCode) {
    md.append(
"""

F1. Sbarchi USA di almeno 6 unita' per territorio (somme sulle partite): proprietario prima Asse / Neutral_True / alleato, presi, alleati alla fine del round dopo (su presi con esito noto), trasporti USA della zona persi prima del turno USA dopo, navi da guerra alleate nella zona a fine turno USA (media). Prime 8 righe per configurazione.

| Config | Territorio | Sbarchi | Unita' | Asse / Neutr. / alleato | Presi | Tenuti | Trasporti persi | Navi alleate (media) |
|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byCode.entrySet()) {
      final Map<String, int[]> by = new TreeMap<>();
      final Map<String, Double> ships = new TreeMap<>();
      for (final JsonObject b : blocks(e.getValue())) {
        for (final JsonElement x : b.getAsJsonArray("f1_sbarchi_usa_6")) {
          final JsonObject l = x.getAsJsonObject();
          final int[] a = by.computeIfAbsent(s(l, "territorio"), k -> new int[9]);
          a[0]++;
          a[1] += n(l, "unita");
          final String cat = s(l, "categoria");
          a["Asse".equals(cat) || "nemico".equals(cat) ? 2 : "Neutral_True".equals(cat) ? 3 : 4]++;
          if (flag(l, "presa")) {
            a[5]++;
            if (l.has("alleato_round_dopo")) {
              a[8]++;
              a[6] += flag(l, "alleato_round_dopo") ? 1 : 0;
            }
          }
          a[7] += n(l, "trasporti_usa_persi");
          ships.merge(s(l, "territorio"), (double) n(l, "navi_alleate_zona"), Double::sum);
        }
      }
      by.entrySet().stream()
          .sorted((x, y) -> Integer.compare(y.getValue()[0], x.getValue()[0]))
          .limit(8)
          .forEach(
              x -> {
                final int[] a = x.getValue();
                md.append("| ")
                    .append(e.getKey())
                    .append(" | ")
                    .append(x.getKey())
                    .append(" | ")
                    .append(a[0])
                    .append(" | ")
                    .append(a[1])
                    .append(" | ")
                    .append(a[2])
                    .append(" / ")
                    .append(a[3])
                    .append(" / ")
                    .append(a[4])
                    .append(" | ")
                    .append(a[5])
                    .append(" | ")
                    .append(a[6])
                    .append(" su ")
                    .append(a[8])
                    .append(" | ")
                    .append(a[7])
                    .append(" | ")
                    .append(f(ships.get(x.getKey()) / a[0]))
                    .append(" |\n");
              });
    }
  }

  private static void f2(final StringBuilder md, final Map<String, List<JsonObject>> byCode) {
    md.append(
"""

F2. Neutrali veri: partite con guerra a Neutral_True aperta da un alleato (round mediano, chi), attacchi alleati a territori Neutral_True (somma), unita' USA / UK sbarcate su territori Neutral_True (media), territori ex neutrali dell'Asse a R6 / R10 / fine (media), unita' neutrali catturate dall'Asse (media).

| Config | Guerra aperta da alleati | Round mediano | Chi | Attacchi alleati | USA / UK su neutrali | Ex neutrali Asse R6 / R10 / fine | Catturate |
|---|---|---|---|---|---|---|---|
""");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> b = blocks(e.getValue());
      final List<Integer> rounds = new ArrayList<>();
      final Map<String, Integer> who = new TreeMap<>();
      for (final JsonObject x : b) {
        final JsonObject f2 = x.getAsJsonObject("f2_neutrali");
        if (flag(f2, "aperta_da_alleati")) {
          rounds.add(n(f2, "round_apertura_alleati"));
          who.merge(s(f2, "nazione_apertura_alleati"), 1, Integer::sum);
        }
      }
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(rounds.size())
          .append("/")
          .append(b.size())
          .append(" | ")
          .append(rounds.isEmpty() ? "-" : String.valueOf(median(rounds)))
          .append(" | ")
          .append(who.isEmpty() ? "-" : who.toString())
          .append(" | ")
          .append(
              sum(
                  b,
                  x -> (double) n(x.getAsJsonObject("f2_neutrali"), "attacchi_alleati_neutrali")))
          .append(" | ")
          .append(
              f(
                  mean(
                      b,
                      x -> (double) n(x.getAsJsonObject("f2_neutrali"), "usa_sbarcate_neutrali"))))
          .append(" / ")
          .append(
              f(mean(b, x -> (double) n(x.getAsJsonObject("f2_neutrali"), "uk_sbarcate_neutrali"))))
          .append(" | ")
          .append(f(mean(b, x -> exNeutral(x, "6"))))
          .append(" / ")
          .append(f(mean(b, x -> exNeutral(x, "10"))))
          .append(" / ")
          .append(f(mean(b, x -> exNeutral(x, "fine"))))
          .append(" | ")
          .append(
              f(
                  mean(
                      b,
                      x ->
                          (double) n(x.getAsJsonObject("f2_neutrali"), "neutrali_catturate_asse"))))
          .append(" |\n");
    }
  }

  private static double exNeutral(final JsonObject x, final String k) {
    final JsonObject ex = x.getAsJsonObject("f2_neutrali").getAsJsonObject("ex_neutrali_asse");
    return ex.has(k) ? ex.get(k).getAsDouble() : Double.NaN;
  }

  private static void f3(final StringBuilder md, final Map<String, List<JsonObject>> byCode) {
    md.append(
"""

F3. Ponte: sbarchi USA di almeno 6 unita'; all'inizio del turno britannico dello stesso round raggiungibili con almeno 2 / 4 unita' caricabili (trasporti britannici che arrivano in una zona accanto entro il loro movimento, zone senza navi nemiche; truppe accanto alle zone da cui partono o passano); rinforzati = unita' britanniche sbarcate li' nello stesso round; teste di ponte tenute un round dopo CON e SENZA rinforzo.

| Config | Sbarchi USA >= 6 | Raggiungibili >= 2 | Rinforzati | Raggiungibili >= 4 | Rinforzati | Tenute con rinforzo | Tenute senza |
|---|---|---|---|---|---|---|---|
""");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> b = blocks(e.getValue());
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(sumT(b, "sbarchi"))
          .append(" | ")
          .append(sumT(b, "raggiungibili_2"))
          .append(" | ")
          .append(sumT(b, "rinforzati_2"))
          .append(" ")
          .append(pct(sumT(b, "rinforzati_2"), sumT(b, "raggiungibili_2")))
          .append(" | ")
          .append(sumT(b, "raggiungibili_4"))
          .append(" | ")
          .append(sumT(b, "rinforzati_4"))
          .append(" ")
          .append(pct(sumT(b, "rinforzati_4"), sumT(b, "raggiungibili_4")))
          .append(" | ")
          .append(sumT(b, "tenuti_con_rinforzo"))
          .append(" su ")
          .append(sumT(b, "presi_con_rinforzo"))
          .append(" ")
          .append(pct(sumT(b, "tenuti_con_rinforzo"), sumT(b, "presi_con_rinforzo")))
          .append(" | ")
          .append(sumT(b, "tenuti_senza_rinforzo"))
          .append(" su ")
          .append(sumT(b, "presi_senza_rinforzo"))
          .append(" ")
          .append(pct(sumT(b, "tenuti_senza_rinforzo"), sumT(b, "presi_senza_rinforzo")))
          .append(" |\n");
    }
  }

  private static void f4(final StringBuilder md, final Map<String, List<JsonObject>> byCode) {
    md.append(
"""

F4. Regno Unito per round (medie sulle partite arrivate a quel round): unita' di terra in patria a inizio turno; PU spesi terra / navi / aerei / fabbriche; unita' sbarcate in Europa; unita' di terra in Egitto e Nord Africa a fine round; trasporti persi.

| Config | Round | Terra in patria | PU terra / navi / aerei / fabbriche | Sbarcate in Europa | Egitto e Nord Africa | Trasporti persi |
|---|---|---|---|---|---|---|
""");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> b = blocks(e.getValue());
      for (int r = 1; r <= ROUND_F4; r++) {
        final String k = String.valueOf(r);
        final List<JsonObject> rr = new ArrayList<>();
        for (final JsonObject x : b) {
          final JsonObject f4 = x.getAsJsonObject("f4_regno_unito");
          if (f4.has(k)) {
            rr.add(f4.getAsJsonObject(k));
          }
        }
        if (rr.isEmpty()) {
          continue;
        }
        md.append("| ")
            .append(e.getKey())
            .append(" | ")
            .append(r)
            .append(" | ")
            .append(f(mean(rr, x -> d(x, "terra_in_patria_inizio_turno"))))
            .append(" | ")
            .append(f(mean(rr, x -> (double) n(x, "pu_terra"))))
            .append(" / ")
            .append(f(mean(rr, x -> (double) n(x, "pu_navi"))))
            .append(" / ")
            .append(f(mean(rr, x -> (double) n(x, "pu_aerei"))))
            .append(" / ")
            .append(f(mean(rr, x -> (double) n(x, "pu_fabbriche"))))
            .append(" | ")
            .append(f(mean(rr, x -> (double) n(x, "sbarcate_europa"))))
            .append(" | ")
            .append(f(mean(rr, x -> (double) n(x, "terra_egitto_nord_africa"))))
            .append(" | ")
            .append(f(mean(rr, x -> (double) n(x, "trasporti_persi"))))
            .append(" |\n");
      }
    }
  }

  // ======================================================================= helpers

  private static List<JsonObject> blocks(final List<JsonObject> games) {
    return games.stream().map(j -> j.getAsJsonObject("p7")).toList();
  }

  private static String cum(final List<JsonObject> b, final String k) {
    final List<String> out = new ArrayList<>();
    for (final int r : ROUND_SBARCATE) {
      out.add(
          f(
              mean(
                  b,
                  x -> {
                    final JsonObject c = x.getAsJsonObject("usa_sbarcate");
                    return c.has(String.valueOf(r))
                        ? c.getAsJsonObject(String.valueOf(r)).get(k).getAsDouble()
                        : Double.NaN;
                  })));
    }
    return String.join(" / ", out);
  }

  private static int sumT(final List<JsonObject> b, final String k) {
    int s = 0;
    for (final JsonObject x : b) {
      s += n(x.getAsJsonObject("teste_di_ponte"), k);
    }
    return s;
  }

  private static int reg(final List<JsonObject> b, final String k) {
    int s = 0;
    for (final JsonObject x : b) {
      if (x.has("registro")) {
        s += n(x.getAsJsonObject("registro"), k);
      }
    }
    return s;
  }

  private static String pct(final int k, final int n) {
    return n == 0 ? "(-)" : String.format(Locale.ROOT, "(%.0f%%)", 100.0 * k / n);
  }

  private static double median(final List<Integer> v) {
    final List<Integer> s = new ArrayList<>(v);
    s.sort(Integer::compare);
    final int m = s.size() / 2;
    return s.size() % 2 == 1 ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2.0;
  }

  private static double mean(final List<JsonObject> b, final Function<JsonObject, Double> f) {
    return ConfrontoP4.mean(b, f);
  }

  private static long sum(final List<JsonObject> b, final Function<JsonObject, Double> f) {
    double s = 0;
    for (final JsonObject x : b) {
      try {
        final double v = f.apply(x);
        if (!Double.isNaN(v)) {
          s += v;
        }
      } catch (final RuntimeException e) {
        // missing = 0
      }
    }
    return Math.round(s);
  }

  private static int count(final List<JsonObject> b, final Predicate<JsonObject> p) {
    int n = 0;
    for (final JsonObject x : b) {
      try {
        if (p.test(x)) {
          n++;
        }
      } catch (final RuntimeException e) {
        // missing = false
      }
    }
    return n;
  }

  private static Map<Integer, JsonObject> snaps(final JsonObject json) {
    final TreeMap<Integer, JsonObject> out = new TreeMap<>();
    if (json.has("round")) {
      for (final JsonElement e : json.getAsJsonArray("round")) {
        final JsonObject r = e.getAsJsonObject();
        if (r.has("p7")) {
          out.put(r.get("round").getAsInt(), r);
        }
      }
    }
    return out;
  }

  /** The Allies of WW2 Europe 1940 (the nations whose war on the neutral is counted). */
  private static List<String> alleati(final JsonObject json) {
    return List.of("Americans", "British", "French", "Russians");
  }

  private static @Nullable JsonObject sub(@Nullable final JsonObject o, final String k) {
    return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
  }

  private static @Nullable String s(final JsonObject o, final String k) {
    return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
  }

  private static int n(final JsonObject o, final String k) {
    return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
  }

  private static double d(final JsonObject o, final String k) {
    return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : Double.NaN;
  }

  private static boolean flag(final JsonObject o, final String k) {
    return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
  }

  private static String f(final double x) {
    return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.1f", x).replace('.', ',');
  }
}
