package games.strategy.triplea.ai.proplus.diario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ItalianTest {
  @Test
  void anUpgradeShowsWhatReplacedWhat() {
    // "PU avanzati: unita' migliori al posto di altre": a tank instead of an artillery
    assertThat(Italian.unitChanges(Map.of("armour", 1, "artillery", -1)))
        .isEqualTo("1 carro al posto di 1 artiglieria");
    assertThat(Italian.unitChanges(Map.of("artillery", 2, "infantry", -2)))
        .isEqualTo("2 artiglierie al posto di 2 fanterie");
  }

  @Test
  void stepsThatOnlyAddOrOnlyRemove() {
    assertThat(Italian.unitChanges(Map.of("infantry", 2, "artillery", 1)))
        .isEqualTo("1 artiglieria, 2 fanterie");
    assertThat(Italian.unitChanges(Map.of("infantry", -1))).isEqualTo("tolte 1 fanteria");
  }
}
