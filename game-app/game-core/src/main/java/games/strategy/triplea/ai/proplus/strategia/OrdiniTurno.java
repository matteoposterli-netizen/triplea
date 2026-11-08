package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.diario.DiarySettings;
import games.strategy.triplea.ai.proplus.strategia.llm.Modalita;
import games.strategy.triplea.ai.proplus.strategia.llm.Uso;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * LLM step, second level: the TURN ORDERS of a nation. At the start of the turn of each Pro+
 * nation with PUs (not those in {@code nazioni_senza_ordini} of the data file: Europe 1940 the
 * French), when its alliance uses an LLM mode, a short call gives only targets, operation
 * decisions and delay tools of the nation ({@link Direttive#applicaOrdini}); plan, posture and risk
 * stay those of the strategist of the round. Input: {@link Ingresso#ordiniTurno} (the strategic
 * directives, what changed since the alliance's last decision, the nation's compact state). Any
 * error = the nation plays the strategic directives (same fallback as the strategist).
 *
 * <p>The result is kept in the history entry of the round, key {@value #KEY}: {nation: {ordini,
 * direttive (merged), decisore}}; {@link PianoAttivo} reads the merged directives for that nation.
 * With the rules (or a file) nothing is asked; with the diary on, the input is still built (file
 * {@code ordini-ingresso-R<N>-<nation>.json}, used by the offline quality test).
 */
@UtilityClass
public final class OrdiniTurno {
  public static final String KEY = "ordini_turno";

  /**
   * What happened at the start of the turn of a nation.
   *
   * @param testo diary text ("" when nothing was asked)
   * @param json diary event
   * @param ingresso the input of the turn orders
   * @param chiamato whether the LLM was asked
   */
  public record Risultato(
      String alleanza,
      String nazione,
      int round,
      String testo,
      JsonObject json,
      JsonObject ingresso,
      boolean chiamato) {}

  /** The turn orders of {@code player}, made once at the start of its turn (real game data). */
  public static Optional<Risultato> inizioTurno(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    final Optional<Strategia> strategia = Strategie.forGame(data);
    if (alliance.isEmpty() || strategia.isEmpty()) {
      return Optional.empty();
    }
    final GameProperties props = data.getProperties();
    final Optional<StatoMaggiore.Stato> state = StatoMaggiore.getState(props, alliance.get());
    final int round = data.getSequence().getRound();
    if (state.isEmpty() || state.get().storico().isEmpty()) {
      return Optional.empty();
    }
    final JsonObject last =
        state.get().storico().get(state.get().storico().size() - 1).getAsJsonObject();
    if (!last.has("round")
        || last.get("round").getAsInt() != round
        || !last.has(Valutazione.KEY_DIRETTIVE)) {
      return Optional.empty();
    }
    final String name = player.getName();
    if (last.has(KEY) && last.getAsJsonObject(KEY).has(name)) {
      return Optional.empty(); // already decided this turn (a reloaded game)
    }
    final Optional<Strategia.Piano> plan =
        strategia.get().alleanza(alliance.get()).flatMap(a -> a.piano(state.get().piano()));
    if (plan.isEmpty()) {
      return Optional.empty();
    }
    final Modalita mode = Decisori.getModalita(props, alliance.get());
    if (!mode.isLlm() && !DiarySettings.isEnabled()) {
      return Optional.empty();
    }
    final JsonObject strategic = last.getAsJsonObject(Valutazione.KEY_DIRETTIVE);
    final JsonObject input =
        Ingresso.ordiniTurno(
            data, strategia.get(), plan.get(), alliance.get(), player, strategic, round);
    final boolean eligible =
        player.getResources().getQuantity(Constants.PUS) > 0
            && !strategia.get().getParametri().nazioniSenzaOrdini().contains(name);
    final Decisore decider = mode.isLlm() && eligible ? Decisori.per(props, alliance.get()) : null;
    if (!(decider instanceof DecisoreLlm llm)) {
      Ingresso.salvaIstantanea(data, alliance.get());
      final JsonObject json = event(alliance.get(), name, round);
      json.addProperty("chiamato", false);
      return Optional.of(new Risultato(alliance.get(), name, round, "", json, input, false));
    }
    final List<String> notes = new ArrayList<>();
    JsonObject decided = null;
    try {
      final JsonObject raw = llm.ordini(input, props, alliance.get(), name, round);
      decided =
          Direttive.applicaOrdini(
              strategic,
              raw,
              data,
              strategia.get(),
              plan.get(),
              alliance.get(),
              name,
              round,
              notes);
    } catch (final Decisore.NonDisponibile e) {
      notes.add(
          "ordini del turno non disponibili ("
              + e.getMessage()
              + "): valgono le direttive dello stratega");
    } catch (final RuntimeException e) {
      notes.add(
          "ordini del turno non validi ("
              + e.getMessage()
              + "): valgono le direttive dello stratega");
    }
    final boolean fallback = decided == null;
    final String source = fallback ? Decisore.REGOLE : llm.etichetta();
    final JsonObject who = new JsonObject();
    who.addProperty("fonte", source);
    who.addProperty("ripiego", fallback);
    final JsonArray notesJson = new JsonArray();
    notes.forEach(notesJson::add);
    who.add("avvisi", notesJson);
    final JsonObject own = new JsonObject();
    if (!fallback) {
      own.add("ordini", decided.getAsJsonObject("ordini"));
      own.add(Valutazione.KEY_DIRETTIVE, decided.getAsJsonObject(Valutazione.KEY_DIRETTIVE));
    }
    own.add("decisore", who);
    StatoMaggiore.aggiornaUltimo(
        data,
        alliance.get(),
        entry -> {
          if (!entry.has(KEY)) {
            entry.add(KEY, new JsonObject());
          }
          entry.getAsJsonObject(KEY).add(name, own);
        });
    Ingresso.salvaIstantanea(data, alliance.get());

    final StringBuilder text = new StringBuilder();
    notes.forEach(n -> text.append("  Avviso degli ordini del turno: ").append(n).append('\n'));
    if (fallback) {
      text.append("  Ordini del turno ")
          .append(name)
          .append(": nessuno, valgono le direttive dello stratega\n");
    } else {
      final JsonObject orders = decided.getAsJsonObject("ordini");
      text.append("  Ordini del turno ")
          .append(name)
          .append(" (")
          .append(source)
          .append("): ")
          .append(Direttive.testoOrdini(orders))
          .append('\n');
      final String why = orders.has("motivazione") ? orders.get("motivazione").getAsString() : "";
      if (!why.isEmpty()) {
        text.append("    Motivazione: ").append(why).append('\n');
      }
    }
    text.append("  Stratega, uso della partita finora: ")
        .append(Uso.testo(Uso.leggi(props)))
        .append('\n');
    final JsonObject json = event(alliance.get(), name, round);
    json.addProperty("chiamato", true);
    json.add("ordini", own);
    json.add("uso", Uso.leggi(props));
    return Optional.of(
        new Risultato(alliance.get(), name, round, text.toString(), json, input, true));
  }

  private static JsonObject event(final String alliance, final String nation, final int round) {
    final JsonObject json = new JsonObject();
    json.addProperty("evento", KEY);
    json.addProperty("alleanza", alliance);
    json.addProperty("nazione", nation);
    json.addProperty("round", round);
    return json;
  }
}
