package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import games.strategy.triplea.ai.proplus.strategia.Direttive;
import games.strategy.triplea.ai.proplus.strategia.Tattiche;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * The strategist's prompt: fixed part from {@value #RISORSA} (rules of the map, the plans of the
 * data file, tools, guard rails, answer schema; the same in every round so the provider can cache
 * it) and variable part (fantasy level + the strategic state JSON of the round).
 */
@UtilityClass
public final class PromptStratega {
  public static final String RISORSA = "proplus/strategia/prompt-stratega-v1.md";
  public static final String VERSIONE = "prompt-stratega-v1";

  /** Default of the "Fantasia" slider. */
  public static final int FANTASIA_DEFAULT = 2;

  private static final List<String> FANTASIA =
      List.of(
          "1/5, ortodossa: segui il piano del file dati e le scelte classiche; niente sorprese.",
          "2/5, prudente: piano del file dati, piccoli adattamenti dove lo stato lo chiede.",
          "3/5, flessibile: adatta bersagli, posture e operazioni alla situazione anche lontano dal"
              + " piano, se ha senso.",
          "4/5, intraprendente: cerca il colpo che il nemico non si aspetta (fronte secondario,"
              + " sbarco anticipato, contrasto mirato), con rischi calcolati.",
          "5/5, sorprendente: finte e manovre inattese sono benvenute (ammassa su un fronte per"
              + " colpire altrove, operazioni in attesa come esca), sempre entro i paletti.");

  /** The fixed part for the plans of {@code strategia} (the strategy file of the game). */
  public static String sistema(final JsonObject strategia) {
    return template()
        .replace("{{PIANI}}", "```json\n" + compact(piani(strategia)) + "\n```")
        .replace("{{PALETTI}}", paletti())
        .replace(
            "{{SCHEMA}}",
            new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(schema()));
  }

  /** The variable part: fantasy level and the strategic state of the round. */
  public static String utente(final JsonObject stato, final int fantasia) {
    final int f = Math.max(1, Math.min(5, fantasia));
    return "Livello di Fantasia: "
        + FANTASIA.get(f - 1)
        + "\n\nStato strategico del round "
        + (stato.has("round") ? stato.get("round").getAsString() : "?")
        + " per l'alleanza "
        + (stato.has("alleanza") ? stato.get("alleanza").getAsString() : "?")
        + " (JSON, schema v1):\n"
        + compact(stato)
        + "\n\nRispondi solo con il JSON delle direttive.";
  }

  /**
   * The answer's JSON schema (strict structured output: every object closed and fully required,
   * no numeric ranges; the ranges are guard rails checked by the game). Maps of the directives v1
   * ({@code rischio}, {@code ruoli}) are lists of pairs here, converted by {@link UscitaLlm}.
   */
  public static JsonObject schema() {
    final JsonObject root =
        object(
            "piano", string("id di un piano dell'alleanza (in vigore o ammesso)"),
            "postura", enumOf(Direttive.POSTURE),
            "rischio",
                array(
                    object(
                        "fronte", string("nome di un fronte"),
                        "valore", intEnum(-1, 0, 1))),
            "operazioni",
                array(
                    object(
                        "id", string("nome dell'operazione"),
                        "decisione", enumOf(Direttive.DECISIONI),
                        "perdita_trasporti_accettata_max_TUV", type("number"))),
            "bersagli",
                array(
                    object(
                        "territorio", string("territorio o zona di mare"),
                        "peso", type("number"),
                        "origine",
                            enumOf(
                                List.of(Direttive.ORIGINE_PIANO, Direttive.ORIGINE_CONTROMOSSA)),
                        "critico", type("boolean"))),
            "tattiche",
                array(
                    object(
                        "strumento", enumOf(Tattiche.STRUMENTI),
                        "nazione", string("nazione dell'alleanza"),
                        "fronte", string("fronte, vuoto = tutti"),
                        "attiva", type("boolean"))),
            "ruoli", array(object("nazione", type("string"), "ruolo", type("string"))),
            "lettura_nemico",
                object("piano", string("id del piano nemico"), "probabilita", type("number")),
            "motivazione", string("max 3 frasi in italiano"));
    return root;
  }

  /** The plans of the data file, without the logistics (the HOW). */
  static JsonObject piani(final JsonObject strategia) {
    final JsonObject out = new JsonObject();
    if (strategia.has("fronti")) {
      final JsonArray fronts = new JsonArray();
      for (final JsonElement f : strategia.getAsJsonArray("fronti")) {
        fronts.add(f.getAsJsonObject().get("nome"));
      }
      out.add("fronti", fronts);
    }
    final JsonObject alliances = new JsonObject();
    if (strategia.has("alleanze")) {
      for (final var a : strategia.getAsJsonObject("alleanze").entrySet()) {
        final JsonObject in = a.getValue().getAsJsonObject();
        final JsonObject alliance = new JsonObject();
        copy(in, alliance, "automatico");
        final JsonArray plans = new JsonArray();
        for (final JsonElement p : in.getAsJsonArray("piani")) {
          plans.add(plan(p.getAsJsonObject()));
        }
        alliance.add("piani", plans);
        alliances.add(a.getKey(), alliance);
      }
    }
    out.add("alleanze", alliances);
    return out;
  }

