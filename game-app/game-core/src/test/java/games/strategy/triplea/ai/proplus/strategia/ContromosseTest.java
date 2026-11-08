package games.strategy.triplea.ai.proplus.strategia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Step 3d-1: plan change on failure, posture and counter-objectives. Global 1940 renamed as Europe
 * 1940 (same European names).
 */
class ContromosseTest {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = player("Germans");
  private final GamePlayer russians = player("Russians");

  // ---------------------------------------------------------------------------- data file

  @Test
  void theFileNamesTakeOversCountersAndConcreteCriticalPoints() {
    final Strategia s = europe();
    assertThat(plan(s, "Axis", "A").subentri()).containsExactly("D");
    assertThat(plan(s, "Axis", "D").subentri()).containsExactly("A");
    assertThat(plan(s, "Axis", "C").subentri()).containsExactly("A");
    assertThat(plan(s, "Axis", "B").subentri()).containsExactly("A");
    assertThat(plan(s, "Allies", "1").subentri()).containsExactly("2");
    assertThat(plan(s, "Allies", "2").subentri()).containsExactly("1");
    assertThat(plan(s, "Allies", "3").subentri()).containsExactly("1", "2");
    // N: no posture, no change
    assertThat(plan(s, "Axis", "N").contromosse()).isFalse();
    assertThat(plan(s, "Axis", "N").subentri()).isEmpty();
    assertThat(plan(s, "Axis", "A").contromosse()).isTrue();
    // who counters whom
    assertThat(plan(s, "Allies", "1").contrasta()).contains("A");
    assertThat(plan(s, "Allies", "2").contrasta()).contains("D");
    // concrete targets
    assertThat(plan(s, "Axis", "A").puntiCritici())
        .extracting(Strategia.PuntoCritico::tipo)
        .containsExactly("stack_avanzato", "territorio", "territorio");
    assertThat(plan(s, "Axis", "A").puntiCritici().get(1).territori()).containsExactly("Romania");
    assertThat(plan(s, "Allies", "1").puntiCritici().get(0).zone())
        .containsExactly("104 Sea Zone", "109 Sea Zone", "110 Sea Zone");
    assertThat(plan(s, "Allies", "2").puntiCritici())
        .flatExtracting(p -> List.of(p.territori(), p.zone()))
        .contains(List.of("91 Sea Zone", "92 Sea Zone"), List.of("Egypt"), List.of("Malta"));
    // C: like B in the first rounds only
    assertThat(plan(s, "Axis", "C").puntiCritici()).allMatch(p -> !p.covers(4) && p.covers(3));
    final Strategia.Parametri par = s.getParametri();
    assertThat(par.roundCrisi()).isEqualTo(2);
    assertThat(par.inerziaRound()).isEqualTo(3);
    assertThat(par.maxCambi()).isEqualTo(2);
    // the race counts what the map's victory conditions count
    assertThat(par.vittoria().get("Axis").vc()).isEqualTo(8);
    assertThat(par.vittoria().get("Allies").territori())
        .containsExactly("Germany", "Southern Italy");
    // every name is a territory of the map
    assertThat(s.unknownTerritories(data.getMap())).isEmpty();
  }

  @Test
  void unknownTakeOversAndCriticalPointTypesAreRejected() {
    assertThatThrownBy(() -> parse("{\"id\": \"A\", \"subentri\": [\"Z\"]}"))
        .hasMessageContaining("subentri");
    assertThatThrownBy(() -> parse("{\"id\": \"A\", \"subentri\": [\"A\"]}"))
        .hasMessageContaining("subentri");
    assertThatThrownBy(() -> parse("{\"id\": \"A\", \"contrasta\": [\"9\"]}"))
        .hasMessageContaining("contrasta");
    assertThatThrownBy(() -> parse("{\"id\": \"A\", \"punti_critici\": [{\"tipo\": \"boh\"}]}"))
        .hasMessageContaining("tipo sconosciuto");
    // a file without "stato_maggiore" uses the defaults
    assertThat(parse("{\"id\": \"A\"}").getParametri()).isEqualTo(Strategia.Parametri.DEFAULT);
  }

