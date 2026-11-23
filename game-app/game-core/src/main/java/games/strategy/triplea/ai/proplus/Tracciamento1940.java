package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import java.io.Serializable;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Step PF1: what a "Pro+ (Europe 1940)" game is really playing, readable from the saved game. At
 * the start of every turn of the player type the game property {@value #KEY}{@code <alliance>}
 * (JSON, saved in the .tsvg) gets the switches on for the alliance ({@code interruttori_attivi})
 * and the player type of every nation; the same data goes to the register of the decisions (diary
 * folder) and, once per game and alliance, to one log line. Only bookkeeping: no decision reads it.
 */
public final class Tracciamento1940 {
  /** Game property: the last turn's switches and player types of the alliance. */
  public static final String KEY = "Pro+ stato maggiore tracciamento: ";

  private static final String KEY_AVVIO = "Pro+ stato maggiore avvio: ";
  private static final Logger LOG = Logger.getLogger(Tracciamento1940.class.getName());

  private Tracciamento1940() {}

  /** The JSON of the turn of {@code player} (switches on for its alliance, player types). */
  static Optional<JsonObject> stato(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return Optional.empty();
    }
    final GameProperties props = data.getProperties();
    final JsonObject o = new JsonObject();
    o.addProperty("alleanza", alliance.get());
    o.addProperty("round", data.getSequence().getRound());
    o.addProperty("nazione", player.getName());
    final JsonArray on = new JsonArray();
    for (final StrategaInterruttori s : StrategaInterruttori.values()) {
      if (s.acceso(props, alliance.get())) {
        on.add(s.id());
      }
    }
    o.add("interruttori_attivi", on);
    o.addProperty("senza_llm", Stratega.senzaLlm(props, alliance.get()));
    final JsonObject types = new JsonObject();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      try {
        types.addProperty(p.getName(), p.getPlayerType().name);
      } catch (final RuntimeException e) {
        types.addProperty(p.getName(), "?");
      }
    }
    o.add("tipi_giocatore", types);
    return Optional.of(o);
  }

  /** Start of a turn: property, register row, log line once per game and alliance. */
  public static void inizioTurno(final GameState data, final GamePlayer player) {
    try {
      final Optional<JsonObject> o = stato(data, player);
      if (o.isEmpty()) {
        return;
      }
      final String alliance = o.get().get("alleanza").getAsString();
      final GameProperties props = data.getProperties();
      props.set(KEY + alliance, (Serializable) null);
      props.set(KEY + alliance, o.get().toString());
      final int round = data.getSequence().getRound();
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              "R" + round + "-" + player.getName() + "-tracciamento",
              round,
              player.getName(),
              "tracciamento",
              new JsonArray(),
              o.get().get("interruttori_attivi").toString(),
              "PF1_TURNO",
              "inizio turno: interruttori attivi e tipi giocatore",
              o.get()));
      if (!"si".equals(props.get(KEY_AVVIO + alliance))) {
        props.set(KEY_AVVIO + alliance, (Serializable) null);
        props.set(KEY_AVVIO + alliance, "si");
        final String line =
            "[Pro+] Pro+ (Europe 1940), avvio "
                + alliance
                + " (R"
                + round
                + "): interruttori attivi "
                + o.get().get("interruttori_attivi")
                + ", tipi giocatore "
                + o.get().get("tipi_giocatore");
        ProLogger.info(line);
        LOG.info(line);
      }
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Tracciamento PF1 non scritto: " + e);
    }
  }

  /** The saved JSON of {@code alliance} (tests, autoplay). */
  public static Optional<JsonObject> letto(final GameProperties props, final String alliance) {
    if (props.get(KEY + alliance) instanceof String s && !s.isBlank()) {
      return Optional.of(JsonParser.parseString(s).getAsJsonObject());
    }
    return Optional.empty();
  }
}
