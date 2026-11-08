package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.triplea.ai.proplus.strategia.Direttive;
import games.strategy.triplea.ai.proplus.strategia.Tattiche;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * The strategist's answer: text -> JSON object ({@link #leggi}) -> directives v1 ({@link
 * #direttive}); {@link #controlla} checks them against the strategic state alone (no game data:
 * the offline quality test). In the game the directives are then checked by {@link
 * Direttive#valida} with the guard rails.
 */
@UtilityClass
public final class UscitaLlm {

  /** The JSON object in {@code text} (code fences and text around it tolerated). */
  public static JsonObject leggi(final String text) {
    final String t = text == null ? "" : text.strip();
    final int start = t.indexOf('{');
    final int end = t.lastIndexOf('}');
    if (start < 0 || end <= start) {
      throw new ErroreStratega("risposta senza JSON");
    }
    try {
      final JsonElement json = JsonParser.parseString(t.substring(start, end + 1));
      if (!json.isJsonObject()) {
        throw new ErroreStratega("la risposta non e' un oggetto JSON");
      }
      return json.getAsJsonObject();
    } catch (final com.google.gson.JsonParseException e) {
      throw new ErroreStratega("JSON non valido nella risposta");
    }
  }

  /**
   * Directives v1 from the answer schema of {@link PromptStratega#schema} (lists of pairs -> maps,
   * empty front -> all fronts). Fields already in the v1 form are kept.
   */
  public static JsonObject direttive(final JsonObject in, final int round, final String alleanza) {
    final JsonObject out = new JsonObject();
    out.addProperty("versione", Direttive.VERSIONE);
    out.addProperty("alleanza", alleanza);
    out.addProperty("round", round);
    for (final String key : List.of("piano", "postura", "operazioni", "bersagli", "motivazione")) {
      if (in.has(key) && !in.get(key).isJsonNull()) {
        out.add(key, in.get(key).deepCopy());
      }
    }
    if (in.has("rischio")) {
      final JsonObject risk = risk(in.get("rischio"));
      if (risk.entrySet().stream().anyMatch(e -> e.getValue().isJsonObject())) {
        out.addProperty("versione", Direttive.VERSIONE_1_1);
      }
      out.add("rischio", risk);
    }
    if (in.has("ruoli")) {
      out.add("ruoli", pairs(in.get("ruoli"), "nazione", "ruolo"));
    }
    if (in.has("tattiche") && in.get("tattiche").isJsonArray()) {
      final JsonArray tools = new JsonArray();
      for (final JsonElement e : in.getAsJsonArray("tattiche")) {
        final JsonObject t = e.getAsJsonObject().deepCopy();
        if (t.has("fronte") && t.get("fronte").getAsString().isBlank()) {
          t.remove("fronte");
        }
        t.addProperty("perche", "stratega");
        tools.add(t);
      }
      out.add("tattiche", tools);
    }
    if (in.has("lettura_nemico") && in.get("lettura_nemico").isJsonObject()) {
      out.add("lettura_nemico", in.get("lettura_nemico").deepCopy());
    }
    return out;
  }

  /**
   * Risk list -> directives v1.1: {front: value} for the whole alliance (empty nation) and {nation:
   * {front: value}} per nation. An object (already v1 / v1.1) is kept.
   */
  static JsonObject risk(final JsonElement e) {
    if (e.isJsonObject()) {
      return e.getAsJsonObject().deepCopy();
    }
    final JsonObject map = new JsonObject();
    if (e.isJsonArray()) {
      for (final JsonElement p : e.getAsJsonArray()) {
        final JsonObject o = p.getAsJsonObject();
        if (!o.has("fronte") || !o.has("valore")) {
          continue;
        }
        final String nation = o.has("nazione") ? o.get("nazione").getAsString().strip() : "";
        if (nation.isEmpty()) {
          map.add(o.get("fronte").getAsString(), o.get("valore"));
        } else {
          if (!map.has(nation) || !map.get(nation).isJsonObject()) {
            map.add(nation, new JsonObject());
          }
          map.getAsJsonObject(nation).add(o.get("fronte").getAsString(), o.get("valore"));
        }
      }
    }
    return map;
  }

  private static JsonObject pairs(final JsonElement e, final String key, final String value) {
    if (e.isJsonObject()) {
      return e.getAsJsonObject().deepCopy();
    }
    final JsonObject map = new JsonObject();
    if (e.isJsonArray()) {
      for (final JsonElement p : e.getAsJsonArray()) {
        final JsonObject o = p.getAsJsonObject();
        if (o.has(key) && o.has(value)) {
          map.add(o.get(key).getAsString(), o.get(value));
        }
      }
    }
    return map;
  }

  /**
   * Offline check of directives v1 against the strategic state that asked for them.
   *
   * @param errori what makes them unusable (the game would fall back to the rules)
   * @param limitati what the guard rails would cut or ignore (the game applies them)
   */
  public record Controllo(List<String> errori, List<String> limitati) {
    public boolean valido() {
      return errori.isEmpty();
    }
  }

  public static Controllo controlla(final JsonObject d, final JsonObject stato) {
    final List<String> errors = new ArrayList<>();
    final List<String> limited = new ArrayList<>();
    final Set<String> plans = new HashSet<>();
    String inForce = "";
    for (final JsonElement e : array(stato, "piani_ammessi")) {
      final JsonObject p = e.getAsJsonObject();
      final boolean current = p.has("in_vigore") && p.get("in_vigore").getAsBoolean();
      if (current) {
        inForce = p.get("id").getAsString();
      }
      if (current || (p.has("ammesso") && p.get("ammesso").getAsBoolean())) {
        plans.add(p.get("id").getAsString());
      }
    }
    final String plan = string(d, "piano");
    if (plan.isEmpty() || !(plans.contains(plan) || plan.equals(inForce))) {
      errors.add("piano non ammesso: " + plan);
    }
    final String posture = string(d, "postura");
    if (!Direttive.POSTURE.contains(posture)) {
      errors.add("postura sconosciuta: " + posture);
    }
    final Set<String> fronts = strings(array(stato, "fronti"));
    final String allianceName = stato.has("alleanza") ? stato.get("alleanza").getAsString() : "";
    final Set<String> allianceNations =
        stato.has("nazioni") && stato.getAsJsonObject("nazioni").has(allianceName)
            ? strings(stato.getAsJsonObject("nazioni").getAsJsonArray(allianceName))
            : Set.of();
    if (d.has("rischio") && d.get("rischio").isJsonObject()) {
      d.getAsJsonObject("rischio")
          .entrySet()
          .forEach(
              e -> {
                if (e.getValue().isJsonObject()) {
                  if (!allianceNations.contains(e.getKey())) {
                    limited.add("rischio per una nazione non dell'alleanza " + e.getKey());
                    return;
                  }
                  e.getValue()
                      .getAsJsonObject()
                      .entrySet()
                      .forEach(f -> checkRisk(f.getKey(), f.getValue(), fronts, limited));
                } else {
                  checkRisk(e.getKey(), e.getValue(), fronts, limited);
                }
              });
    }
    final Set<String> ops = new HashSet<>();
    array(stato, "operazioni").forEach(o -> ops.add(o.getAsJsonObject().get("id").getAsString()));
    double loss = 0;
    for (final JsonElement e : array(d, "operazioni")) {
      final JsonObject o = e.getAsJsonObject();
      if (!ops.contains(string(o, "id"))) {
        limited.add("operazione sconosciuta " + string(o, "id"));
      }
      if (!Direttive.DECISIONI.contains(string(o, "decisione"))) {
        errors.add("decisione sconosciuta: " + string(o, "decisione"));
      }
      final double l = number(o, "perdita_trasporti_accettata_max_TUV");
      if (l < 0 || l > Direttive.PERDITA_TRASPORTI_MAX) {
        limited.add("perdita accettata " + l + " fuori da 0.." + Direttive.PERDITA_TRASPORTI_MAX);
      }
      if (Direttive.LANCIA.equals(string(o, "decisione"))) {
        loss += Math.max(0, Math.min(Direttive.PERDITA_TRASPORTI_MAX, l));
      }
    }
    if (loss > Direttive.PERDITA_TRASPORTI_MAX) {
      limited.add("perdite accettate " + loss + " oltre il tetto per alleanza");
    }
    for (final JsonElement e : array(d, "bersagli")) {
      final double w = number(e.getAsJsonObject(), "peso");
      if (w < 0 || w > Direttive.PESO_MAX) {
        limited.add("peso " + w + " fuori da 0.." + Direttive.PESO_MAX);
      }
    }
    final String alliance = stato.has("alleanza") ? stato.get("alleanza").getAsString() : "";
    final Set<String> nations =
        stato.has("nazioni") && stato.getAsJsonObject("nazioni").has(alliance)
            ? strings(stato.getAsJsonObject("nazioni").getAsJsonArray(alliance))
            : Set.of();
    for (final JsonElement e : array(d, "tattiche")) {
      final JsonObject t = e.getAsJsonObject();
      if (!Tattiche.STRUMENTI.contains(string(t, "strumento"))) {
        limited.add("strumento sconosciuto " + string(t, "strumento"));
      } else if (!nations.contains(string(t, "nazione"))) {
        limited.add("strumento per una nazione non dell'alleanza " + string(t, "nazione"));
      } else if (t.has("fronte") && !fronts.contains(string(t, "fronte"))) {
        limited.add("strumento su fronte sconosciuto " + string(t, "fronte"));
      }
    }
    if (d.has("lettura_nemico")) {
      final double p = number(d.getAsJsonObject("lettura_nemico"), "probabilita");
      if (p < 0 || p > 1) {
        limited.add("probabilita' della lettura fuori da 0..1");
      }
    }
    if (frasi(string(d, "motivazione")) > Direttive.FRASI_MAX) {
      limited.add("motivazione oltre " + Direttive.FRASI_MAX + " frasi");
    }
    if (string(d, "motivazione").isBlank()) {
      limited.add("motivazione vuota");
    }
    return new Controllo(errors, limited);
  }

  private static void checkRisk(
      final String front,
      final JsonElement value,
      final Set<String> fronts,
      final List<String> limited) {
    if (!fronts.contains(front)) {
      limited.add("fronte sconosciuto " + front);
    } else if (Math.abs(value.getAsDouble()) > 1) {
      limited.add("rischio fuori da -1..+1 su " + front);
    }
  }

  /**
   * Offline check of turn orders (schema ordini-turno-v1) against their input: unusable = not the
   * expected fields; limited = ignored fields (plan, posture, risk), operations not of the nation,
   * weights or losses out of range, unknown tools or fronts, too long a motivation.
   */
  public static Controllo controllaOrdini(final JsonObject o, final JsonObject ingresso) {
    final List<String> errors = new ArrayList<>();
    final List<String> limited = new ArrayList<>();
    for (final String key : List.of("bersagli", "operazioni", "tattiche")) {
      if (o.has(key) && !o.get(key).isJsonArray()) {
        errors.add(key + " non e' una lista");
      }
    }
    if (!errors.isEmpty()) {
      return new Controllo(errors, limited);
    }
    for (final String key : List.of("piano", "postura", "rischio")) {
      if (o.has(key)) {
        limited.add("campo " + key + " ignorato negli ordini del turno");
      }
    }
    final Set<String> ops = new HashSet<>();
    array(ingresso, "operazioni").forEach(x -> ops.add(x.getAsJsonObject().get("id").getAsString()));
    double loss = 0;
    for (final JsonElement e : array(o, "operazioni")) {
      final JsonObject x = e.getAsJsonObject();
      if (!ops.contains(string(x, "id"))) {
        limited.add("operazione non della nazione " + string(x, "id"));
      }
      if (!Direttive.DECISIONI.contains(string(x, "decisione"))) {
        limited.add("decisione sconosciuta " + string(x, "decisione"));
      }
      final double l = number(x, "perdita_trasporti_accettata_max_TUV");
      if (l < 0 || l > Direttive.PERDITA_TRASPORTI_MAX) {
        limited.add("perdita accettata " + l + " fuori da 0.." + Direttive.PERDITA_TRASPORTI_MAX);
      }
      loss += Math.max(0, l);
    }
    if (loss > Direttive.PERDITA_TRASPORTI_MAX) {
      limited.add("perdite accettate " + loss + " oltre il tetto");
    }
    for (final JsonElement e : array(o, "bersagli")) {
      final double w = number(e.getAsJsonObject(), "peso");
      if (w < 0 || w > Direttive.PESO_MAX) {
        limited.add("peso " + w + " fuori da 0.." + Direttive.PESO_MAX);
      }
    }
    final Set<String> fronts = strings(array(ingresso, "fronti"));
    for (final JsonElement e : array(o, "tattiche")) {
      final JsonObject t = e.getAsJsonObject();
      if (!Tattiche.STRUMENTI.contains(string(t, "strumento"))) {
        limited.add("strumento sconosciuto " + string(t, "strumento"));
      } else if (!string(t, "fronte").isBlank() && !fronts.contains(string(t, "fronte"))) {
        limited.add("strumento su fronte sconosciuto " + string(t, "fronte"));
      }
    }
    if (frasi(string(o, "motivazione")) > 2) {
      limited.add("motivazione oltre 2 frasi");
    }
    return new Controllo(errors, limited);
  }

  static int frasi(final String text) {
    final String t = text.strip();
    int count = 0;
    for (int i = 0; i < t.length(); i++) {
      final char c = t.charAt(i);
      if ((c == '.' || c == '!' || c == '?')
          && (i + 1 == t.length() || Character.isWhitespace(t.charAt(i + 1)))) {
        count++;
      }
    }
    return count == 0 && !t.isEmpty() ? 1 : count;
  }

  private static JsonArray array(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
  }

  private static Set<String> strings(final JsonArray a) {
    final Set<String> s = new HashSet<>();
    a.forEach(
        e ->
            s.add(
                e.isJsonObject() && e.getAsJsonObject().has("nome")
                    ? e.getAsJsonObject().get("nome").getAsString()
                    : e.getAsString()));
    return s;
  }

  private static String string(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
  }

  private static double number(final JsonObject o, final String key) {
    try {
      return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
    } catch (final NumberFormatException e) {
      return Double.NaN;
    }
  }

  /** Eg "Haiku" -> "haiku" (keys of the usage tally). */
  static String key(final String fonte) {
    return fonte.toLowerCase(Locale.ROOT).replace(' ', '_');
  }
}
