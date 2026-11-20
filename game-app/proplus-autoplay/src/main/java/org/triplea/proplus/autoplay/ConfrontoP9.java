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
 * Step P9+P10: the block {@code p9} of each game's {@code riepilogo.json} (from the monitor events
 * {@code p7_eventi}, the block {@code p7} and the register rows {@code ponte_concentrato}) and the
 * section "P9" of {@code riepilogo-generale.md}: the seven configurations, the six comparisons
 * declared before the run, the bridgeheads (D) and the concentrated bridge. Read only.
 */
final class ConfrontoP9 {
  private ConfrontoP9() {}

  static final String USA = "Americans";
  static final String UK = "British";
  static final int FORTE = 6;
  static final List<Integer> ROUND_SBARCATE = List.of(6, 8, 10);
  static final String TIPO = "ponte_concentrato";
  static final String RINFORZO = "PONTE_CONC_RINFORZO";
  static final List<String> MOTIVI =
      List.of(
          "PONTE_CONC_RINFORZO",
          "PONTE_CONC_NESSUN_CANDIDATO",
          "PONTE_CONC_NESSUNO_SOTTO_SOGLIA",
          "PONTE_CONC_GIA_SICURO",
          "PONTE_CONC_ESPOSTI",
          "PONTE_CONC_RIFIUTATO");

  /** The configurations of the run by their Allied switches (code → switches). */
  static final Map<String, Set<String>> CODICI = new LinkedHashMap<>();

  static {
    final Set<String> s2 = Set.of("usa_proporzione", "sbarco_concentrato");
    CODICI.put("e2", Set.of());
    CODICI.put("n1", Set.of("niente_neutrali"));
    CODICI.put("s2", s2);
    CODICI.put("s5", plus(s2, "niente_neutrali"));
    CODICI.put("c3", plus(s2, "niente_neutrali", "ponte_uk"));
    CODICI.put("d1", plus(s2, "ponte_concentrato"));
    CODICI.put("d2", plus(s2, "niente_neutrali", "ponte_concentrato"));
  }

  /** The six comparisons declared before the run. */
  static final List<String[]> CONFRONTI =
      List.of(
          new String[] {"n1", "e2", "niente neutrali sopra Hard"},
          new String[] {"s5", "s2", "niente neutrali sopra la Base"},
          new String[] {"s5", "n1", "la Base sopra niente neutrali"},
          new String[] {"d1", "s2", "ponte concentrato sopra la Base"},
          new String[] {"d2", "s5", "ponte concentrato sopra s5"},
          new String[] {"d2", "c3", "ponte concentrato contro ponte_uk"});

  private static Set<String> plus(final Set<String> base, final String... more) {
    final Set<String> out = new TreeSet<>(base);
    out.addAll(List.of(more));
    return Set.copyOf(out);
  }

  static String codice(final Set<String> switches) {
    for (final var e : CODICI.entrySet()) {
      if (e.getValue().equals(switches)) {
        return e.getKey();
      }
    }
    return switches.isEmpty() ? "e2" : String.join("+", switches);
  }

  // ================================================================ block of one game

  /**
   * The US land units landed by sea up to round {@code r} on land that was not allied when the US
   * turn started (enemy, Axis, true neutral): the transfers to own or allied land (United Kingdom,
   * Gibraltar, Morocco after Torch...) are not landings.
   */
  static int usaSbarcate(final List<JsonObject> landings, final int r) {
    int n = 0;
    for (final JsonObject l : landings) {
      if (USA.equals(s(l, "nazione"))
          && n(l, "round") <= r
          && !"alleato".equals(s(l, "categoria"))
          && !"altro".equals(s(l, "categoria"))) {
        n += n(l, "unita");
      }
    }
    return n;
  }

