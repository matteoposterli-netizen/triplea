package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

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
        .contains("| R1 | Alleati | 1 | IN LINEA | - | A | A | 60% | si' |")
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
  void comparisonAgainstHardPerConfigurationAndForTheSideThatChanges() {
    // 2 seeds of Hard vs Hard (Axis VC 3 and 3) and 2 of Axis Pro+ A vs Allies Hard (Axis VC 5, 6)
    final List<RiepilogoGenerale.Game> games = new java.util.ArrayList<>();
    final int[][] seeds = {{3, 30}, {3, 40}, {5, 10}, {6, 20}};
    for (int i = 0; i < seeds.length; i++) {
      final JsonObject g = JsonParser.parseString(SAMPLE).getAsJsonObject();
      final boolean hard = i < 2;
      final JsonObject types = g.getAsJsonObject("parametri").getAsJsonObject("tipi");
      types.addProperty("Axis", hard ? "hard" : "proplus");
      types.addProperty("Allies", "hard");
      g.getAsJsonObject("parametri").getAsJsonObject("piani").addProperty("Allies", "-");
      g.getAsJsonArray("round")
          .get(0)
          .getAsJsonObject()
          .getAsJsonObject("vc")
          .getAsJsonObject("Axis")
          .addProperty("numero", seeds[i][0]);
      final JsonObject lost = new JsonObject();
      lost.addProperty("Axis", seeds[i][1]);
      lost.addProperty("Allies", 60);
      g.add("tuv_perso", lost);
      if (hard) {
        g.remove("esito");
        g.add("esito", JsonParser.parseString("{\"testo\":\"nessuna entro 1 round\"}"));
      }
      games.add(new RiepilogoGenerale.Game((hard ? "1-Hard-seme" : "2-A-seme") + (i % 2 + 1), g));
    }
    final String md = RiepilogoGenerale.markdown("T", games);
    assertThat(md)
        .contains("## Confronto contro Hard (riferimento: Hard / Hard)")
        .contains("| Hard / Hard | 2 | 3 / 1 | nessuna | 30 / 20 | 40 / 25 | 35 / 60 | 0 / 1 |")
        .contains("| Pro+ A / Hard | 2 | 5.5 (5-6) / 1 | Asse 2 (R1, R1) |")
        .contains(
            "| Pro+ A / Hard | Asse | Pro+ regole | 5.5 (5-6), ds 0.7 | Δ +2.5 | 2 (R1, R1)/2 |"
                + " 30 Δ +0 | 40 Δ +0 | 4.5 (3-6) Δ +2.75 | 0 Δ +0 | 0 Δ +0 | piu' forte |")
        .contains(
            "- Asse, Pro+ regole contro Hard (2 partite, riferimento 2): VC R1 5.5 (5-6) contro 3"
                + " (Δ +2.5), rapporto TUV 4.5 contro 1.75, vittorie del lato 2 (R1, R1)/2 contro"
                + " 0/2 ⇒ **piu' forte**");
    // without a Hard vs Hard reference the section is not written
    assertThat(RiepilogoGenerale.markdown("T", games.subList(2, 4)))
        .doesNotContain("Confronto contro Hard");
    assertThat(ConfrontoHard.kind(games.get(2).json(), "Axis")).isEqualTo("Pro+ regole");
  }

  @Test
  void ablationOptionAcceptsKnownComponentsOnly() {
    final AutoplayOptions o =
        AutoplayOptions.parse(
            new String[] {
              "--mappa", "m.xml", "--ablazione", "Axis=rallentamento+rischio_fronte,Allies=nudo"
            });
    assertThat(o.ablazione("Axis")).isEqualTo("rallentamento,rischio_fronte");
    assertThat(o.ablazione("Allies")).isEqualTo("nudo");
    assertThat(o.ablazione("Other")).isEmpty();
    assertThatThrownBy(
            () -> AutoplayOptions.parse(new String[] {"--mappa", "m", "--ablazione", "Axis=boh"}))
        .hasMessageContaining("Ablazione non valida per Axis")
        .hasMessageContaining("boh");
  }

  @Test
  void varianceSectionSplitsSeedsReplicasAndMachines() {
    // Axis VC: Hard vs Hard seeds 1-3 = 6, 6, 6 and 101-103 = 6, 7, 5; nudo seeds 1-3 = 4, 5, 3,
    // 101-103 = 7, 6, 7, replica of 1-3 = 6, 5, 7
    final List<RiepilogoGenerale.Game> games = new java.util.ArrayList<>();
    final String[] ids = {
      "a-hh-r1-seme1", "a-hh-r1-seme2", "a-hh-r1-seme3",
      "a-hh-r1-seme101", "a-hh-r1-seme102", "a-hh-r1-seme103",
      "b-nudo-r1-seme1", "b-nudo-r1-seme2", "b-nudo-r1-seme3",
      "b-nudo-r1-seme101", "b-nudo-r1-seme102", "b-nudo-r1-seme103",
      "c-nudo-r2-seme1", "c-nudo-r2-seme2", "c-nudo-r2-seme3"
    };
    final int[] vcs = {6, 6, 6, 6, 7, 5, 4, 5, 3, 7, 6, 7, 6, 5, 7};
    for (int i = 0; i < ids.length; i++) {
      final JsonObject g = JsonParser.parseString(SAMPLE).getAsJsonObject();
      final boolean hard = ids[i].startsWith("a-");
      final JsonObject p = g.getAsJsonObject("parametri");
      p.getAsJsonObject("tipi").addProperty("Axis", hard ? "hard" : "proplus");
      p.getAsJsonObject("tipi").addProperty("Allies", "hard");
      p.getAsJsonObject("piani").addProperty("Allies", "-");
      if (!hard) {
        final JsonObject abl = new JsonObject();
        abl.addProperty("Axis", "nudo");
        p.add("ablazione", abl);
      }
      g.getAsJsonArray("round")
          .get(0)
          .getAsJsonObject()
          .getAsJsonObject("vc")
          .getAsJsonObject("Axis")
          .addProperty("numero", vcs[i]);
      g.addProperty("durata_s", 100 + 10 * i);
      g.add(
          "tempi",
          JsonParser.parseString(
              "{\"per_nazione\":{\"Germans\":{\"media_s\":20},\"Italians\":{\"media_s\":5}},"
                  + "\"ambiente\":{\"cpu\":\"CPU X\",\"processori\":4}}"));
      g.remove("esito");
      g.add("esito", JsonParser.parseString("{\"testo\":\"nessuna entro 1 round\"}"));
      games.add(new RiepilogoGenerale.Game(ids[i], g));
    }
    final String md = RiepilogoGenerale.markdown("T", games);
    assertThat(md)
        .contains("## Varianza tra run: semi, repliche, tempo e macchina (lato Asse)")
        .contains("| Hard / Hard | 1-99 | 1 | 3 | 6.00 ± 0.00 [0.00] |")
        .contains("| Pro+ A [nudo] / Hard | 1-99 | 2 | 3 | 6.00 ± 1.00 [0.58] | [3.52, 8.48] |")
        .contains("| Pro+ A [nudo] / Hard semi 100+ − Pro+ A [nudo] / Hard semi 1-99 | +2.67 |")
        .contains("| Pro+ A [nudo] / Hard semi 1-99 replica 2 − replica 1 | +2.00 |")
        .contains("| Pro+ A [nudo] / Hard − Hard / Hard (semi 1-99) | -2.00 |")
        .contains("Stesso seme giocato due volte (3 coppie): differenza media assoluta 2.00 VC")
        .contains("| CPU X, 4 processori | 6, 6.00 ± 0.63 [0.26] | 9,");
    // turn of the side = Germans + Italians
    assertThat(md).contains("| 25.00 ± 0.00 [0.00] |");
  }

  @Test
  void confirmationStatisticsGiveWelchIntervalsAndTheSeedsNeeded() {
    // Axis VC at the end: Hard vs Hard 6, 8, 4 (mean 6, sd 2); nudo 3, 4, 5 (mean 4, sd 1)
    final List<RiepilogoGenerale.Game> games = new java.util.ArrayList<>();
    final int[] vcs = {6, 8, 4, 3, 4, 5};
    for (int i = 0; i < vcs.length; i++) {
      final JsonObject g = JsonParser.parseString(SAMPLE).getAsJsonObject();
      final boolean hard = i < 3;
      final JsonObject p = g.getAsJsonObject("parametri");
      p.getAsJsonObject("tipi").addProperty("Axis", hard ? "hard" : "proplus");
      p.getAsJsonObject("tipi").addProperty("Allies", "hard");
      p.getAsJsonObject("piani").addProperty("Allies", "-");
      if (!hard) {
        final JsonObject abl = new JsonObject();
        abl.addProperty("Axis", "nudo");
        p.add("ablazione", abl);
      }
      g.getAsJsonArray("round")
          .get(0)
          .getAsJsonObject()
          .getAsJsonObject("vc")
          .getAsJsonObject("Axis")
          .addProperty("numero", vcs[i]);
      g.remove("esito");
      g.add("esito", JsonParser.parseString("{\"testo\":\"nessuna entro 1 round\"}"));
      games.add(new RiepilogoGenerale.Game(i / 3 + "-x-seme" + (101 + i % 3), g));
    }
    final String md = RiepilogoGenerale.markdown("T", games);
    // Welch: delta -2, se sqrt(1/3 + 4/3) = 1.29, 2.94 degrees of freedom (t 3.182)
    assertThat(md)
        .contains("## Statistica del confronto contro Hard (riferimento: Hard / Hard)")
        .contains("| Hard / Hard | 3 | 6.00 ± 2.00 [1.15] |")
        .contains("| Pro+ A [nudo] / Hard | 3 | 4.00 ± 1.00 [0.58] |")
        .contains(
            "| Pro+ A [nudo] / Hard − Hard / Hard | Asse | VC R1 | -2.00 | [-6.11, 2.11] (t 3.18,"
                + " gl 2.9) | include lo 0 | 10 |");
    // the t quantile against the table
    assertThat(ConfrontoConferma.tQuantile975(5)).isCloseTo(2.571, within(0.005));
    assertThat(ConfrontoConferma.tQuantile975(10)).isCloseTo(2.228, within(0.002));
    assertThat(ConfrontoConferma.tQuantile975(30)).isCloseTo(2.042, within(0.002));
  }

  @Test
  void ablationSummaryRanksTheComponentsAgainstProPlusAndHard() {
    // Axis VC at the end: Hard vs Hard 3, 3; Pro+ A 3, 4 (RALLENTAMENTO 4 rounds); without
    // RALLENTAMENTO 6, 6; nudo 3, 3
    final List<RiepilogoGenerale.Game> games = new java.util.ArrayList<>();
    final String[] offs = {null, null, "", "", "rallentamento", "rallentamento", "nudo", "nudo"};
    final int[] vcs = {3, 3, 3, 4, 6, 6, 3, 3};
    for (int i = 0; i < offs.length; i++) {
      final JsonObject g = JsonParser.parseString(SAMPLE).getAsJsonObject();
      final boolean hard = offs[i] == null;
      final JsonObject p = g.getAsJsonObject("parametri");
      p.getAsJsonObject("tipi").addProperty("Axis", hard ? "hard" : "proplus");
      p.getAsJsonObject("tipi").addProperty("Allies", "hard");
      p.getAsJsonObject("piani").addProperty("Allies", "-");
      if (!hard && !offs[i].isEmpty()) {
        final JsonObject abl = new JsonObject();
        abl.addProperty("Axis", offs[i]);
        p.add("ablazione", abl);
      }
      if ("".equals(offs[i])) {
        g.add("stratega", JsonParser.parseString("{\"Axis\":{\"posture\":{\"RALLENTAMENTO\":4}}}"));
      }
      g.getAsJsonArray("round")
          .get(0)
          .getAsJsonObject()
          .getAsJsonObject("vc")
          .getAsJsonObject("Axis")
          .addProperty("numero", vcs[i]);
      final JsonObject lost = new JsonObject();
      lost.addProperty("Axis", 50);
      lost.addProperty("Allies", 60);
      g.add("tuv_perso", lost);
      g.remove("esito");
      g.add("esito", JsonParser.parseString("{\"testo\":\"nessuna entro 1 round\"}"));
      games.add(new RiepilogoGenerale.Game(i / 2 + "-x-seme" + (i % 2 + 1), g));
    }
    final String md = RiepilogoGenerale.markdown("T", games);
    assertThat(md)
        .contains("## Ablazione dei componenti Pro+ (riferimento: Hard / Hard)")
        .contains("Rumore (Hard / Hard, 2 partite): VC R1 Asse 3 ds 0.0")
        .contains("| Pro+ A / Hard | Asse | 2 | 3.5 (3-4), ds 0.7 | Δ +0.5 (rumore) | - |")
        .contains(
            "| Pro+ A [senza rallentamento] / Hard | Asse | 2 | 6, ds 0.0 | Δ +3 (forte) |"
                + " Δ +2.5 (forte) | 50 / 60 |")
        .containsPattern("\\| Pro\\+ A / Hard \\| Asse \\|.*\\| 4 \\|\n")
        .contains("| Pro+ A [nudo] / Hard | Asse | 2 | 3, ds 0.0 | Δ +0 (rumore) | Δ -0.5 (rumore)")
        .doesNotContain("⚠")
        .contains("- Asse, rallentamento: -2.50 VC ⇒ **costa**")
        .contains("- Asse, tutti i componenti (nudo): +0.50 VC ⇒ **rumore**");
    // the ablation configurations are not mixed with "Pro+ attuale"
    assertThat(RiepilogoGenerale.configuration(games.get(4).json(), List.of("Axis", "Allies")))
        .isEqualTo("Pro+ A [senza rallentamento] / Hard");
    // without ablation games the section is not written
    assertThat(RiepilogoGenerale.markdown("T", games.subList(0, 4))).doesNotContain("Ablazione");
  }

  @Test
  void plansAndPosturesPerGameAndConfiguration() {
    final JsonObject game = JsonParser.parseString(SAMPLE).getAsJsonObject();
    final JsonArray rounds = new JsonArray();
    final String[][] staff = {
      {"1", "OFFENSIVA", ""}, {"1", "CONTRASTO", ""}, {"2", "RALLENTAMENTO", "cambio"}
    };
    for (int i = 0; i < staff.length; i++) {
      final JsonObject r = game.getAsJsonArray("round").get(0).getAsJsonObject().deepCopy();
      r.addProperty("round", i + 1);
      final JsonObject a = r.getAsJsonObject("stato_maggiore").getAsJsonObject("Allies");
      a.addProperty("piano", staff[i][0]);
      a.addProperty("postura", staff[i][1]);
      final JsonArray counter = new JsonArray();
      if (i == 1) {
        counter.add("Romania");
      }
      a.add("contro_obiettivi", counter);
      if (!staff[i][2].isEmpty()) {
        a.add(
            "cambio",
            JsonParser.parseString(
                "{\"round\":3,\"da\":\"1\",\"a\":\"2\",\"motivo\":\"Overlord in stallo\"}"));
      }
      rounds.add(r);
    }
    game.add("round", rounds);

    assertThat(Riepilogo.markdown(game))
        .contains("| R2 | Alleati | 1 | IN LINEA | CONTRASTO (Romania) |")
        .contains("| R3 | Alleati | 2 | IN LINEA | RALLENTAMENTO - **cambio piano 1 -> 2** |")
        .contains(
            "- Alleati: piano iniziale 1, cambi: R3 1 -> 2 (Overlord in stallo); posture: R1"
                + " OFFENSIVA, R2 CONTRASTO, R3 RALLENTAMENTO");
    assertThat(RiepilogoGenerale.markdown("T", List.of(new RiepilogoGenerale.Game("1-A-1", game))))
        .contains(
            "| 1-A-1 | Alleati | 1 -> 2 (R3: Overlord in stallo) | R1 OFFENSIVA, R2 CONTRASTO, R3"
                + " RALLENTAMENTO |")
        .contains("| Pro+ A / Pro+ 1 | Alleati | 1 | 1 | 1 | 2 x1 |");
    assertThat(Riepilogo.runs(List.of("A", "A", "B", "B", "B", "A")))
        .isEqualTo("R1-2 A, R3-5 B, R6 A");
  }

  @Test
  void lostTransportsPerNationAndRoundAgainstTheLandingsTuv() {
    final JsonObject game = JsonParser.parseString(SAMPLE).getAsJsonObject();
    final JsonObject british =
        game.getAsJsonArray("round")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("nazioni")
            .getAsJsonObject("British");
    british.addProperty("trasporti_persi", 2);
    british.addProperty("tuv_trasporti_persi", 14);
    british.addProperty("tuv_sbarchi", 9);
    final JsonObject other = game.deepCopy();

    assertThat(
            RiepilogoGenerale.markdown(
                "T",
                List.of(
                    new RiepilogoGenerale.Game("1-A-1-seme1", game),
                    new RiepilogoGenerale.Game("1-A-1-seme2", other))))
        .contains("## Trasporti persi e sbarchi (step 3d-1-bis)")
        .contains("| Configurazione | Nazione | Partite | R1 | Totale | TUV trasporti persi |")
        // two games: the sums
        .contains("| Pro+ A / Pro+ 1 | British | 2 | 4 | 4 | 28 | 18 |")
        // nothing lost, no landing: no row
        .doesNotContain("| Pro+ A / Pro+ 1 | Germans | 2 |");
  }

  @Test
  void delayToolsAndTheFallOfTheSovietKeyTerritories() {
    final JsonObject game = JsonParser.parseString(SAMPLE).getAsJsonObject();
    final JsonObject round1 =
        game.getAsJsonArray("round").get(0).getAsJsonObject().getAsJsonObject("nazioni");
    final JsonObject russians = new JsonObject();
    russians.addProperty("reddito", 37);
    final JsonObject delay = new JsonObject();
    delay.addProperty("blocchi_messi", 3);
    delay.addProperty("blocchi_persi", 2);
    delay.addProperty("navi_messi", 1);
    delay.addProperty("zone_caccia", 2);
    delay.addProperty("affondati_caccia", 1);
    russians.add("ritardo", delay);
    round1.add("Russians", russians);
    game.add(
        "territori_chiave",
        JsonParser.parseString(
            "[{\"round\":1,\"evento\":\"caduta\",\"territorio\":\"Novgorod\","
                + "\"proprietario\":\"Germans\"}]"));

    assertThat(RiepilogoGenerale.fallRound(game, "Novgorod")).isEqualTo(1);
    assertThat(RiepilogoGenerale.fallRound(game, "Russia")).isZero();
    assertThat(RiepilogoGenerale.markdown("T", List.of(new RiepilogoGenerale.Game("1-A-1", game))))
        .contains("## Strumenti di ritardo e fronte russo (step 3d-2)")
        .contains("| 1-A-1 | Pro+ A / Pro+ 1 | R1 | - | - | London R1 | 3/2 | 1/0 | 0 | 2/1 |")
        .contains("| Pro+ A / Pro+ 1 | 1 | 1 / R1.0 | 0 | 0 |")
        .contains("| 1-A-1 | 37 |");
  }

  @Test
  void deciderPerAllianceIsRulesOrAFile() {
    final AutoplayOptions o =
        AutoplayOptions.parse(
            new String[] {"--mappa", "m.xml", "--decisore", "Allies=file:/tmp/dir,Axis=regole"});
    assertThat(o.decisore("Allies")).isEqualTo("file:/tmp/dir");
    assertThat(o.decisore("Axis")).isEqualTo("regole");
    assertThat(o.decisore("Other")).isEqualTo("regole");
    assertThat(
            AutoplayOptions.parse(new String[] {"--mappa", "m", "--decisore", "Axis=llm"})
                .decisore("Axis"))
        .isEqualTo(AutoplayOptions.LLM);
    assertThatThrownBy(
            () -> AutoplayOptions.parse(new String[] {"--mappa", "m", "--decisore", "Axis=gpt"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            AutoplayOptions.parse(
                    new String[] {"--mappa", "m", "--decisore", "Allies=llm:claude-sonnet-5-5"})
                .decisore("Allies"))
        .isEqualTo("llm:claude-sonnet-5-5");
    assertThatThrownBy(
            () -> AutoplayOptions.parse(new String[] {"--mappa", "m", "--decisore", "Axis=llm:"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AutoplayOptions.parse(new String[] {"--mappa", "m", "--decisore", "Axis=file:"}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theContractSizeIsMeasuredFromTheStateFilesOfTheDiary(
      @org.junit.jupiter.api.io.TempDir final java.nio.file.Path diary) throws Exception {
    java.nio.file.Files.writeString(diary.resolve("stato-R1-Axis.json"), "{\n  \"a\": 1\n}");
    java.nio.file.Files.writeString(diary.resolve("stato-R1-Allies.json"), "{\"abc\": [1, 2]}");
    java.nio.file.Files.writeString(diary.resolve("direttive-R1-Axis.json"), "{\"x\": 1}");
    java.nio.file.Files.writeString(
        diary.resolve("decisioni.jsonl"),
"""
{"evento":"stato_maggiore_round","decisore":{"ripiego":true},"direttive":{"tattiche":[]}}
{"evento":"attacco"}
""");

    final JsonObject c = Riepilogo.contract(diary);

    // compact: {"a":1} = 7 characters, {"abc":[1,2]} = 13
    assertThat(c.get("file_stato").getAsInt()).isEqualTo(2);
    assertThat(c.get("caratteri_medi").getAsLong()).isEqualTo(10);
    assertThat(c.get("caratteri_max").getAsLong()).isEqualTo(13);
    assertThat(c.get("token_stimati_medi").getAsLong()).isEqualTo(3);
    assertThat(c.get("decisioni").getAsInt()).isEqualTo(1);
    assertThat(c.get("ripieghi_sulle_regole").getAsInt()).isEqualTo(1);

    final JsonObject game = JsonParser.parseString(SAMPLE).getAsJsonObject();
    game.add("contratto", c);
    assertThat(RiepilogoGenerale.markdown("T", List.of(new RiepilogoGenerale.Game("1-A-1", game))))
        .contains("## Contratto strategico (stato e direttive)")
        .contains("| 2 | 10 | 13 | 3 | 1/1 | 0 |")
        .contains("Media su tutte le partite: 10 caratteri");
  }

  @Test
  void theStrategistIsSummarizedPerAllianceFromTheDiary(
      @org.junit.jupiter.api.io.TempDir final java.nio.file.Path diary) throws Exception {
    java.nio.file.Files.writeString(
        diary.resolve("decisioni.jsonl"),
"""
{"evento":"stato_maggiore_round","alleanza":"Allies","round":1,"piano":"1","decisore":{"nome":"llm","fonte":"Haiku","ripiego":false,"avvisi":[],"uso":{"chiamate":1,"costo_usd":0.002,"token_ingresso":3000}},"direttive":{"postura":"OFFENSIVA","tattiche":[],"operazioni":[{"id":"Overlord UK","decisione":"lancia"}]}}
{"evento":"stato_maggiore_round","alleanza":"Allies","round":2,"piano":"2","decisore":{"nome":"llm","ripiego":true,"avvisi":["stratega non disponibile (Haiku: timeout): decidono le regole"],"uso":{"chiamate":2,"errori":1,"costo_usd":0.002,"token_ingresso":3000}},"direttive":{"postura":"DIFENSIVA","tattiche":[{"strumento":"blocco_terrestre","nazione":"Russians","attiva":true}],"operazioni":[{"id":"Overlord UK","decisione":"attendi"}]}}
{"evento":"stato_maggiore_round","alleanza":"Axis","round":1,"piano":"A","decisore":{"nome":"regole","ripiego":false,"avvisi":[]},"direttive":{"postura":"OFFENSIVA","tattiche":[]}}
""");

    final JsonObject s = Riepilogo.strategist(diary);

    final JsonObject allies = s.getAsJsonObject("Allies");
    assertThat(allies.get("decisioni").getAsInt()).isEqualTo(2);
    assertThat(allies.get("ripieghi").getAsInt()).isEqualTo(1);
    assertThat(allies.get("cambi_piano").getAsInt()).isEqualTo(1);
    assertThat(allies.getAsJsonObject("fonti").toString()).isEqualTo("{\"Haiku\":1,\"regole\":1}");
    assertThat(allies.getAsJsonObject("posture").toString())
        .isEqualTo("{\"OFFENSIVA\":1,\"DIFENSIVA\":1}");
    assertThat(allies.getAsJsonObject("strumenti_accesi").get("blocco_terrestre").getAsInt())
        .isEqualTo(1);
    assertThat(allies.getAsJsonObject("operazioni").toString())
        .isEqualTo("{\"lancia\":1,\"attendi\":1}");
    assertThat(allies.getAsJsonObject("uso").get("chiamate").getAsInt()).isEqualTo(2);
    assertThat(allies.getAsJsonArray("motivi_ripiego").get(0).getAsString())
        .startsWith("R2: stratega non disponibile");
    assertThat(s.getAsJsonObject("Axis").get("decisore").getAsString()).isEqualTo("regole");

    final JsonObject game = JsonParser.parseString(SAMPLE).getAsJsonObject();
    final JsonObject deciders = new JsonObject();
    deciders.addProperty("Allies", "llm");
    game.getAsJsonObject("parametri").add("decisori", deciders);
    game.add("stratega", s);
    assertThat(Riepilogo.markdown(game))
        .contains("- Stratega Alleati: {\"Haiku\":1,\"regole\":1}, ripieghi 1/2");
    final JsonObject rules = JsonParser.parseString(SAMPLE).getAsJsonObject();
    final String all =
        RiepilogoGenerale.markdown(
            "T",
            List.of(
                new RiepilogoGenerale.Game("8-LLM", game),
                new RiepilogoGenerale.Game("1-A-1", rules)));
    assertThat(all)
        .contains("## Stratega LLM")
        .contains("| 8-LLM | Alleati | {\"Haiku\":1,\"regole\":1} | 1/2 | 1 |")
        .contains("| 2 | 3000 | 0.0020 |")
        .contains("Totale: 2 chiamate, costo stimato $0.0020")
        .contains("| Pro+ A / Pro+ 1 (llm) | 1 |")
        .contains("| Pro+ A / Pro+ 1 | 1 |");
  }

  @Test
  void turnOrdersAndMeanRiskPerNationAreSummarized(
      @org.junit.jupiter.api.io.TempDir final java.nio.file.Path diary) throws Exception {
    java.nio.file.Files.writeString(
        diary.resolve("decisioni.jsonl"),
"""
{"evento":"stato_maggiore_round","alleanza":"Allies","round":1,"piano":"1","rischio_effettivo":{"British":{"Ovest":4,"Est":3},"Russians":{"Ovest":3,"Est":2}},"decisore":{"nome":"llm","fonte":"Haiku","ripiego":false,"avvisi":[]},"direttive":{"postura":"OFFENSIVA","tattiche":[]}}
{"evento":"ordini_turno","alleanza":"Allies","nazione":"British","round":1,"chiamato":true,"ordini":{"ordini":{"bersagli":[{"territorio":"Normandy Bordeaux"}],"operazioni":[{"id":"Overlord UK","decisione":"lancia"}],"tattiche":[{"strumento":"blocco_navale","attiva":true}]},"decisore":{"fonte":"Haiku","ripiego":false}},"uso":{"chiamate":2,"costo_usd":0.003,"per_livello":{"stratega":{"chiamate":1,"costo_usd":0.002},"ordini":{"chiamate":1,"costo_usd":0.001}}}}
{"evento":"ordini_turno","alleanza":"Allies","nazione":"Russians","round":1,"chiamato":true,"ordini":{"decisore":{"fonte":"regole","ripiego":true,"avvisi":["ordini del turno non disponibili (timeout): valgono le direttive dello stratega"]}}}
{"evento":"ordini_turno","alleanza":"Axis","nazione":"Germans","round":1,"chiamato":false}
""");

    final JsonObject s = Riepilogo.strategist(diary);

    final JsonObject allies = s.getAsJsonObject("Allies");
    assertThat(allies.get("decisioni").getAsInt()).isEqualTo(1);
    final JsonObject orders = allies.getAsJsonObject("ordini_turno");
    assertThat(orders.get("chiamate").getAsInt()).isEqualTo(2);
    assertThat(orders.get("ripieghi").getAsInt()).isEqualTo(1);
    assertThat(orders.getAsJsonObject("operazioni").get("lancia").getAsInt()).isEqualTo(1);
    assertThat(orders.getAsJsonObject("strumenti_accesi").get("blocco_navale").getAsInt())
        .isEqualTo(1);
    assertThat(allies.getAsJsonObject("rischio_medio").get("British").getAsDouble()).isEqualTo(3.5);
    assertThat(allies.getAsJsonObject("rischio_medio").get("Russians").getAsDouble())
        .isEqualTo(2.5);
    assertThat(s.has("Axis")).isTrue();

    final JsonObject game = JsonParser.parseString(SAMPLE).getAsJsonObject();
    final JsonObject deciders = new JsonObject();
    deciders.addProperty("Allies", "llm");
    game.getAsJsonObject("parametri").add("decisori", deciders);
    game.add("stratega", s);
    assertThat(Riepilogo.markdown(game))
        .contains("ordini del turno 2 (ripieghi 1)")
        .contains(
            "- Rischio effettivo medio per nazione (1-5, tutti i round e fronti): British 3.5, Russians 2.5");
    assertThat(RiepilogoGenerale.markdown("T", List.of(new RiepilogoGenerale.Game("8-LLM", game))))
        .contains("| 1 / $0.0020 | 1 / $0.0010 (ripieghi 1) |")
        .contains("Stratega 1 chiamate $0.0020 ($0.0020 per partita), Ordini del turno 1 chiamate")
        .contains("## Rischio effettivo medio per nazione")
        .contains("| 8-LLM | 3.5 | 2.5 |");
    // comparison per configuration: rules and LLM with the same plans
    final JsonObject rules = JsonParser.parseString(SAMPLE).getAsJsonObject();
    final JsonObject sonnet = game.deepCopy();
    sonnet
        .getAsJsonObject("parametri")
        .getAsJsonObject("decisori")
        .addProperty("Allies", "llm:claude-sonnet-5-5");
    final String cmp =
        RiepilogoGenerale.markdown(
            "T",
            List.of(
                new RiepilogoGenerale.Game("1-A-1", rules),
                new RiepilogoGenerale.Game("8-LLM", game),
                new RiepilogoGenerale.Game("11-Sonnet", sonnet)));
    assertThat(cmp)
        .contains("Confronto per configurazione")
        .contains("| Pro+ A / Pro+ 1 (llm) | 1 |")
        .contains("| Pro+ A / Pro+ 1 (llm:claude-sonnet-5-5) | 1 |")
        .contains("100% / 0% / 0% (1)")
        .contains("| 0.0030 |");
  }

  @Test
  void theQualityTestOfTurnOrders(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
      throws Exception {
    java.nio.file.Files.writeString(
        dir.resolve("ordini-ingresso-R3-British.json"),
        "{\"nazione\": \"British\", \"round\": 3, \"fronti\": [\"Ovest\"],"
            + " \"operazioni\": [{\"id\": \"Overlord UK\"}]}");
    java.nio.file.Files.writeString(
        dir.resolve("ordini-ingresso-R4-British.json"),
        "{\"nazione\": \"British\", \"round\": 4, \"fronti\": [\"Ovest\"], \"operazioni\": []}");
    final List<games.strategy.triplea.ai.proplus.strategia.llm.Richiesta> asked =
        new java.util.ArrayList<>();

    final JsonObject r =
        TestQualita.valuta(
            TestQualita.orderInputs(dir),
            games.strategy.triplea.ai.proplus.strategia.llm.Fornitore.ANTHROPIC,
            2,
            req -> {
              asked.add(req);
              return new games.strategy.triplea.ai.proplus.strategia.llm.Risposta(
                  "{\"bersagli\": [], \"operazioni\": [{\"id\": \"Overlord UK\", \"decisione\":"
                      + " \"lancia\"}], \"tattiche\": [], \"motivazione\": \"Vado.\"}",
                  "Haiku",
                  "claude-haiku-5-5",
                  500,
                  100,
                  2000,
                  0,
                  0.0004,
                  false,
                  1500);
            },
            TestQualita.ORDINI);

    assertThat(asked.get(0).sistema()).contains("ufficiale operativo");
    assertThat(r.get("livello").getAsString()).isEqualTo("ordini del turno");
    assertThat(r.get("json_validi_primo_colpo").getAsInt()).isEqualTo(2);
    // R4: Overlord UK is not an operation of the input
    assertThat(r.get("direttive_limitate_dai_paletti").getAsInt()).isEqualTo(1);
    assertThat(TestQualita.markdown(r))
        .contains("(ordini del turno)")
        .contains("Ordini del turno, Haiku");
  }

  @Test
  void theQualityTestCountsValidJsonLimitsLatencyAndCost(
      @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {
    final java.nio.file.Path example =
        java.nio.file.Path.of("../../proplus/strategia/esempi/stato-R2-Allies.json");
    for (int i = 1; i <= 3; i++) {
      java.nio.file.Files.copy(example, dir.resolve("stato-R" + i + "-Allies.json"));
    }
    final String good =
        "{\"piano\": \"1\", \"postura\": \"CONTRASTO\", \"rischio\": [{\"fronte\": \"Ovest\","
            + " \"valore\": 1}], \"operazioni\": [], \"bersagli\": [{\"territorio\": \"Norway\","
            + " \"peso\": 25, \"origine\": \"contromossa\", \"critico\": false}], \"tattiche\": [],"
            + " \"ruoli\": [], \"lettura_nemico\": {\"piano\": \"A\", \"probabilita\": 0.8},"
            + " \"motivazione\": \"Contrasto Barbarossa.\"}";
    final int[] n = {0};
    final JsonObject r =
        TestQualita.valuta(
            TestQualita.pick(TestQualita.states(dir), 30),
            games.strategy.triplea.ai.proplus.strategia.llm.Fornitore.ANTHROPIC,
            2,
            req -> {
              n[0]++;
              if (n[0] == 3) {
                throw new games.strategy.triplea.ai.proplus.strategia.llm.ErroreStratega("timeout");
              }
              return new games.strategy.triplea.ai.proplus.strategia.llm.Risposta(
                  n[0] == 1 ? good : "{\"piano\": \"Z\", \"postura\": \"OFFENSIVA\"}",
                  "Haiku",
                  "claude-haiku-5-5",
                  3000,
                  300,
                  6000,
                  0,
                  0.001,
                  false,
                  2000);
            });

    assertThat(r.get("stati").getAsInt()).isEqualTo(3);
    assertThat(r.get("json_validi_primo_colpo").getAsInt()).isEqualTo(1);
    assertThat(r.get("direttive_limitate_dai_paletti").getAsInt()).isEqualTo(1);
    assertThat(r.get("latenza_media_ms").getAsLong()).isEqualTo(2000);
    assertThat(r.get("costo_totale_usd").getAsDouble()).isEqualTo(0.002);
    assertThat(r.getAsJsonArray("motivazioni").get(0).getAsString())
        .isEqualTo("R2 Allies: Contrasto Barbarossa.");
    assertThat(TestQualita.markdown(r))
        .contains("JSON validi al primo colpo 1 (33%)")
        .contains("peso 25.0 fuori da 0..20.0")
        .contains("piano non ammesso: Z")
        .contains("| NO |");
    assertThat(TestQualita.pick(List.of(dir, dir, dir, dir), 2)).hasSize(2);
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
  void usLandingsInWesternEuropeAndTheFirstHeldBridgehead() {
    final JsonArray landings =
        JsonParser.parseString(
                "[{\"round\":4,\"nazione\":\"Americans\",\"alleanza\":\"Allies\",\"territorio\":\"Normandy"
                    + " Bordeaux\",\"unita\":4,\"fase\":\"combattimento\"},"
                    + "{\"round\":5,\"nazione\":\"Americans\",\"alleanza\":\"Allies\",\"territorio\":\"Holland"
                    + " Belgium\",\"unita\":3,\"fase\":\"combattimento\"},"
                    + "{\"round\":5,\"nazione\":\"Americans\",\"alleanza\":\"Allies\","
                    + "\"territorio\":\"Greece\",\"unita\":2,\"fase\":\"combattimento\"},"
                    + "{\"round\":6,\"nazione\":\"British\",\"alleanza\":\"Allies\","
                    + "\"territorio\":\"Norway\",\"unita\":2,\"fase\":\"combattimento\"}]")
            .getAsJsonArray();
    // Normandy lost in round 5; Holland held at the end of rounds 5 and 6
    final JsonArray rounds =
        JsonParser.parseString(
                "[{\"round\":4,\"ovest_alleato\":[\"Normandy Bordeaux\"]},"
                    + "{\"round\":5,\"ovest_alleato\":[\"Holland Belgium\"]},"
                    + "{\"round\":6,\"ovest_alleato\":[\"Holland Belgium\",\"Norway\"]},"
                    + "{\"round\":7,\"ovest_alleato\":[\"Holland Belgium\"]}]")
            .getAsJsonArray();

    final JsonObject us = Riepilogo.usLandedInWestEurope(landings, 7);
    assertThat(us.get("4").getAsInt()).isEqualTo(4);
    assertThat(us.get("5").getAsInt()).isEqualTo(3); // Greece is not western Europe
    assertThat(us.get("6").getAsInt()).isZero();
    final JsonObject head = Riepilogo.firstHeldBridgehead(landings, rounds).orElseThrow();
    assertThat(Riepilogo.landingText(head))
        .isEqualTo("R5 Holland Belgium (Americans, 3 unita' sbarcate (assalto anfibio))");
    final JsonObject game = new JsonObject();
    game.add("usa_sbarcate_ovest", us);
    assertThat(Riepilogo.usLandedText(game)).isEqualTo("R4 4, R5 3 (totale 7)");
    assertThat(RiepilogoGenerale.usWestRound(game)).isEqualTo(4);
    assertThat(Riepilogo.firstHeldBridgehead(landings, new JsonArray())).isEmpty();
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

  @Test
  void smokeCalibrationLeavesOutCancelPostponeAndWithdraw() {
    final JsonObject game =
        JsonParser.parseString(
                """
                {"stato":"completata",
                 "parametri":{"tipi":{"Axis":"stratega","Allies":"hard"}},
                 "stratega_llm":{"uso":{"chiamate":3,"costo_usd":0.03},
                  "Axis":{"round_decisi":2,"round_senza_llm":0,"operazioni":[
                   {"tipo":"offensiva","bersaglio":"France","stato":"annullata",
                    "previsioni":[
                     {"round":1,"azione":"avvia","prob_tenuta_stimata":0.9,"esito_reale":true},
                     {"round":2,"azione":"annulla","prob_tenuta_stimata":0.0,"esito_reale":true}],
                    "decisioni_ordini":[
                     {"round":1,"azione":"tieni","prob_llm":0.7,"esito_reale":true},
                     {"round":1,"azione":"ritira","prob_llm":0.1,"esito_reale":true},
                     {"round":1,"azione":"rinvia","prob_llm":0.2,"esito_reale":true}]}]}}}
                """)
            .getAsJsonObject();
    final StringBuilder md = new StringBuilder();

    ConfrontoSmoke.write(
        md, List.of(new RiepilogoGenerale.Game("1-stratega", game)), List.of("Axis", "Allies"));

    final String text = md.toString();
    assertThat(text)
        .contains("| 80-100% | 1 | 90% | 100% |")
        .contains("| 60-79% | 1 | 70% | 100% |");
    assertThat(text).doesNotContain("| 0-19% |").doesNotContain("| 20-39% |");
    assertThat(text).contains("Errore medio pesato: 10.0 punti (1 stime)");
    assertThat(text).contains("Errore medio pesato: 30.0 punti (1 stime)");
  }

  @Test
  void smokeCountsOnlyTheEngineStepsAsExecutedAndReportsCausesAndCoherence() {
    final JsonObject game =
        JsonParser.parseString(
                """
                {"stato":"completata",
                 "parametri":{"tipi":{"Axis":"stratega","Allies":"hard"}},
                 "stratega_llm":{"uso":{"chiamate":3,"costo_usd":0.03},
                  "Axis":{"round_decisi":2,"round_senza_llm":0,
                   "blocchi":{"tetto_perdita":2,"sicurezza_raduni":1,"quota_pu":3,"ultimi":["x"]},
                   "operazioni":[
                   {"tipo":"offensiva","bersaglio":"France","stato":"tenuta",
                    "presa_senza_operazione":true,
                    "stima_avvio":{"successo_pct":60,"tenuta_pct":70},
                    "stima_decisione_prima":{"successo_pct":50,"tenuta_pct":60}},
                   {"tipo":"offensiva","bersaglio":"Egypt","stato":"completata","tappa_motore":true,
                    "stima_avvio":{"successo_pct":40,"tenuta_pct":50},
                    "stima_decisione_prima":{"successo_pct":44,"tenuta_pct":52}},
                   {"tipo":"rinforzo","bersaglio":"Rostov","stato":"annullata",
                    "causa_annullamento":"C2"},
                   {"tipo":"tenuta","bersaglio":"Rome","stato":"completata",
                    "tenuta_motore":true}]}}}
                """)
            .getAsJsonObject();
    final StringBuilder md = new StringBuilder();

    ConfrontoSmoke.write(
        md, List.of(new RiepilogoGenerale.Game("1-stratega", game)), List.of("Axis", "Allies"));

    final String text = md.toString();
    // France taken by Hard: not executed by the engine, in its own column
    assertThat(text).contains("| offensiva | 2 | 1 | 1 | 1 | 0 | 0 | 1 |");
    assertThat(text).contains("| rinforzo | 1 | 0 | 0 | 0 | 0 | 1 | 0 |");
    assertThat(text).contains("| tenuta | 1 | 1 | 0 | 1 | 0 | 0 | 0 |");
    assertThat(text).contains("Annullamenti per artefatto (C1-C5): **1 su 4 avviate (25%)**");
    assertThat(text).contains("Offensive e sbarchi eseguiti DAL MOTORE: **1 su 2 avviati (50%)**");
    assertThat(text).contains("mediana **7.0 punti** di successo su 2 operazioni");
    assertThat(text).contains("quota_pu=3").contains("tetto_perdita=2");
    assertThat(ConfrontoSmoke.mediana(List.of(3.0, 1.0, 2.0))).isEqualTo(2.0);
  }

  @Test
  void parsesTheStrategistSwitchesOfStepR0() {
    final AutoplayOptions o =
        AutoplayOptions.parse(
            new String[] {
              "--mappa",
              "m.xml",
              "--tipo",
              "Axis=stratega",
              "--stratega-spento",
              "Axis=liste+tenute"
            });
    assertThat(o.strategaSpento("Axis")).isEqualTo("liste,tenute");
    assertThat(o.strategaSpento("Allies")).isEmpty();
    assertThatThrownBy(
            () ->
                AutoplayOptions.parse(
                    new String[] {"--mappa", "m", "--stratega-spento", "Axis=difesa"}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void diagnosisOfStepR0ComparesTheStrategistConfigurations() {
    final List<RiepilogoGenerale.Game> games = new java.util.ArrayList<>();
    final int[][] vc = {{6, 7, 5}, {6, 6, 5}, {4, 3, 4}, {5, 5, 6}};
    final String[] params = {
      "\"tipi\":{\"Axis\":\"hard\",\"Allies\":\"hard\"}",
      "\"tipi\":{\"Axis\":\"stratega\",\"Allies\":\"hard\"},\"chiave_llm\":false",
      "\"tipi\":{\"Axis\":\"stratega\",\"Allies\":\"hard\"},\"chiave_llm\":true",
      "\"tipi\":{\"Axis\":\"stratega\",\"Allies\":\"hard\"},\"chiave_llm\":true,"
          + "\"stratega_spento\":{\"Axis\":\"liste\"}"
    };
    for (int c = 0; c < vc.length; c++) {
      for (int seed = 0; seed < 3; seed++) {
        final String json =
            "{\"stato\":\"completata\",\"parametri\":{\"round_max\":12,"
                + params[c]
                + "},\"alleanze\":{\"Axis\":[\"Germans\"],\"Allies\":[\"British\"]},"
                + "\"tuv_perso\":{\"Axis\":100,\"Allies\":90},"
                + "\"capitali\":[],"
                + "\"controllo_nudo\":{\"Germans\":{\"R1\":{\"domande\":10,"
                + "\"differenze\":0,\"operazione\":1,\"esempi\":[]}}},"
                + "\"round\":[{\"round\":12,\"completo\":true,\"nazioni\":{"
                + "\"Germans\":{\"reddito\":40},\"British\":{\"reddito\":30}},"
                + "\"vc\":{\"Axis\":{\"numero\":"
                + vc[c][seed]
                + "},\"Allies\":{\"numero\":3}}}]}";
        games.add(
            new RiepilogoGenerale.Game(
                c + "-x-seme" + seed, JsonParser.parseString(json).getAsJsonObject()));
      }
    }
    final StringBuilder md = new StringBuilder();

    ConfrontoDiagnosi.write(md, games, List.of("Axis", "Allies"), List.of(4, 8, 12));

    final String text = md.toString();
    assertThat(text).contains("## Diagnosi Stratega (R0)");
    assertThat(text).contains("| Hard / Hard | Asse | 3 | 6.00 ± 1.00 (5.00-7.00)");
    assertThat(text).contains("| Stratega LLM [senza chiave] / Hard | Asse | 3 |");
    assertThat(text)
        .contains("| Stratega LLM / Hard − Hard / Hard | Asse | VC R12 | -2.33 |")
        .contains("| Stratega LLM [senza liste] / Hard − Stratega LLM / Hard | Asse | VC R12 |");
    assertThat(text).contains("| Stratega LLM [senza chiave] / Hard | 3 | 30 | 0 | 3 |");
    assertThat(ConfrontoDiagnosi.full("Stratega LLM [senza tenute] / Hard"))
        .isEqualTo("Stratega LLM / Hard");
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
