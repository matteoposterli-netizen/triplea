package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.ProAttackWorth;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.ProStagingOrders;
import games.strategy.triplea.ai.pro.ProTuning;
import games.strategy.triplea.ai.pro.ProWarDecision;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.util.ProNationalObjectiveUtils;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.proplus.strategia.Direttive;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.ai.proplus.strategia.Strategia;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import javax.annotation.Nullable;

/**
 * Pro+ numbers. Changes only:
 *
 * <ul>
 *   <li>attack thresholds: from the alliance's {@link RiskLevel} (defense, purchase, scramble keep
 *       the "Hard (AI)" thresholds);
 *   <li>victory cities: valued with the Hard Beta formula, also when the game is won by "Triggered
 *       Victory" (WW2 Europe 1940 and Global 1940 count victory cities through triggers, so Hard
 *       Beta's own condition never applies there);
 *   <li>attack priority: a victory city adds {@link #ATTACK_PRIORITY_VICTORY_CITY_WEIGHT} to the
 *       score the combat move ranks its targets by;
 *   <li>last check of the planned attacks: some attacks Hard drops as not worth it are kept, by the
 *       risk level ({@link #keepsAttackNotWorthIt});
 *   <li>strategy (step 3a): with a strategy file for the map, the alliance's plan in force ({@link
 *       PianoAttivo}) sets the risk level per target (base level + the front's modifier) and its
 *       objectives get a priority bonus and replace the victory city / national objective rule.
 * </ul>
 */
public final class ProPlusTuning implements ProTuning {
  /**
   * Priority points per victory city of a target that can be held (a quarter if it can't), added
   * where the Pro AI adds TUV swing and production value, so the capital (x5) and
   * next-to-my-capital (x3) multipliers apply to it too. A holdable land target with production P
   * scores 3·P (6·P if empty, x2 with a factory), so 10 ≈ 3 PUs of production: it puts a victory
   * city ahead of a non-victory-city target of similar production and TUV swing, but not ahead of a
   * target worth clearly more (Western Germany, 5 PUs + factory: 30) nor of an enemy capital (x5:
   * 60 or more).
   */
  static final double ATTACK_PRIORITY_VICTORY_CITY_WEIGHT = 10;

  /**
   * Enemy win% above which the non-combat move keeps or brings back units to each own capital (step
   * 3b-bis), with or without a plan; also where a plan's minimum purchase share stops limiting the
   * defense of a factory or the capital. Hard has no such guard.
   */
  public static final double CAPITAL_GUARD_THRESHOLD = 25;

  /**
   * Desired win% of a critical objective of the plan (step 3b-ter: France in round 1, enemy
   * capitals), whatever the risk slider.
   */
  public static final double CRITICAL_OBJECTIVE_DESIRED = 80;

  /** {@link #keepsAttackNotWorthIt}: kept by the risk level's tolerance. */
  public static final String KEPT_BY_RISK = "risk";

  /** {@link #keepsAttackNotWorthIt}: kept by the victory city / national objective rule. */
  public static final String KEPT_BY_OBJECTIVE = "objective";

  /** Step PROVA DI VALORE: kept as the second step of a joint operation. */
  public static final String KEPT_BY_OPERATION = "joint_operation";

  /** {@link #keepsBridgehead}: kept as the first step of a two-step bridgehead. */
  public static final String KEPT_BY_BRIDGEHEAD = "bridgehead";

  /**
   * {@link #dropsLanding}: not the target of the combined operation (both land on the same one).
   */
  public static final String DROP_OTHER_TARGET = "bridgehead_other_target";

  /**
   * {@link #dropsLanding}: the bridgehead would not hold even after the partner's reinforcements.
   */
  public static final String DROP_NOT_HELD = "bridgehead_not_held";

  /**
   * The Pro AI data this tuning belongs to (step ABLAZIONE): tells the methods without a {@link
   * ProData} parameter which game and player the ablation switches are read for.
   */
  private @Nullable ProData owner;

  /**
   * Step PROVA DI VALORE: set when the last answer came from a joint operation ({@link
   * OperazioniCongiunte}), read and cleared by the check against Hard ({@link ControlloTuning}).
   */
  private static final ThreadLocal<Boolean> ANSWER_OF_OPERATION =
      ThreadLocal.withInitial(() -> false);

  /** Marks the answer being returned as the joint operation's. */
  static <T> T operationAnswer(final T answer) {
    ANSWER_OF_OPERATION.set(true);
    return answer;
  }

  /** Whether the last answer was the joint operation's (and clears the mark). */
  static boolean takeOperationMark() {
    final boolean mark = ANSWER_OF_OPERATION.get();
    ANSWER_OF_OPERATION.set(false);
    return mark;
  }

  /**
   * The Pro+ tuning of {@code tuning}: itself, or the one inside the check against Hard ({@link
   * ControlloTuning}); null for another tuning (Hard's).
   */
  public static @Nullable ProPlusTuning unwrap(final @Nullable ProTuning tuning) {
    if (tuning instanceof ProPlusTuning plus) {
      return plus;
    }
    return ControlloTuning.inner(tuning);
  }

  /** Round of the game of the AI data this tuning belongs to (0 before it has one). */
  int ownerRound() {
    return owner == null || owner.getData() == null ? 0 : owner.getData().getSequence().getRound();
  }

  /** Ties this tuning to the AI data that uses it ({@link ProPlusAi}). */
  public void attach(final ProData proData) {
    owner = proData;
  }

  /** Whether {@code c} is on for {@code proData}'s player ({@link Ablazione}). */
  private static boolean on(final ProData proData, final Ablazione.Componente c) {
    return Ablazione.attivo(proData, c);
  }

  /** Whether {@code c} is on for the owner's player (no owner: on). */
  private boolean on(final Ablazione.Componente c) {
    return Ablazione.attivo(owner, c);
  }

  /** Cache of {@link #plan}: the orders are the same for one game state, player and round. */
  private @Nullable GameState planData;

  private @Nullable GamePlayer planPlayer;
  private int planRound = -1;
  private Optional<PianoAttivo> plan = Optional.empty();

  /** The general staff's orders for {@code player} now; empty without a strategy for the map. */
  public Optional<PianoAttivo> plan(final GameState data, final GamePlayer player) {
    final int round = data.getSequence().getRound();
    if (data != planData || player != planPlayer || round != planRound) {
      plan = PianoAttivo.of(data, player);
      planData = data;
      planPlayer = player;
      planRound = round;
    }
    return plan;
  }

  /** Forgets the cached orders (the plan was just fixed in the game). */
  void forgetPlan() {
    planData = null;
  }

  /** {@link #plan} of {@code proData}'s player, also when its tuning is not this class. */
  public static Optional<PianoAttivo> planOf(final ProData proData) {
    return unwrap(proData.getTuning()) instanceof ProPlusTuning tuning
        ? tuning.plan(proData.getData(), proData.getPlayer())
        : PianoAttivo.of(proData.getData(), proData.getPlayer());
  }

