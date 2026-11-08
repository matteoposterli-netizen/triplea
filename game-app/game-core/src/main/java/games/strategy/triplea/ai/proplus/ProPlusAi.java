package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.logging.ProLogUi;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.ai.proplus.strategia.Strategie;
import games.strategy.triplea.ai.proplus.strategia.Valutazione;
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
    if (!purchaseForBid && getProData().getTuning() instanceof ProPlusTuning tuning) {
      try {
        initializeData();
        tuning.prepareOperationPurchases(getProData());
      } catch (final RuntimeException e) {
        ProLogger.warn(LOG_PREFIX + "Amphibious operations purchase check failed: " + e);
      }
    }
    super.purchase(purchaseForBid, pusToSpend, purchaseDelegate, data, player);
  }

  @Override
  protected void move(
      final boolean nonCombat,
      final IMoveDelegate moveDel,
      final GameData data,
      final GamePlayer player) {
    logPhase(data, player, nonCombat ? "non-combat move" : "combat move");
    if (nonCombat) {
      playAmphibiousOperations(moveDel, data, player);
    }
    super.move(nonCombat, moveDel, data, player);
  }

  /**
   * Step OPERAZIONI ANFIBIE: the plan's amphibious operations move first (load, transit, shuttle);
   * the Pro AI's non-combat move then leaves the units they hold where they are.
   */
  private void playAmphibiousOperations(
      final IMoveDelegate moveDel, final GameData data, final GamePlayer player) {
    if (!(getProData().getTuning() instanceof ProPlusTuning tuning)) {
      return;
    }
    final var plan = PianoAttivo.of(data, player);
    final boolean operations = plan.filter(p -> !p.getOperazioni().isEmpty()).isPresent();
    // step 3d-1: troops stuck aboard go back ashore (every plan with counter-moves, not N)
    final boolean bringBack = plan.filter(p -> p.getPiano().contromosse()).isPresent();
    if (!operations && !bringBack) {
      tuning.hold(data, player, java.util.Set.of());
      return;
    }
    try {
      initializeData();
      prepareData(data);
      final java.util.Set<games.strategy.engine.data.Unit> held = new java.util.HashSet<>();
      final java.util.List<OperazioniAnfibie.Rapporto> reports = new java.util.ArrayList<>();
      if (bringBack) {
        try {
          final OperazioniAnfibie.Esito back =
              OperazioniAnfibie.rientro(getProData(), getCalc(), moveDel, plan.get());
          held.addAll(back.tenute());
          reports.addAll(back.rapporti());
          if (!back.rapporti().isEmpty()) {
            // the units moved: the operations see the board after the moves
            initializeData();
            prepareData(data);
          }
        } catch (final RuntimeException e) {
          ProLogger.warn(LOG_PREFIX + "Troops aboard check failed: " + e);
        }
      }
      if (operations) {
        final OperazioniAnfibie.Esito result =
            OperazioniAnfibie.esegui(getProData(), getCalc(), moveDel, plan.get());
        held.addAll(result.tenute());
        reports.addAll(result.rapporti());
      }
      tuning.hold(data, player, held);
      if (getProData().getRecorder() instanceof DiaryRecorder diary && !reports.isEmpty()) {
        diary.amphibiousOperations(
            getProData(),
            reports,
            tuning.operationPurchases(player, data.getSequence().getRound()));
      }
      reports.forEach(
          r ->
              ProLogger.info(
                  LOG_PREFIX
                      + "Operation "
                      + r.operazione()
                      + ": "
                      + r.stato().testo()
                      + ", transports "
                      + r.trasporti()
                      + ", aboard "
                      + r.truppeABordo()
                      + ", escort "
                      + r.scorta()
                      + (r.motivo().isEmpty() ? "" : ", " + r.motivo())));
    } catch (final RuntimeException e) {
      ProLogger.warn(LOG_PREFIX + "Amphibious operations failed: " + e);
      tuning.hold(data, player, java.util.Set.of());
    }
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
      fixPlan(data, player);
      if (getProData().getTuning() instanceof ProPlusTuning tuning) {
        tuning.forgetPlan();
      }
      // step 3c, observation only: the alliance's general staff at the start of the round
      try {
        Valutazione.inizioRound(data, player).ifPresent(r -> DiaryRecorder.allianceRound(data, r));
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] General staff check failed: " + e);
      }
      ProLogger.info(turnStartLine(data, player));
    }
    ProLogger.info(LOG_PREFIX + player.getName() + " " + phase);
  }

  /**
   * Fixes the alliance's plan in the game the first time one of its Pro+ players plays (real game,
   * not a simulation copy: the phases get the real game data). It never changes afterwards.
   */
  static void fixPlan(final GameState data, final GamePlayer player) {
    ProPlusSettings.allianceOf(data, player)
        .ifPresent(
            alliance ->
                Strategie.forGame(data)
                    .ifPresent(strategia -> StatoMaggiore.fix(data, alliance, strategia)));
  }

  /**
   * Eg: "[Pro+] Germans round 3, alliance Axis, risk 3 Equilibrato, attack win% desired 80 /
   * minimum 60 (normal dice), plan A Barbarossa, fase R3+ (fixed in round 1), fronts Ovest 2 Cauto,
   * Est 4 Audace, ...".
   */
  static String turnStartLine(final GameState data, final GamePlayer player) {
    final RiskLevel risk = ProPlusSettings.riskLevelFor(data, player);
    final boolean lowLuck = ProPlusTuning.isLowLuck(data);
    return baseLine(data, player, risk, lowLuck)
        + PianoAttivo.of(data, player).map(ProPlusAi::planText).orElse(", no plan");
  }

  private static String planText(final PianoAttivo plan) {
    final StringBuilder sb = new StringBuilder(", plan ").append(plan.label());
    plan.getRoundInizio().ifPresent(r -> sb.append(" (fixed in round ").append(r).append(')'));
    sb.append(", fronts");
    plan.livelliPerFronte()
        .forEach(
            (front, level) ->
                sb.append(' ')
                    .append(front)
                    .append(' ')
                    .append(level.getLevel())
                    .append(' ')
                    .append(level.getLabel())
                    .append(','));
    sb.setLength(sb.length() - 1);
    if (!plan.getObiettivi().isEmpty()) {
      sb.append(", objectives ");
      sb.append(
          String.join(
              ", ", plan.getObiettivi().stream().map(o -> o.label() + " " + o.peso()).toList()));
    }
    return sb.toString();
  }

  private static String baseLine(
      final GameState data, final GamePlayer player, final RiskLevel risk, final boolean lowLuck) {
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
