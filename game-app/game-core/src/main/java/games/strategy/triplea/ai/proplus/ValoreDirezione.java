package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.attachments.RulesAttachment;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.TriggerAttachment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Step P2: the VALUE of a direction for the alliance's objective, in PUs per round, read from the
 * map (no territory, nation or bonus written by hand): what changes if the next results of the
 * direction succeed (the given territories taken by the alliance).
 *
 * <ul>
 *   <li>{@code territori}: the production of every enemy territory taken, twice (taken from the
 *       enemy and given to the alliance);
 *   <li>{@code capitale}: the capital taken: an enemy nation that loses it collects nothing (its
 *       production); an allied nation that gets it back collects again (the production of what it
 *       owns);
 *   <li>{@code obiettivi}: the national objectives of the map ({@code objectiveAttachment...},
 *       their territory-ownership part) that stop for the enemy or start for the alliance;
 *   <li>{@code urgenza}: the cities of the enemy's victory condition (the map's victory triggers)
 *       on the direction that are in play (taken from the enemy, or allied and threatened), {@code
 *       peso_urgenza_pu} each, divided by the cities the enemy still lacks to win.
 * </ul>
 */
final class ValoreDirezione {
  private ValoreDirezione() {}

  /** The value and its items (for the register). */
  record Conto(double valore, JsonObject voci) {}

