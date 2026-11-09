package games.strategy.triplea.ai.pro.data;

import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;

/** The result of an AI movement analysis for its own possible moves. */
@Getter
public class ProMyMoveOptions {
  private final Map<Territory, ProTerritory> territoryMap = new HashMap<>();
  private final Map<Unit, Set<Territory>> unitMoveMap = new HashMap<>();
  private final Map<Unit, Set<Territory>> transportMoveMap = new HashMap<>();
  private final Map<Unit, Set<Territory>> bombardMap = new HashMap<>();
  private final List<ProTransport> transportList = new ArrayList<>();
  private final Map<Unit, Set<Territory>> bomberMoveMap = new HashMap<>();

  ProMyMoveOptions() {}

  ProMyMoveOptions(final ProMyMoveOptions myMoveOptions, final ProData proData) {
    for (final Territory t : myMoveOptions.territoryMap.keySet()) {
      territoryMap.put(t, new ProTerritory(myMoveOptions.territoryMap.get(t), proData));
    }
    unitMoveMap.putAll(myMoveOptions.unitMoveMap);
    transportMoveMap.putAll(myMoveOptions.transportMoveMap);
    bombardMap.putAll(myMoveOptions.bombardMap);
    transportList.addAll(myMoveOptions.transportList);
    bomberMoveMap.putAll(myMoveOptions.bomberMoveMap);
  }

  /**
   * Takes {@code units} out of every option (Pro+: units a joint operation already moved; never
   * called for Hard).
   */
  public void removeUnits(final Collection<Unit> units) {
    if (units.isEmpty()) {
      return;
    }
    units.forEach(
        u -> {
          unitMoveMap.remove(u);
          transportMoveMap.remove(u);
          bombardMap.remove(u);
          bomberMoveMap.remove(u);
        });
    transportList.removeIf(t -> units.contains(t.getTransport()));
    for (final ProTerritory t : territoryMap.values()) {
      t.getMaxUnits().removeAll(units);
      t.getMaxAmphibUnits().removeAll(units);
      t.getMaxBombardUnits().removeAll(units);
      t.getBombardOptionsMap().keySet().removeAll(units);
      t.getAmphibAttackMap().keySet().removeAll(units);
      t.getAmphibAttackMap()
          .replaceAll(
              (transport, cargo) ->
                  new ArrayList<>(cargo.stream().filter(u -> !units.contains(u)).toList()));
    }
  }
}