  // ---------------------------------------------------------------------------- plan change

  @Test
  void aPlanInCrisisForTwoRoundsChangesToABetterTakeOver() {
    final Strategia s = twoPlans();
    final StatoMaggiore.Stato state = new StatoMaggiore.Stato("1", 1, history(entry(6, "1", true)));

    final Contromosse.Cambio change =
        Contromosse.cambio(
            data, "Allies", s, plan(s, "Allies", "1"), state, entry(7, "1", true), 7);

    assertThat(change.nuovo()).map(Strategia.Piano::id).contains("2");
    assertThat(change.motivo()).contains("piano 1 in crisi da 2 round");
    // no milestone, no target: 0.5 each; state: 0 for the plan in crisis, 0.5 for the other
    assertThat(score(change, "1")).isEqualTo(0.33);
    assertThat(score(change, "2")).isEqualTo(0.5);
  }

  @Test
  void oneRoundOfCrisisOrAPlanOnTrackNeverChanges() {
    final Strategia s = twoPlans();
    final Strategia.Piano one = plan(s, "Allies", "1");
    // one round only
    assertThat(
            Contromosse.cambio(
                    data,
                    "Allies",
                    s,
                    one,
                    new StatoMaggiore.Stato("1", 1, history(entry(6, "1", false))),
                    entry(7, "1", true),
                    7)
                .nuovo())
        .isEmpty();
    // on track
    final Contromosse.Cambio onTrack =
        Contromosse.cambio(
            data,
            "Allies",
            s,
            one,
            new StatoMaggiore.Stato("1", 1, history(entry(6, "1", false))),
            entry(7, "1", false),
            7);
    assertThat(onTrack.nuovo()).isEmpty();
    assertThat(onTrack.motivo()).isEqualTo("piano non in crisi");
    // the crisis of round 6 was of another plan: it does not count
    assertThat(
            Contromosse.cambio(
                    data,
                    "Allies",
                    s,
                    one,
                    new StatoMaggiore.Stato("1", 1, history(entry(6, "2", true))),
                    entry(7, "1", true),
                    7)
                .nuovo())
        .isEmpty();
  }

  @Test
  void atLeastThreeRoundsBetweenChangesAndAtMostTwoChanges() {
    final Strategia s = twoPlans();
    final Strategia.Piano two = plan(s, "Allies", "2");
    final JsonArray oneChange = changes(8);
    // changed in round 8: not again in round 10
    final Contromosse.Cambio early =
        Contromosse.cambio(
            data,
            "Allies",
            s,
            two,
            new StatoMaggiore.Stato("2", 1, history(entry(9, "2", true)), oneChange),
            entry(10, "2", true),
            10);
    assertThat(early.nuovo()).isEmpty();
    assertThat(early.motivo()).contains("ultimo cambio al round 8");
    // round 11: allowed
    assertThat(
            Contromosse.cambio(
                    data,
                    "Allies",
                    s,
                    two,
                    new StatoMaggiore.Stato("2", 1, history(entry(10, "2", true)), oneChange),
                    entry(11, "2", true),
                    11)
                .nuovo())
        .map(Strategia.Piano::id)
        .contains("1");
    // two changes done: never a third
    final Contromosse.Cambio third =
        Contromosse.cambio(
            data,
            "Allies",
            s,
            two,
            new StatoMaggiore.Stato("2", 1, history(entry(12, "2", true)), changes(5, 8)),
            entry(13, "2", true),
            13);
    assertThat(third.nuovo()).isEmpty();
    assertThat(third.motivo()).contains("gia' fatti 2 cambi");
  }

