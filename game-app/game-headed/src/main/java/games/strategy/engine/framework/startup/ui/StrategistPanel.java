package games.strategy.engine.framework.startup.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.proplus.strategia.llm.ClaudeCode;
import games.strategy.triplea.ai.proplus.strategia.llm.ClientApi;
import games.strategy.triplea.ai.proplus.strategia.llm.ErroreStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Fornitore;
import games.strategy.triplea.ai.proplus.strategia.llm.Http;
import games.strategy.triplea.ai.proplus.strategia.llm.Modalita;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Richiesta;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import games.strategy.triplea.ai.proplus.strategia.llm.UscitaLlm;
import games.strategy.triplea.ai.proplus.strategia.llm.Uso;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import lombok.Getter;

/**
 * The "Stratega" section of the Pro+ settings: what was found on this PC (Claude Code, a key), the
 * provider, the API key (masked: only the last 4 characters are ever shown), Salva / Prova
 * connessione / Rimuovi, the cap of calls per game. The key is saved only in the user profile
 * ({@link ProfiloStratega}). A {@value ProfiloStratega#FILE_DA_IMPORTARE} next to the game is
 * imported when the panel opens, then the user is asked whether to delete it.
 */
final class StrategistPanel {
  static final String TEST_OK = "✓";
  static final String TEST_KO = "✗";

  @Getter private final JPanel panel = new JPanel();
  @Getter private final JComboBox<Fornitore> provider = new JComboBox<>(Fornitore.values());
  @Getter private final JPasswordField keyField = new JPasswordField(24);
  @Getter private final JLabel savedKey = new JLabel();
  @Getter private final JLabel detected = new JLabel("Verifica in corso...");
  @Getter private final JLabel testResult = new JLabel(" ");
  @Getter private final JSpinner cap;

  /** Step STRATEGA LLM: cap in USD per game of the "Pro+ Stratega LLM" player (user profile). */
  @Getter private final JSpinner usdCap;

  /** Step R2b: the commitment ("Impegno") of the "Pro+ Stratega LLM" player (user profile). */
  @Getter private final javax.swing.JCheckBox impegno = new javax.swing.JCheckBox();

  private final ProfiloStratega profile;
  private final Function<Fornitore, ClientApi> clients;
  private final Supplier<ClaudeCode.Stato> claude;

  StrategistPanel(final GameProperties props) {
    this(
        props,
        ProfiloStratega.carica(),
        f -> new ClientApi(Http.REALE),
        ClaudeCode::stato,
        gameFolder());
  }

