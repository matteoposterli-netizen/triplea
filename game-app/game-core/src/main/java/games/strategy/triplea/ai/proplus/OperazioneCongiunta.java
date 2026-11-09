package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;

/**
 * A hand-written joint operation of two nations of an alliance (step PROVA DI VALORE): one target,
 * fixed roles (the nation that opens weakens the target, the one that closes takes it in the same
 * round), a target round fixed at the start of the game from what is feasible, reserves bought and
 * gathered beforehand. Definitions in {@code proplus/strategia/operazioni-congiunte.json}; the
 * engine is {@link OperazioniCongiunte}.
 *
 * <p>Off by default: on only with the game property {@code "Pro+ operazione congiunta: <alleanza>"}
 * = id of an operation of that alliance. The state of the operation is saved in the game ({@code
 * "Pro+ operazione congiunta stato: <id>"}, JSON with JDK strings only).
 */
public final class OperazioneCongiunta {
  /** Game property (per alliance) that turns an operation on: its id, or nothing. */
  public static final String FLAG_PREFIX = "Pro+ operazione congiunta: ";

  /** Game property (per operation id) holding its state (JSON). */
  public static final String STATE_PREFIX = "Pro+ operazione congiunta stato: ";

  static final String RESOURCE = "proplus/strategia/operazioni-congiunte.json";

  private static @Nullable List<Definizione> cache;

  private OperazioneCongiunta() {}

  /** States of an operation. */
  public enum Stato {
    PREPARAZIONE,
    POSIZIONAMENTO,
    ESECUZIONE,
    COMPLETATA,
    ANNULLATA;

    public String testo() {
      return name().toLowerCase(Locale.ROOT);
    }

    public boolean isFinale() {
      return this == COMPLETATA || this == ANNULLATA;
    }

    static Stato of(final String text) {
      return valueOf(text.toUpperCase(Locale.ROOT));
    }
  }

  /** At least {@code minimo} units of {@code tipi} (in rotation), category {@code categoria}. */
  public record Acquisto(String categoria, List<String> tipi, int minimo) {}

  /**
   * What a nation buys for the operation while it prepares: mandatory purchases, where it places
   * them first, percent of the PUs reserved ({@code quota}: urgent defense may spend the rest).
   */
  public record Riserva(List<Acquisto> acquisti, List<String> piazzamento, double quota) {}

  /**
   * A shuttle of {@code nazione}'s troops: loaded at {@code territorio} from one of {@code zone},
   * unloaded at {@code arrivo} from {@code zonaArrivo}.
   */
  public record Porto(
      String territorio, List<String> zone, String arrivo, String zonaArrivo, String nazione) {}

  /**
   * One operation of the data file.
   *
   * @param raccoltaApre where the opener's units gather (land; for planes: where they strike from)
   * @param raccoltaChiude where the closer's units gather
   * @param zonaRaccolta sea zone of the transports for the landing ("" = no landing)
   * @param accesso enemy territories the closer must take first to reach the target (priority)
   * @param aereiApre planes of the opener gathered for the first step (0 = none)
   * @param trasporti transports per nation the operation wants near the gathering zone
   * @param guerraDaRound first round a nation may declare war on the target's owner (map rule)
   * @param valore PUs the alliance gains taking the target (expected value of the operation)
   * @param tettoPerdita highest TUV the opener may expect to lose in the first step
   * @param tolleranzaDifensori second step: defenders left may exceed the estimate by this share
   * @param sogliaChiusura second step: lowest win% to attack
   * @param maxRinvii postponements of one round before the operation is cancelled
   */
  public record Definizione(
      String id,
      String nome,
      String alleanza,
      String bersaglio,
      String apre,
      String chiude,
      List<String> raccoltaApre,
      List<String> raccoltaChiude,
      String zonaRaccolta,
      List<String> accesso,
      Optional<Porto> porto,
      int aereiApre,
      int roundMin,
      int roundMax,
      Map<String, Integer> trasporti,
      Map<String, Integer> guerraDaRound,
      Map<String, Riserva> riserve,
      double valore,
      double tettoPerdita,
      double tolleranzaDifensori,
      double sogliaChiusura,
      int maxRinvii) {

    public boolean riguarda(final String nation) {
      return apre.equals(nation) || chiude.equals(nation);
    }

    public boolean isApre(final String nation) {
      return apre.equals(nation);
    }

    public boolean isChiude(final String nation) {
      return chiude.equals(nation);
    }
  }

