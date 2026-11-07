package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.ai.proplus.strategia.Strategia;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.util.TuvUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Amphibious operations of a strategy plan (Pro+, step OPERAZIONI ANFIBIE), played in the real
 * non-combat move of a nation before the Pro AI's own moves:
 *
 * <ul>
 *   <li>loading: land units waiting in a port board the empty transports in the port's sea zone
 *       (from a capital only while the enemy's best attack on it stays under {@link
 *       ProPlusTuning#CAPITAL_GUARD_THRESHOLD});
 *   <li>transit: loaded transports move towards the nearest gathering zone, only to a zone where
 *       the enemy's best attack on the fleet wins at most {@code 100 - desired win%} of the zone's
 *       effective risk level; own warships that can reach the zone join as escort until it is safe;
 *   <li>landing: none here. The combat move of the Pro AI lands on the operation's targets (they
 *       are objectives of the plan, attacked only at the desired win%). A fleet that waited a whole
 *       turn loaded at the gathering zone without landing puts its troops ashore on the nearest
 *       friendly target or port (they embark again from there in the next combat move);
 *   <li>shuttle: empty transports go back to the port where most units wait (else the first port
 *       with a factory);
 *   <li>allied transports ({@code vettori} of another nation): the cargo nation loads its units on
 *       them in its own turn and unloads them on a friendly target in a later turn of its own.
 * </ul>
 *
 * <p>Every transport, cargo and escort the operation moved or keeps in place is returned, so the
 * Pro AI's non-combat move leaves them where they are ({@code ProTuning#heldUnits}). A blocked
 * operation keeps its fleet where it is safe, or leaves it to the Pro AI (which moves transports to
 * the safest zone) when even that zone is not safe.
 */
public final class OperazioniAnfibie {

  /** Farthest sea distance of a transport from the operation's zones to take part in it. */
  static final int MAX_DISTANCE = 6;

  /** Escorts added to one destination at most (one battle estimate each). */
  private static final int MAX_ESCORTS = 6;

  /** State of an operation at the end of the nation's move, for the diary. */
  public enum Stato {
    PREPARAZIONE("preparazione"),
    IN_ROTTA("in rotta"),
    SBARCO("sbarco"),
    BLOCCATA("bloccata");

    private final String testo;

    Stato(final String testo) {
      this.testo = testo;
    }

    public String testo() {
      return testo;
    }
  }

  /**
   * What one operation did in a nation's turn.
   *
   * @param operazione the operation's name
   * @param stato state after the moves
   * @param trasporti transports of the operation (own, or allied ones carrying or awaiting my
   *     units)
   * @param truppeABordo land units of the operation's nations on them
   * @param scorta warships (own and allied) with the loaded fleet, or at the gathering zones
   * @param sbarcate units of the nation that landed from transports this turn (combat move)
   * @param sbarcateSu where they landed
   * @param prossimoPasso the next step (Italian text)
   * @param motivo why (Italian text), empty if nothing to explain
   * @param mosse moves made (Italian text)
   * @param bersaglio first enemy target and the estimated win% of a landing now, if computed
   */
  public record Rapporto(
      String operazione,
      String nazione,
      Stato stato,
      int trasporti,
      int truppeABordo,
      int scorta,
      int sbarcate,
      List<String> sbarcateSu,
      String prossimoPasso,
      String motivo,
      List<String> mosse,
      Optional<String> bersaglio,
      double winStimata) {}

  /** The units the operations keep in place, and the reports. */
  public record Esito(Set<Unit> tenute, List<Rapporto> rapporti) {
    static final Esito NESSUNO = new Esito(Set.of(), List.of());
  }

  private final ProData proData;
  private final @Nullable ProOddsCalculator calc;
  private final @Nullable IMoveDelegate moveDel;
  private final GameData data;
  private final GamePlayer player;
  private final GameMap map;
  private final boolean lowLuck;
  private final Set<Unit> held = new LinkedHashSet<>();

  /** My transports that had cargo when the operations started (loaded in an earlier turn). */
  private final Set<Unit> loadedAtStart = new HashSet<>();

  private final Map<Unit, Territory> where = new HashMap<>();
  private @Nullable ProTerritoryManager enemy;

  private OperazioniAnfibie(
      final ProData proData,
      final @Nullable ProOddsCalculator calc,
      final @Nullable IMoveDelegate moveDel) {
    this.proData = proData;
    this.calc = calc;
    this.moveDel = moveDel;
    this.data = proData.getData();
    this.player = proData.getPlayer();
    this.map = data.getMap();
    this.lowLuck = ProPlusTuning.isLowLuck(data);
  }

