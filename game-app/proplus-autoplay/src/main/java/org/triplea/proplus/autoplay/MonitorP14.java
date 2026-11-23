package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.history.Event;
import games.strategy.engine.history.Round;
import games.strategy.engine.history.Step;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;

/**
 * Step P14: the air units the engine removed at the end of a turn because they could not land
 * ({@code AirThatCantLandUtil}: "X could not land in Y and was removed"), with round and nation,
 * from the game's history (every configuration).
 */
final class MonitorP14 {
  private MonitorP14() {}

  static final String TESTO = " could not land in ";

  private static final Pattern NUMERO = Pattern.compile("(^|, | and )(\\d+) ");

  /** One row per history event: round, nation, units (count), text. */
  static JsonArray nonAtterrati(final GameData data) {
    final JsonArray out = new JsonArray();
    try {
      try (GameData.Unlocker ignored = data.acquireReadLock()) {
        final Object root = data.getHistory().getRoot();
        if (root instanceof DefaultMutableTreeNode node) {
          visit(node, 0, "", out);
        }
      }
    } catch (final RuntimeException e) {
      final JsonObject err = new JsonObject();
      err.addProperty("errore", e.toString());
      out.add(err);
    }
    return out;
  }

  private static void visit(
      final TreeNode n, final int round, final String nation, final JsonArray out) {
    int r = round;
    String p = nation;
    if (n instanceof Round rn) {
      r = rn.getRoundNo();
    } else if (n instanceof Step st) {
      p = st.getPlayerId().map(x -> x.getName()).orElse(nation);
    } else if (n instanceof Event e && e.getDescription().contains(TESTO)) {
      final JsonObject o = new JsonObject();
      o.addProperty("round", r);
      o.addProperty("nazione", p);
      o.addProperty("unita", unita(e.getDescription()));
      o.addProperty("testo", e.getDescription());
      out.add(o);
    }
    for (int i = 0; i < n.getChildCount(); i++) {
      visit(n.getChildAt(i), r, p, out);
    }
  }

  /** The number of units in "2 fighters and 1 bomber could not land ..." (1 per unnumbered). */
  static int unita(final String text) {
    final String units = text.substring(0, Math.max(0, text.indexOf(TESTO)));
    final Matcher m = NUMERO.matcher(units);
    int n = 0;
    int groups = 0;
    while (m.find()) {
      n += Integer.parseInt(m.group(2));
      groups++;
    }
    return groups == 0 ? 1 : n;
  }
}