  /**
   * Risk level of an attack on {@code target}: the alliance's level moved by the plan's modifier
   * for the target's front; the alliance's level without a plan.
   */
  public static RiskLevel riskLevel(final ProData proData, final Territory target) {
    return planOf(proData)
        .map(p -> p.livelloEffettivo(target))
        .orElseGet(() -> ProPlusSettings.riskLevelFor(proData.getData(), proData.getPlayer()));
  }

  /**
   * Desired win% of an attack on {@code target}: the effective level's (base level + the front's
   * modifier), but for an objective of the plan in force never below the base level's. The front's
   * modifier decides whether to try an objective (minimum win%), not how few units to commit to it:
   * with Ovest +1 France is tried from Audace's minimum but planned to Equilibrato's desired win%.
   */
  @Override
  public double targetAttackWinPercentage(
      final ProData proData, final Territory target, final double attackWinPercentage) {
    if (!on(proData, Ablazione.Componente.SOGLIE_RISCHIO)) {
      return attackWinPercentage;
    }
    return desiredWinPercentage(proData, target);
  }

  /** See {@link #targetAttackWinPercentage}. */
  public static double desiredWinPercentage(final ProData proData, final Territory target) {
    final boolean lowLuck = isLowLuck(proData.getData());
    final double desired = riskLevel(proData, target).desiredWinPercentage(lowLuck);
    return planOf(proData)
        .filter(p -> !ProUtils.isNeutralLand(target) && p.isObiettivo(target))
        .map(
            p -> {
              final double objective =
                  Math.max(desired, p.getLivelloBase().desiredWinPercentage(lowLuck));
              return p.isCritico(target)
                  ? Math.max(objective, CRITICAL_OBJECTIVE_DESIRED)
                  : objective;
            })
        .orElse(desired);
  }

  @Override
  public double targetAttackMinWinPercentage(
      final ProData proData, final Territory target, final double attackMinWinPercentage) {
    // step PROVA DI VALORE: the target of an approved second step, down to the closing threshold
    if (OperazioniCongiunte.forzaBersaglio(proData, target)) {
      return operationAnswer(
          Math.min(attackMinWinPercentage, OperazioniCongiunte.sogliaForzata(proData, target)));
    }
    if (!on(proData, Ablazione.Componente.SOGLIE_RISCHIO)) {
      return attackMinWinPercentage;
    }
    // strategic contract, guard rail: never below the absolute minimum, whatever the decider
    final double minimum =
        Math.max(
            Direttive.SOGLIA_MINIMA_ASSOLUTA,
            riskLevel(proData, target).minimumWinPercentage(isLowLuck(proData.getData())));
    if (!isLandingOnlyTarget(proData, target)) {
      return minimum;
    }
    // step OPERAZIONI ANFIBIE-BIS: the first nation of a combined operation lands at the desired
    // win% of the target's effective level (the partner reinforces the bridgehead)
    if (TestaDiPonte.primaSu(proData, target).isPresent()) {
      return Math.max(
          minimum, riskLevel(proData, target).desiredWinPercentage(isLowLuck(proData.getData())));
    }
    return Math.max(minimum, desiredWinPercentage(proData, target));
  }

  /**
   * Step OPERAZIONI ANFIBIE: a target of an operation of mine that only a landing can reach (no own
   * or allied land next to it) is attacked only at the desired win%: the minimum is the desired.
   */
  static boolean isLandingOnlyTarget(final ProData proData, final Territory target) {
    if (target.isWater()) {
      return false;
    }
    final Optional<PianoAttivo> plan = planOf(proData);
    if (plan.isEmpty() || !plan.get().isBersaglioOperazione(target)) {
      return false;
    }
    return proData
        .getData()
        .getMap()
        .getNeighbors(
            target,
            games.strategy.triplea.delegate.Matches.territoryIsLand()
                .and(
                    games.strategy.triplea.delegate.Matches.isTerritoryAllied(proData.getPlayer())))
        .isEmpty();
  }

  @Override
  public double attackWinPercentageCeiling(
      final ProData proData, final double attackWinPercentage) {
    if (!on(proData, Ablazione.Componente.SOGLIE_RISCHIO)) {
      return attackWinPercentage;
    }
    final boolean lowLuck = isLowLuck(proData.getData());
    return Math.max(
        attackWinPercentage,
        planOf(proData).map(p -> p.maxDesiredWinPercentage(lowLuck)).orElse(attackWinPercentage));
  }

  /** The plan's objective weight for {@code target} (never for neutrals); 0 without a plan. */
  @Override
  public double attackPriorityPlanBonus(final ProData proData, final Territory target) {
    if (ProUtils.isNeutralLand(target)) {
      return 0;
    }
    if (OperazioniCongiunte.forzaBersaglio(proData, target)) {
      return operationAnswer(OperazioniCongiunte.BONUS_FORZATO);
    }
    final double plan = planOf(proData).map(p -> p.peso(target)).orElse(0.0);
    // step PROVA DI VALORE: an access territory of the joint operation (only with its switch on)
    final double access = OperazioniCongiunte.bonusAccesso(proData, target);
    return access > plan ? operationAnswer(access) : plan;
  }

  /**
   * The plan's purchase orders for this nation and round (step 3b): the mix per category with the
   * unit types of each category from the strategy file, mandatory purchases, preferred placement
   * and the gathering territories (placement nearest to them). Empty without orders (plan N, a
   * nation or round the plan has none for, no strategy file).
   */
  @Override
  public Optional<ProPurchaseOrders> purchaseOrders(final ProData proData) {
    // step PROVA DI VALORE: the joint operation's reserves (only with its switch on)
    final Optional<ProPurchaseOrders> reserves = OperazioniCongiunte.ordiniAcquisto(proData);
    // step R2b: the commitment's orders (switch "impegno"), with the operations' reserves if any
    final Optional<ProPurchaseOrders> commitment = Impegno.ordini(proData);
    if (commitment.isPresent()) {
      return operationAnswer(
          reserves.map(r -> withReserves(commitment.get(), r)).or(() -> commitment));
    }
    if (reserves.isPresent()) {
      return operationAnswer(reserves);
    }
    if (!on(proData, Ablazione.Componente.LOGISTICA_ACQUISTI)) {
      return Optional.empty();
    }
    return planOf(proData)
        .flatMap(
            plan ->
                plan.getLogistica()
                    .filter(l -> !l.mix().isEmpty() || !l.obbligati().isEmpty())
                    .map(l -> ordersWithOperations(proData, plan, l))
                    .map(o -> plan.isDifensiva() ? withoutPlanShare(o) : o));
  }

  /** Step R2c (B3): the units the commitment's orders really bought are followed exactly. */
  @Override
  public void purchaseOrdersBought(
      final ProData proData,
      final ProPurchaseOrders orders,
      final ProPurchaseOrders.Result result) {
    Impegno.acquistato(proData, orders, result);
  }

