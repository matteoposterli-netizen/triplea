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
      double winStimata,
      String flottaCombinata,
      String testaDiPonte,
      Optional<TestaDiPonte.Valutazione> valutazione,
      Optional<Accumulo> accumulo) {}

  /**
   * Step OPERAZIONI ANFIBIE-BIS: the nation moving first in a combined operation waits until the
   * landing on the pair's target reaches the desired win% of its effective level.
   *
   * @param bersaglio the pair's target
   * @param porto the gathering port (eg United Kingdom)
   * @param unitaPorto my land units that can board there or are aboard at the gathering zones
   * @param imbarcabili those that fit my transports in reach of the gathering zones
   * @param trasporti those transports
   * @param win win% of a landing with {@code imbarcabili} on the target's defenders now
   * @param soglia the desired win% of the target's effective level
   * @param stato the stall state after this round
   */
  public record Accumulo(
      String bersaglio,
      String porto,
      int unitaPorto,
      int imbarcabili,
      int trasporti,
      double win,
      double soglia,
      boolean pronto,
      games.strategy.triplea.ai.proplus.strategia.StalloOperazioni.Stato stato) {

    /** Eg "accumulo (Americans 10 unita' in United Kingdom, ...)". */
    public String testo(final String operazione, final String nazione) {
      final String head =
          String.format(
              Locale.ROOT,
              "%s: %s (%s %d unita' in %s, %d imbarcabili su %d trasporti, win%% %s %.0f%% /"
                  + " soglia %.0f%%)",
              operazione,
              pronto ? "pronto" : "accumulo",
              nazione,
              unitaPorto,
              porto,
              imbarcabili,
              trasporti,
              bersaglio,
              win,
              soglia);
      return stato.stallo()
          ? head
              + String.format(
                  Locale.ROOT,
                  " - %s IN STALLO: %d round dal R%d senza raggiungere la soglia",
                  operazione,
                  stato.roundSenza(),
                  games.strategy.triplea.ai.proplus.strategia.StalloOperazioni.DA_ROUND)
          : head;
    }
  }

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

  /** Step OPERAZIONI ANFIBIE-BIS: my operations paired with an ally's sharing a gathering zone. */
  private List<TestaDiPonte.Coppia> coppie = List.of();

  /** What the bridgehead / reinforcement step did, per operation name (diary). */
  private final Map<String, String> bridgeheadText = new HashMap<>();

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
    o.coppie = TestaDiPonte.coppie(proData.getData(), plan, proData.getPlayer());
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
      for (final TestaDiPonte.Coppia c : coppie) {
        if (c.mia().equals(op) && !c.primaIo()) {
          reinforceBridgehead(op, c, transports, moves);
          joinCombinedFleet(op, c, moves, reasons);
        }
      }
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
      if (land != null && isGatheringPort(op, zone, land)) {
        // step OPERAZIONI ANFIBIE-BIS: the troops wait ashore in the port, the combat move of the
        // Pro AI loads and lands them in one go (it can't use transports loaded a turn earlier).
        // The transports their number needs stay; the others fetch troops from the other ports.
        final long keptHere =
            held.stream().filter(u -> isTransport(u) && zone.equals(territory(u))).count();
        if (waiting(land, player).size() > 2 * keptHere || !troopsWaitElsewhere(op, land)) {
          holdIfSafe(
              op,
              List.of(transport),
              zone,
              reasons,
              "trasporto vuoto alla raccolta (truppe in attesa a " + land.getName() + ")");
          return;
        }
        shuttle(op, transport, zone, moves, reasons, zone);
        return;
      }
      final List<Unit> loaded = land == null ? List.of() : load(op, transport, land, zone, moves);
      if (!loaded.isEmpty()) {
        playLoaded(op, transport, zone, moves, reasons);
        return;
      }
    }
    shuttle(op, transport, zone, moves, reasons, null);
  }

  /**
   * Whether {@code land} is a friendly port of {@code op} on {@code zone}, a gathering zone with an
   * enemy target in reach: the troops wait there ashore (step OPERAZIONI ANFIBIE-BIS).
   */
  private boolean isGatheringPort(
      final Strategia.Operazione op, final Territory zone, final Territory land) {
    return op.raccolta().contains(zone.getName())
        && op.porti().stream()
            .anyMatch(p -> p.zona().equals(zone.getName()) && p.territorio().equals(land.getName()))
        && isFriendlyLand(land)
        && firstEnemyTargetInReach(op, zone).isPresent();
  }

  /** Whether units of the operation's nations wait in a port other than {@code here}. */
  private boolean troopsWaitElsewhere(final Strategia.Operazione op, final Territory here) {
    for (final Strategia.Porto p : op.porti()) {
      final Territory land = map.getTerritoryOrNull(p.territorio());
      if (land == null || land.equals(here) || !isFriendlyLand(land)) {
        continue;
      }
      for (final String nation : op.nazioni()) {
        final GamePlayer owner = data.getPlayerList().getPlayerId(nation);
        if (owner != null && !waiting(land, owner).isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Arrived loaded at a gathering zone that is also a port of the operation (eg Overlord at 110 Sea
   * Zone, port United Kingdom): the own troops go ashore in the port at once, so the next combat
   * move of the Pro AI loads and lands them in one go.
   */
  private void unloadAtGatheringPort(
      final Strategia.Operazione op,
      final Unit transport,
      final Territory zone,
      final List<String> moves) {
    final List<Unit> mine = ownCargo(transport);
    if (mine.isEmpty() || !transport.getUnloaded().isEmpty()) {
      return;
    }
    for (final Strategia.Porto p : op.porti()) {
      final Territory land = map.getTerritoryOrNull(p.territorio());
      if (land == null || !p.zona().equals(zone.getName()) || !isGatheringPort(op, zone, land)) {
        continue;
      }
      if (move(mine, new Route(zone, land), moves, "a terra nel porto di raccolta")) {
        held.add(transport);
        held.addAll(mine);
      }
      return;
    }
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS, the nation moving second: its transports put their troops ashore
   * on the bridgehead the partner took (a common target owned by the partner, with its land units),
   * loading first at a port on the way when empty.
   */
  private void reinforceBridgehead(
      final Strategia.Operazione op,
      final TestaDiPonte.Coppia c,
      final List<Unit> transports,
      final List<String> moves) {
    Territory head = null;
    for (final String name : c.bersagliComuni()) {
      final Territory t = map.getTerritoryOrNull(name);
      if (t != null
          && !t.isWater()
          && t.isOwnedBy(c.alleato())
          && t.anyUnitsMatch(Matches.unitIsOwnedBy(c.alleato()).and(Matches.unitIsLand()))) {
        head = t;
        break;
      }
    }
    if (head == null) {
      return;
    }
    final Set<Territory> adjacent = map.getNeighbors(head, Matches.territoryIsWater());
    final List<Unit> landed = new ArrayList<>();
    for (final Unit t : transports) {
      if (!t.getUnloaded().isEmpty() || t.getMovementLeft().signum() < 0) {
        continue;
      }
      if (ownCargo(t).isEmpty()) {
        loadForBridgehead(op, t, adjacent, moves);
      }
      final List<Unit> mine = ownCargo(t);
      if (mine.isEmpty()) {
        continue;
      }
      final Territory from = territory(t);
      Territory unloadZone = adjacent.contains(from) ? from : null;
      if (unloadZone == null) {
        Route best = null;
        for (final Territory a : adjacent) {
          final Optional<Route> r = seaRoute(from, a, withCargo(t));
          if (r.isPresent()
              && r.get().numberOfSteps() <= t.getMovementLeft().intValue()
              && (best == null || r.get().numberOfSteps() < best.numberOfSteps())) {
            best = r.get();
          }
        }
        if (best == null || !move(withCargo(t), best, moves, "verso la testa di ponte")) {
          continue;
        }
        unloadZone = best.getEnd();
      }
      if (move(mine, new Route(unloadZone, head), moves, "rinforzo della testa di ponte")) {
        held.add(t);
        held.addAll(mine);
        landed.addAll(mine);
      }
    }
    bridgeheadText.put(
        op.nome(),
        landed.isEmpty()
            ? String.format(
                Locale.ROOT,
                "testa di ponte di %s su %s: nessun rinforzo di %s (nessun trasporto con truppe in"
                    + " raggio)",
                c.alleato().getName(),
                head.getName(),
                player.getName())
            : String.format(
                Locale.ROOT,
                "testa di ponte di %s su %s, rinforzo di %s: %d unita' (%s)",
                c.alleato().getName(),
                head.getName(),
                player.getName(),
                landed.size(),
                unitsText(landed)));
  }

  /** An empty transport: to a port in reach of the bridgehead and load the troops waiting there. */
  private void loadForBridgehead(
      final Strategia.Operazione op,
      final Unit transport,
      final Set<Territory> adjacent,
      final List<String> moves) {
    final Territory zone = territory(transport);
    final int movesLeft = transport.getMovementLeft().intValue();
    for (final Strategia.Porto p : op.porti()) {
      final Territory portZone = map.getTerritoryOrNull(p.zona());
      final Territory land = map.getTerritoryOrNull(p.territorio());
      if (portZone == null
          || land == null
          || !isFriendlyLand(land)
          || waiting(land, player).stream().allMatch(held::contains)) {
        continue;
      }
      final int toHead = TestaDiPonte.distance(map, portZone, adjacent);
      if (portZone.equals(zone)) {
        if (toHead <= movesLeft) {
          load(op, transport, land, zone, moves);
          return;
        }
        continue;
      }
      final Optional<Route> r = seaRoute(zone, portZone, List.of(transport));
      if (r.isPresent()
          && toHead != Integer.MAX_VALUE
          && r.get().numberOfSteps() + toHead <= movesLeft
          && move(List.of(transport), r.get(), moves, "al porto per il rinforzo")) {
        load(op, transport, land, portZone, moves);
        return;
      }
    }
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS, the nation moving second: its warships join the partner's fleet at
   * a shared gathering zone until the combined fleet is safe.
   */
  private void joinCombinedFleet(
      final Strategia.Operazione op,
      final TestaDiPonte.Coppia c,
      final List<String> moves,
      final List<String> reasons) {
    for (final String name : c.raccoltaComune()) {
      final Territory zone = map.getTerritoryOrNull(name);
      if (zone == null
          || !zone.anyUnitsMatch(
              Matches.unitIsOwnedBy(c.alleato()).and(OperazioniAnfibie::isTransport))) {
        continue;
      }
      final Safety safety = makeSafe(op, zone, List.of());
      if (safety.safe()) {
        held.addAll(safety.escortsMoved());
        held.addAll(warships(zone));
        if (!safety.escortsMoved().isEmpty()) {
          moves.add(
              "scorta alla flotta combinata in "
                  + zone.getName()
                  + ": "
                  + unitsText(safety.escortsMoved()));
        }
      } else {
        reasons.add(
            String.format(
                Locale.ROOT,
                "flotta combinata in %s non sicura: win%% nemica %.0f%% > soglia %.0f%% (navi %d)",
                zone.getName(),
                safety.enemyWin(),
                safety.threshold(),
                safety.warships()));
      }
    }
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
        unloadAtGatheringPort(op, transport, zone, moves);
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
            unloadAtGatheringPort(op, transport, end, moves);
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
      final List<String> reasons,
      final @Nullable Territory excluded) {
    final int movesLeft = transport.getMovementLeft().intValue();
    final List<Strategia.Porto> ports = portsToGo(op, zone);
    if (ports.isEmpty() || movesLeft <= 0) {
      return;
    }
    final List<String> unsafe = new ArrayList<>();
    for (final Strategia.Porto target : ports) {
      final Territory portZone = map.getTerritoryOrNull(target.zona());
      if (portZone == null || portZone.equals(excluded)) {
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
  static List<Unit> waitingFor(final Territory land, final GamePlayer owner) {
    return waiting(land, owner);
  }

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
            true,
            safety.enemyWin(),
            safety.threshold(),
            safety.warships(),
            List.copyOf(moved),
            safety.arriving());
      }
    }
    return safety;
  }

  private Safety safety(
      final Strategia.Operazione op, final Territory zone, final Collection<Unit> escorts) {
    // step OPERAZIONI ANFIBIE-BIS: the risk the zone's level accepts (Equilibrato: 40%)
    final double threshold =
        100 - ProPlusTuning.riskLevel(proData, zone).minimumWinPercentage(lowLuck);
    final List<Unit> defenders =
        new ArrayList<>(zone.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotLand())));
    defenders.addAll(escorts);
    final List<Unit> arriving = combinedArrivals(zone);
    defenders.addAll(arriving);
    final int warships = (int) defenders.stream().filter(OperazioniAnfibie::isWarship).count();
    final ProTerritory max = enemyMax(zone);
    if (max == null || max.getMaxUnits().isEmpty()) {
      return new Safety(true, 0, threshold, warships, List.of(), arriving.size());
    }
    final double win =
        defenders.stream().noneMatch(OperazioniAnfibie::isWarship)
            ? 100
            : calc.calculateBattleResults(
                    proData, zone, max.getMaxUnits(), defenders, max.getMaxBombardUnits())
                .getWinPercentage();
    final boolean enoughEscort = warships >= Math.min(op.scortaMinima(), 1) || win <= 0;
    return new Safety(
        win <= threshold && enoughEscort, win, threshold, warships, List.of(), arriving.size());
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS: warships of the partner of a combined operation that can join
   * {@code zone} (a shared gathering zone) in the partner's turn later this round: within 2 sea
   * zones and their movement, not carriers, not from zones where the partner's transports are. None
   * when I move after the partner (its ships are already where they are).
   */
  private List<Unit> combinedArrivals(final Territory zone) {
    final List<Unit> list = new ArrayList<>();
    for (final TestaDiPonte.Coppia c : coppie) {
      if (!c.primaIo() || !c.raccoltaComune().contains(zone.getName())) {
        continue;
      }
      final GamePlayer partner = c.alleato();
      for (final Territory t : map.getNeighbors(zone, 2, Matches.territoryIsWater())) {
        if (t.equals(zone)
            || t.anyUnitsMatch(
                Matches.unitIsOwnedBy(partner).and(OperazioniAnfibie::isTransport))) {
          continue;
        }
        final int d = map.getDistance(t, zone, Matches.territoryIsWater());
        for (final Unit u :
            t.getMatches(
                Matches.unitIsOwnedBy(partner)
                    .and(OperazioniAnfibie::isWarship)
                    .and(Matches.unitIsCarrier().negate()))) {
          if (d >= 0 && u.getUnitAttachment().getMovement(partner) >= d && !list.contains(u)) {
            list.add(u);
          }
        }
      }
    }
    return list;
  }

  /**
   * @param enemyWin win% of the enemy's best attack on the zone
   * @param threshold highest enemy win% accepted
   * @param warships warships defending the zone (own and allied, escorts included)
   * @param escortsMoved warships moved there as escort
   */
  private record Safety(
      boolean safe,
      double enemyWin,
      double threshold,
      int warships,
      List<Unit> escortsMoved,
      int arriving) {}

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
      for (final String name : op.bersagli()) {
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
          .filter(Objects::nonNull)
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
    final Optional<TestaDiPonte.Valutazione> estimate = bridgeheadEstimate(op, landed);
    final List<String> bridgehead = new ArrayList<>();
    Optional.ofNullable(bridgeheadText.get(op.nome())).ifPresent(bridgehead::add);
    estimate.ifPresent(
        v ->
            bridgehead.add(
                String.format(
                    Locale.ROOT,
                    "testa di ponte di %s su %s, rinforzo di %s nel suo turno: %s",
                    v.primo(),
                    v.territorio(),
                    v.secondo(),
                    v.testo())));
    combatEstimates(op, landed).forEach(bridgehead::add);
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
        win,
        combinedFleetText(op),
        String.join("; ", bridgehead),
        estimate,
        accumulo(op, landed));
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS, the nation moving first: whether the landing on the pair's target
   * is ready (win% of the land units at the gathering port or aboard there, as many as my
   * transports in reach carry, against the target's defenders now, at least the desired win% of the
   * target's effective level), noted for the stall rule ({@link
   * games.strategy.triplea.ai.proplus.strategia.StalloOperazioni}). A landing on a common target
   * this turn counts as ready.
   */
  private Optional<Accumulo> accumulo(
      final Strategia.Operazione op, final Map<String, Integer> landed) {
    if (calc == null) {
      return Optional.empty();
    }
    for (final TestaDiPonte.Coppia c : coppie) {
      if (!c.mia().equals(op) || !c.primaIo()) {
        continue;
      }
      final Optional<Territory> target = TestaDiPonte.bersaglioScelto(data, c, player);
      final List<Territory> zones =
          c.raccoltaComune().stream()
              .map(map::getTerritoryOrNull)
              .filter(Objects::nonNull)
              .toList();
      final Territory port =
          op.porti().stream()
              .filter(p -> c.raccoltaComune().contains(p.zona()))
              .map(p -> map.getTerritoryOrNull(p.territorio()))
              .filter(Objects::nonNull)
              .findFirst()
              .orElse(null);
      if (target.isEmpty() || zones.isEmpty() || port == null) {
        return Optional.empty();
      }
      final boolean landedNow = c.bersagliComuni().stream().anyMatch(landed::containsKey);
      // troops: in the port and aboard my transports at the gathering zones
      final List<Unit> troops = new ArrayList<>(waiting(port, player));
      final List<Unit> transports = new ArrayList<>();
      for (final Territory z : map.getTerritories()) {
        if (!z.isWater() || TestaDiPonte.distance(map, z, zones) > 2) {
          continue;
        }
        for (final Unit t :
            z.getMatches(Matches.unitIsOwnedBy(player).and(OperazioniAnfibie::isTransport))) {
          transports.add(t);
          if (zones.contains(z)) {
            troops.addAll(ownCargo(t));
          }
        }
      }
      troops.sort(
          Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
              .thenComparing(u -> u.getType().getName()));
      final List<Unit> fit = new ArrayList<>();
      final List<Unit> left = new ArrayList<>(troops);
      for (final Unit t : transports) {
        int room = t.getUnitAttachment().getTransportCapacity();
        for (final Unit u : new ArrayList<>(left)) {
          final int cost = u.getUnitAttachment().getTransportCost();
          if (cost > 0 && cost <= room) {
            fit.add(u);
            left.remove(u);
            room -= cost;
          }
        }
      }
      final Territory t = target.get();
      final List<Unit> defenders = t.getMatches(Matches.enemyUnit(player));
      final double win =
          fit.isEmpty()
              ? 0
              : calc.calculateBattleResults(proData, t, fit, defenders, Set.of())
                  .getWinPercentage();
      final double threshold = ProPlusTuning.riskLevel(proData, t).desiredWinPercentage(lowLuck);
      final boolean ready = landedNow || win >= threshold;
      final var state =
          games.strategy.triplea.ai.proplus.strategia.StalloOperazioni.aggiorna(
              data.getProperties(), op.nome(), data.getSequence().getRound(), ready);
      return Optional.of(
          new Accumulo(
              t.getName(),
              port.getName(),
              troops.size(),
              fit.size(),
              transports.size(),
              win,
              threshold,
              ready,
              state));
    }
    return Optional.empty();
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS: the combined fleet at the shared gathering zones, eg "110 Sea
   * Zone: Americans 2 navi, British 3 (+1 in arrivo nel turno di British)"; empty without a pair.
   */
  private String combinedFleetText(final Strategia.Operazione op) {
    final List<String> parts = new ArrayList<>();
    for (final TestaDiPonte.Coppia c : coppie) {
      if (!c.mia().equals(op)) {
        continue;
      }
      for (final String name : c.raccoltaComune()) {
        final Territory zone = map.getTerritoryOrNull(name);
        if (zone == null) {
          continue;
        }
        final Map<String, Integer> ships = new TreeMap<>();
        ships.put(player.getName(), 0);
        ships.put(c.alleato().getName(), 0);
        zone.getMatches(Matches.isUnitAllied(player).and(OperazioniAnfibie::isWarship))
            .forEach(u -> ships.merge(u.getOwner().getName(), 1, Integer::sum));
        final int arriving = c.primaIo() ? combinedArrivals(zone).size() : 0;
        final List<String> byNation = new ArrayList<>();
        ships.forEach((n, k) -> byNation.add(n + " " + k));
        parts.add(
            zone.getName()
                + ": navi "
                + String.join(", ", byNation)
                + (arriving > 0
                    ? " (+" + arriving + " in arrivo nel turno di " + c.alleato().getName() + ")"
                    : ""));
      }
    }
    return String.join("; ", parts);
  }

  /**
   * The bridgehead rule on a common target I landed on in this turn's combat move, with the units
   * that survived and the partner's reinforcements (the nation moving first only).
   */
  private Optional<TestaDiPonte.Valutazione> bridgeheadEstimate(
      final Strategia.Operazione op, final Map<String, Integer> landed) {
    if (calc == null) {
      return Optional.empty();
    }
    for (final TestaDiPonte.Coppia c : coppie) {
      if (!c.mia().equals(op) || !c.primaIo()) {
        continue;
      }
      for (final String name : c.bersagliComuni()) {
        final Territory t = map.getTerritoryOrNull(name);
        if (t == null || !landed.containsKey(name) || !t.isOwnedBy(player)) {
          continue;
        }
        final List<Unit> holders =
            t.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsLand()));
        final ProTerritory max = enemyMax(t);
        final List<Unit> enemyUnits = max == null ? List.of() : new ArrayList<>(max.getMaxUnits());
        final List<Unit> bombard =
            max == null ? List.of() : new ArrayList<>(max.getMaxBombardUnits());
        final double without =
            enemyUnits.isEmpty()
                ? 0
                : calc.calculateBattleResults(proData, t, enemyUnits, holders, bombard)
                    .getWinPercentage();
        final double win =
            proData.getTuning() instanceof ProPlusTuning tuning
                ? Optional.ofNullable(
                        tuning.bridgeheads(player, data.getSequence().getRound()).get(t.getName()))
                    .map(TestaDiPonte.Valutazione::winSbarco)
                    .orElse(Double.NaN)
                : Double.NaN;
        return Optional.of(
            TestaDiPonte.valuta(
                proData,
                calc,
                t,
                enemyUnits,
                bombard,
                win,
                without,
                holders,
                c,
                TestaDiPonte.rinforzi(proData, calc, t, c)));
      }
    }
    return Optional.empty();
  }

  /** The combat move's bridgehead estimates on common targets not landed on (dropped ones). */
  private List<String> combatEstimates(
      final Strategia.Operazione op, final Map<String, Integer> landed) {
    final List<String> list = new ArrayList<>();
    if (!(proData.getTuning() instanceof ProPlusTuning tuning)) {
      return list;
    }
    final Map<String, TestaDiPonte.Valutazione> estimates =
        tuning.bridgeheads(player, data.getSequence().getRound());
    for (final TestaDiPonte.Coppia c : coppie) {
      if (!c.mia().equals(op) || !c.primaIo()) {
        continue;
      }
      for (final String name : c.bersagliComuni()) {
        final TestaDiPonte.Valutazione v = estimates.get(name);
        if (v != null && !landed.containsKey(name)) {
          list.add(
              "testa di ponte su "
                  + name
                  + (v.tenuta() ? " ammessa ma non sbarcata: " : " scartata: ")
                  + v.testo());
        }
      }
    }
    return list;
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
        final String why =
            String.format(
                Locale.ROOT,
                "posto libero per %d sui %d trasporti, %d truppe in attesa (%d a bordo)",
                room,
                mine.size(),
                waiting,
                aboard);
        landShares(plan, Math.min(MAX_LAND_PER_TURN, room - waiting))
            .forEach((category, n) -> list.add(new Acquisto(op.nome(), category, n, why)));
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

  /**
   * Land categories the operations buy, in the proportions of the plan's mix (step ANFIBIE-BIS).
   */
  static final List<String> LAND_CATEGORIES = List.of(INFANTRY, "artiglieria", "carri_mech");

  /**
   * {@code n} land units split among {@link #LAND_CATEGORIES} in the proportions of the plan's
   * purchase mix (largest remainder; all infantry without a mix of these categories).
   */
  public static Map<String, Integer> landShares(final PianoAttivo plan, final int n) {
    final Map<String, Double> mix =
        plan.getLogistica().map(Strategia.Logistica::mix).orElse(Map.of());
    final Map<String, Double> weights = new LinkedHashMap<>();
    LAND_CATEGORIES.forEach(
        c -> {
          final double w = mix.getOrDefault(c, 0.0);
          if (w > 0) {
            weights.put(c, w);
          }
        });
    final Map<String, Integer> result = new LinkedHashMap<>();
    if (weights.isEmpty() || n <= 0) {
      if (n > 0) {
        result.put(INFANTRY, n);
      }
      return result;
    }
    final double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
    final Map<String, Double> remainders = new LinkedHashMap<>();
    int given = 0;
    for (final Map.Entry<String, Double> e : weights.entrySet()) {
      final double exact = n * e.getValue() / total;
      final int whole = (int) Math.floor(exact);
      result.put(e.getKey(), whole);
      remainders.put(e.getKey(), exact - whole);
      given += whole;
    }
    final List<String> byRemainder = new ArrayList<>(remainders.keySet());
    byRemainder.sort(Comparator.comparingDouble((String c) -> -remainders.get(c)));
    for (int i = 0; given < n; i++, given++) {
      result.merge(byRemainder.get(i % byRemainder.size()), 1, Integer::sum);
    }
    result.values().removeIf(k -> k == 0);
    return result;
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
