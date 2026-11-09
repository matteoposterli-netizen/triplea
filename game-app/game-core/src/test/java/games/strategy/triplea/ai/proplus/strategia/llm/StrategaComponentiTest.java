package games.strategy.triplea.ai.proplus.strategia.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pieces of the LLM strategist, with fake HTTP and processes (no network, no Claude Code). */
class StrategaComponentiTest {
  private static final String KEY = "sk-ant-finta-SEGRETISSIMA-abcd";

  @TempDir Path dir;

  private static Richiesta request() {
    return new Richiesta("PARTE FISSA", "stato del round", PromptStratega.schema(), 3);
  }

  // ------------------------------------------------------------------------ API client

  @Test
  void anthropicRequestCachesTheFixedPartAndAsksForTheSchemaWithoutTemperature() {
    final List<String> bodies = new ArrayList<>();
    final List<Map<String, String>> headers = new ArrayList<>();
    final ClientApi client =
        new ClientApi(
            (url, h, body, timeout) -> {
              assertThat(url).isEqualTo("https://api.anthropic.com/v1/messages");
              assertThat(timeout).isEqualTo(ClientApi.TIMEOUT);
              bodies.add(body);
              headers.add(h);
              return new Http.Esito(
                  200,
                  """
                  {"model": "claude-haiku-5-5", "stop_reason": "end_turn",
                   "content": [{"type": "thinking", "thinking": ""},
                               {"type": "text", "text": "{\\"piano\\": \\"1\\"}"}],
                   "usage": {"input_tokens": 2000, "output_tokens": 500,
                             "cache_read_input_tokens": 6000, "cache_creation_input_tokens": 0}}
                  """);
            });

    final Risposta r = client.chiama(Fornitore.ANTHROPIC, KEY, request());

    final JsonObject body = JsonParser.parseString(bodies.get(0)).getAsJsonObject();
    assertThat(body.get("model").getAsString()).isEqualTo("claude-haiku-5-5");
    assertThat(body.has("temperature")).isFalse();
    final JsonObject system = body.getAsJsonArray("system").get(0).getAsJsonObject();
    assertThat(system.get("text").getAsString()).isEqualTo("PARTE FISSA");
    assertThat(system.getAsJsonObject("cache_control").get("ttl").getAsString()).isEqualTo("1h");
    assertThat(body.getAsJsonObject("output_config").getAsJsonObject("format").get("type").getAsString())
        .isEqualTo("json_schema");
    assertThat(headers.get(0)).containsEntry("x-api-key", KEY).containsKey("anthropic-version");
    assertThat(r.testo()).isEqualTo("{\"piano\": \"1\"}");
    assertThat(r.fonte()).isEqualTo("Haiku");
    assertThat(r.cacheLettura()).isEqualTo(6000);
    // 2000 x 0.10 + 500 x 0.50 + 6000 x 0.01 per million
    assertThat(r.costoUsd()).isCloseTo(0.00051, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void openAiCompatibleProvidersGetJsonModeAndTheTemperatureOfTheFantasy() {
    final List<String> bodies = new ArrayList<>();
    final ClientApi client =
        new ClientApi(
            (url, h, body, timeout) -> {
              assertThat(h).containsEntry("authorization", "Bearer gsk_chiave");
              bodies.add(body);
              return new Http.Esito(
                  200,
                  """
                  {"model": "openai/gpt-oss-120b",
                   "choices": [{"finish_reason": "stop", "message": {"content": "{}"}}],
                   "usage": {"prompt_tokens": 9000, "completion_tokens": 300,
                             "prompt_tokens_details": {"cached_tokens": 8000}}}
                  """);
            });

    final Risposta r = client.chiama(Fornitore.GROQ, "gsk_chiave", request());

    final JsonObject body = JsonParser.parseString(bodies.get(0)).getAsJsonObject();
    assertThat(body.get("temperature").getAsDouble()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(body.getAsJsonObject("response_format").get("type").getAsString())
        .isEqualTo("json_object");
    assertThat(r.ingresso()).isEqualTo(1000);
    assertThat(r.cacheLettura()).isEqualTo(8000);
    assertThat(r.fonte()).isEqualTo("Groq");
  }

  @Test
  void aRefusedKeyGivesAClearReasonWithoutTheKey() {
    final ClientApi client =
        new ClientApi(
            (url, h, body, timeout) ->
                new Http.Esito(
                    401,
                    "{\"error\": {\"type\": \"authentication_error\", \"message\": \"invalid x-api-key "
                        + KEY
                        + "\"}}"));

    assertThatThrownBy(() -> client.chiama(Fornitore.ANTHROPIC, KEY, request()))
        .isInstanceOf(ErroreStratega.class)
        .hasMessageContaining("chiave rifiutata da Haiku")
        .hasMessageNotContaining(KEY)
        .hasMessageNotContaining("SEGRETISSIMA");
  }

  @Test
  void aServerErrorIsTriedOnceMore() {
    final int[] calls = {0};
    final ClientApi client =
        new ClientApi(
            (url, h, body, timeout) -> {
              calls[0]++;
              return calls[0] == 1
                  ? new Http.Esito(529, "{\"error\": {\"message\": \"overloaded\"}}")
                  : new Http.Esito(
                      200,
                      "{\"content\": [{\"type\": \"text\", \"text\": \"{}\"}], \"usage\": {}}");
            });

    assertThat(client.chiama(Fornitore.ANTHROPIC, KEY, request()).testo()).isEqualTo("{}");
    assertThat(calls[0]).isEqualTo(2);
  }

  @Test
  void twoTimeoutsEndInAnError() {
    final int[] calls = {0};
    final ClientApi client =
        new ClientApi(
            (url, h, body, timeout) -> {
              calls[0]++;
              throw new HttpTimeoutException("timeout");
            });

    assertThatThrownBy(() -> client.chiama(Fornitore.DEEPSEEK, "sk-x", request()))
        .hasMessageContaining("nessuna risposta da DeepSeek entro 45 s (2 tentativi)");
    assertThat(calls[0]).isEqualTo(2);
  }

  @Test
  void creditOverAndQuotaHaveTheirOwnWords() {
    assertThat(
            ClientApi.errore(
                Fornitore.ANTHROPIC,
                new Http.Esito(400, "{\"error\": {\"message\": \"Your credit balance is too low\"}}"),
                KEY))
        .startsWith("credito esaurito su Haiku");
    assertThat(ClientApi.errore(Fornitore.GROQ, new Http.Esito(429, ""), KEY))
        .isEqualTo("limite di richieste o quota raggiunti su Groq");
  }

  // ------------------------------------------------------------------------ Claude Code

  @Test
  void claudeCodeIsFoundOnThePathOrInTheNativeInstallFolder() {
    final Set<Path> files =
        Set.of(Path.of("C:\\Users\\Mario", ".local", "bin", "claude.exe"));
    final ClaudeCode cc =
        new ClaudeCode(
            (c, in, t) -> null,
            Map.of("PATH", "C:\\Windows;C:\\Tools", "USERPROFILE", "C:\\Users\\Mario")::get,
            files::contains,
            true);

    assertThat(cc.trova()).contains(Path.of("C:\\Users\\Mario", ".local", "bin", "claude.exe"));

    final ClaudeCode none = new ClaudeCode((c, in, t) -> null, Map.<String, String>of()::get, p -> false, true);
    assertThat(none.verifica().descrizione()).isEqualTo("Claude Code non trovato");
  }

  @Test
  void claudeCodeNotLoggedInIsNotReady() {
    final ClaudeCode cc =
        new ClaudeCode(
            (c, in, t) ->
                new ClaudeCode.Processo.Esito(
                    1, "{\"type\":\"result\",\"is_error\":true,\"result\":\"Not logged in · Please run /login\"}", "", false),
            Map.of("PATH", "/usr/bin")::get,
            p -> p.equals(Path.of("/usr/bin", "claude")),
            false);

    final ClaudeCode.Stato s = cc.verifica();

    assertThat(s.pronto()).isFalse();
    assertThat(s.descrizione()).contains("non collegato a un account");
  }

  @Test
  void claudeCodeIsCalledHeadlessWithJsonOutputAndNoTools() {
    final List<List<String>> commands = new ArrayList<>();
    final List<String> inputs = new ArrayList<>();
    final ClaudeCode cc =
        new ClaudeCode(
            (c, in, t) -> {
              commands.add(c);
              inputs.add(in);
              assertThat(t).isEqualTo(ClaudeCode.TIMEOUT);
              return new ClaudeCode.Processo.Esito(
                  0,
                  "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                      + "\"result\":\"{\\\"piano\\\":\\\"1\\\"}\",\"total_cost_usd\":0.004,"
                      + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50,"
                      + "\"cache_read_input_tokens\":7000}}",
                  "",
                  false);
            },
            Map.<String, String>of()::get,
            p -> true,
            false);

    final Risposta r = cc.chiama(Path.of("/usr/bin/claude"), request());

    assertThat(commands.get(0))
        .containsSubsequence("-p", "--output-format", "json")
        .containsSubsequence("--model", "haiku")
        .containsSubsequence("--tools", "")
        .contains("--no-session-persistence", "--system-prompt-file");
    assertThat(inputs.get(0)).isEqualTo("stato del round");
    assertThat(r.fonte()).isEqualTo("Claude Code");
    assertThat(r.testo()).isEqualTo("{\"piano\":\"1\"}");
    assertThat(r.inAbbonamento()).isTrue();
    assertThat(r.cacheLettura()).isEqualTo(7000);
  }

  @Test
  void claudeCodeTimeoutIsAnError() {
    final ClaudeCode cc =
        new ClaudeCode(
            (c, in, t) -> new ClaudeCode.Processo.Esito(-1, "", "", true),
            Map.<String, String>of()::get,
            p -> true,
            false);

    assertThatThrownBy(() -> cc.chiama(Path.of("claude"), request()))
        .hasMessageContaining("nessuna risposta entro il tempo massimo");
  }

  // ------------------------------------------------------------------------ profile and keys

  @Test
  void theKeyIsSavedOnlyInTheProfileMaskedAndRemovable() throws IOException {
    final Path file = dir.resolve("profilo").resolve(ProfiloStratega.FILE_NAME);
    final ProfiloStratega p = new ProfiloStratega(file, k -> null);
    p.setChiave(Fornitore.ANTHROPIC, KEY);
    p.salva();

    final ProfiloStratega again = new ProfiloStratega(file, k -> null);
    assertThat(again.chiave(Fornitore.ANTHROPIC)).contains(KEY);
    assertThat(ProfiloStratega.maschera(KEY)).endsWith("abcd").doesNotContain("SEGRET");
    again.rimuoviChiave(Fornitore.ANTHROPIC);
    again.salva();
    assertThat(new ProfiloStratega(file, k -> null).chiave(Fornitore.ANTHROPIC)).isEmpty();
  }

  @Test
  void withoutASavedKeyTheEnvironmentVariableIsUsed() {
    final ProfiloStratega p =
        new ProfiloStratega(dir.resolve("x.properties"), Map.of("ANTHROPIC_API_KEY", KEY)::get);

    assertThat(p.chiaveSalvata(Fornitore.ANTHROPIC)).isEmpty();
    assertThat(p.chiave(Fornitore.ANTHROPIC)).contains(KEY);
    assertThat(p.chiave(Fornitore.GROQ)).isEmpty();
  }

  @Test
  void theKeyFileNextToTheGameIsImportedOnce() throws IOException {
    final Path game = Files.createDirectories(dir.resolve("gioco"));
    Files.writeString(game.resolve(ProfiloStratega.FILE_DA_IMPORTARE), "# la mia chiave\ngsk_abc123456\n");
    final Path file = dir.resolve("profilo").resolve(ProfiloStratega.FILE_NAME);
    final ProfiloStratega p = new ProfiloStratega(file, k -> null);

    assertThat(p.importaDa(game)).contains(Fornitore.GROQ);
    final ProfiloStratega saved = new ProfiloStratega(file, k -> null);
    assertThat(saved.chiave(Fornitore.GROQ)).contains("gsk_abc123456");
    assertThat(saved.fornitore()).isEqualTo(Fornitore.GROQ);
    assertThat(saved.modalitaPredefinita()).isEqualTo(Modalita.AUTOMATICO);
    // already saved: nothing imported again (the file is left for the panel to delete)
    assertThat(saved.importaDa(game)).isEmpty();
    assertThat(Files.exists(game.resolve(ProfiloStratega.FILE_DA_IMPORTARE))).isTrue();
  }

  @Test
  void theProviderIsRecognizedFromTheKey() {
    assertThat(Fornitore.daChiave("sk-ant-api03-x")).contains(Fornitore.ANTHROPIC);
    assertThat(Fornitore.daChiave("gsk_x")).contains(Fornitore.GROQ);
    assertThat(Fornitore.daChiave("sk-123")).contains(Fornitore.DEEPSEEK);
    assertThat(Fornitore.daChiave("abc")).isEmpty();
  }

  // ------------------------------------------------------------------------ prompt and answer

  @Test
  void theFixedPromptHoldsRulesPlansGuardRailsAndSchema() throws IOException {
    final String system = PromptStratega.sistema(strategy());

    assertThat(system)
        .doesNotContain("{{")
        .doesNotStartWith("<!--")
        .contains("8 victory city")
        .contains("Barbarossa")
        .contains("Overlord UK")
        .contains("blocco_terrestre")
        .contains("30 TUV per alleanza per round")
        .contains("\"motivazione\"");
    assertThat(system).doesNotContain("acquisti\":{\"mix");
    // fixed part ~4-6k tokens: well within Haiku's context, cached
    assertThat(system.length()).isBetween(10_000, 40_000);
  }

  @Test
  void theSchemaIsStrict() {
    assertStrict(PromptStratega.schema());
  }

  private static void assertStrict(final JsonElement e) {
    if (!e.isJsonObject()) {
      return;
    }
    final JsonObject o = e.getAsJsonObject();
    if (o.has("type") && "object".equals(o.get("type").getAsString())) {
      assertThat(o.get("additionalProperties").getAsBoolean()).isFalse();
      assertThat(o.getAsJsonArray("required").size())
          .isEqualTo(o.getAsJsonObject("properties").size());
    }
    o.entrySet().forEach(x -> assertStrict(x.getValue()));
    if (o.has("properties")) {
      o.getAsJsonObject("properties").entrySet().forEach(x -> assertStrict(x.getValue()));
    }
  }

  @Test
  void theAnswerBecomesDirectivesV1() {
    final JsonObject d =
        UscitaLlm.direttive(
            UscitaLlm.leggi(
                "```json\n"
                    + "{\"piano\": \"1\", \"postura\": \"OFFENSIVA\","
                    + " \"rischio\": [{\"fronte\": \"Ovest\", \"valore\": 1}],"
                    + " \"tattiche\": [{\"strumento\": \"ritirata_combattuta\", \"nazione\": \"Russians\","
                    + " \"fronte\": \"\", \"attiva\": true}],"
                    + " \"ruoli\": [{\"nazione\": \"British\", \"ruolo\": \"Egitto\"}],"
                    + " \"motivazione\": \"Avanti.\"}\n```"),
            3,
            "Allies");

    assertThat(d.get("versione").getAsInt()).isEqualTo(1);
    assertThat(d.get("round").getAsInt()).isEqualTo(3);
    assertThat(d.getAsJsonObject("rischio").get("Ovest").getAsInt()).isEqualTo(1);
    assertThat(d.getAsJsonObject("ruoli").get("British").getAsString()).isEqualTo("Egitto");
    final JsonObject tool = d.getAsJsonArray("tattiche").get(0).getAsJsonObject();
    assertThat(tool.has("fronte")).isFalse();
    assertThatThrownBy(() -> UscitaLlm.leggi("nessun json")).hasMessage("risposta senza JSON");
    assertThatThrownBy(() -> UscitaLlm.leggi("{piano: }")).hasMessage("JSON non valido nella risposta");
  }

  @Test
  void theOfflineCheckFindsErrorsAndLimits() throws IOException {
    final JsonObject state;
    try (InputStream in = Files.newInputStream(Path.of("../../proplus/strategia/esempi/stato-R2-Allies.json"))) {
      state = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    final JsonObject ok =
        JsonParser.parseString(
                "{\"piano\": \"1\", \"postura\": \"CONTRASTO\", \"rischio\": {\"Ovest\": 1},"
                    + " \"operazioni\": [{\"id\": \"Overlord UK\", \"decisione\": \"lancia\","
                    + " \"perdita_trasporti_accettata_max_TUV\": 10}],"
                    + " \"motivazione\": \"Uno. Due.\"}")
            .getAsJsonObject();
    assertThat(UscitaLlm.controlla(ok, state).valido()).isTrue();
    assertThat(UscitaLlm.controlla(ok, state).limitati()).isEmpty();

    final JsonObject bad =
        JsonParser.parseString(
                "{\"piano\": \"9\", \"postura\": \"CONTRASTO\", \"rischio\": {\"Luna\": 1, \"Ovest\": 2},"
                    + " \"operazioni\": [{\"id\": \"Overlord UK\", \"decisione\": \"lancia\","
                    + " \"perdita_trasporti_accettata_max_TUV\": 50}],"
                    + " \"bersagli\": [{\"territorio\": \"Norway\", \"peso\": 40}],"
                    + " \"motivazione\": \"Uno. Due. Tre. Quattro.\"}")
            .getAsJsonObject();
    final UscitaLlm.Controllo c = UscitaLlm.controlla(bad, state);
    assertThat(c.errori()).containsExactly("piano non ammesso: 9");
    assertThat(String.join(";", c.limitati()))
        .contains("fronte sconosciuto Luna")
        .contains("rischio fuori da -1..+1 su Ovest")
        .contains("perdita accettata 50.0")
        .contains("peso 40.0")
        .contains("motivazione oltre 3 frasi");
  }

  @Test
  void usageTextAddsCallsTokensAndCost() {
    final JsonObject u = new JsonObject();
    final var props = new games.strategy.engine.data.properties.GameProperties(null);
    Uso.registra(props, new Risposta("{}", "Haiku", "m", 1000, 200, 5000, 6000, 0.0025, false, 800));
    Uso.registraErrore(props, "Haiku");
    assertThat(Uso.chiamate(props)).isEqualTo(2);
    assertThat(Uso.testo(Uso.leggi(props)))
        .startsWith("2 chiamate (Haiku 1 + 1 errori), 12200 token")
        .contains("costo stimato $0.0025");
    assertThat(u.size()).isZero();
    assertThat(Optional.of(Uso.tetto(props))).contains(Uso.TETTO_DEFAULT);
  }

  // ------------------------------------------------------------------------ LLM step, level 2

  @Test
  void riskPerNationBecomesDirectivesV11() {
    final JsonObject d =
        UscitaLlm.direttive(
            UscitaLlm.leggi(
                "{\"piano\": \"A\", \"rischio\": [{\"nazione\": \"\", \"fronte\": \"Ovest\","
                    + " \"valore\": -1}, {\"nazione\": \"Germans\", \"fronte\": \"Est\", \"valore\": 1},"
                    + " {\"nazione\": \"Italians\", \"fronte\": \"Mediterraneo-Africa\", \"valore\": -1}]}"),
            2,
            "Axis");

    assertThat(d.get("versione").getAsDouble()).isEqualTo(1.1);
    assertThat(d.getAsJsonObject("rischio").toString())
        .isEqualTo(
            "{\"Ovest\":-1,\"Germans\":{\"Est\":1},\"Italians\":{\"Mediterraneo-Africa\":-1}}");
  }

  @Test
  void theTurnOrdersPromptIsShortAndHasTheTurnOrder() {
    final String system = PromptOrdini.sistema();

    assertThat(system)
        .doesNotContain("{{")
        .contains("GERMANIA -> URSS -> USA -> REGNO UNITO -> ITALIA -> FRANCIA")
        .contains("NON puoi cambiare piano, postura o rischio")
        .contains("\"tattiche\"");
    assertThat(system.length()).isLessThan(PromptStratega.sistema(strategyOrEmpty()).length());
    assertStrict(PromptOrdini.schema());
    assertThat(PromptOrdini.utente(JsonParser.parseString("{\"nazione\": \"British\", \"round\": 3}").getAsJsonObject(), 4))
        .contains("4/5")
        .contains("ordini del turno di British, round 3");
    assertThat(PromptStratega.sistema(strategyOrEmpty()))
        .contains("GERMANIA -> URSS -> USA -> REGNO UNITO -> ITALIA -> FRANCIA")
        .contains("tra il turno ITALIANO e quello TEDESCO del round dopo muove solo la Francia");
  }

  @Test
  void theOfflineCheckOfTurnOrders() {
    final JsonObject input =
        JsonParser.parseString(
                "{\"nazione\": \"British\", \"fronti\": [\"Ovest\", \"Est\"],"
                    + " \"operazioni\": [{\"id\": \"Overlord UK\"}]}")
            .getAsJsonObject();
    final JsonObject ok =
        JsonParser.parseString(
                "{\"bersagli\": [{\"territorio\": \"Normandy Bordeaux\", \"peso\": 15}],"
                    + " \"operazioni\": [{\"id\": \"Overlord UK\", \"decisione\": \"lancia\","
                    + " \"perdita_trasporti_accettata_max_TUV\": 10}], \"tattiche\": [],"
                    + " \"motivazione\": \"Rinforzo la testa di ponte.\"}")
            .getAsJsonObject();
    assertThat(UscitaLlm.controllaOrdini(ok, input).valido()).isTrue();
    assertThat(UscitaLlm.controllaOrdini(ok, input).limitati()).isEmpty();

    final JsonObject bad =
        JsonParser.parseString(
                "{\"piano\": \"2\", \"bersagli\": [{\"territorio\": \"X\", \"peso\": 30}],"
                    + " \"operazioni\": [{\"id\": \"Torch\", \"decisione\": \"lancia\"}],"
                    + " \"tattiche\": [{\"strumento\": \"blocco_terrestre\", \"fronte\": \"Luna\"}],"
                    + " \"motivazione\": \"Uno. Due. Tre.\"}")
            .getAsJsonObject();
    assertThat(String.join(";", UscitaLlm.controllaOrdini(bad, input).limitati()))
        .contains("campo piano ignorato")
        .contains("peso 30.0")
        .contains("operazione non della nazione Torch")
        .contains("fronte sconosciuto Luna")
        .contains("motivazione oltre 2 frasi");
    assertThat(
            UscitaLlm.controllaOrdini(
                    JsonParser.parseString("{\"bersagli\": 3}").getAsJsonObject(), input)
                .valido())
        .isFalse();
  }

  @Test
  void usageIsSplitByLevelAndTheCapIs100() {
    final var props = new games.strategy.engine.data.properties.GameProperties(null);
    Uso.registra(props, new Risposta("{}", "Haiku", "m", 1000, 200, 0, 0, 0.002, false, 800));
    Uso.registra(
        props, new Risposta("{}", "Haiku", "m", 500, 100, 0, 0, 0.001, false, 400), Uso.ORDINI);
    Uso.registraErrore(props, "Haiku", Uso.ORDINI);

    assertThat(Uso.tetto(props)).isEqualTo(100);
    assertThat(Uso.chiamate(props)).isEqualTo(3);
    assertThat(Uso.testoLivelli(Uso.leggi(props)))
        .isEqualTo("Stratega 1 chiamate $0.0020; Ordini del turno 2 chiamate (1 errori) $0.0010");
  }

  @Test
  void thePromptsV3DefineAttendiAsADeliberateDelayAndHaveTheDoctrine() {
    for (final String system : List.of(PromptStratega.sistema(strategyOrEmpty()), PromptOrdini.sistema())) {
      assertThat(system)
          .contains("RINVIO DELIBERATO")
          .contains("in attesa da N round")
          .contains("Rallentare il piano nemico vale quanto avanzare")
          .contains("Difesa solo su minaccia reale")
          .contains("obiettivi_nazionali")
          .doesNotContain("aspettare la scorta");
    }
    assertThat(PromptStratega.sistema(strategyOrEmpty()))
        .contains("DIFENSIVA solo se una capitale o la partita sono a rischio concreto");
    assertThat(PromptStratega.VERSIONE).isEqualTo("prompt-stratega-v3");
    assertThat(PromptOrdini.VERSIONE).isEqualTo("prompt-ordini-turno-v2");
  }

  @Test
  void anotherModelOfTheProviderIsAskedAndPricedAsItself() {
    final List<String> bodies = new ArrayList<>();
    final ClientApi client =
        new ClientApi(
            (url, h, body, timeout) -> {
              bodies.add(body);
              return new Http.Esito(
                  200,
                  "{\"content\": [{\"type\": \"text\", \"text\": \"{}\"}],"
                      + " \"usage\": {\"input_tokens\": 1000000, \"output_tokens\": 0}}");
            },
            "claude-sonnet-5-5");

    final Risposta r = client.chiama(Fornitore.ANTHROPIC, KEY, request());

    assertThat(JsonParser.parseString(bodies.get(0)).getAsJsonObject().get("model").getAsString())
        .isEqualTo("claude-sonnet-5-5");
    assertThat(r.fonte()).isEqualTo("Sonnet");
    assertThat(r.costoUsd()).isEqualTo(2.0);
    assertThat(ClientApi.etichetta(Fornitore.ANTHROPIC, "claude-haiku-5-5")).isEqualTo("Haiku");
    assertThat(ClientApi.etichetta(Fornitore.GROQ, "x")).isEqualTo("Groq");
  }

  private static JsonObject strategyOrEmpty() {
    try {
      return strategy();
    } catch (final IOException e) {
      return new JsonObject();
    }
  }

  private static JsonObject strategy() throws IOException {
    try (InputStream in =
        StrategaComponentiTest.class
            .getClassLoader()
            .getResourceAsStream("proplus/strategia/ww2eur40_2nd_edition.json")) {
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }
  }
}
