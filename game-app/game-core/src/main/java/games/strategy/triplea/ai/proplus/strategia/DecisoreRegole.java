package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;

/**
 * The decider by rules (default): the general staff of steps 3a-3d written as directives. Plan
 * change ({@link Contromosse#cambio}), posture and counter-objectives ({@link
 * Contromosse#statoStrategico}), risk per front and objectives from the plan's phase in the data
 * file, every operation of the plan launched with no transport loss accepted beyond the safety
 * rule, delay tools all off (decision of Matteo on step 3d-2), the plan's roles, the enemy reading
 * of the round.
 *
 * <p>It reads the game through the {@link Decisore.Contesto} (its rules were written on the game
 * data) and changes nothing: the plan change is simulated, {@link Valutazione#inizioRound} applies
 * it.
 */
public final class DecisoreRegole implements Decisore {

  /**
   * What the rules decided.
   *
   * @param piano the plan in force after the decision
   * @param direttive the directives (schema v1, valid)
   * @param analisi the general staff's strategic state with that plan ({@code stato_strategico}:
   *     race, posture and why, enemy critical points, plan change check)
   */
  public record Esito(Strategia.Piano piano, JsonObject direttive, JsonObject analisi) {}

  @Override
  public String nome() {
    return REGOLE;
  }

  @Override
  public Optional<JsonObject> decidi(final JsonObject stato, final Contesto contesto) {
    return Optional.of(esito(contesto).direttive());
  }

  /** The rules' decision for {@code ctx}. */
  public static Esito esito(final Contesto ctx) {
    final Contromosse.Cambio change = ctx.cambio();
    final Strategia.Piano plan = change.nuovo().orElse(ctx.piano());
    return esito(ctx, plan, change);
  }

  /**
   * The rules' strategic state and directives with {@code plan} in force ({@code change} = how it
   * got there; the plan in force if no change).
   */
  static Esito esito(
      final Contesto ctx, final Strategia.Piano plan, final Contromosse.Cambio change) {
    final StatoMaggiore.Stato before = ctx.stato();
    JsonObject entry = ctx.storico();
    StatoMaggiore.Stato now = before;
    if (!plan.id().equals(ctx.piano().id())) {
      // as StatoMaggiore.cambia would write it, and the progress measured with the new plan
      final JsonArray changes = before.cambi().deepCopy();
      final JsonObject c = new JsonObject();
      c.addProperty("round", ctx.round());
      c.addProperty("da", before.piano());
      c.addProperty("a", plan.id());
      c.addProperty("motivo", change.motivo());
      changes.add(c);
      now = new StatoMaggiore.Stato(plan.id(), before.roundInizio(), before.storico(), changes);
      entry =
          Valutazione.valuta(
                  ctx.data(),
                  ctx.alleanza(),
                  ctx.strategia(),
                  plan,
                  before.storico(),
                  ctx.round(),
                  ctx.round())
              .storico();
    }
    final JsonObject analysis =
        Contromosse.statoStrategico(
            ctx.data(), ctx.alleanza(), ctx.strategia(), plan, now, entry, change, ctx.round());
    final JsonObject d =
        direttive(
            ctx.strategia(),
            ctx.alleanza(),
            plan,
            ctx.round(),
            analysis,
            change.nuovo().isPresent() ? change.motivo() : null);
    return new Esito(plan, d, analysis);
  }

  /**
   * The directives of a strategic state ({@code stato_strategico}) of the rules with {@code plan}
   * in force: base orders of the plan + posture, counter-objectives, delay tools and enemy reading
   * of the state. Also used by tests to write a round's decision.
   *
   * @param changeReason why the plan changed this round, null if it did not
   */
  public static JsonObject direttive(
      final Strategia strategia,
      final String alliance,
      final Strategia.Piano plan,
      final int round,
      final JsonObject analysis,
      final @javax.annotation.Nullable String changeReason) {
    final JsonObject d = base(strategia, alliance, plan, round);
    d.addProperty("postura", analysis.get("postura").getAsString());
    final JsonArray targets = d.getAsJsonArray("bersagli");
    for (final JsonElement e : Direttive.array(analysis, "contro_obiettivi")) {
      final JsonObject o = e.getAsJsonObject();
      final JsonObject t = new JsonObject();
      t.addProperty("territorio", o.get("territorio").getAsString());
      t.addProperty("peso", o.get("peso").getAsDouble());
      t.addProperty("origine", Direttive.ORIGINE_CONTROMOSSA);
      if (o.has("punto_critico")) {
        t.addProperty("punto_critico", o.get("punto_critico").getAsString());
      }
      targets.add(t);
    }
    d.add(
        "tattiche",
        tools(analysis.has(Tattiche.KEY) ? analysis.getAsJsonObject(Tattiche.KEY) : null));
    if (analysis.has("lettura")) {
      final JsonObject r = analysis.getAsJsonObject("lettura");
      final JsonObject reading = new JsonObject();
      reading.addProperty("piano", r.get("piu_probabile").getAsString());
      reading.addProperty("probabilita", r.get("probabilita").getAsDouble());
      d.add("lettura_nemico", reading);
    }
    d.addProperty(
        "motivazione",
        Direttive.motivation(
            "Regole: postura "
                + analysis.get("postura").getAsString()
                + " perche' "
                + Direttive.string(analysis, "motivo", "-").replace(". ", ", ")
                + ". Piano "
                + plan.label()
                + (changeReason != null
                    ? " (cambiato: " + changeReason.replace(". ", ", ") + ")"
                    : "")
                + ". Strumenti di ritardo spenti.",
            new java.util.ArrayList<>()));
    return d;
  }