  static JsonObject blocco(final JsonObject json) {
    final JsonObject o = new JsonObject();
    o.addProperty("configurazione", codice(ConfrontoP6.interruttori(json)));
    final String stato = json.has("stato") ? json.get("stato").getAsString() : "";
    o.addProperty("valida", stato.startsWith("completata") || stato.startsWith("stallo"));
    final JsonObject ev =
        json.has("p7_eventi") ? json.getAsJsonObject("p7_eventi") : new JsonObject();
    final List<JsonObject> landings = new ArrayList<>();
    if (ev.has("sbarchi")) {
      ev.getAsJsonArray("sbarchi").forEach(e -> landings.add(e.getAsJsonObject()));
    }
    final JsonObject cum = new JsonObject();
    for (final int r : ROUND_SBARCATE) {
      cum.addProperty(String.valueOf(r), usaSbarcate(landings, r));
    }
    o.add("usa_sbarcate", cum);
    // the register of ponte_concentrato: one row per British turn
    final List<JsonObject> rows = new ArrayList<>();
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if ("decisione".equals(s(d, "riga")) && TIPO.equals(s(d, "tipo"))) {
        rows.add(d);
      }
    }
    // candidate → its numbers (round|territory), the targets (round|territory)
    final Map<String, JsonObject> cand = new TreeMap<>();
    final Map<String, JsonObject> targets = new TreeMap<>();
    final JsonObject motivi = new JsonObject();
    MOTIVI.forEach(m -> motivi.addProperty(m, 0));
    final JsonArray dopo = new JsonArray();
    int turns = 0;
    int sent = 0;
    int couldArrive = 0;
    int allSent = 0;
    int moreThanOne = 0;
    final Map<Integer, Integer> perRound = new TreeMap<>();
    for (final JsonObject d : rows) {
      turns++;
      final String code = ConfrontoR5.codice(d);
      motivi.addProperty(code, n(motivi, code) + 1);
      final JsonObject p = d.has("parametri") ? d.getAsJsonObject("parametri") : new JsonObject();
      final int round = n(d, "round");
      if (p.has("candidati")) {
        for (final JsonElement c : p.getAsJsonArray("candidati")) {
          final JsonObject x = c.getAsJsonObject();
          cand.put(round + "|" + s(x, "territorio"), x);
          if (x.has("vittoria_asse_dopo")) {
            dopo.add(x.get("vittoria_asse_dopo").getAsDouble());
          }
        }
      }
      if (RINFORZO.equals(code)) {
        perRound.merge(round, 1, Integer::sum);
        targets.put(round + "|" + s(p, "bersaglio"), p);
        sent += n(p, "unita_mandate");
        couldArrive += n(p, "unita_che_possono_arrivare");
        if (n(p, "unita_mandate") >= n(p, "unita_che_possono_arrivare")) {
          allSent++;
        }
      }
    }
    for (final int k : perRound.values()) {
      if (k > 1) {
        moreThanOne++;
      }
    }
    final JsonObject pc = new JsonObject();
    pc.addProperty("turni", turns);
    pc.addProperty("rinforzi", targets.size());
    pc.addProperty("unita_mandate", sent);
    pc.addProperty("unita_che_potevano_arrivare", couldArrive);
    pc.addProperty("rinforzi_con_tutto_il_carico", allSent);
    pc.addProperty("round_con_piu_bersagli", moreThanOne);
    pc.add("motivi", motivi);
    pc.add("vittoria_asse_dopo_candidati", dopo);
    // the bridgeheads (D)
    final JsonArray heads = new JsonArray();
    final JsonObject t = new JsonObject();
    int taken = 0;
    int known = 0;
    int held = 0;
    int counter = 0;
    int counterTaken = 0;
    int reach = 0;
    int reachKnown = 0;
    int reachHeld = 0;
    int targetKnown = 0;
    int targetHeld = 0;
    int targetCounter = 0;
    for (final JsonObject l : landings) {
      if (!USA.equals(s(l, "nazione"))
          || n(l, "unita") < FORTE
          || !("Asse".equals(s(l, "categoria")) || "nemico".equals(s(l, "categoria")))
          || !flag(l, "presa")) {
        continue;
      }
      taken++;
      final String key = n(l, "round") + "|" + s(l, "territorio");
      final JsonObject x = new JsonObject();
      for (final String k :
          List.of(
              "round",
              "territorio",
              "unita",
              "difensori_fine_uk",
              "asse_in_portata",
              "uk_unita_caricabili",
              "uk_sbarcate",
              "contrattaccata",
              "contrattaccata_prima_del_turno_usa",
              "contrattaccata_da",
              "esito_contrattacco",
              "contrattacchi",
              "alleato_round_dopo")) {
        if (l.has(k)) {
          x.add(k, l.get(k).deepCopy());
        }
      }
      final JsonObject c = cand.get(key);
      if (c != null) {
        x.addProperty("vittoria_asse_prima", d(c, "vittoria_asse_prima"));
        x.addProperty("vittoria_asse_dopo", d(c, "vittoria_asse_dopo"));
        x.addProperty("stato_ponte", s(c, "stato"));
      }
      final boolean isTarget = targets.containsKey(key);
      x.addProperty("bersaglio_ponte", isTarget);
      heads.add(x);
      final boolean c1 = flag(l, "contrattaccata");
      counter += c1 ? 1 : 0;
      if (c1 && "presa_asse".equals(s(l, "esito_contrattacco"))) {
        counterTaken++;
      }
      if (l.has("alleato_round_dopo")) {
        known++;
        final boolean h = flag(l, "alleato_round_dopo");
        held += h ? 1 : 0;
        if (n(l, "uk_unita_caricabili") >= 2) {
          reachKnown++;
          reachHeld += h ? 1 : 0;
        }
        if (isTarget) {
          targetKnown++;
          targetHeld += h ? 1 : 0;
          targetCounter += c1 ? 1 : 0;
        }
      }
      if (n(l, "uk_unita_caricabili") >= 2) {
        reach++;
      }
    }
    t.addProperty("prese", taken);
    t.addProperty("prese_con_esito_noto", known);
    t.addProperty("tenute_round_dopo", held);
    t.addProperty("contrattaccate", counter);
    t.addProperty("riprese_dall_asse", counterTaken);
    t.addProperty("raggiungibili", reach);
    t.addProperty("raggiungibili_con_esito_noto", reachKnown);
    t.addProperty("raggiungibili_tenute", reachHeld);
    t.addProperty("bersagli_con_esito_noto", targetKnown);
    t.addProperty("bersagli_tenuti", targetHeld);
    t.addProperty("bersagli_contrattaccati", targetCounter);
    o.add("teste_di_ponte", t);
    o.add("teste", heads);
    o.add("ponte_concentrato", pc);
    // the UK's own landings on enemy land
    final JsonObject uk = new JsonObject();
    int ukN = 0;
    int ukUnits = 0;
    int ukTaken = 0;
    int ukKnown = 0;
    int ukHeld = 0;
    for (final JsonObject l : landings) {
      if (!UK.equals(s(l, "nazione"))
          || !("Asse".equals(s(l, "categoria")) || "nemico".equals(s(l, "categoria")))) {
        continue;
      }
      ukN++;
      ukUnits += n(l, "unita");
      if (flag(l, "presa")) {
        ukTaken++;
        if (l.has("alleato_round_dopo")) {
          ukKnown++;
          ukHeld += flag(l, "alleato_round_dopo") ? 1 : 0;
        }
      }
    }
    uk.addProperty("sbarchi", ukN);
    uk.addProperty("unita", ukUnits);
    uk.addProperty("presi", ukTaken);
    uk.addProperty("presi_con_esito_noto", ukKnown);
    uk.addProperty("tenuti", ukHeld);
    o.add("uk_sbarchi_propri", uk);
    return o;
  }

  // ======================================================================= the section

  static boolean applicabile(final List<Game> games) {
    return games.stream()
        .anyMatch(
            g ->
                g.json() != null
                    && g.json().has("p9")
                    && g.json().getAsJsonObject("p9").has("configurazione")
                    && Set.of("n1", "d1", "d2")
                        .contains(
                            g.json().getAsJsonObject("p9").get("configurazione").getAsString()));
  }

  static void write(final StringBuilder md, final List<Game> games) {
    final Map<String, List<JsonObject>> byCode = new LinkedHashMap<>();
    final Map<String, int[]> invalid = new LinkedHashMap<>();
    CODICI.keySet().forEach(k -> byCode.put(k, new ArrayList<>()));
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("p9") || g.json().getAsJsonObject("p9").has("errore")) {
        continue;
      }
      final JsonObject p9 = g.json().getAsJsonObject("p9");
      final String code = p9.get("configurazione").getAsString();
      if (!flag(p9, "valida")) {
        invalid.computeIfAbsent(code, k -> new int[1])[0]++;
        continue;
      }
      byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(g.json());
    }
    byCode.values().removeIf(List::isEmpty);
    if (byCode.isEmpty()) {
      return;
    }
    md.append(
"""


### P9+P10

""");
    md.append(
        "Step P9+P10 (ponte_concentrato; conferma di niente_neutrali). Configurazioni per"
            + " interruttori degli Alleati: e2 Hard; n1 niente_neutrali; s2 Base (usa_proporzione +"
            + " sbarco_concentrato); s5 Base + niente_neutrali; c3 Base + niente_neutrali +"
            + " ponte_uk; d1 Base + ponte_concentrato; d2 Base + niente_neutrali +"
            + " ponte_concentrato. Partite valide = completate o in stallo (le partite in errore"
            + " sono escluse e contate a parte). Dal blocco `p9` dei `riepilogo.json` (sola"
            + " lettura). p > 0,05 non e' una conclusione.\n\n");
    md.append(
        "| Config | Valide | Errori/parziali | Stalli | Alleati vince [IC 95%] | Mosca non caduta entro"
            + " R14 | Roma cade | Berlino cade | Unita' USA sbarcate entro R6 / R8 / R10 (media) |"
            + " Trasporti persi entro R8 USA / UK (media) | Teste di ponte prese / contrattaccate"
            + " / tenute un round dopo | UK sbarchi propri su Asse: numero / unita' / presi /"
            + " tenuti (somme) |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> l = e.getValue();
      final List<JsonObject> b = blocks(l, "p9");
      final List<JsonObject> b7 = blocks(l, "p7");
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
          .append(ConfrontoR4.wilsonText(allies, n))
          .append(" | ")
          .append(count(b7, x -> flag(x, "mosca_oltre_r14")))
          .append(" | ")
          .append(count(b7, x -> x.getAsJsonObject("cadute").has("roma")))
          .append(" | ")
          .append(count(b7, x -> x.getAsJsonObject("cadute").has("berlino")))
          .append(" | ")
          .append(cum(b))
          .append(" | ")
          .append(f(mean(b7, x -> (double) n(x, "usa_trasporti_persi_r8"))))
          .append(" / ")
          .append(f(mean(b7, x -> (double) n(x, "uk_trasporti_persi_r8"))))
          .append(" | ")
          .append(sumT(b, "prese"))
          .append(" / ")
          .append(sumT(b, "contrattaccate"))
          .append(" ")
          .append(pct(sumT(b, "contrattaccate"), sumT(b, "prese")))
          .append(" / ")
          .append(sumT(b, "tenute_round_dopo"))
          .append(" su ")
          .append(sumT(b, "prese_con_esito_noto"))
          .append(" ")
          .append(pct(sumT(b, "tenute_round_dopo"), sumT(b, "prese_con_esito_noto")))
          .append(" | ")
          .append(sumUk(b, "sbarchi"))
          .append(" / ")
          .append(sumUk(b, "unita"))
          .append(" / ")
          .append(sumUk(b, "presi"))
          .append(" / ")
          .append(sumUk(b, "tenuti"))
          .append(" su ")
          .append(sumUk(b, "presi_con_esito_noto"))
          .append(" |\n");
    }
    // criterion 3: the true neutrals
    md.append(
"""

Neutrali veri (criterio 3): partite con guerra a Neutral_True aperta da un alleato, attacchi alleati a territori Neutral_True (somma).

| Config | Guerra aperta da alleati | Attacchi alleati |
|---|---|---|
""");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> b7 = blocks(e.getValue(), "p7");
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(count(b7, x -> flag(x.getAsJsonObject("f2_neutrali"), "aperta_da_alleati")))
          .append("/")
          .append(b7.size())
          .append(" | ")
          .append(
              sum(
                  b7,
                  x -> (double) n(x.getAsJsonObject("f2_neutrali"), "attacchi_alleati_neutrali")))
          .append(" |\n");
    }
    // the six declared comparisons
    md.append(
"""

I sei confronti dichiarati prima (Fisher esatto a due code; vittorie alleate e partite con Mosca non caduta entro R14).

| Confronto | Vittorie alleate | p | Mosca non caduta entro R14 | p |
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
      final int ma = count(blocks(a, "p7"), x -> flag(x, "mosca_oltre_r14"));
      final int mb = count(blocks(b, "p7"), x -> flag(x, "mosca_oltre_r14"));
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
    // criterion 5: the lever
    md.append(
"""

Misura della leva (dichiarata prima): teste di ponte USA (>= 6 unita', territorio dell'Asse, prese) raggiungibili dal Regno Unito (monitor: >= 2 unita' britanniche caricabili che arrivano accanto, all'inizio del turno britannico) ancora alleate alla fine del round dopo; quota di teste di ponte contrattaccate; riprese dall'Asse.

| Config | Raggiungibili tenute un round dopo | Tutte le teste: tenute | Contrattaccate | Riprese dall'Asse |
|---|---|---|---|---|
""");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> b = blocks(e.getValue(), "p9");
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(sumT(b, "raggiungibili_tenute"))
          .append(" su ")
          .append(sumT(b, "raggiungibili_con_esito_noto"))
          .append(" ")
          .append(pct(sumT(b, "raggiungibili_tenute"), sumT(b, "raggiungibili_con_esito_noto")))
          .append(" | ")
          .append(sumT(b, "tenute_round_dopo"))
          .append(" su ")
          .append(sumT(b, "prese_con_esito_noto"))
          .append(" ")
          .append(pct(sumT(b, "tenute_round_dopo"), sumT(b, "prese_con_esito_noto")))
          .append(" | ")
          .append(sumT(b, "contrattaccate"))
          .append(" su ")
          .append(sumT(b, "prese"))
          .append(" ")
          .append(pct(sumT(b, "contrattaccate"), sumT(b, "prese")))
          .append(" | ")
          .append(sumT(b, "riprese_dall_asse"))
          .append(" ")
          .append(pct(sumT(b, "riprese_dall_asse"), sumT(b, "contrattaccate")))
          .append(" |\n");
    }
    ponte(md, byCode);
    teste(md, byCode);
  }

  /** The concentrated bridge: register per configuration (criterion 4). */
  private static void ponte(final StringBuilder md, final Map<String, List<JsonObject>> byCode) {
    md.append(
"""

Ponte concentrato (registro `ponte_concentrato`, una riga per turno britannico): turni, rinforzi (volte a partita), unita' mandate / che potevano arrivare, rinforzi con tutto il carico, round con piu' di un bersaglio (deve essere 0), motivi per cui non ha agito, bersagli tenuti un round dopo e contrattaccati; distribuzione della vittoria dell'Asse DOPO il rinforzo stimata sui candidati.

| Config | Turni | Rinforzi (a partita) | Unita' mandate / potevano (media per rinforzo) | Con tutto il carico | Round con > 1 bersaglio | Nessun candidato / nessuno sotto soglia / gia' sicuro / esposti / rifiutati | Bersagli tenuti | Bersagli contrattaccati | Vittoria Asse dopo sui candidati: <35 / 35-65 / 65-85 / >=85 |
|---|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> b = blocks(e.getValue(), "p9");
      if (sumP(b, "turni") == 0) {
        continue;
      }
      final int[] bands = new int[4];
      for (final JsonObject x : b) {
        for (final JsonElement v :
            x.getAsJsonObject("ponte_concentrato").getAsJsonArray("vittoria_asse_dopo_candidati")) {
          final double w = v.getAsDouble();
          bands[w < 35 ? 0 : w < 65 ? 1 : w < 85 ? 2 : 3]++;
        }
      }
      final int rinf = sumP(b, "rinforzi");
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(sumP(b, "turni"))
          .append(" | ")
          .append(rinf)
          .append(" (")
          .append(f((double) rinf / b.size()))
          .append(") | ")
          .append(sumP(b, "unita_mandate"))
          .append(" / ")
          .append(sumP(b, "unita_che_potevano_arrivare"))
          .append(" (")
          .append(rinf == 0 ? "-" : f((double) sumP(b, "unita_mandate") / rinf))
          .append(") | ")
          .append(sumP(b, "rinforzi_con_tutto_il_carico"))
          .append(" | ")
          .append(sumP(b, "round_con_piu_bersagli"))
          .append(" | ")
          .append(motivo(b, "PONTE_CONC_NESSUN_CANDIDATO"))
          .append(" / ")
          .append(motivo(b, "PONTE_CONC_NESSUNO_SOTTO_SOGLIA"))
          .append(" / ")
          .append(motivo(b, "PONTE_CONC_GIA_SICURO"))
          .append(" / ")
          .append(motivo(b, "PONTE_CONC_ESPOSTI"))
          .append(" / ")
          .append(motivo(b, "PONTE_CONC_RIFIUTATO"))
          .append(" | ")
          .append(sumT(b, "bersagli_tenuti"))
          .append(" su ")
          .append(sumT(b, "bersagli_con_esito_noto"))
          .append(" ")
          .append(pct(sumT(b, "bersagli_tenuti"), sumT(b, "bersagli_con_esito_noto")))
          .append(" | ")
          .append(sumT(b, "bersagli_contrattaccati"))
          .append(" | ")
          .append(bands[0])
          .append(" / ")
          .append(bands[1])
          .append(" / ")
          .append(bands[2])
          .append(" / ")
          .append(bands[3])
          .append(" |\n");
    }
  }

  /** D: the bridgeheads, means per configuration. */
  private static void teste(final StringBuilder md, final Map<String, List<JsonObject>> byCode) {
    md.append(
"""

D. Teste di ponte USA (>= 6 unita', territorio dell'Asse, prese), medie per testa: difensori alleati a fine turno britannico; unita' dell'Asse in portata (approssimazione del monitor: terra entro il suo movimento via terra, aerei entro il loro movimento, niente anfibi); vittoria stimata dell'Asse prima / dopo il rinforzo (solo dove il ponte concentrato l'ha calcolata); unita' britanniche sbarcate li' nel round; contrattaccate fino alla fine del round dopo (da Germania / Italia; prima che gli USA muovano di nuovo, cioe' contro la forza lasciata dal turno britannico); nel contrattacco attaccanti / difensori, perse Asse / alleate; esito: riprese dall'Asse; alleate alla fine del round dopo.

| Config | Teste | Difensori | Asse in portata | Vittoria Asse prima / dopo (n) | UK sbarcate | Contrattaccate (Ger / Ita; prima del turno USA dopo) | Attaccanti / difensori | Perse Asse / alleate | Riprese | Tenute un round dopo |
|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byCode.entrySet()) {
      final List<JsonObject> heads = new ArrayList<>();
      final List<JsonObject> attacks = new ArrayList<>();
      for (final JsonObject x : blocks(e.getValue(), "p9")) {
        for (final JsonElement h : x.getAsJsonArray("teste")) {
          final JsonObject o = h.getAsJsonObject();
          heads.add(o);
          if (o.has("contrattacchi")) {
            o.getAsJsonArray("contrattacchi").forEach(a -> attacks.add(a.getAsJsonObject()));
          }
        }
      }
      final List<JsonObject> withCalc =
          heads.stream().filter(o -> o.has("vittoria_asse_prima")).toList();
      final int known = count(heads, o -> o.has("alleato_round_dopo"));
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(heads.size())
          .append(" | ")
          .append(f(mean(heads, o -> d(o, "difensori_fine_uk"))))
          .append(" | ")
          .append(f(mean(heads, o -> d(o, "asse_in_portata"))))
          .append(" | ")
          .append(f(mean(withCalc, o -> d(o, "vittoria_asse_prima"))))
          .append(" / ")
          .append(f(mean(withCalc, o -> d(o, "vittoria_asse_dopo"))))
          .append(" (")
          .append(withCalc.size())
          .append(") | ")
          .append(f(mean(heads, o -> (double) n(o, "uk_sbarcate"))))
          .append(" | ")
          .append(count(heads, o -> flag(o, "contrattaccata")))
          .append(" (")
          .append(count(heads, o -> "Germans".equals(s(o, "contrattaccata_da"))))
          .append(" / ")
          .append(count(heads, o -> "Italians".equals(s(o, "contrattaccata_da"))))
          .append("; ")
          .append(count(heads, o -> flag(o, "contrattaccata_prima_del_turno_usa")))
          .append(") | ")
          .append(f(mean(attacks, o -> d(o, "attaccanti"))))
          .append(" / ")
          .append(f(mean(attacks, o -> d(o, "difensori"))))
          .append(" | ")
          .append(f(mean(attacks, o -> d(o, "perse_asse"))))
          .append(" / ")
          .append(f(mean(attacks, o -> d(o, "perse_alleate"))))
          .append(" | ")
          .append(count(heads, o -> "presa_asse".equals(s(o, "esito_contrattacco"))))
          .append(" | ")
          .append(count(heads, o -> flag(o, "alleato_round_dopo")))
          .append(" su ")
          .append(known)
          .append(" |\n");
    }
  }

  // ======================================================================= helpers

  private static List<JsonObject> blocks(final List<JsonObject> games, final String k) {
    return games.stream().map(j -> j.has(k) ? j.getAsJsonObject(k) : new JsonObject()).toList();
  }

  private static String cum(final List<JsonObject> b) {
    final List<String> out = new ArrayList<>();
    for (final int r : ROUND_SBARCATE) {
      out.add(f(mean(b, x -> d(x.getAsJsonObject("usa_sbarcate"), String.valueOf(r)))));
    }
    return String.join(" / ", out);
  }

  private static int sumT(final List<JsonObject> b, final String k) {
    int s = 0;
    for (final JsonObject x : b) {
      s += n(sub(x, "teste_di_ponte"), k);
    }
    return s;
  }

  private static int sumUk(final List<JsonObject> b, final String k) {
    int s = 0;
    for (final JsonObject x : b) {
      s += n(sub(x, "uk_sbarchi_propri"), k);
    }
    return s;
  }

  private static int sumP(final List<JsonObject> b, final String k) {
    int s = 0;
    for (final JsonObject x : b) {
      s += n(sub(x, "ponte_concentrato"), k);
    }
    return s;
  }

  private static int motivo(final List<JsonObject> b, final String k) {
    int s = 0;
    for (final JsonObject x : b) {
      s += n(sub(sub(x, "ponte_concentrato"), "motivi"), k);
    }
    return s;
  }

  private static String pct(final int k, final int n) {
    return n == 0 ? "(-)" : String.format(Locale.ROOT, "(%.0f%%)", 100.0 * k / n);
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

  private static JsonObject sub(@Nullable final JsonObject o, final String k) {
    return o != null && o.has(k) && o.get(k).isJsonObject()
        ? o.getAsJsonObject(k)
        : new JsonObject();
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
