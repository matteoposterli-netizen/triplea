package org.triplea.proplus.autoplay;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.Matches;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Step P4: what the block {@code p4} of {@code riepilogo.json} needs from the board, for every
 * configuration (Hard included): the owners of the watched territories at the end of the round, the
 * land army's TUV of Russia and Germany, and the Russian attacks of the round (Russian units in the
 * battles after Russia's combat move, enemy units there before and after Russia's battles).
 */
final class MonitorP4 {
  static final String RUSSIA = "Russians";
  static final String GERMANIA = "Germans";
  static final List<String> TERRITORI =
      List.of("Norway", "Novgorod", "Ukraine", "Volgograd", "Russia", "Southern Italy", "Germany");

  private final GameData data;
  private final Supplier<Map<String, Map<String, Integer>>> costs;

  /** Round → {attaccanti, nemici_prima, nemici_uccisi, battaglie}. */
  private final Map<Integer, Map<String, Integer>> attacchi = new TreeMap<>();

  /** Battle sites of Russia's turn → enemy units there after the combat move. */
  private final Map<String, Set<String>> nemiciPrima = new HashMap<>();

  MonitorP4(final GameData data, final Supplier<Map<String, Map<String, Integer>>> costs) {
    this.data = data;
    this.costs = costs;
  }

  void afterStep(final String step, final String player, final int round) {
    if (!RUSSIA.equals(player)) {
      return;
    }
    final GamePlayer russia = data.getPlayerList().getPlayerId(RUSSIA);
    if (russia == null) {
      return;
    }
    if (GameStep.isCombatMoveStepName(step) && !GameStep.isAirborneCombatMoveStepName(step)) {
      nemiciPrima.clear();
      int attackers = 0;
      int enemies = 0;
      for (final Territory t : data.getMap().getTerritories()) {
        final List<Unit> mine =
            t.getMatches(Matches.unitIsOwnedBy(russia).and(Matches.unitIsNotInfrastructure()));
        final List<Unit> theirs = enemies(t, russia);
        if (mine.isEmpty() || theirs.isEmpty()) {
          continue;
        }
        attackers += mine.size();
        enemies += theirs.size();
        final Set<String> ids = new HashSet<>();
        theirs.forEach(u -> ids.add(u.getId().toString()));
        nemiciPrima.put(t.getName(), ids);
      }
      final Map<String, Integer> m = attacchi.computeIfAbsent(round, k -> new TreeMap<>());
      m.merge("attaccanti", attackers, Integer::sum);
      m.merge("nemici_prima", enemies, Integer::sum);
      m.merge("battaglie", nemiciPrima.size(), Integer::sum);
    } else if (GameStep.isBattleStepName(step) && !nemiciPrima.isEmpty()) {
      int killed = 0;
      for (final var e : nemiciPrima.entrySet()) {
        final Territory t = data.getMap().getTerritoryOrNull(e.getKey());
        final Set<String> left = new HashSet<>();
        if (t != null) {
          enemies(t, russia).forEach(u -> left.add(u.getId().toString()));
        }
        for (final String id : e.getValue()) {
          if (!left.contains(id)) {
            killed++;
          }
        }
      }
      attacchi
          .computeIfAbsent(round, k -> new TreeMap<>())
          .merge("nemici_uccisi", killed, Integer::sum);
      nemiciPrima.clear();
    }
  }

  private static List<Unit> enemies(final Territory t, final GamePlayer russia) {
    return t.getMatches(Matches.unitIsNotInfrastructure().and(u -> russia.isAtWar(u.getOwner())));
  }

  /** The round's snapshot. */
  JsonObject snapshot(final int round) {
    final JsonObject o = new JsonObject();
    final JsonObject owners = new JsonObject();
    for (final String n : TERRITORI) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      owners.addProperty(n, t == null ? "?" : t.getOwner().getName());
    }
    o.add("proprietari", owners);
    final JsonObject army = new JsonObject();
    for (final String n : List.of(RUSSIA, GERMANIA)) {
      army.addProperty(n, landTuv(n));
    }
    o.add("esercito_tuv", army);
    final JsonObject att = new JsonObject();
    attacchi.getOrDefault(round, Map.of()).forEach(att::addProperty);
    o.add("attacchi_russi", att);
    return o;
  }

  private int landTuv(final String nation) {
    final Map<String, Integer> c = costs.get().getOrDefault(nation, Map.of());
    int sum = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        if (u.getOwner().getName().equals(nation)
            && Matches.unitIsLand().test(u)
            && Matches.unitIsNotInfrastructure().test(u)) {
          sum += c.getOrDefault(u.getType().getName(), 0);
        }
      }
    }
    return sum;
  }
}
