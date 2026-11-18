package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.BattleRecord;
import games.strategy.triplea.delegate.data.BattleRecords;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Step P1: what the block {@code p1} of {@code riepilogo.json} needs, for every configuration (Hard
 * included, to compare): the units the USA buy per round and type (new ids after their place step),
 * the rounds the USA attack and take Southern Italy and France, and per round the US land units by
 * region with Gibraltar as the base and the US transports.
 */
final class MonitorP1 {
  static final String USA = "Americans";
  static final List<String> CAPITALI = List.of("Southern Italy", "France");
  static final String BASE = "Gibraltar";

  private final GameData data;
  private final Set<String> beforePlace = new HashSet<>();

  /** Round → type → units bought by the USA. */
  private final Map<Integer, Map<String, Integer>> acquisti = new TreeMap<>();

  /** Territory → rounds of US attacks on it. */
  private final Map<String, JsonArray> attacchi = new TreeMap<>();

  /** Territory → first round it was owned by the USA. */
  private final JsonObject prese = new JsonObject();

  MonitorP1(final GameData data) {
    this.data = data;
  }

  /** Owners of the capitals when the US battle step started. */
  private final Map<String, String> ownerBefore = new java.util.HashMap<>();

  void beforeStep(final String step, final String player) {
    if (USA.equals(player) && GameStep.isBattleStepName(step)) {
      ownerBefore.clear();
      for (final String c : CAPITALI) {
        final Territory t = data.getMap().getTerritoryOrNull(c);
        if (t != null) {
          ownerBefore.put(c, t.getOwner().getName());
        }
      }
    }
    if (USA.equals(player) && GameStep.isPlaceStepName(step)) {
      beforePlace.clear();
      usUnits().forEach(u -> beforePlace.add(u.getId().toString()));
    }
  }

  void afterStep(final String step, final String player, final int round) {
    if (!USA.equals(player)) {
      return;
    }
    if (GameStep.isPlaceStepName(step) && !beforePlace.isEmpty()) {
      for (final Unit u : usUnits()) {
        if (!beforePlace.contains(u.getId().toString())) {
          acquisti
              .computeIfAbsent(round, k -> new TreeMap<>())
              .merge(u.getType().getName(), 1, Integer::sum);
        }
      }
      beforePlace.clear();
    }
    if (GameStep.isBattleStepName(step)) {
      final var all = data.getBattleRecordsList();
      final BattleRecords records = all == null ? null : all.getBattleRecordsMapCopy().get(round);
      if (records != null) {
        for (final BattleRecord r : BattleRecords.getAllRecords(records)) {
          final Territory site = field(r, "battleSite");
          final GamePlayer attacker = field(r, "attacker");
          if (site != null
              && attacker != null
              && USA.equals(attacker.getName())
              && CAPITALI.contains(site.getName())) {
            final JsonArray rounds = attacchi.computeIfAbsent(site.getName(), k -> new JsonArray());
            if (rounds.isEmpty() || rounds.get(rounds.size() - 1).getAsInt() != round) {
              rounds.add(round);
            }
          }
        }
      }
    }
    // taken by the USA: the owner changed in the US battle step (a liberated France goes back to
    // the French) from a nation at war with the USA
    if (GameStep.isBattleStepName(step)) {
      final GamePlayer us = data.getPlayerList().getPlayerId(USA);
      for (final String c : CAPITALI) {
        final Territory t = data.getMap().getTerritoryOrNull(c);
        final String before = ownerBefore.get(c);
        final GamePlayer was = before == null ? null : data.getPlayerList().getPlayerId(before);
        if (t != null
            && us != null
            && was != null
            && us.isAtWar(was)
            && !t.getOwner().getName().equals(before)
            && !us.isAtWar(t.getOwner())
            && !prese.has(c)) {
          prese.addProperty(c, round);
        }
      }
    }
  }

  private List<Unit> usUnits() {
    final GamePlayer us = data.getPlayerList().getPlayerId(USA);
    if (us == null) {
      return List.of();
    }
    final List<Unit> out = new java.util.ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      out.addAll(t.getMatches(Matches.unitIsOwnedBy(us)));
    }
    return out;
  }

  /** The round's snapshot: US land units by region (base Gibraltar), transports. */
  JsonObject snapshot() {
    final JsonObject o = new JsonObject();
    final GamePlayer us = data.getPlayerList().getPlayerId(USA);
    if (us == null) {
      return o;
    }
    final JsonObject reg = new JsonObject();
    try {
      games.strategy.triplea.ai.proplus.SbarcoCoordinato.regioni(data, us, BASE)
          .forEach(reg::addProperty);
    } catch (final RuntimeException e) {
      reg.addProperty("errore", e.toString());
    }
    o.add("regioni_usa", reg);
    int transports = 0;
    int empty = 0;
    int warships = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      for (final Unit u : t.getMatches(Matches.unitIsOwnedBy(us).and(Matches.unitIsSea()))) {
        if (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)) {
          transports++;
          if (!u.isTransporting()) {
            empty++;
          }
        } else {
          warships++;
        }
      }
    }
    o.addProperty("trasporti_usa", transports);
    o.addProperty("trasporti_usa_vuoti", empty);
    o.addProperty("navi_da_guerra_usa", warships);
    return o;
  }

  /** The game's totals. */
  JsonObject toJson() {
    final JsonObject o = new JsonObject();
    final JsonObject buy = new JsonObject();
    acquisti.forEach(
        (r, m) -> {
          final JsonObject x = new JsonObject();
          m.forEach(x::addProperty);
          buy.add(String.valueOf(r), x);
        });
    o.add("acquisti_usa", buy);
    final JsonObject att = new JsonObject();
    attacchi.forEach((k, v) -> att.add(k, v.deepCopy()));
    o.add("attacchi_usa", att);
    o.add("prese_usa", prese.deepCopy());
    return o;
  }

  @SuppressWarnings("unchecked")
  @Nullable
  private static <T> T field(final BattleRecord record, final String name) {
    try {
      final Field f = BattleRecord.class.getDeclaredField(name);
      f.setAccessible(true);
      return (T) f.get(record);
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }
}
