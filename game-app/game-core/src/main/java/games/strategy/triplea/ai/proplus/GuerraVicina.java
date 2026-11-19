package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.GameDataUtils;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.triplea.java.collections.IntegerMap;

/**
 * Step P4, A ({@code usa_pronti}): a nation still neutral whose war is certain by the map's rules
 * (data: the war comes after its turn of round N, with the relationships the map's trigger sets).
 *
 * <p>Diagnosis (Hard's log, the nation of the data in rounds 1-4): (1) while neutral, Hard counts
 * every player not allied as an enemy, so the future allies (their armies next door, their fleet)
 * are the threat its {@code defend_land} step buys against, its {@code land} step sees the coast as
 * a front at distance 1 and its sea zone "needs defenders"; (2) once at war, the nation's place
 * territories have strategic value 0 (no enemy within 3 sea zones, no enemy capital or factory by
 * sea route), so the {@code land} and {@code sea} steps buy nothing and the PUs go to {@code
 * remaining} (bombers at R4); the transports start when the sea zone's value becomes barely
 * positive (R5).
 *
 * <p>The indication: in the neutral rounds, Hard plans the purchase (and the placement it plans
 * with it) on a copy of the game with the relationships of the war to come ({@link #vista}), and
 * its place sea zones are worth the enemy coasts their transports reach in {@code raggio} zones
 * ({@link #valoreMare}, Hard's own value when higher). The purchase goes to the real delegate
 * ({@link #delegato}); the combat and factory moves planned on the copy are forgotten (the moves
 * stay a neutral nation's). Nothing else.
 */
public final class GuerraVicina {
  private GuerraVicina() {}

  /** The war view of one purchase. */
  record Vista(GameData data, GamePlayer player, IndicazioniP4.Guerra guerra) {}

  /**
   * The war view for {@code player}'s purchase now: the switch on, a round up to the one after
   * which the war comes, and not at war yet with any of the future enemies. Empty otherwise.
   */
  static Optional<Vista> vista(final GameData data, final GamePlayer player) {
    if (!IndicazioniP4.attivo(data, player, StrategaInterruttori.USA_PRONTI)) {
      return Optional.empty();
    }
    final IndicazioniP4.Guerra g = IndicazioniP4.Config.get().guerra().get(player.getName());
    if (!neutrale(data, player, g)) {
      return Optional.empty();
    }
    final Optional<GameData> copy =
        GameDataUtils.cloneGameData(
            data, GameDataManager.Options.builder().withDelegates(true).build());
    if (copy.isEmpty()) {
      return Optional.empty();
    }
    final GameData c = copy.get();
    final GamePlayer me = c.getPlayerList().getPlayerId(player.getName());
    for (final var e : g.relazioni().entrySet()) {
      final GamePlayer other = c.getPlayerList().getPlayerId(e.getKey());
      final RelationshipType type = c.getRelationshipTypeList().getRelationshipType(e.getValue());
      if (other == null || type == null || other.equals(me)) {
        continue;
      }
      final RelationshipType now = c.getRelationshipTracker().getRelationshipType(me, other);
      if (!type.equals(now)) {
        c.performChange(ChangeFactory.relationshipChange(me, other, now, type));
      }
    }
    return Optional.of(new Vista(c, me, g));
  }

  /**
   * A, first war turns: only the sea zones' value ({@code turni_di_guerra_con_valore_mare} rounds
   * after the war's round), no war view.
   */
  static boolean soloValoreMare(final GameState data, final GamePlayer player) {
    // step P5: also with "usa_proporzione" (no bombers in the first war turn)
    if (!IndicazioniP4.attivo(data, player, StrategaInterruttori.USA_PRONTI)
        && !IndicazioniP4.attivo(data, player, StrategaInterruttori.USA_PROPORZIONE)) {
      return false;
    }
    final IndicazioniP4.Guerra g = IndicazioniP4.Config.get().guerra().get(player.getName());
    final int round = data.getSequence().getRound();
    return round > g.guerraDopoIlRound()
        && round <= g.guerraDopoIlRound() + IndicazioniP4.Config.get().turniDiGuerraConValoreMare();
  }

