package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Step P7 (read only, every configuration): what the block {@code p7} of {@code riepilogo.json}
 * needs from the board.
 *
 * <ul>
 *   <li>F1/F3: every arrival by sea of US and British land units in a land territory (a unit that
 *       was at sea when the move step started, or that ends in a land territory it could not reach
 *       by land), per round, nation and territory: units, owner when the nation's turn started,
 *       taken at the end of the turn, unload zones, allied warships there at the end of the turn,
 *       the US transports there lost before the next US turn, still allied at the end of the next
 *       round; for the US landings of at least {@link #FORTE} units, at the start of the British
 *       turn of the same round the British transports that reach a sea zone next to it and the
 *       British land units they can load, then the British units landed there in that round;
 *   <li>F2: Allied attacks on land of {@link #NEUTRALE} (territories of the neutral with units of
 *       an Allied nation after its combat move), ex-neutral land of the Axis per round, neutral
 *       units captured by the Axis;
 *   <li>F4: per round the British land units at home when the British turn starts, the British
 *       purchases in PUs (land, sea, air, factories), British units landed in Europe ({@link
 *       #EUROPA}), British land units in Egypt and North Africa ({@link #NORD_AFRICA}) at the end
 *       of the round, British transports lost.
 * </ul>
 */
final class MonitorP7 {
  static final String USA = "Americans";
  static final String UK = "British";
  static final String NEUTRALE = "Neutral_True";
  static final String PATRIA_UK = "United Kingdom";
  static final List<String> ASSE = List.of("Germans", "Italians");
  static final int FORTE = 6;

  /** Continental Europe and its islands (WW2 Europe 1940). */
  static final Set<String> EUROPA =
      Set.of(
          "France",
          "Normandy Bordeaux",
          "Holland Belgium",
          "Western Germany",
          "Southern France",
          "Northern Italy",
          "Southern Italy",
          "Sicily",
          "Sardinia",
          "Norway",
          "Denmark",
          "Germany",
          "Greater Southern Germany",
          "Poland",
          "Slovakia Hungary",
          "Romania",
          "Bulgaria",
          "Yugoslavia",
          "Greece",
          "Albania",
          "Spain",
          "Portugal",
          "Finland",
          "Sweden",
          "Switzerland",
          "Baltic States",
          "Crete");

  /** Egypt and North Africa (WW2 Europe 1940). */
  static final Set<String> NORD_AFRICA =
      Set.of("Egypt", "Alexandria", "Tobruk", "Libya", "Tunisia", "Algeria", "Morocco");

  private final GameData data;
  private final Supplier<Map<String, Map<String, Integer>>> costs;

  /** Land units of the USA and the UK: where they were when the move step started. */
  private final Map<Unit, Territory> before = new HashMap<>();

  /** Owners when the current nation's turn started. */
  private final Map<String, String> owners = new HashMap<>();

  private String turnPlayer = "";
  private int turnRound = 0;

  /** The landings (one per round, nation, territory), in order. */
  private final Map<String, JsonObject> landings = new LinkedHashMap<>();

  /** Landing key → zone → US transports there at the end of the US turn. */
  private final Map<String, Set<String>> usTransportsAtEnd = new HashMap<>();

  /** Allied attacks on the neutral's land. */
  private final JsonArray neutralAttacks = new JsonArray();

  private final Set<String> neutralLand = new LinkedHashSet<>();
  private final Set<String> neutralUnits = new HashSet<>();
  private final Map<Integer, Integer> capturedByAxis = new TreeMap<>();

  /** Round → UK figures (F4). */
  private final Map<Integer, JsonObject> uk = new TreeMap<>();

  private final Set<String> beforePlace = new HashSet<>();
  private Set<String> ukTransports;

  /**
   * Step P9 (D): the US bridgeheads of at least {@link #FORTE} units on enemy land, taken, followed
   * from the end of the British turn of their round to the end of the next round (landing key →
   * landing row).
   */
  private final Map<String, JsonObject> heads = new LinkedHashMap<>();

  /** Step P9 (D): the counterattacks seen at the start of an Axis battle step. */
  private final List<Contrattacco> pending = new ArrayList<>();

  private record Contrattacco(
      JsonObject testa, Territory t, String player, List<Unit> att, List<Unit> def) {}

  MonitorP7(final GameData data, final Supplier<Map<String, Map<String, Integer>>> costs) {
    this.data = data;
    this.costs = costs;
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && NEUTRALE.equals(t.getOwner().getName())) {
        neutralLand.add(t.getName());
      }
      for (final Unit u : t.getUnits()) {
        if (NEUTRALE.equals(u.getOwner().getName())) {
          neutralUnits.add(u.getId().toString());
        }
      }
    }
    ukTransports = transports(UK);
  }

  // ========================================================================= steps

  void beforeStep(final String step, final String player, final int round) {
    try {
      if (ASSE.contains(player) && GameStep.isBattleStepName(step)) {
        counterattacksBefore(player);
      }
    } catch (final RuntimeException e) {
      // read only: never stops the game
    }
    if (!player.equals(turnPlayer) || round != turnRound) {
      endOfTurn(turnPlayer, turnRound);
      turnPlayer = player;
      turnRound = round;
      owners.clear();
      for (final Territory t : data.getMap().getTerritories()) {
        if (!t.isWater()) {
          owners.put(t.getName(), t.getOwner().getName());
        }
      }
      startOfTurn(player, round);
    }
    if ((USA.equals(player) || UK.equals(player))
        && (GameStep.isCombatMoveStepName(step) || GameStep.isNonCombatMoveStepName(step))) {
      before.clear();
      for (final Territory t : data.getMap().getTerritories()) {
        for (final Unit u : t.getMatches(landOf(player))) {
          before.put(u, t);
        }
      }
    }
    if (UK.equals(player) && GameStep.isPlaceStepName(step)) {
      beforePlace.clear();
      for (final Territory t : data.getMap().getTerritories()) {
        t.getMatches(Matches.unitIsOwnedBy(p(UK)))
            .forEach(u -> beforePlace.add(u.getId().toString()));
      }
    }
  }

  void afterStep(final String step, final String player, final int round) {
    try {
      if (GameStep.isBattleStepName(step)) {
        counterattacksAfter();
      }
      if ((USA.equals(player) || UK.equals(player))
          && (GameStep.isCombatMoveStepName(step) || GameStep.isNonCombatMoveStepName(step))) {
        arrivals(player, round, GameStep.isCombatMoveStepName(step));
      }
      if (GameStep.isCombatMoveStepName(step) && allied(player)) {
        attacksOnNeutrals(player, round);
      }
      if (UK.equals(player) && GameStep.isPlaceStepName(step) && !beforePlace.isEmpty()) {
        purchases(round);
      }
      captures(round);
      final Set<String> now = transports(UK);
      int lost = 0;
      for (final String id : ukTransports) {
        if (!now.contains(id)) {
          lost++;
        }
      }
      if (lost > 0) {
        add(ukRound(round), "trasporti_persi", lost);
      }
      ukTransports = now;
    } catch (final RuntimeException e) {
      // read only: never stops the game
    }
  }

  private void startOfTurn(final String player, final int round) {
    if (UK.equals(player)) {
      final Territory home = data.getMap().getTerritoryOrNull(PATRIA_UK);
      if (home != null) {
        ukRound(round)
            .addProperty("terra_in_patria_inizio_turno", home.getMatches(landOf(UK)).size());
      }
      // F3: the US landings in force of this round, seen from the British turn
      for (final JsonObject l : landings.values()) {
        if (l.get("round").getAsInt() == round
            && USA.equals(l.get("nazione").getAsString())
            && l.get("unita").getAsInt() >= FORTE) {
          ponte(l);
        }
      }
    }
    if (USA.equals(player)) {
      // step P9 (D): from now on a counterattack meets the bridgehead after the US moved again
      heads.values().forEach(l -> l.addProperty("usa_mosso_dopo", true));
      // the US transports in the unload zones of last round's landings, lost since
      for (final var e : usTransportsAtEnd.entrySet()) {
        final JsonObject l = landings.get(e.getKey());
        if (l == null || l.has("trasporti_usa_persi")) {
          continue;
        }
        final Set<String> alive = transports(USA);
        int lost = 0;
        for (final String id : e.getValue()) {
          if (!alive.contains(id)) {
            lost++;
          }
        }
        l.addProperty("trasporti_usa_persi", lost);
      }
      usTransportsAtEnd.clear();
    }
  }

  private void endOfTurn(final String player, final int round) {
    if (!(USA.equals(player) || UK.equals(player))) {
      return;
    }
    final GamePlayer me = p(player);
    for (final var e : landings.entrySet()) {
      final JsonObject l = e.getValue();
      if (l.get("round").getAsInt() != round || !player.equals(l.get("nazione").getAsString())) {
        continue;
      }
      final Territory t = data.getMap().getTerritoryOrNull(l.get("territorio").getAsString());
      if (t == null) {
        continue;
      }
      l.addProperty("presa", me != null && friendly(me, t));
      // the unload zones: those noted, else the sea zones next to it with own transports
      final Set<String> zones = new LinkedHashSet<>();
      l.getAsJsonArray("zone").forEach(z -> zones.add(z.getAsString()));
      if (zones.isEmpty()) {
        for (final Territory z : data.getMap().getNeighbors(t, Matches.territoryIsWater())) {
          if (z.anyUnitsMatch(
              Matches.unitIsOwnedBy(me)
                  .and(Matches.unitIsSeaTransportButNotCombatSeaTransport()))) {
            zones.add(z.getName());
          }
        }
      }
      int warships = 0;
      final Set<String> trs = new HashSet<>();
      for (final String zn : zones) {
        final Territory z = data.getMap().getTerritoryOrNull(zn);
        if (z == null) {
          continue;
        }
        warships +=
            z.getMatches(
                    u ->
                        me != null
                            && me.isAllied(u.getOwner())
                            && Matches.unitIsSea().test(u)
                            && !Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u))
                .size();
        if (USA.equals(player)) {
          z.getMatches(
                  Matches.unitIsOwnedBy(me)
                      .and(Matches.unitIsSeaTransportButNotCombatSeaTransport()))
              .forEach(u -> trs.add(u.getId().toString()));
        }
      }
      l.addProperty("navi_alleate_zona", warships);
      if (USA.equals(player)) {
        usTransportsAtEnd.put(e.getKey(), trs);
        l.addProperty("trasporti_usa_in_zona", trs.size());
      }
      if (UK.equals(player)) {
        // F3: the British units landed in a US landing territory of the round
        final JsonObject us = landings.get(key(round, USA, t.getName()));
        if (us != null) {
          us.addProperty("uk_sbarcate", l.get("unita").getAsInt());
        }
      }
    }
    if (UK.equals(player)) {
      bridgeheadsAtEndOfUk(round);
    }
  }

  // ========================================================================= P9 (D)

  /**
   * Step P9 (D), at the end of the British turn: each US bridgehead of the round (at least {@link
   * #FORTE} units, enemy land, taken) gets the allied defenders there and the Axis units in reach
   * (approximation: land units within their movement by land, planes within their movement), and is
   * followed until the end of the next round.
   */
  private void bridgeheadsAtEndOfUk(final int round) {
    final GamePlayer us = p(USA);
    if (us == null) {
      return;
    }
    for (final var e : landings.entrySet()) {
      final JsonObject l = e.getValue();
      if (l.get("round").getAsInt() != round
          || !USA.equals(l.get("nazione").getAsString())
          || l.get("unita").getAsInt() < FORTE
          || !("Asse".equals(l.get("categoria").getAsString())
              || "nemico".equals(l.get("categoria").getAsString()))
          || !(l.has("presa") && l.get("presa").getAsBoolean())) {
        continue;
      }
      final Territory t = data.getMap().getTerritoryOrNull(l.get("territorio").getAsString());
      if (t == null) {
        continue;
      }
      l.addProperty("alleata_fine_uk", friendly(us, t));
      l.addProperty("difensori_fine_uk", t.getMatches(alliedForce(us)).size());
      l.addProperty("asse_in_portata", inReach(t));
      if (!l.has("uk_sbarcate")) {
        l.addProperty("uk_sbarcate", 0);
      }
      l.addProperty("contrattaccata", false);
      l.add("contrattacchi", new JsonArray());
      heads.put(e.getKey(), l);
    }
  }

  private Predicate<Unit> alliedForce(final GamePlayer us) {
    return u ->
        (u.isOwnedBy(us) || us.isAllied(u.getOwner()))
            && (Matches.unitIsLand().test(u) || Matches.unitIsAir().test(u))
            && Matches.unitIsNotInfrastructure().test(u);
  }

  /** Axis land units within their movement by land, Axis planes within their movement. */
  private int inReach(final Territory t) {
    int n = 0;
    for (final Territory x : data.getMap().getTerritories()) {
      final List<Unit> axis =
          x.getMatches(
              u ->
                  ASSE.contains(u.getOwner().getName())
                      && Matches.unitIsNotInfrastructure().test(u)
                      && (Matches.unitIsLand().test(u) || Matches.unitIsAir().test(u)));
      if (axis.isEmpty()) {
        continue;
      }
      final int land =
          x.isWater() ? -1 : data.getMap().getDistance(x, t, Matches.territoryIsLand());
      final int any = data.getMap().getDistance(x, t);
      for (final Unit u : axis) {
        final int mv = Math.max(0, u.getUnitAttachment().getMovement(u.getOwner()));
        if (Matches.unitIsAir().test(u)) {
          if (any >= 0 && any <= mv) {
            n++;
          }
        } else if (x.equals(t) || (land >= 0 && land <= mv)) {
          n++;
        }
      }
    }
    return n;
  }

  /** Before an Axis battle step: the followed bridgeheads, still allied, with Axis units in. */
  private void counterattacksBefore(final String player) {
    pending.clear();
    final GamePlayer us = p(USA);
    if (us == null) {
      return;
    }
    for (final JsonObject l : heads.values()) {
      final Territory t = data.getMap().getTerritoryOrNull(l.get("territorio").getAsString());
      if (t == null || !friendly(us, t)) {
        continue;
      }
      final List<Unit> att = t.getMatches(u -> player.equals(u.getOwner().getName()) || isAxis(u));
      if (att.isEmpty()) {
        continue;
      }
      pending.add(
          new Contrattacco(
              l, t, player, new ArrayList<>(att), new ArrayList<>(t.getMatches(alliedForce(us)))));
    }
  }

  private static boolean isAxis(final Unit u) {
    return ASSE.contains(u.getOwner().getName());
  }

  /** After the battle step: the outcome and the losses of each counterattack. */
  private void counterattacksAfter() {
    for (final Contrattacco c : pending) {
      final Set<Unit> now = new HashSet<>(c.t().getUnits());
      final JsonObject o = new JsonObject();
      o.addProperty("da", c.player());
      o.addProperty("prima_del_turno_usa", !c.testa().has("usa_mosso_dopo"));
      o.addProperty("attaccanti", c.att().size());
      o.addProperty("difensori", c.def().size());
      o.addProperty("perse_asse", (int) c.att().stream().filter(u -> !now.contains(u)).count());
      o.addProperty("perse_alleate", (int) c.def().stream().filter(u -> !now.contains(u)).count());
      final boolean taken = ASSE.contains(c.t().getOwner().getName());
      o.addProperty("esito", taken ? "presa_asse" : "respinto");
      final JsonObject l = c.testa();
      l.addProperty("contrattaccata", true);
      if (!c.testa().has("usa_mosso_dopo")) {
        l.addProperty("contrattaccata_prima_del_turno_usa", true);
      }
      if (!l.has("contrattaccata_da")) {
        l.addProperty("contrattaccata_da", c.player());
      }
      l.addProperty("esito_contrattacco", taken ? "presa_asse" : "respinto");
      l.getAsJsonArray("contrattacchi").add(o);
    }
    pending.clear();
  }

  // ========================================================================= arrivals

  private void arrivals(final String player, final int round, final boolean combat) {
    final GamePlayer me = p(player);
    if (me == null) {
      return;
    }
    final Map<Unit, Territory> zoneOf = new HashMap<>();
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit tr : z.getMatches(Matches.unitIsSeaTransport())) {
        for (final Unit u : tr.getUnloaded()) {
          zoneOf.put(u, z);
        }
      }
    }
    final Map<String, Integer> count = new TreeMap<>();
    final Map<String, Set<String>> zones = new TreeMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        continue;
      }
      for (final Unit u : t.getMatches(landOf(player))) {
        final Territory from = before.get(u);
        if (from == null || from.equals(t) || !bySea(u, from, t)) {
          continue;
        }
        count.merge(t.getName(), 1, Integer::sum);
        final Territory z = zoneOf.get(u);
        if (z != null) {
          zones.computeIfAbsent(t.getName(), k -> new LinkedHashSet<>()).add(z.getName());
        }
      }
    }
    for (final var e : count.entrySet()) {
      final String k = key(round, player, e.getKey());
      final JsonObject l =
          landings.computeIfAbsent(
              k,
              x -> {
                final JsonObject o = new JsonObject();
                o.addProperty("round", round);
                o.addProperty("nazione", player);
                o.addProperty("territorio", e.getKey());
                final String owner = owners.getOrDefault(e.getKey(), "?");
                o.addProperty("proprietario_prima", owner);
                o.addProperty("categoria", categoria(me, owner));
                o.addProperty("unita", 0);
                o.addProperty("unita_combattimento", 0);
                o.add("zone", new JsonArray());
                return o;
              });
      add(l, "unita", e.getValue());
      if (combat) {
        add(l, "unita_combattimento", e.getValue());
      }
      final JsonArray zj = l.getAsJsonArray("zone");
      for (final String z : zones.getOrDefault(e.getKey(), Set.of())) {
        if (!zj.toString().contains("\"" + z + "\"")) {
          zj.add(z);
        }
      }
      if (UK.equals(player) && EUROPA.contains(e.getKey())) {
        add(ukRound(round), "sbarcate_europa", e.getValue());
      }
    }
    before.clear();
  }

  private boolean bySea(final Unit u, final Territory from, final Territory to) {
    if (from.isWater()) {
      return true;
    }
    final int d = data.getMap().getDistance(from, to, Matches.territoryIsLand());
    return d < 0 || d > Math.max(1, u.getUnitAttachment().getMovement(u.getOwner()));
  }

  private String categoria(final GamePlayer me, final String owner) {
    if (NEUTRALE.equals(owner)) {
      return "Neutral_True";
    }
    final GamePlayer o = p(owner);
    if (o == null || o.isNull()) {
      return "altro";
    }
    if (me.isAtWar(o)) {
      return ASSE.contains(owner) ? "Asse" : "nemico";
    }
    return me.equals(o) || me.isAllied(o) ? "alleato" : "altro";
  }

  // ========================================================================= F3

  private void ponte(final JsonObject l) {
    final GamePlayer me = p(UK);
    final Territory t = data.getMap().getTerritoryOrNull(l.get("territorio").getAsString());
    if (me == null || t == null) {
      return;
    }
    final Set<Territory> targets =
        new LinkedHashSet<>(data.getMap().getNeighbors(t, Matches.territoryIsWater()));
    final Predicate<Territory> free =
        z -> z.isWater() && !z.anyUnitsMatch(Matches.enemyUnit(me).and(Matches.unitIsSea()));
    int reach = 0;
    int places = 0;
    final Set<Unit> loadable = new LinkedHashSet<>();
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit tr :
          z.getMatches(
              Matches.unitIsOwnedBy(me)
                  .and(Matches.unitIsSeaTransportButNotCombatSeaTransport()))) {
        final int mv = Math.max(0, tr.getUnitAttachment().getMovement(me));
        final Map<Territory, Integer> fromStart = bfs(List.of(z), mv, free, true);
        final Map<Territory, Integer> toTarget = bfs(targets, mv, free, false);
        boolean ok = false;
        final Set<Territory> loadZones = new LinkedHashSet<>();
        for (final var e : fromStart.entrySet()) {
          final Integer back = toTarget.get(e.getKey());
          if (back != null && e.getValue() + back <= mv) {
            ok = true;
            loadZones.add(e.getKey());
          }
        }
        if (!ok) {
          continue;
        }
        reach++;
        places += Math.max(0, tr.getUnitAttachment().getTransportCapacity());
        tr.getTransporting().stream().filter(u -> u.isOwnedBy(me)).forEach(loadable::add);
        for (final Territory y : loadZones) {
          for (final Territory land : data.getMap().getNeighbors(y, Matches.territoryIsLand())) {
            if (!land.equals(t) && friendly(me, land)) {
              loadable.addAll(land.getMatches(landOf(UK)));
            }
          }
        }
      }
    }
    // greedy fill of the places with the cheapest loads
    final List<Integer> c = new ArrayList<>();
    loadable.forEach(u -> c.add(Math.max(1, u.getUnitAttachment().getTransportCost())));
    c.sort(Integer::compare);
    int left = places;
    int n = 0;
    for (final int x : c) {
      if (x <= left) {
        left -= x;
        n++;
      }
    }
    l.addProperty("uk_trasporti_raggiungono", reach);
    l.addProperty("uk_unita_caricabili", n);
    l.addProperty("uk_sbarcate", 0);
  }

  private Map<Territory, Integer> bfs(
      final java.util.Collection<Territory> starts,
      final int max,
      final Predicate<Territory> free,
      final boolean anyStart) {
    final Map<Territory, Integer> out = new LinkedHashMap<>();
    final Deque<Territory> q = new ArrayDeque<>();
    for (final Territory s : starts) {
      if (anyStart || free.test(s)) {
        out.putIfAbsent(s, 0);
        q.add(s);
      }
    }
    while (!q.isEmpty()) {
      final Territory x = q.poll();
      final int d = out.get(x);
      if (d >= max) {
        continue;
      }
      for (final Territory n : data.getMap().getNeighbors(x, Matches.territoryIsWater())) {
        if (!out.containsKey(n) && free.test(n)) {
          out.put(n, d + 1);
          q.add(n);
        }
      }
    }
    return out;
  }

  // ========================================================================= F2, F4

  private void attacksOnNeutrals(final String player, final int round) {
    final GamePlayer me = p(player);
    final GamePlayer neutral = p(NEUTRALE);
    // an attack only at war with the neutral (units in a friendly neutral's land are not one)
    if (me == null || neutral == null || !me.isAtWar(neutral)) {
      return;
    }
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()
          && NEUTRALE.equals(t.getOwner().getName())
          && t.anyUnitsMatch(Matches.unitIsOwnedBy(me))) {
        final JsonObject o = new JsonObject();
        o.addProperty("round", round);
        o.addProperty("nazione", player);
        o.addProperty("territorio", t.getName());
        o.addProperty("unita", t.getMatches(Matches.unitIsOwnedBy(me)).size());
        neutralAttacks.add(o);
      }
    }
  }

  private void captures(final int round) {
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        if (ASSE.contains(u.getOwner().getName()) && neutralUnits.remove(u.getId().toString())) {
          capturedByAxis.merge(round, 1, Integer::sum);
        }
      }
    }
  }

  private void purchases(final int round) {
    final Map<String, Integer> c = costs.get().getOrDefault(UK, Map.of());
    final JsonObject r = ukRound(round);
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getMatches(Matches.unitIsOwnedBy(p(UK)))) {
        if (!beforePlace.contains(u.getId().toString())) {
          final String kind =
              Matches.unitIsInfrastructure().test(u)
                  ? "pu_fabbriche"
                  : Matches.unitIsAir().test(u)
                      ? "pu_aerei"
                      : Matches.unitIsSea().test(u) ? "pu_navi" : "pu_terra";
          add(r, kind, c.getOrDefault(u.getType().getName(), 0));
        }
      }
    }
    beforePlace.clear();
  }

  // ========================================================================= snapshot

  JsonObject snapshot(final int round) {
    // still allied at the end of the next round
    for (final JsonObject l : landings.values()) {
      if (l.get("round").getAsInt() == round - 1 && !l.has("alleato_round_dopo")) {
        final Territory t = data.getMap().getTerritoryOrNull(l.get("territorio").getAsString());
        final GamePlayer me = p(l.get("nazione").getAsString());
        l.addProperty("alleato_round_dopo", t != null && me != null && friendly(me, t));
      }
    }
    // step P9 (D): the bridgeheads of last round are no longer followed
    heads.values().removeIf(l -> l.get("round").getAsInt() <= round - 1);
    final JsonObject o = new JsonObject();
    int exNeutralAxis = 0;
    for (final String n : neutralLand) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null && ASSE.contains(t.getOwner().getName())) {
        exNeutralAxis++;
      }
    }
    o.addProperty("ex_neutrali_asse", exNeutralAxis);
    o.addProperty("neutrali_catturate_asse", capturedByAxis.getOrDefault(round, 0));
    int na = 0;
    for (final String n : NORD_AFRICA) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null) {
        na += t.getMatches(landOf(UK)).size();
      }
    }
    final JsonObject r = ukRound(round).deepCopy();
    r.addProperty("terra_egitto_nord_africa", na);
    o.add("uk", r);
    return o;
  }

  JsonObject toJson() {
    final JsonObject o = new JsonObject();
    final JsonArray l = new JsonArray();
    landings.values().forEach(x -> l.add(x.deepCopy()));
    o.add("sbarchi", l);
    o.add("attacchi_neutrali_alleati", neutralAttacks.deepCopy());
    int captured = 0;
    for (final int v : capturedByAxis.values()) {
      captured += v;
    }
    o.addProperty("neutrali_catturate_asse", captured);
    return o;
  }

  // ========================================================================= helpers

  private static String key(final int round, final String nation, final String t) {
    return round + "|" + nation + "|" + t;
  }

  private JsonObject ukRound(final int round) {
    return uk.computeIfAbsent(round, k -> new JsonObject());
  }

  private static void add(final JsonObject o, final String k, final int v) {
    o.addProperty(k, (o.has(k) ? o.get(k).getAsInt() : 0) + v);
  }

  private @Nullable GamePlayer p(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private boolean allied(final String player) {
    final GamePlayer me = p(player);
    final GamePlayer us = p(USA);
    return me != null && us != null && (me.equals(us) || us.isAllied(me));
  }

  private static boolean friendly(final GamePlayer me, final Territory t) {
    return !t.getOwner().isNull() && (t.isOwnedBy(me) || me.isAllied(t.getOwner()));
  }

  private Predicate<Unit> landOf(final String nation) {
    return u ->
        nation.equals(u.getOwner().getName())
            && Matches.unitIsLand().test(u)
            && Matches.unitIsNotInfrastructure().test(u);
  }

  private Set<String> transports(final String nation) {
    final Set<String> out = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        for (final Unit u :
            t.getMatches(
                x ->
                    nation.equals(x.getOwner().getName())
                        && Matches.unitIsSeaTransportButNotCombatSeaTransport().test(x))) {
          out.add(u.getId().toString());
        }
      }
    }
    return out;
  }
}
