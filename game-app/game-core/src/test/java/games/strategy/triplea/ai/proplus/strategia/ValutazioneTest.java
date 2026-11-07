package games.strategy.triplea.ai.proplus.strategia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import com.google.gson.JsonObject;
import games.strategy.engine.data.BattleRecordsList;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.history.HistoryWriter;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.delegate.data.BattleRecord;
import games.strategy.triplea.delegate.data.BattleRecords;
import games.strategy.triplea.delegate.data.PlacementDescription;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 3c: progress of the own plan and reading of the enemy's plan at the start of each round,
 * observation only. Global 1940 renamed as Europe 1940 (same European names).
 */
class ValutazioneTest {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
  private final GamePlayer americans = data.getPlayerList().getPlayerId("Americans");

  @Test
  void theMilestonesOfThePlansAreInTheFile() {
    final Strategia s = europe();
    assertThat(labels(s, "Axis", "A"))
        .containsExactly("France R1", "Egypt R4", "Novgorod R6", "Russia R9");
    assertThat(labels(s, "Axis", "B")).containsExactly("United Kingdom R3");
    assertThat(labels(s, "Axis", "C"))
        .containsExactly("France R2", "Egypt R5", "Novgorod R7", "Russia R10");
    assertThat(labels(s, "Axis", "D"))
        .containsExactly("Egypt R3", "Gibraltar R3", "Trans-Jordan R4", "Caucasus R7");
    final Strategia.Tappa landing =
        s.alleanza("Allies").orElseThrow().piano("1").orElseThrow().tappe().get(0);
    assertThat(landing.territori())
        .containsExactly("Normandy Bordeaux", "Norway", "Holland Belgium");
    assertThat(landing.tutti()).isFalse();
    assertThat(landing.entroRound()).isEqualTo(5);
    assertThat(labels(s, "Allies", "1")).contains("France R8", "Germany R11");
    assertThat(labels(s, "Allies", "2"))
        .containsExactly("Libya+Tobruk R5", "Southern Italy R7", "Northern Italy R9");
    // every plan but the neutral ones has a signature to be read by
    for (final String id : List.of("A", "B", "C", "D")) {
      assertThat(s.alleanza("Axis").orElseThrow().piano(id).orElseThrow().firma()).isNotEmpty();
    }
    for (final String id : List.of("1", "2", "3")) {
      assertThat(s.alleanza("Allies").orElseThrow().piano(id).orElseThrow().firma()).isNotEmpty();
    }
  }

  @Test
  void seaLionIsExperimentalAndAutomaticNeverPicksIt() {
    final Strategia s = europe();
    final Strategia.Piano seaLion = s.alleanza("Axis").orElseThrow().piano("B").orElseThrow();
    assertThat(seaLion.etichettaPannello()).isEqualTo("B Sea Lion (sperimentale)");
    assertThat(seaLion.label()).isEqualTo("B Sea Lion");
    assertThat(s.alleanza("Axis").orElseThrow().pianoAutomatico().orElseThrow().id())
        .isEqualTo("A");

    // even a file naming it as "Automatico", or listing it first
    final Strategia file =
        Strategia.parse(
            new StringReader(
                "{\"alleanze\": {\"Axis\": {\"automatico\": \"B\", \"piani\": ["
                    + "{\"id\": \"B\", \"sperimentale\": true}, {\"id\": \"A\"}]}}}"));
    assertThat(file.alleanza("Axis").orElseThrow().pianoAutomatico().orElseThrow().id())
        .isEqualTo("A");
  }

  @Test
  void malformedMilestonesAndCluesAreRejected() {
    assertThatThrownBy(() -> parsePlan("\"tappe\": [{\"territorio\": \"France\"}]"))
        .hasMessageContaining("entro_round");
    assertThatThrownBy(
            () ->
                parsePlan(
                    "\"firma\": [{\"tipo\": \"sconosciuto\", \"nazioni\": [\"Germans\"],"
                        + " \"da\": 0, \"a\": 1}]"))
        .hasMessageContaining("tipo sconosciuto");
    assertThatThrownBy(
            () ->
                parsePlan(
                    "\"firma\": [{\"tipo\": \"quota_tuv_fronte\", \"nazioni\": [\"Germans\"],"
                        + " \"da\": 0, \"a\": 1}]"))
        .hasMessageContaining("fronte");
    assertThatThrownBy(
            () ->
                parsePlan(
                    "\"firma\": [{\"tipo\": \"unita_in\", \"nazioni\": [\"Germans\"],"
                        + " \"classe\": \"sommergibili\", \"da\": 0, \"a\": 1}]"))
        .hasMessageContaining("classe sconosciuta");
  }

