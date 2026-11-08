package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.ai.proplus.strategia.Strategia;
import games.strategy.triplea.ai.proplus.strategia.Strategie;
import games.strategy.triplea.ai.proplus.strategia.Tattiche;
import games.strategy.triplea.ai.proplus.strategia.Valutazione;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Strategic contract: proves that routing Pro+'s strategic choices through the decider left Pro+
 * unchanged. Global 1940 renamed as Europe 1940 (the strategy file applies), Axis plan A, Allies
 * plan 1, Equilibrato. It records:
 *
 * <ul>
 *   <li>the general staff of both alliances at the start of rounds 1-10 on a board changed by a
 *       script (France, Egypt, Romania and Soviet territories change hands), so progress, crisis,
 *       plan change and posture move: the strategic state and, for every nation, what the tactics
 *       read from the plan (risk per front, objectives, operations, posture, enemy reading, delay
 *       tools);
 *   <li>for the round-1 turns of Germans, Russians and British: the attacks Pro+ can win (seeded
 *       battle dice) with their thresholds and plan bonus, the attack value of every territory, the
 *       purchase and staging orders.
 * </ul>
 *
 * <p>Final purchases are not recorded: the Pro AI picks among purchase options with {@code
 * Math.random} ({@code ProPurchaseUtils.randomizePurchaseOption}), also in Hard.
 */
class ProPlusGoldenTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261108L;
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private static final String GOLDEN = "/proplus/proplus-golden-global1940-europe.txt";
  private static final List<String> NATIONS =
      List.of(
          "Germans",
          "Russians",
          "Japanese",
          "Americans",
          "Chinese",
          "British",
          "Italians",
          "ANZAC",
          "French");

  @Test
  void proPlusDecisionsAreTheOnesRecordedBeforeTheContract() throws IOException {
    final String actual = report();
    final Path out = Path.of("build", "proplus", "proplus-golden-actual.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, actual);

    try (InputStream golden = ProPlusGoldenTest.class.getResourceAsStream(GOLDEN)) {
      assertThat(golden).as("golden report " + GOLDEN).isNotNull();
      assertThat(actual).isEqualTo(new String(golden.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void theReportIsReproducible() throws IOException {
    final String first = report();
    final String second = report();
    Files.createDirectories(Path.of("build", "proplus"));
    Files.writeString(Path.of("build", "proplus", "proplus-golden-1.txt"), first);
    Files.writeString(Path.of("build", "proplus", "proplus-golden-2.txt"), second);
    assertThat(second).isEqualTo(first);
  }

  /**
   * The same report on the real Europe 1940 map, round 1 (general staff, orders of every nation,
   * attack options): only with {@code PROPLUS_GOLDEN_EUROPE_XML} (path of
   * ww2eur40_2nd_edition.xml), written to build/proplus/proplus-golden-europe1940.txt to compare
   * before/after a change by hand (the map is not in the repository).
   */
  @Test
  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
      named = "PROPLUS_GOLDEN_EUROPE_XML",
      matches = ".+")
  void realEurope1940RoundOneReport() throws IOException {
    final java.util.function.Supplier<GameData> load =
        () -> {
          final GameData data =
              games.strategy.engine.data.gameparser.GameParser.parse(
                      Path.of(System.getenv("PROPLUS_GOLDEN_EUROPE_XML")), false)
                  .orElseThrow();
          prepare(data);
          return data;
        };
    final GameData data = load.get();
    final StringBuilder sb = new StringBuilder();
    for (final String first : List.of("Germans", "British")) {
      final GamePlayer player = player(data, first);
      data.getSequence().setRoundAndStep(1, "Combat Move", player);
      Valutazione.inizioRound(data, player);
    }
    for (final String alliance : List.of("Axis", "Allies")) {
      final StatoMaggiore.Stato state =
          StatoMaggiore.getState(data.getProperties(), alliance).orElseThrow();
      sb.append(alliance)
          .append(' ')
          .append(state.storico().get(state.storico().size() - 1))
          .append('\n');
    }
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      data.getSequence().setRoundAndStep(1, "Combat Move", player);
      PianoAttivo.of(data, player).ifPresent(p -> sb.append(planView(player.getName(), p)));
    }
    for (final String nation : List.of("Germans", "Russians", "British", "Italians", "Americans")) {
      sb.append(turn(data, player(data, nation)));
    }
    final Path out = Path.of("build", "proplus", "proplus-golden-europe1940.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString());
  }

  static String report() {
    final StringBuilder sb = new StringBuilder();
    sb.append(turns());
    sb.append(staffOverRounds());
    return sb.toString();
  }

  // ------------------------------------------------------------------------ general staff

  private static String staffOverRounds() {
    final GameData data = europe();
    final StringBuilder sb = new StringBuilder();
    for (int round = 1; round <= 10; round++) {
      script(data, round);
      for (final String first : List.of("Germans", "British")) {
        final GamePlayer player = player(data, first);
        data.getSequence().setRoundAndStep(round, "Combat Move", player);
        Valutazione.inizioRound(data, player);
      }
      for (final String alliance : List.of("Axis", "Allies")) {
        final StatoMaggiore.Stato state =
            StatoMaggiore.getState(data.getProperties(), alliance).orElseThrow();
        final JsonObject last = state.storico().get(state.storico().size() - 1).getAsJsonObject();
        sb.append("R")
            .append(round)
            .append(' ')
            .append(alliance)
            .append(" piano ")
            .append(state.piano())
            .append(" cambi ")
            .append(state.cambi())
            .append('\n');
        sb.append("  avanzamento ").append(last.get("avanzamento")).append('\n');
        sb.append("  lettura ").append(last.get("lettura")).append('\n');
        sb.append("  stato_strategico ").append(last.get("stato_strategico")).append('\n');
      }
      for (final String nation : NATIONS) {
        final GamePlayer player = player(data, nation);
        data.getSequence().setRoundAndStep(round, "Combat Move", player);
        PianoAttivo.of(data, player).ifPresent(p -> sb.append(planView(nation, p)));
      }
    }
    return sb.toString();
  }

  /** Ownership changes before the start of {@code round}. */
  private static void script(final GameData data, final int round) {
    switch (round) {
      case 2 -> owner(data, "France", "Germans");
      case 3 -> {
        owner(data, "Belarus", "Germans");
        owner(data, "Western Ukraine", "Germans");
      }
      case 4 -> {
        owner(data, "Egypt", "Italians");
        owner(data, "Ukraine", "Germans");
      }
      case 5 -> {
        owner(data, "Novgorod", "Germans");
        owner(data, "Smolensk", "Germans");
      }
      case 6 -> {
        owner(data, "Bryansk", "Germans");
        owner(data, "Karelia", "Germans");
      }
      case 7 -> {
        owner(data, "Romania", "Russians");
        owner(data, "Caucasus", "Germans");
      }
      case 8 -> owner(data, "Tambov", "Germans");
      case 9 -> owner(data, "Volgograd", "Germans");
      case 10 -> owner(data, "Archangel", "Germans");
      default -> {}
    }
  }

  /** What the tactics read from the general staff's orders for {@code nation}. */
  private static String planView(final String nation, final PianoAttivo p) {
    final StringBuilder sb = new StringBuilder("  ").append(nation).append(": ");
    sb.append(p.label()).append(", postura ").append(p.getPostura());
    sb.append(", difensiva ").append(p.isDifensiva());
    sb.append(", fronti");
    p.livelliPerFronte()
        .forEach(
            (f, l) ->
                sb.append(' ')
                    .append(f)
                    .append('=')
                    .append(p.modificatore(f))
                    .append('/')
                    .append(l.getLabel()));
    sb.append(", obiettivi [");
    sb.append(
        p.getObiettivi().stream()
            .map(
                o ->
                    o.label()
                        + " "
                        + o.peso()
                        + (o.critico() ? " critico" : "")
                        + (o.nazioni().isEmpty() ? "" : " " + new java.util.TreeSet<>(o.nazioni())))
            .collect(Collectors.joining(", ")));
    sb.append("], contro [");
    sb.append(
        p.getControObiettivi().stream()
            .map(o -> o.label() + " " + o.peso())
            .collect(Collectors.joining(", ")));
    sb.append("], operazioni [");
    sb.append(
        p.getOperazioni().stream()
            .map(o -> o.nome() + " " + o.bersagli())
            .collect(Collectors.joining(", ")));
    sb.append("], lettura ")
        .append(p.getLetturaNemico())
        .append(' ')
        .append(p.getProbabilitaNemico());
    sb.append(", tattiche [");
    sb.append(
        Tattiche.STRUMENTI.stream()
            .filter(t -> p.tattica(t, (String) null).isPresent())
            .map(t -> t + " " + p.tattica(t, (String) null).get())
            .collect(Collectors.joining(", ")));
    sb.append("], caccia ").append(p.getCaccia().size());
    sb.append(", logistica ").append(p.getLogistica().isPresent());
    return sb.append('\n').toString();
  }

  // -------------------------------------------------------------------------- round-1 turns

  private static String turns() {
    final GameData data = europe();
    for (final String first : List.of("Germans", "British")) {
      final GamePlayer player = player(data, first);
      data.getSequence().setRoundAndStep(1, "Combat Move", player);
      Valutazione.inizioRound(data, player);
    }
    final StringBuilder sb = new StringBuilder();
    for (final String nation : List.of("Germans", "Russians", "British")) {
      sb.append(turn(data, player(data, nation)));
    }
    return sb.toString();
  }

  /**
   * Attack options Pro+ can win (seeded battle dice), their thresholds and priority bonus, the
   * attack value of every territory, purchase and staging orders of one round-1 turn. The full
   * combat move is not recorded: its order follows hash sets of units, which differ between two
   * loads of the map (also for Hard).
   */
  private static String turn(final GameData data, final GamePlayer player) {
    data.getSequence().setRoundAndStep(1, "Combat Move", player);
    final ProData proData = new ProData(false, new ProPlusTuning());
    final TestAi ai = new TestAi(player.getName(), proData, new SeededBattleCalculator(SEED));
    final List<String> decisions = new ArrayList<>();
    proData.initializeSimulation(ai, data, player);
    ai.prepareData(data);
    final ProTuning tuning = proData.getTuning();
    final ProTerritoryManager manager =
        new ProTerritoryManager(
            new games.strategy.triplea.ai.pro.util.ProOddsCalculator(ai.calculator), proData);
    manager.populateAttackOptions();
    final List<ProTerritory> attacks = manager.removeTerritoriesThatCantBeConquered();
    attacks.stream()
        .sorted(java.util.Comparator.comparing(a -> a.getTerritory().getName()))
        .forEach(
            a -> {
              final Territory t = a.getTerritory();
              final double desired =
                  tuning.attackWinPercentage(data, player, proData.getWinPercentage());
              decisions.add(
                  String.format(
                      java.util.Locale.ROOT,
                      "attack %s maxWin%%=%.2f swing=%.2f desired=%.1f target=%.1f min=%.1f"
                          + " bonus=%.2f",
                      t.getName(),
                      a.getMaxBattleResult().getWinPercentage(),
                      a.getMaxBattleResult().getTuvSwing(),
                      desired,
                      tuning.targetAttackWinPercentage(proData, t, desired),
                      tuning.targetAttackMinWinPercentage(
                          proData,
                          t,
                          tuning.attackMinWinPercentage(
                              data, player, proData.getMinWinPercentage())),
                      tuning.attackPriorityPlanBonus(proData, t)));
            });
    data.getMap().getTerritories().stream()
        .sorted(java.util.Comparator.comparing(Territory::getName))
        .forEach(
            t -> {
              final double value =
                  games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils
                      .findTerritoryAttackValue(proData, player, t);
              if (value != 0) {
                decisions.add(
                    String.format(java.util.Locale.ROOT, "value %s %.3f", t.getName(), value));
              }
            });
    proData.initializeSimulation(ai, data, player);
    ai.prepareData(data);
    decisions.add("purchaseOrders " + tuning.purchaseOrders(proData).map(Object::toString));
    final Optional<ProStagingOrders> staging = tuning.stagingOrders(proData);
    decisions.add(
        "stagingOrders "
            + staging
                .map(
                    s ->
                        s.label()
                            + " "
                            + s.front()
                            + " "
                            + s.territories()
                            + " "
                            + new TreeMap<>(s.weights()))
                .orElse("-"));
    decisions.add(
        "capitalGuard "
            + tuning.capitalGuardThreshold(proData)
            + " garrisons "
            + new TreeMap<>(tuning.minimumGarrisons(proData)));
    final StringBuilder sb = new StringBuilder("TURN R1 ").append(player.getName()).append('\n');
    decisions.forEach(d -> sb.append("  ").append(d).append('\n'));
    return sb.toString();
  }

  // ------------------------------------------------------------------------------- helpers

  private static GameData europe() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    data.setGameName(EUROPE);
    prepare(data);
    return data;
  }

  private static void prepare(final GameData data) {
    final Strategia strategia = Strategie.forGame(data).orElseThrow();
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "A");
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    data.getSequence().setRoundAndStep(1, "Combat Move", player(data, "Germans"));
    StatoMaggiore.fix(data, "Axis", strategia);
    StatoMaggiore.fix(data, "Allies", strategia);
  }

  private static GamePlayer player(final GameData data, final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private static void owner(final GameData data, final String territory, final String nation) {
    data.performChange(
        ChangeFactory.changeOwner(
            data.getMap().getTerritoryOrThrow(territory), player(data, nation)));
  }

  /** A Pro+ AI with seeded battle dice; the dice restart on every {@link #prepareData}. */
  private static final class TestAi extends AbstractProAi {
    private final SeededBattleCalculator calculator;

    TestAi(final String name, final ProData proData, final SeededBattleCalculator calculator) {
      super(name, calculator, proData, ProPlusAi.PLAYER_LABEL);
      this.calculator = calculator;
    }

    @Override
    protected void prepareData(final GameData data) {
      calculator.setGameData(data).join();
    }
  }
}
