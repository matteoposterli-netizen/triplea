package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Stato;
import games.strategy.triplea.ai.proplus.StrategaCandidati.Candidato;
import games.strategy.triplea.ai.proplus.StrategaCandidati.Tipo;
import games.strategy.triplea.ai.proplus.diario.DiarySettings;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step C2: the computed defense of the "Pro+ Stratega LLM" player (threat of every real enemy
 * together, maximum on capitals and key factories, realistic elsewhere, value against cost, the
 * LLM's lists, the strategy budget, the same estimate as the holds) and the tracking of the units
 * an operation counts. No LLM call.
 */
class DifesaTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261111L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private @TempDir Path profile;
  private @TempDir Path diary;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
    System.setProperty(DiarySettings.FOLDER_PROPERTY, diary.toString());
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    Stratega.registra(data, player("Germans"));
    Stratega.registra(data, player("Italians"));
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
    System.clearProperty(DiarySettings.FOLDER_PROPERTY);
  }

  @Test
  void theThreatCombinesEveryRealEnemyThatMovesBeforeTheNextTurn() {
    // Northern Italy: British and French units can both reach it before Germany plays again
    final Difesa.Piano plan = plan("Germans");
    final Difesa.Voce ni = voce(plan, "Northern Italy");
    assertThat(ni.nemici()).contains("British", "French");
    assertThat(ni.tuvContrattacco()).isPositive();
    // only real enemies: the Russians (not at war in round 1) never
    assertThat(plan.voci()).allMatch(v -> !v.nemici().contains("Russians"));
  }

  @Test
  void theCounterattackIsTheMaximumOnCapitalsAndKeyFactoriesAndRealisticElsewhere() {
    final Difesa.Piano plan = plan("Germans");
    assertThat(plan.voci()).isNotEmpty();
    for (final Difesa.Voce v : plan.voci()) {
      final Territory t = v.territorio();
      final boolean capital =
          TerritoryAttachment.get(t).map(TerritoryAttachment::isCapital).orElse(false);
      final boolean keyFactory =
          t.anyUnitsMatch(Matches.unitCanProduceUnits())
              && TerritoryAttachment.getProduction(t)
                  >= StrategaConfig.get().produzioneFabbricaChiave();
      if (capital || keyFactory) {
        assertThat(v.impegno().frazione()).as(t.getName()).isEqualTo(1.0);
      } else {
        assertThat(v.impegno().frazione())
            .as(t.getName())
            .isEqualTo(StrategaConfig.get().impegnoNemicoPrudente());
      }
    }
    assertThat(voce(plan, "Western Germany").impegno().base()).startsWith("massimo");
  }

  @Test
  void anExpendableTerritoryGetsNoReinforcementNorPurchaseOfThePlan() {
    final Difesa.Piano before = plan("Germans");
    final Difesa.Voce wg = voce(before, "Western Germany");
    lists("Axis", List.of(), List.of("Western Germany"));
    final Difesa.Piano plan = plan("Germans");
    final Difesa.Voce given = voce(plan, "Western Germany");
    assertThat(given.difendi()).isFalse();
    assertThat(given.motivo()).startsWith("sacrificabile");
    assertThat(given.rinforzi()).isEmpty();
    assertThat(given.nAcquisti()).isZero();
    assertThat(wg.difendi()).isTrue();
    assertThat(given.presidio()).isEmpty();
    assertThat(plan.perAcquisto().purchases())
        .noneMatch(p -> p.territory().equals("Western Germany"));
    // the capital can never be expendable
    lists("Axis", List.of(), List.of("Germany"));
    assertThat(Difesa.liste(data.getProperties(), "Axis", 1).sacrificabili()).isEmpty();
    assertThat(wg).isNotNull();
  }

  @Test
  void anIrreplaceableTerritoryIsDefendedEvenIfItCostsMoreThanItIsWorth() {
    // the rule: costs more than it is worth -> given up by the engine, defended if irreplaceable
    final Difesa.Decisione engine =
        Difesa.decidi(Optional.empty(), Optional.empty(), 10, 80, 70, 5, 4, 12);
    assertThat(engine.difendi()).isFalse();
    assertThat(engine.motivo()).startsWith("costo 12 > valore 4");
    final Difesa.Decisione must =
        Difesa.decidi(Optional.of("lista dell'LLM (Suez)"), Optional.empty(), 10, 80, 70, 5, 4, 12);
    assertThat(must.difendi()).isTrue();
    assertThat(must.fonte()).isEqualTo("llm");
    // also when it cannot reach the threshold, and over an expendable entry
    assertThat(
            Difesa.decidi(Optional.of("capitale"), Optional.of("x"), 0, 2, 70, 5, 1, 50).difendi())
        .isTrue();
    // and in a real plan: Denmark (defended by value) stays defended when listed
    lists("Axis", List.of("Denmark"), List.of());
    final Difesa.Voce now = voce(plan("Germans"), "Denmark");
    assertThat(now.difendi()).isTrue();
    assertThat(now.fonte()).isEqualTo("llm");
    assertThat(now.motivo()).startsWith("irrinunciabile");
  }

  @Test
  void theStrategyBudgetIsTheAvailablePusMinusTheDefenseWithinTheCap() {
    final Difesa.Piano plan = plan("Germans");
    final int pus = player("Germans").getResources().getQuantity(Constants.PUS);
    assertThat(plan.pu()).isEqualTo(pus);
    assertThat(plan.puDifesa())
        .isEqualTo(plan.voci().stream().mapToInt(Difesa.Voce::puAcquisti).sum());
    final int cap = (int) Math.floor(pus * StrategaConfig.get().tettoStrategiaPct() / 100);
    assertThat(plan.puStrategia()).isEqualTo(Math.min(pus - plan.puDifesa(), cap));
    assertThat(plan.puDifesa() + plan.puStrategia() + plan.puHard()).isEqualTo(pus);
    // without a cap (100%) everything left after the defense goes to the strategy
    Stratega.setTettoStrategia(data.getProperties(), "Axis", 100);
    final Difesa.Piano all = plan("Germans");
    assertThat(all.puStrategia()).isEqualTo(pus - all.puDifesa());
    assertThat(all.puHard()).isZero();
    assertThat(all.taglioTetto()).isZero();
  }

  @Test
  void theHoldOfAnOperationIsTheSameEstimateAsTheDefense() {
    final OperazioneCongiunta.Definizione def = install(Tipo.TENUTA, "Western Germany", 1);
    final StimaUnica.Stima hold =
        StimaUnica.tenuta(proData(player("Germans"), 1), calc(), def, territory("Western Germany"));
    final double defense =
        Difesa.tenutaCon(
            proData(player("Germans"), 1), calc(), territory("Western Germany"), hold.presidio());
    assertThat(defense).isCloseTo(hold.tenuta(), within(0.001));
  }

  @Test
  void theCountedUnitsAreTrackedTurnByTurnAndAFallGetsItsCause() {
    final OperazioneCongiunta.Definizione def = install(Tipo.OFFENSIVA, "France", 2);
    final Registro reg = Registro.leggi(data.getProperties(), def.id());
    reg.setStato(Stato.PREPARAZIONE);
    reg.setRoundObiettivo(3);
    final List<Unit> army =
        territory("Germany")
            .getMatches(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()));
    assertThat(army).hasSizeGreaterThanOrEqualTo(3);
    final List<Unit> counted = List.of(army.get(0), army.get(1), army.get(2));
    Tracciamento.avvio(reg, counted, data.getMap());
    final JsonObject launch = new JsonObject();
    launch.addProperty("successo_pct", 80);
    launch.addProperty("presa_pct", 90);
    launch.addProperty("tenuta_pct", 89);
    reg.json().add("stima_avvio", launch);
    reg.salva(data.getProperties());
    // one stays at the capital, one is lost, one moves to Western Germany (closer to France)
    data.performChange(ChangeFactory.removeUnits(territory("Germany"), List.of(counted.get(1))));
    data.performChange(
        ChangeFactory.moveUnits(
            territory("Germany"), territory("Western Germany"), List.of(counted.get(2))));
    data.getSequence().setRoundAndStep(2, "Purchase", player("Germans"));
    Tracciamento.aggiorna(data, player("Germans"));
    final JsonArray tracking =
        Registro.leggi(data.getProperties(), def.id()).json().getAsJsonArray("tracciamento");
    assertThat(tracking).hasSize(1);
    final JsonObject entry = tracking.get(0).getAsJsonObject();
    assertThat(entry.get("guardia_capitale").getAsInt()).isEqualTo(1);
    assertThat(entry.get("persa").getAsInt()).isEqualTo(1);
    assertThat(entry.get("avvicinata").getAsInt()).isEqualTo(1);
    // the engine's estimate now falls by 60 points: the cause comes from the counted units
    final Registro now = Registro.leggi(data.getProperties(), def.id());
    final StimaUnica.Stima stima =
        StimaUnica.tenuta(proData(player("Germans"), 2), calc(), def, territory("Western Germany"));
    final JsonObject nowJson = new JsonObject();
    nowJson.addProperty("successo_pct", 20);
    nowJson.addProperty("presa_pct", 25);
    nowJson.addProperty("tenuta_pct", 80);
    final Optional<String> text = Tracciamento.calo(data, now, stima, nowJson);
    assertThat(text).isPresent();
    final JsonObject fall = now.json().getAsJsonArray("cali").get(0).getAsJsonObject();
    assertThat(fall.get("unita_contate_avvio").getAsInt()).isEqualTo(3);
    assertThat(fall.getAsJsonObject("unita_non_piu_contate").get("persa").getAsInt()).isEqualTo(1);
    assertThat(fall.get("causa").getAsString()).startsWith("presa: unita' contate");
    // once per round
    assertThat(Tracciamento.calo(data, now, stima, nowJson)).isEmpty();
  }

  // ================================================================================ helpers

  private Difesa.Piano plan(final String nation) {
    return Difesa.calcola(proData(player(nation), 1), calc(), List.of()).orElseThrow();
  }

  private static Difesa.Voce voce(final Difesa.Piano plan, final String name) {
    return plan.voci().stream()
        .filter(v -> v.territorio().getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError(name + " non valutato: " + plan.voci()));
  }

  private void lists(final String alliance, final List<String> must, final List<String> giveUp) {
    final JsonObject answer = new JsonObject();
    answer.add("irrinunciabili", entries(must));
    answer.add("sacrificabili", entries(giveUp));
    Difesa.salvaListe(data, alliance, 1, answer);
  }

  private static JsonArray entries(final List<String> names) {
    final JsonArray a = new JsonArray();
    for (final String n : names) {
      final JsonObject x = new JsonObject();
      x.addProperty("territorio", n);
      x.addProperty("motivazione", "prova");
      a.add(x);
    }
    return a;
  }

  private OperazioneCongiunta.Definizione install(
      final Tipo tipo, final String target, final int round) {
    final ProData proData = proData(player("Germans"), round);
    final StrategaCandidati gen =
        new StrategaCandidati(
            proData, calc(), "Axis", List.copyOf(Stratega.nazioni(data.getProperties(), "Axis")));
    final boolean conquest = tipo.conquista();
    final Candidato c =
        new Candidato(
            "C1",
            tipo,
            target,
            conquest ? "Germans" : "Italians",
            conquest ? "Italians" : "Germans",
            round,
            "",
            "",
            List.of(),
            gen.valore(territory(target)),
            1,
            new JsonObject());
    final String id = OperazioneCongiunta.PREFISSO_STRATEGA + tipo.id() + "-R" + round + "-test";
    final JsonArray a = OperazioneCongiunta.dinamicheJson(data.getProperties(), "Axis");
    final JsonObject def = gen.definizione(c, id, round, 0, 0, "");
    a.add(def);
    OperazioneCongiunta.setDinamiche(data.getProperties(), "Axis", a);
    return OperazioneCongiunta.parse(StrategaCandidati.wrap(def)).get(0);
  }

  private void clear(final String territory) {
    final Territory t = territory(territory);
    final List<Unit> units = t.getMatches(Matches.unitIsNotInfrastructure());
    if (!units.isEmpty()) {
      data.performChange(ChangeFactory.removeUnits(t, units));
    }
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrNull(name);
  }

  private ProData proData(final GamePlayer player, final int round) {
    data.getSequence().setRoundAndStep(round, "Purchase", player);
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
