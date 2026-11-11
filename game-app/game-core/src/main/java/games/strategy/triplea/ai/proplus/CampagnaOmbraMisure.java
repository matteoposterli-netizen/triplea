package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TerritoryEffectHelper;
import games.strategy.triplea.delegate.battle.BattleResults;
import games.strategy.triplea.odds.calculator.AggregateResults;
import games.strategy.triplea.odds.calculator.IBattleCalculator;
import games.strategy.triplea.odds.calculator.context.model.BattleResult;
import games.strategy.triplea.odds.calculator.context.model.Lifecycle;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;

/**
 * Step R2a-bis (still IN THE SHADOW, no effect on the game): the two measures of the shadow
 * campaign planner. Measure 1: the attacker's losses of a battle from the real battle calculator
 * (the one Hard uses), per simulation, so the margins 60/70/80 are true percentiles; the three
 * models of the future defenders; the type of a territory. Measure 2: the class of a land unit Hard
 * left in a rear territory (rule 2.6, cases a and b).
 */
final class CampagnaOmbraMisure {
  private CampagnaOmbraMisure() {}

  // ======================================================================= 1.1 calculator

  /**
   * The attacker's losses (TUV) of {@code simulazioni} simulated battles, sorted ascending, and its
   * win percentage.
   *
   * @param metodo how the per-run losses were read (see {@link #simula})
   */
  record Distribuzione(double vittoriaPct, double[] perdite, int simulazioni, String metodo) {

    /** Nearest-rank percentile of the losses: monotone in {@code p} on the same runs. */
    double percentile(final int p) {
      if (perdite.length == 0) {
        return 0;
      }
      final int rank = (int) Math.ceil(p / 100.0 * perdite.length);
      return perdite[Math.max(0, Math.min(perdite.length - 1, rank - 1))];
    }

    double media() {
      return Arrays.stream(perdite).average().orElse(0);
    }
  }

  static final String METODO_LISTA = "per simulazione (risultati della lista)";
  static final String METODO_CONTATORI = "per simulazione (contatori del calcolatore a contesto)";
  static final String METODO_RIPETUTO = "ripetuto con 1 simulazione per chiamata";

