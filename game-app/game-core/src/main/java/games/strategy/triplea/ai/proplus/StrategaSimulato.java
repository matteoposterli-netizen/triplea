package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.triplea.ai.proplus.strategia.llm.Richiesta;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import java.util.Optional;

/**
 * Step STRATEGA LLM, tests and local checks only: a fake model without network ({@code
 * -Dproplus.stratega.simulato=true}, never set for the players). Strategic level: launches the
 * first two candidates (one of them a hold if any) and continues the operations in progress; turn
 * orders: the first allowed action (launch, go on, keep). Cost 0.001 USD a call.
 */
final class StrategaSimulato implements DecisoreOperazione.Trasporto {
  static final String PROPERTY = "proplus.stratega.simulato";

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
        "Simulato",
        "simulato",
        1000,
        100,
        0,
        0,
        0.001,
        false,
        5);
  }

  static JsonObject strategico(final JsonObject state) {
    final JsonObject a = new JsonObject();
    final JsonArray p = new JsonArray();
    for (final JsonElement e : state.getAsJsonArray("operazioni_in_corso")) {
      p.add(voce("continua", "", e.getAsJsonObject().get("operazione").getAsString()));
    }
    // two candidates of different types, landings and reinforcements first (to exercise them)
    final java.util.List<JsonObject> cands = new java.util.ArrayList<>();
    state.getAsJsonArray("candidati").forEach(e -> cands.add(e.getAsJsonObject()));
    final java.util.List<String> order =
        java.util.List.of("sbarco", "rinforzo", "offensiva", "tenuta");
    cands.sort(java.util.Comparator.comparingInt(c -> order.indexOf(c.get("tipo").getAsString())));
    final java.util.Set<String> types = new java.util.HashSet<>();
    for (final JsonObject c : cands) {
      if (types.size() >= 2) {
        break;
      }
      if (types.add(c.get("tipo").getAsString())) {
        p.add(voce("avvia", c.get("id").getAsString(), ""));
      }
    }
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
    a.addProperty("motivazione", "risposta simulata");
    return a;
  }

  private static JsonObject voce(final String action, final String cand, final String op) {
    final JsonObject v = new JsonObject();
    v.addProperty("azione", action);
    v.addProperty("candidato", cand);
    v.addProperty("operazione", op);
    v.addProperty("round_obiettivo", 0);
    v.addProperty("tetto_perdita_TUV", 0);
    v.addProperty("durata_tenuta", 0);
    v.addProperty("bersaglio_successivo", "");
    v.addProperty("prob_tenuta_stimata", 0.6);
    v.addProperty("motivazione", "simulata");
    return v;
  }

  private static JsonObject ordine(final JsonObject state) {
    final String allowed =
        state.has("azioni_ammesse") ? state.get("azioni_ammesse").getAsString() : "tieni";
    final JsonObject d = new JsonObject();
    d.addProperty("azione", allowed.split("\\|")[0].strip());
    d.addProperty("prob_tenuta_stimata", 0.6);
    d.addProperty("motivazione", "simulata");
    final JsonObject o = new JsonObject();
    o.add("decisione_operazione", d);
    return o;
  }
}
