package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProAttackWorth;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.Test;

/**
 * Pro+ tactics under the general staff (step 3a): thresholds per target from the effective risk of
 * the target's front, priority bonus and objective rule from the plan. Global 1940 renamed as
 * Europe 1940 (same European names), Germans round 1.
 */
class StrategyTuningTest {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final Territory france = territory("France");
  private final Territory poland = territory("Poland");
  private final Territory egypt = territory("Egypt");

  StrategyTuningTest() {
    data.getSequence().setRoundAndStep(1, "Combat Move", germans);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private ProData proPlus(final RiskLevel axisRisk, final String axisPlan) {
    data.setGameName(EUROPE);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", axisRisk);
    StatoMaggiore.setChoice(data.getProperties(), "Axis", axisPlan);
    final ProData proData = new ProData(false, new ProPlusTuning());
    proData.initializeSimulation(null, data, germans);
    return proData;
  }

  private ProData hard() {
    final ProData proData = new ProData();
    proData.initializeSimulation(null, data, germans);
    return proData;
  }

  @Test
  void thresholdsFollowTheFrontOfTheTarget() {
    final ProData proData = proPlus(RiskLevel.EQUILIBRATO, "A");
    final boolean lowLuck = ProPlusTuning.isLowLuck(data);

    // France is an objective: minimum of the front (Audace), desired of the base level (step 3b)
    assertThat(proData.getAttackWinPercentage(france))
        .isEqualTo(RiskLevel.EQUILIBRATO.desiredWinPercentage(lowLuck));
    assertThat(proData.getAttackMinWinPercentage(france))
        .isEqualTo(RiskLevel.AUDACE.minimumWinPercentage(lowLuck));
    // not an objective, same front: both from the front's level
    assertThat(proData.getAttackWinPercentage(territory("Normandy Bordeaux")))
        .isEqualTo(RiskLevel.AUDACE.desiredWinPercentage(lowLuck));
    assertThat(proData.getAttackWinPercentage(poland))
        .isEqualTo(RiskLevel.CAUTO.desiredWinPercentage(lowLuck));
    assertThat(proData.getAttackWinPercentage(egypt))
        .isEqualTo(RiskLevel.EQUILIBRATO.desiredWinPercentage(lowLuck));
    // the turn-wide numbers stay the slider's
    assertThat(proData.getAttackWinPercentage())
        .isEqualTo(RiskLevel.EQUILIBRATO.desiredWinPercentage(lowLuck));
    // the searches start from the most cautious front
    assertThat(proData.getAttackWinPercentageCeiling())
        .isEqualTo(RiskLevel.CAUTO.desiredWinPercentage(lowLuck));
  }

  @Test
  void anObjectiveIsTriedFromTheFrontsMinimumButPlannedToTheBaseLevelsDesired() {
    final boolean lowLuck = ProPlusTuning.isLowLuck(data);
    // Barbarossa R1, base Equilibrato, Ovest +1: France minimum 52 (Audace), desired 80
    final ProData equilibrato = proPlus(RiskLevel.EQUILIBRATO, "A");
    assertThat(equilibrato.getAttackMinWinPercentage(france)).isEqualTo(52);
    assertThat(equilibrato.getAttackWinPercentage(france)).isEqualTo(80);
    assertThat(ProPlusTuning.check(equilibrato, notWorthIn(france)).desired()).isEqualTo(80);
    // a base level the front can't raise (Spericolato): the same level for both
    final ProData spericolato = proPlus(RiskLevel.SPERICOLATO, "A");
    assertThat(spericolato.getAttackWinPercentage(france))
        .isEqualTo(RiskLevel.SPERICOLATO.desiredWinPercentage(lowLuck));
    // Prudente + Ovest +1 = Cauto: tried from Cauto's minimum, planned to Prudente's desired
    final ProData prudente = proPlus(RiskLevel.PRUDENTE, "A");
    assertThat(prudente.getAttackMinWinPercentage(france))
        .isEqualTo(RiskLevel.CAUTO.minimumWinPercentage(lowLuck));
    assertThat(prudente.getAttackWinPercentage(france))
        .isEqualTo(RiskLevel.PRUDENTE.desiredWinPercentage(lowLuck));
    // the turn's ceiling still covers every target
    for (final Territory t : data.getMap().getTerritories()) {
      assertThat(equilibrato.getAttackWinPercentage(t))
          .isLessThanOrEqualTo(equilibrato.getAttackWinPercentageCeiling());
    }
  }

  @Test
  void aFleetObjectiveSeaZoneIsPlannedToTheBaseLevelsDesiredToo() {
    final ProData proData = proPlus(RiskLevel.EQUILIBRATO, "B"); // R1: Mari +1, flotta British
    final Territory britishFleet =
        data.getMap().getTerritories().stream()
            .filter(
                t -> t.isWater() && t.anyUnitsMatch(u -> u.getOwner().getName().equals("British")))
            .findFirst()
            .orElseThrow();
    assertThat(proData.getAttackMinWinPercentage(britishFleet)).isEqualTo(52);
    assertThat(proData.getAttackWinPercentage(britishFleet)).isEqualTo(80);
  }

  @Test
  void prudenteWithTheNeutralPlanHasHardThresholdsAndRulesEverywhere() {
    final ProData proPlus = proPlus(RiskLevel.PRUDENTE, "N");
    final ProData hard = hard();

    for (final Territory t : data.getMap().getTerritories()) {
      assertThat(proPlus.getAttackWinPercentage(t)).isEqualTo(hard.getAttackWinPercentage(t));
      assertThat(proPlus.getAttackMinWinPercentage(t)).isEqualTo(hard.getAttackMinWinPercentage(t));
      assertThat(proPlus.getTuning().attackPriorityPlanBonus(proPlus, t)).isZero();
    }
    assertThat(proPlus.getAttackWinPercentageCeiling())
        .isEqualTo(hard.getAttackWinPercentageCeiling());
    final ProAttackWorth notWorth =
        new ProAttackWorth(france, 100, true, false, false, false, 100, 0, 4, 12, -3, false);
    assertThat(proPlus.getTuning().keepsAttackNotWorthIt(proPlus, notWorth)).isEmpty();
  }

  @Test
  void planObjectivesGetAPriorityBonusAndTheObjectiveRule() {
    final ProData barbarossa = proPlus(RiskLevel.EQUILIBRATO, "A");

    assertThat(barbarossa.getTuning().attackPriorityPlanBonus(barbarossa, france)).isEqualTo(15);
    assertThat(barbarossa.getTuning().attackPriorityPlanBonus(barbarossa, egypt)).isZero();
    assertThat(ProPlusTuning.isObjectiveTarget(barbarossa, france)).isTrue();
    // Egypt: a victory city, but not an objective of Barbarossa for Germany in round 1
    assertThat(ProPlusTuning.isObjectiveTarget(barbarossa, egypt)).isFalse();

    final ProData south = proPlus(RiskLevel.EQUILIBRATO, "D");
    assertThat(ProPlusTuning.isObjectiveTarget(south, egypt)).isTrue();
    assertThat(south.getTuning().attackPriorityPlanBonus(south, egypt)).isEqualTo(15);
  }

  @Test
  void objectiveRuleUsesTheEffectiveLevelOfTheFront() {
    // Sud/Mediterraneo R1: Mediterraneo-Africa +1 => Equilibrato becomes Audace (cost limit 12)
    final ProData south = proPlus(RiskLevel.EQUILIBRATO, "D");
    // risk rule: 1 - 2 + 2 x 0.8 - 0.45 x 9 = -3.45 < -2; expected TUV cost 9 + 2 = 11
    final ProAttackWorth egyptAttack =
        new ProAttackWorth(egypt, 80, true, false, false, false, 100, -2, 2, 9, -5.4, false);

    final var check = ProPlusTuning.check(south, egyptAttack);
    assertThat(check.level()).isEqualTo(RiskLevel.AUDACE);
    assertThat(check.planRule()).isTrue();
    assertThat(check.objectiveTarget()).isTrue();
    assertThat(check.keptBy()).contains(ProPlusTuning.KEPT_BY_OBJECTIVE); // 11 <= 12 (Audace)
    // with the slider one step lower the front is Equilibrato: limit 8
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.CAUTO);
    assertThat(ProPlusTuning.check(south, egyptAttack).keptBy()).isEmpty();
  }

  @Test
  void withoutAStrategyFilePlusPlaysAsInStep2d() {
    final ProData proData = proPlus(RiskLevel.EQUILIBRATO, "A");
    data.setGameName("Global 1940 senza strategia");
    proData.initializeSimulation(null, data, germans);

    assertThat(ProPlusTuning.planOf(proData)).isEmpty();
    assertThat(proData.getAttackWinPercentage(france)).isEqualTo(proData.getAttackWinPercentage());
    assertThat(proData.getAttackWinPercentage(poland)).isEqualTo(proData.getAttackWinPercentage());
    // step 2d rule: Egypt is a victory city
    assertThat(ProPlusTuning.isObjectiveTarget(proData, egypt)).isTrue();
    assertThat(ProPlusTuning.check(proData, notWorthIn(egypt)).planRule()).isFalse();
  }

  private static ProAttackWorth notWorthIn(final Territory t) {
    return new ProAttackWorth(t, 90, true, false, false, false, 100, 0, 2, 9, -5, false);
  }
}
