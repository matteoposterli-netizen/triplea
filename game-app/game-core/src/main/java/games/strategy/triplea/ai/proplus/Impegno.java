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

  static final String PRESO = "preso";
  static final String NON_NEMICO = "non piu' del nemico";
  static final String ROTTA_ASSENTE = "rotta assente";
  static final String CAPITALE_C = "capitale in caso c";

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
  static int puOperazione(final int puTurno, final int puCasoC, final double quota) {
    return (int) Math.floor(Math.max(0, puTurno - puCasoC) * quota / 100.0);
  }

  /** 5: a forced attack when the calculator's win with every unit in range is >= the threshold. */
  static boolean forza(final double vittoriaPct, final double soglia) {
    return vittoriaPct >= soglia;
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
        && !StrategaConfig.get().nazioniSenzaRuoli().contains(player.getName());
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
      for (final var e : o.getAsJsonObject("tipi").entrySet()) {
        mandatory.add(
            new ProPurchaseOrders.Mandatory(
                "impegno " + e.getKey(), List.of(e.getKey()), e.getValue().getAsInt()));
      }
      if (mandatory.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new ProPurchaseOrders(
              "Impegno " + player.getName() + ": " + o.get("obiettivo").getAsString(),
              List.of(),
              List.copyOf(mandatory),
              List.of(o.get("fabbrica").getAsString()),
              List.of(o.get("raduno").getAsString()),
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
          final Territory factory =
              data.getMap().getTerritoryOrNull(order.get("fabbrica").getAsString());
          final List<Unit> fresh = new ArrayList<>();
          for (final Territory t : data.getMap().getTerritories()) {
            t.getUnitCollection().stream()
                .filter(Matches.unitIsOwnedBy(player))
                .filter(u -> !before.contains(u.getId().toString()))
                .forEach(fresh::add);
          }
          final Map<Unit, Territory> where = luoghi(data, fresh);
          fresh.sort(
              Comparator.comparingInt(
                      (Unit u) ->
                          factory == null || where.get(u) == null
                              ? 99
                              : Math.max(0, data.getMap().getDistance(where.get(u), factory)))
                  .thenComparing(u -> u.getId().toString()));
          final Map<String, Integer> costs = costiAcquisto(data, player);
          final Map<String, Integer> taken = new LinkedHashMap<>();
          int spent = 0;
          for (final var e : order.getAsJsonObject("tipi").entrySet()) {
            int n = e.getValue().getAsInt();
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
          final JsonObject turn = turno(st, round, player.getName());
          turn.addProperty("pu_op_spesi", spent);
          turn.add("unita_comprate", mappa(taken));
          lines.add(
              "  Impegno "
                  + player.getName()
                  + ": comprate e piazzate per l'operazione "
                  + (taken.isEmpty() ? "nessuna unita'" : testo(taken))
                  + " ("
                  + spent
                  + " PU di "
                  + order.get("pu_op").getAsInt()
                  + " previsti), seguite per id");
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
    final int round;
    final CampagnaOmbra.Calcolo c;
    final JsonObject st;
    final JsonObject ns;
    final List<String> lines = new ArrayList<>();
    @Nullable ProTerritoryManager enemy;
    final Map<String, Double> safety = new HashMap<>();

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
      lines.add("=== R" + round + " " + player.getName() + " - Impegno (step R2b, AGISCE) ===");
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
      final int puOp = puOperazione(puTurn, puC, cfg.quotaPu());
      turn.addProperty("pu_turno", puTurn);
      turn.addProperty("pu_caso_c", puC);
      turn.addProperty("capitale_caso_c", capitalC);
      // the units in case a/b at the start of the turn (routing, point 4)
      final JsonArray rear = new JsonArray();
      for (final var e : cases.entrySet()) {
        final String caso = e.getValue().get("caso").getAsString();
        if (CampagnaOmbra.CASO_C.equals(caso)) {
          continue;
        }
        e.getKey().getMatches(terraPropria()).forEach(u -> rear.add(u.getId().toString()));
      }
      ns.add("retrovie", rear);
      ns.addProperty("retrovie_round", round);
      // 2: inertia of the current target
      final List<Territory> fronts = c.fronti();
      String suspended = "";
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
        if (end.isPresent()) {
          chiudi(end.get(), target);
          route = Optional.empty();
        } else if (route.isEmpty()) {
          suspended =
              ROTTA_ASSENTE + " (durata " + (round - since) + " < " + cfg.durataMinima() + ")";
        } else if (capitalC) {
          // the target stays (inertia); the garrison of the capital comes first in the PUs
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
        }
      }
      turn.addProperty("obiettivo", ns.has("obiettivo") ? ns.get("obiettivo").getAsString() : "");
      if (!suspended.isEmpty()) {
        turn.addProperty("sospeso", suspended);
        lines.add("  Operazione SOSPESA nel turno: " + suspended + " (obiettivo invariato)");
      }
      ns.addProperty("sospeso", suspended);
      if (route.isEmpty() || !suspended.isEmpty() || !ns.has("obiettivo")) {
        ns.remove("tappa");
        ns.remove("raduno");
        salvaETermina(turn);
        return;
      }
      final CampagnaOmbra.Calcolo.Obiettivo o = route.get();
      final Territory stage = prossimaTappa(o);
      final Territory gather = raduno(o, stage);
      ns.addProperty("tappa", stage.getName());
      ns.addProperty("raduno", gather.getName());
      turn.addProperty("tappa", stage.getName());
      turn.addProperty("raduno", gather.getName());
      lines.add(
          String.format(
              Locale.ROOT,
              "  Obiettivo %s (dal R%d, %s); rotta %s; prossima tappa %s, raduno %s",
              o.bersaglio().getName(),
              ns.get("dal_round").getAsInt(),
              ns.has("motivo_scelta") ? ns.get("motivo_scelta").getAsString() : "-",
              String.join(" > ", o.percorso().stream().map(Territory::getName).toList()),
              stage.getName(),
              gather.getName()));
      // 5: forced attacks on the next stage and the target
      attacchiForzati(o, stage, turn);
      // 3: purchase orders
      ordineAcquisto(o, stage, gather, puTurn, puC, puOp, turn);
      salvaETermina(turn);
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
            t.equals(proData.getMyCapital()) || t.anyUnitsMatch(Matches.unitCanProduceUnits());
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
      final List<CampagnaOmbra.Calcolo.Obiettivo> all = c.obiettivi(new ArrayList<>());
      final List<Candidato> candidates = new ArrayList<>();
      final Map<String, CampagnaOmbra.Calcolo.Obiettivo> byName = new HashMap<>();
      final double perRound = puOperazione(puTurn, puC, cfg.quotaPu());
      final List<String> text = new ArrayList<>();
      for (final CampagnaOmbra.Calcolo.Obiettivo o : all) {
        final Territory t = o.bersaglio();
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
      final Optional<Candidato> chosen = scegli(candidates);
      if (chosen.isEmpty()) {
        lines.add("  Nessun obiettivo scelto");
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

    /** 5: the next stage and the target, with every unit in range and the real defenders. */
    private void attacchiForzati(
        final CampagnaOmbra.Calcolo.Obiettivo o, final Territory stage, final JsonObject turn) {
      final List<Territory> targets = new ArrayList<>(List.of(stage));
      if (!o.bersaglio().equals(stage)) {
        targets.add(o.bersaglio());
      }
      final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
      tm.populateAttackOptions();
      final List<String> forced = new ArrayList<>();
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
        final boolean force = forza(win, cfg.sogliaForzato());
        final JsonObject x = new JsonObject();
        x.addProperty("territorio", t.getName());
        x.addProperty("obiettivo", t.equals(o.bersaglio()));
        x.addProperty("vittoria_pct", Math.round(win * 10) / 10.0);
        x.addProperty("perdite_p80_tuv", Math.round(p80 * 10) / 10.0);
        x.addProperty("attaccanti_tuv", Math.round(c.tuv(attackers)));
        x.addProperty("forzato", force);
        out.add(x);
        lines.add(
            String.format(
                Locale.ROOT,
                "  Attacco su %s con tutte le unita' in portata (%.0f TUV): vittoria %.1f%%,"
                    + " perdite P80 %.0f TUV -> %s",
                t.getName(),
                c.tuv(attackers),
                win,
                p80,
                force
                    ? "FORZATO (>= " + Math.round(cfg.sogliaForzato()) + "%)"
                    : "sotto soglia: decide Hard, l'operazione accumula"));
        if (force) {
          forced.add(t.getName());
        }
      }
      turn.add("attacchi", out);
      clear(props, KEY_FORZATI + player.getName());
      if (!forced.isEmpty()) {
        props.set(KEY_FORZATI + player.getName(), round + "|" + String.join(";", forced));
      }
    }

    /** 3: the orders of the turn (types by distance, nearest own factory to the next stage). */
    private void ordineAcquisto(
        final CampagnaOmbra.Calcolo.Obiettivo o,
        final Territory stage,
        final Territory gather,
        final int puTurn,
        final int puC,
        final int puOp,
        final JsonObject turn) {
      turn.addProperty("pu_op", puOp);
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
      if (factory == null || puOp <= 0 || puTurn <= 0) {
        lines.add(
            "  Acquisti: "
                + (factory == null ? "nessuna fabbrica propria collegata via terra" : "0 PU")
                + " -> tutto a Hard");
        turn.addProperty("pu_op", 0);
        return;
      }
      final List<String> types = tipi(best, cfg);
      final Map<String, Integer> costs = costiAcquisto(data, player);
      final Map<String, Integer> mix = composizione(puOp, types, costs);
      if (mix.isEmpty()) {
        turn.addProperty("pu_op", 0);
        return;
      }
      final JsonObject order = new JsonObject();
      order.addProperty("round", round);
      order.addProperty("obiettivo", o.bersaglio().getName());
      order.addProperty("fabbrica", factory.getName());
      order.addProperty("raduno", gather.getName());
      order.addProperty("pu_op", puOp);
      order.addProperty("quota_minima", Math.min(100, 100.0 * puOp / puTurn));
      order.add("tipi", mappa(mix));
      clear(props, KEY_ORDINE + player.getName());
      props.set(KEY_ORDINE + player.getName(), order.toString());
      turn.addProperty("fabbrica", factory.getName());
      turn.addProperty("distanza_fabbrica", best);
      turn.add("ordine", mappa(mix));
      lines.add(
          String.format(
              Locale.ROOT,
              "  Acquisti: PU del turno %d, presidio caso c di capitale/fabbriche %d -> all'operazione"
                  + " %d (%.0f%%): %s nella fabbrica %s (a %d dalla tappa: %s); il resto a Hard",
              puTurn,
              puC,
              puOp,
              cfg.quotaPu(),
              testo(mix),
              factory.getName(),
              best,
              best <= cfg.distanzaVicina() ? "vicina" : "lontana"));
    }

    // ---------------------------------------------------------------------- non combat

    Set<Unit> nonCombattimento(final IMoveDelegate delegate) {
      this.moveDel = delegate;
      final JsonObject turn = turno(st, round, player.getName());
      esiti(turn);
      final Set<Unit> held = new LinkedHashSet<>();
      if (!ns.has("obiettivo") || !ns.get("sospeso").getAsString().isEmpty()) {
        salvaNc(turn);
        return held;
      }
      final Territory target = data.getMap().getTerritoryOrNull(ns.get("obiettivo").getAsString());
      if (target == null || target.isOwnedBy(player)) {
        salvaNc(turn);
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
          salvaNc(turn);
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
      // 4: the operation's units toward the gathering territory
      final Set<String> ids = new HashSet<>(obj(ns, "unita").keySet());
      if (ns.has("retrovie")
          && ns.has("retrovie_round")
          && ns.get("retrovie_round").getAsInt() == round) {
        ns.getAsJsonArray("retrovie").forEach(e -> ids.add(e.getAsString()));
      }
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
        if (from.equals(gather)) {
          held.add(u);
          there++;
          continue;
        }
        if (!Matches.unitHasMovementLeft().test(u)) {
          continue;
        }
        final Optional<Territory> to = passo(u, from, gather);
        if (to.isEmpty()) {
          refused++;
          continue;
        }
        held.add(u);
        moved++;
        moves.merge(from.getName() + " -> " + to.get().getName(), 1, Integer::sum);
        if (!ns.has("unita") || !obj(ns, "unita").has(u.getId().toString())) {
          // a rear unit routed now becomes an operation's unit (followed for the losses)
          obj(ns, "unita").addProperty(u.getId().toString(), u.getType().getName());
        }
      }
      potaSeguite(obj(ns, "unita"), cfg.maxSeguite());
      turn.addProperty("instradate", moved);
      turn.addProperty("gia_al_raduno", there);
      turn.addProperty("senza_passo_sicuro", refused);
      lines.add(
          "  Instradamento verso il raduno "
              + gather.getName()
              + " (tappa "
              + stage.getName()
              + "): "
              + moved
              + " unita' mosse"
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
              + " gia' al raduno, "
              + refused
              + " senza passo sicuro (restano a Hard)");
      salvaNc(turn);
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
