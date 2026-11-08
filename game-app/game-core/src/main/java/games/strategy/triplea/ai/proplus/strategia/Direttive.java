package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Strategic contract v1: the directives of an alliance for one round, the only source of Pro+'s
 * strategic choices (plan, posture, risk per front, priority targets, amphibious operations, delay
 * tools, enemy reading). Written by a {@link Decisore}, checked by {@link #valida} (schema, ranges,
 * guard rails), saved in the history entry of the round ({@code direttive}) and read by {@link
 * PianoAttivo}.
 *
 * <p>Format (see proplus/strategia/contratto-v1.md): {@code {"versione": 1, "alleanza", "round",
 * "piano", "postura", "rischio": {front: -1..1}, "operazioni": [{"id", "decisione":
 * "lancia|attendi|annulla", "perdita_trasporti_accettata_max_TUV"}], "bersagli": [{"territorio" |
 * "flotta", "peso": 0..20, "nazioni"?, "critico"?, "origine"?}], "tattiche": [{"strumento",
 * "nazione", "fronte" | "zona", "attiva"}], "ruoli": {nation: text}, "lettura_nemico": {"piano",
 * "probabilita"}, "motivazione"}}.
 */
public final class Direttive {
  public static final int VERSIONE = 1;

  /** Operation decision: the operation runs (the default). */
  public static final String LANCIA = "lancia";

  /** Operation decision: gather and wait; no landing on its targets this round. */
  public static final String ATTENDI = "attendi";

  /** Operation decision: off this round (its units go back to the Pro AI). */
  public static final String ANNULLA = "annulla";

  public static final List<String> DECISIONI = List.of(LANCIA, ATTENDI, ANNULLA);

  public static final List<String> POSTURE =
      List.of(
          Contromosse.OFFENSIVA,
          Contromosse.RALLENTAMENTO,
          Contromosse.CONTRASTO,
          Contromosse.DIFENSIVA,
          Contromosse.NESSUNA);

  /** Origin of a target: an objective of the plan's phase (counts in the landings' value). */
  public static final String ORIGINE_PIANO = "piano";

  /** Origin of a target: a counter-objective of the posture. */
  public static final String ORIGINE_CONTROMOSSA = "contromossa";

  /** Highest weight of a target. */
  public static final double PESO_MAX = 20;

  /** Guard rail: lowest attack win% whatever the decider (minimum threshold of any attack). */
  public static final double SOGLIA_MINIMA_ASSOLUTA = 30;

  /** Guard rail: transport loss accepted beyond the safety rule, per alliance and turn (TUV). */
  public static final double PERDITA_TRASPORTI_MAX = 30;

  /** Guard rail: plan changes per game. */
  public static final int MAX_CAMBI = 2;

  /** Guard rail: rounds between two plan changes. */
  public static final int ROUND_TRA_CAMBI = 3;

  /** Most sentences of the motivation. */
  public static final int FRASI_MAX = 3;

  /** An amphibious operation's decision. */
  public record Operazione(String id, String decisione, double perditaAccettata) {}

  /** A priority target: a territory (land or sea zone) or the fleet of a nation. */
  public record Bersaglio(
      @Nullable String territorio,
      @Nullable String flotta,
      double peso,
      Set<String> nazioni,
      boolean critico,
      String origine) {
    Strategia.Obiettivo obiettivo() {
      return new Strategia.Obiettivo(territorio, flotta, peso, nazioni, critico);
    }

    boolean isContromossa() {
      return ORIGINE_CONTROMOSSA.equals(origine);
    }
  }

  private final JsonObject json;
  private final String piano;
  private final String postura;
  private final Map<String, Integer> rischio;
  private final List<Operazione> operazioni;
  private final List<Bersaglio> bersagli;
  private final JsonObject tattiche;
  private final String letturaPiano;
  private final double letturaProbabilita;

