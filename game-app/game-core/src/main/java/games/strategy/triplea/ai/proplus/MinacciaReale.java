package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.RelationshipTracker;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.attachments.AbstractConditionsAttachment;
import games.strategy.triplea.attachments.ICondition;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Real threat (Pro+, step 3c): the enemies whose units can attack a nation's territories before its
 * next turn. "Hard (AI)" counts every player not allied to the nation and moves its units as if at
 * war (in WW2 Europe 1940 the British count as enemies of the USA, and the Americans of the
 * British, until the USA enters the war). Pro+ counts only:
 *
 * <ul>
 *   <li>players already at war with the nation;
 *   <li>players the plan has the nation declare war on by its next turn ({@code dichiarazioni} of
 *       the plan with {@code dal_round} up to the next round: the declaration is made at the start
 *       of that turn, and the units the nation leaves now are the ones there when the enemy
 *       answers);
 *   <li>players that may declare war on the nation now by the map's rules (a political action of a
 *       player not allied to the nation, whose conditions hold, that sets war between the nation
 *       and that player).
 * </ul>
 *
 * <p>Which units can reach a territory (land movement, transports in range, air range with a place
 * to land) stays the Pro AI's model of enemy attacks. Units the enemy places in its next turn can't
 * attack before the nation plays again, so enemy production is not counted.
 */
@UtilityClass
public final class MinacciaReale {
  public static final String IN_GUERRA = "in guerra";

  /** The real enemies of {@code player} now, in turn order, each with why. */
  public static Map<GamePlayer, String> nemici(
      final GameState data, final GamePlayer player, final Optional<PianoAttivo> plan) {
    final RelationshipTracker tracker = data.getRelationshipTracker();
    final int nextRound = data.getSequence().getRound() + 1;
    final Map<GamePlayer, String> real = new LinkedHashMap<>();
    Set<GamePlayer> mayDeclare = null;
    for (final GamePlayer enemy : ProUtils.getEnemyPlayersInTurnOrder(player)) {
      if (tracker.isAtWar(player, enemy)) {
        real.put(enemy, IN_GUERRA);
        continue;
      }
      final Optional<Integer> planned =
          plan.flatMap(p -> p.getPiano().dichiarazione(player.getName(), enemy.getName()))
              .map(d -> d.dalRound())
              .filter(r -> r <= nextRound);
      if (planned.isPresent()) {
        real.put(enemy, "guerra prevista dal piano dal round " + planned.get());
        continue;
      }
      if (mayDeclare == null) {
        mayDeclare = mayDeclareWarOn(data, player);
      }
      if (mayDeclare.contains(enemy)) {
        real.put(enemy, "puo' dichiarare guerra (regole della mappa)");
      }
    }
    return real;
  }

  /**
   * Players not allied to {@code player} with a political action they may take now (conditions met,
   * attempts left) that puts them at war with {@code player}.
   */
  static Set<GamePlayer> mayDeclareWarOn(final GameState data, final GamePlayer player) {
    final Set<GamePlayer> result = new HashSet<>();
    if (!Properties.getUsePolitics(data.getProperties()) || !(data instanceof GameData gameData)) {
      return result;
    }
    final RelationshipTracker tracker = data.getRelationshipTracker();
    for (final GamePlayer other : data.getPlayerList().getPlayers()) {
      if (other.equals(player) || tracker.isAllied(player, other)) {
        continue;
      }
      final Collection<PoliticalActionAttachment> actions =
          PoliticalActionAttachment.getPoliticalActionAttachments(other);
      if (actions.isEmpty()) {
        continue;
      }
      final Map<ICondition, Boolean> tested;
      try {
        final Set<ICondition> conditions = new HashSet<>();
        actions.forEach(a -> conditions.addAll(a.getConditions()));
        tested =
            AbstractConditionsAttachment.testAllConditionsRecursive(
                AbstractConditionsAttachment.getAllConditionsRecursive(conditions, null),
                null,
                new ProDummyDelegateBridge(null, other, gameData));
      } catch (final RuntimeException e) {
        continue; // conditions that need a real game: not counted
      }
      for (final PoliticalActionAttachment action :
          PoliticalActionAttachment.getValidActions(other, tested, data)) {
        for (final PoliticalActionAttachment.RelationshipChange change :
            action.getRelationshipChanges()) {
          if (change.relationshipType.getRelationshipTypeAttachment().isWar()
              && List.of(change.player1, change.player2).contains(player)) {
            final GamePlayer enemy =
                change.player1.equals(player) ? change.player2 : change.player1;
            if (!tracker.isAllied(player, enemy)) {
              result.add(enemy);
            }
          }
        }
      }
    }
    return result;
  }
}
