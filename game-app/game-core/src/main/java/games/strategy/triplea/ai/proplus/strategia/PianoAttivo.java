package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonObject;
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

  /** Step 3d-1: round this plan took over in after a plan change; empty if never changed. */
  private final Optional<Integer> roundSubentro;

  /** Game properties holding the alliance's slider (read on each use: the dialog may change it). */
  @Getter(AccessLevel.NONE)
  private final GameProperties props;

  private final List<Strategia.Obiettivo> obiettivi;

  @Getter(AccessLevel.NONE)
  private final GameState dataPerCondizioni;

  @Getter(AccessLevel.NONE)
  private final Map<String, Strategia.Operazione> attivaSalvata = new java.util.HashMap<>();

  /** Amphibious operations of the plan in force this round for this nation (step OPERAZIONI). */
  private final List<Strategia.Operazione> operazioni;

  /**
   * Step 3d-1: the alliance's posture this round ({@link Contromosse}); {@link
   * Contromosse#OFFENSIVA} without a decision for this round and plan.
   */
  private final String postura;

  /** Step 3d-1: the enemy's critical points made objectives by the posture this round. */
  private final List<Strategia.Obiettivo> controObiettivi;

  /** Step 3d-2: the switches of the delay tools this round ({@link Tattiche}), if any. */
  @Getter(AccessLevel.NONE)
  private final @Nullable JsonObject tattiche;

  /** Strategic contract: the directives of the round (the plan's base orders if none yet). */
  private final Direttive direttive;

  /** Step 3d-2: the enemy transports hunted this turn (counter-objectives). */
  private final List<Caccia> caccia;

  /** Step 3d-2: the most probable enemy plan read at the start of the round ("" if none). */
  private final String letturaNemico;

  /** Step 3d-2: its probability (0 if none). */
  private final double probabilitaNemico;

  private PianoAttivo(
      final GameState data,
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
    this.roundSubentro =
        StatoMaggiore.getState(props, alleanza)
            .filter(st -> st.piano().equals(piano.id()) && !st.cambi().isEmpty())
            .map(StatoMaggiore.Stato::roundPiano);
    this.props = props;
    this.dataPerCondizioni = data;
    // strategic contract: every strategic choice comes from the directives of the round
    this.direttive =
        Direttive.of(
            directives(props, alleanza, nazione, piano.id(), round)
                .orElseGet(() -> DecisoreRegole.base(strategia, alleanza, piano, round)));
    this.operazioni =
        piano.operazioni(nazione, round).stream()
            .filter(o -> !Direttive.ANNULLA.equals(direttive.operazione(o.nome()).decisione()))
            .map(o -> attiva(data, o))
            .toList();
    final List<Strategia.Obiettivo> list = new ArrayList<>();
    direttive.bersagli().stream()
        .filter(b -> !b.isContromossa())
        .map(Direttive.Bersaglio::obiettivo)
        .filter(o -> o.appliesTo(nazione))
        .forEach(list::add);
    // the targets of an operation whose units are mine are objectives too (step OPERAZIONI),
    // not while the directives keep it waiting
    for (final Strategia.Operazione op : operazioni) {
      if (!op.nazioni().contains(nazione) || isInAttesa(op)) {
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
    this.postura = direttive.postura();
    final List<Strategia.Obiettivo> counter = new ArrayList<>();
    direttive.bersagli().stream()
        .filter(Direttive.Bersaglio::isContromossa)
        .forEach(
            b ->
                counter.add(
                    new Strategia.Obiettivo(
                        b.territorio(), b.flotta(), b.peso(), Set.of(), false)));
    this.letturaNemico = direttive.letturaPiano();
    this.probabilitaNemico = direttive.letturaProbabilita();
    this.tattiche = direttive.tattiche();
    // step 3d-2: hunted enemy transports are counter-objectives with the file's fixed weight
    this.caccia = huntedTransports(data);
    caccia.stream()
        .filter(c -> counter.stream().noneMatch(o -> c.zona().equals(o.territorio())))
        .forEach(
            c ->
                counter.add(
                    new Strategia.Obiettivo(
                        c.zona(),
                        null,
                        strategia.getParametri().ritardo().pesoCacciaTrasporti(),
                        Set.of(),
                        false)));
    this.controObiettivi = List.copyOf(counter);
    list.addAll(controObiettivi);
    this.obiettivi = List.copyOf(list);
  }

  /**
   * Step 3d-2: a sea zone with enemy transports the hunt makes a counter-objective: {@code
   * trasporti} = their unit ids, {@code perche} = loaded and/or in a gathering zone of the enemy
   * plan read, and why the tool is on.
   */
  public record Caccia(String zona, List<String> trasporti, String perche) {}

  /**
   * Sea zones with transports of nations at war with mine, loaded or in a gathering zone of the
   * most probable enemy plan's operations, with own warships (by sea) or own planes within {@code
   * distanza_caccia}; only on the fronts where the hunt is on for my nation.
   */
  private List<Caccia> huntedTransports(final GameState data) {
    final Optional<String> anyFront = Tattiche.perche(tattiche, Tattiche.CACCIA, nazione, null);
    if (anyFront.isEmpty()) {
      return List.of();
    }
    final GamePlayer me = data.getPlayerList().getPlayerId(nazione);
    if (me == null) {
      return List.of();
    }
    final Set<String> gathering = new java.util.HashSet<>();
    pianoNemicoLetto().stream()
        .flatMap(p -> p.operazioni().stream())
        .forEach(o -> gathering.addAll(o.raccolta()));
    final int distance = strategia.getParametri().ritardo().distanzaCaccia();
    final List<Caccia> found = new ArrayList<>();
    for (final Territory zone : data.getMap().getTerritories()) {
      if (!zone.isWater()) {
        continue;
      }
      final List<games.strategy.engine.data.Unit> transports =
          zone.getMatches(
              Matches.unitIsSeaTransport()
                  .and(u -> data.getRelationshipTracker().isAtWar(me, u.getOwner())));
      if (transports.isEmpty()) {
        continue;
      }
      final boolean loaded = transports.stream().anyMatch(u -> !u.getTransporting().isEmpty());
      final boolean inGathering = gathering.contains(zone.getName());
      if (!loaded && !inGathering) {
        continue;
      }
      final Optional<String> on = Tattiche.perche(tattiche, Tattiche.CACCIA, nazione, fronte(zone));
      if (on.isEmpty() || !canReach(data, me, zone, distance)) {
        continue;
      }
      found.add(
          new Caccia(
              zone.getName(),
              transports.stream().map(u -> u.getId().toString()).sorted().toList(),
              (loaded && inGathering
                      ? "carichi e in zona di raccolta"
                      : loaded ? "carichi" : "in zona di raccolta")
                  + " ("
                  + on.get()
                  + ")"));
    }
    return List.copyOf(found);
  }

  private static boolean canReach(
      final GameState data, final GamePlayer me, final Territory zone, final int distance) {
    final java.util.function.Predicate<games.strategy.engine.data.Unit> warship =
        Matches.unitIsOwnedBy(me)
            .and(Matches.unitIsSea())
            .and(u -> u.getUnitAttachment().getAttack(me) > 0);
    final java.util.function.Predicate<games.strategy.engine.data.Unit> plane =
        Matches.unitIsOwnedBy(me)
            .and(Matches.unitIsAir())
            .and(u -> u.getUnitAttachment().getAttack(me) > 0);
    if (zone.anyUnitsMatch(warship.or(plane))) {
      return true;
    }
    return data.getMap().getNeighbors(zone, distance, Matches.territoryIsWater()).stream()
            .anyMatch(t -> t.anyUnitsMatch(warship))
        || data.getMap().getNeighbors(zone, distance).stream()
            .anyMatch(t -> t.anyUnitsMatch(plane));
  }

  /** Step 3d-2: the enemy alliance's plan read as most probable at the start of the round. */
  public Optional<Strategia.Piano> pianoNemicoLetto() {
    if (letturaNemico.isEmpty()) {
      return Optional.empty();
    }
    return ProPlusSettings.configurableAlliances(dataPerCondizioni).stream()
        .filter(a -> !a.equals(alleanza))
        .flatMap(a -> strategia.alleanza(a).stream())
        .flatMap(a -> a.piano(letturaNemico).stream())
        .findFirst();
  }

  /**
   * Step 3d-2: why the delay tool {@code tool} is on for this nation on {@code front} ({@code null}
   * = any front), empty if off ({@link Tattiche}).
   */
  public Optional<String> tattica(final String tool, final @Nullable String front) {
    return Tattiche.perche(tattiche, tool, nazione, front);
  }

  /** Step 3d-2: {@link #tattica} on the front of {@code t}. */
  public Optional<String> tattica(final String tool, final Territory t) {
    return tattica(tool, fronte(t));
  }

  /**
   * The directives of the history entry of {@code round}, if they were written with {@code planId}
   * in force.
   */
  /**
   * The directives of the round for {@code nation}: its turn orders merged with the strategic
   * directives (LLM step, {@link OrdiniTurno}) if it has some, else the strategic directives.
   */
  private static Optional<JsonObject> directives(
      final GameProperties props,
      final String alliance,
      final String nation,
      final String planId,
      final int round) {
    final Optional<StatoMaggiore.Stato> state = StatoMaggiore.getState(props, alliance);
    if (state.isEmpty() || state.get().storico().isEmpty()) {
      return Optional.empty();
    }
    final JsonObject last =
        state.get().storico().get(state.get().storico().size() - 1).getAsJsonObject();
    if (!last.has("round")
        || last.get("round").getAsInt() != round
        || !last.has(Valutazione.KEY_DIRETTIVE)) {
      return Optional.empty();
    }
    JsonObject d = last.getAsJsonObject(Valutazione.KEY_DIRETTIVE);
    if (last.has(OrdiniTurno.KEY) && last.getAsJsonObject(OrdiniTurno.KEY).has(nation)) {
      final JsonObject own = last.getAsJsonObject(OrdiniTurno.KEY).getAsJsonObject(nation);
      if (own.has(Valutazione.KEY_DIRETTIVE)) {
        d = own.getAsJsonObject(Valutazione.KEY_DIRETTIVE);
      }
    }
    return planId.equals(d.get("piano").getAsString()) ? Optional.of(d) : Optional.empty();
  }

  /** Strategic contract: whether the directives keep {@code op} waiting (no landing this round). */
  public boolean isInAttesa(final Strategia.Operazione op) {
    return Direttive.ATTENDI.equals(direttive.operazione(op.nome()).decisione());
  }

  /**
   * Strategic contract: the transport loss the directives accept for a landing on {@code t} beyond
   * the safety rule (TUV, 0 = none): the highest of my launched operations targeting it.
   */
  public double perditaAccettata(final Territory t) {
    double max = 0;
    for (final Strategia.Operazione op : operazioni) {
      if (op.nazioni().contains(nazione)
          && op.bersagli().contains(t.getName())
          && !isInAttesa(op)) {
        max = Math.max(max, direttive.operazione(op.nome()).perditaAccettata());
      }
    }
    return max;
  }

  /**
   * Strategic contract: whether {@code t} is a target of my operations only while they wait (the
   * directives say "attendi"): no landing on it this round.
   */
  public boolean isBersaglioInAttesa(final Territory t) {
    final List<Strategia.Operazione> mine =
        operazioni.stream()
            .filter(op -> op.nazioni().contains(nazione) && op.bersagli().contains(t.getName()))
            .toList();
    return !mine.isEmpty() && mine.stream().allMatch(this::isInAttesa);
  }

  /** Step 3d-1: posture DIFENSIVA (risk −1 on every front, defense before the plan's share). */
  public boolean isDifensiva() {
    return Contromosse.DIFENSIVA.equals(postura);
  }

  /**
   * Step OPERAZIONI ANFIBIE-BIS: the operations of the same plan, in force this round, of another
   * nation whose gathering zones share one with {@code mine} (eg Overlord UK for Overlord USA):
   * they form a combined fleet with it.
   */
  public List<Strategia.Operazione> operazioniCombinate(final Strategia.Operazione mine) {
    return piano.operazioni().stream()
        .filter(o -> !o.nome().equals(mine.nome()) && o.covers(round) && !o.riguarda(nazione))
        .filter(o -> !Direttive.ANNULLA.equals(direttive.operazione(o.nome()).decisione()))
        .filter(o -> o.raccolta().stream().anyMatch(mine.raccolta()::contains))
        .map(o -> attivaSalvata.computeIfAbsent(o.nome(), k -> attiva(dataPerCondizioni, o)))
        .toList();
  }

  /** Every target of the operation named {@code nome}, also those not active this round. */
  public List<String> bersagliCompleti(final String nome) {
    return piano.operazioni().stream()
        .filter(o -> o.nome().equals(nome))
        .findFirst()
        .map(Strategia.Operazione::bersagli)
        .orElse(List.of());
  }

  /**
   * {@code op} with its active targets this round: a conditional target only from its round or when
   * its condition holds ({@link CondizioniBersaglio}).
   */
  private Strategia.Operazione attiva(final GameState data, final Strategia.Operazione op) {
    if (op.condizionati().isEmpty()) {
      return op;
    }
    final List<String> active =
        op.bersagli().stream()
            .filter(
                t ->
                    !op.condizionati().containsKey(t)
                        || CondizioniBersaglio.attivo(
                            data, strategia, round, op.condizionati().get(t)))
            .toList();
    return op.conBersagli(active);
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
                    data,
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

  /**
   * Risk offset of {@code front} for this nation in the directives of the round (-1..+1; the
   * decider by rules writes the phase's modifier for the whole alliance, 0 without a phase).
   */
  public int modificatore(final String front) {
    // schema v1.1: the nation's own offset if the directives give one
    return direttive.rischio(nazione, front);
  }

  /** The alliance's slider level. */
  public RiskLevel getLivelloBase() {
    return ProPlusSettings.getRiskLevel(props, alleanza);
  }

  /** The front's level: the slider moved by the phase's modifier, then −1 in DIFENSIVA. */
  public RiskLevel livelloFronte(final String front) {
    final RiskLevel level = getLivelloBase().withModifier(modificatore(front));
    return isDifensiva() ? level.withModifier(-1) : level;
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

  /**
   * Step 3d-1-bis: weight of {@code t} as an objective of this nation in the round's phase of the
   * data file (the highest if several), 0 if none. Operation targets and counter-objectives of the
   * posture are not counted here.
   */
  public double pesoObiettivoFase(final Territory t) {
    return direttive.bersagli().stream()
        .filter(b -> !b.isContromossa())
        .map(Direttive.Bersaglio::obiettivo)
        .filter(o -> o.appliesTo(nazione) && matches(o, t))
        .mapToDouble(Strategia.Obiettivo::peso)
        .max()
        .orElse(0);
  }

  /**
   * Step 3d-1-bis: weight of {@code t} as an active target of this nation's amphibious operations
   * ({@code peso × 0.8^position}, the highest if several), 0 if none.
   */
  public double pesoBersaglioOperazione(final Territory t) {
    double max = 0;
    for (final Strategia.Operazione op : operazioni) {
      final int i = op.bersagli().indexOf(t.getName());
      if (op.nazioni().contains(nazione) && i >= 0 && !isInAttesa(op)) {
        max = Math.max(max, op.pesoBersaglio(i));
      }
    }
    return max;
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