  /** Reads directives already checked by {@link #valida} (or written by the rules). */
  public static Direttive of(final JsonObject json) {
    return new Direttive(json);
  }

  private Direttive(final JsonObject json) {
    this.json = json;
    this.piano = string(json, "piano", "");
    this.postura = string(json, "postura", Contromosse.OFFENSIVA);
    final Map<String, Integer> risk = new LinkedHashMap<>();
    if (json.has("rischio") && json.get("rischio").isJsonObject()) {
      json.getAsJsonObject("rischio")
          .entrySet()
          .forEach(e -> risk.put(e.getKey(), e.getValue().getAsInt()));
    }
    this.rischio = Map.copyOf(risk);
    final List<Operazione> ops = new ArrayList<>();
    array(json, "operazioni")
        .forEach(
            e -> {
              final JsonObject o = e.getAsJsonObject();
              ops.add(
                  new Operazione(
                      o.get("id").getAsString(),
                      string(o, "decisione", LANCIA),
                      number(o, "perdita_trasporti_accettata_max_TUV", 0)));
            });
    this.operazioni = List.copyOf(ops);
    final List<Bersaglio> targets = new ArrayList<>();
    array(json, "bersagli").forEach(e -> targets.add(target(e.getAsJsonObject())));
    this.bersagli = List.copyOf(targets);
    this.tattiche = structure(array(json, "tattiche"));
    final JsonObject reading =
        json.has("lettura_nemico") && json.get("lettura_nemico").isJsonObject()
            ? json.getAsJsonObject("lettura_nemico")
            : new JsonObject();
    this.letturaPiano = string(reading, "piano", "");
    this.letturaProbabilita = number(reading, "probabilita", 0);
  }

  public JsonObject json() {
    return json;
  }

  public String piano() {
    return piano;
  }

  public String postura() {
    return postura;
  }

  /** Risk offset of {@code front} (-1..+1, 0 if absent). */
  public int rischio(final String front) {
    return rischio.getOrDefault(front, 0);
  }

  /** The decision on the operation named {@code id} ({@link #LANCIA} with no loss if absent). */
  public Operazione operazione(final String id) {
    return operazioni.stream()
        .filter(o -> o.id().equals(id))
        .findFirst()
        .orElse(new Operazione(id, LANCIA, 0));
  }

  public List<Bersaglio> bersagli() {
    return bersagli;
  }

  /** The delay tools on, in the {@link Tattiche} structure. */
  public JsonObject tattiche() {
    return tattiche;
  }

  public String letturaPiano() {
    return letturaPiano;
  }

  public double letturaProbabilita() {
    return letturaProbabilita;
  }

  public String motivazione() {
    return string(json, "motivazione", "");
  }

  private static Bersaglio target(final JsonObject o) {
    final Set<String> nations = new LinkedHashSet<>();
    array(o, "nazioni").forEach(n -> nations.add(n.getAsString()));
    return new Bersaglio(
        o.has("territorio") ? o.get("territorio").getAsString() : null,
        o.has("flotta") ? o.get("flotta").getAsString() : null,
        number(o, "peso", 0),
        Set.copyOf(nations),
        o.has("critico") && o.get("critico").getAsBoolean(),
        string(o, "origine", ORIGINE_PIANO));
  }

