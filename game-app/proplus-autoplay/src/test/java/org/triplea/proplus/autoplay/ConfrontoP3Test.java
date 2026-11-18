package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Step P3: the block {@code p3} of a game (Egypt, the city's register, the bridgehead weight). */
class ConfrontoP3Test {

  @Test
  void theBlockCountsEgyptTheCityAndTheBridgeheads() {
    final JsonObject game =
        JsonParser.parseString(
                """
                {"round_giocati": 2,
                 "capitali": [{"round": 2, "territorio": "United Kingdom", "evento": "caduta"}],
                 "round": [
                  {"round": 1, "p2": {"egitto_asse": false, "uk_egitto": 5}},
                  {"round": 2, "p2": {"egitto_asse": true, "uk_egitto": 0}}],
                 "p2": {"round_egitto_asse": 1, "round_con_fotografia": 2,
                   "sbarchi_usa": [
                     {"territorio": "Normandy Bordeaux", "britannici_fine_round": 2,
                      "alleato_round_dopo": true},
                     {"territorio": "Denmark", "britannici_fine_round": 0,
                      "alleato_round_dopo": false}],
                   "p2bis": {"unita_sbarcate": {"Americans": {"totale": 10}, "British": {"totale": 4}},
                     "trasporti_posseduti": {"British": {"12": 9}}}},
                 "impegno_decisioni": [
                  {"riga": "decisione", "tipo": "cairo_tenuta", "round": 1,
                   "parametri": {"riservate": 3, "acquisto": {"pu": 6},
                     "non_combattimento": {"via_mare": 2, "via_terra": 1, "via_aria": 1,
                       "viaggi": [{"consegna_da": "Union of South Africa", "truppe": {"infantry": 2}}]},
                     "combattimento": {"attacco_minaccia_eseguito": true}},
                   "motivo": {"codice": "MINACCIATO"}},
                  {"riga": "decisione", "tipo": "ponte_peso", "round": 1,
                   "parametri": {"teste": [{"territorio": "Normandy Bordeaux"}], "unita_mandate": 3,
                     "esiti": [{"via_mare": 2}]},
                   "motivo": {"codice": "PESO_SEGUITO"}}]}
                """)
            .getAsJsonObject();
    final JsonObject b = ConfrontoP3.blocco(game);
    assertThat(b.getAsJsonObject("egitto_asse_fine_round").get("1").getAsBoolean()).isFalse();
    assertThat(b.getAsJsonObject("egitto_asse_fine_round").get("2").getAsBoolean()).isTrue();
    assertThat(b.get("uk_in_egitto_r1").getAsInt()).isEqualTo(5);
    assertThat(b.get("portate_in_egitto_via_mare").getAsInt()).isEqualTo(4);
    assertThat(b.get("portate_in_egitto_via_terra").getAsInt()).isEqualTo(1);
    assertThat(b.get("pu_cairo").getAsInt()).isEqualTo(6);
    assertThat(b.get("attacchi_alla_minaccia").getAsInt()).isEqualTo(1);
    assertThat(b.get("uk_mandate_teste_di_ponte").getAsInt()).isEqualTo(3);
    assertThat(b.get("uk_mandate_teste_di_ponte_via_mare").getAsInt()).isEqualTo(2);
    assertThat(b.get("sbarchi_alleati").getAsInt()).isEqualTo(2);
    assertThat(b.get("sbarchi_alleati_con_britannici").getAsInt()).isEqualTo(1);
    assertThat(b.get("sbarchi_alleati_con_britannici_ancora_alleati").getAsInt()).isEqualTo(1);
    assertThat(b.get("unita_sbarcate_usa_uk").getAsInt()).isEqualTo(14);
    assertThat(b.get("trasporti_uk_r12").getAsInt()).isEqualTo(9);
    assertThat(b.get("regno_unito_perso").getAsBoolean()).isTrue();
    assertThat(b.get("regno_unito_perso_round").getAsInt()).isEqualTo(2);
  }

  @Test
  void theComparisonWithoutP3DropsOnlyTheSwitch() {
    final String p3 = "Asse [con assedio] / Alleati [con compiti] [con regno_unito_p3]";
    assertThat(
            ConfrontoP3.senzaP3(
                p3, java.util.Set.of(p3, "Asse [con assedio] / Alleati [con compiti]")))
        .isEqualTo("Asse [con assedio] / Alleati [con compiti]");
  }
}
