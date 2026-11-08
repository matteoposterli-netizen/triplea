package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProLandingTransports;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProOtherMoveOptions;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Step 3d-1-bis: the transports of a landing decided by the combat move. After unloading, the
 * transports (and their escort) stay in the unload zone through the enemy turns before my next one.
 * For each sea zone they end in, the expected loss = enemy win% of its best attack on the zone (the
 * same estimate as the safe stops of {@link OperazioniAnfibie}: the enemy attack options with the
 * real enemies of step 3c) × TUV of my sea units there; transports without a warship = 100%. The
 * landing is made only if
 *
 * <ul>
 *   <li>(conquest value + strategic value) × win% + expected battle TUV swing > the summed expected
 *       loss, where strategic value = {@link #TUV_PER_WEIGHT} × (weight of the target as an
 *       objective of the phase in the data file + weight as a target of my amphibious operations),
 *       or
 *   <li>the target is a critical objective of the plan and the win% is above its desired win%.
 * </ul>
 *
 * <p>Otherwise each zone with a loss is compared with the other sea zones next to the target that
 * all its transports can reach this turn (cargo aboard or next to their zone, no enemy units there,
 * no scramble that would sink them): the one with the lowest loss replaces it if lower; the
 * bombarding ships stay. If the landing then pays off it is made from there, else dropped. Replaces
 * Hard's "transports exposed" rule and its objective / bridgehead exceptions for Pro+.
 */
final class TrasportiSbarco {
  /** Kept: the gain is above the expected loss of the transports. */
  static final String KEPT = "kept_transports_after_landing";

  /** Dropped: the expected loss of the transports is above the gain. */
  static final String DROPPED = "transports_after_landing";

  /**
   * Step 3d-1-bis (closure): TUV a point of plan weight is worth in the gain of a landing (the
   * strategic value of the target: weight as a phase objective + weight as an operation target).
   * Chosen, not tuned: the weights of the data file (10-20) are about the TUV of 1-3 transports.
   */
  static final double TUV_PER_WEIGHT = 1.0;

  private final ProData proData;
  private final ProOddsCalculator calc;
  private final GamePlayer player;
  private final GameMap map;
  private final ProOtherMoveOptions enemy;
  private final Collection<Unit> moving;
  private final List<TestaDiPonte.Coppia> coppie;

  private TrasportiSbarco(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProOtherMoveOptions enemy,
      final Collection<Unit> moving) {
    this.proData = proData;
    this.calc = calc;
    this.player = proData.getPlayer();
    this.map = proData.getData().getMap();
    this.enemy = enemy;
    this.moving = moving;
    this.coppie = TestaDiPonte.coppieDi(proData);
  }

  /** The loss expected in one zone: enemy win%, my TUV there, units. */
  record Zona(Territory zone, double enemyWin, double tuv, int transports, int warships) {
    double loss() {
      return enemyWin / 100 * tuv;
    }

    String testo() {
      return String.format(
          Locale.ROOT,
          "%s: win%% nemica %.0f%% x %.0f TUV (trasporti %d, scorta %d) = %.1f",
          zone.getName(),
          enemyWin,
          tuv,
          transports,
          warships,
          loss());
    }
  }

  static ProLandingTransports valuta(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritory landing,
      final Map<Territory, List<Unit>> unloadZones,
      final ProOtherMoveOptions enemy,
      final Collection<Unit> moving,
      final ProBattleResult battle,
      final double conquestValue,
      final BiPredicate<Territory, Collection<Unit>> lostToScramble) {
    return new TrasportiSbarco(proData, calc, enemy, moving)
        .check(landing, unloadZones, battle, conquestValue, lostToScramble);
  }

