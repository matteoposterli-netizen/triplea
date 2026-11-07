package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.data.ProPlaceTerritory;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProPurchaseOptionMap;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.data.ProResourceTracker;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProPurchaseValidationUtils;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Buys what a strategy plan's {@link ProPurchaseOrders} ask for, with the PUs left after urgent
 * defense: mandatory purchases first, then the category whose share is furthest from its target,
 * one unit at a time, while a unit of that category fits its remaining share (at least half its
 * cost) and can be placed. Units of a category are bought in the rotation of its unit types.
 *
 * <p>Placement: land and air units in land place territories that can be held, sea units in the
 * place sea zones the enemy can't attack first; within that, the preferred territories in order,
 * then the nearest to the gathering territories, then the highest strategic value. Only used when
 * the tuning gives orders ({@link ProTuning#purchaseOrders}); Hard never gets here.
 */
class ProPlanPurchase {
  private final ProData proData;
  private final GamePlayer player;
  private final GameData data;
  private final GameState startOfTurnData;
  private final ProResourceTracker resourceTracker;
  private final ProTerritoryManager territoryManager;
  private final Map<Territory, ProPurchaseTerritory> purchaseTerritories;
  private final BiConsumer<ProPlaceTerritory, Collection<Unit>> addUnitsToPlace;
  private final Map<String, List<ProPurchaseOption>> optionsByType = new HashMap<>();
  private final Map<String, Integer> rotation = new HashMap<>();
  private final Map<String, Integer> spent = new LinkedHashMap<>();
  private final Map<String, Map<String, Integer>> bought = new LinkedHashMap<>();
  private final Map<String, Map<String, Integer>> placements = new TreeMap<>();
  private final Map<String, Double> exposed = new TreeMap<>();
  private final List<Territory> stagingLand = new ArrayList<>();
  private final List<Territory> stagingSea = new ArrayList<>();
  private final ProPurchaseOrders orders;

  ProPlanPurchase(
      final ProPurchaseOrders orders,
      final ProData proData,
      final GameState startOfTurnData,
      final ProResourceTracker resourceTracker,
      final ProTerritoryManager territoryManager,
      final Map<Territory, ProPurchaseTerritory> purchaseTerritories,
      final ProPurchaseOptionMap purchaseOptions,
      final BiConsumer<ProPlaceTerritory, Collection<Unit>> addUnitsToPlace) {
    this.orders = orders;
    this.proData = proData;
    this.player = proData.getPlayer();
    this.data = proData.getData();
    this.startOfTurnData = startOfTurnData;
    this.resourceTracker = resourceTracker;
    this.territoryManager = territoryManager;
    this.purchaseTerritories = purchaseTerritories;
    this.addUnitsToPlace = addUnitsToPlace;
    for (final ProPurchaseOption option : purchaseOptions.getAllOptions()) {
      if (!option.isConstruction()) {
        optionsByType
            .computeIfAbsent(option.getUnitType().getName(), k -> new ArrayList<>())
            .add(option);
      }
    }
  }

  ProPurchaseOrders.Result buy() {
    for (final String name : orders.stagingTerritories()) {
      Optional.ofNullable(data.getMap().getTerritoryOrNull(name))
          .ifPresent(t -> (t.isWater() ? stagingSea : stagingLand).add(t));
    }
    final int budget = pus();
    for (final ProPurchaseOrders.Category c : orders.mix()) {
      spent.put(c.name(), 0);
    }

    // mandatory purchases first; what they cost outside the mix's categories is not in its base
    final List<String> missing = new ArrayList<>();
    for (final ProPurchaseOrders.Mandatory m : orders.mandatory()) {
      int count =
          bought.getOrDefault(m.category(), Map.of()).values().stream()
              .mapToInt(Integer::intValue)
              .sum();
      while (count < m.minimum()) {
        final Optional<Integer> units = buyOne(m.category(), m.unitTypes(), Integer.MAX_VALUE);
        if (units.isEmpty()) {
          missing.add(
              m.category()
                  + ": "
                  + count
                  + " di "
                  + m.minimum()
                  + " (PU o capacita' di piazzamento insufficienti)");
          break;
        }
        count += units.get();
      }
    }
    final int outsideMix =
        spent.entrySet().stream()
            .filter(e -> orders.mix().stream().noneMatch(c -> c.name().equals(e.getKey())))
            .mapToInt(Map.Entry::getValue)
            .sum();
    final Map<String, Double> targets = new LinkedHashMap<>();
    for (final ProPurchaseOrders.Category c : orders.mix()) {
      targets.put(c.name(), (budget - outsideMix) * c.percent() / 100);
    }
    ProLogger.info(
        "[Pro+] Plan purchase "
            + orders.label()
            + ", budget "
            + budget
            + ", mandatory "
            + bought
            + ", mix "
            + targets);

    while (true) {
      boolean boughtSomething = false;
      final List<ProPurchaseOrders.Category> byDeficit = new ArrayList<>(orders.mix());
      byDeficit.sort(
          Comparator.comparingDouble(
              (ProPurchaseOrders.Category c) -> spent.get(c.name()) - targets.get(c.name())));
      for (final ProPurchaseOrders.Category c : byDeficit) {
        final double deficit = targets.get(c.name()) - spent.get(c.name());
        if (deficit <= 0) {
          continue;
        }
        // a unit fits if at least half of its cost is still in the share
        if (buyOne(c.name(), c.unitTypes(), 2 * deficit).isPresent()) {
          boughtSomething = true;
          break;
        }
      }
      if (!boughtSomething) {
        break;
      }
    }
    final ProPurchaseOrders.Result result =
        new ProPurchaseOrders.Result(
            budget, targets, spent, bought, placements, exposed, missing, pus());
    ProLogger.info("[Pro+] Plan purchase result: " + result);
    return result;
  }

  /**
   * Buys one option of {@code unitTypes} (next in the category's rotation that costs at most {@code
   * maxCost}, is affordable and can be placed). Returns the units bought.
   */
  private Optional<Integer> buyOne(
      final String category, final List<String> unitTypes, final double maxCost) {
    final int start = rotation.getOrDefault(category, 0);
    for (int i = 0; i < unitTypes.size(); i++) {
      final int index = (start + i) % unitTypes.size();
      for (final ProPurchaseOption option :
          optionsByType.getOrDefault(unitTypes.get(index), List.of())) {
        if (option.getCost() > maxCost || !resourceTracker.hasEnough(option)) {
          continue;
        }
        final Optional<ProPlaceTerritory> where = placeFor(option);
        if (where.isEmpty()) {
          continue;
        }
        final List<Unit> units = option.createTempUnits();
        final double exposure = exposure(where.get());
        resourceTracker.purchase(option);
        addUnitsToPlace.accept(where.get(), units);
        if (exposure > 0 && where.get().getTerritory().isWater()) {
          exposed.merge(where.get().getTerritory().getName(), exposure, Math::max);
        }
        rotation.put(category, index + 1);
        spent.merge(category, option.getCost(), Integer::sum);
        bought
            .computeIfAbsent(category, k -> new TreeMap<>())
            .merge(option.getUnitType().getName(), option.getQuantity(), Integer::sum);
        placements
            .computeIfAbsent(where.get().getTerritory().getName(), k -> new TreeMap<>())
            .merge(option.getUnitType().getName(), option.getQuantity(), Integer::sum);
        return Optional.of(option.getQuantity());
      }
    }
    return Optional.empty();
  }

  private Optional<ProPlaceTerritory> placeFor(final ProPurchaseOption option) {
    final boolean sea = option.getUnitType().getUnitAttachment().isSea();
    final List<Candidate> candidates = new ArrayList<>();
    for (final ProPurchaseTerritory factory : purchaseTerritories.values()) {
      final int remaining = factory.getRemainingUnitProduction();
      if (remaining < option.getQuantity()) {
        continue;
      }
      for (final ProPlaceTerritory place : factory.getCanPlaceTerritories()) {
        final Territory t = place.getTerritory();
        if (t.isWater() != sea || !canPlace(option, factory, place, remaining)) {
          continue;
        }
        candidates.add(new Candidate(place, factory.getTerritory()));
      }
    }
    return candidates.stream().min(candidateOrder()).map(Candidate::place);
  }

  private boolean canPlace(
      final ProPurchaseOption option,
      final ProPurchaseTerritory factory,
      final ProPlaceTerritory place,
      final int remaining) {
    final List<ProPurchaseOption> single = new ArrayList<>(List.of(option));
    ProPurchaseValidationUtils.removeInvalidPurchaseOptions(
        proData,
        player,
        startOfTurnData,
        single,
        resourceTracker,
        remaining,
        place.getPlaceUnits(),
        purchaseTerritories,
        0,
        place.getTerritory());
    if (single.isEmpty()) {
      return false;
    }
    final List<Unit> units = new ArrayList<>(place.getPlaceUnits());
    units.addAll(option.createTempUnits());
    return ProPurchaseValidationUtils.canUnitsBePlaced(
        proData, units, player, place.getTerritory(), factory.getTerritory(), false);
  }

  private Comparator<Candidate> candidateOrder() {
    final List<String> preferred = orders.preferredPlacement();
    return Comparator.comparing((Candidate c) -> exposure(c.place()) > 0)
        .thenComparingDouble(c -> exposure(c.place()))
        .thenComparingInt(c -> preferenceIndex(preferred, c))
        .thenComparingInt(c -> stagingDistance(c.place().getTerritory()))
        .thenComparing(c -> -c.place().getStrategicValue())
        .thenComparing(c -> c.place().getTerritory().getName());
  }

  private static int preferenceIndex(final List<String> preferred, final Candidate c) {
    final int place = preferred.indexOf(c.place().getTerritory().getName());
    final int factory = preferred.indexOf(c.factory().getName());
    final int best = place < 0 ? factory : (factory < 0 ? place : Math.min(place, factory));
    return best < 0 ? Integer.MAX_VALUE : best;
  }

  /**
   * How exposed a place territory is (0 = not exposed). Land: 1 if it can't be held (the defense
   * purchase found it lost). Sea: TUV the enemy can attack it with beyond what will defend it
   * (allied ships there plus those placed), so among exposed sea zones the least exposed is used.
   */
  private double exposure(final ProPlaceTerritory place) {
    final Territory t = place.getTerritory();
    if (!t.isWater()) {
      return place.isCanHold() ? 0 : 1;
    }
    final ProTerritory enemy = territoryManager.getEnemyAttackOptions().getMax(t);
    if (enemy == null || enemy.getMaxUnits().isEmpty()) {
      return 0;
    }
    final double threat = TuvUtils.getTuv(enemy.getMaxUnits(), proData.getUnitValueMap());
    final List<Unit> defenders =
        new ArrayList<>(t.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsSea())));
    defenders.addAll(place.getPlaceUnits());
    final double defense = TuvUtils.getTuv(defenders, proData.getUnitValueMap());
    return Math.max(0, threat - defense);
  }

  private int stagingDistance(final Territory t) {
    final List<Territory> targets = t.isWater() ? stagingSea : stagingLand;
    final Predicate<Territory> passable =
        t.isWater()
            ? ProMatches.territoryCanMoveSeaUnits(player, true)
            : ProMatches.territoryCanMoveLandUnits(player, false);
    int best = Integer.MAX_VALUE;
    for (final Territory target : targets) {
      final int d = data.getMap().getDistance(t, target, passable);
      if (d >= 0 && d < best) {
        best = d;
      }
    }
    if (best == Integer.MAX_VALUE && t.isWater() && !stagingLand.isEmpty()) {
      // a sea zone without sea gathering zones: by its coast's distance to the land ones
      for (final Territory coast : data.getMap().getNeighbors(t, Matches.territoryIsLand())) {
        best = Math.min(best, stagingDistance(coast) + 1);
      }
    }
    return best;
  }

  private int pus() {
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    return resourceTracker.getRemaining(pus);
  }

  private record Candidate(ProPlaceTerritory place, Territory factory) {}
}
