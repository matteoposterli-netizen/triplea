package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Step P6: the block {@code p6} of a game (landings by size, losses, Germans, Norway, register).
 */
class ConfrontoP6Test {

  @Test
  void theSizesOfALanding() {
    assertThat(ConfrontoP6.taglia(1)).isEqualTo("1-2");
    assertThat(ConfrontoP6.taglia(2)).isEqualTo("1-2");
    assertThat(ConfrontoP6.taglia(3)).isEqualTo("3-5");
    assertThat(ConfrontoP6.taglia(6)).isEqualTo("6-9");
    assertThat(ConfrontoP6.taglia(10)).isEqualTo("10+");
  }

  @Test
  void theBlockCountsLandingsBySizeLossesGermansNorwayAndTheRegister() {
    final JsonObject game =
        JsonParser.parseString(
                """
                {"alleanze": {"Axis": ["Germans"], "Allies": ["Americans", "British", "Russians"]},
                 "sbarchi": [
                  {"round": 5, "nazione": "Americans", "territorio": "Normandy Bordeaux", "unita": 4},
                  {"round": 5, "nazione": "Americans", "territorio": "Normandy Bordeaux", "unita": 3},
                  {"round": 6, "nazione": "Americans", "territorio": "Sicily", "unita": 2},
                  {"round": 9, "nazione": "Americans", "territorio": "Southern Italy", "unita": 11},
                  {"round": 2, "nazione": "British", "territorio": "Norway", "unita": 4}],
                 "capitali": [{"round": 16, "territorio": "Russia", "evento": "caduta"}],
                 "round": [
                  {"round": 2, "p4": {"proprietari": {"Norway": "British"}},
                   "p6": {"uk_trasporti_persi": 1}},
                  {"round": 3, "p6": {"uk_trasporti_persi": 1},
                   "p5": {"perse": {"uk_navi_da_guerra": 2, "usa_trasporti": 1}}},
                  {"round": 4, "nazioni": {"Germans": {"reddito": 40}},
                   "p4": {"proprietari": {"Norway": "Germans"}}},
                  {"round": 6, "p6": {"tedeschi_terra": 90, "tedeschi_terra_ovest": 30,
                     "usa_a_bordo_3_round": 2},
                   "p4": {"esercito_tuv": {"Russians": 100, "Germans": 300}}},
                  {"round": 9, "p5": {"perse": {"usa_trasporti": 4}}}],
                 "impegno_decisioni": [
                  {"riga": "decisione", "tipo": "sbarco_concentrato", "round": 5,
                   "motivo": {"codice": "SBARCO_IN_FORZA"}, "parametri": {"unita_sbarcate": 7}},
                  {"riga": "decisione", "tipo": "sbarco_concentrato", "round": 6,
                   "motivo": {"codice": "ATTESA"}, "parametri": {"oltre_un_round": 1}},
                  {"riga": "decisione", "tipo": "sbarco_concentrato", "round": 5,
                   "motivo": {"codice": "ESITO_SBARCO"}, "parametri": {"preso": true}},
                  {"riga": "decisione", "tipo": "sbarco_concentrato", "round": 6,
                   "motivo": {"codice": "SEGUITO_SBARCO"},
                   "parametri": {"ancora_alleato": true, "trasporti_persi": 2}},
                  {"riga": "decisione", "tipo": "norvegia_esposta", "round": 2,
                   "motivo": {"codice": "SBARCO_ESPOSTO"},
                   "parametri": {"preso": true, "truppe_sbarcate": 4,
                     "zone": [{"scorta_basta": false}]}},
                  {"riga": "decisione", "tipo": "norvegia_esposta", "round": 3,
                   "motivo": {"codice": "PERDITE_ESPOSTO"},
                   "parametri": {"trasporti": 1, "trasporti_persi": 1, "navi_scorta": 0,
                     "navi_scorta_perse": 0}}]}
                """)
            .getAsJsonObject();
    final JsonObject b = ConfrontoP6.blocco(game);
    final JsonObject sizes = b.getAsJsonObject("sbarchi_usa_taglie_r8");
    assertThat(sizes.get("6-9").getAsInt()).isEqualTo(1);
    assertThat(sizes.get("1-2").getAsInt()).isEqualTo(1);
    assertThat(sizes.get("10+").getAsInt()).isZero();
    assertThat(b.get("sbarchi_usa_6_entro_r8").getAsInt()).isEqualTo(1);
    assertThat(b.get("round_primo_sbarco_usa_6").getAsInt()).isEqualTo(5);
    final JsonObject cum = b.getAsJsonObject("usa_sbarcate_cumulate");
    assertThat(cum.getAsJsonObject("6").get("totale").getAsInt()).isEqualTo(9);
    assertThat(cum.getAsJsonObject("10").get("totale").getAsInt()).isEqualTo(20);
    assertThat(b.get("usa_trasporti_persi_r8").getAsInt()).isEqualTo(1);
    assertThat(b.get("usa_trasporti_persi_totale").getAsInt()).isEqualTo(5);
    assertThat(b.getAsJsonObject("uk_perse_r4").get("trasporti").getAsInt()).isEqualTo(2);
    assertThat(b.getAsJsonObject("uk_perse_r4").get("navi_da_guerra").getAsInt()).isEqualTo(2);
    assertThat(b.getAsJsonObject("tedeschi").getAsJsonObject("6").get("ovest").getAsInt())
        .isEqualTo(30);
    assertThat(b.get("usa_a_bordo_3_round_max").getAsInt()).isEqualTo(2);
    assertThat(b.getAsJsonObject("norway_alleata").get("2").getAsBoolean()).isTrue();
    assertThat(b.getAsJsonObject("norway_alleata").get("4").getAsBoolean()).isFalse();
    assertThat(b.getAsJsonObject("norway_sbarchi_uk").get("unita").getAsInt()).isEqualTo(4);
    assertThat(b.getAsJsonObject("reddito_tedesco").get("4").getAsInt()).isEqualTo(40);
    assertThat(b.get("mosca_oltre_r14").getAsBoolean()).isTrue();
    final JsonObject r = b.getAsJsonObject("registro");
    assertThat(r.get("sc_in_forza").getAsInt()).isEqualTo(1);
    assertThat(r.get("sc_in_forza_unita").getAsInt()).isEqualTo(7);
    assertThat(r.get("sc_turni_oltre_un_round").getAsInt()).isEqualTo(1);
    assertThat(r.get("sc_presi").getAsInt()).isEqualTo(1);
    assertThat(r.get("sc_trasporti_persi_dopo").getAsInt()).isEqualTo(2);
    assertThat(r.get("es_sbarchi").getAsInt()).isEqualTo(1);
    assertThat(r.get("es_scorta_basta").getAsInt()).isZero();
    assertThat(r.get("es_trasporti_persi").getAsInt()).isEqualTo(1);
  }
}