  /** Step R2c (B2): a forced attack checked with the units Hard really sends. */
  @Override
  public void reviewAttacks(
      final ProData proData,
      final ProOddsCalculator calc,
      final Map<Territory, games.strategy.triplea.ai.pro.data.ProTerritory> attackMap) {
    Impegno.rivedi(proData, calc, attackMap);
  }

  /** Step R2b: the commitment's orders with the operations' mandatory reserves added. */
  private static ProPurchaseOrders withReserves(
      final ProPurchaseOrders commitment, final ProPurchaseOrders reserves) {
    final List<ProPurchaseOrders.Mandatory> mandatory = new ArrayList<>(commitment.mandatory());
    mandatory.addAll(reserves.mandatory());
    return new ProPurchaseOrders(
        commitment.label() + " + " + reserves.label(),
        commitment.mix(),
        List.copyOf(mandatory),
        commitment.preferredPlacement(),
        commitment.stagingTerritories(),
        Math.max(commitment.minimumShare(), reserves.minimumShare()));
  }

  /**
   * Step 3d-1, posture DIFENSIVA: no share reserved for the plan, so the real-threat defense comes
   * before the mix without a ceiling.
   */
  private static ProPurchaseOrders withoutPlanShare(final ProPurchaseOrders o) {
    return new ProPurchaseOrders(
        o.label() + " [postura DIFENSIVA: difesa prima del piano, nessuna quota]",
        o.mix(),
        o.mandatory(),
        o.preferredPlacement(),
        o.stagingTerritories(),
        0);
  }

  /**
   * The plan's orders; the amphibious operations of the nation (step OPERAZIONI ANFIBIE) add their
   * transports / land units / escort to the mandatory purchases and replace the step 3b-ter
   * "transport for the embarkation" rule.
   */
  private ProPurchaseOrders ordersWithOperations(
      final ProData proData, final PianoAttivo plan, final Strategia.Logistica l) {
    final GameState data = proData.getData();
    final String key = proData.getPlayer().getName() + "|" + data.getSequence().getRound();
    // computed on the real board at the start of the purchase (the purchase plans on a copy
    // where the Pro AI's own moves of the turn are already simulated)
    final List<OperazioniAnfibie.Acquisto> extra =
        key.equals(lastOperationPurchasesKey)
            ? lastOperationPurchases
            : OperazioniAnfibie.acquisti(proData, plan);
    lastOperationPurchases = extra;
    lastOperationPurchasesKey = key;
    if (plan.getOperazioni().isEmpty()) {
      return Staging.needsTransport(proData, plan, l)
          ? withTransport(purchaseOrders(plan, l), plan)
          : purchaseOrders(plan, l);
    }
    final ProPurchaseOrders orders = purchaseOrders(plan, l);
    if (extra.isEmpty()) {
      return orders;
    }
    final Map<String, List<String>> categories = plan.getStrategia().getCategorie();
    final Map<String, Integer> added = new java.util.LinkedHashMap<>();
    extra.forEach(a -> added.merge(a.categoria(), a.numero(), Integer::sum));
    // the plan's own mandatory purchases already serve the operation: the larger of the two
    if (orders.mandatory().stream().anyMatch(m -> ESCORTS.contains(m.category()))) {
      added.remove(OperazioniAnfibie.ESCORT);
    }
    final List<ProPurchaseOrders.Mandatory> mandatory = new ArrayList<>();
    for (final ProPurchaseOrders.Mandatory m : orders.mandatory()) {
      final int wanted = added.getOrDefault(m.category(), 0);
      added.remove(m.category());
      mandatory.add(
          new ProPurchaseOrders.Mandatory(
              m.category(), m.unitTypes(), Math.max(m.minimum(), wanted)));
    }
    added.forEach(
        (category, n) ->
            mandatory.add(
                new ProPurchaseOrders.Mandatory(
                    category, categories.getOrDefault(category, List.of()), n)));
    final StringBuilder label = new StringBuilder(orders.label());
    for (final OperazioniAnfibie.Acquisto a : extra) {
      label
          .append(" + operazione ")
          .append(a.operazione())
          .append(": ")
          .append(a.numero())
          .append(' ')
          .append(a.categoria())
          .append(" (")
          .append(a.motivo())
          .append(')');
    }
    return new ProPurchaseOrders(
        label.toString(),
        orders.mix(),
        mandatory,
        orders.preferredPlacement(),
        orders.stagingTerritories(),
        orders.minimumShare());
  }

  /** The last purchases asked by the operations, for the diary of the operations. */
  private List<OperazioniAnfibie.Acquisto> lastOperationPurchases = List.of();

  private String lastOperationPurchasesKey = "";

  /**
   * Computes the operations' purchases for {@code proData}'s player on the real board, before the
   * purchase plans on its copy of the game (see {@link #purchaseOrders}).
   */
  void prepareOperationPurchases(final ProData proData) {
    final Optional<PianoAttivo> plan = plan(proData.getData(), proData.getPlayer());
    lastOperationPurchasesKey =
        proData.getPlayer().getName() + "|" + proData.getData().getSequence().getRound();
    lastOperationPurchases =
        plan.map(p -> OperazioniAnfibie.acquisti(proData, p)).orElse(List.of());
  }

  /** What the operations asked to buy in {@code player}'s purchase of this round. */
  public List<OperazioniAnfibie.Acquisto> operationPurchases(
      final GamePlayer player, final int round) {
    return lastOperationPurchasesKey.equals(player.getName() + "|" + round)
        ? lastOperationPurchases
        : List.of();
  }

  // -------------------------------------------------- units held by the amphibious operations

  private @Nullable GameState heldData;
  private @Nullable GamePlayer heldPlayer;
  private int heldRound = -1;
  private java.util.Set<Unit> held = java.util.Set.of();

  /** The operations moved or keep {@code units} this turn (real game only). */
  public void hold(final GameState data, final GamePlayer player, final java.util.Set<Unit> units) {
    heldData = data;
    heldPlayer = player;
    heldRound = data.getSequence().getRound();
    held = java.util.Set.copyOf(units);
    heldByOperation = false;
  }

  /** Step PROVA DI VALORE: whether the units held this turn include a joint operation's. */
  private boolean heldByOperation;

  /**
   * Step PROVA DI VALORE: a joint operation moved or keeps {@code units} this turn (added to the
   * units held by the plan's operations, same turn).
   */
  public void holdForOperation(
      final GameState data, final GamePlayer player, final java.util.Set<Unit> units) {
    final java.util.Set<Unit> all = new java.util.LinkedHashSet<>(units);
    if (heldData == data && heldPlayer == player && heldRound == data.getSequence().getRound()) {
      all.addAll(held);
    }
    hold(data, player, all);
    heldByOperation = !units.isEmpty();
  }

