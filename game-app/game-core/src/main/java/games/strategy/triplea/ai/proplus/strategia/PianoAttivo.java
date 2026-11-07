package games.strategy.triplea.ai.proplus.strategia;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * The general staff's orders for one nation in one round: the alliance's plan in force, its phase
 * for the round, the risk level per front and the nation's objectives. Tactics (the Pro AI with
 * {@code ProPlusTuning}) read it per target.
 *
 * <p>Effective risk of an attack = the alliance's base level (slider) + the phase's modifier for
 * the target's front (-1, 0, +1), limited to 1..5 ({@link RiskLevel#withModifier}).
 */
@Getter
public final class PianoAttivo {
  private final Strategia strategia;
  private final String alleanza;
  private final String nazione;
  private final int round;
  private final Strategia.Piano piano;

  /** Phase of {@link #round}; null if the plan has none for it (every front 0, no objective). */
  private final @Nullable Strategia.Fase fase;

  /** Round the plan was fixed in; empty while not fixed yet (only a choice). */
  private final Optional<Integer> roundInizio;

  /** Game properties holding the alliance's slider (read on each use: the dialog may change it). */
  @Getter(AccessLevel.NONE)
  private final GameProperties props;

  private final List<Strategia.Obiettivo> obiettivi;

  /** Amphibious operations of the plan in force this round for this nation (step OPERAZIONI). */
  private final List<Strategia.Operazione> operazioni;

  private PianoAttivo(
      final Strategia strategia,
      final String alleanza,
      final GamePlayer player,
      final int round,
      final Strategia.Piano piano,
      final Optional<Integer> roundInizio,
      final GameProperties props) {
    this.strategia = strategia;
    this.alleanza = alleanza;
    this.nazione = player.getName();
    this.round = round;
    this.piano = piano;
    this.fase = piano.fase(round).orElse(null);
    this.roundInizio = roundInizio;
    this.props = props;
    this.operazioni = piano.operazioni(nazione, round);
    final List<Strategia.Obiettivo> list = new ArrayList<>();
    if (fase != null) {
      fase.obiettivi().stream().filter(o -> o.appliesTo(nazione)).forEach(list::add);
    }
    // the targets of an operation whose units are mine are objectives too (step OPERAZIONI)
    for (final Strategia.Operazione op : operazioni) {
      if (!op.nazioni().contains(nazione)) {
        continue;
      }
      for (int i = 0; i < op.bersagli().size(); i++) {
        final String target = op.bersagli().get(i);
        if (list.stream().noneMatch(o -> target.equals(o.territorio()))) {
          list.add(
              new Strategia.Obiettivo(
                  target, null, op.pesoBersaglio(i), Set.of(nazione), op.critico()));
        }
      }
    }
    this.obiettivi = List.copyOf(list);
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS: the operations of the same plan, in force this round, of another
   * nation whose gathering zones share one with {@code mine} (eg Overlord UK for Overlord USA):
   * they form a combined fleet with it.
   */
  public List<Strategia.Operazione> operazioniCombinate(final Strategia.Operazione mine) {
    return piano.operazioni().stream()
        .filter(o -> !o.equals(mine) && o.covers(round) && !o.riguarda(nazione))
        .filter(o -> o.raccolta().stream().anyMatch(mine.raccolta()::contains))
        .toList();
  }

  /** Whether {@code t} is a landing target of an operation whose units are mine. */
  public boolean isBersaglioOperazione(final Territory t) {
    return operazioni.stream()
        .anyMatch(op -> op.nazioni().contains(nazione) && op.bersagli().contains(t.getName()));
  }

  /**
   * The orders for {@code player} in the current round; empty if the map has no strategy file or
   * the player's alliance has no plans.
   */
  public static Optional<PianoAttivo> of(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return Optional.empty();
    }
    final Optional<Strategia> strategia = Strategie.forGame(data);
    if (strategia.isEmpty()) {
      return Optional.empty();
    }
    return of(strategia.get(), data, player, alliance.get());
  }