  /**
   * The nation is in its neutral rounds: up to the war's round and not yet at war with every future
   * enemy (the map's own condition: an early declaration by one of them leaves the war incomplete,
   * and the future allies still not allied).
   */
  static boolean neutrale(
      final GameState data, final GamePlayer player, final IndicazioniP4.Guerra g) {
    if (data.getSequence().getRound() > g.guerraDopoIlRound()) {
      return false;
    }
    for (final String n : g.nemici()) {
      final GamePlayer e = data.getPlayerList().getPlayerId(n);
      if (e != null && !player.isAtWar(e)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The value of the place sea zone {@code t} for the purchase in the war view: the enemy land
   * territories next to a sea zone within {@code raggio} zones, production halved per zone, times
   * {@code fattore} (Hard's own scale: land values / 10).
   */
  static double valoreMare(final ProData proData, final Territory t) {
    final GamePlayer player = proData.getPlayer();
    final IndicazioniP4.Config c = IndicazioniP4.Config.get();
    // every sea zone: a value, not a route (Hard's own move predicate stops at the first zones
    // its relationships restrict); the route is Hard's business when it moves
    final Predicate<Territory> sea = Matches.territoryIsWater();
    final Map<Territory, Integer> dist = distanze(proData.getData(), t, sea, c.raggioZoneMare());
    final Map<Territory, Integer> coasts = new HashMap<>();
    final Predicate<Territory> enemyLand =
        Matches.territoryIsLand().and(Matches.isTerritoryEnemy(player));
    for (final var e : dist.entrySet()) {
      for (final Territory l : proData.getData().getMap().getNeighbors(e.getKey(), enemyLand)) {
        coasts.merge(l, e.getValue(), Math::min);
      }
    }
    double sum = 0;
    for (final var e : coasts.entrySet()) {
      sum += valoreCosta(TerritoryAttachment.getProduction(e.getKey()), e.getValue());
    }
    return sum * c.fattoreCosta();
  }

  /** One coast's part (pure rule): production halved for every sea zone of distance. */
  static double valoreCosta(final int production, final int distance) {
    return production / Math.pow(2, distance);
  }

  /** Sea zones within {@code max} moves of {@code start} (start = 0). */
  static Map<Territory, Integer> distanze(
      final GameState data, final Territory start, final Predicate<Territory> sea, final int max) {
    final Map<Territory, Integer> out = new LinkedHashMap<>();
    final Deque<Territory> queue = new ArrayDeque<>();
    out.put(start, 0);
    queue.add(start);
    while (!queue.isEmpty()) {
      final Territory x = queue.poll();
      final int d = out.get(x);
      if (d >= max) {
        continue;
      }
      for (final Territory n : data.getMap().getNeighbors(x, sea)) {
        if (n.isWater() && !out.containsKey(n)) {
          out.put(n, d + 1);
          queue.add(n);
        }
      }
    }
    return out;
  }

  /**
   * The real purchase delegate for a purchase planned on the war view's copy: the production rules
   * and the repair's units and rules are translated to the real game's by name and id.
   */
  static IPurchaseDelegate delegato(
      final IPurchaseDelegate real, final GameData data, final GamePlayer player) {
    return (IPurchaseDelegate)
        Proxy.newProxyInstance(
            IPurchaseDelegate.class.getClassLoader(),
            new Class<?>[] {IPurchaseDelegate.class},
            (proxy, m, args) -> {
              Object[] a = args;
              if ("purchase".equals(m.getName()) && args != null && args.length == 1) {
                a = new Object[] {regole(cast(args[0]), player)};
              } else if ("purchaseRepair".equals(m.getName()) && args != null && args.length == 1) {
                a = new Object[] {riparazioni(castRepair(args[0]), data, player)};
              }
              try {
                return m.invoke(real, a);
              } catch (final InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  @SuppressWarnings("unchecked")
  private static IntegerMap<ProductionRule> cast(final Object o) {
    return (IntegerMap<ProductionRule>) o;
  }

  @SuppressWarnings("unchecked")
  private static Map<Unit, IntegerMap<RepairRule>> castRepair(final Object o) {
    return (Map<Unit, IntegerMap<RepairRule>>) o;
  }

  private static IntegerMap<ProductionRule> regole(
      final IntegerMap<ProductionRule> in, final GamePlayer player) {
    final IntegerMap<ProductionRule> out = new IntegerMap<>();
    final List<ProductionRule> real =
        player.getProductionFrontier() == null
            ? List.of()
            : player.getProductionFrontier().getRules();
    for (final ProductionRule r : in.keySet()) {
      final ProductionRule mine =
          real.stream().filter(x -> x.getName().equals(r.getName())).findFirst().orElse(r);
      out.add(mine, in.getInt(r));
    }
    return out;
  }

  private static Map<Unit, IntegerMap<RepairRule>> riparazioni(
      final Map<Unit, IntegerMap<RepairRule>> in, final GameData data, final GamePlayer player) {
    final Map<Unit, IntegerMap<RepairRule>> out = new HashMap<>();
    final List<RepairRule> real =
        player.getRepairFrontier() == null ? List.of() : player.getRepairFrontier().getRules();
    for (final var e : in.entrySet()) {
      final Unit u = data.getUnits().get(e.getKey().getId());
      final IntegerMap<RepairRule> rules = new IntegerMap<>();
      for (final RepairRule r : e.getValue().keySet()) {
        final RepairRule mine =
            real.stream().filter(x -> x.getName().equals(r.getName())).findFirst().orElse(r);
        rules.add(mine, e.getValue().getInt(r));
      }
      out.put(u == null ? e.getKey() : u, rules);
    }
    return out;
  }

  // ======================================================================== register

  /** The nation's units now: transports, warships, land units in its home territories. */
  static JsonObject posseduti(final GameState data, final GamePlayer player) {
    int transports = 0;
    int warships = 0;
    int home = 0;
    int aboard = 0;
    int loadable = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        transports +=
            t.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsSeaTransport())).size();
        warships +=
            t.getMatches(
                    Matches.unitIsOwnedBy(player)
                        .and(Matches.unitIsSea())
                        .and(Matches.unitIsSeaTransport().negate()))
                .size();
        aboard += t.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsLand())).size();
        continue;
      }
      final int land =
          t.getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsLand())
                      .and(Matches.unitIsNotInfrastructure()))
              .size();
      final boolean mine =
          TerritoryAttachment.get(t)
              .flatMap(TerritoryAttachment::getOriginalOwner)
              .map(player::equals)
              .orElse(false);
      if (mine) {
        home += land;
      }
      final boolean nextToOwnTransport =
          !data.getMap()
              .getNeighbors(
                  t,
                  Matches.territoryIsWater()
                      .and(
                          Matches.territoryHasUnitsThatMatch(
                              Matches.unitIsOwnedBy(player).and(Matches.unitIsSeaTransport()))))
              .isEmpty();
      if (nextToOwnTransport) {
        loadable += land;
      }
    }
    final JsonObject o = new JsonObject();
    o.addProperty("trasporti", transports);
    o.addProperty("navi_da_guerra", warships);
    o.addProperty("truppe_in_patria", home);
    o.addProperty("a_bordo", aboard);
    o.addProperty("caricabili", loadable);
    return o;
  }