  /** [{strumento, nazione, fronte?, attiva}] -> {tool: {nation: {fronti, perche}}}. */
  static JsonObject structure(final JsonArray list) {
    final JsonObject json = Tattiche.spente();
    for (final JsonElement e : list) {
      final JsonObject t = e.getAsJsonObject();
      if (t.has("attiva") && !t.get("attiva").getAsBoolean()) {
        continue;
      }
      final JsonObject nations = json.getAsJsonObject(t.get("strumento").getAsString());
      final String nation = t.get("nazione").getAsString();
      final JsonObject o;
      if (nations.has(nation)) {
        o = nations.getAsJsonObject(nation);
      } else {
        o = new JsonObject();
        o.add("fronti", new JsonArray());
        o.addProperty("perche", t.has("perche") ? t.get("perche").getAsString() : "direttiva");
        o.addProperty("tutti", false);
        nations.add(nation, o);
      }
      if (t.has("fronte")) {
        o.getAsJsonArray("fronti").add(t.get("fronte").getAsString());
      } else {
        o.addProperty("tutti", true);
      }
    }
    // a switch with no front (or one without front) is on every front: empty list
    for (final String tool : Tattiche.STRUMENTI) {
      json.getAsJsonObject(tool)
          .entrySet()
          .forEach(
              n -> {
                final JsonObject o = n.getValue().getAsJsonObject();
                if (o.remove("tutti").getAsBoolean()) {
                  o.add("fronti", new JsonArray());
                }
              });
    }
    return json;
  }

  // ---------------------------------------------------------------------------- validation

  /**
   * The outcome of the check of a decider's directives.
   *
   * @param direttive the directives in force (the decider's, limited; or the rules' on fallback)
   * @param ripiego whether the rules' directives replaced invalid or missing ones
   * @param avvisi what was limited or why the fallback (Italian text)
   */
  public record Validazione(JsonObject direttive, boolean ripiego, List<String> avvisi) {}

  /**
   * Checks {@code raw} against the schema v1 and the guard rails. Fields absent from {@code raw}
   * take the value of {@code base} (the rules' directives, or the plan's base orders when {@code
   * raw} changes plan). Invalid directives (not an object, unknown plan or posture, a plan change
   * not admitted) are replaced by {@code regole}.
   *
   * @param regole the rules' directives (already valid)
   */
  public static Validazione valida(
      final Optional<JsonObject> raw,
      final Decisore.Contesto ctx,
      final JsonObject regole,
      final String decisore) {
    final List<String> notes = new ArrayList<>();
    if (raw.isEmpty()) {
      notes.add("nessuna direttiva dal decisore " + decisore + ": uso le regole");
      return new Validazione(regole, true, notes);
    }
    try {
      final Optional<String> error = check(raw.get(), ctx, regole, notes);
      if (error.isPresent()) {
        final List<String> all = new ArrayList<>();
        all.add(
            "direttive del decisore "
                + decisore
                + " non valide ("
                + error.get()
                + "): uso le regole");
        return new Validazione(regole, true, all);
      }
      return new Validazione(normalize(raw.get(), ctx, regole, notes), false, notes);
    } catch (final RuntimeException e) {
      return new Validazione(
          regole,
          true,
          List.of(
              "direttive del decisore "
                  + decisore
                  + " non valide ("
                  + e.getMessage()
                  + "): uso le regole"));
    }
  }

  /** The errors that make directives unusable (fallback), empty if none. */
  private static Optional<String> check(
      final JsonObject raw,
      final Decisore.Contesto ctx,
      final JsonObject regole,
      final List<String> notes) {
    if (raw.has("versione") && raw.get("versione").getAsInt() != VERSIONE) {
      return Optional.of("versione " + raw.get("versione") + " invece di " + VERSIONE);
    }
    final String planId = raw.has("piano") ? raw.get("piano").getAsString() : ctx.piano().id();
    final Optional<Strategia.Piano> plan =
        ctx.strategia().alleanza(ctx.alleanza()).flatMap(a -> a.piano(planId));
    if (plan.isEmpty()) {
      return Optional.of("piano sconosciuto " + planId);
    }
    if (!planId.equals(ctx.piano().id())) {
      final Optional<String> notAdmitted = cambioNonAmmesso(ctx, planId);
      if (notAdmitted.isPresent()) {
        return notAdmitted;
      }
    }
    if (raw.has("postura") && !POSTURE.contains(raw.get("postura").getAsString())) {
      return Optional.of("postura sconosciuta " + raw.get("postura").getAsString());
    }
    return Optional.empty();
  }

