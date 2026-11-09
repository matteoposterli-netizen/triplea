package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import games.strategy.triplea.util.TuvUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.triplea.java.collections.IntegerMap;

/**
 * Step 3d-1: the general staff decides at the start of each round, after {@link Valutazione}
 * measured the progress of the own plan and read the enemy's.
 *
 * <p><b>Plan change</b> ({@link #cambio}): the plan in force has been in crisis ({@value
 * Valutazione#IN_CRISI}, or an operation of it in a stall) for {@link
 * Strategia.Parametri#roundCrisi} rounds in a row, fewer than {@link Strategia.Parametri#maxCambi}
 * changes were made, the last one at least {@link Strategia.Parametri#inerziaRound} rounds ago, and
 * one of its {@code subentri} scores at least {@link Strategia.Parametri#margineCambio} more. Score
 * of a plan = mean of three parts in 0..1, plus {@link Strategia.Parametri#bonusContrasto} if the
 * plan is an alternative (not the plan in force, which is in crisis here: step 3d-1-bis) and
 * counters ({@code contrasta}) the enemy plan read with a probability above {@link
 * Strategia.Parametri#sogliaLettura}:
 *
 * <ul>
 *   <li>milestones in reach: share of the plan's milestones held by the alliance, or whose
 *       territories are within {@link Strategia.Parametri#distanzaForze} of alliance land units (no
 *       milestone: 0.5);
 *   <li>forces near the targets: alliance land and air TUV / (alliance + enemy) within that
 *       distance of the plan's targets not held (milestones, objectives of the round's phase,
 *       landing targets); nothing there: 0.5;
 *   <li>state: the plan in force 0 (it is checked only in crisis), an alternative 0.5 (not
 *       measured).
 * </ul>
 *
 * <p><b>Posture</b> ({@link #statoStrategico}), from the state of the plan in force, the reading of
 * the enemy and the race (rounds to victory of each alliance from the victory cities it holds and
 * their trend over the last 3 rounds; what counts is {@link Strategia.Parametri#vittoria} of the
 * file, else the map's "{@code <alliance>} Honorable Victory VCs" property, else a majority):
 *
 * <ul>
 *   <li>{@value #DIFENSIVA}: plan IN CRISI and the enemy faster ⇒ risk −1 on every front and no
 *       plan share in the purchase (the real-threat defense first, uncapped);
 *   <li>{@value #CONTRASTO}: the enemy faster and a critical point of its plan read (probability ≥
 *       {@link Strategia.Parametri#sogliaContrasto}) attackable (own units within {@link
 *       Strategia.Parametri#distanzaAttaccabile}) ⇒ those points are main objectives (weight {@link
 *       Strategia.Parametri#pesoContrasto}), at most {@link Strategia.Parametri#roundContrasto}
 *       rounds in a row;
 *   <li>{@value #RALLENTAMENTO}: an even race and the enemy plan read above {@link
 *       Strategia.Parametri#sogliaLettura} ⇒ its critical points are secondary objectives (weight
 *       {@link Strategia.Parametri#pesoRallentamento});
 *   <li>{@value #OFFENSIVA}: otherwise (plan IN LINEA and ahead, or no counter-move applies) ⇒ no
 *       change.
 * </ul>
 *
 * <p>A plan with {@code contromosse} false (N) gets posture {@value #NESSUNA} and never changes.
 */
public final class Contromosse {
  public static final String OFFENSIVA = "OFFENSIVA";
  public static final String RALLENTAMENTO = "RALLENTAMENTO";
  public static final String CONTRASTO = "CONTRASTO";
  public static final String DIFENSIVA = "DIFENSIVA";
  public static final String NESSUNA = "NESSUNA";

  /** Rounds to victory when the trend does not lead there. */
  static final int MAI = 99;

  /** Rounds of the race's trend. */
  static final int TENDENZA = 3;

  private Contromosse() {}

  /**
   * The check of a plan change at the start of {@code round}.
   *
   * @param nuovo the plan chosen, if the plan changes
   * @param motivo why (Italian text)
   * @param json what was checked: condition, scores of the alternatives
   */
  public record Cambio(Optional<Strategia.Piano> nuovo, String motivo, JsonObject json) {}

  // ------------------------------------------------------------------------- plan change

