package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Step P15: Russia holds and retakes Ukraine. Two switches of the Allies (data {@value #RESOURCE},
 * default off, not in the panel's box): {@link StrategaInterruttori#UCRAINA_CONTRATTACCO} ({@link
 * UcrainaContrattacco}, A: more Russian attacks after Hard's combat move) and {@link
 * StrategaInterruttori#UCRAINA_PRESIDIO} ({@link UcrainaPresidio}, B: reinforcements to Ukraine
 * only when they hold it). Both off: nothing of the step runs.
 */
public final class LeveP15 {
  private LeveP15() {}

  public static final String RESOURCE = "proplus/strategia/p15.json";

  static final String TIPO_CONTRATTACCO = "p15_contrattacco";
  static final String TIPO_PRESIDIO = "p15_presidio";
  static final String TIPO_UCRAINA = "p15_ucraina";

  static final List<StrategaInterruttori> LEVE =
      List.of(StrategaInterruttori.UCRAINA_CONTRATTACCO, StrategaInterruttori.UCRAINA_PRESIDIO);

  private static final AtomicLong SEQ = new AtomicLong();

  /** The data file. */
  record Config(
      Map<String, Boolean> diDefault,
      String nazione,
      String nemicoGuerra,
      String ucraina,
      String novgorod,
      double sogliaVittoria,
      double rapportoTuv,
      double margineMosca,
      double vittoriaSufficiente,
      double sogliaPresidio) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in = LeveP15.class.getClassLoader().getResourceAsStream(RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello step P15 non valido: " + e, e);
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
      final JsonObject r = v(o, "russia").getAsJsonObject();
      final JsonObject a = v(o, "contrattacco").getAsJsonObject();
      final JsonObject b = v(o, "presidio").getAsJsonObject();
      return new Config(
          Map.copyOf(def),
          r.get("nazione").getAsString(),
          r.get("nemico_guerra").getAsString(),
          r.get("ucraina").getAsString(),
          r.get("novgorod").getAsString(),
          a.get("soglia_vittoria_pct").getAsDouble(),
          a.get("rapporto_tuv").getAsDouble(),
          a.get("margine_mosca_punti").getAsDouble(),
          a.get("vittoria_sufficiente_pct").getAsDouble(),
          b.get("soglia_minaccia_pct").getAsDouble());
    }

    boolean diDefault(final StrategaInterruttori s) {
      return diDefault.getOrDefault(s.id(), false);
    }

    private static JsonElement v(final JsonObject o, final String key) {
      final JsonElement e = o.get(key);
      return e.isJsonObject() && e.getAsJsonObject().has("valore")
          ? e.getAsJsonObject().get("valore")
          : e;
    }
  }

  // ================================================================================== switches

  /** Switch {@code s} is on and {@code player} is the data's nation (Russia). */
  static boolean attiva(
      @Nullable final GameState data,
      @Nullable final GamePlayer player,
      final StrategaInterruttori s) {
    return player != null
        && Config.get().nazione().equals(player.getName())
        && LevePv1.accesa(data, player, s);
  }

  /** Any switch of the step on for {@code player}. */
  static boolean alcuna(@Nullable final GameState data, @Nullable final GamePlayer player) {
    return LEVE.stream().anyMatch(s -> attiva(data, player, s));
  }

  /** A3: Russia is at war with the data's enemy (Germany). */
  static boolean inGuerra(final GameState data, final GamePlayer player) {
    final GamePlayer enemy = data.getPlayerList().getPlayerId(Config.get().nemicoGuerra());
    return enemy != null && player.isAtWar(enemy);
  }

  // ================================================================================= estimates

  /** The nation's capital if it still owns it. */
  static Optional<Territory> capitale(final GameState data, final GamePlayer player) {
    return CapitaleOltranza.capitale(data, player);
  }

  /** The win of the enemies' best attack next turn on {@code t} against {@code defenders}. */
  static double minaccia(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory t,
      final LeveItalia.Minaccia m,
      final Collection<Unit> defenders) {
    return LeveItalia.vittoria(proData, calc, t, m, new ArrayList<>(defenders));
  }

  /** Allied non-infrastructure units in {@code t}. */
  static List<Unit> difensori(final Territory t, final GamePlayer player) {
    return t.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotInfrastructure()));
  }

  static int tuv(final ProData proData, final Collection<Unit> units) {
    return TuvUtils.getTuv(units, proData.getUnitValueMap());
  }

  // ======================================================================== pure rules

  /**
   * A1 (pure rule): execute at win >= threshold and Axis TUV killed >= ratio x Russian TUV lost.
   */
  static boolean esegue(
      final double win,
      final double killed,
      final double lost,
      final double winThreshold,
      final double ratio) {
    return win >= winThreshold && killed >= ratio * lost;
  }

  /** A2 (pure rule): Moscow's enemy win with the units gone at most the reference + margin. */
  static boolean moscaProtetta(final double without, final double reference, final double margin) {
    return without <= reference + margin;
  }

  /** B (pure rule): Ukraine threatened = the enemy's win at the threshold or more. */
  static boolean minacciata(final double win, final double threshold) {
    return win >= threshold;
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
      ProLogger.warn("[Pro+] P15, registro: " + e);
    }
  }

  /** The register rows of the step of {@code type} (tests). */
  static List<JsonObject> righe(final GameState data, final String type) {
    return LevePv1.righe(data, type);
  }

  /** End of Russia's turn: Ukraine and Novgorod Russian or not (only with a switch on). */
  static void fineTurno(final GameState data, final GamePlayer player) {
    if (!alcuna(data, player)) {
      return;
    }
    final Config c = Config.get();
    final JsonObject row = new JsonObject();
    for (final String n : List.of(c.ucraina(), c.novgorod())) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      row.addProperty(n, t != null && t.isOwnedBy(player));
    }
    row.addProperty("in_guerra", inGuerra(data, player));
    registra(data, player, TIPO_UCRAINA, "P15_FINE_TURNO", "Ucraina a fine turno russo", row);
  }

  static double r1(final double v) {
    return Math.round(v * 10) / 10.0;
  }
}
