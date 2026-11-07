package games.strategy.triplea.ai.proplus.strategia;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import games.strategy.triplea.util.TuvUtils;
import java.util.List;
import java.util.function.Predicate;

/**
 * Step OPERAZIONI ANFIBIE-BIS: whether a conditional target of an operation is active. A target
 * with {@code dal_round} R is active from round R, or earlier when its nation (eg Germans) is
 * weakened: it lost its capital, or its land TUV on the front (eg Ovest) is below {@code
 * quota_tuv_terra} (eg 0.5) of the reference.
 *
 * <p>The reference is the nation's land TUV on the front the first time the condition is read in
 * the game, saved as the game property {@code "Pro+ TUV iniziale: <nazione> <fronte>"} (a JDK
 * string). In Europe 1940 with Allies Pro+ that is the first Allied turn of round 1 (after the
 * German turn).
 */
public final class CondizioniBersaglio {
  static final String REFERENCE_PREFIX = "Pro+ TUV iniziale: ";

  private CondizioniBersaglio() {}

  /** Whether {@code target} with condition {@code c} (null = none) is active in {@code round}. */
  static boolean attivo(
      final GameState data,
      final Strategia strategia,
      final int round,
      final Strategia.CondizioneBersaglio c) {
    if (round >= c.dalRound()) {
      return true;
    }
    if (c.nazione() == null) {
      return false;
    }
    final GamePlayer nation = data.getPlayerList().getPlayerId(c.nazione());
    if (nation == null || nation.isNull()) {
      return false;
    }
    if (capitaleperduta(data, nation)) {
      return true;
    }
    if (c.fronte() == null || c.quotaTuvTerra() <= 0) {
      return false;
    }
    final int now = tuvTerraFronte(data, strategia, nation, c.fronte());
    final GameProperties props = data.getProperties();
    final String key = REFERENCE_PREFIX + c.nazione() + " " + c.fronte();
    if (!(props.get(key) instanceof String saved)) {
      props.set(key, String.valueOf(now));
      return false;
    }
    try {
      return now < c.quotaTuvTerra() * Integer.parseInt(saved);
    } catch (final NumberFormatException e) {
      return false;
    }
  }

  /** Whether a capital of {@code nation} is owned by another player. */
  static boolean capitaleperduta(final GameState data, final GamePlayer nation) {
    for (final Territory t : data.getMap().getTerritories()) {
      final boolean capital =
          TerritoryAttachment.get(t)
              .flatMap(TerritoryAttachment::getCapital)
              .map(name -> name.equals(nation.getName()))
              .orElse(false);
      if (capital && !t.isOwnedBy(nation)) {
        return true;
      }
    }
    return false;
  }

  /** Land TUV (no infrastructure) of {@code nation} on the land territories of {@code front}. */
  static int tuvTerraFronte(
      final GameState data,
      final Strategia strategia,
      final GamePlayer nation,
      final String front) {
    final Predicate<Unit> land =
        Matches.unitIsLand()
            .and(Matches.unitIsNotInfrastructure())
            .and(Matches.unitIsOwnedBy(nation));
    final var costs = new TuvCostsCalculator().getCostsForTuv(nation);
    int tuv = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && front.equals(strategia.fronteOf(t))) {
        final List<Unit> units = t.getMatches(land);
        tuv += TuvUtils.getTuv(units, costs);
      }
    }
    return tuv;
  }
}