  /**
   * @param player the nation whose point of view decides who is an enemy (an alliance member)
   * @param presi the territories the next results take (enemy ones count)
   * @param teatro the direction's theatre (where its victory cities are)
   * @param minacciata an allied territory the enemy can take this round (realistic attack)
   */
  static Conto valore(
      final GameState data,
      final GamePlayer player,
      final Collection<Territory> presi,
      final Collection<Territory> teatro,
      final double pesoUrgenza,
      final Predicate<Territory> minacciata) {
    final JsonObject voci = new JsonObject();
    final Set<Territory> taken = new LinkedHashSet<>();
    for (final Territory t : presi) {
      if (!t.isWater() && nemico(player, t.getOwner())) {
        taken.add(t);
      }
    }
    // territories: taken from the enemy and given to the alliance
    double territories = 0;
    final JsonObject tj = new JsonObject();
    for (final Territory t : taken) {
      final int p = TerritoryAttachment.getProduction(t);
      territories += 2.0 * p;
      tj.addProperty(t.getName(), 2 * p);
    }
    voci.add("territori_dettaglio", tj);
    voci.addProperty("territori", territories);
    // capitals among the taken territories
    double capital = 0;
    final JsonObject cj = new JsonObject();
    for (final Territory t : taken) {
      final Optional<String> of =
          TerritoryAttachment.get(t).flatMap(TerritoryAttachment::getCapital);
      if (of.isEmpty()) {
        continue;
      }
      final GamePlayer nation = data.getPlayerList().getPlayerId(of.get());
      if (nation == null) {
        continue;
      }
      final double v =
          nemico(player, nation)
              ? ProUtils.getPlayerProduction(nation, data)
              : posseduto(data, nation);
      capital += v;
      cj.addProperty(t.getName() + " (" + nation.getName() + ")", v);
    }
    voci.add("capitale_dettaglio", cj);
    voci.addProperty("capitale", capital);
    // national objectives (ownership part)
    final Map<Territory, GamePlayer> after = new HashMap<>();
    for (final Territory t : taken) {
      after.put(t, nuovoProprietario(data, player, t));
    }
    double objectives = 0;
    final JsonArray oj = new JsonArray();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      for (final RulesAttachment ra : RulesAttachment.getNationalObjectives(p)) {
        final int v = ra.getObjectiveValue();
        if (v == 0) {
          continue;
        }
        final Optional<RulesAttachment.OwnershipTest> ot = ra.getOwnershipTest();
        if (ot.isEmpty() || ot.get().territories().stream().noneMatch(taken::contains)) {
          continue;
        }
        final boolean before = attivo(data, ot.get(), Map.of());
        final boolean now = attivo(data, ot.get(), after);
        final double change;
        if (nemico(player, p) && before && !now) {
          change = v;
        } else if (!nemico(player, p) && !before && now) {
          change = v;
        } else {
          continue;
        }
        objectives += change;
        final JsonObject x = new JsonObject();
        x.addProperty("obiettivo", ra.getName());
        x.addProperty("nazione", p.getName());
        x.addProperty("pu", v);
        x.addProperty(
            "effetto", nemico(player, p) ? "salta al nemico" : "si attiva per l'alleanza");
        x.addProperty("solo_proprieta", ot.get().ownershipOnly());
        oj.add(x);
      }
    }
    voci.add("obiettivi_dettaglio", oj);
    voci.addProperty("obiettivi", objectives);
    // urgency of the enemy's victory cities on the direction
    double urgency = 0;
    final Optional<RulesAttachment.OwnershipTest> victory = vittoriaNemica(data, player);
    if (victory.isPresent()) {
      final RulesAttachment.OwnershipTest ot = victory.get();
      final int held = conta(data, ot, Map.of());
      final int missing = Math.max(1, ot.needed() - held);
      final JsonArray inPlay = new JsonArray();
      for (final Territory c : ot.territories()) {
        if (!teatro.contains(c)) {
          continue;
        }
        final boolean enemyHeld = nemico(player, c.getOwner());
        if ((enemyHeld && taken.contains(c)) || (!enemyHeld && minacciata.test(c))) {
          inPlay.add(c.getName() + (enemyHeld ? " (presa)" : " (minacciata)"));
        }
      }
      urgency = pesoUrgenza * inPlay.size() / missing;
      final JsonObject uj = new JsonObject();
      uj.addProperty("citta_necessarie_al_nemico", ot.needed());
      uj.addProperty("citta_del_nemico", held);
      uj.addProperty("citta_mancanti_al_nemico", missing);
      uj.add("citta_in_gioco", inPlay);
      voci.add("urgenza_dettaglio", uj);
    }
    voci.addProperty("urgenza", urgency);
    final double total = territories + capital + objectives + urgency;
    voci.addProperty("totale", total);
    return new Conto(total, voci);
  }

  static boolean nemico(final GamePlayer player, final GamePlayer other) {
    return other != null && !other.isNull() && player.isAtWar(other);
  }

  /** The production of what {@code nation} owns (what it collects with its capital). */
  private static double posseduto(final GameState data, final GamePlayer nation) {
    double sum = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && t.isOwnedBy(nation)) {
        sum += TerritoryAttachment.getProduction(t);
      }
    }
    return sum;
  }

  /** Who gets {@code t} back: its original owner if it is not an enemy, else {@code player}. */
  private static GamePlayer nuovoProprietario(
      final GameState data, final GamePlayer player, final Territory t) {
    final Optional<GamePlayer> original =
        TerritoryAttachment.get(t).flatMap(TerritoryAttachment::getOriginalOwner);
    return original.filter(o -> !o.isNull() && !nemico(player, o)).orElse(player);
  }

  private static boolean attivo(
      final GameState data,
      final RulesAttachment.OwnershipTest ot,
      final Map<Territory, GamePlayer> changed) {
    return conta(data, ot, changed) >= ot.needed();
  }

  /** The territories of {@code ot} its players own (directly or allied), with {@code changed}. */
  static int conta(
      final GameState data,
      final RulesAttachment.OwnershipTest ot,
      final Map<Territory, GamePlayer> changed) {
    int n = 0;
    for (final Territory t : ot.territories()) {
      final GamePlayer owner = changed.getOrDefault(t, t.getOwner());
      if (owner == null || owner.isNull()) {
        continue;
      }
      for (final GamePlayer p : ot.players()) {
        if (ot.direct()
            ? owner.equals(p)
            : owner.equals(p) || data.getRelationshipTracker().isAllied(owner, p)) {
          n++;
          break;
        }
      }
    }
    return n;
  }

  /**
   * The ownership part of the enemy's victory condition (the map's victory triggers of the nations
   * at war with {@code player}): the condition with the most cities needed.
   */
  static Optional<RulesAttachment.OwnershipTest> vittoriaNemica(
      final GameState data, final GamePlayer player) {
    final Set<GamePlayer> enemies = new LinkedHashSet<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (nemico(player, p)) {
        enemies.add(p);
      }
    }
    if (enemies.isEmpty()) {
      return Optional.empty();
    }
    RulesAttachment.OwnershipTest best = null;
    try {
      final List<TriggerAttachment> triggers =
          new ArrayList<>(
              TriggerAttachment.collectForAllTriggersMatching(
                  enemies, TriggerAttachment.victoryMatch()));
      triggers.sort(java.util.Comparator.comparing(TriggerAttachment::getName));
      for (final TriggerAttachment t : triggers) {
        for (final RulesAttachment c : t.getConditions()) {
          final Optional<RulesAttachment.OwnershipTest> ot = c.getOwnershipTest();
          if (ot.isPresent()
              && ot.get().players().stream().anyMatch(enemies::contains)
              && (best == null || ot.get().needed() > best.needed())) {
            best = ot.get();
          }
        }
      }
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
    return Optional.ofNullable(best);
  }
}