  /**
   * Why the change of the plan in force to {@code planId} is not admitted now (subentri of the
   * plan, guard rails: at most {@link #MAX_CAMBI} changes, {@link #ROUND_TRA_CAMBI} rounds between
   * two), empty if admitted.
   */
  static Optional<String> cambioNonAmmesso(final Decisore.Contesto ctx, final String planId) {
    if (!ctx.piano().subentri().contains(planId)) {
      return Optional.of(
          "cambio "
              + ctx.piano().id()
              + " -> "
              + planId
              + " non ammesso (subentri "
              + ctx.piano().subentri()
              + ")");
    }
    final StatoMaggiore.Stato state = ctx.stato();
    final int max = Math.min(MAX_CAMBI, ctx.strategia().getParametri().maxCambi());
    if (state.cambi().size() >= max) {
      return Optional.of("gia' fatti " + max + " cambi di piano");
    }
    final int gap = Math.max(ROUND_TRA_CAMBI, ctx.strategia().getParametri().inerziaRound());
    if (!state.cambi().isEmpty() && ctx.round() - state.roundPiano() < gap) {
      return Optional.of(
          "ultimo cambio al round "
              + state.roundPiano()
              + " (almeno "
              + gap
              + " round tra due cambi)");
    }
    return Optional.empty();
  }

  /** The directives with every field present, values limited, guard rails applied. */
  private static JsonObject normalize(
      final JsonObject raw,
      final Decisore.Contesto ctx,
      final JsonObject regole,
      final List<String> notes) {
    final GameState data = ctx.data();
    final String planId = raw.has("piano") ? raw.get("piano").getAsString() : ctx.piano().id();
    final Strategia.Piano plan =
        ctx.strategia().alleanza(ctx.alleanza()).flatMap(a -> a.piano(planId)).orElseThrow();
    final JsonObject base =
        planId.equals(string(regole, "piano", ""))
            ? regole
            : DecisoreRegole.base(ctx.strategia(), ctx.alleanza(), plan, ctx.round());
    final JsonObject out = new JsonObject();
    out.addProperty("versione", VERSIONE);
    out.addProperty("alleanza", ctx.alleanza());
    out.addProperty("round", ctx.round());
    out.addProperty("piano", planId);
    String posture = pick(raw, base, "postura").getAsString();
    if (!plan.contromosse() && !Contromosse.NESSUNA.equals(posture)) {
      notes.add("il piano " + plan.label() + " non ha contromosse: postura NESSUNA");
      posture = Contromosse.NESSUNA;
    }
    out.addProperty("postura", posture);
    out.add("rischio", risk(pick(raw, base, "rischio").getAsJsonObject(), ctx.strategia(), notes));
    out.add(
        "operazioni",
        operations(pick(raw, base, "operazioni").getAsJsonArray(), plan, ctx.round(), notes));
    out.add(
        "bersagli",
        targets(pick(raw, base, "bersagli").getAsJsonArray(), data, ctx.alleanza(), notes));
    out.add("tattiche", tools(pick(raw, base, "tattiche").getAsJsonArray(), ctx, notes));
    out.add("ruoli", pick(raw, base, "ruoli").getAsJsonObject().deepCopy());
    out.add("lettura_nemico", reading(pick(raw, base, "lettura_nemico").getAsJsonObject(), notes));
    out.addProperty("motivazione", motivation(pick(raw, base, "motivazione").getAsString(), notes));
    return out;
  }

  private static JsonElement pick(final JsonObject raw, final JsonObject base, final String key) {
    if (raw.has(key) && !raw.get(key).isJsonNull()) {
      return raw.get(key);
    }
    return base.has(key) ? base.get(key) : defaultOf(key);
  }

  private static JsonElement defaultOf(final String key) {
    return switch (key) {
      case "rischio", "ruoli", "lettura_nemico" -> new JsonObject();
      case "operazioni", "bersagli", "tattiche" -> new JsonArray();
      case "postura" -> new JsonPrimitive(Contromosse.OFFENSIVA);
      default -> new JsonPrimitive("");
    };
  }

