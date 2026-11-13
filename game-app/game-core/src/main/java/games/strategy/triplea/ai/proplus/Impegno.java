package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.ai.proplus.strategia.Direttive;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step R2b "IMPEGNO": the first campaign of the "Pro+ Stratega LLM" that ACTS. One nation, by land,
 * one target at a time; no tandem between nations, no landing, no LLM call. Behind the switch
 * {@link StrategaInterruttori#IMPEGNO} (default from {@code impegno.acceso_di_default} in {@code
 * stratega-llm.json}: OFF = today's behavior).
 *
 * <ol>
 *   <li><b>Target</b> ({@link #inizioTurno}, purchase of the nation): among the land candidates of
 *       {@link CampagnaOmbra} (enemy VC or capital, owner already at war with the nation) the one
 *       feasible at margin {@code margine_scelta} with model M1 and fewest stages; if none is
 *       feasible, the smallest gap ({@link #scegli}).
 *   <li><b>Inertia</b> ({@link #motivoFine}): the target changes only when taken, no longer the
 *       enemy's, without a route or with the own capital in case c of rule 2.6 (the last two only
 *       after {@code durata_minima_round}); never because an estimate got worse.
 *   <li><b>Purchases</b>: {@link #puOperazione} of the turn's PUs as mandatory orders ({@link
 *       #ordini}), unit types by the distance from the factory to the next stage, placed in the own
 *       factory nearest to it; the units bought are followed by id ({@link #dopoIlPiazzamento}).
 *   <li><b>Routing</b> ({@link #nonCombattimento}, non-combat only): the operation's land units
 *       Hard did not use in battle go toward the gathering territory, never where the realistic
 *       counterattack takes them.
 *   <li><b>Attacks</b>: next stage and target forced ({@link #forzato}) when the calculator with
 *       every unit in range and the real defenders wins at least {@code soglia_attacco_forzato}.
 *   <li><b>Cover</b>: on the stages taken, the minimal garrison of rule 2.6 case c, nothing more.
 *   <li><b>Opportunism</b>: in the combat move Hard keeps every unit (nothing held or reserved).
 * </ol>
 *
 * <p>State per alliance in {@value #KEY_STATO}; forced targets of a nation in {@value
 * #KEY_FORZATI}; purchase orders of a nation in {@value #KEY_ORDINE}.
 */
public final class Impegno {
  private Impegno() {}

  public static final String KEY_STATO = "Pro+ stratega impegno: ";
  public static final String KEY_FORZATI = "Pro+ stratega impegno forzati: ";
  public static final String KEY_ORDINE = "Pro+ stratega impegno ordine: ";

  /**
   * Step R6 (D): the operation's share of the PUs for one alliance (game property, set by the
   * autoplay's {@code --quota-operazione}); absent = {@code quota_pu_operazione} of the data file.
   */
  public static final String KEY_QUOTA = "Pro+ stratega impegno quota: ";

  static final String PRESO = "preso";
  static final String NON_NEMICO = "non piu' del nemico";
  static final String ROTTA_ASSENTE = "rotta assente";
  static final String CAPITALE_C = "capitale in caso c";
  static final String SENZA_CAMPAGNA = "nazione senza campagne via terra (R2c)";
  static final String COME_HARD = "gioca come Hard";
  static final String PREPARAZIONE = "PREPARAZIONE";
  static final String ESECUZIONE = "ESECUZIONE";

  /** Step R2c (B3): what the commitment's orders really bought, by "nation|round". */
  private static final Map<String, ProPurchaseOrders.Result> ACQUISTI =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** Step R2c (B2): the forced attacks reviewed after Hard's choice, by "nation|round". */
  private static final Map<String, JsonArray> REVISIONI =
      new java.util.concurrent.ConcurrentHashMap<>();

  // ================================================================================ config

  /** Values of {@code impegno} in {@code stratega-llm.json} (all "scelto", none tuned). */
  public record Config(
      boolean accesoDiDefault,
      double quotaPu,
      double sogliaForzato,
      int durataMinima,
      int margineScelta,
      int distanzaVicina,
      List<String> tipiVicino,
      List<String> tipiLontano,
      int maxSeguite) {

    private static @Nullable Config cache;

    public static synchronized Config get() {
      if (cache == null) {
        try (InputStream in =
            Impegno.class.getClassLoader().getResourceAsStream(StrategaConfig.RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + StrategaConfig.RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject()
                      .getAsJsonObject("impegno"));
        } catch (final IOException e) {
          throw new IllegalStateException("File dello stratega non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject o) {
      return new Config(
          v(o, "acceso_di_default").getAsBoolean(),
          v(o, "quota_pu_operazione").getAsDouble(),
          v(o, "soglia_attacco_forzato").getAsDouble(),
          v(o, "durata_minima_round").getAsInt(),
          v(o, "margine_scelta").getAsInt(),
          v(o, "distanza_vicina").getAsInt(),
          strings(v(o, "tipi_vicino").getAsJsonArray()),
          strings(v(o, "tipi_lontano").getAsJsonArray()),
          v(o, "max_unita_seguite").getAsInt());
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

  /** 3: PUs of the operation = share of (turn's PUs - garrison of capital/factories in case c). */
  /** Step R6 (D): the operation's share (percent) of {@code player}'s alliance. */
  static double quotaOperazione(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isPresent()
        && data.getProperties().get(KEY_QUOTA + alliance.get()) instanceof String s
        && !s.isBlank()) {
      try {
        final double v = Double.parseDouble(s.strip());
        if (v >= 0 && v <= 100) {
          return v;
        }
      } catch (final NumberFormatException e) {
        // the data file's value
      }
    }
    return Config.get().quotaPu();
  }

  /** Step R6 (D): sets the operation's share of {@code alliance} (null = the data file's). */
  public static void setQuota(
      final GameProperties props, final String alliance, @Nullable final Double percent) {
    props.set(KEY_QUOTA + alliance, (Serializable) null);
    if (percent != null) {
      if (percent < 0 || percent > 100) {
        throw new IllegalArgumentException("Quota dell'operazione non valida: " + percent);
      }
      props.set(KEY_QUOTA + alliance, String.valueOf(percent));
    }
  }

  static int puOperazione(final int puTurno, final int puCasoC, final double quota) {
    return (int) Math.floor(Math.max(0, puTurno - puCasoC) * quota / 100.0);
  }

  /** 5: a forced attack when the calculator's win with every unit in range is >= the threshold. */
  static boolean forza(final double vittoriaPct, final double soglia) {
    return vittoriaPct >= soglia;
  }

  /** R2c A: the phase of the operation from the calculator's win% on the next stage. */
  static String fase(final double vittoriaTappaPct, final double soglia) {
    return vittoriaTappaPct >= soglia ? ESECUZIONE : PREPARAZIONE;
  }

  /** R2c B4: only a feasible candidate (fewest stages, ties as {@link #scegli}); else none. */
  static Optional<Candidato> scegliFattibile(final List<Candidato> candidates) {
    return scegli(candidates.stream().filter(Candidato::fattibile).toList());
  }

  /**
   * R2c B2: whether a forced attack goes ahead after Hard's choice: the win% with the units sent
   * and then with each extra unit added in turn ({@code conAggiunte}, cumulative). Returns the
   * number of extra units needed (0 = as sent), empty = given up (never an attack left half-done).
   */
  static Optional<Integer> completa(
      final double vittoriaMandate, final List<Double> conAggiunte, final double soglia) {
    if (vittoriaMandate >= soglia) {
      return Optional.of(0);
    }
    for (int i = 0; i < conAggiunte.size(); i++) {
      if (conAggiunte.get(i) >= soglia) {
        return Optional.of(i + 1);
      }
    }
    return Optional.empty();
  }

  /**
   * 2: why the target ends now, or empty (it stays). The only reasons: taken, no longer the
   * enemy's, no route, own capital in case c; the last two only after {@code durataMinima} rounds.
   * No estimate is an input: an estimate that got worse never ends the target.
   */
  static Optional<String> motivoFine(
      final boolean presoDallaNazione,
      final boolean ancoraNemico,
      final boolean rotta,
      final boolean capitaleCasoC,
      final int durata,
      final int durataMinima) {
    if (presoDallaNazione) {
      return Optional.of(PRESO);
    }
    if (!ancoraNemico) {
      return Optional.of(NON_NEMICO);
    }
    if (!rotta && durata >= durataMinima) {
      return Optional.of(ROTTA_ASSENTE);
    }
    if (capitaleCasoC && durata >= durataMinima) {
      return Optional.of(CAPITALE_C);
    }
    return Optional.empty();
  }

  /**
   * 1: one candidate of the rule.
   *
   * @param scarto gap in TUV at the margin, -1 = not estimable (the calculator blocks where the
   *     estimate finds the force enough)
   */
  record Candidato(String territorio, int tappe, boolean fattibile, long scarto, double valore) {}

  /**
   * 1: the rule of the choice: feasible ones with fewest stages; if none is feasible, the smallest
   * gap (not estimable after the estimated ones), then fewest stages; ties by the higher value
   * index, then the name.
   */
  static Optional<Candidato> scegli(final List<Candidato> candidates) {
    final Comparator<Candidato> tie =
        Comparator.comparingDouble((Candidato c) -> -c.valore())
            .thenComparing(Candidato::territorio);
    final Optional<Candidato> feasible =
        candidates.stream()
            .filter(Candidato::fattibile)
            .min(Comparator.comparingInt(Candidato::tappe).thenComparing(tie));
    if (feasible.isPresent()) {
      return feasible;
    }
    return candidates.stream()
        .min(
            Comparator.comparingLong((Candidato c) -> c.scarto() < 0 ? Long.MAX_VALUE : c.scarto())
                .thenComparingInt(Candidato::tappe)
                .thenComparing(tie));
  }

  /** 3: types to buy by the land distance from the factory to the next stage. */
  static List<String> tipi(final int distanza, final Config cfg) {
    return distanza >= 0 && distanza <= cfg.distanzaVicina() ? cfg.tipiVicino() : cfg.tipiLontano();
  }

  /** 3: units of {@code types} in rotation for at most {@code pu} PUs (type → count, in order). */
  static Map<String, Integer> composizione(
      final int pu, final List<String> types, final Map<String, Integer> costs) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    int left = pu;
    int i = 0;
    int failures = 0;
    while (failures < types.size()) {
      final String t = types.get(i % types.size());
      i++;
      final int c = costs.getOrDefault(t, 0);
      if (c <= 0 || c > left) {
        failures++;
        continue;
      }
      failures = 0;
      out.merge(t, 1, Integer::sum);
      left -= c;
    }
    return out;
  }

  // ========================================================================== entry points

  /** The switch is on and the nation plays the step (strategist's nations, not the French). */
  public static boolean attivo(final GameState data, final GamePlayer player) {
    if (player == null || !Stratega.gioca(data, player)) {
      return false;
    }
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    return alliance.isPresent()
        && StrategaInterruttori.IMPEGNO.acceso(data.getProperties(), alliance.get())
        && !StrategaConfig.get().nazioniSenzaRuoli().contains(player.getName())
        // step R6: with the Gibraltar squadron only the USA play the commitment
        && !SquadraGibilterra.esclusa(data, player, alliance.get());
  }

  /**
   * Purchase of a nation of the strategist (real board, {@code proData} initialized): losses of the
   * operation's units in the enemy turns, rule 2.6, the target (choice and inertia), the forced
   * attacks of the turn, the purchase orders. Never throws.
   */
  public static void inizioTurno(
      final ProData proData, final ProOddsCalculator calc, final GamePlayer player) {
    final GameState data = proData.getData();
    // the forced targets and the orders of an earlier turn never leak into this one
    clear(data.getProperties(), KEY_FORZATI + player.getName());
    clear(data.getProperties(), KEY_ORDINE + player.getName());
    if (!attivo(data, player)) {
      return;
    }
    try {
      new Turno(proData, calc, player).inizio();
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Impegno, inizio turno: " + e);
    }
  }

  /** 3: the purchase orders of the nation this round, if any. */
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
      final List<String> factories = new ArrayList<>();
      if (o.has("tipi")) {
        for (final var e : o.getAsJsonObject("tipi").entrySet()) {
          mandatory.add(
              new ProPurchaseOrders.Mandatory(
                  "impegno " + e.getKey(), List.of(e.getKey()), e.getValue().getAsInt()));
        }
        factories.add(o.get("fabbrica").getAsString());
        // step R4 (A2): the other captured factories, nearest to Moscow first
        if (o.has("fabbriche_extra")) {
          o.getAsJsonArray("fabbriche_extra").forEach(e -> factories.add(e.getAsString()));
        }
      }
      // step R4 (B4): the United Kingdom's units for Africa from South Africa
      if (o.has("tipi_sudafrica")) {
        for (final var e : o.getAsJsonObject("tipi_sudafrica").entrySet()) {
          mandatory.add(
              new ProPurchaseOrders.Mandatory(
                  "sudafrica " + e.getKey(),
                  List.of(e.getKey()),
                  e.getValue().getAsInt(),
                  o.get("fabbrica_sudafrica").getAsString()));
        }
      }
      // step R4 (A3): bombers and escorts for the raid on Moscow
      if (o.has("tipi_assedio")) {
        for (final var e : o.getAsJsonObject("tipi_assedio").entrySet()) {
          mandatory.add(
              new ProPurchaseOrders.Mandatory(
                  "assedio " + e.getKey(),
                  List.of(e.getKey()),
                  e.getValue().getAsInt(),
                  o.has("fabbrica_assedio") ? o.get("fabbrica_assedio").getAsString() : null));
        }
      }
      // step R2c: the permanent program (USA, landing force of the United Kingdom)
      if (o.has("tipi_programma")) {
        for (final var e : o.getAsJsonObject("tipi_programma").entrySet()) {
          mandatory.add(
              new ProPurchaseOrders.Mandatory(
                  "programma " + e.getKey(), List.of(e.getKey()), e.getValue().getAsInt()));
        }
        factories.add(o.get("fabbrica_programma").getAsString());
      }
      // step R6b (C3): the shuttle's troops at the second factory
      if (o.has("tipi_programma_extra")) {
        for (final var e : o.getAsJsonObject("tipi_programma_extra").entrySet()) {
          mandatory.add(
              new ProPurchaseOrders.Mandatory(
                  "programma " + e.getKey() + " seconda fabbrica",
                  List.of(e.getKey()),
                  e.getValue().getAsInt(),
                  o.get("fabbrica_programma_extra").getAsString()));
        }
      }
      if (mandatory.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new ProPurchaseOrders(
              "Impegno " + player.getName() + ": " + o.get("obiettivo").getAsString(),
              List.of(),
              List.copyOf(mandatory),
              List.copyOf(factories),
              o.has("raduno") ? List.of(o.get("raduno").getAsString()) : List.of(),
              o.get("quota_minima").getAsDouble()));
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
  }

  /** 5: {@code t} is a forced target of the nation this round. */
  public static boolean forzato(final ProData proData, final Territory t) {
    final GamePlayer player = proData.getPlayer();
    return player != null && forzati(proData.getData(), player).contains(t.getName());
  }

  /** 5: the nation has a forced target this round. */
  public static boolean forzaQualcosa(final ProData proData) {
    final GamePlayer player = proData.getPlayer();
    return player != null && !forzati(proData.getData(), player).isEmpty();
  }

  private static Set<String> forzati(final GameState data, final GamePlayer player) {
    if (!(data.getProperties().get(KEY_FORZATI + player.getName()) instanceof String s)
        || s.isBlank()) {
      return Set.of();
    }
    final int bar = s.indexOf('|');
    if (bar < 0) {
      return Set.of();
    }
    try {
      if (Integer.parseInt(s.substring(0, bar)) != data.getSequence().getRound()) {
        return Set.of();
      }
    } catch (final NumberFormatException e) {
      return Set.of();
    }
    final Set<String> out = new HashSet<>();
    for (final String n : s.substring(bar + 1).split(";")) {
      if (!n.isBlank()) {
        out.add(n);
      }
    }
    return out;
  }

  /** R2c B3: what the commitment's orders bought (from the purchase, real or simulated). */
  public static void acquistato(
      final ProData proData,
      final ProPurchaseOrders orders,
      final ProPurchaseOrders.Result result) {
    final GamePlayer player = proData.getPlayer();
    if (player == null || !orders.label().startsWith("Impegno ")) {
      return;
    }
    ACQUISTI.put(player.getName() + "|" + proData.getData().getSequence().getRound(), result);
  }

  /**
   * R2c B2: after Hard chose its attacks (before the moves), each forced attack is computed again
   * with the units Hard really sends; below {@code soglia_esecuzione} the land units in range not
   * committed to other battles are added (strongest first) until it is reached, otherwise the
   * attack is given up. Never an attack left half-done. Nothing with the switch off.
   */
  public static void rivedi(
      final ProData proData,
      final ProOddsCalculator calc,
      final Map<Territory, ProTerritory> attackMap) {
    final GamePlayer player = proData.getPlayer();
    if (player == null || !attivo(proData.getData(), player) || !forzaQualcosa(proData)) {
      return;
    }
    try {
      final double threshold = Programmi.Config.get().sogliaEsecuzione();
      final Set<Unit> committed = new HashSet<>();
      for (final ProTerritory p : attackMap.values()) {
        committed.addAll(p.getUnits());
        committed.addAll(p.getBombers());
        committed.addAll(p.getAmphibAttackMap().keySet());
        p.getAmphibAttackMap().values().forEach(committed::addAll);
        committed.addAll(p.getBombardTerritoryMap().keySet());
      }
      final JsonArray out = new JsonArray();
      final List<Territory> forced =
          attackMap.keySet().stream()
              .filter(t -> forzato(proData, t))
              .sorted(Comparator.comparing(Territory::getName))
              .toList();
      for (final Territory t : forced) {
        final ProTerritory p = attackMap.get(t);
        final JsonObject x = new JsonObject();
        x.addProperty("territorio", t.getName());
        if (attaccanti(p).isEmpty()) {
          x.addProperty("revisione", "non attaccato da Hard");
          out.add(x);
          continue;
        }
        final double sent = vittoria(proData, calc, p, t);
        x.addProperty("vittoria_mandate_pct", Math.round(sent * 10) / 10.0);
        final List<Unit> extra =
            p.getMaxUnits().stream()
                .filter(Matches.unitIsOwnedBy(player).and(Matches.unitIsLand()))
                .filter(Matches.unitIsNotInfrastructure())
                .filter(u -> !committed.contains(u))
                .sorted(
                    Comparator.comparingInt(
                            (Unit u) -> -u.getUnitAttachment().getAttack(u.getOwner()))
                        .thenComparing(u -> u.getId().toString()))
                .toList();
        final List<Double> wins = new ArrayList<>();
        final List<Unit> added = new ArrayList<>();
        if (sent < threshold) {
          for (final Unit u : extra) {
            p.addUnit(u);
            added.add(u);
            final double w = vittoria(proData, calc, p, t);
            wins.add(w);
            if (w >= threshold) {
              break;
            }
          }
        }
        final Optional<Integer> needed = completa(sent, wins, threshold);
        if (needed.isPresent()) {
          committed.addAll(added);
          x.addProperty("revisione", needed.get() == 0 ? "eseguito" : "completato");
          x.addProperty("aggiunte", needed.get());
          x.addProperty(
              "vittoria_finale_pct",
              Math.round((needed.get() == 0 ? sent : wins.get(needed.get() - 1)) * 10) / 10.0);
        } else {
          p.getUnits().removeAll(added);
          rinuncia(p);
          x.addProperty("revisione", "rinunciato");
          x.addProperty("aggiunte", 0);
        }
        out.add(x);
      }
      REVISIONI.put(player.getName() + "|" + proData.getData().getSequence().getRound(), out);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Impegno, revisione degli attacchi forzati: " + e);
    }
  }

  private static List<Unit> attaccanti(final ProTerritory p) {
    final List<Unit> out = new ArrayList<>(p.getUnits());
    p.getAmphibAttackMap().values().forEach(out::addAll);
    return out;
  }

  private static double vittoria(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritory p,
      final Territory t) {
    final List<Unit> defenders = t.getMatches(Matches.enemyUnit(proData.getPlayer()));
    if (defenders.stream().noneMatch(Matches.unitIsNotInfrastructure())) {
      return 100;
    }
    final List<Unit> bombard = new ArrayList<>();
    p.getBombardTerritoryMap()
        .forEach(
            (u, where) -> {
              if (t.equals(where)) {
                bombard.add(u);
              }
            });
    return calc.calculateBattleResults(proData, t, attaccanti(p), defenders, bombard)
        .getWinPercentage();
  }

  /** R2c B2: the attack on {@code p} given up (no unit of it moves). */
  private static void rinuncia(final ProTerritory p) {
    p.getUnits().clear();
    p.getBombers().clear();
    p.getAmphibAttackMap().clear();
    p.getTransportTerritoryMap().clear();
    p.getBombardTerritoryMap().clear();
  }

  /** 5: the minimum win% Hard keeps for a forced target (the absolute guard rail, 30%). */
  public static double sogliaMinimaForzata() {
    return Direttive.SOGLIA_MINIMA_ASSOLUTA;
  }

  /**
   * Non-combat move (real board, before Hard's): the outcome of the forced attacks and of the
   * stages, the cover of the stages taken (6), the routing toward the gathering territory (4).
   * Returns the units Hard's non-combat move must leave where they are.
   */
  public static Set<Unit> nonCombattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final GamePlayer player) {
    if (!attivo(proData.getData(), player)) {
      return Set.of();
    }
    try {
      return new Turno(proData, calc, player).nonCombattimento(moveDel);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Impegno, non combattimento: " + e);
      return Set.of();
    }
  }

  /** Before the placement: the ids of the nation's units on the map (to find the new ones). */
  public static Set<String> primaDelPiazzamento(final GameState data, final GamePlayer player) {
    if (!attivo(data, player)) {
      return Set.of();
    }
    final Set<String> ids = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnitCollection().stream()
          .filter(Matches.unitIsOwnedBy(player))
          .forEach(u -> ids.add(u.getId().toString()));
    }
    return ids;
  }

  /**
   * After the placement: the units placed for the operation (types and counts of its orders,
   * nearest to its factory first) are followed by id; the operation's units alive at the end of the
   * turn (for the losses in the enemy turns).
   */
  public static void dopoIlPiazzamento(
      final GameState data, final GamePlayer player, final Set<String> before) {
    if (!attivo(data, player)) {
      return;
    }
    try {
      final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
      if (alliance.isEmpty()) {
        return;
      }
      final GameProperties props = data.getProperties();
      final JsonObject st = stato(props, alliance.get());
      final JsonObject ns = nazione(st, player.getName());
      final JsonObject seguite = obj(ns, "unita");
      final int round = data.getSequence().getRound();
      final List<String> lines = new ArrayList<>();
      if (props.get(KEY_ORDINE + player.getName()) instanceof String s && !s.isBlank()) {
        final JsonObject order = JsonParser.parseString(s).getAsJsonObject();
        if (order.get("round").getAsInt() == round) {
          // step R2c (B3): the units the orders really bought and where (not every new unit of
          // the same type: Hard buys infantry too)
          final ProPurchaseOrders.Result bought = ACQUISTI.remove(player.getName() + "|" + round);
          final Territory factory =
              order.has("fabbrica")
                  ? data.getMap().getTerritoryOrNull(order.get("fabbrica").getAsString())
                  : null;
          final List<Unit> fresh = new ArrayList<>();
          for (final Territory t : data.getMap().getTerritories()) {
            t.getUnitCollection().stream()
                .filter(Matches.unitIsOwnedBy(player))
                .filter(u -> !before.contains(u.getId().toString()))
                .forEach(fresh::add);
          }
          final Map<Unit, Territory> where = luoghi(data, fresh);
          final Set<String> placedAt = bought == null ? Set.of() : bought.placements().keySet();
          fresh.sort(
              Comparator.comparingInt(
                      (Unit u) ->
                          where.get(u) != null && placedAt.contains(where.get(u).getName()) ? 0 : 1)
                  .thenComparingInt(
                      (Unit u) ->
                          factory == null || where.get(u) == null
                              ? 99
                              : Math.max(0, data.getMap().getDistance(where.get(u), factory)))
                  .thenComparing(u -> u.getId().toString()));
          final Map<String, Integer> want = new LinkedHashMap<>();
          int programSpent = 0;
          final Map<String, Integer> program = new LinkedHashMap<>();
          if (bought != null) {
            bought
                .units()
                .forEach(
                    (category, m) -> {
                      if (category.startsWith("impegno ")) {
                        m.forEach((type, n) -> want.merge(type, n, Integer::sum));
                      } else if (category.startsWith("programma ")) {
                        m.forEach((type, n) -> program.merge(type, n, Integer::sum));
                      }
                    });
            for (final var e : bought.spent().entrySet()) {
              if (e.getKey().startsWith("programma ")) {
                programSpent += e.getValue();
              }
            }
          } else if (order.has("tipi")) {
            order
                .getAsJsonObject("tipi")
                .entrySet()
                .forEach(e -> want.put(e.getKey(), e.getValue().getAsInt()));
          }
          final Map<String, Integer> costs = costiAcquisto(data, player);
          final Map<String, Integer> taken = new LinkedHashMap<>();
          int spent = 0;
          for (final var e : want.entrySet()) {
            int n = e.getValue();
            for (final Unit u : fresh) {
              if (n <= 0) {
                break;
              }
              if (u.getType().getName().equals(e.getKey()) && !seguite.has(u.getId().toString())) {
                seguite.addProperty(u.getId().toString(), u.getType().getName());
                taken.merge(e.getKey(), 1, Integer::sum);
                spent += costs.getOrDefault(e.getKey(), 0);
                n--;
              }
            }
          }
          // step R4 (A4): the Italian shuttle's troops are the campaign's once in Africa
          if (Assedio.italia(data, player)) {
            for (final var e : program.entrySet()) {
              int n = e.getValue();
              for (final Unit u : fresh) {
                if (n <= 0) {
                  break;
                }
                if (u.getType().getName().equals(e.getKey())
                    && Matches.unitIsLand().test(u)
                    && !seguite.has(u.getId().toString())) {
                  seguite.addProperty(u.getId().toString(), u.getType().getName());
                  n--;
                }
              }
            }
          }
          if (bought != null) {
            Assedio.esitoRinforzo(data, player, bought.placements());
          }
          // step R4 (B3, B4): the landing program's units and South Africa's, by id
          if (bought != null && SnodoAlleati.programmaPerId(data, player)) {
            final Map<String, Integer> south = new LinkedHashMap<>();
            bought
                .units()
                .forEach(
                    (category, m) -> {
                      if (category.startsWith("sudafrica ")) {
                        m.forEach((type, n) -> south.merge(type, n, Integer::sum));
                      }
                    });
            final Set<String> already = new HashSet<>(obj(ns, "programma_ids").keySet());
            segui(obj(ns, "programma_ids"), program, fresh, Set.of());
            // step R6: the program's units bought now join the squadron (or are its escorts)
            final JsonObject now = obj(ns, "programma_ids");
            SquadraGibilterra.dopoIlPiazzamento(
                data,
                player,
                fresh.stream()
                    .filter(
                        u ->
                            now.has(u.getId().toString())
                                && !already.contains(u.getId().toString()))
                    .toList());
            final Territory sa =
                data.getMap().getTerritoryOrNull(ConfigR4.get().fabbricaSudafrica());
            segui(obj(ns, "sudafrica_ids"), south, fresh, sa == null ? Set.of() : Set.of(sa));
          }
          final int planned = order.has("pu_op") ? order.get("pu_op").getAsInt() : 0;
          final JsonObject turn = turno(st, round, player.getName());
          turn.addProperty("pu_op_spesi", spent);
          turn.add("unita_comprate", mappa(taken));
          turn.addProperty("pu_programma_spesi", programSpent);
          turn.add("programma_comprate", mappa(program));
          final List<String> missing = bought == null ? List.of() : bought.mandatoryMissing();
          if (bought != null) {
            turn.addProperty("pu_dopo_difesa_hard", bought.budget());
            final JsonArray m = new JsonArray();
            missing.forEach(m::add);
            turn.add("obbligati_mancanti", m);
          }
          if (planned > 0 || !taken.isEmpty()) {
            lines.add(
                "  Impegno "
                    + player.getName()
                    + ": comprate e piazzate per l'operazione "
                    + (taken.isEmpty() ? "nessuna unita'" : testo(taken))
                    + " ("
                    + spent
                    + " PU di "
                    + planned
                    + " previsti), seguite per id");
          }
          if (!program.isEmpty() || order.has("tipi_programma")) {
            lines.add(
                "  Programma "
                    + player.getName()
                    + ": comprate "
                    + (program.isEmpty() ? "nessuna unita'" : testo(program))
                    + " ("
                    + programSpent
                    + " PU di "
                    + (order.has("pu_programma") ? order.get("pu_programma").getAsInt() : 0)
                    + " previsti)");
          }
          if (!missing.isEmpty()) {
            lines.add(
                "  Ordini non eseguiti: "
                    + String.join("; ", missing)
                    + " - PU rimasti agli ordini dopo la difesa urgente di Hard di capitale e"
                    + " fabbriche: "
                    + bought.budget());
          }
        }
      }
      potaSeguite(seguite, Config.get().maxSeguite());
      // the operation's units alive at the end of the turn
      final JsonArray alive = new JsonArray();
      final Set<String> onMap = idsSullaMappa(data);
      for (final String id : seguite.keySet()) {
        if (onMap.contains(id)) {
          alive.add(id);
        }
      }
      ns.add("vive_fine_turno", alive);
      salva(props, alliance.get(), st);
      diario(data, lines, null);
      SbarcoCoordinato.dopoIlPiazzamento(data, player, before);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Impegno, piazzamento: " + e);
    }
  }

  /** The summary for the strategist's summary file (no unit ids). */
  static JsonObject riepilogo(final GameProperties props, final String alliance) {
    final JsonObject st = stato(props, alliance);
    final JsonObject o = new JsonObject();
    for (final String k : List.of("storia", "turni", "perse_viaggio")) {
      o.add(k, st.has(k) ? st.get(k).deepCopy() : new JsonArray());
    }
    final JsonObject now = new JsonObject();
    if (st.has("nazioni")) {
      for (final var e : st.getAsJsonObject("nazioni").entrySet()) {
        final JsonObject n = e.getValue().getAsJsonObject();
        final JsonObject x = new JsonObject();
        for (final String k : List.of("obiettivo", "dal_round", "motivo_scelta", "tappe_prese")) {
          if (n.has(k)) {
            x.add(k, n.get(k).deepCopy());
          }
        }
        x.addProperty("unita_seguite", n.has("unita") ? n.getAsJsonObject("unita").size() : 0);
        now.add(e.getKey(), x);
      }
    }
    o.add("nazioni", now);
    // step R3a: theater, base, bridgeheads (the decisions are in the register)
    if (props.get(SbarcoCoordinato.KEY_STATO + alliance) != null) {
      o.add("r3a", SbarcoCoordinato.riepilogo(props, alliance));
    }
    return o;
  }

  // ============================================================================ one turn

  /** One phase of one nation's turn. */
  private static final class Turno {
    final ProData proData;
    final ProOddsCalculator calc;
    final GamePlayer player;
    final GameState data;
    final GameProperties props;
    final String alliance;
    final Config cfg = Config.get();
    final Programmi.Config r2c = Programmi.Config.get();
    final int round;
    final CampagnaOmbra.Calcolo c;
    final JsonObject st;
    final JsonObject ns;
    final List<String> lines = new ArrayList<>();
    @Nullable ProTerritoryManager enemy;
    final Map<String, Double> safety = new HashMap<>();

    /** Step R4 (A3): PUs of the share already ordered as bombers this turn. */
    int riservaR4;

    Turno(final ProData proData, final ProOddsCalculator calc, final GamePlayer player) {
      this.proData = proData;
      this.calc = calc;
      this.player = player;
      this.data = proData.getData();
      this.props = data.getProperties();
      this.alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      this.round = data.getSequence().getRound();
      this.c =
          new CampagnaOmbra.Calcolo(proData, calc, player, alliance, CampagnaOmbra.Config.get());
      this.st = stato(props, alliance);
      this.ns = nazione(st, player.getName());
    }

    // -------------------------------------------------------------------------- purchase

    void inizio() {
      lines.add("");
      lines.add("=== R" + round + " " + player.getName() + " - Impegno (step R2c, AGISCE) ===");
      final JsonObject turn = turno(st, round, player.getName());
      perditeInViaggio(turn);
      // rule 2.6 on the own territories with own land units, the capital and the factories
      final Map<Territory, JsonObject> cases = classifica();
      final Territory capital = proData.getMyCapital();
      final boolean capitalC =
          capital != null
              && cases.containsKey(capital)
              && CampagnaOmbra.CASO_C.equals(cases.get(capital).get("caso").getAsString());
      final int puC = puCasoC(cases);
      final int puTurn = player.getResources().getQuantity(Constants.PUS);
      turn.addProperty("pu_turno", puTurn);
      turn.addProperty("pu_caso_c", puC);
      turn.addProperty("capitale_caso_c", capitalC);
      // step R2c: no unit already on the map is routed any more (B1)
      ns.remove("retrovie");
      ns.remove("retrovie_round");
      final JsonArray attacks = new JsonArray();
      final List<String> forced = new ArrayList<>();
      final JsonObject order = new JsonObject();
      order.addProperty("round", round);
      int puOp = 0;
      // step R4 (A3): Moscow's production since the last German turn; bombers from the share
      Assedio.produzioneMoscaEsito(data, player);
      int puAssedio = 0;
      if (Assedio.germania(data, player)) {
        final Map<String, Integer> bombers =
            Assedio.bombardieriDaComprare(
                proData,
                calc,
                player,
                c,
                puOperazione(puTurn, puC, quotaOperazione(data, player)),
                lines);
        if (!bombers.isEmpty()) {
          final Map<String, Integer> costs = costiAcquisto(data, player);
          for (final var e : bombers.entrySet()) {
            puAssedio += costs.getOrDefault(e.getKey(), 0) * e.getValue();
          }
          order.add("tipi_assedio", mappa(bombers));
          final List<Territory> captured = Assedio.fabbricheCatturate(data, player);
          final Territory where = captured.isEmpty() ? proData.getMyCapital() : captured.get(0);
          if (where != null) {
            order.addProperty("fabbrica_assedio", where.getName());
          }
          turn.addProperty("pu_assedio", puAssedio);
        }
      }
      riservaR4 = puAssedio;
      if (campagnaTerra()) {
        puOp = campagna(capitalC, puC, puTurn, turn, attacks, forced, order) + puAssedio;
      } else {
        if (ns.has("obiettivo")) {
          chiudi(
              SENZA_CAMPAGNA, data.getMap().getTerritoryOrNull(ns.get("obiettivo").getAsString()));
        }
        lines.add(
            "  Nessuna campagna via terra per "
                + player.getName()
                + (SbarcoCoordinato.attivo(data, player)
                    ? " (step R3a: solo "
                        + String.join(", ", SbarcoCoordinato.Config.get().nazioniCampagnaTerra())
                    : " (step R2c: solo " + String.join(", ", r2c.nazioniCampagnaTerra()))
                + ")");
        turn.addProperty("obiettivo", "");
        for (final String k : List.of("tappa", "raduno", "base", "fase", "sospeso")) {
          ns.remove(k);
        }
      }
      // C, D: the permanent programs
      int puProg = 0;
      if (Programmi.USA.equals(player.getName()) || Programmi.UK.equals(player.getName())) {
        final Programmi.Turno p = programmi();
        // step R6: the Gibraltar squadron of the USA (no capital garrison in the building rounds)
        final boolean r6 = SquadraGibilterra.attivo(data, player);
        final int garrison =
            r6 && SquadraGibilterra.costruzione(round, SquadraGibilterra.Config.get())
                ? 0
                : presidioPorto(cases, p.porto());
        ns.addProperty("presidio_porto", garrison);
        // step R4 (B4): the United Kingdom's PUs: London, Africa (South Africa), landing force
        final boolean r5 = AlleatiR5.attivo(data, player);
        final int puAfrica =
            r5
                ? AlleatiR5.acquistiSudafrica(
                    data,
                    player,
                    Math.max(0, puTurn - puC - puOp),
                    order,
                    lines,
                    casoC(cases, "Egypt"))
                : SnodoAlleati.acquistiSudafrica(
                    data, player, Math.max(0, puTurn - puC - puOp), order, lines);
        puProg += puAfrica;
        final double usaShare = r5 ? AlleatiR5.Config.get().quota() : r2c.quotaProgrammaUsa();
        final double ukShare = r5 ? AlleatiR5.Config.get().quota() : r2c.quotaForzaSbarcoUk();
        final int pu =
            r6
                ? SquadraGibilterra.pu(data, puTurn, puC)
                : Programmi.USA.equals(player.getName())
                    ? puOperazione(puTurn, puC, usaShare)
                    : puOperazione(puTurn, puC + puOp + puAfrica, ukShare);
        // step R5 (A1, B1-B2): escorts for A3, the defended port's zone of the United Kingdom
        final Map<String, Integer> mix =
            r6
                ? SquadraGibilterra.acquisti(proData, calc, p, pu, puTurn, puC, turn, ns)
                : r5
                    ? AlleatiR5.acquistiProgramma(proData, calc, p, pu, garrison, turn)
                    : p.acquisti(pu, garrison, turn);
        SbarcoCoordinato.registraProgramma(
            data, player, turn.has("programma") ? turn.getAsJsonObject("programma") : null);
        if (!mix.isEmpty() && p.porto() != null) {
          // step R6b (C3): the shuttle's troops that do not fit at the port go to the second
          // factory (next to the port's zone: they load from there)
          final Territory second =
              r6 && !SquadraGibilterra.costruzione(round, SquadraGibilterra.Config.get())
                  ? TrasportiProtetti.secondaFabbrica(p)
                  : null;
          final Map<String, Integer> extra = new LinkedHashMap<>();
          if (second != null) {
            final Set<String> land = new HashSet<>();
            data.getUnitTypeList()
                .getAllUnitTypes()
                .forEach(
                    ut -> {
                      if (!ut.getUnitAttachment().isSea() && !ut.getUnitAttachment().isAir()) {
                        land.add(ut.getName());
                      }
                    });
            extra.putAll(TrasportiProtetti.eccedenza(mix, Assedio.capacita(p.porto()), land));
          }
          final Map<String, Integer> atPort = new LinkedHashMap<>(mix);
          extra.forEach((k, v) -> atPort.merge(k, -v, Integer::sum));
          atPort.values().removeIf(v -> v <= 0);
          order.add("tipi_programma", mappa(atPort));
          if (!extra.isEmpty()) {
            order.add("tipi_programma_extra", mappa(extra));
            order.addProperty("fabbrica_programma_extra", second.getName());
          }
          order.addProperty("fabbrica_programma", p.porto().getName());
          final Map<String, Integer> costs = costiAcquisto(data, player);
          for (final var e : mix.entrySet()) {
            puProg += costs.getOrDefault(e.getKey(), 0) * e.getValue();
          }
        }
        forced.addAll(p.forzatiMedioOriente(attacks));
      }
      // step R4 (A4): Italy's share to the Africa shuttle or to warships
      if (Assedio.italia(data, player)) {
        final Programmi.Turno p = programmi();
        final int garrison = presidioPorto(cases, p.porto());
        ns.addProperty("presidio_porto", garrison);
        puProg +=
            Assedio.acquistiItalia(
                data,
                player,
                Math.max(0, puOperazione(puTurn, puC, quotaOperazione(data, player)) - puOp),
                p,
                garrison,
                turn,
                order,
                lines);
      }
      // step R3a: theater, preparation, green light, the United Kingdom's action, transport cap
      SbarcoCoordinato.inizio(proData, calc, player, c, lines);
      turn.add("attacchi", attacks);
      clear(props, KEY_FORZATI + player.getName());
      if (!forced.isEmpty()) {
        props.set(KEY_FORZATI + player.getName(), round + "|" + String.join(";", forced));
      }
      clear(props, KEY_ORDINE + player.getName());
      if ((order.has("tipi")
              || order.has("tipi_programma")
              || order.has("tipi_assedio")
              || order.has("tipi_sudafrica"))
          && puTurn > 0) {
        order.addProperty("pu_programma", puProg);
        order.addProperty("quota_minima", Math.min(100, 100.0 * (puOp + puProg) / puTurn));
        if (!order.has("obiettivo")) {
          order.addProperty("obiettivo", "programma");
        }
        props.set(KEY_ORDINE + player.getName(), order.toString());
      }
      salvaETermina(turn);
    }

    /** The nation may have a land campaign (R2c B4; with step R3a its own list: no Russia). */
    private boolean campagnaTerra() {
      return SbarcoCoordinato.attivo(data, player)
          ? SbarcoCoordinato.Config.get().nazioniCampagnaTerra().contains(player.getName())
          : r2c.campagnaTerra(player.getName());
    }

    private Programmi.Turno programmi() {
      return new Programmi.Turno(proData, calc, player, alliance, c, lines);
    }

    /** Step R5 (B5): the territory {@code name} is own and in case c. */
    private static boolean casoC(final Map<Territory, JsonObject> cases, final String name) {
      for (final var e : cases.entrySet()) {
        if (e.getKey().getName().equals(name)) {
          return CampagnaOmbra.CASO_C.equals(e.getValue().get("caso").getAsString());
        }
      }
      return false;
    }

    /** The minimal garrison (units) of case c in the program's port, 0 if not in case c. */
    private static int presidioPorto(
        final Map<Territory, JsonObject> cases, @Nullable final Territory port) {
      if (port == null || !cases.containsKey(port)) {
        return 0;
      }
      final JsonObject x = cases.get(port);
      return CampagnaOmbra.CASO_C.equals(x.get("caso").getAsString())
          ? x.get("unita").getAsInt()
          : 0;
    }

    /**
     * The land campaign of the turn (R2b with the two phases of R2c). Returns the PUs of its orders
     * (0 without a target, suspended or without a factory).
     */
    private int campagna(
        final boolean capitalC,
        final int puC,
        final int puTurn,
        final JsonObject turn,
        final JsonArray attacks,
        final List<String> forced,
        final JsonObject order) {
      final int puOp =
          Math.max(0, puOperazione(puTurn, puC, quotaOperazione(data, player)) - riservaR4);
      // 2: inertia of the current target
      final List<Territory> fronts = c.fronti();
      String suspended = "";
      boolean chosenNow = false;
      Optional<CampagnaOmbra.Calcolo.Obiettivo> route = Optional.empty();
      if (ns.has("obiettivo")) {
        final Territory target =
            data.getMap().getTerritoryOrNull(ns.get("obiettivo").getAsString());
        final int since = ns.get("dal_round").getAsInt();
        route = target == null ? Optional.empty() : c.obiettivoPer(target, fronts);
        final boolean taken = target != null && target.isOwnedBy(player);
        final boolean enemyStill =
            target != null
                && c.nemico(target.getOwner())
                && data.getRelationshipTracker().isAtWar(player, target.getOwner());
        final Optional<String> end =
            motivoFine(
                taken, enemyStill, route.isPresent(), capitalC, round - since, cfg.durataMinima());
        if (end.isEmpty()
            && Assedio.moscaOra(proData, calc, player, c, ns.get("obiettivo").getAsString())) {
          // step R4 (A1): Moscow at the threshold this turn replaces the current target
          chiudi(
              "Mosca vinta al " + Math.round(ConfigR4.get().sogliaMosca()) + "% nel turno (R4)",
              target);
          route = Optional.empty();
        } else if (end.isPresent()) {
          chiudi(end.get(), target);
          route = Optional.empty();
        } else if (route.isEmpty()) {
          suspended =
              ROTTA_ASSENTE + " (durata " + (round - since) + " < " + cfg.durataMinima() + ")";
        } else if (capitalC) {
          lines.add(
              "  Capitale in caso c, durata "
                  + (round - since)
                  + " < "
                  + cfg.durataMinima()
                  + ": obiettivo invariato, prima il presidio della capitale");
        }
      }
      // 1: a new target (never while the own capital is in case c)
      if (!ns.has("obiettivo")) {
        if (capitalC) {
          lines.add("  Nessun obiettivo: capitale propria in caso c (regola 2.6)");
        } else {
          route = scegliObiettivo(puTurn, puC);
          chosenNow = route.isPresent();
        }
      }
      // B4: a nation that keeps only feasible targets (the Russians) plays as Hard without one
      if (suspended.isEmpty()
          && !chosenNow
          && route.isPresent()
          && r2c.nazioniSoloFattibili().contains(player.getName())) {
        final JsonObject m = c.valutaImpegno(route.get(), cfg.margineScelta(), puOp, "m1");
        if (!m.get("fattibile").getAsBoolean()) {
          suspended = COME_HARD + " (non fattibile a margine " + cfg.margineScelta() + ")";
        }
      }
      turn.addProperty("obiettivo", ns.has("obiettivo") ? ns.get("obiettivo").getAsString() : "");
      if (!suspended.isEmpty()) {
        turn.addProperty("sospeso", suspended);
        lines.add("  Operazione SOSPESA nel turno: " + suspended + " (obiettivo invariato)");
      }
      ns.addProperty("sospeso", suspended);
      if (route.isEmpty() || !suspended.isEmpty() || !ns.has("obiettivo")) {
        for (final String k : List.of("tappa", "raduno", "base", "fase")) {
          ns.remove(k);
        }
        return 0;
      }
      final CampagnaOmbra.Calcolo.Obiettivo o = route.get();
      final Territory stage = prossimaTappa(o);
      final Territory gather = raduno(o, stage);
      ns.addProperty("tappa", stage.getName());
      ns.addProperty("raduno", gather.getName());
      turn.addProperty("tappa", stage.getName());
      turn.addProperty("raduno", gather.getName());
      // A: the phase from the calculator on the next stage with the real forces
      final JsonArray here = valutaAttacchi(o, stage);
      double stageWin = 0;
      for (final JsonElement e : here) {
        if (e.getAsJsonObject().get("territorio").getAsString().equals(stage.getName())) {
          stageWin = e.getAsJsonObject().get("vittoria_pct").getAsDouble();
        }
      }
      final String phase = fase(stageWin, r2c.sogliaEsecuzione());
      ns.addProperty("fase", phase);
      turn.addProperty("fase", phase);
      for (final JsonElement e : here) {
        final JsonObject x = e.getAsJsonObject();
        final boolean force =
            ESECUZIONE.equals(phase)
                && forza(x.get("vittoria_pct").getAsDouble(), cfg.sogliaForzato());
        x.addProperty("forzato", force);
        x.addProperty("fase", phase);
        if (force) {
          forced.add(x.get("territorio").getAsString());
        }
        attacks.add(x);
        lines.add(
            String.format(
                Locale.ROOT,
                "  Attacco su %s con tutte le unita' in portata (%d TUV): vittoria %.1f%%,"
                    + " perdite P80 %.0f TUV -> %s",
                x.get("territorio").getAsString(),
                x.get("attaccanti_tuv").getAsLong(),
                x.get("vittoria_pct").getAsDouble(),
                x.get("perdite_p80_tuv").getAsDouble(),
                force
                    ? "FORZATO (>= " + Math.round(cfg.sogliaForzato()) + "%)"
                    : PREPARAZIONE.equals(phase)
                        ? "PREPARAZIONE: nessun attacco forzato, decide Hard"
                        : "sotto soglia: decide Hard"));
      }
      // A: in preparation the units bought gather in a safe base on the route
      String baseText = "-";
      if (PREPARAZIONE.equals(phase)) {
        final Optional<Territory> base = baseSicura(o, stage, fabbricaVicina(stage));
        if (base.isPresent()) {
          ns.addProperty("base", base.get().getName());
          turn.addProperty("base", base.get().getName());
          baseText = base.get().getName();
        } else {
          ns.remove("base");
          baseText = "nessuna sicura (le unita' comprate restano dove piazzate)";
        }
      } else {
        ns.remove("base");
      }
      lines.add(
          String.format(
              Locale.ROOT,
              "  Obiettivo %s (dal R%d, %s); rotta %s; prossima tappa %s (vittoria %.1f%%) -> FASE"
                  + " %s; %s",
              o.bersaglio().getName(),
              ns.get("dal_round").getAsInt(),
              ns.has("motivo_scelta") ? ns.get("motivo_scelta").getAsString() : "-",
              String.join(" > ", o.percorso().stream().map(Territory::getName).toList()),
              stage.getName(),
              stageWin,
              phase,
              ESECUZIONE.equals(phase)
                  ? "raduno " + gather.getName() + " e attacco forzato"
                  : "base sicura " + baseText + ", nessun attacco forzato"));
      // 3: purchase orders
      return ordineAcquisto(o, stage, gather, puTurn, puC, puOp, turn, order);
    }

    /** 3: the own factory nearest by land to {@code stage} (ties by name), if any. */
    @Nullable
    private Territory fabbricaVicina(final Territory stage) {
      Territory factory = null;
      int best = Integer.MAX_VALUE;
      for (final Territory t : data.getMap().getTerritories()) {
        if (t.isWater()
            || !t.isOwnedBy(player)
            || !t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
          continue;
        }
        final int d = data.getMap().getDistance(t, stage, Matches.territoryIsLand());
        if (d >= 0 && (d < best || (d == best && t.getName().compareTo(factory.getName()) < 0))) {
          best = d;
          factory = t;
        }
      }
      return factory;
    }

    /**
     * A: the own territory on the route nearest to the stage where the realistic counterattack
     * against the allied units there wins at most {@code soglia_base_sicura}.
     */
    private Optional<Territory> baseSicura(
        final CampagnaOmbra.Calcolo.Obiettivo o,
        final Territory stage,
        @Nullable final Territory factory) {
      final List<Territory> line = new ArrayList<>();
      // the friendly land from the factory where the units are bought to the route's start
      if (factory != null && !factory.equals(o.partenza())) {
        line.add(factory);
        data.getMap()
            .getRoute(
                factory,
                o.partenza(),
                x -> !x.isWater() && Matches.isTerritoryAllied(player).test(x))
            .ifPresent(
                r -> r.getSteps().stream().filter(x -> !line.contains(x)).forEach(line::add));
      }
      if (!line.contains(o.partenza())) {
        line.add(o.partenza());
      }
      for (final Territory t : o.percorso()) {
        if (t.equals(stage)) {
          break;
        }
        line.add(t);
      }
      for (int i = line.size() - 1; i >= 0; i--) {
        final Territory t = line.get(i);
        if (t.isWater() || !t.isOwnedBy(player)) {
          continue;
        }
        final List<Unit> counter = c.contrattacco(t);
        if (counter.isEmpty()) {
          return Optional.of(t);
        }
        final List<Unit> defenders = t.getMatches(Matches.isUnitAllied(player));
        if (defenders.isEmpty()) {
          continue;
        }
        final double enemyWin =
            calc.calculateBattleResults(proData, t, counter, defenders, List.of())
                .getWinPercentage();
        if (enemyWin <= r2c.sogliaBaseSicura()) {
          return Optional.of(t);
        }
      }
      return Optional.empty();
    }

    private void salvaETermina(final JsonObject turn) {
      salva(props, alliance, st);
      final JsonObject rec = new JsonObject();
      rec.addProperty("tipo", "impegno");
      rec.addProperty("round", round);
      rec.add("turno", turn.deepCopy());
      diario(data, lines, rec);
    }

    /** The operation's units lost in the enemy turns (gathering or on the way). */
    private void perditeInViaggio(final JsonObject turn) {
      final JsonObject seguite = obj(ns, "unita");
      final Set<String> onMap = idsSullaMappa(data);
      final Set<String> aliveAtEnd = new HashSet<>();
      if (ns.has("vive_fine_turno")) {
        ns.getAsJsonArray("vive_fine_turno").forEach(e -> aliveAtEnd.add(e.getAsString()));
      }
      int n = 0;
      int tuv = 0;
      final Map<String, Integer> costs = costiAcquisto(data, player);
      for (final String id : new ArrayList<>(seguite.keySet())) {
        if (onMap.contains(id)) {
          continue;
        }
        if (aliveAtEnd.contains(id)) {
          n++;
          tuv += costs.getOrDefault(seguite.get(id).getAsString(), 0);
        }
        seguite.remove(id);
      }
      ns.remove("vive_fine_turno");
      turn.addProperty("perse_viaggio", n);
      turn.addProperty("perse_viaggio_tuv", tuv);
      if (n > 0) {
        final JsonObject x = new JsonObject();
        x.addProperty("round", round);
        x.addProperty("nazione", player.getName());
        x.addProperty("unita", n);
        x.addProperty("tuv", tuv);
        arr(st, "perse_viaggio").add(x);
        lines.add(
            "  Unita' dell'operazione perse nei turni nemici (raduno o viaggio): "
                + n
                + " ("
                + tuv
                + " TUV)");
      }
    }

    /** Rule 2.6 for the own land with own land units, the capital and the own factories. */
    private Map<Territory, JsonObject> classifica() {
      final List<Territory> own = new ArrayList<>();
      for (final Territory t : data.getMap().getTerritories()) {
        if (t.isWater() || !t.isOwnedBy(player)) {
          continue;
        }
        if (t.anyUnitsMatch(terraPropria())
            || t.equals(proData.getMyCapital())
            || t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
          own.add(t);
        }
      }
      final Map<Territory, JsonObject> out = new LinkedHashMap<>();
      if (own.isEmpty()) {
        return out;
      }
      final ProTerritoryManager tm = enemyOptions(own);
      for (final Territory t : own) {
        final JsonObject x = c.classifica(t, enemyLand(tm, t), false, 0, 0, 0);
        out.put(t, x);
      }
      return out;
    }

    private ProTerritoryManager enemyOptions(final Collection<Territory> check) {
      final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
      tm.populateEnemyAttackOptions(List.of(), new ArrayList<>(check));
      return tm;
    }

    /** The enemy land units (landings included) that can reach {@code t} (as rule 2.6 reads). */
    private static List<Unit> enemyLand(final ProTerritoryManager tm, final Territory t) {
      final ProTerritory max = tm.getEnemyAttackOptions().getMax(t);
      final List<Unit> out = new ArrayList<>();
      if (max != null) {
        max.getMaxUnits().stream()
            .filter(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()))
            .forEach(out::add);
        max.getMaxAmphibUnits().stream()
            .filter(Matches.unitIsLand().and(u -> !out.contains(u)))
            .forEach(out::add);
      }
      return out;
    }

    /**
     * 3: PUs of the minimal garrison of rule 2.6 case c still missing in the capital and the own
     * factories (the own land units there count first).
     */
    private int puCasoC(final Map<Territory, JsonObject> cases) {
      final Map<String, Integer> costs = costiAcquisto(data, player);
      final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
      final List<Territory> critical = new ArrayList<>();
      for (final var e : cases.entrySet()) {
        final Territory t = e.getKey();
        final boolean key =
            t.equals(proData.getMyCapital())
                || t.anyUnitsMatch(Matches.unitCanProduceUnits())
                || r2c.presidiPrioritari()
                    .getOrDefault(player.getName(), List.of())
                    .contains(t.getName());
        if (key && CampagnaOmbra.CASO_C.equals(e.getValue().get("caso").getAsString())) {
          critical.add(t);
        }
      }
      if (critical.isEmpty()) {
        return 0;
      }
      tm.populateEnemyAttackOptions(List.of(), critical);
      int pu = 0;
      for (final Territory t : critical) {
        final List<String> garrison = c.presidioMinimo(t, enemyLand(tm, t));
        final int own = t.getMatches(terraPropria()).size();
        for (int i = own; i < garrison.size(); i++) {
          pu += costs.getOrDefault(garrison.get(i), 3);
        }
      }
      return pu;
    }

    /** Closes the current target with its reason (history). */
    private void chiudi(final String why, @Nullable final Territory target) {
      final JsonObject h = new JsonObject();
      h.addProperty("nazione", player.getName());
      h.addProperty("obiettivo", ns.get("obiettivo").getAsString());
      h.addProperty("dal_round", ns.get("dal_round").getAsInt());
      h.addProperty("al_round", round);
      h.addProperty("durata", round - ns.get("dal_round").getAsInt());
      h.addProperty("motivo_fine", why);
      if (ns.has("preso_round")) {
        h.addProperty("preso_round", ns.get("preso_round").getAsInt());
      } else if (PRESO.equals(why)) {
        h.addProperty("preso_round", round - 1);
      }
      if (ns.has("motivo_scelta")) {
        h.addProperty("motivo_scelta", ns.get("motivo_scelta").getAsString());
      }
      arr(st, "storia").add(h);
      lines.add(
          "  Obiettivo "
              + ns.get("obiettivo").getAsString()
              + " CHIUSO: "
              + why
              + " (dal R"
              + ns.get("dal_round").getAsInt()
              + ", "
              + (round - ns.get("dal_round").getAsInt())
              + " round)"
              + (target != null ? ", proprietario ora " + target.getOwner().getName() : ""));
      for (final String k :
          List.of("obiettivo", "dal_round", "motivo_scelta", "preso_round", "tappe_prese")) {
        ns.remove(k);
      }
    }

    /** 1: the rule over CampagnaOmbra's land candidates (owner at war), model M1. */
    private Optional<CampagnaOmbra.Calcolo.Obiettivo> scegliObiettivo(
        final int puTurn, final int puC) {
      // step R3a: the USA and the United Kingdom only toward R3a's targets (bridgehead, North
      // Africa); the United Kingdom's Egypt stays R2c's
      final Optional<Map<Territory, String>> r3a =
          SbarcoCoordinato.bersagliCampagna(data, player, c);
      // step R4 (A1, A4): the siege's and Italy's targets with their levels
      final Optional<Map<Territory, Integer>> r4 = Assedio.bersagli(proData, calc, player, c);
      final List<CampagnaOmbra.Calcolo.Obiettivo> all;
      if (r4.isPresent()) {
        all = new ArrayList<>();
        final List<Territory> fronts = c.fronti();
        for (final Territory t : r4.get().keySet()) {
          c.obiettivoPer(t, fronts).ifPresent(all::add);
        }
      } else if (r3a.isPresent()) {
        all = new ArrayList<>();
        final List<Territory> fronts = c.fronti();
        for (final Territory t : r3a.get().keySet()) {
          c.obiettivoPer(t, fronts).ifPresent(all::add);
        }
      } else {
        all = c.obiettivi(new ArrayList<>());
      }
      final JsonArray alternatives = new JsonArray();
      final List<Candidato> candidates = new ArrayList<>();
      final Map<String, CampagnaOmbra.Calcolo.Obiettivo> byName = new HashMap<>();
      final double perRound = puOperazione(puTurn, puC, quotaOperazione(data, player));
      final List<String> text = new ArrayList<>();
      for (final CampagnaOmbra.Calcolo.Obiettivo o : all) {
        final Territory t = o.bersaglio();
        // B4: the United Kingdom only in Africa and the Middle East
        if (r3a.isEmpty()
            && Programmi.UK.equals(player.getName())
            && !r2c.territoriUkCampagna().contains(t.getName())) {
          text.add(t.getName() + " (Regno Unito: solo Africa e Medio Oriente)");
          continue;
        }
        if (!data.getRelationshipTracker().isAtWar(player, t.getOwner())) {
          text.add(t.getName() + " (non in guerra con " + t.getOwner().getName() + ")");
          continue;
        }
        final JsonObject m = c.valutaImpegno(o, cfg.margineScelta(), perRound, "m1");
        final Candidato x =
            new Candidato(
                t.getName(),
                o.percorso().size(),
                m.get("fattibile").getAsBoolean(),
                m.get("scarto_tuv").getAsLong(),
                CampagnaOmbra.Calcolo.valoreIndice(t));
        candidates.add(x);
        byName.put(t.getName(), o);
        final JsonObject a = new JsonObject();
        a.addProperty("territorio", t.getName());
        a.addProperty("tappe", x.tappe());
        a.addProperty("fattibile", x.fattibile());
        a.addProperty("scarto_tuv", x.scarto());
        a.addProperty("valore", x.valore());
        alternatives.add(a);
        text.add(
            String.format(
                Locale.ROOT,
                "%s (%d tappe, fattibile %s, scarto %s)",
                t.getName(),
                x.tappe(),
                x.fattibile() ? "SI'" : "no",
                x.scarto() < 0 ? "non stimabile" : String.valueOf(x.scarto())));
      }
      lines.add(
          "  Candidati via terra (margine "
              + cfg.margineScelta()
              + ", M1): "
              + (text.isEmpty() ? "nessuno" : String.join("; ", text)));
      final boolean onlyFeasible = r2c.nazioniSoloFattibili().contains(player.getName());
      Optional<Candidato> chosen = onlyFeasible ? scegliFattibile(candidates) : scegli(candidates);
      if (r4.isPresent()) {
        // step R4 (C2): by level, then the gain per round (PUs + bonuses obtained + taken away)
        final List<Assedio.Scelta> r4c = new ArrayList<>();
        for (final Candidato k : candidates) {
          final Territory t = data.getMap().getTerritoryOrNull(k.territorio());
          r4c.add(
              new Assedio.Scelta(
                  k.territorio(),
                  r4.get().getOrDefault(t, 9),
                  Reddito.guadagno(data, player, t),
                  k.fattibile(),
                  k.tappe()));
        }
        final Optional<Assedio.Scelta> best = Assedio.scegli(r4c);
        chosen =
            best.flatMap(
                b ->
                    candidates.stream()
                        .filter(k -> k.territorio().equals(b.territorio()))
                        .findFirst());
        best.ifPresent(b -> Assedio.registraScelta(data, player, r4c, b, r4.get()));
        best.ifPresent(
            b ->
                lines.add(
                    "  R4: livello "
                        + b.livello()
                        + ", guadagno per round "
                        + b.guadagno().totale()
                        + " PU ("
                        + b.guadagno().pu()
                        + " + bonus "
                        + b.guadagno().bonusOttenuti()
                        + " + tolti "
                        + b.guadagno().bonusTolti()
                        + ")"));
      }
      if (chosen.isEmpty()) {
        lines.add(
            "  Nessun obiettivo scelto"
                + (onlyFeasible && !candidates.isEmpty()
                    ? ": nessuno fattibile a margine " + cfg.margineScelta() + ", " + COME_HARD
                    : ""));
        return Optional.empty();
      }
      final Candidato x = chosen.get();
      final String why =
          x.fattibile()
              ? "fattibile a margine " + cfg.margineScelta() + " con meno tappe (" + x.tappe() + ")"
              : "nessuno fattibile: scarto piu' piccolo ("
                  + (x.scarto() < 0 ? "non stimabile" : x.scarto() + " TUV")
                  + ")";
      ns.addProperty("obiettivo", x.territorio());
      ns.addProperty("dal_round", round);
      ns.addProperty("motivo_scelta", why);
      ns.add("tappe_prese", new JsonArray());
      lines.add("  NUOVO obiettivo " + x.territorio() + ": " + why);
      if (r3a.isPresent()) {
        final String kind =
            r3a.get().getOrDefault(byName.get(x.territorio()).bersaglio(), "campagna_africa");
        ns.addProperty("tipo_r3a", kind);
        SbarcoCoordinato.registraCampagna(data, player, kind, alternatives, x.territorio(), why);
      }
      final JsonObject turn = turno(st, round, player.getName());
      turn.addProperty("nuovo_obiettivo", true);
      return Optional.of(byName.get(x.territorio()));
    }

    /** The first enemy territory on the route. */
    private Territory prossimaTappa(final CampagnaOmbra.Calcolo.Obiettivo o) {
      for (final Territory t : o.percorso()) {
        if (c.nemico(t.getOwner())) {
          return t;
        }
      }
      return o.bersaglio();
    }

    /** The friendly territory the next stage is attacked from (its predecessor on the route). */
    private static Territory raduno(
        final CampagnaOmbra.Calcolo.Obiettivo o, final Territory stage) {
      final int i = o.percorso().indexOf(stage);
      return i <= 0 ? o.partenza() : o.percorso().get(i - 1);
    }

    /**
     * The next stage and the target with every unit in range (Hard's attack options) and the real
     * defenders: win% and P80 of the losses (not yet forced: the phase decides).
     */
    private JsonArray valutaAttacchi(
        final CampagnaOmbra.Calcolo.Obiettivo o, final Territory stage) {
      final List<Territory> targets = new ArrayList<>(List.of(stage));
      if (!o.bersaglio().equals(stage)) {
        targets.add(o.bersaglio());
      }
      final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
      tm.populateAttackOptions();
      final JsonArray out = new JsonArray();
      for (final Territory t : targets) {
        if (!data.getRelationshipTracker().isAtWar(player, t.getOwner())) {
          continue;
        }
        final ProTerritory pt = tm.getAttackOptions().getTerritoryMap().get(t);
        if (pt == null) {
          continue;
        }
        final List<Unit> attackers = new ArrayList<>(pt.getMaxUnits());
        pt.getMaxAmphibUnits().stream().filter(u -> !attackers.contains(u)).forEach(attackers::add);
        if (attackers.stream().noneMatch(Matches.unitIsLand())) {
          continue;
        }
        final List<Unit> defenders = t.getMatches(Matches.enemyUnit(player));
        double win;
        double p80 = -1;
        final List<Unit> fighting =
            defenders.stream().filter(Matches.unitIsNotInfrastructure()).toList();
        if (fighting.isEmpty()) {
          win = 100;
          p80 = 0;
        } else if (c.calcolatore.isPresent()) {
          final CampagnaOmbraMisure.Distribuzione d = c.attacco(t, attackers, defenders);
          win = d.vittoriaPct();
          p80 = d.percentile(80);
        } else {
          win =
              calc.calculateBattleResults(proData, t, attackers, defenders, pt.getMaxBombardUnits())
                  .getWinPercentage();
        }
        final JsonObject x = new JsonObject();
        x.addProperty("territorio", t.getName());
        x.addProperty("obiettivo", t.equals(o.bersaglio()));
        x.addProperty("vittoria_pct", Math.round(win * 10) / 10.0);
        x.addProperty("perdite_p80_tuv", Math.round(p80 * 10) / 10.0);
        x.addProperty("attaccanti_tuv", Math.round(c.tuv(attackers)));
        out.add(x);
      }
      return out;
    }

    /**
     * 3: the campaign's orders of the turn (types by distance, nearest own factory to the next
     * stage) written in {@code order}; returns their PUs.
     */
    private int ordineAcquisto(
        final CampagnaOmbra.Calcolo.Obiettivo o,
        final Territory stage,
        final Territory gather,
        final int puTurn,
        final int puC,
        final int puOp,
        final JsonObject turn,
        final JsonObject order) {
      turn.addProperty("pu_op", puOp);
      // step R4 (A4): Italy reaches Africa by sea: the shuttle program buys for it
      if (Assedio.italia(data, player)
          && (ConfigR4.get().italiaLivello1().contains(o.bersaglio().getName())
              || ConfigR4.get().italiaLivello2().contains(o.bersaglio().getName()))) {
        lines.add("  Acquisti: Africa via mare -> spola italiana (R4)");
        turn.addProperty("pu_op", 0);
        return 0;
      }
      // step R4 (A2): the captured factory nearest to Moscow first
      final Territory factory =
          Assedio.fabbricaOperazione(data, player, order, lines)
              .orElseGet(() -> fabbricaVicina(stage));
      final int best =
          factory == null
              ? -1
              : data.getMap().getDistance(factory, stage, Matches.territoryIsLand());
      if (factory == null || puOp <= 0 || puTurn <= 0) {
        lines.add(
            "  Acquisti: "
                + (factory == null ? "nessuna fabbrica propria collegata via terra" : "0 PU")
                + " -> tutto a Hard");
        turn.addProperty("pu_op", 0);
        return 0;
      }
      // step R3a: a campaign in North Africa buys only from an own factory nearby
      if (SbarcoCoordinato.attivo(data, player)
          && SbarcoCoordinato.Config.get().nordAfrica().contains(o.bersaglio().getName())
          && best > SbarcoCoordinato.Config.get().distanzaFabbricaAfrica()) {
        lines.add(
            "  Acquisti: nessuna fabbrica propria entro "
                + SbarcoCoordinato.Config.get().distanzaFabbricaAfrica()
                + " dalla tappa (Nord Africa, R3a) -> tutto a Hard, campagna con le unita' gia' in"
                + " zona");
        turn.addProperty("pu_op", 0);
        return 0;
      }
      final List<String> types = tipi(best, cfg);
      final Map<String, Integer> costs = costiAcquisto(data, player);
      final Map<String, Integer> mix = composizione(puOp, types, costs);
      if (mix.isEmpty()) {
        turn.addProperty("pu_op", 0);
        return 0;
      }
      int spent = 0;
      for (final var e : mix.entrySet()) {
        spent += costs.getOrDefault(e.getKey(), 0) * e.getValue();
      }
      order.addProperty("obiettivo", o.bersaglio().getName());
      order.addProperty("fabbrica", factory.getName());
      order.addProperty("raduno", gather.getName());
      order.addProperty("pu_op", puOp);
      order.add("tipi", mappa(mix));
      turn.addProperty("fabbrica", factory.getName());
      turn.addProperty("distanza_fabbrica", best);
      turn.add("ordine", mappa(mix));
      lines.add(
          String.format(
              Locale.ROOT,
              "  Acquisti: PU del turno %d, presidio caso c di capitale/fabbriche %d -> all'operazione"
                  + " %d (%.0f%%): %s nella fabbrica %s (a %d dalla tappa: %s)",
              puTurn,
              puC,
              puOp,
              quotaOperazione(data, player),
              testo(mix),
              factory.getName(),
              best,
              best <= cfg.distanzaVicina() ? "vicina" : "lontana"));
      return spent;
    }

    // ---------------------------------------------------------------------- non combat

    Set<Unit> nonCombattimento(final IMoveDelegate delegate) {
      this.moveDel = delegate;
      final JsonObject turn = turno(st, round, player.getName());
      esiti(turn);
      final Set<Unit> held = new LinkedHashSet<>();
      if (campagnaTerra()
          && ns.has("obiettivo")
          && (!ns.has("sospeso") || ns.get("sospeso").getAsString().isEmpty())) {
        held.addAll(campagnaNc(turn));
      }
      if (Programmi.USA.equals(player.getName())) {
        held.addAll(
            programmi()
                .spola(
                    delegate,
                    ns.has("presidio_porto") ? ns.get("presidio_porto").getAsInt() : 0,
                    turn));
        SbarcoCoordinato.registraSpola(
            data, player, turn.has("spola") ? turn.getAsJsonObject("spola") : null);
        held.addAll(SbarcoCoordinato.nonCombattimento(proData, calc, delegate, player, c, lines));
      } else if (Programmi.UK.equals(player.getName())) {
        final Set<String> keep = new HashSet<>();
        if (proData.getMyCapital() != null) {
          keep.add(proData.getMyCapital().getName());
        }
        keep.addAll(r2c.presidiPrioritari().getOrDefault(player.getName(), List.of()));
        held.addAll(programmi().medioOriente(delegate, keep, turn));
        held.addAll(SbarcoCoordinato.nonCombattimento(proData, calc, delegate, player, c, lines));
        // step R4 (B4-B6): South Africa north, neutrals east of Egypt, the southern barrier
        held.addAll(
            SnodoAlleati.nonCombattimentoUk(proData, calc, delegate, player, c, keep, lines));
      }
      // step R4: the raid's outcome and the bombers' base (Germany), the Africa shuttle (Italy)
      held.addAll(Assedio.nonCombattimento(proData, calc, delegate, player, c, lines));
      if (ConvoglioItalia.attivo(data, player)) {
        // step R6c (E1-E3): the shuttle in convoy
        held.addAll(
            ConvoglioItalia.nonCombattimento(
                programmi(),
                delegate,
                ns.has("presidio_porto") ? ns.get("presidio_porto").getAsInt() : 0));
      } else if (Assedio.italia(data, player)) {
        held.addAll(
            programmi()
                .spola(
                    delegate,
                    ns.has("presidio_porto") ? ns.get("presidio_porto").getAsInt() : 0,
                    turn));
      }
      salvaNc(turn);
      return held;
    }

    /** The campaign's non-combat move: cover of the stages taken, the units bought gather. */
    private Set<Unit> campagnaNc(final JsonObject turn) {
      final Set<Unit> held = new LinkedHashSet<>();
      final Territory target = data.getMap().getTerritoryOrNull(ns.get("obiettivo").getAsString());
      if (target == null || target.isOwnedBy(player)) {
        return held;
      }
      // the stage and gathering territory of the purchase stay for the whole turn; a new route only
      // when the stage was taken in the combat move
      Territory stage =
          ns.has("tappa") ? data.getMap().getTerritoryOrNull(ns.get("tappa").getAsString()) : null;
      Territory gather =
          ns.has("raduno")
              ? data.getMap().getTerritoryOrNull(ns.get("raduno").getAsString())
              : null;
      if (stage == null
          || gather == null
          || stage.isOwnedBy(player)
          || !Matches.isTerritoryAllied(player).test(gather)) {
        final Optional<CampagnaOmbra.Calcolo.Obiettivo> route = c.obiettivoPer(target, c.fronti());
        if (route.isEmpty()) {
          lines.add("  Instradamento: nessuna rotta ora");
          return held;
        }
        stage = prossimaTappa(route.get());
        gather = raduno(route.get(), stage);
        ns.addProperty("tappa", stage.getName());
        ns.addProperty("raduno", gather.getName());
        turn.addProperty("tappa_dopo_combattimento", stage.getName());
        turn.addProperty("raduno_dopo_combattimento", gather.getName());
      }
      // 6: the cover of the stages taken
      held.addAll(copertura(turn));
      // A: execution -> the gathering territory; preparation -> the safe base
      final boolean execution = ns.has("fase") && ESECUZIONE.equals(ns.get("fase").getAsString());
      final Territory dest =
          execution
              ? gather
              : ns.has("base")
                  ? data.getMap().getTerritoryOrNull(ns.get("base").getAsString())
                  : null;
      if (dest == null || !Matches.isTerritoryAllied(player).test(dest)) {
        lines.add(
            "  Instradamento: PREPARAZIONE senza base sicura, le unita' comprate restano ferme");
        return held;
      }
      // B1: only the units bought for the operation (followed by id)
      final Set<String> ids = new HashSet<>(obj(ns, "unita").keySet());
      final List<Unit> units = new ArrayList<>();
      final Map<Unit, Territory> where = new HashMap<>();
      for (final Territory t : data.getMap().getTerritories()) {
        if (t.isWater()) {
          continue;
        }
        for (final Unit u : t.getMatches(terraPropria())) {
          if (ids.contains(u.getId().toString()) && !held.contains(u)) {
            units.add(u);
            where.put(u, t);
          }
        }
      }
      units.sort(Comparator.comparing(u -> u.getId().toString()));
      int moved = 0;
      int there = 0;
      int refused = 0;
      final Map<String, Integer> moves = new LinkedHashMap<>();
      for (final Unit u : units) {
        final Territory from = where.get(u);
        if (from.equals(dest)) {
          held.add(u);
          there++;
          continue;
        }
        if (!Matches.unitHasMovementLeft().test(u)) {
          continue;
        }
        final Optional<Territory> to = passo(u, from, dest);
        if (to.isEmpty()) {
          refused++;
          continue;
        }
        held.add(u);
        moved++;
        moves.merge(from.getName() + " -> " + to.get().getName(), 1, Integer::sum);
      }
      turn.addProperty("instradate", moved);
      turn.addProperty("gia_al_raduno", there);
      turn.addProperty("senza_passo_sicuro", refused);
      turn.addProperty("destinazione", dest.getName());
      lines.add(
          "  "
              + (execution ? "ESECUZIONE: raduno in " : "PREPARAZIONE: accumulo nella base sicura ")
              + dest.getName()
              + " (tappa "
              + stage.getName()
              + "), solo unita' comprate per l'operazione: "
              + moved
              + " mosse"
              + (moves.isEmpty()
                  ? ""
                  : " ("
                      + String.join(
                          ", ",
                          moves.entrySet().stream()
                              .map(e -> e.getValue() + " " + e.getKey())
                              .toList())
                      + ")")
              + ", "
              + there
              + " gia' li', "
              + refused
              + " senza passo sicuro (restano a Hard)");
      return held;
    }

    private void salvaNc(final JsonObject turn) {
      salva(props, alliance, st);
      final JsonObject rec = new JsonObject();
      rec.addProperty("tipo", "impegno_non_combattimento");
      rec.addProperty("round", round);
      rec.add("turno", turn.deepCopy());
      diario(data, lines, rec);
    }

    /** After the battles: the forced attacks' outcome, stages and target taken. */
    private void esiti(final JsonObject turn) {
      final JsonArray reviews = REVISIONI.remove(player.getName() + "|" + round);
      if (reviews != null) {
        turn.add("revisioni", reviews);
        for (final JsonElement e : reviews) {
          final JsonObject r = e.getAsJsonObject();
          lines.add(
              "  Attacco forzato su "
                  + r.get("territorio").getAsString()
                  + " rivisto dopo le mosse di Hard: "
                  + r.get("revisione").getAsString().toUpperCase(Locale.ROOT)
                  + (r.has("vittoria_mandate_pct")
                      ? " (vittoria con le unita' mandate "
                          + r.get("vittoria_mandate_pct").getAsDouble()
                          + "%"
                          + (r.has("aggiunte") && r.get("aggiunte").getAsInt() > 0
                              ? ", +"
                                  + r.get("aggiunte").getAsInt()
                                  + " unita' -> "
                                  + r.get("vittoria_finale_pct").getAsDouble()
                                  + "%"
                              : "")
                          + ")"
                      : ""));
          if (turn.has("attacchi")) {
            for (final JsonElement a : turn.getAsJsonArray("attacchi")) {
              if (a.getAsJsonObject()
                  .get("territorio")
                  .getAsString()
                  .equals(r.get("territorio").getAsString())) {
                a.getAsJsonObject().add("revisione", r.get("revisione"));
              }
            }
          }
        }
      }
      if (turn.has("attacchi")) {
        for (final JsonElement e : turn.getAsJsonArray("attacchi")) {
          final JsonObject x = e.getAsJsonObject();
          if (!x.get("forzato").getAsBoolean() || x.has("esito")) {
            continue;
          }
          final Territory t = data.getMap().getTerritoryOrNull(x.get("territorio").getAsString());
          if (t == null) {
            continue;
          }
          final int lost = c.perditaReale(round, t.getName());
          final String outcome =
              t.isOwnedBy(player) ? "vinto" : lost >= 0 ? "non preso" : "non attaccato";
          x.addProperty("esito", outcome);
          x.addProperty("perdite_reali_tuv", Math.max(0, lost));
          lines.add(
              String.format(
                  Locale.ROOT,
                  "  Attacco forzato su %s: %s, perdite %d TUV (P80 %.0f)",
                  t.getName(),
                  outcome.toUpperCase(Locale.ROOT),
                  Math.max(0, lost),
                  x.get("perdite_p80_tuv").getAsDouble()));
        }
      }
      if (ns.has("tappa")) {
        final Territory s = data.getMap().getTerritoryOrNull(ns.get("tappa").getAsString());
        if (s != null && s.isOwnedBy(player)) {
          final JsonArray taken = arr(ns, "tappe_prese");
          boolean known = false;
          for (final JsonElement e : taken) {
            known |= e.getAsJsonObject().get("territorio").getAsString().equals(s.getName());
          }
          if (!known) {
            final JsonObject x = new JsonObject();
            x.addProperty("territorio", s.getName());
            x.addProperty("round", round);
            taken.add(x);
            turn.addProperty("tappa_presa", s.getName());
            lines.add("  Tappa " + s.getName() + " PRESA al R" + round);
          }
        }
      }
      if (ns.has("obiettivo") && !ns.has("preso_round")) {
        final Territory t = data.getMap().getTerritoryOrNull(ns.get("obiettivo").getAsString());
        if (t != null && t.isOwnedBy(player)) {
          ns.addProperty("preso_round", round);
          turn.addProperty("obiettivo_preso", t.getName());
          lines.add("  Obiettivo " + t.getName() + " PRESO al R" + round);
        }
      }
    }

    /** 6: on each stage taken and still own, the minimal garrison of case c (inf/art), no more. */
    private Set<Unit> copertura(final JsonObject turn) {
      final Set<Unit> held = new LinkedHashSet<>();
      if (!ns.has("tappe_prese")) {
        return held;
      }
      final List<Territory> stages = new ArrayList<>();
      for (final JsonElement e : ns.getAsJsonArray("tappe_prese")) {
        final Territory t =
            data.getMap().getTerritoryOrNull(e.getAsJsonObject().get("territorio").getAsString());
        if (t != null && t.isOwnedBy(player)) {
          stages.add(t);
        }
      }
      if (stages.isEmpty()) {
        return held;
      }
      final ProTerritoryManager tm = enemyOptions(stages);
      final List<String> text = new ArrayList<>();
      for (final Territory t : stages) {
        final JsonObject x = c.classifica(t, enemyLand(tm, t), true, 0, 0, 0);
        if (!CampagnaOmbra.CASO_C.equals(x.get("caso").getAsString())) {
          text.add(t.getName() + " caso " + x.get("caso").getAsString() + ": nessun presidio");
          continue;
        }
        final int n = x.get("unita").getAsInt();
        final List<Unit> infArt =
            t.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(
                        u ->
                            u.getType().getName().equals("infantry")
                                || u.getType().getName().equals("artillery")));
        infArt.sort(Comparator.comparing(u -> u.getId().toString()));
        final List<Unit> keep = infArt.subList(0, Math.min(n, infArt.size()));
        held.addAll(keep);
        text.add(t.getName() + " caso c: presidio " + keep.size() + " di " + n);
      }
      turn.addProperty("presidio_tappe", held.size());
      lines.add("  Copertura delle tappe prese: " + String.join("; ", text));
      return held;
    }

    /**
     * 4: one move of {@code u} to the friendly land nearest to the gathering territory, within its
     * movement, where the realistic counterattack does not take it (enemy win% <= the gathering
     * places' safety threshold). The move is made; empty if none.
     */
    private Optional<Territory> passo(final Unit u, final Territory from, final Territory gather) {
      final int now = distanzaRaduno(from, gather);
      if (now < 0) {
        // no path through friendly land to the gathering territory: the unit stays with Hard
        return Optional.empty();
      }
      final int range = Math.max(0, u.getMovementLeft().intValue());
      final List<Territory> options = new ArrayList<>();
      for (final Territory t : data.getMap().getNeighbors(from, range)) {
        if (!t.isWater()
            && Matches.isTerritoryAllied(player).test(t)
            && distanzaRaduno(t, gather) >= 0
            && distanzaRaduno(t, gather) < now) {
          options.add(t);
        }
      }
      options.sort(
          Comparator.comparingInt((Territory t) -> distanzaRaduno(t, gather))
              .thenComparing(Territory::getName));
      for (final Territory to : options) {
        final Predicate<Territory> can =
            ProMatches.territoryCanMoveLandUnitsThrough(player, u, from, false, List.of());
        final Optional<Route> r = data.getMap().getRouteForUnit(from, to, can, u, player);
        if (r.isEmpty() || r.get().getMovementCost(u).compareTo(u.getMovementLeft()) > 0) {
          continue;
        }
        if (!sicuro(to, u)) {
          continue;
        }
        if (moveDel == null) {
          return Optional.empty();
        }
        final Optional<String> error =
            moveDel.performMove(new MoveDescription(List.of(u), r.get()));
        if (error.isEmpty()) {
          return Optional.of(to);
        }
      }
      return Optional.empty();
    }

    private @Nullable IMoveDelegate moveDel;

    private int distanzaRaduno(final Territory t, final Territory gather) {
      if (t.equals(gather)) {
        return 0;
      }
      // through friendly land only (never around an enemy coast: no landings in this step)
      return data.getMap()
          .getDistance(
              t,
              gather,
              x -> !x.isWater() && (x.equals(gather) || Matches.isTerritoryAllied(player).test(x)));
    }

    /**
     * 4: the realistic counterattack ({@link CampagnaOmbra.Calcolo#contrattacco}) against the
     * allied units there and {@code u}, enemy win% within {@code soglia_sicurezza_raduno} (the same
     * check as the operations' gathering places). Memoized per territory: adding units only helps.
     */
    private boolean sicuro(final Territory to, final Unit u) {
      final Double known = safety.get(to.getName());
      final double limit = StrategaConfig.get().sogliaSicurezzaRaduno();
      if (known != null && known <= limit) {
        return true;
      }
      final List<Unit> counter = c.contrattacco(to);
      if (counter.isEmpty()) {
        safety.put(to.getName(), 0.0);
        return true;
      }
      final List<Unit> defenders = new ArrayList<>(to.getMatches(Matches.isUnitAllied(player)));
      if (!defenders.contains(u)) {
        defenders.add(u);
      }
      final double enemyWin =
          calc.calculateBattleResults(proData, to, counter, defenders, List.of())
              .getWinPercentage();
      safety.put(to.getName(), enemyWin);
      return enemyWin <= limit;
    }

    private Predicate<Unit> terraPropria() {
      return Matches.unitIsOwnedBy(player)
          .and(Matches.unitIsLand())
          .and(Matches.unitIsNotInfrastructure());
    }
  }

  // ================================================================================ helpers

  static JsonObject stato(final GameProperties props, final String alliance) {
    if (props.get(KEY_STATO + alliance) instanceof String s && !s.isBlank()) {
      try {
        return JsonParser.parseString(s).getAsJsonObject();
      } catch (final RuntimeException e) {
        // a fresh state
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

  static JsonObject nazione(final JsonObject st, final String nation) {
    final JsonObject all = obj(st, "nazioni");
    return obj(all, nation);
  }

  /** The record of the turn of {@code nation} in {@code round} (created if absent). */
  static JsonObject turno(final JsonObject st, final int round, final String nation) {
    final JsonArray turns = arr(st, "turni");
    for (int i = turns.size() - 1; i >= 0; i--) {
      final JsonObject t = turns.get(i).getAsJsonObject();
      if (t.get("round").getAsInt() == round && t.get("nazione").getAsString().equals(nation)) {
        return t;
      }
    }
    final JsonObject t = new JsonObject();
    t.addProperty("round", round);
    t.addProperty("nazione", nation);
    turns.add(t);
    return t;
  }

  private static JsonObject obj(final JsonObject o, final String key) {
    if (!o.has(key) || !o.get(key).isJsonObject()) {
      o.add(key, new JsonObject());
    }
    return o.getAsJsonObject(key);
  }

  private static JsonArray arr(final JsonObject o, final String key) {
    if (!o.has(key) || !o.get(key).isJsonArray()) {
      o.add(key, new JsonArray());
    }
    return o.getAsJsonArray(key);
  }

  private static JsonObject mappa(final Map<String, Integer> m) {
    final JsonObject o = new JsonObject();
    m.forEach(o::addProperty);
    return o;
  }

  private static String testo(final Map<String, Integer> m) {
    return String.join(
        ", ", m.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList());
  }

  /** Keeps at most {@code max} followed units (the oldest ids are dropped first). */
  private static void potaSeguite(final JsonObject seguite, final int max) {
    final List<String> keys = new ArrayList<>(seguite.keySet());
    for (int i = 0; i < keys.size() - max; i++) {
      seguite.remove(keys.get(i));
    }
  }

  /**
   * Step R4: follows by id {@code want} (type → count) among {@code fresh}; only units placed in
   * {@code where} when not empty; the ids no longer on the map are dropped.
   */
  private static void segui(
      final JsonObject ids,
      final Map<String, Integer> want,
      final List<Unit> fresh,
      final Set<Territory> where) {
    final Set<String> taken = new HashSet<>(ids.keySet());
    for (final var e : want.entrySet()) {
      int n = e.getValue();
      for (final Unit u : fresh) {
        if (n <= 0) {
          break;
        }
        if (u.getType().getName().equals(e.getKey())
            && !taken.contains(u.getId().toString())
            && (where.isEmpty()
                || where.stream().anyMatch(t -> t.getUnitCollection().contains(u)))) {
          ids.addProperty(u.getId().toString(), u.getType().getName());
          taken.add(u.getId().toString());
          n--;
        }
      }
    }
    final Set<String> onMap = new HashSet<>();
    if (!fresh.isEmpty()) {
      final GameState data = fresh.get(0).getData();
      onMap.addAll(idsSullaMappa(data));
      for (final String id : new ArrayList<>(ids.keySet())) {
        if (!onMap.contains(id)) {
          ids.remove(id);
        }
      }
    }
  }

  private static Set<String> idsSullaMappa(final GameState data) {
    final Set<String> ids = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnitCollection().forEach(u -> ids.add(u.getId().toString()));
    }
    return ids;
  }

  private static Map<Unit, Territory> luoghi(final GameState data, final List<Unit> units) {
    final Set<Unit> set = new HashSet<>(units);
    final Map<Unit, Territory> out = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnitCollection().stream().filter(set::contains).forEach(u -> out.put(u, t));
    }
    return out;
  }

  /** The PUs of {@code mix} (type → count). */
  static int costo(final GameState data, final GamePlayer player, final Map<String, Integer> mix) {
    final Map<String, Integer> costs = costiAcquisto(data, player);
    int n = 0;
    for (final var e : mix.entrySet()) {
      n += costs.getOrDefault(e.getKey(), 0) * e.getValue();
    }
    return n;
  }

  /** PUs of one unit of each type the nation can buy (production frontier), by type name. */
  static Map<String, Integer> costiAcquisto(final GameState data, final GamePlayer player) {
    final Map<String, Integer> out = new HashMap<>();
    if (player.getProductionFrontier() == null) {
      return out;
    }
    final var pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    for (final ProductionRule rule : player.getProductionFrontier().getRules()) {
      if (rule.getResults().size() != 1
          || !(rule.getResults().keySet().iterator().next() instanceof UnitType type)
          || rule.getResults().getInt(type) != 1) {
        continue;
      }
      out.putIfAbsent(type.getName(), rule.getCosts().getInt(pus));
    }
    return out;
  }

  /** Step R3a: lines of the combat move in the strategist's diary. */
  static void diarioRighe(final GameState data, final List<String> lines) {
    diario(data, lines, null);
  }

  private static void diario(
      final GameState data, final List<String> lines, @Nullable final JsonObject rec) {
    if (lines.isEmpty() && rec == null) {
      return;
    }
    try {
      DiaryRecorder.stratega(
          data, lines, rec, data instanceof GameData gd ? StrategaDiario.riepilogo(gd) : null);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Impegno, diario: " + e);
    }
  }
}
