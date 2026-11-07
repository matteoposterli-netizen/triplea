package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProStagingOrders;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.proplus.strategia.PianoAttivo;
import games.strategy.triplea.ai.proplus.strategia.Strategia;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.experimental.UtilityClass;

/**
 * Where a plan's reserves gather (step 3b-ter): fixed territories of the file (preparation), or the
 * front line computed each turn (offensive phases), several fronts with weights, and the
 * embarkation points when the gathering territories are overseas.
 */
@UtilityClass
class Staging {

  /**
   * Whether a staging is done enough to declare war on {@code enemy}.
   *
   * @param ready land TUV of mine on the gathering territories at least the enemy's next to them
   * @param text eg "ammassamento 120 TUV contro 85 TUV nemici confinanti"
   */
  record Readiness(boolean ready, String text) {}

  /** The staging orders of {@code l}; empty if no territory to gather at. */
  static Optional<ProStagingOrders> orders(
      final ProData proData,
      final PianoAttivo plan,
      final Strategia.Logistica l,
      final String label) {
    final Map<String, Double> weights = new LinkedHashMap<>();
    final List<String> fronts = new ArrayList<>();
    final double maxWeight =
        Math.max(
            l.pesoAmmassamento(),
            l.altreRaccolte().stream().mapToDouble(Strategia.Raccolta::peso).max().orElse(0));
    final Strategia.Raccolta main =
        new Strategia.Raccolta(
            l.fronteAmmassamento(),
            plan.territoriAmmassamento(l),
            l.pesoAmmassamento(),
            l.lineaDelFronte());
    final List<Strategia.Raccolta> groups = new ArrayList<>(List.of(main));
    groups.addAll(l.altreRaccolte());
    for (final Strategia.Raccolta group : groups) {
      final double share = maxWeight <= 0 ? 1 : group.peso() / maxWeight;
      final Map<String, Double> territories = territories(proData, plan, group);
      territories.forEach((t, w) -> weights.merge(t, w * share, Math::max));
      final boolean formed =
          group.lineaDelFronte()
              && group.fronte() != null
              && !frontLine(proData, plan, group.fronte()).isEmpty();
      fronts.add(frontText(group, formed, groups.size() > 1));
    }
    if (isOverseas(proData, main.territori()) && !l.imbarco().isEmpty()) {
      l.imbarco().forEach(t -> weights.merge(t, 1.0, Math::max));
      fronts.add("imbarco " + String.join("/", l.imbarco()) + " (raccolta oltremare)");
    }
    if (weights.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new ProStagingOrders(
            label,
            String.join(" + ", fronts),
            List.copyOf(weights.keySet()),
            l.forza(),
            l.aerei(),
            Map.copyOf(weights)));
  }

  /** Territories of one group with their pull (0..1) before the group's share. */
  private static Map<String, Double> territories(
      final ProData proData, final PianoAttivo plan, final Strategia.Raccolta group) {
    final Map<String, Double> result = new LinkedHashMap<>();
    if (group.lineaDelFronte() && group.fronte() != null) {
      final Map<String, Double> line = frontLine(proData, plan, group.fronte());
      if (!line.isEmpty()) {
        return line;
      }
    }
    final List<String> names =
        group.territori().isEmpty() && group.fronte() != null
            ? plan.getStrategia().territoriDelFronte(group.fronte())
            : group.territori();
    names.forEach(n -> result.put(n, 1.0));
    return result;
  }

  /**
   * The front line of {@code front}: own land territories of the front next to enemy land (players
   * at war of the map's main alliances, not neutrals), each weighed 0.5..1 by its distance to the
   * nearest objective of the plan ({@code 0.5 + 0.5 / (1 + distance)}; 1 without objectives). Empty
   * if not at war there.
   */
  static Map<String, Double> frontLine(
      final ProData proData, final PianoAttivo plan, final String front) {
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final GameMap map = data.getMap();
    final Predicate<Territory> enemyLand =
        Matches.isTerritoryEnemy(player)
            .and(Matches.territoryIsLand())
            .and(t -> !ProUtils.isNeutralLand(t))
            .and(t -> ProPlusSettings.allianceOf(data, t.getOwner()).isPresent());
    final List<Territory> objectives = new ArrayList<>();
    plan.getObiettivi().stream()
        .map(Strategia.Obiettivo::territorio)
        .filter(Objects::nonNull)
        .map(map::getTerritoryOrNull)
        .filter(t -> t != null && !t.isWater() && front.equals(plan.fronte(t)))
        .forEach(objectives::add);
    final Map<String, Double> line = new LinkedHashMap<>();
    for (final Territory t : map.getTerritories()) {
      if (t.isWater()
          || !t.isOwnedBy(player)
          || !front.equals(plan.fronte(t))
          || map.getNeighbors(t, enemyLand).isEmpty()) {
        continue;
      }
      double weight = 1;
      if (!objectives.isEmpty()) {
        int best = Integer.MAX_VALUE;
        for (final Territory o : objectives) {
          final int d = map.getDistance(t, o, Matches.territoryIsLand());
          if (d >= 0) {
            best = Math.min(best, d);
          }
        }
        weight = best == Integer.MAX_VALUE ? 0.5 : 0.5 + 0.5 / (1 + best);
      }
      line.put(t.getName(), weight);
    }
    return line;
  }

