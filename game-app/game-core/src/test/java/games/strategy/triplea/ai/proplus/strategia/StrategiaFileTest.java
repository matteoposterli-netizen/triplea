package games.strategy.triplea.ai.proplus.strategia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The strategy data file of WW2 Europe 1940 2nd Edition and its parsing. */
class StrategiaFileTest {
  static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private static final String FILE = Strategie.FOLDER + "ww2eur40_2nd_edition.json";

  private static Strategia europe() throws IOException {
    try (InputStream in = StrategiaFileTest.class.getClassLoader().getResourceAsStream(FILE)) {
      return Strategia.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
  }

  private static JsonObject raw() throws IOException {
    try (InputStream in = StrategiaFileTest.class.getClassLoader().getResourceAsStream(FILE)) {
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  @Test
  void indexPointsToTheFile() {
    final GameData data = new GameData();
    data.setGameName(EUROPE);

    assertThat(Strategie.forGame(data)).isPresent();
  }

  @Test
  void mapWithoutFileHasNoStrategy() {
    final GameData data = new GameData();
    data.setGameName("Nessuna mappa con questo nome");

    assertThat(Strategie.forGame(data)).isEmpty();
  }

  @Test
  void plansOfBothAlliances() throws IOException {
    final Strategia s = europe();

    assertThat(s.getFronti())
        .containsExactly("Ovest", "Est", "Mediterraneo-Africa", "Mari", "Altro");
    assertThat(s.alleanza("Axis").orElseThrow().piani())
        .extracting(Strategia.Piano::id)
        .containsExactly("A", "B", "C", "D", "N");
    assertThat(s.alleanza("Allies").orElseThrow().piani())
        .extracting(Strategia.Piano::id)
        .containsExactly("1", "2", "3");
    assertThat(s.alleanza("Axis").orElseThrow().pianoAutomatico().orElseThrow().nome())
        .isEqualTo("Barbarossa");
    assertThat(s.alleanza("Allies").orElseThrow().pianoAutomatico().orElseThrow().nome())
        .isEqualTo("Germania prima");
  }

  @Test
  void phasesHaveTheAgreedModifiers() throws IOException {
    final Strategia.Alleanza axis = europe().alleanza("Axis").orElseThrow();
    final Strategia.Fase barbarossaEarly = axis.piano("A").orElseThrow().fase(2).orElseThrow();
    final Strategia.Fase barbarossaLate = axis.piano("A").orElseThrow().fase(9).orElseThrow();
    final Strategia.Fase seaLionRound1 = axis.piano("B").orElseThrow().fase(1).orElseThrow();
    final Strategia.Fase fakeSeaLionLate = axis.piano("C").orElseThrow().fase(3).orElseThrow();

    assertThat(barbarossaEarly.label()).isEqualTo("R1-2");
    assertThat(barbarossaEarly.modificatore("Ovest")).isEqualTo(1);
    assertThat(barbarossaEarly.modificatore("Est")).isEqualTo(-1);
    assertThat(barbarossaLate.label()).isEqualTo("R3+");
    assertThat(barbarossaLate.modificatore("Est")).isEqualTo(1);
    assertThat(barbarossaLate.obiettivi())
        .filteredOn(o -> "Egypt".equals(o.territorio()))
        .singleElement()
        .satisfies(o -> assertThat(o.nazioni()).containsExactly("Italians"));
    assertThat(seaLionRound1.modificatore("Mari")).isEqualTo(1);
    assertThat(seaLionRound1.modificatore("Mediterraneo-Africa")).isEqualTo(-1);
    assertThat(fakeSeaLionLate).isEqualTo(axis.piano("A").orElseThrow().fase(3).orElseThrow());
    assertThat(axis.piano("C").orElseThrow().fasi())
        .flatExtracting(Strategia.Fase::obiettivi)
        .extracting(Strategia.Obiettivo::territorio)
        .doesNotContain("United Kingdom");

    final Strategia.Alleanza allies = europe().alleanza("Allies").orElseThrow();
    final Strategia.Piano germanyFirst = allies.piano("1").orElseThrow();
    assertThat(germanyFirst.fase(3).orElseThrow().modificatore("Est")).isEqualTo(-1);
    assertThat(germanyFirst.fase(3).orElseThrow().obiettivi()).isEmpty();
    assertThat(germanyFirst.fase(4).orElseThrow().modificatore("Est")).isZero();
    assertThat(germanyFirst.fase(4).orElseThrow().obiettivi())
        .extracting(Strategia.Obiettivo::territorio)
        .containsExactly("Normandy Bordeaux", "Norway", "Holland Belgium");
    assertThat(allies.piano("3").orElseThrow().fase(5).orElseThrow().fronti()).isEmpty();
  }

  @Test
  void everyPlanHasTheFullSchema() throws IOException {
    final JsonObject alliances = raw().getAsJsonObject("alleanze");
    for (final String alliance : alliances.keySet()) {
      for (final var plan : alliances.getAsJsonObject(alliance).getAsJsonArray("piani")) {
        assertThat(plan.getAsJsonObject().keySet())
            .as(alliance + " " + plan.getAsJsonObject().get("id"))
            .contains(
                "id", "nome", "descrizione", "fasi", "ruoli", "tappe", "firma", "punti_critici");
      }
    }
  }

  @Test
  void everyModifierIsBetweenMinusOneAndOne() throws IOException {
    final Strategia s = europe();
    for (final String alliance : List.of("Axis", "Allies")) {
      for (final Strategia.Piano plan : s.alleanza(alliance).orElseThrow().piani()) {
        for (final Strategia.Fase fase : plan.fasi()) {
          assertThat(fase.fronti().values()).allSatisfy(m -> assertThat(m).isBetween(-1, 1));
          assertThat(s.getFronti()).containsAll(fase.fronti().keySet());
        }
      }
    }
  }

  /** Europe 1940 shares its European names with Global 1940 (checked against both XMLs). */
  @Test
  void everyNameIsATerritoryOfTheMap() throws IOException {
    final GameData global = TestMapGameData.GLOBAL1940.getGameData();

    assertThat(europe().unknownTerritories(global.getMap())).isEmpty();
  }

  @Test
  void frontsOfTerritories() throws IOException {
    final Strategia s = europe();
    final var map = TestMapGameData.GLOBAL1940.getGameData().getMap();

    assertThat(s.fronteOf(map.getTerritoryOrThrow("France"))).isEqualTo("Ovest");
    assertThat(s.fronteOf(map.getTerritoryOrThrow("Novgorod"))).isEqualTo("Est");
    assertThat(s.fronteOf(map.getTerritoryOrThrow("Egypt"))).isEqualTo("Mediterraneo-Africa");
    assertThat(s.fronteOf(map.getTerritoryOrThrow("110 Sea Zone"))).isEqualTo("Mari");
    assertThat(s.fronteOf(map.getTerritoryOrThrow("India"))).isEqualTo(Strategia.ALTRO);
  }

  @Test
  void aTerritoryInTwoFrontsIsRefused() {
    final String json =
        "{\"fronti\":[{\"nome\":\"A\",\"territori\":[\"France\"]},"
            + "{\"nome\":\"B\",\"territori\":[\"France\"]}]}";

    assertThatThrownBy(() -> Strategia.parse(new StringReader(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("France");
  }

  @Test
  void anObjectiveNeedsATerritoryOrAFleet() {
    final String json =
        "{\"alleanze\":{\"Axis\":{\"piani\":[{\"id\":\"X\",\"fasi\":[{\"obiettivi\":[{\"peso\":1}]}]}]}}}";

    assertThatThrownBy(() -> Strategia.parse(new StringReader(json)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void seaLionTurnsToBarbarossaFromRound4() throws IOException {
    final Strategia.Alleanza axis = europe().alleanza("Axis").orElseThrow();
    final Strategia.Fase seaLionLate = axis.piano("B").orElseThrow().fase(4).orElseThrow();

    assertThat(seaLionLate.label()).isEqualTo("R4+");
    assertThat(seaLionLate)
        .isEqualTo(
            new Strategia.Fase(
                4,
                Integer.MAX_VALUE,
                axis.piano("A").orElseThrow().fase(3).orElseThrow().fronti(),
                axis.piano("A").orElseThrow().fase(3).orElseThrow().obiettivi()));
    assertThat(axis.piano("B").orElseThrow().fase(3).orElseThrow().label()).isEqualTo("R2-3");
  }

  @Test
  void logisticsOfTheProposal() throws IOException {
    final Strategia s = europe();
    final Strategia.Alleanza axis = s.alleanza("Axis").orElseThrow();
    final Strategia.Logistica barbarossa =
        axis.piano("A").orElseThrow().logistica("Germans", 2).orElseThrow();

    assertThat(barbarossa.label()).isEqualTo("R1-3");
    assertThat(barbarossa.mix())
        .containsExactly(
            java.util.Map.entry("fanteria", 35.0),
            java.util.Map.entry("artiglieria", 25.0),
            java.util.Map.entry("carri_mech", 30.0),
            java.util.Map.entry("aerei", 10.0));
    assertThat(barbarossa.piazzamento()).containsExactly("Germany");
    assertThat(barbarossa.fronteAmmassamento()).isEqualTo("Est");
    assertThat(barbarossa.territoriAmmassamento())
        .containsExactly("Poland", "Slovakia Hungary", "Romania");
    assertThat(axis.piano("A").orElseThrow().logistica("Germans", 4).orElseThrow().label())
        .isEqualTo("R4+");

    final Strategia.Logistica seaLion =
        axis.piano("B").orElseThrow().logistica("Germans", 1).orElseThrow();
    assertThat(seaLion.obbligati())
        .containsExactly(
            new Strategia.Obbligo("trasporti", 2), new Strategia.Obbligo("cacciatorpediniere", 1));
    assertThat(axis.piano("B").orElseThrow().logistica("Germans", 5).orElseThrow())
        .isEqualTo(
            new Strategia.Logistica(
                4,
                Integer.MAX_VALUE,
                barbarossa.mix(),
                List.of(),
                barbarossa.piazzamento(),
                "Est",
                barbarossa.territoriAmmassamento(),
                1));
    // the neutral plans give no orders: Hard's purchase and moves
    assertThat(axis.piano("N").orElseThrow().logistica()).isEmpty();
    assertThat(axis.piano("B").orElseThrow().logistica("Italians", 1)).isEmpty();

    final Strategia.Alleanza allies = s.alleanza("Allies").orElseThrow();
    final Strategia.Logistica usa =
        allies.piano("1").orElseThrow().logistica("Americans", 1).orElseThrow();
    assertThat(usa.mix().get("navi_da_guerra") + usa.mix().get("trasporti"))
        .isGreaterThanOrEqualTo(50);
    assertThat(usa.territoriAmmassamento()).contains("United Kingdom", "110 Sea Zone");
    assertThat(allies.piano("3").orElseThrow().logistica("British", 1).orElseThrow().mix())
        .containsKeys("bombardieri", "caccia");
  }

  @Test
  void everyMixIsAtMost100PercentOfKnownCategories() throws IOException {
    final Strategia s = europe();
    for (final String alliance : List.of("Axis", "Allies")) {
      for (final Strategia.Piano plan : s.alleanza(alliance).orElseThrow().piani()) {
        plan.logistica()
            .values()
            .forEach(
                orders ->
                    orders.forEach(
                        l -> {
                          assertThat(l.mix().values().stream().mapToDouble(d -> d).sum())
                              .isBetween(0.0, 100.0);
                          assertThat(s.getCategorie())
                              .containsKeys(l.mix().keySet().toArray(String[]::new));
                        }));
      }
    }
  }

  /** Unit names of the categories exist in Global 1940 (same unit set as Europe 1940). */
  @Test
  void everyCategoryUnitIsAUnitOfTheMap() throws IOException {
    assertThat(europe().unknownUnitTypes(TestMapGameData.GLOBAL1940.getGameData())).isEmpty();
  }

  @Test
  void anUnknownCategoryIsRefused() {
    final String json =
        "{\"categorie\":{\"fanteria\":[\"infantry\"]},\"alleanze\":{\"Axis\":{\"piani\":"
            + "[{\"id\":\"X\",\"logistica\":{\"Germans\":[{\"acquisti\":{\"mix\":"
            + "{\"carri\":50}}}]}}]}}}";

    assertThatThrownBy(() -> Strategia.parse(new StringReader(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("carri");
  }

  @Test
  void aMixOver100PercentIsRefused() {
    final String json =
        "{\"categorie\":{\"fanteria\":[\"infantry\"],\"aerei\":[\"fighter\"]},"
            + "\"alleanze\":{\"Axis\":{\"piani\":[{\"id\":\"X\",\"logistica\":{\"Germans\":"
            + "[{\"acquisti\":{\"mix\":{\"fanteria\":70,\"aerei\":40}}}]}}]}}}";

    assertThatThrownBy(() -> Strategia.parse(new StringReader(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("100");
  }
}
