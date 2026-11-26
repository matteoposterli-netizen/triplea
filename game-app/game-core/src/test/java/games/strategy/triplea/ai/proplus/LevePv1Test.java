package games.strategy.triplea.ai.proplus;

import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProTerritory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step PV1: the data, all off = nothing acts, the pure rules, and the four cases of the step on
 * built positions (Global 1940, same names as Europe 1940): (1) the USA with 10 empty transports at
 * the port and 40 PUs order land units before anything else; (2) a threatened Russian capital:
 * every PU in defense placed there, no land unit leaves, the units nearby come back; (3) British
 * troops in America without transports go back to the port, never south; (4) a British unit loads
 * on an empty US transport (legal for the engine; the unload in the same turn is refused).
 */
class LevePv1Test extends AbstractClientSettingTestCase {
  private static final long SEED = 20261126L;
  private static final String ALL = "usa_carico,capitale_oltranza,ponte_alleato";

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
    final LevePv1.Config c = LevePv1.Config.get();
    assertThat(c.caricoNazione()).isEqualTo("Americans");
    assertThat(c.postiPerTrasporto()).isEqualTo(2);
    assertThat(c.capitaleNazione()).isEqualTo("Russians");
    assertThat(c.capitaleNemici()).containsExactly("Germans", "Italians");
    assertThat(c.sogliaMinaccia()).isEqualTo(30);
    assertThat(c.sogliaContrattacco()).isEqualTo(70);
    assertThat(c.ponteNazione()).isEqualTo("British");
    assertThat(c.ponteAlleato()).isEqualTo("Americans");
    assertThat(c.porti()).contains("Quebec", "New Brunswick Nova Scotia", "Eastern United States");
    assertThat(c.riserva()).isEqualTo("United Kingdom");
    for (final StrategaInterruttori s : LevePv1.LEVE) {
      assertThat(s.predefinito()).as(s.id()).isFalse();
      assertThat(StrategaInterruttori.of(s.id())).contains(s);
    }
    for (final String file : List.of("LevePv1", "UsaCarico", "CapitaleOltranza", "PonteAlleato")) {
      final String src =
          Files.readString(
              Path.of("src/main/java/games/strategy/triplea/ai/proplus/" + file + ".java"),
              StandardCharsets.UTF_8);
      for (final String word :
          List.of(
              "\"British\"",
              "\"Americans\"",
              "\"Germans\"",
              "\"Russians\"",
              "\"Italians\"",
              "\"United Kingdom\"",
              "\"Russia\"",
              "\"Quebec\"",
              "Sea Zone\"",
              "\"infantry\"",
              "\"transport\"")) {
        assertThat(src).as(file + " contiene " + word).doesNotContain(word);
      }
    }
  }

  @Test
  void allOffNothingActs() {
    // the 7 recommended Allied switches and the Axis' do not turn them on
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio");
    StrategaInterruttori.accendi(
        data.getProperties(),
        "Allies",
        "usa_proporzione,sbarco_concentrato,niente_neutrali,norvegia_presto,norvegia_esposta,"
            + "ponte_concentrato,taranto");
    for (final String n : List.of("British", "Germans", "Italians", "Russians", "Americans")) {
      assertThat(UsaCarico.attivo(data, player(n))).as(n).isFalse();
      assertThat(CapitaleOltranza.attivo(data, player(n))).as(n).isFalse();
      assertThat(PonteAlleato.attivo(data, player(n))).as(n).isFalse();
    }
    final ProData pd = proData(player("Americans"), 5);
    assertThat(PonteAlleato.trattenute(pd)).isEmpty();
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(pd.getTuning());
    assertThat(UsaCarico.ordini(pd, tuning.pv1a())).isEmpty();
    assertThat(CapitaleOltranza.ordini(pd, tuning.pv1b())).isEmpty();
  }

  @Test
  void eachLeverIsOnlyItsNations() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", ALL);
    assertThat(UsaCarico.attivo(data, player("Americans"))).isTrue();
    assertThat(UsaCarico.attivo(data, player("British"))).isFalse();
    assertThat(CapitaleOltranza.attivo(data, player("Russians"))).isTrue();
    assertThat(CapitaleOltranza.attivo(data, player("Americans"))).isFalse();
    assertThat(PonteAlleato.attivo(data, player("British"))).isTrue();
    assertThat(PonteAlleato.attivo(data, player("Americans"))).isTrue();
    assertThat(PonteAlleato.attivo(data, player("Russians"))).isFalse();
    assertThat(PonteAlleato.attivo(data, player("Germans"))).isFalse();
  }

  @Test
  void thePureRules() {
    assertThat(UsaCarico.daComprare(10, 2, 0)).isEqualTo(20);
    assertThat(UsaCarico.daComprare(10, 2, 5)).isEqualTo(15);
    assertThat(UsaCarico.daComprare(2, 2, 9)).isZero();
    assertThat(UsaCarico.tipi(5, List.of("infantry", "artillery", "x"), 2, "infantry"))
        .containsExactly("infantry", "artillery", "infantry", "artillery", "infantry");
    assertThat(UsaCarico.tipi(3, List.of(), 2, "infantry"))
        .containsExactly("infantry", "infantry", "infantry");
    assertThat(
            UsaCarico.entro(
                List.of("infantry", "artillery", "infantry", "artillery"),
                Map.of("infantry", 3, "artillery", 4),
                10,
                10))
        .containsExactly("infantry", "artillery", "infantry");
    assertThat(UsaCarico.entro(List.of("infantry", "infantry"), Map.of("infantry", 3), 40, 1))
        .hasSize(1);
    assertThat(CapitaleOltranza.minacciata(30.1, 30)).isTrue();
    assertThat(CapitaleOltranza.minacciata(30, 30)).isFalse();
    assertThat(CapitaleOltranza.puoAttaccare(true, 70, 70)).isTrue();
    assertThat(CapitaleOltranza.puoAttaccare(true, 69.9, 70)).isFalse();
    assertThat(CapitaleOltranza.puoAttaccare(false, 99, 70)).isFalse();
    final Map<String, double[]> d = new LinkedHashMap<>();
    d.put("armour", new double[] {3, 6});
    d.put("artillery", new double[] {2, 4});
    d.put("infantry", new double[] {2, 3});
    assertThat(CapitaleOltranza.migliorDifesa(d)).contains("infantry");
    assertThat(PonteAlleato.testaDiPonte(Map.of("Norway", 3, "Normandy Bordeaux", 8)))
        .contains("Normandy Bordeaux");
    assertThat(PonteAlleato.testaDiPonte(Map.of("Norway", 0))).isEmpty();
  }

  /** (1) 10 empty US transports at the port and 40 PUs: land units are ordered first. */
  @Test
  void theUsaOrderTheCargoOfTheEmptyTransportsBeforeAnythingElse() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", ALL);
    atWar();
    final GamePlayer us = player("Americans");
    final Territory port = territory("101 Sea Zone");
    data.performChange(
        ChangeFactory.addUnits(
            port, data.getUnitTypeList().getUnitTypeOrThrow("transport").createTemp(10, us)));
    setPus(us, 40);
    final ProData pd = proData(us, 5);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(pd.getTuning());
    UsaCarico.piano(pd, tuning.pv1a());
    final Optional<ProPurchaseOrders> orders = UsaCarico.ordini(pd, tuning.pv1a());
    assertThat(orders).isPresent();
    int units = 0;
    int cost = 0;
    for (final ProPurchaseOrders.Mandatory m : orders.get().mandatory()) {
      assertThat(m.unitTypes()).hasSize(1);
      assertThat(
              data.getUnitTypeList().getUnitTypeOrThrow(m.unitTypes().get(0)).getUnitAttachment())
          .matches(ua -> !ua.isSea() && !ua.isAir() && ua.getTransportCost() > 0);
      units += m.minimum();
      cost += m.minimum() * Impegno.costiAcquisto(data, us).get(m.unitTypes().get(0));
    }
    assertThat(units).isPositive();
    assertThat(cost).isLessThanOrEqualTo(40);
    assertThat(orders.get().minimumShare()).isEqualTo(100);
    // the cargo before every other order (here: a transport ordered by another lever)
    final ProPurchaseOrders other =
        new ProPurchaseOrders(
            "altro",
            List.of(),
            List.of(new ProPurchaseOrders.Mandatory("navi", List.of("transport"), 2)),
            List.of("101 Sea Zone"),
            List.of(),
            100);
    final ProPurchaseOrders both = UsaCarico.prima(orders, Optional.of(other)).orElseThrow();
    assertThat(both.mandatory().get(0).category()).startsWith("pv1 carico");
    assertThat(both.mandatory().get(both.mandatory().size() - 1).category()).isEqualTo("navi");
    // the PUs: every unit within 40 PUs and the places of the port factories
    assertThat(tuning.pv1a().riga.get("trasporti_vuoti_al_porto").getAsInt())
        .isGreaterThanOrEqualTo(10);
    // the switch off: no order
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "");
    final ProData off = proData(us, 5);
    assertThat(UsaCarico.attivo(data, us)).isFalse();
    assertThat(
            UsaCarico.ordini(off, ((ProPlusTuning) ProPlusTuning.unwrap(off.getTuning())).pv1a()))
        .isEmpty();
  }

  /** (2) The Russian capital threatened: all PUs in defense there, no land unit leaves. */
  @Test
  void aThreatenedCapitalKeepsItsUnitsAndBuysDefense() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", ALL);
    atWar();
    final GamePlayer ru = player("Russians");
    final GamePlayer de = player("Germans");
    final Territory capital = territory("Russia");
    final Territory bryansk = territory("Bryansk");
    data.performChange(ChangeFactory.changeOwner(bryansk, de));
    data.performChange(ChangeFactory.removeUnits(bryansk, new ArrayList<>(bryansk.getUnits())));
    data.performChange(
        ChangeFactory.addUnits(
            bryansk, data.getUnitTypeList().getUnitTypeOrThrow("armour").createTemp(25, de)));
    final Territory tambov = territory("Tambov");
    data.performChange(
        ChangeFactory.addUnits(
            tambov, data.getUnitTypeList().getUnitTypeOrThrow("infantry").createTemp(3, ru)));
    setPus(ru, 30);
    final ProData pd = proData(ru, 6);
    final ProPlusTuning tuning = (ProPlusTuning) ProPlusTuning.unwrap(pd.getTuning());
    final CapitaleOltranza.Stato st = tuning.pv1b();
    CapitaleOltranza.piano(pd, calc(), st);
    assertThat(st.minacciata).isTrue();
    final ProPurchaseOrders orders = CapitaleOltranza.ordini(pd, st).orElseThrow();
    assertThat(orders.mandatory()).hasSize(1);
    assertThat(orders.mandatory().get(0).unitTypes()).containsExactly("infantry");
    assertThat(orders.mandatory().get(0).factory()).isEqualTo(capital.getName());
    assertThat(orders.mandatory().get(0).minimum()).isBetween(1, 10);
    assertThat(orders.preferredPlacement()).containsExactly(capital.getName());

    // B1 in the combat move: a capital unit only in a strong attack next to the capital
    final Unit inCapital =
        capital.getMatches(Matches.unitIsOwnedBy(ru).and(Matches.unitIsLand())).get(0);
    final Map<Territory, ProTerritory> attacks = new LinkedHashMap<>();
    final ProTerritory weak = new ProTerritory(bryansk, pd);
    weak.addUnit(inCapital);
    weak.setBattleResult(new ProBattleResult(50, 0, true, List.of(), List.of(), 1));
    attacks.put(bryansk, weak);
    final Territory far = territory("Ukraine");
    final ProTerritory farAttack = new ProTerritory(far, pd);
    farAttack.addUnit(inCapital);
    farAttack.setBattleResult(new ProBattleResult(95, 0, true, List.of(), List.of(), 1));
    attacks.put(far, farAttack);
    CapitaleOltranza.rivedi(st, pd, attacks);
    assertThat(weak.getUnits()).isEmpty();
    assertThat(farAttack.getUnits()).isEmpty();
    final ProTerritory strong = new ProTerritory(bryansk, pd);
    strong.addUnit(inCapital);
    strong.setBattleResult(new ProBattleResult(80, 0, true, List.of(), List.of(), 1));
    CapitaleOltranza.rivedi(st, pd, Map.of(bryansk, strong));
    assertThat(strong.getUnits()).containsExactly(inCapital);

    // B1 and B2 in the non-combat move: Tambov's infantry comes back, all held in the capital
    final MoveDelegate del = move("Russians", "NonCombatMove");
    final int before =
        capital.getMatches(Matches.unitIsOwnedBy(ru).and(Matches.unitIsLand())).size();
    final Set<Unit> held = CapitaleOltranza.nonCombattimento(pd, calc(), del, st);
    del.end();
    assertThat(tambov.getMatches(Matches.unitIsOwnedBy(ru).and(Matches.unitIsLand()))).isEmpty();
    final List<Unit> after =
        capital.getMatches(Matches.unitIsOwnedBy(ru).and(Matches.unitIsLand()));
    assertThat(after).hasSizeGreaterThanOrEqualTo(before + 3);
    assertThat(held).containsAll(after.stream().filter(Matches.unitIsNotInfrastructure()).toList());
    assertThat(LevePv1.righe(data, LevePv1.TIPO_CAPITALE)).isNotEmpty();

    // B4: the capital lost, nothing of the lever
    data.performChange(ChangeFactory.changeOwner(capital, de));
    final ProData lost = proData(ru, 7);
    final CapitaleOltranza.Stato st2 =
        ((ProPlusTuning) ProPlusTuning.unwrap(lost.getTuning())).pv1b();
    CapitaleOltranza.piano(lost, calc(), st2);
    assertThat(st2.minacciata).isFalse();
    assertThat(CapitaleOltranza.ordini(lost, st2)).isEmpty();
  }

  /** (3) British troops in America without transports go back to the port, never south. */
  @Test
  void britishTroopsInAmericaWaitAtThePort() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", ALL);
    atWar();
    final GamePlayer uk = player("British");
    allied("British", "Americans");
    final Territory central = territory("Central United States");
    final Territory eastern = territory("Eastern United States");
    final List<Unit> added =
        new ArrayList<>(data.getUnitTypeList().getUnitTypeOrThrow("infantry").createTemp(3, uk));
    data.performChange(ChangeFactory.addUnits(central, added));
    final List<Unit> atPort =
        data.getUnitTypeList().getUnitTypeOrThrow("infantry").createTemp(2, uk);
    data.performChange(ChangeFactory.addUnits(eastern, atPort));
    added.addAll(atPort);
    final MoveDelegate del = move("British", "NonCombatMove");
    final Set<Unit> held = PonteAlleato.alPorto(data, uk, del);
    del.end();
    assertThat(central.getMatches(Matches.unitIsOwnedBy(uk))).isEmpty();
    // the port next to the US transports (101): the troops gather there (Quebec's come too)
    assertThat(eastern.getMatches(Matches.unitIsOwnedBy(uk))).containsAll(added);
    assertThat(territory("Southeast Mexico").getMatches(Matches.unitIsOwnedBy(uk))).isEmpty();
    assertThat(held).containsAll(eastern.getMatches(Matches.unitIsOwnedBy(uk)));
  }

  /** (4) A British unit loads on an empty US transport: legal; the unload in the same turn not. */
  @Test
  void britishTroopsLoadOnAnEmptyUsTransport() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", ALL);
    atWar();
    final GamePlayer uk = player("British");
    final GamePlayer us = player("Americans");
    allied("British", "Americans");
    final Territory eastern = territory("Eastern United States");
    final Territory zone = territory("101 Sea Zone");
    data.performChange(ChangeFactory.removeUnits(zone, new ArrayList<>(zone.getUnits())));
    final List<Unit> transports =
        data.getUnitTypeList().getUnitTypeOrThrow("transport").createTemp(1, us);
    data.performChange(ChangeFactory.addUnits(zone, transports));
    final List<Unit> troops =
        data.getUnitTypeList().getUnitTypeOrThrow("infantry").createTemp(2, uk);
    data.performChange(ChangeFactory.addUnits(eastern, troops));
    final MoveDelegate del = move("British", "NonCombatMove");
    final Set<Unit> loaded = PonteAlleato.carica(data, uk, del);
    assertThat(loaded).containsExactlyInAnyOrderElementsOf(troops);
    assertThat(transports.get(0).getTransporting()).containsExactlyInAnyOrderElementsOf(troops);
    // the engine refuses the unload in the same turn (allied transport)
    assertThat(del.performMove(new MoveDescription(troops, new Route(zone, eastern)))).isPresent();
    del.end();
    // the US turn: the transport with British cargo is out of Hard's moves
    final ProData pd = proData(us, 5);
    assertThat(PonteAlleato.trattenute(pd)).containsExactly(transports.get(0));
    // the UK's turn: its units on board are out of Hard's moves
    final ProData pdUk = proData(uk, 5);
    assertThat(PonteAlleato.trattenute(pdUk)).containsExactlyInAnyOrderElementsOf(troops);
    assertThat(PonteAlleato.aBordoAlleato(data, uk)).hasSize(2);
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
