package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.data.ProPlaceTerritory;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.util.ProPurchaseUtils;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step PV1 (A, switch {@code usa_carico}): at the start of the nation's purchase (data: the USA),
 * when empty own transports wait in the port zones (sea zones where the nation's factories place),
 * land units come first: enough to fill {@code posti_per_trasporto} places per empty transport
 * (minus the land units already next to the port), the mix of the load Hard's amphibious efficiency
 * picks, within the PUs and the land places of the port factories. Orders for Hard's purchase (they
 * come before any other order); the PUs left go to Hard as today. Transports are never removed or
 * forbidden: only the cargo is added.
 */
public final class UsaCarico {
  private UsaCarico() {}

  static final String ETICHETTA = "PV1 usa_carico";

  static boolean attivo(final GameState data, @Nullable final GamePlayer player) {
    return LevePv1.attiva(
        data, player, StrategaInterruttori.USA_CARICO, LevePv1.Config.get().caricoNazione());
  }

  // ======================================================================== pure rules

  /** Units to buy (pure rule): places per empty transport minus the units already at the port. */
  static int daComprare(final int emptyTransports, final int perTransport, final int atPort) {
    return Math.max(0, emptyTransports * perTransport - atPort);
  }

  /**
   * The types of {@code n} units (pure rule): the load of one transport ({@code load}, Hard's
   * choice) cut to {@code perTransport} units and repeated; with an empty load the cheapest type.
   */
  static List<String> tipi(
      final int n, final List<String> load, final int perTransport, final String cheapest) {
    final List<String> pattern =
        new ArrayList<>(load.subList(0, Math.min(perTransport, load.size())));
    while (pattern.size() < Math.max(1, perTransport)) {
      pattern.add(cheapest);
    }
    final List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(pattern.get(i % pattern.size()));
    }
    return out;
  }

  /** {@code types} in order while the PUs and the places last (pure rule). */
  static List<String> entro(
      final List<String> types, final Map<String, Integer> costs, final int pu, final int places) {
    final List<String> out = new ArrayList<>();
    int left = pu;
    for (final String t : types) {
      final int c = costs.getOrDefault(t, Integer.MAX_VALUE);
      if (out.size() >= places || c > left) {
        break;
      }
      out.add(t);
      left -= c;
    }
    return out;
  }

  // ===================================================================== turn's state

  /** The orders of one turn, kept by the tuning until the purchase (one AI = one nation). */
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

  /** The empty own transports in {@code zones}. */
  static List<Unit> vuoti(final GamePlayer player, final Set<Territory> zones) {
    final List<Unit> out = new ArrayList<>();
    for (final Territory z : zones) {
      out.addAll(
          z.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsSeaTransportButNotCombatSeaTransport())
                  .and(u -> u.getTransporting().isEmpty())));
    }
    out.sort(Comparator.comparing(u -> u.getId().toString()));
    return out;
  }

  /** The port: sea zones where the nation's factories place, and those factories. */
  record Porto(Set<Territory> zone, Set<Territory> fabbriche, int posti) {}

  static Porto porto(final ProData proData) {
    final Map<Territory, ProPurchaseTerritory> factories =
        ProPurchaseUtils.findPurchaseTerritories(proData, proData.getPlayer());
    final Set<Territory> zones = new LinkedHashSet<>();
    final Set<Territory> ports = new LinkedHashSet<>();
    int places = 0;
    for (final var e : factories.entrySet()) {
      boolean sea = false;
      for (final ProPlaceTerritory p : e.getValue().getCanPlaceTerritories()) {
        if (p.getTerritory().isWater()) {
          zones.add(p.getTerritory());
          sea = true;
        }
      }
      if (sea) {
        ports.add(e.getKey());
        places += e.getValue().getRemainingUnitProduction();
      }
    }
    return new Porto(zones, ports, places);
  }

  private static Predicate<Unit> caricabile(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player)
        .and(Matches.unitIsLand())
        .and(Matches.unitIsNotInfrastructure())
        .and(Matches.unitIsAaForAnything().negate())
        .and(u -> u.getUnitAttachment().getTransportCost() > 0);
  }

  /** The own land units that can be loaded next to the port zones (own land). */
  static List<Unit> alPorto(
      final GameState data, final GamePlayer player, final Set<Territory> zones) {
    final Set<Territory> lands = new LinkedHashSet<>();
    for (final Territory z : zones) {
      for (final Territory n : data.getMap().getNeighbors(z, Matches.territoryIsLand())) {
        if (n.isOwnedBy(player)) {
          lands.add(n);
        }
      }
    }
    final List<Unit> out = new ArrayList<>();
    lands.stream()
        .sorted(Comparator.comparing(Territory::getName))
        .forEach(t -> out.addAll(t.getMatches(caricabile(player))));
    return out;
  }

  /**
   * At the start of the purchase (real board, {@code proData} initialized): the land units for the
   * empty transports at the port, as orders for Hard's purchase, kept in {@code st}.
   */
  static void piano(final ProData proData, final Stato st) {
    st.pulisci();
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final LevePv1.Config cfg = LevePv1.Config.get();
    final int pu = player.getResources().getQuantity(Constants.PUS);
    final Porto port = porto(proData);
    final List<Unit> empty = vuoti(player, port.zone());
    final List<Unit> atPort = alPorto(data, player, port.zone());
    final int need = daComprare(empty.size(), cfg.postiPerTrasporto(), atPort.size());
    final JsonObject row = new JsonObject();
    row.addProperty("pu", pu);
    row.add("zone_porto", nomi(port.zone()));
    row.addProperty("trasporti_vuoti_al_porto", empty.size());
    row.addProperty("truppe_al_porto", atPort.size());
    row.addProperty("posti_per_trasporto", cfg.postiPerTrasporto());
    row.addProperty("unita_richieste", need);
    row.addProperty("posti_di_piazzamento", port.posti());
    st.round = data.getSequence().getRound();
    st.nazione = player.getName();
    st.riga = row;
    if (need <= 0 || port.fabbriche().isEmpty()) {
      return;
    }
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
    final List<ForzaSbarco.Opzione> options = ForzaSbarco.opzioniTruppe(proData);
    final int cap =
        ForzaSbarco.opzioneTrasporto(proData)
            .map(ProPurchaseOption::getUnitType)
            .map(t -> t.getUnitAttachment().getTransportCapacity())
            .orElse(0);
    final List<String> load = cap > 0 ? ForzaSbarco.riempi(cap, options) : List.of();
    final String cheapest =
        options.stream()
            .min(
                Comparator.comparingInt(ForzaSbarco.Opzione::costo)
                    .thenComparing(ForzaSbarco.Opzione::tipo))
            .map(ForzaSbarco.Opzione::tipo)
            .orElse("");
    if (cheapest.isEmpty()) {
      row.addProperty("nota", "nessuna unita' di terra trasportabile acquistabile");
      return;
    }
    final List<String> types =
        entro(tipi(need, load, cfg.postiPerTrasporto(), cheapest), costs, pu, port.posti());
    final Map<String, Integer> byType = new TreeMap<>();
    types.forEach(t -> byType.merge(t, 1, Integer::sum));
    final JsonArray l = new JsonArray();
    load.forEach(l::add);
    row.add("carico_di_hard", l);
    final JsonObject plan = new JsonObject();
    byType.forEach(plan::addProperty);
    row.add("ordini", plan);
    row.addProperty("unita_ordinate", types.size());
    if (types.isEmpty()) {
      return;
    }
    final List<ProPurchaseOrders.Mandatory> mandatory = new ArrayList<>();
    byType.forEach(
        (t, n) -> mandatory.add(new ProPurchaseOrders.Mandatory("pv1 carico " + t, List.of(t), n)));
    final List<String> preferred = new ArrayList<>();
    port.fabbriche().forEach(f -> preferred.add(f.getName()));
    st.ordini =
        new ProPurchaseOrders(
            ETICHETTA + " " + player.getName(),
            List.of(),
            List.copyOf(mandatory),
            List.copyOf(preferred),
            List.of(),
            100);
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

  /**
   * The orders of the turn put before {@code other} (the cargo first, then the other orders'
   * mandatory purchases and mix); either may be empty.
   */
  static Optional<ProPurchaseOrders> prima(
      final Optional<ProPurchaseOrders> cargo, final Optional<ProPurchaseOrders> other) {
    if (cargo.isEmpty()) {
      return other;
    }
    if (other.isEmpty()) {
      return cargo;
    }
    final ProPurchaseOrders c = cargo.get();
    final ProPurchaseOrders o = other.get();
    final List<ProPurchaseOrders.Mandatory> mandatory = new ArrayList<>(c.mandatory());
    mandatory.addAll(o.mandatory());
    final List<String> preferred = new ArrayList<>(o.preferredPlacement());
    c.preferredPlacement().stream().filter(p -> !preferred.contains(p)).forEach(preferred::add);
    return Optional.of(
        new ProPurchaseOrders(
            c.label() + " + " + o.label(),
            o.mix(),
            List.copyOf(mandatory),
            List.copyOf(preferred),
            o.stagingTerritories(),
            Math.max(c.minimumShare(), o.minimumShare())));
  }

  /** What Hard's purchase really bought with the orders. */
  static void acquistato(
      final ProPurchaseOrders orders, final ProPurchaseOrders.Result result, final Stato st) {
    if (st.ordini != null && orders.label().startsWith(st.ordini.label())) {
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
    int units = 0;
    if (st.risultato != null) {
      final Map<String, Integer> perType = new TreeMap<>();
      st.risultato
          .units()
          .forEach(
              (cat, u) -> {
                if (cat.startsWith("pv1 carico")) {
                  u.forEach((t, n) -> perType.merge(t, n, Integer::sum));
                }
              });
      perType.forEach(bought::addProperty);
      units = perType.values().stream().mapToInt(Integer::intValue).sum();
      final JsonArray missing = new JsonArray();
      st.risultato.mandatoryMissing().stream().filter(m -> m.contains("pv1")).forEach(missing::add);
      row.add("non_comprati", missing);
    }
    row.add("comprato", bought);
    row.addProperty("unita_comprate", units);
    LevePv1.registra(
        data,
        player,
        LevePv1.TIPO_CARICO,
        units > 0 ? "PV1_CARICO_COMPRATO" : "PV1_CARICO_NESSUNO",
        "carico per i trasporti vuoti al porto prima di ogni altro acquisto",
        row);
  }

  /** At the end of the nation's non-combat move: the empty transports at the port (register). */
  static void fineTurno(final ProData proData) {
    final GamePlayer player = proData.getPlayer();
    final Porto port = porto(proData);
    final JsonObject row = new JsonObject();
    row.addProperty("trasporti_vuoti_al_porto", vuoti(player, port.zone()).size());
    row.addProperty("truppe_al_porto", alPorto(proData.getData(), player, port.zone()).size());
    LevePv1.registra(
        proData.getData(),
        player,
        LevePv1.TIPO_CARICO,
        "PV1_CARICO_FINE_TURNO",
        "trasporti vuoti al porto a fine turno",
        row);
  }

  private static JsonArray nomi(final Iterable<Territory> ts) {
    final JsonArray a = new JsonArray();
    final List<String> l = new ArrayList<>();
    ts.forEach(t -> l.add(t.getName()));
    l.stream().sorted().forEach(a::add);
    return a;
  }

  static Map<String, Integer> perTipo(final List<Unit> units) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    units.forEach(u -> out.merge(u.getType().getName(), 1, Integer::sum));
    return out;
  }
}
