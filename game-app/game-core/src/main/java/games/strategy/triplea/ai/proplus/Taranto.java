package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.ScrambleLogic;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;

/**
 * Step P13, lever {@link StrategaInterruttori#TARANTO} (Allies, the data's United Kingdom, only in
 * the data's round, in the combat move before Hard's): the raid on the Italian fleet of the target
 * zone with the fleet of the data's zone, the tactical bomber on its carrier, the fighter of the
 * air base, the bomber of the capital and up to two fighters of the capital (they land on the
 * carrier in the target zone; the capital keeps them unless the best German attack still wins under
 * the threshold without them). The battle is estimated with the scramblers that can join the
 * defense (the engine's {@link ScrambleLogic}); at {@code soglia_attacco_pct} or more the units
 * move (each move checked by the engine), otherwise nothing changes. In the non-combat move the
 * tactical bomber, the bomber and the air base's fighter land at the air base. Off: nothing.
 */
public final class Taranto {
  private Taranto() {}

  /** Raid executed in this game: the planes to land at the air base (ids), by game. */
  private static final Map<GameState, Set<String>> ATTERRAGGI =
      Collections.synchronizedMap(new WeakHashMap<>());

  /** The lever is on for {@code player} (the data's nation) in the data's round. */
  public static boolean attivo(@Nullable final GameState data, @Nullable final GamePlayer player) {
    final LeveP13.Config c = LeveP13.Config.get();
    return data != null
        && data.getSequence().getRound() == c.tarantoRound()
        && LeveP13.attiva(data, player, StrategaInterruttori.TARANTO, c.tarantoNazione());
  }

  /** Fighters that leave the capital (pure rule): 2 if even without both, else 1, else 0. */
  static int cacciaDaMandare(
      final int disponibili, final double senzaDue, final double senzaUno, final double soglia) {
    if (disponibili >= 2 && senzaDue < soglia) {
      return 2;
    }
    if (disponibili >= 1 && senzaUno < soglia) {
      return 1;
    }
    return 0;
  }

