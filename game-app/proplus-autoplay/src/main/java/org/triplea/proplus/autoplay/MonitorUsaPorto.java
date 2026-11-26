package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.delegate.Matches;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Step P15 (point 1.3, read only): at the end of the USA's place step, the empty US transports at
 * the port (sea zones next to a US factory), the US land units in Eastern and Central US, and for
 * each sea zone where new US warships appeared the enemy units near it as Hard's local naval
 * superiority check counts them ({@code ProBattleUtils.territoryHasLocalNavalSuperiority}: enemy
 * sea units and enemy planes on land within max(3, distance of the closest enemy land over water +
 * 1)). The threat is a proxy of Hard's estimate, not its exact numbers.
 */
final class MonitorUsaPorto {
  static final String USA = "Americans";
  static final List<String> EST = List.of("Eastern United States", "Central United States");

  private final GameData data;
  private final Supplier<Map<String, Map<String, Integer>>> costs;
  private final Set<String> beforePlace = new HashSet<>();
  private final Map<Integer, JsonObject> perRound = new TreeMap<>();

  MonitorUsaPorto(final GameData data, final Supplier<Map<String, Map<String, Integer>>> costs) {
    this.data = data;
    this.costs = costs;
  }

  void beforeStep(final String step, final String player) {
    if (USA.equals(player) && GameStep.isPlaceStepName(step)) {
      beforePlace.clear();
      for (final Territory t : data.getMap().getTerritories()) {
        t.getMatches(u -> USA.equals(u.getOwner().getName()))
            .forEach(u -> beforePlace.add(u.getId().toString()));
      }
    }
  }

  void afterStep(final String step, final String player, final int round) {
    if (!USA.equals(player) || !GameStep.isPlaceStepName(step) || beforePlace.isEmpty()) {
      return;
    }
    final GamePlayer us = data.getPlayerList().getPlayerId(USA);
    if (us == null) {
      return;
    }
    final JsonObject o = new JsonObject();
    final Set<Territory> port = new LinkedHashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()
          && t.isOwnedBy(us)
          && t.anyUnitsMatch(Matches.unitCanProduceUnits().and(Matches.unitIsOwnedBy(us)))) {
        port.addAll(data.getMap().getNeighbors(t, Matches.territoryIsWater()));
      }
    }
    int empty = 0;
    for (final Territory z : port) {
      for (final Unit u :
          z.getMatches(
              Matches.unitIsOwnedBy(us)
                  .and(Matches.unitIsSeaTransportButNotCombatSeaTransport()))) {
        if (u.getTransporting().isEmpty()) {
          empty++;
        }
      }
    }
    o.addProperty("vuoti_porto", empty);
    int east = 0;
    for (final String n : EST) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null) {
        east +=
            t.getMatches(
                    Matches.unitIsOwnedBy(us)
                        .and(Matches.unitIsLand())
                        .and(Matches.unitIsNotInfrastructure()))
                .size();
      }
    }
    o.addProperty("terra_est", east);
    final Map<String, Integer> c = costs.get().getOrDefault(USA, Map.of());
    final JsonArray zones = new JsonArray();
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      int warships = 0;
      int pu = 0;
      for (final Unit u : z.getMatches(Matches.unitIsOwnedBy(us))) {
        if (!beforePlace.contains(u.getId().toString())
            && !Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)) {
          warships++;
          pu += c.getOrDefault(u.getType().getName(), 0);
        }
      }
      if (warships == 0) {
        continue;
      }
      int landDistance = ProUtils.getClosestEnemyLandTerritoryDistanceOverWater(data, us, z);
      if (landDistance <= 0) {
        landDistance = 10;
      }
      final int distance = Math.max(3, landDistance + 1);
      int enemySea = 0;
      int enemySeaTuv = 0;
      int enemyAir = 0;
      int enemyAirTuv = 0;
      final Set<Territory> near = new HashSet<>(data.getMap().getNeighbors(z, distance));
      near.add(z);
      for (final Territory t : near) {
        for (final Unit u : t.getMatches(Matches.enemyUnit(us))) {
          final int v =
              costs
                  .get()
                  .getOrDefault(u.getOwner().getName(), Map.of())
                  .getOrDefault(u.getType().getName(), 0);
          if (t.isWater() && Matches.unitIsSea().test(u)) {
            enemySea++;
            enemySeaTuv += v;
          } else if (!t.isWater() && Matches.unitIsAir().test(u)) {
            enemyAir++;
            enemyAirTuv += v;
          }
        }
      }
      final JsonObject zone = new JsonObject();
      zone.addProperty("zona", z.getName());
      zone.addProperty("unita_nuove", warships);
      zone.addProperty("pu_nuove", pu);
      zone.addProperty("distanza", distance);
      zone.addProperty("navi_nemiche", enemySea);
      zone.addProperty("tuv_navi_nemiche", enemySeaTuv);
      zone.addProperty("aerei_nemici", enemyAir);
      zone.addProperty("tuv_aerei_nemici", enemyAirTuv);
      zones.add(zone);
    }
    o.add("zone_flotta", zones);
    perRound.put(round, o);
    beforePlace.clear();
  }

  JsonObject snapshot(final int round) {
    final JsonObject o = perRound.get(round);
    return o == null ? new JsonObject() : o.deepCopy();
  }
}
