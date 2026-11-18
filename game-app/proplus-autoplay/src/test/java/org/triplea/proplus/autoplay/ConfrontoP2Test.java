package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Step P2: the block {@code p2} of a game (direction, joint landings, effect on the objective). */
class ConfrontoP2Test {

  @Test
  void theBlockCountsTheDirectionTheJointLandingsAndTheEffectOnTheObjective() {
    final JsonObject game =
        JsonParser.parseString(
                """
                {"round_giocati": 12,
                 "capitali": [{"round": 10, "territorio": "Southern Italy", "evento": "caduta"},
                              {"round": 11, "territorio": "Southern Italy", "evento": "liberata"},
                              {"round": 9, "territorio": "Russia", "evento": "caduta"}],
                 "round": [
                  {"round": 8, "nazioni": {"Americans": {"trasporti_persi": 1},
                     "British": {"trasporti": 7, "trasporti_inattivi": 3},
                     "Italians": {"reddito": 14}},
                   "p1": {"regioni_usa": {"mediterraneo_nord_africa": 5}},
                   "p2": {"egitto_asse": true, "a_bordo": {"Americans": 6, "British": 2},
                     "sbarchi_usa": [{"territorio": "Normandy Bordeaux", "unita_usa_sbarcate": 8,
                        "usa_fine_round": 8, "britannici_fine_round": 3, "alleato_fine_round": true},
                       {"territorio": "Holland Belgium", "unita_usa_sbarcate": 2,
                        "usa_fine_round": 0, "britannici_fine_round": 0, "alleato_fine_round": false}],
                     "sbarchi_uk": [], "sbarchi_usa_round_prima": []}},
                  {"round": 9, "nazioni": {"Americans": {"trasporti_persi": 0}},
                   "p2": {"egitto_asse": false, "a_bordo": {"Americans": 0, "British": 0},
                     "sbarchi_usa": [], "sbarchi_uk": [],
                     "sbarchi_usa_round_prima": [
                       {"territorio": "Normandy Bordeaux", "alleato": true},
                       {"territorio": "Holland Belgium", "alleato": false}]}}],
                 "impegno_decisioni": [
                  {"riga": "decisione", "tipo": "alleanza_direzione", "round": 1,
                   "parametri": {"scelta": "MEDITERRANEO", "conti": {}},
                   "motivo": {"codice": "DIREZIONE_SCELTA"}},
                  {"riga": "decisione", "tipo": "alleanza_direzione", "round": 7,
                   "parametri": {"scelta": "FRANCIA", "conti": {}},
                   "motivo": {"codice": "DIREZIONE_CAMBIATA"}},
                  {"riga": "decisione", "tipo": "alleanza_risultato_condiviso", "round": 8,
                   "parametri": {}, "motivo": {"codice": "CONGIUNTO"}},
                  {"riga": "decisione", "tipo": "alleanza_risultato_condiviso", "round": 9,
                   "parametri": {}, "motivo": {"codice": "LOGORAMENTO"}},
                  {"riga": "decisione", "tipo": "sbarco", "round": 9,
                   "parametri": {"compito": true, "decisione_alleanza": "LOGORAMENTO"},
                   "motivo": {"codice": "ESEGUITO"}}]}
                """)
            .getAsJsonObject();
    final JsonObject b = ConfrontoP2.blocco(game);
    assertThat(b.getAsJsonObject("round_per_direzione").get("MEDITERRANEO").getAsInt()).isEqualTo(6);
    assertThat(b.getAsJsonObject("round_per_direzione").get("FRANCIA").getAsInt()).isEqualTo(6);
    assertThat(b.getAsJsonObject("direzione_per_round").get("8").getAsString()).isEqualTo("FRANCIA");
    assertThat(b.get("prima_direzione").getAsString()).isEqualTo("MEDITERRANEO");
    assertThat(b.get("sbarchi_usa_con_francia").getAsInt()).isEqualTo(2);
    assertThat(b.get("sbarchi_usa_con_francia_britannici_fine_round").getAsInt()).isEqualTo(1);
    assertThat(b.get("sbarchi_congiunti_con_francia").getAsInt()).isEqualTo(1);
    assertThat(b.get("sbarchi_congiunti_con_francia_tenuti_round_dopo").getAsInt()).isEqualTo(1);
    assertThat(b.get("sbarchi_usa_tenuti_round_dopo").getAsInt()).isEqualTo(1);
    assertThat(b.get("sbarchi_logoramento").getAsInt()).isEqualTo(1);
    assertThat(b.getAsJsonObject("decisioni_condivise").get("CONGIUNTO").getAsInt()).isEqualTo(1);
    assertThat(b.get("round_egitto_asse").getAsInt()).isEqualTo(1);
    assertThat(b.get("round_con_fotografia").getAsInt()).isEqualTo(2);
    assertThat(b.getAsJsonObject("cadute").get("roma").getAsInt()).isEqualTo(10);
    assertThat(b.getAsJsonObject("cadute").get("mosca").getAsInt()).isEqualTo(9);
    assertThat(b.getAsJsonObject("cadute").has("berlino")).isFalse();
    final JsonObject r8 = b.getAsJsonObject("per_round").getAsJsonObject("8");
    assertThat(r8.get("a_bordo_totale").getAsInt()).isEqualTo(8);
    assertThat(r8.get("trasporti_uk_inattivi").getAsInt()).isEqualTo(3);
    assertThat(r8.get("reddito_italia").getAsInt()).isEqualTo(14);
    assertThat(r8.get("usa_mediterraneo_nord_africa").getAsInt()).isEqualTo(5);
    assertThat(b.get("trasporti_usa_persi").getAsInt()).isEqualTo(1);
  }
}
