package games.strategy.triplea.ai.proplus;

import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P15: the data, all off = nothing acts, the pure rules, and the four cases of the step on
 * built positions (Global 1940, same names as Europe 1940): (1) Ukraine German, Russian forces
 * enough: the extra attack is made at the 60% threshold; (2) the same kind of attack with only the
 * capital's units, uncovering Moscow beyond +10 points: given up; (3) Ukraine threatened and
 * holdable: units sent until the enemy's win is under 40%; (4) Ukraine not holdable: nobody sent.
 */
class LeveP15Test extends AbstractClientSettingTestCase {
  private static final long SEED = 20261126L;
  private static final String BOTH = "ucraina_contrattacco,ucraina_presidio";

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    for (final String n : List.of("Americans", "British", "Russians", "Germans", "Italians")) {
      Stratega.registra(data, player(n));
    }
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && !t.getOwner().isNull()) {
        data.performChange(OriginalOwnerTracker.addOriginalOwnerChange(t, t.getOwner()));
      }
    }
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

  @Test
  void theDataHaveTheValuesTheSwitchesAreOffAndNoNameIsInTheCode() throws Exception {
    final LeveP15.Config c = LeveP15.Config.get();
    assertThat(c.nazione()).isEqualTo("Russians");
    assertThat(c.nemicoGuerra()).isEqualTo("Germans");
    assertThat(c.ucraina()).isEqualTo("Ukraine");
    assertThat(c.novgorod()).isEqualTo("Novgorod");
    assertThat(c.sogliaVittoria()).isEqualTo(60);
    assertThat(c.rapportoTuv()).isEqualTo(1.3);
    assertThat(c.margineMosca()).isEqualTo(10);
    assertThat(c.sogliaPresidio()).isEqualTo(40);
    for (final StrategaInterruttori s : LeveP15.LEVE) {
      assertThat(s.predefinito()).as(s.id()).isFalse();
      assertThat(StrategaInterruttori.of(s.id())).contains(s);
      assertThat(Stratega.ALLEATI_CONSIGLIATI).doesNotContain(s);
    }
    for (final String file : List.of("LeveP15", "UcrainaContrattacco", "UcrainaPresidio")) {
      final String src =
          Files.readString(
              Path.of("src/main/java/games/strategy/triplea/ai/proplus/" + file + ".java"),
              StandardCharsets.UTF_8);
      for (final String word :
          List.of(
              "\"Germans\"",
              "\"Russians\"",
              "\"Ukraine\"",
              "\"Novgorod\"",
              "\"Russia\"",
              "\"infantry\"")) {
        assertThat(src).as(file + " contiene " + word).doesNotContain(word);
      }
    }
  }

  @Test
  void allOffNothingActs() {
    StrategaInterruttori.accendi(
        data.getProperties(),
        "Allies",
        String.join(",", Stratega.ALLEATI_CONSIGLIATI.stream().map(s -> s.id()).toList()));
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio");
    for (final String n : List.of("British", "Germans", "Italians", "Russians", "Americans")) {
      assertThat(UcrainaContrattacco.attivo(data, player(n))).as(n).isFalse();
      assertThat(UcrainaPresidio.attivo(data, player(n))).as(n).isFalse();
      assertThat(LeveP15.alcuna(data, player(n))).as(n).isFalse();
    }
    LeveP15.fineTurno(data, player("Russians"));
    assertThat(LeveP15.righe(data, LeveP15.TIPO_UCRAINA)).isEmpty();
  }

  @Test
  void onlyRussia() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", BOTH);
    assertThat(UcrainaContrattacco.attivo(data, player("Russians"))).isTrue();
    assertThat(UcrainaPresidio.attivo(data, player("Russians"))).isTrue();
    for (final String n : List.of("British", "Americans", "Germans")) {
      assertThat(LeveP15.alcuna(data, player(n))).as(n).isFalse();
    }
  }

  @Test
  void thePureRules() {
    assertThat(LeveP15.esegue(60, 13, 10, 60, 1.3)).isTrue();
    assertThat(LeveP15.esegue(59.9, 100, 0, 60, 1.3)).isFalse();
    assertThat(LeveP15.esegue(95, 12.9, 10, 60, 1.3)).isFalse();
    assertThat(LeveP15.esegue(100, 0, 0, 60, 1.3)).isTrue();
    assertThat(LeveP15.moscaProtetta(40, 30, 10)).isTrue();
    assertThat(LeveP15.moscaProtetta(40.1, 30, 10)).isFalse();
    assertThat(LeveP15.minacciata(40, 40)).isTrue();
    assertThat(LeveP15.minacciata(39.9, 40)).isFalse();
    // B2 as a pure rule: the units are added until under the threshold, else none
    final List<Unit> units =
        data.getUnitTypeList().getUnitTypeOrThrow("infantry").createTemp(3, player("Russians"));
    final UcrainaPresidio.Scelta ok =
        UcrainaPresidio.scegli(units, List.of(), 40, d -> 90 - 30 * d.size());
    assertThat(ok.tiene()).isTrue();
    assertThat(ok.unita()).hasSize(2);
    final UcrainaPresidio.Scelta no =
        UcrainaPresidio.scegli(units, List.of(), 40, d -> 90 - 10 * d.size());
    assertThat(no.tiene()).isFalse();
    assertThat(no.unita()).isEmpty();
  }

  /** (1) Ukraine German, Russian forces enough next to it: the extra attack is made. */
  @Test
  void ukraineGermanIsAttackedAtTheSixtyPercentThreshold() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", BOTH);
    atWar();
    final GamePlayer ru = player("Russians");
    final GamePlayer de = player("Germans");
    clearRussianLand();
    final Territory ukraine = territory("Ukraine");
    data.performChange(ChangeFactory.changeOwner(ukraine, de));
    add(ukraine, "infantry", 3, de);
    add(ukraine, "artillery", 1, de);
    final Territory rostov = territory("Rostov");
    add(rostov, "infantry", 6, ru);
    add(rostov, "armour", 2, ru);
    add(territory("Russia"), "infantry", 8, ru);
    final ProData pd = proData(ru, 5);
    final MoveDelegate del = move("Russians", "CombatMove");
    final Set<Unit> moved = UcrainaContrattacco.combattimento(pd, calc(), del);
    del.end();
    final JsonObject row = row(LeveP15.TIPO_CONTRATTACCO, "P15_A_ESEGUITO", "Ukraine");
    assertThat(row).as(LeveP15.righe(data, LeveP15.TIPO_CONTRATTACCO).toString()).isNotNull();
    assertThat(moved).isNotEmpty();
    assertThat(ukraine.getMatches(Matches.unitIsOwnedBy(ru))).containsAll(moved);
    assertThat(row.get("vittoria_stimata").getAsDouble()).isGreaterThanOrEqualTo(60);
    assertThat(row.get("tuv_asse_ucciso_stimato").getAsDouble())
        .isGreaterThanOrEqualTo(1.3 * row.get("tuv_russo_perso_stimato").getAsDouble());
    assertThat(row.get("dalla_capitale").getAsInt()).isZero();
    // the switch off: nothing
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "ucraina_presidio");
    assertThat(UcrainaContrattacco.attivo(data, ru)).isFalse();
  }

  /** (2) Only the capital's units could attack, Moscow uncovered beyond +10 points: given up. */
  @Test
  void anAttackThatUncoversMoscowIsGivenUp() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", BOTH);
    atWar();
    final GamePlayer ru = player("Russians");
    final GamePlayer de = player("Germans");
    clearRussianLand();
    final Territory bryansk = territory("Bryansk");
    data.performChange(ChangeFactory.changeOwner(bryansk, de));
    add(bryansk, "infantry", 4, de);
    final Territory moscow = territory("Russia");
    add(moscow, "infantry", 8, ru);
    add(moscow, "armour", 4, ru);
    final Territory smolensk = territory("Smolensk");
    data.performChange(ChangeFactory.changeOwner(smolensk, de));
    add(smolensk, "armour", 7, de);
    add(smolensk, "infantry", 4, de);
    final ProData pd = proData(ru, 5);
    final MoveDelegate del = move("Russians", "CombatMove");
    final Set<Unit> moved = UcrainaContrattacco.combattimento(pd, calc(), del);
    del.end();
    final JsonObject row = row(LeveP15.TIPO_CONTRATTACCO, "P15_A_RINUNCIA", "Bryansk");
    assertThat(row).as(LeveP15.righe(data, LeveP15.TIPO_CONTRATTACCO).toString()).isNotNull();
    assertThat(row.get("motivo").getAsString()).isEqualTo("Mosca scoperta oltre il margine");
    assertThat(moved).isEmpty();
    assertThat(bryansk.getMatches(Matches.unitIsOwnedBy(ru))).isEmpty();
    assertThat(moscow.getMatches(Matches.unitIsOwnedBy(ru).and(Matches.unitIsLand())))
        .hasSizeGreaterThanOrEqualTo(10);
  }

  /** (3) Ukraine threatened and holdable: units sent until the enemy's win is under 40%. */
  @Test
  void aHoldableUkraineIsReinforcedUnderFortyPercent() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", BOTH);
    atWar();
    final GamePlayer ru = player("Russians");
    final GamePlayer de = player("Germans");
    clearRussianLand();
    final Territory ukraine = territory("Ukraine");
    add(ukraine, "infantry", 2, ru);
    final Territory west = territory("Western Ukraine");
    data.performChange(ChangeFactory.changeOwner(west, de));
    add(west, "infantry", 4, de);
    add(west, "armour", 2, de);
    final Territory rostov = territory("Rostov");
    add(rostov, "infantry", 24, ru);
    final ProData pd = proData(ru, 5);
    final MoveDelegate del = move("Russians", "NonCombatMove");
    final Set<Unit> moved = UcrainaPresidio.nonCombattimento(pd, calc(), del);
    del.end();
    final JsonObject row = row(LeveP15.TIPO_PRESIDIO, "P15_B_SPOSTATE", null);
    assertThat(row).as(LeveP15.righe(data, LeveP15.TIPO_PRESIDIO).toString()).isNotNull();
    assertThat(row.get("minaccia_prima_pct").getAsDouble()).isGreaterThanOrEqualTo(40);
    assertThat(row.get("minaccia_dopo_pct").getAsDouble()).isLessThan(40);
    assertThat(moved).isNotEmpty().hasSizeLessThan(24);
    assertThat(ukraine.getMatches(Matches.unitIsOwnedBy(ru))).containsAll(moved);
  }

  /** (4) Ukraine not holdable with the units available: nobody more is sent. */
  @Test
  void anUnholdableUkraineGetsNobody() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", BOTH);
    atWar();
    final GamePlayer ru = player("Russians");
    final GamePlayer de = player("Germans");
    clearRussianLand();
    final Territory ukraine = territory("Ukraine");
    add(ukraine, "infantry", 2, ru);
    final Territory west = territory("Western Ukraine");
    data.performChange(ChangeFactory.changeOwner(west, de));
    add(west, "armour", 20, de);
    add(west, "infantry", 10, de);
    final Territory rostov = territory("Rostov");
    add(rostov, "infantry", 4, ru);
    final ProData pd = proData(ru, 5);
    final MoveDelegate del = move("Russians", "NonCombatMove");
    final Set<Unit> moved = UcrainaPresidio.nonCombattimento(pd, calc(), del);
    del.end();
    assertThat(row(LeveP15.TIPO_PRESIDIO, "P15_B_RINUNCIA", null))
        .as(LeveP15.righe(data, LeveP15.TIPO_PRESIDIO).toString())
        .isNotNull();
    assertThat(moved).isEmpty();
    assertThat(rostov.getMatches(Matches.unitIsOwnedBy(ru))).hasSize(4);
  }

  private JsonObject row(final String type, final String code, final String target) {
    for (final JsonObject o : LeveP15.righe(data, type)) {
      final JsonObject p = o.getAsJsonObject("parametri");
      if (code.equals(o.get("scelta").getAsString())
          && (target == null || target.equals(p.get("bersaglio").getAsString()))) {
        return p;
      }
    }
    return null;
  }

  private void clearRussianLand() {
    final GamePlayer ru = player("Russians");
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> mine =
          t.getMatches(
              Matches.unitIsOwnedBy(ru)
                  .and(Matches.unitIsLand())
                  .and(Matches.unitIsNotInfrastructure()));
      if (!mine.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(t, new ArrayList<>(mine)));
      }
    }
  }

  private void add(final Territory t, final String type, final int n, final GamePlayer p) {
    data.performChange(
        ChangeFactory.addUnits(
            t, data.getUnitTypeList().getUnitTypeOrThrow(type).createTemp(n, p)));
  }

  // ================================================================================ helpers

  private void atWar() {
    for (final String a : List.of("Americans", "British", "Russians")) {
      for (final String e : List.of("Germans", "Italians")) {
        if (!player(a).isAtWar(player(e))) {
          data.performChange(
              ChangeFactory.relationshipChange(
                  player(a),
                  player(e),
                  data.getRelationshipTracker().getRelationshipType(player(a), player(e)),
                  data.getRelationshipTypeList().getDefaultWarRelationship()));
        }
      }
    }
  }

  /** The relationship of the war (the US allied with the UK, as in the map's trigger). */
  private void allied(final String a, final String b) {
    if (!player(a).isAllied(player(b))) {
      data.performChange(
          ChangeFactory.relationshipChange(
              player(a),
              player(b),
              data.getRelationshipTracker().getRelationshipType(player(a), player(b)),
              data.getRelationshipTypeList().getDefaultAlliedRelationship()));
    }
  }

  private void setPus(final GamePlayer p, final int pus) {
    final var pu = data.getResourceList().getResourceOrThrow(Constants.PUS);
    data.performChange(
        ChangeFactory.changeResourcesChange(p, pu, pus - p.getResources().getQuantity(pu)));
  }

  private MoveDelegate move(final String nation, final String step) {
    final IDelegateBridge bridge = newDelegateBridge(player(nation));
    advanceToStep(bridge, nation.toLowerCase(java.util.Locale.ROOT) + step);
    final MoveDelegate del = (MoveDelegate) data.getMoveDelegate();
    del.setDelegateBridgeAndPlayer(bridge);
    del.start();
    return del;
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
