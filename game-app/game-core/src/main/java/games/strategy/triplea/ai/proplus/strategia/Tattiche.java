package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Step 3d-2: the switches of the delay tools ("tattiche attive"), per alliance (one structure in
 * each alliance's general staff), nation and front. Saved in the history entry of the round ({@code
 * stato_strategico.tattiche_attive}), so a decider (rules today, another one later) only has to
 * write this structure: the tools ({@code StrumentiRitardo}, the hunt in {@link PianoAttivo}, the
 * counter-attack check of {@code ProPlusTuning}) read it and decide nothing.
 *
 * <p>Format: {@code {"<tool>": {"<nation>": {"fronti": [...], "perche": "..."}}}}; no nation = tool
 * off; empty {@code fronti} = every front.
 *
 * <p>The decider by rules (fixed, no score): the nations with role "ritarda" in the plan ({@link
 * Strategia.Piano#ritarda}) and every nation of an alliance in posture RALLENTAMENTO or DIFENSIVA;
 * nothing with a plan without counter-moves (plan N).
 */
public final class Tattiche {
  public static final String BLOCCO_TERRESTRE = "blocco_terrestre";
  public static final String RITIRATA = "ritirata_combattuta";
  public static final String BLOCCO_NAVALE = "blocco_navale";
  public static final String CACCIA = "caccia_trasporti";
  public static final List<String> STRUMENTI =
      List.of(BLOCCO_TERRESTRE, RITIRATA, BLOCCO_NAVALE, CACCIA);

  /** Key of the structure in the strategic state. */
  public static final String KEY = "tattiche_attive";

  private Tattiche() {}

  /** The decider by rules, see the class comment. */
  public static JsonObject decidi(
      final GameState data,
      final String alliance,
      final Strategia.Piano plan,
      final String posture) {
    final Map<String, String> why = new LinkedHashMap<>();
    if (plan.contromosse()) {
      plan.ritarda().forEach(n -> why.put(n, "ruolo ritarda nel piano " + plan.id()));
      if (Contromosse.RALLENTAMENTO.equals(posture) || Contromosse.DIFENSIVA.equals(posture)) {
        data.getAllianceTracker().getPlayersInAlliance(alliance).stream()
            .map(GamePlayer::getName)
            .sorted()
            .forEach(n -> why.putIfAbsent(n, "postura " + posture));
      }
    }
    final JsonObject json = new JsonObject();
    for (final String tool : STRUMENTI) {
      final JsonObject nations = new JsonObject();
      why.forEach(
          (nation, reason) -> {
            final JsonObject o = new JsonObject();
            o.add("fronti", new JsonArray());
            o.addProperty("perche", reason);
            nations.add(nation, o);
          });
      json.add(tool, nations);
    }
    return json;
  }

  /**
   * Why {@code tool} is on for {@code nation} on {@code front} ({@code null} front = on any front),
   * empty if off.
   */
  public static Optional<String> perche(
      final @Nullable JsonObject tattiche,
      final String tool,
      final String nation,
      final @Nullable String front) {
    if (tattiche == null || !tattiche.has(tool) || !tattiche.get(tool).isJsonObject()) {
      return Optional.empty();
    }
    final JsonObject nations = tattiche.getAsJsonObject(tool);
    if (!nations.has(nation)) {
      return Optional.empty();
    }
    final JsonObject o = nations.getAsJsonObject(nation);
    if (front != null && o.has("fronti") && !o.getAsJsonArray("fronti").isEmpty()) {
      boolean found = false;
      for (final JsonElement f : o.getAsJsonArray("fronti")) {
        found |= f.getAsString().equals(front);
      }
      if (!found) {
        return Optional.empty();
      }
    }
    return Optional.of(o.has("perche") ? o.get("perche").getAsString() : "attivo");
  }

  /** Eg " Tattiche attive: blocco_terrestre Russians (ruolo ritarda nel piano 1); ...". */
  public static String testo(final @Nullable JsonObject tattiche) {
    final List<String> parts = new ArrayList<>();
    if (tattiche != null) {
      for (final String tool : STRUMENTI) {
        if (!tattiche.has(tool)) {
          continue;
        }
        final List<String> nations = new ArrayList<>();
        tattiche
            .getAsJsonObject(tool)
            .entrySet()
            .forEach(
                e -> {
                  final JsonObject o = e.getValue().getAsJsonObject();
                  final String fronts =
                      o.has("fronti") && !o.getAsJsonArray("fronti").isEmpty()
                          ? " fronti " + o.getAsJsonArray("fronti")
                          : "";
                  nations.add(
                      e.getKey()
                          + fronts
                          + " ("
                          + (o.has("perche") ? o.get("perche").getAsString() : "attivo")
                          + ")");
                });
        if (!nations.isEmpty()) {
          parts.add(tool + " " + String.join(", ", nations));
        }
      }
    }
    return "  Tattiche attive: " + (parts.isEmpty() ? "nessuna" : String.join("; ", parts));
  }
}