  static Optional<PianoAttivo> of(
      final Strategia strategia,
      final GameState data,
      final GamePlayer player,
      final String alliance) {
    final var props = data.getProperties();
    return StatoMaggiore.activePlan(props, alliance, strategia)
        .map(
            plan ->
                new PianoAttivo(
                    strategia,
                    alliance,
                    player,
                    data.getSequence().getRound(),
                    plan,
                    StatoMaggiore.getState(props, alliance)
                        .filter(s -> s.piano().equals(plan.id()))
                        .map(StatoMaggiore.Stato::roundInizio),
                    props));
  }

  public String fronte(final Territory t) {
    return strategia.fronteOf(t);
  }

  /** Modifier of {@code front} in this round's phase (-1..+1, 0 without a phase). */
  public int modificatore(final String front) {
    return fase == null ? 0 : fase.modificatore(front);
  }

  /** The alliance's slider level. */
  public RiskLevel getLivelloBase() {
    return ProPlusSettings.getRiskLevel(props, alleanza);
  }

  public RiskLevel livelloFronte(final String front) {
    return getLivelloBase().withModifier(modificatore(front));
  }

  /** Effective risk level of an attack on {@code t}. */
  public RiskLevel livelloEffettivo(final Territory t) {
    return livelloFronte(fronte(t));
  }

  /** Effective level of every front, in file order (then "Altro"). */
  public Map<String, RiskLevel> livelliPerFronte() {
    final Map<String, RiskLevel> levels = new LinkedHashMap<>();
    strategia.getFronti().forEach(f -> levels.put(f, livelloFronte(f)));
    return levels;
  }

  /** Highest desired win% of any front (the most cautious front sets it). */
  public double maxDesiredWinPercentage(final boolean lowLuck) {
    return livelliPerFronte().values().stream()
        .mapToDouble(l -> l.desiredWinPercentage(lowLuck))
        .max()
        .orElse(getLivelloBase().desiredWinPercentage(lowLuck));
  }

  /** Objectives of this nation's phase that {@code t} matches. */
  public List<Strategia.Obiettivo> obiettiviDi(final Territory t) {
    return obiettivi.stream().filter(o -> matches(o, t)).toList();
  }

  public boolean isObiettivo(final Territory t) {
    return obiettivi.stream().anyMatch(o -> matches(o, t));
  }

  /** Whether {@code t} is a critical objective of this nation's phase (step 3b-ter). */
  public boolean isCritico(final Territory t) {
    return obiettivi.stream().anyMatch(o -> o.critico() && matches(o, t));
  }

  /** Priority bonus of {@code t}: the highest weight among the objectives it matches, else 0. */
  public double peso(final Territory t) {
    return obiettivi.stream()
        .filter(o -> matches(o, t))
        .mapToDouble(Strategia.Obiettivo::peso)
        .max()
        .orElse(0);
  }

  /** The nation's purchase / placement / staging orders for this round (step 3b), if any. */
  public Optional<Strategia.Logistica> getLogistica() {
    return piano.logistica(nazione, round);
  }

  /**
   * Gathering territories of the staging orders: listed ones, else the front's land territories.
   */
  public List<String> territoriAmmassamento(final Strategia.Logistica l) {
    if (!l.territoriAmmassamento().isEmpty() || l.fronteAmmassamento() == null) {
      return l.territoriAmmassamento();
    }
    return strategia.territoriDelFronte(l.fronteAmmassamento());
  }

  /** Eg: "A Barbarossa, fase R1-2". */
  public String label() {
    return piano.label() + ", fase " + (fase == null ? "nessuna" : fase.label());
  }

  static boolean matches(final Strategia.Obiettivo o, final Territory t) {
    if (o.territorio() != null) {
      return o.territorio().equals(t.getName());
    }
    return t.isWater()
        && t.getUnitCollection()
            .anyMatch(
                u -> u.getOwner().getName().equals(o.flotta()) && Matches.unitIsSea().test(u));
  }
}
