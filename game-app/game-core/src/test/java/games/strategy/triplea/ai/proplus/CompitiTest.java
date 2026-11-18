package games.strategy.triplea.ai.proplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.odds.calculator.SeededBattleCalculator;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step P1 "COMPITO": the generic mechanism. Pure rules ({@link Risposta}): response chosen from the
 * obstacles, no new transports while the owned ones (also empty and idle) can carry the ready
 * troops, the direction held 3 rounds; the data file; no nation in the engine's code; on built
 * positions (Global 1940): the obstacles computed with their numbers, an enemy fleet beaten at >=
 * 70% attacked, no transport bought with enough empty ones, the switch off = nothing.
 */
class CompitiTest extends AbstractClientSettingTestCase {
  private static final long SEED = 20261117L;

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private @TempDir Path profile;

  @BeforeEach
  void setUp() {
    System.setProperty(ProfiloStratega.FOLDER_PROPERTY, profile.toString());
    ProPlusSettings.setRiskLevel(data.getProperties(), "Axis", RiskLevel.EQUILIBRATO);
    ProPlusSettings.setRiskLevel(data.getProperties(), "Allies", RiskLevel.EQUILIBRATO);
    for (final String n : List.of("Americans", "British", "Russians", "Germans", "Italians")) {
      Stratega.registra(data, player(n));
    }
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(ProfiloStratega.FOLDER_PROPERTY);
  }

  // =========================================================================== data, code

  @Test
  void theDataFileHasTheTasksOfTheUsaAndTheUkAndTheAllianceLevelAsData() {
    final Compito.Dati d = Compito.dati();
    assertThat(d.accesoDiDefault()).isFalse();
    assertThat(StrategaInterruttori.COMPITI.predefinito()).isFalse();
    assertThat(d.nazioniAttive().get("Allies")).containsExactly("Americans", "British");
    final Compito usa = d.di("Allies", "Americans").orElseThrow();
    assertThat(usa.risultati())
        .extracting(Compito.Risultato::id)
        .containsExactly("R1", "R2", "R3", "R4");
    assertThat(usa.risultati().get(0).tipo()).isEqualTo(Compito.CONTROLLO_E_FLOTTA);
    assertThat(usa.risultati().get(0).traguardo()).isEqualTo(6);
    assertThat(usa.risultati().get(1).tipo()).isEqualTo(Compito.DIREZIONE_ALLEANZA);
    assertThat(usa.risultati().get(2).tipo()).isEqualTo(Compito.TESTA_DI_PONTE);
    assertThat(usa.risultati().get(3).tipo()).isEqualTo(Compito.PRESA);
    final Compito uk = d.di("Allies", "British").orElseThrow();
    assertThat(uk.risultati()).extracting(Compito.Risultato::id).containsExactly("U0", "U1", "U2");
    assertThat(uk.risultati().get(0).vincolo()).isTrue();
    assertThat(uk.risultati().get(0).continuo()).isTrue();
    assertThat(uk.risultati().get(1).continuo()).isTrue();
    assertThat(uk.risultati().get(1).territorio()).isEqualTo("Egypt");
    assertThat(uk.risultati().get(2).tipo()).isEqualTo(Compito.TESTA_DI_PONTE);
    assertThat(uk.risultati().get(2).valePer("FRANCIA")).isTrue();
    assertThat(uk.risultati().get(2).valePer("MEDITERRANEO")).isFalse();
    assertThat(uk.quotaPrima()).isEqualTo(80);
    assertThat(uk.quotaDopo()).isEqualTo(80);
    final Compito.Livello lv = d.livello("Allies").orElseThrow();
    assertThat(lv.tenutaRound()).isEqualTo(3);
    assertThat(lv.direzioni().keySet()).containsExactly("MEDITERRANEO", "FRANCIA");
    assertThat(lv.direzioni().get("MEDITERRANEO").capitali()).containsExactly("Southern Italy");
    assertThat(lv.direzioni().get("FRANCIA").capitali()).containsExactly("France", "Germany");
    assertThat(lv.direzioni().get("FRANCIA").contributori()).containsExactly("Americans", "British");
    assertThat(lv.direzioni().get("MEDITERRANEO").contributori()).containsExactly("Americans");
    assertThat(d.parametri().sogliaAttacco()).isEqualTo(70);
    assertThat(d.parametri().sogliaTrasporti()).isEqualTo(40);
    assertThat(d.parametri().quotaPrima()).isEqualTo(100);
    assertThat(d.parametri().quotaDopo()).isEqualTo(80);
    assertThat(d.parametri().roundAccumuloMax()).isEqualTo(2);
    assertThat(d.parametri().roundABordoMax()).isEqualTo(2);
    assertThat(d.di("Axis", "Germans")).isEmpty();
    assertThat(d.livello("Axis")).isEmpty();
  }

