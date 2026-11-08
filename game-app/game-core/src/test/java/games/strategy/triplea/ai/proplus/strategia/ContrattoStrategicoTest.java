package games.strategy.triplea.ai.proplus.strategia;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.ai.proplus.diario.DiarySettings;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Strategic contract v1: the decider by rules (default), the decider reading a file, the check of
 * the directives (ranges, guard rails, fallback) and the files of the contract. Global 1940 renamed
 * as Europe 1940, Axis plan A, Allies plan 1.
 */
class ContrattoStrategicoTest extends AbstractClientSettingTestCase {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";

  @TempDir Path folder;
  @TempDir Path diaryRoot;
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer british = player("British");
  private final GamePlayer americans = player("Americans");
  private final GamePlayer russians = player("Russians");

  @BeforeEach
  void setUp() {
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
    System.clearProperty(DiarySettings.FOLDER_PROPERTY);
  }

  // ------------------------------------------------------------------------ decider by rules

  @Test
  void theRulesWriteValidDirectivesWithEveryToolOffAndTheOperationsLaunched() {
    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    final JsonObject d = r.storico().getAsJsonObject(Valutazione.KEY_DIRETTIVE);
    assertThat(d.get("versione").getAsInt()).isEqualTo(1);
    assertThat(d.get("piano").getAsString()).isEqualTo("1");
    // phase R4+ of plan 1: Ovest +1, Mediterraneo-Africa -1, Est 0, Mari +1
    assertThat(d.getAsJsonObject("rischio").get("Ovest").getAsInt()).isEqualTo(1);
    assertThat(d.getAsJsonObject("rischio").get("Mediterraneo-Africa").getAsInt()).isEqualTo(-1);
    assertThat(d.getAsJsonArray("tattiche")).isEmpty();
    assertThat(d.getAsJsonArray("operazioni").toString())
        .contains("\"id\":\"Overlord UK\",\"decisione\":\"lancia\"");
    assertThat(d.get("motivazione").getAsString()).startsWith("Regole: postura ");
    assertThat(r.storico().getAsJsonObject("decisore").get("nome").getAsString())
        .isEqualTo(Decisore.REGOLE);
    assertThat(r.testo()).contains("Direttive (regole): piano 1");
    // the rules' directives pass the check unchanged
    final Direttive.Validazione checked =
        Direttive.valida(java.util.Optional.of(d), context(), d, Decisore.FILE);
    assertThat(checked.ripiego()).isFalse();
    assertThat(checked.avvisi()).isEmpty();
  }

  @Test
  void theInputHoldsTheStrategicStateWithinTheBudget() {
    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    final JsonObject in = r.ingresso();
    assertThat(in.keySet())
        .contains(
            "round",
            "ordine_turni",
            "vc",
            "economia",
            "tuv",
            "capitali",
            "fabbriche",
            "vc_contese",
            "piano",
            "piani_ammessi",
            "lettura_nemico",
            "operazioni",
            "minacce",
            "eventi",
            "strumenti",
            "paletti");
    assertThat(in.getAsJsonArray("ordine_turni").get(0).getAsString()).isEqualTo("Germans");
    assertThat(in.getAsJsonObject("vc").getAsJsonObject("Axis").getAsJsonArray("tenute").toString())
        .contains("Germany");
    assertThat(in.getAsJsonObject("tuv").getAsJsonObject("Est").has("Axis")).isTrue();
    assertThat(in.getAsJsonArray("piani_ammessi").get(0).getAsJsonObject().get("id").getAsString())
        .isEqualTo("1");
    assertThat(
            in.getAsJsonArray("piani_ammessi")
                .get(1)
                .getAsJsonObject()
                .get("ammesso")
                .getAsBoolean())
        .isTrue();
    assertThat(in.getAsJsonArray("operazioni").toString()).contains("Overlord UK");
    assertThat(in.getAsJsonArray("minacce").size()).isLessThanOrEqualTo(Ingresso.MINACCE);
    // budget: ~5,000 tokens = ~20,000 characters of compact JSON
    assertThat(in.toString().length()).isLessThan(20_000);
  }

  // ------------------------------------------------------------------------ decider by file

