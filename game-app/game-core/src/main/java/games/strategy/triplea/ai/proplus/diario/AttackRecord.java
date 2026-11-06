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

  record UnitInfo(UUID id, String type, int tuv) {}

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
