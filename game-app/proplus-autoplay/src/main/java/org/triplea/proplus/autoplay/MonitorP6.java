package org.triplea.proplus.autoplay;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.Matches;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Step P6: what the block {@code p6} of {@code riepilogo.json} needs from the board, for every
 * configuration (Hard included): at the end of each round the German land units in all and in
 * Western Europe ({@link #OVEST}), the US land units on board at the end of 3 or more rounds in a
 * row, and per round the British transports lost (units gone between two steps).
 */
final class MonitorP6 {
  static final String USA = "Americans";
  static final String UK = "British";
  static final String GERMANIA = "Germans";

  /** Western Europe of the step P6 (WW2 Europe 1940): France, Benelux, Western Germany, Italy. */
  static final List<String> OVEST =
      List.of(
          "France",
          "Normandy Bordeaux",
          "Holland Belgium",
          "Western Germany",
          "Southern France",
          "Northern Italy",
          "Southern Italy");

  private final GameData data;

  /** Round → British transports lost. */
  private final Map<Integer, Integer> ukTrasportiPersi = new TreeMap<>();

  private Set<String> ukTransports;

  /** US land unit id → round ends in a row on board. */
  private Map<String, Integer> aBordo = new HashMap<>();

  MonitorP6(final GameData data) {
    this.data = data;
    this.ukTransports = trasporti(UK);
  }

  void afterStep(final String step, final String player, final int round) {
    final Set<String> now = trasporti(UK);
    int lost = 0;
    for (final String id : ukTransports) {
      if (!now.contains(id)) {
        lost++;
      }
    }
    if (lost > 0) {
      ukTrasportiPersi.merge(round, lost, Integer::sum);
    }
    ukTransports = now;
  }

  private Set<String> trasporti(final String nation) {
    final Set<String> out = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      for (final Unit u :
          t.getMatches(
              u ->
                  nation.equals(u.getOwner().getName())
                      && Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u))) {
        out.add(u.getId().toString());
      }
    }
    return out;
  }

  /** The round's snapshot. */
  JsonObject snapshot(final int round) {
    final JsonObject o = new JsonObject();
    int total = 0;
    int west = 0;
    final Map<String, Integer> next = new HashMap<>();
    int longAboard = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        for (final Unit u :
            t.getMatches(u -> USA.equals(u.getOwner().getName()) && Matches.unitIsLand().test(u))) {
          final String id = u.getId().toString();
          final int n = aBordo.getOrDefault(id, 0) + 1;
          next.put(id, n);
          if (n >= 3) {
            longAboard++;
          }
        }
        continue;
      }
      final int g =
          t.getMatches(
                  u ->
                      GERMANIA.equals(u.getOwner().getName())
                          && Matches.unitIsLand().test(u)
                          && Matches.unitIsNotInfrastructure().test(u))
              .size();
      total += g;
      if (OVEST.contains(t.getName())) {
        west += g;
      }
    }
    aBordo = next;
    o.addProperty("tedeschi_terra", total);
    o.addProperty("tedeschi_terra_ovest", west);
    o.addProperty("usa_a_bordo_3_round", longAboard);
    o.addProperty("uk_trasporti_persi", ukTrasportiPersi.getOrDefault(round, 0));
    return o;
  }
}