  private static JsonObject risk(
      final JsonObject in, final Strategia strategia, final List<String> notes) {
    final JsonObject out = new JsonObject();
    for (final String front : strategia.getFronti()) {
      if (!in.has(front)) {
        out.addProperty(front, 0);
        continue;
      }
      final double v = in.get(front).getAsDouble();
      final int limited = (int) Math.max(-1, Math.min(1, Math.round(v)));
      if (limited != v) {
        notes.add(
            String.format(Locale.ROOT, "rischio %s %s limitato a %+d", front, fmt(v), limited));
      }
      out.addProperty(front, limited);
    }
    in.keySet().stream()
        .filter(f -> !strategia.getFronti().contains(f))
        .forEach(f -> notes.add("rischio: fronte sconosciuto " + f + " ignorato"));
    return out;
  }

  private static JsonArray operations(
      final JsonArray in, final Strategia.Piano plan, final int round, final List<String> notes) {
    final Map<String, JsonObject> byId = new LinkedHashMap<>();
    for (final JsonElement e : in) {
      final JsonObject o = e.getAsJsonObject();
      final String id = string(o, "id", "");
      if (plan.operazioni().stream().noneMatch(op -> op.nome().equals(id))) {
        notes.add("operazione sconosciuta \"" + id + "\" ignorata");
        continue;
      }
      String decision = string(o, "decisione", LANCIA);
      if (!DECISIONI.contains(decision)) {
        notes.add("operazione " + id + ": decisione \"" + decision + "\" sconosciuta, uso lancia");
        decision = LANCIA;
      }
      double loss = number(o, "perdita_trasporti_accettata_max_TUV", 0);
      if (loss < 0 || loss > PERDITA_TRASPORTI_MAX) {
        final double limited = Math.max(0, Math.min(PERDITA_TRASPORTI_MAX, loss));
        notes.add(
            String.format(
                Locale.ROOT,
                "operazione %s: perdita accettata %s limitata a %s",
                id,
                fmt(loss),
                fmt(limited)));
        loss = limited;
      }
      final JsonObject out = new JsonObject();
      out.addProperty("id", id);
      out.addProperty("decisione", decision);
      out.addProperty("perdita_trasporti_accettata_max_TUV", loss);
      byId.put(id, out);
    }
    // operations of the plan in this round not named: launched with no loss accepted
    plan.operazioni().stream()
        .filter(op -> op.covers(round) && !byId.containsKey(op.nome()))
        .forEach(
            op -> {
              final JsonObject out = new JsonObject();
              out.addProperty("id", op.nome());
              out.addProperty("decisione", LANCIA);
              out.addProperty("perdita_trasporti_accettata_max_TUV", 0);
              byId.put(op.nome(), out);
            });
    // guard rail: the accepted losses of the alliance's operations add up to at most the cap
    final double sum =
        byId.values().stream()
            .filter(o -> LANCIA.equals(o.get("decisione").getAsString()))
            .mapToDouble(o -> o.get("perdita_trasporti_accettata_max_TUV").getAsDouble())
            .sum();
    if (sum > PERDITA_TRASPORTI_MAX) {
      final double factor = PERDITA_TRASPORTI_MAX / sum;
      byId.values()
          .forEach(
              o ->
                  o.addProperty(
                      "perdita_trasporti_accettata_max_TUV",
                      Math.floor(
                              o.get("perdita_trasporti_accettata_max_TUV").getAsDouble()
                                  * factor
                                  * 10)
                          / 10));
      notes.add(
          String.format(
              Locale.ROOT,
              "perdite di trasporti accettate %s TUV in totale: ridotte al tetto di %s per alleanza",
              fmt(sum),
              fmt(PERDITA_TRASPORTI_MAX)));
    }
    final JsonArray out = new JsonArray();
    byId.values().forEach(out::add);
    return out;
  }

