package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step R6c "ITALIA: SPOLA IN CONVOGLIO" (switch {@link StrategaInterruttori#ITALIA_CONVOGLIO}, on
 * top of the siege, off by default: off = the Axis as before). Italy's Africa shuttle of step R4
 * (A4) with the rules of step R6b:
 *
 * <ul>
 *   <li>E1: the shuttle's state comes from Italy's war and an Axis base in Africa ({@link
 *       Assedio#baseAfrica}); never "before the war" nor the United Kingdom. Without a base the
 *       convoy goes next to the African target of highest value and lands there in the combat move
 *       with win >= 70;
 *   <li>E2: A1 of step R6b ({@link TrasportiProtetti#valuta}): defenders = the Italian and German
 *       warships there, the Axis planes that can scramble to it and those on carriers (German
 *       planes are never moved for this);
 *   <li>E3: the loaded transports sail in convoy with the minimum of Italian warships that makes
 *       the arrival zone safe; the escort stays with the transports and they come back together;
 *   <li>E4: the share buys warships first, one at a time (the type that lowers the risk most per
 *       PU), until the route escorted by the whole fleet is safe, then complete pairs.
 * </ul>
 */
public final class ConvoglioItalia {
  private ConvoglioItalia() {}

  static final String BASE = "BASE";
  static final String SBARCO = "SBARCO";
  static final String NESSUNA = "NESSUNA_META";

  /** Values of {@code impegno.r6c} in {@code stratega-llm.json}. */
  public record Config(boolean accesoDiDefault, double sogliaSbarco, int maxNaviPerTurno) {
    private static @Nullable Config cache;

    public static synchronized Config get() {
      if (cache == null) {
        try (InputStream in =
            ConvoglioItalia.class.getClassLoader().getResourceAsStream(StrategaConfig.RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + StrategaConfig.RESOURCE);
          }
          final JsonObject o =
              JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                  .getAsJsonObject()
                  .getAsJsonObject("impegno")
                  .getAsJsonObject("r6c");
          cache =
              new Config(
                  v(o, "italia_convoglio_acceso_di_default").getAsBoolean(),
                  v(o, "soglia_sbarco").getAsDouble(),
                  v(o, "max_navi_per_turno").getAsInt());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello stratega non valido: " + e, e);
        }
      }
      return cache;
    }

    private static JsonElement v(final JsonObject o, final String key) {
      return o.getAsJsonObject(key).get("valore");
    }
  }

  /** On for Italy of the siege with the switch on. */
  public static boolean attivo(final GameState data, final GamePlayer player) {
    return player != null
        && Assedio.italia(data, player)
        && ProPlusSettings.allianceOf(data, player)
            .map(a -> StrategaInterruttori.ITALIA_CONVOGLIO.acceso(data.getProperties(), a))
            .orElse(false);
  }

  // ======================================================================== E1 destination

  /** Where the convoy goes: an Axis base in Africa (unload there) or a target to land on. */
  record Meta(String stato, @Nullable Territory terra, List<Territory> zone, double vittoria) {}

  /**
   * E1: the Axis base in Africa ({@link Assedio#baseAfrica}) with its sea zones; without one, the
   * enemy African territory of the siege's level 1 of highest value with win >= {@code
   * soglia_sbarco} for {@code cargo} (or, with no cargo, of highest value: the convoy is staged).
   */
  static Meta meta(final Programmi.Turno p, final List<Unit> cargo) {
    final Territory base = baseVicina(p);
    if (base != null) {
      return new Meta(BASE, base, p.zone(base), 100);
    }
    Meta best = null;
    double bestValue = -1;
    for (final Territory t : bersagli(p)) {
      final double win = cargo.isEmpty() ? -1 : vittoria(p, t, cargo);
      if (!cargo.isEmpty() && win < Config.get().sogliaSbarco()) {
        continue;
      }
      final double value = CampagnaOmbra.Calcolo.valoreIndice(t);
      if (value > bestValue) {
        bestValue = value;
        best = new Meta(SBARCO, t, liberi(p, t), win);
      }
    }
    return best == null ? new Meta(NESSUNA, null, List.of(), 0) : best;
  }

  /**
   * E1: the Axis base in Africa of the siege's list nearest by sea to the port (ties: the list's
   * order); null without one.
   */
  @Nullable
  static Territory baseVicina(final Programmi.Turno p) {
    final Territory port = p.porto();
    final List<Territory> portZones = port == null ? List.of() : p.zone(port);
    Territory best = null;
    int bestD = Integer.MAX_VALUE;
    for (final String n : ConfigR4.get().basiAfricaItalia()) {
      final Territory t = p.data.getMap().getTerritoryOrNull(n);
      if (t == null || t.isWater() || !Matches.isTerritoryAllied(p.player).test(t)) {
        continue;
      }
      int d = Integer.MAX_VALUE - 1;
      for (final Territory z : p.zone(t)) {
        d = Math.min(d, portZones.isEmpty() ? 0 : p.distanzaMare(z, portZones));
      }
      if (d < bestD) {
        bestD = d;
        best = t;
      }
    }
    return best;
  }

  /** The enemy coastal territories of the siege's level 1 at war with Italy, by name. */
  static List<Territory> bersagli(final Programmi.Turno p) {
    final List<Territory> out = new ArrayList<>();
    for (final String n : ConfigR4.get().italiaLivello1()) {
      final Territory t = p.data.getMap().getTerritoryOrNull(n);
      if (t != null
          && !t.isWater()
          && !Matches.isTerritoryAllied(p.player).test(t)
          && p.data.getRelationshipTracker().isAtWar(p.player, t.getOwner())
          && !p.zone(t).isEmpty()) {
        out.add(t);
      }
    }
    return out;
  }

  private static List<Territory> liberi(final Programmi.Turno p, final Territory t) {
    return p.zone(t).stream().filter(z -> !z.anyUnitsMatch(Matches.enemyUnit(p.player))).toList();
  }

  static double vittoria(final Programmi.Turno p, final Territory t, final List<Unit> cargo) {
    final List<Unit> defenders = t.getMatches(Matches.enemyUnit(p.player));
    if (defenders.stream().noneMatch(Matches.unitIsNotInfrastructure())) {
      return cargo.isEmpty() ? 0 : 100;
    }
    return p.calc
        .calculateBattleResults(p.proData, t, cargo, defenders, List.of())
        .getWinPercentage();
  }

  // ============================================================================ E4 purchase

  /**
   * E4: warships first, one at a time, until the convoy's arrival zone escorted by the whole
   * Italian fleet near the port is safe (A1), then complete pairs (a transport with its load) or
   * troops for free seats. Records a row {@code italia_convoglio} (purchase).
   */
  static Map<String, Integer> acquisti(
      final Programmi.Turno p, final int pu, final int garrison, final JsonObject turn) {
    final GameState data = p.data;
    final Territory port = p.porto();
    final Map<String, Integer> mix = new LinkedHashMap<>();
    if (port == null || pu <= 0) {
      return mix;
    }
    final List<Territory> portZones = p.zone(port);
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, p.player);
    final Map<String, Integer> seats = new LinkedHashMap<>();
    int capacity = 0;
    for (final UnitType t : data.getUnitTypeList().getAllUnitTypes()) {
      seats.put(t.getName(), t.getUnitAttachment().getTransportCost());
      if ("transport".equals(t.getName())) {
        capacity = t.getUnitAttachment().getTransportCapacity();
      }
    }
    final int places = Assedio.capacita(port);
    // the fleet near the port and the transports there
    final List<Unit> fleet = new ArrayList<>();
    int free = 0;
    int transports = 0;
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater() || p.distanzaMare(z, portZones) > 2) {
        continue;
      }
      for (final Unit u : z.getMatches(Matches.unitIsOwnedBy(p.player).and(Matches.unitIsSea()))) {
        if (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)) {
          free += TransportTracker.getAvailableCapacity(u);
          transports++;
        } else if (!Matches.unitIsCarrier().test(u)) {
          fleet.add(u);
        }
      }
    }
    final List<Unit> waitingUnits = p.inAttesa(port, garrison);
    final int waiting =
        waitingUnits.stream().mapToInt(u -> u.getUnitAttachment().getTransportCost()).sum();
    final Meta meta = meta(p, List.of());
    final Territory arrival = arrivo(p, meta, portZones);
    final List<Unit> oneTransport =
        data.getUnitTypeList()
            .getUnitType("transport")
            .map(ut -> ut.createTemp(1, p.player))
            .orElse(List.of());
    double before = -1;
    double after = -1;
    Map<String, Integer> warships = Map.of();
    if (arrival != null) {
      final Function<Map<String, Integer>, Double> win =
          extra -> {
            final List<Unit> ours = new ArrayList<>(fleet);
            ours.addAll(oneTransport);
            extra.forEach(
                (type, n) ->
                    data.getUnitTypeList()
                        .getUnitType(type)
                        .ifPresent(ut -> ours.addAll(ut.createTemp(n, p.player))));
            return TrasportiProtetti.valuta(p, arrival, ours, List.of()).vittoria();
          };
      before = win.apply(Map.of());
      warships =
          SquadraGibilterra.sostegno(
              win,
              ConfigR4.get().tipiFlottaItalia(),
              costs,
              Set.of(),
              0,
              pu,
              places,
              Config.get().maxNaviPerTurno(),
              TrasportiProtetti.Config.get().soglia());
      after = win.apply(warships);
    }
    // E4: still not safe with the warships that lower the risk: the rest to warships anyway (the
    // fleet grows until the route is safe)
    if (arrival != null && !TrasportiProtetti.sicura(after, TrasportiProtetti.Config.get().soglia())) {
      final int used = warships.values().stream().mapToInt(Integer::intValue).sum();
      final Map<String, Integer> more =
          Impegno.composizione(
              pu - Impegno.costo(data, p.player, warships), ConfigR4.get().tipiFlottaItalia(), costs);
      final Map<String, Integer> all = new LinkedHashMap<>(warships);
      int room = Math.max(0, places - used);
      for (final var e : more.entrySet()) {
        final int n = Math.min(e.getValue(), room);
        if (n > 0) {
          all.merge(e.getKey(), n, Integer::sum);
          room -= n;
        }
      }
      warships = all;
    }
    mix.putAll(warships);
    final int shipCost = Impegno.costo(data, p.player, warships);
    final int shipUnits = warships.values().stream().mapToInt(Integer::intValue).sum();
    // the route safe (or nothing to buy for it): complete pairs with the rest
    final boolean safe = arrival != null && TrasportiProtetti.sicura(after, TrasportiProtetti.Config.get().soglia());
    Map<String, Integer> pairs = Map.of();
    if (safe) {
      pairs =
          SquadraGibilterra.spola(
              Math.max(0, pu - shipCost),
              Math.max(0, places - shipUnits),
              free,
              waiting,
              false,
              costs,
              seats,
              capacity,
              SquadraGibilterra.Config.get());
      pairs.forEach((k, v) -> mix.merge(k, v, Integer::sum));
    }
    final JsonObject a = new JsonObject();
    a.addProperty("fase", "acquisto");
    a.addProperty("stato", meta.stato());
    a.addProperty("base", meta.terra() == null ? "" : meta.terra().getName());
    a.addProperty("zona_arrivo", arrival == null ? "" : arrival.getName());
    a.addProperty("vittoria_nemica_flotta_pct", AlleatiR5.round1(before));
    a.addProperty("vittoria_nemica_dopo_acquisto_pct", AlleatiR5.round1(after));
    a.addProperty("rotta_sicura", safe);
    a.addProperty("pu", pu);
    a.add("navi", SquadraGibilterra.mappa(warships));
    a.add("coppie", SquadraGibilterra.mappa(pairs));
    a.addProperty("pu_spesi", Impegno.costo(data, p.player, mix));
    a.addProperty("truppe_in_attesa", waitingUnits.size());
    a.addProperty("posti_truppe_in_attesa", waiting);
    a.addProperty("trasporti_vicino_al_porto", transports);
    a.addProperty("posti_liberi", free);
    a.add("flotta", TrasportiProtetti.perTipo(fleet));
    registra(
        p,
        a,
        mix.isEmpty() ? "niente" : SquadraGibilterra.mappa(mix).toString(),
        safe ? "SPOLA" : "FLOTTA",
        String.format(
            Locale.ROOT,
            "quota %d PU: navi %s (vittoria nemica nella zona d'arrivo %s con la flotta %.1f%% ->"
                + " %.1f%%), coppie %s; truppe in attesa %d unita' (%d posti)",
            pu,
            warships,
            arrival == null ? "-" : arrival.getName(),
            before,
            after,
            pairs,
            waitingUnits.size(),
            waiting));
    final JsonObject prog = new JsonObject();
    prog.addProperty("nome", "convoglio Italia");
    prog.addProperty("pu", pu);
    prog.add("ordine", SquadraGibilterra.mappa(mix));
    turn.add("programma", prog);
    p.lines.add(
        String.format(
            Locale.ROOT,
            "  R6c convoglio Italia: %d PU -> navi %s (vittoria nemica in %s %.1f%% -> %.1f%%),"
                + " coppie %s",
            pu,
            warships,
            arrival == null ? "-" : arrival.getName(),
            before,
            after,
            pairs));
    return mix;
  }

  /** The convoy's arrival zone: the meta's zone nearest to the port. */
  @Nullable
  static Territory arrivo(final Programmi.Turno p, final Meta meta, final List<Territory> portZones) {
    Territory best = null;
    int bestD = Integer.MAX_VALUE;
    for (final Territory z : meta.zone()) {
      final int d = p.distanzaMare(z, portZones);
      if (d < bestD || (d == bestD && best != null && z.getName().compareTo(best.getName()) < 0)) {
        bestD = d;
        best = z;
      }
    }
    return best;
  }

  // ======================================================================== non combat

  /**
   * E1-E3 in Italy's non-combat move (instead of R4's shuttle): the empty transports come back
   * with their escort to the port (safe stops); loading at the port; the loaded transports sail in
   * convoy toward the arrival zone with the minimum escort (A1); arrived next to an Axis base they
   * unload there. Returns the units Hard leaves.
   */
  static Set<Unit> nonCombattimento(
      final Programmi.Turno p, final IMoveDelegate moveDel, final int garrison) {
    final Set<Unit> held = new LinkedHashSet<>();
    final GameState data = p.data;
    final Territory port = p.porto();
    if (port == null) {
      return held;
    }
    final JsonObject st = SbarcoCoordinato.stato(data.getProperties(), p.alliance);
    final int round = data.getSequence().getRound();
    final List<Territory> portZones = p.zone(port);
    final JsonArray entries = scorte(st);
    // B2: the escort of last turns' convoys and their empty transports come back together
    int back = 0;
    final Map<Territory, List<Unit>> returning = new LinkedHashMap<>();
    for (final Unit tr : trasporti(p)) {
      final Territory z = p.dove(tr);
      if (z == null
          || portZones.contains(z)
          || tr.isTransporting()
          || !tr.getUnloaded().isEmpty()
          || !Matches.unitHasMovementLeft().test(tr)) {
        continue;
      }
      returning.computeIfAbsent(z, k -> new ArrayList<>()).add(tr);
    }
    final Set<String> escortIds = new HashSet<>();
    entries.forEach(
        e -> e.getAsJsonObject().getAsJsonArray("scorta").forEach(x -> escortIds.add(x.getAsString())));
    for (final var e : returning.entrySet()) {
      final List<Unit> group = new ArrayList<>(e.getValue());
      e.getKey()
          .getMatches(
              Matches.unitIsOwnedBy(p.player)
                  .and(Matches.unitHasMovementLeft())
                  .and(u -> escortIds.contains(u.getId().toString())))
          .forEach(group::add);
      if (TrasportiProtetti.naviga(
              p, group, e.getKey(), portZones, held, moveDel, "convoglio_rientro", st)
          .isPresent()) {
        back += e.getValue().size();
      }
    }
    // loading at the port
    final List<Unit> waiting = new ArrayList<>(p.inAttesa(port, garrison));
    waiting.removeIf(u -> !Matches.unitHasMovementLeft().test(u));
    waiting.sort(
        Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
            .thenComparing(u -> u.getId().toString()));
    int loaded = 0;
    for (final Territory z : portZones) {
      for (final Unit tr :
          z.getMatches(
              Matches.unitIsSeaTransportButNotCombatSeaTransport()
                  .and(Matches.unitIsOwnedBy(p.player)))) {
        if (!tr.getUnloaded().isEmpty() || held.contains(tr)) {
          continue;
        }
        int room = TransportTracker.getAvailableCapacity(tr);
        for (final Unit u : new ArrayList<>(waiting)) {
          final int cost = u.getUnitAttachment().getTransportCost();
          if (cost > room) {
            continue;
          }
          if (moveDel
              .performMove(new MoveDescription(List.of(u), new Route(port, z), Map.of(u, tr)))
              .isEmpty()) {
            room -= cost;
            waiting.remove(u);
            loaded++;
          }
        }
      }
    }
    // the convoys: per zone the loaded transports and the minimum escort
    int sailed = 0;
    int unloaded = 0;
    final Meta now = meta(p, List.of());
    String state = now.stato();
    String target = now.terra() == null ? "" : now.terra().getName();
    final Map<Territory, List<Unit>> loadedByZone = new LinkedHashMap<>();
    for (final Unit tr : trasporti(p)) {
      final Territory z = p.dove(tr);
      if (z != null
          && tr.isTransporting()
          && tr.getUnloaded().isEmpty()
          && tr.getTransporting().stream().allMatch(u -> u.isOwnedBy(p.player))) {
        loadedByZone.computeIfAbsent(z, k -> new ArrayList<>()).add(tr);
      }
    }
    for (final var e : loadedByZone.entrySet()) {
      final Territory z = e.getKey();
      final List<Unit> group = e.getValue();
      final List<Unit> cargo = new ArrayList<>();
      group.forEach(tr -> cargo.addAll(tr.getTransporting()));
      final Meta meta = meta(p, cargo);
      state = meta.stato();
      target = meta.terra() == null ? "" : meta.terra().getName();
      if (meta.terra() == null || meta.zone().isEmpty()) {
        held.addAll(group);
        held.addAll(cargo);
        continue;
      }
      if (meta.zone().contains(z)) {
        if (BASE.equals(meta.stato())) {
          unloaded += scarica(p, group, z, meta.terra(), moveDel, held);
        } else {
          held.addAll(group);
          held.addAll(cargo);
        }
        continue;
      }
      final Optional<Territory> to = convoglio(p, group, z, meta.zone(), moveDel, held, st);
      if (to.isPresent()) {
        sailed += group.size();
        if (meta.zone().contains(to.get()) && BASE.equals(meta.stato())) {
          unloaded += scarica(p, group, to.get(), meta.terra(), moveDel, held);
        }
      }
    }
    // the escorts with transports anywhere stay with them
    final JsonArray keep = new JsonArray();
    for (final JsonElement e : entries) {
      final JsonObject o = e.getAsJsonObject();
      boolean alive = false;
      for (final JsonElement x : o.getAsJsonArray("trasporti")) {
        final Unit u = trova(p, x.getAsString());
        final Territory at = u == null ? null : p.dove(u);
        alive |= at != null && !portZones.contains(at);
      }
      if (alive) {
        keep.add(o);
        for (final JsonElement x : o.getAsJsonArray("scorta")) {
          final Unit u = trova(p, x.getAsString());
          if (u != null) {
            held.add(u);
          }
        }
      }
    }
    st.add("convoglio_scorte", keep);
    for (final Unit tr : trasporti(p)) {
      if (tr.isTransporting()) {
        held.add(tr);
        held.addAll(tr.getTransporting());
      }
    }
    final JsonObject a = new JsonObject();
    a.addProperty("fase", "movimento");
    a.addProperty("stato", state);
    a.addProperty("base", target);
    a.addProperty("caricate", loaded);
    a.addProperty("trasporti_partiti", sailed);
    a.addProperty("scaricate", unloaded);
    a.addProperty("trasporti_rientrati", back);
    a.addProperty("truppe_rimaste_al_porto", waiting.size());
    registra(
        p,
        a,
        state,
        "SPOLA",
        String.format(
            Locale.ROOT,
            "convoglio Italia (%s %s): caricate %d, trasporti partiti %d, scaricate %d, vuoti in"
                + " rientro %d, truppe al porto %d",
            state,
            target,
            loaded,
            sailed,
            unloaded,
            back,
            waiting.size()),
        st);
    SbarcoCoordinato.salva(data.getProperties(), p.alliance, st);
    p.lines.add(
        String.format(
            Locale.ROOT,
            "  R6c convoglio Italia (%s %s, round %d): caricate %d, partiti %d trasporti, scaricate"
                + " %d, rientro %d, truppe al porto %d",
            state,
            target,
            round,
            loaded,
            sailed,
            unloaded,
            back,
            waiting.size()));
    return held;
  }

  /**
   * E3: {@code transports} sail from {@code z} toward {@code zones}, ending in the farthest zone
   * within movement where, with the minimum escort of Italian warships of {@code z} (one at a time,
   * the one that lowers the enemy's win most), A1 holds. Records {@code trasporti_sicurezza}.
   */
  static Optional<Territory> convoglio(
      final Programmi.Turno p,
      final List<Unit> transports,
      final Territory z,
      final List<Territory> zones,
      final IMoveDelegate moveDel,
      final Set<Unit> held,
      final JsonObject st) {
    final List<Unit> cargo = new ArrayList<>();
    transports.forEach(tr -> cargo.addAll(tr.getTransporting()));
    final List<Unit> all = new ArrayList<>(transports);
    all.addAll(cargo);
    final Predicate<Territory> free =
        x ->
            x.isWater()
                && ProMatches.territoryCanMoveSeaUnitsThrough(p.player, false).test(x)
                && !x.anyUnitsMatch(Matches.enemyUnit(p.player));
    Route best = null;
    for (final Territory to : zones) {
      final Optional<Route> r = p.data.getMap().getRouteForUnits(z, to, free, all, p.player);
      if (r.isPresent() && (best == null || r.get().numberOfSteps() < best.numberOfSteps())) {
        best = r.get();
      }
    }
    // E3: the Italian warships (no carrier) anywhere, not already escorting
    final List<Unit> candidates = new ArrayList<>();
    for (final Territory t : p.data.getMap().getTerritories()) {
      if (t.isWater()) {
        candidates.addAll(
            t.getMatches(
                Matches.unitIsOwnedBy(p.player)
                    .and(AlleatiR5.naveDaGuerra())
                    .and(Matches.unitIsCarrier().negate())
                    .and(Matches.unitHasMovementLeft())
                    .and(u -> !held.contains(u))));
      }
    }
    candidates.sort(Comparator.comparing(u -> u.getId().toString()));
    final JsonArray alt = new JsonArray();
    Territory end = null;
    List<Unit> escort = List.of();
    TrasportiProtetti.Sicurezza ok = null;
    if (best != null) {
      final int range =
          transports.stream().mapToInt(u -> u.getMovementLeft().intValue()).min().orElse(0);
      final List<Territory> steps = best.getSteps();
      for (int i = Math.min(range, steps.size()); i >= 1 && end == null; i--) {
        final Territory x = steps.get(i - 1);
        final Route r = new Route(z, steps.subList(0, i).toArray(new Territory[0]));
        final List<Unit> reach = new ArrayList<>();
        final Map<Unit, Route> own = new LinkedHashMap<>();
        for (final Unit c : candidates) {
          final Territory at = p.dove(c);
          if (at == null) {
            continue;
          }
          if (at.equals(z)) {
            if (c.getMovementLeft().intValue() >= i) {
              reach.add(c);
            }
            continue;
          }
          if (at.equals(x)) {
            reach.add(c);
            continue;
          }
          final Optional<Route> cr = p.data.getMap().getRouteForUnits(at, x, free, List.of(c), p.player);
          if (cr.isPresent() && cr.get().numberOfSteps() <= c.getMovementLeft().intValue()) {
            reach.add(c);
            own.put(c, cr.get());
          }
        }
        final List<Unit> chosen = new ArrayList<>();
        TrasportiProtetti.Sicurezza s = TrasportiProtetti.valuta(p, x, transports, List.of());
        while (!s.sicura() && !reach.isEmpty()) {
          Unit pick = null;
          TrasportiProtetti.Sicurezza pickS = null;
          final Set<String> tried = new HashSet<>();
          for (final Unit c : reach) {
            if (!tried.add(c.getType().getName())) {
              continue;
            }
            final List<Unit> trial = new ArrayList<>(transports);
            trial.addAll(chosen);
            trial.add(c);
            final TrasportiProtetti.Sicurezza y = TrasportiProtetti.valuta(p, x, trial, List.of());
            if (pickS == null || y.vittoria() < pickS.vittoria()) {
              pick = c;
              pickS = y;
            }
          }
          chosen.add(pick);
          reach.remove(pick);
          s = pickS;
        }
        final JsonObject a = s.json();
        a.add("scorta", TrasportiProtetti.perTipo(chosen));
        alt.add(a);
        if (!s.sicura()) {
          continue;
        }
        final List<Unit> moving = new ArrayList<>(all);
        chosen.stream().filter(c -> z.equals(p.dove(c))).forEach(moving::add);
        final Optional<String> error = moveDel.performMove(new MoveDescription(moving, r));
        if (error.isEmpty()) {
          // the escorts from other zones join the convoy there
          for (final Unit c : chosen) {
            if (own.containsKey(c)) {
              moveDel.performMove(new MoveDescription(List.of(c), own.get(c)));
            }
          }
          end = x;
          escort = chosen;
          ok = s;
        } else {
          ProLogger.warn("[Pro+] R6c: rotta del convoglio non riuscita " + error.get());
        }
      }
    }
    held.addAll(all);
    held.addAll(escort);
    if (end != null) {
      final JsonObject entry = new JsonObject();
      entry.addProperty("round", p.data.getSequence().getRound());
      entry.addProperty("zona", end.getName());
      final JsonArray tr = new JsonArray();
      transports.forEach(u -> tr.add(u.getId().toString()));
      entry.add("trasporti", tr);
      final JsonArray es = new JsonArray();
      escort.forEach(u -> es.add(u.getId().toString()));
      entry.add("scorta", es);
      scorte(st).add(entry);
    }
    final JsonObject par = TrasportiProtetti.parametri("convoglio_italia", z.getName());
    par.addProperty("trasporti", transports.size());
    par.addProperty("carichi", true);
    par.addProperty("navi_da_guerra", escort.size());
    TrasportiProtetti.registra(
        p.data,
        st,
        p.player,
        "trasporti_sicurezza",
        alt,
        end == null ? "fermi in " + z.getName() : end.getName(),
        end == null
            ? "FERMO"
            : escort.isEmpty() ? TrasportiProtetti.SICURA : TrasportiProtetti.SCORTATA,
        end == null
            ? "convoglio italiano: nessuna sosta sicura nemmeno con la flotta della zona"
            : String.format(
                Locale.ROOT,
                "convoglio italiano: %d trasporti e %d navi di scorta fino a %s (vittoria nemica"
                    + " %.1f%%)",
                transports.size(),
                escort.size(),
                end.getName(),
                ok.vittoria()),
        par);
    return Optional.ofNullable(end);
  }

  private static int scarica(
      final Programmi.Turno p,
      final List<Unit> group,
      final Territory z,
      final Territory land,
      final IMoveDelegate moveDel,
      final Set<Unit> held) {
    int n = 0;
    for (final Unit tr : group) {
      final List<Unit> cargo = new ArrayList<>(tr.getTransporting());
      if (cargo.isEmpty()) {
        continue;
      }
      if (moveDel.performMove(new MoveDescription(cargo, new Route(z, land))).isEmpty()) {
        n += cargo.size();
        held.add(tr);
        held.addAll(cargo);
      }
    }
    return n;
  }

  // ============================================================================== combat

  /**
   * E1 without an Axis base: in the combat move (before Hard's) the loaded convoy next to an enemy
   * African target lands on the one of highest value with win >= {@code soglia_sbarco}. Returns
   * the units moved.
   */
  public static Set<Unit> combattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final GamePlayer player) {
    final Set<Unit> moved = new LinkedHashSet<>();
    if (!attivo(proData.getData(), player)
        || Assedio.baseAfrica(proData.getData(), player) != null) {
      return moved;
    }
    try {
      final GameState data = proData.getData();
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final List<String> lines = new ArrayList<>();
      final Programmi.Turno p =
          new Programmi.Turno(
              proData,
              calc,
              player,
              alliance,
              new CampagnaOmbra.Calcolo(proData, calc, player, alliance, CampagnaOmbra.Config.get()),
              lines);
      final Map<Territory, List<Unit>> loadedByZone = new LinkedHashMap<>();
      for (final Unit tr : trasporti(p)) {
        final Territory z = p.dove(tr);
        if (z != null && tr.isTransporting()) {
          loadedByZone.computeIfAbsent(z, k -> new ArrayList<>()).add(tr);
        }
      }
      final JsonObject st = SbarcoCoordinato.stato(data.getProperties(), alliance);
      for (final var e : loadedByZone.entrySet()) {
        final List<Unit> cargo = new ArrayList<>();
        e.getValue().forEach(tr -> cargo.addAll(tr.getTransporting()));
        Territory best = null;
        double bestValue = -1;
        double bestWin = 0;
        for (final Territory t : bersagli(p)) {
          if (!data.getMap().getNeighbors(e.getKey()).contains(t)) {
            continue;
          }
          final double win = vittoria(p, t, cargo);
          final double value = CampagnaOmbra.Calcolo.valoreIndice(t);
          if (win >= Config.get().sogliaSbarco() && value > bestValue) {
            best = t;
            bestValue = value;
            bestWin = win;
          }
        }
        if (best == null) {
          continue;
        }
        int landed = 0;
        for (final Unit tr : e.getValue()) {
          final List<Unit> c = new ArrayList<>(tr.getTransporting());
          if (moveDel.performMove(new MoveDescription(c, new Route(e.getKey(), best))).isEmpty()) {
            moved.add(tr);
            moved.addAll(c);
            landed += c.size();
          }
        }
        final JsonObject a = new JsonObject();
        a.addProperty("fase", "sbarco");
        a.addProperty("stato", SBARCO);
        a.addProperty("base", best.getName());
        a.addProperty("zona", e.getKey().getName());
        a.addProperty("vittoria_pct", AlleatiR5.round1(bestWin));
        a.addProperty("scaricate", landed);
        registra(
            p,
            a,
            best.getName(),
            "ESEGUITO",
            "convoglio italiano: sbarco su " + best.getName() + " con " + landed + " unita'",
            st);
      }
      SbarcoCoordinato.salva(data.getProperties(), alliance, st);
      lines.forEach(ProLogger::info);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R6c, sbarco del convoglio: " + e);
    }
    return moved;
  }

  /** The convoy's loaded transports and escorts stay out of Hard's combat move. */
  public static Set<Unit> trattenute(final GameState data, final GamePlayer player) {
    final Set<Unit> out = new LinkedHashSet<>();
    if (!attivo(data, player)) {
      return out;
    }
    final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
    final JsonObject st = SbarcoCoordinato.stato(data.getProperties(), alliance);
    final Set<String> escorts = new HashSet<>();
    scorte(st)
        .forEach(
            e -> e.getAsJsonObject().getAsJsonArray("scorta").forEach(x -> escorts.add(x.getAsString())));
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection()) {
        if (!u.isOwnedBy(player)) {
          continue;
        }
        if (escorts.contains(u.getId().toString())
            || (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)
                && u.isTransporting())) {
          out.add(u);
          out.addAll(u.getTransporting());
        }
      }
    }
    return out;
  }

  // ============================================================================== helpers

  static JsonArray scorte(final JsonObject st) {
    return SbarcoCoordinato.arr(st, "convoglio_scorte");
  }

  private static List<Unit> trasporti(final Programmi.Turno p) {
    final List<Unit> out = new ArrayList<>();
    for (final Territory z : p.data.getMap().getTerritories()) {
      if (z.isWater()) {
        out.addAll(
            z.getMatches(
                Matches.unitIsSeaTransportButNotCombatSeaTransport()
                    .and(Matches.unitIsOwnedBy(p.player))));
      }
    }
    out.sort(Comparator.comparing(u -> u.getId().toString()));
    return out;
  }

  @Nullable
  private static Unit trova(final Programmi.Turno p, final String id) {
    for (final Territory t : p.data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection()) {
        if (u.getId().toString().equals(id)) {
          return u;
        }
      }
    }
    return null;
  }

  private static void registra(
      final Programmi.Turno p,
      final JsonObject a,
      final String choice,
      final String code,
      final String why) {
    registra(p, a, choice, code, why, SbarcoCoordinato.stato(p.data.getProperties(), p.alliance));
    // the sequence of the ids is kept in the state
  }

  private static void registra(
      final Programmi.Turno p,
      final JsonObject a,
      final String choice,
      final String code,
      final String why,
      final JsonObject st) {
    final JsonArray alt = new JsonArray();
    alt.add(a);
    final JsonObject par = new JsonObject();
    par.addProperty("soglia_trasporti", TrasportiProtetti.Config.get().soglia());
    par.addProperty("soglia_sbarco", Config.get().sogliaSbarco());
    TrasportiProtetti.registra(p.data, st, p.player, "italia_convoglio", alt, choice, code, why, par);
    SbarcoCoordinato.salva(p.data.getProperties(), p.alliance, st);
  }
}
