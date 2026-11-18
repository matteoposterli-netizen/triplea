package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.battle.ScrambleLogic;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.Serializable;
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
import java.util.TreeMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step P1 "COMPITO": one generic mechanism, driven by data ({@link Compito}, file {@code
 * compiti.json}), for the nations of an alliance with the switch {@link
 * StrategaInterruttori#COMPITI} on (on top of the commitment; in this step only one nation of the
 * data file has a task, the other nations of the alliance play as Hard). No class or branch knows a
 * nation: the list of results is data.
 *
 * <p>Every turn of the nation, on the current result: (a) is it obtainable this turn with the real
 * forces (battle calculator)? then it is done; (b) otherwise which OBSTACLES stop it, with numbers
 * ({@link Ostacolo.Tipo}: MARE, TERRA, CAPACITA, DISTANZA), and the RESPONSE ({@link Risposta}):
 * purchases against the biggest obstacle, moves that bring the forces closer, an attack on an enemy
 * force beaten with a win >= the threshold, another target of the same result with a smaller
 * obstacle; (c) the own capital keeps the minimal garrison. Everything the task does not need stays
 * with Hard. Every decision goes to {@link RegistroDecisioni} ({@code compito_stato}, {@code
 * compito_acquisto}, {@code compito_direzione}, {@code compito_risultato}, {@code
 * compito_battaglia}, {@code sbarco}, {@code perdite_in_mare}).
 */
public final class Compiti {
  private Compiti() {}

  public static final String KEY_STATO = "Pro+ compiti: ";
  public static final String KEY_ORDINE = "Pro+ compiti ordine: ";

  /**
   * Step P2-bis (register only, no decision reads it): ids of the land units the task unloaded in
   * the nation's turn ({@code {"round": n, "ids": [...]}}), so that the autoplay tells the task's
   * landings from Hard's.
   */
  public static final String KEY_SCARICATE = "Pro+ compiti scaricate: ";

  // ============================================================================= switches

  /** The switch is on for {@code alliance} (on top of the commitment). */
  public static boolean acceso(final GameState data, final String alliance) {
    final GameProperties props = data.getProperties();
    return StrategaInterruttori.IMPEGNO.acceso(props, alliance)
        && StrategaInterruttori.COMPITI.acceso(props, alliance);
  }

  /**
   * With the switch on the alliance's commitment is replaced: its nations with a task play the
   * task, the others play as Hard.
   */
  public static boolean escludeImpegno(final GameState data, final String alliance) {
    return acceso(data, alliance) && Compito.dati().nazioniAttive().containsKey(alliance);
  }

  /** The nation plays its task this turn. */
  public static boolean attivo(final GameState data, @Nullable final GamePlayer player) {
    if (player == null || !Stratega.gioca(data, player)) {
      return false;
    }
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    return alliance.isPresent()
        && acceso(data, alliance.get())
        && Compito.dati().di(alliance.get(), player.getName()).isPresent();
  }

  // ========================================================================= entry points

  /** Purchase (real board, {@code proData} initialized): analysis, response, orders. */
  public static void inizioTurno(
      final ProData proData, final ProOddsCalculator calc, final GamePlayer player) {
    final GameState data = proData.getData();
    clear(data.getProperties(), KEY_ORDINE + player.getName());
    if (!attivo(data, player)) {
      return;
    }
    try {
      new Turno(proData, calc, player).inizio();
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Compito, inizio turno: " + e);
    }
  }

  /** The task's units, held out of Hard's combat move (and out of its amphibious loads). */
  public static Set<Unit> trattenute(final GameState data, final GamePlayer player) {
    if (!attivo(data, player)) {
      return Set.of();
    }
    try {
      final Compito task = compito(data, player).orElseThrow();
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      if (Compito.dati().livello(alliance).isEmpty()) {
        return new LinkedHashSet<>(forza(data, player, task, true));
      }
      // step P2 (D): only the units the task uses this turn
      return usate(data, player, task);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Compito, trattenute: " + e);
      return Set.of();
    }
  }

  /** The purchase orders of the task this round, if any. */
  public static Optional<ProPurchaseOrders> ordini(final ProData proData) {
    final GamePlayer player = proData.getPlayer();
    if (player == null) {
      return Optional.empty();
    }
    final GameState data = proData.getData();
    if (!(data.getProperties().get(KEY_ORDINE + player.getName()) instanceof String s)
        || s.isBlank()) {
      return Optional.empty();
    }
    try {
      final JsonObject o = JsonParser.parseString(s).getAsJsonObject();
      if (o.get("round").getAsInt() != data.getSequence().getRound()) {
        return Optional.empty();
      }
      final List<ProPurchaseOrders.Mandatory> mandatory = new ArrayList<>();
      for (final JsonElement e : o.getAsJsonArray("voci")) {
        final JsonObject v = e.getAsJsonObject();
        mandatory.add(
            new ProPurchaseOrders.Mandatory(
                "compito " + v.get("tipo").getAsString() + " " + v.get("fabbrica").getAsString(),
                List.of(v.get("tipo").getAsString()),
                v.get("n").getAsInt(),
                v.get("fabbrica").getAsString()));
      }
      if (mandatory.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new ProPurchaseOrders(
              "Compito " + player.getName() + ": " + o.get("risultato").getAsString(),
              List.of(),
              List.copyOf(mandatory),
              List.of(o.get("porto").getAsString()),
              List.of(),
              o.get("quota_minima").getAsDouble()));
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Combat move before Hard's: attack on an enemy force, landing, land attack. */
  public static Set<Unit> combattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final GamePlayer player) {
    if (!attivo(proData.getData(), player)) {
      return Set.of();
    }
    final Set<String> before = scaricateOra(proData.getData(), player);
    try {
      return new Turno(proData, calc, player).combattimento(moveDel);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Compito, combattimento: " + e);
      return Set.of();
    } finally {
      registraScaricate(proData.getData(), player, before);
    }
  }

  /** Non-combat move before Hard's: outcomes, load, fleet and transports, planes. */
  public static Set<Unit> nonCombattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final GamePlayer player) {
    if (!attivo(proData.getData(), player)) {
      return Set.of();
    }
    final Set<String> before = scaricateOra(proData.getData(), player);
    try {
      return new Turno(proData, calc, player).nonCombattimento(moveDel);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Compito, non combattimento: " + e);
      return Set.of();
    } finally {
      registraScaricate(proData.getData(), player, before);
    }
  }

