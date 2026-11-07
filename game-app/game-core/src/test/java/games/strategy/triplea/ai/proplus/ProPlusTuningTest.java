package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.properties.IEditableProperty;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProPlusTuningTest extends AbstractClientSettingTestCase {
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");

  private ProData initializedProPlusData() {
    final ProData proData = new ProData(false, new ProPlusTuning());
    proData.initializeSimulation(null, data, germans);
    return proData;
  }

  @Test
  void attackThresholdsFollowTheAllianceRiskLevel() {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.SPERICOLATO);

    final ProData proData = initializedProPlusData();

    assertThat(proData.getAttackWinPercentage()).isEqualTo(65);
    assertThat(proData.getAttackMinWinPercentage()).isEqualTo(45);
    // defense, purchase and scramble keep the Hard thresholds
    assertThat(proData.getWinPercentage()).isEqualTo(90);
    assertThat(proData.getMinWinPercentage()).isEqualTo(65);
  }

  @Test
  void landingScrambleCheckAndAmphibiousStrafeRuleAreProPlusOnly() {
    // Hard: no scramble check (Hard Beta yes), every strafe kept
    assertThat(ProTuning.DEFAULT.checksLandingScramble(false)).isFalse();
    assertThat(ProTuning.DEFAULT.checksLandingScramble(true)).isTrue();
    final ProData hard = new ProData();
    hard.initializeSimulation(null, data, germans);
    final Territory france = data.getMap().getTerritoryOrThrow("France");
    assertThat(ProTuning.DEFAULT.dropsAmphibiousStrafe(hard, france, 0)).isFalse();

    // Pro+ (Equilibrato, minimum 60%): scramble checked, amphibious strafe below 60% dropped
    final ProData proData = initializedProPlusData();
    final ProTuning tuning = proData.getTuning();
    assertThat(tuning.checksLandingScramble(false)).isTrue();
    assertThat(tuning.dropsAmphibiousStrafe(proData, france, 0)).isTrue();
    assertThat(tuning.dropsAmphibiousStrafe(proData, france, 59.9)).isTrue();
    assertThat(tuning.dropsAmphibiousStrafe(proData, france, 60)).isFalse();
  }

  @Test
  void defaultRiskLevelIsEquilibrato() {
    final ProData proData = initializedProPlusData();

    assertThat(proData.getAttackWinPercentage()).isEqualTo(80);
    assertThat(proData.getAttackMinWinPercentage()).isEqualTo(60);
  }

  @Test
  void lowLuckAddsFivePoints() {
    setLowLuck(data);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.AUDACE);

    final ProData proData = initializedProPlusData();

    assertThat(proData.getAttackWinPercentage()).isEqualTo(77);
    assertThat(proData.getAttackMinWinPercentage()).isEqualTo(57);
    assertThat(proData.getWinPercentage()).isEqualTo(95);
    assertThat(proData.getMinWinPercentage()).isEqualTo(75);
  }

  @Test
  void victoryCitiesAreValuedWithTheHardBetaFormula() {
    final ProData hard = new ProData();
    hard.initializeSimulation(null, data, germans);
    final ProData proPlus = initializedProPlusData();
    final var unitedKingdom = data.getMap().getTerritoryOrThrow("United Kingdom");
    final var normandy = data.getMap().getTerritoryOrThrow("Normandy Bordeaux");

    // victory city: 3 x (1 victory city x 5 PUs) more than Hard
    assertThat(ProTerritoryValueUtils.findTerritoryAttackValue(proPlus, germans, unitedKingdom))
        .isEqualTo(
            ProTerritoryValueUtils.findTerritoryAttackValue(hard, germans, unitedKingdom) + 15);
    // not a victory city: same value
    assertThat(ProTerritoryValueUtils.findTerritoryAttackValue(proPlus, germans, normandy))
        .isEqualTo(ProTerritoryValueUtils.findTerritoryAttackValue(hard, germans, normandy));
  }

  @Test
  void prudenteKeepsTheSameAttacksAsHard() throws IOException {
    final String report = proPlusReport(RiskLevel.PRUDENTE);

    assertThat(attackLines(report)).isEqualTo(attackLines(golden()));
  }

  @Test
  void spericolatoKeepsEveryAttackHardKeepsAndMore() throws IOException {
    final List<String> hardTargets = targets(golden());
    final List<String> spericolatoTargets = targets(proPlusReport(RiskLevel.SPERICOLATO));

    assertThat(spericolatoTargets).containsAll(hardTargets);
    assertThat(spericolatoTargets.size()).isGreaterThan(hardTargets.size());
  }

  /** Also written to build/proplus/ to compare the levels by hand. */
  private static String proPlusReport(final RiskLevel axisRisk) throws IOException {
    final String report =
        HardNeutralityGoldenTest.germanAttackReport(
            new ProData(false, new ProPlusTuning()),
            map -> ProPlusSettings.setRiskLevel(map.getProperties(), "Axis", axisRisk));
    final Path out = Path.of("build", "proplus", "proplus-" + axisRisk.getLabel() + ".txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, report);
    return report;
  }

  private static String golden() throws IOException {
    try (InputStream in =
        ProPlusTuningTest.class.getResourceAsStream(
            "/proplus/hard-golden-global1940-germans.txt")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<String> attackLines(final String report) {
    return report.lines().filter(l -> l.startsWith("attack ")).toList();
  }

  private static List<String> targets(final String report) {
    return attackLines(report).stream().map(l -> l.substring(0, l.indexOf(" maxWin"))).toList();
  }

  @SuppressWarnings("unchecked")
  private static void setLowLuck(final GameData data) {
    ((IEditableProperty<Boolean>)
            data.getProperties().getEditablePropertiesByName().get(Constants.LOW_LUCK))
        .setValue(true);
  }
}
