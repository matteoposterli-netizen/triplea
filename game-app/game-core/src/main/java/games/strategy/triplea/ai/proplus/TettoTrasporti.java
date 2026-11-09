package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.proplus.strategia.Direttive;
import java.io.Serializable;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Guard rail of the strategic contract: the transport loss accepted beyond the safety rule ({@code
 * perdita_trasporti_accettata_max_TUV} of the directives) is at most {@link
 * Direttive#PERDITA_TRASPORTI_MAX} TUV per ALLIANCE per round, summing every landing of every
 * nation of the alliance in the round (before: the cap held for each landing alone).
 *
 * <p>Saved in the game ({@code "Pro+ perdite trasporti accettate: <alliance>"} = JSON {round,
 * voci: {"nation|target": loss}}): a nation that plays later in the round sees what the earlier
 * ones used. One entry per nation and target, so the combat planning that checks the same landing
 * again does not count it twice; a landing accepted and later dropped by another rule still counts
 * (conservative).
 */
@UtilityClass
public final class TettoTrasporti {
  static final String PREFIX = "Pro+ perdite trasporti accettate: ";

  /** TUV the alliance of {@code player} can still accept for a landing on {@code target}. */
  public static double residuo(
      final GameState data, final GamePlayer player, final Territory target) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return Direttive.PERDITA_TRASPORTI_MAX;
    }
    // step ABLAZIONE: without the guard rail the whole directive's loss is accepted
    if (!Ablazione.attivo(
        data.getProperties(), alliance.get(), Ablazione.Componente.TETTO_TRASPORTI)) {
      return Double.MAX_VALUE;
    }
    final JsonObject entries = voci(data, alliance.get());
    final String own = key(player, target);
    double used = 0;
    for (final var e : entries.entrySet()) {
      if (!e.getKey().equals(own)) {
        used += e.getValue().getAsDouble();
      }
    }
    return Math.max(0, Direttive.PERDITA_TRASPORTI_MAX - used);
  }

  /** TUV accepted by the alliance of {@code player} in this round so far. */
  public static double usato(final GameState data, final GamePlayer player) {
    return ProPlusSettings.allianceOf(data, player)
        .map(
            a ->
                voci(data, a).entrySet().stream()
                    .mapToDouble(e -> e.getValue().getAsDouble())
                    .sum())
        .orElse(0.0);
  }

  /** Records the loss accepted for the landing of {@code player} on {@code target}. */
  public static void registra(
      final GameState data, final GamePlayer player, final Territory target, final double loss) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return;
    }
    final JsonObject entries = voci(data, alliance.get());
    entries.addProperty(key(player, target), loss);
    final JsonObject json = new JsonObject();
    json.addProperty("round", data.getSequence().getRound());
    json.add("voci", entries);
    final GameProperties props = data.getProperties();
    props.set(PREFIX + alliance.get(), (Serializable) null);
    props.set(PREFIX + alliance.get(), json.toString());
  }

  /** The entries of the current round (an older round = none). */
  private static JsonObject voci(final GameState data, final String alliance) {
    if (data.getProperties().get(PREFIX + alliance) instanceof String s) {
      try {
        final JsonObject json = JsonParser.parseString(s).getAsJsonObject();
        if (json.get("round").getAsInt() == data.getSequence().getRound()) {
          return json.getAsJsonObject("voci");
        }
      } catch (final RuntimeException e) {
        // unreadable: nothing used
      }
    }
    return new JsonObject();
  }

  private static String key(final GamePlayer player, final Territory target) {
    return player.getName() + "|" + target.getName();
  }
}
