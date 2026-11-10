package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Step STRATEGA LLM: the prompt of the strategic level (start of the round, per alliance): fixed
 * part from {@value #RISORSA} with the engine's limits and the schema; variable part = the state of
 * the round (candidates, operations in progress, reading of the opponent).
 */
@UtilityClass
public final class PromptStrategaV4 {
  public static final String RISORSA = "proplus/strategia/prompt-stratega-v4.md";
  public static final String VERSIONE = "prompt-stratega-v4";
  public static final String CONTRATTO = "1.3";

  /** The fixed part with the limits text ({@code vincoli}: one rule a line). */
  public static String sistema(final String vincoli) {
    return PromptStratega.risorsa(RISORSA)
        .replace("{{VINCOLI}}", vincoli)
        .replace(
            "{{SCHEMA}}",
            new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(schema()));
  }

  /** Schema of the answer (strict: closed objects, every field required). */
  public static JsonObject schema() {
    final JsonObject voce =
        PromptStratega.object(
            "azione", PromptStratega.enumOf(List.of("avvia", "continua", "modifica", "annulla")),
            "candidato", PromptStratega.string("id del candidato per avvia, altrimenti \"\""),
            "operazione", PromptStratega.string("id dell'operazione in corso, \"\" per avvia"),
            "round_obiettivo", PromptStratega.type("integer"),
            "tetto_perdita_TUV", PromptStratega.type("integer"),
            "durata_tenuta", PromptStratega.type("integer"),
            "bersaglio_successivo", PromptStratega.string("solo sbarco, \"\" = nessuno"),
            "prob_tenuta_stimata", PromptStratega.type("number"),
            "motivazione", PromptStratega.string("max 2 frasi in italiano"));
    final JsonObject fuori =
        PromptStratega.object(
            "proponi", PromptStratega.type("boolean"),
            "tipo",
                PromptStratega.enumOf(
                    List.of("offensiva", "sbarco", "tenuta", "rinforzo", "nessuno")),
            "bersaglio", PromptStratega.string("nome del territorio"),
            "apre", PromptStratega.string("nazione"),
            "chiude", PromptStratega.string("nazione"),
            "prob_tenuta_stimata", PromptStratega.type("number"),
            "motivazione", PromptStratega.string("max 2 frasi in italiano"));
    final JsonObject rinforzo =
        PromptStratega.object(
            "territorio", PromptStratega.string("territorio o zona di mare"),
            "peso", PromptStratega.type("integer"));
    return PromptStratega.object(
        "portafoglio", PromptStratega.array(voce),
        "fuori_lista", fuori,
        "rinforzi", PromptStratega.array(rinforzo),
        "motivazione", PromptStratega.string("max 3 frasi in italiano"));
  }

  public static Richiesta richiesta(final String vincoli, final JsonObject stato) {
    return new Richiesta(
        sistema(vincoli),
        "Stato del round (JSON, calcolato dal motore):\n"
            + PromptStratega.compact(stato)
            + "\n\nRispondi solo con il JSON del portafoglio.",
        schema(),
        1);
  }
}
