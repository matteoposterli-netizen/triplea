package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonObject;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.proplus.strategia.llm.ClaudeCode;
import games.strategy.triplea.ai.proplus.strategia.llm.ClientApi;
import games.strategy.triplea.ai.proplus.strategia.llm.ErroreStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Fornitore;
import games.strategy.triplea.ai.proplus.strategia.llm.Http;
import games.strategy.triplea.ai.proplus.strategia.llm.Modalita;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.PromptOrdini;
import games.strategy.triplea.ai.proplus.strategia.llm.PromptStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Richiesta;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import games.strategy.triplea.ai.proplus.strategia.llm.UscitaLlm;
import games.strategy.triplea.ai.proplus.strategia.llm.Uso;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The LLM strategist: decides the WHAT/WHY of the alliance once per round (the first Pro+ nation of
 * the alliance asks, {@link Valutazione#inizioRound}); the deterministic part does the HOW. The
 * transports, tried in order for the mode:
 *
 * <ol>
 *   <li>Claude Code installed and logged in ({@link ClaudeCode}, Automatico and "Claude
 *       installato");
 *   <li>the API key of the provider chosen in the profile ({@link ClientApi}, Automatico and
 *       "Chiave API");
 *   <li>nothing worked: {@link Decisore.NonDisponibile} = the rules decide this round, with a
 *       notice in the diary. The game is never blocked.
 * </ol>
 *
 * <p>Any error, timeout, quota, cap of calls per game or answer that is not valid JSON ends in the
 * rules for that round. The directives are then checked by {@link Direttive#valida} (guard rails).
 */
public final class DecisoreLlm implements Decisore {

  /** One way to reach the strategist. */
  public interface Trasporto {
    /** Eg "Claude Code", "Haiku". */
    String nome();

    /** Why it cannot be used now (empty = usable). */
    Optional<String> nonDisponibile();

    Risposta chiama(Richiesta r);
  }

  private static final Map<String, String> SYSTEM_BY_GAME = new ConcurrentHashMap<>();

  private final Modalita modalita;
  private final List<Trasporto> trasporti;
  private volatile String etichetta = REGOLE;

  public DecisoreLlm(final Modalita modalita, final List<Trasporto> trasporti) {
    this.modalita = modalita;
    this.trasporti = List.copyOf(trasporti);
  }

  /** The real strategist for {@code modalita} (user profile, real network and processes). */
  public static DecisoreLlm reale(final Modalita modalita) {
    final ProfiloStratega profile = ProfiloStratega.carica();
    final List<Trasporto> list = new ArrayList<>();
    if (modalita == Modalita.AUTOMATICO || modalita == Modalita.CLAUDE_CODE) {
      list.add(claudeCode(ClaudeCode::stato, r -> ClaudeCode.reale().chiama(r)));
    }
    if (modalita == Modalita.AUTOMATICO || modalita == Modalita.CHIAVE_API) {
      list.add(api(profile.fornitore(), profile::chiave, new ClientApi(Http.REALE)));
    }
    return new DecisoreLlm(modalita, list);
  }

  /** Transport through Claude Code. */
  public static Trasporto claudeCode(
      final Supplier<ClaudeCode.Stato> stato, final Function<Richiesta, Risposta> call) {
    return new Trasporto() {
      @Override
      public String nome() {
        return "Claude Code";
      }

      @Override
      public Optional<String> nonDisponibile() {
        final ClaudeCode.Stato s = stato.get();
        return s.pronto() ? Optional.empty() : Optional.of(s.descrizione());
      }

      @Override
      public Risposta chiama(final Richiesta r) {
        return call.apply(r);
      }
    };
  }

  /** Transport through the API key of {@code provider}. */
  public static Trasporto api(
      final Fornitore provider,
      final Function<Fornitore, Optional<String>> keys,
      final ClientApi client) {
    return new Trasporto() {
      @Override
      public String nome() {
        return provider.etichetta();
      }

      @Override
      public Optional<String> nonDisponibile() {
        return keys.apply(provider).isPresent()
            ? Optional.empty()
            : Optional.of("nessuna chiave " + provider.etichetta() + " salvata");
      }

      @Override
      public Risposta chiama(final Richiesta r) {
        return client.chiama(
            provider,
            keys.apply(provider).orElseThrow(() -> new ErroreStratega("nessuna chiave")),
            r);
      }
    };
  }

  @Override
  public String nome() {
    return LLM;
  }

  @Override
  public String etichetta() {
    return etichetta;
  }

  public Modalita getModalita() {
    return modalita;
  }

  @Override
  public Optional<JsonObject> decidi(final JsonObject stato, final Contesto contesto) {
    final GameProperties props = contesto.data().getProperties();
    final int fantasia = Uso.fantasia(props, contesto.alleanza());
    final Richiesta request =
        new Richiesta(
            sistema(contesto), PromptStratega.utente(stato, fantasia), PromptStratega.schema(), fantasia);
    final JsonObject answer =
        chiedi(request, props, Uso.STRATEGA, contesto.alleanza() + " R" + contesto.round());
    return Optional.of(UscitaLlm.direttive(answer, contesto.round(), contesto.alleanza()));
  }

  /**
   * LLM step, turn orders of {@code nation}: its answer (schema ordini-turno-v1, not checked yet:
   * {@link Direttive#applicaOrdini} does it).
   *
   * @param ingresso the input of the turn orders ({@link Ingresso#ordiniTurno})
   * @throws Decisore.NonDisponibile no answer (the nation plays the strategic directives)
   */
  public JsonObject ordini(
      final JsonObject ingresso,
      final GameProperties props,
      final String alliance,
      final String nation,
      final int round) {
    final int fantasia = Uso.fantasia(props, alliance);
    final Richiesta request =
        new Richiesta(
            PromptOrdini.sistema(),
            PromptOrdini.utente(ingresso, fantasia),
            PromptOrdini.schema(),
            fantasia);
    return chiedi(request, props, Uso.ORDINI, nation + " R" + round);
  }

  /** The transports in order; the first answer that is a JSON object wins. */
  private JsonObject chiedi(
      final Richiesta request, final GameProperties props, final String level, final String what) {
    etichetta = REGOLE;
    final int cap = Uso.tetto(props);
    if (Uso.chiamate(props) >= cap) {
      throw new NonDisponibile("tetto di " + cap + " chiamate per partita raggiunto");
    }
    final List<String> failures = new ArrayList<>();
    for (final Trasporto t : trasporti) {
      final Optional<String> unavailable = t.nonDisponibile();
      if (unavailable.isPresent()) {
        failures.add(unavailable.get());
        continue;
      }
      if (Uso.chiamate(props) >= cap) {
        failures.add("tetto di " + cap + " chiamate per partita raggiunto");
        break;
      }
      final Risposta answer;
      try {
        answer = t.chiama(request);
      } catch (final ErroreStratega e) {
        Uso.registraErrore(props, t.nome(), level);
        failures.add(t.nome() + ": " + e.getMessage());
        continue;
      }
      Uso.registra(props, answer, level);
      try {
        final JsonObject json = UscitaLlm.leggi(answer.testo());
        etichetta = answer.fonte();
        ProLogger.info(
            "[Pro+] "
                + (Uso.ORDINI.equals(level) ? "Ordini del turno " : "Stratega ")
                + answer.fonte()
                + " per "
                + what
                + ": "
                + answer.millis()
                + " ms, "
                + answer.tokenTotali()
                + " token");
        return json;
      } catch (final ErroreStratega e) {
        Uso.registraJsonNonValido(props, level);
        failures.add(t.nome() + ": " + e.getMessage());
      }
    }
    throw new NonDisponibile(
        failures.isEmpty() ? "nessuno stratega configurato" : String.join("; ", failures));
  }

  private static String sistema(final Contesto ctx) {
    final String game = String.valueOf(ctx.data().getGameName());
    return SYSTEM_BY_GAME.computeIfAbsent(
        game,
        g ->
            PromptStratega.sistema(
                Strategie.json(ctx.data())
                    .orElseThrow(() -> new NonDisponibile("nessun file di strategia"))));
  }
}
