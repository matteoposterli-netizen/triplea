package org.triplea.proplus.autoplay;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.proplus.ConfigR4;
import games.strategy.triplea.attachments.AbstractConditionsAttachment;
import games.strategy.triplea.attachments.AbstractTriggerAttachment;
import games.strategy.triplea.attachments.ICondition;
import games.strategy.triplea.attachments.RulesAttachment;
import games.strategy.triplea.attachments.TriggerAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.ui.ObjectiveDummyDelegateBridge;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Step R4: what the tables of "Partite complete" need, for every configuration (Hard included),
 * read from the board at the end of each round: owners of the key cities and factories, the army
 * per nation, Moscow's factory (damage, units placed there), units the Axis placed in the captured
 * Russian factories, the Axis' territories of Africa and east of Egypt, the national objectives
 * active and their PUs, the Italian land units in the south of Russia.
 */
final class MonitorR4 {
  /** Cities and factories whose owner is recorded every round (key = label of the tables). */
  static final Map<String, String> CITTA = citta();

  private static Map<String, String> citta() {
    final Map<String, String> m = new LinkedHashMap<>();
    m.put("Mosca", "Russia");
    m.put("Stalingrado", "Volgograd");
    m.put("Leningrado", "Novgorod");
    m.put("Ucraina", "Ukraine");
    m.put("Egitto", "Egypt");
    m.put("Parigi", "France");
    m.put("Roma", "Southern Italy");
    m.put("Berlino", "Germany");
    m.put("Londra", "United Kingdom");
    m.put("Gibilterra", "Gibraltar");
    return java.util.Collections.unmodifiableMap(m);
  }

  /** South of Russia: where Italian land units "passed to the south of Russia" are counted. */
  static final List<String> SUD_RUSSIA =
      List.of("Caucasus", "Rostov", "Volgograd", "Kazakhstan", "Turkmenistan");

  private final GameData data;
  private final Function<String, String> allianceOf;

  /** Place step: unit ids in the Russian factory territories when it started. */
  private final Map<String, Set<String>> beforePlace = new HashMap<>();

  /** Round → units placed by the Russians in Moscow. */
  private final Map<Integer, Integer> moscowPlaced = new TreeMap<>();

  /** Round → units placed by the Axis in the captured Russian factories. */
  private final Map<Integer, Integer> capturedPlaced = new TreeMap<>();

  MonitorR4(final GameData data, final Function<String, String> allianceOf) {
    this.data = data;
    this.allianceOf = allianceOf;
  }

  private List<String> fabbriche() {
    final List<String> out = new java.util.ArrayList<>(ConfigR4.get().fabbricheRusse());
    out.add(ConfigR4.get().capitaleRussa());
    return out;
  }

