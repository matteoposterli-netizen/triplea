package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.strategia.StatoMaggiore;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.EndRoundDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.delegate.data.BattleRecord;
import games.strategy.triplea.delegate.data.BattleRecords;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.Nullable;
import org.triplea.java.collections.IntegerMap;

/**
 * Watches a running game from the game thread, between two steps: one snapshot per finished round
 * (PU, income, TUV, territories per nation; victory cities and general staff per alliance; the
 * round's battles), declarations of war and fallen capitals as they happen. All it keeps is plain
 * JSON, so {@link Riepilogo} can write it from any thread (also from a shutdown hook).
 */
final class Monitor {
  /**
   * Allied nations whose landings in Europe are tracked (the French start on the continent, the
   * Soviets reach it by land). Names of WW2 Europe 1940.
   */
  static final Set<String> WESTERN_ALLIES = Set.of("British", "Americans");

  /** Land territories of continental Europe for "first Allied landing" (WW2 Europe 1940). */
  static final Set<String> EUROPE =
      Set.of(
          "Normandy Bordeaux",
          "France",
          "Southern France",
          "Holland Belgium",
          "Western Germany",
          "Germany",
          "Greater Southern Germany",
          "Denmark",
          "Norway",
          "Finland",
          "Northern Italy",
          "Southern Italy",
          "Sicily",
          "Sardinia",
          "Albania",
          "Greece",
          "Crete",
          "Yugoslavia",
          "Bulgaria",
          "Romania",
          "Slovakia Hungary",
          "Poland",
          "Baltic States");

  /**
   * Western Europe for the US landings and the bridgeheads of step OPERAZIONI ANFIBIE-BIS: France,
   * Benelux, Norway, Denmark, Germany (WW2 Europe 1940).
   */
  static final Set<String> WEST_EUROPE =
      Set.of(
          "Normandy Bordeaux",
          "France",
          "Southern France",
          "Holland Belgium",
          "Norway",
          "Denmark",
          "Western Germany",
          "Germany",
          "Greater Southern Germany");

  /** Step 3d-2: Soviet territories whose fall is tracked (WW2 Europe 1940). */
  static final List<String> KEY_TERRITORIES = List.of("Novgorod", "Russia", "Volgograd");

  private final GameData data;

  /** Step 3d-2: falls and recaptures of {@link #KEY_TERRITORIES}. */
  private final JsonArray keyTerritoryEvents = new JsonArray();

  /** Step 3d-2: key territories held by the enemy of their original owner now. */
  private final Set<String> keyFallen = new HashSet<>();

  /** Step 3d-2: records of the delay tools already read ("nation|round"). */
  private final Set<String> delayRecords = new HashSet<>();

  /** Step 3d-2: unit id -> "nation|tool" of the units placed by a delay tool, still on the map. */
  private final Map<String, String> delayUnits = new HashMap<>();

  /** Step 3d-2: round -> nation -> counter name -> value (placed, lost, sunk by the hunt). */
  private final Map<Integer, Map<String, Map<String, Integer>>> delayCounts = new TreeMap<>();

  /** Alliance -> its nations (configurable alliances only, eg. Axis and Allies). */
  private final Map<String, List<String>> alliances = new LinkedHashMap<>();

  /** Nation -> unit type -> cost (the TUV costs of the map). */
  private final Map<String, Map<String, Integer>> costs = new HashMap<>();

  private final Map<Integer, JsonObject> rounds = new TreeMap<>();
  private final Map<Integer, JsonArray> battles = new TreeMap<>();
  private final JsonArray wars = new JsonArray();
  private final JsonArray capitals = new JsonArray();
  private final Set<String> atWar = new HashSet<>();
  private final Set<String> fallen = new HashSet<>();
  @Nullable private JsonObject partialRound;
  private int lastRound;

  /** Step about to run (see {@link #beforeStep}): name and player. */
  private String runningStep = "";

  private String runningPlayer = "";

  /** Player of the previous step: a new one starts its turn. */
  private String turnPlayer = "";

  /** Territory -> owner when the current player's turn started. */
  private final Map<String, String> ownersAtTurnStart = new HashMap<>();

  /** Units already counted as landed (a unit lands once per turn). */
  private final Set<Unit> landedCounted = new HashSet<>();

  /** Real landings (unloaded from transports on enemy land, or land taken this turn). */
  private final JsonArray landings = new JsonArray();

  /** Step R2c: the forward base of the US shuttle (WW2 Europe 1940). */
  static final String FORWARD_BASE = "United Kingdom";

