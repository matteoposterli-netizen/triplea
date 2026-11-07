package games.strategy.triplea.ai.proplus.diario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.annotation.Nullable;

/** What the diary knows about one attack the AI considered this turn (only copies, no AI data). */
final class AttackRecord {
  final String territory;
  String owner = "";
  boolean neutral;
  boolean water;
  boolean amphib;
  boolean strafing;
  boolean canHold;

  /** Win% with every unit that could attack. */
  double maxWin = Double.NaN;

  /** Win% with the units the AI assigned (last estimate), NaN if never estimated. */
  double win = Double.NaN;

  double tuvSwing = Double.NaN;

  /** The AI's ranking score of the target (Pro AI "attack value"), NaN before ranking. */
  double priority = Double.NaN;

  /** Part of {@link #priority} from victory cities (0 unless the AI's tuning weighs them). */
  double priorityVictoryCity;

  /** Part of {@link #priority} from the strategy plan's objectives (0 without a plan). */
  double priorityPlan;

  /** Pro+ with a strategy plan: front of the target, null otherwise. */
  @Nullable String front;

  /** Pro+: risk level of the attack (with a plan: the front's effective level), null for Hard. */
  @Nullable String risk;

  /** Pro+ with a strategy plan: whether the target is an objective of the plan's phase. */
  boolean planObjective;

  double value;
  double valueProduction;
  double valueVictoryCity;
  double valueNeutral;
  double desired;
  double minimum;
  Map<String, Integer> maxAttackers = new TreeMap<>();
  Map<String, Integer> attackers = new TreeMap<>();
  Map<String, Integer> defenders = new TreeMap<>();

  /** Reason code why the attack was dropped; null while it is still planned. */
  @Nullable String dropReason;

  boolean readded;
  boolean planned;
  @Nullable Boolean executed;
  int bombers;

  /** Units in the battle when the combat move ended, for the real outcome. */
  final List<UnitInfo> attackerUnits = new ArrayList<>();

  final List<UnitInfo> defenderUnits = new ArrayList<>();
  @Nullable String outcome;

  /** Late check that took every unit off the attack (reason code), null if none. */
  @Nullable String clearedReason;

  /** For "transports_exposed": TUV the enemy wins against the transports, and the attack value. */
  double clearedEnemyTuvSwing = Double.NaN;

  double clearedAttackValue = Double.NaN;

  /** Last "worth it with the units assigned" check of the combat move; null if never reached. */
  @Nullable WorthInfo worth;

  record UnitInfo(UUID id, String type, int tuv) {}

  /**
   * Copy of the combat move's last check (see {@code ProAttackWorth}).
   *
   * @param keptBy why the tuning kept an attack Hard drops (Pro+), null otherwise
   * @param proPlus the Pro+ rules' numbers when Hard would drop the attack, null for Hard
   */
  record WorthInfo(
      double win,
      boolean landUnitRemaining,
      boolean capturesTooRarely,
      boolean neutral,
      boolean canHold,
      double counterWin,
      double tuvSwing,
      double territoryValue,
      double counterTuv,
      double attackValue,
      boolean hardDrops,
      @Nullable String keptBy,
      @Nullable ProPlusInfo proPlus,
      @Nullable String testaDiPonte) {

    double expectedTuvCost() {
      return counterTuv - tuvSwing;
    }
  }

  /** The Pro+ rules for an attack Hard drops as not worth it (see {@code ProPlusTuning}). */
  record ProPlusInfo(
      String level,
      boolean levelKeeps,
      double counterWeight,
      double tolerance,
      double riskValue,
      double desired,
      boolean objectiveTarget,
      boolean planRule,
      double objectiveMinWin,
      double costLimit) {}

  AttackRecord(final String territory) {
    this.territory = territory;
  }

  boolean isDropped() {
    return dropReason != null;
  }

  double victoryCityShare() {
    return value > 0 ? valueVictoryCity / value : 0;
  }

  double priorityVictoryCityShare() {
    return priority > 0 ? priorityVictoryCity / priority : 0;
  }
}
