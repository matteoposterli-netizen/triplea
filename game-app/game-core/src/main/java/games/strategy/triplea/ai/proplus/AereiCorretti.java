package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.move.validation.AirMovementValidator;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step P14: the air units in Pro+'s own attacks (switch {@link
 * StrategaInterruttori#AEREI_CORRETTI}, per alliance, default off). Two corrections of Hard's
 * attack options ({@code ProTerritoryManager.findAirMoveOptions}), nothing else of Hard changes:
 *
 * <ol>
 *   <li><b>air base</b>: the range is the movement the engine gives at the start of the combat move
 *       ({@code MoveDelegate.giveBonusMovementToUnits}: the best {@code givesMovement} of an allied
 *       unit in the territory that is not disabled, e.g. a damaged air base gives nothing), not the
 *       movement left of the purchase's copy where the bonus is not given yet;
 *   <li><b>carriers</b>: a carrier (own or allied) counts as a landing when it is in a sea zone
 *       with no enemy unit (no battle there this turn), Hard's plan does not move it, and it has a
 *       free place. After Hard chose its attacks ({@link #rivedi}), every air unit that can only
 *       land on a carrier gets one free place of a reachable such zone (the shortest range first),
 *       otherwise it leaves the attack. In the non-combat move, before Hard's, those air units fly
 *       to their carrier ({@link #atterra}, each move checked by the engine) and the carriers stay
 *       there.
 * </ol>
 *
 * Off: every answer is Hard's (the tuning's defaults).
 */
public final class AereiCorretti {
  private AereiCorretti() {}

  static final String TIPO = "p14_aerei";

  /** The switch is on for {@code player}'s alliance (strategist's nations only). */
  public static boolean attivo(@Nullable final GameState data, @Nullable final GamePlayer player) {
    return LeveP13.accesa(data, player, StrategaInterruttori.AEREI_CORRETTI);
  }

  // ===================================================================================== state

  /** The turn's state (one AI = one nation): options added, the plan's carrier landings. */
  public static final class Stato {
    private String turno = "";

    /** "unit id|target" → only with a carrier, every option added this turn. */
    private final Map<String, Boolean> aggiunte = new LinkedHashMap<>();

    /** Unit id → its air unit type, for the added options. */
    private final Map<String, String> tipi = new HashMap<>();

    /** Air unit id → sea zone of its carrier, from the last plan of the turn. */
    private final Map<UUID, String> assegnazioni = new LinkedHashMap<>();

    /** The last plan's attacks with an added air unit (register rows). */
    private JsonArray attacchi = new JsonArray();

    /** Air units the last plan sent without a landing and took out of the attack. */
    private JsonArray tolti = new JsonArray();

    /** Round 1: the air units of the first plan of the turn (Hard alone), "from>target:type". */
    private @Nullable JsonArray primoPiano;

    private int piani;

    private void turno(final GameState data, final GamePlayer player) {
      final String key = player.getName() + "|" + data.getSequence().getRound();
      if (!key.equals(turno)) {
        turno = key;
        aggiunte.clear();
        tipi.clear();
        assegnazioni.clear();
        attacchi = new JsonArray();
        tolti = new JsonArray();
        primoPiano = null;
        piani = 0;
      }
    }
  }

  // ===================================================================================== range

  /**
   * The range of {@code unit} in the own attacks: Hard's movement left once the unit moved this
   * turn (the bonus was given), otherwise its movement plus the bonus the engine gives at the start
   * of the combat move in {@code from}.
   */
  static BigDecimal raggio(
      final GameState data,
      final GamePlayer player,
      final Unit unit,
      final Territory from,
      final BigDecimal hardRange) {
    if (unit.getAlreadyMoved().signum() != 0 || !unit.getUnitAttachment().isAir()) {
      return hardRange;
    }
    final int base = unit.getUnitAttachment().getMovement(unit.getOwner());
    return new BigDecimal(base + bonus(data, player, unit, from));
  }

  /**
   * The bonus of {@code unit} in {@code t} as {@code MoveDelegate.giveBonusMovementToUnits} gives
   * it (air units: the allied units in the territory only), 0 without the map's property.
   */
  static int bonus(
      final GameState data, final GamePlayer player, final Unit unit, final Territory t) {
    if (!Properties.getUnitsMayGiveBonusMovement(data.getProperties())
        || !Matches.unitCanBeGivenBonusMovementByFacilitiesInItsTerritory(t, player).test(unit)
        || !Matches.isUnitAllied(player).test(unit)) {
      return 0;
    }
    int best = Integer.MIN_VALUE;
    for (final Unit giver : t.getUnits()) {
      if (!Matches.alliedUnit(player).test(giver)
          || Matches.unitIsDisabled().test(giver)
          || Matches.unitIsBeingTransported().test(giver)) {
        continue;
      }
      final int b = giver.getUnitAttachment().getGivesMovement().getInt(unit.getType());
      if (b != 0 && b > best) {
        best = b;
      }
    }
    if (best == Integer.MIN_VALUE) {
      return 0;
    }
    return Math.max(best, -unit.getUnitAttachment().getMovement(player));
  }

  // ================================================================================== carriers

  /**
   * Sea zones with an own or allied carrier, no enemy unit, and a free place or own air units that
   * may come back to it (the review gives the places).
   */
  static Set<Territory> zoneConPortaerei(final GameState data, final GamePlayer player) {
    final Set<Territory> out = new LinkedHashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()
          || !t.anyUnitsMatch(Matches.unitIsCarrier().and(Matches.alliedUnit(player)))
          || t.anyUnitsMatch(Matches.enemyUnit(player))) {
        continue;
      }
      final int capacity =
          AirMovementValidator.carrierCapacity(
              t.getMatches(Matches.unitIsCarrier().and(Matches.alliedUnit(player))), t);
      final int cost =
          AirMovementValidator.carrierCost(
              t.getMatches(Matches.unitIsAir().and(Matches.alliedUnit(player))));
      if (capacity > cost
          || t.anyUnitsMatch(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsAir())
                  .and(Matches.unitCanLandOnCarrier()))) {
        out.add(t);
      }
    }
    return out;
  }

  /** An option Hard would not have: counted for the register. */
  static void aggiunta(
      final Stato s,
      final ProData proData,
      final Unit unit,
      final Territory target,
      final boolean carrier) {
    s.turno(proData.getData(), proData.getPlayer());
    s.aggiunte.merge(unit.getId() + "|" + target.getName(), carrier, (a, b) -> a && b);
    s.tipi.put(unit.getId().toString(), unit.getType().getName());
  }

  // ==================================================================================== review

  /**
   * After Hard chose its attacks: every air unit that can only land on a carrier gets a free place
   * (the shortest range first), otherwise it leaves the attack. The register rows of the plan.
   */
  static void rivedi(
      final Stato s,
      final ProData proData,
      final ProOddsCalculator calc,
      final Map<Territory, ProTerritory> attackMap) {
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    s.turno(data, player);
    s.piani++;
    s.assegnazioni.clear();
    s.attacchi = new JsonArray();
    s.tolti = new JsonArray();
    final GameMap map = data.getMap();
    final Predicate<Territory> canFly =
        ProMatches.territoryCanMoveAirUnitsAndNoAa(data, player, true);
    final Predicate<Territory> canLand =
        ProMatches.territoryCanLandAirUnits(player, true, List.of(), List.of());

    // the units Hard's plan moves (they leave their zone)
    final Set<Unit> moving = new LinkedHashSet<>();
    for (final ProTerritory patd : attackMap.values()) {
      moving.addAll(patd.getUnits());
      moving.addAll(patd.getBombers());
      patd.getAmphibAttackMap()
          .forEach(
              (k, v) -> {
                moving.add(k);
                moving.addAll(v);
              });
      moving.addAll(patd.getBombardTerritoryMap().keySet());
    }
    // free places per safe zone (carriers that stay, air that stays)
    final Map<Territory, Integer> free = new LinkedHashMap<>();
    for (final Territory z : zoneConPortaerei(data, player)) {
      final List<Unit> carriers =
          z.getMatches(
              Matches.unitIsCarrier()
                  .and(Matches.alliedUnit(player))
                  .and(u -> !moving.contains(u)));
      if (carriers.isEmpty()) {
        continue;
      }
      final int capacity = AirMovementValidator.carrierCapacity(carriers, z);
      final int staying =
          AirMovementValidator.carrierCost(
              z.getMatches(
                  Matches.unitIsAir()
                      .and(Matches.alliedUnit(player))
                      .and(u -> !moving.contains(u))));
      if (capacity - staying > 0) {
        free.put(z, capacity - staying);
      }
    }

    // the air units that can land only on a carrier, the shortest range first
    record Volo(ProTerritory patd, Unit unit, Territory from, BigDecimal remaining) {}
    final List<Volo> voli = new ArrayList<>();
    if (s.primoPiano == null && data.getSequence().getRound() == 1) {
      s.primoPiano = new JsonArray();
    }
    for (final ProTerritory patd : attackMap.values()) {
      final Territory t = patd.getTerritory();
      for (final Unit u : patd.getUnits()) {
        if (!Matches.unitIsAir().test(u) || !u.isOwnedBy(player)) {
          continue;
        }
        final Territory from = proData.getUnitTerritory(u);
        if (from == null) {
          continue;
        }
        if (s.piani == 1 && s.primoPiano != null) {
          s.primoPiano.add(from.getName() + ">" + t.getName() + ":" + u.getType().getName());
        }
        if (!Matches.unitCanLandOnCarrier().test(u)) {
          continue;
        }
        final Optional<Route> route = map.getRouteForUnit(from, t, canFly, u, player);
        if (route.isEmpty()) {
          continue;
        }
        final BigDecimal cost = route.get().getMovementCost(u);
        final BigDecimal range = raggio(data, player, u, from, u.getMovementLeft());
        final BigDecimal remaining = range.subtract(cost);
        if (remaining.signum() < 0) {
          continue;
        }
        if (remaining.compareTo(cost) >= 0 && !from.isWater()) {
          continue; // it can fly back home (Hard's rule)
        }
        final boolean land =
            map.getNeighborsByMovementCost(t, remaining, canFly).stream().anyMatch(canLand);
        if (!land) {
          voli.add(new Volo(patd, u, from, remaining));
        }
      }
    }
    voli.sort(Comparator.comparing(Volo::remaining));
    final Set<ProTerritory> changed = new LinkedHashSet<>();
    final Map<UUID, String> landing = new HashMap<>();
    for (final Volo v : voli) {
      final Set<Territory> reach =
          map.getNeighborsByMovementCost(v.patd().getTerritory(), v.remaining(), canFly);
      final int need = AirMovementValidator.carrierCost(v.unit());
      Territory chosen = null;
      if (reach.contains(v.from()) && free.getOrDefault(v.from(), 0) >= need) {
        chosen = v.from(); // back to its own carrier
      } else {
        for (final Territory z : free.keySet()) {
          if (reach.contains(z) && free.get(z) >= need) {
            chosen = z;
            break;
          }
        }
      }
      if (chosen == null) {
        v.patd().getUnits().remove(v.unit());
        changed.add(v.patd());
        final JsonObject o = new JsonObject();
        o.addProperty("bersaglio", v.patd().getTerritory().getName());
        o.addProperty("tipo", v.unit().getType().getName());
        o.addProperty("da", v.from().getName());
        s.tolti.add(o);
        continue;
      }
      free.merge(chosen, -need, Integer::sum);
      s.assegnazioni.put(v.unit().getId(), chosen.getName());
      landing.put(v.unit().getId(), chosen.getName());
    }
    for (final ProTerritory patd : changed) {
      patd.setBattleResult(null);
    }

    // the register: the attacks with an added air unit
    for (final ProTerritory patd : attackMap.values()) {
      final Territory t = patd.getTerritory();
      final JsonArray planes = new JsonArray();
      for (final Unit u : patd.getUnits()) {
        final Boolean carrier = s.aggiunte.get(u.getId() + "|" + t.getName());
        if (carrier == null && !landing.containsKey(u.getId())) {
          continue;
        }
        final JsonObject p = new JsonObject();
        p.addProperty("tipo", u.getType().getName());
        final Territory from = proData.getUnitTerritory(u);
        p.addProperty("da", from == null ? "?" : from.getName());
        p.addProperty(
            "motivo",
            carrier == null ? "hard" : Boolean.TRUE.equals(carrier) ? "portaerei" : "aeroporto");
        if (landing.containsKey(u.getId())) {
          p.addProperty("portaerei", landing.get(u.getId()));
        }
        planes.add(p);
      }
      if (planes.isEmpty() || patd.getUnits().isEmpty()) {
        continue;
      }
      if (patd.getBattleResult() == null) {
        try {
          patd.estimateBattleResult(calc, player);
        } catch (final RuntimeException e) {
          ProLogger.warn("[Pro+] P14, stima: " + e);
        }
      }
      final JsonObject o = new JsonObject();
      o.addProperty("bersaglio", t.getName());
      o.addProperty(
          "vittoria",
          patd.getBattleResult() == null
              ? -1
              : LeveP13.r1(patd.getBattleResult().getWinPercentage()));
      o.addProperty("unita", patd.getUnits().size());
      o.add("aerei", planes);
      s.attacchi.add(o);
    }
  }

  // ============================================================================== registration

  /** After the combat move (real game): the turn's options, attacks and first plan. */
  static void registraCombattimento(final Stato s, final GameState data, final GamePlayer player) {
    s.turno(data, player);
    final JsonObject row = new JsonObject();
    row.addProperty("fase", "combattimento");
    int airBase = 0;
    int carrier = 0;
    final Set<String> airBasePlanes = new LinkedHashSet<>();
    final Set<String> carrierPlanes = new LinkedHashSet<>();
    for (final Map.Entry<String, Boolean> e : s.aggiunte.entrySet()) {
      final String id = e.getKey().substring(0, e.getKey().indexOf('|'));
      if (e.getValue()) {
        carrier++;
        carrierPlanes.add(id);
      } else {
        airBase++;
        airBasePlanes.add(id);
      }
    }
    row.addProperty("opzioni_aeroporto", airBase);
    row.addProperty("opzioni_portaerei", carrier);
    row.addProperty("aerei_aeroporto", airBasePlanes.size());
    row.addProperty("aerei_portaerei", carrierPlanes.size());
    row.addProperty("piani", s.piani);
    row.add("attacchi", s.attacchi.deepCopy());
    row.add("tolti", s.tolti.deepCopy());
    row.addProperty("assegnati_portaerei", s.assegnazioni.size());
    if (s.primoPiano != null) {
      row.add("primo_piano", s.primoPiano.deepCopy());
    }
    LeveP13.registra(data, player, TIPO, "P14_AEREI", "aerei nei propri attacchi", row);
  }

  // ============================================================================ non-combat move

  /**
   * Before Hard's non-combat move: the air units of the plan's carrier landings fly to their
   * carrier (each move checked by the engine); they and the carriers there stay (held).
   */
  static Set<Unit> atterra(
      final Stato s, final GameState data, final GamePlayer player, final IMoveDelegate del) {
    final Set<Unit> held = new LinkedHashSet<>();
    s.turno(data, player);
    if (s.assegnazioni.isEmpty()) {
      return held;
    }
    int landed = 0;
    int already = 0;
    int failed = 0;
    int gone = 0;
    final JsonArray refused = new JsonArray();
    final Map<UUID, Territory> where = new HashMap<>();
    final Map<UUID, Unit> units = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        if (s.assegnazioni.containsKey(u.getId())) {
          where.put(u.getId(), t);
          units.put(u.getId(), u);
        }
      }
    }
    final Predicate<Territory> canFly = ProMatches.territoryCanMoveAirUnits(data, player, false);
    for (final Map.Entry<UUID, String> e : s.assegnazioni.entrySet()) {
      final Unit u = units.get(e.getKey());
      final Territory zone = data.getMap().getTerritoryOrNull(e.getValue());
      if (u == null || zone == null) {
        gone++;
        continue;
      }
      final Territory now = where.get(e.getKey());
      held.addAll(zone.getMatches(Matches.unitIsCarrier().and(Matches.unitIsOwnedBy(player))));
      if (now.equals(zone)) {
        held.add(u);
        already++;
        continue;
      }
      if (!now.isWater()
          && ProMatches.territoryCanLandAirUnits(player, false, List.of(), List.of()).test(now)) {
        already++; // it did not leave (the move was refused) or can stay where it is
        continue;
      }
      final Optional<Route> r = data.getMap().getRouteForUnit(now, zone, canFly, u, player);
      final Optional<String> error =
          r.isEmpty() || r.get().getMovementCost(u).compareTo(u.getMovementLeft()) > 0
              ? Optional.of("nessuna rotta nel movimento rimasto")
              : del.performMove(new MoveDescription(List.of(u), r.get()));
      if (error.isEmpty()) {
        held.add(u);
        landed++;
      } else {
        failed++;
        refused.add(
            u.getType().getName()
                + " "
                + now.getName()
                + ">"
                + zone.getName()
                + ": "
                + error.get());
      }
    }
    final JsonObject row = new JsonObject();
    row.addProperty("fase", "atterraggio");
    row.addProperty("assegnati", s.assegnazioni.size());
    row.addProperty("atterrati", landed);
    row.addProperty("gia_a_posto", already);
    row.addProperty("distrutti_prima", gone);
    row.addProperty("rifiutati", failed);
    row.add("rifiuti", refused);
    LeveP13.registra(data, player, TIPO, "P14_ATTERRAGGIO", "atterraggio sulle portaerei", row);
    s.assegnazioni.clear();
    return held;
  }
}