  @Test
  void theEnemyPlanReadIsCounteredByThePlanThatPutsItInTrouble() {
    final Strategia s = twoPlans();
    // plan 2 counters the enemy's A, read at 60%: bonus 0.25
    final JsonObject read = entry(7, "1", true);
    final JsonObject reading = new JsonObject();
    reading.addProperty("piu_probabile", "A");
    final JsonObject probs = new JsonObject();
    probs.addProperty("A", 0.6);
    probs.addProperty("D", 0.4);
    reading.add("probabilita", probs);
    read.add("lettura", reading);
    final Contromosse.Cambio change =
        Contromosse.cambio(
            data,
            "Allies",
            s,
            plan(s, "Allies", "1"),
            new StatoMaggiore.Stato("1", 1, history(entry(6, "1", true))),
            read,
            7);
    assertThat(score(change, "2")).isEqualTo(0.75);
    assertThat(change.motivo()).contains("contrasta A letto 60%");
  }

  @Test
  void thePlanInForceInCrisisGetsNoCounterBonus() {
    // step 3d-1-bis: both plans counter the enemy's A read at 60%; plan 1 is in force and in
    // crisis, so only the alternative 2 gets the bonus
    final Strategia s =
        Strategia.parse(
            new StringReader(
                "{\"alleanze\": {"
                    + "\"Allies\": {\"piani\": ["
                    + "{\"id\": \"1\", \"subentri\": [\"2\"], \"contrasta\": [\"A\"]},"
                    + "{\"id\": \"2\", \"subentri\": [\"1\"], \"contrasta\": [\"A\"]}]},"
                    + "\"Axis\": {\"piani\": [{\"id\": \"A\"}, {\"id\": \"D\"}]}}}"));
    final JsonObject read = entry(7, "1", true);
    final JsonObject reading = new JsonObject();
    reading.addProperty("piu_probabile", "A");
    final JsonObject probs = new JsonObject();
    probs.addProperty("A", 0.6);
    probs.addProperty("D", 0.4);
    reading.add("probabilita", probs);
    read.add("lettura", reading);
    final Contromosse.Cambio change =
        Contromosse.cambio(
            data,
            "Allies",
            s,
            plan(s, "Allies", "1"),
            new StatoMaggiore.Stato("1", 1, history(entry(6, "1", true))),
            read,
            7);

    assertThat(score(change, "1")).isEqualTo(0.33);
    assertThat(score(change, "2")).isEqualTo(0.75);
    assertThat(change.nuovo()).map(Strategia.Piano::id).contains("2");
    final JsonObject current = change.json().getAsJsonArray("valutazioni").get(0).getAsJsonObject();
    assertThat(current.get("contrasto").getAsDouble()).isZero();
    assertThat(current.get("contrasto_escluso").getAsString()).contains("in crisi");
  }

  @Test
  void noBetterAlternativeGivesTheScoresOfBothPlans() {
    // a margin of 0.5: the alternative 2 (0.50) is not enough above plan 1 (0.33); the reason
    // lists the parts of both scores
    final Strategia s =
        Strategia.parse(
            new StringReader(
                "{\"stato_maggiore\": {\"margine_cambio\": 0.5}, \"alleanze\": {"
                    + "\"Allies\": {\"piani\": ["
                    + "{\"id\": \"1\", \"subentri\": [\"2\"]},"
                    + "{\"id\": \"2\", \"subentri\": [\"1\"]}]},"
                    + "\"Axis\": {\"piani\": [{\"id\": \"A\"}]}}}"));
    final Contromosse.Cambio change =
        Contromosse.cambio(
            data,
            "Allies",
            s,
            plan(s, "Allies", "1"),
            new StatoMaggiore.Stato("1", 1, history(entry(6, "1", true))),
            entry(7, "1", true),
            7);

    assertThat(change.nuovo()).isEmpty();
    assertThat(change.motivo())
        .contains("nessuna alternativa migliore di 0.50")
        .contains("1 0.33 = (tappe 0.50 + forze 0.50 + stato 0.00)/3 + contrasto 0.00")
        .contains("2 0.50 = (tappe 0.50 + forze 0.50 + stato 0.50)/3 + contrasto 0.00");
  }

