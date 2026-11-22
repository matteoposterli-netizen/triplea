package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.UnitUtils;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Step P13, lever {@link StrategaInterruttori#BOMBARDAMENTO_MOSCA} (Axis, the data's Germany, on
 * top of the siege; it replaces the siege's own raid while on): every German turn at war with
 * Russia, ALL the strategic bombers that reach the target and can land afterwards bomb the factory
 * of Moscow (at maximum damage: the Russian factory that produces most); escorts if the target has
 * interceptors, from the fighters not used by Hard or used in a Hard attack estimated under {@code
 * soglia_scorta_hard_pct}. Purchase, ADDED to Hard's from the siege's share: one bomber a turn
 * while Germany has fewer than {@code bombardieri_minimi}. Off: nothing.
 */
public final class BombardamentoMosca {
  private BombardamentoMosca() {}

  /** The raid planned in the combat move (simulation or real), by "nation|round". */
  private static final Map<String, JsonObject> RAID = new ConcurrentHashMap<>();

  public static boolean attivo(@Nullable final GameState data, @Nullable final GamePlayer player) {
    return LeveP13.attiva(
            data,
            player,
            StrategaInterruttori.BOMBARDAMENTO_MOSCA,
            LeveP13.Config.get().bombNazione())
        && Assedio.attivo(data, player);
  }

  /** At war with the data's enemy (Russia). */
  static boolean inGuerra(final GameState data, final GamePlayer player) {
    final GamePlayer enemy = data.getPlayerList().getPlayerId(LeveP13.Config.get().bombNemico());
    return enemy != null && player.isAtWar(enemy);
  }

  /** One bomber to buy (pure rule): while fewer than the minimum, and none bought by the siege. */
  static int daComprare(final int posseduti, final int minimi, final int giaComprati) {
    return posseduti < minimi && giaComprati == 0 ? 1 : 0;
  }

  // =============================================================================== target

  /** The target factory: Moscow's while not at maximum damage, else the enemy's most productive. */
  static Optional<Territory> bersaglio(final GameState data, final GamePlayer player) {
    final LeveP13.Config c = LeveP13.Config.get();
    final GamePlayer enemy = data.getPlayerList().getPlayerId(c.bombNemico());
    if (enemy == null) {
      return Optional.empty();
    }
    final Territory moscow = data.getMap().getTerritoryOrNull(c.capitaleRussa());
    if (moscow != null && moscow.isOwnedBy(enemy) && danneggiabile(moscow, enemy) != null) {
      return Optional.of(moscow);
    }
    Territory best = null;
    int bestProd = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || !t.isOwnedBy(enemy)) {
        continue;
      }
      final Unit f = danneggiabile(t, enemy);
      if (f == null) {
        continue;
      }
      final int p = UnitUtils.getHowMuchCanUnitProduce(f, t, true, true);
      if (p > bestProd
          || (p == bestProd && best != null && t.getName().compareTo(best.getName()) < 0)) {
        best = t;
        bestProd = p;
      }
    }
    return Optional.ofNullable(best);
  }

  /** The enemy's factory in {@code t} that can still take damage, or null. */
  @Nullable
  static Unit danneggiabile(final Territory t, final GamePlayer enemy) {
    for (final Unit u :
        t.getMatches(
            Matches.unitCanProduceUnitsAndCanBeDamaged().and(Matches.unitIsOwnedBy(enemy)))) {
      if (u.getHowMuchMoreDamageCanThisUnitTake(t) > 0) {
        return u;
      }
    }
    return null;
  }

  @Nullable
  private static Unit fabbrica(final Territory t) {
    return t.getMatches(Matches.unitCanProduceUnitsAndCanBeDamaged()).stream()
        .findFirst()
        .orElse(null);
  }

  // ============================================================================== purchase

  /**
   * The bombers held out of Hard's attacks at the purchase: every German bomber while at war with
   * Russia (the raid comes first; those out of range fly to a base in the non-combat move).
   */
  public static Set<Unit> trattenute(final ProData proData) {
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    if (!attivo(data, player) || !inGuerra(data, player)) {
      return Set.of();
    }
    return new LinkedHashSet<>(proprie(data, player, LeveP13.Config.get().bombTipo()));
  }

  /**
   * The siege's bomber purchase with the lever on: one more bomber while Germany has fewer than the
   * minimum (none bought by the siege this turn), if the share pays it. Writes the row.
   */
  public static Map<String, Integer> acquisto(
      final GameState data,
      final GamePlayer player,
      final Map<String, Integer> siege,
      final int puQuota) {
    final Map<String, Integer> out = new LinkedHashMap<>(siege);
    try {
      final LeveP13.Config c = LeveP13.Config.get();
      final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
      int spent = 0;
      for (final var e : siege.entrySet()) {
        spent += costs.getOrDefault(e.getKey(), 0) * e.getValue();
      }
      final int own = proprie(data, player, c.bombTipo()).size();
      final int cost = costs.getOrDefault(c.bombTipo(), 0);
      final int n = daComprare(own, c.bombardieriMinimi(), siege.getOrDefault(c.bombTipo(), 0));
      final boolean pays = n > 0 && cost > 0 && cost <= puQuota - spent;
      if (pays) {
        out.merge(c.bombTipo(), 1, Integer::sum);
      }
      final JsonObject row = new JsonObject();
      row.addProperty("bombardieri_posseduti", own);
      row.addProperty("comprati_assedio", siege.getOrDefault(c.bombTipo(), 0));
      row.addProperty("comprati_leva", pays ? 1 : 0);
      row.addProperty("pu_quota", puQuota);
      row.addProperty("pu_gia_spesi_assedio", spent);
      LeveP13.registra(
          data,
          player,
          LeveP13.TIPO_BOMBARDAMENTO,
          "P13_BOMBARDIERE_ACQUISTO",
          pays
              ? "un bombardiere dalla quota dell'assedio"
              : n > 0 ? "quota insufficiente" : "bombardieri sufficienti o gia' comprati",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P13, acquisto del bombardiere: " + e);
    }
    return out;
  }

  // ================================================================================== raid

  /**
   * Combat move (after Hard chose its attacks; on the simulation copy or the real board): the raid
   * with every bomber not used by Hard that reaches the target and can land, escorts if needed.
   * Replaces the siege's raid.
   */
  public static void bombarda(final ProData proData, final Map<Territory, ProTerritory> attackMap) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    if (player == null || !attivo(data, player) || !inGuerra(data, player)) {
      return;
    }
    try {
      final LeveP13.Config c = LeveP13.Config.get();
      final int round = data.getSequence().getRound();
      final Optional<Territory> target = bersaglio(data, player);
      final JsonObject raid = new JsonObject();
      raid.addProperty("round", round);
      if (target.isEmpty()) {
        raid.addProperty("bersaglio", "");
        RAID.put(player.getName() + "|" + round, raid);
        return;
      }
      final Territory t = target.get();
      final Set<Unit> committed = new HashSet<>();
      for (final ProTerritory p : attackMap.values()) {
        committed.addAll(p.getUnits());
        committed.addAll(p.getBombers());
        committed.addAll(p.getAmphibAttackMap().keySet());
        p.getAmphibAttackMap().values().forEach(committed::addAll);
        committed.addAll(p.getBombardTerritoryMap().keySet());
      }
      final List<Unit> bombers = raggiungibili(proData, player, t, c.bombTipo(), committed);
      final int interceptors =
          t.getMatches(Matches.enemyUnit(player).and(Matches.unitCanIntercept())).size();
      final List<Unit> escorts = new ArrayList<>();
      int fromHard = 0;
      if (!bombers.isEmpty() && interceptors > 0) {
        final List<Unit> free = raggiungibili(proData, player, t, c.scortaTipo(), committed);
        free.removeIf(u -> !u.getUnitAttachment().canEscort());
        for (final Unit u : free) {
          if (escorts.size() < interceptors) {
            escorts.add(u);
          }
        }
        // then the fighters of Hard's attacks estimated under the threshold
        if (escorts.size() < interceptors) {
          final Set<Unit> weak = new HashSet<>();
          for (final ProTerritory p : attackMap.values()) {
            if (p.getBattleResult() != null
                && p.getBattleResult().getWinPercentage() < c.sogliaScortaHard()) {
              weak.addAll(p.getUnits());
            }
          }
          final Set<Unit> other = new HashSet<>(committed);
          other.removeAll(weak);
          final List<Unit> candidates = raggiungibili(proData, player, t, c.scortaTipo(), other);
          candidates.removeIf(u -> !u.getUnitAttachment().canEscort() || !weak.contains(u));
          for (final Unit u : candidates) {
            if (escorts.size() >= interceptors) {
              break;
            }
            for (final ProTerritory p : attackMap.values()) {
              p.getUnits().remove(u);
            }
            escorts.add(u);
            fromHard++;
          }
        }
      }
      if (!bombers.isEmpty()) {
        final ProTerritory pt = attackMap.computeIfAbsent(t, x -> new ProTerritory(x, proData));
        pt.getBombers().addAll(bombers);
        pt.getBombers().addAll(escorts);
      }
      final Unit f = fabbrica(t);
      raid.addProperty("bersaglio", t.getName());
      raid.addProperty("danno_prima", f == null ? 0 : f.getUnitDamage());
      raid.addProperty("fabbrica", f == null ? "" : f.getId().toString());
      raid.addProperty("intercettori", interceptors);
      raid.addProperty("scorta_da_attacchi_hard", fromHard);
      final JsonArray ids = new JsonArray();
      bombers.forEach(u -> ids.add(u.getId().toString()));
      raid.add("bombardieri", ids);
      final JsonArray eids = new JsonArray();
      escorts.forEach(u -> eids.add(u.getId().toString()));
      raid.add("scorta", eids);
      raid.addProperty("bombardieri_posseduti", proprie(data, player, c.bombTipo()).size());
      RAID.put(player.getName() + "|" + round, raid);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P13, bombardamento: " + e);
    }
  }

  /**
   * Non-combat move (real board, before Hard's): the row of the raid planned this turn with its
   * outcome (damage done, planes lost). Never throws.
   */
  public static void esito(final GameState data, final GamePlayer player) {
    if (!attivo(data, player) || !inGuerra(data, player)) {
      return;
    }
    try {
      final int round = data.getSequence().getRound();
      final JsonObject raid = RAID.remove(player.getName() + "|" + round);
      final JsonObject row = new JsonObject();
      if (raid == null
          || !raid.has("bombardieri")
          || raid.getAsJsonArray("bombardieri").isEmpty()) {
        row.addProperty("bersaglio", raid == null ? "" : raid.get("bersaglio").getAsString());
        row.addProperty(
            "bombardieri_posseduti", proprie(data, player, LeveP13.Config.get().bombTipo()).size());
        LeveP13.registra(
            data,
            player,
            LeveP13.TIPO_BOMBARDAMENTO,
            "P13_BOMBARDAMENTO_NO",
            raid == null ? "nessun piano d'attacco con la leva" : "nessun bombardiere in portata",
            row);
        return;
      }
      final Set<String> alive = new HashSet<>();
      for (final Territory t : data.getMap().getTerritories()) {
        t.getUnits().forEach(u -> alive.add(u.getId().toString()));
      }
      int lost = 0;
      for (final var e : raid.getAsJsonArray("bombardieri")) {
        if (!alive.contains(e.getAsString())) {
          lost++;
        }
      }
      int escortsLost = 0;
      for (final var e : raid.getAsJsonArray("scorta")) {
        if (!alive.contains(e.getAsString())) {
          escortsLost++;
        }
      }
      final Territory t = data.getMap().getTerritoryOrNull(raid.get("bersaglio").getAsString());
      final Unit f = t == null ? null : fabbrica(t);
      final int after = f == null ? 0 : f.getUnitDamage();
      final int before = raid.get("danno_prima").getAsInt();
      row.addProperty("bersaglio", raid.get("bersaglio").getAsString());
      row.addProperty("bombardieri_usati", raid.getAsJsonArray("bombardieri").size());
      row.addProperty("scorta", raid.getAsJsonArray("scorta").size());
      row.addProperty("scorta_da_attacchi_hard", raid.get("scorta_da_attacchi_hard").getAsInt());
      row.addProperty("intercettori", raid.get("intercettori").getAsInt());
      row.addProperty("danno_prima", before);
      row.addProperty("danno_dopo", after);
      row.addProperty("danno_inflitto", Math.max(0, after - before));
      row.addProperty("bombardieri_persi", lost);
      row.addProperty("scorta_persa", escortsLost);
      LeveP13.registra(
          data,
          player,
          LeveP13.TIPO_BOMBARDAMENTO,
          "P13_BOMBARDAMENTO_ESITO",
          "bombardamento della fabbrica con tutti i bombardieri in portata",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P13, esito del bombardamento: " + e);
    }
  }

  /**
   * Non-combat move (real board, before Hard's): the bombers that cannot reach the target from
   * where they are fly to the own territory nearest to the target from which they reach it and come
   * back, where the best enemy attack wins under {@code soglia_contrattacco_pct}. Returns the
   * bombers moved or already in range (Hard leaves them). Never throws.
   */
  public static Set<Unit> basi(
      final ProData proData,
      final games.strategy.triplea.ai.pro.util.ProOddsCalculator calc,
      final games.strategy.triplea.delegate.remote.IMoveDelegate del) {
    final Set<Unit> held = new LinkedHashSet<>();
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    if (!attivo(data, player) || !inGuerra(data, player)) {
      return held;
    }
    try {
      final LeveP13.Config c = LeveP13.Config.get();
      final Optional<Territory> target = bersaglio(data, player);
      final var type = data.getUnitTypeList().getUnitType(c.bombTipo());
      if (target.isEmpty() || type.isEmpty()) {
        return held;
      }
      final Territory t = target.get();
      final int range = type.get().getUnitAttachment().getMovement(player);
      // candidate bases: own land from which the target is reachable with a way back
      final List<Territory> bases = new ArrayList<>();
      for (final Territory x : data.getMap().getTerritories()) {
        if (x.isWater() || !x.isOwnedBy(player) || x.equals(t)) {
          continue;
        }
        final int d = data.getMap().getDistance(x, t);
        if (d >= 1 && 2 * d <= range) {
          bases.add(x);
        }
      }
      bases.sort(
          Comparator.comparingInt((Territory x) -> data.getMap().getDistance(x, t))
              .thenComparing(Territory::getName));
      Territory base = null;
      double baseWin = 0;
      for (final Territory x : bases) {
        if (Matches.isTerritoryAllied(player).negate().test(x)) {
          continue;
        }
        final LeveItalia.Minaccia m = LeveItalia.minaccia(proData, calc, x);
        final List<Unit> def =
            x.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotInfrastructure()));
        final double w = m.vuota() ? 0 : LeveItalia.vittoria(proData, calc, x, m, def);
        if (w < c.sogliaContrattacco()) {
          base = x;
          baseWin = w;
          break;
        }
      }
      int moved = 0;
      int inRange = 0;
      final Map<Unit, Territory> where = new LinkedHashMap<>();
      for (final Territory x : data.getMap().getTerritories()) {
        for (final Unit u : x.getUnits()) {
          if (u.isOwnedBy(player) && u.getType().getName().equals(c.bombTipo())) {
            where.put(u, x);
          }
        }
      }
      for (final var e : where.entrySet()) {
        final Unit u = e.getKey();
        final Territory at = e.getValue();
        if (at.isWater()) {
          continue;
        }
        final int d = data.getMap().getDistance(at, t);
        if (d >= 1 && 2 * d <= range && at.isOwnedBy(player)) {
          held.add(u);
          inRange++;
          continue;
        }
        if (base == null || u.getMovementLeft().signum() <= 0) {
          continue;
        }
        final Optional<Route> r =
            data.getMap()
                .getRouteForUnit(
                    at, base, ProMatches.territoryCanMoveAirUnits(data, player, false), u, player);
        if (r.isPresent()
            && r.get().numberOfSteps() <= u.getMovementLeft().intValue()
            && del.performMove(new games.strategy.engine.data.MoveDescription(List.of(u), r.get()))
                .isEmpty()) {
          held.add(u);
          moved++;
        }
      }
      final JsonObject row = new JsonObject();
      row.addProperty("fase", "basi");
      row.addProperty("bersaglio", t.getName());
      row.addProperty("base", base == null ? "" : base.getName());
      row.addProperty("vittoria_nemica_base", LeveP13.r1(baseWin));
      row.addProperty("bombardieri", where.size());
      row.addProperty("gia_in_portata", inRange);
      row.addProperty("spostati_alla_base", moved);
      LeveP13.registra(
          data,
          player,
          LeveP13.TIPO_BOMBARDAMENTO,
          "P13_BOMBARDIERE_BASE",
          "bombardieri fuori portata verso la base sicura piu' vicina al bersaglio",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P13, basi dei bombardieri: " + e);
    }
    return held;
  }

  // ============================================================================== helpers

  private static List<Unit> proprie(
      final GameState data, final GamePlayer player, final String type) {
    final List<Unit> out = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnitCollection().stream()
          .filter(u -> u.isOwnedBy(player) && u.getType().getName().equals(type))
          .forEach(out::add);
    }
    return out;
  }

  /** Own units of {@code type} not in {@code committed} that reach {@code target} and can land. */
  static List<Unit> raggiungibili(
      final ProData proData,
      final GamePlayer player,
      final Territory target,
      final String type,
      final Set<Unit> committed) {
    final GameState data = proData.getData();
    final List<Unit> out = new ArrayList<>();
    final Map<Unit, Integer> length = new LinkedHashMap<>();
    for (final Territory from : data.getMap().getTerritories()) {
      for (final Unit u : from.getUnitCollection()) {
        if (!u.isOwnedBy(player)
            || !u.getType().getName().equals(type)
            || committed.contains(u)
            || u.getMovementLeft().signum() <= 0
            || from.equals(target)) {
          continue;
        }
        final Optional<Route> r =
            data.getMap()
                .getRouteForUnit(
                    from,
                    target,
                    ProMatches.territoryCanMoveAirUnitsAndNoAa(data, player, true),
                    u,
                    player);
        if (r.isEmpty()) {
          continue;
        }
        final int left = u.getMovementLeft().intValue() - r.get().numberOfSteps();
        if (left < 1 || !puoAtterrare(data, player, target, left)) {
          continue;
        }
        out.add(u);
        length.put(u, r.get().numberOfSteps());
      }
    }
    out.sort(
        Comparator.comparingInt((Unit u) -> length.get(u))
            .thenComparing(u -> u.getId().toString()));
    return out;
  }

  private static boolean puoAtterrare(
      final GameState data, final GamePlayer player, final Territory from, final int range) {
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || t.equals(from)) {
        continue;
      }
      if (Matches.airCanLandOnThisAlliedNonConqueredLandTerritory(player).test(t)) {
        final int d = data.getMap().getDistance(from, t);
        if (d >= 1 && d <= range) {
          return true;
        }
      }
    }
    return false;
  }
}
