package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.pro.util.ProPurchaseUtils;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step PV1 (B, switch {@code capitale_oltranza}, data: Russia). While the nation's capital is
 * threatened (the realistic attack of the data's enemies next turn wins more than {@code
 * soglia_minaccia_pct}, Hard's estimate {@link LeveItalia#minaccia}):
 *
 * <ul>
 *   <li>B1: no land unit leaves the capital: in the combat move only for an attack with win at
 *       least {@code soglia_contrattacco_pct} on a territory next to the capital (else the attack
 *       is dropped); in the non-combat move every land unit in the capital is held;
 *   <li>B2: the own land units that reach the capital this turn by land go back to it (legal moves
 *       through own or allied land, before Hard's non-combat move);
 *   <li>B3: every PU buys the land type with the most defense per PU, placed in the capital;
 *   <li>B4: with the capital lost nothing of this applies (Hard).
 * </ul>
 */
public final class CapitaleOltranza {
  private CapitaleOltranza() {}

  static final String ETICHETTA = "PV1 capitale_oltranza";

  static boolean attivo(final GameState data, @Nullable final GamePlayer player) {
    return LevePv1.attiva(
        data,
        player,
        StrategaInterruttori.CAPITALE_OLTRANZA,
        LevePv1.Config.get().capitaleNazione());
  }

  // ======================================================================== pure rules

  /** Threatened (pure rule): the enemy's win above the threshold. */
  static boolean minacciata(final double win, final double threshold) {
    return win > threshold;
  }

  /**
   * A capital unit may take part in an attack (pure rule): only on a territory next to the capital
   * with the estimated win at least {@code threshold}.
   */
  static boolean puoAttaccare(final boolean adjacent, final double win, final double threshold) {
    return adjacent && win >= threshold;
  }

  /** The type with the most defense per PU (ties: the cheaper, then the name), if any. */
  static Optional<String> migliorDifesa(final Map<String, double[]> defenseAndCost) {
    return defenseAndCost.entrySet().stream()
        .filter(e -> e.getValue()[1] > 0 && e.getValue()[0] > 0)
        .min(
            Comparator.comparingDouble(
                    (Map.Entry<String, double[]> e) -> -e.getValue()[0] / e.getValue()[1])
                .thenComparingDouble(e -> e.getValue()[1])
                .thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey);
  }

  // ===================================================================== turn's state

  /** The state of one turn, kept by the tuning (also read on Hard's copy of the game). */
  public static final class Stato {
    int round = -1;
    @Nullable String nazione;
    boolean minacciata;
    double vittoria;
    @Nullable String capitale;
    @Nullable ProPurchaseOrders ordini;
    @Nullable JsonObject riga;
    @Nullable ProPurchaseOrders.Result risultato;
    int attacchiTolti;

    void pulisci() {
      round = -1;
      nazione = null;
      minacciata = false;
      vittoria = 0;
      capitale = null;
      ordini = null;
      riga = null;
      risultato = null;
      attacchiTolti = 0;
    }

    boolean vale(final GameState data, @Nullable final GamePlayer player) {
      return player != null
          && player.getName().equals(nazione)
          && data.getSequence().getRound() == round;
    }
  }

  // ======================================================================== estimates

  /** The nation's capital if it still owns it. */
  static Optional<Territory> capitale(final GameState data, final GamePlayer player) {
    return TerritoryAttachment.getAllCapitals(player, data.getMap()).stream()
        .filter(t -> t.isOwnedBy(player))
        .findFirst();
  }

  /** The win of the data's enemies' best attack on {@code capital} against its units now. */
  static double vittoria(
      final ProData proData, final ProOddsCalculator calc, final Territory capital) {
    final GamePlayer player = proData.getPlayer();
    final List<String> enemies = LevePv1.Config.get().capitaleNemici();
    final LeveItalia.Minaccia all = LeveItalia.minaccia(proData, calc, capital);
    final LeveItalia.Minaccia m =
        new LeveItalia.Minaccia(
            all.attaccanti().stream()
                .filter(u -> enemies.contains(u.getOwner().getName()))
                .toList(),
            all.bombardamento().stream()
                .filter(u -> enemies.contains(u.getOwner().getName()))
                .toList());
    final List<Unit> def = capital.getMatches(Matches.alliedUnit(player));
    return LeveItalia.vittoria(proData, calc, capital, m, def);
  }

  private static Predicate<Unit> terra(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player)
        .and(Matches.unitIsLand())
        .and(Matches.unitIsNotInfrastructure());
  }

  // ==================================================================== the purchase

  /** At the start of the purchase (real board): the threat and, if threatened, the orders. */
  static void piano(final ProData proData, final ProOddsCalculator calc, final Stato st) {
    st.pulisci();
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final LevePv1.Config cfg = LevePv1.Config.get();
    st.round = data.getSequence().getRound();
    st.nazione = player.getName();
    final JsonObject row = new JsonObject();
    st.riga = row;
    final Optional<Territory> cap = capitale(data, player);
    if (cap.isEmpty()) {
      row.addProperty("caduta", true);
      row.addProperty("nota", "capitale persa: decide Hard (B4)");
      return;
    }
    final Territory capital = cap.get();
    st.capitale = capital.getName();
    st.vittoria = vittoria(proData, calc, capital);
    st.minacciata = minacciata(st.vittoria, cfg.sogliaMinaccia());
    row.addProperty("caduta", false);
    row.addProperty("capitale", capital.getName());
    row.addProperty("minaccia_pct", LevePv1.r1(st.vittoria));
    row.addProperty("minacciata", st.minacciata);
    row.addProperty("unita_in_capitale", capital.getMatches(terra(player)).size());
    if (!st.minacciata) {
      return;
    }
    final int pu = player.getResources().getQuantity(Constants.PUS);
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
    final Map<String, double[]> options = new LinkedHashMap<>();
    for (final var e : costs.entrySet()) {
      final Optional<UnitType> type = data.getUnitTypeList().getUnitType(e.getKey());
      if (type.isEmpty()) {
        continue;
      }
      final UnitAttachment ua = type.get().getUnitAttachment();
      final Unit probe = type.get().createTemp(1, player).get(0);
      if (ua.isSea()
          || ua.isAir()
          || ua.isInfrastructure()
          || ua.isConstruction()
          || Matches.unitIsAaForAnything().test(probe)) {
        continue;
      }
      options.put(
          e.getKey(),
          new double[] {
            ua.getDefense(player) * (double) Math.max(1, ua.getDefenseRolls(player)), e.getValue()
          });
    }
    final Optional<String> best = migliorDifesa(options);
    final Map<Territory, ProPurchaseTerritory> factories =
        ProPurchaseUtils.findPurchaseTerritories(proData, player);
    final int places =
        factories.containsKey(capital) ? factories.get(capital).getRemainingUnitProduction() : 0;
    row.addProperty("posti_in_capitale", places);
    if (best.isEmpty() || places <= 0) {
      row.addProperty("nota", "nessun posto o nessun tipo di difesa: acquisto di Hard");
      return;
    }
    final int n = Math.min(places, pu / Math.max(1, costs.get(best.get())));
    row.addProperty("tipo_difesa", best.get());
    row.addProperty("unita_ordinate", n);
    if (n <= 0) {
      return;
    }
    st.ordini =
        new ProPurchaseOrders(
            ETICHETTA + " " + player.getName(),
            List.of(),
            List.of(
                new ProPurchaseOrders.Mandatory(
                    "pv1 capitale " + best.get(), List.of(best.get()), n, capital.getName())),
            List.of(capital.getName()),
            List.of(),
            100);
  }

  static Optional<ProPurchaseOrders> ordini(final ProData proData, final Stato st) {
    if (st.ordini == null || !st.vale(proData.getData(), proData.getPlayer())) {
      return Optional.empty();
    }
    return Optional.of(st.ordini);
  }

  static void acquistato(
      final ProPurchaseOrders orders, final ProPurchaseOrders.Result result, final Stato st) {
    if (st.ordini != null && orders.label().startsWith(st.ordini.label())) {
      st.risultato = result;
    }
  }

  static void registraAcquisto(final GameState data, final GamePlayer player, final Stato st) {
    if (st.riga == null || !st.vale(data, player)) {
      return;
    }
    final JsonObject row = st.riga;
    int units = 0;
    if (st.risultato != null) {
      final JsonObject bought = new JsonObject();
      for (final var e : st.risultato.units().entrySet()) {
        if (e.getKey().startsWith("pv1 capitale")) {
          for (final var t : e.getValue().entrySet()) {
            bought.addProperty(t.getKey(), t.getValue());
            units += t.getValue();
          }
        }
      }
      row.add("comprato", bought);
    }
    row.addProperty("unita_comprate", units);
    final boolean fallen = row.has("caduta") && row.get("caduta").getAsBoolean();
    LevePv1.registra(
        data,
        player,
        LevePv1.TIPO_CAPITALE,
        fallen
            ? "PV1_CAPITALE_CADUTA"
            : st.minacciata ? "PV1_CAPITALE_MINACCIATA" : "PV1_CAPITALE_SICURA",
        "capitale tenuta a oltranza: minaccia stimata, unita' in capitale, acquisti",
        row);
  }

  // ===================================================================== combat move

  /**
   * B1 in the combat move (Hard's attacks, also on Hard's copy of the game): an attack that uses a
   * land unit of the capital is dropped unless it is on a territory next to the capital with the
   * estimated win at least the counterattack threshold.
   */
  static void rivedi(
      final Stato st, final ProData proData, final Map<Territory, ProTerritory> attackMap) {
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    if (!st.minacciata || st.capitale == null || !st.vale(data, player)) {
      return;
    }
    final Territory capital = data.getMap().getTerritoryOrNull(st.capitale);
    if (capital == null || !capital.isOwnedBy(player)) {
      return;
    }
    final double threshold = LevePv1.Config.get().sogliaContrattacco();
    for (final ProTerritory patd : attackMap.values()) {
      final boolean usesCapital =
          patd.getUnits().stream()
              .anyMatch(u -> terra(player).test(u) && capital.equals(proData.getUnitTerritory(u)));
      if (!usesCapital) {
        continue;
      }
      final double win =
          patd.getBattleResult() == null ? 0 : patd.getBattleResult().getWinPercentage();
      final boolean adjacent = data.getMap().getNeighbors(capital).contains(patd.getTerritory());
      if (puoAttaccare(adjacent, win, threshold)) {
        continue;
      }
      patd.getUnits().clear();
      patd.getBombers().clear();
      patd.getAmphibAttackMap().clear();
      patd.getBombardTerritoryMap().clear();
      st.attacchiTolti++;
    }
  }

  // ================================================================= non-combat move

  /**
   * B1 and B2 before Hard's non-combat move: with the capital threatened now, the land units that
   * reach it by land go back to it, then every land unit in the capital is held (returned). Nothing
   * if the capital is lost or not threatened.
   */
  static Set<Unit> nonCombattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate del,
      final Stato st) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final LevePv1.Config cfg = LevePv1.Config.get();
    final Optional<Territory> cap = capitale(data, player);
    final JsonObject row = new JsonObject();
    if (cap.isEmpty()) {
      return Set.of();
    }
    final Territory capital = cap.get();
    final double win = vittoria(proData, calc, capital);
    row.addProperty("capitale", capital.getName());
    row.addProperty("minaccia_pct", LevePv1.r1(win));
    row.addProperty("attacchi_tolti", st.vale(data, player) ? st.attacchiTolti : 0);
    if (!minacciata(win, cfg.sogliaMinaccia())) {
      row.addProperty("minacciata", false);
      LevePv1.registra(
          data,
          player,
          LevePv1.TIPO_CAPITALE,
          "PV1_CAPITALE_NC_SICURA",
          "capitale non minacciata nel non combattimento: decide Hard",
          row);
      return Set.of();
    }
    row.addProperty("minacciata", true);
    final int before = capital.getMatches(terra(player)).size();
    int back = 0;
    final Predicate<Territory> through =
        x ->
            !x.isWater()
                && (x.isOwnedBy(player) || player.isAllied(x.getOwner()))
                && Matches.territoryIsPassableAndNotRestricted(player).test(x);
    final List<Territory> lands = new ArrayList<>(data.getMap().getTerritories());
    lands.sort(Comparator.comparing(Territory::getName));
    for (final Territory from : lands) {
      if (from.isWater() || from.equals(capital)) {
        continue;
      }
      final List<Unit> units =
          from.getMatches(
              terra(player)
                  .and(Matches.unitHasMovementLeft())
                  .and(Matches.unitIsBeingTransported().negate()));
      if (units.isEmpty()) {
        continue;
      }
      for (final Unit u : units) {
        final Optional<Route> r =
            data.getMap().getRouteForUnits(from, capital, through, List.of(u), player);
        if (r.isEmpty() || !Matches.unitHasEnoughMovementForRoute(r.get()).test(u)) {
          continue;
        }
        if (del.performMove(new MoveDescription(List.of(u), r.get())).isEmpty()) {
          back++;
        }
      }
    }
    final Set<Unit> held = new LinkedHashSet<>(capital.getMatches(terra(player)));
    row.addProperty("unita_in_capitale_prima", before);
    row.addProperty("rientrate", back);
    row.addProperty("unita_in_capitale_dopo", held.size());
    row.addProperty("minaccia_dopo_pct", LevePv1.r1(vittoria(proData, calc, capital)));
    LevePv1.registra(
        data,
        player,
        LevePv1.TIPO_CAPITALE,
        "PV1_CAPITALE_TENUTA",
        "capitale minacciata: le unita' di terra rientrano e nessuna esce",
        row);
    return held;
  }
}
