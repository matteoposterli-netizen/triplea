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
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProStagingOrders;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.BattleRecords;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import javax.annotation.Nullable;

/**
 * Step P3 (switch {@link StrategaInterruttori#REGNO_UNITO_P3}, under {@code compiti}, off by
 * default): two levers for a nation of the task with a {@code p3} entry in {@code compiti.json},
 * both on top of Hard (nothing taken from Hard but what B1 and B4 say). No nation or territory in
 * the code: the city, the weights and the thresholds are data.
 *
 * <ul>
 *   <li><b>A, bridgehead weight</b>: in the nation's turn, a territory of the continent that an
 *       ally of the task ({@code nazioni_attive}) took this round (enemy at the start of the round,
 *       now with the ally's land units) or attacked and has not taken yet gets a higher value for
 *       Hard: an attack priority bonus ({@code peso_ponte_attacco}) if still the enemy's, a pull of
 *       the non-combat move ({@code forza_ponte_rinforzo}, the staging orders, land and the sea
 *       zones next to it) if taken. Hard decides with its own rules; no unit is held for it.
 *   <li><b>B, the city to hold</b>: B1 the units in the city and next to it that the city needs
 *       against the enemy's realistic attack ({@code soglia_tenuta}) are not Hard's in the combat
 *       move (only the minimum; an attack that removes the threat is allowed at >= {@code
 *       vittoria_attacco_minaccia} if the city holds after it); B2 reinforcement in the non-combat
 *       move by land and by sea (own transports in reach load own troops on the coasts and unload
 *       them in the city), own warships in the unloading zones lower the threat; B3 the city taken
 *       back (land and amphibious) at >= {@code soglia_attacco}; B4 troops bought at the own
 *       factory nearest in turns of travel (map data), only those B1-B3 do not cover, up to the
 *       factory's production, a transport only without one in its zone; they are carried to the
 *       city by the own transports.
 * </ul>
 *
 * <p>Rows {@code ponte_peso} and {@code cairo_tenuta} (every turn of the nation).
 */
public final class CittaPonte {
  private CittaPonte() {}

  /** The bridgeheads of the round for Hard's values: {@code {round, attacco:[], rinforzo:[]}}. */
  public static final String KEY_PONTE = "Pro+ p3 ponte: ";

  // ============================================================================ data

  /** The entry of one nation. */
  record Nazione(String citta, List<String> sostituisce) {}

