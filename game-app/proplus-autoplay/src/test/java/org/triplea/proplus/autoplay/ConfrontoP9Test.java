package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Step P9+P10: the configurations by switches, the US units landed (transfers to allied land left
 * out) and the block {@code p9} of a game (bridgeheads, concentrated bridge, UK landings).
 */
class ConfrontoP9Test {

  @Test
  void theConfigurationsAreFoundByTheirSwitches() {
    assertThat(ConfrontoP9.codice(Set.of())).isEqualTo("e2");
    assertThat(ConfrontoP9.codice(Set.of("niente_neutrali"))).isEqualTo("n1");
    assertThat(
            ConfrontoP9.codice(
                Set.of("usa_proporzione", "sbarco_concentrato", "ponte_concentrato")))
        .isEqualTo("d1");
    assertThat(
            ConfrontoP9.codice(
                Set.of(
                    "usa_proporzione",
                    "sbarco_concentrato",
                    "niente_neutrali",
                    "ponte_concentrato")))
        .isEqualTo("d2");
    assertThat(
            ConfrontoP9.codice(
                Set.of("usa_proporzione", "sbarco_concentrato", "niente_neutrali", "ponte_uk")))
        .isEqualTo("c3");
  }

  @Test
  void theLandedUnitsLeaveOutTheTransfersToAlliedLand() {
    final List<JsonObject> l =
        List.of(
            o(
                "{\"round\": 5, \"nazione\": \"Americans\", \"categoria\": \"alleato\", \"unita\": 14}"),
            o("{\"round\": 5, \"nazione\": \"Americans\", \"categoria\": \"Asse\", \"unita\": 6}"),
            o("{\"round\": 7, \"nazione\": \"Americans\", \"categoria\": \"Asse\", \"unita\": 12}"),
            o(
                "{\"round\": 7, \"nazione\": \"Americans\", \"categoria\": \"Neutral_True\","
                    + " \"unita\": 3}"),
            o("{\"round\": 6, \"nazione\": \"British\", \"categoria\": \"Asse\", \"unita\": 9}"));
    assertThat(ConfrontoP9.usaSbarcate(l, 6)).isEqualTo(6);
    assertThat(ConfrontoP9.usaSbarcate(l, 8)).isEqualTo(21);
  }