  @Test
  void theRealFileMovesTheAlliesFromAStalledOverlordToItalyFirst() {
    final Strategia s = europe();
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    data.getSequence().setRoundAndStep(1, "Purchase", russians);
    StatoMaggiore.fix(data, "Allies", s);
    for (int round = 1; round <= 6; round++) {
      startOfRound(round);
    }
    assertThat(StatoMaggiore.getState(data.getProperties(), "Allies").orElseThrow().piano())
        .isEqualTo("1");
    // Overlord USA in a stall from round 7
    data.getProperties()
        .set(
            StalloOperazioni.PREFIX + "Overlord USA",
            "{\"ultimo_pronto\":0,\"round_senza\":3,\"stallo\":true}");
    final Valutazione.Risultato r7 = startOfRound(7);
    assertThat(r7.storico().get("piano").getAsString()).isEqualTo("1");
    final Valutazione.Risultato r8 = startOfRound(8);

    final StatoMaggiore.Stato state =
        StatoMaggiore.getState(data.getProperties(), "Allies").orElseThrow();
    assertThat(state.piano()).isEqualTo("2");
    assertThat(state.roundPiano()).isEqualTo(8);
    assertThat(state.cambi()).hasSize(1);
    assertThat(state.cambi().get(0).getAsJsonObject().get("da").getAsString()).isEqualTo("1");
    // the entry, the diary text and the strategic state say so
    assertThat(r8.storico().get("piano").getAsString()).isEqualTo("2");
    final JsonObject strategic = r8.storico().getAsJsonObject("stato_strategico");
    assertThat(strategic.get("piano").getAsString()).isEqualTo("2");
    assertThat(strategic.getAsJsonArray("cambi_piano")).hasSize(1);
    assertThat(r8.testo()).contains("Stato maggiore: postura ").contains("(cambiato da 1 perche'");
    // the new plan's milestones are not late at once
    assertThat(r8.storico().getAsJsonObject("avanzamento").get("stato").getAsString())
        .isNotEqualTo(Valutazione.IN_CRISI);
    // the orders follow the new plan in the same round
    data.getSequence().setRoundAndStep(8, "Purchase", player("British"));
    assertThat(PianoAttivo.of(s, data, player("British"), "Allies").orElseThrow().getPiano().id())
        .isEqualTo("2");
  }