  private ProLandingTransports check(
      final ProTerritory landing,
      final Map<Territory, List<Unit>> unloadZones,
      final ProBattleResult battle,
      final double conquestValue,
      final BiPredicate<Territory, Collection<Unit>> lostToScramble) {
    final Territory target = landing.getTerritory();
    final double win = battle.getWinPercentage();
    final Optional<PianoAttivo> plan = ProPlusTuning.planOf(proData);
    if (plan.isPresent() && plan.get().isBersaglioInAttesa(target)) {
      // strategic contract: the directives keep the operation waiting this round
      return new ProLandingTransports(
          false,
          DROPPED,
          0,
          0,
          "operazione in attesa per direttiva: nessuno sbarco su "
              + target.getName()
              + " in questo round",
          Map.of());
    }
    final double objective = plan.map(p -> p.pesoObiettivoFase(target)).orElse(0.0);
    final double operation = plan.map(p -> p.pesoBersaglioOperazione(target)).orElse(0.0);
    final double strategic = TUV_PER_WEIGHT * (objective + operation);
    final double gain = (conquestValue + strategic) * win / 100 + battle.getTuvSwing();
    final String gainText =
        String.format(
                Locale.ROOT,
                "guadagno %.1f = conquista %.1f x win %.0f%% + TUV battaglia %.1f",
                gain,
                conquestValue,
                win,
                battle.getTuvSwing())
            + (strategic > 0
                ? String.format(
                    Locale.ROOT,
                    " + valore strategico %.1f x win %.0f%% (obiettivo %.1f + operazione %.1f)",
                    strategic,
                    win,
                    objective,
                    operation)
                : "");
    final Map<Territory, List<Unit>> zones = new LinkedHashMap<>();
    unloadZones.forEach((z, u) -> zones.put(z, new ArrayList<>(u)));
    final List<Zona> before = estimate(zones);
    final double loss = total(before);
    if (gain > loss) {
      return new ProLandingTransports(
          true, KEPT, loss, gain, text(loss, "<=", gain, gainText, before, ""), Map.of());
    }
    final double desired = ProPlusTuning.desiredWinPercentage(proData, target);
    // strategic contract: an operation the directives launch may lose up to their cap
    final double accepted = plan.map(p -> p.perditaAccettata(target)).orElse(0.0);
    if (accepted > 0 && loss <= accepted) {
      return new ProLandingTransports(
          true,
          KEPT,
          loss,
          gain,
          text(loss, ">", gain, gainText, before, "")
              + String.format(
                  Locale.ROOT,
                  "; tenuto: perdita entro il tetto della direttiva (%.0f TUV)",
                  accepted),
          Map.of());
    }
    if (plan.isPresent() && plan.get().isCritico(target) && win > desired) {
      return new ProLandingTransports(
          true,
          KEPT,
          loss,
          gain,
          text(loss, ">", gain, gainText, before, "")
              + String.format(
                  Locale.ROOT,
                  "; tenuto: obiettivo critico del piano, win %.0f%% > desiderata %.0f%%",
                  win,
                  desired),
          Map.of());
    }
    // another zone next to the target, safer, for the transports of each zone with a loss
    final Map<Unit, Territory> moved = new LinkedHashMap<>();
    final List<String> tried = new ArrayList<>();
    for (final Zona z : before) {
      if (z.loss() <= 0) {
        continue;
      }
      final List<Unit> transports =
          zones.get(z.zone()).stream()
              .filter(u -> landing.getAmphibAttackMap().containsKey(u))
              .toList();
      if (transports.isEmpty()) {
        continue;
      }
      final Optional<Zona> better = saferZone(landing, z, transports, zones, lostToScramble, tried);
      if (better.isPresent()) {
        zones.get(z.zone()).removeAll(transports);
        zones.computeIfAbsent(better.get().zone(), k -> new ArrayList<>()).addAll(transports);
        transports.forEach(u -> moved.put(u, better.get().zone()));
      }
    }
    zones.values().removeIf(List::isEmpty);
    final String triedText =
        tried.isEmpty() ? "" : "; zone alternative: " + String.join(", ", tried);
    if (!moved.isEmpty()) {
      final List<Zona> after = estimate(zones);
      final double newLoss = total(after);
      final String movedText = "; scarico spostato: " + movedText(moved, unloadZones);
      if (gain > newLoss) {
        return new ProLandingTransports(
            true,
            KEPT,
            newLoss,
            gain,
            text(newLoss, "<=", gain, gainText, after, "") + movedText + triedText,
            Map.copyOf(moved));
      }
      return new ProLandingTransports(
          false,
          DROPPED,
          newLoss,
          gain,
          text(newLoss, ">", gain, gainText, after, "") + movedText + triedText,
          Map.of());
    }
    return new ProLandingTransports(
        false,
        DROPPED,
        loss,
        gain,
        text(loss, ">", gain, gainText, before, "") + triedText,
        Map.of());
  }

  private static String text(
      final double loss,
      final String sign,
      final double gain,
      final String gainText,
      final List<Zona> zones,
      final String extra) {
    return String.format(Locale.ROOT, "perdita attesa %.1f TUV %s guadagno %.1f", loss, sign, gain)
        + " ("
        + String.join("; ", zones.stream().map(Zona::testo).toList())
        + "; "
        + gainText
        + ")"
        + extra;
  }

