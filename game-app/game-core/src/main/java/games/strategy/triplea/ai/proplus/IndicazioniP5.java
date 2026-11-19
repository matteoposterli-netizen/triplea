package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * Step P5: the data of the two levers ({@value #RESOURCE}) and the register rows (types {@code
 * usa_proporzione}, {@code norvegia_scorta}). A = {@link ForzaSbarco} ({@link
 * StrategaInterruttori#USA_PROPORZIONE}), B = {@link ScortaSbarco} ({@link
 * StrategaInterruttori#NORVEGIA_SCORTA}). The nations, the war's round, the future relationships
 * and B's targets come from {@code p4.json} ({@link IndicazioniP4.Config}).
 */
public final class IndicazioniP5 {
  private IndicazioniP5() {}

  public static final String RESOURCE = "proplus/strategia/p5.json";

  private static final java.util.concurrent.atomic.AtomicInteger SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  /** The data file. */
  record Config(
      boolean usaDiDefault,
      double sogliaConvoglio,
      int raggioZonaArrivo,
      int maxUnitaScorta,
      boolean scortaDiDefault,
      double sogliaTrasporti,
      int maxUnitaMancanti) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in = IndicazioniP5.class.getClassLoader().getResourceAsStream(RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello step P5 non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject root) {
      final JsonObject a = root.getAsJsonObject("usa_proporzione");
      final JsonObject ap = a.getAsJsonObject("parametri");
      final JsonObject b = root.getAsJsonObject("norvegia_scorta");
      final JsonObject bp = b.getAsJsonObject("parametri");
      return new Config(
          v(a, "acceso_di_default").getAsBoolean(),
          v(ap, "soglia_convoglio").getAsDouble(),
          v(ap, "raggio_zona_arrivo").getAsInt(),
          v(ap, "max_unita_scorta").getAsInt(),
          v(b, "acceso_di_default").getAsBoolean(),
          v(bp, "soglia_trasporti").getAsDouble(),
          v(bp, "max_unita_mancanti").getAsInt());
    }

    private static JsonElement v(final JsonObject o, final String key) {
      final JsonElement e = o.get(key);
      return e.isJsonObject() && e.getAsJsonObject().has("valore")
          ? e.getAsJsonObject().get("valore")
          : e;
    }
  }

  /** A register row of step P5 (type {@code tipo}, reason {@code codice}). Never throws. */
  static void registra(
      final GameState data,
      final GamePlayer player,
      final String tipo,
      final String codice,
      final String frase,
      final JsonObject parametri) {
    try {
      final int round = data.getSequence().getRound();
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              "R"
                  + round
                  + "-"
                  + player.getName()
                  + "-"
                  + tipo
                  + "-"
                  + codice
                  + "-"
                  + SEQ.incrementAndGet(),
              round,
              player.getName(),
              tipo,
              new com.google.gson.JsonArray(),
              codice,
              codice,
              frase,
              parametri));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P5, registro: " + e);
    }
  }
}
