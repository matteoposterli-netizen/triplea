package games.strategy.triplea.odds.calculator;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.TerritoryEffect;
import games.strategy.engine.data.Unit;
import games.strategy.engine.random.PlainRandomSource;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * Single-threaded battle calculator with seeded dice, so AI decisions that run battle simulations
 * can be replayed exactly in tests. The dice sequence restarts on every {@link #setGameData}.
 */
public final class SeededBattleCalculator implements IBattleCalculator {
  private final long seed;
  private @Nullable BattleCalculator calculator;

  public SeededBattleCalculator(final long seed) {
    this.seed = seed;
  }

  @Override
  public AggregateResults calculate(
      final GamePlayer attacker,
      final GamePlayer defender,
      final Territory location,
      final Collection<Unit> attacking,
      final Collection<Unit> defending,
      final Collection<Unit> bombarding,
      final Collection<TerritoryEffect> territoryEffects,
      final boolean retreatWhenOnlyAirLeft,
      final int runCount) {
    return calculator()
        .calculate(
            attacker,
            defender,
            location,
            stableOrder(attacking),
            stableOrder(defending),
            stableOrder(bombarding),
            territoryEffects,
            retreatWhenOnlyAirLeft,
            runCount);
  }

  /**
   * Unit ids are random on every map load, so the callers' sets iterate in a different order each
   * time; sorting interchangeable units the same way keeps the dice landing on the same units.
   */
  private static List<Unit> stableOrder(final Collection<Unit> units) {
    return units.stream()
        .sorted(
            Comparator.comparing((Unit u) -> u.getType().getName())
                .thenComparing(u -> u.getOwner().getName())
                .thenComparingInt(Unit::getHits)
                .thenComparing(u -> u.getAlreadyMoved().toPlainString())
                .thenComparing(u -> String.valueOf(u.getTransportedBy() != null)))
        .toList();
  }

  @Override
  public CompletableFuture<Boolean> setGameData(final @Nullable GameData data) {
    if (data == null) {
      calculator = null;
    } else {
      calculator = new BattleCalculator(data);
      calculator.setRandomSource(new PlainRandomSource(seed));
    }
    return CompletableFuture.completedFuture(true);
  }

  private BattleCalculator calculator() {
    if (calculator == null) {
      throw new IllegalStateException("setGameData first");
    }
    return calculator;
  }

  @Override
  public void cancel() {
    // runs synchronously: nothing to cancel
  }

  @Override
  public void setKeepOneAttackingLandUnit(final boolean value) {
    calculator().setKeepOneAttackingLandUnit(value);
  }

  @Override
  public void setAmphibious(final boolean value) {
    calculator().setAmphibious(value);
  }

  @Override
  public void setRetreatAfterRound(final int value) {
    calculator().setRetreatAfterRound(value);
  }

  @Override
  public void setRetreatAfterXUnitsLeft(final int value) {
    calculator().setRetreatAfterXUnitsLeft(value);
  }

  @Override
  public void setAttackerOrderOfLosses(final String value) {
    calculator().setAttackerOrderOfLosses(value);
  }

  @Override
  public void setDefenderOrderOfLosses(final String value) {
    calculator().setDefenderOrderOfLosses(value);
  }
}
