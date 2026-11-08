package games.strategy.engine.framework.startup.ui;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.proplus.strategia.llm.ClaudeCode;
import games.strategy.triplea.ai.proplus.strategia.llm.ClientApi;
import games.strategy.triplea.ai.proplus.strategia.llm.Fornitore;
import games.strategy.triplea.ai.proplus.strategia.llm.Http;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
    assertThat(StrategistPanel.detectedText(new ClaudeCode.Stato(Path.of("claude"), true, ""), none, Fornitore.ANTHROPIC))
        .isEqualTo("✓ Claude Code trovato");
    assertThat(StrategistPanel.detectedText(missing, none, Fornitore.ANTHROPIC))
        .isEqualTo("✗ Claude non trovato, nessuna chiave – uso le regole");
    none.setChiave(Fornitore.ANTHROPIC, KEY);
    assertThat(StrategistPanel.detectedText(missing, none, Fornitore.ANTHROPIC))
        .isEqualTo("✗ Claude non trovato – uso la chiave Haiku (••••1234)");
  }
}
