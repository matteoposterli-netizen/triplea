package org.triplea.proplus.autoplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/**
 * Step P3: the block {@code p3} of each game's {@code riepilogo.json} (monitor snapshots {@code
 * round[].p2}, the block {@code p2}, register rows {@code cairo_tenuta} and {@code ponte_peso}, the
 * capitals' events) and the section "P3" of {@code riepilogo-generale.md}. Every configuration,
 * Hard included (the register rows are then absent: 0).
 */
final class ConfrontoP3 {
  private ConfrontoP3() {}

  static final String P3 = "[con regno_unito_p3]";
  static final String CAPITALE_UK = "United Kingdom";
  static final List<Integer> ROUND_EGITTO = List.of(1, 2, 3, 4);

  // ================================================================ block of one game

  static JsonObject blocco(final JsonObject json) {
    final JsonObject o = new JsonObject();
    final List<JsonObject> rows = new ArrayList<>();
    for (final JsonElement e : ConfrontoR3a.registro(json)) {
      final JsonObject d = e.getAsJsonObject();
      if ("decisione".equals(d.get("riga").getAsString())) {
        rows.add(d);
      }
    }
    final Map<Integer, JsonObject> snaps = new TreeMap<>();
    if (json.has("round")) {
      for (final JsonElement e : json.getAsJsonArray("round")) {
        final JsonObject r = e.getAsJsonObject();
        snaps.put(r.get("round").getAsInt(), r);
      }
    }
    // Egypt of the Axis at the end of R1-R4; British land units in Egypt at the end of R1
    final JsonObject eg = new JsonObject();
    for (final int r : ROUND_EGITTO) {
      final JsonObject p2 = p2(snaps.get(r));
      if (p2 != null && p2.has("egitto_asse")) {
        eg.addProperty(String.valueOf(r), p2.get("egitto_asse").getAsBoolean());
      }
    }
    o.add("egitto_asse_fine_round", eg);
    final JsonObject r1 = p2(snaps.get(1));
    o.addProperty(
        "uk_in_egitto_r1", r1 != null && r1.has("uk_egitto") ? r1.get("uk_egitto").getAsInt() : -1);
    final JsonObject b2 = json.has("p2") ? json.getAsJsonObject("p2") : new JsonObject();
    final int egRounds = b2.has("round_egitto_asse") ? b2.get("round_egitto_asse").getAsInt() : 0;
    final int snapRounds =
        b2.has("round_con_fotografia") ? b2.get("round_con_fotografia").getAsInt() : 0;
    o.addProperty("round_egitto_asse", egRounds);
    o.addProperty("round_con_fotografia", snapRounds);
    // the city's register
    int bySea = 0;
    int byLand = 0;
    int byAir = 0;
    int pu = 0;
    int reserved = 0;
    final JsonObject states = new JsonObject();
    int threatHits = 0;
    int retakes = 0;
    for (final JsonObject d : decisioni(rows, "cairo_tenuta")) {
      final JsonObject p = d.getAsJsonObject("parametri");
      final String code = ConfrontoR5.codice(d);
      states.addProperty(code, (states.has(code) ? states.get(code).getAsInt() : 0) + 1);
      if (p.has("non_combattimento")) {
        final JsonObject n = p.getAsJsonObject("non_combattimento");
        bySea += num(n, "via_mare");
        byLand += num(n, "via_terra");
        byAir += num(n, "via_aria");
        if (n.has("viaggi")) {
          for (final JsonElement t : n.getAsJsonArray("viaggi")) {
            final JsonObject trip = t.getAsJsonObject();
            if (trip.has("consegna_da") && trip.has("truppe")) {
              bySea += total(trip.getAsJsonObject("truppe"));
            }
          }
        }
      }
      if (p.has("combattimento")) {
        final JsonObject c = p.getAsJsonObject("combattimento");
        bySea += num(c, "ripresa_via_mare");
        byLand += num(c, "ripresa_via_terra");
        if (c.has("ripresa_eseguita") && c.get("ripresa_eseguita").getAsBoolean()) {
          retakes++;
        }
        if (c.has("attacco_minaccia_eseguito")
            && c.get("attacco_minaccia_eseguito").getAsBoolean()) {
          threatHits++;
        }
      }
      if (p.has("acquisto")) {
        pu += num(p.getAsJsonObject("acquisto"), "pu");
      }
      reserved += num(p, "riservate");
    }
    o.add("turni_citta", states);
    o.addProperty("portate_in_egitto_via_mare", bySea);
    o.addProperty("portate_in_egitto_via_terra", byLand);
    o.addProperty("portate_in_egitto_via_aria", byAir);
    o.addProperty("pu_cairo", pu);
    o.addProperty("unita_riservate_totale", reserved);
    o.addProperty("attacchi_alla_minaccia", threatHits);
    o.addProperty("riprese", retakes);
    // the bridgehead weight's register
    int heads = 0;
    int sent = 0;
    int sentSea = 0;
    int followed = 0;
    for (final JsonObject d : decisioni(rows, "ponte_peso")) {
      final JsonObject p = d.getAsJsonObject("parametri");
      if (p.has("teste") && !p.getAsJsonArray("teste").isEmpty()) {
        heads++;
      }
      sent += num(p, "unita_mandate");
      if ("PESO_SEGUITO".equals(ConfrontoR5.codice(d))) {
        followed++;
      }
      if (p.has("esiti")) {
        for (final JsonElement x : p.getAsJsonArray("esiti")) {
          sentSea += num(x.getAsJsonObject(), "via_mare");
        }
      }
    }
    o.addProperty("turni_con_teste_di_ponte", heads);
    o.addProperty("turni_peso_seguito", followed);
    o.addProperty("uk_mandate_teste_di_ponte", sent);
    o.addProperty("uk_mandate_teste_di_ponte_via_mare", sentSea);
    // allied (US) landings with British land units at the end of the round
    int landings = 0;
    int withUk = 0;
    int withUkHeld = 0;
    int withUkKnown = 0;
    if (b2.has("sbarchi_usa")) {
      for (final JsonElement e : b2.getAsJsonArray("sbarchi_usa")) {
        final JsonObject l = e.getAsJsonObject();
        landings++;
        if (l.get("britannici_fine_round").getAsInt() > 0) {
          withUk++;
          if (l.has("alleato_round_dopo")) {
            withUkKnown++;
            if (l.get("alleato_round_dopo").getAsBoolean()) {
              withUkHeld++;
            }
          }
        }
      }
    }
    o.addProperty("sbarchi_alleati", landings);
    o.addProperty("sbarchi_alleati_con_britannici", withUk);
    o.addProperty("sbarchi_alleati_con_britannici_noti_round_dopo", withUkKnown);
    o.addProperty("sbarchi_alleati_con_britannici_ancora_alleati", withUkHeld);
    // no harm: landed units USA + UK, British transports at R12, the UK's capital lost
    if (b2.has("p2bis")) {
      final JsonObject pb = b2.getAsJsonObject("p2bis");
      final JsonObject l = pb.getAsJsonObject("unita_sbarcate");
      o.addProperty(
          "unita_sbarcate_usa_uk",
          l.getAsJsonObject(MonitorP2.USA).get("totale").getAsInt()
              + l.getAsJsonObject(MonitorP2.UK).get("totale").getAsInt());
      final JsonObject w = pb.getAsJsonObject("trasporti_posseduti").getAsJsonObject(MonitorP2.UK);
      if (w.has("12")) {
        o.addProperty("trasporti_uk_r12", w.get("12").getAsInt());
      }
    }
    int ukLost = -1;
    if (json.has("capitali") && json.get("capitali").isJsonArray()) {
      for (final JsonElement e : json.getAsJsonArray("capitali")) {
        final JsonObject c = e.getAsJsonObject();
        if (CAPITALE_UK.equals(c.get("territorio").getAsString())
            && "caduta".equals(c.get("evento").getAsString())
            && ukLost < 0) {
          ukLost = c.get("round").getAsInt();
        }
      }
    }
    o.addProperty("regno_unito_perso", ukLost > 0);
    if (ukLost > 0) {
      o.addProperty("regno_unito_perso_round", ukLost);
    }
    // armies at R12
    final JsonObject s12 = snaps.get(12);
    if (s12 != null && s12.has("r4") && s12.getAsJsonObject("r4").has("esercito")) {
      o.add("esercito_r12", s12.getAsJsonObject("r4").get("esercito").deepCopy());
    }
    return o;
  }

