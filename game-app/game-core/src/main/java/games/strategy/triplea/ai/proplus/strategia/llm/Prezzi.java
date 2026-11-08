package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import lombok.experimental.UtilityClass;

/** Prices per million tokens, from {@value #RISORSA} (estimated costs only). */
@UtilityClass
public final class Prezzi {
  public static final String RISORSA = "proplus/strategia/prezzi-stratega.json";

  private static volatile JsonObject models;

  /** Estimated USD cost of an answer of {@code model}; 0 for a model without prices. */
  public static double costo(
      final String model,
      final long input,
      final long output,
      final long cacheRead,
      final long cacheWrite) {
    final JsonObject all = models();
    if (!all.has(model)) {
      return 0;
    }
    final JsonObject p = all.getAsJsonObject(model);
    return (input * price(p, "ingresso")
            + output * price(p, "uscita")
            + cacheRead * price(p, "cache_lettura")
            + cacheWrite * price(p, "cache_scrittura"))
        / 1_000_000.0;
  }

  private static double price(final JsonObject p, final String key) {
    return p.has(key) ? p.get(key).getAsDouble() : 0;
  }

  private static JsonObject models() {
    JsonObject m = models;
    if (m == null) {
      m = new JsonObject();
      try (InputStream in = Prezzi.class.getClassLoader().getResourceAsStream(RISORSA)) {
        if (in != null) {
          final JsonObject file =
              JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                  .getAsJsonObject();
          m = file.has("modelli") ? file.getAsJsonObject("modelli") : m;
        }
      } catch (final IOException | RuntimeException e) {
        // no prices: costs read 0
      }
      models = m;
    }
    return m;
  }
}