  @Test
  void aClueIsALinearRampEitherWay() {
    final Strategia.Firma rising = clue(0, 3);
    assertThat(rising.valore(-1)).isZero();
    assertThat(rising.valore(1.5)).isEqualTo(0.5);
    assertThat(rising.valore(4)).isEqualTo(1);
    final Strategia.Firma falling = clue(3, 1);
    assertThat(falling.valore(0)).isEqualTo(1);
    assertThat(falling.valore(2)).isEqualTo(0.5);
    assertThat(falling.valore(5)).isZero();
  }

  @Test
  void progressAtTheStartOfEachRoundOncePerAlliance() {
    final Strategia s = europe();
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "A");
    data.getSequence().setRoundAndStep(1, "Purchase", germans);
    StatoMaggiore.fix(data, "Axis", s);

    final Valutazione.Risultato r1 = Valutazione.inizioRound(data, germans).orElseThrow();
    assertThat(milestones(r1)).containsEntry("France R1", "in tempo");
    assertThat(progress(r1).get("stato").getAsString()).isEqualTo(Valutazione.IN_LINEA);
    assertThat(r1.testo()).contains("Avanzamento: IN LINEA - tappe: France R1 in tempo");
    // the Italians, later in the same round, find the entry already there
    data.getSequence().setRoundAndStep(1, "Purchase", data.getPlayerList().getPlayerId("Italians"));
    assertThat(Valutazione.inizioRound(data, data.getPlayerList().getPlayerId("Italians")))
        .isEmpty();

    // round 2, France not taken: late
    data.getSequence().setRoundAndStep(2, "Purchase", germans);
    final Valutazione.Risultato r2 = Valutazione.inizioRound(data, germans).orElseThrow();
    assertThat(milestones(r2)).containsEntry("France R1", "in ritardo");
    assertThat(progress(r2).get("stato").getAsString()).isEqualTo(Valutazione.IN_RITARDO);

    // round 3, France taken: reached, and it stays reached once lost again
    data.performChange(
        ChangeFactory.changeOwner(data.getMap().getTerritoryOrThrow("France"), germans));
    data.getSequence().setRoundAndStep(3, "Purchase", germans);
    final Valutazione.Risultato r3 = Valutazione.inizioRound(data, germans).orElseThrow();
    assertThat(milestones(r3)).containsEntry("France R1", "raggiunta");
    assertThat(r3.testo()).contains("France R1 raggiunta (R3)");
    data.performChange(
        ChangeFactory.changeOwner(
            data.getMap().getTerritoryOrThrow("France"),
            data.getPlayerList().getPlayerId("French")));
    data.getSequence().setRoundAndStep(4, "Purchase", germans);
    final Valutazione.Risultato r4 = Valutazione.inizioRound(data, germans).orElseThrow();
    assertThat(milestones(r4)).containsEntry("France R1", "raggiunta");