  private static JsonObject plan(final JsonObject p) {
    final JsonObject q = new JsonObject();
    for (final String k :
        List.of(
            "id",
            "nome",
            "descrizione",
            "sperimentale",
            "contromosse",
            "subentri",
            "contrasta",
            "ruoli",
            "ritarda")) {
      copy(p, q, k);
    }
    final JsonArray phases = new JsonArray();
    if (p.has("fasi")) {
      for (final JsonElement f : p.getAsJsonArray("fasi")) {
        final JsonObject phase = new JsonObject();
        for (final String k : List.of("da_round", "a_round", "fronti", "obiettivi", "dichiarazioni")) {
          copy(f.getAsJsonObject(), phase, k);
        }
        phases.add(phase);
      }
    }
    q.add("fasi", phases);
    copy(p, q, "tappe");
    q.add("firma", names(p, "firma", "indizio"));
    q.add("punti_critici", names(p, "punti_critici", "descrizione"));
    final JsonArray ops = new JsonArray();
    if (p.has("operazioni_anfibie")) {
      for (final JsonElement o : p.getAsJsonArray("operazioni_anfibie")) {
        final JsonObject op = new JsonObject();
        for (final String k :
            List.of(
                "nome",
                "nazioni",
                "vettori",
                "da_round",
                "a_round",
                "raccolta",
                "bersagli",
                "trasporti",
                "scorta_minima",
                "critico")) {
          copy(o.getAsJsonObject(), op, k);
        }
        ops.add(op);
      }
    }
    q.add("operazioni", ops);
    return q;
  }

  private static JsonArray names(final JsonObject p, final String list, final String key) {
    final JsonArray out = new JsonArray();
    if (p.has(list)) {
      for (final JsonElement e : p.getAsJsonArray(list)) {
        if (e.isJsonObject() && e.getAsJsonObject().has(key)) {
          out.add(e.getAsJsonObject().get(key));
        }
      }
    }
    return out;
  }

  static String paletti() {
    return String.format(
        Locale.ROOT,
        "- guarnigione della capitale: decisa dal motore, nessuna direttiva la tocca;%n"
            + "- nessun attacco sotto il %.0f%% di probabilita' di vittoria;%n"
            + "- perdita di trasporti accettata: al massimo %.0f TUV per alleanza per round, sommando"
            + " tutti gli sbarchi di tutte le nazioni (le tue perdite accettate oltre il tetto vengono"
            + " ridotte in proporzione);%n"
            + "- rischio per fronte tra -1 e +1; peso dei bersagli tra 0 e %.0f; probabilita' tra 0 e"
            + " 1; motivazione al massimo %d frasi;%n"
            + "- cambi di piano: al massimo %d per partita e almeno %d round tra un cambio e l'altro,"
            + " solo verso un piano ammesso.",
        Direttive.SOGLIA_MINIMA_ASSOLUTA,
        Direttive.PERDITA_TRASPORTI_MAX,
        Direttive.PESO_MAX,
        Direttive.FRASI_MAX,
        Direttive.MAX_CAMBI,
        Direttive.ROUND_TRA_CAMBI);
  }

  private static String template() {
    try (InputStream in = PromptStratega.class.getClassLoader().getResourceAsStream(RISORSA)) {
      if (in == null) {
        throw new ErroreStratega("prompt dello stratega mancante (" + RISORSA + ")");
      }
      final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      // the leading HTML comment is for maintainers only
      return text.startsWith("<!--") ? text.substring(text.indexOf("-->") + 3).strip() : text;
    } catch (final IOException e) {
      throw new ErroreStratega("prompt dello stratega non leggibile", e);
    }
  }

  static String compact(final JsonElement json) {
    return new GsonBuilder().disableHtmlEscaping().create().toJson(json);
  }

  private static void copy(final JsonObject from, final JsonObject to, final String key) {
    if (from.has(key)) {
      to.add(key, from.get(key).deepCopy());
    }
  }

  // ------------------------------------------------------------------------------- schema helpers

  private static JsonObject object(final Object... pairs) {
    final JsonObject o = type("object");
    final JsonObject props = new JsonObject();
    final JsonArray required = new JsonArray();
    for (int i = 0; i < pairs.length; i += 2) {
      props.add((String) pairs[i], (JsonObject) pairs[i + 1]);
      required.add((String) pairs[i]);
    }
    o.add("properties", props);
    o.add("required", required);
    o.addProperty("additionalProperties", false);
    return o;
  }

  private static JsonObject array(final JsonObject items) {
    final JsonObject o = type("array");
    o.add("items", items);
    return o;
  }

  private static JsonObject type(final String type) {
    final JsonObject o = new JsonObject();
    o.addProperty("type", type);
    return o;
  }

  private static JsonObject string(final String description) {
    final JsonObject o = type("string");
    o.addProperty("description", description);
    return o;
  }

  private static JsonObject enumOf(final List<String> values) {
    final JsonObject o = type("string");
    final JsonArray e = new JsonArray();
    values.forEach(e::add);
    o.add("enum", e);
    return o;
  }

  private static JsonObject intEnum(final int... values) {
    final JsonObject o = type("integer");
    final JsonArray e = new JsonArray();
    for (final int v : values) {
      e.add(new JsonPrimitive(v));
    }
    o.add("enum", e);
    return o;
  }
}
