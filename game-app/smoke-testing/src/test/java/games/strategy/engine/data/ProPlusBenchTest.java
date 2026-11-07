package games.strategy.engine.data;

import static org.mockito.Mockito.mock;

import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.player.Player;
import games.strategy.engine.random.IRandomSource;
import games.strategy.net.LocalNoOpMessenger;
import games.strategy.net.Messengers;
import games.strategy.net.websocket.ClientNetworkBridge;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.diario.DiarySettings;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.triplea.game.server.HeadlessGameServer;
import org.triplea.game.server.HeadlessLaunchAction;

/**
 * Pro+ bench (not part of the normal test run): a real game with every nation played by Pro+,
 * seeded game dice. Enabled only with the environment variable {@code PROPLUS_BANCO_XML} (path of
 * the map XML, eg. WW2 Europe 1940 2nd Edition). Other variables: {@code PROPLUS_BANCO_ROUNDS}
 * (default 4), {@code PROPLUS_BANCO_PIANI} ("Axis=A,Allies=1"), {@code PROPLUS_BANCO_SEED} (default
 * 1), {@code PROPLUS_BANCO_DIARIO} (diary folder). The decision diary is the output.
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "PROPLUS_BANCO_XML", matches = ".+")
class ProPlusBenchTest {
  @Test
  void playRounds() throws Exception {
    GameTestUtils.setUp();
    final String diary = System.getenv("PROPLUS_BANCO_DIARIO");
    if (diary != null) {
      System.setProperty(DiarySettings.FOLDER_PROPERTY, diary);
    }
    final int rounds = Integer.parseInt(env("PROPLUS_BANCO_ROUNDS", "4"));
    final long seed = Long.parseLong(env("PROPLUS_BANCO_SEED", "1"));
    final GameData data =
        GameParser.parse(Path.of(System.getenv("PROPLUS_BANCO_XML")), false).orElseThrow();
    for (final String choice : env("PROPLUS_BANCO_PIANI", "Axis=A,Allies=1").split(",")) {
      final String[] kv = choice.split("=");
      StatoMaggiore.setChoice(data.getProperties(), kv[0].trim(), kv[1].trim());
    }
    final Set<Player> players = new LinkedHashSet<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      players.add(new ProPlusAi(p.getName(), ProPlusAi.PLAYER_LABEL));
    }
    final HeadlessLaunchAction launchAction =
        new HeadlessLaunchAction(mock(HeadlessGameServer.class));
    final ServerGame game =
        new ServerGame(
            data,
            players,
            new HashMap<>(),
            new Messengers(new LocalNoOpMessenger()),
            ClientNetworkBridge.NO_OP_SENDER,
            launchAction);
    game.setDelegateAutosavesEnabled(false);
    seedDice(game, seed);
    data.getGameLoader().startGame(game, players, launchAction, null);
    game.setStopGameOnDelegateExecutionStop(true);
    final long start = System.currentTimeMillis();
    int lastRound = 0;
    while (!game.isGameOver() && data.getSequence().getRound() <= rounds) {
      if (data.getSequence().getRound() != lastRound) {
        lastRound = data.getSequence().getRound();
        log.info(
            "BANCO round {} after {} s", lastRound, (System.currentTimeMillis() - start) / 1000);
      }
      game.runNextStep();
    }
    log.info("BANCO done in {} s", (System.currentTimeMillis() - start) / 1000);
    game.stopGame();
  }

  private static String env(final String name, final String fallback) {
    final String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
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
