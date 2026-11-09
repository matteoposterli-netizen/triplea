package games.strategy.triplea.ai.proplus.strategia;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.ai.proplus.RiskLevel;
import games.strategy.triplea.ai.proplus.diario.DiarySettings;
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
 * LLM step, second level and risk per nation: the turn orders of a nation (merged with the
 * strategic directives, fallback, nations excluded), the input "what changed", the directives
 * schema v1.1. Fake transports (no network). Global 1940 renamed as Europe 1940, A / 1, round 4.
 */
class OrdiniTurnoTest extends AbstractClientSettingTestCase {
  private static final String EUROPE = "World War II Europe 1940 2nd Edition";

  /** Strategist: plan 1, Overlord UK waiting, British risk +1 on Ovest only (v1.1). */
  private static final String STRATEGY =
      """
      {"piano": "1", "postura": "CONTRASTO",
       "rischio": [{"nazione": "", "fronte": "Ovest", "valore": 0},
                   {"nazione": "British", "fronte": "Ovest", "valore": 1},
                   {"nazione": "Russians", "fronte": "Est", "valore": -1}],
       "operazioni": [{"id": "Overlord UK", "decisione": "attendi",
                       "perdita_trasporti_accettata_max_TUV": 0}],
       "bersagli": [{"territorio": "Norway", "peso": 12, "origine": "contromossa", "critico": false}],
       "tattiche": [{"strumento": "blocco_terrestre", "nazione": "British", "fronte": "", "attiva": true}],
       "ruoli": [], "lettura_nemico": {"piano": "A", "probabilita": 0.7},
       "motivazione": "Linea del round."}
      """;

  /** Turn orders of the British: launch Overlord UK, Normandy first, no tools; plan ignored. */
  private static final String ORDERS =
      """
      {"piano": "2", "postura": "OFFENSIVA",
       "bersagli": [{"territorio": "Normandy Bordeaux", "peso": 25, "origine": "piano", "critico": true},
                    {"territorio": "Norway", "peso": 5, "origine": "contromossa", "critico": false},
                    {"territorio": "Atlantide", "peso": 5, "origine": "piano", "critico": false}],
       "operazioni": [{"id": "Overlord UK", "decisione": "lancia",
                       "perdita_trasporti_accettata_max_TUV": 50},
                      {"id": "Overlord USA", "decisione": "annulla",
                       "perdita_trasporti_accettata_max_TUV": 0}],
       "tattiche": [],
       "motivazione": "Gli USA hanno aperto la testa di ponte. La rinforzo subito."}
      """;