  @Test
  void theEngineKnowsNoNationAndNoMapName() throws Exception {
    for (final String file :
        List.of(
            "Compiti.java",
            "Risposta.java",
            "Ostacolo.java",
            "Compito.java",
            "Alleanza.java",
            "ValoreDirezione.java")) {
      final String src =
          Files.readString(
              Path.of("src/main/java/games/strategy/triplea/ai/proplus/" + file),
              StandardCharsets.UTF_8);
      for (final String word :
          List.of(
              "Americans",
              "\"USA\"",
              "British",
              "Gibraltar",
              "Southern Italy",
              "\"France\"",
              "\"Germany\"",
              "Egypt",
              "United Kingdom",
              "91 Sea",
              "MEDITERRANEO",
              "\"FRANCIA\"")) {
        assertThat(src).as(file + " contiene " + word).doesNotContain(word);
      }
    }
  }

  // ========================================================================= pure rules

  @Test
  void anObtainableTargetIsExecuted() {
    final Risposta.Scelta s =
        Risposta.decidi(
            List.of(
                target("A", false, ostacolo(Ostacolo.Tipo.MARE, 20)),
                target("B", true),
                target("C", true, ostacolo(Ostacolo.Tipo.DISTANZA, 10))),
            "A",
            10);
    assertThat(s.bersaglio().nome()).isEqualTo("B");
    assertThat(s.risposte()).containsExactly(Risposta.Tipo.ESEGUI);
    assertThat(s.scartate()).hasSize(2);
  }

  @Test
  void aSeaObstacleIsAnsweredWithPurchasesAndEscortAndABeatableFleetIsAttacked() {
    final Risposta.Bersaglio b =
        new Risposta.Bersaglio(
            "A",
            "z",
            false,
            List.of(ostacolo(Ostacolo.Tipo.TERRA, 20), ostacolo(Ostacolo.Tipo.MARE, 60)),
            "zona nemica",
            82);
    final Risposta.Scelta s = Risposta.decidi(List.of(b), null, 10);
    assertThat(s.risposte())
        .containsExactly(
            Risposta.Tipo.ATTACCO_FLOTTA,
            Risposta.Tipo.ACQUISTO_MARE,
            Risposta.Tipo.SCORTA,
            Risposta.Tipo.ACQUISTO_TERRA);
  }

  @Test
  void theTargetChangesOnlyWithAnObstacleSmallerByTheMargin() {
    final List<Risposta.Bersaglio> close =
        List.of(
            target("A", false, ostacolo(Ostacolo.Tipo.MARE, 50)),
            target("B", false, ostacolo(Ostacolo.Tipo.TERRA, 45)));
    assertThat(Risposta.decidi(close, "A", 10).bersaglio().nome()).isEqualTo("A");
    final List<Risposta.Bersaglio> far =
        List.of(
            target("A", false, ostacolo(Ostacolo.Tipo.MARE, 50)),
            target("B", false, ostacolo(Ostacolo.Tipo.TERRA, 30)));
    final Risposta.Scelta s = Risposta.decidi(far, "A", 10);
    assertThat(s.bersaglio().nome()).isEqualTo("B");
    assertThat(s.risposte()).contains(Risposta.Tipo.CAMBIO_BERSAGLIO, Risposta.Tipo.ACQUISTO_TERRA);
    assertThat(Risposta.decidi(List.of(), "A", 10).risposte()).containsExactly(Risposta.Tipo.ATTESA);
  }

  @Test
  void noNewTransportsWhileTheOwnedOnesCanCarryTheReadyTroops() {
    // 3 owned transports (also empty and idle) = 15 places: 14 places of troops, none bought
    assertThat(Risposta.trasportiDaComprare(14, 15, 5)).isZero();
    assertThat(Risposta.trasportiDaComprare(15, 15, 5)).isZero();
    assertThat(Risposta.trasportiDaComprare(16, 15, 5)).isEqualTo(1);
    assertThat(Risposta.trasportiDaComprare(26, 15, 5)).isEqualTo(3);
    assertThat(Risposta.trasportiDaComprare(0, 0, 5)).isZero();
  }

