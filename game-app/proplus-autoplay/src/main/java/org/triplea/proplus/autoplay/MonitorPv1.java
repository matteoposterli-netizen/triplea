package org.triplea.proplus.autoplay;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.triplea.java.collections.IntegerMap;

/**
 * Step PV1: the board at the end of each round, for every configuration: the empty US transports at
 * the port (sea zones next to a US factory), the British land units in America and on board of US
 * transports, the German land army (TUV), the owners of Normandy and Norway. Names from the lever's
 * data ({@code pv1.json}) where they exist.
 */
final class MonitorPv1 {
  static final String USA = "Americans";
  static final String UK = "British";
  static final String GERMANIA = "Germans";
  static final List<String> SBARCHI = List.of("Normandy Bordeaux", "Norway");

  private final GameData data;

  MonitorPv1(final GameData data) {
    this.data = data;
  }

  JsonObject snapshot() {
    final JsonObject json = new JsonObject();
    final GamePlayer us = data.getPlayerList().getPlayerId(USA);
    final GamePlayer uk = data.getPlayerList().getPlayerId(UK);
    final GamePlayer de = data.getPlayerList().getPlayerId(GERMANIA);
    if (us != null) {
      final Set<Territory> port = new LinkedHashSet<>();
      for (final Territory t : data.getMap().getTerritories()) {
        if (!t.isWater()
            && t.isOwnedBy(us)
            && t.anyUnitsMatch(Matches.unitCanProduceUnits().and(Matches.unitIsOwnedBy(us)))) {
          port.addAll(data.getMap().getNeighbors(t, Matches.territoryIsWater()));
        }
      }
      int empty = 0;
      int all = 0;
      for (final Territory z : port) {
        for (final Unit u :
            z.getMatches(
                Matches.unitIsOwnedBy(us)
                    .and(Matches.unitIsSeaTransportButNotCombatSeaTransport()))) {
          all++;
          if (u.getTransporting().isEmpty()) {
            empty++;
          }
        }
      }
      json.addProperty("usa_trasporti_porto", all);
      json.addProperty("usa_vuoti_porto", empty);
    }
    if (uk != null) {
      int america = 0;
      for (final String n : games.strategy.triplea.ai.proplus.LevePv1.america()) {
        final Territory t = data.getMap().getTerritoryOrNull(n);
        if (t != null) {
          america +=
              t.getMatches(
                      Matches.unitIsOwnedBy(uk)
                          .and(Matches.unitIsLand())
                          .and(Matches.unitIsNotInfrastructure()))
                  .size();
        }
      }
      json.addProperty("uk_in_america", america);
      int aboard = 0;
      for (final Territory z : data.getMap().getTerritories()) {
        if (z.isWater()) {
          for (final Unit u : z.getMatches(Matches.unitIsOwnedBy(uk).and(Matches.unitIsLand()))) {
            if (u.getTransportedBy() != null && !u.getTransportedBy().isOwnedBy(uk)) {
              aboard++;
            }
          }
        }
      }
      json.addProperty("uk_a_bordo_alleato", aboard);
    }
    if (de != null) {
      final IntegerMap<UnitType> costs = new TuvCostsCalculator().getCostsForTuv(de);
      int tuv = 0;
      for (final Territory t : data.getMap().getTerritories()) {
        for (final Unit u :
            t.getMatches(
                Matches.unitIsOwnedBy(de)
                    .and(Matches.unitIsLand())
                    .and(Matches.unitIsNotInfrastructure()))) {
          tuv += costs.getInt(u.getType());
        }
      }
      json.addProperty("tuv_terra_tedesco", tuv);
    }
    final JsonObject owners = new JsonObject();
    for (final String n : SBARCHI) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null) {
        owners.addProperty(n, t.getOwner().getName());
      }
    }
    json.add("proprietari", owners);
    return json;
  }
}