  /**
   * The combat move: estimate and, at the threshold, the raid. Returns the units moved (Hard plans
   * again without them). Never throws.
   */
  public static Set<Unit> combattimento(
      final ProData proData, final ProOddsCalculator calc, final IMoveDelegate del) {
    final Set<Unit> moved = new LinkedHashSet<>();
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final LeveP13.Config c = LeveP13.Config.get();
    final JsonObject row = new JsonObject();
    try {
      final Territory target = data.getMap().getTerritoryOrNull(c.zonaBersaglio());
      final Territory fleetZone = data.getMap().getTerritoryOrNull(c.zonaFlotta());
      final Territory base = data.getMap().getTerritoryOrNull(c.baseAerea());
      final Territory capital = data.getMap().getTerritoryOrNull(c.capitaleUk());
      if (target == null || fleetZone == null || base == null || capital == null) {
        return moved;
      }
      final List<Unit> defenders =
          target.getMatches(Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure()));
      row.addProperty("difensori", defenders.size());
      if (defenders.isEmpty()) {
        LeveP13.registra(
            data, player, LeveP13.TIPO_TARANTO, "P13_TARANTO_NO", "zona senza nemici", row);
        return moved;
      }
      // the fleet that reaches the target zone this turn
      final List<Unit> ships =
          fleetZone.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(u -> c.tipiNavi().contains(u.getType().getName()))
                  .and(Matches.unitHasMovementLeft()));
      final Optional<Route> seaRoute =
          ships.isEmpty()
              ? Optional.empty()
              : data.getMap()
                  .getRouteForUnits(
                      fleetZone,
                      target,
                      ProMatches.territoryCanMoveSeaUnits(player, true)
                          .and(
                              t ->
                                  t.equals(target)
                                      || ProMatches.territoryCanMoveSeaUnitsThrough(player, true)
                                          .test(t)),
                      ships,
                      player);
      final List<Unit> fleet = new ArrayList<>();
      if (seaRoute.isPresent()) {
        for (final Unit s : ships) {
          if (s.getMovementLeft().intValue() >= seaRoute.get().numberOfSteps()) {
            fleet.add(s);
          }
        }
      }
      final int carrierCapacity =
          fleet.stream()
              .mapToInt(u -> Math.max(0, u.getUnitAttachment().getCarrierCapacity()))
              .sum();
      // the planes: tactical bomber (lands at the air base), the base's fighter (back home), the
      // capital's bomber (lands at the air base), the capital's fighters (land on the carrier)
      final Map<Unit, Route> air = new LinkedHashMap<>();
      final Set<String> toBase = new LinkedHashSet<>();
      for (final Territory from : List.of(fleetZone, base, capital)) {
        final String type =
            from.equals(fleetZone) ? c.tattico() : from.equals(base) ? c.caccia() : c.bombardiere();
        for (final Unit u : of(from, player, type)) {
          final Optional<Route> r = rotta(data, player, from, target, u, base, false);
          if (r.isPresent()) {
            air.put(u, r.get());
            toBase.add(u.getId().toString());
          }
        }
      }
      // the capital's fighters that reach the zone (landing: the carrier there)
      final List<Unit> londonFighters = new ArrayList<>();
      final Map<Unit, Route> londonRoutes = new LinkedHashMap<>();
      final List<String> reach = new ArrayList<>();
      for (final Unit u : of(capital, player, c.caccia())) {
        final Optional<Route> any =
            data.getMap()
                .getRouteForUnit(
                    capital,
                    target,
                    ProMatches.territoryCanMoveAirUnitsAndNoAa(data, player, true),
                    u,
                    player);
        reach.add(
            u.getMovementLeft().intValue()
                + "/"
                + any.map(x -> String.valueOf(x.numberOfSteps())).orElse("-"));
        final Optional<Route> r = rotta(data, player, capital, target, u, base, true);
        if (r.isPresent()) {
          londonFighters.add(u);
          londonRoutes.put(u, r.get());
        }
      }
      final int carrierFree = carrierCapacity;
      final int availableFighters =
          Math.min(c.maxCacciaCapitale(), Math.min(londonFighters.size(), carrierFree));
      row.addProperty("navi", fleet.size());
      row.addProperty("aerei_mediterraneo_e_bombardiere", air.size());
      row.addProperty("caccia_capitale_in_portata", londonFighters.size());
      row.addProperty("caccia_capitale_movimento_rotta", String.join(" ", reach));
      row.addProperty(
          "bombardiere_rotta",
          air.entrySet().stream()
              .filter(e -> e.getValue().getStart().equals(capital))
              .map(e -> e.getKey().getMovementLeft() + "/" + e.getValue().numberOfSteps())
              .findFirst()
              .orElse("-"));
      row.addProperty("capacita_portaerei", carrierCapacity);
      // the capital's safety (calculation of capitale_minima)
      final LeveItalia.Minaccia m = LeveItalia.minaccia(proData, calc, capital);
      final List<Unit> home =
          capital.getMatches(
              Matches.isUnitAllied(player)
                  .and(Matches.unitIsNotInfrastructure())
                  .and(u -> !air.containsKey(u)));
      final List<Unit> withoutTwo = new ArrayList<>(home);
      final List<Unit> withoutOne = new ArrayList<>(home);
      for (int i = 0; i < Math.min(2, londonFighters.size()); i++) {
        withoutTwo.remove(londonFighters.get(i));
        if (i == 0) {
          withoutOne.remove(londonFighters.get(i));
        }
      }
      final double w0 = LeveItalia.vittoria(proData, calc, capital, m, home);
      final double w1 = LeveItalia.vittoria(proData, calc, capital, m, withoutOne);
      final double w2 = LeveItalia.vittoria(proData, calc, capital, m, withoutTwo);
      final int send = cacciaDaMandare(availableFighters, w2, w1, c.sogliaCapitale());
      row.addProperty("vittoria_tedesca_londra_con_tutti", LeveP13.r1(w0));
      row.addProperty("vittoria_tedesca_londra_senza_1", LeveP13.r1(w1));
      row.addProperty("vittoria_tedesca_londra_senza_2", LeveP13.r1(w2));
      row.addProperty("caccia_londra_mandati", send);
      // the battle with the scramblers
      final List<Unit> scramblers = scramblers(data, player, target);
      final List<Unit> def = new ArrayList<>(defenders);
      def.addAll(scramblers);
      final List<Unit> attackers = new ArrayList<>(fleet);
      attackers.addAll(air.keySet());
      for (int i = 0; i < send; i++) {
        attackers.add(londonFighters.get(i));
      }
      row.addProperty("attaccanti", attackers.size());
      row.addProperty("caccia_in_difesa_possibili", scramblers.size());
      final double win =
          attackers.isEmpty() || fleet.isEmpty()
              ? 0
              : calc.calculateBattleResults(proData, target, attackers, def, List.of())
                  .getWinPercentage();
      final List<Unit> mediterranean =
          attackers.stream()
              .filter(u -> !londonFighters.contains(u))
              .filter(u -> !air.containsKey(u) || !air.get(u).getStart().equals(capital))
              .toList();
      final double winMed =
          mediterranean.isEmpty() || fleet.isEmpty()
              ? 0
              : calc.calculateBattleResults(proData, target, mediterranean, def, List.of())
                  .getWinPercentage();
      row.addProperty("vittoria_stimata", LeveP13.r1(win));
      row.addProperty("vittoria_solo_mediterraneo", LeveP13.r1(winMed));
      if (fleet.isEmpty() || win < c.sogliaAttacco()) {
        LeveP13.registra(
            data,
            player,
            LeveP13.TIPO_TARANTO,
            "P13_TARANTO_NO",
            fleet.isEmpty() ? "flotta non in portata" : "vittoria stimata sotto la soglia",
            row);
        return moved;
      }
      // the moves (each one checked by the engine): fleet first, then the planes
      if (del.performMove(new MoveDescription(fleet, seaRoute.get())).isPresent()) {
        row.addProperty("rifiutata", "flotta");
        LeveP13.registra(
            data,
            player,
            LeveP13.TIPO_TARANTO,
            "P13_TARANTO_NO",
            "mossa della flotta rifiutata dal motore",
            row);
        return moved;
      }
      moved.addAll(fleet);
      final List<String> refused = new ArrayList<>();
      final Map<Unit, Route> all = new LinkedHashMap<>(air);
      for (int i = 0; i < send; i++) {
        all.put(londonFighters.get(i), londonRoutes.get(londonFighters.get(i)));
      }
      int londonSent = 0;
      for (final var e : all.entrySet()) {
        final Optional<String> err =
            del.performMove(new MoveDescription(List.of(e.getKey()), e.getValue()));
        if (err.isPresent()) {
          refused.add(e.getKey().getType().getName() + ": " + err.get());
          toBase.remove(e.getKey().getId().toString());
        } else {
          moved.add(e.getKey());
          if (londonFighters.contains(e.getKey())) {
            londonSent++;
          }
        }
      }
      row.addProperty("caccia_londra_partiti", londonSent);
      row.addProperty("unita_mosse", moved.size());
      if (!refused.isEmpty()) {
        row.addProperty("rifiutate", String.join("; ", refused));
      }
      ATTERRAGGI.put(data, toBase);
      LeveP13.registra(
          data,
          player,
          LeveP13.TIPO_TARANTO,
          "P13_TARANTO",
          "attacco alla flotta italiana del primo round",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P13, Taranto: " + e);
    }
    return moved;
  }

  /**
   * The non-combat move (before Hard's): the raid's tactical bomber, bomber and the base's fighter
   * land at the air base. Returns the planes landed (Hard leaves them). Never throws.
   */
  public static Set<Unit> nonCombattimento(
      final GameState data, final GamePlayer player, final IMoveDelegate del) {
    final Set<Unit> held = new LinkedHashSet<>();
    try {
      final Set<String> ids = ATTERRAGGI.remove(data);
      if (ids == null || ids.isEmpty()) {
        return held;
      }
      final LeveP13.Config c = LeveP13.Config.get();
      final Territory target = data.getMap().getTerritoryOrNull(c.zonaBersaglio());
      final Territory base = data.getMap().getTerritoryOrNull(c.baseAerea());
      if (target == null || base == null || !base.isOwnedBy(player)) {
        return held;
      }
      final List<Unit> planes =
          target.getMatches(
              u ->
                  u.isOwnedBy(player)
                      && ids.contains(u.getId().toString())
                      && Matches.unitIsAir().test(u));
      int landed = 0;
      for (final Unit u : planes) {
        final Optional<Route> r =
            data.getMap()
                .getRouteForUnit(
                    target,
                    base,
                    ProMatches.territoryCanMoveAirUnits(data, player, false),
                    u,
                    player);
        if (r.isPresent()
            && r.get().numberOfSteps() <= u.getMovementLeft().intValue()
            && del.performMove(new MoveDescription(List.of(u), r.get())).isEmpty()) {
          held.add(u);
          landed++;
        }
      }
      final JsonObject row = new JsonObject();
      row.addProperty("fase", "atterraggio");
      row.addProperty("aerei_da_atterrare", planes.size());
      row.addProperty("atterrati_alla_base", landed);
      row.addProperty(
          "navi_nemiche_rimaste",
          target.getMatches(Matches.enemyUnit(player).and(Matches.unitIsSea())).size());
      row.addProperty(
          "unita_proprie_nella_zona", target.getMatches(Matches.unitIsOwnedBy(player)).size());
      LeveP13.registra(
          data,
          player,
          LeveP13.TIPO_TARANTO,
          "P13_TARANTO",
          "dopo la battaglia: atterraggio alla base",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P13, Taranto (atterraggio): " + e);
    }
    return held;
  }

  /** Own units of {@code type} in {@code t} with movement left, by id. */
  private static List<Unit> of(final Territory t, final GamePlayer player, final String type) {
    final List<Unit> out =
        new ArrayList<>(
            t.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(u -> u.getType().getName().equals(type))
                    .and(Matches.unitHasMovementLeft())));
    out.sort(Comparator.comparing(u -> u.getId().toString()));
    return out;
  }

  /**
   * The air route of {@code u} from {@code from} to the target with the movement left: either it
   * can land at {@code base} afterwards ({@code carrier} false) or it reaches the target to land on
   * the carrier there ({@code carrier} true).
   */
  private static Optional<Route> rotta(
      final GameState data,
      final GamePlayer player,
      final Territory from,
      final Territory target,
      final Unit u,
      final Territory base,
      final boolean carrier) {
    final Optional<Route> r =
        data.getMap()
            .getRouteForUnit(
                from,
                target,
                ProMatches.territoryCanMoveAirUnitsAndNoAa(data, player, true),
                u,
                player);
    if (r.isEmpty()) {
      return Optional.empty();
    }
    final int left = u.getMovementLeft().intValue() - r.get().numberOfSteps();
    if (left < 0) {
      return Optional.empty();
    }
    if (carrier) {
      return u.getUnitAttachment().getCarrierCost() > 0 ? r : Optional.empty();
    }
    final int back = data.getMap().getDistance(target, base);
    return back >= 0 && back <= left ? r : Optional.empty();
  }

  /** The enemy planes that can scramble to defend {@code target} (the engine's rule). */
  static List<Unit> scramblers(
      final GameState data, final GamePlayer player, final Territory target) {
    if (!Properties.getScrambleRulesInEffect(data.getProperties())) {
      return List.of();
    }
    final List<Unit> out =
        new ArrayList<>(new ScrambleLogic(data, player, target).getUnitsThatCanScramble());
    final Set<Unit> seen = new HashSet<>();
    out.removeIf(u -> !seen.add(u));
    out.sort(Comparator.comparing(u -> u.getId().toString()));
    return out;
  }
}
