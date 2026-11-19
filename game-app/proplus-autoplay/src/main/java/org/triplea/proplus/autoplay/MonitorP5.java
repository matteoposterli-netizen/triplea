package org.triplea.proplus.autoplay;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Step P5: what the block {@code p5} of {@code riepilogo.json} needs from the board, for every
 * configuration (Hard included): the USA's purchases per round in PUs by kind (land, transports,
 * warships, planes: new units after the US place step), at the end of each round the US transports,
 * warships, land units at home and on board, the British warships, and per round the US transports
 * and the British warships lost (units gone between two steps).
 */
final class MonitorP5 {
  static final String USA = "Americans";
  static final String UK = "British";

  private final GameData data;
  private final Supplier<Map<String, Map<String, Integer>>> costs;
  private final Set<String> beforePlace = new HashSet<>();

  /** Round → kind → PUs of the USA's purchases. */
  private final Map<Integer, Map<String, Integer>> acquisti = new TreeMap<>();

  /** Round → {usa_trasporti, uk_navi_da_guerra} lost. */
  private final Map<Integer, Map<String, Integer>> perse = new TreeMap<>();

  private Set<String> usTransports = new HashSet<>();
  private Set<String> ukWarships = new HashSet<>();

  MonitorP5(final GameData data, final Supplier<Map<String, Map<String, Integer>>> costs) {
    this.data = data;
    this.costs = costs;
    usTransports = ids(USA, true);
    ukWarships = ids(UK, false);
  }

  void beforeStep(final String step, final String player) {
    if (USA.equals(player) && GameStep.isPlaceStepName(step)) {
      beforePlace.clear();
      for (final Territory t : data.getMap().getTerritories()) {
        t.getMatches(owned(USA)).forEach(u -> beforePlace.add(u.getId().toString()));
      }
    }
  }

  void afterStep(final String step, final String player, final int round) {
    if (USA.equals(player) && GameStep.isPlaceStepName(step) && !beforePlace.isEmpty()) {
      final Map<String, Integer> c = costs.get().getOrDefault(USA, Map.of());
      for (final Territory t : data.getMap().getTerritories()) {
        for (final Unit u : t.getMatches(owned(USA))) {
          if (!beforePlace.contains(u.getId().toString())) {
            acquisti
                .computeIfAbsent(round, k -> new TreeMap<>())
                .merge(tipo(u), c.getOrDefault(u.getType().getName(), 0), Integer::sum);
          }
        }
      }
      beforePlace.clear();
    }
    final Set<String> tr = ids(USA, true);
    final Set<String> ws = ids(UK, false);
    int lostTr = 0;
    for (final String id : usTransports) {
      if (!tr.contains(id)) {
        lostTr++;
      }
    }
    int lostWs = 0;
    for (final String id : ukWarships) {
      if (!ws.contains(id)) {
        lostWs++;
      }
    }
    if (lostTr > 0) {
      perse
          .computeIfAbsent(round, k -> new TreeMap<>())
          .merge("usa_trasporti", lostTr, Integer::sum);
    }
    if (lostWs > 0) {
      perse
          .computeIfAbsent(round, k -> new TreeMap<>())
          .merge("uk_navi_da_guerra", lostWs, Integer::sum);
    }
    usTransports = tr;
    ukWarships = ws;
  }

  /** land, trasporti, navi (warships), aerei, altro (infrastructure). */
  static String tipo(final Unit u) {
    if (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)) {
      return "trasporti";
    }
    if (Matches.unitIsSea().test(u)) {
      return "navi";
    }
    if (Matches.unitIsAir().test(u)) {
      return "aerei";
    }
    if (Matches.unitIsLand().test(u) && Matches.unitIsNotInfrastructure().test(u)) {
      return "terra";
    }
    return "altro";
  }

  private static java.util.function.Predicate<Unit> owned(final String nation) {
    return u -> nation.equals(u.getOwner().getName());
  }

  /** Ids of the nation's transports ({@code transports}) or warships at sea. */
  private Set<String> ids(final String nation, final boolean transports) {
    final Set<String> out = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      for (final Unit u : t.getMatches(owned(nation).and(Matches.unitIsSea()))) {
        if (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u) == transports) {
          out.add(u.getId().toString());
        }
      }
    }
    return out;
  }

  /**
   * The homeland: the territories with the nation as original owner, owned by it, joined by land
   * through such territories to its capital (not the far home territories of other continents).
   */
  private Set<Territory> patria(final GamePlayer us) {
    final Set<Territory> seen = new HashSet<>();
    final java.util.Deque<Territory> queue = new java.util.ArrayDeque<>();
    TerritoryAttachment.getAllCapitals(us, data.getMap()).stream()
        .filter(t -> t.isOwnedBy(us))
        .forEach(
            t -> {
              seen.add(t);
              queue.add(t);
            });
    while (!queue.isEmpty()) {
      final Territory x = queue.poll();
      for (final Territory n : data.getMap().getNeighbors(x, Matches.territoryIsLand())) {
        final boolean home =
            n.isOwnedBy(us)
                && TerritoryAttachment.get(n)
                    .flatMap(TerritoryAttachment::getOriginalOwner)
                    .map(us::equals)
                    .orElse(false);
        if (home && seen.add(n)) {
          queue.add(n);
        }
      }
    }
    return seen;
  }

  /** The round's snapshot. */
  JsonObject snapshot(final int round) {
    final JsonObject o = new JsonObject();
    final GamePlayer us = data.getPlayerList().getPlayerId(USA);
    int transports = 0;
    int warships = 0;
    int home = 0;
    int aboard = 0;
    int ukWar = 0;
    final Set<Territory> patria = us == null ? Set.of() : patria(us);
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        for (final Unit u : t.getMatches(Matches.unitIsSea())) {
          final boolean tr = Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u);
          if (USA.equals(u.getOwner().getName())) {
            if (tr) {
              transports++;
            } else {
              warships++;
            }
          } else if (UK.equals(u.getOwner().getName()) && !tr) {
            ukWar++;
          }
        }
        aboard += t.getMatches(owned(USA).and(Matches.unitIsLand())).size();
        continue;
      }
      if (patria.contains(t)) {
        home +=
            t.getMatches(
                    owned(USA).and(Matches.unitIsLand()).and(Matches.unitIsNotInfrastructure()))
                .size();
      }
    }
    o.addProperty("usa_trasporti", transports);
    o.addProperty("usa_navi_da_guerra", warships);
    o.addProperty("usa_terra_in_patria", home);
    o.addProperty("usa_terra_a_bordo", aboard);
    o.addProperty("uk_navi_da_guerra", ukWar);
    final JsonObject buy = new JsonObject();
    acquisti.getOrDefault(round, Map.of()).forEach(buy::addProperty);
    o.add("usa_acquisti_pu", buy);
    final JsonObject lost = new JsonObject();
    perse.getOrDefault(round, Map.of()).forEach(lost::addProperty);
    o.add("perse", lost);
    return o;
  }
}
