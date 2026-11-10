package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.Territory;
import java.util.Set;

/**
 * Pro+ step C2-bis: territories the Pro AI's own defense (purchase and non-combat move) leaves out
 * ({@code expendable}) or always tries to defend ({@code mustHold}), by territory name. Only the
 * "Pro+ Stratega LLM" player has lists; Hard has none ({@link ProTuning#defenseLists}).
 */
public record ProDefenseLists(Set<String> expendable, Set<String> mustHold) {
  public ProDefenseLists {
    expendable = Set.copyOf(expendable);
    mustHold = Set.copyOf(mustHold);
  }

  public boolean isExpendable(final Territory t) {
    return expendable.contains(t.getName()) && !mustHold.contains(t.getName());
  }

  public boolean isMustHold(final Territory t) {
    return mustHold.contains(t.getName());
  }
}
