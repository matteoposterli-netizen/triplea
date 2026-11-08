package games.strategy.triplea.ai.proplus.strategia;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.strategia.llm.ClaudeCode;
import games.strategy.triplea.ai.proplus.strategia.llm.ErroreStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Modalita;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Richiesta;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import games.strategy.triplea.ai.proplus.strategia.llm.Uso;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step INTEGRAZIONE LLM: the LLM strategist in the general staff's flow, with fake transports (no
 * network, no process). Global 1940 renamed as Europe 1940, Axis plan A, Allies plan 1, round 4.
 */
class StrategaLlmTest extends AbstractClientSettingTestCase {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";

  /** An answer in the strategist's schema (lists of pairs). */
  static final String ANSWER =
      """
      {"piano": "1", "postura": "CONTRASTO",
       "rischio": [{"fronte": "Ovest", "valore": 1}, {"fronte": "Est", "valore": -1}],
       "operazioni": [{"id": "Overlord UK", "decisione": "attendi",
                       "perdita_trasporti_accettata_max_TUV": 0}],
       "bersagli": [{"territorio": "Norway", "peso": 12, "origine": "contromossa", "critico": false}],
       "tattiche": [{"strumento": "blocco_terrestre", "nazione": "Russians", "fronte": "Est",
                     "attiva": true}],
       "ruoli": [{"nazione": "British", "ruolo": "tiene l'Egitto"}],
       "lettura_nemico": {"piano": "A", "probabilita": 0.7},
       "motivazione": "Contrasto il piano A. Overlord aspetta la scorta."}
      """;

