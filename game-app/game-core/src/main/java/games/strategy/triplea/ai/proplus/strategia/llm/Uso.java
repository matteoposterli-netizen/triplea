package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.properties.GameProperties;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * The strategist's settings and usage saved in the game (game properties holding only JDK strings
 * and integers, like the risk level): calls, tokens and estimated cost of the game ({@value
 * #KEY_USO}), cap of calls per game ({@value #KEY_TETTO}, default {@value #TETTO_DEFAULT}), fantasy
 * level per alliance. No API key is ever saved here.
 */
@UtilityClass
public final class Uso {
  public static final String KEY_USO = "Pro+ stratega uso";
  public static final String KEY_TETTO = "Pro+ stratega tetto chiamate";
  static final String FANTASIA_PREFIX = "Pro+ stratega fantasia: ";
  public static final int TETTO_DEFAULT = 40;

  /** The usage of the game so far: {chiamate, errori, json_non_validi, token, costo_usd, per_fonte}. */
  public static JsonObject leggi(final GameProperties props) {
    if (props.get(KEY_USO) instanceof String s) {
      try {
        return JsonParser.parseString(s).getAsJsonObject();
      } catch (final RuntimeException e) {
        // unreadable: start again
      }
    }
    return new JsonObject();
  }

  public static int chiamate(final GameProperties props) {
    final JsonObject u = leggi(props);
    return u.has("chiamate") ? u.get("chiamate").getAsInt() : 0;
  }

  /** One answer received (tokens and cost added). */
  public static void registra(final GameProperties props, final Risposta r) {
    final JsonObject u = leggi(props);
    add(u, "chiamate", 1);
    add(u, "token_ingresso", r.ingresso());
    add(u, "token_uscita", r.uscita());
    add(u, "token_cache_lettura", r.cacheLettura());
    add(u, "token_cache_scrittura", r.cacheScrittura());
    add(u, "millisecondi", r.millis());
    if (r.inAbbonamento()) {
      add(u, "costo_abbonamento_usd", r.costoUsd());
    } else {
      add(u, "costo_usd", r.costoUsd());
    }
    final JsonObject source = source(u, r.fonte());
    add(source, "chiamate", 1);
    add(source, "token", r.tokenTotali());
    add(source, "costo_usd", r.costoUsd());
    write(props, u);
  }

  /** A call without an answer (error, timeout): counts toward the cap. */
  public static void registraErrore(final GameProperties props, final String fonte) {
    final JsonObject u = leggi(props);
    add(u, "chiamate", 1);
    add(u, "errori", 1);
    add(source(u, fonte), "errori", 1);
    write(props, u);
  }

  /** An answer that was not valid JSON (already counted by {@link #registra}). */
  public static void registraJsonNonValido(final GameProperties props) {
    final JsonObject u = leggi(props);
    add(u, "json_non_validi", 1);
    write(props, u);
  }

  public static int tetto(final GameProperties props) {
    return props.get(KEY_TETTO) instanceof Integer i && i >= 0 ? i : TETTO_DEFAULT;
  }

  public static void setTetto(final GameProperties props, final int cap) {
    props.set(KEY_TETTO, (Serializable) null);
    props.set(KEY_TETTO, Integer.valueOf(Math.max(0, cap)));
  }

  /** The "Fantasia" level of {@code alliance}, 1-5 (default {@link PromptStratega#FANTASIA_DEFAULT}). */
  public static int fantasia(final GameProperties props, final String alliance) {
    return props.get(FANTASIA_PREFIX + alliance) instanceof Integer i && i >= 1 && i <= 5
        ? i
        : PromptStratega.FANTASIA_DEFAULT;
  }

  public static void setFantasia(final GameProperties props, final String alliance, final int f) {
    props.set(FANTASIA_PREFIX + alliance, (Serializable) null);
    props.set(FANTASIA_PREFIX + alliance, Integer.valueOf(Math.max(1, Math.min(5, f))));
  }

  /** Eg "5 chiamate (Haiku 5), 41.230 token, costo stimato $0,0061". */
  public static String testo(final JsonObject u) {
    if (!u.has("chiamate")) {
      return "nessuna chiamata";
    }
    final List<String> sources = new ArrayList<>();
    if (u.has("per_fonte")) {
      u.getAsJsonObject("per_fonte")
          .entrySet()
          .forEach(
              e -> {
                final JsonObject s = e.getValue().getAsJsonObject();
                sources.add(
                    e.getKey()
                        + " "
                        + (s.has("chiamate") ? s.get("chiamate").getAsLong() : 0)
                        + (s.has("errori") ? " + " + s.get("errori").getAsLong() + " errori" : ""));
              });
    }
    final long tokens =
        number(u, "token_ingresso")
            + number(u, "token_uscita")
            + number(u, "token_cache_lettura")
            + number(u, "token_cache_scrittura");
    final StringBuilder sb =
        new StringBuilder()
            .append(u.get("chiamate").getAsLong())
            .append(" chiamate (")
            .append(String.join(", ", sources))
            .append("), ")
            .append(tokens)
            .append(" token (ingresso ")
            .append(number(u, "token_ingresso"))
            .append(", uscita ")
            .append(number(u, "token_uscita"))
            .append(", cache letta ")
            .append(number(u, "token_cache_lettura"))
            .append(", cache scritta ")
            .append(number(u, "token_cache_scrittura"))
            .append("), costo stimato $")
            .append(String.format(Locale.ROOT, "%.4f", u.has("costo_usd") ? u.get("costo_usd").getAsDouble() : 0));
    if (u.has("costo_abbonamento_usd")) {
      sb.append(
          String.format(
              Locale.ROOT,
              " + Claude Code $%.4f (stima, incluso nell'abbonamento)",
              u.get("costo_abbonamento_usd").getAsDouble()));
    }
    if (u.has("json_non_validi")) {
      sb.append(", JSON non validi ").append(u.get("json_non_validi").getAsLong());
    }
    return sb.toString();
  }

  private static JsonObject source(final JsonObject u, final String fonte) {
    if (!u.has("per_fonte")) {
      u.add("per_fonte", new JsonObject());
    }
    final JsonObject all = u.getAsJsonObject("per_fonte");
    if (!all.has(fonte)) {
      all.add(fonte, new JsonObject());
    }
    return all.getAsJsonObject(fonte);
  }

  private static void add(final JsonObject o, final String key, final double v) {
    final double old = o.has(key) ? o.get(key).getAsDouble() : 0;
    final double sum = old + v;
    if (sum == Math.rint(sum) && Math.abs(sum) < Long.MAX_VALUE / 2.0) {
      o.addProperty(key, (long) sum);
    } else {
      o.addProperty(key, sum);
    }
  }

  private static long number(final JsonObject o, final String key) {
    return o.has(key) ? o.get(key).getAsLong() : 0;
  }

  private static void write(final GameProperties props, final JsonObject u) {
    props.set(KEY_USO, (Serializable) null);
    props.set(KEY_USO, u.toString());
  }
}
