package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
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
import javax.annotation.Nullable;

/**
 * Step P6: the data of the two levers ({@value #RESOURCE}), their activation and the register rows
 * (types {@code sbarco_concentrato}, {@code norvegia_esposta}). A = {@link SbarcoConcentrato}
 * ({@link StrategaInterruttori#SBARCO_CONCENTRATO}), B = {@link ScortaSbarco} in its "exposed" mode
 * ({@link StrategaInterruttori#NORVEGIA_ESPOSTA}, with {@link
 * StrategaInterruttori#NORVEGIA_PRESTO}): the weights of B's targets (attack while the enemy's,
 * hold while allied) are here.
 */
public final class IndicazioniP6 {
  private IndicazioniP6() {}

  public static final String RESOURCE = "proplus/strategia/p6.json";

  private static final java.util.concurrent.atomic.AtomicInteger SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  /** A target of B: attack weight in its rounds, hold weight up to its round. */
  record Bersaglio(
      String territorio,
      double pesoAttacco,
      int dalRound,
      int alRound,
      double pesoTenuta,
      int tenutaAlRound) {}

  /** The data file. */
  record Config(
      boolean sbarcoDiDefault,
      List<String> nazioniSbarco,
      double sogliaVittoria,
      int roundDiAttesa,
      double sogliaScorta,
      double valoreCittaVittoria,
      double moltiplicatoreCapitale,
      int zoneRaduno,
      int raggioAvvicinamento,
      boolean espostaDiDefault,
      double sogliaScortaEsposta,
      Map<String, List<Bersaglio>> bersagli) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in = IndicazioniP6.class.getClassLoader().getResourceAsStream(RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello step P6 non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject root) {
      final JsonObject a = root.getAsJsonObject("sbarco_concentrato");
      final JsonObject ap = a.getAsJsonObject("parametri");
      final JsonObject b = root.getAsJsonObject("norvegia_esposta");
      final JsonObject bp = b.getAsJsonObject("parametri");
      final List<String> nations = new ArrayList<>();
      v(a, "nazioni").getAsJsonArray().forEach(e -> nations.add(e.getAsString()));
      final Map<String, List<Bersaglio>> targets = new LinkedHashMap<>();
      for (final var e : b.getAsJsonObject("nazioni").entrySet()) {
        final List<Bersaglio> list = new ArrayList<>();
        for (final JsonElement x : e.getValue().getAsJsonArray()) {
          final JsonObject t = x.getAsJsonObject();
          list.add(
              new Bersaglio(
                  t.get("territorio").getAsString(),
                  t.get("peso_attacco").getAsDouble(),
                  t.get("attacco_dal_round").getAsInt(),
                  t.get("attacco_al_round").getAsInt(),
                  t.get("peso_tenuta").getAsDouble(),
                  t.get("tenuta_al_round").getAsInt()));
        }
        targets.put(e.getKey(), List.copyOf(list));
      }
      return new Config(
          v(a, "acceso_di_default").getAsBoolean(),
          List.copyOf(nations),
          v(ap, "soglia_vittoria").getAsDouble(),
          v(ap, "round_di_attesa").getAsInt(),
          v(ap, "soglia_scorta").getAsDouble(),
          v(ap, "valore_citta_vittoria").getAsDouble(),
          v(ap, "moltiplicatore_capitale").getAsDouble(),
          v(ap, "zone_raduno_valutate").getAsInt(),
          v(ap, "raggio_avvicinamento").getAsInt(),
          v(b, "acceso_di_default").getAsBoolean(),
          v(bp, "soglia_scorta").getAsDouble(),
          Map.copyOf(targets));
    }

    private static JsonElement v(final JsonObject o, final String key) {
      final JsonElement e = o.get(key);
      return e.isJsonObject() && e.getAsJsonObject().has("valore")
          ? e.getAsJsonObject().get("valore")
          : e;
    }
  }

  // ======================================================================== switches

  /** {@code s} is on for the player's alliance, the player is the strategist's and in the data. */
  static boolean attivo(
      final GameState data, @Nullable final GamePlayer player, final StrategaInterruttori s) {
    if (player == null || data == null || !Stratega.gioca(data, player)) {
      return false;
    }
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty() || !s.acceso(data.getProperties(), alliance.get())) {
      return false;
    }
    final Config c = Config.get();
    return switch (s) {
      case SBARCO_CONCENTRATO -> c.nazioniSbarco().contains(player.getName());
      case NORVEGIA_ESPOSTA ->
          c.bersagli().containsKey(player.getName())
              && StrategaInterruttori.NORVEGIA_PRESTO.acceso(data.getProperties(), alliance.get());
      default -> false;
    };
  }

  // ======================================================================== B: weights

  /**
   * B: the attack weight of {@code target} this round (0 if B is off, out of its rounds, or the
   * target is not an enemy's at war).
   */
  static double pesoAttacco(final ProData proData, final Territory target) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    if (!attivo(data, player, StrategaInterruttori.NORVEGIA_ESPOSTA)
        || target.isWater()
        || target.getOwner().isNull()
        || !player.isAtWar(target.getOwner())) {
      return 0;
    }
    return peso(
        Config.get().bersagli().getOrDefault(player.getName(), List.of()),
        target.getName(),
        data.getSequence().getRound(),
        false);
  }

  /** B: the hold weight of {@code target} this round when it is own or allied (else 0). */
  static double pesoTenuta(final ProData proData, final Territory target) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    if (!attivo(data, player, StrategaInterruttori.NORVEGIA_ESPOSTA)
        || target.isWater()
        || target.getOwner().isNull()
        || !(target.isOwnedBy(player) || player.isAllied(target.getOwner()))) {
      return 0;
    }
    return peso(
        Config.get().bersagli().getOrDefault(player.getName(), List.of()),
        target.getName(),
        data.getSequence().getRound(),
        true);
  }

  /** The weight of {@code target} in {@code round} (pure rule): attack or hold. */
  static double peso(
      final List<Bersaglio> list, final String target, final int round, final boolean hold) {
    double best = 0;
    for (final Bersaglio b : list) {
      if (!b.territorio().equals(target)) {
        continue;
      }
      if (hold && round <= b.tenutaAlRound()) {
        best = Math.max(best, b.pesoTenuta());
      } else if (!hold && round >= b.dalRound() && round <= b.alRound()) {
        best = Math.max(best, b.pesoAttacco());
      }
    }
    return best;
  }

  // ======================================================================== register

  /** A register row of step P6 (type {@code tipo}, reason {@code codice}). Never throws. */
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
              new JsonArray(),
              codice,
              codice,
              frase,
              parametri));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P6, registro: " + e);
    }
  }
}
