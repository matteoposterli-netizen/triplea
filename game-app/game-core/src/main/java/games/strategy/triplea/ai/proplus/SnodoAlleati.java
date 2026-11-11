package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
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
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Step R4 block B "ALLEATI - SNODO GIBILTERRA E AFRICA" (switch {@link
 * StrategaInterruttori#ALLEATI_R4}, off by default, on top of R3a; it replaces R3a's theater
 * choice). The hub and the landings live in {@link SbarcoCoordinato} (B1-B3); here the pure rules
 * and the United Kingdom's Africa (B4 South Africa, B5 neutrals east of Egypt, B6 the southern
 * barrier).
 */
public final class SnodoAlleati {
  private SnodoAlleati() {}

  static final String AFRICA = "AFRICA";
  static final String FRANCIA = "FRANCIA";
  static final String MISTO = "MISTO";

  // ============================================================================ pure rules

  /** B1: Gibraltar is usable when it is the alliance's and no enemy warship is in its zones. */
  static boolean gibilterraUtilizzabile(final boolean dellAlleanza, final int naviNemiche) {
    return dellAlleanza && naviNemiche == 0;
  }

  /**
   * B2: the hub's choice from what goes where: fast units to Africa and the rest to France = MISTO;
   * the rest (or everything) to an African/Italian target = AFRICA; to France = FRANCIA; nothing =
   * empty.
   */
  static Optional<String> sceltaSnodo(
      final boolean velociInAfrica, @Nullable final String restoVerso, final ConfigR4 cfg) {
    final boolean restFrance = restoVerso != null && cfg.bersagliFrancia().contains(restoVerso);
    if (velociInAfrica && restFrance) {
      return Optional.of(MISTO);
    }
    if (restFrance) {
      return Optional.of(FRANCIA);
    }
    if (velociInAfrica || restoVerso != null) {
      return Optional.of(AFRICA);
    }
    return Optional.empty();
  }

  /** B2: a fast unit (land, movement >= {@code movimento_veloci}). */
  static boolean veloce(final Unit u, final ConfigR4 cfg) {
    return Matches.unitIsLand().test(u)
        && u.getUnitAttachment().getMovement(u.getOwner()) >= cfg.movimentoVeloci();
  }

  /**
   * B4: South Africa buys while the Axis has African territories or Egypt is not the alliance's; at
   * most the factory's capacity.
   */
  static int unitaSudafrica(
      final boolean asseInAfrica, final boolean egittoAlleato, final int capacita) {
    return (asseInAfrica || !egittoAlleato) ? Math.max(0, capacita) : 0;
  }

  /** B6: the barrier is manned when Axis land units are within the distance. */
  static boolean sbarramentoAttivo(final int distanzaAsse, final int soglia) {
    return distanzaAsse >= 0 && distanzaAsse <= soglia;
  }

  // ========================================================================== entry points

  /** The switch is on and the nation plays block B (the R3a nations: USA, UK, Russia). */
  public static boolean attivo(final GameState data, final GamePlayer player) {
    if (player == null || !SbarcoCoordinato.attivo(data, player)) {
      return false;
    }
    return ProPlusSettings.allianceOf(data, player)
        .map(a -> StrategaInterruttori.ALLEATI_R4.acceso(data.getProperties(), a))
        .orElse(false);
  }