  /**
   * Whether {@code alliance} changes {@code plan} now; {@code entry} is the history entry of this
   * round (progress and reading measured with {@code plan}).
   */
  public static Cambio cambio(
      final GameState data,
      final String alliance,
      final Strategia strategia,
      final Strategia.Piano plan,
      final StatoMaggiore.Stato state,
      final JsonObject entry,
      final int round) {
    final Strategia.Parametri par = strategia.getParametri();
    final JsonObject json = new JsonObject();
    final int crisis = crisisRounds(state, plan.id(), entry, round);
    json.addProperty("round_in_crisi", crisis);
    json.addProperty("cambi_fatti", state.cambi().size());
    if (!plan.contromosse() || plan.subentri().isEmpty()) {
      return none(json, "il piano " + plan.label() + " non ha subentri");
    }
    if (crisis < par.roundCrisi()) {
      return none(
          json,
          crisis == 0
              ? "piano non in crisi"
              : "piano in crisi da " + crisis + " round (servono " + par.roundCrisi() + ")");
    }
    if (state.cambi().size() >= par.maxCambi()) {
      return none(json, "gia' fatti " + par.maxCambi() + " cambi di piano");
    }
    if (!state.cambi().isEmpty() && round - state.roundPiano() < par.inerziaRound()) {
      return none(
          json,
          "ultimo cambio al round "
              + state.roundPiano()
              + " (almeno "
              + par.inerziaRound()
              + " round tra due cambi)");
    }
    final Optional<Strategia.Alleanza> plans = strategia.alleanza(alliance);
    if (plans.isEmpty()) {
      return none(json, "nessun piano per " + alliance);
    }
    final Lettura reading = Lettura.of(entry);
    final Forze forces = new Forze(data, alliance);
    // checked only in crisis: the plan in force scores its state as IN CRISI (a stalled operation
    // counts as crisis even when the progress reads IN RITARDO)
    // step 3d-1-bis: the "contrasta" bonus goes to the alternatives only; the plan in force is in
    // crisis here (IN CRISI or its operation in a stall), so its bonus is 0
    final Punteggio current =
        score(data, strategia, plan, forces, reading, Valutazione.IN_CRISI, false, round);
    final JsonArray scores = new JsonArray();
    scores.add(current.json());
    Punteggio best = null;
    for (final String id : plan.subentri()) {
      final Optional<Strategia.Piano> alt = plans.get().piano(id);
      if (alt.isEmpty()) {
        continue;
      }
      final Punteggio p = score(data, strategia, alt.get(), forces, reading, null, true, round);
      scores.add(p.json());
      if (best == null || p.totale() > best.totale()) {
        best = p;
      }
    }
    json.add("valutazioni", scores);
    if (best == null || best.totale() < current.totale() + par.margineCambio()) {
      return none(
          json,
          String.format(
              Locale.ROOT,
              "nessuna alternativa migliore di %.2f (%s; %s)",
              par.margineCambio(),
              parts(current),
              best == null ? "nessuna" : parts(best)));
    }
    final String why =
        String.format(
            Locale.ROOT,
            "piano %s in crisi da %d round%s; %s %.2f contro %s %.2f (tappe %.2f, forze %.2f%s)",
            plan.id(),
            crisis,
            stalledText(entry),
            best.piano().id(),
            best.totale(),
            plan.id(),
            current.totale(),
            best.tappe(),
            best.forze(),
            best.contrasto() > 0
                ? ", contrasta " + reading.piuProbabile() + " letto " + pct(reading.probabilita())
                : "");
    json.addProperty("scelto", best.piano().id());
    json.addProperty("motivo", why);
    return new Cambio(Optional.of(best.piano()), why, json);
  }

  /** Eg "1 0.33 = (tappe 0.50 + forze 0.50 + stato 0.00)/3 + contrasto 0.00". */
  private static String parts(final Punteggio p) {
    return String.format(
        Locale.ROOT,
        "%s %.2f = (tappe %.2f + forze %.2f + stato %.2f)/3 + contrasto %.2f",
        p.piano().id(),
        p.totale(),
        p.tappe(),
        p.forze(),
        p.stato(),
        p.contrasto());
  }

  private static Cambio none(final JsonObject json, final String why) {
    json.addProperty("motivo", why);
    return new Cambio(Optional.empty(), why, json);
  }

  /**
   * Rounds in a row, this one included, in which {@code planId} was in force and in crisis (state
   * IN CRISI or an operation in a stall).
   */
  static int crisisRounds(
      final StatoMaggiore.Stato state,
      final String planId,
      final JsonObject entry,
      final int round) {
    if (!isCrisis(entry)) {
      return 0;
    }
    int count = 1;
    int expected = round - 1;
    final JsonArray storico = state.storico();
    for (int i = storico.size() - 1; i >= 0; i--) {
      final JsonObject e = storico.get(i).getAsJsonObject();
      final int r = e.has("round") ? e.get("round").getAsInt() : -1;
      final String p = e.has("piano") ? e.get("piano").getAsString() : state.piano();
      if (r != expected || !p.equals(planId) || !isCrisis(e)) {
        break;
      }
      count++;
      expected--;
    }
    return count;
  }

  static boolean isCrisis(final JsonObject entry) {
    if (!entry.has("avanzamento")) {
      return false;
    }
    final JsonObject p = entry.getAsJsonObject("avanzamento");
    return Valutazione.IN_CRISI.equals(p.get("stato").getAsString())
        || (p.has("operazioni_in_stallo") && !p.getAsJsonArray("operazioni_in_stallo").isEmpty());
  }

  private static String stalledText(final JsonObject entry) {
    final JsonObject p = entry.getAsJsonObject("avanzamento");
    if (!p.has("operazioni_in_stallo") || p.getAsJsonArray("operazioni_in_stallo").isEmpty()) {
      return "";
    }
    final List<String> names = new ArrayList<>();
    p.getAsJsonArray("operazioni_in_stallo").forEach(e -> names.add(e.getAsString()));
    return " (" + String.join(", ", names) + " in stallo)";
  }

