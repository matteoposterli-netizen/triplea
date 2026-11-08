package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonObject;
import java.util.Optional;

/**
 * Placeholder of the LLM strategist (Claude): only the interface in this step, no network call. It
 * never decides, so the decider by rules is used (with a notice). Not offered in the settings
 * dialog nor in the autoplay yet.
 */
public final class DecisoreLlm implements Decisore {
  @Override
  public String nome() {
    return LLM;
  }

  @Override
  public Optional<JsonObject> decidi(final JsonObject stato, final Contesto contesto) {
    return Optional.empty();
  }
}
