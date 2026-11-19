package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Step P4: the block {@code p4} of a game and the reference configuration of the section. */
class ConfrontoP4Test {

  @Test
  void theBlockCountsLandingsFleetNorwayTheFrontAndTheRegister() {
    final JsonObject game =
        JsonParser.parseString(
                """
                {"alleanze": {"Axis": ["Germans"], "Allies": ["Americans", "British", "Russians"]},
                 "sbarchi": [
                  {"round": 5, "nazione": "Americans", "territorio": "Normandy Bordeaux", "unita": 4},
                  {"round": 5, "nazione": "Americans", "territorio": "Normandy Bordeaux", "unita": 3},
                  {"round": 7, "nazione": "Americans", "territorio": "Morocco", "unita": 2},
                  {"round": 6, "nazione": "British", "territorio": "Norway", "unita": 9}],
                 "capitali": [{"round": 15, "territorio": "Russia", "evento": "caduta"},
                              {"round": 9, "territorio": "Southern Italy", "evento": "caduta"}],
                 "round": [
                  {"round": 3, "p1": {"trasporti_usa": 4, "navi_da_guerra_usa": 3},
                   "nazioni": {"Germans": {"reddito": 40}, "Russians": {"pu_riparazioni": 0}},
                   "p4": {"proprietari": {"Norway": "British", "Ukraine": "Russians"},
                     "attacchi_russi": {"attaccanti": 10, "nemici_uccisi": 6}}},
                  {"round": 4, "p1": {"trasporti_usa": 6, "navi_da_guerra_usa": 3},
                   "nazioni": {"Germans": {"reddito": 38}, "Russians": {"pu_riparazioni": 5}},
                   "p4": {"proprietari": {"Norway": "Germans", "Ukraine": "Russians"}}},
                  {"round": 6, "nazioni": {"Russians": {"pu_riparazioni": 2}},
                   "p4": {"proprietari": {"Norway": "British", "Ukraine": "Germans"},
                     "esercito_tuv": {"Russians": 90, "Germans": 300}}}],
                 "impegno_decisioni": [
                  {"riga": "decisione", "tipo": "usa_pronti", "round": 1,
                   "motivo": {"codice": "ACQUISTO_NEUTRALE"},
                   "parametri": {"acquisti": {"transport": 2, "infantry": 4}}},
                  {"riga": "decisione", "tipo": "norvegia_presto", "round": 2,
                   "motivo": {"codice": "PESO_ATTIVO"},
                   "parametri": {"bersagli": [{"territorio": "Norway", "attaccato": true,
                     "alleato_fine_turno": true}]}},
                  {"riga": "decisione", "tipo": "russia_fronte", "round": 3,
                   "motivo": {"codice": "FRONTE"},
                   "parametri": {"pu_riparati": 4, "pu_risparmiati": 3, "contrattacchi": [
                     {"territorio": "Belarus", "attaccato": true, "sotto_soglia_standard": true,
                      "preso": true, "tenuto_in_difensiva": true},
                     {"territorio": "Poland", "attaccato": false, "preso": false,
                      "tenuto_in_difensiva": false}]}}]}
                """)
            .getAsJsonObject();
    final JsonObject b = ConfrontoP4.blocco(game);
    final JsonObject cum = b.getAsJsonObject("usa_sbarcate_cumulate");
    assertThat(cum.getAsJsonObject("5").get("totale").getAsInt()).isEqualTo(7);
    assertThat(cum.getAsJsonObject("8").get("totale").getAsInt()).isEqualTo(9);
    assertThat(cum.getAsJsonObject("8").get("mediterraneo").getAsInt()).isEqualTo(2);
    assertThat(b.get("round_primo_sbarco_usa_6").getAsInt()).isEqualTo(5);
    assertThat(b.getAsJsonObject("trasporti_usa").get("4").getAsInt()).isEqualTo(6);
    assertThat(b.getAsJsonObject("norway_alleata").get("3").getAsBoolean()).isTrue();
    assertThat(b.getAsJsonObject("norway_alleata").get("4").getAsBoolean()).isFalse();
    assertThat(b.getAsJsonObject("reddito_tedesco").get("4").getAsInt()).isEqualTo(38);
    final JsonObject ukraine = b.getAsJsonObject("russe_fine_round").getAsJsonObject("Ukraine");
    assertThat(ukraine.get("4").getAsBoolean()).isTrue();
    assertThat(ukraine.get("6").getAsBoolean()).isFalse();
    assertThat(
            b.getAsJsonObject("attacchi_russi")
                .getAsJsonObject("3")
                .get("nemici_uccisi")
                .getAsInt())
        .isEqualTo(6);
    assertThat(b.getAsJsonObject("pu_riparazioni_russe").get("4").getAsInt()).isEqualTo(5);
    assertThat(b.getAsJsonObject("esercito_tuv").getAsJsonObject("6").get("Germans").getAsInt())
        .isEqualTo(300);
    assertThat(b.getAsJsonObject("cadute").get("mosca").getAsInt()).isEqualTo(15);
    assertThat(b.get("mosca_oltre_r14").getAsBoolean()).isTrue();
    final JsonObject r = b.getAsJsonObject("registro");
    assertThat(r.getAsJsonObject("usa_acquisti_neutrali").get("transport").getAsInt()).isEqualTo(2);
    assertThat(r.get("norway_attaccata").getAsInt()).isEqualTo(1);
    assertThat(r.get("contrattacchi_fatti").getAsInt()).isEqualTo(1);
    assertThat(r.get("contrattacchi_sotto_soglia_standard").getAsInt()).isEqualTo(1);
    assertThat(r.get("tenuti_in_difensiva").getAsInt()).isEqualTo(1);
    assertThat(r.get("pu_risparmiati").getAsInt()).isEqualTo(3);
  }

  @Test
  void theReferenceIsTheConfigurationWithTheAlliesOfHard() {
    final String e2 = "Stratega [con impegno] [con assedio] / Hard";
    final String q1 = "Stratega [con impegno] [con assedio] / Stratega [con usa_pronti]";
    assertThat(ConfrontoP4.riferimento(Set.of(q1, e2))).isEqualTo(e2);
    assertThat(ConfrontoP4.median(List.of(9, 5, 7))).isEqualTo(7);
    assertThat(ConfrontoP4.median(List.of(4, 6))).isEqualTo(5);
  }
}
