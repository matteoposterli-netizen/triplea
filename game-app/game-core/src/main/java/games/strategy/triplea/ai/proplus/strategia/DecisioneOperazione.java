package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.triplea.ai.proplus.strategia.llm.ErroreStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.UscitaLlm;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Contract v1.2 (step PROVALLM): the decision at a decision point of a joint operation, object
 * {@code decisione_operazione} = {azione, prob_tenuta_stimata 0-1, motivazione}. The allowed
 * actions depend on the point ({@link Punto}). See {@code proplus/strategia/contratto-v1.md}.
 *
 * @param probTenuta probability (0-1) that the target is the alliance's at the start of the next
 *     turn of the nation that decides, if the action is carried out
 */
public record DecisioneOperazione(Azione azione, double probTenuta, String motivazione) {

  /** Version of the contract that adds the object. */
  public static final String VERSIONE = "1.2";

  /** Key of the object in the answer. */
  public static final String CHIAVE = "decisione_operazione";

  /** Longest reason kept (characters). */
  static final int MAX_MOTIVAZIONE = 300;

  /** The actions of the contract. */
  public enum Azione {
    LANCIA,
    RINVIA,
    PROCEDI,
    TIENI,
    RITIRA,
    ANNULLA;

    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Azione> daId(final String id) {
      return Arrays.stream(values())
          .filter(a -> a.id().equals(id == null ? "" : id.strip().toLowerCase(Locale.ROOT)))
          .findFirst();
    }
  }

  /** The decision points of the operations v2 and the actions allowed there. */
  public enum Punto {
    /** Step 1 of the opener (also on the final target): launch or not. */
    TAPPA1(Azione.LANCIA, Azione.RINVIA, Azione.ANNULLA),
    /** Step 2 of the closer, same round: go on or not. */
    TAPPA2(Azione.PROCEDI, Azione.RINVIA, Azione.ANNULLA),
    /** The target held: keep the garrison (with the engine's reinforcements) or leave it. */
    TENUTA(Azione.TIENI, Azione.RITIRA);

    private final List<Azione> azioni;

    Punto(final Azione... azioni) {
      this.azioni = List.of(azioni);
    }

    public List<Azione> azioni() {
      return azioni;
    }

    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Whether the action carries out the operation (launch, go on, keep). */
  public boolean esegue() {
    return azione == Azione.LANCIA || azione == Azione.PROCEDI || azione == Azione.TIENI;
  }

  /**
   * The decision in an answer text ({@code {"decisione_operazione": {...}}} or the fields at the
   * root), checked against the actions of {@code punto}.
   *
   * @throws ErroreStratega answer not valid (no JSON, action unknown or not allowed, probability
   *     out of 0-1)
   */
  public static DecisioneOperazione leggi(final String text, final Punto punto) {
    final JsonObject root = UscitaLlm.leggi(text);
    final JsonObject o =
        root.has(CHIAVE) && root.get(CHIAVE).isJsonObject() ? root.getAsJsonObject(CHIAVE) : root;
    final String id = string(o, "azione");
    final Azione azione =
        Azione.daId(id).orElseThrow(() -> new ErroreStratega("azione sconosciuta: " + id));
    if (!punto.azioni().contains(azione)) {
      throw new ErroreStratega(
          "azione " + azione.id() + " non ammessa in " + punto.id() + " (" + ids(punto) + ")");
    }
    final JsonElement p = o.get("prob_tenuta_stimata");
    if (p == null || !p.isJsonPrimitive() || !p.getAsJsonPrimitive().isNumber()) {
      throw new ErroreStratega("prob_tenuta_stimata mancante");
    }
    final double prob = p.getAsDouble();
    if (Double.isNaN(prob) || prob < 0 || prob > 1) {
      throw new ErroreStratega(
          String.format(Locale.ROOT, "prob_tenuta_stimata %.2f fuori da 0-1", prob));
    }
    String why = string(o, "motivazione").strip().replaceAll("\\s+", " ");
    if (why.length() > MAX_MOTIVAZIONE) {
      why = why.substring(0, MAX_MOTIVAZIONE) + "...";
    }
    return new DecisioneOperazione(azione, prob, why);
  }

  /** "lancia | rinvia | annulla". */
  public static String ids(final Punto punto) {
    return String.join(" | ", punto.azioni().stream().map(Azione::id).toList());
  }

  public JsonObject json() {
    final JsonObject o = new JsonObject();
    o.addProperty("azione", azione.id());
    o.addProperty("prob_tenuta_stimata", Math.round(probTenuta * 1000) / 1000.0);
    o.addProperty("motivazione", motivazione);
    return o;
  }

  private static String string(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
  }
}
