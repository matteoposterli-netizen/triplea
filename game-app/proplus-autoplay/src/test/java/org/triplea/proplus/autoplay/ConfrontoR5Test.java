package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step R5: the comparisons of the subsection "R5" (e1 against a, e3 against e2), the periods and
 * regions of the landings, the program's landings separated from Hard's, the n-th change of owner
 * of a city.
 */
class ConfrontoR5Test {
  private static final String A = "Hard / Hard";
  private static final String E1 =
      "Hard / Stratega LLM [senza chiave] [con impegno] [con alleati_r5]";
  private static final String E2 = "Stratega LLM [senza chiave] [con impegno] [con assedio] / Hard";
  private static final String E3 =
      "Stratega LLM [senza chiave] [con impegno] [con assedio] / Stratega LLM [senza chiave] [con"
          + " impegno] [con alleati_r5]";

  @Test
  void e1IsComparedWithAAndE3WithE2() {
    final List<String> configs = List.of(A, E1, E2, E3);
    assertThat(ConfrontoR5.confronto(E1, configs, A)).isEqualTo(A);
    assertThat(ConfrontoR5.confronto(E3, configs, A)).isEqualTo(E2);
    assertThat(ConfrontoR5.confronto(E2, configs, A)).isNull();
    assertThat(ConfrontoR5.confronto(A, configs, A)).isNull();
  }

  @Test
  void periodsAndRegionsOfTheLandings() {
    assertThat(ConfrontoR5.periodo(6)).isEqualTo("R1-6");
    assertThat(ConfrontoR5.periodo(7)).isEqualTo("R7-12");
    assertThat(ConfrontoR5.periodo(18)).isEqualTo("R13-18");
    assertThat(ConfrontoR5.periodo(19)).isEqualTo("R19+");
    assertThat(ConfrontoR5.regione("Normandy Bordeaux")).isEqualTo("europa_occidentale");
    assertThat(ConfrontoR5.regione("Sicily")).isEqualTo("italia_isole");
    assertThat(ConfrontoR5.regione("Tunisia")).isEqualTo("nord_africa");
    assertThat(ConfrontoR5.regione("Persia")).isEqualTo("altrove");
  }

  @Test
  void theProgramsLandingsAreSeparatedFromTheMonitorsTotal() {
    final var game =
        JsonParser.parseString(
                """
                {"sbarchi": [
                  {"round": 8, "nazione": "Americans", "territorio": "Southern France", "unita": 10},
                  {"round": 8, "nazione": "British", "territorio": "Normandy Bordeaux", "unita": 3},
                  {"round": 8, "nazione": "Russians", "territorio": "Finland", "unita": 3}],
                 "impegno_decisioni": [
                  {"riga": "decisione", "tipo": "sbarco", "round": 8, "nazione": "Americans",
                   "alternative": [{"territorio": "Southern France", "unita_sbarcate": 8}]}]}
                """)
            .getAsJsonObject();
    final var m = ConfrontoR5.sbarchiPartita(game);
    assertThat(m).containsEntry("totale|R7-12|europa_occidentale", 13.0);
    assertThat(m).containsEntry("programma|R7-12|europa_occidentale", 8.0);
  }

  @Test
  void theNthChangeOfOwnerOfACity() {
    final var game =
        JsonParser.parseString(
                """
                {"alleanze": {"Axis": ["Germans"], "Allies": ["French", "British"]},
                 "round": [
                  {"round": 1, "r4": {"proprietari": {"Parigi": "French"}}},
                  {"round": 2, "r4": {"proprietari": {"Parigi": "Germans"}}},
                  {"round": 9, "r4": {"proprietari": {"Parigi": "British"}}}]}
                """)
            .getAsJsonObject();
    assertThat(ConfrontoR5.cambioRound(game, "Parigi", 1)).isEqualTo(2);
    assertThat(ConfrontoR5.cambioRound(game, "Parigi", 2)).isEqualTo(9);
    assertThat(ConfrontoR5.cambioRound(game, "Parigi", 3)).isEqualTo(-1);
  }
}