  /** Plays the operations of {@code plan} that concern its nation; nothing without any. */
  public static Esito esegui(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final PianoAttivo plan) {
    final List<Strategia.Operazione> ops = plan.getOperazioni();
    if (ops.isEmpty()) {
      return Esito.NESSUNO;
    }
    final OperazioniAnfibie o = new OperazioniAnfibie(proData, calc, moveDel);
    final List<Rapporto> reports = new ArrayList<>();
    final Map<Strategia.Operazione, List<Unit>> transports = o.assignTransports(ops);
    transports.values().stream()
        .flatMap(List::stream)
        .filter(t -> !t.getTransporting().isEmpty())
        .forEach(o.loadedAtStart::add);
    o.findEnemyAttacks(ops, transports);
    for (final Strategia.Operazione op : ops) {
      try {
        reports.add(o.play(op, transports.getOrDefault(op, List.of())));
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] Amphibious operation " + op.nome() + " failed: " + e);
      }
    }
    return new Esito(Set.copyOf(o.held), List.copyOf(reports));
  }

  // ------------------------------------------------------------------------------ one operation

  private Rapporto play(final Strategia.Operazione op, final List<Unit> transports) {
    final List<String> moves = new ArrayList<>();
    final List<String> reasons = new ArrayList<>();
    final Map<String, Integer> landed = landedThisTurn(op);
    final int landedUnits = landed.values().stream().mapToInt(Integer::intValue).sum();
    final boolean carrier = op.vettori().contains(player.getName());
    if (op.nazioni().contains(player.getName()) && !carrier) {
      playCargoNation(op, moves, reasons);
    }
    if (carrier) {
      // loaded first, then the empty ones at a port, then the others
      final List<Unit> ordered = new ArrayList<>(transports);
      ordered.sort(
          Comparator.comparing((Unit t) -> t.getTransporting().isEmpty())
              .thenComparing(t -> portOf(op, territory(t)).isEmpty())
              .thenComparing(t -> territory(t).getName()));
      for (final Unit t : ordered) {
        playTransport(op, t, moves, reasons);
      }
    }
    return report(op, transports, landed, landedUnits, moves, reasons);
  }

  /** A transport of mine: load, move towards the gathering zone, wait, or go back to a port. */
  private void playTransport(
      final Strategia.Operazione op,
      final Unit transport,
      final List<String> moves,
      final List<String> reasons) {
    final Territory zone = territory(transport);
    if (!transport.getUnloaded().isEmpty()) {
      return; // unloaded this turn: it can't move again
    }
    final List<Unit> cargo = cargo(op, transport);
    if (!cargo.isEmpty()) {
      playLoaded(op, transport, zone, moves, reasons);
      return;
    }
    final Optional<Strategia.Porto> port = portOf(op, zone);
    if (port.isPresent()) {
      final Territory land = map.getTerritoryOrNull(port.get().territorio());
      if (alliedCargoWaitsAt(op, zone)) {
        // allied troops board in their own turn: keep the room for them
        holdIfSafe(op, List.of(transport), zone, reasons, "attende le truppe alleate al porto");
        return;
      }
      if (land != null
          && op.raccolta().contains(zone.getName())
          && !waiting(land, player).isEmpty()) {
        // the troops would wait aboard here: only if the fleet is safe
        final Safety safety = makeSafe(op, zone, List.of(transport));
        if (!safety.safe()) {
          reasons.add(
              String.format(
                  Locale.ROOT,
                  "nessun imbarco a %s: %s non e' sicura (win%% nemica %.0f%% > soglia %.0f%%,"
                      + " scorta %d)",
                  land.getName(),
                  zone.getName(),
                  safety.enemyWin(),
                  safety.threshold(),
                  safety.warships()));
          return;
        }
        safety.escortsMoved().forEach(held::add);
      }
      final List<Unit> loaded = land == null ? List.of() : load(op, transport, land, zone, moves);
      if (!loaded.isEmpty()) {
        playLoaded(op, transport, zone, moves, reasons);
        return;
      }
    }
    shuttle(op, transport, zone, moves, reasons);
  }

  /** A loaded transport: to the gathering zone, wait there, or put the troops ashore. */
  private void playLoaded(
      final Strategia.Operazione op,
      final Unit transport,
      final Territory zone,
      final List<String> moves,
      final List<String> reasons) {
    final List<Unit> cargo = cargo(op, transport);
    if (op.raccolta().contains(zone.getName())) {
      // loaded at the gathering zone since the last turn and no landing in this combat move
      final boolean waited =
          loadedAtStart.contains(transport) && transport.getAlreadyMoved().signum() == 0;
      if (waited && unloadAshore(op, transport, zone, moves, reasons)) {
        return;
      }
      if (!waited) {
        unloadOnFriendlyTarget(op, transport, zone, moves);
        if (ownCargo(transport).isEmpty() && cargo(op, transport).isEmpty()) {
          holdIfSafe(op, List.of(transport), zone, reasons, "trasporto scaricato");
          return;
        }
      }
      holdIfSafe(op, withCargo(transport), zone, reasons, "flotta alla zona di raccolta");
      return;
    }
    final int movesLeft = transport.getMovementLeft().intValue();
    if (movesLeft <= 0) {
      holdIfSafe(op, withCargo(transport), zone, reasons, "senza movimento");
      return;
    }
    final Optional<Territory> gather = nearest(zone, op.raccolta());
    if (gather.isEmpty()) {
      reasons.add("nessuna rotta verso " + String.join("/", op.raccolta()));
      return;
    }
    final Optional<Route> route = seaRoute(zone, gather.get(), withCargo(transport));
    if (route.isEmpty()) {
      reasons.add("nessuna rotta libera verso " + gather.get().getName());
      return;
    }
    final List<Territory> path = route.get().getAllTerritories();
    final int steps = Math.min(movesLeft, path.size() - 1);
    for (int s = steps; s >= 1; s--) {
      final Territory end = path.get(s);
      final Safety safety = makeSafe(op, end, List.of(transport));
      if (safety.safe()) {
        if (move(withCargo(transport), new Route(path.subList(0, s + 1)), moves, "in rotta")) {
          held.addAll(withCargo(transport));
          safety.escortsMoved().forEach(held::add);
          held.addAll(warships(end));
          if (op.raccolta().contains(end.getName())) {
            unloadOnFriendlyTarget(op, transport, end, moves);
          }
        }
        return;
      }
      if (s == steps) {
        reasons.add(
            String.format(
                Locale.ROOT,
                "scorta insufficiente per %s: win%% nemica %.0f%% > soglia %.0f%% (scorta %d)",
                end.getName(),
                safety.enemyWin(),
                safety.threshold(),
                safety.warships()));
      }
    }
    holdIfSafe(op, withCargo(transport), zone, reasons, "in attesa di scorta");
  }

  /**
   * At the gathering zone after a whole turn loaded without landing (the target was too strong):
   * the own troops go ashore on a friendly target or port next to the zone. Allied troops stay
   * aboard (only their owner can unload them).
   */
  private boolean unloadAshore(
      final Strategia.Operazione op,
      final Unit transport,
      final Territory zone,
      final List<String> moves,
      final List<String> reasons) {
    final Optional<Territory> enemyTarget = firstEnemyTargetInReach(op, zone);
    final List<Unit> mine = ownCargo(transport);
    if (mine.isEmpty()) {
      return false;
    }
    final List<Territory> ashore = friendlyShores(op, zone);
    if (ashore.isEmpty()) {
      return false;
    }
    enemyTarget.ifPresent(
        t ->
            reasons.add(
                "bersaglio troppo forte: nessuno sbarco su "
                    + t.getName()
                    + " nel combattimento, truppe a terra a "
                    + ashore.get(0).getName()));
    if (move(mine, new Route(zone, ashore.get(0)), moves, "a terra")) {
      held.add(transport);
      held.addAll(mine);
      return true;
    }
    return false;
  }

  /**
   * Arrived at the gathering zone: unload the own troops at once on a friendly target next to it
   * when no enemy target is in reach of the zone (eg Italy's convoy to Libya).
   */
  private void unloadOnFriendlyTarget(
      final Strategia.Operazione op,
      final Unit transport,
      final Territory zone,
      final List<String> moves) {
    final List<Unit> mine = ownCargo(transport);
    if (mine.isEmpty()) {
      return;
    }
    // targets in order: an enemy one in reach first means wait for the combat move
    final Set<Territory> reach = reach(zone);
    for (final String name : op.bersagli()) {
      final Territory t = map.getTerritoryOrNull(name);
      if (t == null || t.isWater()) {
        continue;
      }
      if (isFriendlyLand(t) && map.getNeighbors(zone).contains(t)) {
        if (move(mine, new Route(zone, t), moves, "sbarco su territorio amico")) {
          held.addAll(mine);
        }
        return;
      }
      if (Matches.isTerritoryEnemy(player).test(t)
          && map.getNeighbors(t, Matches.territoryIsWater()).stream().anyMatch(reach::contains)) {
        return;
      }
    }
  }

  /**
   * An empty transport: to the port where most units wait (else the first port with a factory),
   * trying the next port when the way to one is not safe.
   */
  private void shuttle(
      final Strategia.Operazione op,
      final Unit transport,
      final Territory zone,
      final List<String> moves,
      final List<String> reasons) {
    final int movesLeft = transport.getMovementLeft().intValue();
    final List<Strategia.Porto> ports = portsToGo(op, zone);
    if (ports.isEmpty() || movesLeft <= 0) {
      return;
    }
    final List<String> unsafe = new ArrayList<>();
    for (final Strategia.Porto target : ports) {
      final Territory portZone = map.getTerritoryOrNull(target.zona());
      if (portZone == null) {
        continue;
      }
      if (portZone.equals(zone)) {
        holdIfSafe(op, List.of(transport), zone, reasons, "trasporto vuoto in porto");
        return;
      }
      final Optional<Route> route = seaRoute(zone, portZone, List.of(transport));
      if (route.isEmpty()) {
        continue;
      }
      final List<Territory> path = route.get().getAllTerritories();
      final int steps = Math.min(movesLeft, path.size() - 1);
      for (int s = steps; s >= 1; s--) {
        final Territory end = path.get(s);
        final Safety safety = makeSafe(op, end, List.of(transport));
        if (!safety.safe()) {
          continue;
        }
        if (move(List.of(transport), new Route(path.subList(0, s + 1)), moves, "spola")) {
          held.add(transport);
          safety.escortsMoved().forEach(held::add);
          final Territory land = map.getTerritoryOrNull(target.territorio());
          if (end.equals(portZone) && land != null && !alliedCargoWaitsAt(op, end)) {
            held.addAll(load(op, transport, land, end, moves));
          }
        }
        return;
      }
      unsafe.add(portZone.getName());
    }
    if (!unsafe.isEmpty()) {
      reasons.add("trasporto vuoto fermo: rotta verso " + String.join("/", unsafe) + " non sicura");
    }
  }

  /**
   * A nation whose units travel on allied transports: unload the cargo on a friendly target, then
   * load the units waiting at the ports on the allied transports with room there.
   */
  private void playCargoNation(
      final Strategia.Operazione op, final List<String> moves, final List<String> reasons) {
    for (final Territory zone : map.getTerritories()) {
      if (!zone.isWater()) {
        continue;
      }
      final List<Unit> mineAboard =
          zone.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsLand())
                  .and(Matches.unitIsBeingTransported()));
      if (mineAboard.isEmpty()) {
        continue;
      }
      final List<Unit> loadedEarlier =
          mineAboard.stream().filter(u -> !u.getWasLoadedThisTurn()).toList();
      Territory shore = null;
      for (final String name : op.bersagli()) {
        final Territory t = map.getTerritoryOrNull(name);
        if (t != null && isFriendlyLand(t) && map.getNeighbors(zone).contains(t)) {
          shore = t;
          break;
        }
      }
      if (shore == null) {
        if (firstEnemyTargetInReach(op, zone).isPresent()) {
          reasons.add(
              "truppe su trasporti alleati in "
                  + zone.getName()
                  + ": bersaglio nemico, sbarco da trasporto alleato non gestito");
        }
        continue;
      }
      if (!loadedEarlier.isEmpty()) {
        move(loadedEarlier, new Route(zone, shore), moves, "sbarco da trasporto alleato");
        held.addAll(loadedEarlier);
      }
    }
    for (final Strategia.Porto p : op.porti()) {
      final Territory land = map.getTerritoryOrNull(p.territorio());
      final Territory zone = map.getTerritoryOrNull(p.zona());
      if (land == null || zone == null) {
        continue;
      }
      final List<Unit> carriers =
          zone.getMatches(
              Matches.unitIsSeaTransportButNotCombatSeaTransport()
                  .and(u -> op.vettori().contains(u.getOwner().getName()))
                  .and(u -> !u.isOwnedBy(player))
                  .and(u -> TransportTracker.getAvailableCapacity(u) > 0));
      if (carriers.isEmpty() || waiting(land, player).isEmpty()) {
        continue;
      }
      final Safety safety = safety(op, zone, List.of());
      if (!safety.safe()) {
        reasons.add(
            String.format(
                Locale.ROOT,
                "porto %s esposto: win%% nemica %.0f%% > soglia %.0f%%, nessun imbarco",
                zone.getName(),
                safety.enemyWin(),
                safety.threshold()));
        continue;
      }
      for (final Unit c : carriers) {
        held.addAll(load(op, c, land, zone, moves));
      }
    }
  }

  // ------------------------------------------------------------------------------------ loading

  /**
   * Loads on {@code transport} (in {@code zone}) the units of my nation waiting in {@code land},
   * biggest first, as many as fit; from a capital only while it stays guarded.
   */
  private List<Unit> load(
      final Strategia.Operazione op,
      final Unit transport,
      final Territory land,
      final Territory zone,
      final List<String> moves) {
    if (!op.nazioni().contains(player.getName()) || !isFriendlyLand(land)) {
      return List.of();
    }
    final List<Unit> candidates = new ArrayList<>(waiting(land, player));
    candidates.removeAll(held);
    if (candidates.isEmpty()) {
      return List.of();
    }
    candidates.sort(
        Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
            .thenComparing(u -> u.getType().getName()));
    int room = TransportTracker.getAvailableCapacity(transport);
    final List<Unit> chosen = new ArrayList<>();
    for (final Unit u : candidates) {
      final int cost = u.getUnitAttachment().getTransportCost();
      if (cost > 0 && cost <= room) {
        chosen.add(u);
        room -= cost;
      }
    }
    final List<Unit> allowed = guardCapital(land, chosen);
    final List<Unit> loaded = new ArrayList<>();
    for (final Unit u : allowed) {
      final MoveDescription m =
          new MoveDescription(List.of(u), new Route(land, zone), Map.of(u, transport));
      final Optional<String> error = moveDel.performMove(m);
      if (error.isPresent()) {
        ProLogger.warn("[Pro+] Operation " + op.nome() + ": could not load " + u + ": " + error);
        continue;
      }
      where.put(u, zone);
      loaded.add(u);
    }
    if (!loaded.isEmpty()) {
      moves.add(
          "imbarco a "
              + land.getName()
              + " su "
              + transport.getOwner().getName()
              + " in "
              + zone.getName()
              + ": "
              + unitsText(loaded));
    }
    return loaded;
  }

  /**
   * The units of {@code chosen} that may leave {@code land}: all of them unless it is a capital of
   * mine, where the enemy's best attack after they leave must stay under the capital guard.
   */
  private List<Unit> guardCapital(final Territory land, final List<Unit> chosen) {
    if (chosen.isEmpty() || !isMyCapital(land)) {
      return chosen;
    }
    final ProTerritory max = enemyMax(land);
    if (max == null || max.getMaxUnits().isEmpty()) {
      return chosen;
    }
    final List<Unit> allowed = new ArrayList<>(chosen);
    while (!allowed.isEmpty()) {
      final List<Unit> defenders = new ArrayList<>(land.getMatches(Matches.isUnitAllied(player)));
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

  /** Land units of {@code owner} in {@code land} that can still board a transport. */
  private static List<Unit> waiting(final Territory land, final GamePlayer owner) {
    return land.getMatches(
        Matches.unitIsOwnedBy(owner)
            .and(Matches.unitIsLand())
            .and(Matches.unitIsNotInfrastructure())
            .and(Matches.unitIsAaForAnything().negate())
            .and(u -> u.getUnitAttachment().getAttack(u.getOwner()) > 0)
            .and(Matches.unitCanBeTransported())
            .and(Matches.unitHasMovementLeft())
            .and(u -> !u.getWasInCombat())
            .and(u -> u.getUnitAttachment().getTransportCost() > 0));
  }

  /** Whether units of another nation of the operation wait in a port of {@code zone}. */
  private boolean alliedCargoWaitsAt(final Strategia.Operazione op, final Territory zone) {
    return op.porti().stream()
        .filter(p -> p.zona().equals(zone.getName()))
        .map(p -> map.getTerritoryOrNull(p.territorio()))
        .anyMatch(land -> land != null && alliedCargoWaits(op, land));
  }

  private boolean alliedCargoWaits(final Strategia.Operazione op, final Territory land) {
    for (final String nation : op.nazioni()) {
      if (nation.equals(player.getName())) {
        continue;
      }
      final GamePlayer p = data.getPlayerList().getPlayerId(nation);
      if (p != null
          && land.anyUnitsMatch(
              Matches.unitIsOwnedBy(p)
                  .and(Matches.unitIsLand())
                  .and(Matches.unitIsNotInfrastructure())
                  .and(Matches.unitCanBeTransported()))) {
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------------------------------- safety

  /**
   * Whether {@code zone} is safe for the fleet with {@code arriving} (transports moving there): the
   * enemy's best attack on the zone wins at most {@code 100 - desired win%} of the zone's risk
   * level. If not, own warships that can reach the zone are moved there one at a time (nearest,
   * then cheapest) until it is.
   */
  private Safety makeSafe(
      final Strategia.Operazione op, final Territory zone, final List<Unit> arriving) {
    Safety safety = safety(op, zone, List.of());
    if (safety.safe()) {
      return safety;
    }
    final List<Unit> escorts = new ArrayList<>();
    final Map<Unit, Route> routes = new LinkedHashMap<>();
    for (final Unit e : escortCandidates(zone, arriving)) {
      if (escorts.size() >= MAX_ESCORTS) {
        break;
      }
      final Optional<Route> r = seaRoute(territory(e), zone, List.of(e));
      if (r.isEmpty() || r.get().numberOfSteps() > e.getMovementLeft().intValue()) {
        continue;
      }
      escorts.add(e);
      routes.put(e, r.get());
      safety = safety(op, zone, escorts);
      if (safety.safe()) {
        final List<Unit> moved = new ArrayList<>();
        routes.forEach(
            (u, route) -> {
              if (moveDel.performMove(new MoveDescription(List.of(u), route)).isEmpty()) {
                where.put(u, zone);
                moved.add(u);
              }
            });
        return new Safety(
            true, safety.enemyWin(), safety.threshold(), safety.warships(), List.copyOf(moved));
      }
    }
    return safety;
  }

  private Safety safety(
      final Strategia.Operazione op, final Territory zone, final Collection<Unit> escorts) {
    final double threshold =
        100 - ProPlusTuning.riskLevel(proData, zone).desiredWinPercentage(lowLuck);
    final List<Unit> defenders =
        new ArrayList<>(zone.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotLand())));
    defenders.addAll(escorts);
    final int warships = (int) defenders.stream().filter(OperazioniAnfibie::isWarship).count();
    final ProTerritory max = enemyMax(zone);
    if (max == null || max.getMaxUnits().isEmpty()) {
      return new Safety(true, 0, threshold, warships, List.of());
    }
    final double win =
        defenders.stream().noneMatch(OperazioniAnfibie::isWarship)
            ? 100
            : calc.calculateBattleResults(
                    proData, zone, max.getMaxUnits(), defenders, max.getMaxBombardUnits())
                .getWinPercentage();
    final boolean enoughEscort = warships >= Math.min(op.scortaMinima(), 1) || win <= 0;
    return new Safety(win <= threshold && enoughEscort, win, threshold, warships, List.of());
  }

  /**
   * @param enemyWin win% of the enemy's best attack on the zone
   * @param threshold highest enemy win% accepted
   * @param warships warships defending the zone (own and allied, escorts included)
   * @param escortsMoved warships moved there as escort
   */
  private record Safety(
      boolean safe, double enemyWin, double threshold, int warships, List<Unit> escortsMoved) {}

  /** Own warships that may join a fleet in {@code zone}, nearest then cheapest. */
  private List<Unit> escortCandidates(final Territory zone, final List<Unit> arriving) {
    final List<Unit> list = new ArrayList<>();
    for (final Territory t : map.getNeighbors(zone, 2, Matches.territoryIsWater())) {
      if (t.anyUnitsMatch(Matches.unitIsOwnedBy(player).and(OperazioniAnfibie::isTransport))) {
        continue; // they guard transports there
      }
      for (final Unit u :
          t.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(OperazioniAnfibie::isWarship)
                  .and(Matches.unitHasMovementLeft())
                  .and(Matches.unitIsCarrier().negate()))) {
        if (!held.contains(u) && !arriving.contains(u)) {
          list.add(u);
        }
      }
    }
    list.sort(
        Comparator.comparingInt((Unit u) -> map.getDistance(territory(u), zone))
            .thenComparingDouble(u -> TuvUtils.getTuv(List.of(u), proData.getUnitValueMap()))
            .thenComparing(u -> u.getType().getName()));
    return list;
  }

  private void holdIfSafe(
      final Strategia.Operazione op,
      final List<Unit> units,
      final Territory zone,
      final List<String> reasons,
      final String why) {
    final Safety safety = makeSafe(op, zone, units);
    if (safety.safe()) {
      held.addAll(units);
      safety.escortsMoved().forEach(held::add);
      held.addAll(warships(zone));
    } else {
      reasons.add(
          String.format(
              Locale.ROOT,
              "%s in %s ma la zona non e' sicura (win%% nemica %.0f%% > soglia %.0f%%): la"
                  + " flotta va dove Hard la ritiene piu' sicura",
              why,
              zone.getName(),
              safety.enemyWin(),
              safety.threshold()));
    }
  }

  private void findEnemyAttacks(
      final List<Strategia.Operazione> ops, final Map<Strategia.Operazione, List<Unit>> mine) {
    final Set<Territory> check = new HashSet<>();
    for (final Strategia.Operazione op : ops) {
      for (final String name : op.raccolta()) {
        Optional.ofNullable(map.getTerritoryOrNull(name)).ifPresent(check::add);
      }
      for (final Strategia.Porto p : op.porti()) {
        Optional.ofNullable(map.getTerritoryOrNull(p.zona())).ifPresent(check::add);
        Optional.ofNullable(map.getTerritoryOrNull(p.territorio())).ifPresent(check::add);
      }
      for (final Unit t : mine.getOrDefault(op, List.of())) {
        check.add(territory(t));
        check.addAll(map.getNeighbors(territory(t), 2, Matches.territoryIsWater()));
      }
    }
    enemy = new ProTerritoryManager(calc, proData);
    enemy.populateEnemyAttackOptions(List.of(), check);
  }

  private @Nullable ProTerritory enemyMax(final Territory t) {
    return enemy == null ? null : enemy.getEnemyAttackOptions().getMax(t);
  }

  // ------------------------------------------------------------------------------------- report

  private Rapporto report(
      final Strategia.Operazione op,
      final List<Unit> transports,
      final Map<String, Integer> landed,
      final int landedUnits,
      final List<String> moves,
      final List<String> reasons) {
    final boolean carrier = op.vettori().contains(player.getName());
    final List<Unit> fleet = new ArrayList<>();
    if (carrier) {
      fleet.addAll(transports.stream().filter(t -> !t.getOwner().isNull()).toList());
    } else {
      for (final Territory z : map.getTerritories()) {
        if (z.isWater()) {
          fleet.addAll(
              z.getMatches(
                  Matches.unitIsSeaTransportButNotCombatSeaTransport()
                      .and(u -> op.vettori().contains(u.getOwner().getName()))
                      .and(u -> distanceToOperation(op, z) <= 2)));
        }
      }
    }
    int aboard = 0;
    final Set<Territory> fleetZones = new LinkedHashSet<>();
    for (final Unit t : fleet) {
      final int c = cargo(op, t).size();
      aboard += c;
      if (c > 0) {
        fleetZones.add(territory(t));
      }
    }
    if (fleetZones.isEmpty()) {
      op.raccolta().stream()
          .map(map::getTerritoryOrNull)
          .filter(java.util.Objects::nonNull)
          .forEach(fleetZones::add);
    }
    int escort = 0;
    for (final Territory z : fleetZones) {
      escort += warships(z).size() + alliedWarships(z);
    }
    // estimate of a landing on the first enemy target from the loaded fleet at the gathering zone
    Optional<String> targetName = Optional.empty();
    double win = -1;
    final Optional<Territory> gatherWithCargo =
        fleetZones.stream().filter(z -> op.raccolta().contains(z.getName())).findFirst();
    if (gatherWithCargo.isPresent() && aboard > 0) {
      final Optional<Territory> target = firstEnemyTargetInReach(op, gatherWithCargo.get());
      if (target.isPresent()) {
        targetName = Optional.of(target.get().getName());
        win = landingEstimate(op, target.get(), fleet);
      }
    }
    final Stato stato;
    if (landedUnits > 0) {
      stato = Stato.SBARCO;
    } else if (!reasons.isEmpty()) {
      stato = Stato.BLOCCATA;
    } else if (aboard > 0) {
      stato = Stato.IN_ROTTA;
    } else {
      stato = Stato.PREPARAZIONE;
    }
    final String next =
        nextStep(op, stato, carrier, fleet, aboard, gatherWithCargo.isPresent(), targetName, win);
    return new Rapporto(
        op.nome(),
        player.getName(),
        stato,
        fleet.size(),
        aboard,
        escort,
        landedUnits,
        List.copyOf(landed.keySet()),
        next,
        String.join("; ", reasons),
        List.copyOf(moves),
        targetName,
        win);
  }

  private String nextStep(
      final Strategia.Operazione op,
      final Stato stato,
      final boolean carrier,
      final List<Unit> fleet,
      final int aboard,
      final boolean atGathering,
      final Optional<String> target,
      final double win) {
    if (!carrier && fleet.isEmpty()) {
      return "attendere trasporti di " + String.join("/", op.vettori()) + " al porto";
    }
    if (carrier && fleet.isEmpty()) {
      return "acquistare trasporti (obiettivo " + op.trasporti() + " per ondata)";
    }
    if (aboard == 0) {
      return "imbarcare truppe a "
          + String.join("/", op.porti().stream().map(Strategia.Porto::territorio).toList());
    }
    if (atGathering && target.isPresent()) {
      final double desired =
          map.getTerritoryOrNull(target.get()) == null
              ? 80
              : ProPlusTuning.desiredWinPercentage(proData, map.getTerritoryOrNull(target.get()));
      return String.format(
          Locale.ROOT,
          "sbarco su %s nel prossimo combattimento se la win%% arriva alla desiderata %.0f%%"
              + " (stima ora %.0f%%)",
          target.get(),
          desired,
          win);
    }
    if (atGathering) {
      return "sbarco su un bersaglio amico ("
          + String.join("/", op.bersagli())
          + ") o attesa di un bersaglio nemico raggiungibile";
    }
    return stato == Stato.BLOCCATA
        ? "attendere scorta o via libera"
        : "raggiungere " + String.join("/", op.raccolta());
  }

  /** Win% of a landing on {@code target} with the operation's troops near it (aboard or ashore). */
  private double landingEstimate(
      final Strategia.Operazione op, final Territory target, final List<Unit> fleet) {
    final List<Unit> attackers = new ArrayList<>();
    for (final Unit t : fleet) {
      attackers.addAll(cargo(op, t));
    }
    for (final Strategia.Porto p : op.porti()) {
      final Territory land = map.getTerritoryOrNull(p.territorio());
      if (land != null && isFriendlyLand(land) && map.getDistance(land, target) <= 3) {
        attackers.addAll(waiting(land, player));
      }
    }
    final List<Unit> defenders = target.getMatches(Matches.isUnitAllied(target.getOwner()));
    if (attackers.isEmpty()) {
      return 0;
    }
    return calc.calculateBattleResults(proData, target, attackers, defenders, Set.of())
        .getWinPercentage();
  }

  /**
   * Land units of the operation's nations that this turn's combat move unloaded from transports
   * (also allied ones): target name → units.
   */
  private Map<String, Integer> landedThisTurn(final Strategia.Operazione op) {
    final Map<String, Integer> landed = new TreeMap<>();
    if (!op.nazioni().contains(player.getName())) {
      return landed;
    }
    final Set<Unit> counted = new HashSet<>();
    for (final Territory z : map.getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit t : z.getMatches(Matches.unitIsSeaTransport())) {
        for (final Unit u : t.getUnloaded()) {
          if (u.isOwnedBy(player) && u.getWasUnloadedInCombatPhase() && counted.add(u)) {
            final Territory at = locate(u);
            if (at != null && op.bersagli().contains(at.getName())) {
              landed.merge(at.getName(), 1, Integer::sum);
            }
          }
        }
      }
    }
    return landed;
  }

  // ---------------------------------------------------------------------------------- purchase

  /** Category names of the strategy file the operations buy in. */
  static final String TRANSPORTS = "trasporti";

  static final String INFANTRY = "fanteria";
  static final String ESCORT = "cacciatorpediniere";

  /** Transports bought per turn for one operation at most. */
  static final int MAX_TRANSPORTS_PER_TURN = 2;

  /** Land units bought per turn for one operation at most. */
  static final int MAX_LAND_PER_TURN = 4;

  /**
   * One purchase an operation asks for.
   *
   * @param categoria category of the strategy file
   * @param numero units
   * @param motivo why (Italian text)
   */
  public record Acquisto(String operazione, String categoria, int numero, String motivo) {}

  /**
   * What the operations of {@code plan} whose transports are this nation's want bought this turn
   * (nothing when the plan's orders already have mandatory purchases of their own): transports
   * while the troops waiting at the ports are more than the free room of the transports (up to the
   * operation's transports per wave), land units while the room is more than the troops, one
   * destroyer while the warships at the ports and gathering zones are fewer than the operation's
   * minimum escort.
   */
  public static List<Acquisto> acquisti(final ProData proData, final PianoAttivo plan) {
    final List<Acquisto> list = new ArrayList<>();
    if (plan.getLogistica().map(l -> !l.obbligati().isEmpty()).orElse(false)) {
      return list; // the plan already says what to buy first (eg Sea Lion's transports and escort)
    }
    final GamePlayer player = proData.getPlayer();
    final OperazioniAnfibie o = new OperazioniAnfibie(proData, null, null);
    final List<Strategia.Operazione> carried =
        plan.getOperazioni().stream()
            .filter(op -> op.vettori().contains(player.getName()))
            .toList();
    if (carried.isEmpty()) {
      return list;
    }
    final Map<Strategia.Operazione, List<Unit>> transports = o.assignTransports(carried);
    for (final Strategia.Operazione op : carried) {
      final List<Unit> mine = transports.getOrDefault(op, List.of());
      int room = 0;
      int aboard = 0;
      for (final Unit t : mine) {
        room += TransportTracker.getAvailableCapacity(t) / 2;
        aboard += cargo(op, t).size();
      }
      int waiting = 0;
      for (final Strategia.Porto p : op.porti()) {
        final Territory land = o.map.getTerritoryOrNull(p.territorio());
        if (land == null || !o.isFriendlyLand(land)) {
          continue;
        }
        for (final String nation : op.nazioni()) {
          final GamePlayer n = o.data.getPlayerList().getPlayerId(nation);
          if (n != null) {
            waiting += waiting(land, n).size();
          }
        }
      }
      if (mine.size() < op.trasporti() && (waiting > room || mine.isEmpty())) {
        final int n =
            Math.min(
                MAX_TRANSPORTS_PER_TURN,
                Math.min(op.trasporti() - mine.size(), Math.max(1, (waiting - room + 1) / 2)));
        list.add(
            new Acquisto(
                op.nome(),
                TRANSPORTS,
                n,
                String.format(
                    Locale.ROOT,
                    "%d truppe in attesa ai porti, posto libero per %d sui %d trasporti"
                        + " (obiettivo %d)",
                    waiting,
                    room,
                    mine.size(),
                    op.trasporti())));
      } else if (op.nazioni().contains(player.getName()) && room > waiting) {
        list.add(
            new Acquisto(
                op.nome(),
                INFANTRY,
                Math.min(MAX_LAND_PER_TURN, room - waiting),
                String.format(
                    Locale.ROOT,
                    "posto libero per %d sui %d trasporti, %d truppe in attesa (%d a bordo)",
                    room,
                    mine.size(),
                    waiting,
                    aboard)));
      }
      int warships = 0;
      final Set<Territory> zones = new HashSet<>();
      op.raccolta().stream()
          .map(o.map::getTerritoryOrNull)
          .filter(Objects::nonNull)
          .forEach(zones::add);
      op.porti().stream()
          .map(p -> o.map.getTerritoryOrNull(p.zona()))
          .filter(Objects::nonNull)
          .forEach(zones::add);
      for (final Territory z : zones) {
        warships +=
            z.getUnitCollection()
                .countMatches(Matches.isUnitAllied(player).and(OperazioniAnfibie::isWarship));
      }
      if (warships < op.scortaMinima() && !mine.isEmpty()) {
        list.add(
            new Acquisto(
                op.nome(),
                ESCORT,
                1,
                String.format(
                    Locale.ROOT,
                    "scorta %d navi da guerra ai porti/raccolta, minima %d",
                    warships,
                    op.scortaMinima())));
      }
    }
    return list;
  }

  // ------------------------------------------------------------------------------------ helpers

  /**
   * My transports per operation: each to the operation whose zones are nearest (at most {@link
   * #MAX_DISTANCE} sea zones away).
   */
  private Map<Strategia.Operazione, List<Unit>> assignTransports(
      final List<Strategia.Operazione> ops) {
    final Map<Strategia.Operazione, List<Unit>> result = new LinkedHashMap<>();
    for (final Territory z : map.getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit t :
          z.getMatches(Matches.unitIsOwnedBy(player).and(OperazioniAnfibie::isTransport))) {
        where.put(t, z);
        Strategia.Operazione best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (final Strategia.Operazione op : ops) {
          if (!op.vettori().contains(player.getName())) {
            continue;
          }
          final int d = distanceToOperation(op, z);
          if (d <= MAX_DISTANCE && d < bestDistance) {
            best = op;
            bestDistance = d;
          }
        }
        if (best != null) {
          result.computeIfAbsent(best, k -> new ArrayList<>()).add(t);
        }
      }
    }
    return result;
  }

  /** Sea distance of {@code zone} from the operation's port and gathering zones. */
  int distanceToOperation(final Strategia.Operazione op, final Territory zone) {
    int best = Integer.MAX_VALUE;
    final List<String> zones = new ArrayList<>(op.raccolta());
    op.porti().forEach(p -> zones.add(p.zona()));
    for (final String name : zones) {
      final Territory z = map.getTerritoryOrNull(name);
      if (z == null) {
        continue;
      }
      final int d = z.equals(zone) ? 0 : map.getDistance(zone, z, Matches.territoryIsWater());
      if (d >= 0) {
        best = Math.min(best, d);
      }
    }
    return best;
  }

  private Optional<Strategia.Porto> portOf(final Strategia.Operazione op, final Territory zone) {
    return op.porti().stream().filter(p -> p.zona().equals(zone.getName())).findFirst();
  }

  /**
   * Ports an empty transport may go to, best first: where most units of the operation's nations
   * wait (nearer first on a tie); without any waiting, the ports with a factory of mine first.
   */
  private List<Strategia.Porto> portsToGo(final Strategia.Operazione op, final Territory zone) {
    final Map<Strategia.Porto, Integer> count = new HashMap<>();
    final Map<Strategia.Porto, Integer> distance = new HashMap<>();
    final List<Strategia.Porto> list = new ArrayList<>();
    for (final Strategia.Porto p : op.porti()) {
      final Territory land = map.getTerritoryOrNull(p.territorio());
      final Territory pz = map.getTerritoryOrNull(p.zona());
      if (land == null || pz == null || !isFriendlyLand(land)) {
        continue;
      }
      int n = 0;
      for (final String nation : op.nazioni()) {
        final GamePlayer owner = data.getPlayerList().getPlayerId(nation);
        if (owner != null) {
          n += waiting(land, owner).size();
        }
      }
      final int d = pz.equals(zone) ? 0 : map.getDistance(zone, pz, Matches.territoryIsWater());
      count.put(p, n);
      distance.put(p, d < 0 ? Integer.MAX_VALUE : d);
      list.add(p);
    }
    final boolean anyWaiting = count.values().stream().anyMatch(n -> n > 0);
    list.sort(
        Comparator.comparingInt((Strategia.Porto p) -> -count.get(p))
            .thenComparing(
                p ->
                    anyWaiting
                        || !ProMatches.territoryHasInfraFactoryAndIsOwnedLand(player)
                            .test(map.getTerritoryOrNull(p.territorio())))
            .thenComparingInt(distance::get));
    return list;
  }

  /** The first enemy target a transport in {@code zone} can land on next turn (2 sea moves). */
  private Optional<Territory> firstEnemyTargetInReach(
      final Strategia.Operazione op, final Territory zone) {
    final Set<Territory> reach = reach(zone);
    for (final String name : op.bersagli()) {
      final Territory t = map.getTerritoryOrNull(name);
      if (t == null || t.isWater() || !Matches.isTerritoryEnemy(player).test(t)) {
        continue;
      }
      for (final Territory z : map.getNeighbors(t, Matches.territoryIsWater())) {
        if (reach.contains(z)) {
          return Optional.of(t);
        }
      }
    }
    return Optional.empty();
  }

  /** Sea zones a transport in {@code zone} can unload from next turn (2 moves). */
  private Set<Territory> reach(final Territory zone) {
    final Set<Territory> reach =
        new HashSet<>(map.getNeighbors(zone, 2, Matches.territoryIsWater()));
    reach.add(zone);
    return reach;
  }

  /** Friendly targets, then friendly ports, next to {@code zone}. */
  private List<Territory> friendlyShores(final Strategia.Operazione op, final Territory zone) {
    final List<Territory> list = new ArrayList<>();
    final List<String> names = new ArrayList<>(op.bersagli());
    op.porti().forEach(p -> names.add(p.territorio()));
    for (final String name : names) {
      final Territory t = map.getTerritoryOrNull(name);
      if (t != null
          && !list.contains(t)
          && isFriendlyLand(t)
          && map.getNeighbors(zone).contains(t)) {
        list.add(t);
      }
    }
    return list;
  }

  private Optional<Territory> nearest(final Territory from, final List<String> names) {
    Territory best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (final String name : names) {
      final Territory t = map.getTerritoryOrNull(name);
      if (t == null) {
        continue;
      }
      final int d =
          map.getDistance(from, t, ProMatches.territoryCanMoveSeaUnitsThrough(player, false));
      if (d >= 0 && d < bestDistance) {
        best = t;
        bestDistance = d;
      }
    }
    return Optional.ofNullable(best);
  }

  private Optional<Route> seaRoute(
      final Territory from, final Territory to, final Collection<Unit> units) {
    if (from.equals(to)) {
      return Optional.empty();
    }
    final Predicate<Territory> canPass = ProMatches.territoryCanMoveSeaUnitsThrough(player, false);
    return map.getRouteForUnits(from, to, canPass, units, player);
  }

  private boolean move(
      final List<Unit> units, final Route route, final List<String> moves, final String what) {
    final Optional<String> error = moveDel.performMove(new MoveDescription(units, route));
    if (error.isPresent()) {
      ProLogger.warn("[Pro+] Amphibious operation: could not move " + units + ": " + error.get());
      return false;
    }
    units.forEach(u -> where.put(u, route.getEnd()));
    moves.add(
        what
            + ": "
            + unitsText(units)
            + " "
            + route.getStart().getName()
            + " -> "
            + route.getEnd().getName());
    return true;
  }

  private Territory territory(final Unit u) {
    final Territory t = where.get(u);
    if (t != null) {
      return t;
    }
    final Territory found = locate(u);
    if (found != null) {
      where.put(u, found);
      return found;
    }
    return proData.getUnitTerritory(u);
  }

  private @Nullable Territory locate(final Unit u) {
    for (final Territory t : map.getTerritories()) {
      if (t.getUnitCollection().contains(u)) {
        return t;
      }
    }
    return null;
  }

  /** Units of the operation's nations aboard {@code transport}. */
  private static List<Unit> cargo(final Strategia.Operazione op, final Unit transport) {
    return transport.getTransporting().stream()
        .filter(u -> op.nazioni().contains(u.getOwner().getName()))
        .toList();
  }

  private List<Unit> ownCargo(final Unit transport) {
    return transport.getTransporting().stream().filter(u -> u.isOwnedBy(player)).toList();
  }

  private static List<Unit> withCargo(final Unit transport) {
    final List<Unit> units = new ArrayList<>();
    units.add(transport);
    units.addAll(transport.getTransporting());
    return units;
  }

  private List<Unit> warships(final Territory zone) {
    return zone.getMatches(Matches.unitIsOwnedBy(player).and(OperazioniAnfibie::isWarship));
  }

  private int alliedWarships(final Territory zone) {
    return zone.getUnitCollection()
        .countMatches(
            Matches.isUnitAllied(player)
                .and(Matches.unitIsOwnedBy(player).negate())
                .and(OperazioniAnfibie::isWarship));
  }

  /** A transport that is not also a warship (combat transports count as warships). */
  static boolean isTransport(final Unit u) {
    return Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u);
  }

  static boolean isWarship(final Unit u) {
    return Matches.unitIsSea().test(u) && !isTransport(u);
  }

  private boolean isFriendlyLand(final Territory t) {
    return !t.isWater() && Matches.isTerritoryAllied(player).test(t);
  }

  private boolean isMyCapital(final Territory t) {
    return t.isOwnedBy(player)
        && TerritoryAttachment.get(t)
            .flatMap(TerritoryAttachment::getCapital)
            .map(c -> c.equals(player.getName()))
            .orElse(false);
  }

  /** Eg "2 infantry, 1 armour". */
  static String unitsText(final Collection<Unit> units) {
    final Map<String, Integer> counts = new TreeMap<>();
    units.forEach(u -> counts.merge(u.getType().getName(), 1, Integer::sum));
    final List<String> parts = new ArrayList<>();
    counts.forEach((type, n) -> parts.add(n + " " + type));
    return String.join(", ", parts);
  }
}
