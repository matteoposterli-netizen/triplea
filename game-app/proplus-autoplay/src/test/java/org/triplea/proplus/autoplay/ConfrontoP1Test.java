package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Step P1: the block {@code p1} of a game and the configuration compared with Hard. */
class ConfrontoP1Test {
  private static final String P1 =
      "Stratega LLM [senza chiave] [con impegno] [con assedio] / Stratega LLM [senza chiave] [con"
          + " impegno] [con compiti]";
  private static final String E2 = "Stratega LLM [senza chiave] [con impegno] [con assedio] / Hard";
  private static final String P2 = "Hard / Stratega LLM [senza chiave] [con impegno] [con compiti]";
  private static final String A = "Hard / Hard";

  @Test
  void eachConfigurationWithTheTasksIsComparedWithTheSameAxisAgainstHard() {
    final Set<String> all = Set.of(P1, E2, P2, A);
    assertThat(ConfrontoP1.senzaCompiti(P1, all)).isEqualTo(E2);
    assertThat(ConfrontoP1.senzaCompiti(P2, all)).isEqualTo(A);
  }

  @Test
  void theBlockCountsTheResultsTheSeaTurnsAndTheirResponses() {
    final JsonObject game =
        JsonParser.parseString(
                """
                {"round_giocati": 12,
                 "sbarchi": [{"round": 7, "nazione": "Americans", "territorio": "Normandy Bordeaux",
                              "alleanza": "Allies", "unita": 4}],
                 "round": [{"round": 8, "nazioni": {"Americans": {"trasporti": 6,
                   "trasporti_inattivi": 2, "trasporti_persi": 1}},
                   "p1": {"regioni_usa": {"patria": 3, "base": 2, "mare": 8}}}],
                 "impegno_decisioni": [
                  {"riga": "decisione", "tipo": "compito_risultato", "round": 5,
                   "parametri": {"risultato": "R1", "esito": "RAGGIUNTO"},
                   "motivo": {"codice": "RISULTATO_RAGGIUNTO"}},
                  {"riga": "decisione", "tipo": "compito_direzione", "round": 5,
                   "parametri": {"scelta": "FRANCIA", "conti": {"ITALIA": 80, "FRANCIA": 60},
                                 "fino_al_round": 7},
                   "motivo": {"codice": "DIREZIONE_SCELTA"}},
                  {"riga": "decisione", "tipo": "compito_stato", "round": 5,
                   "parametri": {"tipi_ostacolo": ["MARE", "DISTANZA"],
                                 "risposte": ["ACQUISTO_MARE", "SCORTA"]},
                   "motivo": {"codice": "OSTACOLI"}},
                  {"riga": "decisione", "tipo": "compito_stato", "round": 6,
                   "parametri": {"tipi_ostacolo": ["MARE"], "risposte": ["AVVICINAMENTO"]},
                   "motivo": {"codice": "OSTACOLI"}},
                  {"riga": "decisione", "tipo": "compito_stato", "round": 6,
                   "parametri": {"mosse": {}}, "motivo": {"codice": "MOSSE_NON_COMBATTIMENTO"}},
                  {"riga": "decisione", "tipo": "sbarco", "round": 7,
                   "parametri": {"compito": true, "verso_capitale": true},
                   "motivo": {"codice": "ESEGUITO"}},
                  {"riga": "decisione", "tipo": "compito_battaglia", "round": 6,
                   "parametri": {}, "motivo": {"codice": "ATTACCO_FLOTTA"}},
                  {"riga": "esito", "tipo": "compito_battaglia", "round": 6,
                   "esito": {"vinta": true}},
                  {"riga": "decisione", "tipo": "attacco_subito", "round": 8,
                   "alternative": [{"trasporti_usa_persi_carichi": 1,
                                    "trasporti_usa_persi_vuoti": 0}],
                   "motivo": {"codice": "ATTACCO"}}]}
                """)
            .getAsJsonObject();
    final JsonObject monitor =
        JsonParser.parseString(
                """
                {"acquisti_usa": {"4": {"destroyer": 3}, "5": {"destroyer": 1, "fighter": 1,
                  "transport": 2, "infantry": 4}},
                 "attacchi_usa": {"France": [9]}, "prese_usa": {}}
                """)
            .getAsJsonObject();
    final JsonObject b = ConfrontoP1.blocco(game, monitor);
    assertThat(b.get("r1_round").getAsInt()).isEqualTo(5);
    assertThat(b.get("direzione").getAsString()).isEqualTo("FRANCIA");
    assertThat(b.get("primo_sbarco_capitale_round").getAsInt()).isEqualTo(7);
    assertThat(b.getAsJsonObject("primo_sbarco_usa_verso").get("France").getAsInt()).isEqualTo(7);
    assertThat(b.get("turni_compito").getAsInt()).isEqualTo(2);
    assertThat(b.get("turni_mare").getAsInt()).isEqualTo(2);
    assertThat(b.get("turni_mare_con_risposta").getAsInt()).isEqualTo(1);
    assertThat(b.getAsJsonArray("turni_mare_senza_risposta").get(0).getAsInt()).isEqualTo(6);
    final JsonObject from5 = b.getAsJsonObject("acquisti_usa_dal_r5");
    assertThat(from5.get("navi_da_guerra").getAsInt()).isEqualTo(1);
    assertThat(from5.get("aerei").getAsInt()).isEqualTo(1);
    assertThat(from5.get("trasporti").getAsInt()).isEqualTo(2);
    assertThat(b.getAsJsonObject("battaglie_navali_usa").get("vinte").getAsInt()).isEqualTo(1);
    assertThat(b.getAsJsonObject("trasporti_usa_persi").get("totale").getAsInt()).isEqualTo(1);
    assertThat(b.getAsJsonObject("trasporti_usa_persi").get("carichi_attacchi_subiti").getAsInt())
        .isEqualTo(1);
    final JsonObject r8 = b.getAsJsonObject("per_round").getAsJsonObject("8");
    assertThat(r8.get("ferme_patria_base").getAsInt()).isEqualTo(5);
    assertThat(r8.get("trasporti_inattivi").getAsInt()).isEqualTo(2);
  }
}
