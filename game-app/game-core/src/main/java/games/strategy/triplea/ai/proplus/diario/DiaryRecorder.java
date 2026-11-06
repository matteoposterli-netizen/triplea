package games.strategy.triplea.ai.proplus.diario;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProDecisionRecorder;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProPlaceTerritory;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle.BattleType;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.triplea.java.collections.IntegerMap;

/**
 * The Pro+ decision diary of one AI player ("Pro+ (AI)", "Hard (AI)", "Hard Beta (AI)").
 *
 * <p>It only observes: it copies names and numbers out of what the Pro AI hands it, reads the
 * board, and writes {@link GameDiary} files. It never changes AI data, never runs a battle
 * simulation and catches every error of its own (after the first one it stays silent for the rest
 * of the game), so it can't change or stop the game.
 *
 * <p>A turn: purchase (the AI plans its attacks in a simulation, then buys) -> combat move (the
 * plan is executed) -> battles -> non-combat move (real outcome of each battle) -> place.
 */
public final class DiaryRecorder implements ProDecisionRecorder {
  private static final int TOP_SHOWN = 5;
  private static final int TOP_COMPARED = 10;

  private final AbstractProAi ai;
  private boolean broken;
  private boolean active;
  private @Nullable GameDiary diary;
  private int round = -1;
  private String playerName = "";
  private String phase = "";
  private boolean turnOpen;
  private long diaryNanos;
  private final Map<String, Long> phaseMillis = new LinkedHashMap<>();

  // purchase
  private final Map<String, Integer> boughtSoFar = new TreeMap<>();
  private final List<String[]> purchaseSteps = new ArrayList<>();
  private Map<String, Integer> purchaseTotal = Map.of();
  private boolean purchaseWritten = true;
  private int pusAtStart;
  private int purchaseSpent;

  // combat plan
  private boolean planFrozen;
  private boolean ignorePlan;
  private boolean attacksWritten = true;
  private Map<String, AttackRecord> attacks = new LinkedHashMap<>();
  private Set<String> alive = new LinkedHashSet<>();
  private List<String> neutralsNotConsidered = List.of();
  private final List<AttackRecord> raids = new ArrayList<>();
  private final List<AttackRecord> pendingOutcomes = new ArrayList<>();

  private DiaryRecorder(final AbstractProAi ai) {
    this.ai = ai;
  }

  /** Gives {@code ai} a decision diary (written only while the diary is on). */
  public static void attach(final AbstractProAi ai) {
    ai.getProData().setRecorder(new DiaryRecorder(ai));
  }

  /** {@link #attach} that returns {@code ai}, for the player type factories. */
  public static <T extends AbstractProAi> T attachedTo(final T ai) {
    attach(ai);
    return ai;
  }

  // ---------------------------------------------------------------- phases (real game only)

  @Override
  public void phaseStart(final GameState data, final GamePlayer player, final String phase) {
    guard(
        () -> {
          this.phase = phase;
          if ("bid".equals(phase)) {
            return;
          }
          final int newRound = data.getSequence().getRound();
          if (!turnOpen || newRound != round || !player.getName().equals(playerName)) {
            closeTurn(data);
            openTurn(data, player, newRound);
          }
          if (!active) {
            return;
          }
          if ("combat".equals(phase)) {
            writePurchases();
          } else if ("noncombat".equals(phase) || "place".equals(phase)) {
            writePurchases();
            resolveOutcomes(data);
          }
        });
  }

  @Override
  public void phaseEnd(
      final GameState data, final GamePlayer player, final String phase, final long millis) {
    guard(
        () -> {
          if (!active || "bid".equals(phase)) {
            return;
          }
          phaseMillis.merge(phase, millis, Long::sum);
          switch (phase) {
            case "purchase" -> writePurchases();
            case "combat" -> {
              checkExecuted(data);
              writeAttacks(data);
            }
            case "place" -> closeTurn(data);
            default -> {
              // nothing else to write
            }
          }
        });
  }

  private void openTurn(final GameState data, final GamePlayer player, final int newRound) {
    round = newRound;
    playerName = player.getName();
    turnOpen = true;
    diary =
        DiarySettings.isEnabled() && data instanceof GameData gameData
            ? GameDiary.forGame(gameData).orElse(null)
            : null;
    active = diary != null;
    phaseMillis.clear();
    diaryNanos = 0;
    boughtSoFar.clear();
    purchaseSteps.clear();
    purchaseTotal = Map.of();
    purchaseSpent = 0;
    purchaseWritten = false;
    planFrozen = false;
    attacksWritten = false;
    attacks = new LinkedHashMap<>();
    alive = new LinkedHashSet<>();
    raids.clear();
    neutralsNotConsidered = List.of();
    if (!active) {
      return;
    }
    pusAtStart = player.isNull() ? 0 : player.getResources().getQuantity("PUs");
    final boolean lowLuck = Properties.getLowLuck(data.getProperties());
    final String alliance = ProPlusSettings.allianceOf(data, player).orElse("-");
    final double desired;
    final double minimum;
    final String risk;
    final JsonObject json = event("inizio_turno");
    if (ai.getProData().getTuning() instanceof ProPlusTuning) {
      final RiskLevel level = ProPlusSettings.riskLevelFor(data, player);
      desired = level.desiredWinPercentage(lowLuck);
      minimum = level.minimumWinPercentage(lowLuck);
      risk = "rischio " + level.getLabel();
      json.addProperty("rischio_livello", level.getLevel());
      json.addProperty("rischio", level.getLabel());
    } else {
      desired = ProTuning.DEFAULT.winPercentage(lowLuck);
      minimum = ProTuning.DEFAULT.minWinPercentage(lowLuck);
      risk = ai.getProData().isBeta() ? "Hard Beta" : "Hard";
    }
    json.addProperty("alleanza", alliance);
    json.addProperty("soglia_desiderata", desired);
    json.addProperty("soglia_minima", minimum);
    json.addProperty("low_luck", lowLuck);
    json.addProperty("pu", pusAtStart);
    diary.json(json);
    diary.text(
        String.format(
            Locale.ROOT,
            "%n================================================================%n"
                + "Round %d - %s (%s, %s, %s: soglie %s/%s%s) - %d PU%n",
            round,
            Italian.nation(playerName),
            Italian.alliance(alliance),
            ai.getPlayerLabel(),
            risk,
            Italian.number(desired),
            Italian.number(minimum),
            lowLuck ? ", Low Luck" : "",
            pusAtStart));
  }

