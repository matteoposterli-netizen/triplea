package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.proplus.Compiti;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Step P2: what the block {@code p2} of {@code riepilogo.json} needs, read from the board at the
 * end of every round, for every configuration (Hard included, to compare): Egypt's owner, the US
 * and British land units on board, and for the US landings of the round whether British land units
 * are on the same territory at the end of the round (joint) and, for the landings of the round
 * before, whether the territory is still allied.
 */
final class MonitorP2 {
  static final String USA = "Americans";
  static final String UK = "British";
  static final String EGITTO = "Egypt";
  static final List<String> A_BORDO = List.of(USA, UK);

  private final GameData data;
  private final Function<String, String> allianceOf;

  /** The US landings of the round before (territory names), for "still allied one round later". */
  private final JsonArray ieri = new JsonArray();

  MonitorP2(final GameData data, final Function<String, String> allianceOf) {
    this.data = data;
    this.allianceOf = allianceOf;
  }

  /** Step P2-bis: round → nation → sea transports placed by the USA and the United Kingdom. */
  private final Map<Integer, Map<String, Integer>> piazzati = new TreeMap<>();

  private final Set<String> primaDelPiazzamento = new HashSet<>();

  void beforeStep(final String step, final String player) {
    if (A_BORDO.contains(player) && GameStep.isPlaceStepName(step)) {
      primaDelPiazzamento.clear();
      trasporti(player).forEach(u -> primaDelPiazzamento.add(u.getId().toString()));
    }
  }

  void afterStep(final String step, final String player, final int round) {
    if (A_BORDO.contains(player) && GameStep.isPlaceStepName(step)) {
      for (final Unit u : trasporti(player)) {
        if (!primaDelPiazzamento.contains(u.getId().toString())) {
          piazzati.computeIfAbsent(round, k -> new TreeMap<>()).merge(player, 1, Integer::sum);
        }
      }
      primaDelPiazzamento.clear();
    }
  }

  private List<Unit> trasporti(final String nation) {
    final GamePlayer p = data.getPlayerList().getPlayerId(nation);
    if (p == null) {
      return List.of();
    }
    final ArrayList<Unit> out = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      out.addAll(t.getMatches(Matches.unitIsOwnedBy(p).and(Matches.unitIsSeaTransport())));
    }
    return out;
  }

  /**
   * Step P2-bis: ids of the land units of {@code nation} the task unloaded this round (property
   * {@link Compiti#KEY_SCARICATE}); empty without the task.
   */
  static Set<String> scaricateDalCompito(
      final GameData data, final String nation, final int round) {
    final Set<String> out = new HashSet<>();
    try {
      if (data.getProperties().get(Compiti.KEY_SCARICATE + nation) instanceof String s
          && !s.isBlank()) {
        final JsonObject o = JsonParser.parseString(s).getAsJsonObject();
        if (o.get("round").getAsInt() == round) {
          o.getAsJsonArray("ids").forEach(e -> out.add(e.getAsString()));
        }
      }
    } catch (final RuntimeException e) {
      // no register: every landing is Hard's
    }
    return out;
  }

  /** The round's snapshot ({@code landings} = every landing of the game so far). */
  JsonObject snapshot(final int round, final JsonArray landings) {
    final JsonObject o = new JsonObject();
    final Territory egypt = data.getMap().getTerritoryOrNull(EGITTO);
    o.addProperty(
        "egitto_asse",
        egypt != null && "Axis".equals(allianceOf.apply(egypt.getOwner().getName())));
    final JsonObject board = new JsonObject();
    for (final String n : A_BORDO) {
      final GamePlayer p = data.getPlayerList().getPlayerId(n);
      int k = 0;
      if (p != null) {
        for (final Territory t : data.getMap().getTerritories()) {
          if (t.isWater()) {
            k += t.getMatches(Matches.unitIsOwnedBy(p).and(Matches.unitIsLand())).size();
          }
        }
      }
      board.addProperty(n, k);
    }
    o.add("a_bordo", board);
    // step P2-bis: transports placed this round (USA, United Kingdom)
    final JsonObject placed = new JsonObject();
    for (final String n : A_BORDO) {
      placed.addProperty(n, piazzati.getOrDefault(round, Map.of()).getOrDefault(n, 0));
    }
    o.add("trasporti_piazzati", placed);
    // the US landings of this round: British land units on the same territory at the end of it
    final JsonArray today = new JsonArray();
    final JsonArray ukLanded = new JsonArray();
    for (final JsonElement e : landings) {
      final JsonObject l = e.getAsJsonObject();
      if (l.get("round").getAsInt() != round) {
        continue;
      }
      final String who = l.get("nazione").getAsString();
      final String where = l.get("territorio").getAsString();
      if (UK.equals(who) && !contains(ukLanded, where)) {
        ukLanded.add(where);
      }
      if (!USA.equals(who) || containsTerritory(today, where)) {
        continue;
      }
      final Territory t = data.getMap().getTerritoryOrNull(where);
      final JsonObject x = new JsonObject();
      x.addProperty("territorio", where);
      x.addProperty("unita_usa_sbarcate", l.get("unita").getAsInt());
      x.addProperty("usa_fine_round", t == null ? 0 : land(t, USA));
      x.addProperty("britannici_fine_round", t == null ? 0 : land(t, UK));
      x.addProperty("alleato_fine_round", t != null && alleato(t));
      today.add(x);
    }
    o.add("sbarchi_usa", today);
    o.add("sbarchi_uk", ukLanded);
    // the US landings of the round before: still allied now?
    final JsonArray before = new JsonArray();
    for (final JsonElement e : ieri) {
      final Territory t = data.getMap().getTerritoryOrNull(e.getAsString());
      final JsonObject x = new JsonObject();
      x.addProperty("territorio", e.getAsString());
      x.addProperty("alleato", t != null && alleato(t));
      before.add(x);
    }
    o.add("sbarchi_usa_round_prima", before);
    while (!ieri.isEmpty()) {
      ieri.remove(0);
    }
    today.forEach(x -> ieri.add(x.getAsJsonObject().get("territorio").getAsString()));
    return o;
  }

  private boolean alleato(final Territory t) {
    return "Allies".equals(allianceOf.apply(t.getOwner().getName()));
  }

  private int land(final Territory t, final String nation) {
    final GamePlayer p = data.getPlayerList().getPlayerId(nation);
    return p == null
        ? 0
        : t.getMatches(
                Matches.unitIsOwnedBy(p)
                    .and(Matches.unitIsLand())
                    .and(Matches.unitIsNotInfrastructure()))
            .size();
  }

  private static boolean contains(final JsonArray a, final String s) {
    for (final JsonElement e : a) {
      if (e.getAsString().equals(s)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsTerritory(final JsonArray a, final String s) {
    for (final JsonElement e : a) {
      if (e.getAsJsonObject().get("territorio").getAsString().equals(s)) {
        return true;
      }
    }
    return false;
  }

  static List<Unit> none() {
    return List.of();
  }
}
