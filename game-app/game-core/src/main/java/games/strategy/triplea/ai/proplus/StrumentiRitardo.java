package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import games.strategy.triplea.ai.proplus.strategia.Tattiche;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.util.TuvCostsCalculator;
import games.strategy.triplea.util.TuvUtils;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step 3d-2: the delay tools of the non-combat move (Pro+ only), played before the Pro AI's own
 * non-combat move, which then leaves their units in place ({@code heldUnits}). Each tool runs only
 * where its switch is on ({@link Tattiche}, decided by the general staff): this class executes and
 * judges nothing beyond the fixed limits of the data file ({@link Strategia.Ritardo}).
 *
 * <ul>
 *   <li><b>Blocco terrestre</b>: own land territories next to enemy mobile units (land units with
 *       2+ moves: tanks, mechanized infantry) and one step closer to one of my key territories
 *       (capital, factory, victory city) within {@code distanza_percorso} keep or receive 1
 *       infantry, so the enemy can't blitz through them. Empty territories first. At most {@code
 *       max_blocchi} per turn; an infantry is taken from a territory under real threat only if the
 *       enemy's best attack there still wins at most {@code 100 - minimum} of the effective level
 *       without it (the territory keeps its defense).
 *   <li><b>Ritirata combattuta</b>: the main stack (most own land TUV) of each front where the tool
 *       is on doesn't stay where the enemy's best attack wins more than {@code 100 - minimum} of
 *       the effective level: it falls back towards the nearest other own key territory, leaving the
 *       blocks (and never leaving my capital). The combat-move part (only isolated enemy stacks are
 *       counter-attacked) is in {@code ProPlusTuning.dropsLandAttack}.
 *   <li><b>Blocco navale</b>: at most {@code max_navi_blocco} own submarines or destroyers go to
 *       (or stay in) the passage zones ({@code zone_di_passaggio}) of the most probable enemy plan
 *       read above {@code soglia_lettura_blocco}.
 * </ul>
 */
public final class StrumentiRitardo {
  /** Game property prefix of the record of the tools' units of a nation (JSON, JDK strings). */
  public static final String PROPERTY_PREFIX = "Pro+ ritardo: ";

  /** Name of the land unit used for blocks ("solo fanteria"). */
  static final String INFANTRY = "infantry";

  /** One action of a tool: where, which units, and why the tool is on. */
  public record Azione(
      String strumento,
      String territorio,
      List<Unit> unita,
      String modo,
      String perche,
      String dettaglio) {
    /** Eg "Ritardo: blocco_terrestre in Karelia (...)". */
    public String testo() {
      return String.format(
          Locale.ROOT,
          "Ritardo: %s in %s - %s %s%s (perche' attivo: %s)",
          strumento,
          territorio,
          modo,
          OperazioniAnfibie.unitsText(unita),
          dettaglio.isEmpty() ? "" : "; " + dettaglio,
          perche);
    }
  }

  /** The units the tools moved or keep in place, their actions and notes (why nothing). */
  public record Esito(Set<Unit> tenute, List<Azione> azioni, List<String> note) {
    static final Esito NESSUNO = new Esito(Set.of(), List.of(), List.of());

    public long conta(final String tool) {
      return azioni.stream().filter(a -> a.strumento().equals(tool)).count();
    }
  }

  /** Mode of a tool unit that was already where the tool wants it. */
  static final String LASCIATA = "lasciata";

  private final ProData proData;
  private final ProOddsCalculator calc;
  private final IMoveDelegate moveDel;
  private final PianoAttivo plan;
  private final GameData data;
  private final GamePlayer player;
  private final GameMap map;
  private final Strategia.Ritardo limits;
  private final Set<Unit> held = new LinkedHashSet<>();
  private final List<Azione> actions = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();
  private @Nullable ProTerritoryManager enemy;

  private StrumentiRitardo(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final PianoAttivo plan,
      final Set<Unit> alreadyHeld) {
    this.proData = proData;
    this.calc = calc;
    this.moveDel = moveDel;
    this.plan = plan;
    this.data = proData.getData();
    this.player = proData.getPlayer();
    this.map = data.getMap();
    this.limits = plan.getStrategia().getParametri().ritardo();
    held.addAll(alreadyHeld);
  }