  private void closeTurn(final GameState data) {
    if (!turnOpen) {
      return;
    }
    turnOpen = false;
    if (!active || diary == null) {
      return;
    }
    writePurchases();
    if (!attacksWritten && !attacks.isEmpty()) {
      writeAttacks(data);
    }
    resolveOutcomes(data);
    final StringBuilder sb = new StringBuilder("  Tempo AI:");
    final JsonObject json = event("fine_turno");
    phaseMillis.forEach(
        (p, ms) -> {
          sb.append(String.format(Locale.ROOT, " %s %.1f s,", phaseName(p), ms / 1000.0));
          json.addProperty("ms_" + p, ms);
        });
    final double diaryMs = diaryNanos / 1_000_000.0;
    sb.append(String.format(Locale.ROOT, " di cui diario %.0f ms%n", diaryMs));
    json.addProperty("ms_diario", Math.round(diaryMs));
    diary.json(json);
    diary.text(sb.toString());
  }

  private static String phaseName(final String phase) {
    return switch (phase) {
      case "purchase" -> "acquisti (con piano d'attacco)";
      case "combat" -> "combattimento";
      case "noncombat" -> "movimento";
      case "place" -> "piazzamento";
      default -> phase;
    };
  }

  // ---------------------------------------------------------------- purchase

  @Override
  public void purchaseStep(
      final ProData proData,
      final String step,
      final Map<Territory, ProPurchaseTerritory> purchaseTerritories,
      final Map<Territory, ProPurchaseTerritory> factoryTerritories) {
    guard(
        () -> {
          if (!active || !"purchase".equals(phase)) {
            return;
          }
          final Map<String, Integer> now = boughtUnits(purchaseTerritories, factoryTerritories);
          final Map<String, Integer> delta = new TreeMap<>();
          for (final String type : union(now.keySet(), boughtSoFar.keySet())) {
            final int d = now.getOrDefault(type, 0) - boughtSoFar.getOrDefault(type, 0);
            if (d != 0) {
              delta.put(type, d);
            }
          }
          boughtSoFar.clear();
          boughtSoFar.putAll(now);
          if ("done".equals(step) || "error".equals(step)) {
            purchaseTotal = new TreeMap<>(now);
            purchaseSpent = cost(proData, now);
            final JsonObject json = event("acquisti_totale");
            json.add("unita", counts(now));
            json.addProperty("pu_spesi", purchaseSpent);
            json.addProperty("pu_disponibili", pusAtStart);
            json.addProperty("esito", step);
            diary.json(json);
            if ("error".equals(step)) {
              purchaseSteps.add(new String[] {step, ""});
            }
            return;
          }
          if (delta.isEmpty()) {
            return;
          }
          purchaseSteps.add(new String[] {step, Italian.units(delta)});
          final JsonObject json = event("acquisto");
          json.addProperty("passo", step);
          json.addProperty("motivo", Italian.purchaseStep(step));
          json.add("unita", counts(delta));
          diary.json(json);
        });
  }

  private static Map<String, Integer> boughtUnits(
      final Map<Territory, ProPurchaseTerritory> purchaseTerritories,
      final Map<Territory, ProPurchaseTerritory> factoryTerritories) {
    final Set<ProPlaceTerritory> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<Unit> units = Collections.newSetFromMap(new IdentityHashMap<>());
    for (final Map<Territory, ProPurchaseTerritory> map :
        List.of(purchaseTerritories, factoryTerritories)) {
      for (final ProPurchaseTerritory ppt : map.values()) {
        for (final ProPlaceTerritory place : ppt.getCanPlaceTerritories()) {
          if (seen.add(place)) {
            units.addAll(place.getPlaceUnits());
          }
        }
      }
    }
    return countTypes(units);
  }

  private static int cost(final ProData proData, final Map<String, Integer> units) {
    if (proData.getPurchaseOptions() == null) {
      return 0;
    }
    final Map<String, Double> unitCost = new HashMap<>();
    proData
        .getPurchaseOptions()
        .getAllOptions()
        .forEach(
            o ->
                unitCost.putIfAbsent(
                    o.getUnitType().getName(),
                    o.getCost() / (double) Math.max(1, o.getQuantity())));
    return (int)
        Math.round(
            units.entrySet().stream()
                .mapToDouble(e -> unitCost.getOrDefault(e.getKey(), 0.0) * e.getValue())
                .sum());
  }

