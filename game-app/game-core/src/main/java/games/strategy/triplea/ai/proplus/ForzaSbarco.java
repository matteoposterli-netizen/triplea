package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.data.ProPlaceTerritory;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.pro.util.ProPurchaseUtils;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step P5, A ({@code usa_proporzione}): a nation still neutral whose war is certain by the map's
 * rules ({@code p4.json}, {@code usa_pronti.nazioni}) reaches the war with ships, transports and
 * troops in proportion. In its neutral rounds and in its first war turn the PUs go, in this order
 * and computed again every turn on the real board, to:
 *
 * <ol>
 *   <li>transports for the land units already at home (the home territories joined by land to the
 *       port's factory): capacity = troops to carry, after the free places of the owned transports;
 *   <li>escort (warships, carrier planes in free carrier places): one unit at a time, the one that
 *       lowers the enemy's win most per PU, until the convoy holds the realistic attack of the
 *       enemy in the arrival zone (every enemy unit in reach, win at most {@code
 *       soglia_convoglio}); none if the owned escort already holds;
 *   <li>with the PUs left, full loads: a transport and the troops that fill it (the mix by Hard's
 *       own amphibious efficiency).
 * </ol>
 *
 * <p>The arrival zone: the nearest sea zone (from the port's zones) next to an enemy coast or to an
 * allied base next to the enemy, found on the map; ties: the smallest threat. In the last neutral
 * turn the troops at home are loaded on the transports in the home sea zones (a move a neutral
 * nation may make) and held out of Hard's non-combat move, so that the force can leave in the first
 * war turn. Where to go and when to land: Hard. From the turn after the first war turn: Hard alone.
 * The purchase goes through Hard's own purchase as orders ({@link ProPurchaseOrders}, plan share
 * 100%: Hard's defense only where a factory or the capital is at risk).
 */
public final class ForzaSbarco {
  private ForzaSbarco() {}

  static final String TIPO = "usa_proporzione";
  static final String ETICHETTA = "P5 forza da sbarco";

  /** The phase of the lever this turn. */
  enum Fase {
    NEUTRALE,
    ULTIMO_NEUTRALE,
    PRIMO_GUERRA
  }

  // ======================================================================== activation

  static boolean attivo(final GameState data, @Nullable final GamePlayer player) {
    return IndicazioniP4.attivo(data, player, StrategaInterruttori.USA_PROPORZIONE);
  }

  /** The lever's phase this turn (empty: it does not apply, Hard decides). */
  static Optional<Fase> fase(final GameState data, @Nullable final GamePlayer player) {
    if (!attivo(data, player)) {
      return Optional.empty();
    }
    final IndicazioniP4.Guerra g = IndicazioniP4.Config.get().guerra().get(player.getName());
    return fase(
        data.getSequence().getRound(),
        g.guerraDopoIlRound(),
        GuerraVicina.neutrale(data, player, g));
  }

  /** The phase (pure rule): neutral rounds, the last of them, the first war turn, else none. */
  static Optional<Fase> fase(final int round, final int guerraDopoIlRound, final boolean neutrale) {
    if (neutrale) {
      return Optional.of(round == guerraDopoIlRound ? Fase.ULTIMO_NEUTRALE : Fase.NEUTRALE);
    }
    return round == guerraDopoIlRound + 1 ? Optional.of(Fase.PRIMO_GUERRA) : Optional.empty();
  }

  // ======================================================================== pure rules

  /**
   * Step 1 (pure rule): the new transports of capacity {@code cap} needed to carry {@code troops}
   * (their transport costs) after the free places {@code free} of the owned transports, first fit
   * decreasing. A troop bigger than a transport is not counted.
   */
  static int trasportiNecessari(
      final List<Integer> troops, final List<Integer> free, final int cap) {
    final List<Integer> sorted = new ArrayList<>(troops);
    sorted.sort(Comparator.reverseOrder());
    final List<Integer> bins = new ArrayList<>(free);
    int added = 0;
    for (final int t : sorted) {
      if (t <= 0) {
        continue;
      }
      boolean placed = false;
      for (int i = 0; i < bins.size(); i++) {
        if (bins.get(i) >= t) {
          bins.set(i, bins.get(i) - t);
          placed = true;
          break;
        }
      }
      if (!placed && t <= cap) {
        bins.add(cap - t);
        added++;
      }
    }
    return added;
  }

  /** A unit type that may be bought: name, PUs, transport cost (land) and Hard's efficiency. */
  record Opzione(String tipo, int costo, int posto, double efficienza) {}

  /**
   * Step 3 (pure rule): the troops that fill one transport of capacity {@code cap}: one at a time
   * the option with the highest efficiency that still fits (ties: the cheaper, then the name).
   */
  static List<String> riempi(final int cap, final List<Opzione> options) {
    final List<String> out = new ArrayList<>();
    int left = cap;
    while (left > 0) {
      final int room = left;
      final Optional<Opzione> best =
          options.stream()
              .filter(o -> o.posto() > 0 && o.posto() <= room)
              .max(
                  Comparator.comparingDouble(Opzione::efficienza)
                      .thenComparing(Comparator.comparingInt(Opzione::costo).reversed())
                      .thenComparing(Opzione::tipo, Comparator.reverseOrder()));
      if (best.isEmpty()) {
        break;
      }
      out.add(best.get().tipo());
      left -= best.get().posto();
    }
    return out;
  }

  /** The escort bought (step 2). */
  record Scorta(
      Map<String, Integer> unita,
      int spesi,
      double prima,
      double dopo,
      boolean regge,
      String nota) {}

  /**
   * Step 2 (pure rule): one unit at a time among {@code types}, the one that lowers the enemy's win
   * ({@code win} of the units bought so far) most per PU, until the win is at most {@code soglia},
   * the PUs or {@code max} units end. If no single unit lowers the win (saturated), every unit of a
   * type the PUs allow is tried and the best per PU taken. Nothing if the owned force holds.
   */
  static Scorta scorta(
      final Map<String, Integer> types,
      final Function<List<String>, Double> win,
      final int budget,
      final double soglia,
      final int max) {
    final List<String> bought = new ArrayList<>();
    final double before = win.apply(List.of());
    double now = before;
    int spent = 0;
    String note = "";
    while (now > soglia && bought.size() < max) {
      final Map<String, double[]> cand = new LinkedHashMap<>();
      final Map<String, Double> after = new HashMap<>();
      for (final var e : types.entrySet()) {
        if (e.getValue() <= 0 || spent + e.getValue() > budget) {
          continue;
        }
        final List<String> f = new ArrayList<>(bought);
        f.add(e.getKey());
        final double w = win.apply(f);
        after.put(e.getKey(), w);
        cand.put(e.getKey(), new double[] {now - w, e.getValue()});
      }
      if (cand.isEmpty()) {
        note = "PU finiti";
        break;
      }
      final Optional<String> best = Risposta.miglioreAcquisto(cand);
      if (best.isPresent()) {
        bought.add(best.get());
        spent += types.get(best.get());
        now = after.get(best.get());
        continue;
      }
      // saturated: every unit of one type the PUs (and the limit) allow
      final Map<String, double[]> bulk = new LinkedHashMap<>();
      final Map<String, Integer> bulkN = new HashMap<>();
      for (final String type : cand.keySet()) {
        final int n = Math.min(max - bought.size(), (budget - spent) / types.get(type));
        if (n <= 1) {
          continue;
        }
        final List<String> f = new ArrayList<>(bought);
        for (int i = 0; i < n; i++) {
          f.add(type);
        }
        final double w = win.apply(f);
        after.put("bulk|" + type, w);
        bulk.put(type, new double[] {now - w, (double) n * types.get(type)});
        bulkN.put(type, n);
      }
      final Optional<String> b = Risposta.miglioreAcquisto(bulk);
      if (b.isEmpty()) {
        note = "nessuna unita' abbassa la vittoria nemica";
        break;
      }
      for (int i = 0; i < bulkN.get(b.get()); i++) {
        bought.add(b.get());
      }
      spent += bulkN.get(b.get()) * types.get(b.get());
      now = after.get("bulk|" + b.get());
    }
    if (now > soglia && note.isEmpty()) {
      note = bought.size() >= max ? "limite di unita'" : "PU finiti";
    }
    final Map<String, Integer> units = new TreeMap<>();
    bought.forEach(t -> units.merge(t, 1, Integer::sum));
    return new Scorta(units, spent, before, now, now <= soglia, note);
  }

  /** The three steps of one turn. */
  record Passi(
      int pu,
      int trasportiNecessari,
      int trasporti,
      int spesiTrasporti,
      Scorta scorta,
      int carichi,
      List<String> carico,
      int spesiCarichi,
      int restano) {}

  /**
   * The three steps in order (pure rule): {@code need} transports at {@code costTransport} with the
   * PUs there are, then the escort ({@code escort} for the PUs left and the transports bought),
   * then as many full loads ({@code load}, the troops of one transport, at {@code loadCost} PUs
   * with the transport) as the PUs left pay and the place left ({@code places} units) allows.
   */
  static Passi dividi(
      final int pu,
      final int need,
      final int costTransport,
      final java.util.function.BiFunction<Integer, Integer, Scorta> escort,
      final List<String> load,
      final int loadCost,
      final int places) {
    final int n1 = costTransport <= 0 ? 0 : Math.max(0, Math.min(need, pu / costTransport));
    final int after1 = pu - n1 * costTransport;
    final Scorta s = escort.apply(after1, n1);
    final int after2 = after1 - s.spesi();
    final int used = n1 + s.unita().values().stream().mapToInt(Integer::intValue).sum();
    final int perLoad = 1 + load.size();
    int loads = loadCost <= 0 || load.isEmpty() ? 0 : after2 / loadCost;
    loads = Math.max(0, Math.min(loads, (places - used) / perLoad));
    final int spent3 = loads * Math.max(0, loadCost);
    return new Passi(pu, need, n1, n1 * costTransport, s, loads, load, spent3, after2 - spent3);
  }

  // ===================================================================== turn's state

  /** The plan of one turn, kept by the tuning until the purchase (one AI = one nation). */
  public static final class Stato {
    int round = -1;
    @Nullable String nazione;
    @Nullable ProPurchaseOrders ordini;
    @Nullable JsonObject riga;
    @Nullable ProPurchaseOrders.Result risultato;

    void pulisci() {
      round = -1;
      nazione = null;
      ordini = null;
      riga = null;
      risultato = null;
    }
  }

  // ==================================================================== the purchase

  /**
   * At the start of the purchase (real board, {@code proData} initialized, {@code calc} ready): the
   * three steps of this turn as orders for Hard's purchase, kept in {@code st}.
   */
  static void piano(
      final ProData proData, final ProOddsCalculator calc, final Stato st, final Fase fase) {
    st.pulisci();
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final IndicazioniP5.Config cfg = IndicazioniP5.Config.get();
    final IndicazioniP4.Guerra g = IndicazioniP4.Config.get().guerra().get(player.getName());
    final Set<GamePlayer> enemies = nemici(data, player, g);
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
    final int pu = player.getResources().getQuantity(games.strategy.triplea.Constants.PUS);

    // the port: own factories that place in a sea zone
    final Map<Territory, ProPurchaseTerritory> factories =
        ProPurchaseUtils.findPurchaseTerritories(proData, player);
    final Set<Territory> portZones = new LinkedHashSet<>();
    final Set<Territory> portFactories = new LinkedHashSet<>();
    int places = 0;
    for (final var e : factories.entrySet()) {
      boolean sea = false;
      for (final ProPlaceTerritory p : e.getValue().getCanPlaceTerritories()) {
        if (p.getTerritory().isWater()) {
          portZones.add(p.getTerritory());
          sea = true;
        }
      }
      if (sea) {
        portFactories.add(e.getKey());
        places += e.getValue().getRemainingUnitProduction();
      }
    }
    final JsonObject row = new JsonObject();
    row.addProperty("fase", fase.name());
    row.addProperty("pu", pu);
    if (portZones.isEmpty()) {
      row.addProperty("nota", "nessuna fabbrica che piazza in mare: PU a Hard");
      st.riga = row;
      st.round = data.getSequence().getRound();
      st.nazione = player.getName();
      return;
    }
    row.add("porto", nomi(portFactories));
    row.add("zone_porto", nomi(portZones));
    row.addProperty("posti_di_piazzamento", places);

    // the troops at home and the transports owned
    final List<Unit> troops = truppeInPatria(data, player, portFactories);
    final List<Unit> transports = trasporti(data, player);
    final List<Integer> free = new ArrayList<>();
    for (final Unit t : transports) {
      free.add(liberi(t));
    }
    final Optional<ProPurchaseOption> tOption = opzioneTrasporto(proData);
    final int cap =
        tOption.map(o -> o.getUnitType().getUnitAttachment().getTransportCapacity()).orElse(0);
    final int costT = tOption.map(ProPurchaseOption::getCost).orElse(0);
    final List<Integer> troopCosts =
        troops.stream().map(u -> u.getUnitAttachment().getTransportCost()).toList();
    final int need = cap <= 0 ? 0 : trasportiNecessari(troopCosts, free, cap);
    row.add("truppe_in_patria", TrasportiProtetti.perTipo(troops));
    row.addProperty("truppe_in_patria_n", troops.size());
    row.addProperty("posti_truppe", troopCosts.stream().mapToInt(Integer::intValue).sum());
    row.addProperty("trasporti_posseduti", transports.size());
    row.addProperty("posti_liberi_posseduti", free.stream().mapToInt(Integer::intValue).sum());
    row.addProperty("capacita_trasporto", cap);
    row.addProperty("trasporti_mancanti", need);

    // the arrival zone and its threat
    final Map<Territory, Integer> dist = distanze(data, portZones, cfg.raggioZonaArrivo());
    final List<Territory> arrivals = zoneArrivo(data, player, g, enemies, dist);
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    // the arrival zones as if ours (cleared): the enemy attacks them also when empty
    tm.populateEnemyAttackOptionsOfEveryEnemy(arrivals, arrivals);
    final List<Unit> fleet = flotta(data, player);
    Territory arrival = null;
    double best = Double.MAX_VALUE;
    final Map<Territory, List<Unit>> threats = new HashMap<>();
    for (final Territory z : arrivals) {
      final List<Unit> att = minaccia(tm, z, enemies);
      threats.put(z, att);
      final List<Unit> def = difensori(data, player, g, z, fleet, transports, List.of());
      final double w = vittoria(proData, calc, z, att, def);
      if (w < best - 1e-9) {
        best = w;
        arrival = z;
      }
    }
    final List<String> escortTypes = new ArrayList<>(tipiScorta(data, player, costs));
    final Map<String, Integer> escortCosts = new LinkedHashMap<>();
    escortTypes.forEach(t -> escortCosts.put(t, costs.getOrDefault(t, 0)));
    final Territory z = arrival;
    final List<Unit> att = z == null ? List.of() : threats.get(z);
    if (z != null) {
      row.addProperty("zona_arrivo", z.getName());
      row.addProperty("distanza_zona_arrivo", dist.getOrDefault(z, -1));
      row.add("minaccia", TrasportiProtetti.perTipo(att));
      row.addProperty("minaccia_tuv", tuv(proData, att));
    } else {
      row.addProperty("zona_arrivo", "-");
    }
    row.add("flotta_posseduta", TrasportiProtetti.perTipo(fleet));

    // the load of one transport (step 3): Hard's amphibious efficiency
    final List<Opzione> landOptions = opzioniTruppe(proData);
    final List<String> load = riempi(cap, landOptions);
    int loadCost = costT;
    for (final String t : load) {
      loadCost += costs.getOrDefault(t, 0);
    }
    final Passi passi =
        dividi(
            pu,
            need,
            costT,
            (budget, n1) -> {
              if (z == null || att.isEmpty()) {
                return new Scorta(Map.of(), 0, 0, 0, true, "nessuna minaccia");
              }
              final List<Unit> newTransports =
                  tOption.isPresent()
                      ? tOption.get().getUnitType().createTemp(n1, player)
                      : List.of();
              return scorta(
                  escortCosts,
                  types -> {
                    final List<Unit> extra = new ArrayList<>(newTransports);
                    extra.addAll(temporanee(data, player, types));
                    if (!aereiImbarcabili(fleet, extra)) {
                      return 101.0;
                    }
                    return vittoria(
                        proData,
                        calc,
                        z,
                        att,
                        difensori(data, player, g, z, fleet, transports, extra));
                  },
                  budget,
                  cfg.sogliaConvoglio(),
                  cfg.maxUnitaScorta());
            },
            load,
            loadCost,
            places);
    row.addProperty("vittoria_nemica_prima", AlleatiR5.round1(passi.scorta().prima()));
    row.addProperty("vittoria_nemica_dopo", AlleatiR5.round1(passi.scorta().dopo()));
    row.addProperty("soglia", cfg.sogliaConvoglio());
    row.addProperty("convoglio_regge", passi.scorta().regge());
    if (!passi.scorta().nota().isEmpty()) {
      row.addProperty("nota_scorta", passi.scorta().nota());
    }
    final JsonObject p1 = new JsonObject();
    p1.addProperty("trasporti", passi.trasporti());
    p1.addProperty("pu", passi.spesiTrasporti());
    row.add("passo_1_trasporti", p1);
    final JsonObject p2 = new JsonObject();
    final JsonObject sc = new JsonObject();
    passi.scorta().unita().forEach(sc::addProperty);
    p2.add("unita", sc);
    p2.addProperty("pu", passi.scorta().spesi());
    row.add("passo_2_scorta", p2);
    final JsonObject p3 = new JsonObject();
    p3.addProperty("carichi", passi.carichi());
    final JsonArray l = new JsonArray();
    load.forEach(l::add);
    p3.add("carico", l);
    p3.addProperty("pu", passi.spesiCarichi());
    row.add("passo_3_carichi", p3);
    row.addProperty("pu_piano", pu - passi.restano());
    row.addProperty("pu_a_hard", passi.restano());

    // the orders: transports, escort, loads (each category in this order)
    final List<ProPurchaseOrders.Mandatory> mandatory = new ArrayList<>();
    if (tOption.isPresent() && passi.trasporti() > 0) {
      mandatory.add(
          new ProPurchaseOrders.Mandatory(
              "p5 1 trasporti", List.of(tOption.get().getUnitType().getName()), passi.trasporti()));
    }
    passi
        .scorta()
        .unita()
        .forEach(
            (type, n) ->
                mandatory.add(
                    new ProPurchaseOrders.Mandatory("p5 2 scorta " + type, List.of(type), n)));
    for (int i = 1; i <= passi.carichi(); i++) {
      mandatory.add(
          new ProPurchaseOrders.Mandatory(
              "p5 3 carico " + i + " trasporto",
              List.of(tOption.orElseThrow().getUnitType().getName()),
              1));
      final Map<String, Integer> byType = new TreeMap<>();
      load.forEach(t -> byType.merge(t, 1, Integer::sum));
      for (final var e : byType.entrySet()) {
        mandatory.add(
            new ProPurchaseOrders.Mandatory(
                "p5 3 carico " + i + " " + e.getKey(), List.of(e.getKey()), e.getValue()));
      }
    }
    final List<String> preferred = new ArrayList<>();
    if (z != null) {
      // the port zone nearest to the arrival first
      portZones.stream()
          .sorted(
              Comparator.comparingInt(
                      (Territory p) ->
                          distanze(data, List.of(p), cfg.raggioZonaArrivo())
                              .getOrDefault(z, Integer.MAX_VALUE))
                  .thenComparing(Territory::getName))
          .forEach(p -> preferred.add(p.getName()));
    }
    portFactories.forEach(f -> preferred.add(f.getName()));
    st.round = data.getSequence().getRound();
    st.nazione = player.getName();
    st.riga = row;
    if (!mandatory.isEmpty()) {
      st.ordini =
          new ProPurchaseOrders(
              ETICHETTA + " " + player.getName() + " " + fase.name(),
              List.of(),
              List.copyOf(mandatory),
              List.copyOf(preferred),
              List.of(),
              100);
    }
  }

  /** The orders of this turn for {@code proData}'s player (also on a copy of the game). */
  static Optional<ProPurchaseOrders> ordini(final ProData proData, final Stato st) {
    final GamePlayer player = proData.getPlayer();
    if (player == null
        || st.ordini == null
        || !player.getName().equals(st.nazione)
        || proData.getData().getSequence().getRound() != st.round) {
      return Optional.empty();
    }
    return Optional.of(st.ordini);
  }

  /** What Hard's purchase really bought with the orders. */
  static void acquistato(
      final ProPurchaseOrders orders, final ProPurchaseOrders.Result result, final Stato st) {
    if (orders == st.ordini) {
      st.risultato = result;
    }
  }

  /** The row of the turn (after Hard's purchase). */
  static void registraAcquisto(final GameState data, final GamePlayer player, final Stato st) {
    if (st.riga == null || st.round != data.getSequence().getRound()) {
      return;
    }
    final JsonObject row = st.riga;
    final JsonObject bought = new JsonObject();
    int spent = 0;
    if (st.risultato != null) {
      final Map<String, Map<String, Integer>> perStep = new TreeMap<>();
      st.risultato
          .units()
          .forEach(
              (cat, units) -> {
                final String step =
                    cat.startsWith("p5 1")
                        ? "trasporti"
                        : cat.startsWith("p5 2") ? "scorta" : "carichi";
                units.forEach(
                    (t, n) ->
                        perStep
                            .computeIfAbsent(step, k -> new TreeMap<>())
                            .merge(t, n, Integer::sum));
              });
      perStep.forEach(
          (k, v) -> {
            final JsonObject o = new JsonObject();
            v.forEach(o::addProperty);
            bought.add(k, o);
          });
      for (final int s : st.risultato.spent().values()) {
        spent += s;
      }
      final JsonArray missing = new JsonArray();
      st.risultato.mandatoryMissing().forEach(missing::add);
      row.add("non_comprati", missing);
    }
    row.add("comprato", bought);
    row.addProperty("pu_spesi_ordini", spent);
    IndicazioniP5.registra(
        data,
        player,
        TIPO,
        "PRIMO_GUERRA".equals(row.get("fase").getAsString())
            ? "PIANO_PRIMO_TURNO"
            : "PIANO_NEUTRALE",
        "forza da sbarco in proporzione: trasporti per le truppe in patria, scorta contro la"
            + " minaccia, carichi completi",
        row);
  }

  // ==================================================================== the load

  /**
   * The last neutral turn's non-combat move (before Hard's): the troops at home loaded on the
   * transports in the sea zones next to them; returns the transports and the troops loaded (held
   * out of Hard's non-combat move).
   */
  static Set<Unit> carica(final GameData data, final GamePlayer player, final IMoveDelegate del) {
    final Set<Unit> held = new LinkedHashSet<>();
    final Map<String, Integer> loaded = new TreeMap<>();
    int transportsUsed = 0;
    final List<Unit> transports = trasporti(data, player);
    for (final Unit tr : transports) {
      final Territory z = dove(data, tr);
      if (z == null || tr.getMovementLeft().signum() <= 0 && tr.getAlreadyMoved().signum() != 0) {
        continue;
      }
      int room = liberi(tr);
      boolean used = false;
      for (final Territory land :
          data.getMap().getNeighbors(z, Matches.territoryIsLand()).stream()
              .filter(t -> t.isOwnedBy(player) && casa(t, player))
              .sorted(Comparator.comparing(Territory::getName))
              .toList()) {
        final List<Unit> ready =
            land.getMatches(caricabile(player).and(u -> u.getMovementLeft().signum() > 0)).stream()
                .sorted(
                    Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
                        .thenComparing(u -> u.getId().toString()))
                .toList();
        for (final Unit u : ready) {
          final int c = u.getUnitAttachment().getTransportCost();
          if (c > room) {
            continue;
          }
          if (del.performMove(new MoveDescription(List.of(u), new Route(land, z), Map.of(u, tr)))
              .isEmpty()) {
            room -= c;
            held.add(u);
            used = true;
            loaded.merge(u.getType().getName(), 1, Integer::sum);
          }
        }
      }
      if (used || tr.isTransporting()) {
        held.add(tr);
        held.addAll(tr.getTransporting());
      }
      if (used) {
        transportsUsed++;
      }
    }
    final JsonObject row = new JsonObject();
    final JsonObject l = new JsonObject();
    loaded.forEach(l::addProperty);
    row.add("truppe_caricate", l);
    row.addProperty(
        "truppe_caricate_n", loaded.values().stream().mapToInt(Integer::intValue).sum());
    row.addProperty("trasporti_caricati", transportsUsed);
    row.addProperty("trasporti_posseduti", transports.size());
    row.add("posseduti", GuerraVicina.posseduti(data, player));
    IndicazioniP5.registra(
        data,
        player,
        TIPO,
        "CARICATE",
        "ultimo turno neutrale: truppe in patria caricate sui trasporti nelle zone di casa",
        row);
    return held;
  }

  // ==================================================================== helpers

  /** Sea zones within {@code max} moves of any of {@code starts} (starts = 0). */
  static Map<Territory, Integer> distanze(
      final GameState data, final Collection<Territory> starts, final int max) {
    final Map<Territory, Integer> out = new LinkedHashMap<>();
    final Deque<Territory> queue = new ArrayDeque<>();
    for (final Territory s : starts) {
      if (out.putIfAbsent(s, 0) == null) {
        queue.add(s);
      }
    }
    while (!queue.isEmpty()) {
      final Territory x = queue.poll();
      final int d = out.get(x);
      if (d >= max) {
        continue;
      }
      for (final Territory n : data.getMap().getNeighbors(x, Matches.territoryIsWater())) {
        if (!out.containsKey(n)) {
          out.put(n, d + 1);
          queue.add(n);
        }
      }
    }
    return out;
  }

  static Set<GamePlayer> nemici(
      final GameState data, final GamePlayer player, final IndicazioniP4.Guerra g) {
    final Set<GamePlayer> out = new LinkedHashSet<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (!p.equals(player) && (player.isAtWar(p) || g.nemici().contains(p.getName()))) {
        out.add(p);
      }
    }
    return out;
  }

  static boolean alleato(
      final GamePlayer player, final IndicazioniP4.Guerra g, final GamePlayer other) {
    return other.equals(player)
        || player.isAllied(other)
        || "Allied".equals(g.relazioni().get(other.getName()));
  }

  private static boolean casa(final Territory t, final GamePlayer player) {
    return TerritoryAttachment.get(t)
        .flatMap(TerritoryAttachment::getOriginalOwner)
        .map(player::equals)
        .orElse(false);
  }

  private static Predicate<Unit> caricabile(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player)
        .and(Matches.unitIsLand())
        .and(Matches.unitIsNotInfrastructure())
        .and(Matches.unitIsAaForAnything().negate())
        .and(u -> u.getUnitAttachment().getTransportCost() > 0);
  }

  /**
   * The land units at home: in the home territories (original owner = the nation, owned by it)
   * joined by land through such territories to a port factory.
   */
  static List<Unit> truppeInPatria(
      final GameState data, final GamePlayer player, final Collection<Territory> ports) {
    final Set<Territory> seen = new LinkedHashSet<>();
    final Deque<Territory> queue = new ArrayDeque<>();
    for (final Territory p : ports) {
      if (!p.isWater() && seen.add(p)) {
        queue.add(p);
      }
    }
    while (!queue.isEmpty()) {
      final Territory x = queue.poll();
      for (final Territory n : data.getMap().getNeighbors(x, Matches.territoryIsLand())) {
        if (n.isOwnedBy(player) && casa(n, player) && seen.add(n)) {
          queue.add(n);
        }
      }
    }
    final List<Unit> out = new ArrayList<>();
    seen.stream()
        .sorted(Comparator.comparing(Territory::getName))
        .forEach(t -> out.addAll(t.getMatches(caricabile(player))));
    return out;
  }

  static List<Unit> trasporti(final GameState data, final GamePlayer player) {
    final List<Unit> out = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        out.addAll(
            t.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(Matches.unitIsSeaTransportButNotCombatSeaTransport())));
      }
    }
    out.sort(Comparator.comparing(u -> u.getId().toString()));
    return out;
  }

  /** The nation's warships and its planes at sea (on carriers). */
  static List<Unit> flotta(final GameState data, final GamePlayer player) {
    final List<Unit> out = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        out.addAll(
            t.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(
                        Matches.unitIsSea()
                            .and(Matches.unitIsSeaTransportButNotCombatSeaTransport().negate())
                            .or(Matches.unitIsAir()))));
      }
    }
    out.sort(Comparator.comparing(u -> u.getId().toString()));
    return out;
  }

  static int liberi(final Unit transport) {
    int used = 0;
    for (final Unit u : transport.getTransporting()) {
      used += u.getUnitAttachment().getTransportCost();
    }
    return Math.max(0, transport.getUnitAttachment().getTransportCapacity() - used);
  }

  @Nullable
  private static Territory dove(final GameState data, final Unit u) {
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.getUnitCollection().contains(u)) {
        return t;
      }
    }
    return null;
  }

  /**
   * Arrival zones: the nearest sea zones (in {@code dist}, not the port's) next to an enemy coast
   * or to an allied base next to the enemy (an allied land territory with an enemy land neighbor or
   * a sea neighbor next to enemy land).
   */
  static List<Territory> zoneArrivo(
      final GameState data,
      final GamePlayer player,
      final IndicazioniP4.Guerra g,
      final Set<GamePlayer> enemies,
      final Map<Territory, Integer> dist) {
    final Predicate<Territory> enemyLand = t -> !t.isWater() && enemies.contains(t.getOwner());
    int min = Integer.MAX_VALUE;
    final List<Territory> out = new ArrayList<>();
    for (final var e : dist.entrySet()) {
      final Territory z = e.getKey();
      if (e.getValue() <= 0) {
        continue;
      }
      boolean ok = false;
      for (final Territory l : data.getMap().getNeighbors(z, Matches.territoryIsLand())) {
        if (enemyLand.test(l)) {
          ok = true;
          break;
        }
        if (!l.getOwner().equals(player)
            && !l.getOwner().isNull()
            && alleato(player, g, l.getOwner())
            && basePressoIlNemico(data, l, enemyLand)) {
          ok = true;
          break;
        }
      }
      if (!ok) {
        continue;
      }
      if (e.getValue() < min) {
        min = e.getValue();
        out.clear();
      }
      if (e.getValue() == min) {
        out.add(z);
      }
    }
    out.sort(Comparator.comparing(Territory::getName));
    return out;
  }

  private static boolean basePressoIlNemico(
      final GameState data, final Territory base, final Predicate<Territory> enemyLand) {
    if (!data.getMap().getNeighbors(base, enemyLand).isEmpty()) {
      return true;
    }
    for (final Territory s : data.getMap().getNeighbors(base, Matches.territoryIsWater())) {
      if (!data.getMap().getNeighbors(s, enemyLand).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /** The realistic attack on {@code z}: every unit of the enemies in reach. */
  static List<Unit> minaccia(
      final ProTerritoryManager tm, final Territory z, final Set<GamePlayer> enemies) {
    final ProTerritory max = tm.getEnemyAttackOptions().getMax(z);
    if (max == null) {
      return List.of();
    }
    return max.getMaxUnits().stream()
        .filter(u -> enemies.contains(u.getOwner()))
        .sorted(Comparator.comparing(u -> u.getId().toString()))
        .toList();
  }

  /** The convoy in {@code z}: own fleet and transports, {@code extra}, allied units there. */
  static List<Unit> difensori(
      final GameState data,
      final GamePlayer player,
      final IndicazioniP4.Guerra g,
      final Territory z,
      final List<Unit> fleet,
      final List<Unit> transports,
      final List<Unit> extra) {
    final List<Unit> out = new ArrayList<>(fleet);
    out.addAll(transports);
    out.addAll(extra);
    for (final Unit u : z.getUnits()) {
      if (!u.isOwnedBy(player)
          && alleato(player, g, u.getOwner())
          && (Matches.unitIsSea().test(u) || Matches.unitIsAir().test(u))
          && !out.contains(u)) {
        out.add(u);
      }
    }
    // the units that do not cover the transports (submarines) are left out
    out.removeIf(
        u ->
            !Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)
                && !copre(u.getUnitAttachment()));
    return out;
  }

  static double vittoria(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory z,
      final List<Unit> att,
      final List<Unit> def) {
    if (att.isEmpty()) {
      return 0;
    }
    if (def.isEmpty()) {
      return 100;
    }
    return calc.calculateBattleResults(proData, z, att, def, List.of()).getWinPercentage();
  }

  /** Whether the planes among {@code fleet} + {@code extra} fit the carriers there. */
  static boolean aereiImbarcabili(final List<Unit> fleet, final List<Unit> extra) {
    int cap = 0;
    int used = 0;
    final List<Unit> all = new ArrayList<>(fleet);
    all.addAll(extra);
    for (final Unit u : all) {
      final UnitAttachment ua = u.getUnitAttachment();
      if (Matches.unitIsCarrier().test(u)) {
        cap += ua.getCarrierCapacity();
      } else if (Matches.unitIsAir().test(u)) {
        used += Math.max(0, ua.getCarrierCost());
      }
    }
    return used <= cap;
  }

  /** Escort types: warships (no pure transport) and planes that land on carriers. */
  static List<String> tipiScorta(
      final GameState data, final GamePlayer player, final Map<String, Integer> costs) {
    final List<String> out = new ArrayList<>();
    for (final String name : new java.util.TreeSet<>(costs.keySet())) {
      final Optional<UnitType> ut = data.getUnitTypeList().getUnitType(name);
      if (ut.isEmpty()) {
        continue;
      }
      final UnitAttachment ua = ut.get().getUnitAttachment();
      final boolean warship =
          ua.isSea()
              && !(ua.getTransportCapacity() > 0 && ua.getDefense(player) <= 0)
              && !ua.isInfrastructure()
              && copre(ua);
      final boolean carrierPlane = ua.isAir() && ua.getCarrierCost() > 0;
      if (warship || carrierPlane) {
        out.add(name);
      }
    }
    return out;
  }

  /**
   * Whether a unit really covers the transports: not one that some attackers cannot hit or that can
   * evade (a submarine without an enemy destroyer: the planes' hits go to the transports, the enemy
   * "loses" the battle and the transports are sunk anyway).
   */
  static boolean copre(final UnitAttachment ua) {
    return !ua.getCanEvade() && ua.getCanNotBeTargetedBy().isEmpty();
  }

  private static List<Unit> temporanee(
      final GameState data, final GamePlayer player, final List<String> types) {
    final Map<String, Integer> n = new TreeMap<>();
    types.forEach(t -> n.merge(t, 1, Integer::sum));
    final List<Unit> out = new ArrayList<>();
    n.forEach(
        (t, k) ->
            data.getUnitTypeList()
                .getUnitType(t)
                .ifPresent(ut -> out.addAll(ut.createTemp(k, player))));
    return out;
  }

  private static Optional<ProPurchaseOption> opzioneTrasporto(final ProData proData) {
    if (proData.getPurchaseOptions() == null) {
      return Optional.empty();
    }
    return proData.getPurchaseOptions().getAllOptions().stream()
        .filter(o -> o.isTransport() && o.getQuantity() == 1)
        .filter(o -> o.getUnitType().getUnitAttachment().getTransportCapacity() > 0)
        .filter(o -> o.getUnitType().getUnitAttachment().isSea())
        .min(
            Comparator.comparingInt(ProPurchaseOption::getCost)
                .thenComparing(o -> o.getUnitType().getName()));
  }

  private static List<Opzione> opzioniTruppe(final ProData proData) {
    final List<Opzione> out = new ArrayList<>();
    if (proData.getPurchaseOptions() == null) {
      return out;
    }
    for (final ProPurchaseOption o : proData.getPurchaseOptions().getLandOptions()) {
      if (o.getQuantity() != 1
          || o.getTransportCost() <= 0
          || o.isConstruction()
          || o.getUnitType().getUnitAttachment().isInfrastructure()) {
        continue;
      }
      out.add(
          new Opzione(
              o.getUnitType().getName(),
              o.getCost(),
              o.getTransportCost(),
              o.getAmphibEfficiency(proData.getData(), List.of(), List.of())));
    }
    return out;
  }

  private static int tuv(final ProData proData, final Collection<Unit> units) {
    int s = 0;
    for (final Unit u : units) {
      s += proData.getUnitValueMap().getInt(u.getType());
    }
    return s;
  }

  private static JsonArray nomi(final Collection<Territory> ts) {
    final JsonArray a = new JsonArray();
    ts.stream().map(Territory::getName).sorted().forEach(a::add);
    return a;
  }
}
