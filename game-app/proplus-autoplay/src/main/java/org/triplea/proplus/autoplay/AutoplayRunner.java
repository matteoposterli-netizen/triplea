package org.triplea.proplus.autoplay;

import com.google.gson.JsonObject;
import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.framework.GameRunner;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.engine.random.IRandomSource;
import games.strategy.net.LocalNoOpMessenger;
import games.strategy.net.Messengers;
import games.strategy.net.websocket.ClientNetworkBridge;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.diario.DiarySettings;
import games.strategy.triplea.ai.proplus.strategia.Decisore;
import games.strategy.triplea.ai.proplus.strategia.Decisori;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.ai.proplus.strategia.llm.Modalita;
import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.game.server.HeadlessLaunchAction;

/**
 * Pro+ autoplay: one AI vs AI game without a GUI (Pro+ or Hard per alliance, seeded game dice),
 * with the decision diary and a summary ({@link Riepilogo}) written after every round, so a game
 * stopped by the time limit (or killed) still leaves a partial summary. {@code confronto <folder>}
 * instead writes the comparison of the games in {@code folder} ({@link RiepilogoGenerale}).
 *
 * <p>Same game set-up as the committed bench ({@code ProPlusBenchTest}): map XML parsed directly,
 * no map resources, no autosaves, AI pauses 0.
 */
@Slf4j
public final class AutoplayRunner {
  static final String DIARY_FOLDER = "diario";

  private final AutoplayOptions options;
  private final long start = System.currentTimeMillis();

  /** Step VARIANZA: time per nation and turn, machine (measures only). */
  private final Tempi tempi = new Tempi();

  private volatile boolean finished;

  private AutoplayRunner(final AutoplayOptions options) {
    this.options = options;
  }

  public static void main(final String[] args) {
    int code;
    try {
      if (args.length > 0 && "confronto".equals(args[0])) {
        code = RiepilogoGenerale.run(Arrays.copyOfRange(args, 1, args.length));
      } else if (args.length > 0 && "qualita".equals(args[0])) {
        code = TestQualita.run(Arrays.copyOfRange(args, 1, args.length));
      } else {
        code = new AutoplayRunner(AutoplayOptions.parse(args)).play();
      }
    } catch (final IllegalArgumentException e) {
      log.error(e.getMessage());
      code = 2;
    } catch (final Exception e) {
      log.error("Autoplay fallito", e);
      code = 1;
    }
    // AI and game threads may still be alive: end the process here
    System.exit(code);
  }

  private int play() throws Exception {
    final Path output = options.output().toAbsolutePath();
    Files.createDirectories(output);
    setUp(output.resolve(DIARY_FOLDER));
    final GameData data = GameParser.parse(options.mappa(), false).orElseThrow();
    final Monitor monitor = new Monitor(data);
    configure(data, monitor);

    final Set<Player> players = new LinkedHashSet<>();
    final boolean allHard =
        monitor.alliances().keySet().stream()
            .allMatch(a -> AutoplayOptions.HARD.equals(options.tipo(a)));
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      final String type =
          ProPlusSettings.allianceOf(data, p)
              .map(options::tipo)
              .orElse(allHard ? AutoplayOptions.HARD : AutoplayOptions.PROPLUS);
      players.add(
          AutoplayOptions.HARD.equals(type)
              ? PlayerTypes.PRO_AI.newPlayerWithName(p.getName())
              : AutoplayOptions.STRATEGA.equals(type)
                  ? new games.strategy.triplea.ai.proplus.ProPlusStrategaAi(
                      p.getName(), games.strategy.triplea.ai.proplus.ProPlusStrategaAi.PLAYER_LABEL)
                  : new ProPlusAi(p.getName(), ProPlusAi.PLAYER_LABEL));
    }
    final HeadlessLaunchAction launchAction = new HeadlessLaunchAction(null);
    final ServerGame game =
        new ServerGame(
            data,
            players,
            new HashMap<>(),
            new Messengers(new LocalNoOpMessenger()),
            ClientNetworkBridge.NO_OP_SENDER,
            launchAction);
    game.setDelegateAutosavesEnabled(false);
    seedDice(game, options.seme());
    data.getGameLoader().startGame(game, players, launchAction, null);
    game.setStopGameOnDelegateExecutionStop(true);

