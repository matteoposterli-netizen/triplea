package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.ai.proplus.strategia.Strategia;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Step OPERAZIONI ANFIBIE-BIS (Pro+ only): coordination of two operations of the same plan with a
 * shared gathering zone, eg Overlord USA and Overlord UK.
 *
 * <ul>
 *   <li>combined fleet: the nation moving first counts the partner's warships that can join the
 *       gathering zone in the partner's turn of the same round as defenders of its fleet;
 *   <li>two-step bridgehead: the nation moving first lands on a target of both operations when the
 *       landing reaches the desired win% of the target's effective level and the territory, with
 *       the partner's reinforcements of the same round, holds the enemy's counter-attack (enemy
 *       win% at most {@code 100 - minimum win%} of that level); the partner then puts its troops
 *       ashore there in its non-combat move.
 * </ul>
 */
public final class TestaDiPonte {

  private TestaDiPonte() {}

  /**
   * An operation of mine and one of an allied nation of the same plan sharing a gathering zone.
   *
   * @param mia my operation (its land units are mine)
   * @param alleata the partner's operation
   * @param alleato the partner nation (lands with {@code alleata})
   * @param primaIo whether I move before the partner in the round
   */
  public record Coppia(
      Strategia.Operazione mia, Strategia.Operazione alleata, GamePlayer alleato, boolean primaIo) {

    /** Targets of both operations, in the order of the operation of the nation moving first. */
    public List<String> bersagliComuni() {
      final Strategia.Operazione first = primaIo ? mia : alleata;
      final Strategia.Operazione second = primaIo ? alleata : mia;
      return first.bersagli().stream().filter(second.bersagli()::contains).toList();
    }

    /** Gathering zones of both operations. */
    public List<String> raccoltaComune() {
      return mia.raccolta().stream().filter(alleata.raccolta()::contains).toList();
    }
  }

  /**
   * What the bridgehead rule found for a target.
   *
   * @param territorio the target
   * @param primo nation landing first (bridgehead)
   * @param secondo nation reinforcing in its turn of the same round
   * @param winSbarco win% of the landing
   * @param desiderata desired win% of the target's effective level
   * @param contrattaccoSenza enemy win% of its counter-attack on the landing units alone (NaN if
   *     not computed)
   * @param contrattaccoDopo enemy win% after the partner's reinforcements
   * @param soglia highest enemy win% accepted ({@code 100 - minimum})
   * @param rinforzi partner's land units that can reinforce the territory
   * @param tenuta whether the bridgehead holds by the rule
   */
  public record Valutazione(
      String territorio,
      String primo,
      String secondo,
      double winSbarco,
      double desiderata,
      double contrattaccoSenza,
      double contrattaccoDopo,
      double soglia,
      int rinforzi,
      boolean tenuta) {

    /** The same with the landing's win% (eg from the combat move's records). */
    public Valutazione withWin(final double win) {
      return new Valutazione(
          territorio,
          primo,
          secondo,
          win,
          desiderata,
          contrattaccoSenza,
          contrattaccoDopo,
          soglia,
          rinforzi,
          tenuta);
    }

    /** Eg "win% sbarco 85%, tenuta dopo il rinforzo di British (6 unita'): ...". */
    public String testo() {
      return String.format(
          java.util.Locale.ROOT,
          "win%% sbarco %s (desiderata %.0f%%), tenuta stimata dopo il rinforzo di %s (%d"
              + " unita'): contrattacco nemico %.0f%%%s %s soglia %.0f%%",
          Double.isNaN(winSbarco)
              ? "n/d"
              : String.format(java.util.Locale.ROOT, "%.0f%%", winSbarco),
          desiderata,
          secondo,
          rinforzi,
          contrattaccoDopo,
          Double.isNaN(contrattaccoSenza)
              ? ""
              : String.format(java.util.Locale.ROOT, " (senza rinforzo %.0f%%)", contrattaccoSenza),
          tenuta ? "<=" : ">",
          soglia);
    }
  }