  private static Optional<Path> gameFolder() {
    try {
      return Optional.of(ClientFileSystemHelper.getRootFolder());
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
  }

  StrategistPanel(
      final GameProperties props,
      final ProfiloStratega profile,
      final Function<Fornitore, ClientApi> clients,
      final Supplier<ClaudeCode.Stato> claude,
      final Optional<Path> gameFolder) {
    this.profile = profile;
    this.clients = clients;
    this.claude = claude;
    gameFolder.ifPresent(this::importKeyFile);
    provider.setSelectedItem(profile.fornitore());
    cap = new JSpinner(new SpinnerNumberModel(Uso.tetto(props), 0, 500, 5));
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBorder(BorderFactory.createTitledBorder("Stratega (LLM)"));
    panel.add(row("Stato rilevato:", detected));
    panel.add(row("Fornitore:", provider));
    final JPanel keyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    keyRow.add(keyField);
    keyRow.add(savedKey);
    panel.add(row("Chiave API:", keyRow));
    final JButton save = new JButton("Salva");
    final JButton test = new JButton("Prova connessione");
    final JButton remove = new JButton("Rimuovi");
    save.addActionListener(e -> save());
    test.addActionListener(e -> test());
    remove.addActionListener(e -> remove());
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(save);
    buttons.add(test);
    buttons.add(remove);
    panel.add(row("", buttons));
    panel.add(row("", testResult));
    final JPanel capRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    capRow.add(cap);
    capRow.add(
        new JLabel(
            "<html>per partita (stratega: 1 per alleanza per round; ordini del turno: 1 per"
                + " nazione per turno)</html>"));
    panel.add(row("Tetto chiamate:", capRow));
    usdCap =
        new JSpinner(
            new SpinnerNumberModel(
                profile.tettoPartitaUsd(
                    games.strategy.triplea.ai.proplus.StrategaConfig.get().budgetDefaultUsd()),
                0.1,
                50.0,
                0.5));
    final JPanel usdRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    usdRow.add(usdCap);
    usdRow.add(
        new JLabel(
            "<html>$ per partita del giocatore \"Pro+ Stratega LLM (Europe 1940)\" (Claude Sonnet,"
                + " chiave Anthropic); raggiunto il tetto gioca come Hard</html>"));
    panel.add(row("Tetto Stratega LLM:", usdRow));
    impegno.setSelected(profile.impegno());
    impegno.setText(
        "<html>campagna via terra che AGISCE (step R2b, sperimentale): un obiettivo per nazione,"
            + " 70% dei PU, instradamento, attacchi forzati; vale per le partite nuove</html>");
    panel.add(row("Impegno:", impegno));
    panel.add(
        row(
            "",
            new JLabel(
                "<html>La chiave resta solo su questo PC (profilo utente), mai nel gioco, nei"
                    + " salvataggi o nel diario.<br>Claude Code funziona con un abbonamento"
                    + " Pro o Max (non con Claude gratuito). Errori o tempi lunghi:<br>quel"
                    + " round decidono le regole, la partita non si blocca.</html>")));
    provider.addActionListener(e -> refresh());
    refresh();
    detect();
  }

  /** Writes the cap in the game, the provider (and the preferred LLM mode) in the profile. */
  void apply(final GameProperties props, final Optional<Modalita> llmMode) {
    Uso.setTetto(props, ((Number) cap.getValue()).intValue());
    profile.setFornitore(selected());
    llmMode.ifPresent(profile::setModalitaPredefinita);
    profile.setTettoPartitaUsd(((Number) usdCap.getValue()).doubleValue());
    profile.setImpegno(impegno.isSelected());
    try {
      profile.salva();
    } catch (final IOException e) {
      // the game settings are saved anyway
    }
  }

  private Fornitore selected() {
    final Fornitore f = (Fornitore) provider.getSelectedItem();
    return f == null ? Fornitore.ANTHROPIC : f;
  }

  private void refresh() {
    savedKey.setText(
        profile
            .chiaveSalvata(selected())
            .map(k -> "salvata: " + ProfiloStratega.maschera(k))
            .orElse("nessuna chiave salvata"));
  }

  /** "Stato rilevato", in the background (the Claude Code check can take up to a minute). */
  private void detect() {
    new SwingWorker<String, Void>() {
      @Override
      protected String doInBackground() {
        return detectedText(claude.get(), profile, selected());
      }

      @Override
      protected void done() {
        try {
          detected.setText(get());
        } catch (final InterruptedException | ExecutionException e) {
          detected.setText("verifica non riuscita");
        }
      }
    }.execute();
  }

  static String detectedText(
      final ClaudeCode.Stato claudeCode, final ProfiloStratega profile, final Fornitore f) {
    if (claudeCode.pronto()) {
      return TEST_OK + " Claude Code trovato";
    }
    final Optional<String> key = profile.chiave(f);
    if (key.isPresent()) {
      return TEST_KO
          + " Claude non trovato – uso la chiave "
          + f.etichetta()
          + " ("
          + ProfiloStratega.maschera(key.get())
          + ")";
    }
    return TEST_KO + " Claude non trovato, nessuna chiave – uso le regole";
  }

  void save() {
    final String key = new String(keyField.getPassword()).strip();
    if (key.isEmpty()) {
      testResult.setText("Scrivi la chiave nel campo, poi Salva.");
      return;
    }
    final Fornitore f = Fornitore.daChiave(key).orElse(selected());
    profile.setChiave(f, key);
    profile.setFornitore(f);
    try {
      profile.salva();
      keyField.setText("");
      provider.setSelectedItem(f);
      testResult.setText(TEST_OK + " Chiave " + f.etichetta() + " salvata su questo PC.");
    } catch (final IOException e) {
      testResult.setText(TEST_KO + " Chiave non salvata: " + e.getMessage());
    }
    refresh();
    detect();
  }

  void remove() {
    profile.rimuoviChiave(selected());
    try {
      profile.salva();
      testResult.setText("Chiave " + selected().etichetta() + " rimossa da questo PC.");
    } catch (final IOException e) {
      testResult.setText(TEST_KO + " Chiave non rimossa: " + e.getMessage());
    }
    refresh();
    detect();
  }

  /** "Prova connessione": the key in the field, else the saved one; a tiny real call. */
  private void test() {
    final String typed = new String(keyField.getPassword()).strip();
    final Fornitore f = typed.isEmpty() ? selected() : Fornitore.daChiave(typed).orElse(selected());
    final Optional<String> key = typed.isEmpty() ? profile.chiave(f) : Optional.of(typed);
    if (key.isEmpty()) {
      testResult.setText(TEST_KO + " Nessuna chiave da provare per " + f.etichetta() + ".");
      return;
    }
    testResult.setText("Provo " + f.etichetta() + "...");
    new SwingWorker<String, Void>() {
      @Override
      protected String doInBackground() {
        return testText(clients.apply(f), f, key.get());
      }

      @Override
      protected void done() {
        try {
          testResult.setText(get());
        } catch (final InterruptedException | ExecutionException e) {
          testResult.setText(TEST_KO + " Prova non riuscita.");
        }
      }
    }.execute();
  }

  /** The outcome of a connection test, in clear words. */
  static String testText(final ClientApi client, final Fornitore f, final String key) {
    final JsonObject schema =
        JsonParser.parseString(
                "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
                    + "\"required\":[\"ok\"],\"additionalProperties\":false}")
            .getAsJsonObject();
    try {
      final Risposta r =
          client.chiama(
              f,
              key,
              new Richiesta(
                  "Sei un test di connessione. Rispondi solo in JSON.",
                  "Rispondi solo con il JSON {\"ok\": true}.",
                  schema,
                  1));
      UscitaLlm.leggi(r.testo());
      return String.format(
          Locale.ROOT,
          "%s Connessione riuscita: %s risponde (%.1f s).",
          TEST_OK,
          f.etichetta(),
          r.millis() / 1000.0);
    } catch (final ErroreStratega e) {
      return TEST_KO + " " + e.getMessage();
    }
  }

  private void importKeyFile(final Path folder) {
    try {
      final Optional<Fornitore> imported = profile.importaDa(folder);
      if (imported.isEmpty()) {
        return;
      }
      final Path file = folder.resolve(ProfiloStratega.FILE_DA_IMPORTARE);
      testResult.setText(
          TEST_OK + " Chiave " + imported.get().etichetta() + " importata dal file.");
      if (java.awt.GraphicsEnvironment.isHeadless()) {
        return;
      }
      SwingUtilities.invokeLater(
          () -> {
            final int answer =
                JOptionPane.showConfirmDialog(
                    panel,
                    "Ho importato la chiave "
                        + imported.get().etichetta()
                        + " da "
                        + ProfiloStratega.FILE_DA_IMPORTARE
                        + ".\nOra e' salvata solo su questo PC."
                        + "\nCancello il file accanto al gioco? (consigliato)",
                    "Stratega Pro+",
                    JOptionPane.YES_NO_OPTION);
            if (answer == JOptionPane.YES_OPTION) {
              try {
                Files.deleteIfExists(file);
              } catch (final IOException e) {
                testResult.setText(TEST_KO + " Non riesco a cancellare il file: " + e.getMessage());
              }
            }
          });
    } catch (final IOException e) {
      testResult.setText(TEST_KO + " Chiave non importata: " + e.getMessage());
    }
  }

  private static JPanel row(final String label, final java.awt.Component c) {
    final JPanel row = new JPanel(new BorderLayout(6, 2));
    row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
    row.add(new JLabel(label), BorderLayout.WEST);
    row.add(c, BorderLayout.CENTER);
    return row;
  }
}