    final String gameName = data.getGameName();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  if (!finished) {
                    monitor.snapshotPartialRound();
                    write(gameName, monitor, "parziale: processo interrotto", false, false);
                  }
                }));
    write(gameName, monitor, "in corso", false, false);

    final long limit = options.minuti() * 60_000L;
    String status = "completata";
    int code = 0;
    int round = monitor.currentRound();
    log.info("AUTOPLAY {} - round {}", Riepilogo.name(options), round);
    try {
      while (!game.isGameOver() && monitor.currentRound() <= options.round()) {
        if (System.currentTimeMillis() - start > limit) {
          status = "parziale: superato il limite di " + options.minuti() + " minuti";
          break;
        }
        monitor.beforeStep();
        final var step = game.getData().getSequence().getStep();
        final String stepNation = step.getPlayerId() == null ? "" : step.getPlayerId().getName();
        final int stepRound = game.getData().getSequence().getRound();
        final long stepStart = System.nanoTime();
        game.runNextStep();
        tempi.step(
            stepNation, stepRound, step.getName(), (System.nanoTime() - stepStart) / 1_000_000);
        monitor.afterStep();
        if (monitor.currentRound() != round) {
          round = monitor.currentRound();
          log.info("AUTOPLAY round {} after {} s", round, seconds());
          write(gameName, monitor, "in corso", false, false);
        }
      }
    } catch (final RuntimeException e) {
      log.error("Errore durante la partita", e);
      status = "errore: " + e;
      code = 1;
    }
    if (!status.equals("completata") || monitor.currentRound() <= options.round()) {
      monitor.snapshotPartialRound();
    }
    finished = true;
    write(gameName, monitor, status, true, game.isGameOver());
    log.info("AUTOPLAY done in {} s: {}", seconds(), status);
    try {
      game.stopGame();
    } catch (final RuntimeException e) {
      log.warn("stopGame: {}", e.toString());
    }
    return code;
  }

  /** Plans and risk levels in the game properties, as the Pro+ settings dialog does. */
  private void configure(final GameData data, final Monitor monitor) {
    for (final String alliance : monitor.alliances().keySet()) {
      if (AutoplayOptions.HARD.equals(options.tipo(alliance))) {
        continue;
      }
      options
          .piano(alliance)
          .ifPresent(plan -> StatoMaggiore.setChoice(data.getProperties(), alliance, plan));
      ProPlusSettings.setRiskLevel(
          data.getProperties(),
          alliance,
          options.rischio(alliance).map(AutoplayRunner::riskLevel).orElse(RiskLevel.DEFAULT));
      // strategic contract: the decider of the alliance (rules, or directives from a file)
      final String decider = options.decisore(alliance);
      if (decider.startsWith("file:")) {
        Decisori.set(
            data.getProperties(), alliance, Decisore.FILE, decider.substring("file:".length()));
      } else if (decider.startsWith(AutoplayOptions.LLM)) {
        // LLM strategist through the API key (Claude Haiku: ANTHROPIC_API_KEY of the environment);
        // "llm:<model>" asks another model of the provider (one LLM alliance per game)
        Decisori.set(data.getProperties(), alliance, Modalita.CHIAVE_API.id(), "");
        if (decider.startsWith(AutoplayOptions.LLM + ":")) {
          System.setProperty(
              games.strategy.triplea.ai.proplus.strategia.llm.ClientApi.MODEL_PROPERTY,
              decider.substring(AutoplayOptions.LLM.length() + 1));
        }
      } else {
        Decisori.set(data.getProperties(), alliance, Decisore.REGOLE, "");
      }
      // step ABLAZIONE: Pro+ components turned off for the alliance
      games.strategy.triplea.ai.proplus.Ablazione.set(
          data.getProperties(), alliance, options.ablazione(alliance));
      // step PROVA DI VALORE: the joint operation turned on for the alliance (default off)
      games.strategy.triplea.ai.proplus.OperazioneCongiunta.set(
          data.getProperties(), alliance, options.operazione(alliance));
      // step PROVALLM: an LLM at the operation's decision points (default: the v2 formula)
      games.strategy.triplea.ai.proplus.DecisoreOperazione.set(
          data.getProperties(), alliance, options.decisoreOperazione(alliance));
    }
    games.strategy.triplea.ai.proplus.DecisoreOperazione.setBudget(
        data.getProperties(), options.budgetOperazioni());
    if (monitor.alliances().keySet().stream()
        .anyMatch(a -> AutoplayOptions.STRATEGA.equals(options.tipo(a)))) {
      // step STRATEGA LLM: the cap of the game for both levels of the strategist
      games.strategy.triplea.ai.proplus.Stratega.setBudget(
          data.getProperties(), options.budgetOperazioni());
      // step R1: the rule-based baseline instead of the LLM (both levels, no network)
      if (options.strategaRegole()) {
        System.setProperty("proplus.stratega.regole", "true");
      }
      // step R0: the strategist's switches turned off (default: all on)
      for (final String alliance : monitor.alliances().keySet()) {
        if (AutoplayOptions.STRATEGA.equals(options.tipo(alliance))) {
          games.strategy.triplea.ai.proplus.StrategaInterruttori.set(
              data.getProperties(), alliance, options.strategaSpento(alliance));
          games.strategy.triplea.ai.proplus.StrategaInterruttori.accendi(
              data.getProperties(), alliance, options.strategaAcceso(alliance));
        }
      }
      // step C2: the cap of the PUs to the strategy (default: the data file)
      if (options.tettoStrategia() != null) {
        for (final String alliance : monitor.alliances().keySet()) {
          if (AutoplayOptions.STRATEGA.equals(options.tipo(alliance))) {
            games.strategy.triplea.ai.proplus.Stratega.setTettoStrategia(
                data.getProperties(), alliance, options.tettoStrategia());
          }
        }
      }
    }
    for (final String alliance : options.tipi().keySet()) {
      if (!monitor.alliances().containsKey(alliance)) {
        throw new IllegalArgumentException(
            "Alleanza sconosciuta: " + alliance + " (mappa: " + monitor.alliances().keySet() + ")");
      }
    }
  }

  static RiskLevel riskLevel(final String text) {
    try {
      return RiskLevel.fromLevel(Integer.parseInt(text))
          .orElseThrow(() -> new IllegalArgumentException("Rischio 1-5, trovato: " + text));
    } catch (final NumberFormatException e) {
      return Arrays.stream(RiskLevel.values())
          .filter(r -> r.getLabel().toLowerCase(Locale.ROOT).equals(text.toLowerCase(Locale.ROOT)))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Rischio sconosciuto: " + text));
    }
  }

  private synchronized void write(
      final String gameName,
      final Monitor monitor,
      final String status,
      final boolean last,
      final boolean gameOver) {
    try {
      final Path output = options.output().toAbsolutePath();
      final Optional<String> winner = last ? monitor.winner() : Optional.empty();
      final JsonObject json =
          Riepilogo.build(
              options,
              gameName,
              monitor,
              status,
              seconds(),
              winner,
              gameOver,
              Riepilogo.diaryFolder(output.resolve(DIARY_FOLDER)).orElse(null));
      json.add("tempi", tempi.toJson());
      Riepilogo.write(output, json);
    } catch (final IOException | RuntimeException e) {
      log.error("Riepilogo non scritto", e);
    }
  }

  private long seconds() {
    return (System.currentTimeMillis() - start) / 1000;
  }

  private static void setUp(final Path diary) throws IOException {
    HeadlessLaunchAction.setSkipMapResourceLoading(true);
    ClientSetting.setPreferences(new MemoryPreferences());
    ClientSetting.aiMovePauseDuration.setValue(0);
    ClientSetting.aiCombatStepPauseDuration.setValue(0);
    final Path root = Files.createTempDirectory("proplus-autoplay");
    Files.writeString(root.resolve(".triplea-root"), "");
    Files.createDirectories(root.resolve("assets"));
    ClientFileSystemHelper.setCodeSourceFolder(root);
    System.setProperty(GameRunner.TRIPLEA_HEADLESS, "true");
    Files.createDirectories(diary);
    System.setProperty(DiarySettings.FOLDER_PROPERTY, diary.toString());
  }

  /** Game dice from a seeded source (the battle simulations of the AI stay random). */
  private static void seedDice(final ServerGame game, final long seed) throws Exception {
    final Random random = new Random(seed);
    final IRandomSource source =
        new IRandomSource() {
          @Override
          public int getRandom(final int max, final String annotation) {
            return random.nextInt(max);
          }

          @Override
          public int[] getRandom(final int max, final int count, final String annotation) {
            final int[] values = new int[count];
            for (int i = 0; i < count; i++) {
              values[i] = random.nextInt(max);
            }
            return values;
          }
        };
    final Field field = ServerGame.class.getDeclaredField("randomSource");
    field.setAccessible(true);
    field.set(game, source);
  }
}
