package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import games.strategy.triplea.ai.proplus.strategia.Direttive;
import games.strategy.triplea.ai.proplus.strategia.Tattiche;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The prompt of the turn orders (LLM step, second level): fixed part from {@value #RISORSA} (role,
 * what the orders may set, turn order and coordination windows, guard rails, schema {@value
 * #SCHEMA_VERSIONE}), variable part = fantasy + the nation's input ({@code Ingresso.ordiniTurno}).
 * Shorter than the strategist's prompt: the plans are not repeated (the input holds the strategic
 * directives of the round).
 */
@UtilityClass
public final class PromptOrdini {
  public static final String RISORSA = "proplus/strategia/prompt-ordini-turno-v1.md";
  public static final String VERSIONE = "prompt-ordini-turno-v1";
  public static final String SCHEMA_VERSIONE = "ordini-turno-v1";

  private static volatile String system;

  public static String sistema() {
    String s = system;
    if (s == null) {
      s =
          PromptStratega.risorsa(RISORSA)
              .replace(
                  "{{PALETTI}}",
                  PromptStratega.paletti()
                      + "\n- gli ordini del turno non cambiano piano, postura, rischio, lettura del"
                      + " nemico.")
              .replace(
                  "{{SCHEMA}}",
                  new GsonBuilder()
                      .disableHtmlEscaping()
                      .setPrettyPrinting()
                      .create()
                      .toJson(schema()));
      system = s;
    }
    return s;
  }

  public static String utente(final JsonObject ingresso, final int fantasia) {
    final String nation = ingresso.has("nazione") ? ingresso.get("nazione").getAsString() : "?";
    return "Livello di Fantasia: "
        + PromptStratega.fantasia(fantasia)
        + "\n\nIngresso degli ordini del turno di "
        + nation
        + ", round "
        + (ingresso.has("round") ? ingresso.get("round").getAsString() : "?")
        + " (JSON):\n"
        + PromptStratega.compact(ingresso)
        + "\n\nRispondi solo con il JSON degli ordini del turno.";
  }

  /** Schema ordini-turno-v1 (strict). */
  public static JsonObject schema() {
    return PromptStratega.object(
        "bersagli",
            PromptStratega.array(
                PromptStratega.object(
                    "territorio", PromptStratega.string("territorio o zona di mare"),
                    "peso", PromptStratega.type("number"),
                    "origine",
                        PromptStratega.enumOf(
                            List.of(Direttive.ORIGINE_PIANO, Direttive.ORIGINE_CONTROMOSSA)),
                    "critico", PromptStratega.type("boolean"))),
        "operazioni",
            PromptStratega.array(
                PromptStratega.object(
                    "id", PromptStratega.string("operazione della nazione"),
                    "decisione", PromptStratega.enumOf(Direttive.DECISIONI),
                    "perdita_trasporti_accettata_max_TUV", PromptStratega.type("number"))),
        "tattiche",
            PromptStratega.array(
                PromptStratega.object(
                    "strumento", PromptStratega.enumOf(Tattiche.STRUMENTI),
                    "fronte", PromptStratega.string("fronte, vuoto = tutti"),
                    "attiva", PromptStratega.type("boolean"))),
        "motivazione", PromptStratega.string("max 2 frasi in italiano"));
  }
}
