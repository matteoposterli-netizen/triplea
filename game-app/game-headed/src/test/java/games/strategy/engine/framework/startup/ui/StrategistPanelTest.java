package games.strategy.engine.framework.startup.ui;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.proplus.strategia.llm.ClaudeCode;
import games.strategy.triplea.ai.proplus.strategia.llm.ClientApi;
import games.strategy.triplea.ai.proplus.strategia.llm.Fornitore;
import games.strategy.triplea.ai.proplus.strategia.llm.Http;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JCheckBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The "Stratega" section: connection test, key import, save and removal (no network). */
class StrategistPanelTest {
  private static final String KEY = "sk-ant-finta-prova-1234";

  @TempDir Path profile;
  @TempDir Path game;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

  private static ClientApi answering(final int status, final String body) {
    return new ClientApi((url, h, b, t) -> new Http.Esito(status, body));
  }

  private StrategistPanel panel(final ClientApi client) {
    return new StrategistPanel(
        new GameProperties(null),
        ProfiloStratega.carica(),
        f -> client,
        () -> new ClaudeCode.Stato(null, false, "Claude Code non trovato"),
        Optional.of(game));
  }

  @Test
  void connectionTestWithTheRightKey() {
    final String text =
        StrategistPanel.testText(
            answering(
                200,
                "{\"content\": [{\"type\": \"text\", \"text\": \"{\\\"ok\\\": true}\"}],"
                    + " \"usage\": {}}"),
            Fornitore.ANTHROPIC,
            KEY);

    assertThat(text).startsWith(StrategistPanel.TEST_OK + " Connessione riuscita: Haiku risponde");
  }

  @Test
  void connectionTestWithAWrongKey() {
    final String text =
        StrategistPanel.testText(
            answering(401, "{\"error\": {\"message\": \"invalid x-api-key\"}}"),
            Fornitore.ANTHROPIC,
            KEY);

    assertThat(text)
        .startsWith(StrategistPanel.TEST_KO + " chiave rifiutata da Haiku")
        .doesNotContain(KEY);
  }

  @Test
  void theKeyFileIsImportedAndTheKeyShownMaskedThenRemoved() throws IOException {
    Files.writeString(game.resolve(ProfiloStratega.FILE_DA_IMPORTARE), KEY + "\n");

    final StrategistPanel p = panel(answering(200, "{}"));

    assertThat(ProfiloStratega.carica().chiave(Fornitore.ANTHROPIC)).contains(KEY);
    assertThat(p.getSavedKey().getText()).isEqualTo("salvata: ••••1234");
    assertThat(p.getTestResult().getText()).contains("importata");
    p.remove();
    assertThat(ProfiloStratega.carica().chiaveSalvata(Fornitore.ANTHROPIC)).isEmpty();
    assertThat(p.getSavedKey().getText()).isEqualTo("nessuna chiave salvata");
  }

  @Test
  void aTypedKeyIsSavedForItsProvider() {
    final StrategistPanel p = panel(answering(200, "{}"));
    p.getKeyField().setText("gsk_nuova5678");

    p.save();

    assertThat(ProfiloStratega.carica().chiave(Fornitore.GROQ)).contains("gsk_nuova5678");
    assertThat(p.getProvider().getSelectedItem()).isEqualTo(Fornitore.GROQ);
    assertThat(new String(p.getKeyField().getPassword())).isEmpty();
  }

  @Test
  void theDetectedStateSaysWhatWillBeUsed() {
    final ProfiloStratega none = ProfiloStratega.carica();
    final ClaudeCode.Stato missing = new ClaudeCode.Stato(null, false, "Claude Code non trovato");
    assertThat(
            StrategistPanel.detectedText(
                new ClaudeCode.Stato(Path.of("claude"), true, ""), none, Fornitore.ANTHROPIC))
        .isEqualTo("✓ Claude Code trovato");
    assertThat(StrategistPanel.detectedText(missing, none, Fornitore.ANTHROPIC))
        .isEqualTo("✗ Claude non trovato, nessuna chiave – uso le regole");
    none.setChiave(Fornitore.ANTHROPIC, KEY);
    assertThat(StrategistPanel.detectedText(missing, none, Fornitore.ANTHROPIC))
        .isEqualTo("✗ Claude non trovato – uso la chiave Haiku (••••1234)");
  }

  // ------------------------------------------------- steps P11, PF1: two boxes, on by default

