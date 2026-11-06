package games.strategy.engine.framework.startup.ui;

import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import lombok.Getter;

/**
 * "Impostazioni Pro+" dialog of the player selection screen: one "Propensione al rischio" slider
 * per alliance read from the map (see {@link ProPlusSettings#configurableAlliances}). OK writes the
 * levels into the game's properties, so they are saved with the game.
 */
final class ProPlusSettingsDialog {
  static final String BUTTON_LABEL = "Impostazioni Pro+";

  private ProPlusSettingsDialog() {}

  static void show(final Component parent, final GameState data) {
    final Form form = new Form(data);
    final int choice =
        JOptionPane.showConfirmDialog(
            parent,
            form.getPanel(),
            BUTTON_LABEL,
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    if (choice == JOptionPane.OK_OPTION) {
      form.apply(data.getProperties());
    }
  }

  /** The dialog content: sliders initialized from the game, written back by {@link #apply}. */
  static final class Form {
    private final Map<String, JSlider> sliders = new LinkedHashMap<>();
    @Getter private final JPanel panel = new JPanel();

    Form(final GameState data) {
      final List<String> alliances = ProPlusSettings.configurableAlliances(data);
      final boolean lowLuck = Properties.getLowLuck(data.getProperties());
      panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
      final JPanel riskPanel = new JPanel();
      riskPanel.setLayout(new BoxLayout(riskPanel, BoxLayout.Y_AXIS));
      riskPanel.setBorder(BorderFactory.createTitledBorder("Propensione al rischio"));
      for (final String alliance : alliances) {
        final JSlider slider =
            newRiskSlider(ProPlusSettings.getRiskLevel(data.getProperties(), alliance));
        sliders.put(alliance, slider);
        riskPanel.add(sliderRow(alliance, slider, lowLuck));
      }
      if (alliances.isEmpty()) {
        riskPanel.add(new JLabel("Nessuna alleanza trovata nella mappa."));
      }
      panel.add(riskPanel);
      // Step 3 ("Fantasia"): its slider goes in this panel; hidden until then.
      final JPanel fantasyPanel = new JPanel();
      fantasyPanel.setVisible(false);
      panel.add(fantasyPanel);
      panel.add(Box.createVerticalStrut(6));
      panel.add(
          new JLabel(
              "<html>Vale solo per i giocatori \"Pro+ (AI)\". Prudente = soglie di Hard."
                  + "<br>I valori sono salvati nella partita.</html>"));
    }

    Map<String, JSlider> getSliders() {
      return sliders;
    }

    void apply(final GameProperties props) {
      sliders.forEach(
          (alliance, slider) ->
              ProPlusSettings.setRiskLevel(
                  props,
                  alliance,
                  RiskLevel.fromLevel(slider.getValue()).orElse(RiskLevel.DEFAULT)));
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
                  "%s: %s  (attacco: desiderata %.0f%%, minima %.0f%%%s)",
                  alliance,
                  level.getLabel(),
                  level.desiredWinPercentage(lowLuck),
                  level.minimumWinPercentage(lowLuck),
                  lowLuck ? ", Low Luck" : ""));
        };
    refresh.run();
    slider.addChangeListener(e -> refresh.run());
    final JPanel row = new JPanel(new BorderLayout());
    row.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
    row.add(title, BorderLayout.NORTH);
    row.add(slider, BorderLayout.CENTER);
    return row;
  }
}