  /** Step R2c: round -> nation -> land units unloaded by transports in {@link #FORWARD_BASE}. */
  private final Map<Integer, Map<String, Integer>> broughtToBase = new TreeMap<>();

  /**
   * Step R2c: the units in {@link #FORWARD_BASE} and the land next to it when the player's turn
   * started, and those already counted: a land unit of the player that appears there in a move step
   * came by sea (the engine clears the transports' "unloaded" list at the end of the non-combat
   * move, so it can't be read).
   */
  private final Set<Unit> broughtCounted = new HashSet<>();

  /** Round -> nation -> transports that did nothing in the nation's turn (empty, not moved). */
  private final Map<Integer, Map<String, Integer>> idleTransports = new TreeMap<>();

  /** Step 3d-1-bis: sea transports on the map after the last step, with their owner. */
  private final Map<Unit, String> knownTransports = new HashMap<>();

  /** Round -> nation -> transports lost (on the map after a step, gone after the next). */
  private final Map<Integer, Map<String, Integer>> lostTransports = new TreeMap<>();

  /** Round -> nation -> TUV of the transports lost. */
  private final Map<Integer, Map<String, Integer>> lostTransportsTuv = new TreeMap<>();

  Monitor(final GameData data) {
    this.data = data;
    // reverse name order: Axis before Allies, as in the plans (Asse / Alleati)
    final List<String> names = new ArrayList<>(ProPlusSettings.configurableAlliances(data));
    names.sort(Comparator.reverseOrder());
    for (final String alliance : names) {
      alliances.put(
          alliance,
          data.getAllianceTracker().getPlayersInAlliance(alliance).stream()
              .map(GamePlayer::getName)
              .sorted()
              .toList());
    }
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      final IntegerMap<UnitType> c = new TuvCostsCalculator().getCostsForTuv(p);
      final Map<String, Integer> byName = new HashMap<>();
      c.keySet().forEach(t -> byName.put(t.getName(), c.getInt(t)));
      costs.put(p.getName(), byName);
    }
    atWar.addAll(currentWars());
    fallen.addAll(currentFallenCapitals().keySet());
    lastRound = data.getSequence().getRound();
    knownTransports.putAll(currentTransports());
    keyFallen.addAll(currentKeyFallen());
  }

  Map<String, List<String>> alliances() {
    return alliances;
  }

  Map<String, Map<String, Integer>> costs() {
    return costs;
  }

  /** Called before every game step: notes the step and, at a new player's turn, the owners. */
  synchronized void beforeStep() {
    final GameStep step = data.getSequence().getStep();
    runningStep = step.getName();
    runningPlayer = Optional.ofNullable(step.getPlayerId()).map(GamePlayer::getName).orElse("-");
    if (!runningPlayer.equals(turnPlayer)) {
      turnPlayer = runningPlayer;
      ownersAtTurnStart.clear();
      landedCounted.clear();
      broughtCounted.clear();
      // the units there and in the land next to it (Scotland) at the start of the turn
      Optional.ofNullable(data.getMap().getTerritoryOrNull(FORWARD_BASE))
          .ifPresent(
              t -> {
                broughtCounted.addAll(t.getUnits());
                data.getMap()
                    .getNeighbors(t, Matches.territoryIsLand())
                    .forEach(n -> broughtCounted.addAll(n.getUnits()));
              });
      for (final Territory t : data.getMap().getTerritories()) {
        if (!t.isWater()) {
          ownersAtTurnStart.put(t.getName(), t.getOwner().getName());
        }
      }
    }
  }

  /** Called after every game step. */
  synchronized void afterStep() {
    if (GameStep.isNonCombatMoveStepName(runningStep)
        || GameStep.isCombatMoveStepName(runningStep)) {
      recordLandings(lastRound);
    }
    if (GameStep.isNonCombatMoveStepName(runningStep)) {
      recordIdleTransports(lastRound);
    }
    recordLostTransports(lastRound);
    recordDelayTools(lastRound);
    runningStep = "";
    final int round = data.getSequence().getRound();
    final GameStep step = data.getSequence().getStep();
    final String current =
        Optional.ofNullable(step.getPlayerId()).map(GamePlayer::getName).orElse("-");
    final int eventRound = round == lastRound ? round : lastRound;
    for (final String pair : currentWars()) {
      if (atWar.add(pair)) {
        final String[] p = pair.split("\\|");
        final JsonObject w = new JsonObject();
        w.addProperty("round", eventRound);
        w.addProperty("nazione_di_turno", current);
        w.addProperty("tra", p[0] + " - " + p[1]);
        w.addProperty("alleanze", allianceOf(p[0]) + " - " + allianceOf(p[1]));
        wars.add(w);
      }
    }
    final Map<String, String[]> nowFallen = currentFallenCapitals();
    for (final Map.Entry<String, String[]> e : nowFallen.entrySet()) {
      if (fallen.add(e.getKey())) {
        capitals.add(capitalEvent(eventRound, e.getKey(), e.getValue(), "caduta"));
      }
    }
    for (final String t : new ArrayList<>(fallen)) {
      if (!nowFallen.containsKey(t)) {
        fallen.remove(t);
        final Territory territory = data.getMap().getTerritoryOrNull(t);
        capitals.add(
            capitalEvent(
                eventRound,
                t,
                new String[] {
                  capitalOwner(territory), territory == null ? "?" : territory.getOwner().getName()
                },
                "liberata"));
      }
    }
    final Set<String> nowKeyFallen = currentKeyFallen();
    for (final String t : KEY_TERRITORIES) {
      if (nowKeyFallen.contains(t) && keyFallen.add(t)) {
        keyTerritoryEvents.add(keyEvent(eventRound, t, "caduta"));
      } else if (!nowKeyFallen.contains(t) && keyFallen.remove(t)) {
        keyTerritoryEvents.add(keyEvent(eventRound, t, "ripresa"));
      }
    }
    if (round != lastRound) {
      snapshot(lastRound, true);
      lastRound = round;
    }
  }

  /** Round in progress (not finished yet). */
  synchronized int currentRound() {
    return data.getSequence().getRound();
  }

  /** Snapshot of the round in progress when the game stops before its end. */
  synchronized void snapshotPartialRound() {
    if (!rounds.containsKey(lastRound)) {
      snapshot(lastRound, false);
    }
  }

  /** Alliance whose players won (the end-round delegate's winners), if the game was won. */
  synchronized Optional<String> winner() {
    for (final var delegate : data.getDelegates()) {
      if (delegate instanceof EndRoundDelegate end
          && end.getWinners() != null
          && !end.getWinners().isEmpty()) {
        return Optional.of(allianceOf(end.getWinners().iterator().next().getName()));
      }
    }
    return Optional.empty();
  }

  synchronized JsonObject toJson() {
    final JsonObject json = new JsonObject();
    final JsonArray r = new JsonArray();
    rounds.values().forEach(r::add);
    if (partialRound != null) {
      r.add(partialRound);
    }
    json.add("round", r);
    json.add("dichiarazioni_guerra", wars.deepCopy());
    json.add("capitali", capitals.deepCopy());
    final JsonArray b = new JsonArray();
    battles.values().forEach(list -> list.forEach(b::add));
    json.add("battaglie", b);
    json.add("sbarchi", landings.deepCopy());
    json.add("territori_chiave", keyTerritoryEvents.deepCopy());
    json.add("operazioni_congiunte", jointOperations());
    json.add("controllo_nudo", nudoCheck());
    // step PROVALLM: calls, tokens, cost and fallbacks of the LLM at the operations' decisions
    json.add(
        "operazioni_llm_uso",
        games.strategy.triplea.ai.proplus.DecisoreOperazione.uso(data.getProperties()));
    // step STRATEGA LLM: portfolios, operations with their outcome and decisions, cost
    json.add("stratega_llm", games.strategy.triplea.ai.proplus.Stratega.riepilogoPartita(data));
    return json;
  }

  /** Step PROVA DI VALORE: owner of each joint operation's target now (id → nation). */
  private JsonObject jointTargets() {
    final JsonObject json = new JsonObject();
    for (final var d : games.strategy.triplea.ai.proplus.OperazioneCongiunta.definizioni()) {
      final Territory t = data.getMap().getTerritoryOrNull(d.bersaglio());
      if (t != null) {
        json.addProperty(d.id(), name(t.getOwner()));
      }
    }
    return json;
  }

  /**
   * Step PROVA2: owner of every territory of the joint operations now (first and final target, eg
   * Normandy Bordeaux and France), territory → nation.
   */
  private JsonObject jointTerritories() {
    final JsonObject json = new JsonObject();
    for (final var d : games.strategy.triplea.ai.proplus.OperazioneCongiunta.definizioni()) {
      for (final String name : List.of(d.bersaglio(), d.bersaglioFinale())) {
        final Territory t = name.isEmpty() ? null : data.getMap().getTerritoryOrNull(name);
        if (t != null) {
          json.addProperty(name, name(t.getOwner()));
        }
      }
    }
    return json;
  }

  /** Step PROVA DI VALORE: the saved state of each joint operation turned on in this game. */
  private JsonObject jointOperations() {
    final JsonObject json = new JsonObject();
    for (final var d : games.strategy.triplea.ai.proplus.OperazioneCongiunta.definizioni()) {
      final Object state =
          data.getProperties()
              .get(games.strategy.triplea.ai.proplus.OperazioneCongiunta.STATE_PREFIX + d.id());
      if (state instanceof String text && !text.isBlank()) {
        try {
          json.add(d.id(), com.google.gson.JsonParser.parseString(text));
        } catch (final RuntimeException e) {
          json.addProperty(d.id(), "illeggibile: " + e);
        }
      }
    }
    return json;
  }

  /** Step PROVA DI VALORE: the counts of the check of Pro+ against Hard, per nation. */
  private JsonObject nudoCheck() {
    final JsonObject json = new JsonObject();
    for (final List<String> members : alliances.values()) {
      for (final String nation : members) {
        final Object value =
            data.getProperties()
                .get(games.strategy.triplea.ai.proplus.ControlloTuning.PROPERTY_PREFIX + nation);
        if (value instanceof String text && !text.isBlank()) {
          try {
            json.add(nation, com.google.gson.JsonParser.parseString(text));
          } catch (final RuntimeException e) {
            // skipped
          }
        }
      }
    }
    return json;
  }

  // ------------------------------------------------------------- delay tools, key territories

  /**
   * Step 3d-2: reads each nation's record of the delay tools ({@code "Pro+ ritardo: <nation>"},
   * written in its non-combat move): new block units and ships are counted as placed and followed
   * until they leave the map (lost); hunted transports already gone when the record is written (the
   * record comes after the hunter's battles) count as sunk by the hunt.
   */
  private void recordDelayTools(final int round) {
    final Set<String> onMap = new HashSet<>();
    data.getMap().getTerritories().forEach(t -> t.getUnits().forEach(u -> onMap.add(id(u))));
    for (final List<String> members : alliances.values()) {
      for (final String nation : members) {
        final Object value = data.getProperties().get("Pro+ ritardo: " + nation);
        if (!(value instanceof String text)) {
          continue;
        }
        final JsonObject rec;
        try {
          rec = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
        } catch (final RuntimeException e) {
          continue;
        }
        if (!rec.has("round") || !delayRecords.add(nation + "|" + rec.get("round").getAsInt())) {
          continue;
        }
        final Map<String, Integer> c =
            delayCounts
                .computeIfAbsent(round, k -> new TreeMap<>())
                .computeIfAbsent(nation, k -> new TreeMap<>());
        for (final String tool : List.of("blocchi", "navi", "ritirata")) {
          if (!rec.has(tool)) {
            continue;
          }
          for (final JsonElement e : rec.getAsJsonArray(tool)) {
            c.merge(tool + "_messi", 1, Integer::sum);
            if (!"ritirata".equals(tool)) {
              delayUnits.put(e.getAsString(), nation + "|" + tool);
            }
          }
        }
        // strategic contract (decision of Matteo): infantry left where it was is no block
        if (rec.has("blocchi_lasciati")) {
          c.merge("blocchi_lasciati", rec.getAsJsonArray("blocchi_lasciati").size(), Integer::sum);
        }
        if (rec.has("caccia")) {
          for (final JsonElement e : rec.getAsJsonArray("caccia")) {
            c.merge("zone_caccia", 1, Integer::sum);
            for (final JsonElement t : e.getAsJsonObject().getAsJsonArray("trasporti")) {
              if (!onMap.contains(t.getAsString())) {
                c.merge("affondati_caccia", 1, Integer::sum);
              }
            }
          }
        }
      }
    }
    for (final var it = delayUnits.entrySet().iterator(); it.hasNext(); ) {
      final Map.Entry<String, String> e = it.next();
      if (!onMap.contains(e.getKey())) {
        final String[] p = e.getValue().split("\\|");
        delayCounts
            .computeIfAbsent(round, k -> new TreeMap<>())
            .computeIfAbsent(p[0], k -> new TreeMap<>())
            .merge(p[1] + "_persi", 1, Integer::sum);
        it.remove();
      }
    }
  }

  private static String id(final Unit u) {
    return u.getId().toString();
  }

  /** {@link #KEY_TERRITORIES} owned by an enemy (not allied) of their original owner. */
  private Set<String> currentKeyFallen() {
    final Set<String> fallenNow = new HashSet<>();
    for (final String name : KEY_TERRITORIES) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t == null) {
        continue;
      }
      final Optional<GamePlayer> original = OriginalOwnerTracker.getOriginalOwner(t);
      if (original.isPresent()
          && !t.getOwner().equals(original.get())
          && !data.getRelationshipTracker().isAllied(original.get(), t.getOwner())) {
        fallenNow.add(name);
      }
    }
    return fallenNow;
  }

  private JsonObject keyEvent(final int round, final String territory, final String event) {
    final JsonObject o = new JsonObject();
    o.addProperty("round", round);
    o.addProperty("evento", event);
    o.addProperty("territorio", territory);
    o.addProperty("proprietario", data.getMap().getTerritoryOrNull(territory).getOwner().getName());
    return o;
  }

  // ----------------------------------------------------------------- landings, idle transports

  /**
   * Land units of the player in turn that a transport unloaded in this step, on land that was an
   * enemy's (at war with them) when the turn started: in the combat move an amphibious assault, in
   * the non-combat move a landing on land taken this turn. Unloading on own or allied land is not a
   * landing.
   */
  private void recordLandings(final int round) {
    final GamePlayer player = data.getPlayerList().getPlayerId(runningPlayer);
    if (player == null) {
      return;
    }
    final Map<Unit, Territory> location = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        t.getUnits().forEach(u -> location.put(u, t));
      }
    }
    final Territory base = data.getMap().getTerritoryOrNull(FORWARD_BASE);
    if (base != null) {
      for (final Unit u :
          base.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsLand())
                  .and(Matches.unitIsNotInfrastructure()))) {
        if (broughtCounted.add(u)) {
          broughtToBase
              .computeIfAbsent(round, k -> new TreeMap<>())
              .merge(runningPlayer, 1, Integer::sum);
        }
      }
    }
    final Map<String, Integer> byTerritory = new TreeMap<>();
    final Map<String, String> phase = new HashMap<>();
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit transport : z.getMatches(Matches.unitIsSeaTransport())) {
        for (final Unit u : transport.getUnloaded()) {
          final Territory at = location.get(u);
          if (!u.isOwnedBy(player) || at == null || landedCounted.contains(u)) {
            continue;
          }
          final GamePlayer ownerAtStart =
              data.getPlayerList().getPlayerId(ownersAtTurnStart.getOrDefault(at.getName(), ""));
          if (ownerAtStart == null
              || !data.getRelationshipTracker().isAtWar(player, ownerAtStart)) {
            continue;
          }
          landedCounted.add(u);
          byTerritory.merge(at.getName(), 1, Integer::sum);
          phase.put(at.getName(), u.getWasUnloadedInCombatPhase() ? "combattimento" : "movimento");
        }
      }
    }
    byTerritory.forEach(
        (territory, units) -> {
          final JsonObject l = new JsonObject();
          l.addProperty("round", round);
          l.addProperty("nazione", runningPlayer);
          l.addProperty("alleanza", allianceOf(runningPlayer));
          l.addProperty("territorio", territory);
          l.addProperty("unita", units);
          l.addProperty("fase", phase.get(territory));
          landings.add(l);
        });
  }

  /** Step 3d-1-bis: the transports gone since the last step, per owner, in {@code round}. */
  private void recordLostTransports(final int round) {
    final Map<Unit, String> now = currentTransports();
    knownTransports.forEach(
        (u, owner) -> {
          if (!now.containsKey(u)) {
            lostTransports
                .computeIfAbsent(round, k -> new TreeMap<>())
                .merge(owner, 1, Integer::sum);
            lostTransportsTuv
                .computeIfAbsent(round, k -> new TreeMap<>())
                .merge(
                    owner,
                    costs.getOrDefault(owner, Map.of()).getOrDefault(u.getType().getName(), 0),
                    Integer::sum);
          }
        });
    knownTransports.clear();
    knownTransports.putAll(now);
  }

  private Map<Unit, String> currentTransports() {
    final Map<Unit, String> map = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getMatches(Matches.unitIsSeaTransport())) {
        map.put(u, u.getOwner().getName());
      }
    }
    return map;
  }

  /**
   * Step 3d-1-bis: TUV swing (defender's TUV lost - attacker's) of the battles of {@code round} on
   * land where the attacker landed real troops in its combat move, per attacking nation.
   */
  private Map<String, Integer> landingTuv(final int round, final JsonArray roundBattles) {
    final Set<String> landed = new HashSet<>();
    for (final JsonElement e : landings) {
      final JsonObject l = e.getAsJsonObject();
      if (l.get("round").getAsInt() == round
          && "combattimento".equals(l.get("fase").getAsString())) {
        landed.add(l.get("nazione").getAsString() + "|" + l.get("territorio").getAsString());
      }
    }
    final Map<String, Integer> tuv = new HashMap<>();
    for (final JsonElement e : roundBattles) {
      final JsonObject b = e.getAsJsonObject();
      final String attacker = b.get("attaccante").getAsString();
      if (landed.contains(attacker + "|" + b.get("territorio").getAsString())) {
        tuv.merge(
            attacker,
            b.get("tuv_perso_difensore").getAsInt() - b.get("tuv_perso_attaccante").getAsInt(),
            Integer::sum);
      }
    }
    return tuv;
  }

  /** Transports of the player in turn that end its non-combat move empty and unmoved. */
  private void recordIdleTransports(final int round) {
    final GamePlayer player = data.getPlayerList().getPlayerId(runningPlayer);
    if (player == null || allianceOf(runningPlayer).equals(runningPlayer)) {
      return;
    }
    int idle = 0;
    for (final Territory z : data.getMap().getTerritories()) {
      for (final Unit t :
          z.getMatches(
              Matches.unitIsSeaTransportButNotCombatSeaTransport()
                  .and(Matches.unitIsOwnedBy(player)))) {
        if (t.getAlreadyMoved().signum() == 0 && !t.isTransporting() && t.getUnloaded().isEmpty()) {
          idle++;
        }
      }
    }
    idleTransports
        .computeIfAbsent(round, k -> new TreeMap<>())
        .merge(runningPlayer, idle, Integer::sum);
  }

  // ------------------------------------------------------------------------------- snapshot

  private void snapshot(final int round, final boolean complete) {
    final JsonObject json = new JsonObject();
    json.addProperty("round", round);
    json.addProperty("completo", complete);
    final JsonObject nations = new JsonObject();
    final Map<String, Integer> tuv = tuvByNation();
    final Map<String, Integer> land = landByNation();
    final Map<String, Integer> transports = transportsByNation();
    final JsonArray roundBattles = battlesOf(round);
    final Map<String, Integer> landingTuv = landingTuv(round, roundBattles);
    for (final List<String> members : alliances.values()) {
      for (final String name : members) {
        final GamePlayer p = data.getPlayerList().getPlayerId(name);
        final JsonObject n = new JsonObject();
        n.addProperty("pu", p.getResources().getQuantity(Constants.PUS));
        n.addProperty("reddito", (int) Math.round(ProUtils.getPlayerProduction(p, data)));
        n.addProperty("tuv", tuv.getOrDefault(name, 0));
        n.addProperty("territori", land.getOrDefault(name, 0));
        n.addProperty("trasporti", transports.getOrDefault(name, 0));
        n.addProperty(
            "trasporti_inattivi",
            idleTransports.getOrDefault(round, Map.of()).getOrDefault(name, 0));
        n.addProperty(
            "portate_regno_unito",
            broughtToBase.getOrDefault(round, Map.of()).getOrDefault(name, 0));
        n.addProperty(
            "trasporti_persi", lostTransports.getOrDefault(round, Map.of()).getOrDefault(name, 0));
        n.addProperty(
            "tuv_trasporti_persi",
            lostTransportsTuv.getOrDefault(round, Map.of()).getOrDefault(name, 0));
        n.addProperty("tuv_sbarchi", landingTuv.getOrDefault(name, 0));
        // step 3d-2: delay tools of the round
        final Map<String, Integer> delay =
            delayCounts.getOrDefault(round, Map.of()).getOrDefault(name, Map.of());
        if (!delay.isEmpty()) {
          final JsonObject d = new JsonObject();
          delay.forEach(d::addProperty);
          n.add("ritardo", d);
        }
        nations.add(name, n);
      }
    }
    json.add("nazioni", nations);
    json.add("vc", victoryCities());
    json.add("bersagli_operazioni", jointTargets());
    json.add("territori_operazioni", jointTerritories());
    json.add("stato_maggiore", generalStaff(round));
    json.add("alleati_in_europa", westernAlliesInEurope());
    json.add("ovest_alleato", westEuropeHeldByWesternAllies());
    final JsonObject landed = new JsonObject();
    alliances.keySet().forEach(a -> landed.addProperty(a, 0));
    for (final JsonElement e : landings) {
      final JsonObject l = e.getAsJsonObject();
      if (l.get("round").getAsInt() == round && landed.has(l.get("alleanza").getAsString())) {
        final String a = l.get("alleanza").getAsString();
        landed.addProperty(a, landed.get(a).getAsInt() + l.get("unita").getAsInt());
      }
    }
    json.add("unita_sbarcate", landed);
    if (complete) {
      rounds.put(round, json);
      partialRound = null;
    } else {
      partialRound = json;
    }
    battles.put(round, roundBattles);
  }

  private Map<String, Integer> tuvByNation() {
    final Map<String, Integer> tuv = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        final String owner = u.getOwner().getName();
        tuv.merge(
            owner,
            costs.getOrDefault(owner, Map.of()).getOrDefault(u.getType().getName(), 0),
            Integer::sum);
      }
    }
    return tuv;
  }

  private Map<String, Integer> transportsByNation() {
    final Map<String, Integer> transports = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getMatches(Matches.unitIsSeaTransport())) {
        transports.merge(u.getOwner().getName(), 1, Integer::sum);
      }
    }
    return transports;
  }

  /**
   * European territories, not originally of the Allies, with British/American land units now:
   * territory -> nations (moving into a French territory is not a landing).
   */
  private JsonObject westernAlliesInEurope() {
    final JsonObject json = new JsonObject();
    for (final String name : new TreeSet<>(EUROPE)) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t == null
          || OriginalOwnerTracker.getOriginalOwner(t)
              .map(o -> allianceOf(o.getName()).equals(allianceOf("British")))
              .orElse(false)) {
        continue;
      }
      final Set<String> nations = new TreeSet<>();
      for (final Unit u : t.getMatches(Matches.unitIsLand())) {
        if (WESTERN_ALLIES.contains(u.getOwner().getName())) {
          nations.add(u.getOwner().getName());
        }
      }
      if (!nations.isEmpty()) {
        final JsonArray list = new JsonArray();
        nations.forEach(list::add);
        json.add(name, list);
      }
    }
    return json;
  }

  /** Territories of {@link #WEST_EUROPE} owned by the alliance of the British now. */
  private JsonArray westEuropeHeldByWesternAllies() {
    final JsonArray list = new JsonArray();
    final String allies = allianceOf("British");
    for (final String name : new TreeSet<>(WEST_EUROPE)) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t != null && allianceOf(t.getOwner().getName()).equals(allies)) {
        list.add(name);
      }
    }
    return list;
  }

  private Map<String, Integer> landByNation() {
    final Map<String, Integer> land = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        land.merge(t.getOwner().getName(), 1, Integer::sum);
      }
    }
    return land;
  }

  private JsonObject victoryCities() {
    final JsonObject json = new JsonObject();
    for (final String alliance : alliances.keySet()) {
      final JsonObject a = new JsonObject();
      final Set<String> names = new TreeSet<>();
      int count = 0;
      for (final Territory t : data.getMap().getTerritories()) {
        final int vc =
            TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0);
        if (vc > 0 && alliance.equals(allianceOf(t.getOwner().getName()))) {
          count += vc;
          names.add(t.getName());
        }
      }
      a.addProperty("numero", count);
      final JsonArray list = new JsonArray();
      names.forEach(list::add);
      a.add("citta", list);
      json.add(alliance, a);
    }
    return json;
  }

  /** Plan, progress and reading of the enemy of each alliance (Pro+ only, from its history). */
  private JsonObject generalStaff(final int round) {
    final JsonObject json = new JsonObject();
    for (final String alliance : alliances.keySet()) {
      final Optional<StatoMaggiore.Stato> state =
          StatoMaggiore.getState(data.getProperties(), alliance);
      if (state.isEmpty()) {
        continue;
      }
      final JsonObject a = new JsonObject();
      a.addProperty("piano", state.get().piano());
      for (final JsonElement e : state.get().storico()) {
        final JsonObject entry = e.getAsJsonObject();
        if (entry.has("round") && entry.get("round").getAsInt() == round) {
          if (entry.has("piano")) {
            a.addProperty("piano", entry.get("piano").getAsString());
          }
          if (entry.has("stato_strategico")) {
            // step 3d-1: posture and plan change of the round
            final JsonObject s = entry.getAsJsonObject("stato_strategico");
            a.addProperty("postura", s.get("postura").getAsString());
            a.addProperty("motivo_postura", s.get("motivo").getAsString());
            final JsonArray changes = s.getAsJsonArray("cambi_piano");
            if (!changes.isEmpty()) {
              final JsonObject last = changes.get(changes.size() - 1).getAsJsonObject();
              if (last.get("round").getAsInt() == round) {
                a.add("cambio", last.deepCopy());
              }
            }
            final JsonArray counter = new JsonArray();
            s.getAsJsonArray("contro_obiettivi")
                .forEach(c -> counter.add(c.getAsJsonObject().get("territorio").getAsString()));
            a.add("contro_obiettivi", counter);
          }
          if (entry.has("avanzamento")) {
            a.addProperty("stato", entry.getAsJsonObject("avanzamento").get("stato").getAsString());
          }
          if (entry.has("lettura")) {
            a.add("lettura", reading(entry.getAsJsonObject("lettura")));
          }
        }
      }
      json.add(alliance, a);
    }
    return json;
  }

  private static JsonObject reading(final JsonObject lettura) {
    final JsonObject r = new JsonObject();
    r.addProperty("piu_probabile", lettura.get("piu_probabile").getAsString());
    r.add("probabilita", lettura.getAsJsonObject("probabilita"));
    if (lettura.has("piano_reale")) {
      final String real = lettura.get("piano_reale").getAsString();
      r.addProperty("piano_reale", real);
      final JsonObject probs = lettura.getAsJsonObject("probabilita");
      r.addProperty("prob_piano_reale", probs.has(real) ? probs.get(real).getAsDouble() : 0);
      r.addProperty("corretta", lettura.get("lettura_corretta").getAsBoolean());
    }
    return r;
  }

  private JsonArray battlesOf(final int round) {
    final JsonArray list = new JsonArray();
    final var all = data.getBattleRecordsList();
    if (all == null) {
      return list;
    }
    final BattleRecords records = all.getBattleRecordsMapCopy().get(round);
    if (records == null) {
      return list;
    }
    for (final BattleRecord r : BattleRecords.getAllRecords(records)) {
      final JsonObject b = new JsonObject();
      b.addProperty("round", round);
      final Territory site = field(r, "battleSite");
      b.addProperty("territorio", site == null ? "?" : site.getName());
      b.addProperty("attaccante", name(field(r, "attacker")));
      b.addProperty("difensore", name(field(r, "defender")));
      final int attLost = number(field(r, "attackerLostTuv"));
      final int defLost = number(field(r, "defenderLostTuv"));
      b.addProperty("tuv_perso_attaccante", attLost);
      b.addProperty("tuv_perso_difensore", defLost);
      b.addProperty("tuv_totale", attLost + defLost);
      final Object result = field(r, "battleResultDescription");
      b.addProperty("risultato", result == null ? "?" : result.toString());
      final Object type = field(r, "battleType");
      b.addProperty("tipo", type == null ? "?" : type.toString());
      list.add(b);
    }
    return list;
  }

  /** The battle record's data has no public getters: read the fields. */
  @SuppressWarnings("unchecked")
  @Nullable
  private static <T> T field(final BattleRecord record, final String name) {
    try {
      final Field f = BattleRecord.class.getDeclaredField(name);
      f.setAccessible(true);
      return (T) f.get(record);
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private static String name(@Nullable final GamePlayer p) {
    return p == null ? "?" : p.getName();
  }

  private static int number(@Nullable final Integer n) {
    return n == null ? 0 : n;
  }

  // ------------------------------------------------------------------------- wars, capitals

  private Set<String> currentWars() {
    final Set<String> pairs = new HashSet<>();
    final List<GamePlayer> players =
        data.getPlayerList().getPlayers().stream().filter(p -> !p.isNull()).toList();
    for (int i = 0; i < players.size(); i++) {
      for (int j = i + 1; j < players.size(); j++) {
        final GamePlayer a = players.get(i);
        final GamePlayer b = players.get(j);
        if (data.getRelationshipTracker().isAtWar(a, b)) {
          pairs.add(a.getName() + "|" + b.getName());
        }
      }
    }
    return pairs;
  }

  /** Capital territory -> {nation of the capital, enemy owner} for capitals held by an enemy. */
  private Map<String, String[]> currentFallenCapitals() {
    final Map<String, String[]> map = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      final String capitalOf = capitalOwner(t);
      if ("-".equals(capitalOf)) {
        continue;
      }
      final GamePlayer nation = data.getPlayerList().getPlayerId(capitalOf);
      if (nation != null
          && !t.getOwner().equals(nation)
          && !data.getRelationshipTracker().isAllied(nation, t.getOwner())) {
        map.put(t.getName(), new String[] {capitalOf, t.getOwner().getName()});
      }
    }
    return map;
  }

  private static String capitalOwner(@Nullable final Territory t) {
    return t == null
        ? "-"
        : TerritoryAttachment.get(t).flatMap(TerritoryAttachment::getCapital).orElse("-");
  }

  private static JsonObject capitalEvent(
      final int round, final String territory, final String[] who, final String event) {
    final JsonObject c = new JsonObject();
    c.addProperty("round", round);
    c.addProperty("evento", event);
    c.addProperty("territorio", territory);
    c.addProperty("capitale_di", who[0]);
    c.addProperty("proprietario", who[1]);
    return c;
  }

  String allianceOf(final String nation) {
    for (final Map.Entry<String, List<String>> e : alliances.entrySet()) {
      if (e.getValue().contains(nation)) {
        return e.getKey();
      }
    }
    return nation;
  }
}
