package org.triplea.proplus.autoplay;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Step VARIANZA: how long the game took, per nation and turn, and on which machine. Only measures:
 * nothing here changes a decision. A nation's turn = every step of that nation in one round
 * (purchase, moves, battles, placement), timed around {@code runNextStep}.
 */
final class Tempi {
  /** Nation → round → milliseconds of that nation's steps in the round. */
  private final Map<String, Map<Integer, Long>> perTurn = new LinkedHashMap<>();

  private long slowestStepMs;
  private String slowestStep = "";

  synchronized void step(final String nation, final int round, final String step, final long ms) {
    if (nation == null || nation.isEmpty()) {
      return;
    }
    perTurn.computeIfAbsent(nation, n -> new TreeMap<>()).merge(round, ms, Long::sum);
    if (ms > slowestStepMs) {
      slowestStepMs = ms;
      slowestStep = nation + " R" + round + " " + step;
    }
  }

  /**
   * {"per_nazione": {nation: {turni, media_s, max_s, totale_s}}, "passo_piu_lento": {...},
   * "ambiente": {...}}.
   */
  synchronized JsonObject toJson() {
    final JsonObject json = new JsonObject();
    final JsonObject nations = new JsonObject();
    perTurn.forEach(
        (nation, rounds) -> {
          final JsonObject n = new JsonObject();
          final long total = rounds.values().stream().mapToLong(Long::longValue).sum();
          final long max = rounds.values().stream().mapToLong(Long::longValue).max().orElse(0);
          n.addProperty("turni", rounds.size());
          n.addProperty("media_s", round1(total / 1000.0 / Math.max(1, rounds.size())));
          n.addProperty("max_s", round1(max / 1000.0));
          n.addProperty("totale_s", round1(total / 1000.0));
          nations.add(nation, n);
        });
    json.add("per_nazione", nations);
    final JsonObject slowest = new JsonObject();
    slowest.addProperty("passo", slowestStep);
    slowest.addProperty("s", round1(slowestStepMs / 1000.0));
    json.add("passo_piu_lento", slowest);
    json.add("ambiente", environment());
    return json;
  }

  /** The machine the game ran on (CI runner name when there is one). */
  static JsonObject environment() {
    final JsonObject env = new JsonObject();
    final Runtime runtime = Runtime.getRuntime();
    env.addProperty("processori", runtime.availableProcessors());
    env.addProperty("memoria_max_mb", runtime.maxMemory() / (1024 * 1024));
    env.addProperty("java", System.getProperty("java.version", "?"));
    env.addProperty("cpu", cpuModel().orElse("?"));
    env.addProperty("runner", Optional.ofNullable(System.getenv("RUNNER_NAME")).orElse("?"));
    try {
      env.addProperty(
          "carico_medio_1min",
          round1(ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()));
    } catch (final RuntimeException e) {
      env.addProperty("carico_medio_1min", -1);
    }
    return env;
  }

  private static Optional<String> cpuModel() {
    try {
      return Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
          .filter(l -> l.startsWith("model name"))
          .map(l -> l.substring(l.indexOf(':') + 1).trim())
          .findFirst();
    } catch (final IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  private static double round1(final double x) {
    return Math.round(x * 10) / 10.0;
  }
}
