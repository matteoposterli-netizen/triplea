package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.triplea.ai.proplus.RiskLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutoplayTest {
  @Test
  void parsesOptions() {
    final AutoplayOptions o =
        AutoplayOptions.parse(
            new String[] {
              "--mappa", "m.xml", "--round", "6", "--piano", "Axis=A, Allies=1",
              "--rischio", "Axis=Audace", "--tipo", "Axis=Pro+,Allies=hard", "--seme", "7"
            });
    assertThat(o.round()).isEqualTo(6);
    assertThat(o.piano("Axis")).contains("A");
    assertThat(o.piano("Allies")).contains("1");
    assertThat(o.tipo("Axis")).isEqualTo(AutoplayOptions.PROPLUS);
    assertThat(o.tipo("Allies")).isEqualTo(AutoplayOptions.HARD);
    assertThat(o.tipo("Other")).isEqualTo(AutoplayOptions.PROPLUS);
    assertThat(o.seme()).isEqualTo(7);
    assertThat(o.minuti()).isEqualTo(55);
  }

  @Test
  void rejectsBadOptions() {
    assertThatThrownBy(() -> AutoplayOptions.parse(new String[] {"--round", "2"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AutoplayOptions.parse(new String[] {"--mappa", "m", "--tipo", "Axis=easy"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AutoplayOptions.pairs("Axis"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsRiskByLevelOrName() {
    assertThat(AutoplayRunner.riskLevel("3")).isEqualTo(RiskLevel.EQUILIBRATO);
    assertThat(AutoplayRunner.riskLevel("spericolato")).isEqualTo(RiskLevel.SPERICOLATO);
    assertThatThrownBy(() -> AutoplayRunner.riskLevel("9"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void diaryGivesDefenseAndPlanPus() {
    final Riepilogo.DiaryData d = new Riepilogo.DiaryData();
    final Map<String, Map<String, Integer>> costs =
        Map.of("Germans", Map.of("infantry", 3, "armour", 6));
    d.add(
        "{\"round\":1,\"nazione\":\"Germans\",\"evento\":\"acquisto\",\"passo\":\"defend_land\","
            + "\"unita\":{\"infantry\":2}}",
        costs);
    d.add(
        "{\"round\":1,\"nazione\":\"Germans\",\"evento\":\"acquisto\",\"passo\":\"capital_emergency\","
            + "\"unita\":{\"infantry\":1}}",
        costs);
    d.add(
        "{\"round\":1,\"nazione\":\"Germans\",\"evento\":\"acquisto\",\"passo\":\"plan\","
            + "\"unita\":{\"armour\":3}}",
        costs);
    d.add(
        "{\"round\":1,\"nazione\":\"Germans\",\"evento\":\"acquisto\",\"passo\":\"land\","
            + "\"unita\":{\"armour\":1}}",
        costs);
    d.add(
        "{\"round\":1,\"nazione\":\"Germans\",\"evento\":\"acquisti_totale\",\"pu_spesi\":33}",
        costs);
    d.add("not json", costs);
    d.add(
        "{\"round\":2,\"nazione\":\"Germans\",\"evento\":\"esito_battaglia\",\"territorio\":\"France\","
            + "\"win_stimata\":90.0,\"esito\":\"VITTORIA\",\"tuv_swing_reale\":20}",
        costs);
    assertThat(d.spent.get("1|Germans")).containsExactly(9, 18, 33, 0);
    assertThat(d.battles).containsKey("2|France|Germans");
  }

  @Test
  void roundsOfTheVcColumns() {
    assertThat(RiepilogoGenerale.vcRounds(12)).containsExactly(4, 8, 12);
    assertThat(RiepilogoGenerale.vcRounds(8)).containsExactly(3, 6, 8);
    assertThat(RiepilogoGenerale.vcRounds(2)).containsExactly(1, 2);
  }

  @Test
  void writesMarkdownOfAGameAndTheComparison() {
    final JsonObject game = JsonParser.parseString(SAMPLE).getAsJsonObject();
    final String md = Riepilogo.markdown(game);
    assertThat(md)
        .contains("Esito: **vittoria Asse**")
        .contains("| R1 | 3 (Berlin) | 1 (London) |")
        .contains("| R1 | Germans | 30 | 40 | 9 | 18 | 33 | - | 500 | 12 |")
        .contains("| R1 | Alleati | 1 | IN LINEA | A | A | 60% | si' |")
        .contains("- R1: Germans - Russians (nel turno di Germans)")
        .contains("- R1: London (capitale di British) caduta, ora di Germans")
        .contains("| R1 | France | Germans | French | 10 / 40 | CONQUERED | 90% | VITTORIA |");
    final String all =
        RiepilogoGenerale.markdown(
            "T",
            List.of(
                new RiepilogoGenerale.Game("1-A-1", game),
                new RiepilogoGenerale.Game("2-x", null)));
    assertThat(all)
        .contains("| 1-A-1 | Pro+ A | Pro+ 1 | vittoria Asse | 3 / 1 | 30 / 20 | completata |")
        .contains("| 2-x | - | - | **FALLITA** (nessun riepilogo) |")
        .contains("| 1-A-1 | Alleati | 60% OK |")
        .contains("| 1-A-1 | 9 / 18 / 33 | 0 / 0 / 0 |")
        .contains("| Pro+ A / Pro+ 1 | 1 | 3 / 1 | Asse R1 | 30 / 20 |")
        .contains("| Pro+ A / Pro+ 1 | Alleati | 60% 1/1 | 60% (OK 100%) |")
        .contains(
            "| 1-A-1 | nessuno | nessuno | R1 Normandy Bordeaux (British, attacco (LOST)) | 0 / 0 |"
                + " 0 / 0 | Germans 2, British 3 | nessuna |");
    assertThat(md)
        .contains(
            "- Primo sbarco alleato in Europa: R1 Normandy Bordeaux (British, attacco (LOST))")
        .contains("| R1 | Germans | 30 | 40 | 9 | 18 | 33 | - | 500 | 12 | 2 |");
  }

  @Test
  void firstLandingIsTheEarliestRealLanding() {
    final JsonArray landings =
        JsonParser.parseString(
                "[{\"round\":3,\"nazione\":\"British\",\"alleanza\":\"Allies\","
                    + "\"territorio\":\"Norway\",\"unita\":4,\"fase\":\"combattimento\"},"
                    + "{\"round\":2,\"nazione\":\"British\",\"alleanza\":\"Allies\","
                    + "\"territorio\":\"Libya\",\"unita\":2,\"fase\":\"combattimento\"},"
                    + "{\"round\":1,\"nazione\":\"Italians\",\"alleanza\":\"Axis\","
                    + "\"territorio\":\"Egypt\",\"unita\":3,\"fase\":\"combattimento\"},"
                    + "{\"round\":3,\"nazione\":\"Americans\",\"alleanza\":\"Allies\","
                    + "\"territorio\":\"Normandy Bordeaux\",\"unita\":6,"
                    + "\"fase\":\"combattimento\"}]")
            .getAsJsonArray();

    // in Europe: round 3, the bigger landing on a tie; Libya is not Europe
    final JsonObject europe = Riepilogo.firstLandingInEurope(landings).orElseThrow();
    assertThat(europe.get("round").getAsInt()).isEqualTo(3);
    assertThat(europe.get("territorio").getAsString()).isEqualTo("Normandy Bordeaux");
    assertThat(Riepilogo.landingText(europe))
        .isEqualTo("R3 Normandy Bordeaux (Americans, 6 unita' sbarcate (assalto anfibio))");
    // per alliance, anywhere
    final JsonObject first = Riepilogo.firstLandings(landings, List.of("Axis", "Allies"));
    assertThat(first.getAsJsonObject("Allies").get("territorio").getAsString()).isEqualTo("Libya");
    assertThat(first.getAsJsonObject("Axis").get("round").getAsInt()).isEqualTo(1);
    assertThat(Riepilogo.firstLandingInEurope(new JsonArray())).isEmpty();
  }

  @Test
  void blockedOperationsAreCountedWithTheirReasons() {
    final JsonObject game =
        JsonParser.parseString(
                "{\"operazioni\":[{\"round\":3,\"nazione\":\"Americans\","
                    + "\"operazione\":\"Overlord USA\",\"stato\":\"bloccata\","
                    + "\"motivo\":\"scorta insufficiente per 110 Sea Zone: win% nemica 90%\"},"
                    + "{\"round\":4,\"nazione\":\"Americans\",\"operazione\":\"Overlord USA\","
                    + "\"stato\":\"bloccata\",\"motivo\":\"scorta insufficiente per 110 Sea"
                    + " Zone: win% nemica 75%\"},"
                    + "{\"round\":5,\"nazione\":\"Americans\",\"operazione\":\"Overlord USA\","
                    + "\"stato\":\"in rotta\",\"motivo\":\"\"}]}")
            .getAsJsonObject();

    assertThat(RiepilogoGenerale.blockedText(List.of(game), 3))
        .isEqualTo(
            "Americans Overlord USA 2 (scorta insufficiente per N Sea Zone: win% nemica N 2)");
    assertThat(RiepilogoGenerale.blockedText(List.of(new JsonObject()), 3)).isEqualTo("nessuna");
  }

  private static final String SAMPLE =
      """
      {"nome":"A / 1","stato":"completata","durata_s":75,"round_giocati":1,
       "parametri":{"mappa":"m.xml","gioco":"G","round_max":1,"seme":1,"minuti_max":55,
         "tipi":{"Axis":"proplus","Allies":"proplus"},"piani":{"Axis":"A","Allies":"1"},
         "rischio_base":{"Axis":"Equilibrato","Allies":"Equilibrato"}},
       "alleanze":{"Axis":["Germans"],"Allies":["British"]},
       "round":[{"round":1,"completo":true,
         "nazioni":{"Germans":{"pu":30,"reddito":40,"tuv":500,"territori":12,
                               "pu_difesa":9,"pu_piano":18,"pu_spesi":33,"trasporti":2},
                    "British":{"pu":20,"reddito":25,"tuv":300,"territori":20,
                               "pu_difesa":0,"pu_piano":0,"pu_spesi":0,"trasporti":3}},
         "vc":{"Axis":{"numero":3,"citta":["Berlin"]},"Allies":{"numero":1,"citta":["London"]}},
         "stato_maggiore":{"Allies":{"piano":"1","stato":"IN LINEA",
           "lettura":{"piu_probabile":"A","piano_reale":"A","prob_piano_reale":0.6,
                      "corretta":true,"probabilita":{"A":0.6}}}}}],
       "dichiarazioni_guerra":[{"round":1,"nazione_di_turno":"Germans",
                                "tra":"Germans - Russians","alleanze":"Axis - Allies"}],
       "capitali":[{"round":1,"evento":"caduta","territorio":"London","capitale_di":"British",
                    "proprietario":"Germans"}],
       "battaglie_top":[{"round":1,"territorio":"France","attaccante":"Germans",
         "difensore":"French","tuv_perso_attaccante":10,"tuv_perso_difensore":40,
         "tuv_totale":50,"risultato":"CONQUERED","win_stimata":90.0,"esito_diario":"VITTORIA"}],
       "esito":{"vincitore":"Axis","testo":"vittoria Asse","vc_finali":{"Axis":3,"Allies":1}},
       "primo_sbarco_europa":{"round":1,"territorio":"Normandy Bordeaux","nazione":"British",
                              "come":"attacco (LOST)"}}
      """;
}