  /**
   * The base orders of {@code plan} in {@code round} as directives: what the plan's data file says
   * (phase risk and objectives, operations launched, roles), posture OFFENSIVA (NESSUNA for a plan
   * without counter-moves), no counter-objective, no tool, no reading. Used when the round has no
   * decision yet and for the fields a decider leaves out after a plan change.
   */
  public static JsonObject base(
      final Strategia strategia,
      final String alliance,
      final Strategia.Piano plan,
      final int round) {
    final JsonObject d = new JsonObject();
    d.addProperty("versione", Direttive.VERSIONE);
    d.addProperty("alleanza", alliance);
    d.addProperty("round", round);
    d.addProperty("piano", plan.id());
    d.addProperty("postura", Contromosse.OFFENSIVA);
    final Optional<Strategia.Fase> phase = plan.fase(round);
    final JsonObject risk = new JsonObject();
    strategia
        .getFronti()
        .forEach(f -> risk.addProperty(f, phase.map(p -> p.modificatore(f)).orElse(0)));
    d.add("rischio", risk);
    final JsonArray ops = new JsonArray();
    plan.operazioni().stream()
        .filter(o -> o.covers(round))
        .forEach(
            o -> {
              final JsonObject op = new JsonObject();
              op.addProperty("id", o.nome());
              op.addProperty("decisione", Direttive.LANCIA);
              op.addProperty("perdita_trasporti_accettata_max_TUV", 0);
              ops.add(op);
            });
    d.add("operazioni", ops);
    final JsonArray targets = new JsonArray();
    phase.ifPresent(
        p ->
            p.obiettivi()
                .forEach(
                    o -> {
                      final JsonObject t = new JsonObject();
                      if (o.territorio() != null) {
                        t.addProperty("territorio", o.territorio());
                      } else {
                        t.addProperty("flotta", o.flotta());
                      }
                      t.addProperty("peso", o.peso());
                      if (!o.nazioni().isEmpty()) {
                        final JsonArray n = new JsonArray();
                        o.nazioni().stream().sorted().forEach(n::add);
                        t.add("nazioni", n);
                      }
                      if (o.critico()) {
                        t.addProperty("critico", true);
                      }
                      t.addProperty("origine", Direttive.ORIGINE_PIANO);
                      targets.add(t);
                    }));
    d.add("bersagli", targets);
    d.add("tattiche", new JsonArray());
    final JsonObject roles = new JsonObject();
    plan.ruoli().forEach(roles::addProperty);
    d.add("ruoli", roles);
    final JsonObject reading = new JsonObject();
    reading.addProperty("piano", "");
    reading.addProperty("probabilita", 0);
    d.add("lettura_nemico", reading);
    d.addProperty("motivazione", "Ordini base del piano " + plan.label() + ".");
    return d;
  }

  /** {tool: {nation: {fronti, perche}}} -> [{strumento, nazione, fronte?, attiva, perche}]. */
  static JsonArray tools(final JsonObject structure) {
    final JsonArray out = new JsonArray();
    if (structure == null) {
      return out;
    }
    for (final String tool : Tattiche.STRUMENTI) {
      if (!structure.has(tool)) {
        continue;
      }
      structure
          .getAsJsonObject(tool)
          .entrySet()
          .forEach(
              e -> {
                final JsonObject o = e.getValue().getAsJsonObject();
                final JsonArray fronts =
                    o.has("fronti") ? o.getAsJsonArray("fronti") : new JsonArray();
                final String why = o.has("perche") ? o.get("perche").getAsString() : "regole";
                if (fronts.isEmpty()) {
                  out.add(tool(tool, e.getKey(), null, why));
                } else {
                  fronts.forEach(f -> out.add(tool(tool, e.getKey(), f.getAsString(), why)));
                }
              });
    }
    return out;
  }

  private static JsonObject tool(
      final String tool, final String nation, final String front, final String why) {
    final JsonObject t = new JsonObject();
    t.addProperty("strumento", tool);
    t.addProperty("nazione", nation);
    if (front != null) {
      t.addProperty("fronte", front);
    }
    t.addProperty("attiva", true);
    t.addProperty("perche", why);
    return t;
  }
}
