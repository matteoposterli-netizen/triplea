package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.ai.proplus.diario.DiarySettings;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.java.collections.IntegerMap;

/**
 * Step 3b: purchase and staging orders of the strategy plans (Pro+ only). Global 1940 renamed as
 * Europe 1940 (same European names and unit types), Germans round 1, seeded battle dice.
 */
class PlanLogisticsTest extends AbstractClientSettingTestCase {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private static final long SEED = 20261107L;

  @TempDir Path diaryRoot;
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");

  @BeforeEach
  void setUp() {
    System.setProperty(DiarySettings.FOLDER_PROPERTY, diaryRoot.toString());
    data.getSequence().setRoundAndStep(1, "Combat Move", germans);
    data.setGameName(EUROPE);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(DiarySettings.FOLDER_PROPERTY);
  }

  @Test
  void hardAndTheNeutralPlanGiveNoOrders() {
    final ProData hard = new ProData();
    hard.initializeSimulation(null, data, germans);
    assertThat(hard.getTuning().purchaseOrders(hard)).isEmpty();
    assertThat(hard.getTuning().stagingOrders(hard)).isEmpty();

    final ProData neutral = proPlus("N");
    assertThat(neutral.getTuning().purchaseOrders(neutral)).isEmpty();
    assertThat(neutral.getTuning().stagingOrders(neutral)).isEmpty();
  }

  @Test
  void theNeutralPlanBuysAsWithoutAStrategyFile() {
    final Purchase neutral = purchase("N");
    data.setGameName("Global 1940 senza strategia");
    final Purchase noFile = purchase("A");

    assertThat(neutral.orders()).isNull();
    assertThat(neutral.bought()).isEqualTo(noFile.bought());
  }

  @Test
  void barbarossaSpendsWhatIsLeftAfterDefenseOnItsMixPlacedInGermany() {
    final Purchase barbarossa = purchase("A");

    final ProPurchaseOrders.Result result = barbarossa.result();
    assertThat(barbarossa.orders().label()).isEqualTo("A Barbarossa, Germans R1-2");
    assertThat(result.budget()).isPositive();
    // each category within one unit of its target
    assertThat((double) result.spent().get("fanteria"))
        .isCloseTo(target(result, "fanteria"), within(3));
    assertThat((double) result.spent().get("artiglieria"))
        .isCloseTo(target(result, "artiglieria"), within(4));
    assertThat((double) result.spent().get("carri_mech"))
        .isCloseTo(target(result, "carri_mech"), within(6));
    assertThat(result.units().get("carri_mech")).containsKeys("armour", "mech_infantry");
    assertThat(result.placements()).containsOnlyKeys("Germany");
    assertThat(result.left()).isLessThan(10);
    assertThat(barbarossa.bought()).containsKeys("buyInfantry", "buyArtillery", "buyArmour");
  }

  @Test
  void seaLionBuysThreeTransportsAndAnEscortFirstAndPlacesThemAtSea() {
    final Purchase seaLion = purchase("B");

    final ProPurchaseOrders.Result result = seaLion.result();
    assertThat(result.units().get("trasporti")).containsEntry("transport", 3);
    // 30 PUs: 3 transports (21) leave too little for a carrier (16), so a destroyer
    assertThat(result.units().get("scorta")).containsEntry("destroyer", 1);
    assertThat(result.mandatoryMissing()).isEmpty();
    final Map<String, Integer> shipsBySeaZone = new TreeMap<>();
    result
        .placements()
        .forEach(
            (t, units) -> {
              if (data.getMap().getTerritoryOrThrow(t).isWater()) {
                units.forEach((u, n) -> shipsBySeaZone.merge(u, n, Integer::sum));
              }
            });
    assertThat(shipsBySeaZone).containsEntry("transport", 3).containsEntry("destroyer", 1);
    assertThat(seaLion.bought().get("buyTransport")).isGreaterThanOrEqualTo(3);
  }

  @Test
  void seaLionRound2BuysTwoTransportsAndAircraft() {
    data.getSequence().setRoundAndStep(2, "Combat Move", germans);
    final Purchase seaLion = purchase("B");

    final ProPurchaseOrders.Result result = seaLion.result();
    // step OPERAZIONI ANFIBIE: the operation "Leone marino" may add its purchases to the label
    assertThat(seaLion.orders().label()).startsWith("B Sea Lion, Germans R2");
    assertThat(result.units().get("trasporti")).containsEntry("transport", 2);
    assertThat(result.units()).containsKey("aerei");
  }

  @Test
  void seaLionStagesTheAirUnitsTooBarbarossaDoesNot() throws IOException {
    data.getSequence().setRoundAndStep(1, "Non Combat Move", germans);
    final AirCapture seaLion = airStaging("B");
    final AirCapture barbarossa = airStaging("A");

    assertThat(barbarossa.result).isNull();
    assertThat(seaLion.result).isNotNull();
    assertThat(seaLion.result.closer() + seaLion.result.same() + seaLion.result.farther())
        .isPositive();
    assertThat(seaLion.result.farther()).isLessThanOrEqualTo(seaLion.result.closer());
  }