  /** The score of a plan, see the class comment. */
  record Punteggio(
      Strategia.Piano piano,
      double tappe,
      double forze,
      double stato,
      double contrasto,
      double totale,
      JsonObject json) {}

  static Punteggio score(
      final GameState data,
      final Strategia strategia,
      final Strategia.Piano plan,
      final Forze forces,
      final Lettura reading,
      final @Nullable String state,
      final boolean candidate,
      final int round) {
    final Strategia.Parametri par = strategia.getParametri();
    final int distance = par.distanzaForze();
    final GameMap map = data.getMap();
    int reachable = 0;
    for (final Strategia.Tappa t : plan.tappe()) {
      final Predicate<String> inReach =
          name -> {
            final Territory territory = map.getTerritoryOrNull(name);
            return territory != null
                && (forces.isOwn(territory.getOwner())
                    || forces.landNear(territory, distance, forces.own));
          };
      if (t.tutti()
          ? t.territori().stream().allMatch(inReach)
          : t.territori().stream().anyMatch(inReach)) {
        reachable++;
      }
    }
    final double milestones =
        plan.tappe().isEmpty() ? 0.5 : (double) reachable / plan.tappe().size();
    final Set<Territory> targets = targets(data, plan, forces, round);
    final Set<Territory> around = new LinkedHashSet<>();
    for (final Territory t : targets) {
      around.add(t);
      around.addAll(map.getNeighbors(t, distance));
    }
    final double own = forces.tuv(around, forces.own);
    final double enemy = forces.tuv(around, forces.enemy);
    final double near = own + enemy <= 0 ? 0.5 : own / (own + enemy);
    final double stateScore =
        state == null
            ? 0.5
            : switch (state) {
              case Valutazione.IN_LINEA -> 1;
              case Valutazione.IN_RITARDO -> 0.5;
              default -> 0;
            };
    final boolean counters =
        reading.probabilita() > par.sogliaLettura()
            && plan.contrasta().contains(reading.piuProbabile());
    final double counter = candidate && counters ? par.bonusContrasto() : 0;
    final double total = (milestones + near + stateScore) / 3 + counter;
    final JsonObject json = new JsonObject();
    json.addProperty("piano", plan.id());
    json.addProperty("tappe_raggiungibili", round2(milestones));
    json.addProperty("forze_vicine", round2(near));
    json.addProperty("tuv_nostro", Math.round(own));
    json.addProperty("tuv_nemico", Math.round(enemy));
    json.addProperty("stato", round2(stateScore));
    json.addProperty("contrasto", round2(counter));
    if (counters && !candidate) {
      json.addProperty("contrasto_escluso", "piano in vigore in crisi");
    }
    json.addProperty("punteggio", round2(total));
    return new Punteggio(plan, milestones, near, stateScore, counter, total, json);
  }

