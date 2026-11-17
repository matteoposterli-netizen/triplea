package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Step P1: the generic response to the obstacles of a task's result (no nation, no map name). Pure
 * functions on the numbers computed by {@link Compiti}: which target, which responses (execute,
 * attack the enemy force, change target, buy against the biggest obstacle, move closer), the
 * transports to buy, the direction and when to check it again.
 */
public final class Risposta {
  private Risposta() {}

  /** The responses of the step. */
  public enum Tipo {
    /** The result (or its next step on the chosen target) is obtainable this turn: do it. */
    ESEGUI,
    /** An enemy force behind a MARE obstacle can be attacked with a win >= the threshold. */
    ATTACCO_FLOTTA,
    /** Another target of the same result has a smaller obstacle. */
    CAMBIO_BERSAGLIO,
    /** Warships / carrier planes against a MARE obstacle. */
    ACQUISTO_MARE,
    /** Troops against a TERRA obstacle. */
    ACQUISTO_TERRA,
    /** Transports (only if the owned ones cannot carry the ready troops). */
    ACQUISTO_CAPACITA,
    /** The whole fleet moves with the transports and covers the zone (MARE obstacle). */
    SCORTA,
    /** The forces move closer (DISTANZA). */
    AVVICINAMENTO,
    /** Nothing can be done. */
    ATTESA
  }

  /**
   * The analysis of one target of the current result.
   *
   * @param nome the target (territory or zone)
   * @param zona the zone the force goes to (landing zone, base zone), may be null
   * @param ottenibile obtainable in this turn with the real forces
   * @param ostacoli the obstacles with their numbers
   * @param flottaBattibile a zone with an enemy force behind a MARE obstacle that the forces in reach
   *     beat with a win >= the threshold, null if none
   * @param vittoriaFlotta our win% against that force (-1 if none)
   */
  public record Bersaglio(
      String nome,
      @Nullable String zona,
      boolean ottenibile,
      List<Ostacolo> ostacoli,
      @Nullable String flottaBattibile,
      double vittoriaFlotta) {

    public double somma() {
      return Ostacolo.somma(ostacoli);
    }

    public boolean ha(final Ostacolo.Tipo t) {
      return ostacoli.stream().anyMatch(o -> o.tipo() == t && o.pu() > 0);
    }

    public JsonObject json() {
      final JsonObject o = new JsonObject();
      o.addProperty("bersaglio", nome);
      if (zona != null) {
        o.addProperty("zona", zona);
      }
      o.addProperty("ottenibile", ottenibile);
      o.addProperty("somma_ostacoli_pu", Math.round(somma() * 10) / 10.0);
      final JsonArray a = new JsonArray();
      ostacoli.forEach(x -> a.add(x.json()));
      o.add("ostacoli", a);
      if (flottaBattibile != null) {
        o.addProperty("flotta_nemica_battibile", flottaBattibile);
        o.addProperty("vittoria_contro_flotta_pct", Math.round(vittoriaFlotta * 10) / 10.0);
      }
      return o;
    }
  }

  /**
   * The choice of the turn.
   *
   * @param bersaglio the chosen target (null = none)
   * @param risposte the responses, in order
   * @param perche why
   * @param scartate the other targets with the reason they were not chosen
   */
  public record Scelta(
      @Nullable Bersaglio bersaglio, List<Tipo> risposte, String perche, JsonArray scartate) {}

  private static final Comparator<Bersaglio> ORDINE =
      Comparator.comparingDouble(Bersaglio::somma).thenComparing(Bersaglio::nome);

