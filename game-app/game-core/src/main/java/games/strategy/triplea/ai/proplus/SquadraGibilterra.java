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
import games.strategy.triplea.Properties;
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
import java.util.Collection;
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
 * Step R6 "SQUADRA DI GIBILTERRA" (switch {@link StrategaInterruttori#SQUADRA_GIBILTERRA}, off by
 * default, on top of the commitment). Only for the USA, and it replaces R3a, step R4's block B and
 * R5 for them; the United Kingdom, Russia and France play as Hard ({@link #esclusa}).
 *
 * <ul>
 *   <li><b>A, rounds 1-4</b>: 100% of the US PUs (no capital garrison) build a squadron in the east
 *       coast's port in a fixed order (transports with their load, destroyer, carrier with its
 *       planes, cruiser, submarine, battleship; then more full transports); the ships already in
 *       the port's zones at the start count. Hard never uses a unit of the squadron before it
 *       leaves.
 *   <li><b>B</b>: the squadron leaves as one group in the first turn the USA may sail to Europe
 *       with it complete, at the latest when it must leave to be at Gibraltar in round 5; an enemy
 *       fleet in Gibraltar's sea: naval battle with the whole squadron at >= 70%, otherwise it
 *       waits in the zone next to it; Gibraltar of the Axis: the landing to take it (R5's A4). Once
 *       there the squadron stays in Gibraltar's zone as the base.
 *   <li><b>C, from round 5</b>: 80% of the PUs (after the capital's garrison of case c): support of
 *       the squadron until the realistic enemy attack wins <= 40%, the rest in the shuttle bought
 *       as complete pairs (a transport with its load) or troops; the transports travel in groups
 *       with a warship, there and back; the hub's landings of R5 (A5) with the squadron's ships and
 *       planes that can support them; only the program's units are held, until they land.
 * </ul>
 *
 * <p>Nothing of the Axis changes. Every decision goes to {@link RegistroDecisioni}.
 */
public final class SquadraGibilterra {
  private SquadraGibilterra() {}

  static final String COSTRUZIONE = "costruzione";
  static final String IN_VIAGGIO = "in_viaggio";
  static final String ATTESA = "attesa";
  static final String ARRIVATA = "arrivata";

  // ================================================================================ config

  /** Values of {@code impegno.r6} in {@code stratega-llm.json}. */
  public record Config(
      boolean accesoDiDefault,
      int roundCostruzione,
      int roundArrivo,
      double quotaCostruzione,
      double quotaPressione,
      String zonaBase,
      Map<String, Integer> obiettivo,
      List<String> ordine,
      List<String> carico,
      String tipoAereo,
      double sogliaBattaglia,
      double sogliaPresa,
      double sogliaSbarco,
      double sogliaSostegno,
      List<String> tipiSostegno,
      int maxSostegno,
      String tipoScortaSpola) {

    private static @Nullable Config cache;

    public static synchronized Config get() {
      if (cache == null) {
        try (InputStream in =
            SquadraGibilterra.class.getClassLoader().getResourceAsStream(StrategaConfig.RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + StrategaConfig.RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject()
                      .getAsJsonObject("impegno")
                      .getAsJsonObject("r6"));
        } catch (final IOException e) {
          throw new IllegalStateException("File dello stratega non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject o) {
      final Map<String, Integer> target = new LinkedHashMap<>();
      v(o, "obiettivo")
          .getAsJsonObject()
          .entrySet()
          .forEach(e -> target.put(e.getKey(), e.getValue().getAsInt()));
      return new Config(
          v(o, "squadra_gibilterra_acceso_di_default").getAsBoolean(),
          v(o, "round_costruzione").getAsInt(),
          v(o, "round_arrivo").getAsInt(),
          v(o, "quota_costruzione").getAsDouble(),
          v(o, "quota_pressione").getAsDouble(),
          v(o, "zona_base").getAsString(),
          java.util.Collections.unmodifiableMap(target),
          strings(v(o, "ordine_acquisto").getAsJsonArray()),
          strings(v(o, "carico_trasporto").getAsJsonArray()),
          v(o, "tipo_aereo").getAsString(),
          v(o, "soglia_battaglia_gibilterra").getAsDouble(),
          v(o, "soglia_presa_gibilterra").getAsDouble(),
          v(o, "soglia_sbarco").getAsDouble(),
          v(o, "soglia_sostegno").getAsDouble(),
          strings(v(o, "tipi_sostegno").getAsJsonArray()),
          v(o, "max_sostegno_per_turno").getAsInt(),
          v(o, "tipo_scorta_spola").getAsString());
    }

    private static JsonElement v(final JsonObject o, final String key) {
      return o.getAsJsonObject(key).get("valore");
    }

    private static List<String> strings(final JsonArray a) {
      final List<String> out = new ArrayList<>();
      a.forEach(e -> out.add(e.getAsString()));
      return List.copyOf(out);
    }
  }

  // ========================================================================== switches

  /** The switch is on for the USA of the commitment. */
  public static boolean attivo(final GameState data, final GamePlayer player) {
    return player != null
        && Programmi.USA.equals(player.getName())
        && SbarcoCoordinato.attivo(data, player)
        && acceso(data, player);
  }

  private static boolean acceso(final GameState data, final GamePlayer player) {
    return ProPlusSettings.allianceOf(data, player)
        .map(a -> StrategaInterruttori.SQUADRA_GIBILTERRA.acceso(data.getProperties(), a))
        .orElse(false);
  }

  /** With the switch on only the USA play the commitment: the other nations play as Hard. */
  static boolean esclusa(final GameState data, final GamePlayer player, final String alliance) {
    return !Programmi.USA.equals(player.getName())
        && StrategaInterruttori.SQUADRA_GIBILTERRA.acceso(data.getProperties(), alliance);
  }

  // ============================================================================ pure rules

  /** A: the round is one of the building rounds. */
  static boolean costruzione(final int round, final Config cfg) {
    return round <= cfg.roundCostruzione();
  }

  /**
   * B1: the squadron leaves when the USA may sail to Europe (at war) and it is complete, or anyway
   * in the round it must leave to be there in {@code roundArrivo} ({@code turni} turns of travel).
   */
  static boolean parte(
      final boolean inGuerra,
      final boolean completa,
      final int round,
      final int roundArrivo,
      final int turni) {
    return inGuerra && (completa || round >= roundArrivo - Math.max(1, turni) + 1);
  }

  /** B2, B3, C3: the action goes with win >= the threshold. */
  static boolean vince(final double vittoria, final double soglia) {
    return vittoria >= soglia;
  }

  /** The result of the building purchase: the ordered mix, PUs spent, missing after it. */
  record Costruzione(Map<String, Integer> acquisto, int spesi, Map<String, Integer> mancanti) {
    boolean completa() {
      return mancanti.isEmpty();
    }
  }

  /**
   * A2-A3: the building purchase with {@code pu} PUs and {@code units} production places. {@code
   * posseduto}: the squadron's units by type; {@code postiVuoti}: free seats on the squadron's
   * transports not covered by troops already waiting. Order: loads of the transports owned, then
   * transports each with its load, destroyer, carrier then its planes, cruiser, submarine,
   * battleship; with the target reached, more full transports. An item that does not fit the PUs
   * left is skipped (a cheaper later one may fit), so the squadron is sensible with few PUs.
   */
  static Costruzione costruisci(
      final int pu,
      final int units,
      final Map<String, Integer> posseduto,
      final int postiVuoti,
      final Map<String, Integer> costi,
      final Map<String, Integer> posti,
      final int capienza,
      final int capienzaPortaerei,
      final Config cfg) {
    final Map<String, Integer> mix = new LinkedHashMap<>();
    final int[] left = {pu, units};
    final Map<String, Integer> have = new LinkedHashMap<>(posseduto);
    final int loadCost = cfg.carico().stream().mapToInt(t -> costi.getOrDefault(t, 0)).sum();
    final int loadSeats = cfg.carico().stream().mapToInt(t -> posti.getOrDefault(t, 0)).sum();
    final int loadUnits = cfg.carico().size();
    final String transport = "transport";
    final int transportCost = costi.getOrDefault(transport, 0);
    final Predicate<Integer> canLoad = x -> loadCost > 0 && left[0] >= loadCost && left[1] >= x;
    int empty = postiVuoti;
    for (final String item : cfg.ordine()) {
      final int target = cfg.obiettivo().getOrDefault(item, 0);
      if (transport.equals(item)) {
        // the loads of the transports owned first, then the transports with their load
        while (loadSeats > 0 && empty >= loadSeats && canLoad.test(loadUnits)) {
          cfg.carico().forEach(t -> compra(mix, t, 1, costi, left));
          empty -= loadSeats;
        }
        while (have.getOrDefault(transport, 0) < target
            && transportCost > 0
            && left[0] >= transportCost + loadCost
            && left[1] >= 1 + loadUnits) {
          compra(mix, transport, 1, costi, left);
          have.merge(transport, 1, Integer::sum);
          cfg.carico().forEach(t -> compra(mix, t, 1, costi, left));
          empty += Math.max(0, capienza - loadSeats);
        }
        continue;
      }
      while (have.getOrDefault(item, 0) < target
          && costi.getOrDefault(item, 0) > 0
          && left[0] >= costi.get(item)
          && left[1] >= 1) {
        compra(mix, item, 1, costi, left);
        have.merge(item, 1, Integer::sum);
      }
      if ("carrier".equals(item)) {
        // the carrier's planes, as many as its places
        final String air = cfg.tipoAereo();
        final int places = have.getOrDefault("carrier", 0) * capienzaPortaerei;
        final int wanted = Math.min(places, cfg.obiettivo().getOrDefault(air, 0));
        while (have.getOrDefault(air, 0) < wanted
            && costi.getOrDefault(air, 0) > 0
            && left[0] >= costi.get(air)
            && left[1] >= 1) {
          compra(mix, air, 1, costi, left);
          have.merge(air, 1, Integer::sum);
        }
      }
    }
    final Map<String, Integer> missing = mancanti(have, cfg);
    if (missing.isEmpty()) {
      // A3: PUs over the target: more full transports
      while (transportCost > 0 && left[0] >= transportCost + loadCost && left[1] >= 1 + loadUnits) {
        compra(mix, transport, 1, costi, left);
        cfg.carico().forEach(t -> compra(mix, t, 1, costi, left));
      }
    }
    return new Costruzione(mix, pu - left[0], missing);
  }

  private static void compra(
      final Map<String, Integer> mix,
      final String type,
      final int n,
      final Map<String, Integer> costi,
      final int[] left) {
    mix.merge(type, n, Integer::sum);
    left[0] -= costi.getOrDefault(type, 0) * n;
    left[1] -= n;
  }

  /** What the squadron still misses of the target (type → number). */
  static Map<String, Integer> mancanti(final Map<String, Integer> have, final Config cfg) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    cfg.obiettivo()
        .forEach(
            (type, n) -> {
              final int m = n - have.getOrDefault(type, 0);
              if (m > 0) {
                out.put(type, m);
              }
            });
    return out;
  }

  /**
   * C2: the shuttle's purchase with {@code pu} PUs: a complete pair (a transport with its load)
   * when the troops waiting fill the free seats (or exceed them), troops when the free seats exceed
   * the troops; first one escort when {@code scorta}. Never an idle transport nor troops without
   * seats.
   */
  static Map<String, Integer> spola(
      final int pu,
      final int units,
      final int posti,
      final int attesa,
      final boolean scorta,
      final Map<String, Integer> costi,
      final Map<String, Integer> postiTipo,
      final int capienza,
      final Config cfg) {
    return spola(pu, units, units, posti, attesa, scorta, costi, postiTipo, capienza, cfg);
  }

  /**
   * C2 with step R6b's second factory (C3): {@code units} places in all, at most {@code maxNavi} of
   * them for ships (the port's places: the escort and the transports).
   */
  static Map<String, Integer> spola(
      final int pu,
      final int units,
      final int maxNavi,
      final int posti,
      final int attesa,
      final boolean scorta,
      final Map<String, Integer> costi,
      final Map<String, Integer> postiTipo,
      final int capienza,
      final Config cfg) {
    final int[] ships = {0};
    final Map<String, Integer> mix = new LinkedHashMap<>();
    final int[] left = {pu, units};
    if (scorta
        && costi.getOrDefault(cfg.tipoScortaSpola(), 0) > 0
        && left[0] >= costi.get(cfg.tipoScortaSpola())
        && left[1] >= 1) {
      compra(mix, cfg.tipoScortaSpola(), 1, costi, left);
      ships[0]++;
    }
    final int loadCost = cfg.carico().stream().mapToInt(t -> costi.getOrDefault(t, 0)).sum();
    final int transportCost = costi.getOrDefault("transport", 0);
    int seats = posti;
    int waiting = attesa;
    int next = 0;
    while (left[1] > 0) {
      if (seats > waiting) {
        // troops for the free seats: the load's types in turn, those that still fit
        boolean bought = false;
        for (int i = 0; i < cfg.carico().size(); i++) {
          final String type = cfg.carico().get((next + i) % cfg.carico().size());
          final int cost = costi.getOrDefault(type, 0);
          final int seat = postiTipo.getOrDefault(type, 0);
          if (cost > 0 && seat > 0 && seat <= seats - waiting && left[0] >= cost) {
            compra(mix, type, 1, costi, left);
            waiting += seat;
            next = (next + i + 1) % cfg.carico().size();
            bought = true;
            break;
          }
        }
        if (bought) {
          continue;
        }
      }
      if (transportCost > 0
          && ships[0] < maxNavi
          && left[0] >= transportCost + loadCost
          && left[1] >= 1 + cfg.carico().size()) {
        ships[0]++;
        compra(mix, "transport", 1, costi, left);
        cfg.carico().forEach(t -> compra(mix, t, 1, costi, left));
        seats += capienza;
        waiting += cfg.carico().stream().mapToInt(t -> postiTipo.getOrDefault(t, 0)).sum();
        continue;
      }
      break;
    }
    return mix;
  }

  /**
   * C1: the support of the squadron, one unit at a time: the type that lowers the enemy's win the
   * most per PU, until it is <= {@code soglia}, {@code max} units or the PUs end; planes only in
   * the free places of the carriers. {@code vittoriaCon}: the enemy's win with the extra units.
   */
  static Map<String, Integer> sostegno(
      final Function<Map<String, Integer>, Double> vittoriaCon,
      final List<String> tipi,
      final Map<String, Integer> costi,
      final Set<String> aerei,
      final int postiAerei,
      final int pu,
      final int units,
      final int max,
      final double soglia) {
    final Map<String, Integer> mix = new LinkedHashMap<>();
    int left = pu;
    int places = postiAerei;
    double win = vittoriaCon.apply(mix);
    for (int k = 0; k < Math.min(max, units) && win > soglia; k++) {
      String best = null;
      double bestScore = 0;
      double bestWin = win;
      for (final String type : tipi) {
        final int cost = costi.getOrDefault(type, 0);
        if (cost <= 0 || cost > left || (aerei.contains(type) && places <= 0)) {
          continue;
        }
        final Map<String, Integer> trial = new LinkedHashMap<>(mix);
        trial.merge(type, 1, Integer::sum);
        final double w = vittoriaCon.apply(trial);
        final double score = (win - w) / cost;
        if (score > bestScore) {
          bestScore = score;
          best = type;
          bestWin = w;
        }
      }
      if (best == null) {
        break;
      }
      mix.merge(best, 1, Integer::sum);
      left -= costi.get(best);
      if (aerei.contains(best)) {
        places--;
      }
      win = bestWin;
    }
    return mix;
  }

  // =============================================================================== state

  static JsonObject r6(final JsonObject st) {
    return SbarcoCoordinato.obj(st, "r6");
  }

  static String fase(final JsonObject r6) {
    return r6.has("fase") ? r6.get("fase").getAsString() : COSTRUZIONE;
  }

  /** The squadron's ids (ships and planes), id → type. */
  static JsonObject ids(final JsonObject r6) {
    return SbarcoCoordinato.obj(r6, "squadra");
  }

  /** The squadron's units on the map. */
  static List<Unit> squadra(final GameState data, final JsonObject r6) {
    final JsonObject ids = ids(r6);
    final List<Unit> out = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnitCollection().stream().filter(u -> ids.has(u.getId().toString())).forEach(out::add);
    }
    out.sort(Comparator.comparing(u -> u.getId().toString()));
    return out;
  }

  /** {@code u} is a unit of the squadron of its owner (USA with R6 on). */
  static boolean inSquadra(final GameState data, final GamePlayer player, final Unit u) {
    if (!attivo(data, player)) {
      return false;
    }
    final String alliance = ProPlusSettings.allianceOf(data, player).orElse(null);
    if (alliance == null) {
      return false;
    }
    return ids(r6(SbarcoCoordinato.stato(data.getProperties(), alliance)))
        .has(u.getId().toString());
  }

  /** The squadron has arrived at Gibraltar (the R5 hub's landings, the shuttle). */
  static boolean arrivata(final GameState data, final GamePlayer player) {
    if (!attivo(data, player)) {
      return false;
    }
    final String alliance = ProPlusSettings.allianceOf(data, player).orElse(null);
    return alliance != null
        && ARRIVATA.equals(fase(r6(SbarcoCoordinato.stato(data.getProperties(), alliance))));
  }

  /** The own warships, transports and planes of the port's zones join the squadron once (A2). */
  private static void adottaIniziali(
      final GamePlayer player, final JsonObject r6, final Programmi.Turno p, final JsonObject ns) {
    if (r6.has("iniziali")) {
      return;
    }
    final JsonObject ids = ids(r6);
    final JsonObject init = new JsonObject();
    for (final Territory z : p.zone(p.porto())) {
      for (final Unit u : z.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsSea()))) {
        ids.addProperty(u.getId().toString(), u.getType().getName());
        init.addProperty(
            u.getType().getName(),
            (init.has(u.getType().getName()) ? init.get(u.getType().getName()).getAsInt() : 0) + 1);
      }
    }
    r6.add("iniziali", init);
    // the program follows them too (held by R5's machinery, the transports used by the waves);
    // {@code ns}: the commitment's state of the nation, saved by the purchase that calls this
    final JsonObject prog = SbarcoCoordinato.obj(ns, "programma_ids");
    ids.keySet().forEach(id -> prog.addProperty(id, ids.get(id).getAsString()));
  }

  /** The squadron's counts by type (planes included). */
  static Map<String, Integer> conteggio(final Collection<Unit> units) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    for (final Unit u : units) {
      if (Matches.unitIsSea().test(u) || Matches.unitIsAir().test(u)) {
        out.merge(u.getType().getName(), 1, Integer::sum);
      }
    }
    return out;
  }

  /** The composition for the register: ships by type, loaded transports, land units, planes. */
  static JsonObject composizione(final Collection<Unit> units) {
    final JsonObject o = new JsonObject();
    final JsonObject ships = new JsonObject();
    final JsonObject air = new JsonObject();
    int loaded = 0;
    int empty = 0;
    int land = 0;
    for (final Unit u : units) {
      if (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)) {
        if (u.isTransporting()) {
          loaded++;
          land += u.getTransporting().size();
        } else {
          empty++;
        }
      } else if (Matches.unitIsSea().test(u)) {
        add(ships, u.getType().getName());
      } else if (Matches.unitIsAir().test(u)) {
        add(air, u.getType().getName());
      }
    }
    o.add("navi", ships);
    o.add("aerei", air);
    o.addProperty("trasporti_carichi", loaded);
    o.addProperty("trasporti_vuoti", empty);
    o.addProperty("unita_di_terra", land);
    return o;
  }

  private static void add(final JsonObject o, final String key) {
    o.addProperty(key, (o.has(key) ? o.get(key).getAsInt() : 0) + 1);
  }

  // ======================================================================== purchase

  /** A, C: the PUs of the program this turn (100% in the building rounds, else 80% after c). */
  static int pu(final GameState data, final int puTurn, final int puCasoC) {
    final Config cfg = Config.get();
    return costruzione(data.getSequence().getRound(), cfg)
        ? Impegno.puOperazione(puTurn, 0, cfg.quotaCostruzione())
        : Impegno.puOperazione(puTurn, puCasoC, cfg.quotaPressione());
  }

  /**
   * The program's purchase of the USA (called by {@link Impegno} instead of R5's): the building
   * rounds by the fixed order, then support of the squadron and the shuttle.
   */
  static Map<String, Integer> acquisti(
      final ProData proData,
      final ProOddsCalculator calc,
      final Programmi.Turno p,
      final int pu,
      final int puTurn,
      final int puCasoC,
      final JsonObject turn,
      final JsonObject ns) {
    final GameState data = proData.getData();
    final GamePlayer player = p.player;
    final Config cfg = Config.get();
    final String alliance = p.alliance;
    final JsonObject st = SbarcoCoordinato.stato(data.getProperties(), alliance);
    final JsonObject r6 = r6(st);
    final int round = data.getSequence().getRound();
    final Territory port = p.porto();
    if (port == null) {
      return Map.of();
    }
    adottaIniziali(player, r6, p, ns);
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
    final Map<String, Integer> seats = new LinkedHashMap<>();
    int capacity = 0;
    int carrierPlaces = 0;
    final Set<String> airTypes = new HashSet<>();
    for (final UnitType t : data.getUnitTypeList().getAllUnitTypes()) {
      seats.put(t.getName(), t.getUnitAttachment().getTransportCost());
      if ("transport".equals(t.getName())) {
        capacity = t.getUnitAttachment().getTransportCapacity();
      }
      if ("carrier".equals(t.getName())) {
        carrierPlaces = t.getUnitAttachment().getCarrierCapacity();
      }
      if (t.getUnitAttachment().isAir()) {
        airTypes.add(t.getName());
      }
    }
    final int places = Assedio.capacita(port);
    final List<Unit> squadron = squadra(data, r6);
    final Map<String, Integer> have = conteggio(squadron);
    final Set<String> program = SnodoAlleati.programma(data, player);
    final JsonObject prog = new JsonObject();
    turn.add("programma", prog);
    prog.addProperty("nome", "squadra di Gibilterra");
    prog.addProperty("pu", pu);
    prog.addProperty("pu_turno", puTurn);
    prog.addProperty("porto", port.getName());
    final JsonObject buyTurn = new JsonObject();
    buyTurn.addProperty("round", round);
    r6.add("acquisto_turno", buyTurn);
    final Map<String, Integer> mix;
    if (costruzione(round, cfg)) {
      // seats on the squadron's transports not covered by the program's troops waiting at the port
      int free = 0;
      for (final Unit tr : squadron) {
        if (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(tr)) {
          free += TransportTracker.getAvailableCapacity(tr);
        }
      }
      int waiting = 0;
      for (final Unit u :
          port.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsLand())
                  .and(u -> program.contains(u.getId().toString())))) {
        waiting += u.getUnitAttachment().getTransportCost();
      }
      final Costruzione c =
          costruisci(
              pu,
              places,
              have,
              Math.max(0, free - waiting),
              costs,
              seats,
              capacity,
              carrierPlaces,
              cfg);
      mix = c.acquisto();
      buyTurn.addProperty("tutto_squadra", true);
      final JsonArray alt = new JsonArray();
      final JsonObject a = new JsonObject();
      a.add("obiettivo", mappa(cfg.obiettivo()));
      a.add("posseduto", mappa(have));
      a.add("comprato", mappa(mix));
      a.add("mancanti_dopo", mappa(c.mancanti()));
      a.addProperty("pu_turno", puTurn);
      a.addProperty("pu", pu);
      a.addProperty("pu_spesi", c.spesi());
      a.addProperty("posti_vuoti", Math.max(0, free - waiting));
      a.addProperty("capacita_fabbrica", places);
      statoSquadra(data, player, a, squadron);
      alt.add(a);
      registra(
          data,
          st,
          player,
          "squadra_costruzione",
          alt,
          mix.isEmpty() ? "niente" : mappa(mix).toString(),
          c.completa() ? "OBIETTIVO_COMPLETO" : "COSTRUZIONE",
          String.format(
              Locale.ROOT,
              "round %d di costruzione: %d PU su %d (100%%, senza presidio della capitale) nell'ordine"
                  + " fisso; %s",
              round,
              c.spesi(),
              puTurn,
              c.completa() ? "squadra completa" : "mancano " + c.mancanti()),
          parametri());
      r6.addProperty("completa", c.completa());
      p.lines.add(
          "  R6 costruzione della squadra: "
              + (mix.isEmpty() ? "niente" : mix)
              + " ("
              + c.spesi()
              + " PU di "
              + pu
              + "), "
              + (c.completa() ? "completa" : "mancano " + c.mancanti()));
      addPu(r6, "pu_costruzione", c.spesi());
      addPu(r6, "pu_turno_costruzione", puTurn);
    } else {
      mix =
          pressione(
              proData,
              calc,
              p,
              r6,
              pu,
              puTurn,
              puCasoC,
              places,
              costs,
              seats,
              airTypes,
              capacity,
              carrierPlaces,
              have,
              squadron,
              st);
    }
    final JsonObject m = mappa(mix);
    prog.add("ordine", m);
    prog.addProperty("pu_ordine", Impegno.costo(data, player, mix));
    SbarcoCoordinato.salva(data.getProperties(), alliance, st);
    return mix;
  }

  /**
   * Step R6b (tables): the squadron at the start of the US turn (ships and planes by type, cargo
   * excluded) and the US warships in the base zone.
   */
  private static void statoSquadra(
      final GameState data,
      final GamePlayer player,
      final JsonObject a,
      final List<Unit> squadron) {
    a.add("squadra_ora", mappa(conteggio(squadron)));
    final Territory base = data.getMap().getTerritoryOrNull(Config.get().zonaBase());
    a.addProperty(
        "navi_usa_in_91",
        base == null
            ? 0
            : base.getMatches(Matches.unitIsOwnedBy(player).and(AlleatiR5.naveDaGuerra())).size());
  }

  private static void addPu(final JsonObject r6, final String key, final int n) {
    r6.addProperty(key, (r6.has(key) ? r6.get(key).getAsInt() : 0) + n);
  }

  /** C1-C2: support of the squadron, then the shuttle in pairs or troops. */
  private static Map<String, Integer> pressione(
      final ProData proData,
      final ProOddsCalculator calc,
      final Programmi.Turno p,
      final JsonObject r6,
      final int pu,
      final int puTurn,
      final int puCasoC,
      final int places,
      final Map<String, Integer> costs,
      final Map<String, Integer> seats,
      final Set<String> airTypes,
      final int capacity,
      final int carrierPlaces,
      final Map<String, Integer> have,
      final List<Unit> squadron,
      final JsonObject st) {
    final GameState data = proData.getData();
    final GamePlayer player = p.player;
    final Config cfg = Config.get();
    final int round = data.getSequence().getRound();
    // C1: the zone of the squadron (the base once there) and the realistic attack on it by every
    // Axis force in range
    final Territory zone = zonaSquadra(data, r6, squadron);
    if (!p.tuttiINemici) {
      p.tuttiINemici = true;
      p.enemy = null;
    }
    final List<Unit> fleet = new ArrayList<>(squadron);
    fleet.removeIf(u -> Matches.unitIsLand().test(u));
    int planes = 0;
    int carriers = 0;
    for (final Unit u : fleet) {
      if (Matches.unitIsAir().test(u)) {
        planes++;
      }
      if (Matches.unitIsCarrier().test(u)) {
        carriers += u.getUnitAttachment().getCarrierCapacity();
      }
    }
    final int freePlanePlaces = Math.max(0, carriers - planes);
    final Map<String, Integer> support;
    double before = 0;
    double after = 0;
    if (zone != null) {
      final Function<Map<String, Integer>, Double> win =
          extra -> {
            final List<Unit> ours = new ArrayList<>(fleet);
            extra.forEach(
                (type, n) ->
                    data.getUnitTypeList()
                        .getUnitType(type)
                        .ifPresent(ut -> ours.addAll(ut.createTemp(n, player))));
            return p.vittoriaNemicaMare(zone, ours);
          };
      before = win.apply(Map.of());
      support =
          sostegno(
              win,
              cfg.tipiSostegno(),
              costs,
              airTypes,
              freePlanePlaces,
              pu,
              places,
              cfg.maxSostegno(),
              cfg.sogliaSostegno());
      after = win.apply(support);
    } else {
      support = Map.of();
    }
    final int supportCost = Impegno.costo(data, player, support);
    final int supportUnits = support.values().stream().mapToInt(Integer::intValue).sum();
    // C2: the shuttle with the rest
    final Territory port = p.porto();
    final Set<String> program = SnodoAlleati.programma(data, player);
    final List<Territory> portZones = p.zone(port);
    int free = 0;
    int idleTransports = 0;
    int transportsAtPort = 0;
    int escortsAtPort = 0;
    final JsonArray escortIds = SbarcoCoordinato.arr(r6, "scorte_spola");
    final Set<String> escorts = new HashSet<>();
    escortIds.forEach(e -> escorts.add(e.getAsString()));
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater() || p.distanzaMare(z, portZones) > 2) {
        continue;
      }
      for (final Unit u : z.getMatches(Matches.unitIsOwnedBy(player))) {
        if (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)
            && program.contains(u.getId().toString())) {
          free += TransportTracker.getAvailableCapacity(u);
          idleTransports += u.isTransporting() ? 0 : 1;
          transportsAtPort += portZones.contains(z) ? 1 : 0;
        }
        if (escorts.contains(u.getId().toString()) && portZones.contains(z)) {
          escortsAtPort++;
        }
      }
    }
    // step R6b (C1): the troops waiting counted as units and as seats, at the port and at the
    // second factory (C3); they are the troops placed last turn, loaded in this turn's non-combat
    int waiting = 0;
    int waitingUnits = 0;
    final Territory second = TrasportiProtetti.secondaFabbrica(p);
    final List<Territory> lands = new ArrayList<>();
    if (port != null) {
      lands.add(port);
    }
    if (second != null) {
      lands.add(second);
    }
    for (final Territory land : lands) {
      for (final Unit u :
          land.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsLand())
                  .and(u -> program.contains(u.getId().toString())))) {
        waiting += u.getUnitAttachment().getTransportCost();
        waitingUnits++;
      }
    }
    final int extraPlaces = second == null ? 0 : Assedio.capacita(second);
    final int rest = Math.max(0, pu - supportCost);
    // C2: an escort for the transports bought now when none will be left at the port next turn
    // (the group at the port this turn sails with one of them)
    // step R6b (C2): an escort only when the arrival zone (91, held by the squadron) is not safe
    final Territory arrival =
        data.getMap().getTerritoryOrNull(TrasportiProtetti.Config.get().zonaRientro());
    final boolean needEscort = TrasportiProtetti.serveScortaSpola(p, arrival);
    final boolean escortNear = !needEscort;
    final int portPlaces = Math.max(0, places - supportUnits);
    Map<String, Integer> shuttle =
        spola(
            rest,
            portPlaces + extraPlaces,
            portPlaces,
            free,
            waiting,
            false,
            costs,
            seats,
            capacity,
            cfg);
    if (needEscort && (shuttle.containsKey("transport") || transportsAtPort > 0)) {
      shuttle =
          spola(
              rest,
              portPlaces + extraPlaces,
              portPlaces,
              free,
              waiting,
              true,
              costs,
              seats,
              capacity,
              cfg);
    }
    final Map<String, Integer> mix = new LinkedHashMap<>(support);
    shuttle.forEach((k, v) -> mix.merge(k, v, Integer::sum));
    final JsonObject buyTurn = r6.getAsJsonObject("acquisto_turno");
    buyTurn.add("sostegno", mappa(support));
    buyTurn.addProperty(
        "scorta", !escortNear && shuttle.containsKey(cfg.tipoScortaSpola()) ? 1 : 0);
    final int shuttleCost = Impegno.costo(data, player, shuttle);
    final JsonArray alt = new JsonArray();
    final JsonObject a = new JsonObject();
    a.addProperty("pu_turno", puTurn);
    a.addProperty("pu_caso_c", puCasoC);
    a.addProperty("pu", pu);
    a.addProperty("zona_squadra", zone == null ? "" : zone.getName());
    a.addProperty("vittoria_nemica_prima_pct", AlleatiR5.round1(before));
    a.addProperty("vittoria_nemica_dopo_pct", AlleatiR5.round1(after));
    a.add("sostegno", mappa(support));
    a.addProperty("pu_sostegno", supportCost);
    a.addProperty("posti_liberi", free);
    a.addProperty("trasporti_fermi_vuoti", idleTransports);
    a.addProperty("truppe_in_attesa", waitingUnits);
    a.addProperty("posti_truppe_in_attesa", waiting);
    a.addProperty("posti_seconda_fabbrica", extraPlaces);
    a.addProperty("scorta_spola_necessaria", needEscort);
    a.addProperty("scorte_al_porto", escortsAtPort);
    a.addProperty("trasporti_al_porto", transportsAtPort);
    a.addProperty("scorta_spola_vicina", escortNear);
    a.add("spola", mappa(shuttle));
    a.addProperty("pu_spola", shuttleCost);
    statoSquadra(data, player, a, squadron);
    alt.add(a);
    final String code =
        !support.isEmpty()
            ? "SOSTEGNO"
            : shuttle.containsKey("transport")
                ? "SPOLA_COPPIE"
                : shuttle.isEmpty() ? "NIENTE" : "SPOLA_TRUPPE";
    final JsonObject par = parametri();
    par.addProperty("quota_pressione", cfg.quotaPressione());
    registra(
        proData.getData(),
        st,
        player,
        "acquisto_pressione",
        alt,
        mix.isEmpty() ? "niente" : mappa(mix).toString(),
        code,
        String.format(
            Locale.ROOT,
            "round %d: %d PU (%.0f%% dopo il presidio caso c di %d): sostegno %s (%d PU, vittoria"
                + " nemica %.1f%% -> %.1f%%), spola %s (%d PU; posti %d, truppe in attesa %d unita' = %d posti)",
            round,
            pu,
            cfg.quotaPressione(),
            puCasoC,
            support,
            supportCost,
            before,
            after,
            shuttle,
            shuttleCost,
            free,
            waitingUnits,
            waiting),
        par);
    p.lines.add(
        String.format(
            Locale.ROOT,
            "  R6 pressione: %d PU -> sostegno %s (vittoria nemica sulla squadra %.1f%% -> %.1f%%),"
                + " spola %s",
            pu,
            support,
            before,
            after,
            shuttle));
    addPu(r6, "pu_pressione", Impegno.costo(data, player, mix));
    addPu(r6, "pu_turno_pressione", puTurn);
    return mix;
  }

  /** The zone of the squadron now: the base zone once arrived, else where its ships are. */
  @Nullable
  static Territory zonaSquadra(
      final GameState data, final JsonObject r6, final List<Unit> squadron) {
    final Map<Territory, Integer> where = new LinkedHashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      final long n = squadron.stream().filter(u -> t.getUnitCollection().contains(u)).count();
      if (n > 0) {
        where.put(t, (int) n);
      }
    }
    if (ARRIVATA.equals(fase(r6))) {
      final Territory base = data.getMap().getTerritoryOrNull(Config.get().zonaBase());
      if (base != null) {
        return base;
      }
    }
    return where.entrySet().stream()
        .max(
            Map.Entry.<Territory, Integer>comparingByValue()
                .thenComparing(e -> e.getKey().getName(), Comparator.reverseOrder()))
        .map(Map.Entry::getKey)
        .orElse(null);
  }

  /**
   * After the placement (called by {@link Impegno#dopoIlPiazzamento}): the program's units bought
   * now ({@code nuove}) join the squadron (every ship and plane in the building rounds; the
   * support's types afterwards) or are the shuttle's escort.
   */
  static void dopoIlPiazzamento(
      final GameState data, final GamePlayer player, final List<Unit> nuove) {
    if (!attivo(data, player) || nuove.isEmpty()) {
      return;
    }
    try {
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject st = SbarcoCoordinato.stato(data.getProperties(), alliance);
      final JsonObject r6 = r6(st);
      final int round = data.getSequence().getRound();
      final JsonObject ids = ids(r6);
      final Config cfg = Config.get();
      final List<Unit> fresh = new ArrayList<>(nuove);
      fresh.sort(Comparator.comparing(u -> u.getId().toString()));
      final JsonObject buy =
          r6.has("acquisto_turno") ? r6.getAsJsonObject("acquisto_turno") : new JsonObject();
      final boolean thisRound = buy.has("round") && buy.get("round").getAsInt() == round;
      if (costruzione(round, cfg)) {
        fresh.stream()
            .filter(Matches.unitIsSea().or(Matches.unitIsAir()))
            .forEach(u -> ids.addProperty(u.getId().toString(), u.getType().getName()));
      } else if (thisRound) {
        final Map<String, Integer> support = new LinkedHashMap<>();
        if (buy.has("sostegno")) {
          buy.getAsJsonObject("sostegno")
              .entrySet()
              .forEach(e -> support.put(e.getKey(), e.getValue().getAsInt()));
        }
        int escort = buy.has("scorta") ? buy.get("scorta").getAsInt() : 0;
        final JsonArray escorts = SbarcoCoordinato.arr(r6, "scorte_spola");
        for (final Unit u : fresh) {
          final String type = u.getType().getName();
          if (support.getOrDefault(type, 0) > 0) {
            ids.addProperty(u.getId().toString(), type);
            support.merge(type, -1, Integer::sum);
          } else if (escort > 0 && cfg.tipoScortaSpola().equals(type)) {
            escorts.add(u.getId().toString());
            escort--;
          }
        }
      }
      SbarcoCoordinato.salva(data.getProperties(), alliance, st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R6, piazzamento: " + e);
    }
  }

  // ======================================================================= the turn (R5)

  /**
   * Purchase of the USA (called by {@link AlleatiR5#inizio} instead of R5's): the checks, the
   * squadron's departure and the battle for Gibraltar's sea (B1-B2), once there Gibraltar taken
   * (B3) or the hub's landings (C3); the program's units and the squadron held out of Hard's
   * combat.
   */
  static void inizio(final SbarcoCoordinato.Turno t) {
    t.lines.add("  --- R6 squadra di Gibilterra ---");
    t.controlli();
    final JsonObject r6 = r6(t.st);
    r6.remove("piano");
    final String fase = fase(r6);
    final List<Unit> wave = new ArrayList<>();
    if (ARRIVATA.equals(fase)) {
      wave.addAll(aGibilterra(t));
    } else {
      partenza(t, r6);
    }
    // A4, C4: the squadron (planes too) and the program's units still not landed
    final Set<Unit> held = new LinkedHashSet<>(wave);
    held.addAll(trattenute(t.data, t.player, r6));
    final JsonObject tr = SbarcoCoordinato.obj(t.st, "trattenute");
    final JsonObject n = new JsonObject();
    n.addProperty("round", t.round);
    final JsonArray ids = new JsonArray();
    held.forEach(u -> ids.add(u.getId().toString()));
    n.add("ids", ids);
    tr.add(t.player.getName(), n);
    t.lines.add("  R6 trattenute (squadra e programma): " + held.size() + " unita'");
    SbarcoCoordinato.salva(t.props, t.alliance, t.st);
  }

  /** A4, C4: the squadron's units and the program's units not landed (Hard leaves them). */
  static Set<Unit> trattenute(final GameState data, final GamePlayer player, final JsonObject r6) {
    final Set<Unit> held = new LinkedHashSet<>(squadra(data, r6));
    final Set<String> program = SnodoAlleati.programma(data, player);
    for (final Territory x : data.getMap().getTerritories()) {
      x.getUnitCollection().stream()
          .filter(u -> u.isOwnedBy(player) && program.contains(u.getId().toString()))
          .forEach(held::add);
    }
    return held;
  }

  /** B1-B2: departure (or the wait next to Gibraltar's sea) decided at the purchase. */
  private static void partenza(final SbarcoCoordinato.Turno t, final JsonObject r6) {
    final Config cfg = Config.get();
    final List<Unit> squadron = squadra(t.data, r6);
    final Territory base = t.data.getMap().getTerritoryOrNull(cfg.zonaBase());
    final Territory from = zonaSquadra(t.data, r6, squadron);
    if (base == null || from == null) {
      t.lines.add("  R6: squadra senza navi o zona base sconosciuta");
      return;
    }
    final boolean atWar = inGuerra(t.data, t.player);
    final boolean complete =
        mancanti(conteggio(squadron), cfg).isEmpty()
            || (r6.has("completa") && r6.get("completa").getAsBoolean());
    final int trips = turniViaggio(t, squadron, from, base);
    final String phase = fase(r6);
    final boolean leave =
        !COSTRUZIONE.equals(phase) || parte(atWar, complete, t.round, cfg.roundArrivo(), trips);
    r6.addProperty("turni_viaggio", trips);
    if (!leave) {
      t.lines.add(
          String.format(
              Locale.ROOT,
              "  R6 squadra al porto: %s, %s, %d turni di viaggio fino a %s",
              atWar ? "in guerra" : "non ancora in guerra",
              complete ? "completa" : "non completa",
              trips,
              base.getName()));
      return;
    }
    final JsonObject plan = new JsonObject();
    plan.addProperty("round", t.round);
    plan.addProperty("da", from.getName());
    final List<Unit> enemy = AlleatiR5.navi(base, t.player);
    if (enemy.isEmpty() || from.equals(base)) {
      plan.addProperty("tipo", "viaggio");
      plan.addProperty("verso", base.getName());
      r6.add("piano", plan);
      t.lines.add("  R6 partenza della squadra verso " + base.getName() + " (mare libero)");
      return;
    }
    // B2: the battle with the whole squadron (warships and planes that reach the zone)
    final List<Unit> attackers = attaccanti(t, squadron, base);
    final double win = AlleatiR5.vittoriaBattaglia(t.proData, t.calc, base, attackers, t.player);
    final boolean go = vince(win, cfg.sogliaBattaglia());
    final Territory wait = go ? null : zonaAttesa(t, base, from, squadron);
    final JsonArray alt = new JsonArray();
    final JsonObject a = new JsonObject();
    a.addProperty("zona", base.getName());
    a.addProperty("da", from.getName());
    a.addProperty("navi_e_aerei_usa", attackers.size());
    a.addProperty("tuv_usa", Math.round(t.c.tuv(attackers)));
    a.addProperty("navi_nemiche", enemy.size());
    a.addProperty("tuv_nemico", Math.round(t.c.tuv(base.getMatches(Matches.enemyUnit(t.player)))));
    a.addProperty("vittoria_pct", AlleatiR5.round1(win));
    a.addProperty("zona_attesa", wait == null ? "" : wait.getName());
    alt.add(a);
    final String id =
        RegistroDecisioni.nuovoId(t.st, t.round, t.player.getName(), "battaglia_gibilterra");
    RegistroDecisioni.scrivi(
        t.data,
        RegistroDecisioni.decisione(
            id,
            t.round,
            t.player.getName(),
            "battaglia_gibilterra",
            alt,
            go ? "battaglia in " + base.getName() : "attesa in " + (wait == null ? "-" : wait),
            go ? "BATTAGLIA" : "ATTESA_ZONA_ACCANTO",
            String.format(
                Locale.ROOT,
                "squadra: vittoria %.1f%% %s %.0f con %d navi e aerei",
                win,
                go ? ">=" : "<",
                cfg.sogliaBattaglia(),
                attackers.size()),
            parametri()));
    plan.addProperty("tipo", go ? "battaglia" : "attesa");
    plan.addProperty("verso", go ? base.getName() : wait == null ? from.getName() : wait.getName());
    plan.addProperty("id_battaglia", id);
    plan.addProperty("vittoria_pct", AlleatiR5.round1(win));
    r6.add("piano", plan);
    t.lines.add(
        String.format(
            Locale.ROOT,
            "  R6 mare di Gibilterra occupato: vittoria %.1f%% -> %s",
            win,
            go
                ? "BATTAGLIA con tutta la squadra"
                : "attesa in " + plan.get("verso").getAsString()));
  }

  /** The USA may sail to Europe: at war with an Axis nation of the map. */
  private static boolean inGuerra(final GameState data, final GamePlayer player) {
    return data.getPlayerList().getPlayers().stream()
        .anyMatch(
            x ->
                ("Germans".equals(x.getName()) || "Italians".equals(x.getName()))
                    && data.getRelationshipTracker().isAtWar(player, x));
  }

  /** B1: turns from {@code from} to {@code to} at the squadron's slowest ship (harbour bonus). */
  private static int turniViaggio(
      final SbarcoCoordinato.Turno t,
      final List<Unit> squadron,
      final Territory from,
      final Territory to) {
    if (from.equals(to)) {
      return 0;
    }
    final int d = t.data.getMap().getDistance(from, to, Matches.territoryIsWater());
    if (d <= 0) {
      return 1;
    }
    int movement = Integer.MAX_VALUE;
    for (final Unit u : squadron) {
      if (Matches.unitIsSea().test(u)) {
        movement =
            Math.min(movement, u.getUnitAttachment().getMovement(u.getOwner()) + bonus(t, u, from));
      }
    }
    if (movement == Integer.MAX_VALUE || movement <= 0) {
      return d;
    }
    return (d + movement - 1) / movement;
  }

  /** The movement given to {@code u} by own units next to {@code zone} (the map's harbour). */
  private static int bonus(final SbarcoCoordinato.Turno t, final Unit u, final Territory zone) {
    int best = 0;
    for (final Territory land : t.data.getMap().getNeighbors(zone, Matches.territoryIsLand())) {
      if (!land.isOwnedBy(t.player)) {
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

  /** B2: the squadron's warships and planes that reach {@code z} this turn (no transports). */
  static List<Unit> attaccanti(
      final SbarcoCoordinato.Turno t, final List<Unit> squadron, final Territory z) {
    final List<Unit> out = new ArrayList<>();
    for (final Unit u : squadron) {
      if (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)
          || Matches.unitIsLand().test(u)) {
        continue;
      }
      final Territory at = t.luogoDi(u);
      if (at == null) {
        continue;
      }
      if (at.equals(z) || rotta(t, at, z, List.of(u)).isPresent()) {
        out.add(u);
      }
    }
    return out;
  }

  /** A route of {@code units} from {@code from} to {@code to} within their movement. */
  static Optional<Route> rotta(
      final SbarcoCoordinato.Turno t,
      final Territory from,
      final Territory to,
      final List<Unit> units) {
    final boolean air = units.stream().allMatch(Matches.unitIsAir());
    final Predicate<Territory> through =
        air
            ? x -> true
            : x ->
                x.isWater()
                    && ProMatches.territoryCanMoveSeaUnitsThrough(t.player, false).test(x)
                    && (x.equals(to) || !x.anyUnitsMatch(Matches.enemyUnit(t.player)));
    final Optional<Route> r = t.data.getMap().getRouteForUnits(from, to, through, units, t.player);
    final int range = units.stream().mapToInt(u -> portata(t, u, from)).min().orElse(0);
    return r.filter(x -> x.numberOfSteps() <= range);
  }

  /**
   * The movement {@code u} has this turn: before the move phases (the purchase) the map's bonus of
   * a harbour next to {@code at} is not given yet, so it is added for a unit that has not moved.
   */
  static int portata(final SbarcoCoordinato.Turno t, final Unit u, final Territory at) {
    final int left = u.getMovementLeft().intValue();
    if (u.getAlreadyMoved().signum() != 0) {
      return left;
    }
    return Math.max(left, u.getUnitAttachment().getMovement(u.getOwner()) + bonus(t, u, at));
  }

  /** B2: the zone next to Gibraltar's where the squadron waits (no enemy, nearest to it). */
  @Nullable
  private static Territory zonaAttesa(
      final SbarcoCoordinato.Turno t,
      final Territory base,
      final Territory from,
      final List<Unit> squadron) {
    Territory best = null;
    int bestD = Integer.MAX_VALUE;
    for (final Territory z :
        t.data.getMap().getNeighbors(base, Matches.territoryIsWater()).stream()
            .sorted(Comparator.comparing(Territory::getName))
            .toList()) {
      if (z.anyUnitsMatch(Matches.enemyUnit(t.player))) {
        continue;
      }
      final int d =
          z.equals(from) ? 0 : t.data.getMap().getDistance(from, z, Matches.territoryIsWater());
      if (d >= 0 && d < bestD) {
        bestD = d;
        best = z;
      }
    }
    return best;
  }

  /**
   * Once at Gibraltar: R5's checks of Gibraltar's sea (battles at >= 70 with the ships in range),
   * Gibraltar of the Axis taken (B3), otherwise the hub's landings (C3). Returns the wave's units.
   */
  private static List<Unit> aGibilterra(final SbarcoCoordinato.Turno t) {
    final Config cfg = Config.get();
    final AlleatiR5.Config r5 = AlleatiR5.Config.get();
    final Territory g = t.data.getMap().getTerritoryOrNull(r5.gibilterra());
    t.st.addProperty("teatro", SbarcoCoordinato.SUD);
    if (g != null) {
      t.st.addProperty("base", g.getName());
      t.st.addProperty("base_origine", "squadra");
    }
    t.st.remove("battaglia_r5");
    final List<Territory> occupied = new ArrayList<>();
    for (final Territory z : AlleatiR5.zoneGibilterra(t.data)) {
      if (!AlleatiR5.navi(z, t.player).isEmpty()) {
        occupied.add(z);
        AlleatiR5.battagliaPrevista(t, z);
      }
    }
    final boolean allied = g != null && SbarcoCoordinato.dellAlleanza(t.data, t.alliance, g);
    final Territory base = t.data.getMap().getTerritoryOrNull(cfg.zonaBase());
    if (g != null
        && !allied
        && t.c.nemico(g.getOwner())
        && (base == null || AlleatiR5.navi(base, t.player).isEmpty())) {
      t.supportoR6 = true;
      try {
        if (AlleatiR5.presaGibilterra(t, g)) {
          return List.of();
        }
      } finally {
        t.supportoR6 = false;
      }
      return List.of();
    }
    if (!allied) {
      t.lines.add("  R6: Gibilterra non dell'alleanza, mare occupato: niente sbarchi dallo snodo");
      t.st.remove("ondata");
      t.st.remove("ondata_africa");
      return List.of();
    }
    return AlleatiR5.snodo(t);
  }

  // ======================================================================== the combat

  /**
   * B2 in the combat move (before R5's battles, the landings and Hard): the planned battle for
   * Gibraltar's sea recomputed on the real board; >= 70% ⇒ the squadron's warships and planes that
   * reach it move in. Returns the units moved.
   */
  static Set<Unit> combattimento(final SbarcoCoordinato.Turno t, final IMoveDelegate moveDel) {
    final Set<Unit> moved = new LinkedHashSet<>();
    final JsonObject r6 = r6(t.st);
    if (!r6.has("piano")) {
      return moved;
    }
    final JsonObject plan = r6.getAsJsonObject("piano");
    if (plan.get("round").getAsInt() != t.round
        || !"battaglia".equals(plan.get("tipo").getAsString())) {
      return moved;
    }
    final Territory z = t.data.getMap().getTerritoryOrNull(plan.get("verso").getAsString());
    if (z == null || AlleatiR5.navi(z, t.player).isEmpty()) {
      plan.addProperty("tipo", "viaggio");
      return moved;
    }
    final List<Unit> squadron = squadra(t.data, r6);
    final List<Unit> attackers = attaccanti(t, squadron, z);
    final double win = AlleatiR5.vittoriaBattaglia(t.proData, t.calc, z, attackers, t.player);
    plan.addProperty("vittoria_reale_pct", AlleatiR5.round1(win));
    if (!vince(win, Config.get().sogliaBattaglia())) {
      plan.addProperty("tipo", "attesa");
      final Territory from = zonaSquadra(t.data, r6, squadron);
      final Territory wait = from == null ? null : zonaAttesa(t, z, from, squadron);
      plan.addProperty(
          "verso", wait == null ? (from == null ? z.getName() : from.getName()) : wait.getName());
      t.lines.add(
          String.format(
              Locale.ROOT,
              "  R6 battaglia rinviata sul tabellone reale: vittoria %.1f%%, attesa in %s",
              win,
              plan.get("verso").getAsString()));
      return moved;
    }
    final List<String> ids = new ArrayList<>();
    // ships first (the planes land on the carrier in the zone after the battle)
    final List<Unit> order = new ArrayList<>(attackers);
    order.sort(Comparator.comparingInt((Unit u) -> Matches.unitIsAir().test(u) ? 1 : 0));
    for (final Unit u : order) {
      final Territory from = t.luogoDi(u);
      if (from == null || from.equals(z)) {
        continue;
      }
      final Optional<Route> r = rotta(t, from, z, List.of(u));
      if (r.isEmpty()) {
        continue;
      }
      if (moveDel.performMove(new MoveDescription(List.of(u), r.get())).isEmpty()) {
        moved.add(u);
        ids.add(u.getId().toString());
      }
    }
    final JsonArray a = new JsonArray();
    ids.forEach(a::add);
    plan.add("unita_battaglia", a);
    plan.addProperty("eseguita", !ids.isEmpty());
    plan.addProperty(
        "tuv_nemico_prima", Math.round(t.c.tuv(z.getMatches(Matches.enemyUnit(t.player)))));
    t.lines.add(
        String.format(
            Locale.ROOT,
            "  R6 BATTAGLIA per il mare di Gibilterra in %s: %d navi e aerei (vittoria %.1f%%)",
            z.getName(),
            ids.size(),
            win));
    SbarcoCoordinato.salva(t.props, t.alliance, t.st);
    return moved;
  }

  // ----------------------------------------------------------- C3: the squadron's support

  /** C3: the squadron's planes that can attack {@code target} and fly back to their carrier. */
  static List<Unit> aereiSupporto(final SbarcoCoordinato.Turno t, final Territory target) {
    final List<Unit> out = new ArrayList<>();
    for (final Unit u : squadra(t.data, r6(t.st))) {
      if (!Matches.unitIsAir().test(u) || !Matches.unitHasMovementLeft().test(u)) {
        continue;
      }
      final Territory at = t.luogoDi(u);
      if (at == null || !at.isWater()) {
        continue;
      }
      final int d = t.data.getMap().getDistance(at, target, x -> true);
      if (d > 0 && 2 * d <= u.getMovementLeft().intValue()) {
        out.add(u);
      }
    }
    return out;
  }

  /**
   * C3: the squadron's ships that can bombard {@code target} from the plan's unload zone (they
   * reach it, it has no enemy), at most one per land unit when the map restricts it.
   */
  static List<Unit> naviSupporto(
      final SbarcoCoordinato.Turno t, final SbarcoCoordinato.Turno.Piano plan) {
    final List<Unit> out = new ArrayList<>();
    final Territory zone = zonaSbarco(plan);
    if (zone == null || zone.anyUnitsMatch(Matches.enemyUnit(t.player))) {
      return out;
    }
    for (final Unit u : squadra(t.data, r6(t.st))) {
      if (!Matches.unitCanBombard(t.player).test(u)) {
        continue;
      }
      final Territory at = t.luogoDi(u);
      if (at != null && (at.equals(zone) || rotta(t, at, zone, List.of(u)).isPresent())) {
        out.add(u);
      }
    }
    if (Properties.getShoreBombardPerGroundUnitRestricted(t.data.getProperties())) {
      final int max = plan.carico().size();
      out.sort(Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getBombard()));
      return new ArrayList<>(out.subList(0, Math.min(max, out.size())));
    }
    return out;
  }

  /** The unload zone used by most trips of the plan. */
  @Nullable
  static Territory zonaSbarco(final SbarcoCoordinato.Turno.Piano plan) {
    final Map<Territory, Integer> n = new LinkedHashMap<>();
    for (final SbarcoCoordinato.Turno.Viaggio v : plan.viaggi()) {
      n.merge(v.rotta().getEnd(), 1, Integer::sum);
    }
    return n.entrySet().stream()
        .max(
            Map.Entry.<Territory, Integer>comparingByValue()
                .thenComparing(e -> e.getKey().getName(), Comparator.reverseOrder()))
        .map(Map.Entry::getKey)
        .orElse(null);
  }

  /**
   * C3 after the landing of {@code plan} on {@code target}: the support ships move to the unload
   * zone and the planes attack. Returns the units moved.
   */
  static Set<Unit> eseguiSupporto(
      final SbarcoCoordinato.Turno t,
      final SbarcoCoordinato.Turno.Piano plan,
      final Territory target) {
    final Set<Unit> moved = new LinkedHashSet<>();
    final Territory zone = zonaSbarco(plan);
    for (final Unit u : naviSupporto(t, plan)) {
      final Territory at = t.luogoDi(u);
      if (at == null || zone == null || at.equals(zone)) {
        continue;
      }
      rotta(t, at, zone, List.of(u))
          .ifPresent(
              r -> {
                if (t.moveDel.performMove(new MoveDescription(List.of(u), r)).isEmpty()) {
                  moved.add(u);
                }
              });
    }
    for (final Unit u : aereiSupporto(t, target)) {
      final Territory at = t.luogoDi(u);
      if (at == null) {
        continue;
      }
      rotta(t, at, target, List.of(u))
          .ifPresent(
              r -> {
                if (t.moveDel.performMove(new MoveDescription(List.of(u), r)).isEmpty()) {
                  moved.add(u);
                }
              });
    }
    if (!moved.isEmpty()) {
      t.lines.add(
          "  R6 sostegno allo sbarco su " + target.getName() + ": " + moved.size() + " unita'");
    }
    return moved;
  }

  // =================================================================== the non-combat

  /**
   * Non-combat move of the USA (called by {@link SbarcoCoordinato.Turno#nonCombattimento} before
   * R5's outcomes): the departure without battle, the rest of the squadron after a battle won, the
   * arrival, the squadron gathered at the base and its planes back on the carrier. Returns the
   * squadron and the program's units (Hard leaves them).
   */
  static Set<Unit> nonCombattimento(final SbarcoCoordinato.Turno t, final IMoveDelegate moveDel) {
    final Config cfg = Config.get();
    final JsonObject r6 = r6(t.st);
    final Territory base = t.data.getMap().getTerritoryOrNull(cfg.zonaBase());
    final String phase = fase(r6);
    if (r6.has("piano") && r6.getAsJsonObject("piano").get("round").getAsInt() == t.round) {
      final JsonObject plan = r6.getAsJsonObject("piano");
      esitoBattaglia(t, plan);
      final Territory to = t.data.getMap().getTerritoryOrNull(plan.get("verso").getAsString());
      if (to != null) {
        final boolean first = COSTRUZIONE.equals(phase);
        final Territory reached = muovi(t, r6, to, moveDel);
        if (first && reached != null) {
          r6.addProperty("partenza_round", t.round);
          final JsonArray alt = new JsonArray();
          final JsonObject a = composizione(squadraConCarico(t.data, r6));
          a.addProperty("da", plan.get("da").getAsString());
          a.addProperty("verso", to.getName());
          a.addProperty("raggiunta", reached.getName());
          a.addProperty(
              "turni_viaggio", r6.has("turni_viaggio") ? r6.get("turni_viaggio").getAsInt() : -1);
          a.addProperty("completa", r6.has("completa") && r6.get("completa").getAsBoolean());
          alt.add(a);
          registra(
              t.data,
              t.st,
              t.player,
              "squadra_partenza",
              alt,
              reached.getName(),
              r6.has("completa") && r6.get("completa").getAsBoolean()
                  ? "PARTENZA_COMPLETA"
                  : "PARTENZA_ROUND",
              "la squadra parte in un solo gruppo verso " + to.getName(),
              parametri());
        }
        if (reached != null) {
          r6.addProperty(
              "fase", "attesa".equals(plan.get("tipo").getAsString()) ? ATTESA : IN_VIAGGIO);
        }
      }
    }
    // the arrival: warships of the squadron in the base zone, no enemy left there
    if (!ARRIVATA.equals(fase(r6)) && base != null && !COSTRUZIONE.equals(fase(r6))) {
      final List<Unit> there =
          base.getMatches(
              u -> ids(r6).has(u.getId().toString()) && AlleatiR5.naveDaGuerra().test(u));
      if (!there.isEmpty() && AlleatiR5.navi(base, t.player).isEmpty()) {
        r6.addProperty("fase", ARRIVATA);
        r6.addProperty("arrivo_round", t.round);
        final JsonArray alt = new JsonArray();
        final JsonObject a = composizione(squadraConCarico(t.data, r6, base));
        a.add("ovunque", composizione(squadraConCarico(t.data, r6)));
        a.addProperty("zona", base.getName());
        alt.add(a);
        registra(
            t.data,
            t.st,
            t.player,
            "squadra_arrivo",
            alt,
            base.getName(),
            "ARRIVO",
            "la squadra e' arrivata nella zona di Gibilterra: da qui la base",
            parametri());
        t.lines.add("  R6 ARRIVO della squadra in " + base.getName() + " al round " + t.round);
      }
    }
    if (ARRIVATA.equals(fase(r6)) && base != null) {
      raduna(t, r6, base, moveDel);
    } else if (COSTRUZIONE.equals(fase(r6))) {
      // the planes of the squadron on its carrier at the port
      final Territory zone = zonaSquadra(t.data, r6, squadra(t.data, r6));
      if (zone != null) {
        aereiAllaPortaerei(t, r6, zone, moveDel);
      }
    }
    SbarcoCoordinato.salva(t.props, t.alliance, t.st);
    final Set<Unit> held = new LinkedHashSet<>(trattenute(t.data, t.player, r6));
    // planes that could not get back to a carrier: Hard lands them
    held.removeIf(
        u ->
            Matches.unitIsAir().test(u)
                && t.luogoDi(u) != null
                && !t.luogoDi(u)
                    .anyUnitsMatch(Matches.unitIsCarrier().and(Matches.unitIsOwnedBy(t.player))));
    return held;
  }

  /** The battle's outcome (non-combat move after the battles): sea freed, US units lost. */
  private static void esitoBattaglia(final SbarcoCoordinato.Turno t, final JsonObject plan) {
    if (!plan.has("eseguita") || !plan.get("eseguita").getAsBoolean() || plan.has("esito")) {
      return;
    }
    final Territory z = t.data.getMap().getTerritoryOrNull(plan.get("verso").getAsString());
    final Set<String> onMap = new HashSet<>();
    for (final Territory x : t.data.getMap().getTerritories()) {
      x.getUnitCollection().forEach(u -> onMap.add(u.getId().toString()));
    }
    int lost = 0;
    for (final JsonElement e : plan.getAsJsonArray("unita_battaglia")) {
      lost += onMap.contains(e.getAsString()) ? 0 : 1;
    }
    final boolean freed = z != null && AlleatiR5.navi(z, t.player).isEmpty();
    final JsonObject o = new JsonObject();
    o.addProperty("zona", plan.get("verso").getAsString());
    o.addProperty("mare_liberato", freed);
    o.addProperty("unita_usa_perse", lost);
    o.addProperty(
        "tuv_nemico_perso",
        plan.has("tuv_nemico_prima")
            ? plan.get("tuv_nemico_prima").getAsLong()
                - (z == null ? 0 : Math.round(t.c.tuv(z.getMatches(Matches.enemyUnit(t.player)))))
            : -1);
    o.addProperty("vittoria_stimata_pct", plan.get("vittoria_pct").getAsDouble());
    plan.addProperty("esito", freed ? "liberato" : "non liberato");
    RegistroDecisioni.scrivi(
        t.data,
        RegistroDecisioni.esito(
            plan.get("id_battaglia").getAsString(),
            t.round,
            t.player.getName(),
            "battaglia_gibilterra",
            o));
    if (!freed) {
      // the rest of the squadron does not sail into an occupied zone
      plan.addProperty("tipo", "attesa");
      plan.addProperty("verso", plan.get("da").getAsString());
    }
    t.lines.add(
        "  R6 esito della battaglia: "
            + plan.get("esito").getAsString()
            + ", unita' USA perse "
            + lost);
  }

  /** The squadron with the cargo of its transports. */
  static List<Unit> squadraConCarico(final GameState data, final JsonObject r6) {
    final List<Unit> out = new ArrayList<>(squadra(data, r6));
    for (final Unit u : new ArrayList<>(out)) {
      u.getTransporting().stream().filter(x -> !out.contains(x)).forEach(out::add);
    }
    return out;
  }

  private static List<Unit> squadraConCarico(
      final GameState data, final JsonObject r6, final Territory z) {
    final List<Unit> out = new ArrayList<>();
    for (final Unit u : squadraConCarico(data, r6)) {
      if (z.getUnitCollection().contains(u)) {
        out.add(u);
      }
    }
    return out;
  }

  /**
   * B1: the squadron's units that have not reached {@code to} sail toward it as far as they can
   * (one group per zone: ships, transports with their cargo, planes with the carrier). Returns the
   * zone reached by most of the squadron, null if nothing moved and nothing is there.
   */
  @Nullable
  private static Territory muovi(
      final SbarcoCoordinato.Turno t,
      final JsonObject r6,
      final Territory to,
      final IMoveDelegate moveDel) {
    final Map<Territory, List<Unit>> byZone = new LinkedHashMap<>();
    for (final Unit u : squadra(t.data, r6)) {
      final Territory at = t.luogoDi(u);
      if (at != null && at.isWater() && !at.equals(to)) {
        byZone.computeIfAbsent(at, k -> new ArrayList<>()).add(u);
      }
    }
    Territory reached = null;
    for (final var e : byZone.entrySet()) {
      final Territory from = e.getKey();
      final List<Unit> ships =
          new ArrayList<>(
              e.getValue().stream()
                  .filter(Matches.unitIsSea().and(Matches.unitHasMovementLeft()))
                  .toList());
      if (ships.isEmpty()) {
        continue;
      }
      final List<Unit> group = new ArrayList<>(ships);
      for (final Unit s : ships) {
        s.getTransporting().stream().filter(x -> !group.contains(x)).forEach(group::add);
      }
      final Predicate<Territory> free =
          x ->
              x.isWater()
                  && ProMatches.territoryCanMoveSeaUnitsThrough(t.player, false).test(x)
                  && !x.anyUnitsMatch(Matches.enemyUnit(t.player));
      final Optional<Route> best =
          t.data.getMap().getRouteForUnits(from, to, free, group, t.player);
      if (best.isEmpty()) {
        continue;
      }
      final int range =
          ships.stream().mapToInt(u -> u.getMovementLeft().intValue()).min().orElse(0);
      final List<Territory> steps = best.get().getSteps();
      for (int i = Math.min(range, steps.size()); i >= 1; i--) {
        final Route r = new Route(from, steps.subList(0, i).toArray(new Territory[0]));
        final Optional<String> error = moveDel.performMove(new MoveDescription(group, r));
        if (error.isEmpty()) {
          reached = steps.get(i - 1);
          // the planes of the squadron in that zone follow their carrier
          for (final Unit p : e.getValue()) {
            if (Matches.unitIsAir().test(p)) {
              rotta(t, from, reached, List.of(p))
                  .ifPresent(rr -> moveDel.performMove(new MoveDescription(List.of(p), rr)));
            }
          }
          break;
        }
        ProLogger.warn("[Pro+] R6: rotta della squadra non riuscita " + error.get());
      }
    }
    if (reached == null
        && to.getUnitCollection().anyMatch(u -> ids(r6).has(u.getId().toString()))) {
      reached = to;
    }
    if (reached != null) {
      aereiAllaPortaerei(t, r6, reached, moveDel);
    }
    return reached;
  }

  /** B4: once there, the squadron's warships and planes gather in the base zone. */
  private static void raduna(
      final SbarcoCoordinato.Turno t,
      final JsonObject r6,
      final Territory base,
      final IMoveDelegate moveDel) {
    // step R6b (B2-B3): escorts stay with their transports; groups come back stopping only in safe
    // zones; empty transports with nothing to load at Gibraltar go back to the port
    final int back = TrasportiProtetti.raduna(t, base, moveDel).size();
    if (back > 0) {
      t.lines.add("  R6b rientro verso " + base.getName() + ": " + back + " unita' mosse");
    }
    aereiAllaPortaerei(t, r6, base, moveDel);
  }

  /** The squadron's planes not on a carrier zone fly to {@code zone} if a carrier is there. */
  private static void aereiAllaPortaerei(
      final SbarcoCoordinato.Turno t,
      final JsonObject r6,
      final Territory zone,
      final IMoveDelegate moveDel) {
    if (!zone.anyUnitsMatch(Matches.unitIsCarrier().and(Matches.unitIsOwnedBy(t.player)))) {
      return;
    }
    for (final Unit u : squadra(t.data, r6)) {
      if (!Matches.unitIsAir().test(u) || !Matches.unitHasMovementLeft().test(u)) {
        continue;
      }
      final Territory at = t.luogoDi(u);
      if (at == null || at.equals(zone)) {
        continue;
      }
      rotta(t, at, zone, List.of(u))
          .ifPresent(r -> moveDel.performMove(new MoveDescription(List.of(u), r)));
    }
  }

  // ======================================================================= the register

  static JsonObject parametri() {
    final Config cfg = Config.get();
    final JsonObject o = new JsonObject();
    o.addProperty("round_costruzione", cfg.roundCostruzione());
    o.addProperty("round_arrivo", cfg.roundArrivo());
    o.addProperty("soglia_battaglia_gibilterra", cfg.sogliaBattaglia());
    o.addProperty("soglia_presa_gibilterra", cfg.sogliaPresa());
    o.addProperty("soglia_sbarco", cfg.sogliaSbarco());
    o.addProperty("soglia_sostegno", cfg.sogliaSostegno());
    o.addProperty("condizione_di_tenuta", false);
    return o;
  }

  private static void registra(
      final GameState data,
      final JsonObject st,
      final GamePlayer player,
      final String type,
      final JsonArray alt,
      final String choice,
      final String code,
      final String why,
      final JsonObject par) {
    try {
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
              par));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R6, registro " + type + ": " + e);
    }
  }

  static JsonObject mappa(final Map<String, Integer> m) {
    final JsonObject o = new JsonObject();
    m.forEach(o::addProperty);
    return o;
  }
}