  @Override
  public java.util.Set<Unit> heldUnits(final ProData proData) {
    final java.util.Set<Unit> units =
        proData.getData() == heldData
                && proData.getPlayer() == heldPlayer
                && proData.getData().getSequence().getRound() == heldRound
            ? held
            : java.util.Set.of();
    return heldByOperation && !units.isEmpty() ? operationAnswer(units) : units;
  }

  private @Nullable GameState combatHeldData;
  private @Nullable GamePlayer combatHeldPlayer;
  private int combatHeldRound = -1;
  private java.util.Set<Unit> combatHeld = java.util.Set.of();

  /** Step PROVA DI VALORE: units a joint operation sent into battle, or keeps, this combat move. */
  public void holdCombat(
      final GameState data, final GamePlayer player, final java.util.Set<Unit> units) {
    combatHeldData = data;
    combatHeldPlayer = player;
    combatHeldRound = data.getSequence().getRound();
    combatHeld = java.util.Set.copyOf(units);
    combatHeldIds = java.util.Set.of();
  }

  /**
   * Step COERENZA STIME: units the strategist's operations hold out of Hard's combat this turn, set
   * at the purchase (before the Pro AI plans its attacks on a copy of the game): the copy's units
   * with the same ids are held in that plan too.
   */
  private java.util.Set<java.util.UUID> combatHeldIds = java.util.Set.of();

