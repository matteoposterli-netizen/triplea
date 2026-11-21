package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/** Step P11: the configurations against the Axis of Hard and the section "P11". */
class ConfrontoP11Test {

  @Test
  void theConfigurationsAreFoundOnlyWithTheAxisOfHard() {
    assertThat(ConfrontoP11.codice(game("hard", "", 1861, "completata", "Allies", 10)))
        .isEqualTo("h0");
    assertThat(
            ConfrontoP11.codice(
                game(
                    "hard",
                    "usa_proporzione,sbarco_concentrato,niente_neutrali",
                    1861,
                    "completata",
                    "Allies",
                    10)))
        .isEqualTo("h3");
    assertThat(ConfrontoP11.codice(game("hard", "niente_neutrali", 1861, "completata", "Axis", 9)))
        .isEqualTo("h1");
    assertThat(
            ConfrontoP11.codice(
                game("hard", "usa_proporzione,sbarco_concentrato", 1861, "completata", "Axis", 9)))
        .isEqualTo("h2");
    // the Axis strategist (runs P7, P9) is not P11
    assertThat(ConfrontoP11.codice(game("stratega", "", 1861, "completata", "Axis", 9))).isNull();
  }

  @Test
  void theSectionHasTheTableTheNeutralsAndTheThreeDeclaredComparisons() {
    final List<Game> games = new ArrayList<>();
    for (int seed = 1861; seed <= 1870; seed++) {
      games.add(g(game("hard", "", seed, "completata", seed % 2 == 0 ? "Allies" : "Axis", 12)));
      games.add(
          g(
              game(
                  "hard",
                  "usa_proporzione,sbarco_concentrato,niente_neutrali",
                  seed,
                  "completata",
                  "Allies",
                  11)));
      games.add(g(game("hard", "niente_neutrali", seed, "completata", "Allies", 11)));
    }
    games.add(g(game("hard", "", 1990, "errore: x", "Allies", 3)));
    final StringBuilder md = new StringBuilder();

    assertThat(ConfrontoP11.applicabile(games)).isTrue();
    ConfrontoP11.write(md, games);

    final String text = md.toString();
    assertThat(text).contains("### P11");
    assertThat(text).contains("| h0 | 10 | 1 | 0 | 5/10");
    assertThat(text).contains("| h3 | 10 | 0 | 0 | 10/10");
    assertThat(text)
        .contains("| h3 vs h0 (consigliati contro Hard (principale)) | 10/10 vs 5/10 |");
    assertThat(text).contains("| h1 vs h0 (solo niente_neutrali contro Hard) | 10/10 vs 5/10 |");
    assertThat(text).contains("| h2 vs h0 (solo proporzione + sbarco contro Hard) | - | - |");
    // Rome fell in every game at round 7
    assertThat(text).contains("10 (R7)");
  }

  private static Game g(final JsonObject json) {
    return new Game("x", json);
  }

  private static JsonObject game(
      final String axis,
      final String allies,
      final int seed,
      final String state,
      final String winner,
      final int rounds) {
    final JsonObject j =
        JsonParser.parseString(
                """
                {"parametri": {"tipi": {"Axis": "%s", "Allies": "%s"}, "seme": %d,
                               "fino_a_vittoria": true},
                 "stato": "%s", "round_giocati": %d,
                 "vittoria": {"vincitore": "%s"},
                 "capitali": [{"territorio": "Southern Italy", "evento": "caduta", "round": 7}],
                 "p7": {"usa_trasporti_persi_r8": 2, "uk_trasporti_persi_r8": 1,
                        "f2_neutrali": {"aperta_da_alleati": false, "attacchi_alleati_neutrali": 0}},
                 "p9": {"usa_sbarcate": {"6": 3, "8": 10, "10": 20}}}
                """
                    .formatted(
                        axis, allies.isEmpty() ? "hard" : "stratega", seed, state, rounds, winner))
            .getAsJsonObject();
    if (!allies.isEmpty()) {
      final JsonObject on = new JsonObject();
      on.addProperty("Allies", allies);
      j.getAsJsonObject("parametri").add("stratega_acceso", on);
    }
    return j;
  }
}
