package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.proplus.strategia.DecisioneOperazione;
import games.strategy.triplea.ai.proplus.strategia.llm.ClientApi;
import games.strategy.triplea.ai.proplus.strategia.llm.ErroreStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Fornitore;
import games.strategy.triplea.ai.proplus.strategia.llm.Http;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.PromptOperazione;
import games.strategy.triplea.ai.proplus.strategia.llm.PromptOrdiniTurnoV4;
import games.strategy.triplea.ai.proplus.strategia.llm.Richiesta;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import java.io.Serializable;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Step PROVALLM: who decides at the decision points of the joint operations v2 (launch of step 1,
 * step 2, step on Paris, keep or leave the held target). Off by default (the v2 formula decides);
 * on with the game property {@code "Pro+ operazione decisore: <alliance>"} = {@code llm} or {@code
 * llm:<model>} (default model {@value #MODELLO_DEFAULT}, Anthropic Messages API with the key of the
 * user profile or ANTHROPIC_API_KEY). No other LLM call is made by the operations.
 *
 * <p>Parachute: no answer, an error, a timeout, an answer that is not a valid decision, the cap of
 * calls or the budget in USD of the game reached ⇒ the formula's decision, with the reason. The
 * game's usage (calls, tokens, cost, fallbacks by reason) is saved in {@value #KEY_USO}. The key is
 * only in the request header: never in the game, the diary or the logs.
 */
public final class DecisoreOperazione {
  public static final String CHOICE_PREFIX = "Pro+ operazione decisore: ";
  public static final String KEY_USO = "Pro+ operazione uso";
  public static final String KEY_BUDGET = "Pro+ operazione budget usd";
  public static final String LLM = "llm";
  public static final String MODELLO_DEFAULT = "claude-sonnet-5-5";

  /** Budget per game in USD when the game sets none. */
  public static final double BUDGET_DEFAULT = 1.0;

  /** Calls per game at most. */
  public static final int TETTO_CHIAMATE = 80;

  /** Least cost assumed for the next call when checking the budget (USD). */
  static final double COSTO_MINIMO_CHIAMATA = 0.03;

  /** Reasons of a fallback to the formula (counted per game). */
  public static final String RIPIEGO_NON_VALIDA = "risposta non valida";

  public static final String RIPIEGO_ERRORE = "errore o timeout";
  public static final String RIPIEGO_BUDGET = "budget esaurito";
  public static final String RIPIEGO_TETTO = "tetto chiamate";
  public static final String RIPIEGO_NON_DISPONIBILE = "LLM non disponibile";

  /** One way to reach the model (a fake one in the tests: no network). */
  public interface Trasporto {
    /** Why it cannot be used now (empty = usable). */
    Optional<String> nonDisponibile();

    /**
     * @throws ErroreStratega no answer (timeout, key refused, ...)
     */
    Risposta chiama(Richiesta r);
  }

  /** Makes the transport for a model id (tests replace it). */
  static Function<String, Trasporto> fabbrica = DecisoreOperazione::reale;

  /** Test hook: the transport factory ({@code null} = the real one). */
  public static void setFabbrica(final Function<String, Trasporto> f) {
    fabbrica = f == null ? DecisoreOperazione::reale : f;
  }

  /**
   * What was decided at a decision point.
   *
   * @param decisione the decision carried out (the LLM's, or the formula's on a fallback)
   * @param fonte "Sonnet", "Haiku"... or "formula"
   * @param ripiego the formula decided although the LLM was on
   * @param motivoRipiego category of the fallback ({@code RIPIEGO_*}), "" without
   * @param dettaglio the error text of a fallback (no key), "" without
   */
  public record Esito(
      DecisioneOperazione decisione,
      String fonte,
      boolean ripiego,
      String motivoRipiego,
      String dettaglio,
      double costoUsd,
      long token,
      long millis) {

    public boolean llm() {
      return !ripiego && !"formula".equals(fonte);
    }
  }

  private DecisoreOperazione() {}

  // ------------------------------------------------------------------------------------- switch

  /** Turns the LLM on for {@code alliance}: "llm", "llm:MODEL"; "" or null = off (formula). */
  public static void set(final GameProperties props, final String alliance, final String value) {
    props.set(CHOICE_PREFIX + alliance, (Serializable) null);
    if (value == null || value.isBlank()) {
      return;
    }
    final String v = value.strip();
    if (!v.equals(LLM) && !(v.startsWith(LLM + ":") && v.length() > LLM.length() + 1)) {
      throw new IllegalArgumentException("Decisore delle operazioni non valido: " + v);
    }
    props.set(CHOICE_PREFIX + alliance, v);
  }

  /** The model deciding for {@code alliance}; empty = the formula (default). */
  public static Optional<String> modello(final GameProperties props, final String alliance) {
    if (!(props.get(CHOICE_PREFIX + alliance) instanceof String v) || v.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(v.startsWith(LLM + ":") ? v.substring(LLM.length() + 1) : MODELLO_DEFAULT);
  }

  public static void setBudget(final GameProperties props, final double usd) {
    props.set(KEY_BUDGET, (Serializable) null);
    props.set(KEY_BUDGET, String.format(Locale.ROOT, "%.4f", usd));
  }

  /** Usage of the strategic level of the LLM strategist (absent in every other game). */
  public static final String KEY_USO_STRATEGICO = "Pro+ stratega llm uso";

  /** Cost (USD) already spent in the game by the strategic level of the LLM strategist. */
  static double costoAltriLivelli(final GameProperties props) {
    if (props.get(KEY_USO_STRATEGICO) instanceof String s && !s.isBlank()) {
      try {
        return JsonParser.parseString(s).getAsJsonObject().get("costo_usd").getAsDouble();
      } catch (final RuntimeException e) {
        return 0;
      }
    }
    return 0;
  }

  public static double budget(final GameProperties props) {
    if (props.get(KEY_BUDGET) instanceof String s) {
      try {
        return Double.parseDouble(s);
      } catch (final NumberFormatException e) {
        // default
      }
    }
    return BUDGET_DEFAULT;
  }

  /** The game's usage (calls, errors, tokens, cost, fallbacks by reason). */
  public static JsonObject uso(final GameProperties props) {
    if (props.get(KEY_USO) instanceof String s && !s.isBlank()) {
      try {
        return JsonParser.parseString(s).getAsJsonObject();
      } catch (final RuntimeException e) {
        // a fresh count
      }
    }
    return nuovoUso();
  }

  private static JsonObject nuovoUso() {
    final JsonObject u = new JsonObject();
    for (final String k :
        new String[] {
          "chiamate",
          "errori",
          "risposte_non_valide",
          "token_ingresso",
          "token_uscita",
          "cache_lettura",
          "cache_scrittura",
          "ms"
        }) {
      u.addProperty(k, 0);
    }
    u.addProperty("costo_usd", 0.0);
    u.add("ripieghi", new JsonObject());
    return u;
  }

  // ------------------------------------------------------------------------------------ decide

  /**
   * The decision at {@code punto}: the formula's when the LLM is off for {@code alliance} (no
   * call), else the model's answer, or the formula's on any fallback.
   */
  public static Esito decidi(
      final GameProperties props,
      final String alliance,
      final DecisioneOperazione.Punto punto,
      final JsonObject stato,
      final DecisioneOperazione formula) {
    final Optional<String> model = modello(props, alliance);
    if (model.isEmpty()) {
      return new Esito(formula, "formula", false, "", "", 0, 0, 0);
    }
    final JsonObject u = uso(props);
    final int calls = u.get("chiamate").getAsInt();
    final double own = u.get("costo_usd").getAsDouble();
    // step STRATEGA LLM: one cap per game for both levels (strategic calls counted here too)
    final double cost = own + costoAltriLivelli(props);
    if (calls >= TETTO_CHIAMATE) {
      return fallback(props, u, formula, RIPIEGO_TETTO, calls + " chiamate");
    }
    final double next = Math.max(COSTO_MINIMO_CHIAMATA, calls > 0 ? own / calls * 1.5 : 0);
    final double budget = budget(props);
    if (cost + next > budget) {
      return fallback(
          props,
          u,
          formula,
          RIPIEGO_BUDGET,
          String.format(
              Locale.ROOT,
              "speso $%.3f + prossima chiamata ~$%.3f > budget $%.2f",
              cost,
              next,
              budget));
    }
    final Trasporto t;
    try {
      t = fabbrica.apply(model.get());
    } catch (final RuntimeException e) {
      return fallback(props, u, formula, RIPIEGO_NON_DISPONIBILE, String.valueOf(e.getMessage()));
    }
    final Optional<String> na = t.nonDisponibile();
    if (na.isPresent()) {
      return fallback(props, u, formula, RIPIEGO_NON_DISPONIBILE, na.get());
    }
    final Risposta r;
    u.addProperty("chiamate", calls + 1);
    try {
      // step STRATEGA LLM: the general operations have their own prompt (turn orders v3)
      r =
          t.chiama(
              stato.has("tipo_operazione")
                  ? PromptOrdiniTurnoV4.richiesta(stato)
                  : PromptOperazione.richiesta(stato));
    } catch (final RuntimeException e) {
      u.addProperty("errori", u.get("errori").getAsInt() + 1);
      return fallback(props, u, formula, RIPIEGO_ERRORE, String.valueOf(e.getMessage()));
    }
    add(u, "token_ingresso", r.ingresso());
    add(u, "token_uscita", r.uscita());
    add(u, "cache_lettura", r.cacheLettura());
    add(u, "cache_scrittura", r.cacheScrittura());
    add(u, "ms", r.millis());
    u.addProperty("costo_usd", own + r.costoUsd());
    u.addProperty("modello", r.modello());
    try {
      final DecisioneOperazione d = DecisioneOperazione.leggi(r.testo(), punto);
      save(props, u);
      return new Esito(d, r.fonte(), false, "", "", r.costoUsd(), r.tokenTotali(), r.millis());
    } catch (final RuntimeException e) {
      u.addProperty("risposte_non_valide", u.get("risposte_non_valide").getAsInt() + 1);
      final Esito f =
          fallback(props, u, formula, RIPIEGO_NON_VALIDA, String.valueOf(e.getMessage()));
      return new Esito(
          f.decisione(),
          f.fonte(),
          true,
          f.motivoRipiego(),
          f.dettaglio(),
          r.costoUsd(),
          r.tokenTotali(),
          r.millis());
    }
  }

  private static Esito fallback(
      final GameProperties props,
      final JsonObject u,
      final DecisioneOperazione formula,
      final String reason,
      final String detail) {
    final JsonObject f = u.getAsJsonObject("ripieghi");
    f.addProperty(reason, (f.has(reason) ? f.get(reason).getAsInt() : 0) + 1);
    save(props, u);
    final String d = detail.length() > 200 ? detail.substring(0, 200) + "..." : detail;
    return new Esito(formula, "formula", true, reason, d, 0, 0, 0);
  }

  /** The usage saved under {@code key} (fresh counts if none). */
  static JsonObject usoDi(final GameProperties props, final String key) {
    if (props.get(key) instanceof String s && !s.isBlank()) {
      try {
        return JsonParser.parseString(s).getAsJsonObject();
      } catch (final RuntimeException e) {
        // a fresh count
      }
    }
    return nuovoUso();
  }

  /** Adds an answer's tokens, time and cost to the usage {@code u}. */
  static void conta(final JsonObject u, final Risposta r) {
    add(u, "token_ingresso", r.ingresso());
    add(u, "token_uscita", r.uscita());
    add(u, "cache_lettura", r.cacheLettura());
    add(u, "cache_scrittura", r.cacheScrittura());
    add(u, "ms", r.millis());
    u.addProperty("costo_usd", u.get("costo_usd").getAsDouble() + r.costoUsd());
    u.addProperty("modello", r.modello());
  }

  static void salvaUso(final GameProperties props, final String key, final JsonObject u) {
    props.set(key, (Serializable) null);
    props.set(key, u.toString());
  }

  private static void add(final JsonObject u, final String key, final long n) {
    u.addProperty(key, u.get(key).getAsLong() + n);
  }

  private static void save(final GameProperties props, final JsonObject u) {
    props.set(KEY_USO, (Serializable) null);
    props.set(KEY_USO, u.toString());
  }

  /** The real model through the Anthropic API key (user profile, else ANTHROPIC_API_KEY). */
  static Trasporto reale(final String model) {
    if (StrategaSimulato.attivo()) {
      return new StrategaSimulato();
    }
    final Optional<String> key = ProfiloStratega.carica().chiave(Fornitore.ANTHROPIC);
    final ClientApi client = new ClientApi(Http.REALE, model);
    return new Trasporto() {
      @Override
      public Optional<String> nonDisponibile() {
        return key.isEmpty()
            ? Optional.of("nessuna chiave Anthropic (profilo o ANTHROPIC_API_KEY)")
            : Optional.empty();
      }

      @Override
      public Risposta chiama(final Richiesta r) {
        return client.chiama(Fornitore.ANTHROPIC, key.orElseThrow(), r);
      }
    };
  }
}
