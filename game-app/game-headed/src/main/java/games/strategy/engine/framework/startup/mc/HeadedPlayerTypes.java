package games.strategy.engine.framework.startup.mc;

import static games.strategy.engine.framework.startup.ui.PlayerTypes.PLAYER_TYPE_HUMAN_LABEL;

import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.TripleAPlayer;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusEuropa1940Ai;
import games.strategy.triplea.ai.proplus.ProPlusStrategaAi;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.settings.ClientSetting;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.triplea.ai.does.nothing.DoesNothingAi;
import org.triplea.ai.flowfield.FlowFieldAi;

public class HeadedPlayerTypes {

  private static final String PLAYER_TYPE_FLOW_FIELD_LABEL = "FlowField (AI)";
  private static final String PLAYER_TYPE_PRO_AI_BETA_LABEL = "Hard Beta (AI)";
  private static final Set<String> BETA_PLAYER_TYPE_LABELS =
      Set.of(PLAYER_TYPE_FLOW_FIELD_LABEL, PLAYER_TYPE_PRO_AI_BETA_LABEL);

  public static final PlayerTypes.Type HUMAN_PLAYER =
      new PlayerTypes.Type(PLAYER_TYPE_HUMAN_LABEL) {
        @Override
        public Player newPlayerWithName(final String name) {
          return new TripleAPlayer(name, getLabel(), false);
        }
      };

  /** A hidden player type to represent network connected players. */
  public static final PlayerTypes.Type CLIENT_PLAYER =
      new PlayerTypes.Type(PlayerTypes.PLAYER_TYPE_DEFAULT_LABEL, false) {
        @Override
        public Player newPlayerWithName(final String name) {
          return new TripleAPlayer(name, getLabel(), true);
        }
      };

  public static final PlayerTypes.Type DOES_NOTHING_PLAYER =
      new PlayerTypes.Type(PlayerTypes.DOES_NOTHING_PLAYER_LABEL) {
        @Override
        public Player newPlayerWithName(String name) {
          return new DoesNothingAi(name, getLabel());
        }
      };

  private static boolean filterBetaPlayerType(final PlayerTypes.Type playerType) {
    if (BETA_PLAYER_TYPE_LABELS.contains(playerType.getLabel())) {
      return ClientSetting.showBetaFeatures.getValue().orElse(false);
    }
    return true;
  }

  private static PlayerTypes.Type getFlowFieldType() {
    return new PlayerTypes.Type(PLAYER_TYPE_FLOW_FIELD_LABEL) {
      @Override
      public Player newPlayerWithName(String name) {
        return new FlowFieldAi(name, getLabel());
      }
    };
  }

  /**
   * Step PF1: "Pro+ (Europe 1940)", the one recommended Pro+ player type (plays as the autoplay
   * configurations of steps P11-P13: Hard + the switches of the two boxes, never an LLM). Listed
   * right after "Hard (AI)".
   */
  public static final PlayerTypes.Type PRO_PLUS_EUROPA_1940_AI =
      new PlayerTypes.Type(ProPlusEuropa1940Ai.PLAYER_LABEL) {
        @Override
        public Player newPlayerWithName(final String name) {
          return new ProPlusEuropa1940Ai(name, getLabel());
        }
      };

  /**
   * Pro+ (fork AI, steps 1-3d: risk slider, general staff). Step PF1: hidden from the list of new
   * games, still loaded from old saved games ({@link #LEGACY_LABELS}).
   */
  public static final PlayerTypes.Type PRO_PLUS_AI =
      new PlayerTypes.Type(ProPlusAi.PLAYER_LABEL, false) {
        @Override
        public Player newPlayerWithName(final String name) {
          return new ProPlusAi(name, getLabel());
        }
      };

  /**
   * Pro+ Stratega LLM (step STRATEGA LLM): Pro+ nudo (= Hard) with the LLM strategist of the joint
   * operations; plays as Hard without an API key. Step PF1: hidden from the list of new games,
   * still loaded from old saved games ({@link #LEGACY_LABELS}).
   */
  public static final PlayerTypes.Type PRO_PLUS_STRATEGA_AI =
      new PlayerTypes.Type(ProPlusStrategaAi.PLAYER_LABEL, false) {
        @Override
        public Player newPlayerWithName(final String name) {
          return new ProPlusStrategaAi(name, getLabel());
        }
      };

  /**
   * Step PF1: hidden player types a saved game may still name; the player selection shows them for
   * that nation only.
   */
  public static final Set<String> LEGACY_LABELS =
      Set.of(ProPlusAi.PLAYER_LABEL, ProPlusStrategaAi.PLAYER_LABEL);

  private static PlayerTypes.Type getProAiBetaType() {
    return new PlayerTypes.Type(PLAYER_TYPE_PRO_AI_BETA_LABEL) {
      @Override
      public Player newPlayerWithName(String name) {
        return DiaryRecorder.attachedTo(new ProAi(name, getLabel(), true));
      }
    };
  }

  public static Collection<PlayerTypes.Type> getPlayerTypes() {
    return Stream.of(
            // The first item in this list will be the default when hosting
            List.of(HUMAN_PLAYER),
            PlayerTypes.getBuiltInPlayerTypes(),
            List.of(
                PRO_PLUS_EUROPA_1940_AI,
                PRO_PLUS_AI,
                PRO_PLUS_STRATEGA_AI,
                getProAiBetaType(),
                CLIENT_PLAYER,
                DOES_NOTHING_PLAYER,
                getFlowFieldType()))
        .flatMap(Collection::stream)
        .filter(HeadedPlayerTypes::filterBetaPlayerType)
        .collect(Collectors.toList());
  }
}