  private void writePurchases() {
    if (purchaseWritten || diary == null) {
      return;
    }
    purchaseWritten = true;
    final StringBuilder sb = new StringBuilder();
    if (purchaseSteps.isEmpty() && purchaseTotal.isEmpty()) {
      sb.append("  Acquisti: nessuno\n");
    } else {
      sb.append("  Acquisti: ").append(Italian.units(purchaseTotal));
      sb.append(" (spesi ")
          .append(purchaseSpent)
          .append(" di ")
          .append(pusAtStart)
          .append(" PU)\n");
      for (final String[] step : purchaseSteps) {
        sb.append("    - ").append(Italian.purchaseStep(step[0]));
        if (!step[1].isEmpty()) {
          sb.append(": ").append(step[1]);
        }
        sb.append('\n');
      }
    }
    diary.text(sb.toString());
  }

  // ---------------------------------------------------------------- combat plan

  @Override
  public void combatPlanStart(final ProData proData) {
    guard(
        () -> {
          ignorePlan =
              !active
                  || "bid".equals(phase)
                  || (planFrozen && "purchase".equals(phase))
                  || attacksWritten;
          if (ignorePlan) {
            return;
          }
          attacks = new LinkedHashMap<>();
          alive = new LinkedHashSet<>();
          raids.clear();
        });
  }

  @Override
  public void attackCandidates(
      final ProData proData,
      final Collection<ProTerritory> all,
      final Collection<ProTerritory> kept) {
    guard(
        () -> {
          if (ignorePlan) {
            return;
          }
          final Set<ProTerritory> keptSet = Collections.newSetFromMap(new IdentityHashMap<>());
          keptSet.addAll(kept);
          for (final ProTerritory patd : sortedByName(all)) {
            final AttackRecord rec = newRecord(proData, patd);
            if (keptSet.contains(patd)) {
              alive.add(rec.territory);
            } else {
              rec.dropReason =
                  rec.maxWin < rec.minimum || !patd.isStrafing()
                      ? "below_minimum"
                      : "strafe_not_enough";
            }
          }
        });
  }

  @Override
  public void attackPriority(
      final ProData proData, final ProTerritory patd, final double victoryCityPart) {
    guard(
        () -> {
          if (ignorePlan) {
            return;
          }
          final AttackRecord rec = record(proData, patd);
          update(rec, patd);
          rec.priority = patd.getValue();
          rec.priorityVictoryCity = victoryCityPart;
        });
  }

  @Override
  public void attackDropped(final ProData proData, final ProTerritory patd, final String reason) {
    guard(
        () -> {
          if (ignorePlan) {
            return;
          }
          final AttackRecord rec = record(proData, patd);
          update(rec, patd);
          if (alive.remove(rec.territory) || !rec.isDropped()) {
            rec.dropReason = reason;
          }
        });
  }

  @Override
  public void attackStage(
      final ProData proData, final String stage, final Collection<ProTerritory> planned) {
    guard(
        () -> {
          if (ignorePlan) {
            return;
          }
          final Set<String> names = new LinkedHashSet<>();
          for (final ProTerritory patd : planned) {
            final AttackRecord rec = record(proData, patd);
            update(rec, patd);
            if (rec.isDropped()) {
              rec.dropReason = null;
              rec.readded = true;
            }
            names.add(rec.territory);
          }
          for (final String name : alive) {
            if (!names.contains(name)) {
              attacks.get(name).dropReason = "stage_" + stage;
            }
          }
          alive = names;
        });
  }

  @Override
  public void attackPlan(final ProData proData, final Map<Territory, ProTerritory> attackMap) {
    guard(
        () -> {
          if (ignorePlan) {
            return;
          }
          final GamePlayer player = proData.getPlayer();
          for (final ProTerritory patd : sortedByName(attackMap.values())) {
            final Territory t = patd.getTerritory();
            final List<Unit> units = attackingUnits(patd);
            if (!patd.getBombers().isEmpty()) {
              final AttackRecord raid = new AttackRecord(t.getName());
              raid.owner = t.getOwner().getName();
              raid.bombers = patd.getBombers().size();
              raid.attackers.putAll(countTypes(patd.getBombers()));
              raids.add(raid);
            }
            if (units.isEmpty() || !isEnemyTarget(t, player)) {
              continue;
            }
            final AttackRecord rec = record(proData, patd);
            update(rec, patd);
            if (rec.isDropped()) {
              rec.dropReason = null;
              rec.readded = true;
            }
            rec.attackers.clear();
            rec.attackers.putAll(countTypes(units));
            rec.planned = true;
            alive.add(rec.territory);
          }
          for (final String name : alive) {
            final AttackRecord rec = attacks.get(name);
            if (!rec.planned) {
              rec.dropReason = "final_checks";
            }
          }
          alive.removeIf(name -> attacks.get(name).isDropped());
          neutralsNotConsidered = neutralsNotConsidered(proData);
          if ("purchase".equals(phase)) {
            planFrozen = true;
          }
        });
  }

  private static List<Unit> attackingUnits(final ProTerritory patd) {
    final List<Unit> units = new ArrayList<>(patd.getUnits());
    patd.getAmphibAttackMap()
        .forEach(
            (transport, cargo) -> {
              units.add(transport);
              units.addAll(cargo);
            });
    return units;
  }

  private static boolean isEnemyTarget(final Territory t, final GamePlayer player) {
    return t.isWater()
        ? t.anyUnitsMatch(Matches.enemyUnit(player))
        : Matches.isTerritoryEnemy(player).test(t);
  }