  @TempDir Path profile;
  @TempDir Path diary;
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer british = data.getPlayerList().getPlayerId("British");
  private final GamePlayer americans = data.getPlayerList().getPlayerId("Americans");
  private final GamePlayer french = data.getPlayerList().getPlayerId("French");
  private final List<Richiesta> asked = new ArrayList<>();
  private Function<Modalita, ? extends Decisore> savedFactory;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
    System.setProperty(DiarySettings.FOLDER_PROPERTY, diary.toString());
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
    System.clearProperty(DiarySettings.FOLDER_PROPERTY);
  }

  /** A transport answering the strategist with {@code strategy} and the turn orders with {@code orders}. */
  private void llm(final String strategy, final Function<Richiesta, String> orders) {
    Decisori.set(data.getProperties(), "Allies", Modalita.CHIAVE_API.id(), "");
    Decisori.fabbricaLlm =
        m ->
            new DecisoreLlm(
                m,
                List.of(
                    new DecisoreLlm.Trasporto() {
                      @Override
                      public String nome() {
                        return "Haiku";
                      }

                      @Override
                      public Optional<String> nonDisponibile() {
                        return Optional.empty();
                      }

                      @Override
                      public Risposta chiama(final Richiesta r) {
                        asked.add(r);
                        final String text =
                            r.sistema().contains("ufficiale operativo")
                                ? orders.apply(r)
                                : strategy;
                        return new Risposta(
                            text, "Haiku", "claude-haiku-5-5", 1000, 200, 3000, 0, 0.0005, false, 500);
                      }
                    }));
  }

  private JsonObject directivesOf(final String nation) {
    final JsonObject last =
        StatoMaggiore.getState(data.getProperties(), "Allies")
            .orElseThrow()
            .storico()
            .get(
                StatoMaggiore.getState(data.getProperties(), "Allies").orElseThrow().storico().size()
                    - 1)
            .getAsJsonObject();
    return last.getAsJsonObject(OrdiniTurno.KEY)
        .getAsJsonObject(nation)
        .getAsJsonObject(Valutazione.KEY_DIRETTIVE);
  }

  @Test
  void theTurnOrdersChangeOnlyTargetsOperationsAndToolsOfTheNation() {
    llm(STRATEGY, r -> ORDERS);
    Valutazione.inizioRound(data, british).orElseThrow();

    final OrdiniTurno.Risultato r = OrdiniTurno.inizioTurno(data, british).orElseThrow();

    assertThat(r.chiamato()).isTrue();
    final JsonObject merged = directivesOf("British");
    // plan and posture stay the strategist's
    assertThat(merged.get("piano").getAsString()).isEqualTo("1");
    assertThat(merged.get("postura").getAsString()).isEqualTo("CONTRASTO");
    assertThat(merged.getAsJsonArray("operazioni").toString())
        .contains("\"id\":\"Overlord UK\",\"decisione\":\"lancia\",\"perdita_trasporti_accettata_max_TUV\":30.0");
    // not a British operation: ignored
    assertThat(merged.getAsJsonArray("operazioni").toString())
        .doesNotContain("\"id\":\"Overlord USA\",\"decisione\":\"annulla\"");
    assertThat(merged.getAsJsonArray("bersagli").toString())
        .contains("\"territorio\":\"Normandy Bordeaux\",\"peso\":20.0,\"nazioni\":[\"British\"]");
    // the strategic Norway target stays for the other nations, the British get their own weight
    final String targets = merged.getAsJsonArray("bersagli").toString();
    assertThat(targets)
        .contains("\"territorio\":\"Norway\",\"peso\":5.0,\"nazioni\":[\"British\"]")
        .doesNotContain("Atlantide");
    merged
        .getAsJsonArray("bersagli")
        .forEach(
            t -> {
              final JsonObject o = t.getAsJsonObject();
              if ("Norway".equals(o.get("territorio").getAsString())
                  && o.get("peso").getAsDouble() == 12) {
                assertThat(o.getAsJsonArray("nazioni").toString())
                    .contains("Americans")
                    .doesNotContain("British");
              }
            });
    // empty tool list: the British block is off for them
    assertThat(merged.getAsJsonArray("tattiche").toString()).doesNotContain("British");
    assertThat(r.testo())
        .contains("Avviso degli ordini del turno: ordini del turno: campo piano ignorato")
        .contains("Ordini del turno British (Haiku): bersagli Normandy Bordeaux (20)")
        .contains("operazioni Overlord UK lancia (perdita max 30)")
        .contains("Motivazione: Gli USA hanno aperto la testa di ponte.");
    final JsonObject levels = Uso.leggi(data.getProperties()).getAsJsonObject("per_livello");
    assertThat(levels.getAsJsonObject(Uso.STRATEGA).get("chiamate").getAsInt()).isEqualTo(1);
    assertThat(levels.getAsJsonObject(Uso.ORDINI).get("chiamate").getAsInt()).isEqualTo(1);
    assertThat(r.testo()).contains("Ordini del turno 1 chiamate");
  }

  @Test
  void theNationPlaysItsOrdersAndTheOthersTheStrategicDirectives() {
    llm(STRATEGY, r -> ORDERS);
    Valutazione.inizioRound(data, british).orElseThrow();
    OrdiniTurno.inizioTurno(data, british).orElseThrow();
    final Territory normandy = data.getMap().getTerritoryOrThrow("Normandy Bordeaux");

    final PianoAttivo uk = PianoAttivo.of(data, british).orElseThrow();
    final PianoAttivo us = PianoAttivo.of(data, americans).orElseThrow();

    assertThat(uk.isCritico(normandy)).isTrue();
    assertThat(uk.obiettiviDi(normandy)).anySatisfy(o -> assertThat(o.peso()).isEqualTo(20.0));
    assertThat(us.obiettiviDi(normandy)).noneSatisfy(o -> assertThat(o.peso()).isEqualTo(20.0));
    assertThat(uk.tattica("blocco_terrestre", "Ovest")).isEmpty();
    // risk v1.1: British +1 on Ovest, Americans 0
    assertThat(uk.modificatore("Ovest")).isEqualTo(1);
    assertThat(us.modificatore("Ovest")).isZero();
  }

  @Test
  void anErrorLeavesTheStrategicDirectives() {
    llm(
        STRATEGY,
        r -> {
          throw new ErroreStratega("nessuna risposta da Haiku entro 45 s (2 tentativi)");
        });
    Valutazione.inizioRound(data, british).orElseThrow();

    final OrdiniTurno.Risultato r = OrdiniTurno.inizioTurno(data, british).orElseThrow();

    assertThat(r.testo())
        .contains("ordini del turno non disponibili (Haiku: nessuna risposta da Haiku entro 45 s")
        .contains("Ordini del turno British: nessuno, valgono le direttive dello stratega");
    assertThat(PianoAttivo.of(data, british).orElseThrow().tattica("blocco_terrestre", "Ovest"))
        .isPresent();
    assertThat(
            Uso.leggi(data.getProperties())
                .getAsJsonObject("per_livello")
                .getAsJsonObject(Uso.ORDINI)
                .get("errori")
                .getAsInt())
        .isEqualTo(1);
  }

  @Test
  void theFrenchAndNationsWithoutPusGetNoCall() {
    llm(STRATEGY, r -> ORDERS);
    Valutazione.inizioRound(data, british).orElseThrow();
    data.getSequence().setRoundAndStep(4, "Combat Move", french);

    final OrdiniTurno.Risultato fr = OrdiniTurno.inizioTurno(data, french).orElseThrow();
    americans
        .getResources()
        .removeResourceUpTo(
            data.getResourceList().getResourceOrThrow(Constants.PUS),
            americans.getResources().getQuantity(Constants.PUS));
    final OrdiniTurno.Risultato us = OrdiniTurno.inizioTurno(data, americans).orElseThrow();

    assertThat(fr.chiamato()).isFalse();
    assertThat(us.chiamato()).isFalse();
    assertThat(asked).hasSize(1); // the strategist only
    assertThat(fr.ingresso().get("nazione").getAsString()).isEqualTo("French");
  }

  @Test
  void withTheRulesNothingIsAskedButTheInputIsBuilt() {
    Valutazione.inizioRound(data, british).orElseThrow();

    final OrdiniTurno.Risultato r = OrdiniTurno.inizioTurno(data, british).orElseThrow();

    assertThat(r.chiamato()).isFalse();
    assertThat(r.testo()).isEmpty();
    final JsonObject in = r.ingresso();
    assertThat(in.keySet())
        .contains(
            "nazione",
            "muovono_dopo",
            "direttive",
            "cambiamenti",
            "pu",
            "stack",
            "flotte",
            "bersagli_vicini",
            "minacce",
            "operazioni");
    assertThat(in.getAsJsonArray("muovono_dopo").toString())
        .contains("Germans")
        .doesNotContain("\"British\"");
    assertThat(in.getAsJsonArray("operazioni").toString()).contains("Overlord UK");
    assertThat(in.toString().length()).isLessThan(12_000);
  }

  @Test
  void theInputListsWhatChangedSinceTheLastDecision() {
    Valutazione.inizioRound(data, british).orElseThrow();
    // after the strategist's snapshot: France taken by the British, a German stack gone
    final Territory france = data.getMap().getTerritoryOrThrow("France");
    final String was = france.getOwner().getName();
    france.setOwner(british);
    final Territory germany = data.getMap().getTerritoryOrThrow("Germany");
    germany.getUnitCollection().removeAll(germany.getUnits());

    final JsonObject changes = OrdiniTurno.inizioTurno(data, british).orElseThrow().ingresso()
        .getAsJsonObject("cambiamenti");

    assertThat(changes.getAsJsonArray("conquiste").toString())
        .contains("\"territorio\":\"France\",\"da\":\"" + was + "\",\"a\":\"British\"");
    assertThat(changes.getAsJsonArray("nemici_spostati").toString())
        .contains("\"territorio\":\"Germany\"");
  }

  @Test
  void anOperationWaitingForThreeRoundsIsFlaggedToTheStrategist() {
    llm(STRATEGY, r -> ORDERS);
    for (final int round : List.of(4, 5, 6)) {
      data.getSequence().setRoundAndStep(round, "Combat Move", british);
      Valutazione.inizioRound(data, british).orElseThrow();
    }
    data.getSequence().setRoundAndStep(7, "Combat Move", british);

    final JsonObject in = Valutazione.inizioRound(data, british).orElseThrow().ingresso();

    final JsonObject overlord =
        java.util.stream.StreamSupport.stream(in.getAsJsonArray("operazioni").spliterator(), false)
            .map(e -> e.getAsJsonObject())
            .filter(o -> "Overlord UK".equals(o.get("id").getAsString()))
            .findFirst()
            .orElseThrow();
    assertThat(overlord.get("round_in_attesa").getAsInt()).isEqualTo(3);
    assertThat(overlord.get("avviso").getAsString())
        .isEqualTo("operazione Overlord UK in attesa da 3 round: motivare o lanciare");
  }

  @Test
  void theInputsListTheOwnNationalObjectivesWithTheirPus() {
    final JsonObject in = Valutazione.inizioRound(data, british).orElseThrow().ingresso();
    final JsonObject orders = OrdiniTurno.inizioTurno(data, british).orElseThrow().ingresso();

    assertThat(in.has("obiettivi_nazionali")).isTrue();
    in.getAsJsonArray("obiettivi_nazionali")
        .forEach(
            e -> {
              final JsonObject o = e.getAsJsonObject();
              assertThat(o.get("pu_per_turno").getAsInt()).isPositive();
              assertThat(o.get("mancano").getAsInt()).isBetween(0, 3);
              assertThat(o.get("obiettivo").getAsString()).doesNotStartWith("objectiveAttachment");
            });
    orders
        .getAsJsonArray("obiettivi_nazionali")
        .forEach(e -> assertThat(e.getAsJsonObject().get("nazione").getAsString()).isEqualTo("British"));
    assertThat(Ingresso.readableName("objectiveAttachment_Germans_3_Control_Caucasus", "Germans"))
        .isEqualTo("Control Caucasus");
  }

  @Test
  void directivesV11CheckRiskPerNation() {
    llm(
        STRATEGY.replace(
            "{\"nazione\": \"Russians\", \"fronte\": \"Est\", \"valore\": -1}",
            "{\"nazione\": \"Russians\", \"fronte\": \"Est\", \"valore\": -1},"
                + " {\"nazione\": \"Germans\", \"fronte\": \"Est\", \"valore\": 1},"
                + " {\"nazione\": \"British\", \"fronte\": \"Luna\", \"valore\": 1}"),
        r -> ORDERS);

    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    final JsonObject d = r.storico().getAsJsonObject(Valutazione.KEY_DIRETTIVE);
    assertThat(d.get("versione").getAsDouble()).isEqualTo(Direttive.VERSIONE_1_1);
    assertThat(d.getAsJsonObject("rischio").getAsJsonObject("British").get("Ovest").getAsInt())
        .isEqualTo(1);
    assertThat(d.getAsJsonObject("rischio").has("Germans")).isFalse();
    assertThat(r.storico().getAsJsonObject("decisore").getAsJsonArray("avvisi").toString())
        .contains("nazione Germans non dell'alleanza ignorata")
        .contains("fronte sconosciuto Luna ignorato");
    // effective risk per nation and front in the history entry and the diary
    final JsonObject effective = r.storico().getAsJsonObject("rischio_effettivo");
    assertThat(effective.getAsJsonObject("British").get("Ovest").getAsInt()).isEqualTo(4);
    assertThat(effective.getAsJsonObject("Americans").get("Ovest").getAsInt()).isEqualTo(3);
    assertThat(effective.getAsJsonObject("Russians").get("Est").getAsInt()).isEqualTo(2);
    assertThat(r.testo())
        .contains("Rischio effettivo per nazione e fronte (base 3 Equilibrato): ")
        .contains("British Ovest 4");
  }

  @Test
  void theRulesKeepTheRiskOfTheAllianceAndAV1Schema() {
    final Valutazione.Risultato r = Valutazione.inizioRound(data, british).orElseThrow();

    final JsonObject d = r.storico().getAsJsonObject(Valutazione.KEY_DIRETTIVE);
    assertThat(d.get("versione").getAsInt()).isEqualTo(Direttive.VERSIONE);
    assertThat(d.getAsJsonObject("rischio").entrySet())
        .allSatisfy(e -> assertThat(e.getValue().isJsonPrimitive()).isTrue());
    final Direttive dir = Direttive.of(d);
    assertThat(dir.rischio("British", "Ovest")).isEqualTo(dir.rischio("Ovest"));
  }

  @Test
  void aRiskAmplitudeOfZeroInTheDataFileFlattensTheOffsets() {
    final JsonObject raw =
        JsonParser.parseString(
                "{\"rischio\": {\"Ovest\": 1, \"British\": {\"Est\": -1}}, \"postura\":"
                    + " \"OFFENSIVA\"}")
            .getAsJsonObject();
    final Strategia flat =
        Strategia.parse(
            new java.io.StringReader(
                Strategie.json(data)
                    .orElseThrow()
                    .toString()
                    .replace("\"ampiezza_rischio\":1", "\"ampiezza_rischio\":0")));
    final Strategia.Piano plan = flat.alleanza("Allies").orElseThrow().piano("1").orElseThrow();
    final Decisore.Contesto ctx =
        new Decisore.Contesto(
            data,
            "Allies",
            flat,
            plan,
            StatoMaggiore.getState(data.getProperties(), "Allies").orElseThrow(),
            new JsonObject(),
            new Contromosse.Cambio(Optional.empty(), "", new JsonObject()),
            4);
    final JsonObject rules = DecisoreRegole.base(flat, "Allies", plan, 4);

    final Direttive.Validazione v = Direttive.valida(Optional.of(raw), ctx, rules, "test");

    assertThat(v.direttive().getAsJsonObject("rischio").get("Ovest").getAsInt()).isZero();
    assertThat(
            v.direttive().getAsJsonObject("rischio").getAsJsonObject("British").get("Est").getAsInt())
        .isZero();
  }
}
