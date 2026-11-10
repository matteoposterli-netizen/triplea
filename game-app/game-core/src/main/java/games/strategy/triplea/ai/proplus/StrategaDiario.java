package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Step STRATEGA LLM: the strategist's diary of the game, next to the Pro+ diary ({@code
 * diario/<partita>/stratega.txt}, {@code stratega.jsonl}, {@code stratega-riepilogo.json}): state
 * sent in summary, decisions, estimates, reasons, outcomes, cost.
 */
@UtilityClass
final class StrategaDiario {

  /** The strategic decision of a round. */
  static void scrivi(final GameData data, final JsonObject record, final List<String> lines) {
    final List<String> text = new ArrayList<>();
    text.add("");
    text.add(
        "=== R"
            + record.get("round").getAsInt()
            + " "
            + record.get("alleanza").getAsString()
            + " - livello strategico ===");
    text.addAll(lines);
    if (record.has("costo_usd")) {
      text.add(
          String.format(
              Locale.ROOT,
              "  (chiamata: %s, %d token, %.1f s, $%.4f)",
              record.get("fonte").getAsString(),
              record.get("token").getAsLong(),
              record.get("ms").getAsLong() / 1000.0,
              record.get("costo_usd").getAsDouble()));
    }
    if (record.has("decisioni")) {
      for (final JsonElement e : record.getAsJsonArray("decisioni")) {
        final JsonObject d = e.getAsJsonObject();
        if ("rifiutata".equals(d.has("esito") ? d.get("esito").getAsString() : "")) {
          text.add(
              "  RIFIUTATA "
                  + d.get("azione").getAsString()
                  + " "
                  + (d.has("candidato") ? d.get("candidato").getAsString() : "")
                  + (d.has("bersaglio") ? d.get("bersaglio").getAsString() : "")
                  + ": "
                  + d.get("motivo_rifiuto").getAsString());
        }
      }
    }
    final JsonObject u = Stratega.usoTotale(data.getProperties());
    text.add(
        String.format(
            Locale.ROOT,
            "  Costo della partita finora: $%.4f su $%.2f (%d chiamate)",
            u.get("costo_usd").getAsDouble(),
            u.get("tetto_usd").getAsDouble(),
            u.get("chiamate").getAsInt()));
    DiaryRecorder.stratega(data, text, record, riepilogo(data));
  }

  /**
   * Step COERENZA STIME: the record of a round whose diary failed to be written in full: the lines
   * and the decision record without the summaries, never throws.
   */
  static void scriviMinimo(
      final GameData data, final JsonObject record, final List<String> lines, final Exception e) {
    try {
      final List<String> text = new ArrayList<>();
      text.add("");
      text.add(
          "=== R"
              + (record.has("round") ? record.get("round").getAsString() : "?")
              + " "
              + (record.has("alleanza") ? record.get("alleanza").getAsString() : "?")
              + " - livello strategico (diario ridotto: "
              + e
              + ") ===");
      text.addAll(lines);
      record.addProperty("errore_diario", String.valueOf(e));
      DiaryRecorder.stratega(data, text, record, null);
    } catch (final RuntimeException again) {
      games.strategy.triplea.ai.pro.logging.ProLogger.warn(
          "[Pro+] Stratega: diario ridotto non scritto: " + again);
    }
  }

  /**
   * End of the turn of a nation of the strategist: the events of the alliance's operations not
   * written yet (decisions of the turn orders with estimates and reasons, steps, outcomes).
   */
  static void fineTurno(final GameState data, final GamePlayer player, final String logLine) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return;
    }
    final GameProperties props = data.getProperties();
    final List<String> text = new ArrayList<>();
    text.add("--- R" + data.getSequence().getRound() + " fine turno " + player.getName() + " ---");
    final JsonArray events = new JsonArray();
    for (final JsonObject o : Stratega.storicoOperazioni(props, alliance.get())) {
      final String id = o.get("id").getAsString();
      final Registro reg = Registro.leggi(props, id);
      final JsonArray ev = reg.eventi();
      final int done = reg.intOf("eventi_nel_diario_stratega", 0);
      if (ev.size() <= done) {
        continue;
      }
      for (int i = done; i < ev.size(); i++) {
        final JsonObject e = ev.get(i).getAsJsonObject();
        if (e.has("testo")) {
          text.add("  " + e.get("testo").getAsString());
        }
        final JsonObject x = e.deepCopy();
        x.addProperty("operazione", id);
        events.add(x);
      }
      reg.set("eventi_nel_diario_stratega", ev.size());
      reg.salva(props);
    }
    text.add("  " + logLine);
    final JsonObject rec = new JsonObject();
    rec.addProperty("tipo", "fine_turno");
    rec.addProperty("round", data.getSequence().getRound());
    rec.addProperty("nazione", player.getName());
    rec.add("eventi", events);
    DiaryRecorder.stratega(data, text, rec, data instanceof GameData gd ? riepilogo(gd) : null);
  }

  /**
   * Portfolios of both alliances with estimates and outcomes, and the cost: rewritten each time.
   */
  static JsonObject riepilogo(final GameState data) {
    final GameProperties props = data.getProperties();
    final JsonObject o = new JsonObject();
    o.addProperty("round", data.getSequence().getRound());
    for (final String a : ProPlusSettings.configurableAlliances(data)) {
      if (Stratega.nazioni(props, a).isEmpty()) {
        continue;
      }
      final JsonObject x = new JsonObject();
      x.add("nazioni", StrategaCandidati.array(Stratega.nazioni(props, a)));
      final JsonArray ops = new JsonArray();
      for (final JsonObject op : Stratega.storicoOperazioni(props, a)) {
        final JsonObject c = op.deepCopy();
        final Registro reg = Registro.leggi(props, op.get("id").getAsString());
        c.addProperty("stato", reg.stato().testo());
        c.addProperty("presa", reg.flag("presa"));
        c.addProperty("rinvii", reg.rinvii());
        for (final String k :
            List.of(
                "tenuta_esito",
                "motivo_fine",
                "tappa1_round",
                "tappa2_round",
                "tappa_motore",
                "tenuta_motore",
                "presa_senza_operazione",
                "chiusa_dal_motore",
                "annullata_dal_motore",
                "fallita_prima_della_decisione",
                "causa_annullamento",
                "causa_testo",
                "stima_avvio",
                "stima_decisione_prima",
                "stima_decisione_ultima",
                "blocchi_tetto")) {
          if (reg.json().has(k)) {
            c.add(k, reg.json().get(k));
          }
        }
        if (reg.json().has("decisioni")) {
          c.add("decisioni_ordini", reg.json().get("decisioni"));
        }
        ops.add(c);
      }
      x.add("operazioni", ops);
      final JsonObject pf = Stratega.portafoglio(props, a);
      x.add("rifiutate", pf.has("rifiutate") ? pf.get("rifiutate") : new JsonArray());
      x.addProperty("round_decisi", pf.has("round_decisi") ? pf.get("round_decisi").getAsInt() : 0);
      x.addProperty(
          "round_senza_llm", pf.has("round_senza_llm") ? pf.get("round_senza_llm").getAsInt() : 0);
      x.add("blocchi", Stratega.blocchi(props, a));
      o.add(a, x);
    }
    o.add("uso", Stratega.usoTotale(props));
    if (props.get(Stratega.KEY_AVVISO) instanceof String s) {
      o.addProperty("avviso", s);
    }
    return o;
  }
}
