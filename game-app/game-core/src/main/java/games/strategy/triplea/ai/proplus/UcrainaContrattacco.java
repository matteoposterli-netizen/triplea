package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Step P15, lever A {@link StrategaInterruttori#UCRAINA_CONTRATTACCO} (Russia, in the combat move
 * AFTER Hard's: every attack of Hard stays). Targets in order: Ukraine and Novgorod if the Axis
 * owns them, then the Axis territories next to Ukraine. A target already attacked by Hard (own
 * units there) is left to Hard. For each target the Russian land units that reach it this turn are
 * added (first those outside the capital, nearest and strongest first) until the estimated win
 * reaches {@code vittoria_sufficiente_pct}; the capital's units are added one at a time only while
 * the attack is not yet good enough and Moscow's enemy win next turn without them stays within the
 * reference + {@code margine_mosca_punti} (A2). The attack is made when the win is at least {@code
 * soglia_vittoria_pct} and the Axis TUV killed is at least {@code rapporto_tuv} x the Russian TUV
 * lost (A1). Only at war with Germany; never a neutral (A3). The real outcome is written at the
 * start of the non-combat move.
 */
public final class UcrainaContrattacco {
  private UcrainaContrattacco() {}

  /** Attacks executed this turn, by game: their real outcome at the non-combat move. */
  private static final Map<GameState, List<Attacco>> ESEGUITI =
      Collections.synchronizedMap(new WeakHashMap<>());

  record Attacco(
      String bersaglio,
      Map<String, Integer> attaccanti,
      Map<String, Integer> difensori,
      double vittoria,
      double uccisoStimato,
      double persoStimato) {}

  /** The estimate of one attack. */
  record Stima(double vittoria, double ucciso, double perso) {}

  static boolean attivo(final GameState data, final GamePlayer player) {
    return LeveP15.attiva(data, player, StrategaInterruttori.UCRAINA_CONTRATTACCO);
  }

  /** The targets in order (A1, A3): Axis-owned, at war, never a neutral. */
  static List<Territory> bersagli(final GameState data, final GamePlayer player) {
    final LeveP15.Config c = LeveP15.Config.get();
    final List<Territory> out = new ArrayList<>();
    final Territory ukraine = data.getMap().getTerritoryOrNull(c.ucraina());
    final Territory novgorod = data.getMap().getTerritoryOrNull(c.novgorod());
    for (final Territory t : List.of(ukraine, novgorod)) {
      if (t != null && dellAsse(data, player, t)) {
        out.add(t);
      }
    }
    if (ukraine != null) {
      final List<Territory> next =
          new ArrayList<>(data.getMap().getNeighbors(ukraine, Matches.territoryIsLand()));
      next.sort(Comparator.comparing(Territory::getName));
      for (final Territory t : next) {
        if (!out.contains(t) && dellAsse(data, player, t)) {
          out.add(t);
        }
      }
    }
    return out;
  }

  /** Owned by an Axis nation at war with {@code player} (never a neutral). */
  static boolean dellAsse(final GameState data, final GamePlayer player, final Territory t) {
    final GamePlayer owner = t.getOwner();
    return !t.isWater()
        && owner != null
        && !owner.isNull()
        && player.isAtWar(owner)
        && ProPlusSettings.allianceOf(data, owner).map("Axis"::equals).orElse(false);
  }

  /**
   * After Hard's combat move: the extra attacks. Returns the units moved. Never throws.
   *
   * @return the units sent to the extra attacks
   */
  public static Set<Unit> combattimento(
      final ProData proData, final ProOddsCalculator calc, final IMoveDelegate del) {
    final Set<Unit> moved = new LinkedHashSet<>();
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    try {
      ESEGUITI.remove(data);
      if (!LeveP15.inGuerra(data, player)) {
        return moved;
      }
      final LeveP15.Config c = LeveP15.Config.get();
      final Optional<Territory> capital = LeveP15.capitale(data, player);
      LeveItalia.Minaccia moscow = null;
      double reference = 0;
      if (capital.isPresent()) {
        moscow = LeveItalia.minaccia(proData, calc, capital.get());
        reference =
            LeveP15.minaccia(
                proData, calc, capital.get(), moscow, LeveP15.difensori(capital.get(), player));
      }
      final List<Attacco> done = new ArrayList<>();
      for (final Territory target : bersagli(data, player)) {
        final JsonObject row = new JsonObject();
        row.addProperty("bersaglio", target.getName());
        row.addProperty("proprietario", target.getOwner().getName());
        if (target.anyUnitsMatch(
            Matches.unitIsOwnedBy(player).and(Matches.unitIsNotInfrastructure()))) {
          row.addProperty("nota", "gia' attaccato da Hard");
          LeveP15.registra(
              data,
              player,
              LeveP15.TIPO_CONTRATTACCO,
              "P15_A_HARD",
              "bersaglio gia' attaccato da Hard",
              row);
          continue;
        }
        final List<Unit> defenders = target.getMatches(Matches.enemyUnit(player));
        final List<Unit> fighting =
            defenders.stream().filter(Matches.unitIsNotInfrastructure()).toList();
        final Map<Unit, Route> routes = new LinkedHashMap<>();
        final List<Unit> outside = new ArrayList<>();
        final List<Unit> inside = new ArrayList<>();
        candidati(data, player, target, capital.orElse(null), moved, routes, outside, inside);
        row.addProperty("difensori", fighting.size());
        row.addProperty("tuv_difensori", LeveP15.tuv(proData, fighting));
        row.addProperty("candidati_fuori_capitale", outside.size());
        row.addProperty("candidati_capitale", inside.size());
        if (outside.isEmpty() && inside.isEmpty()) {
          row.addProperty("motivo", "nessuna unita' russa arriva");
          LeveP15.registra(
              data,
              player,
              LeveP15.TIPO_CONTRATTACCO,
              "P15_A_RINUNCIA",
              "nessuna unita' russa arriva",
              row);
          continue;
        }
        final List<Unit> force = new ArrayList<>();
        Stima est = new Stima(0, 0, 0);
        int fromCapital = 0;
        double moscowWithout = reference;
        String reason = "";
        if (fighting.isEmpty()) {
          // an empty Axis territory: one unit takes it (cheapest first)
          final List<Unit> all = new ArrayList<>(outside);
          if (all.isEmpty()) {
            all.addAll(inside);
          }
          all.sort(Comparator.comparingInt(u -> LeveP15.tuv(proData, List.of(u))));
          force.add(all.get(0));
          fromCapital = inside.contains(all.get(0)) ? 1 : 0;
          est = new Stima(100, 0, 0);
        } else {
          for (int i = 0; i < outside.size(); i++) {
            force.add(outside.get(i));
            final boolean last = i == outside.size() - 1;
            if (force.size() < Math.min(fighting.size(), outside.size()) && !last) {
              continue;
            }
            if (!last && (force.size() - fighting.size()) % 2 != 0) {
              continue;
            }
            est = stima(proData, calc, target, force, defenders);
            if (est.vittoria() >= c.vittoriaSufficiente()) {
              break;
            }
          }
          if (!esegue(est, c)) {
            for (final Unit u : inside) {
              final List<Unit> left = new ArrayList<>(LeveP15.difensori(capital.get(), player));
              force.stream().filter(inside::contains).forEach(left::remove);
              left.remove(u);
              final double without = LeveP15.minaccia(proData, calc, capital.get(), moscow, left);
              if (!LeveP15.moscaProtetta(without, reference, c.margineMosca())) {
                reason = "Mosca scoperta oltre il margine";
                break;
              }
              force.add(u);
              fromCapital++;
              moscowWithout = without;
              est = stima(proData, calc, target, force, defenders);
              if (esegue(est, c)) {
                break;
              }
            }
          }
        }
        row.addProperty("unita", force.size());
        row.addProperty("dalla_capitale", fromCapital);
        row.addProperty("tuv_attaccanti", LeveP15.tuv(proData, force));
        row.addProperty("vittoria_stimata", LeveP15.r1(est.vittoria()));
        row.addProperty("tuv_asse_ucciso_stimato", LeveP15.r1(est.ucciso()));
        row.addProperty("tuv_russo_perso_stimato", LeveP15.r1(est.perso()));
        row.addProperty("mosca_riferimento_pct", LeveP15.r1(reference));
        row.addProperty("mosca_senza_pct", LeveP15.r1(moscowWithout));
        if (!esegue(est, c)) {
          if (reason.isEmpty()) {
            reason =
                est.vittoria() < c.sogliaVittoria()
                    ? "vittoria stimata sotto la soglia"
                    : "scambio di TUV sfavorevole";
          }
          row.addProperty("motivo", reason);
          LeveP15.registra(data, player, LeveP15.TIPO_CONTRATTACCO, "P15_A_RINUNCIA", reason, row);
          continue;
        }
        // the moves (each one checked by the engine)
        final List<Unit> sent = new ArrayList<>();
        final List<String> refused = new ArrayList<>();
        for (final Unit u : force) {
          final Optional<String> err =
              del.performMove(new MoveDescription(List.of(u), routes.get(u)));
          if (err.isPresent()) {
            refused.add(u.getType().getName() + ": " + err.get());
          } else {
            sent.add(u);
          }
        }
        moved.addAll(sent);
        row.addProperty("mosse", sent.size());
        if (!refused.isEmpty()) {
          row.addProperty("rifiutate", String.join("; ", refused));
        }
        if (sent.isEmpty()) {
          row.addProperty("motivo", "mosse rifiutate dal motore");
          LeveP15.registra(
              data,
              player,
              LeveP15.TIPO_CONTRATTACCO,
              "P15_A_RINUNCIA",
              "mosse rifiutate dal motore",
              row);
          continue;
        }
        done.add(
            new Attacco(
                target.getName(),
                costi(proData, sent),
                costi(proData, fighting),
                est.vittoria(),
                est.ucciso(),
                est.perso()));
        LeveP15.registra(
            data,
            player,
            LeveP15.TIPO_CONTRATTACCO,
            "P15_A_ESEGUITO",
            "attacco russo in piu' (Hard non lo faceva)",
            row);
      }
      if (!done.isEmpty()) {
        ESEGUITI.put(data, done);
      }
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P15, contrattacco: " + e);
    }
    return moved;
  }

  private static boolean esegue(final Stima est, final LeveP15.Config c) {
    return LeveP15.esegue(
        est.vittoria(), est.ucciso(), est.perso(), c.sogliaVittoria(), c.rapportoTuv());
  }

  /** The battle estimate: win, Axis TUV killed and Russian TUV lost on average. */
  static Stima stima(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory target,
      final List<Unit> attackers,
      final List<Unit> defenders) {
    final ProBattleResult r =
        calc.calculateBattleResults(proData, target, attackers, defenders, List.of());
    final List<Unit> fighting =
        defenders.stream().filter(Matches.unitIsNotInfrastructure()).toList();
    final double killed =
        Math.max(
            0,
            LeveP15.tuv(proData, fighting)
                - LeveP15.tuv(
                    proData,
                    r.getAverageDefendersRemaining().stream()
                        .filter(Matches.unitIsNotInfrastructure())
                        .toList()));
    final double lost =
        Math.max(
            0,
            LeveP15.tuv(proData, attackers)
                - LeveP15.tuv(proData, r.getAverageAttackersRemaining()));
    return new Stima(r.getWinPercentage(), killed, lost);
  }

  /**
   * The own land units that reach {@code target} this turn through own or allied land without
   * enemies; split into those outside and inside the capital, each list nearest then strongest
   * first.
   */
  static void candidati(
      final GameState data,
      final GamePlayer player,
      final Territory target,
      final Territory capital,
      final Set<Unit> used,
      final Map<Unit, Route> routes,
      final List<Unit> outside,
      final List<Unit> inside) {
    final Predicate<Territory> through =
        t ->
            t.equals(target)
                || (!t.isWater()
                    && Matches.isTerritoryAllied(player).test(t)
                    && !t.anyUnitsMatch(Matches.enemyUnit(player)));
    final Predicate<Unit> land =
        Matches.unitIsOwnedBy(player)
            .and(Matches.unitIsLand())
            .and(Matches.unitIsNotInfrastructure())
            .and(Matches.unitHasMovementLeft())
            .and(u -> !u.getWasInCombat())
            .and(u -> u.getTransportedBy() == null)
            .and(u -> u.getUnitAttachment().getAttack(player) > 0);
    final Map<Unit, Integer> steps = new HashMap<>();
    for (final Territory from : data.getMap().getTerritories()) {
      if (from.isWater()
          || from.equals(target)
          || !Matches.isTerritoryAllied(player).test(from)
          || from.anyUnitsMatch(Matches.enemyUnit(player))) {
        continue;
      }
      final int distance = data.getMap().getLandDistance(from, target);
      if (distance < 0 || distance > 3) {
        continue;
      }
      for (final Unit u : from.getMatches(land)) {
        if (used.contains(u)) {
          continue;
        }
        final Optional<Route> r = data.getMap().getRouteForUnit(from, target, through, u, player);
        if (r.isEmpty() || r.get().numberOfSteps() > u.getMovementLeft().intValue()) {
          continue;
        }
        routes.put(u, r.get());
        steps.put(u, r.get().numberOfSteps());
        (from.equals(capital) ? inside : outside).add(u);
      }
    }
    final Comparator<Unit> order =
        Comparator.comparingInt((Unit u) -> steps.get(u))
            .thenComparingInt(u -> -u.getUnitAttachment().getAttack(player))
            .thenComparing(u -> u.getId().toString());
    outside.sort(order);
    inside.sort(order);
  }

  private static Map<String, Integer> costi(final ProData proData, final List<Unit> units) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    for (final Unit u : units) {
      out.put(u.getId().toString(), LeveP15.tuv(proData, List.of(u)));
    }
    return out;
  }

  /**
   * At the start of the non-combat move: the real outcome of the attacks of this turn (owner of the
   * target, TUV killed and lost: units of the battle gone from the board). Never throws.
   */
  public static void esiti(final GameState data, final GamePlayer player) {
    try {
      final List<Attacco> done = ESEGUITI.remove(data);
      if (done == null || done.isEmpty()) {
        return;
      }
      final Set<String> alive = new java.util.HashSet<>();
      for (final Territory t : data.getMap().getTerritories()) {
        t.getUnits().forEach(u -> alive.add(u.getId().toString()));
      }
      for (final Attacco a : done) {
        final Territory t = data.getMap().getTerritoryOrNull(a.bersaglio());
        int lost = 0;
        int lostUnits = 0;
        for (final var e : a.attaccanti().entrySet()) {
          if (!alive.contains(e.getKey())) {
            lost += e.getValue();
            lostUnits++;
          }
        }
        int killed = 0;
        int killedUnits = 0;
        for (final var e : a.difensori().entrySet()) {
          if (!alive.contains(e.getKey())) {
            killed += e.getValue();
            killedUnits++;
          }
        }
        final JsonObject row = new JsonObject();
        row.addProperty("bersaglio", a.bersaglio());
        row.addProperty("preso", t != null && t.isOwnedBy(player));
        row.addProperty("vittoria_stimata", LeveP15.r1(a.vittoria()));
        row.addProperty("tuv_asse_ucciso_stimato", LeveP15.r1(a.uccisoStimato()));
        row.addProperty("tuv_russo_perso_stimato", LeveP15.r1(a.persoStimato()));
        row.addProperty("tuv_asse_ucciso", killed);
        row.addProperty("tuv_russo_perso", lost);
        row.addProperty("unita_asse_uccise", killedUnits);
        row.addProperty("unita_russe_perse", lostUnits);
        LeveP15.registra(
            data,
            player,
            LeveP15.TIPO_CONTRATTACCO,
            "P15_A_ESITO",
            "esito reale dell'attacco in piu'",
            row);
      }
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P15, esiti: " + e);
    }
  }
}
