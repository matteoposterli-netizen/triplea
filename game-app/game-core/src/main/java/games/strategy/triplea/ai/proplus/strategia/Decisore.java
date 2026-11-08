package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameState;
import java.util.Optional;

/**
 * Strategic contract (v1): who decides WHAT and WHY for an alliance. Called at the start of each
 * round by the first Pro+ nation of the alliance ({@link Valutazione#inizioRound}); the
 * deterministic part of Pro+ (the HOW) then reads only the directives it returns.
 *
 * <p>Input: the strategic state ({@link Ingresso}, JSON v1, see proplus/strategia/contratto-v1.md);
 * output: the directives ({@link Direttive}, JSON v1). Directives that are missing or invalid fall
 * back to the decider by rules ({@link DecisoreRegole}), with a notice in the diary; the guard
 * rails ({@link Direttive#valida}) apply whatever the decider.
 */
public interface Decisore {
  /** The decider by rules: the general staff of steps 3a-3d (default). */
  String REGOLE = "regole";

  /** Directives read from a JSON file per round and alliance (tests, hand trials). */
  String FILE = "file";

  /** The LLM strategist: only the interface in this step (no network call). */
  String LLM = "llm";

  /** {@link #REGOLE}, {@link #FILE} or {@link #LLM}. */
  String nome();

  /**
   * The directives for the alliance of {@code contesto} at the start of its round; empty = no
   * decision (the decider by rules is used, with a notice).
   *
   * @param stato the strategic state (input JSON v1)
   */
  Optional<JsonObject> decidi(JsonObject stato, Contesto contesto);

  /**
   * What the general staff knows when it asks for a decision. The decider by rules reads the game
   * through it (its rules were written on the game data); the other deciders only read the input
   * JSON.
   *
   * @param piano the plan in force before the decision
   * @param stato the saved state of the alliance before the decision
   * @param storico the history entry of this round measured with {@code piano} (progress, reading)
   * @param cambio the rules' check of a plan change
   */
  record Contesto(
      GameState data,
      String alleanza,
      Strategia strategia,
      Strategia.Piano piano,
      StatoMaggiore.Stato stato,
      JsonObject storico,
      Contromosse.Cambio cambio,
      int round) {}
}