  public void holdCombatForPurchase(
      final GameState data, final GamePlayer player, final java.util.Set<Unit> units) {
    holdCombat(data, player, units);
    combatHeldIds =
        units.stream().map(Unit::getId).collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  // ------------------------------------- step C2-bis: Hard's defense + budget (Stratega LLM only)

  /**
   * Step C2-bis: the operations' reserves get at most {@code tetto_strategia_pct}% of the turn's
   * PUs, after Hard has bought its own defense (only the strategist's nations).
   */
  @Override
  public OptionalDouble strategyCapPercent(final ProData proData) {
    final GamePlayer player = proData.getPlayer();
    if (player == null || !Stratega.gioca(proData.getData(), player)) {
      return java.util.OptionalDouble.empty();
    }
    // step R2b: with the commitment's orders the share of the orders reserves the PUs (only the
    // capital and the factories at risk are defended without a limit)
    if (Impegno.ordini(proData).isPresent()) {
      return operationAnswer(java.util.OptionalDouble.empty());
    }
    return ProPlusSettings.allianceOf(proData.getData(), player)
        .map(
            a ->
                operationAnswer(
                    java.util.OptionalDouble.of(
                        Difesa.tetto(proData.getData().getProperties(), a))))
        .orElse(java.util.OptionalDouble.empty());
  }

  /**
   * Step C2-bis: the LLM's lists of the round (contract 1.5) for Hard's defense of the strategist's
   * nations: expendable land is left out, irreplaceable land (and the nation's capital) always in.
   */
  @Override
  public Optional<games.strategy.triplea.ai.pro.ProDefenseLists> defenseLists(
      final ProData proData) {
    final GamePlayer player = proData.getPlayer();
    if (player == null || !Stratega.gioca(proData.getData(), player)) {
      return Optional.empty();
    }
    final Optional<String> alliance = ProPlusSettings.allianceOf(proData.getData(), player);
    if (alliance.isEmpty()
        || !StrategaInterruttori.LISTE.acceso(proData.getData().getProperties(), alliance.get())) {
      // step R0: lists switched off = Hard's defense without them (the LLM still answers them)
      return Optional.empty();
    }
    final Difesa.Liste l =
        Difesa.liste(
            proData.getData().getProperties(),
            alliance.get(),
            proData.getData().getSequence().getRound());
    final java.util.Set<String> must = new java.util.LinkedHashSet<>(l.irrinunciabili().keySet());
    if (proData.getMyCapital() != null) {
      must.add(proData.getMyCapital().getName());
    }
    return operationAnswer(
        Optional.of(
            new games.strategy.triplea.ai.pro.ProDefenseLists(l.sacrificabili().keySet(), must)));
  }

  @Override
  public java.util.Set<Unit> combatHeldUnits(final ProData proData) {
    if (proData.getData() == combatHeldData
        && proData.getPlayer() == combatHeldPlayer
        && proData.getData().getSequence().getRound() == combatHeldRound
        && !combatHeld.isEmpty()) {
      return operationAnswer(combatHeld);
    }
    if (!combatHeldIds.isEmpty()
        && combatHeldPlayer != null
        && proData.getData() != combatHeldData
        && proData.getPlayer() != null
        && proData.getPlayer().getName().equals(combatHeldPlayer.getName())
        && proData.getData().getSequence().getRound() == combatHeldRound) {
      final java.util.Set<Unit> copy = new java.util.LinkedHashSet<>();
      for (final Territory t : proData.getData().getMap().getTerritories()) {
        t.getUnits().stream().filter(u -> combatHeldIds.contains(u.getId())).forEach(copy::add);
      }
      if (!copy.isEmpty()) {
        return operationAnswer(copy);
      }
    }
    return java.util.Set.of();
  }

  /**
   * Step 3b-ter: the gathering is overseas and the transports near the embarkation point can't
   * carry the units waiting there: one transport more among the mandatory purchases.
   */
  private static ProPurchaseOrders withTransport(
      final ProPurchaseOrders orders, final PianoAttivo plan) {
    final List<String> types =
        plan.getStrategia().getCategorie().getOrDefault(TRANSPORTS, List.of("transport"));
    final List<ProPurchaseOrders.Mandatory> mandatory = new ArrayList<>();
    boolean found = false;
    for (final ProPurchaseOrders.Mandatory m : orders.mandatory()) {
      if (m.category().equals(TRANSPORTS)) {
        mandatory.add(
            new ProPurchaseOrders.Mandatory(m.category(), m.unitTypes(), m.minimum() + 1));
        found = true;
      } else {
        mandatory.add(m);
      }
    }
    if (!found) {
      mandatory.add(new ProPurchaseOrders.Mandatory(TRANSPORTS, types, 1));
    }
    return new ProPurchaseOrders(
        orders.label() + " + trasporto per l'imbarco",
        orders.mix(),
        mandatory,
        orders.preferredPlacement(),
        orders.stagingTerritories(),
        orders.minimumShare());
  }

  /** Categories of the strategy file that escort a fleet. */
  static final java.util.Set<String> ESCORTS =
      java.util.Set.of("scorta", "cacciatorpediniere", "navi_da_guerra");

  /** Category of the transports in the strategy file. */
  static final String TRANSPORTS = "trasporti";

  static ProPurchaseOrders purchaseOrders(final PianoAttivo plan, final Strategia.Logistica l) {
    final Map<String, List<String>> categories = plan.getStrategia().getCategorie();
    final List<ProPurchaseOrders.Category> mix = new ArrayList<>();
    l.mix()
        .forEach(
            (name, percent) ->
                mix.add(
                    new ProPurchaseOrders.Category(
                        name, percent, categories.getOrDefault(name, List.of()))));
    final List<ProPurchaseOrders.Mandatory> mandatory =
        l.obbligati().stream()
            .map(
                o ->
                    new ProPurchaseOrders.Mandatory(
                        o.categoria(),
                        categories.getOrDefault(o.categoria(), List.of()),
                        o.minimo()))
            .toList();
    return new ProPurchaseOrders(
        ordersLabel(plan, l),
        mix,
        mandatory,
        l.piazzamento(),
        plan.territoriAmmassamento(l),
        l.quotaMinimaPiano());
  }

  /**
   * The plan's staging orders for this nation and round (step 3b): the gathering territories (or
   * the front's land territories) and the pull strength. Step 3b-ter: a group in "linea_del_fronte"
   * mode gathers on the front line computed this turn ({@link Staging#frontLine}), extra groups are
   * weighed against the main one, and gathering overseas adds the embarkation points. Empty without
   * orders or with strength 0.
   */
  @Override
  public Optional<ProStagingOrders> stagingOrders(final ProData proData) {
    // step STRATEGA LLM: the strategist's priority reinforcements of the round (only its nations)
    final Optional<ProStagingOrders> llm = rinforziStratega(proData);
    if (llm.isPresent()) {
      return operationAnswer(llm);
    }
    if (!on(proData, Ablazione.Componente.AMMASSAMENTO)) {
      return Optional.empty();
    }
    return planOf(proData)
        .flatMap(
            plan ->
                plan.getLogistica()
                    .filter(l -> l.forza() > 0)
                    .flatMap(l -> Staging.orders(proData, plan, l, ordersLabel(plan, l))));
  }

  /**
   * Step STRATEGA LLM: the territories and sea zones where the strategist wants reserves this
   * round, with their weight (1-3), as a pull of the non-combat move (the engine chooses the
   * units). Empty for every player that is not the strategist's.
   */
  private static Optional<ProStagingOrders> rinforziStratega(final ProData proData) {
    if (proData == null || proData.getData() == null || proData.getPlayer() == null) {
      return Optional.empty();
    }
    final var data = proData.getData();
    if (!Stratega.gioca(data, proData.getPlayer())) {
      return Optional.empty();
    }
    final Map<String, Integer> r = Stratega.rinforzi(data, proData.getPlayer());
    if (r.isEmpty()) {
      return Optional.empty();
    }
    final Map<String, Double> weights = new java.util.LinkedHashMap<>();
    r.forEach((k, v) -> weights.put(k, v / 3.0));
    return Optional.of(
        new ProStagingOrders(
            "stratega LLM: rinforzi prioritari",
            "",
            List.copyOf(r.keySet()),
            0.5,
            false,
            Map.copyOf(weights)));
  }

  /** The plan's minimum garrisons (step 3b-ter): own territory → land units. */
  @Override
  public Map<String, Integer> minimumGarrisons(final ProData proData) {
    return planOf(proData)
        .flatMap(PianoAttivo::getLogistica)
        .map(Strategia.Logistica::presidioMinimo)
        .orElse(Map.of());
  }

  /**
   * War orders of the plan (step 3b-ter): a declaration of this nation on {@code enemy} is
   * forbidden before its round; from that round it is made once the staging is done ({@link
   * Staging#readiness}). Hard's own rule for enemies the plan doesn't name.
   */
  @Override
  public ProWarDecision warDecision(final ProData proData, final GamePlayer enemy) {
    // step PROVA DI VALORE: the war the joint operation needs (only with its switch on)
    final Optional<ProWarDecision> war = OperazioniCongiunte.guerra(proData, enemy);
    if (war.isPresent()) {
      return operationAnswer(war.get());
    }
    final Optional<PianoAttivo> plan = planOf(proData);
    if (plan.isEmpty()) {
      return ProWarDecision.HARD;
    }
    final Optional<Strategia.Dichiarazione> order =
        plan.get().getPiano().dichiarazione(proData.getPlayer().getName(), enemy.getName());
    if (order.isEmpty()) {
      return ProWarDecision.HARD;
    }
    final int round = proData.getData().getSequence().getRound();
    final int from = order.get().dalRound();
    if (round < from) {
      return new ProWarDecision(
          ProWarDecision.Kind.FORBIDDEN,
          "vietata prima del round " + from + " (piano " + plan.get().getPiano().label() + ")");
    }
    final Staging.Readiness ready = Staging.readiness(proData, plan.get(), enemy);
    return ready.ready()
        ? new ProWarDecision(
            ProWarDecision.Kind.DECLARE,
            "dichiarata: dal round "
                + from
                + " (piano "
                + plan.get().getPiano().label()
                + "), "
                + ready.text())
        : new ProWarDecision(
            ProWarDecision.Kind.FORBIDDEN,
            "rinviata: dal round " + from + " ma l'ammassamento non e' fatto, " + ready.text());
  }

  /** Eg: "A Barbarossa, Germans R1-3". */
  private static String ordersLabel(final PianoAttivo plan, final Strategia.Logistica l) {
    return plan.getPiano().label() + ", " + plan.getNazione() + " " + l.label();
  }

  /** Cache of {@link #realEnemies}: the same for one game state, player, round and step. */
  private @Nullable GameState threatData;

  private @Nullable GamePlayer threatPlayer;
  private int threatRound = -1;
  private @Nullable String threatStep;
  private Map<GamePlayer, String> threats = Map.of();

  /**
   * Step 3c: only the enemies that can really attack before the next turn ({@link MinacciaReale}),
   * with or without a plan.
   */
  @Override
  public Optional<Map<GamePlayer, String>> realEnemies(
      final ProData proData, final GamePlayer player) {
    if (!on(proData, Ablazione.Componente.MINACCIA_REALE)) {
      return Optional.empty();
    }
    final GameState data = proData.getData();
    final int round = data.getSequence().getRound();
    final String step = data.getSequence().getStep().getName();
    if (data != threatData
        || player != threatPlayer
        || round != threatRound
        || !step.equals(threatStep)) {
      threats = MinacciaReale.nemici(data, player, plan(data, player));
      threatData = data;
      threatPlayer = player;
      threatRound = round;
      threatStep = step;
    }
    return Optional.of(threats);
  }

  /**
   * Step 3c: a plan's minimum garrison only against a real landing: some enemy unit can land there
   * before the next turn from transports in range.
   */
  @Override
  public boolean garrisonNeeded(
      final ProData proData, final Territory territory, final List<Unit> landingEnemies) {
    if (!on(proData, Ablazione.Componente.MINACCIA_REALE)) {
      return ProTuning.super.garrisonNeeded(proData, territory, landingEnemies);
    }
    return !landingEnemies.isEmpty();
  }

  @Override
  public OptionalDouble capitalGuardThreshold(final ProData proData) {
    if (!on(proData, Ablazione.Componente.GUARNIGIONE_CAPITALE)) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(CAPITAL_GUARD_THRESHOLD);
  }

  @Override
  public double attackWinPercentage(
      final GameState data, final GamePlayer player, final double winPercentage) {
    if (!Ablazione.attivo(data, player, Ablazione.Componente.SOGLIE_RISCHIO)) {
      return winPercentage;
    }
    return ProPlusSettings.riskLevelFor(data, player).desiredWinPercentage(isLowLuck(data));
  }

  @Override
  public double attackMinWinPercentage(
      final GameState data, final GamePlayer player, final double minWinPercentage) {
    if (!Ablazione.attivo(data, player, Ablazione.Componente.SOGLIE_RISCHIO)) {
      return minWinPercentage;
    }
    return Math.max(
        Direttive.SOGLIA_MINIMA_ASSOLUTA,
        ProPlusSettings.riskLevelFor(data, player).minimumWinPercentage(isLowLuck(data)));
  }

  @Override
  public boolean valuesVictoryCities(final boolean beta) {
    if (!on(Ablazione.Componente.VICTORY_CITY)) {
      return ProTuning.super.valuesVictoryCities(beta);
    }
    return true;
  }

  /**
   * Step 3c-bis: as Hard Beta, no landing whose transports enemy air can scramble on and sink
   * (autoplay A/2, round 8: the Americans lost the landing on Northern Italy to Italian fighters
   * scrambled from Southern Italy into 95 Sea Zone).
   */
  @Override
  public boolean checksLandingScramble(final boolean beta) {
    if (!on(Ablazione.Componente.SCRAMBLE_SBARCHI)) {
      return ProTuning.super.checksLandingScramble(beta);
    }
    return true;
  }

  /**
   * Step 3c-bis: no strafe with units landing from transports below the minimum win% of the
   * target's effective risk level: landed units cannot retreat (autoplay A/2, round 8: 12 American
   * units against 15 defenders in Normandy Bordeaux at 0%, 6 land units lost).
   */
  @Override
  public boolean dropsAmphibiousStrafe(
      final ProData proData, final Territory target, final double winPercentage) {
    if (!on(proData, Ablazione.Componente.STRAFE_ANFIBIO)) {
      return false;
    }
    return winPercentage < proData.getAttackMinWinPercentage(target);
  }

  @Override
  public boolean hasVictoryCityWinCondition(final GameProperties props) {
    if (!on(Ablazione.Componente.VICTORY_CITY)) {
      return ProTuning.super.hasVictoryCityWinCondition(props);
    }
    return ProTuning.super.hasVictoryCityWinCondition(props)
        || Properties.getTriggeredVictory(props);
  }

  @Override
  public double attackPriorityVictoryCityWeight() {
    if (!on(Ablazione.Componente.VICTORY_CITY)) {
      return ProTuning.super.attackPriorityVictoryCityWeight();
    }
    return ATTACK_PRIORITY_VICTORY_CITY_WEIGHT;
  }

  /**
   * Keeps an attack "Hard (AI)" drops only because its value is negative with the units assigned,
   * by one of two rules of the {@linkplain #riskLevel risk level of the target} (never at {@link
   * RiskLevel#PRUDENTE}; never for neutrals, nor below the desired win%):
   *
   * <ul>
   *   <li>{@value #KEPT_BY_RISK}: the attack {@linkplain #takesSomething takes something} and its
   *       value, with the level's counter-attack weight in place of Hard's 2/3, is at least minus
   *       the level's tolerance;
   *   <li>{@value #KEPT_BY_OBJECTIVE}: the target is an {@linkplain #isObjectiveTarget objective
   *       target}, the attack wins at least {@link RiskLevel#objectiveRuleMinWin} and the TUV
   *       expected to be lost ({@link ProAttackWorth#expectedTuvCost}) is at most the level's
   *       limit.
   * </ul>
   */
  @Override
  public Optional<String> keepsAttackNotWorthIt(final ProData proData, final ProAttackWorth worth) {
    if (OperazioniCongiunte.forzaBersaglio(proData, worth.territory())) {
      return operationAnswer(Optional.of(KEPT_BY_OPERATION));
    }
    if (!on(proData, Ablazione.Componente.NON_CONVIENE)) {
      return Optional.empty();
    }
    return check(proData, worth).keptBy();
  }

  /**
   * Keeps a landing Hard drops because its transports are exposed, by the objective rule only: the
   * target is an {@linkplain #isObjectiveTarget objective target}, the landing wins at least {@link
   * RiskLevel#objectiveRuleMinWin} and the TUV expected to be lost (0.75 × the enemy's TUV swing
   * against the transports minus the landing's value) is at most the level's limit. Without it a
   * victory city landing kept by {@link #keepsAttackNotWorthIt} would be dropped here anyway, and
   * the units it held wasted. Never at {@link RiskLevel#PRUDENTE}.
   */
  @Override
  public Optional<String> keepsLandingWithExposedTransports(
      final ProData proData,
      final Territory territory,
      final double winPercentage,
      final double enemyTuvSwing,
      final double attackValue) {
    if (OperazioniCongiunte.forzaBersaglio(proData, territory)) {
      return operationAnswer(Optional.of(KEPT_BY_OPERATION));
    }
    // step ABLAZIONE: without the Pro+ transport rule the landing follows Hard's rule alone
    if (!on(proData, Ablazione.Componente.NON_CONVIENE)
        || !on(proData, Ablazione.Componente.TRASPORTI_SBARCO)) {
      return Optional.empty();
    }
    final GameState data = proData.getData();
    final RiskLevel level = riskLevel(proData, territory);
    final boolean kept =
        level.keepsAttacksNotWorthIt()
            && winPercentage >= level.objectiveRuleMinWin(isLowLuck(data))
            && exposedTransportsCost(enemyTuvSwing, attackValue) <= level.getObjectiveCostLimit()
            && isObjectiveTarget(proData, territory);
    if (kept) {
      return Optional.of(KEPT_BY_OBJECTIVE);
    }
    // step OPERAZIONI ANFIBIE-BIS: the partner's warships join the fleet in its turn; whether the
    // bridgehead holds is checked in the last check of the attacks (keepsBridgehead)
    final boolean bridgehead =
        level.keepsAttacksNotWorthIt()
            && winPercentage >= level.desiredWinPercentage(isLowLuck(data))
            && TestaDiPonte.primaSu(proData, territory).isPresent();
    return bridgehead ? Optional.of(KEPT_BY_BRIDGEHEAD) : Optional.empty();
  }

  // ------------------------------------------------------- transports after a landing (3d-1-bis)

  private String unloadKey = "";
  private @Nullable GameState unloadData;
  private final Map<Unit, Territory> unloadZones = new java.util.HashMap<>();

  /**
   * Step 3d-1-bis: the expected loss of the landing's transports (and escort) in their unload zones
   * against the expected gain, in place of Hard's "transports exposed" rule ({@link
   * TrasportiSbarco}); a safer zone next to the target is remembered for the route ({@link
   * #unloadZone}).
   */
  @Override
  public Optional<games.strategy.triplea.ai.pro.ProLandingTransports> checksLandingTransports(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritory landing,
      final Map<Territory, List<Unit>> zones,
      final games.strategy.triplea.ai.pro.data.ProOtherMoveOptions enemyAttackOptions,
      final java.util.Collection<Unit> moving,
      final games.strategy.triplea.ai.pro.data.ProBattleResult battle,
      final double conquestValue,
      final java.util.function.BiPredicate<Territory, java.util.Collection<Unit>> lostToScramble) {
    if (!on(proData, Ablazione.Componente.TRASPORTI_SBARCO)) {
      return Optional.empty();
    }
    final games.strategy.triplea.ai.pro.ProLandingTransports check =
        TrasportiSbarco.valuta(
            proData,
            calc,
            landing,
            zones,
            enemyAttackOptions,
            moving,
            battle,
            conquestValue,
            lostToScramble);
    syncUnloadZones(proData);
    landing.getAmphibAttackMap().keySet().forEach(unloadZones::remove);
    if (check.keep()) {
      unloadZones.putAll(check.unloadZones());
    }
    return Optional.of(check);
  }

  @Override
  public Optional<Territory> unloadZone(
      final ProData proData, final Unit transport, final Territory target) {
    if (!on(proData, Ablazione.Componente.TRASPORTI_SBARCO)) {
      return Optional.empty();
    }
    syncUnloadZones(proData);
    // only for the landing it was chosen for (a zone next to the target)
    return Optional.ofNullable(unloadZones.get(transport))
        .filter(z -> proData.getData().getMap().getNeighbors(target).contains(z));
  }

  private void syncUnloadZones(final ProData proData) {
    final String key = turnKey(proData);
    if (!key.equals(unloadKey) || unloadData != proData.getData()) {
      unloadKey = key;
      unloadData = proData.getData();
      unloadZones.clear();
    }
  }

  // ------------------------------------------------------------- two-step bridgehead (ANFIBIE-BIS)

  private String bridgeheadKey = "";
  private final Map<String, TestaDiPonte.Valutazione> bridgeheads = new java.util.TreeMap<>();
  private final Map<String, List<Unit>> reinforcements = new java.util.HashMap<>();
  private @Nullable GameState reinforcementsData;

  /**
   * Keeps a landing Hard drops when it is the first step of a two-step bridgehead ({@link
   * TestaDiPonte}): a common target of a combined operation where I move first, a landing at the
   * desired win% of the target's effective level and a counter-attack, after the partner's
   * reinforcements of this round, at most {@code 100 - minimum win%} of that level. Never at {@link
   * RiskLevel#PRUDENTE}.
   */
  @Override
  public Optional<String> keepsBridgehead(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritory attack,
      final ProAttackWorth worth,
      final java.util.Collection<Unit> holders) {
    final Territory target = worth.territory();
    if (!on(proData, Ablazione.Componente.TESTA_DI_PONTE)) {
      return Optional.empty();
    }
    if (worth.capturesTooRarely() || worth.neutral()) {
      return Optional.empty();
    }
    final Optional<TestaDiPonte.Coppia> pair = TestaDiPonte.primaSu(proData, target);
    if (pair.isEmpty()) {
      return Optional.empty();
    }
    final RiskLevel level = riskLevel(proData, target);
    if (!level.keepsAttacksNotWorthIt()
        || worth.winPercentage() < level.desiredWinPercentage(isLowLuck(proData.getData()))) {
      return Optional.empty();
    }
    final TestaDiPonte.Valutazione v =
        TestaDiPonte.valuta(
            proData,
            calc,
            target,
            attack.getMaxEnemyUnits(),
            attack.getMaxBombardUnits(),
            worth.winPercentage(),
            worth.counterWinPercentage(),
            holders,
            pair.get(),
            reinforcementsFor(proData, calc, target, pair.get()));
    noteBridgehead(proData, v);
    return v.tenuta() ? Optional.of(KEPT_BY_BRIDGEHEAD) : Optional.empty();
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS, rules that also drop landings Hard would keep, on a common target
   * of a combined operation:
   *
   * <ul>
   *   <li>the nation moving first lands only on the pair's target ({@link
   *       TestaDiPonte#bersaglioScelto}: the first common target active this round and not yet
   *       allied, in the plan's order), none of the operation's other targets (also those not
   *       active yet), and only if the counter-attack after the partner's reinforcements wins at
   *       most {@code 100 - minimum win%} of the target's effective level;
   *   <li>the nation moving second lands on no other common target while the partner holds a
   *       bridgehead (it reinforces that one in its non-combat move).
   * </ul>
   */
  /** Code of an attack dropped by the fall-back's counter-attack rule (step 3d-2). */
  public static final String DROP_NOT_ISOLATED = "delay_not_isolated";

  /**
   * Step 3d-2, fighting retreat: where the tool is on for the nation and the target's front, a land
   * attack is kept only against an isolated enemy stack: win% at least the desired win% of the
   * target and the enemy's counter-attack win% at most {@code 100 - minimum} of the effective level
   * (no counter-attack possible = isolated).
   */
  @Override
  public Optional<String> dropsLandAttack(final ProData proData, final ProAttackWorth worth) {
    final Territory target = worth.territory();
    final Optional<PianoAttivo> plan = planOf(proData);
    if (plan.isEmpty()
        || plan.get()
            .tattica(games.strategy.triplea.ai.proplus.strategia.Tattiche.RITIRATA, target)
            .isEmpty()) {
      return Optional.empty();
    }
    final boolean lowLuck = isLowLuck(proData.getData());
    final double counterLimit = 100 - riskLevel(proData, target).minimumWinPercentage(lowLuck);
    final boolean strongEnough = worth.winPercentage() >= desiredWinPercentage(proData, target);
    final boolean isolated =
        Double.isNaN(worth.counterWinPercentage()) || worth.counterWinPercentage() <= counterLimit;
    return strongEnough && isolated ? Optional.empty() : Optional.of(DROP_NOT_ISOLATED);
  }

  @Override
  public Optional<String> dropsLanding(
      final ProData proData,
      final ProOddsCalculator calc,
      final ProTerritory attack,
      final ProAttackWorth worth,
      final java.util.Collection<Unit> holders,
      final java.util.Collection<ProTerritory> planned) {
    final Territory target = attack.getTerritory();
    if (!on(proData, Ablazione.Componente.TESTA_DI_PONTE)) {
      return Optional.empty();
    }
    final Optional<PianoAttivo> plan = planOf(proData);
    if (plan.isEmpty() || target.isWater()) {
      return Optional.empty();
    }
    for (final TestaDiPonte.Coppia pair : TestaDiPonte.coppieDi(proData)) {
      if (!pair.primaIo()) {
        if (!pair.bersagliComuni().contains(target.getName())) {
          continue;
        }
        final Optional<Territory> head = TestaDiPonte.testaDiPonteAlleata(proData.getData(), pair);
        if (head.isPresent() && !head.get().equals(target)) {
          return Optional.of(DROP_OTHER_TARGET);
        }
        continue;
      }
      // every target of the pair, also those not active this round (Holland Belgium before R8)
      final List<String> all = plan.get().bersagliCompleti(pair.mia().nome());
      if (!all.contains(target.getName())) {
        continue;
      }
      final Optional<Territory> chosen =
          TestaDiPonte.bersaglioScelto(proData.getData(), pair, proData.getPlayer());
      if (chosen.isEmpty()) {
        continue;
      }
      if (!chosen.get().equals(target)) {
        return Optional.of(DROP_OTHER_TARGET);
      }
      final TestaDiPonte.Valutazione v =
          TestaDiPonte.valuta(
              proData,
              calc,
              target,
              attack.getMaxEnemyUnits(),
              attack.getMaxBombardUnits(),
              worth.winPercentage(),
              worth.counterWinPercentage(),
              holders,
              pair,
              reinforcementsFor(proData, calc, target, pair));
      noteBridgehead(proData, v);
      if (v.contrattaccoDopo() > v.soglia()) {
        return Optional.of(DROP_NOT_HELD);
      }
    }
    return Optional.empty();
  }

  /** The partner's reinforcements for {@code target}, once per game state, player and round. */
  List<Unit> reinforcementsFor(
      final ProData proData,
      final @Nullable ProOddsCalculator calc,
      final Territory target,
      final TestaDiPonte.Coppia pair) {
    syncBridgeheads(proData);
    return reinforcements.computeIfAbsent(
        target.getName(), k -> TestaDiPonte.rinforzi(proData, calc, target, pair));
  }

  void noteBridgehead(final ProData proData, final TestaDiPonte.Valutazione v) {
    syncBridgeheads(proData);
    bridgeheads.put(v.territorio(), v);
  }

  /** The bridgehead estimates of {@code player}'s last combat move planning this round. */
  public Map<String, TestaDiPonte.Valutazione> bridgeheads(
      final GamePlayer player, final int round) {
    return bridgeheadKey.equals(player.getName() + "|" + round)
        ? Map.copyOf(bridgeheads)
        : Map.of();
  }

  /** A new turn or game state (eg the purchase's simulation, then the real game) starts afresh. */
  private void syncBridgeheads(final ProData proData) {
    final String key = turnKey(proData);
    if (!key.equals(bridgeheadKey) || reinforcementsData != proData.getData()) {
      bridgeheadKey = key;
      reinforcementsData = proData.getData();
      bridgeheads.clear();
      reinforcements.clear();
    }
  }

  private static String turnKey(final ProData proData) {
    return proData.getPlayer().getName() + "|" + proData.getData().getSequence().getRound();
  }

  /** TUV expected to be lost by a landing with exposed transports (Hard's own weights). */
  public static double exposedTransportsCost(final double enemyTuvSwing, final double attackValue) {
    return 0.75 * enemyTuvSwing - attackValue;
  }

  /** The numbers of {@link #keepsAttackNotWorthIt}, also for the decision diary. */
  public static UnworthAttackCheck check(final ProData proData, final ProAttackWorth worth) {
    final GameState data = proData.getData();
    final RiskLevel level = riskLevel(proData, worth.territory());
    final boolean lowLuck = isLowLuck(data);
    final double riskValue = worth.attackValue(level.getCounterAttackWeight());
    final boolean objectiveTarget = isObjectiveTarget(proData, worth.territory());
    final double objectiveMinWin = level.objectiveRuleMinWin(lowLuck);
    String keptBy = null;
    final double desired = desiredWinPercentage(proData, worth.territory());
    if (level.keepsAttacksNotWorthIt()
        && !worth.neutral()
        && !worth.capturesTooRarely()
        && worth.winPercentage() >= desired) {
      if (takesSomething(worth) && riskValue >= -level.getValueTolerance()) {
        keptBy = KEPT_BY_RISK;
      } else if (objectiveTarget
          && worth.winPercentage() >= objectiveMinWin
          && worth.expectedTuvCost() <= level.getObjectiveCostLimit()) {
        keptBy = KEPT_BY_OBJECTIVE;
      }
    }
    return new UnworthAttackCheck(
        level,
        desired,
        riskValue,
        objectiveTarget,
        planOf(proData).isPresent(),
        objectiveMinWin,
        Optional.ofNullable(keptBy));
  }

  /**
   * Whether the attack gains something besides trading units: land worth taking (production,
   * factory, capital) or a sea battle won on TUV. A land target worth nothing is only a TUV trade,
   * and an attack Hard finds negative there stays dropped.
   */
  static boolean takesSomething(final ProAttackWorth worth) {
    return worth.territoryValue() > 0 || (worth.territory().isWater() && worth.tuvSwing() > 0);
  }

  /**
   * Target of the objective rule. With a strategy plan: an objective of the plan's current phase
   * for this nation (land or, for a fleet objective, a sea zone; never a neutral). Without a plan
   * (no strategy file for the map, step 2d rule): enemy land with a victory city, or with national
   * objectives at stake for my side, that gives the enemy production or a victory city.
   */
  static boolean isObjectiveTarget(final ProData proData, final Territory t) {
    if (ProUtils.isNeutralLand(t)) {
      return false;
    }
    final Optional<PianoAttivo> plan = planOf(proData);
    if (plan.isPresent()) {
      return plan.get().isObiettivo(t);
    }
    if (t.isWater()) {
      return false;
    }
    final int victoryCities =
        TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0);
    return victoryCities > 0
        || (TerritoryAttachment.getProduction(t) > 0
            && ProNationalObjectiveUtils.stake(proData.getPlayer(), t) > 0);
  }

  /**
   * What {@link #check} found.
   *
   * @param desired the level's desired win% (both rules need it)
   * @param riskValue the attack value with the level's counter-attack weight
   * @param objectiveTarget whether the target counts for the objective rule
   * @param planRule whether the objective rule is the plan's ({@code false}: step 2d victory city /
   *     national objective rule, no strategy for the map)
   * @param objectiveMinWin win% the objective rule needs
   * @param keptBy {@value #KEPT_BY_RISK}, {@value #KEPT_BY_OBJECTIVE} or empty (dropped)
   */
  public record UnworthAttackCheck(
      RiskLevel level,
      double desired,
      double riskValue,
      boolean objectiveTarget,
      boolean planRule,
      double objectiveMinWin,
      Optional<String> keptBy) {}

  static boolean isLowLuck(final GameState data) {
    return Properties.getLowLuck(data.getProperties());
  }
}