  /** Targets of {@code plan} not held by the alliance: milestones, phase objectives, landings. */
  private static Set<Territory> targets(
      final GameState data, final Strategia.Piano plan, final Forze forces, final int round) {
    final Set<String> names = new LinkedHashSet<>();
    plan.tappe().forEach(t -> names.addAll(t.territori()));
    plan.fase(round)
        .ifPresent(
            f ->
                f.obiettivi().stream()
                    .map(Strategia.Obiettivo::territorio)
                    .filter(java.util.Objects::nonNull)
                    .forEach(names::add));
    plan.operazioni().stream()
        .filter(o -> o.covers(round))
        .forEach(o -> names.addAll(o.bersagli()));
    final Set<Territory> targets = new LinkedHashSet<>();
    for (final String name : names) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t != null && !t.isWater() && !forces.isOwn(t.getOwner())) {
        targets.add(t);
      }
    }
    return targets;
  }

  // ------------------------------------------------------------------------------ posture

  /**
   * The strategic state of {@code alliance} at the start of {@code round} with {@code plan} in
   * force (after a change, if any): posture and why, race, counter-objectives, plan changes. Saved
   * in the history entry ({@code stato_strategico}) and read by {@link PianoAttivo}.
   */
  public static JsonObject statoStrategico(
      final GameState data,
      final String alliance,
      final Strategia strategia,
      final Strategia.Piano plan,
      final StatoMaggiore.Stato state,
      final JsonObject entry,
      final Cambio cambio,
      final int round) {
    final Strategia.Parametri par = strategia.getParametri();
    final JsonObject json = new JsonObject();
    json.addProperty("alleanza", alliance);
    json.addProperty("round", round);
    json.addProperty("piano", plan.id());
    json.addProperty("nome_piano", plan.label());
    json.addProperty("fase", plan.fase(round).map(Strategia.Fase::label).orElse("nessuna"));
    final String planState = entry.getAsJsonObject("avanzamento").get("stato").getAsString();
    json.addProperty("stato", planState);
    final Lettura reading = Lettura.of(entry);
    if (entry.has("lettura")) {
      final JsonObject r = new JsonObject();
      r.addProperty("piu_probabile", reading.piuProbabile());
      r.addProperty("probabilita", round2(reading.probabilita()));
      r.add("probabilita_piani", entry.getAsJsonObject("lettura").getAsJsonObject("probabilita"));
      json.add("lettura", r);
    }
    final Corsa race = Corsa.of(data, alliance, state.storico(), round, par);
    json.add("corsa", race.json());

    final Forze forces = new Forze(data, alliance);
    final Optional<Strategia.Piano> enemyPlan =
        forces
            .enemyAlliance
            .flatMap(strategia::alleanza)
            .flatMap(a -> a.piano(reading.piuProbabile()));
    final List<JsonObject> points =
        enemyPlan.map(p -> criticalTargets(data, p, forces, par)).orElse(List.of());
    String posture;
    String why;
    final List<JsonObject> counter = new ArrayList<>();
    if (!plan.contromosse()) {
      posture = NESSUNA;
      why = "il piano " + plan.label() + " non ha contromosse";
    } else if (Valutazione.IN_CRISI.equals(planState) && race.nemicoPiuVeloce()) {
      posture = DIFENSIVA;
      why = "piano IN CRISI e " + race.testo();
    } else if (race.nemicoPiuVeloce()
        && reading.probabilita() >= par.sogliaContrasto()
        && points.stream().anyMatch(p -> p.get("attaccabile").getAsBoolean())
        && contrastRounds(state.storico(), round) < par.roundContrasto()) {
      posture = CONTRASTO;
      why =
          race.testo()
              + " ma un punto critico di "
              + enemyPlan.get().label()
              + " (letto "
              + pct(reading.probabilita())
              + ") e' attaccabile";
      points.stream()
          .filter(p -> p.get("attaccabile").getAsBoolean())
          .forEach(p -> counter.add(withWeight(p, par.pesoContrasto(), "principale")));
    } else if (race.pari()
        && reading.probabilita() > par.sogliaLettura()
        && !points.isEmpty()
        // step ABLAZIONE: without RALLENTAMENTO the even race is OFFENSIVA (no effect)
        && games.strategy.triplea.ai.proplus.Ablazione.attivo(
            data.getProperties(),
            alliance,
            games.strategy.triplea.ai.proplus.Ablazione.Componente.RALLENTAMENTO)) {
      posture = RALLENTAMENTO;
      why =
          race.testo()
              + " e piano nemico "
              + enemyPlan.get().label()
              + " letto al "
              + pct(reading.probabilita());
      points.forEach(p -> counter.add(withWeight(p, par.pesoRallentamento(), "secondario")));
    } else {
      posture = OFFENSIVA;
      why =
          Valutazione.IN_LINEA.equals(planState) && race.inVantaggio()
              ? "piano IN LINEA e " + race.testo()
              : "nessuna contromossa: piano "
                  + planState
                  + ", "
                  + race.testo()
                  + readingWhy(reading, points, race, par, state.storico(), round);
    }
    json.addProperty("postura", posture);
    json.addProperty("motivo", why);
    final JsonArray counterJson = new JsonArray();
    counter.forEach(counterJson::add);
    json.add("contro_obiettivi", counterJson);
    final JsonArray candidates = new JsonArray();
    points.forEach(candidates::add);
    json.add("punti_critici_nemici", candidates);
    if (cambio.nuovo().isPresent() || cambio.json().has("valutazioni")) {
      json.add("cambio_piano", cambio.json());
    } else {
      json.addProperty("cambio_piano_motivo", cambio.motivo());
    }
    final JsonArray changes = state.cambi().deepCopy();
    json.add("cambi_piano", changes);
    // the switches of the delay tools: all off for the decider by rules (decision of Matteo)
    json.add(Tattiche.KEY, Tattiche.spente());
    return json;
  }

  private static String readingWhy(
      final Lettura reading,
      final List<JsonObject> points,
      final Corsa race,
      final Strategia.Parametri par,
      final JsonArray storico,
      final int round) {
    if (reading.piuProbabile().isEmpty()) {
      return "; piano nemico non letto";
    }
    if (race.nemicoPiuVeloce() && contrastRounds(storico, round) >= par.roundContrasto()) {
      return "; gia' " + par.roundContrasto() + " round di contrasto";
    }
    if (race.nemicoPiuVeloce()) {
      return points.isEmpty()
          ? "; nessun punto critico nemico"
          : "; nessun punto critico nemico attaccabile o lettura sotto "
              + pct(par.sogliaContrasto());
    }
    if (reading.probabilita() > par.sogliaLettura() && points.isEmpty()) {
      return "; piano nemico "
          + reading.piuProbabile()
          + " letto al "
          + pct(reading.probabilita())
          + " ma nessun suo punto critico presente";
    }
    return "; piano nemico "
        + reading.piuProbabile()
        + " letto al "
        + pct(reading.probabilita())
        + " (soglia "
        + pct(par.sogliaLettura())
        + ")";
  }

  /** CONTRASTO rounds in a row just before {@code round}. */
  private static int contrastRounds(final JsonArray storico, final int round) {
    int count = 0;
    int expected = round - 1;
    for (int i = storico.size() - 1; i >= 0; i--) {
      final JsonObject e = storico.get(i).getAsJsonObject();
      if (!e.has("round") || e.get("round").getAsInt() != expected) {
        break;
      }
      if (!e.has("stato_strategico")
          || !CONTRASTO.equals(
              e.getAsJsonObject("stato_strategico").get("postura").getAsString())) {
        break;
      }
      count++;
      expected--;
    }
    return count;
  }

  private static JsonObject withWeight(
      final JsonObject point, final double weight, final String r) {
    final JsonObject o = point.deepCopy();
    o.addProperty("peso", weight);
    o.addProperty("ruolo", r);
    return o;
  }

  /**
   * The concrete targets of the critical points of the enemy plan {@code enemyPlan}, for the
   * alliance of {@code forces}: [{territorio, punto_critico, attaccabile}].
   */
  static List<JsonObject> criticalTargets(
      final GameState data,
      final Strategia.Piano enemyPlan,
      final Forze forces,
      final Strategia.Parametri par) {
    final Map<String, JsonObject> found = new LinkedHashMap<>();
    final int round = data.getSequence().getRound();
    for (final Strategia.PuntoCritico p : enemyPlan.puntiCritici()) {
      if (!p.covers(round)) {
        continue;
      }
      for (final Territory t : places(data, p, forces)) {
        if (found.containsKey(t.getName())) {
          continue;
        }
        final JsonObject o = new JsonObject();
        o.addProperty("territorio", t.getName());
        o.addProperty("punto_critico", p.descrizione());
        o.addProperty("attaccabile", forces.canAttack(t, par.distanzaAttaccabile()));
        found.put(t.getName(), o);
      }
    }
    return List.copyOf(found.values());
  }

  /** Where the critical point {@code p} is now, as targets for the alliance of {@code forces}. */
  static List<Territory> places(
      final GameState data, final Strategia.PuntoCritico p, final Forze forces) {
    final GameMap map = data.getMap();
    final List<Territory> list = new ArrayList<>();
    final Predicate<Unit> ofNation =
        u ->
            p.nazione() == null
                ? forces.isEnemy(u.getOwner())
                : u.getOwner().getName().equals(p.nazione());
    switch (p.tipo()) {
      case Strategia.PuntoCritico.TERRITORIO -> {
        for (final String name : p.territori()) {
          final Territory t = map.getTerritoryOrNull(name);
          if (t != null && !t.isWater() && forces.isEnemy(t.getOwner())) {
            list.add(t);
          }
        }
      }
      case Strategia.PuntoCritico.UNITA, Strategia.PuntoCritico.FLOTTA -> {
        final Predicate<Unit> kind =
            Strategia.PuntoCritico.FLOTTA.equals(p.tipo())
                ? ofNation.and(Matches.unitIsSea())
                : ofNation.and(unitFilter(p));
        for (final Territory t : candidatePlaces(data, p)) {
          if (!t.isWater() && !forces.isEnemy(t.getOwner())) {
            continue;
          }
          if (t.anyUnitsMatch(kind)) {
            list.add(t);
          }
        }
      }
      case Strategia.PuntoCritico.STACK_AVANZATO ->
          advancedStack(data, p, forces).ifPresent(list::add);
      default -> {
        // rejected when the file is read
      }
    }
    return list;
  }

  private static List<Territory> candidatePlaces(
      final GameState data, final Strategia.PuntoCritico p) {
    final List<Territory> list = new ArrayList<>();
    final GameMap map = data.getMap();
    p.territori().stream()
        .map(map::getTerritoryOrNull)
        .filter(java.util.Objects::nonNull)
        .forEach(list::add);
    p.zone().stream()
        .map(map::getTerritoryOrNull)
        .filter(java.util.Objects::nonNull)
        .forEach(list::add);
    if (p.fronte() != null) {
      final Strategia s = Strategie.forGame(data).orElse(null);
      if (s != null) {
        map.getTerritories().stream()
            .filter(t -> p.fronte().equals(s.fronteOf(t)) && !list.contains(t))
            .forEach(list::add);
      }
    }
    return list;
  }

  private static Predicate<Unit> unitFilter(final Strategia.PuntoCritico p) {
    if (p.unita().isEmpty() && p.classi().isEmpty()) {
      return Matches.unitIsNotInfrastructure();
    }
    Predicate<Unit> any = u -> p.unita().contains(u.getType().getName());
    for (final String c : p.classi()) {
      any = any.or(classe(c));
    }
    return any;
  }

  private static Predicate<Unit> classe(final String c) {
    return switch (c) {
      case "terra" -> Matches.unitIsLand().and(Matches.unitIsNotInfrastructure());
      case "aerei" -> Matches.unitIsAir();
      case "navi" -> Matches.unitIsSea();
      case "trasporti" -> Matches.unitIsSeaTransport();
      default -> u -> false;
    };
  }

  /**
   * The land territory of the enemy alliance with land units of {@code p.nazione()}, bordering the
   * alliance of {@code forces}, farthest from the nation's capital; ties: the fewest neighboring
   * land territories of the enemy alliance (the least supported), then the most units.
   */
  private static Optional<Territory> advancedStack(
      final GameState data, final Strategia.PuntoCritico p, final Forze forces) {
    if (p.nazione() == null) {
      return Optional.empty();
    }
    final GamePlayer nation = data.getPlayerList().getPlayerId(p.nazione());
    if (nation == null) {
      return Optional.empty();
    }
    final GameMap map = data.getMap();
    final List<Territory> capitals = TerritoryAttachment.getAllCapitals(nation, map);
    final Predicate<Unit> land =
        Matches.unitIsOwnedBy(nation)
            .and(Matches.unitIsLand())
            .and(Matches.unitIsNotInfrastructure());
    return map.getTerritories().stream()
        .filter(t -> !t.isWater() && forces.isEnemy(t.getOwner()) && t.anyUnitsMatch(land))
        .filter(
            t ->
                map.getNeighbors(t, Matches.territoryIsLand()).stream()
                    .anyMatch(n -> forces.isOwn(n.getOwner())))
        .max(
            Comparator.comparingInt(
                    (Territory t) ->
                        capitals.stream()
                            .mapToInt(c -> map.getDistance(c, t, Matches.territoryIsLand()))
                            .min()
                            .orElse(0))
                .thenComparingLong(
                    t ->
                        -map.getNeighbors(t, Matches.territoryIsLand()).stream()
                            .filter(n -> forces.isEnemy(n.getOwner()))
                            .count())
                .thenComparingInt(t -> t.getUnitCollection().countMatches(land))
                .thenComparing(Territory::getName, Comparator.reverseOrder()));
  }

  // ---------------------------------------------------------------------------- the race

  /**
   * The race to victory: victory cities held by each alliance, trend per round over the last
   * {@value #TENDENZA} rounds, rounds to victory ({@value #MAI} = the trend does not get there).
   */
  record Corsa(
      String alleanza,
      String nemica,
      Map<String, Integer> vc,
      Map<String, Integer> obiettivo,
      Map<String, Double> tendenza,
      Map<String, Integer> roundAllaVittoria,
      int corsaPari) {

    static Corsa of(
        final GameState data,
        final String alliance,
        final JsonArray storico,
        final int round,
        final Strategia.Parametri par) {
      final List<String> alliances = ProPlusSettings.configurableAlliances(data);
      final String enemy =
          alliances.stream().filter(a -> !a.equals(alliance)).findFirst().orElse("");
      final Map<String, Integer> vc = new LinkedHashMap<>();
      final Map<String, Integer> target = new LinkedHashMap<>();
      final Map<String, Double> trend = new LinkedHashMap<>();
      final Map<String, Integer> rounds = new LinkedHashMap<>();
      final int total = totalVc(data);
      for (final String a : List.of(alliance, enemy)) {
        if (a.isEmpty()) {
          continue;
        }
        final Strategia.Vittoria v = par.vittoria().get(a);
        final int now =
            v != null && !v.territori().isEmpty()
                ? held(data, a, v.territori())
                : victoryCities(data, a);
        vc.put(a, now);
        final int goal =
            v == null ? target(data, a, total) : v.territori().isEmpty() ? v.vc() : v.richiesti();
        target.put(a, goal);
        final Optional<int[]> past = pastVc(storico, a, round);
        final double t = past.map(p -> (double) (now - p[1]) / (round - p[0])).orElse(0.0);
        trend.put(a, Math.round(t * 100) / 100.0);
        rounds.put(
            a, now >= goal ? 0 : t > 0 ? (int) Math.min(MAI, Math.ceil((goal - now) / t)) : MAI);
      }
      return new Corsa(alliance, enemy, vc, target, trend, rounds, par.corsaPari());
    }

    int mie() {
      return roundAllaVittoria.getOrDefault(alleanza, MAI);
    }

    int loro() {
      return roundAllaVittoria.getOrDefault(nemica, MAI);
    }

    boolean pari() {
      return (mie() >= MAI && loro() >= MAI) || Math.abs(mie() - loro()) <= corsaPari;
    }

    boolean inVantaggio() {
      return !pari() && mie() < loro();
    }

    boolean nemicoPiuVeloce() {
      return !pari() && loro() < mie();
    }

    String esito() {
      return pari() ? "alla pari" : inVantaggio() ? "in vantaggio" : "nemico piu' veloce";
    }

    /** Eg "corsa alla pari (VC 5/8, Asse 6/8; round alla vittoria: mai / mai)". */
    String testo() {
      return String.format(
          Locale.ROOT,
          "corsa %s (VC %s %d/%d tendenza %+.2f, %s %d/%d tendenza %+.2f; round alla vittoria %s"
              + " / %s)",
          esito(),
          alleanza,
          vc.getOrDefault(alleanza, 0),
          obiettivo.getOrDefault(alleanza, 0),
          tendenza.getOrDefault(alleanza, 0.0),
          nemica,
          vc.getOrDefault(nemica, 0),
          obiettivo.getOrDefault(nemica, 0),
          tendenza.getOrDefault(nemica, 0.0),
          roundsText(mie()),
          roundsText(loro()));
    }

    JsonObject json() {
      final JsonObject json = new JsonObject();
      json.add("vc", toJson(vc));
      json.add("obiettivo_vc", toJson(obiettivo));
      final JsonObject t = new JsonObject();
      tendenza.forEach(t::addProperty);
      json.add("tendenza", t);
      json.add("round_alla_vittoria", toJson(roundAllaVittoria));
      json.addProperty("esito", esito());
      return json;
    }

    private static JsonObject toJson(final Map<String, Integer> map) {
      final JsonObject o = new JsonObject();
      map.forEach(o::addProperty);
      return o;
    }

    private static String roundsText(final int r) {
      return r >= MAI ? "mai" : String.valueOf(r);
    }
  }

  /**
   * {round, vc} of {@code alliance} in the history entry {@value #TENDENZA} rounds ago (or the
   * oldest).
   */
  private static Optional<int[]> pastVc(
      final JsonArray storico, final String alliance, final int round) {
    int[] found = null;
    for (final JsonElement e : storico) {
      final JsonObject entry = e.getAsJsonObject();
      if (!entry.has("round") || !entry.has("stato_strategico")) {
        continue;
      }
      final int r = entry.get("round").getAsInt();
      if (r >= round || r < round - TENDENZA) {
        continue;
      }
      final JsonObject vc =
          entry.getAsJsonObject("stato_strategico").getAsJsonObject("corsa").getAsJsonObject("vc");
      if (vc.has(alliance) && (found == null || r < found[0])) {
        found = new int[] {r, vc.get(alliance).getAsInt()};
      }
    }
    return Optional.ofNullable(found);
  }

  static int victoryCities(final GameState data, final String alliance) {
    final Collection<GamePlayer> players = data.getAllianceTracker().getPlayersInAlliance(alliance);
    int count = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      final int vc = TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0);
      if (vc > 0 && players.contains(t.getOwner())) {
        count += vc;
      }
    }
    return count;
  }

  /** How many of {@code names} the alliance holds. */
  static int held(final GameState data, final String alliance, final List<String> names) {
    final Collection<GamePlayer> players = data.getAllianceTracker().getPlayersInAlliance(alliance);
    return (int)
        names.stream()
            .map(data.getMap()::getTerritoryOrNull)
            .filter(t -> t != null && players.contains(t.getOwner()))
            .count();
  }

  private static int totalVc(final GameState data) {
    return data.getMap().getTerritories().stream()
        .mapToInt(
            t -> TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0))
        .sum();
  }

  /** VCs {@code alliance} needs: the map's property, else a majority of all VCs. */
  static int target(final GameState data, final String alliance, final int total) {
    for (final String key :
        List.of(alliance + " Honorable Victory VCs", alliance + " Total Victory VCs")) {
      final Object value = data.getProperties().get(key);
      if (value instanceof Integer i && i > 0) {
        return i;
      }
      if (value instanceof String s) {
        try {
          final int i = Integer.parseInt(s.trim());
          if (i > 0) {
            return i;
          }
        } catch (final NumberFormatException e) {
          // next key
        }
      }
    }
    return total / 2 + 1;
  }

  // ---------------------------------------------------------------------------- helpers

  /** The most probable enemy plan of a history entry and its probability ("" / 0 if none). */
  record Lettura(String piuProbabile, double probabilita) {
    static Lettura of(final JsonObject entry) {
      if (!entry.has("lettura")) {
        return new Lettura("", 0);
      }
      final JsonObject r = entry.getAsJsonObject("lettura");
      final String best = r.get("piu_probabile").getAsString();
      final JsonObject probs = r.getAsJsonObject("probabilita");
      return new Lettura(best, probs.has(best) ? probs.get(best).getAsDouble() : 0);
    }
  }

  /** The units of the alliance and of its enemy, with TUV costs. */
  static final class Forze {
    final List<GamePlayer> own;
    final List<GamePlayer> enemy;
    final Optional<String> enemyAlliance;
    private final GameState data;
    private final Map<String, IntegerMap<UnitType>> costs = new HashMap<>();

    Forze(final GameState data, final String alliance) {
      this.data = data;
      this.own = List.copyOf(data.getAllianceTracker().getPlayersInAlliance(alliance));
      this.enemyAlliance =
          ProPlusSettings.configurableAlliances(data).stream()
              .filter(a -> !a.equals(alliance))
              .findFirst();
      this.enemy =
          enemyAlliance
              .map(a -> List.copyOf(data.getAllianceTracker().getPlayersInAlliance(a)))
              .orElse(List.of());
    }

    boolean isOwn(final GamePlayer p) {
      return own.contains(p);
    }

    boolean isEnemy(final GamePlayer p) {
      return enemy.contains(p);
    }

    /** Whether land units of {@code players} are in {@code t} or within {@code distance} of it. */
    boolean landNear(final Territory t, final int distance, final List<GamePlayer> players) {
      final Predicate<Unit> land =
          Matches.unitIsLand()
              .and(Matches.unitIsNotInfrastructure())
              .and(u -> players.contains(u.getOwner()));
      if (t.anyUnitsMatch(land)) {
        return true;
      }
      return data.getMap().getNeighbors(t, distance).stream().anyMatch(n -> n.anyUnitsMatch(land));
    }

    /** Land and air TUV of {@code players} in {@code territories}. */
    double tuv(final Collection<Territory> territories, final List<GamePlayer> players) {
      double sum = 0;
      for (final Territory t : territories) {
        for (final GamePlayer p : players) {
          final List<Unit> units =
              t.getMatches(
                  Matches.unitIsOwnedBy(p)
                      .and(Matches.unitIsLand().or(Matches.unitIsAir()))
                      .and(Matches.unitIsNotInfrastructure()));
          if (!units.isEmpty()) {
            sum += TuvUtils.getTuv(units, cost(p));
          }
        }
      }
      return sum;
    }

    /** Whether own combat units are in {@code t} or within {@code distance} of it. */
    boolean canAttack(final Territory t, final int distance) {
      final Predicate<Unit> combat =
          Matches.unitIsNotInfrastructure()
              .and(u -> own.contains(u.getOwner()))
              .and(u -> u.getUnitAttachment().getAttack(u.getOwner()) > 0);
      return t.anyUnitsMatch(combat)
          || data.getMap().getNeighbors(t, distance).stream()
              .anyMatch(n -> n.anyUnitsMatch(combat));
    }

    private IntegerMap<UnitType> cost(final GamePlayer p) {
      return costs.computeIfAbsent(p.getName(), k -> new TuvCostsCalculator().getCostsForTuv(p));
    }
  }

  static String pct(final double p) {
    return Math.round(p * 100) + "%";
  }

  static double round2(final double v) {
    return Math.round(v * 100) / 100.0;
  }

  /**
   * Eg "Stato maggiore: postura RALLENTAMENTO perche' ...; piano 2 Italia prima (cambiato da 1
   * Germania prima perche' ...)" plus the counter-objectives.
   */
  public static String testo(final JsonObject s) {
    final StringBuilder sb =
        new StringBuilder("  Stato maggiore: postura ")
            .append(s.get("postura").getAsString())
            .append(" perche' ")
            .append(s.get("motivo").getAsString())
            .append("; piano ")
            .append(s.get("nome_piano").getAsString());
    final JsonArray changes = s.getAsJsonArray("cambi_piano");
    final JsonObject last =
        changes.isEmpty() ? null : changes.get(changes.size() - 1).getAsJsonObject();
    if (last != null && last.get("round").getAsInt() == s.get("round").getAsInt()) {
      sb.append(" (cambiato da ")
          .append(last.get("da").getAsString())
          .append(" perche' ")
          .append(last.get("motivo").getAsString())
          .append(')');
    } else if (last != null) {
      sb.append(" (in vigore dal round ").append(last.get("round").getAsInt()).append(')');
    }
    sb.append('\n');
    final JsonArray counter = s.getAsJsonArray("contro_obiettivi");
    if (!counter.isEmpty()) {
      final List<String> list = new ArrayList<>();
      counter.forEach(
          e -> {
            final JsonObject o = e.getAsJsonObject();
            list.add(
                o.get("territorio").getAsString()
                    + " (peso "
                    + Math.round(o.get("peso").getAsDouble())
                    + ", "
                    + o.get("punto_critico").getAsString()
                    + ")");
          });
      sb.append("  Contro-obiettivi ")
          .append(CONTRASTO.equals(s.get("postura").getAsString()) ? "principali" : "secondari")
          .append(": ")
          .append(String.join("; ", list))
          .append('\n');
    }
    if (s.has("cambio_piano")) {
      final JsonObject c = s.getAsJsonObject("cambio_piano");
      if (c.has("valutazioni")) {
        final List<String> list = new ArrayList<>();
        c.getAsJsonArray("valutazioni")
            .forEach(
                e -> {
                  final JsonObject v = e.getAsJsonObject();
                  list.add(
                      String.format(
                              Locale.ROOT,
                              "%s %.2f (tappe %.2f, forze %.2f, stato %.2f, contrasto %.2f)",
                              v.get("piano").getAsString(),
                              v.get("punteggio").getAsDouble(),
                              v.get("tappe_raggiungibili").getAsDouble(),
                              v.get("forze_vicine").getAsDouble(),
                              v.get("stato").getAsDouble(),
                              v.get("contrasto").getAsDouble())
                          + (v.has("contrasto_escluso")
                              ? " [contrasto escluso: "
                                  + v.get("contrasto_escluso").getAsString()
                                  + "]"
                              : ""));
                });
        sb.append("  Valutazione dei piani: ")
            .append(String.join("; ", list))
            .append(" -> ")
            .append(c.get("motivo").getAsString())
            .append('\n');
      }
    }
    final String tools =
        Tattiche.testo(s.has(Tattiche.KEY) ? s.getAsJsonObject(Tattiche.KEY) : null);
    if (!tools.endsWith("nessuna")) {
      sb.append(tools).append('\n');
    }
    return sb.toString();
  }
}
