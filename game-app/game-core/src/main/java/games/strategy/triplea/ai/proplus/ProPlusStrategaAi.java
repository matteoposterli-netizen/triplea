package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IEditDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import java.util.List;

/**
 * "Pro+ Stratega LLM (Europe 1940)" (step STRATEGA LLM): Pro+ nudo (= Hard) plus the LLM strategist
 * ({@link Stratega}) that launches, continues and cancels general joint operations; the engine
 * carries them out and Hard plays the rest. Without a key, on errors or with the game's cap
 * reached, it plays as Hard, with one warning in the game log.
 */
public class ProPlusStrategaAi extends ProPlusAi {
  public static final String PLAYER_LABEL = Stratega.PLAYER_LABEL;

  /** The warning was already written in this game's log by this player (once per game). */
  private boolean warned;

  public ProPlusStrategaAi(final String name, final String playerLabel) {
    super(name, playerLabel);
  }

  @Override
  public void initialize(final PlayerBridge playerBridge, final GamePlayer gamePlayer) {
    super.initialize(playerBridge, gamePlayer);
    try {
      importaChiave();
      Stratega.registra(playerBridge.getGameData(), gamePlayer);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Stratega: registrazione fallita: " + e);
    }
  }

  @Override
  protected void purchase(
      final boolean purchaseForBid,
      final int pusToSpend,
      final IPurchaseDelegate purchaseDelegate,
      final GameData data,
      final GamePlayer player) {
    if (!purchaseForBid) {
      try {
        initializeData();
        prepareData(data);
        attendiCalcolatore(data, player);
        Stratega.registra(data, player);
        final Stratega.Esito result = Stratega.inizioTurno(getProData(), getCalc(), data, player);
        Stratega.diarioTurno(data, result.righe());
        // step R1 (A7): every failure of the strategic call in the game log (this player's turn)
        result
            .avviso()
            .ifPresent(
                text -> {
                  warned = true;
                  commento(text);
                });
        if (result.avviso().isEmpty()
            && data.getProperties().get(Stratega.KEY_AVVISO) instanceof String why) {
          // another nation of the game met the warning first: once in this player's turns too
          avvisa(Stratega.AVVISO + " (" + why.split(":")[0] + ")");
        }
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] Stratega: inizio turno fallito: " + e);
      }
    }
    super.purchase(purchaseForBid, pusToSpend, purchaseDelegate, data, player);
  }

  @Override
  protected void place(
      final boolean placeForBid,
      final IAbstractPlaceDelegate placeDelegate,
      final GameState data,
      final GamePlayer player) {
    super.place(placeForBid, placeDelegate, data, player);
    if (placeForBid) {
      return;
    }
    try {
      final List<String> ended = Stratega.finiteNelTurno(data, player);
      ended.forEach(e -> commento("Stratega LLM: " + e));
      final String line = Stratega.rigaFineTurno(data, player);
      commento(line);
      StrategaDiario.fineTurno(data, player, line);
      Difesa.fineTurno(data, player);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Stratega: riga di fine turno non scritta: " + e);
    }
  }

  /** The warning in the game log, once per game and player. */
  private void avvisa(final String text) {
    if (!warned) {
      warned = true;
      commento(text);
    }
  }

  /** A line in the game log (history), as a comment of this player's turn. */
  private void commento(final String text) {
    ProLogger.info("[Pro+] " + text);
    try {
      if (getPlayerBridge().getRemotePersistentDelegate("edit") instanceof IEditDelegate edit) {
        edit.addComment(text);
      }
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Stratega: commento nel log non scritto: " + e);
    }
  }

  /** {@code chiave-stratega.txt} next to the game, imported into the user profile if no key yet. */
  private static void importaChiave() {
    try {
      final java.nio.file.Path root = games.strategy.engine.ClientFileSystemHelper.getRootFolder();
      games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.carica().importaDa(root);
    } catch (final java.io.IOException | RuntimeException | LinkageError e) {
      // no game folder (tests, autoplay) or no file: nothing to import
    }
  }
}
