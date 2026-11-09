package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.Ablazione;
import games.strategy.triplea.ai.proplus.DecisoreOperazione;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Stato;
import games.strategy.triplea.ai.proplus.OperazioniCongiunte;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.StoricoNemico;
import games.strategy.triplea.ai.proplus.strategia.DecisioneOperazione;
import games.strategy.triplea.ai.proplus.strategia.DecisioneOperazione.Azione;
import games.strategy.triplea.ai.proplus.strategia.DecisioneOperazione.Punto;
import games.strategy.triplea.ai.proplus.strategia.llm.ErroreStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.PromptOperazione;
import games.strategy.triplea.ai.proplus.strategia.llm.Richiesta;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Step PROVALLM: the LLM at the decision points of the joint operations v2. Every call is a fake
 * (no network): parsing of the decision (contract v1.2), the formula on an invalid answer, a
 * timeout or the budget, no call with the switch off, the state sent with the enemy's history, the
 * keep/leave decision of a held target and the real outcome saved for the calibration.
 */
class OperazioniLlmTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261110L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final List<Richiesta> asked = new ArrayList<>();

  @BeforeEach
  void setUp() {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    Ablazione.set(data.getProperties(), "Axis", Ablazione.NUDO);
    Ablazione.set(data.getProperties(), "Allies", Ablazione.NUDO);
  }

  @AfterEach
  void tearDown() {
    DecisoreOperazione.setFabbrica(null);
  }

  // ------------------------------------------------------------------------------- contract

  @Test
  void theDecisionIsReadAndCheckedAgainstThePoint() {
    final DecisioneOperazione d =
        DecisioneOperazione.leggi(
            "```json\n{\"decisione_operazione\": {\"azione\": \"Lancia\","
                + " \"prob_tenuta_stimata\": 0.62, \"motivazione\": \"il Regno Unito ha solo 2"
                + " corazzati in portata\"}}\n```",
            Punto.TAPPA1);
    assertThat(d.azione()).isEqualTo(Azione.LANCIA);
    assertThat(d.probTenuta()).isEqualTo(0.62);
    assertThat(d.esegue()).isTrue();
    // fields at the root are accepted too
    assertThat(
            DecisioneOperazione.leggi(
                    "{\"azione\":\"tieni\",\"prob_tenuta_stimata\":1,\"motivazione\":\"\"}",
                    Punto.TENUTA)
                .azione())
        .isEqualTo(Azione.TIENI);
    // an action of another point, an unknown one, a probability out of 0-1, no JSON
    assertThatThrownBy(
            () ->
                DecisioneOperazione.leggi(
                    "{\"azione\":\"procedi\",\"prob_tenuta_stimata\":0.5,\"motivazione\":\"\"}",
                    Punto.TAPPA1))
        .isInstanceOf(ErroreStratega.class)
        .hasMessageContaining("non ammessa");
    assertThatThrownBy(
            () ->
                DecisioneOperazione.leggi(
                    "{\"azione\":\"attacca\",\"prob_tenuta_stimata\":0.5,\"motivazione\":\"\"}",
                    Punto.TAPPA2))
        .isInstanceOf(ErroreStratega.class);
    assertThatThrownBy(
            () ->
                DecisioneOperazione.leggi(
                    "{\"azione\":\"tieni\",\"prob_tenuta_stimata\":55,\"motivazione\":\"\"}",
                    Punto.TENUTA))
        .isInstanceOf(ErroreStratega.class)
        .hasMessageContaining("fuori da 0-1");
    assertThatThrownBy(() -> DecisioneOperazione.leggi("lancia!", Punto.TAPPA1))
        .isInstanceOf(ErroreStratega.class);
    // the schema is closed and asks the three fields
    final JsonObject inner =
        PromptOperazione.schema()
            .getAsJsonObject("properties")
            .getAsJsonObject(DecisioneOperazione.CHIAVE);
    assertThat(inner.get("additionalProperties").getAsBoolean()).isFalse();
    assertThat(inner.getAsJsonArray("required")).hasSize(3);
    assertThat(PromptOperazione.sistema())
        .contains("REALE")
        .contains("tappa1")
        .doesNotContain("{{");
  }

  // ------------------------------------------------------------------------------ parachute

  @Test
  void theFormulaDecidesOnAnInvalidAnswerATimeoutOrTheBudget() {
    final var props = data.getProperties();
    final DecisioneOperazione formula = new DecisioneOperazione(Azione.RINVIA, 0.1, "formula");
    // switch off: no call at all
    fake(r -> answer("{\"azione\":\"lancia\",\"prob_tenuta_stimata\":0.9,\"motivazione\":\"\"}"));
    var x = DecisoreOperazione.decidi(props, "Axis", Punto.TAPPA1, new JsonObject(), formula);
    assertThat(x.decisione()).isSameAs(formula);
    assertThat(x.ripiego()).isFalse();
    assertThat(asked).isEmpty();

    DecisoreOperazione.set(props, "Axis", "llm");
    assertThat(DecisoreOperazione.modello(props, "Axis")).contains("claude-sonnet-5-5");
    // a valid answer
    x = DecisoreOperazione.decidi(props, "Axis", Punto.TAPPA1, new JsonObject(), formula);
    assertThat(x.llm()).isTrue();
    assertThat(x.decisione().azione()).isEqualTo(Azione.LANCIA);
    // not a valid decision: the formula
    fake(r -> answer("{\"azione\":\"procedi\",\"prob_tenuta_stimata\":0.9,\"motivazione\":\"\"}"));
    x = DecisoreOperazione.decidi(props, "Axis", Punto.TAPPA1, new JsonObject(), formula);
    assertThat(x.decisione()).isSameAs(formula);
    assertThat(x.motivoRipiego()).isEqualTo(DecisoreOperazione.RIPIEGO_NON_VALIDA);
    // timeout (the client's error): the formula
    fake(
        r -> {
          throw new ErroreStratega("nessuna risposta da Haiku entro 45 s (2 tentativi)");
        });
    x = DecisoreOperazione.decidi(props, "Axis", Punto.TAPPA1, new JsonObject(), formula);
    assertThat(x.decisione()).isSameAs(formula);
    assertThat(x.motivoRipiego()).isEqualTo(DecisoreOperazione.RIPIEGO_ERRORE);
    assertThat(x.dettaglio()).contains("entro 45 s");
    // budget: the next call would go over it, no call
    fake(r -> answer("{\"azione\":\"lancia\",\"prob_tenuta_stimata\":0.9,\"motivazione\":\"\"}"));
    final int before = asked.size();
    DecisoreOperazione.setBudget(props, 0.05);
    x = DecisoreOperazione.decidi(props, "Axis", Punto.TAPPA1, new JsonObject(), formula);
    assertThat(x.decisione()).isSameAs(formula);
    assertThat(x.motivoRipiego()).isEqualTo(DecisoreOperazione.RIPIEGO_BUDGET);
    assertThat(asked).hasSize(before);
    // the usage of the game
    final JsonObject u = DecisoreOperazione.uso(props);
    assertThat(u.get("chiamate").getAsInt()).isEqualTo(3);
    assertThat(u.get("errori").getAsInt()).isEqualTo(1);
    assertThat(u.get("risposte_non_valide").getAsInt()).isEqualTo(1);
    assertThat(u.get("costo_usd").getAsDouble()).isEqualTo(0.04);
    assertThat(u.getAsJsonObject("ripieghi").get(DecisoreOperazione.RIPIEGO_BUDGET).getAsInt())
        .isEqualTo(1);
    // no key anywhere in what is saved
    assertThat(u.toString()).doesNotContain("sk-ant");
  }

  // ------------------------------------------------------------------------- in the engine

  @Test
  void switchOffMeansNoCallAndNoRecord() {
    fake(r -> answer("{\"azione\":\"ritira\",\"prob_tenuta_stimata\":0.1,\"motivazione\":\"\"}"));
    final GamePlayer italians = heldEgypt(1);
    final IMoveDelegate moves = recordingMoves();
    OperazioniCongiunte.nonCombattimento(proData(italians, 2, "Non Combat Move"), calc(), moves);
    assertThat(asked).isEmpty();
    final Registro reg = Registro.leggi(data.getProperties(), "egitto");
    assertThat(reg.json().has("decisioni")).isFalse();
    assertThat(data.getProperties().get(StoricoNemico.PREFIX + "egitto")).isNull();
    assertThat(data.getProperties().get(DecisoreOperazione.KEY_USO)).isNull();
    // the formula reinforced the weak hold as before
    assertThat(reinforcedFromAlexandria(moves)).isTrue();
  }

  @Test
  void theLlmLeavesAWeakHoldAndTheOutcomeAndTheEnemysAnswerAreSaved() {
    fake(
        r ->
            answer(
                "{\"decisione_operazione\":{\"azione\":\"ritira\",\"prob_tenuta_stimata\":0.15,"
                    + "\"motivazione\":\"2 corazzati britannici a distanza 1\"}}"));
    final GamePlayer italians = heldEgypt(1);
    DecisoreOperazione.set(data.getProperties(), "Axis", "llm:claude-sonnet-5-5");
    final IMoveDelegate moves = recordingMoves();
    final var nc =
        OperazioniCongiunte.nonCombattimento(
            proData(italians, 2, "Non Combat Move"), calc(), moves);
    assertThat(asked).hasSize(1);
    assertThat(reinforcedFromAlexandria(moves)).as("no reinforcements after 'ritira'").isFalse();
    assertThat(nc.righe()).anyMatch(l -> l.contains("decisione tenuta") && l.contains("RITIRA"));

    // the state sent: target, counterattack by origin, hold by commitment, history, priorities
    final JsonObject in = state(asked.get(0));
    assertThat(in.get("punto").getAsString()).isEqualTo("tenuta");
    assertThat(in.get("azioni_ammesse").getAsString()).isEqualTo("tieni | ritira");
    assertThat(in.get("bersaglio").getAsString()).isEqualTo("Egypt");
    final JsonObject c = in.getAsJsonObject("contrattacco_massimo");
    assertThat(c.getAsJsonArray("per_origine"))
        .anyMatch(
            o ->
                o.getAsJsonObject().get("da").getAsString().equals("Trans-Jordan")
                    && o.getAsJsonObject().get("distanza").getAsInt() == 1);
    assertThat(in.getAsJsonArray("tenuta_per_impegno")).hasSize(4);
    assertThat(in.has("storico_nemico")).isTrue();
    assertThat(in.has("priorita_nemico")).isTrue();
    assertThat(in.getAsJsonObject("partita").get("vc_alleanza").getAsInt()).isPositive();
    assertThat(in.getAsJsonObject("formula_v2").get("azione").getAsString())
        .isIn("tieni", "ritira");

    // the decision saved with both estimates; the garrison goes to Hard at the next combat move
    Registro reg = Registro.leggi(data.getProperties(), "egitto");
    final JsonObject rec = reg.json().getAsJsonArray("decisioni").get(0).getAsJsonObject();
    assertThat(rec.get("azione").getAsString()).isEqualTo("ritira");
    assertThat(rec.get("prob_llm").getAsDouble()).isEqualTo(0.15);
    assertThat(rec.has("prob_formula")).isTrue();
    assertThat(rec.get("fonte").getAsString()).isEqualTo("Sonnet");
    final List<Unit> garrison = territory("Egypt").getMatches(u -> u.getOwner().equals(italians));
    assertThat(
            OperazioniCongiunte.combattimento(
                    proData(italians, 3, "Combat Move"), calc(), recordingMoves())
                .tenute())
        .doesNotContainAnyElementsOf(garrison);

    // the enemy noted in reach at the end of the turn; then the British armour takes Egypt back
    final var notes = StoricoNemico.leggi(data.getProperties(), "egitto");
    assertThat(notes).hasSize(1);
    final Territory tj = territory("Trans-Jordan");
    final List<Unit> armour = tj.getMatches(Matches.unitIsOwnedBy(player("British")));
    data.performChange(ChangeFactory.moveUnits(tj, territory("Egypt"), armour));
    data.performChange(ChangeFactory.changeOwner(territory("Egypt"), player("British")));
    update(italians, 3);
    final JsonObject seen =
        StoricoNemico.leggi(data.getProperties(), "egitto")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("territori")
            .get(0)
            .getAsJsonObject();
    assertThat(seen.get("territorio").getAsString()).isEqualTo("Egypt");
    assertThat(seen.get("ripreso").getAsBoolean()).isTrue();
    assertThat(seen.get("tuv_impegnato").getAsInt()).isPositive();
    assertThat(seen.get("frazione_impegnata").getAsDouble()).isGreaterThan(0);
    final JsonObject summary = StoricoNemico.riassunto(data.getProperties(), "egitto");
    assertThat(summary.get("osservazioni").getAsInt()).isEqualTo(1);
    assertThat(summary.get("ripresi").getAsInt()).isEqualTo(1);
    // the real outcome of the decision (at the closer's turn of the next round): lost
    reg = Registro.leggi(data.getProperties(), "egitto");
    final JsonObject settled = reg.json().getAsJsonArray("decisioni").get(0).getAsJsonObject();
    assertThat(settled.get("esito_reale").getAsBoolean()).isFalse();
    assertThat(reg.stato()).isEqualTo(Stato.FALLITA);
  }

  @Test
  void theLlmKeepsAWeakHoldWithTheEnginesReinforcements() {
    fake(
        r ->
            answer(
                "{\"decisione_operazione\":{\"azione\":\"tieni\",\"prob_tenuta_stimata\":0.6,"
                    + "\"motivazione\":\"il Regno Unito difende il Medio Oriente\"}}"));
    final GamePlayer italians = heldEgypt(1);
    DecisoreOperazione.set(data.getProperties(), "Axis", "llm");
    final IMoveDelegate moves = recordingMoves();
    OperazioniCongiunte.nonCombattimento(proData(italians, 2, "Non Combat Move"), calc(), moves);
    assertThat(asked).hasSize(1);
    assertThat(reinforcedFromAlexandria(moves)).isTrue();
    // the garrison stays locked at the next combat move
    final List<Unit> garrison = territory("Egypt").getMatches(u -> u.getOwner().equals(italians));
    assertThat(
            OperazioniCongiunte.combattimento(
                    proData(italians, 3, "Combat Move"), calc(), recordingMoves())
                .tenute())
        .containsAll(garrison);
  }

  @Test
  void anInvalidAnswerInTheEngineFallsBackToTheFormula() {
    fake(r -> answer("non so"));
    final GamePlayer italians = heldEgypt(1);
    DecisoreOperazione.set(data.getProperties(), "Axis", "llm");
    final IMoveDelegate moves = recordingMoves();
    OperazioniCongiunte.nonCombattimento(proData(italians, 2, "Non Combat Move"), calc(), moves);
    assertThat(reinforcedFromAlexandria(moves)).as("the formula's reinforcements").isTrue();
    final JsonObject rec =
        Registro.leggi(data.getProperties(), "egitto")
            .json()
            .getAsJsonArray("decisioni")
            .get(0)
            .getAsJsonObject();
    assertThat(rec.get("ripiego").getAsBoolean()).isTrue();
    assertThat(rec.get("motivo_ripiego").getAsString())
        .isEqualTo(DecisoreOperazione.RIPIEGO_NON_VALIDA);
    assertThat(rec.has("prob_llm")).isFalse();
    assertThat(rec.get("fonte").getAsString()).isEqualTo("formula");
  }

  // ------------------------------------------------------------------------------- helpers

  private void fake(final Function<Richiesta, Risposta> f) {
    DecisoreOperazione.setFabbrica(
        model ->
            new DecisoreOperazione.Trasporto() {
              @Override
              public Optional<String> nonDisponibile() {
                return Optional.empty();
              }

              @Override
              public Risposta chiama(final Richiesta r) {
                asked.add(r);
                return f.apply(r);
              }
            });
  }

  private static Risposta answer(final String text) {
    return new Risposta(text, "Sonnet", "claude-sonnet-5-5", 1000, 100, 0, 0, 0.02, false, 5);
  }

  private static JsonObject state(final Richiesta r) {
    final String t = r.utente();
    return JsonParser.parseString(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
        .getAsJsonObject();
  }

  private static boolean reinforcedFromAlexandria(final IMoveDelegate moves) {
    final ArgumentCaptor<MoveDescription> captor = ArgumentCaptor.forClass(MoveDescription.class);
    try {
      verify(moves, org.mockito.Mockito.atLeast(0)).performMove(captor.capture());
    } catch (final RuntimeException e) {
      return false;
    }
    return captor.getAllValues().stream()
        .anyMatch(
            m ->
                m.getRoute().getEnd().getName().equals("Egypt")
                    && m.getRoute().getStart().getName().equals("Alexandria"));
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private ProData proData(final GamePlayer player, final int round, final String step) {
    data.getSequence().setRoundAndStep(round, step, player);
    final ProPlusTuning plus = new ProPlusTuning();
    final ProData proData = new ProData(false, plus);
    plus.attach(proData);
    proData.initializeSimulation(
        new TestAi(player.getName(), proData, new SeededBattleCalculator(SEED)), data, player);
    return proData;
  }

  private ProOddsCalculator calc() {
    final SeededBattleCalculator calculator = new SeededBattleCalculator(SEED);
    calculator.setGameData(data).join();
    return new ProOddsCalculator(calculator);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrNull(name);
  }

  private void update(final GamePlayer player, final int round) {
    OperazioniCongiunte.aggiorna(proData(player, round, "Purchase"), calc());
  }

  private void ownedBy(final String territory, final String nation) {
    data.performChange(ChangeFactory.changeOwner(territory(territory), player(nation)));
  }

  private void clear(final String territory) {
    final Territory t = territory(territory);
    final List<Unit> units = t.getMatches(Matches.unitIsNotInfrastructure());
    if (!units.isEmpty()) {
      data.performChange(ChangeFactory.removeUnits(t, units));
    }
  }

  private void add(
      final String territory, final GamePlayer owner, final String type, final int count) {
    data.performChange(
        ChangeFactory.addUnits(
            territory(territory),
            data.getUnitTypeList().getUnitTypeOrThrow(type).create(count, owner)));
  }

  /**
   * Egypt taken by the Italians in round {@code since} and held by 1 infantry, 3 more in Alexandria
   * (Italian too), 2 British armour in Trans-Jordan: the operation in its hold (as in {@code
   * OperazioniCongiunteTest}).
   */
  private GamePlayer heldEgypt(final int since) {
    OperazioneCongiunta.set(data.getProperties(), "Axis", "egitto");
    final GamePlayer italians = player("Italians");
    ownedBy("Egypt", "Italians");
    clear("Egypt");
    add("Egypt", italians, "infantry", 1);
    ownedBy("Alexandria", "Italians");
    clear("Alexandria");
    add("Alexandria", italians, "infantry", 3);
    add("Trans-Jordan", player("British"), "armour", 2);
    final Registro reg = Registro.leggi(data.getProperties(), "egitto");
    reg.setStato(Stato.TENUTA);
    reg.setRoundObiettivo(since);
    reg.set("tenuta_bersaglio", "Egypt");
    reg.set("tenuta_dal", since);
    reg.set("presa", true);
    reg.set("round_presa", since);
    reg.salva(data.getProperties());
    return italians;
  }

  private static IMoveDelegate recordingMoves() {
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any(MoveDescription.class))).thenReturn(Optional.empty());
    return moveDel;
  }

  /** An AI with seeded battle dice. */
  private static final class TestAi extends AbstractProAi {
    private final SeededBattleCalculator calculator;

    TestAi(final String name, final ProData proData, final SeededBattleCalculator calculator) {
      super(name, calculator, proData, ProPlusAi.PLAYER_LABEL);
      this.calculator = calculator;
    }

    @Override
    protected void prepareData(final GameData data) {
      calculator.setGameData(data).join();
    }
  }
}
