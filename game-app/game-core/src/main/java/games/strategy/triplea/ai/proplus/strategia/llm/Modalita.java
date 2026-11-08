package games.strategy.triplea.ai.proplus.strategia.llm;

import java.util.Arrays;
import java.util.Optional;

/**
 * Who decides the WHAT/WHY of an alliance (the "Stratega" section of the Pro+ settings). The ids
 * are saved in the game property {@code "Pro+ decisore: <alliance>"} (the first two existed before
 * the LLM strategist).
 */
public enum Modalita {
  /** Claude Code if installed and logged in, else the API key, else the rules. */
  AUTOMATICO("auto", "Automatico"),
  CLAUDE_CODE("claude", "Claude installato"),
  CHIAVE_API("chiave", "Chiave API"),
  FILE("file", "File"),
  REGOLE("regole", "Solo regole");

  private final String id;
  private final String nome;

  Modalita(final String id, final String nome) {
    this.id = id;
    this.nome = nome;
  }

  public String id() {
    return id;
  }

  /** Whether an LLM is asked (Automatico, Claude installato, Chiave API). */
  public boolean isLlm() {
    return this == AUTOMATICO || this == CLAUDE_CODE || this == CHIAVE_API;
  }

  @Override
  public String toString() {
    return nome;
  }

  public static Optional<Modalita> daId(final String id) {
    return Arrays.stream(values()).filter(m -> m.id.equals(id)).findFirst();
  }
}