  private static JsonArray targets(
      final JsonArray in, final GameState data, final String alliance, final List<String> notes) {
    final Set<String> nations = allianceNations(data, alliance);
    final JsonArray out = new JsonArray();
    for (final JsonElement e : in) {
      final JsonObject o = e.getAsJsonObject();
      final JsonObject t = new JsonObject();
      final String name =
          o.has("territorio")
              ? o.get("territorio").getAsString()
              : o.has("zona") ? o.get("zona").getAsString() : null;
      if (name != null) {
        if (data.getMap().getTerritoryOrNull(name) == null) {
          notes.add("bersaglio sconosciuto \"" + name + "\" ignorato");
          continue;
        }
        t.addProperty("territorio", name);
      } else if (o.has("flotta")) {
        final String fleet = o.get("flotta").getAsString();
        if (data.getPlayerList().getPlayerId(fleet) == null) {
          notes.add("flotta di una nazione sconosciuta \"" + fleet + "\" ignorata");
          continue;
        }
        t.addProperty("flotta", fleet);
      } else {
        notes.add("bersaglio senza territorio ne' flotta ignorato");
        continue;
      }
      final double weight = number(o, "peso", 0);
      final double limited = Math.max(0, Math.min(PESO_MAX, weight));
      if (limited != weight) {
        notes.add(
            String.format(
                Locale.ROOT,
                "peso di %s %s limitato a %s",
                name != null ? name : "flotta",
                fmt(weight),
                fmt(limited)));
      }
      t.addProperty("peso", limited);
      if (o.has("nazioni")) {
        final JsonArray list = new JsonArray();
        array(o, "nazioni")
            .forEach(
                n -> {
                  if (nations.contains(n.getAsString())) {
                    list.add(n.getAsString());
                  } else {
                    notes.add(
                        "bersaglio "
                            + (name != null ? name : "flotta")
                            + ": nazione "
                            + n.getAsString()
                            + " non dell'alleanza ignorata");
                  }
                });
        if (!list.isEmpty()) {
          t.add("nazioni", list);
        }
      }
      if (o.has("critico") && o.get("critico").getAsBoolean()) {
        t.addProperty("critico", true);
      }
      t.addProperty("origine", string(o, "origine", "direttiva"));
      if (o.has("punto_critico")) {
        t.addProperty("punto_critico", o.get("punto_critico").getAsString());
      }
      out.add(t);
    }
    return out;
  }

  private static JsonArray tools(
      final JsonArray in, final Decisore.Contesto ctx, final List<String> notes) {
    final Set<String> nations = allianceNations(ctx.data(), ctx.alleanza());
    final JsonArray out = new JsonArray();
    for (final JsonElement e : in) {
      final JsonObject o = e.getAsJsonObject();
      final String tool = string(o, "strumento", "");
      final String nation = string(o, "nazione", "");
      if (!Tattiche.STRUMENTI.contains(tool)) {
        notes.add("strumento sconosciuto \"" + tool + "\" ignorato");
        continue;
      }
      if (!nations.contains(nation)) {
        notes.add("strumento " + tool + ": nazione " + nation + " non dell'alleanza ignorata");
        continue;
      }
      final JsonObject t = new JsonObject();
      t.addProperty("strumento", tool);
      t.addProperty("nazione", nation);
      String front = o.has("fronte") ? o.get("fronte").getAsString() : null;
      if (front == null && o.has("zona")) {
        final Territory zone = ctx.data().getMap().getTerritoryOrNull(o.get("zona").getAsString());
        if (zone == null) {
          notes.add(
              "strumento "
                  + tool
                  + ": zona sconosciuta "
                  + o.get("zona").getAsString()
                  + " ignorata");
          continue;
        }
        front = ctx.strategia().fronteOf(zone);
        t.addProperty("zona", zone.getName());
      }
      if (front != null) {
        if (!ctx.strategia().getFronti().contains(front)) {
          notes.add("strumento " + tool + ": fronte sconosciuto " + front + " ignorato");
          continue;
        }
        t.addProperty("fronte", front);
      }
      t.addProperty("attiva", !o.has("attiva") || o.get("attiva").getAsBoolean());
      t.addProperty("perche", o.has("perche") ? o.get("perche").getAsString() : "direttiva");
      out.add(t);
    }
    return out;
  }