  @Test
  void stagingAddsAPullThatFadesWithDistance() {
    final ProData proData = proPlus("A");
    final Territory poland = territory("Poland");
    final Territory germany = territory("Germany");
    final Territory westernGermany = territory("Western Germany");
    final Map<Territory, Double> land = new HashMap<>();
    List.of(poland, germany, westernGermany).forEach(t -> land.put(t, 4.0));
    final Map<Territory, Double> sea = new HashMap<>();

    final ProStagingOrders orders = proData.getTuning().stagingOrders(proData).orElseThrow();
    final ProStagingOrders.Result result =
        ProStaging.apply(proData, orders, List.copyOf(land.keySet()), land, sea);

    assertThat(orders.territories()).containsExactly("Poland", "Slovakia Hungary", "Romania");
    assertThat(result.landScale()).isEqualTo(4);
    assertThat(land.get(poland)).isEqualTo(4 + 4);
    assertThat(land.get(germany)).isEqualTo(4 + 4 / 2.0);
    assertThat(land.get(westernGermany)).isEqualTo(4 + 4 / 3.0);
    assertThat(result.distance()).containsEntry(westernGermany, 2);
  }

  @Test
  void stagingMovesReservesEastAndTheDiaryChangesNothing() throws IOException {
    data.getSequence().setRoundAndStep(1, "Non Combat Move", germans);
    final List<String> barbarossaOff = nonCombat("A", false);
    final List<String> barbarossaOn = nonCombat("A", true);
    final List<String> neutral = nonCombat("N", false);

    assertThat(barbarossaOn).isEqualTo(barbarossaOff);
    assertThat(barbarossaOff).isNotEqualTo(neutral);
    assertThat(unitsEndingIn(barbarossaOff, "Poland", "Slovakia Hungary", "Romania"))
        .isGreaterThan(unitsEndingIn(neutral, "Poland", "Slovakia Hungary", "Romania"));
    final String text = diaryText();
    assertThat(text)
        .contains("Ammassamento (A Barbarossa, Germans R1-2) verso il fronte Est")
        .contains("unita' mosse")
        .contains("Perche': dopo i movimenti di difesa");
  }

  @Test
  void theDiaryComparesTheMixWithWhatWasBought() throws IOException {
    final Purchase off = purchase("A");
    final Purchase on = purchaseWithDiary("A");

    assertThat(on.bought()).isEqualTo(off.bought());
    final String text = diaryText();
    Files.createDirectories(Path.of("build", "proplus", "diario-esempio"));
    Files.writeString(
        Path.of("build", "proplus", "diario-esempio", "diario-mix.txt"),
        text,
        StandardCharsets.UTF_8);
    assertThat(text)
        .contains("mix del piano dello stato maggiore")
        .contains("Mix del piano (A Barbarossa, Germans R1-2)")
        .contains("- fanteria: obiettivo 35% = ")
        .contains("reale nel turno")
        .contains("Piazzamento del piano: Germany");
  }

  // ------------------------------------------------------------------------------- helpers

  private record Purchase(
      Map<String, Integer> bought,
      ProPurchaseOrders orders,
      ProPurchaseOrders.Result result,
      Map<Territory, ProPurchaseTerritory> territories) {}

  private ProData proPlus(final String plan) {
    StatoMaggiore.setChoice(data.getProperties(), "Axis", plan);
    final ProData proData = new ProData(false, new ProPlusTuning());
    proData.initializeSimulation(null, data, germans);
    return proData;
  }

  private Purchase purchase(final String plan) {
    return purchase(plan, false);
  }

  private Purchase purchaseWithDiary(final String plan) {
    return purchase(plan, true);
  }

  private Purchase purchase(final String plan, final boolean diary) {
    StatoMaggiore.setChoice(data.getProperties(), "Axis", plan);
    final ProData proData = new ProData(false, new ProPlusTuning());
    final TestAi ai = new TestAi(proData, new SeededBattleCalculator(SEED));
    if (diary) {
      DiaryRecorder.attach(ai);
    }
    final Capture capture = new Capture(proData.getRecorder());
    proData.setRecorder(capture);
    capture.phaseStart(data, germans, "purchase");
    proData.initializeSimulation(ai, data, germans);
    ai.prepareData(data);
    final Map<String, Integer> bought = new TreeMap<>();
    final Map<Territory, ProPurchaseTerritory> territories =
        new ProPurchaseAi(ai).purchase(recordingPurchase(bought), data);
    capture.phaseEnd(data, germans, "purchase", 1);
    capture.phaseStart(data, germans, "place");
    capture.phaseEnd(data, germans, "place", 1);
    return new Purchase(bought, capture.orders, capture.result, territories);
  }