    // the history keeps one entry per round
    assertThat(StatoMaggiore.getState(data.getProperties(), "Axis").orElseThrow().storico())
        .hasSize(4);
    assertThat(progress(r4).get("reddito_r1").getAsInt())
        .isEqualTo(progress(r1).get("reddito").getAsInt());
  }

  @Test
  void aMilestoneHeldFromTheStartIsReachedOnlyOnceRetaken() {
    final Strategia s = europe();
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    data.getSequence().setRoundAndStep(1, "Purchase", russians);
    StatoMaggiore.fix(data, "Allies", s);
    final String landing = "prima zona di sbarco (Normandy Bordeaux o Norway o Holland Belgium) R5";
    final Territory normandy = data.getMap().getTerritoryOrThrow("Normandy Bordeaux");
    final GamePlayer french = data.getPlayerList().getPlayerId("French");

    // Normandy is French at the start: not a landing
    assertThat(milestones(Valutazione.inizioRound(data, russians).orElseThrow()))
        .containsEntry(landing, "in tempo");
    // taken by the Germans, then retaken: reached
    data.performChange(ChangeFactory.changeOwner(normandy, germans));
    data.getSequence().setRoundAndStep(2, "Purchase", russians);
    assertThat(milestones(Valutazione.inizioRound(data, russians).orElseThrow()))
        .containsEntry(landing, "in tempo");
    data.performChange(ChangeFactory.changeOwner(normandy, french));
    data.getSequence().setRoundAndStep(3, "Purchase", russians);
    assertThat(milestones(Valutazione.inizioRound(data, russians).orElseThrow()))
        .containsEntry(landing, "raggiunta");
  }

  @Test
  void theAlliesReadSeaLionFromGermanTransportsAndRememberTheEvidence() {
    final Strategia s = europe();
    data.getSequence().setRoundAndStep(1, "Purchase", russians);
    StatoMaggiore.fix(data, "Allies", s);
    final Territory northSea = data.getMap().getTerritoryOrThrow("112 Sea Zone");
    data.performChange(
        ChangeFactory.addUnits(
            northSea, data.getUnitTypeList().getUnitTypeOrThrow("transport").create(4, germans)));
    buy(germans, 1, "germansPurchase", "transport", 3, "destroyer", 1);

    final List<Map<String, Double>> rounds = new ArrayList<>();
    for (int round = 1; round <= 4; round++) {
      if (round == 3) {
        // the transports leave: the evidence fades, it does not vanish at once
        for (int zone = 110; zone <= 115; zone++) {
          final Territory t = data.getMap().getTerritoryOrThrow(zone + " Sea Zone");
          data.performChange(
              ChangeFactory.removeUnits(
                  t,
                  t.getMatches(Matches.unitIsOwnedBy(germans).and(Matches.unitIsSeaTransport()))));
        }
      }
      rounds.add(probabilities(round));
    }
    // A, B, C, D read (N has no signature)
    assertThat(rounds.get(0)).containsOnlyKeys("A", "B", "C", "D");
    rounds.forEach(
        p ->
            assertThat(p.values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1, org.assertj.core.data.Offset.offset(0.01)));
    // Sea Lion leads and, with the same evidence, stays where it is
    assertThat(rounds.get(0).get("B")).isGreaterThan(rounds.get(0).get("A"));
    assertThat(rounds.get(1).get("B")).isCloseTo(rounds.get(0).get("B"), offset(0.01));
    // without the transports (round 3, when "no war on the Soviets by round 3" also starts to
    // count for B, C and D) it falls round after round
    assertThat(rounds.get(3).get("B")).isLessThan(rounds.get(2).get("B"));
    final JsonObject memory =
        StatoMaggiore.getState(data.getProperties(), "Allies")
            .orElseThrow()
            .storico()
            .get(3)
            .getAsJsonObject()
            .getAsJsonObject("lettura")
            .getAsJsonObject("memoria");
    // 1 at rounds 1-2, then 0: 0.6 x 1 = 0.6 at round 3, 0.36 at round 4
    assertThat(
            memory
                .getAsJsonObject("B")
                .get("almeno 3 trasporti tedeschi nel Baltico / Mare del Nord")
                .getAsDouble())
        .isCloseTo(0.36, offset(0.001));
  }

  @Test
  void aStateClueIsAveragedAndAnEventFadesSlowly() {
    final Strategia.Firma state = clue(0, 1);
    assertThat(Valutazione.remember(state, null, null)).isNull();
    assertThat(Valutazione.remember(state, null, 0.8)).isEqualTo(0.8);
    assertThat(Valutazione.remember(state, 0.8, null)).isEqualTo(0.8);
    assertThat(Valutazione.remember(state, 1.0, 0.0)).isCloseTo(Valutazione.MEMORIA, offset(1e-9));
    final Strategia.Firma event = clue(Strategia.Firma.ATTACCHI, 0, 1);
    assertThat(event.isEvento()).isTrue();
    assertThat(Valutazione.remember(event, 1.0, 0.0))
        .isCloseTo(Valutazione.MEMORIA_EVENTI, offset(1e-9));
    assertThat(Valutazione.remember(event, 0.2, 1.0)).isEqualTo(1.0);
  }

  @Test
  void theAxisReadsItalyFirstFromBritishPlacementsAndAttacks() {
    final Strategia s = europe();
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    final GamePlayer italians = data.getPlayerList().getPlayerId("Italians");
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "2");
    data.getSequence().setRoundAndStep(1, "Purchase", russians);
    StatoMaggiore.fix(data, "Allies", s);
    data.getSequence().setRoundAndStep(2, "Purchase", germans);
    StatoMaggiore.fix(data, "Axis", s);
    place(british, 1, "Union of South Africa", "infantry", 3);
    place(british, 1, "United Kingdom", "infantry", 1);
    final BattleRecords records = new BattleRecords();
    final UUID id = UUID.randomUUID();
    records.addBattle(
        british, id, data.getMap().getTerritoryOrThrow("96 Sea Zone"), IBattle.BattleType.NORMAL);
    records.addResultToBattle(
        british,
        id,
        italians,
        0,
        10,
        BattleRecord.BattleResultDescription.WON_WITHOUT_CONQUERING,
        null);
    BattleRecordsList.addRecords(data.getBattleRecordsList().getBattleRecordsMap(), 1, records);

    final JsonObject reading =
        Valutazione.inizioRound(data, germans).orElseThrow().storico().getAsJsonObject("lettura");

    assertThat(reading.get("piu_probabile").getAsString()).isEqualTo("2");
    final Map<String, Double> values = clueValues(reading, "2");
    assertThat(values.get("piazzamenti britannici in Sud Africa")).isEqualTo(1.0);
    assertThat(values.get("attacco britannico alla flotta italiana nei primi round"))
        .isEqualTo(1.0);
    assertThat(
            clueValues(reading, "1")
                .get("piazzamenti britannici nel Regno Unito, non in Sud Africa"))
        .isZero();
    assertThat(Valutazione.lastPlacements(data, british, 2))
        .extracting(Valutazione.Placement::territory)
        .containsExactly("Union of South Africa", "United Kingdom");
  }

  @Test
  void aWarOnTheSovietsByRound3IsReadAsBarbarossa() {
    final Strategia s = europe();
    data.getSequence().setRoundAndStep(1, "Purchase", russians);
    StatoMaggiore.fix(data, "Allies", s);
    probabilities(1);
    probabilities(2);
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    data.performChange(
        ChangeFactory.relationshipChange(
            germans,
            russians,
            data.getRelationshipTracker().getRelationshipType(germans, russians),
            data.getRelationshipTracker().getRelationshipType(germans, british)));
    assertThat(data.getRelationshipTracker().isAtWar(germans, russians)).isTrue();

    final Map<String, Double> r3 = probabilities(3);

    final JsonObject reading =
        StatoMaggiore.getState(data.getProperties(), "Allies")
            .orElseThrow()
            .storico()
            .get(2)
            .getAsJsonObject()
            .getAsJsonObject("lettura");
    assertThat(clueValues(reading, "A").get("guerra tedesca all'URSS gia' al round 3"))
        .isEqualTo(1.0);
    assertThat(clueValues(reading, "D").get("nessuna guerra tedesca all'URSS al round 3")).isZero();
    // out of its round the clue keeps its value
    final Map<String, Double> r4 = probabilities(4);
    assertThat(r4.get("A")).isCloseTo(r3.get("A"), offset(0.02));
  }

  @Test
  void theRealEnemyPlanIsComparedWhenTheEnemyIsProPlus() {
    final Strategia s = europe();
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "A");
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "2");
    data.getSequence().setRoundAndStep(1, "Purchase", germans);
    StatoMaggiore.fix(data, "Axis", s);
    StatoMaggiore.fix(data, "Allies", s);

    final Valutazione.Risultato axis = Valutazione.inizioRound(data, germans).orElseThrow();

    final JsonObject reading = axis.storico().getAsJsonObject("lettura");
    assertThat(reading.get("piano_reale").getAsString()).isEqualTo("2");
    assertThat(reading.getAsJsonObject("probabilita").keySet()).containsOnly("1", "2", "3");
    assertThat(axis.testo())
        .contains("Lettura del nemico: ")
        .contains("(piano reale avversario: 2 Italia prima - lettura corretta ");
  }

  @Test
  void enemyPurchasesComeFromTheGameHistory() {
    buy(americans, 1, "americansPurchase", "transport", 2, "infantry", 3);
    buy(americans, 2, "americansPurchase", "bomber", 1);
    buy(americans, 3, "americansPurchase", "infantry", 1);

    // the last two purchases
    final Map<String, Integer> bought = new TreeMap<>();
    Valutazione.lastPurchases(data, americans, 2)
        .forEach(u -> bought.merge(u.getType().getName(), 1, Integer::sum));
    assertThat(bought).containsExactlyInAnyOrderEntriesOf(Map.of("bomber", 1, "infantry", 1));
  }

  // ------------------------------------------------------------------------------- helpers

  private Strategia europe() {
    data.setGameName(EUROPE);
    return Strategie.forGame(data).orElseThrow();
  }

  /** The Allies' reading of the Axis plan at the start of {@code round}: plan -> probability. */
  private Map<String, Double> probabilities(final int round) {
    data.getSequence().setRoundAndStep(round, "Purchase", russians);
    final JsonObject reading =
        Valutazione.inizioRound(data, russians).orElseThrow().storico().getAsJsonObject("lettura");
    final Map<String, Double> p = new TreeMap<>();
    reading
        .getAsJsonObject("probabilita")
        .entrySet()
        .forEach(e -> p.put(e.getKey(), e.getValue().getAsDouble()));
    return p;
  }

  /** Remembered value of each clue of {@code plan} in the reading. */
  private static Map<String, Double> clueValues(final JsonObject reading, final String plan) {
    final Map<String, Double> values = new TreeMap<>();
    reading
        .getAsJsonObject("indizi")
        .getAsJsonArray(plan)
        .forEach(
            e -> {
              final JsonObject c = e.getAsJsonObject();
              if (c.has("valore")) {
                values.put(c.get("indizio").getAsString(), c.get("valore").getAsDouble());
              }
            });
    return values;
  }

  /** Writes a place step of {@code player} in round {@code round} to the game history. */
  private void place(
      final GamePlayer player,
      final int round,
      final String territory,
      final String type,
      final int count) {
    final HistoryWriter writer = data.getHistory().getHistoryWriter();
    writer.startNextRound(round);
    writer.startNextStep(
        player.getName().toLowerCase(java.util.Locale.ROOT) + "Place", "place", player, "Place");
    final List<Unit> units = data.getUnitTypeList().getUnitTypeOrThrow(type).create(count, player);
    writer.startEvent(type + " placed in " + territory);
    writer.setRenderingData(
        new PlacementDescription(units, data.getMap().getTerritoryOrThrow(territory)));
  }

  /** Writes a purchase step of {@code player} in round {@code round} to the game history. */
  private void buy(
      final GamePlayer player, final int round, final String step, final Object... units) {
    final HistoryWriter writer = data.getHistory().getHistoryWriter();
    writer.startNextRound(round);
    writer.startNextStep(step, "purchase", player, "Purchase");
    final List<Unit> bought = new ArrayList<>();
    for (int i = 0; i < units.length; i += 2) {
      bought.addAll(
          data.getUnitTypeList()
              .getUnitTypeOrThrow((String) units[i])
              .create((Integer) units[i + 1], player));
    }
    writer.startEvent(player.getName() + " buy");
    writer.setRenderingData(bought);
  }

  private static List<String> labels(final Strategia s, final String alliance, final String id) {
    return s.alleanza(alliance).orElseThrow().piano(id).orElseThrow().tappe().stream()
        .map(Strategia.Tappa::label)
        .toList();
  }

  private static Map<String, String> milestones(final Valutazione.Risultato r) {
    final Map<String, String> m = new TreeMap<>();
    progress(r)
        .getAsJsonArray("tappe")
        .forEach(
            e ->
                m.put(
                    e.getAsJsonObject().get("tappa").getAsString(),
                    e.getAsJsonObject().get("stato").getAsString()));
    return m;
  }

  private static JsonObject progress(final Valutazione.Risultato r) {
    return r.storico().getAsJsonObject("avanzamento");
  }

  private static Strategia.Firma clue(final double from, final double to) {
    return clue(Strategia.Firma.UNITA_IN, from, to);
  }

  private static Strategia.Firma clue(final String type, final double from, final double to) {
    return new Strategia.Firma(
        "x",
        type,
        List.of("Germans"),
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        from,
        to,
        1,
        1,
        Integer.MAX_VALUE);
  }

  private static void parsePlan(final String fields) {
    Strategia.parse(
        new StringReader(
            "{\"alleanze\": {\"Axis\": {\"piani\": [{\"id\": \"A\", " + fields + "}]}}}"));
  }
}