  /**
   * Whether no land territory of {@code names} can be reached by land from the player's capital (or
   * first factory): the units need transports.
   */
  static boolean isOverseas(final ProData proData, final List<String> names) {
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final Optional<Territory> home = home(data, player);
    if (home.isEmpty()) {
      return false;
    }
    // only through own and allied land: a way through enemy land is not a way to send reserves
    final Predicate<Territory> path =
        ProMatches.territoryCanMoveLandUnits(player, false).and(Matches.isTerritoryAllied(player));
    boolean anyLand = false;
    for (final String name : names) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t == null || t.isWater()) {
        continue;
      }
      anyLand = true;
      if (t.equals(home.get()) || data.getMap().getDistance(home.get(), t, path) >= 0) {
        return false;
      }
    }
    return anyLand;
  }

  /**
   * Step 3b-ter: the gathering is overseas, there are embarkation points, and the own transports
   * near them carry fewer units than wait there (2 per transport; land units on and next to the
   * embarkation territories).
   */
  static boolean needsTransport(
      final ProData proData, final PianoAttivo plan, final Strategia.Logistica l) {
    if (l.imbarco().isEmpty() || !isOverseas(proData, plan.territoriAmmassamento(l))) {
      return false;
    }
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final GameMap map = data.getMap();
    int transports = 0;
    int waiting = 0;
    final List<Territory> seaZones = new ArrayList<>();
    for (final String name : l.imbarco()) {
      final Territory t = map.getTerritoryOrNull(name);
      if (t == null) {
        continue;
      }
      if (t.isWater()) {
        seaZones.add(t);
      } else {
        seaZones.addAll(map.getNeighbors(t, Matches.territoryIsWater()));
        final List<Territory> area = new ArrayList<>(List.of(t));
        area.addAll(map.getNeighbors(t, Matches.territoryIsLand()));
        for (final Territory a : area) {
          if (a.isOwnedBy(player)) {
            waiting +=
                a.getUnitCollection()
                    .countMatches(
                        Matches.unitIsOwnedBy(player)
                            .and(Matches.unitIsLand())
                            .and(Matches.unitIsNotInfrastructure()));
          }
        }
      }
    }
    final java.util.Set<Territory> near = new java.util.HashSet<>(seaZones);
    for (final Territory z : seaZones) {
      near.addAll(map.getNeighbors(z, 2, Matches.territoryIsWater()));
    }
    for (final Territory z : near) {
      transports +=
          z.getUnitCollection()
              .countMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsSeaTransport()));
    }
    return transports * 2 < Math.max(2, waiting);
  }

  /**
   * Readiness to declare war on {@code enemy}: land TUV of mine on the gathering territories (the
   * plan's points of this round: the front line doesn't exist before the war) at least the land TUV
   * of {@code enemy} on the land next to them. Ready without staging orders.
   */
  static Readiness readiness(
      final ProData proData, final PianoAttivo plan, final GamePlayer enemy) {
    final Optional<Strategia.Logistica> l = plan.getLogistica();
    if (l.isEmpty()) {
      return new Readiness(true, "nessun ammassamento richiesto");
    }
    final GameState data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final GameMap map = data.getMap();
    final List<Unit> mine = new ArrayList<>();
    final java.util.Set<Territory> border = new java.util.HashSet<>();
    final List<String> names = new ArrayList<>(plan.territoriAmmassamento(l.get()));
    if (names.isEmpty()) {
      return new Readiness(true, "nessun territorio di raccolta");
    }
    for (final String name : names) {
      final Territory t = map.getTerritoryOrNull(name);
      if (t == null || t.isWater()) {
        continue;
      }
      if (t.isOwnedBy(player)) {
        mine.addAll(
            t.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(Matches.unitIsLand())
                    .and(Matches.unitIsNotInfrastructure())));
      }
      border.addAll(map.getNeighbors(t, Matches.isTerritoryOwnedBy(enemy)));
    }
    final List<Unit> theirs = new ArrayList<>();
    for (final Territory t : border) {
      theirs.addAll(
          t.getMatches(
              Matches.unitIsOwnedBy(enemy)
                  .and(Matches.unitIsLand())
                  .and(Matches.unitIsNotInfrastructure())));
    }
    final double myTuv = TuvUtils.getTuv(mine, proData.getUnitValueMap());
    final double theirTuv = TuvUtils.getTuv(theirs, proData.getUnitValueMap());
    return new Readiness(
        myTuv >= theirTuv,
        String.format(
            Locale.ROOT,
            "ammassamento %.0f TUV in %s contro %.0f TUV nemici confinanti",
            myTuv,
            String.join("/", names),
            theirTuv));
  }

  private static Optional<Territory> home(final GameState data, final GamePlayer player) {
    final List<Territory> capitals =
        TerritoryAttachment.getAllCurrentlyOwnedCapitals(player, data.getMap());
    if (!capitals.isEmpty()) {
      return Optional.of(capitals.get(0));
    }
    return data.getMap().getTerritories().stream()
        .filter(ProMatches.territoryHasInfraFactoryAndIsOwnedLand(player))
        .findFirst();
  }

  /** Eg: "Est 80 (linea del fronte)" or "Ovest". */
  private static String frontText(
      final Strategia.Raccolta group, final boolean formed, final boolean weighed) {
    final StringBuilder sb =
        new StringBuilder(Optional.ofNullable(group.fronte()).orElse("raccolta"));
    if (weighed) {
      sb.append(String.format(Locale.ROOT, " peso %.0f", group.peso()));
    }
    if (group.lineaDelFronte()) {
      sb.append(formed ? " (linea del fronte)" : " (linea del fronte non formata: punti del file)");
    }
    return sb.toString();
  }
}
