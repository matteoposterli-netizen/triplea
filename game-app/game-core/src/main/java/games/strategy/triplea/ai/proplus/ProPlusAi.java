package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.logging.ProLogUi;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;

/**
 * "Pro+ (AI)": the fork's own AI, built on top of {@link ProAi} ("Hard (AI)").
 *
 * <p>For now it plays exactly like {@link ProAi}: each overridden phase only writes a "[Pro+]" line
 * to the AI log and then delegates to {@link ProAi}. Pro+ specific tuning goes here, never in the
 * Pro classes, so that "Hard (AI)" keeps its behavior.
 */
public class ProPlusAi extends ProAi {
  public static final String PLAYER_LABEL = "Pro+ (AI)";
  public static final String LOG_PREFIX = "[Pro+] ";

  public ProPlusAi(final String name, final String playerLabel) {
    super(name, playerLabel, false);
  }

  @Override
  protected void purchase(
      final boolean purchaseForBid,
      final int pusToSpend,
      final IPurchaseDelegate purchaseDelegate,
      final GameData data,
      final GamePlayer player) {
    logPhase(data, player, purchaseForBid ? "bid purchase" : "purchase");
    super.purchase(purchaseForBid, pusToSpend, purchaseDelegate, data, player);
  }

  @Override
  protected void move(
      final boolean nonCombat,
      final IMoveDelegate moveDel,
      final GameData data,
      final GamePlayer player) {
    logPhase(data, player, nonCombat ? "non-combat move" : "combat move");
    super.move(nonCombat, moveDel, data, player);
  }

  @Override
  protected void place(
      final boolean placeForBid,
      final IAbstractPlaceDelegate placeDelegate,
      final GameState data,
      final GamePlayer player) {
    logPhase(data, player, placeForBid ? "bid place" : "place");
    super.place(placeForBid, placeDelegate, data, player);
  }

  /**
   * Marks the start of a phase in the AI log. The round header is opened first (as ProAi does), so
   * the marker shows under the right player and round.
   */
  private static void logPhase(final GameState data, final GamePlayer player, final String phase) {
    ProLogUi.notifyStartOfRound(data.getSequence().getRound(), player.getName());
    ProLogger.info(LOG_PREFIX + player.getName() + " " + phase);
  }
}
