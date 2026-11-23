package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;

/**
 * "Pro+ (Europe 1940)" (step PF1): the one recommended Pro+ player type of new games. It plays
 * exactly as the configurations measured in the autoplay (steps P11-P13: player type "stratega"
 * without a key): Pro+ nudo (= Hard) plus the switches of the two boxes of the panel, "Alleati
 * consigliati" and "Asse consigliato" (user profile, on by default). Never an LLM: both levels of
 * the strategist fall back to the rules as in the autoplay without a key, whatever key or mode the
 * user saved.
 */
public class ProPlusEuropa1940Ai extends ProPlusStrategaAi {
  public static final String PLAYER_LABEL = "Pro+ (Europe 1940)";

  public ProPlusEuropa1940Ai(final String name, final String playerLabel) {
    super(name, playerLabel);
  }

  @Override
  protected void registra(final GameState data, final GamePlayer player) {
    final ProfiloStratega profile = ProfiloStratega.carica();
    Stratega.registraEuropa1940(
        data, player, profile.alleatiConsigliati(), profile.asseConsigliato());
  }

  @Override
  protected boolean conLlm() {
    return false;
  }
}
