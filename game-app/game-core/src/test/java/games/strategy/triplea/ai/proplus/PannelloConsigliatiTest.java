package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.proplus.strategia.llm.Fornitore;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P11: the two boxes of the panel ("Alleati consigliati", "Asse consigliato") turn on exactly
 * their switches; an old profile (one box per switch) loads with a side on only if it is exactly
 * its recommended combination.
 */
class PannelloConsigliatiTest extends AbstractClientSettingTestCase {
  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

  @Test
  void alleatiConsigliatiTurnsOnExactlyTheSixSwitchesOfTheAllies() throws IOException {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setAlleatiConsigliati(true);
    p.salva();

    final GameData data = registered();

    assertThat(Set.copyOf(StrategaInterruttori.accesi(data.getProperties(), "Allies")))
        .containsExactlyInAnyOrder(
            "usa_proporzione",
            "sbarco_concentrato",
            "niente_neutrali",
            "norvegia_presto",
            "norvegia_esposta",
            "ponte_concentrato");
    assertThat(StrategaInterruttori.accesi(data.getProperties(), "Axis")).isEmpty();
  }

  @Test
  void asseConsigliatoTurnsOnExactlyImpegnoAndAssedioOfTheAxis() throws IOException {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setAsseConsigliato(true);
    p.salva();

    final GameData data = registered();

    assertThat(Set.copyOf(StrategaInterruttori.accesi(data.getProperties(), "Axis")))
        .containsExactlyInAnyOrder("impegno", "assedio");
    assertThat(StrategaInterruttori.accesi(data.getProperties(), "Allies")).isEmpty();
  }

  @Test
  void bothBoxesOffIsHardForBothSides() throws IOException {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setAlleatiConsigliati(false);
    p.setAsseConsigliato(false);
    p.salva();

    final GameData data = registered();

    assertThat(StrategaInterruttori.accesi(data.getProperties(), "Allies")).isEmpty();
    assertThat(StrategaInterruttori.accesi(data.getProperties(), "Axis")).isEmpty();
  }

  @Test
  void anOldProfileWithExactlyTheRecommendedCombinationsLoadsWithBothBoxesOn() throws IOException {
    oldProfile(
        """
        usa_proporzione=si
        sbarco_concentrato=si
        niente_neutrali=si
        ponte_uk=no
        assedio=si
        italia_convoglio=no
        """);

    final ProfiloStratega p = ProfiloStratega.carica();

    assertThat(p.alleatiConsigliati()).isTrue();
    assertThat(p.asseConsigliato()).isTrue();
    final GameData data = registered();
    assertThat(Set.copyOf(StrategaInterruttori.accesi(data.getProperties(), "Allies")))
        .containsExactlyInAnyOrder(
            "usa_proporzione",
            "sbarco_concentrato",
            "niente_neutrali",
            "norvegia_presto",
            "norvegia_esposta",
            "ponte_concentrato");
    assertThat(Set.copyOf(StrategaInterruttori.accesi(data.getProperties(), "Axis")))
        .containsExactlyInAnyOrder("impegno", "assedio");
  }

  @Test
  void anOldProfileWithOtherSwitchesLoadsWithTheBoxesOffAndTheSwitchesOff() throws IOException {
    oldProfile(
        """
        chiave.anthropic=sk-ant-finta-1234
        usa_proporzione=si
        sbarco_concentrato=si
        niente_neutrali=si
        ponte_uk=si
        compiti=si
        assedio=si
        italia_convoglio=si
        """);

    final ProfiloStratega p = ProfiloStratega.carica();

    assertThat(p.alleatiConsigliati()).isFalse();
    assertThat(p.asseConsigliato()).isFalse();
    assertThat(p.usaProporzione()).isFalse();
    assertThat(p.nienteNeutrali()).isFalse();
    assertThat(p.ponteUk()).isFalse();
    assertThat(p.compiti()).isFalse();
    assertThat(p.assedio()).isFalse();
    assertThat(p.italiaConvoglio()).isFalse();
    assertThat(p.chiaveSalvata(Fornitore.ANTHROPIC)).contains("sk-ant-finta-1234");
    final GameData data = registered();
    assertThat(StrategaInterruttori.accesi(data.getProperties(), "Allies")).isEmpty();
    assertThat(StrategaInterruttori.accesi(data.getProperties(), "Axis")).isEmpty();
  }

  @Test
  void anOldProfileWithImpegnoAndAssedioKeepsTheAxisAndTurnsTheAlliesOff() throws IOException {
    // "impegno" turned on the commitment of both alliances: it is not part of the Allies' box
    oldProfile(
        """
        impegno=si
        assedio=si
        """);

    final ProfiloStratega p = ProfiloStratega.carica();

    assertThat(p.asseConsigliato()).isTrue();
    assertThat(p.alleatiConsigliati()).isFalse();
    assertThat(p.impegno()).isFalse();
    final GameData data = registered();
    assertThat(StrategaInterruttori.accesi(data.getProperties(), "Allies")).isEmpty();
    assertThat(Set.copyOf(StrategaInterruttori.accesi(data.getProperties(), "Axis")))
        .containsExactlyInAnyOrder("impegno", "assedio");
  }

  @Test
  void aProfileSavedFromNowOnKeepsItsSwitchesForTheAutoplayAndTheTests() throws IOException {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setPonteUk(true);
    p.salva();

    assertThat(ProfiloStratega.carica().ponteUk()).isTrue();
  }

  @Test
  void aP11ProfileWithTheCoreOfThreeLoadsAsTheBoxOnWithTheSixSwitches() throws IOException {
    oldProfile(
        """
        pannello=p11
        usa_proporzione=si
        sbarco_concentrato=si
        niente_neutrali=si
        norvegia_presto=no
        norvegia_esposta=no
        ponte_concentrato=no
        """);

    final ProfiloStratega p = ProfiloStratega.carica();

    assertThat(p.alleatiConsigliati()).isTrue();
    assertThat(p.norvegiaPresto()).isTrue();
    assertThat(p.norvegiaEsposta()).isTrue();
    assertThat(p.ponteConcentrato()).isTrue();
    final GameData data = registered();
    assertThat(Set.copyOf(StrategaInterruttori.accesi(data.getProperties(), "Allies")))
        .containsExactlyInAnyOrder(
            "usa_proporzione",
            "sbarco_concentrato",
            "niente_neutrali",
            "norvegia_presto",
            "norvegia_esposta",
            "ponte_concentrato");
  }

  @Test
  void aP11ProfileWithOnlyPartOfTheSixLoadsWithTheBoxOff() throws IOException {
    oldProfile(
        """
        pannello=p11
        usa_proporzione=si
        sbarco_concentrato=si
        niente_neutrali=si
        ponte_concentrato=si
        """);

    assertThat(ProfiloStratega.carica().alleatiConsigliati()).isFalse();
  }

  private void oldProfile(final String text) throws IOException {
    Files.writeString(profile.resolve("stratega.properties"), text, StandardCharsets.UTF_8);
  }

  private static GameData registered() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    for (final String n : List.of("Americans", "British", "Russians", "Germans", "Italians")) {
      Stratega.registra(data, data.getPlayerList().getPlayerId(n));
    }
    return data;
  }
}
