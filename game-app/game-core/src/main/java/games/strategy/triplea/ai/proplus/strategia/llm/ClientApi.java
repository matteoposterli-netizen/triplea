package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The strategist reached with an API key: Anthropic Messages API (structured output with the JSON
 * schema, prompt cache of the fixed part for 1 hour, effort "low") or the OpenAI-compatible chat
 * completions of DeepSeek and Groq (JSON mode, temperature from the fantasy level). Timeout {@link
 * #TIMEOUT}, one more try on a network error, timeout, 429 or 5xx. The key goes only in the request
 * header: it is masked out of every error text.
 */
public final class ClientApi {
  public static final Duration TIMEOUT = Duration.ofSeconds(45);

  /** API version of the Anthropic Messages API (checked 2026-11-08). */
  static final String ANTHROPIC_VERSION = "2026-10-21";

  static final int MAX_TOKENS = 8000;

  private final Http http;

  public ClientApi(final Http http) {
    this.http = http;
  }

  /**
   * Asks {@code provider}.
   *
   * @throws ErroreStratega no answer (Italian reason: key refused, credit over, timeout, ...)
   */
  public Risposta chiama(final Fornitore provider, final String key, final Richiesta r) {
    final String body =
        provider.isOpenAiCompatibile() ? openAiBody(provider, r) : anthropicBody(provider, r);
    final Map<String, String> headers = new LinkedHashMap<>();
    headers.put("content-type", "application/json");
    if (provider.isOpenAiCompatibile()) {
      headers.put("authorization", "Bearer " + key);
    } else {
      headers.put("x-api-key", key);
      headers.put("anthropic-version", ANTHROPIC_VERSION);
    }
    final long start = System.nanoTime();
    Http.Esito result = null;
    String failure = "";
    for (int attempt = 1; attempt <= 2; attempt++) {
      try {
        result = http.post(provider.url(), headers, body, TIMEOUT);
        if (result.stato() == 429 || result.stato() >= 500) {
          failure = errore(provider, result, key);
          result = null;
          continue;
        }
        break;
      } catch (final HttpTimeoutException e) {
        failure = "nessuna risposta da " + provider.etichetta() + " entro " + TIMEOUT.toSeconds() + " s";
      } catch (final IOException e) {
        failure = "rete non raggiungibile (" + mask(String.valueOf(e.getMessage()), key) + ")";
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ErroreStratega("chiamata interrotta");
      }
    }
    if (result == null) {
      throw new ErroreStratega(failure + " (2 tentativi)");
    }
    if (result.stato() != 200) {
      throw new ErroreStratega(errore(provider, result, key));
    }
    final long millis = (System.nanoTime() - start) / 1_000_000;
    try {
      final JsonObject json = JsonParser.parseString(result.corpo()).getAsJsonObject();
      return provider.isOpenAiCompatibile()
          ? openAiAnswer(provider, json, millis)
          : anthropicAnswer(provider, json, millis);
    } catch (final ErroreStratega e) {
      throw e;
    } catch (final RuntimeException e) {
      throw new ErroreStratega("risposta di " + provider.etichetta() + " non leggibile");
    }
  }

  // ------------------------------------------------------------------------------- Anthropic

  static String anthropicBody(final Fornitore provider, final Richiesta r) {
    final JsonObject body = new JsonObject();
    body.addProperty("model", provider.modello());
    body.addProperty("max_tokens", MAX_TOKENS);
    final JsonArray system = new JsonArray();
    final JsonObject fixed = new JsonObject();
    fixed.addProperty("type", "text");
    fixed.addProperty("text", r.sistema());
    final JsonObject cache = new JsonObject();
    cache.addProperty("type", "ephemeral");
    // rounds of a human game can be minutes apart: 1 hour keeps the fixed part cached
    cache.addProperty("ttl", "1h");
    fixed.add("cache_control", cache);
    system.add(fixed);
    body.add("system", system);
    final JsonArray messages = new JsonArray();
    final JsonObject user = new JsonObject();
    user.addProperty("role", "user");
    user.addProperty("content", r.utente());
    messages.add(user);
    body.add("messages", messages);
    final JsonObject output = new JsonObject();
    // Claude Haiku 5.5 rejects temperature: the fantasy level is only in the prompt
    output.addProperty("effort", "low");
    if (r.schema() != null && r.schema().size() > 0) {
      final JsonObject format = new JsonObject();
      format.addProperty("type", "json_schema");
      format.add("schema", r.schema());
      output.add("format", format);
    }
    body.add("output_config", output);
    return body.toString();
  }

  private static Risposta anthropicAnswer(
      final Fornitore provider, final JsonObject json, final long millis) {
    final String stop = json.has("stop_reason") ? json.get("stop_reason").getAsString() : "";
    if ("refusal".equals(stop)) {
      throw new ErroreStratega(provider.etichetta() + " ha rifiutato la richiesta");
    }
    if ("max_tokens".equals(stop)) {
      throw new ErroreStratega("risposta di " + provider.etichetta() + " troncata (max_tokens)");
    }
    final StringBuilder text = new StringBuilder();
    for (final JsonElement e : json.getAsJsonArray("content")) {
      final JsonObject block = e.getAsJsonObject();
      if ("text".equals(block.get("type").getAsString())) {
        text.append(block.get("text").getAsString());
      }
    }
    final JsonObject usage =
        json.has("usage") ? json.getAsJsonObject("usage") : new JsonObject();
    final long in = number(usage, "input_tokens");
    final long out = number(usage, "output_tokens");
    final long read = number(usage, "cache_read_input_tokens");
    final long write = number(usage, "cache_creation_input_tokens");
    final String model = json.has("model") ? json.get("model").getAsString() : provider.modello();
    return new Risposta(
        text.toString(),
        provider.etichetta(),
        model,
        in,
        out,
        read,
        write,
        Prezzi.costo(provider.modello(), in, out, read, write),
        false,
        millis);
  }

  // ------------------------------------------------------------------------------- OpenAI-like

  static String openAiBody(final Fornitore provider, final Richiesta r) {
    final JsonObject body = new JsonObject();
    body.addProperty("model", provider.modello());
    final JsonArray messages = new JsonArray();
    final JsonObject system = new JsonObject();
    system.addProperty("role", "system");
    system.addProperty("content", r.sistema());
    messages.add(system);
    final JsonObject user = new JsonObject();
    user.addProperty("role", "user");
    user.addProperty("content", r.utente());
    messages.add(user);
    body.add("messages", messages);
    body.addProperty("temperature", temperatura(r.fantasia()));
    body.addProperty("max_tokens", MAX_TOKENS);
    final JsonObject format = new JsonObject();
    format.addProperty("type", "json_object");
    body.add("response_format", format);
    return body.toString();
  }

  /** Fantasy 1-5 -> temperature 0.2-1.0 (providers that accept it). */
  static double temperatura(final int fantasia) {
    return 0.2 * Math.max(1, Math.min(5, fantasia));
  }

  private static Risposta openAiAnswer(
      final Fornitore provider, final JsonObject json, final long millis) {
    final JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
    final String finish =
        choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
            ? choice.get("finish_reason").getAsString()
            : "";
    if ("length".equals(finish)) {
      throw new ErroreStratega("risposta di " + provider.etichetta() + " troncata (max_tokens)");
    }
    final JsonElement content = choice.getAsJsonObject("message").get("content");
    final String text = content == null || content.isJsonNull() ? "" : content.getAsString();
    final JsonObject usage =
        json.has("usage") ? json.getAsJsonObject("usage") : new JsonObject();
    final long prompt = number(usage, "prompt_tokens");
    long cached = number(usage, "prompt_cache_hit_tokens");
    if (cached == 0 && usage.has("prompt_tokens_details")) {
      cached = number(usage.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
    }
    final long in = Math.max(0, prompt - cached);
    final long out = number(usage, "completion_tokens");
    final String model = json.has("model") ? json.get("model").getAsString() : provider.modello();
    return new Risposta(
        text,
        provider.etichetta(),
        model,
        in,
        out,
        cached,
        0,
        Prezzi.costo(provider.modello(), in, out, cached, 0),
        false,
        millis);
  }

  // ------------------------------------------------------------------------------- helpers

  /** Clear Italian reason of an HTTP error. */
  static String errore(final Fornitore provider, final Http.Esito result, final String key) {
    final String detail = mask(apiMessage(result.corpo()), key);
    final String who = provider.etichetta();
    final String reason =
        switch (result.stato()) {
          case 401 -> "chiave rifiutata da " + who + " (non valida o revocata)";
          case 403 -> "chiave senza permessi per " + who;
          case 402 -> "credito esaurito su " + who;
          case 404 -> "modello " + provider.modello() + " non trovato su " + who;
          case 429 -> "limite di richieste o quota raggiunti su " + who;
          case 400 ->
              detail.toLowerCase(Locale.ROOT).contains("credit")
                  ? "credito esaurito su " + who
                  : "richiesta rifiutata da " + who;
          default ->
              result.stato() >= 500
                  ? who + " non disponibile (errore " + result.stato() + ")"
                  : "errore " + result.stato() + " da " + who;
        };
    return detail.isEmpty() ? reason : reason + ": " + detail;
  }

  private static String apiMessage(final String body) {
    try {
      final JsonObject json = JsonParser.parseString(body).getAsJsonObject();
      if (json.has("error") && json.get("error").isJsonObject()) {
        final JsonObject err = json.getAsJsonObject("error");
        if (err.has("message")) {
          final String m = err.get("message").getAsString();
          return m.length() > 160 ? m.substring(0, 160) + "..." : m;
        }
      }
    } catch (final RuntimeException e) {
      // not JSON
    }
    return "";
  }

  /** {@code text} without the key (never shown, even partially). */
  static String mask(final String text, final String key) {
    return key == null || key.length() < 6 ? text : text.replace(key, "***");
  }

  private static long number(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0;
  }
}