  void beforeStep(final String stepName) {
    if (!GameStep.isPlaceStepName(stepName)) {
      return;
    }
    beforePlace.clear();
    for (final String n : fabbriche()) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null) {
        final Set<String> ids = new HashSet<>();
        t.getUnitCollection().forEach(u -> ids.add(u.getId().toString()));
        beforePlace.put(n, ids);
      }
    }
  }

  void afterStep(final String stepName, final String player, final int round) {
    if (!GameStep.isPlaceStepName(stepName) || beforePlace.isEmpty()) {
      return;
    }
    for (final String n : fabbriche()) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      final Set<String> before = beforePlace.getOrDefault(n, Set.of());
      if (t == null) {
        continue;
      }
      final long placed =
          t.getUnitCollection().stream()
              .filter(u -> u.getOwner().getName().equals(player))
              .filter(u -> !before.contains(u.getId().toString()))
              .filter(Matches.unitIsNotInfrastructure())
              .count();
      if (placed == 0) {
        continue;
      }
      if ("Russians".equals(player) && n.equals(ConfigR4.get().capitaleRussa())) {
        moscowPlaced.merge(round, (int) placed, Integer::sum);
      } else if ("Axis".equals(allianceOf.apply(player))) {
        capturedPlaced.merge(round, (int) placed, Integer::sum);
      }
    }
    beforePlace.clear();
  }

  JsonObject snapshot(final int round, final Map<String, List<String>> alliances) {
    final JsonObject json = new JsonObject();
    final JsonObject owners = new JsonObject();
    CITTA.forEach(
        (label, name) -> {
          final Territory t = data.getMap().getTerritoryOrNull(name);
          owners.addProperty(label, t == null ? "?" : t.getOwner().getName());
        });
    json.add("proprietari", owners);
    final JsonObject army = new JsonObject();
    final Map<String, Integer> counts = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        if (Matches.unitIsLand().test(u) && Matches.unitIsNotInfrastructure().test(u)) {
          counts.merge(u.getOwner().getName(), 1, Integer::sum);
        }
      }
    }
    alliances.values().forEach(m -> m.forEach(n -> army.addProperty(n, counts.getOrDefault(n, 0))));
    json.add("esercito", army);
    final Territory moscow = data.getMap().getTerritoryOrNull(ConfigR4.get().capitaleRussa());
    int damage = -1;
    if (moscow != null) {
      for (final Unit u : moscow.getMatches(Matches.unitCanProduceUnitsAndCanBeDamaged())) {
        damage = Math.max(damage, u.getUnitDamage());
      }
    }
    json.addProperty("danno_mosca", damage);
    json.addProperty("prodotte_mosca", moscowPlaced.getOrDefault(round, 0));
    json.addProperty("prodotte_fabbriche_catturate", capturedPlaced.getOrDefault(round, 0));
    final ConfigR4 cfg = ConfigR4.get();
    json.addProperty("africa_asse_livello1", axisCount(cfg.italiaLivello1()));
    json.addProperty("africa_asse_livello2", axisCount(cfg.italiaLivello2()));
    int south = 0;
    final GamePlayer italians = data.getPlayerList().getPlayerId(cfg.nazioneItalia());
    for (final String n : SUD_RUSSIA) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null && italians != null) {
        south +=
            t.getMatches(
                    Matches.unitIsOwnedBy(italians)
                        .and(Matches.unitIsLand())
                        .and(Matches.unitIsNotInfrastructure()))
                .size();
      }
    }
    json.addProperty("italiani_sud_russia", south);
    json.add("obiettivi", objectives(alliances));
    return json;
  }

  private int axisCount(final List<String> names) {
    int n = 0;
    for (final String name : names) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t != null && "Axis".equals(allianceOf.apply(t.getOwner().getName()))) {
        n++;
      }
    }
    return n;
  }

  /**
   * Nation → objective → PUs it would give now (0 = not active): the national objectives of the map
   * (RulesAttachment with objectiveValue, uses left) and the triggers that give PUs, tested like
   * the engine's end of turn (same conditions, the objectives panel's dummy bridge).
   */
  private JsonObject objectives(final Map<String, List<String>> alliances) {
    final JsonObject json = new JsonObject();
    final Map<String, Integer> triggerValues = new HashMap<>();
    ConfigR4.get().obiettivi().forEach(o -> triggerValues.put(o.id(), o.valore()));
    try {
      final ObjectiveDummyDelegateBridge bridge = new ObjectiveDummyDelegateBridge(data);
      for (final List<String> members : alliances.values()) {
        for (final String name : members) {
          final GamePlayer p = data.getPlayerList().getPlayerId(name);
          if (p == null) {
            continue;
          }
          final Set<RulesAttachment> rules = new HashSet<>();
          for (final RulesAttachment r : RulesAttachment.getNationalObjectives(p)) {
            if (r.getUses() != 0) {
              rules.add(r);
            }
          }
          final Set<TriggerAttachment> triggers =
              TriggerAttachment.collectForAllTriggersMatching(
                  Set.of(p),
                  AbstractTriggerAttachment.availableUses
                      .and(AbstractTriggerAttachment.whenOrDefaultMatch(null, null))
                      .and(TriggerAttachment.resourceMatch()));
          final Set<ICondition> needed = new HashSet<>();
          needed.addAll(
              AbstractConditionsAttachment.getAllConditionsRecursive(new HashSet<>(rules), null));
          needed.addAll(
              AbstractConditionsAttachment.getAllConditionsRecursive(
                  new HashSet<>(triggers), null));
          if (needed.isEmpty()) {
            continue;
          }
          final Map<ICondition, Boolean> tested =
              AbstractConditionsAttachment.testAllConditionsRecursive(needed, null, bridge);
          final JsonObject n = new JsonObject();
          for (final RulesAttachment r : rules) {
            n.addProperty(
                r.getName(),
                r.isSatisfied(tested) ? r.getObjectiveValue() * r.getEachMultiple() : 0);
          }
          for (final TriggerAttachment t : triggers) {
            n.addProperty(
                t.getName(),
                t.isSatisfied(tested) ? triggerValues.getOrDefault(t.getName(), 0) : 0);
          }
          json.add(name, n);
        }
      }
    } catch (final RuntimeException e) {
      json.addProperty("errore", e.toString());
    }
    return json;
  }
}
