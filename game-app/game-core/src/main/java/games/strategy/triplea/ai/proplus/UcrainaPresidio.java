package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.PlaceableUnits;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Step P15, lever B {@link StrategaInterruttori#UCRAINA_PRESIDIO} (Russia). In the non-combat move,
 * before Hard's: if Ukraine is Russian and the enemies' best attack next turn wins at least {@code
 * soglia_minaccia_pct}, the nearest Russian land units that reach it are sent one at a time until
 * the estimate falls under the threshold (B1; a unit of the capital only if Moscow's enemy win
 * without it stays within the reference + {@code margine_mosca_punti}). If even all of them do not
 * bring it under the threshold, none is sent (B2: no sacrificed stack, Hard decides). At the
 * placement, before Hard's: if Ukraine has a factory and is still threatened, the land units bought
 * that bring the estimate under the threshold are placed there (within the factory's limit; none if
 * they are not enough), the rest as Hard (B3).
 */
public final class UcrainaPresidio {
  private UcrainaPresidio() {}

  static boolean attivo(final GameState data, final GamePlayer player) {
    return LeveP15.attiva(data, player, StrategaInterruttori.UCRAINA_PRESIDIO);
  }

  /** The outcome of one choice of reinforcements (pure part, for the tests). */
  record Scelta(List<Unit> unita, double prima, double dopo, boolean tiene) {}

  /**
   * The units to add, in order, until the threat falls under the threshold: none when even all of
   * them are not enough (B2).
   */
  static Scelta scegli(
      final List<Unit> candidates,
      final List<Unit> defenders,
      final double threshold,
      final java.util.function.ToDoubleFunction<List<Unit>> threat) {
    final double before = threat.applyAsDouble(defenders);
    if (!LeveP15.minacciata(before, threshold)) {
      return new Scelta(List.of(), before, before, true);
    }
    final List<Unit> def = new ArrayList<>(defenders);
    final List<Unit> added = new ArrayList<>();
    double now = before;
    for (final Unit u : candidates) {
      def.add(u);
      added.add(u);
      now = threat.applyAsDouble(def);
      if (!LeveP15.minacciata(now, threshold)) {
        return new Scelta(added, before, now, true);
      }
    }
    return new Scelta(List.of(), before, now, false);
  }

  /**
   * The non-combat move before Hard's (B1, B2). Returns the units sent (Hard leaves them). Never
   * throws.
   */
  public static Set<Unit> nonCombattimento(
      final ProData proData, final ProOddsCalculator calc, final IMoveDelegate del) {
    final Set<Unit> moved = new LinkedHashSet<>();
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final LeveP15.Config c = LeveP15.Config.get();
    try {
      final Territory ukraine = data.getMap().getTerritoryOrNull(c.ucraina());
      if (ukraine == null || !ukraine.isOwnedBy(player)) {
        return moved;
      }
      final LeveItalia.Minaccia m = LeveItalia.minaccia(proData, calc, ukraine);
      final List<Unit> defenders = LeveP15.difensori(ukraine, player);
      final JsonObject row = new JsonObject();
      row.addProperty("difensori_prima", defenders.size());
      if (m.vuota()) {
        row.addProperty("minaccia_prima_pct", 0);
        LeveP15.registra(
            data, player, LeveP15.TIPO_PRESIDIO, "P15_B_SICURA", "Ucraina non minacciata", row);
        return moved;
      }
      // the candidates: nearest first; the capital's only while Moscow stays protected
      final Map<Unit, Route> routes = new LinkedHashMap<>();
      final List<Unit> candidates = candidati(data, player, ukraine, routes);
      final Optional<Territory> capital = LeveP15.capitale(data, player);
      int capitalExcluded = 0;
      if (capital.isPresent() && candidates.stream().anyMatch(capital.get().getUnits()::contains)) {
        final LeveItalia.Minaccia mc = LeveItalia.minaccia(proData, calc, capital.get());
        final List<Unit> home = LeveP15.difensori(capital.get(), player);
        final double reference = LeveP15.minaccia(proData, calc, capital.get(), mc, home);
        final List<Unit> fromCapital =
            candidates.stream().filter(capital.get().getUnits()::contains).toList();
        final List<Unit> left = new ArrayList<>(home);
        for (final Unit u : fromCapital) {
          left.remove(u);
          final double without = LeveP15.minaccia(proData, calc, capital.get(), mc, left);
          if (!LeveP15.moscaProtetta(without, reference, c.margineMosca())) {
            left.add(u);
            candidates.remove(u);
            capitalExcluded++;
          }
        }
        row.addProperty("mosca_riferimento_pct", LeveP15.r1(reference));
      }
      row.addProperty("candidati", candidates.size());
      row.addProperty("candidati_capitale_esclusi", capitalExcluded);
      final Scelta s =
          scegli(
              candidates,
              defenders,
              c.sogliaPresidio(),
              def -> LeveP15.minaccia(proData, calc, ukraine, m, def));
      row.addProperty("minaccia_prima_pct", LeveP15.r1(s.prima()));
      row.addProperty("minaccia_dopo_pct", LeveP15.r1(s.dopo()));
      if (!LeveP15.minacciata(s.prima(), c.sogliaPresidio())) {
        LeveP15.registra(
            data,
            player,
            LeveP15.TIPO_PRESIDIO,
            "P15_B_SICURA",
            "Ucraina minacciata sotto la soglia",
            row);
        return moved;
      }
      if (!s.tiene()) {
        row.addProperty("nota", "nemmeno con tutti i candidati: nessuno in piu' (B2)");
        LeveP15.registra(
            data,
            player,
            LeveP15.TIPO_PRESIDIO,
            "P15_B_RINUNCIA",
            "non tenibile con le unita' disponibili",
            row);
        return moved;
      }
      final List<String> refused = new ArrayList<>();
      for (final Unit u : s.unita()) {
        final Optional<String> err =
            del.performMove(new MoveDescription(List.of(u), routes.get(u)));
        if (err.isPresent()) {
          refused.add(u.getType().getName() + ": " + err.get());
        } else {
          moved.add(u);
        }
      }
      row.addProperty("spostate", moved.size());
      row.addProperty(
          "dalla_capitale",
          capital
              .map(
                  t -> (int) moved.stream().filter(u -> routes.get(u).getStart().equals(t)).count())
              .orElse(0));
      if (!refused.isEmpty()) {
        row.addProperty("rifiutate", String.join("; ", refused));
      }
      LeveP15.registra(
          data,
          player,
          LeveP15.TIPO_PRESIDIO,
          "P15_B_SPOSTATE",
          "rinforzi in Ucraina fino sotto la soglia",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P15, presidio: " + e);
    }
    return moved;
  }

  /** Own land units that reach {@code ukraine} in the non-combat move, nearest first. */
  static List<Unit> candidati(
      final GameState data,
      final GamePlayer player,
      final Territory ukraine,
      final Map<Unit, Route> routes) {
    final Predicate<Territory> through =
        t ->
            !t.isWater()
                && Matches.isTerritoryAllied(player).test(t)
                && !t.anyUnitsMatch(Matches.enemyUnit(player));
    final Predicate<Unit> land =
        Matches.unitIsOwnedBy(player)
            .and(Matches.unitIsLand())
            .and(Matches.unitIsNotInfrastructure())
            .and(Matches.unitHasMovementLeft())
            .and(u -> !u.getWasInCombat())
            .and(u -> u.getTransportedBy() == null)
            .and(u -> u.getUnitAttachment().getDefense(player) > 0);
    final Map<Unit, Integer> steps = new HashMap<>();
    final List<Unit> out = new ArrayList<>();
    for (final Territory from : data.getMap().getTerritories()) {
      if (from.equals(ukraine) || !through.test(from)) {
        continue;
      }
      final int distance = data.getMap().getLandDistance(from, ukraine);
      if (distance < 0 || distance > 3) {
        continue;
      }
      for (final Unit u : from.getMatches(land)) {
        final Optional<Route> r = data.getMap().getRouteForUnit(from, ukraine, through, u, player);
        if (r.isEmpty() || r.get().numberOfSteps() > u.getMovementLeft().intValue()) {
          continue;
        }
        routes.put(u, r.get());
        steps.put(u, r.get().numberOfSteps());
        out.add(u);
      }
    }
    out.sort(
        Comparator.comparingInt((Unit u) -> steps.get(u))
            .thenComparingInt(u -> -u.getUnitAttachment().getDefense(player))
            .thenComparing(u -> u.getId().toString()));
    return out;
  }

  /**
   * B3, before Hard's placement: the bought land units that bring Ukraine under the threshold are
   * placed there (none if they are not enough). Never throws.
   */
  public static void piazzamento(
      final ProData proData, final ProOddsCalculator calc, final IAbstractPlaceDelegate del) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final LeveP15.Config c = LeveP15.Config.get();
    try {
      final Territory ukraine = data.getMap().getTerritoryOrNull(c.ucraina());
      if (ukraine == null
          || !ukraine.isOwnedBy(player)
          || !ukraine.anyUnitsMatch(Matches.unitCanProduceUnits())) {
        return;
      }
      final List<Unit> hand =
          new ArrayList<>(
              player
                  .getUnitCollection()
                  .getMatches(
                      Matches.unitIsLand()
                          .and(Matches.unitIsNotInfrastructure())
                          .and(Matches.unitIsNotConstruction())));
      if (hand.isEmpty()) {
        return;
      }
      final PlaceableUnits placeable = del.getPlaceableUnits(hand, ukraine);
      if (placeable.isError()) {
        return;
      }
      final int max = placeable.getMaxUnits() < 0 ? Integer.MAX_VALUE : placeable.getMaxUnits();
      final List<Unit> can = new ArrayList<>(placeable.getUnits());
      can.retainAll(hand);
      can.sort(
          Comparator.comparingDouble(
                  (Unit u) ->
                      -u.getUnitAttachment().getDefense(player)
                          / (double) Math.max(1, LeveP15.tuv(proData, List.of(u))))
              .thenComparing(u -> u.getId().toString()));
      final List<Unit> limited = can.subList(0, Math.min(max, can.size()));
      final LeveItalia.Minaccia m = LeveItalia.minaccia(proData, calc, ukraine);
      if (m.vuota()) {
        return;
      }
      final Scelta s =
          scegli(
              new ArrayList<>(limited),
              LeveP15.difensori(ukraine, player),
              c.sogliaPresidio(),
              def -> LeveP15.minaccia(proData, calc, ukraine, m, def));
      if (!LeveP15.minacciata(s.prima(), c.sogliaPresidio())) {
        return;
      }
      final JsonObject row = new JsonObject();
      row.addProperty("minaccia_prima_pct", LeveP15.r1(s.prima()));
      row.addProperty("minaccia_dopo_pct", LeveP15.r1(s.dopo()));
      row.addProperty("posti_fabbrica", max == Integer.MAX_VALUE ? -1 : max);
      row.addProperty("unita_in_mano", hand.size());
      if (!s.tiene()) {
        LeveP15.registra(
            data,
            player,
            LeveP15.TIPO_PRESIDIO,
            "P15_B_PIAZZA_RINUNCIA",
            "piazzamento in Ucraina non basta: decide Hard",
            row);
        return;
      }
      int placed = 0;
      for (final Unit u : s.unita()) {
        if (del.placeUnits(List.of(u), ukraine, IAbstractPlaceDelegate.BidMode.NOT_BID).isEmpty()) {
          placed++;
        }
      }
      row.addProperty("piazzate", placed);
      LeveP15.registra(
          data,
          player,
          LeveP15.TIPO_PRESIDIO,
          "P15_B_PIAZZATE",
          "unita' piazzate in Ucraina fino sotto la soglia",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P15, piazzamento: " + e);
    }
  }
}
