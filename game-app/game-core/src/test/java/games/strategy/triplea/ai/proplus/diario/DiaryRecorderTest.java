package games.strategy.triplea.ai.proplus.diario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiaryRecorderTest extends AbstractClientSettingTestCase {
  @TempDir Path diaryRoot;
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");

  @BeforeEach
  void setUp() {
    System.setProperty(DiarySettings.FOLDER_PROPERTY, diaryRoot.toString());
    data.getSequence().setRoundAndStep(1, "Combat Move", germans);
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(DiarySettings.FOLDER_PROPERTY);
  }

  @Test
  void anInternalErrorNeverReachesTheAiAndStopsTheDiary() {
    final ProPlusAi ai = new ProPlusAi("Germans", ProPlusAi.PLAYER_LABEL);
    final DiaryRecorder recorder = (DiaryRecorder) ai.getProData().getRecorder();
    final ProData proData = ai.getProData();
    recorder.phaseStart(data, germans, "purchase");
    recorder.combatPlanStart(proData);

    assertThatCode(() -> recorder.attackDropped(proData, null, "negative_value"))
        .doesNotThrowAnyException();
    assertThat(recorder.isBroken()).isTrue();
    assertThatCode(() -> recorder.phaseEnd(data, germans, "purchase", 1))
        .doesNotThrowAnyException();
  }

  @Test
  void writesNothingWithoutAFolder() throws IOException {
    System.clearProperty(DiarySettings.FOLDER_PROPERTY);
    System.setProperty("triplea.headless", "true");
    try {
      final ProPlusAi ai = new ProPlusAi("Germans", ProPlusAi.PLAYER_LABEL);
      ai.getProData().getRecorder().phaseStart(data, germans, "purchase");
      ai.getProData().getRecorder().phaseEnd(data, germans, "purchase", 1);
    } finally {
      System.clearProperty("triplea.headless");
    }
    try (Stream<Path> files = Files.list(diaryRoot)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  void neutralsIncludeProAxisAndProAlliesTerritories() {
    final Territory yugoslavia = data.getMap().getTerritoryOrNull("Yugoslavia");
    final Territory france = data.getMap().getTerritoryOrNull("France");

    assertThat(DiaryRecorder.isNeutral(data, yugoslavia)).isTrue();
    assertThat(DiaryRecorder.isNeutral(data, france)).isFalse();
  }

  @Test
  void aTurnWithoutPlaceIsClosedWhenTheNextNationStarts() throws IOException {
    final GamePlayer french = data.getPlayerList().getPlayerId("French");
    final ProPlusAi frenchAi = new ProPlusAi("French", ProPlusAi.PLAYER_LABEL);
    final ProPlusAi germanAi = new ProPlusAi("Germans", ProPlusAi.PLAYER_LABEL);
    // French: nothing to buy nor place, so no purchase and no place phase
    frenchAi.getProData().getRecorder().phaseStart(data, french, "combat");
    frenchAi.getProData().getRecorder().phaseEnd(data, french, "combat", 100);
    frenchAi.getProData().getRecorder().phaseStart(data, french, "noncombat");
    frenchAi.getProData().getRecorder().phaseEnd(data, french, "noncombat", 900);
    data.getSequence().setRoundAndStep(2, "Combat Move", germans);
    germanAi.getProData().getRecorder().phaseStart(data, germans, "purchase");
    germanAi.getProData().getRecorder().phaseEnd(data, germans, "purchase", 1);
    germanAi.getProData().getRecorder().phaseStart(data, germans, "place");
    germanAi.getProData().getRecorder().phaseEnd(data, germans, "place", 1);
    frenchAi.getProData().getRecorder().phaseStart(data, french, "combat");

    final String text = diaryText();
    final int french1 = text.indexOf("Round 1 - Francia");
    final int germany2 = text.indexOf("Round 2 - Germania");
    final int french2 = text.indexOf("Round 2 - Francia");
    assertThat(french1).isNotNegative();
    assertThat(germany2).isGreaterThan(french1);
    assertThat(french2).isGreaterThan(germany2);
    // the French "Tempo AI" line is in the French turn, before Germany's turn, and only once
    assertThat(text.substring(french1, germany2))
        .contains("Tempo AI: combattimento 0.1 s, movimento 0.9 s");
    assertThat(text.substring(germany2, french2)).containsOnlyOnce("Tempo AI");
  }

  private String diaryText() throws IOException {
    try (Stream<Path> games = Files.list(diaryRoot)) {
      final Path game = games.findFirst().orElseThrow();
      return Files.readString(game.resolve(GameDiary.TEXT_FILE));
    }
  }
}
