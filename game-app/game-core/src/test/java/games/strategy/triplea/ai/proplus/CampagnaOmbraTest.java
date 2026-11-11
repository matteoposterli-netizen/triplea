package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.io.IoUtils;

/**
 * Step R2a: the shadow campaign planner of the "Pro+ Stratega LLM" (Global 1940, Germans). Shadow
 * on = shadow off for every answer of the tuning and every game property the game reads; its state
 * survives save and load; the three cases of rule 2.6 on built positions and the release of a
 * garrison when the threat is gone.
 */
class CampagnaOmbraTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261111L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(
        games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.FOLDER_PROPERTY,
        profile.toString());
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    Stratega.registra(data, player("Germans"));
    Stratega.registra(data, player("Italians"));
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(
        games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.FOLDER_PROPERTY);
  }

  @Test
  void theShadowOnIsTheShadowOffForTheGame() {
    final var props = data.getProperties();
    StrategaInterruttori.set(props, "Axis", "ombra");
    final ProData off = proData(player("Germans"), 2);
    final List<String> answersOff = answers(off);
    final Map<String, String> propsOff = snapshot();
    assertThat(CampagnaOmbra.calcola(off, calc(), player("Germans"))).isEmpty();
    assertThat(snapshot()).isEqualTo(propsOff);
    // shadow on: the same answers, only its own property is new
    StrategaInterruttori.set(props, "Axis", "");
    final Map<String, String> before = snapshot();
    final ProData on = proData(player("Germans"), 2);
    final Optional<JsonObject> out = CampagnaOmbra.calcola(on, calc(), player("Germans"));
    assertThat(out).isPresent();
    assertThat(out.get().getAsJsonArray("campagne")).isNotEmpty();
    final Map<String, String> after = snapshot();
    after.remove(CampagnaOmbra.KEY_STATO + "Axis");
    assertThat(after).isEqualTo(before);
    assertThat(answers(on)).isEqualTo(answersOff);
    // every campaign has the three margins with need, gap, feasibility and estimated round
    for (final var c : out.get().getAsJsonArray("campagne")) {
      final JsonObject m = c.getAsJsonObject().getAsJsonObject("margini");
      for (final String k : List.of("60", "70", "80")) {
        assertThat(m.getAsJsonObject(k).has("forza_necessaria_tuv")).isTrue();
        assertThat(m.getAsJsonObject(k).has("scarto_tuv")).isTrue();
        assertThat(m.getAsJsonObject(k).has("fattibile")).isTrue();
        assertThat(m.getAsJsonObject(k).has("round_stimato")).isTrue();
      }
    }
    assertThat(CampagnaOmbra.riepilogo(props, "Axis").has("round_con_blocco")).isTrue();
    // step R2a-bis: the recomputed campaign per margin, with the real calculator
    for (final var c : out.get().getAsJsonArray("campagne")) {
      final JsonObject m = c.getAsJsonObject().getAsJsonObject("margini_v2");
      for (final String k : List.of("60", "70", "80")) {
        assertThat(m.getAsJsonObject(k).has("fattibile")).isTrue();
        assertThat(m.getAsJsonObject(k).get("modello").getAsString()).isEqualTo("m2");
      }
    }
    assertThat(out.get().get("chiamate_calcolatore").getAsInt()).isPositive();
    assertThat(out.get().has("errore_calcolatore")).isFalse();
  }

  @Test
  void theShadowStateSurvivesSaveAndLoad() throws Exception {
    CampagnaOmbra.calcola(proData(player("Germans"), 2), calc(), player("Germans"));
    final String state = (String) data.getProperties().get(CampagnaOmbra.KEY_STATO + "Axis");
    assertThat(state).isNotBlank();
    assertThat(state.length()).isLessThan(20_000);
    final byte[] bytes = IoUtils.writeToMemory(os -> GameDataManager.saveGame(os, data));
    final GameData loaded = IoUtils.readFromMemory(bytes, GameDataManager::loadGame).orElseThrow();
    assertThat(loaded.getProperties().get(CampagnaOmbra.KEY_STATO + "Axis")).isEqualTo(state);
  }

  @Test
  void ruleTwoSixOnBuiltPositions() {
    final CampagnaOmbra.Calcolo c =
        new CampagnaOmbra.Calcolo(
            proData(player("Germans"), 2),
            calc(),
            player("Germans"),
            "Axis",
            CampagnaOmbra.Config.get());
    // a) no enemy land unit can reach it: 0 units
    final JsonObject a = c.classifica(territory("Denmark"), List.of(), false, 30, 80, 100);
    assertThat(a.get("caso").getAsString()).isEqualTo(CampagnaOmbra.CASO_A);
    assertThat(a.get("unita").getAsInt()).isZero();
    // b) a small force with tanks able to blitz toward a territory that counts: 1 infantry
    final JsonObject b =
        c.classifica(
            territory("Slovakia Hungary"), units("Russians", "armour", 1), false, 30, 80, 100);
    assertThat(b.get("caso").getAsString()).isEqualTo(CampagnaOmbra.CASO_B);
    assertThat(b.get("unita").getAsInt()).isEqualTo(1);
    assertThat(b.get("composizione").getAsString()).isEqualTo("1 infantry");
    assertThat(b.has("perdita_attesa_senza_presidio")).isTrue();
    assertThat(b.has("costo_presidio")).isTrue();
    // b) a small force of infantry only (no blitz): 0 units
    final JsonObject b0 =
        c.classifica(
            territory("Slovakia Hungary"), units("Russians", "infantry", 1), false, 30, 80, 100);
    assertThat(b0.get("caso").getAsString()).isEqualTo(CampagnaOmbra.CASO_B);
    assertThat(b0.get("unita").getAsInt()).isZero();
    // c) a real threat on a VC: the minimal garrison, infantry and artillery only
    final List<Unit> threat = new ArrayList<>(units("Russians", "armour", 4));
    threat.addAll(units("Russians", "infantry", 6));
    final JsonObject cc = c.classifica(territory("Poland"), threat, false, 30, 80, 100);
    assertThat(cc.get("caso").getAsString()).isEqualTo(CampagnaOmbra.CASO_C);
    assertThat(cc.get("unita").getAsInt()).isPositive();
    assertThat(cc.get("composizione").getAsString())
        .doesNotContain("armour")
        .doesNotContain("fighter")
        .doesNotContain("bomber");
    // the threat is gone: case a) and the garrison is released
    final JsonObject gone = c.classifica(territory("Poland"), List.of(), false, 30, 80, 100);
    assertThat(gone.get("caso").getAsString()).isEqualTo(CampagnaOmbra.CASO_A);
    assertThat(CampagnaOmbra.rilasciato(CampagnaOmbra.CASO_C, gone.get("caso").getAsString()))
        .isTrue();
    assertThat(CampagnaOmbra.rilasciato(CampagnaOmbra.CASO_C, CampagnaOmbra.CASO_C)).isFalse();
  }

  // ======================================================================== step R2a-bis

  @Test
  void theCalculatorPercentilesAreOrderedOnTheSameForces() {
    final CampagnaOmbra.Calcolo c = calcolo();
    assertThat(c.calcolatore).isPresent();
    final List<Unit> attackers = new ArrayList<>(units("Germans", "infantry", 6));
    attackers.addAll(units("Germans", "armour", 3));
    final List<Unit> defenders = new ArrayList<>(units("Russians", "infantry", 5));
    defenders.addAll(units("Russians", "artillery", 1));
    final CampagnaOmbraMisure.Distribuzione d =
        c.attacco(territory("Poland"), attackers, defenders);
    assertThat(d.simulazioni()).isEqualTo(CampagnaOmbra.Config.get().bis().simulazioni());
    assertThat(d.metodo())
        .isIn(CampagnaOmbraMisure.METODO_LISTA, CampagnaOmbraMisure.METODO_CONTATORI);
    assertThat(d.percentile(60)).isLessThanOrEqualTo(d.percentile(70));
    assertThat(d.percentile(70)).isLessThanOrEqualTo(d.percentile(80));
    assertThat(d.percentile(80)).isLessThanOrEqualTo(c.tuv(attackers));
    assertThat(d.vittoriaPct()).isBetween(0.0, 100.0);
    // the same forces again: from the cache, no second call
    final int calls = c.chiamateCalcolatore;
    assertThat(c.attacco(territory("Poland"), attackers, defenders)).isSameAs(d);
    assertThat(c.chiamateCalcolatore).isEqualTo(calls);
    // nearest rank on a known sample
    final CampagnaOmbraMisure.Distribuzione k =
        new CampagnaOmbraMisure.Distribuzione(
            50, new double[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, 10, "test");
    assertThat(k.percentile(60)).isEqualTo(5);
    assertThat(k.percentile(70)).isEqualTo(6);
    assertThat(k.percentile(80)).isEqualTo(7);
  }

  @Test
  void theThreeModelsOfTheDefendersOnABuiltPosition() {
    final CampagnaOmbra.Calcolo c = calcolo();
    final Territory poland = territory("Eastern Poland");
    // enemy units now (M1) and enemy units next to it (part of M0)
    final double present = c.tuv(c.presenti(poland));
    final double m0 = c.tuv(c.difensori(poland, 1));
    assertThat(m0).isGreaterThanOrEqualTo(present);
    // M2 with the starting share: half of M0's increase
    final double start = CampagnaOmbra.Config.get().bis().quotaM2Iniziale();
    assertThat(c.m2(present, m0, 1)).isEqualTo(present + start * (m0 - present));
    c.modello = "m1";
    assertThat(c.tuv(c.difensoriModello(poland, 1))).isEqualTo(present);
    c.modello = "m0";
    assertThat(c.tuv(c.difensoriModello(poland, 1))).isEqualTo(m0);
    c.modello = "m2";
    final double m2Units = c.tuv(c.difensoriModello(poland, 1));
    assertThat(m2Units).isBetween(present, m0);
    // the share observed in the game: real increase 10 over a predicted 40 at +1
    final JsonObject st = new JsonObject();
    final JsonObject q = new JsonObject();
    final com.google.gson.JsonArray a = new com.google.gson.JsonArray();
    a.add(10);
    a.add(40);
    q.add("1", a);
    st.add("quote_m2", q);
    // M1 has the lowest median absolute error: it is the model of 1.5
    final JsonObject errors = new JsonObject();
    for (final String m : List.of("m0", "m1", "m2")) {
      final com.google.gson.JsonArray e = new com.google.gson.JsonArray();
      for (int i = 0; i < 12; i++) {
        e.add("m1".equals(m) ? 3 : "m2".equals(m) ? 8 : 20);
      }
      errors.add(m, e);
    }
    st.add("errori_modelli", errors);
    c.leggiModelli(st);
    assertThat(c.quoteM2[1]).isEqualTo(0.25);
    assertThat(c.quoteM2[2]).isEqualTo(start);
    assertThat(c.modello).isEqualTo("m1");
    assertThat(c.m2(present, m0, 1)).isEqualTo(present + 0.25 * (m0 - present));
  }

  @Test
  void theClassesOfMeasureTwoOnABuiltPosition() {
    final CampagnaOmbra.Calcolo c = calcolo();
    final GamePlayer germans = player("Germans");
    // a German infantry on an island of the Axis: it cannot get closer to the front
    final Territory sardinia = territory("Sardinia");
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            sardinia, units("Germans", "infantry", 1)));
    final JsonObject st = new JsonObject();
    final com.google.gson.JsonArray rear = new com.google.gson.JsonArray();
    rear.add(record("Germany", "a"));
    rear.add(record("Sardinia", "b"));
    st.add("retrovia", rear);
    c.inizioTurno(st);
    // one infantry of Germany arrived there in the round before, moving closer to the front
    final Territory germany = territory("Germany");
    final Unit transit =
        germany.getUnitCollection().stream()
            .filter(u -> u.getOwner().equals(germans) && u.getType().getName().equals("infantry"))
            .findFirst()
            .orElseThrow();
    final JsonObject track = new JsonObject();
    final JsonObject mine = new JsonObject();
    final com.google.gson.JsonArray t = new com.google.gson.JsonArray();
    t.add(c.territoriOrdinati().indexOf(germany));
    t.add(1);
    t.add(1);
    mine.add(CampagnaOmbraMisure.id(transit), t);
    track.add("Germans", mine);
    st.add("traccia", track);
    // placed this turn
    final List<Unit> placed = units("Germans", "artillery", 1);
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(germany, placed));
    final com.google.gson.JsonArray rows = c.fineTurnoBis(st);
    final List<String> classes = new ArrayList<>();
    for (final var e : rows) {
      final JsonObject x = e.getAsJsonObject();
      classes.add(x.get("territorio").getAsString() + ":" + x.get("classe").getAsString());
    }
    assertThat(classes)
        .contains("Germany:" + CampagnaOmbraMisure.APPENA_PIAZZATA)
        .contains("Germany:" + CampagnaOmbraMisure.IN_TRANSITO)
        .contains("Germany:" + CampagnaOmbraMisure.FERMA_DAVVERO)
        .contains("Sardinia:" + CampagnaOmbraMisure.BLOCCATA);
    // the aggregate of the state: per nation, case and class
    final JsonObject m2 = st.getAsJsonObject("misura2").getAsJsonObject("Germans");
    assertThat(m2.get("turni").getAsInt()).isEqualTo(1);
    assertThat(m2.getAsJsonObject("a").has(CampagnaOmbraMisure.FERMA_DAVVERO)).isTrue();
    assertThat(m2.getAsJsonObject("b").has(CampagnaOmbraMisure.BLOCCATA)).isTrue();
    // the pure rule
    assertThat(CampagnaOmbraMisure.classe(true, true, false, 0, false, true))
        .isEqualTo(CampagnaOmbraMisure.ANTIAEREA);
    assertThat(CampagnaOmbraMisure.classe(false, false, false, 1, true, true))
        .isEqualTo(CampagnaOmbraMisure.IN_TRANSITO);
    assertThat(CampagnaOmbraMisure.classe(false, false, false, 3, false, true))
        .isEqualTo(CampagnaOmbraMisure.FERMA_DAVVERO);
    assertThat(CampagnaOmbraMisure.classe(false, false, false, 3, false, false))
        .isEqualTo(CampagnaOmbraMisure.BLOCCATA);
  }

  @Test
  void aTerritoryTakenInTheTurnIsAPointToCheck() {
    final CampagnaOmbra.Calcolo c = calcolo();
    final JsonObject st = new JsonObject();
    st.add("retrovia", new com.google.gson.JsonArray());
    c.inizioTurno(st);
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.changeOwner(
            territory("Eastern Poland"), player("Germans")));
    c.fineTurnoBis(st);
    final JsonObject p = st.getAsJsonArray("punte").get(0).getAsJsonObject();
    assertThat(p.get("territorio").getAsString()).isEqualTo("Eastern Poland");
    assertThat(p.get("massima_tuv").getAsDouble()).isPositive();
    assertThat(p.get("tenuta_stimata").getAsDouble()).isBetween(0.0, 100.0);
  }

  private static JsonObject record(final String territory, final String caso) {
    final JsonObject r = new JsonObject();
    r.addProperty("territorio", territory);
    r.addProperty("round", 2);
    r.addProperty("nazione", "Germans");
    r.addProperty("caso", caso);
    r.addProperty("unita", 0);
    return r;
  }

  private CampagnaOmbra.Calcolo calcolo() {
    return new CampagnaOmbra.Calcolo(
        proData(player("Germans"), 2),
        calc(),
        player("Germans"),
        "Axis",
        CampagnaOmbra.Config.get());
  }

  // ================================================================================ helpers

  /** The answers of the tuning the game reads for the nation (step R2a: they must not change). */
  private static List<String> answers(final ProData pd) {
    final ProPlusTuning t = (ProPlusTuning) pd.getTuning();
    final List<String> out = new ArrayList<>();
    out.add(String.valueOf(t.strategyCapPercent(pd)));
    out.add(String.valueOf(t.defenseLists(pd)));
    out.add(String.valueOf(t.combatHeldUnits(pd).size()));
    out.add(String.valueOf(t.purchaseOrders(pd).isPresent()));
    out.add(String.valueOf(t.stagingOrders(pd).isPresent()));
    return out;
  }

  private Map<String, String> snapshot() {
    final Map<String, String> out = new TreeMap<>();
    data.getProperties()
        .getConstantPropertiesByName()
        .forEach((k, v) -> out.put(k, String.valueOf(v)));
    return out;
  }

  private List<Unit> units(final String owner, final String type, final int n) {
    return data.getUnitTypeList().getUnitTypeOrThrow(type).createTemp(n, player(owner));
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