  /** The pairs of {@code plan}'s operations whose land units are {@code me}'s. */
  public static List<Coppia> coppie(
      final GameState data, final PianoAttivo plan, final GamePlayer me) {
    final List<Coppia> list = new ArrayList<>();
    for (final Strategia.Operazione op : plan.getOperazioni()) {
      if (!op.nazioni().contains(me.getName())) {
        continue;
      }
      for (final Strategia.Operazione other : plan.operazioniCombinate(op)) {
        final GamePlayer partner =
            other.nazioni().stream()
                .filter(n -> !n.equals(me.getName()))
                .map(n -> data.getPlayerList().getPlayerId(n))
                .filter(p -> p != null && !p.isNull())
                .findFirst()
                .orElse(null);
        if (partner == null || !data.getRelationshipTracker().isAllied(me, partner)) {
          continue;
        }
        list.add(new Coppia(op, other, partner, muovePrima(data, me, partner)));
      }
    }
    return list;
  }

  /** Whether {@code a}'s turn comes before {@code b}'s in a round of the game's sequence. */
  static boolean muovePrima(final GameState data, final GamePlayer a, final GamePlayer b) {
    for (final GameStep step : data.getSequence()) {
      final GamePlayer p = step.getPlayerId();
      if (a.equals(p)) {
        return true;
      }
      if (b.equals(p)) {
        return false;
      }
    }
    return false;
  }

  /** The pair in which {@code proData}'s player moves first and {@code target} is a common one. */
  public static Optional<Coppia> primaSu(final ProData proData, final Territory target) {
    if (target.isWater()) {
      return Optional.empty();
    }
    return ProPlusTuning.planOf(proData).stream()
        .flatMap(p -> coppie(proData.getData(), p, proData.getPlayer()).stream())
        .filter(c -> c.primaIo() && c.bersagliComuni().contains(target.getName()))
        .findFirst();
  }

  /**
   * The bridgehead rule for a landing of mine on {@code target} with {@code holders} (my land units
   * left after the battle): the partner's reinforcements join them against the enemy's strongest
   * counter-attack ({@code enemyUnits}, {@code bombard}).
   */
  public static Valutazione valuta(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory target,
      final Collection<Unit> enemyUnits,
      final Collection<Unit> bombard,
      final double winPercentage,
      final double counterWithout,
      final Collection<Unit> holders,
      final Coppia coppia,
      final List<Unit> reinforcements) {
    final RiskLevel level = ProPlusTuning.riskLevel(proData, target);
    final boolean lowLuck = ProPlusTuning.isLowLuck(proData.getData());
    final double threshold = 100 - level.minimumWinPercentage(lowLuck);
    final List<Unit> defenders = new ArrayList<>(holders);
    defenders.addAll(reinforcements);
    final double counter =
        enemyUnits.isEmpty()
            ? 0
            : calc.calculateBattleResults(proData, target, enemyUnits, defenders, bombard)
                .getWinPercentage();
    final boolean holds = !reinforcements.isEmpty() && counter <= threshold;
    return new Valutazione(
        target.getName(),
        proData.getPlayer().getName(),
        coppia.alleato().getName(),
        winPercentage,
        level.desiredWinPercentage(lowLuck),
        counterWithout,
        counter,
        threshold,
        reinforcements.size(),
        holds);
  }

