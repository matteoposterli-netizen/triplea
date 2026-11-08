package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import java.util.Map;

/**
 * A tuning's own check of a landing's transports in the sea zones they unload from ({@link
 * ProTuning#checksLandingTransports}), in place of "Hard (AI)"'s "transports exposed" rule.
 *
 * @param keep whether the landing is made
 * @param reason short code of the decision, for the decision recorder
 * @param expectedLoss TUV of the transports (and escort) expected to be lost in the enemy turns
 *     before the next own turn
 * @param gain what the landing is expected to gain (conquest value and battle TUV swing)
 * @param detail the numbers behind the decision (Italian text, for the decision diary)
 * @param unloadZones transports moved to another unload zone: transport -> zone
 */
public record ProLandingTransports(
    boolean keep,
    String reason,
    double expectedLoss,
    double gain,
    String detail,
    Map<Unit, Territory> unloadZones) {}
