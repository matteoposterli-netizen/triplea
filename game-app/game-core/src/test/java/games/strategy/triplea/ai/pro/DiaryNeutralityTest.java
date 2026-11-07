package games.strategy.triplea.ai.pro;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.ai.proplus.diario.DiarySettings;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.java.collections.IntegerMap;

/**
 * The decision diary only observes: with the diary on, the Pro+ attack plan, its moves and the
 * purchase are exactly those with the diary off (same map, same seeded battle dice).
 *
 * <p>The AI runs on the same {@link GameData} object each time and the move/purchase delegates only
 * record what the AI asks, so nothing changes between runs except the diary.
 */
class DiaryNeutralityTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261106L;

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
  void decisionsAreTheSameWithAndWithoutDiary() throws IOException {
    final String off = germanTurn(false);
    final String offAgain = germanTurn(false);
    final String on = germanTurn(true);

    assertThat(offAgain).as("replay without diary is reproducible").isEqualTo(off);
    assertThat(on).isEqualTo(off);
    assertThat(off).contains("move ").contains("buy ");
    assertThat(diaryFiles()).isNotEmpty();
  }

  @Test
  void diaryHasReadableTextAndValidJsonLines() throws IOException {
    germanTurn(true);

    final Path game = diaryFiles().get(0);
    final String text = Files.readString(game.resolve("diario.txt"), StandardCharsets.UTF_8);
    // copy kept for people to look at: build/proplus/diario-esempio/
    final Path sample = Path.of("build", "proplus", "diario-esempio");
    Files.createDirectories(sample);
    Files.copy(game.resolve("diario.txt"), sample.resolve("diario.txt"), REPLACE_EXISTING);
    Files.copy(
        game.resolve("decisioni.jsonl"), sample.resolve("decisioni.jsonl"), REPLACE_EXISTING);
    assertThat(text)
        .contains("Round 1 - Germania (Asse, Pro+ (AI), rischio Equilibrato: soglie 80/60)")
        .contains("Acquisti:")
        .contains("Attacchi eseguiti:")
        .contains("vittoria stimata")
        .contains("Attacchi scartati:")
        .contains("< soglia minima 60%")
        .contains("di cui VC")
        .contains("(quota VC ")
        .contains("Obiettivi principali del turno")
        .contains("NEUTRALI:")
        .contains("Tempo AI:");
    final List<String> lines =
        Files.readAllLines(game.resolve("decisioni.jsonl"), StandardCharsets.UTF_8);
    assertThat(lines).hasSizeGreaterThan(5);
    final List<JsonObject> events =
        lines.stream().map(l -> JsonParser.parseString(l).getAsJsonObject()).toList();
    assertThat(events)
        .extracting(e -> e.get("evento").getAsString())
        .contains("inizio_partita", "inizio_turno", "acquisto", "attacco", "obiettivi");
    final JsonObject executed =
        events.stream()
            .filter(e -> "attacco".equals(e.get("evento").getAsString()))
            // moves are only recorded, so planned attacks show as "non_eseguito"
            .filter(e -> !"scartato".equals(e.get("stato").getAsString()))
            .findFirst()
            .orElseThrow();
    assertThat(executed.has("win_stimata")).isTrue();
    assertThat(executed.get("soglia_desiderata").getAsDouble()).isEqualTo(80);
    assertThat(executed.getAsJsonObject("valore").has("victory_city")).isTrue();
  }

  @Test
  void withAStrategyPlanDecisionsAreTheSameWithAndWithoutDiaryAndTheDiaryShowsThePlan()
      throws IOException {
    data.setGameName("World War II Europe 1940 2nd Edition");
    final String off = germanTurn(false);
    final String on = germanTurn(true);

    assertThat(on).isEqualTo(off);
    final String text =
        Files.readString(diaryFiles().get(0).resolve("diario.txt"), StandardCharsets.UTF_8);
    Files.writeString(
        Path.of("build", "proplus", "diario-esempio", "diario-con-piano.txt"),
        text,
        StandardCharsets.UTF_8);
    assertThat(text)
        .contains("Stato maggiore Asse: piano A Barbarossa (non ancora fissato), fase R1-2")
        .contains(
            "Fronti -> rischio effettivo (soglie desiderata/minima): Ovest +1 = Audace 72/52;")
        .contains("Est -1 = Cauto 85/62")
        .contains("Obiettivi del piano per Germania: France (peso 15), flotta British (peso 10)")
        .contains("Ruolo: ")
        .contains("fronte Ovest, rischio Audace")
        .contains("OBIETTIVO DEL PIANO")
        .contains("quota piano ");
    final List<JsonObject> events =
        Files.readAllLines(diaryFiles().get(0).resolve("decisioni.jsonl"), StandardCharsets.UTF_8)
            .stream()
            .map(l -> JsonParser.parseString(l).getAsJsonObject())
            .toList();
    final JsonObject turn =
        events.stream()
            .filter(e -> "inizio_turno".equals(e.get("evento").getAsString()))
            .findFirst()
            .orElseThrow();
    assertThat(turn.getAsJsonObject("piano").get("id").getAsString()).isEqualTo("A");
    assertThat(
            turn.getAsJsonObject("piano")
                .getAsJsonObject("fronti")
                .getAsJsonObject("Ovest")
                .get("rischio")
                .getAsString())
        .isEqualTo("Audace");
    final JsonObject france =
        events.stream()
            .filter(e -> "attacco".equals(e.get("evento").getAsString()))
            .filter(e -> "France".equals(e.get("territorio").getAsString()))
            .findFirst()
            .orElseThrow();
    assertThat(france.get("fronte").getAsString()).isEqualTo("Ovest");
    assertThat(france.get("rischio_effettivo").getAsString()).isEqualTo("Audace");
    assertThat(france.get("obiettivo_piano").getAsBoolean()).isTrue();
    // an objective of the plan: the front's minimum, the base level's desired win% (step 3b)
    assertThat(france.get("soglia_desiderata").getAsDouble()).isEqualTo(80);
    assertThat(france.get("soglia_minima").getAsDouble()).isEqualTo(52);
  }

  /** One German turn: phase hooks, purchase (with its attack plan) and combat move. */
  private String germanTurn(final boolean diary) {
    final ProData proData = new ProData(false, new ProPlusTuning());
    final TestAi ai = new TestAi(proData, new SeededBattleCalculator(SEED));
    if (diary) {
      DiaryRecorder.attach(ai);
    }
    final List<String> decisions = new ArrayList<>();
    final ProDecisionRecorder recorder = proData.getRecorder();
    recorder.phaseStart(data, germans, "purchase");

    // attack plan, as the purchase simulation does it
    proData.initializeSimulation(ai, data, germans);
    ai.prepareData(data);
    final Map<?, ProTerritory> plan =
        new ProCombatMoveAi(ai).doCombatMove(recordingMoves(decisions));
    plan.values().stream()
        .filter(p -> !p.getUnits().isEmpty())
        .map(p -> "attack " + p.getTerritory().getName() + " " + unitTypes(p.getUnits()))
        .sorted()
        .forEach(decisions::add);

    // purchase
    proData.initializeSimulation(ai, data, germans);
    ai.prepareData(data);
    new ProPurchaseAi(ai).purchase(recordingPurchase(decisions), data);
    recorder.phaseEnd(data, germans, "purchase", 1);
    recorder.phaseStart(data, germans, "combat");
    recorder.phaseEnd(data, germans, "combat", 1);
    recorder.phaseStart(data, germans, "place");
    recorder.phaseEnd(data, germans, "place", 1);
    return String.join("\n", decisions);
  }

  private static IMoveDelegate recordingMoves(final List<String> decisions) {
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any()))
        .thenAnswer(
            invocation -> {
              final MoveDescription move = invocation.getArgument(0);
              decisions.add(
                  "move "
                      + unitTypes(move.getUnits())
                      + " "
                      + move.getRoute().getAllTerritories().stream()
                          .map(t -> t.getName())
                          .collect(Collectors.joining(">")));
              return Optional.empty();
            });
    return moveDel;
  }

  private static IPurchaseDelegate recordingPurchase(final List<String> decisions) {
    final IPurchaseDelegate purchaseDel = mock(IPurchaseDelegate.class);
    when(purchaseDel.purchase(any()))
        .thenAnswer(
            invocation -> {
              final IntegerMap<ProductionRule> bought = invocation.getArgument(0);
              bought.keySet().stream()
                  .map(r -> "buy " + r.getName() + " x" + bought.getInt(r))
                  .sorted()
                  .forEach(decisions::add);
              return null;
            });
    return purchaseDel;
  }

  private static String unitTypes(final Collection<Unit> units) {
    return units.stream()
        .map(u -> u.getType().getName() + "@" + u.getOwner().getName())
        .sorted()
        .collect(Collectors.joining(","));
  }

  private List<Path> diaryFiles() throws IOException {
    try (Stream<Path> games = Files.list(diaryRoot)) {
      return games.sorted(Comparator.reverseOrder()).toList();
    }
  }

  /** A Pro+ AI with seeded battle dice; the dice restart on every {@link #prepareData}. */
  private static final class TestAi extends AbstractProAi {
    private final SeededBattleCalculator calculator;

    TestAi(final ProData proData, final SeededBattleCalculator calculator) {
      super("Germans", calculator, proData, ProPlusAi.PLAYER_LABEL);
      this.calculator = calculator;
    }

    @Override
    protected void prepareData(final GameData data) {
      calculator.setGameData(data).join();
    }
  }
}