  /** Step P2-bis: the ids of the nation's land units unloaded from a transport this turn. */
  static Set<String> scaricateOra(final GameState data, final GamePlayer player) {
    final Set<String> out = new LinkedHashSet<>();
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit tr : z.getMatches(Matches.unitIsSeaTransport())) {
        for (final Unit u : tr.getUnloaded()) {
          if (u.isOwnedBy(player)) {
            out.add(u.getId().toString());
          }
        }
      }
    }
    return out;
  }

  /**
   * Step P2-bis: adds to {@link #KEY_SCARICATE} the units unloaded since {@code before} (the task's
   * part of the move phase: the task moves before Hard).
   */
  private static void registraScaricate(
      final GameState data, final GamePlayer player, final Set<String> before) {
    try {
      final int round = data.getSequence().getRound();
      final String key = KEY_SCARICATE + player.getName();
      final JsonArray ids = new JsonArray();
      if (data.getProperties().get(key) instanceof String s && !s.isBlank()) {
        final JsonObject o = JsonParser.parseString(s).getAsJsonObject();
        if (o.get("round").getAsInt() == round) {
          o.getAsJsonArray("ids").forEach(ids::add);
        }
      }
      for (final String id : scaricateOra(data, player)) {
        if (!before.contains(id) && !ids.contains(new JsonPrimitive(id))) {
          ids.add(id);
        }
      }
      final JsonObject o = new JsonObject();
      o.addProperty("round", round);
      o.add("ids", ids);
      data.getProperties().set(key, (Serializable) null);
      data.getProperties().set(key, o.toString());
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Compito, registro delle unita' scaricate: " + e);
    }
  }

  /** The state without ids, for the strategist's summary. */
  public static JsonObject riepilogo(final GameProperties props, final String alliance) {
    final JsonObject st = stato(props, alliance);
    final JsonObject out = new JsonObject();
    if (st.has("nazioni")) {
      for (final var e : st.getAsJsonObject("nazioni").entrySet()) {
        final JsonObject n = e.getValue().getAsJsonObject();
        final JsonObject o = new JsonObject();
        for (final String k : List.of("indice", "raggiunti", "mancati", "direzione", "bersaglio")) {
          if (n.has(k)) {
            o.add(k, n.get(k).deepCopy());
          }
        }
        out.add(e.getKey(), o);
      }
    }
    return out;
  }

  // ============================================================================== helpers

  static Optional<Compito> compito(final GameState data, final GamePlayer player) {
    return ProPlusSettings.allianceOf(data, player)
        .flatMap(a -> Compito.dati().di(a, player.getName()));
  }

  static JsonObject stato(final GameProperties props, final String alliance) {
    if (props.get(KEY_STATO + alliance) instanceof String s && !s.isBlank()) {
      try {
        return JsonParser.parseString(s).getAsJsonObject();
      } catch (final RuntimeException e) {
        return new JsonObject();
      }
    }
    return new JsonObject();
  }

  static void salva(final GameProperties props, final String alliance, final JsonObject st) {
    props.set(KEY_STATO + alliance, (Serializable) null);
    props.set(KEY_STATO + alliance, st.toString());
  }

  private static void clear(final GameProperties props, final String key) {
    if (props.get(key) != null) {
      props.set(key, (Serializable) null);
    }
  }

  static JsonObject obj(final JsonObject o, final String key) {
    if (!o.has(key) || !o.get(key).isJsonObject()) {
      o.add(key, new JsonObject());
    }
    return o.getAsJsonObject(key);
  }

  static double r1(final double x) {
    return Math.round(x * 10) / 10.0;
  }

  static boolean nave(final Unit u) {
    return Matches.unitIsSea().test(u);
  }

  static boolean trasporto(final Unit u) {
    return Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u);
  }

  static boolean daGuerra(final Unit u) {
    return nave(u) && !trasporto(u);
  }

  /**
   * The task's force: the nation's sea units, its planes at sea (on carriers), the land units on
   * its transports and (with {@code pronte}) the land units ready at the troop factories beyond the
   * capital's garrison.
   */
  static List<Unit> forza(
      final GameState data, final GamePlayer player, final Compito task, final boolean pronte) {
    final List<Unit> out = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        out.addAll(t.getMatches(Matches.unitIsOwnedBy(player)));
      }
    }
    if (pronte) {
      for (final String f : task.fabbricheTruppe()) {
        final Territory t = data.getMap().getTerritoryOrNull(f);
        if (t != null) {
          out.addAll(
              t.getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsLand())
                      .and(Matches.unitIsNotInfrastructure())
                      .and(u -> u.getUnitAttachment().getTransportCost() > 0)));
        }
      }
    }
    return out;
  }

  /**
   * Step P2 (D): the units the task uses this turn ({@code piano.uso}): FLOTTA = warships and
   * planes at sea, SBARCO = transports, the troops on them and the troops ready at the troop
   * factories, TERRA_VICINA = the troops on allied land next to the target. The rest is Hard's.
   */
  static Set<Unit> usate(final GameState data, final GamePlayer player, final Compito task) {
    final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
    final JsonObject ns =
        obj(obj(stato(data.getProperties(), alliance), "nazioni"), player.getName());
    final JsonObject piano = ns.has("piano") ? ns.getAsJsonObject("piano") : null;
    final Set<Unit> out = new LinkedHashSet<>();
    if (piano == null
        || piano.get("round").getAsInt() != data.getSequence().getRound()
        || !piano.has("uso")) {
      return out;
    }
    final Set<String> uso = new java.util.HashSet<>();
    piano.getAsJsonArray("uso").forEach(e -> uso.add(e.getAsString()));
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      for (final Unit u : t.getMatches(Matches.unitIsOwnedBy(player))) {
        final boolean transport = trasporto(u);
        final boolean cargo = Matches.unitIsLand().test(u);
        if (uso.contains(Risposta.Uso.FLOTTA.name()) && !transport && !cargo) {
          out.add(u);
        } else if (uso.contains(Risposta.Uso.SBARCO.name())
            && (transport ? u.isTransporting() : cargo)) {
          // step P2-bis: only the loaded transports and their cargo; the empty transports and the
          // troops at home are Hard's (its own landings)
          out.add(u);
        }
      }
    }
    if (uso.contains(Risposta.Uso.TERRA_VICINA.name()) && piano.has("bersaglio")) {
      final Territory target =
          data.getMap().getTerritoryOrNull(piano.get("bersaglio").getAsString());
      if (target != null) {
        for (final Territory n : data.getMap().getNeighbors(target, Matches.territoryIsLand())) {
          if (Matches.isTerritoryAllied(player).test(n)
              && !task.fabbricheTruppe().contains(n.getName())) {
            out.addAll(
                n.getMatches(
                    Matches.unitIsOwnedBy(player)
                        .and(Matches.unitIsLand())
                        .and(Matches.unitIsNotInfrastructure())));
          }
        }
      }
    }
    return out;
  }

  // ================================================================================ turn

  /** One call of the engine for a nation (purchase, combat or non-combat). */
  static final class Turno {
    final ProData proData;
    final ProOddsCalculator calc;
    final GamePlayer player;
    final GameState data;
    final String alliance;
    final Compito task;
    final Compito.Parametri par;
    final int round;
    final List<String> lines = new ArrayList<>();
    final JsonObject st;
    final JsonObject ns;
    final Map<String, Integer> costi;
    final Programmi.Turno p;

    /**
     * Step P2: the nations whose forces the analyses count (the player alone, or the alliance's
     * task nations for the combined counts of {@link Alleanza}); moves are only ever the player's.
     */
    final List<GamePlayer> squadra;

    private final Map<String, Double> cache = new HashMap<>();
    private final Map<String, Risposta.Bersaglio> analisi = new HashMap<>();
    private final Map<String, JsonObject> dettagli = new HashMap<>();
    @Nullable private Map<Unit, Territory> luoghi;
    @Nullable private IMoveDelegate moveDel;
    private final Map<String, Integer> presidi = new HashMap<>();

    /** Step P2: the combined analysis of the round ({@link Alleanza#combinata}). */
    @Nullable Turno combinata;

    /** Step P2: the single-nation analyses of the other members ({@link Alleanza#turnoDi}). */
    final Map<String, Turno> altri = new HashMap<>();

    /** Step P2: the enemy's attack options per territory ({@link Alleanza#contrattacco}). */
    final Map<String, ProTerritoryManager> nemici = new HashMap<>();

    /** Step P2: this nation's part of the shared result this turn. */
    @Nullable JsonObject condivisoNazione;

    private final Map<String, List<Unit>> temporanee = new HashMap<>();

    Turno(final ProData proData, final ProOddsCalculator calc, final GamePlayer player) {
      this(proData, calc, player, List.of(player));
    }

    Turno(
        final ProData proData,
        final ProOddsCalculator calc,
        final GamePlayer player,
        final List<GamePlayer> squadra) {
      this.squadra = List.copyOf(squadra);
      this.proData = proData;
      this.calc = calc;
      this.player = player;
      this.data = proData.getData();
      this.alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      this.task = compito(data, player).orElseThrow();
      this.par = Compito.dati().parametri();
      this.round = data.getSequence().getRound();
      this.st = stato(data.getProperties(), alliance);
      this.ns = obj(obj(st, "nazioni"), player.getName());
      this.costi = Impegno.costiAcquisto(data, player);
      this.p =
          new Programmi.Turno(
              proData,
              calc,
              player,
              alliance,
              new CampagnaOmbra.Calcolo(
                  proData, calc, player, alliance, CampagnaOmbra.Config.get()),
              lines);
    }

    // ------------------------------------------------------------------------ units

    Map<Unit, Territory> luoghi() {
      if (luoghi == null) {
        luoghi = new HashMap<>();
        for (final Territory t : data.getMap().getTerritories()) {
          for (final Unit u : t.getUnitCollection()) {
            luoghi.put(u, t);
          }
        }
      }
      return luoghi;
    }

    void aggiorna() {
      luoghi = null;
    }

    @Nullable
    Territory dove(final Unit u) {
      return luoghi().get(u);
    }

    List<Unit> mie(final Predicate<Unit> m) {
      final List<Unit> out = new ArrayList<>();
      for (final var e : luoghi().entrySet()) {
        if (squadra.contains(e.getKey().getOwner()) && m.test(e.getKey())) {
          out.add(e.getKey());
        }
      }
      out.sort(Comparator.comparing(u -> u.getId().toString()));
      return out;
    }

    List<Unit> flotta() {
      return mie(Compiti::daGuerra);
    }

    List<Unit> trasporti() {
      return mie(Compiti::trasporto);
    }

    List<Unit> aereiInMare() {
      return mie(u -> Matches.unitIsAir().test(u) && dove(u) != null && dove(u).isWater());
    }

    List<Unit> caricate() {
      return mie(
          u ->
              Matches.unitIsLand().test(u)
                  && u.getTransportedBy() != null
                  && squadra.contains(u.getTransportedBy().getOwner()));
    }

    @Nullable
    Territory porto() {
      return data.getMap().getTerritoryOrNull(task.porto());
    }

    List<Territory> zonePorto() {
      final Territory port = porto();
      final List<Territory> out =
          port == null
              ? new ArrayList<>()
              : new ArrayList<>(
                  data.getMap().getNeighbors(port, Matches.territoryIsWater()).stream()
                      .sorted(Comparator.comparing(Territory::getName))
                      .toList());
      // step P2: with the combined forces, every member's port
      for (final GamePlayer m : squadra) {
        if (m.equals(player)) {
          continue;
        }
        compito(data, m)
            .map(c -> data.getMap().getTerritoryOrNull(c.porto()))
            .ifPresent(
                t ->
                    data.getMap().getNeighbors(t, Matches.territoryIsWater()).stream()
                        .sorted(Comparator.comparing(Territory::getName))
                        .filter(z -> !out.contains(z))
                        .forEach(out::add));
      }
      return out;
    }

    /** Land units ready at the troop factories, beyond the capital's minimal garrison. */
    List<Unit> pronte() {
      final List<Unit> out = new ArrayList<>();
      for (final GamePlayer m : squadra) {
        final Optional<Compito> mt = m.equals(player) ? Optional.of(task) : compito(data, m);
        if (mt.isEmpty()) {
          continue;
        }
        for (final String f : mt.get().fabbricheTruppe()) {
          final Territory t = data.getMap().getTerritoryOrNull(f);
          if (t == null) {
            continue;
          }
          final List<Unit> here =
              new ArrayList<>(
                  t.getMatches(
                      Matches.unitIsOwnedBy(m)
                          .and(Matches.unitIsLand())
                          .and(Matches.unitIsNotInfrastructure())
                          .and(u -> u.getUnitAttachment().getTransportCost() > 0)
                          // step P2: units that cannot attack (AA guns) are no landing force
                          .and(
                              u ->
                                  !Alleanza.ha(this)
                                      || Matches.unitCanMoveDuringCombatMove().test(u))));
          if (f.equals(mt.get().porto())) {
            // the strongest defenders stay as the garrison
            here.sort(
                Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getDefense(m))
                    .thenComparing(u -> u.getId().toString()));
            final int g = Math.min(here.size(), presidio(m, mt.get()));
            here.subList(0, g).clear();
          }
          out.addAll(here);
        }
      }
      return out;
    }

    /**
     * (c) The capital's minimal garrison: none if no enemy can attack it, otherwise the strongest
     * own defenders one by one until the enemy's maximum attack wins <= the threshold.
     */
    int presidio() {
      return presidio(player, task);
    }

    int presidio(final GamePlayer m, final Compito mt) {
      final Integer known = presidi.get(m.getName());
      if (known != null) {
        return known;
      }
      int presidio = 0;
      presidi.put(m.getName(), presidio);
      final Territory port = data.getMap().getTerritoryOrNull(mt.porto());
      if (port == null) {
        return presidio;
      }
      try {
        final ProTerritoryManager enemy = new ProTerritoryManager(calc, proData);
        enemy.populateEnemyAttackOptions(List.of(), List.of(port));
        final ProTerritory max = enemy.getEnemyAttackOptions().getMax(port);
        if (max == null || max.getMaxUnits().isEmpty()) {
          return presidio;
        }
        final List<Unit> own =
            new ArrayList<>(
                port.getMatches(
                    Matches.unitIsOwnedBy(m)
                        .and(Matches.unitIsLand())
                        .and(Matches.unitIsNotInfrastructure())));
        own.sort(Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getDefense(m)));
        final List<Unit> g = new ArrayList<>();
        for (final Unit u : own) {
          if (vittoria(port, new ArrayList<>(max.getMaxUnits()), g, List.of())
              <= par.sogliaPresidioCapitale()) {
            break;
          }
          g.add(u);
        }
        presidio = g.size();
        presidi.put(m.getName(), presidio);
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] Compito, presidio: " + e);
      }
      return presidio;
    }

    int capacita(final Unit tr) {
      return tr.getUnitAttachment().getTransportCapacity();
    }

    int liberi(final Unit tr) {
      return TransportTracker.getAvailableCapacity(tr);
    }

    int posti(final Collection<Unit> land) {
      return land.stream().mapToInt(u -> u.getUnitAttachment().getTransportCost()).sum();
    }

    int tuv(final Collection<Unit> units) {
      int n = 0;
      for (final Unit u : units) {
        n += proData.getUnitValueMap().getInt(u.getType());
      }
      return n;
    }

    int costo(final String type) {
      return costi.getOrDefault(type, 0);
    }

    List<Unit> temp(final String type, final int n) {
      final Optional<UnitType> ut = data.getUnitTypeList().getUnitType(type);
      return ut.isPresent() && n > 0 ? ut.get().createTemp(n, player) : List.of();
    }

    // ---------------------------------------------------------------- movement, routes

    /** The movement given by own units next to {@code zone} (the map's harbour bonus). */
    int bonus(final Unit u, final Territory zone) {
      int best = 0;
      for (final Territory land : data.getMap().getNeighbors(zone, Matches.territoryIsLand())) {
        if (!land.isOwnedBy(player)) {
          continue;
        }
        for (final Unit x : land.getUnitCollection()) {
          try {
            best = Math.max(best, x.getUnitAttachment().getGivesMovement().getInt(u.getType()));
          } catch (final RuntimeException e) {
            // no bonus
          }
        }
      }
      return best;
    }

    int portata(final Unit u, final Territory at) {
      final int left = u.getMovementLeft().intValue();
      if (u.getAlreadyMoved().signum() != 0) {
        return left;
      }
      return Math.max(left, u.getUnitAttachment().getMovement(player) + bonus(u, at));
    }

    /** A sea route for {@code units} from {@code from} to {@code to} (no movement limit). */
    Optional<Route> rottaMare(
        final Territory from, final Territory to, final List<Unit> units, final boolean combat) {
      if (from.equals(to)) {
        return Optional.of(new Route(from));
      }
      final Predicate<Territory> through =
          x ->
              x.isWater()
                  && ProMatches.territoryCanMoveSeaUnits(player, combat).test(x)
                  && (x.equals(to) || !x.anyUnitsMatch(Matches.enemyUnit(player)));
      return data.getMap().getRouteForUnits(from, to, through, units, player);
    }

    /** Turns {@code units} at {@code from} need to reach {@code to} (MAX_VALUE: no route). */
    int turni(final Territory from, final Territory to, final List<Unit> units) {
      if (from.equals(to)) {
        return 0;
      }
      final Optional<Route> r = rottaMare(from, to, units, true);
      if (r.isEmpty()) {
        return Integer.MAX_VALUE;
      }
      final int steps = r.get().numberOfSteps();
      int move = Integer.MAX_VALUE;
      for (final Unit u : units) {
        if (nave(u)) {
          move = Math.min(move, u.getUnitAttachment().getMovement(player));
        }
      }
      if (move == Integer.MAX_VALUE || move <= 0) {
        return steps;
      }
      final int first =
          units.stream().filter(Compiti::nave).mapToInt(u -> portata(u, from)).min().orElse(move);
      return steps <= first ? 1 : 1 + (steps - first + move - 1) / move;
    }

    /** The units of {@code units} that reach {@code to} this turn by sea. */
    List<Unit> raggiungono(final List<Unit> units, final Territory to, final boolean combat) {
      final List<Unit> out = new ArrayList<>();
      for (final Unit u : units) {
        final Territory at = dove(u);
        if (at == null || !at.isWater()) {
          continue;
        }
        if (at.equals(to)) {
          out.add(u);
          continue;
        }
        if (u.getMovementLeft().signum() <= 0 && u.getAlreadyMoved().signum() != 0) {
          continue;
        }
        final Optional<Route> r = rottaMare(at, to, List.of(u), combat);
        if (r.isPresent() && r.get().numberOfSteps() <= portata(u, at)) {
          out.add(u);
        }
      }
      return out;
    }

    /** The zone where most of the fleet's value is (the fleet's position). */
    @Nullable
    Territory centroFlotta() {
      final Map<Territory, Integer> value = new TreeMap<>(Comparator.comparing(Territory::getName));
      for (final Unit u : flotta()) {
        final Territory t = dove(u);
        if (t != null) {
          value.merge(t, tuv(List.of(u)), Integer::sum);
        }
      }
      if (value.isEmpty()) {
        final List<Territory> port = zonePorto();
        return port.isEmpty() ? null : port.get(0);
      }
      return value.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    // ------------------------------------------------------------------- estimates

    /** Win% of {@code att} against {@code def} in {@code t} (cached by composition). */
    double vittoria(
        final Territory t, final List<Unit> att, final List<Unit> def, final List<Unit> bomb) {
      if (att.isEmpty()) {
        return 0;
      }
      if (def.isEmpty()) {
        return 100;
      }
      final String key =
          "v|" + t.getName() + "|" + chiave(att) + "|" + chiave(def) + "|" + chiave(bomb);
      final Double c = cache.get(key);
      if (c != null) {
        return c;
      }
      final double v =
          calc.calculateBattleResults(proData, t, propri(att), def, propri(bomb))
              .getWinPercentage();
      cache.put(key, v);
      return v;
    }

    /**
     * Step P2: the attackers of another member of the squad as temporary units of the player of the
     * same types (a battle has one attacker; the combined counts are estimates).
     */
    List<Unit> propri(final List<Unit> units) {
      if (units.stream().allMatch(u -> u.isOwnedBy(player))) {
        return units;
      }
      final List<Unit> out = new ArrayList<>();
      final Map<String, Integer> foreign = new TreeMap<>();
      for (final Unit u : units) {
        if (u.isOwnedBy(player)) {
          out.add(u);
        } else {
          foreign.merge(u.getType().getName(), 1, Integer::sum);
        }
      }
      foreign.forEach(
          (type, n) -> {
            final List<Unit> pool = temporanee.computeIfAbsent(type, k -> new ArrayList<>());
            while (pool.size() < n) {
              pool.addAll(temp(type, n - pool.size()));
              if (pool.isEmpty()) {
                break;
              }
            }
            out.addAll(pool.subList(0, Math.min(n, pool.size())));
          });
      return out;
    }

    private String chiave(final Collection<Unit> units) {
      final Map<String, Integer> m = new TreeMap<>();
      for (final Unit u : units) {
        m.merge(u.getType().getName() + "@" + u.getOwner().getName(), 1, Integer::sum);
      }
      return m.toString();
    }

    /**
     * A1 (the existing rule of the transports): the enemy's win in {@code z} at the end of the turn
     * against the allied sea units there plus {@code arriving}, the planes on carriers and those
     * that can scramble to it; every enemy unit in reach attacks.
     */
    TrasportiProtetti.Sicurezza sicurezza(final Territory z, final List<Unit> arriving) {
      return TrasportiProtetti.valuta(p, z, arriving, List.of());
    }

    double nemicaFine(final Territory z, final List<Unit> arriving) {
      final String key = "s|" + z.getName() + "|" + chiave(arriving);
      final Double c = cache.get(key);
      if (c != null) {
        return c;
      }
      final double v = sicurezza(z, arriving).vittoria();
      cache.put(key, v);
      return v;
    }

    /** The enemy's TUV swing attacking {@code z} at the end of the turn (A1 forces). */
    double swingNemico(final Territory z, final List<Unit> arriving) {
      final String key = "sw|" + z.getName() + "|" + chiave(arriving);
      final Double c = cache.get(key);
      if (c != null) {
        return c;
      }
      final TrasportiProtetti.Sicurezza s = sicurezza(z, arriving);
      final double v =
          s.attaccanti().isEmpty() || s.difensori().isEmpty()
              ? 0
              : calc.calculateBattleResults(proData, z, s.attaccanti(), s.difensori(), List.of())
                  .getTuvSwing();
      cache.put(key, v);
      return v;
    }

    /** Our TUV swing attacking the enemy force in {@code z} with the warships of {@code ours}. */
    double swingNostro(final Territory z, final List<Unit> ours) {
      final List<Unit> att = ours.stream().filter(u -> !trasporto(u)).toList();
      final List<Unit> def = difensoriMare(z);
      if (att.isEmpty() || def.isEmpty()) {
        return 0;
      }
      final String key = "sn|" + z.getName() + "|" + chiave(att) + "|" + chiave(def);
      final Double c = cache.get(key);
      if (c != null) {
        return c;
      }
      final double v =
          calc.calculateBattleResults(proData, z, propri(att), def, List.of()).getTuvSwing();
      cache.put(key, v);
      return v;
    }

    double sogliaZona(final Territory z) {
      return TrasportiProtetti.soglia(
          TrasportiProtetti.pericolo(
              data, player, z, TrasportiProtetti.Config.get().roundPericolo()),
          TrasportiProtetti.Config.get());
    }

    /** The enemy units that defend {@code z} against our attack: those there plus scramble. */
    List<Unit> difensoriMare(final Territory z) {
      final List<Unit> out = new ArrayList<>(z.getMatches(Matches.enemyUnit(player)));
      if (!Properties.getScrambleRulesInEffect(data.getProperties())) {
        return out;
      }
      try {
        final var byFrom =
            new ScrambleLogic(data, player, z).getUnitsThatCanScrambleByDestination().get(z);
        if (byFrom != null) {
          for (final var e : byFrom.entrySet()) {
            final int max = ScrambleLogic.getMaxScrambleCount(e.getValue().getFirst());
            e.getValue().getSecond().stream()
                .filter(Matches.enemyUnit(player))
                .filter(u -> !out.contains(u))
                .sorted(
                    Comparator.comparingInt(
                            (Unit u) -> -u.getUnitAttachment().getDefense(u.getOwner()))
                        .thenComparing(u -> u.getId().toString()))
                .limit(max)
                .forEach(out::add);
          }
        }
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] Compito, scramble: " + e);
      }
      return out;
    }

    boolean inGuerraCon(final Territory t) {
      return t.getOwner() != null && !t.getOwner().isNull() && player.isAtWar(t.getOwner());
    }

    boolean inGuerraConUnita(final Collection<Unit> units) {
      return units.stream().anyMatch(u -> player.isAtWar(u.getOwner()));
    }

    boolean alleato(final Territory t) {
      return Matches.isTerritoryAllied(player).test(t);
    }

    /** Planes of the nation on carriers in {@code zones}. */
    List<Unit> aereiDi(final Collection<Territory> zones) {
      final List<Unit> out = new ArrayList<>();
      for (final Unit u : aereiInMare()) {
        if (zones.contains(dove(u))) {
          out.add(u);
        }
      }
      return out;
    }

    List<Territory> zoneDi(final Collection<Unit> units) {
      final Set<Territory> out = new LinkedHashSet<>();
      units.forEach(
          u -> {
            final Territory t = dove(u);
            if (t != null) {
              out.add(t);
            }
          });
      return List.copyOf(out);
    }

    /** Ships that can bombard (at most one per land unit). */
    List<Unit> bombardieri(final List<Unit> ships, final int landUnits) {
      return ships.stream()
          .filter(Matches.unitCanBombard(player))
          .sorted(Comparator.comparing(u -> u.getId().toString()))
          .limit(landUnits)
          .toList();
    }

    // ------------------------------------------------------------------- MARE estimate

    /** The MARE obstacle of zone {@code z} for a force {@code ours}: 0 = none. */
    double mareObiettivo(final Territory z, final List<Unit> ours, final boolean battaglia) {
      final double soglia = sogliaZona(z);
      double obj = Math.max(0, nemicaFine(z, ours) - soglia);
      if (battaglia) {
        final List<Unit> att = ours.stream().filter(u -> !trasporto(u)).toList();
        obj += Math.max(0, par.sogliaAttacco() - vittoria(z, att, difensoriMare(z), List.of()));
      }
      return obj;
    }

    /**
     * The MARE purchase: one unit at a time among warships and carrier planes (planes only in free
     * carrier places), the one that lowers the obstacle most per PU, until the zone can be held or
     * the enemy force beaten, at most {@code max_passi_acquisto_mare} units or {@code budget} PUs.
     */
    record AcquistoMare(
        Map<String, Integer> unita,
        int spesi,
        double prima,
        double dopo,
        double nemicaPrima,
        double nemicaDopo,
        boolean basta) {}

    AcquistoMare acquistoMare(
        final Territory z, final List<Unit> base, final boolean battaglia, final int budget) {
      final List<Unit> force = new ArrayList<>(base);
      final double before = mareObiettivo(z, force, battaglia);
      final double enemyBefore = nemicaFine(z, force);
      double obj = before;
      int spent = 0;
      final Map<String, Integer> bought = new LinkedHashMap<>();
      for (int step = 0; step < par.maxPassiAcquistoMare() && obj > 0; step++) {
        final Map<String, double[]> cand = new LinkedHashMap<>();
        final Map<String, Double> objAfter = new HashMap<>();
        for (final String type : par.tipiMare()) {
          final int c = costo(type);
          if (c <= 0 || spent + c > budget) {
            continue;
          }
          final List<Unit> one = temp(type, 1);
          if (one.isEmpty()) {
            continue;
          }
          if (Matches.unitIsAir().test(one.get(0)) && postiPortaerei(force) < costoPortaerei(one)) {
            continue;
          }
          final List<Unit> f = new ArrayList<>(force);
          f.addAll(one);
          final double o = mareObiettivo(z, f, battaglia);
          objAfter.put(type, o);
          cand.put(type, new double[] {obj - o, c});
        }
        Optional<String> best = Risposta.miglioreAcquisto(cand);
        if (best.isEmpty() && !cand.isEmpty() && step == 0) {
          // one unit does not move the win (saturated): every unit of a type the PUs allow, the
          // type that lowers the obstacle most per PU; then one at a time again
          final Map<String, double[]> bulk = new LinkedHashMap<>();
          final Map<String, Integer> bulkN = new HashMap<>();
          for (final String type : cand.keySet()) {
            final int n = Math.min(par.maxPassiAcquistoMare(), (budget - spent) / costo(type));
            if (n <= 1) {
              continue;
            }
            final List<Unit> more = temp(type, n);
            if (Matches.unitIsAir().test(more.get(0))
                && postiPortaerei(force) < costoPortaerei(more)) {
              continue;
            }
            final List<Unit> f = new ArrayList<>(force);
            f.addAll(more);
            final double o = mareObiettivo(z, f, battaglia);
            bulk.put(type, new double[] {obj - o, (double) n * costo(type)});
            bulkN.put(type, n);
            objAfter.put("bulk|" + type, o);
          }
          final Optional<String> b = Risposta.miglioreAcquisto(bulk);
          if (b.isPresent()) {
            final int n = bulkN.get(b.get());
            force.addAll(temp(b.get(), n));
            bought.merge(b.get(), n, Integer::sum);
            spent += n * costo(b.get());
            obj = objAfter.get("bulk|" + b.get());
            step += n - 1;
            continue;
          }
        }
        if (best.isEmpty() && !cand.isEmpty()) {
          // the win does not move with one unit (saturated at 100% or 0%): the enemy's TUV swing
          // (A1 zone) or ours (battle) per PU decides
          final boolean a1 = nemicaFine(z, force) > sogliaZona(z);
          final double before0 = a1 ? swingNemico(z, force) : -swingNostro(z, force);
          final Map<String, double[]> cand2 = new LinkedHashMap<>();
          for (final String type : cand.keySet()) {
            final List<Unit> f = new ArrayList<>(force);
            f.addAll(temp(type, 1));
            final double after0 = a1 ? swingNemico(z, f) : -swingNostro(z, f);
            cand2.put(type, new double[] {before0 - after0, costo(type)});
          }
          best = Risposta.miglioreAcquisto(cand2);
        }
        if (best.isEmpty()) {
          break;
        }
        force.addAll(temp(best.get(), 1));
        bought.merge(best.get(), 1, Integer::sum);
        spent += costo(best.get());
        obj = objAfter.get(best.get());
      }
      return new AcquistoMare(
          bought, spent, before, obj, enemyBefore, nemicaFine(z, force), obj <= 0);
    }

    int postiPortaerei(final List<Unit> force) {
      int cap = 0;
      int used = 0;
      for (final Unit u : force) {
        if (Matches.unitIsCarrier().test(u)) {
          cap += u.getUnitAttachment().getCarrierCapacity();
        } else if (Matches.unitCanLandOnCarrier().test(u)) {
          used += Math.max(0, u.getUnitAttachment().getCarrierCost());
        }
      }
      return cap - used;
    }

    int costoPortaerei(final List<Unit> planes) {
      return planes.stream()
          .mapToInt(u -> Math.max(0, u.getUnitAttachment().getCarrierCost()))
          .sum();
    }

    /**
     * An enemy force in the zone that the fleet beats (>= the threshold): still a MARE obstacle (a
     * battle comes first), sized as the enemy TUV times the chance of losing, at least 1 PU.
     */
    double puBattibile(final Territory z, final List<Unit> ours) {
      final List<Unit> att = ours.stream().filter(Compiti::daGuerra).toList();
      final double w = vittoria(z, att, difensoriMare(z), List.of());
      return Math.max(1, tuv(z.getMatches(Matches.enemyUnit(player))) * (100 - w) / 100.0);
    }

    /** PU-equivalent size of a MARE obstacle (the estimate continues past the step limit). */
    double puMare(final AcquistoMare a) {
      if (a.basta() || a.prima() <= 0) {
        return a.spesi();
      }
      final double drop = a.prima() - a.dopo();
      if (drop <= 0 || a.spesi() <= 0) {
        return 300;
      }
      return Math.min(300, a.spesi() * a.prima() / drop);
    }

    // ------------------------------------------------------------- TERRA estimate

    record Terra(double vittoria, int truppeNecessarie, int pu, boolean basta) {}

    Terra terra(
        final Territory t, final List<Unit> att, final List<Unit> bomb, final List<Unit> def) {
      final double v = vittoria(t, att, def, bomb);
      if (v >= par.sogliaAttacco()) {
        return new Terra(v, 0, 0, true);
      }
      final List<Unit> force = new ArrayList<>(att);
      int n = 0;
      int pu = 0;
      double w = v;
      while (n < par.maxTruppeStima() && w < par.sogliaAttacco()) {
        final String type = par.tipiTruppe().get(n % par.tipiTruppe().size());
        force.addAll(temp(type, 1));
        pu += costo(type);
        n++;
        if (n % 2 == 0 || n == par.maxTruppeStima()) {
          w = vittoria(t, force, def, bomb);
        }
      }
      final boolean ok = w >= par.sogliaAttacco();
      return new Terra(v, n, ok ? pu : pu + 60, ok);
    }

    // ------------------------------------------------------------------- analysis

    /**
     * The analysis of a landing on {@code t} (the result's territory, the capital or a coastal
     * territory next to it): MARE in the best landing zone, TERRA, CAPACITA, DISTANZA; obtainable
     * with the forces that reach this turn.
     */
    Risposta.Bersaglio sbarco(final Territory t) {
      final String key = "sbarco|" + t.getName();
      if (analisi.containsKey(key)) {
        return analisi.get(key);
      }
      final JsonObject det = new JsonObject();
      final List<Ostacolo> obs = new ArrayList<>();
      final List<Unit> fleet = flotta();
      final List<Unit> transports = trasporti();
      final List<Unit> loaded = caricate();
      final List<Unit> ready = pronte();
      final List<Unit> planes = aereiInMare();
      final List<Unit> landAdj = terraConfinante(t);
      final List<Unit> def =
          t.getMatches(Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure()));
      final Territory centre = centroFlotta();
      // MARE: the landing zone with the smallest obstacle
      Territory bestZone = null;
      double bestMare = Double.MAX_VALUE;
      AcquistoMare bestEst = null;
      boolean bestBattle = false;
      final List<Territory> zones =
          data.getMap().getNeighbors(t, Matches.territoryIsWater()).stream()
              .sorted(Comparator.comparing(Territory::getName))
              .toList();
      final List<Unit> all = new ArrayList<>(fleet);
      all.addAll(planes);
      all.addAll(transports);
      for (final Territory z : zones) {
        if (centre != null
            && turni(centre, z, fleet.isEmpty() ? transports : fleet) == Integer.MAX_VALUE) {
          continue;
        }
        final boolean battle = z.anyUnitsMatch(Matches.enemyUnit(player));
        final double o = mareObiettivo(z, all, battle);
        if (o < bestMare - 1e-9) {
          bestMare = o;
          bestZone = z;
          bestBattle = battle;
        }
      }
      if (bestZone == null) {
        det.addProperty("nessuna_zona_di_sbarco_raggiungibile", true);
        obs.add(
            new Ostacolo(
                Ostacolo.Tipo.DISTANZA,
                t.getName(),
                par.maxTurniDistanza() * par.puPerTurnoDistanza(),
                -1,
                det.deepCopy()));
        final Risposta.Bersaglio b =
            new Risposta.Bersaglio(t.getName(), null, false, obs, null, -1);
        analisi.put(key, b);
        return b;
      }
      final Territory z = bestZone;
      final double soglia = sogliaZona(z);
      final double enemyEnd = nemicaFine(z, all);
      final JsonObject mare = new JsonObject();
      mare.addProperty("zona", z.getName());
      mare.add("nostre", TrasportiProtetti.perTipo(all));
      mare.add(
          "nemiche_in_zona", TrasportiProtetti.perTipo(z.getMatches(Matches.enemyUnit(player))));
      final TrasportiProtetti.Sicurezza sic = sicurezza(z, all);
      mare.add("attacco_nemico", TrasportiProtetti.perTipo(sic.attaccanti()));
      mare.addProperty("vittoria_nemica_fine_turno_pct", r1(enemyEnd));
      mare.addProperty("soglia_pct", soglia);
      if (bestBattle) {
        mare.addProperty(
            "vittoria_nostra_contro_flotta_pct",
            r1(vittoria(z, fleet, difensoriMare(z), List.of())));
      }
      if (bestMare > 0) {
        bestEst = acquistoMare(z, all, bestBattle, 1000);
        mare.add("stima_acquisto", mappa(bestEst.unita()));
        mare.addProperty("stima_pu", bestEst.spesi());
        obs.add(new Ostacolo(Ostacolo.Tipo.MARE, z.getName(), puMare(bestEst), enemyEnd, mare));
      } else if (bestBattle) {
        obs.add(new Ostacolo(Ostacolo.Tipo.MARE, z.getName(), puBattibile(z, all), enemyEnd, mare));
      }
      det.add("mare", mare);
      // TERRA: the force that can land (loaded + ready) + land neighbours + carrier planes
      final List<Unit> att = new ArrayList<>(loaded);
      att.addAll(ready);
      att.addAll(landAdj);
      att.addAll(planes.stream().filter(Matches.unitCanLandOnCarrier()).toList());
      final List<Unit> bomb = bombardieri(fleet, loaded.size() + ready.size());
      final Terra terra = terra(t, att, bomb, def);
      final JsonObject tj = new JsonObject();
      tj.add("nostre", TrasportiProtetti.perTipo(att));
      tj.add("difensori", TrasportiProtetti.perTipo(def));
      tj.addProperty("vittoria_pct", r1(terra.vittoria()));
      tj.addProperty("truppe_mancanti", terra.truppeNecessarie());
      det.add("terra", tj);
      if (terra.truppeNecessarie() > 0 || !terra.basta()) {
        obs.add(new Ostacolo(Ostacolo.Tipo.TERRA, t.getName(), terra.pu(), terra.vittoria(), tj));
      }
      // CAPACITA: owned transports (anywhere, also empty and idle) first
      final int free = transports.stream().mapToInt(this::liberi).sum();
      final int need = posti(ready) + terra.truppeNecessarie() * 2;
      final int cap = transports.isEmpty() ? 5 : capacita(transports.get(0));
      final int missing = Risposta.trasportiDaComprare(need, free, cap > 0 ? cap : 5);
      final JsonObject cj = capacitaJson(transports, ready, loaded);
      cj.addProperty("posti_da_portare", need);
      cj.addProperty("trasporti_mancanti", missing);
      det.add("capacita", cj);
      if (missing > 0) {
        obs.add(
            new Ostacolo(
                Ostacolo.Tipo.CAPACITA,
                task.porto(),
                missing * costo(par.tipoTrasporto()),
                -1,
                cj));
      }
      // DISTANZA: fleet and transports from where they are
      final int tf =
          centre == null
              ? par.maxTurniDistanza()
              : Math.min(
                  par.maxTurniDistanza(), turni(centre, z, fleet.isEmpty() ? transports : fleet));
      int tt = 0;
      for (final Territory from : zoneDi(transports.isEmpty() ? fleet : transports)) {
        tt =
            Math.max(
                tt,
                Math.min(
                    par.maxTurniDistanza(),
                    turni(from, z, transports.isEmpty() ? fleet : transports)));
      }
      final int turns = Math.max(tf, Math.min(tt, par.maxTurniDistanza()));
      final JsonObject dj = new JsonObject();
      dj.addProperty("turni_flotta", tf);
      dj.addProperty("turni_trasporti", tt);
      det.add("distanza", dj);
      if (turns > 1) {
        obs.add(
            new Ostacolo(
                Ostacolo.Tipo.DISTANZA,
                z.getName(),
                (turns - 1) * par.puPerTurnoDistanza(),
                -1,
                dj));
      }
      // obtainable now: zone free, loaded transports (and ready troops loadable at the port) that
      // reach the zone this turn, a win >= the threshold, the zone safe at the end of the turn
      final boolean war = inGuerraCon(t);
      final List<Unit> trNow = raggiungono(transports, z, true);
      final List<Unit> cargoNow = new ArrayList<>();
      int portRoom = 0;
      for (final Unit tr : trNow) {
        cargoNow.addAll(tr.getTransporting());
        if (zonePorto().contains(dove(tr))) {
          portRoom += liberi(tr);
        }
      }
      for (final Unit u : ready) {
        final int c = u.getUnitAttachment().getTransportCost();
        if (c <= portRoom) {
          cargoNow.add(u);
          portRoom -= c;
        }
      }
      final List<Unit> fleetNow = raggiungono(fleet, z, true);
      final List<Unit> attNow = new ArrayList<>(cargoNow);
      attNow.addAll(landAdj);
      final List<Unit> planesNow =
          fleetNow.stream().anyMatch(Matches.unitIsCarrier())
              ? aereiDi(zoneDi(fleetNow.stream().filter(Matches.unitIsCarrier()).toList())).stream()
                  .filter(Matches.unitCanLandOnCarrier())
                  .toList()
              : List.of();
      attNow.addAll(planesNow);
      final List<Unit> arrivingNow = new ArrayList<>(fleetNow);
      arrivingNow.addAll(trNow);
      arrivingNow.addAll(planesNow);
      final double winNow =
          cargoNow.isEmpty() ? 0 : vittoria(t, attNow, def, bombardieri(fleetNow, cargoNow.size()));
      final double enemyNow = cargoNow.isEmpty() ? 100 : nemicaFine(z, arrivingNow);
      final boolean obtainable =
          war
              && !cargoNow.isEmpty()
              && !z.anyUnitsMatch(Matches.enemyUnit(player))
              && winNow >= par.sogliaAttacco()
              && enemyNow <= soglia;
      final JsonObject ora = new JsonObject();
      ora.addProperty("in_guerra", war);
      ora.addProperty("trasporti_che_arrivano", trNow.size());
      ora.addProperty("carico_che_arriva", cargoNow.size());
      ora.addProperty("navi_che_arrivano", fleetNow.size());
      ora.addProperty("vittoria_ora_pct", r1(winNow));
      ora.addProperty("vittoria_nemica_fine_turno_ora_pct", r1(enemyNow));
      det.add("ora", ora);
      // an enemy force behind the MARE obstacle that can be beaten now
      final String[] beatable = flottaBattibile(z, sic.attaccanti());
      final Risposta.Bersaglio b =
          new Risposta.Bersaglio(
              t.getName(),
              z.getName(),
              obtainable,
              List.copyOf(obs),
              beatable == null ? null : beatable[0],
              beatable == null ? -1 : Double.parseDouble(beatable[1]));
      analisi.put(key, b);
      dettagli.put(t.getName(), det);
      return b;
    }

    /**
     * Step P2: what the nation can land on {@code t} from zone {@code z} this turn (P1's "now"):
     * the loaded transports that reach the zone and the ready troops loadable at the port, the land
     * units next to {@code t}, the carriers' planes, the warships that reach the zone (bombard),
     * the win against the defenders, the zone safe at the end of the turn (A1).
     */
    record ForzaOra(
        List<Unit> carico,
        List<Unit> terraVicina,
        List<Unit> aerei,
        List<Unit> navi,
        List<Unit> trasporti,
        List<Unit> attaccanti,
        List<Unit> bombardieri,
        double vittoria,
        double nemicaFine,
        boolean sicura) {
      /**
       * The force takes the territory this turn: a win >= {@code soglia} with a landing whose zone
       * is safe, or by land alone (the units next to it).
       */
      boolean prende(final double soglia) {
        return !attaccanti.isEmpty()
            && vittoria >= soglia
            && (carico.isEmpty() ? !terraVicina.isEmpty() : sicura);
      }

      JsonObject json() {
        final JsonObject o = new JsonObject();
        o.add("carico", TrasportiProtetti.perTipo(carico));
        o.add("terra_vicina", TrasportiProtetti.perTipo(terraVicina));
        o.addProperty("navi", navi.size());
        o.addProperty("trasporti", trasporti.size());
        o.addProperty("vittoria_pct", r1(vittoria));
        o.addProperty("vittoria_nemica_zona_pct", r1(nemicaFine));
        o.addProperty("zona_sicura", sicura);
        return o;
      }
    }

    ForzaOra forzaOra(final Territory t, final Territory z) {
      final List<Unit> transports = trasporti();
      final List<Unit> ready = pronte();
      final List<Unit> fleet = flotta();
      final List<Unit> landAdj = terraConfinante(t);
      final List<Unit> def =
          t.getMatches(Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure()));
      final List<Unit> trNow = raggiungono(transports, z, true);
      final List<Unit> cargoNow = new ArrayList<>();
      int portRoom = 0;
      for (final Unit tr : trNow) {
        cargoNow.addAll(tr.getTransporting());
        if (zonePorto().contains(dove(tr))) {
          portRoom += liberi(tr);
        }
      }
      for (final Unit u : ready) {
        final int c = u.getUnitAttachment().getTransportCost();
        if (c <= portRoom) {
          cargoNow.add(u);
          portRoom -= c;
        }
      }
      final List<Unit> fleetNow = raggiungono(fleet, z, true);
      final List<Unit> planesNow =
          fleetNow.stream().anyMatch(Matches.unitIsCarrier())
              ? aereiDi(zoneDi(fleetNow.stream().filter(Matches.unitIsCarrier()).toList())).stream()
                  .filter(Matches.unitCanLandOnCarrier())
                  .toList()
              : List.of();
      final List<Unit> attNow = new ArrayList<>(cargoNow);
      attNow.addAll(landAdj);
      attNow.addAll(planesNow);
      final List<Unit> arriving = new ArrayList<>(fleetNow);
      arriving.addAll(trNow);
      arriving.addAll(planesNow);
      final List<Unit> bomb = bombardieri(fleetNow, cargoNow.size());
      final double win =
          cargoNow.isEmpty() && landAdj.isEmpty() ? 0 : vittoria(t, attNow, def, bomb);
      final double enemy = cargoNow.isEmpty() ? 100 : nemicaFine(z, arriving);
      final boolean safe =
          !cargoNow.isEmpty()
              && !z.anyUnitsMatch(Matches.enemyUnit(player))
              && enemy <= sogliaZona(z);
      return new ForzaOra(
          List.copyOf(cargoNow),
          List.copyOf(landAdj),
          List.copyOf(planesNow),
          List.copyOf(fleetNow),
          List.copyOf(trNow),
          List.copyOf(attNow),
          List.copyOf(bomb),
          win,
          enemy,
          safe);
    }

    /** Own land units next to {@code t} by land (they can attack it). */
    List<Unit> terraConfinante(final Territory t) {
      final List<Unit> out = new ArrayList<>();
      for (final Territory n : data.getMap().getNeighbors(t, Matches.territoryIsLand())) {
        out.addAll(
            n.getMatches((Unit u) -> squadra.contains(u.getOwner())).stream()
                .filter(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()))
                .filter(u -> u.getUnitAttachment().getAttack(u.getOwner()) > 0)
                .toList());
      }
      return out;
    }

    JsonObject capacitaJson(
        final List<Unit> transports, final List<Unit> ready, final List<Unit> loaded) {
      final JsonObject cj = new JsonObject();
      cj.addProperty("trasporti", transports.size());
      cj.addProperty(
          "trasporti_vuoti", (int) transports.stream().filter(x -> !x.isTransporting()).count());
      cj.addProperty(
          "trasporti_vuoti_fuori_porto",
          (int)
              transports.stream()
                  .filter(x -> !x.isTransporting() && !zonePorto().contains(dove(x)))
                  .count());
      cj.addProperty("posti_liberi", transports.stream().mapToInt(this::liberi).sum());
      cj.addProperty("truppe_pronte", ready.size());
      cj.addProperty("posti_truppe_pronte", posti(ready));
      cj.addProperty("truppe_caricate", loaded.size());
      return cj;
    }

    /**
     * An enemy force that makes the MARE obstacle of {@code z} (enemy ships in it, or among its
     * attackers) that the warships in reach beat this turn with a win >= the threshold: [zone,
     * win].
     */
    @Nullable
    String[] flottaBattibile(final Territory z, final List<Unit> attackers) {
      final Set<Territory> cand = new LinkedHashSet<>();
      if (z.anyUnitsMatch(Matches.enemyUnit(player).and(Matches.unitIsSea()))) {
        cand.add(z);
      }
      for (final Territory t : data.getMap().getTerritories()) {
        if (t.isWater()
            && t.anyUnitsMatch(Matches.enemyUnit(player).and(Matches.unitIsSea()))
            && attackers.stream().anyMatch(u -> t.getUnitCollection().contains(u))) {
          cand.add(t);
        }
      }
      String best = null;
      double bestWin = -1;
      int bestTuv = -1;
      for (final Territory e : cand) {
        final List<Unit> enemies = e.getMatches(Matches.enemyUnit(player));
        if (!inGuerraConUnita(enemies)) {
          continue;
        }
        final List<Unit> att = attaccantiMare(e);
        if (att.isEmpty()) {
          continue;
        }
        final double w = vittoria(e, att, difensoriMare(e), List.of());
        final int value = tuv(enemies);
        if (w >= par.sogliaAttacco() && value > bestTuv) {
          best = e.getName();
          bestWin = w;
          bestTuv = value;
        }
      }
      return best == null ? null : new String[] {best, String.valueOf(bestWin)};
    }

    /** Warships that reach {@code e} this turn, with the planes of the carriers among them. */
    List<Unit> attaccantiMare(final Territory e) {
      final List<Unit> ships =
          raggiungono(flotta(), e, true).stream()
              .filter(u -> u.getMovementLeft().signum() > 0 || dove(u).equals(e))
              .toList();
      final List<Unit> att = new ArrayList<>(ships);
      final List<Territory> carrierZones =
          zoneDi(ships.stream().filter(Matches.unitIsCarrier()).toList());
      att.addAll(aereiDi(carrierZones));
      return att;
    }

    /** CONTROLLO_E_FLOTTA: the territory of the alliance and a fleet the zone can hold. */
    Risposta.Bersaglio controllo(final Compito.Risultato r) {
      final Territory t = data.getMap().getTerritoryOrNull(r.territorio());
      final Territory z = data.getMap().getTerritoryOrNull(r.zona());
      if (t == null || z == null) {
        return new Risposta.Bersaglio(
            String.valueOf(r.territorio()), null, false, List.of(), null, -1);
      }
      final List<Ostacolo> obs = new ArrayList<>();
      final JsonObject det = new JsonObject();
      String beatable = null;
      double beatWin = -1;
      boolean obtainable;
      if (!alleato(t)) {
        // taken by the enemy: retaken (naval battle if needed + landing)
        final Risposta.Bersaglio land = sbarco(t);
        obs.addAll(land.ostacoli());
        obtainable = land.ottenibile();
        beatable = land.flottaBattibile();
        beatWin = land.vittoriaFlotta();
        det.add("presa", dettagli.getOrDefault(t.getName(), new JsonObject()));
      } else {
        obtainable = true;
      }
      final List<Unit> fleet = flotta();
      final List<Unit> all = new ArrayList<>(fleet);
      all.addAll(aereiInMare());
      final boolean battle = z.anyUnitsMatch(Matches.enemyUnit(player));
      final double soglia = sogliaZona(z);
      final double enemyEnd = nemicaFine(z, all);
      final TrasportiProtetti.Sicurezza sic = sicurezza(z, all);
      final JsonObject mare = new JsonObject();
      mare.addProperty("zona", z.getName());
      mare.add("nostre", TrasportiProtetti.perTipo(all));
      mare.add(
          "nemiche_in_zona", TrasportiProtetti.perTipo(z.getMatches(Matches.enemyUnit(player))));
      mare.add("attacco_nemico", TrasportiProtetti.perTipo(sic.attaccanti()));
      mare.addProperty("vittoria_nemica_fine_turno_pct", r1(enemyEnd));
      mare.addProperty("soglia_pct", soglia);
      final double o = mareObiettivo(z, all, battle);
      if (o > 0) {
        final AcquistoMare est = acquistoMare(z, all, battle, 1000);
        mare.add("stima_acquisto", mappa(est.unita()));
        mare.addProperty("stima_pu", est.spesi());
        obs.add(new Ostacolo(Ostacolo.Tipo.MARE, z.getName(), puMare(est), enemyEnd, mare));
        obtainable = false;
      } else if (battle) {
        obs.add(new Ostacolo(Ostacolo.Tipo.MARE, z.getName(), puBattibile(z, all), enemyEnd, mare));
      }
      det.add("mare", mare);
      final Territory centre = centroFlotta();
      final int turns =
          centre == null || fleet.isEmpty()
              ? par.maxTurniDistanza()
              : Math.min(par.maxTurniDistanza(), turni(centre, z, fleet));
      final JsonObject dj = new JsonObject();
      dj.addProperty("turni_flotta", turns);
      dj.addProperty("da", centre == null ? "" : centre.getName());
      det.add("distanza", dj);
      if (turns > 1) {
        obs.add(
            new Ostacolo(
                Ostacolo.Tipo.DISTANZA,
                z.getName(),
                (turns - 1) * par.puPerTurnoDistanza(),
                -1,
                dj));
      }
      final List<Unit> fleetNow = raggiungono(fleet, z, false);
      if (fleetNow.isEmpty() || battle) {
        obtainable = false;
      } else if (nemicaFine(z, fleetNow) > soglia) {
        obtainable = false;
      }
      det.addProperty("navi_che_arrivano_ora", fleetNow.size());
      if (beatable == null) {
        final String[] fb = flottaBattibile(z, sic.attaccanti());
        if (fb != null) {
          beatable = fb[0];
          beatWin = Double.parseDouble(fb[1]);
        }
      }
      dettagli.put(t.getName(), det);
      return new Risposta.Bersaglio(
          t.getName(), z.getName(), obtainable, List.copyOf(obs), beatable, beatWin);
    }

    /**
     * PRESA of capital {@code c}: the capital (landing if coastal, by land from a held neighbour
     * otherwise) and the enemy coastal territories next to it by land (bridgeheads).
     */
    List<Risposta.Bersaglio> presa(final Territory c) {
      final List<Risposta.Bersaglio> out = new ArrayList<>();
      final boolean coastal = !data.getMap().getNeighbors(c, Matches.territoryIsWater()).isEmpty();
      if (coastal) {
        out.add(sbarco(c));
      } else if (!terraConfinante(c).isEmpty()
          || data.getMap().getNeighbors(c, Matches.territoryIsLand()).stream()
              .anyMatch(this::alleato)) {
        // an inland capital is a target only from a held neighbour (otherwise its bridgeheads are)
        out.add(viaTerra(c));
      }
      for (final Territory n :
          data.getMap().getNeighbors(c, Matches.territoryIsLand()).stream()
              .sorted(Comparator.comparing(Territory::getName))
              .toList()) {
        if (!alleato(n)
            && inGuerraCon(n)
            && !data.getMap().getNeighbors(n, Matches.territoryIsWater()).isEmpty()) {
          final Risposta.Bersaglio b = sbarco(n);
          if (!coastal) {
            // the capital is still to be taken from the bridgehead: its TERRA counts
            final List<Ostacolo> obs = new ArrayList<>(b.ostacoli());
            terraCapitale(c).ifPresent(obs::add);
            out.add(
                new Risposta.Bersaglio(
                    b.nome(),
                    b.zona(),
                    b.ottenibile(),
                    List.copyOf(obs),
                    b.flottaBattibile(),
                    b.vittoriaFlotta()));
          } else {
            out.add(b);
          }
        }
      }
      return out;
    }

    /** TERRA of an inland capital against the whole landing force (taken from a bridgehead). */
    Optional<Ostacolo> terraCapitale(final Territory c) {
      final List<Unit> att = new ArrayList<>(caricate());
      att.addAll(pronte());
      att.addAll(terraConfinante(c));
      final List<Unit> def =
          c.getMatches(Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure()));
      final Terra terra = terra(c, att, List.of(), def);
      if (terra.truppeNecessarie() == 0 && terra.basta()) {
        return Optional.empty();
      }
      final JsonObject tj = new JsonObject();
      tj.add("nostre", TrasportiProtetti.perTipo(att));
      tj.add("difensori", TrasportiProtetti.perTipo(def));
      tj.addProperty("vittoria_pct", r1(terra.vittoria()));
      tj.addProperty("truppe_mancanti", terra.truppeNecessarie());
      return Optional.of(
          new Ostacolo(Ostacolo.Tipo.TERRA, c.getName(), terra.pu(), terra.vittoria(), tj));
    }

    /** An inland target taken by land from own units next to it; the zone of a held bridgehead. */
    Risposta.Bersaglio viaTerra(final Territory c) {
      final String key = "terra|" + c.getName();
      if (analisi.containsKey(key)) {
        return analisi.get(key);
      }
      final List<Ostacolo> obs = new ArrayList<>();
      final List<Unit> adj = terraConfinante(c);
      final List<Unit> def =
          c.getMatches(Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure()));
      final double v = vittoria(c, adj, def, List.of());
      final JsonObject tj = new JsonObject();
      tj.add("nostre", TrasportiProtetti.perTipo(adj));
      tj.add("difensori", TrasportiProtetti.perTipo(def));
      tj.addProperty("vittoria_pct", r1(v));
      // the zone next to a held bridgehead, where the troops are brought
      Territory zone = null;
      for (final Territory n :
          data.getMap().getNeighbors(c, Matches.territoryIsLand()).stream()
              .sorted(Comparator.comparing(Territory::getName))
              .toList()) {
        if (alleato(n)) {
          final Optional<Territory> z =
              data.getMap().getNeighbors(n, Matches.territoryIsWater()).stream()
                  .min(Comparator.comparing(Territory::getName));
          if (z.isPresent()) {
            zone = z.get();
            tj.addProperty("testa_di_ponte", n.getName());
            break;
          }
        }
      }
      if (adj.isEmpty() && zone == null) {
        tj.addProperty("nessuna_testa_di_ponte", true);
        obs.add(
            new Ostacolo(
                Ostacolo.Tipo.DISTANZA,
                c.getName(),
                par.maxTurniDistanza() * par.puPerTurnoDistanza(),
                -1,
                tj));
      } else {
        terraCapitale(c).ifPresent(obs::add);
        if (zone != null && v < par.sogliaAttacco()) {
          // DISTANZA: the troops on the transports or at the port, not yet at the bridgehead
          int turns = par.maxTurniDistanza();
          final List<Unit> transports = trasporti();
          for (final Territory from : zoneDi(transports)) {
            turns = Math.min(turns, turni(from, zone, transports));
          }
          final JsonObject dj = new JsonObject();
          dj.addProperty("zona", zone.getName());
          dj.addProperty("turni_trasporti", turns);
          dj.addProperty("vittoria_con_le_forze_confinanti_pct", r1(v));
          obs.add(
              new Ostacolo(
                  Ostacolo.Tipo.DISTANZA,
                  zone.getName(),
                  Math.max(1, turns) * par.puPerTurnoDistanza(),
                  -1,
                  dj));
        }
        if (zone != null) {
          final List<Unit> all = new ArrayList<>(flotta());
          all.addAll(trasporti());
          final double e = nemicaFine(zone, all);
          if (e > sogliaZona(zone)) {
            final AcquistoMare est = acquistoMare(zone, all, false, 1000);
            final JsonObject mare = new JsonObject();
            mare.addProperty("zona", zone.getName());
            mare.addProperty("vittoria_nemica_fine_turno_pct", r1(e));
            mare.add("stima_acquisto", mappa(est.unita()));
            obs.add(new Ostacolo(Ostacolo.Tipo.MARE, zone.getName(), puMare(est), e, mare));
          }
        }
      }
      final boolean obtainable = inGuerraCon(c) && v >= par.sogliaAttacco();
      final Risposta.Bersaglio b =
          new Risposta.Bersaglio(
              c.getName(),
              zone == null ? null : zone.getName(),
              obtainable,
              List.copyOf(obs),
              null,
              -1);
      analisi.put(key, b);
      final JsonObject det = new JsonObject();
      det.add("terra", tj);
      dettagli.put(c.getName(), det);
      return b;
    }

    // ------------------------------------------------------------------ the results

    /** The current result of the list (continuous results are never current). */
    @Nullable
    Compito.Risultato corrente() {
      int i = ns.has("indice") ? ns.get("indice").getAsInt() : 0;
      while (i < task.risultati().size() && task.risultati().get(i).continuo()) {
        i++;
      }
      return i < task.risultati().size() ? task.risultati().get(i) : null;
    }

    /** Step P2: the alliance's direction (null without the alliance level or before the choice). */
    @Nullable
    String direzioneAlleanza() {
      return Alleanza.direzione(this).map(Compito.Direzione::nome).orElse(null);
    }

    @Nullable
    Territory capitaleDirezione() {
      if (Alleanza.ha(this)) {
        return Alleanza.capitale(this).orElse(null);
      }
      if (!ns.has("direzione")) {
        return null;
      }
      return data.getMap()
          .getTerritoryOrNull(ns.getAsJsonObject("direzione").get("capitale").getAsString());
    }

    /** The result's condition on the board. */
    boolean soddisfatto(final Compito.Risultato r) {
      switch (r.tipo()) {
        case Compito.CONTROLLO_E_FLOTTA -> {
          final Territory t = data.getMap().getTerritoryOrNull(r.territorio());
          final Territory z = data.getMap().getTerritoryOrNull(r.zona());
          if (t == null || z == null || !alleato(t)) {
            return false;
          }
          final List<Unit> ships =
              z.getMatches(Matches.unitIsOwnedBy(player).and(Compiti::daGuerra));
          return !ships.isEmpty()
              && !z.anyUnitsMatch(Matches.enemyUnit(player))
              && nemicaFine(z, List.of()) <= sogliaZona(z);
        }
        case Compito.SCELTA -> {
          return ns.has("direzione");
        }
        case Compito.DIREZIONE_ALLEANZA -> {
          return Alleanza.direzione(this).isPresent();
        }
        case Compito.TESTA_DI_PONTE -> {
          return Alleanza.testaDiPonteTenuta(this).isPresent();
        }
        case Compito.PRESA -> {
          if (Alleanza.ha(this)) {
            return Alleanza.direzione(this).isPresent() && Alleanza.capitale(this).isEmpty();
          }
          final Territory c = capitaleDirezione();
          if (c == null) {
            return false;
          }
          final JsonObject seen = obj(ns, "visto_nemico");
          if (!alleato(c)) {
            seen.addProperty(c.getName(), round);
            return false;
          }
          return seen.has(c.getName());
        }
        default -> {
          return false;
        }
      }
    }

    /** A reached result with {@code mantieni}: only its control part must still hold. */
    boolean tenuto(final Compito.Risultato r) {
      if (Compito.CONTROLLO_E_FLOTTA.equals(r.tipo())) {
        final Territory t = data.getMap().getTerritoryOrNull(r.territorio());
        return t != null && alleato(t);
      }
      if (Compito.TESTA_DI_PONTE.equals(r.tipo())) {
        return Alleanza.testaDiPonteTenuta(this).isPresent();
      }
      return true;
    }

    void aggiornaRisultati() {
      int i = ns.has("indice") ? ns.get("indice").getAsInt() : 0;
      final JsonObject reached = obj(ns, "raggiunti");
      // a reached result to keep that no longer holds becomes the current one again
      for (int j = 0; j < i && j < task.risultati().size(); j++) {
        final Compito.Risultato r = task.risultati().get(j);
        if (!r.continuo() && r.mantieni() && !tenuto(r)) {
          registraRisultato(
              r, "PERSO", "il risultato " + r.id() + " non e' piu' tenuto: torna in corso");
          reached.remove(r.id());
          i = j;
          break;
        }
      }
      while (i < task.risultati().size()) {
        final Compito.Risultato r = task.risultati().get(i);
        if (r.continuo()) {
          // step P2: a continuous result is served every turn, never in the list's order
          i++;
          continue;
        }
        if (Compito.SCELTA.equals(r.tipo()) && !ns.has("direzione")) {
          direzione(r, true);
        }
        if (!soddisfatto(r)) {
          break;
        }
        if (!reached.has(r.id())) {
          reached.addProperty(r.id(), round);
          registraRisultato(r, "RAGGIUNTO", r.nome() + " raggiunto al round " + round);
        }
        i++;
      }
      ns.addProperty("indice", i);
      // missed target rounds (once)
      final JsonObject missed = obj(ns, "mancati");
      for (final Compito.Risultato r : task.risultati()) {
        if (r.traguardo() > 0
            && round > r.traguardo()
            && !reached.has(r.id())
            && !missed.has(r.id())
            && !(Compito.PRESA.equals(r.tipo()) && ns.has("primo_sbarco_capitale"))) {
          missed.addProperty(r.id(), round);
          registraRisultato(
              r, "MANCATO", r.nome() + ": traguardo del round " + r.traguardo() + " mancato");
        }
      }
    }

    void registraRisultato(final Compito.Risultato r, final String code, final String why) {
      final JsonObject par0 = new JsonObject();
      par0.addProperty("risultato", r.id());
      par0.addProperty("nome", r.nome());
      par0.addProperty("traguardo_round", r.traguardo());
      par0.addProperty("esito", code);
      scrivi(
          "compito_risultato",
          new JsonArray(),
          r.id() + " " + code,
          "RISULTATO_" + code,
          why,
          par0);
      lines.add("  Risultato " + r.id() + " (" + r.nome() + "): " + code + " - " + why);
    }

    /**
     * R2 (SCELTA): the option whose capital has the smallest sum of obstacles; kept {@code
     * tenuta_round} rounds, then checked again.
     */
    void direzione(final Compito.Risultato r, final boolean first) {
      final JsonObject old = ns.has("direzione") ? ns.getAsJsonObject("direzione") : null;
      if (!first
          && old != null
          && !Risposta.ricontrollaDirezione(
              round, old.get("dal_round").getAsInt(), r.tenutaRound())) {
        return;
      }
      final Map<String, Double> sums = new LinkedHashMap<>();
      final JsonArray alt = new JsonArray();
      for (final var e : r.opzioni().entrySet()) {
        final Territory c = data.getMap().getTerritoryOrNull(e.getValue());
        if (c == null) {
          continue;
        }
        final List<Risposta.Bersaglio> targets = presa(c);
        double best = Double.MAX_VALUE;
        String via = "";
        for (final Risposta.Bersaglio b : targets) {
          if (b.somma() < best) {
            best = b.somma();
            via = b.nome();
          }
        }
        if (alleato(c)) {
          best = Double.MAX_VALUE / 4;
          via = "gia' dell'alleanza";
        }
        sums.put(e.getKey(), best);
        final JsonObject a = new JsonObject();
        a.addProperty("opzione", e.getKey());
        a.addProperty("capitale", c.getName());
        a.addProperty("somma_ostacoli_pu", r1(Math.min(best, 1e9)));
        a.addProperty("via", via);
        final JsonArray ts = new JsonArray();
        targets.forEach(b -> ts.add(b.json()));
        a.add("bersagli", ts);
        alt.add(a);
      }
      if (sums.isEmpty()) {
        return;
      }
      final String choice = Risposta.scegliDirezione(sums);
      final String capital = r.opzioni().get(choice);
      final String code;
      if (old == null) {
        code = "DIREZIONE_SCELTA";
      } else if (old.get("scelta").getAsString().equals(choice)) {
        code = "DIREZIONE_TENUTA";
      } else {
        code = "DIREZIONE_CAMBIATA";
        ns.remove("bersaglio");
      }
      final JsonObject d = new JsonObject();
      d.addProperty("scelta", choice);
      d.addProperty("capitale", capital);
      d.addProperty("dal_round", round);
      d.addProperty("fino_al_round", round + r.tenutaRound() - 1);
      final JsonObject conti = new JsonObject();
      sums.forEach((k, v) -> conti.addProperty(k, r1(Math.min(v, 1e9))));
      d.add("conti", conti);
      ns.add("direzione", d);
      final String why =
          String.format(
              Locale.ROOT,
              "%s: somma degli ostacoli verso %s %.0f PU contro %s; vale fino al round %d",
              choice,
              capital,
              Math.min(sums.get(choice), 1e9),
              sums.entrySet().stream()
                  .filter(x -> !x.getKey().equals(choice))
                  .map(x -> x.getKey() + " " + Math.round(Math.min(x.getValue(), 1e9)))
                  .toList(),
              round + r.tenutaRound() - 1);
      final JsonObject par0 = new JsonObject();
      par0.addProperty("risultato", r.id());
      par0.add("conti", conti);
      par0.addProperty("scelta", choice);
      par0.addProperty("capitale", capital);
      par0.addProperty("fino_al_round", round + r.tenutaRound() - 1);
      par0.addProperty("tenuta_round", r.tenutaRound());
      scrivi("compito_direzione", alt, choice, code, why, par0);
      lines.add("  Direzione " + code + ": " + why);
    }

    List<Risposta.Bersaglio> bersagli(final Compito.Risultato r) {
      return switch (r.tipo()) {
        case Compito.CONTROLLO_E_FLOTTA -> List.of(controllo(r));
        case Compito.PRESA -> {
          final Territory c = capitaleDirezione();
          yield c == null ? List.of() : presa(c);
        }
        case Compito.TESTA_DI_PONTE -> Alleanza.bersaglio(this).map(List::of).orElse(List.of());
        default -> List.of();
      };
    }

    // ------------------------------------------------------------------ purchase

    /** The PUs of the turn: total, the task's share, what is left, the mix bought so far. */
    static final class Cassa {
      final int pus;
      final double quota;
      final int budget;
      int left;
      final Map<String, Integer> mix = new LinkedHashMap<>();
      final JsonArray righe = new JsonArray();

      /** Step P2 (C3, B): troops bought for a continuous result, placed at a given factory. */
      final Map<String, Map<String, Integer>> perFabbrica = new LinkedHashMap<>();

      Cassa(final int pus, final double quota) {
        this.pus = pus;
        this.quota = quota;
        this.budget = (int) Math.floor(pus * quota / 100.0);
        this.left = budget;
      }

      int spesi() {
        return budget - left;
      }
    }

    Cassa cassa() {
      final int pus =
          player
              .getResources()
              .getQuantity(
                  data.getResourceList().getResourceOrThrow(games.strategy.triplea.Constants.PUS));
      final boolean first =
          !task.risultati().isEmpty() && obj(ns, "raggiunti").has(primoSequenziale());
      final double prima = task.quotaPrima() == null ? par.quotaPrima() : task.quotaPrima();
      final double dopo = task.quotaDopo() == null ? par.quotaDopo() : task.quotaDopo();
      return new Cassa(pus, first ? dopo : prima);
    }

    /** The id of the first result in the list's order (continuous results excluded). */
    String primoSequenziale() {
      for (final Compito.Risultato r : task.risultati()) {
        if (!r.continuo()) {
          return r.id();
        }
      }
      return "";
    }

    void inizio() {
      lines.add("");
      lines.add(
          "=== R"
              + round
              + " "
              + player.getName()
              + " - Compito (step "
              + (Alleanza.ha(this) ? "P2" : "P1")
              + ") ===");
      esitiPrecedenti();
      final boolean livello = Alleanza.ha(this);
      if (livello) {
        // step P2: the alliance level (direction, bridgeheads taken) at its first nation's turn
        Alleanza.inizioRound(this);
      }
      aggiornaRisultati();
      if (livello) {
        // step P2: the shared result's decision of the round (first contributing nation)
        Alleanza.condivisoDelRound(this);
      } else {
        // R2 checked again every tenuta_round rounds while a later result is in progress
        for (final Compito.Risultato r : task.risultati()) {
          if (Compito.SCELTA.equals(r.tipo())
              && ns.has("direzione")
              && obj(ns, "raggiunti").has(r.id())) {
            direzione(r, false);
          }
        }
      }
      final JsonObject piano = new JsonObject();
      piano.addProperty("round", round);
      final Cassa cassa = cassa();
      // step P2: the continuous results first (constraint, territories held), then the current one
      final JsonArray tenute = new JsonArray();
      boolean constraintOk = true;
      for (final Compito.Risultato r : task.risultati()) {
        if (!r.continuo()) {
          continue;
        }
        final JsonObject x = tenuta(r, cassa);
        tenute.add(x);
        if (r.vincolo() && !x.get("regge").getAsBoolean()) {
          constraintOk = false;
        }
      }
      if (!tenute.isEmpty()) {
        piano.add("tenute", tenute);
      }
      if (!constraintOk) {
        // the constraint does not hold: the task takes no PU (Hard defends the capital)
        cassa.left = 0;
        cassa.mix.clear();
        cassa.perFabbrica.clear();
        lines.add("  Vincolo non rispettato: il compito non prende PU, tutto a Hard");
      }
      Compito.Risultato r = constraintOk ? corrente() : null;
      final String dir = direzioneAlleanza();
      if (r != null && !r.valePer(dir)) {
        lines.add("  Risultato " + r.id() + " non vale con la direzione " + dir + ": a Hard");
        r = null;
      }
      if (r == null) {
        if (corrente() == null) {
          lines.add("  Compito completato: tutto a Hard");
        }
        piano.add("uso", new JsonArray());
        ns.add("piano", piano);
        ordina(cassa, "tenute");
        final JsonObject par0 = new JsonObject();
        par0.addProperty("risultato", "");
        par0.add("tenute", tenute);
        par0.add("acquisti", cassa.righe);
        par0.add("risposte", new JsonArray());
        par0.addProperty("in_guerra", inGuerra());
        if (!tenute.isEmpty()) {
          scrivi(
              "compito_stato",
              new JsonArray(),
              "tenute",
              "OSTACOLI",
              "solo i risultati continui (tenute)",
              par0);
        }
        traccia();
        salva(data.getProperties(), alliance, st);
        Impegno.diarioRighe(data, lines);
        return;
      }
      piano.addProperty("risultato", r.id());
      piano.addProperty("tipo", r.tipo());
      final List<Risposta.Bersaglio> targets = bersagli(r);
      final Risposta.Scelta scelta =
          Risposta.decidi(
              targets,
              ns.has("bersaglio") ? ns.get("bersaglio").getAsString() : null,
              par.margineCambioBersaglio());
      final Risposta.Bersaglio b = scelta.bersaglio();
      final List<String> responses = new ArrayList<>();
      scelta.risposte().forEach(x -> responses.add(x.name()));
      if (cassa.left > 0) {
        acquisti(r, b, scelta.risposte(), responses, cassa);
      }
      final JsonArray purchases = cassa.righe;
      if (b != null) {
        ns.addProperty("bersaglio", b.nome());
        piano.addProperty("bersaglio", b.nome());
        if (b.zona() != null) {
          piano.addProperty("zona", b.zona());
        }
        piano.addProperty("ottenibile", b.ottenibile());
        if (b.flottaBattibile() != null
            && scelta.risposte().contains(Risposta.Tipo.ATTACCO_FLOTTA)) {
          piano.addProperty("flotta_battibile", b.flottaBattibile());
        }
      }
      final JsonArray rs = new JsonArray();
      responses.forEach(rs::add);
      piano.add("risposte", rs);
      // step P2 (D): what the task holds this turn; the rest goes back to Hard
      final Optional<JsonObject> shared =
          Compito.TESTA_DI_PONTE.equals(r.tipo())
              ? Alleanza.pianoCondiviso(this)
              : Optional.empty();
      shared.ifPresent(
          x -> x.entrySet().forEach(e -> piano.add(e.getKey(), e.getValue().deepCopy())));
      final Risposta.Condivisa dec =
          shared.isPresent() && shared.get().has("decisione")
              ? Risposta.Condivisa.valueOf(shared.get().get("decisione").getAsString())
              : null;
      if (Compito.PRESA.equals(r.tipo()) && b != null && !b.ottenibile()) {
        if (!ns.has("accumulo_capitale_dal")) {
          ns.addProperty("accumulo_capitale_dal", round);
        }
      } else {
        ns.remove("accumulo_capitale_dal");
      }
      final int accCap =
          Risposta.roundDiAccumulo(
              round,
              ns.has("accumulo_capitale_dal") ? ns.get("accumulo_capitale_dal").getAsInt() : 0);
      final boolean flag =
          Compito.CONTROLLO_E_FLOTTA.equals(r.tipo())
              ? r.territorio() != null
                  && data.getMap().getTerritoryOrNull(r.territorio()) != null
                  && !Alleanza.dellAlleanza(this, data.getMap().getTerritoryOrNull(r.territorio()))
              : b != null && b.ottenibile();
      final Set<Risposta.Uso> uso =
          livello
              ? Risposta.uso(r.tipo(), dec, flag, accCap, par.roundAccumuloMax())
              : Set.of(Risposta.Uso.FLOTTA, Risposta.Uso.SBARCO, Risposta.Uso.TERRA_VICINA);
      final JsonArray usoJ = new JsonArray();
      uso.stream().sorted().forEach(u -> usoJ.add(u.name()));
      piano.add("uso", usoJ);
      ns.add("piano", piano);
      ordina(cassa, r.id() + (b == null ? "" : " " + b.nome()));
      // compito_stato
      final JsonObject par0 = new JsonObject();
      par0.addProperty("risultato", r.id());
      par0.addProperty("nome", r.nome());
      par0.addProperty("ottenibile", b != null && b.ottenibile());
      if (b != null) {
        par0.add("bersaglio_scelto", b.json());
        final JsonArray types = new JsonArray();
        b.ostacoli().stream().filter(o -> o.pu() > 0).forEach(o -> types.add(o.tipo().name()));
        par0.add("tipi_ostacolo", types);
        par0.add("dettagli", dettagli.getOrDefault(b.nome(), new JsonObject()));
      }
      par0.add("risposte", rs);
      par0.add("acquisti", purchases);
      par0.addProperty("perche", scelta.perche());
      par0.addProperty("in_guerra", inGuerra());
      par0.add("uso", usoJ);
      if (!tenute.isEmpty()) {
        par0.add("tenute", tenute);
      }
      if (dir != null) {
        par0.addProperty("direzione_alleanza", dir);
      }
      shared.ifPresent(x -> par0.add("condiviso", x));
      par0.addProperty("pu", cassa.pus);
      par0.addProperty("quota_pct", cassa.quota);
      par0.addProperty("pu_al_compito", cassa.spesi());
      scrivi(
          "compito_stato",
          scelta.scartate(),
          b == null ? "nessuno" : b.nome(),
          b != null && b.ottenibile() ? "OTTENIBILE" : "OSTACOLI",
          scelta.perche(),
          par0);
      lines.add(
          "  Risultato in corso "
              + r.id()
              + " ("
              + r.nome()
              + "), bersaglio "
              + (b == null ? "nessuno" : b.nome() + (b.zona() == null ? "" : " da " + b.zona()))
              + (b != null && b.ottenibile() ? ": OTTENIBILE" : "")
              + ", uso "
              + uso);
      if (b != null) {
        b.ostacoli().forEach(o -> lines.add("    ostacolo " + o.testo()));
      }
      lines.add("    risposte " + responses + " - " + scelta.perche());
      traccia();
      salva(data.getProperties(), alliance, st);
      Impegno.diarioRighe(data, lines);
    }

    boolean inGuerra() {
      for (final GamePlayer other : data.getPlayerList().getPlayers()) {
        if (player.isAtWar(other)) {
          return true;
        }
      }
      return false;
    }

    // ------------------------------------------------------ continuous results (P2)

    /**
     * Step P2, a continuous result TENUTA of territory {@code r.territorio}: held (the enemy's
     * realistic attack wins <= {@code soglia_tenuta} against the allied defenders) or taken back
     * (own land units next to it win >= {@code soglia_attacco}). The obstacle is TERRA: the
     * infantry missing (defense) or the troops missing (attack); the response: own units that reach
     * it this turn move in (non-combat) or attack (combat), troops bought at an own factory in or
     * next to the territory (the PUs it needs, not a fixed share); a constraint ({@code vincolo})
     * only says whether it holds. Row {@code compito_tenuta}.
     */
    JsonObject tenuta(final Compito.Risultato r, final Cassa cassa) {
      final JsonObject x = new JsonObject();
      x.addProperty("risultato", r.id());
      x.addProperty("vincolo", r.vincolo());
      final Territory t = data.getMap().getTerritoryOrNull(r.territorio());
      if (t == null) {
        x.addProperty("regge", true);
        return x;
      }
      x.addProperty("territorio", t.getName());
      final boolean held = alleato(t);
      x.addProperty("alleato", held);
      String code;
      String why;
      if (held) {
        final List<Unit> def =
            new ArrayList<>(
                t.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotInfrastructure())));
        final double w = contrattaccoProprio(t, def);
        x.addProperty("vittoria_nemica_pct", r1(w));
        x.addProperty("soglia_pct", par.sogliaTenuta());
        final boolean ok = w <= par.sogliaTenuta();
        x.addProperty("regge", ok || !inGuerraConQualcuno());
        if (ok) {
          code = "TENUTO";
          why =
              String.format(
                  Locale.ROOT,
                  "%s regge: attacco realistico %.0f%% <= %.0f%%",
                  t.getName(),
                  w,
                  par.sogliaTenuta());
          x.addProperty("azione", "NIENTE");
        } else {
          // the infantry missing for the defense
          final String type = par.tipiDifesa().get(0);
          final List<Unit> more = new ArrayList<>(def);
          int n = 0;
          double after = w;
          while (n < par.maxTruppeStima() && after > par.sogliaTenuta()) {
            more.addAll(temp(type, 1));
            n++;
            if (n % 2 == 0 || n == par.maxTruppeStima()) {
              after = contrattaccoProprio(t, more);
            }
          }
          x.addProperty("truppe_mancanti", n);
          x.addProperty("vittoria_nemica_dopo_pct", r1(after));
          x.addProperty("azione", "DIFESA");
          code = r.vincolo() ? "VINCOLO_NON_RISPETTATO" : "MINACCIATO";
          why =
              String.format(
                  Locale.ROOT,
                  "%s minacciato: attacco realistico %.0f%% > %.0f%%, mancano %d %s",
                  t.getName(),
                  w,
                  par.sogliaTenuta(),
                  n,
                  type);
          if (!r.vincolo()) {
            compraTenuta(t, type, n, cassa, x);
          }
        }
      } else {
        // lost: taken back from the own land units next to it
        final List<Unit> adj =
            terraConfinante(t).stream().filter(u -> u.isOwnedBy(player)).toList();
        final List<Unit> def =
            t.getMatches(Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure()));
        final Terra terra = terra(t, adj, List.of(), def);
        x.addProperty("vittoria_nostra_pct", r1(terra.vittoria()));
        x.addProperty("truppe_mancanti", terra.truppeNecessarie());
        x.addProperty("regge", false);
        if (inGuerraCon(t) && terra.vittoria() >= par.sogliaAttacco() && !adj.isEmpty()) {
          x.addProperty("azione", "RIPRESA");
          code = "RIPRESA";
          why =
              String.format(
                  Locale.ROOT,
                  "%s perso: ripreso via terra (vittoria %.0f%%)",
                  t.getName(),
                  terra.vittoria());
        } else {
          x.addProperty("azione", "ACCUMULO");
          code = "ACCUMULO";
          why =
              String.format(
                  Locale.ROOT,
                  "%s perso: vittoria %.0f%%, mancano %d truppe",
                  t.getName(),
                  terra.vittoria(),
                  terra.truppeNecessarie());
          if (!r.vincolo() && terra.truppeNecessarie() > 0) {
            compraTenuta(t, par.tipiTruppe().get(0), terra.truppeNecessarie(), cassa, x);
          }
        }
      }
      scrivi("compito_tenuta", new JsonArray(), t.getName(), code, why, x.deepCopy());
      lines.add("  Tenuta " + r.id() + " " + t.getName() + ": " + code + " - " + why);
      return x;
    }

    boolean inGuerraConQualcuno() {
      return inGuerra();
    }

    /**
     * Troops for a continuous result: at an own factory in {@code t} or next to it (they reach it
     * this turn or the next); none = the PUs stay with Hard (obstacle DISTANZA, recorded).
     */
    void compraTenuta(
        final Territory t, final String type, final int n, final Cassa cassa, final JsonObject x) {
      Territory factory = null;
      final List<Territory> cand = new ArrayList<>();
      cand.add(t);
      data.getMap().getNeighbors(t, Matches.territoryIsLand()).stream()
          .sorted(Comparator.comparing(Territory::getName))
          .forEach(cand::add);
      for (final Territory f : cand) {
        if (f.isOwnedBy(player)
            && f.anyUnitsMatch(Matches.unitIsOwnedBy(player).and(Matches.unitCanProduceUnits()))
            && capienza(f) > 0) {
          factory = f;
          break;
        }
      }
      if (factory == null) {
        x.addProperty("fabbrica", "");
        x.addProperty(
            "nota", "nessuna fabbrica propria nel territorio o accanto: PU a Hard (DISTANZA)");
        return;
      }
      final int c = costo(type);
      int k = c <= 0 ? 0 : Math.min(n, Math.min(capienza(factory), cassa.left / c));
      if (k <= 0) {
        return;
      }
      cassa.left -= k * c;
      cassa
          .perFabbrica
          .computeIfAbsent(factory.getName(), q -> new LinkedHashMap<>())
          .merge(type, k, Integer::sum);
      x.addProperty("fabbrica", factory.getName());
      final JsonObject bought = new JsonObject();
      bought.addProperty(type, k);
      x.add("comprato", bought);
      x.addProperty("pu", k * c);
      final JsonObject row = new JsonObject();
      row.addProperty("ostacolo", "TERRA");
      row.addProperty("luogo", t.getName());
      row.add("comprato", bought.deepCopy());
      row.addProperty("pu", k * c);
      row.addProperty("fabbrica", factory.getName());
      cassa.righe.add(row);
    }

    /** The enemy's realistic attack on own (allied) {@code t} against {@code defenders}: win%. */
    double contrattaccoProprio(final Territory t, final List<Unit> defenders) {
      return Alleanza.contrattacco(this, t, defenders, false).vittoria();
    }

    /**
     * The purchases of the turn against the obstacles of the chosen target, the biggest first: MARE
     * one unit at a time (warships and carrier planes) until the zone can be held or the enemy
     * force beaten; TERRA troops; CAPACITA transports only if the owned ones (anywhere, also idle
     * and empty) cannot carry the ready troops. Share: 100% until the first result is reached, then
     * 80% (per nation in the data); the PUs the task cannot spend usefully go to Hard.
     */
    void acquisti(
        final Compito.Risultato r,
        @Nullable final Risposta.Bersaglio b,
        final List<Risposta.Tipo> planned,
        final List<String> responses,
        final Cassa cassa) {
      final JsonArray out = cassa.righe;
      final Map<String, Integer> mix = cassa.mix;
      if (b != null) {
        for (final Ostacolo o : Ostacolo.perGrandezza(b.ostacoli())) {
          if (cassa.left <= 0) {
            break;
          }
          final Territory where = data.getMap().getTerritoryOrNull(o.luogo());
          switch (o.tipo()) {
            case MARE -> {
              if (where == null) {
                break;
              }
              final List<Unit> all = new ArrayList<>(flotta());
              all.addAll(aereiInMare());
              all.addAll(trasporti());
              final boolean battle = where.anyUnitsMatch(Matches.enemyUnit(player));
              final AcquistoMare a = acquistoMare(where, all, battle, cassa.left);
              if (!a.unita().isEmpty()) {
                a.unita().forEach((k, v) -> mix.merge(k, v, Integer::sum));
                cassa.left -= a.spesi();
                final JsonObject x = new JsonObject();
                x.addProperty("ostacolo", "MARE");
                x.addProperty("luogo", where.getName());
                x.add("comprato", mappa(a.unita()));
                x.addProperty("pu", a.spesi());
                x.addProperty("vittoria_nemica_prima_pct", r1(a.nemicaPrima()));
                x.addProperty("vittoria_nemica_dopo_pct", r1(a.nemicaDopo()));
                x.addProperty("ostacolo_prima", r1(a.prima()));
                x.addProperty("ostacolo_dopo", r1(a.dopo()));
                x.addProperty("basta", a.basta());
                out.add(x);
                registraAcquisto(r, x);
              }
            }
            case TERRA -> {
              final int n =
                  o.numeri().has("truppe_mancanti")
                      ? o.numeri().get("truppe_mancanti").getAsInt()
                      : 0;
              final Map<String, Integer> troops = new LinkedHashMap<>();
              int spent = 0;
              for (int k = 0; k < n; k++) {
                final String type = par.tipiTruppe().get(k % par.tipiTruppe().size());
                if (costo(type) <= 0 || spent + costo(type) > cassa.left) {
                  break;
                }
                troops.merge(type, 1, Integer::sum);
                spent += costo(type);
              }
              if (!troops.isEmpty()) {
                troops.forEach((k, v) -> mix.merge(k, v, Integer::sum));
                cassa.left -= spent;
                final JsonObject x = new JsonObject();
                x.addProperty("ostacolo", "TERRA");
                x.addProperty("luogo", o.luogo());
                x.add("comprato", mappa(troops));
                x.addProperty("pu", spent);
                x.addProperty("vittoria_prima_pct", r1(o.vittoria()));
                x.addProperty("truppe_mancanti", n);
                out.add(x);
                registraAcquisto(r, x);
              }
            }
            default -> {
              // CAPACITA after the troops (below); DISTANZA: moves
            }
          }
        }
        // CAPACITA: after the troops of this turn, only with a landing obstacle of the target
        if (b.ha(Ostacolo.Tipo.CAPACITA) || b.ha(Ostacolo.Tipo.TERRA)) {
          final List<Unit> transports = trasporti();
          final List<Unit> ready = pronte();
          int bought = 0;
          for (final var e : mix.entrySet()) {
            final List<Unit> one = temp(e.getKey(), 1);
            if (!one.isEmpty() && Matches.unitIsLand().test(one.get(0))) {
              bought += e.getValue() * one.get(0).getUnitAttachment().getTransportCost();
            }
          }
          final int free = transports.stream().mapToInt(this::liberi).sum();
          int need = posti(ready) + bought;
          if (Alleanza.ha(this)) {
            // step P2: places for at most the largest landing force of the estimates
            // (max_truppe_stima troops), not for every troop at home
            need = Math.min(need, Math.max(bought, 2 * par.maxTruppeStima()));
          }
          final List<Unit> tr = temp(par.tipoTrasporto(), 1);
          final int cap = tr.isEmpty() ? 5 : capacita(tr.get(0));
          int n = Risposta.trasportiDaComprare(need, free, cap);
          final int c = costo(par.tipoTrasporto());
          n = c <= 0 ? 0 : Math.min(n, cassa.left / c);
          final JsonObject x = new JsonObject();
          x.addProperty("ostacolo", "CAPACITA");
          x.addProperty("luogo", task.porto());
          x.addProperty("posti_da_portare", need);
          x.addProperty("posti_liberi_posseduti", free);
          x.add("trasporti_posseduti", capacitaJson(transports, ready, caricate()));
          if (n > 0) {
            mix.merge(par.tipoTrasporto(), n, Integer::sum);
            cassa.left -= n * c;
            x.add("comprato", mappa(Map.of(par.tipoTrasporto(), n)));
            x.addProperty("pu", n * c);
            out.add(x);
            registraAcquisto(r, x);
          } else if (b.ha(Ostacolo.Tipo.CAPACITA)) {
            x.add("comprato", new JsonObject());
            x.addProperty("pu", 0);
            x.addProperty(
                "nota", "trasporti posseduti sufficienti o PU finiti: nessun trasporto nuovo");
            out.add(x);
            registraAcquisto(r, x);
          }
        }
      }
      // the responses that really bought something
      if (planned.contains(Risposta.Tipo.ACQUISTO_MARE) && !compratoPer(out, "MARE")) {
        responses.remove(Risposta.Tipo.ACQUISTO_MARE.name());
      }
      if (planned.contains(Risposta.Tipo.ACQUISTO_TERRA) && !compratoPer(out, "TERRA")) {
        responses.remove(Risposta.Tipo.ACQUISTO_TERRA.name());
      }
      if (planned.contains(Risposta.Tipo.ACQUISTO_CAPACITA) && !compratoPer(out, "CAPACITA")) {
        responses.remove(Risposta.Tipo.ACQUISTO_CAPACITA.name());
      }
    }

    /** The order of the turn (the task's purchases). */
    void ordina(final Cassa cassa, final String label) {
      final Map<String, Integer> mix = cassa.mix;
      final int spent = cassa.spesi();
      final Map<String, Integer> all = new LinkedHashMap<>(mix);
      cassa.perFabbrica.values().forEach(m -> m.forEach((k, v) -> all.merge(k, v, Integer::sum)));
      lines.add(
          String.format(
              Locale.ROOT,
              "  PU %d, quota %.0f%% = %d, al compito %d: %s; il resto a Hard",
              cassa.pus,
              cassa.quota,
              cassa.budget,
              spent,
              all.isEmpty() ? "niente" : all.toString()));
      // step P2-bis: no ban on Hard's transports any more (Hard buys and uses its own transports
      // with the PUs the task leaves it; the task buys its own for the CAPACITA obstacle)
      if (!all.isEmpty()) {
        final JsonObject order = new JsonObject();
        order.addProperty("round", round);
        order.addProperty("risultato", label);
        order.addProperty("porto", task.porto());
        order.addProperty(
            "quota_minima", cassa.pus <= 0 ? 0 : Math.min(100.0, 100.0 * spent / cassa.pus));
        final JsonArray voci = voci(mix);
        cassa.perFabbrica.forEach((f, m) -> m.forEach((k, v) -> voci.add(voce(k, v, f))));
        order.add("voci", voci);
        data.getProperties().set(KEY_ORDINE + player.getName(), (Serializable) null);
        data.getProperties().set(KEY_ORDINE + player.getName(), order.toString());
      }
    }

    private static boolean compratoPer(final JsonArray rows, final String type) {
      for (final JsonElement e : rows) {
        final JsonObject o = e.getAsJsonObject();
        if (type.equals(o.get("ostacolo").getAsString())
            && o.has("pu")
            && o.get("pu").getAsInt() > 0) {
          return true;
        }
      }
      return false;
    }

    /** Ships and planes at the port's factory; troops there up to its room, then the others. */
    JsonArray voci(final Map<String, Integer> mix) {
      final JsonArray out = new JsonArray();
      final List<String> factories = new ArrayList<>(task.fabbricheTruppe());
      if (!factories.contains(task.porto())) {
        factories.add(0, task.porto());
      }
      final Map<String, Integer> room = new LinkedHashMap<>();
      for (final String f : factories) {
        final Territory t = data.getMap().getTerritoryOrNull(f);
        room.put(f, t == null ? 0 : capienza(t));
      }
      for (final var e : mix.entrySet()) {
        final List<Unit> one = temp(e.getKey(), 1);
        final boolean land = !one.isEmpty() && Matches.unitIsLand().test(one.get(0));
        int n = e.getValue();
        if (!land) {
          out.add(voce(e.getKey(), n, task.porto()));
          room.merge(task.porto(), -n, Integer::sum);
          continue;
        }
        for (final String f : factories) {
          if (n <= 0) {
            break;
          }
          final int k = Math.min(n, Math.max(0, room.get(f)));
          if (k > 0) {
            out.add(voce(e.getKey(), k, f));
            room.merge(f, -k, Integer::sum);
            n -= k;
          }
        }
        if (n > 0) {
          out.add(voce(e.getKey(), n, task.porto()));
        }
      }
      return out;
    }

    private static JsonObject voce(final String type, final int n, final String factory) {
      final JsonObject v = new JsonObject();
      v.addProperty("tipo", type);
      v.addProperty("n", n);
      v.addProperty("fabbrica", factory);
      return v;
    }

    int capienza(final Territory t) {
      try {
        return Math.max(
            0, games.strategy.triplea.attachments.TerritoryAttachment.getUnitProduction(t));
      } catch (final RuntimeException e) {
        return 10;
      }
    }

    void registraAcquisto(final Compito.Risultato r, final JsonObject x) {
      final JsonObject par0 = x.deepCopy();
      par0.addProperty("risultato", r.id());
      par0.add("trasporti", capacitaJson(trasporti(), pronte(), caricate()));
      scrivi(
          "compito_acquisto",
          new JsonArray(),
          x.has("comprato") ? x.get("comprato").toString() : "niente",
          "ACQUISTO_" + x.get("ostacolo").getAsString(),
          "acquisto contro l'ostacolo "
              + x.get("ostacolo").getAsString()
              + " in "
              + x.get("luogo").getAsString(),
          par0);
    }

    // ---------------------------------------------------------- outcomes, tracking

    /** Ids of the transports (loaded or not) at the end of the turn, for the losses. */
    void traccia() {
      final JsonObject tr = new JsonObject();
      // step P2: since when every transport is loaded (the troops on board)
      final JsonObject old = obj(ns, "a_bordo");
      final JsonObject since = new JsonObject();
      for (final Unit u : trasporti()) {
        tr.addProperty(u.getId().toString(), u.isTransporting() ? u.getTransporting().size() : 0);
        if (u.isTransporting()) {
          final String id = u.getId().toString();
          since.addProperty(id, old.has(id) ? old.get(id).getAsInt() : round);
        }
      }
      ns.add("a_bordo", since);
      ns.add("trasporti_ids", tr);
      final JsonObject fl = new JsonObject();
      for (final Unit u : flotta()) {
        fl.addProperty(u.getId().toString(), u.getType().getName());
      }
      ns.add("flotta_ids", fl);
    }

    /** The transports and warships lost since the last turn of the nation. */
    void esitiPrecedenti() {
      final Set<String> alive = new LinkedHashSet<>();
      luoghi().keySet().forEach(u -> alive.add(u.getId().toString()));
      int emptyLost = 0;
      int loadedLost = 0;
      int cargoLost = 0;
      if (ns.has("trasporti_ids")) {
        for (final var e : ns.getAsJsonObject("trasporti_ids").entrySet()) {
          if (!alive.contains(e.getKey())) {
            if (e.getValue().getAsInt() > 0) {
              loadedLost++;
              cargoLost += e.getValue().getAsInt();
            } else {
              emptyLost++;
            }
          }
        }
      }
      final Map<String, Integer> shipsLost = new TreeMap<>();
      if (ns.has("flotta_ids")) {
        for (final var e : ns.getAsJsonObject("flotta_ids").entrySet()) {
          if (!alive.contains(e.getKey())) {
            shipsLost.merge(e.getValue().getAsString(), 1, Integer::sum);
          }
        }
      }
      if (emptyLost + loadedLost > 0 || !shipsLost.isEmpty()) {
        final JsonObject par0 = new JsonObject();
        par0.addProperty("trasporti_vuoti_persi", emptyLost);
        par0.addProperty("trasporti_carichi_persi", loadedLost);
        par0.addProperty("carico_perso", cargoLost);
        par0.add("navi_da_guerra_perse", mappa(shipsLost));
        par0.addProperty("compito", true);
        scrivi(
            "perdite_in_mare",
            new JsonArray(),
            (emptyLost + loadedLost) + " trasporti",
            "PERDITE",
            "unita' del compito perse dall'ultimo turno",
            par0);
        lines.add(
            "  Perse dall'ultimo turno: trasporti vuoti "
                + emptyLost
                + ", carichi "
                + loadedLost
                + " (carico "
                + cargoLost
                + "), navi da guerra "
                + shipsLost);
      }
    }

    // --------------------------------------------------------------------- combat

    Set<Unit> combattimento(final IMoveDelegate delegate) {
      this.moveDel = delegate;
      final Set<Unit> moved = new LinkedHashSet<>();
      final JsonObject piano = ns.has("piano") ? ns.getAsJsonObject("piano") : null;
      if (piano == null || piano.get("round").getAsInt() != round) {
        return moved;
      }
      lines.add("");
      lines.add("=== R" + round + " " + player.getName() + " - Compito, combattimento ===");
      // step P2: a continuous result lost is taken back by land
      if (piano.has("tenute")) {
        for (final JsonElement e : piano.getAsJsonArray("tenute")) {
          final JsonObject x = e.getAsJsonObject();
          if (x.has("azione") && "RIPRESA".equals(x.get("azione").getAsString())) {
            final Territory t = data.getMap().getTerritoryOrNull(x.get("territorio").getAsString());
            if (t != null && !alleato(t) && inGuerraCon(t)) {
              moved.addAll(attaccoTerra(t));
            }
          }
        }
      }
      if (!piano.has("risultato")) {
        salva(data.getProperties(), alliance, st);
        Impegno.diarioRighe(data, lines);
        return moved;
      }
      // 1 the enemy force that can be beaten
      if (piano.has("flotta_battibile")) {
        final Territory e =
            data.getMap().getTerritoryOrNull(piano.get("flotta_battibile").getAsString());
        if (e != null) {
          moved.addAll(attaccaFlotta(e));
        }
      }
      // 2 the landing / land attack on the chosen target
      if (piano.has("ottenibile")
          && piano.get("ottenibile").getAsBoolean()
          && piano.has("bersaglio")) {
        final Territory t = data.getMap().getTerritoryOrNull(piano.get("bersaglio").getAsString());
        if (t != null && !alleato(t) && inGuerraCon(t)) {
          final Territory z =
              piano.has("zona")
                  ? data.getMap().getTerritoryOrNull(piano.get("zona").getAsString())
                  : null;
          moved.addAll(
              z == null || !data.getMap().getNeighbors(t).contains(z)
                  ? attaccoTerra(t)
                  : sbarca(t, z, moved));
        }
      }
      salva(data.getProperties(), alliance, st);
      Impegno.diarioRighe(data, lines);
      return moved;
    }

    /** ATTACCO_FLOTTA: the warships in reach (and their carriers' planes) attack zone {@code e}. */
    Set<Unit> attaccaFlotta(final Territory e) {
      final Set<Unit> moved = new LinkedHashSet<>();
      aggiorna();
      final List<Unit> enemies = e.getMatches(Matches.enemyUnit(player));
      if (enemies.isEmpty() || !inGuerraConUnita(enemies)) {
        return moved;
      }
      final List<Unit> att = attaccantiMare(e);
      final List<Unit> def = difensoriMare(e);
      final double w = vittoria(e, att, def, List.of());
      final JsonObject par0 = new JsonObject();
      par0.addProperty("zona", e.getName());
      par0.add("attaccanti", TrasportiProtetti.perTipo(att));
      par0.add("difensori", TrasportiProtetti.perTipo(def));
      par0.addProperty("vittoria_pct", r1(w));
      par0.addProperty("soglia_pct", par.sogliaAttacco());
      if (w < par.sogliaAttacco()) {
        scrivi(
            "compito_battaglia",
            new JsonArray(),
            "niente",
            "NON_ESEGUITO",
            "sul tabellone reale la vittoria e' sotto la soglia",
            par0);
        return moved;
      }
      // ships by zone (carriers with their planes)
      final Map<Territory, List<Unit>> byZone = new LinkedHashMap<>();
      for (final Unit u : att) {
        final Territory at = dove(u);
        if (at != null && !at.equals(e)) {
          byZone.computeIfAbsent(at, k -> new ArrayList<>()).add(u);
        }
      }
      for (final var g : byZone.entrySet()) {
        final List<Unit> ships = g.getValue().stream().filter(Compiti::nave).toList();
        final List<Unit> planes = g.getValue().stream().filter(Matches.unitIsAir()).toList();
        if (!ships.isEmpty()) {
          final Optional<Route> r = rottaMare(g.getKey(), e, ships, true);
          if (r.isPresent() && moveDel.performMove(new MoveDescription(ships, r.get())).isEmpty()) {
            moved.addAll(ships);
          }
        }
        for (final Unit pl : planes) {
          final Route r = data.getMap().getRoute(g.getKey(), e, x -> true).orElse(null);
          if (r != null && moveDel.performMove(new MoveDescription(List.of(pl), r)).isEmpty()) {
            moved.add(pl);
          }
        }
      }
      aggiorna();
      final String id = nuovoId("compito_battaglia");
      par0.add("mosse", TrasportiProtetti.perTipo(moved));
      scrivi(
          id,
          "compito_battaglia",
          new JsonArray(),
          e.getName(),
          "ATTACCO_FLOTTA",
          String.format(
              Locale.ROOT,
              "flotta nemica in %s attaccata con vittoria stimata %.1f%%",
              e.getName(),
              w),
          par0);
      final JsonObject pend = new JsonObject();
      pend.addProperty("id", id);
      pend.addProperty("zona", e.getName());
      pend.addProperty("tuv_nemico", tuv(enemies));
      final JsonArray ids = new JsonArray();
      moved.forEach(u -> ids.add(u.getId().toString()));
      pend.add("unita", ids);
      ns.add("battaglia", pend);
      lines.add(
          String.format(
              Locale.ROOT,
              "  ATTACCO FLOTTA in %s: %d unita', vittoria %.1f%%",
              e.getName(),
              moved.size(),
              w));
      return moved;
    }

    /**
     * ESEGUI on a coastal target: loaded transports (and the ready troops at the port) that reach
     * the zone, with the escort of the warships that reach it, unload on the target; the land units
     * next to it and the carriers' planes in the zone attack too. Checked again on the real board:
     * zone free and safe at the end of the turn (<= soglia_trasporti), win >= the threshold.
     */
    Set<Unit> sbarca(final Territory t, final Territory z, final Set<Unit> alreadyMoved) {
      final Set<Unit> moved = new LinkedHashSet<>();
      aggiorna();
      final JsonObject par0 = new JsonObject();
      par0.addProperty("bersaglio", t.getName());
      par0.addProperty("zona", z.getName());
      par0.addProperty("compito", true);
      final Territory capital = capitaleDirezione();
      final boolean towardCapital =
          capital != null && (capital.equals(t) || data.getMap().getNeighbors(capital).contains(t));
      par0.addProperty("verso_capitale", towardCapital);
      par0.addProperty("capitale", capital == null ? "" : capital.getName());
      if (Alleanza.ha(this)) {
        // step P2: the shared result's decision of the round (CONGIUNTO, LOGORAMENTO, ...)
        par0.addProperty("decisione_alleanza", Alleanza.decisioneDelRound(this));
        par0.addProperty(
            "condiviso",
            ns.has("piano")
                && ns.getAsJsonObject("piano").has("condiviso_territorio")
                && ns.getAsJsonObject("piano")
                    .get("condiviso_territorio")
                    .getAsString()
                    .equals(t.getName()));
      }
      if (z.anyUnitsMatch(Matches.enemyUnit(player))) {
        scrivi(
            "sbarco",
            new JsonArray(),
            "niente",
            "NON_ESEGUITO",
            "zona di sbarco occupata dal nemico",
            par0);
        return moved;
      }
      final List<Unit> transports =
          raggiungono(trasporti(), z, true).stream()
              .filter(u -> !alreadyMoved.contains(u))
              .toList();
      final List<Unit> escort =
          raggiungono(flotta(), z, true).stream().filter(u -> !alreadyMoved.contains(u)).toList();
      // load the ready troops at the port on the empty places of the transports there
      final List<Unit> ready = new ArrayList<>(pronte());
      ready.sort(Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getAttack(player)));
      final Map<Unit, List<Unit>> toLoad = new LinkedHashMap<>();
      for (final Unit tr : transports) {
        final Territory at = dove(tr);
        if (!zonePorto().contains(at)) {
          continue;
        }
        int room = liberi(tr);
        for (final Unit u : new ArrayList<>(ready)) {
          final int c = u.getUnitAttachment().getTransportCost();
          final Territory from = dove(u);
          if (c <= room && from != null && data.getMap().getNeighbors(from).contains(at)) {
            toLoad.computeIfAbsent(tr, k -> new ArrayList<>()).add(u);
            room -= c;
            ready.remove(u);
          }
        }
      }
      final List<Unit> cargo = new ArrayList<>();
      transports.forEach(tr -> cargo.addAll(tr.getTransporting()));
      toLoad.values().forEach(cargo::addAll);
      final List<Unit> landAdj = terraConfinante(t);
      // step P2: nothing to land, the units next to it attack by land
      if (Alleanza.ha(this) && cargo.isEmpty() && !landAdj.isEmpty()) {
        return attaccoTerra(t);
      }
      final List<Unit> planes =
          escort.stream().anyMatch(Matches.unitIsCarrier())
              ? aereiDi(zoneDi(escort.stream().filter(Matches.unitIsCarrier()).toList())).stream()
                  .filter(Matches.unitCanLandOnCarrier())
                  .toList()
              : List.of();
      final List<Unit> att = new ArrayList<>(cargo);
      att.addAll(landAdj);
      att.addAll(planes);
      final List<Unit> def =
          t.getMatches(Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure()));
      final double w = vittoria(t, att, def, bombardieri(escort, cargo.size()));
      final List<Unit> arriving = new ArrayList<>(escort);
      arriving.addAll(
          transports.stream().filter(x -> x.isTransporting() || toLoad.containsKey(x)).toList());
      arriving.addAll(planes);
      final TrasportiProtetti.Sicurezza sic = sicurezza(z, arriving);
      par0.add("attaccanti", TrasportiProtetti.perTipo(att));
      par0.add("difensori", TrasportiProtetti.perTipo(def));
      par0.add("scorta", TrasportiProtetti.perTipo(escort));
      par0.addProperty("vittoria_pct", r1(w));
      par0.add("sicurezza", sic.json());
      if (cargo.isEmpty() || w < par.sogliaAttacco() || !sic.sicura()) {
        scrivi(
            "sbarco",
            new JsonArray(),
            "niente",
            "NON_ESEGUITO",
            cargo.isEmpty()
                ? "nessun carico"
                : w < par.sogliaAttacco()
                    ? "vittoria sotto la soglia sul tabellone reale"
                    : "zona di sbarco non sicura per i trasporti a fine turno",
            par0);
        lines.add(
            "  Sbarco su "
                + t.getName()
                + " NON eseguito (vittoria "
                + r1(w)
                + "%, zona "
                + (sic.sicura() ? "sicura" : "non sicura")
                + ")");
        return moved;
      }
      // load
      for (final var e : toLoad.entrySet()) {
        for (final Unit u : e.getValue()) {
          final Territory from = dove(u);
          if (from == null) {
            continue;
          }
          final Optional<String> err =
              moveDel.performMove(
                  new MoveDescription(
                      List.of(u), new Route(from, dove(e.getKey())), Map.of(u, e.getKey())));
          if (err.isPresent()) {
            ProLogger.warn("[Pro+] Compito: carico non riuscito " + err.get());
          }
        }
      }
      aggiorna();
      // escort first (the zone is free), then the transports with their cargo
      for (final var g : perZona(escort).entrySet()) {
        if (g.getKey().equals(z)) {
          moved.addAll(g.getValue());
          continue;
        }
        final List<Unit> ships = g.getValue();
        rottaMare(g.getKey(), z, ships, true)
            .ifPresent(
                r -> {
                  if (moveDel.performMove(new MoveDescription(ships, r)).isEmpty()) {
                    moved.addAll(ships);
                  }
                });
      }
      aggiorna();
      int landed = 0;
      for (final Unit tr : transports) {
        final Territory at = dove(tr);
        if (at == null || !tr.isTransporting()) {
          continue;
        }
        final List<Unit> load = new ArrayList<>(tr.getTransporting());
        if (!at.equals(z)) {
          final List<Unit> group = new ArrayList<>(List.of(tr));
          group.addAll(load);
          final Optional<Route> r = rottaMare(at, z, List.of(tr), true);
          if (r.isEmpty() || moveDel.performMove(new MoveDescription(group, r.get())).isPresent()) {
            continue;
          }
        }
        moved.add(tr);
        if (Alleanza.ha(this)) {
          // step P2: units that cannot move in the combat move (AA guns) stay on board
          load.removeIf(Matches.unitCanNotMoveDuringCombatMove());
          if (load.isEmpty()) {
            continue;
          }
        }
        final Optional<String> err =
            moveDel.performMove(new MoveDescription(load, new Route(z, t)));
        if (err.isEmpty()) {
          landed += load.size();
          moved.addAll(load);
        } else {
          par0.addProperty("errore_motore", err.get());
        }
      }
      aggiorna();
      // land neighbours and the carriers' planes
      for (final Unit u : landAdj) {
        final Territory from = dove(u);
        if (from != null
            && u.getMovementLeft().signum() > 0
            && moveDel.performMove(new MoveDescription(List.of(u), new Route(from, t))).isEmpty()) {
          moved.add(u);
        }
      }
      if (landed > 0) {
        for (final Unit pl : planes) {
          final Territory from = dove(pl);
          if (from == null) {
            continue;
          }
          final Route r = data.getMap().getRoute(from, t, x -> true).orElse(null);
          if (r != null
              && r.numberOfSteps() * 2 <= pl.getMovementLeft().intValue()
              && moveDel.performMove(new MoveDescription(List.of(pl), r)).isEmpty()) {
            moved.add(pl);
          }
        }
      }
      aggiorna();
      final String id = nuovoId("sbarco");
      par0.addProperty("unita_sbarcate", landed);
      scrivi(
          id,
          "sbarco",
          new JsonArray(),
          t.getName(),
          landed > 0 ? "ESEGUITO" : "NON_ESEGUITO",
          landed > 0
              ? "sbarco del compito: " + landed + " unita' su " + t.getName()
              : "mosse rifiutate dal motore",
          par0);
      if (landed > 0) {
        final JsonObject pend = new JsonObject();
        pend.addProperty("id", id);
        pend.addProperty("bersaglio", t.getName());
        pend.addProperty("unita", landed);
        ns.add("sbarco_in_corso", pend);
        if (towardCapital && !ns.has("primo_sbarco_capitale")) {
          ns.addProperty("primo_sbarco_capitale", round);
        }
      }
      lines.add(
          "  SBARCO su "
              + t.getName()
              + " da "
              + z.getName()
              + ": "
              + landed
              + " unita', vittoria "
              + r1(w)
              + "%");
      return moved;
    }

    /** ESEGUI on an inland target: the own land units next to it attack (win >= threshold). */
    Set<Unit> attaccoTerra(final Territory t) {
      final Set<Unit> moved = new LinkedHashSet<>();
      aggiorna();
      final List<Unit> adj =
          terraConfinante(t).stream().filter(u -> u.getMovementLeft().signum() > 0).toList();
      final List<Unit> def =
          t.getMatches(Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure()));
      final double w = vittoria(t, adj, def, List.of());
      final JsonObject par0 = new JsonObject();
      par0.addProperty("bersaglio", t.getName());
      par0.add("attaccanti", TrasportiProtetti.perTipo(adj));
      par0.add("difensori", TrasportiProtetti.perTipo(def));
      par0.addProperty("vittoria_pct", r1(w));
      par0.addProperty("compito", true);
      par0.addProperty("via_terra", true);
      if (Alleanza.ha(this)) {
        par0.addProperty("decisione_alleanza", Alleanza.decisioneDelRound(this));
      }
      final Territory capital = capitaleDirezione();
      par0.addProperty("verso_capitale", capital != null && capital.equals(t));
      if (w < par.sogliaAttacco()) {
        scrivi(
            "sbarco",
            new JsonArray(),
            "niente",
            "NON_ESEGUITO",
            "attacco via terra sotto la soglia",
            par0);
        return moved;
      }
      for (final Unit u : adj) {
        final Territory from = dove(u);
        if (from != null
            && moveDel.performMove(new MoveDescription(List.of(u), new Route(from, t))).isEmpty()) {
          moved.add(u);
        }
      }
      aggiorna();
      final String id = nuovoId("sbarco");
      par0.addProperty("unita_attaccanti", moved.size());
      scrivi(
          id,
          "sbarco",
          new JsonArray(),
          t.getName(),
          moved.isEmpty() ? "NON_ESEGUITO" : "ESEGUITO",
          "attacco via terra del compito su " + t.getName(),
          par0);
      if (!moved.isEmpty()) {
        final JsonObject pend = new JsonObject();
        pend.addProperty("id", id);
        pend.addProperty("bersaglio", t.getName());
        pend.addProperty("unita", moved.size());
        ns.add("sbarco_in_corso", pend);
      }
      lines.add(
          "  ATTACCO VIA TERRA su "
              + t.getName()
              + ": "
              + moved.size()
              + " unita', vittoria "
              + r1(w)
              + "%");
      return moved;
    }

    Map<Territory, List<Unit>> perZona(final Collection<Unit> units) {
      final Map<Territory, List<Unit>> out = new LinkedHashMap<>();
      for (final Unit u : units) {
        final Territory at = dove(u);
        if (at != null) {
          out.computeIfAbsent(at, k -> new ArrayList<>()).add(u);
        }
      }
      return out;
    }

    // ------------------------------------------------------------------ non combat

    Set<Unit> nonCombattimento(final IMoveDelegate delegate) {
      this.moveDel = delegate;
      final Set<Unit> held = new LinkedHashSet<>();
      lines.add("");
      lines.add("=== R" + round + " " + player.getName() + " - Compito, non combattimento ===");
      esitiCombattimento();
      final JsonObject piano = ns.has("piano") ? ns.getAsJsonObject("piano") : new JsonObject();
      final boolean livello = Alleanza.ha(this);
      // step P2 (D): what the task uses this turn (P1: everything)
      final Set<String> uso = new java.util.HashSet<>();
      if (!livello) {
        for (final Risposta.Uso u : Risposta.Uso.values()) {
          uso.add(u.name());
        }
      } else if (piano.has("uso") && piano.get("round").getAsInt() == round) {
        piano.getAsJsonArray("uso").forEach(e -> uso.add(e.getAsString()));
      }
      final boolean fleetUse = uso.contains(Risposta.Uso.FLOTTA.name());
      final boolean landingUse = uso.contains(Risposta.Uso.SBARCO.name());
      Territory dest = null;
      if (piano.has("zona")) {
        dest = data.getMap().getTerritoryOrNull(piano.get("zona").getAsString());
      }
      final JsonObject moves = new JsonObject();
      Territory fleetAt = null;
      if (landingUse) {
        // 1 load the ready troops at the port
        final int loaded = carica();
        moves.addProperty("caricate_al_porto", loaded);
        aggiorna();
        // 2 the convoy: the whole fleet and the loaded transports toward the destination, stopping
        // together only where the zone can be held (A1); without loaded transports the fleet alone
        if (dest != null && piano.has("risultato")) {
          fleetAt = convoglio(dest, moves);
        }
        aggiorna();
        // 3 the loaded transports the convoy did not take; the empty ones back to the port
        muoviTrasporti(fleetAt != null ? fleetAt : dest, moves);
        aggiorna();
        // 4 unload on a held bridgehead next to the target
        if (piano.has("bersaglio")) {
          final Territory t =
              data.getMap().getTerritoryOrNull(piano.get("bersaglio").getAsString());
          if (t != null) {
            moves.addProperty("scaricate_testa_di_ponte", scaricaTestaDiPonte(t));
            // step P2: the shared bridgehead taken this round is reinforced
            if (alleato(t)) {
              moves.addProperty("scaricate_sul_bersaglio", scaricaSu(t));
            }
          }
        }
        aggiorna();
        // step P2: troops on board for too long land on allied ground
        if (livello) {
          moves.addProperty("scaricate_dopo_attesa", limiteABordo());
          aggiorna();
        }
      } else if (fleetUse && dest != null && piano.has("risultato")) {
        fleetAt = muoviFlotta(dest, moves);
        aggiorna();
      }
      if (fleetUse) {
        // 5 planes onto the carriers
        moves.addProperty("aerei_alla_portaerei", aereiAllaPortaerei());
        aggiorna();
      }
      // step P2: the continuous results threatened get the units that reach them
      if (piano.has("tenute") && piano.get("round").getAsInt() == round) {
        final Set<Unit> defended = difendiTenute(piano.getAsJsonArray("tenute"), moves);
        held.addAll(defended);
        aggiorna();
      }
      // 6 transports left where the enemy sinks them
      final JsonArray exposed = new JsonArray();
      if (landingUse) {
        for (final var e : perZona(trasporti()).entrySet()) {
          if (zonePorto().contains(e.getKey())) {
            continue;
          }
          final TrasportiProtetti.Sicurezza s = sicurezza(e.getKey(), List.of());
          if (!s.sicura()) {
            final JsonObject x = s.json();
            x.addProperty("trasporti", e.getValue().size());
            exposed.add(x);
          }
        }
      }
      moves.add("trasporti_esposti", exposed);
      final JsonObject par0 = new JsonObject();
      par0.add("mosse", moves);
      par0.addProperty("destinazione", dest == null ? "" : dest.getName());
      par0.addProperty("flotta_in", fleetAt == null ? "" : fleetAt.getName());
      final JsonArray usoJ = new JsonArray();
      uso.stream().sorted().forEach(usoJ::add);
      par0.add("uso", usoJ);
      scrivi(
          "compito_stato",
          new JsonArray(),
          "mosse",
          "MOSSE_NON_COMBATTIMENTO",
          "mosse del compito nel non combattimento",
          par0);
      if (fleetUse) {
        held.addAll(flotta());
        held.addAll(aereiInMare());
      }
      if (landingUse) {
        // step P2-bis: with the alliance level only the loaded transports (the empty ones are
        // Hard's)
        held.addAll(
            livello ? trasporti().stream().filter(Unit::isTransporting).toList() : trasporti());
        held.addAll(caricate());
      }
      // the troops gathered on a held territory next to the target wait there for the attack
      if (uso.contains(Risposta.Uso.TERRA_VICINA.name()) && piano.has("bersaglio")) {
        final Territory t = data.getMap().getTerritoryOrNull(piano.get("bersaglio").getAsString());
        if (t != null && !alleato(t)) {
          for (final Territory n : data.getMap().getNeighbors(t, Matches.territoryIsLand())) {
            if (alleato(n) && !task.fabbricheTruppe().contains(n.getName())) {
              held.addAll(
                  n.getMatches(
                      Matches.unitIsOwnedBy(player)
                          .and(Matches.unitIsLand())
                          .and(Matches.unitIsNotInfrastructure())));
            }
          }
        }
      }
      // step P2: the troops landed on the shared bridgehead stay there
      if (landingUse && piano.has("bersaglio")) {
        final Territory t = data.getMap().getTerritoryOrNull(piano.get("bersaglio").getAsString());
        if (t != null && alleato(t) && piano.has("condiviso_territorio")) {
          held.addAll(t.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsLand())));
        }
      }
      // the ready troops wait at the port only up to the free places of the owned transports;
      // the others are Hard's (step P2-bis: with the alliance level all of them are Hard's)
      if (landingUse && !livello) {
        int room = trasporti().stream().mapToInt(this::liberi).sum();
        for (final Unit u : pronte()) {
          final int c = u.getUnitAttachment().getTransportCost();
          if (c <= room) {
            held.add(u);
            room -= c;
          }
        }
      }
      // the results reached with this turn's moves (e.g. the fleet now holds its zone)
      aggiorna();
      aggiornaRisultati();
      traccia();
      salva(data.getProperties(), alliance, st);
      Impegno.diarioRighe(data, lines);
      return held;
    }

    /** Step P2: loaded transports in a zone next to {@code t} (allied) unload on it. */
    int scaricaSu(final Territory t) {
      int n = 0;
      for (final Unit tr : trasporti()) {
        final Territory z = dove(tr);
        if (z == null || !tr.isTransporting() || !data.getMap().getNeighbors(t).contains(z)) {
          continue;
        }
        final List<Unit> cargo = new ArrayList<>(tr.getTransporting());
        if (moveDel.performMove(new MoveDescription(cargo, new Route(z, t))).isEmpty()) {
          n += cargo.size();
        }
      }
      if (n > 0) {
        lines.add("  Scaricate sulla testa di ponte " + t.getName() + ": " + n);
      }
      return n;
    }

    /**
     * Step P2: the troops do not stay on board beyond {@code round_a_bordo_max} rounds: a transport
     * loaded since then unloads on allied land next to its zone (the bridgehead or the theatre
     * first); the troops are then Hard's. Row {@code compito_a_bordo}.
     */
    int limiteABordo() {
      final JsonObject since = obj(ns, "a_bordo");
      int n = 0;
      final JsonArray done = new JsonArray();
      final Set<String> theatre = new LinkedHashSet<>();
      Alleanza.direzione(this).ifPresent(d -> theatre.addAll(d.teatro()));
      for (final Unit tr : trasporti()) {
        final String id = tr.getId().toString();
        final Territory z = dove(tr);
        if (z == null
            || !tr.isTransporting()
            || !since.has(id)
            || !Risposta.troppoABordo(round, since.get(id).getAsInt(), par.roundABordoMax())) {
          continue;
        }
        final List<Territory> lands =
            data.getMap().getNeighbors(z, Matches.territoryIsLand()).stream()
                .filter(this::alleato)
                .filter(x -> !x.anyUnitsMatch(Matches.enemyUnit(player)))
                .sorted(
                    Comparator.comparing((Territory x) -> !theatre.contains(x.getName()))
                        .thenComparing(Territory::getName))
                .toList();
        if (lands.isEmpty()) {
          continue;
        }
        final List<Unit> cargo = new ArrayList<>(tr.getTransporting());
        if (moveDel.performMove(new MoveDescription(cargo, new Route(z, lands.get(0)))).isEmpty()) {
          n += cargo.size();
          final JsonObject x = new JsonObject();
          x.addProperty("trasporto", id);
          x.addProperty("a_bordo_dal", since.get(id).getAsInt());
          x.addProperty("scaricate_su", lands.get(0).getName());
          x.addProperty("unita", cargo.size());
          done.add(x);
        }
      }
      if (n > 0) {
        final JsonObject par0 = new JsonObject();
        par0.add("scarichi", done);
        par0.addProperty("round_a_bordo_max", par.roundABordoMax());
        scrivi(
            "compito_a_bordo",
            new JsonArray(),
            n + " unita'",
            "SCARICATE",
            "truppe a bordo da "
                + par.roundABordoMax()
                + " round: scaricate su terra alleata (a Hard)",
            par0);
        lines.add("  A bordo da troppo: scaricate " + n + " unita'");
      }
      return n;
    }

    /**
     * Step P2: a continuous result threatened (action DIFESA): own land units next to the territory
     * and own planes that can land there move in (strongest defenders first) until the realistic
     * attack wins <= {@code soglia_tenuta}. Returns the units moved (Hard leaves them there).
     */
    Set<Unit> difendiTenute(final JsonArray tenute, final JsonObject moves) {
      final Set<Unit> moved = new LinkedHashSet<>();
      final JsonArray rows = new JsonArray();
      for (final JsonElement e : tenute) {
        final JsonObject x = e.getAsJsonObject();
        if (!x.has("azione")
            || !"DIFESA".equals(x.get("azione").getAsString())
            || x.get("vincolo").getAsBoolean()) {
          continue;
        }
        final Territory t = data.getMap().getTerritoryOrNull(x.get("territorio").getAsString());
        if (t == null || !alleato(t)) {
          continue;
        }
        aggiorna();
        final List<Unit> def =
            new ArrayList<>(
                t.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotInfrastructure())));
        double w = contrattaccoProprio(t, def);
        final List<Unit> cand = new ArrayList<>();
        for (final Territory n : data.getMap().getNeighbors(t, Matches.territoryIsLand())) {
          if (!alleato(n) || n.equals(porto())) {
            continue;
          }
          cand.addAll(
              n.getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsLand())
                      .and(Matches.unitIsNotInfrastructure())
                      .and(u -> u.getMovementLeft().signum() > 0)));
        }
        for (final Unit u : mie(Matches.unitIsAir())) {
          final Territory from = dove(u);
          if (from == null || from.equals(t) || u.getMovementLeft().signum() <= 0) {
            continue;
          }
          final Route r = data.getMap().getRoute(from, t, y -> true).orElse(null);
          if (r != null && r.numberOfSteps() <= u.getMovementLeft().intValue()) {
            cand.add(u);
          }
        }
        cand.sort(
            Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getDefense(player))
                .thenComparing(u -> u.getId().toString()));
        int k = 0;
        for (final Unit u : cand) {
          if (w <= par.sogliaTenuta()) {
            break;
          }
          final Territory from = dove(u);
          if (from == null) {
            continue;
          }
          final Route r =
              Matches.unitIsAir().test(u)
                  ? data.getMap().getRoute(from, t, y -> true).orElse(null)
                  : new Route(from, t);
          if (r != null && moveDel.performMove(new MoveDescription(List.of(u), r)).isEmpty()) {
            moved.add(u);
            def.add(u);
            k++;
            if (k % 2 == 0) {
              w = contrattaccoProprio(t, def);
            }
          }
        }
        if (k % 2 == 1) {
          w = contrattaccoProprio(t, def);
        }
        final JsonObject row = new JsonObject();
        row.addProperty("territorio", t.getName());
        row.addProperty("unita_mosse", k);
        row.addProperty("vittoria_nemica_dopo_pct", r1(w));
        rows.add(row);
        if (k > 0) {
          lines.add(
              "  Difesa di "
                  + t.getName()
                  + ": "
                  + k
                  + " unita' mosse, attacco realistico "
                  + r1(w)
                  + "%");
        }
      }
      moves.add("difesa_tenute", rows);
      return moved;
    }

    /** Outcomes of this turn's naval battle and landing (after the combat). */
    void esitiCombattimento() {
      aggiorna();
      final Set<String> alive = new LinkedHashSet<>();
      luoghi().keySet().forEach(u -> alive.add(u.getId().toString()));
      if (ns.has("battaglia")) {
        final JsonObject b = ns.getAsJsonObject("battaglia");
        final Territory e = data.getMap().getTerritoryOrNull(b.get("zona").getAsString());
        int lost = 0;
        for (final JsonElement x : b.getAsJsonArray("unita")) {
          if (!alive.contains(x.getAsString())) {
            lost++;
          }
        }
        final boolean won =
            e != null && !e.anyUnitsMatch(Matches.enemyUnit(player).and(Matches.unitIsSea()));
        final JsonObject x = new JsonObject();
        x.addProperty("zona", b.get("zona").getAsString());
        x.addProperty("vinta", won);
        x.addProperty("unita_nostre_perse", lost);
        x.addProperty("tuv_nemico_prima", b.get("tuv_nemico").getAsInt());
        x.addProperty(
            "tuv_nemico_dopo", e == null ? 0 : tuv(e.getMatches(Matches.enemyUnit(player))));
        RegistroDecisioni.scrivi(
            data,
            RegistroDecisioni.esito(
                b.get("id").getAsString(), round, player.getName(), "compito_battaglia", x));
        lines.add(
            "  Esito battaglia in "
                + x.get("zona").getAsString()
                + ": "
                + (won ? "VINTA" : "PERSA")
                + ", perse "
                + lost);
        ns.remove("battaglia");
      }
      if (ns.has("sbarco_in_corso")) {
        final JsonObject s = ns.getAsJsonObject("sbarco_in_corso");
        final Territory t = data.getMap().getTerritoryOrNull(s.get("bersaglio").getAsString());
        final JsonObject x = new JsonObject();
        x.addProperty("bersaglio", s.get("bersaglio").getAsString());
        x.addProperty("preso", t != null && alleato(t));
        x.addProperty("proprietario", t == null ? "" : t.getOwner().getName());
        x.addProperty(
            "unita_nostre_rimaste",
            t == null ? 0 : t.getMatches(Matches.unitIsOwnedBy(player)).size());
        RegistroDecisioni.scrivi(
            data,
            RegistroDecisioni.esito(
                s.get("id").getAsString(), round, player.getName(), "sbarco", x));
        lines.add(
            "  Esito su "
                + x.get("bersaglio").getAsString()
                + ": "
                + (x.get("preso").getAsBoolean() ? "PRESO" : "non preso"));
        ns.remove("sbarco_in_corso");
      }
    }

    /** The ready troops at the troop factories onto the transports in the zones next to them. */
    int carica() {
      int n = 0;
      final List<Unit> ready = new ArrayList<>(pronte());
      ready.sort(
          Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
              .thenComparing(u -> u.getId().toString()));
      for (final Unit tr : trasporti()) {
        final Territory z = dove(tr);
        if (z == null
            || !tr.getUnloaded().isEmpty()
            || tr.getMovementLeft().signum() <= 0 && tr.getAlreadyMoved().signum() != 0) {
          continue;
        }
        int room = liberi(tr);
        for (final Unit u : new ArrayList<>(ready)) {
          final int c = u.getUnitAttachment().getTransportCost();
          final Territory from = dove(u);
          if (c > room
              || from == null
              || !data.getMap().getNeighbors(from).contains(z)
              || u.getMovementLeft().signum() <= 0) {
            continue;
          }
          final Optional<String> err =
              moveDel.performMove(
                  new MoveDescription(List.of(u), new Route(from, z), Map.of(u, tr)));
          if (err.isEmpty()) {
            room -= c;
            ready.remove(u);
            n++;
          }
        }
      }
      if (n > 0) {
        lines.add("  Caricate al porto: " + n + " unita'");
      }
      return n;
    }

    /**
     * The convoy toward {@code dest}: the main group of loaded transports (most cargo) and the
     * whole fleet stop together in the farthest zone of the transports' route that both reach and
     * that the fleet and the transports together can hold (A1). No such zone: the fleet goes toward
     * the transports (to escort them the next turn) and the transports stay. Without loaded
     * transports (or with them already at the destination) the fleet moves alone.
     */
    @Nullable
    Territory convoglio(final Territory dest, final JsonObject moves) {
      final Map<Territory, List<Unit>> loaded =
          new TreeMap<>(Comparator.comparing(Territory::getName));
      for (final Unit tr : trasporti()) {
        if (tr.isTransporting()
            && tr.getUnloaded().isEmpty()
            && tr.getMovementLeft().signum() > 0) {
          final Territory at = dove(tr);
          if (at != null && !at.equals(dest)) {
            loaded.computeIfAbsent(at, k -> new ArrayList<>()).add(tr);
          }
        }
      }
      if (loaded.isEmpty()) {
        return muoviFlotta(dest, moves);
      }
      final Territory main =
          loaded.entrySet().stream()
              .max(
                  Comparator.comparingInt(
                      (Map.Entry<Territory, List<Unit>> e) ->
                          e.getValue().stream().mapToInt(x -> x.getTransporting().size()).sum()))
              .orElseThrow()
              .getKey();
      final List<Unit> group = loaded.get(main);
      final List<Unit> fleet =
          flotta().stream()
              .filter(u -> u.getMovementLeft().signum() > 0 || main.equals(dove(u)))
              .toList();
      final Optional<Route> route = rottaMare(main, dest, group, false);
      final List<Territory> cand = new ArrayList<>();
      if (route.isPresent()) {
        final List<Territory> steps = route.get().getSteps();
        final int range = group.stream().mapToInt(u -> portata(u, main)).min().orElse(0);
        for (int i = Math.min(range, steps.size()); i >= 1; i--) {
          cand.add(steps.get(i - 1));
        }
      }
      cand.add(main);
      final JsonArray tried = new JsonArray();
      Territory stop = null;
      List<Unit> escort = List.of();
      Territory leastExposed = null;
      List<Unit> leastEscort = List.of();
      double least = Double.MAX_VALUE;
      for (final Territory c : cand) {
        if (c.anyUnitsMatch(Matches.enemyUnit(player))) {
          continue;
        }
        final List<Unit> reach = raggiungono(fleet, c, false);
        final List<Unit> arriving = new ArrayList<>(reach);
        arriving.addAll(
            raggiungono(trasporti().stream().filter(Unit::isTransporting).toList(), c, false));
        final double e = nemicaFine(c, arriving);
        final JsonObject x = new JsonObject();
        x.addProperty("zona", c.getName());
        x.addProperty("navi_che_arrivano", reach.size());
        x.addProperty("vittoria_nemica_pct", r1(e));
        tried.add(x);
        if (e <= sogliaZona(c)) {
          stop = c;
          escort = reach;
          break;
        }
        if (e < least) {
          least = e;
          leastExposed = c;
          leastEscort = reach;
        }
      }
      moves.add("convoglio_soste_provate", tried);
      if (stop == null) {
        // no zone the convoy can hold: fleet and transports together in the least exposed one
        stop = leastExposed == null ? main : leastExposed;
        escort = leastEscort;
        moves.addProperty(
            "convoglio_senza_sosta_sicura",
            "flotta e trasporti insieme nella zona meno esposta "
                + stop.getName()
                + " ("
                + r1(least)
                + "%)");
        lines.add(
            "  Convoglio: nessuna sosta sicura, insieme nella zona meno esposta " + stop.getName());
      }
      int ships = 0;
      for (final var g : perZona(escort).entrySet()) {
        if (!g.getKey().equals(stop)) {
          ships +=
              muoviGruppo(g.getKey(), stop, g.getValue().stream().filter(Compiti::nave).toList());
        }
      }
      aggiorna();
      int trs = 0;
      for (final var g : loaded.entrySet()) {
        final List<Unit> reach = raggiungono(g.getValue(), stop, false);
        if (!reach.isEmpty() && !g.getKey().equals(stop)) {
          trs += muoviGruppo(g.getKey(), stop, reach);
        }
      }
      aggiorna();
      // the fleet that could not reach the stop goes toward it
      final Territory at = stop;
      final List<Unit> rest =
          flotta().stream()
              .filter(u -> u.getMovementLeft().signum() > 0 && !at.equals(dove(u)))
              .toList();
      for (final var g : perZona(rest).entrySet()) {
        ships += muoviGruppo(g.getKey(), at, g.getValue());
      }
      moves.addProperty(
          "convoglio",
          "verso "
              + dest.getName()
              + ": sosta "
              + stop.getName()
              + ", navi "
              + ships
              + ", trasporti carichi "
              + trs);
      lines.add(
          "  Convoglio verso "
              + dest.getName()
              + ": sosta "
              + stop.getName()
              + " ("
              + ships
              + " navi, "
              + trs
              + " trasporti carichi)");
      return stop;
    }

    /**
     * The fleet (warships, carriers and their planes) toward {@code dest}: every group to the
     * farthest zone of its route where the whole fleet plus the transports joining it can hold
     * (A1); none = it stays (if its zone is not safe either: the safest zone it reaches).
     */
    @Nullable
    Territory muoviFlotta(final Territory dest, final JsonObject moves) {
      final List<Unit> fleet =
          flotta().stream().filter(u -> u.getMovementLeft().signum() > 0).toList();
      if (fleet.isEmpty()) {
        return null;
      }
      final Territory centre = centroFlotta();
      if (centre == null) {
        return null;
      }
      final List<Unit> leaders = perZona(fleet).getOrDefault(centre, List.of());
      final Optional<Route> route =
          rottaMare(centre, dest, leaders.isEmpty() ? fleet : leaders, false);
      Territory stop = null;
      final List<Unit> joining = new ArrayList<>(fleet);
      joining.addAll(trasporti().stream().filter(Unit::isTransporting).toList());
      final JsonArray tried = new JsonArray();
      if (route.isPresent()) {
        final List<Territory> steps = new ArrayList<>(route.get().getSteps());
        final int range =
            (leaders.isEmpty() ? fleet : leaders)
                .stream().mapToInt(u -> portata(u, centre)).min().orElse(0);
        for (int i = Math.min(range, steps.size()); i >= 1; i--) {
          final Territory z = steps.get(i - 1);
          final List<Unit> reach = raggiungono(joining, z, false);
          final double e = nemicaFine(z, reach);
          final JsonObject x = new JsonObject();
          x.addProperty("zona", z.getName());
          x.addProperty("vittoria_nemica_pct", r1(e));
          tried.add(x);
          if (e <= sogliaZona(z)) {
            stop = z;
            break;
          }
        }
      }
      moves.add("soste_provate", tried);
      if (stop == null) {
        if (nemicaFine(centre, List.of()) <= sogliaZona(centre)) {
          moves.addProperty(
              "flotta", "ferma in " + centre.getName() + " (nessuna sosta sicura piu' avanti)");
          return centre;
        }
        // the safest zone the fleet reaches
        double best = Double.MAX_VALUE;
        for (final Territory z : data.getMap().getNeighbors(centre, 2)) {
          if (z.isWater() && !z.anyUnitsMatch(Matches.enemyUnit(player))) {
            final double e = nemicaFine(z, raggiungono(joining, z, false));
            if (e < best) {
              best = e;
              stop = z;
            }
          }
        }
        if (stop == null) {
          return centre;
        }
      }
      int movedN = 0;
      for (final var g : perZona(fleet).entrySet()) {
        if (g.getKey().equals(stop)) {
          continue;
        }
        final List<Unit> ships = g.getValue().stream().filter(Compiti::nave).toList();
        movedN += muoviGruppo(g.getKey(), stop, ships);
      }
      moves.addProperty(
          "flotta",
          "verso " + dest.getName() + ": sosta " + stop.getName() + ", navi mosse " + movedN);
      lines.add(
          "  Flotta verso "
              + dest.getName()
              + ": sosta "
              + stop.getName()
              + " ("
              + movedN
              + " navi mosse)");
      return stop;
    }

    /**
     * Moves {@code ships} (and the planes on the carriers among them) as far as possible. The
     * nation's transports that stay in {@code from} keep the minimum guard of warships (the
     * strongest defenders first) that makes the zone holdable (A1): ships are never taken from
     * transports the enemy would sink.
     */
    int muoviGruppo(final Territory from, final Territory to, final List<Unit> ships0) {
      final List<Unit> ships = new ArrayList<>(ships0);
      final List<Unit> staying =
          from.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Compiti::trasporto)
                  .and(u -> !ships.contains(u))
                  .and(u -> u.getMovementLeft().signum() <= 0 || !u.getUnloaded().isEmpty()));
      if (!staying.isEmpty() && from.isWater()) {
        final List<Unit> leaving = ships.stream().filter(Compiti::daGuerra).toList();
        if (!leaving.isEmpty()
            && TrasportiProtetti.valuta(p, from, List.of(), leaving).vittoria()
                > sogliaZona(from)) {
          final List<Unit> guard = new ArrayList<>();
          final List<Unit> pool =
              leaving.stream()
                  .sorted(
                      Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getDefense(player))
                          .thenComparing(u -> u.getId().toString()))
                  .toList();
          for (final Unit g : pool) {
            guard.add(g);
            final List<Unit> go = new ArrayList<>(leaving);
            go.removeAll(guard);
            if (TrasportiProtetti.valuta(p, from, List.of(), go).vittoria() <= sogliaZona(from)) {
              break;
            }
          }
          ships.removeAll(guard);
          lines.add(
              "  Guardia in "
                  + from.getName()
                  + " per "
                  + staying.size()
                  + " trasporti: "
                  + guard.size()
                  + " navi");
        }
      }
      if (ships.isEmpty()) {
        return 0;
      }
      final Optional<Route> r = rottaMare(from, to, ships, false);
      if (r.isEmpty()) {
        return 0;
      }
      final List<Unit> group = new ArrayList<>(ships);
      for (final Unit s : ships) {
        s.getTransporting().stream().filter(x -> !group.contains(x)).forEach(group::add);
      }
      final List<Territory> steps = r.get().getSteps();
      final int range = ships.stream().mapToInt(u -> portata(u, from)).min().orElse(0);
      for (int i = Math.min(range, steps.size()); i >= 1; i--) {
        final Route rr = new Route(from, steps.subList(0, i).toArray(new Territory[0]));
        if (moveDel.performMove(new MoveDescription(group, rr)).isEmpty()) {
          // the planes of the carriers follow
          if (ships.stream().anyMatch(Matches.unitIsCarrier())) {
            for (final Unit pl :
                from.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsAir()))) {
              final Route pr =
                  data.getMap().getRoute(from, steps.get(i - 1), x -> true).orElse(null);
              if (pr != null) {
                moveDel.performMove(new MoveDescription(List.of(pl), pr));
              }
            }
          }
          return ships.size();
        }
      }
      return 0;
    }

    /**
     * Loaded transports toward {@code target} (the fleet's stop), only to zones that can be held
     * (A1); empty ones (not at the port) back toward the port with safe stops; a transport in a
     * zone that cannot be held moves to the safest zone it reaches.
     */
    void muoviTrasporti(@Nullable final Territory target, final JsonObject moves) {
      final boolean livello = Alleanza.ha(this);
      final List<Territory> port = zonePorto();
      int loadedMoved = 0;
      int emptyMoved = 0;
      for (final var g : perZona(trasporti()).entrySet()) {
        final Territory at = g.getKey();
        final List<Unit> loaded =
            g.getValue().stream()
                .filter(
                    u ->
                        u.isTransporting()
                            && u.getMovementLeft().signum() > 0
                            && u.getUnloaded().isEmpty())
                .toList();
        final List<Unit> empty =
            g.getValue().stream()
                .filter(
                    u ->
                        !u.isTransporting()
                            && u.getMovementLeft().signum() > 0
                            && u.getUnloaded().isEmpty())
                .toList();
        if (!loaded.isEmpty() && target != null && !at.equals(target)) {
          loadedMoved += viaggio(at, target, loaded);
        }
        // step P2-bis: with the alliance level the empty transports are Hard's
        if (!livello && !empty.isEmpty() && !port.isEmpty() && !port.contains(at)) {
          // with troops waiting at a held bridgehead next to the zone the empties stay
          emptyMoved += viaggio(at, port.get(0), empty);
        }
      }
      moves.addProperty("trasporti_carichi_mossi", loadedMoved);
      moves.addProperty("trasporti_vuoti_rientro", emptyMoved);
    }

    /** {@code transports} from {@code at} toward {@code to}, stopping only in safe zones. */
    int viaggio(final Territory at, final Territory to, final List<Unit> transports) {
      final Optional<Route> r = rottaMare(at, to, transports, false);
      if (r.isEmpty()) {
        return 0;
      }
      final List<Unit> group = new ArrayList<>(transports);
      transports.forEach(t -> group.addAll(t.getTransporting()));
      final List<Territory> steps = r.get().getSteps();
      final int range = transports.stream().mapToInt(u -> portata(u, at)).min().orElse(0);
      for (int i = Math.min(range, steps.size()); i >= 1; i--) {
        final Territory z = steps.get(i - 1);
        if (nemicaFine(z, transports) > sogliaZona(z)) {
          continue;
        }
        final Route rr = new Route(at, steps.subList(0, i).toArray(new Territory[0]));
        if (moveDel.performMove(new MoveDescription(group, rr)).isEmpty()) {
          return transports.size();
        }
      }
      // the zone it is in cannot be held: the safest zone it reaches
      if (nemicaFine(at, List.of()) > sogliaZona(at)) {
        Territory best = null;
        double min = Double.MAX_VALUE;
        for (final Territory z : data.getMap().getNeighbors(at, Math.max(1, range))) {
          if (!z.isWater() || z.anyUnitsMatch(Matches.enemyUnit(player))) {
            continue;
          }
          final double e = nemicaFine(z, transports);
          if (e < min) {
            min = e;
            best = z;
          }
        }
        if (best != null) {
          final Optional<Route> rr = rottaMare(at, best, transports, false);
          if (rr.isPresent()
              && rr.get().numberOfSteps() <= range
              && moveDel.performMove(new MoveDescription(group, rr.get())).isEmpty()) {
            return transports.size();
          }
        }
      }
      return 0;
    }

    /** Loaded transports next to a held land territory next to the target unload there. */
    int scaricaTestaDiPonte(final Territory target) {
      int n = 0;
      for (final Unit tr : trasporti()) {
        final Territory z = dove(tr);
        if (z == null || !tr.isTransporting()) {
          continue;
        }
        for (final Territory land : data.getMap().getNeighbors(z, Matches.territoryIsLand())) {
          if (alleato(land)
              && !land.equals(porto())
              && !task.fabbricheTruppe().contains(land.getName())
              && data.getMap().getNeighbors(land).contains(target)) {
            final List<Unit> cargo = new ArrayList<>(tr.getTransporting());
            if (moveDel.performMove(new MoveDescription(cargo, new Route(z, land))).isEmpty()) {
              n += cargo.size();
            }
            break;
          }
        }
      }
      if (n > 0) {
        lines.add("  Scaricate sulla testa di ponte vicino a " + target.getName() + ": " + n);
      }
      return n;
    }

    /** Carrier planes not at sea fly to a zone with an own carrier with free places. */
    int aereiAllaPortaerei() {
      int n = 0;
      final List<Unit> planes =
          mie(
              u ->
                  Matches.unitCanLandOnCarrier().test(u)
                      && dove(u) != null
                      && !dove(u).isWater()
                      && u.getMovementLeft().signum() > 0);
      for (final Unit pl : planes) {
        final Territory from = dove(pl);
        Territory best = null;
        int bestSteps = Integer.MAX_VALUE;
        for (final Unit c : mie(Matches.unitIsCarrier())) {
          final Territory z = dove(c);
          if (z == null) {
            continue;
          }
          final List<Unit> there = new ArrayList<>(z.getMatches(Matches.unitIsOwnedBy(player)));
          if (postiPortaerei(there) < costoPortaerei(List.of(pl))) {
            continue;
          }
          final Route r = data.getMap().getRoute(from, z, x -> true).orElse(null);
          if (r != null
              && r.numberOfSteps() <= pl.getMovementLeft().intValue()
              && r.numberOfSteps() < bestSteps) {
            best = z;
            bestSteps = r.numberOfSteps();
          }
        }
        if (best != null) {
          final Route r = data.getMap().getRoute(from, best, x -> true).orElseThrow();
          if (moveDel.performMove(new MoveDescription(List.of(pl), r)).isEmpty()) {
            n++;
            aggiorna();
          }
        }
      }
      return n;
    }

    // ------------------------------------------------------------------- register

    String nuovoId(final String tipo) {
      return RegistroDecisioni.nuovoId(st, round, player.getName(), tipo);
    }

    void scrivi(
        final String tipo,
        final JsonArray alt,
        final String scelta,
        final String codice,
        final String frase,
        final JsonObject par0) {
      scrivi(nuovoId(tipo), tipo, alt, scelta, codice, frase, par0);
    }

    void scrivi(
        final String id,
        final String tipo,
        final JsonArray alt,
        final String scelta,
        final String codice,
        final String frase,
        final JsonObject par0) {
      try {
        RegistroDecisioni.scrivi(
            data,
            RegistroDecisioni.decisione(
                id, round, player.getName(), tipo, alt, scelta, codice, frase, par0));
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] Compito, registro: " + e);
      }
    }
  }

  static JsonObject mappa(final Map<String, Integer> m) {
    final JsonObject o = new JsonObject();
    m.forEach(o::addProperty);
    return o;
  }

  /** Unused data reference kept for the summary of the game (GameData only). */
  static boolean partita(final GameState data) {
    return data instanceof GameData;
  }
}
