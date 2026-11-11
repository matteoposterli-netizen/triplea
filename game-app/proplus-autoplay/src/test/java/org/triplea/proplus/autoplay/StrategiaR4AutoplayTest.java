package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step R4: the two readings of the map's victory rule (point 0) and the statistics of the tables of
 * the complete games (Wilson's interval, Fisher's exact test, the median).
 */
class StrategiaR4AutoplayTest {
  @Test
  void theTwoReadingsOfTheVictoryRule() {
    // the condition met at the end of R12, lost, then met at the end of R15 and R16
    final List<Integer> met = List.of(12, 15, 16);
    assertThat(Vittoria.letturaXml(met)).isEqualTo(12);
    assertThat(Vittoria.letturaNote(met, Vittoria.ROUNDS_HELD)).isEqualTo(16);
    assertThat(Vittoria.letturaXml(List.of())).isEqualTo(-1);
    assertThat(Vittoria.letturaNote(List.of(12), 1)).isEqualTo(-1);
    assertThat(Vittoria.letturaNote(List.of(20, 21), 1)).isEqualTo(21);
  }

  @Test
  void wilsonAndFisherGiveTheTextbookValues() {
    final double[] w = ConfrontoR4.wilson(5, 10);
    assertThat(w[0]).isCloseTo(0.2366, within(1e-3));
    assertThat(w[1]).isCloseTo(0.7634, within(1e-3));
    assertThat(ConfrontoR4.fisher(3, 10, 8, 10)).isCloseTo(0.0698, within(1e-3));
    assertThat(ConfrontoR4.fisher(5, 10, 5, 10)).isCloseTo(1.0, within(1e-9));
    assertThat(ConfrontoR4.median(List.of(3.0, 1.0, 2.0, 10.0))).isEqualTo(2.5);
  }

  @Test
  void theCurrentVersionOfANewConfigurationIsWithoutItsSwitch() {
    assertThat(
            ConfrontoR4.attuale("Stratega LLM [senza chiave] [con impegno] [con assedio] / Hard"))
        .isEqualTo("Stratega LLM [senza chiave] [con impegno] / Hard");
    assertThat(ConfrontoR4.attuale("Hard / Stratega LLM [con impegno] [con alleati_r4]"))
        .isEqualTo("Hard / Stratega LLM [con impegno]");
    assertThat(ConfrontoR4.attuale("Hard / Hard")).isNull();
  }
}
