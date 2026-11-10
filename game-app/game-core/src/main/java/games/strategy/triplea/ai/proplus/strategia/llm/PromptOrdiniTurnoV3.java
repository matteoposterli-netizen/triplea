package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;

/**
 * Step STRATEGA LLM: the prompt of the turn orders on the general operations (offensive, landing,
 * hold, reinforcement) at their decision points. Same answer ({@code decisione_operazione},
 * contract 1.2) and guard rails as {@link PromptOperazione}; fixed part from {@value #RISORSA}.
 */
@UtilityClass
public final class PromptOrdiniTurnoV3 {
  public static final String RISORSA = "proplus/strategia/prompt-ordini-turno-v3.md";
  public static final String VERSIONE = "prompt-ordini-turno-v3";

  private static volatile String system;

  public static String sistema() {
    String s = system;
    if (s == null) {
      s =
          PromptStratega.risorsa(RISORSA)
              .replace(
                  "{{PALETTI}}",
                  PromptOperazione.paletti()
                      .replace(
                          "decide la formula del motore",
                          "decide Hard (l'operazione non agisce in questo turno)"))
              .replace(
                  "{{SCHEMA}}",
                  new GsonBuilder()
                      .disableHtmlEscaping()
                      .setPrettyPrinting()
                      .create()
                      .toJson(PromptOperazione.schema()));
      system = s;
    }
    return s;
  }

  public static Richiesta richiesta(final JsonObject stato) {
    return new Richiesta(sistema(), PromptOperazione.utente(stato), PromptOperazione.schema(), 1);
  }
}
