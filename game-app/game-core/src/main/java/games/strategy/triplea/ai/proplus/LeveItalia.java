package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Step P12: four levers of the strategist's Italy (Axis), each behind its own switch (data {@value
 * #RESOURCE}, default off), on top of the commitment and the siege. All four off: nothing here
 * runs, the Axis plays exactly as before.
 *
 * <ul>
 *   <li>{@link StrategaInterruttori#ITALIA_EST}: in Italy's non-combat move (before Hard's), the
 *       Italian land units outside Africa not needed by the other levers move toward the most
 *       advanced Axis territory with German land units on the way to the siege's current target;
 *   <li>{@link StrategaInterruttori#ITALIA_AFRICA}: the sea task of Egypt: the Italian transports
 *       carry land units to the most advanced Axis territory toward Egypt (or Egypt), the fleet
 *       sails with them and stays in the unload zone; Egypt weighted in Hard's attacks and landed
 *       on from loaded transports next to it when the win beats Hard's minimum;
 *   <li>{@link StrategaInterruttori#CAPITALE_MINIMA}: the capital's garrison is the minimum of land
 *       units that brings the best enemy attack of the next turn under the threshold (none without
 *       a threat); the missing units are bought there first, then brought from nearby, then the
 *       German land units in Italy go there in the German turn;
 *   <li>{@link StrategaInterruttori#ITALIA_GIBILTERRA}: the sea task of Gibraltar: taken from
 *       loaded transports next to it as soon as the win beats Hard's minimum, then held with the
 *       minimum garrison of the same rule; the fleet stays in the strait while it holds.
 * </ul>
 *
 * <p>One balance for Italy: Hard's defense first; the capital's minimum; ONE sea task per turn
 * (Egypt, then Gibraltar, then the more threatened of those already taken); at most one transport
 * (if fewer than the minimum) plus a share of the PUs after the defense in warships, only while the
 * fleet does not hold; Hard buys no other ship; the rest is Hard's land purchase. Register types
 * {@value #TIPO_BILANCIO}, {@value #TIPO_TURNO}, {@value #TIPO_ASSALTO}, {@value #TIPO_GERMANIA}.
 */
public final class LeveItalia {
  private LeveItalia() {}

  public static final String RESOURCE = "proplus/strategia/p12.json";
  public static final String KEY_STATO = "Pro+ stratega p12: ";

  static final String TIPO_BILANCIO = "p12_bilancio";
  static final String TIPO_TURNO = "p12_turno";
  static final String TIPO_ASSALTO = "p12_assalto";
  static final String TIPO_GERMANIA = "p12_germania";

  static final String EGITTO = "EGITTO";
  static final String GIBILTERRA = "GIBILTERRA";

  static final String CAT_ROMA = "p12 capitale";
  static final String CAT_TRASPORTO = "p12 trasporto";
  static final String CAT_NAVI = "p12 navi";

  /** The data file. */
  record Config(
      String nazione,
      String alleato,
      Map<String, Boolean> diDefault,
      String capitale,
      List<String> porti,
      List<String> italia,
      String capitaleRussa,
      List<String> fronteSud,
      String egitto,
      List<String> nordAfrica,
      String gibilterra,
      String zonaStretto,
      double sogliaMinaccia,
      double sogliaFlotta,
      double sogliaDistrutto,
      double quotaNavi,
      int trasportiMinimi,
      double peso,
      int maxGuarnigione,
      String tipoGuarnigione,
      String tipoTrasporto,
      List<String> tipiFlotta) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in = LeveItalia.class.getClassLoader().getResourceAsStream(RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello step P12 non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject o) {
      final JsonObject p = o.getAsJsonObject("parametri");
      final Map<String, Boolean> def = new LinkedHashMap<>();
      v(o, "acceso_di_default")
          .getAsJsonObject()
          .entrySet()
          .forEach(e -> def.put(e.getKey(), e.getValue().getAsBoolean()));
      return new Config(
          v(o, "nazione").getAsString(),
          v(o, "alleato").getAsString(),
          Map.copyOf(def),
          v(o, "capitale").getAsString(),
          lista(v(o, "porti")),
          lista(v(o, "italia")),
          v(o, "capitale_russa").getAsString(),
          lista(v(o, "fronte_sud")),
          v(o, "egitto").getAsString(),
          lista(v(o, "nord_africa")),
          v(o, "gibilterra").getAsString(),
          v(o, "zona_stretto").getAsString(),
          v(p, "soglia_minaccia_pct").getAsDouble(),
          v(p, "soglia_flotta_regge_pct").getAsDouble(),
          v(p, "soglia_convoglio_distrutto_pct").getAsDouble(),
          v(p, "quota_navi_max_pct").getAsDouble(),
          v(p, "trasporti_minimi").getAsInt(),
          v(p, "peso_attacco").getAsDouble(),
          v(p, "max_guarnigione").getAsInt(),
          v(p, "tipo_guarnigione").getAsString(),
          v(p, "tipo_trasporto").getAsString(),
          lista(v(p, "tipi_flotta")));
    }

    boolean diDefault(final StrategaInterruttori s) {
      return diDefault.getOrDefault(s.id(), false);
    }

    private static List<String> lista(final JsonElement e) {
      final List<String> out = new ArrayList<>();
      e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
      return List.copyOf(out);
    }

    private static JsonElement v(final JsonObject o, final String key) {
      final JsonElement e = o.get(key);
      return e.isJsonObject() && e.getAsJsonObject().has("valore")
          ? e.getAsJsonObject().get("valore")
          : e;
    }
  }

  /** The four switches of the step. */
  static final List<StrategaInterruttori> LEVE =
      List.of(
          StrategaInterruttori.ITALIA_EST,
          StrategaInterruttori.ITALIA_AFRICA,
          StrategaInterruttori.CAPITALE_MINIMA,
          StrategaInterruttori.ITALIA_GIBILTERRA);

  // ================================================================================== switches

  private static boolean accesa(
      final GameState data, @Nullable final GamePlayer player, final StrategaInterruttori s) {
    if (player == null || data == null || !Stratega.gioca(data, player)) {
      return false;
    }
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    return alliance.isPresent() && s.acceso(data.getProperties(), alliance.get());
  }

  /** Lever {@code s} is on and {@code player} is the data's Italy. */
  static boolean attiva(
      final GameState data, @Nullable final GamePlayer player, final StrategaInterruttori s) {
    return player != null
        && Config.get().nazione().equals(player.getName())
        && accesa(data, player, s);
  }

  /** Some lever of the step is on for Italy. */
  public static boolean alcuna(final GameState data, @Nullable final GamePlayer player) {
    return LEVE.stream().anyMatch(s -> attiva(data, player, s));
  }

  /** A sea task lever (Egypt or Gibraltar) is on for Italy. */
  public static boolean mare(final GameState data, @Nullable final GamePlayer player) {
    return attiva(data, player, StrategaInterruttori.ITALIA_AFRICA)
        || attiva(data, player, StrategaInterruttori.ITALIA_GIBILTERRA);
  }

  /** The German turn of the capital's lever (German units in Italy go to the capital). */
  public static boolean tedeschiPerRoma(final GameState data, @Nullable final GamePlayer player) {
    return player != null
        && Config.get().alleato().equals(player.getName())
        && accesa(data, player, StrategaInterruttori.CAPITALE_MINIMA);
  }

  // ================================================================================== state

  /** The state of Italy's turn (kept by the tuning; the orders are read on Hard's copy too). */
  public static final class Stato {
    int round = -1;
    String nazione = "";
    @Nullable ProPurchaseOrders ordini;
    boolean soloRoma;
    int mancantiRoma;
    String compito = "";
    int puInizio;
    double quotaNaviPct;
    boolean flottaRegge = true;
    double vittoriaFlotta;
    int trasportiPosseduti;
    @Nullable ProPurchaseOrders.Result risultato;
    final JsonObject acquisto = new JsonObject();

    void pulisci() {
      round = -1;
      nazione = "";
      ordini = null;
      soloRoma = false;
      mancantiRoma = 0;
      compito = "";
      puInizio = 0;
      quotaNaviPct = 0;
      flottaRegge = true;
      vittoriaFlotta = 0;
      trasportiPosseduti = 0;
      risultato = null;
      acquisto.entrySet().clear();
    }

    boolean di(final ProData proData) {
      return proData.getPlayer() != null
          && proData.getPlayer().getName().equals(nazione)
          && proData.getData().getSequence().getRound() == round;
    }
  }

  // ============================================================================ pure rules

  /**
   * The current sea task (pure rule): the first not reached of Egypt (lever C) then Gibraltar
   * (lever E); with both reached, the more threatened (enemy win%); "" with no sea lever.
   */
  static String compitoMare(
      final boolean c,
      final boolean e,
      final boolean egittoAsse,
      final boolean gibilterraAsse,
      final double minacciaEgitto,
      final double minacciaGibilterra) {
    if (c && !egittoAsse) {
      return EGITTO;
    }
    if (e && !gibilterraAsse) {
      return GIBILTERRA;
    }
    if (c && e) {
      return minacciaGibilterra > minacciaEgitto ? GIBILTERRA : EGITTO;
    }
    return c ? EGITTO : e ? GIBILTERRA : "";
  }

  /**
   * The share of warships (pure rule): 0 if the fleet holds; else the PUs the warships needed cost,
   * as a percent of {@code base}, at most {@code max}.
   */
  static double quotaNavi(
      final boolean regge, final int puNecessari, final int base, final double max) {
    if (regge || base <= 0 || puNecessari <= 0) {
      return 0;
    }
    return Math.min(max, 100.0 * puNecessari / base);
  }

  /** One transport if fewer than the minimum are owned (pure rule). */
  static int trasportiDaComprare(final int posseduti, final int minimi) {
    return posseduti < minimi ? 1 : 0;
  }

  /**
   * The smallest k in [0, max] with {@code win(k) < soglia}, win non increasing in k (exponential
   * then binary search); {@code max + 1} if even max is not enough.
   */
  static int minimo(
      final java.util.function.IntToDoubleFunction win, final int max, final double soglia) {
    if (win.applyAsDouble(0) < soglia) {
      return 0;
    }
    int lo = 0;
    int hi = 1;
    while (hi <= max && win.applyAsDouble(hi) >= soglia) {
      lo = hi;
      hi *= 2;
    }
    if (hi > max) {
      if (win.applyAsDouble(max) >= soglia) {
        return max + 1;
      }
      hi = max;
    }
    while (hi - lo > 1) {
      final int mid = (lo + hi) / 2;
      if (win.applyAsDouble(mid) < soglia) {
        hi = mid;
      } else {
        lo = mid;
      }
    }
    return hi;
  }

  // ============================================================================== estimates

  /** The enemy's best attack on {@code t} next turn (Hard's estimate): attackers and bombard. */
  record Minaccia(List<Unit> attaccanti, List<Unit> bombardamento) {
    boolean vuota() {
      return attaccanti.isEmpty();
    }
  }

  static Minaccia minaccia(final ProData proData, final ProOddsCalculator calc, final Territory t) {
    final GamePlayer player = proData.getPlayer();
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateEnemyAttackOptionsOfEveryEnemy(List.of(t), List.of(t));
    final ProTerritory max = tm.getEnemyAttackOptions().getMax(t);
    final List<Unit> att = new ArrayList<>();
    final List<Unit> bombard = new ArrayList<>();
    if (max != null) {
      final Set<Unit> all = new LinkedHashSet<>(max.getMaxUnits());
      all.addAll(max.getMaxAmphibUnits());
      all.stream().filter(u -> player.isAtWar(u.getOwner())).forEach(att::add);
      max.getMaxBombardUnits().stream()
          .filter(u -> player.isAtWar(u.getOwner()))
          .forEach(bombard::add);
    }
    att.sort(Comparator.comparing(u -> u.getId().toString()));
    return new Minaccia(att, bombard);
  }

  static double vittoria(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory t,
      final Minaccia m,
      final List<Unit> def) {
    if (m.vuota()) {
      return 0;
    }
    final List<Unit> d = new ArrayList<>(def);
    d.removeIf(Matches.unitIsInfrastructure());
    if (d.isEmpty()) {
      return 100;
    }
    return calc.calculateBattleResults(proData, t, m.attaccanti(), d, m.bombardamento())
        .getWinPercentage();
  }

  /**
   * The minimum garrison of {@code t}: the units of {@code candidates} (in order) and then extra
   * units of the data's garrison type, added to {@code fixed}, until the enemy's win is under the
   * threshold. {needed among the candidates, extra to buy, win before, win after}.
   */
  record Guarnigione(int richiesta, int extra, double prima, double dopo, boolean minaccia) {
    int totale() {
      return richiesta + extra;
    }
  }

  static Guarnigione guarnigione(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory t,
      final List<Unit> fixed,
      final List<Unit> candidates) {
    final Config cfg = Config.get();
    final Minaccia m = minaccia(proData, calc, t);
    if (m.vuota()) {
      return new Guarnigione(0, 0, 0, 0, false);
    }
    final UnitType type =
        proData.getData().getUnitTypeList().getUnitType(cfg.tipoGuarnigione()).orElse(null);
    final List<Unit> extras =
        type == null ? List.of() : type.createTemp(cfg.maxGuarnigione(), proData.getPlayer());
    final List<Unit> pool = new ArrayList<>(candidates);
    pool.addAll(extras);
    final int max = pool.size();
    final Map<Integer, Double> memo = new LinkedHashMap<>();
    final java.util.function.IntToDoubleFunction win =
        k ->
            memo.computeIfAbsent(
                k,
                kk -> {
                  final List<Unit> def = new ArrayList<>(fixed);
                  def.addAll(pool.subList(0, Math.min(kk, max)));
                  return vittoria(proData, calc, t, m, def);
                });
    final int k = Math.min(minimo(win, max, cfg.sogliaMinaccia()), max);
    final double before = win.applyAsDouble(0);
    final double after = win.applyAsDouble(k);
    final int fromCandidates = Math.min(k, candidates.size());
    return new Guarnigione(fromCandidates, k - fromCandidates, before, after, true);
  }

  // =========================================================================== map helpers

  private static @Nullable Territory terr(final GameState data, final String name) {
    return data.getMap().getTerritoryOrNull(name);
  }

  private static boolean asse(final GamePlayer player, @Nullable final Territory t) {
    return t != null
        && !t.getOwner().isNull()
        && (t.isOwnedBy(player) || player.isAllied(t.getOwner()));
  }

  private static Predicate<Unit> terraMobile(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player)
        .and(Matches.unitIsLand())
        .and(Matches.unitIsNotInfrastructure())
        .and(Matches.unitHasMovementLeft())
        .and(Matches.unitIsBeingTransported().negate());
  }

  /** Defensive strength first (fewest units), then the cheapest, then the id. */
  private static Comparator<Unit> perDifesa(final ProData proData) {
    return Comparator.comparingInt(
            (Unit u) ->
                -u.getUnitAttachment().getDefense(u.getOwner())
                    * Math.max(1, u.getUnitAttachment().getDefenseRolls(u.getOwner())))
        .thenComparingDouble(u -> proData.getUnitValueMap().getInt(u.getType()))
        .thenComparing(u -> u.getId().toString());
  }

  private static Map<Unit, Territory> posizioni(final GameState data) {
    final Map<Unit, Territory> out = new LinkedHashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnits().forEach(u -> out.put(u, t));
    }
    return out;
  }

  /** A land route for {@code u} to {@code to} through own or allied land (non-combat). */
  private static Optional<Route> rottaTerra(
      final GameState data,
      final GamePlayer player,
      final Territory from,
      final Territory to,
      final Unit u) {
    if (from.equals(to)) {
      return Optional.empty();
    }
    final Predicate<Territory> ok =
        x ->
            !x.isWater()
                && asse(player, x)
                && Matches.territoryIsPassableAndNotRestricted(player).test(x);
    return data.getMap().getRouteForUnits(from, to, ok, List.of(u), player);
  }

  /** {@code r} cut to {@code steps} steps (null if 0). */
  private static @Nullable Route taglia(final Route r, final int steps) {
    final int n = Math.min(steps, r.numberOfSteps());
    if (n <= 0) {
      return null;
    }
    return new Route(r.getStart(), r.getSteps().subList(0, n).toArray(new Territory[0]));
  }

  private static int movimento(final Unit u) {
    return u.getMovementLeft().intValue();
  }

  private static int costo(final GameState data, final GamePlayer player, final String type) {
    return Impegno.costiAcquisto(data, player).getOrDefault(type, 0);
  }

  // ============================================================================== purchase

  /**
   * At the start of Italy's purchase, on the real game: the capital's shortfall (lever D), the sea
   * task and the sea spending (levers C, E), the orders for Hard's purchase. Never throws.
   */
  public static void inizioAcquisto(
      final ProData proData, final ProOddsCalculator calc, final Stato st) {
    st.pulisci();
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final Config cfg = Config.get();
    try {
      st.round = data.getSequence().getRound();
      st.nazione = player.getName();
      st.puInizio = player.getResources().getQuantity(games.strategy.triplea.Constants.PUS);
      final List<ProPurchaseOrders.Mandatory> mandatory = new ArrayList<>();
      final List<ProPurchaseOrders.Category> mix = new ArrayList<>();
      final List<String> placement = new ArrayList<>();
      final Territory capital = terr(data, cfg.capitale());
      int mandatoryPu = 0;
      // D: the capital's minimum garrison, the missing units bought there first
      if (attiva(data, player, StrategaInterruttori.CAPITALE_MINIMA)
          && capital != null
          && capital.isOwnedBy(player)) {
        final Guarnigione g = guarnigioneCapitale(proData, calc, capital, Set.of());
        st.mancantiRoma = g.extra();
        st.acquisto.addProperty("roma_minaccia", g.minaccia());
        st.acquisto.addProperty("roma_richiesta", g.totale());
        st.acquisto.addProperty("roma_mancanti", g.extra());
        st.acquisto.addProperty("roma_vittoria_prima", AlleatiR5.round1(g.prima()));
        st.acquisto.addProperty("roma_vittoria_dopo", AlleatiR5.round1(g.dopo()));
        if (g.extra() > 0) {
          mandatory.add(
              new ProPurchaseOrders.Mandatory(
                  CAT_ROMA, List.of(cfg.tipoGuarnigione()), g.extra(), capital.getName()));
          mandatoryPu += g.extra() * costo(data, player, cfg.tipoGuarnigione());
          placement.add(capital.getName());
        }
        salvaMancanti(data, player, g.extra(), st.round);
      }
      // C, E: one sea task, one transport if fewer than the minimum, warships while the fleet
      // does not hold (at most the share of the PUs after the defense)
      if (mare(data, player)) {
        st.compito = compito(proData, calc);
        st.acquisto.addProperty("compito", st.compito);
        final List<Unit> transports = ForzaSbarco.trasporti(data, player);
        st.trasportiPosseduti = transports.size();
        final Territory taskLand = bersaglioCompito(data, player, st.compito);
        final Optional<Territory[]> port = porto(data, player, taskLand);
        final int buyTr = trasportiDaComprare(transports.size(), cfg.trasportiMinimi());
        if (buyTr > 0 && port.isPresent()) {
          mandatory.add(
              new ProPurchaseOrders.Mandatory(
                  CAT_TRASPORTO, List.of(cfg.tipoTrasporto()), 1, port.get()[0].getName()));
          mandatoryPu += costo(data, player, cfg.tipoTrasporto());
        }
        port.ifPresent(p -> placement.add(0, p[1].getName()));
        final Territory zone = zonaCompito(data, player, taskLand, port);
        final int base = Math.max(0, st.puInizio - mandatoryPu);
        int needed = 0;
        if (zone != null) {
          final Minaccia m = minaccia(proData, calc, zone);
          final List<Unit> fleet = flotta(data, player, Set.of());
          final List<Unit> def = new ArrayList<>(fleet);
          def.addAll(transports);
          st.vittoriaFlotta = AlleatiR5.round1(vittoria(proData, calc, zone, m, def));
          st.flottaRegge = st.vittoriaFlotta <= cfg.sogliaFlotta();
          if (!st.flottaRegge && !cfg.tipiFlotta().isEmpty()) {
            final String cheapest =
                cfg.tipiFlotta().stream()
                    .min(Comparator.comparingInt(x -> costo(data, player, x)))
                    .orElseThrow();
            final UnitType t = data.getUnitTypeList().getUnitType(cheapest).orElse(null);
            final int unit = costo(data, player, cheapest);
            final int maxUnits =
                unit <= 0 ? 0 : (int) Math.floor(base * cfg.quotaNavi() / 100 / unit);
            if (t != null && maxUnits > 0) {
              final List<Unit> extra = t.createTemp(maxUnits, player);
              int k = maxUnits;
              for (int i = 1; i <= maxUnits; i++) {
                final List<Unit> d = new ArrayList<>(def);
                d.addAll(extra.subList(0, i));
                if (vittoria(proData, calc, zone, m, d) <= cfg.sogliaFlotta()) {
                  k = i;
                  break;
                }
              }
              needed = k * unit;
            }
          }
          st.acquisto.addProperty("zona_compito", zone.getName());
        }
        st.quotaNaviPct = quotaNavi(st.flottaRegge, needed, base, cfg.quotaNavi());
        if (st.quotaNaviPct > 0) {
          mix.add(new ProPurchaseOrders.Category(CAT_NAVI, st.quotaNaviPct, cfg.tipiFlotta()));
        }
        st.acquisto.addProperty("trasporti_posseduti", transports.size());
        st.acquisto.addProperty("trasporto_ordinato", buyTr > 0 && port.isPresent());
        st.acquisto.addProperty("flotta_regge", st.flottaRegge);
        st.acquisto.addProperty("vittoria_contro_flotta", st.vittoriaFlotta);
        st.acquisto.addProperty("quota_navi_pct", AlleatiR5.round1(st.quotaNaviPct));
        st.acquisto.addProperty("pu_navi_necessari", needed);
      }
      // with a sea lever the step's orders replace the commitment's for Italy even when empty
      if (mandatory.isEmpty() && mix.isEmpty() && !mare(data, player)) {
        return;
      }
      st.soloRoma = !mare(data, player);
      st.ordini =
          new ProPurchaseOrders(
              "P12 Italia" + (st.compito.isEmpty() ? "" : " (" + st.compito + ")"),
              List.copyOf(mix),
              List.copyOf(mandatory),
              List.copyOf(placement),
              List.of(),
              0);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P12, inizio acquisto: " + e);
      st.ordini = null;
    }
  }

  /**
   * The orders for Hard's purchase: with a sea lever the step's orders; with only the capital's
   * lever its mandatory units added to the commitment's orders (if any). Empty: none.
   */
  static Optional<ProPurchaseOrders> ordini(
      final ProData proData, final Stato st, final Supplier<Optional<ProPurchaseOrders>> others) {
    if (st.ordini == null || !st.di(proData)) {
      return Optional.empty();
    }
    if (!st.soloRoma) {
      return Optional.of(st.ordini);
    }
    final Optional<ProPurchaseOrders> o = others.get();
    if (o.isEmpty()) {
      return Optional.of(st.ordini);
    }
    final List<ProPurchaseOrders.Mandatory> m = new ArrayList<>(st.ordini.mandatory());
    m.addAll(o.get().mandatory());
    final List<String> place = new ArrayList<>(st.ordini.preferredPlacement());
    place.addAll(o.get().preferredPlacement());
    return Optional.of(
        new ProPurchaseOrders(
            o.get().label() + " + P12 capitale",
            o.get().mix(),
            List.copyOf(m),
            List.copyOf(place),
            o.get().stagingTerritories(),
            o.get().minimumShare()));
  }

  /** What the step's orders bought (hook of the tuning). */
  static void acquistato(
      final ProPurchaseOrders orders, final ProPurchaseOrders.Result result, final Stato st) {
    if (orders.label().contains("P12")) {
      st.risultato = result;
    }
  }

  /** Hard buys no ship of its own while a sea lever is on (the balance's cap). */
  public static boolean compraNaviHard(final ProData proData) {
    return !mare(proData.getData(), proData.getPlayer());
  }

  /** The register row of the balance after the purchase, on the real game. Never throws. */
  public static void registraAcquisto(
      final GameState data, final GamePlayer player, final Stato st) {
    try {
      if (!alcuna(data, player) || !Config.get().nazione().equals(player.getName())) {
        return;
      }
      final Config cfg = Config.get();
      final JsonObject row = st.acquisto.deepCopy();
      final int now = player.getResources().getQuantity(games.strategy.triplea.Constants.PUS);
      final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
      int sea = 0;
      int transports = 0;
      int land = 0;
      for (final Unit u : player.getUnitCollection()) {
        final int c = costs.getOrDefault(u.getType().getName(), 0);
        if (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)) {
          transports += c;
        } else if (Matches.unitIsSea().test(u)) {
          sea += c;
        } else {
          land += c;
        }
      }
      int roma = 0;
      if (st.risultato != null) {
        roma = st.risultato.spent().getOrDefault(CAT_ROMA, 0);
      }
      row.addProperty("compito", st.compito);
      row.addProperty("pu_inizio", st.puInizio);
      row.addProperty("pu_spesi", Math.max(0, st.puInizio - now));
      row.addProperty("pu_difesa_roma", roma);
      row.addProperty("pu_trasporti", transports);
      row.addProperty("pu_navi", sea);
      row.addProperty("pu_terra", Math.max(0, land - roma));
      row.addProperty("pu_avanzati", now);
      row.addProperty(
          "tetto_mare_pu",
          costs.getOrDefault(cfg.tipoTrasporto(), 0)
              + (int) Math.floor(Math.max(0, st.puInizio) * cfg.quotaNavi() / 100));
      if (mare(data, player)) {
        // the balance's cap applies only with a sea lever (else Hard buys its own ships)
        row.addProperty(
            "mare_entro_tetto",
            sea <= Math.floor(Math.max(0, st.puInizio) * cfg.quotaNavi() / 100) + 0.001
                && transports <= costs.getOrDefault(cfg.tipoTrasporto(), 0));
      }
      registra(
          data,
          player,
          TIPO_BILANCIO,
          "P12_BILANCIO",
          "bilancio unico dell'Italia: difesa di Hard, capitale minima, un compito di mare, il"
              + " resto a terra",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P12, bilancio: " + e);
    }
  }

  // =================================================================== the sea task (C, E)

  /** The current sea task on the board now. */
  static String compito(final ProData proData, final ProOddsCalculator calc) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final Config cfg = Config.get();
    final boolean c = attiva(data, player, StrategaInterruttori.ITALIA_AFRICA);
    final boolean e = attiva(data, player, StrategaInterruttori.ITALIA_GIBILTERRA);
    final Territory egypt = terr(data, cfg.egitto());
    final Territory gib = terr(data, cfg.gibilterra());
    final boolean ea = asse(player, egypt);
    final boolean ga = asse(player, gib);
    double me = 0;
    double mg = 0;
    if (c && e && ea && ga) {
      me = minacciaAttuale(proData, calc, egypt);
      mg = minacciaAttuale(proData, calc, gib);
    }
    return compitoMare(c, e, ea, ga, me, mg);
  }

  private static double minacciaAttuale(
      final ProData proData, final ProOddsCalculator calc, final Territory t) {
    final Minaccia m = minaccia(proData, calc, t);
    return vittoria(
        proData,
        calc,
        t,
        m,
        new ArrayList<>(t.getMatches(Matches.isUnitAllied(proData.getPlayer()))));
  }

  /** The task's land: Egypt or Gibraltar. */
  static @Nullable Territory bersaglioCompito(
      final GameState data, final GamePlayer player, final String task) {
    final Config cfg = Config.get();
    return EGITTO.equals(task)
        ? terr(data, cfg.egitto())
        : GIBILTERRA.equals(task) ? terr(data, cfg.gibilterra()) : null;
  }

  /**
   * Where the convoy unloads: the task's land if it is of the Axis; for Egypt otherwise the Axis
   * territory of North Africa nearest to it by land with a coast; null = nowhere to unload (the
   * convoy goes next to the task's land, loaded, for a landing).
   */
  static @Nullable Territory scarico(
      final GameState data, final GamePlayer player, final String task) {
    final Config cfg = Config.get();
    final Territory target = bersaglioCompito(data, player, task);
    if (target == null) {
      return null;
    }
    if (asse(player, target)) {
      return target;
    }
    if (!EGITTO.equals(task)) {
      return null;
    }
    Territory best = null;
    int bestD = Integer.MAX_VALUE;
    for (final String n : cfg.nordAfrica()) {
      final Territory t = terr(data, n);
      if (t == null
          || !asse(player, t)
          || data.getMap().getNeighbors(t, Matches.territoryIsWater()).isEmpty()) {
        continue;
      }
      final int d = data.getMap().getDistance(t, target, Matches.territoryIsLand());
      if (d >= 0 && d < bestD) {
        best = t;
        bestD = d;
      }
    }
    return best;
  }

  /**
   * The port: {factory territory, its sea zone} of the data's ports owned by Italy, the zone
   * nearest to the task's land.
   */
  static Optional<Territory[]> porto(
      final GameState data, final GamePlayer player, @Nullable final Territory taskLand) {
    Territory[] best = null;
    int bestD = Integer.MAX_VALUE;
    for (final String n : Config.get().porti()) {
      final Territory p = terr(data, n);
      if (p == null || !p.isOwnedBy(player)) {
        continue;
      }
      for (final Territory z : data.getMap().getNeighbors(p, Matches.territoryIsWater())) {
        int d = 0;
        if (taskLand != null) {
          d = Integer.MAX_VALUE;
          for (final Territory w :
              data.getMap().getNeighbors(taskLand, Matches.territoryIsWater())) {
            final int x = data.getMap().getDistance(z, w, Matches.territoryIsWater());
            if (x >= 0) {
              d = Math.min(d, x);
            }
          }
        }
        if (best == null || d < bestD) {
          best = new Territory[] {p, z};
          bestD = d;
        }
      }
    }
    return Optional.ofNullable(best);
  }

  /** The sea zone of the task: next to its unload land (or its land), nearest to the port. */
  static @Nullable Territory zonaCompito(
      final GameState data,
      final GamePlayer player,
      @Nullable final Territory taskLand,
      final Optional<Territory[]> port) {
    if (taskLand == null) {
      return null;
    }
    final String task = taskLand.getName().equals(Config.get().egitto()) ? EGITTO : GIBILTERRA;
    final Territory unload = Optional.ofNullable(scarico(data, player, task)).orElse(taskLand);
    if (GIBILTERRA.equals(task)) {
      final Territory strait = terr(data, Config.get().zonaStretto());
      if (strait != null) {
        return strait;
      }
    }
    Territory best = null;
    int bestD = Integer.MAX_VALUE;
    for (final Territory w : data.getMap().getNeighbors(unload, Matches.territoryIsWater())) {
      final int d =
          port.map(p -> data.getMap().getDistance(p[1], w, Matches.territoryIsWater())).orElse(0);
      final int dd = d < 0 ? 1000 : d;
      if (best == null || dd < bestD) {
        best = w;
        bestD = dd;
      }
    }
    return best;
  }

  /** The Italian warships (no carrier) with movement left, not held. */
  static List<Unit> flotta(final GameState data, final GamePlayer player, final Set<Unit> held) {
    final List<Unit> out = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        out.addAll(
            t.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(AlleatiR5.naveDaGuerra())
                    .and(Matches.unitIsCarrier().negate())
                    .and(u -> !held.contains(u))));
      }
    }
    out.sort(Comparator.comparing(u -> u.getId().toString()));
    return out;
  }

  // ======================================================================= combat (C, E)

  /**
   * In Italy's combat move, before Hard's: the loaded Italian transports that reach a free zone
   * next to the sea task's land, still the enemy's, land there if the win beats Hard's minimum for
   * it; the fleet that reaches the zone goes with them. Returns the units moved. Never throws.
   */
  public static Set<Unit> combattimento(
      final ProData proData, final ProOddsCalculator calc, final IMoveDelegate del) {
    final Set<Unit> moved = new LinkedHashSet<>();
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    try {
      if (!mare(data, player)) {
        return moved;
      }
      final String task = compito(proData, calc);
      final Territory target = bersaglioCompito(data, player, task);
      if (target == null || asse(player, target) || !player.isAtWar(target.getOwner())) {
        return moved;
      }
      final Predicate<Territory> free = SbarcoConcentrato.libera(player);
      final List<Territory> zones = new ArrayList<>();
      for (final Territory z : data.getMap().getNeighbors(target, Matches.territoryIsWater())) {
        if (free.test(z)) {
          zones.add(z);
        }
      }
      if (zones.isEmpty()) {
        return moved;
      }
      final Map<Unit, Territory> where = posizioni(data);
      final Map<Unit, Route> trips = new LinkedHashMap<>();
      final List<Unit> cargo = new ArrayList<>();
      for (final Unit tr : ForzaSbarco.trasporti(data, player)) {
        if (!tr.isTransporting()
            || !tr.getUnloaded().isEmpty()
            || !tr.getTransporting().stream().allMatch(u -> u.isOwnedBy(player))) {
          continue;
        }
        final Territory at = where.get(tr);
        final Route r = rottaMare(data, player, at, zones, tr, free);
        if (r == null && !zones.contains(at)) {
          continue;
        }
        if (r != null && r.numberOfSteps() > movimento(tr)) {
          continue;
        }
        trips.put(tr, r);
        cargo.addAll(tr.getTransporting());
      }
      final JsonObject row = new JsonObject();
      row.addProperty("compito", task);
      row.addProperty("bersaglio", target.getName());
      row.addProperty("trasporti_in_portata", trips.size());
      row.addProperty("unita_a_bordo", cargo.size());
      if (cargo.isEmpty()) {
        return moved;
      }
      final List<Unit> def =
          new ArrayList<>(
              target.getMatches(Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure())));
      final double win =
          def.isEmpty()
              ? 100
              : calc.calculateBattleResults(
                      proData, target, cargo, def, bombardieri(data, player, zones))
                  .getWinPercentage();
      final double min = proData.getAttackMinWinPercentage(target);
      row.addProperty("vittoria", AlleatiR5.round1(win));
      row.addProperty("soglia_minima_hard", AlleatiR5.round1(min));
      row.addProperty("difensori", def.size());
      if (win < min) {
        registra(
            data,
            player,
            TIPO_ASSALTO,
            "P12_ASSALTO_NO",
            "vittoria sotto la soglia minima di Hard: nessuno sbarco",
            row);
        return moved;
      }
      int landed = 0;
      Territory zoneUsed = null;
      for (final var e : trips.entrySet()) {
        final Unit tr = e.getKey();
        final List<Unit> c = new ArrayList<>(tr.getTransporting());
        Territory z = where.get(tr);
        if (e.getValue() != null) {
          final List<Unit> all = new ArrayList<>(c);
          all.add(tr);
          if (del.performMove(new MoveDescription(all, e.getValue())).isPresent()) {
            continue;
          }
          z = e.getValue().getEnd();
        }
        if (del.performMove(new MoveDescription(c, new Route(z, target))).isEmpty()) {
          landed += c.size();
          moved.add(tr);
          moved.addAll(c);
          zoneUsed = z;
        }
      }
      if (zoneUsed != null) {
        moved.addAll(muoviFlotta(data, player, del, zoneUsed, moved, free));
      }
      row.addProperty("unita_sbarcate", landed);
      registra(
          data,
          player,
          TIPO_ASSALTO,
          landed > 0 ? "P12_ASSALTO" : "P12_ASSALTO_NO",
          landed > 0
              ? "sbarco del compito di mare dai trasporti carichi"
              : "mosse rifiutate dal motore",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P12, assalto: " + e);
    }
    return moved;
  }

  /** The Italian warships that can bombard and are already in a landing zone (they stay there). */
  private static List<Unit> bombardieri(
      final GameState data, final GamePlayer player, final List<Territory> zones) {
    final List<Unit> out = new ArrayList<>();
    for (final Territory z : zones) {
      out.addAll(
          z.getMatches(
              Matches.unitIsOwnedBy(player).and(u -> u.getUnitAttachment().getCanBombard(player))));
    }
    return out;
  }

  private static @Nullable Route rottaMare(
      final GameState data,
      final GamePlayer player,
      final Territory from,
      final Collection<Territory> zones,
      final Unit tr,
      final Predicate<Territory> free) {
    Route best = null;
    for (final Territory z : zones) {
      if (z.equals(from)) {
        return null;
      }
      final List<Unit> units = new ArrayList<>(tr.getTransporting());
      units.add(tr);
      final Optional<Route> r = data.getMap().getRouteForUnits(from, z, free, units, player);
      if (r.isPresent() && (best == null || r.get().numberOfSteps() < best.numberOfSteps())) {
        best = r.get();
      }
    }
    return best;
  }

  /** The Italian warships that reach {@code z} through free zones move there; returns them. */
  private static Set<Unit> muoviFlotta(
      final GameState data,
      final GamePlayer player,
      final IMoveDelegate del,
      final Territory z,
      final Set<Unit> held,
      final Predicate<Territory> free) {
    final Set<Unit> out = new LinkedHashSet<>();
    final Map<Unit, Territory> where = posizioni(data);
    for (final Unit s : flotta(data, player, held)) {
      final Territory at = where.get(s);
      if (at == null || movimento(s) <= 0) {
        continue;
      }
      if (at.equals(z)) {
        out.add(s);
        continue;
      }
      final Optional<Route> r = data.getMap().getRouteForUnits(at, z, free, List.of(s), player);
      if (r.isPresent()
          && r.get().numberOfSteps() <= movimento(s)
          && del.performMove(new MoveDescription(List.of(s), r.get())).isEmpty()) {
        out.add(s);
      }
    }
    return out;
  }

  // ============================================================================ non-combat

  /**
   * Italy's non-combat move, before Hard's: D the capital's garrison, then the sea task (C or E),
   * then the east (B). Returns the units Hard must leave where they are. Never throws.
   */
  public static Set<Unit> nonCombattimento(
      final ProData proData, final ProOddsCalculator calc, final IMoveDelegate del) {
    final Set<Unit> held = new LinkedHashSet<>();
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final Config cfg = Config.get();
    final JsonObject row = new JsonObject();
    try {
      final Territory capital = terr(data, cfg.capitale());
      final boolean d = attiva(data, player, StrategaInterruttori.CAPITALE_MINIMA);
      // D: the capital's minimum garrison
      final JsonObject rj = new JsonObject();
      if (d && capital != null && capital.isOwnedBy(player)) {
        held.addAll(capitale(proData, calc, del, capital, rj));
      }
      row.add("roma", rj);
      // C, E: the sea task
      final JsonObject mj = new JsonObject();
      if (mare(data, player)) {
        held.addAll(mareNc(proData, calc, del, held, d ? null : capital, mj));
      }
      row.add("mare", mj);
      // B: the east
      final JsonObject ej = new JsonObject();
      if (attiva(data, player, StrategaInterruttori.ITALIA_EST)) {
        held.addAll(est(data, player, del, held, d ? null : capital, ej));
      }
      row.add("est", ej);
      row.addProperty("unita_roma", rj.has("presente") ? rj.get("presente").getAsInt() : 0);
      row.addProperty("unita_mare", mj.has("caricate") ? mj.get("caricate").getAsInt() : 0);
      row.addProperty("unita_est", ej.has("mosse") ? ej.get("mosse").getAsInt() : 0);
      registra(
          data, player, TIPO_TURNO, "P12_TURNO", "turno dell'Italia: capitale, mare, est", row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P12, non combattimento: " + e);
    }
    return held;
  }

  /** The candidates of the capital: Italian land units there first, then those that reach it. */
  private static Guarnigione guarnigioneCapitale(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory capital,
      final Set<Unit> excluded) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final List<Unit> fixed = new ArrayList<>();
    final List<Unit> inside = new ArrayList<>();
    for (final Unit u : capital.getUnits()) {
      if (!Matches.isUnitAllied(player).test(u) || Matches.unitIsInfrastructure().test(u)) {
        continue;
      }
      if (u.isOwnedBy(player) && Matches.unitIsLand().test(u) && !excluded.contains(u)) {
        inside.add(u);
      } else {
        fixed.add(u);
      }
    }
    inside.sort(perDifesa(proData));
    final List<Unit> near = vicine(data, player, capital, excluded);
    near.sort(perDifesa(proData));
    final List<Unit> candidates = new ArrayList<>(inside);
    candidates.addAll(near);
    return guarnigione(proData, calc, capital, fixed, candidates);
  }

  /** Own land units with movement left that reach {@code t} by land this turn. */
  private static List<Unit> vicine(
      final GameState data, final GamePlayer player, final Territory t, final Set<Unit> excluded) {
    final List<Unit> out = new ArrayList<>();
    for (final Territory n : data.getMap().getNeighbors(t, 2)) {
      if (n.isWater() || n.equals(t) || !asse(player, n)) {
        continue;
      }
      for (final Unit u : n.getMatches(terraMobile(player))) {
        if (excluded.contains(u)) {
          continue;
        }
        final Optional<Route> r = rottaTerra(data, player, n, t, u);
        if (r.isPresent() && r.get().numberOfSteps() <= movimento(u)) {
          out.add(u);
        }
      }
    }
    return out;
  }

  private static Set<Unit> capitale(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate del,
      final Territory capital,
      final JsonObject rj) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final Set<Unit> held = new LinkedHashSet<>();
    final Guarnigione g = guarnigioneCapitale(proData, calc, capital, Set.of());
    rj.addProperty("minaccia", g.minaccia());
    rj.addProperty("richiesta", g.totale());
    rj.addProperty("vittoria_prima", AlleatiR5.round1(g.prima()));
    rj.addProperty("vittoria_dopo", AlleatiR5.round1(g.dopo()));
    final List<Unit> inside = new ArrayList<>();
    for (final Unit u : capital.getUnits()) {
      if (u.isOwnedBy(player)
          && Matches.unitIsLand().test(u)
          && Matches.unitIsNotInfrastructure().test(u)) {
        inside.add(u);
      }
    }
    inside.sort(perDifesa(proData));
    final int need = g.richiesta();
    int brought = 0;
    for (int i = 0; i < Math.min(need, inside.size()); i++) {
      held.add(inside.get(i));
    }
    if (need > inside.size()) {
      final List<Unit> near = vicine(data, player, capital, Set.of());
      near.sort(perDifesa(proData));
      final Map<Unit, Territory> where = posizioni(data);
      for (final Unit u : near) {
        if (held.size() >= need) {
          break;
        }
        final Territory at = where.get(u);
        final Optional<Route> r = rottaTerra(data, player, at, capital, u);
        if (r.isPresent() && del.performMove(new MoveDescription(List.of(u), r.get())).isEmpty()) {
          held.add(u);
          brought++;
        }
      }
    }
    rj.addProperty("portate", brought);
    rj.addProperty("presente", held.size());
    rj.addProperty("mancanti", Math.max(0, g.totale() - held.size()));
    rj.addProperty("unita_in_capitale", inside.size() + brought);
    salvaMancanti(
        data, player, Math.max(0, g.totale() - held.size()), data.getSequence().getRound());
    return held;
  }

  /**
   * The sea task's non-combat move: each transport loads at the port (units not held; without the
   * capital's lever, never from the capital), sails toward the unload zone and unloads there, or
   * goes next to the task's land loaded; it gives up only if the whole convoy would be destroyed in
   * its stop zone without unloading. The fleet sails with them and stays (Gibraltar: in the strait
   * while it holds, else back to the port).
   */
  private static Set<Unit> mareNc(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate del,
      final Set<Unit> already,
      @Nullable final Territory noLoadFrom,
      final JsonObject mj) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final Config cfg = Config.get();
    final Set<Unit> held = new LinkedHashSet<>();
    final String task = compito(proData, calc);
    final Territory target = bersaglioCompito(data, player, task);
    mj.addProperty("compito", task);
    if (target == null) {
      return held;
    }
    final Territory unload = scarico(data, player, task);
    mj.addProperty("scarico", unload == null ? "" : unload.getName());
    final Predicate<Territory> free = SbarcoConcentrato.libera(player);
    final Territory dest = unload == null ? target : unload;
    final List<Territory> zones = new ArrayList<>();
    for (final Territory z : data.getMap().getNeighbors(dest, Matches.territoryIsWater())) {
      if (free.test(z)) {
        zones.add(z);
      }
    }
    mj.addProperty("zone_scarico", zones.size());
    // Gibraltar held: carry only the minimum garrison still missing
    int limit = Integer.MAX_VALUE;
    if (GIBILTERRA.equals(task) && unload != null) {
      final List<Unit> fixed =
          new ArrayList<>(
              target.getMatches(
                  Matches.isUnitAllied(player).and(Matches.unitIsNotInfrastructure())));
      final Guarnigione g = guarnigione(proData, calc, target, fixed, List.of());
      limit = g.extra();
      mj.addProperty("gibilterra_richiesta", g.totale() + fixed.size());
      mj.addProperty("gibilterra_presente", fixed.size());
      mj.addProperty("gibilterra_mancanti", g.extra());
    }
    if (zones.isEmpty()) {
      return held;
    }
    final Optional<Territory[]> port = porto(data, player, target);
    final Map<Unit, Territory> where = posizioni(data);
    int loaded = 0;
    int unloaded = 0;
    int sailed = 0;
    int gaveUp = 0;
    final Map<Territory, Integer> ends = new LinkedHashMap<>();
    for (final Unit tr : ForzaSbarco.trasporti(data, player)) {
      if (!tr.getUnloaded().isEmpty() || already.contains(tr) || movimento(tr) <= 0) {
        if (tr.isTransporting()) {
          held.add(tr);
          held.addAll(tr.getTransporting());
        }
        continue;
      }
      Territory at = posizioni(data).get(tr);
      if (at == null) {
        continue;
      }
      // load at the port if empty: the port with units to load, nearest to the transport and to
      // the task's zones
      final Territory[] pick =
          tr.isTransporting() || limit <= 0
              ? null
              : portoConTruppe(data, player, at, zones, tr, free, already, held, noLoadFrom);
      if (pick != null) {
        final Territory pz = pick[1];
        final Territory pl = pick[0];
        if (!at.equals(pz)) {
          final Optional<Route> r =
              data.getMap().getRouteForUnits(at, pz, free, List.of(tr), player);
          if (r.isEmpty()
              || r.get().numberOfSteps() > movimento(tr)
              || del.performMove(new MoveDescription(List.of(tr), r.get())).isPresent()) {
            continue;
          }
          at = pz;
        }
        final List<Unit> wait =
            new ArrayList<>(pl.getMatches(terraMobile(player).and(Matches.unitCanBeTransported())));
        wait.removeIf(u -> already.contains(u) || held.contains(u));
        if (pl.equals(noLoadFrom)) {
          wait.clear();
        }
        // with the capital's lever the units above its garrison are free: the port's other land
        wait.sort(
            Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
                .thenComparing(u -> u.getId().toString()));
        int room = TransportTracker.getAvailableCapacity(tr);
        for (final Unit u : wait) {
          if (limit <= 0) {
            break;
          }
          final int cost = u.getUnitAttachment().getTransportCost();
          if (cost > room) {
            continue;
          }
          if (del.performMove(new MoveDescription(List.of(u), new Route(pl, pz), Map.of(u, tr)))
              .isEmpty()) {
            room -= cost;
            loaded++;
            limit--;
          }
        }
      }
      if (!tr.isTransporting()) {
        continue;
      }
      final List<Unit> cargo = new ArrayList<>(tr.getTransporting());
      if (!cargo.stream().allMatch(u -> u.isOwnedBy(player))) {
        continue;
      }
      Territory end = at;
      if (!zones.contains(at) && movimento(tr) > 0) {
        final Route r = rottaMare(data, player, at, zones, tr, free);
        if (r != null) {
          final Route go = taglia(r, movimento(tr));
          if (go != null) {
            final boolean arrives = zones.contains(go.getEnd());
            // it gives up only if the whole convoy would be destroyed without unloading
            if (!(arrives && unload != null)
                && distrutto(proData, calc, go.getEnd(), tr, cargo, cfg)) {
              gaveUp++;
              held.add(tr);
              held.addAll(cargo);
              continue;
            }
            final List<Unit> all = new ArrayList<>(cargo);
            all.add(tr);
            if (del.performMove(new MoveDescription(all, go)).isEmpty()) {
              end = go.getEnd();
              sailed++;
            }
          }
        }
      }
      if (zones.contains(end) && unload != null) {
        if (del.performMove(new MoveDescription(cargo, new Route(end, unload))).isEmpty()) {
          unloaded += cargo.size();
        }
      }
      held.add(tr);
      held.addAll(cargo);
      ends.merge(end, 1, Integer::sum);
    }
    // the fleet with the transports (Gibraltar held: the strait while the fleet holds)
    Territory fleetZone =
        ends.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    if (GIBILTERRA.equals(task) && unload != null) {
      final Territory strait = terr(data, cfg.zonaStretto());
      if (strait != null) {
        final Minaccia m = minaccia(proData, calc, strait);
        final List<Unit> fl = flotta(data, player, held);
        final double w = vittoria(proData, calc, strait, m, fl);
        mj.addProperty("vittoria_contro_flotta_stretto", AlleatiR5.round1(w));
        fleetZone = w <= cfg.sogliaFlotta() ? strait : port.map(p -> p[1]).orElse(null);
      }
    }
    if (fleetZone != null) {
      final Set<Unit> f = muoviFlotta(data, player, del, fleetZone, held, free);
      held.addAll(f);
      mj.addProperty("flotta_zona", fleetZone.getName());
      mj.addProperty("flotta_mossa", f.size());
    }
    mj.addProperty("caricate", loaded);
    mj.addProperty("scaricate", unloaded);
    mj.addProperty("trasporti_partiti", sailed);
    mj.addProperty("rinunce", gaveUp);
    return held;
  }

  /**
   * {port land, its sea zone}: a port of the data owned by Italy with Italian land units to load
   * (not held, not from {@code noLoadFrom}), whose zone the transport reaches this turn; the
   * smallest distance transport, zone, task zones. Null if none.
   */
  private static @Nullable Territory[] portoConTruppe(
      final GameState data,
      final GamePlayer player,
      final Territory at,
      final List<Territory> zones,
      final Unit tr,
      final Predicate<Territory> free,
      final Set<Unit> already,
      final Set<Unit> held,
      @Nullable final Territory noLoadFrom) {
    Territory[] best = null;
    int bestD = Integer.MAX_VALUE;
    for (final String n : Config.get().porti()) {
      final Territory pl = terr(data, n);
      if (pl == null || !pl.isOwnedBy(player) || pl.equals(noLoadFrom)) {
        continue;
      }
      final boolean units =
          pl.getMatches(terraMobile(player).and(Matches.unitCanBeTransported())).stream()
              .anyMatch(u -> !already.contains(u) && !held.contains(u));
      if (!units) {
        continue;
      }
      for (final Territory pz : data.getMap().getNeighbors(pl, Matches.territoryIsWater())) {
        if (!free.test(pz)) {
          continue;
        }
        int d1 = 0;
        if (!at.equals(pz)) {
          final Optional<Route> r =
              data.getMap().getRouteForUnits(at, pz, free, List.of(tr), player);
          if (r.isEmpty() || r.get().numberOfSteps() > movimento(tr)) {
            continue;
          }
          d1 = r.get().numberOfSteps();
        }
        int d2 = 1000;
        for (final Territory z : zones) {
          final int x = data.getMap().getDistance(pz, z, Matches.territoryIsWater());
          if (x >= 0) {
            d2 = Math.min(d2, x);
          }
        }
        if (d1 + d2 < bestD) {
          best = new Territory[] {pl, pz};
          bestD = d1 + d2;
        }
      }
    }
    return best;
  }

  /** The whole convoy would be destroyed in {@code z} (enemy win at or above the threshold). */
  private static boolean distrutto(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory z,
      final Unit tr,
      final List<Unit> cargo,
      final Config cfg) {
    final Minaccia m = minaccia(proData, calc, z);
    if (m.vuota()) {
      return false;
    }
    final List<Unit> def =
        new ArrayList<>(flotta(proData.getData(), proData.getPlayer(), Set.of()));
    def.add(tr);
    def.addAll(z.getMatches(Matches.isUnitAllied(proData.getPlayer()).and(Matches.unitIsSea())));
    return vittoria(proData, calc, z, m, def) >= cfg.sogliaDistrutto();
  }

  /**
   * B: the Italian land units outside Africa, with movement left, not held (without the capital's
   * lever, never those in the capital), move toward the Axis territory with German land units
   * nearest by land to the siege's current target.
   */
  private static Set<Unit> est(
      final GameData data,
      final GamePlayer player,
      final IMoveDelegate del,
      final Set<Unit> already,
      @Nullable final Territory noMoveFrom,
      final JsonObject ej) {
    final Set<Unit> held = new LinkedHashSet<>();
    final Config cfg = Config.get();
    final Territory goal = obiettivoAssedio(data, player);
    ej.addProperty("obiettivo", goal == null ? "" : goal.getName());
    if (goal == null) {
      return held;
    }
    final Set<String> africa = new HashSet<>(ConfigR4.get().italiaLivello1());
    africa.addAll(ConfigR4.get().italiaLivello2());
    final GamePlayer ally = data.getPlayerList().getPlayerId(cfg.alleato());
    // the destination: Axis land of the southern front with German land units, nearest by land
    // to the target (none with German units: the nearest Axis land of the front)
    Territory dest = null;
    int bestD = Integer.MAX_VALUE;
    for (final boolean withGermans : List.of(true, false)) {
      for (final String n : cfg.fronteSud()) {
        final Territory t = terr(data, n);
        if (t == null
            || t.isWater()
            || !asse(player, t)
            || (withGermans
                && (ally == null
                    || !t.anyUnitsMatch(
                        Matches.unitIsOwnedBy(ally)
                            .and(Matches.unitIsLand())
                            .and(Matches.unitIsNotInfrastructure()))))) {
          continue;
        }
        final int dd = data.getMap().getDistance(t, goal, Matches.territoryIsLand());
        if (dd >= 0 && dd < bestD) {
          dest = t;
          bestD = dd;
        }
      }
      if (dest != null) {
        ej.addProperty("con_tedeschi", withGermans);
        break;
      }
    }
    ej.addProperty("destinazione", dest == null ? "" : dest.getName());
    if (dest == null) {
      return held;
    }
    int moved = 0;
    int arrived = 0;
    final Map<String, Integer> from = new LinkedHashMap<>();
    for (final Territory t : new ArrayList<>(data.getMap().getTerritories())) {
      if (t.isWater() || t.equals(dest) || africa.contains(t.getName()) || t.equals(noMoveFrom)) {
        continue;
      }
      for (final Unit u : t.getMatches(terraMobile(player))) {
        if (already.contains(u) || held.contains(u)) {
          continue;
        }
        final Optional<Route> r = rottaTerra(data, player, t, dest, u);
        if (r.isEmpty()) {
          continue;
        }
        final Route go = taglia(r.get(), movimento(u));
        if (go != null && del.performMove(new MoveDescription(List.of(u), go)).isEmpty()) {
          held.add(u);
          moved++;
          from.merge(t.getName(), 1, Integer::sum);
          if (go.getEnd().equals(dest)) {
            arrived++;
          }
        }
      }
    }
    final JsonObject fj = new JsonObject();
    from.forEach(fj::addProperty);
    ej.add("da", fj);
    ej.addProperty("mosse", moved);
    ej.addProperty("arrivate", arrived);
    return held;
  }

  /** The siege's current target (German commitment), else the data's Russian capital. */
  static @Nullable Territory obiettivoAssedio(final GameState data, final GamePlayer player) {
    final Config cfg = Config.get();
    try {
      final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
      if (alliance.isPresent()) {
        final JsonObject ns =
            Impegno.nazione(Impegno.stato(data.getProperties(), alliance.get()), cfg.alleato());
        if (ns.has("obiettivo")) {
          final Territory t = terr(data, ns.get("obiettivo").getAsString());
          if (t != null && !asse(player, t)) {
            return t;
          }
        }
      }
    } catch (final RuntimeException e) {
      // no state: the fallback
    }
    final Territory m = terr(data, cfg.capitaleRussa());
    return m != null && !asse(player, m) ? m : null;
  }

  // ======================================================================= German turn (D)

  /**
   * D in the German non-combat move, before Hard's: if Italy's last turn left the capital short,
   * German land units already in the capital stay and those next to it by land (in Italy) go there,
   * up to the shortfall. Never units from elsewhere. Never throws.
   */
  public static Set<Unit> tedeschi(
      final GameData data, final GamePlayer player, final IMoveDelegate del) {
    final Set<Unit> held = new LinkedHashSet<>();
    try {
      final Config cfg = Config.get();
      final Territory capital = terr(data, cfg.capitale());
      if (capital == null || !asse(player, capital)) {
        return held;
      }
      final int missing = mancanti(data, player);
      if (missing <= 0) {
        return held;
      }
      final Predicate<Unit> mine =
          Matches.unitIsOwnedBy(player)
              .and(Matches.unitIsLand())
              .and(Matches.unitIsNotInfrastructure());
      for (final Unit u : capital.getMatches(mine)) {
        if (held.size() >= missing) {
          break;
        }
        held.add(u);
      }
      int moved = 0;
      for (final Territory n : data.getMap().getNeighbors(capital, Matches.territoryIsLand())) {
        if (!cfg.italia().contains(n.getName())) {
          continue;
        }
        for (final Unit u : n.getMatches(mine.and(Matches.unitHasMovementLeft()))) {
          if (held.size() >= missing) {
            break;
          }
          if (del.performMove(new MoveDescription(List.of(u), new Route(n, capital))).isEmpty()) {
            held.add(u);
            moved++;
          }
        }
      }
      final JsonObject row = new JsonObject();
      row.addProperty("mancanti_italia", missing);
      row.addProperty("tedeschi_in_capitale", held.size());
      row.addProperty("tedeschi_mossi", moved);
      registra(
          data,
          player,
          TIPO_GERMANIA,
          "P12_GERMANIA_ROMA",
          "guarnigione della capitale italiana corta: unita' tedesche gia' in Italia verso la capitale",
          row);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P12, tedeschi per la capitale: " + e);
    }
    return held;
  }

  private static void salvaMancanti(
      final GameState data, final GamePlayer player, final int missing, final int round) {
    final GameProperties props = data.getProperties();
    ProPlusSettings.allianceOf(data, player)
        .ifPresent(
            a -> {
              final JsonObject o = new JsonObject();
              o.addProperty("mancanti_roma", missing);
              o.addProperty("round", round);
              props.set(KEY_STATO + a, (Serializable) null);
              props.set(KEY_STATO + a, o.toString());
            });
  }

  private static int mancanti(final GameState data, final GamePlayer player) {
    final GameProperties props = data.getProperties();
    return ProPlusSettings.allianceOf(data, player)
        .map(
            a -> {
              if (props.get(KEY_STATO + a) instanceof String s && !s.isBlank()) {
                try {
                  final JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                  return o.has("mancanti_roma") ? o.get("mancanti_roma").getAsInt() : 0;
                } catch (final RuntimeException e) {
                  return 0;
                }
              }
              return 0;
            })
        .orElse(0);
  }

  // ============================================================================ attack weight

  /** The weight of the sea task's land (still the enemy's) in Hard's attack priority; else 0. */
  static double pesoAttacco(final ProData proData, final Territory target) {
    final GamePlayer player = proData.getPlayer();
    if (player == null || !mare(proData.getData(), player)) {
      return 0;
    }
    final Config cfg = Config.get();
    final GameState data = proData.getData();
    final boolean c = attiva(data, player, StrategaInterruttori.ITALIA_AFRICA);
    final boolean e = attiva(data, player, StrategaInterruttori.ITALIA_GIBILTERRA);
    final String task =
        compitoMare(
            c,
            e,
            asse(player, terr(data, cfg.egitto())),
            asse(player, terr(data, cfg.gibilterra())),
            0,
            0);
    final Territory land = bersaglioCompito(data, player, task);
    return land != null && land.equals(target) && !asse(player, target) ? cfg.peso() : 0;
  }

  // ================================================================================ register

  private static void registra(
      final GameState data,
      final GamePlayer player,
      final String tipo,
      final String codice,
      final String frase,
      final JsonObject parametri) {
    IndicazioniP7.registra(data, player, tipo, codice, frase, parametri);
  }

  /** The register rows of the step of {@code type} (for tests and the autoplay). */
  static List<JsonObject> righe(final GameState data, final String type) {
    final List<JsonObject> out = new ArrayList<>();
    for (final JsonObject o : RegistroDecisioni.righe(data)) {
      if (o.has("tipo") && type.equals(o.get("tipo").getAsString())) {
        out.add(o);
      }
    }
    return out;
  }

  static JsonArray vuoto() {
    return new JsonArray();
  }
}
