package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.logging.ProLogUi;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import java.util.Locale;

/**
 * "Pro+ (AI)": the fork's own AI, built on top of {@link ProAi} ("Hard (AI)").
 *
 * <p>It plays the {@link ProAi} logic with its own numbers ({@link ProPlusTuning}): attack
 * thresholds from the alliance's risk level ({@link ProPlusSettings}) and victory cities valued.
 * Pro+ specific behavior goes here, never in the Pro classes, so that "Hard (AI)" keeps its
 * behavior. Each overridden phase writes a "[Pro+]" line to the AI log, the first one of a turn
 * also the risk level and thresholds in use.
 */
public class ProPlusAi extends ProAi {
  public static final String PLAYER_LABEL = "Pro+ (AI)";
  public static final String LOG_PREFIX = "[Pro+] ";

  /** Round of the last turn-start line, so it is written once per turn. */
  private int loggedRound = -1;

  public ProPlusAi(final String name, final String playerLabel) {
    super(name, playerLabel, new ProData(false, new ProPlusTuning()));
    DiaryRecorder.attach(this);
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
  private void logPhase(final GameState data, final GamePlayer player, final String phase) {
    final int round = data.getSequence().getRound();
    ProLogUi.notifyStartOfRound(round, player.getName());
    if (round != loggedRound) {
      loggedRound = round;
      ProLogger.info(turnStartLine(data, player));
    }
    ProLogger.info(LOG_PREFIX + player.getName() + " " + phase);
  }

  /**
   * Eg: "[Pro+] Germans round 3, alliance Axis, risk 3 Equilibrato, attack win% desired 80 /
   * minimum 60 (normal dice)".
   */
  static String turnStartLine(final GameState data, final GamePlayer player) {
    final RiskLevel risk = ProPlusSettings.riskLevelFor(data, player);
    final boolean lowLuck = ProPlusTuning.isLowLuck(data);
    return String.format(
        Locale.ROOT,
        "%s%s round %d, alliance %s, risk %d %s, attack win%% desired %.0f / minimum %.0f (%s)",
        LOG_PREFIX,
        player.getName(),
        data.getSequence().getRound(),
        ProPlusSettings.allianceOf(data, player).orElse("-"),
        risk.getLevel(),
        risk.getLabel(),
        risk.desiredWinPercentage(lowLuck),
        risk.minimumWinPercentage(lowLuck),
        lowLuck ? "Low Luck" : "normal dice");
  }
}