  @Test
  void theDirectionIsHeldThreeRoundsThenCheckedAgain() {
    assertThat(Risposta.ricontrollaDirezione(5, 0, 3)).isTrue();
    assertThat(Risposta.ricontrollaDirezione(5, 5, 3)).isFalse();
    assertThat(Risposta.ricontrollaDirezione(7, 5, 3)).isFalse();
    assertThat(Risposta.ricontrollaDirezione(8, 5, 3)).isTrue();
    final Map<String, Double> sums = new LinkedHashMap<>();
    sums.put("ITALIA", 80.0);
    sums.put("FRANCIA", 60.0);
    assertThat(Risposta.scegliDirezione(sums)).isEqualTo("FRANCIA");
    sums.put("FRANCIA", 80.0);
    assertThat(Risposta.scegliDirezione(sums)).isEqualTo("ITALIA");
  }

  @Test
  void theSeaPurchaseTakesTheUnitThatLowersTheObstacleMostPerPu() {
    final Map<String, double[]> c = new LinkedHashMap<>();
    c.put("destroyer", new double[] {8, 8});
    c.put("battleship", new double[] {30, 20});
    c.put("cruiser", new double[] {0, 12});
    assertThat(Risposta.miglioreAcquisto(c)).contains("battleship");
    c.clear();
    c.put("destroyer", new double[] {0, 8});
    assertThat(Risposta.miglioreAcquisto(c)).isEmpty();
  }

  // =================================================================== built positions

  @Test
  void theSwitchOffChangesNothing() {
    assertThat(Compiti.attivo(data, player("Americans"))).isFalse();
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno");
    assertThat(Compiti.attivo(data, player("Americans"))).isFalse();
    assertThat(Compiti.escludeImpegno(data, "Allies")).isFalse();
    assertThat(Compiti.trattenute(data, player("Americans"))).isEmpty();
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,compiti");
    assertThat(Compiti.attivo(data, player("Americans"))).isTrue();
    // step P2: the United Kingdom has a task too
    assertThat(Compiti.attivo(data, player("British"))).isTrue();
    assertThat(Compiti.attivo(data, player("Russians"))).isFalse();
    // with the tasks the commitment of the alliance is replaced (the others play as Hard)
    assertThat(Impegno.attivo(data, player("British"))).isFalse();
    assertThat(Impegno.attivo(data, player("Americans"))).isFalse();
  }

  @Test
  void theObstaclesOfTheBaseAreComputedWithTheirNumbersAndABeatableFleetIsAttacked() {
    on();
    atWar();
    final Territory z = territory("91 Sea Zone");
    clear(z);
    // an Italian destroyer in 91, a strong US fleet next to it
    add(z, "Italians", "destroyer", 1);
    final Territory from = territory("103 Sea Zone");
    clear(from);
    add(from, "Americans", "cruiser", 2);
    add(from, "Americans", "destroyer", 2);
    final Compiti.Turno t = turno(4);
    final Compito.Risultato r1 = t.task.risultati().get(0);
    final Risposta.Bersaglio b = t.controllo(r1);
    assertThat(b.ottenibile()).isFalse();
    assertThat(b.ostacoli()).extracting(Ostacolo::tipo).contains(Ostacolo.Tipo.MARE);
    final Ostacolo sea =
        b.ostacoli().stream().filter(o -> o.tipo() == Ostacolo.Tipo.MARE).findFirst().orElseThrow();
    assertThat(sea.luogo()).isEqualTo("91 Sea Zone");
    assertThat(sea.numeri().getAsJsonObject("nemiche_in_zona").get("destroyer").getAsInt())
        .isEqualTo(1);
    assertThat(sea.numeri().has("vittoria_nemica_fine_turno_pct")).isTrue();
    assertThat(b.flottaBattibile()).isEqualTo("91 Sea Zone");
    assertThat(b.vittoriaFlotta()).isGreaterThanOrEqualTo(70);
    final Risposta.Scelta s = Risposta.decidi(List.of(b), null, 10);
    assertThat(s.risposte()).contains(Risposta.Tipo.ATTACCO_FLOTTA);
  }

