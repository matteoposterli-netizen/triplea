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