  /** The row of a neutral turn (after the purchase planned in the war view). */
  static void registraAcquisto(
      final GameState data,
      final GamePlayer player,
      final IndicazioniP4.Stato stato,
      final IndicazioniP4.Guerra g) {
    final JsonObject p = new JsonObject();
    p.addProperty("guerra_dopo_il_round", g.guerraDopoIlRound());
    final JsonObject steps = new JsonObject();
    final Map<String, Integer> total = new TreeMap<>();
    stato.passi.forEach(
        (step, units) -> {
          final JsonObject u = new JsonObject();
          units.forEach(u::addProperty);
          units.forEach((k, n) -> total.merge(k, n, Integer::sum));
          steps.add(step, u);
        });
    p.add("acquisti_per_passo", steps);
    final JsonObject tot = new JsonObject();
    total.forEach(tot::addProperty);
    p.add("acquisti", tot);
    final JsonObject values = new JsonObject();
    stato.valoriMare.forEach(values::addProperty);
    p.add("valore_zone_mare", values);
    p.add("posseduti", posseduti(data, player));
    IndicazioniP4.registra(
        data,
        data.getSequence().getRound(),
        player,
        "usa_pronti",
        "ACQUISTO_NEUTRALE",
        "acquisto pianificato da Hard con le relazioni della guerra in arrivo",
        p);
  }

  /** The row of a first war turn with the sea zones' value (after Hard's purchase). */
  static void registraValoreMare(
      final GameState data, final GamePlayer player, final IndicazioniP4.Stato stato) {
    final JsonObject p = new JsonObject();
    final JsonObject steps = new JsonObject();
    stato.passi.forEach(
        (step, units) -> {
          final JsonObject u = new JsonObject();
          units.forEach(u::addProperty);
          steps.add(step, u);
        });
    p.add("acquisti_per_passo", steps);
    final JsonObject values = new JsonObject();
    stato.valoriMare.forEach(values::addProperty);
    p.add("valore_zone_mare", values);
    p.add("posseduti", posseduti(data, player));
    IndicazioniP4.registra(
        data,
        data.getSequence().getRound(),
        player,
        "usa_pronti",
        "ACQUISTO_VALORE_MARE",
        "turno di guerra con il solo valore delle zone di mare (nessuna vista di guerra)",
        p);
  }

  /** The row of the first war turn (before Hard's purchase). */
  static void registraPrimoTurno(final GameState data, final GamePlayer player) {
    if (!IndicazioniP4.attivo(data, player, StrategaInterruttori.USA_PRONTI)) {
      return;
    }
    final IndicazioniP4.Guerra g = IndicazioniP4.Config.get().guerra().get(player.getName());
    if (data.getSequence().getRound() != g.guerraDopoIlRound() + 1) {
      return;
    }
    IndicazioniP4.registra(
        data,
        data.getSequence().getRound(),
        player,
        "usa_pronti",
        "PRIMO_TURNO_DI_GUERRA",
        "primo turno di guerra: trasporti, navi da guerra, truppe a bordo e caricabili",
        posseduti(data, player));
  }
}
