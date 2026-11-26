package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Step PV1: the data and the switches of three Allied corrections from Matteo's real game (data
 * {@value #RESOURCE}, default off): {@link StrategaInterruttori#USA_CARICO} ({@link UsaCarico}),
 * {@link StrategaInterruttori#CAPITALE_OLTRANZA} ({@link CapitaleOltranza}) and {@link
 * StrategaInterruttori#PONTE_ALLEATO} ({@link PonteAlleato}). All off: nothing of the step runs.
 */
public final class LevePv1 {
  private LevePv1() {}

  public static final String RESOURCE = "proplus/strategia/pv1.json";

  static final String TIPO_CARICO = "pv1_usa_carico";
  static final String TIPO_CAPITALE = "pv1_capitale";
  static final String TIPO_PONTE = "pv1_ponte_alleato";

  static final List<StrategaInterruttori> LEVE =
      List.of(
          StrategaInterruttori.USA_CARICO,
          StrategaInterruttori.CAPITALE_OLTRANZA,
          StrategaInterruttori.PONTE_ALLEATO);

  private static final AtomicLong SEQ = new AtomicLong();

  /** The data file. */
  record Config(
      Map<String, Boolean> diDefault,
      String caricoNazione,
      int postiPerTrasporto,
      String capitaleNazione,
      List<String> capitaleNemici,
      double sogliaMinaccia,
      double sogliaContrattacco,
      String ponteNazione,
      String ponteAlleato,
      List<String> america,
      List<String> porti,
      List<String> testeDiPonte,
      String riserva,
      double sogliaZona) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in = LevePv1.class.getClassLoader().getResourceAsStream(RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello step PV1 non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject o) {
      final Map<String, Boolean> def = new LinkedHashMap<>();
      v(o, "acceso_di_default")
          .getAsJsonObject()
          .entrySet()
          .forEach(e -> def.put(e.getKey(), e.getValue().getAsBoolean()));
      final JsonObject c = v(o, "usa_carico").getAsJsonObject();
      final JsonObject k = v(o, "capitale_oltranza").getAsJsonObject();
      final JsonObject p = v(o, "ponte_alleato").getAsJsonObject();
      return new Config(
          Map.copyOf(def),
          c.get("nazione").getAsString(),
          c.get("posti_per_trasporto").getAsInt(),
          k.get("nazione").getAsString(),
          lista(k.get("nemici")),
          k.get("soglia_minaccia_pct").getAsDouble(),
          k.get("soglia_contrattacco_pct").getAsDouble(),
          p.get("nazione").getAsString(),
          p.get("alleato").getAsString(),
          lista(p.get("america")),
          lista(p.get("porti")),
          lista(p.get("teste_di_ponte")),
          p.get("riserva").getAsString(),
          p.get("soglia_zona_pct").getAsDouble());
    }

    boolean diDefault(final StrategaInterruttori s) {
      return diDefault.getOrDefault(s.id(), false);
    }

    private static List<String> lista(final JsonElement e) {
      final List<String> out = new ArrayList<>();
      e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
      return List.copyOf(out);
    }

    private static JsonElement v(final JsonObject o, final String key) {
      final JsonElement e = o.get(key);
      return e.isJsonObject() && e.getAsJsonObject().has("valore")
          ? e.getAsJsonObject().get("valore")
          : e;
    }
  }

  /** The territories of America of the data (autoplay's monitor). */
  public static List<String> america() {
    return Config.get().america();
  }

  // ================================================================================== switches

  /** Switch {@code s} is on for {@code player}'s alliance (strategist's nations only). */
  static boolean accesa(
      @Nullable final GameState data,
      @Nullable final GamePlayer player,
      final StrategaInterruttori s) {
    if (player == null || data == null || !Stratega.gioca(data, player)) {
      return false;
    }
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    return alliance.isPresent() && s.acceso(data.getProperties(), alliance.get());
  }

  /** Switch {@code s} is on and {@code player} is the nation {@code nation}. */
  static boolean attiva(
      @Nullable final GameState data,
      @Nullable final GamePlayer player,
      final StrategaInterruttori s,
      final String nation) {
    return player != null && nation.equals(player.getName()) && accesa(data, player, s);
  }

  // ================================================================================ register

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
              "R" + round + "-" + player.getName() + "-" + tipo + "-" + SEQ.incrementAndGet(),
              round,
              player.getName(),
              tipo,
              new JsonArray(),
              codice,
              codice,
              frase,
              parametri));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] PV1, registro: " + e);
    }
  }

  /** The register rows of the step of {@code type} (tests). */
  static List<JsonObject> righe(final GameState data, final String type) {
    final List<JsonObject> out = new ArrayList<>();
    for (final JsonObject o : RegistroDecisioni.righe(data)) {
      if (o.has("tipo") && type.equals(o.get("tipo").getAsString())) {
        out.add(o);
      }
    }
    return out;
  }

  static double r1(final double v) {
    return Math.round(v * 10) / 10.0;
  }
}
