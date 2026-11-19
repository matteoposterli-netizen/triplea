package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Step P5: the block {@code p5} of a game and the reference configuration of the section. */
class ConfrontoP5Test {

  @Test
  void theBlockCountsPurchasesForceLandingsLossesNorwayAndTheRegister() {
    final JsonObject game =
        JsonParser.parseString(
                """
                {"alleanze": {"Axis": ["Germans"], "Allies": ["Americans", "British", "Russians"]},
                 "sbarchi": [
                  {"round": 5, "nazione": "Americans", "territorio": "Normandy Bordeaux", "unita": 4},
                  {"round": 5, "nazione": "Americans", "territorio": "Normandy Bordeaux", "unita": 3},
                  {"round": 7, "nazione": "Americans", "territorio": "Morocco", "unita": 2},
                  {"round": 6, "nazione": "British", "territorio": "Norway", "unita": 9}],
                 "capitali": [{"round": 12, "territorio": "Russia", "evento": "caduta"},
                              {"round": 9, "territorio": "Germany", "evento": "caduta"}],
                 "round": [
                  {"round": 1, "p5": {"usa_acquisti_pu": {"trasporti": 14, "navi": 8}}},
                  {"round": 2, "p4": {"proprietari": {"Norway": "British"}},
                   "p5": {"perse": {"uk_navi_da_guerra": 1}}},
                  {"round": 3, "p5": {"usa_trasporti": 6, "usa_navi_da_guerra": 2,
                     "usa_terra_in_patria": 3, "usa_terra_a_bordo": 12},
                   "p4": {"proprietari": {"Norway": "Germans"}}},
                  {"round": 4, "nazioni": {"Germans": {"reddito": 38}},
                   "p5": {"usa_trasporti": 7, "usa_terra_in_patria": 9, "usa_terra_a_bordo": 12,
                     "perse": {"uk_navi_da_guerra": 2, "usa_trasporti": 1}},
                   "p4": {"proprietari": {"Norway": "British"}}},
                  {"round": 6, "p4": {"proprietari": {"Norway": "British"},
                     "esercito_tuv": {"Russians": 90, "Germans": 300}},
                   "p5": {"perse": {"usa_trasporti": 2, "uk_navi_da_guerra": 4}}},
                  {"round": 9, "p5": {"perse": {"usa_trasporti": 5}}}],
                 "impegno_decisioni": [
                  {"riga": "decisione", "tipo": "usa_proporzione", "round": 1,
                   "motivo": {"codice": "PIANO_NEUTRALE"},
                   "parametri": {"passo_1_trasporti": {"trasporti": 2, "pu": 14},
                     "passo_2_scorta": {"pu": 8}, "passo_3_carichi": {"carichi": 1},
                     "convoglio_regge": true}},
                  {"riga": "decisione", "tipo": "usa_proporzione", "round": 3,
                   "motivo": {"codice": "CARICATE"}, "parametri": {"truppe_caricate_n": 10}},
                  {"riga": "decisione", "tipo": "norvegia_scorta", "round": 2,
                   "motivo": {"codice": "SCORTA_TROVATA"}, "parametri": {"preso": true}},
                  {"riga": "decisione", "tipo": "norvegia_scorta", "round": 3,
                   "motivo": {"codice": "SCORTA_MANCANTE"}, "parametri": {}},
                  {"riga": "decisione", "tipo": "norvegia_scorta", "round": 3,
                   "motivo": {"codice": "PERDITE_SCORTA"},
                   "parametri": {"navi_scorta": 2, "navi_scorta_perse": 1}}]}
                """)
            .getAsJsonObject();
    final JsonObject b = ConfrontoP5.blocco(game);
    assertThat(
            b.getAsJsonObject("usa_acquisti_pu").getAsJsonObject("1").get("trasporti").getAsInt())
        .isEqualTo(14);
    assertThat(b.getAsJsonObject("usa_acquisti_pu").getAsJsonObject("1").get("terra").getAsInt())
        .isZero();
    final JsonObject r4 = b.getAsJsonObject("usa_forza").getAsJsonObject("4");
    assertThat(r4.get("trasporti").getAsInt()).isEqualTo(7);
    assertThat(r4.get("terra_patria_o_bordo").getAsInt()).isEqualTo(21);
    assertThat(b.get("round_primo_sbarco_usa_6").getAsInt()).isEqualTo(5);
    final JsonObject cum = b.getAsJsonObject("usa_sbarcate_cumulate");
    assertThat(cum.getAsJsonObject("6").get("totale").getAsInt()).isEqualTo(7);
    assertThat(cum.getAsJsonObject("8").get("totale").getAsInt()).isEqualTo(9);
    assertThat(cum.getAsJsonObject("8").getAsJsonObject("dove").get("Morocco").getAsInt())
        .isEqualTo(2);
    assertThat(b.get("usa_trasporti_persi_r8").getAsInt()).isEqualTo(3);
    assertThat(b.get("uk_navi_perse_r4").getAsInt()).isEqualTo(3);
    assertThat(b.getAsJsonObject("norway_alleata").get("2").getAsBoolean()).isTrue();
    assertThat(b.getAsJsonObject("norway_alleata").get("3").getAsBoolean()).isFalse();
    assertThat(b.getAsJsonObject("reddito_tedesco").get("4").getAsInt()).isEqualTo(38);
    assertThat(b.getAsJsonObject("cadute").get("mosca").getAsInt()).isEqualTo(12);
    assertThat(b.get("mosca_oltre_r14").getAsBoolean()).isFalse();
    final JsonObject r = b.getAsJsonObject("registro");
    assertThat(r.get("usa_turni_piano").getAsInt()).isEqualTo(1);
    assertThat(r.get("usa_pu_trasporti_piano").getAsInt()).isEqualTo(14);
    assertThat(r.get("usa_truppe_caricate").getAsInt()).isEqualTo(10);
    assertThat(r.get("norway_scorta_trovata").getAsInt()).isEqualTo(1);
    assertThat(r.get("norway_scorta_mancante").getAsInt()).isEqualTo(1);
    assertThat(r.get("norway_scortata_presa").getAsInt()).isEqualTo(1);
    assertThat(r.get("norway_navi_scorta_perse").getAsInt()).isEqualTo(1);
  }

  @Test
  void theReferenceIsTheConfigurationWithoutTheSwitches() {
    assertThat(
            ConfrontoP5.riferimento(
                Set.of(
                    "Asse Stratega impegno+assedio / Alleati Stratega usa_proporzione",
                    "Asse Stratega impegno+assedio / Alleati Hard",
                    "Asse Stratega impegno+assedio / Alleati Stratega norvegia_presto")))
        .isEqualTo("Asse Stratega impegno+assedio / Alleati Hard");
  }
}
