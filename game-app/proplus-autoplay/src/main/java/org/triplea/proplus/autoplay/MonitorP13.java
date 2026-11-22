package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.history.Event;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.tree.TreeNode;

/**
 * Step P13: what the block {@code p13} of {@code riepilogo.json} needs from the board, for every
 * configuration: per round the German land units in the originally Russian territories, the Russian
 * factories' damage repaired in the Russian turn and done in the German turn; at round 1 the
 * British warships in the Mediterranean at the end of the Italian turn and the scrambles of the
 * round (from the game's history).
 */
final class MonitorP13 {
  static final String GERMANIA = "Germans";
  static final String RUSSIA = "Russians";
  static final String UK = "British";
  static final String ITALIA = "Italians";

  /** The Mediterranean sea zones of WW2 Europe 1940 (British fleet after the Italian turn). */
  static final List<String> MEDITERRANEO =
      List.of(
          "92 Sea Zone",
          "93 Sea Zone",
          "94 Sea Zone",
          "95 Sea Zone",
          "96 Sea Zone",
          "97 Sea Zone",
          "98 Sea Zone",
          "99 Sea Zone");

  private final GameData data;

  private final Map<String, Integer> damageBefore = new HashMap<>();
  private final Map<Integer, Integer> repaired = new TreeMap<>();
  private final Map<Integer, Integer> bombed = new TreeMap<>();
  private int ukMedAfterItaly = -1;
  private JsonArray scrambles;

  MonitorP13(final GameData data) {
    this.data = data;
  }

  void beforeStep(final String step, final String player) {
    if ((RUSSIA.equals(player) && GameStep.isPurchaseStepName(step))
        || (GERMANIA.equals(player) && GameStep.isBattleStepName(step))) {
      damageBefore.clear();
      damageBefore.putAll(damage());
    }
  }

  void afterStep(final String step, final String player, final int round) {
    if (RUSSIA.equals(player) && GameStep.isPurchaseStepName(step) && !damageBefore.isEmpty()) {
      int sum = 0;
      for (final var e : damage().entrySet()) {
        sum += Math.max(0, damageBefore.getOrDefault(e.getKey(), 0) - e.getValue());
      }
      repaired.merge(round, sum, Integer::sum);
      damageBefore.clear();
    } else if (GERMANIA.equals(player)
        && GameStep.isBattleStepName(step)
        && !damageBefore.isEmpty()) {
      int sum = 0;
      for (final var e : damage().entrySet()) {
        sum += Math.max(0, e.getValue() - damageBefore.getOrDefault(e.getKey(), 0));
      }
      bombed.merge(round, sum, Integer::sum);
      damageBefore.clear();
    }
    if (round == 1
        && ITALIA.equals(player)
        && (GameStep.isPlaceStepName(step) || GameStep.isNonCombatMoveStepName(step))) {
      ukMedAfterItaly = ukMed();
    }
  }

  /** Russian factory unit id → damage, on Russian land. */
  private Map<String, Integer> damage() {
    final Map<String, Integer> out = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || !RUSSIA.equals(t.getOwner().getName())) {
        continue;
      }
      for (final Unit u : t.getMatches(Matches.unitCanProduceUnitsAndCanBeDamaged())) {
        out.put(u.getId().toString(), u.getUnitDamage());
      }
    }
    return out;
  }

  private int ukMed() {
    int n = 0;
    for (final String z : MEDITERRANEO) {
      final Territory t = data.getMap().getTerritoryOrNull(z);
      if (t != null) {
        n +=
            t.getMatches(
                    u ->
                        UK.equals(u.getOwner().getName())
                            && Matches.unitIsSea().test(u)
                            && !Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u))
                .size();
      }
    }
    return n;
  }

  JsonObject snapshot(final int round) {
    final JsonObject json = new JsonObject();
    int east = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        continue;
      }
      final boolean russian =
          OriginalOwnerTracker.getOriginalOwner(t)
              .map(p -> RUSSIA.equals(p.getName()))
              .orElse(false);
      if (russian) {
        east +=
            t.getMatches(
                    u ->
                        GERMANIA.equals(u.getOwner().getName())
                            && Matches.unitIsLand().test(u)
                            && Matches.unitIsNotInfrastructure().test(u))
                .size();
      }
    }
    json.addProperty("tedeschi_est", east);
    json.addProperty("riparazioni_russe", repaired.getOrDefault(round, 0));
    json.addProperty("danno_tedesco", bombed.getOrDefault(round, 0));
    if (round == 1) {
      json.addProperty("uk_navi_med_dopo_italia", ukMedAfterItaly);
      if (scrambles == null) {
        scrambles = scrambles();
      }
      json.add("scramble", scrambles);
    }
    return json;
  }

  /** The scramble events of the history so far ("X scrambles N units out of A ... in B"). */
  private JsonArray scrambles() {
    final JsonArray out = new JsonArray();
    try {
      try (GameData.Unlocker ignored = data.acquireReadLock()) {
        final Object root = data.getHistory().getRoot();
        if (root instanceof javax.swing.tree.DefaultMutableTreeNode node) {
          final Enumeration<TreeNode> en = node.depthFirstEnumeration();
          while (en.hasMoreElements()) {
            final TreeNode n = en.nextElement();
            if (n instanceof Event e && e.getDescription().contains(" scrambles ")) {
              out.add(e.getDescription());
            }
          }
        }
      }
    } catch (final RuntimeException e) {
      out.add("errore: " + e);
    }
    return out;
  }
}