  /**
   * Plays the tools that are on for the plan's nation; {@code alreadyHeld} are units the amphibious
   * operations already use (never touched here).
   */
  public static Esito esegui(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final PianoAttivo plan,
      final Set<Unit> alreadyHeld) {
    final boolean land = plan.tattica(Tattiche.BLOCCO_TERRESTRE, (String) null).isPresent();
    final boolean retreat = plan.tattica(Tattiche.RITIRATA, (String) null).isPresent();
    final boolean naval = plan.tattica(Tattiche.BLOCCO_NAVALE, (String) null).isPresent();
    if (!land && !retreat && !naval) {
      return Esito.NESSUNO;
    }
    final StrumentiRitardo tools = new StrumentiRitardo(proData, calc, moveDel, plan, alreadyHeld);
    if (land || retreat) {
      tools.findEnemyAttacks();
    }
    if (land) {
      tools.guard(tools::landBlocks, Tattiche.BLOCCO_TERRESTRE);
    }
    if (retreat) {
      tools.guard(tools::fightingRetreat, Tattiche.RITIRATA);
    }
    if (naval) {
      tools.guard(tools::navalBlocks, Tattiche.BLOCCO_NAVALE);
    }
    final Set<Unit> mine = new LinkedHashSet<>(tools.held);
    mine.removeAll(alreadyHeld);
    return new Esito(Set.copyOf(mine), List.copyOf(tools.actions), List.copyOf(tools.notes));
  }

  private void guard(final Runnable tool, final String name) {
    try {
      tool.run();
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Delay tool " + name + " failed: " + e);
    }
  }

  private void findEnemyAttacks() {
    final List<Territory> own =
        map.getTerritories().stream().filter(t -> !t.isWater() && t.isOwnedBy(player)).toList();
    enemy = new ProTerritoryManager(calc, proData);
    enemy.populateEnemyAttackOptions(List.of(), own);
  }

  private @Nullable ProTerritory enemyMax(final Territory t) {
    return enemy == null ? null : enemy.getEnemyAttackOptions().getMax(t);
  }

  /** Real threat (step 3c): some real enemy can attack {@code t} before my next turn. */
  private boolean threatened(final Territory t) {
    final ProTerritory max = enemyMax(t);
    return max != null && !max.getMaxUnits().isEmpty();
  }

  // ------------------------------------------------------------------------------ land blocks

  /** An enemy mobile stack, a key territory of mine it heads to, and the territory in between. */
  private record Passaggio(Territory nemico, Territory chiave, Territory blocco, int distanza) {}

  private void landBlocks() {
    final List<Territory> keys = keyTerritories();
    final Predicate<Territory> passable = t -> !t.isWater() && !t.getOwner().isNull();
    final Set<GamePlayer> enemies = realEnemies();
    final List<Passaggio> found = new ArrayList<>();
    for (final Territory e : map.getTerritories()) {
      if (e.isWater()
          || !e.anyUnitsMatch(
              u -> enemies.contains(u.getOwner()) && isMobileLand(u) && !u.isOwnedBy(player))) {
        continue;
      }
      for (final Territory k : keys) {
        final int d = map.getDistance(e, k, passable);
        if (d < 2 || d > limits.distanzaPercorso()) {
          continue;
        }
        for (final Territory t : map.getNeighbors(e, passable)) {
          if (t.equals(k)
              || !t.isOwnedBy(player)
              || plan.tattica(Tattiche.BLOCCO_TERRESTRE, t).isEmpty()
              || map.getDistance(t, k, passable) != d - 1) {
            continue;
          }
          found.add(new Passaggio(e, k, t, d));
        }
      }
    }
    found.sort(
        Comparator.comparing((Passaggio p) -> !isEmptyOfDefenders(p.blocco()))
            .thenComparingInt(Passaggio::distanza)
            .thenComparing(p -> !capital(p.chiave()).isPresent())
            .thenComparing(p -> p.blocco().getName()));
    final Set<Territory> done = new HashSet<>();
    int blocks = 0;
    for (final Passaggio p : found) {
      if (blocks >= limits.maxBlocchi()) {
        notes.add(
            "blocco terrestre: limite di " + limits.maxBlocchi() + " blocchi per turno raggiunto");
        break;
      }
      if (!done.add(p.blocco())) {
        continue;
      }
      final String why = plan.tattica(Tattiche.BLOCCO_TERRESTRE, p.blocco()).orElse("");
      final String path =
          "percorso da "
              + p.nemico().getName()
              + " verso "
              + p.chiave().getName()
              + " ("
              + p.distanza()
              + " territori)";
      if (block(p.blocco(), why, path)) {
        blocks++;
      }
    }
    if (found.isEmpty()) {
      notes.add(
          "blocco terrestre: nessun percorso di unita' mobili nemiche verso i territori chiave");
    }
  }