  @Test
  void aPlanOnTrackKeepsItsPlanAndNoCounterMoveForPlanN() {
    final Strategia s = europe();
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "N");
    data.getSequence().setRoundAndStep(1, "Purchase", germans);
    StatoMaggiore.fix(data, "Axis", s);
    for (int round = 1; round <= 5; round++) {
      data.getSequence().setRoundAndStep(round, "Purchase", germans);
      final JsonObject strategic =
          Valutazione.inizioRound(data, germans)
              .orElseThrow()
              .storico()
              .getAsJsonObject("stato_strategico");
      assertThat(strategic.get("postura").getAsString()).isEqualTo(Contromosse.NESSUNA);
      assertThat(strategic.getAsJsonArray("contro_obiettivi")).isEmpty();
    }
    assertThat(StatoMaggiore.getState(data.getProperties(), "Axis").orElseThrow().cambi())
        .isEmpty();
  }

  // ------------------------------------------------------------------------------ posture

  @Test
  void theRaceIsEvenAheadOrBehind() {
    final Contromosse.Corsa even = race(99, 99);
    assertThat(even.pari()).isTrue();
    assertThat(race(5, 6).pari()).isTrue();
    assertThat(race(3, 8).inVantaggio()).isTrue();
    assertThat(race(8, 3).nemicoPiuVeloce()).isTrue();
    assertThat(race(99, 4).nemicoPiuVeloce()).isTrue();
    assertThat(race(8, 3).testo()).contains("nemico piu' veloce");
  }

  @Test
  void inCrisisWithAFasterEnemyTheAllianceDefends() {
    final Strategia s = europe();
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    data.getSequence().setRoundAndStep(4, "Purchase", russians);
    StatoMaggiore.fix(data, "Allies", s);
    final int axisVc = Contromosse.victoryCities(data, "Axis");
    final int alliesVc = Contromosse.held(data, "Allies", List.of("Germany", "Southern Italy"));
    // the Axis gained 3 VCs in 3 rounds, the Allies none
    final StatoMaggiore.Stato state =
        new StatoMaggiore.Stato(
            "1", 1, history(entryWithVc(1, axisVc - 3, alliesVc)), new JsonArray());
    final JsonObject now = entry(4, "1", true);
    now.getAsJsonObject("avanzamento").addProperty("stato", Valutazione.IN_CRISI);

    final JsonObject strategic =
        Contromosse.statoStrategico(
            data,
            "Allies",
            s,
            plan(s, "Allies", "1"),
            state,
            now,
            new Contromosse.Cambio(Optional.empty(), "x", new JsonObject()),
            4);

    assertThat(strategic.get("postura").getAsString())
        .as(strategic.toString())
        .isEqualTo(Contromosse.DIFENSIVA);
    assertThat(strategic.get("motivo").getAsString()).contains("piano IN CRISI");
    assertThat(strategic.getAsJsonObject("corsa").get("esito").getAsString())
        .isEqualTo("nemico piu' veloce");
  }

  @Test
  void aFasterEnemyWithAnAttackableCriticalPointIsCountered() {
    final Strategia s = europe();
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    data.getSequence().setRoundAndStep(4, "Purchase", russians);
    StatoMaggiore.fix(data, "Allies", s);
    final Territory romania = data.getMap().getTerritoryOrThrow("Romania");
    data.performChange(ChangeFactory.changeOwner(romania, germans));
    final int axisVc = Contromosse.victoryCities(data, "Axis");
    final int alliesVc = Contromosse.held(data, "Allies", List.of("Germany", "Southern Italy"));
    final StatoMaggiore.Stato state =
        new StatoMaggiore.Stato(
            "1", 1, history(entryWithVc(1, axisVc - 3, alliesVc)), new JsonArray());
    final JsonObject now = readA(entry(4, "1", false), 0.6);

    final JsonObject strategic =
        Contromosse.statoStrategico(
            data,
            "Allies",
            s,
            plan(s, "Allies", "1"),
            state,
            now,
            new Contromosse.Cambio(Optional.empty(), "x", new JsonObject()),
            4);

    assertThat(strategic.get("postura").getAsString())
        .as(strategic.toString())
        .isEqualTo(Contromosse.CONTRASTO);
    assertThat(counterObjectives(strategic)).containsEntry("Romania", 20.0);
  }

  @Test
  void anEvenRaceAndAClearReadingSlowTheEnemyDown() {
    final Strategia s = europe();
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    data.getSequence().setRoundAndStep(2, "Purchase", russians);
    StatoMaggiore.fix(data, "Allies", s);
    data.performChange(
        ChangeFactory.changeOwner(data.getMap().getTerritoryOrThrow("Romania"), germans));
    // no history: no trend, both "never"
    final JsonObject strategic =
        Contromosse.statoStrategico(
            data,
            "Allies",
            s,
            plan(s, "Allies", "1"),
            new StatoMaggiore.Stato("1", 1, new JsonArray()),
            readA(entry(2, "1", false), 0.7),
            new Contromosse.Cambio(Optional.empty(), "x", new JsonObject()),
            2);
    assertThat(strategic.get("postura").getAsString())
        .as(strategic.toString())
        .isEqualTo(Contromosse.RALLENTAMENTO);
    assertThat(counterObjectives(strategic)).containsEntry("Romania", 8.0);
    // read at 50%: no counter-move
    final JsonObject unsure =
        Contromosse.statoStrategico(
            data,
            "Allies",
            s,
            plan(s, "Allies", "1"),
            new StatoMaggiore.Stato("1", 1, new JsonArray()),
            readA(entry(2, "1", false), 0.5),
            new Contromosse.Cambio(Optional.empty(), "x", new JsonObject()),
            2);
    assertThat(unsure.get("postura").getAsString()).isEqualTo(Contromosse.OFFENSIVA);
    assertThat(unsure.getAsJsonArray("contro_obiettivi")).isEmpty();
  }

  @Test
  void theOrdersFollowThePosture() {
    final Strategia s = europe();
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    data.getSequence().setRoundAndStep(4, "Purchase", russians);
    StatoMaggiore.fix(data, "Allies", s);
    final PianoAttivo before = PianoAttivo.of(s, data, russians, "Allies").orElseThrow();
    final Map<String, RiskLevel> levels = before.livelliPerFronte();
    final Territory romania = data.getMap().getTerritoryOrThrow("Romania");
    assertThat(before.peso(romania)).isZero();

    // CONTRASTO on Romania this round: a main objective of every nation of the alliance
    StatoMaggiore.aggiungiStorico(
        data, "Allies", decided(4, "1", Contromosse.CONTRASTO, "Romania"));
    final PianoAttivo countering = PianoAttivo.of(s, data, russians, "Allies").orElseThrow();
    assertThat(countering.peso(romania)).isEqualTo(20);
    assertThat(countering.isObiettivo(romania)).isTrue();
    assertThat(countering.livelliPerFronte()).isEqualTo(levels);
    // not in another round
    data.getSequence().setRoundAndStep(5, "Purchase", russians);
    assertThat(PianoAttivo.of(s, data, russians, "Allies").orElseThrow().peso(romania)).isZero();

    // DIFENSIVA: risk -1 on every front
    StatoMaggiore.aggiungiStorico(data, "Allies", decided(5, "1", Contromosse.DIFENSIVA, null));
    final PianoAttivo defending = PianoAttivo.of(s, data, russians, "Allies").orElseThrow();
    assertThat(defending.isDifensiva()).isTrue();
    defending
        .livelliPerFronte()
        .forEach((front, level) -> assertThat(level).isEqualTo(levels.get(front).withModifier(-1)));
  }

  // ------------------------------------------------------------------------------ helpers

  private Valutazione.Risultato startOfRound(final int round) {
    data.getSequence().setRoundAndStep(round, "Purchase", russians);
    return Valutazione.inizioRound(data, russians).orElseThrow();
  }

  private static double score(final Contromosse.Cambio change, final String plan) {
    for (final var e : change.json().getAsJsonArray("valutazioni")) {
      final JsonObject v = e.getAsJsonObject();
      if (v.get("piano").getAsString().equals(plan)) {
        return v.get("punteggio").getAsDouble();
      }
    }
    throw new AssertionError("no score for " + plan);
  }

  /** A history entry of {@code plan} in {@code round}, in crisis (a stalled operation) or not. */
  private static JsonObject entry(final int round, final String plan, final boolean crisis) {
    final JsonObject e = new JsonObject();
    e.addProperty("round", round);
    e.addProperty("piano", plan);
    final JsonObject p = new JsonObject();
    p.addProperty("stato", crisis ? Valutazione.IN_RITARDO : Valutazione.IN_LINEA);
    final JsonArray stalled = new JsonArray();
    if (crisis) {
      stalled.add("X");
    }
    p.add("operazioni_in_stallo", stalled);
    e.add("avanzamento", p);
    return e;
  }

  private static JsonObject entryWithVc(final int round, final int axis, final int allies) {
    final JsonObject e = entry(round, "1", false);
    final JsonObject s = new JsonObject();
    s.addProperty("postura", Contromosse.OFFENSIVA);
    final JsonObject corsa = new JsonObject();
    final JsonObject vc = new JsonObject();
    vc.addProperty("Axis", axis);
    vc.addProperty("Allies", allies);
    corsa.add("vc", vc);
    s.add("corsa", corsa);
    e.add("stato_strategico", s);
    return e;
  }

  private static JsonObject readA(final JsonObject e, final double p) {
    final JsonObject reading = new JsonObject();
    reading.addProperty("piu_probabile", "A");
    final JsonObject probs = new JsonObject();
    probs.addProperty("A", p);
    probs.addProperty("D", 1 - p);
    reading.add("probabilita", probs);
    e.add("lettura", reading);
    return e;
  }

  /** A history entry of {@code round} whose decision is {@code posture} on {@code target}. */
  private static JsonObject decided(
      final int round, final String plan, final String posture, final String target) {
    final JsonObject e = entry(round, plan, false);
    final JsonObject s = new JsonObject();
    s.addProperty("piano", plan);
    s.addProperty("postura", posture);
    final JsonArray counter = new JsonArray();
    if (target != null) {
      final JsonObject o = new JsonObject();
      o.addProperty("territorio", target);
      o.addProperty("peso", 20);
      counter.add(o);
    }
    s.add("contro_obiettivi", counter);
    e.add("stato_strategico", s);
    return e;
  }

  private static Map<String, Double> counterObjectives(final JsonObject strategic) {
    final Map<String, Double> map = new java.util.TreeMap<>();
    strategic
        .getAsJsonArray("contro_obiettivi")
        .forEach(
            e ->
                map.put(
                    e.getAsJsonObject().get("territorio").getAsString(),
                    e.getAsJsonObject().get("peso").getAsDouble()));
    return map;
  }

  private static JsonArray history(final JsonObject... entries) {
    final JsonArray a = new JsonArray();
    for (final JsonObject e : entries) {
      a.add(e);
    }
    return a;
  }

  private static JsonArray changes(final int... rounds) {
    final JsonArray a = new JsonArray();
    for (final int r : rounds) {
      final JsonObject c = new JsonObject();
      c.addProperty("round", r);
      c.addProperty("da", "1");
      c.addProperty("a", "2");
      c.addProperty("motivo", "test");
      a.add(c);
    }
    return a;
  }

  private static Contromosse.Corsa race(final int mine, final int theirs) {
    return new Contromosse.Corsa(
        "Allies",
        "Axis",
        Map.of("Allies", 3, "Axis", 6),
        Map.of("Allies", 9, "Axis", 8),
        Map.of("Allies", 0.0, "Axis", 1.0),
        Map.of("Allies", mine, "Axis", theirs),
        2);
  }

  /** Allied plans 1 and 2 taking over from each other, no milestone, no target. */
  private static Strategia twoPlans() {
    return Strategia.parse(
        new StringReader(
            "{\"alleanze\": {"
                + "\"Allies\": {\"piani\": ["
                + "{\"id\": \"1\", \"subentri\": [\"2\"]},"
                + "{\"id\": \"2\", \"subentri\": [\"1\"], \"contrasta\": [\"A\"]}]},"
                + "\"Axis\": {\"piani\": [{\"id\": \"A\"}, {\"id\": \"D\"}]}}}"));
  }

  private static Strategia parse(final String plan) {
    return Strategia.parse(
        new StringReader("{\"alleanze\": {\"Axis\": {\"piani\": [" + plan + "]}}}"));
  }

  private static Strategia.Piano plan(final Strategia s, final String alliance, final String id) {
    return s.alleanza(alliance).orElseThrow().piano(id).orElseThrow();
  }

  private Strategia europe() {
    data.setGameName(EUROPE);
    return Strategie.forGame(data).orElseThrow();
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }
}
