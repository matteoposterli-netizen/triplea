package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.Ablazione;
import games.strategy.triplea.ai.proplus.ControlloTuning;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Stato;
import games.strategy.triplea.ai.proplus.OperazioniCongiunte;
import games.strategy.triplea.ai.proplus.ProPlusAi;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.ProPlusTuning;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Step PROVA DI VALORE: the hand-written joint operations ({@link OperazioneCongiunta}, {@link
 * OperazioniCongiunte}) on the Global 1940 test map (same territories as Europe 1940 for Egypt and
 * Normandy). Off by default; their state, the reserves in the purchase, the condition of the second
 * step; with the switch off Pro+ nudo answers as Hard, and the check against Hard ({@link
 * ControlloTuning}) counts zero differences out of the operation over a whole turn.
 */
class OperazioniCongiunteTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261110L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();

  @BeforeEach
  void setUp() {
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    Ablazione.set(data.getProperties(), "Axis", Ablazione.NUDO);
    Ablazione.set(data.getProperties(), "Allies", Ablazione.NUDO);
  }

  @Test
  void operationsAreDefinedAndOffByDefault() {
    assertThat(OperazioneCongiunta.ids()).containsExactly("egitto", "francia");
    final var egitto = OperazioneCongiunta.definizione("egitto").orElseThrow();
    assertThat(egitto.apre()).isEqualTo("Germans");
    assertThat(egitto.chiude()).isEqualTo("Italians");
    assertThat(egitto.bersaglio()).isEqualTo("Egypt");
    final var francia = OperazioneCongiunta.definizione("francia").orElseThrow();
    assertThat(francia.apre()).isEqualTo("Americans");
    assertThat(francia.chiude()).isEqualTo("British");
    assertThat(francia.bersaglio()).isEqualTo("Normandy Bordeaux");

    final GamePlayer germans = player("Germans");
    assertThat(OperazioneCongiunta.per(data, germans)).as("off by default").isEmpty();
    assertThatThrownBy(() -> OperazioneCongiunta.set(data.getProperties(), "Allies", "egitto"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OperazioneCongiunta.set(data.getProperties(), "Axis", "nessuna"))
        .isInstanceOf(IllegalArgumentException.class);

    OperazioneCongiunta.set(data.getProperties(), "Axis", "egitto");
    assertThat(OperazioneCongiunta.per(data, germans)).isPresent();
    assertThat(OperazioneCongiunta.per(data, player("Italians"))).isPresent();
    assertThat(OperazioneCongiunta.per(data, player("British"))).isEmpty();

    OperazioneCongiunta.set(data.getProperties(), "Axis", "");
    assertThat(OperazioneCongiunta.per(data, germans)).isEmpty();
  }

  @Test
  void startFixesTheTargetRoundAndTheStateFollowsTheRounds() {
    OperazioneCongiunta.set(data.getProperties(), "Axis", "egitto");
    final var def = OperazioneCongiunta.definizione("egitto").orElseThrow();
    final GamePlayer germans = player("Germans");

    final var start = update(germans, 1);
    final Registro reg = Registro.leggi(data.getProperties(), "egitto");
    final int target = reg.roundObiettivo();
    assertThat(target).isBetween(Math.max(2, def.roundMin()), def.roundMax());
    assertThat(reg.json().get("fattibilita").getAsString()).contains("aerei Germans");
    assertThat(start.righe().get(0)).contains("avvio al R1, round obiettivo R" + target);
    assertThat(reg.stato()).isIn(Stato.PREPARAZIONE, Stato.POSIZIONAMENTO);

    update(germans, target - 1);
    assertThat(Registro.leggi(data.getProperties(), "egitto").stato())
        .isEqualTo(Stato.POSIZIONAMENTO);
    update(germans, target);
    final Registro now = Registro.leggi(data.getProperties(), "egitto");
    assertThat(now.stato()).isEqualTo(Stato.ESECUZIONE);
    assertThat(now.roundObiettivo()).as("fixed at the start").isEqualTo(target);
    assertThat(now.eventi().size()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void reservesAreBoughtWhileTheOperationPrepares() {
    final GamePlayer americans = player("Americans");
    final ProData off = proData(americans, 1);
    assertThat(off.getTuning().purchaseOrders(off)).as("switch off: Hard's purchase").isEmpty();

    OperazioneCongiunta.set(data.getProperties(), "Allies", "francia");
    update(americans, 1);
    final ProData on = proData(americans, 1);
    final ProPurchaseOrders orders = on.getTuning().purchaseOrders(on).orElseThrow();
    assertThat(orders.label()).contains("operazione Francia");
    assertThat(orders.mix()).isEmpty();
    assertThat(orders.mandatory().stream().flatMap(m -> m.unitTypes().stream()))
        .contains("transport", "infantry");
    assertThat(orders.minimumShare()).isPositive();

    final List<String> bought = new ArrayList<>();
    SeededPurchaseChoice.with(
        SEED,
        () -> {
          final TestAi ai = new TestAi(americans.getName(), on, new SeededBattleCalculator(SEED));
          on.initializeSimulation(ai, data, americans);
          ai.prepareData(data);
          new ProPurchaseAi(ai).purchase(recordingPurchase(bought), data);
          return "";
        });
    assertThat(bought).as("the reserves are in the purchase").anyMatch(b -> b.contains("ransport"));

    final int target = Registro.leggi(data.getProperties(), "francia").roundObiettivo();
    final ProData late = proData(americans, target);
    assertThat(late.getTuning().purchaseOrders(late))
        .as("no reserves from the target round")
        .isEmpty();
  }

  @Test
  void secondStepGoesOnOnlyWithinTheTolerance() {
    final var def = OperazioneCongiunta.definizione("egitto").orElseThrow();
    // defenders as estimated, small loss, enough win%: attack
    assertThat(OperazioniCongiunte.condizioneTappa2(def, "Italians", false, 10, 10, 5, 70))
        .isEmpty();
    // more defenders than estimated + 25%
    assertThat(OperazioniCongiunte.condizioneTappa2(def, "Italians", false, 20, 10, 5, 70))
        .singleElement()
        .asString()
        .contains("difensori residui");
    // ... unless the win is sure
    assertThat(OperazioniCongiunte.condizioneTappa2(def, "Italians", false, 20, 10, 5, 85))
        .isEmpty();
    // the opener lost more than the cap
    assertThat(OperazioniCongiunte.condizioneTappa2(def, "Italians", false, 10, 10, 60, 70))
        .singleElement()
        .asString()
        .contains("TUV perso nella tappa 1");
    // below the closing threshold, no attackers
    assertThat(OperazioniCongiunte.condizioneTappa2(def, "Italians", true, 10, 10, 5, 30))
        .hasSize(2);
  }

  @Test
  void switchOffMeansHardAndTheCheckCountsNoDifference() {
    final GamePlayer germans = player("Germans");
    final ControlloTuning.Risultato off = checkedTurn(germans);
    assertThat(off.domande()).as("the Pro classes asked the tuning").isGreaterThan(1000);
    assertThat(off.differenze()).as("Pro+ nudo = Hard").isZero();
    assertThat(off.operazione()).as("no operation").isZero();

    // the operation on for the other alliance changes nothing for the Axis
    OperazioneCongiunta.set(data.getProperties(), "Allies", "francia");
    final ControlloTuning.Risultato other = checkedTurn(germans);
    assertThat(other.differenze()).isZero();
    assertThat(other.operazione()).isZero();

    // the operation on for the Axis: out of its own answers, still Hard's
    OperazioneCongiunta.set(data.getProperties(), "Axis", "egitto");
    update(germans, 1);
    final ControlloTuning.Risultato on = checkedTurn(germans);
    assertThat(on.differenze()).as("no difference out of the operation").isZero();
  }

  @Test
  void hardHasNoCombatHeldUnits() {
    final ProData hard = new ProData(false, ProTuning.DEFAULT);
    assertThat(ProTuning.DEFAULT.combatHeldUnits(hard)).isEmpty();
  }

  // ------------------------------------------------------------------------------------ helpers

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
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

  private OperazioniCongiunte.Esito update(final GamePlayer player, final int round) {
    final ProData proData = proData(player, round);
    return OperazioniCongiunte.aggiorna(
        proData, new ProOddsCalculator(new SeededBattleCalculator(SEED)));
  }

  /**
   * One turn of {@code player} (attack plan as the purchase simulates it, purchase, combat move,
   * non-combat move) with the Pro+ tuning checked against Hard's on every answer.
   */
  private ControlloTuning.Risultato checkedTurn(final GamePlayer player) {
    final ProPlusTuning plus = new ProPlusTuning();
    final ProTuning checked = ControlloTuning.wrap(plus);
    final ProData proData = new ProData(false, checked);
    plus.attach(proData);
    SeededPurchaseChoice.with(
        SEED,
        () -> {
          final TestAi ai = new TestAi(player.getName(), proData, new SeededBattleCalculator(SEED));
          data.getSequence().setRoundAndStep(1, "Combat Move", player);
          proData.initializeSimulation(ai, data, player);
          ai.prepareData(data);
          new ProCombatMoveAi(ai).doCombatMove(recordingMoves());
          proData.initializeSimulation(ai, data, player);
          ai.prepareData(data);
          new ProPurchaseAi(ai).purchase(recordingPurchase(new ArrayList<>()), data);
          data.getSequence().setRoundAndStep(1, "Non Combat Move", player);
          proData.initializeSimulation(ai, data, player);
          ai.prepareData(data);
          new ProNonCombatMoveAi(ai).doNonCombatMove(null, null, recordingMoves());
          return "";
        });
    return ControlloTuning.of(checked).risultato();
  }

  private static IMoveDelegate recordingMoves() {
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any(MoveDescription.class))).thenReturn(Optional.empty());
    return moveDel;
  }

  private static IPurchaseDelegate recordingPurchase(final List<String> bought) {
    final IPurchaseDelegate purchaseDel = mock(IPurchaseDelegate.class);
    when(purchaseDel.purchase(any()))
        .thenAnswer(
            invocation -> {
              final IntegerMap<ProductionRule> rules = invocation.getArgument(0);
              rules.keySet().forEach(r -> bought.add(r.getName() + " x" + rules.getInt(r)));
              return null;
            });
    return purchaseDel;
  }

  static String types(final Collection<Unit> units) {
    return units.stream().map(u -> u.getType().getName()).sorted().collect(Collectors.joining(","));
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

  static Set<String> none() {
    return Set.of();
  }
}