  @Test
  void theBlockHasTheBridgeheadsTheBridgeAndTheUksOwnLandings() {
    final JsonObject game =
        o(
            """
            {"stato": "completata",
             "parametri": {"stratega_acceso": {"Allies": "usa_proporzione,sbarco_concentrato,ponte_concentrato"}},
             "p7_eventi": {"sbarchi": [
               {"round": 7, "nazione": "Americans", "territorio": "Spain", "categoria": "Asse",
                "unita": 8, "presa": true, "alleato_round_dopo": true, "uk_unita_caricabili": 9,
                "uk_sbarcate": 9, "difensori_fine_uk": 17, "asse_in_portata": 30,
                "contrattaccata": true, "contrattaccata_da": "Germans",
                "esito_contrattacco": "respinto",
                "contrattacchi": [{"da": "Germans", "attaccanti": 20, "difensori": 17,
                                   "perse_asse": 12, "perse_alleate": 9, "esito": "respinto"}]},
               {"round": 8, "nazione": "Americans", "territorio": "France", "categoria": "Asse",
                "unita": 6, "presa": true, "alleato_round_dopo": false, "uk_unita_caricabili": 0,
                "contrattaccata": true, "esito_contrattacco": "presa_asse",
                "contrattaccata_da": "Germans", "contrattacchi": []},
               {"round": 8, "nazione": "Americans", "territorio": "Morocco", "categoria": "alleato",
                "unita": 7, "presa": true},
               {"round": 8, "nazione": "British", "territorio": "Norway", "categoria": "Asse",
                "unita": 4, "presa": true, "alleato_round_dopo": false}]},
             "impegno_decisioni": [
              {"riga": "decisione", "tipo": "ponte_concentrato", "round": 6,
               "motivo": {"codice": "PONTE_CONC_NESSUN_CANDIDATO"}, "parametri": {"candidati": []}},
              {"riga": "decisione", "tipo": "ponte_concentrato", "round": 7,
               "motivo": {"codice": "PONTE_CONC_RINFORZO"},
               "parametri": {"bersaglio": "Spain", "unita_mandate": 9, "unita_che_possono_arrivare": 9,
                 "candidati": [{"territorio": "Spain", "vittoria_asse_prima": 77.2,
                                "vittoria_asse_dopo": 10.0, "stato": "sotto_soglia"}]}},
              {"riga": "decisione", "tipo": "ponte_concentrato", "round": 8,
               "motivo": {"codice": "PONTE_CONC_GIA_SICURO"},
               "parametri": {"candidati": [{"territorio": "Morocco", "vittoria_asse_prima": 0.0,
                                            "vittoria_asse_dopo": 0.0, "stato": "gia_sicuro"}]}}]}
            """);
    final JsonObject b = ConfrontoP9.blocco(game);
    assertThat(b.get("configurazione").getAsString()).isEqualTo("d1");
    assertThat(b.get("valida").getAsBoolean()).isTrue();
    assertThat(b.getAsJsonObject("usa_sbarcate").get("8").getAsInt()).isEqualTo(14);
    final JsonObject t = b.getAsJsonObject("teste_di_ponte");
    assertThat(t.get("prese").getAsInt()).isEqualTo(2);
    assertThat(t.get("contrattaccate").getAsInt()).isEqualTo(2);
    assertThat(t.get("riprese_dall_asse").getAsInt()).isEqualTo(1);
    assertThat(t.get("tenute_round_dopo").getAsInt()).isEqualTo(1);
    assertThat(t.get("raggiungibili_tenute").getAsInt()).isEqualTo(1);
    assertThat(t.get("bersagli_tenuti").getAsInt()).isEqualTo(1);
    final JsonObject head = b.getAsJsonArray("teste").get(0).getAsJsonObject();
    assertThat(head.get("vittoria_asse_prima").getAsDouble()).isEqualTo(77.2);
    assertThat(head.get("bersaglio_ponte").getAsBoolean()).isTrue();
    final JsonObject pc = b.getAsJsonObject("ponte_concentrato");
    assertThat(pc.get("turni").getAsInt()).isEqualTo(3);
    assertThat(pc.get("rinforzi").getAsInt()).isEqualTo(1);
    assertThat(pc.get("rinforzi_con_tutto_il_carico").getAsInt()).isEqualTo(1);
    assertThat(pc.get("round_con_piu_bersagli").getAsInt()).isZero();
    assertThat(pc.getAsJsonObject("motivi").get("PONTE_CONC_GIA_SICURO").getAsInt()).isEqualTo(1);
    assertThat(pc.getAsJsonArray("vittoria_asse_dopo_candidati")).hasSize(2);
    final JsonObject uk = b.getAsJsonObject("uk_sbarchi_propri");
    assertThat(uk.get("sbarchi").getAsInt()).isEqualTo(1);
    assertThat(uk.get("unita").getAsInt()).isEqualTo(4);
    assertThat(uk.get("tenuti").getAsInt()).isZero();
    // a game in error is not valid
    game.addProperty("stato", "errore: java.lang.IllegalStateException");
    assertThat(ConfrontoP9.blocco(game).get("valida").getAsBoolean()).isFalse();
  }

  @Test
  void theSectionHasTheSevenConfigurationsAndTheSixComparisons() {
    assertThat(ConfrontoP9.CODICI.keySet())
        .containsExactly("e2", "n1", "s2", "s5", "c3", "d1", "d2");
    assertThat(ConfrontoP9.CONFRONTI).hasSize(6);
  }

  private static JsonObject o(final String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }
}