  /**
   * Land units of the partner its non-combat move can put on {@code target} this round: those
   * aboard its transports within reach of a sea zone next to the target, and those waiting in a
   * port of its operation that an empty transport of its can reach, load and bring there (from its
   * capital only while the enemy's best attack on it stays under {@link
   * ProPlusTuning#CAPITAL_GUARD_THRESHOLD}). Sea distances only (no check of enemy fleets).
   */
  public static List<Unit> rinforzi(
      final ProData proData,
      final @Nullable ProOddsCalculator calc,
      final Territory target,
      final Coppia coppia) {
    final GameMap map = proData.getData().getMap();
    final GamePlayer partner = coppia.alleato();
    final Set<Territory> adjacent = map.getNeighbors(target, Matches.territoryIsWater());
    final List<Unit> result = new ArrayList<>();
    if (adjacent.isEmpty()) {
      return result;
    }
    // land units waiting at the partner's ports
    final Map<Strategia.Porto, List<Unit>> waiting = new LinkedHashMap<>();
    for (final Strategia.Porto p : coppia.alleata().porti()) {
      final Territory land = map.getTerritoryOrNull(p.territorio());
      if (land == null
          || land.isWater()
          || !Matches.isTerritoryAllied(partner).test(land)
          || map.getTerritoryOrNull(p.zona()) == null) {
        continue;
      }
      final List<Unit> units =
          new ArrayList<>(
              OperazioniAnfibie.waitingFor(land, partner).stream()
                  .filter(u -> waiting.values().stream().noneMatch(l -> l.contains(u)))
                  .toList());
      units.sort(
          Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
              .thenComparing(u -> u.getType().getName()));
      waiting.put(p, guardCapital(proData, calc, land, partner, units));
    }
    for (final Territory zone : map.getTerritories()) {
      if (!zone.isWater()) {
        continue;
      }
      for (final Unit transport :
          zone.getMatches(Matches.unitIsOwnedBy(partner).and(OperazioniAnfibie::isTransport))) {
        final int moves = transport.getUnitAttachment().getMovement(partner);
        final List<Unit> cargo =
            transport.getTransporting().stream()
                .filter(u -> u.isOwnedBy(partner) && Matches.unitIsLand().test(u))
                .toList();
        if (!cargo.isEmpty()) {
          if (distance(map, zone, adjacent) <= moves) {
            result.addAll(cargo);
          }
          continue;
        }
        int room = TransportTracker.getAvailableCapacity(transport);
        for (final Map.Entry<Strategia.Porto, List<Unit>> e : waiting.entrySet()) {
          final Territory portZone = map.getTerritoryOrNull(e.getKey().zona());
          final int toPort = distance(map, zone, Set.of(portZone));
          final int toTarget = distance(map, portZone, adjacent);
          if (toPort == Integer.MAX_VALUE
              || toTarget == Integer.MAX_VALUE
              || toPort + toTarget > moves) {
            continue;
          }
          final List<Unit> left = e.getValue();
          for (final Unit u : new ArrayList<>(left)) {
            final int cost = u.getUnitAttachment().getTransportCost();
            if (cost > 0 && cost <= room) {
              result.add(u);
              left.remove(u);
              room -= cost;
            }
          }
          if (room <= 0) {
            break;
          }
        }
      }
    }
    return result;
  }

  /** Units of {@code candidates} that may leave {@code land} (all unless it's owner's capital). */
  private static List<Unit> guardCapital(
      final ProData proData,
      final @Nullable ProOddsCalculator calc,
      final Territory land,
      final GamePlayer owner,
      final List<Unit> candidates) {
    final boolean capital =
        land.isOwnedBy(owner)
            && TerritoryAttachment.get(land)
                .flatMap(TerritoryAttachment::getCapital)
                .map(c -> c.equals(owner.getName()))
                .orElse(false);
    if (!capital || candidates.isEmpty() || calc == null) {
      return candidates;
    }
    final ProTerritoryManager enemy = new ProTerritoryManager(calc, proData);
    enemy.populateEnemyAttackOptions(List.of(), Set.of(land));
    final ProTerritory max = enemy.getEnemyAttackOptions().getMax(land);
    if (max == null || max.getMaxUnits().isEmpty()) {
      return candidates;
    }
    final List<Unit> allowed = new ArrayList<>(candidates);
    while (!allowed.isEmpty()) {
      final List<Unit> defenders = new ArrayList<>(land.getMatches(Matches.isUnitAllied(owner)));
      defenders.removeAll(allowed);
      final double win =
          calc.calculateBattleResults(
                  proData, land, max.getMaxUnits(), defenders, max.getMaxBombardUnits())
              .getWinPercentage();
      if (win <= ProPlusTuning.CAPITAL_GUARD_THRESHOLD) {
        return allowed;
      }
      allowed.remove(allowed.size() - 1);
    }
    return allowed;
  }

  /** Sea distance from {@code from} to the nearest of {@code to}; MAX_VALUE if none. */
  static int distance(final GameMap map, final Territory from, final Collection<Territory> to) {
    int best = Integer.MAX_VALUE;
    for (final Territory t : to) {
      if (t == null) {
        continue;
      }
      final int d = from.equals(t) ? 0 : map.getDistance(from, t, Matches.territoryIsWater());
      if (d >= 0) {
        best = Math.min(best, d);
      }
    }
    return best;
  }
}