  private static String movedText(
      final Map<Unit, Territory> moved, final Map<Territory, List<Unit>> unloadZones) {
    final Map<String, Integer> count = new LinkedHashMap<>();
    moved.forEach(
        (u, to) -> {
          final String from =
              unloadZones.entrySet().stream()
                  .filter(e -> e.getValue().contains(u))
                  .map(e -> e.getKey().getName())
                  .findFirst()
                  .orElse("?");
          count.merge(from + " -> " + to.getName(), 1, Integer::sum);
        });
    return String.join(
        ", ", count.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")").toList());
  }

  private static double total(final List<Zona> zones) {
    return zones.stream().mapToDouble(Zona::loss).sum();
  }

  private List<Zona> estimate(final Map<Territory, List<Unit>> zones) {
    final List<Zona> list = new ArrayList<>();
    zones.forEach((z, units) -> list.add(zone(z, units)));
    return list;
  }

  /**
   * The enemy's best attack on {@code zone} against the allied ships there that stay (not used by
   * the combat move), {@code arriving} and the partner's warships joining a shared gathering zone
   * of a combined operation; my sea units there are what it may sink.
   */
  Zona zone(final Territory zone, final Collection<Unit> arriving) {
    final Set<Unit> defenders = new HashSet<>(arriving);
    // as for the safe stops: the partner's warships that join a shared gathering zone in its turn
    defenders.addAll(OperazioniAnfibie.combinedArrivals(map, coppie, zone));
    for (final Unit u :
        zone.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotLand()))) {
      if (!u.isOwnedBy(player) || !moving.contains(u)) {
        defenders.add(u);
      }
    }
    final List<Unit> mine =
        defenders.stream().filter(u -> u.isOwnedBy(player) && Matches.unitIsSea().test(u)).toList();
    final double tuv = TuvUtils.getTuv(mine, proData.getUnitValueMap());
    final int transports = (int) mine.stream().filter(OperazioniAnfibie::isTransport).count();
    final int warships = (int) defenders.stream().filter(OperazioniAnfibie::isWarship).count();
    final ProTerritory max = enemy.getMax(zone);
    if (max == null || max.getMaxUnits().isEmpty() || mine.isEmpty()) {
      return new Zona(zone, 0, tuv, transports, warships);
    }
    final double win =
        warships == 0
            ? 100
            : calc.calculateBattleResults(
                    proData, zone, max.getMaxUnits(), defenders, max.getMaxBombardUnits())
                .getWinPercentage();
    return new Zona(zone, win, tuv, transports, warships);
  }

  /**
   * The sea zone next to the target where the transports lower the summed loss most below {@code
   * from}'s (the loss left in {@code from} plus what they add there), that every transport can
   * reach this turn (its cargo aboard or next to its start zone), with no enemy units and no
   * scramble that beats the transports.
   */
  private Optional<Zona> saferZone(
      final ProTerritory landing,
      final Zona from,
      final List<Unit> transports,
      final Map<Territory, List<Unit>> zones,
      final BiPredicate<Territory, Collection<Unit>> lostToScramble,
      final List<String> tried) {
    final Territory target = landing.getTerritory();
    final Predicate<Territory> canPass = ProMatches.territoryCanMoveSeaUnitsThrough(player, true);
    final List<Territory> candidates =
        new ArrayList<>(map.getNeighbors(target, Matches.territoryIsWater()));
    candidates.sort(Comparator.comparing(Territory::getName));
    // the loss left in the zone the transports leave (bombarding ships stay there)
    final List<Unit> staying = new ArrayList<>(zones.get(from.zone()));
    staying.removeAll(transports);
    final double remaining = staying.isEmpty() ? 0 : zone(from.zone(), staying).loss();
    Zona best = null;
    double bestTotal = Double.MAX_VALUE;
    for (final Territory zone : candidates) {
      if (zone.equals(from.zone())
          || zone.anyUnitsMatch(Matches.enemyUnit(player))
          || !transports.stream().allMatch(tr -> canReach(landing, tr, zone, canPass))) {
        continue;
      }
      if (lostToScramble.test(zone, transports)) {
        tried.add(zone.getName() + " scramble nemico");
        continue;
      }
      final List<Unit> already = zones.getOrDefault(zone, List.of());
      final List<Unit> arriving = new ArrayList<>(already);
      arriving.addAll(transports);
      final Zona z = zone(zone, arriving);
      // what moving the transports there changes: their loss there (and what they add to the
      // loss of the units already going there) against the whole loss in the zone they leave
      final double added = z.loss() - (already.isEmpty() ? 0 : zone(zone, already).loss());
      final double total = remaining + added;
      tried.add(String.format(Locale.ROOT, "%s perdita %.1f", zone.getName(), z.loss()));
      if (total < from.loss() && (best == null || total < bestTotal)) {
        best = z;
        bestTotal = total;
      }
    }
    return Optional.ofNullable(best);
  }

  private boolean canReach(
      final ProTerritory landing,
      final Unit transport,
      final Territory zone,
      final Predicate<Territory> canPass) {
    final Territory start = proData.getUnitTerritory(transport);
    if (start == null) {
      return false;
    }
    for (final Unit u : landing.getAmphibAttackMap().getOrDefault(transport, List.of())) {
      final Territory at = proData.getUnitTerritory(u);
      if (!transport.getTransporting().contains(u)
          && (at == null || map.getDistance(start, at) != 1)) {
        return false;
      }
    }
    if (start.equals(zone)) {
      return true;
    }
    final Optional<Route> route = map.getRouteForUnit(start, zone, canPass, transport, player);
    return route.isPresent()
        && route.get().numberOfSteps() <= transport.getMovementLeft().intValue();
  }
}
