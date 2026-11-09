package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import games.strategy.triplea.ai.proplus.strategia.DecisioneOperazione;
import java.util.Arrays;
import lombok.experimental.UtilityClass;

/**
 * The prompt of the decision points of the joint operations (step PROVALLM): fixed part from
 * {@value #RISORSA} (operations by steps, actions, how to read the enemy's real response, guard
 * rails, schema of {@code decisione_operazione}, contract {@value DecisioneOperazione#VERSIONE}),
 * variable part = the operation's state computed by the engine.
 */
@UtilityClass
public final class PromptOperazione {
  public static final String RISORSA = "proplus/strategia/prompt-operazione-v1.md";
  public static final String VERSIONE = "prompt-operazione-v1";

  private static volatile String system;

  public static String sistema() {
    String s = system;
    if (s == null) {
      s =
          PromptStratega.risorsa(RISORSA)
              .replace("{{PALETTI}}", paletti())
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

  static String paletti() {
    return String.join(
        "\n",
        "- tetto di perdita per tappa (campo `tetto_perdita_TUV`): una tappa con perdita attesa oltre"
            + " il tetto non viene eseguita, e il motore non ti chiede nulla;",
        "- la tappa 2 non parte se la tappa 1 ha perso piu' del tetto o se la nazione non ha unita'"
            + " che possono attaccare;",
        "- la guarnigione della capitale (25% di rischio nemico) non lascia mai la capitale per"
            + " l'operazione;",
        "- numero massimo di rinvii e round massimo dell'operazione (campi `rinvii`,"
            + " `max_rinvii`, `round_max`): oltre, l'operazione si annulla da sola;",
        "- risposta non valida, assente o fuori tempo: decide la formula del motore.");
  }

  public static String utente(final JsonObject stato) {
    return "Stato dell'operazione (JSON, calcolato dal motore):\n"
        + PromptStratega.compact(stato)
        + "\n\nRispondi solo con il JSON della decisione.";
  }

  /** Schema of the answer (strict): {"decisione_operazione": {azione, prob, motivazione}}. */
  public static JsonObject schema() {
    return PromptStratega.object(
        DecisioneOperazione.CHIAVE,
        PromptStratega.object(
            "azione",
                PromptStratega.enumOf(
                    Arrays.stream(DecisioneOperazione.Azione.values())
                        .map(DecisioneOperazione.Azione::id)
                        .toList()),
            "prob_tenuta_stimata", PromptStratega.type("number"),
            "motivazione", PromptStratega.string("max 2 frasi in italiano")));
  }

  public static Richiesta richiesta(final JsonObject stato) {
    return new Richiesta(sistema(), utente(stato), schema(), 1);
  }
}