  /**
   * The calculator Hard uses, behind {@link ProOddsCalculator} (private field read, nothing
   * changed). Empty if it cannot be read.
   */
  static Optional<IBattleCalculator> calcolatore(@Nullable final ProOddsCalculator calc) {
    if (calc == null) {
      return Optional.empty();
    }
    try {
      final Field f = ProOddsCalculator.class.getDeclaredField("calc");
      f.setAccessible(true);
      return Optional.ofNullable((IBattleCalculator) f.get(calc));
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * One battle with the real calculator. The per-run losses are read from the results when the
   * implementation keeps them: the list-backed results expose {@link
   * AggregateResults#getResults()}; the bounded-context results keep the runs in a private list
   * (read, not changed). Otherwise the battle is repeated {@code ripetizioni} times with one
   * simulation each (a run per call: the same distribution, slower).
   *
   * @param costo TUV of a unit type by name (the calculator's units may be copies)
   */
  static Distribuzione simula(
      final IBattleCalculator bc,
      final Territory t,
      final Collection<Unit> attaccanti,
      final Collection<Unit> difensori,
      final int simulazioni,
      final int ripetizioni,
      final ToIntFunction<String> costo) {
    final GamePlayer attacker = attaccanti.iterator().next().getOwner();
    final GamePlayer defender = difensori.iterator().next().getOwner();
    final double start = tuv(attaccanti, costo);
    final AggregateResults r =
        bc.calculate(
            attacker,
            defender,
            t,
            attaccanti,
            difensori,
            List.of(),
            TerritoryEffectHelper.getEffects(t),
            false,
            simulazioni);
    final double win = r.getAttackerWinPercent() * 100;
    final Optional<double[]> list = perditeLista(r, start, costo);
    if (list.isPresent()) {
      return new Distribuzione(win, sorted(list.get()), list.get().length, METODO_LISTA);
    }
    final Optional<double[]> counts = perditeContatori(r, start, costo);
    if (counts.isPresent()) {
      return new Distribuzione(win, sorted(counts.get()), counts.get().length, METODO_CONTATORI);
    }
    final double[] out = new double[ripetizioni];
    double wins = 0;
    for (int i = 0; i < ripetizioni; i++) {
      final AggregateResults one =
          bc.calculate(
              attacker,
              defender,
              t,
              attaccanti,
              difensori,
              List.of(),
              TerritoryEffectHelper.getEffects(t),
              false,
              1);
      wins += one.getAttackerWinPercent();
      out[i] =
          Math.max(0, start - tuv(one.getAverageAttackingUnitsRemaining(), costo) * 1.0); // one run
    }
    return new Distribuzione(100 * wins / ripetizioni, sorted(out), ripetizioni, METODO_RIPETUTO);
  }

  private static Optional<double[]> perditeLista(
      final AggregateResults r, final double start, final ToIntFunction<String> costo) {
    try {
      final List<BattleResults> runs = r.getResults();
      final double[] out = new double[runs.size()];
      for (int i = 0; i < out.length; i++) {
        out[i] = Math.max(0, start - tuv(runs.get(i).getRemainingAttackingUnits(), costo));
      }
      return out.length == 0 ? Optional.empty() : Optional.of(out);
    } catch (final UnsupportedOperationException e) {
      return Optional.empty();
    }
  }

  @SuppressWarnings("unchecked")
  private static Optional<double[]> perditeContatori(
      final AggregateResults r, final double start, final ToIntFunction<String> costo) {
    try {
      final Field f = r.getClass().getDeclaredField("runs");
      f.setAccessible(true);
      final List<BattleResult> runs = (List<BattleResult>) f.get(r);
      final double[] out = new double[runs.size()];
      for (int i = 0; i < out.length; i++) {
        double left = 0;
        for (final var e : runs.get(i).attackerSurvivors().counts().entrySet()) {
          if (e.getKey().state() != Lifecycle.DEAD) {
            left += costo.applyAsInt(e.getKey().profile().type().name()) * (double) e.getValue();
          }
        }
        out[i] = Math.max(0, start - left);
      }
      return out.length == 0 ? Optional.empty() : Optional.of(out);
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return Optional.empty();
    }
  }

  private static double[] sorted(final double[] v) {
    final double[] s = v.clone();
    Arrays.sort(s);
    return s;
  }

  static double tuv(final Collection<Unit> units, final ToIntFunction<String> costo) {
    double sum = 0;
    for (final Unit u : units) {
      sum += costo.applyAsInt(u.getType().getName());
    }
    return sum;
  }

  // ===================================================================== 1.2 territory type

  static final String CAPITALE = "capitale";
  static final String VC = "VC";
  static final String FABBRICA = "fabbrica";
  static final String ALTRO = "altro";
  static final List<String> TIPI = List.of(CAPITALE, VC, FABBRICA, ALTRO);

  /** Capital, else victory city, else factory, else other. */
  static String tipo(final Territory t) {
    final Optional<TerritoryAttachment> ta = TerritoryAttachment.get(t);
    if (ta.isPresent() && ta.get().isCapital()) {
      return CAPITALE;
    }
    if (ta.isPresent() && ta.get().getVictoryCity() > 0) {
      return VC;
    }
    if (t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
      return FABBRICA;
    }
    return ALTRO;
  }

  /**
   * Model M2: the present defenders + share {@code quota} of the increase M0 predicts (TUV). Share
   * clamped to {@code [min, max]}.
   */
  static double m2(
      final double presenti,
      final double m0,
      final double quota,
      final double min,
      final double max) {
    return presenti + Math.max(min, Math.min(max, quota)) * Math.max(0, m0 - presenti);
  }

  /**
   * The share of M2 observed in the game: real increase / increase M0 predicted, summed over the
   * checks made; {@code start} until there is a predicted increase.
   */
  static double quota(final double aumentoReale, final double aumentoPrevisto, final double start) {
    return aumentoPrevisto <= 0 ? start : aumentoReale / aumentoPrevisto;
  }

  // ============================================================================ Measure 2

  static final String APPENA_PIAZZATA = "appena piazzata";
  static final String MOSSA = "mossa nel turno";
  static final String IN_TRANSITO = "in transito";
  static final String ANTIAEREA = "antiaerea";
  static final String FERMA_DAVVERO = "ferma davvero";
  static final String FERMA_UNO = "ferma da 1 turno";
  static final String BLOCCATA = "bloccata";
  static final List<String> CLASSI =
      List.of(APPENA_PIAZZATA, MOSSA, IN_TRANSITO, ANTIAEREA, FERMA_DAVVERO, FERMA_UNO, BLOCCATA);

  /**
   * The class of a land unit at the end of its nation's turn in a rear territory.
   *
   * @param antiaerea an anti-aircraft unit (counted apart)
   * @param piazzata placed this turn (not on the board at the start of the turn)
   * @param mossa moved this turn (another territory at the start of the turn)
   * @param fermaDa turns in the same territory (this one included; the start of the game counts as
   *     the round before the first)
   * @param avvicinataPrima moved closer to the front in the turn before
   * @param puoAvvicinarsi a friendly land path within its movement lowers its distance from the
   *     closest enemy territory
   */
  static String classe(
      final boolean antiaerea,
      final boolean piazzata,
      final boolean mossa,
      final int fermaDa,
      final boolean avvicinataPrima,
      final boolean puoAvvicinarsi) {
    if (antiaerea) {
      return ANTIAEREA;
    }
    if (piazzata) {
      return APPENA_PIAZZATA;
    }
    if (mossa) {
      return MOSSA;
    }
    if (!puoAvvicinarsi) {
      return BLOCCATA;
    }
    if (fermaDa <= 1 && avvicinataPrima) {
      return IN_TRANSITO;
    }
    return fermaDa >= 2 ? FERMA_DAVVERO : FERMA_UNO;
  }

  /**
   * Land distance (through passable land territories) of every land territory from the closest one
   * of {@code enemy}; territories not connected are absent.
   */
  static Map<Territory, Integer> distanzaFronte(
      final GameMap map, final Predicate<Territory> enemy, final Predicate<Territory> land) {
    final Map<Territory, Integer> out = new HashMap<>();
    final Deque<Territory> queue = new ArrayDeque<>();
    for (final Territory t : map.getTerritories()) {
      if (land.test(t) && enemy.test(t)) {
        out.put(t, 0);
        queue.add(t);
      }
    }
    while (!queue.isEmpty()) {
      final Territory t = queue.poll();
      for (final Territory n : map.getNeighbors(t)) {
        if (land.test(n) && !out.containsKey(n)) {
          out.put(n, out.get(t) + 1);
          queue.add(n);
        }
      }
    }
    return out;
  }

  /**
   * Whether a unit at {@code from} with {@code movement} could reach, through friendly land
   * territories only, one closer to the front.
   */
  static boolean puoAvvicinarsi(
      final GameMap map,
      final Territory from,
      final int movement,
      final Predicate<Territory> friendlyLand,
      final Map<Territory, Integer> fronte) {
    final Integer here = fronte.get(from);
    if (here == null || here == 0 || movement <= 0) {
      return false;
    }
    final Map<Territory, Integer> seen = new HashMap<>();
    final Deque<Territory> queue = new ArrayDeque<>();
    seen.put(from, 0);
    queue.add(from);
    while (!queue.isEmpty()) {
      final Territory t = queue.poll();
      final int d = seen.get(t);
      if (d >= movement) {
        continue;
      }
      for (final Territory n : map.getNeighbors(t)) {
        if (seen.containsKey(n) || !friendlyLand.test(n)) {
          continue;
        }
        final Integer f = fronte.get(n);
        if (f != null && f < here) {
          return true;
        }
        seen.put(n, d + 1);
        queue.add(n);
      }
    }
    return false;
  }

  /** A short stable id of a unit for the compact state. */
  static String id(final Unit u) {
    return u.getId().toString().substring(0, 8);
  }

  /** Units sorted for a stable key of a battle (the calculator cache). */
  static String chiave(final Territory t, final Collection<Unit> a, final Collection<Unit> d) {
    final List<String> x = new ArrayList<>();
    a.forEach(u -> x.add("a" + u.getId()));
    d.forEach(u -> x.add("d" + u.getId()));
    x.sort(String::compareTo);
    return t.getName() + "|" + String.join(",", x);
  }
}
