package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.logging.ProLogUi;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.ai.proplus.strategia.OrdiniTurno;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.ai.proplus.strategia.Strategie;
import games.strategy.triplea.ai.proplus.strategia.Valutazione;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import java.util.List;
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

  /** Step 3d-2: the enemy transports hunted this turn (seen at the start of the turn). */
  private List<PianoAttivo.Caccia> hunt = List.of();

  private int huntRound = -1;

  public ProPlusAi(final String name, final String playerLabel) {
    // step PROVA DI VALORE: every answer of the tuning checked against Hard's (counted only)
    super(name, playerLabel, new ProData(false, ControlloTuning.wrap(new ProPlusTuning())));
    if (ProPlusTuning.unwrap(getProData().getTuning()) instanceof ProPlusTuning tuning) {
      tuning.attach(getProData());
    }
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
    final boolean plan = Ablazione.attivo(data, player, Ablazione.Componente.PIANO);
    if (!purchaseForBid && plan) {
      // step 3d-2: the enemy transports hunted this turn, seen before any move
      try {
        huntRound = data.getSequence().getRound();
        hunt = PianoAttivo.of(data, player).map(PianoAttivo::getCaccia).orElse(List.of());
      } catch (final RuntimeException e) {
        hunt = List.of();
        ProLogger.warn(LOG_PREFIX + "Transport hunt check failed: " + e);
      }
    }
    if (!purchaseForBid
        && plan
        && ProPlusTuning.unwrap(getProData().getTuning()) instanceof ProPlusTuning tuning) {
      try {
        initializeData();
        tuning.prepareOperationPurchases(getProData());
      } catch (final RuntimeException e) {
        ProLogger.warn(LOG_PREFIX + "Amphibious operations purchase check failed: " + e);
      }
    }
    if (!purchaseForBid) {
      // step PROVA DI VALORE: the joint operation's start, state and outcome (switch off: nothing)
      try {
        initializeData();
        report(data, OperazioniCongiunte.aggiorna(getProData(), getCalc()));
      } catch (final RuntimeException e) {
        ProLogger.warn(LOG_PREFIX + "Joint operation update failed: " + e);
      }
    }
    super.purchase(purchaseForBid, pusToSpend, purchaseDelegate, data, player);
    saveCheck(data, player);
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
    playJointOperation(nonCombat, moveDel, data, player);
    super.move(nonCombat, moveDel, data, player);
    saveCheck(data, player);
  }

  /**
   * Step PROVA DI VALORE: the hand-written joint operation moves first (steps in the combat move,
   * reserves and completion in the non-combat move); the Pro AI then leaves its units where they
   * are. Nothing with the operation's switch off.
   */
  private void playJointOperation(
      final boolean nonCombat,
      final IMoveDelegate moveDel,
      final GameData data,
      final GamePlayer player) {
    if (!(ProPlusTuning.unwrap(getProData().getTuning()) instanceof ProPlusTuning tuning)
        || OperazioneCongiunta.per(data, player).isEmpty()) {
      return;
    }
    try {
      initializeData();
      prepareData(data);
      final OperazioniCongiunte.Esito result =
          nonCombat
              ? OperazioniCongiunte.nonCombattimento(getProData(), getCalc(), moveDel)
              : OperazioniCongiunte.combattimento(getProData(), getCalc(), moveDel);
      if (nonCombat) {
        tuning.holdForOperation(data, player, result.tenute());
      } else {
        tuning.holdCombat(data, player, result.tenute());
      }
      report(data, result);
    } catch (final RuntimeException e) {
      ProLogger.warn(LOG_PREFIX + "Joint operation failed: " + e);
    }
  }

  /** The joint operation's lines and events in the diary (only while the diary is on). */
  private static void report(final GameData data, final OperazioniCongiunte.Esito result) {
    if (!result.righe().isEmpty()) {
      DiaryRecorder.jointOperation(data, result.righe(), result.eventi());
    }
  }

  /**
   * Step PROVA DI VALORE: the counts of the check against Hard saved in the game, in the games
   * where they mean something (Pro+ nudo, with or without a joint operation).
   */
  private void saveCheck(final GameState data, final GamePlayer player) {
    final ControlloTuning check = ControlloTuning.of(getProData().getTuning());
    if (check == null) {
      return;
    }
    final var alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()
        || !Ablazione.spenti(data.getProperties(), alliance.get())
            .containsAll(java.util.EnumSet.allOf(Ablazione.Componente.class))) {
      return;
    }
    try {
      check.salva(data.getProperties(), player.getName());
    } catch (final RuntimeException e) {
      ProLogger.warn(LOG_PREFIX + "Check against Hard not saved: " + e);
    }
  }

  /**
   * Step OPERAZIONI ANFIBIE: the plan's amphibious operations move first (load, transit, shuttle);
   * the Pro AI's non-combat move then leaves the units they hold where they are.
   */
  private void playAmphibiousOperations(
      final IMoveDelegate moveDel, final GameData data, final GamePlayer player) {
    if (!(ProPlusTuning.unwrap(getProData().getTuning()) instanceof ProPlusTuning tuning)) {
      return;
    }
    final var plan = PianoAttivo.of(data, player);
    final boolean operations = plan.filter(p -> !p.getOperazioni().isEmpty()).isPresent();
    // step 3d-1: troops stuck aboard go back ashore (every plan with counter-moves, not N)
    final boolean bringBack = plan.filter(p -> p.getPiano().contromosse()).isPresent();
    // step 3d-2: the delay tools whose switch is on (never with plan N)
    final boolean delay = bringBack && hasDelayTools(plan.get(), data, player);
    if (!operations && !bringBack) {
      tuning.hold(data, player, java.util.Set.of());
      return;
    }
    try {
      initializeData();
      prepareData(data);
      final java.util.Set<games.strategy.engine.data.Unit> held = new java.util.HashSet<>();
      final List<OperazioniAnfibie.Rapporto> reports = new java.util.ArrayList<>();
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
      Runnable delayDiary = () -> {};
      if (delay) {
        try {
          if (!held.isEmpty()) {
            // the operations moved: the tools see the board after the moves
            initializeData();
            prepareData(data);
          }
          delayDiary = playDelayTools(moveDel, data, player, plan.get(), held);
        } catch (final RuntimeException e) {
          ProLogger.warn(LOG_PREFIX + "Delay tools failed: " + e);
        }
      }
      tuning.hold(data, player, held);
      if (getProData().getRecorder() instanceof DiaryRecorder diary && !reports.isEmpty()) {
        diary.amphibiousOperations(
            getProData(),
            reports,
            tuning.operationPurchases(player, data.getSequence().getRound()));
      }
      // after the operations' diary block, which starts the non-combat text
      delayDiary.run();
      saveReports(data, player, reports);
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

  /**
   * Strategic contract: the last report of each operation, shown to the decider in the next input
   * ({@code ultimo_rapporto}).
   */
  private static void saveReports(
      final GameData data,
      final GamePlayer player,
      final List<OperazioniAnfibie.Rapporto> reports) {
    final var alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return;
    }
    for (final OperazioniAnfibie.Rapporto r : reports) {
      try {
        final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("round", data.getSequence().getRound());
        o.addProperty("nazione", r.nazione());
        o.addProperty("stato", r.stato().testo());
        o.addProperty("trasporti", r.trasporti());
        o.addProperty("truppe_a_bordo", r.truppeABordo());
        o.addProperty("scorta", r.scorta());
        o.addProperty("sbarcate", r.sbarcate());
        r.bersaglio().ifPresent(b -> o.addProperty("bersaglio", b));
        if (r.bersaglio().isPresent()) {
          o.addProperty("win_bersaglio", Math.round(r.winStimata()));
        }
        r.valutazione()
            .ifPresent(
                v -> {
                  o.addProperty("testa_di_ponte", v.territorio());
                  o.addProperty("tenuta_stimata", v.tenuta());
                });
        r.accumulo()
            .ifPresent(
                a -> {
                  o.addProperty("win_accumulo", Math.round(a.win()));
                  o.addProperty("soglia_accumulo", Math.round(a.soglia()));
                });
        if (!r.motivo().isEmpty()) {
          o.addProperty(
              "motivo",
              r.motivo().length() > 160 ? r.motivo().substring(0, 160) + "..." : r.motivo());
        }
        games.strategy.triplea.ai.proplus.strategia.Ingresso.salvaRapporto(
            data.getProperties(), alliance.get(), r.operazione(), o);
      } catch (final RuntimeException e) {
        ProLogger.warn(LOG_PREFIX + "Operation report not saved: " + e);
      }
    }
  }

  /**
   * Whether a delay tool is on for the nation, or the nation has a record of the tools from an
   * earlier turn (its losses are counted and the record cleared).
   */
  private static boolean hasDelayTools(
      final PianoAttivo plan, final GameData data, final GamePlayer player) {
    return games.strategy.triplea.ai.proplus.strategia.Tattiche.STRUMENTI.stream()
            .anyMatch(t -> plan.tattica(t, (String) null).isPresent())
        || data.getProperties().get(StrumentiRitardo.PROPERTY_PREFIX + player.getName()) != null;
  }

  /**
   * Step 3d-2: blocks, fighting retreat and naval blocks in the real non-combat move, after the
   * amphibious operations ({@code held}: their units, to which the tools' units are added).
   */
  private Runnable playDelayTools(
      final IMoveDelegate moveDel,
      final GameData data,
      final GamePlayer player,
      final PianoAttivo plan,
      final java.util.Set<games.strategy.engine.data.Unit> held) {
    final StrumentiRitardo.Esito result =
        StrumentiRitardo.esegui(getProData(), getCalc(), moveDel, plan, held);
    held.addAll(result.tenute());
    final List<PianoAttivo.Caccia> hunted =
        huntRound == data.getSequence().getRound() ? hunt : List.of();
    final java.util.Set<String> onMap = new java.util.HashSet<>();
    data.getMap()
        .getTerritories()
        .forEach(t -> t.getUnits().forEach(u -> onMap.add(u.getId().toString())));
    final int sunk =
        (int)
            hunted.stream()
                .flatMap(c -> c.trasporti().stream())
                .filter(id -> !onMap.contains(id))
                .count();
    final StrumentiRitardo.Perdite lost = StrumentiRitardo.registra(data, player, result, hunted);
    result.azioni().forEach(a -> ProLogger.info(LOG_PREFIX + a.testo()));
    if (getProData().getRecorder() instanceof DiaryRecorder diary) {
      return () -> diary.delayTools(getProData(), result, hunted, sunk, lost);
    }
    return () -> {};
  }

  @Override
  protected void place(
      final boolean placeForBid,
      final IAbstractPlaceDelegate placeDelegate,
      final GameState data,
      final GamePlayer player) {
    logPhase(data, player, placeForBid ? "bid place" : "place");
    super.place(placeForBid, placeDelegate, data, player);
    saveCheck(data, player);
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
      // step ABLAZIONE: without the general staff, no plan, no evaluation, no orders
      if (!Ablazione.attivo(data, player, Ablazione.Componente.PIANO)) {
        ProLogger.info(turnStartLine(data, player) + ", ablation: no general staff");
        ProLogger.info(LOG_PREFIX + player.getName() + " " + phase);
        return;
      }
      fixPlan(data, player);
      if (ProPlusTuning.unwrap(getProData().getTuning()) instanceof ProPlusTuning tuning) {
        tuning.forgetPlan();
      }
      // step 3c, observation only: the alliance's general staff at the start of the round
      try {
        Valutazione.inizioRound(data, player).ifPresent(r -> DiaryRecorder.allianceRound(data, r));
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] General staff check failed: " + e);
      }
      // LLM step: the turn orders of this nation (asked only with an LLM strategist)
      try {
        OrdiniTurno.inizioTurno(data, player).ifPresent(r -> DiaryRecorder.nationOrders(data, r));
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] Turn orders failed: " + e);
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
