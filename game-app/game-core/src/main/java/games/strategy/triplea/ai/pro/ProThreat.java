package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.Matches;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * The enemy units that can attack a territory before the next turn, by nation (Pro+ diary, step 3c:
 * "difendo X: minaccia reale da ...").
 *
 * @param territory the territory defended
 * @param units enemy nation → unit type → count (no infrastructure)
 * @param why enemy nation → why it counts (at war, war of the plan, war the map allows)
 */
public record ProThreat(
    String territory, Map<String, Map<String, Integer>> units, Map<String, String> why) {

  /** The threat of {@code attackers} on {@code t}; {@code reasons} from the real enemies. */
  public static ProThreat of(
      final Territory t, final Collection<Unit> attackers, final Map<GamePlayer, String> reasons) {
    final Map<String, Map<String, Integer>> units = new TreeMap<>();
    final Map<String, String> why = new TreeMap<>();
    for (final Unit u : attackers) {
      if (Matches.unitIsInfrastructure().test(u)) {
        continue;
      }
      units
          .computeIfAbsent(u.getOwner().getName(), k -> new TreeMap<>())
          .merge(u.getType().getName(), 1, Integer::sum);
      final String reason = reasons.get(u.getOwner());
      if (reason != null) {
        why.put(u.getOwner().getName(), reason);
      }
    }
    return new ProThreat(t.getName(), units, why);
  }

  public boolean isEmpty() {
    return units.isEmpty();
  }
}
