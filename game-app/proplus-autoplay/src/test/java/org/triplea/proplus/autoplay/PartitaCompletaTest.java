package org.triplea.proplus.autoplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step PARTITA COMPLETA: real Hard vs Hard games on a small map whose Allied victory trigger holds
 * from round 3 (the Russians keep their capital) and whose message, as in WW2 Europe 1940, is in no
 * notifications file (the engine alone never ends the game).
 */
class PartitaCompletaTest {
  private static Path map() throws Exception {
    return Path.of(PartitaCompletaTest.class.getResource("/minimap-vittoria.xml").toURI());
  }

  private static JsonObject play(final Path out, final String... extra) throws Exception {
    final List<String> args =
        new java.util.ArrayList<>(
            List.of(
                "--mappa", map().toString(),
                "--tipo", "Axis=hard,Allies=hard",
                "--seme", "1",
                "--minuti", "10",
                "--output", out.toString()));
    args.addAll(List.of(extra));
    assertThat(AutoplayRunner.playGame(args.toArray(String[]::new))).isZero();
    return JsonParser.parseString(
            Files.readString(out.resolve(Riepilogo.JSON_FILE), StandardCharsets.UTF_8))
        .getAsJsonObject();
  }

  @Test
  void theGameStopsAtTheRoundWhoseEndCompletesTheVictory(@TempDir final Path dir) throws Exception {
    final JsonObject json =
        play(dir.resolve("vittoria"), "--round", "10", "--fino-a-vittoria", "si");

    // condition met at the end of round 3, held for a complete round: victory at the end of R4
    final JsonObject outcome = json.getAsJsonObject("esito");
    assertThat(outcome.get("vincitore").getAsString()).isEqualTo("Allies");
    assertThat(outcome.get("round_vittoria").getAsInt()).isEqualTo(4);
    assertThat(outcome.get("testo").getAsString()).isEqualTo("vittoria Alleati al round 4");
    assertThat(json.get("stato").getAsString()).isEqualTo("completata");
    assertThat(json.get("round_giocati").getAsInt()).isEqualTo(4);
    assertThat(
            json.getAsJsonObject("vittoria").getAsJsonObject("condizione").get("Allies").toString())
        .isEqualTo("[3,4]");
    assertThat(json.getAsJsonObject("tempi").getAsJsonObject("per_round_s").keySet())
        .contains("1", "2", "3", "4")
        .doesNotContain("6");
  }

  @Test
  void aGameAtTheRoundCapWithoutAWinnerIsAStalemateNotAnError(@TempDir final Path dir)
      throws Exception {
    final JsonObject json = play(dir.resolve("stallo"), "--round", "3", "--fino-a-vittoria", "si");

    final JsonObject outcome = json.getAsJsonObject("esito");
    assertThat(outcome.has("vincitore")).isFalse();
    assertThat(outcome.get("stallo").getAsBoolean()).isTrue();
    assertThat(json.get("stato").getAsString())
        .isEqualTo("stallo: tetto di 3 round senza vincitore");
    assertThat(json.get("round_giocati").getAsInt()).isEqualTo(3);
  }

  @Test
  void withoutTheOptionTheGameGoesOnToTheLastRoundAsBefore(@TempDir final Path dir)
      throws Exception {
    final JsonObject json = play(dir.resolve("normale"), "--round", "5");

    assertThat(json.getAsJsonObject("esito").get("testo").getAsString())
        .isEqualTo("nessuna entro 5 round");
    assertThat(json.get("stato").getAsString()).isEqualTo("completata");
    assertThat(json.get("round_giocati").getAsInt()).isEqualTo(5);
    assertThat(json.has("vittoria")).isFalse();
  }

  @Test
  void theSummaryOfCompleteGamesGivesBandsEveryFourRoundsAndTheCiEstimate(@TempDir final Path dir)
      throws Exception {
    play(dir.resolve("1-vittoria"), "--round", "10", "--fino-a-vittoria", "si");
    RiepilogoGenerale.run(new String[] {dir.toString(), "--titolo", "Prova"});

    final String md = Files.readString(dir.resolve(RiepilogoGenerale.FILE), StandardCharsets.UTF_8);
    assertThat(md)
        .contains("## Partite complete (fino alla vittoria)")
        .contains("| 1-vittoria | vittoria Alleati al round 4 | 4 | 4 |")
        .contains("Alleati 3,4")
        .contains("| Nazione | R4 |")
        .contains("| Russians |")
        .contains("Stima per una run di 60 partite complete");
  }
}