  /** Keeps or brings 1 infantry in {@code t}; returns whether the block is in place. */
  private boolean block(final Territory t, final String why, final String path) {
    final List<Unit> mineThere =
        t.getMatches(u -> u.isOwnedBy(player) && isInfantry(u) && !held.contains(u));
    if (!isEmptyOfDefenders(t)) {
      if (mineThere.isEmpty()) {
        return false;
      }
      final Unit u = mineThere.get(0);
      held.add(u);
      actions.add(
          new Azione(Tattiche.BLOCCO_TERRESTRE, t.getName(), List.of(u), "lasciata", why, path));
      return true;
    }
    // empty: one infantry from a neighbour that no real enemy can attack
    record Fonte(Territory from, Unit unit, Route route) {}
    final List<Fonte> sources = new ArrayList<>();
    final Predicate<Territory> friendly =
        Matches.territoryIsLand().and(Matches.isTerritoryAllied(player));
    for (final Territory s : map.getNeighbors(t, 2, friendly)) {
      for (final Unit u :
          s.getMatches(u -> u.isOwnedBy(player) && isInfantry(u) && !held.contains(u))) {
        final Optional<Route> route = map.getRouteForUnits(s, t, friendly, List.of(u), player);
        if (route.isPresent()
            && route.get().numberOfSteps() <= u.getMovementLeft().intValue()
            && route.get().numberOfSteps() > 0) {
          if (canSpare(s, u)) {
            sources.add(new Fonte(s, u, route.get()));
          }
          break;
        }
      }
    }
    sources.sort(
        Comparator.comparingInt((Fonte f) -> f.route().numberOfSteps())
            .thenComparing(f -> f.from().getName()));
    for (final Fonte f : sources) {
      if (move(List.of(f.unit()), f.route())) {
        held.add(f.unit());
        actions.add(
            new Azione(
                Tattiche.BLOCCO_TERRESTRE,
                t.getName(),
                List.of(f.unit()),
                "portata da " + f.from().getName(),
                why,
                path));
        return true;
      }
    }
    notes.add(
        "blocco terrestre in "
            + t.getName()
            + ": nessuna fanteria vicina libera da minaccia reale");
    return false;
  }

