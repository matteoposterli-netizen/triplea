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
import games.strategy.triplea.delegate.battle.ScrambleLogic;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
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
import java.util.function.IntToDoubleFunction;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step R5 "ALLEATI RIFATTI" (switch {@link StrategaInterruttori#ALLEATI_R5}, off by default, on top
 * of the commitment): for the USA and the United Kingdom it replaces R3a and step R4's block B.
 *
 * <ul>
 *   <li><b>USA</b>: the program buys waves (70% of the PUs after the capital's garrison: transports
 *       and troops by the balanced load, the escort A3 needs); the shuttle travels as a group with
 *       at least one warship and never stops for the estimated counterattack (A2); the Gibraltar
 *       sea occupied: naval battle at >= 70%, otherwise the waves go to the United Kingdom (A3);
 *       Gibraltar of the Axis: a landing to take it (A4); from the hub each round fast units to
 *       Africa while Italy holds Africa, the rest on the highest-value target with win >= 70%, NO
 *       hold condition (A5); the landing with all the load before Hard, only the program's units
 *       held and only until they leave (A6).
 *   <li><b>United Kingdom</b>: South Africa only while an Italian African bonus is active, Egypt is
 *       not allied or Egypt is in case c (B5); transports only with the port's zone defended (B2);
 *       attrition landings with the program's units at >= 70% every turn, no hold condition, and
 *       the reinforcement of a US conquest of the round (B3); Egypt, the pincer, the neutrals east
 *       and the southern barrier as before (B4).
 * </ul>
 *
 * <p>Nothing of the Axis changes. Every decision goes to {@link RegistroDecisioni}.
 */
public final class AlleatiR5 {
  private AlleatiR5() {}

  static final String ITALIA = "ITALIA";

  // ================================================================================ config

  /** Values of {@code impegno.r5} in {@code stratega-llm.json} ("scelto" or from the map). */
  public record Config(
      boolean accesoDiDefault,
      double quota,
      double sogliaSbarco,
      double sogliaBattaglia,
      double sogliaPresa,
      double sogliaMareDifeso,
      String gibilterra,
      List<String> zoneGibilterra,
      String baseAtlantico,
      String tipoScorta,
      int maxScortaPerTurno,
      int minNaviNelGruppo,
      int maxScortaMossa,
      List<String> tipiDifesaPorto,
      int maxDifesaPorto,
      List<String> bersagliAfrica,
      List<String> bersagliResto,
      List<String> bersagliLogoramento,
      List<String> africaItaliaTerritori,
      List<String> bonusAfricani,
      Map<String, List<String>> regioni) {

    private static @Nullable Config cache;

    public static synchronized Config get() {
      if (cache == null) {
        try (InputStream in =
            AlleatiR5.class.getClassLoader().getResourceAsStream(StrategaConfig.RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + StrategaConfig.RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject()
                      .getAsJsonObject("impegno")
                      .getAsJsonObject("r5"));
        } catch (final IOException e) {
          throw new IllegalStateException("File dello stratega non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject o) {
      final Map<String, List<String>> regions = new LinkedHashMap<>();
      v(o, "regioni")
          .getAsJsonObject()
          .entrySet()
          .forEach(e -> regions.put(e.getKey(), strings(e.getValue().getAsJsonArray())));
      return new Config(
          v(o, "alleati_r5_acceso_di_default").getAsBoolean(),
          v(o, "quota_programma").getAsDouble(),
          v(o, "soglia_sbarco").getAsDouble(),
          v(o, "soglia_battaglia_gibilterra").getAsDouble(),
          v(o, "soglia_presa_gibilterra").getAsDouble(),
          v(o, "soglia_mare_difeso").getAsDouble(),
          v(o, "gibilterra").getAsString(),
          strings(v(o, "zone_gibilterra").getAsJsonArray()),
          v(o, "base_atlantico").getAsString(),
          v(o, "tipo_scorta").getAsString(),
          v(o, "max_scorta_per_turno").getAsInt(),
          v(o, "min_navi_nel_gruppo").getAsInt(),
          v(o, "max_scorta_mossa").getAsInt(),
          strings(v(o, "tipi_difesa_porto").getAsJsonArray()),
          v(o, "max_difesa_porto_per_turno").getAsInt(),
          strings(v(o, "bersagli_africa").getAsJsonArray()),
          strings(v(o, "bersagli_resto").getAsJsonArray()),
          strings(v(o, "bersagli_logoramento_uk").getAsJsonArray()),
          strings(v(o, "africa_italia_territori").getAsJsonArray()),
          strings(v(o, "bonus_africani_italiani").getAsJsonArray()),
          Map.copyOf(regions));
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

  // ============================================================================ pure rules

  /** A5, A6, B3: a landing goes with win >= the threshold; the hold is never asked. */
  static boolean sbarca(final double vittoria, final double soglia) {
    return vittoria >= soglia;
  }

  /** B2: the port's zone is defended when the realistic counterattack wins <= the threshold. */
  static boolean mareDifeso(final double contrattacco, final double soglia) {
    return contrattacco <= soglia;
  }

  /**
   * B5: South Africa buys while one condition holds; the first true one ("bonus_africano",
   * "egitto_non_alleato", "egitto_caso_c"), empty when none.
   */
  static Optional<String> condizioneSudafrica(
      final boolean bonusAttivo, final boolean egittoAlleato, final boolean egittoCasoC) {
    if (bonusAttivo) {
      return Optional.of("bonus_africano");
    }
    if (!egittoAlleato) {
      return Optional.of("egitto_non_alleato");
    }
    if (egittoCasoC) {
      return Optional.of("egitto_caso_c");
    }
    return Optional.empty();
  }

  /** A5: the fast units go to Africa while Italy holds Africa or has an African bonus active. */
  static boolean africaAperta(final boolean asseTieneAfrica, final boolean bonusAfricano) {
    return asseTieneAfrica || bonusAfricano;
  }

  /**
   * A3: the fewest escorts {@code k} (0..{@code max}) with which the battle wins at least {@code
   * soglia}; {@code max} when even that is not enough.
   */
  static int scortaPerVittoria(
      final IntToDoubleFunction vittoriaCon, final double soglia, final int max) {
    for (int k = 0; k <= max; k++) {
      if (vittoriaCon.applyAsDouble(k) >= soglia) {
        return k;
      }
    }
    return max;
  }

  /** A5: the hub's choice from the fast wave and the rest's target. */
  static Optional<String> sceltaSnodo(
      final boolean velociInAfrica, @Nullable final String restoVerso, final Config cfg) {
    final boolean france =
        restoVerso != null && ConfigR4.get().bersagliFrancia().contains(restoVerso);
    if (velociInAfrica && restoVerso != null) {
      return Optional.of(SnodoAlleati.MISTO);
    }
    if (restoVerso != null) {
      return Optional.of(france ? SnodoAlleati.FRANCIA : ITALIA);
    }
    if (velociInAfrica) {
      return Optional.of(SnodoAlleati.AFRICA);
    }
    return Optional.empty();
  }

  // ========================================================================== entry points

  /** The switch is on and the nation is the USA or the United Kingdom of the commitment. */
  public static boolean attivo(final GameState data, final GamePlayer player) {
    if (player == null
        || !(Programmi.USA.equals(player.getName()) || Programmi.UK.equals(player.getName()))
        || !SbarcoCoordinato.attivo(data, player)) {
      return false;
    }
    // step R6: the Gibraltar squadron uses R5's machinery for the USA (program by id, battle,
    // taking Gibraltar, the hub's landings) with its own purchase, holds and moves
    return ProPlusSettings.allianceOf(data, player)
            .map(a -> StrategaInterruttori.ALLEATI_R5.acceso(data.getProperties(), a))
            .orElse(false)
        || SquadraGibilterra.attivo(data, player);
  }

  /** The program's units of {@code player} by id (R4's {@code programma_ids}). */
  static Predicate<Unit> delProgramma(final GameState data, final GamePlayer player) {
    final Set<String> ids = SnodoAlleati.programma(data, player);
    return u -> ids.contains(u.getId().toString());
  }

  /** A transport of the program (step R5: the program's waves use only its own transports). */
  static Predicate<Unit> trasportoDelProgramma(final GameState data, final GamePlayer player) {
    return delProgramma(data, player).and(Matches.unitIsSeaTransport());
  }

  /** The Axis holds Egypt or a territory of North Africa (A5). */
  static boolean asseTieneAfrica(final GameState data, final GamePlayer player) {
    return SnodoAlleati.contaAsse(data, player, Config.get().africaItaliaTerritori()) > 0;
  }

  /** The Italian African bonuses active now (by ownership), id → PUs. */
  static Map<String, Integer> bonusAfricaniAttivi(final GameState data) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    for (final String id : Config.get().bonusAfricani()) {
      final int v = Reddito.valoreOra(data, id);
      if (v > 0) {
        out.put(id, v);
      }
    }
    return out;
  }

  /** After a landing or a reinforcement: the program's units that left are Hard's from now on. */
  static void partite(final GameState data, final GamePlayer player, final Collection<Unit> u) {
    if (u.isEmpty()) {
      return;
    }
    final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
    final JsonObject st = Impegno.stato(data.getProperties(), alliance);
    final JsonObject ns = Impegno.nazione(st, player.getName());
    if (!ns.has("programma_ids")) {
      return;
    }
    final JsonObject ids = ns.getAsJsonObject("programma_ids");
    for (final Unit x : u) {
      if (Matches.unitIsLand().test(x)) {
        ids.remove(x.getId().toString());
      }
    }
    Impegno.salva(data.getProperties(), alliance, st);
  }

  // ================================================================= purchase (Impegno)

  /**
   * B5: the United Kingdom's purchase in South Africa (up to the factory's capacity) only while an
   * Italian African bonus is active, Egypt is not allied or Egypt is in case c. Returns the PUs.
   */
  static int acquistiSudafrica(
      final GameState data,
      final GamePlayer player,
      final int pu,
      final JsonObject order,
      final List<String> lines,
      final boolean egittoCasoC) {
    if (!attivo(data, player) || !Programmi.UK.equals(player.getName())) {
      return 0;
    }
    final Territory factory = data.getMap().getTerritoryOrNull(ConfigR4.get().fabbricaSudafrica());
    if (factory == null || !factory.isOwnedBy(player)) {
      return 0;
    }
    final Map<String, Integer> bonus = bonusAfricaniAttivi(data);
    final Territory egypt = data.getMap().getTerritoryOrNull("Egypt");
    final boolean egyptOk = egypt != null && Matches.isTerritoryAllied(player).test(egypt);
    final Optional<String> cond = condizioneSudafrica(!bonus.isEmpty(), egyptOk, egittoCasoC);
    final int capacity = Assedio.capacita(factory);
    final Map<String, Integer> mix =
        SnodoAlleati.compraSudafrica(
            data, player, factory, cond.isPresent() ? capacity : 0, pu, order);
    final int spent = Impegno.costo(data, player, mix);
    final JsonArray alt = new JsonArray();
    final JsonObject a = new JsonObject();
    final JsonObject b = new JsonObject();
    bonus.forEach(b::addProperty);
    a.add("bonus_africani_attivi", b);
    a.addProperty("egitto_alleato", egyptOk);
    a.addProperty("egitto_caso_c", egittoCasoC);
    a.addProperty("condizione", cond.orElse("nessuna"));
    a.addProperty("capacita", capacity);
    a.addProperty("pu_disponibili", pu);
    final JsonObject m = new JsonObject();
    mix.forEach(m::addProperty);
    a.add("ordine", m);
    a.addProperty("pu_ordinati", spent);
    alt.add(a);
    registra(
        data,
        player,
        "africa_sudafrica",
        alt,
        mix.isEmpty() ? "niente" : m.toString(),
        cond.isPresent() ? "CONDIZIONE_VERA" : "CONDIZIONE_FALSA",
        cond.map(c -> "condizione B5 vera (" + c + "): Sudafrica fino alla capacita'")
            .orElse("nessuna condizione B5 vera: niente Sudafrica"),
        new JsonObject());
    lines.add(
        "  R5 Sudafrica: "
            + cond.orElse("nessuna condizione")
            + " -> "
            + (mix.isEmpty() ? "niente" : mix)
            + " ("
            + spent
            + " PU)");
    return spent;
  }

  /**
   * A1 / B1-B2: the program's purchase with {@code pu} PUs. USA: escorts as A3 needs (and one
   * warship for the group), then the balanced load on the program's transports. United Kingdom: the
   * port's zone defended (B2) ⇒ the balanced load and transports for Hard too; not defended ⇒ first
   * what defends it, no transport for the program nor for Hard (the block is registered).
   */
  static Map<String, Integer> acquistiProgramma(
      final ProData proData,
      final ProOddsCalculator calc,
      final Programmi.Turno p,
      final int pu,
      final int garrison,
      final JsonObject turn) {
    final GameState data = proData.getData();
    final GamePlayer player = p.player;
    final Config cfg = Config.get();
    final String tettoKey = SbarcoCoordinato.KEY_TETTO + player.getName();
    data.getProperties().set(tettoKey, (Serializable) null);
    if (Programmi.USA.equals(player.getName())) {
      p.scortaR5 = scortaUsa(proData, calc, p);
      p.maxScortaR5 = cfg.maxScortaPerTurno();
      final Map<String, Integer> mix = p.acquisti(pu, garrison, turn);
      if (turn.has("programma")) {
        turn.getAsJsonObject("programma").addProperty("pu_turno", puTurno(player));
        turn.getAsJsonObject("programma").addProperty("scorta_a3", p.scortaR5);
      }
      return mix;
    }
    // United Kingdom: B2
    final Difesa d = difesaPorto(proData, p, pu);
    final Map<String, Integer> out = new LinkedHashMap<>(d.acquisto());
    final int defenseCost = Impegno.costo(data, player, d.acquisto());
    p.scortaR5 = 0;
    p.maxScortaR5 = 0;
    p.vietaTrasporti = !d.difeso();
    if (!d.difeso()) {
      data.getProperties()
          .set(tettoKey, String.valueOf(data.getSequence().getRound())); // no transport for Hard
    }
    final Map<String, Integer> mix = p.acquisti(Math.max(0, pu - defenseCost), garrison, turn);
    mix.forEach((k, v) -> out.merge(k, v, Integer::sum));
    if (turn.has("programma")) {
      final JsonObject prog = turn.getAsJsonObject("programma");
      prog.addProperty("pu_turno", puTurno(player));
      prog.addProperty("pu", pu);
      prog.addProperty("pu_difesa_porto", defenseCost);
      prog.addProperty(
          "pu_ordine",
          (prog.has("pu_ordine") ? prog.get("pu_ordine").getAsInt() : 0) + defenseCost);
      final JsonObject m = new JsonObject();
      out.forEach(m::addProperty);
      prog.add("ordine", m);
    }
    final JsonArray alt = d.zone().deepCopy();
    final JsonObject buy = new JsonObject();
    d.acquisto().forEach(buy::addProperty);
    final JsonObject par = new JsonObject();
    par.addProperty("soglia_mare_difeso", cfg.sogliaMareDifeso());
    par.add("difesa_comprata", buy);
    par.addProperty("contrattacco_migliore_pct", round1(d.migliore()));
    registra(
        data,
        player,
        "trasporti_mare_difeso",
        alt,
        d.difeso() ? "permesso" : "bloccato",
        d.difeso() ? "MARE_DIFESO" : "MARE_NON_DIFESO",
        d.difeso()
            ? String.format(
                Locale.ROOT,
                "zona del porto difesa (contrattacco %.1f%% <= %.0f): trasporti permessi",
                d.migliore(),
                cfg.sogliaMareDifeso())
            : String.format(
                Locale.ROOT,
                "zona del porto non difesa (contrattacco %.1f%% > %.0f): niente trasporti, prima"
                    + " la difesa %s",
                d.migliore(),
                cfg.sogliaMareDifeso(),
                d.acquisto()),
        par);
    p.lines.add(
        "  R5 mare del porto "
            + (d.difeso() ? "DIFESO" : "NON difeso")
            + String.format(Locale.ROOT, " (contrattacco %.1f%%)", d.migliore())
            + (d.acquisto().isEmpty() ? "" : ", difesa comprata " + d.acquisto()));
    return out;
  }

  private static int puTurno(final GamePlayer player) {
    return player.getResources().getQuantity(games.strategy.triplea.Constants.PUS);
  }

  /** A1-A3: escorts of the US program: one warship for the group, and what A3's battle needs. */
  private static int scortaUsa(
      final ProData proData, final ProOddsCalculator calc, final Programmi.Turno p) {
    final Config cfg = Config.get();
    final GameState data = proData.getData();
    int need = 0;
    int warships = 0;
    for (final Territory z : p.zone(p.porto())) {
      warships += z.getMatches(naveDaGuerra().and(Matches.unitIsOwnedBy(p.player))).size();
    }
    if (warships < cfg.minNaviNelGruppo()) {
      need = cfg.minNaviNelGruppo() - warships;
    }
    final Optional<UnitType> escort = data.getUnitTypeList().getUnitType(cfg.tipoScorta());
    if (escort.isPresent()) {
      for (final Territory z : zoneGibilterra(data)) {
        if (navi(z, p.player).isEmpty()) {
          continue;
        }
        final List<Unit> ships = naviInPortata(data, p.player, z);
        final int k =
            scortaPerVittoria(
                x -> {
                  final List<Unit> a = new ArrayList<>(ships);
                  a.addAll(escort.get().createTemp(x, p.player));
                  return vittoriaBattaglia(proData, calc, z, a, p.player);
                },
                cfg.sogliaBattaglia(),
                cfg.maxScortaPerTurno());
        need = Math.max(need, k);
      }
    }
    return Math.min(need, cfg.maxScortaPerTurno());
  }

  /** B2: the defense of the British port's zones and what to buy when not defended. */
  record Difesa(boolean difeso, double migliore, JsonArray zone, Map<String, Integer> acquisto) {}

  static Difesa difesaPorto(final ProData proData, final Programmi.Turno p, final int pu) {
    final Config cfg = Config.get();
    final GameState data = proData.getData();
    final JsonArray zones = new JsonArray();
    double best = Double.MAX_VALUE;
    Territory bestZone = null;
    for (final Territory z : p.zone(p.porto())) {
      final List<Unit> air = aereiInDifesa(data, p.player, z);
      final double w = p.vittoriaNemicaMare(z, air);
      final JsonObject a = new JsonObject();
      a.addProperty("zona", z.getName());
      a.addProperty(
          "navi_alleate",
          z.getMatches(Matches.isUnitAllied(p.player).and(Matches.unitIsSea())).size());
      a.addProperty(
          "aerei_portaerei",
          z.getMatches(Matches.isUnitAllied(p.player).and(Matches.unitIsAir())).size());
      a.addProperty("aerei_scramble", scramble(data, p.player, z).size());
      a.addProperty("contrattacco_pct", round1(w));
      zones.add(a);
      if (w < best) {
        best = w;
        bestZone = z;
      }
    }
    if (bestZone == null) {
      return new Difesa(false, 100, zones, Map.of());
    }
    if (mareDifeso(best, cfg.sogliaMareDifeso())) {
      return new Difesa(true, best, zones, Map.of());
    }
    // not defended: the cheapest of the types that brings the best zone within the threshold
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, p.player);
    final Territory z = bestZone;
    final List<Unit> base = aereiInDifesa(data, p.player, z);
    final int airRoom = postiScramble(data, p.player, p.porto());
    Map<String, Integer> choice = Map.of();
    int choiceCost = Integer.MAX_VALUE;
    double choiceWin = 101;
    for (final String type : cfg.tipiDifesaPorto()) {
      final Optional<UnitType> ut = data.getUnitTypeList().getUnitType(type);
      final int cost = costs.getOrDefault(type, 0);
      if (ut.isEmpty() || cost <= 0) {
        continue;
      }
      final boolean air = ut.get().getUnitAttachment().isAir();
      final int max = Math.min(cfg.maxDifesaPorto(), air ? airRoom : cfg.maxDifesaPorto());
      for (int k = 1; k <= max && k * cost <= pu; k++) {
        final List<Unit> extra = new ArrayList<>(base);
        extra.addAll(ut.get().createTemp(k, p.player));
        final double w = p.vittoriaNemicaMare(z, extra);
        final boolean ok = mareDifeso(w, cfg.sogliaMareDifeso());
        if ((ok && k * cost < choiceCost) || (choiceCost == Integer.MAX_VALUE && w < choiceWin)) {
          if (ok) {
            choiceCost = k * cost;
          }
          choiceWin = w;
          choice = Map.of(type, k);
        }
        if (ok) {
          break;
        }
      }
    }
    return new Difesa(false, best, zones, choice);
  }

  /** B2: allied planes on carriers in {@code z} and those that can scramble to it. */
  static List<Unit> aereiInDifesa(
      final GameState data, final GamePlayer player, final Territory z) {
    final List<Unit> out =
        new ArrayList<>(z.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsAir())));
    scramble(data, player, z).stream().filter(u -> !out.contains(u)).forEach(out::add);
    return out;
  }

  /**
   * The allied planes that can scramble to {@code z} by the map's rules (engine's {@link
   * ScrambleLogic}: airbase not disabled, distance, at most the airbases' count per territory).
   */
  static List<Unit> scramble(final GameState data, final GamePlayer player, final Territory z) {
    final List<Unit> out = new ArrayList<>();
    if (!Properties.getScrambleRulesInEffect(data.getProperties())) {
      return out;
    }
    final GamePlayer enemy = nemico(data, player);
    if (enemy == null) {
      return out;
    }
    try {
      final var byFrom =
          new ScrambleLogic(data, enemy, z).getUnitsThatCanScrambleByDestination().get(z);
      if (byFrom == null) {
        return out;
      }
      for (final var e : byFrom.entrySet()) {
        final int max = ScrambleLogic.getMaxScrambleCount(e.getValue().getFirst());
        e.getValue().getSecond().stream()
            .filter(Matches.isUnitAllied(player))
            .sorted(
                Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getDefense(u.getOwner()))
                    .thenComparing(u -> u.getId().toString()))
            .limit(max)
            .forEach(out::add);
      }
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R5, scramble: " + e);
    }
    return out;
  }

  /** Free scramble places of {@code t}'s airbases (planes bought there could defend). */
  private static int postiScramble(
      final GameState data, final GamePlayer player, @Nullable final Territory t) {
    if (t == null || !Properties.getScrambleRulesInEffect(data.getProperties())) {
      return 0;
    }
    final List<Unit> bases = t.getMatches(Matches.unitIsAirBase().and(Matches.unitIsNotDisabled()));
    if (bases.isEmpty()) {
      return 0;
    }
    final int max = ScrambleLogic.getMaxScrambleCount(bases);
    final long planes =
        t.getMatches(Matches.isUnitAllied(player).and(Matches.unitCanScramble())).size();
    return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) max - planes));
  }

  @Nullable
  private static GamePlayer nemico(final GameState data, final GamePlayer player) {
    return data.getPlayerList().getPlayers().stream()
        .filter(x -> data.getRelationshipTracker().isAtWar(player, x))
        .filter(x -> ProPlusSettings.allianceOf(data, x).isPresent())
        .min(Comparator.comparing(GamePlayer::getName))
        .orElse(null);
  }

  // ======================================================== Gibraltar (A2-A4), helpers

  static Predicate<Unit> naveDaGuerra() {
    return Matches.unitIsSea().and(Matches.unitIsSeaTransportButNotCombatSeaTransport().negate());
  }

  static List<Territory> zoneGibilterra(final GameState data) {
    final List<Territory> out = new ArrayList<>();
    for (final String n : Config.get().zoneGibilterra()) {
      final Territory z = data.getMap().getTerritoryOrNull(n);
      if (z != null) {
        out.add(z);
      }
    }
    return out;
  }

  /** The enemy warships in {@code z}. */
  static List<Unit> navi(final Territory z, final GamePlayer player) {
    return z.getMatches(Matches.enemyUnit(player).and(naveDaGuerra()));
  }

  /** The US warships (no carrier) that reach {@code z} this turn. */
  static List<Unit> naviInPortata(
      final GameState data, final GamePlayer player, final Territory z) {
    final List<Unit> out = new ArrayList<>();
    final Predicate<Territory> through =
        x ->
            x.isWater()
                && ProMatches.territoryCanMoveSeaUnitsThrough(player, false).test(x)
                && (x.equals(z) || !x.anyUnitsMatch(Matches.enemyUnit(player)));
    for (final Territory from : data.getMap().getTerritories()) {
      if (!from.isWater()) {
        continue;
      }
      for (final Unit u :
          from.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(naveDaGuerra())
                  .and(Matches.unitIsCarrier().negate())
                  .and(Matches.unitHasMovementLeft()))) {
        if (from.equals(z)) {
          out.add(u);
          continue;
        }
        final Optional<Route> r =
            data.getMap().getRouteForUnits(from, z, through, List.of(u), player);
        if (r.isPresent() && r.get().numberOfSteps() <= u.getMovementLeft().intValue()) {
          out.add(u);
        }
      }
    }
    out.sort(Comparator.comparing(u -> u.getId().toString()));
    return out;
  }

  static double vittoriaBattaglia(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory z,
      final List<Unit> attackers,
      final GamePlayer player) {
    if (attackers.isEmpty()) {
      return 0;
    }
    final List<Unit> defenders = z.getMatches(Matches.enemyUnit(player));
    if (defenders.isEmpty()) {
      return 100;
    }
    return calc.calculateBattleResults(proData, z, attackers, defenders, List.of())
        .getWinPercentage();
  }

  // ========================================================== the R5 turn (SbarcoCoordinato)

  /**
   * Purchase of the USA or the United Kingdom with R5 (called by {@link
   * SbarcoCoordinato.Turno#inizio}): checks of earlier decisions, then USA: route and Gibraltar
   * (A2-A4) and the hub's choice (A5); United Kingdom: the attrition landing (B3). The program's
   * units still not gone are held out of Hard's combat.
   */
  static void inizio(final SbarcoCoordinato.Turno t) {
    if (SquadraGibilterra.attivo(t.data, t.player)) {
      // step R6: the Gibraltar squadron replaces R5's turn of the USA
      SquadraGibilterra.inizio(t);
      return;
    }
    t.lines.add("  --- R5 alleati rifatti (" + t.player.getName() + ") ---");
    t.controlli();
    final List<Unit> wave = new ArrayList<>();
    if (t.usa()) {
      final boolean presa = rotta(t);
      if (!presa) {
        wave.addAll(snodo(t));
      }
    } else {
      wave.addAll(logoramento(t));
    }
    // A6: only the program's units (land units until they leave, its ships), not its planes
    final Set<Unit> held = new LinkedHashSet<>(wave);
    final Predicate<Unit> prog = t.delProgramma();
    for (final Territory x : t.data.getMap().getTerritories()) {
      x.getMatches(prog.and(Matches.unitIsAir().negate())).forEach(held::add);
    }
    final JsonObject tr = SbarcoCoordinato.obj(t.st, "trattenute");
    final JsonObject n = new JsonObject();
    n.addProperty("round", t.round);
    final JsonArray ids = new JsonArray();
    held.forEach(u -> ids.add(u.getId().toString()));
    n.add("ids", ids);
    tr.add(t.player.getName(), n);
    final JsonArray alt = new JsonArray();
    final JsonObject a = new JsonObject();
    a.addProperty("unita", held.size());
    a.addProperty("tuv", Math.round(t.c.tuv(held)));
    alt.add(a);
    RegistroDecisioni.scrivi(
        t.data,
        RegistroDecisioni.decisione(
            RegistroDecisioni.nuovoId(t.st, t.round, t.player.getName(), "unita_trattenute"),
            t.round,
            t.player.getName(),
            "unita_trattenute",
            alt,
            String.valueOf(held.size()),
            "SOLO_PROGRAMMA",
            "trattenute solo le unita' comprate dal programma non ancora partite",
            parametri()));
    t.lines.add("  R5 trattenute (solo programma): " + held.size() + " unita'");
    SbarcoCoordinato.salva(t.props, t.alliance, t.st);
  }

  static JsonObject parametri() {
    final Config cfg = Config.get();
    final JsonObject o = new JsonObject();
    o.addProperty("soglia_sbarco", cfg.sogliaSbarco());
    o.addProperty("soglia_battaglia_gibilterra", cfg.sogliaBattaglia());
    o.addProperty("soglia_presa_gibilterra", cfg.sogliaPresa());
    o.addProperty("condizione_di_tenuta", false);
    return o;
  }

  /**
   * A2-A4: Gibraltar usable ⇒ hub Gibraltar; its sea occupied ⇒ the naval battle at >= 70% (in the
   * combat move) and meanwhile the waves to the United Kingdom; Gibraltar of the Axis ⇒ the landing
   * to take it (sea free, >= 70%). Returns true when the landing on Gibraltar was planned.
   */
  private static boolean rotta(final SbarcoCoordinato.Turno t) {
    final Config cfg = Config.get();
    final Territory g = t.data.getMap().getTerritoryOrNull(cfg.gibilterra());
    final boolean allied = g != null && SbarcoCoordinato.dellAlleanza(t.data, t.alliance, g);
    final JsonArray alt = new JsonArray();
    final List<Territory> occupied = new ArrayList<>();
    for (final Territory z : zoneGibilterra(t.data)) {
      final List<Unit> enemy = navi(z, t.player);
      final JsonObject a = new JsonObject();
      a.addProperty("zona", z.getName());
      a.addProperty("navi_nemiche", enemy.size());
      a.addProperty("tuv_nemico", Math.round(t.c.tuv(enemy)));
      alt.add(a);
      if (!enemy.isEmpty()) {
        occupied.add(z);
      }
    }
    t.st.remove("battaglia_r5");
    final boolean usable = allied && occupied.isEmpty();
    final String theater = usable ? SbarcoCoordinato.SUD : SbarcoCoordinato.NORD;
    final Territory base = usable ? g : t.data.getMap().getTerritoryOrNull(cfg.baseAtlantico());
    final String code =
        usable
            ? "GIBILTERRA"
            : !occupied.isEmpty() ? "MARE_GIBILTERRA_OCCUPATO" : "GIBILTERRA_ASSE";
    final JsonObject par = parametri();
    par.addProperty("gibilterra_dell_alleanza", allied);
    RegistroDecisioni.scrivi(
        t.data,
        RegistroDecisioni.decisione(
            RegistroDecisioni.nuovoId(t.st, t.round, t.player.getName(), "rotta_usa"),
            t.round,
            t.player.getName(),
            "rotta_usa",
            alt,
            usable ? cfg.gibilterra() : cfg.baseAtlantico(),
            code,
            usable
                ? "Gibilterra dell'alleanza e mare libero: snodo Gibilterra"
                : (!occupied.isEmpty()
                        ? "mare di Gibilterra occupato da navi da guerra nemiche"
                        : "Gibilterra dell'Asse")
                    + ": ondate al Regno Unito dall'Atlantico",
            par));
    final JsonObject rec = new JsonObject();
    rec.addProperty("round", t.round);
    rec.addProperty("teatro", theater);
    rec.addProperty("snodo", usable ? cfg.gibilterra() : cfg.baseAtlantico());
    SbarcoCoordinato.arr(t.st, "round_teatro").add(rec);
    t.st.addProperty("teatro", theater);
    if (base != null && SbarcoCoordinato.dellAlleanza(t.data, t.alliance, base)) {
      t.st.addProperty("base", base.getName());
      t.st.addProperty("base_origine", "teatro");
    }
    t.lines.add(
        "  R5 rotta: " + (usable ? "snodo Gibilterra" : "ondate al Regno Unito (" + code + ")"));
    // A3: the naval battle
    for (final Territory z : occupied) {
      battagliaPrevista(t, z);
    }
    // A4: Gibraltar of the Axis, its sea free: the landing to take it
    if (g != null && !allied && occupied.isEmpty() && t.c.nemico(g.getOwner())) {
      return presaGibilterra(t, g);
    }
    return false;
  }

  /** A3 at the purchase: the battle in the zone with the US warships in range (>= 70%). */
  static void battagliaPrevista(final SbarcoCoordinato.Turno t, final Territory z) {
    final Config cfg = Config.get();
    final List<Unit> ships = naviInPortata(t.data, t.player, z);
    final double win = vittoriaBattaglia(t.proData, t.calc, z, ships, t.player);
    final boolean go = sbarca(win, cfg.sogliaBattaglia());
    final JsonArray alt = new JsonArray();
    final JsonObject a = new JsonObject();
    a.addProperty("zona", z.getName());
    a.addProperty("navi_usa_in_portata", ships.size());
    a.addProperty("tuv_usa", Math.round(t.c.tuv(ships)));
    a.addProperty("tuv_nemico", Math.round(t.c.tuv(z.getMatches(Matches.enemyUnit(t.player)))));
    a.addProperty("vittoria_pct", round1(win));
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
            go ? "battaglia in " + z.getName() : "niente: ondate al Regno Unito",
            go ? "BATTAGLIA" : "SOTTO_SOGLIA",
            String.format(
                Locale.ROOT,
                "vittoria %.1f%% %s %.0f con %d navi USA in portata",
                win,
                go ? ">=" : "<",
                cfg.sogliaBattaglia(),
                ships.size()),
            parametri()));
    t.lines.add(
        String.format(
            Locale.ROOT,
            "  R5 battaglia di Gibilterra in %s: vittoria %.1f%% con %d navi -> %s",
            z.getName(),
            win,
            ships.size(),
            go ? "BATTAGLIA nel combattimento" : "sotto soglia, il programma compra scorta"));
    if (go) {
      final JsonObject b = SbarcoCoordinato.obj(t.st, "battaglia_r5");
      b.addProperty("round", t.round);
      final JsonObject x = new JsonObject();
      x.addProperty("id", id);
      x.addProperty("vittoria_pct", round1(win));
      x.addProperty("eseguita", false);
      b.add(z.getName(), x);
    }
  }

  /** A4: the landing on Gibraltar from the United Kingdom (program's units, >= 70%). */
  static boolean presaGibilterra(final SbarcoCoordinato.Turno t, final Territory g) {
    final Config cfg = Config.get();
    t.filtro = t.delProgramma();
    final SbarcoCoordinato.Turno.Piano plan = t.piano(g, t.player, t.presidioBase());
    t.filtro = null;
    final JsonArray alt = new JsonArray();
    final JsonObject a = new JsonObject();
    a.addProperty("territorio", g.getName());
    a.addProperty("unita", plan.carico().size());
    a.addProperty("trasporti", plan.viaggi().size());
    double win = 0;
    SbarcoCoordinato.Turno.Stima s = null;
    if (!plan.carico().isEmpty()) {
      s = t.stima(plan, false);
      win = s.vittoria();
    }
    a.addProperty("vittoria_pct", round1(win));
    alt.add(a);
    final boolean go = !plan.carico().isEmpty() && sbarca(win, cfg.sogliaPresa());
    final String id =
        RegistroDecisioni.nuovoId(t.st, t.round, t.player.getName(), "presa_gibilterra");
    RegistroDecisioni.scrivi(
        t.data,
        RegistroDecisioni.decisione(
            id,
            t.round,
            t.player.getName(),
            "presa_gibilterra",
            alt,
            go ? "sbarco su " + g.getName() : "attesa",
            go ? "PRESA" : plan.carico().isEmpty() ? "NESSUN_CARICO" : "MANCA_VITTORIA",
            go
                ? "sbarco per prendere Gibilterra (vittoria " + round1(win) + "%)"
                : "Gibilterra non ancora presa: manca "
                    + (plan.carico().isEmpty()
                        ? "il carico in posizione"
                        : "vittoria (" + round1(win) + "% < " + cfg.sogliaPresa() + ")"),
            parametri()));
    t.lines.add(
        "  R5 presa di Gibilterra: " + (go ? "SBARCO" : "attesa") + " (" + round1(win) + "%)");
    t.st.remove("ondata");
    t.st.remove("ondata_africa");
    if (!go) {
      return false;
    }
    if (TrasportiProtetti.attivo(t.data, t.player)) {
      // step R6b (A3): the transports protected, else no landing
      t.scorteUsate.clear();
      final Optional<TrasportiProtetti.Protezione> pr =
          TrasportiProtetti.proteggi(t, g, plan, s, t.delProgramma(), "PRESA");
      if (pr.isEmpty()) {
        return false;
      }
      final JsonObject w =
          onda(t, g, pr.get().stima(), pr.get().piano(), "programma", id, "GIBILTERRA");
      TrasportiProtetti.salvaInOnda(w, pr.get());
      t.st.add("ondata", w);
      return true;
    }
    t.st.add("ondata", onda(t, g, s, plan, "programma", id, "GIBILTERRA"));
    return true;
  }

  private static JsonObject onda(
      final SbarcoCoordinato.Turno t,
      final Territory target,
      final SbarcoCoordinato.Turno.Stima s,
      final SbarcoCoordinato.Turno.Piano plan,
      final String filter,
      final String id,
      final String choice) {
    final JsonObject w = new JsonObject();
    w.addProperty("round", t.round);
    w.addProperty("nazione", t.player.getName());
    w.addProperty("bersaglio", target.getName());
    w.addProperty("id", id);
    w.addProperty("filtro", filter);
    w.addProperty("snodo", choice);
    w.addProperty("vittoria_pct", round1(s.vittoria()));
    w.addProperty("perdite_p80_tuv", round1(s.p80()));
    w.addProperty("tenuta_alleanza_pct", round1(100 - s.contrattaccoAlleanza()));
    w.addProperty("tenuta_usa_pct", round1(100 - s.contrattaccoSolo()));
    w.addProperty("unita", plan.carico().size());
    w.addProperty("rinforzi_uk_stimati", 0);
    w.addProperty("presidio_base", t.presidioBase());
    w.addProperty("eseguito", false);
    w.addProperty("r5", true);
    // step R6 (C3): the squadron's ships and planes support this wave
    if (t.supportoR6) {
      w.addProperty("supporto_r6", true);
    }
    return w;
  }

  /** One option: the best target of a group with win >= 70 (value, then win). */
  private record Opzione(
      Territory t,
      SbarcoCoordinato.Turno.Piano piano,
      SbarcoCoordinato.Turno.Stima stima,
      double valore,
      @Nullable TrasportiProtetti.Protezione protezione) {}

  @Nullable
  private static Opzione migliore(
      final SbarcoCoordinato.Turno t,
      final List<Territory> targets,
      final Predicate<Unit> units,
      final JsonArray alt,
      final String label) {
    final Config cfg = Config.get();
    final List<Opzione> candidates = new ArrayList<>();
    for (final Territory x : targets) {
      t.filtro = units;
      final SbarcoCoordinato.Turno.Piano plan = t.piano(x, t.player, t.presidioBase());
      t.filtro = null;
      final Reddito.Guadagno g = Reddito.guadagno(t.data, t.player, x);
      final double value =
          CampagnaOmbra.Calcolo.valoreIndice(x) + g.bonusOttenuti() + g.bonusTolti();
      final JsonObject a = g.json();
      a.addProperty("gruppo", label);
      a.addProperty("territorio", x.getName());
      a.addProperty("valore", round1(value));
      a.addProperty("unita", plan.carico().size());
      a.addProperty("trasporti", plan.viaggi().size());
      if (plan.carico().isEmpty()) {
        a.addProperty("esito", "NESSUN_CARICO");
        alt.add(a);
        continue;
      }
      final SbarcoCoordinato.Turno.Stima s = t.stima(plan, false);
      final boolean go = sbarca(s.vittoria(), cfg.sogliaSbarco());
      a.addProperty("vittoria_pct", round1(s.vittoria()));
      a.addProperty("tenuta_stimata_pct_info", round1(100 - s.contrattaccoSolo()));
      a.addProperty("esito", go ? "SBARCO" : "MANCA_VITTORIA");
      alt.add(a);
      if (go) {
        candidates.add(new Opzione(x, plan, s, value, null));
      }
    }
    // the highest value first, then the win
    candidates.sort(
        Comparator.comparingDouble((Opzione o) -> -o.valore())
            .thenComparingDouble(o -> -o.stima().vittoria()));
    if (!TrasportiProtetti.attivo(t.data, t.player)) {
      return candidates.isEmpty() ? null : candidates.get(0);
    }
    // step R6b (A3): the transports protected, else the next target by value
    return primoProtetto(
            candidates,
            o -> {
              final Optional<TrasportiProtetti.Protezione> pr =
                  TrasportiProtetti.proteggi(t, o.t(), o.piano(), o.stima(), units, label);
              for (final var e : alt) {
                final JsonObject a = e.getAsJsonObject();
                if (label.equals(a.has("gruppo") ? a.get("gruppo").getAsString() : "")
                    && o.t().getName().equals(a.get("territorio").getAsString())) {
                  a.addProperty(
                      "trasporti",
                      pr.map(TrasportiProtetti.Protezione::esito)
                          .orElse(TrasportiProtetti.RINUNCIA));
                }
              }
              return pr;
            })
        .map(
            e ->
                new Opzione(
                    e.getKey().t(),
                    e.getValue().piano(),
                    e.getValue().stima(),
                    e.getKey().valore(),
                    e.getValue()))
        .orElse(null);
  }

  /**
   * Step R6b (A3): the first of {@code ordered} (highest value first) whose transports can be
   * protected, with its protection; the ones given up before it are skipped.
   */
  static <T, P> Optional<Map.Entry<T, P>> primoProtetto(
      final List<T> ordered, final java.util.function.Function<T, Optional<P>> protect) {
    for (final T o : ordered) {
      final Optional<P> p = protect.apply(o);
      if (p.isPresent()) {
        return Optional.of(Map.entry(o, p.get()));
      }
    }
    return Optional.empty();
  }

  private static List<Territory> nemiciInGuerra(
      final SbarcoCoordinato.Turno t, final List<String> names) {
    final List<Territory> out = new ArrayList<>();
    for (final String n : names) {
      final Territory x = t.data.getMap().getTerritoryOrNull(n);
      if (x != null
          && !x.isWater()
          && t.c.nemico(x.getOwner())
          && t.data.getRelationshipTracker().isAtWar(t.player, x.getOwner())) {
        out.add(x);
      }
    }
    return out;
  }

  /**
   * A5: from the hub, every round: the fast program units to Africa (Morocco if allied, else the
   * best North African target) while Italy holds Africa or an African bonus; the rest on the
   * highest-value target of the French coasts, Sardinia, Sicily, Southern Italy with win >= 70. No
   * hold condition; nothing at 70% = the units gather and what is missing is registered.
   */
  static List<Unit> snodo(final SbarcoCoordinato.Turno t) {
    final Config cfg = Config.get();
    t.st.remove("ondata");
    t.st.remove("ondata_africa");
    final Predicate<Unit> prog = t.delProgramma();
    final Predicate<Unit> fast = u -> SnodoAlleati.veloce(u, ConfigR4.get());
    final boolean axisAfrica = asseTieneAfrica(t.data, t.player);
    final Map<String, Integer> bonus = bonusAfricaniAttivi(t.data);
    final boolean africa = africaAperta(axisAfrica, !bonus.isEmpty());
    final JsonArray alt = new JsonArray();
    t.trasportiUsati.clear();
    t.scorteUsate.clear();
    Opzione fastWave = null;
    if (africa) {
      final List<Territory> fastTargets = new ArrayList<>();
      final Territory morocco = t.data.getMap().getTerritoryOrNull("Morocco");
      if (morocco != null && SbarcoCoordinato.dellAlleanza(t.data, t.alliance, morocco)) {
        fastTargets.add(morocco);
      } else {
        fastTargets.addAll(nemiciInGuerra(t, cfg.bersagliAfrica()));
      }
      fastWave = migliore(t, fastTargets, prog.and(fast), alt, "VELOCI_AFRICA");
      if (fastWave != null) {
        fastWave.piano().viaggi().forEach(v -> t.trasportiUsati.add(v.trasporto()));
        // step R6b (B1): the same ships cannot escort two waves
        if (fastWave.protezione() != null) {
          fastWave.protezione().scorta().forEach(u -> t.scorteUsate.add(u.getId().toString()));
        }
      }
    }
    final Set<Unit> fastCargo =
        fastWave == null ? Set.of() : new HashSet<>(fastWave.piano().carico());
    // step R6 (C3): the squadron supports the main wave (the rest)
    t.supportoR6 = SquadraGibilterra.attivo(t.data, t.player);
    final Opzione restWave;
    try {
      restWave =
          migliore(
              t,
              nemiciInGuerra(t, cfg.bersagliResto()),
              prog.and(u -> !fastCargo.contains(u)).and(africa ? fast.negate() : u -> true),
              alt,
              "RESTO");
    } finally {
      t.supportoR6 = false;
    }
    t.trasportiUsati.clear();
    final Optional<String> choice =
        sceltaSnodo(fastWave != null, restWave == null ? null : restWave.t().getName(), cfg);
    final JsonObject par = parametri();
    par.addProperty("africa_aperta", africa);
    par.addProperty("asse_tiene_africa", axisAfrica);
    final JsonObject b = new JsonObject();
    bonus.forEach(b::addProperty);
    par.add("bonus_africani_attivi", b);
    par.addProperty("base", t.base() == null ? "" : t.base().getName());
    par.addProperty("unita_del_programma", SnodoAlleati.programma(t.data, t.player).size());
    String missing = "";
    if (choice.isEmpty()) {
      double closest = -1;
      for (final var e : alt) {
        final JsonObject a = e.getAsJsonObject();
        if (a.has("vittoria_pct") && a.get("vittoria_pct").getAsDouble() > closest) {
          closest = a.get("vittoria_pct").getAsDouble();
          missing =
              a.get("territorio").getAsString()
                  + ": vittoria "
                  + a.get("vittoria_pct").getAsDouble()
                  + "% (serve >= "
                  + cfg.sogliaSbarco()
                  + ")";
        }
      }
      // step R6b: a target given up for its exposed transports says so
      final List<String> exposed = new ArrayList<>();
      for (final var e : alt) {
        final JsonObject a = e.getAsJsonObject();
        if (a.has("trasporti")
            && TrasportiProtetti.RINUNCIA.equals(a.get("trasporti").getAsString())) {
          exposed.add(a.get("territorio").getAsString());
        }
      }
      if (!exposed.isEmpty()) {
        missing = "trasporti esposti dopo lo sbarco (rinuncia: " + String.join(", ", exposed) + ")";
      }
      if (missing.isEmpty()) {
        missing =
            alt.isEmpty()
                ? "nessun bersaglio nemico in guerra"
                : "nessun carico in posizione (unita' del programma o trasporti alla base)";
      }
    }
    par.addProperty("manca", missing);
    final String why =
        choice.isEmpty()
            ? "ACCUMULO, manca: " + missing
            : (fastWave != null ? "veloci su " + fastWave.t().getName() : "")
                + (fastWave != null && restWave != null ? ", " : "")
                + (restWave != null
                    ? "resto su "
                        + restWave.t().getName()
                        + " (valore "
                        + round1(restWave.valore())
                        + ")"
                    : "");
    final String id = RegistroDecisioni.nuovoId(t.st, t.round, t.player.getName(), "snodo_usa");
    RegistroDecisioni.scrivi(
        t.data,
        RegistroDecisioni.decisione(
            id,
            t.round,
            t.player.getName(),
            "snodo_usa",
            alt,
            choice.orElse("accumulo"),
            choice.orElse("ATTESA_ACCUMULO"),
            why,
            par));
    t.lines.add("  R5 snodo USA: " + choice.orElse("ACCUMULO") + " (" + why + ")");
    final List<Unit> out = new ArrayList<>();
    if (restWave != null) {
      t.supportoR6 = SquadraGibilterra.attivo(t.data, t.player);
      final JsonObject w =
          onda(
              t,
              restWave.t(),
              restWave.stima(),
              restWave.piano(),
              africa ? "lenti" : "programma",
              id,
              choice.orElse(""));
      if (restWave.protezione() != null) {
        TrasportiProtetti.salvaInOnda(w, restWave.protezione());
      }
      t.st.add("ondata", w);
      t.supportoR6 = false;
      out.addAll(restWave.piano().tutte());
    }
    if (fastWave != null) {
      final JsonObject w =
          onda(
              t, fastWave.t(), fastWave.stima(), fastWave.piano(), "veloci", id, choice.orElse(""));
      if (fastWave.protezione() != null) {
        TrasportiProtetti.salvaInOnda(w, fastWave.protezione());
      }
      t.st.add(restWave == null ? "ondata" : "ondata_africa", w);
      out.addAll(fastWave.piano().tutte());
    }
    return out;
  }

  /**
   * B3: the United Kingdom's attrition landing of the turn: the program's units on the coastal
   * target of Western Europe with the highest value and win >= 70. No hold condition.
   */
  private static List<Unit> logoramento(final SbarcoCoordinato.Turno t) {
    final Config cfg = Config.get();
    t.st.remove("azione_uk");
    final JsonArray alt = new JsonArray();
    final Opzione best =
        migliore(t, nemiciInGuerra(t, cfg.bersagliLogoramento()), t.delProgramma(), alt, "UK");
    final JsonObject par = parametri();
    par.addProperty("base", t.base() == null ? "" : t.base().getName());
    par.addProperty("unita_del_programma", SnodoAlleati.programma(t.data, t.player).size());
    final String id =
        RegistroDecisioni.nuovoId(t.st, t.round, t.player.getName(), "logoramento_uk");
    RegistroDecisioni.scrivi(
        t.data,
        RegistroDecisioni.decisione(
            id,
            t.round,
            t.player.getName(),
            "logoramento_uk",
            alt,
            best == null ? "accumulo" : best.t().getName(),
            best == null ? "ATTESA_ACCUMULO" : "LOGORAMENTO",
            best == null
                ? "nessun bersaglio costiero con vittoria >= " + cfg.sogliaSbarco()
                : "sbarco di logoramento su "
                    + best.t().getName()
                    + " (vittoria "
                    + round1(best.stima().vittoria())
                    + "%, valore "
                    + round1(best.valore())
                    + ")",
            par));
    t.lines.add(
        "  R5 logoramento UK: " + (best == null ? "accumulo" : "SBARCO su " + best.t().getName()));
    if (best == null) {
      return List.of();
    }
    final JsonObject w = onda(t, best.t(), best.stima(), best.piano(), "programma", id, "UK");
    w.addProperty("tipo", "logoramento");
    w.addProperty("codice", "LOGORAMENTO");
    w.addProperty("tenuta_pct", round1(100 - best.stima().contrattaccoSolo()));
    t.st.add("azione_uk", w);
    return best.piano().tutte();
  }

  /**
   * A3 in the combat move (before the landings and before Hard): the planned battles recomputed on
   * the real board; >= 70% ⇒ the US warships in range move into the zone. Returns the units moved.
   */
  static Set<Unit> battaglia(final SbarcoCoordinato.Turno t, final IMoveDelegate moveDel) {
    final Set<Unit> moved = new LinkedHashSet<>();
    if (!t.st.has("battaglia_r5")) {
      return moved;
    }
    final JsonObject b = t.st.getAsJsonObject("battaglia_r5");
    if (b.get("round").getAsInt() != t.round) {
      return moved;
    }
    for (final String name : new ArrayList<>(b.keySet())) {
      if ("round".equals(name)) {
        continue;
      }
      final Territory z = t.data.getMap().getTerritoryOrNull(name);
      final JsonObject x = b.getAsJsonObject(name);
      if (z == null || navi(z, t.player).isEmpty()) {
        continue;
      }
      final List<Unit> ships = naviInPortata(t.data, t.player, z);
      ships.removeAll(moved);
      final double win = vittoriaBattaglia(t.proData, t.calc, z, ships, t.player);
      x.addProperty("vittoria_reale_pct", round1(win));
      x.addProperty("navi_mandate", 0);
      if (!sbarca(win, Config.get().sogliaBattaglia())) {
        continue;
      }
      final Predicate<Territory> through =
          y ->
              y.isWater()
                  && ProMatches.territoryCanMoveSeaUnitsThrough(t.player, false).test(y)
                  && (y.equals(z) || !y.anyUnitsMatch(Matches.enemyUnit(t.player)));
      int sent = 0;
      final List<String> shipIds = new ArrayList<>();
      for (final Unit u : ships) {
        final Territory from = t.luogoDi(u);
        if (from == null || from.equals(z)) {
          continue;
        }
        final Optional<Route> r =
            t.data.getMap().getRouteForUnits(from, z, through, List.of(u), t.player);
        if (r.isEmpty()) {
          continue;
        }
        final List<Unit> group = new ArrayList<>(List.of(u));
        group.addAll(u.getTransporting());
        if (moveDel.performMove(new MoveDescription(group, r.get())).isEmpty()) {
          moved.addAll(group);
          sent++;
          shipIds.add(u.getId().toString());
        }
      }
      x.addProperty("navi_mandate", sent);
      x.addProperty("eseguita", sent > 0);
      final JsonArray ids = new JsonArray();
      shipIds.forEach(ids::add);
      x.add("navi_ids", ids);
      x.addProperty(
          "tuv_nemico_prima", Math.round(t.c.tuv(z.getMatches(Matches.enemyUnit(t.player)))));
      t.lines.add(
          String.format(
              Locale.ROOT,
              "  R5 BATTAGLIA NAVALE in %s: %d navi USA (vittoria %.1f%%)",
              name,
              sent,
              win));
    }
    SbarcoCoordinato.salva(t.props, t.alliance, t.st);
    return moved;
  }

  /** A3 after the battles: the zone freed or not, the US ships lost (non-combat move). */
  static void esitoBattaglia(final SbarcoCoordinato.Turno t) {
    if (!t.st.has("battaglia_r5")) {
      return;
    }
    final JsonObject b = t.st.getAsJsonObject("battaglia_r5");
    if (b.get("round").getAsInt() != t.round) {
      return;
    }
    final Set<String> onMap = new HashSet<>();
    for (final Territory x : t.data.getMap().getTerritories()) {
      x.getUnitCollection().forEach(u -> onMap.add(u.getId().toString()));
    }
    for (final String name : b.keySet()) {
      if ("round".equals(name)) {
        continue;
      }
      final JsonObject x = b.getAsJsonObject(name);
      if (!x.has("eseguita") || !x.get("eseguita").getAsBoolean() || x.has("esito")) {
        continue;
      }
      final Territory z = t.data.getMap().getTerritoryOrNull(name);
      int lost = 0;
      for (final JsonElement e : x.getAsJsonArray("navi_ids")) {
        lost += onMap.contains(e.getAsString()) ? 0 : 1;
      }
      final JsonObject o = new JsonObject();
      o.addProperty("zona", name);
      o.addProperty("mare_liberato", z != null && navi(z, t.player).isEmpty());
      o.addProperty("navi_usa_perse", lost);
      o.addProperty("vittoria_stimata_pct", x.get("vittoria_pct").getAsDouble());
      x.addProperty("esito", o.get("mare_liberato").getAsBoolean() ? "liberato" : "non liberato");
      RegistroDecisioni.scrivi(
          t.data,
          RegistroDecisioni.esito(
              x.get("id").getAsString(), t.round, t.player.getName(), "battaglia_gibilterra", o));
      t.lines.add(
          "  R5 esito della battaglia in "
              + name
              + ": "
              + x.get("esito").getAsString()
              + ", navi USA perse "
              + lost);
    }
  }

  /**
   * B3: in the United Kingdom's non-combat move, the program's units still at home reinforce the
   * territory a US wave took this round. Returns the units moved.
   */
  static Set<Unit> rinforzoUk(final SbarcoCoordinato.Turno t) {
    final Set<Unit> held = new LinkedHashSet<>();
    if (!t.st.has("ondata")) {
      return held;
    }
    final JsonObject w = t.st.getAsJsonObject("ondata");
    if (w.get("round").getAsInt() != t.round
        || !w.has("eseguito")
        || !w.get("eseguito").getAsBoolean()) {
      return held;
    }
    final Territory target = t.data.getMap().getTerritoryOrNull(w.get("bersaglio").getAsString());
    if (target == null || !SbarcoCoordinato.dellAlleanza(t.data, t.alliance, target)) {
      return held;
    }
    t.filtro = t.delProgramma();
    final SbarcoCoordinato.Turno.Piano plan = t.piano(target, t.player, t.presidioBase());
    final int n = t.esegui(plan, target, held);
    t.filtro = null;
    final JsonArray alt = new JsonArray();
    final JsonObject a = new JsonObject();
    a.addProperty("territorio", target.getName());
    a.addProperty("unita_previste", plan.carico().size());
    a.addProperty("unita_arrivate", n);
    alt.add(a);
    RegistroDecisioni.scrivi(
        t.data,
        RegistroDecisioni.decisione(
            RegistroDecisioni.nuovoId(t.st, t.round, t.player.getName(), "rinforzo_testa_di_ponte"),
            t.round,
            t.player.getName(),
            "rinforzo_testa_di_ponte",
            alt,
            "rinforzo di " + target.getName(),
            n > 0 ? "RINFORZO" : "NIENTE",
            "un'ondata USA ha preso "
                + target.getName()
                + " in questo round: unita' del programma britannico rimaste -> "
                + n,
            parametri()));
    t.lines.add("  R5 rinforzo britannico di " + target.getName() + ": " + n + " unita'");
    return held;
  }

  // ======================================================================= the register

  static void registra(
      final GameState data,
      final GamePlayer player,
      final String type,
      final JsonArray alt,
      final String choice,
      final String code,
      final String why,
      final JsonObject par) {
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
              par));
      SbarcoCoordinato.salva(data.getProperties(), alliance, st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R5, registro " + type + ": " + e);
    }
  }

  static double round1(final double x) {
    return Math.round(x * 10) / 10.0;
  }

  /** The land units of {@code player} by region of the tables (step R5). */
  public static Map<String, Integer> regioni(final GameState data, final GamePlayer player) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    Config.get().regioni().keySet().forEach(k -> out.put(k, 0));
    out.put("altrove", 0);
    out.put("mare", 0);
    for (final Territory t : data.getMap().getTerritories()) {
      final int n =
          t.getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsLand())
                      .and(Matches.unitIsNotInfrastructure()))
              .size();
      if (n == 0) {
        continue;
      }
      String k = t.isWater() ? "mare" : "altrove";
      if (!t.isWater()) {
        for (final var e : Config.get().regioni().entrySet()) {
          if (e.getValue().contains(t.getName())) {
            k = e.getKey();
            break;
          }
        }
      }
      out.merge(k, n, Integer::sum);
    }
    return out;
  }
}
