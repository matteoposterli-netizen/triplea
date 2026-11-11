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
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step R2c "PREPARAZIONE E PROGRAMMI": the permanent programs of the nations that cannot attack
 * yet, behind the same switch as {@link Impegno} (off by default = today's behavior).
 *
 * <ul>
 *   <li><b>USA</b> (from R1, also before the war): {@code quota_programma_usa}% of the PUs after
 *       the capital's garrison of case c, by the rule of the BALANCED LOAD ({@link
 *       #caricoBilanciato}); in the non-combat move the shuttle ({@link Turno#spola}): loaded
 *       transports go toward the forward base (United Kingdom) and unload there, the empty ones go
 *       back to load, only through safe sea zones. Before the war the map lets the USA neither
 *       unload in the United Kingdom (neutral to the British) nor enter the zones near Europe: the
 *       transports load and wait ready on the east coast.
 *   <li><b>United Kingdom</b>: the landing force at home with the same balanced load (after the
 *       garrisons of London and Egypt and the campaign on Egypt); in the Middle East the pro-Allied
 *       neutrals taken by entering them and the pro-Axis Iraq attacked only with units already
 *       there ({@code medio_oriente}).
 * </ul>
 *
 * <p>The troops brought to the United Kingdom and the transports stay Hard's for the assaults: in
 * the combat move nothing is held.
 */
public final class Programmi {
  private Programmi() {}

  static final String USA = "Americans";
  static final String UK = "British";

  // ================================================================================ config

  /** Values of {@code impegno.r2c} in {@code stratega-llm.json} (all "scelto", none tuned). */
  public record Config(
      double sogliaEsecuzione,
      double sogliaBaseSicura,
      List<String> nazioniCampagnaTerra,
      List<String> nazioniSoloFattibili,
      List<String> territoriUkCampagna,
      Map<String, List<String>> presidiPrioritari,
      double quotaProgrammaUsa,
      double quotaForzaSbarcoUk,
      String baseAvanzataUsa,
      String portoUsa,
      String portoUk,
      List<String> tipiSpola,
      String tipoTrasporto,
      String tipoScorta,
      int maxScortaPerTurno,
      double sogliaMareSicuro,
      int maxScortaMossa,
      List<String> occupaSenzaCombattere,
      List<String> attacca,
      List<String> esclusi) {

    private static @Nullable Config cache;

    public static synchronized Config get() {
      if (cache == null) {
        try (InputStream in =
            Programmi.class.getClassLoader().getResourceAsStream(StrategaConfig.RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + StrategaConfig.RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject()
                      .getAsJsonObject("impegno")
                      .getAsJsonObject("r2c"));
        } catch (final IOException e) {
          throw new IllegalStateException("File dello stratega non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject o) {
      final Map<String, List<String>> garrisons = new LinkedHashMap<>();
      for (final var e : v(o, "presidi_prioritari").getAsJsonObject().entrySet()) {
        garrisons.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
      }
      final JsonObject me = o.getAsJsonObject("medio_oriente");
      return new Config(
          v(o, "soglia_esecuzione").getAsDouble(),
          v(o, "soglia_base_sicura").getAsDouble(),
          strings(v(o, "nazioni_campagna_terra").getAsJsonArray()),
          strings(v(o, "nazioni_solo_fattibili").getAsJsonArray()),
          strings(v(o, "territori_uk_campagna").getAsJsonArray()),
          Map.copyOf(garrisons),
          v(o, "quota_programma_usa").getAsDouble(),
          v(o, "quota_forza_sbarco_uk").getAsDouble(),
          v(o, "base_avanzata_usa").getAsString(),
          v(o, "porto_usa").getAsString(),
          v(o, "porto_uk").getAsString(),
          strings(v(o, "tipi_spola").getAsJsonArray()),
          v(o, "tipo_trasporto").getAsString(),
          v(o, "tipo_scorta").getAsString(),
          v(o, "max_scorta_per_turno").getAsInt(),
          v(o, "soglia_mare_sicuro").getAsDouble(),
          v(o, "max_scorta_mossa").getAsInt(),
          strings(v(me, "occupa_senza_combattere").getAsJsonArray()),
          strings(v(me, "attacca").getAsJsonArray()),
          strings(v(me, "esclusi").getAsJsonArray()));
    }

    private static JsonElement v(final JsonObject o, final String key) {
      return o.getAsJsonObject(key).get("valore");
    }

    private static List<String> strings(final JsonArray a) {
      final List<String> out = new ArrayList<>();
      a.forEach(e -> out.add(e.getAsString()));
      return List.copyOf(out);
    }

    /** The nation may have a land campaign (B4). */
    boolean campagnaTerra(final String nation) {
      return nazioniCampagnaTerra.contains(nation);
    }
  }

  // ============================================================================ pure rules

  /**
   * The rule of the BALANCED LOAD: escort first (at most {@code maxScortaPerTurno}), then troops of
   * {@code tipiSpola} in rotation while the troops waiting at the port plus those bought fit the
   * free room of the transports at the port by the next turn; a transport only when no idle empty
   * usable transport exists and the troops no longer fit (and a troop can still follow it).
   *
   * @param fermiVuoti idle empty transports usable from the port
   * @param postiLiberi free room (transport cost) of the transports at the port by the next turn
   * @param inAttesa transport cost of the troops waiting at the port
   * @param scorta escort ships the shuttle's zones need
   */
  static Map<String, Integer> caricoBilanciato(
      final int pu,
      final int fermiVuoti,
      final int postiLiberi,
      final int inAttesa,
      final int scorta,
      final Map<String, Integer> costs,
      final Map<String, Integer> transportCosts,
      final int capacitaTrasporto,
      final Config cfg) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    int left = pu;
    final int escortCost = costs.getOrDefault(cfg.tipoScorta(), 0);
    for (int i = 0; i < Math.min(scorta, cfg.maxScortaPerTurno()); i++) {
      if (escortCost <= 0 || escortCost > left) {
        break;
      }
      out.merge(cfg.tipoScorta(), 1, Integer::sum);
      left -= escortCost;
    }
    final List<String> types = cfg.tipiSpola();
    final int cheapest =
        types.stream()
            .mapToInt(t -> costs.getOrDefault(t, 0))
            .filter(c -> c > 0)
            .min()
            .orElse(Integer.MAX_VALUE);
    final int transportCost = costs.getOrDefault(cfg.tipoTrasporto(), 0);
    int room = postiLiberi;
    int need = inAttesa;
    int index = 0;
    while (true) {
      boolean bought = false;
      for (int k = 0; k < types.size() && !types.isEmpty(); k++) {
        final String t = types.get((index + k) % types.size());
        final int c = costs.getOrDefault(t, 0);
        final int tc = transportCosts.getOrDefault(t, 0);
        if (c > 0 && c <= left && tc > 0 && need + tc <= room) {
          out.merge(t, 1, Integer::sum);
          left -= c;
          need += tc;
          index += k + 1;
          bought = true;
          break;
        }
      }
      if (bought) {
        continue;
      }
      if (fermiVuoti == 0
          && transportCost > 0
          && capacitaTrasporto > 0
          && left >= transportCost + cheapest) {
        out.merge(cfg.tipoTrasporto(), 1, Integer::sum);
        left -= transportCost;
        room += capacitaTrasporto;
        continue;
      }
      return out;
    }
  }

  // ========================================================================== one turn

  /** The programs in one phase of one nation's turn (called by {@link Impegno}). */
  static final class Turno {
    final ProData proData;
    final ProOddsCalculator calc;
    final GamePlayer player;
    final GameState data;
    final String alliance;
    final CampagnaOmbra.Calcolo c;
    final List<String> lines;
    final Config cfg = Config.get();
    final Map<String, Double> seaSafety = new HashMap<>();
    @Nullable ProTerritoryManager enemy;
    @Nullable IMoveDelegate moveDel;

    Turno(
        final ProData proData,
        final ProOddsCalculator calc,
        final GamePlayer player,
        final String alliance,
        final CampagnaOmbra.Calcolo c,
        final List<String> lines) {
      this.proData = proData;
      this.calc = calc;
      this.player = player;
      this.data = proData.getData();
      this.alliance = alliance;
      this.c = c;
      this.lines = lines;
    }

    boolean usa() {
      return USA.equals(player.getName());
    }

    boolean uk() {
      return UK.equals(player.getName());
    }

    @Nullable
    Territory porto() {
      final Territory t =
          data.getMap().getTerritoryOrNull(usa() ? cfg.portoUsa() : uk() ? cfg.portoUk() : "");
      return t != null && t.isOwnedBy(player) && t.anyUnitsMatch(Matches.unitCanProduceUnits())
          ? t
          : null;
    }

    List<Territory> zone(@Nullable final Territory land) {
      if (land == null) {
        return List.of();
      }
      return data.getMap().getNeighbors(land, Matches.territoryIsWater()).stream()
          .sorted(Comparator.comparing(Territory::getName))
          .toList();
    }

    @Nullable
    Territory base() {
      if (!usa()) {
        return null;
      }
      // step R3a: the base follows the alliance's theater (or a bridgehead held)
      if (SbarcoCoordinato.attivo(data, player)) {
        final Optional<String> b = SbarcoCoordinato.baseCorrente(data, alliance);
        if (b.isPresent()) {
          return data.getMap().getTerritoryOrNull(b.get());
        }
      }
      return data.getMap().getTerritoryOrNull(cfg.baseAvanzataUsa());
    }

    /** The USA can unload at the forward base (allied to its owner: not before the war). */
    boolean puoScaricare() {
      final Territory b = base();
      return b != null && Matches.isTerritoryAllied(player).test(b);
    }

    private Predicate<Territory> mare() {
      return ProMatches.territoryCanMoveSeaUnitsThrough(player, false);
    }

    /** The shuttle's sea zones: the port's, the route to the nearest base zone, the base's. */
    List<Territory> zoneSpola() {
      if (usa() && puoScaricare()) {
        return zoneSpolaVerso(base());
      }
      return List.copyOf(new LinkedHashSet<>(zone(porto())));
    }

    /** Step R3a: the shuttle's sea zones toward {@code base} (port, shortest route, base). */
    List<Territory> zoneSpolaVerso(@Nullable final Territory base) {
      final Set<Territory> out = new LinkedHashSet<>(zone(porto()));
      if (base != null) {
        final List<Territory> baseZones = zone(base);
        out.addAll(baseZones);
        Route best = null;
        for (final Territory from : zone(porto())) {
          for (final Territory to : baseZones) {
            final Optional<Route> r = data.getMap().getRoute(from, to, mare());
            if (r.isPresent() && (best == null || r.get().numberOfSteps() < best.numberOfSteps())) {
              best = r.get();
            }
          }
        }
        if (best != null) {
          out.addAll(best.getSteps());
        }
      }
      return List.copyOf(out);
    }

    private List<Unit> trasporti() {
      final List<Unit> out = new ArrayList<>();
      for (final Territory z : data.getMap().getTerritories()) {
        if (z.isWater()) {
          out.addAll(
              z.getMatches(
                  Matches.unitIsSeaTransportButNotCombatSeaTransport()
                      .and(Matches.unitIsOwnedBy(player))));
        }
      }
      out.sort(Comparator.comparing(u -> u.getId().toString()));
      return out;
    }

    @Nullable
    private Territory dove(final Unit u) {
      for (final Territory t : data.getMap().getTerritories()) {
        if (t.getUnitCollection().contains(u)) {
          return t;
        }
      }
      return null;
    }

    int distanzaMare(final Territory from, final Collection<Territory> to) {
      int best = Integer.MAX_VALUE;
      for (final Territory t : to) {
        final int d = from.equals(t) ? 0 : data.getMap().getDistance(from, t, mare());
        if (d >= 0 && d < best) {
          best = d;
        }
      }
      return best;
    }

    /**
     * Idle empty usable transports: within 2 zones of the port's zones and not transporting (the
     * balanced load's definition; step R3a's cap on Hard's transports).
     */
    int fermiVuoti() {
      final List<Territory> portZones = zone(porto());
      if (portZones.isEmpty()) {
        return 0;
      }
      int idle = 0;
      for (final Unit tr : trasporti()) {
        final Territory z = dove(tr);
        if (z != null && distanzaMare(z, portZones) <= 2 && !tr.isTransporting()) {
          idle++;
        }
      }
      return idle;
    }

    /** The own land units waiting at the port (transportable), minus the garrison of case c. */
    private List<Unit> inAttesa(final Territory port, final int garrison) {
      final List<Unit> units =
          new ArrayList<>(
              port.getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsLand())
                      .and(Matches.unitIsNotInfrastructure())
                      .and(u -> u.getUnitAttachment().getTransportCost() > 0)
                      .and(u -> u.getUnitAttachment().getAttack(u.getOwner()) > 0)));
      // the garrison: the cheapest stay (infantry first)
      units.sort(
          Comparator.comparingInt((Unit u) -> u.getUnitAttachment().getTransportCost())
              .thenComparing(u -> u.getId().toString()));
      return units.subList(Math.min(garrison, units.size()), units.size());
    }

    // --------------------------------------------------------------------- purchase

    /**
     * The program's purchase of the turn with {@code pu} PUs: the types by the balanced load.
     * Writes the diary and {@code turn.programma}; empty without a port or PUs.
     */
    Map<String, Integer> acquisti(final int pu, final int garrison, final JsonObject turn) {
      final Territory port = porto();
      final JsonObject p = new JsonObject();
      turn.add("programma", p);
      p.addProperty("nome", usa() ? "USA" : "forza da sbarco UK");
      p.addProperty("pu", pu);
      if (port == null || pu <= 0) {
        lines.add(
            "  Programma "
                + (usa() ? "USA" : "Regno Unito")
                + ": "
                + (port == null ? "nessun porto con fabbrica propria" : "0 PU")
                + " -> tutto a Hard");
        p.addProperty("pu", 0);
        return Map.of();
      }
      final List<Territory> portZones = zone(port);
      int idleEmpty = 0;
      int room = 0;
      int loaded = 0;
      int total = 0;
      for (final Unit tr : trasporti()) {
        total++;
        final Territory z = dove(tr);
        if (z == null || distanzaMare(z, portZones) > 2) {
          continue;
        }
        room += TransportTracker.getAvailableCapacity(tr);
        if (tr.isTransporting()) {
          loaded++;
        } else {
          idleEmpty++;
        }
      }
      final int waiting =
          inAttesa(port, garrison).stream()
              .mapToInt(u -> u.getUnitAttachment().getTransportCost())
              .sum();
      final int escort = scortaNecessaria(portZones);
      final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
      final Map<String, Integer> tcs = new HashMap<>();
      int capacity = 0;
      for (final UnitType t : data.getUnitTypeList().getAllUnitTypes()) {
        tcs.put(t.getName(), t.getUnitAttachment().getTransportCost());
        if (t.getName().equals(cfg.tipoTrasporto())) {
          capacity = t.getUnitAttachment().getTransportCapacity();
        }
      }
      final Map<String, Integer> mix =
          caricoBilanciato(pu, idleEmpty, room, waiting, escort, costs, tcs, capacity, cfg);
      int spent = 0;
      for (final var e : mix.entrySet()) {
        spent += costs.getOrDefault(e.getKey(), 0) * e.getValue();
      }
      p.addProperty("porto", port.getName());
      p.addProperty("trasporti", total);
      p.addProperty("trasporti_fermi_vuoti_al_porto", idleEmpty);
      p.addProperty("trasporti_carichi_al_porto", loaded);
      p.addProperty("posti_liberi", room);
      p.addProperty("truppe_in_attesa", waiting);
      p.addProperty("scorta_necessaria", escort);
      p.addProperty("pu_ordine", spent);
      final JsonObject m = new JsonObject();
      mix.forEach(m::addProperty);
      p.add("ordine", m);
      lines.add(
          String.format(
              Locale.ROOT,
              "  Programma %s: %d PU; porto %s: trasporti %d (al porto entro 2 zone: %d fermi vuoti,"
                  + " %d carichi), posti liberi %d, truppe in attesa %d, scorta necessaria %d ->"
                  + " carico bilanciato: %s (%d PU), il resto a Hard",
              usa() ? "USA" : "Regno Unito (forza da sbarco)",
              pu,
              port.getName(),
              total,
              idleEmpty,
              loaded,
              room,
              waiting,
              escort,
              mix.isEmpty()
                  ? "niente"
                  : String.join(
                      ", ",
                      mix.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList()),
              spent));
      return mix;
    }

    /**
     * Escort ships the shuttle needs: destroyers added (temporary) to the own ships at the port
     * until the worst zone of the shuttle has a realistic counterattack within {@code
     * soglia_mare_sicuro}; at most {@code max_scorta_per_turno}.
     */
    private int scortaNecessaria(final List<Territory> portZones) {
      final List<Territory> zones = zoneSpola();
      if (zones.isEmpty() || portZones.isEmpty()) {
        return 0;
      }
      final List<Unit> fleet = new ArrayList<>();
      for (final Territory z : portZones) {
        fleet.addAll(
            z.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(Matches.unitIsSea())
                    .and(Matches.unitCanEvade().negate())));
      }
      final Optional<UnitType> destroyer = data.getUnitTypeList().getUnitType(cfg.tipoScorta());
      if (destroyer.isEmpty()) {
        return 0;
      }
      for (int k = 0; k <= cfg.maxScortaPerTurno(); k++) {
        final List<Unit> group = new ArrayList<>(fleet);
        group.addAll(destroyer.get().createTemp(k, player));
        double worst = 0;
        for (final Territory z : zones) {
          worst = Math.max(worst, vittoriaNemicaMare(z, group));
        }
        if (worst <= cfg.sogliaMareSicuro()) {
          return k;
        }
      }
      return cfg.maxScortaPerTurno();
    }

    /**
     * The enemy's win% in {@code z} with the realistic counterattack (the enemy ships and planes in
     * reach, the commitment share A3, most expensive first) against the allied ships there plus
     * {@code ours}.
     */
    double vittoriaNemicaMare(final Territory z, final Collection<Unit> ours) {
      if (enemy == null) {
        enemy = new ProTerritoryManager(calc, proData);
        final List<Territory> all = new ArrayList<>();
        for (final Territory t : data.getMap().getTerritories()) {
          if (t.isWater()) {
            all.add(t);
          }
        }
        enemy.populateEnemyAttackOptions(List.of(), all);
      }
      final ProTerritory max = enemy.getEnemyAttackOptions().getMax(z);
      if (max == null || max.getMaxUnits().isEmpty()) {
        return 0;
      }
      final List<Unit> all = new ArrayList<>(max.getMaxUnits());
      all.sort(Comparator.comparingDouble((Unit u) -> -c.tuv(List.of(u))));
      final double share =
          StimaUnica.impegno(data, alliance, z, StimaUnica.TipoBersaglio.CONQUISTA).frazione();
      final double total = c.tuv(all);
      final List<Unit> counter = new ArrayList<>();
      double sum = 0;
      for (final Unit u : all) {
        if (sum >= total * share) {
          break;
        }
        counter.add(u);
        sum += c.tuv(List.of(u));
      }
      final List<Unit> defenders =
          new ArrayList<>(z.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsSea())));
      ours.stream().filter(u -> !defenders.contains(u)).forEach(defenders::add);
      if (defenders.isEmpty()) {
        return 100;
      }
      return calc.calculateBattleResults(proData, z, counter, defenders, List.of())
          .getWinPercentage();
    }

    private boolean sicuroMare(final Territory z, final Collection<Unit> ours) {
      return vittoriaNemicaMare(z, ours) <= cfg.sogliaMareSicuro();
    }

    // ------------------------------------------------------------------- non combat

    /**
     * C: the USA's shuttle in the non-combat move (before Hard's): load at the port, loaded
     * transports toward the forward base (unload there), empty ones back to load; only safe zones.
     * Before the war: load and wait at the port. Returns the units Hard must leave where they are.
     */
    Set<Unit> spola(final IMoveDelegate delegate, final int garrison, final JsonObject turn) {
      this.moveDel = delegate;
      final Set<Unit> held = new LinkedHashSet<>();
      final Territory port = porto();
      final JsonObject s = new JsonObject();
      turn.add("spola", s);
      if (!usa() || port == null) {
        return held;
      }
      final List<Territory> portZones = zone(port);
      final Territory base = base();
      final boolean unload = puoScaricare();
      final List<Territory> baseZones = unload ? zone(base) : List.of();
      final List<Territory> shuttle = zoneSpola();
      int loadedNow = 0;
      int sailed = 0;
      int brought = 0;
      int back = 0;
      int waitingLoaded = 0;
      // 1 the empty ones back to the port (from the shuttle's zones or within 2 zones of it)
      for (final Unit tr : trasporti()) {
        final Territory z = dove(tr);
        if (z == null
            || tr.isTransporting()
            || !tr.getUnloaded().isEmpty()
            || portZones.contains(z)
            || !(shuttle.contains(z) || distanzaMare(z, portZones) <= 2)
            || tr.getMovementLeft().signum() <= 0) {
          continue;
        }
        if (naviga(new ArrayList<>(List.of(tr)), z, portZones, held).isPresent()) {
          back++;
        }
      }
      // 2 load at the port (transports there, also those just arrived)
      final List<Unit> waiting = new ArrayList<>(inAttesa(port, garrison));
      waiting.removeIf(u -> !Matches.unitHasMovementLeft().test(u));
      waiting.sort(
          Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
              .thenComparing(u -> u.getId().toString()));
      final int ready = waiting.size();
      int atPort = 0;
      int emptyAtPort = 0;
      final List<String> refused = new ArrayList<>();
      for (final Territory z : portZones) {
        for (final Unit tr :
            z.getMatches(
                Matches.unitIsSeaTransportButNotCombatSeaTransport()
                    .and(Matches.unitIsOwnedBy(player)))) {
          atPort++;
          emptyAtPort += tr.isTransporting() ? 0 : 1;
          if (!tr.getUnloaded().isEmpty()) {
            continue;
          }
          int room = TransportTracker.getAvailableCapacity(tr);
          for (final Unit u : new ArrayList<>(waiting)) {
            final int cost = u.getUnitAttachment().getTransportCost();
            if (cost > room) {
              continue;
            }
            final Optional<String> error =
                moveDel.performMove(
                    new MoveDescription(List.of(u), new Route(port, z), Map.of(u, tr)));
            if (error.isEmpty()) {
              room -= cost;
              waiting.remove(u);
              loadedNow++;
            } else if (refused.size() < 3) {
              refused.add(error.get());
            }
          }
        }
      }
      // 3 loaded transports toward the base (or waiting at the port before the war)
      final Map<Territory, List<Unit>> loadedByZone = new LinkedHashMap<>();
      for (final Unit tr : trasporti()) {
        if (!tr.isTransporting() || !tr.getUnloaded().isEmpty()) {
          continue;
        }
        final Territory z = dove(tr);
        if (z != null && tr.getTransporting().stream().allMatch(u -> u.isOwnedBy(player))) {
          loadedByZone.computeIfAbsent(z, k -> new ArrayList<>()).add(tr);
        }
      }
      for (final var e : loadedByZone.entrySet()) {
        final Territory z = e.getKey();
        final List<Unit> group = e.getValue();
        if (!unload) {
          if (portZones.contains(z)) {
            group.forEach(tr -> held.add(tr));
            group.forEach(tr -> held.addAll(tr.getTransporting()));
            waitingLoaded += group.size();
          }
          continue;
        }
        if (baseZones.contains(z)) {
          brought += scarica(group, z, base, held);
          continue;
        }
        if (!shuttle.contains(z) && distanzaMare(z, portZones) > 2) {
          continue; // a transport Hard took elsewhere: Hard's
        }
        final Optional<Territory> to = naviga(group, z, baseZones, held);
        if (to.isPresent()) {
          sailed += group.size();
          if (baseZones.contains(to.get())) {
            brought += scarica(group, to.get(), base, held);
          }
        }
      }
      s.addProperty("trasporti_al_porto", atPort);
      s.addProperty("vuoti_al_porto", emptyAtPort);
      s.addProperty("truppe_pronte", ready);
      s.addProperty("puo_scaricare", unload);
      s.addProperty("caricate", loadedNow);
      s.addProperty("trasporti_partiti", sailed);
      s.addProperty("portate_base", brought);
      s.addProperty("trasporti_rientro", back);
      s.addProperty("carichi_fermi_al_porto", waitingLoaded);
      lines.add(
          String.format(
                  Locale.ROOT,
                  "  Spola USA (%s): caricate %d unita' a %s, %d trasporti carichi in rotta, %d unita'"
                      + " scaricate a %s, %d trasporti vuoti in rientro%s",
                  unload
                      ? "in guerra, scarico a " + cfg.baseAvanzataUsa()
                      : "prima della guerra: niente scarico nel Regno Unito, carichi pronti sulla costa est",
                  loadedNow,
                  port.getName(),
                  sailed,
                  brought,
                  cfg.baseAvanzataUsa(),
                  back,
                  waitingLoaded > 0
                      ? ", " + waitingLoaded + " trasporti carichi fermi al porto"
                      : "")
              + String.format(
                  Locale.ROOT,
                  " [al carico: %d trasporti nella zona del porto (%d vuoti), %d truppe pronte%s]",
                  atPort,
                  emptyAtPort,
                  ready,
                  refused.isEmpty() ? "" : "; rifiuti: " + String.join(" | ", refused)));
      return held;
    }

    /** Unloads the own cargo of {@code group} from {@code z} to {@code land}; units unloaded. */
    private int scarica(
        final List<Unit> group, final Territory z, final Territory land, final Set<Unit> held) {
      int n = 0;
      for (final Unit tr : group) {
        final List<Unit> cargo = new ArrayList<>(tr.getTransporting());
        if (cargo.isEmpty()) {
          continue;
        }
        final Optional<String> error =
            moveDel.performMove(new MoveDescription(cargo, new Route(z, land)));
        if (error.isEmpty()) {
          n += cargo.size();
          held.add(tr);
          held.addAll(cargo);
        } else {
          ProLogger.warn("[Pro+] Programma USA: scarico non riuscito " + error.get());
        }
      }
      return n;
    }

    /**
     * Moves {@code group} (with cargo) from {@code z} toward the nearest of {@code targets}: the
     * farthest zone within movement that is safe, with warships of the same zone as escort if
     * needed. Empty if it stays (held only if {@code z} is safe).
     */
    private Optional<Territory> naviga(
        final List<Unit> group,
        final Territory z,
        final List<Territory> targets,
        final Set<Unit> held) {
      Route best = null;
      for (final Territory t : targets) {
        final List<Unit> units = conCarico(group);
        final Optional<Route> r = data.getMap().getRouteForUnits(z, t, mare(), units, player);
        if (r.isPresent() && (best == null || r.get().numberOfSteps() < best.numberOfSteps())) {
          best = r.get();
        }
      }
      if (best == null) {
        return Optional.empty();
      }
      final int range =
          group.stream().mapToInt(u -> u.getMovementLeft().intValue()).min().orElse(0);
      final List<Territory> steps = best.getSteps();
      final List<Unit> escorts =
          new ArrayList<>(
              z.getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsSea())
                      .and(Matches.unitIsNotSeaTransport())
                      .and(Matches.unitCanEvade().negate())
                      .and(Matches.unitIsCarrier().negate())
                      .and(Matches.unitHasMovementLeft())
                      .and(u -> !held.contains(u))));
      escorts.sort(Comparator.comparingDouble((Unit u) -> c.tuv(List.of(u))));
      for (int i = Math.min(range, steps.size()); i >= 1; i--) {
        final Territory end = steps.get(i - 1);
        final List<Unit> moving = new ArrayList<>(group);
        int added = 0;
        while (!sicuroMare(end, conCarico(moving))
            && added < Math.min(cfg.maxScortaMossa(), escorts.size())) {
          moving.add(escorts.get(added));
          added++;
        }
        if (!sicuroMare(end, conCarico(moving))) {
          continue;
        }
        final Route r = new Route(z, steps.subList(0, i).toArray(new Territory[0]));
        final List<Unit> units = conCarico(moving);
        final Optional<String> error = moveDel.performMove(new MoveDescription(units, r));
        if (error.isEmpty()) {
          held.addAll(units);
          return Optional.of(end);
        }
        ProLogger.warn("[Pro+] Programma USA: rotta non riuscita " + error.get());
      }
      if (sicuroMare(z, conCarico(group))) {
        held.addAll(conCarico(group));
      }
      return Optional.empty();
    }

    private static List<Unit> conCarico(final List<Unit> ships) {
      final List<Unit> out = new ArrayList<>(ships);
      for (final Unit s : ships) {
        s.getTransporting().stream().filter(u -> !out.contains(u)).forEach(out::add);
      }
      return out;
    }

    /**
     * D4: the pro-Allied neutrals of the Middle East taken by entering them with one unit already
     * next to them (non-combat move). Returns the units moved.
     */
    Set<Unit> medioOriente(
        final IMoveDelegate delegate, final Set<String> keep, final JsonObject turn) {
      this.moveDel = delegate;
      final Set<Unit> held = new LinkedHashSet<>();
      if (!uk()) {
        return held;
      }
      final JsonArray taken = new JsonArray();
      for (final String name : cfg.occupaSenzaCombattere()) {
        final Territory t = data.getMap().getTerritoryOrNull(name);
        // still the pro-Allied neutral's (the relationship Friendly_Neutral lets a unit take it)
        if (t == null
            || t.getOwner().isNull()
            || !t.getOwner().getName().startsWith("Neutral")
            || data.getRelationshipTracker().isAtWar(player, t.getOwner())) {
          continue;
        }
        Unit chosen = null;
        Territory from = null;
        for (final Territory n :
            data.getMap().getNeighbors(t, Matches.territoryIsLand()).stream()
                .sorted(Comparator.comparing(Territory::getName))
                .toList()) {
          if (!n.isOwnedBy(player) || keep.contains(n.getName())) {
            continue;
          }
          for (final Unit u :
              n.getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsLand())
                      .and(Matches.unitIsNotInfrastructure())
                      .and(Matches.unitHasMovementLeft()))) {
            if (chosen == null || c.tuv(List.of(u)) < c.tuv(List.of(chosen))) {
              chosen = u;
              from = n;
            }
          }
        }
        if (chosen == null) {
          continue;
        }
        final Optional<String> error =
            moveDel.performMove(new MoveDescription(List.of(chosen), new Route(from, t)));
        if (error.isEmpty()) {
          held.add(chosen);
          taken.add(name);
          lines.add(
              "  Medio Oriente: "
                  + chosen.getType().getName()
                  + " da "
                  + from.getName()
                  + " entra in "
                  + name
                  + " (neutrale filo-Alleato: presa senza battaglia)");
        } else {
          lines.add("  Medio Oriente: " + name + " non presa (" + error.get() + ")");
        }
      }
      turn.add("medio_oriente_presi", taken);
      return held;
    }

    /**
     * D4: the pro-Axis neutrals of the Middle East attacked only with the units already in range
     * (no purchase), when the calculator wins at least {@code soglia_esecuzione}.
     */
    List<String> forzatiMedioOriente(final JsonArray out) {
      final List<String> forced = new ArrayList<>();
      if (!uk()) {
        return forced;
      }
      ProTerritoryManager tm = null;
      for (final String name : cfg.attacca()) {
        final Territory t = data.getMap().getTerritoryOrNull(name);
        if (t == null || !data.getRelationshipTracker().isAtWar(player, t.getOwner())) {
          continue;
        }
        if (tm == null) {
          tm = new ProTerritoryManager(calc, proData);
          tm.populateAttackOptions();
        }
        final ProTerritory pt = tm.getAttackOptions().getTerritoryMap().get(t);
        if (pt == null || pt.getMaxUnits().stream().noneMatch(Matches.unitIsLand())) {
          continue;
        }
        final List<Unit> attackers = new ArrayList<>(pt.getMaxUnits());
        final List<Unit> defenders = t.getMatches(Matches.enemyUnit(player));
        final double win =
            defenders.stream().noneMatch(Matches.unitIsNotInfrastructure())
                ? 100
                : calc.calculateBattleResults(proData, t, attackers, defenders, List.of())
                    .getWinPercentage();
        final boolean force = win >= cfg.sogliaEsecuzione();
        final JsonObject x = new JsonObject();
        x.addProperty("territorio", name);
        x.addProperty("obiettivo", false);
        x.addProperty("medio_oriente", true);
        x.addProperty("vittoria_pct", Math.round(win * 10) / 10.0);
        x.addProperty("perdite_p80_tuv", -1);
        x.addProperty("attaccanti_tuv", Math.round(c.tuv(attackers)));
        x.addProperty("forzato", force);
        out.add(x);
        lines.add(
            String.format(
                Locale.ROOT,
                "  Medio Oriente: %s (neutrale filo-Asse) con le unita' gia' in zona (%.0f TUV):"
                    + " vittoria %.1f%% -> %s",
                name,
                c.tuv(attackers),
                win,
                force ? "FORZATO" : "niente (sotto soglia, nessun acquisto)"));
        if (force) {
          forced.add(name);
        }
      }
      return forced;
    }
  }
}