  /**
   * The rule of the step for the current result: (a) an obtainable target is executed (the one with
   * the smallest obstacles); (b) otherwise the current target, unless another one has obstacles
   * smaller by more than {@code margine} PU (change of target); then an enemy force that can be
   * beaten is attacked, the biggest obstacles are answered by purchases (MARE: warships and planes
   * plus the escort of the whole fleet; TERRA: troops; CAPACITA: transports) and the forces move
   * closer.
   */
  public static Scelta decidi(
      final List<Bersaglio> bersagli, @Nullable final String attuale, final double margine) {
    final JsonArray scartate = new JsonArray();
    if (bersagli.isEmpty()) {
      return new Scelta(null, List.of(Tipo.ATTESA), "nessun bersaglio del risultato", scartate);
    }
    final Optional<Bersaglio> obtainable =
        bersagli.stream().filter(Bersaglio::ottenibile).min(ORDINE);
    final Bersaglio chosen;
    final List<Tipo> out = new ArrayList<>();
    final String why;
    if (obtainable.isPresent()) {
      chosen = obtainable.get();
      out.add(Tipo.ESEGUI);
      why = "ottenibile in questo turno con le forze reali";
    } else {
      final Bersaglio best = bersagli.stream().min(ORDINE).orElseThrow();
      final Bersaglio current =
          attuale == null
              ? best
              : bersagli.stream().filter(b -> b.nome().equals(attuale)).findFirst().orElse(best);
      if (!current.equals(best) && best.somma() < current.somma() - margine) {
        chosen = best;
        out.add(Tipo.CAMBIO_BERSAGLIO);
        why =
            String.format(
                java.util.Locale.ROOT,
                "%s ha ostacoli minori (%.0f PU contro %.0f di %s)",
                best.nome(),
                best.somma(),
                current.somma(),
                current.nome());
      } else {
        chosen = current;
        why =
            current.equals(best)
                ? "ostacoli minori tra i bersagli del risultato"
                : "bersaglio tenuto: nessun altro con ostacoli minori di oltre "
                    + Math.round(margine)
                    + " PU";
      }
      if (chosen.flottaBattibile() != null) {
        out.add(Tipo.ATTACCO_FLOTTA);
      }
    }
    for (final Ostacolo o : Ostacolo.perGrandezza(chosen.ostacoli())) {
      final List<Tipo> add =
          switch (o.tipo()) {
            case MARE -> List.of(Tipo.ACQUISTO_MARE, Tipo.SCORTA);
            case TERRA -> List.of(Tipo.ACQUISTO_TERRA);
            case CAPACITA -> List.of(Tipo.ACQUISTO_CAPACITA);
            case DISTANZA -> List.of(Tipo.AVVICINAMENTO);
          };
      add.stream().filter(t -> !out.contains(t)).forEach(out::add);
    }
    if (out.isEmpty()) {
      out.add(Tipo.AVVICINAMENTO);
    }
    for (final Bersaglio b : bersagli) {
      if (b.equals(chosen)) {
        continue;
      }
      final JsonObject s = b.json();
      s.addProperty(
          "scartato_perche",
          b.ottenibile()
              ? "ottenibile ma con ostacoli maggiori"
              : obtainable.isPresent()
                  ? "non ottenibile in questo turno"
                  : "ostacoli maggiori o non oltre il margine di cambio");
      scartate.add(s);
    }
    return new Scelta(chosen, List.copyOf(out), why, scartate);
  }

  /**
   * CAPACITA: new transports only if the owned ones (anywhere, also empty and idle) cannot carry the
   * troops ready to go: {@code ceil((posti da portare - posti liberi posseduti) / capacita)}, never
   * negative.
   */
  public static int trasportiDaComprare(
      final int postiDaPortare, final int postiLiberiPosseduti, final int capacitaTrasporto) {
    final int missing = postiDaPortare - postiLiberiPosseduti;
    if (missing <= 0 || capacitaTrasporto <= 0) {
      return 0;
    }
    return (missing + capacitaTrasporto - 1) / capacitaTrasporto;
  }

  /** R2: the direction is chosen again when none is chosen or it has been held {@code tenuta}. */
  public static boolean ricontrollaDirezione(final int round, final int dalRound, final int tenuta) {
    return dalRound <= 0 || round - dalRound >= tenuta;
  }

  /** R2: the option with the smallest sum of obstacles (ties: the first in the data's order). */
  public static String scegliDirezione(final Map<String, Double> somme) {
    String best = null;
    double min = Double.MAX_VALUE;
    for (final var e : somme.entrySet()) {
      if (e.getValue() < min) {
        min = e.getValue();
        best = e.getKey();
      }
    }
    if (best == null) {
      throw new IllegalArgumentException("nessuna opzione");
    }
    return best;
  }

  /**
   * MARE purchase: among {@code candidati} (type → [drop of the obstacle, PUs]) the one that lowers
   * the obstacle most per PU spent; empty if none lowers it.
   */
  public static Optional<String> miglioreAcquisto(final Map<String, double[]> candidati) {
    String best = null;
    double score = 0;
    for (final var e : candidati.entrySet()) {
      final double drop = e.getValue()[0];
      final double cost = e.getValue()[1];
      if (cost <= 0 || drop <= 0) {
        continue;
      }
      final double s = drop / cost;
      if (s > score + 1e-9) {
        score = s;
        best = e.getKey();
      }
    }
    return Optional.ofNullable(best);
  }
}
