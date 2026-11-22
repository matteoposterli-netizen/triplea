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
 * Step P13: the data and the switches of three levers measured in a factorial design (data {@value
 * #RESOURCE}, default off): {@link StrategaInterruttori#TARANTO} (Allies, {@link Taranto}), {@link
 * StrategaInterruttori#FABBRICHE_TENUTE} ({@link FabbricheTenute}) and {@link
 * StrategaInterruttori#BOMBARDAMENTO_MOSCA} ({@link BombardamentoMosca}) of the Axis, on top of the
 * commitment and the siege. All off: nothing of the step runs.
 */
public final class LeveP13 {
  private LeveP13() {}

  public static final String RESOURCE = "proplus/strategia/p13.json";

  static final String TIPO_TARANTO = "p13_taranto";
  static final String TIPO_FABBRICHE = "p13_fabbriche";
  static final String TIPO_BOMBARDAMENTO = "p13_bombardamento";

  static final List<StrategaInterruttori> LEVE =
      List.of(
          StrategaInterruttori.TARANTO,
          StrategaInterruttori.FABBRICHE_TENUTE,
          StrategaInterruttori.BOMBARDAMENTO_MOSCA);

  private static final AtomicLong SEQ = new AtomicLong();

  /** The data file. */
  record Config(
      Map<String, Boolean> diDefault,
      String tarantoNazione,
      int tarantoRound,
      String zonaBersaglio,
      String zonaFlotta,
      List<String> tipiNavi,
      String tattico,
      String caccia,
      String bombardiere,
      String baseAerea,
      String capitaleUk,
      int maxCacciaCapitale,
      String nemicoCapitale,
      String difensore,
      String fabbricheNazione,
      List<String> ovest,
      String bombNazione,
      String bombNemico,
      String capitaleRussa,
      String bombTipo,
      String scortaTipo,
      int bombardieriMinimi,
      double sogliaCapitale,
      double sogliaAttacco,
      double sogliaContrattacco,
      double sogliaScortaHard,
      int maxGuarnigione,
      String tipoGuarnigione) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in = LeveP13.class.getClassLoader().getResourceAsStream(RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello step P13 non valido: " + e, e);
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
      final JsonObject t = v(o, "taranto").getAsJsonObject();
      final JsonObject f = v(o, "fabbriche").getAsJsonObject();
      final JsonObject b = v(o, "bombardamento").getAsJsonObject();
      final JsonObject p = o.getAsJsonObject("parametri");
      return new Config(
          Map.copyOf(def),
          t.get("nazione").getAsString(),
          t.get("round").getAsInt(),
          t.get("zona_bersaglio").getAsString(),
          t.get("zona_flotta").getAsString(),
          lista(t.get("tipi_navi")),
          t.get("tattico").getAsString(),
          t.get("caccia").getAsString(),
          t.get("bombardiere").getAsString(),
          t.get("base_aerea").getAsString(),
          t.get("capitale").getAsString(),
          t.get("max_caccia_capitale").getAsInt(),
          t.get("nemico_capitale").getAsString(),
          t.get("difensore").getAsString(),
          f.get("nazione").getAsString(),
          lista(f.get("ovest")),
          b.get("nazione").getAsString(),
          b.get("nemico").getAsString(),
          b.get("capitale_russa").getAsString(),
          b.get("bombardiere").getAsString(),
          b.get("scorta").getAsString(),
          b.get("bombardieri_minimi").getAsInt(),
          v(p, "soglia_capitale_pct").getAsDouble(),
          v(p, "soglia_attacco_pct").getAsDouble(),
          v(p, "soglia_contrattacco_pct").getAsDouble(),
          v(p, "soglia_scorta_hard_pct").getAsDouble(),
          v(p, "max_guarnigione").getAsInt(),
          v(p, "tipo_guarnigione").getAsString());
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
      ProLogger.warn("[Pro+] P13, registro: " + e);
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
