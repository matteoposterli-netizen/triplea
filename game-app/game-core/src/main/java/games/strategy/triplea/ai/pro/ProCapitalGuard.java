package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Minimum garrison of the capitals (Pro+, step 3b-bis): after the non-combat move has assigned
 * every unit, the enemy's best possible attack on each own capital next turn is estimated (the same
 * maximum attack the non-combat move defends against). While the enemy's win% is above the tuning's
 * threshold ({@link ProTuning#capitalGuardThreshold}), units that were leaving the capital stay and
 * units that can reach it are brought back, one at a time, cheapest land units first, then air. If
 * even every unit that can reach the capital leaves it at {@link #HOPELESS} or more, nothing
 * changes (the units would be lost with it). Only used when the tuning gives a threshold; Hard
 * never gets here.
 */
public class ProCapitalGuard {
  /** Enemy win% at which a capital is given up: more units would only be lost with it. */
  public static final double HOPELESS = 90;

  /** Battle calculations per capital at most (one per unit added). */
  private static final int MAX_STEPS = 40;

  /**
   * What the guard found and did for one capital.
   *
   * @param capital the capital
   * @param threshold enemy win% above which units are kept / brought back
   * @param riskBefore enemy win% with the moves of the non-combat move
   * @param riskAfter enemy win% after the guard's changes (= before if none)
   * @param garrisonBefore defenders (not infrastructure) with the moves of the non-combat move
   * @param garrisonAfter defenders after the guard's changes
   * @param kept units that were leaving the capital and stay (type → count)
   * @param broughtBack units brought back, per territory they were going to (name → type → count)
   * @param hopeless the capital stays above the threshold even with every unit that can reach it
   *     (changes undone if at {@link #HOPELESS} or more)
   */
  public record Result(
      Territory capital,
      double threshold,
      double riskBefore,
      double riskAfter,
      int garrisonBefore,
      int garrisonAfter,
      Map<String, Integer> kept,
      Map<String, Map<String, Integer>> broughtBack,
      boolean hopeless) {}

  private final ProData proData;
  private final ProOddsCalculator calc;
  private final GamePlayer player;

  ProCapitalGuard(final ProData proData, final ProOddsCalculator calc) {
    this.proData = proData;
    this.calc = calc;
    this.player = proData.getPlayer();
  }

  /** Applies the guard to {@code moveMap} (destination → units moving there or staying). */
  List<Result> apply(final Map<Territory, ProTerritory> moveMap, final double threshold) {
    final List<Result> results = new ArrayList<>();
    for (final Territory capital :
        TerritoryAttachment.getAllCurrentlyOwnedCapitals(player, proData.getData().getMap())) {
      final ProTerritory cap = moveMap.get(capital);
      if (cap != null) {
        results.add(guard(moveMap, cap, threshold));
      }
    }
    return results;
  }

  private Result guard(
      final Map<Territory, ProTerritory> moveMap, final ProTerritory cap, final double threshold) {
    final Territory capital = cap.getTerritory();
    final Map<Unit, ProTerritory> assignedTo = assignments(moveMap);
    final Set<Unit> defenders = defenders(cap, assignedTo);
    final double riskBefore = risk(cap, defenders);
    final int garrisonBefore = garrison(defenders);
    final Map<String, Integer> kept = new TreeMap<>();
    final Map<String, Map<String, Integer>> broughtBack = new TreeMap<>();
    if (riskBefore <= threshold) {
      return new Result(
          capital,
          threshold,
          riskBefore,
          riskBefore,
          garrisonBefore,
          garrisonBefore,
          kept,
          broughtBack,
          false);
    }

    final Set<Unit> cargo = cargo(moveMap);
    final List<Unit> candidates = new ArrayList<>();
    for (final Unit u : cap.getMaxUnits()) {
      final ProTerritory to = assignedTo.get(u);
      if (to != null
          && to != cap
          && !to.getTerritory().isWater()
          && !cargo.contains(u)
          && u.isOwnedBy(player)
          && Matches.unitIsNotInfrastructure().test(u)
          && Matches.unitIsNotSea().test(u)) {
        candidates.add(u);
      }
    }
    candidates.sort(
        Comparator.comparing((Unit u) -> !capital.equals(proData.getUnitTerritory(u)))
            .thenComparing(Matches.unitIsAir()::test)
            .thenComparingDouble(u -> TuvUtils.getTuv(List.of(u), proData.getUnitValueMap()))
            .thenComparing(u -> u.getType().getName())
            .thenComparing(u -> assignedTo.get(u).getTerritory().getName()));

    final Map<Unit, ProTerritory> moved = new LinkedHashMap<>();
    double risk = riskBefore;
    for (final Unit u : candidates) {
      if (risk <= threshold || moved.size() >= MAX_STEPS) {
        break;
      }
      final ProTerritory from = assignedTo.get(u);
      from.getUnits().remove(u);
      from.setBattleResult(null);
      cap.addUnit(u);
      defenders.add(u);
      moved.put(u, from);
      risk = risk(cap, defenders);
    }
    cap.setBattleResult(null);
    final boolean hopeless = risk > threshold;
    if (hopeless && risk >= HOPELESS) {
      // the capital falls anyway: the units would only be lost with it
      moved.forEach(
          (u, from) -> {
            cap.getUnits().remove(u);
            from.addUnit(u);
          });
      ProLogger.info(
          "[Pro+] Capital guard: "
              + capital
              + " can't be held (enemy win% "
              + risk
              + " with every unit that can reach it), no change");
      return new Result(
          capital,
          threshold,
          riskBefore,
          riskBefore,
          garrisonBefore,
          garrisonBefore,
          kept,
          broughtBack,
          true);
    }
    moved.forEach(
        (u, from) -> {
          if (capital.equals(proData.getUnitTerritory(u))) {
            kept.merge(u.getType().getName(), 1, Integer::sum);
          } else {
            broughtBack
                .computeIfAbsent(from.getTerritory().getName(), k -> new TreeMap<>())
                .merge(u.getType().getName(), 1, Integer::sum);
          }
        });
    ProLogger.info(
        "[Pro+] Capital guard: "
            + capital
            + " enemy win% "
            + riskBefore
            + " -> "
            + risk
            + " (threshold "
            + threshold
            + "), kept "
            + kept
            + ", brought back "
            + broughtBack);
    return new Result(
        capital,
        threshold,
        riskBefore,
        risk,
        garrisonBefore,
        garrison(defenders),
        kept,
        broughtBack,
        hopeless);
  }

  /**
   * What the minimum garrison of one territory found and did (step 3c).
   *
   * @param territory the territory
   * @param required land units it must keep
   * @param before land units (not infrastructure) with the moves of the non-combat move
   * @param after land units after the changes
   * @param moved units that stay or are brought in, per territory they were going to (name → type →
   *     count)
   */
  public record GarrisonResult(
      Territory territory,
      int required,
      int before,
      int after,
      Map<String, Map<String, Integer>> moved) {}

  /**
   * Keeps at least the required land units in each own territory of {@code garrisons} (name →
   * units): cheapest land units that can reach it first, never taken from an own capital nor from
   * another garrisoned territory below its minimum. Territories not owned are skipped.
   */
  List<GarrisonResult> minimumGarrisons(
      final Map<Territory, ProTerritory> moveMap, final Map<String, Integer> garrisons) {
    final List<GarrisonResult> results = new ArrayList<>();
    final Set<Territory> capitals =
        new HashSet<>(
            TerritoryAttachment.getAllCurrentlyOwnedCapitals(player, proData.getData().getMap()));
    final Map<Territory, Integer> required = new LinkedHashMap<>();
    garrisons.forEach(
        (name, n) -> {
          final Territory t = proData.getData().getMap().getTerritoryOrNull(name);
          if (t != null && !t.isWater() && t.isOwnedBy(player) && moveMap.get(t) != null) {
            required.put(t, n);
          }
        });
    for (final Map.Entry<Territory, Integer> e : required.entrySet()) {
      final ProTerritory pt = moveMap.get(e.getKey());
      final Map<Unit, ProTerritory> assignedTo = assignments(moveMap);
      final Set<Unit> defenders = defenders(pt, assignedTo);
      final int before = landCount(defenders);
      final Map<String, Map<String, Integer>> moved = new TreeMap<>();
      if (before < e.getValue()) {
        final Set<Unit> cargo = cargo(moveMap);
        final List<Unit> candidates = new ArrayList<>();
        for (final Unit u : pt.getMaxUnits()) {
          final ProTerritory from = assignedTo.get(u);
          if (from == null
              || from == pt
              || from.getTerritory().isWater()
              || capitals.contains(from.getTerritory())
              || cargo.contains(u)
              || !u.isOwnedBy(player)
              || !Matches.unitIsLand().test(u)
              || Matches.unitIsInfrastructure().test(u)) {
            continue;
          }
          final Integer min = required.get(from.getTerritory());
          if (min != null && landCount(defenders(from, assignedTo)) <= min) {
            continue;
          }
          candidates.add(u);
        }
        candidates.sort(
            Comparator.comparingDouble(
                    (Unit u) -> TuvUtils.getTuv(List.of(u), proData.getUnitValueMap()))
                .thenComparing(u -> u.getType().getName())
                .thenComparing(u -> assignedTo.get(u).getTerritory().getName()));
        int count = before;
        for (final Unit u : candidates) {
          if (count >= e.getValue()) {
            break;
          }
          final ProTerritory from = assignedTo.get(u);
          final Integer min = required.get(from.getTerritory());
          if (min != null && landCount(defenders(from, assignments(moveMap))) <= min) {
            continue;
          }
          from.getUnits().remove(u);
          pt.addUnit(u);
          count++;
          moved
              .computeIfAbsent(from.getTerritory().getName(), k -> new TreeMap<>())
              .merge(u.getType().getName(), 1, Integer::sum);
        }
      }
      final int after = landCount(defenders(pt, assignments(moveMap)));
      ProLogger.info(
          "[Pro+] Minimum garrison: "
              + pt.getTerritory()
              + " "
              + before
              + " -> "
              + after
              + " (required "
              + e.getValue()
              + "), moved "
              + moved);
      results.add(new GarrisonResult(pt.getTerritory(), e.getValue(), before, after, moved));
    }
    return results;
  }

  private static int landCount(final Collection<Unit> units) {
    return (int)
        units.stream().filter(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure())).count();
  }

  /** Enemy win% of its maximum attack on the capital against {@code defenders}. */
  private double risk(final ProTerritory cap, final Collection<Unit> defenders) {
    if (cap.getMaxEnemyUnits().isEmpty()) {
      return 0;
    }
    final ProBattleResult result = calc.calculateBattleResults(proData, cap, defenders);
    return result.getWinPercentage();
  }

  /**
   * Defenders of the capital after the moves: those assigned to it (staying or arriving), those
   * that can't move, and own or allied units there that no move takes away.
   */
  private Set<Unit> defenders(final ProTerritory cap, final Map<Unit, ProTerritory> assignedTo) {
    final Set<Unit> defenders = new HashSet<>(cap.getAllDefenders());
    for (final Unit u : cap.getTerritory().getMatches(Matches.isUnitAllied(player))) {
      if (!assignedTo.containsKey(u)) {
        defenders.add(u);
      }
    }
    return defenders;
  }

  private static int garrison(final Collection<Unit> defenders) {
    return (int) defenders.stream().filter(Matches.unitIsNotInfrastructure()).count();
  }

  /** Destination of every unit the non-combat move assigned. */
  private static Map<Unit, ProTerritory> assignments(final Map<Territory, ProTerritory> moveMap) {
    final Map<Unit, ProTerritory> assigned = new LinkedHashMap<>();
    for (final ProTerritory pt : moveMap.values()) {
      for (final Unit u : pt.getUnits()) {
        assigned.put(u, pt);
      }
    }
    return assigned;
  }

  /** Units moving on transports (land units and their transports): the guard leaves them. */
  private static Set<Unit> cargo(final Map<Territory, ProTerritory> moveMap) {
    final Set<Unit> cargo = new HashSet<>();
    for (final ProTerritory pt : moveMap.values()) {
      for (final Map.Entry<Unit, List<Unit>> e : pt.getAmphibAttackMap().entrySet()) {
        cargo.add(e.getKey());
        cargo.addAll(e.getValue());
      }
      for (final Map.Entry<Unit, List<Unit>> e : pt.getTempAmphibAttackMap().entrySet()) {
        cargo.add(e.getKey());
        cargo.addAll(e.getValue());
      }
      cargo.addAll(pt.getTransportTerritoryMap().keySet());
    }
    return cargo;
  }
}
