package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.experimental.UtilityClass;

/**
 * Pro+ settings chosen on the player selection screen, one risk level per alliance.
 *
 * <p>They are stored in the game itself, as constant game properties ({@code "Pro+ risk:
 * <alliance>"} = level 1..5 as an {@link Integer}), so a save game keeps them. Only JDK types go
 * into the save: official TripleA opens it unchanged and ignores the extra properties.
 *
 * <p>Alliances are read from the map, nothing is wired to "Axis"/"Allies".
 */
@UtilityClass
public final class ProPlusSettings {
  static final String RISK_PROPERTY_PREFIX = "Pro+ risk: ";

  /**
   * Alliances that get a risk slider: those with at least one non-optional player (in WW2 Europe
   * 1940: Allies and Axis, not the optional neutral "alliances"). If every alliance has only
   * optional players, all of them. Sorted by name.
   */
  public static List<String> configurableAlliances(final GameState data) {
    final var tracker = data.getAllianceTracker();
    final Set<String> withRequiredPlayers = new TreeSet<>();
    final Set<String> all = new TreeSet<>();
    for (final String alliance : tracker.getAlliances()) {
      final var players = tracker.getPlayersInAlliance(alliance);
      if (players.stream().allMatch(GamePlayer::isHidden)) {
        continue;
      }
      all.add(alliance);
      if (players.stream().anyMatch(p -> !p.getOptional())) {
        withRequiredPlayers.add(alliance);
      }
    }
    return new ArrayList<>(withRequiredPlayers.isEmpty() ? all : withRequiredPlayers);
  }

  /** The alliance whose risk level applies to {@code player}, if it has one with a slider. */
  public static Optional<String> allianceOf(final GameState data, final GamePlayer player) {
    final List<String> configurable = configurableAlliances(data);
    return data.getAllianceTracker().getAlliancesPlayerIsIn(player).stream()
        .filter(configurable::contains)
        .sorted()
        .findFirst();
  }

  /** Risk level for {@code player}: its alliance's setting, else {@link RiskLevel#DEFAULT}. */
  public static RiskLevel riskLevelFor(final GameState data, final GamePlayer player) {
    return allianceOf(data, player)
        .map(alliance -> getRiskLevel(data.getProperties(), alliance))
        .orElse(RiskLevel.DEFAULT);
  }

  public static RiskLevel getRiskLevel(final GameProperties props, final String alliance) {
    final Serializable value = props.get(RISK_PROPERTY_PREFIX + alliance);
    return value instanceof Integer level
        ? RiskLevel.fromLevel(level).orElse(RiskLevel.DEFAULT)
        : RiskLevel.DEFAULT;
  }

  public static void setRiskLevel(
      final GameProperties props, final String alliance, final RiskLevel riskLevel) {
    final String key = RISK_PROPERTY_PREFIX + alliance;
    // unbind first: set() appends the key to the property ordering each time
    props.set(key, (Serializable) null);
    props.set(key, Integer.valueOf(riskLevel.getLevel()));
  }
}
