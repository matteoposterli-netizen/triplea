package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Step P7: the configurations by switches and the block {@code p7} of a game (F1-F4). */
class ConfrontoP7Test {

  @Test
  void theConfigurationsAreFoundByTheirSwitches() {
    assertThat(ConfrontoP7.codice(Set.of())).isEqualTo("e2");
    assertThat(ConfrontoP7.codice(Set.of("usa_proporzione", "sbarco_concentrato"))).isEqualTo("s2");
    assertThat(
            ConfrontoP7.codice(
                Set.of("usa_proporzione", "sbarco_concentrato", "ponte_uk", "ponte_pronti")))
        .isEqualTo("c2");
    assertThat(
            ConfrontoP7.codice(
                Set.of(
                    "usa_proporzione",
                    "sbarco_concentrato",
                    "niente_neutrali",
                    "ponte_uk",
                    "ponte_pronti",
                    "norvegia_presto",
                    "norvegia_esposta")))
        .isEqualTo("c5");
  }

  @Test
  void theBlockHasTheLandingsTheBridgeheadsTheNeutralsAndTheUk() {
    final JsonObject game =
        JsonParser.parseString(
                """
                {"parametri": {"stratega_acceso": {"Allies": "usa_proporzione,sbarco_concentrato,ponte_uk"}},
                 "dichiarazioni_guerra": [
                  {"round": 7, "nazione_di_turno": "British", "tra": "British - Neutral_True"},
                  {"round": 3, "nazione_di_turno": "Germans", "tra": "Germans - Russians"}],
                 "p6": {"usa_trasporti_persi_r8": 3, "cadute": {"roma": 12}, "mosca_oltre_r14": true},
                 "p7_eventi": {"sbarchi": [
                   {"round": 5, "nazione": "Americans", "territorio": "Normandy Bordeaux",
                    "categoria": "Asse", "unita": 8, "presa": true, "alleato_round_dopo": true,
                    "uk_unita_caricabili": 4, "uk_sbarcate": 3},
                   {"round": 6, "nazione": "Americans", "territorio": "Spain",
                    "categoria": "Neutral_True", "unita": 2},
                   {"round": 7, "nazione": "Americans", "territorio": "Holland Belgium",
                    "categoria": "Asse", "unita": 6, "presa": true, "alleato_round_dopo": false,
                    "uk_unita_caricabili": 2, "uk_sbarcate": 0}],
                   "attacchi_neutrali_alleati": [{"round": 7, "nazione": "British"}],
                   "neutrali_catturate_asse": 5},
                 "round": [
                  {"round": 6, "p7": {"ex_neutrali_asse": 1, "uk": {"pu_terra": 12, "trasporti_persi": 2}}},
                  {"round": 10, "p7": {"ex_neutrali_asse": 3, "uk": {"trasporti_persi": 1}}}],
                 "impegno_decisioni": [
                  {"riga": "decisione", "tipo": "ponte_uk", "round": 5,
                   "motivo": {"codice": "PONTE_RINFORZO"}, "parametri": {"unita_sbarcate": 3}}]}
                """)
            .getAsJsonObject();
    final JsonObject b = ConfrontoP7.blocco(game);
    assertThat(b.get("configurazione").getAsString()).isEqualTo("c1");
    assertThat(b.getAsJsonObject("usa_sbarcate").getAsJsonObject("6").get("totale").getAsInt())
        .isEqualTo(10);
    assertThat(b.getAsJsonObject("usa_sbarcate").getAsJsonObject("8").get("asse").getAsInt())
        .isEqualTo(14);
    final JsonObject t = b.getAsJsonObject("teste_di_ponte");
    assertThat(t.get("sbarchi").getAsInt()).isEqualTo(2);
    assertThat(t.get("tenuti_round_dopo").getAsInt()).isEqualTo(1);
    assertThat(t.get("raggiungibili_2").getAsInt()).isEqualTo(2);
    assertThat(t.get("rinforzati_2").getAsInt()).isEqualTo(1);
    assertThat(t.get("raggiungibili_4").getAsInt()).isEqualTo(1);
    assertThat(t.get("tenuti_con_rinforzo").getAsInt()).isEqualTo(1);
    assertThat(t.get("presi_senza_rinforzo").getAsInt()).isEqualTo(1);
    final JsonObject n = b.getAsJsonObject("f2_neutrali");
    assertThat(n.get("aperta_da_alleati").getAsBoolean()).isTrue();
    assertThat(n.get("round_apertura_alleati").getAsInt()).isEqualTo(7);
    assertThat(n.get("attacchi_alleati_neutrali").getAsInt()).isEqualTo(1);
    assertThat(n.get("usa_sbarcate_neutrali").getAsInt()).isEqualTo(2);
    assertThat(n.getAsJsonObject("ex_neutrali_asse").get("fine").getAsInt()).isEqualTo(3);
    assertThat(b.get("uk_trasporti_persi_r8").getAsInt()).isEqualTo(2);
    assertThat(b.get("usa_trasporti_persi_r8").getAsInt()).isEqualTo(3);
    assertThat(b.getAsJsonObject("registro").get("PONTE_RINFORZO").getAsInt()).isEqualTo(1);
    assertThat(b.getAsJsonObject("registro").get("ponte_unita_sbarcate").getAsInt()).isEqualTo(3);
  }
}