  private AttackRecord record(final ProData proData, final ProTerritory patd) {
    final AttackRecord rec = attacks.get(patd.getTerritory().getName());
    return rec != null ? rec : newRecord(proData, patd);
  }

  private AttackRecord newRecord(final ProData proData, final ProTerritory patd) {
    final Territory t = patd.getTerritory();
    final GamePlayer player = proData.getPlayer();
    final AttackRecord rec = new AttackRecord(t.getName());
    rec.owner = t.getOwner().getName();
    rec.water = t.isWater();
    rec.neutral = isNeutral(proData.getData(), t);
    rec.desired = proData.getAttackWinPercentage();
    rec.minimum = proData.getAttackMinWinPercentage();
    final ProBattleResult max = patd.getMaxBattleResult();
    if (max != null) {
      rec.maxWin = max.getWinPercentage();
      rec.tuvSwing = max.getTuvSwing();
    }
    final List<Unit> maxAttackers = new ArrayList<>(patd.getMaxUnits());
    maxAttackers.addAll(patd.getMaxAmphibUnits());
    rec.maxAttackers.putAll(countTypes(maxAttackers));
    rec.defenders.putAll(countTypes(withoutInfrastructure(patd.getMaxEnemyDefenders(player))));
    rec.value = ProTerritoryValueUtils.findTerritoryAttackValue(proData, player, t);
    final int isEnemyFactory =
        ProMatches.territoryHasInfraFactoryAndIsEnemyLand(player).test(t) ? 1 : 0;
    rec.valueProduction = 3.0 * TerritoryAttachment.getProduction(t) * (isEnemyFactory + 1);
    rec.valueVictoryCity = 3.0 * ProTerritoryValueUtils.victoryCityValue(proData, t);
    rec.valueNeutral = rec.value - rec.valueProduction - rec.valueVictoryCity;
    update(rec, patd);
    attacks.put(rec.territory, rec);
    return rec;
  }

  private static void update(final AttackRecord rec, final ProTerritory patd) {
    rec.amphib = patd.isNeedAmphibUnits();
    rec.strafing = patd.isStrafing();
    rec.canHold = patd.isCanHold();
    if (patd.getValue() != 0 || !Double.isNaN(rec.priority)) {
      rec.priority = patd.getValue();
    }
    final ProBattleResult result = patd.getBattleResult();
    if (result != null) {
      rec.win = result.getWinPercentage();
      rec.tuvSwing = result.getTuvSwing();
    }
  }

  /**
   * Neutral for the diary: land the Pro AI counts as neutral, or land owned by a player outside the
   * map's main alliances (WW2 Europe 1940: Neutral_True, Neutral_Axis, Neutral_Allies).
   */
  static boolean isNeutral(final GameState data, final Territory t) {
    if (t.isWater()) {
      return false;
    }
    return ProUtils.isNeutralLand(t)
        || (!t.getOwner().isNull() && ProPlusSettings.allianceOf(data, t.getOwner()).isEmpty());
  }

  /** Neutral land with units next to my land that the AI could not even consider attacking. */
  private List<String> neutralsNotConsidered(final ProData proData) {
    final GamePlayer player = proData.getPlayer();
    final Set<String> result = new LinkedHashSet<>();
    for (final Territory t : proData.getData().getMap().getTerritories()) {
      if (!isNeutral(proData.getData(), t) || t.getUnitCollection().isEmpty()) {
        continue;
      }
      if (attacks.containsKey(t.getName())) {
        continue;
      }
      final boolean nextToMine =
          proData.getData().getMap().getNeighbors(t).stream()
              .anyMatch(n -> !n.isWater() && n.isOwnedBy(player));
      if (nextToMine) {
        result.add(t.getName() + " [" + t.getOwner().getName() + "]");
      }
    }
    return new ArrayList<>(result);
  }

  // ---------------------------------------------------------------- execution and outcome

  private void checkExecuted(final GameState data) {
    final GamePlayer player = data.getPlayerList().getPlayerId(playerName);
    if (player == null) {
      return;
    }
    final BattleTracker tracker = data.getBattleDelegate().getBattleTracker();
    final TuvCostsCalculator costs = new TuvCostsCalculator();
    for (final AttackRecord rec : attacks.values()) {
      if (!rec.planned || rec.isDropped()) {
        continue;
      }
      final Territory t = data.getMap().getTerritoryOrNull(rec.territory);
      if (t == null) {
        continue;
      }
      final boolean pending = tracker.getPendingBattle(t, BattleType.NORMAL) != null;
      final boolean conquered = tracker.wasConquered(t);
      final List<Unit> mine = t.getMatches(Matches.unitIsOwnedBy(player));
      rec.executed = pending || conquered || !mine.isEmpty();
      if (!rec.executed) {
        rec.outcome = "non eseguito";
        continue;
      }
      for (final Unit u : mine) {
        rec.attackerUnits.add(unitInfo(u, costs));
      }
      for (final Unit u : withoutInfrastructure(t.getMatches(Matches.enemyUnit(player)))) {
        rec.defenderUnits.add(unitInfo(u, costs));
      }
      if (pending) {
        pendingOutcomes.add(rec);
      } else {
        rec.outcome = conquered ? "VITTORIA (senza battaglia)" : "nessuna battaglia";
      }
    }
  }

