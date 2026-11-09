package games.strategy.triplea.ai.pro;

import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.named;

import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.util.ProPurchaseUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * Test only: makes the Pro AI's purchase reproducible without touching the Pro code.
 *
 * <p>{@code ProPurchaseUtils.randomizePurchaseOption} picks one option with {@code Math.random}
 * from a {@code HashMap} keyed by {@code ProPurchaseOption} (identity hash code), so two runs of
 * the same purchase can buy different units: both the random number and the order of the options
 * change. Inside {@link #with}, the method (redefined once in the test JVM with a Byte Buddy
 * advice) draws from a {@link Random} with the given seed and walks the options sorted by
 * production rule: same weights as the original (option chance = efficiency / total), but the
 * same inputs always give the same choice. Outside {@link #with}, and on any other thread, the
 * original method runs unchanged.
 */
public final class SeededPurchaseChoice {
  private static final ThreadLocal<Random> CURRENT = new ThreadLocal<>();
  private static final ThreadLocal<int[]> CHOICES = ThreadLocal.withInitial(() -> new int[1]);
  private static boolean installed;

  private SeededPurchaseChoice() {}

  /**
   * Runs {@code action} with the purchase choices drawn from {@code seed}. A purchase that follows
   * the orders of a strategy plan may make no random choice: {@link #choicesOfLastRun} tells.
   */
  static <T> T with(final long seed, final Supplier<T> action) {
    install();
    CURRENT.set(new Random(seed));
    CHOICES.get()[0] = 0;
    try {
      return action.get();
    } finally {
      CURRENT.remove();
    }
  }

  /** Seeded purchase choices of the last {@link #with} on this thread (0: hook not used). */
  static int choicesOfLastRun() {
    return CHOICES.get()[0];
  }

  private static synchronized void install() {
    if (installed) {
      return;
    }
    // retransformation with the class format frozen: only the method body changes
    new AgentBuilder.Default()
        .disableClassFormatChanges()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
        .type(is(ProPurchaseUtils.class))
        .transform(
            (builder, type, loader, module, domain) ->
                builder.visit(Advice.to(Hook.class).on(named("randomizePurchaseOption"))))
        .installOn(ByteBuddyAgent.install());
    installed = true;
  }

  /**
   * The seeded choice, or null (the original method runs) when the thread has no seed. Same rule
   * as the original: cumulative chances in percent, the first option whose bound is at least the
   * random number, any option if none (rounding).
   */
  @Nullable
  public static Optional<ProPurchaseOption> pick(
      final Map<ProPurchaseOption, Double> efficiencies) {
    final Random random = CURRENT.get();
    if (random == null) {
      return null;
    }
    CHOICES.get()[0]++;
    final double total = efficiencies.values().stream().mapToDouble(Double::doubleValue).sum();
    if (total == 0) {
      return Optional.empty();
    }
    final List<Map.Entry<ProPurchaseOption, Double>> options =
        new ArrayList<>(efficiencies.entrySet());
    options.sort(
        Comparator.comparing(
                (Map.Entry<ProPurchaseOption, Double> e) -> e.getKey().getProductionRule().getName())
            .thenComparing(e -> e.getKey().getUnitType().getName()));
    final double number = random.nextDouble() * 100;
    double upperBound = 0;
    for (final Map.Entry<ProPurchaseOption, Double> e : options) {
      upperBound += e.getValue() / total * 100;
      if (number <= upperBound) {
        return Optional.of(e.getKey());
      }
    }
    return Optional.of(options.get(options.size() - 1).getKey());
  }

  /** Inlined into {@code randomizePurchaseOption}: skips the original body when seeded. */
  public static final class Hook {
    private Hook() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static Optional<ProPurchaseOption> enter(
        @Advice.Argument(0) final Map<ProPurchaseOption, Double> efficiencies) {
      return pick(efficiencies);
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final Optional<ProPurchaseOption> picked,
        @Advice.Return(readOnly = false) Optional<ProPurchaseOption> returned) {
      if (picked != null) {
        returned = picked;
      }
    }
  }
}
