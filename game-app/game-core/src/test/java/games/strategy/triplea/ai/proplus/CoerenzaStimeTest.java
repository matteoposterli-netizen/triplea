package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Stato;
import games.strategy.triplea.ai.proplus.StrategaCandidati.Candidato;
import games.strategy.triplea.ai.proplus.StrategaCandidati.Tipo;
import games.strategy.triplea.ai.proplus.diario.DiarySettings;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.ai.proplus.strategia.llm.Richiesta;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step COERENZA STIME: one estimate for candidates, decision points and execution; targets taken by
 * Hard go into TENUTA (or close) without the LLM; lost targets are FALLITA before the strategic
 * call; infeasible candidates are discarded; the loss cap is proportional; no relaunch without
 * better numbers; the strategist's diary survives an error. Every LLM call is a fake.
 */
class CoerenzaStimeTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261110L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final List<Richiesta> asked = new ArrayList<>();
  private @TempDir Path profile;
  private @TempDir Path diary;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
    System.setProperty(DiarySettings.FOLDER_PROPERTY, diary.toString());
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    Stratega.registra(data, player("Germans"));
    Stratega.registra(data, player("Italians"));
  }

  @AfterEach
  void tearDown() {
    DecisoreOperazione.setFabbrica(null);
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
    System.clearProperty(DiarySettings.FOLDER_PROPERTY);
  }

  // --------------------------------------------------------------------------- one estimate

  @Test
  void theCandidateAndTheDecisionPointGiveTheSameEstimate() {
    // Alexandria British with 2 infantry; German fighters and Italian armour in Tobruk next to it
    clear("Alexandria");
    add("Alexandria", player("British"), "infantry", 2);
    add("Tobruk", player("Germans"), "fighter", 2);
    add("Tobruk", player("Italians"), "armour", 2);
    final String id = "stratega-offensiva-R3-Alexandria";
    final OperazioneCongiunta.Definizione def = install(Tipo.OFFENSIVA, "Alexandria", id, 3, 120);
    final Registro reg = Registro.leggi(data.getProperties(), id);
    reg.setStato(Stato.ESECUZIONE);
    reg.setRoundObiettivo(3);
    reg.set("avvio_round", 3);
    reg.salva(data.getProperties());
    // the candidate's numbers: the single estimate at the start of the round (fresh dice)
    final StimaUnica.Stima candidate =
        StimaUnica.conquista(
            proData(player("Germans"), 3, "Purchase"), calc(), def, territory("Alexandria"), 1);
    assertThat(candidate.tappa1()).anyMatch(Matches.unitIsAir());
    assertThat(candidate.tappa2()).anyMatch(Matches.unitIsLand());
    // the decision point of the opener in the same state (fresh dice): the LLM reads the same
    fake(r -> answer("{\"azione\":\"rinvia\",\"prob_tenuta_stimata\":0.5,\"motivazione\":\"x\"}"));
    OperazioniCongiunte.combattimento(
        proData(player("Germans"), 3, "Combat Move"), calc(), moves());
    assertThat(asked).hasSize(1);
    final JsonObject atDecision =
        state(asked.get(0)).getAsJsonObject("numeri").getAsJsonObject("stima_motore");
    final JsonObject atLaunch = candidate.json();
    for (final String k : List.of("successo_pct", "presa_pct", "tenuta_pct", "win_tappa1_pct")) {
      assertThat(atDecision.get(k).getAsDouble())
          .as(k)
          .isCloseTo(atLaunch.get(k).getAsDouble(), within(1.0));
    }
    assertThat(atDecision.get("attaccanti_tappa1").getAsString())
        .isEqualTo(atLaunch.get("attaccanti_tappa1").getAsString());
    // the first estimate at a decision point is kept for the coherence measure
    assertThat(Registro.leggi(data.getProperties(), id).json().has("stima_decisione_prima"))
        .isTrue();
  }

  // ------------------------------------------------------------------- target taken by Hard

  @Test
  void aTargetTakenByHardGoesIntoHoldWithoutTheLlmWhenTheHoldJustifiesIt() {
    noCall();
    final String id = "stratega-offensiva-R2-Alexandria";
    install(Tipo.OFFENSIVA, "Alexandria", id, 3, 0);
    preparing(id, 3);
    // Hard took it, with a strong garrison and no British army near
    ownedBy("Alexandria", "Italians");
    clear("Alexandria");
    add("Alexandria", player("Italians"), "infantry", 6);
    for (final String t : List.of("Egypt", "Trans-Jordan", "Anglo Egyptian Sudan")) {
      clear(t);
    }
    OperazioniCongiunte.primaDellaDecisione(
        proData(player("Germans"), 3, "Purchase"), calc(), "Axis");
    final Registro reg = Registro.leggi(data.getProperties(), id);
    assertThat(reg.stato()).isEqualTo(Stato.TENUTA);
    assertThat(reg.flag("presa_senza_operazione")).isTrue();
    assertThat(reg.flag("tappa_motore")).as("Hard's take is not the engine's").isFalse();
  }

  @Test
  void aTargetTakenByHardIsClosedWithoutTheLlmWhenItCannotBeHeld() {
    noCall();
    final String id = "stratega-offensiva-R2-Alexandria";
    install(Tipo.OFFENSIVA, "Alexandria", id, 3, 0);
    preparing(id, 3);
    ownedBy("Alexandria", "Italians");
    clear("Alexandria");
    clearAxisNear("Alexandria", 6);
    add("Egypt", player("British"), "armour", 6);
    OperazioniCongiunte.primaDellaDecisione(
        proData(player("Germans"), 3, "Purchase"), calc(), "Axis");
    final Registro reg = Registro.leggi(data.getProperties(), id);
    assertThat(reg.stato()).isEqualTo(Stato.ANNULLATA);
    assertThat(reg.flag("chiusa_dal_motore")).isTrue();
    assertThat(reg.json().get("causa_annullamento").getAsString()).isEqualTo("C1");
  }

  // ------------------------------------------------------------ FALLITA before the decision

  @Test
  void aLostTargetIsFailedBeforeTheStrategicCall() {
    final String id = "stratega-tenuta-R2-Alexandria";
    install(Tipo.TENUTA, "Alexandria", id, 2, 0);
    final Registro reg = Registro.leggi(data.getProperties(), id);
    reg.setStato(Stato.TENUTA);
    reg.setRoundObiettivo(2);
    reg.set("tenuta_bersaglio", "Alexandria");
    reg.set("tenuta_dal", 2);
    reg.salva(data.getProperties());
    final JsonObject pf = new JsonObject();
    final JsonArray ops = new JsonArray();
    final JsonObject op = new JsonObject();
    op.addProperty("id", id);
    op.addProperty("tipo", "tenuta");
    op.addProperty("bersaglio", "Alexandria");
    op.addProperty("apre", "Italians");
    op.addProperty("chiude", "Germans");
    op.addProperty("durata", 2);
    op.add("previsioni", new JsonArray());
    ops.add(op);
    pf.add("operazioni", ops);
    data.getProperties().set(Stratega.KEY_PORTAFOGLIO + "Axis", pf.toString());
    // the British retook it before the round
    ownedBy("Alexandria", "British");
    fake(r -> answer(emptyPortfolio()));
    Stratega.inizioTurno(
        proData(player("Germans"), 3, "Purchase"), calc(), data, player("Germans"));
    final Registro after = Registro.leggi(data.getProperties(), id);
    assertThat(after.stato()).isEqualTo(Stato.FALLITA);
    assertThat(after.flag("fallita_prima_della_decisione")).isTrue();
    assertThat(asked).hasSize(1);
    final JsonObject sent = state(asked.get(0));
    assertThat(sent.getAsJsonArray("operazioni_in_corso")).isEmpty();
    assertThat(sent.getAsJsonArray("operazioni_finite").toString()).contains("fallita");
  }

  // --------------------------------------------------------------------- feasibility

  @Test
  void anInfeasibleCandidateIsDiscarded() {
    // a landing gathered in a zone the British fleet holds: not safe against the realistic attack
    add("110 Sea Zone", player("British"), "battleship", 3);
    add("110 Sea Zone", player("British"), "destroyer", 4);
    final StrategaCandidati gen = generator("Germans", 3);
    final Candidato c =
        new Candidato(
            "C1",
            Tipo.SBARCO,
            "Normandy Bordeaux",
            "Germans",
            "Italians",
            4,
            "Holland Belgium",
            "110 Sea Zone",
            List.of(),
            10,
            1,
            new JsonObject());
    final OperazioneCongiunta.Definizione def =
        OperazioneCongiunta.parse(
                StrategaCandidati.wrap(gen.definizione(c, "stratega-sbarco-R3-x", 4, 0, 0, "")))
            .get(0);
    final OperazioniCongiunte.Lancio l =
        OperazioniCongiunte.lancio(proData(player("Germans"), 3, "Purchase"), calc(), def, 5);
    assertThat(l.motivo()).hasValueSatisfying(m -> assertThat(m).contains("non sicura"));
    // the candidates of the round never contain a discarded target, and say why it was discarded
    final List<Candidato> list = gen.calcola();
    for (final JsonElement e : gen.scartati()) {
      final JsonObject x = e.getAsJsonObject();
      assertThat(x.get("motivo").getAsString()).isNotBlank();
      assertThat(list)
          .noneMatch(
              k ->
                  k.bersaglio().equals(x.get("bersaglio").getAsString())
                      && k.tipo().id().equals(x.get("tipo").getAsString()));
    }
  }

  // ------------------------------------------------------------------------------ loss cap

  @Test
  void theLossCapIsProportionalToTheValueAndTheLlmMayAskAnotherWithinTheMaximum() {
    final StrategaConfig cfg = StrategaConfig.get();
    assertThat(cfg.tettoPerdita(12, 0)).isEqualTo(36);
    assertThat(cfg.tettoPerdita(2, 0)).isEqualTo(cfg.tettoPerditaMinimo());
    assertThat(cfg.tettoPerdita(100, 0)).isEqualTo(cfg.tettoPerditaMax());
    assertThat(cfg.tettoPerdita(12, 60)).isEqualTo(60);
    assertThat(cfg.tettoPerdita(12, 500)).isEqualTo(cfg.tettoPerditaMaxLlm());
    final OperazioneCongiunta.Definizione def =
        install(Tipo.OFFENSIVA, "Alexandria", "stratega-offensiva-R2-Alexandria", 3, 0);
    // step C1-bis: the cap scales with the old value index, not with the value in PU equivalents
    final double index = generator("Germans", 3).valore(territory("Alexandria"));
    assertThat(def.tettoPerdita())
        .isEqualTo((double) Math.round(cfg.tettoPerdita(Math.max(1, index), 0)));
  }

  // ------------------------------------------------------------ step C1-bis: held planes

  @Test
  void aPlaneCountedForALaterStepIsHeldFromThePurchaseAndOneThatMustFlyFirstIsNotCounted() {
    clear("Alexandria");
    add("Alexandria", player("British"), "infantry", 2);
    clear("Tobruk");
    add("Tobruk", player("Italians"), "armour", 2);
    add("Tobruk", player("Italians"), "fighter", 1);
    add("Germany", player("Italians"), "fighter", 1);
    final Unit near = territory("Tobruk").getMatches(Matches.unitIsAir()).get(0);
    final Unit far =
        territory("Germany")
            .getMatches(Matches.unitIsAir().and(Matches.unitIsOwnedBy(player("Italians"))))
            .get(0);
    final String id = "stratega-offensiva-R3-Alexandria";
    final OperazioneCongiunta.Definizione def = install(Tipo.OFFENSIVA, "Alexandria", id, 4, 0);
    final Registro reg = Registro.leggi(data.getProperties(), id);
    reg.setStato(Stato.PREPARAZIONE);
    reg.setRoundObiettivo(4);
    reg.set("avvio_round", 3);
    reg.salva(data.getProperties());
    final ProData italians = proData(player("Italians"), 3, "Purchase");
    final StimaUnica.Stima st =
        StimaUnica.conquista(italians, calc(), def, territory("Alexandria"), 2);
    // the plane that strikes from where it is counts; the one that must fly closer first does not
    assertThat(st.tappa2()).contains(near).doesNotContain(far);
    // and the counted plane stays out of Hard's attacks from the purchase on
    assertThat(OperazioniCongiunte.trattenuteAllAcquisto(italians, calc()))
        .contains(near)
        .doesNotContain(far);
    final JsonArray held =
        Registro.leggi(data.getProperties(), id).json().getAsJsonArray("trattenute_acquisto");
    assertThat(held.get(held.size() - 1).getAsJsonObject().get("aerei").getAsInt())
        .isGreaterThanOrEqualTo(1);
  }

  // ------------------------------------------------------ step C1-bis: value in PU equivalents

  @Test
  void theValueOfTheAllianceIsInPuEquivalentsWithItsComponents() {
    final StrategaConfig cfg = StrategaConfig.get();
    final StrategaCandidati gen = generator("Germans", 3);
    final Territory london = territory("United Kingdom");
    final JsonObject capital = gen.valoreBersaglioPu(london, "Germans", 2);
    final int production =
        games.strategy.triplea.attachments.TerritoryAttachment.get(london)
            .orElseThrow()
            .getProduction();
    assertThat(capital.get("reddito_PU").getAsDouble())
        .isEqualTo(Math.round(cfg.fattoreReddito() * production * 2));
    assertThat(capital.get("capitale_PU").getAsDouble())
        .as("cash of the owner + its income x 2 rounds")
        .isGreaterThan(0);
    assertThat(capital.get("totale_PU").getAsDouble())
        .isCloseTo(
            capital.get("reddito_PU").getAsDouble()
                + capital.get("obiettivi_nazionali_PU").getAsDouble()
                + capital.get("victory_city_PU").getAsDouble()
                + capital.get("capitale_PU").getAsDouble(),
            within(2.0));
    clear("Alexandria");
    add("Alexandria", player("British"), "infantry", 2);
    add("Tobruk", player("Germans"), "fighter", 2);
    add("Tobruk", player("Italians"), "armour", 2);
    final OperazioneCongiunta.Definizione def =
        install(Tipo.OFFENSIVA, "Alexandria", "stratega-offensiva-R3-Alexandria", 3, 0);
    assertThat(def.valore())
        .isEqualTo(
            Math.max(
                1,
                gen.valoreBersaglioPu(territory("Alexandria"), "Germans", 2)
                    .get("totale_PU")
                    .getAsDouble()));
    final StimaUnica.Stima st =
        StimaUnica.conquista(
            proData(player("Germans"), 3, "Purchase"), calc(), def, territory("Alexandria"), 1);
    assertThat(st.valoreAlleanza())
        .isCloseTo(
            def.valore() * st.presa() * st.tenuta() / 100 + st.tuvDistrutto() - st.tuvPersoAtteso(),
            within(1e-9));
    assertThat(st.tuvDistrutto()).as("the British infantry is expected destroyed").isPositive();
    final JsonObject v = st.json().getAsJsonObject("valore_alleanza");
    for (final String k :
        List.of("valore_bersaglio_PU", "tuv_nemico_distrutto", "tuv_perso", "totale_PU")) {
      assertThat(v.has(k)).as(k).isTrue();
    }
  }

  // ---------------------------------------------------------- step C1-ter: reach = movement

  @Test
  void aLandUnitWhoseOnlyStepIsUnsafeIsNotCountedForALaterStep() {
    // nobody near (no Axis transport: the infantry can only walk; no British army: Tobruk is safe)
    for (final Territory t : data.getMap().getNeighbors(territory("Alexandria"), 4)) {
      clear(t.getName());
    }
    clear("Alexandria");
    add("Alexandria", player("British"), "infantry", 1);
    clear("Tobruk");
    clear("Libya");
    add("Libya", player("Italians"), "infantry", 1);
    final Unit walker = territory("Libya").getMatches(Matches.unitIsLand()).get(0);
    final String id = "stratega-offensiva-R3-Alexandria";
    final OperazioneCongiunta.Definizione def = install(Tipo.OFFENSIVA, "Alexandria", id, 4, 0);
    preparing(id, 4);
    // nothing threatens Tobruk: the step Libya -> Tobruk is safe, the infantry counts
    final StimaUnica.Stima safe =
        StimaUnica.conquista(
            proData(player("Italians"), 3, "Purchase"), calc(), def, territory("Alexandria"), 2);
    assertThat(safe.tappa2()).contains(walker);
    assertThat(safe.esclusePercorso()).doesNotContain(walker);
    // a British army next to Tobruk: the same safety check that would refuse the move refuses it
    // in the estimate too
    add("Alexandria", player("British"), "armour", 12);
    add("Alexandria", player("British"), "artillery", 6);
    final StimaUnica.Stima unsafe =
        StimaUnica.conquista(
            proData(player("Italians"), 3, "Purchase"), calc(), def, territory("Alexandria"), 2);
    assertThat(unsafe.tappa2()).doesNotContain(walker);
    assertThat(unsafe.esclusePercorso()).contains(walker);
    assertThat(unsafe.json().get("escluse_percorso_non_sicuro_n").getAsInt()).isPositive();
  }

  // ------------------------------------------------------- step C1-ter: empty target, no win%

  @Test
  void anEmptyTargetIsTakenByALandUnitAndNeverByPlanesAlone() {
    clearAxisNear("Alexandria", 4);
    clear("Alexandria");
    add("Tobruk", player("Germans"), "fighter", 2);
    final OperazioneCongiunta.Definizione def =
        install(Tipo.OFFENSIVA, "Alexandria", "stratega-offensiva-R3-Alexandria", 3, 0);
    final StimaUnica.Stima planes =
        StimaUnica.conquista(
            proData(player("Germans"), 3, "Purchase"), calc(), def, territory("Alexandria"), 1);
    // step R1 (A2): this turn the estimate counts the executable set, and planes cannot move into
    // an empty enemy territory: no land unit, take 0, "planes alone"
    assertThat(Stream.concat(planes.tappa1().stream(), planes.tappa2().stream()))
        .noneMatch(Matches.unitIsLand());
    assertThat(planes.presa()).isZero();
    assertThat(planes.soloAerei()).isTrue();
    assertThat(planes.json().has("win_tappa1_pct")).as("no win% of step 1").isFalse();
    assertThat(planes.json().get("nota_presa").getAsString()).contains("serve un'unita' di terra");
    add("Tobruk", player("Italians"), "armour", 1);
    final StimaUnica.Stima land =
        StimaUnica.conquista(
            proData(player("Germans"), 3, "Purchase"), calc(), def, territory("Alexandria"), 1);
    assertThat(land.presa()).isEqualTo(1.0);
    assertThat(land.soloAerei()).isFalse();
    assertThat(land.json().has("win_tappa1_pct")).isFalse();
  }

  // -------------------------------------------- step C1-ter: TUV destroyed = defenders there now

  @Test
  void theEnemyTuvDestroyedCountsOnlyTheDefendersThereAtTheEstimate() {
    clear("Alexandria");
    add("Alexandria", player("British"), "infantry", 2);
    add("Egypt", player("British"), "armour", 3);
    add("Tobruk", player("Germans"), "fighter", 3);
    add("Tobruk", player("Italians"), "armour", 4);
    final OperazioneCongiunta.Definizione def =
        install(Tipo.OFFENSIVA, "Alexandria", "stratega-offensiva-R3-Alexandria", 3, 0);
    final ProData germans = proData(player("Germans"), 3, "Purchase");
    final StimaUnica.Stima st =
        StimaUnica.conquista(germans, calc(), def, territory("Alexandria"), 1);
    final double defenders =
        games.strategy.triplea.util.TuvUtils.getTuv(
            territory("Alexandria").getMatches(Matches.unitIsNotInfrastructure()),
            germans.getUnitValueMap());
    assertThat(st.tuvRinforziNemici()).as("enemy reinforcements expected").isPositive();
    assertThat(st.tuvDistrutto()).isPositive().isLessThanOrEqualTo(defenders + 1e-9);
    assertThat(st.valoreAlleanza())
        .isCloseTo(
            def.valore() * st.presa() * st.tenuta() / 100 + st.tuvDistrutto() - st.tuvPersoAtteso(),
            within(1e-9));
  }

  // ------------------------------------------------------------ step C1-bis: order of the causes

  @Test
  void aFallOfTheEngineEstimateIsC3BeforeTheCandidateLeftTheList() {
    final String id = "stratega-offensiva-R3-Alexandria";
    final OperazioneCongiunta.Definizione def = install(Tipo.OFFENSIVA, "Alexandria", id, 4, 0);
    final Registro reg = Registro.leggi(data.getProperties(), id);
    reg.setStato(Stato.PREPARAZIONE);
    reg.setRoundObiettivo(4);
    reg.set("avvio_round", 3);
    reg.json()
        .add(
            "stima_avvio",
            JsonParser.parseString("{\"successo_pct\":60,\"contrattacco_massimo_TUV\":20}"));
    data.getSequence().setRoundAndStep(4, "Purchase", player("Germans"));
    final JsonObject fell =
        JsonParser.parseString("{\"successo_pct\":10,\"contrattacco_massimo_TUV\":20}")
            .getAsJsonObject();
    assertThat(CauseAnnullamento.classifica(data, def, reg, List.of(), fell)).startsWith("C3");
    final JsonObject same =
        JsonParser.parseString("{\"successo_pct\":58,\"contrattacco_massimo_TUV\":20}")
            .getAsJsonObject();
    assertThat(CauseAnnullamento.classifica(data, def, reg, List.of(), same)).startsWith("C5");
  }

  // ------------------------------------------------------------------------- no relaunch

  @Test
  void noRelaunchOfTheSameTargetWithin3RoundsWithoutBetterNumbers() {
    final StrategaConfig cfg = StrategaConfig.get();
    final JsonObject pf =
        JsonParser.parseString(
                "{\"operazioni\":[{\"id\":\"stratega-offensiva-R3-Egypt\",\"bersaglio\":\"Egypt\","
                    + "\"esito\":\"annullata\",\"fine_round\":4,"
                    + "\"stima_motore_avvio\":{\"successo_pct\":30}}]}")
            .getAsJsonObject();
    assertThat(Stratega.riavvioVietato(pf, "Egypt", 5, 32, cfg)).isPresent();
    assertThat(Stratega.riavvioVietato(pf, "Egypt", 5, 30 + cfg.riavvioMiglioramentoPunti(), cfg))
        .isEmpty();
    assertThat(Stratega.riavvioVietato(pf, "Egypt", 4 + cfg.riavvioRound() + 1, 10, cfg)).isEmpty();
    assertThat(Stratega.riavvioVietato(pf, "Libya", 5, 10, cfg)).isEmpty();
  }

  // ------------------------------------------------------------------------- robust diary

  @Test
  void theRecordOfTheRoundIsWrittenEvenIfApplyingTheAnswerFails() throws IOException {
    final String good = "stratega-offensiva-R2-Alexandria";
    install(Tipo.OFFENSIVA, "Alexandria", good, 3, 0);
    preparing(good, 3);
    // a second operation whose saved definition is broken (no hold section)
    final JsonArray defs = OperazioneCongiunta.dinamicheJson(data.getProperties(), "Axis");
    final JsonObject broken = defs.get(0).getAsJsonObject().deepCopy();
    broken.addProperty("id", "stratega-offensiva-R2-Egypt");
    broken.addProperty("bersaglio", "Egypt");
    broken.remove("tenuta");
    defs.add(broken);
    OperazioneCongiunta.setDinamiche(data.getProperties(), "Axis", defs);
    preparing("stratega-offensiva-R2-Egypt", 3);
    fake(
        r ->
            answer(
                "{\"portafoglio\":["
                    + voce("annulla", good, 0)
                    + ","
                    + voce("modifica", "stratega-offensiva-R2-Egypt", 2)
                    + "],\"fuori_lista\":{\"proponi\":false,\"tipo\":\"nessuno\",\"bersaglio\":\"\","
                    + "\"apre\":\"\",\"chiude\":\"\",\"prob_tenuta_stimata\":0,\"motivazione\":\"\"},"
                    + "\"rinforzi\":[],\"motivazione\":\"prova\"}"));
    final Stratega.Esito e =
        Stratega.inizioTurno(
            proData(player("Germans"), 3, "Purchase"), calc(), data, player("Germans"));
    assertThat(e.righe()).anyMatch(x -> x.contains("ERRORE"));
    // the cancellation applied before the error stays, and the round is recorded
    assertThat(Registro.leggi(data.getProperties(), good).stato()).isEqualTo(Stato.ANNULLATA);
    assertThat(Registro.leggi(data.getProperties(), good).json().has("causa_annullamento"))
        .isTrue();
    assertThat(Stratega.portafoglio(data.getProperties(), "Axis").get("round_decisi").getAsInt())
        .isEqualTo(1);
    try (Stream<Path> files = Files.walk(diary)) {
      final Optional<Path> jsonl =
          files.filter(p -> p.getFileName().toString().equals("stratega.jsonl")).findFirst();
      assertThat(jsonl).isPresent();
      assertThat(Files.readString(jsonl.get()))
          .contains("errore_applicazione")
          .contains("\"round\":3");
    }
  }

  // ================================================================================ helpers

  private OperazioneCongiunta.Definizione install(
      final Tipo tipo, final String target, final String id, final int round, final int cap) {
    final boolean conquest = tipo.conquista();
    final StrategaCandidati gen = generator("Germans", round);
    final Candidato c =
        new Candidato(
            "C1",
            tipo,
            target,
            conquest ? "Germans" : "Italians",
            conquest ? "Italians" : "Germans",
            round,
            "",
            "",
            List.of(),
            gen.valore(territory(target)),
            1,
            new JsonObject());
    final JsonArray a = OperazioneCongiunta.dinamicheJson(data.getProperties(), "Axis");
    final JsonObject def = gen.definizione(c, id, round, cap, 0, "");
    a.add(def);
    OperazioneCongiunta.setDinamiche(data.getProperties(), "Axis", a);
    return OperazioneCongiunta.parse(StrategaCandidati.wrap(def)).get(0);
  }

  private void preparing(final String id, final int target) {
    final Registro reg = Registro.leggi(data.getProperties(), id);
    reg.setStato(Stato.PREPARAZIONE);
    reg.setRoundObiettivo(target);
    reg.set("avvio_round", 2);
    reg.salva(data.getProperties());
  }

  private static String voce(final String action, final String op, final int hold) {
    return "{\"azione\":\""
        + action
        + "\",\"candidato\":\"\",\"operazione\":\""
        + op
        + "\",\"round_obiettivo\":0,\"tetto_perdita_TUV\":0,\"durata_tenuta\":"
        + hold
        + ",\"bersaglio_successivo\":\"\",\"prob_tenuta_stimata\":0.2,\"motivazione\":\"prova\"}";
  }

  private static String emptyPortfolio() {
    return "{\"portafoglio\":[],\"fuori_lista\":{\"proponi\":false,\"tipo\":\"nessuno\","
        + "\"bersaglio\":\"\",\"apre\":\"\",\"chiude\":\"\",\"prob_tenuta_stimata\":0,"
        + "\"motivazione\":\"\"},\"rinforzi\":[],\"motivazione\":\"prova\"}";
  }

  private StrategaCandidati generator(final String nation, final int round) {
    final ProData proData = proData(player(nation), round, "Purchase");
    final String alliance = ProPlusSettings.allianceOf(data, player(nation)).orElseThrow();
    return new StrategaCandidati(
        proData, calc(), alliance, List.copyOf(Stratega.nazioni(data.getProperties(), alliance)));
  }

  private void noCall() {
    fake(
        r -> {
          throw new AssertionError("no LLM call expected");
        });
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

  private void clearAxisNear(final String territory, final int distance) {
    final GamePlayer germans = player("Germans");
    for (final Territory t : data.getMap().getNeighbors(territory(territory), distance)) {
      final List<Unit> units =
          t.getMatches(Matches.isUnitAllied(germans).and(Matches.unitIsNotInfrastructure()));
      if (!units.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(t, units));
      }
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
}