  @Test
  void noTransportIsBoughtWithEnoughEmptyOnesAndHardBuysNone() {
    on();
    atWar();
    final Territory port = territory("Eastern United States");
    final Territory zone = territory("101 Sea Zone");
    clear(zone);
    for (final Territory t : List.of(port, territory("Central United States"))) {
      t.getMatches(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()))
          .forEach(u -> data.performChange(ChangeFactory.removeUnits(t, List.of(u))));
    }
    data.getMap().getTerritories().stream()
        .filter(Territory::isWater)
        .forEach(
            z ->
                z.getMatches(Matches.unitIsOwnedBy(player("Americans")))
                    .forEach(u -> data.performChange(ChangeFactory.removeUnits(z, List.of(u)))));
    // 3 empty transports, 4 infantry ready (8 places) at the port
    add(zone, "Americans", "transport", 3);
    add(port, "Americans", "infantry", 4);
    final Compiti.Turno t = turno(6);
    final Compito.Risultato r3 = t.task.risultati().get(2);
    final Ostacolo capacity =
        new Ostacolo(Ostacolo.Tipo.CAPACITA, "porto", 7, -1, new JsonObject());
    final Risposta.Bersaglio b =
        new Risposta.Bersaglio("Southern Italy", null, false, List.of(capacity), null, -1);
    final List<String> responses = new ArrayList<>(List.of("ACQUISTO_CAPACITA"));
    final Compiti.Turno.Cassa cassa = t.cassa();
    t.acquisti(r3, b, List.of(Risposta.Tipo.ACQUISTO_CAPACITA), responses, cassa);
    t.ordina(cassa, "prova");
    final JsonArray bought = cassa.righe;
    assertThat(bought.toString()).doesNotContain("\"transport\"");
    assertThat(responses).doesNotContain("ACQUISTO_CAPACITA");
    assertThat(Compiti.ordini(t.proData)).isEmpty();
    assertThat(Compiti.vietaTrasporti(t.proData)).isTrue();
    // more troops than places: the transports that the missing places need, no more
    final int cap = t.capacita(zone.getMatches(Matches.unitIsSeaTransport()).get(0));
    final int perInfantry =
        port.getMatches(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()))
            .get(0)
            .getUnitAttachment()
            .getTransportCost();
    add(port, "Americans", "infantry", 3 * cap / perInfantry + 1);
    final Compiti.Turno t2 = turno(6);
    // step P2: the transports carry the troops the result needs (the TERRA obstacle's missing
    // troops), not every troop at home
    final JsonObject missing = new JsonObject();
    missing.addProperty("truppe_mancanti", 3 * cap / perInfantry + 1);
    final Risposta.Bersaglio b2 =
        new Risposta.Bersaglio(
            "Southern Italy",
            null,
            false,
            List.of(capacity, new Ostacolo(Ostacolo.Tipo.TERRA, "x", 1, -1, missing)),
            null,
            -1);
    final Compiti.Turno.Cassa cassa2 = t2.cassa();
    t2.acquisti(r3, b2, List.of(Risposta.Tipo.ACQUISTO_CAPACITA), new ArrayList<>(), cassa2);
    final JsonArray bought2 = cassa2.righe;
    JsonObject row = null;
    for (final var e : bought2) {
      if ("CAPACITA".equals(e.getAsJsonObject().get("ostacolo").getAsString())) {
        row = e.getAsJsonObject();
      }
    }
    assertThat(row).as(bought2.toString()).isNotNull();
    final int expected =
        Risposta.trasportiDaComprare(
            row.get("posti_da_portare").getAsInt(),
            row.get("posti_liberi_posseduti").getAsInt(),
            cap);
    assertThat(expected).as(row.toString() + " cap " + cap + " per " + perInfantry).isPositive();
    assertThat(row.getAsJsonObject("comprato").get("transport").getAsInt()).isEqualTo(expected);
  }

  @Test
  void theAllianceChoosesTheDirectionOnTheBoardWithValueAndObstaclesAndHoldsItThreeRounds() {
    on();
    atWar();
    final Compiti.Turno t = turno(5);
    Alleanza.inizioRound(t);
    final JsonObject al = t.st.getAsJsonObject("alleanza");
    assertThat(al.has("direzione")).isTrue();
    final JsonObject d = al.getAsJsonObject("direzione");
    assertThat(d.get("dal_round").getAsInt()).isEqualTo(5);
    assertThat(d.get("fino_al_round").getAsInt()).isEqualTo(7);
    assertThat(d.getAsJsonObject("conti").keySet())
        .containsExactlyInAnyOrder("MEDITERRANEO", "FRANCIA");
    for (final String k : List.of("MEDITERRANEO", "FRANCIA")) {
      final JsonObject c = d.getAsJsonObject("conti").getAsJsonObject(k);
      assertThat(c.has("valore")).isTrue();
      assertThat(c.has("ostacoli")).isTrue();
      assertThat(c.has("rapporto")).isTrue();
    }
    // the choice is the best ratio value / obstacles
    final List<Risposta.ContoDirezione> conti = new ArrayList<>();
    for (final var e : d.getAsJsonObject("conti").entrySet()) {
      final JsonObject c = e.getValue().getAsJsonObject();
      conti.add(
          new Risposta.ContoDirezione(
              e.getKey(), c.get("valore").getAsDouble(), c.get("ostacoli").getAsDouble()));
    }
    assertThat(d.get("scelta").getAsString())
        .isEqualTo(
            Risposta.scegliDirezioneAlleanza(conti, Compito.dati().parametri().ostacoliMinimiPu()));
    // the row with the items of the value (read from the map)
    final JsonObject row = lastRow("alleanza_direzione");
    assertThat(row.getAsJsonArray("alternative")).hasSize(2);
    final JsonObject first = row.getAsJsonArray("alternative").get(0).getAsJsonObject();
    assertThat(first.getAsJsonObject("voci_valore").keySet())
        .contains("territori", "capitale", "obiettivi", "urgenza", "totale");
    assertThat(row.getAsJsonObject("parametri").getAsJsonArray("forze_di").toString())
        .contains("Americans", "British");
    Compiti.salva(data.getProperties(), "Allies", t.st);
    final long rows = rows("alleanza_direzione");
    // rounds 6 and 7: held, no new row
    Alleanza.inizioRound(turno(6));
    final Compiti.Turno t7 = turno(7);
    Alleanza.inizioRound(t7);
    Compiti.salva(data.getProperties(), "Allies", t7.st);
    assertThat(rows("alleanza_direzione")).isEqualTo(rows);
    // round 8: checked again
    final Compiti.Turno t8 = turno(8);
    Alleanza.inizioRound(t8);
    assertThat(rows("alleanza_direzione")).isEqualTo(rows + 1);
    assertThat(t8.st.getAsJsonObject("alleanza").getAsJsonObject("direzione").get("dal_round").getAsInt())
        .isEqualTo(8);
  }

  @Test
  void aNationNotAtWarLeavesTheAllianceCountsToTheNextOne() {
    on();
    final GamePlayer usa = player("Americans");
    final GamePlayer uk = player("British");
    // Global 1940 at the start: the USA at war with nobody, the UK at war with the Axis
    assertThat(usa.isAtWar(player("Germans"))).isFalse();
    final Compiti.Turno t = turno(usa, 1);
    assertThat(Alleanza.inGuerraConNemici(t, usa)).isFalse();
    Alleanza.inizioRound(t);
    assertThat(t.st.getAsJsonObject("alleanza").has("direzione")).isFalse();
    Compiti.salva(data.getProperties(), "Allies", t.st);
    final Compiti.Turno u = turno(uk, 1);
    assertThat(Alleanza.inGuerraConNemici(u, uk)).isTrue();
    Alleanza.inizioRound(u);
    assertThat(u.st.getAsJsonObject("alleanza").has("direzione")).isTrue();
    assertThat(lastRow("alleanza_direzione").get("nazione").getAsString()).isEqualTo("British");
  }

  @Test
  void theDirectionWithTheBestRatioOfValueToObstaclesIsChosen() {
    // the Mediterranean is worth more for fewer obstacles
    assertThat(
            Risposta.scegliDirezioneAlleanza(
                List.of(
                    new Risposta.ContoDirezione("MED", 60, 40),
                    new Risposta.ContoDirezione("FR", 40, 40)),
                10))
        .isEqualTo("MED");
    // the enemy reinforces the Mediterranean: its obstacles grow, France wins
    assertThat(
            Risposta.scegliDirezioneAlleanza(
                List.of(
                    new Risposta.ContoDirezione("MED", 60, 200),
                    new Risposta.ContoDirezione("FR", 40, 40)),
                10))
        .isEqualTo("FR");
    // obstacles below the minimum count as the minimum; ties: the smaller obstacles
    assertThat(new Risposta.ContoDirezione("x", 30, 2).rapporto(10)).isEqualTo(3.0);
    assertThat(
            Risposta.scegliDirezioneAlleanza(
                List.of(
                    new Risposta.ContoDirezione("A", 20, 20),
                    new Risposta.ContoDirezione("B", 10, 10)),
                5))
        .isEqualTo("B");
  }

  @Test
  void theValueOfADirectionIsReadFromTheMap() {
    atWar();
    final GamePlayer uk = player("British");
    final Territory si = territory("Southern Italy");
    final ValoreDirezione.Conto v =
        ValoreDirezione.valore(data, uk, List.of(si), List.of(si), 30, t -> false);
    final int prod =
        games.strategy.triplea.attachments.TerritoryAttachment.getProduction(si);
    assertThat(v.voci().get("territori").getAsDouble()).isEqualTo(2.0 * prod);
    // the Italian capital: Italy collects nothing without it
    assertThat(v.voci().get("capitale").getAsDouble()).isPositive();
    assertThat(v.valore())
        .isEqualTo(
            v.voci().get("territori").getAsDouble()
                + v.voci().get("capitale").getAsDouble()
                + v.voci().get("obiettivi").getAsDouble()
                + v.voci().get("urgenza").getAsDouble());
    // an allied territory is not taken: no value
    final Territory london = territory("United Kingdom");
    assertThat(
            ValoreDirezione.valore(data, uk, List.of(london), List.of(london), 30, t -> false)
                .valore())
        .isZero();
  }

  @Test
  void theSharedResultLandsWhenObtainableGathersAtMostTwoRoundsThenAttrition() {
    final int max = Compito.dati().parametri().roundAccumuloMax();
    assertThat(max).isEqualTo(2);
    assertThat(Risposta.decidiCondiviso(true, true, 0, max, true))
        .isEqualTo(Risposta.Condivisa.CONGIUNTO);
    assertThat(Risposta.decidiCondiviso(false, true, 0, max, true))
        .isEqualTo(Risposta.Condivisa.ACCUMULO);
    assertThat(Risposta.decidiCondiviso(false, true, 1, max, true))
        .isEqualTo(Risposta.Condivisa.ACCUMULO);
    // after 2 rounds of gathering: land anyway where the exchange is best
    assertThat(Risposta.decidiCondiviso(false, true, 2, max, true))
        .isEqualTo(Risposta.Condivisa.LOGORAMENTO);
    assertThat(Risposta.decidiCondiviso(false, true, 2, max, false))
        .isEqualTo(Risposta.Condivisa.NESSUNO);
    assertThat(Risposta.decidiCondiviso(false, false, 0, max, false))
        .isEqualTo(Risposta.Condivisa.NESSUNO);
    // obtainable wins over the gathering count
    assertThat(Risposta.decidiCondiviso(true, true, 5, max, true))
        .isEqualTo(Risposta.Condivisa.CONGIUNTO);
    assertThat(Risposta.roundDiAccumulo(7, 0)).isZero();
    assertThat(Risposta.roundDiAccumulo(7, 5)).isEqualTo(2);
    // the troops never stay on board beyond 2 rounds
    assertThat(Risposta.troppoABordo(6, 5, 2)).isFalse();
    assertThat(Risposta.troppoABordo(7, 5, 2)).isTrue();
    assertThat(Risposta.troppoABordo(7, 0, 2)).isFalse();
  }

  @Test
  void theSharedResultCountsTheCombinedForcesOfTheNations() {
    on();
    atWar();
    final Territory nb = territory("Normandy Bordeaux");
    final Territory z = territory("110 Sea Zone");
    clear(z);
    final GamePlayer usa = player("Americans");
    final GamePlayer uk = player("British");
    // US transports loaded in the landing zone; British troops ready at their port
    final List<Unit> usTr = add(z, "Americans", "transport", 2);
    final List<Unit> usInf = add(z, "Americans", "infantry", 4);
    for (int i = 0; i < 4; i++) {
      data.performChange(
          ChangeFactory.unitPropertyChange(
              usInf.get(i), usTr.get(i / 2), Unit.PropertyName.TRANSPORTED_BY));
    }
    add(z, "Americans", "battleship", 2);
    final Compiti.Turno t = turno(6);
    final Compiti.Turno c = Alleanza.combinata(t);
    assertThat(c.squadra).containsExactly(usa, uk);
    final Alleanza.Valutazione v = Alleanza.valuta(t, c, nb, List.of(usa, uk));
    final JsonObject forces = v.json().getAsJsonObject("forze_sbarcabili");
    assertThat(forces.keySet()).containsExactly("Americans", "British");
    assertThat(forces.getAsJsonObject("Americans").getAsJsonObject("carico").get("infantry").getAsInt())
        .isEqualTo(4);
    assertThat(v.json().has("contrattacco_pct")).isTrue();
    assertThat(v.json().has("truppe_mancanti")).isTrue();
    // the combined analysis counts the units of both nations
    assertThat(c.flotta().stream().map(Unit::getOwner).distinct().toList()).contains(usa);
    assertThat(c.flotta().stream().anyMatch(u -> u.isOwnedBy(uk))).isTrue();
  }

  @Test
  void theUnitsTheTaskDoesNotUseThisTurnGoBackToHard() {
    // pure rule
    assertThat(Risposta.uso(Compito.TESTA_DI_PONTE, Risposta.Condivisa.NESSUNO, false, 0, 2)).isEmpty();
    assertThat(Risposta.uso(Compito.TESTA_DI_PONTE, null, false, 0, 2)).isEmpty();
    assertThat(Risposta.uso(Compito.TESTA_DI_PONTE, Risposta.Condivisa.ACCUMULO, false, 0, 2))
        .containsExactlyInAnyOrder(Risposta.Uso.FLOTTA, Risposta.Uso.SBARCO);
    assertThat(Risposta.uso(Compito.CONTROLLO_E_FLOTTA, null, false, 0, 2))
        .containsExactly(Risposta.Uso.FLOTTA);
    assertThat(Risposta.uso(Compito.CONTROLLO_E_FLOTTA, null, true, 0, 2))
        .containsExactlyInAnyOrder(Risposta.Uso.FLOTTA, Risposta.Uso.SBARCO);
    assertThat(Risposta.uso(Compito.PRESA, null, false, 1, 2)).contains(Risposta.Uso.TERRA_VICINA);
    assertThat(Risposta.uso(Compito.PRESA, null, false, 2, 2)).doesNotContain(Risposta.Uso.TERRA_VICINA);
    // on the board: the plan of the round says what is held
    on();
    atWar();
    final GamePlayer usa = player("Americans");
    final Territory z = territory("101 Sea Zone");
    add(z, "Americans", "transport", 1);
    add(z, "Americans", "destroyer", 1);
    final Compiti.Turno t = turno(6);
    final JsonObject piano = new JsonObject();
    piano.addProperty("round", 6);
    piano.add("uso", new JsonArray());
    t.ns.add("piano", piano);
    Compiti.salva(data.getProperties(), "Allies", t.st);
    assertThat(Compiti.trattenute(data, usa)).isEmpty();
    final JsonArray fleet = new JsonArray();
    fleet.add("FLOTTA");
    piano.add("uso", fleet);
    t.ns.add("piano", piano);
    Compiti.salva(data.getProperties(), "Allies", t.st);
    final java.util.Set<Unit> held = Compiti.trattenute(data, usa);
    assertThat(held).isNotEmpty();
    assertThat(held).allMatch(u -> !Compiti.trasporto(u) && !Matches.unitIsLand().test(u));
  }

  @Test
  void theUkServesCairoFirstAndGivesTheRestToTheBridgeheadOnlyTowardFrance() {
    on();
    atWar();
    final GamePlayer uk = player("British");
    final Compiti.Turno t = turno(uk, 6);
    // continuous results are never current: the current one is the bridgehead
    assertThat(t.corrente().id()).isEqualTo("U2");
    // the direction MEDITERRANEO: no shared result for the UK (Hard lands), U1 only
    final JsonObject dir = new JsonObject();
    dir.addProperty("scelta", "MEDITERRANEO");
    dir.addProperty("dal_round", 6);
    dir.addProperty("fino_al_round", 8);
    Compiti.obj(t.st, "alleanza").add("direzione", dir);
    Compiti.obj(t.st, "alleanza").addProperty("round", 6);
    Compiti.salva(data.getProperties(), "Allies", t.st);
    final long tenute = rows("compito_tenuta");
    final Compiti.Turno t2 = turno(uk, 6);
    t2.inizio();
    // U0 and U1 were served (two rows), the bridgehead not
    assertThat(rows("compito_tenuta")).isEqualTo(tenute + 2);
    final JsonObject piano = t2.ns.getAsJsonObject("piano");
    assertThat(piano.has("risultato")).isFalse();
    assertThat(piano.getAsJsonArray("tenute")).hasSize(2);
    assertThat(piano.getAsJsonArray("tenute").get(1).getAsJsonObject().get("territorio").getAsString())
        .isEqualTo("Egypt");
    // the direction FRANCIA: the rest of the share goes to U2
    dir.addProperty("scelta", "FRANCIA");
    Compiti.obj(t2.st, "alleanza").add("direzione", dir);
    Compiti.salva(data.getProperties(), "Allies", t2.st);
    final Compiti.Turno t3 = turno(uk, 6);
    t3.inizio();
    final JsonObject p3 = t3.ns.getAsJsonObject("piano");
    assertThat(p3.get("risultato").getAsString()).isEqualTo("U2");
    final JsonObject stato = lastRow("compito_stato");
    assertThat(stato.get("nazione").getAsString()).isEqualTo("British");
    assertThat(stato.getAsJsonObject("parametri").get("quota_pct").getAsDouble()).isEqualTo(80);
  }

  @Test
  void theBoxOfThePanelTurnsTheTasksOnForTheAlliesOnly() throws Exception {
    final GameData fresh = TestMapGameData.GLOBAL1940.getGameData();
    final ProfiloStratega p = ProfiloStratega.carica();
    p.setCompiti(true);
    p.salva();
    Stratega.registra(fresh, fresh.getPlayerList().getPlayerId("Germans"));
    Stratega.registra(fresh, fresh.getPlayerList().getPlayerId("Americans"));
    assertThat(StrategaInterruttori.COMPITI.acceso(fresh.getProperties(), "Allies")).isTrue();
    assertThat(StrategaInterruttori.IMPEGNO.acceso(fresh.getProperties(), "Allies")).isTrue();
    assertThat(StrategaInterruttori.COMPITI.acceso(fresh.getProperties(), "Axis")).isFalse();
    final ProfiloStratega q = ProfiloStratega.carica();
    q.setCompiti(false);
    q.salva();
    final GameData other = TestMapGameData.GLOBAL1940.getGameData();
    Stratega.registra(other, other.getPlayerList().getPlayerId("Americans"));
    assertThat(StrategaInterruttori.accesi(other.getProperties(), "Allies")).isEmpty();
  }

  // ============================================================================== helpers

  private static Risposta.Bersaglio target(
      final String name, final boolean obtainable, final Ostacolo... obstacles) {
    return new Risposta.Bersaglio(name, null, obtainable, List.of(obstacles), null, -1);
  }

  private static Ostacolo ostacolo(final Ostacolo.Tipo t, final double pu) {
    return new Ostacolo(t, "x", pu, -1, new JsonObject());
  }

  private long rows(final String tipo) {
    return RegistroDecisioni.righe(data).stream()
        .filter(r -> "decisione".equals(r.get("riga").getAsString()))
        .filter(r -> tipo.equals(r.get("tipo").getAsString()))
        .count();
  }

  private void on() {
    StrategaInterruttori.accendi(data.getProperties(), "Allies", "impegno,compiti");
  }

  private Compiti.Turno turno(final int round) {
    return turno(player("Americans"), round);
  }

  private Compiti.Turno turno(final GamePlayer p, final int round) {
    return new Compiti.Turno(proData(p, round), calc(), p);
  }

  private JsonObject lastRow(final String tipo) {
    final List<JsonObject> all =
        RegistroDecisioni.righe(data).stream()
            .filter(r -> "decisione".equals(r.get("riga").getAsString()))
            .filter(r -> tipo.equals(r.get("tipo").getAsString()))
            .toList();
    assertThat(all).as("righe " + tipo).isNotEmpty();
    return all.get(all.size() - 1);
  }

  private void clear(final Territory z) {
    final List<Unit> units = z.getMatches(Matches.unitIsSea().or(Matches.unitIsAir()));
    if (!units.isEmpty()) {
      data.performChange(ChangeFactory.removeUnits(z, units));
    }
  }

  /** The USA at war with the Axis; no Axis ship or plane anywhere. */
  private void atWar() {
    final GamePlayer usa = player("Americans");
    final var types = data.getRelationshipTypeList();
    for (final String a : List.of("British", "French", "Russians")) {
      final GamePlayer ally = player(a);
      data.performChange(
          ChangeFactory.relationshipChange(
              usa,
              ally,
              data.getRelationshipTracker().getRelationshipType(usa, ally),
              types.getDefaultAlliedRelationship()));
    }
    for (final String e : List.of("Germans", "Italians", "Japanese")) {
      war("Americans", e);
      war("British", e);
    }
    if (usa.getRulesAttachment() != null) {
      data.performChange(
          ChangeFactory.attachmentPropertyReset(
              usa.getRulesAttachment(), "movementRestrictionTerritories"));
    }
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> axis =
          t.getMatches(
              Matches.isUnitAllied(usa).negate().and(Matches.unitIsSea().or(Matches.unitIsAir())));
      if (!axis.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(t, axis));
      }
    }
  }

  private void war(final String a, final String b) {
    if (data.getRelationshipTracker().isAtWar(player(a), player(b))) {
      return;
    }
    data.performChange(
        ChangeFactory.relationshipChange(
            player(a),
            player(b),
            data.getRelationshipTracker().getRelationshipType(player(a), player(b)),
            data.getRelationshipTypeList().getDefaultWarRelationship()));
  }

  private List<Unit> add(final Territory t, final String owner, final String type, final int n) {
    final List<Unit> units =
        data.getUnitTypeList().getUnitTypeOrThrow(type).create(n, player(owner));
    data.performChange(ChangeFactory.addUnits(t, units));
    return units;
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrNull(name);
  }

  private ProData proData(final GamePlayer player, final int round) {
    data.getSequence().setRoundAndStep(round, "Purchase", player);
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