  /** B3: the ids of the landing program's units of {@code player} (followed since bought). */
  static Set<String> programma(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return Set.of();
    }
    final JsonObject ns =
        Impegno.nazione(Impegno.stato(data.getProperties(), alliance.get()), player.getName());
    return ns.has("programma_ids")
        ? new HashSet<>(ns.getAsJsonObject("programma_ids").keySet())
        : Set.of();
  }

  /** The Axis owns a territory of North Africa now. */
  static boolean asseInNordAfrica(final GameState data, final GamePlayer player) {
    return contaAsse(data, player, ConfigR4.get().nordAfrica()) > 0;
  }

  static int contaAsse(final GameState data, final GamePlayer player, final List<String> names) {
    int n = 0;
    for (final String name : names) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t != null
          && !t.getOwner().isNull()
          && data.getRelationshipTracker().isAtWar(player, t.getOwner())
          && ProPlusSettings.allianceOf(data, t.getOwner()).isPresent()) {
        n++;
      }
    }
    return n;
  }

  // ------------------------------------------------------------------- B4 South Africa

  /**
   * B4 purchase of the United Kingdom: units of {@code tipi_sudafrica} in the South African
   * factory, up to its capacity, within {@code pu}. Writes the order and the register; returns the
   * PUs ordered.
   */
  static int acquistiSudafrica(
      final GameState data,
      final GamePlayer player,
      final int pu,
      final JsonObject order,
      final List<String> lines) {
    if (!attivo(data, player) || !Programmi.UK.equals(player.getName())) {
      return 0;
    }
    final ConfigR4 cfg = ConfigR4.get();
    final Territory factory = data.getMap().getTerritoryOrNull(cfg.fabbricaSudafrica());
    if (factory == null || !factory.isOwnedBy(player)) {
      return 0;
    }
    final List<String> africa = new ArrayList<>(cfg.italiaLivello1());
    africa.addAll(cfg.italiaLivello2());
    africa.addAll(List.of("Ethiopia", "Italian Somaliland"));
    final boolean axis = contaAsse(data, player, africa) > 0;
    final Territory egypt = data.getMap().getTerritoryOrNull("Egypt");
    final boolean egyptOk = egypt != null && Matches.isTerritoryAllied(player).test(egypt);
    final int capacity = Assedio.capacita(factory);
    final int units = unitaSudafrica(axis, egyptOk, capacity);
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
    final Map<String, Integer> mix = new LinkedHashMap<>();
    int left = pu;
    for (int i = 0; i < units; i++) {
      boolean bought = false;
      for (int k = 0; k < cfg.tipiSudafrica().size(); k++) {
        final String t = cfg.tipiSudafrica().get((i + k) % cfg.tipiSudafrica().size());
        final int cost = costs.getOrDefault(t, 0);
        if (cost > 0 && cost <= left) {
          mix.merge(t, 1, Integer::sum);
          left -= cost;
          bought = true;
          break;
        }
      }
      if (!bought) {
        break;
      }
    }
    if (!mix.isEmpty()) {
      final JsonObject m = new JsonObject();
      mix.forEach(m::addProperty);
      order.add("tipi_sudafrica", m);
      order.addProperty("fabbrica_sudafrica", factory.getName());
    }
    final JsonArray alt = new JsonArray();
    final JsonObject a = new JsonObject();
    a.addProperty("asse_in_africa", axis);
    a.addProperty("egitto_alleato", egyptOk);
    a.addProperty("capacita", capacity);
    a.addProperty("pu_disponibili", pu);
    final JsonObject m = new JsonObject();
    mix.forEach(m::addProperty);
    a.add("ordine", m);
    a.addProperty("pu_ordinati", pu - left);
    alt.add(a);
    registra(
        data,
        player,
        "africa_sudafrica",
        alt,
        mix.isEmpty() ? "niente" : m.toString(),
        units > 0 ? "COMPRA_SUDAFRICA" : "SUDAFRICA_CHIUSO",
        units > 0
            ? "Asse in Africa o Egitto non alleato: unita' veloci in Sudafrica fino alla capacita'"
            : "Egitto alleato e Asse fuori dall'Africa");
    lines.add("  R4 Sudafrica: " + (mix.isEmpty() ? "niente" : mix) + " (" + (pu - left) + " PU)");
    return pu - left;
  }

  // -------------------------------------------------------------- UK non-combat (B4-B6)

  /**
   * Non-combat move of the United Kingdom (before Hard's): the South African units one step north,
   * the pro-Allied neutrals east of Egypt (also by transport), the southern barrier. Never a
   * British unit into Russia. Returns the units Hard must leave.
   */
  static Set<Unit> nonCombattimentoUk(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final GamePlayer player,
      final CampagnaOmbra.Calcolo c,
      final Set<String> keep,
      final List<String> lines) {
    final Set<Unit> held = new LinkedHashSet<>();
    final GameState data = proData.getData();
    if (!attivo(data, player) || !Programmi.UK.equals(player.getName())) {
      return held;
    }
    try {
      held.addAll(nord(data, player, moveDel, lines));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, Sudafrica: " + e);
    }
    try {
      held.addAll(neutrali(data, player, moveDel, keep, held, lines));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, neutrali: " + e);
    }
    try {
      held.addAll(sbarramento(proData, calc, player, moveDel, c, keep, held, lines));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, sbarramento: " + e);
    }
    return held;
  }

  /** B4: the units bought in South Africa (followed by id) move north toward Egypt. */
  private static Set<Unit> nord(
      final GameState data,
      final GamePlayer player,
      final IMoveDelegate moveDel,
      final List<String> lines) {
    final Set<Unit> held = new LinkedHashSet<>();
    final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
    final JsonObject ns =
        Impegno.nazione(Impegno.stato(data.getProperties(), alliance), player.getName());
    if (!ns.has("sudafrica_ids")) {
      return held;
    }
    final Set<String> ids = ns.getAsJsonObject("sudafrica_ids").keySet();
    final Territory dest = destinazioneNord(data, player);
    if (dest == null) {
      return held;
    }
    int moved = 0;
    final Map<Unit, Territory> where = new LinkedHashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        continue;
      }
      for (final Unit u : t.getMatches(Matches.unitIsOwnedBy(player))) {
        if (ids.contains(u.getId().toString())) {
          where.put(u, t);
        }
      }
    }
    for (final var e : where.entrySet()) {
      final Unit u = e.getKey();
      final Territory from = e.getValue();
      if (from.equals(dest) || !Matches.unitHasMovementLeft().test(u)) {
        continue;
      }
      final Optional<Route> r =
          data.getMap()
              .getRoute(
                  from,
                  dest,
                  x ->
                      !x.isWater() && Matches.isTerritoryAllied(player).test(x) && !russa(data, x));
      if (r.isEmpty()) {
        continue;
      }
      final int steps = Math.min(u.getMovementLeft().intValue(), r.get().numberOfSteps());
      final List<Territory> path = r.get().getSteps().subList(0, steps);
      final Route go = new Route(from, path.toArray(new Territory[0]));
      if (moveDel.performMove(new MoveDescription(List.of(u), go)).isEmpty()) {
        held.add(u);
        moved++;
      }
    }
    if (moved > 0) {
      lines.add("  R4 Sudafrica: " + moved + " unita' verso " + dest.getName());
    }
    return held;
  }

  /** B4: Egypt, or the allied territory nearest to an Axis territory of Africa once Egypt held. */
  @Nullable
  private static Territory destinazioneNord(final GameState data, final GamePlayer player) {
    final Territory egypt = data.getMap().getTerritoryOrNull("Egypt");
    if (egypt == null) {
      return null;
    }
    if (Matches.isTerritoryAllied(player).test(egypt)) {
      return egypt;
    }
    // Egypt lost: the allied territory next to it
    return data.getMap().getNeighbors(egypt, Matches.territoryIsLand()).stream()
        .filter(Matches.isTerritoryAllied(player))
        .min(Comparator.comparing(Territory::getName))
        .orElse(null);
  }

  /**
   * B5: the pro-Allied neutrals east of Egypt still neutral: by land with a unit next to them
   * (R2c), otherwise by a British transport that loads a unit and unloads it there in this move.
   */
  private static Set<Unit> neutrali(
      final GameState data,
      final GamePlayer player,
      final IMoveDelegate moveDel,
      final Set<String> keep,
      final Set<Unit> used,
      final List<String> lines) {
    final Set<Unit> held = new LinkedHashSet<>();
    final JsonArray alt = new JsonArray();
    final List<String> taken = new ArrayList<>();
    for (final String name : ConfigR4.get().neutraliEst()) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t == null
          || t.getOwner().isNull()
          || !t.getOwner().getName().startsWith("Neutral")
          || data.getRelationshipTracker().isAtWar(player, t.getOwner())) {
        continue;
      }
      final JsonObject a = new JsonObject();
      a.addProperty("territorio", name);
      String how = "nessun mezzo";
      // by land
      for (final Territory n : data.getMap().getNeighbors(t, Matches.territoryIsLand())) {
        if (!n.isOwnedBy(player) || keep.contains(n.getName())) {
          continue;
        }
        final Optional<Unit> u =
            n
                .getMatches(
                    Matches.unitIsOwnedBy(player)
                        .and(Matches.unitIsLand())
                        .and(Matches.unitIsNotInfrastructure())
                        .and(Matches.unitHasMovementLeft()))
                .stream()
                .filter(x -> !used.contains(x) && !held.contains(x))
                .findFirst();
        if (u.isPresent()
            && moveDel
                .performMove(new MoveDescription(List.of(u.get()), new Route(n, t)))
                .isEmpty()) {
          held.add(u.get());
          how = "via terra da " + n.getName();
          break;
        }
      }
      // by transport
      if (how.equals("nessun mezzo")) {
        how = viaMare(data, player, moveDel, t, keep, used, held).orElse(how);
      }
      a.addProperty("mezzo", how);
      a.addProperty("preso", t.isOwnedBy(player));
      alt.add(a);
      if (t.isOwnedBy(player)) {
        taken.add(name);
      }
    }
    if (!alt.isEmpty()) {
      registra(
          data,
          player,
          "neutrali_est",
          alt,
          String.join(",", taken),
          taken.isEmpty() ? "NON_PRESO" : "PRESO",
          taken.isEmpty()
              ? "nessun neutrale filo-alleato raggiungibile ora"
              : "presi entrando nel non combattimento: " + String.join(", ", taken));
      if (!taken.isEmpty()) {
        lines.add("  R4 neutrali a est dell'Egitto presi: " + String.join(", ", taken));
      }
    }
    return held;
  }

  /** B5: a British transport loads one land unit and unloads it into {@code t} (one move each). */
  private static Optional<String> viaMare(
      final GameState data,
      final GamePlayer player,
      final IMoveDelegate moveDel,
      final Territory t,
      final Set<String> keep,
      final Set<Unit> used,
      final Set<Unit> held) {
    final List<Territory> unloadZones =
        data.getMap().getNeighbors(t, Matches.territoryIsWater()).stream()
            .filter(z -> !z.anyUnitsMatch(Matches.enemyUnit(player)))
            .toList();
    if (unloadZones.isEmpty()) {
      return Optional.empty();
    }
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit tr :
          z.getMatches(
              Matches.unitIsSeaTransportButNotCombatSeaTransport()
                  .and(Matches.unitIsOwnedBy(player))
                  .and(Matches.unitHasNotMoved()))) {
        if (used.contains(tr)
            || tr.isTransporting()
            || TransportTracker.getAvailableCapacity(tr) <= 0) {
          continue;
        }
        // a land unit next to the transport's zone
        for (final Territory n : data.getMap().getNeighbors(z, Matches.territoryIsLand())) {
          if (!n.isOwnedBy(player) || keep.contains(n.getName())) {
            continue;
          }
          final Optional<Unit> u =
              n
                  .getMatches(
                      Matches.unitIsOwnedBy(player)
                          .and(Matches.unitIsLand())
                          .and(Matches.unitIsNotInfrastructure())
                          .and(Matches.unitHasNotMoved())
                          .and(x -> x.getUnitAttachment().getTransportCost() > 0)
                          .and(
                              x ->
                                  x.getUnitAttachment().getTransportCost()
                                      <= TransportTracker.getAvailableCapacity(tr)))
                  .stream()
                  .filter(x -> !used.contains(x) && !held.contains(x))
                  .findFirst();
          if (u.isEmpty()) {
            continue;
          }
          Route best = null;
          for (final Territory to : unloadZones) {
            final Optional<Route> r =
                to.equals(z)
                    ? Optional.of(new Route(z))
                    : data.getMap()
                        .getRouteForUnits(
                            z,
                            to,
                            x ->
                                x.isWater()
                                    && ProMatches.territoryCanMoveSeaUnitsThrough(player, false)
                                        .test(x)
                                    && !x.anyUnitsMatch(Matches.enemyUnit(player)),
                            List.of(tr, u.get()),
                            player);
            if (r.isPresent()
                && r.get().numberOfSteps() <= tr.getMovementLeft().intValue()
                && (best == null || r.get().numberOfSteps() < best.numberOfSteps())) {
              best = r.get();
            }
          }
          if (best == null) {
            continue;
          }
          if (moveDel
              .performMove(
                  new MoveDescription(List.of(u.get()), new Route(n, z), Map.of(u.get(), tr)))
              .isPresent()) {
            continue;
          }
          if (best.numberOfSteps() > 0
              && moveDel.performMove(new MoveDescription(List.of(tr, u.get()), best)).isPresent()) {
            held.add(tr);
            held.add(u.get());
            return Optional.empty();
          }
          final Territory at = best.getEnd();
          held.add(tr);
          held.add(u.get());
          if (moveDel
              .performMove(new MoveDescription(List.of(u.get()), new Route(at, t)))
              .isEmpty()) {
            return Optional.of("trasporto da " + z.getName() + " con unita' da " + n.getName());
          }
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  /**
   * B6: the first passage of the southern road that is the alliance's, with Axis land units within
   * {@code distanza_sbarramento}: the British garrison of rule 2.6 case c there (units already
   * there count; the others from the neighbors, cheapest first, never from London's or Egypt's
   * garrison, never into Russia).
   */
  private static Set<Unit> sbarramento(
      final ProData proData,
      final ProOddsCalculator calc,
      final GamePlayer player,
      final IMoveDelegate moveDel,
      final CampagnaOmbra.Calcolo c,
      final Set<String> keep,
      final Set<Unit> used,
      final List<String> lines) {
    final GameState data = proData.getData();
    final ConfigR4 cfg = ConfigR4.get();
    final Set<Unit> held = new LinkedHashSet<>();
    final JsonArray alt = new JsonArray();
    Territory chosen = null;
    int chosenDistance = -1;
    for (final String name : cfg.passaggiSud()) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t == null) {
        continue;
      }
      final boolean ours = Matches.isTerritoryAllied(player).test(t) && !russa(data, t);
      final int d = distanzaAsse(data, player, t, cfg.distanzaSbarramento());
      final JsonObject a = new JsonObject();
      a.addProperty("territorio", name);
      a.addProperty("dell_alleanza", ours);
      a.addProperty("distanza_asse", d);
      alt.add(a);
      if (chosen == null && ours) {
        chosen = t;
        chosenDistance = d;
      }
    }
    if (chosen == null || !sbarramentoAttivo(chosenDistance, cfg.distanzaSbarramento())) {
      registra(
          data,
          player,
          "sbarramento_sud",
          alt,
          "",
          "NESSUNA_MINACCIA",
          chosen == null
              ? "nessun passaggio dell'alleanza"
              : "nessuna unita' di terra dell'Asse entro " + cfg.distanzaSbarramento());
      return held;
    }
    final Territory t = chosen;
    final List<Unit> enemyLand = new ArrayList<>();
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateEnemyAttackOptions(List.of(), List.of(t));
    final ProTerritory max = tm.getEnemyAttackOptions().getMax(t);
    if (max != null) {
      max.getMaxUnits().stream()
          .filter(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()))
          .forEach(enemyLand::add);
    }
    final List<String> garrison = c.presidioMinimo(t, enemyLand);
    final List<Unit> there =
        new ArrayList<>(
            t.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(Matches.unitIsLand())
                    .and(Matches.unitIsNotInfrastructure())));
    int need = Math.max(0, garrison.size() - there.size());
    there.stream().limit(garrison.size()).forEach(held::add);
    int moved = 0;
    for (final Territory n : data.getMap().getNeighbors(t, Matches.territoryIsLand())) {
      if (need <= 0) {
        break;
      }
      if (!n.isOwnedBy(player) || keep.contains(n.getName()) || russa(data, n)) {
        continue;
      }
      final List<Unit> units =
          new ArrayList<>(
              n.getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsLand())
                      .and(Matches.unitIsNotInfrastructure())
                      .and(Matches.unitHasMovementLeft())));
      units.removeIf(u -> used.contains(u) || held.contains(u));
      units.sort(Comparator.comparingDouble((Unit u) -> c.tuv(List.of(u))));
      for (final Unit u : units) {
        if (need <= 0) {
          break;
        }
        if (moveDel.performMove(new MoveDescription(List.of(u), new Route(n, t))).isEmpty()) {
          held.add(u);
          need--;
          moved++;
        }
      }
    }
    final JsonObject a = new JsonObject();
    a.addProperty("presidio_richiesto", garrison.size());
    a.addProperty("gia_presenti", there.size());
    a.addProperty("mosse", moved);
    a.addProperty("mancanti", need);
    alt.add(a);
    registra(
        data,
        player,
        "sbarramento_sud",
        alt,
        t.getName(),
        "PRESIDIO",
        "Asse entro "
            + chosenDistance
            + " mosse: presidio caso c su "
            + t.getName()
            + " ("
            + garrison.size()
            + " unita', "
            + moved
            + " mosse, "
            + need
            + " mancanti)");
    lines.add(
        "  R4 sbarramento a sud su "
            + t.getName()
            + ": presidio "
            + garrison.size()
            + ", mosse "
            + moved
            + ", mancanti "
            + need);
    return held;
  }

  /** The land distance (<= {@code max}) from {@code t} to the nearest Axis land unit, else -1. */
  static int distanzaAsse(
      final GameState data, final GamePlayer player, final Territory t, final int max) {
    int best = -1;
    for (final Territory x : data.getMap().getTerritories()) {
      if (x.isWater()
          || !x.anyUnitsMatch(
              Matches.enemyUnit(player)
                  .and(Matches.unitIsLand())
                  .and(Matches.unitIsNotInfrastructure())
                  .and(u -> ProPlusSettings.allianceOf(data, u.getOwner()).isPresent()))) {
        continue;
      }
      final int d = x.equals(t) ? 0 : data.getMap().getDistance(x, t, Matches.territoryIsLand());
      if (d >= 0 && d <= max && (best < 0 || d < best)) {
        best = d;
      }
    }
    return best;
  }

  /** A territory of the Russians (no British unit enters it). */
  private static boolean russa(final GameState data, final Territory t) {
    return "Russians".equals(t.getOwner().getName());
  }

  private static void registra(
      final GameState data,
      final GamePlayer player,
      final String type,
      final JsonArray alt,
      final String choice,
      final String code,
      final String why) {
    try {
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject st = SbarcoCoordinato.stato(data.getProperties(), alliance);
      final int round = data.getSequence().getRound();
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              RegistroDecisioni.nuovoId(st, round, player.getName(), type),
              round,
              player.getName(),
              type,
              alt,
              choice,
              code,
              why,
              new JsonObject()));
      SbarcoCoordinato.salva(data.getProperties(), alliance, st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, registro " + type + ": " + e);
    }
  }
}
