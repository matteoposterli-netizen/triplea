package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;

/**
 * Step P1: one obstacle between a task's result and the board, with its numbers. {@code pu} is its
 * size in PU-equivalents (what removing it costs, or {@code turni x pu_per_turno_di_distanza} for a
 * distance), so obstacles of different types can be added and compared.
 *
 * @param tipo the kind of obstacle
 * @param luogo the zone or territory
 * @param pu size in PU-equivalents (0 = no obstacle)
 * @param vittoria the relevant win% (MARE: enemy win on the zone at the end of the turn, or our win
 *     against the fleet in the zone; TERRA: our win on the target), -1 if none
 * @param numeri the numbers (own forces, enemy forces, ...), for the register
 */
public record Ostacolo(Tipo tipo, String luogo, double pu, double vittoria, JsonObject numeri) {

  /** The obstacle types of the step (the prompt's list). */
  public enum Tipo {
    /** Enemy ships in the zone, or that can attack it in their turn, planes in range, scramble. */
    MARE,
    /** Defenders of the target against the force that can land / attack. */
    TERRA,
    /** Transports or troops missing (the owned ones, also idle and empty, counted first). */
    CAPACITA,
    /** Forces not yet in position (turns). */
    DISTANZA
  }

  public JsonObject json() {
    final JsonObject o = new JsonObject();
    o.addProperty("tipo", tipo.name());
    o.addProperty("luogo", luogo);
    o.addProperty("pu", Math.round(pu * 10) / 10.0);
    if (vittoria >= 0) {
      o.addProperty("vittoria_pct", Math.round(vittoria * 10) / 10.0);
    }
    o.add("numeri", numeri);
    return o;
  }

  public String testo() {
    return String.format(
        Locale.ROOT,
        "%s %s (%.0f PU%s)",
        tipo,
        luogo,
        pu,
        vittoria >= 0 ? String.format(Locale.ROOT, ", vittoria %.1f%%", vittoria) : "");
  }

  /** The sum of {@code obstacles} in PU-equivalents. */
  public static double somma(final List<Ostacolo> obstacles) {
    return obstacles.stream().mapToDouble(Ostacolo::pu).sum();
  }

  /** The biggest obstacle first. */
  public static List<Ostacolo> perGrandezza(final List<Ostacolo> obstacles) {
    return obstacles.stream()
        .filter(o -> o.pu() > 0)
        .sorted(
            java.util.Comparator.comparingDouble((Ostacolo o) -> -o.pu())
                .thenComparing(o -> o.tipo().ordinal()))
        .toList();
  }
}