  @Test
  void directivesFromAFileChangePostureRiskOperationsTargetsAndTools() throws IOException {
    write(
        DecisoreFile.fileName(4, "Allies"),
        """
        {"versione": 1, "piano": "1", "postura": "DIFENSIVA",
         "rischio": {"Ovest": -1, "Est": 3},
         "operazioni": [
           {"id": "Overlord UK", "decisione": "attendi"},
           {"id": "Overlord USA", "decisione": "lancia", "perdita_trasporti_accettata_max_TUV": 50}],
         "bersagli": [{"territorio": "Norway", "peso": 25, "origine": "contromossa"},
                      {"territorio": "Atlantide", "peso": 5}],
         "tattiche": [{"strumento": "blocco_terrestre", "nazione": "Russians", "fronte": "Est",
                       "attiva": true}],
         "lettura_nemico": {"piano": "B", "probabilita": 0.7},
         "motivazione": "Prima frase. Seconda frase. Terza frase. Quarta frase."}
        """);
    Decisori.set(data.getProperties(), "Allies", Decisore.FILE, folder.toString());

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    final JsonObject who = r.storico().getAsJsonObject("decisore");
    assertThat(who.get("nome").getAsString()).isEqualTo(Decisore.FILE);
    assertThat(who.get("ripiego").getAsBoolean()).isFalse();
    assertThat(who.getAsJsonArray("avvisi").toString())
        .contains("rischio Est 3 limitato a +1")
        .contains("perdita accettata 50 limitata a 30")
        .contains("peso di Norway 25 limitato a 20")
        .contains("bersaglio sconosciuto \\\"Atlantide\\\"")
        .contains("motivazione oltre 3 frasi");
    final PianoAttivo uk = PianoAttivo.of(data, british).orElseThrow();
    assertThat(uk.getPostura()).isEqualTo(Contromosse.DIFENSIVA);
    // Ovest: Equilibrato -1 = Cauto, then -1 for DIFENSIVA = Prudente
    assertThat(uk.livelloFronte("Ovest")).isEqualTo(RiskLevel.PRUDENTE);
    assertThat(uk.livelloFronte("Est")).isEqualTo(RiskLevel.EQUILIBRATO);
    final Territory normandy = territory("Normandy Bordeaux");
    final Strategia.Operazione overlordUk =
        uk.getOperazioni().stream()
            .filter(o -> o.nome().equals("Overlord UK"))
            .findFirst()
            .orElseThrow();
    assertThat(uk.isInAttesa(overlordUk)).isTrue();
    assertThat(uk.isBersaglioInAttesa(normandy)).isTrue();
    assertThat(uk.pesoBersaglioOperazione(normandy)).isZero();
    final PianoAttivo usa = PianoAttivo.of(data, americans).orElseThrow();
    assertThat(usa.perditaAccettata(normandy)).isEqualTo(30);
    assertThat(usa.isBersaglioInAttesa(normandy)).isFalse();
    assertThat(usa.peso(territory("Norway"))).isEqualTo(20);
    assertThat(usa.getLetturaNemico()).isEqualTo("B");
    final PianoAttivo ussr = PianoAttivo.of(data, russians).orElseThrow();
    assertThat(ussr.tattica(Tattiche.BLOCCO_TERRESTRE, "Est")).contains("direttiva");
    assertThat(ussr.tattica(Tattiche.BLOCCO_TERRESTRE, "Ovest")).isEmpty();
    assertThat(usa.tattica(Tattiche.BLOCCO_TERRESTRE, (String) null)).isEmpty();
    // the strategic state shows the directives
    assertThat(r.storico().getAsJsonObject("stato_strategico").get("postura").getAsString())
        .isEqualTo(Contromosse.DIFENSIVA);
    assertThat(r.testo()).contains("Direttive (file): piano 1, postura DIFENSIVA");
    assertThat(r.testo()).contains("Motivazione: Prima frase. Seconda frase. Terza frase.");
  }

  @Test
  void anOperationCancelledByTheDirectivesIsOffThisRound() throws IOException {
    write(
        DecisoreFile.fileName(4, "Allies"),
        "{\"operazioni\": [{\"id\": \"Overlord UK\", \"decisione\": \"annulla\"}]}");
    Decisori.set(data.getProperties(), "Allies", Decisore.FILE, folder.toString());

    Valutazione.inizioRound(data, british).orElseThrow();

    final PianoAttivo uk = PianoAttivo.of(data, british).orElseThrow();
    assertThat(uk.getOperazioni())
        .extracting(Strategia.Operazione::nome)
        .doesNotContain("Overlord UK");
    // fields absent from the file are the rules' ones
    assertThat(uk.modificatore("Ovest")).isEqualTo(1);
  }

  @Test
  void aFileCanChangeThePlanToAnAdmittedTakeOver() throws IOException {
    write(
        "direttive.json",
        "{\"Allies\": {\"R4\": {\"piano\": \"2\", \"motivazione\": \"Italia prima.\"}}}");
    Decisori.set(
        data.getProperties(), "Allies", Decisore.FILE, folder.resolve("direttive.json").toString());

    Valutazione.inizioRound(data, british).orElseThrow();

    final StatoMaggiore.Stato state =
        StatoMaggiore.getState(data.getProperties(), "Allies").orElseThrow();
    assertThat(state.piano()).isEqualTo("2");
    assertThat(state.cambi().toString()).contains("direttiva del decisore file: Italia prima.");
    final PianoAttivo uk = PianoAttivo.of(data, british).orElseThrow();
    assertThat(uk.getPiano().id()).isEqualTo("2");
    // plan 2's phase: Mediterraneo-Africa +1
    assertThat(uk.modificatore("Mediterraneo-Africa")).isEqualTo(1);
  }