  @Test
  void theOnlyStrategyBoxesAreAlleatiConsigliatiAndAsseConsigliatoOnByDefault() {
    final StrategistPanel p = panel(answering(200, "{}"));

    assertThat(boxes(p.getPanel())).isEmpty();
    assertThat(boxes(p.getConsigliatiPanel()))
        .containsExactly(p.getAlleatiConsigliati(), p.getAsseConsigliato());
    assertThat(p.getAlleatiConsigliati().isSelected()).isTrue();
    assertThat(p.getAsseConsigliato().isSelected()).isTrue();
  }

  @Test
  void theBoxesSaveExactlyTheRecommendedSwitches() {
    final StrategistPanel p = panel(answering(200, "{}"));
    p.getAlleatiConsigliati().setSelected(true);
    p.getAsseConsigliato().setSelected(true);
    assertThat(p.apply(new GameProperties(null), Optional.empty())).isEmpty();

    final ProfiloStratega saved = ProfiloStratega.carica();
    assertThat(saved.usaProporzione()).isTrue();
    assertThat(saved.sbarcoConcentrato()).isTrue();
    assertThat(saved.nienteNeutrali()).isTrue();
    assertThat(saved.norvegiaPresto()).isTrue();
    assertThat(saved.norvegiaEsposta()).isTrue();
    assertThat(saved.ponteConcentrato()).isTrue();
    // step PF1: taranto is part of "Alleati consigliati"
    assertThat(saved.taranto()).isTrue();
    assertThat(saved.norvegiaScorta()).isFalse();
    assertThat(saved.ponteUk()).isFalse();
    assertThat(saved.impegno()).isFalse();
    assertThat(saved.assedio()).isTrue();
    assertThat(saved.italiaConvoglio()).isFalse();
    assertThat(saved.alleatiConsigliati()).isTrue();
    assertThat(saved.asseConsigliato()).isTrue();
  }

  @Test
  void theBoxesTurnedOffStayOffWhenThePanelIsOpenedAgain() {
    final StrategistPanel p = panel(answering(200, "{}"));
    p.getAlleatiConsigliati().setSelected(false);
    p.getAsseConsigliato().setSelected(false);
    p.apply(new GameProperties(null), Optional.empty());

    final StrategistPanel again = panel(answering(200, "{}"));
    assertThat(again.getAlleatiConsigliati().isSelected()).isFalse();
    assertThat(again.getAsseConsigliato().isSelected()).isFalse();
  }

  @Test
  void theBoxesTurnedOnStayOnWhenThePanelIsOpenedAgain() {
    final StrategistPanel p = panel(answering(200, "{}"));
    p.getAlleatiConsigliati().setSelected(true);
    p.getAsseConsigliato().setSelected(false);
    p.apply(new GameProperties(null), Optional.empty());

    final StrategistPanel again = panel(answering(200, "{}"));
    assertThat(again.getAlleatiConsigliati().isSelected()).isTrue();
    assertThat(again.getAsseConsigliato().isSelected()).isFalse();
  }

  @Test
  void anOldProfileShowsBothBoxesOn() throws IOException {
    Files.writeString(
        profile.resolve("stratega.properties"),
"""
pannello=p12
usa_proporzione=si
assedio=no
italia_convoglio=si
""");

    final StrategistPanel p = panel(answering(200, "{}"));
    assertThat(p.getAlleatiConsigliati().isSelected()).isTrue();
    assertThat(p.getAsseConsigliato().isSelected()).isTrue();
    p.apply(new GameProperties(null), Optional.empty());

    final ProfiloStratega saved = ProfiloStratega.carica();
    assertThat(saved.ponteConcentrato()).isTrue();
    assertThat(saved.taranto()).isTrue();
    assertThat(saved.assedio()).isTrue();
    assertThat(saved.italiaConvoglio()).isFalse();
  }

  @Test
  void aProfileThatCannotBeSavedIsReported() throws IOException {
    // the profile "folder" is a file: saving fails
    final Path blocked = profile.resolve("bloccata");
    Files.writeString(blocked, "x");
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, blocked.toString());
    final StrategistPanel p = panel(answering(200, "{}"));

    assertThat(p.apply(new GameProperties(null), Optional.empty()))
        .hasValueSatisfying(text -> assertThat(text).startsWith("Impostazioni Pro+ non salvate"));
  }

  private static List<JCheckBox> boxes(final Container c) {
    final List<JCheckBox> found = new ArrayList<>();
    for (final Component child : c.getComponents()) {
      if (child instanceof JCheckBox box) {
        found.add(box);
      } else if (child instanceof Container inner) {
        found.addAll(boxes(inner));
      }
    }
    return found;
  }
}
