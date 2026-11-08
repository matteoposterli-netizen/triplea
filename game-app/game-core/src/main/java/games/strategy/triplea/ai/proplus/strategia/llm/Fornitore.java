package games.strategy.triplea.ai.proplus.strategia.llm;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The providers of the LLM strategist reached with an API key. Model ids checked on 2026-11-08:
 * Anthropic from the official model list ({@code claude-haiku-5-5}); DeepSeek ({@code
 * deepseek-chat} retired on 2026-07-24, {@code deepseek-v4-flash} still accepted) and Groq ({@code
 * openai/gpt-oss-120b}) from search results of their official pages, not opened (see CLAUDE.md
 * "Come configurare lo stratega"). Prices are in {@value Prezzi#RISORSA}.
 */
public enum Fornitore {
  ANTHROPIC(
      "anthropic",
      "Haiku",
      "Claude Haiku (consigliato)",
      "claude-haiku-5-5",
      "https://api.anthropic.com/v1/messages",
      "ANTHROPIC_API_KEY"),
  DEEPSEEK(
      "deepseek",
      "DeepSeek",
      "DeepSeek",
      "deepseek-v4-flash",
      "https://api.deepseek.com/chat/completions",
      "DEEPSEEK_API_KEY"),
  GROQ(
      "groq",
      "Groq",
      "Groq",
      "openai/gpt-oss-120b",
      "https://api.groq.com/openai/v1/chat/completions",
      "GROQ_API_KEY");

  private final String id;
  private final String etichetta;
  private final String nomePannello;
  private final String modello;
  private final String url;
  private final String variabile;

  Fornitore(
      final String id,
      final String etichetta,
      final String nomePannello,
      final String modello,
      final String url,
      final String variabile) {
    this.id = id;
    this.etichetta = etichetta;
    this.nomePannello = nomePannello;
    this.modello = modello;
    this.url = url;
    this.variabile = variabile;
  }

  /** Eg "anthropic" (saved in the user profile). */
  public String id() {
    return id;
  }

  /** Name in the diary, eg "Haiku". */
  public String etichetta() {
    return etichetta;
  }

  public String modello() {
    return modello;
  }

  public String url() {
    return url;
  }

  /** Environment variable read when no key is saved (the autoplay in CI). */
  public String variabile() {
    return variabile;
  }

  /** DeepSeek and Groq speak the OpenAI chat completions format. */
  public boolean isOpenAiCompatibile() {
    return this != ANTHROPIC;
  }

  @Override
  public String toString() {
    return nomePannello;
  }

  public static Optional<Fornitore> daId(final String id) {
    return Arrays.stream(values())
        .filter(f -> f.id.equals(id == null ? "" : id.strip().toLowerCase(Locale.ROOT)))
        .findFirst();
  }

  /** The provider a key belongs to, from its prefix (sk-ant- / gsk_ / sk-). */
  public static Optional<Fornitore> daChiave(final String key) {
    final String k = key == null ? "" : key.strip();
    if (k.startsWith("sk-ant-")) {
      return Optional.of(ANTHROPIC);
    }
    if (k.startsWith("gsk_")) {
      return Optional.of(GROQ);
    }
    if (k.startsWith("sk-")) {
      return Optional.of(DEEPSEEK);
    }
    return Optional.empty();
  }
}
