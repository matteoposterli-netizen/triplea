package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Step R6: the comparisons of the subsection "R6", the squadron's rows, the Axis share label. */
class ConfrontoR6Test {
  private static final String A = "Hard / Hard";
  private static final String F1 =
      "Hard / Stratega LLM [senza chiave] [con impegno] [con squadra_gibilterra]";
  private static final String E2 = "Stratega LLM [senza chiave] [con impegno] [con assedio] / Hard";
  private static final String F3 =
      "Stratega LLM [senza chiave] [con impegno] [con assedio] / Stratega LLM [senza chiave] [con"
          + " impegno] [con squadra_gibilterra]";
  private static final String G =
      "Stratega LLM [senza chiave] [con impegno] [con assedio] [quota 80] / Hard";

  @Test
  void f1IsComparedWithAndF3AndGWithE2() {
    final List<String> configs = List.of(A, F1, E2, F3, G);
    assertThat(ConfrontoR6.confronto(F1, configs, A)).containsExactly(A, "Allies", "f1 contro (a)");
    assertThat(ConfrontoR6.confronto(F3, configs, A)).containsExactly(E2, "Allies", "f3 contro e2");
    assertThat(ConfrontoR6.confronto(G, configs, A)).containsExactly(E2, "Axis", "g contro e2");
    assertThat(ConfrontoR6.confronto(E2, configs, A)).isNull();
    assertThat(ConfrontoR6.confronto(A, configs, A)).isNull();
  }

  @Test
  void theSquadronsRowsAreReadFromTheRegister() {
    final var game =
        JsonParser.parseString(
                """
                {"impegno_decisioni": [
                  {"riga": "decisione", "tipo": "squadra_costruzione", "round": 1,
                   "nazione": "Americans", "alternative": [{"pu_spesi": 35, "pu_turno": 35}]},
                  {"riga": "decisione", "tipo": "squadra_costruzione", "round": 2,
                   "nazione": "Americans", "alternative": [{"pu_spesi": 30, "pu_turno": 35}]},
                  {"riga": "decisione", "tipo": "squadra_arrivo", "round": 5,
                   "nazione": "Americans", "alternative": [{"navi": {"cruiser": 1, "carrier": 1},
                   "aerei": {"fighter": 2}, "trasporti_carichi": 4, "unita_di_terra": 8}]}]}
                """)
            .getAsJsonObject();
    assertThat(ConfrontoR6.puCostruzione(game)).containsExactly(65.0, 70.0);
    assertThat(ConfrontoR6.prima(game, "squadra_arrivo").get("round").getAsInt()).isEqualTo(5);
    assertThat(
            ConfrontoR6.composizione(ConfrontoR5.alt0(ConfrontoR6.prima(game, "squadra_arrivo"))))
        .isEqualTo("1 cruiser, 1 carrier, 2 fighter");
    assertThat(ConfrontoR6.prima(game, "squadra_partenza")).isNull();
  }

  @Test
  void theRoundBands() {
    assertThat(ConfrontoR6.banda(3)).isEqualTo("R1-4");
    assertThat(ConfrontoR6.banda(5)).isEqualTo("R5");
    assertThat(ConfrontoR6.banda(10)).isEqualTo("R9-12");
    assertThat(ConfrontoR6.banda(20)).isEqualTo("R19+");
  }

  @Test
  void theAxisShareIsInTheLabelAndTheOptions() {
    final var json =
        JsonParser.parseString(
                """
                {"parametri": {"chiave_llm": false, "stratega_acceso": {"Axis": "impegno,assedio"},
                 "quota_operazione": {"Axis": 80.0}}}
                """)
            .getAsJsonObject();
    assertThat(RiepilogoGenerale.strategistLabel(json, "Axis"))
        .isEqualTo(" [senza chiave] [con impegno] [con assedio] [quota 80]");
    final AutoplayOptions o =
        AutoplayOptions.parse(new String[] {"--mappa", "x.xml", "--quota-operazione", "Axis=80"});
    assertThat(o.quotaOperazione("Axis")).isEqualTo(80.0);
    assertThat(o.quotaOperazione("Allies")).isNull();
  }
}