  private static AttackRecord.UnitInfo unitInfo(final Unit u, final TuvCostsCalculator costs) {
    final IntegerMap<UnitType> map = costs.getCostsForTuv(u.getOwner());
    return new AttackRecord.UnitInfo(u.getId(), u.getType().getName(), map.getInt(u.getType()));
  }

  private void resolveOutcomes(final GameState data) {
    if (pendingOutcomes.isEmpty() || diary == null) {
      return;
    }
    final GamePlayer player = data.getPlayerList().getPlayerId(playerName);
    final Set<UUID> onMap = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        onMap.add(u.getId());
      }
    }
    final StringBuilder sb =
        new StringBuilder("  Esito reale delle battaglie (stima -> risultato):\n");
    int won = 0;
    double expected = 0;
    for (final AttackRecord rec : pendingOutcomes) {
      final Territory t = data.getMap().getTerritoryOrNull(rec.territory);
      if (t == null || player == null) {
        continue;
      }
      final Set<UUID> here =
          t.getUnits().stream().map(Unit::getId).collect(Collectors.toCollection(HashSet::new));
      final int attackersBefore = rec.attackerUnits.size();
      final int attackersHere =
          (int) rec.attackerUnits.stream().filter(u -> here.contains(u.id())).count();
      final int attackersDead =
          (int) rec.attackerUnits.stream().filter(u -> !onMap.contains(u.id())).count();
      final int attackersRetreated = attackersBefore - attackersHere - attackersDead;
      final int defendersBefore = rec.defenderUnits.size();
      final int defendersLeft =
          (int) rec.defenderUnits.stream().filter(u -> here.contains(u.id())).count();
      final int tuvAttackersLost =
          rec.attackerUnits.stream()
              .filter(u -> !onMap.contains(u.id()))
              .mapToInt(u -> u.tuv())
              .sum();
      final int tuvDefendersLost =
          rec.defenderUnits.stream()
              .filter(u -> !onMap.contains(u.id()))
              .mapToInt(u -> u.tuv())
              .sum();
      final boolean enemyLeft = t.anyUnitsMatch(Matches.enemyUnit(player));
      final boolean win;
      if (t.isWater()) {
        win = !enemyLeft && attackersHere > 0;
      } else {
        win =
            t.isOwnedBy(player)
                || (!t.getOwner().isNull()
                    && data.getRelationshipTracker().isAllied(player, t.getOwner())
                    && !rec.owner.equals(t.getOwner().getName()));
      }
      final String outcome;
      if (win) {
        outcome = t.isWater() ? "VITTORIA (zona ripulita)" : "VITTORIA (conquistato)";
        won++;
      } else if (attackersHere == 0 && attackersRetreated > 0) {
        outcome = "RITIRATA";
      } else if (attackersHere == 0) {
        outcome = "SCONFITTA";
      } else {
        outcome = "NON RIUSCITO (restano difensori o solo aerei)";
      }
      rec.outcome = outcome;
      final double estimated = Double.isNaN(rec.win) ? rec.maxWin : rec.win;
      expected += Double.isNaN(estimated) ? 0 : estimated / 100;
      final int realSwing = tuvDefendersLost - tuvAttackersLost;
      sb.append(
          String.format(
              Locale.ROOT,
              "    - %s%s: stimata %s -> %s; attaccanti %d -> %d%s, difensori %d -> %d;"
                  + " TUV stimato %s, reale %+d%n",
              rec.territory,
              rec.neutral ? " (NEUTRALE)" : "",
              Italian.percent(estimated),
              outcome,
              attackersBefore,
              attackersHere,
              attackersRetreated > 0 ? " (" + attackersRetreated + " ritirati)" : "",
              defendersBefore,
              defendersLeft,
              Double.isNaN(rec.tuvSwing) ? "?" : String.format(Locale.ROOT, "%+.0f", rec.tuvSwing),
              realSwing));
      final JsonObject json = event("esito_battaglia");
      json.addProperty("territorio", rec.territory);
      json.addProperty("neutrale", rec.neutral);
      json.addProperty("win_stimata", round1(estimated));
      json.addProperty("esito", outcome);
      json.addProperty("vinta", win);
      json.addProperty("attaccanti_prima", attackersBefore);
      json.addProperty("attaccanti_dopo", attackersHere);
      json.addProperty("attaccanti_ritirati", attackersRetreated);
      json.addProperty("difensori_prima", defendersBefore);
      json.addProperty("difensori_dopo", defendersLeft);
      json.addProperty("tuv_swing_stimato", round1(rec.tuvSwing));
      json.addProperty("tuv_swing_reale", realSwing);
      diary.json(json);
    }
    sb.append(
        String.format(
            Locale.ROOT,
            "    Totale: %d %s, vinte %d, attese %.1f (somma delle win%% stimate)%n",
            pendingOutcomes.size(),
            pendingOutcomes.size() == 1 ? "battaglia" : "battaglie",
            won,
            expected));
    pendingOutcomes.clear();
    diary.text(sb.toString());
  }

  // ---------------------------------------------------------------- text of the attack plan

  private void writeAttacks(final GameState data) {
    if (attacksWritten || diary == null) {
      return;
    }
    attacksWritten = true;
    final List<AttackRecord> all = new ArrayList<>(attacks.values());
    final List<AttackRecord> executed =
        sorted(
            all.stream()
                .filter(r -> r.planned && !r.isDropped() && !Boolean.FALSE.equals(r.executed)));
    final List<AttackRecord> notExecuted =
        sorted(all.stream().filter(r -> r.planned && Boolean.FALSE.equals(r.executed)));
    final List<AttackRecord> dropped = sorted(all.stream().filter(AttackRecord::isDropped));
    final StringBuilder sb = new StringBuilder();
    sb.append("  Attacchi eseguiti:").append(executed.isEmpty() ? " nessuno" : "").append('\n');
    executed.forEach(r -> sb.append("    - ").append(executedLine(r)).append('\n'));
    if (!notExecuted.isEmpty()) {
      sb.append("  Attacchi pianificati ma non eseguiti (mossa rifiutata):\n");
      notExecuted.forEach(r -> sb.append("    - ").append(executedLine(r)).append('\n'));
    }
    if (!raids.isEmpty()) {
      sb.append("  Bombardamenti strategici: ");
      sb.append(
          raids.stream()
              .map(r -> r.territory + " (" + Italian.units(r.attackers) + ")")
              .collect(Collectors.joining("; ")));
      sb.append('\n');
    }
    sb.append("  Attacchi scartati:").append(dropped.isEmpty() ? " nessuno" : "").append('\n');
    dropped.forEach(r -> sb.append("    - ").append(droppedLine(r)).append('\n'));
    for (final AttackRecord r : all) {
      diary.json(attackJson(r));
    }
    writeObjectives(sb, data);
    writeNeutrals(sb, all);
    diary.text(sb.toString());
  }

  private static List<AttackRecord> sorted(final Stream<AttackRecord> records) {
    return records
        .sorted(
            Comparator.comparingDouble(
                    (AttackRecord r) -> Double.isNaN(r.priority) ? -1e9 : r.priority)
                .reversed()
                .thenComparing(
                    Comparator.comparingDouble((AttackRecord r) -> nanLow(r.maxWin)).reversed())
                .thenComparing(r -> r.territory))
        .toList();
  }

  private static double nanLow(final double d) {
    return Double.isNaN(d) ? -1 : d;
  }

  private String executedLine(final AttackRecord r) {
    final StringBuilder sb = new StringBuilder();
    sb.append(r.territory).append(owner(r)).append(": vittoria stimata ");
    sb.append(Double.isNaN(r.win) ? "n/d" : Italian.percent(r.win));
    sb.append(" (soglia desiderata ").append(Italian.percent(r.desired));
    sb.append(", max possibile ").append(Italian.percent(r.maxWin)).append(')');
    sb.append(", ").append(valueText(r));
    sb.append(tags(r));
    sb.append("; attaccanti: ").append(Italian.units(r.attackers));
    sb.append("; difensori: ").append(Italian.units(r.defenders));
    return sb.toString();
  }

  private String droppedLine(final AttackRecord r) {
    final StringBuilder sb = new StringBuilder();
    sb.append(r.territory).append(owner(r)).append(": ");
    if ("below_minimum".equals(r.dropReason)) {
      sb.append("win% max ")
          .append(Italian.percent(r.maxWin))
          .append(" < soglia minima ")
          .append(Italian.percent(r.minimum));
    } else {
      sb.append("win% max ").append(Italian.percent(r.maxWin));
      if (!Double.isNaN(r.win)) {
        sb.append(", con le unita' assegnate ").append(Italian.percent(r.win));
        if (r.win < r.desired) {
          sb.append(" < desiderata ").append(Italian.percent(r.desired));
        }
      }
      sb.append(" - ").append(Italian.reason(r.dropReason));
    }
    sb.append("; ").append(valueText(r)).append(tags(r));
    return sb.toString();
  }

  private static String owner(final AttackRecord r) {
    if (r.water) {
      return " [mare]";
    }
    return " [" + (r.neutral ? "NEUTRALE " : "") + Italian.nation(r.owner) + "]";
  }

  private static String valueText(final AttackRecord r) {
    final StringBuilder sb = new StringBuilder();
    if (!Double.isNaN(r.priority)) {
      sb.append("priorita' ").append(Italian.number(r.priority));
      sb.append(" (quota VC ").append(Italian.number(r.priorityVictoryCity)).append("), ");
    }
    sb.append("valore ").append(Italian.number(r.value));
    sb.append(" (di cui VC ").append(Italian.number(r.valueVictoryCity));
    if (r.valueNeutral != 0) {
      sb.append(", costo neutrale ").append(Italian.number(r.valueNeutral));
    }
    sb.append(')');
    return sb.toString();
  }

  private static String tags(final AttackRecord r) {
    final List<String> tags = new ArrayList<>();
    if (r.amphib) {
      tags.add("anfibio");
    }
    if (r.strafing) {
      tags.add("logoramento");
    }
    if (r.readded) {
      tags.add("ripreso");
    }
    return tags.isEmpty() ? "" : " [" + String.join(", ", tags) + "]";
  }

  private void writeObjectives(final StringBuilder sb, final GameState data) {
    final List<AttackRecord> ranked =
        attacks.values().stream()
            .filter(r -> !Double.isNaN(r.priority) && r.priority > 0)
            .sorted(Comparator.comparingDouble((AttackRecord r) -> r.priority).reversed())
            .toList();
    sb.append("  Obiettivi principali del turno (per priorita'):\n");
    if (ranked.isEmpty()) {
      sb.append("    nessun bersaglio con priorita' positiva\n");
    }
    for (int i = 0; i < Math.min(TOP_SHOWN, ranked.size()); i++) {
      final AttackRecord r = ranked.get(i);
      sb.append(
          String.format(
              Locale.ROOT,
              "    %d. %s%s: priorita' %s (quota VC %s), valore %s, quota victory city %.0f%%"
                  + " -> %s%n",
              i + 1,
              r.territory,
              owner(r),
              Italian.number(r.priority),
              Italian.number(r.priorityVictoryCity),
              Italian.number(r.value),
              r.victoryCityShare() * 100,
              statusText(r)));
    }
    final List<String> top = ranked.stream().limit(TOP_COMPARED).map(r -> r.territory).toList();
    final Map<String, Double> values = new HashMap<>();
    attacks.values().stream()
        .filter(r -> !Double.isNaN(r.priority))
        .forEach(r -> values.put(r.territory, r.priority));
    final JsonObject json = event("obiettivi");
    final JsonArray topJson = new JsonArray();
    ranked.stream()
        .limit(TOP_COMPARED)
        .forEach(
            r -> {
              final JsonObject o = new JsonObject();
              o.addProperty("territorio", r.territory);
              o.addProperty("priorita", round1(r.priority));
              o.addProperty("priorita_vc", round1(r.priorityVictoryCity));
              o.addProperty("valore", round1(r.value));
              o.addProperty("valore_vc", round1(r.valueVictoryCity));
              o.addProperty("stato", statusCode(r));
              topJson.add(o);
            });
    json.add("top", topJson);
    final Optional<GameDiary.Objectives> previous = diary.previousObjectives(playerName);
    if (previous.isPresent()) {
      final List<String> entered =
          top.stream().filter(n -> !previous.get().top().contains(n)).toList();
      final List<String> exited =
          previous.get().top().stream().filter(n -> !top.contains(n)).toList();
      sb.append(
          String.format(
              Locale.ROOT,
              "  Cambi negli obiettivi (top %d) rispetto al round %d:%n",
              TOP_COMPARED,
              previous.get().round()));
      if (entered.isEmpty() && exited.isEmpty()) {
        sb.append("    nessun cambio\n");
      }
      final JsonArray enteredJson = new JsonArray();
      for (final String n : entered) {
        final Double before = previous.get().values().get(n);
        sb.append("    + entrato ")
            .append(n)
            .append(": priorita' ")
            .append(Italian.number(values.get(n)))
            .append(
                before == null
                    ? " (il turno prima non era un bersaglio possibile)"
                    : " (prima " + Italian.number(before) + ")")
            .append('\n');
        enteredJson.add(change(n, before, values.get(n), null));
      }
      final JsonArray exitedJson = new JsonArray();
      for (final String n : exited) {
        final AttackRecord now = attacks.get(n);
        final Double before = previous.get().values().get(n);
        final String why;
        if (now == null) {
          why = whyNoLongerTarget(data, n);
        } else if (Double.isNaN(now.priority)) {
          why = "non arriva alla classifica: " + Italian.reason(now.dropReason);
        } else {
          why = "priorita' scesa a " + Italian.number(now.priority);
        }
        sb.append("    - uscito ")
            .append(n)
            .append(" (prima ")
            .append(before == null ? "?" : Italian.number(before))
            .append("): ")
            .append(why)
            .append('\n');
        exitedJson.add(
            change(
                n, before, now == null || Double.isNaN(now.priority) ? null : now.priority, why));
      }
      json.add("entrati", enteredJson);
      json.add("usciti", exitedJson);
      json.addProperty("round_precedente", previous.get().round());
    }
    diary.json(json);
    diary.setObjectives(playerName, new GameDiary.Objectives(round, top, values));
  }

  private String whyNoLongerTarget(final GameState data, final String territory) {
    final Territory t = data.getMap().getTerritoryOrNull(territory);
    final GamePlayer player = data.getPlayerList().getPlayerId(playerName);
    if (t == null || player == null) {
      return "non e' piu' un bersaglio possibile";
    }
    if (t.isWater()) {
      return t.anyUnitsMatch(Matches.enemyUnit(player))
          ? "navi nemiche fuori portata questo turno"
          : "nessuna nave nemica rimasta nella zona";
    }
    if (!Matches.isTerritoryEnemy(player).test(t)) {
      return "non piu' nemico: ora di " + Italian.nation(t.getOwner().getName());
    }
    return "fuori portata questo turno (nessuna unita' puo' attaccarlo)";
  }

  private static JsonObject change(
      final String territory,
      @Nullable final Double before,
      @Nullable final Double now,
      @Nullable final String why) {
    final JsonObject o = new JsonObject();
    o.addProperty("territorio", territory);
    if (before != null) {
      o.addProperty("priorita_prima", round1(before));
    }
    if (now != null) {
      o.addProperty("priorita_ora", round1(now));
    }
    if (why != null) {
      o.addProperty("motivo", why);
    }
    return o;
  }

  private static String statusText(final AttackRecord r) {
    if (r.isDropped()) {
      return "scartato (" + Italian.reason(r.dropReason) + ")";
    }
    if (Boolean.FALSE.equals(r.executed)) {
      return "pianificato, non eseguito";
    }
    return r.planned ? "attaccato" : "non attaccato";
  }

  private static String statusCode(final AttackRecord r) {
    if (r.isDropped()) {
      return "scartato";
    }
    if (Boolean.FALSE.equals(r.executed)) {
      return "non_eseguito";
    }
    return r.planned ? "eseguito" : "candidato";
  }

  private void writeNeutrals(final StringBuilder sb, final List<AttackRecord> all) {
    final List<AttackRecord> neutrals = sorted(all.stream().filter(r -> r.neutral));
    sb.append("  NEUTRALI:");
    if (neutrals.isEmpty() && neutralsNotConsidered.isEmpty()) {
      sb.append(" nessun neutrale tra i bersagli possibili\n");
      return;
    }
    sb.append('\n');
    for (final AttackRecord r : neutrals) {
      sb.append("    * ")
          .append(r.territory)
          .append(owner(r))
          .append(": ")
          .append(statusText(r))
          .append(", win% max ")
          .append(Italian.percent(r.maxWin))
          .append(", difensori ")
          .append(Italian.units(r.defenders))
          .append('\n');
    }
    if (!neutralsNotConsidered.isEmpty()) {
      sb.append(
              "    * confinanti con unita' ma NON considerati (non in guerra o irraggiungibili): ")
          .append(String.join(", ", neutralsNotConsidered))
          .append('\n');
      final JsonObject json = event("neutrali_non_considerati");
      final JsonArray list = new JsonArray();
      neutralsNotConsidered.forEach(list::add);
      json.add("territori", list);
      diary.json(json);
    }
  }

  private JsonObject attackJson(final AttackRecord r) {
    final JsonObject json = event("attacco");
    json.addProperty("territorio", r.territory);
    json.addProperty("proprietario", r.owner);
    json.addProperty("neutrale", r.neutral);
    json.addProperty("mare", r.water);
    json.addProperty("stato", statusCode(r));
    if (r.dropReason != null) {
      json.addProperty("motivo_codice", r.dropReason);
      json.addProperty("motivo", Italian.reason(r.dropReason));
    }
    json.addProperty("win_stimata", round1(r.win));
    json.addProperty("win_max", round1(r.maxWin));
    json.addProperty("soglia_desiderata", r.desired);
    json.addProperty("soglia_minima", r.minimum);
    json.addProperty("priorita", round1(r.priority));
    json.addProperty("priorita_vc", round1(r.priorityVictoryCity));
    final JsonObject value = new JsonObject();
    value.addProperty("totale", round1(r.value));
    value.addProperty("produzione", round1(r.valueProduction));
    value.addProperty("victory_city", round1(r.valueVictoryCity));
    value.addProperty("neutrale", round1(r.valueNeutral));
    json.add("valore", value);
    json.addProperty("tuv_swing_stimato", round1(r.tuvSwing));
    json.addProperty("anfibio", r.amphib);
    json.addProperty("logoramento", r.strafing);
    json.addProperty("tenibile", r.canHold);
    json.addProperty("ripreso", r.readded);
    json.add("unita_attaccanti", counts(r.attackers));
    json.add("unita_attaccanti_max", counts(r.maxAttackers));
    json.add("difensori", counts(r.defenders));
    if (r.outcome != null) {
      json.addProperty("esito_immediato", r.outcome);
    }
    return json;
  }

  // ---------------------------------------------------------------- helpers

  private JsonObject event(final String type) {
    final JsonObject json = new JsonObject();
    json.addProperty("round", round);
    json.addProperty("nazione", playerName);
    json.addProperty("ai", ai.getPlayerLabel());
    json.addProperty("fase", phase);
    json.addProperty("evento", type);
    return json;
  }

  private static JsonObject counts(final Map<String, Integer> counts) {
    final JsonObject json = new JsonObject();
    counts.forEach(json::addProperty);
    return json;
  }

  @Nullable
  private static Double round1(final double d) {
    return Double.isNaN(d) || Double.isInfinite(d) ? null : Math.round(d * 10) / 10.0;
  }

  /** Factories, bases and the like: they don't fight, so the diary doesn't count them. */
  private static List<Unit> withoutInfrastructure(final Collection<Unit> units) {
    return units.stream().filter(Matches.unitIsInfrastructure().negate()).toList();
  }

  private static Map<String, Integer> countTypes(final Collection<Unit> units) {
    final Map<String, Integer> counts = new TreeMap<>();
    for (final Unit u : units) {
      counts.merge(u.getType().getName(), 1, Integer::sum);
    }
    return counts;
  }

  private static Set<String> union(final Set<String> a, final Set<String> b) {
    final Set<String> all = new LinkedHashSet<>(a);
    all.addAll(b);
    return all;
  }

  private static List<ProTerritory> sortedByName(final Collection<ProTerritory> territories) {
    return territories.stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing((ProTerritory p) -> p.getTerritory().getName()))
        .toList();
  }

  /**
   * Runs a diary action: never lets an error out. After the first error the diary of this AI stops
   * for the rest of the game (one warning in the AI log and, if possible, in the diary).
   */
  private void guard(final Runnable action) {
    if (broken) {
      return;
    }
    final long start = System.nanoTime();
    try {
      action.run();
    } catch (final RuntimeException | LinkageError | StackOverflowError e) {
      broken = true;
      try {
        ProLogger.warn("Diario Pro+ disattivato per errore: " + e);
        if (diary != null) {
          diary.text("  [diario interrotto per un errore interno: " + e + "]\n");
          final JsonObject json = event("errore_diario");
          json.addProperty("errore", String.valueOf(e));
          diary.json(json);
        }
      } catch (final RuntimeException | LinkageError ignored) {
        // nothing more to do: the game goes on without diary
      }
    } finally {
      diaryNanos += System.nanoTime() - start;
    }
  }

  /** For tests: whether an internal error stopped this diary. */
  boolean isBroken() {
    return broken;
  }
}