  private List<String> nonCombat(final String plan, final boolean diary) {
    StatoMaggiore.setChoice(data.getProperties(), "Axis", plan);
    final ProData proData = new ProData(false, new ProPlusTuning());
    final TestAi ai = new TestAi(proData, new SeededBattleCalculator(SEED));
    if (diary) {
      DiaryRecorder.attach(ai);
    }
    final ProDecisionRecorder recorder = proData.getRecorder();
    recorder.phaseStart(data, germans, "noncombat");
    proData.initializeSimulation(ai, data, germans);
    ai.prepareData(data);
    final List<String> moves = new ArrayList<>();
    new ProNonCombatMoveAi(ai).doNonCombatMove(null, null, recordingMoves(moves));
    recorder.phaseEnd(data, germans, "noncombat", 1);
    recorder.phaseStart(data, germans, "place");
    recorder.phaseEnd(data, germans, "place", 1);
    return moves;
  }

  private AirCapture airStaging(final String plan) {
    StatoMaggiore.setChoice(data.getProperties(), "Axis", plan);
    final ProData proData = new ProData(false, new ProPlusTuning());
    final TestAi ai = new TestAi(proData, new SeededBattleCalculator(SEED));
    final AirCapture capture = new AirCapture();
    proData.setRecorder(capture);
    proData.initializeSimulation(ai, data, germans);
    ai.prepareData(data);
    new ProNonCombatMoveAi(ai).doNonCombatMove(null, null, recordingMoves(new ArrayList<>()));
    return capture;
  }

  /** Keeps the air staging result of the non-combat move. */
  private static final class AirCapture implements ProDecisionRecorder {
    private ProStagingOrders.AirResult result;

    @Override
    public void airStaging(
        final ProData proData,
        final ProStagingOrders orders,
        final ProStagingOrders.AirResult result) {
      this.result = result;
    }
  }

  private static long unitsEndingIn(final List<String> moves, final String... territories) {
    return moves.stream()
        .filter(m -> List.of(territories).contains(m.substring(m.lastIndexOf('>') + 1)))
        .mapToLong(m -> m.split(" ")[1].split(",").length)
        .sum();
  }

  private static IMoveDelegate recordingMoves(final List<String> moves) {
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any()))
        .thenAnswer(
            invocation -> {
              final MoveDescription move = invocation.getArgument(0);
              moves.add(
                  "move "
                      + move.getUnits().stream()
                          .map(u -> u.getType().getName())
                          .sorted()
                          .collect(Collectors.joining(","))
                      + " "
                      + move.getRoute().getAllTerritories().stream()
                          .map(Territory::getName)
                          .collect(Collectors.joining(">")));
              return Optional.empty();
            });
    return moveDel;
  }

  private static IPurchaseDelegate recordingPurchase(final Map<String, Integer> bought) {
    final IPurchaseDelegate purchaseDel = mock(IPurchaseDelegate.class);
    when(purchaseDel.purchase(any()))
        .thenAnswer(
            invocation -> {
              final IntegerMap<ProductionRule> rules = invocation.getArgument(0);
              rules.keySet().forEach(r -> bought.put(r.getName(), rules.getInt(r)));
              return null;
            });
    return purchaseDel;
  }

  private static double target(final ProPurchaseOrders.Result result, final String category) {
    return result.targets().get(category);
  }

  private static org.assertj.core.data.Offset<Double> within(final double offset) {
    return org.assertj.core.data.Offset.offset(offset);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private String diaryText() throws IOException {
    try (Stream<Path> games = Files.list(diaryRoot)) {
      final Path game = games.findFirst().orElseThrow();
      return Files.readString(game.resolve("diario.txt"), StandardCharsets.UTF_8);
    }
  }

  /** Passes every call to the diary (or no-op) and keeps the plan's purchase orders. */
  private static final class Capture implements ProDecisionRecorder {
    private final ProDecisionRecorder next;
    private ProPurchaseOrders orders;
    private ProPurchaseOrders.Result result;

    Capture(final ProDecisionRecorder next) {
      this.next = next;
    }

    @Override
    public void phaseStart(final GameState data, final GamePlayer player, final String phase) {
      next.phaseStart(data, player, phase);
    }

    @Override
    public void phaseEnd(
        final GameState data, final GamePlayer player, final String phase, final long millis) {
      next.phaseEnd(data, player, phase, millis);
    }

    @Override
    public void purchaseOrders(
        final ProData proData,
        final ProPurchaseOrders orders,
        final ProPurchaseOrders.Result result) {
      this.orders = orders;
      this.result = result;
      next.purchaseOrders(proData, orders, result);
    }

    @Override
    public void purchaseStep(
        final ProData proData,
        final String step,
        final Map<Territory, ProPurchaseTerritory> purchaseTerritories,
        final Map<Territory, ProPurchaseTerritory> factoryTerritories) {
      next.purchaseStep(proData, step, purchaseTerritories, factoryTerritories);
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
