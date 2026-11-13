package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Step R6c: the comparison h against e2 (i against f3) and Italy's rows. */
class ConfrontoItaliaTest {
  private static final String E2 = "Stratega LLM [senza chiave] [con impegno] [con assedio] / Hard";
  private static final String H =
      "Stratega LLM [senza chiave] [con impegno] [con assedio] [con italia_convoglio] / Hard";

  @Test
  void hIsComparedWithE2() {
    assertThat(ConfrontoItalia.senzaConvoglio(H)).isEqualTo(E2);
    assertThat(ConfrontoItalia.senzaConvoglio(E2)).isEqualTo(E2);
  }

  @Test
  void northAfricaWholeAndTheItalianBonusesAreReadFromTheRounds() {
    final var game =
        JsonParser.parseString(
                """
                {"round": [
                  {"round": 1, "r4": {"obiettivi": {"Italians": {
                    "objectiveAttachment_Italians_3_North_Africa": 5,
                    "objectiveAttachment_Italians_2_Roman_Empire": 5}}}},
                  {"round": 2, "r4": {"obiettivi": {"Italians": {
                    "objectiveAttachment_Italians_3_North_Africa": 0,
                    "objectiveAttachment_Italians_2_Roman_Empire": 5}}}}]}
                """)
            .getAsJsonObject();
    assertThat(ConfrontoItalia.nordAfrica(game)).isEqualTo(1.0);
    assertThat(ConfrontoItalia.bonus(game, 1)).isEqualTo(10.0);
    assertThat(ConfrontoItalia.bonus(game, 2)).isEqualTo(5.0);
    assertThat(ConfrontoItalia.bonus(game, 3)).isNaN();
  }
}