  /** The {@code p3} block of {@code compiti.json}. */
  record Config(
      boolean accesoDiDefault,
      double pesoPonteAttacco,
      double forzaPonteRinforzo,
      double vittoriaAttaccoMinaccia,
      int maxTurniFabbrica,
      int raggioTrasportiNemici,
      String tipoTruppa,
      double margineRiservaPct,
      Map<String, Nazione> nazioni) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in =
            CittaPonte.class.getClassLoader().getResourceAsStream(Compito.RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + Compito.RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject());
        } catch (final IOException e) {
          throw new IllegalStateException("File dei compiti non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject root) {
      if (!root.has("p3")) {
        return new Config(false, 0, 0, 70, 0, 3, "infantry", 50, Map.of());
      }
      final JsonObject o = root.getAsJsonObject("p3");
      final JsonObject p = o.getAsJsonObject("parametri");
      final Map<String, Nazione> nations = new LinkedHashMap<>();
      for (final var e : o.getAsJsonObject("nazioni").entrySet()) {
        final JsonObject n = e.getValue().getAsJsonObject();
        final List<String> rep = new ArrayList<>();
        n.getAsJsonArray("sostituisce").forEach(x -> rep.add(x.getAsString()));
        nations.put(e.getKey(), new Nazione(n.get("citta").getAsString(), List.copyOf(rep)));
      }
      return new Config(
          v(o, "acceso_di_default").getAsBoolean(),
          v(p, "peso_ponte_attacco").getAsDouble(),
          v(p, "forza_ponte_rinforzo").getAsDouble(),
          v(p, "vittoria_attacco_minaccia").getAsDouble(),
          v(p, "max_turni_fabbrica").getAsInt(),
          v(p, "raggio_trasporti_nemici").getAsInt(),
          v(p, "tipo_truppa").getAsString(),
          p.has("margine_riserva_pct") ? v(p, "margine_riserva_pct").getAsDouble() : 100,
          Map.copyOf(nations));
    }

    private static JsonElement v(final JsonObject o, final String key) {
      final JsonElement e = o.get(key);
      return e.isJsonObject() && e.getAsJsonObject().has("valore")
          ? e.getAsJsonObject().get("valore")
          : e;
    }
  }

  // ======================================================================== switches

  /** The switch is on for {@code alliance} (with {@code compiti}). */
  public static boolean acceso(final GameState data, final String alliance) {
    return Compiti.acceso(data, alliance)
        && StrategaInterruttori.REGNO_UNITO_P3.acceso(data.getProperties(), alliance);
  }

  /** The nation plays step P3 this turn. */
  public static boolean attivo(final GameState data, @Nullable final GamePlayer player) {
    if (player == null || !Compiti.attivo(data, player)) {
      return false;
    }
    final Optional<String> a = ProPlusSettings.allianceOf(data, player);
    return a.isPresent()
        && acceso(data, a.get())
        && Config.get().nazioni().containsKey(player.getName());
  }

  /** The result {@code id} of the nation's task is replaced by step P3 (its own execution off). */
  static boolean sostituito(final Compiti.Turno t, final String id) {
    return attivo(t.data, t.player)
        && Config.get().nazioni().get(t.player.getName()).sostituisce().contains(id);
  }

  // ===================================================================== pure rules

  /**
   * The enemy's realistic attack with the naval screen: the amphibious part counts only for the
   * chance that the screen in the unloading zone falls ({@code tenutaSchermo} = chance it holds).
   */
  static double minacciaConSchermo(
      final double vittoriaTerra, final double vittoriaTutto, final double tenutaSchermo) {
    final double hold = Math.max(0, Math.min(1, tenutaSchermo));
    return vittoriaTerra + Math.max(0, vittoriaTutto - vittoriaTerra) * (1 - hold);
  }

  /**
   * B1, the minimum reserve: {@code candidates} in order (the units in the city first, strongest
   * defense first), added one at a time until {@code threat} of {@code base} + the reserve is <=
   * {@code soglia}; all of them if never.
   */
  static <U> List<U> riservaMinima(
      final List<U> base,
      final List<U> candidates,
      final ToDoubleFunction<List<U>> threat,
      final double soglia) {
    final List<U> def = new ArrayList<>(base);
    final List<U> out = new ArrayList<>();
    if (threat.applyAsDouble(def) <= soglia) {
      return out;
    }
    for (final U u : candidates) {
      def.add(u);
      out.add(u);
      if (threat.applyAsDouble(def) <= soglia) {
        return out;
      }
    }
    return out;
  }

  /** B1, the exception: an attack on the threat at >= the threshold after which the city holds. */
  static boolean attaccoMinacciaPermesso(
      final double vittoria,
      final double minacciaDopo,
      final double sogliaAttacco,
      final double sogliaTenuta) {
    return vittoria >= sogliaAttacco && minacciaDopo <= sogliaTenuta;
  }

  /** B4: the factory with the fewest turns of travel (then the name), at most {@code max}. */
  static Optional<String> fabbricaPiuVicina(final Map<String, Integer> turni, final int max) {
    return turni.entrySet().stream()
        .filter(e -> e.getValue() >= 0 && e.getValue() <= max)
        .min(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey);
  }

  /** B4: the troops bought: those missing, up to the factory's production and the PUs. */
  static int truppeDaComprare(
      final int mancanti, final int produzione, final int pu, final int costo) {
    if (mancanti <= 0 || costo <= 0) {
      return 0;
    }
    return Math.max(0, Math.min(mancanti, Math.min(produzione, pu / costo)));
  }

  /** Turns of a transport to cover {@code steps} sea zones (2 per turn), at least 1. */
  static int turniMare(final int steps, final int movimento) {
    if (steps < 0) {
      return -1;
    }
    final int m = Math.max(1, movimento);
    return Math.max(1, (steps + m - 1) / m);
  }

  // ================================================================ round snapshot

  /**
   * The owners of the continent's land territories at the first turn of a task nation in the round
   * (before its moves), for the bridgeheads "enemy at the start of the round".
   */
  static void fotografiaRound(final Compiti.Turno t) {
    if (!acceso(t.data, t.alliance)) {
      return;
    }
    final JsonObject old =
        t.st.has("p3_proprietari") ? t.st.getAsJsonObject("p3_proprietari") : null;
    if (old != null && old.get("round").getAsInt() == t.round) {
      return;
    }
    final JsonObject o = new JsonObject();
    o.addProperty("round", t.round);
    final JsonObject di = new JsonObject();
    for (final Territory x : t.data.getMap().getTerritories()) {
      if (continente(t, x) && x.getOwner() != null) {
        di.addProperty(x.getName(), x.getOwner().getName());
      }
    }
    o.add("di", di);
    t.st.add("p3_proprietari", o);
  }

  /** A land territory with at least one land neighbour (not an island). */
  static boolean continente(final Compiti.Turno t, final Territory x) {
    return !x.isWater() && !t.data.getMap().getNeighbors(x, Matches.territoryIsLand()).isEmpty();
  }

  // ===================================================================== A: bridgehead

  /** One allied bridgehead of the round. */
  record Testa(String territorio, boolean presa, List<String> alleati, int unitaAlleate) {
    JsonObject json() {
      final JsonObject o = new JsonObject();
      o.addProperty("territorio", territorio);
      o.addProperty("stato", presa ? "PRESA" : "ATTACCATA");
      final JsonArray a = new JsonArray();
      alleati.forEach(a::add);
      o.add("alleati", a);
      o.addProperty("unita_alleate", unitaAlleate);
      return o;
    }
  }

  /** The allies of the task (the other active nations of the alliance). */
  static List<GamePlayer> alleatiDelCompito(final Compiti.Turno t) {
    final List<GamePlayer> out = new ArrayList<>();
    for (final String n : Compito.dati().nazioniAttive().getOrDefault(t.alliance, List.of())) {
      final GamePlayer p = t.data.getPlayerList().getPlayerId(n);
      if (p != null && !p.equals(t.player)) {
        out.add(p);
      }
    }
    return out;
  }

  /**
   * The bridgeheads of the round: a continent territory that an ally of the task took this round
   * (enemy at the round's snapshot, or fought for this round, and now allied with its land units),
   * or attacked this round and has not taken.
   */
  static List<Testa> teste(final Compiti.Turno t) {
    final List<Testa> out = new ArrayList<>();
    final List<GamePlayer> allies = alleatiDelCompito(t);
    if (allies.isEmpty()) {
      return out;
    }
    final JsonObject snap =
        t.st.has("p3_proprietari")
                && t.st.getAsJsonObject("p3_proprietari").get("round").getAsInt() == t.round
            ? t.st.getAsJsonObject("p3_proprietari").getAsJsonObject("di")
            : new JsonObject();
    final BattleRecords records =
        t.data.getBattleRecordsList() == null
            ? null
            : t.data.getBattleRecordsList().getBattleRecordsMapCopy().get(t.round);
    for (final Territory x : t.data.getMap().getTerritories()) {
      if (!continente(t, x)) {
        continue;
      }
      final List<String> by = new ArrayList<>();
      int units = 0;
      for (final GamePlayer a : allies) {
        final boolean fought =
            records != null
                && BattleRecords.getWereThereBattlesInTerritoriesMatching(
                    BattleRecords.getAllRecords(records), a, null, "any", List.of(x));
        final int here =
            x.getMatches(
                    Matches.unitIsOwnedBy(a)
                        .and(Matches.unitIsLand())
                        .and(Matches.unitIsNotInfrastructure()))
                .size();
        // of the other alliance at the round's snapshot (neutrals are not bridgeheads); without a
        // snapshot (no task turn before) the battle alone
        final boolean wasEnemy =
            snap.size() == 0
                ? fought
                : snap.has(x.getName()) && enemyName(t, snap.get(x.getName()).getAsString());
        if (wasEnemy && (fought || here > 0)) {
          by.add(a.getName());
          units += here;
        }
      }
      if (by.isEmpty()) {
        continue;
      }
      final boolean taken = t.alleato(x);
      if (taken && units == 0) {
        continue;
      }
      if (!taken && (!t.inGuerraCon(x) || !enemyName(t, x.getOwner().getName()))) {
        continue;
      }
      out.add(new Testa(x.getName(), taken, List.copyOf(by), units));
    }
    return out;
  }

  /** {@code owner} is of another alliance than the task's (an enemy, not a neutral). */
  private static boolean enemyName(final Compiti.Turno t, final String owner) {
    final GamePlayer p = t.data.getPlayerList().getPlayerId(owner);
    if (p == null) {
      return false;
    }
    final Optional<String> a = ProPlusSettings.allianceOf(t.data, p);
    return a.isPresent() && !a.get().equals(t.alliance);
  }

  /** A, at the purchase: the bridgeheads of the round for Hard's values (property, state). */
  static JsonObject ponte(final Compiti.Turno t) {
    final List<Testa> heads = teste(t);
    final Config c = Config.get();
    final JsonObject o = new JsonObject();
    o.addProperty("round", t.round);
    final JsonArray att = new JsonArray();
    final JsonArray rin = new JsonArray();
    final JsonArray all = new JsonArray();
    final JsonObject present = new JsonObject();
    for (final Testa h : heads) {
      all.add(h.json());
      (h.presa() ? rin : att).add(h.territorio());
      final Territory x = t.data.getMap().getTerritoryOrNull(h.territorio());
      final JsonArray ids = new JsonArray();
      if (x != null) {
        x.getMatches(Matches.unitIsOwnedBy(t.player).and(Matches.unitIsLand()))
            .forEach(u -> ids.add(u.getId().toString()));
      }
      present.add(h.territorio(), ids);
    }
    o.add("attacco", att);
    o.add("rinforzo", rin);
    o.addProperty("peso_attacco", c.pesoPonteAttacco());
    o.addProperty("forza_rinforzo", c.forzaPonteRinforzo());
    final GameProperties props = t.data.getProperties();
    props.set(KEY_PONTE + t.player.getName(), (Serializable) null);
    props.set(KEY_PONTE + t.player.getName(), o.toString());
    final JsonObject st = new JsonObject();
    st.addProperty("round", t.round);
    st.add("teste", all);
    st.add("presenti", present);
    t.ns.add("p3_ponte", st);
    t.lines.add(
        "  P3 ponte: "
            + (heads.isEmpty()
                ? "nessuna testa di ponte alleata nel round"
                : heads.stream()
                    .map(h -> h.territorio() + (h.presa() ? " (presa)" : " (attaccata)"))
                    .toList()
                    .toString()));
    return st;
  }

  private static final Map<String, JsonObject> PARSED =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static Optional<JsonObject> pontoDi(final ProData proData) {
    if (proData == null || proData.getData() == null || proData.getPlayer() == null) {
      return Optional.empty();
    }
    final GameState data = proData.getData();
    if (!(data.getProperties().get(KEY_PONTE + proData.getPlayer().getName()) instanceof String s)
        || s.isBlank()) {
      return Optional.empty();
    }
    if (!attivo(data, proData.getPlayer())) {
      return Optional.empty();
    }
    try {
      if (PARSED.size() > 64) {
        PARSED.clear();
      }
      final JsonObject o =
          PARSED.computeIfAbsent(s, k -> JsonParser.parseString(k).getAsJsonObject());
      return o.get("round").getAsInt() == data.getSequence().getRound()
          ? Optional.of(o)
          : Optional.empty();
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
  }

  /** A: the attack priority bonus of {@code target} for Hard (0 without a bridgehead). */
  public static double pesoAttacco(final ProData proData, final Territory target) {
    final Optional<JsonObject> o = pontoDi(proData);
    if (o.isEmpty()) {
      return 0;
    }
    for (final JsonElement e : o.get().getAsJsonArray("attacco")) {
      if (e.getAsString().equals(target.getName())) {
        return o.get().get("peso_attacco").getAsDouble();
      }
    }
    return 0;
  }

  /**
   * A: the pull of Hard's non-combat move toward the bridgeheads taken this round (the territory
   * and the sea zones next to it). Empty without one.
   */
  public static Optional<ProStagingOrders> rinforzo(final ProData proData) {
    final Optional<JsonObject> o = pontoDi(proData);
    if (o.isEmpty() || o.get().getAsJsonArray("rinforzo").isEmpty()) {
      return Optional.empty();
    }
    final List<String> names = new ArrayList<>();
    for (final JsonElement e : o.get().getAsJsonArray("rinforzo")) {
      final Territory x = proData.getData().getMap().getTerritoryOrNull(e.getAsString());
      if (x == null) {
        continue;
      }
      if (!names.contains(x.getName())) {
        names.add(x.getName());
      }
      proData.getData().getMap().getNeighbors(x, Matches.territoryIsWater()).stream()
          .map(Territory::getName)
          .sorted()
          .filter(n -> !names.contains(n))
          .forEach(names::add);
    }
    if (names.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new ProStagingOrders(
            "P3: teste di ponte alleate del round",
            String.join(", ", names),
            List.copyOf(names),
            o.get().get("forza_rinforzo").getAsDouble(),
            false));
  }

  /** Two staging orders together: the territories of both, the stronger pull. */
  public static ProStagingOrders unisci(final ProStagingOrders a, final ProStagingOrders b) {
    final List<String> names = new ArrayList<>(a.territories());
    b.territories().stream().filter(n -> !names.contains(n)).forEach(names::add);
    final Map<String, Double> w = new LinkedHashMap<>();
    for (final String n : names) {
      final double wa = a.territories().contains(n) ? a.weight(n) : 0;
      final double wb = b.territories().contains(n) ? b.weight(n) : 0;
      w.put(n, Math.max(wa, wb));
    }
    return new ProStagingOrders(
        a.label() + " + " + b.label(),
        a.front() + (b.front().isEmpty() ? "" : " | " + b.front()),
        List.copyOf(names),
        Math.max(a.strength(), b.strength()),
        a.air() || b.air(),
        Map.copyOf(w));
  }

  // ======================================================================= B: the city

  @Nullable
  static Territory citta(final Compiti.Turno t) {
    final Nazione n = Config.get().nazioni().get(t.player.getName());
    return n == null ? null : t.data.getMap().getTerritoryOrNull(n.citta());
  }

  /** The realistic attack on the city and its parts. */
  record Minaccia(double vittoria, double terra, double tutto, double schermo, int attaccanti) {
    JsonObject json() {
      final JsonObject o = new JsonObject();
      o.addProperty("vittoria_nemica_pct", Compiti.r1(vittoria));
      o.addProperty("solo_terra_pct", Compiti.r1(terra));
      o.addProperty("con_sbarchi_pct", Compiti.r1(tutto));
      o.addProperty("schermo_navale", Compiti.r1(schermo));
      o.addProperty("attaccanti", attaccanti);
      return o;
    }
  }

  /** The state of one turn's computation (the enemy's options are computed once). */
  static final class Calcolo {
    final Compiti.Turno t;
    final Territory c;
    final Config cfg = Config.get();
    private final Set<Unit> tolti = new LinkedHashSet<>();
    private final Map<String, Double> cache = new HashMap<>();
    @Nullable private Double schermo;

    /** In the non-combat move the screen is not counted: Hard moves its warships after the task. */
    boolean senzaSchermo;

    Calcolo(final Compiti.Turno t, final Territory c) {
      this.t = t;
      this.c = c;
    }

    /** Enemy units considered destroyed (the attack on the threat). */
    void togli(final List<Unit> units) {
      tolti.addAll(units);
      cache.clear();
    }

    List<Unit> difensori() {
      return new ArrayList<>(
          c.getMatches(Matches.isUnitAllied(t.player).and(Matches.unitIsNotInfrastructure())));
    }

    Minaccia minaccia(final List<Unit> def) {
      final Alleanza.Contrattacco all = Alleanza.contrattacco(t, c, def, false);
      final ProTerritoryManager m = t.nemici.get(c.getName() + "|false");
      final ProTerritory max = m == null ? null : m.getEnemyAttackOptions().getMax(c);
      final Set<Unit> amphib =
          max == null ? Set.of() : new LinkedHashSet<>(max.getMaxAmphibUnits());
      final List<Unit> counter = new ArrayList<>(all.attaccanti());
      counter.removeAll(tolti);
      if (counter.isEmpty()) {
        return new Minaccia(0, 0, 0, 1, 0);
      }
      final List<Unit> land = counter.stream().filter(u -> !amphib.contains(u)).toList();
      final double wAll = calc(counter, def);
      if (land.size() == counter.size()) {
        return new Minaccia(wAll, wAll, wAll, 1, counter.size());
      }
      final double wLand = calc(land, def);
      final double hold = senzaSchermo ? 0 : schermo();
      return new Minaccia(minacciaConSchermo(wLand, wAll, hold), wLand, wAll, hold, counter.size());
    }

    double minacciaDi(final List<Unit> def) {
      return minaccia(def).vittoria();
    }

    private double calc(final List<Unit> att, final List<Unit> def) {
      if (att.isEmpty()) {
        return 0;
      }
      if (def.isEmpty()) {
        return 100;
      }
      final String key = chiave(att) + "|" + chiave(def);
      final Double v = cache.get(key);
      if (v != null) {
        return v;
      }
      final double w =
          t.calc.calculateBattleResults(t.proData, c, att, def, List.of()).getWinPercentage();
      cache.put(key, w);
      return w;
    }

    /**
     * The chance that the naval screen holds: the zones next to the city that an enemy transport
     * reaches ({@code raggio_trasporti_nemici}); the weakest of them (no allied warship = 0); no
     * such zone = 1 (no landing possible).
     */
    double schermo() {
      if (schermo != null) {
        return schermo;
      }
      final List<Territory> enemyTransports = new ArrayList<>();
      for (final Territory z : t.data.getMap().getTerritories()) {
        if (z.isWater() && z.anyUnitsMatch(Matches.enemyUnit(t.player).and(Compiti::trasporto))) {
          enemyTransports.add(z);
        }
      }
      double hold = 1;
      for (final Territory z :
          t.data.getMap().getNeighbors(c, Matches.territoryIsWater()).stream()
              .sorted(Comparator.comparing(Territory::getName))
              .toList()) {
        boolean usable = false;
        for (final Territory e : enemyTransports) {
          final int d = t.data.getMap().getDistance(e, z, Matches.territoryIsWater());
          if (d >= 0 && d <= cfg.raggioTrasportiNemici()) {
            usable = true;
            break;
          }
        }
        if (!usable) {
          continue;
        }
        final boolean warships =
            z.anyUnitsMatch(Matches.isUnitAllied(t.player).and(Compiti::daGuerra));
        final double h = warships ? 1 - t.nemicaFine(z, List.of()) / 100.0 : 0;
        hold = Math.min(hold, h);
      }
      schermo = hold;
      return hold;
    }
  }

  private static String chiave(final List<Unit> units) {
    final Map<String, Integer> m = new java.util.TreeMap<>();
    for (final Unit u : units) {
      m.merge(u.getType().getName() + "@" + u.getOwner().getName(), 1, Integer::sum);
    }
    return m.toString();
  }

  private static int difesa(final Unit u) {
    return u.getUnitAttachment().getDefense(u.getOwner());
  }

  private static final Comparator<Unit> PIU_FORTE =
      Comparator.comparingInt((Unit u) -> -difesa(u)).thenComparing(u -> u.getId().toString());

  /** The own land units of the allied land territories next to {@code c} that can still move. */
  static List<Unit> vicineTerra(final Compiti.Turno t, final Territory c, final boolean anche0) {
    final List<Unit> out = new ArrayList<>();
    final Territory capital = t.porto();
    for (final Territory n :
        t.data.getMap().getNeighbors(c, Matches.territoryIsLand()).stream()
            .sorted(Comparator.comparing(Territory::getName))
            .toList()) {
      if (!t.alleato(n) || n.equals(capital)) {
        continue;
      }
      out.addAll(
          n.getMatches(
              Matches.unitIsOwnedBy(t.player)
                  .and(Matches.unitIsLand())
                  .and(Matches.unitIsNotInfrastructure())
                  .and(u -> anche0 || u.getMovementLeft().signum() > 0)));
    }
    out.sort(PIU_FORTE);
    return out;
  }

  /** The own planes that can land in {@code c} this turn. */
  static List<Unit> aereiVerso(final Compiti.Turno t, final Territory c) {
    final List<Unit> out = new ArrayList<>();
    for (final Unit u : t.mie(Matches.unitIsAir())) {
      final Territory from = t.dove(u);
      if (from == null
          || from.equals(c)
          || u.getMovementLeft().signum() <= 0
          || !u.isOwnedBy(t.player)) {
        continue;
      }
      final Route r = t.data.getMap().getRoute(from, c, y -> true).orElse(null);
      if (r != null && r.numberOfSteps() <= u.getMovementLeft().intValue()) {
        out.add(u);
      }
    }
    out.sort(PIU_FORTE);
    return out;
  }

  // ------------------------------------------------------------------------ the sea plan

  /** One transport's trip: from its zone to the loading zone, load, to the arrival, unload. */
  record Carico(
      Unit trasporto,
      Territory da,
      Territory zonaCarico,
      @Nullable Territory terra,
      List<Unit> truppe,
      Territory arrivo,
      Territory sbarco) {
    JsonObject json() {
      final JsonObject o = new JsonObject();
      o.addProperty("trasporto", trasporto.getId().toString());
      o.addProperty("da", da.getName());
      o.addProperty("zona_carico", zonaCarico.getName());
      o.addProperty("carico_da", terra == null ? "a bordo" : terra.getName());
      o.add("truppe", TrasportiProtetti.perTipo(truppe));
      o.addProperty("arrivo", arrivo.getName());
      o.addProperty("sbarco", sbarco.getName());
      return o;
    }
  }

  /**
   * B2/B3: the trips of the own transports in reach that bring own troops to {@code dest} this
   * turn: a loaded transport goes to a zone next to it; an empty one goes to a zone next to an
   * allied coast with own troops that can move (not the capital, not a territory next to {@code
   * dest} by land: those go by land), loads the strongest defenders that fit and goes on to a zone
   * next to {@code dest}; zones with enemy units are avoided (also for the unloading). Safe arrival
   * zones first.
   */
  static List<Carico> pianoMare(
      final Compiti.Turno t, final Territory dest, final Set<Unit> esclusi, final boolean combat) {
    final List<Carico> out = new ArrayList<>();
    final List<Territory> arrivals =
        t.data.getMap().getNeighbors(dest, Matches.territoryIsWater()).stream()
            .filter(z -> !z.anyUnitsMatch(Matches.enemyUnit(t.player)))
            .sorted(Comparator.comparing(Territory::getName))
            .toList();
    if (arrivals.isEmpty()) {
      return out;
    }
    final Set<Unit> used = new LinkedHashSet<>(esclusi);
    final Territory capital = t.porto();
    final Set<Territory> byLand =
        new LinkedHashSet<>(t.data.getMap().getNeighbors(dest, Matches.territoryIsLand()));
    for (final Unit tr : t.trasporti()) {
      if (!tr.isOwnedBy(t.player) || used.contains(tr) || !tr.getUnloaded().isEmpty()) {
        continue;
      }
      final Territory at = t.dove(tr);
      if (at == null || !at.isWater()) {
        continue;
      }
      final int range =
          tr.getAlreadyMoved().signum() != 0 ? tr.getMovementLeft().intValue() : t.portata(tr, at);
      if (range < 0) {
        continue;
      }
      final List<Unit> cargoNow =
          tr.getTransporting().stream().filter(u -> u.isOwnedBy(t.player)).toList();
      Carico best = null;
      int bestScore = 0;
      for (final Territory arr : arrivals) {
        final int dArr = passi(t, at, arr, tr, combat);
        if (!cargoNow.isEmpty()) {
          if (dArr >= 0 && dArr <= range) {
            final int s = cargoNow.stream().mapToInt(CittaPonte::difesa).sum() * 10 + 5 - dArr;
            if (s > bestScore) {
              bestScore = s;
              best = new Carico(tr, at, at, null, cargoNow, arr, dest);
            }
          }
          continue;
        }
        // empty: load somewhere on the way
        for (final Territory zl : t.data.getMap().getTerritories()) {
          if (!zl.isWater() || zl.anyUnitsMatch(Matches.enemyUnit(t.player))) {
            continue;
          }
          final int quick = t.data.getMap().getDistance(at, zl, Matches.territoryIsWater());
          if (quick < 0 || quick > range) {
            continue;
          }
          final int d1 = passi(t, at, zl, tr, combat);
          if (d1 < 0 || d1 > range) {
            continue;
          }
          final int d2 = passi(t, zl, arr, tr, combat);
          if (d2 < 0 || d1 + d2 > range) {
            continue;
          }
          for (final Territory l :
              t.data.getMap().getNeighbors(zl, Matches.territoryIsLand()).stream()
                  .sorted(Comparator.comparing(Territory::getName))
                  .toList()) {
            if (l.equals(dest) || l.equals(capital) || byLand.contains(l) || !t.alleato(l)) {
              continue;
            }
            final List<Unit> troops =
                l
                    .getMatches(
                        Matches.unitIsOwnedBy(t.player)
                            .and(Matches.unitIsLand())
                            .and(Matches.unitIsNotInfrastructure())
                            .and(u -> u.getUnitAttachment().getTransportCost() > 0)
                            .and(u -> u.getMovementLeft().signum() > 0)
                            .and(u -> !used.contains(u)))
                    .stream()
                    .sorted(PIU_FORTE)
                    .toList();
            int room = t.liberi(tr);
            final List<Unit> pick = new ArrayList<>();
            for (final Unit u : troops) {
              final int cost = u.getUnitAttachment().getTransportCost();
              if (cost <= room) {
                pick.add(u);
                room -= cost;
              }
            }
            if (pick.isEmpty()) {
              continue;
            }
            final int s = pick.stream().mapToInt(CittaPonte::difesa).sum() * 10 + 5 - (d1 + d2);
            if (s > bestScore) {
              bestScore = s;
              best = new Carico(tr, at, zl, l, List.copyOf(pick), arr, dest);
            }
          }
        }
      }
      if (best != null) {
        out.add(best);
        used.add(tr);
        used.addAll(best.truppe());
      }
    }
    return out;
  }

  private static int passi(
      final Compiti.Turno t,
      final Territory from,
      final Territory to,
      final Unit tr,
      final boolean combat) {
    if (from.equals(to)) {
      return 0;
    }
    return t.rottaMare(from, to, List.of(tr), combat).map(Route::numberOfSteps).orElse(-1);
  }

  /** Executes one trip; the units landed (empty if it failed before the unloading). */
  static List<Unit> esegui(
      final Compiti.Turno t, final IMoveDelegate del, final Carico k, final boolean combat) {
    final Territory at = t.dove(k.trasporto());
    if (at == null) {
      return List.of();
    }
    if (!at.equals(k.zonaCarico())) {
      final Optional<Route> r = t.rottaMare(at, k.zonaCarico(), List.of(k.trasporto()), combat);
      if (r.isEmpty()) {
        return List.of();
      }
      final List<Unit> group = new ArrayList<>(List.of(k.trasporto()));
      group.addAll(k.trasporto().getTransporting());
      if (del.performMove(new MoveDescription(group, r.get())).isPresent()) {
        return List.of();
      }
    }
    final List<Unit> cargo = new ArrayList<>();
    if (k.terra() == null) {
      cargo.addAll(k.trasporto().getTransporting());
    } else {
      for (final Unit u : k.truppe()) {
        if (del.performMove(
                new MoveDescription(
                    List.of(u), new Route(k.terra(), k.zonaCarico()), Map.of(u, k.trasporto())))
            .isEmpty()) {
          cargo.add(u);
        }
      }
    }
    if (cargo.isEmpty()) {
      return List.of();
    }
    if (!k.zonaCarico().equals(k.arrivo())) {
      final Optional<Route> r =
          t.rottaMare(k.zonaCarico(), k.arrivo(), List.of(k.trasporto()), combat);
      final List<Unit> group = new ArrayList<>(List.of(k.trasporto()));
      group.addAll(cargo);
      if (r.isEmpty() || del.performMove(new MoveDescription(group, r.get())).isPresent()) {
        return List.of();
      }
    }
    if (del.performMove(new MoveDescription(cargo, new Route(k.arrivo(), k.sbarco())))
        .isPresent()) {
      return List.of();
    }
    return cargo;
  }

  // -------------------------------------------------------------- factories (B4)

  /** Turns of travel of troops from factory {@code f} to {@code c} (by land or by sea), -1 none. */
  static int turniDa(final Compiti.Turno t, final Territory f, final Territory c) {
    final int[] lm = turniTerraMare(t, f, c);
    if (lm[0] < 0) {
      return lm[1];
    }
    return lm[1] < 0 ? lm[0] : Math.min(lm[0], lm[1]);
  }

  /** {turns by land, turns by sea} from factory {@code f} to {@code c} (-1 = no way). */
  static int[] turniTerraMare(final Compiti.Turno t, final Territory f, final Territory c) {
    final int land =
        t.data.getMap().getDistance(f, c, x -> !x.isWater() && (t.alleato(x) || x.equals(c)));
    int sea = -1;
    final int move = transportMove(t);
    for (final Territory a : t.data.getMap().getNeighbors(f, Matches.territoryIsWater())) {
      for (final Territory b : t.data.getMap().getNeighbors(c, Matches.territoryIsWater())) {
        final int d =
            a.equals(b) ? 0 : t.data.getMap().getDistance(a, b, Matches.territoryIsWater());
        if (d < 0) {
          continue;
        }
        final int turns = turniMare(d, move);
        if (sea < 0 || turns < sea) {
          sea = turns;
        }
      }
    }
    return new int[] {land, sea};
  }

  /** The troops go by sea: a sea way faster than the land way (or no land way). */
  static boolean viaMare(final Compiti.Turno t, final Territory f, final Territory c) {
    final int[] lm = turniTerraMare(t, f, c);
    return lm[1] >= 0 && (lm[0] < 0 || lm[1] < lm[0]);
  }

  private static int transportMove(final Compiti.Turno t) {
    return t.data
        .getUnitTypeList()
        .getUnitType(t.par.tipoTrasporto())
        .map(ut -> ut.getUnitAttachment().getMovement(t.player))
        .orElse(2);
  }

  /** The own factories with their turns of travel to {@code c}. */
  static Map<String, Integer> fabbriche(final Compiti.Turno t, final Territory c) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    for (final Territory f : t.data.getMap().getTerritories()) {
      if (f.isWater()
          || !f.isOwnedBy(t.player)
          || !f.anyUnitsMatch(Matches.unitIsOwnedBy(t.player).and(Matches.unitCanProduceUnits()))
          || t.capienza(f) <= 0) {
        continue;
      }
      out.put(f.getName(), turniDa(t, f, c));
    }
    return out;
  }

  // ====================================================================== purchase (B1, B4)

  /**
   * At the purchase: state of the city, the minimum reserve (B1), the attack on the threat, the sea
   * reinforcement possible (B2/B3, projected), the purchase (B4). State {@code ns.p3}.
   */
  static JsonObject acquisto(final Compiti.Turno t, final Compiti.Turno.Cassa cassa) {
    final JsonObject x = new JsonObject();
    final Territory c = citta(t);
    x.addProperty("round", t.round);
    if (c == null) {
      x.addProperty("stato", "NESSUNA_CITTA");
      t.ns.add("p3", x);
      return x;
    }
    x.addProperty("citta", c.getName());
    // the outcome of the round before (the city at the start of this turn)
    if (t.ns.has("p3") && t.ns.getAsJsonObject("p3").has("stato")) {
      final JsonObject old = t.ns.getAsJsonObject("p3");
      x.addProperty("stato_turno_prima", old.get("stato").getAsString());
      x.addProperty("round_prima", old.get("round").getAsInt());
    }
    x.addProperty("alleata_ora", t.alleato(c));
    final Calcolo k = new Calcolo(t, c);
    final JsonArray reserve = new JsonArray();
    final JsonArray plannedSea = new JsonArray();
    final Set<Unit> planned = new LinkedHashSet<>();
    int missing = 0;
    if (!t.inGuerraConQualcuno()) {
      x.addProperty("stato", "TENUTO");
      x.addProperty("nota", "non in guerra");
    } else if (t.alleato(c)) {
      final List<Unit> def = k.difensori();
      final List<Unit> own =
          def.stream().filter(u -> u.isOwnedBy(t.player)).sorted(PIU_FORTE).toList();
      final List<Unit> base = def.stream().filter(u -> !u.isOwnedBy(t.player)).toList();
      final Minaccia m0 = k.minaccia(def);
      x.add("attacco_realistico_prima", m0.json());
      final double soglia = t.par.sogliaTenuta();
      x.addProperty("soglia_pct", soglia);
      x.addProperty("stato", m0.vittoria() <= soglia ? "TENUTO" : "MINACCIATO");
      // B1: the minimum of the own units in the city and next to it
      final List<Unit> cand = new ArrayList<>(own);
      cand.addAll(vicineTerra(t, c, false));
      // the reserve is computed before Hard's combat and non-combat moves, which change the
      // enemy's ways (a neighbour emptied, a fleet gone): the minimum that keeps the threat
      // within half the threshold (margine_riserva_pct of the data)
      final double target = soglia * k.cfg.margineRiservaPct() / 100.0;
      x.addProperty("obiettivo_riserva_pct", target);
      final List<Unit> res = riservaMinima(base, cand, k::minacciaDi, target);
      final List<Unit> defRes = new ArrayList<>(base);
      defRes.addAll(res);
      double after = k.minacciaDi(defRes);
      res.forEach(u -> reserve.add(u.getId().toString()));
      planned.addAll(res);
      x.addProperty("riservate", res.size());
      x.add("riservate_per_tipo", TrasportiProtetti.perTipo(res));
      // the attack on the threat (planned; executed in the combat move on the real board)
      final Optional<JsonObject> hit =
          m0.vittoria() > soglia || !res.isEmpty()
              ? attaccoMinaccia(t, k, c, base, own)
              : Optional.empty();
      hit.ifPresent(h -> x.add("attacco_minaccia", h));
      // B2 by sea (projected)
      if (after > soglia) {
        final List<Unit> more = new ArrayList<>(defRes);
        for (final Carico ca : pianoMare(t, c, planned, false)) {
          if (after <= soglia) {
            break;
          }
          plannedSea.add(ca.json());
          planned.add(ca.trasporto());
          planned.addAll(ca.truppe());
          more.addAll(ca.truppe());
          after = k.minacciaDi(more);
        }
        // B4: the troops still missing
        if (after > soglia) {
          final List<Unit> tmp = new ArrayList<>(more);
          while (missing < t.par.maxTruppeStima() && after > soglia) {
            tmp.addAll(t.temp(k.cfg.tipoTruppa(), 1));
            missing++;
            if (missing % 2 == 0 || missing == t.par.maxTruppeStima()) {
              after = k.minacciaDi(tmp);
            }
          }
        }
      }
      x.addProperty("attacco_realistico_dopo_leve_pct", Compiti.r1(after));
    } else {
      x.addProperty("stato", "PERSO");
      final List<Unit> def =
          c.getMatches(Matches.enemyUnit(t.player).and(Matches.unitIsNotInfrastructure()));
      final List<Unit> land =
          t.terraConfinante(c).stream()
              .filter(u -> u.isOwnedBy(t.player) && u.getMovementLeft().signum() > 0)
              .toList();
      final List<Unit> att = new ArrayList<>(land);
      for (final Carico ca : pianoMare(t, c, new LinkedHashSet<>(land), true)) {
        plannedSea.add(ca.json());
        planned.add(ca.trasporto());
        planned.addAll(ca.truppe());
        att.addAll(ca.truppe());
      }
      planned.addAll(land);
      land.forEach(u -> reserve.add(u.getId().toString()));
      final Compiti.Turno.Terra terra = t.terra(c, att, List.of(), def);
      x.addProperty("vittoria_ripresa_pct", Compiti.r1(terra.vittoria()));
      x.add("ripresa_attaccanti", TrasportiProtetti.perTipo(att));
      x.add("difensori", TrasportiProtetti.perTipo(def));
      x.addProperty("ripresa", inGuerraCon(t, c) && terra.vittoria() >= t.par.sogliaAttacco());
      if (terra.vittoria() < t.par.sogliaAttacco()) {
        missing = terra.truppeNecessarie();
        // nothing reserved when the city is not taken back this turn
        while (!reserve.isEmpty()) {
          reserve.remove(0);
        }
        planned.clear();
      }
    }
    x.add("riserva", reserve);
    x.add("piano_mare", plannedSea);
    final JsonArray plannedIds = new JsonArray();
    planned.forEach(u -> plannedIds.add(u.getId().toString()));
    x.add("trattenute", plannedIds);
    x.addProperty("truppe_mancanti", missing);
    // B4
    compra(t, c, missing, cassa, x);
    t.ns.add("p3", x);
    return x;
  }

  private static boolean inGuerraCon(final Compiti.Turno t, final Territory c) {
    return t.inGuerraCon(c);
  }

  /**
   * B1, the exception: an enemy force that would bring the attack (land units at most 2 away by
   * land, or transports in a zone next to the city) beaten at >= {@code vittoria_attacco_minaccia}
   * by the own units next to it, after which the city holds without the attackers from it.
   */
  static Optional<JsonObject> attaccoMinaccia(
      final Compiti.Turno t,
      final Calcolo k,
      final Territory c,
      final List<Unit> base,
      final List<Unit> own) {
    final Alleanza.Contrattacco all = Alleanza.contrattacco(t, c, k.difensori(), false);
    if (all.attaccanti().isEmpty()) {
      return Optional.empty();
    }
    final Map<Unit, Territory> where = new HashMap<>();
    for (final Unit u : all.attaccanti()) {
      final Territory at = t.dove(u);
      if (at != null) {
        where.put(u, at);
      }
    }
    final Set<Territory> sources = new LinkedHashSet<>(where.values());
    JsonObject best = null;
    double bestAfter = Double.MAX_VALUE;
    for (final Territory s :
        sources.stream().sorted(Comparator.comparing(Territory::getName)).toList()) {
      if (s.equals(c) || (!s.isWater() && !t.inGuerraCon(s))) {
        continue;
      }
      final List<Unit> enemies =
          s.getMatches(Matches.enemyUnit(t.player).and(Matches.unitIsNotInfrastructure()));
      if (enemies.isEmpty() || !t.inGuerraConUnita(enemies)) {
        continue;
      }
      final List<Unit> att;
      if (s.isWater()) {
        if (!t.data.getMap().getNeighbors(c).contains(s)) {
          continue;
        }
        att = t.attaccantiMare(s).stream().filter(u -> u.isOwnedBy(t.player)).toList();
      } else {
        att =
            t.terraConfinante(s).stream()
                .filter(u -> u.isOwnedBy(t.player) && u.getMovementLeft().signum() > 0)
                .toList();
      }
      if (att.isEmpty()) {
        continue;
      }
      final double w =
          s.isWater()
              ? t.vittoria(s, att, t.difensoriMare(s), List.of())
              : t.vittoria(s, att, enemies, List.of());
      if (w < k.cfg.vittoriaAttaccoMinaccia()) {
        continue;
      }
      final List<Unit> gone = new ArrayList<>(enemies);
      if (s.isWater()) {
        // the cargo the transports there would bring
        for (final Unit e : enemies) {
          gone.addAll(e.getTransporting());
        }
      }
      final List<Unit> left = new ArrayList<>(base);
      own.stream().filter(u -> !att.contains(u)).forEach(left::add);
      final Calcolo k2 = new Calcolo(t, c);
      k2.togli(gone);
      final double after = k2.minacciaDi(left);
      if (!attaccoMinacciaPermesso(
          w, after, k.cfg.vittoriaAttaccoMinaccia(), t.par.sogliaTenuta())) {
        continue;
      }
      if (after < bestAfter) {
        bestAfter = after;
        best = new JsonObject();
        best.addProperty("bersaglio", s.getName());
        best.addProperty("mare", s.isWater());
        best.addProperty("vittoria_pct", Compiti.r1(w));
        best.addProperty("attacco_realistico_dopo_pct", Compiti.r1(after));
        best.add("attaccanti", TrasportiProtetti.perTipo(att));
        final JsonArray ids = new JsonArray();
        att.forEach(u -> ids.add(u.getId().toString()));
        best.add("ids", ids);
      }
    }
    return Optional.ofNullable(best);
  }

  /** B4: the troops missing bought at the nearest own factory (a transport only if none there). */
  static void compra(
      final Compiti.Turno t,
      final Territory c,
      final int missing,
      final Compiti.Turno.Cassa cassa,
      final JsonObject x) {
    final JsonObject buy = new JsonObject();
    buy.addProperty("pu", 0);
    x.add("acquisto", buy);
    if (missing <= 0) {
      buy.addProperty("nota", "nessuna truppa mancante: PU a Hard");
      return;
    }
    final Map<String, Integer> turns = fabbriche(t, c);
    buy.add("fabbriche_turni", Compiti.mappa(turns));
    final Optional<String> f = fabbricaPiuVicina(turns, Config.get().maxTurniFabbrica());
    if (f.isEmpty()) {
      buy.addProperty("nota", "nessuna fabbrica propria entro max_turni_fabbrica: PU a Hard");
      return;
    }
    final Territory factory = t.data.getMap().getTerritoryOrNull(f.get());
    buy.addProperty("fabbrica", f.get());
    buy.addProperty("turni", turns.get(f.get()));
    final String type = Config.get().tipoTruppa();
    final int cost = t.costo(type);
    final boolean bySea = factory != null && viaMare(t, factory, c);
    buy.addProperty("via_mare", bySea);
    int pu = cassa.left;
    boolean transport = false;
    if (bySea && factory != null) {
      final boolean have =
          t.data.getMap().getNeighbors(factory, Matches.territoryIsWater()).stream()
              .anyMatch(
                  z -> z.anyUnitsMatch(Matches.unitIsOwnedBy(t.player).and(Compiti::trasporto)));
      buy.addProperty("trasporto_in_zona", have);
      if (!have && pu >= t.costo(t.par.tipoTrasporto()) + cost) {
        transport = true;
        pu -= t.costo(t.par.tipoTrasporto());
      }
    }
    final int room = factory == null ? 0 : t.capienza(factory) - (transport ? 1 : 0);
    final int n = truppeDaComprare(missing, Math.max(0, room), pu, cost);
    if (n <= 0) {
      buy.addProperty("nota", "PU o produzione insufficienti");
      return;
    }
    final Map<String, Integer> m =
        cassa.perFabbrica.computeIfAbsent(f.get(), q -> new LinkedHashMap<>());
    m.merge(type, n, Integer::sum);
    int spent = n * cost;
    if (transport) {
      m.merge(t.par.tipoTrasporto(), 1, Integer::sum);
      spent += t.costo(t.par.tipoTrasporto());
    }
    cassa.left -= spent;
    buy.addProperty(type, n);
    buy.addProperty("trasporto", transport ? 1 : 0);
    buy.addProperty("pu", spent);
    final JsonObject row = new JsonObject();
    row.addProperty("ostacolo", "TERRA");
    row.addProperty("luogo", c.getName());
    final JsonObject bought = new JsonObject();
    bought.addProperty(type, n);
    if (transport) {
      bought.addProperty(t.par.tipoTrasporto(), 1);
    }
    row.add("comprato", bought);
    row.addProperty("pu", spent);
    row.addProperty("fabbrica", f.get());
    cassa.righe.add(row);
    // the delivery stays open until the troops are in the city
    final JsonObject del = new JsonObject();
    del.addProperty("fabbrica", f.get());
    del.addProperty("dal_round", t.round);
    t.ns.add("p3_consegna", del);
  }

  /** The units the city holds out of Hard's combat move this turn (B1, B3, the sea plan). */
  static Set<Unit> riservate(final GameState data, final GamePlayer player) {
    final Set<Unit> out = new LinkedHashSet<>();
    if (!attivo(data, player)) {
      return out;
    }
    try {
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject ns =
          Compiti.obj(
              Compiti.obj(Compiti.stato(data.getProperties(), alliance), "nazioni"),
              player.getName());
      if (!ns.has("p3")) {
        return out;
      }
      final JsonObject p = ns.getAsJsonObject("p3");
      if (p.get("round").getAsInt() != data.getSequence().getRound() || !p.has("trattenute")) {
        return out;
      }
      final Set<String> ids = new java.util.HashSet<>();
      p.getAsJsonArray("trattenute").forEach(e -> ids.add(e.getAsString()));
      if (p.has("attacco_minaccia")) {
        p.getAsJsonObject("attacco_minaccia")
            .getAsJsonArray("ids")
            .forEach(e -> ids.add(e.getAsString()));
      }
      if (ns.has("p3_trasporti")) {
        ns.getAsJsonArray("p3_trasporti").forEach(e -> ids.add(e.getAsString()));
      }
      for (final Unit u : data.getUnits().getUnits()) {
        if (u.isOwnedBy(player) && ids.contains(u.getId().toString())) {
          out.add(u);
        }
      }
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P3, riservate: " + e);
    }
    return out;
  }

  // ======================================================================== combat (B1, B3)

  /** Combat move before Hard: the attack on the threat, the city taken back. */
  static Set<Unit> combattimento(final Compiti.Turno t, final IMoveDelegate del) {
    final Set<Unit> moved = new LinkedHashSet<>();
    if (!attivo(t.data, t.player) || !t.ns.has("p3")) {
      return moved;
    }
    final JsonObject p = t.ns.getAsJsonObject("p3");
    if (p.get("round").getAsInt() != t.round) {
      return moved;
    }
    final Territory c = citta(t);
    if (c == null) {
      return moved;
    }
    final JsonObject out = new JsonObject();
    try {
      t.aggiorna();
      if (t.alleato(c) && p.has("attacco_minaccia")) {
        final JsonObject h = p.getAsJsonObject("attacco_minaccia");
        final Territory s = t.data.getMap().getTerritoryOrNull(h.get("bersaglio").getAsString());
        if (s != null) {
          if (s.isWater()) {
            moved.addAll(t.attaccaFlotta(s));
          } else {
            moved.addAll(attaccoTerra(t, del, s, idSet(h.getAsJsonArray("ids")), out));
          }
          out.addProperty("attacco_minaccia_eseguito", !moved.isEmpty());
          out.addProperty("attacco_minaccia_su", s.getName());
        }
      } else if (!t.alleato(c) && t.inGuerraCon(c)) {
        // B3: land + amphibious, on the real board
        final List<Unit> land =
            t.terraConfinante(c).stream()
                .filter(u -> u.isOwnedBy(t.player) && u.getMovementLeft().signum() > 0)
                .toList();
        final List<Carico> sea = pianoMare(t, c, new LinkedHashSet<>(land), true);
        final List<Unit> att = new ArrayList<>(land);
        sea.forEach(ca -> att.addAll(ca.truppe()));
        final List<Unit> def =
            c.getMatches(Matches.enemyUnit(t.player).and(Matches.unitIsNotInfrastructure()));
        final double w = t.vittoria(c, att, def, List.of());
        out.addProperty("ripresa_vittoria_pct", Compiti.r1(w));
        if (w >= t.par.sogliaAttacco() && !att.isEmpty()) {
          int bySea = 0;
          final JsonArray trips = new JsonArray();
          for (final Carico ca : sea) {
            final List<Unit> landed = esegui(t, del, ca, true);
            t.aggiorna();
            if (!landed.isEmpty()) {
              bySea += landed.size();
              moved.add(ca.trasporto());
              moved.addAll(landed);
              trips.add(ca.json());
            }
          }
          int byLand = 0;
          for (final Unit u : land) {
            final Territory from = t.dove(u);
            if (from != null
                && del.performMove(new MoveDescription(List.of(u), new Route(from, c))).isEmpty()) {
              moved.add(u);
              byLand++;
            }
          }
          t.aggiorna();
          out.addProperty("ripresa_eseguita", true);
          out.addProperty("ripresa_via_terra", byLand);
          out.addProperty("ripresa_via_mare", bySea);
          out.add("ripresa_viaggi", trips);
          t.lines.add(
              "  P3 RIPRESA di "
                  + c.getName()
                  + ": "
                  + byLand
                  + " via terra, "
                  + bySea
                  + " via mare, vittoria "
                  + Compiti.r1(w)
                  + "%");
        } else {
          out.addProperty("ripresa_eseguita", false);
        }
      }
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P3, combattimento: " + e);
    }
    p.add("combattimento", out);
    return moved;
  }

  private static Set<String> idSet(final JsonArray a) {
    final Set<String> s = new java.util.HashSet<>();
    a.forEach(e -> s.add(e.getAsString()));
    return s;
  }

  private static Set<Unit> attaccoTerra(
      final Compiti.Turno t,
      final IMoveDelegate del,
      final Territory s,
      final Set<String> ids,
      final JsonObject out) {
    final Set<Unit> moved = new LinkedHashSet<>();
    final List<Unit> att =
        t.terraConfinante(s).stream()
            .filter(u -> u.isOwnedBy(t.player) && u.getMovementLeft().signum() > 0)
            .filter(u -> ids.contains(u.getId().toString()))
            .toList();
    final List<Unit> def =
        s.getMatches(Matches.enemyUnit(t.player).and(Matches.unitIsNotInfrastructure()));
    final double w = t.vittoria(s, att, def, List.of());
    out.addProperty("attacco_minaccia_vittoria_reale_pct", Compiti.r1(w));
    if (w < Config.get().vittoriaAttaccoMinaccia() || !t.inGuerraCon(s)) {
      return moved;
    }
    for (final Unit u : att) {
      final Territory from = t.dove(u);
      if (from != null
          && del.performMove(new MoveDescription(List.of(u), new Route(from, s))).isEmpty()) {
        moved.add(u);
      }
    }
    t.aggiorna();
    t.lines.add("  P3 attacco alla minaccia su " + s.getName() + ": " + moved.size() + " unita'");
    return moved;
  }

  // ========================================================================= non combat (B2)

  /**
   * Non-combat move before Hard: the city reinforced by land, by air and by sea until it holds; the
   * delivery of the troops bought (B4). Returns the units Hard leaves where they are.
   */
  static Set<Unit> nonCombattimento(
      final Compiti.Turno t, final IMoveDelegate del, final JsonObject moves) {
    final Set<Unit> held = new LinkedHashSet<>();
    if (!attivo(t.data, t.player) || !t.ns.has("p3")) {
      return held;
    }
    final JsonObject p = t.ns.getAsJsonObject("p3");
    if (p.get("round").getAsInt() != t.round) {
      return held;
    }
    final Territory c = citta(t);
    if (c == null) {
      return held;
    }
    final JsonObject out = new JsonObject();
    try {
      t.aggiorna();
      final double soglia = t.par.sogliaTenuta();
      int byLand = 0;
      int byAir = 0;
      int bySea = 0;
      final JsonArray trips = new JsonArray();
      final JsonArray tr = new JsonArray();
      if (t.alleato(c) && t.inGuerraConQualcuno()) {
        final Calcolo k = new Calcolo(t, c);
        k.senzaSchermo = true;
        final List<Unit> def = k.difensori();
        double w = k.minacciaDi(def);
        out.add("attacco_realistico_inizio", k.minaccia(def).json());
        // by land, then planes
        final List<Unit> cand = new ArrayList<>(vicineTerra(t, c, false));
        cand.addAll(aereiVerso(t, c));
        int n = 0;
        for (final Unit u : cand) {
          if (w <= soglia) {
            break;
          }
          final Territory from = t.dove(u);
          if (from == null) {
            continue;
          }
          final Route r =
              Matches.unitIsAir().test(u)
                  ? t.data.getMap().getRoute(from, c, y -> true).orElse(null)
                  : new Route(from, c);
          if (r != null && del.performMove(new MoveDescription(List.of(u), r)).isEmpty()) {
            def.add(u);
            held.add(u);
            if (Matches.unitIsAir().test(u)) {
              byAir++;
            } else {
              byLand++;
            }
            n++;
            if (n % 2 == 0) {
              w = k.minacciaDi(def);
            }
          }
        }
        if (n % 2 == 1) {
          w = k.minacciaDi(def);
        }
        t.aggiorna();
        // by sea
        if (w > soglia) {
          for (final Carico ca : pianoMare(t, c, held, false)) {
            if (w > soglia) {
              final List<Unit> landed = esegui(t, del, ca, false);
              t.aggiorna();
              if (!landed.isEmpty()) {
                bySea += landed.size();
                def.addAll(landed);
                held.add(ca.trasporto());
                held.addAll(landed);
                trips.add(ca.json());
                tr.add(ca.trasporto().getId().toString());
                w = k.minacciaDi(def);
              }
            }
          }
        }
        // the own units in the city stay, planes too (Hard moves after the task: a unit it moves
        // out, or
        // a neighbour it empties, opens the way to the enemy); with a threat above half the
        // threshold at the start of the move also the own land units next to it (they block the
        // enemy's way by land)
        held.addAll(
            c.getMatches(Matches.unitIsOwnedBy(t.player).and(Matches.unitIsNotInfrastructure())));
        final double start =
            out.getAsJsonObject("attacco_realistico_inizio")
                .get("vittoria_nemica_pct")
                .getAsDouble();
        if (start > soglia / 2) {
          final List<Unit> blockers = vicineTerra(t, c, true);
          held.addAll(blockers);
          out.addProperty("bloccanti_vicini_fermi", blockers.size());
        }
        out.addProperty("attacco_realistico_fine_pct", Compiti.r1(w));
      }
      // B4: the delivery of the troops bought
      final int delivered = consegna(t, del, c, held, trips, tr);
      out.addProperty("via_terra", byLand);
      out.addProperty("via_aria", byAir);
      out.addProperty("via_mare", bySea);
      out.addProperty("consegna_in_corso", delivered);
      out.add("viaggi", trips);
      out.add("trasporti", tr);
      moves.add("p3_citta", out);
      if (byLand + byAir + bySea > 0) {
        t.lines.add(
            "  P3 rinforzo di "
                + c.getName()
                + ": "
                + byLand
                + " via terra, "
                + byAir
                + " aerei, "
                + bySea
                + " via mare");
      }
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P3, non combattimento: " + e);
    }
    p.add("non_combattimento", out);
    return held;
  }

  /**
   * B4: the troops bought at the factory go to the city on the own transports: a loaded transport
   * of the delivery moves toward a zone next to the city, as far as it can stop safely (rule A1),
   * and unloads when there; an empty own transport next to the factory loads the own troops there.
   * The delivery ends when the city is held without a threat and no transport of it is loaded.
   */
  static int consegna(
      final Compiti.Turno t,
      final IMoveDelegate del,
      final Territory c,
      final Set<Unit> held,
      final JsonArray trips,
      final JsonArray trIds) {
    if (!t.ns.has("p3_consegna")) {
      return 0;
    }
    final JsonObject d = t.ns.getAsJsonObject("p3_consegna");
    final Territory f = t.data.getMap().getTerritoryOrNull(d.get("fabbrica").getAsString());
    final Set<String> ids =
        t.ns.has("p3_trasporti")
            ? idSet(t.ns.getAsJsonArray("p3_trasporti"))
            : new LinkedHashSet<>();
    final List<Territory> arrivals =
        t.data.getMap().getNeighbors(c, Matches.territoryIsWater()).stream()
            .sorted(Comparator.comparing(Territory::getName))
            .toList();
    // the land target: the city if allied, else an allied territory next to it
    Territory land = t.alleato(c) ? c : null;
    if (land == null) {
      land =
          t.data.getMap().getNeighbors(c, Matches.territoryIsLand()).stream()
              .filter(t::alleato)
              .sorted(Comparator.comparing(Territory::getName))
              .findFirst()
              .orElse(null);
    }
    int moving = 0;
    // 1 load at the factory
    if (f != null && f.isOwnedBy(t.player)) {
      for (final Territory z : t.data.getMap().getNeighbors(f, Matches.territoryIsWater())) {
        for (final Unit tr :
            z.getMatches(Matches.unitIsOwnedBy(t.player).and(Compiti::trasporto))) {
          if (tr.isTransporting() || !tr.getUnloaded().isEmpty() || held.contains(tr)) {
            continue;
          }
          int room = t.liberi(tr);
          for (final Unit u :
              f
                  .getMatches(
                      Matches.unitIsOwnedBy(t.player)
                          .and(Matches.unitIsLand())
                          .and(Matches.unitIsNotInfrastructure())
                          .and(x -> x.getUnitAttachment().getTransportCost() > 0)
                          .and(x -> x.getMovementLeft().signum() > 0))
                  .stream()
                  .sorted(PIU_FORTE)
                  .toList()) {
            final int cost = u.getUnitAttachment().getTransportCost();
            if (cost <= room
                && del.performMove(new MoveDescription(List.of(u), new Route(f, z), Map.of(u, tr)))
                    .isEmpty()) {
              room -= cost;
              ids.add(tr.getId().toString());
            }
          }
        }
      }
    }
    t.aggiorna();
    // 2 the loaded transports of the delivery toward the city
    for (final Unit tr : t.trasporti()) {
      if (!ids.contains(tr.getId().toString()) || !tr.isOwnedBy(t.player)) {
        continue;
      }
      if (!tr.isTransporting()) {
        ids.remove(tr.getId().toString());
        continue;
      }
      final Territory at = t.dove(tr);
      if (at == null || land == null) {
        continue;
      }
      final List<Unit> group = new ArrayList<>(List.of(tr));
      group.addAll(tr.getTransporting());
      Territory now = at;
      if (!arrivals.contains(at)) {
        Route bestRoute = null;
        int bestLen = Integer.MAX_VALUE;
        for (final Territory arr : arrivals) {
          final Optional<Route> r = t.rottaMare(at, arr, List.of(tr), false);
          if (r.isPresent() && r.get().numberOfSteps() < bestLen) {
            bestLen = r.get().numberOfSteps();
            bestRoute = r.get();
          }
        }
        if (bestRoute == null) {
          continue;
        }
        final List<Territory> steps = bestRoute.getSteps();
        final int range =
            tr.getAlreadyMoved().signum() != 0
                ? tr.getMovementLeft().intValue()
                : t.portata(tr, at);
        for (int i = Math.min(range, steps.size()); i >= 1; i--) {
          final Territory stop = steps.get(i - 1);
          final boolean arrives = arrivals.contains(stop);
          if (!arrives && !t.sicurezza(stop, List.of(tr)).sicura()) {
            continue;
          }
          final Route rr = new Route(at, steps.subList(0, i).toArray(new Territory[0]));
          if (del.performMove(new MoveDescription(group, rr)).isEmpty()) {
            now = stop;
            break;
          }
        }
      }
      held.add(tr);
      held.addAll(tr.getTransporting());
      trIds.add(tr.getId().toString());
      moving++;
      if (arrivals.contains(now) && t.data.getMap().getNeighbors(now).contains(land)) {
        final List<Unit> cargo = new ArrayList<>(tr.getTransporting());
        if (del.performMove(new MoveDescription(cargo, new Route(now, land))).isEmpty()) {
          final JsonObject trip = new JsonObject();
          trip.addProperty("trasporto", tr.getId().toString());
          trip.addProperty("consegna_da", d.get("fabbrica").getAsString());
          trip.addProperty("sbarco", land.getName());
          trip.add("truppe", TrasportiProtetti.perTipo(cargo));
          trips.add(trip);
          held.addAll(cargo);
          ids.remove(tr.getId().toString());
        }
      }
      t.aggiorna();
    }
    final JsonArray a = new JsonArray();
    ids.forEach(a::add);
    t.ns.add("p3_trasporti", a);
    final JsonObject p = t.ns.getAsJsonObject("p3");
    final boolean calm = "TENUTO".equals(p.has("stato") ? p.get("stato").getAsString() : "");
    if (ids.isEmpty() && calm && p.get("truppe_mancanti").getAsInt() == 0) {
      t.ns.remove("p3_consegna");
    }
    return moving;
  }

  // ================================================================= after Hard's moves

  /**
   * After Hard's non-combat move (register only): rows {@code ponte_peso} (did Hard send units to
   * the bridgeheads, how) and {@code cairo_tenuta} (the city's turn).
   */
  public static void dopoNonCombattimento(
      final ProData proData,
      final games.strategy.triplea.ai.pro.util.ProOddsCalculator calc,
      final GamePlayer player) {
    if (!attivo(proData.getData(), player)) {
      return;
    }
    try {
      final Compiti.Turno t = new Compiti.Turno(proData, calc, player);
      registraPonte(t);
      registraCitta(t);
      Compiti.salva(t.data.getProperties(), t.alliance, t.st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P3, registro: " + e);
    }
  }

  private static void registraPonte(final Compiti.Turno t) {
    final JsonObject par0 = new JsonObject();
    final JsonObject st =
        t.ns.has("p3_ponte") ? t.ns.getAsJsonObject("p3_ponte") : new JsonObject();
    final boolean ok = st.has("round") && st.get("round").getAsInt() == t.round;
    final JsonArray heads = ok ? st.getAsJsonArray("teste") : new JsonArray();
    par0.add("teste", heads);
    par0.addProperty("peso_attacco", Config.get().pesoPonteAttacco());
    par0.addProperty("forza_rinforzo", Config.get().forzaPonteRinforzo());
    final BattleRecords records =
        t.data.getBattleRecordsList() == null
            ? null
            : t.data.getBattleRecordsList().getBattleRecordsMapCopy().get(t.round);
    int total = 0;
    final JsonArray esiti = new JsonArray();
    for (final JsonElement e : heads) {
      final JsonObject h = e.getAsJsonObject();
      final Territory x = t.data.getMap().getTerritoryOrNull(h.get("territorio").getAsString());
      if (x == null) {
        continue;
      }
      final Set<String> before =
          st.has("presenti") && st.getAsJsonObject("presenti").has(x.getName())
              ? idSet(st.getAsJsonObject("presenti").getAsJsonArray(x.getName()))
              : Set.of();
      final Set<String> unloaded = new java.util.HashSet<>();
      for (final Territory z : t.data.getMap().getNeighbors(x, Matches.territoryIsWater())) {
        for (final Unit tr : z.getMatches(Matches.unitIsSeaTransport())) {
          tr.getUnloaded().forEach(u -> unloaded.add(u.getId().toString()));
        }
      }
      int sea = 0;
      int land = 0;
      for (final Unit u : x.getMatches(Matches.unitIsOwnedBy(t.player).and(Matches.unitIsLand()))) {
        final String id = u.getId().toString();
        if (before.contains(id)) {
          continue;
        }
        if (unloaded.contains(id)) {
          sea++;
        } else {
          land++;
        }
      }
      final boolean attacked =
          records != null
              && BattleRecords.getWereThereBattlesInTerritoriesMatching(
                  BattleRecords.getAllRecords(records), t.player, null, "any", List.of(x));
      final JsonObject o = new JsonObject();
      o.addProperty("territorio", x.getName());
      o.addProperty("stato", h.get("stato").getAsString());
      o.addProperty("unita_mandate", sea + land);
      o.addProperty("via_mare", sea);
      o.addProperty("via_terra", land);
      o.addProperty("attaccato_da_noi", attacked);
      o.addProperty("alleato_ora", t.alleato(x));
      o.addProperty(
          "perche",
          sea + land > 0
              ? "Hard ci ha mandato unita'"
              : attacked
                  ? "Hard l'ha attaccato"
                  : "Hard non ci ha mandato unita' (nessuna in portata o valori migliori altrove)");
      esiti.add(o);
      total += sea + land;
    }
    par0.add("esiti", esiti);
    par0.addProperty("unita_mandate", total);
    t.scrivi(
        "ponte_peso",
        new JsonArray(),
        heads.isEmpty() ? "nessuna" : String.valueOf(heads.size()),
        heads.isEmpty() ? "NESSUNA_TESTA" : total > 0 ? "PESO_SEGUITO" : "PESO_IGNORATO",
        heads.isEmpty()
            ? "nessuna testa di ponte alleata nel round: Hard sbarca come sempre"
            : "teste di ponte alleate con il valore alzato per Hard",
        par0);
  }

  private static void registraCitta(final Compiti.Turno t) {
    final Territory c = citta(t);
    if (c == null || !t.ns.has("p3")) {
      return;
    }
    final JsonObject p = t.ns.getAsJsonObject("p3");
    if (p.get("round").getAsInt() != t.round) {
      return;
    }
    final JsonObject par0 = p.deepCopy();
    if (t.alleato(c) && t.inGuerraConQualcuno()) {
      final Calcolo k = new Calcolo(t, c);
      par0.add("attacco_realistico_fine_turno", k.minaccia(k.difensori()).json());
    }
    par0.addProperty(
        "unita_proprie_fine_turno",
        c.getMatches(Matches.unitIsOwnedBy(t.player).and(Matches.unitIsNotInfrastructure()))
            .size());
    par0.addProperty("alleata_fine_turno", t.alleato(c));
    final String stato = p.get("stato").getAsString();
    t.scrivi(
        "cairo_tenuta",
        new JsonArray(),
        c.getName(),
        "PERSO".equals(stato) ? "PERSO" : "MINACCIATO".equals(stato) ? "MINACCIATO" : "TENUTO",
        String.format(
            Locale.ROOT,
            "%s %s, PU %d",
            c.getName(),
            stato.toLowerCase(Locale.ROOT),
            p.has("acquisto") ? p.getAsJsonObject("acquisto").get("pu").getAsInt() : 0),
        par0);
  }
}