  @Test
  void aPlanChangeNotAdmittedFallsBackToTheRules() throws IOException {
    write(DecisoreFile.fileName(4, "Allies"), "{\"piano\": \"3\", \"postura\": \"DIFENSIVA\"}");
    Decisori.set(data.getProperties(), "Allies", Decisore.FILE, folder.toString());

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    assertThat(r.storico().getAsJsonObject("decisore").get("ripiego").getAsBoolean()).isTrue();
    assertThat(r.testo()).contains("cambio 1 -> 3 non ammesso").contains("uso le regole");
    assertThat(StatoMaggiore.getState(data.getProperties(), "Allies").orElseThrow().piano())
        .isEqualTo("1");
    assertThat(PianoAttivo.of(data, british).orElseThrow().getPostura())
        .isNotEqualTo(Contromosse.DIFENSIVA);
  }

  @Test
  void theGuardRailsRefuseAThirdPlanChange() {
    final com.google.gson.JsonArray changes = new com.google.gson.JsonArray();
    for (final int round : List.of(1, 2)) {
      final JsonObject c = new JsonObject();
      c.addProperty("round", round);
      changes.add(c);
    }
    final Strategia s = Strategie.forGame(data).orElseThrow();
    final Decisore.Contesto ctx =
        new Decisore.Contesto(
            data,
            "Allies",
            s,
            s.alleanza("Allies").orElseThrow().piano("1").orElseThrow(),
            new StatoMaggiore.Stato("1", 1, new com.google.gson.JsonArray(), changes),
            new JsonObject(),
            new Contromosse.Cambio(java.util.Optional.empty(), "", new JsonObject()),
            9);
    assertThat(Direttive.cambioNonAmmesso(ctx, "2")).contains("gia' fatti 2 cambi di piano");
  }

  @Test
  void anInvalidOrMissingFileFallsBackToTheRulesWithANotice() throws IOException {
    write(DecisoreFile.fileName(4, "Allies"), "{ non e' json");
    Decisori.set(data.getProperties(), "Allies", Decisore.FILE, folder.toString());

    final Valutazione.Risultato bad = Valutazione.inizioRound(data, british).orElseThrow();
    assertThat(bad.storico().getAsJsonObject("decisore").get("ripiego").getAsBoolean()).isTrue();
    assertThat(bad.testo())
        .contains("Avviso del contratto: direttive del decisore file non valide");
    assertThat(bad.storico().getAsJsonObject(Valutazione.KEY_DIRETTIVE).getAsJsonArray("tattiche"))
        .isEmpty();

    data.getSequence().setRoundAndStep(5, "Combat Move", british);
    final Valutazione.Risultato missing = Valutazione.inizioRound(data, british).orElseThrow();
    assertThat(missing.testo()).contains("nessuna direttiva dal decisore file: uso le regole");

    write(DecisoreFile.fileName(6, "Allies"), "{\"postura\": \"ATTACCO\"}");
    data.getSequence().setRoundAndStep(6, "Combat Move", british);
    assertThat(Valutazione.inizioRound(data, british).orElseThrow().testo())
        .contains("postura sconosciuta ATTACCO");
  }

  // ------------------------------------------------------------------------------ files

  @Test
  void theDiaryWritesTheStateAndTheDirectivesOfEachRoundAndAlliance() throws IOException {
    System.setProperty(DiarySettings.FOLDER_PROPERTY, diaryRoot.toString());

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();
    DiaryRecorder.allianceRound(data, r);

    final Path game;
    try (Stream<Path> games = Files.list(diaryRoot)) {
      game = games.findFirst().orElseThrow();
    }
    final JsonObject state =
        JsonParser.parseString(
                Files.readString(game.resolve("stato-R4-Allies.json"), StandardCharsets.UTF_8))
            .getAsJsonObject();
    final JsonObject directives =
        JsonParser.parseString(
                Files.readString(game.resolve("direttive-R4-Allies.json"), StandardCharsets.UTF_8))
            .getAsJsonObject();
    assertThat(state.get("alleanza").getAsString()).isEqualTo("Allies");
    assertThat(directives.get("piano").getAsString()).isEqualTo("1");
    // the directives file can be given back to the decider by file as it is
    Decisori.set(data.getProperties(), "Allies", Decisore.FILE, game.toString());
    data.getSequence().setRoundAndStep(4, "Combat Move", british);
    assertThat(new DecisoreFile(game.toString()).decidi(state, context())).contains(directives);
  }

  // ------------------------------------------------------------------------------ helpers

  private Decisore.Contesto context() {
    final Strategia s = Strategie.forGame(data).orElseThrow();
    final StatoMaggiore.Stato state =
        StatoMaggiore.getState(data.getProperties(), "Allies").orElseThrow();
    return new Decisore.Contesto(
        data,
        "Allies",
        s,
        s.alleanza("Allies").orElseThrow().piano(state.piano()).orElseThrow(),
        state,
        new JsonObject(),
        new Contromosse.Cambio(java.util.Optional.empty(), "", new JsonObject()),
        4);
  }

  private void write(final String name, final String text) throws IOException {
    Files.writeString(folder.resolve(name), text, StandardCharsets.UTF_8);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }
}
