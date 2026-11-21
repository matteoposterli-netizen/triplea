package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/** Step P12: the configuration codes, the block, the regression and the section "P12". */
class ConfrontoP12Test {
  private static final String FINALI =
      "usa_proporzione,sbarco_concentrato,niente_neutrali,norvegia_presto,norvegia_esposta,"
          + "ponte_concentrato";
  private static final String NUCLEO = "usa_proporzione,sbarco_concentrato,niente_neutrali";

  @Test
  void theCodesOfTheConfigurations() {
    assertThat(ConfrontoP12.codice(game("stratega", "impegno,assedio", "hard", "", "Axis")))
        .isEqualTo("H0000");
    assertThat(
            ConfrontoP12.codice(
                game("stratega", "impegno,assedio,italia_est,capitale_minima", "hard", "", "Axis")))
        .isEqualTo("H1010");
    assertThat(
            ConfrontoP12.codice(
                game(
                    "stratega",
                    "impegno,assedio,italia_africa,italia_gibilterra",
                    "stratega",
                    FINALI,
                    "Axis")))
        .isEqualTo("F0101");
    assertThat(ConfrontoP12.codice(game("stratega", "impegno,assedio", "stratega", NUCLEO, "Axis")))
        .isEqualTo("a3");
    assertThat(ConfrontoP12.codice(game("hard", "", "stratega", NUCLEO, "Allies"))).isEqualTo("h3");
    assertThat(ConfrontoP12.codice(game("hard", "", "stratega", FINALI, "Allies"))).isEqualTo("h5");
    // other runs are not P12
    assertThat(ConfrontoP12.codice(game("hard", "", "hard", "", "Allies"))).isNull();
    assertThat(
            ConfrontoP12.codice(
                game("stratega", "impegno,assedio,italia_convoglio", "hard", "", "Axis")))
        .isNull();
  }

  @Test
  void theLogisticRegressionFindsAKnownEffect() {
    final Random rnd = new Random(7);
    final List<double[]> x = new ArrayList<>();
    final List<Integer> y = new ArrayList<>();
    for (int i = 0; i < 4000; i++) {
      final double a = rnd.nextBoolean() ? 1 : 0;
      final double eta = -0.5 + 1.2 * a;
      x.add(new double[] {1, a});
      y.add(rnd.nextDouble() < 1 / (1 + Math.exp(-eta)) ? 1 : 0);
    }
    final double[][] fit = ConfrontoP12.logistica(x, y);
    assertThat(fit[0][1]).isBetween(1.0, 1.4);
    assertThat(ConfrontoP12.pNormale(fit[0][1] / fit[1][1])).isLessThan(0.001);
    assertThat(ConfrontoP12.pNormale(0)).isBetween(0.99, 1.01);
    assertThat(ConfrontoP12.pNormale(1.96)).isBetween(0.049, 0.051);
  }

  @Test
  void theSectionWritesTheTablesForBothOpponents() {
    final List<Game> games = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      final String bits = String.format("%4s", Integer.toBinaryString(i)).replace(' ', '0');
      final StringBuilder on = new StringBuilder("impegno,assedio");
      final String[] leve = {"italia_est", "italia_africa", "capitale_minima", "italia_gibilterra"};
      for (int k = 0; k < 4; k++) {
        if (bits.charAt(k) == '1') {
          on.append(',').append(leve[k]);
        }
      }
      for (int seed = 0; seed < 4; seed++) {
        final String winner = (seed + i) % 2 == 0 ? "Axis" : "Allies";
        games.add(g(game("stratega", on.toString(), "hard", "", winner)));
        games.add(g(game("stratega", on.toString(), "stratega", FINALI, winner)));
      }
    }
    games.add(g(game("hard", "", "stratega", FINALI, "Allies")));
    final StringBuilder md = new StringBuilder();
    assertThat(ConfrontoP12.applicabile(games)).isTrue();
    ConfrontoP12.write(md, games);
    final String text = md.toString();
    assertThat(text)
        .contains("### P12")
        .contains("Le 16 combinazioni")
        .contains("Effetto di ogni leva")
        .contains("regressione logistica")
        .contains("| H1111 |")
        .contains("| F0000 |")
        .contains("| h5 |");
  }

  private static Game g(final JsonObject json) {
    json.add("p12", ConfrontoP12.blocco(json));
    return new Game("x", json);
  }

  private static JsonObject game(
      final String axisType,
      final String axisOn,
      final String alliesType,
      final String alliesOn,
      final String winner) {
    final JsonObject j = new JsonObject();
    final JsonObject p = new JsonObject();
    final JsonObject t = new JsonObject();
    t.addProperty("Axis", axisType);
    t.addProperty("Allies", alliesType);
    p.add("tipi", t);
    final JsonObject on = new JsonObject();
    if (!axisOn.isEmpty()) {
      on.addProperty("Axis", axisOn);
    }
    if (!alliesOn.isEmpty()) {
      on.addProperty("Allies", alliesOn);
    }
    p.add("stratega_acceso", on);
    p.addProperty("fino_a_vittoria", true);
    p.addProperty("seme", 2101);
    j.add("parametri", p);
    j.addProperty("stato", "completata");
    final JsonObject v = new JsonObject();
    v.addProperty("vincitore", winner);
    j.add("vittoria", v);
    final JsonArray rounds = new JsonArray();
    for (int r = 1; r <= 12; r++) {
      final JsonObject x = new JsonObject();
      x.addProperty("round", r);
      final JsonObject r4 = new JsonObject();
      final JsonObject own = new JsonObject();
      own.addProperty("Mosca", r >= 10 && "Axis".equals(winner) ? "Germans" : "Russians");
      own.addProperty("Roma", "Italians");
      r4.add("proprietari", own);
      x.add("r4", r4);
      final JsonObject p12 = new JsonObject();
      final JsonObject reg = new JsonObject();
      reg.addProperty("sud_russia", r);
      reg.addProperty("italia", 10);
      p12.add("ita_terra", reg);
      p12.addProperty("egitto_asse", r < 6);
      p12.addProperty("gibilterra_asse", r >= 3 && r <= 6);
      x.add("p12", p12);
      rounds.add(x);
    }
    j.add("round", rounds);
    return j;
  }
}