  private static @Nullable JsonObject p2(@Nullable final JsonObject snap) {
    return snap != null && snap.has("p2") ? snap.getAsJsonObject("p2") : null;
  }

  private static int num(final JsonObject o, final String k) {
    return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
  }

  private static int total(final JsonObject perType) {
    int n = 0;
    for (final var e : perType.entrySet()) {
      if (e.getValue().isJsonPrimitive()) {
        n += e.getValue().getAsInt();
      }
    }
    return n;
  }

  private static List<JsonObject> decisioni(final List<JsonObject> rows, final String tipo) {
    return rows.stream().filter(d -> tipo.equals(d.get("tipo").getAsString())).toList();
  }

  // ======================================================================= the section

  static boolean applicabile(final List<Game> games) {
    return games.stream()
        .anyMatch(
            g ->
                g.json() != null
                    && g.json().has("p3")
                    && RiepilogoGenerale.strategistLabel(g.json(), "Allies").contains(P3));
  }

  static void write(final StringBuilder md, final List<Game> games, final List<String> alliances) {
    final Map<String, List<JsonObject>> byConfig = new LinkedHashMap<>();
    for (final Game g : games) {
      if (g.json() == null || !g.json().has("p3") || g.json().getAsJsonObject("p3").has("errore")) {
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
    md.append(
"""


### P3

""");
    md.append(
        "Regno Unito: Il Cairo tenuto e ponte come peso sui bersagli di Hard. Somme e medie del"
            + " blocco `p3` dei `riepilogo.json` (campo tra parentesi). Confronti: p3 (con"
            + " `regno_unito_p3`) contro la stessa Asse con gli Alleati di Hard (e2) e con i soli"
            + " `compiti` (p1).\n\n");
    md.append(
"""
| Configurazione | Partite | Asse | Alleati | Stalli | Quota Alleati [IC 95%] | contro e2 (Fisher) | contro p1 (Fisher) |
|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> list = e.getValue();
      final int n = list.size();
      final int axis = ConfrontoR4.wins(list, "Axis");
      final int allies = ConfrontoR4.wins(list, "Allies");
      final String hard = ConfrontoP1.senzaCompiti(e.getKey(), byConfig.keySet());
      final String p1 = e.getKey().contains(P3) ? senzaP3(e.getKey(), byConfig.keySet()) : null;
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
              fisher(
                  allies, n, hard == null || hard.equals(e.getKey()) ? null : byConfig.get(hard)))
          .append(" | ")
          .append(fisher(allies, n, p1 == null ? null : byConfig.get(p1)))
          .append(" |\n");
    }
    md.append(
"""

Criteri: 1 Egypt dell'Asse a fine R1 / R2 / R3 / R4 in partite (`egitto_asse_fine_round`), quota dei round con Egypt dell'Asse (`round_egitto_asse`/`round_con_fotografia`); britannici in Egypt a fine R1 (`uk_in_egitto_r1`); 2 sbarchi alleati (USA) con britannici a fine round (`sbarchi_alleati_con_britannici`/`sbarchi_alleati`) e ancora alleati un round dopo (`..._ancora_alleati`/`..._noti_round_dopo`); 3 unita' sbarcate USA+UK (`unita_sbarcate_usa_uk`), trasporti UK R12 (`trasporti_uk_r12`), United Kingdom perso (`regno_unito_perso`); unita' portate in Egypt mare / terra / aria (`portate_in_egitto_via_*`), PU per Il Cairo (`pu_cairo`), britanniche mandate sulle teste di ponte (`uk_mandate_teste_di_ponte`, via mare).

""");
    md.append(
"""
| Configurazione | 1 Egypt Asse R1 / R2 / R3 / R4 | 1 quota round | UK in Egypt R1 | 2 con UK / sbarchi | 2 ancora alleati | 3 sbarcate USA+UK | 3 trasporti UK R12 | 3 UK perso | portate mare / terra / aria | PU Cairo | UK su teste di ponte (mare) |
|---|---|---|---|---|---|---|---|---|---|---|---|
""");
    for (final var e : byConfig.entrySet()) {
      final List<JsonObject> b = e.getValue().stream().map(j -> j.getAsJsonObject("p3")).toList();
      final int n = Math.max(1, b.size());
      final List<String> eg = new ArrayList<>();
      for (final int r : ROUND_EGITTO) {
        eg.add(
            String.valueOf(
                b.stream()
                    .filter(
                        x ->
                            x.getAsJsonObject("egitto_asse_fine_round").has(String.valueOf(r))
                                && x.getAsJsonObject("egitto_asse_fine_round")
                                    .get(String.valueOf(r))
                                    .getAsBoolean())
                    .count()));
      }
      final double er = sum(b, x -> x.get("round_egitto_asse").getAsDouble());
      final double sr = sum(b, x -> x.get("round_con_fotografia").getAsDouble());
      final double withUk = sum(b, x -> x.get("sbarchi_alleati_con_britannici").getAsDouble());
      final double all = sum(b, x -> x.get("sbarchi_alleati").getAsDouble());
      final double known =
          sum(b, x -> x.get("sbarchi_alleati_con_britannici_noti_round_dopo").getAsDouble());
      final double held =
          sum(b, x -> x.get("sbarchi_alleati_con_britannici_ancora_alleati").getAsDouble());
      final List<JsonObject> with12 = b.stream().filter(x -> x.has("trasporti_uk_r12")).toList();
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(String.join(" / ", eg))
          .append(" su ")
          .append(b.size())
          .append(" | ")
          .append(pct(er, sr))
          .append(" | ")
          .append(f(sum(b, x -> Math.max(0, x.get("uk_in_egitto_r1").getAsDouble())) / n))
          .append(" | ")
          .append(Math.round(withUk))
          .append(" / ")
          .append(Math.round(all))
          .append(" (")
          .append(pct(withUk, all))
          .append(") | ")
          .append(Math.round(held))
          .append(" / ")
          .append(Math.round(known))
          .append(" (")
          .append(pct(held, known))
          .append(") | ")
          .append(f(sum(b, x -> x.get("unita_sbarcate_usa_uk").getAsDouble()) / n))
          .append(" | ")
          .append(
              with12.isEmpty()
                  ? "-"
                  : f(sum(with12, x -> x.get("trasporti_uk_r12").getAsDouble()) / with12.size()))
          .append(" | ")
          .append(b.stream().filter(x -> x.get("regno_unito_perso").getAsBoolean()).count())
          .append(" | ")
          .append(f(sum(b, x -> x.get("portate_in_egitto_via_mare").getAsDouble()) / n))
          .append(" / ")
          .append(f(sum(b, x -> x.get("portate_in_egitto_via_terra").getAsDouble()) / n))
          .append(" / ")
          .append(f(sum(b, x -> x.get("portate_in_egitto_via_aria").getAsDouble()) / n))
          .append(" | ")
          .append(f(sum(b, x -> x.get("pu_cairo").getAsDouble()) / n))
          .append(" | ")
          .append(f(sum(b, x -> x.get("uk_mandate_teste_di_ponte").getAsDouble()) / n))
          .append(" (")
          .append(f(sum(b, x -> x.get("uk_mandate_teste_di_ponte_via_mare").getAsDouble()) / n))
          .append(") |\n");
    }
  }

  /** The same configuration without {@code regno_unito_p3} (the P2-bis tasks), if present. */
  @Nullable
  static String senzaP3(final String config, final java.util.Set<String> configs) {
    final String plain = config.replace(" " + P3, "").replace(P3, "");
    return configs.contains(plain) ? plain : null;
  }

  private static String fisher(
      final int allies, final int n, @Nullable final List<JsonObject> other) {
    if (other == null) {
      return "-";
    }
    final int k = ConfrontoR4.wins(other, "Allies");
    return k
        + "/"
        + other.size()
        + ", p "
        + ConfrontoR5.pText(ConfrontoR4.fisher(allies, n, k, other.size()));
  }

  private static double sum(final List<JsonObject> b, final Function<JsonObject, Double> f) {
    double s = 0;
    for (final JsonObject x : b) {
      try {
        s += f.apply(x);
      } catch (final RuntimeException e) {
        // missing field = 0
      }
    }
    return s;
  }

  private static String f(final double x) {
    return String.format(Locale.ROOT, "%.1f", x).replace('.', ',');
  }

  private static String pct(final double k, final double n) {
    return n <= 0 ? "-" : Math.round(100.0 * k / n) + "%";
  }
}