  @TempDir Path profile;
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer british = data.getPlayerList().getPlayerId("British");
  private final List<Richiesta> asked = new ArrayList<>();
  private Function<Modalita, ? extends Decisore> savedFactory;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
    savedFactory = Decisori.fabbricaLlm;
    data.setGameName(EUROPE);
    final Strategia strategia = Strategie.forGame(data).orElseThrow();
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    StatoMaggiore.setChoice(data.getProperties(), "Axis", "A");
    StatoMaggiore.setChoice(data.getProperties(), "Allies", "1");
    data.getSequence().setRoundAndStep(4, "Combat Move", british);
    StatoMaggiore.fix(data, "Axis", strategia);
    StatoMaggiore.fix(data, "Allies", strategia);
  }

  @AfterEach
  void tearDown() {
    Decisori.fabbricaLlm = savedFactory;
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

  private void llm(final Modalita mode, final DecisoreLlm.Trasporto... transports) {
    Decisori.set(data.getProperties(), "Allies", mode.id(), "");
    Decisori.fabbricaLlm = m -> new DecisoreLlm(m, List.of(transports));
  }

  private DecisoreLlm.Trasporto answering(final String name, final String text) {
    return transport(
        name,
        Optional.empty(),
        r -> {
          asked.add(r);
          return new Risposta(text, name, "claude-haiku-5-5", 3000, 400, 5000, 0, 0.0012, false, 900);
        });
  }

  private static DecisoreLlm.Trasporto transport(
      final String name,
      final Optional<String> unavailable,
      final Function<Richiesta, Risposta> call) {
    return new DecisoreLlm.Trasporto() {
      @Override
      public String nome() {
        return name;
      }

      @Override
      public Optional<String> nonDisponibile() {
        return unavailable;
      }

      @Override
      public Risposta chiama(final Richiesta r) {
        return call.apply(r);
      }
    };
  }

  @Test
  void theStrategistsDirectivesAreAppliedAndLoggedWithTheCost() {
    llm(Modalita.CHIAVE_API, answering("Haiku", ANSWER));

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    final JsonObject d = r.storico().getAsJsonObject(Valutazione.KEY_DIRETTIVE);
    assertThat(d.get("postura").getAsString()).isEqualTo("CONTRASTO");
    assertThat(d.getAsJsonObject("rischio").get("Ovest").getAsInt()).isEqualTo(1);
    assertThat(d.getAsJsonObject("rischio").get("Est").getAsInt()).isEqualTo(-1);
    assertThat(d.getAsJsonArray("operazioni").toString())
        .contains("\"id\":\"Overlord UK\",\"decisione\":\"attendi\"");
    assertThat(d.getAsJsonArray("tattiche").toString())
        .contains("blocco_terrestre")
        .contains("Russians");
    final JsonObject who = r.storico().getAsJsonObject("decisore");
    assertThat(who.get("nome").getAsString()).isEqualTo(Decisore.LLM);
    assertThat(who.get("fonte").getAsString()).isEqualTo("Haiku");
    assertThat(who.get("ripiego").getAsBoolean()).isFalse();
    assertThat(who.getAsJsonObject("uso").get("chiamate").getAsInt()).isEqualTo(1);
    assertThat(r.testo())
        .contains(
            "Stratega: Haiku - postura CONTRASTO, piano 1, Contrasto il piano A. Overlord aspetta"
                + " la scorta.")
        .contains("Stratega, uso della partita finora: 1 chiamate (Haiku 1)")
        .contains("costo stimato $0.0012");
    // the prompt: fixed part with the plans, variable part with fantasy and state
    assertThat(asked).hasSize(1);
    assertThat(asked.get(0).sistema()).contains("Barbarossa").doesNotContain("{{");
    assertThat(asked.get(0).utente())
        .contains("Livello di Fantasia: 2/5")
        .contains("\"alleanza\":\"Allies\"");
    assertThat(asked.get(0).fantasia()).isEqualTo(2);
  }

  @Test
  void theFantasyLevelOfTheAllianceReachesThePrompt() {
    llm(Modalita.CHIAVE_API, answering("Haiku", ANSWER));
    Uso.setFantasia(data.getProperties(), "Allies", 5);

    Valutazione.inizioRound(data, british).orElseThrow();

    assertThat(asked.get(0).fantasia()).isEqualTo(5);
    assertThat(asked.get(0).utente()).contains("5/5, sorprendente");
  }

  @Test
  void anErrorOrTimeoutFallsBackToTheRulesWithANotice() {
    llm(
        Modalita.CHIAVE_API,
        transport(
            "Haiku",
            Optional.empty(),
            r -> {
              throw new ErroreStratega("nessuna risposta da Haiku entro 45 s (2 tentativi)");
            }));

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    final JsonObject who = r.storico().getAsJsonObject("decisore");
    assertThat(who.get("ripiego").getAsBoolean()).isTrue();
    assertThat(who.getAsJsonArray("avvisi").toString())
        .contains("stratega non disponibile (Haiku: nessuna risposta da Haiku entro 45 s");
    assertThat(r.testo()).contains("Stratega: regole - postura ");
    assertThat(r.storico().getAsJsonObject(Valutazione.KEY_DIRETTIVE).get("motivazione").getAsString())
        .startsWith("Regole: ");
    assertThat(Uso.leggi(data.getProperties()).get("errori").getAsInt()).isEqualTo(1);
  }

  @Test
  void anAnswerThatIsNotJsonFallsBackToTheRules() {
    llm(Modalita.CHIAVE_API, answering("Haiku", "Non saprei, dipende."));

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    assertThat(r.storico().getAsJsonObject("decisore").get("ripiego").getAsBoolean()).isTrue();
    assertThat(r.storico().getAsJsonObject("decisore").getAsJsonArray("avvisi").toString())
        .contains("risposta senza JSON");
    final JsonObject usage = Uso.leggi(data.getProperties());
    assertThat(usage.get("chiamate").getAsInt()).isEqualTo(1);
    assertThat(usage.get("json_non_validi").getAsInt()).isEqualTo(1);
  }

  @Test
  void aPlanNotAdmittedMakesTheDirectivesInvalid() {
    llm(Modalita.CHIAVE_API, answering("Haiku", ANSWER.replace("\"piano\": \"1\"", "\"piano\": \"X\"")));

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    assertThat(r.storico().getAsJsonObject("decisore").get("ripiego").getAsBoolean()).isTrue();
    assertThat(r.storico().getAsJsonObject("decisore").getAsJsonArray("avvisi").toString())
        .contains("piano sconosciuto X");
  }

  @Test
  void automaticUsesClaudeCodeWhenReadyElseTheKey() {
    final List<String> called = new ArrayList<>();
    final DecisoreLlm.Trasporto claude =
        DecisoreLlm.claudeCode(
            () -> new ClaudeCode.Stato(null, false, "Claude Code non trovato"),
            r -> {
              called.add("claude");
              throw new IllegalStateException("never");
            });
    llm(Modalita.AUTOMATICO, claude, answering("Haiku", ANSWER));

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    assertThat(called).isEmpty();
    assertThat(r.storico().getAsJsonObject("decisore").get("fonte").getAsString())
        .isEqualTo("Haiku");
  }

  @Test
  void automaticPrefersClaudeCode() {
    final DecisoreLlm.Trasporto claude =
        DecisoreLlm.claudeCode(
            () -> new ClaudeCode.Stato(Path.of("claude"), true, "ok"),
            r -> new Risposta(ANSWER, "Claude Code", "haiku", 10, 10, 0, 0, 0.003, true, 2000));
    llm(Modalita.AUTOMATICO, claude, answering("Haiku", ANSWER));

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    assertThat(r.storico().getAsJsonObject("decisore").get("fonte").getAsString())
        .isEqualTo("Claude Code");
    assertThat(asked).isEmpty();
    assertThat(r.testo()).contains("incluso nell'abbonamento");
  }

  @Test
  void noStrategistAvailableMeansTheRules() {
    llm(
        Modalita.AUTOMATICO,
        DecisoreLlm.claudeCode(
            () -> new ClaudeCode.Stato(null, false, "Claude Code non trovato"), r -> null),
        transport("Haiku", Optional.of("nessuna chiave Haiku salvata"), r -> null));

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    assertThat(r.storico().getAsJsonObject("decisore").getAsJsonArray("avvisi").toString())
        .contains("Claude Code non trovato; nessuna chiave Haiku salvata");
    assertThat(Uso.chiamate(data.getProperties())).isZero();
  }

  @Test
  void theCapOfCallsPerGameStopsTheCalls() {
    llm(Modalita.CHIAVE_API, answering("Haiku", ANSWER));
    Uso.setTetto(data.getProperties(), 0);

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    assertThat(asked).isEmpty();
    assertThat(r.storico().getAsJsonObject("decisore").getAsJsonArray("avvisi").toString())
        .contains("tetto di 0 chiamate per partita raggiunto");
  }

  @Test
  void aGameWithoutAChoiceUsesTheRulesUnlessTheUserPickedAMode() {
    assertThat(Decisori.getModalita(data.getProperties(), "Allies")).isEqualTo(Modalita.REGOLE);
    assertThat(Decisori.per(data.getProperties(), "Allies")).isInstanceOf(DecisoreRegole.class);
  }
}
