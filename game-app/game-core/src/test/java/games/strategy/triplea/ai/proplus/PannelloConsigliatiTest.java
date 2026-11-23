package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.proplus.strategia.Decisori;
import games.strategy.triplea.ai.proplus.strategia.llm.Fornitore;
import games.strategy.triplea.ai.proplus.strategia.llm.Modalita;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Steps P11, P12, PF1: the two boxes of the panel ("Alleati consigliati", "Asse consigliato"), on
 * by default; "Pro+ (Europe 1940)" plays exactly the autoplay configurations of P12/P13 (player
 * type "stratega" without a key) with the switches of the boxes; the old player type "Pro+ Stratega
 * LLM" keeps reading the profile's switches (no profile = nothing on, as in the autoplay).
 */
class PannelloConsigliatiTest extends AbstractClientSettingTestCase {
  private static final List<String> NATIONS =
      List.of("Americans", "British", "Russians", "Germans", "Italians");
  private static final Set<String> SEVEN =
      Set.of(
          "usa_proporzione",
          "sbarco_concentrato",
          "niente_neutrali",
          "norvegia_presto",
          "norvegia_esposta",
          "ponte_concentrato",
          "taranto");

  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
    DecisoreOperazione.setFabbrica(null);
  }

  // ---------------------------------------------------------------------------- the boxes

  @Test
  void withoutAProfileBothBoxesAreOn() {
    final ProfiloStratega p = ProfiloStratega.carica();

    assertThat(p.alleatiConsigliati()).isTrue();
    assertThat(p.asseConsigliato()).isTrue();
  }

  @Test
  void theBoxesTurnedOffAreSavedAndReadBack() throws IOException {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setAlleatiConsigliati(false);
    p.setAsseConsigliato(false);
    p.salva();

    final ProfiloStratega again = ProfiloStratega.carica();
    assertThat(again.alleatiConsigliati()).isFalse();
    assertThat(again.asseConsigliato()).isFalse();
    again.normalizza(); // as the panel does when opened
    assertThat(again.alleatiConsigliati()).isFalse();
    assertThat(again.asseConsigliato()).isFalse();
  }

  @Test
  void theBoxesTurnedOnAreSavedAndReadBack() throws IOException {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setAlleatiConsigliati(true);
    p.setAsseConsigliato(false);
    p.salva();

    final ProfiloStratega again = ProfiloStratega.carica();
    assertThat(again.alleatiConsigliati()).isTrue();
    assertThat(again.asseConsigliato()).isFalse();
  }

  @Test
  void anOldProfileLoadsWithBothBoxesOnAndKeepsTheKey() throws IOException {
    oldProfile(
        """
        pannello=p12
        chiave.anthropic=sk-ant-finta-1234
        usa_proporzione=no
        ponte_uk=si
        compiti=si
        assedio=no
        italia_convoglio=si
        """);

    final ProfiloStratega p = ProfiloStratega.carica();

    assertThat(p.alleatiConsigliati()).isTrue();
    assertThat(p.asseConsigliato()).isTrue();
    assertThat(p.taranto()).isTrue();
    assertThat(p.ponteUk()).isFalse();
    assertThat(p.compiti()).isFalse();
    assertThat(p.italiaConvoglio()).isFalse();
    assertThat(p.chiaveSalvata(Fornitore.ANTHROPIC)).contains("sk-ant-finta-1234");
  }

  @Test
  void aProfileWithoutMarkerLoadsWithBothBoxesOn() throws IOException {
    oldProfile("impegno=si\n");

    final ProfiloStratega p = ProfiloStratega.carica();

    assertThat(p.alleatiConsigliati()).isTrue();
    assertThat(p.asseConsigliato()).isTrue();
    assertThat(p.impegno()).isFalse();
  }

  @Test
  void aProfileSavedFromNowOnKeepsItsSwitchesForTheAutoplayAndTheTests() throws IOException {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setPonteUk(true);
    p.salva();

    assertThat(ProfiloStratega.carica().ponteUk()).isTrue();
  }

  // ------------------------------------------------------------------ "Pro+ (Europe 1940)"

  @Test
  void europa1940WithoutAProfilePlaysTheSevenAlliesAndImpegnoAssedio() {
    final GameData data = registeredEuropa1940();
    final GameProperties props = data.getProperties();

    assertThat(Set.copyOf(StrategaInterruttori.accesi(props, "Allies"))).isEqualTo(SEVEN);
    assertThat(Set.copyOf(StrategaInterruttori.accesi(props, "Axis")))
        .containsExactlyInAnyOrder("impegno", "assedio");
    for (final String alliance : List.of("Allies", "Axis")) {
      assertThat(Ablazione.spenti(props, alliance))
          .isEqualTo(EnumSet.allOf(Ablazione.Componente.class));
      assertThat(Stratega.senzaLlm(props, alliance)).isTrue();
      assertThat(Decisori.getModalita(props, alliance)).isEqualTo(Modalita.REGOLE);
    }
    assertThat(Stratega.nazioni(props, "Allies"))
        .containsExactlyInAnyOrder("Americans", "British", "Russians");
  }

  @Test
  void europa1940WithBothBoxesOffIsHard() throws IOException {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setAlleatiConsigliati(false);
    p.setAsseConsigliato(false);
    p.salva();

    final GameProperties props = registeredEuropa1940().getProperties();

    assertThat(StrategaInterruttori.accesi(props, "Allies")).isEmpty();
    assertThat(StrategaInterruttori.accesi(props, "Axis")).isEmpty();
    assertThat(Ablazione.spenti(props, "Allies"))
        .isEqualTo(EnumSet.allOf(Ablazione.Componente.class));
  }

  @Test
  void europa1940IgnoresTheOtherSwitchesOfTheProfile() throws IOException {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setAlleatiConsigliati(false);
    p.setAsseConsigliato(true);
    p.setPonteUk(true);
    p.setCompiti(true);
    p.setItaliaConvoglio(true);
    p.salva();

    final GameProperties props = registeredEuropa1940().getProperties();

    assertThat(StrategaInterruttori.accesi(props, "Allies")).isEmpty();
    assertThat(Set.copyOf(StrategaInterruttori.accesi(props, "Axis")))
        .containsExactlyInAnyOrder("impegno", "assedio");
  }

  /**
   * The autoplay configurations "Alleati finali + taranto" (P13 r1 and combinations 1xx) and
   * "assedio" (Axis=impegno+assedio): player type "stratega" ({@link ProPlusStrategaAi}), no
   * profile, the switches written by {@code AutoplayRunner.configure} (stratega-spento "",
   * stratega-acceso), the rules as strategic decider. "Pro+ (Europe 1940)" with both boxes on
   * reaches the same state of every switch, ablation and decider.
   */
  @Test
  void europa1940EqualsTheAutoplayConfigurations() {
    final GameData autoplay = TestMapGameData.GLOBAL1940.getGameData();
    final GameProperties a = autoplay.getProperties();
    for (final String alliance : List.of("Allies", "Axis")) {
      Decisori.set(a, alliance, games.strategy.triplea.ai.proplus.strategia.Decisore.REGOLE, "");
      Ablazione.set(a, alliance, "");
      DecisoreOperazione.set(a, alliance, "");
      StrategaInterruttori.set(a, alliance, "");
    }
    StrategaInterruttori.accendi(a, "Allies", String.join(",", SEVEN));
    StrategaInterruttori.accendi(a, "Axis", "impegno,assedio");
    for (final String n : NATIONS) {
      Stratega.registra(autoplay, autoplay.getPlayerList().getPlayerId(n));
    }
    final GameProperties e = registeredEuropa1940().getProperties();

    for (final String alliance : List.of("Allies", "Axis")) {
      for (final StrategaInterruttori s : StrategaInterruttori.values()) {
        assertThat(s.acceso(e, alliance))
            .as(alliance + " " + s.id())
            .isEqualTo(s.acceso(a, alliance));
      }
      assertThat(Ablazione.spenti(e, alliance)).isEqualTo(Ablazione.spenti(a, alliance));
      assertThat(DecisoreOperazione.modello(e, alliance))
          .isEqualTo(DecisoreOperazione.modello(a, alliance));
      assertThat(Decisori.getModalita(e, alliance)).isEqualTo(Decisori.getModalita(a, alliance));
      assertThat(Stratega.nazioni(e, alliance)).isEqualTo(Stratega.nazioni(a, alliance));
    }
  }

  @Test
  void europa1940NeverReachesTheLlmEvenWithAWorkingTransport() {
    final boolean[] called = {false};
    DecisoreOperazione.setFabbrica(
        model ->
            new DecisoreOperazione.Trasporto() {
              @Override
              public Optional<String> nonDisponibile() {
                return Optional.empty();
              }

              @Override
              public Risposta chiama(
                  final games.strategy.triplea.ai.proplus.strategia.llm.Richiesta r) {
                called[0] = true;
                throw new IllegalStateException("no call expected");
              }
            });
    final GameProperties props = registeredEuropa1940().getProperties();

    final DecisoreOperazione.Trasporto t =
        DecisoreOperazione.trasporto(props, "Allies", "claude-test");

    assertThat(t.nonDisponibile()).contains(Stratega.MOTIVO_SENZA_LLM);
    assertThat(called[0]).isFalse();
  }

  @Test
  void theTurnTraceHasTheSwitchesOnAndThePlayerTypes() {
    final GameData data = registeredEuropa1940();
    final games.strategy.engine.data.GamePlayer british =
        data.getPlayerList().getPlayerId("British");
    british.setWhoAmI("AI:" + ProPlusEuropa1940Ai.PLAYER_LABEL);

    Tracciamento1940.inizioTurno(data, british);

    final com.google.gson.JsonObject o =
        Tracciamento1940.letto(data.getProperties(), "Allies").orElseThrow();
    final Set<String> on = new java.util.HashSet<>();
    o.getAsJsonArray("interruttori_attivi").forEach(e -> on.add(e.getAsString()));
    assertThat(on).containsAll(SEVEN);
    assertThat(o.getAsJsonObject("tipi_giocatore").get("British").getAsString())
        .isEqualTo(ProPlusEuropa1940Ai.PLAYER_LABEL);
    assertThat(o.get("senza_llm").getAsBoolean()).isTrue();
    assertThat(RegistroDecisioni.righe(data))
        .anyMatch(r -> "tracciamento".equals(r.get("tipo").getAsString()));
  }

  // -------------------------------------------------------------- old "Pro+ Stratega LLM"

  @Test
  void theOldPlayerTypeWithoutAProfileTurnsNothingOnAsInTheAutoplay() {
    final GameProperties props = registeredOld().getProperties();

    assertThat(StrategaInterruttori.accesi(props, "Allies")).isEmpty();
    assertThat(StrategaInterruttori.accesi(props, "Axis")).isEmpty();
    assertThat(Stratega.senzaLlm(props, "Allies")).isFalse();
  }

  @Test
  void theOldPlayerTypeReadsTheBoxesSavedByThePanel() throws IOException {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setAlleatiConsigliati(true);
    p.setAsseConsigliato(true);
    p.salva();

    final GameProperties props = registeredOld().getProperties();

    assertThat(Set.copyOf(StrategaInterruttori.accesi(props, "Allies"))).isEqualTo(SEVEN);
    assertThat(Set.copyOf(StrategaInterruttori.accesi(props, "Axis")))
        .containsExactlyInAnyOrder("impegno", "assedio");
  }

  private void oldProfile(final String text) throws IOException {
    Files.writeString(profile.resolve("stratega.properties"), text, StandardCharsets.UTF_8);
  }

  private static GameData registeredOld() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    for (final String n : NATIONS) {
      Stratega.registra(data, data.getPlayerList().getPlayerId(n));
    }
    return data;
  }

  private static GameData registeredEuropa1940() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    final ProfiloStratega p = ProfiloStratega.carica();
    for (final String n : NATIONS) {
      Stratega.registraEuropa1940(
          data, data.getPlayerList().getPlayerId(n), p.alleatiConsigliati(), p.asseConsigliato());
    }
    return data;
  }
}