  // ------------------------------------------------------------------------------- definitions

  /** Every operation of the data file (cached). */
  public static synchronized List<Definizione> definizioni() {
    if (cache == null) {
      cache = load();
    }
    return cache;
  }

  public static Optional<Definizione> definizione(final String id) {
    return definizioni().stream().filter(d -> d.id().equals(id)).findFirst();
  }

  /** Ids of the operations, sorted. */
  public static Set<String> ids() {
    final Set<String> ids = new TreeSet<>();
    definizioni().forEach(d -> ids.add(d.id()));
    return ids;
  }

  private static List<Definizione> load() {
    try (InputStream in =
        OperazioneCongiunta.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        return List.of();
      }
      return parse(
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonObject());
    } catch (final IOException | RuntimeException e) {
      throw new IllegalStateException("File delle operazioni congiunte non valido: " + e, e);
    }
  }

  static List<Definizione> parse(final JsonObject root) {
    final List<Definizione> list = new ArrayList<>();
    for (final JsonElement e : root.getAsJsonArray("operazioni")) {
      final JsonObject o = e.getAsJsonObject();
      final Map<String, Riserva> riserve = new LinkedHashMap<>();
      if (o.has("riserve")) {
        o.getAsJsonObject("riserve")
            .entrySet()
            .forEach(r -> riserve.put(r.getKey(), riserva(r.getValue().getAsJsonObject())));
      }
      Optional<Porto> porto = Optional.empty();
      if (o.has("porto")) {
        final JsonObject p = o.getAsJsonObject("porto");
        porto =
            Optional.of(
                new Porto(
                    p.get("territorio").getAsString(),
                    strings(p, "zone"),
                    p.get("arrivo").getAsString(),
                    p.get("zona_arrivo").getAsString(),
                    p.get("nazione").getAsString()));
      }
      list.add(
          new Definizione(
              o.get("id").getAsString(),
              o.get("nome").getAsString(),
              o.get("alleanza").getAsString(),
              o.get("bersaglio").getAsString(),
              o.get("apre").getAsString(),
              o.get("chiude").getAsString(),
              strings(o, "raccolta_apre"),
              strings(o, "raccolta_chiude"),
              o.has("zona_raccolta") ? o.get("zona_raccolta").getAsString() : "",
              strings(o, "accesso"),
              porto,
              o.has("aerei_apre") ? o.get("aerei_apre").getAsInt() : 0,
              o.get("round_min").getAsInt(),
              o.get("round_max").getAsInt(),
              ints(o, "trasporti"),
              ints(o, "guerra_da_round"),
              Map.copyOf(riserve),
              o.get("valore").getAsDouble(),
              o.get("tetto_perdita").getAsDouble(),
              o.get("tolleranza_difensori").getAsDouble(),
              o.get("soglia_chiusura").getAsDouble(),
              o.get("max_rinvii").getAsInt()));
    }
    return List.copyOf(list);
  }

  private static Riserva riserva(final JsonObject r) {
    final List<Acquisto> acquisti = new ArrayList<>();
    if (r.has("acquisti")) {
      for (final JsonElement e : r.getAsJsonArray("acquisti")) {
        final JsonObject a = e.getAsJsonObject();
        acquisti.add(
            new Acquisto(
                a.get("categoria").getAsString(), strings(a, "tipi"), a.get("minimo").getAsInt()));
      }
    }
    return new Riserva(
        List.copyOf(acquisti),
        strings(r, "piazzamento"),
        r.has("quota") ? r.get("quota").getAsDouble() : 0);
  }

  private static List<String> strings(final JsonObject o, final String key) {
    if (!o.has(key)) {
      return List.of();
    }
    final List<String> list = new ArrayList<>();
    o.getAsJsonArray(key).forEach(e -> list.add(e.getAsString()));
    return List.copyOf(list);
  }

  private static Map<String, Integer> ints(final JsonObject o, final String key) {
    if (!o.has(key)) {
      return Map.of();
    }
    final Map<String, Integer> map = new LinkedHashMap<>();
    o.getAsJsonObject(key).entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsInt()));
    return Map.copyOf(map);
  }

  // ------------------------------------------------------------------------------------ switch

  /**
   * Turns on operation {@code id} for {@code alliance} ("" or null = off). Unknown ids, or ids of
   * another alliance, are refused.
   */
  public static void set(
      final GameProperties props, final String alliance, final @Nullable String id) {
    // unbind first: set() appends the key to the property ordering each time
    props.set(FLAG_PREFIX + alliance, (java.io.Serializable) null);
    if (id == null || id.isBlank()) {
      return;
    }
    final Definizione d =
        definizione(id.trim())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Operazione congiunta sconosciuta: " + id + " (note: " + ids() + ")"));
    if (!d.alleanza().equals(alliance)) {
      throw new IllegalArgumentException(
          "L'operazione " + d.id() + " e' dell'alleanza " + d.alleanza() + ", non " + alliance);
    }
    props.set(FLAG_PREFIX + alliance, d.id());
  }

  /** The operation turned on for {@code alliance}, if any. */
  public static Optional<Definizione> attiva(final GameProperties props, final String alliance) {
    final Object value = props.get(FLAG_PREFIX + alliance);
    if (!(value instanceof String id) || id.isBlank()) {
      return Optional.empty();
    }
    return definizione(id).filter(d -> d.alleanza().equals(alliance));
  }

  /** The operation turned on for {@code player}'s alliance, if {@code player} takes part in it. */
  public static Optional<Definizione> per(final GameState data, final GamePlayer player) {
    // cheap check first: no switch on in the game (every game but the operation's tests)
    final GameProperties props = data.getProperties();
    if (definizioni().stream().noneMatch(d -> props.get(FLAG_PREFIX + d.alleanza()) != null)) {
      return Optional.empty();
    }
    return ProPlusSettings.allianceOf(data, player)
        .flatMap(a -> attiva(data.getProperties(), a))
        .filter(d -> d.riguarda(player.getName()));
  }

  // ------------------------------------------------------------------------------------- state

  /** The saved state of an operation (a JSON object; read and written as a whole). */
  public static final class Registro {
    private final String id;
    private final JsonObject json;

    private Registro(final String id, final JsonObject json) {
      this.id = id;
      this.json = json;
    }

    /** The state of operation {@code id} in the game; a new empty one if none yet. */
    public static Registro leggi(final GameProperties props, final String id) {
      final Object value = props.get(STATE_PREFIX + id);
      if (value instanceof String text && !text.isBlank()) {
        try {
          return new Registro(id, JsonParser.parseString(text).getAsJsonObject());
        } catch (final RuntimeException e) {
          // a broken state starts over
        }
      }
      return new Registro(id, new JsonObject());
    }

    public void salva(final GameProperties props) {
      props.set(STATE_PREFIX + id, json.toString());
    }

    public boolean isNuovo() {
      return !json.has("stato");
    }

    public Stato stato() {
      return json.has("stato") ? Stato.of(json.get("stato").getAsString()) : Stato.PREPARAZIONE;
    }

    public void setStato(final Stato stato) {
      json.addProperty("stato", stato.testo());
    }

    public int roundObiettivo() {
      return intOf("round_obiettivo", 0);
    }

    public void setRoundObiettivo(final int round) {
      json.addProperty("round_obiettivo", round);
      if (!json.has("round_obiettivo_iniziale")) {
        json.addProperty("round_obiettivo_iniziale", round);
      }
    }

    public int rinvii() {
      return intOf("rinvii", 0);
    }

    public void setRinvii(final int n) {
      json.addProperty("rinvii", n);
    }

    public int intOf(final String key, final int fallback) {
      return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : fallback;
    }

    public boolean flag(final String key) {
      return json.has(key) && json.get(key).getAsBoolean();
    }

    public void set(final String key, final boolean value) {
      json.addProperty(key, value);
    }

    public void set(final String key, final int value) {
      json.addProperty(key, value);
    }

    public void set(final String key, final String value) {
      json.addProperty(key, value);
    }

    public void set(final String key, final JsonObject value) {
      json.add(key, value);
    }

    public Optional<JsonObject> oggetto(final String key) {
      return json.has(key) && json.get(key).isJsonObject()
          ? Optional.of(json.getAsJsonObject(key))
          : Optional.empty();
    }

    /** Adds an event (round, nation, type, text and its numbers) to the list of the operation. */
    public void evento(final JsonObject event) {
      if (!json.has("eventi")) {
        json.add("eventi", new JsonArray());
      }
      json.getAsJsonArray("eventi").add(event);
    }

    public JsonArray eventi() {
      return json.has("eventi") ? json.getAsJsonArray("eventi") : new JsonArray();
    }

    public JsonObject json() {
      return json;
    }
  }
}
