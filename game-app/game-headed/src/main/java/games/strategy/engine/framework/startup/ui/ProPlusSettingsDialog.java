package games.strategy.engine.framework.startup.ui;

import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.diario.DiarySettings;
import games.strategy.triplea.ai.proplus.diario.Italian;
import games.strategy.triplea.ai.proplus.strategia.Decisore;
import games.strategy.triplea.ai.proplus.strategia.Decisori;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.ai.proplus.strategia.Strategia;
import games.strategy.triplea.ai.proplus.strategia.Strategie;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import lombok.Getter;

/**
 * "Impostazioni Pro+" dialog of the player selection screen: per alliance read from the map (see
 * {@link ProPlusSettings#configurableAlliances}) a "Propensione al rischio" slider and, when the
 * map has a strategy file, a "Piano" choice. OK writes them into the game's properties, so they are
 * saved with the game.
 *
 * <p>The dialog sizes itself to its content ({@code pack()}), is resizable, never taller than the
 * screen (the settings scroll, the OK/Annulla buttons stay visible), Enter = OK, Esc = Annulla.
 */
final class ProPlusSettingsDialog {
  static final String BUTTON_LABEL = "Impostazioni Pro+";
  static final String OPEN_DIARY_LABEL = "Apri cartella diario";

  private ProPlusSettingsDialog() {}

  static void show(final Component parent, final GameState data) {
    newDialog(parent, data).setVisible(true);
  }

