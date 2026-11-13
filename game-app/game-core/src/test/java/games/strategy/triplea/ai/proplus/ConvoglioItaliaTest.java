package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step R6c "ITALIA: SPOLA IN CONVOGLIO" on built positions (Global 1940, Italy with the siege): the
 * shuttle at war is never "before the war" and never has the United Kingdom as its base; the convoy
 * leaves with the minimum escort; German planes in reach count as defenders; Italy buys warships
 * until the route is safe, then complete pairs; switch off = the Axis as before; the panel's box.
 */
class ConvoglioItaliaTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261114L;

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
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

  @Test
  void theDataFileHasTheValuesOfStepR6cAndTheSwitchIsOffByDefault() {
    assertThat(ConvoglioItalia.Config.get().accesoDiDefault()).isFalse();
    assertThat(ConvoglioItalia.Config.get().sogliaSbarco()).isEqualTo(70);
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio");
    assertThat(Assedio.italia(data, player("Italians"))).isTrue();
    assertThat(ConvoglioItalia.attivo(data, player("Italians"))).isFalse();
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio,italia_convoglio");
    assertThat(ConvoglioItalia.attivo(data, player("Italians"))).isTrue();
    // on top of the siege: without it nothing
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,italia_convoglio");
    assertThat(ConvoglioItalia.attivo(data, player("Italians"))).isFalse();
  }

  @Test
  void switchOffTheItalianShuttleIsTodays() {
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio");
    clean();
    final Programmi.Turno p = turno(4, "Purchase");
    final JsonObject turn = new JsonObject();
    Assedio.acquistiItalia(
        data, italy(), 30, p, 0, turn, new JsonObject(), new ArrayList<>());
    assertThat(rows("italia_convoglio")).isEmpty();
    assertThat(rows("italia_africa")).isNotEmpty();
  }

  @Test
  void atWarTheShuttleIsNeverBeforeTheWarNorBasedInTheUnitedKingdom() {
    on();
    clean();
    // Libya of the Axis: the base
    owner("Libya", "Italians");
    Programmi.Turno p = turno(4, "Non Combat Move");
    ConvoglioItalia.nonCombattimento(p, recording(new ArrayList<>()), 0);
    JsonObject row = last("italia_convoglio");
    JsonObject a = row.getAsJsonArray("alternative").get(0).getAsJsonObject();
    assertThat(a.get("stato").getAsString()).isEqualTo(ConvoglioItalia.BASE);
    assertThat(a.get("base").getAsString()).isNotEqualTo("United Kingdom");
    assertThat(ConfigR4.get().basiAfricaItalia()).contains(a.get("base").getAsString());
    assertThat(row.toString()).doesNotContain("prima della guerra").doesNotContain("Regno Unito");
    // no Axis base in Africa: a landing target (never the United Kingdom, never "before the war")
    for (final String n : ConfigR4.get().basiAfricaItalia()) {
      owner(n, "British");
    }
    p = turno(5, "Non Combat Move");
    ConvoglioItalia.nonCombattimento(p, recording(new ArrayList<>()), 0);
    row = last("italia_convoglio");
    a = row.getAsJsonArray("alternative").get(0).getAsJsonObject();
    assertThat(a.get("stato").getAsString()).isIn(ConvoglioItalia.SBARCO, ConvoglioItalia.NESSUNA);
    assertThat(row.toString()).doesNotContain("prima della guerra").doesNotContain("United Kingdom");
    final ConvoglioItalia.Meta meta = ConvoglioItalia.meta(p, List.of());
    assertThat(meta.stato()).isEqualTo(ConvoglioItalia.SBARCO);
    assertThat(ConfigR4.get().italiaLivello1()).contains(meta.terra().getName());
  }

  @Test
  void theConvoyLeavesWithTheMinimumEscort() {
    on();
    clean();
    owner("Libya", "Italians");
    final Territory z97 = territory("97 Sea Zone");
    final Unit tr = loadedTransport(z97, "Italians", "infantry", "artillery");
    final List<Unit> fleet = new ArrayList<>(add(z97, "Italians", "destroyer", 3));
    fleet.addAll(add(z97, "Italians", "cruiser", 2));
    fleet.addAll(add(z97, "Italians", "battleship", 1));
    // a British destroyer in 98 reaches 96 (the zone of Libya)
    add(territory("98 Sea Zone"), "British", "destroyer", 1);
    final Programmi.Turno p = turno(4, "Non Combat Move");
    final List<MoveDescription> moves = new ArrayList<>();
    ConvoglioItalia.nonCombattimento(p, recording(moves), 0);
    final List<MoveDescription> convoy =
        moves.stream().filter(m -> m.getUnits().contains(tr)).toList();
    assertThat(convoy).as(moves.toString()).hasSize(1);
    final Set<Unit> escort = new HashSet<>(convoy.get(0).getUnits());
    escort.retainAll(fleet);
    assertThat(escort).isNotEmpty().hasSizeLessThan(fleet.size());
    final Territory end = convoy.get(0).getRoute().getEnd();
    final List<Unit> group = new ArrayList<>(escort);
    group.add(tr);
    assertThat(TrasportiProtetti.valuta(p, end, group, List.of()).sicura()).isTrue();
    // the transport alone would not be safe there
    assertThat(TrasportiProtetti.valuta(p, end, List.of(tr), List.of()).sicura()).isFalse();
    assertThat(last("trasporti_sicurezza").getAsJsonObject("motivo").get("codice").getAsString())
        .isEqualTo(TrasportiProtetti.SCORTATA);
  }

  @Test
  void germanPlanesInReachCountAsDefenders() {
    on();
    clean();
    final Territory z96 = territory("96 Sea Zone");
    final List<Unit> transports = add(z96, "Italians", "transport", 1);
    add(territory("98 Sea Zone"), "British", "destroyer", 1);
    Programmi.Turno p = turno(4, "Non Combat Move");
    final TrasportiProtetti.Sicurezza alone = TrasportiProtetti.valuta(p, z96, transports, List.of());
    // German fighters on a German carrier in the zone (no German unit is moved for this)
    add(z96, "Germans", "carrier", 1);
    final List<Unit> planes = add(z96, "Germans", "fighter", 2);
    p = turno(4, "Non Combat Move");
    final TrasportiProtetti.Sicurezza with = TrasportiProtetti.valuta(p, z96, transports, List.of());
    assertThat(with.difensori()).containsAll(planes);
    assertThat(with.vittoria()).isLessThan(alone.vittoria());
  }

  @Test
  void italyBuysWarshipsUntilTheRouteIsSafeThenCompletePairs() {
    on();
    clean();
    owner("Libya", "Italians");
    // a British fleet reaches 96: warships first
    add(territory("98 Sea Zone"), "British", "cruiser", 2);
    Programmi.Turno p = turno(4, "Purchase");
    Map<String, Integer> mix = ConvoglioItalia.acquisti(p, 30, 0, new JsonObject());
    JsonObject a = last("italia_convoglio").getAsJsonArray("alternative").get(0).getAsJsonObject();
    assertThat(a.get("vittoria_nemica_flotta_pct").getAsDouble()).isGreaterThan(40);
    assertThat(mix).as(a.toString()).isNotEmpty().doesNotContainKey("transport");
    assertThat(mix.keySet()).isSubsetOf(ConfigR4.get().tipiFlottaItalia());
    // no threat: the route is safe, complete pairs (a transport with its load)
    clean();
    p = turno(5, "Purchase");
    mix = ConvoglioItalia.acquisti(p, 30, 0, new JsonObject());
    a = last("italia_convoglio").getAsJsonArray("alternative").get(0).getAsJsonObject();
    assertThat(a.get("rotta_sicura").getAsBoolean()).as(a.toString()).isTrue();
    assertThat(a.get("truppe_in_attesa").getAsInt()).isPositive();
    assertThat(mix).containsKey("transport");
    assertThat(mix.getOrDefault("infantry", 0)).isEqualTo(mix.get("transport"));
    assertThat(mix.getOrDefault("artillery", 0)).isEqualTo(mix.get("transport"));
  }

  @Test
  void thePanelBoxTurnsTheConvoyOn() throws Exception {
    final GameData fresh = TestMapGameData.GLOBAL1940.getGameData();
    final ProfiloStratega pr = ProfiloStratega.carica();
    pr.setItaliaConvoglio(true);
    pr.salva();
    Stratega.registra(fresh, fresh.getPlayerList().getPlayerId("Italians"));
    for (final StrategaInterruttori s :
        List.of(
            StrategaInterruttori.IMPEGNO,
            StrategaInterruttori.ASSEDIO,
            StrategaInterruttori.ITALIA_CONVOGLIO)) {
      assertThat(s.acceso(fresh.getProperties(), "Axis")).as(s.id()).isTrue();
    }
    assertThat(StrategaInterruttori.ITALIA_CONVOGLIO.acceso(fresh.getProperties(), "Allies"))
        .isFalse();
  }

  // ================================================================================ helpers

  private void on() {
    StrategaInterruttori.accendi(data.getProperties(), "Axis", "impegno,assedio,italia_convoglio");
  }

  /** Italy at war with the Allies; no ship or plane of anyone on the map. */
  private void clean() {
    for (final String a : List.of("British", "French", "Americans", "Russians")) {
      war("Italians", a);
      war("Germans", a);
    }
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> u = t.getMatches(Matches.unitIsSea().or(Matches.unitIsAir()));
      if (!u.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(t, u));
      }
    }
  }

  private void owner(final String name, final String owner) {
    final Territory t = territory(name);
    data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    data.performChange(ChangeFactory.changeOwner(t, player(owner)));
    add(t, owner, "infantry", 1);
  }

  private GamePlayer italy() {
    return player("Italians");
  }

  private Programmi.Turno turno(final int round, final String step) {
    final ProData pd = proData(italy(), round, step);
    return new Programmi.Turno(
        pd,
        calc(),
        italy(),
        "Axis",
        new CampagnaOmbra.Calcolo(pd, calc(), italy(), "Axis", CampagnaOmbra.Config.get()),
        new ArrayList<>());
  }

  private List<JsonObject> rows(final String tipo) {
    return RegistroDecisioni.righe(data).stream()
        .filter(r -> "decisione".equals(r.get("riga").getAsString()))
        .filter(r -> tipo.equals(r.get("tipo").getAsString()))
        .toList();
  }

  private JsonObject last(final String tipo) {
    final List<JsonObject> r = rows(tipo);
    assertThat(r).as("decisioni " + tipo).isNotEmpty();
    return r.get(r.size() - 1);
  }

  private void war(final String a, final String b) {
    if (data.getRelationshipTracker().isAtWar(player(a), player(b))) {
      return;
    }
    data.performChange(
        ChangeFactory.relationshipChange(
            player(a),
            player(b),
            data.getRelationshipTracker().getRelationshipType(player(a), player(b)),
            data.getRelationshipTypeList().getDefaultWarRelationship()));
  }

  private Unit loadedTransport(final Territory zone, final String owner, final String... cargo) {
    final Unit tr =
        data.getUnitTypeList().getUnitTypeOrThrow("transport").create(1, player(owner)).get(0);
    final List<Unit> all = new ArrayList<>(List.of(tr));
    final List<Unit> load = new ArrayList<>();
    for (final String type : cargo) {
      load.add(data.getUnitTypeList().getUnitTypeOrThrow(type).create(1, player(owner)).get(0));
    }
    all.addAll(load);
    data.performChange(ChangeFactory.addUnits(zone, all));
    for (final Unit u : load) {
      data.performChange(ChangeFactory.unitPropertyChange(u, tr, Unit.PropertyName.TRANSPORTED_BY));
    }
    return tr;
  }

  private List<Unit> add(final Territory t, final String owner, final String type, final int n) {
    final List<Unit> units =
        data.getUnitTypeList().getUnitTypeOrThrow(type).create(n, player(owner));
    data.performChange(ChangeFactory.addUnits(t, units));
    return units;
  }

  private static IMoveDelegate recording(final List<MoveDescription> moves) {
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any()))
        .thenAnswer(
            invocation -> {
              moves.add(invocation.getArgument(0));
              return Optional.empty();
            });
    return moveDel;
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrNull(name);
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
