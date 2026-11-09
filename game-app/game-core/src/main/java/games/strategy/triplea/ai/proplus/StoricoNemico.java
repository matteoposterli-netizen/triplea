package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.BattleRecords;
import games.strategy.triplea.util.TuvUtils;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step PROVALLM: how the enemy REALLY answered in this game. At the end of the turn of a nation of
 * an operation (non-combat move), for the territories the alliance holds that are worth a
 * counterattack (the ones the nation took this turn and the operation's target held), the enemy
 * units that could reach them (the Pro AI's enemy attack options, the maximum) are noted with their
 * place; at the next turn of that nation, where those units went: into the territory (or dead after
 * a battle there) = committed, elsewhere, or still. Saved in {@value #PREFIX}{@code <operation
 * id>}. Only with the LLM deciding the operation (it reads the summary).
 */
public final class StoricoNemico {
  public static final String PREFIX = "Pro+ operazione storico: ";

  /** Territories noted per turn at most. */
  static final int MAX_TERRITORI = 4;

  /** Evaluated notes kept in the summary for the LLM. */
  static final int MAX_RIASSUNTO = 8;

  private StoricoNemico() {}

  /**
   * Notes the enemy units in reach of the territories {@code player} took this turn and of {@code
   * held} (the operation's target, when the alliance's).
   */
  public static void registra(
      final ProData proData,
      final ProOddsCalculator calc,
      final String operation,
      final Collection<Territory> held) {
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final List<Territory> list = new ArrayList<>();
    held.stream()
        .filter(t -> !t.isWater() && Matches.isTerritoryAllied(player).test(t))
        .forEach(list::add);
    final var tracker = data.getBattleDelegate().getBattleTracker();
    data.getMap().getTerritories().stream()
        .filter(t -> !t.isWater() && !list.contains(t))
        .filter(t -> t.isOwnedBy(player) && tracker.wasConquered(t))
        .sorted(Comparator.comparing((Territory t) -> -value(t)).thenComparing(Territory::getName))
        .forEach(list::add);
    final List<Territory> noted = list.stream().limit(MAX_TERRITORI).toList();
    if (noted.isEmpty()) {
      return;
    }
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateEnemyAttackOptions(noted, noted);
    final Map<Unit, Territory> where = locations(data.getMap());
    final JsonArray ts = new JsonArray();
    for (final Territory t : noted) {
      final ProTerritory max = tm.getEnemyAttackOptions().getMax(t);
      final List<Unit> units = new ArrayList<>();
      if (max != null) {
        max.getMaxUnits().stream().filter(Matches.unitIsNotInfrastructure()).forEach(units::add);
        max.getMaxAmphibUnits().stream()
            .filter(Matches.unitIsNotInfrastructure())
            .filter(u -> !units.contains(u))
            .forEach(units::add);
      }
      final JsonObject o = new JsonObject();
      o.addProperty("territorio", t.getName());
      final JsonArray us = new JsonArray();
      double total = 0;
      for (final Unit u : units) {
        final double tuv = TuvUtils.getTuv(List.of(u), proData.getUnitValueMap());
        total += tuv;
        final Territory from = where.get(u);
        us.add(u.getId() + "|" + Math.round(tuv) + "|" + (from == null ? "" : from.getName()));
      }
      o.addProperty("tuv_in_portata", Math.round(total));
      o.add("unita", us);
      ts.add(o);
    }
    final JsonObject entry = new JsonObject();
    entry.addProperty("round", data.getSequence().getRound());
    entry.addProperty("nazione", player.getName());
    entry.add("territori", ts);
    final JsonArray all = leggi(data.getProperties(), operation);
    all.add(entry);
    salva(data.getProperties(), operation, all);
  }

  /**
   * At the start of {@code player}'s turn: what the enemy did with the units noted at its previous
   * turns (committed / elsewhere / still, taken back or not, attacked or not).
   */
  public static void valuta(final GameState data, final GamePlayer player, final String operation) {
    final JsonArray all = leggi(data.getProperties(), operation);
    final int round = data.getSequence().getRound();
    Map<String, Territory> where = null;
    boolean changed = false;
    for (final JsonElement e : all) {
      final JsonObject entry = e.getAsJsonObject();
      if (entry.has("valutata")
          || !player.getName().equals(entry.get("nazione").getAsString())
          || entry.get("round").getAsInt() >= round) {
        continue;
      }
      if (where == null) {
        where = new HashMap<>();
        for (final Map.Entry<Unit, Territory> l : locations(data.getMap()).entrySet()) {
          where.put(l.getKey().getId().toString(), l.getValue());
        }
      }
      final int from = entry.get("round").getAsInt();
      for (final JsonElement te : entry.getAsJsonArray("territori")) {
        final JsonObject o = te.getAsJsonObject();
        final Territory t = data.getMap().getTerritoryOrNull(o.get("territorio").getAsString());
        if (t == null) {
          continue;
        }
        final boolean attacked = attacked(data, player, t, from, round);
        double committed = 0;
        double still = 0;
        final Map<String, Double> elsewhere = new LinkedHashMap<>();
        for (final JsonElement ue : o.getAsJsonArray("unita")) {
          final String[] p = ue.getAsString().split("\\|", -1);
          final double tuv = Double.parseDouble(p[1]);
          final Territory now = where.get(p[0]);
          if (now == null) {
            // dead: counted as committed when the enemy attacked this territory
            if (attacked) {
              committed += tuv;
            }
          } else if (now.equals(t)) {
            committed += tuv;
          } else if (now.getName().equals(p[2])) {
            still += tuv;
          } else {
            elsewhere.merge(now.getName(), tuv, Double::sum);
          }
        }
        final double max = o.get("tuv_in_portata").getAsDouble();
        o.remove("unita");
        o.addProperty("tuv_impegnato", Math.round(committed));
        o.addProperty(
            "frazione_impegnata", max <= 0 ? 0 : Math.round(committed / max * 100) / 100.0);
        o.addProperty("attaccato", attacked);
        o.addProperty("ripreso", !Matches.isTerritoryAllied(player).test(t));
        o.addProperty("tuv_ferme", Math.round(still));
        final JsonArray to = new JsonArray();
        elsewhere.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(3)
            .forEach(
                x -> {
                  final JsonObject d = new JsonObject();
                  d.addProperty("verso", x.getKey());
                  d.addProperty("tuv", Math.round(x.getValue()));
                  to.add(d);
                });
        o.add("spostate_altrove", to);
      }
      entry.addProperty("valutata", round);
      changed = true;
    }
    if (changed) {
      salva(data.getProperties(), operation, all);
    }
  }

  /** The last evaluated notes and their totals, for the LLM's input. */
  public static JsonObject riassunto(final GameProperties props, final String operation) {
    final List<JsonObject> rows = new ArrayList<>();
    for (final JsonElement e : leggi(props, operation)) {
      final JsonObject entry = e.getAsJsonObject();
      if (!entry.has("valutata")) {
        continue;
      }
      for (final JsonElement te : entry.getAsJsonArray("territori")) {
        final JsonObject o = te.getAsJsonObject().deepCopy();
        if (!o.has("tuv_in_portata") || o.get("tuv_in_portata").getAsDouble() <= 0) {
          // no enemy in reach: nothing to learn about its answer
          continue;
        }
        o.addProperty("round", entry.get("round").getAsInt());
        o.addProperty("dopo_il_turno_di", entry.get("nazione").getAsString());
        rows.add(o);
      }
    }
    final JsonObject out = new JsonObject();
    final double max = rows.stream().mapToDouble(r -> r.get("tuv_in_portata").getAsDouble()).sum();
    final double used = rows.stream().mapToDouble(r -> r.get("tuv_impegnato").getAsDouble()).sum();
    out.addProperty("osservazioni", rows.size());
    out.addProperty(
        "frazione_impegnata_media",
        rows.isEmpty()
            ? 0
            : Math.round(
                    rows.stream()
                            .mapToDouble(r -> r.get("frazione_impegnata").getAsDouble())
                            .average()
                            .orElse(0)
                        * 100)
                / 100.0);
    out.addProperty(
        "frazione_impegnata_pesata_tuv", max <= 0 ? 0 : Math.round(used / max * 100) / 100.0);
    out.addProperty(
        "attaccati", rows.stream().filter(r -> r.get("attaccato").getAsBoolean()).count());
    out.addProperty("ripresi", rows.stream().filter(r -> r.get("ripreso").getAsBoolean()).count());
    out.addProperty(
        "nota",
        "per ogni territorio preso dall'alleanza: forze nemiche in portata (massimo teorico) e quante ne"
            + " ha davvero mandate (entrate o morte attaccandolo) entro il turno successivo della"
            + " nazione");
    final JsonArray last = new JsonArray();
    rows.stream().skip(Math.max(0, rows.size() - MAX_RIASSUNTO)).forEach(last::add);
    out.add("ultimi", last);
    return out;
  }

  /** Whether an enemy of {@code player} fought in {@code t} in rounds {@code from}..{@code to}. */
  static boolean attacked(
      final GameState data,
      final GamePlayer player,
      final Territory t,
      final int from,
      final int to) {
    if (data.getBattleRecordsList() == null) {
      return false;
    }
    final var map = data.getBattleRecordsList().getBattleRecordsMapCopy();
    for (int r = from; r <= to; r++) {
      final BattleRecords records = map.get(r);
      if (records == null) {
        continue;
      }
      for (final GamePlayer e : data.getPlayerList().getPlayers()) {
        if (!e.isNull()
            && data.getRelationshipTracker().isAtWar(player, e)
            && BattleRecords.getWereThereBattlesInTerritoriesMatching(
                BattleRecords.getAllRecords(records), e, null, "any", List.of(t))) {
          return true;
        }
      }
    }
    return false;
  }

  static Map<Unit, Territory> locations(final GameMap map) {
    final Map<Unit, Territory> where = new HashMap<>();
    for (final Territory t : map.getTerritories()) {
      for (final Unit u : t.getUnits()) {
        where.put(u, t);
      }
    }
    return where;
  }

  private static double value(final Territory t) {
    return TerritoryAttachment.get(t)
        .map(a -> a.getProduction() + 10.0 * a.getVictoryCity() + (a.isCapital() ? 20 : 0))
        .orElse(0.0);
  }

  public static JsonArray leggi(final GameProperties props, final String operation) {
    if (props.get(PREFIX + operation) instanceof String s && !s.isBlank()) {
      try {
        return JsonParser.parseString(s).getAsJsonArray();
      } catch (final RuntimeException e) {
        // a fresh list
      }
    }
    return new JsonArray();
  }

  private static void salva(
      final GameProperties props, final String operation, final JsonArray all) {
    props.set(PREFIX + operation, (Serializable) null);
    props.set(PREFIX + operation, all.toString());
  }
}
