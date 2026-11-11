package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.attachments.ICondition;
import games.strategy.triplea.attachments.TriggerAttachment;
import games.strategy.triplea.ui.ObjectiveDummyDelegateBridge;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Step PARTITA COMPLETA: the map's victory, checked by the autoplay at the end of every round.
 *
 * <p>Why not the engine: the victory triggers of WW2 Europe 1940 2nd Edition ({@code
 * triggerAttachmentAxisVictory1}, {@code triggerAttachmentAlliedVictory1}) carry the message text
 * as the {@code victory} key, which the engine looks up in the map's {@code
 * notifications.properties}; the key is not there (and the autoplay loads no map resources), so
 * {@code TriggerAttachment.triggerVictory} never calls {@code signalGameOver}: the game never ends.
 * Here the conditions of the same triggers are tested (as the engine does, with the dummy bridge of
 * the objectives panel) and an alliance wins when they hold at the end of {@link #ROUNDS_HELD} + 1
 * consecutive rounds (the map's notes: "maintain it for a complete round of play"; the XML itself
 * checks only the end of one round, see its TODO). Measures only: the AIs never see it.
 */
final class Vittoria {
  /** Rounds the condition must be held after the round it is first met (a complete round). */
  static final int ROUNDS_HELD = 1;

  private final GameData data;
  private final Function<String, String> allianceOf;

  /** Alliance → first round of the current run of rounds with the condition met. */
  private final Map<String, Integer> since = new LinkedHashMap<>();

  /** Alliance → rounds whose end saw the condition met (for the summary). */
  private final Map<String, JsonArray> metRounds = new LinkedHashMap<>();

  private String winner;
  private int winningRound;

  Vittoria(final GameData data, final Function<String, String> allianceOf) {
    this.data = data;
    this.allianceOf = allianceOf;
  }

  /** Tests the victory triggers at the end of {@code round}; true when an alliance has won. */
  boolean endOfRound(final int round) {
    final Set<String> met = metAlliances();
    since.keySet().removeIf(a -> !met.contains(a));
    for (final String alliance : met) {
      since.putIfAbsent(alliance, round);
      metRounds.computeIfAbsent(alliance, a -> new JsonArray()).add(round);
      if (winner == null && round - since.get(alliance) >= ROUNDS_HELD) {
        winner = alliance;
        winningRound = round;
      }
    }
    return winner != null;
  }

  /** Alliances whose victory trigger has its conditions met now. */
  Set<String> metAlliances() {
    final Set<GamePlayer> players = Set.copyOf(data.getPlayerList().getPlayers());
    final Set<TriggerAttachment> triggers =
        TriggerAttachment.collectForAllTriggersMatching(players, TriggerAttachment.victoryMatch());
    if (triggers.isEmpty()) {
      return Set.of();
    }
    final Map<ICondition, Boolean> tested =
        TriggerAttachment.collectTestsForAllTriggers(
            triggers, new ObjectiveDummyDelegateBridge(data));
    final Set<String> met = new java.util.TreeSet<>();
    for (final TriggerAttachment t : triggers) {
      if (t.isSatisfied(tested)) {
        met.add(allianceOf.apply(firstPlayer(t)));
      }
    }
    return met;
  }

  /** First of the trigger's "players" (the winners), else the player it is attached to. */
  private static String firstPlayer(final TriggerAttachment t) {
    return t.getPropertyOrEmpty("players")
        .map(p -> p.getValue())
        .filter(List.class::isInstance)
        .map(List.class::cast)
        .filter(l -> !l.isEmpty() && l.get(0) instanceof GamePlayer)
        .map(l -> ((GamePlayer) l.get(0)).getName())
        .orElseGet(() -> ((games.strategy.engine.data.Named) t.getAttachedTo()).getName());
  }

  Optional<String> winner() {
    return Optional.ofNullable(winner);
  }

  int winningRound() {
    return winningRound;
  }

  /**
   * Step R4 (point 0), reading of the XML: the first end of round with the condition met (what the
   * engine's trigger would do if the map had the victory message), -1 if never.
   */
  static int letturaXml(final List<Integer> metRounds) {
    return metRounds.isEmpty() ? -1 : metRounds.get(0);
  }

  /**
   * Step R4 (point 0), reading of the map's notes (what ends the game for a player, since the
   * engine never signals it on this map): the first round that closes {@code held} complete rounds
   * with the condition met at every end of round, -1 if never.
   */
  static int letturaNote(final List<Integer> metRounds, final int held) {
    int start = -1;
    int last = -2;
    for (final int r : metRounds) {
      start = r == last + 1 ? start : r;
      last = r;
      if (r - start >= held) {
        return r;
      }
    }
    return -1;
  }

  /**
   * {"round_tenuta": 1, "condizione": {alliance: [rounds met]}, "lettura_xml": {alliance: first
   * round met}, "lettura_note": {alliance: first round held}, "vincitore", "round"}.
   */
  JsonObject toJson() {
    final JsonObject json = new JsonObject();
    json.addProperty("round_tenuta", ROUNDS_HELD);
    final JsonObject met = new JsonObject();
    metRounds.forEach(met::add);
    json.add("condizione", met);
    final JsonObject xml = new JsonObject();
    final JsonObject notes = new JsonObject();
    metRounds.forEach(
        (alliance, rounds) -> {
          final List<Integer> list = new java.util.ArrayList<>();
          rounds.forEach(e -> list.add(e.getAsInt()));
          xml.addProperty(alliance, letturaXml(list));
          notes.addProperty(alliance, letturaNote(list, ROUNDS_HELD));
        });
    json.add("lettura_xml", xml);
    json.add("lettura_note", notes);
    if (winner != null) {
      json.addProperty("vincitore", winner);
      json.addProperty("round", winningRound);
    }
    return json;
  }
}
