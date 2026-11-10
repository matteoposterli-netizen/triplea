package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.triplea.ai.proplus.strategia.llm.ErroreStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.UscitaLlm;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * Step STRATEGA LLM, contract 1.3: the answer of the strategic level ({@code portafoglio}, {@code
 * fuori_lista}, {@code rinforzi}, {@code motivazione}), read from the model's text and checked
 * field by field. Unknown candidates and operations are not an error here: the engine refuses them
 * with the reason in the diary.
 */
@UtilityClass
public final class StrategaDecisione {
  static final List<String> AZIONI = List.of("avvia", "continua", "modifica", "annulla");
  static final int MAX_VOCI = 12;
  static final int MAX_TESTO = 300;

  /**
   * The answer normalized (every field present, strings trimmed and cut, numbers in range).
   *
   * @throws ErroreStratega not valid (no JSON, unknown action, probability out of 0-1, missing
   *     fields)
   */
  public static JsonObject leggi(final String text) {
    final JsonObject root = UscitaLlm.leggi(text);
    final JsonObject out = new JsonObject();
    final JsonArray voci = new JsonArray();
    if (!root.has("portafoglio") || !root.get("portafoglio").isJsonArray()) {
      throw new ErroreStratega("portafoglio mancante");
    }
    for (final JsonElement e : root.getAsJsonArray("portafoglio")) {
      if (voci.size() >= MAX_VOCI) {
        break;
      }
      if (!e.isJsonObject()) {
        throw new ErroreStratega("voce del portafoglio non valida");
      }
      final JsonObject v = e.getAsJsonObject();
      final String action = string(v, "azione").toLowerCase(Locale.ROOT);
      if (!AZIONI.contains(action)) {
        throw new ErroreStratega("azione sconosciuta: " + action);
      }
      final JsonObject n = new JsonObject();
      n.addProperty("azione", action);
      n.addProperty("candidato", string(v, "candidato"));
      n.addProperty("operazione", string(v, "operazione"));
      if ("avvia".equals(action) && n.get("candidato").getAsString().isEmpty()) {
        throw new ErroreStratega("avvia senza candidato");
      }
      if (!"avvia".equals(action) && n.get("operazione").getAsString().isEmpty()) {
        throw new ErroreStratega(action + " senza operazione");
      }
      n.addProperty("round_obiettivo", Math.max(0, integer(v, "round_obiettivo")));
      n.addProperty("tetto_perdita_TUV", Math.max(0, integer(v, "tetto_perdita_TUV")));
      n.addProperty("durata_tenuta", Math.max(0, integer(v, "durata_tenuta")));
      n.addProperty("bersaglio_successivo", string(v, "bersaglio_successivo"));
      n.addProperty("prob_tenuta_stimata", prob(v));
      n.addProperty("motivazione", testo(v, "motivazione"));
      voci.add(n);
    }
    out.add("portafoglio", voci);
    final JsonObject f = new JsonObject();
    final JsonObject src =
        root.has("fuori_lista") && root.get("fuori_lista").isJsonObject()
            ? root.getAsJsonObject("fuori_lista")
            : new JsonObject();
    final boolean propose =
        src.has("proponi")
            && src.get("proponi").isJsonPrimitive()
            && src.get("proponi").getAsBoolean()
            && !"nessuno".equals(string(src, "tipo"));
    f.addProperty("proponi", propose);
    f.addProperty("tipo", propose ? string(src, "tipo").toLowerCase(Locale.ROOT) : "nessuno");
    f.addProperty("bersaglio", string(src, "bersaglio"));
    f.addProperty("apre", string(src, "apre"));
    f.addProperty("chiude", string(src, "chiude"));
    f.addProperty("prob_tenuta_stimata", propose ? prob(src) : 0);
    f.addProperty("motivazione", testo(src, "motivazione"));
    out.add("fuori_lista", f);
    final JsonArray reinf = new JsonArray();
    if (root.has("rinforzi") && root.get("rinforzi").isJsonArray()) {
      for (final JsonElement e : root.getAsJsonArray("rinforzi")) {
        if (e.isJsonObject() && !string(e.getAsJsonObject(), "territorio").isEmpty()) {
          final JsonObject r = new JsonObject();
          r.addProperty("territorio", string(e.getAsJsonObject(), "territorio"));
          r.addProperty("peso", Math.max(1, Math.min(3, integer(e.getAsJsonObject(), "peso"))));
          reinf.add(r);
        }
      }
    }
    out.add("rinforzi", reinf);
    // contract 1.5 (step C2): irreplaceable / expendable territories, at most 5 each
    for (final String key : java.util.List.of("irrinunciabili", "sacrificabili")) {
      final JsonArray list = new JsonArray();
      if (root.has(key) && root.get(key).isJsonArray()) {
        for (final JsonElement e : root.getAsJsonArray(key)) {
          if (e.isJsonObject() && !string(e.getAsJsonObject(), "territorio").isEmpty()) {
            final JsonObject r = new JsonObject();
            r.addProperty("territorio", string(e.getAsJsonObject(), "territorio"));
            r.addProperty("motivazione", testo(e.getAsJsonObject(), "motivazione"));
            list.add(r);
            if (list.size() >= 5) {
              break;
            }
          }
        }
      }
      out.add(key, list);
    }
    out.addProperty("motivazione", testo(root, "motivazione"));
    return out;
  }

  private static double prob(final JsonObject o) {
    final JsonElement p = o.get("prob_tenuta_stimata");
    if (p == null || !p.isJsonPrimitive() || !p.getAsJsonPrimitive().isNumber()) {
      throw new ErroreStratega("prob_tenuta_stimata mancante");
    }
    final double v = p.getAsDouble();
    if (Double.isNaN(v) || v < 0 || v > 1) {
      throw new ErroreStratega(
          String.format(Locale.ROOT, "prob_tenuta_stimata %.2f fuori da 0-1", v));
    }
    return Math.round(v * 1000) / 1000.0;
  }

  private static String string(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().strip() : "";
  }

  private static int integer(final JsonObject o, final String key) {
    try {
      return o.has(key) && o.get(key).isJsonPrimitive()
          ? (int) Math.round(o.get(key).getAsDouble())
          : 0;
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  private static String testo(final JsonObject o, final String key) {
    final String s = string(o, key).replaceAll("\\s+", " ");
    return s.length() > MAX_TESTO ? s.substring(0, MAX_TESTO) + "..." : s;
  }
}
