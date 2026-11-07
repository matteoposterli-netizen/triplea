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
        .containsExactly("Normandy Bordeaux", "Norway");
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
                axis.piano("A").orElseThrow().fase(3).orElseThrow().obiettivi(),
                List.of()));
    assertThat(axis.piano("B").orElseThrow().fase(3).orElseThrow().label()).isEqualTo("R2-3");
  }

  @Test
  void logisticsOfTheProposal() throws IOException {
    final Strategia s = europe();
    final Strategia.Alleanza axis = s.alleanza("Axis").orElseThrow();
    final Strategia.Logistica barbarossa =
        axis.piano("A").orElseThrow().logistica("Germans", 2).orElseThrow();

    assertThat(barbarossa.label()).isEqualTo("R1-2");
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
        .containsExactly(new Strategia.Obbligo("trasporti", 3), new Strategia.Obbligo("scorta", 1));
    assertThat(seaLion.piazzamento()).startsWith("114 Sea Zone", "113 Sea Zone");
    assertThat(s.getCategorie().get("scorta")).containsExactly("carrier", "destroyer");
    // Sea Lion from round 4 = Barbarossa from round 4
    assertThat(axis.piano("B").orElseThrow().logistica("Germans", 5).orElseThrow())
        .isEqualTo(axis.piano("A").orElseThrow().logistica("Germans", 4).orElseThrow());
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

  @Test
  void planShareAndAirStagingOfStep3bBis() throws IOException {
    final Strategia s = europe();
    final Strategia.Alleanza allies = s.alleanza("Allies").orElseThrow();
    assertThat(allies.piano("1").orElseThrow().logistica("Americans", 1).orElseThrow())
        .extracting(Strategia.Logistica::quotaMinimaPiano)
        .isEqualTo(70.0);
    assertThat(allies.piano("1").orElseThrow().logistica("Americans", 5).orElseThrow())
        .extracting(Strategia.Logistica::quotaMinimaPiano)
        .isEqualTo(70.0);
    assertThat(allies.piano("2").orElseThrow().logistica("Americans", 1).orElseThrow())
        .extracting(Strategia.Logistica::quotaMinimaPiano)
        .isEqualTo(70.0);
    assertThat(allies.piano("1").orElseThrow().logistica("British", 1).orElseThrow())
        .extracting(Strategia.Logistica::quotaMinimaPiano)
        .isEqualTo(40.0);
    // nowhere else
    assertThat(allies.piano("2").orElseThrow().logistica("British", 1).orElseThrow())
        .extracting(Strategia.Logistica::quotaMinimaPiano)
        .isEqualTo(0.0);
    assertThat(allies.piano("3").orElseThrow().logistica("Americans", 1).orElseThrow())
        .extracting(Strategia.Logistica::quotaMinimaPiano)
        .isEqualTo(0.0);

    final Strategia.Alleanza axis = s.alleanza("Axis").orElseThrow();
    for (final int round : List.of(1, 2, 3)) {
      assertThat(axis.piano("B").orElseThrow().logistica("Germans", round).orElseThrow().aerei())
          .isTrue();
    }
    assertThat(axis.piano("B").orElseThrow().logistica("Germans", 4).orElseThrow().aerei())
        .isFalse();
    assertThat(axis.piano("D").orElseThrow().logistica("Italians", 1).orElseThrow().aerei())
        .isTrue();
    assertThat(axis.piano("A").orElseThrow().logistica("Germans", 1).orElseThrow().aerei())
        .isFalse();
    final Strategia.Logistica seaLionR2 =
        axis.piano("B").orElseThrow().logistica("Germans", 2).orElseThrow();
    assertThat(seaLionR2.obbligati()).containsExactly(new Strategia.Obbligo("trasporti", 2));
    assertThat(seaLionR2.mix()).containsKeys("aerei", "fanteria", "artiglieria");
    assertThat(seaLionR2.territoriAmmassamento())
        .contains("Holland Belgium", "Normandy Bordeaux", "Denmark");
  }

  @Test
  void aPlanShareOutside0To100IsRefused() {
    final String json =
        "{\"categorie\":{\"fanteria\":[\"infantry\"]},\"alleanze\":{\"Axis\":{\"piani\":"
            + "[{\"id\":\"X\",\"logistica\":{\"Germans\":[{\"quota_minima_piano\":120,"
            + "\"acquisti\":{\"mix\":{\"fanteria\":50}}}]}}]}}}";

    assertThatThrownBy(() -> Strategia.parse(new StringReader(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quota_minima_piano");
  }

  @Test
  void ordersOfStep3c() throws IOException {
    final Strategia s = europe();
    final Strategia.Alleanza axis = s.alleanza("Axis").orElseThrow();
    // war on the USSR: never before the plan's round
    assertThat(axis.piano("A").orElseThrow().dichiarazione("Germans", "Russians"))
        .hasValue(new Strategia.Dichiarazione("Germans", "Russians", 3));
    for (final String plan : List.of("B", "C", "D")) {
      assertThat(axis.piano(plan).orElseThrow().dichiarazione("Germans", "Russians"))
          .hasValue(new Strategia.Dichiarazione("Germans", "Russians", 4));
    }
    assertThat(axis.piano("N").orElseThrow().dichiarazione("Germans", "Russians")).isEmpty();
    // critical objectives: France and the enemy capitals
    assertThat(axis.piano("A").orElseThrow().fase(1).orElseThrow().obiettivi())
        .filteredOn(o -> "France".equals(o.territorio()))
        .allMatch(Strategia.Obiettivo::critico);
    assertThat(axis.piano("A").orElseThrow().fase(3).orElseThrow().obiettivi())
        .filteredOn(Strategia.Obiettivo::critico)
        .extracting(Strategia.Obiettivo::territorio)
        .containsExactly("Russia");
    // plan share of Germany and Italy
    for (final Strategia.Piano plan : axis.piani()) {
      plan.logistica()
          .forEach(
              (nation, orders) ->
                  orders.forEach(
                      l ->
                          assertThat(l.quotaMinimaPiano())
                              .isEqualTo(nation.equals("Germans") ? 50.0 : 40.0)));
    }
    // offensive phases: front line in the East, 20 to the Channel coast, garrisons there
    final Strategia.Logistica attack =
        axis.piano("A").orElseThrow().logistica("Germans", 3).orElseThrow();
    assertThat(attack.lineaDelFronte()).isTrue();
    assertThat(attack.pesoAmmassamento()).isEqualTo(80);
    assertThat(attack.altreRaccolte())
        .containsExactly(
            new Strategia.Raccolta(
                "Ovest", List.of("Normandy Bordeaux", "Holland Belgium", "Denmark"), 20, false));
    assertThat(attack.presidioMinimo())
        .containsEntry("Normandy Bordeaux", 2)
        .containsEntry("Holland Belgium", 2)
        .containsEntry("Denmark", 2);
    assertThat(axis.piano("A").orElseThrow().logistica("Germans", 1).orElseThrow().lineaDelFronte())
        .isFalse();
    // Italy gathers overseas from an embarkation point
    assertThat(axis.piano("A").orElseThrow().logistica("Italians", 1).orElseThrow().imbarco())
        .containsExactly("Southern Italy", "96 Sea Zone");
    // the USSR gathers on its front line
    assertThat(
            s.alleanza("Allies")
                .orElseThrow()
                .piano("1")
                .orElseThrow()
                .logistica("Russians", 1)
                .orElseThrow()
                .lineaDelFronte())
        .isTrue();
  }

  @Test
  void aDeclarationNeedsWhoWhomAndRound() {
    final String json =
        "{\"alleanze\":{\"Axis\":{\"piani\":[{\"id\":\"X\",\"fasi\":[{\"dichiarazioni\":"
            + "[{\"da\":\"Germans\",\"a\":\"Russians\"}]}]}]}}}";

    assertThatThrownBy(() -> Strategia.parse(new StringReader(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dal_round");
  }

  @Test
  void amphibiousOperationsOfThePlans() throws IOException {
    final Strategia.Alleanza allies = europe().alleanza("Allies").orElseThrow();
    final Strategia.Alleanza axis = europe().alleanza("Axis").orElseThrow();

    final Strategia.Piano germanyFirst = allies.piano("1").orElseThrow();
    assertThat(germanyFirst.operazioni("Americans", 2)).isEmpty();
    final Strategia.Operazione overlord = germanyFirst.operazioni("Americans", 3).get(0);
    assertThat(overlord.nome()).isEqualTo("Overlord USA");
    assertThat(overlord.porti())
        .extracting(Strategia.Porto::territorio)
        .containsExactly("Eastern United States", "United Kingdom", "United Kingdom");
    assertThat(overlord.raccolta()).containsExactly("110 Sea Zone", "109 Sea Zone");
    assertThat(overlord.bersagli())
        .containsExactly("Normandy Bordeaux", "Holland Belgium", "Norway");
    assertThat(overlord.vettori()).containsExactly("Americans");
    // step ANFIBIE-BIS: Holland Belgium from round 8, or earlier with Germany weakened
    assertThat(overlord.condizionati())
        .containsOnlyKeys("Holland Belgium")
        .containsEntry(
            "Holland Belgium", new Strategia.CondizioneBersaglio(8, "Germans", "Ovest", 0.5));
    assertThat(overlord.trasporti()).isEqualTo(8);
    assertThat(germanyFirst.operazioni("British", 2))
        .extracting(Strategia.Operazione::nome)
        .containsExactly("Overlord UK");

    final Strategia.Piano italyFirst = allies.piano("2").orElseThrow();
    assertThat(italyFirst.operazioni("Americans", 3))
        .extracting(Strategia.Operazione::nome)
        .containsExactly("Torch");
    assertThat(italyFirst.operazioni("British", 1))
        .extracting(Strategia.Operazione::nome)
        .containsExactly("Rinforzi Egitto");

    final Strategia.Operazione afrika =
        axis.piano("D").orElseThrow().operazioni("Germans", 1).get(0);
    assertThat(afrika.nazioni()).containsExactly("Germans", "Italians");
    assertThat(afrika.vettori()).containsExactly("Italians");
    assertThat(axis.piano("D").orElseThrow().operazioni("Italians", 1)).containsExactly(afrika);
    assertThat(afrika.bersagli()).containsExactly("Libya", "Tobruk", "Egypt");

    final Strategia.Piano seaLion = axis.piano("B").orElseThrow();
    assertThat(seaLion.operazioni("Germans", 3)).hasSize(1);
    assertThat(seaLion.operazioni("Germans", 4)).isEmpty();
    assertThat(seaLion.operazioni("Germans", 1).get(0).critico()).isTrue();
    // no operation in A, C, N, 3
    for (final String id : List.of("A", "C", "N")) {
      assertThat(axis.piano(id).orElseThrow().operazioni()).isEmpty();
    }
    assertThat(allies.piano("3").orElseThrow().operazioni()).isEmpty();
  }

  @Test
  void anOperationNeedsPortsGatheringAndTargets() {
    final String json =
        "{\"alleanze\":{\"Allies\":{\"piani\":[{\"id\":\"X\",\"operazioni_anfibie\":"
            + "[{\"nome\":\"O\",\"nazioni\":[\"British\"],\"raccolta\":[\"110 Sea Zone\"],"
            + "\"bersagli\":[\"Norway\"]}]}]}}}";

    assertThatThrownBy(() -> Strategia.parse(new StringReader(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("porti");
  }

  @Test
  void theFirstTargetWeighsMostAndTheCarrierDefaultsToTheNations() {
    final String json =
        "{\"alleanze\":{\"Allies\":{\"piani\":[{\"id\":\"X\",\"operazioni_anfibie\":"
            + "[{\"nome\":\"O\",\"nazioni\":[\"British\"],\"porti\":[{\"territorio\":"
            + "\"United Kingdom\",\"zona\":\"110 Sea Zone\"}],\"raccolta\":[\"110 Sea Zone\"],"
            + "\"bersagli\":[\"Normandy Bordeaux\",\"Norway\"],\"peso\":10}]}]}}}";

    final Strategia.Operazione o =
        Strategia.parse(new StringReader(json))
            .alleanza("Allies")
            .orElseThrow()
            .piano("X")
            .orElseThrow()
            .operazioni()
            .get(0);
    assertThat(o.vettori()).containsExactly("British");
    assertThat(o.pesoBersaglio(0)).isEqualTo(10);
    assertThat(o.pesoBersaglio(1)).isEqualTo(8, org.assertj.core.data.Offset.offset(0.001));
    assertThat(o.trasporti()).isEqualTo(2);
    assertThat(o.daRound()).isEqualTo(1);
  }
}
