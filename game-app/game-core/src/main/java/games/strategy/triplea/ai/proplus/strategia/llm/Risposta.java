package games.strategy.triplea.ai.proplus.strategia.llm;

/**
 * The strategist's answer and what it cost.
 *
 * @param testo the answer text (expected: one JSON object)
 * @param fonte name in the diary: "Claude Code", "Haiku", "DeepSeek", "Groq"
 * @param ingresso input tokens not read from the cache
 * @param cacheLettura input tokens read from the prompt cache
 * @param cacheScrittura input tokens written to the prompt cache
 * @param costoUsd estimated cost (USD); for Claude Code the client's estimate, included in the
 *     Pro/Max subscription
 */
public record Risposta(
    String testo,
    String fonte,
    String modello,
    long ingresso,
    long uscita,
    long cacheLettura,
    long cacheScrittura,
    double costoUsd,
    boolean inAbbonamento,
    long millis) {

  public long tokenTotali() {
    return ingresso + uscita + cacheLettura + cacheScrittura;
  }
}