  private static JsonObject reading(final JsonObject in, final List<String> notes) {
    final JsonObject out = new JsonObject();
    out.addProperty("piano", string(in, "piano", ""));
    final double p = number(in, "probabilita", 0);
    final double limited = Math.max(0, Math.min(1, p));
    if (limited != p) {
      notes.add("probabilita' della lettura " + fmt(p) + " limitata a " + fmt(limited));
    }
    out.addProperty("probabilita", limited);
    return out;
  }

  /** At most {@link #FRASI_MAX} sentences. */
  static String motivation(final String text, final List<String> notes) {
    final String t = text.strip();
    int end = -1;
    int count = 0;
    for (int i = 0; i < t.length(); i++) {
      final char c = t.charAt(i);
      if ((c == '.' || c == '!' || c == '?')
          && (i + 1 == t.length() || Character.isWhitespace(t.charAt(i + 1)))) {
        count++;
        if (count == FRASI_MAX) {
          end = i + 1;
          break;
        }
      }
    }
    if (end > 0 && end < t.length()) {
      notes.add("motivazione oltre " + FRASI_MAX + " frasi: tagliata");
      return t.substring(0, end);
    }
    return t;
  }

  static Set<String> allianceNations(final GameState data, final String alliance) {
    final Set<String> set = new LinkedHashSet<>();
    data.getAllianceTracker()
        .getPlayersInAlliance(alliance)
        .forEach((GamePlayer p) -> set.add(p.getName()));
    return set;
  }

  // ------------------------------------------------------------------------------- reading

  /** Eg "Direttive (regole): piano 1, postura OFFENSIVA, ...; motivazione: ...". */
  public static String testo(final JsonObject d, final String decisore, final boolean fallback) {
    final Direttive dir = of(d);
    final StringBuilder sb =
        new StringBuilder("  Direttive (")
            .append(decisore)
            .append(fallback ? ", ripiego sulle regole" : "")
            .append("): piano ")
            .append(dir.piano())
            .append(", postura ")
            .append(dir.postura());
    final List<String> risk = new ArrayList<>();
    dir.rischio.forEach(
        (f, v) -> {
          if (v != 0) {
            risk.add(f + " " + (v > 0 ? "+" : "") + v);
          }
        });
    sb.append(", rischio ").append(risk.isEmpty() ? "base" : String.join(" ", risk));
    final List<String> ops = new ArrayList<>();
    dir.operazioni.forEach(
        o ->
            ops.add(
                o.id()
                    + " "
                    + o.decisione()
                    + (o.perditaAccettata() > 0
                        ? " (perdita max " + fmt(o.perditaAccettata()) + ")"
                        : "")));
    if (!ops.isEmpty()) {
      sb.append(", operazioni ").append(String.join(", ", ops));
    }
    sb.append(", bersagli ").append(dir.bersagli.size());
    final String tools = Tattiche.testo(dir.tattiche);
    sb.append(tools.endsWith("nessuna") ? ", tattiche nessuna" : ";" + tools.substring(1));
    if (!dir.motivazione().isEmpty()) {
      sb.append("\n    Motivazione: ").append(dir.motivazione());
    }
    return sb.append('\n').toString();
  }

  // ------------------------------------------------------------------------------- helpers

  static String string(final JsonObject o, final String key, final String fallback) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
  }

  static double number(final JsonObject o, final String key, final double fallback) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallback;
  }

  static JsonArray array(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
  }

  static String fmt(final double v) {
    return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
  }
}
