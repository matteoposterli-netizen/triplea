package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.ProStagingOrders;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.pro.ProWarDecision;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.Test;

/**
 * Step 3b-ter: politics, critical objectives, front line staging, minimum garrisons and embarkation
 * of the plans (Pro+ only). Global 1940 renamed as Europe 1940 (same European names).
 */
class PlanStep3cTest {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final GamePlayer italians = data.getPlayerList().getPlayerId("Italians");
  private final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
  private final GamePlayer british = data.getPlayerList().getPlayerId("British");

  private ProData proPlus(final GamePlayer player, final int round, final String axisPlan) {
    data.setGameName(EUROPE);
    data.getSequence().setRoundAndStep(round, "Combat Move", player);
    StatoMaggiore.setChoice(data.getProperties(), "Axis", axisPlan);
    final ProData proData = new ProData(false, new ProPlusTuning());
    proData.initializeSimulation(null, data, player);
    return proData;
  }

  @Test
  void warOnTheUssrIsForbiddenBeforeThePlansRound() {
    final ProData barbarossaR1 = proPlus(germans, 1, "A");
    final ProWarDecision r1 = barbarossaR1.getTuning().warDecision(barbarossaR1, russians);
    assertThat(r1.kind()).isEqualTo(ProWarDecision.Kind.FORBIDDEN);
    assertThat(r1.reason()).contains("vietata prima del round 3");
    // an enemy the plan doesn't name: Hard decides
    assertThat(barbarossaR1.getTuning().warDecision(barbarossaR1, british))
        .isEqualTo(ProWarDecision.HARD);

    final ProData seaLionR3 = proPlus(germans, 3, "B");
    assertThat(seaLionR3.getTuning().warDecision(seaLionR3, russians).reason()).contains("round 4");

    // from round 3 Barbarossa declares once the staging is done, else waits
    final ProData barbarossaR3 = proPlus(germans, 3, "A");
    final ProWarDecision r3 = barbarossaR3.getTuning().warDecision(barbarossaR3, russians);
    assertThat(r3.kind()).isNotEqualTo(ProWarDecision.Kind.HARD);
    assertThat(r3.reason()).contains("ammassamento").contains("TUV");

    final ProData neutral = proPlus(germans, 1, "N");
    assertThat(neutral.getTuning().warDecision(neutral, russians)).isEqualTo(ProWarDecision.HARD);
    assertThat(ProTuning.DEFAULT.warDecision(neutral, russians)).isEqualTo(ProWarDecision.HARD);
  }

  @Test
  void aCriticalObjectiveIsPlannedToAtLeast80WhateverTheSlider() {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.SPERICOLATO);
    final ProData proData = proPlus(germans, 1, "A");

    assertThat(proData.getAttackWinPercentage(territory("France"))).isEqualTo(80);
    // not critical: the slider's
    assertThat(proData.getAttackWinPercentage(territory("Normandy Bordeaux"))).isLessThan(80);
  }

  @Test
  void offensiveStagingGathersOnTheFrontLineAndOnTheChannelWithItsWeight() {
    atWar(germans, russians);
    final ProData proData = proPlus(germans, 3, "A");

    final ProStagingOrders orders = proData.getTuning().stagingOrders(proData).orElseThrow();

    assertThat(orders.front()).contains("Est peso 80 (linea del fronte)").contains("Ovest peso 20");
    // the front line: own land of the East front next to the USSR, weighed towards the objectives
    assertThat(orders.territories()).contains("Poland");
    assertThat(orders.weight("Poland")).isBetween(0.5 * 0.99, 1.0);
    // the Channel coast pulls a quarter of the East (20 vs 80)
    assertThat(orders.weight("Holland Belgium")).isEqualTo(0.25);
    assertThat(proData.getTuning().minimumGarrisons(proData))
        .containsEntry("Normandy Bordeaux", 2)
        .containsEntry("Holland Belgium", 2)
        .containsEntry("Denmark", 2);
  }

  @Test
  void beforeTheWarTheFrontLineFallsBackOnTheFilesPoints() {
    final ProData proData = proPlus(germans, 3, "A");

    final ProStagingOrders orders = proData.getTuning().stagingOrders(proData).orElseThrow();

    assertThat(orders.front()).contains("linea del fronte non formata");
    assertThat(orders.territories()).contains("Poland", "Slovakia Hungary", "Romania");
  }

  @Test
  void italyGathersOverseasFromItsEmbarkationPointAndBuysATransport() {
    final ProData proData = proPlus(italians, 1, "A");

    final ProStagingOrders orders = proData.getTuning().stagingOrders(proData).orElseThrow();
    assertThat(orders.territories()).contains("Southern Italy", "96 Sea Zone", "Libya");
    assertThat(orders.front()).contains("imbarco Southern Italy/96 Sea Zone");

    final ProPurchaseOrders purchase = proData.getTuning().purchaseOrders(proData).orElseThrow();
    assertThat(purchase.label()).contains("trasporto per l'imbarco");
    assertThat(purchase.mandatory())
        .anyMatch(m -> m.category().equals("trasporti") && m.minimum() >= 1);
    assertThat(purchase.minimumShare()).isEqualTo(40);

    // at war with the USSR a way by land through enemy land doesn't make Africa reachable
    atWar(italians, russians);
    final ProData atWar = proPlus(italians, 3, "A");
    assertThat(atWar.getTuning().stagingOrders(atWar).orElseThrow().front()).contains("imbarco");
  }

  @Test
  void theNeutralPlanAndHardHaveNoneOfIt() {
    final ProData neutral = proPlus(germans, 3, "N");
    assertThat(neutral.getTuning().stagingOrders(neutral)).isEmpty();
    assertThat(neutral.getTuning().minimumGarrisons(neutral)).isEmpty();
    assertThat(ProTuning.DEFAULT.minimumGarrisons(neutral)).isEmpty();
  }

  private void atWar(final GamePlayer a, final GamePlayer b) {
    data.performChange(
        ChangeFactory.relationshipChange(
            a,
            b,
            data.getRelationshipTracker().getRelationshipType(a, b),
            data.getRelationshipTypeList().getDefaultWarRelationship()));
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }
}