  /**
   * Whether {@code u} can leave {@code s} without taking defense from a territory under real
   * threat: no real enemy can attack {@code s}, or its best attack still wins at most {@code 100 -
   * minimum} of the effective level without {@code u} (the threshold of the fighting retreat).
   */
  private boolean canSpare(final Territory s, final Unit u) {
    if (!threatened(s)) {
      return true;
    }
    final ProTerritory max = enemyMax(s);
    final List<Unit> defenders =
        new ArrayList<>(
            s.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotInfrastructure())));
    defenders.remove(u);
    if (defenders.isEmpty()) {
      return false;
    }
    final double win =
        calc.calculateBattleResults(
                proData, s, max.getMaxUnits(), defenders, max.getMaxBombardUnits())
            .getWinPercentage();
    return win <= threshold(s);
  }

  /** {@code 100 - minimum win%} of the effective level of {@code t}. */
  private double threshold(final Territory t) {
    return 100
        - ProPlusTuning.riskLevel(proData, t).minimumWinPercentage(ProPlusTuning.isLowLuck(data));
  }

  /** No own or allied land unit that fights (only infrastructure, or nothing). */
  private boolean isEmptyOfDefenders(final Territory t) {
    return !t.anyUnitsMatch(
        Matches.unitIsLand()
            .and(Matches.unitIsNotInfrastructure())
            .and(Matches.isUnitAllied(player)));
  }

  /** Own capital, factories and victory cities. */
  private List<Territory> keyTerritories() {
    return map.getTerritories().stream()
        .filter(t -> !t.isWater() && t.isOwnedBy(player) && isKey(t))
        .sorted(Comparator.comparing(Territory::getName))
        .toList();
  }

  private static Optional<String> capital(final Territory t) {
    return TerritoryAttachment.get(t).flatMap(TerritoryAttachment::getCapital);
  }

  private static boolean isKey(final Territory t) {
    return capital(t).isPresent()
        || ProMatches.territoryHasInfraFactoryAndIsLand().test(t)
        || TerritoryAttachment.get(t).map(a -> a.getVictoryCity() > 0).orElse(false);
  }

  private Set<GamePlayer> realEnemies() {
    return proData
        .getTuning()
        .realEnemies(proData, player)
        .map(m -> (Set<GamePlayer>) new HashSet<>(m.keySet()))
        .orElseGet(
            () -> {
              final Set<GamePlayer> s = new HashSet<>();
              data.getPlayerList().getPlayers().stream()
                  .filter(p -> data.getRelationshipTracker().isAtWar(player, p))
                  .forEach(s::add);
              return s;
            });
  }

  private static boolean isMobileLand(final Unit u) {
    return Matches.unitIsLand().test(u)
        && Matches.unitIsNotInfrastructure().test(u)
        && u.getUnitAttachment().getMovement(u.getOwner()) >= 2;
  }

  private static boolean isInfantry(final Unit u) {
    return INFANTRY.equals(u.getType().getName());
  }

  // --------------------------------------------------------------------------- fighting retreat

  private void fightingRetreat() {
    final java.util.Map<String, Territory> stacks = new java.util.TreeMap<>();
    final java.util.Map<String, Double> best = new java.util.HashMap<>();
    final var costs = new TuvCostsCalculator().getCostsForTuv(player);
    for (final Territory t : map.getTerritories()) {
      if (t.isWater() || !t.isOwnedBy(player)) {
        continue;
      }
      final String front = plan.fronte(t);
      if (plan.tattica(Tattiche.RITIRATA, front).isEmpty()) {
        continue;
      }
      final List<Unit> units = movableLand(t);
      if (units.isEmpty()) {
        continue;
      }
      final double tuv = TuvUtils.getTuv(units, costs);
      if (tuv > best.getOrDefault(front, 0.0)) {
        best.put(front, tuv);
        stacks.put(front, t);
      }
    }
    if (stacks.isEmpty()) {
      notes.add("ritirata combattuta: nessuno stack sui fronti attivi");
    }
    stacks.forEach((front, t) -> retreat(t));
  }

  private List<Unit> movableLand(final Territory t) {
    return t.getMatches(
        u ->
            u.isOwnedBy(player)
                && Matches.unitIsLand().test(u)
                && Matches.unitIsNotInfrastructure().test(u)
                && !held.contains(u)
                && u.getMovementLeft().signum() > 0);
  }

  private void retreat(final Territory t) {
    if (capital(t).filter(c -> c.equals(player.getName())).isPresent()) {
      notes.add("ritirata combattuta: lo stack di " + t.getName() + " e' nella capitale, resta");
      return;
    }
    final ProTerritory max = enemyMax(t);
    if (max == null || max.getMaxUnits().isEmpty()) {
      notes.add("ritirata combattuta: stack di " + t.getName() + " non attaccabile, resta");
      return;
    }
    final List<Unit> defenders =
        t.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotInfrastructure()));
    final double win =
        calc.calculateBattleResults(
                proData, t, max.getMaxUnits(), defenders, max.getMaxBombardUnits())
            .getWinPercentage();
    final double threshold = threshold(t);
    if (win <= threshold) {
      notes.add(
          String.format(
              Locale.ROOT,
              "ritirata combattuta: stack di %s resta (win%% nemica %.0f%% <= soglia %.0f%%)",
              t.getName(),
              win,
              threshold));
      return;
    }
    final Predicate<Territory> friendly =
        Matches.territoryIsLand().and(Matches.isTerritoryAllied(player));
    final Optional<Territory> key =
        keyTerritories().stream()
            .filter(k -> !k.equals(t))
            .filter(k -> map.getDistance(t, k, friendly) > 0)
            .min(
                Comparator.comparingInt((Territory k) -> map.getDistance(t, k, friendly))
                    .thenComparing(k -> capital(k).isEmpty())
                    .thenComparing(Territory::getName));
    if (key.isEmpty()) {
      notes.add("ritirata combattuta: nessun territorio chiave raggiungibile da " + t.getName());
      return;
    }
    final String why = plan.tattica(Tattiche.RITIRATA, t).orElse("");
    final String detail =
        String.format(
            Locale.ROOT,
            "win%% nemica %.0f%% > soglia %.0f%%, verso %s",
            win,
            threshold,
            key.get().getName());
    final List<Unit> moved = new ArrayList<>();
    String end = "";
    for (final Unit u : movableLand(t)) {
      final Optional<Route> route =
          map.getRouteForUnits(t, key.get(), friendly, List.of(u), player);
      if (route.isEmpty()) {
        continue;
      }
      final int steps = Math.min(u.getMovementLeft().intValue(), route.get().numberOfSteps());
      final Route part = new Route(route.get().getAllTerritories().subList(0, steps + 1));
      if (move(List.of(u), part)) {
        held.add(u);
        moved.add(u);
        end = part.getEnd().getName();
      }
    }
    if (moved.isEmpty()) {
      notes.add("ritirata combattuta: nessuna unita' di " + t.getName() + " puo' arretrare");
      return;
    }
    actions.add(
        new Azione(
            Tattiche.RITIRATA,
            t.getName(),
            moved,
            "arretrate fino a " + end,
            why,
            detail + (stayingBlocks(t) ? "; blocchi lasciati" : "")));
  }

  private boolean stayingBlocks(final Territory t) {
    return actions.stream()
        .anyMatch(
            a ->
                a.strumento().equals(Tattiche.BLOCCO_TERRESTRE)
                    && a.territorio().equals(t.getName()));
  }

  // ----------------------------------------------------------------------------- naval blocks

  private void navalBlocks() {
    final Optional<Strategia.Piano> enemyPlan = plan.pianoNemicoLetto();
    if (enemyPlan.isEmpty() || plan.getProbabilitaNemico() <= limits.sogliaLetturaBlocco()) {
      notes.add(
          String.format(
              Locale.ROOT,
              "blocco navale: nessun piano nemico letto oltre il %.0f%%",
              limits.sogliaLetturaBlocco() * 100));
      return;
    }
    final List<String> zones = enemyPlan.get().zoneDiPassaggio();
    if (zones.isEmpty()) {
      notes.add("blocco navale: il piano " + enemyPlan.get().label() + " non ha zone di passaggio");
      return;
    }
    int ships = 0;
    for (final String name : zones) {
      if (ships >= limits.maxNaviBlocco()) {
        break;
      }
      final Territory zone = map.getTerritoryOrNull(name);
      if (zone == null) {
        continue;
      }
      final Optional<String> why = plan.tattica(Tattiche.BLOCCO_NAVALE, zone);
      if (why.isEmpty()) {
        continue;
      }
      final String detail =
          String.format(
              Locale.ROOT,
              "zona di passaggio di %s (letto %.0f%%)",
              enemyPlan.get().label(),
              plan.getProbabilitaNemico() * 100);
      final List<Unit> there = zone.getMatches(this::isBlockShip);
      if (!there.isEmpty()) {
        held.add(there.get(0));
        actions.add(
            new Azione(
                Tattiche.BLOCCO_NAVALE,
                name,
                List.of(there.get(0)),
                "lasciata",
                why.get(),
                detail));
        ships++;
        continue;
      }
      if (zone.anyUnitsMatch(Matches.enemyUnit(player))) {
        notes.add("blocco navale: " + name + " occupata dal nemico");
        continue;
      }
      if (sendShip(zone, why.get(), detail)) {
        ships++;
      }
    }
  }

  private boolean sendShip(final Territory zone, final String why, final String detail) {
    record Nave(Territory from, Unit unit, Route route) {}
    final Predicate<Territory> canPass = ProMatches.territoryCanMoveSeaUnitsThrough(player, false);
    final List<Nave> ships = new ArrayList<>();
    for (final Territory from : map.getTerritories()) {
      if (!from.isWater() || from.equals(zone)) {
        continue;
      }
      // never the escort of own transports
      if (from.anyUnitsMatch(Matches.unitIsOwnedBy(player).and(Matches.unitIsSeaTransport()))) {
        continue;
      }
      for (final Unit u : from.getMatches(this::isBlockShip)) {
        final Optional<Route> route = map.getRouteForUnits(from, zone, canPass, List.of(u), player);
        if (route.isPresent() && route.get().numberOfSteps() <= u.getMovementLeft().intValue()) {
          ships.add(new Nave(from, u, route.get()));
        }
      }
    }
    ships.sort(
        Comparator.comparingInt((Nave n) -> n.route().numberOfSteps())
            .thenComparing(n -> n.unit().getType().getName())
            .thenComparing(n -> n.from().getName()));
    for (final Nave n : ships) {
      if (move(List.of(n.unit()), n.route())) {
        held.add(n.unit());
        actions.add(
            new Azione(
                Tattiche.BLOCCO_NAVALE,
                zone.getName(),
                List.of(n.unit()),
                "mandata da " + n.from().getName(),
                why,
                detail));
        return true;
      }
    }
    notes.add("blocco navale: nessun sottomarino o cacciatorpediniere raggiunge " + zone.getName());
    return false;
  }

  /** Own submarine or destroyer, not used by another tool or operation, able to move. */
  private boolean isBlockShip(final Unit u) {
    return u.isOwnedBy(player)
        && Matches.unitIsSea().test(u)
        && (Matches.unitIsDestroyer().test(u) || Matches.unitHasSubBattleAbilities().test(u))
        && !held.contains(u);
  }

  // ------------------------------------------------------------------------------------- moves

  private boolean move(final List<Unit> units, final Route route) {
    final Optional<String> error = moveDel.performMove(new MoveDescription(units, route));
    if (error.isPresent()) {
      ProLogger.warn("[Pro+] Delay tool: could not move " + units + ": " + error.get());
      return false;
    }
    return true;
  }

  // ------------------------------------------------------------------------- record and losses

  /**
   * Writes the game property {@code "Pro+ ritardo: <nation>"} = {round, blocchi, navi, ritirata:
   * unit ids, caccia: [{zona, trasporti}]} (the autoplay counts placed and lost units from it), and
   * returns the units of the previous record that are gone from the map (lost in the tools).
   */
  public static Perdite registra(
      final GameData data,
      final GamePlayer player,
      final Esito result,
      final List<PianoAttivo.Caccia> hunt) {
    final String key = PROPERTY_PREFIX + player.getName();
    final Perdite lost = perdite(data, data.getProperties().get(key));
    final JsonObject json = new JsonObject();
    json.addProperty("round", data.getSequence().getRound());
    // decision of Matteo (strategic contract): an infantry left where it already was is no block
    json.add("blocchi", ids(result, Tattiche.BLOCCO_TERRESTRE, false));
    json.add("blocchi_lasciati", ids(result, Tattiche.BLOCCO_TERRESTRE, true));
    json.add("navi", ids(result, Tattiche.BLOCCO_NAVALE));
    json.add("ritirata", ids(result, Tattiche.RITIRATA));
    final JsonArray h = new JsonArray();
    for (final PianoAttivo.Caccia c : hunt) {
      final JsonObject o = new JsonObject();
      o.addProperty("zona", c.zona());
      final JsonArray t = new JsonArray();
      c.trasporti().forEach(t::add);
      o.add("trasporti", t);
      h.add(o);
    }
    json.add("caccia", h);
    data.getProperties().set(key, (Serializable) null);
    data.getProperties().set(key, json.toString());
    return lost;
  }

  /** Units of the previous record no longer on the map, per tool. */
  public record Perdite(int blocchi, int navi, int ritirata) {
    public int totale() {
      return blocchi + navi + ritirata;
    }
  }

  private static Perdite perdite(final GameData data, final @Nullable Object previous) {
    if (!(previous instanceof String text)) {
      return new Perdite(0, 0, 0);
    }
    final JsonObject json;
    try {
      json = JsonParser.parseString(text).getAsJsonObject();
    } catch (final RuntimeException e) {
      return new Perdite(0, 0, 0);
    }
    final Set<String> onMap = new HashSet<>();
    data.getMap().getTerritories().forEach(t -> t.getUnits().forEach(u -> onMap.add(id(u))));
    return new Perdite(
        gone(json, "blocchi", onMap), gone(json, "navi", onMap), gone(json, "ritirata", onMap));
  }

  private static int gone(final JsonObject json, final String key, final Set<String> onMap) {
    if (!json.has(key)) {
      return 0;
    }
    int n = 0;
    for (final var e : json.getAsJsonArray(key)) {
      if (!onMap.contains(e.getAsString())) {
        n++;
      }
    }
    return n;
  }

  private static JsonArray ids(final Esito result, final String tool) {
    final JsonArray a = new JsonArray();
    result.azioni().stream()
        .filter(x -> x.strumento().equals(tool))
        .flatMap(x -> x.unita().stream())
        .map(StrumentiRitardo::id)
        .distinct()
        .forEach(a::add);
    return a;
  }

  /** Land blocks left where they were ({@code left}) or brought there. */
  private static JsonArray ids(final Esito result, final String tool, final boolean left) {
    final JsonArray a = new JsonArray();
    result.azioni().stream()
        .filter(x -> x.strumento().equals(tool))
        .filter(x -> LASCIATA.equals(x.modo()) == left)
        .flatMap(x -> x.unita().stream())
        .map(StrumentiRitardo::id)
        .distinct()
        .forEach(a::add);
    return a;
  }

  static String id(final Unit u) {
    return u.getId().toString();
  }

  /** Total line of the turn, eg "Ritardo, totale del turno: 2 blocchi terrestri, ...". */
  public static String totale(
      final Esito result, final List<PianoAttivo.Caccia> hunt, final Perdite lost) {
    return String.format(
        Locale.ROOT,
        "Ritardo, totale del turno: %d blocchi terrestri (piu' %d fanterie lasciate dov'erano),"
            + " %d ritirate, %d navi di blocco, %d zone di caccia ai trasporti; perse negli"
            + " strumenti dal turno prima: %d (blocchi %d, navi %d, ritirata %d)",
        result.azioni().stream()
            .filter(a -> a.strumento().equals(Tattiche.BLOCCO_TERRESTRE))
            .filter(a -> !LASCIATA.equals(a.modo()))
            .count(),
        result.azioni().stream()
            .filter(a -> a.strumento().equals(Tattiche.BLOCCO_TERRESTRE))
            .filter(a -> LASCIATA.equals(a.modo()))
            .count(),
        result.conta(Tattiche.RITIRATA),
        result.conta(Tattiche.BLOCCO_NAVALE),
        hunt.size(),
        lost.totale(),
        lost.blocchi(),
        lost.navi(),
        lost.ritirata());
  }

  static List<String> names(final Collection<Unit> units) {
    return units.stream().map(StrumentiRitardo::id).toList();
  }
}
