package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Stato;
import games.strategy.triplea.ai.proplus.StrategaCandidati.Candidato;
import games.strategy.triplea.ai.proplus.StrategaCandidati.Tipo;
import games.strategy.triplea.ai.proplus.strategia.llm.ErroreStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.PromptOrdiniTurnoV3;
import games.strategy.triplea.ai.proplus.strategia.llm.PromptStrategaV4;
import games.strategy.triplea.ai.proplus.strategia.llm.Richiesta;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step STRATEGA LLM: the "Pro+ Stratega LLM" player. Every LLM call is a fake (no network):
 * registration (nudo, LLM at the decision points, cap), parachute without key / on an error / with
 * the cap reached (no operation, one warning), candidates generated and valid, the portfolio
 * applied within the engine's limits, the proposal outside the list refused with its reason, each
 * type of operation (reserves, hold of the units, execution, end), no call for the other players.
 */
class StrategaLlmCompletoTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261110L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final List<Richiesta> asked = new ArrayList<>();
  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
  }

  @AfterEach
  void tearDown() {
    DecisoreOperazione.setFabbrica(null);
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

  // ---------------------------------------------------------------------------- registration

  @Test
  void theNationIsRegisteredAndPlaysNudoWithTheLlmAtTheDecisionPoints() {
    final var props = data.getProperties();
    assertThat(Stratega.gioca(data, player("Germans"))).isFalse();
    Stratega.registra(data, player("Germans"));
    Stratega.registra(data, player("Italians"));
    assertThat(Stratega.nazioni(props, "Axis")).containsExactlyInAnyOrder("Germans", "Italians");
    assertThat(Stratega.gioca(data, player("Germans"))).isTrue();
    assertThat(Stratega.gioca(data, player("British"))).isFalse();
    assertThat(Ablazione.spenti(props, "Axis"))
        .containsExactlyInAnyOrder(Ablazione.Componente.values());
    assertThat(Ablazione.spenti(props, "Allies")).isEmpty();
    assertThat(DecisoreOperazione.modello(props, "Axis")).contains("claude-sonnet-5-5");
    assertThat(DecisoreOperazione.modello(props, "Allies")).isEmpty();
    assertThat(Stratega.budget(props)).isEqualTo(2.0);
    assertThat(DecisoreOperazione.budget(props)).isEqualTo(2.0);
    assertThat(Stratega.PLAYER_LABEL).isEqualTo("Pro+ Stratega LLM (Europe 1940)");
  }

  @Test
  void theCapOfTheGameComesFromTheUserProfile() throws Exception {
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setTettoPartitaUsd(0.75);
    p.salva();
    Stratega.registra(data, player("Germans"));
    assertThat(Stratega.budget(data.getProperties())).isEqualTo(0.75);
  }

  // ------------------------------------------------------------------------------ parachute

  @Test
  void withoutKeyNoOperationAndOneWarning() {
    registerAxis();
    DecisoreOperazione.setFabbrica(
        model ->
            new DecisoreOperazione.Trasporto() {
              @Override
              public Optional<String> nonDisponibile() {
                return Optional.of("nessuna chiave Anthropic");
              }

              @Override
              public Risposta chiama(final Richiesta r) {
                throw new AssertionError("no call without a key");
              }
            });
    final Stratega.Esito first = start("Germans", 1);
    assertThat(first.avviso()).isPresent();
    assertThat(first.avviso().get()).startsWith(Stratega.AVVISO);
    assertThat(OperazioneCongiunta.dinamiche(data.getProperties(), "Axis")).isEmpty();
    // the next round: no new warning, still no operation (Hard)
    final Stratega.Esito second = start("Germans", 2);
    assertThat(second.avviso()).isEmpty();
    assertThat(OperazioneCongiunta.tutte(data, player("Germans"))).isEmpty();
    final JsonObject u = Stratega.usoTotale(data.getProperties());
    assertThat(
            u.getAsJsonObject("strategico")
                .getAsJsonObject("ripieghi")
                .get("LLM non disponibile")
                .getAsInt())
        .isEqualTo(2);
  }

  @Test
  void onAnErrorOrAnInvalidAnswerOrTheCapNoOperation() {
    registerAxis();
    fake(
        r -> {
          throw new ErroreStratega("timeout");
        });
    assertThat(start("Germans", 1).avviso()).isPresent();
    assertThat(OperazioneCongiunta.dinamiche(data.getProperties(), "Axis")).isEmpty();
    fake(r -> answer("non so"));
    start("Germans", 2);
    assertThat(OperazioneCongiunta.dinamiche(data.getProperties(), "Axis")).isEmpty();
    // cap of the game reached: no call at all
    Stratega.setBudget(data.getProperties(), 0.01);
    asked.clear();
    fake(r -> answer(launch("C1")));
    start("Germans", 3);
    assertThat(asked).isEmpty();
    final JsonObject r =
        Stratega.usoTotale(data.getProperties())
            .getAsJsonObject("strategico")
            .getAsJsonObject("ripieghi");
    assertThat(r.get("errore o timeout").getAsInt()).isEqualTo(1);
    assertThat(r.get("risposta non valida").getAsInt()).isEqualTo(1);
    assertThat(r.get("budget esaurito").getAsInt()).isEqualTo(1);
  }

  @Test
  void theDecisionPointsOfTheStrategistFallBackToHard() {
    registerAxis();
    fake(
        r -> {
          throw new ErroreStratega("timeout");
        });
    final JsonObject in = new JsonObject();
    in.addProperty("tipo_operazione", "offensiva");
    final var x =
        DecisoreOperazione.decidi(
            data.getProperties(),
            "Axis",
            games.strategy.triplea.ai.proplus.strategia.DecisioneOperazione.Punto.TAPPA1,
            in,
            new games.strategy.triplea.ai.proplus.strategia.DecisioneOperazione(
                games.strategy.triplea.ai.proplus.strategia.DecisioneOperazione.Azione.LANCIA,
                0.5,
                "formula"));
    assertThat(x.ripiego()).isTrue();
    // the engine turns this fallback into Hard's decision for the strategist's operations
    assertThat(OperazioneCongiunta.isStratega("stratega-offensiva-R1-Egypt")).isTrue();
    assertThat(OperazioneCongiunta.tipoStratega("stratega-offensiva-R1-Egypt"))
        .isEqualTo("offensiva");
    // the turn orders of the general operations use their own prompt
    assertThat(PromptOrdiniTurnoV3.sistema())
        .contains("67%")
        .contains("31%")
        .contains("offensiva")
        .contains("rinforzo")
        .doesNotContain("{{");
  }

  // ----------------------------------------------------------------------------- candidates

  @Test
  void candidatesAreFewWithTheEngineNumbersAndValidDefinitions() {
    registerAxis();
    final StrategaCandidati gen = generator("Germans", 2);
    final List<Candidato> list = gen.calcola();
    assertThat(list).isNotEmpty().hasSizeLessThanOrEqualTo(StrategaConfig.get().maxCandidati());
    for (final Candidato c : list) {
      assertThat(c.id()).startsWith("C");
      assertThat(ProPlusSettings.allianceOf(data, territory(c.bersaglio()).getOwner()))
          .as("no neutral target: %s", c.bersaglio())
          .isPresent();
      assertThat(c.json().get("tipo").getAsString()).isEqualTo(c.tipo().id());
      assertThat(c.json().has("valore")).isTrue();
      assertThat(c.roundMinimo()).isBetween(2, 2 + StrategaConfig.get().orizzonteRound());
      assertThat(Set.of("Germans", "Italians")).contains(c.apre(), c.chiude());
      if (c.tipo().conquista()) {
        assertThat(c.apre()).isNotEqualTo(c.chiude());
        assertThat(c.json().has("tappa1")).isTrue();
        assertThat(c.json().has("tappa2")).isTrue();
        assertThat(c.json().has("presa_stimata_pct")).isTrue();
      }
      if (c.json().has("tenuta_per_impegno")) {
        assertThat(c.json().getAsJsonArray("tenuta_per_impegno")).hasSize(4);
        assertThat(c.json().getAsJsonObject("contrattacco_massimo")).isNotNull();
      }
      final JsonObject def = gen.definizione(c, "stratega-" + c.tipo().id() + "-R2-x", 0, 0, 0, "");
      final var parsed = OperazioneCongiunta.parse(StrategaCandidati.wrap(def)).get(0);
      assertThat(parsed.roundMin()).isGreaterThanOrEqualTo(c.roundMinimo());
      assertThat(parsed.tettoPerdita()).isEqualTo(StrategaConfig.get().tettoPerditaDefault());
      assertThat(parsed.riserve().values())
          .allMatch(r -> r.quota() <= StrategaConfig.get().quotaPuMaxPct());
    }
    final JsonObject reading = gen.letturaAvversario(new JsonObject());
    assertThat(reading.has("minacce_capitali_vc")).isTrue();
    assertThat(reading.has("storico_nemico_per_tipo")).isTrue();
    assertThat(reading.has("ammassamenti_per_fronte")).isTrue();
  }

  // ------------------------------------------------------------------------------ portfolio

  @Test
  void thePortfolioIsAppliedWithinTheLimitsAndTheProposalOutsideIsRefused() {
    registerAxis();
    // five launches asked: three operations at most per alliance
    fake(
        r -> {
          final JsonObject state = state(r);
          final List<String> ids = new ArrayList<>();
          for (final JsonElement e : state.getAsJsonArray("candidati")) {
            ids.add(e.getAsJsonObject().get("id").getAsString());
          }
          return answer(
              portfolio(
                  ids.stream().limit(5).map(StrategaLlmCompletoTest::avvia).toList(),
                  outside("offensiva", "Germany", "Germans", "Italians")));
        });
    start("Germans", 2);
    final var props = data.getProperties();
    final List<OperazioneCongiunta.Definizione> ops = OperazioneCongiunta.dinamiche(props, "Axis");
    assertThat(ops).hasSizeLessThanOrEqualTo(StrategaConfig.get().maxOperazioni()).isNotEmpty();
    assertThat(ops).allMatch(d -> OperazioneCongiunta.isStratega(d.id()));
    final JsonObject pf = Stratega.portafoglio(props, "Axis");
    final List<String> reasons = new ArrayList<>();
    pf.getAsJsonArray("rifiutate")
        .forEach(e -> reasons.add(e.getAsJsonObject().get("motivo").getAsString()));
    assertThat(reasons).anyMatch(s -> s.contains("gia' dell'alleanza"));
    // a neutral is never a target, nor a candidate
    final JsonObject neutral =
        JsonParser.parseString(outside("offensiva", "Venezuela", "Germans", "Italians"))
            .getAsJsonObject();
    assertThat(StrategaFuoriLista.valida(data, "Axis", Stratega.nazioni(props, "Axis"), neutral))
        .hasValueSatisfying(w -> assertThat(w).contains("neutrale"));
    if (ops.size() == StrategaConfig.get().maxOperazioni()) {
      assertThat(reasons).anyMatch(s -> s.contains("massimo"));
    }
    // the game log line lists the active operations and their state, no estimate nor reason
    final String line = Stratega.rigaFineTurno(data, player("Germans"));
    assertThat(line)
        .startsWith("Stratega LLM Germans: ")
        .doesNotContain("%")
        .doesNotContain("simul");
    // an unknown candidate and an unknown operation are refused too
    fake(r -> answer(portfolio(List.of(avvia("C99"), annulla("stratega-x")), none())));
    start("Germans", 3);
    final List<String> more = new ArrayList<>();
    Stratega.portafoglio(props, "Axis")
        .getAsJsonArray("rifiutate")
        .forEach(e -> more.add(e.getAsJsonObject().get("motivo").getAsString()));
    assertThat(more).anyMatch(s -> s.contains("C99")).anyMatch(s -> s.contains("non in corso"));
  }

  @Test
  void theAnswerIsCheckedAndTheSchemaIsStrict() {
    final JsonObject ok = StrategaDecisione.leggi(portfolio(List.of(avvia("C2")), none()));
    assertThat(ok.getAsJsonArray("portafoglio")).hasSize(1);
    assertThatThrownBy(
            () -> StrategaDecisione.leggi("{\"portafoglio\":[{\"azione\":\"attacca\"}]}"))
        .isInstanceOf(ErroreStratega.class);
    assertThatThrownBy(
            () ->
                StrategaDecisione.leggi(
                    portfolio(List.of(avvia("C1").replace("0.6", "6")), none())))
        .isInstanceOf(ErroreStratega.class);
    final JsonObject schema = PromptStrategaV4.schema();
    assertThat(schema.get("additionalProperties").getAsBoolean()).isFalse();
    assertThat(schema.getAsJsonArray("required")).hasSize(4);
    assertThat(PromptStrategaV4.sistema("- vincolo"))
        .contains("REALE")
        .contains("67%")
        .doesNotContain("{{");
  }

  // ------------------------------------------------------------------- the four types

  @Test
  void holdAndReinforcementKeepTheGarrisonReinforceAndEnd() {
    registerAxis();
    final GamePlayer italians = player("Italians");
    final GamePlayer germans = player("Germans");
    // Egypt Italian with 1 infantry, 3 Italian infantry in Alexandria, British armour near
    ownedBy("Egypt", "Italians");
    clear("Egypt");
    add("Egypt", italians, "infantry", 1);
    ownedBy("Alexandria", "Italians");
    clear("Alexandria");
    add("Alexandria", italians, "infantry", 3);
    add("Trans-Jordan", player("British"), "armour", 3);
    final StrategaCandidati gen = generator("Italians", 2);
    final Candidato hold =
        new Candidato(
            "C1",
            Tipo.TENUTA,
            "Egypt",
            "Italians",
            "Germans",
            2,
            "",
            "",
            List.of(),
            10,
            1,
            new JsonObject());
    install(gen, hold, "stratega-tenuta-R2-Egypt", 2);
    final Registro reg = Registro.leggi(data.getProperties(), "stratega-tenuta-R2-Egypt");
    reg.setStato(Stato.TENUTA);
    reg.setRoundObiettivo(2);
    reg.set("tenuta_bersaglio", "Egypt");
    reg.set("tenuta_dal", 2);
    reg.salva(data.getProperties());
    // hold: the garrison stays out of Hard's combat move
    final var combat =
        OperazioniCongiunte.combattimento(proData(italians, 2, "Combat Move"), calc(), moves());
    assertThat(combat.tenute()).anyMatch(u -> Matches.unitIsOwnedBy(italians).test(u));
    // execution: under the threshold the engine reinforces from Alexandria (LLM: keep)
    fake(r -> answer("{\"azione\":\"tieni\",\"prob_tenuta_stimata\":0.6,\"motivazione\":\"x\"}"));
    final IMoveDelegate moves = moves();
    OperazioniCongiunte.nonCombattimento(proData(italians, 2, "Non Combat Move"), calc(), moves);
    verify(moves, atLeastOnce()).performMove(any(MoveDescription.class));
    // reserves: with the last estimate under the threshold, land units in the purchase
    final Registro after = Registro.leggi(data.getProperties(), "stratega-tenuta-R2-Egypt");
    if (after.json().has("ultima_stima_tenuta")
        && after.json().get("ultima_stima_tenuta").getAsDouble()
            < StrategaConfig.get().sogliaRinforzi()) {
      final Optional<ProPurchaseOrders> o =
          OperazioniCongiunte.ordiniAcquisto(proData(italians, 3, "Purchase"));
      assertThat(o).isPresent();
      assertThat(o.get().mandatory().get(0).category()).isEqualTo("terra");
    }
    // end: held long enough at the closer's turn = COMPLETATA
    OperazioniCongiunte.aggiorna(proData(germans, 2 + 2, "Purchase"), calc());
    assertThat(Registro.leggi(data.getProperties(), "stratega-tenuta-R2-Egypt").stato())
        .isEqualTo(Stato.COMPLETATA);
  }

  @Test
  void aReinforcementOfAnAllyMovesTheUnitsOfAnotherNationAndFailsIfLost() {
    registerAxis();
    final GamePlayer italians = player("Italians");
    final GamePlayer germans = player("Germans");
    ownedBy("Alexandria", "Germans");
    clear("Alexandria");
    add("Alexandria", germans, "infantry", 1);
    ownedBy("Egypt", "Italians");
    clear("Egypt");
    add("Egypt", italians, "infantry", 4);
    add("Trans-Jordan", player("British"), "armour", 3);
    final StrategaCandidati gen = generator("Italians", 2);
    final Candidato c =
        new Candidato(
            "C1",
            Tipo.RINFORZO,
            "Alexandria",
            "Italians",
            "Italians",
            2,
            "",
            "",
            List.of(),
            5,
            1,
            new JsonObject());
    install(gen, c, "stratega-rinforzo-R2-Alexandria", 2);
    final Registro reg = Registro.leggi(data.getProperties(), "stratega-rinforzo-R2-Alexandria");
    reg.setStato(Stato.TENUTA);
    reg.set("tenuta_bersaglio", "Alexandria");
    reg.set("tenuta_dal", 2);
    reg.salva(data.getProperties());
    assertThat(OperazioneCongiunta.tutte(data, italians)).hasSize(1);
    assertThat(OperazioneCongiunta.tutte(data, germans)).isEmpty();
    fake(r -> answer("{\"azione\":\"tieni\",\"prob_tenuta_stimata\":0.6,\"motivazione\":\"x\"}"));
    final IMoveDelegate moves = moves();
    OperazioniCongiunte.nonCombattimento(proData(italians, 2, "Non Combat Move"), calc(), moves);
    verify(moves, atLeastOnce()).performMove(any(MoveDescription.class));
    // the territory lost: FALLITA at the next turn of the nation
    ownedBy("Alexandria", "British");
    OperazioniCongiunte.aggiorna(proData(italians, 3, "Purchase"), calc());
    assertThat(Registro.leggi(data.getProperties(), "stratega-rinforzo-R2-Alexandria").stato())
        .isEqualTo(Stato.FALLITA);
  }

  @Test
  void anOffensiveReservesHoldsTheGatheredUnitsExecutesAndIsCancelled() {
    registerAxis();
    final GamePlayer italians = player("Italians");
    final GamePlayer germans = player("Germans");
    final StrategaCandidati gen = generator("Germans", 1);
    final Candidato c =
        new Candidato(
            "C1",
            Tipo.OFFENSIVA,
            "Egypt",
            "Germans",
            "Italians",
            3,
            "",
            "",
            List.of(),
            10,
            1,
            new JsonObject());
    install(gen, c, "stratega-offensiva-R1-Egypt", 3);
    OperazioniCongiunte.aggiorna(proData(germans, 1, "Purchase"), calc());
    final Registro reg = Registro.leggi(data.getProperties(), "stratega-offensiva-R1-Egypt");
    assertThat(reg.roundObiettivo()).isGreaterThanOrEqualTo(3);
    // reserves: mandatory land units within the share of the data file
    final Optional<ProPurchaseOrders> o =
        OperazioniCongiunte.ordiniAcquisto(proData(italians, 1, "Purchase"));
    assertThat(o).isPresent();
    assertThat(o.get().mandatory()).anyMatch(m -> m.category().equals("terra"));
    assertThat(o.get().minimumShare()).isLessThanOrEqualTo(StrategaConfig.get().quotaPuMaxPct());
    // positioning round: the units gathered stay out of Hard's attacks
    final int target = reg.roundObiettivo();
    OperazioniCongiunte.aggiorna(proData(italians, target - 1, "Purchase"), calc());
    final var held =
        OperazioniCongiunte.combattimento(
            proData(italians, target - 1, "Combat Move"), calc(), moves());
    assertThat(held.tenute()).allMatch(u -> Matches.unitIsOwnedBy(italians).test(u));
    // execution: at the target round the opener's step is decided (LLM asked through the fake)
    asked.clear();
    fake(r -> answer("{\"azione\":\"lancia\",\"prob_tenuta_stimata\":0.6,\"motivazione\":\"x\"}"));
    OperazioniCongiunte.aggiorna(proData(germans, target, "Purchase"), calc());
    OperazioniCongiunte.combattimento(proData(germans, target, "Combat Move"), calc(), moves());
    final Registro done = Registro.leggi(data.getProperties(), "stratega-offensiva-R1-Egypt");
    final List<String> types = new ArrayList<>();
    done.eventi().forEach(e -> types.add(e.getAsJsonObject().get("tipo").getAsString()));
    assertThat(types).as("the opener's step was evaluated at the target round").contains("tappa1");
    // end: the strategist cancels, units back to Hard
    done.setStato(Stato.ANNULLATA);
    done.salva(data.getProperties());
    assertThat(
            OperazioniCongiunte.combattimento(
                    proData(italians, target, "Combat Move"), calc(), moves())
                .tenute())
        .isEmpty();
  }

  @Test
  void aLandingReservesTransportsAndGathersAtTheZone() {
    final var props = data.getProperties();
    Stratega.registra(data, player("Americans"));
    Stratega.registra(data, player("British"));
    final StrategaCandidati gen = generator("Americans", 5);
    final Candidato c =
        new Candidato(
            "C1",
            Tipo.SBARCO,
            "Normandy Bordeaux",
            "Americans",
            "British",
            7,
            "United Kingdom",
            "110 Sea Zone",
            List.of("France"),
            10,
            1,
            new JsonObject());
    install(gen, c, "stratega-sbarco-R5-Normandy_Bordeaux", 7);
    final var d = OperazioneCongiunta.dinamiche(props, "Allies").get(0);
    assertThat(d.zonaRaccolta()).isEqualTo("110 Sea Zone");
    assertThat(d.raccoltaChiude()).containsExactly("United Kingdom");
    assertThat(d.bersaglioFinale()).isEqualTo("France");
    assertThat(d.riserve().get("British").acquisti())
        .anyMatch(a -> a.categoria().equals("trasporti"));
    OperazioniCongiunte.aggiorna(proData(player("Americans"), 5, "Purchase"), calc());
    final Optional<ProPurchaseOrders> o =
        OperazioniCongiunte.ordiniAcquisto(proData(player("British"), 5, "Purchase"));
    assertThat(o).isPresent();
    assertThat(o.get().stagingTerritories()).contains("United Kingdom");
  }

  // --------------------------------------------------------------------- existing players

  @Test
  void noOperationNorCallForTheOtherPlayers() {
    fake(
        r -> {
          throw new AssertionError("no LLM call for a player that is not the strategist");
        });
    // nothing registered: Pro+ and Hard games see no dynamic operation and no strategist round
    assertThat(OperazioneCongiunta.tutte(data, player("Germans"))).isEmpty();
    assertThat(
            Stratega.inizioTurno(
                    proData(player("Germans"), 1, "Purchase"), calc(), data, player("Germans"))
                .righe())
        .isEmpty();
    assertThat(data.getProperties().get(OperazioneCongiunta.DYNAMIC_ANY)).isNull();
    assertThat(Stratega.rinforzi(data, player("Germans"))).isEmpty();
    // the strategist of one alliance never acts for the other
    registerAxis();
    assertThat(
            Stratega.inizioTurno(
                    proData(player("British"), 1, "Purchase"), calc(), data, player("British"))
                .righe())
        .isEmpty();
  }

  // ================================================================================ helpers

  private void registerAxis() {
    Stratega.registra(data, player("Germans"));
    Stratega.registra(data, player("Italians"));
  }

  private Stratega.Esito start(final String nation, final int round) {
    return Stratega.inizioTurno(
        proData(player(nation), round, "Purchase"), calc(), data, player(nation));
  }

  private StrategaCandidati generator(final String nation, final int round) {
    final ProData proData = proData(player(nation), round, "Purchase");
    final String alliance = ProPlusSettings.allianceOf(data, player(nation)).orElseThrow();
    return new StrategaCandidati(
        proData, calc(), alliance, List.copyOf(Stratega.nazioni(data.getProperties(), alliance)));
  }

  private void install(
      final StrategaCandidati gen, final Candidato c, final String id, final int round) {
    final JsonArray a = new JsonArray();
    a.add(
        gen.definizione(c, id, round, 0, 0, c.successivi().isEmpty() ? "" : c.successivi().get(0)));
    final String alliance = ProPlusSettings.allianceOf(data, player(c.apre())).orElseThrow();
    OperazioneCongiunta.setDinamiche(data.getProperties(), alliance, a);
  }

  private void fake(final Function<Richiesta, Risposta> f) {
    DecisoreOperazione.setFabbrica(
        model ->
            new DecisoreOperazione.Trasporto() {
              @Override
              public Optional<String> nonDisponibile() {
                return Optional.empty();
              }

              @Override
              public Risposta chiama(final Richiesta r) {
                asked.add(r);
                return f.apply(r);
              }
            });
  }

  private static Risposta answer(final String text) {
    return new Risposta(text, "Sonnet", "claude-sonnet-5-5", 1000, 100, 0, 0, 0.02, false, 5);
  }

  private static JsonObject state(final Richiesta r) {
    final String t = r.utente();
    return JsonParser.parseString(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
        .getAsJsonObject();
  }

  private static String voce(final String action, final String cand, final String op) {
    return "{\"azione\":\""
        + action
        + "\",\"candidato\":\""
        + cand
        + "\",\"operazione\":\""
        + op
        + "\",\"round_obiettivo\":0,\"tetto_perdita_TUV\":0,\"durata_tenuta\":0,"
        + "\"bersaglio_successivo\":\"\",\"prob_tenuta_stimata\":0.6,\"motivazione\":\"prova\"}";
  }

  private static String avvia(final String cand) {
    return voce("avvia", cand, "");
  }

  private static String annulla(final String op) {
    return voce("annulla", "", op);
  }

  private static String launch(final String cand) {
    return portfolio(List.of(avvia(cand)), none());
  }

  private static String none() {
    return "{\"proponi\":false,\"tipo\":\"nessuno\",\"bersaglio\":\"\",\"apre\":\"\",\"chiude\":\"\","
        + "\"prob_tenuta_stimata\":0,\"motivazione\":\"\"}";
  }

  private static String outside(
      final String tipo, final String target, final String a, final String c) {
    return "{\"proponi\":true,\"tipo\":\""
        + tipo
        + "\",\"bersaglio\":\""
        + target
        + "\",\"apre\":\""
        + a
        + "\",\"chiude\":\""
        + c
        + "\",\"prob_tenuta_stimata\":0.5,\"motivazione\":\"prova\"}";
  }

  private static String portfolio(final List<String> voci, final String outside) {
    return "{\"portafoglio\":["
        + String.join(",", voci)
        + "],\"fuori_lista\":"
        + outside
        + ",\"rinforzi\":[],\"motivazione\":\"prova\"}";
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private ProData proData(final GamePlayer player, final int round, final String step) {
    data.getSequence().setRoundAndStep(round, step, player);
    final ProPlusTuning plus = new ProPlusTuning();
    final ProData proData = new ProData(false, plus);
    plus.attach(proData);
    proData.initializeSimulation(
        new TestAi(player.getName(), proData, new SeededBattleCalculator(SEED)), data, player);
    return proData;
  }

  private ProOddsCalculator calc() {
    final SeededBattleCalculator calculator = new SeededBattleCalculator(SEED);
    calculator.setGameData(data).join();
    return new ProOddsCalculator(calculator);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrNull(name);
  }

  private void ownedBy(final String territory, final String nation) {
    data.performChange(ChangeFactory.changeOwner(territory(territory), player(nation)));
  }

  private void clear(final String territory) {
    final Territory t = territory(territory);
    final List<Unit> units = t.getMatches(Matches.unitIsNotInfrastructure());
    if (!units.isEmpty()) {
      data.performChange(ChangeFactory.removeUnits(t, units));
    }
  }

  private void add(
      final String territory, final GamePlayer owner, final String type, final int count) {
    data.performChange(
        ChangeFactory.addUnits(
            territory(territory),
            data.getUnitTypeList().getUnitTypeOrThrow(type).create(count, owner)));
  }

  private static IMoveDelegate moves() {
    final IMoveDelegate moveDel = mock(IMoveDelegate.class);
    when(moveDel.performMove(any(MoveDescription.class))).thenReturn(Optional.empty());
    return moveDel;
  }

  /** An AI with seeded battle dice. */
  private static final class TestAi extends AbstractProAi {
    private final SeededBattleCalculator calculator;

    TestAi(final String name, final ProData proData, final SeededBattleCalculator calculator) {
      super(name, calculator, proData, ProPlusAi.PLAYER_LABEL);
      this.calculator = calculator;
    }

    @Override
    protected void prepareData(final GameData data) {
      calculator.setGameData(data).join();
    }
  }

  @SuppressWarnings("unused")
  private static boolean exists(final Path p) {
    return Files.exists(p);
  }
}