  /** The dialog, packed and fitted to the screen, not shown yet. */
  static JDialog newDialog(final Component parent, final GameState data) {
    final Form form = new Form(data);
    final Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
    final JDialog dialog = new JDialog(owner, BUTTON_LABEL, JDialog.DEFAULT_MODALITY_TYPE);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    final JButton ok = new JButton("OK");
    ok.addActionListener(
        e -> {
          form.apply(data.getProperties());
          dialog.dispose();
        });
    final JButton cancel = new JButton("Annulla");
    cancel.addActionListener(e -> dialog.dispose());
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(ok);
    buttons.add(cancel);

    final JScrollPane scroll = new JScrollPane(new WidthTrackingPanel(form.getPanel()));
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    final JPanel content = new JPanel(new BorderLayout());
    content.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
    content.add(scroll, BorderLayout.CENTER);
    content.add(buttons, BorderLayout.SOUTH);
    dialog.setContentPane(content);
    dialog.getRootPane().setDefaultButton(ok);
    dialog
        .getRootPane()
        .registerKeyboardAction(
            e -> dialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.setResizable(true);
    dialog.pack();
    fitToScreen(dialog, owner);
    dialog.setLocationRelativeTo(owner);
    return dialog;
  }

  /** Shrinks the dialog to the usable screen area (without taskbar); the content then scrolls. */
  private static void fitToScreen(final JDialog dialog, final Window owner) {
    final GraphicsConfiguration gc =
        owner != null ? owner.getGraphicsConfiguration() : dialog.getGraphicsConfiguration();
    if (gc == null) {
      return;
    }
    final Rectangle bounds = gc.getBounds();
    final Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
    final int maxWidth = bounds.width - insets.left - insets.right - 20;
    final int maxHeight = bounds.height - insets.top - insets.bottom - 20;
    final Dimension size = dialog.getSize();
    // room for a vertical scroll bar when the height is cut
    final int width = Math.min(maxWidth, size.width + (size.height > maxHeight ? 24 : 0));
    dialog.setSize(width, Math.min(maxHeight, size.height));
  }

  /** Holds the settings in the scroll pane: follows the pane's width, scrolls only vertically. */
  private static final class WidthTrackingPanel extends JPanel implements Scrollable {
    private static final long serialVersionUID = 1L;

    WidthTrackingPanel(final JComponent content) {
      super(new BorderLayout());
      add(content, BorderLayout.CENTER);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(
        final Rectangle visibleRect, final int orientation, final int direction) {
      return 16;
    }

    @Override
    public int getScrollableBlockIncrement(
        final Rectangle visibleRect, final int orientation, final int direction) {
      return Math.max(16, visibleRect.height - 32);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
  }

  /** The dialog content: initialized from the game, written back by {@link #apply}. */
  static final class Form {
    private final Map<String, JSlider> sliders = new LinkedHashMap<>();
    private final Map<String, JComboBox<PlanChoice>> plans = new LinkedHashMap<>();
    private final Map<String, JComboBox<String>> deciders = new LinkedHashMap<>();
    private final Map<String, javax.swing.JTextField> deciderFiles = new LinkedHashMap<>();
    private final JCheckBox diaryCheckBox =
        new JCheckBox("Scrivi il diario delle decisioni dell'AI", DiarySettings.isEnabled());
    @Getter private final JPanel panel = new JPanel();

    Form(final GameState data) {
      final List<String> alliances = ProPlusSettings.configurableAlliances(data);
      final boolean lowLuck = Properties.getLowLuck(data.getProperties());
      final Optional<Strategia> strategia = Strategie.forGame(data);
      panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
      final JPanel alliancePanel = new JPanel();
      alliancePanel.setLayout(new BoxLayout(alliancePanel, BoxLayout.Y_AXIS));
      alliancePanel.setBorder(
          BorderFactory.createTitledBorder("Propensione al rischio e piano per alleanza"));
      for (final String alliance : alliances) {
        final JSlider slider =
            newRiskSlider(ProPlusSettings.getRiskLevel(data.getProperties(), alliance));
        sliders.put(alliance, slider);
        alliancePanel.add(sliderRow(alliance, slider, lowLuck));
        strategia
            .flatMap(s -> s.alleanza(alliance))
            .ifPresent(
                plansOfAlliance -> {
                  alliancePanel.add(planRow(data.getProperties(), alliance, plansOfAlliance));
                  alliancePanel.add(deciderRow(data.getProperties(), alliance));
                });
      }
      if (alliances.isEmpty()) {
        alliancePanel.add(new JLabel("Nessuna alleanza trovata nella mappa."));
      }
      if (strategia.isEmpty()) {
        alliancePanel.add(
            new JLabel("Nessun file di strategia per questa mappa: Pro+ gioca senza piano."));
      }
      panel.add(alliancePanel);
      panel.add(diaryPanel());
      panel.add(Box.createVerticalStrut(6));
      panel.add(
          new JLabel(
              "<html>Vale solo per i giocatori \"Pro+ (AI)\". Prudente = soglie di Hard."
                  + "<br>Rischio effettivo di un attacco = cursore + modificatore del piano per"
                  + " il fronte (-1, 0, +1)."
                  + "<br>Il piano si fissa al primo turno dell'alleanza; lo stato maggiore puo'"
                  + " cambiarlo (max 2 volte)."
                  + "<br>Decisore: \"regole\" = stato maggiore di Pro+; \"file\" = direttive"
                  + " JSON da un file o da una cartella<br>(direttive-R&lt;round&gt;-&lt;alleanza&gt;.json),"
                  + " se mancano decidono le regole."
                  + "<br>I valori sono salvati nella partita.</html>"));
    }

    Map<String, JSlider> getSliders() {
      return sliders;
    }

    Map<String, JComboBox<PlanChoice>> getPlans() {
      return plans;
    }

    Map<String, JComboBox<String>> getDeciders() {
      return deciders;
    }

    Map<String, javax.swing.JTextField> getDeciderFiles() {
      return deciderFiles;
    }

    /** Strategic contract: who decides for the alliance (rules or a directives file). */
    private JPanel deciderRow(final GameProperties props, final String alliance) {
      final JComboBox<String> combo = new JComboBox<>(Decisori.SCELTE.toArray(new String[0]));
      combo.setSelectedItem(Decisori.getScelta(props, alliance));
      final javax.swing.JTextField file =
          new javax.swing.JTextField(Decisori.getFile(props, alliance), 18);
      final JButton browse = new JButton("Sfoglia...");
      browse.addActionListener(
          e -> {
            final javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(file.getText());
            chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(panel) == javax.swing.JFileChooser.APPROVE_OPTION) {
              file.setText(chooser.getSelectedFile().getAbsolutePath());
              combo.setSelectedItem(Decisore.FILE);
            }
          });
      final Runnable refresh =
          () -> {
            final boolean byFile = Decisore.FILE.equals(combo.getSelectedItem());
            file.setEnabled(byFile);
            browse.setEnabled(byFile);
          };
      refresh.run();
      combo.addActionListener(e -> refresh.run());
      final JPanel row = new JPanel(new BorderLayout(6, 2));
      row.setBorder(BorderFactory.createEmptyBorder(0, 4, 10, 4));
      row.add(new JLabel("Decisore:"), BorderLayout.WEST);
      final JPanel right = new JPanel(new BorderLayout(4, 0));
      right.add(combo, BorderLayout.WEST);
      right.add(file, BorderLayout.CENTER);
      right.add(browse, BorderLayout.EAST);
      row.add(right, BorderLayout.CENTER);
      deciders.put(alliance, combo);
      deciderFiles.put(alliance, file);
      return row;
    }

    JCheckBox getDiaryCheckBox() {
      return diaryCheckBox;
    }

    private JPanel planRow(
        final GameProperties props, final String alliance, final Strategia.Alleanza alleanza) {
      final JComboBox<PlanChoice> combo = new JComboBox<>();
      final String auto = alleanza.pianoAutomatico().map(p -> " (= " + p.label() + ")").orElse("");
      combo.addItem(new PlanChoice(StatoMaggiore.AUTOMATICO, "Automatico" + auto, ""));
      for (final Strategia.Piano piano : alleanza.piani()) {
        combo.addItem(new PlanChoice(piano.id(), piano.etichettaPannello(), piano.descrizione()));
      }
      final Optional<StatoMaggiore.Stato> fixed = StatoMaggiore.getState(props, alliance);
      final String selected =
          fixed.map(StatoMaggiore.Stato::piano).orElse(StatoMaggiore.getChoice(props, alliance));
      for (int i = 0; i < combo.getItemCount(); i++) {
        if (combo.getItemAt(i).id().equals(selected)) {
          combo.setSelectedIndex(i);
        }
      }
      final JLabel description = new JLabel();
      final Runnable refresh =
          () -> {
            final PlanChoice choice = (PlanChoice) combo.getSelectedItem();
            description.setText(
                choice == null || choice.description().isEmpty()
                    ? " "
                    : "<html><i>" + choice.description() + "</i></html>");
          };
      refresh.run();
      combo.addActionListener(e -> refresh.run());
      final JPanel row = new JPanel(new BorderLayout(6, 2));
      row.setBorder(BorderFactory.createEmptyBorder(0, 4, 10, 4));
      row.add(new JLabel("Piano:"), BorderLayout.WEST);
      row.add(combo, BorderLayout.CENTER);
      if (fixed.isPresent()) {
        combo.setEnabled(false);
        row.add(
            new JLabel("<html>fissato al round " + fixed.get().roundInizio() + "</html>"),
            BorderLayout.EAST);
      } else {
        plans.put(alliance, combo);
      }
      row.add(description, BorderLayout.SOUTH);
      return row;
    }

    private JPanel diaryPanel() {
      final JPanel diary = new JPanel();
      diary.setLayout(new BoxLayout(diary, BoxLayout.Y_AXIS));
      diary.setBorder(BorderFactory.createTitledBorder("Diario delle decisioni"));
      diary.add(diaryCheckBox);
      final JButton open = new JButton(OPEN_DIARY_LABEL);
      open.addActionListener(e -> openDiaryFolder(panel));
      final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
      row.add(open);
      diary.add(row);
      diary.add(
          new JLabel(
              "<html>Per ogni turno di Pro+ e Hard: acquisti, attacchi fatti e scartati"
                  + " (con win% e motivo),<br>obiettivi ed esito reale delle battaglie."
                  + " Cartella \"diario\" accanto al gioco, ultime "
                  + DiarySettings.MAX_GAMES
                  + " partite.</html>"));
      return diary;
    }

    void apply(final GameProperties props) {
      DiarySettings.setEnabled(diaryCheckBox.isSelected());
      sliders.forEach(
          (alliance, slider) ->
              ProPlusSettings.setRiskLevel(
                  props,
                  alliance,
                  RiskLevel.fromLevel(slider.getValue()).orElse(RiskLevel.DEFAULT)));
      plans.forEach(
          (alliance, combo) -> {
            final PlanChoice choice = (PlanChoice) combo.getSelectedItem();
            StatoMaggiore.setChoice(
                props, alliance, choice == null ? StatoMaggiore.AUTOMATICO : choice.id());
          });
      deciders.forEach(
          (alliance, combo) ->
              Decisori.set(
                  props,
                  alliance,
                  String.valueOf(combo.getSelectedItem()),
                  deciderFiles.get(alliance).getText().strip()));
    }
  }

  /** An item of the "Piano" choice: a plan id or {@link StatoMaggiore#AUTOMATICO}. */
  record PlanChoice(String id, String label, String description) {
    @Override
    public String toString() {
      return label;
    }
  }

  private static void openDiaryFolder(final Component parent) {
    final Optional<Path> folder = DiarySettings.folder();
    try {
      if (folder.isEmpty()) {
        throw new IOException("cartella non trovata");
      }
      Files.createDirectories(folder.get());
      Desktop.getDesktop().open(folder.get().toFile());
    } catch (final IOException | RuntimeException e) {
      JOptionPane.showMessageDialog(
          parent,
          "Non riesco ad aprire la cartella del diario"
              + folder.map(f -> ":\n" + f).orElse("")
              + "\n("
              + e.getMessage()
              + ")",
          OPEN_DIARY_LABEL,
          JOptionPane.WARNING_MESSAGE);
    }
  }

  private static JSlider newRiskSlider(final RiskLevel current) {
    final RiskLevel[] levels = RiskLevel.values();
    final JSlider slider =
        new JSlider(levels[0].getLevel(), levels[levels.length - 1].getLevel(), current.getLevel());
    final Hashtable<Integer, JComponent> labels = new Hashtable<>();
    for (final RiskLevel level : levels) {
      labels.put(level.getLevel(), new JLabel(level.getLabel()));
    }
    slider.setLabelTable(labels);
    slider.setPaintLabels(true);
    slider.setMajorTickSpacing(1);
    slider.setPaintTicks(true);
    slider.setSnapToTicks(true);
    return slider;
  }

  private static JPanel sliderRow(
      final String alliance, final JSlider slider, final boolean lowLuck) {
    final JLabel title = new JLabel();
    title.setFont(title.getFont().deriveFont(Font.BOLD));
    final Runnable refresh =
        () -> {
          final RiskLevel level = RiskLevel.fromLevel(slider.getValue()).orElse(RiskLevel.DEFAULT);
          title.setText(
              String.format(
                  "%s: %s  (base: desiderata %.0f%%, minima %.0f%%%s)",
                  Italian.alliance(alliance),
                  level.getLabel(),
                  level.desiredWinPercentage(lowLuck),
                  level.minimumWinPercentage(lowLuck),
                  lowLuck ? ", Low Luck" : ""));
        };
    refresh.run();
    slider.addChangeListener(e -> refresh.run());
    final JPanel row = new JPanel(new BorderLayout());
    row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    row.add(title, BorderLayout.NORTH);
    row.add(slider, BorderLayout.CENTER);
    return row;
  }
}
