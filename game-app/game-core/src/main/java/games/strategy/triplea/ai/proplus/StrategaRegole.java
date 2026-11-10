package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.triplea.ai.proplus.strategia.llm.Richiesta;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Step R1, rule-based baseline of the "Pro+ Stratega LLM" (no network, cost 0; {@code
 * -Dproplus.stratega.regole=true}, set only by the autoplay with {@code --stratega-regole}): tells
 * whether the LLM adds anything over a plain rule on the same engine numbers.
 *
 * <ul>
 *   <li>strategic level: cancels the operations in progress whose engine success now is under
 *       {@value #SUCCESSO_MINIMO}%, continues the others, then launches the candidates with the
 *       highest positive alliance value up to the maximum of operations; no operation outside the
 *       list, no reinforcement weights, empty lists;
 *   <li>turn orders: the engine's own action at the decision point.
 * </ul>
 */
final class StrategaRegole implements DecisoreOperazione.Trasporto {
  static final String PROPERTY = "proplus.stratega.regole";

  /** Operations in progress under this engine success (%) are cancelled. */
  static final double SUCCESSO_MINIMO = 30;

  static boolean attivo() {
    return Boolean.getBoolean(PROPERTY);
  }

  @Override
  public Optional<String> nonDisponibile() {
    return Optional.empty();
  }

  @Override
  public Risposta chiama(final Richiesta r) {
    final String text = r.utente();
    final JsonObject state =
        JsonParser.parseString(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            .getAsJsonObject();
    return new Risposta(
        state.has("candidati") ? strategico(state).toString() : ordine(state).toString(),
        "Regole",
        "regole",
        0,
        0,
        0,
        0,
        0,
        false,
        0);
  }

  static JsonObject strategico(final JsonObject state) {
    final JsonArray p = new JsonArray();
    int active = 0;
    if (state.has("operazioni_in_corso")) {
      for (final JsonElement e : state.getAsJsonArray("operazioni_in_corso")) {
        final JsonObject o = e.getAsJsonObject();
        final String id = o.get("operazione").getAsString();
        final double now = successo(o.get("stima_motore_ora"));
        if (now >= 0 && now < SUCCESSO_MINIMO) {
          p.add(voce("annulla", "", id, 0, "regola: successo stimato " + now + "% < 30%"));
        } else {
          p.add(voce("continua", "", id, Math.max(0, now) / 100, "regola: continua"));
          active++;
        }
      }
    }
    final List<JsonObject> cands = new ArrayList<>();
    state.getAsJsonArray("candidati").forEach(e -> cands.add(e.getAsJsonObject()));
    cands.removeIf(c -> valore(c) <= 0);
    cands.sort(Comparator.comparingDouble((JsonObject c) -> -valore(c)));
    final int max = StrategaConfig.get().maxOperazioni();
    for (final JsonObject c : cands) {
      if (active >= max) {
        break;
      }
      p.add(
          voce(
              "avvia",
              c.get("id").getAsString(),
              "",
              Math.max(0, successo(c.get("stima_motore"))) / 100,
              "regola: valore d'alleanza " + valore(c) + " PU"));
      active++;
    }
    final JsonObject a = new JsonObject();
    a.add("portafoglio", p);
    final JsonObject f = new JsonObject();
    f.addProperty("proponi", false);
    f.addProperty("tipo", "nessuno");
    f.addProperty("bersaglio", "");
    f.addProperty("apre", "");
    f.addProperty("chiude", "");
    f.addProperty("prob_tenuta_stimata", 0);
    f.addProperty("motivazione", "");
    a.add("fuori_lista", f);
    a.add("rinforzi", new JsonArray());
    a.add("irrinunciabili", new JsonArray());
    a.add("sacrificabili", new JsonArray());
    a.addProperty("motivazione", "linea di base a regole");
    return a;
  }

  /** The engine's alliance value of a candidate (PU), -inf if missing. */
  static double valore(final JsonObject c) {
    if (c.has("stima_motore") && c.get("stima_motore").isJsonObject()) {
      final JsonObject st = c.getAsJsonObject("stima_motore");
      if (st.has("valore_alleanza") && st.get("valore_alleanza").isJsonObject()) {
        final JsonObject v = st.getAsJsonObject("valore_alleanza");
        if (v.has("totale_PU")) {
          return v.get("totale_PU").getAsDouble();
        }
      }
    }
    return Double.NEGATIVE_INFINITY;
  }

  private static double successo(final JsonElement e) {
    return StimaUnica.successo(e);
  }

  private static JsonObject voce(
      final String action,
      final String cand,
      final String op,
      final double prob,
      final String why) {
    final JsonObject v = new JsonObject();
    v.addProperty("azione", action);
    v.addProperty("candidato", cand);
    v.addProperty("operazione", op);
    v.addProperty("round_obiettivo", 0);
    v.addProperty("tetto_perdita_TUV", 0);
    v.addProperty("durata_tenuta", 0);
    v.addProperty("bersaglio_successivo", "");
    v.addProperty("prob_tenuta_stimata", Math.round(prob * 100) / 100.0);
    v.addProperty("motivazione", why);
    return v;
  }

  /** The engine's own action at a decision point (else the first allowed one). */
  private static JsonObject ordine(final JsonObject state) {
    String action = null;
    for (final String key : List.of("stima_motore_azione", "formula_v2")) {
      if (state.has(key) && state.get(key).isJsonObject()) {
        action = state.getAsJsonObject(key).get("azione").getAsString();
        break;
      }
    }
    if (action == null) {
      final String allowed =
          state.has("azioni_ammesse") ? state.get("azioni_ammesse").getAsString() : "tieni";
      action = allowed.split("\\|")[0].strip();
    }
    final JsonObject d = new JsonObject();
    d.addProperty("azione", action);
    d.addProperty("prob_tenuta_stimata", 0.5);
    d.addProperty("motivazione", "regola: azione del motore");
    final JsonObject o = new JsonObject();
    o.add("decisione_operazione", d);
    return o;
  }
}
