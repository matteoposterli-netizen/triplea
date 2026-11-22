package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplea.proplus.autoplay.RiepilogoGenerale.Game;

/** Step P13: the configuration codes, the block (falls, factories, round 1) and the section. */
class ConfrontoP13Test {
  private static final String FINALI =
      "usa_proporzione,sbarco_concentrato,niente_neutrali,norvegia_presto,norvegia_esposta,"
          + "ponte_concentrato";

  @Test
  void theCodesOfTheConfigurations() {
    assertThat(ConfrontoP13.codice(game("stratega", "impegno,assedio", FINALI, "Axis")))
        .isEqualTo("000");
    assertThat(
            ConfrontoP13.codice(
                game(
                    "stratega",
                    "impegno,assedio,fabbriche_tenute,bombardamento_mosca",
                    FINALI + ",taranto",
                    "Axis")))
        .isEqualTo("111");
    assertThat(
            ConfrontoP13.codice(
                game("stratega", "impegno,assedio,bombardamento_mosca", FINALI, "Axis")))
        .isEqualTo("001");
    assertThat(ConfrontoP13.codice(game("hard", "", FINALI, "Allies"))).isEqualTo("r0");
    assertThat(ConfrontoP13.codice(game("hard", "", FINALI + ",taranto", "Allies")))
        .isEqualTo("r1");
    // other runs are not P13
    assertThat(ConfrontoP13.codice(game("stratega", "impegno,assedio,italia_est", FINALI, "Axis")))
        .isNull();
    assertThat(ConfrontoP13.codice(game("hard", "", "usa_proporzione", "Allies"))).isNull();
  }

  @Test
  void theBlockReadsTheFallsTheFactoriesAndRoundOne() {
    final JsonObject j = game("stratega", "impegno,assedio,fabbriche_tenute", FINALI, "Axis");
    final JsonObject data = new JsonObject();
    final JsonArray battles = new JsonArray();
    battles.add(battle(1, "97 Sea Zone", "British", "Italians", 30, 59, "WON_WITHOUT_CONQUERING"));
    battles.add(battle(1, "France", "Germans", "French", 18, 53, "CONQUERED"));
    battles.add(battle(1, "110 Sea Zone", "British", "Germans", 8, 20, "CONQUERED"));
    battles.add(battle(2, "Poland", "Germans", "Russians", 5, 5, "CONQUERED"));
    data.add("battaglie", battles);
    final JsonObject b = ConfrontoP13.blocco(j, data);
    assertThat(b.get("configurazione").getAsString()).isEqualTo("010");
    assertThat(ConfrontoP13.caduta(b, "Ucraina")).isEqualTo(4);
    assertThat(ConfrontoP13.ripresa(b, "Ucraina")).isTrue();
    assertThat(ConfrontoP13.caduta(b, "Mosca")).isEqualTo(10);
    assertThat(ConfrontoP13.caduta(b, "Londra")).isZero();
    assertThat(b.getAsJsonObject("taranto").get("eseguito").getAsBoolean()).isTrue();
    assertThat(ConfrontoP13.vinceUk(b.getAsJsonObject("taranto"))).isTrue();
    final JsonArray germ = b.getAsJsonArray("germania_r1");
    assertThat(germ).hasSize(2);
    assertThat(germ.get(1).getAsJsonObject().get("tuv_tedesco").getAsInt()).isEqualTo(20);
    assertThat(ConfrontoP13.alRound(b, 5, "prodotte_mosca")).isEqualTo(7);
  }

  @Test
  void theSectionWritesTheTables() {
    final List<Game> games = new ArrayList<>();
    final String[] leve = {"fabbriche_tenute", "bombardamento_mosca"};
    for (final String c : ConfrontoP13.FATTORIALE) {
      final StringBuilder axis = new StringBuilder("impegno,assedio");
      for (int k = 0; k < 2; k++) {
        if (c.charAt(k + 1) == '1') {
          axis.append(',').append(leve[k]);
        }
      }
      final String allies = FINALI + (c.charAt(0) == '1' ? ",taranto" : "");
      for (int seed = 0; seed < 4; seed++) {
        games.add(g(game("stratega", axis.toString(), allies, seed % 2 == 0 ? "Axis" : "Allies")));
      }
    }
    games.add(g(game("hard", "", FINALI, "Allies")));
    games.add(g(game("hard", "", FINALI + ",taranto", "Axis")));
    assertThat(ConfrontoP13.applicabile(games)).isTrue();
    final StringBuilder md = new StringBuilder();
    ConfrontoP13.write(md, games);
    assertThat(md.toString())
        .contains("### P13")
        .contains("Effetto di ogni leva")
        .contains("regressione logistica")
        .contains("Taranto contro l'Asse di Hard")
        .contains("Perdite tedesche al primo round")
        .contains("| 111 |")
        .contains("| r1 |");
  }

  private static Game g(final JsonObject json) {
    json.add("p13", ConfrontoP13.blocco(json, new JsonObject()));
    return new Game("x", json);
  }

  private static JsonObject battle(
      final int round,
      final String where,
      final String att,
      final String def,
      final int attLost,
      final int defLost,
      final String result) {
    final JsonObject b = new JsonObject();
    b.addProperty("round", round);
    b.addProperty("territorio", where);
    b.addProperty("attaccante", att);
    b.addProperty("difensore", def);
    b.addProperty("tuv_perso_attaccante", attLost);
    b.addProperty("tuv_perso_difensore", defLost);
    b.addProperty("risultato", result);
    return b;
  }

  private static JsonObject game(
      final String axisType, final String axisOn, final String alliesOn, final String winner) {
    final JsonObject j = new JsonObject();
    final JsonObject p = new JsonObject();
    final JsonObject t = new JsonObject();
    t.addProperty("Axis", axisType);
    t.addProperty("Allies", "stratega");
    p.add("tipi", t);
    final JsonObject on = new JsonObject();
    if (!axisOn.isEmpty()) {
      on.addProperty("Axis", axisOn);
    }
    on.addProperty("Allies", alliesOn);
    p.add("stratega_acceso", on);
    p.addProperty("fino_a_vittoria", true);
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
      own.addProperty("Mosca", r >= 10 ? "Germans" : "Russians");
      own.addProperty("Ucraina", r >= 4 && r < 7 ? "Germans" : "Russians");
      own.addProperty("Roma", "Italians");
      own.addProperty("Londra", "British");
      r4.add("proprietari", own);
      r4.addProperty("prodotte_mosca", r + 2);
      x.add("r4", r4);
      final JsonObject p13 = new JsonObject();
      p13.addProperty("tedeschi_est", 10 * r);
      p13.addProperty("riparazioni_russe", r);
      p13.addProperty("danno_tedesco", r);
      x.add("p13", p13);
      rounds.add(x);
    }
    j.add("round", rounds);
    return j;
  }
}
