@AGENTS.md

Nested `AGENTS.md` files exist throughout the source tree (under `lib/`,
`game-app/`, `http-clients/`, `gradle/`, `docs/`, etc.). When working in or
beneath a directory that contains an `AGENTS.md`, read that file as well.
Guidance from a nested `AGENTS.md` applies to its directory and descendants,
and takes precedence over higher-level `AGENTS.md` files on conflict.

---

# Progetto Pro+ (fork matteoposterli-netizen/triplea)

Memoria tecnica del progetto. **Leggere all'inizio di ogni sessione, aggiornare alla fine
(sezione "Log degli step").**

Obiettivo: potenziare l'AI "Hard" (ProAi) sulla mappa *World War II Europe 1940 2nd Edition*
(repo `triplea-maps/world_war_ii_europe`, file `map/games/ww2eur40_2nd_edition.xml`) creando una
AI **separata** "Pro+". L'utente (Matteo) non scrive codice e non usa il terminale: prova il
gioco su Windows (engine ufficiale 2026-11.16.15870, Java 25.0.3) usando lo zip della release.

## Vincoli (regole del progetto)

- Mai lavorare su `main` (il "master" del fork). Si lavora **solo sul branch `proplus`**.
- La ProAi originale (`ProAi`, "Hard (AI)") e "Hard Beta" **non vanno mai alterate**. Le
  modifiche vivono in una AI separata "Pro+" (nuove classi/sottoclassi) o dietro flag spento di
  default.
- Build e test devono passare, localmente e nella Action `ProPlus Release`.
- Non toccare file non necessari. Niente refactoring del codice upstream.
- Compatibilità salvataggi: non rinominare/cancellare campi privati delle classi
  `GameDataComponent` né cambiarne i package (serializzazione Java); non cambiare le firme dei
  metodi `@RemoteActionCode`. Vale anche per le classi Pro+ che finissero in un salvataggio.
- File specifici Pro+ fuori dal codice di gioco: `proplus/`,
  `.github/workflows/proplus-release.yml`, `.github/workflows/proplus-autoplay.yml`, modulo
  `game-app/proplus-autoplay` (+ 2 righe in `settings.gradle.kts`).
- Upstream: `https://github.com/triplea-game/triplea` (branch `main`). Il fork `main` va tenuto
  identico all'upstream; `proplus` si aggiorna con merge da `main`.

## Moduli rilevanti

Nomi Gradle piatti (`:game-core`, non `:game-app:game-core`), vedi `settings.gradle.kts`.

| Modulo | Cartella | Ruolo |
|---|---|---|
| `:game-core` | `game-app/game-core` | Motore, delegate, UI Swing condivisa, **tutte le AI Weak/Fast/Pro** |
| `:game-headed` | `game-app/game-headed` | Client desktop (main `org.triplea.game.client.HeadedGameRunner`), shadowJar, installer install4j |
| `:game-headless` | `game-app/game-headless` | Bot server per lobby (main `org.triplea.game.server.HeadlessGameRunner`); NON è un runner AI-vs-AI |
| `:ai` | `game-app/ai` | AI extra: `FlowFieldAi`, `DoesNothingAi` |
| `:battle-calc-core` | `game-app/battle-calc-core` | Calcolo battaglie "bounded" usato dall'odds calculator |
| `:smoke-testing` | `game-app/smoke-testing` | Test end-to-end: `AiGameTest` (partite tutte-AI), compatibilità salvataggi |
| `:domain-data`, `:map-data` | `game-app/...` | Dati di dominio, parsing mappe |

## Dove vive l'AI

Package `games.strategy.triplea.ai` in `game-app/game-core/src/main/java/games/strategy/triplea/ai/`:

- `AbstractAi` — base comune di tutte le AI (implementa `Player`); `AiUtils`, `AiPoliticalUtils`,
  `RocketTargetPlanner`.
- `weak/WeakAi` — "Easy (AI)", legacy.
- `fast/FastAi` — "Fast (AI)", estende `AbstractProAi` con calcolo veloce.
- `pro/` — ProAi:
  - `ProAi` — "Hard (AI)"; costruttore `ProAi(name, label, beta)`; `beta=true` = "Hard Beta (AI)".
    Usa un `LazyBattleCalculator` statico condiviso.
  - `AbstractProAi` — orchestrazione del turno: `purchase()`, `move()` (combat/non-combat),
    `place`, retreat, scramble, casualty, politica. Crea i sotto-moduli sotto.
  - `ProData` — stato per turno (player, data, mappe unità/territori, opzioni di acquisto);
    contiene il flag `beta` (`isBeta()`) che abilita i comportamenti "Hard Beta".
  - `ProPurchaseAi` — acquisti, riparazioni, piazzamento, bid.
  - `ProCombatMoveAi` — pianificazione e esecuzione attacchi.
  - `ProNonCombatMoveAi` — movimenti non di combattimento, difesa, atterraggi aerei.
  - `ProPoliticsAi`, `ProTechAi`, `ProRetreatAi`, `ProScrambleAi`, `ProBidPlanner`.
  - `data/` — `ProTerritory`, `ProTerritoryManager`, `ProPurchaseOption(Map)`, `ProTransport`, ...
  - `util/` — `ProTerritoryValueUtils` (valore strategico dei territori), `ProOddsCalculator`,
    `ProMatches`, `ProMoveUtils`, `ProPurchaseUtils`, `ProTransportUtils`, `ProBattleUtils`, ...
  - `logging/` — `ProLogger` (log AI; livelli configurabili da UI `ProLogUi`/`ProLogWindow`,
    impostazioni salvate in Java Preferences da `ProLogSettings`).
  - `simulate/` — simulazione turni (`ProDummyDelegateBridge`, `ProSimulateTurnUtils`).

### Registrazione delle AI selezionabili

- `game-core/.../engine/framework/startup/ui/PlayerTypes.java`: tipi built-in `WEAK_AI`,
  `FAST_AI`, `PRO_AI` (etichette i18n in
  `game-core/src/main/resources/i18n/games/strategy/engine/framework/ui*.properties`, es.
  `startup.PlayerTypes.PLAYER_TYPE_AI_HARD_LABEL=Hard (AI)`).
- `game-headed/.../engine/framework/startup/mc/HeadedPlayerTypes.java`: lista mostrata nel client
  = Human + built-in + "Hard Beta (AI)" + client + "Does Nothing (AI)" + "FlowField (AI)".
  I tipi in `BETA_PLAYER_TYPE_LABELS` compaiono solo con l'impostazione "show beta features".
- Il tipo è risolto per **etichetta** (`PlayerTypes.fromLabel`).

### Dove vive Pro+ (dallo step 1)

- `game-core/.../triplea/ai/proplus/ProPlusAi.java` — `ProPlusAi extends ProAi` (beta=false),
  etichetta `ProPlusAi.PLAYER_LABEL = "Pro+ (AI)"`. Costruita con `new ProData(false, new
  ProPlusTuning())` (costruttore protetto `ProAi(name, label, proData)`). Sovrascrive solo
  `purchase`/`move`/`place` per il log `[Pro+]` (riga di inizio turno con alleanza/rischio/soglie +
  riga per fase) e poi chiama `super`. Condivide il `LazyBattleCalculator` statico di `ProAi`.
- `ai/pro/ProTuning.java` (step 2) — interfaccia dei numeri modificabili; `ProTuning.DEFAULT` =
  valori originali (Hard/Hard Beta). Letta da `ProData` (`getTuning()`).
- `ai/pro/ProDecisionRecorder.java` (step 2bis) — osservatore delle decisioni (metodi no-op di
  default, `ProDecisionRecorder.NONE` in ogni `ProData`, `ProData.setRecorder`). Agganci di sola
  registrazione in `AbstractProAi` (inizio/fine fase), `ProCombatMoveAi` (candidati, scarti con
  motivo, stadi, piano finale), `ProPurchaseAi` (unità comprate dopo ogni passo).
- `ai/proplus/diario/` (step 2bis) — diario delle decisioni: `DiaryRecorder` (implementazione,
  per AI), `GameDiary` (cartella e file della partita), `DiarySettings` (cartella, on/off),
  `Italian` (testi), `AttackRecord`. Vedi "Diario delle decisioni".
- `ai/proplus/ProPlusTuning.java` — tuning Pro+: soglie di attacco dal livello di rischio, victory
  city valutate, peso VC nella priorità degli attacchi (step 2c), attacchi "che non convengono"
  tenuti per rischio / regola VC-obiettivo (step 2d, `keepsAttackNotWorthIt`,
  `keepsLandingWithExposedTransports`). `ai/pro/ProAttackWorth.java` (step 2d) — numeri del
  controllo finale "conviene con le unità assegnate". `RiskLevel.java` — i 5 livelli. `ProPlusSettings.java` — alleanze configurabili e
  lettura/scrittura dei livelli nelle proprietà della partita.
- `ai/pro/ProPurchaseOrders`, `ProStagingOrders` (record generici), `ProPlanPurchase` (esegue il
  mix), `ProStaging` (bonus di ammassamento) — step 3b, usati solo se `ProTuning.purchaseOrders`/
  `stagingOrders` restituiscono qualcosa (default vuoto ⇒ Hard identica).
- `ai/proplus/strategia/` (step 3a) — stato maggiore: `Strategia` (modello + parsing del file
  JSON), `Strategie` (indice gioco→file, cache, avvisi), `StatoMaggiore` (scelta e stato salvati
  nelle proprietà della partita, `fix`), `PianoAttivo` (ordini per nazione/round: fase, rischio per
  fronte, obiettivi). Dati: `game-core/src/main/resources/proplus/strategia/indice.json` +
  `ww2eur40_2nd_edition.json`. Vedi "Stato maggiore e piani".
- Step 3c: `ai/proplus/MinacciaReale` (nemici reali), `ai/pro/ProThreat` (minaccia per il diario),
  `ai/proplus/strategia/Valutazione` (avanzamento del piano e lettura del nemico). Vedi "Minaccia
  reale" e "Avanzamento del piano e lettura del nemico".
- Step 3c-bis: `ProTuning.checksLandingScramble` (default = Hard Beta; Pro+ sempre) e
  `ProTuning.dropsAmphibiousStrafe` (default mai; Pro+ sotto la soglia minima del bersaglio), agganci
  in `ProCombatMoveAi`. Vedi "Step 3c-bis".
- Step OPERAZIONI ANFIBIE: `ai/proplus/OperazioniAnfibie` (esecuzione nel non-combattimento reale +
  acquisti), `ProTuning.heldUnits` (unità che il non-combattimento di Hard non muove; default vuoto),
  aggancio in `ProNonCombatMoveAi.findUnitsThatCantMove`, `Strategia.Operazione`/`Porto`,
  `PianoAttivo.getOperazioni`. Vedi "Operazioni anfibie".
- Step 3d-1: `ai/proplus/strategia/Contromosse` (cambio piano, corsa, postura, contro-obiettivi),
  `StatoMaggiore.cambia` (+ `cambi` nello stato), `PianoAttivo` (postura e contro-obiettivi del round),
  `OperazioniAnfibie.rientro` (truppe ferme a bordo). Vedi "Postura, cambio piano, contromosse".
- Step 3d-2: `ai/proplus/strategia/Tattiche` (interruttori + decisore a regole), `ai/proplus/StrumentiRitardo`
  (blocco terrestre, ritirata, blocco navale, registro), `PianoAttivo.getCaccia`/`tattica`, `ProTuning.dropsLandAttack`
  (aggancio in `ProCombatMoveAi`, default vuoto), `Strategia.Ritardo`. Vedi "Strumenti di ritardo".
- Step CONTRATTO STRATEGICO: `ai/proplus/strategia/` `Decisore` (interfaccia + `Contesto`), `DecisoreRegole`
  (default), `DecisoreFile`, `DecisoreLlm` (vuoto), `Decisori` (scelta per alleanza), `Ingresso` (stato strategico
  JSON), `Direttive` (schema, validazione, paletti); `Valutazione.inizioRound` chiama il decisore, `PianoAttivo` legge
  solo le direttive. Vedi "Contratto strategico" e `proplus/strategia/contratto-v1.md`.
- Step INTEGRAZIONE LLM: `ai/proplus/strategia/DecisoreLlm` (catena di trasporti), `Decisori` (modalita' per
  alleanza), pacchetto `ai/proplus/strategia/llm/` (`Modalita`, `Fornitore`, `ProfiloStratega` chiavi nel profilo
  utente, `ClaudeCode`, `ClientApi` + `Http`, `PromptStratega`, `UscitaLlm`, `Uso`, `Prezzi`), risorse
  `proplus/strategia/prompt-stratega-v1.md` e `prezzi-stratega.json` (game-core), `ai/proplus/TettoTrasporti`
  (paletto per alleanza), UI `StrategistPanel` (game-headed). Vedi "Stratega LLM" e "Come configurare lo stratega".
- Step ABLAZIONE: `ai/proplus/Ablazione` (interruttori per alleanza nella proprieta' `Pro+ ablazione: <alleanza>`,
  default = tutto acceso); agganci in `ProPlusTuning` (ogni override torna al valore di `ProTuning.DEFAULT` col suo
  componente spento), `PianoAttivo`, `Contromosse`, `OperazioniAnfibie.safety`, `TettoTrasporti`, `ProPlusAi`. Vedi
  "Ablazione (step ABLAZIONE)".
- Step PROVA DI VALORE: `ai/proplus/OperazioneCongiunta` (definizioni dal file `proplus/strategia/operazioni-congiunte.json`,
  interruttore `Pro+ operazione congiunta: <alleanza>`, stato `Pro+ operazione congiunta stato: <id>`), `ai/proplus/OperazioniCongiunte`
  (motore: fattibilita', riserve, posizionamento, tappe, diario), `ai/proplus/ControlloTuning` (contatore: ogni risposta del tuning
  confrontata con Hard), hook `ProTuning.combatHeldUnits` (default vuoto) + `ProMyMoveOptions.removeUnits` chiamato in
  `ProCombatMoveAi`. Vedi "Operazioni congiunte (step PROVA DI VALORE)".
- Step PROVA2: variante v2 "presa e tenuta" nello stesso motore (`OperazioniCongiunte`: contrattacco atteso al lancio,
  stato TENUTA, rinforzi, copertura aerea, tappa sul bersaglio finale; `OperazioneCongiunta.Tenuta`, stati TENUTA/FALLITA,
  `bersaglio_finale`), riepilogo `ConfrontoTenuta` (autoplay). Nessun hook nuovo nelle classi Pro. Vedi "Variante v2: presa
  e tenuta (step PROVA2)".
- Step PROVALLM: `ai/proplus/DecisoreOperazione` (interruttore `Pro+ operazione decisore: <alleanza>`, budget, uso, paracadute),
  `ai/proplus/StoricoNemico` (risposta reale del nemico), `ai/proplus/strategia/DecisioneOperazione` (contratto v1.2),
  `strategia/llm/PromptOperazione` + risorsa `proplus/strategia/prompt-operazione-v1.md`; punti di decisione in `OperazioniCongiunte`
  (`decide`, `stato`); riepilogo `ConfrontoLlm` (autoplay). Vedi "Operazioni decise da LLM (step PROVALLM)".
- Step STRATEGA LLM: voce `ai/proplus/ProPlusStrategaAi` ("Pro+ Stratega LLM (Europe 1940)", `HeadedPlayerTypes.PRO_PLUS_STRATEGA_AI`
  subito dopo "Pro+ (AI)"), `Stratega` (registrazione, livello strategico, portafoglio, riga di fine turno), `StrategaCandidati`
  (candidati dei 4 tipi + lettura dell'avversario + definizioni dinamiche), `StrategaDecisione` (contratto 1.3), `StrategaFuoriLista`,
  `StrategaDiario`, `StrategaConfig` (`proplus/strategia/stratega-llm.json`), `StrategaSimulato` (solo test/prove locali);
  operazioni dinamiche in `OperazioneCongiunta` (`DYNAMIC_PREFIX`, `tutte`, `setDinamiche`); prompt `prompt-stratega-v4.md`,
  `prompt-ordini-turno-v3.md` (`strategia/llm/PromptStrategaV4`, `PromptOrdiniTurnoV3`). Vedi "Stratega LLM completo".
- Step COERENZA STIME: `ai/proplus/StimaUnica` (una sola stima di presa e tenuta per candidati, punti di decisione ed
  esecuzione), `CauseAnnullamento` (C1-C6/giustificato registrati in automatico); in `OperazioniCongiunte` `step1Stratega`,
  `presaSenzaOperazione`, `primaDellaDecisione`, `lancio` (fattibilita'), `holdPct`/`sicuro` (attacco realistico solo per le
  operazioni dello Stratega), `raggiungibiliOra`/`sbarcabili`, `rinforziPossibili`, `trattenuteAllAcquisto` (+
  `ProPlusTuning.holdCombatForPurchase`, da `ProPlusAi.purchase` solo per le nazioni dello Stratega); prompt `prompt-stratega-v5.md`, `prompt-ordini-turno-v4.md`
  (`PromptStrategaV5`, `PromptOrdiniTurnoV4`, sostituiscono v4/v3). Vedi "Coerenza delle stime (step COERENZA STIME)".
- Step C1-bis: aerei (e ogni unita') contati per una tappa futura trattenuti o non contati (`StimaUnica.forze`,
  `OperazioniCongiunte.trattenute`/`avvicina`/`colpisceDaQui`/`combat`), cause con il calo di stima prima di C5
  (`CauseAnnullamento`), valore d'alleanza in PU con i TUV nemici distrutti (`StimaUnica.Stima.valoreAlleanza`,
  `StrategaCandidati.valoreBersaglioPu`, `stratega-llm.json` `valore_pu`). Vedi "Step C1-bis".
- Step C1-ter: un solo controllo di sicurezza per mosse e stima (`OperazioniCongiunte.passiSicuri`/`arriviSicuri`/
  `haPercorsoSicuro`/`luoghiRaccolta`), bersaglio vuoto (presa 100%/0%, `Stima.soloAerei`), TUV distrutti solo dei difensori
  presenti (`StimaUnica.destroyed`). Vedi "Step C1-ter".
- Step C2: budget difensivo calcolato (solo Stratega LLM): `ai/proplus/Difesa` (minaccia, valore, costo, piano difensivo,
  esecuzione nel non combattimento, liste dell'LLM, registro `Pro+ stratega llm difesa: <alleanza>`), `ai/proplus/Tracciamento`
  (unita' contate dalle conquiste, cause dei cali >= 30 punti), hook `ProTuning.defensePlan` + record `ai/pro/ProDefensePlan`
  (default vuoto; in `ProPurchaseAi` sostituisce la difesa di terra e limita i PU degli ordini), `StimaUnica.nemiciReali`/
  `soloNemiciReali`/`contate`, contratto 1.5 (`irrinunciabili`, `sacrificabili`). Vedi "Budget difensivo calcolato (step C2)".
- Step C2-bis: la difesa torna a Hard (solo Stratega LLM): hook `ProTuning.strategyCapPercent` (tetto degli ordini DOPO la
  difesa di Hard) e `ProTuning.defenseLists` + record `ai/pro/ProDefenseLists` (sacrificabili esclusi, irrinunciabili sempre
  difesi, in `ProPurchaseAi` e `ProNonCombatMoveAi`), record `ai/pro/ProStrategyBudget` + hook diario
  `ProDecisionRecorder.strategyBudget`; tolti `ProTuning.defensePlan` e `ProDefensePlan`. `Difesa` solo IN OMBRA (diario).
  Vedi "Difesa di Hard e budget dinamico (step C2-bis)".
- Step R2b IMPEGNO: `ai/proplus/Impegno` (prima campagna che AGISCE, interruttore `impegno` di `StrategaInterruttori`, default
  dal file dati `stratega-llm.json` `impegno.acceso_di_default` = spento), agganci in `ProPlusAi` (acquisto, non combattimento,
  piazzamento), `ProPlusTuning.purchaseOrders`/`strategyCapPercent`, `OperazioniCongiunte.forzaBersaglio`/`forzaUnBersaglio`/
  `sogliaForzata(proData, t)`; metodi nuovi in `CampagnaOmbra.Calcolo` (`fronti`, `obiettivoPer`, `valutaImpegno`); casella
  "Impegno" nel pannello Stratega (`StrategistPanel`, profilo `ProfiloStratega.impegno`, letto da `Stratega.registra`). Vedi
  "Impegno (step R2b)".
- Step R2c PREPARAZIONE E PROGRAMMI: `ai/proplus/Programmi` (config `impegno.r2c`, carico bilanciato, spola USA, Medio Oriente),
  `Impegno` (fasi, base sicura, B1-B4), hook `ProTuning.purchaseOrdersBought` e `ProTuning.reviewAttacks` (default vuoti; chiamati in
  `ProPurchaseAi` e `ProCombatMoveAi`). Vedi "Preparazione e programmi (step R2c)".
- Step R3a SBARCO COORDINATO E TEATRI: `ai/proplus/SbarcoCoordinato` (teatro, preparazione, via libera, sbarco, Regno Unito, testa di
  ponte, tetto ai trasporti), `ai/proplus/RegistroDecisioni` (`impegno-decisioni.jsonl`), hook `ProTuning.buysSeaTransports` e
  `ProTuning.amphibiousHeldUnits` (default = Hard; in `ProPurchaseAi` e `ProCombatMoveAi`), `ProPurchaseOptionMap.withoutSeaTransports`,
  `ProPlusAi.playCoordinatedLanding`; autoplay `RegistroLettura`, `ConfrontoR3a`. Vedi "Sbarco coordinato e teatri (step R3a)".
- Step R4 STRATEGIA COMPLETA: `ai/proplus/ConfigR4` (dati `impegno.r4`), `Reddito` (regola C), `Assedio` (blocco A, interruttore
  `assedio`), `SnodoAlleati` (blocco B, interruttore `alleati_r4`; snodo e ondate in `SbarcoCoordinato.teatroR4`/`viaLiberaR4`),
  `StrategaInterruttori.ASSEDIO`/`ALLEATI_R4`, `ProPurchaseOrders.Mandatory.factory` (facoltativo, usato da `ProPlanPurchase`),
  `Assedio.bombarda` dall'hook `reviewAttacks`; autoplay `MonitorR4`, `ConfrontoR4`, `Vittoria.letturaXml`/`letturaNote`. Vedi
  "Strategia completa (step R4)" e "Scelte di R4".
- Step R5 ALLEATI RIFATTI: `ai/proplus/AlleatiR5` (config `impegno.r5`, interruttore `alleati_r5`), agganci in `SbarcoCoordinato`,
  `Programmi`, `Impegno`, `SnodoAlleati`, `Reddito`, `RegistroDecisioni`, `StrategaInterruttori.ALLEATI_R5`; autoplay `ConfrontoR5`,
  `Monitor` (+`r5`). Vedi "Alleati rifatti (step R5)" e "Scelte di R5".
- Step R6 SQUADRA DI GIBILTERRA: `ai/proplus/SquadraGibilterra` (config `impegno.r6`, interruttore `squadra_gibilterra`), agganci in
  `AlleatiR5` (attivo anche con R6 per gli USA, `inizio` delega, `snodo` col sostegno), `SbarcoCoordinato.Turno` (`supportoR6`, stima con
  aerei e bombardamento, battaglia e movimento della squadra), `Programmi.spola` (niente vele prima dell'arrivo, scorte fuori squadra,
  vuoti con scorta), `Impegno` (PU 100%/80%, `quotaOperazione` + `KEY_QUOTA` = punto D, esclusione di UK/Russia/Francia), `ProPlusTuning`
  (`capitalGuardThreshold` 100 nei round di costruzione), `RegistroDecisioni`; `ProPlusAi.prepareData`/`attendiCopie` (punto 0);
  autoplay `ConfrontoR6`, `--quota-operazione`. Vedi "Squadra di Gibilterra (step R6)" e "Scelte di R6".
- Step R6b TRASPORTI PROTETTI: `ai/proplus/TrasportiProtetti` (config `impegno.r6b`, stesso interruttore `squadra_gibilterra`), agganci in
  `AlleatiR5` (`migliore`, `primoProtetto`, snodo e presa), `SbarcoCoordinato.Turno` (`zonaScarico`, `scorteUsate`, esecuzione delle
  ondate), `SquadraGibilterra` (`raduna`, `pressione`, `spola` con `maxNavi`), `Programmi.Turno` (`attaccoRealistico`, spola R6b),
  `Impegno` (seconda fabbrica negli ordini), `RegistroDecisioni` (tipi, motivi, `scriviEsterna`), `Stratega.registra` + `ProfiloStratega` +
  `StrategistPanel` (caselle "Assedio" e "Squadra di Gibilterra"); autoplay `Monitor` (`attacco_subito`), `ConfrontoR6b` +
  `r6-riferimento.json`. Vedi "Trasporti protetti (step R6b)" e "Scelte di R6b".
- UI: `game-headed/.../startup/ui/ProPlusSettingsDialog.java` + pulsante in `SetupPanel`.
- Registrazione: `HeadedPlayerTypes.PRO_PLUS_AI` (game-headed), sempre visibile (non dietro "beta
  features"), in lista subito dopo "Hard (AI)". Non è in `PlayerTypes.getBuiltInPlayerTypes()`
  quindi il bot headless non la offre.
- Branding: `game-core/.../org/triplea/proplus/ProPlusBranding.java` — titolo
  `"TripleA Pro+ <versione>"` usato da `MainFrame` (schermata iniziale) e `TripleAFrame`
  (`"TripleA Pro+ <versione> - <nome partita>"`); riquadro info della schermata iniziale
  (`GameSelectorPanel`, "Engine Version") mostra `<versione> (Pro+)`.
- Test: `game-core/src/test/.../ai/proplus/ProPlusAiTest.java`, `HeadedPlayerTypesTest`
  (registrazione + istanziazione).

## Build, test, avvio

JDK 25 richiesto (toolchain Gradle `JavaLanguageVersion.of(25)`; `mise.toml` → temurin-25).

```bash
./gradlew :game-headed:run                 # avvia il client
./gradlew :game-headed:shadowJar           # jar eseguibile completo -> game-app/game-headed/build/libs/game-headed-<versione>.jar
./gradlew :game-core:test                  # test motore + ProAi
./gradlew :game-core:test --tests 'games.strategy.triplea.ai.pro.*'   # solo test ProAi
./gradlew :smoke-testing:test --tests games.strategy.engine.data.AiGameTest  # partite tutte-AI
./verify                                   # tutto (spotless, PMD, test, check custom)
./gradlew spotlessApply                    # formattazione
```

- Versione release: `YYYY-MM.<product-version.txt>.<git rev-list --count HEAD>` (task
  `printReleaseVersion`). Lo script `game-app/run/.build/set-game-headed-build-number` la scrive
  in `game-core/src/main/resources/META-INF/triplea/product.properties` (solo in CI, mai
  committare: i test si aspettano `0.0+00000`).
- La versione nel salvataggio è scritta ma **ignorata** in lettura (`GameDataManager`): la
  compatibilità dipende solo dalla serializzazione delle classi.
- Ambiente sandbox Claude: Maven Central a volte risponde 429 tramite proxy → ripetere il comando
  (le dipendenze scaricate restano in cache). JDK 25 scaricabile da GitHub releases Adoptium.

### Partita headless AI vs AI

**Banco Pro+ (dallo step 3c, committato)**: `smoke-testing/.../engine/data/ProPlusBenchTest`, attivo
solo con la variabile d'ambiente `PROPLUS_BANCO_XML` (percorso dell'XML della mappa, es.
`ww2eur40_2nd_edition.xml` scaricato da `triplea-maps/world_war_ii_europe`); `PROPLUS_BANCO_ROUNDS`
(4), `PROPLUS_BANCO_PIANI` (`Axis=A,Allies=1`), `PROPLUS_BANCO_SEED` (1, dadi di gioco; le
simulazioni dell'AI restano casuali), `PROPLUS_BANCO_DIARIO` (cartella del diario = l'output).
Tutte le nazioni Pro+ Equilibrato. Comando: `./gradlew :smoke-testing:test --tests
'*ProPlusBenchTest' --rerun` (senza `--rerun` Gradle salta il test se il codice non è cambiato).
Nella CI e in `:smoke-testing:test` normale è saltato.

**Runner autoplay (dallo step AUTOPLAY, al posto del banco)**: modulo `:proplus-autoplay`
(`game-app/proplus-autoplay`, main `org.triplea.proplus.autoplay.AutoplayRunner`), stesso set-up del
banco (XML letto direttamente, niente risorse della mappa, niente autosave, pause AI 0, dadi di gioco
con seme; le simulazioni dell'AI restano casuali). Mappa: `git clone --depth 1
https://github.com/triplea-maps/world_war_ii_europe` → `map/games/ww2eur40_2nd_edition.xml`.

```bash
# da Gradle (percorsi relativi alla radice del repo)
./gradlew :proplus-autoplay:run --args="--mappa /percorso/ww2eur40_2nd_edition.xml --round 2 \
  --piano Axis=A,Allies=1 --seme 1 --output build/autoplay/prova"
# da riga di comando (dopo ./gradlew :proplus-autoplay:installDist)
game-app/proplus-autoplay/build/install/proplus-autoplay/bin/proplus-autoplay --mappa ... --round 8 \
  --piano Axis=D,Allies=2 --rischio Axis=Audace,Allies=3 --tipo Axis=proplus,Allies=hard \
  --seme 4 --output out/partita --minuti 55 --nome "D / 2"
# confronto di piu' partite (una sottocartella per partita) -> <cartella>/riepilogo-generale.md
.../bin/proplus-autoplay confronto out --titolo "Prova"
```
- Parametri (per alleanza = nome XML dell'alleanza: `Axis`, `Allies`): `--mappa` (obbligatorio),
  `--round` (12, dallo step 3c-bis), `--piano` (id del piano, default `auto`), `--rischio` (1-5 o nome, default
  Equilibrato), `--tipo` (`proplus` | `hard`, default proplus; Hard = `PlayerTypes.PRO_AI`, col diario),
  `--seme` (1), `--output` (`autoplay-output`), `--minuti` (55: oltre, la partita si ferma e il
  riepilogo è "parziale"), `--nome`. Nazioni fuori dalle alleanze configurabili: Pro+ (Hard se tutte
  Hard). Uscita 0 = ok (anche parziale), 1 = errore della partita, 2 = parametri errati.
- Output: `<output>/diario/<data>_<mappa>/{diario.txt,decisioni.jsonl}` (stesso diario di Pro+, anche
  per Hard) + `<output>/riepilogo.json` e `riepilogo.md`, **riscritti a ogni fine round** (e da uno
  shutdown hook se il processo viene ucciso) ⇒ un riepilogo parziale c'è sempre.
- Riepilogo (`Monitor` legge il tabellone tra uno step e l'altro, `Riepilogo` scrive): per round e
  nazione PU (cassa a fine round), reddito (`ProUtils.getPlayerProduction`), PU difesa (passi
  `defend_land` + `capital_emergency` del diario, valorizzati con i costi TUV), PU piano (passo
  `plan`), PU spesi (`acquisti_totale`), TUV, territori di terra; VC per alleanza con i nomi; per le
  alleanze Pro+ piano, stato (`storico` dello stato maggiore) e lettura dell'avversario (più probabile,
  reale, probabilità del reale, corretta); dichiarazioni di guerra (coppie che passano in guerra, con
  la nazione di turno); capitali cadute/liberate; top 5 battaglie per TUV perso (attaccante+difensore,
  da `BattleRecordsList`, campi letti per riflessione) con win% stimata ed esito del diario
  (`esito_battaglia`); esito (vincitori dell'`EndRoundDelegate` ⇒ "vittoria Asse/Alleati", altrimenti
  "nessuna entro N round") e VC finali. Step 3c-bis: trasporti per nazione a fine round (colonna
  "Trasporti"). Dallo step OPERAZIONI ANFIBIE **sbarchi veri** (`Monitor.recordLandings`, dopo ogni
  passo di movimento): unità di terra della nazione di turno nella lista `unloaded` di un trasporto,
  in un territorio che a inizio turno della nazione era di un nemico in guerra (assalto anfibio nel
  combattimento, o territorio preso nel turno se scaricate nel movimento); scaricare su terra propria
  o alleata non conta. JSON `sbarchi` [{round, nazione, alleanza, territorio, unita, fase}],
  `unita_sbarcate` per round e alleanza, `primo_sbarco_vero` per alleanza, `primo_sbarco_europa` =
  primo sbarco vero di British/Americans (`Monitor.WESTERN_ALLIES`) in `Monitor.EUROPE` (prima:
  battaglie o presenze, sostituito). **Trasporti inattivi** (`trasporti_inattivi` per nazione e
  round): trasporti puri della nazione fermi (`alreadyMoved` 0), vuoti e senza scarichi a fine del
  suo movimento non di combattimento. Dal diario: `operazioni` (eventi `operazione_anfibia`),
  `attacchi_regno_unito` (eventi `attacco` tedeschi su United Kingdom/Scotland), `pu_riparazioni`.
- **D'ora in poi niente banchi lunghi in sandbox**: verifica breve locale (UNA partita da 2 round,
  ~1 min) + autoplay in CI (sotto). Il banco `ProPlusBenchTest` resta per compatibilità.
- Test: `:proplus-autoplay:test` (`AutoplayTest`: parametri, PU dal diario, markdown, confronto).

## Autoplay in CI (`.github/workflows/proplus-autoplay.yml`)

- Trigger: job `autoplay` di `proplus-release.yml` (dopo `build`, workflow riutilizzabile
  `workflow_call`, 12 round, 6 configurazioni × 2 semi = 12 partite dallo step 3c-bis; dallo step
  OPERAZIONI ANFIBIE 7 configurazioni = 14 partite, aggiunta **B/1** in coda dopo Hard, così gli id
  delle altre restano uguali): la release è già pubblicata, quindi non la blocca né la
  modifica. **Solo se il messaggio dell'ultimo commit del push contiene `[autoplay]`** (regole di
  consumo, sotto). **`workflow_run` e `workflow_dispatch` non funzionano**: GitHub li accetta solo per
  workflow presenti sul branch di default (`main` = upstream, non si tocca): verificato, `gh workflow
  run` ⇒ "HTTP 404: workflow proplus-autoplay.yml not found on the default branch".
  `workflow_dispatch` (input `round`, `partite` = prime N configurazioni di A/1, A/2, D/1, D/2, C/1,
  Hard, `semi` = semi per configurazione (2, max 4), `piani` = es. `A/1,B/2,hard`) resta nel file per quando/se il file sarà su `main`. Ripetere a mano oggi:
  Actions → run di "ProPlus Release" → "Re-run all jobs" (rifà anche la release).
- Job: `prepare` (installDist + XML della mappa + matrice, artefatto `autoplay-kit`), `play` (matrice,
  max-parallel 6, fail-fast off, timeout 30 min, `--minuti 25`, semi 1..N **uguali per ogni
  configurazione** (id `<n>-Asse-A-Alleati-1-seme<s>`), rischio Equilibrato per entrambe, artefatto
  `partita-<id>`), `publish` (anche se qualche partita fallisce: cartella vuota = "FALLITA" nel
  confronto; `riepilogo-generale.md` con: tabella per partita (VC a 1/3, 2/3 e fine = round 4/8/12,
  PU finali, esito), **tabella per configurazione** (chiave = tipi + piani, es. "Pro+ A / Pro+ 1";
  media dei semi: VC, vittorie col round, PU finali) e **lettura per configurazione** (per round:
  probabilità media del piano reale + partite con lettura corretta, colonna "Media" con % OK),
  primo sbarco alleato in Europa e trasporti per nazione a fine partita, lettura per partita e round,
  PU difesa/piano/spesi; dallo step OPERAZIONI ANFIBIE sezione "Sbarchi veri e trasporti" (per partita:
  primo sbarco vero per alleanza, unità sbarcate e trasporti inattivi in tutta la partita, operazioni
  bloccate; per configurazione: partite con sbarco alleato entro R6, round medio del primo sbarco,
  sbarcate e inattivi per round; operazioni bloccate con i motivi più frequenti; attacchi tedeschi
  alle isole britanniche); `autoplay.zip`; release **`autoplay-latest`** ricreata sul commit
  testato, prerelease, note con commit/data/run; non pubblica se `proplus` è già a un commit più nuovo,
la cui run pubblicherà). `proplus-latest` mai toccata.
- Dallo step INTEGRAZIONE LLM, **solo se esiste il secret `ANTHROPIC_API_KEY`** (`secrets: inherit` dal job
  `autoplay` di `proplus-release.yml`; senza secret niente errori, le partite non entrano nella matrice): 4 partite
  in coda con lo stratega LLM (Claude Haiku, `--decisore <alleanza>=llm`, chiave passata come variabile d'ambiente
  solo a quei job): `<n+1>-LLM-Alleati-vs-Asse-A-seme1/2` (Alleati LLM, Asse A regole) e
  `<n+2>-LLM-Asse-vs-Alleati-1-seme1/2` (Asse LLM, Alleati 1 regole), piani di partenza Axis=A, Allies=1 (confronto
  con A/1). Dallo step LLM-3: **4 semi** per le due configurazioni LLM, 2 partite `<n+3>-Sonnet-Alleati-vs-Asse-A-
  seme1/2` (Alleati con Claude Sonnet 5.5) e A/1 a regole anche con i semi 3 e 4 (`1-Asse-A-Alleati-1-seme3/4`):
  24 partite. Sezione "Confronto per configurazione" nel `riepilogo-generale.md`: VC finali, sbarchi veri per
  alleanza, % lancia/attendi/annulla (stratega + ordini dell'alleanza LLM, o regole Alleati), posture, costo per
  partita. Nel `publish`, dopo il confronto, `proplus-autoplay qualita autoplay --n 30 --output autoplay/qualita`
  (test di qualita' su 30 stati strategici delle partite, nello zip e nel sommario della run) e, dallo step LLM-2,
  `qualita autoplay --n 30 --livello ordini --output autoplay/qualita-ordini`. Le 14 partite
  solo-regole restano il riferimento.
- **Modalita' "confronto" (step CONFRONTO CONTRO HARD)**: `[confronto]` nel messaggio del commit di testa (in
  alternativa a `[autoplay]`) ⇒ input `modalita: confronto` del workflow riutilizzabile. 12 round, rischio base
  Equilibrato, **semi 1-4 per ogni configurazione**: 1 `Hard-vs-Hard` (riferimento), 2 `Asse-ProPlus-A-vs-Hard`, 3
  `Asse-ProPlus-D-vs-Hard`, 4 `Hard-vs-Alleati-ProPlus-1`, 5 `Hard-vs-Alleati-ProPlus-2`, 6 `Asse-Haiku-vs-Hard`
  (`--decisore Axis=llm`, piano di partenza A), 7 `Hard-vs-Alleati-Haiku` (`Allies=llm`, piano 1); 6 e 7 solo col
  secret (senza: 20 partite, nessun errore). Niente test di qualita' in questa modalita'. Il `riepilogo.json` di ogni
  partita ha `tuv_perso` per alleanza (somma su TUTTE le battaglie, `Riepilogo.lostTuv`). Nel
  `riepilogo-generale.md` la sezione **"Confronto contro Hard"** (`ConfrontoHard`, scritta solo se ci sono "Hard /
  Hard" e almeno una configurazione con un solo lato non Hard): tabella per configurazione (VC R4/R8/R12 con min-max,
  vittorie col round, PU e reddito finali, TUV perso, capitali perse = eventi "caduta" delle capitali dell'alleanza,
  sbarchi veri), tabella del lato che cambia (valori del lato con min-max e ds sui semi, Δ dalla media dello stesso lato
  in Hard vs Hard, rapporto TUV = perso dal nemico / perso dal lato) e "Risposta" per lato e tipo di AI (piani
  insieme). Verdetto sulle VC finali del lato: "piu' forte"/"piu' debole" se |Δ| > max(0,5, ds media dei due gruppi),
  altrimenti "non distinguibile" (indice semplice, non un test statistico).
- **Modalita' "ablazione" (step ABLAZIONE)**: `[ablazione]` nel messaggio del commit di testa (ha la precedenza su
  `[confronto]`) ⇒ input `modalita: ablazione`. Solo regole (nessuna chiave LLM passata, nessun costo API), 12 round,
  Equilibrato, **semi 1-6 per ogni configurazione**, 10 configurazioni = 60 partite: 1 `Asse-ProPlus-A-vs-Hard`, 2
  `Asse-A-senza-rallentamento`, 3 `Asse-A-senza-rischio-fronte`, 3b `Asse-A-senza-flotta`, 4 `Asse-ProPlus-nudo-vs-Hard`
  (piano A impostato, ignorato), 5 `Hard-vs-Alleati-ProPlus-1`, 6a `Alleati-1-senza-trasporti-sbarco`, 6b
  `Alleati-1-senza-soste-sicure`, 7 `Hard-vs-Alleati-ProPlus-nudo`, 8 `Hard-vs-Hard`. Opzione del runner `--ablazione
  Axis=c1+c2,Allies=nudo`; `riepilogo.json` `parametri.ablazione`; etichetta della configurazione con " [senza c1, c2]" o
  " [nudo]" (non si mescola con "Pro+ attuale"). Nel `riepilogo-generale.md`, dopo "Confronto contro Hard", la sezione
  **"Ablazione dei componenti Pro+"** (`ConfrontoAblazione`): rumore di Hard vs Hard, per configurazione VC R12 del lato
  (min-max, ds), Δ da Hard vs Hard e da "Pro+ attuale" (stesso piano) con "rumore" se |Δ| <= max(0,5, ds media), TUV
  perso per alleanza, reddito finale, sbarchi veri, round in RALLENTAMENTO; poi i componenti dal piu' dannoso al piu'
  utile ("costa / aiuta / rumore"); avviso ⚠ se "Pro+ nudo" e' distinguibile da Hard. Niente test di qualita'.
- **Modalita' "conferma" (step CONFERMA)**: `[conferma]` nel messaggio del commit di testa (precedenza su `[ablazione]` e
  `[confronto]`) ⇒ input `modalita: conferma`. Solo regole, 12 round, Equilibrato, **semi 101-112** (mai usati prima), 4
  configurazioni = 48 partite: a `Hard-vs-Hard`, b `Asse-ProPlus-nudo-vs-Hard`, c `Asse-ProPlus-A-vs-Hard`, d
  `Asse-A-senza-rischio-fronte`. Nel `riepilogo-generale.md` la sezione **"Statistica del confronto contro Hard"**
  (`ConfrontoConferma`, scritta quando c'e' "Hard / Hard" e almeno una configurazione con un solo lato non Hard): per
  configurazione VC R12, reddito finale e TUV perso per alleanza come media ± ds [errore standard]; differenze del lato
  che cambia (VC R12, reddito) da Hard vs Hard e, per le configurazioni con componenti spenti, da "Pro+ attuale", con
  intervallo al 95% del **t di Welch** (gradi di liberta' di Welch-Satterthwaite, quantile con l'espansione di
  Cornish-Fisher, tabella sotto 3 gradi) ed esito "esclude / include lo 0", piu' i semi per configurazione per un test
  al 5% con potenza 80% a quella differenza. Pooling di run diverse: comando `confronto` su una cartella con le
  sottocartelle (anche collegamenti) di tutte le partite.
- **Modalita' "varianza" (step VARIANZA)**: `[varianza]` nel messaggio del commit di testa (precedenza su tutte le altre)
  ⇒ `modalita: varianza`. Solo regole, 12 round, Equilibrato, 30 partite nella stessa run: Hard vs Hard e Asse Pro+
  nudo sui semi 1-6 e 101-106 + replica di nudo sui semi 1-6; id `<n>-<config>-r<replica>-seme<seme>`, matrice
  **mescolata per seme** (le configurazioni girano affiancate nel tempo, non a blocchi). Ogni `riepilogo.json` ha
  `tempi` (`Tempi`: per nazione turni, secondi medi/max/totali per turno = somma dei passi della nazione nel round,
  passo piu' lento, `ambiente` con CPU, processori, memoria, java, runner, carico). Sezione **"Varianza tra run"**
  (`ConfrontoVarianza`, scritta solo con id `-r<n>-seme<s>`): per configurazione x gruppo di semi (1-99 / 100+) x
  replica VC R12 del lato con IC 95% (t di Student), durata della partita e del turno del lato; differenze (gruppo di
  semi, replica, contro Hard vs Hard) con IC di Welch; correlazione tra repliche sullo stesso seme; correlazione durata
  / VC (Fisher); VC per macchina.
- **Modalita' "prova" (step PROVA DI VALORE)**: `[prova]` nel messaggio del commit di testa (precedenza su tutte le altre) ⇒
  `modalita: prova`. Solo regole, 12 round, Equilibrato, **semi 201-235** (mai usati prima), 4 configurazioni = 140 partite,
  matrice **mescolata per seme**, `max-parallel` 20 (solo in questa modalita'): 1 `Asse-nudo-vs-Hard`, 2 `Asse-nudo-Egitto-vs-Hard`
  (`--operazione Axis=egitto`), 3 `Hard-vs-Alleati-nudo`, 4 `Hard-vs-Alleati-nudo-Francia` (`--operazione Allies=francia`).
  Opzione del runner `--operazione Axis=egitto,Allies=francia`; `riepilogo.json`: `parametri.operazione`, `operazioni_congiunte`
  (stato salvato di ogni operazione con gli eventi), `controllo_nudo` (conteggi per nazione e round), per round
  `bersagli_operazioni` (proprietario del bersaglio di ogni operazione). Etichetta della configurazione con " + operazione <id>".
  Sezione **"Prova di valore: operazioni congiunte"** (`ConfrontoProva`, scritta solo se una partita ha un'operazione): per
  configurazione VC R12 del lato (media ± ds [es], IC 95%), reddito finale, TUV perso, contatore nudo; effetto dell'operazione
  (operazione − nudo, stesso lato) con IC di Welch, semi per l'80% di potenza e verdetto (vale = Δ ≥ 1 e IC che esclude 0;
  promettente = Δ > 0 e IC che arriva a +1; non vale = altrimenti); andamento (avviate, completate, annullate, rinvii, attese,
  tappa 2 approvata/attacco fatto, TUV perso per tappa, bersaglio preso e tenuto dopo 1 e 2 round, round obiettivo iniziale) con
  i motivi; bersaglio dell'alleanza a fine partita con e senza operazione; elenco dei round con differenze del contatore.
- **Modalita' "prova2" (step PROVA2)**: `[prova2]` nel messaggio del commit di testa (precedenza su `[prova]` e tutte le altre) ⇒ `modalita:
  prova2`. Stesse 4 configurazioni di `[prova]` (le operazioni sono ora la variante v2), **semi 301-335** (mai usati), 140 partite, matrice
  mescolata per seme, `max-parallel` 20, solo regole. `riepilogo.json`: per round `territori_operazioni` (proprietario di ogni bersaglio e
  bersaglio finale: Egypt, Normandy Bordeaux, France). Dopo "Prova di valore" la sezione **"Presa e tenuta"** (`ConfrontoTenuta`, scritta
  se un'operazione ha la tenuta): per operazione i bersagli dell'alleanza per round (Egitto R3/R6/R9/R12, Normandy e France R8/R10/R12) con e
  senza operazione e Δ a fine partita con IC 95% (differenza di proporzioni, approssimazione normale); esiti (tenuta iniziata, gia' presa
  senza operazione, COMPLETATA, FALLITA persa con round tenuti, FALLITA senza presa, ancora in tenuta, ANNULLATA, rinvii e annullamenti per
  contrattacco atteso, presidi lasciati a Hard; Francia: testa di ponte tenuta, tappe su Parigi valutate/eseguite/approvate, Parigi presa e
  tenuta a fine partita); TUV perso per alleanza con Δ e IC di Welch; **calibrazione** per fasce di 20%: tenuta stimata al lancio (bersaglio
  preso nello stesso round; reale = ancora dell'alleanza dopo `tenuta.round` round) e a ogni `stima_tenuta` (reale = ancora dell'alleanza a
  fine del round dopo).
- **Modalita' "provallm" (step PROVALLM)**: `[provallm]` nel messaggio del commit di testa (precedenza su tutte le altre) ⇒ `modalita:
  provallm`. 12 round, Equilibrato, **semi 401-435** (mai usati), matrice mescolata per seme, `max-parallel` 20: 1 `Asse-nudo-vs-Hard`, 2
  `Asse-nudo-Egitto-LLM-vs-Hard` (`--operazione Axis=egitto --operazione-decisore Axis=llm:claude-sonnet-5-5`), 3 `Hard-vs-Alleati-nudo`, 4
  `Hard-vs-Alleati-nudo-Francia-LLM`; 2 e 4 solo col secret (senza: 70 partite, nessun errore). **Tetto rigido della run 40 USD** diviso tra
  le partite LLM (`--budget-operazioni` = 40 / 70 = 0,5714 per partita): la somma non puo' superarlo. La chiave va solo ai job con
  `matrix.opllm` o `matrix.decisore`. `riepilogo.json`: `parametri.operazione_decisore`, `budget_operazioni_usd`, `operazioni_llm_uso`; le
  decisioni sono in `operazioni_congiunte.<id>.decisioni`. Etichetta "... + operazione egitto decisa da LLM". Dopo "Presa e tenuta" la
  sezione **"Decisioni LLM delle operazioni congiunte"** (`ConfrontoLlm`): decisioni per tipo e azione, diverse dalla formula, ripieghi con i
  motivi, calibrazione LLM contro formula sulle stesse decisioni (fasce 20%, errore medio pesato, Brier), costo totale/per partita.
- **Modalita' "smoke" (step STRATEGA LLM)**: `[smoke]` nel messaggio del commit di testa (precedenza su tutte le altre) ⇒ `modalita:
  smoke`. Controllo, non misura di forza: 12 round, Equilibrato, **semi 1001-1006** (step C2-bis, `--tetto-strategia 70`; 901-906 C2, prima 801-806,
  701-706, 601-606, 501-506), 2 configurazioni = 12 partite (`1-Asse-Stratega-LLM-
  vs-Hard`, `2-Hard-vs-Alleati-Stratega-LLM`), matrice mescolata, `max-parallel` 6, tetto per partita min(2, 30/12) = 2 $ (run <= 24 $,
  tetto 30 $). La chiave va ai job con `matrix.stratega`; senza secret le partite girano lo stesso (paracadute = Hard). Sezione
  "Stratega LLM: controllo [smoke]" (`ConfrontoSmoke`): stato, round decisi/senza LLM, chiamate, costo, ripieghi per livello e motivo,
  operazioni per tipo (avviate, eseguite, completate, fallite, annullate, in corso), rifiutate per motivo, calibrazione per fasce
  (livello strategico: stima a inizio round contro bersaglio dell'alleanza a fine durata; ordini: `prob_llm` contro `esito_reale`), VC
  finali solo informative. Dallo step COERENZA STIME: colonna "Eseguite dal motore" (offensiva/sbarco: tappa 1 mossa dal
  motore o tappa 2 approvata con l'attacco forzato; tenuta/rinforzo: presidio tenuto o rinforzato dal motore) e colonna separata "Prese da Hard (senza
  operazione)"; sottosezione "Coerenza delle stime": cause degli annullamenti, artefatti C1-C5 / avviate, offensive+sbarchi
  eseguiti dal motore / avviati, mediana |stima del candidato - prima stima al punto di decisione| (punti di successo e di
  tenuta), blocchi del motore (tetto di perdita, sicurezza raduni, quota PU, candidati scartati, riavvii rifiutati);
  calibrazione per tipo (stima dell'LLM) e della stima unica del motore al lancio per tipo.
- **Modalita' "diagnosi" (step R0)**: `[diagnosi]` nel messaggio del commit di testa (precedenza su tutte le altre) ⇒ `modalita:
  diagnosi`. 12 round, Equilibrato, **semi 1101-1110** (mai usati prima), matrice mescolata per seme, `max-parallel` 20, 5
  configurazioni = 50 partite: a `Hard-vs-Hard`, b `Asse-Stratega-senza-chiave-vs-Hard` (nessuna chiave al job: paracadute, nessuna
  operazione, nessuna lista), c `Asse-Stratega-LLM-vs-Hard`, d `Asse-Stratega-senza-liste-vs-Hard` (`--stratega-spento Axis=liste`), e
  `Asse-Stratega-senza-tenute-vs-Hard` (`--stratega-spento Axis=tenute`). La chiave va solo a c, d, e (`matrix.stratega`); tetto della
  run 15 $ diviso sulle 30 partite LLM (0,5 $ a partita). `riepilogo.json` `parametri.chiave_llm` (solo vero/falso, mai la chiave) e
  `parametri.stratega_spento`; etichetta della configurazione "Stratega LLM [senza chiave] / [senza liste] / [senza tenute]". Sezione
  **"Diagnosi Stratega (R0)"** (`ConfrontoDiagnosi`, scritta con "Hard / Hard" e almeno una configurazione Stratega contro Hard): per
  configurazione VC R12 del lato (media ± ds, min-max), reddito finale, TUV perso, capitali cadute; differenze di ogni configurazione
  Stratega da Hard vs Hard e di "[senza liste]"/"[senza tenute]" dallo Stratega completo, con IC 95% di Welch (`ConfrontoConferma.Welch`)
  ed esito "esclude / include lo 0"; per "[senza chiave]" il contatore di `ControlloTuning` (domande, differenze, risposte "operazione").
- **Modalita' "correzioni" (step R1)**: `[correzioni]` nel messaggio del commit di testa (precedenza su tutte le altre) ⇒
  `modalita: correzioni`. Stessi semi di R0 (1101-1110), 12 round, Equilibrato, mescolata, `max-parallel` 20, 4 configurazioni = 40
  partite: a `Hard-vs-Hard`, c `Asse-Stratega-LLM-vs-Hard`, f `Hard-vs-Alleati-Stratega-LLM`, g `Asse-Stratega-regole-vs-Hard`
  (linea di base a regole `--stratega-regole si`, nessuna chiave, costo 0). Chiave solo a c e f, tetto 15 $ sulle 20 partite LLM (0,75 $
  a partita). Etichetta "Stratega LLM [regole]"; la sezione "Diagnosi Stratega (R0)" confronta anche g con c e mostra i contatori
  dello Stratega (round decisi/senza LLM, tentativi falliti, menu vuoto, unita' contate non mosse, piani di Hard ricalcolati).
- **Modalita' "ombra" (step R2a)**: `[ombra]` nel messaggio del commit di testa (precedenza su tutte le altre) ⇒ `modalita: ombra`.
  Semi 1101-1110 (come R0/R1), 12 round, Equilibrato, mescolata, `max-parallel` 20, 3 configurazioni = 30 partite: a `Hard-vs-Hard`,
  c `Asse-Stratega-LLM-vs-Hard`, f `Hard-vs-Alleati-Stratega-LLM` (tenute spente = il nuovo default, ombra accesa). Chiave solo a c e f,
  tetto 15 $ sulle 20 partite LLM. Sezione **"Campagne in ombra"** (`ConfrontoOmbra`, da `stratega_llm.<alleanza>.ombra`).
  Opzione nuova del runner `--stratega-acceso Axis=tenute` (riaccende un interruttore spento di default; etichetta "[con tenute]").
  **Dallo step R2a-bis** `[ombra]` = 2 configurazioni SENZA chiave (costo API 0), 20 partite: b1 `Asse-Stratega-senza-chiave-vs-Hard`,
  b2 `Hard-vs-Alleati-Stratega-senza-chiave` (lo Stratega senza chiave gioca come Hard: l'ombra misura Hard).
- **Modalita' "impegno" (step R2b)**: `[impegno]` nel messaggio del commit di testa (precedenza su tutte le altre) ⇒ `modalita:
  impegno`. Semi 1101-1110, 12 round, Equilibrato, mescolata, `max-parallel` 20, 3 configurazioni = 30 partite SENZA chiave (costo API
  0): a `Hard-vs-Hard`, c1 `Asse-Stratega-impegno-vs-Hard` (`--stratega-acceso Axis=impegno`), c2 `Hard-vs-Alleati-Stratega-impegno`
  (`--stratega-acceso Allies=impegno`). Campo nuovo della matrice `acceso` ⇒ `--stratega-acceso` (prima il workflow non lo passava).
  Etichetta "Stratega LLM [senza chiave] [con impegno]". Sezione **"Impegno (step R2b)"** (`ConfrontoImpegno`, da
  `stratega_llm.<alleanza>.impegno`).
  **Dallo step R2c** semi 1101-1120 (60 partite, sempre senza chiave) e sezione "Impegno (step R2b, R2c)" con la sottosezione "Step R2c".
  **Dallo step R3a** semi 1121-1140 (60 partite, senza chiave), sezione "Impegno (step R2b, R2c, R3a)" con la sottosezione "R3a: sbarco
  coordinato e teatri" (`ConfrontoR3a`, dal registro `impegno_decisioni` e dai campi `regioni`/`nord_africa_asse` di `Monitor`).
- **Modalita' "completa" (step PARTITA COMPLETA)**: `[completa]` nel messaggio del commit di testa (precedenza su tutte le altre) ⇒
  `modalita: completa`. Hard vs Hard **fino alla vittoria**, semi 1101-1103 (3 partite), senza chiave (costo API 0), tetti di sicurezza
  `--round 60` e `--minuti 120`, job `play` con `timeout-minutes` 135 (solo in questa modalita'; 30 nelle altre; limite dei runner
  ospitati 6 h per job). Opzione del runner `--fino-a-vittoria si`: a ogni fine round l'autoplay valuta le condizioni dei **trigger di
  vittoria della mappa** (`Vittoria`, con il bridge fittizio del pannello obiettivi) e ferma la partita quando restano vere per un round
  intero (vere a fine round N e N+1 ⇒ vittoria al round N+1, `Vittoria.ROUNDS_HELD` = 1). **Il motore da solo non chiude mai questa
  mappa**: `TriggerAttachment.triggerVictory` usa il testo `victory` del trigger come chiave di `notifications.properties` e la chiave
  non c'e' (il file della mappa ha solo 3 messaggi di guerra; l'autoplay inoltre non carica le risorse della mappa) ⇒ `signalGameOver`
  non viene mai chiamato (vale anche nel client di Matteo). Al tetto senza vincitore: stato "stallo: tetto di N round/minuti senza
  vincitore", `esito.stallo` = true, uscita 0 (non errore). `riepilogo.json`: `parametri.fino_a_vittoria`, `vittoria` (round in cui la
  condizione era vera per alleanza, vincitore, round), `esito.round_vittoria`; `tempi.per_round_s` (secondi di tutti i passi del round,
  scritto in tutte le modalita'). Sezione **"Partite complete (fino alla vittoria)"** (`ConfrontoCompleta`, solo con
  `fino_a_vittoria`): per partita esito, round della vittoria, round giocati, durata, s/round per fasce R1-R12, R13-R24, R25-R36, R37+;
  per partita VC e reddito / TUV per nazione ogni 4 round e capitali cadute/liberate col round; stima della CI per 60 partite complete
  (20 in parallelo). Senza `--fino-a-vittoria` il runner si comporta come prima.
  **Dallo step R4** `[completa]` = 5 configurazioni x 40 semi 1141-1180 = 200 partite senza chiave (costo 0), mescolate per seme,
  `max-parallel` 20: a `Hard-vs-Hard`, c1 `Asse-impegno-vs-Hard` (`Axis=impegno`), c1n `Asse-impegno-assedio-vs-Hard`
  (`Axis=impegno+assedio`), c2 `Hard-vs-Alleati-R3a` (`Allies=impegno`), c2n `Hard-vs-Alleati-R3a-R4` (`Allies=impegno+alleati_r4`);
  `publish` timeout 45 min, riepilogo nel sommario della run troncato a 900 KB (completo nello zip). Tabelle per configurazione in
  "Partite complete" (`ConfrontoR4`).
  **Dallo step R5** `[completa]` = 4 configurazioni x 40 semi 1181-1220 = 160 partite senza chiave: a `Hard-vs-Hard`, e1
  `Hard-vs-Alleati-R5` (`Allies=impegno+alleati_r5`), e2 `Asse-impegno-assedio-vs-Hard` (`Axis=impegno+assedio`), e3
  `Asse-assedio-vs-Alleati-R5` (entrambe le alleanze Stratega senza chiave); sottosezione "R5" (`ConfrontoR5`).
  **Dallo step R6** `[completa]` = 5 configurazioni x 40 semi 1221-1260 = 200 partite senza chiave: a `Hard-vs-Hard`, f1
  `Hard-vs-Alleati-squadra` (`Allies=impegno+squadra_gibilterra`), e2 `Asse-impegno-assedio-vs-Hard` (quota 70), f3
  `Asse-assedio-vs-Alleati-squadra`, g `Asse-assedio-quota80-vs-Hard` (campo `quota` della matrice ⇒ `--quota-operazione Axis=80`);
  sottosezione "R6" (`ConfrontoR6`).
- Download: `https://github.com/matteoposterli-netizen/triplea/releases/download/autoplay-latest/autoplay.zip`.

## Regole di consumo della CI (decise da Matteo, 2026-11-08)

Dopo che le Actions del fork si erano fermate (step 3d-1):
- `proplus-release.yml`: `paths-ignore: '**.md'` ⇒ un push di sola documentazione non lancia nulla;
  `concurrency` del workflow `proplus-release-<ref>` con `cancel-in-progress: true` ⇒ una run nuova
  annulla quella vecchia dello stesso branch (release e autoplay compresi); job `changes` (ubuntu)
  ⇒ `powershell-tests` (Windows, minuti x2) solo se il push cambia qualcosa sotto `proplus/` (o run
  manuale/branch nuovo; dallo step INTEGRAZIONE LLM solo `proplus/ci/` e `proplus/dist/`, non `proplus/strategia/`),
  `build` parte anche se è saltato; job `autoplay` solo con `[autoplay]` nel
  messaggio del commit di testa (`github.event.head_commit.message`) o run manuale. Le condizioni di
  `build` e `autoplay` controllano `needs.<job>.result` con `!cancelled()`: il `success()` implicito è
  falso se un job a monte è stato saltato (run 42466240207: build verde, autoplay saltato).
- Autoplay: timeout 30 minuti per partita, `--minuti 25` (le partite da 12 round durano 3-8 min).
- Prima dei test (release e `prepare` dell'autoplay) il passo "Download the Gradle distribution"
  ripete `./gradlew --version` fino a 5 volte: il wrapper ha un solo tentativo con timeout 10 s e la
  cache di Gradle è in sola lettura fuori dal branch di default (si scarica a ogni run). Run 42465326439
  fallita 2 volte così e 42465652478 5 volte su 5 (services.gradle.org: timeout e HTTP 504 dai runner,
  da fuori rispondeva) ⇒ lo stesso passo, solo nella run, riscrive in `gradle-wrapper.properties`
  `distributionUrl` verso `github.com/gradle/gradle-distributions/releases/download/v<ver>/` (dove
  services.gradle.org reindirizza, stesso zip) e `networkTimeout` a 60 s. Il file nel repo resta
  quello dell'upstream.
- Attenzione (step INTEGRAZIONE LLM): il prompt dello stratega e' un `.md` dentro le risorse di game-core
  (`prompt-stratega-v1.md`): per `paths-ignore: '**.md'` un commit che cambia SOLO il prompt non lancia la build ⇒
  cambiarlo insieme a codice o a `prezzi-stratega.json` (il dispatch manuale non funziona fuori da `main`).
- Commit raggruppati: 1-2 push per step, l'ultimo con `[autoplay]` nel messaggio; CLAUDE.md dentro
  l'ultimo commit, **niente push di sola documentazione**.
- La run #38 (42428388452, 4a6660e) è rimasta "queued" senza job e non si può annullare: ignorarla.

## Release Pro+ (GitHub Action)

`.github/workflows/proplus-release.yml` — su push a `proplus` (o manuale), con le regole di consumo sopra:
0. job `powershell-tests` (runner **windows-latest**, gate del job `build`, solo se cambia `proplus/`): esegue
   `proplus/ci/test-aggiorna.ps1`, che testa la logica di `aggiorna.ps1` con un server HTTP
   locale (`System.Net.HttpListener`) che simula la GitHub API + download (nessuna rete reale);
   `Start-MockServer` attende il server con ping ogni 250 ms fino a 90 s e riavvia il job (max 3)
   se termina prima di rispondere (step AUTOPLAY: una volta era fallito "Mock server non pronto");
1. test: `:game-core:test :ai:test :game-headed:test :proplus-autoplay:test` (smoke-testing escluso: scarica salvataggi
   esterni ed è lento; `./verify` escluso: include PMD/spotless/check custom non necessari qui);
2. scrive la versione (`set-game-headed-build-number`), `:game-headed:shadowJar`;
3. zip `TripleA-ProPlus.zip` = `TripleA-ProPlus.jar` + `avvia.bat` + `aggiorna.ps1` +
   `build-info.txt` + `LEGGIMI.txt` + `LEGGIMI-TEST.txt` (step STRATEGA LLM; `aggiorna.ps1` NON lo copia
   nell'aggiornamento automatico: arriva con il download manuale dello zip, `LEGGIMI.txt` lo cita) +
   `.triplea-root` (marcatore vuoto cercato da `ClientFileSystemHelper.getRootFolder()`);
4. sposta il tag `proplus-latest` sul commit e sostituisce l'asset nella release (prerelease);
5. job `autoplay` (dopo la release, workflow riutilizzabile, solo con `[autoplay]`): partite AI vs AI,
   vedi "Autoplay in CI".

Sorgenti dei file dello zip: `proplus/dist/{avvia.bat,aggiorna.ps1,LEGGIMI.txt,build-info.txt}`.
Placeholder `@VERSION@ @COMMIT@ @DATE@ @REPO@` sostituiti in CI in `LEGGIMI.txt` e
`build-info.txt`. CRLF applicato a `avvia.bat`, `aggiorna.ps1`, `LEGGIMI.txt`; `build-info.txt`
resta **LF** (letto da `avvia.bat` con `for /f`, che altrimenti lascerebbe un CR nei valori).

### Aggiornamento automatico (step 0bis)

- `avvia.bat` è un lanciatore sottile: a ogni avvio chiama `aggiorna.ps1` (Windows PowerShell
  5.1, nessun tool esterno), poi mostra la versione in uso (commit + data da `build-info.txt`)
  e avvia il jar con `-Xss1250K`.
- `aggiorna.ps1` interroga in **anonimo** `GET /repos/<repo>/releases/tags/proplus-latest`,
  confronta `asset.updated_at`/`id` con `version.txt` (stato locale, non nello zip). Primo avvio
  senza `version.txt`: confronto per commit (regex sul body della release vs `build-info.txt`).
  Se serve: scarica in `%TEMP%`, verifica (dimensione + zip valido + presenza jar), sostituisce
  **solo** i file noti (jar: copia in `.tmp`, poi `[IO.File]::Copy(tmp, jar, $true)` sopra il jar
  esistente + controllo dimensione, `.tmp` rimosso), riscrive `version.txt`.
  Qualsiasi errore di rete/API/zip ⇒ avviso + `exit 0` (fallback alla build locale).
- Self-update del lanciatore: `aggiorna.ps1` non sovrascrive a caldo `avvia.bat`/`aggiorna.ps1`
  (in uso); se cambiano li mette in `*.new` ed esce con **10**. `avvia.bat` genera uno script
  temporaneo in `%TEMP%` che attende la chiusura, applica i `*.new` e rilancia `avvia.bat`.
- Prima adozione: la build step-0 non ha `aggiorna.ps1`, quindi il primo passaggio richiede UN
  download manuale dello zip aggiornato; da lì in avanti è automatico.
- Invocazione: `avvia.bat` chiama `powershell.exe -NoProfile -ExecutionPolicy Bypass -File
  "%HERE%aggiorna.ps1" -InstallDir "%HERE%."` (il `.` evita che il `\` finale di `%HERE%` rompa la
  virgoletta). `aggiorna.ps1` ha comunque il fallback `Split-Path -Parent
  $MyInvocation.MyCommand.Path` e un `trap` di livello superiore: ogni errore imprevisto ⇒ avviso
  giallo + `exit 0`, mai un errore rosso.
- Hook solo-test (mai impostati per l'utente): `PROPLUS_TEST_NOLAUNCH=1` (avvia.bat salta pause,
  riavvio del lanciatore e Java), `PROPLUS_TEST_APIBASE` (aggiorna.ps1 usa quel server se `-ApiBase`
  non è passato).
- **Lezione 1 (bug 0bis-fix): testare sempre l'invocazione reale** (stesso eseguibile, stessi
  argomenti, working directory diversa, percorsi con spazi), non solo la logica con parametri
  comodi. Scenari 7-9 di `proplus/ci/test-aggiorna.ps1`.
- **Lezione 2 (bug 0bis-fix2): mai sostituire un file esistente per rinomina** (`Move-Item -Force`,
  `Rename-Item`, `move /y`) in Windows PowerShell 5.1 / cmd: se un altro processo (antivirus,
  indicizzatore) tiene aperto il file con condivisione Delete, la cancellazione resta "in sospeso",
  il nome resta occupato e la rinomina fallisce con "Cannot create a file when that file already
  exists". Usare copia con sovrascrittura (`[IO.File]::Copy(src, dst, $true)`, `copy /y`) + rimozione
  della sorgente. Non usare `[IO.File]::Replace(src, dst, $null)`: PowerShell converte `$null` in
  `""` ⇒ "The path is not of a legal form". Sul runner NTFS il problema NON si riproduce (la
  cancellazione libera subito il nome): serve un volume FAT32 (scenario 11) o equivalente.

Fatti verificati per avvia.bat / LEGGIMI:
- Installer ufficiale = install4j (`game-headed/build.install4j`), JRE Adoptium 25 incluso nella
  cartella `jre` dell'installazione (convenzione install4j), cartella default
  `C:\Program Files\TripleA`; unica opzione JVM aggiunta: `-Xss1250K` (nessun `-Xmx`).
- Dati utente condivisi con l'installazione ufficiale: `%USERPROFILE%\Documents\triplea` (o
  `%USERPROFILE%\triplea`) con `downloadedMaps`, `savedGames`, `savedGames\autoSave`;
  impostazioni in Java Preferences (registro HKCU\Software\JavaSoft\Prefs\games\strategy\...).

## Diario delle decisioni (dallo step 2bis)

Cartella `diario` accanto al jar (`ClientFileSystemHelper.getRootFolder()` = cartella con
`.triplea-root`, cioè `C:\...\TripleA-ProPlus\diario\`), una sottocartella per partita
`AAAA-MM-GG_hh-mm-ss_<nome mappa>` (anche caricare un salvataggio apre una cartella nuova). Tenute
le ultime 20 (`DiarySettings.MAX_GAMES`); si cancellano solo cartelle con quel formato di nome.
`aggiorna.ps1` tocca solo i file noti (jar, LEGGIMI, build-info, .triplea-root, *.new) e la sua
cartella temporanea in `%TEMP%`: la cartella `diario` non viene mai toccata (verificato sul codice,
nessuna modifica necessaria). On/off: casella in "Impostazioni Pro+" (Java Preferences, default on);
pulsante "Apri cartella diario" (`Desktop.open`). Sistema `proplus.diario.dir` = cartella alternativa
(test, benchmark). Bot headless: niente diario (salvo proprietà impostata).

Ciclo di un turno (perché i dati arrivano in quest'ordine): nella fase **acquisti** la Pro AI simula il
turno su una copia della partita: pianifica gli attacchi (`doCombatMove` in simulazione: è QUESTO il
piano poi eseguito) e poi compra. Nella fase **combattimento** il piano viene eseguito
(`doMove(storedCombatMoveMap)`); se non c'era un piano salvato, viene fatto lì. A fine
combattimento il diario guarda il tabellone reale (battaglie pendenti nel `BattleTracker`, unità
presenti) per sapere cosa è stato davvero eseguito; a inizio **movimento** (dopo le battaglie)
registra l'esito reale. Piani fatti in altre simulazioni (bid di Hard Beta, secondi piani nella
stessa fase acquisti) sono ignorati.

`diario.txt` (UTF-8, CRLF), per turno e nazione:
`Round N - <Nazione> (<alleanza>, <tipo AI>, rischio <livello>: soglie D/M) - <PU>` poi:
- Acquisti: totale, PU spesi, e per passo di `ProPurchaseAi` le unità aggiunte (motivo = passo:
  difesa territori minacciati, unità di terra per i territori prioritari, flotta, PU avanzati...;
  "upgrade" mostra anche le unità tolte con segno -).
- Attacchi eseguiti: win% stimata con le unità assegnate, soglia desiderata, win% massima, priorità
  (= `ProTerritory.getValue()`, il punteggio d'ordinamento) con la sua **quota VC** (step 2c; 0 per
  Hard), valore strategico
  (`findTerritoryAttackValue`: 3·produzione·(fabbrica+1) + 3·VC, meno costo per i neutrali) con la
  quota VC, attaccanti e difensori (senza infrastrutture).
- Attacchi pianificati ma non eseguiti (mossa rifiutata dal motore).
- Bombardamenti strategici.
- Attacchi scartati con motivo: `below_minimum` (win% max < soglia minima), `negative_value`,
  `defensive_stance`, `convoy_cant_hold`, `neutral_cant_hold`, `amphib_low_value`,
  `neutral_exposes_attackers`, `no_transports_left`, `not_enough_units`, `capital_defense`,
  `stage_*` (scartato senza motivo puntuale in quello stadio), `final_checks`.
  Dallo step 2d `units_not_worth` è diviso in `units_win_rarely`, `units_no_land_unit`,
  `units_neutral_cant_hold`, `units_negative_value` (con la formula e i numeri: TUV battaglia,
  valore territorio × win%, 2/3 × contrattacco, win% del contrattacco, TUV atteso, e per Pro+ perché
  le sue regole non lo tengono); dalle verifiche finali si distinguono `transports_exposed` (con i
  numeri), `transports_scrambled`, `landing_unfought_zone`, `landing_blocked_by_subs`. Attacchi
  tenuti da Pro+ che Hard scarterebbe: "tenuto da Pro+ ..." nella riga dell'attacco eseguito.
  JSON: `controllo_finale` (e `pro_plus`), `tolto_da`. Testi in `diario/Italian.REASONS`.
- Obiettivi principali (top 5 per priorità, quota VC, stato) e confronto top 10 con il turno
  precedente della stessa nazione (entrati/usciti con priorità prima/ora o motivo).
- NEUTRALI: neutrali tra i candidati (attaccati/scartati e perché) + neutrali con unità confinanti con
  terre della nazione mai considerati. "Neutrale" = `ProUtils.isNeutralLand` oppure proprietario
  fuori dalle alleanze principali (Europe 1940: Neutral_True/Axis/Allies).
- Esito reale: stimata → VITTORIA/SCONFITTA/RITIRATA/NON RIUSCITO, attaccanti e difensori prima/dopo,
  TUV swing stimato vs reale, totale "vinte X, attese Y (somma win%)".
- Tempo AI per fase e "di cui diario" (costo del diario misurato nel diario stesso).

Campo JSON `priorita_vc` (eventi `attacco` e `obiettivi.top`) = quota VC della priorità.

Dallo step 3a (Pro+ con piano): dopo l'intestazione del turno, "Stato maggiore <alleanza>: piano X
(fissato al round N | non ancora fissato), fase Rx", riga "Fronti -> rischio effettivo" (modificatore,
livello, soglie), obiettivi del piano per la nazione con peso, ruolo. Per ogni attacco tag
"[fronte F, rischio L, OBIETTIVO DEL PIANO]" e "quota piano" nella priorità; regola "obiettivo del
piano" al posto di "VC/obiettivo" nei testi. JSON: `inizio_turno.piano` {alleanza, id, nome,
round_inizio, fase, fronti{modificatore, rischio_livello, rischio, soglie}, obiettivi};
`attacco.fronte`, `rischio_effettivo`, `obiettivo_piano`, `priorita_piano`, `soglia_desiderata`/
`soglia_minima` ora del bersaglio; `controllo_finale.pro_plus.regola_obiettivo` = `piano` |
`vc_obiettivo_nazionale`. Senza file: riga "Stato maggiore: nessun piano".

`decisioni.jsonl`: una riga JSON per evento, campi comuni `round`, `nazione` (nome XML), `ai`,
`fase` (purchase/combat/noncombat/place), `evento`: `inizio_partita`, `inizio_turno` (alleanza,
rischio, soglie, low_luck, pu), `acquisto` (passo, motivo, unita), `acquisti_totale`, `attacco`
(territorio, proprietario, neutrale, mare, stato eseguito/non_eseguito/scartato/candidato,
motivo_codice, win_stimata, win_max, soglie, priorita, valore{totale, produzione, victory_city,
neutrale}, tuv_swing_stimato, anfibio, logoramento, tenibile, unita_attaccanti, difensori),
`obiettivi` (top 10, entrati, usciti), `neutrali_non_considerati`, `esito_battaglia` (win_stimata,
esito, vinta, attaccanti/difensori prima/dopo, tuv_swing_stimato/reale), `fine_turno` (ms per fase,
ms_diario), `errore_diario`.

Debug con un salvataggio: Matteo manda la cartella della partita del diario + un `.tsvg` dello stesso
momento (salvataggio manuale, oppure `savedGames\autoSave\autosave_round_odd/even.tsvg` = inizio
dell'ultimo round dispari/pari, `autosaveBefore<Delegate>`/`autosaveAfter<Delegate>`). Il diario dice
round, nazione e piano; caricando il `.tsvg` (stesso round) si ricrea la situazione e con il log AI
(Debug > Show Logs) si ripete la scelta. Le win% sono simulazioni: rigiocando cambiano di qualche
punto (dadi della simulazione non fissati).

## Stato maggiore e piani (dallo step 3a)

Tre livelli: **stato maggiore** (uno per alleanza: piano, fasi, rischio per fronte, obiettivi, ruoli)
→ **nazione** (applica il piano nel suo turno) → **tattica** (ProAi con `ProPlusTuning`, che riceve
rischio effettivo e obiettivi per bersaglio).
- File: `proplus/strategia/indice.json` mappa il nome del gioco (`<info name>` dell'XML,
  `GameData.getGameName()`) al file della mappa. Mappa senza file ⇒ nessun piano, Pro+ = step 2d,
  avviso `[Pro+] Nessun file di strategia` nel log AI. Avvisi una volta per gioco: nomi del file che
  non sono territori della mappa, territori in nessun fronte (⇒ fronte "Altro", modificatore 0;
  Europe 1940: Americhe, India e dintorni, territori fittizi `GermanyWar*`).
- Schema per piano: `id`, `nome`, `descrizione`, `fasi` [`da_round`, `a_round` (assente = fino a
  fine partita), `fronti` {fronte: -1/0/+1, assente = 0}, `obiettivi` [{`territorio` | `flotta`
  (zone di mare con navi di quella nazione), `peso`, `nazioni` opzionale}]], `ruoli` {nazione:
  compito}, `tappe` e `firma` (step 3c, vedi "Avanzamento del piano e lettura del nemico"),
  `sperimentale` (step 3c: "(sperimentale)" nel pannello, mai scelto da "Automatico"; B Sea Lion),
  `punti_critici` [{`descrizione`, `tipo` (`territorio` | `unita` | `flotta` | `stack_avanzato`),
  `territori`, `zone`, `unita`, `classi`, `fronte`, `nazione`, `da_round`, `a_round`}] (step 3d-1:
  bersagli concreti per il nemico del piano), `subentri` [id] e `contrasta` [id nemici] (step
  3d-1), `contromosse` (default true; N false), `logistica` (step 3b, vedi "Logistica del piano").
  Usati: `fasi` (rischio, obiettivi), `logistica`, dallo step 3d-1 `tappe`/`firma` (lettura e stato
  che decidono postura e cambio), `punti_critici`, `subentri`, `contrasta`; `ruoli` appare nel
  diario. Radice del file: `stato_maggiore` (numeri dello step 3d-1, vedi sotto).
- Fronti Europe 1940: Ovest, Est, Mediterraneo-Africa, Mari (`zone_di_mare: true` = tutte le zone
  di mare), Altro. Piani: Asse A Barbarossa (Automatico), B Sea Lion, C Finta Sea Lion,
  D Sud/Mediterraneo, N Neutro (tutti 0, nessun obiettivo, nessuna logistica: per confronti); B
  dal R4 = fase R3+ di Barbarossa (step 3b); Alleati 1 Germania prima
  (Automatico; "Est −1 fino al R4 poi 0" interpretato come R1-3 −1, R4+ 0, obiettivi di sbarco dal
  R4), 2 Italia prima, 3 Pressione economica (tutti 0).
- Persistenza (solo `String` JDK ⇒ salvataggio apribile dalla TripleA ufficiale):
  `"Pro+ piano: <alleanza>"` = id scelto o `auto`; `"Pro+ stato maggiore: <alleanza>"` =
  `{"piano":"A","round_inizio":1,"storico":[]}`. Il piano si **fissa** (`StatoMaggiore.fix`) al
  primo turno Pro+ dell'alleanza (`ProPlusAi.logPhase`, sui dati reali): il pannello mostra la
  tendina bloccata "fissato al round N". Dallo step 3d-1 lo stato maggiore può **cambiarlo** a
  inizio round (`StatoMaggiore.cambia`): `piano` = nuovo piano, `cambi` [{round, da, a, motivo}]
  (assente finché non ce ne sono). `storico` = per round (avanzamento, lettura, dallo step 3d-1
  `piano` in vigore e `stato_strategico`). Fase = round di gioco assoluto (non relativo a
  `round_inizio` né al round del cambio).
- Rischio effettivo di un attacco = cursore dell'alleanza + modificatore della fase per il fronte
  del bersaglio, limitato a -1..+1 e a 1..5 (`RiskLevel.withModifier`). Usato per: soglie
  desiderata/minima per bersaglio (`ProTuning.targetAttackWinPercentage`/`targetAttackMinWin…`,
  letti da `ProData.getAttackWinPercentage(Territory)`), regola "rischio" e regola "obiettivo"
  dello step 2d (`ProPlusTuning.riskLevel(proData, territorio)`). I punti del combat move che
  cercano "l'attacco più sotto la soglia" partono da `ProData.getAttackWinPercentageCeiling()`
  (= la soglia del fronte più prudente) e confrontano ogni bersaglio con la sua soglia
  (`ProCombatMoveAi.belowAttackTarget`); per Hard identico (una sola soglia).
  `ProData.getAttackWinPercentage()` senza bersaglio resta il livello del cursore (diario/log).
- Obiettivi: bonus di priorità = peso dell'obiettivo (il massimo se più obiettivi coincidono)
  sommato come il bonus VC, prima dei moltiplicatori (capitale ×5, vicino alla mia capitale ×3);
  mai per neutrali; escluso dal controllo "postura difensiva" come la quota VC. La regola fissa
  "VC/obiettivo nazionale" dello step 2d è **sostituita** da "obiettivo del piano attivo" (anche
  zone di mare per gli obiettivi `flotta`), con i limiti TUV del livello effettivo del fronte.
  Senza file di strategia resta la regola dello step 2d.
- Peso VC di priorità dello step 2c: **invariato a 10** (vedi report step 3a).
- Soglia desiderata degli obiettivi (step 3b): per un bersaglio che è obiettivo del piano attivo
  (territorio o zona di mare `flotta`, mai neutrali) la desiderata = max(desiderata del livello
  del fronte, desiderata del livello base del cursore); la minima resta quella del fronte
  (`ProPlusTuning.desiredWinPercentage`, usata anche dalla regola "non conviene" dello step 2d).
  Il fronte decide SE tentare l'obiettivo, non con quante poche unità.
- Fasi e round: la fase è scelta sul round di gioco assoluto; "Est −1 fino al R4" = round 1-3
  (confermato da Matteo).

### Logistica del piano (step 3b)

Nel file di strategia: `categorie` {nome: [tipi di unità in ordine di rotazione]} e per piano
`logistica` {nazione: [{`da_round`, `a_round`, `acquisti`: {`mix` {categoria: %}, `obbligati`
[{`categoria`, `minimo`}]}, `piazzamento` [territori/zone], `ammassamento` {`fronte`,
`territori`, `forza`}}]}. Intervalli di round **propri** della logistica (non le `fasi` del
combattimento: es. Barbarossa acquisti R1-3/R4+, fasi R1-2/R3+). Mix > 100% o categoria ignota ⇒
file rifiutato; unità ignote ⇒ avviso nel log. Nazione/round senza logistica (Francia, piano N,
Italia in B) ⇒ acquisti e movimenti di Hard.
- **Acquisti** (`ProPurchaseAi.purchase`): dopo `defend_land` (difesa dei territori minacciati,
  capitale compresa) e il calcolo del valore strategico dei territori di piazzamento, se
  `tuning.purchaseOrders(proData)` c'è, `ProPlanPurchase` spende i PU restanti: prima gli
  obbligati, poi (base = PU restanti − obbligati fuori dal mix) una unità alla volta nella
  categoria più lontana dal suo obiettivo, se un'unità ci sta (costo ≤ 2 × quota mancante) ed è
  piazzabile; tipi della categoria a rotazione. Poi la pipeline di Hard continua con gli avanzi
  (AA, terra, difesa navale, fabbriche, mare, avanzi, upgrade — l'upgrade può sostituire unità
  del piano). Passo del diario `plan`.
- **Piazzamento** delle unità del piano: terra/aerei in territori di terra tenibili, navi prima in
  zone che il nemico non può attaccare con più TUV di quanto le difende (navi alleate + piazzate);
  poi territori di `piazzamento` in ordine (anche la fabbrica di una zona di mare), poi vicinanza
  ai territori di raccolta, poi valore strategico. Le unità di Hard (difesa ecc.) non cambiano.
- **Ammassamento** (`ProNonCombatMoveAi.doNonCombatMove`): dopo `findTerritoryValues`/
  `findSeaTerritoryValues`, `ProStaging.apply` aggiunge a ogni destinazione `forza × scala /
  (1 + distanza)` (distanza dalla raccolta più vicina via terra per i territori, via mare per le
  zone; max 8; scala = valore massimo delle destinazioni di quel tipo, min 1). I movimenti di
  difesa (`moveUnitsToDefendTerritories`) sono già fatti e non leggono questi valori; li leggono
  `moveUnitsToBestTerritories` (terra, mare, trasporti) e il ciclo "difesa della capitale" (×10
  vicino alla capitale se serve). Non guida gli aerei (logica "atterraggio sicuro" di Hard).
- Diario: dopo gli acquisti "Mix del piano (...)": per categoria obiettivo %/PU, comprato dal piano,
  reale nel turno (tutte le unità del turno, prima categoria del mix che le contiene) + "fuori dal
  mix", obbligati mancanti, piazzamento. Nel movimento "Ammassamento (...)": unità mosse,
  avvicinate/stessa distanza/allontanate, distanza media prima→dopo, destinazioni con fronte,
  distanza e bonus, ferme, "Perche'". JSON: `acquisti_piano`, `ammassamento`.
- Cache: `ProPlusTuning.plan` per (GameData, giocatore, round); il livello del cursore è letto a
  ogni uso. `ProPlusAi` svuota la cache dopo aver fissato il piano.
- Step 3b-bis: `quota_minima_piano` (campo della voce di logistica, % dei PU del turno riservata al
  piano) e `ammassamento.aerei` (true = anche gli aerei atterrano verso la raccolta). Categoria
  `scorta` = [carrier, destroyer] (portaerei se i PU bastano, altrimenti cacciatorpediniere).

### Guarnigione della capitale e quota del piano (step 3b-bis)

- `ProTuning.capitalGuardThreshold` (default vuoto = Hard; Pro+ `ProPlusTuning.CAPITAL_GUARD_THRESHOLD`
  = 25, sempre attiva, con o senza piano). `ai/pro/ProCapitalGuard`: alla fine di
  `ProNonCombatMoveAi.doNonCombatMove` (dopo il ciclo "difesa della capitale" di Hard, prima delle
  mosse) per ogni capitale propria calcola la win% del massimo attacco nemico (stesse unità nemiche
  massime che usa la difesa di Hard: **solo nazioni già in guerra**) contro i difensori dopo le mosse
  (unità assegnate, ferme, piazzamenti del turno). Sopra soglia: trattiene le unità che stavano
  uscendo dalla capitale, poi riporta unità di terra (più economiche prima) e aerei che possono
  arrivarci, una alla volta (max 40 calcoli), escluse quelle su trasporti. Se anche con tutte resta
  ≥ 90% (`HOPELESS`) non cambia nulla. Acquisti: la difesa di Hard (`defend_land`) viene già prima
  del mix e per la capitale punta sotto il 10%: nessun passo nuovo.
- Quota: `ProPurchaseOrders.minimumShare`; in `ProPurchaseAi.purchase`, se > 0,
  `purchaseDefendersWithinPlanShare`: prima i territori minacciati con fabbrica o capitale e win%
  nemica (prima dell'acquisto) > soglia guarnigione, senza tetto; poi gli altri con al massimo
  `PU iniziali × (100 − quota)%` meno quanto già speso (PU accantonati con
  `ProResourceTracker.reserve/release`). I territori di piazzamento di terra hanno tutti una
  fabbrica, quindi il tetto vale per quelli con rischio ≤ 25%.
- Aerei (`ProStagingOrders.air`): nel ciclo "aerei nel territorio sicuro con più opzioni d'attacco" di
  `moveUnitsToBestTerritories`, tra i territori sicuri vince valore aereo di Hard + forza × miglior
  valore aereo / (1 + distanza in volo dalla raccolta di terra); parità ⇒ scelta di Hard.
- Diario: "Capitale X: rischio stimato A% -> B% (soglia 25%), guarnigione N -> M unita' (trattenute
  ...; riportate ... da ...)" nel movimento; "Quota del piano: almeno Q% di P PU al piano (difesa
  urgente fino a T PU): difesa D PU, al piano R PU" + righe "difesa senza tetto" / "difesa nel
  tetto" negli acquisti; "aerei verso la raccolta" nell'ammassamento. JSON `capitale`,
  `quota_piano`, `ammassamento_aerei`.

### Politica, linea del fronte, presidi, imbarco, obiettivi critici (step 3b-ter)

- **Politica**: `fasi[].dichiarazioni` [{`da`, `a`, `dal_round`}] (unione di tutte le fasi del piano,
  `Piano.dichiarazione`). `ProTuning.warDecision(proData, nemico)` → `ProWarDecision` (HARD default;
  FORBIDDEN / DECLARE). `ProPoliticsAi.politicalActions`: le azioni di guerra verso un nemico
  FORBIDDEN sono tolte, DECLARE è eseguita senza il tiro casuale di Hard; nemici non nominati dal
  piano = regola casuale di Hard (anche il ramo "guerra ai neutrali" resta solo se Hard non aveva
  opzioni contro nemici). Pronta (`Staging.readiness`) = TUV di terra propria sui territori di raccolta
  del round (punti del file) ≥ TUV di terra del nemico nei territori confinanti; senza ammassamento =
  pronta. Dati: A dal R3 (variante A1 = mettere 2), B/C/D dal R4; Germania e Italia verso l'URSS.
  Diario: "Politica: guerra a URSS vietata prima del round 3 ..." / "dichiarata: ..., ammassamento
  X TUV in ... contro Y TUV nemici confinanti" / "rinviata: ..." (JSON `politica`), scritta in testa
  agli acquisti del turno (la fase politica viene prima dell'apertura del turno nel diario).
- **Obiettivi critici**: `obiettivi[].critico` (France R1, capitali nemiche: Russia, United Kingdom,
  Southern Italy, ...) ⇒ desiderata ≥ `ProPlusTuning.CRITICAL_OBJECTIVE_DESIRED` = 80 con qualunque
  cursore (la minima resta del fronte).
- **Raccolta**: `ammassamento.modo` = `linea_del_fronte` ⇒ ogni turno `Staging.frontLine`: territori
  di terra propri del fronte confinanti con terra di un nemico in guerra delle alleanze principali
  (non neutrali), peso 0.5 + 0.5/(1 + distanza dall'obiettivo del piano più vicino); senza linea
  (non ancora in guerra) ripiego sui `territori` del file. `ammassamento.peso` + `altre_raccolte`
  [{fronte, peso, territori, modo}]: peso di ogni territorio × peso del gruppo / peso massimo.
  `ProStagingOrders.weights`; `ProStaging` e gli aerei usano il miglior peso/(1 + distanza).
- **Presidio**: `presidio_minimo` {territori, unita} ⇒ `ProTuning.minimumGarrisons`;
  `ProCapitalGuard.minimumGarrisons` dopo la guarnigione della capitale: unità di terra più economiche
  che possono arrivarci, mai prese da una capitale o da un altro presidio sotto il minimo. Diario
  "Presidio X: minimo N unita' di terra, a -> b (portate: ...)".
- **Imbarco**: `imbarco` [territorio, zona] ⇒ se nessun territorio di raccolta è raggiungibile via
  terra **su terre proprie/alleate** dalla capitale (`Staging.isOverseas`), i punti d'imbarco entrano
  nella raccolta (peso 1); se i trasporti propri entro 2 zone trasportano meno delle unità di terra
  in attesa (sull'imbarco e confinanti; minimo 2) ⇒ +1 trasporto obbligato (categoria `trasporti`,
  etichetta "+ trasporto per l'imbarco").
- **Quote**: Germania 50%, Italia 40% in tutti i piani (oltre a USA 70%, UK 40%).
- Russi: raccolta `linea_del_fronte` (Est) in tutti i piani alleati, ripiego "Russia".

### Minaccia reale (step 3c)

Principio (deciso da Matteo): un territorio, capitali comprese, si presidia o rinforza **solo se può
essere attaccato prima del prossimo turno della nazione** da unità che possono arrivarci davvero.
- **Causa delle difese inutili** (verificata sul banco, R1 USA): la stima degli attacchi nemici di
  Hard (`ProTerritoryManager.findEnemyAttackOptions`) prende come nemici **tutti i giocatori non
  alleati** (`ProUtils.getEnemyPlayersInTurnOrder`) e li muove ignorando i rapporti
  (`isIgnoringRelationships = true`). In Europe 1940 USA e Regno Unito sono in "Neutrality" fino
  all'entrata in guerra degli USA ⇒ per gli USA le unità britanniche in Ontario/Quebec + bombardiere
  UK "attaccano" Central United States (rischio 100% con la sola fabbrica: la simulazione del turno
  ha già spostato via le unità) e per il Regno Unito le unità USA "attaccano" il Quebec. Trasporti
  e aerei erano contati correttamente (aerei: atterraggio controllato; trasporti: raggio e carico).
- `ProTuning.realEnemies(proData, player)` (default vuoto = Hard: tutti i non alleati). Pro+
  (`ProPlusTuning`, cache per dati/giocatore/round/step; con o senza piano) usa
  `proplus/MinacciaReale.nemici`: nemici **in guerra**; nemici a cui il piano fa dichiarare guerra
  dalla nazione stessa entro il prossimo round (`dichiarazioni` con `da` = la nazione e `dal_round` ≤
  round+1: la dichiarazione avviene a inizio del prossimo turno e le unità lasciate ora sono quelle
  che il nemico trova; es. Germania R2 con Barbarossa ⇒ URSS conta); nemici che **possono
  dichiararci guerra adesso** secondo la mappa (azione politica di un giocatore non alleato, condizioni
  vere, tentativi rimasti, che mette in guerra lui e la nazione; condizioni testate con
  `ProDummyDelegateBridge`; es. per gli USA la Germania conta sempre, per la Germania l'URSS conta
  dal round 4 in Europe 1940). Il filtro è applicato in `findEnemyAttackOptions` (gli
  altri usi delle unità nemiche — valore dei territori, bersagli — non cambiano) ⇒ vale per tutto
  ciò che usa la stima: difesa negli acquisti (`defend_land`), eccezioni alla quota del piano,
  guarnigione della capitale, movimenti di difesa del non-combattimento, contrattacchi stimati nel
  combattimento.
- Raggiungibilità: resta il modello di Hard (terra per movimento, sbarchi da trasporti in raggio
  con unità caricabili, aerei nel raggio con atterraggio). **Produzione nemica non contata**: le unità
  che il nemico piazza nel suo turno non possono attaccare prima del mio prossimo turno (scelta
  motivata nel report dello step 3c).
- **Presidi** (`presidio_minimo`, step 3b-ter): `ProTuning.garrisonNeeded` (default sì); Pro+ solo
  se la stima ha unità nemiche **sbarcabili** su quel territorio (`getMaxAmphibUnits` della stima
  filtrata) ⇒ presidio solo con trasporti nemici in raggio e unità da caricare. Limite noto del
  modello di Hard: un trasporto non passa per zone con navi nemiche (le mie): la flotta tedesca in
  110 SZ "protegge" Normandy/Holland Belgium dagli sbarchi britannici anche se l'UK potrebbe
  attaccarla prima di sbarcare.
- Diario: negli acquisti "difendo X: minaccia reale da <nazione> (<perché>): <unità>" per ogni
  territorio dove la difesa ha comprato, poi "PU risparmiati sulla difesa: N (stima: difesa di Hard
  per K territori minacciati solo da nazioni che non possono attaccare prima del prossimo turno)".
  Stima (`ProPurchaseAi.reportRealThreatDefense`, solo diario, calcolata sempre per Pro+ così le
  decisioni non dipendono dal diario on/off): seconda stima con tutti i non alleati
  (`ProTerritoryManager.populateEnemyAttackOptionsOfEveryEnemy`); per ogni territorio di
  piazzamento che il test di Hard trova in pericolo solo così, la fanteria/unità di terra più
  economica, una alla volta (max produzione della fabbrica, max 10), finché tiene come vuole Hard;
  limite ai PU del turno. Movimento: "difendo X: ..." sotto la riga della capitale quando la
  guarnigione cambia, "difendo X: ... (sbarco)" sotto ogni presidio, "Presidi non necessari (nessuno
  sbarco nemico possibile prima del prossimo turno): N". JSON `difesa_minaccia_reale`
  {difesi, pu_risparmiati, territori_non_minacciati}, `presidi_non_necessari`, `presidio.minaccia`.
- Codice: `ProThreat` (unità nemiche per nazione + perché), `ProCapitalGuard.Result.threat`,
  `GarrisonResult.threat`, hook `ProDecisionRecorder.realThreatDefense` / `garrisonsNotNeeded`.

### Avanzamento del piano e lettura del nemico (step 3c, solo osservazione)

Nessuna decisione dell'AI legge questi dati. `strategia/Valutazione.inizioRound`, chiamata in
`ProPlusAi.logPhase` al primo turno Pro+ di ogni round dell'alleanza (le altre nazioni trovano la
voce già scritta), aggiunge una voce a `storico` di `"Pro+ stato maggiore: <alleanza>"`
(`StatoMaggiore.aggiungiStorico`) e scrive nel diario un blocco "Stato maggiore <alleanza> - inizio
round N (piano X)" prima del turno della prima nazione (`DiaryRecorder.allianceRound`; JSON
`stato_maggiore_round` = la voce dello storico).
- **Tappe** (`tappe` [{`territorio` | `territori` + `richiesti` `tutti` (default) / `uno`, `nome`
  opzionale, `entro_round`}]): raggiunta (territori dell'alleanza ora, o raggiunta a un inizio round
  precedente: resta raggiunta) / in tempo / in ritardo (round superato e non raggiunta). Dati: A France
  R1, Egypt R4, Novgorod R6, Russia R9; B United Kingdom R3; C come A + 1 round (France R2 compresa,
  alla lettera); D Egypt R3, Gibraltar R3, Trans-Jordan R4, Caucasus R7; Alleati 1 "prima zona di
  sbarco" (uno tra Normandy Bordeaux, Norway, Holland Belgium) R5, France R8, Germany R11; 2
  Libya+Tobruk R5, Southern Italy R7, Northern Italy R9; N e 3 nessuna.
- **Indicatori**: reddito dell'alleanza (somma `ProUtils.getPlayerProduction`, solo produzione dei
  territori) ora, al R1 (prima voce dello storico) e del nemico; bilancio TUV cumulato = TUV nemico
  distrutto − TUV perso in tutte le battaglie (`BattleRecordsList`, per attaccante; il difensore è
  considerato dell'altra parte: attacchi a neutrali contano come perdite nemiche).
- **Stato**: IN CRISI se ≥ 2 tappe in ritardo e reddito in calo in ciascuno degli ultimi 2 round;
  IN RITARDO se ≥ 1 tappa in ritardo o reddito in calo da 2 round; altrimenti IN LINEA.
- **Lettura del nemico** (dallo step 3c-bis **evidenza cumulata**, sotto): per ogni piano nemico con
  `firma` (N escluso): punteggio = media pesata dei valori **ricordati** degli indizi; probabilità ∝
  punteggio² + 0.05 (`EPSILON`), normalizzate (l'`INERZIA` 0.5 sulle probabilità dello step 3c è
  stata tolta: la memoria è sugli indizi). Indizio (`firma`):
  {`indizio`, `tipo`, `nazioni`, `fronte`, `territori`/`zone`, `unita`/`classi`, `da`, `a`, `peso`,
  `da_round`, `a_round`}; valore = rampa lineare da `da` (0) ad `a` (1), `a < da` = più basso è più
  forte. Tipi: `quota_tuv_fronte` (quota del TUV di terra delle nazioni sul fronte), `quota_acquisti`
  (quota dei PU degli ultimi 2 acquisti delle nazioni, dalla **cronologia della partita**: eventi
  dei passi `...Purchase` con le unità; nessun acquisto ancora ⇒ indizio non valutato),
  `unita_in` (unità nei territori/zone, nessuno = ovunque). Classi: terra, aerei, navi,
  navi_da_guerra, trasporti. Pesi e rampe iniziali (scelti, non tarati) nel file. Indizi principali
  = i 3 con peso × valore più alti. Con avversario Pro+ (piano fissato nelle proprietà) "(piano
  reale avversario: X - lettura corretta si'/no)", corretta = il piano più probabile è quello vero.
- Testi: "Avanzamento: <stato> - tappe: ...; reddito N PU (R1 .., nemico ..), bilancio TUV ±N
  (nemico -.., noi -..)" e "Lettura del nemico: <piano> 55%, ... - indizi principali: ...".

#### Evidenza cumulata e indizi nuovi (step 3c-bis, solo osservazione)

- **Memoria** (`Valutazione.remember`, salvata nello storico: `lettura.memoria` {piano: {indizio:
  valore}}; il nome dell'indizio deve essere unico nel piano): indizio di stato m = 0.6 × m_prima +
  0.4 × valore del round (`MEMORIA`); indizio evento (`attacchi` senza `altrove`) m = max(valore,
  0.75 × m_prima) (`MEMORIA_EVENTI`: una battaglia vista una volta pesa 1, 0.75, 0.56...). Indizio
  non misurabile nel round o fuori da `da_round`/`a_round` ⇒ resta il valore ricordato (es. "guerra
  all'URSS al R3" misurato solo al R3 conta per tutta la partita). Prima misura ⇒ m = valore. JSON
  per indizio: `misura`, `valore_round`, `valore` (ricordato), `peso`.
- **Tipi nuovi** (`Strategia.Firma`): `piazzamenti` (TUV piazzato dalle nazioni negli ultimi 2 passi
  `...Place` della cronologia, eventi con `PlacementDescription`; quota in `territori` sul totale o su
  `territori`+`altrove`; niente piazzato ⇒ non valutato), `attacchi` (territori di `territori` con una
  battaglia delle nazioni come attaccante nel round o nel precedente, contro `contro` se indicato,
  da `BattleRecordsList` con `BattleRecords.getWereThereBattlesInTerritoriesMatching`), `in_guerra`
  (1 se una delle nazioni è in guerra con una di `contro`). Campo `altrove` (o `zone_altrove`) per
  `unita_in`/`piazzamenti`/`attacchi` ⇒ la misura è la quota in `territori` rispetto a `territori` +
  `altrove` (nessuna unità/battaglia in entrambi ⇒ non valutato): così lo stesso fatto è a favore
  di un piano e contro l'altro (rampe speculari). Classe `trasporti_carichi` (trasporti con unità a
  bordo, contati nel loro territorio).
- **Indizi e pesi** (scelti, non tarati; rampa da→a; "q" = quota con `altrove`):

  | Piano | Indizio | Tipo | Rampa | Peso |
  |---|---|---|---|---|
  | 1 | piazzamenti UK in Union of South Africa/71 SZ (q vs United Kingdom/109/110) bassi | piazzamenti | 0.3→0 | 3 |
  | 1 | navi UK in 96/97/98 (q vs 109-112) basse | unita_in | 0.5→0.15 | 2 |
  | 1 | attacchi di British/Americans/French su territori italiani (q vs costa tedesca: Normandy Bordeaux, Holland Belgium, Denmark, Norway, Western Germany) bassi | attacchi | 0.5→0.1 | 3 |
  | 1 | navi USA in 91/92 (q vs 109/110) basse | unita_in | 0.5→0.1 | 3 |
  | 1 | trasporti carichi UK+USA nel Mediterraneo 91-99 (q vs 106/109-112/119) bassi | unita_in | 0.5→0.1 | 2 |
  | 1 | trasporti UK in 109/110/111/119 (q vs 71/81/91-99) | unita_in | 0.5→0.9 | 2 |
  | 1, 2 | acquisti USA navali | quota_acquisti | 0.3→0.6 | 1 |
  | 2 | piazzamenti UK in Sud Africa (q) | piazzamenti | 0.1→0.5 | 3 |
  | 2 | navi UK in 96/97/98 (q) | unita_in | 0.5→0.85 | 2 |
  | 2 | attacco UK alla flotta italiana in 95/96/97 (letture R1-R3) | attacchi (evento) | 0→1 | 2 |
  | 2 | attacchi alleati su territori italiani (Libya, Tobruk, Ethiopia, Italian Somaliland, Sicily, Sardinia, Southern/Northern Italy, Albania) (q) | attacchi | 0.5→0.9 | 3 |
  | 2 | navi USA in 91/92 (q) | unita_in | 0.5→0.9 | 3 |
  | 2 | trasporti carichi UK+USA nel Mediterraneo (q) | unita_in | 0.5→0.9 | 2 |
  | 2 | unità di terra UK in Egypt/Alexandria/Trans-Jordan/Sudan | unita_in | 5→10 | 1 (era 3) |
  | 3 | bombardieri negli acquisti UK+USA | quota_acquisti | 0.1→0.35 | 4 |
  | A | TUV di terra tedesco a Est | quota_tuv_fronte | 0.25→0.55 | 3 |
  | A | Germania in guerra con l'URSS al R3 (misurato solo al R3) | in_guerra | 0→1 | 3 |
  | A | poche unità di terra tedesche al sud (Southern Italy, Sicily, Sardinia, Albania, Greece, Bulgaria, Libya, Tobruk, Tunisia, Algeria, Morocco, Egypt, Alexandria, Trans-Jordan) | unita_in | 3→0 | 2 |
  | A | acquisti tedeschi di terra / pochi trasporti tedeschi | quota_acquisti / unita_in | 0.6→0.85 / 3→1 | 1 + 1 (erano 2 + 2) |
  | B, C | indizi dello step 3c + nessuna guerra all'URSS al R3 | in_guerra | 1→0 | 1 |
  | D | unità di terra tedesche al sud (stessa lista) | unita_in | 0→4 | 4 |
  | D | aerei tedeschi al sud (peso basso: atterrano e ripartono) | unita_in | 0→4 | 1 |
  | D | attacchi tedeschi nel Mediterraneo (Egypt, Alexandria, Malta, Greece, Gibraltar, Trans-Jordan, Crete, Cyprus, 92-99 SZ) | attacchi (evento) | 0→1 | 3 |
  | D | attacchi di Germania/Italia a Malta, Greece, Gibraltar | attacchi (evento) | 0→1 | 2 |
  | D | nessuna guerra all'URSS al R3 (A la dichiara al R3, B/C/D dal R4) | in_guerra | 1→0 | 3 |
  | D | ammassamento tedesco a Est basso | quota_tuv_fronte | 0.35→0.15 | 3 |
  | D | trasporti italiani negli acquisti / carichi in 93-99 | quota_acquisti / unita_in | 0.15→0.35 / 0→2 | 1 + 1 |

  Tolti: D "unità o aerei tedeschi in Italia/Africa" (peso 4, aerei inclusi ⇒ oscillava), D "TUV di
  terra dell'Asse nel Mediterraneo" (crollava con le perdite italiane), 1 "flotta USA verso l'Atlantico
  orientale" e "trasporti UK attorno al Regno Unito" in conteggio assoluto, 2 "marina alleata nel
  Mediterraneo" (sostituiti dalle quote). Northern Italy escluso dalla lista "sud": al R1 la Germania
  in piano A vi parcheggia unità (logica di Hard) ⇒ falso indizio D.

## Operazioni anfibie (step OPERAZIONI ANFIBIE, solo Pro+)

Hard decide gli sbarchi turno per turno; Pro+ con un piano che ha `operazioni_anfibie` pianifica
porto → zona di raccolta → bersaglio. Hard identica (nessun piano ⇒ nessuna operazione; hook di
default vuoti), piano N senza operazioni.
- **Dati** (`operazioni_anfibie` del piano, `Strategia.Operazione`): `nome`, `nazioni` (truppe che
  sbarcano), `vettori` (nazioni dei trasporti, default = `nazioni`), `da_round`/`a_round`, `porti`
  [{territorio, zona}] dalla retrovia al fronte, `raccolta` (zone dove la flotta carica aspetta),
  `bersagli` in ordine, `trasporti` per ondata, `scorta_minima` (navi da guerra), `peso`, `critico`.
  Europe 1940 (verificati sull'XML; 101→110 = 4 zone = 2 turni; Norway confina solo con 112/113/125/
  126; Morocco/Algeria/Tunisia sono **francesi (alleati)** a inizio partita):
  - 1: "Overlord USA" (Americans dal R3, porti Eastern United States/101, United Kingdom/110 e /109,
    raccolta 110/109, bersagli Normandy Bordeaux, Holland Belgium, Norway, 4 trasporti, scorta 2,
    peso 15); "Overlord UK" (British dal R2, porti United Kingdom/110 e /109, raccolta 110, stessi
    bersagli, 3 trasporti).
  - 2: "Torch" (Americans dal R3, porto Eastern United States/101, raccolta 91/92, bersagli Morocco,
    Algeria, Tunisia, Sicily, Southern Italy); "Rinforzi Egitto" (British dal R1, porto Union of South
    Africa/71, raccolta 81 (Mar Rosso, confina con Egypt), bersagli Egypt, Tobruk, Libya).
  - D: "Afrika Korps" (nazioni Germans + Italians, vettori Italians, porti Southern Italy/97 e /95,
    Northern Italy/97, raccolta 96, bersagli Libya, Tobruk, Egypt, 4 trasporti, scorta 1). Regola della
    mappa verificata nel codice (`MoveValidator`, `TransportTracker.getUnitsLoadedOnAlliedTransports
    ThisTurn`): si possono caricare unità proprie su un trasporto alleato, ma non caricarle e scaricarle
    nello stesso round, e solo il proprietario muove le sue unità ⇒ i tedeschi salgono sui trasporti
    italiani nel turno tedesco, l'Italia porta il trasporto in 96 nel suo turno, i tedeschi scendono in
    Libia/Tobruk nel turno tedesco successivo. Raccolta tedesca di D: Southern Italy + Northern Italy
    (tolta Greater Southern Germany, che è nel fronte Ovest e non è un porto).
  - B (sperimentale): "Leone marino" (Germans R1-3, porti Western Germany/112, Denmark/112,
    Germany/113, raccolta 112, bersagli United Kingdom, Scotland, critico).
- **Bersagli = obiettivi del piano** (`PianoAttivo`): per le nazioni di `nazioni` ogni bersaglio è un
  obiettivo con peso `peso × 0.8^posizione` (se non lo è già nella fase) ⇒ bonus di priorità, desiderata
  ≥ base (≥ 80 se `critico`), regola "obiettivo" dello step 2d. `ProPlusTuning.isLandingOnlyTarget`:
  bersaglio senza terre proprie/alleate confinanti (solo via mare) ⇒ **minima = desiderata** (sbarco solo
  alla win% desiderata, Equilibrato 80). Lo sbarco lo fa il combattimento di Hard (trasporti carichi o
  caricabili in raggio, scramble controllato come Hard Beta dallo step 3c-bis, battaglia navale
  preliminare già gestita da Hard).
- **Esecuzione** (`OperazioniAnfibie.esegui`, chiamata da `ProPlusAi.move` nel non-combattimento reale,
  prima di quello di Hard; mai nelle simulazioni): trasporti puri propri assegnati all'operazione con
  zone più vicine (≤ 6 zone); in ordine carichi, vuoti in porto, altri:
  - *carico*: truppe di terra con attacco > 0 (no antiaerei) al porto, le più grandi prima, finché c'è
    posto; da una capitale propria solo finché la win% nemica sulla capitale resta ≤ 25
    (`CAPITAL_GUARD_THRESHOLD`); in una zona di raccolta solo se la flotta è sicura; se nel porto
    aspettano truppe di un'altra nazione dell'operazione il trasporto resta vuoto per loro;
  - *trasferimento*: verso la zona di raccolta più vicina, fermandosi nella zona più lontana **sicura**:
    win% del massimo attacco nemico (stima di Hard con i nemici reali dello step 3c) ≤ 100 −
    desiderata del livello effettivo della zona (Equilibrato: 20%). Se non lo è, navi da guerra proprie
    entro 2 zone (non portaerei, non da zone con propri trasporti) si aggiungono come scorta, la più
    vicina/economica prima, max 6, finché lo diventa;
  - alla raccolta: bersagli in ordine, il primo amico confinante ⇒ sbarco subito (Libia, Marocco,
    Egitto); il primo nemico raggiungibile (2 zone) ⇒ attesa carichi per il combattimento del turno dopo;
    flotta già carica dall'inizio del turno senza sbarco ⇒ "bersaglio troppo forte": truppe a terra sul
    primo bersaglio amico o porto confinante (dal porto Hard le ricarica nel combattimento);
  - *spola*: trasporto vuoto verso il porto con più truppe in attesa (pari: più vicino; senza truppe: il
    porto con fabbrica), prova il porto successivo se la rotta non è sicura; in porto resta lì se sicuro;
  - *nazione con trasporti alleati* (Germania in D): scarica le sue truppe a bordo da un turno precedente
    sul primo bersaglio amico confinante; carica le truppe in porto sui trasporti alleati con posto se la
    zona è sicura;
  - unità mosse o tenute (trasporti, carico, scorta, navi da guerra della zona) ⇒ `ProTuning.heldUnits`
    ⇒ `ProNonCombatMoveAi` le mette tra le "non muovibili" (il carico solo fuori dalle mosse). Flotta in
    una zona non sicura ⇒ non tenuta: Hard la porta nella zona più sicura.
- **Soste sicure e scorta** (fix della partita di Matteo, build 315bcc2, Alleati Pro+ piano 1 contro Asse
  umano: R7-R9 trasporti USA carichi mandati da 101 a 108 Sea Zone senza scorta, "scorta 3" rimasta in 101, e
  affondati ogni turno; la logica è la stessa in 011a5ac). Cause nel codice: (a) zona senza attacco nemico
  stimato ⇒ "sicura" anche senza navi da guerra (uscita anticipata di `safety`); (b) i candidati di scorta
  escludevano le zone con trasporti propri, quindi le navi nella zona di partenza dei trasporti non li
  accompagnavano mai; (c) nessuna memoria delle perdite. Ora (`OperazioniAnfibie.safety`/`makeSafe`):
  ogni zona di fine movimento (non solo la raccolta) richiede **almeno 1 nave da guerra presente** se non è
  la zona di un porto dell'operazione (niente trasporti soli fuori dal porto, anche con stima nemica 0) e
  win% del miglior attacco nemico ≤ soglia del livello effettivo; le navi della zona di partenza dei
  trasporti possono fare da scorta e si muovono nella stessa zona d'arrivo; **memoria del pericolo**
  (`lossRound`, dai `BattleRecords` tramite gli helper pubblici): zona dove unità delle nazioni
  dell'operazione (truppe o vettori) hanno perso TUV difendendo negli ultimi `DANGER_ROUNDS` = 3 round ⇒
  soglia dimezzata (margine doppio). Nessuna sosta sicura ⇒ la flotta non parte, diario "nessuna sosta
  sicura verso X, la flotta resta in Y (soste: Z: win% nemica A% > soglia B% (scorta N)[, zona pericolosa:
  perdite al round R, soglia dimezzata][, trasporti senza scorta]; ...)". Rotta libera più lunga di 6 zone
  (deviazioni, es. nella mappa Global di test il giro del mondo via Panama) ⇒ "nessuna rotta diretta",
  la flotta aspetta. Vale per trasporti carichi, spola dei vuoti (fuori porto con scorta) e scorte della
  flotta combinata.
- **Acquisti** (`OperazioniAnfibie.acquisti`, calcolati in `ProPlusAi.purchase` sul tabellone reale prima
  della simulazione, `ProPlusTuning.prepareOperationPurchases`): obbligati aggiunti agli ordini del piano
  (al posto della regola "+1 trasporto per l'imbarco" dello step 3b-ter): trasporti (max 2 per turno, fino
  a `trasporti`) se le truppe in attesa ai porti superano il posto libero (2 unità per trasporto) o non ci
  sono trasporti; altrimenti fanteria (max 4) se il posto supera le truppe; 1 cacciatorpediniere se le
  navi da guerra ai porti e alla raccolta sono meno di `scorta_minima`. Etichetta degli ordini
  "+ operazione X: N categoria (motivo)".
- **Diario** (scritto all'inizio del movimento, dopo gli esiti delle battaglie): "Operazioni anfibie:" +
  per operazione "Operazione X: stato (preparazione / in rotta / sbarco / bloccata) - trasporti N, truppe
  a bordo N, scorta N[, sbarcate nel turno N unità su ...]; prossimo passo: ...; motivo: ..." + "mosse:"
  + "acquisti chiesti nel turno". Stato: sbarco = unità della nazione scaricate nel combattimento su un
  bersaglio; bloccata = un motivo (scorta insufficiente, zona non sicura, bersaglio troppo forte, nessuna
  rotta, porto esposto); in rotta = truppe a bordo; altrimenti preparazione. Il prossimo passo con flotta
  carica alla raccolta riporta la win% stimata dello sbarco sul primo bersaglio nemico (truppe a bordo +
  truppe nei porti vicini). JSON `operazione_anfibia`.
- **Riparazioni** (punto 3 dello step): i "PU non spesi" della Germania in D (40/53, 47/53, 49/56 in
  68f095c) sono **riparazioni delle fabbriche** bombardate da UK/USA (le differenze coincidono round per
  round con i bombardamenti su Western Germany del round prima; R3/R4: 6/7 PU). La cassa a fine turno
  non resta. Il diario ora scrive "(spesi X di Y PU, riparazioni fabbriche R PU, avanzati A PU)" e JSON
  `pu_riparazioni`, `pu_avanzati`; colonna "PU riparazioni" nel riepilogo.

## Coordinamento alleato (step OPERAZIONI ANFIBIE-BIS, solo Pro+)

`ai/proplus/TestaDiPonte` + agganci in `OperazioniAnfibie`, `ProPlusTuning`, `PianoAttivo`. Hard identica:
nuovo hook `ProTuning.keepsBridgehead` (default vuoto) chiamato in `ProCombatMoveAi` solo dopo che
`keepsAttackNotWorthIt` non ha tenuto uno sbarco che Hard scarta.
- **Coppia** (`TestaDiPonte.coppie`, `PianoAttivo.operazioniCombinate`): operazioni dello stesso piano, in
  vigore nel round, di nazioni diverse e alleate con una zona di raccolta in comune (piano 1: Overlord USA
  + Overlord UK, raccolta comune 110). Chi muove prima: ordine degli step della sequenza (Europe 1940:
  Germans, Russians, **Americans, British**, Italians, French; nessun turno dell'Asse tra USA e UK).
  Bersagli comuni nell'ordine dell'operazione di chi muove per primo.
- **Flotta combinata**: la sicurezza di una zona conta già le navi alleate presenti; per chi muove per
  primo si aggiungono le navi da guerra del partner che possono raggiungere la zona comune nel suo turno
  (entro 2 zone e il loro movimento, non portaerei, non da zone con trasporti del partner). Nel turno del
  partner (`joinCombinedFleet`) le sue navi entro 2 zone raggiungono la zona dove ci sono trasporti
  dell'altra nazione finché la flotta combinata è sicura (diario "scorta alla flotta combinata").
- **Soglia di sicurezza della flotta** (tutte le operazioni, anche D/B/2): nemico ≤ 100 − **minima** del
  livello effettivo della zona (Equilibrato 40%, Audace 48%), prima 100 − desiderata (20%). Piano 1: fasi
  R1-2 (Mari 0), R3 (Mari +1), R4+ (Mari +1).
- **Truppe a terra nel porto di raccolta** (scoperta dello step): il combattimento di Hard non sa usare
  trasporti caricati in un turno precedente (`ProTerritoryManager.findAmphibMoveOptions` toglie le
  opzioni senza territori da cui caricare: `transportMap.values().removeIf(Collection::isEmpty)`), per
  questo in 28681cd gli USA con 11 truppe a bordo in 110 e stima 100% non valutavano nemmeno Normandy
  ("bersaglio troppo forte" il turno dopo). Ora, se la zona di raccolta è anche zona di un porto amico
  dell'operazione con un bersaglio nemico in raggio (Overlord: 110/United Kingdom; Leone marino:
  112/Western Germany), un trasporto che vi arriva carico mette subito le truppe a terra nel porto e i
  trasporti vuoti aspettano lì (quanti ne servono: 2 truppe per trasporto; gli altri fanno la spola verso
  gli altri porti con truppe): il combattimento successivo di Hard carica e sbarca nello stesso turno.
  Nessun imbarco "in attesa" nella zona di raccolta-porto.
- **Testa di ponte a due tempi**: chi muove per primo sbarca su un bersaglio comune solo-via-mare con
  minima = **desiderata del livello effettivo** del bersaglio (Normandy con Ovest +1 = Audace 72; prima 80;
  la desiderata con cui Hard assegna le unità resta 80 per gli obiettivi). Se Hard scarta lo sbarco per
  valore (non tenibile) `ProPlusTuning.keepsBridgehead` lo tiene (`KEPT_BY_BRIDGEHEAD` = "bridgehead")
  quando win ≥ desiderata effettiva e il contrattacco nemico massimo (unità max nemiche di Hard) contro
  superstiti + **rinforzi del partner** ha win% ≤ 100 − minima del livello del bersaglio (Audace 48%).
  Rinforzi (`TestaDiPonte.rinforzi`, distanze solo marittime, flotte nemiche ignorate): truppe del partner
  a bordo di suoi trasporti in raggio di una zona adiacente al bersaglio + truppe nei porti della sua
  operazione caricabili da un suo trasporto vuoto (porto + zona adiacente entro il movimento), dalla
  capitale solo finché la win% nemica sulla capitale resta ≤ 25%. Anche il controllo "trasporti esposti"
  tiene lo sbarco (`keepsLandingWithExposedTransports`) se è un bersaglio comune di chi muove per primo con
  win ≥ desiderata effettiva (la tenuta si verifica dopo). Se Hard tiene già lo sbarco per valore non si
  scarta nulla. Il partner nel suo non combattimento (`reinforceBridgehead`): primo bersaglio comune del
  partner con sue truppe di terra ⇒ i suoi trasporti (carichi, o vuoti che caricano in un porto in raggio)
  scaricano lì tutte le truppe. Senza testa di ponte il partner segue la regola normale (80).
- **Stesso bersaglio e scarto degli sbarchi** (chiusura dello step, hook `ProTuning.dropsLanding`, default
  vuoto, chiamato in `ProCombatMoveAi` per ogni sbarco pianificato non di logoramento **prima** delle
  regole di Hard: può togliere sbarchi che Hard terrebbe; motivo nel diario e nel JSON):
  - chi muove per primo sbarca solo sul **bersaglio della coppia** = il primo bersaglio comune, in
    ordine Normandy Bordeaux > Holland Belgium > Norway, ancora pianificato dal combattimento (un
    bersaglio non raggiungibile o sotto la minima non è pianificato ⇒ si passa al successivo); gli
    sbarchi sugli altri bersagli comuni sono scartati (`bridgehead_other_target`); le unità tornano
    all'assegnazione di Hard;
  - sul bersaglio della coppia lo sbarco è scartato se il contrattacco stimato dopo i rinforzi del
    partner supera 100 − minima del livello effettivo (`bridgehead_not_held`), anche se Hard lo
    terrebbe; con contrattacco ≤ soglia decide Hard (o `keepsBridgehead` se Hard lo scarta);
  - chi muove per secondo non sbarca su altri bersagli comuni mentre il partner tiene una testa di
    ponte (`bridgehead_other_target`): rinforza quella nel non combattimento.
- **Bersagli per fase** (`bersagli_condizionati` dell'operazione, `Strategia.CondizioneBersaglio`,
  `strategia/CondizioniBersaglio`, applicati in `PianoAttivo` alle operazioni proprie e a quelle
  combinate; `PianoAttivo.bersagliCompleti` = tutti, anche non attivi): piano 1, Overlord USA e UK
  `["Normandy Bordeaux", "Holland Belgium", "Norway"]` con Holland Belgium attivo **dal R8**, oppure prima
  se la Germania ha perso la capitale (un territorio con `capital` = Germans non suo) **o** il suo TUV di
  terra (senza infrastrutture) sul fronte Ovest è < 50% del riferimento. Riferimento = lo stesso TUV la
  prima volta che la condizione viene letta nella partita, salvato nella proprietà `"Pro+ TUV iniziale:
  Germans Ovest"` (stringa); con gli Alleati Pro+ è il primo turno alleato del R1 (dopo quello tedesco).
  Quindi R1-R7: Normandy, poi Norway. Holland tolto dagli obiettivi di fase R4+ (è obiettivo tramite
  l'operazione quando è attivo). Overlord USA: `trasporti` 4 → 8.
- **Bersaglio della coppia per chi muove per primo** (`TestaDiPonte.bersaglioScelto`): il primo bersaglio
  comune attivo non ancora alleato, senza ripiego: gli USA non sbarcano su nessun altro bersaglio
  dell'operazione (anche non attivo) finché Normandy è nemica (accumulo); presa Normandy, Norway.
- **Accumulo** (`OperazioniAnfibie.Accumulo`, nel non combattimento degli USA, chi muove per primo):
  truppe USA nel porto di raccolta (United Kingdom) + a bordo dei trasporti USA nelle zone di raccolta
  comuni, imbarcabili sui trasporti USA entro 2 zone (dalla più grande); win% contro i difensori attuali
  del bersaglio (senza aerei né bombardamento) vs desiderata del livello effettivo (Normandy, Ovest +1:
  72). Pronto = win% ≥ soglia o sbarco su un bersaglio comune nel turno. Lo sbarco vero resta del
  combattimento di Hard (minima = desiderata effettiva) con la regola di tenuta. Nel frattempo i
  trasporti portano le truppe in United Kingdom e gli acquisti dell'operazione per il posto libero sono
  divisi fanteria/artiglieria/carri_mech in proporzione al mix del piano (`OperazioniAnfibie.landShares`,
  resti maggiori; prima solo fanteria).
- **Stallo** (`strategia/StalloOperazioni`, proprietà `"Pro+ operazione: <nome>"` = JSON {ultimo_pronto,
  round_senza, stallo}): dal R5 (o dal round dopo l'ultimo "pronto") 3 round senza raggiungere la soglia
  ⇒ "in stallo" finché non torna pronta. `Valutazione`: un'operazione del piano in stallo peggiora lo
  stato di un livello (IN LINEA → IN RITARDO → IN CRISI), JSON `operazioni_in_stallo`, testo
  "operazioni in stallo: ...". Nessun cambio di piano (step 3d).
- Diario a ogni turno USA: "Overlord USA: accumulo (Americans X unita' in United Kingdom, N imbarcabili su
  T trasporti, win% Normandy Bordeaux Y% / soglia Z%)" (o "pronto"), con " - Overlord USA IN STALLO: N
  round dal R5 senza raggiungere la soglia"; JSON `accumulo` {bersaglio, porto, unita_porto, imbarcabili,
  trasporti, win, soglia, pronto, round_senza, stallo}. Autoplay: colonna "Operazione in stallo" per
  partita, "Sbarco USA entro R9" e "In stallo" per configurazione.
- **Diario**: righe "flotta combinata: 110 Sea Zone: navi Americans N, British M (+K in arrivo nel turno di
  British)" e testa di ponte ("testa di ponte di Americans su X, rinforzo di British nel suo turno: win%
  sbarco W% (desiderata D%), tenuta stimata dopo il rinforzo di British (N unita'): contrattacco nemico
  C% (senza rinforzo C0%) <= soglia S%"; per il partner "testa di ponte di Americans su X, rinforzo di
  British: N unita'"; teste di ponte valutate nel combattimento e scartate). La tenuta del primo è
  ricalcolata nel suo non combattimento con i superstiti reali; win% sbarco dalla valutazione del
  combattimento o dal record dell'attacco. JSON `operazione_anfibia`: `flotta_combinata`,
  `testa_di_ponte`, `valutazione_testa_di_ponte` {territorio, testa_di_ponte, rinforzo, win_sbarco,
  desiderata, contrattacco_senza_rinforzo, contrattacco_dopo_rinforzo, soglia, rinforzi, tenuta}.
  Riga dell'attacco tenuto: "tenuto da Pro+ ... - testa di ponte: ...".
- **Autoplay**: `Monitor.WEST_EUROPE` (Normandy Bordeaux, France, Southern France, Holland Belgium,
  Norway, Denmark, Western Germany, Germany, Greater Southern Germany), `ovest_alleato` per round
  (territori di WEST_EUROPE degli Alleati a fine round); riepilogo `usa_sbarcate_ovest` (unità americane
  di sbarchi veri in WEST_EUROPE per round) e `prima_testa_di_ponte` (primo sbarco vero britannico/
  americano in WEST_EUROPE il cui territorio è alleato a fine del suo round e del successivo);
  `riepilogo-generale.md` sezione "Coordinamento alleato: sbarchi USA e teste di ponte" (per partita e
  per configurazione: sbarco USA entro R7, teste di ponte ≥ 2 round, round medio, unità USA per round).

## Postura, cambio piano, contromosse (step 3d-1, solo Pro+)

`strategia/Contromosse`, chiamata da `Valutazione.inizioRound` (primo turno Pro+ dell'alleanza nel round,
dati reali) dopo avanzamento e lettura. Hard identica (nessuna AI Hard arriva qui), piano N senza effetti
(`contromosse: false` ⇒ postura `NESSUNA`, mai cambio, niente rientro). Decisioni indipendenti dal diario.
- **Numeri** (`stato_maggiore` nel file, `Strategia.Parametri`; scelti, non tarati): `round_crisi` 2,
  `inerzia_round` 3, `max_cambi` 2, `margine_cambio` 0.1, `soglia_lettura` 0.5, `bonus_contrasto` 0.25,
  `proroga_tappe` 2, `peso_rallentamento` 8, `peso_contrasto` 20, `round_contrasto` 2, `corsa_pari` 2,
  `distanza_forze` 2, `distanza_attaccabile` 2, `soglia_contrasto` 0.35, `vittoria` {Axis: {vc: 8},
  Allies: {territori: [Germany, Southern Italy], richiesti: 2}} (come le condizioni di vittoria dell'XML:
  gli Alleati partono con 8 VC su 11, contarle darebbe "vittoria in 0 round" dal R1).
- **Cambio piano** (`Contromosse.cambio`): condizione = piano in vigore in crisi (stato IN CRISI **o** una
  sua operazione in stallo) in `round_crisi` round di fila con quel piano, meno di `max_cambi` cambi,
  almeno `inerzia_round` round dall'ultimo cambio. Alternative = `subentri` (A→D, D→A, C→A, B→A, 1→2,
  2→1, 3→1/2). Punteggio = (tappe + forze + stato)/3 + contrasto: tappe = quota delle tappe del piano
  tenute o con unità di terra dell'alleanza entro `distanza_forze`; forze = TUV terra+aerei nostro /
  (nostro + nemico) entro `distanza_forze` dai bersagli non tenuti (tappe, obiettivi della fase del round,
  bersagli delle operazioni); stato = 0 per il piano in vigore (è in crisi), 0.5 per le alternative;
  contrasto = `bonus_contrasto` se il piano `contrasta` il piano nemico più probabile letto oltre
  `soglia_lettura` ("chi mette in crisi chi": 1 contrasta A, B, C; 2 contrasta D; A contrasta 2, 3; D
  contrasta 1). Cambio se la migliore alternativa supera il piano in vigore di `margine_cambio`.
  **Formula (dallo step 3d-1-bis)**: punteggio(P) = (tappe + forze + stato)/3 + contrasto, con
  contrasto = `bonus_contrasto` (0.25) **solo se P è un'alternativa** (un subentro) e `contrasta` il piano
  nemico più probabile letto oltre `soglia_lettura`; per il piano in vigore contrasto = 0, perché qui è
  sempre in crisi (IN CRISI o operazione in stallo: solo allora si valuta), JSON `contrasto_escluso` =
  "piano in vigore in crisi" quando il bonus gli spetterebbe. Prima (3d-1) il bonus andava anche al piano
  in vigore e lo teneva sopra il 2 in 6 partite su 8. Senza cambio il motivo è "nessuna alternativa
  migliore di <margine> (1 0.33 = (tappe a + forze b + stato 0.00)/3 + contrasto 0.00; 2 ... )". Al cambio:
  `StatoMaggiore.cambia`, stallo delle operazioni del nuovo piano ripartito (`StalloOperazioni.riparti`),
  avanzamento ricalcolato col nuovo piano; le sue tappe non sono in ritardo prima di round del cambio +
  `proroga_tappe` (`entro_round_effettivo` nel JSON). La fase è quella del round corrente; acquisti,
  raccolta e operazioni seguono il nuovo piano dallo stesso round (`PianoAttivo` legge lo stato).
- **Corsa** (`Contromosse.Corsa`): per alleanza misura = VC tenute (o territori di `vittoria` tenuti),
  tendenza = (misura ora − misura dell'ingresso dello storico di 3 round fa, o il più vecchio) / round,
  round alla vittoria = (obiettivo − misura) / tendenza (`mai` = 99 se tendenza ≤ 0; 0 se raggiunto). Pari
  se entrambe `mai` o differenza ≤ `corsa_pari`.
- **Postura**, in quest'ordine: `DIFENSIVA` (piano IN CRISI e nemico più veloce ⇒ rischio −1 su tutti i
  fronti dopo il modificatore della fase, `PianoAttivo.livelloFronte`; quota del piano 0 negli acquisti ⇒
  la difesa della minaccia reale viene prima del mix senza tetto, etichetta "[postura DIFENSIVA ...]");
  `CONTRASTO` (nemico più veloce, piano nemico più probabile letto ≥ `soglia_contrasto`, almeno un suo punto
  critico attaccabile = unità nostre con attacco > 0 nel bersaglio o entro `distanza_attaccabile`, meno di
  `round_contrasto` round di contrasto di fila ⇒ i punti attaccabili sono obiettivi di tutte le nazioni
  dell'alleanza con peso `peso_contrasto`); `RALLENTAMENTO` (corsa pari e piano nemico letto oltre
  `soglia_lettura` ⇒ tutti i suoi punti critici presenti obiettivi con `peso_rallentamento`);
  `OFFENSIVA` altrimenti (piano IN LINEA e in vantaggio, **ma anche ogni caso non coperto**: nessun
  effetto; il motivo dice quale). Contro-obiettivi = obiettivi normali del piano (bonus di priorità,
  desiderata ≥ livello base, regola "obiettivo" dello step 2d; mai neutrali).
- **Punti critici** (`Contromosse.places`, bersagli per l'alleanza che contrasta): `territorio` = i
  territori se sono dell'alleanza del piano (Egypt conta solo se l'Asse l'ha preso); `unita` = luoghi di
  `territori`/`zone` o del `fronte` con unità `unita`/`classi` di `nazione` (terra: solo se del nemico);
  `flotta` = zone con navi di `nazione` (assente = qualunque nazione del piano); `stack_avanzato` = territorio
  dell'alleanza del piano con unità di terra di `nazione` confinante con terra nostra, il più lontano dalla
  capitale (distanza via terra), a parità il meno sostenuto (meno territori confinanti del piano), poi più
  unità. Dati: A stack tedesco, Romania, Egypt; B trasporti tedeschi in 112-115, aerei tedeschi sul fronte
  Ovest; C come B fino al R3; D trasporti e flotta italiani in 92-99, Egypt, Gibraltar; 1 flotta (qualunque
  nazione alleata) in 104/109/110; 2 flotta USA in 91/92, Egypt, Malta; 3 e N nessuno.
- **Rientro** (`OperazioniAnfibie.rientro`, in `ProPlusAi.move` nel non combattimento reale prima delle
  operazioni, per ogni piano con contromosse anche senza operazioni): proprietà `"Pro+ a bordo: <nazione>"`
  = {id trasporto: {zona, dal_round, carico, rientro}} aggiornata a ogni non combattimento. Trasporto puro
  con mie truppe a bordo nella **stessa zona con lo stesso carico** da un round precedente (o già in
  rientro), fermo, non in una zona di raccolta delle operazioni del piano (lì scarica già la regola
  "bersaglio troppo forte") ⇒ costa amica (terra propria o alleata) più vicina via mare (entro 6 zone), a
  parità un porto delle operazioni del piano, poi terra propria; zona d'arrivo sicura (win% nemica ≤ 100 −
  minima del livello della zona; trasporto senza navi da guerra = 100%; **memoria del pericolo dallo step
  3d-1-bis**: zona dove unità della nazione hanno perso TUV difendendo negli ultimi 3 round ⇒ soglia
  dimezzata, testo "zona pericolosa: perdite al round R, soglia dimezzata"); fuori portata ⇒ verso di lei
  fino alla zona sicura più lontana (stessa soglia; resta "in rientro"). Truppe a terra, trasporto e truppe tenuti
  (`heldUnits`). Diario: rapporto "Rientro truppe a bordo" tra le operazioni anfibie.
- **Stato strategico** (JSON `stato_strategico` nell'ingresso dello storico, quindi nel diario e in
  `decisioni.jsonl` con l'evento `stato_maggiore_round`): {alleanza, round, piano, nome_piano, fase, stato,
  lettura {piu_probabile, probabilita, probabilita_piani}, corsa {vc, obiettivo_vc, tendenza,
  round_alla_vittoria, esito}, postura, motivo, contro_obiettivi [{territorio, punto_critico, attaccabile,
  peso, ruolo}], punti_critici_nemici, cambio_piano {round_in_crisi, cambi_fatti, valutazioni [{piano,
  tappe_raggiungibili, forze_vicine, tuv_nostro, tuv_nemico, stato, contrasto, punteggio}], scelto,
  motivo} (solo se valutato; altrimenti `cambio_piano_motivo`), cambi_piano}. L'ingresso ha anche `piano`.
  `PianoAttivo` usa solo l'ingresso del round corrente con lo stesso piano.
- **Diario**: nel blocco dello stato maggiore "Stato maggiore: postura X perche' ...; piano Y (cambiato da
  Z perche' ...)" (o "(in vigore dal round N)"), "Contro-obiettivi principali/secondari: ...",
  "Valutazione dei piani: ..." quando valutati; nell'intestazione del turno "(subentrato al round N)" e
  ", postura X".
- **Autoplay**: `Monitor` per round e alleanza `piano` (dell'ingresso), `postura`, `motivo_postura`,
  `contro_obiettivi`, `cambio`; `riepilogo.md` colonna "Postura" e sezione "Piani e posture";
  `riepilogo-generale.md` sezione "Piani e posture dello stato maggiore" (per partita: piani con round e
  motivo dei cambi, posture per tratti di round; per configurazione: cambi totali, partite con cambi,
  piano finale).

## Trasporti negli sbarchi del combattimento (step 3d-1-bis, solo Pro+)

`ai/proplus/TrasportiSbarco`, chiamata da `ProPlusTuning.checksLandingTransports`. Hard identica: hook nuovi
`ProTuning.checksLandingTransports` (default vuoto ⇒ regola "transports exposed" di Hard) e
`ProTuning.unloadZone` (default vuoto ⇒ rotta di Hard), chiamati in `ProCombatMoveAi.
removeTerritoriesWhereTransportsAreExposed` (dopo `calculateAmphibRoutes`, zone di scarico note) e in
`ProMoveUtils.calculateAmphibRoutes` (solo combattimento). Per Pro+ sostituisce la regola di Hard e le sue
eccezioni Pro+ (`keepsLandingWithExposedTransports`: regola obiettivo e testa di ponte, non più usate).
- Per ogni zona dove finiscono trasporti e navi da bombardamento dello sbarco: difensori = navi alleate già
  lì e non usate nel combattimento + quelle in arrivo + navi da guerra del partner che raggiungono la zona
  di raccolta comune nel suo turno (come le soste sicure); win% nemica = miglior attacco della stima di Hard
  con i nemici reali (step 3c, la stessa delle soste), 100% senza navi da guerra; **perdita attesa = win% ×
  TUV delle mie unità navali nella zona** (trasporti + scorta propria; il carico è già a terra).
- Guadagno = (valore della conquista + **valore strategico**) × win% + TUV atteso della battaglia (valore
  della conquista = quello di Hard in quel controllo: produzione + obiettivi nazionali, ×4 se capitale).
  **Valore strategico (chiusura dello step)** = `TrasportiSbarco.TUV_PER_WEIGHT` (**1.0**: 1 punto di peso
  = 1 TUV; scelto, non tarato: i pesi del file, 10-20, valgono 1-3 trasporti) × (peso del bersaglio come
  obiettivo della fase della nazione nel file dati, `PianoAttivo.pesoObiettivoFase` + peso come bersaglio
  attivo delle sue operazioni anfibie, `peso × 0.8^posizione`, `PianoAttivo.pesoBersaglioOperazione`; contro-
  obiettivi della postura esclusi). Es. Normandy per gli USA nel piano 1 dal R3: 0 + 15; United Kingdom per la
  Germania in B R2-3: 20 + 20. L'eccezione "critico" resta invariata. Diario: "... + valore strategico V x win
  W% (obiettivo P + operazione Q)".
- Tenuto se guadagno > perdita attesa, oppure se il bersaglio è obiettivo **critico** della fase
  (`PianoAttivo.isCritico`) e win% > desiderata (`ProPlusTuning.desiredWinPercentage`). Altrimenti, per
  ogni zona con perdita: le altre zone di mare confinanti col bersaglio, senza unità nemiche, raggiungibili
  in questo turno da tutti i trasporti della zona (carico a bordo o confinante con la zona di partenza),
  senza scramble nemico che li batte; scelta quella che riduce di più la perdita totale (perdita che resta
  nella zona lasciata, dove restano le navi da bombardamento, + quanto i trasporti aggiungono nella nuova).
  Se così il guadagno supera la perdita lo sbarco si fa da lì (`ProLandingTransports.unloadZones` ⇒
  `transportTerritoryMap` + `unloadZone` ⇒ la rotta del combattimento porta il trasporto carico in quella
  zona e scarica da lì; solo per lo sbarco su quel bersaglio), altrimenti è scartato.
- Diario: scartato "<territorio>: ... - trasporti esposti dopo lo sbarco: perdita attesa X TUV > guadagno Y
  (<zona>: win% nemica W% x T TUV (trasporti N, scorta M) = P; guadagno Y = conquista C x win W% + TUV
  battaglia S)[; scarico spostato: A -> B (n)][; zone alternative: B perdita P, ...]"; tenuto con perdita > 0
  "; trasporti dopo lo sbarco: perdita attesa X TUV <= guadagno Y (...)" o "; tenuto: obiettivo critico del
  piano, win W% > desiderata D%". Codici `transports_after_landing` / `kept_transports_after_landing`; JSON
  dell'attacco `trasporti_dopo_sbarco` (testo), `tuv_nemico_trasporti` = perdita, `valore_sbarco` = guadagno.
- Autoplay (`Monitor`, per nazione e round): `trasporti_persi` (trasporti sulla mappa dopo un passo e
  spariti dopo il successivo, qualunque causa), `tuv_trasporti_persi`, `tuv_sbarchi` (TUV perso dal
  difensore − attaccante nelle battaglie dove la nazione ha sbarcato truppe vere nel combattimento);
  `riepilogo-generale.md` sezione "Trasporti persi e sbarchi (step 3d-1-bis)": per configurazione e nazione
  trasporti persi per round (somma dei semi), totale, TUV trasporti persi, TUV sbarchi.

## Strumenti di ritardo (step 3d-2, solo Pro+)

**Dallo step CONTRATTO STRATEGICO il decisore a regole li tiene tutti spenti**: si accendono solo con le direttive
(`tattiche`), vedi "Contratto strategico". Il "Decisore a regole" descritto sotto e' ora `Tattiche.perRuoloEPostura`
(solo test).

Decisione di Matteo (8 nov): la parte deterministica fa il "COME" ed e' congelata; il "COSA e PERCHE'" passera' a un
decisore (regole oggi, LLM domani) tramite un contratto JSON (step successivo). Questo step aggiunge solo
**strumenti eseguibili accesi/spenti da interruttori**; nessuna formula di convenienza, solo paletti fissi nel file dati.
Hard identica (hook nuovo `ProTuning.dropsLandAttack`, default vuoto), piano N senza effetti (`contromosse: false` ⇒
nessuno strumento).
- **Interruttori** (`strategia/Tattiche`): struttura `tattiche_attive` nell'ingresso del round dello storico dello stato
  maggiore (`stato_strategico.tattiche_attive`, quindi salvata nella partita):
  `{"<strumento>": {"<nazione>": {"fronti": [], "perche": "..."}}}`; nazione assente = spento, `fronti` vuoto = tutti.
  Strumenti: `blocco_terrestre`, `ritirata_combattuta`, `blocco_navale`, `caccia_trasporti`. Un decisore futuro deve solo
  scrivere questa struttura. **Decisore a regole** (`Tattiche.decidi`, chiamato in `Contromosse.statoStrategico`): nazioni
  del campo `ritarda` del piano (Europe 1940: Russians nei piani alleati 1, 2, 3) con "ruolo ritarda nel piano X" + tutte
  le nazioni dell'alleanza in postura RALLENTAMENTO o DIFENSIVA ("postura X"). Letti da `PianoAttivo.tattica(strumento,
  fronte)`. Diario: riga "Tattiche attive: ..." nel blocco dello stato maggiore (solo se qualcuno e' attivo).
- **Dati**: per piano `ritarda` [nazioni] e `zone_di_passaggio` [zone di mare verso le zone di raccolta del piano, dove il
  NEMICO che lo legge mette i blocchi navali; B 111/110, C 111, D 96/98, 1 104/108/103, 2 102/103, A/N/3 nessuna,
  verificate sull'XML]; `stato_maggiore.ritardo` = `max_blocchi` 3, `distanza_percorso` 4, `max_navi_blocco` 2,
  `soglia_lettura_blocco` 0.5, `peso_caccia_trasporti` 15, `distanza_caccia` 2 (`Strategia.Ritardo`, scelti, non tarati).
- **Esecuzione** (`proplus/StrumentiRitardo`, in `ProPlusAi` nel non combattimento reale dopo rientro e operazioni
  anfibie, prima del non combattimento di Hard; unita' tenute con `heldUnits`):
  1. *Blocco terrestre*: territori propri confinanti con unita' mobili nemiche (terra, movimento >= 2: carri/mech; nemici
     reali dello step 3c) e un passo piu' vicini a un territorio chiave proprio (capitale, fabbrica, VC) entro
     `distanza_percorso` (via terra, territori non neutrali) ⇒ 1 fanteria ("lasciata" se c'e' gia' una fanteria propria,
     "portata" da un territorio vicino se il territorio e' vuoto). Prima i vuoti, poi distanza, capitale. Max 3 per turno.
     Fonte: un territorio senza minaccia reale, oppure dove il miglior attacco nemico **senza quella fanteria** vince
     comunque <= 100 − minima del livello effettivo (interpretazione di "mai togliendo difese a territori con minaccia
     reale": la lettura letterale vietava ogni fanteria vicino al fronte russo, minacciato ovunque dai carri).
  2. *Ritirata combattuta*: per ogni fronte attivo lo stack proprio con piu' TUV di terra; se il miglior attacco nemico
     vince > 100 − minima del livello effettivo, le unita' (non i blocchi) arretrano verso il territorio chiave proprio piu'
     vicino (altro dal suo; mai dalla propria capitale), fin dove arrivano. Combattimento (`ProPlusTuning.dropsLandAttack`,
     codice `delay_not_isolated`): attacchi di terra (non sbarchi, non logoramenti) tenuti solo se win% >= desiderata del
     bersaglio e contrattacco nemico <= 100 − minima (nessun contrattacco = isolato).
  3. *Blocco navale*: piano nemico piu' probabile letto > `soglia_lettura_blocco` ⇒ nelle sue `zone_di_passaggio`, in
     ordine, al massimo 2 sottomarini/cacciatorpediniere propri ("lasciata" se gia' li', altrimenti la nave raggiungibile
     piu' vicina; mai la scorta di trasporti propri; zona occupata dal nemico saltata).
  4. *Caccia ai trasporti* (`PianoAttivo.getCaccia`, calcolata a ogni `PianoAttivo`): zone con trasporti di nazioni in
     guerra con me, carichi o nelle zone di raccolta delle operazioni del piano nemico letto, con navi da guerra proprie
     (via mare) o aerei propri entro `distanza_caccia` ⇒ contro-obiettivi con peso `peso_caccia_trasporti` (bonus di
     priorita', desiderata >= livello base come gli altri contro-obiettivi).
- **Registro** `"Pro+ ritardo: <nazione>"` (proprieta' JSON, scritta nel non combattimento): {round, blocchi, navi,
  ritirata (id unita'), caccia [{zona, trasporti (id, visti a inizio turno)}]}; al turno dopo le unita' sparite = perse.
- **Diario**: tra le operazioni anfibie "Ritardo: <strumento> in X - <modo> <unita'>; <dettaglio> (perche' attivo:
  ...)", "Ritardo: caccia_trasporti in Z - N trasporti nemici carichi/in zona di raccolta (...)", "trasporti nemici
  affondati con la caccia: N", note (perche' niente), "Ritardo, totale del turno: ... perse negli strumenti dal turno
  prima: N (blocchi, navi, ritirata)". JSON evento `ritardo` {azioni, caccia, affondati_caccia, persi_dal_turno_prima,
  note}. Log AI "[Pro+] Ritardo: ...".
- **Autoplay** (`Monitor`): `territori_chiave` = cadute/riprese di Novgorod, Russia, Volgograd (proprietario non alleato
  del proprietario originale); per nazione e round `ritardo` {blocchi_messi/persi, navi_messi/persi, ritirata_messi,
  zone_caccia, affondati_caccia} letto dal registro (persi = id scomparsi dalla mappa dopo un passo; affondati = trasporti
  cacciati gia' spariti quando il registro e' scritto, cioe' dopo le battaglie del cacciatore). `riepilogo-generale.md`
  sezione "Strumenti di ritardo e fronte russo (step 3d-2)": per partita round di caduta, capitali cadute,
  messi/persi, ritirata, caccia; per configurazione partite con caduta e round medio; reddito russo per round.

## Contratto strategico (step CONTRATTO STRATEGICO, solo Pro+)

Decisione di Matteo: la parte deterministica fa il COME (congelata), un DECISORE fa il COSA/PERCHE'. Oggi il decisore
sono le regole (stato maggiore 3a-3d), domani un LLM (Claude Haiku 5.5; secret GitHub `ANTHROPIC_API_KEY` gia'
creato, **non usato** in questo step). Schema completo, esempi reali e regole di validazione:
**`proplus/strategia/contratto-v1.md`** (+ `proplus/strategia/esempi/`).
- **Flusso** (`Valutazione.inizioRound`, primo turno Pro+ dell'alleanza nel round, dati reali): avanzamento e lettura
  (`valuta`) → controllo di cambio delle regole (`Contromosse.cambio`, senza effetti) → `Decisore.Contesto` →
  `DecisoreRegole.esito` (sempre: e' anche il ripiego; simula il cambio senza scriverlo e calcola postura/contro-obiettivi
  con il piano scelto, identico a prima) → `Ingresso.of` (stato strategico) → decisore dell'alleanza (`Decisori.per`) →
  `Direttive.valida` (solo se non sono le regole) → applicazione: cambio piano (`StatoMaggiore.cambia`, stallo
  ripartito, `valuta` col nuovo piano), `stato_strategico` (delle regole; per un altro decisore ricalcolato col piano
  scelto e con postura/motivo/contro-obiettivi/tattiche presi dalle direttive), voce dello storico con `direttive`,
  `decisore` {nome, ripiego, avvisi}, `guerre`.
- **Lettori**: `PianoAttivo` legge SOLO le direttive del round (stesso piano), altrimenti `DecisoreRegole.base` (ordini
  base del file: stesso comportamento di prima della prima decisione): postura, `modificatore(fronte)` = rischio
  delle direttive (+ −1 in DIFENSIVA come prima), obiettivi = `bersagli` non "contromossa" (filtrati per nazione) +
  bersagli delle operazioni lanciate + contro-obiettivi (`origine: contromossa`) + caccia; operazioni senza quelle
  "annulla", `isInAttesa`/`isBersaglioInAttesa`/`perditaAccettata` (attendi e tetto delle perdite), lettura del nemico,
  tattiche (struttura di `Tattiche`, convertita dalla lista delle direttive). `pesoObiettivoFase` = bersagli non
  contromossa. `TrasportiSbarco`: bersaglio solo di operazioni in attesa ⇒ sbarco scartato ("operazione in attesa per
  direttiva"); perdita attesa <= perdita accettata (>0) ⇒ tenuto ("perdita entro il tetto della direttiva").
- **Strumenti di ritardo** (decisione di Matteo sul 3d-2): restano nel codice ma `DecisoreRegole` li tiene tutti spenti
  (`Tattiche.spente()`); la regola del 3d-2 (ruolo "ritarda" + postura) e' `Tattiche.perRuoloEPostura`, usata solo dai
  test. Si accendono solo con le direttive. Paletto del 3d-2 sul prelievo di fanteria invariato. Registro dei blocchi:
  `blocchi` = solo fanterie "portate", `blocchi_lasciati` a parte (le fanterie gia' nel territorio non contano come
  blocchi, ne' come perse); il diario scrive "N blocchi terrestri (piu' M fanterie lasciate dov'erano)".
- **Paletti** (qualunque decisore): guarnigione capitale (codice tattico), soglia minima assoluta 30%
  (`Direttive.SOGLIA_MINIMA_ASSOLUTA`, in `ProPlusTuning.attackMinWinPercentage`/`targetAttackMinWinPercentage`; i
  livelli hanno minime 45-65% quindi oggi non cambia nulla), perdita di trasporti accettata <= 30 TUV per alleanza per
  turno (somma ridotta in proporzione), max 2 cambi per partita e >= 3 round tra cambi (`Direttive.cambioNonAmmesso`,
  il piu' severo tra paletto e file dati).
- **Ingresso**: stime rapide senza dadi (`ProBattleUtils.estimateStrengthDifference`), battaglie da `BattleRecordsList`
  per riflessione (come il `Monitor`), ultimo rapporto delle operazioni nella proprieta' `Pro+ rapporti: <alleanza>`
  (scritta da `ProPlusAi` dopo le operazioni anfibie). Misurato in CI (288 ingressi, R1-R12): media 6.969 caratteri
  (~1.742 token a 4 caratteri/token), max 8.900; budget ~5.000 token.
- **File**: `stato-R<N>-<alleanza>.json` e `direttive-R<N>-<alleanza>.json` nella cartella della partita del diario
  (`DiaryRecorder.contractFiles`, solo con diario attivo; l'autoplay lo ha sempre). `DecisoreFile` legge gli stessi nomi.
- **Scelta**: pannello "Impostazioni Pro+" (riga "Decisore" per alleanza: regole/file + percorso + "Sfoglia..."),
  autoplay `--decisore`; proprieta' `Pro+ decisore: <alleanza>` / `Pro+ decisore file: <alleanza>`.
- **Golden Pro+** (`ai/pro/ProPlusGoldenTest`, `proplus/proplus-golden-global1940-europe.txt`, registrato su f4446c8+
  punto a, prima del refactoring): Global 1940 rinominata Europe 1940, Asse A / Alleati 1; stato maggiore R1-R10 su una
  mappa cambiata da un copione (cadute sovietiche, Francia, Egitto, Romania: IN CRISI, DIFENSIVA e cambio 1 → 2 al R10)
  con quello che la tattica legge per ogni nazione; per Germans/Russians/British R1 attacchi vincibili con soglie e
  bonus del piano, valori d'attacco, ordini di acquisto e ammassamento. **Non** registra la mossa completa ne' gli
  acquisti finali: l'ordine delle mosse dipende da HashSet di unita' (UUID diversi a ogni caricamento della mappa, anche
  in Hard) e gli acquisti usano `Math.random`. Test facoltativo `realEurope1940RoundOneReport` con
  `PROPLUS_GOLDEN_EUROPE_XML` (mappa reale, R1): report in `build/proplus/` da confrontare a mano.

## Stratega LLM (step INTEGRAZIONE LLM, solo Pro+)

Decisione di Matteo: lo stratega LLM decide il COSA/PERCHE' (direttive del contratto v1), la parte deterministica
esegue. Provider scelto: Claude Haiku 5.5. Hard identica, golden Hard e golden Pro+ (`DecisoreRegole`) identici:
con la modalita' "regole" (default di ogni partita finche' l'utente non sceglie altro nel pannello) nessun codice
nuovo cambia una decisione.
- **Modalita'** per alleanza (`Modalita`, proprieta' `Pro+ decisore: <alleanza>` = `auto` | `claude` | `chiave` |
  `file` | `regole`): Automatico = Claude Code se installato e collegato, poi la chiave API, poi le regole; Claude
  installato; Chiave API; File (`DecisoreFile`); Solo regole. Partita senza proprieta' = modalita' preferita del
  profilo utente (`regole` finche' non se ne sceglie una LLM nel pannello). `Decisori.fabbricaLlm` = punto di
  iniezione dei test (trasporti finti).
- **Trasporti** (`DecisoreLlm.Trasporto`, provati in ordine; ognuno puo' dire "non disponibile" senza chiamare):
  1. `ClaudeCode`: comando `claude` cercato nel PATH (come `where`) e in `%USERPROFILE%\.local\bin\claude.exe`
     (installer nativo), `%APPDATA%\npm\claude.cmd` (npm, cartella di default di npm: non scritta nei documenti di
     Claude Code), `~/.local/bin`, `/usr/local/bin`, `/opt/homebrew/bin`; verificato UNA volta per avvio del gioco con
     una chiamata breve (timeout 60 s). Chiamata: `claude -p --output-format json --model haiku --tools ""
     --strict-mcp-config --no-session-persistence --max-turns 2 --system-prompt-file <parte fissa>`, stato del round
     su stdin, cartella temporanea, timeout 120 s; risultato in `result`, token in `usage`, `total_cost_usd` = stima
     del client (compresa nell'abbonamento). Flag verificati sui documenti ufficiali (code.claude.com, 8 nov):
     `--json-schema` esiste ma non e' usato (lo schema passato come argomento su Windows/`.cmd` rischia problemi di
     virgolette: lo schema e' nella parte fissa del prompt). Claude gratuito non include Claude Code ⇒ la verifica
     fallisce ("non collegato"/"non include Claude Code") e si passa alla chiave.
  2. `ClientApi` con la chiave del fornitore scelto (`Fornitore`): **Anthropic** `claude-haiku-5-5` (ID e prezzi dalla
     documentazione ufficiale Anthropic: $0,10/$0,50 per MTok fino a 100K token di prompt; cache letta 0,1x,
     scrittura 1h 2x), Messages API `anthropic-version: 2026-10-21`, parte fissa in `system` con `cache_control`
     ephemeral **ttl 1h** (round di una partita umana anche a minuti di distanza), output strutturato
     `output_config.format` json_schema (`PromptStratega.schema()`, strict: oggetti chiusi, tutti i campi
     obbligatori), `effort: low`, **niente temperature** (Haiku 5.5 rifiuta i parametri di campionamento ⇒ la
     Fantasia per Haiku e' solo nel prompt); **DeepSeek** `deepseek-v4-flash` e **Groq** `openai/gpt-oss-120b` via chat
     completions compatibili OpenAI (`response_format` json_object, `temperature` = 0,2 × Fantasia). DeepSeek/Groq
     **non verificati sui siti ufficiali** (bloccati dal proxy della sandbox): `deepseek-chat` ritirato il 24/7/2026
     (nota ufficiale vista nei risultati di ricerca), prezzi da fonti terze, marcati nel file dei prezzi. Timeout 45 s,
     1 nuovo tentativo su errore di rete, timeout, 429, 5xx. Errori in italiano chiaro (401 chiave rifiutata, 402/400
     credito esaurito, 429 quota, 404 modello); la chiave e' mascherata in ogni testo d'errore.
  3. Nessun trasporto riuscito ⇒ `Decisore.NonDisponibile` ⇒ `Valutazione` usa le regole per quel round con
     l'avviso "stratega non disponibile (<motivi>): decidono le regole". Risposta non JSON ⇒ stesso ripiego
     (`json_non_validi`). Direttive non valide (piano non ammesso, postura sconosciuta) ⇒ ripiego di `Direttive.valida`.
     La partita non si blocca mai: il turno AI aspetta al massimo 120 s (Claude Code) + 2 × 45 s (chiave).
- **Prompt** (`PromptStratega`, risorsa versionata `game-core/src/main/resources/proplus/strategia/prompt-stratega-v2.md` (era v1),
  in italiano; il commento iniziale e' tolto): parte fissa = ruolo, regole chiave della mappa (verificate sull'XML:
  vittoria Asse 8 VC + una capitale, Alleati Roma + Berlino; obiettivi nazionali con i valori; USA in guerra al R3,
  URSS dal R4 o se attaccata/Londra presa), piani del file dati senza logistica (`PromptStratega.piani`: fasi,
  tappe, nomi degli indizi della firma, punti critici, operazioni, subentri, contrasta, ruoli), strumenti (operazioni
  lancia/attendi/annulla, perdita accettata, strumenti di ritardo SPENTI salvo accenderli, quando servono), paletti
  (`PromptStratega.paletti()` dai valori di `Direttive`), istruzioni (stato maggiore, rischi calcolati se la corsa lo
  chiede, cambia piano solo se fallimentare, max 3 frasi) e schema. Misurata in CI: **12.385 token** (Haiku 5.5),
  uguale per entrambe le alleanze ⇒ in cache (scritta una volta, poi letta anche tra job diversi entro 1 ora). Parte variabile = livello di Fantasia (testo per 1-5) + stato strategico JSON compatto.
- **Risposta** (`UscitaLlm`): JSON estratto dal testo (tollera blocchi di codice), schema dello stratega
  (`rischio`/`ruoli` come liste di coppie, fronte vuoto = tutti) convertito nelle direttive v1, poi
  `Direttive.valida` + paletti come per ogni decisore. `UscitaLlm.controlla` = controllo offline sullo stato (piano
  ammesso, postura, fronti, operazioni, pesi, tetto, frasi) usato dal test di qualita'.
- **Chiamate**: una per alleanza a inizio round (prima nazione Pro+ dell'alleanza, `Valutazione.inizioRound`).
  **Sincrona**: la richiesta "in background finche' non servono le direttive" non da' vantaggi qui, perche' la prima
  nazione le usa subito (acquisti) e un anticipo al turno prima userebbe uno stato vecchio di un turno nemico. Tetto
  per partita `Pro+ stratega tetto chiamate` (default 40, pannello; ogni chiamata a un fornitore conta, anche fallita).
- **Uso e costo** (`Uso`, proprieta' `Pro+ stratega uso` JSON: chiamate, errori, json_non_validi, token ingresso/
  uscita/cache letta/scritta, ms, `costo_usd`, `costo_abbonamento_usd` per Claude Code, `per_fonte`). Prezzi in
  `prezzi-stratega.json` (modificabile, con la fonte di ogni prezzo).
- **Diario**: ogni round "Stratega: <regole | file | Claude Code | Haiku | DeepSeek | Groq> - postura X, piano Y,
  <motivazione>" (anche con le regole); con lo stratega LLM "Stratega, uso della partita finora: N chiamate (...),
  T token (...), costo stimato $C" (l'ultimo round = totale della partita). Voce dello storico: `decisore.fonte`
  (non regole) e `decisore.uso` (LLM).
- **Chiavi** (`ProfiloStratega`): `<home>/.triplea-proplus/stratega.properties` (permessi solo proprietario dove il
  file system li ha; cartella spostabile con `-Dproplus.stratega.cartella`, test), chiave per fornitore, fornitore,
  modalita' preferita. Mai nelle proprieta' della partita, nel diario, nei log, nella cartella del gioco. Senza chiave
  salvata vale la variabile d'ambiente del fornitore (`ANTHROPIC_API_KEY`, `DEEPSEEK_API_KEY`, `GROQ_API_KEY`: CI).
  `chiave-stratega.txt` accanto al gioco (prima riga non commento; anche `groq=...`) importato all'apertura del
  pannello se manca una chiave per quel fornitore (riconosciuto dal prefisso `sk-ant-`/`gsk_`/`sk-`), poi il pannello
  chiede se cancellarlo; e' nel `.gitignore`.
- **Pannello** (`ProPlusSettingsDialog` + `StrategistPanel`): per alleanza riga "Stratega" (al posto di "Decisore") +
  cursore "Fantasia" (1-5, default 2, `Pro+ stratega fantasia: <alleanza>`); sezione "Stratega (LLM)": stato rilevato
  (in background: "✓ Claude Code trovato" / "✗ Claude non trovato – uso la chiave Haiku (••••1234)" / "... uso le
  regole"), fornitore, campo chiave (password) + "salvata: ••••1234", Salva / Prova connessione (chiamata minima,
  esito "✓ Connessione riuscita: Haiku risponde (1,2 s)" o il motivo) / Rimuovi, tetto chiamate. OK salva anche il
  fornitore e, se un'alleanza usa una modalita' LLM, la rende la preferita delle partite nuove.
- **Autoplay**: `--decisore <alleanza>=llm` (= Chiave API, fornitore del profilo o Anthropic, chiave da
  `ANTHROPIC_API_KEY`); `riepilogo.json` `stratega` per alleanza (decisioni, fonti, ripieghi con i primi motivi,
  cambi piano, posture, strumenti accesi, operazioni lancia/attendi/annulla, uso); riga nel `riepilogo.md`;
  `riepilogo-generale.md` sezione "Stratega LLM" (una riga per alleanza LLM con chiamate e costo per livello, totali
  per livello e per partita, VC finali medie LLM contro regole con gli stessi piani) e "Rischio effettivo medio per
  nazione" (tutte le partite Pro+); le configurazioni con decisore non regole hanno il suffisso
  "(llm)" e non si mescolano con quelle a regole. Comando `qualita <cartella> [--n 30] [--output DIR] [--fornitore
  anthropic] [--fantasia 2] [--livello stratega|ordini]` (`TestQualita`; `ordini` = ingressi
  `ordini-ingresso-R*-*.json`, prompt e controllo `UscitaLlm.controllaOrdini` degli ordini del turno): N stati `stato-R*-*.json` presi a intervalli regolari, prompt vero, %
  JSON validi al primo colpo (letto + piano ammesso + postura nota), % limitate dai paletti (tra le valide), latenza
  media, token, costo, 5 motivazioni; `qualita.md` + `qualita.json`; senza chiave esce 0 senza chiamare.
- **Paletto trasporti per alleanza** (punto a): vedi `TettoTrasporti` e `contratto-v1.md` (somma di tutti gli sbarchi
  dell'alleanza nel round, prima era per singolo sbarco).
- **Due livelli (step LLM-2)**: (a) **Stratega** = la chiamata per alleanza a inizio round (sopra); (b) **Ordini del
  turno** (`OrdiniTurno`, chiamato da `ProPlusAi.logPhase` subito dopo `Valutazione.inizioRound`, una volta per
  nazione e round): solo con modalita' LLM, nazione con PU > 0, non in `nazioni_senza_ordini` (French). Ingresso
  `Ingresso.ordiniTurno` (~3.000 caratteri), "cosa e' cambiato" calcolato contro un'istantanea salvata dopo ogni
  decisione dell'alleanza (proprieta' `Pro+ istantanea: <alleanza>`: battaglie viste, proprietari, TUV nemico e
  alleato per territorio; scritta anche con le regole se il diario e' attivo, per i file d'ingresso del test di
  qualita'). Prompt `prompt-ordini-turno-v1.md` (`PromptOrdini`, piu' corto: niente piani, le direttive sono
  nell'ingresso), schema `ordini-turno-v1` strict, stessi trasporti e fallback (`DecisoreLlm.ordini`), fusione e
  paletti in `Direttive.applicaOrdini`. Diario: "Ordini del turno <nazione> (<fonte>): bersagli ...; operazioni ...;
  tattiche ..." + motivazione, avvisi, uso; evento `ordini_turno`; file `ordini-ingresso-R<N>-<nazione>.json` e
  `ordini-R<N>-<nazione>.json`. Uso per livello (`Uso.per_livello.stratega|ordini`: chiamate, errori, token, costo),
  tetto per partita **100** (totale dei due livelli).
- **Rischio per nazione** (schema direttive v1.1, vedi `contratto-v1.md`): `Direttive.rischio(nazione, fronte)`,
  `PianoAttivo.modificatore` usa quello della propria nazione; ampiezza `ampiezza_rischio` nel file dati (1);
  `DecisoreRegole` scrive ancora v1 (golden Pro+ identico). Diario: tabella del rischio effettivo per nazione e
  fronte; storico `rischio_effettivo`; autoplay `rischio_medio` per nazione.
- **Prompt stratega v2** (`prompt-stratega-v2.md`, sostituisce v1): + ordine dei turni GER → URSS → USA → UK → ITA →
  FRA con le finestre di coordinamento (Italia → Germania quasi sicura; Germania → Italia il nemico reagisce; USA
  prima di UK), rischio per nazione, e il significato di "lancia" chiarito (il motore sbarca solo se conviene; la
  stima della perdita dei trasporti non e' un divieto), dopo che nel primo autoplay Haiku teneva Overlord in attesa 44
  volte su 46. Le stesse finestre sono nel prompt degli ordini. Niente "attacco di logoramento" ne' missioni congiunte
  (step successivo).
- **Prompt v3 / ordini v2 (step LLM-3)** (`prompt-stratega-v3.md`, `prompt-ordini-turno-v2.md`): "attendi" =
  RINVIO DELIBERATO anche a flotta pronta (finta, altro fronte, partner), da motivare; scorta e ammassamento li fa
  gia' il motore con "lancia" (tolto "aspettare la scorta"). Ingresso: per operazione `round_in_attesa` (round di
  fila in attesa: decisione strategica "attendi" non lanciata da ordini del turno, o ordini "attendi";
  `Ingresso.roundsWaiting`) e da 3 round `avviso` "operazione X in attesa da N round: motivare o lanciare".
  Dottrina di Matteo in "Come decidere" (rallentare = avanzare, difesa solo su minaccia reale, territori bonus /
  obiettivi nazionali propri, DIFENSIVA solo con rischio concreto). Ingresso `obiettivi_nazionali`
  (`Ingresso.obiettiviNazionali`: obiettivi della alleanza o della nazione con valore in PU per turno, territori
  richiesti/tenuti/mancanti, solo quelli definiti da proprieta' di territori e a <= 3 conquiste; quelli con condizioni
  composte, es. Germania "Novgorod o Volgograd o Russia", non compaiono).
- **Modello alternativo** (solo autoplay): `ClientApi.MODEL_PROPERTY` (`proplus.stratega.modello`) / `--decisore
  Allies=llm:claude-sonnet-5-5`; nome nel diario dalla famiglia del modello ("Sonnet"), prezzo dal suo ID in
  `prezzi-stratega.json` (Sonnet 5.5 $2/$10 per MTok, cache letta 0,20, scrittura 1h 4,00; ID e prezzi dalla
  documentazione ufficiale Anthropic). Non usato per i giocatori.

## Ablazione (step ABLAZIONE)

Interruttori on/off dei componenti Pro+ per misurare quanto vale ciascuno contro Hard. **Default = tutto acceso =
comportamento di prima** (nessuna proprieta'). Proprieta' della partita `Pro+ ablazione: <alleanza>` = id separati da
virgola, oppure `nudo` (tutti); id sconosciuti rifiutati da `Ablazione.parse`/`set` (in un salvataggio sono ignorati).
Letta a ogni uso (`Ablazione.attivo`, cache per testo). Autoplay: `--ablazione Axis=rallentamento+rischio_fronte`. Nessun
pannello (solo autoplay/test).

| Id | Componente | Dove si spegne | Spento = |
|---|---|---|---|
| `piano` | stato maggiore (piani, fasi, obiettivi, guerre, posture, valutazione, ordini) | `PianoAttivo.of` vuoto; `ProPlusAi.logPhase` salta fixPlan/Valutazione/OrdiniTurno; acquisti delle operazioni e caccia saltati | nessun piano (come senza file di strategia) |
| `rallentamento` | postura RALLENTAMENTO | `Contromosse.statoStrategico` | corsa pari ⇒ OFFENSIVA, nessun contro-obiettivo |
| `strumenti_ritardo` | blocchi, ritirata, blocco navale, caccia | `PianoAttivo` (tattiche nulle) | spenti qualunque decisore (con le regole lo sono gia') |
| `rischio_fronte` | modificatore di rischio per fronte | `PianoAttivo.modificatore` = 0 | ogni fronte al livello del cursore (DIFENSIVA −1 resta) |
| `bersagli_flotta` | obiettivi `flotta` del piano (A/B/C R1-2: flotta British) | `PianoAttivo` (obiettivi) | nessun obiettivo flotta, il resto invariato |
| `trasporti_sbarco` | regola Pro+ dei trasporti dopo lo sbarco (`TrasportiSbarco`) | `checksLandingTransports`/`unloadZone`/`keepsLandingWithExposedTransports` | regola "transports exposed" di Hard |
| `soste_sicure` | soste sicure e scorta delle operazioni anfibie | `OperazioniAnfibie.safety` | ogni zona sicura, nessuna scorta richiesta (il rientro resta) |
| `tetto_trasporti` | paletto 30 TUV per alleanza/round | `TettoTrasporti.residuo` | nessun tetto (con le regole la perdita accettata e' 0: nessun effetto) |
| `logistica_acquisti` | ordini d'acquisto del piano + acquisti delle operazioni | `purchaseOrders` | acquisti di Hard |
| `ammassamento` | ammassamento del piano | `stagingOrders` | non combattimento di Hard |
| `operazioni_anfibie` | operazioni anfibie del piano | `PianoAttivo` (operazioni) | nessuna operazione |
| `testa_di_ponte` | testa di ponte e stesso bersaglio | `keepsBridgehead`, `dropsLanding` | regole di Hard |
| `victory_city` | VC nei valori e nella priorita' | `valuesVictoryCities`, `hasVictoryCityWinCondition`, `attackPriorityVictoryCityWeight` (con `ProPlusTuning.attach`) | come Hard |
| `soglie_rischio` | soglie d'attacco del cursore e per bersaglio | `attackWinPercentage`, `attackMinWinPercentage`, `targetAttack*`, `attackWinPercentageCeiling` | 90/65 di Hard (dadi normali) |
| `non_conviene` | regole 2d "tenuto da Pro+" | `keepsAttackNotWorthIt`, `keepsLandingWithExposedTransports` | regole di Hard |
| `guarnigione_capitale` | guarnigione 25% | `capitalGuardThreshold` | nessuna |
| `minaccia_reale` | solo nemici reali (3c) | `realEnemies`, `garrisonNeeded` | tutti i non alleati, presidi sempre |
| `scramble_sbarchi` | controllo scramble (Hard Beta) | `checksLandingScramble` | Hard |
| `strafe_anfibio` | scarto degli strafe anfibi | `dropsAmphibiousStrafe` | mai |

- `ProPlusTuning.attach(ProData)` (chiamato da `ProPlusAi` dopo `super`): i metodi di `ProTuning` senza `ProData`
  (VC, scramble) leggono gli interruttori della partita/nazione del `ProData` proprietario; senza proprietario = acceso.
- **"Pro+ nudo" = Hard** (`ai/pro/NudoHardTest`): un turno intero (piano d'attacco della simulazione d'acquisto, acquisto
  con `SeededPurchaseChoice`, combattimento, non combattimento) con dadi seminati, in cui OGNI risposta di
  `ProPlusTuning` nudo e' confrontata con quella di `ProTuning.DEFAULT` alla stessa domanda (proxy dell'interfaccia):
  0 differenze su >1000 domande per Asse e Alleati; con tutto acceso le differenze ci sono (controllo). Il confronto
  letterale di due turni NON e' un test affidabile: la stessa Hard, ripetuta sulla stessa partita nella stessa JVM dopo
  altri turni, cambia piano d'attacco 8 volte su 8 (ordine di insiemi hash di oggetti nuovi); misurato scrivendo il
  test, prima versione fallita una volta per questo.

## Operazioni congiunte (step PROVA DI VALORE)

Prova di valore del coordinamento d'alleanza: due operazioni **scritte a mano** (bersaglio e ruoli fissi, niente LLM, niente
pianificatore generale), sopra Pro+ "nudo". **Spente di default**: si accendono solo con la proprieta' della partita
`Pro+ operazione congiunta: <alleanza>` = id (`OperazioneCongiunta.set`, autoplay `--operazione`); nessun pannello.
Hard identica: unico hook nuovo nelle classi Pro `ProTuning.combatHeldUnits` (default `Set.of()`), applicato in
`ProCombatMoveAi` subito dopo `populateAttackOptions` (`ProMyMoveOptions.removeUnits`, no-op con insieme vuoto).

**Verifiche di regola** (codice + XML `ww2eur40_2nd_edition.xml`, fatte prima del codice):
- Atterraggio aerei: `AirMovementValidator.canLand` vieta solo i territori di terra **conquistati nel turno in corso**
  (`BattleTracker.wasConquered`); il `BattleTracker` si azzera a fine turno di ogni nazione (`AbstractEndTurnDelegate`:
  `getBattleTracker().clear()`). Quindi un territorio preso da un alleato **prima, nello stesso round** e' un normale territorio
  alleato: gli aerei UK possono atterrare in Normandy presa dagli USA nello stesso round; quelli della nazione che lo conquista
  no (stesso turno). Aerei su portaerei alleate: ammessi (`Allied Air Independent` assente = falso ⇒ caccia alleati come carico
  della portaerei, `TransportTracker`), difendono la flotta; aerei a terra coprono le zone di mare adiacenti con lo scramble da un territorio con
  aeroporto (`Scramble Rules In Effect`, `Scramble To Sea Only` veri). Un aereo non puo' restare in una zona di mare senza portaerei.
- Ordine di turno (XML, `<step>`): Germans, Russians, Americans, British, Italians, French ⇒ GER apre e ITA chiude nello stesso
  round (in mezzo RUS, USA, UK: **il Regno Unito puo' rinforzare l'Egitto tra le due tappe**); USA apre e UK chiude nello stesso
  round **senza turni dell'Asse in mezzo**.
- **USA neutrali** con Germania e Italia e **non alleati** con UK a inizio partita: guerra possibile dal **R4**
  (`conditionAttachmentTurn4OrHigher`) o prima se l'Asse dichiara guerra; prima della guerra divieto di movimento in 65, 66, 85,
  86, 88, 90, 103, 106, 107, 116, 117, 120, 122 Sea Zone (`movementRestrictionTerritories`) ⇒ nessuna rotta 101 → 110 prima della
  guerra. Rotta 101 → 102 → 103 → 104 → 110 = 4 zone = 2 turni ⇒ esempio R4 preparazione, R5 posizionamento, R6 esecuzione.
- "France" (Parigi) **non ha coste**: il bersaglio dello sbarco e' **Normandy Bordeaux** (110 e 105 Sea Zone).
- Egitto (Cairo, VC): confina con Alexandria, Trans-Jordan, Anglo Egyptian Sudan, 81 e 98 Sea Zone; **non con Tobruk** (Libya –
  Tobruk – Alexandria – Egypt). Raggiungibile dai tedeschi: **aerei** (da Western Germany un caccia arriva a Tobruk in un turno, 4
  zone; da Tobruk/Libya colpisce l'Egitto e rientra), **trasporti italiani** (unita' tedesche caricate nel turno tedesco, spostate
  nel turno italiano, sbarcate nel turno tedesco dopo: 2 round, regola gia' verificata in OPERAZIONI ANFIBIE), via terra solo dalla
  Libia/Tobruk (unita' tedesche in Africa = solo con i trasporti italiani). **Trasporti tedeschi propri: non praticabile**
  (fabbriche/porto solo sul Mare del Nord, Gibilterra britannica). Obiettivo nazionale tedesco +5: almeno 1 unita' di terra
  tedesca in Egypt chiunque lo controlli (`conditionAttachment_Germans_4_unit_presence`). Nessuna regola rende impossibile le due
  operazioni; per l'Egitto la "unita' tedesca in Egypt" e' possibile solo con unita' tedesche gia' in Africa (non nella prova:
  in nudo nessuno le porta).

**Dati** (`operazioni-congiunte.json`, valori scelti, NON tarati): per operazione `bersaglio`, `apre`, `chiude`, `raccolta_apre` /
`raccolta_chiude` (dove si radunano: aerei tedeschi Tobruk/Libya/Alexandria; truppe UK/USA United Kingdom), `zona_raccolta`
(Francia: 110 Sea Zone), `accesso` (Egitto: Alexandria, priorita' d'attacco per l'Italia mentre si prepara), `porto` (navetta:
Southern Italy 97/95 → Tobruk 96 per l'Italia; Eastern United States 101 → United Kingdom 110 per gli USA), `aerei_apre` (4),
`round_min`/`round_max` (Egitto 2-6, Francia 5-9), `trasporti` (USA 4, UK 2), `guerra_da_round` (USA 4), `riserve` (acquisti
obbligati per nazione: Italia 2 terra; USA 1 trasporto + 3 terra, quota 50%; UK 1 trasporto + 2 terra, quota 30%), `valore` 25
(PU del bersaglio nel valore atteso), `tetto_perdita` 40 TUV, `tolleranza_difensori` 0,25, `soglia_chiusura` 50%, `max_rinvii` 2.

**Meccanica** (`OperazioniCongiunte`, solo partita reale, mai nelle simulazioni; chiamata da `ProPlusAi` prima della mossa di Hard
della fase; le unita' mosse/tenute vanno a `heldUnits` (non combattimento) e `combatHeldUnits` (combattimento)):
- **Avvio** (acquisto del primo turno di una nazione dell'operazione): round obiettivo dalla fattibilita', poi fisso: Egitto =
  1 + max(round degli aerei tedeschi alla raccolta (2° aereo piu' vicino), round delle truppe italiane accanto al bersaglio:
  distanza via terra − 1, +1 se un territorio di accesso e' nemico, **0 se trasporti italiani in portata possono sbarcare**);
  Francia = round di guerra possibile + traversata (zone / 2). Limitato a `round_min`-`round_max`.
- **Stati**: PREPARAZIONE (round < obiettivo − 1), POSIZIONAMENTO (obiettivo − 1), ESECUZIONE (obiettivo), COMPLETATA, ANNULLATA.
- **Riserve**: `ProPlusTuning.purchaseOrders` restituisce gli acquisti obbligati della nazione (trasporti solo se sotto
  `trasporti`) finche' round < obiettivo; il non combattimento porta le riserve alla raccolta: aerei tedeschi verso Tobruk/Libya
  (max `aerei_apre`, territorio sicuro: win% nemica ≤ 40 = 100 − minima Equilibrato), truppe italiane verso il territorio di
  raccolta piu' vicino al bersaglio, navetta dei trasporti (carico al porto solo se esiste una sosta sicura, sosta piu' lontana
  sicura con le navi da guerra della zona come scorta, scarico all'arrivo), flotta riunita nella zona di raccolta (trasporti +
  navi da guerra entro 2 zone, se la zona e' sicura). In POSIZIONAMENTO le unita' gia' in raccolta restano fuori dagli attacchi di
  Hard (`combatHeldUnits`).
- **Tappa 1** (chi apre, combattimento del round obiettivo): attaccanti = unita' in portata secondo le opzioni d'attacco di Hard
  (aerei solo dove possono atterrare) + sbarco dalla zona di raccolta (carico dal territorio di raccolta sui trasporti propri in
  zona; dalla capitale solo se resta sotto il 25%); stima con ritirata degli aerei se solo aerei; difensori residui stimati +
  rinforzi nemici dei turni in mezzo (opzioni di difesa nemiche di Hard); attaccanti stimati di chi chiude (opzioni alleate di Hard
  + portata propria a movimento pieno + sbarchi possibili). **Soglia d'alleanza**: presa = p1 + (1 − p1)·win2, valore atteso =
  presa × valore + swing TUV tappa 1 + (1 − p1) × swing tappa 2; esegue se valore atteso > 0 e perdita attesa ≤ tetto (anche con
  win% della battaglia bassa). Bersaglio senza difensori ⇒ tappa 1 "non necessaria" (conta come avviata). Bersaglio gia'
  dell'alleanza ⇒ **IN ATTESA** (round obiettivo +1 senza contare un rinvio) fino a `round_max`, poi ANNULLATA "superata".
- **Tappa 2** (chi chiude, stesso round): condizione (`condizioneTappa2`): difensori residui ≤ stima × (1 + tolleranza) (oppure
  win% ≥ 80), TUV perso nella tappa 1 ≤ tetto (registri di battaglia), win% ≥ `soglia_chiusura`, almeno un attaccante (stima con
  gli sbarchi che Hard potrebbe fare). Se ok: prima **le mosse dell'operazione** (unita' in portata secondo Hard + sbarco dalla
  zona di raccolta: carico dal territorio di raccolta e assalto nello stesso turno), poi **il combattimento di Hard forzato** sul
  bersaglio per le altre unita' (risposte dell'operazione: priorita' +1000, soglia minima = soglia di chiusura, attacco tenuto
  anche "non conviene" / trasporti esposti: `KEPT_BY_OPERATION`), cosi' usa anche sbarchi e bombardamenti di Hard. Scoperta: il
  combattimento di Hard britannico a volte non vede lo sbarco UK → 110 → Normandy ("fuori portata": prova locale seme 207 R8,
  causa non verificata) — per questo le mosse le fa prima l'operazione; con navi nemiche nella zona (3 sottomarini tedeschi in 110,
  seme 207 R6) lo sbarco e' impossibile in quel turno e l'operazione rinvia. Se nessuno attacca davvero (registri di battaglia) ⇒ rinvio. Se il bersaglio e' gia' preso da chi apre: rinforzo nel non combattimento (truppe sbarcate dalla zona di
  raccolta, fino a 2 aerei che atterrano). Condizione non rispettata ⇒ **rinvio** di un round (max `max_rinvii`, entro
  `round_max`) altrimenti ANNULLATA.
- **Esito**: preso/non preso nel non combattimento di chi chiude (COMPLETATA), tenuto dopo 1 e 2 round (turno di chi apre);
  Egitto: un'unita' di terra tedesca entra in Egypt se puo' (obiettivo nazionale).
- **Diario**: righe "Operazione X: ..." con i numeri (avvio con fattibilita', stato, riserve con le mosse, tappa 1 con attaccanti,
  win%, perdita attesa, residui, rinforzi, stima tappa 2, presa stimata, valore atteso; tappa 2 con residui/stimati/tollerati,
  TUV perso, win%; rinvio/attesa/annullamento con il motivo; presa, rinforzo, completata, tenuta); JSON `operazione_congiunta`
  (stessi numeri). Stesso elenco di eventi nello stato salvato (letto dall'autoplay anche senza diario).
- **Contatore di controllo** (`ControlloTuning`): `ProPlusAi` costruisce `ProData` con un proxy di `ProTuning` che a ogni domanda
  chiama Pro+ e `ProTuning.DEFAULT` sugli stessi argomenti e conta per round domande, differenze e risposte dell'operazione
  (segnate da `ProPlusTuning.operationAnswer`); restituisce sempre la risposta di Pro+. Salvato nelle partite con il lato nudo
  (`Pro+ controllo nudo: <nazione>`, fine di ogni fase). `ProPlusTuning.unwrap` al posto degli `instanceof ProPlusTuning`.
- Test: `ai/pro/OperazioniCongiunteTest` (definizioni e interruttore, stato e round obiettivo, riserve negli acquisti con acquisto
  vero, condizione tappa 2, flag spento = Hard con contatore a 0 su un turno intero, operazione accesa: 0 differenze fuori
  dall'operazione, `combatHeldUnits` di Hard vuoto).

### Variante v2: presa e tenuta (step PROVA2)

Diagnosi della prova di valore (run 42673273717): si PRENDE prima ma NON si TIENE (Egitto all'Asse R3 80% contro 40% del nudo,
R12 9% contro 14%; Normandy tenuta 2 round 12/27). La v2 **sostituisce** la v1 per gli id `egitto` e `francia` (la v1 si
riproduce dal commit 428885d); le definizioni hanno `tenuta` (assente = comportamento v1, `Tenuta.NESSUNA`).

**Dati** (`operazioni-congiunte.json` versione 2, valori scelti, NON tarati): `tenuta.round` 2 (round da tenere dopo la presa:
2 = due turni di ogni nemico), `soglia_rinforzi` 70 (tenuta stimata sotto cui partono rinforzi e riserve), `soglia_ritirata` 5
(a o sotto: presidio lasciato a Hard e niente rinforzi inutili), `aerei_copertura` 2 (aerei per nazione che atterrano nel
bersaglio), `quota_acquisti_nemici` 0,5 (meta' dei PU nemici spesi per il contrattacco: il resto va su altri fronti),
`riserve` 2 (unita' di terra obbligate negli acquisti). Francia: `bersaglio_finale` "France" (Parigi).

**A) Lancio con contrattacco atteso** (tappa 1, punto di decisione di chi apre; anche per la tappa su Parigi):
- contrattacco = unita' nemiche che possono colpire il bersaglio contato come gia' dell'alleanza (`ProTerritoryManager.populateEnemyAttackOptions`
  con il bersaglio tra i "cleared", unita' max + anfibie, bombardamento) **+ acquisti possibili** (`enemyPurchases`): per ogni nemico in
  guerra, fabbriche proprie entro 2 mosse di terra dal bersaglio, capacita' = produzione del territorio, unita' di terra con miglior
  attacco/PU che arriva al bersaglio da li', entro `quota_acquisti_nemici` dei PU attuali (unita' temporanee, mai sulla mappa);
- presidio = sopravvissuti di terra della tappa 2 (o 1) + unita' di chi chiude in portata non usate nell'attacco; se la "corsa media" del
  calcolatore tiene solo aerei (la fanteria muore per prima) almeno l'unita' di terra piu' forte (la presa richiede un'unita' di terra);
- tenuta stimata = 100 − win% del contrattacco contro il presidio;
- **valore d'alleanza** = valore × presa stimata × tenuta − perdite attese (tappa 1 + tappa 2 se la 1 non prende + presidio perso con la
  tenuta) (`valoreAlleanza`, pubblico e testato). Si lancia solo se > 0 (e perdita tappa 1 entro il tetto); altrimenti rinvio (stesso
  meccanismo, max 2 rinvii) con motivo "contrattacco atteso: tenuta stimata X%, valore d'alleanza V <= 0" quando senza contrattacco
  sarebbe stato positivo. Bersaglio senza difensori: stesso conto con il presidio = truppe di chi chiude. Bersaglio gia' dell'alleanza
  (preso da Hard) al round obiettivo: entra direttamente in TENUTA ("preso senza operazione").
- Diario e JSON della tappa 1: `tenuta_stimata`, `contrattacco` (+ TUV), `contrattacco_acquisti` (+ TUV), `presidio`, `valore_alleanza`,
  `valore_senza_contrattacco`.

**B) TENUTA** (`Stato.TENUTA`, dopo la presa al posto di COMPLETATA; `tenuta_bersaglio`, `tenuta_dal`):
- presidio bloccato: unita' di terra della nazione nel bersaglio in `combatHeldUnits` e `heldUnits` (Hard non le muove), salvo ultima stima
  ≤ `soglia_ritirata` ("presidio lasciato a Hard");
- non combattimento di ogni nazione dell'operazione: stima della tenuta (contrattacco come sopra contro i difensori alleati presenti), poi
  se sotto `soglia_rinforzi`: sbarco dalla zona di raccolta (Francia), unita' di terra entro 2 mosse in territorio alleato (non dalla
  capitale a rischio), poi fino a `aerei_copertura` aerei che possono atterrarvi (le regole: non nel territorio conquistato nello stesso
  turno dalla stessa nazione; il validatore delle mosse rifiuta il resto), fermandosi quando la stima arriva alla soglia. Se nemmeno con
  tutte le unita' in portata la stima supera `soglia_ritirata`: nessun rinforzo ("rinforzi inutili"). Navetta USA e flotta in 110
  continuano; le truppe in raccolta non sono piu' tenute (solo trasporti e presidio). Evento `stima_tenuta` (prima/dopo, contrattacco,
  acquisti, difensori, rinforzi); `ultima_stima_tenuta` salvata;
- acquisti: con `ultima_stima_tenuta` < `soglia_rinforzi`, `riserve` unita' di terra obbligate (tipi di terra delle riserve), piazzate prima
  nel bersaglio (se ha una fabbrica) poi nel piazzamento della nazione, ammassate verso il bersaglio;
- fine: a inizio turno di ogni nazione, bersaglio non dell'alleanza ⇒ **FALLITA** (`tenuta_esito` fallita, `tenuta_persa_round`); al turno di
  chi chiude, dopo `tenuta.round` round ⇒ **COMPLETATA** (`tenuta_esito` completata). Tappa 2 eseguita senza presa ⇒ FALLITA ("non preso").
  In tutti gli stati finali le unita' tornano a Hard.

**C) Francia: testa di ponte.** Normandy tenuta `tenuta.round` round ⇒ `testa_di_ponte_tenuta`, l'operazione resta in TENUTA su Normandy;
da li' a ogni combattimento USA la tappa 1 su France con la logica di A (chiavi con prefisso `finale_`: `finale_tappa1_round`,
`finale_avvii`, `finale_presa`, `finale_tentativi_valutati`, ...; eventi con `fase` = `finale`), UK la tappa 2 nello stesso round
(`forzaBersaglio` riconosce anche il bersaglio finale). Non giustificata ⇒ `rinvio_finale` senza limite: si continua a tenere Normandy.
Parigi presa ⇒ TENUTA su France per `tenuta.round` round, poi COMPLETATA; Normandy o Parigi persa ⇒ FALLITA.

**D) Bug "Hard britannico non vede lo sbarco UK → Normandy" — causa trovata (codice dell'operazione, non Hard).** Riproduzione locale
(seme 207, commit 65ebfd7): al R6 gli USA rinviano la tappa 1 ⇒ round obiettivo R7 ⇒ nello **stesso R6** il Regno Unito risultava in
"posizionamento" (round = obiettivo − 1) e `combat()` metteva in `combatHeldUnits` le unita' in raccolta (3 artiglierie, 4 fanterie, 1
fanteria meccanizzata in United Kingdom, 2 trasporti in 110): il combattimento di Hard vedeva "Normandy Bordeaux: win% max 0% < soglia
minima" mentre l'operazione stimava la tappa 2 all'86% con quelle stesse unita'. Correzione: `rinvio_round` salvato da `postpone`; nel round
del rinvio il posizionamento non trattiene nulla (evento `posizionamento_libero`).

## Operazioni decise da LLM (step PROVALLM)

Causa misurata in [prova2]: la formula v2 non sa prevedere cosa fara' DAVVERO il nemico (stima contro il massimo teorico, tenuta stimata ~1%
→ reale 52%), quindi non sa quando lanciare. Qui un LLM decide **solo** nei punti dove decideva la formula; esecuzione, presidio, rinforzi,
ritirata restano del motore. **Spento di default** (proprieta' `Pro+ operazione decisore: <alleanza>` assente = formula; nessun pannello).
- **Punti** (`OperazioniCongiunte.decide`, `DecisioneOperazione.Punto`): `tappa1` di chi apre (anche bersaglio senza difensori e tappa su
  Parigi, prefisso `finale_`): lancia/rinvia/annulla; `tappa2` di chi chiude: procedi/rinvia/annulla; `tenuta` (non combattimento di ogni
  nazione dell'operazione, solo con stima del motore < `soglia_rinforzi` 70%): tieni (rinforzi del motore anche se la formula li direbbe
  "inutili") / ritira (niente rinforzi, presidio a Hard al combattimento successivo, `tenuta_decisione_<nazione>`). `annulla` sul bersaglio
  finale = `finale_annullata` (niente piu' Parigi, la testa di ponte resta). Nessuna chiamata se la tappa e' oltre il tetto di perdita o
  senza attaccanti (paletti rigidi, decide la formula).
- **Ingresso** (`OperazioniCongiunte.stato`, contratto v1.2 in `proplus/strategia/contratto-v1.md`): numeri della tappa, contrattacco
  massimo per origine + acquisti per fabbrica, `tenuta_per_impegno` (25/50/75/100%), formula v2, `storico_nemico` (`StoricoNemico`: a fine
  turno di una nazione dell'operazione, solo con LLM acceso, unita' nemiche in portata dei territori presi nel turno (max 4) e del bersaglio;
  al turno dopo della stessa nazione: entrate o morte attaccandoli = impegnate, spostate altrove (dove), ferme; ripreso/attaccato; proprieta'
  `Pro+ operazione storico: <id>`), `priorita_nemico` (territori di valore del nemico sotto pressione: TUV alleato entro 2/3 contro difesa),
  VC e reddito. File `operazione-ingresso-R<N>-<nazione>-[finale_]<punto>.json` nel diario.
- **Prompt** `prompt-operazione-v1.md`: operazioni a tappe e ordine dei turni, perdite di una nazione accettabili se convengono all'alleanza,
  stimare la risposta REALE (storico, priorita', curva per impegno, distanza), il dato misurato della prova2 (fasce di calibrazione della
  formula), paletti, schema strict. `prob_tenuta_stimata` = bersaglio dell'alleanza all'inizio del turno di chi chiude nel round dopo.
- **Modello**: Sonnet 5.5 (`claude-sonnet-5-5`, configurabile con `llm:<modello>`), Messages API `2026-10-21`, `effort: low`, output
  strutturato. **Temperatura non impostabile**: dalla versione API 2026-10-21 `temperature`/`top_p`/`top_k` danno errore 400 su tutti i modelli
  (verificato su platform.claude.com, 9 nov) ⇒ "temperatura bassa" non applicabile, solo prompt deterministico + effort low.
- **Paracadute** (`DecisoreOperazione.decidi`): errore/timeout (45 s, 2 tentativi), risposta non valida, tetto 80 chiamate, budget della
  partita (`Pro+ operazione budget usd`, default 1 USD; si chiama solo se speso + max(0,03, 1,5 × costo medio) ≤ budget) ⇒ formula, con il
  motivo. Uso in `Pro+ operazione uso` (chiamate, errori, risposte non valide, token, costo, ripieghi per motivo). Chiave: profilo utente o
  `ANTHROPIC_API_KEY`, solo nell'header.
- **Diario**: "Operazione X, decisione <punto> su <bersaglio> (<fonte>): AZIONE, tenuta stimata dall'LLM N% (motivazione) - formula: azione,
  M%; stato: <riassunto>" + evento `decisione_operazione`; record in `decisioni` del registro con `prob_llm`, `prob_formula`, azioni, ripiego,
  costo, e `esito_reale` (bersaglio dell'alleanza all'inizio del turno di chi chiude nel round dopo; `settleDecisions`).
- Prezzi verificati (platform.claude.com, 9 nov 2026): Sonnet 5.5 $2 ingresso / $10 uscita / cache 5 min $2,50 / 1 h $4 / lettura $0,10 per
  MTok, nessun sovrapprezzo di contesto lungo; Haiku 5.5 $0,10/$0,50 (fino a 100K). Corretto `prezzi-stratega.json` (lettura cache Sonnet era
  0,20).
- Test: `ai/pro/OperazioniLlmTest` (chiamate simulate): parsing e schema, ripiego su risposta non valida/timeout/budget, nessuna chiamata
  col flag spento, stato con storico del nemico, tieni/ritira nel motore, esito reale salvato.

## Stratega LLM completo (step STRATEGA LLM)

Decisione di Matteo (dopo PROVALLM): la misura principale diventano le partite giocate da Matteo; l'LLM decide COSA/PERCHE' con
leve vere (operazioni, rinforzi), il motore calcola ed esegue (COME), Hard fa il resto; le manopole restano a Hard ("nudo") e
l'LLM NON le controlla. Voce giocatore **"Pro+ Stratega LLM (Europe 1940)"** (`ProPlusStrategaAi extends ProPlusAi`); le voci
esistenti non cambiano (nessuna proprieta' nuova senza la voce, `OperazioneCongiunta.tutte` vuota, nessuna chiamata).
- **Registrazione** (`Stratega.registra`, in `initialize` e a ogni acquisto): nazione nella proprieta' `Pro+ stratega llm nazioni:
  <alleanza>`; alleanza in `Pro+ ablazione` = `nudo` (attenzione: vale per TUTTA l'alleanza: una nazione "Pro+ (AI)" della stessa
  alleanza diventa nudo); `Pro+ operazione decisore: <alleanza>` = `llm:<modello>` (Sonnet 5.5, `Pro+ stratega llm modello`);
  tetto `Pro+ stratega llm budget usd` = profilo utente (`stratega_llm_tetto_usd`, campo "Tetto Stratega LLM" del pannello,
  default 2 $ da `stratega-llm.json`), lo stesso valore in `Pro+ operazione budget usd`. `chiave-stratega.txt` accanto al gioco
  importato nel profilo all'inizio della partita (senza aprire il pannello; il file non viene cancellato).
- **Operazioni dinamiche**: definizioni nello stesso formato di `operazioni-congiunte.json`, salvate nella proprieta' `Pro+
  stratega operazioni: <alleanza>` (+ `Pro+ stratega operazioni presenti` = controllo rapido), id `stratega-<tipo>-R<n>-<bersaglio>`;
  `OperazioneCongiunta.tutte(data, player)` = operazione scritta a mano accesa (solo autoplay) + dinamiche della nazione. I punti
  d'ingresso di `OperazioniCongiunte` (aggiorna, acquisti (ordini uniti, quota = la massima), combattimento, non combattimento,
  guerra, accesso, bersaglio forzato) iterano su tutte; `ProPlusAi.playJointOperation` le gioca una dopo l'altra (dati
  reinizializzati, unita' gia' tenute escluse). Con una sola operazione il comportamento e' identico a prima.
- **Tipi** (costruiti sulla meccanica v2, niente riscrittura): **offensiva** (apre = indebolisce, chiude = prende nello STESSO round;
  chi apre gioca prima nell'ordine dei turni; se chi apre colpisce con aerei `aerei_apre` + raccolta aerei entro 2 dal bersaglio),
  **sbarco** (zona di mare confinante con bersaglio e con una terra dell'alleanza = raduno/carico; trasporti + terra nelle riserve;
  `bersaglio_finale` = bersaglio successivo facoltativo tra i confinanti), **tenuta** (territorio proprio minacciato: il registro
  parte in TENUTA, presidio bloccato, rinforzi sotto 70%, COMPLETATA dopo `durata_tenuta` round al turno di chi chiude, FALLITA se
  perso), **rinforzo** (una nazione nel territorio di un alleato minacciato, poi come tenuta). Round successivi tra le tappe NON
  supportati (vedi problemi aperti). `germanPresence` (regola Egitto) spenta per le dinamiche.
- **Candidati** (`StrategaCandidati.calcola`, inizio round, max 8, al massimo 3 per tipo, uno per bersaglio e tipo): conquiste =
  territori nemici con valore (produzione + 10 VC + 10 capitale) raggiungibili entro `orizzonte_round` 3 dalle coppie di nazioni
  dello Stratega in guerra col proprietario (stima rapida delle tappe `estimateAttackBattleResults`: win%, perdita, difensori residui,
  presa); tenute/rinforzi = territori dell'alleanza con valore >= 2 e nemici di TERRA in portata, tenuta al 50% < 80 e al 100% < 95.
  Per gli 8 scelti: `presidio_atteso`, `contrattacco_massimo` per origine + acquisti per fabbrica e `tenuta_per_impegno` 25/50/75/100%
  (`OperazioniCongiunte.valutaTenuta`, stessi numeri dei punti di decisione). Lettura dell'avversario: TUV nemico per fronte del file
  di strategia con variazione dal round prima (`Pro+ stratega llm lettura: <alleanza>`), minacce a capitali/VC proprie, storico del
  nemico per tipo di bersaglio (terra / costa-sbarco / capitale, da `StoricoNemico` delle operazioni dello Stratega).
- **Livello strategico** (`Stratega.inizioTurno`, acquisto della prima nazione dello Stratega dell'alleanza nel round, proprieta'
  `Pro+ stratega llm round: <alleanza>`): prima controlla chiave/tetti (`trasporto()`: senza chiave nessun candidato calcolato),
  poi candidati + stato → Sonnet (`PromptStrategaV4`, contratto 1.3 in `contratto-v1.md`) → `StrategaDecisione.leggi` → applicazione
  con i vincoli del motore (max 3 operazioni attive, un bersaglio una sola operazione, tetto di perdita <= 80, durata <= 4, riserve
  <= 30% dei PU per nazione, guarnigione capitale del motore) e rifiuti con motivo; una sola proposta fuori lista. Portafoglio in
  `Pro+ stratega llm portafoglio: <alleanza>` (operazioni con stima e motivazione, previsioni per round con `verifica_round` ed
  `esito_reale`, `rifiutate`, `round_decisi`, `round_senza_llm`); operazioni finite tolte dalla lista dinamica a inizio round.
  Tetto chiamate strategiche 30/partita; uso in `Pro+ stratega llm uso` (stesso formato di `Pro+ operazione uso`).
- **Ordini del turno** = punti di decisione esistenti (tappa1/tappa2/tenuta, `DecisoreOperazione`) con `tipo_operazione`
  nell'ingresso ⇒ `prompt-ordini-turno-v3`. **Scostamento dalla richiesta**: non c'e' una chiamata separata a inizio turno della
  nazione; la decisione procedi/rinvia/tieni/ritira arriva nel momento in cui il motore ha i numeri (combattimento per le tappe,
  non combattimento per la tenuta), come in PROVALLM. I "rinforzi prioritari" sono nel livello strategico (territori con peso 1-3
  → ammassamento del non combattimento, il motore sceglie le unita').
- **Paracadute** = Hard: senza chiave, errore, timeout, risposta non valida, tetto chiamate o tetto $ (strategico + ordini insieme:
  `DecisoreOperazione.costoAltriLivelli`) ⇒ nessuna operazione nuova; nei punti di decisione delle operazioni dello Stratega rinvia /
  ritira (non la formula, `ripiego_hard`). Avviso UNA volta per partita e nazione nel log di gioco: "Stratega LLM non disponibile:
  gioco con le regole (<motivo>)" (`Pro+ stratega llm avviso`).
- **Visibilita'**: a fine turno (dopo il piazzamento) di ogni nazione dello Stratega un commento nella cronologia del gioco
  (`IEditDelegate.addComment`, ammesso nel turno del giocatore senza modalita' modifica) "Stratega LLM <nazione>: <operazioni
  attive [nazioni]: stato>" (niente stime ne' motivazioni) + "Stratega LLM: <operazione>: COMPLETATA/FALLITA/ANNULLATA" alla fine.
  Diario dello Stratega SEMPRE scritto (anche col diario Pro+ spento) nella cartella della partita del diario: `stratega.txt`,
  `stratega.jsonl`, `stratega-riepilogo.json` (riscritto a ogni round/turno).
- **Prezzi** (verificati su platform.claude.com il 10/11/2026): Sonnet 5.5 $2 / $10, cache 5 min $2,50, 1 h $4, lettura $0,10 per MTok
  (gia' in `prezzi-stratega.json`); `temperature`/`top_p`/`top_k` = errore 400 dalla versione 2026-10-21 (non inviati), `effort` in
  `output_config.effort` (low).
- **Autoplay**: `--tipo <alleanza>=stratega` (tetto della partita = `--budget-operazioni`); `riepilogo.json` `stratega_llm`
  (= `stratega-riepilogo.json`); sezione "Stratega LLM: controllo [smoke]" (`ConfrontoSmoke`). Prove locali senza rete:
  `JAVA_OPTS=-Dproplus.stratega.simulato=true` (modello finto: avvia i primi 2 candidati, procedi/tieni).
- Test: `ai/proplus/StrategaLlmCompletoTest` (13, chiamate simulate), `HeadedPlayerTypesTest.proPlusStrategaLlmIsListedAfterProPlusAndInstantiable`.
- **Perche' annulla** (step ANALISI ANNULLAMENTI, `proplus/analisi/annullamenti-smoke-42775993327.md`): 56 annullate su 93; 82% artefatti
  (bersaglio preso da Hard prima delle tappe 15, rinforzi con tenuta "con rinforzi" gonfiata nel candidato 18, forze della tappa
  insufficienti 7, raccolta/flotta 4, candidato sparito 2), 5% ripensamento dell'LLM, 12,5% giustificati; offensive davvero eseguite 3/49
  (le altre "eseguite" sono prese di Hard senza operazione). Correzioni proposte (non fatte) nel documento.

## Coerenza delle stime (step COERENZA STIME)

Causa misurata (analisi `proplus/analisi/annullamenti-smoke-42775993327.md`): lo Stratega decideva bene su numeri incoerenti del
motore (candidato, punto di decisione ed esecuzione usavano stime diverse). Ora **una sola stima** (`StimaUnica`), solo per le
operazioni dello Stratega (`OperazioneCongiunta.isStratega`): le operazioni scritte a mano (egitto/francia, [prova]/[prova2]/
[provallm]) e Hard non cambiano.
- **Forze**: unita' delle nazioni dell'operazione che possono essere sul bersaglio entro il round obiettivo: nel round stesso
  (`raggiungibiliOra`) terra per territori dell'alleanza, aerei con atterraggio, truppe sbarcabili (anche caricate su una costa
  amica e sbarcate nello stesso turno, `sbarcabili`); per un round successivo SOLO cio' che l'operazione trattiene (unita' ai
  luoghi di raccolta, carico dei trasporti nella zona di raduno, aerei di chi apre che arrivano in tempo alla raccolta, al
  massimo `aerei_apre`) PIU' le unita' raggiungibili entro k round (`raggiungibiliEntro`: terra k x movimento per territori
  alleati, aerei) e quelle sbarcabili ora; il motore le avvicina davvero (`avvicina`: le unita' di terra contate fanno un passo
  sicuro a turno verso la raccolta, trattenute) e le trattiene dall'acquisto (`trattenute`). Nota: la versione "solo cio' che e'
  gia' in raccolta" (commit 1f1c7e2) azzerava le conquiste (vedi risultati della run 42803941666). Escluse le unita' di altre
  operazioni attive dell'alleanza (`StimaUnica.riservate`: presidio tenuto, aerei/terra/trasporti ai raduni) e senza la guarnigione
  delle capitali (`guardiaCapitali`, stessa soglia delle operazioni). Conquista: tappa 1 (`calculateBattleResults`, solo aerei con
  ritirata), rinforzi nemici tra le tappe visti dal turno di chi apre, tappa 2; presa = P1 + (1 - P1) x P2; presidio = superstiti
  di terra (o l'unita' di terra piu' forte). Tenuta/rinforzo: difensori alleati + `rinforziPossibili` delle nazioni
  dell'operazione (terra entro 2 per territori alleati, niente antiaerea/difesa 0, non la guardia della capitale, al massimo
  `aerei_copertura` aerei che possono atterrare).
- **Contrattacco**: capitali e fabbriche con produzione >= `produzione_fabbrica_chiave` (3) = MASSIMO; altrove REALISTICO = la
  frazione `impegno` delle forze in portata + acquisti (le piu' vicine prima, per TUV), con `impegno` = frazione impegnata media
  di `StoricoNemico` pesata sulle osservazioni di tutte le operazioni dell'alleanza, almeno `impegno_nemico_minimo` 0,25; con
  meno di `osservazioni_minime_storico` 4 osservazioni almeno `impegno_nemico_prudente` 0,5 (`base_impegno` dice quale).
  Successo = presa x tenuta.
- **Dove**: candidati (`StrategaCandidati.valuta`, i primi `candidati_valutati` 14 dello screening rapido, che resta solo per
  ordinarli), stato strategico (`stima_motore_ora` di ogni operazione in corso, anche fuori lista), tappa 1 (`step1Stratega`:
  numeri, decisione del motore, LLM, unita' mosse = quelle contate; "contate ma non mosse" nel diario), punto `tenuta`
  (`tieni` del motore se la stima con i rinforzi > `soglia_ritirata`), soglie di rinforzo/ritirata (`holdPct`). La tappa 2
  resta quella di prima (attacco forzato di Hard) con la tenuta realistica. Prima stima a un punto di decisione salvata in
  `stima_decisione_prima` (misura di coerenza con `stima_avvio`).
- **Unita' contate = unita' trattenute**: Hard pianifica gli attacchi gia' nella fase d'acquisto (su una copia della partita), quindi
  trattenerle solo al combattimento era tardi (prova locale: 2 caccia russi del rinforzo di Egypt usati da Hard sui Paesi
  Baltici). All'acquisto di una nazione dello Stratega `OperazioniCongiunte.trattenuteAllAcquisto` (presidio tenuto e, sotto
  `soglia_rinforzi`, i rinforzi della stima; unita' in raccolta nel round di posizionamento; forze della tappa della nazione nel
  round obiettivo) va in `ProPlusTuning.holdCombatForPurchase`, che `combatHeldUnits` riconosce anche sulla copia (stessi id,
  stessa nazione e round). Anche nel combattimento di una tenuta i rinforzi sotto soglia restano fuori dagli attacchi di Hard.
  Costo: se poi la tappa viene rinviata, quelle unita' restano ferme quel turno.
- **Bersagli gia' in mano / presi da Hard**: niente candidato se una nazione dell'alleanza lo prende da sola nel round (stima rapida
  delle sue forze, win >= `soglia_presa_hard` 70%). Se il bersaglio di una conquista diventa dell'alleanza senza le tappe (a
  inizio turno di ogni nazione, prima della decisione strategica, alla tappa 1): TENUTA senza chiamare l'LLM se la tenuta stimata
  >= `soglia_tenuta_presa_hard` 30%, altrimenti chiusa dal motore (ANNULLATA, `chiusa_dal_motore`, causa C1). Flag
  `presa_senza_operazione`: non conta come eseguita.
- **Prima della decisione di inizio round** (`OperazioniCongiunte.primaDellaDecisione`): bersaglio tenuto perso ⇒ FALLITA
  (`fallita_prima_della_decisione`), conquista presa da Hard ⇒ come sopra; poi `chiudiFinite`.
- **Fattibilita' al lancio** (`OperazioniCongiunte.lancio`): scartati i candidati con raccolta oltre `orizzonte_round` (la
  `fattibilita` non limitata; dentro l'orizzonte il round minimo sale a quello della raccolta), zona di raduno non sicura (navi
  da guerra delle nazioni entro 2 zone + alleate nella zona contro l'attacco realistico), nessun luogo di raccolta aerea sicuro,
  tappa 1 oltre il tetto, tenuta < `tenuta_minima_candidato` 20% anche con i rinforzi; tutti in `candidati_scartati` con il
  motivo. **Sicurezza raduni**: `SOGLIA_SICUREZZA` 40 contro l'attacco massimo resta per le operazioni scritte a mano; per lo
  Stratega win% nemica dell'attacco realistico <= `soglia_sicurezza_raduno` 40 (`sicuro`).
- **Tetto di perdita** (`StrategaConfig.tettoPerdita`): `tetto_perdita_per_pu` 3 TUV per PU di valore, tra `tetto_perdita_minimo`
  20 e `tetto_perdita_max` 80; l'LLM puo' chiederne un altro fino a `tetto_perdita_max_llm` 120 (avvia e modifica). Riga
  "TETTO DI PERDITA - ..." nel diario quando ferma una tappa (`bloccoTetto`).
- **Riavvio**: niente riavvio dello stesso bersaglio entro `riavvio_round` 3 round da un'operazione annullata/fallita se il
  successo stimato dal motore non e' migliorato di `riavvio_miglioramento_punti` 5 (`Stratega.riavvioVietato`, rifiuto motivato).
- **Diario e misure**: `causa_annullamento`/`causa_testo` (`CauseAnnullamento`: C1 bersaglio dell'alleanza senza tappe, C5
  candidato fuori lista, C4 niente in raccolta dopo 2 round, giustificato = contrattacco massimo +30% dal lancio, C3/C2 successo
  del motore -15 punti dal lancio, altrimenti C6) per annulla dello stratega, degli ordini, del motore (rinvii) e chiusure del
  motore; flag `tappa_motore`, `tenuta_motore`; blocchi per alleanza in `Pro+ stratega llm blocchi: <alleanza>`
  (`tetto_perdita`, `sicurezza_raduni` = fasi con mosse verso il raduno negate, `quota_pu` = difesa limitata dalla quota delle
  operazioni, contata in `DiaryRecorder.purchaseDefenseShare`, `candidati_scartati`, `riavvii_rifiutati`) nel riepilogo.
- **Record perso (seme 503, R7)**: l'eccezione in `applica` dopo un annullamento saltava `chiudi`; causa non ricostruibile dai
  file. Ora `applica` e' in try/catch (riga "ERRORE nell'applicazione", `errore_applicazione` nel record), il diario del round
  e' scritto comunque (`StrategaDiario.scriviMinimo` se il diario completo fallisce).

### Step C1-bis: aerei trattenuti, ordine delle cause, valore d'alleanza in PU

Dalla [smoke] 42822465005: le offensive crollavano il round dopo il lancio (mediana 77 punti) perche' gli aerei contati per
una tappa futura non erano trattenuti (Hard li usava).
- **Ogni unita' contata e' trattenuta** (solo operazioni dello Stratega): per un round successivo `StimaUnica.forze` conta un
  aereo solo se e' su terra e puo' colpire il bersaglio DA DOVE E' (`raggiungibiliEntro`: distanza + atterraggio <= movimento,
  non piu' movimento x k; `OperazioniCongiunte.colpisceDaQui`), oppure se e' uno degli aerei di chi apre per la raccolta aerea
  (`aerei_apre`, volano li' con `gatherPlanes` che ora muove proprio quelli contati); aerei su portaerei o che dovrebbero prima
  avvicinarsi: non contati. Trattenuti: all'acquisto (`trattenute()` senza il filtro "solo terra" ⇒ `holdCombatForPurchase`),
  nel combattimento dei round di preparazione/posizionamento (`combat()`, anche se Hard ripianifica) e nel non combattimento
  (`avvicina`: aerei che colpiscono da li' fermi, unita' di terra contate tenute anche senza un passo sicuro). Diario/registro:
  `trattenute_acquisto[].aerei`, `aerei_trattenuti_ultimo_turno`, `aerei_turni_fermi` (turni-aereo fermi), riga "aerei
  trattenuti: ..." nelle riserve. Attacchi di Hard rinunciati per mancanza di quegli aerei: NON misurati (servirebbe un
  secondo piano d'attacco di Hard senza la trattenuta a ogni turno).
- **Ordine delle cause** (`CauseAnnullamento`): C1 → (con stima al lancio e stima ora) giustificato / C3 / C2 → C5 → C4 → C6.
- **Valore d'alleanza in PU equivalenti** (solo Stratega; formula e razionale in `stratega-llm.json` `valore_pu`): valore =
  `valore_bersaglio_PU` x presa x tenuta + TUV_nemico_distrutto − TUV_perso (1 TUV = 1 PU; TUV_perso = tappa 1 + tappa 2 se la
  1 non prende + (1 − tenuta) x presidio, come la v2). `valore_bersaglio_PU` = H x (2 x produzione + obiettivi nazionali in
  gioco (`ProNationalObjectiveUtils.stake`) + 5 PU x VC) + capitale (cassa del proprietario + suo reddito x H, regole di
  `ProUtils.capturedTreasury`/`capitalValue`), H = round di tenuta dell'operazione (2). `StimaUnica.Stima.valoreAlleanza`/
  `jsonValore` (in `stima_motore.valore_alleanza` per l'LLM e nel diario della tappa 1), `StrategaCandidati.valoreBersaglioPu`
  (`valore.componenti_PU` del candidato, `valore`/`valore_componenti` della definizione). La tappa 1 del motore lancia se il
  valore > 0. Il vecchio indice (produzione + valore_vc + valore_capitale) resta per ordinare i candidati, per
  `valore_minimo_tenuta` e per il tetto di perdita (`indice_valore` nella definizione). Prompt stratega v5: 1 riga sulla formula.
- Misure nuove in `ConfrontoSmoke`: mediana |stima al lancio − stima del round dopo| per offensive/sbarchi (`stima_round_dopo`,
  scritta da `Stratega` nello stato del round dopo il lancio), turni-aereo fermi. [smoke] semi 701-706.

### Step C1-ter: raggiungibilita' = movimento, bersaglio vuoto, TUV distrutti dei difensori presenti

- **Un solo controllo di sicurezza** (`OperazioniCongiunte.passiSicuri` / `arriviSicuri`, sopra `safeLand` → `sicuro`): lo usano
  le mosse (`stepToward`, `gatherPlanes`) e la stima (`haPercorsoSicuro`, movimento pieno, memo per stima). Per un round futuro
  `StimaUnica.forze` conta un'unita' di terra che deve camminare (non raggiungibile ora, non in raccolta ne' accanto al bersaglio)
  e un aereo per la raccolta aerea solo se una rotta supera quel controllo; le altre in `Stima.esclusePercorso`
  (`escluse_percorso_non_sicuro`, `_n` nella stima e nel diario della tappa 1). Restano contate le unita' raggiungibili ora (anche
  sbarcabili da un trasporto in questo turno). Luoghi di raccolta = `luoghiRaccolta` (stessi della stima e delle mosse).
- **Bersaglio vuoto**: presa 100% se nelle tappe c'e' un'unita' di terra capace di conquistare, 0% con soli aerei (`soloAerei`,
  `nota_presa` "serve un'unita' di terra"); `win_tappa1_pct`/`win_tappa2_pct` non scritti (nessun win% della tappa 1 nel confronto).
- **TUV distrutti** = solo i difensori presenti alla stima (`StimaUnica.destroyed`: difensori - quelli che restano dopo le tappe,
  per id); i rinforzi nemici attesi restano nel contrattacco/tenuta. Syria (run 42833255633): valore d'alleanza ~38 PU (49 TUV
  distrutti, 46 di rinforzi) → ~−8 PU.
- `ConfrontoSmoke`: unita' escluse per percorso non sicuro al lancio (per operazione), lanci con presa 0 per "solo aerei".

### Budget difensivo calcolato (step C2)

Solo per la voce "Pro+ Stratega LLM" (`Stratega.gioca`); Hard, Hard Beta, Pro+ (AI) e le operazioni scritte a mano invariati
(hook `ProTuning.defensePlan` vuoto di default). Valori in `stratega-llm.json` (`difesa`, `tetto_strategia_pct`), NON tarati.
- **Quando**: nell'acquisto di ogni nazione dello Stratega (`ProPlusAi.purchase`, dopo `trattenuteAllAcquisto`, sul tabellone
  reale prima della simulazione di Hard): `Tracciamento.aggiorna`, poi `Difesa.calcola`.
- **Minaccia (A)**: territori di terra della nazione + capitali/fabbriche dell'alleanza alleate alla nazione con sue unita'
  entro 2; prefiltro con UNA stima d'attacco nemica di Hard (`ProTerritoryManager.populateEnemyAttackOptions` su tutti i
  candidati: nemici che muovono prima del suo prossimo turno, insieme) e unita' di terra di **nemici reali**
  (`StimaUnica.nemiciReali` = `MinacciaReale.nemici`: in guerra o che possono dichiarare guerra ora; senza questo filtro al R1
  le unita' britanniche "minacciavano" Washington, come nello step 3c). Primi `max_territori` 8 (capitali e irrinunciabili
  prima, poi per valore). Per ognuno `op.minaccia(t)` + `StimaUnica.impegno`/`parte` (massimo su capitali e fabbriche chiave,
  realistico altrove, minimo prudente 0,5 con storico scarso) + `StimaUnica.tenuta`: la STESSA stima delle tenute (F);
  dallo step C2 anche `OperazioniCongiunte.minaccia` delle operazioni dello Stratega conta solo i nemici reali.
- **Valore (B)**: `valoreBersaglioPu` (H = `tenuta_round_default`) + TUV delle fabbriche + valore delle operazioni attive
  con il territorio tra i luoghi di raccolta. **Costo (C)**: unita' minime per `soglia_tenuta` 70% (ricerca binaria): prima
  `rinforziPossibili` (le stesse delle tenute, non riservate da altre operazioni, al massimo `max_unita_per_territorio` 12),
  poi acquisti del miglior difensore (2 x punti ferita + difesa per PU) solo nella fabbrica del territorio (le unita'
  piazzate altrove non si muovono prima del turno nemico); costo = PU degli acquisti + (1 - tenuta) x TUV delle unita'
  impegnate. Regola (`Difesa.decidi`): irrinunciabile (capitale, lista LLM) = difendi; sacrificabile (lista LLM, mai una
  capitale) = sacrifica; tiene gia' = difeso a costo 0; tenuta massima <= `soglia_ritirata` = sacrifica; altrimenti
  valore >= costo.
- **Budget (D, J)**: PU_strategia = min(PU - PU_difesa, `tetto_strategia_pct`% dei PU) (`Pro+ stratega llm tetto strategia:
  <alleanza>`, autoplay `--tetto-strategia`; default 70, al posto del vecchio `quota_pu_max_pct` 30); in `ProPurchaseAi` gli
  acquisti del piano sostituiscono `defend_land`, gli ordini (riserve delle operazioni) hanno al massimo PU_strategia (il resto
  accantonato con `reserve/release`), poi Hard spende tutto il resto. "Tetto limita" = taglio > 0 e riserve chieste >
  PU_strategia (PU tagliati = min(taglio, riserve - PU_strategia)).
- **Non combattimento (E, parziale)**: `ProPlusAi.playDefense` dopo le operazioni e prima di Hard: i rinforzi del piano vanno
  nei territori difesi; rinforzi e presidi dei difesi tenuti (`holdForOperation`) e fuori dagli attacchi di Hard
  (`holdCombatForPurchase`). **Non fatto**: Hard puo' ancora mandare unita' nei territori sacrificati (servirebbe un hook nelle
  destinazioni di `ProNonCombatMoveAi`, troppo invasivo).
- **Tracciamento (G)**: al lancio di una conquista `unita_contate` (`StimaUnica.contate`, id e luogo); a ogni turno di una
  nazione dello Stratega `tracciamento` [{round, turno, ferma, avvicinata, spostata_da_hard, altra_operazione,
  guardia_capitale, persa, dettagli}]; quando la stima strategica scende di >= 30 punti dal lancio `cali` [{presa/tenuta
  prima-ora, unita' contate prima-ora, unita' non piu' contate per motivo, difensori e contrattacco, causa, testo}] e una riga
  nel diario dello Stratega.
- **Diario e misure (H)**: in `stratega.txt` "=== R<n> <nazione> - piano difensivo: PU = difesa + strategia + Hard ..." con
  DIFESO/SACRIFICATO (valore, costo, tenuta prima -> dopo, massima, contrattacco, impegno, rinforzi, acquisti, motivo) ed
  "esito del turno prima"; "Difesa <nazione> R<n> (non combattimento): rinforzi mossi ..."; `stratega-riepilogo.json`
  `difesa` per alleanza (con `esito_tenuto` al turno dopo della stessa nazione). `ConfrontoSmoke`: sezione "Budget difensivo
  calcolato (step C2)" (PU per nazione, % strategia contro il 30% fisso, tetto, decisioni per fonte, capitali e irrinunciabili
  persi, calibrazione della stima di difesa, cause dei cali).
- **LLM**: stato `difesa_calcolata` (ultimo piano di ogni nazione), risposta `irrinunciabili`/`sacrificabili` (max 5),
  `Difesa.salvaListe`; prompt v5 aggiornato.

### Difesa di Hard e budget dinamico (step C2-bis)

Decisione di Matteo dopo la [smoke] del C2 (Asse 0,33 VC): il pianificatore difensivo C2 NON decide piu'. Solo per la voce
"Pro+ Stratega LLM"; Hard, Hard Beta, Pro+ (AI) invariati (hook vuoti di default).
- **Difesa = Hard**: acquisti difensivi di Hard (`purchaseDefenders` pieno, senza la quota `minimumShare` quando c'e' il
  tetto) e non combattimento di Hard. Nessuna guarnigione congelata: restano trattenute solo le unita' delle operazioni
  attive (`trattenuteAllAcquisto`, come prima del C2).
- **Budget** (`ProPurchaseAi.purchase`, `ProTuning.strategyCapPercent` = `Difesa.tetto`, default 70, `--tetto-strategia`):
  1) Hard compra la sua difesa (`defend_land`) ⇒ PU_difesa; 2) gli ordini (riserve delle operazioni) con al massimo
  min(PU rimasti, tetto% dei PU del turno) (il resto accantonato con `reserve/release`); 3) Hard spende il resto. Hook
  `ProDecisionRecorder.strategyBudget(ProStrategyBudget)` ⇒ `DiaryRecorder` ⇒ `Difesa.budgetAcquisto` (l'acquisto gira su
  una copia: i numeri sono scritti sul gioco reale da `ProPlusAi.purchase` con `Difesa.registraBudget`). "Tetto limita" =
  PU trattenuti > 0 e ordini con acquisti obbligati non fatti.
- **Liste dell'LLM** (contratto 1.5 invariato, `ProTuning.defenseLists` ⇒ `ProDefenseLists` per nome; capitale della
  nazione sempre irrinunciabile): sacrificabili ⇒ negli acquisti esclusi da `prioritizeTerritoriesToDefend` e segnati
  "non tenibili" (Hard non ci compra ne' ci piazza unita' di terra), nel non combattimento tolti da `prioritizeDefendOptions`
  con `canHold = false` (Hard non ci manda unita' a restare; passaggio e ritirata restano); irrinunciabili ⇒ negli acquisti
  tra i territori da difendere se il nemico vince > 100 − desiderata, e mai abbandonati dal controllo "vale la pena" (come
  la capitale, anche nel piazzamento); nel non combattimento mai tolti (salvo "tiene gia'") e provati per primi
  nell'ordine di Hard. Limite noto: se nemmeno provandoli per primi Hard riesce a tenerli tutti, il ciclo di Hard li toglie.
- **Ombra** (`Difesa.calcola`, all'acquisto, solo diario e `Pro+ stratega llm difesa: <alleanza>` con `ombra: true`): cosa
  avrebbe difeso/sacrificato il piano C2 e con quanti PU; limiti separati (12 rinforzi + 12 acquisti nella fabbrica del
  territorio); fabbriche vicine proprie (`Difesa.fabbricheVicine`: unita' che arrivano in una mossa su terra amica,
  difendono dal turno nemico DOPO: `acquisti_vicini`, `tenuta_turno_dopo_pct`). Fabbriche alleate non usabili: si piazza solo
  nei propri territori (`AbstractPlaceDelegate.canProduce`). Accanto: `reale` (PU difesa di Hard, budget, ordini, resto,
  tetto) e a fine turno (`Difesa.fineTurno`, da `ProPlusStrategaAi.place`) `difensori_inizio` → `difensori_fine_turno`,
  `hard_rinforzato`; esito al turno dopo (`esito_tenuto`). Righe in `stratega.txt`: "piano difensivo IN OMBRA", "Budget ...
  (reale)", "Difesa ... a fine turno (ombra contro Hard)". Prompt v5: la difesa la fa Hard con le liste; `difesa_calcolata` e'
  una stima.
- `ConfrontoSmoke`: sezione "Difesa di Hard e budget dinamico (step C2-bis)" (PU reali per nazione, % operazioni mediana e
  max, turni con difesa 0, tetto che trattiene/limita; ombra contro Hard con esito; fabbriche vicine; calibrazione della
  tenuta iniziale stimata).

### Interruttori di diagnosi dello Stratega (step R0)

Solo per la voce "Pro+ Stratega LLM", default = comportamento di prima (acceso). Proprieta' della partita `Pro+ stratega llm spento:
<alleanza>` = id spenti separati da virgola (`StrategaInterruttori`); opzione dell'autoplay `--stratega-spento Axis=liste+tenute`;
nessun pannello.
- `liste` spento: `ProPlusTuning.defenseLists` torna vuoto (Hard difende senza irrinunciabili/sacrificabili); l'LLM viene chiamato
  lo stesso e le liste restano nel diario.
- `tenute` spento: `StrategaCandidati.calcola` non aggiunge candidati tenuta/rinforzo; `StrategaFuoriLista.valida` rifiuta tenuta e
  rinforzo fuori lista ("tenute e rinforzi spenti"). I "rinforzi prioritari" (pesi dell'ammassamento) NON sono toccati.
- Test: `StrategaLlmCompletoTest.theSwitchesOfStepR0TurnOffTheListsAndTheHolds`, `AutoplayTest.parsesTheStrategistSwitchesOfStepR0`,
  `AutoplayTest.diagnosisOfStepR0ComparesTheStrategistConfigurations`.

### Correzioni dello Stratega (step R1, gruppo A)

Solo per la voce "Pro+ Stratega LLM" (operazioni `stratega-*`, `Stratega.gioca`); Hard, Pro+ (AI), operazioni scritte a mano
invariate. Valori nuovi in `stratega-llm.json` (`guadagno_minimo_tenuta_punti`, `nota_r1`), NON tarati.
- **A1 piano di Hard ricalcolato**: Hard pianifica il combattimento all'acquisto su una copia ed esegue il piano memorizzato
  (`AbstractProAi.move`), quindi il bersaglio forzato della tappa 2 (scritto nel combattimento) e le unita' trattenute dopo le
  operazioni non entravano nel piano. Hook minimo `AbstractProAi.forgetStoredCombatMove`/`hasStoredCombatMove` (protetti, mai
  chiamati da Hard); `ProPlusAi.playJointOperation` (solo Stratega): trattenute = quelle dell'acquisto ∪ quelle delle operazioni,
  e se c'e' un bersaglio forzato (`OperazioniCongiunte.forzaUnBersaglio`) o unita' trattenute non escluse all'acquisto il piano viene
  dimenticato e Hard ripianifica sul tabellone reale (blocco `piano_hard_ricalcolato`). Win% della tappa 2 dello Stratega con le
  stesse unita' dell'esecuzione (`attackers(..., false)`, senza gli sbarchi che Hard potrebbe aggiungere).
- **A2 insieme eseguibile**: `OperazioniCongiunte.eseguibili` = unita' che l'esecuzione muove nel turno (attaccanti diretti senza la
  guardia della capitale + carico dei trasporti nella zona di raduno), usato da `StimaUnica.forze` per il round corrente della nazione
  di turno (candidato, punto di decisione, presidio, tenuta); `step1Stratega` muove solo le contate (anche negli sbarchi). Contate e
  non mosse dopo `execute`: riga nel diario + `contate_non_mosse` nel registro e nei blocchi (riepilogo). Per i round futuri il
  conteggio resta quello del C1-ter.
- **A3 impegno per tipo di bersaglio** (`StimaUnica.TipoBersaglio`: conquista = offensiva, costa = sbarco, tenuta di territorio
  proprio = tenuta/rinforzo/difesa in ombra): lo storico del nemico e' mediato solo sulle operazioni dello stesso tipo; per una
  conquista o una costa (anche dopo la presa) mai sotto `impegno_nemico_prudente`.
- **A4 nemico**: in `StrategaCandidati` nemico = proprietario di un'altra alleanza non neutrale (non piu' "nemico della prima nazione");
  la guerra si verifica per OGNI nazione dell'operazione con `OperazioniCongiunte.guerraPossibile` (in guerra o puo' dichiararla per
  le regole della mappa, `StimaUnica.nemiciReali` in entrambe le direzioni) per la preparazione; unita' nemiche vicine e flotte nelle
  zone di sbarco = di nazioni di altre alleanze in guerra possibile con una nazione dello Stratega; acquisti nemici
  (`enemyPurchases`) = `nemicoDellOperazione`; esecuzione delle tappe solo con la guerra reale (`inGuerraPerLaTappa`, altrimenti
  rinvio "non e' ancora in guerra"); `presaDaHard` con la guerra reale; fuori lista con la guerra possibile.
- **A5 guardia della capitale fissata al lancio**: `OperazioniCongiunte.fissaGuardia` (da `Stratega.avvia`) salva in `Pro+ stratega
  guardia: <id>` le unita' che la minaccia del momento tiene in ogni capitale delle nazioni dell'operazione; `guardCapital` delle
  operazioni dello Stratega riusa quella guardia (a tabellone invariato le stime successive contano le stesse unita').
- **A6 paracadute = pausa**: nei punti di decisione il paracadute non e' piu' "decisione LLM: rinvia/ritira": la tappa resta in
  ESECUZIONE al round dopo senza rinvio contato (`pausa`, `pause_paracadute`, testo "PAUSA per paracadute"; oltre `round_max` la fine
  normale), la tenuta e' TIENI; nel record dell'ordine `azione = "paracadute"` (esclusa dalla calibrazione in `ConfrontoSmoke`).
- **A7 chiamata strategica ripetuta**: `KEY_ROUND` scritto solo dopo una risposta valida o dopo l'ultimo tentativo
  (`Pro+ stratega llm tentativi: <alleanza>` = "round:n", `MAX_TENTATIVI` 2); un errore o una risposta non valida viene riprovato una
  volta dalla nazione successiva dell'alleanza nello stesso round; "nessuna chiave", tetto $ e tetto chiamate non si riprovano.
  Portafoglio: `tentativi_falliti`, `round_decisi_al_secondo_tentativo`. Una riga nel log di gioco per OGNI fallimento (la chiave
  mancante una volta sola) e una nel diario dello Stratega.
- **A8 tenute**: candidato tenuta/rinforzo scartato se la tenuta che Hard ottiene da solo (`StimaUnica.tenutaDaSolo`: difensori presenti,
  senza rinforzi, stesso contrattacco realistico) e' gia' >= `soglia_rinforzi` o se il guadagno e' < `guadagno_minimo_tenuta_punti` 5;
  nel candidato `tenuta_hard_da_solo_pct`, `guadagno_tenuta_punti`, `valore_tenuta` = guadagno x valore. Costo ridotto:
  `trattenutaMinima` = il minimo di unita' (presidio prima, difensori piu' forti prima, poi rinforzi possibili; ricerca binaria) che
  porta la tenuta a `soglia_rinforzi`, nessuna se gia' tenuta, tutte se nemmeno tutte bastano; usata all'acquisto (`trattenute`) e nel
  combattimento della tenuta (evento `trattenuta_minima`, `presidio_liberato`). Il non combattimento continua a tenere tutto il
  presidio (le unita' non attaccano in quella fase).
- **Gruppo B fatto**: **B5** in `attackers` (solo Stratega) gli attaccanti diretti senza la guardia delle capitali e senza le unita'
  riservate da altre operazioni (tappa 1, tappa 2, insieme eseguibile); **B6** tetto di perdita della definizione proporzionale al
  valore in PU (`valore`, come il valore d'alleanza) e non all'indice; **B7** il presidio della stima di conquista comprende i
  rinforzi del non combattimento di chi chiude (`rinforziPossibili`, unita' non usate nelle tappe), come diceva gia' il commento;
  **B8** obiettivi nazionali in gioco di TUTTE le nazioni dell'alleanza nel valore del bersaglio. **D6** chiave di scarto = bersaglio
  + tipo + nazioni, candidati con successo stimato < 30% (`SUCCESSO_MINIMO_MENU`) fuori dal menu, menu ordinato per valore d'alleanza
  della stima unica. **Non fatto**: D5 (filtro "Hard lo prende da solo" con le soglie d'attacco vere di Hard e la stessa portata della
  stima): tempo.
- **Menu vuoto**: round con 0 candidati contati nel portafoglio (`round_menu_vuoto`, `round_menu_vuoto_elenco`) e riga nel diario.
- **Linea di base a regole** (`StrategaRegole`, `-Dproplus.stratega.regole=true`, autoplay `--stratega-regole si`): annulla le operazioni
  con successo stimato < 30%, continua le altre, avvia i candidati con valore d'alleanza positivo piu' alto fino al massimo; liste vuote;
  ai punti di decisione l'azione del motore. Nessuna rete, costo 0.

### Tenute spente e campagne in ombra (step R2a)

- **Tenute spente di default** (solo voce Stratega LLM): `StrategaInterruttori` ha un default per interruttore (`liste` acceso,
  `tenute` SPENTO, `ombra` acceso); proprieta' `Pro+ stratega llm spento: <alleanza>` e `Pro+ stratega llm acceso: <alleanza>`
  (autoplay `--stratega-spento`, `--stratega-acceso`). Niente candidati tenuta/rinforzo ne' fuori lista; la fase di tenuta DOPO una
  conquista non cambia. Prompt strategico v5: le istruzioni sulle tenute sostituite da una riga ("spenti, non proporli fuori lista").
- **Pianificatore di campagna IN OMBRA** (`ai/proplus/CampagnaOmbra`, chiamato in `ProPlusAi.purchase` dopo `Difesa.calcola` per le
  nazioni dello Stratega tranne la Francia, e a fine turno da `ProPlusStrategaAi.place`): solo diario, nessun effetto sul gioco
  (nessun acquisto, nessuna unita' trattenuta, nessuna chiamata LLM, nessun campo nel contratto, nessun dado: solo
  `ProBattleUtils.estimateStrength` e una stima d'attacco nemica di Hard per la regola 2.6). Valori in `stratega-llm.json`
  `campagna_ombra` (con la nota sulle approssimazioni), NON tarati.
  - 2.1 obiettivi: VC e capitali nemiche raggiungibili via terra (territori conquistabili: terra, non impassabili, di un'alleanza)
    entro `orizzonte_round` 6 tappe, al massimo 4 per nazione (le piu' vicine); le altre con una costa "non valutato: sbarco";
    etichetta con i piani della dottrina che le hanno come obiettivo o tappa.
  - 2.2 percorso a tappe (`GameMap.getRoute`), un round per tappa; guerra non ancora in corso: "possibile ora: la dichiara il tiro
    casuale di Hard (ProPoliticsAi:153-160)" (non cambiato), oppure il round della dichiarazione del piano, altrimenti "non possibile".
  - 2.3 arrivo = distanza / movimento (terra per territori conquistabili; aerei distanza / (movimento − 1)).
  - 2.4-2.5 punta come flusso per i margini 60/70/80: arrivi del round, battaglia con i difensori attesi (presenti + quota impegno
    A3 delle unita' di terra nemiche confinanti + fanterie comprabili nelle fabbriche nemiche entro 2), perdite attese, superstiti
    che reggono il contrattacco (terra entro 2, aerei entro movimento/2, stessa quota + acquisti); forza necessaria e scarto alla
    prima tappa che blocca; la campagna si ferma li'. Approssimazione dichiarata: forza nemica e perdite x `fattori_percentile`.
  - 2.6 retrovie e percorso (al massimo 10 territori propri per valore): a) nessuna unita' di terra nemica (sbarchi compresi) puo'
    arrivarci ⇒ 0; b) forza piccola (<= `forza_piccola_tuv`) ⇒ 0, oppure 1 fanteria se carri nemici possono fare blitz verso un
    territorio che conta; minaccia reale su un territorio che non conta ⇒ 0 (si accetta di perderlo); c) minaccia reale su un
    territorio che conta (VC/capitale, fabbrica propria, sul percorso, obiettivo nazionale) ⇒ presidio minimo di fanteria e
    artiglieria al margine 70, mai carri ne' aerei; "RILASCIATO" quando torna a)/b). Nel diario perdita attesa senza presidio e costo
    del presidio.
  - 2.7 acquisti per round (tipo e fabbrica per tempo d'arrivo, punto di ammassamento) e fattibilita' (scarto colmabile entro la
    scadenza con il 70% dei PU dopo la difesa di Hard dell'ultimo turno registrato).
  - 2.8 verifica a +1/+2/+3 round: difensori previsti contro reali (obiettivo e tappe), reddito e forza propria, perdita della prima
    tappa per margine contro la battaglia reale, regola 2.6 (attaccato/perso nel turno nemico dopo, unita' lasciate da Hard).
  - 2.9 uscite: `campagna-ombra-R<N>-<alleanza>.json` nel diario, blocco "Campagne IN OMBRA (nessun effetto sul gioco)" in
    `stratega.txt`, stato compatto `Pro+ stratega ombra: <alleanza>` (< 20 KB), `stratega_llm.<alleanza>.ombra` nel riepilogo,
    sezione "Campagne in ombra" del riepilogo generale.
- Test: `CampagnaOmbraTest` (ombra accesa = ombra spenta per le risposte del tuning e le proprieta' della partita; stato salvato e
  ricaricato; i tre casi della 2.6 e il rilascio), `NudoHardTest.axisStrategaWithoutKeyAnswersAsHardWithTheShadowOn` (turno intero),
  `AutoplayTest.shadowCampaignsOfStepR2aAreSummarized`.

### Misure in ombra R2a-bis (step R2a-bis, ancora nessun effetto sul gioco)

- `ai/proplus/CampagnaOmbraMisure` (nuova) + `CampagnaOmbra` (stesse chiamate di R2a). Valori in `stratega-llm.json`
  `campagna_ombra.r2a_bis`, ognuno `{"valore", "origine"}` (tutti "scelto", nessuno tarato; la quota iniziale di M2 non e'
  misurabile dalla run 42976494562 perche' non registrava i difensori presenti).
- **1.1 calcolatore vero**: `CampagnaOmbraMisure.calcolatore` legge (riflessione, nessuna modifica) l'`IBattleCalculator` dentro
  `ProOddsCalculator` (lo stesso di Hard). Perdite per simulazione: `ListBackedAggregateResults.getResults()` oppure la lista
  privata `runs` di `BoundedContextAggregateResults` (contatori dei superstiti per tipo, costo per NOME del tipo); se nessuna
  delle due e' leggibile, `ripetizioni_senza_lista` chiamate da 1 simulazione. Percentile = rango piu' vicino (monotono sulle
  stesse simulazioni). `simulazioni_calcolatore` 100. Cache per unita' identiche (i margini condividono la prima tappa).
- **Flusso v2** (`Calcolo.flussoV2`, campo `margini_v2` di ogni campagna, accanto a `margini` R2a che resta = M0 con i fattori):
  difensori del modello scelto (1.5), nessun fattore; ogni tappa stimata, tranne la PRIMA che la stima blocca e l'OBIETTIVO,
  combattute col calcolatore: presa = vittoria dell'attaccante, perdite = percentile del margine, tenuta = 100 − vittoria del
  contrattacco (`contrattacco`, invariato) sui superstiti. Forza necessaria/scarto = stima; se il calcolatore blocca dove la
  stima basta: "scarto non stimabile", non fattibile. Errore del calcolatore ⇒ la tappa resta stimata (`errore_calcolatore`).
- **1.2 tre modelli** in ogni previsione (`previsioni[].modelli`: tipo capitale/VC/fabbrica/altro, guerra gia' in corso,
  presenti, per round [M0, M2]); M1 = presenti. Verifica: righe `verifica.difensori_modelli` (una per territorio e round della
  previsione), stato `quote_m2` (per orizzonte: somma aumento reale, somma aumento previsto da M0 ⇒ quota di M2, limitata
  [0, 2]) e `errori_modelli` (ultimi 200 errori assoluti per modello). Modello del flusso v2 = mediana dell'errore assoluto piu'
  bassa con >= 10 verifiche per modello, prima `m2`.
- **1.3 contrattacco sulla punta**: a inizio turno (`inizioTurno`, stato `inizio.<nazione>`) i territori nemici e la posizione
  delle unita' di terra; a fine turno (`fineTurnoBis`, chiamato da `fineTurno`) i territori presi dalla nazione (da chiunque)
  ⇒ `punte` (forza nemica massima in portata = tutte le unita' di `contrattacco` senza quota ne' acquisti, tenuta stimata con
  `estimateStrength`); alla verifica successiva `verifica.contrattacco` (attaccato, forza impegnata = TUV perso dall'attaccante
  + `remainingAttackingUnits` del `BattleRecord`, tenuto).
- **1.4**: `previsioni[].perdite_calcolatore` (percentili della prima tappa del round valutata col calcolatore) contro la
  perdita reale ⇒ `verifica.battaglie_calcolatore` e `statistiche.battaglie_calcolatore_<m>`.
- **Misura 2** (`fineTurnoBis`): per ogni unita' di terra propria (infrastrutture escluse, antiaerea a parte) nei territori
  dei casi a/b del round: classe (`CampagnaOmbraMisure.classe`: antiaerea, appena piazzata, mossa nel turno, bloccata = nessun
  territorio amico entro il movimento piu' vicino al fronte, in transito = ferma da 1 turno e avvicinata nel turno prima,
  ferma davvero = ferma da >= 2 turni e spostabile, ferma da 1 turno), tipo, TUV, fabbrica, ferma da, distanza dal fronte
  (BFS sui territori di terra dal nemico piu' vicino, a fine turno). Stato `traccia.<nazione>` (id corto → [indice del
  territorio, round d'arrivo, avvicinata]; l'inizio partita conta come round 0), aggregato `misura2.<nazione>.<caso>.<classe>`
  = [unita'-turno, TUV] + `turni` + `tipi_ferme`; righe in `retrovie-ombra-R<N>-<alleanza>.json`. Caso b: `persi` (territorio,
  valore, chi l'ha preso, unita' di Hard a fine turno prima della caduta, `ripreso` "R<n>" se dell'alleanza a fine turno della
  nazione nel round della scoperta o in quello dopo, altrimenti "no").
- Autoplay: `OmbraDettagli` legge i file `campagna-ombra-*` e `retrovie-ombra-*` del diario ⇒ `riepilogo.json`
  `ombra_dettagli.<alleanza>` (righe compatte complete); `ConfrontoOmbra.writeBis` scrive la sottosezione "R2a-bis" della
  sezione "Campagne in ombra" (1.2 per orizzonte e per tipo/guerra, quota M2 osservata, 1.3 calibrazione a fasce del 20% e
  quota impegnata per tipo, 1.4, 1.5 fattibili v2 contro M0 per round, Misura 2 per nazione e caso, caso b persi/ripresi).
- Test: `CampagnaOmbraTest` +4 (percentili ordinati sulle stesse forze e cache; i tre modelli, la quota osservata e la scelta
  del modello; le classi della Misura 2 su una posizione costruita; un territorio preso diventa una punta) + controllo del
  calcolatore nell'ombra accesa = spenta; `AutoplayTest.shadowMeasuresOfStepR2aBisAreSummarized`.

### Impegno (step R2b)

Prima campagna che AGISCE, solo per la voce "Pro+ Stratega LLM" e solo con l'interruttore `impegno` acceso (default nel file dati
`stratega-llm.json` `impegno.acceso_di_default` = **false**: spento = comportamento di prima; si accende con la proprieta' `Pro+
stratega llm acceso: <alleanza>` = `impegno` (autoplay `--stratega-acceso Axis=impegno`) o con la casella "Impegno" del pannello
Stratega, salvata nel profilo utente e copiata nella partita da `Stratega.registra`). Una nazione (tutte quelle dello Stratega tranne
la Francia), via terra, un obiettivo alla volta; niente tandem, niente sbarchi, tenute spente, nessuna chiamata LLM. Valori in
`stratega-llm.json` `impegno`, ognuno `{"valore", "origine": "scelto"}`: quota PU 70, soglia d'attacco 70, durata minima 3 round,
margine di scelta 80, distanza "vicina" 2, tipi vicino infantry/artillery, lontano mech_infantry/armour. NON tarati.
- **1 Obiettivo** (`Impegno.inizioTurno`, acquisto della nazione, tabellone reale): candidati = `CampagnaOmbra.Calcolo.obiettivi` (VC
  o capitali nemiche raggiungibili via terra entro 6 tappe, le 4 piu' vicine) con il proprietario GIA' in guerra con la nazione;
  per ognuno il flusso v2 col modello M1 (`valutaImpegno`, difensori = presenti) al margine 80. Regola (`Impegno.scegli`): i
  fattibili, quello con meno tappe; se nessuno, lo scarto piu' piccolo (scarto "non stimabile" dopo quelli stimati); parita': indice
  di valore piu' alto, poi nome. Nessun obiettivo nuovo con la capitale propria in caso c.
- **2 Inerzia** (`Impegno.motivoFine`, nessuna stima tra gli ingressi): chiude solo "preso" (della nazione), "non piu' del nemico"
  (proprietario non piu' in guerra / di un'altra alleanza), "rotta assente" e "capitale in caso c" — le ultime due solo dopo 3 round;
  prima: rotta assente = operazione SOSPESA nel turno (niente acquisti/instradamento/forzature), capitale in caso c = si continua con
  il presidio della capitale tolto dai PU. Preso ⇒ al turno dopo si sceglie il successivo.
- **3 Acquisti**: PU_op = floor(70% x (PU del turno − costo del presidio minimo 2.6 caso c MANCANTE in capitale e fabbriche proprie;
  le unita' di terra proprie gia' li' contano prima)) (`puOperazione`). Fabbrica = propria piu' vicina via terra alla prossima tappa;
  distanza <= 2 ⇒ fanteria/artiglieria, altrimenti fanteria meccanizzata/carri, alternati finche' entrano nei PU (`composizione`).
  Ordini obbligati (`ProPurchaseOrders`, proprieta' `Pro+ stratega impegno ordine: <nazione>`) con `minimumShare` = 100 x PU_op /
  PU: `strategyCapPercent` torna vuoto in quel turno ⇒ percorso `purchaseDefendersWithinPlanShare` di ProPurchaseAi (capitale e
  fabbriche a rischio difese senza limite, il resto della difesa entro il 30%), poi gli ordini, poi Hard col resto. Al piazzamento
  (`dopoIlPiazzamento`) le unita' nuove dei tipi ordinati (prima le piu' vicine alla fabbrica) sono seguite per id.
- **4 Instradamento** (`nonCombattimento`, SOLO non combattimento, prima di quello di Hard): unita' di terra seguite + quelle che a
  inizio turno erano in territori di caso a/b (regola 2.6 su tutti i territori propri con unita' di terra), che hanno ancora
  movimento (Hard non le ha usate in battaglia) ⇒ un passo verso il raduno (territorio amico prima della prossima tappa sulla rotta;
  tappa e raduno fissati all'acquisto, ricalcolati solo se la tappa e' stata presa nel combattimento), SOLO su terra amica, verso il
  territorio amico piu' vicino al raduno dove il contrattacco realistico (`CampagnaOmbra.Calcolo.contrattacco`, quota d'impegno A3 +
  acquisti) col calcolatore di Hard vince <= `soglia_sicurezza_raduno` (40, la stessa dei raduni delle operazioni). Unita' mosse e
  gia' al raduno restano ferme nel non combattimento di Hard (`holdForOperation`). Nel combattimento NIENTE e' trattenuto.
- **5 Attacchi forzati** (all'acquisto, prima che Hard pianifichi): prossima tappa e obiettivo (proprietario in guerra), attaccanti
  = TUTTE le unita' in portata secondo le opzioni d'attacco di Hard (`populateAttackOptions`: max + anfibie), difensori reali;
  vittoria e P80 delle perdite dal calcolatore (`CampagnaOmbraMisure.simula`, 100 simulazioni). >= 70% (`forza`) ⇒ proprieta' `Pro+
  stratega impegno forzati: <nazione>` = "round|T1;T2" ⇒ `OperazioniCongiunte.forzaBersaglio` vero (stesso meccanismo delle
  offensive: priorita' +1000, tenuto anche se "non conviene"), soglia minima di Hard = 30% (`Direttive.SOGLIA_MINIMA_ASSOLUTA`).
  Sotto soglia decide Hard. Esito nel non combattimento: vinto / non preso / non attaccato, perdite reali (registri di battaglia)
  contro il P80. Limite visto in prova: la forzatura NON scavalca la "postura difensiva" di Hard (capitale minacciata, motivo
  `defensive_stance`) e Hard puo' attaccare con meno unita' di quelle contate (basta il 30%).
- **6 Copertura**: sulle tappe prese (registro `tappe_prese`) ancora proprie e in caso c, restano ferme fino al presidio minimo 2.6
  di fanterie/artiglierie presenti; niente altro.
- **7 Opportunismo**: nessuna unita' trattenuta o riservata nel combattimento (test). Approssimazione: la priorita' +1000 di un
  bersaglio forzato puo' togliere unita' ad altri attacchi di Hard nello stesso turno (non misurato in controfattuale).
- **Diario**: blocco "=== R<n> <nazione> - Impegno (step R2b, AGISCE) ===" in `stratega.txt` (candidati, scelta/cambio col motivo,
  rotta, tappa, raduno, attacchi con vittoria/P80/FORZATO, acquisti, esiti, copertura, instradate, comprate) + record `impegno` in
  `stratega.jsonl`. Stato per alleanza `Pro+ stratega impegno: <alleanza>`: `nazioni` (obiettivo, dal_round, tappa, raduno, unita'
  seguite per id, max 200), `storia` (obiettivi chiusi: durata, motivo, round di presa), `turni` (PU turno/caso c/op/spesi, ordine,
  fabbrica, attacchi, instradate, perse in viaggio), `perse_viaggio` (unita' seguite vive a fine turno e sparite al turno dopo).
  `stratega_llm.<alleanza>.impegno` nel riepilogo (senza id).
- Test: `ImpegnoTest` (8: obiettivo NON annullato quando la stima scende; 70% dei PU dopo il presidio della capitale; l'unita' non
  usata in battaglia si avvicina al raduno; nel combattimento Hard ha le stesse unita' con interruttore acceso e spento; forzato a 70
  e non a 69; spento = Hard; regola di scelta; valori del file), `AutoplayTest.theCommitmentOfStepR2bIsSummarized`.

### Preparazione e programmi (step R2c)

Corregge R2b e aggiunge i programmi permanenti, tutto dietro lo stesso interruttore `impegno` (spento di default = comportamento di
prima; solo voce "Pro+ Stratega LLM"). Nessuno sbarco coordinato tra nazioni, nessun conto d'alleanza, tenute spente, livello LLM
invariato; l'assalto anfibio lo decide Hard. Valori in `stratega-llm.json` `impegno.r2c` (tutti "scelto", nessuno tarato), letti da
`Programmi.Config`. Classe nuova `ai/proplus/Programmi` (config, carico bilanciato, spola USA, Medio Oriente); hook nuovi in `ProTuning`
(default vuoti, Hard non li usa): `purchaseOrdersBought` (chiamato in `ProPurchaseAi` dopo `ProPlanPurchase.buy`) e `reviewAttacks`
(chiamato in `ProCombatMoveAi.doCombatMove` subito prima di `doMove`), implementati in `ProPlusTuning` → `Impegno.acquistato`/`rivedi`.
- **A due fasi** (`Impegno.fase`, stato `fase` della nazione, `turni[].fase`): a ogni acquisto il calcolatore con TUTTE le unita' in
  portata e i difensori reali sulla prossima tappa: >= `soglia_esecuzione` 70 ⇒ ESECUZIONE (raduno nel territorio prima della tappa,
  attacchi forzati su tappa/obiettivo >= 70), altrimenti PREPARAZIONE: acquisti per l'operazione, nessun attacco forzato, nel non
  combattimento le SOLE unita' comprate vanno verso la **base sicura** (`baseSicura`: la via di terra amica dalla fabbrica d'acquisto
  alla partenza + la rotta prima della tappa; il territorio proprio piu' vicino alla tappa dove il contrattacco realistico
  `CampagnaOmbra.Calcolo.contrattacco` vince <= `soglia_base_sicura` 40; nessuna ⇒ restano dove piazzate). Un obiettivo non fattibile
  resta (inerzia R2b) ma in PREPARAZIONE.
- **B1**: si instradano solo le unita' comprate (seguite per id); le unita' dei territori di caso a/b non sono piu' toccate (tolto
  `retrovie`).
- **B2** (`Impegno.rivedi`, `completa`): dopo che Hard ha scelto attacchi e unita', ogni bersaglio forzato e' ricalcolato con le unita'
  che Hard manda davvero (+ sbarchi e bombardamenti): >= 70 "eseguito"; sotto, unita' di terra proprie in portata (`getMaxUnits`) non
  impegnate in altre battaglie aggiunte una alla volta (attacco piu' alto prima) fino a 70 "completato"; altrimenti "rinunciato" (unita',
  sbarchi, bombardamenti tolti: nessuna mossa). Aerei mai aggiunti (atterraggio non verificato). Registro in memoria per
  "nazione|round" (ultima chiamata = piano eseguito: con il piano memorizzato di Hard la revisione avviene nella simulazione
  dell'acquisto e passa al piano reale), scritto nel diario e in `turni[].revisioni` / `attacchi[].revisione` al non combattimento.
- **B3 "0 PU di 23 previsti"** (diagnosi su seme 1102, Alleati): due cause. (1) `purchaseDefendersWithinPlanShare` di Hard difende
  SENZA limite capitale e fabbriche con win% nemica oltre la sua soglia di guardia (non la regola 2.6): es. Russia R4 "difesa 33 PU,
  al piano 6 PU" con presidio 2.6 stimato 7 PU ⇒ gli ordini restano senza PU. Non cambiato (e' la difesa di Hard, coerente con C2-bis):
  ora registrato (`turni[].pu_dopo_difesa_hard`, `obbligati_mancanti`, riga "Ordini non eseguiti" nel diario). (2) Le unita'
  "dell'operazione" erano riconosciute per TIPO tra tutte le nuove unita' (anche quelle comprate da Hard, o perse se Hard piazzava
  altrove) ⇒ conti sbagliati in piu' e in meno. Corretto: `Impegno.acquistato` salva il `ProPurchaseOrders.Result` (unita' per
  categoria e piazzamenti) e `dopoIlPiazzamento` segue esattamente le unita' delle categorie "impegno <tipo>" nei territori di
  piazzamento del risultato. `quota_minima` degli ordini = PU davvero ordinati (campagna + programma) / PU.
- **B4**: campagne via terra solo per `nazioni_campagna_terra` (Germans, Italians, Russians, British); USA mai (obiettivo chiuso
  "nazione senza campagne via terra"); Regno Unito solo verso `territori_uk_campagna` (Africa/Medio Oriente: tra VC e capitali in
  pratica l'Egitto); Russia (`nazioni_solo_fattibili`) sceglie solo un obiettivo fattibile a margine 80 (`scegliFattibile`) e, se
  quello tenuto non e' piu' fattibile, nel turno e' SOSPESA "gioca come Hard" (niente ordini, forzature, instradamenti).
- **C programma USA** (dal R1, `Programmi.Turno.acquisti` + `spola`): PU = 70% di (PU − presidio della capitale in caso c); regola del
  **carico bilanciato** (`Programmi.caricoBilanciato`, testo in `impegno.r2c.carico_bilanciato`): scorta di cacciatorpediniere solo
  finche' la zona peggiore della spola (zone del porto, rotta piu' corta fino alle zone del Regno Unito, zone della base) ha contrattacco
  realistico (unita' in portata dalle opzioni nemiche di Hard, quota A3 di `StimaUnica.impegno`, piu' care prima) > 40, max 2 per turno;
  truppe (`tipi_spola` alternati) solo finche' truppe in attesa al porto + comprate entrano nei posti liberi dei trasporti entro 2 zone
  dal porto (+ trasporti comprati ora); un trasporto MAI se ne esiste uno fermo e vuoto entro 2 zone dal porto, altrimenti quando le
  truppe non entrano piu'. Spola nel non combattimento (prima di Hard): 1) trasporti vuoti nelle zone della spola o entro 2 dal porto
  tornano verso il porto; 2) carico al porto (unita' con attacco > 0, le piu' grandi prima, presidio della capitale escluso); 3) trasporti
  carichi verso la zona del Regno Unito piu' vicina, fermandosi nella zona piu' lontana raggiungibile con contrattacco realistico <= 40
  (con navi da guerra della stessa zona come scorta, max 4), e scarico nel Regno Unito se arrivati. Unita' mosse/tenute ⇒
  `holdForOperation` (solo non combattimento). **Regole della mappa verificate (XML)**: prima della guerra gli USA sono "Neutrality" con
  i britannici (non possono scaricare nel Regno Unito), hanno `movementRestrictionTerritories` (zone 65, 66, 85, 86, 88, 90, 103, 106,
  107, 116, 117, 120, 122 vietate) tolte dal trigger "Americans_Unrestricted_Movement" quando sono in guerra con un paese dell'Asse; la
  guerra arriva al piu' tardi dopo il piazzamento USA del R3 (`triggerAttachment_Americans_War_Japanese`, alleati con i britannici) o
  prima se attaccati ⇒ prima della guerra i trasporti caricano e restano carichi sulla costa est (tenuti nel non combattimento).
- **D programma Regno Unito**: 1) Londra presidio 2.6 caso c e 2) Egitto (`presidi_prioritari`) tolti dai PU (`puCasoC`); Egitto perso
  ⇒ obiettivo via terra come in R2b; 3) forza da sbarco in patria = 70% di (PU − presidi − PU della campagna) col carico bilanciato
  (porto United Kingdom); 4) Medio Oriente (`medio_oriente`): Persia e Northwest Persia (Neutral_Allies, Friendly_Neutral con
  canTakeOverOwnedTerritory) prese entrando nel non combattimento con la unita' piu' economica gia' confinante (non da capitale/Egitto);
  Iraq (Neutral_Axis, Unfriendly_Neutral = guerra, 3 fanterie, 2 PU, nessun trigger politico) attacco forzato solo con le unita' gia' in
  portata e vittoria >= 70, nessun acquisto; Arabia Saudita, Turchia, Afghanistan (Neutral_True, Strict_Neutral) esclusi: servirebbe la
  guerra ai neutrali veri, che rende gli altri neutrali veri amici dell'Asse.
- Ordini: un solo ordine per nazione e round (`Pro+ stratega impegno ordine: <nazione>`) con `tipi` (campagna, categorie "impegno
  <tipo>") e `tipi_programma` (categorie "programma <tipo>"), fabbriche preferite = fabbrica della campagna + porto del programma.
- Riepilogo: `Monitor` conta `portate_regno_unito` per nazione e round (unita' scaricate da trasporti nel Regno Unito); `ConfrontoImpegno`
  scrive la sottosezione "Step R2c: preparazione e programmi" (reddito e TUV per alleanza a R4/R8/R12, Russia, fasi e attacchi forzati
  per nazione, USA e Regno Unito per round: trasporti / fermi vuoti / portate nel Regno Unito / sbarcate in Europa occidentale, primo
  sbarco USA, prima testa di ponte tenuta 2 round = `prima_testa_di_ponte` del riepilogo).
- Test: `ImpegnoTest` +7 (valori del file; esecuzione a 70 e non a 69; in preparazione nessuna unita' preesistente mossa e nessun
  attacco forzato; attacco forzato rinunciato con le unita' di Hard insufficienti e intatto a interruttore spento; Russia senza obiettivo
  fattibile = Hard; carico bilanciato; trasporto USA carico verso il Regno Unito e vuoto di ritorno, posizione costruita su Global 1940),
  tolto il test R2b dell'instradamento delle retrovie; `AutoplayTest.theCommitmentOfStepR2bIsSummarized` esteso.

### Sbarco coordinato e teatri (step R3a)

Dietro lo stesso interruttore `impegno` (spento di default = comportamento di prima), solo per le nazioni di `impegno.r3a.nazioni_r3a`
(Americans, British, Russians: gli Alleati dello Stratega). **L'Asse resta R2c** (nessuna riga di codice dell'Asse cambia; tandem
Italia-Germania, livello LLM e tenute non toccati). Valori in `stratega-llm.json` `impegno.r3a`, ognuno `{"valore", "origine"}` ("scelto",
"richiesta dello step R3a" o "dalla mappa"), letti da `SbarcoCoordinato.Config`; nessuno tarato. Classi nuove `ai/proplus/SbarcoCoordinato`
(teatro, preparazione, via libera, sbarco, Regno Unito, testa di ponte, tetto) e `ai/proplus/RegistroDecisioni` (registro); hook nuovi in
`ProTuning` (default = Hard): `buysSeaTransports` (chiamato in `ProPurchaseAi.purchase`: `false` ⇒ `ProPurchaseOptionMap.withoutSeaTransports()`,
copia senza trasporti) e `amphibiousHeldUnits` (in `ProCombatMoveAi`, aggiunte alle unita' gia' usate quando Hard sceglie chi caricare per
un assalto anfibio); `ProPlusAi`: trattenute R3a unite a quelle dell'acquisto (`holdCombatForPurchase`) e `playCoordinatedLanding` nel
combattimento prima di Hard (poi `holdCombat` delle unita' mosse e `forgetStoredCombatMove`).
- **1 Teatro** (`Turno.teatro`, acquisto USA = inizio round per l'alleanza; il Regno Unito usa il teatro corrente): NORD (base United Kingdom)
  e SUD (base Morocco se dell'alleanza, altrimenti Gibraltar). Bersagli dalla mappa (zone entro 2 dalle zone della base, territori costieri
  di proprieta' iniziale tedesca/italiana/francese, vedi `teatri`), a ogni turno solo quelli di un nemico. Aperto = base dell'alleanza E
  zona peggiore della spola verso la base (`Programmi.Turno.zoneSpolaVerso`: zone del porto USA, rotta piu' corta, zone della base) con
  contrattacco realistico (`Programmi.Turno.vittoriaNemicaMare`, navi alleate della zona + la scorta USA nelle zone del porto) <= 40.
  Uno aperto = quello; entrambi = punteggio (tenuta d'alleanza stimata del bersaglio migliore x valore; forze = unita' di terra USA e UK
  alla base + carico USA entro 2 zone dalla base; tenuta = 100 − vittoria del contrattacco realistico `CampagnaOmbra.Calcolo.contrattacco`
  contro i superstiti medi della battaglia; valore = produzione + 10 VC + 10 capitale + PU degli obiettivi nazionali tolti, dalla mappa);
  nessuno = resta. Inerzia `sceltaTeatro`: cambio solo dopo 3 round dall'ultimo e mai con un trasporto USA carico fuori dalle zone del
  porto (ondata in mare). La base segue il teatro; `Programmi.Turno.base()` legge la base di R3a (`SbarcoCoordinato.baseCorrente`).
- **2 Sbarco coordinato**. PREPARAZIONE (`preparazione`): le unita' di terra della nazione alla base sono trattenute dal combattimento di
  Hard (anche dal carico anfibio) e tenute ferme nel non combattimento (difendono la base); non se la base confina con terra nemica (testa
  di ponte, Marocco accanto a un'Algeria dell'Asse: li' le usa la campagna via terra). VIA LIBERA (`viaLiberaUsa`, acquisto USA, forze
  reali): per ogni bersaglio del teatro il carico in posizione (`piano`: trasporti USA carichi che raggiungono una zona di sbarco libera
  da nemici entro il movimento, rotta senza nemici + trasporti vuoti nelle zone della base caricati con le unita' USA alla base, le piu'
  forti prima, meno il presidio caso c se la base e' il porto); vittoria e superstiti medi dal calcolatore di Hard, P80 dal calcolatore
  vero (100 simulazioni); rinforzi britannici (`rinforziUk`: trasporti britannici nelle zone della base che arrivano alla zona del
  bersaglio, riempiti con le unita' britanniche alla base meno il presidio di Londra dell'ultimo turno UK); via libera = vittoria >= 70 E
  contrattacco realistico <= 40 contro superstiti USA + rinforzi (`viaLibera`); tra i bersagli col via libera quello di valore piu' alto.
  SBARCO (`combattimento`, combattimento USA prima di Hard): carico, rotta, scarico di TUTTO il carico in un colpo; le unita' mosse escono
  dal piano di Hard. REGNO UNITO (`azioneUk`, acquisto UK): ondata USA eseguita e bersaglio preso ⇒ RINFORZO nel non combattimento (truppe
  alla base sui trasporti britannici verso la testa di ponte); ondata eseguita e bersaglio non preso ⇒ COMPLETAMENTO nel combattimento solo
  con vittoria >= 70; nessuna ondata ⇒ sbarco da solo solo con vittoria >= 70 e tenuta da solo (contrattacco <= 40 contro i soli superstiti
  britannici). DOPO (`aggiornaBase`, acquisto USA): la testa di ponte piu' recente del teatro, dell'alleanza e con le zone della spola sicure
  (<= 40) diventa la base; base persa ⇒ base del teatro.
- **Campagne** (`Impegno.scegliObiettivo` con `SbarcoCoordinato.bersagliCampagna`, stesso meccanismo a fasi di R2c): Regno Unito prima
  l'Egitto (R2c), poi i territori dell'Asse in Nord Africa (`nord_africa`, anche non VC) e, con una testa di ponte tenuta, France, Germany,
  Southern Italy; USA: con una testa di ponte tenuta France, Germany, Southern Italy; Nord Africa solo col teatro SUD e base Morocco o
  Gibraltar. Acquisti per il Nord Africa solo da una fabbrica propria entro `distanza_fabbrica_africa` 3 via terra (il Regno Unito non ne ha:
  verificato sulla mappa, fabbriche britanniche United Kingdom, Quebec, Union of South Africa); le campagne dalla testa di ponte non hanno
  fabbriche collegate via terra (nessun acquisto, solo attacchi forzati >= 70 con le unita' in portata).
- **4 Tetto ai trasporti** (`tetto`): con un trasporto fermo e vuoto entro 2 zone dalle zone del porto (`Programmi.Turno.fermiVuoti`, la
  definizione del carico bilanciato) la proprieta' `Pro+ stratega r3a tetto: <nazione>` = round ⇒ `buysSeaTransports` falso ⇒ nessun
  trasporto nelle opzioni d'acquisto del turno (anche per gli ordini del programma, che non ne comprerebbero comunque).
- **5 Russia**: non e' in `nazioni_campagna_terra` di R3a ⇒ nessun obiettivo, nessun ordine, nessuna forzatura: gioca come Hard.
- **Registro** (`RegistroDecisioni`, file `impegno-decisioni.jsonl` nella cartella della partita del diario + memoria per i test): righe
  `decisione` (id `R<round>-<nazione>-<tipo>-<n>`, round, nazione, tipo in `TIPI`, `alternative` con i numeri, `scelta`, `motivo`
  {codice in `MOTIVI`, frase}, `parametri`) e righe `esito` con lo stesso id: sbarco (preso, perdite reali e P80, vittoria e tenuta stimate),
  tenuta dopo 1 e 2 round (controllata all'acquisto USA dei round dopo), unita' perse in mare (trasporti dell'ondata; trasporti e carico della
  spola a fine turno), rinforzi arrivati, comprate da Hard col tetto, obiettivo della campagna preso (entro 6 round). Tipi: teatro,
  bersaglio_sbarco, via_libera_o_attesa (con `parametri.manca`), sbarco, rinforzo_testa_di_ponte, cambio_base, acquisto_programma,
  tetto_trasporti, spola, campagna_africa, campagna_testa_di_ponte (aggiunto), unita_trattenute.
- Stato per alleanza `Pro+ stratega r3a: <alleanza>` (teatro, teatro_dal, base, base_origine, round_teatro, cambi, ondata, azione_uk,
  teste_di_ponte, pendenti, trattenute, tetto_aperto, seq); `stratega_llm.<alleanza>.impegno.r3a` nel riepilogo (teatro, base, round per
  teatro, cambi, teste di ponte).
- Autoplay: `Monitor` per round `nazioni.<Americans|British>.regioni` (unita' di terra per regione: base della spola di R3a o United Kingdom,
  patria, mare, europa_occidentale, mediterraneo_nord_africa, altrove; liste in `impegno.r3a.regioni`), `nord_africa_asse`, `teatro_r3a`;
  `RegistroLettura` ⇒ `riepilogo.json` `impegno_decisioni` (righe complete del registro); `ConfrontoR3a` scrive la sottosezione "R3a: sbarco
  coordinato e teatri" della sezione "Impegno". Modalita' `[impegno]`: semi 1121-1140.
- Test: `SbarcoCoordinatoTest` (11: valori del file; interruttore spento = niente; teatro unico aperto scelto e base che segue il teatro;
  nessun cambio prima di 3 round ne' con ondata in mare (regola + tabellone); via libera a 70 e non a 69; via libera negata senza tenuta
  d'alleanza e concessa coi rinforzi britannici (tabellone costruito su Normandy); sbarco con tutto il carico in un colpo; Hard non usa la
  forza trattenuta (opzioni d'attacco e carico anfibio) e la tiene ferma nel non combattimento; tetto ai trasporti ed esito; Russia = Hard;
  ogni decisione ha la riga nel registro e l'esito quando verificabile), `AutoplayTest.theCoordinatedLandingOfStepR3aIsSummarized`.

### Scelte di R3a (fatte da solo, con il perche' e l'alternativa scartata)

1. **Bersagli dei teatri** dalla mappa (script sulle connessioni dell'XML): zone entro 2 (un movimento di trasporto) dalle zone della base,
   territori costieri di proprieta' iniziale dell'Asse o francese (la Francia cade al R1). Esclusi French Guiana e French West Africa (fuori
   teatro); Holland Belgium e Normandy solo NORD, Morocco solo SUD. Scartato: calcolarli a ogni turno (cambierebbero col tabellone).
2. **Base SUD**: Morocco se dell'alleanza, altrimenti Gibraltar (Morocco confina con l'Algeria: serve alla campagna USA verso est).
   Scartato: Gibraltar prima (isolata via terra).
3. **Teatro aperto** richiede anche una base dell'alleanza; i difensori della zona sono le navi alleate li' + la scorta USA delle zone del
   porto (come la scorta del carico bilanciato). Scartato: zona senza difensori (sempre chiusa con un solo aereo nemico in portata).
4. **Punteggio**: tenuta = contrattacco realistico contro i superstiti MEDI della battaglia di tutte le forze alla base o in arrivo; valore
   = indice di R2b (produzione + 10 VC + 10 capitale) + PU degli obiettivi nazionali italiani tolti (Nord Africa 5, Southern France 5).
   Scartato: pesare anche la vittoria (il prompt dice tenuta x valore).
5. **Scelta del teatro solo nel turno USA**; prima del primo turno USA il Regno Unito usa `teatro_iniziale` NORD.
6. **Forza trattenuta** = TUTTE le unita' di terra della nazione alla base (non solo quelle del programma: R2c non segue per id gli acquisti
   del programma); NON trattenute se la base confina con terra nemica (lo sbarco da li' e' via terra: le usa la campagna). Scartato: seguire
   per id le unita' della spola (le unita' britanniche in patria non sono distinguibili).
7. **Carico anfibio di Hard**: verificato nel codice che `ProCombatMoveAi` sceglie le truppe da caricare da `getUnitsToTransportFromTerritories`
   ignorando le unita' trattenute (il "trattenere" di R2c/operazioni toglie solo le opzioni d'attacco): hook nuovo `amphibiousHeldUnits`, attivo
   SOLO per le nazioni R3a (per l'Asse e per le operazioni LLM nulla cambia). Scartato: correggerlo per tutti (cambierebbe l'Asse).
8. **Carico in posizione**: solo trasporti carichi che arrivano a una zona di sbarco SENZA nemici per una rotta senza nemici (nessuna battaglia
   navale prima dello sbarco) + trasporti vuoti nelle zone della base; niente scorta mossa con l'ondata. Scartato: forzare il passaggio
   (battaglie navali non stimate dal via libera).
9. **Stime del via libera**: vittoria e superstiti medi dal calcolatore di Hard (`ProOddsCalculator`), P80 dal calcolatore vero; rinforzi UK
   = trasporti britannici gia' nelle zone della base nel turno USA (quelli che possono partire nel turno UK) riempiti con le truppe alla base
   meno il presidio di Londra dell'ultimo turno UK. Tra i bersagli col via libera: valore piu' alto, poi vittoria.
10. **Regno Unito**: completa solo un'ondata USA eseguita che non ha preso (solo vittoria >= 70, come da prompt); sbarco da solo solo se
    nel round non c'e' stata un'ondata USA; il rinforzo nel non combattimento usa la stessa routine di carico (`piano`) e non ha soglia.
11. **Testa di ponte → base**: la piu' recente del teatro corrente, tenuta, con la zona peggiore della spola verso di lei <= 40 (stesso
    controllo del teatro aperto). Scartato: diventare base appena presa (il prompt chiede "tenuta").
12. **Campagne**: una per nazione (meccanismo di Impegno); Regno Unito Egitto prima, poi Nord Africa e campagne dalla testa di ponte nella
    stessa scelta (regola di R2b); nessun acquisto senza fabbrica entro 3 via terra (il Regno Unito non ne ha in zona).
13. **Tetto**: vale anche per gli ordini del programma (stessa lista d'acquisto; il carico bilanciato non ne comprerebbe con un trasporto
    fermo e vuoto). "Cosa voleva comprare Hard" NON misurato: servirebbe un secondo acquisto simulato senza tetto (costo, e rischio di
    effetti sul generatore casuale): registrati i fermi vuoti e cosa ha comprato Hard nel turno (esito).
14. **Esiti**: tenuta controllata all'acquisto USA del round dopo (dopo i turni di Italia, Germania e Russia) e di due round dopo; perse in
    mare = id dei trasporti dell'ondata (e di trasporti e carico della spola) assenti all'acquisto USA dopo.
15. **Registro**: tipo in piu' `campagna_testa_di_ponte` (le campagne da una testa di ponte non sono "Africa"); `acquisto_programma` e
    `spola` registrano le decisioni R2c dei programmi (gia' esistenti) per i conteggi.
16. **Regioni delle tabelle**: liste scelte (`impegno.r3a.regioni`); la "base" senza R3a (Hard vs Hard) e' United Kingdom.

### Strategia completa (step R4)

Due blocchi nuovi, ciascuno dietro un interruttore proprio di `StrategaInterruttori` (default dal file dati `stratega-llm.json`
`impegno.r4.assedio_acceso_di_default` / `alleati_r4_acceso_di_default` = **false**: spenti = comportamento di R3a/R2c), entrambi
sopra `impegno` (attivi solo con `impegno` acceso; autoplay `--stratega-acceso Axis=impegno+assedio`, `Allies=impegno+alleati_r4`;
etichette "[con assedio]", "[con alleati_r4]"). Regola comune C (reddito e bonus). Valori in `impegno.r4` (`ConfigR4`), ognuno
`{"valore", "origine"}` ("scelto", "richiesta dello step R4" o "dalla mappa"), nessuno tarato. Nessun cambio al livello LLM, tenute
spente, Russia = Hard. Classi nuove: `ai/proplus/ConfigR4`, `Reddito` (C), `Assedio` (A), `SnodoAlleati` (B, parti pure e Regno Unito);
autoplay `MonitorR4`, `ConfrontoR4`.
- **0 Regola di vittoria** (verificata nel motore): `EndRoundDelegate` (proprieta' "Triggered Victory" = true; "Total Victory" e
  "Honorable Surrender" spente) prova a ogni fine round i trigger di vittoria e chiama `TriggerAttachment.triggerVictory`, che chiude la
  partita solo se il testo `victory` del trigger e' una chiave di `notifications.properties`: sulla mappa non lo e' (3 righe, solo
  dichiarazioni di guerra) ⇒ **nel client di una persona la partita non finisce mai da sola**; il giocatore applica le note della mappa
  ("maintain it for a complete round of play"). L'autoplay finisce quindi con la **lettura delle note** (condizione vera a fine N-1 e
  N ⇒ vittoria al round N) e registra anche la **lettura dell'XML** (primo fine round con la condizione vera: cio' che farebbe il
  trigger se il messaggio esistesse): `riepilogo.json` `vittoria.lettura_xml` / `vittoria.lettura_note` per alleanza
  (`Vittoria.letturaXml`/`letturaNote`), colonna "Lettura XML" nella tabella delle partite complete.
- **A1 Germania** (`Assedio.bersagli`, `livelliGermania`, chiamato da `Impegno.scegliObiettivo`): livello 1 = fabbriche russe
  (`fabbriche_russe` Novgorod, Ukraine, Volgograd: factory_minor dei Russians a inizio partita) di un nemico in guerra; Mosca
  (`capitale_russa` = Russia) a livello 1 solo con nessuna fabbrica rimasta, a livello 0 se il calcolatore di Hard con tutte le unita'
  in portata da' >= `soglia_mosca` 70 nel turno (`vittoriaMosca`); in quel caso l'obiettivo corrente viene chiuso subito
  (`moscaOra`, eccezione all'inerzia di R2b). I territori russi sulla strada sono le tappe (meccanismo di R2b). Senza bersagli (guerra non
  ancora dichiarata, tutto preso) vale la regola di R2b.
- **A2 Rinforzi** (`fabbricheCatturate`, `fabbricaOperazione`): gli ordini dell'operazione tedesca vanno nella fabbrica catturata piu'
  vicina a Mosca (poi le altre in `fabbriche_extra`), il piazzamento verifica la capacita' (`ProPlanPurchase.canPlace`). Mappa: una
  fabbrica catturata produce 3 unita' (factory_minor `canProduceXUnits` 3; factory_major `whenCapturedChangesInto` factory_minor) meno il
  danno. Registro `rinforzo_fabbrica` con l'esito (unita' piazzate per territorio).
- **A3 Bombardamento di Mosca** (`bombardieriDaComprare` all'acquisto, `trattenute`, `bombarda` dall'hook `reviewAttacks`,
  `nonCombattimento`): se Mosca produce (capacita' = 10 − danno > 0) e c'e' una base sicura entro meta' movimento del bombardiere
  (3 territori, contrattacco <= 40), si comprano dalla quota dell'operazione bombardieri fino a coprire la produzione col danno atteso
  (`ceil(produzione / danno atteso)`, danno atteso = (1 − 1/6 antiaerea) x (3,5 + 2) = 4,58, max 2 per turno) e un caccia di scorta se
  a Mosca ci sono intercettori (max 1 per turno); i bombardieri che raggiungono Mosca e possono atterrare dopo sono tolti agli attacchi di
  Hard (`Assedio.trattenute` in `ProPlusAi.purchase`) e nel combattimento entrano come incursione (`ProTerritory.getBombers` di Russia: Hard
  li muove con `isBombing`, il motore chiede il bersaglio e l'AI sceglie la fabbrica), con la scorta (caccia `canEscort`) se ci sono
  intercettori. Nel non combattimento: esito (danno fatto, bombardieri/scorta persi = mandati − ancora su Mosca), bombardieri fermi
  verso la base; al turno tedesco dopo l'esito "unita' prodotte da Mosca" (unita' russe nuove a Mosca). Regole verificate: danno alle
  unita' (`Damage From Bombing Done To Units Instead Of Territories` true), `Use Bombing Max Dice Sides And Bonus` true (bomber d6+2),
  antiaerea propria della fabbrica (`isAAforBombingThisUnitOnly`), intercettori (`Raids May Be Preceeded By Air Battles` true), maxDamage
  20, riparazione a carico della Russia. Hard non lo fa: `ProCombatMoveAi.determineTerritoriesThatCanBeBombed` evita i territori con
  possibili intercettori e le fabbriche con antiaerea.
- **A4 Italia** (`livelliItalia`, `acquistiItalia`, `Programmi` con porto/base italiani): livello 1 Egitto + Nord Africa
  (`italia_livello1`), livello 2 resto dell'Africa e a est dell'Egitto (`italia_livello2`, raggiungibili via terra senza neutrali veri;
  Persia, Northwest Persia, Eastern Persia attaccabili: Italians-Neutral_Allies = Unfriendly_Neutral, archetipo guerra); finche' ce n'e'
  uno nemico solo questi (mai la Russia). Acquisti: l'Africa e' via mare ⇒ niente ordine della campagna; la quota va alla **spola italiana**
  (`porto_italia` Northern Italy, capacita' 10, zone 95/97 a una zona dalla 96; base = prima dell'Asse in `basi_africa_italia`) col
  carico bilanciato degli USA; con Egitto e Nord Africa dell'Asse la quota compra navi (`tipi_flotta_italia` destroyer, cruiser). Le truppe
  della spola sono seguite per id dalla campagna (instradamento verso il raduno una volta in Africa). **Gibilterra dell'Asse NON fatta**
  (facoltativa: rimandata; tipo `gibilterra_asse` riservato nel registro, mai scritto).
- **B1 Snodo** (`SbarcoCoordinato.teatroR4`, `SnodoAlleati.gibilterraUtilizzabile`): teatro SUD con base Gibraltar se e' dell'alleanza e
  nelle sue zone (91, 92) non ci sono navi da guerra nemiche (trasporti puri esclusi), altrimenti NORD (United Kingdom); niente soglia del
  40%, niente inerzia; la base non diventa piu' una testa di ponte (resta Gibilterra o il Regno Unito).
- **B2 Scelta dello snodo** (`viaLiberaR4`, `SnodoAlleati.sceltaSnodo`): ogni round USA, bersagli = `bersagli_africa` (Nord Africa,
  Sardinia, Sicily, Southern Italy) + `bersagli_francia` (Southern France, Normandy Bordeaux) nemici in guerra; per ognuno piano di carico,
  stima e via libera di R3a (vittoria >= 70, contrattacco d'alleanza <= 40), punteggio = tenuta d'alleanza x valore (indice + bonus
  ottenuti + bonus tolti, regola C). Con l'Asse in Nord Africa: le unita' veloci del programma (terra, movimento >= 2) in Marocco se e'
  alleato (scarico) o sul bersaglio nordafricano migliore, il resto sul bersaglio migliore di tutti con trasporti diversi ⇒ MISTO se il
  resto va in Francia, AFRICA altrimenti; senza Asse in Nord Africa tutto sul migliore (AFRICA o FRANCIA). Due ondate (`ondata_africa`
  eseguita prima, poi `ondata`), ognuna con il suo filtro. Registro `snodo_usa` (alternative con i numeri per gruppo AFRICA, FRANCIA,
  VELOCI_AFRICA, RESTO).
- **B3 Forza trattenuta** (`delProgramma`): solo le unita' comprate dal programma di sbarco, seguite per id (`programma_ids` della nazione
  in `Impegno.dopoIlPiazzamento`); la spola USA carica solo quelle; le altre restano a Hard.
- **B4 Regno Unito** (`SnodoAlleati.acquistiSudafrica`, `nord`): PU nell'ordine Londra (presidio caso c), Africa, forza da sbarco: nella
  fabbrica Union of South Africa (factory_minor, 3 unita') fino alla capacita' `tipi_sudafrica` (mech_infantry, armour) finche' l'Asse ha
  territori in Africa o l'Egitto non e' alleato; categoria con fabbrica propria (`ProPurchaseOrders.Mandatory.factory`, nuovo componente
  facoltativo: null = come prima); le unita' (seguite per id `sudafrica_ids`) salgono verso l'Egitto su terra alleata nel non
  combattimento. Nord Africa a tenaglia e tetto ai trasporti di R3a restano.
- **B5 Neutrali a est dell'Egitto** (`neutrali`, `viaMare`): Persia, Northwest Persia, Eastern Persia (Friendly_Neutral per i Britannici:
  si prendono entrando) via terra o con un trasporto britannico che carica un'unita' vicina e la scarica (stesso non combattimento).
- **B6 Sbarramento a sud** (`sbarramento`, `distanzaAsse`): passaggi obbligati dalla mappa `passaggi_sud` Trans-Jordan (unico confine
  terrestre dell'Egitto verso est) e Iraq (Syria confina solo con la Turchia, neutrale vera); sul primo dell'alleanza, con unita' di terra
  dell'Asse entro 3 mosse, il presidio caso c (`presidioMinimo`) con le unita' gia' li' e quelle dei confinanti (mai da Londra/Egitto, mai
  in territori russi).
- **C Reddito e bonus** (`Reddito.guadagno`): guadagno per round = PU del territorio (`pu_territori`) + bonus della nazione ottenuti +
  bonus tolti alle nazioni dell'altra alleanza, dal modello degli obiettivi nazionali della mappa (`obiettivi_nazionali`: tipo
  "ciascuno", "almeno N", "altro" = condizioni non di possesso, che il territorio non cambia). A parita' di livello vince il guadagno
  maggiore (A1, A4: `Assedio.scegli`), nello snodo entra nel valore del bersaglio (B2, C4). Registro con i tre numeri per alternativa.
- **Registro**: tipi nuovi obiettivo_assedio, rinforzo_fabbrica, bombardamento (acquisto e combattimento, esiti con danno, perdite e
  produzione di Mosca), italia_africa, snodo_usa, africa_sudafrica, neutrali_est, sbarramento_sud (gibilterra_asse riservato); motivi
  nuovi nell'elenco chiuso.
- **Autoplay**: `MonitorR4` scrive in ogni round `r4` (proprietari di Mosca, Stalingrado, Leningrado, Ucraina, Egitto, Parigi, Roma,
  Berlino, Londra, Gibilterra; esercito per nazione = unita' di terra; danno della fabbrica di Mosca; unita' piazzate a Mosca dai Russi e
  dall'Asse nelle fabbriche russe catturate; territori dell'Asse dei livelli 1 e 2 italiani; italiani nel sud della Russia; obiettivi
  nazionali attivi con i PU, provati come la fine turno del motore). `ConfrontoR4` scrive "Per configurazione (step R4)" dentro
  "Partite complete": vittorie con IC di Wilson e test esatto di Fisher (da (a) e nuova contro attuale), round della vittoria (note e
  XML), durata; VC Asse, reddito ed esercito per nazione a R4-R20; round di caduta delle citta'; assedio; Italia; bonus; Alleati. Con piu'
  di 12 partite niente sottosezioni per partita. `ConfrontoImpegno` non scrive con partite fino alla vittoria.
- Test: `StrategiaR4Test` (12, Global 1940), `StrategiaR4AutoplayTest` (3).

### Scelte di R4 (fatte da solo, con il perche' e l'alternativa scartata)

1. **Vittoria**: l'autoplay finisce con la lettura delle note (condizione tenuta un round intero), come gia' dallo step PARTITA COMPLETA,
   perche' nel client la partita non finisce mai da sola e il giocatore applica le note; la lettura dell'XML e' registrata accanto.
   Scartato: fermarsi al primo fine round con la condizione (sarebbe la regola del trigger, che sulla mappa non scatta mai).
2. **Interruttori sopra `impegno`**: `assedio` e `alleati_r4` valgono solo con `impegno` acceso (il prompt li vuole "sopra l'impegno"
   / "sostituisce la scelta del teatro di R3a"). Scartato: interruttori indipendenti (senza impegno non ci sono campagne ne' programmi).
3. **A1 livelli**: Mosca al 70% nel turno = livello 0 (prima delle fabbriche) e chiude subito l'obiettivo corrente (eccezione all'inerzia
   di R2b); fabbriche al livello 1, a parita' il guadagno di C; i territori russi sulla strada sono le tappe del percorso, non obiettivi
   propri. Scartato: obiettivi separati per ogni territorio sulla strada (avrebbero rotto la regola "un obiettivo alla volta").
4. **Senza bersagli R4** (prima della guerra con la Russia, o tutto preso) vale la regola di R2b (es. Francia al R1). Scartato: nessun
   obiettivo (la Germania resterebbe ferma i primi round).
5. **A2 capacita'**: la verifica e' quella del piazzamento di Hard (`canPlace`); NON si scavalca l'ordine di `ProPlanPurchase` che mette per
   ultimi i territori di terra "che non si tengono": una fabbrica catturata indifendibile riceve unita' solo se le altre sono piene.
   Scartato: forzare il piazzamento (unita' regalate al contrattacco).
6. **A3 numeri**: danno atteso = (1 − 1/6) x 5,5 = 4,58 per bombardiere (antiaerea propria della fabbrica, 1 colpo su 6 per aereo;
   intercettori non stimati: per loro la scorta); bombardieri necessari = ceil(produzione residua / 4,58) (3 con la fabbrica intatta);
   comprati al massimo 2 bombardieri e 1 caccia di scorta per turno ("scelto"), solo con una base sicura entro meta' movimento (3
   territori); "finche' il danno non blocca la produzione" = capacita' > 0 (danno < 10, non il massimo 20). Atterraggio: un territorio
   alleato non conquistato nel turno entro il movimento residuo (non la sicurezza del territorio: l'atterraggio vero lo decide il non
   combattimento di Hard). Bombardieri tolti agli attacchi di Hard all'acquisto (altrimenti Hard li usa e li perde: visto in prova). Perdite
   = mandati − aerei ancora su Mosca prima dell'atterraggio (il piano memorizzato di Hard sposta unita' dello stesso tipo, non sempre gli
   stessi id). Scartato: contare gli id.
7. **A4 acquisti italiani**: la quota dell'operazione (70% dopo il presidio caso c) va tutta alla spola o alla flotta; la spola usa il
   carico bilanciato degli USA (scorta di cacciatorpediniere compresa) con porto Northern Italy (capacita' 10) e scarico nella prima base
   dell'Asse da Alexandria verso ovest; navi: destroyer e cruiser alternati. Scartato: Southern Italy come porto (produce 3 unita').
8. **A4 livello 2**: territori britannici/francesi d'Africa raggiungibili via terra + Trans-Jordan, Syria, Persia, Northwest Persia,
   Eastern Persia; escluso Iraq (Neutral_Axis: per l'Italia si prende entrando, non si attacca). I neutrali filo-alleati non sono sulla
   rete "conquistabile" della campagna (solo territori di un'alleanza): restano nella lista ma la campagna non li raggiunge.
9. **Gibilterra dell'Asse**: lasciata fuori (facoltativa).
10. **B1**: "navi da guerra nemiche" = navi nemiche che non sono trasporti puri (un incrociatore che trasporta conta); controllate nelle
    zone 91 e 92 a ogni turno USA, senza inerzia. La base non diventa piu' una testa di ponte (lo snodo resta Gibilterra).
11. **B2 Marocco**: le unita' veloci vanno in Marocco se e' alleato (scarico nel combattimento) oppure sul bersaglio nordafricano con la
    via libera migliore; il resto sceglie tra tutti i bersagli con trasporti diversi da quelli dell'ondata africana. AFRICA = tutto su un
    bersaglio africano/italiano; FRANCIA = tutto in Francia; MISTO = veloci in Africa e resto in Francia. "Unita' veloci" = movimento >= 2.
    Valore = indice di R2b + bonus ottenuti + bonus tolti (C4 dentro il punteggio, non come spareggio separato).
12. **B3**: la spola USA carica solo le unita' del programma (altrimenti un trasporto misto non sarebbe ne' trattenuto ne' di Hard).
13. **B4**: "finche' l'Egitto non e' tenuto e l'Asse ha territori in Africa" letto come: si compra finche' l'Asse ha territori in Africa
    (Nord Africa, livello 2, Ethiopia, Italian Somaliland) o l'Egitto non e' alleato. Tipi mech_infantry/armour (6 territori fino
    all'Egitto). Le unita' salgono verso l'Egitto (o il territorio alleato accanto se perso) su terra alleata, mai in territori russi.
14. **B5**: un trasporto britannico non mosso, vuoto, carica un'unita' da un territorio britannico accanto alla sua zona e la scarica nel
    neutrale nello stesso non combattimento.
15. **B6**: passaggi obbligati calcolati sul grafo di terra senza i neutrali veri: Trans-Jordan e Iraq; presidio sul primo dell'alleanza;
    "entro 3 mosse" = distanza di terra <= 3 da un'unita' di terra dell'Asse; nessuna unita' britannica in territori russi per le mosse
    di R4 (quelle di Hard non sono limitate).
16. **C**: obiettivi "altro" (Mediterraneo senza navi, presenza in Egitto, Svezia, commercio con la Russia, lend-lease) non entrano nel
    guadagno per territorio; il bonus "ottenuto" e' solo della nazione che sceglie (non degli alleati). PU incassati nelle tabelle =
    somma dei valori attivi a fine round (approssimazione: il motore paga a fine turno della nazione).
17. **Categoria con fabbrica** (`ProPurchaseOrders.Mandatory.factory`, componente nuovo con costruttore a 3 argomenti invariato): serve
    perche' le preferenze di piazzamento erano per ordine, non per categoria (le unita' del Sudafrica e quelle della forza da sbarco
    finivano nella stessa fabbrica). Hard non da' mai ordini: nessun effetto su Hard.
18. **Trasporti allo snodo**: con unita' del programma ferme a Gibilterra i trasporti USA vuoti nelle zone di Gibilterra restano li' (non
    tornano al porto) e lo sbarco dallo snodo li carica; visto in prova che altrimenti le unita' si accumulavano a Gibilterra senza
    trasporti (attese "NESSUN_CARICO" con 30+ unita'). Scartato: comprare trasporti nuovi a Gibilterra (non c'e' fabbrica).

### Alleati rifatti (step R5)

Interruttore nuovo `alleati_r5` di `StrategaInterruttori` (default dal file dati `impegno.r5.alleati_r5_acceso_di_default` =
**false**: spento = comportamento di prima), sopra `impegno` (autoplay `--stratega-acceso Allies=impegno+alleati_r5`, etichetta
"[con alleati_r5]"). Acceso, per **USA e Regno Unito** sostituisce R3a e `alleati_r4` (`SnodoAlleati.attivo` diventa falso per
loro); la Russia resta Hard (nessuna campagna), la Francia non gioca l'impegno; l'Asse non cambia; livello LLM e tenute invariati.
Valori in `stratega-llm.json` `impegno.r5` (`AlleatiR5.Config`), ognuno `{"valore", "origine"}` ("scelto", "richiesta dello step R5"
o "dalla mappa"), nessuno tarato. Classe nuova `ai/proplus/AlleatiR5`; agganci in `SbarcoCoordinato.Turno` (`r5()`: `inizio`,
`combattimento`, `nonCombattimento`, filtro dei trasporti del programma, base fissa del Regno Unito, unita' partite), `Programmi.Turno`
(`r5()`: solo trasporti del programma, scorta di A3, divieto dei trasporti di B2, `navigaR5`), `Impegno` (Sudafrica B5, quota 70 e
acquisti R5, `casoC`, `costo`), `SnodoAlleati` (`programmaPerId`, `compraSudafrica`), `Reddito.valoreOra`, `RegistroDecisioni` (tipi e
motivi). **Non valgono piu'** (acceso): condizione di tenuta nel via libera, teatro aperto/chiuso, arresto della spola per contrattacco
stimato, tetto ai trasporti (USA mai; Regno Unito solo B2), trattenute di unita' non comprate dal programma, Sudafrica senza condizione.
- **A1 Acquisti USA** (`AlleatiR5.acquistiProgramma`): presidio della capitale (caso c) tolto, 70% al programma col carico bilanciato
  di R2c contato **sui soli trasporti del programma** (id in `programma_ids`), scorta = max(1 nave da guerra per il gruppo se al porto
  non ce n'e', cacciatorpediniere che servono ad A3 per arrivare al 70%) fino a `max_scorta_per_turno` 4. Prima della guerra: trasporti
  carichi fermi sulla costa est (invariato).
- **A2 Rotta** (`rotta`, registro `rotta_usa`): Gibilterra dell'alleanza e zone 91/92 senza navi da guerra nemiche ⇒ snodo Gibilterra
  (teatro SUD), altrimenti base United Kingdom (teatro NORD). Spola (`Programmi.Turno.navigaR5`): il gruppo dei trasporti carichi parte
  solo con almeno `min_navi_nel_gruppo` 1 nave da guerra della stessa zona (fino a 4 con lui) e fa tutto il movimento verso la base per
  zone senza nemici, **senza controllo del contrattacco**; senza scorta resta fermo (trattenuto). I vuoti tornano al porto senza scorta.
- **A3 Battaglia di Gibilterra** (`battagliaPrevista` all'acquisto, `battaglia` nel combattimento USA prima di tutto, `esitoBattaglia`
  nel non combattimento): per ogni zona di Gibilterra con navi da guerra nemiche, le navi USA in portata (non portaerei) contro tutte le
  unita' nemiche della zona; >= 70 ⇒ battaglia (ricalcolata sul tabellone reale); sotto ⇒ niente, il programma compra scorta e le
  ondate vanno al Regno Unito. Esito: mare liberato, navi perse.
- **A4 Presa di Gibilterra** (`presaGibilterra`, `presa_gibilterra`): Gibilterra dell'Asse e mare libero ⇒ sbarco del programma (dalla
  base Regno Unito) con vittoria >= 70.
- **A5 Snodo** (`snodo`, `snodo_usa`): ogni round dalla base corrente; finche' l'Asse tiene Egitto o un territorio del Nord Africa o un
  bonus italiano africano e' attivo (`africaAperta`) le unita' veloci del programma (movimento >= 2) vanno in Marocco se alleato o sul
  bersaglio nordafricano migliore (`bersagli_africa`); il resto (le lente, o tutte quando l'Africa e' chiusa) sul bersaglio di valore
  piu' alto tra `bersagli_resto` (Normandy Bordeaux, Southern France, Sardinia, Sicily, Southern Italy) con vittoria >= 70 sulle forze
  reali; valore = indice + bonus ottenuti + bonus tolti (C di R4); parita' = vittoria. **Nessuna condizione di tenuta** (la tenuta
  stimata e' registrata solo come informazione: `tenuta_stimata_pct_info`). Nessun bersaglio ⇒ ATTESA_ACCUMULO con `parametri.manca`.
  Codici AFRICA / FRANCIA / ITALIA / MISTO.
- **A6 Sbarco e trattenute**: ondate eseguite dal meccanismo di R3a/R4 nel combattimento prima di Hard (africana prima), solo trasporti
  del programma (`filtroTrasporti`); le unita' di terra sbarcate escono da `programma_ids` (`partite`): da quel momento sono di Hard.
  Trattenute (acquisto, combattimento di Hard, carico anfibio di Hard) = unita' del programma non partite, aerei esclusi. Esito dello
  sbarco (preso, perdite) e tenuta dopo 1 e 2 round (solo informazione) anche per l'ondata africana.
- **B1-B2 Acquisti UK**: Londra (caso c) ed Egitto (`presidi_prioritari`) e campagna, poi Sudafrica (B5), poi 70% al programma.
  `difesaPorto`: per ogni zona del porto (109, 110) contrattacco realistico (`Programmi.Turno.vittoriaNemicaMare`) contro navi alleate
  della zona + aerei alleati sulle portaerei della zona + aerei che possono fare scramble verso la zona (`AlleatiR5.scramble`, logica del
  motore `ScrambleLogic`); la zona migliore <= 40 ⇒ difesa: trasporti permessi a programma e Hard; altrimenti la proprieta' del tetto di
  R3a vieta i trasporti a Hard e al programma e il programma compra prima la difesa (il tipo piu' economico tra destroyer e fighter che
  porta la zona <= 40, fighter solo nei posti di scramble liberi della base aerea del porto, max 3; nessuno basta ⇒ quello che scende di
  piu'). Registro `trasporti_mare_difeso` con i numeri per zona.
- **B3 Logoramento** (`logoramento`, `logoramento_uk`; sbarco nel combattimento come "azione_uk" tipo `logoramento`): unita' del
  programma britannico dal porto di casa (base fissa United Kingdom) sul bersaglio di `bersagli_logoramento_uk` di valore piu' alto con
  vittoria >= 70, senza tenuta. `rinforzoUk` nel non combattimento: se un'ondata USA del round ha preso il suo bersaglio, le unita' del
  programma rimaste lo rinforzano.
- **B4**: Egitto (presidio caso c, riconquista), Nord Africa a tenaglia, neutrali a est e sbarramento a sud: meccanismi esistenti
  (`SnodoAlleati.nonCombattimentoUk` attivo anche con R5).
- **B5 Sudafrica** (`AlleatiR5.acquistiSudafrica`): fino alla capacita' solo se un bonus italiano africano e' attivo (`bonus_africani_italiani`:
  North Africa e Roman Empire, valutati sul possesso con `Reddito.valoreOra`), o l'Egitto non e' alleato, o l'Egitto e' in caso c;
  registro `africa_sudafrica` con la condizione vera o `CONDIZIONE_FALSA`.
- **Registro**: tipi nuovi `rotta_usa`, `battaglia_gibilterra`, `presa_gibilterra`, `logoramento_uk`, `trasporti_mare_difeso`,
  `perdite_in_mare` (ai controlli delle unita' in mare: unita' e trasporti persi); motivi nuovi nell'elenco chiuso.
- **Autoplay**: `Monitor` scrive in ogni round `r5.regioni` (unita' di terra di USA, Regno Unito, Germania, Italia per Europa
  occidentale, Italia e isole, Nord Africa, altrove, mare; liste in `impegno.r5.regioni`); `ConfrontoR5` scrive la sottosezione **"R5"**
  di "Partite complete" (vittorie, IC di Wilson, Fisher e1 contro a ed e3 contro e2; sbarchi per periodo e regione divisi programma/Hard;
  trasporti; terra; Gibilterra; Russia e citta'; Italia e Africa; quota PU del programma e attese). Modalita' `[completa]` = 4
  configurazioni x semi 1181-1220.
- Test: `AlleatiR5Test` (11, Global 1940), `ConfrontoR5Test` (4).

### Scelte di R5 (fatte da solo, con il perche' e l'alternativa scartata)

1. **Interruttore sopra `impegno`** (come `assedio`/`alleati_r4`): senza impegno non ci sono programmi. Con `alleati_r4` e `alleati_r5`
   accesi vince R5 (`SnodoAlleati.attivo` falso per USA e UK). Scartato: interruttore indipendente.
2. **Trasporti del programma**: il programma usa solo i trasporti che ha comprato (id) e il carico bilanciato conta solo quelli; i
   trasporti iniziali e quelli comprati da Hard restano a Hard. Scartato: usare qualsiasi trasporto USA (toglieva a Hard i suoi sbarchi).
3. **Trattenute**: unita' del programma non partite, comprese navi (trasporti e scorte, servono alla spola); aerei comprati dal programma
   (difesa del porto) mai trattenuti. "Partite" = di terra scaricate da uno sbarco o da un rinforzo del programma. Scartato: trattenere
   anche le navi solo finche' in porto.
4. **Scorta di A3**: cacciatorpediniere temporanei aggiunti alle navi USA in portata adesso finche' la battaglia arriva al 70% (max 4
   per turno); e' una stima (le navi comprate arrivano dopo). Piu' 1 nave per il gruppo se al porto non ce n'e'. Scartato: stimare la
   flotta tra due turni.
5. **Gruppo della spola**: parte solo con almeno una nave da guerra della stessa zona; i trasporti vuoti tornano senza scorta (sono il
   rischio minore e non devono bloccarsi). Scartato: muovere comunque senza scorta.
6. **Battaglia di Gibilterra**: attaccanti = navi da guerra USA in portata nel turno (non portaerei, non aerei), difensori = tutte le
   unita' nemiche della zona; ogni zona occupata separatamente. Scramble dell'Asse in difesa non stimato. Scartato: includere gli aerei
   USA (atterraggio non verificato).
7. **Base con Gibilterra non utilizzabile**: United Kingdom; da li' A5 continua sui bersagli raggiungibili (Normandy Bordeaux) invece di
   fermarsi. Scartato: nessuno sbarco USA finche' Gibilterra non e' libera.
8. **Africa aperta**: territori "tenuti dall'Italia" letti come dell'Asse (l'obiettivo italiano North Africa conta il possesso
   d'alleanza); bonus africani = North Africa e Roman Empire (contiene Egitto e Gibilterra). Scartato: solo proprieta' italiana diretta.
9. **Unita' veloci con l'Africa aperta ma senza bersaglio al 70%**: restano (accumulo), non vanno col resto. Scartato: mandarle in
   Francia (la regola le vuole in Africa finche' l'Italia la tiene).
10. **Valore e spareggio**: indice di R2b + bonus ottenuti + bonus tolti; a parita' la vittoria piu' alta.
11. **Zona del porto difesa (B2)**: basta la migliore delle zone del porto (109 o 110); il piazzamento di Hard sceglie la zona e non e'
    controllato. Scartato: tutte le zone (avrebbe bloccato quasi sempre).
12. **Difesa comprata**: il tipo piu' economico che porta la zona <= 40; i caccia contano solo nei posti di scramble liberi della base
    aerea del porto (la mappa permette 3 aerei per base); nessuno basta ⇒ quello che abbassa di piu' il contrattacco.
13. **Logoramento UK**: dal porto di casa con i trasporti del programma; bersagli = i costieri NORD di R3a; rinforzo della conquista USA
    con le unita' del programma rimaste (nessuna soglia). Scartato: logoramento anche dal Mediterraneo (il Regno Unito non ha base li').
14. **Sudafrica**: "Egitto in caso c" letto dalla regola 2.6 dell'acquisto del turno; capacita' della fabbrica come in R4.
15. **Perdite in mare**: dai controlli gia' esistenti (trasporti della spola a fine turno e dell'ondata) con una riga `perdite_in_mare`.
16. **Tabelle**: "Hard" = sbarchi del monitor meno quelli del programma (il monitor conta solo territori nemici a inizio turno, il
    programma anche il Marocco alleato: per questo il minimo e' 0).

### Squadra di Gibilterra (step R6)

Interruttore nuovo `squadra_gibilterra` di `StrategaInterruttori` (default dal file dati `impegno.r6.squadra_gibilterra_acceso_di_default`
= **false**: spento = comportamento di prima), sopra `impegno` (autoplay `--stratega-acceso Allies=impegno+squadra_gibilterra`, etichetta
"[con squadra_gibilterra]"). Acceso vale SOLO per gli USA e sostituisce per loro R3a, `alleati_r4` e `alleati_r5`; Regno Unito, Russia e
Francia **giocano come Hard** (`SquadraGibilterra.esclusa` dentro `Impegno.attivo`). Per gli USA usa i meccanismi di R5 (`AlleatiR5.attivo`
vero per gli USA con R6: programma per id, battaglia A3, presa A4, snodo A5, sbarchi A6) con acquisti, trattenute e movimento propri.
Valori in `stratega-llm.json` `impegno.r6` (`SquadraGibilterra.Config`, ognuno `{"valore","origine"}`: "dalla mappa", "richiesta dello step
R6", "scelto"; nessuno tarato) con il blocco informativo `mappa` (costi, capienze, navi iniziali, rotta). Stato nella proprieta' di R3a
(`Pro+ stratega r3a: Allies`, oggetto `r6`: `fase` costruzione/in_viaggio/attesa/arrivata, `squadra` id → tipo, `iniziali`, `piano`,
`acquisto_turno`, `scorte_spola`, `partenza_round`, `arrivo_round`, PU).
- **Regole della mappa verificate** (script sull'XML `ww2eur40_2nd_edition.xml`): costi fanteria 3, artiglieria 4, carro 6, meccanizzata 4,
  caccia 10, tattico 11, trasporto 7, sottomarino 6, cacciatorpediniere 8, incrociatore 12, portaerei 16, corazzata 20; trasporto 5 posti
  (fanteria 2, artiglieria/carro/meccanizzata 3); portaerei 2 aerei; navi USA iniziali in Atlantico: zona 101 1 incrociatore + 1 trasporto;
  fabbriche Eastern e Central United States major (10 unita'), entrambe sulla 101; harbour di Eastern US: +1 movimento alle navi (trasporto,
  sottomarino, cacciatorpediniere, incrociatore, portaerei, corazzata); rotta 101 → 102 → 103 → 91 = 3 zone (un turno col bonus), 92 a 4;
  prima della guerra 103/106 vietate (la squadra parte solo in guerra; guerra al piu' tardi dopo il piazzamento USA del R3); corazzata e
  incrociatore `canBombard`. Bombardamento nel motore: ogni nave `canBombard` del giocatore nella zona da cui parte lo sbarco (senza
  battaglia navale li') bombarda (`BattleDelegate.addBombardmentSources`).
- **A Costruzione (R1-4)**: PU = 100% del turno (`SquadraGibilterra.pu`, nessun presidio: `capitalGuardThreshold` = 100 per gli USA ⇒ Hard
  non difende capitale e fabbriche prima della squadra; `presidio_porto` 0). Ordine (`costruisci`): carichi dei trasporti posseduti, trasporti
  ciascuno col carico (fanteria + artiglieria), cacciatorpediniere, portaerei e poi i suoi caccia, incrociatore, sottomarino, corazzata;
  una voce che non entra nei PU rimasti si salta (una successiva piu' economica puo' entrare); obiettivo raggiunto ⇒ altri trasporti pieni.
  Limite di unita' = capacita' della fabbrica del porto. Navi iniziali delle zone del porto adottate al primo acquisto (squadra +
  `programma_ids`). Le unita' comprate dal programma nei round di costruzione (navi e aerei) entrano nella squadra (`dopoIlPiazzamento`).
- **A4/C4 Trattenute**: squadra (aerei compresi) + unita' del programma non sbarcate, nel combattimento (acquisto) e nel non combattimento.
- **B1 Partenza** (`partenza` all'acquisto, `muovi` nel non combattimento): parte se in guerra con Germania o Italia E (completa O round >=
  `round_arrivo` − turni di viaggio + 1); turni = distanza / movimento della nave piu' lenta col bonus del porto. Un gruppo per zona (navi +
  carico) per la rotta piu' corta senza nemici, quanto permette il movimento; gli aerei seguono la portaerei. Registro `squadra_partenza`
  (composizione) e `squadra_arrivo` (navi nella zona base `91 Sea Zone` e nessuna nave da guerra nemica).
- **B2 Battaglia**: zona base con navi da guerra nemiche ⇒ vittoria di navi da guerra + aerei della squadra che arrivano (portata col bonus
  del porto se non ancora mossi) >= 70 ⇒ battaglia nel combattimento (ricalcolata sul tabellone reale; i trasporti seguono nel non
  combattimento se il mare e' liberato), altrimenti attesa nella zona confinante libera piu' vicina (`ATTESA_ZONA_ACCANTO`), rivalutata il
  round dopo. Dopo l'arrivo le battaglie per 91/92 sono quelle di R5 (A3).
- **B3 Presa**: Gibilterra dell'Asse e zona base libera ⇒ sbarco di R5 (A4) >= 70 col sostegno della squadra. **Corretto un difetto di R5**:
  il codice `PRESA` non era nell'elenco dei motivi ⇒ ogni sbarco per prendere Gibilterra lanciava un'eccezione e annullava il turno R5 degli
  USA (con `alleati_r5` acceso lo sbarco A4 non e' MAI avvenuto nelle run di R5).
- **B4 Base**: arrivata, la squadra resta in 91 (`raduna`: navi da guerra e trasporti vuoti della squadra rientrano, gli aerei tornano sulla
  portaerei); la spola scarica a Gibilterra se alleata, altrimenti i trasporti restano carichi nelle zone di Gibilterra.
- **C1 Sostegno** (round >= 5, `pressione`): PU = 80% di (PU − presidio caso c); vittoria nemica realistica sulla squadra nella sua zona
  (`Programmi.Turno.vittoriaNemicaMare` con navi e aerei della squadra); finche' > 40 si aggiunge un'unita' alla volta tra `tipi_sostegno`
  quella che abbassa di piu' per PU (caccia solo nei posti liberi delle portaerei), max 4 per turno; entrano nella squadra.
- **C2 Spola** (`spola`): truppe in attesa >= posti ⇒ coppie complete (trasporto + fanteria + artiglieria), posti > truppe ⇒ truppe; una
  scorta (cacciatorpediniere, `scorte_spola`, mai della squadra) quando si comprano trasporti o ce ne sono al porto e al turno dopo non
  restera' una scorta al porto. Gruppi carichi e vuoti viaggiano solo con una nave da guerra non della squadra (`navigaR5` con scorta anche al
  ritorno). Prima dell'arrivo nessun trasporto salpa: si caricano solo i trasporti del programma al porto.
- **C3 Sbarchi**: lo snodo di R5 (A5) dalla base Gibilterra; l'ondata principale ("resto") e la presa contano gli aerei della squadra che
  possono attaccare e tornare sulla portaerei (2 x distanza <= movimento) e le navi `canBombard` che raggiungono la zona di scarico (al
  massimo una per unita' di terra se la mappa lo limita); eseguito: dopo lo scarico le navi vanno nella zona e gli aerei attaccano.
- **0 Calcolatore** (`ProPlusAi`): `prepareData` attende la copia del calcolatore (`setGameData(...).get`, max 120 s); a inizio di
  acquisto/movimento/piazzamento `attendiCopie` attende la fine del lavoro in background del pool comune (copie avviate da altre AI, worker
  rimasti dopo una stima fallita). Vale per tutte le voci Pro+ (solo attese: nessuna decisione cambia).
- **D Quota dell'Asse**: proprieta' `Pro+ stratega impegno quota: <alleanza>` (`Impegno.setQuota`, autoplay `--quota-operazione Axis=80`,
  etichetta " [quota 80]"); assente = `quota_pu_operazione` del file (70). Sostituisce `cfg.quotaPu()` nei 5 usi di `Impegno`.
- **Registro**: tipi nuovi `squadra_costruzione`, `squadra_partenza`, `squadra_arrivo`, `acquisto_pressione` (piu' `battaglia_gibilterra`,
  `presa_gibilterra`, `snodo_usa`, `sbarco`, `perdite_in_mare` gia' esistenti); motivi nuovi PRESA, COSTRUZIONE, OBIETTIVO_COMPLETO,
  PARTENZA_COMPLETA, PARTENZA_ROUND, ARRIVO, ATTESA_ZONA_ACCANTO, SOSTEGNO, SPOLA_COPPIE, SPOLA_TRUPPE.
- **Autoplay**: `ConfrontoR6` scrive la sottosezione **"R6"** di "Partite complete" (esiti con Fisher f1/a, f3/e2, g/e2; squadra per partita e
  distribuzione dell'arrivo; Gibilterra; ondate del programma; sbarchi per periodo/regione, terra, Russia e citta', trasporti e perdite di R5;
  quota PU dal R5); le tabelle dell'assedio di g sono in "Per configurazione (step R4)" accanto a e2. `[completa]` = 5 configurazioni x semi
  1221-1260 (a, f1, e2, f3, g), campo `quota` nella matrice.
- Test: `SquadraGibilterraTest` (12, Global 1940 con fabbrica major al porto), `ConfrontoR6Test` (4).

### Scelte di R6 (fatte da solo, con il perche' e l'alternativa scartata)

1. **R6 dentro la macchina di R5** (programma per id, snodo, ondate, battaglie, presa) invece di un modulo separato: meno codice nuovo e le
   stesse stime; scartato: riscrivere sbarchi e battaglie.
2. **Gli altri Alleati come Hard** = l'impegno spento per loro (lo Stratega senza chiave gioca come Hard); scartato: lasciarli su R3a.
3. **Zona base 91** (lato atlantico, 3 zone dal porto); 92 resta controllata dalle battaglie di R5. Scartato: base 92 (4 zone, due turni).
4. **Carico** fanteria + artiglieria (7 PU per 5 posti); scartato carri (9 PU).
5. **Aerei della portaerei** = caccia (difesa 4, 10 PU); scartato il tattico.
6. **Navi iniziali** = navi USA nelle zone del porto al primo acquisto (101: incrociatore e trasporto); il caccia iniziale di Eastern US NON e'
   contato (lo step parla di navi).
7. **Voce che non entra nei PU**: si salta alla successiva, il resto (meno di una voce) va a Hard; scartato: risparmiare i PU (Hard li
   spenderebbe comunque nel suo acquisto).
8. **Senza presidio** nei round 1-4 realizzato con la soglia di guardia della capitale a 100 per gli USA (vale anche per la guardia della
   capitale nel non combattimento di Hard); scartato: lasciare la difesa urgente di Hard (in prova Central United States "a rischio 100%" si
   prendeva 12 PU su 35 al R1).
9. **Partenza anticipata** = solo se il viaggio dura piu' di un turno (col bonus del porto dura 1 ⇒ si parte a R5, o prima se completa e in
   guerra). La squadra completa e in guerra a R4 parte a R4 e arriva a R4 (lettura letterale di B1).
10. **Battaglia della squadra**: attaccanti = navi da guerra e aerei della squadra che arrivano (trasporti esclusi, seguono nel non
    combattimento); difensori = tutte le unita' nemiche della zona; scramble dell'Asse non stimato (come R5).
11. **Zona d'attesa**: la confinante con la base senza nemici piu' vicina alla squadra.
12. **Sostegno**: un'unita' alla volta con il calo maggiore della vittoria nemica per PU, max 4 per turno ("scelto"); le unita' comprate
    arrivano dopo (stima, come la scorta di R5).
13. **Scorta della spola**: cacciatorpediniere separati dalla squadra (la squadra resta a Gibilterra); se ne compra uno quando servira' al
    turno dopo. In prova la regola "una scorta se non ce n'e' entro 2 zone dal porto" bloccava la spola a round alterni (la scorta restava a
    Gibilterra): corretta prima del push.
14. **Ritorno dei vuoti con scorta** (C2 "e ritorno"): senza scorta i vuoti restano fermi; con unita' del programma in attesa a Gibilterra
    restano allo snodo (regola di R4) per lo sbarco.
15. **Sostegno agli sbarchi solo per l'ondata principale e la presa** (non per l'ondata africana delle unita' veloci): le stesse navi non
    possono stare in due zone.
16. **PRESA aggiunto ai motivi**: corregge anche R5 (alleati_r5), che non e' una voce di default; segnalato.
17. **Punto 0 per tutte le voci Pro+**: le attese non cambiano le decisioni; Hard (`ProAi`) non e' toccata.

### Trasporti protetti (step R6b)

Corregge il programma USA di `squadra_gibilterra` (stesso interruttore, spento di default = comportamento di prima; acceso vale solo per
gli USA, Regno Unito, Russia e Francia giocano come Hard; codice dell'Asse, livello LLM e tenute invariati). Classe nuova
`ai/proplus/TrasportiProtetti` (config `impegno.r6b` di `stratega-llm.json`: `soglia_trasporti` 40, `round_pericolo` 3, `zona_rientro`
91, `seconda_fabbrica` Central United States, `max_scorta_spola_per_turno` 1; nessun valore tarato). Agganci: `AlleatiR5.migliore`
(bersagli in ordine di valore, `primoProtetto`), `AlleatiR5.snodo`/`presaGibilterra` (protezione salvata nell'ondata), `SbarcoCoordinato.Turno`
(`zonaScarico` usata da `piano`, `scorteUsate`, ricontrollo e scorta nell'esecuzione delle due ondate), `SquadraGibilterra.raduna`
(sostituita da `TrasportiProtetti.raduna`), `SquadraGibilterra.pressione` (scorta della spola solo se serve, seconda fabbrica, conto delle
truppe), `Programmi.Turno` (`attaccoRealistico` che restituisce anche gli attaccanti, spola R6b: niente rientro dei vuoti nella spola, carico
anche dalla seconda fabbrica, partenza in gruppo con `TrasportiProtetti.naviga`), `Impegno` (ordine `tipi_programma_extra` +
`fabbrica_programma_extra` = categorie "programma <tipo> seconda fabbrica" con fabbrica propria).
- **A1 regola unica** (`TrasportiProtetti.valuta`): difensori a fine turno = unita' navali alleate della zona (meno quelle che partono) +
  navi in arrivo (il carico non conta) + aerei alleati sulle portaerei della zona e quelli che possono fare scramble verso di lei
  (`AlleatiR5.aereiInDifesa`); attacco = TUTTE le unita' nemiche che raggiungono la zona (`Programmi.Turno.attaccoPieno`: opzioni
  d'attacco di Hard di tutti i nemici, SENZA la quota A3 del contrattacco realistico di R5) col calcolatore di Hard; sicura con vittoria
  nemica <= 40, <= 20 se nella zona
  unita' USA hanno perso TUV in difesa negli ultimi 3 round (memoria del pericolo delle soste sicure, `pericolo`). Vale per ondate,
  spola e rientri; le truppe a terra restano sacrificabili.
- **A3/B1 ondate** (`proteggi`, all'acquisto USA nello snodo e nella presa di Gibilterra): per il bersaglio di valore piu' alto con
  vittoria >= 70 prima la zona di scarico del piano, poi le altre zone confinanti senza nemici (in ordine di nome); in ogni zona il piano
  rifatto con quella sola zona (`zonaScarico`), la vittoria dello sbarco rifatta se il carico cambia (sotto 70 la zona salta), i trasporti
  con le navi che bombardano da li', poi la scorta minima: una nave da guerra della squadra alla volta (portaerei escluse; navi che
  raggiungono la zona nel turno, non gia' scorta di un'altra ondata ne' di vuoti in rientro), quella che abbassa di piu' la vittoria nemica
  (un candidato per tipo). Esiti SICURA / SCORTATA / ZONA_CAMBIATA; nessuna zona sicura = RINUNCIA e si passa al bersaglio successivo per
  valore (`AlleatiR5.primoProtetto`). Nel combattimento (`primaDelloSbarco`) la zona e' ricontrollata sul tabellone reale (non sicura =
  ondata annullata, RINUNCIA); dopo lo scarico (`dopoLoSbarco`) la scorta va nella zona e resta li' con i trasporti (voce `r6b.scorte`
  nello stato di R3a).
- **B2-B3 rientro** (`raduna`, non combattimento USA con la squadra arrivata, al posto del raduno di R6): le scorte del turno restano;
  altrove, per zona, navi da guerra della squadra + trasporti vuoti del programma + scorte della spola partono insieme verso la 91 e si
  fermano nella zona piu' lontana raggiungibile dove A1 vale (nessuna = fermi); le zone con trasporti carichi del programma non sono
  toccate (le navi restano col carico); nelle zone di Gibilterra (91, 92) i vuoti restano se a Gibilterra aspettano truppe del programma
  (le carica l'ondata del turno dopo), altrimenti tornano al porto con soste sicure.
- **C1 truppe ferme al porto, causa**: nei 40 log f1 di R6 le truppe del programma non restano al porto (dopo il carico del turno 0,8 in
  media; "caricate" = "pronte" quasi sempre). Gli 11-15 del registro erano il campo `truppe_in_attesa` di `acquisto_pressione`: contato in
  POSTI (fanteria 2, artiglieria 3: 3+3 unita' = 15) e all'acquisto, cioe' PRIMA del carico dello stesso turno (sono le truppe piazzate al
  turno prima); i 2-3 trasporti vuoti erano quelli comprati con loro. Unita' di Hard escluse gia' in R6 (filtro per id). Corretto il conto:
  `truppe_in_attesa` in unita', `posti_truppe_in_attesa` in posti, nel registro `spola` `truppe_rimaste_al_porto` e
  `trasporti_vuoti_programma_al_porto` DOPO il carico. Il blocco vero della spola in R6 era altrove: 650 "gruppo fermo in 91" (vuoti
  senza scorta fuori squadra) e 82 "gruppo fermo in 101" in 40 partite.
- **C2 spola**: i trasporti carichi del programma di una zona partono tutti insieme con le navi da guerra del programma di quella zona
  (anche quelle della squadra rimaste al porto) verso la 91 (`naviga`, soste solo sicure); scorta comprata (1 cacciatorpediniere) solo se
  la 91 non e' sicura per un trasporto con la squadra che c'e' (`serveScortaSpola`).
- **C3 PU in coppie fino all'80%**: il limite era il posto (10 unita' al porto: 3 coppie + scorta); le truppe che non entrano al porto vanno
  nella seconda fabbrica (Central United States, confinante con la 101) e caricano da li' (`spola` con `maxNavi` = posti del porto).
- **C4**: i rinforzi di R4 (corazzata, caccia, trasporti carichi) partono a R5 con la spola in un solo gruppo (navi della squadra al porto
  nel gruppo dei carichi); gli aerei raggiungono la portaerei come in R6.
- **D pannello**: caselle "Assedio" e "Squadra di Gibilterra" nel pannello Stratega (`StrategistPanel`), salvate nel profilo
  (`ProfiloStratega.assedio`/`squadraGibilterra`); `Stratega.registra` accende `impegno`+`assedio` per l'Asse e `impegno`+`squadra_gibilterra`
  per gli Alleati (salvo spenti esplicitamente nella partita).
- **Registro**: tipi nuovi `trasporti_sicurezza` (ondata, ricontrollo, partenza della spola: zona, difensori e attacco realistico per
  tipo, vittoria nemica, soglia, esito), `scorta_ondata` (navi andate e rimaste nella 91), `rientro_trasporti` (RIENTRO, RIENTRO_PARZIALE,
  FERMO), `attacco_subito` (scritto dal `Monitor` dell'autoplay intorno al passo di battaglia del nemico: zona, attaccanti, difensori,
  perdite dei due lati per tipo, trasporti USA persi carichi/vuoti, carico perso; `RegistroDecisioni.scriviEsterna`; nelle partite di
  Matteo senza autoplay NON c'e'). Nelle righe `squadra_costruzione`/`acquisto_pressione` anche `squadra_ora` e `navi_usa_in_91`.
- **Autoplay**: `ConfrontoR6b` scrive la sottosezione **"R6b"** di "Partite complete" al posto di "R6" quando il registro ha righe
  `trasporti_sicurezza`; sotto ogni configurazione la riga "(R6)" letta da `proplus-autoplay/src/main/resources/r6-riferimento.json`,
  calcolato con lo stesso codice sulle 200 partite di R6 (proprieta' di sistema `r6b.esporta=<file>` per rigenerarlo con `confronto`).
  `[completa]` = stesse 5 configurazioni, semi 1261-1300.
- Test: `TrasportiProtettiTest` (11, Global 1940).

### Scelte di R6b (fatte da solo, con il perche' e l'alternativa scartata)

1. **Attacco pieno, non la quota A3**: prima versione con il contrattacco realistico di R5 (quota A3); nella prova locale f1 seme 1261 lo
   sbarco su Sardinia (zona 95) era stimato al 8,2% di vittoria nemica (contati 1 bombardiere + 1 caccia) e l'Italia ha attaccato con
   bombardiere, 2 caccia e sottomarino affondando corazzata, incrociatore e 3 trasporti. Corretto prima del push: tutte le unita' in
   portata (`attaccoPieno`). Nella seconda prova (14 round) 0 trasporti del programma persi fino al R13, 2 rinunce, 1 zona cambiata.
   Scartato: tenere la quota (stima ottimista proprio dove i trasporti venivano affondati).
2. **Memoria del pericolo** riusata dalle soste sicure (3 round, soglia dimezzata a 20) solo per le perdite USA in difesa nella zona;
   scartato: perdite di qualsiasi alleato.
3. **Portaerei mai scorta** (i suoi caccia devono atterrarci dopo il sostegno allo sbarco); scorta = cacciatorpediniere, incrociatori,
   corazzate, sottomarini della squadra. Un candidato per tipo a ogni passo (la stima e' cara).
4. **Zone alternative** in ordine di nome dopo quella del piano, anche con scorta (A3 punto 2 letto come "zona sicura, se serve con la
   scorta"); la vittoria dello sbarco ricalcolata se il carico cambia.
5. **Rinuncia a un bersaglio** = ordine per valore (poi vittoria) tra quelli >= 70; nessun bersaglio protetto = accumulo come in R5.
6. **Ricontrollo nel combattimento** con la scorta salvata: l'ondata e' annullata se nel frattempo la zona non e' piu' sicura.
7. **B2 letterale**: una nave che scorta vuoti non ancora rientrati non puo' scortare una nuova ondata (meno navi disponibili, ma
   trasporti mai soli).
8. **Vuoti nella 92** con truppe a Gibilterra restano (caricano dallo snodo); senza truppe tornano al porto come quelli della 91.
9. **Seconda fabbrica** per le sole truppe (le navi restano nella fabbrica del porto: un trasporto messo nella 89 dalla fabbrica di Central
   United States non sarebbe nelle zone del porto). Scartato: comprare meno coppie (la quota resterebbe sotto l'80%).
10. **attacco_subito dal monitor dell'autoplay**: il gioco non ha un aggancio durante i turni nemici che dia gli attaccanti per tipo e le
    perdite (i registri di battaglia hanno solo i TUV); scartato: modificare il motore o l'AI dell'Asse (vietato).
11. **Riga "(R6)" delle tabelle** da un file di riferimento calcolato offline con lo stesso codice sulle partite di R6 (gli stessi numeri
    del riepilogo R6 dove la misura esisteva; "-" dove R6 non aveva il dato, es. attacchi subiti, rientri, composizione a R4/R5).

## Come configurare lo stratega

Per Matteo e gli amici (stesso testo, piu' corto, nel `LEGGIMI.txt` dello zip):
1. **Senza fare nulla** Pro+ gioca con le regole di sempre (gratis, nessuna rete).
2. **Con Claude Code** (abbonamento Claude Pro o Max; il piano gratuito NON include Claude Code): installarlo e fare
   il login una volta (dal sito di Claude Code), poi nel pannello "Impostazioni Pro+" mettere "Stratega" su
   **Automatico** (o "Claude installato"). "Stato rilevato" deve dire "✓ Claude Code trovato".
3. **Con una chiave API** (consigliato Claude Haiku: console Anthropic → API keys, servono crediti prepagati):
   - modo piu' semplice: salvare un file `chiave-stratega.txt` accanto ad `avvia.bat` con dentro solo la chiave;
     all'apertura del pannello viene importata e il gioco propone di cancellare il file (rispondere Si');
   - oppure incollarla nel campo "Chiave API" e premere **Salva**; **Prova connessione** dice subito se funziona
     (chiave giusta: "✓ Connessione riuscita"; sbagliata: "✗ chiave rifiutata da Haiku ...");
   - mettere "Stratega" su **Automatico** o **Chiave API** per le alleanze che si vogliono far decidere all'LLM.
4. **Fantasia** 1-5 (2 di default): 1 = segue i piani classici, 5 = cerca finte e mosse inattese (sempre entro i
   paletti).
5. **Costo**: con Haiku pochi centesimi per partita (vedi il diario: "Stratega, uso della partita finora", separato
   tra Stratega e Ordini del turno); con Claude Code compreso nell'abbonamento. Tetto di 100 chiamate per partita
   modificabile (una partita da 12 round: 36-48 chiamate con un'alleanza all'LLM, ~85 con entrambe).
6. **Rimuovere la chiave**: pulsante "Rimuovi" (la chiave sta solo in `%USERPROFILE%\.triplea-proplus\stratega.properties`).
7. Se qualcosa non va (rete, credito finito, chiave revocata) il diario scrive "stratega non disponibile (...)": quel
   round decidono le regole, la partita continua.
- DeepSeek e Groq sono selezionabili ma i loro modelli e prezzi NON sono stati verificati sui siti ufficiali
  (bloccati dalla sandbox): da provare con "Prova connessione" prima di usarli.

## Log degli step

### Step 0 — setup e build (2026-11-06)
- Fork `main` verificato identico a upstream `main` (commit a99d908). Branch `proplus` creato da lì.
- Nota: upstream `main` è 22+ commit avanti al tag `2026-11.16.15870` installato da Matteo, quasi
  tutti fix/feature della ProAi ("Hard"/"Hard Beta"). Salvataggi compatibili (nessuna classe
  serializzata di stato toccata), ma il comportamento dell'AI "Hard" della build Pro+ può già
  differire dalla 15870 ufficiale: per confronti A/B usare la build Pro+ con "Hard (AI)" come
  baseline, non l'installazione ufficiale.
- Aggiunti: questa sezione di CLAUDE.md, `proplus/dist/avvia.bat`, `proplus/dist/LEGGIMI.txt`,
  `.github/workflows/proplus-release.yml`. Nessuna modifica a codice di gioco/AI.

### Step 0bis — aggiornamento automatico + isolamento dati (2026-11-06)
- Aggiunti/modificati: `proplus/dist/aggiorna.ps1` (nuovo), `proplus/dist/build-info.txt` (nuovo,
  template), `proplus/ci/test-aggiorna.ps1` (nuovo, test CI), `proplus/dist/avvia.bat` (ora
  lanciatore sottile con auto-update + display versione), `proplus/dist/LEGGIMI.txt`,
  `.github/workflows/proplus-release.yml` (zip con aggiorna.ps1/build-info.txt + job
  `powershell-tests` su windows-latest che fa da gate). Nessuna modifica a codice di gioco/AI.
- Meccanismo di update e self-update: vedi sezione "Aggiornamento automatico (step 0bis)" sopra.
- **Punto 7 — isolamento dati Pro+: NON applicato (richiederebbe modifiche al codice).** Fatti:
  - Cartella utente (salvataggi + autosave + `optionCache` + mappe di default): deriva da
    `ClientFileSystemHelper.getUserRootFolder()` = `{user.home}/Documents/triplea` (o
    `{user.home}/triplea`). Dipende solo dalla proprietà JVM standard `user.home`; non esiste una
    proprietà dedicata per la cartella utente.
  - Impostazioni: `Preferences.userNodeForPackage(ClientSetting.class)` ⇒ su Windows registro
    HKCU `...\JavaSoft\Prefs\...`. **Non** dipende da `user.home`; non c'è switch JVM supportato
    per spostarle su file senza una `PreferencesFactory` custom (= codice / jar extra).
  - Cartella mappe e cartella salvataggi sono `ClientSetting` (`mapFolderOverride`,
    `saveGamesFolderPath`), valori nel registro; modificabili solo da GUI, non da proprietà di
    sistema (`triplea.map.folder` in `tools/util/ToolArguments` serve solo ai tool di map-making,
    non al client).
  - Conseguenza: l'unica leva senza codice è `-Duser.home=<cartella Pro+>`, ma sposta TUTTA la
    cartella utente (inclusa `downloadedMaps` ⇒ costringerebbe a riscaricare le mappe, contro il
    requisito "mappe condivise") e **non** isola comunque le impostazioni (registro). Quindi
    l'obiettivo "autosave+preferenze isolati, mappe condivise" non è raggiungibile a norma del
    vincolo "niente modifiche al codice di gioco". `avvia.bat` lasciato invariato su questo punto.
  - Opzione futura (SE si potesse toccare il codice o aggiungere un piccolo helper): leggere una
    proprietà/variabile Pro+ in `getUserRootFolder()` per salvataggi/autosave, più una
    `PreferencesFactory` su file per le impostazioni, tenendo `mapFolderOverride` sulla cartella
    condivisa. Fuori dallo scope dello step 0bis.

### Step 0bis-fix — aggiorna.ps1 falliva al primo comando (2026-11-06)
- Bug in produzione (PC di Matteo, doppio click su `avvia.bat`): `aggiorna.ps1 : Cannot bind argument
  to parameter 'Path' because it is an empty string` (`ParameterArgumentValidationErrorEmptyString
  NotAllowed`). Il default `$InstallDir = $PSScriptRoot` risultava vuoto in Windows PowerShell 5.1
  con `-File` senza `-InstallDir`; `Join-Path` falliva prima di ogni try/catch ⇒ update mai partito,
  `version.txt` mai scritto (avvia.bat proseguiva con la build locale per via dell'exit code ≠ 10).
- Perché la CI non l'ha visto: gli scenari 1-5 passavano sempre `-InstallDir` esplicito.
- Fix: `-InstallDir "%HERE%."` in avvia.bat; fallback `$MyInvocation.MyCommand.Path` /
  `$PSCommandPath` + `GetFullPath` in aggiorna.ps1; `trap` globale (avviso giallo + exit 0).
- Test nuovi (windows-latest): 7) `powershell.exe -File` senza `-InstallDir` da altra working dir;
  8) end-to-end `cmd.exe /c avvia.bat` su installazione vecchia senza `version.txt` contro il mock;
  9) come 7 e 8 in un percorso con spazi e parentesi (`...\con spazi\TripleA Pro+ (1)`). Copie di
  avvia.bat/aggiorna.ps1 nei test ora in CRLF come nello zip.
- Effetto per chi ha la build buggata: il suo `aggiorna.ps1` vecchio non può aggiornarsi da solo ⇒
  serve UN download manuale dello zip nuovo.

### Step 0bis-fix2 — sostituzione del jar falliva (2026-11-06)
- Bug segnalato dal PC di Matteo dopo 0bis-fix: `[avviso] errore durante l'aggiornamento (Cannot
  create a file when that file already exists.)`; restava `TripleA-ProPlus.jar.tmp` accanto al jar
  vecchio, `version.txt` non scritto. Causa: `Move-Item $jarTmp $jarPath -Force`.
- Perché la CI non l'ha visto: gli scenari 1/7/8/9 avevano già il jar presente e passavano. Sul
  runner (NTFS, Windows recente) la cancellazione di un file libera subito il nome (semantica
  POSIX), anche se un altro processo lo tiene aperto: `Move-Item -Force` riesce sempre. Verificato:
  lo scenario 10 (jar tenuto aperto con condivisione Read/Write/Delete) è passato anche sul codice
  VECCHIO (run 42234434013). Riprodotto invece su un disco virtuale **FAT32** (scenario 11, creato
  con diskpart): il vecchio `Move-Item -Force` fallisce con lo stesso identico messaggio del PC di
  Matteo, il codice nuovo passa (run 42234810413). Sul PC reale la condizione equivalente è
  probabilmente un file aperto da antivirus/sync/indicizzatore su un volume o filtro che non libera
  subito il nome (FAT/exFAT, cartella sincronizzata, ecc.): non verificato quale.
- Fix: `[IO.File]::Copy(tmp, jar, $true)` + rimozione `.tmp` (soluzione verificata da Matteo) +
  controllo dimensione; rimozione di un `.tmp` residuo a ogni avvio; nello script di swap di
  avvia.bat `move /y` → `copy /y` + `del`. Il jar non è più sostituito in modo atomico: se la copia
  si interrompesse a metà il jar sarebbe rotto (il controllo dimensione lo segnala, l'avvio
  successivo riscarica perché `version.txt` non è aggiornato).

### Step 1 — identità "Pro+" (2026-11-06)
- Nuovo tipo giocatore "Pro+ (AI)" = `ProPlusAi extends ProAi`, zero cambi di logica (vedi "Dove
  vive Pro+"). Log AI: righe `[Pro+] <nazione> purchase|combat move|non-combat move|place` all'inizio
  di ogni fase (il resto del log è quello della ProAi, che è statico e non sa quale AI lo chiama).
- Titolo finestre "TripleA Pro+ <versione>" + "(Pro+)" nel riquadro info. Nessuna classe Pro
  (`ai/pro/**`) modificata.
- File toccati: nuovi `ProPlusAi`, `ProPlusBranding`, `ProPlusAiTest`; modificati
  `HeadedPlayerTypes`, `MainFrame`, `TripleAFrame`, `GameSelectorPanel`, `HeadedPlayerTypesTest`.

#### Salvataggi (verificato su codice + test `ProPlusAiTest.savegameKeepsProPlusPlayerType`)
Nel salvataggio NON finisce nessuna classe AI: il tipo di giocatore è solo la stringa
`GamePlayer.whoAmI` (es. `"AI:Pro+ (AI)"`), scritta da `ServerGame` (riga 679) all'avvio/caricamento.
Al caricamento `SetupPanel`/`ServerSetupPanel` leggono `GamePlayer.currentPlayers()` e cercano
l'etichetta tra i tipi disponibili.
- a) Save Pro+ → build Pro+: l'etichetta esiste ⇒ "Pro+ (AI)" preselezionata. OK (test round-trip).
- b) Save Pro+ → TripleA ufficiale: il file si apre (nessuna classe nuova serializzata). L'etichetta
  non esiste ⇒ nessun errore, si ripiega: `PlayerSelectorRow.setDefaultPlayerType()` (partita
  locale) / `ServerSetupPanel.determinePlayerType()` (host di rete) ⇒ "Hard (AI)" se la nazione ha
  `defaultType="AI"` nell'XML (o nome che inizia con "Neutral"/"AI" in rete), "Does Nothing (AI)" se
  `defaultType="DoesNothing"`, altrimenti **Human**. In pratica: le nazioni Pro+ si riaprono come
  Human e vanno rimesse su "Hard (AI)" a mano (c'è il menu "Set all to"). Nessuna soluzione da
  implementare: comportamento accettabile; se servisse, si potrebbe salvare nel `whoAmI` l'etichetta
  "Hard (AI)" + un marcatore Pro+ altrove, ma tocca `ServerGame` (logica salvataggi) ⇒ non fatto.
  Non verificato eseguendo l'exe ufficiale: il codice di caricamento coinvolto è identico a upstream.
- c) Save ufficiale → build Pro+: stesso formato/stesse classi (nessuna classe serializzata
  toccata) ⇒ OK; le etichette ufficiali ("Hard (AI)", ecc.) esistono anche nella build Pro+.

#### Piano punto 4 — come Pro+ sovrascriverà soglie e valori (analisi; realizzato nello step 2)
Fatto chiave: tutte le funzioni Pro ricevono già l'oggetto `ProData` (anche le simulazioni di
acquisto, che riusano lo stesso `ProData` via `initializeSimulation`). Quindi il punto di aggancio
naturale è `ProData`, senza toccare `ProCombatMoveAi`/`ProNonCombatMoveAi`/`ProPurchaseAi`.
- Soglie di rischio — `ai/pro/ProData.java`:
  - righe 35-36: `winPercentage = 95`, `minWinPercentage = 75` (Low Luck);
  - righe 95-98 (`hiddenInitialize`): con dadi normali diventano 90 / 65.
  - Usate in `ProCombatMoveAi` (es. righe 471-473, 513, 693, 1290, 1657, 1926, 2039) e
    `ProNonCombatMoveAi` (es. 689, 893-897, 938-942, 1029-1030, 1216, 2192, 2204, 2473).
  - Altre soglie hard-coded da inventariare nello step 2: `AbstractProAi.AIR_DECIDES_THRESHOLD=50`
    (riga 68), `ProCombatMoveAi` STRAFE_MARGIN/TRADE_COUNTER_WEIGHT (righe 56-64),
    `ProCasualtyUtils` HOLD_CHANCE_* (righe 26-28), `ProOddsCalculator.BORDERLINE_CAPTURE_PERCENTAGE`.
- Valore territori / victory city — `ai/pro/util/ProTerritoryValueUtils.java`:
  - `findTerritoryAttackValue` righe 41-42: `3.0 * produzione * (factory+1)` e `3.0 * victoryCityValue`;
  - `victoryCityValue` righe 60-72: **oggi vale 0 fuori da Hard Beta** (`!proData.isBeta()`), poi
    `victoryCity * ProCasualtyUtils.VICTORY_CITY_VALUE` (=5, `ProCasualtyUtils.java:29`);
  - `findEnemyCapitalsAndFactoriesValue` riga 276: fattore `* 32` (non riceve `ProData`).
- Refactoring neutro proposto (step 2, comportamento ProAi invariato, verificabile con i test Pro):
  1. Nuova interfaccia `ProTuning` in `ai/pro/` con metodi default che restituiscono ESATTAMENTE i
     valori attuali (winPct 95/90, minWinPct 75/65, `valuesVictoryCities()` = beta,
     `victoryCityValue()` = 5, moltiplicatori 3.0 / 32 ...).
  2. `ProData`: campo `ProTuning tuning` + costruttore `ProData(boolean beta, ProTuning tuning)`;
     i costruttori esistenti usano il default. `hiddenInitialize` legge le soglie da `tuning`
     invece che dai letterali (righe 35-36, 95-98).
  3. `ProAi`: costruttore `protected ProAi(String name, String label, ProData proData)` (oggi
     `ProData` è creato dentro il costruttore, riga 18) così `ProPlusAi` passa il suo `ProData`.
  4. `ProTerritoryValueUtils.victoryCityValue`: `!proData.isBeta()` → `!proData.getTuning()
     .valuesVictoryCities()` e `VICTORY_CITY_VALUE` → `proData.getTuning().victoryCityValue()`;
     aggiungere `proData` a `findEnemyCapitalsAndFactoriesValue` (package-private, firma libera).
  5. `ProPlusTuning` (in `ai/proplus/`) sovrascrive solo ciò che serve.
  `ProData` non è serializzato nei salvataggi (non è `GameDataComponent`) ⇒ nessun rischio save.
  Alternativa scartata: togliere `final` da `ProData` e sottoclassarlo — più invasivo (Lombok
  `@Getter` sui campi, semantica meno chiara).


### Step 2 — Pro+ diversa da Hard: ProTuning + "Propensione al rischio" (2026-11-06)

#### A) Refactoring neutro (classi Pro: solo struttura)
- Nuova `ai/pro/ProTuning` (default = valori originali: win% 95/75 Low Luck, 90/65 dadi normali;
  victory city valutate solo con `beta` e solo con Total Victory/Honorable Surrender/Projection of
  Power; valore VC 5).
- `ProData`: campo `tuning`, costruttore `ProData(beta, tuning)` (i vecchi usano `DEFAULT`); nuovi
  getter `getAttackWinPercentage()`/`getAttackMinWinPercentage()` = per default identici a
  `getWinPercentage()`/`getMinWinPercentage()`. Replicato anche il comportamento originale "prima di
  initialize valgono 95/75, con Low Luck i campi non vengono riscritti".
- `ProAi`: costruttore `protected ProAi(name, label, ProData)`.
- Punti d'uso passati alle soglie "di attacco" (solo pianificazione degli attacchi):
  `ProCombatMoveAi` (tutti tranne `canHoldAgainstCounterAttack`, che valuta la tenuta contro il
  contrattacco nemico = difesa), `ProTerritoryManager.removeTerritoriesThatCantBeConquered`
  (scarto bersagli; usato anche da `ProPoliticsAi` per le dichiarazioni di guerra),
  `ProTerritory.setBattleResult` (flag `currentlyWins`, letto solo dal combat move).
  Restano sulle soglie generali (= Hard anche per Pro+): `ProNonCombatMoveAi` (difesa),
  `ProPurchaseAi`, `ProScrambleAi`, `ProTransportUtils`, `ProSeaBlockerUtils`, `ProBidPlanner`,
  `ProOddsCalculator` (ramo solo-Beta). `ProRetreatAi` non usa le soglie (decide sul valore
  TUV-swing + valore territorio × win%).
- `ProTerritoryValueUtils.victoryCityValue` legge `tuning.valuesVictoryCities(beta)`,
  `tuning.hasVictoryCityWinCondition(props)`, `tuning.victoryCityValue()`.
- Prova di neutralità: `ProTuningTest` (default = valori originali, Hard/Hard Beta usano DEFAULT) +
  `HardNeutralityGoldenTest`: ripete la vera pianificazione degli attacchi tedeschi del round 1 su
  Global 1940 (simulazioni di battaglia reali con dadi a seed fisso, `SeededBattleCalculator` in
  test, unità ordinate in modo stabile perché gli id sono UUID casuali) e la confronta con
  `src/test/resources/proplus/hard-golden-global1940-germans.txt`, **generato sul codice prima del
  refactoring** (riprodotto identico in 3 esecuzioni). Tutti i test `ai.*` preesistenti invariati.

#### B) Tuning Pro+
- Victory city: formula di Hard Beta (`3 × victoryCity × 5` nel valore d'attacco, e nei valori di
  difesa/vicinato dove la Beta già la usa). **Attenzione**: con la condizione della Beta la formula
  vale 0 su Europe 1940 e Global 1940 (vittoria con "Triggered Victory", Honorable Surrender false).
  Pro+ quindi la attiva anche con `Triggered Victory` = true (`ProPlusTuning.hasVictoryCityWinCondition`).
  Non è attiva nessun'altra parte della Beta (`isBeta()` resta false).
- Rischio: `RiskLevel` 1 Prudente 90/65 · 2 Cauto 85/62 · 3 Equilibrato 80/60 (default) · 4 Audace
  72/52 · 5 Spericolato 65/45; Low Luck +5 su entrambi, max 95, tranne Prudente che con Low Luck
  usa le soglie di Hard 95/75 (corretto nello step 2bis: prima era 95/70).
  - "desiderata" = `attackWinPercentage`: win% che il combat move cerca di raggiungere assegnando
    unità (aggiunge unità finché l'attacco è sotto), soglia per `currentlyWins`, per aggiungere
    anfibi/strafing alleato, per togliere bombardieri/navi bloccanti da un attacco, controllo scramble.
  - "minima" = `attackMinWinPercentage`: sotto questa win% (con tutte le unità possibili) il bersaglio
    è scartato; con dadi normali scarta anche se non resta un'unità di terra (Hard non-Beta).
- Calibrare: modificare i numeri in `RiskLevel` (e il test `RiskLevelTest`). Per vedere l'effetto sui
  bersagli: `ProPlusTuningTest` scrive `game-core/build/proplus/proplus-<livello>.txt` da confrontare
  con il golden di Hard. Esempio (Global 1940, Germania round 1): Spericolato aggiunge "93 Sea Zone"
  (maxWin 53%) ai bersagli che Hard scarta; Prudente = stessi bersagli di Hard.

#### C) Pannello + persistenza
- Pulsante "Impostazioni Pro+" accanto a "Resource Modifiers" in `SetupPanel.layoutPlayerComponents`
  (schermata locale, PBEM, PBF; **non** nella schermata host di rete `ServerSetupPanel`). Apre un
  `JOptionPane` con un cursore per alleanza (`ProPlusSettings.configurableAlliances`: alleanze con
  almeno un giocatore non `optional`, altrimenti tutte quelle con giocatori visibili; Europe 1940 ⇒
  Allies, Axis). Pannello nascosto riservato al futuro cursore "Fantasia".
- Persistenza **nella partita**: proprietà costanti `GameProperties` `"Pro+ risk: <alleanza>"` =
  `Integer` 1..5. Solo tipi JDK ⇒ il salvataggio si apre nella TripleA ufficiale (che ignora le
  chiavi). Lette da `ProPlusTuning` a ogni `ProData.initialize` (ogni fase e simulazione).
  Test: `ProPlusSettingsTest.levelsSurviveSaveAndLoad`.
- Log: `[Pro+] Germans round 3, alliance Axis, risk 3 Equilibrato, attack win% desired 80 / minimum
  60 (normal dice)` alla prima fase Pro+ del turno (log AI).

#### Prestazioni (misurate in sandbox, 4 core, 1 sola partita per configurazione)
Europe 1940 2nd Ed. (XML reale), tutte le nazioni stessa AI, round 1-2, `ServerGame.runNextStep`
come `AiGameTest` (benchmark temporaneo, non committato):
Hard 90.6 s · Pro+ Prudente 95.4 s · Equilibrato 87.5 s · Spericolato 70.9 s (Germania: 36.9 / 39.2 /
34.9 / 26.6 s). Il costo extra di Pro+ (lettura proprietà + valore VC) è trascurabile; le differenze
sono dovute a dadi diversi e piani diversi (un solo campione, varianza alta: ±5% è rumore).

#### Note ambiente (sandbox Claude)
- JDK 25: i download Adoptium/GitHub releases sono bloccati dal proxy; funziona
  `apt-get install openjdk-25-jdk-headless` (Ubuntu 24.04, 25.0.4).
- Maven Central restituisce spesso 429: ripetere il comando Gradle (script con retry).


### Step 2bis — diario delle decisioni (2026-11-06)

- Correzione preliminare: `RiskLevel.PRUDENTE` con Low Luck = 95/75 (= Hard), gli altri +5 (max 95).
  `RiskLevelTest.prudenteEqualsHardAlsoWithLowLuck` confronta con `ProTuning.DEFAULT`.
- Diario attivo per **Pro+, Hard e Hard Beta** (costo basso, vedi prestazioni): `DiaryRecorder.attach`
  in `ProPlusAi` (costruttore), `PlayerTypes.PRO_AI` e `HeadedPlayerTypes` (Hard Beta). `ProAi` e
  `FastAi` non sono toccate; una `ProAi` creata altrove (test) non ha diario.
- Agganci nelle classi Pro (solo righe di registrazione, nessuna logica cambiata):
  `ProData` (campo `recorder`), `AbstractProAi` (phaseStart/phaseEnd in purchase/move/place),
  `ProCombatMoveAi` (14 righe), `ProPurchaseAi` (10 righe), `ProTerritoryValueUtils.victoryCityValue`
  reso `public` (letto dal diario per la quota VC).
- Neutralità: `HardNeutralityGoldenTest` invariato e verde; `ai.pro.DiaryNeutralityTest` esegue la
  vera pianificazione d'attacco + acquisto Pro+ (Global 1940, Germania, dadi a seed fisso) sullo
  stesso `GameData` tre volte: off, off, on ⇒ mosse e acquisti identici (off/off prova che il
  confronto è significativo). Il move/purchase delegate sono mock che registrano soltanto.
- Fatto verificato leggendo il codice (importante per lo step 3): la **priorità** con cui il combat
  move ordina i bersagli (`prioritizeAttackOptions`) usa produzione + obiettivi nazionali, capitale,
  TUV swing; **non** usa il valore victory city. Il valore VC di Pro+ entra in
  `ProTerritoryValueUtils.findTerritoryValues` (valori usati da movimento non di combattimento,
  difesa e piazzamento) e nel valore dei vicini dei neutrali. Il diario mostra entrambi i numeri.
  **Superato dallo step 2c** (la priorità ora usa le VC per Pro+).

### Step 2c — victory city nella priorità degli attacchi di Pro+ (2026-11-06)

#### Dove entrava il valore VC di Pro+ PRIMA dello step 2c (correzione del report dello step 2)
Il report dello step 2 diceva "le VC valgono 3×5 punti in più nel valore di attacco": vero per la
funzione `ProTerritoryValueUtils.findTerritoryAttackValue`, ma quella funzione **non ordina i
bersagli**. È usata solo per i neutrali (valore dei territori nemici vicini a un neutrale a valore
negativo, `ProCombatMoveAi.prioritizeAttackOptions`; valore dei neutrali vicini in `findLandValue`/
`findWaterValue`) e dal diario. Quindi le VC non influenzavano la scelta degli attacchi. Mappa:
- priorità bersagli (`ProCombatMoveAi.prioritizeAttackOptions`): **no** (produzione + obiettivi
  nazionali, capitale, TUV swing). Dallo step 2c: sì, solo Pro+.
- simulazione nella fase acquisti: è lo stesso `doCombatMove` con lo stesso `ProData`
  (`AbstractProAi` righe 163/258) ⇒ stessa regola della priorità.
- `findTerritoryValues` → `findLandValue`/`findWaterValue` (`ProTerritoryValueUtils` righe ~352 e
  ~446): +5 per VC (`victoryCityValue`) nel valore dei territori **nemici vicini** di ogni territorio.
  Usato da: non-combat (`ProNonCombatMoveAi` 138, 751, 1128: dove difendere/spostare unità),
  acquisti/piazzamento (`ProPurchaseAi` 222, 485, 706, 1281, 1548, 2164: valore strategico dei
  territori di piazzamento, nuove fabbriche), e nel combat move per decidere se un bersaglio "si può
  tenere" (`determineTerritoriesThatCanBeHeld`, `ProCombatMoveAi` ~850). Effetto indiretto: rende
  più preziose le zone vicine a VC nemiche, non il bersaglio VC stesso.
- scelta delle perdite (tenere un'unità di terra per conquistare): `ProCasualtyUtils.captureValue`
  conta già +5 per VC per **tutte** le AI (non dipende da `ProTuning`).
- ritirata (`ProRetreatAi.captureValue`): **nessuna VC**, né per Hard né per Pro+ (non toccato).

#### Modifica
- `ProTuning.attackPriorityVictoryCityWeight()` (default **0** ⇒ Hard/Hard Beta identiche).
- `ProTerritoryValueUtils.victoryCityAttackPriority(proData, t, canHold)` = peso × n° VC, ×0.25 se il
  bersaglio non si può tenere (le condizioni di vittoria contano le VC tenute per un round intero);
  0 se le VC non sono valutate (stesse condizioni di `victoryCityValue`).
- `ProCombatMoveAi.prioritizeAttackOptions`: priorità = (TUV swing + valore produzione + bonus VC) ×
  moltiplicatori (capitale ×5, vicino alla mia capitale ×3, neutrale ×0.1). Con bonus 0 si usa
  l'espressione originale (stessi float). Il controllo "postura difensiva" (priorità ≤ 8 a ≤ 3 dalla
  mia capitale ⇒ scarto) usa la priorità **senza** quota VC: una VC non scavalca la difesa. Il ritiro
  di attacchi per tenere la capitale (`removeAttacksUntilCapitalCanBeHeld`) continua finché la
  capitale regge; la quota VC cambia solo quale attacco viene tolto per primo (unità vicine alla
  capitale / priorità).
- Hook `ProDecisionRecorder.attackPriority(proData, patd, victoryCityPart)`; diario: "priorita' X
  (quota VC Y)" in eseguiti/scartati/obiettivi, JSON `priorita_vc`.
- `ProPlusTuning.ATTACK_PRIORITY_VICTORY_CITY_WEIGHT = 10`. Motivo: un bersaglio di terra tenibile di
  produzione P vale 3·P (6·P se vuoto, ×2 con fabbrica) ⇒ 10 ≈ 3 PU di produzione: una VC supera un
  non-VC di produzione/TUV simili (Europe 1940: Novgorod, Volgograd, Polonia, Egitto, Ontario sono
  VC da 2 PU) ma non un bersaglio chiaramente più ricco (Western Germany 5 PU + fabbrica: 30) né una
  capitale nemica (×5: ≥ 60). Da calibrare con le partite.

#### Test
- `ai.pro.HardPriorityGoldenTest` + `proplus/hard-golden-priority-global1940.txt`: classifica delle
  priorità (territorio, valore, ordine) e scarti in classifica di Hard, Global 1940 round 1 per
  Germania/Italia/Gran Bretagna, **generato sul codice prima dello step 2c** (3 esecuzioni identiche).
  Mosse/acquisti non inclusi: con id unità UUID casuali cambiano tra due caricamenti della stessa
  mappa anche a codice invariato (verificato); il confronto a piano completo resta
  `DiaryNeutralityTest` (stessa `GameData`).
- `ai.pro.VictoryCityPriorityTest`: Global 1940 Germania round 1, Yugoslavia resa VC; Hard la lascia
  dietro Normandy Bordeaux, Pro+ (Prudente = soglie Hard) la mette davanti; France (capitale) resta
  prima per entrambe. Valori del bonus: Hard 0, Pro+ 10 / 2.5.
- `DiaryNeutralityTest` (on/off invariato, ora con il peso VC attivo) + controllo "(quota VC ".


#### Prova Europe 1940 (sandbox, 3 round, tutte le nazioni Pro+ Equilibrato)
Metodo: partita reale con Pro+ step 2c; a ogni turno, prima degli acquisti, il combat move è
ripianificato due volte sulla stessa copia dello stato con dadi di simulazione fissi: peso 0 (=
step 2bis) e peso 10 (= step 2c). Banco temporaneo (non committato, `EuropeVcBench` in
smoke-testing con XML reale), 234 s. Differenze:
- R1 Germania: France (capitale VC) prima in entrambi, priorità 337 di cui VC 50; piano uguale.
- R1 Italia: Egypt (VC, non tenibile: +2.5) sale dal 3° al 2° posto; piano uguale (non attaccata).
- R2 Russia: Poland (VC tedesca, non tenibile) sale dal 2° al 1° posto; piano uguale.
- R2 Italia: Egypt dal 3° al 2°; **piano diverso**: con le VC aggiunge l'attacco ad Alexandria
  (effetto indiretto dell'ordine sull'assegnazione delle unità), Egypt resta non attaccata.
- R3 Germania: Poland (riconquista, tenibile, vicino alla capitale ⇒ VC 10×3 = 30 su 56.5) già
  prima; Novgorod ed Egypt restano negative anche con la VC ⇒ scartate. Piano uguale.
- Americani, Britannici: nessun bersaglio VC raggiungibile, nessun cambio.
Giudizio: nei primi 3 round l'effetto è piccolo (1 piano cambiato su 15 turni). Motivo: le VC
non capitali raggiungibili presto (Egypt, Poland, Novgorod) quasi sempre "non tenibili" ⇒ bonus
2.5, e le capitali erano già prime. Leve per un effetto più forte: alzare il peso o il fattore 0.25.

### Step 2d — il filtro "con le unità assegnate non conviene" e il rischio (2026-11-06)

#### Diagnosi
Il messaggio `units_not_worth` viene da `ProCombatMoveAi.determineUnitsToAttackWith`, ciclo
"Determine if all attacks are worth it" (dopo l'assegnazione delle unità). Per ogni attacco
pianificato (strafing escluso) Hard lo scarta se vale UNA di queste condizioni (a ogni giro toglie
l'ultimo in classifica che fallisce, poi riassegna le unità e ricontrolla):
1. `capturesTooRarely`: win% < minima, oppure (dadi normali, non Beta) in media non sopravvive
   un'unità di terra (`units_win_rarely` / `units_no_land_unit`). Solo questa usa il rischio.
2. neutrale non tenibile (`units_neutral_cant_hold`).
3. valore < 0 (`units_negative_value`): `1 + TUV battaglia + valore territorio × win% − 2/3 ×
   TUV del contrattacco nemico`. Valore territorio = `(1 + terra − anfibio non tenibile + fabbrica
   + tenibile×(…)) × produzione + capitale`: **non contiene le victory city** (il peso VC dello
   step 2c entra solo nella priorità). Contrattacco: `canHoldAgainstCounterAttack` con le unità
   superstiti senza aerei contro il massimo nemico.
Il cursore di rischio non tocca la condizione 3, che è quella che scattava nei casi di Matteo.
Numeri (banco Europe 1940, Asse Spericolato; R1 Germania è lo stato iniziale, uguale alla partita
di Matteo):
- Normandy Bordeaux R1: win 78% (Matteo 76%), TUV battaglia −4.8, territorio 6×0.78, contrattacco
  vince 100% (TUV +5.4) ⇒ valore −2.6.
- 110 Sea Zone R1: win 68% (Matteo 67%), TUV +7.7, contrattacco vince 100% (TUV +13.2) ⇒ −0.1.
- Egypt Italia R1 (seed 1, come Matteo 77-79%): win 77%, anfibio non tenibile ⇒ territorio 2
  (VC = 0), TUV +1.3, contrattacco vince 100% (TUV +8.8) ⇒ −2.1. R2: win 100%, TUV +0.1,
  contrattacco +5.1 ⇒ −0.3. La priorità alta (13.1 con quota VC 10 per Matteo) non conta qui.
Secondo filtro che ignora le VC: `removeTerritoriesWhereTransportsAreExposed` (sbarco tolto se
0.75 × TUV nemico contro i trasporti > TUV battaglia + produzione); nel diario prima era
"verifiche finali", ora `transports_exposed` con i numeri.

#### Modifica (solo Pro+, Hard identica: default `Optional.empty()`)
- `ProAttackWorth` (record con i numeri del controllo), `ProTuning.keepsAttackNotWorthIt`,
  `ProTuning.keepsLandingWithExposedTransports`, hook diario `attackWorth`/`attackUnitsCleared`,
  `ProNationalObjectiveUtils.stake` (obiettivi nazionali anche fuori Beta).
- `ProPlusTuning`, mai a Prudente, mai neutrali, mai sotto la win% desiderata, solo per attacchi
  che Hard scarta per la sola condizione 3:
  - regola rischio: l'attacco prende qualcosa (territorio con valore > 0, o battaglia navale con TUV
    > 0) e `1 + TUV + territorio × win% − peso × contrattacco ≥ −tolleranza`;
  - regola VC/obiettivo: territorio nemico con VC, o con produzione e obiettivo nazionale in gioco;
    win% ≥ max(75, desiderata); TUV perso atteso (contrattacco − TUV battaglia) ≤ limite. Vale anche
    per i trasporti esposti (costo = 0.75 × TUV nemico − valore sbarco).
- `RiskLevel` (peso contrattacco / tolleranza / limite TUV regola VC):
  Prudente 2/3 / 0 / off (= Hard) · Cauto 0.6 / 0.5 / 4 · Equilibrato 0.55 / 1 / 8 ·
  Audace 0.45 / 2 / 12 · Spericolato 0.4 / 3 / 16. Motivo: Hard conta 2/3 del contrattacco; con più
  rischio l'AI scommette che il nemico non contrattacchi a pieno (peso giù) e accetta scambi
  leggermente negativi (tolleranza in TUV, ordine di grandezza dei contrattacchi visti: 4-15 TUV).
  Le VC non hanno valore nel controllo: il limite TUV è il "prezzo" accettato per togliere VC +
  produzione (Egypt: 2 PU/turno + VC + obiettivo italiano). Scambi su terre senza produzione
  (Alexandria, Vyborg) restano scartati: nella prima versione la regola li teneva tutti.
- Diario: motivi distinti con numeri (vedi sezione diario), "tenuto da Pro+" negli eseguiti.
  Correzioni: la voce "upgrade" degli acquisti ora dice "1 carro al posto di 1 artiglieria" (prima
  "1 carro, -1 artiglieria"); riga "Tempo AI" doppia = era quella della **Francia del round
  precedente** (0 PU ⇒ niente fase piazzamento ⇒ il turno si chiudeva solo al turno successivo,
  sotto il blocco dell'Italia): ora `GameDiary.turnStarted` chiude il turno rimasto aperto di
  un'altra nazione quando ne inizia una nuova.

#### Test
- `NotWorthAttackRuleTest` (regole per livello, Hard/Prudente mai), `RiskLevelTest` (mappatura
  monotona, Prudente = Hard), `ItalianTest`, `DiaryRecorderTest.aTurnWithoutPlace...`.
- Golden `HardNeutralityGoldenTest`, `HardPriorityGoldenTest`, `DiaryNeutralityTest` invariati e
  verdi (258 test `ai.*` verdi).

#### Prova Europe 1940 (sandbox, banco temporaneo non committato, 4 partite con dadi di gioco
seed 1-4, Asse Spericolato / Alleati Equilibrato; a ogni turno di Germania e Italia il combat move
è ripianificato sulla stessa copia con dadi di simulazione fissi: "prima" = step 2c, "dopo" = 2d)
- Germania R1 (stesso stato in tutti i seed): + Normandy Bordeaux (tenuta per rischio, valore Pro+
  −1); in 2 seed + Southern France, ma 110 Sea Zone esce (le unità vanno a Normandy, la 110 scende
  a 64% < 65%); in 1 seed (s4) + 110 SZ e 91 SZ.
- Italia: Egypt aggiunto in s2 R1, s2 R2, s4 R1; in s1 R1 Egypt resta scartato da un'altra verifica
  finale non ancora tracciata nel diario ("final_checks" generico, non trasporti esposti).
  Effetti collaterali visti: talvolta escono Algeria/Malta/Gibraltar/Cyprus (unità spostate su
  Egypt o su attacchi tenuti), entra Trans-Jordan/Syria.
- R2-R3 Germania quasi sempre uguale; 11 piani diversi su 24 turni Asse.
Giudizio: il cursore ora incide sul controllo finale; l'effetto è concentrato su R1 e sull'Egitto.
Da verificare in partita: perdite reali degli attacchi tenuti (Normandy contrattaccata al 100%).

### Step 3a — stato maggiore e piani per alleanza (2026-11-07)

Vedi "Stato maggiore e piani" per il funzionamento. Modifiche alle classi Pro (solo struttura,
default = valori di prima): `ProTuning` (+ `targetAttackWinPercentage`,
`targetAttackMinWinPercentage`, `attackWinPercentageCeiling`, `attackPriorityPlanBonus`),
`ProData` (+ getter per bersaglio e ceiling), `ProCombatMoveAi` (soglie per bersaglio,
`belowAttackTarget`, bonus piano nella priorità), `ProTerritory`, `ProTerritoryManager`,
`ProDecisionRecorder.attackPriority(+planPart)`.

#### Test
- Neutralità Hard: `HardNeutralityGoldenTest`, `HardPriorityGoldenTest`, `DiaryNeutralityTest`
  invariati e verdi (282 test `ai.*`). `DiaryNeutralityTest` ora anche con piano A: decisioni
  identiche diario on/off + contenuto del diario.
- `StrategyTuningTest`: soglie per fronte, ceiling, **Prudente + piano N = Hard** (soglie per ogni
  territorio, bonus 0, regole "non conviene" spente), regola obiettivo del piano col livello del
  fronte, senza file = step 2d.
- `strategia.StrategiaFileTest` (schema completo, modificatori, nomi validi su Global 1940; verificati
  a mano anche sull'XML reale Europe 1940), `strategia.StatoMaggioreTest` (fissato una volta,
  salvataggio/caricamento, rischio effettivo e limiti 1..5, obiettivi per nazione e flotta).
- `ProPlusSettingsDialogTest` (tendina piano, scrittura, piano fissato non modificabile). Pannello
  verificato con screenshot sotto Xvfb a scala 150% e 200% (schermo 1280x800): pulsanti visibili,
  contenuto scorrevole, nessuna barra orizzontale.
- Nota: il peso VC di Pro+ resta anche con Prudente + N ⇒ la **priorità** dei bersagli VC resta
  diversa da Hard (come dallo step 2c); soglie e regole sono quelle di Hard.

#### Prova Europe 1940 (sandbox, banco temporaneo non committato)
Partita reale XML Europe 1940 2nd Ed., tutte le nazioni Pro+ Equilibrato, piani Automatico (A / 1),
4 round. A ogni turno (prima degli acquisti) il combat move è ripianificato sulla stessa copia con
dadi di simulazione fissi per: "2d" (nessun file = step 2d) e ogni piano dell'alleanza.
- Stato salvato a fine prova: Axis `{"piano":"A","round_inizio":1,"storico":[]}`, Allies piano 1.
- Prestazioni: nessun costo misurabile (stesse durate tra 2d e piani, variazioni dentro il rumore);
  partita reale 24-35 s per round.
- R1 Germania (il turno con più scelte): differenze forti.
  - 2d: France 90% (16 unità), Normandy 97%, 110 SZ 100%, 91 SZ, 106 SZ, Southern France, Yugoslavia.
  - A Barbarossa: France 73% con 14 unità, aggiunge 111 SZ (flotta UK, 67%), 110 SZ 95%.
  - B/C Sea Lion: 4 zone di mare con flotta UK (111 SZ 53%), France 88%.
  - D: France 72%, 111 SZ 99%.
  - N: = 2d tranne Normandy Bordeaux (nello step 2d tenuta dalla regola VC/obiettivo nazionale,
    con N non è obiettivo).
  **Problema**: con Ovest +1 (Audace, desiderata 72%) l'AI mette su France solo le unità per
  arrivare al 72% e usa il resto altrove: l'obiettivo principale del piano diventa più rischioso
  (73% contro 90% della 2d). È l'effetto diretto di "rischio +1 sul fronte" applicato anche agli
  obiettivi; da decidere nello step successivo (es. soglia desiderata degli obiettivi non sotto
  quella del cursore).
- R1 Italia: B/C (Med −1) tolgono Alexandria e Malta; D toglie Alexandria/Malta e marca Greece
  obiettivo. R2 Italia con D: aggiunge Egypt (obiettivo, 100%).
- R2-R4: i bersagli raggiungibili sono pochi e quasi tutti vinti al 95-100% ⇒ i piani cambiano
  solo le soglie, quasi mai l'elenco (R3 Italia Novgorod obiettivo con A/C, R4 Germania
  Novgorod/Ukraine obiettivi con A/C: stessi attacchi).
- Alleati: effetto quasi nullo in combattimento nei round 1-4. Americani: nessun attacco
  possibile R1-R3; R1 Gran Bretagna con piano 2: Tobruk (obiettivo, 86%) e Italian Somaliland al
  posto di Ethiopia/96 SZ. Il piano 1 conta dal R4 (obiettivi sbarco) ma gli sbarchi richiedono
  acquisti e movimenti preparatori che il piano non guida ancora.
- Sea Lion (B) non ha fasi dopo il R3 ⇒ dal R4 "fase nessuna" (tutti 0, nessun obiettivo).

### Step 3b — acquisti, piazzamento e ammassamento guidati dal piano (2026-11-07)

Vedi "Logistica del piano" per il funzionamento. Correzioni dallo step 3a: soglia desiderata degli
obiettivi ≥ livello base (Barbarossa R1, Equilibrato, Ovest +1: France minima 52, desiderata 80);
Sea Lion R4+ = Barbarossa R3+. Modifiche alle classi Pro (solo agganci, default = Hard):
`ProTuning` (+ `purchaseOrders`, `stagingOrders`), `ProDecisionRecorder` (+ `purchaseOrders`,
`staging`), `ProPurchaseAi` (passo `plan` dopo `defend_land`), `ProNonCombatMoveAi` (bonus ai
valori + hook diario), `ProResourceTracker.getRemaining`. Nuove: `ProPurchaseOrders`,
`ProStagingOrders`, `ProPlanPurchase`, `ProStaging`.

#### Test
- `PlanLogisticsTest` (Global 1940 rinominata Europe, Germania R1, dadi fissi): Hard e piano N senza
  ordini; N compra come "nessun file"; Barbarossa rispetta il mix (entro un'unità per categoria),
  piazza in Germany; Sea Lion compra 2 trasporti + 1 cacciatorpediniere e li piazza in mare;
  bonus di ammassamento (1, 1/2, 1/3 della scala); non-combat con piano A: mosse identiche diario
  on/off, più unità finiscono in Poland/Slovakia Hungary/Romania che con N; testo del diario.
- `StrategyTuningTest` (desiderata obiettivi, flotta), `StrategiaFileTest` (logistica, Sea Lion R4+,
  mix ≤ 100%, categorie note, unità esistenti in Global 1940, file malformati).
- Golden Hard (`HardNeutralityGoldenTest`, `HardPriorityGoldenTest`) invariati e verdi;
  `DiaryNeutralityTest` verde (France ora 80/52). CI locale: `:game-core:test :ai:test
  :game-headed:test` verdi tranne `ContentDownloaderIntegrationTest` (rete assente in sandbox).
- Instabilità osservata 1 volta su 6 esecuzioni: `DiaryNeutralityTest.decisionsAreTheSame...` ha
  fallito il controllo preliminare "off = off" (stesso insieme di mosse, ordine diverso) nel caso
  senza piano, che lo step 3b non tocca. Non riprodotta in 5 esecuzioni successive (da sola e con
  tutta la suite). Probabile ordine di iterazione su oggetti con hash di identità: da indagare se
  ricompare in CI.

#### Prova Europe 1940 (sandbox, banco temporaneo non committato, XML reale, tutte le nazioni
Pro+ Equilibrato, 6 round, dadi di gioco seed 1; partite A/1, B/2, N/3)
- France R1 (A): 86% con desiderata 80 (step 3a 73%, step 2d 90%). Con B: 92%.
- Germania, unità di terra a Est a fine round (A / B / N): R1 16/10/20, R2 34/34/32,
  R3 65/39/57, R4 80/39/63, R6 72/77/83. Barbarossa ammassa prima (R2: 54 unità su 55 mosse si
  avvicinano a Poland/Slovakia Hungary/Romania, distanza media 2.8 → 0.9); B a Ovest R1-3 (Holland
  Belgium 18-23 unità) poi a Est dal R4.
- Acquisti tedeschi: A R1-3 ~35/25/30/10 (es. R2: 5 fant., 6 art., 2 carri, 1 mech, 1 caccia);
  B R1 2 trasporti + 1 cacciatorpediniere + fanteria/artiglieria, R2-3 con 1 caccia/turno.
- Trasporti tedeschi (B): prima versione piazzati in 112 SZ e affondati nel turno britannico del
  R1 ⇒ correzione: tra zone tutte attaccabili si sceglie la meno esposta (114 SZ, Baltico) e il
  diario lo segnala ("! navi del piano in una zona attaccabile"). Seconda prova: 3 trasporti a fine
  R1, in 110 SZ al R2, ma lo sbarco nel Regno Unito ha win% max 0% in R2-R3 (UK difeso) ⇒ Sea
  Lion prepara ma non sbarca; trasporti persi entro il R6.
- USA: fino all'entrata in guerra le zone verso Gibilterra/Regno Unito sono vietate dalle regole di
  neutralità della mappa (`movementRestrictionTerritories`) ⇒ ammassamento "lontano", nessun
  effetto. Flotta USA a 91 SZ dal R4 (piano 2) / R5 (piano 1), in 104/110 SZ al R6 (piano 1).
  La difesa urgente di Hard prende 18-26 PU su 35 ai R2-R3 (territori USA "minacciati") ⇒ il mix
  navale vale su 9-17 PU: 1 cacciatorpediniere + 1 trasporto per turno al massimo.
- URSS: mix 65/35 rispettato; ammassamento verso Mosca debole (molte unità restano/vanno al fronte
  per la difesa di Hard: "allontanate").
- Capitali: Germania a 1 unità a fine R3 in B (riserve spinte verso Holland Belgium e Polonia; in
  N anche Hard scende a 3 al R1). Nessuna caduta di capitale in 6 round in nessuna partita.

### Step 3b-bis — guarnigione capitale, quota del piano, dati Sea Lion (2026-11-07)

Vedi "Guarnigione della capitale e quota del piano". Classi Pro toccate (solo agganci, default =
Hard): `ProTuning` (+ `capitalGuardThreshold`), `ProPurchaseOrders` (+ `minimumShare`,
`DefenseShare`), `ProStagingOrders` (+ `air`, `AirResult`), `ProDecisionRecorder` (+ 3 hook),
`ProPurchaseAi`, `ProNonCombatMoveAi`, `ProResourceTracker` (+ `reserve/release`). Nuova
`ProCapitalGuard`. Dati: USA quota 70% (piani 1 e 2), UK 40% (piano 1); Sea Lion R1 3 trasporti +
scorta nel Baltico (114/113), R2 2 trasporti + mix aerei 40/fanteria 35/artiglieria 25, aerei
nell'ammassamento B (Germania R1-3) e D (Italia).

#### Test
- `CapitalGuardTest` (trattenute prima, poi riportate, capitale persa comunque = nessuna modifica,
  nessuna minaccia, riga del diario), `PlanShareTest` (capitale a rischio oltre il tetto; altrimenti
  difesa nel tetto; senza quota e Hard nessun ramo nuovo — confronto delle unità impossibile:
  `ProPurchaseUtils.randomizePurchaseOption` usa `Math.random`), `PlanLogisticsTest` (Sea Lion R1/R2,
  aerei B sì / A no), `StrategiaFileTest` (quote, aerei, scorta, quota fuori 0-100 rifiutata).
- Golden Hard e `DiaryNeutralityTest` invariati e verdi: 310 test `ai.*` + `:ai:test` verdi.

#### Banco Europe 1940 (1 partita, 3 round, dadi seed 1, tutti Pro+ Equilibrato, Asse B / Alleati 1; 112 s)
- Capitale tedesca: R1 rischio 100% → 25% (5 fanterie trattenute); R2 0% con 0 unità, R3 0% con 3:
  nessun nemico in guerra può arrivarci (la stima non conta l'URSS finché non è in guerra).
- USA: R1 quota 70%, ma Central United States a rischio 100% (fabbrica) ⇒ difesa senza tetto 12 PU,
  al piano 23/35 (66%); R2 35/35, R3 59/59 al piano. UK R1 20/29 (69%), R2-3 100%.
- Trasporti tedeschi: 4 a fine R1 (1 iniziale + 3), 6 a fine R2, tutti fermi in 114 Sea Zone;
  distrutti dall'URSS (1 caccia + 1 tattico) al R3. Da 114 SZ il Regno Unito è fuori portata (3 zone):
  win% su United Kingdom R1 0%, R2 0%, R3 non candidato; R4 non misurato (tetto 3 round).
  Problema: la "zona più sicura del Baltico" è una trappola per Sea Lion; l'ammassamento navale
  (scala mare 1-2.5) non li porta verso 112 SZ.
- Aerei (B): R1 12 su 12 verso Western Germany; R2 8 aerei finiti a Libya/Tobruk ("allontanati"):
  la scelta per valore aereo di Hard (attacchi possibili nel Mediterraneo) supera il bonus quando la
  raccolta più vicina (Holland Belgium appena presa) non è sicura. Da rivedere.

### Step 3b-ter — correzioni dalla partita di Matteo (7 round, Asse A Spericolato, Alleati 1) (2026-11-07)

Nota: nei commit e nei commenti del codice di quel giorno questo step si chiama ancora "step 3c"
(rinominato su richiesta di Matteo; lo step 3c è quello sotto).

Vedi "Politica, linea del fronte, presidi, imbarco, obiettivi critici". Classi Pro toccate (solo
agganci, default = Hard): `ProTuning` (+ `warDecision`, `minimumGarrisons`), `ProPoliticsAi`,
`ProStagingOrders` (+ `weights`), `ProStaging`, `ProNonCombatMoveAi` (pesi aerei, presidi),
`ProCapitalGuard` (+ `minimumGarrisons`), `ProDecisionRecorder` (+ 2 hook). Nuove: `ProWarDecision`,
`proplus/Staging`. Barbarossa: logistica tedesca divisa in R1-2 (preparazione, punti fissi), R3 e R4+
(linea del fronte Est peso 80 + coste della Manica peso 20, presidio 2 su Normandy Bordeaux,
Holland Belgium, Denmark); stessi ordini per B R4+ e C R3+.

#### Test
- `PlanStep3cTest` (guerra vietata/dichiarata/rinviata, Hard e N invariati; Francia ≥ 80 con
  Spericolato; linea del fronte e pesi; ripiego prima della guerra; Italia oltremare con imbarco e
  trasporto, anche in guerra con l'URSS), `StrategiaFileTest.ordersOfStep3c` +
  dichiarazione senza `dal_round` rifiutata; `StrategyTuningTest` aggiornato (Francia critica).
- Golden Hard, `DiaryNeutralityTest`: verdi (318 test `ai.*` + `:ai:test`).
- Spotless: il passo `expandwildcardimports` fallisce con un errore interno di Java-Parser su
  `CapitalGuardTest` (file invariato e già formattato); formattazione applicata al resto.

#### Banco Europe 1940 (1 partita, 4 round, dadi seed 1, tutti Pro+ Equilibrato, Asse A / Alleati 1; 157 s)
- Politica: R1-R2 "vietata prima del round 3"; R3 dichiarata (116 TUV in Poland/Slovakia
  Hungary/Romania contro 61). Germania-URSS in pace fino al turno tedesco del R3.
- Quote: Germania 100% al piano R1-R4 (nessuna difesa urgente); Italia 100%. USA: Central United
  States risulta a rischio 100% (fabbrica) ⇒ difesa senza tetto 12/15/25 PU ai R1-R3 (al piano
  66/57/29%), R4 100%: rischio probabilmente sovrastimato dalla stima del massimo attacco di Hard
  (da verificare). UK: Quebec 100% al R1-R2.
- Raccolta tedesca R3/R4 sulla linea del fronte (R4: Belarus, Karelia, Baltic States, Vyborg,
  Bessarabia, Western Ukraine + Manica): R4 88 unità mosse, 66 avvicinate, 11 allontanate (Matteo R7:
  56 su 82 allontanate). Presidi: Normandy 0→2, Denmark 0→2 al R3 e al R4, Holland Belgium 3.
- Italia: trasporto obbligato in più ogni turno R1-R2 (3 → 5 trasporti), unità in Africa: R1 8,
  R2 6 (Egitto 3, Alexandria 2), R4 8 (Egypt 4, Tobruk 3, Tunisia 1). Al R3 l'imbarco era sparito
  (la via di terra attraverso l'URSS in guerra contava come "raggiungibile"): corretto dopo il banco
  (solo terre proprie/alleate) e coperto da test, non ripetuto nel banco.
- Russi: linea del fronte formata dal R3; al R4 32 unità su 49 "allontanate" dalla linea (la difesa
  di Hard le ritira verso l'interno): da valutare.

### Step 3c — difesa solo su minaccia reale + avanzamento del piano e lettura del nemico (2026-11-07)

Vedi "Minaccia reale" e "Avanzamento del piano e lettura del nemico". Classi Pro toccate (solo
agganci, default = Hard): `ProTuning` (+ `realEnemies`, `garrisonNeeded`), `ProTerritoryManager`
(filtro in `findEnemyAttackOptions`, + `populateEnemyAttackOptionsOfEveryEnemy`), `ProPurchaseAi`
(`reportRealThreatDefense`, solo diario), `ProNonCombatMoveAi` (presidi condizionati),
`ProCapitalGuard` (`Result.threat`, `GarrisonResult.threat`), `ProDecisionRecorder` (+ 2 hook). Nuove:
`ProThreat`, `proplus/MinacciaReale`, `strategia/Valutazione`, banco `ProPlusBenchTest`. Dati: tappe e
firme di tutti i piani, B Sea Lion `sperimentale`. Rinominato lo step precedente "3c" → "3b-ter"
(CLAUDE.md e commenti; i nomi `PlanStep3cTest`/`ordersOfStep3c` restano).

#### Test
- `RealThreatTest`: USA R1 senza britannici/russi tra i nemici reali (Germania sì, "regole della
  mappa"); causa: con Hard le unità britanniche attaccano Central United States; Washington (Eastern
  United States) e Central United States senza acquisti di difesa ai R1-R3; Manica senza trasporti
  alleati ⇒ 3 presidi non necessari; con il trasporto UK di 109 SZ ⇒ presidio di Denmark con minaccia
  "British in guerra" (Normandy no: 110 SZ ha navi tedesche); Germania R1 Barbarossa senza URSS, R2 con
  URSS ("guerra prevista dal piano dal round 3") nella stima degli attacchi su Berlino.
- `ValutazioneTest` (9): tappe nel file, B sperimentale e mai "Automatico", file malformati, rampa,
  avanzamento in tempo/in ritardo/raggiunta (resta raggiunta), una voce per round per alleanza, tappa
  già alleata a inizio partita raggiunta solo se ripresa, lettura con inerzia (Sea Lion sale coi
  trasporti tedeschi), piano reale avversario, acquisti dalla cronologia.
- Golden Hard, `DiaryNeutralityTest`, tutti i test `ai.*` + `:ai:test` + `:game-headed:test` verdi.

#### Banco Europe 1940 (4 round, dadi seed 1, tutti Pro+ Equilibrato; A/1 136 s, D/2 123 s)
PU in difesa (`defend_land`) per nazione, R1/R2/R3/R4, nuova vs build precedente (stessi dadi di
gioco; le simulazioni dell'AI sono casuali, quindi le partite divergono dopo pochi turni):
- A/1: USA 0/0/0/0 vs 13/7/9/0 (al piano 30/30/53/56 vs 19/23/23/56); UK 0/13/0/0 vs 9/10/0/0
  (R2: minaccia reale, Asse in guerra); Germania 0 vs 9/0/0/0; URSS 0/6/9/0 vs 3/6/20/0; Italia
  0/0/0/21 vs 0/0/0/6.
- D/2: USA 0/0/0/0 vs 12/10/12/0; UK 0 vs 9/9/0/0; Germania 7/0/0/0 vs 0; URSS 0/6/6/9 vs
  10/7/0/9; Italia 0.
- "PU risparmiati" (stima): R1 Germania 15, USA 15, UK 9; UK 9 al R2 in D/2; poi 0 (dal R2-R3 la
  stima di Hard senza filtro non trova più territori in pericolo solo per nazioni non in guerra).
  Prima versione con il calcolatore di battaglie: tolta perché consumava i dadi della simulazione
  dell'AI (la Action ha fallito su `DiaryNeutralityTest` "replay without diary is reproducible");
  ora usa `ProBattleUtils.estimateStrengthDifference` (> 55 = "probabile conquista" di Hard).
- Presidi Manica: R3 3 non necessari, R4 tenuti (sbarco possibile). Berlino R2 (URSS contata):
  rischio 0% (nessuna unità sovietica arriva in un turno).
- Avanzamento: Asse IN LINEA in entrambe (A: France raggiunta al R2, Egypt R4; D: Egypt e Gibraltar
  R2); Alleati IN RITARDO dal R3 per reddito in calo da 2 round (105→99→97, 105→96→94).
- Lettura (prob. del piano reale): A/1 Alleati leggono A 45/62/59/58% (>50% dal R2); Asse legge 1
  27/46/56/61% (>50% dal R3). D/2 Alleati leggono D 43/28/29/18% (mai >50%; A in testa dal R2);
  Asse legge 2 46/32/24/21% (mai; 1 al 56-61% dal R3). La lettura dell'Asse è **identica** nelle
  due partite: gli indizi degli Alleati non distinguono i piani.
- Indizi che mancano: (Asse) gli acquisti navali USA sono uguali nei piani 1 e 2 e prima della
  guerra la flotta USA non può entrare nelle zone di Gibilterra/Regno Unito ⇒ servono indizi su dove
  vengono piazzate/ammassate le navi USA (101-106 SZ verso nord o verso sud) e sugli acquisti UK in
  Egitto/Sud Africa; (Alleati) "unità/aerei tedeschi in Italia/Africa" oscilla (aerei che atterrano
  e ripartono) e la quota TUV dell'Asse nel Mediterraneo crolla quando l'Italia perde unità ⇒ servono
  indizi stabili (unità di terra tedesche in Africa, trasporti italiani carichi, acquisti italiani).

### Step AUTOPLAY — partite AI vs AI automatiche in CI (2026-11-07)

Vedi "Partita headless AI vs AI" (runner) e "Autoplay in CI". Nessuna classe di gioco toccata
(nuovo modulo `:proplus-autoplay`, 2 righe in `settings.gradle.kts`, job `autoplay` in
`proplus-release.yml`, attesa del mock server in `proplus/ci/test-aggiorna.ps1`; `aggiorna.ps1` e
`avvia.bat` invariati).
- Verifica locale (sandbox, 4 core): 1 partita Pro+ A/1, 2 round, seme 1: 47 s, riepilogo corretto
  (Francia caduta al R2, VC Asse 3→4, lettura Alleati R2 = A 48% corretta); Hard vs Hard 1 round 37 s;
  confronto con una cartella vuota ⇒ "FALLITA".

- Prima run in CI (run 42381039087, commit 93ba1fa, tutto verde): release 8 min 30 s, autoplay 7 min
  20 s (prepare 3 min, 6 partite in parallelo da 8 round: 2 min 36 s - 3 min 51 s di partita, ~20 s per
  round; publish 19 s). Nessuna vittoria entro 8 round; VC R8 Asse/Alleati: A/1 7/4, A/2 5/6, D/1 6/5,
  D/2 4/7, C/1 6/5, Hard 6/5. Lettura: gli Alleati leggono bene A (>50% dal R3-R4) e D tardi (dal R6-R7);
  l'Asse legge 1 bene ma confonde 2 con 1 fino al R7 (A/2) o sempre (D/2), come già visto nello step 3c.
  Asse in A/1 spende in difesa quasi quanto per il piano (292 vs 297 PU): da guardare.

### Step 3c-bis — lettura del nemico cumulata + due anomalie tattiche + autoplay 12 partite (2026-11-07)

Vedi "Evidenza cumulata e indizi nuovi" e "Autoplay in CI". Classi Pro toccate (solo agganci, default =
Hard): `ProTuning` (+2 metodi), `ProCombatMoveAi` (2 punti). Pro+: `ProPlusTuning`, `Valutazione`,
`Strategia.Firma` (+`altrove`, `contro`, 3 tipi, classe `trasporti_carichi`), `DiaryRecorder` (esito
degli sbarchi), `Italian` (+`amphibious_strafe`), JSON della strategia (firme). Autoplay: `Monitor`,
`Riepilogo`, `RiepilogoGenerale`, `AutoplayOptions` (12 round), workflow (12 round, 2 semi).

#### Anomalie dell'autoplay di 8bfb575 (partita A/2, round 8, USA)
- **a) Normandy Bordeaux a 0%** (12 unità contro 15, 6 unità di terra perse): era uno **strafe** di
  Hard (`ProTerritoryManager.removeTerritoriesThatCantBeConquered`): per un territorio con fabbrica
  che non posso conquistare ma che un alleato (UK, che gioca dopo) può prendere, Hard attacca "per
  logorare" e sostituisce la win% del bersaglio con quella dell'alleato dopo lo strafe (100% ⇒ "max
  possibile 100%" nel diario); gli strafe sono esenti da tutti i controlli successivi (`allAttacksSucceed`,
  controllo "conviene", trasporti esposti). La stima dello strafe (`callBattleCalcWithRetreatAir`) fa
  ritirare solo gli aerei: con unità sbarcate (che non possono ritirarsi) l'attacco combatte fino alla
  fine. Pro+: `dropsAmphibiousStrafe` ⇒ scartato se la win% con le unità assegnate è sotto la soglia
  minima del livello effettivo del bersaglio (`getAttackMinWinPercentage(t)`), motivo
  `amphibious_strafe` nel diario. Hard: invariata.
- **b) Northern Italy 100% → "SCONFITTA" con 0 difensori** (e Sicily 100% → "RITIRATA"): errore del
  **gioco** (di Hard), non del diario. I due sbarchi scaricavano da 95 Sea Zone, confinante con
  Southern Italy (aeroporto italiano); con "Scramble To Any Amphibious Assault" i caccia italiani
  scramblano nella zona di scarico: il motore ricarica il carico prima della battaglia navale, un
  trasporto affondato porta con sé le unità (Northern Italy: carro + fanteria = TUV −9, i difensori
  restano 0) e i trasporti superstiti si ritirano col carico (Sicily: 2 "ritirati"). Hard Beta ha già
  il controllo (`isLostToScramble`), Hard no. Pro+: `checksLandingScramble` = sempre. Il diario
  attribuiva l'esito giusto ma lo descriveva male: ora "PERSI IN MARE (battaglia navale in <zona>
  prima dello sbarco ...)" o "SBARCO ANNULLATO (...)" quando le unità sbarcate mancano, i difensori
  non hanno perso nulla e c'è stata una battaglia mia in una zona di mare confinante nel round.
  (Verificato su codice, mappa e numeri del diario: il log della partita non ha il dettaglio.)

#### Test
- `ValutazioneTest`: memoria (stessa evidenza ⇒ stessa probabilità; trasporti spariti ⇒ 0.6, 0.36),
  `remember` (stato/evento), Asse legge 2 da piazzamenti UK in Sud Africa + attacco alla flotta
  italiana, Alleati leggono A dalla guerra all'URSS al R3 (valore tenuto al R4), firme nuove nel file.
- `ProPlusTuningTest`: i due agganci (Hard: no scramble check, nessuno strafe scartato; Pro+ sì, soglia
  60% a Equilibrato). `AutoplayTest`: colonne VC 4/8/12, tabelle per configurazione, primo sbarco
  (battaglia/presenza, niente bombardamenti/Africa), colonna trasporti.
- Golden Hard (`HardNeutralityGoldenTest`, `HardPriorityGoldenTest`), `DiaryNeutralityTest`, tutto
  `:game-core:test` e `:proplus-autoplay:test` verdi in locale.
- Verifica locale (firme finali): 1 partita A/2, 2 round, seme 1, 45 s. Lettura Asse R2: 2 al 66%
  corretta (indizi: piazzamenti UK in Sud Africa, attacco UK alla flotta italiana); Alleati: A 70% (R1),
  74% (R2), corretta. (Prima di togliere Northern Italy dalla lista "sud" gli Alleati leggevano A al 40%
  al R1: D saliva per 2 unità tedesche parcheggiate lì.) In sandbox fallisce solo
  `ContentDownloaderIntegrationTest` (scarica da internet, rete bloccata); `spotlessApply` segnala un
  errore del lint `expandwildcardimports` (Java-Parser), non del formato: la release non lo esegue.

#### Autoplay in CI (run 42387525038, commit 68f095c, tutto verde)
Release 8 min, autoplay 12 partite da 12 round (2 min 50 s - 7 min 24 s ciascuna). Nessuna vittoria
entro 12 round. VC R12 medie Asse/Alleati: A/1 7.5/3.5, A/2 6/5, D/1 4.5/6.5, D/2 5.5/5.5, C/1 6/5,
Hard 5/6. Lettura, probabilità media del piano reale / % round con lettura corretta, **R1-R8** (stessi
round dell'autoplay precedente; 8bfb575 = 1 seme, 68f095c = media di 2 semi):

| Config | Alleanza | 8bfb575 | 68f095c |
|---|---|---|---|
| A/1 | Asse | 59% / 100% | 40% / 43% |
| A/1 | Alleati | 52% / 88% | 75% / 100% |
| A/2 | Asse | 35% / 29% | 74% / 100% |
| A/2 | Alleati | 50% / 88% | 70% / 100% |
| D/1 | Asse | 53% / 86% | 56% / 71% |
| D/1 | Alleati | 34% / 38% | 23% / 0% |
| D/2 | Asse | 29% / 0% | 72% / 100% |
| D/2 | Alleati | 26% / 25% | 23% / 6% |
| C/1 | Asse | 56% / 100% | 48% / 43% |
| C/1 | Alleati | 45% / 88% | 41% / 81% |

- Migliorato: Asse distingue 2 (A/2, D/2 100% corretta); Alleati leggono A al 70-75% stabile.
- **D non riconosciuto (peggio)**: nelle partite D la Germania di Pro+ non porta MAI unità di terra
  nella lista "sud" né attacca nel Mediterraneo (misure sempre 0 in R1-R12, 4 partite), TUV a Est
  0.4-0.5 come in A, 0 trasporti tedeschi ⇒ gli indizi di A "pochi trasporti" e "poche unità al sud"
  valgono 1 anche in D. Il piano D, per la Germania, non produce il comportamento della sua firma:
  problema del piano (logistica D: raccolta Greater Southern Germany/Northern Italy/Southern Italy),
  non della lettura. Unico indizio D funzionante: nessuna guerra all'URSS al R3.
- **Asse legge peggio 1 nei primi round** (A/1, C/1): (a) "attacchi alleati su territori italiani vs
  costa tedesca" vale 1 per il piano 2 già al R1-R2 anche col piano 1 (l'UK attacca Ethiopia/Italian
  Somaliland/flotta italiana con la logica di Hard in ogni piano) e, non essendoci poi attacchi né in
  Italia né sulla costa tedesca, la quota resta "non disponibile" e la memoria **non decade mai**
  (difetto di progetto: per le quote di eventi serve decadimento anche senza misura); (b) anche col
  piano 1 l'UK piazza ~44% in Sud Africa (acquisti di Hard fuori dal mix). Valori non ritoccati.
- Primo sbarco alleato: R1 Holland Belgium in 2 partite (unità britanniche a fine round in un
  territorio tedesco senza battaglia registrata: da verificare), altrimenti R6-R12 (Norway, Denmark,
  Holland Belgium, Greece); D/1 seme 2 nessuno. Trasporti a fine partita: USA 9-22 (Hard 31-35), UK
  1-12 (Hard 14-18), Germania 0 ovunque, Italia 0-22.

### Step OPERAZIONI ANFIBIE — porto, raccolta, sbarco, spola (2026-11-07)

Vedi "Operazioni anfibie". Classi Pro toccate (solo agganci, default = Hard): `ProTuning` (+`heldUnits`),
`ProNonCombatMoveAi` (unità tenute = non muovibili in `findUnitsThatCantMove`). Pro+: `OperazioniAnfibie`
(nuova), `ProPlusAi` (acquisti delle operazioni sul tabellone reale, esecuzione prima del
non-combattimento), `ProPlusTuning` (ordini con le operazioni, minima = desiderata sui bersagli solo via
mare, `hold`/`heldUnits`), `PianoAttivo` (operazioni, bersagli come obiettivi), `Strategia` (`Operazione`,
`Porto`, parsing e nomi), `DiaryRecorder` (blocco "Operazioni anfibie", riparazioni), JSON della
strategia (4 piani, raccolta tedesca di D). Autoplay: `Monitor` (sbarchi veri, trasporti inattivi,
`beforeStep`), `Riepilogo`, `RiepilogoGenerale`, `AutoplayRunner` (1 riga), workflow (B/1, 14 partite).

Come si aggancia a Hard (cosa è riusato): lo sbarco resta il combattimento di Hard
(`ProCombatMoveAi` + `ProTransportUtils`/`ProMoveUtils.calculateAmphibRoutes`: trasporti carichi o
caricabili in raggio), guidato solo da priorità/soglie del piano (bersagli = obiettivi, minima =
desiderata); la stima della minaccia sulla flotta è quella della difesa di Hard
(`ProTerritoryManager.populateEnemyAttackOptions`, nemici reali dello step 3c) con
`ProOddsCalculator`; le mosse passano dal vero `IMoveDelegate` con le stesse `MoveDescription` di Hard
(carico con mappa unità→trasporto, trasporto + carico sulla rotta, scarico mare→terra); dopo, il
non-combattimento di Hard (`ProNonCombatMoveAi`: difesa, ammassamento, trasporti verso "best loading
territory"/"safest territory") muove tutto il resto e lascia ferme le unità tenute. Gli acquisti sono
obbligati in più dentro gli ordini del piano (`ProPlanPurchase`, quota del piano e piazzamento invariati).

#### Test
- `ai.pro.AmphibiousOperationsTest` (Global 1940 rinominata Europe, vero `MoveDelegate`, tabellone
  preparato): bersagli = obiettivi solo per le nazioni dell'operazione e dal round giusto; minima =
  desiderata per Normandy (solo via mare), Hard invariata (65, nessuna unità tenuta); UK carica a 110 e
  aspetta lo sbarco (stato "in rotta", stima su Normandy); dopo un turno carico senza sbarco ⇒ truppe a
  terra in UK ("bersaglio troppo forte"); nessun imbarco con 4 corazzate tedesche in 112 ("bloccata");
  il non-combattimento di Hard non muove le unità tenute; acquisti: 2 trasporti con 14 truppe in attesa;
  D: fanteria tedesca su trasporto italiano in 97 → Italia in 96 → Libia nel turno tedesco dopo.
  Trovato coi test: in Global 1940 "balanced" le corazzate sono trasporti da combattimento
  (`transportCapacity`) ⇒ l'operazione usa solo trasporti puri (`unitIsSeaTransportButNotCombatSea
  Transport`), le corazzate contano come scorta.
- `StrategiaFileTest` (operazioni dei piani, porti obbligatori, peso decrescente, vettori di default),
  `AutoplayTest` (primo sbarco vero, per alleanza, operazioni bloccate, colonna riparazioni).
- Verifica locale (1 partita D/1, 3 round, seme 1, 52 s, prima delle ultime correzioni): Italia R2
  carica in 97 e sbarca in Libia (1 fanteria + 1 antiaereo: corretto, gli antiaerei non salgono più);
  Germania R2 17 unità in Northern Italy, ma i trasporti italiani restano in 96 perché 97 non è sicura
  (flotta britannica) ⇒ nessun tedesco a bordo entro R3; UK R2 "bloccata" (110/109 non sicure, flotta
  UK distrutta al R1, chiede 1 cacciatorpediniere); USA R3 carica a 101 e va in 104; lo "sbarco" del
  diario contava anche sbarchi di Hard fuori bersaglio (Gibraltar, Iraq): ora solo i bersagli.


#### Autoplay in CI (run 42396636390, commit 3a02889, tutto verde: test 8 min 40 s, 14 partite da 12 round)
Nessuna vittoria entro 12 round. Primo sbarco vero alleato **in Europa** (British/Americans, unità
scaricate su terra nemica) per partita, seme 1 / seme 2:

| Config | Europa | USA: unità sbarcate (tutta la partita) | Trasporti inattivi Alleati (somma round) | VC R12 Asse/Alleati |
|---|---|---|---|---|
| A/1 | R7 Normandy (UK) / R6 Holland (UK) | 12 / 7 | 85 / 82 | 6/5, 8/3 |
| A/2 | R7 Denmark (USA) / R7 Greece (UK) | 38 / 38 | 99 / 102 | 6/5, 7/4 |
| D/1 | R9 Holland (UK) / R6 Normandy (UK) | 22 / 28 | 85 / 61 | 7/4, 6/5 |
| D/2 | R8 Normandy (USA) / R6 Greece (UK) | 42 / 12 | 107 / 119 | 4/7, 4/7 |
| C/1 | R7 Normandy (UK) / R5 Holland (UK) | 5 / 2 | 33 / 82 | 7/4, 5/6 |
| Hard | R6 Yugoslavia (UK) / R6 Holland (UK) | 30 / 40 | 173 / 185 | 7/4, 3/8 |
| B/1 | R8 Holland (UK) / R6 Greece (UK) | 4 / 4 | 54 / 64 | 8/3, 7/4 |

- **Alleati 1 (criterio: sbarco entro R6 in almeno metà)**: 4 partite su 8 con piano 1 (A/1, D/1, C/1,
  B/1, ognuna col seme 2) = metà, al limite; sempre britannici, mai l'Overlord USA. Hard: 2/2 al R6.
  Overlord USA bloccata quasi ogni turno dal R3-R4 ("scorta insufficiente per 110/109: win% nemica >
  20%", fino a 51 turni-motivo in D/1) e "bersaglio troppo forte" su Normandy (truppe rimesse a terra in
  UK, 6-13 volte): la flotta USA arriva in UK ma lo sbarco all'80% non c'è. Gli USA sbarcano **meno** di
  Hard nel piano 1 (2-28 unità contro 30-40). Il criterio "trasporti inattivi in calo rispetto a 68f095c"
  non è verificabile (la metrica non esisteva); rispetto a Hard nella stessa run gli Alleati Pro+ hanno
  33-119 turni-trasporto inattivi contro 173-185 (ma anche meno trasporti: USA 9-21 a fine partita
  contro 19-25).
- **Asse D (criterio: unità di terra tedesche in Nord Africa entro R5)**: 2 partite su 4. D/1 seme 2:
  1 carro in Libia al R3 (caricato al R2 a Northern Italy su trasporto italiano in 97), poi ondate fino a
  18 unità al R11; D/1 seme 1: 6 unità in Egypt (già dell'Asse) al R5. D/2 seme 1: carico tedesco al R2
  rimasto a bordo (trasporto italiano mai arrivato in 96: zona non sicura); D/2 seme 2: nessun imbarco
  ("porto 97 esposto": flotta britannica). La regola dei trasporti alleati funziona nel gioco reale.
- **Sea Lion (B, sperimentale)**: nessun tentativo sul Regno Unito (win% max 0% ogni round); Scotland
  attaccata al R3 (seme 2, 100%) e candidata al R4 (seme 1, 100%, scartata). "Leone marino" bloccata
  10-15 turni: trasporti vuoti fermi perché 112/113 non sono sicure.
- Hard identica: golden e `DiaryNeutralityTest` verdi in locale e in CI. `PlanLogisticsTest.theNeutral
  PlanBuysAsWithoutAStrategyFile` è fallito 1 volta su 5 in locale (passa 3/3 da solo): gli acquisti di
  Hard usano `Math.random` (`ProPurchaseUtils.randomizePurchaseOption`), instabilità preesistente.
- Correzione dopo la run: la colonna per configurazione "Alleati entro R6" usava il primo sbarco ovunque
  (sempre R1 Ethiopia, sbarco britannico di Hard in Africa orientale) ⇒ ora "Alleati in Europa entro R6".
- Da decidere (non tarato, per regola): soglia di sicurezza della flotta (Equilibrato: nemico ≤ 20%) e
  desiderata 80% sui bersagli solo via mare sono troppo prudenti per Overlord; il coordinamento UK+USA
  sullo stesso bersaglio non esiste (ognuno da solo non arriva all'80% su Normandy).

### Step OPERAZIONI ANFIBIE-BIS — coordinamento alleato (2026-11-07)

Vedi "Coordinamento alleato". Classi Pro toccate (solo agganci, default = Hard): `ProTuning`
(+`keepsBridgehead`), `ProCombatMoveAi` (chiamata dopo `keepsAttackNotWorthIt` per gli sbarchi). Pro+:
`TestaDiPonte` (nuova), `OperazioniAnfibie`, `ProPlusTuning`, `PianoAttivo`, `DiaryRecorder`/`AttackRecord`,
JSON (piano 1: fasi R1-2 / R3 / R4+, Mari +1 da R3). Autoplay: `Monitor`, `Riepilogo`, `RiepilogoGenerale`.
Test: `AmphibiousOperationsTest` (coppia USA+UK e ordine dei turni, minima 72 per gli USA su Normandy e 80
per il UK, Mari +1 da R3, navi UK "in arrivo" contate dagli USA, senza di esse flotta ferma con soglia 48%,
UK che raggiunge la flotta combinata, UK che rinforza la testa di ponte USA, rinforzi stimati e regola di
tenuta, Hard senza regola; aggiornati: truppe a terra nel porto di raccolta, flotta carica dal turno prima,
soglia 40%), `AutoplayTest` (USA in Europa occidentale per round, prima testa di ponte tenuta). Nei test su
Global 1940 servono USA in guerra con l'Asse e **alleati** del UK (in Europe 1940 USA-UK sono "Neutrality"
finché gli USA non entrano in guerra: la coppia vale solo da alleati).

#### Autoplay in CI (run 42404200388, commit 1586175, tutto verde)
Confronto con 28681cd (run 42399119888), seme 1 / seme 2; "USA ovest" = primo round e unità americane
sbarcate in Europa occidentale (Francia/Benelux/Norvegia/Danimarca/Germania) in tutta la partita:

| Config | USA ovest 28681cd | USA ovest 1586175 | Testa di ponte >= 2 round (1586175) | VC R12 Asse/Alleati 28681cd | 1586175 |
|---|---|---|---|---|---|
| A/1 | R9 9 / nessuno | R7 12 / R6 7 | R5 Norway (UK) / R6 Holland (UK) | 8/3, 8/3 | 8/3, 5/6 |
| D/1 | nessuno / nessuno | nessuno / R8 12 | nessuna / R10 Norway (USA) | 5/6, 5/6 | 5/6, 5/6 |
| C/1 | R8 6 / R10 9 | R8 13 / R8 17 | R8 Norway (USA) / R7 Norway (UK) | 7/4, 8/3 | 8/3, 8/3 |
| B/1 | R11 4 / R11 11 | R7 15 / R7 6 | R9 Normandy (UK) / nessuna | 6/5, 8/3 | 7/4, 7/4 |
| A/2 | R6 32 / R7 28 | R6 35 / R8 24 | | 7/4, 6/5 | 6/5, 7/4 |
| D/2 | R8 27 / R7 24 | R6 6 / R9 22 | | 5/6, 6/5 | 7/4, 3/8 |
| Hard | R7 25 / R8 17 | R10 27 / R10 8 | | 5/6, 6/5 | 5/6, 7/4 |

- **Criteri piano 1** (8 partite: A/1, D/1, C/1, B/1): sbarco USA in Europa occidentale entro R7 in 4/8
  (prima 0/8): metà, al limite (A/1 e B/1 sì, D/1 e C/1 no: R8). Testa di ponte tenuta >= 2 round in 6/8
  (metrica nuova, non c'è il confronto), ma metà sono sbarchi britannici di 2 unità in Norvegia/Benelux
  fatti da Hard, non teste di ponte coordinate. VC Alleati contro Asse A a R12: 3 e 6 (prima 3 e 3).
- **Coordinamento visto nei diari**: la sequenza USA sbarca → UK rinforza nello stesso round avviene (A/1:
  Normandy 90% con 12 rinforzi UK, contrattacco stimato 1% ⇒ tenuta; Holland/Normandy con 10-18 rinforzi
  UK in D/1 seme 2, C/1, B/1). Ma la maggior parte degli sbarchi USA su Holland Belgium sono tenuti dalla
  logica di Hard (valore positivo) anche con contrattacco stimato 96-100% dopo il rinforzo: la regola Pro+
  aggiunge sbarchi, non ne toglie; quelle teste di ponte vengono distrutte dal contrattacco tedesco.
- Piano 2 e piano D: nessun peggioramento netto in media (A/2 VC Alleati 4,5 → 5,4; D/2 6,5 → 4,8; Asse D
  VC medi 4.75 → 5.0; D/2 seme 2 crollo dell'Asse 3/8). Con 2 semi la varianza è alta.
- Hard identica: golden e `DiaryNeutralityTest` verdi in locale e in CI.
- Da decidere: scartare gli sbarchi di chi muove per primo su un bersaglio comune quando la tenuta dopo il
  rinforzo è oltre soglia anche se Hard li terrebbe (oggi non si scarta nulla); bersaglio unico condiviso
  (oggi il primo bersaglio comune su cui il primo sbarca, spesso Holland per priorità di Hard).

### Step OPERAZIONI ANFIBIE-BIS, chiusura — stesso bersaglio e scarto delle teste di ponte (2026-11-07)

Hook `ProTuning.dropsLanding` (default vuoto) + `ProPlusTuning.dropsLanding`, `TestaDiPonte.coppieSu` /
`bersaglioScelto` / `testaDiPonteAlleata`, motivi `bridgehead_other_target` e `bridgehead_not_held`
(`Italian.REASONS`). Vedi "Coordinamento alleato" (ultimo punto). Test in `AmphibiousOperationsTest`
(solo il bersaglio della coppia; scarto con contrattacco oltre soglia anche se Hard lo terrebbe, Hard
niente; tenuto con i rinforzi; il secondo non sbarca altrove con la testa di ponte alleata). Golden e
`DiaryNeutralityTest` verdi.

#### Autoplay in CI (run 42410637712, commit 315bcc2, tutto verde) contro 1586175 (run 42404200388)
Piano 1 = 8 partite (A/1, D/1, C/1, B/1, 2 semi). Perdite misurate dal diario: "sbarco" = attaccanti
americani persi nelle battaglie di sbarco in Europa occidentale; "contrattacchi" = difensori alleati
persi negli attacchi dell'Asse su territori americani di Europa occidentale (anche britannici di rinforzo).

| Metrica (piano 1) | 1586175 | 315bcc2 |
|---|---|---|
| Sbarco USA in Europa occidentale entro R7 | 4/8 | 2/8 |
| Unità USA sbarcate in Europa occidentale (somma) | 82 | 25 |
| Perse nelle battaglie di sbarco | 33 | 10 |
| Perse nei contrattacchi su territori USA | 177 | 39 |
| Testa di ponte tenuta >= 2 round | 6/8 (2 USA) | 7/8 (2 USA) |
| VC Alleati contro Asse A a R12 | 3, 6 | 3, 3 |
| Sbarchi scartati dalle regole nuove | - | 19 "non tenibile", 7 "altro bersaglio" |

- Le regole fanno quello che chiedono: gli sbarchi suicidi spariscono (perdite −80%), ma con essi quasi
  tutti gli sbarchi USA. Gli scarti "non tenibile" sono quasi tutti su **Holland Belgium** (confina con
  la Germania): contrattacco stimato 100% anche con 6-20 rinforzi britannici. Normandy Bordeaux è
  raramente pianificata (sotto la minima 72% o non raggiungibile), quindi il bersaglio della coppia
  scende a Holland e viene scartato. Criteri del piano 1 non raggiunti (sbarco entro R7 2/8, VC come
  28681cd); le teste di ponte tenute sono ancora soprattutto britanniche fatte da Hard.
- Piano 2 / D / Hard (VC R12 Asse/Alleati, 1586175 → 315bcc2): A/2 6/5, 7/4 → 7/4, 7/4; D/2 7/4, 3/8 →
  4/7, 6/5; Hard 5/6, 7/4 → 6/5, 7/4. Nessun peggioramento netto (2 semi, varianza alta).
- Lettura: il collo di bottiglia non è più la tenuta ma la forza dello sbarco su Normandy (truppe USA in UK
  e trasporti nella stessa zona nello stesso turno) e la capacità di rinforzo britannica (trasporti UK
  pochi o fuori raggio: rinforzi 0 in parte degli scarti).

- `DiaryNeutralityTest` reso robusto (run 42414747630 fallita sul controllo preliminare "replay without
  diary is reproducible": due turni senza diario con acquisti diversi, 2 o 3 artiglierie; in locale 2
  su 13 su 574ad7b, 0 su 6 su 92d6059, 1 su 6 allo step 3b): le mosse si confrontano sempre, gli
  acquisti (Hard usa `Math.random` in `ProPurchaseUtils.randomizePurchaseOption`) solo in un tentativo
  in cui due ripetizioni senza diario comprano uguale (max 4 tentativi). 6/6 verdi in locale.

#### Autoplay in CI (run 42418306952, commit 011a5ac, tutto verde) — chiusura dello step
Piano 1 = 8 partite (A/1, D/1, C/1, B/1 × 2 semi). Perdite dal diario come sopra.

| Metrica (piano 1) | 1586175 | 315bcc2 | 011a5ac |
|---|---|---|---|
| Sbarco USA in Europa occidentale entro R7 | 4/8 | 2/8 | 1/8 |
| ... entro R9 | 7/8 | 4/8 | 3/8 |
| Unità USA sbarcate (somma) | 82 | 25 | 49 |
| Perse nelle battaglie di sbarco | 33 | 10 | 12 |
| Perse nei contrattacchi su territori USA | 177 | 39 | 38 |
| Testa di ponte tenuta >= 2 round (di cui USA) | 6/8 (2) | 7/8 (2) | 8/8 (2) |
| VC Alleati contro Asse A a R12 | 3, 6 | 3, 3 | 3, 3 |
| Partite con "Overlord in stallo" | - | - | 7/8 |

- **Perché l'accumulo non parte**: le truppe USA non arrivano mai in United Kingdom (righe "Americans 0
  unita' in United Kingdom" quasi ovunque). I trasporti USA caricano a Eastern United States e si
  fermano carichi in **104 Sea Zone** (A/1 seme 2: 9 → 27 truppe a bordo dal R4 al R12) perché 110 Sea
  Zone non è mai sicura ("scorta insufficiente per 110: win% nemica 86-100% > soglia 48%", aviazione e
  sottomarini tedeschi; la flotta britannica non basta a renderla sicura). La rotta prova solo la zona di
  raccolta più vicina (110), mai 109. Hard non usa trasporti già carichi (vedi sopra) ⇒ quelle truppe
  non sbarcano mai. La riga di accumulo conta solo truppe in UK o a bordo nelle zone di raccolta, quindi
  segna 0 anche con 27 truppe in 104 (corretto per lo sbarco, fuorviante come "accumulo").
- Stallo segnato in 7 partite su 8 (dal R7, una dal R10): la regola funziona e porta lo stato del piano
  a IN RITARDO / IN CRISI; nessun cambio di piano (3d).
- Gli sbarchi USA che avvengono sono pochi e ancora soprattutto fuori Overlord (Norway/Normandy tardi,
  R8-R12). Holland Belgium attivo dal R8 in A/1 seme 2 (riga "win% Holland Belgium").
- Piano 2 / D / Hard (VC R12 Asse/Alleati, 315bcc2 → 011a5ac): A/2 7/4, 7/4 → 6/5, 7/4; D/2 4/7, 6/5 →
  4/7, 3/8; Hard 6/5, 7/4 → 5/6, 7/4. Nessun peggioramento netto.
- **Step OPERAZIONI ANFIBIE chiuso** (decisione di Matteo). Criteri del piano 1 non raggiunti. Aperto per
  lo step 3d: la logistica atlantica USA (zona di raccolta 110 mai sicura, nessun ripiego su 109 né su
  sbarco diretto da 104, flotta di scorta USA/UK insufficiente) e l'uso di "Overlord in stallo" per
  cambiare piano.

### Step 3d-1 — postura, cambio piano, contromosse + fix soste/scorta dei trasporti (2026-11-08)

Vedi "Postura, cambio piano, contromosse" e "Operazioni anfibie" (soste sicure e scorta). Classi: nuova
`strategia/Contromosse`; modificate `Strategia` (`Parametri`, `Vittoria`, `subentri`, `contrasta`,
`contromosse`, punti critici concreti con `da_round`/`a_round`), `StatoMaggiore` (`cambi`, `cambia`,
`roundPiano`), `Valutazione` (decisione a inizio round, proroga delle tappe), `StalloOperazioni.riparti`,
`PianoAttivo` (postura, contro-obiettivi, `roundSubentro`, rischio −1 in DIFENSIVA), `ProPlusTuning` (quota 0
in DIFENSIVA), `OperazioniAnfibie` (rientro, soste/scorta/pericolo, limite deviazioni), `ProPlusAi`,
`DiaryRecorder` (intestazione), autoplay `Monitor`/`Riepilogo`/`RiepilogoGenerale`; JSON (stato_maggiore,
subentri, contrasta, punti critici). Nessuna classe Pro toccata.
- Scoperta: in Europe 1940 gli Alleati partono con 8 VC su 11 (Asse 3): "VC tenute" non misura la loro
  corsa (Honorable 9 ⇒ quasi vinta dal R1). La corsa usa le condizioni dell'XML: Asse 8 VC, Alleati
  Germany + Southern Italy (`stato_maggiore.vittoria`).
- Test nuovi: `ContromosseTest` (13: file dati, scarti del parser, cambio con 2 round di crisi, nessun cambio
  con 1 round / IN LINEA / crisi di un altro piano, inerzia 3 round e massimo 2 cambi, bonus "contrasta", file
  reale: Overlord in stallo R7-R8 ⇒ piano 2 al R8 con ordini del piano 2 nello stesso round e tappe non subito
  in ritardo, piano N sempre NESSUNA, corsa pari/vantaggio/nemico più veloce, DIFENSIVA, CONTRASTO su Romania,
  RALLENTAMENTO e lettura al 50% senza effetti, effetti su PianoAttivo); `AmphibiousOperationsTest` (+7: rientro
  da 104 a United Kingdom, trasporto in transito non bloccato, nessuna sosta sicura ⇒ la flotta resta, scorta
  che accompagna, niente trasporto solo fuori porto, perdita al round prima ⇒ zona evitata, perdita di 3 round
  prima dimenticata); `AutoplayTest` (+1: piani e posture). Golden e `DiaryNeutralityTest` verdi.
- Verifica locale: 1 partita A/1 da 2 round (38 s): posture Alleati R1 RALLENTAMENTO (A letto 72%,
  contro-obiettivi Finland e Romania), R2 CONTRASTO (Asse +1 VC/round); Asse R1-2 OFFENSIVA.
- CI: run 42428388452 (4a6660e) interrotta dallo spegnimento del runner, il re-run è rimasto "queued"
  senza job e non si annulla; poi le Actions del fork si sono fermate (il push 4b17015 non ha creato
  run). Riattivate da Matteo; applicate le regole di consumo (sezione sopra).
- Il 2026-11-08 GitHub ha avuto ~1 ora di 504 sui download delle release (asset del gioco, Gradle, Kotlin):
  run 42466240207 verde al 3° tentativo (test + release); autoplay saltato per la condizione `success()`
  (corretta in 0fa7e03).

#### Autoplay in CI (run 42475178239, commit 0fa7e03, tutto verde) contro a9b51b3 (run 42420519719)
a9b51b3 = stesso codice di 011a5ac (commit solo doc), secondo campione; la run 42418306952 di 011a5ac
dava VC Alleati in A/1 3 e 3, questa 5 e 4 (varianza tra due campioni dello stesso codice).

| Metrica | a9b51b3 (= 011a5ac) | 0fa7e03 (3d-1) |
|---|---|---|
| Cambi di piano Alleati (12 partite Pro+) | - | 1 (D/1 seme 1: 1 → 2 al R9) |
| Cambi di piano Asse | - | 1 (D/1 seme 1: D → A al R11) |
| Cambi con piano IN LINEA / ping-pong | - | 0 / 0 |
| Partite piano 1 IN CRISI ≥ 2 round senza cambio | - | 6/8 ("nessuna alternativa migliore") |
| VC Alleati R12 contro Asse A (A/1) | 5, 4 (011a5ac: 3, 3) | 3, 4 |
| VC Alleati R12, media delle 6 configurazioni Pro+ | 4.75 | 5.6 |
| Sbarco USA in Europa occ. entro R7 (piano 1) | 2/8 | 5/8 |
| Unità USA sbarcate in Europa occ. (piano 1, somma) | 45 | 54 |
| Trasporti USA persi, piano 1 (8 partite) | 10 (113 comprati) | 19 (120) |
| Trasporti USA persi, piano 2 (4 partite) | 10 (84) | 24 (85) |
| Trasporti USA persi, Hard (2 partite) | 8 | 3 |

Trasporti USA persi per round (somma delle partite, R1-R12): piano 1 a9b51b3 0 0 0 1 1 0 1 0 2 0 2 3,
3d-1 0 0 0 0 3 0 1 5 2 4 1 3; piano 2 a9b51b3 0 0 0 0 1 3 0 1 0 0 1 4, 3d-1 0 0 2 1 1 0 2 4 5 4 4 1
(persi = trasporti a fine round prima + comprati − trasporti a fine round; non distingue affondati in
sosta da persi negli sbarchi del combattimento di Hard).
- **Cambio 1 → 2** (D/1 seme 1): piano 1 IN CRISI R8 (Overlord in stallo) e R8-R9 ⇒ piano 2 al R9 (2 0.31
  contro 1 0.19); Libya+Tobruk raggiunta al R11 (tappa R5 prorogata), Southern/Northern Italy in ritardo
  al R12; VC R12 3/8 (Alleati 8), la migliore delle partite piano 1.
- **Perché il piano 1 in crisi non cambia nelle altre 6 partite**: il punteggio del piano in vigore
  (0.40-0.72) resta sopra quello del 2 (0.29-0.52). Causa principale: `bonus_contrasto` 0.25 al piano
  1, che `contrasta` A/B/C, letti oltre 0.5 in quelle partite (A/1 65-74%); il piano 2 contrasta solo D.
  Il bonus premia il piano in vigore anche quando è in crisi. Non tarato (da decidere).
- Regole nuove dei trasporti attive: "nessuna sosta sicura" 0-7 volte per partita, "trasporti senza
  scorta" 0-4, "zona pericolosa" 0-4, rientro truppe a bordo 0-5. Ciononostante i trasporti USA persi
  sono aumentati (piano 1: 10 → 19, piano 2: 10 → 24), insieme agli sbarchi USA (piano 2: 53 → 105
  unità sbarcate in Europa occidentale): le perdite più probabili sono negli sbarchi decisi dal
  combattimento di Hard, che le soste sicure non coprono. Non verificato battaglia per battaglia.
- Posture: tutte e 4 usate; RALLENTAMENTO/CONTRASTO frequenti per gli Alleati nei R1-R5 contro A (A letto
  60-74%), DIFENSIVA solo con IN CRISI (R8-R12). Asse quasi sempre OFFENSIVA/RALLENTAMENTO.
- Hard identica: golden e `DiaryNeutralityTest` verdi in CI.

### Step 3d-1-bis — bonus "contrasta", trasporti negli sbarchi, rientro, autoplay (2026-11-08)

Vedi "Postura, cambio piano, contromosse" (formula), "Trasporti negli sbarchi del combattimento" e
"Rientro". Classi: nuove `proplus/TrasportiSbarco`, `pro/ProLandingTransports` (record); modificate
`Contromosse` (bonus solo alle alternative, motivo con i punteggi), `OperazioniAnfibie` (memoria del
pericolo nel rientro, `combinedArrivals` statico), `ProPlusTuning` (due override), `DiaryRecorder`/
`AttackRecord`/`Italian`, autoplay `Monitor`/`RiepilogoGenerale`. Classi Pro: solo hook con default vuoto
(`ProTuning` +2, `ProDecisionRecorder.landingTransports`) e le due chiamate (`ProCombatMoveAi`,
`ProMoveUtils`).
- Test nuovi: `ContromosseTest` +2 (piano in vigore in crisi senza bonus ⇒ cambio al 2; "nessuna alternativa
  migliore" con le parti dei punteggi); `AmphibiousOperationsTest` +6 (rientro: zona con perdite ⇒ soglia
  dimezzata; sbarco da una zona più sicura con la rotta del combattimento che scarica da lì e Hard senza zona
  propria; navi del partner contate nella raccolta comune; perdita ovunque ⇒ scartato coi numeri; guadagno
  maggiore ⇒ tenuto; obiettivo critico sopra la desiderata ⇒ tenuto, sotto ⇒ scartato); `AutoplayTest` +1.
  Test AI di game-core (394, golden Hard e `DiaryNeutralityTest` compresi) e autoplay verdi in locale.
- Verifica locale (2 partite da 2 round, B/1 e A/1): nel diario le righe "trasporti esposti dopo lo sbarco"
  scartano sbarchi mediterranei con trasporti esposti (Egypt, Malta, Morocco, Ethiopia: perdite attese 7-27
  TUV contro guadagni 2-5). Atteso: meno sbarchi "a perdere"; da verificare in CI che gli sbarchi USA in
  Europa entro R7 restino ≥ 4/8.


#### Autoplay in CI (run 42495914034, commit fb492f7, tutto verde) contro 3d-1 (0fa7e03, run 42475178239) e 011a5ac (run 42418306952)
Trasporti persi: 3d-1-bis contati dal `Monitor` (spariti tra due passi); 3d-1 e 011a5ac stimati come
"trasporti a fine round prima + comprati − a fine round" (non identico, ma stesso ordine di grandezza).

| Metrica | 011a5ac | 3d-1 | 3d-1-bis |
|---|---|---|---|
| Trasporti USA persi, piano 1 (8 partite) | 10 | 19 | 2 |
| Trasporti USA persi, piano 2 (4 partite) | 10 | 24 | 0 |
| Trasporti USA persi, Hard (2 partite) | 8 | 3 | 3 |
| Sbarco USA in Europa occ. entro R7 (piano 1) | 1/8 | 5/8 | 1/8 |
| ... entro R9 (piano 1) | 3/8 | - | 3/8 |
| Partite piano 1 IN CRISI >= 2 round | - | 7/8 | 5/8 |
| ... con cambio verso il 2 | - | 1 | 1 (D/1 seme 1, R9: 2 0.43 contro 1 0.31) |
| ... con "nessuna alternativa migliore" e punteggi | - | 6 | 4 (C/1 ×2, B/1 ×2: 1 0.27-0.37 contro 2 0.20-0.38) |
| Ping-pong / cambi con piano IN LINEA | - | 0 / 0 | 0 / 0 (D/2 seme 2: 2 → 1 al R11, un solo cambio) |

- La regola dei trasporti scarta 16-69 righe di sbarchi per partita Pro+ (0 in Hard), "scarico spostato"
  0-2, obiettivo critico 1 (Leone marino in B). Per Normandy il guadagno è 0-6 (produzione 2 × win% + TUV
  battaglia) contro 7-19 TUV di trasporti soli in 105/110 ⇒ quasi sempre scartato: i trasporti USA non si
  perdono più, ma gli sbarchi USA in Europa tornano ai livelli di 011a5ac. Criterio "≥ 4/8 entro R7" non
  raggiunto. Causa: il valore della conquista di Hard (solo produzione) ignora il valore di Normandy come
  obiettivo/testa di ponte del piano, e le navi di scorta propria contano come perdita.
- VC R12 Alleati (media delle 6 configurazioni Pro+): 5.08 (3d-1: 5.6); Hard 7/4 (Asse/Alleati).
- Hard identica: golden e `DiaryNeutralityTest` verdi in CI.

### Step 3d-1-bis, chiusura — valore strategico nel guadagno degli sbarchi (2026-11-08)

Decisione di Matteo: prima opzione del report (valore strategico del bersaglio nel guadagno), eccezione
"critico" non estesa, tutto il resto invariato (soglie, soste sicure, rientro); **tema sbarchi chiuso, si
passa al 3d-2**. Codice: `TrasportiSbarco` (formula sopra), `PianoAttivo.pesoObiettivoFase` /
`pesoBersaglioOperazione`. Test: `AmphibiousOperationsTest` +1 (valore strategico di Normandy nel piano 1:
sbarco tenuto, testo del guadagno); i test della regola senza valore strategico usano il piano 3 degli
Alleati (Normandy né obiettivo né bersaglio); il test dell'obiettivo critico ha 6 trasporti esposti (42 TUV >
guadagno 39.7) perché il valore strategico del Regno Unito in B (40) ora basta da solo. 395 test AI di
game-core e autoplay verdi in locale.


#### Autoplay in CI (run 42500350471, commit f4446c8, tutto verde) — chiusura del tema sbarchi
Trasporti persi dal `Monitor` per 3d-1-bis e chiusura; per 011a5ac e 3d-1 stima "fine round prima +
comprati − fine round". TUV perso in trasporti / TUV sbarchi solo dal 3d-1-bis (colonna nuova).

| Metrica | 011a5ac | 3d-1 | 3d-1-bis (fb492f7) | chiusura (f4446c8) |
|---|---|---|---|---|
| Trasporti USA persi, piano 1 (8 partite) | 10 | 19 | 2 | 12 |
| Trasporti USA persi, piano 2 (4 partite) | 10 | 24 | 0 | 5 |
| Sbarco USA in Europa occ. entro R7 (piano 1) | 1/8 | 5/8 | 1/8 | 2/8 |
| USA piano 1: TUV perso in trasporti / TUV sbarchi | n/d | n/d | 14 / 84 | 84 / 96 |
| USA piano 2: TUV perso in trasporti / TUV sbarchi | n/d | n/d | 0 / 89 | 35 / 51 |
| VC Alleati R12 (media delle 6 configurazioni Pro+) | 4.75 | 5.6 | 5.08 | 5.08 |

- Piano 1 IN CRISI >= 2 round: 4 partite, 1 cambio verso il 2 (B/1 seme 1, R9: 2 0.36 contro 1 0.23), 3 con
  "nessuna alternativa migliore" e i punteggi; nessun ping-pong (1 cambio Asse D → A in D/2).
- Il valore strategico rende di nuovo convenienti gli sbarchi sui bersagli del piano (trasporti persi risaliti
  a 12, sotto i 19 del 3d-1 ma sopra i 10 di 011a5ac), ma gli sbarchi USA entro R7 restano 2/8: il limite
  principale non è più la regola dei trasporti ma l'accumulo/stallo di Overlord (Overlord in stallo in 8/8 partite).
- Hard identica: golden e `DiaryNeutralityTest` verdi in CI. **Tema sbarchi chiuso** (decisione di Matteo).

### Step 3d-2 ridotto — strumenti di ritardo (2026-11-08)

Decisione di Matteo: il deterministico fa il "COME" (congelato), le scelte strategiche passeranno a un decisore via
contratto JSON (step successivo); qui solo strumenti eseguibili con interruttori, nessuna formula di convenienza (uno
"step 3d-2" con formule di convenienza era stato ipotizzato e abbandonato). Vedi "Strumenti di ritardo". Classi nuove
`strategia/Tattiche`, `StrumentiRitardo`; modificate `Strategia` (`ritarda`, `zone_di_passaggio`, `Ritardo`),
`Contromosse` (tattiche nello stato strategico + diario), `PianoAttivo` (interruttori, lettura, caccia),
`ProPlusTuning.dropsLandAttack`, `ProPlusAi`, `DiaryRecorder`/`Italian`, autoplay `Monitor`/`Riepilogo`/
`RiepilogoGenerale`; classi Pro: solo l'hook `ProTuning.dropsLandAttack` (default vuoto) e la sua chiamata in
`ProCombatMoveAi`.
- Test nuovi: `DelayToolsTest` (10: decisore per ruolo/postura/fronti, piano N e Asse senza strumenti, blocco su
  Belarus vuota con fanteria da Smolensk, ritirata da Belarus verso Novgorod, contrattacco solo su stack isolati e Hard
  mai, blocco navale in 111 con Sea Lion letto al 70%, niente blocco sotto soglia, caccia a un trasporto carico in 112
  con peso 15, perdite dei blocchi dal registro); `AutoplayTest` +1 (sezione 3d-2). 416 test AI di game-core e
  autoplay verdi in locale (golden Hard e `DiaryNeutralityTest` compresi).
- Nota: con la regola letterale "mai togliere fanteria da territori minacciati" il test con 6 fanterie a Smolensk non
  trovava fonti (Smolensk raggiungibile dai carri e dagli aerei tedeschi): da qui la regola "resta sotto soglia senza".
- Verifica locale (A/1, 2 round, 43 s): Alleati in RALLENTAMENTO dal R1 ⇒ strumenti attivi per tutte le nazioni
  alleate (Russians anche per ruolo); blocchi R1-R2: 6 (quasi tutti "lasciata" in territori gia' presidiati: Bessarabia,
  Eastern Poland, Baltic States; 1 in Alexandria), 1 perso; 1 attacco britannico scartato dalla regola
  "solo stack isolati" (Anglo Egyptian Sudan 84% < desiderata 85%); nessuna ritirata, nave o caccia in 2 round.

#### Autoplay in CI (run 42506232917, commit 2853ee7, tutto verde) contro f4446c8 (run 42500350471)
Gli artefatti per partita di f4446c8 non sono scaricabili dalla sandbox (host Azure degli artefatti bloccato dal proxy) e
`autoplay-latest` e' stato ricreato: per f4446c8 valgono solo i numeri gia' registrati qui (cadute di Novgorod/Russia non
misurate prima di questo step).

| Metrica | f4446c8 | 2853ee7 (3d-2) |
|---|---|---|
| VC Alleati R12, media delle 6 configurazioni Pro+ | 5.08 | 4.25 |
| VC Alleati R12 contro Asse A (A/1, A/2) | - | 3.5 / 3.5 |
| Russia (Mosca) caduta, partite Pro+ | n/d | 6/12 (R9-R12); Hard 0/2 |
| Novgorod caduta, round medio | n/d | Pro+ R4-R6 per configurazione; Hard R3.5 |
| Volgograd caduta | n/d | 8/12 Pro+ (R6-R12); Hard 2/2 (R5, R8) |

Strumenti (12 partite Pro+, somma): blocchi terrestri Russians 237 (224 "lasciata", 13 "portata"), persi 124; Germans 49 /
19 (Asse in RALLENTAMENTO 33 round su 144); British 19/12; Italians 6/1. Ritirate: Russians 19 (539 unita' arretrate),
Germans 11 (189), British 6 (45), Italians 9 (38). Blocchi navali: 21 navi mandate/lasciate, 15 perse (Germans 7/7).
Caccia ai trasporti: 34 zone segnate, **0 trasporti affondati**. Attacchi scartati da "solo stack isolati" (righe del
diario, piu' stadi della stessa pianificazione): Russians 212, Germans 130, Italians 69, British 47, Americans 14.
- Giudizio (non tarato): gli strumenti si attivano dove previsto (Russians per ruolo in tutte le partite, posture per
  le altre nazioni); il blocco terrestre e' quasi sempre "lasciata" in territori gia' presidiati (effetto scarso); la
  caccia non produce affondamenti (il bonus 15 non basta a far attaccare Hard); i blocchi navali tedeschi si perdono
  tutti. Peggioramento netto probabile per gli Alleati (VC −0.83 di media, Mosca cade in 6/12): ipotesi principale la
  regola "solo stack isolati" sui russi (212 scarti) + ritirate che cedono territorio (reddito russo in A: 28 → 1-8 a
  R9). Varianza alta (2 semi), ma la direzione e' negativa. Non tarato: da decidere.

### Step CONTRATTO STRATEGICO — decisore, stato strategico e direttive JSON (2026-11-08)

Decisioni di Matteo sul 3d-2 applicate prima del resto: (a) strumenti di ritardo tutti spenti nel decisore a regole
(`Tattiche.spente()`; la regola del 3d-2 resta come `Tattiche.perRuoloEPostura`, usata dai test) ⇒ Pro+ torna a f4446c8
per la parte strumenti; (b) paletto del prelievo di fanteria invariato; (c) fanterie "lasciate" non contano come blocchi
(registro `blocchi` = portate, `blocchi_lasciati` a parte; `Monitor` conta `blocchi_lasciati` separatamente). Poi il
contratto: vedi "Contratto strategico" e `proplus/strategia/contratto-v1.md`.
- Classi nuove (`ai/proplus/strategia/`): `Decisore`, `DecisoreRegole`, `DecisoreFile`, `DecisoreLlm`, `Decisori`,
  `Ingresso`, `Direttive`. Modificate: `Valutazione` (flusso del decisore, `Risultato.ingresso`), `PianoAttivo` (legge
  solo le direttive), `Tattiche`, `Contromosse` (tattiche spente), `TrasportiSbarco` (attendi, tetto delle perdite),
  `ProPlusTuning` (soglia minima assoluta), `StrumentiRitardo` (punto c), `ProPlusAi` (rapporti delle operazioni),
  `DiaryRecorder` (file del contratto), `ProPlusSettingsDialog` (riga Decisore), autoplay `AutoplayOptions`
  (`--decisore`), `AutoplayRunner`, `Monitor`, `Riepilogo` (`contratto`), `RiepilogoGenerale` (sezione), workflow
  autoplay (JSON del contratto solo delle partite seme 1 nello zip). **Nessuna classe Pro toccata** (Hard identica).
- Golden Pro+ (`ProPlusGoldenTest`): registrato sul codice di f4446c8 + punto a, **identico dopo il refactoring**
  (verde). Report sul round 1 di Europe 1940 reale (mappa clonata in sandbox, `PROPLUS_GOLDEN_EUROPE_XML`): identico
  prima/dopo salvo le chiavi nuove della voce dello storico (`direttive`, `decisore`). Golden Hard e
  `DiaryNeutralityTest` verdi.
- Test nuovi: `ContrattoStrategicoTest` (9: direttive delle regole valide con strumenti spenti e operazioni lanciate;
  ingresso con tutti i campi ed entro 20.000 caratteri; file che cambia postura/rischio/operazioni/bersagli/tattiche con
  i limiti e gli avvisi; operazione annullata; cambio piano ammesso da file; cambio non ammesso ⇒ ripiego; terzo cambio
  rifiutato dai paletti; file non JSON / mancante / postura sconosciuta ⇒ ripiego; file stato/direttive scritti dal
  diario e riletti da `DecisoreFile`), `ProPlusGoldenTest` (2 + 1 facoltativo), `ProPlusSettingsDialogTest` +1,
  `AutoplayTest` +2. `ContromosseTest`/`DelayToolsTest`: gli helper scrivono anche le direttive
  (`DecisoreRegole.direttive`).
- Verifica locale (Europe 1940 reale, seme 1): A/1 2 round 27 s, 4 ingressi da 4.500-6.800 caratteri (media 5.852 =
  ~1.460 token stimati), direttive delle regole senza tattiche; A/1 1 round con `--decisore Allies=file:...`
  (DIFENSIVA, Overlord UK in attesa, blocco terrestre e ritirata russi accesi): direttive applicate, 2 blocchi
  "lasciata" (non contati come blocchi), nessun ripiego.

#### Autoplay in CI (run 42513100896, commit 4db1abe, tutto verde) contro f4446c8 (run 42500350471) e 2853ee7 (run 42506232917)
Primo tentativo: `powershell-tests` fallito per il mock server Windows (scenario 9b "Mock server non pronto", flaky gia'
visto; il job era partito perche' il push aggiungeva `proplus/strategia/`) ⇒ build e autoplay saltati; "re-run failed
jobs" verde (attempt 2). Tutte le direzioni con decisore `regole` (strumenti spenti).

| Metrica | f4446c8 | 2853ee7 (3d-2) | 4db1abe (contratto) |
|---|---|---|---|
| VC Alleati R12, media delle 6 configurazioni Pro+ | 5.08 | 4.25 | 4.75 |
| VC Alleati R12 per partita (12 Pro+) | - | - | 4,4 / 4,6 / 6,5 / 6,5 / 4,4 / 6,3 (dev. std 1.06) |
| Russia (Mosca) caduta, partite Pro+ | n/d | 6/12 | 2/12 (R11, R12); Hard 1/2 |
| Strumenti di ritardo usati | - | si' | 0 in tutte le partite |
| Ingresso del decisore (288 file) | - | - | media 6.969 caratteri (~1.742 token), max 8.900 (~2.225) |
| Ripieghi sulle regole | - | - | 0 / 288 |

- VC Alleati R12: 4.75 contro 5.08 di f4446c8, differenza −0.33 con deviazione standard per partita 1.06 (errore
  della media su 12 partite ~0.3): **in linea entro la varianza**, non identico (le simulazioni dell'AI restano casuali).
  Rispetto al 3d-2 (+0.5 VC, Mosca cade 2/12 invece di 6/12) il peggioramento del 3d-2 sparisce con gli strumenti spenti.
- Cambi di piano: 2 (Alleati in D/1 e D/2, un seme ciascuno). Budget dell'ingresso rispettato con ampio margine.
- Hard identica: golden Hard, golden Pro+ e `DiaryNeutralityTest` verdi in CI.

### Step INTEGRAZIONE LLM — stratega LLM (Claude Code / chiave API / regole) (2026-11-08)

Decisione di Matteo: lo stratega LLM decide il COSA/PERCHE', il deterministico esegue; provider Claude Haiku 5.5.
Vedi "Stratega LLM" e "Come configurare lo stratega". Prima le due correzioni chieste dal report del contratto:
(a) tetto delle perdite di trasporti accettate per ALLEANZA PER ROUND sommando tutti gli sbarchi (`TettoTrasporti`,
test `TettoTrasportiTest`); (b) `powershell-tests` solo per modifiche sotto `proplus/ci/` e `proplus/dist/`.
- Classi nuove: `strategia/llm/*` (Modalita, Fornitore, ProfiloStratega, ClaudeCode, ClientApi, Http, Richiesta,
  Risposta, ErroreStratega, PromptStratega, UscitaLlm, Uso, Prezzi), `TettoTrasporti`, UI `StrategistPanel`, autoplay
  `TestQualita`; risorse `prompt-stratega-v1.md`, `prezzi-stratega.json`. Modificate: `DecisoreLlm` (da segnaposto
  a stratega vero), `Decisore` (`etichetta`, `NonDisponibile`), `Decisori` (modalita'), `Valutazione` (ripiego con
  avviso, riga "Stratega:", uso), `Direttive` (costanti pubbliche), `Strategie.json`, `TrasportiSbarco` (punto a),
  `ProPlusSettingsDialog`, autoplay `AutoplayOptions`/`AutoplayRunner`/`Riepilogo`/`RiepilogoGenerale`, workflow.
  **Nessuna classe Pro toccata** (Hard identica).
- Test nuovi (tutti con client/processi finti, nessuna rete): `StrategaLlmTest` (10: direttive applicate e scritte
  nel diario con costo, Fantasia nel prompt, errore/timeout ⇒ regole con avviso, risposta non JSON, piano non
  ammesso, Automatico con/senza Claude Code, nessuno stratega, tetto delle chiamate, partita senza scelta = regole),
  `StrategaComponentiTest` (19: corpo Anthropic con cache 1h/schema/senza temperature, DeepSeek/Groq JSON mode e
  temperatura, chiave rifiutata senza chiave nel testo, nuovo tentativo su 5xx, due timeout, credito/quota, ricerca
  di Claude Code, non collegato, comando headless, timeout, profilo/maschera/rimozione, variabile d'ambiente, import
  del file, prefissi, prompt fisso, schema strict, conversione, controllo offline, testo dell'uso),
  `TettoTrasportiTest` (2), `StrategistPanelTest` (5: prova connessione con chiave giusta/sbagliata, import +
  maschera + rimozione, salvataggio, stato rilevato), `ProPlusSettingsDialogTest` aggiornato, `AutoplayTest` +2
  (riepilogo dello stratega, test di qualita').
- Test in locale (sandbox, JDK 25 da apt): `:game-core:test` 3080 test, falliti solo `ContentDownloaderIntegrationTest`
  (rete della sandbox) e una volta `DiaryNeutralityTest.decisionsAreTheSameWithAndWithoutDiary` (acquisti di Hard con
  `Math.random`, nessun piano: verde rilanciato); golden Hard, golden Pro+ (`DecisoreRegole`) e
  `HardNeutralityGoldenTest` verdi; `:game-headed:test` 93, `:proplus-autoplay:test` 16, `:ai:test` 13 verdi.
  `spotlessApply` non eseguito (Maven Central 429 dalla sandbox; la CI non lo esegue).
- Verifica locale (Europe 1940 reale, A/1, 2 round, seme 1, `--decisore Allies=llm` SENZA chiave, 40 s): ogni round
  degli Alleati "Avviso del contratto: stratega non disponibile (nessuna chiave Haiku salvata): decidono le regole",
  riga "Stratega: regole - postura ...", partita completata; `qualita` senza chiave esce 0 senza chiamare. Nessuna
  chiamata reale a un LLM dalla sandbox (nessuna chiave qui): il primo uso vero e' l'autoplay in CI col secret.
- Ricerca di chiavi nel repo (pattern `sk-ant-api..-`, `gsk_`, `sk-` lunghi): nessuna.

#### Autoplay in CI (run 42526503915, commit b289818, tutto verde: Windows, test 8 min 36 s, 18 partite)
Secret `ANTHROPIC_API_KEY` presente ⇒ 14 partite a regole + 4 con lo stratega LLM (Claude Haiku via chiave API).

| Metrica | Valore |
|---|---|
| Test di qualita' (30 stati, Haiku, Fantasia 2) | JSON validi al primo colpo **30/30 (100%)**; limitate dai paletti 4/30 (13%); latenza media **3,7 s**; costo totale **$0,027** |
| Chiamate nelle 4 partite LLM | 48/48 risposte valide, **0 ripieghi**, 0 errori |
| Costo per partita (una alleanza LLM, 12 chiamate) | **$0,010-0,014** (in media $0,011) |
| Token per chiamata | ~16.900 (12.385 parte fissa letta dalla cache + ~3.400 stato + ~900 risposta) |
| Durata partita LLM | 273-471 s (a regole 240-370 s) |
| VC R12 Asse / Alleati, A/1 tutto regole (2 semi) | 8 / 3 |
| VC R12, Alleati LLM contro Asse A regole | 8 / 3 (entrambi i semi) |
| VC R12, Asse LLM contro Alleati 1 regole | 8 / 3 e 7 / 4 (media 7,5 / 3,5) |

- Comportamento dell'LLM: Alleati in RALLENTAMENTO 18 round su 24, operazioni anfibie **"attendi" 44 volte su 46**
  (Overlord quasi mai lanciato: nelle motivazioni Haiku legge `perdita_attesa_trasporti_stima` 22-87 TUV come oltre
  il "tetto di 30" e aspetta sempre; con "lancia" lo sbarco avverrebbe comunque solo se sicuro); strumenti di ritardo
  accesi spesso (blocco terrestre 21 round, blocco navale 7, caccia 6, ritirata 4). Asse LLM: OFFENSIVA 23/24, quasi
  nessuno strumento. Nessun cambio di piano. Limitazioni del test di qualita': operazioni non ancora attive al R1
  elencate dall'LLM (ignorate), strumenti chiesti per i Russians dall'Asse (ignorati).
- Giudizio: integrazione affidabile (100% JSON validi, nessun ripiego, costo ~1 centesimo per partita), ma l'effetto
  sul gioco e' nullo o non misurabile con 2 semi (stesse VC delle regole); la prudenza sugli sbarchi e' un difetto
  del prompt (vedi problemi aperti), non della parte deterministica. Non tarato.

### Step LLM-2 — ordini del turno, rischio per nazione, ordine dei turni nei prompt (2026-11-08)

Richiesta di Matteo dopo lo step INTEGRAZIONE LLM: decisione a due livelli (stratega per alleanza + ordini del turno
per nazione), rischio per nazione (schema v1.1), ordine dei turni e finestre di coordinamento nei prompt, tetto 100.
Vedi "Stratega LLM" (punti "Due livelli", "Rischio per nazione", "Prompt stratega v2") e `contratto-v1.md`.
- Classi nuove: `strategia/OrdiniTurno`, `strategia/llm/PromptOrdini`; risorsa `prompt-ordini-turno-v1.md`;
  `prompt-stratega-v1.md` → `prompt-stratega-v2.md`. Modificate: `Direttive` (v1.1, `applicaOrdini`, `testoOrdini`),
  `PianoAttivo` (rischio della nazione, direttive fuse), `Ingresso` (`ordiniTurno`, `salvaIstantanea`, un solo
  calcolatore TUV per ingresso), `Valutazione` (rischio effettivo, istantanea), `StatoMaggiore.aggiornaUltimo`,
  `Strategia.Parametri` (`ampiezza_rischio`, `nazioni_senza_ordini`; file dati), `DecisoreLlm` (catena comune,
  `ordini`), `Uso` (per livello, tetto 100), `UscitaLlm` (rischio per nazione, `controllaOrdini`), `ProPlusAi`
  (aggancio), `DiaryRecorder.nationOrders`, `StrategistPanel`, autoplay `Riepilogo`/`RiepilogoGenerale`/`TestQualita`,
  workflow (secondo test di qualita'). **Nessuna classe Pro toccata.**
- Test nuovi: `OrdiniTurnoTest` (9: fusione solo di bersagli/operazioni/tattiche della nazione con piano e postura
  ignorati, la nazione gioca i suoi ordini e le altre le direttive, rischio v1.1 nel `PianoAttivo`, errore ⇒ direttive
  dello stratega, Francia e nazioni senza PU senza chiamata, regole senza chiamata ma ingresso costruito, "cosa e'
  cambiato" con conquista e stack spostato, v1.1 con nazione/fronte sconosciuti, regole ancora v1, ampiezza 0 dal file
  dati), `StrategaComponentiTest` +4 (rischio v1.1 dalla risposta, prompt degli ordini corto con l'ordine dei turni,
  controllo offline degli ordini, uso per livello e tetto 100), `AutoplayTest` +2 (ordini e rischio medio nel
  riepilogo, test di qualita' degli ordini). Golden Hard, golden Pro+, `HardNeutralityGoldenTest`,
  `DiaryNeutralityTest` verdi.
- Verifica locale (Europe 1940 reale, seme 1): A/1 a regole 2 round 41 s (prima 40 s), file
  `ordini-ingresso-R*-*.json` per ogni nazione (~3.000 caratteri), tabella del rischio nel diario; `--decisore
  Allies=llm` senza chiave 1 round: "ordini del turno non disponibili (nessuna chiave Haiku salvata): valgono le
  direttive dello stratega" per ogni nazione alleata, partita completata.
- Test in locale: `:game-core:test` 3093 (fallito solo `ContentDownloaderIntegrationTest`, rete della sandbox),
  `:game-headed:test` 93, `:proplus-autoplay:test` 18, `:ai:test` 13 verdi. Ricerca di chiavi nel repo: nessuna.

#### Autoplay in CI (run 42531873047, commit bb83094, tutto verde: Windows, test, release, 18 partite)

| Metrica | Valore |
|---|---|
| Test di qualita' Stratega (30 stati, prompt v2) | JSON validi al primo colpo **30/30 (100%)**, limitate dai paletti 8/30 (27%), latenza media 3,9 s, $0,029 |
| Test di qualita' Ordini del turno (30 ingressi) | JSON validi al primo colpo **30/30 (100%)**, limitate 10/30 (33%), latenza media **1,9 s**, $0,012 |
| Chiamate nelle 4 partite LLM | Stratega 48/48, Ordini del turno 110/110 valide, **0 ripieghi** |
| Costo per partita (una alleanza LLM) | **$0,019-0,029** (media $0,023): Stratega $0,012 + Ordini del turno $0,011 |
| Chiamate per partita | Alleati 12 + 31 ordini (Francia esclusa, nazioni senza PU saltate), Asse 12 + 24 |
| Durata partita LLM | 292-512 s (regole 274-366 s) |
| VC R12 Asse / Alleati: A/1 regole | 6,5 / 4,5 (run precedente 8 / 3: varianza) |
| VC R12: Alleati LLM / Asse A regole | 8 / 3 (entrambi i semi) |
| VC R12: Asse LLM / Alleati 1 regole | 8 / 3 e 6 / 5 (media 7 / 4) |
| Sbarchi veri degli Alleati, A/1 regole vs Alleati LLM | 19 e 7 vs 4 e 13 |

- Rischio per nazione: usato poco (Germans 3,03-3,05 contro Italians 3,0; British 2,45-2,47 contro French 2,43-2,47);
  Alleati LLM con DIFENSIVA 4 round su 12 ⇒ rischio medio alleato ~2,45 contro ~3,05 delle regole.
- **Problema confermato**: le operazioni anfibie alleate restano "attendi" in quasi tutti i round, sia nelle direttive
  (44/46) sia negli ordini del turno (43/44 decisioni). Le motivazioni mostrano l'equivoco: Haiku usa "attendi" per
  "aspetta che flotta e scorta siano pronte", ma "attendi" blocca lo sbarco anche a flotta pronta (il motore con
  "lancia" raccoglie e sbarca solo quando trasporti e scorta bastano). Il chiarimento del prompt v2 su "lancia" non e'
  bastato. Proposta (non fatta, da decidere): nei due prompt definire "attendi" come "rinvio deliberato anche se tutto
  e' pronto (finta, attesa di un altro fronte)" e dire esplicitamente che l'attesa della flotta la gestisce gia' il
  motore con "lancia".
- Giudizio: integrazione a due livelli affidabile (100% JSON validi su entrambi i livelli, nessun ripiego), costo
  ~2-3 centesimi per partita con un'alleanza LLM (meno dei 5-10 attesi). Sul gioco l'LLM non migliora le regole con 2
  semi; per gli Alleati probabilmente peggiora (meno sbarchi, piu' DIFENSIVA). Non tarato.

### Step LLM-3 — "attendi" deliberato, dottrina, obiettivi nazionali, 4 semi + Sonnet (2026-11-09)

Richiesta di Matteo: "attendi" come rinvio deliberato con avviso dopo 3 round, dottrina nei due prompt, obiettivi
nazionali propri nell'ingresso, autoplay con 4 semi per le configurazioni LLM + 2 partite con Claude Sonnet 5.5. Vedi
"Stratega LLM" (punti "Prompt v3 / ordini v2" e "Modello alternativo") e "Autoplay in CI".
- Modificate: risorse `prompt-stratega-v2.md` → `v3`, `prompt-ordini-turno-v1.md` → `v2`, `prezzi-stratega.json`
  (Sonnet); `Ingresso` (`round_in_attesa`/`avviso`, `obiettivi_nazionali` in stato e ordini), `ClientApi` (modello
  alternativo, etichetta e prezzo per modello), `DecisoreLlm` (modello dalla proprieta' di sistema), autoplay
  `AutoplayOptions`/`AutoplayRunner` (`llm:MODELLO`), `RiepilogoGenerale` (confronto per configurazione), workflow.
  **Nessuna classe Pro toccata.**
- Test nuovi: `OrdiniTurnoTest` +2 (Overlord UK in attesa 3 round ⇒ avviso; obiettivi nazionali nei due ingressi),
  `StrategaComponentiTest` +2 (prompt v3/v2: rinvio deliberato, dottrina, niente "aspettare la scorta"; modello
  Sonnet chiesto, etichetta "Sonnet", prezzo $2/MTok), `AutoplayTest` (opzione `llm:MODELLO`, tabella di confronto).
  `:game-core:test` 3097 (fallito solo `ContentDownloaderIntegrationTest`, rete), `:game-headed:test`,
  `:proplus-autoplay:test`, `:ai:test` verdi; golden Hard e Pro+ identici.
- Verifica locale (Europe 1940 reale, A/1 regole, 2 round, 32 s): `obiettivi_nazionali` R2 Asse = Germania Control
  Caucasus 5 PU (manca Caucasus), Italia Roman Empire 5 PU (manca 1 tra Gibraltar ed Egypt); Alleati = USA Homeland 20
  e Defense Obligations 5 (tenuti), URSS Berlin 10 (manca Germany), Far East Reserves 9 (tenuto).

#### Autoplay in CI (run 42578913119, commit 5529383, verde al 3° tentativo)
Tentativo 1: `PlanLogisticsTest.theNeutralPlanBuysAsWithoutAStrategyFile` (acquisti di Hard con `Math.random`, instabile
noto); tentativo 2: HTTP 504 di GitHub sul download degli asset (`:game-headed:downloadAssets`); tentativo 3 verde
("re-run failed jobs", stesso commit). 24 partite. **Difetto del riepilogo**: `Riepilogo.parameters` toglieva il modello
dal decisore ("llm:claude-sonnet-5-5" → "llm") e la tabella per configurazione mescolava Haiku e Sonnet; corretto dopo
la run (il modello resta, si toglie solo il percorso dei file). Confronto ricalcolato partita per partita (media dei semi;
operazioni = decisioni di stratega + ordini del turno dell'alleanza LLM, Alleati per le regole):

| Configurazione | Partite | VC R12 Asse / Alleati | Sbarchi veri Asse / Alleati | Lancia / attendi / annulla | Posture (round) | $ per partita |
|---|---|---|---|---|---|---|
| A/1 regole (run 48) | 2 | 6,5 / 4,5 | 5,5 / 13,0 | 100 / 0 / 0 % | RALL 8, CONTR 11, OFF 5 | - |
| A/1 regole (run 49) | 4 | 7,0 / 4,0 | 4,2 / 6,8 | 100 / 0 / 0 % | RALL 18, CONTR 20, OFF 6, DIF 4 | - |
| Alleati Haiku v2 (run 48) | 2 | 8,0 / 3,0 | 7,5 / 8,5 | 2 / 97 / 1 % (90) | OFF 3, RALL 4, CONTR 9, DIF 8 | 0,027 |
| **Alleati Haiku v3** | 4 | 8,0 / 3,0 | 5,5 / 13,8 | 10 / 76 / 14 % (185) | OFF 10, RALL 9, CONTR 25, DIF 4 | 0,028 |
| **Alleati Sonnet v3** | 2 | 7,5 / 3,5 | 7,0 / 4,5 | 74 / 26 / 0 % (72) | OFF 3, RALL 17, CONTR 3, DIF 1 | 0,49 |
| Asse Haiku v2 (run 48) | 2 | 7,0 / 4,0 | 6,0 / 8,0 | - | OFF 21, CONTR 3 | 0,019 |
| **Asse Haiku v3** | 4 | 7,25 / 3,75 | 7,2 / 9,2 | - | OFF 46, CONTR 2 | 0,020 |

- Test di qualita' (Haiku, prompt v3 / ordini v2): stratega 30/30 JSON validi, limitate dai paletti 12/30 (40%), 4,0 s,
  $0,029; ordini 30/30, limitate 10/30, 1,8 s, $0,011. 412 chiamate nelle 10 partite LLM, 0 ripieghi.
- "attendi": Haiku v3 scende da 97% a 76% (+14% "annulla"), resta dominante; Sonnet v3 lancia nel 74% delle decisioni.
  DIFENSIVA: Haiku 8 → 4 round (v2 → v3), Sonnet 1.
- Risultato: nessuna configurazione LLM batte le regole. Alleati Haiku v3 8/3 in tutti e 4 i semi (regole 7/4);
  Sonnet 7,5/3,5 su 2 semi con meno sbarchi alleati (4,5) nonostante "lancia". Costo Sonnet ~17 volte Haiku
  ($0,49 contro $0,028 per partita, 2 livelli). Varianza alta, non tarato.

### Step CONFRONTO CONTRO HARD — Pro+ contro la Hard originale (2026-11-09)

Decisione di Matteo dopo LLM-3: opzione (a), niente altri interventi sui prompt, stratega LLM sperimentale (default
del pannello: Solo regole). Prima di altri sviluppi la misura che conta: Pro+ e' piu' forte della Hard originale? Gli
autoplay precedenti mettevano Pro+ contro Pro+ (risultati dei due lati confusi). **Nessun cambio al comportamento
dell'AI** (nessuna classe Pro o Pro+ di gioco toccata; golden Hard e Pro+ identici).
- **Test instabili resi deterministici** (`PlanLogisticsTest.theNeutralPlanBuysAsWithoutAStrategyFile`,
  `DiaryNeutralityTest`). Causa: `ProPurchaseUtils.randomizePurchaseOption` sceglie con `Math.random` (non
  seminabile) da una `HashMap` con chiavi `ProPurchaseOption` (hash d'identita': anche l'ordine delle opzioni cambia
  tra due esecuzioni). Soluzione, solo nei test: `SeededPurchaseChoice` (test di game-core) ritrasforma una volta per
  JVM `ProPurchaseUtils` con un Advice di Byte Buddy (gia' nel classpath dei test tramite Mockito; `AgentBuilder` con
  `disableClassFormatChanges` + `RETRANSFORMATION`, agente caricato a runtime con `ByteBuddyAgent.install()`): dentro
  `SeededPurchaseChoice.with(seme, ...)` la scelta usa un `Random(seme)` e scorre le opzioni ordinate per regola di
  produzione, con gli stessi pesi dell'originale (efficienza / totale, primo limite cumulato >= numero); fuori da
  `with`, o su un altro thread, gira il metodo originale. I test non sono indeboliti, anzi: `DiaryNeutralityTest`
  confronta SEMPRE gli acquisti (prima solo quando due replay senza diario coincidevano, con fallimento dopo 4
  tentativi) e ora anche il replay completo; `theNeutralPlanBuysAs...` confronta gli acquisti di N e senza file con lo
  stesso seme. Controllo che il gancio sia attivo: `choicesOfLastRun() > 0` nei due test che dipendono dalla scelta
  casuale (con il piano A gli acquisti seguono gli ordini del piano e non fanno scelte casuali: 0). Locale: 6
  ripetizioni consecutive verdi dei due test.
- Autoplay: modalita' `[confronto]` (vedi "Autoplay in CI"), `tuv_perso` nel riepilogo, `ConfrontoHard` nel
  riepilogo generale. File: workflow `proplus-autoplay.yml` (input `modalita`, matrice) e `proplus-release.yml`
  (condizione `[confronto]`), autoplay `Riepilogo`, `RiepilogoGenerale` (5 helper resi package-private),
  `ConfrontoHard` (nuovo), `AutoplayTest` (+1: sezione e verdetto); test game-core `SeededPurchaseChoice` (nuovo),
  `PlanLogisticsTest`, `DiaryNeutralityTest`.
- Test locali (JDK 25 da apt): `:game-core:test` 3097 (fallito solo `ContentDownloaderIntegrationTest`, rete della
  sandbox), `:ai:test` 13, `:game-headed:test` 93, `:proplus-autoplay:test` 19 verdi. Verifica breve (Europe 1940
  reale, 2 round, seme 1): Hard/Hard 38 s, Asse Pro+ A / Alleati Hard 31 s, Asse Hard / Alleati Pro+ 1 40 s, sezione
  "Confronto contro Hard" scritta correttamente.

#### Autoplay in CI (run 42605879121, commit d4109aa, tutto verde al 1° tentativo: test, release, 28 partite)
Modalita' `[confronto]`, 12 round, semi 1-4, rischio Equilibrato; nessuna partita fallita, nessuna vittoria entro R12.
I test resi deterministici sono passati in CI. VC R12 = Asse / Alleati, media (min-max) sui 4 semi; Δ = lato che cambia
contro lo stesso lato in Hard vs Hard; rapporto TUV = perso dal nemico / perso dal lato.

| Configurazione | VC R4 | VC R8 | VC R12 | Reddito fine | Sbarchi veri A / Al | Lato: Δ VC R12 | Rapporto TUV (rif.) | Verdetto |
|---|---|---|---|---|---|---|---|---|
| 1 Hard / Hard | 5 / 6 | 6,25 / 4,75 | 6,25 (6-7) / 4,75 (4-5) | 69 / 92 | 3,5 / 23 | - | Asse 1,27, Alleati 0,80 | riferimento |
| 2 Asse Pro+ A / Hard | 5 / 6 | 5,25 / 5,75 | 3,75 (3-5) / 7,25 | 44 / 121 | 3,25 / 27 | Asse −2,5 (ds 1,0) | 0,96 | **piu' debole** |
| 3 Asse Pro+ D / Hard | 4,75 / 6,25 | 6 / 5 | 5,5 (3-7) / 5,5 | 58 / 109 | 6,75 / 22 | Asse −0,75 (ds 1,7) | 1,19 | non distinguibile |
| 4 Hard / Alleati Pro+ 1 | 5,25 / 5,75 | 7 / 4 | 7,5 / 3,5 (3-5) | 98 / 66 | 6,75 / 12 | Alleati −1,25 (ds 1,0) | 0,73 | **piu' debole** |
| 5 Hard / Alleati Pro+ 2 | 5 / 6 | 6 / 5 | 7 / 4 (3-5) | 86 / 82 | 4,25 / 13 | Alleati −0,75 (ds 0,8) | 0,77 | **piu' debole** |
| 6 Asse Pro+ Haiku / Hard | 5 / 6 | 6,75 / 4,25 | 7 (6-8) / 4 | 77 / 89 | 6,5 / 22 | Asse +0,75 (ds 0,8) | 1,13 | piu' forte (al limite) |
| 7 Hard / Alleati Pro+ Haiku | 5,25 / 5,75 | 6 / 5 | 7 / 4 (4-4) | 89 / 85 | 5 / 15 | Alleati −0,75 (ds 0) | 0,90 | **piu' debole** |

- Risposte (piani insieme): **Asse Pro+ regole (A+D, 8 partite) piu' debole di Hard**: VC R12 4,63 (3-7) contro 6,25
  (6-7), Δ −1,63, rapporto TUV 1,07 contro 1,27, reddito finale −11/−25. **Alleati Pro+ regole (1+2, 8 partite) piu'
  deboli di Hard**: VC 3,75 contro 4,75, Δ −1, rapporto TUV 0,75 contro 0,80, sbarchi veri 12-13 contro 23 della Hard.
  **Asse Pro+ Haiku**: VC 7 contro 6,25 (Δ +0,75, appena oltre la soglia 0,68), ma rapporto TUV peggiore (1,13 contro
  1,27): al massimo pari, non una vittoria netta. **Alleati Pro+ Haiku**: 4 contro 4,75, piu' deboli.
- Indizio forte (osservazione, non verificato con un esperimento): con lo **stesso piano A**, Haiku tiene l'Asse in
  OFFENSIVA 12 round su 12 e chiude a 7 VC; le regole passano a RALLENTAMENTO per 3-6 round su 12 e chiudono a 3,75 (e
  reddito 44 contro 77). La postura RALLENTAMENTO / gli strumenti di ritardo delle regole sembrano costare all'Asse.
  Per gli Alleati il limite principale e' negli sbarchi: la Hard sbarca quasi il doppio di Pro+ (23 contro 12-15).
- Costo LLM: Asse Haiku $0,019-0,024 per partita, Alleati Haiku $0,026-0,028; 0 ripieghi. Durate: Hard/Hard 6-7 min,
  Pro+ 3-8 min, LLM 5-10 min.
- Limiti: 4 semi (le simulazioni dell'AI restano casuali, quindi stesso seme ≠ stessa partita); verdetto = indice
  semplice sulle VC finali, non un test statistico; nessuna vittoria entro R12 in nessuna configurazione.

### Step ABLAZIONE — quali componenti Pro+ costano VC (2026-11-09)

Richiesta di Matteo dopo il confronto contro Hard: misurare, non migliorare. **Nessuna correzione al comportamento di
Pro+, nessuna taratura**: solo interruttori on/off con default invariato (vedi "Ablazione (step ABLAZIONE)") e la
modalita' autoplay `[ablazione]`. Log della run 42605879121 (d4109aa) archiviati prima del push (`autoplay.zip` della
release `autoplay-latest`, sha256 `6cfa12c5...9e89b8a`, 28 partite con `decisioni.jsonl`) e analizzati con script
nella sandbox (non committati).

#### Fase 1a — Asse Pro+ piano A (4 partite contro Hard): il RALLENTAMENTO non e' la causa
- **Chi lo fa scattare**: `Contromosse.statoStrategico`, ramo "corsa pari + piano nemico letto > 50%". La corsa e'
  sempre "pari" perche' la misura degli Alleati (Germany + Southern Italy tenute) vale 0 per tutta la partita ⇒
  "mai"; quella dell'Asse e' "mai" appena la tendenza VC su 3 round e' <= 0. Quindi RALLENTAMENTO = **Asse fermo da 3
  round** con lettura > 50%. Round: seme 1 R2, R6-7, R10-12 (6); seme 2 R10-12 (3); seme 3 R10-12 (3); seme 4 R7-12 (6):
  18 round su 48. Lettura al momento: 0,51-0,80 (piano 2 nei semi 1-2, piano 1 nei semi 3-4). Contro-obiettivi (peso 8):
  Egypt, Malta, 91/92 SZ (piano 2 letto), 104/110 SZ (piano 1 letto).
- **Cosa cambia in concreto** (con le regole RALLENTAMENTO aggiunge SOLO contro-obiettivi: gli strumenti di ritardo sono
  gia' spenti dal decisore a regole, gli acquisti non leggono la postura, solo DIFENSIVA toglie la quota del piano):
  16 attacchi valutati sui contro-obiettivi, 4 eseguiti (Egypt ×2, 110 SZ ×2), tutti vinti, swing reale +66 TUV; 12
  scartati (9 sotto la minima). Attacchi per round: 6,3 in RALLENTAMENTO contro 6,6 in OFFENSIVA, swing reale 20 contro
  20,4 per round. PU avanzati: 3 in 36 turni (OFFENSIVA 4 in 60): nessun risparmio. Acquisti R7-R12 (48 turni):
  stesso mix di Hard (fanteria 76% contro 77%), ma 1.674 PU spesi contro 2.031 perche' il reddito e' piu' basso.
- **Dove l'Asse perde davvero**: TUV netto delle battaglie dell'Asse per partita, R1-4 / R5-8 / R9-12: Hard +155 / +90 /
  +122; Pro+ A +89 / +79 / +75; Haiku (stesso piano A) +165 / +41 / +110. Il divario nasce in **R1-4**, prima di
  qualunque RALLENTAMENTO (semi 2 e 3: reddito gia' in calo da R5-R8 con postura OFFENSIVA). R1 Germania: Pro+ divide
  gli aerei tra 110 SZ e 111 SZ (entrambe flotta britannica) in 3 semi su 4, con 110 SZ a 63-64% in 2 semi; Hard
  concentra tutto su una zona (100%) in 4 su 4, Haiku divide in 1 su 4. Swing R1-2 su 110+111 SZ: Hard +199, Pro+ +51,
  Haiku +129. Cause sospette: obiettivo del piano "flotta British" (peso 10, fase R1-2: ogni zona con navi UK diventa
  obiettivo) + rischio Ovest +1 (Audace: minima 52). Da qui le config 3 e 3b (scelte con Matteo).
- Righe di diario mancanti: nessuna per 1a.

#### Fase 1b — Alleati Pro+ (piano 1 e 2, 4 partite ciascuno, contro Asse Hard): sbarchi bloccati per motivo
Sbarchi del combattimento (ultimo stato per round/nazione/bersaglio), totale sui 4 semi:

| Motivo | Piano 1 | Piano 2 | Hard Alleati (config 1) |
|---|---|---|---|
| eseguiti | 43 | 39 | 81 |
| trasporti dopo lo sbarco (`TrasportiSbarco`, Pro+) | 94 | 108 | - |
| regola trasporti di Hard (`transports_exposed`) | - | - | 51 |
| testa di ponte (`bridgehead_other_target` 20 / `bridgehead_not_held` 4) | 24 | 1 | - |
| scramble (Hard Beta) | 8 | 11 | - |
| strafe anfibio Pro+ | 6 | 7 | - |
| soglie Pro+ (win max >= 65 di Hard ma < minima Pro+) | 1 | 0 | - |
| tetto 30 TUV per alleanza | **0** | **0** | - |
| regole di Hard (sotto la minima, unita' insufficienti, basso valore...) | 309 | 426 | 392 |

Operazioni anfibie bloccate (turni): piano 1 **scorta insufficiente 25** (Overlord USA 23), trasporto vuoto fermo 8,
nessuna rotta 4, bersaglio troppo forte 2; piano 2 scorta insufficiente 30 (Rinforzi Egitto 15, Torch 13, Overlord 2),
nessuna rotta 4. Sbarchi veri (riepilogo): piano 1 British 35 + Americans 12, piano 2 20 + 30, Hard 55 + 38.
- **Tetto 30 TUV: 0 casi per costruzione**: con le regole la perdita accettata delle direttive e' 0 e il tetto si
  applica solo se > 0.
- **Blocco principale: la regola Pro+ dei trasporti dopo lo sbarco** (perdita attesa mediana 7 TUV contro guadagno
  2,1-2,7; bersagli piu' scartati: Denmark, Morocco, Algeria, Norway, Greece, quasi tutti NON obiettivi del piano: 89/94
  e 105/108). Secondo blocco: **soste sicure / scorta** (Overlord USA ferma 23 turni). Testa di ponte terzo (24 casi,
  solo piano 1). Logistica del piano: effetto indiretto, non attribuibile al singolo sbarco dai log.
- **Righe di diario mancanti**: per gli sbarchi scartati da `TrasportiSbarco` il diario non scrive cosa avrebbe deciso la
  regola di Hard (0,75 × swing nemico contro valore dello sbarco): con i numeri di Pro+ si puo' solo dire che Hard li
  terrebbe con certezza in 2 casi su 202; gli altri sono indeterminati. Per contarli servirebbe una riga
  "regola di Hard: 0,75 × X vs Y" accanto a `trasporti_dopo_sbarco`.

#### Fase 2 — interruttori
Vedi "Ablazione (step ABLAZIONE)". Esistevano gia' solo i default "tutto acceso" (nessun interruttore per componente);
gli strumenti di ritardo erano spenti dal decisore a regole ma senza interruttore. **Nessuna classe Pro toccata.**
File: nuovi `ai/proplus/Ablazione`, test `ai/pro/NudoHardTest`, `ai/proplus/strategia/AblazioneTest`, autoplay
`ConfrontoAblazione`; modificati `ProPlusTuning`, `ProPlusAi`, `PianoAttivo`, `Contromosse`, `OperazioniAnfibie`,
`TettoTrasporti`, autoplay `AutoplayOptions`/`AutoplayRunner`/`Riepilogo`/`RiepilogoGenerale`, workflow
`proplus-autoplay.yml` (modalita' ablazione) e `proplus-release.yml` (`[ablazione]`), test `ContromosseTest` +1,
`DelayToolsTest` +1, `AutoplayTest` +2.

#### Fase 3 — autoplay di ablazione (run 42622705022, commit 304a14a, tutto verde al 1° tentativo: test, release, 60 partite)
12 round, Equilibrato, semi 1-6, solo regole, nessuna partita fallita, nessuna vittoria entro R12. VC R12 del lato che
cambia, media (min-max), ds sui 6 semi; Δ Hard = contro lo stesso lato in Hard vs Hard; Δ attuale = contro lo stesso
lato con tutto acceso (stesso piano). Rumore di Hard vs Hard: Asse 6 (4-8) ds 1,4, Alleati 5 (3-7) ds 1,4.

| Configurazione | VC R12 lato | Δ Hard | Δ attuale | Reddito fine lato | TUV perso Asse / Alleati | Sbarchi veri lato | Round RALL. |
|---|---|---|---|---|---|---|---|
| 8 Hard / Hard | A 6 / Al 5 | - | - | A 70 / Al 96 | 816 / 952 | A 4 / Al 26 | - |
| 1 Asse Pro+ A | 4,5 (2-7) ds 1,8 | −1,5 rumore | - | 48 | 1007 / 1069 | 3,3 | 2,7 |
| 2 A senza rallentamento | 5,33 (1-7) ds 2,3 | −0,67 rumore | +0,83 rumore | 60 | 957 / 1051 | 3,2 | 0 |
| 3 A senza rischio_fronte | 6,5 (4-8) ds 1,6 | +0,5 rumore | **+2 (costa)** | 80 | 762 / 940 | 5,2 | 1,7 |
| 3b A senza bersagli_flotta | 5,83 (2-8) ds 2,1 | −0,17 rumore | +1,33 rumore | 68 | 914 / 1018 | 6,7 | 2,2 |
| 4 Asse nudo | 4,5 (3-6) ds 1,2 | **−1,5 "piu' debole"** | 0 | 46 | 948 / 1061 | 3,7 | 0 |
| 5 Alleati Pro+ 1 | 4,33 (3-6) ds 1,2 | −0,67 rumore | - | 79 | 771 / 992 | 11 | 4,7 |
| 6a 1 senza trasporti_sbarco | 4 (3-5) ds 0,6 | −1 rumore | −0,33 rumore | 85 | 879 / 1018 | 24 | 5,2 |
| 6b 1 senza soste_sicure | 3,67 (3-5) ds 0,8 | −1,33 "piu' debole" | −0,67 rumore | 75 | 684 / 941 | 16 | 4,7 |
| 7 Alleati nudo | 4,67 (3-7) ds 1,4 | −0,33 rumore | +0,33 rumore | 97 | 853 / 1122 | 29 | 0 |

Effetto dei componenti (VC acceso − spento), dal piu' dannoso al piu' utile: Asse rischio_fronte **−2,00 costa**;
Asse bersagli_flotta −1,33 rumore; Asse rallentamento −0,83 rumore; Alleati trasporti_sbarco +0,33 rumore; Alleati
soste_sicure +0,67 rumore. Strumenti di ritardo e tetto trasporti non misurati in partita: con le regole sono gia'
spenti / senza effetto (perdita accettata 0).
- **Rischio per fronte**: unico componente che costa oltre il rumore. Senza modificatori (ogni fronte Equilibrato)
  l'Asse con piano A chiude a 6,5 VC (Hard 6) con reddito 80 contro 48 e TUV perso 762 contro 1007. Coerente con la Fase
  1a: con Ovest +1 (Audace, minima 52) la Germania al R1 manda meno unita' su France (80-93% contro 88-100%) e aggiunge
  Normandy (76-81%) in 5 semi su 6.
- **Correzione della Fase 1a**: con 6 semi anche Hard divide gli aerei tra 110 e 111 SZ al R1 (semi 1, 4, 6 di Hard vs
  Hard: 110 al 68-71%), quindi la divisione da sola non e' una differenza di Pro+; la differenza di Pro+ A al R1 e'
  France a desiderata piu' bassa + Normandy (rischio Ovest +1). Il bersaglio "flotta British" spento vale +1,33 VC ma
  dentro il rumore (ds 2,1).
- **RALLENTAMENTO**: spento +0,83, rumore: conferma la Fase 1a (non e' la causa).
- **Alleati**: nessun componente misurato esce dal rumore. Senza `trasporti_sbarco` gli sbarchi veri raddoppiano (24
  contro 11) ma le VC non cambiano (4 contro 4,33) e il TUV perso alleato sale di 26. Senza `soste_sicure` 3,67
  (−0,67 da Pro+ attuale, rumore; −1,33 da Hard vs Hard, appena oltre la soglia).
- **Pro+ nudo Asse segnalato "piu' debole" di Hard** (4,5 contro 6, soglia 1,3 = ds media). Verifiche fatte: (1) codice
  di `ProPlusAi` con `piano` spento: nessuna operazione, rientro, strumento o acquisto Pro+ (PianoAttivo vuoto); (2) nei
  6 diari nudo nessuna riga di comportamento Pro+ (0 righe "tenuto da Pro+", mix del piano, guarnigione, operazioni,
  minaccia reale); (3) attacchi tedeschi del R1 nudo dello stesso tipo di Hard vs Hard (France 92-96%, Southern France,
  Yugoslavia, flotta UK divisa o concentrata, Normandy in 1 seme su 6 come in Hard 2 su 6), diversi da Pro+ A (France
  80-93% + Normandy 5 su 6); (4) `NudoHardTest`: 0 risposte diverse da Hard. Divergenza tardiva (VC R4 4,5 contro 4,33,
  R8 5,33 contro 5,83). Welch: Δ 1,5 su errore 0,75 ≈ 2 errori standard, 1 confronto su 9: compatibile col rumore, ma
  **non escluso**. Alleati nudo: −0,33, rumore. Per chiudere servono piu' semi (es. 12) di nudo e Hard vs Hard.

### Step CONFERMA — nudo = Hard? il rischio per fronte costa? (2026-11-09)

Solo misura (nessun comportamento di Pro+ o Hard toccato): modalita' autoplay `[conferma]` e sezione statistica
`ConfrontoConferma` (vedi "Autoplay in CI"). File: workflow `proplus-autoplay.yml` / `proplus-release.yml`, autoplay
`ConfrontoConferma` (nuovo), `RiepilogoGenerale` (1 riga), `AutoplayTest` +1 (22 test verdi in locale).
- **Codice equivalente per il pooling** (verificato con `git diff d4109aa 304a14a`): tra le due run precedenti cambiano
  solo file `ai/proplus/` dell'ablazione, ogni ramo nuovo scatta solo con un componente spento; nessuna classe `ai/pro`
  toccata; 5330871 cambia solo il modulo autoplay. Quindi "Pro+ A" (confronto + ablazione) e "Hard vs Hard" sono
  sommabili; nudo e senza rischio_fronte esistono solo dall'ablazione.

#### Autoplay in CI (run 42636636143, commit 5330871, tutto verde al 1° tentativo, 48 partite, nessuna fallita)
Lato Asse, VC R12 media ± ds [errore standard]; intervalli al 95% t di Welch.

| Configurazione | Solo semi nuovi (101-112) | Aggregato (con run 42605879121 + 42622705022) |
|---|---|---|
| a Hard vs Hard | 6,00 ± 1,54 [0,44], n 12 | 6,05 ± 1,33 [0,28], n 22 |
| b Pro+ nudo | 6,58 ± 1,00 [0,29], n 12 | 5,89 ± 1,45 [0,34], n 18 |
| c Pro+ A | 6,00 ± 1,21 [0,35], n 12 | 5,18 ± 1,59 [0,34], n 22 |
| d A senza rischio_fronte | 6,50 ± 0,67 [0,19], n 12 | 6,50 ± 1,04 [0,25], n 18 |

| Differenza | Solo nuovi: Δ VC [IC 95%] | Aggregato: Δ VC [IC 95%] | Aggregato: Δ reddito [IC 95%] |
|---|---|---|---|
| b − a (nudo − Hard) | +0,58 [−0,52, +1,69] | −0,16 [−1,06, +0,74] | −4,0 [−16,3, +8,3] |
| c − d (rischio per fronte acceso − spento) | −0,50 [−1,34, +0,34] | **−1,32 [−2,17, −0,47]** | **−18,1 [−30,0, −6,3]** |
| d − a | +0,50 [−0,53, +1,53] | +0,45 [−0,30, +1,21] | +5,7 [−3,8, +15,1] |
| c − a | 0,00 [−1,17, +1,17] | −0,86 [−1,76, +0,03] | **−12,5 [−23,5, −1,4]** |

- **Nudo = Hard: si'.** Il −1,5 dell'ablazione non si ripete: sui semi nuovi nudo e' anzi sopra Hard (+0,58), aggregato
  −0,16 con intervallo ±0,9 attorno allo 0. Nessun indizio di bug negli agganci; non indagato nei diari (l'intervallo
  non esclude lo 0).
- **Rischio per fronte: incerto.** Sui semi nuovi −0,50, intervallo che include lo 0; nell'aggregato −1,32 con intervallo
  che esclude lo 0, ma l'aggregato e' trascinato dai 6 semi dell'ablazione (−2,0), cioe' dalla run che ha fatto nascere
  il sospetto (stima gonfiata dalla selezione). Stima onesta: costo tra 0 e ~1,3 VC, piu' probabile intorno a 0,5. Per
  confermare un costo di 0,5 VC servono ~60 semi per configurazione (ds ~1).
- **Eterogeneita' tra run (dubbio aperto)**: le configurazioni Pro+ Asse cambiano molto tra run con lo stesso codice,
  Hard vs Hard no. Nudo: ablazione 4,5 (semi 1-6) → conferma 6,58 (semi 101-112), circa 3,6 errori standard; Pro+ A:
  confronto 3,75 (semi 1-4) e ablazione 4,5 → conferma 6,0; Hard vs Hard 6,25 / 6,0 / 6,0. Lo stesso seme non ripete la
  partita (seme 1-4 di Pro+ A: 5/4, 3/6, 3/2, 4/4 tra confronto e ablazione). Causa non trovata (semi bassi, carico dei
  runner o caso): finche' non e' chiarita, i confronti validi sono quelli dentro la stessa run con gli stessi semi, e qui
  nessuna differenza esce dal rumore.

### Step VARIANZA — perche' la stessa configurazione cambia tra run (2026-11-09)

Solo misura (nessun comportamento di Pro+ o Hard toccato). File: workflow `proplus-autoplay.yml` / `proplus-release.yml`
(`[varianza]`), autoplay `Tempi` e `ConfrontoVarianza` (nuovi), `AutoplayRunner` (tempo di ogni passo), `RiepilogoGenerale`
(1 riga), `AutoplayTest` +1 (23 verdi in locale). Verifica locale: partita nudo da 1 round con `tempi` nel riepilogo.

#### Analisi statica
- **Tempo: nessuna dipendenza che cambi una decisione.** `AbstractProAi` (149, 178, 193) misura il tempo solo per log e
  diario. `ConcurrentBattleCalculator`: il tempo di copia dei dati (riga 153, `getThreadsToUse` 107-125: 1 thread se la
  copia supera 20 s, altrimenti processori e memoria) decide solo **quanti thread** si dividono le simulazioni; il loro
  numero totale e' fisso (`RunCountDistributor`, riga ~195), quindi cambia solo quale flusso casuale le genera.
  `calculate` aspetta sempre i dati (`waitForGameDataReady`, 92-104); il ramo con 0 risultati (riga ~191) scatta solo
  se i dati non sono impostati, non per tempo. Nessun timeout, `sleep` o limite per mossa nelle classi `ai/pro` e
  `ai/proplus` fuori dal codice LLM. Runner: unico limite `--minuti` 25 (`AutoplayRunner` ~137), mai scattato (tutte le
  partite delle 4 run "completata", 12 round).
- **Seme**: `AutoplayRunner.seedDice` (267-290) sostituisce solo la sorgente dei **dadi di gioco** con
  `new Random(seme).nextInt(max)`, uguale in [confronto], [ablazione], [conferma], [varianza]. Non coperti: simulazioni
  di battaglia dell'AI (calcolatore non seminato, un flusso per thread), `Math.random` negli acquisti
  (`ProPurchaseUtils.randomizePurchaseOption`) e nella politica di Hard, ordine di `HashMap`/`HashSet` con hash
  d'identita' e UUID casuali delle unita'. Verificato: i semi piccoli e consecutivi non danno dadi simili (`nextInt(6)`
  dei semi 1-6 e 101-112 diversi dal primo tiro; solo il primo `nextDouble` e' quasi uguale, non usato).
- **Percorsi solo-Pro+ fuori dal tuning**: nessuno nelle classi `ai/pro` (`reportRealThreatDefense` dietro
  `realEnemies`, vuoto con nudo; nessun controllo `instanceof ProPlusTuning`); `ProPlusAi` con `piano` spento non chiama
  operazioni, rientro, strumenti, caccia, acquisti delle operazioni, stato maggiore. Tutti i metodi di `ProPlusTuning`
  senza piano tornano al valore di Hard (anche `warDecision`, `minimumGarrisons`, `attackPriorityPlanBonus`,
  `dropsLandAttack`, `heldUnits`).
- **Ambiente delle run passate** (API dei job): tutte `ubuntu-latest`, max-parallel 6, matrice **a blocchi per
  configurazione** (es. ablazione: Hard vs Hard per ultima, 13:14-14:19); la run [confronto] aveva in coda le partite LLM.
  Durate medie per configurazione simili (Pro+ 312-397 s, Hard vs Hard 354-402 s), correlazione durata/VC dell'Asse Pro+
  nelle 3 run −0,15. Modello di CPU dei job passati non ricavabile (log non pubblici).

#### Autoplay in CI (run 42650606455, commit 499df34, tutto verde al 1° tentativo, 30 partite)

| Gruppo | n | VC R12 Asse ± ds | IC 95% | Durata (s) |
|---|---|---|---|---|
| Hard vs Hard, semi 1-6 | 6 | 6,17 ± 0,98 | 5,14-7,20 | 277 |
| Hard vs Hard, semi 101-106 | 6 | 5,33 ± 1,37 | 3,90-6,77 | 349 |
| nudo, semi 1-6 | 6 | 4,67 ± 2,42 | 2,13-7,21 | 344 |
| nudo, semi 1-6, replica | 6 | 5,67 ± 1,21 | 4,40-6,94 | 327 |
| nudo, semi 101-106 | 6 | 4,50 ± 2,07 | 2,33-6,67 | 307 |

- Effetto dei semi (101-106 − 1-6): Hard −0,83 [−2,39, +0,72], nudo −0,17 [−3,08, +2,74]: **nessuno**. Stesso seme
  giocato due volte: differenza media 2,33 VC, r −0,05 (IC −0,83/+0,80): il seme dei dadi **non fissa l'esito**.
- Replica (stessi semi, stessa run): +1,00 [−1,59, +3,59]. nudo − Hard: −1,50 [−4,05, +1,05] (semi 1-6), −0,83
  [−3,14, +1,47] (101-106).
- Durata e VC: r −0,54 (nudo), −0,46 (tutte); con il tempo medio del turno alleato r −0,63, con quello dell'Asse −0,44:
  piu' compatibile con causa inversa (Asse in difficolta' ⇒ Alleati con piu' unita' e calcoli piu' lunghi) che con un
  limite di tempo, che nel codice non c'e'. Macchine: 6 modelli di CPU (EPYC 7763/9V74/9V45, Xeon 8370C/8573C/6973P),
  4 processori ciascuna; durata media per CPU 207-431 s. Nudo sulle CPU vecchie (7763, 8370C) 3,71 VC (7 partite) contro
  5,73 sulle nuove (11); Hard vs Hard 5,67 contro 5,78: segnale solo su nudo, campione piccolo, nessun meccanismo nel
  codice.
- **Tutte le run insieme** (Asse, VC R12): Hard vs Hard 34 partite 5,94 ± 1,28, cinque gruppi **omogenei** (test di
  permutazione p 0,79). Nudo 36 partite 5,42 ± 1,75, cinque gruppi **eterogenei** (p 0,03) e piu' dispersi di Hard
  (p 0,047). nudo − Hard: −0,52 [−1,25, +0,20]. Scelte della Germania al R1 uguali (34 contro 36 partite: France e
  Yugoslavia 100/100%, Southern France 91/94%, 111 SZ 74/75%, 110 SZ 65/72%, Normandy 41/42%, Baltic States 32/17% =
  guerra casuale di Hard al R1).
- **Conclusione**: esclusi l'effetto semi (misurato) e un limite di tempo (codice). Le differenze tra run sono in gran
  parte **caso**: un esito di una partita ha ds ~1,3-1,8 VC e gruppi da 4-6 semi oscillano di ±1,5 VC. Resta un dubbio
  non spiegato: nudo e' piu' variabile di Hard ed eterogeneo tra gruppi (p 0,03-0,05, con piu' test fatti) e piu'
  debole sulle CPU vecchie, pur avendo le stesse risposte del tuning e le stesse scelte al R1. Ipotesi aperta: una
  differenza nei round successivi non coperta da `NudoHardTest` (che controlla un solo turno), oppure ancora il caso.
- **Indicazione pratica**: confrontare configurazioni solo **nella stessa run**, con la matrice mescolata, mai con
  riferimenti di run diverse; repliche dello stesso seme non servono (il seme non fissa la partita). Semi per
  configurazione per vedere una differenza vera con un test al 5% e potenza 80% (ds 1,5): 1 VC ⇒ ~35; 0,5 VC ⇒ ~140.

### Step PROVA DI VALORE — due operazioni congiunte scritte a mano (2026-11-09)

Prova di valore del coordinamento d'alleanza prima della versione generale (OC-1): Egitto (GER apre con gli aerei, ITA chiude)
e Francia (USA sbarca per primi in Normandy Bordeaux, UK completa), sopra Pro+ nudo, accese solo con l'interruttore. Vedi
"Operazioni congiunte (step PROVA DI VALORE)" (verifiche di regola, dati, meccanica, contatore) e la modalita' `[prova]`.
File: `ai/proplus/OperazioneCongiunta`, `OperazioniCongiunte`, `ControlloTuning` (nuovi), `ProPlusTuning` (risposte
dell'operazione marcate, `unwrap`, `holdForOperation`, `holdCombat`, `combatHeldUnits`), `ProPlusAi` (aggancio in acquisto,
combattimento, non combattimento; contatore salvato), `DiaryRecorder.jointOperation`, `OperazioniAnfibie` (solo `unwrap`);
`ai/pro/ProTuning.combatHeldUnits` + `ProMyMoveOptions.removeUnits` + 1 riga in `ProCombatMoveAi` (vuoti per Hard); risorsa
`proplus/strategia/operazioni-congiunte.json`; autoplay `--operazione`, `Monitor`/`Riepilogo` (stato, contatore, bersagli),
`ConfrontoProva` (nuovo), `RiepilogoGenerale` (etichetta + sezione); workflow `[prova]` (140 partite, max-parallel 20).
- Test locali (JDK 25 da apt): `:game-core:test --tests 'games.strategy.triplea.ai.*'` + `:proplus-autoplay:test` +
  `HeadedPlayerTypesTest`: 512 test, 0 falliti (golden di Hard `HardPriorityGoldenTest`, `HardNeutralityGoldenTest`,
  `ProPlusGoldenTest`, `NudoHardTest`, diario on/off `DiaryNeutralityTest` compresi); nuovo `OperazioniCongiunteTest` 6 verdi.
  `spotlessApply` eseguito solo sui file toccati (le altre riformattazioni annullate).
- Prove locali (sandbox, partite brevi, non statistiche): Egitto seme 205/206 (obiettivo R2 con lo sbarco italiano in
  fattibilita': tappa 1 aerea tedesca, perdita 10 TUV, Italia prende e tiene l'Egitto 2 round); semi 201-203 con la versione
  senza la via anfibia: obiettivo R3 e **Hard Italia prende l'Egitto da sola al R1-R2** ⇒ operazione "superata" (da qui l'attesa
  e la fattibilita' via mare). Francia semi 204, 207, 209: completata al R6 (USA prendono Normandy da soli; UK rinforzo), al R8
  dopo 2 rinvii (sottomarini in 110 al R6, valore atteso negativo al R7), al R6 con lo sbarco UK delle mosse dell'operazione.
  **Contatore nudo: 0 differenze** su ~64.000 domande (2 partite complete con operazione, Asse e Alleati) fuori dalle risposte
  dell'operazione.

#### Autoplay in CI (run 42673273717, commit 428885d, tutto verde al 1° tentativo: test, release, 140 partite, nessuna fallita)
`[prova]`, 12 round, Equilibrato, semi 201-235, matrice mescolata, 4 configurazioni × 35. VC R12 del lato Pro+, media ± ds
[IC 95%]; differenze con IC di Welch.

| Configurazione | VC R12 del lato | Reddito finale | TUV perso | Contatore nudo (domande; risposte dell'operazione) |
|---|---|---|---|---|
| Asse nudo | 5,54 ± 1,17 [5,14, 5,95] | 60 | 894 | **0** (1.637.197; 0) |
| Asse nudo + Egitto | 5,17 ± 1,64 [4,61, 5,73] | 56 | 880 | **0** (1.673.795; 268) |
| Alleati nudo | 5,97 ± 1,65 [5,40, 6,54] | 110 | 1045 | **0** (2.260.982; 0) |
| Alleati nudo + Francia | 5,31 ± 1,57 [4,78, 5,85] | 103 | 1090 | **0** (2.205.059; 749) |

- **Effetto**: Egitto −0,37 VC [−1,05, +0,31], Francia −0,66 VC [−1,43, +0,11]: **non vale** per entrambe (nessun effetto
  positivo; per vedere differenze di questa grandezza servirebbero 95-231 semi). Contatore nudo **0** in tutte le 140 partite.
- **Egitto** (35 partite): round obiettivo R2 in tutte. Tappa 1 tedesca con attacco vero 19; tappa 1 "non necessaria"
  (Egitto senza difensori al R2) 13; bersaglio gia' dell'Asse senza tappa 1 (attesa/superata) 2; annullata senza tappa 1 1.
  Criterio 70%: 32/35 (91%) contando le tappe "non necessarie"; solo sugli attacchi veri 19/20 (95%) delle partite con
  difensori al round della tappa. Con attacco vero: Egitto preso nello stesso round 16/19. Tappa 2 approvata 31, attacco
  fatto 30. TUV perso medio: tappa 1 5,2, tappa 2 1,1. Preso 26/35, tenuto 1 round 26/26, 2 round 21/26.
  Egypt dell'Asse (nudo / nudo+Egitto): R3 40% / **80%**, R6 17% / 49%, R12 14% / 9%; prima presa 21/35 (R medio 3,2) /
  34/35 (R medio 3,1). L'operazione anticipa e allunga il possesso del Cairo ma il Regno Unito lo riprende entro R12, e le
  VC finali non migliorano. VC R12 dell'Asse con tappa 1 eseguita 5,00 ± 1,67 (19) contro 5,38 ± 1,63 senza (16): nessuna
  differenza utile (gruppi scelti dal gioco, non a caso).
- **Francia** (35 partite): round obiettivo R6 in tutte. Tappa 1 eseguita 32/35, completata 30, annullata 5, partite con
  rinvii 20 (33 rinvii; motivi: perdita attesa > tetto 11, nessuna unita' USA 6, valore atteso ≤ 0 6). Normandy presa 27/35
  (14 volte dagli USA da soli), tenuta 1 round 18/27, 2 round 12/27. Normandy alleata (nudo / nudo+Francia): R8 51% / 57%,
  R12 51% / 49%. Parigi riconquistata dagli Alleati: 12/35 partite (12 volte) / 9/35 (9 volte). TUV perso alleato +45
  [−11, +102].
- Giudizio: la meccanica funziona (operazioni avviate oltre il 90%, tappe tracciate, tappa 2 eseguita o rinviata secondo la
  condizione, contatore a 0), ma il coordinamento forzato **non vale** 1 VC: Egitto e Normandia sono presi prima o piu'
  spesso e poi persi, e il costo (aerei e truppe consumati, TUV perso Alleati in piu') annulla il guadagno.


### Step PROVA2 — variante v2 "presa e tenuta" delle operazioni congiunte (2026-11-09)

Dopo la prova di valore (si prende ma non si tiene) le due operazioni passano alla variante v2: lancio solo con valore d'alleanza
positivo contando il contrattacco atteso (unita' in portata + acquisti possibili alle fabbriche vicine), stato TENUTA con presidio
bloccato, rinforzi, riserve e copertura aerea, fine COMPLETATA dopo 2 round o FALLITA; Francia con Normandy testa di ponte e tappa
su Parigi. Correzione del bug "Hard britannico non vede lo sbarco" (causa nell'operazione: posizionamento nel round di un rinvio).
Vedi "Variante v2: presa e tenuta (step PROVA2)" e la modalita' `[prova2]`.
File: `ai/proplus/OperazioneCongiunta` (`Tenuta`, stati TENUTA/FALLITA, `bersaglio_finale`), `OperazioniCongiunte` (contrattacco,
`valoreAlleanza`, `minacciaPer`, tenuta, tappa finale con prefisso `finale_`, fix del posizionamento), `operazioni-congiunte.json`
(versione 2); autoplay `Monitor` (`territori_operazioni`), `ConfrontoTenuta` (nuovo), `ConfrontoProva` (chiama la sezione);
workflow `[prova2]` (semi 301-335). Nessun hook nuovo nelle classi Pro, Hard invariata.
- Test locali (JDK 25 da apt, Maven Central dietro mirror Google in `~/.gradle/init.d`, solo sandbox): `:game-core:test --tests
  'games.strategy.triplea.ai.*'` + `:proplus-autoplay:test` + `HeadedPlayerTypesTest`: **517 test, 0 falliti** (golden di Hard,
  `NudoHardTest`, `DiaryNeutralityTest` diario on/off, contatore nudo a 0 su un turno compresi). `OperazioniCongiunteTest` 11 verdi, 5
  nuovi: lancio con contrattacco (`valoreAlleanza` + contrattacco su Normandy con acquisti tedeschi), presidio bloccato, rinforzi verso il
  bersaglio + riserve negli acquisti, fine tenuta COMPLETATA/FALLITA, testa di ponte e tappa su Parigi.
- Prove locali (non statistiche, 12 o meno round): Egitto semi 201, 205, 206: lancio rinviato per contrattacco atteso (tenuta stimata
  0% al R2: 1 corazzato, 2-4 fanterie, artiglieria, caccia e bombardiere tattico britannici in portata contro un solo superstite di
  terra), 2 volte Egitto preso da Hard al R3 ⇒ TENUTA, rinforzi tedeschi aerei (tenuta 0% → 7%), perso al R4 (stime 0-7%, reale 0:
  coerente). Francia semi 204, 209: rinviata/annullata per contrattacco (14 fanterie + 8 artiglierie tedesche in portata + acquisti a
  Parigi) o perdita > tetto. Seme 207 (commit 65ebfd7) per la riproduzione del bug D.
- Correzioni durante le prove: presidio stimato vuoto (la "corsa media" del calcolatore tiene solo aerei) ⇒ almeno l'unita' di terra
  piu' forte; rinforzi mandati in un bersaglio perso (tenuta 0%) ⇒ nessun rinforzo se anche con tutte le unita' in portata la stima
  resta ≤ `soglia_ritirata`.

#### Autoplay in CI (run 42691097392, commit f6458c0, tutto verde al 1° tentativo: test, release, 140 partite, nessuna fallita)
`[prova2]`, 12 round, Equilibrato, semi 301-335 (nuovi), matrice mescolata, 4 configurazioni × 35. VC R12 del lato Pro+, media ± ds
[IC 95%]; differenze con IC di Welch; proporzioni con IC 95% normale.

| Configurazione | VC R12 del lato | Reddito finale | TUV perso del lato | Contatore nudo (domande; risposte dell'operazione) |
|---|---|---|---|---|
| Asse nudo | 5,69 ± 1,35 [5,22, 6,15] | 63 | 867 | **0** (1.617.799; 0) |
| Asse nudo + Egitto v2 | 5,46 ± 1,65 [4,89, 6,02] | 60 | 904 | **0** (1.686.102; 349) |
| Alleati nudo | 5,43 ± 1,80 [4,81, 6,05] | 104 | 1044 | **0** (2.319.955; 0) |
| Alleati nudo + Francia v2 | 5,29 ± 1,49 [4,78, 5,80] | 101 | 1068 | **0** (2.239.005; 896) |

- **Effetto in VC**: Egitto v2 −0,23 [−0,95, +0,49], Francia v2 −0,14 [−0,93, +0,65]: **non vale** per entrambe (servirebbero 682 e
  2101 semi per vedere differenze di questa grandezza). Contatore nudo **0** su 7,9 milioni di risposte.
- **Egitto**: Egypt dell'Asse (nudo / v2) R3 34% / 60%, R6 20% / 46%, R9 9% / 17%, **R12 9% / 9%** (Δ 0 punti [−13, +13]). Tappa 1
  eseguita solo 4/35; rinvii 52 (35 partite), 17 tappe 1 respinte esplicitamente per contrattacco atteso + 41 per valore d'alleanza ≤ 0;
  annullate 8. Tenuta iniziata 27/35, di cui **23 su Egitto gia' preso da Hard**; COMPLETATA 7, FALLITA 20 (tenuto in media 0,5 round).
  Presidio lasciato a Hard 12 volte. TUV perso Asse +37 [−23, +97] (aerei di copertura persi), Alleati −9.
- **Francia**: Normandy alleata (nudo / v2) R8 40% / 31%, R10 46% / 46%, **R12 46% / 43%** (Δ −3 [−26, +20]); Parigi R8 6% / 3%, R10
  9% / 17%, **R12 9% / 9%** (Δ 0 [−13, +13]). Tappa 1 su Normandy eseguita 8/35 (tutte con tappa 2), annullate 22 (contrattacco atteso 9,
  perdita > tetto 5, valore ≤ 0 5, round passato 3); tenuta iniziata 13 (5 senza operazione), testa di ponte tenuta 2 round 6/35, tappe su
  Parigi valutate 11 / tappa 1 eseguita **0** (perdita attesa > tetto 8 volte, il resto valore ≤ 0); Parigi presa 1 (senza operazione),
  tenuta fino a R12. Presidio lasciato a Hard 23 volte. TUV perso Alleati +24 [−43, +91].
- **Calibrazione** (la stima e' affidabile?): al lancio 12 stime (10 nella fascia 80-100%: stimata 94%, reale 80%; 2 a 40-59%: 56% /
  50%): poche ma ragionevoli. **Durante la tenuta la stima e' molto pessimista nelle fasce basse**: 0-19% → stimata 1%, reale **52%** (77
  stime); 20-39% → 27% / 64%; 40-59% → 48% / 100%; 60-79% → 73% / 100%; 80-100% → 94% / 97%.
- **Perche' non vale (dal diario e dai numeri)**: (1) il contrattacco e' **sovrastimato**: la stima somma TUTTE le unita' nemiche che
  possono raggiungere il bersaglio (il massimo di Hard, che non le manda mai tutte su un solo territorio) piu' meta' dei PU nemici in
  acquisti, quindi una tenuta stimata ~1% in realta' tiene la meta' delle volte; (2) di conseguenza i lanci sono quasi tutti bloccati
  (Egitto 4/35, Parigi 0/11) e la v2 lavora soprattutto su bersagli gia' presi da Hard; (3) la soglia di ritirata scatta su stime sbagliate
  (35 presidi lasciati a Hard, "rinforzi inutili") e quando i rinforzi partono costano unita' (Asse +37 TUV): il possesso intermedio
  migliora (Egitto R6 46% contro 20%) ma non arriva a R12. Non e' un problema di taratura delle soglie: la grandezza stimata (contrattacco
  massimo) non e' quella che decide l'esito (contrattacco che il nemico fa davvero).
- **Giudizio**: con la regola decisa prima della run ("se anche v2 non supera il rumore, la linea coordinamento si chiude") la linea delle
  operazioni congiunte scritte a mano **si chiude**: due varianti, 280 partite, nessun effetto distinguibile da 0 in VC, entrambi gli IC
  sotto +1. Le operazioni restano nel codice, spente di default.

### Step PROVALLM — operazioni v2 decise da un LLM nei punti di decisione (2026-11-09)

Dopo [prova] e [prova2] (nessun effetto in VC; causa misurata: la formula non prevede la risposta REALE del nemico) un LLM (Claude Sonnet
5.5) decide lancia/rinvia/procedi/tieni/ritira/annulla nei punti dove decideva la formula v2 di Egitto e Francia (tappe su Parigi
comprese), leggendo i numeri del motore; tutto il resto resta del motore. Vedi "Operazioni decise da LLM (step PROVALLM)" e la modalita'
`[provallm]`.
File: `ai/proplus/DecisoreOperazione`, `StoricoNemico`, `strategia/DecisioneOperazione`, `strategia/llm/PromptOperazione` (nuovi);
`OperazioniCongiunte` (punti di decisione, stato per l'LLM, storico, esito reale, `enemyPurchases` anche per fabbrica, `reinforceHold`
diviso in candidati + esecuzione senza cambiarne il comportamento); `DiaryRecorder.operationInput`; risorse `prompt-operazione-v1.md`,
`prezzi-stratega.json` (cache letta Sonnet 0,10); `proplus/strategia/contratto-v1.md` (schema v1.2); autoplay `--operazione-decisore`,
`--budget-operazioni`, `Monitor`/`Riepilogo` (uso e parametri), `ConfrontoProva` (etichetta LLM), `ConfrontoLlm` (nuovo); workflow
`[provallm]`. Nessun hook nuovo nelle classi Pro, Hard invariata; con l'interruttore spento nessuna chiamata e nessun calcolo in piu'.
- **Run [prova] 42673273717 "failure"**: nessun job fallito (143 riusciti, `powershell-tests` saltato, 140 partite verdi, release e
  autoplay validi). Manca il job `changes` (primo job di `proplus-release.yml`): non compare tra i job ne' tra i check ne' nel consumo ⇒
  errore lato GitHub nella creazione/avvio del job (nessun log disponibile dall'API; il messaggio e' solo nelle "Annotations" della
  pagina della run, bloccata dal proxy della sandbox). La run [prova2] 42691097392 ha `changes` verde ed e' "success".
- Test locali (JDK 25 da apt, mirror Google di Maven Central in `~/.gradle/init.d`, solo sandbox): `:game-core:test` 3127 (fallito solo
  `ContentDownloaderIntegrationTest`, rete della sandbox; `DiaryNeutralityTest` fallito 1 volta nella suite in parallelo sul controllo
  "replay senza diario", poi 2/2 verde da solo: instabilita' gia' nota, codice dell'operazione spento in quel test), `:ai:test` 13,
  `:game-headed:test` 93, `:proplus-autoplay:test` 23 verdi; golden Hard, golden Pro+, `NudoHardTest`, `OperazioniLlmTest` 6/6. Partita
  locale 4 round con LLM acceso e senza chiave: 4 decisioni registrate, tutte ripiego "LLM non disponibile", partita completata.

#### Autoplay in CI (run 42712911200, commit 36b9b28, tutto verde al 1° tentativo: test, release, 140 partite, nessuna fallita)
`[provallm]`, 12 round, Equilibrato, semi 401-435 (nuovi), matrice mescolata. VC R12 del lato Pro+, media ± ds [IC 95%].

| Configurazione | VC R12 del lato | Reddito finale | TUV perso del lato | Contatore nudo |
|---|---|---|---|---|
| Asse nudo | 5,17 ± 1,69 [4,59, 5,75] | 57 | 883 | **0** (1.649.313) |
| Asse nudo + Egitto decisa da LLM | 5,40 ± 1,88 [4,75, 6,05] | 60 | 871 | **0** (1.680.992; 280 dell'operazione) |
| Alleati nudo | 5,57 ± 1,65 [5,00, 6,14] | 108 | 1101 | **0** (2.305.064) |
| Alleati nudo + Francia decisa da LLM | 5,20 ± 1,41 [4,72, 5,68] | 102 | 1043 | **0** (2.207.438; 779) |

- **Effetto in VC**: Egitto LLM **+0,23 [−0,62, +1,08] "promettente"** (IC che arriva a +1, servirebbero ~960 semi per 0,23); Francia LLM
  **−0,37 [−1,10, +0,36] "non vale"**. Nessuno dei due "vale".
- **Decisioni** (239 chiamate, **0 ripieghi**, 0 errori, 0 risposte non valide): Egitto tappa 1 lancia 17 / rinvia 14 / annulla 13 (la
  formula avrebbe rinviato tutte e 44), tappa 2 procedi 16, tenuta tieni 58 / ritira 1 (formula ritira 18); Francia tappa 1 lancia 29 /
  rinvia 8 / annulla 4 (formula: rinvia 40 su 41), tappa 2 procedi 18 / rinvia 1 / annulla 2, tenuta tieni 47 / ritira 10 (formula ritira
  49). Diverse dalla formula: 70% delle tappe 1, 0-10% delle tappe 2, 29-68% delle tenute.
- **Calibrazione** (194 decisioni eseguite con esito; reale = bersaglio dell'alleanza al turno di chi chiude nel round dopo): errore medio
  per fascia **LLM 9,5 punti contro formula 48,2**, Brier 0,222 contro 0,449 ⇒ **l'LLM stima la tenuta meglio della formula: si'**. Per
  operazione: Egitto lancio stimato 0,56 / reale 0,76, tappa 2 0,83 / 0,81, tieni 0,73 / 0,76 (ben calibrato); **Francia lancio 0,67 /
  reale 0,31, tappa 2 0,62 / 0,22** (troppo ottimista), tieni 0,66 / 0,58. Nota: il prompt riportava le fasce misurate della formula in
  prova2 (fatto, non taratura): parte del vantaggio sulla formula viene da li'.
- **Costo**: $1,75 per tutta la run (tetto $40), $0,025 a partita (max $0,10), 3,4 chiamate a partita, 2,8 s per chiamata; token ingresso
  601K + cache letta 842K, uscita 44K. Il budget per partita ($0,57) non e' mai stato toccato.
- **Egitto**: Egypt dell'Asse (nudo / LLM) R3 34% / 74%, R6 17% / 51%, R9 3% / 26%, **R12 6% / 11%** (+6 punti [−7, +19]); preso 21/35,
  tenuta COMPLETATA 14/35, FALLITA 7. Decisioni giuste (calibrate, lanci con tenuta reale 76%), possesso molto piu' lungo, ma dopo la
  COMPLETATA le unita' tornano a Hard e il Regno Unito riprende il Cairo: l'effetto in VC a R12 resta piccolo. TUV perso Asse −13.
- **Francia**: Normandy alleata (nudo / LLM) R10 57% / 37%, **R12 51% / 40%** (−11 [−35, +12]); France R12 17% / 6%. Normandy presa 21/35 ma
  tenuta COMPLETATA **0/35**, FALLITA 20 (tenuta media 1,5 round), testa di ponte 2 round 3/35, tappe su Parigi valutate 4 / eseguite 0.
  **Perche' non vale**: (1) **stima dell'LLM sbagliata sul lancio** (0,67 contro 0,31): la Germania contrattacca Normandy davvero, a
  differenza di quanto suggerisce lo storico del nemico in altri territori; (2) l'esecuzione della tappa 2 (sbarco UK) lascia presidi che
  non reggono (procedi 18, tenuto 22%); (3) i lanci tolgono unita' all'offensiva normale di Hard (Normandy e Parigi meno alleate a R12 che
  in nudo). Le perdite in TUV non sono la causa: TUV perso Alleati −58 [−108, −9], Asse −83 [−141, −26] (scambi piu' pari).

### Step STRATEGA LLM — stratega LLM completo e giocabile (2026-11-10)

Voce giocatore "Pro+ Stratega LLM (Europe 1940)": Pro+ nudo (= Hard) + stratega LLM (Claude Sonnet 5.5, effort low) che sceglie a
inizio round il portafoglio di operazioni congiunte GENERALI (offensiva, sbarco, tenuta, rinforzo all'alleato) dai candidati calcolati
dal motore, e decide procedi/rinvia/tieni/ritira nei punti di decisione; il motore le esegue con la meccanica v2 generalizzata
(definizioni dinamiche); senza chiave/errore/tetto gioca come Hard con un avviso. Vedi "Stratega LLM completo (step STRATEGA LLM)"
e la modalita' `[smoke]`.
File: nuovi `ai/proplus/ProPlusStrategaAi`, `Stratega`, `StrategaCandidati`, `StrategaConfig`, `StrategaDecisione`, `StrategaDiario`,
`StrategaFuoriLista`, `StrategaSimulato`, `strategia/llm/PromptStrategaV4`, `PromptOrdiniTurnoV3`, risorse `stratega-llm.json`,
`prompt-stratega-v4.md`, `prompt-ordini-turno-v3.md`; modificati `OperazioneCongiunta` (operazioni dinamiche, `tutte`),
`OperazioniCongiunte` (punti d'ingresso su tutte le operazioni, `valutaTenuta`, paracadute Hard per le dinamiche, `germanPresence`
solo per le scritte a mano), `DecisoreOperazione` (prompt v3 per le dinamiche, tetto $ comune ai due livelli, uso per chiave,
trasporto simulato), `ProPlusAi` (operazioni in sequenza), `ProPlusTuning` (rinforzi dello stratega come ammassamento),
`DiaryRecorder.stratega`, `ProfiloStratega` (tetto $), `HeadedPlayerTypes` (+ test), `StrategistPanel` (campo "Tetto Stratega LLM");
autoplay `--tipo stratega`, `Monitor`/`Riepilogo` (`stratega_llm`), `ConfrontoSmoke` (nuovo), `RiepilogoGenerale`; workflow `[smoke]`;
zip con `LEGGIMI-TEST.txt` (+ sezione in `LEGGIMI.txt`); `contratto-v1.md` (schema 1.3). `avvia.bat` e `aggiorna.ps1` NON toccati.
Nessun hook nuovo nelle classi Pro: Hard invariata.
- Test locali (JDK 25 da apt, mirror Google di Maven Central in `~/.gradle/init.d`, solo sandbox): `:game-core:test --tests
  'games.strategy.triplea.ai.*'` + `:ai:test` + `:game-headed:test` + `:proplus-autoplay:test`: **638 test, 0 falliti** (golden Hard
  `HardPriorityGoldenTest`/`HardNeutralityGoldenTest`, `ProPlusGoldenTest`, `NudoHardTest`, `DiaryNeutralityTest` diario on/off,
  `OperazioniCongiunteTest`, `OperazioniLlmTest` compresi). Nuovi: `StrategaLlmCompletoTest` 13 (registrazione nudo + LLM + tetto dal
  profilo; nessuna chiave = nessuna operazione e un solo avviso; errore / risposta non valida / tetto $ = nessuna chiamata o nessuna
  operazione; ripiego dei punti di decisione = Hard; candidati <= 8 con numeri e definizioni valide; portafoglio entro max 3 con
  rifiuti motivati, fuori lista rifiutata, candidato/operazione sconosciuti; schema strict; tenuta: presidio bloccato, rinforzi,
  riserve, COMPLETATA; rinforzo: unita' di un'altra nazione, FALLITA se perso; offensiva: riserve <= 30%, raduno trattenuto, tappa 1
  valutata, annullata = unita' a Hard; sbarco: zona, raduno, trasporti nelle riserve, bersaglio successivo; nessuna operazione ne'
  chiamata per gli altri giocatori), `HeadedPlayerTypesTest` (voce elencata dopo "Pro+ (AI)" e istanziabile).
- Prove locali (non statistiche): partita 4 round Asse Stratega simulato (offensive France/Egypt, tenuta Germany: Egypt preso dall'Italia
  con tappa 2 approvata, poi FALLITA; France e Germany COMPLETATE; rifiuti "gia' 3 operazioni attive"); partita 2 round Alleati senza
  chiave (nessuna chiamata, nessun candidato calcolato, "nessuna decisione ... LLM non disponibile", gioca come Hard).
  Correzioni dalle prove: tenuta proposta su minacce di soli aerei (ora servono nemici di terra); offensiva aerea senza raccolta aerei
  (ora `aerei_apre` + raccolta entro 2).

#### Autoplay in CI (run 42775993327, commit 805c684, tutto verde al 1° tentativo: test, release `proplus-latest`, 12 partite [smoke])
`[smoke]`, 12 round, Equilibrato, semi 501-506, Stratega LLM (Sonnet 5.5 vero, secret) contro Hard. **Non e' una misura di forza.**
- **12/12 partite completate**, nessun errore nei log, nessun ripiego (paracadute mai usato), 11-12 round decisi dall'LLM su 12 per
  partita; durata 5-8 min per partita.
- **Costo**: 207 chiamate (17,3 a partita), **$2,84 la run** (tetto 30), **$0,237 a partita** (max $0,31; tetto per partita 2).
- **Operazioni** (93 avviate): offensiva 49 (eseguite 13, completate 7, fallite 3, annullate 30, in corso 9), rinforzo 36 (eseguite
  36, completate 8, fallite 3, annullate 21), tenuta 5 (completate 2, annullate 2), sbarco 3 (eseguito 1, annullate 3): **4 tipi usati
  realmente**. Rifiutate dal motore 3 (bersaglio gia' in un'operazione 2, massimo 3 attive 1). Azioni strategiche: avvia 93, continua
  77, annulla 51, modifica 2; ordini: tenuta tieni 25 / ritira 28, tappa1 lancia 4 / rinvia 1 / annulla 4, tappa2 procedi 1.
  Lo stratega annulla molto (spesso "bersaglio gia' nostro" o "trattiene unita' che servono altrove") e molte offensive non arrivano alla
  tappa 1.
- **Calibrazione** (la tabella della sezione [smoke] contava anche le stime di annulla/rinvia/ritira, che hanno prob ~0 e falsano le fasce
  basse: corretto nello step ANALISI ANNULLAMENTI, `ConfrontoSmoke.NON_ESEGUITE`). Ricalcolata a mano sulle sole azioni eseguite:
  livello strategico (avvia/continua/modifica, 124 stime, esito a fine durata) errore medio **18,7 punti**, troppo pessimista (0-19% →
  reale 57%, 20-39% → 73%, 60-79% → 80%, 80-100% 88% → 95%); per tipo offensiva 0,54 / reale 0,63, rinforzo 0,62 / 0,93, tenuta 0,47 /
  0,60, sbarco 0,26 / 0,60 (n 5); ordini del turno (lancia/procedi/tieni, 24 stime) errore **15,6 punti**.
- VC R12 del lato Stratega (solo informative, 6 semi, nessun riferimento Hard vs Hard nella run): Asse 3, 6, 5, 5, 6, 7 (media 5,3);
  Alleati 5, 5, 8, 4, 6, 4 (media 5,3).

### Step ANALISI ANNULLAMENTI — perche' lo Stratega LLM annulla (run [smoke] 42775993327) (2026-11-10)

Solo analisi dei diari della run [smoke] (zip `autoplay-latest`, commit 805c684): nessuna partita nuova, nessuna modifica al
comportamento. Documento completo con la tabella delle 56 operazioni: `proplus/analisi/annullamenti-smoke-42775993327.md`.
- **Conteggi**: 93 avviate, **56 annullate** (52 dal livello strategico, 4 dagli ordini; il "51" del riepilogo erano le azioni `annulla`
  nei record). L'LLM vede sempre le operazioni in corso e il loro stato (55/56): non e' una causa. Limite di 3 operazioni: irrilevante (1 rifiuto).
- **Cause**: C1 bersaglio preso da Hard/alleato prima delle tappe 15 (France R1 in 6 partite su 6: 3 annullate, 3 "completate" senza
  operazione); C2 rinforzo con `tenuta_50_con_rinforzi_pct` della stima rapida di `StrategaCandidati.tenuta()` (nemici dal piu' caro al
  50%, rinforzi = tutte le unita' in portata, aaGun compresi) a 100% contro `valutaTenuta` 0-29% al punto di decisione con gli stessi
  input: ritirati nel round di avvio, annullati il round dopo, 18; C3 presa del candidato con tutte le unita' in portata, tappa con solo
  gli aerei riservati (win 0 o perdita > tetto; Mosca lanciata 5 volte, mai eseguita) 7; C4 raccolta/flotta non realizzata 4; C5 candidato
  uscito dagli 8 2; C6 ripensamento sugli stessi numeri (avviate con tenuta 0 a ogni impegno; il punto 4 del prompt v4 "motore troppo
  pessimista" spinge a lanciare contro i numeri: 32/52 offensive/sbarchi con tenuta al 50% <= 5%) 3; G giustificati 7.
  **Artefatti 46 (82%), LLM 3 (5%), giustificati 7 (12,5%)**; nel merito l'annullamento e' giusto in 53/56: niente inerzia sugli
  annullamenti, il problema e' il lancio. Oscillazione in senso stretto (stesso bersaglio riavviato entro 2-3 round): 6 (11%). 6
  "annullate" erano gia' perse (FALLITE non registrate perche' la chiamata strategica precede l'aggiornamento del motore).
- **Offensive non eseguite 36/49**: C1 13, C3 7, C4 2, C5 2, C6 3, G 2, avviate al R12 5, bloccate fino a fine partita 2. Le 13
  "eseguite" del riepilogo sono 10 prese di Hard senza operazione + **3 tappe vere**. Ordine di turno mai causa.
- **Costo**: 470 PU di acquisti obbligati per operazioni poi annullate (65% delle riserve, 3,5% dei PU spesi; unita' non perse, mix e
  luogo non di Hard); 78 unita' (~795 TUV, quasi tutte aeree) spostate verso raduni abbandonati; nessuna perdita in combattimento.
- **Correzioni proposte (non implementate)**, annullamenti evitati: (1) una sola stima di tenuta nei candidati rinforzo/tenuta con i
  rinforzi realmente movibili 18; (2) escludere i bersagli che Hard attacca nel round / passare in TENUTA senza chiedere 15; (3) presa
  del candidato con le forze che l'operazione impegnera' 7; (4) aggiornare lo stato delle operazioni prima della chiamata strategica
  (contabilita': 6 annullate -> FALLITE); (5) fattibilita' come filtro 4; (6) prompt senza la spinta contro i numeri del motore 3;
  (7) candidati persistenti per le operazioni in corso 2; (8) isteresi sul RI-avvio (rete di sicurezza). 1+2+3 = 40/56, 1-7 = 49/56.
- **Anomalie** (non corrette): Alleati seme 503 R7 chiamata strategica fatta (annullamento applicato) ma record del diario e portafoglio
  del round persi: eccezione in `Stratega.Round.applica` dopo l'annullamento, solo in `ProLogger.warn`; la colonna "Eseguite" di
  `ConfrontoSmoke` conta le prese di Hard senza operazione e i rinforzi ritirati.
- **Unica modifica di codice**: `ConfrontoSmoke` esclude dalla calibrazione le azioni annulla/rinvia/ritira (`NON_ESEGUITE`, `eseguita`),
  + test `AutoplayTest.smokeCalibrationLeavesOutCancelPostponeAndWithdraw`. Verificato rigenerando il riepilogo dalle 12 partite della
  run: strategico 18,7 punti su 124 stime (prima 30,1 su 162), ordini 15,6 su 24 (prima 34,3 su 51) = ricalcolo a mano.
- Test locali (JDK 25 da apt; Maven Central 429 ⇒ init script in `~/.gradle/init.d` con il mirror Google SOLO in
  `dependencyResolutionManagement` (addFirst, dopo le impostazioni) e `pluginManagement` (beforeSettings): un blocco `allprojects
  { repositories }` rompe la build, `FAIL_ON_PROJECT_REPOS`): `:proplus-autoplay:test` 24/24, `spotlessCheck`, `pmdMain`, `pmdTest`,
  `:game-headed:compileJava` verdi. Attenzione: `:proplus-autoplay:spotlessApply` riformatta 4 file non toccati (ConfrontoConferma,
  ConfrontoHard, ConfrontoVarianza, TestQualita): ripristinati, non committare quelle differenze.
- Push senza `[smoke]`/`[autoplay]`: solo build e release `proplus-latest`, nessuna partita in CI.


### Step COERENZA STIME — una sola stima per candidati, punti di decisione ed esecuzione (2026-11-10)

Dall'analisi degli annullamenti (684652e): 46/56 annullamenti erano artefatti di numeri incoerenti del motore. Questo step rende i
numeri coerenti (una sola stima), senza inerzia sugli annullamenti e senza budget difensivo. Vedi "Coerenza delle stime (step
COERENZA STIME)" e lo schema 1.4 in `proplus/strategia/contratto-v1.md`.
File: nuovi `ai/proplus/StimaUnica`, `CauseAnnullamento`, risorse `prompt-stratega-v5.md`, `prompt-ordini-turno-v4.md`
(`PromptStrategaV4`→`PromptStrategaV5`, `PromptOrdiniTurnoV3`→`PromptOrdiniTurnoV4`, contratto 1.4), test `CoerenzaStimeTest`;
modificati `OperazioniCongiunte` (solo per le operazioni dello Stratega: `step1Stratega`, presa di Hard, stato prima della
decisione, fattibilita', sicurezza realistica, tenuta realistica, unita' trattenute, cause, tetto nel diario), `StrategaCandidati`
(screening rapido + stima unica sui primi 14, scarti motivati, tetto proporzionale), `Stratega` (aggiornamento prima della
decisione, `stima_motore_ora`/`al_lancio`, riavvio, cause, blocchi, `applica` robusto), `StrategaConfig` + `stratega-llm.json`
(valori nuovi documentati nella `nota_coerenza`, NON tarati), `StrategaDiario` (`scriviMinimo`, chiavi nuove nel riepilogo),
`DiaryRecorder.purchaseDefenseShare` (conteggio quota PU), `ProPlusAi.purchase` + `ProPlusTuning.holdCombatForPurchase`
(solo nazioni dello Stratega), `DecisoreOperazione` (prompt v4), autoplay `ConfrontoSmoke` (+ test), workflow `[smoke]` semi
601-606. `avvia.bat` e `aggiorna.ps1` NON toccati. Hard, Hard Beta, Pro+ (AI) e le operazioni scritte a mano invariati (ogni
cambiamento e' dietro `OperazioneCongiunta.isStratega` o `Stratega.gioca`).
- Test locali (JDK 25 da apt, mirror Google di Maven Central in `~/.gradle/init.d` con repository dal nome univoco e controllo
  `findByName`, altrimenti "repository with name ... already exists"): `:game-core:test --tests 'games.strategy.triplea.ai.*'`
  516, `:ai:test` 13, `:game-headed:test` 94, `:proplus-autoplay:test` 25: **0 falliti** (golden Hard `HardPriorityGoldenTest`
  1 + `HardNeutralityGoldenTest` 2, `NudoHardTest` 2, `DiaryNeutralityTest` 3 diario on/off, `ProPlusGoldenTest`,
  `OperazioniCongiunteTest` 11, `OperazioniLlmTest` 6, `StrategaLlmCompletoTest` 13 aggiornato). Nuovi `CoerenzaStimeTest` 8:
  stessa stima al candidato e al punto di decisione (stesso stato, dadi con lo stesso seme), bersaglio preso da Hard → TENUTA
  senza LLM / chiuso senza LLM (C1), FALLITA prima della chiamata strategica, candidato infattibile scartato (zona non sicura) e
  mai tra i candidati, tetto proporzionale e richiesta dell'LLM entro il massimo, nessun riavvio entro 3 round senza +5 punti,
  record del round scritto anche con un errore in `applica`; `AutoplayTest.smokeCountsOnlyTheEngineStepsAsExecutedAnd...`.
  PMD/spotless: le violazioni restano solo in file preesistenti non toccati (stesse di prima: `OperazioniAnfibie` 839,
  `OperazioniCongiunte.ordiniTenuta`/`gatherPlanes`, `Direttive`; formattazione di `ProPlusAi`, `StrategaSimulato`, ...):
  `spotlessApply` NON va lanciato sull'intero modulo (riformatta file non toccati).
- Prove locali con lo Stratega simulato (`-Dproplus.stratega.simulato=true`, 3-7 round, semi 601-604, nessun errore, 25-30 s a
  round): hanno trovato e fatto correggere due incoerenze vere: (1) per un round futuro la stima contava unita' "raggiungibili
  ora" che l'operazione non trattiene (Syria 100% → 11% alla tappa): ora solo cio' che l'operazione trattiene; (2) Hard pianifica
  gli attacchi all'acquisto: i rinforzi trattenuti solo al combattimento erano gia' stati usati (2 caccia russi del rinforzo di
  Egypt sui Paesi Baltici): ora trattenuti dall'acquisto.

#### Autoplay in CI (run 42803941666, commit 1f1c7e2, tutto verde: test, release `proplus-latest`, 12 partite [smoke] semi 601-606)
**Run NON valida come misura**: credito API Anthropic esaurito durante la run. 104 chiamate strategiche su 128 e 6 degli ordini
sono fallite con "Your credit balance is too low" (il messaggio dice "su Haiku", ma e' solo un'etichetta fissa del client; il
modello era Sonnet). 6 partite su 12 (semi 604-606) senza nessun round deciso dall'LLM; nelle altre 6, 5-8 round decisi su 12.
- **12/12 partite completate** (paracadute = Hard funzionante), 164 chiamate, **costo $0,630** (fino all'esaurimento del credito).
- Operazioni avviate 13, tutte TENUTA (completate 6, fallite 5, annullate 1 "giustificato", in corso 1), eseguite dal motore
  11/13. **Nessuna offensiva ne' sbarco avviato**: nei 24 round decisi l'LLM ha rifiutato le offensive perche' 178 candidati
  offensivi su 182 avevano successo 0% (tappa 2 senza attaccanti in 166). Causa: difetto di 1f1c7e2. Per i round futuri la stima
  contava solo le unita' gia' in raccolta, e la fattibilita' impone sempre il round obiettivo >= round + 1, quindi ogni conquista
  aveva k >= 2 e forze nulle. L'LLM ha seguito correttamente i numeri del motore.
- Criteri: artefatti C1-C5 0/13 (0%); offensive/sbarchi eseguiti dal motore 0/0 (non misurabile); mediana |stima candidato -
  stima al punto di decisione| **0 punti** su 12 operazioni (solo tenute); calibrazione tenuta: LLM 37,5 punti (21 stime,
  80-100% → reale 63%), stima del motore al lancio 48,1 punti (12 stime, 100% → 55%), ordini 5,5 punti (13 stime). Candidati
  scartati 1.325 (in prevalenza "Hard lo prende da solo" e raccolta oltre l'orizzonte). Su sole tenute e su meta' delle partite
  questi numeri NON dicono se lo step funziona.
- **Correzione** (commit successivo, solo prove locali con lo Stratega simulato, semi 604-605, 7 round): forze per i round futuri =
  raggiungibili entro k round + sbarcabili + gia' in raccolta, con `avvicina` e `trattenute` che le portano e le tengono; le
  offensive tornano con presa 94-100%, ma la tenuta stimata contro il contrattacco realistico resta bassa (5-27%): successo >= 20%
  in 4 candidati offensivi su 34. Coerenza locale mista: Syria 26 → 27, Egypt 6 → 0, Morocco 7 → 0; rinforzi Denmark 99 → 1,
  France 100 → 16 (stato cambiato tra lancio e decisione).
- **Da rifare**: [smoke] con credito API ricaricato (stessi semi 601-606).
- **Diagnostica per la nuova [smoke]** (solo diario, NON nello stato inviato all'LLM, nessun cambio di comportamento):
  `stratega-analisi-R<N>-<alleanza>.json` (per ogni conquista valutata: presa, tenuta, impegno nemico e se era il minimo
  prudente, TUV nemico distrutto atteso = difensori + rinforzi tra le tappe - residui dopo la tappa 2, TUV perso atteso nelle
  tappe, presidio perso atteso, scambio TUV, esito del motore "in lista"/"scartato: motivo"; `StimaUnica.Stima.jsonAnalisi`,
  `StrategaCandidati.analisi`); nel registro di ogni operazione `trattenute_acquisto` [{round, nazione, stato, round_obiettivo,
  unita, tuv, ids}] (unita' tenute fuori dagli attacchi di Hard all'acquisto), copiato in `stratega-riepilogo.json`.

#### Autoplay in CI (run 42822465005, commit eb20be2 = 2d7e78c + diagnostica, tutto verde, 12 partite [smoke] semi 601-606)
Run valida: 12/12 completate, 143/144 round decisi dall'LLM (1 risposta non valida), 209 chiamate, **$2,59** (tetto 30), $0,22 a
partita. Operazioni 56: tenuta 45 (eseguite dal motore 40, completate 21, fallite 12, annullate 3), offensiva 10 (eseguite 1, prese
da Hard 1, fallite 2, annullate 8), sbarco 1 (in corso). Rifiutato 1 riavvio (Holland Belgium).
- **Criteri**: artefatti C1-C5 **7/56 (13%) > 10%: NO**; offensive/sbarchi eseguiti dal motore **1/11 (9%) < 30%: NO**; mediana
  candidato/primo punto di decisione 0 punti su 43, MA quasi solo tenute: per le offensive la stima del round dopo il lancio
  differisce di **77 punti** di mediana (11 casi: Syria 80-89% → 0-27% in 4 partite, Caucasus 100 → 3, Southern France 100 → 0):
  **NO per le offensive**. Calibrazione: stima del motore al lancio offensive 91% → reale 29% (7), tenute 99% → 71% (35); LLM
  strategico 34,1 punti (offensive 70,2), ordini 6,8.
- **Causa principale** (dalle stime del round dopo): le unita' contate per una conquista futura NON sono trattenute. `trattenute`
  in preparazione tiene solo le unita' di TERRA contate e `gathered` solo cio' che e' ai luoghi di raccolta: gli aerei contati
  (Syria: 5 caccia + 5 tattici al lancio, 1-2 al round dopo) li usa Hard nel suo turno. Le 7 "C5" sono questo caso (candidato poi
  scartato perche' Hard lo prende da sola o raccolta aerea non sicura): la regola di `CauseAnnullamento` mette C5 prima del
  controllo del calo di stima, quindi la classe giusta sarebbe C3.
- **Diagnostica** (`stratega-analisi-R*.json`, 196 conquiste in lista + 21 scartate dopo la stima): impegno nemico = minimo
  prudente 0,5 in 63/196; tenuta < 20% in 158 dei 185 non avviati; scambio TUV atteso delle tappe > 0 in 57 non avviati (43 con
  tenuta < 20%). Valore d'alleanza (presa x valore x tenuta - perdite) > 0 solo in 7 non avviati; contando i TUV nemici distrutti
  attesi diventa > 0 in altri 42; con tenuta 100% in altri 23. Unita' trattenute all'acquisto: 134 registrazioni (1.726 unita',
  113 per tenute); per tappe poi rinviate 3 (28 unita', 1 turno ciascuna). Un solo calo >= 30 punti a un punto di decisione
  (Syria seme 602: tappa 1 "2 bomber, 5 fighter, 5 tactical" → "2 bomber, 2 fighter, 2 tactical", presidio "2 art, 2 inf" → "1
  inf"); nessun caso tipo Denmark tra le tenute.

### Step C1-bis — aerei trattenuti, ordine delle cause, valore d'alleanza in PU (2026-11-10)

Vedi "Step C1-bis" (sezione "Coerenza delle stime"). File: `StimaUnica` (aerei contati solo se trattenibili, `valoreAlleanza`/
`jsonValore`), `OperazioniCongiunte` (`trattenute` con ogni unita', `avvicina`, `colpisceDaQui`, `gatherPlanes` sugli aerei
contati, trattenute nel combattimento di preparazione, tappa 1 con il valore in PU), `CauseAnnullamento` (ordine),
`StrategaCandidati` (`valoreBersaglioPu`, `indice_valore`), `StrategaConfig` + `stratega-llm.json` (`valore_pu`), `Stratega`
(`stima_round_dopo`), `StrategaDiario`, prompt v5 (1 riga), `contratto-v1.md`, `ConfrontoSmoke`, workflow (semi 701-706).
Hard, Pro+ (AI) e operazioni scritte a mano invariati.
- Test locali: `:game-core:test --tests 'games.strategy.triplea.ai.*'` + `:proplus-autoplay:test` + `:ai:test` **557, 0
  falliti** (golden Hard, `NudoHardTest`, `DiaryNeutralityTest` diario on/off compresi); `CoerenzaStimeTest` 11 (nuovi: aereo che
  colpisce da dove e' contato e trattenuto all'acquisto, aereo che dovrebbe prima avvicinarsi non contato; valore in PU con
  componenti e formula; calo di stima C3 prima di C5); `:game-headed:compileJava` verde.
- Cause della run 42822465005 ricalcolate con il nuovo ordine (stima ora = `stima_motore_ora` nello stato della chiamata che
  annulla): 11 annullate = **C3 6, giustificato 5** (prima C5 7, giustificato 4): le 7 "C5" erano 6 Syria/Southern France/
  Caucasus con successo 80-100% → 0-27% e West India con contrattacco massimo 3 → 16 TUV. Artefatti C1-C5 6/56 (11%).

#### Autoplay in CI (run 42833255633, commit 21f57be, tutto verde, 12 partite [smoke] semi 701-706)
12/12 completate, 144/144 round decisi dall'LLM, 0 ripieghi, 222 chiamate, **$3,08** (tetto 30), $0,26 a partita. Operazioni
69: tenuta 48 (eseguite dal motore 46, completate 28, fallite 11, annullate 2), offensiva 18 (eseguite 4, completata 1, fallita 1,
annullate 13), sbarco 3 (preso da Hard 1, annullato 1). Aerei trattenuti: 130 turni-aereo in 21 operazioni.
- **Criteri**: artefatti C1-C5 **11/69 (16%) > 10%: NO** (C3 9, C2 1, C5 1; giustificati 5); offensive/sbarchi eseguiti dal
  motore **4/21 (19%) < 30%: NO**; mediana candidato → primo punto di decisione 0 punti su 50 (quasi tutte tenute); **lancio →
  round dopo per le offensive mediana 26 punti su 19 (criterio <= 15): NO** (era 77). Calibrazione del motore al lancio: offensive
  92% → reale 15% (13), tenute 99% → 79% (38); LLM strategico 33,7 punti (offensive 68,4), ordini 4,9. VC R12 (informative):
  Asse Stratega 5,2 / Hard 5,8; Alleati Stratega 4,8 / Hard 6,2.
- **Perche' (dai diari)**: (1) Syria R1 lanciata in 6 partite su 6 e annullata al R2 in 6 su 6 (C3): i 3 TUV di difensori
  francesi spariscono prima della tappa (Hard italiana la prende o la svuota: l'LLM lo dice in tutte e 6 le motivazioni), la
  tappa 1 di soli aerei passa a win 0% perche' senza difensori la formula da' 0 a una tappa senza terra (artefatto del numero,
  non delle forze), e la tenuta stimata oscilla 81-91% → 21-100% con +7 TUV di contrattacco contato e 1 fanteria in meno nel
  presidio (stima di tenuta molto sensibile su stack piccoli). Il valore d'alleanza di Syria era 38 PU di cui 49 TUV nemici
  distrutti attesi (46 di rinforzi britannici tra le tappe) contro 4 PU di bersaglio: i TUV distrutti hanno reso il lancio piu'
  attraente. (2) Offensive tardive (Greece R10 x2, Iraq R11): la tappa 2 conta unita' di terra raggiungibili entro k x movimento
  (es. 9 artiglierie italiane, carri tedeschi da Bryansk), ma la "sicurezza raduni" nega i passi (15-34 mosse negate per turno)
  ⇒ le unita' restano ferme (ora trattenute) e il round dopo non sono piu' entro (k-1) x movimento ⇒ la tappa 2 crolla (9 art.
  5 fant. → 1 carro, win 100 → 0). Gli aerei ora restano (5-9 turni-aereo per operazione); il calo e' sulle unita' di terra che
  il motore conta ma non puo' muovere. (3) Tenute: buone (46/48 eseguite, calibrazione 99 → 79).
- Non tarato (regola dello step). Correzione coerente con lo step (da decidere): contare per un round futuro solo le unita' di
  terra che hanno un percorso SICURO verso la raccolta (la stessa prova di `stepToward`/`sicuro` usata per muoverle), e per una
  tappa senza difensori nemici non usare il win% della tappa 1 nel confronto (presa gia' 100%).

### Step C1-ter — raggiungibilita' = movimento, bersaglio vuoto, TUV distrutti (2026-11-10)

Vedi "Step C1-ter". File: `OperazioniCongiunte` (`passiSicuri`, `arriviSicuri`, `haPercorsoSicuro`, `luoghiRaccolta`,
`routeFor` con portata, `stepToward`/`gatherPlanes` sullo stesso controllo, testo della tappa 1), `StimaUnica` (forze con
esclusioni, bersaglio vuoto, `destroyed`, campi `bersaglioVuoto`/`soloAerei`/`esclusePercorso`), `stratega-llm.json` (nota),
prompt v5 (2 righe), `ConfrontoSmoke`, workflow (semi 801-806), `CoerenzaStimeTest` +3. Hard, Pro+ (AI), operazioni scritte a
mano invariati (tutto dietro le operazioni dello Stratega).
- Test locali: `:game-core:test --tests 'games.strategy.triplea.ai.*'` + `:proplus-autoplay:test` + `:ai:test` **560, 0 falliti**
  (golden Hard, `NudoHardTest`, `DiaryNeutralityTest` compresi); nuovi: fanteria con l'unico passo negato dal controllo di
  sicurezza non contata (contata se il passo e' sicuro); bersaglio vuoto con soli aerei presa 0 e nota, con un carro presa 100%,
  senza win% della tappa 1; TUV distrutti <= difensori presenti con rinforzi nemici attesi > 0. Partita locale 3 round con lo
  Stratega simulato: 89 s (nessun rallentamento).

#### Autoplay in CI (run 42848125217, commit 291b47c, tutto verde, 12 partite [smoke] semi 801-806)
12/12 completate, 143/144 round decisi (1 risposta non valida), 195 chiamate, **$2,68** (tetto 30), $0,22 a partita. Operazioni
44: tenuta 38 (eseguite dal motore 36, completate 18, fallite 13, in corso 7), offensiva 4 (prese da Hard 2 = completate senza
tappe, annullate 2), sbarco 1 (eseguito, fallito), rinforzo 1.
- **Criteri**: artefatti C1-C5 **1/44 (2%) <= 10%: SI'** (C3 1; giustificato 1); offensive/sbarchi eseguiti dal motore **1/5 (20%)
  < 30%: NO**; lancio → round dopo per le offensive mediana **18 punti su 5 (<= 15): NO** (era 26, prima 77); calibrazione del
  motore al lancio: offensive 99% → reale 75% (4; era 92 → 15), sbarco 100 → 0 (1), tenute 99 → 62 (29). LLM strategico 37,9 punti
  (offensive 12,1), ordini 6,2. Escluse per percorso non sicuro: 1 caccia in 1 operazione; lanci con presa 0 "solo aerei": 0;
  turni-aereo fermi 39 in 5 operazioni. VC R12 (informative): Asse Stratega 5,2 / Hard 5,8; Alleati Stratega 5,0 / Hard 6,0.
- **Perche' (dai diari)**: le offensive lanciate sono crollate da 18 a 4 (+1 sbarco): con i TUV distrutti dei soli difensori il
  valore d'alleanza dei bersagli poveri e' negativo (Syria non e' piu' lanciata in nessuna partita). Delle 5 conquiste: Algeria
  (R2) e Normandy (R8, offensiva) prese da Hard prima delle tappe (TENUTA, completate, non contano come eseguite dal motore: e'
  questo che tiene il criterio al 20% su un campione di 5); Libya annullata "giustificato" (rinforzi nemici tra le tappe 3 → 169
  TUV, contrattacco 3 → 48); Iraq annullata C3 (successo 100 → 25: il presidio stimato passa da 3 carri + 1 mech a 1 carro perche'
  la tappa 1 scende da 23 a 16 carri e la tappa 2 da 3 a 2); sbarco su Normandy eseguito dal motore e perso al R9
  (contrattacco 16 → 30). Le differenze lancio → round dopo: Libya 97, Iraq 75, sbarco 18, Algeria 0, Normandy 0 (mediana 18).
- Non tarato. Restano: (a) Iraq: 7 carri e 3 mech contati al lancio non ci sono piu' nella stima del round dopo, anche se le
  unita' contate sono trattenute (acquisto, combattimento, non combattimento): causa NON verificata (candidati: perse in difesa
  nel turno nemico, prese da un'altra operazione dell'alleanza, guardia della capitale); (b) campione piccolo (5 conquiste):
  servono piu' partite per giudicare il 30%.

### Step C2 — budget difensivo calcolato (2026-11-10)

Vedi "Budget difensivo calcolato (step C2)". File: nuovi `ai/proplus/Difesa`, `Tracciamento`, `ai/pro/ProDefensePlan`, test
`DifesaTest` (7), `DefensePlanPurchaseTest` (2); modificati `ProTuning` (+`defensePlan`, default vuoto), `ProPurchaseAi` (piano
al posto di `defend_land`, ordini limitati a PU_strategia), `ProPlusTuning` (`setDefensePlan`), `ProPlusAi` (calcolo all'acquisto,
`playDefense`), `StimaUnica` (`nemiciReali`, `soloNemiciReali`, `contate`, `tenuta` condivisa), `OperazioniCongiunte.minaccia`
(nemici reali per lo Stratega), `Stratega` (liste, stato `difesa_calcolata`, tracciamento, vincoli, `setTettoStrategia`),
`StrategaDecisione`/`PromptStrategaV5` (contratto 1.5), `StrategaConfig`/`stratega-llm.json` (`tetto_strategia_pct` 70 al posto di
`quota_pu_max_pct` 30, blocco `difesa`), `StrategaDiario`, `StrategaSimulato`, prompt v5, `contratto-v1.md`; autoplay
`--tetto-strategia`, `ConfrontoSmoke.difesa`, workflow semi 901-906. Test locali: AI di game-core + autoplay + `:ai` + game-headed
verdi (golden Hard, `NudoHardTest`, `DiaryNeutralityTest` compresi).

#### Autoplay in CI (run 42868691448, commit 52b7463, tutto verde, 12 partite [smoke] semi 901-906)
12/12 completate, 0 ripieghi, 177 chiamate, **$2,86**. **Risultato fortemente negativo per l'Asse**: VC R12 Asse Stratega **0,33**
(1, 0, 0, 1, 0, 0) contro 5,17 in C1-bis e C1-ter; Germany caduta 8 volte (6 partite su 6), Southern Italy 5; territori dell'Asse a
fine partita 1 (prima 27-29); reddito tedesco R4 20,5 contro 43,5. Alleati Stratega VC 3,83 (prima 4,83 / 5,0), Russia caduta 5/6
(prima 3/6, 4/6), France R1 6/6 come sempre.
- **Cause (dai diari, non tarato)**: (1) **guarnigioni congelate**: `Difesa.Piano.trattenute` tiene il presidio di OGNI territorio
  difeso, anche "tiene gia'" (50-93 unita' tedesche per turno fuori dagli attacchi e dal non combattimento di Hard): l'esercito
  tedesco non attacca piu'; (2) **difesa di Hard tolta**: Hard comprava 21 PU/turno di difesa per la Germania (C1-ter), il piano 0,6
  (PU_difesa = 0 in 89/112 turni dell'Asse, 171/192 degli Alleati; media 1,85 / 0,92; massimo 15 / 21); (3) **acquisti mai fatti
  quando i rinforzi arrivano a 12** (`max_unita_per_territorio` conta rinforzi + acquisti: con 12 rinforzi gli acquisti sono 0; 11
  casi di territori con fabbrica propria difesi sotto soglia, es. Germany R6 tenuta 0% con 12 fanterie e nessun acquisto);
  (4) acquisti solo nella fabbrica del territorio (deviazione dal punto C): dei territori minacciati sotto soglia 718 su 856 non
  hanno una fabbrica propria (Asse 202 senza fabbrica + 70 con fabbrica di un alleato su 368; Alleati 516 + 9 su 578).
- Difesi persi al turno dopo: Asse 57/520 (capitali 11/170, LLM 16/78, motore 30/272), Alleati 35/303 (capitali 0/46, LLM 13/48,
  motore 22/209); irrinunciabili persi 40/342. Calibrazione della stima di difesa: errore medio **29,4 punti** (criterio <= 20: NO),
  troppo pessimista sotto il 20% (585 stime: 1% → reale 58%). Tetto strategia: superato in 268/304 turni ma **mai limitante** (riserve
  chieste sempre < PU_strategia; strategia 58-69% dei PU contro il 30% fisso di prima). Tracciamento: 6 cali >= 30 punti, tutti
  "tenuta: contrattacco nemico aumentato" (Holland Belgium: 13 unita' contate spostate, 2 perse).
- Offensive/sbarchi eseguiti dal motore 0/4, artefatti 1/24 (4%).

### Step C2-bis — difesa di Hard, budget dinamico sopra la difesa, piano C2 in ombra (2026-11-10)

Decisione di Matteo dopo la [smoke] del C2: la difesa torna a Hard, il budget delle operazioni si costruisce sopra. Vedi
"Difesa di Hard e budget dinamico (step C2-bis)". File: nuovi `ai/pro/ProDefenseLists`, `ProStrategyBudget`, test
`ai/pro/StrategyBudgetDefenseTest` (4, al posto di `DefensePlanPurchaseTest`); tolti `ai/pro/ProDefensePlan` e
`ProTuning.defensePlan`; modificati `ProTuning` (+`strategyCapPercent`, `defenseLists`, vuoti), `ProPurchaseAi` (difesa di Hard,
tetto degli ordini dopo la difesa, sacrificabili/irrinunciabili in `prioritizeTerritoriesToDefend`, `purchaseDefenders`,
`placeDefenders`), `ProNonCombatMoveAi.prioritizeDefendOptions`, `ProDecisionRecorder.strategyBudget`, `ProPlusTuning`,
`ProPlusAi` (niente guarnigioni del piano, niente `playDefense`), `ProPlusStrategaAi` (`Difesa.fineTurno`), `Difesa` (ombra,
limiti separati, fabbriche vicine, `registraBudget`), `DiaryRecorder`, `Stratega` (testo dei vincoli), prompt v5,
`stratega-llm.json`, `ConfrontoSmoke`, workflow (semi 1001-1006). Test locali: 535 test AI di game-core (golden Hard, `NudoHardTest`,
`DiaryNeutralityTest` compresi; quest'ultimo fallito 1 volta nella suite, verde 2/2 da solo: instabilita' nota) + autoplay + `:ai` verdi.

#### Autoplay in CI (run 42890953654, commit 81e89b0, tutto verde, 12 partite [smoke] semi 1001-1006)
12/12 completate, 144/144 round decisi dall'LLM, 0 ripieghi, 208 chiamate, **$3,63** ($0,30 a partita).

| Metrica | C1-ter (42848125217) | C2 (42868691448) | C2-bis |
|---|---|---|---|
| VC R12 Asse Stratega (semi) | 5,17 (5,5,7,7,3,4) | 0,33 | **4,17** (4,4,3,3,5,6) |
| Capitali dell'Asse cadute | 0 | 13 | 2 (Southern Italy R9 e R10, seme 1004) |
| VC R12 Alleati Stratega | 5,00 | 3,83 | **5,00** (4,6,4,7,4,5) |
| Capitali alleate cadute | 12 (France R1 6/6 + Russia 6) | 11 | 10 (France R1 6/6 + Russia 4/6) |
| PU di difesa di Hard, Germania (media per turno) | ~21 | 0,6 | 20,3 |

- **Paracadute**: Alleati uguali a C1-ter; Asse −1 VC (Welch: errore ~0,8, IC 95% ~±1,8: dentro il rumore, ma la direzione e'
  negativa) e Southern Italy caduta in una partita (R8 Italia: 26 PU tutti nella difesa di Hard, 0 → 3 unita', presa al R9: difesa
  di Hard, non unita' tolte dalle operazioni). Il crollo del C2 e' sparito.
- **Budget**: operazioni 1-3% dei PU per nazione (mediana 0%, massimo 17-58%): con poche offensive le riserve sono quasi nulle;
  difesa di Hard Germania 20,3, Italia 8,4, URSS 11,4, UK 4,4, USA 3,8 PU per turno; turni con difesa 0: USA 53/72, UK 42/72,
  Germania 21/72. Il tetto 70% trattiene PU in 27 turni su 336 e **non limita mai** le riserve (0 turni con obbligati non comprati).
- **Ombra contro Hard** (1.558 territori minacciati valutati, esito al turno dopo): ombra difende / Hard non rinforza 666 (tenuti
  88%); ombra difende / Hard rinforza 263 (98%); ombra sacrifica / Hard non rinforza 539 (67%); ombra sacrifica / Hard rinforza 90
  (76%). Calibrazione della stima in ombra (tenuta senza rinforzi): errore medio **50 punti**, molto pessimista (0-19% → reale 74%
  su 912 stime). Fabbriche vicine: 277 territori con acquisti vicini, 99 arriverebbero alla soglia al turno dopo.
- Offensive/sbarchi eseguiti dal motore 2/11 (18%, criterio 30%: NO); artefatti C1-C5 4/54 (7%: SI'); lancio → round dopo per
  le offensive mediana 18,5 punti (criterio 15: NO); calibrazione del motore al lancio: offensive 99% → reale 0% (7), tenute 99%
  → 72% (36). Irrinunciabili difesi e persi al turno dopo 32/414.

### Step R0 — diagnosi dei -1,8 VC dell'Asse Stratega (2026-11-10, lavoro notturno autonomo)

Scopo: capire da dove vengono i ~-1,8 VC dell'Asse Stratega rispetto a Hard vs Hard (smoke 42890953654: 4,17 contro 5,94) prima di
correggere altro. Nessuna correzione di comportamento: due interruttori (default acceso), modalita' `[diagnosi]`, sezione "Diagnosi
Stratega (R0)". Vedi "Interruttori di diagnosi dello Stratega (step R0)" e "Modalita' diagnosi" nella sezione dell'autoplay.
File: nuovi `ai/proplus/StrategaInterruttori`, autoplay `ConfrontoDiagnosi`; modificati `ProPlusTuning.defenseLists`,
`StrategaCandidati.calcola`, `StrategaFuoriLista.valida`, autoplay `AutoplayOptions` (`--stratega-spento`), `AutoplayRunner`,
`Riepilogo` (`chiave_llm`, `stratega_spento`), `RiepilogoGenerale` (etichetta, sezione), workflow (`[diagnosi]`). Hard, Pro+ (AI) e le
altre voci invariate. `avvia.bat` e `aggiorna.ps1` NON toccati.


#### Autoplay in CI (run 42915599025, commit 9d73dd4, tutto verde, 50 partite [diagnosi] semi 1101-1110)
50/50 completate, 0 eccezioni nei log, costo LLM **7,99 $** (tetto 15). Cancello R0: SI'. Asse, VC R12 (media ± ds, min-max),
reddito finale, capitali dell'Asse cadute (totale su 10 partite):

| Configurazione | VC R12 | Reddito finale | TUV perso | Capitali cadute |
|---|---|---|---|---|
| a Hard / Hard | 5,70 ± 1,64 (2-8) | 66,1 | 884 | 1 |
| b Stratega senza chiave | 5,50 ± 1,43 (4-7) | 62,0 | 849 | 3 |
| c Stratega LLM | 4,00 ± 1,63 (2-6) | 35,8 | 871 | 5 |
| d Stratega LLM senza liste | 4,70 ± 1,83 (2-8) | 50,0 | 894 | 7 |
| e Stratega LLM senza tenute | 5,50 ± 1,08 (4-7) | 55,6 | 894 | 3 |

Differenze VC R12 (IC 95% Welch; con 10 semi si distinguono solo ~1,2 VC o piu'): b − a −0,20 [−1,65; 1,25] include lo 0;
**c − a −1,70 [−3,24; −0,16] esclude lo 0** (reddito −30,3 [−53,7; −6,9] esclude lo 0); d − c +0,70 [−0,93; 2,33] include lo 0;
**e − c +1,50 [0,18; 2,82] esclude lo 0**; e − a −0,20 include lo 0. `ControlloTuning` di b: 449.743 domande, **0 differenze**,
949 risposte "operazione" (il tetto della strategia 70%, inerte senza operazioni).
- **Regole del prompt applicate**: b NON e' distinguibile da a ⇒ il percorso Stratega senza LLM e' Hard, nessuna correzione "b" in R1.
  e migliora c (+1,5 VC, esclude lo 0); d migliora c di +0,7 (non distinguibile). **Default NON cambiati: decide Matteo.**
- Perche' (dai riepiloghi): in c l'LLM avvia quasi solo tenute (33 tenute, 4 offensive in 10 partite; d: 33/2; e: 0/4) e le unita'
  tenute fuori dagli attacchi di Hard all'acquisto sono **2.144 in 99 turni** (c), 1.726 in 80 (d), **58 in 13** (e): le tenute
  bloccano l'esercito dell'Asse (presidio + rinforzi interi, OC:457-474), da qui il reddito −30 e le VC −1,7. E' il punto A8 di R1.

### Step R1 — correzioni del gruppo A (+ B5-B8, D6, linea di base a regole) (2026-11-10, notte)

Vedi "Correzioni dello Stratega (step R1, gruppo A)" e la modalita' `[correzioni]`. File: nuovi `ai/proplus/StrategaRegole`;
modificati `ai/pro/AbstractProAi` (hook protetto `forgetStoredCombatMove`/`hasStoredCombatMove`, mai chiamato da Hard),
`ProPlusAi`, `ProPlusStrategaAi`, `OperazioniCongiunte`, `StimaUnica`, `Stratega`, `StrategaCandidati`, `StrategaFuoriLista`,
`StrategaDiario`, `StrategaConfig` + `stratega-llm.json`, `DecisoreOperazione`; autoplay `--stratega-regole`, `ConfrontoDiagnosi`
(contatori, g contro c), `ConfrontoSmoke` (paracadute fuori dalla calibrazione), workflow `[correzioni]`. Hard e le altre voci
invariate; `avvia.bat` e `aggiorna.ps1` NON toccati.
- Test locali (JDK 25, mirror Google): `:game-core:test --tests 'games.strategy.triplea.ai.*'` + `:ai:test` + `:proplus-autoplay:test`
  **581, 0 falliti** (golden Hard `HardPriorityGoldenTest` e `HardNeutralityGoldenTest`, `NudoHardTest`, `DiaryNeutralityTest`
  compresi). Nuovi in `StrategaLlmCompletoTest`: chiamata fallita ripetuta dalla nazione successiva e round chiuso dopo 2 fallimenti
  (A7), impegno di una conquista/costa mai abbassato dallo storico delle tenute (A3), guardia della capitale fissata al lancio e
  invariata con una minaccia nuova (A5), candidati tenuta con `tenuta_hard_da_solo_pct` < soglia e guadagno >= 5 (A8), linea di base
  a regole (annulla < 30%, continua, avvia per valore). `CoerenzaStimeTest.anEmptyTargetIsTaken...` aggiornato: con l'insieme
  eseguibile gli aerei non entrano in un territorio vuoto (presa 0 "solo aerei" invariata).
- Non verificato nel codice e quindi non toccato: nessuno dei punti A; i numeri di riga del prompt corrispondevano a f0f5e3f.

#### Autoplay in CI (run 42921405264, commit cebbd59, tutto verde, 40 partite [correzioni] semi 1101-1110)
40/40 completate, 0 eccezioni, costo LLM **4,90 $** (tetto 15). Stessi semi di R0.

| Configurazione | Lato | VC R12 | Reddito finale | Δ VC da Hard vs Hard (IC 95% Welch) |
|---|---|---|---|---|
| a Hard / Hard | Asse | 5,60 ± 1,26 | 61,6 | - |
| a Hard / Hard | Alleati | 5,40 ± 1,26 | 104 | - |
| c Asse Stratega LLM | Asse | 4,40 ± 0,97 | 40,5 | **−1,20 [−2,26; −0,14] esclude lo 0** (R0: −1,70) |
| f Alleati Stratega LLM | Alleati | 4,40 ± 0,70 | 91,1 | **−1,00 [−1,98; −0,02] esclude lo 0** |
| g Asse Stratega a regole | Asse | 4,00 ± 1,49 | 42,4 | −1,60 [−2,90; −0,30]; g − c −0,40 [−1,59; 0,79] include lo 0 |

- Hard vs Hard sugli stessi semi: 5,7 in R0, 5,6 in R1 (le simulazioni dell'AI restano casuali): la differenza c R1 − c R0 (+0,4)
  e' dentro il rumore.
- **A8 funziona sul costo**: unita' tenute fuori dagli attacchi di Hard all'acquisto (Asse) 2.144 in 99 turni (R0) → **638 in 75**;
  500 righe "trattenute ... solo" nel combattimento. Ma le VC restano sotto Hard: in R0 solo "senza tenute" (e) era alla pari.
- Operazioni: c 36 tenute + 5 offensive (tutte annullate), f 27 tenute + 3 offensive + 1 sbarco + 1 rinforzo, g 79 tenute + 6 offensive
  + 1 sbarco. **Menu vuoto** in 30/120 round (c), 56/120 (f), 32/120 (g). 0 tentativi falliti, 0 round senza LLM, 0 unita' contate e non
  mosse, piani di Hard ricalcolati 3 (c) / 8 (f) / 24 (g), 0 pause per paracadute (l'LLM ha sempre risposto).
- **La linea a regole non e' distinguibile dall'LLM** (−0,4 VC, include lo 0): con questi candidati l'LLM non aggiunge valore
  misurabile rispetto a una regola semplice; entrambi restano sotto Hard di ~1-1,6 VC.

### Step R2a-OMBRA — tenute spente di default + pianificatore di campagna in ombra (2026-11-11)

Vedi "Tenute spente e campagne in ombra (step R2a)" e la modalita' `[ombra]`. File: nuovi `ai/proplus/CampagnaOmbra`, test
`CampagnaOmbraTest`, autoplay `ConfrontoOmbra`; modificati `StrategaInterruttori` (default per interruttore, `accendi`, `OMBRA`),
`ProPlusAi` (chiamata all'acquisto), `ProPlusStrategaAi` (fine turno), `Stratega.diarioTurnoOmbra`, `StrategaDiario` (`ombra` nel
riepilogo), `stratega-llm.json` (`campagna_ombra`), `prompt-stratega-v5.md` (tenute), autoplay `AutoplayOptions`
(`--stratega-acceso`), `AutoplayRunner`, `Riepilogo` (`stratega_acceso`), `RiepilogoGenerale`, `ConfrontoDiagnosi` ("[con x]"),
workflow `[ombra]`; test `NudoHardTest` (+1: Stratega senza chiave con l'ombra accesa), `StrategaLlmCompletoTest` (default delle
tenute), `AutoplayTest` (+1). Hard e le altre voci invariate; `avvia.bat` e `aggiorna.ps1` NON toccati.
- Test locali: `:game-core:test --tests 'games.strategy.triplea.ai.*'` + `:ai:test` + `:proplus-autoplay:test` **589, 0 falliti**
  (golden Hard, `NudoHardTest` 3 compreso "Stratega senza chiave", `DiaryNeutralityTest`). Nel turno "Stratega senza chiave" le sole
  risposte diverse da Hard sono il tetto della strategia (inerte senza operazioni) e la capitale sempre tra gli irrinunciabili
  (comportamento del C2-bis, gia' misurato in R0 come risposte "operazione": b − a −0,2 VC).
- Prove locali (Stratega simulato, 3 round): Asse 83 s, Alleati 63 s (R1 locale 89 s): nessun rallentamento misurabile; stato
  dell'ombra 6,9 KB per alleanza; blocco in ogni turno; rilascio del presidio visto (Novgorod R1 caso c → R2 caso a).

#### Autoplay in CI (run 42976494562, commit f9b7eae, tutto verde, 30 partite [ombra] semi 1101-1110)
30/30 completate, 0 eccezioni, 0 errori dell'ombra, costo LLM **3,28 $** (tetto 15). Durata media: Hard vs Hard 286 s, c 452 s (R1
488, −7%), f 428 s (R1 410, +4%): entro +30%. Blocco dell'ombra in **120/120** round per alleanza.

| VC R12 del lato | R2a (tenute spente) | Hard vs Hard (stessa run) | Δ da Hard [IC 95% Welch] | R1 (stessi semi) | Δ da R1 [IC 95%] |
|---|---|---|---|---|---|
| Asse Stratega (c) | 5,70 ± 1,34 | 6,10 | −0,40 [−1,55; 0,75] include lo 0 | 4,40 | **+1,30 [0,20; 2,40] esclude lo 0** |
| Alleati Stratega (f) | 4,90 ± 1,29 | 4,90 | 0,00 [−1,13; 1,13] | 4,40 | +0,50 [−0,49; 1,49] |

- **Parte 1 conferma R0**: con le tenute spente lo Stratega torna alla pari con Hard su entrambi i lati (la stima dice "non
  distinguibile", ~±1,1 VC con 10 semi), e l'Asse migliora rispetto a R1 oltre il rumore.
- **Ombra, candidati fattibili** (somma R1-R12 su 10 partite; valutati: Asse 643, Alleati 823): Asse 138 / 133 / 98 ai margini
  60 / 70 / 80, Alleati 109 / 92 / 88. Obiettivi piu' frequenti: Asse France R1 (10/10 a 60 e 70), poi Novgorod, Volgograd, Egypt;
  Alleati Germany, Poland, Egypt, France (nessuno al R1). Quota PU richiesta media 0-60%. Non monotono in qualche round (es. Alleati
  R6: 23 fattibili a 80 contro 20 a 70): la campagna si ferma alla prima tappa che blocca e lo scarto "colmabile con acquisti" cambia
  con la tappa, quindi i margini non sono annidati (difetto noto dell'approssimazione).
- **Previsioni**: difensori a +1/+2/+3 round mediana dell'errore Asse 33 / 51 / 70 TUV, Alleati 85 / 124 / 155 TUV (210-410% del
  reale); **sovrastimati nel 89-96% dei casi** (rinforzi confinanti e acquisti nemici contati che non arrivano). Reddito: mediana 2-6
  PU (7-21%); forza propria 21-47 TUV (7-15%).
- **Perdite delle battaglie reali** (prima tappa prevista, Asse 210, Alleati 187): reale <= prevista nel **98-100%** a tutti i margini
  (attese ~60/70/80%): le perdite previste sono molto pessimiste e i tre margini non si distinguono.
- **Regola 2.6** (territori classificati / attaccati / persi nel turno nemico dopo; presidio previsto / unita' lasciate da Hard, medie):
  Asse a 714 / 1% / 0% / 0 / 2,5; b 804 / 13% / 10% / 0,1 / 3,4; c 137 / 22% / 14% / 7,0 / 6,9. Alleati a 472 / 0% / 0% / 0 / 1,4; b 535 /
  37% / 33% / 0,1 / 2,5; c 195 / 34% / 27% / 9,7 / 12,9. Il caso a) e' affidabile (quasi mai attaccato); nei casi b) gli Alleati perdono
  1 territorio su 3 (retrovie accettate); nel caso c) Hard lascia in media quanto il presidio previsto (Asse) o di piu' (Alleati).
- Le liste dell'errore nei riepiloghi sono potate a 200 voci per chiave e alleanza (le tabelle contano fino a 1.957 confronti; i file
  `campagna-ombra-*.json` li hanno tutti: 2.167 per gli Alleati a +1).

### Step R2a-BIS — due misure in ombra: previsioni ricalibrate e retrovie di Hard (2026-11-11)

Vedi "Misure in ombra R2a-bis". Nessun effetto sul gioco, nessuna chiave API (costo 0). File: nuovi
`ai/proplus/CampagnaOmbraMisure`, autoplay `OmbraDettagli`; modificati `CampagnaOmbra` (flusso v2, tre modelli, punte, Misura 2,
persi/ripresi; `fineTurno` ora solo con l'ombra accesa), `stratega-llm.json` (`campagna_ombra.r2a_bis` con l'origine di ogni
valore), autoplay `Riepilogo` (`ombra_dettagli`), `ConfrontoOmbra` (`writeBis`), workflow `[ombra]` (b1/b2 senza chiave); test
`CampagnaOmbraTest` (+4 e controllo del calcolatore), `AutoplayTest` (+1). Hard e le altre voci invariate; `avvia.bat` e
`aggiorna.ps1` NON toccati.
- Verificato nel codice: `AggregateResults.getResults()` esiste solo nella versione a lista; quella a contesto
  (`BoundedContextAggregateResults`) tiene le simulazioni in un campo privato `runs` ⇒ letto per riflessione (come i campi di
  `BattleRecord` in R2a), nessun codice upstream toccato. Nella partita locale tutte le 54 tappe col calcolatore hanno letto le
  perdite per simulazione dalla lista (implementazione di default).
- Test locali (JDK 25, mirror Google solo in sandbox): `:game-core:test --tests 'games.strategy.triplea.ai.*'` + `:ai:test` +
  `:proplus-autoplay:test` **591, 0 falliti** (golden Hard 1+2, `NudoHardTest` 3 compreso "Stratega senza chiave = Hard" con
  l'ombra accesa, `DiaryNeutralityTest` 3, `CampagnaOmbraTest` 7 compreso "ombra accesa = ombra spenta").
- Prova locale (Asse Stratega senza chiave vs Hard, seme 1101, 3 round): 74 s con l'ombra, 86 s con l'ombra spenta (rumore: nessun
  rallentamento misurabile); 56 chiamate al calcolatore in 3 round; 0 eccezioni. Hard sposta davvero unita' dal fronte ovest alla
  Germania (classe "mossa nel turno"), verificato con il territorio di partenza nelle righe.

#### Autoplay in CI (run 42994455013, commit 733d975, tutto verde, 20 partite [ombra] senza chiave, semi 1101-1110)
20/20 completate, 0 eccezioni, 0 errori del calcolatore, costo API 0. Durata media b1 (Asse Stratega senza chiave) **309 s**, b2
(Alleati) **382 s**: contro la run 42976494562 −32% / −11% rispetto a c/f (LLM), +8% / +34% rispetto a Hard vs Hard (286 s): entro
+50%. Chiamate al calcolatore 155 (Asse) e 173 (Alleati) a partita, tutte con le perdite lette per simulazione dalla lista.
- **1.2 difensori** (mediana con segno / assoluta, TUV): Asse +1/+2/+3 M0 28/30, 45/48, 63/65; **M1 0/7, 3/9, 3/9**; M2 2/9,
  6/15, 14/28,5. Alleati M0 88/88, 126/127, 160/160; **M1 0/15, 0/18, 0/20**; M2 0/15, 3/21, 5/30. M1 (persistenza) e' il migliore
  in 46 righe su 48 per tipo e guerra; M0 sovrastima ovunque (capitali e fabbriche alleate +120-220 TUV). Quota M2 osservata
  ≈ −0,05 (limitata a 0): i difensori NON crescono dopo la previsione; M2 resta peggiore di M1 solo per la quota iniziale 0,5
  delle prime verifiche e per i casi "guerra non ancora in corso".
- **1.3 contrattacco** (tenuta stimata ⇒ tenuti davvero): Asse 0-20% 348 territori ⇒ 59%, 20-40% ⇒ 74%, 40-60% ⇒ 100%, 60-80% ⇒
  95%, 80-100% ⇒ 99%; Alleati 0-20% 344 ⇒ 38%, poi 92-100%. La fascia 0-20% e' quasi tutta di territori presi e lasciati vuoti
  (tenuta stimata 0); sopra il 20% la stima e' pessimista. Forza impegnata / massima in portata (attaccati): Asse capitale 63%, VC
  79%, fabbrica 71%, altro 33%; Alleati 13% / 51% / 42% / 31%.
- **1.4 perdite** reali sotto P60/P70/P80 del calcolatore: Asse 60% / 75% / 81% (95 battaglie), Alleati 61% / 74% / 80% (145):
  **calibrati** (R2a con i fattori: 98-100%).
- **1.5 fattibili** (somma R1-R12): Asse v2 314 / 309 / 300 su 636 contro M0 135 / 106 / 72; Alleati v2 267 / 258 / 259 su 831 contro
  M0 135 / 118 / 83. Modello usato M1 in 556/636 e 742/831 (M2 solo nei primi round, prima di 10 verifiche).
- **Misura 2** (unita'-turno / TUV per round della nazione): "ferma davvero" quasi nulla: Germania a 0,1/0,4, b 0,1/0,5; Italia ~0;
  Alleati ≤ 0,1. Nelle retrovie Hard lascia soprattutto unita' **mosse nel turno** (Germania b 10,3/38,5, Russia b 11,0/41,0),
  appena piazzate (Germania a 3,8/14,0) e antiaeree; "bloccate" Germania b 4,3/15,9 (isole, Finlandia). Il guadagno di "liberare le
  retrovie" e' quindi trascurabile: Hard non lascia unita' ferme dietro.
- **Caso b persi** (piu' frequenti): Asse Greece 7 (ripresi 1), Rostov 6 (4), Libya/Tobruk 5 (0); Alleati Smolensk 16 (ripresi 14),
  Belarus 14 (6), Rostov 14 (5), Bryansk 12 (10), Karelia 10 (2), Archangel 9 (1); unita' di Hard alla caduta 0,1-1,5.

### Step R2b IMPEGNO — prima campagna che AGISCE (2026-11-11)

Vedi "Impegno (step R2b)" e la modalita' `[impegno]`. Motivo (run 42976494562 e 42994455013): le offensive dello Stratega venivano
annullate quando la stima ricalcolata scendeva (6 su 8) e la fattibilita' a piu' round non regge (al round stimato fattibile 19% Asse
/ 3% Alleati); reggono le previsioni a 1 round (M1) e il calcolatore sulle forze reali del turno ⇒ un impegno di DIREZIONE che dura,
senza date ne' annullamenti per stima. File: nuovi `ai/proplus/Impegno`, test `ImpegnoTest`, autoplay `ConfrontoImpegno`; modificati
`StrategaInterruttori` (`IMPEGNO`, default dal file dati), `CampagnaOmbra` (solo metodi nuovi: `fronti`, `obiettivoPer`,
`valutaImpegno`), `ProPlusAi` (acquisto, `playCommitment` nel non combattimento, piazzamento), `ProPlusTuning` (ordini dell'impegno,
tetto vuoto nel turno con ordini), `OperazioniCongiunte` (`forzaBersaglio`, `forzaUnBersaglio`, `sogliaForzata(proData, t)`),
`StrategaDiario` (`impegno` nel riepilogo), `Stratega.registra` + `ProfiloStratega.impegno` + `StrategistPanel` (casella "Impegno"),
`stratega-llm.json` (`impegno`), autoplay `RiepilogoGenerale`, `AutoplayTest` (+1), workflow `[impegno]` (+ campo `acceso` della
matrice). Hard e le altre voci invariate; `avvia.bat` e `aggiorna.ps1` NON toccati.
- Scelte fatte da solo (documentate): "durata minima 3 round negli altri casi" = rotta assente e capitale in caso c chiudono l'obiettivo
  solo dopo 3 round (prima: sospensione nel turno / si continua col presidio della capitale tolto dai PU); "quanto serve a capitale e
  fabbriche in caso c" = costo del presidio minimo 2.6 MANCANTE (le unita' proprie presenti contano prima); "perso" per
  l'instradamento = vittoria del contrattacco realistico oltre `soglia_sicurezza_raduno` 40 (la soglia gia' usata per i raduni);
  vittoria e P80 dell'attacco forzato dal calcolatore con 100 simulazioni; unita' "dell'operazione" = comprate (per id) + quelle in
  caso a/b a inizio turno + quelle instradate (seguite per id da li' in poi); perdite "in viaggio" = unita' seguite vive a fine turno
  e sparite all'inizio del turno dopo (cioe' nei turni nemici).
- Prove locali (seme 1101/1102, senza chiave): Asse 3 round 57 s (obiettivi France R1 presa, Egypt R2 presa dall'Italia, poi Russia;
  attacchi forzati vinti su France, Alexandria, Egypt, Baltic States), Alleati 4 round 70 s (Russia su Poland; Stati Uniti e Regno
  Unito senza obiettivo: capitale in caso c o nessun candidato via terra). Visti: Hard scarta un bersaglio forzato per `defensive_stance`
  (la forzatura non scavalca la postura difensiva di Hard); Hard puo' attaccare un forzato con meno unita' di quelle contate (soglia 30%).

#### Autoplay in CI (run 43023379267, commit 89ee22b, tutto verde, 30 partite [impegno] senza chiave, semi 1101-1110)
30/30 completate, 0 eccezioni, costo API 0. Durata media: a Hard vs Hard 353 s, c1 311 s (**−12%**), c2 334 s (**−6%**): entro +50%.

| VC Asse | R4 | R8 | R12 | Δ R12 da (a) [IC 95% Welch] | Reddito R12 Asse / Alleati | TUV R12 Asse / Alleati |
|---|---|---|---|---|---|---|
| a Hard / Hard | 4,40 | 5,80 | 5,60 ± 1,1 | - | 63,1 / 102,8 | 1015 / 1565 |
| c1 Asse Stratega + impegno | 5,00 (+0,60 [−0,04; 1,24]) | 5,20 (−0,60 [−1,60; 0,40]) | 5,40 ± 2,1 | −0,20 [−1,78; 1,38] include lo 0 | 53,9 / 111,3 | 848 / 1604 |
| c2 Alleati Stratega + impegno | 4,40 (0,00) | 5,70 (−0,10) | 6,40 ± 1,4 | +0,80 [−0,40; 2,00] include lo 0 (peggio per gli Alleati) | 74,0 / 91,1 | 1168 / 1421 |

- Citta' passate di mano entro R12 (a / c1 / c2, partite su 10, round mediano): Mosca 2 R8,5 / 5 R9 / 7 R8; Stalingrado 9 R8 / 6 R9 / 9 R9;
  Leningrado 10 R5 / 10 R5 / 9 R5; Egitto 6 R3 / 8 R1 / 6 R3; Parigi 10 R1 in tutte; Berlino mai; Roma 0 / 1 R8 / 1 R7.
- Obiettivi (c1): Germans France 10, Novgorod 7, Russia 8, Volgograd 4, Egypt 1; 17 presi (France R1 x8, Novgorod R3-R5 x6, Russia R7 e
  R9, Volgograd R10); chiusi per "capitale in caso c" 9, "non piu' del nemico" 3; durata media 2,6 round. Italians Egypt 16 (presa 9
  volte, R1-R4), cambi: capitale c 8, non piu' del nemico 6, rotta assente 5. (c2): Russians Poland 8 (presa 3), chiusi 10 volte per
  capitale in caso c; British Egypt 6 (riconquistata 6 volte R3-R6), Novgorod 8; Americans obiettivi scelti ma mai presi.
- **PU all'operazione solo 24% (Asse) e 18% (Alleati) dei PU dei turni**, non 70%: obiettivo presente in 144/240 turni dell'Asse e
  153/360 degli Alleati; **capitale propria in caso c (2.6) in 111/240 turni dell'Asse e 87/360 degli Alleati** (niente obiettivo
  nuovo) e PU_op calato dal presidio mancante. La regola 2.6 classifica "c" la capitale molto spesso: e' il freno principale.
- Attacchi forzati: c1 107 (vinti 86, non presi 11, non attaccati da Hard 10); perdite reali <= P80 in 77/97, media 8,7 contro P80
  10,2 TUV. c2 62 (vinti 39, non presi 12, non attaccati 11); <= P80 44/51 (5,2 contro 11,1).
- Instradate per round (c1) 1-48 (picco R6), (c2) 2-8; unita' dell'operazione perse nei turni nemici: c1 fino a 5,5 unita' / 23,5 TUV
  per round (R8), c2 fino a 4,7 / 16,6 (R7).

### Step R2c PREPARAZIONE E PROGRAMMI — due fasi, correzioni di R2b, programmi USA e Regno Unito (2026-11-11)

Vedi "Preparazione e programmi (step R2c)". Motivo (run 43023379267): l'instradamento R2b portava a est le unita' di caso a/b che
coprivano dagli sbarchi (2.6 guarda solo la terra); la Russia sceglieva obiettivi non fattibili e svuotava Mosca (caduta 7/10 contro
2/10); attacchi forzati mandati da Hard con meno unita' di quelle contate; USA e Regno Unito con trasporti fermi e vuoti. File: nuovo
`ai/proplus/Programmi`; modificati `Impegno`, `ProPlusTuning` (2 hook), `ProTuning` (+2 hook di default vuoti), `ProPurchaseAi` (+1
chiamata), `ProCombatMoveAi` (+1 chiamata), `stratega-llm.json` (`impegno.r2c`), `ImpegnoTest`, autoplay `Monitor`
(`portate_regno_unito`), `ConfrontoImpegno` (sottosezione R2c), `AutoplayTest`, workflow `[impegno]` (semi 1101-1120). Hard e le
altre voci invariate (golden Hard, nudo = Hard, Stratega senza chiave = Hard, diario, ombra accesa = spenta: verdi).
- Scelte fatte da solo: base sicura cercata anche sulla via amica fabbrica → partenza (con la sola rotta prima della tappa, spesso
  1 territorio esposto, la base mancava quasi sempre: visto nella prima prova); in revisione si aggiungono solo unita' di TERRA (aerei
  no: atterraggio non verificato); Russia con obiettivo tenuto ma non piu' fattibile = sospesa nel turno (gioca come Hard), non chiusa;
  "trasporto utilizzabile" = entro 2 zone (un turno) dalle zone del porto; i trasporti vuoti tornano al porto anche prima della guerra
  (dalle zone della spola o entro 2 zone); prima della guerra i carichi restano fermi e tenuti al porto; forza da sbarco UK = 70% dei PU
  dopo presidi E campagna sull'Egitto; Persia presa con l'unita' confinante piu' economica non della capitale ne' dell'Egitto.
- B3: vedi la sezione (difesa urgente di Hard prima degli ordini: spiegata e registrata; riconoscimento per tipo: corretto).
- `Monitor`: il motore svuota la lista "scaricate" dei trasporti a fine movimento non di combattimento (`MoveDelegate`,
  `resetUnitStateAtEnd`), quindi le unita' portate nel Regno Unito si contano come unita' di terra della nazione comparse li' in un
  passo di movimento e assenti a inizio turno (anche in Scotland).
- Prove locali (sandbox, senza chiave): seme 1104 Asse 4 round 117 s (fasi ESECUZIONE su France/Egypt/Slovakia Hungary con attacchi
  forzati "ESEGUITO" alla revisione, PREPARAZIONE con base sicura Northern Italy, un "NON ATTACCATO DA HARD"); seme 1103 Alleati 7
  round 178 s contro Hard vs Hard 178 s: Russia "gioca come Hard" (nessun obiettivo fattibile), USA trasporti fermi vuoti a R7 5 contro
  16, 2+8 unita' portate nel Regno Unito a R5-R6, Persia presa, Iraq non attaccato (vittoria 10%).

#### Autoplay in CI (run 43058974168, commit c31ce23, tutto verde, 60 partite [impegno] senza chiave, semi 1101-1120)
60/60 completate, 0 fallite, costo API 0. Durata media: a Hard vs Hard 341 s, c1 357 s (**+5%**), c2 344 s (**+1%**): entro +50%.

| VC Asse | R4 | R8 | R12 | Δ R12 da (a) [IC 95% Welch] | Reddito R12 Asse / Alleati | TUV R12 Asse / Alleati |
|---|---|---|---|---|---|---|
| a Hard / Hard | 4,65 | 5,60 | 5,15 ± 1,7 | - | 57,0 / 109,2 | 875 / 1583 |
| c1 Asse Stratega + impegno | 5,00 (+0,35 [−0,07; 0,77]) | 5,60 (0,00) | 5,60 ± 1,8 | +0,45 [−0,66; 1,56] include lo 0 | 62,9 / 104,0 | 949 / 1498 |
| c2 Alleati Stratega + impegno | 4,60 (−0,05) | 5,45 (−0,15) | 6,00 ± 1,3 | +0,85 [−0,11; 1,81] include lo 0 (peggio per gli Alleati) | 69,8 / 96,3 | 985 / 1452 |

- Citta' passate di mano (a / c1 / c2, su 20, round mediano): Mosca 6 R9,5 / 9 R9 / 9 R10; Stalingrado 13 R8 / 14 R8 / 15 R7;
  Leningrado 19 R5 / 20 R5 / 20 R5; Egitto 12 R3,5 / 19 R1 / 13 R3; Parigi 20 R1 ovunque; Berlino mai; Roma 3 R8 / 2 R9,5 / 0.
- Fasi (c1): Germans 39 turni in PREPARAZIONE, 129 in ESECUZIONE, 43 obiettivi presi; Italians 53 / 81, 32 presi, 3 sospesi. Attacchi
  forzati c1 228: rivisti "eseguito" 170, "completato" 0, "rinunciato" 4, non attaccati da Hard 21, vinti 181. (c2): British 6/20,
  Egitto ripreso 12 volte; Russians 22 PREPARAZIONE, 7 ESECUZIONE, **16 turni sospesi "gioca come Hard"**, 3 presi.
- Russia (TUV / reddito): R4 351/24 (a), 339/25 (c1), 348/24 (c2); R8 301/15, 260/13, 293/12; R12 247/14, 192/13, **158/10**: con
  l'impegno degli Alleati la Russia a R12 ha comunque meno esercito di Hard vs Hard (Mosca 9/20 contro 6/20).
- USA (c2 contro a, medie per partita): trasporti fermi vuoti a fine movimento R8 12,6 contro 14,5, R12 18,2 contro 24,5; unita'
  portate nel Regno Unito per round 0,8-7,2 da R5 (a: 0-2,4 da R7); sbarcate in Europa occidentale simili; primo sbarco USA in Europa
  occidentale R7 contro R8 (20/20); prima testa di ponte tenuta 2 round R7 contro R6,5. Programma USA: 285 PU spesi su 694 PU dei
  turni (41%), per partita 25,8 fanterie, 11,5 artiglierie, 7,2 carri, 11,1 cacciatorpediniere, **4,5 trasporti** (gli altri ~22 li
  compra Hard con il resto dei PU: la regola del carico bilanciato vincola solo i PU del programma). Spola: 52 unita' caricate, 35
  scaricate nel Regno Unito, 29 rientri di trasporti vuoti per partita.
- Regno Unito: trasporti fermi vuoti come Hard (R12 10,0 contro 10,5); programma 147 PU su 461 (32%, presidi di Londra/Egitto prima);
  Persia presa in 20/20 partite, Northwest Persia 15/20; Iraq forzato 18 volte (16 vinti, 2 non attaccati da Hard).

### Step R3a SBARCO COORDINATO E TEATRI — teatri, sbarco coordinato, Nord Africa a tenaglia, tetto ai trasporti, Russia = Hard (2026-11-11)

Vedi "Sbarco coordinato e teatri (step R3a)" e "Scelte di R3a". Motivo (run 43058974168): il programma USA porta ~40 unita' a partita nel
Regno Unito ma l'esercito USA resta parcheggiato (Berlino mai, Roma 0/20), Hard sbarca a gocce e compra ~22 trasporti (18 fermi a R12),
nessuna pressione sul Mediterraneo, nessun obiettivo dopo l'Egitto, campagne russe inutili (3 obiettivi in 20 partite). File: nuovi
`ai/proplus/SbarcoCoordinato`, `ai/proplus/RegistroDecisioni`, test `SbarcoCoordinatoTest`, autoplay `RegistroLettura`, `ConfrontoR3a`;
modificati `Impegno` (campagne R3a, Russia senza campagne, chiamate a R3a, registro dei programmi e della spola, `diarioRighe`, `r3a` nel
riepilogo), `Programmi` (base dal teatro, `zoneSpolaVerso`, `fermiVuoti`, `distanzaMare` di pacchetto), `ProPlusAi` (trattenute
all'acquisto, `playCoordinatedLanding`), `ProPlusTuning` (`buysSeaTransports`, `amphibiousHeldUnits`), `ProTuning` (+2 hook di default =
Hard), `ProPurchaseAi` (+1 riga: opzioni senza trasporti se l'hook lo chiede), `ProCombatMoveAi` (+1 riga: unita' escluse dal carico
anfibio dell'hook), `ProPurchaseOptionMap` (`withoutSeaTransports`), `stratega-llm.json` (`impegno.r3a`), autoplay `Monitor` (regioni,
Nord Africa, teatro), `Riepilogo` (`impegno_decisioni`), `ConfrontoImpegno` (titolo + chiamata), `AutoplayTest` (+1 e titolo), workflow
`[impegno]` (semi 1121-1140). Hard e le altre voci invariate; l'Asse non cambia (R3a solo per Americans, British, Russians); `avvia.bat` e
`aggiorna.ps1` NON toccati.
- Test locali (JDK 25, mirror Google solo in sandbox): `:game-core:test --tests 'games.strategy.triplea.ai.*'` **574, 0 falliti** (golden
  Hard `HardPriorityGoldenTest`/`HardNeutralityGoldenTest`, `NudoHardTest` compreso "Stratega senza chiave = Hard", `DiaryNeutralityTest`
  diario on/off, `CampagnaOmbraTest` ombra accesa = spenta, `ImpegnoTest` 14, `SbarcoCoordinatoTest` 11), `:ai:test` 13, `:proplus-autoplay:test`
  31: 0 falliti.
- Prova locale (Europa 1940, Hard vs Alleati Stratega senza chiave con impegno, seme 1121, 7 round, 250 s, 0 eccezioni, 116 righe di
  registro): teatro NORD chiuso dal R1 (zona peggiore 70-100%: aerei tedeschi in portata delle zone 109/110 contro la sola scorta USA),
  SUD aperto al R4 ⇒ cambio a SUD, base Gibraltar (Morocco era dell'Asse); via libera e sbarco USA su Tunisia R5 (2 unita', vittoria 100%,
  tenuta 89,9%, presa e tenuta 2 round) e Morocco R6 (8 unita', presa, tenuta); rinforzi britannici 0 (nessun trasporto britannico nelle zone
  di Gibilterra); Regno Unito: Tobruk R1 → Libya R2-R6 (presa al R6), poi France dalla testa di ponte (R7); USA: Algeria (presa al R7); Asse
  senza territori in Nord Africa a fine R7; tetto attivo per gli USA in 5 turni su 7 (0 trasporti comprati in quei turni; R4 e R6 senza tetto
  Hard ne ha comprati 4 e 6).

#### Autoplay in CI (run 43088872425, commit 1ec2ead, tutto verde, 60 partite [impegno] senza chiave, semi 1121-1140)
60/60 completate, 0 fallite, costo API 0. Durata media: a Hard vs Hard 362 s, c1 366 s (**+1%**), c2 313 s (**−14%**): entro +50%.

| VC Asse | R4 | R8 | R12 | Δ R12 da (a) [IC 95% Welch] | Reddito R12 Asse / Alleati | TUV R12 Asse / Alleati |
|---|---|---|---|---|---|---|
| a Hard / Hard | 4,60 | 5,55 | 5,35 ± 1,3 | - | 57,0 / 106,5 | 912 / 1571 |
| c1 Asse Stratega + impegno (R2c) | 5,20 (+0,60 [0,24; 0,96] esclude lo 0) | 5,80 (+0,25) | 5,25 ± 1,8 | −0,10 [−1,10; 0,90] include lo 0 | 59,1 / 106,8 | 933 / 1518 |
| c2 Alleati Stratega + R3a | 4,70 (+0,10) | 5,60 (+0,05) | 5,55 ± 1,4 | +0,20 [−0,65; 1,05] include lo 0 (peggio per gli Alleati) | 63,4 / 101,7 | 997 / 1601 |

- Citta' (a / c1 / c2, su 20): Mosca 5 / 7 / 6; Stalingrado 16 / 16 / 16; Leningrado 20 ovunque; Egitto 13 R3 / 20 R1 / 10 R3; Parigi 20 R1;
  Berlino mai; **Roma 6 R9 / 1 / 0**. Germania c2 a R12 reddito 49,5 / TUV 759 contro 43,5 / 676 in (a): con R3a la Germania sta meglio.
- **Teatro** (c2, 240 round): NORD 220, SUD 20; aperti NORD 94, SUD 75 (spesso entrambi chiusi: NESSUNO_APERTO 105); cambi 6 (UNICO_APERTO 4,
  PUNTEGGIO_MAGGIORE 2); bloccati da ONDATA_IN_MARE 25, INERZIA 1.
- **Sbarchi coordinati USA**: 26 (NORD 19 in 13/20 partite, primo R9 mediano, 12,8 unita' per ondata, presi 19/19, tenuti 1 round 13, 2 round 7,
  perdite reali 0 / P80 1; SUD 7 in 4/20, presi 5, tenuti 2 round 5). Rinforzi britannici arrivati 14 volte (67 unita'), sbarchi UK da soli 4,
  completamenti 0. Attese (NORD): MANCA_TENUTA 100, NESSUN_BERSAGLIO 59 (prima della guerra), NESSUN_CARICO 41; (SUD) NESSUN_CARICO 8.
  Calibrazione: vittoria stimata 80-100% ⇒ presi 28/30 (93%); tenuta stimata 60-80% ⇒ tenuti 7/10, 80-100% ⇒ 15/15.
- **Unita' di terra a R12** (a / c2): USA base 0,1 / 9,7, patria 16,4 / 12,4, mare 0,7 / 7,2, Europa occidentale 9,2 / 5,3, Mediterraneo e Nord
  Africa 18,9 / 5,4; Regno Unito base 9,4 / 9,9, Europa occidentale **14,1 / 2,2**, Mediterraneo 7,6 / 5,1, altrove 18,6 / 30,1. La forza
  trattenuta alla base (USA + UK 7 a R1, 18,5 a R12) e i soli sbarchi coordinati mettono MENO truppe in Europa e nel Mediterraneo degli sbarchi
  "a gocce" di Hard (il Regno Unito non sbarca piu' con Hard dalla patria).
- **Nord Africa**: territori dell'Asse a fine R4/R8/R12 (a) 5,2 / 2,1 / 0,3, (c2) 4,1 / 0,1 / 0,1; Asse a zero 17/20 R9 (a) contro 20/20 **R7**
  (c2); reddito italiano R12 13,6 / 13,9 (nessun effetto sul reddito). Regno Unito: 50 obiettivi in Africa (Tobruk 19, Libya 16, Tunisia 10),
  47 presi; USA: Algeria 1.
- **Trasporti** (a / c2): USA R12 26,0 (24,7 fermi vuoti) / 13,6 (8,9); UK 11,6 (11,0) / 5,9 (5,5). Tetto attivo 8,9 turni a partita per
  nazione, **0 trasporti comprati da Hard col tetto**.
- **Russia**: 0 obiettivi, 0 ordini, 0 forzature (= Hard); Mosca 6/20 contro 5/20.
- **Registro**: 2.780 decisioni in 20 partite + 870 esiti; tutti i 12 tipi presenti.

### Step PARTITA COMPLETA - PROVA — quanto dura una partita giocata fino alla vittoria (2026-11-11)

Solo misura: nessun cambio al codice di gioco, a Hard, allo Stratega o all'impegno. Motivo: in oltre 200 partite a 12 round nessuna
vittoria (si misurava sempre una partita a meta').
- **Scoperta**: su WW2 Europe 1940 2nd Edition **il motore non dichiara mai la vittoria**. I trigger `triggerAttachmentAxisVictory1` /
  `triggerAttachmentAlliedVictory1` hanno come `victory` il testo del messaggio; `TriggerAttachment.triggerVictory` lo cerca come chiave in
  `notifications.properties` della mappa, che non lo contiene (3 sole righe, dichiarazioni di guerra) ⇒ `getMessage` = null ⇒ niente
  `signalGameOver`. Vale anche nel client (non solo nell'autoplay, che in piu' non carica le risorse della mappa). Inoltre l'XML controlla
  la condizione a un solo fine round (TODO "For a complete round of play"), mentre le note della mappa chiedono di tenerla un round intero.
- Modifica (solo modulo `proplus-autoplay` e workflow): opzione `--fino-a-vittoria si`; classe `Vittoria` che, quando parte il passo di
  fine round (round N, tutte le nazioni hanno giocato), valuta le condizioni degli stessi trigger della mappa (stesso metodo del motore,
  `collectTestsForAllTriggers` con `ObjectiveDummyDelegateBridge`) e dichiara vincitrice l'alleanza la cui condizione e' vera a fine N-1 e
  a fine N ("round intero", per entrambe le alleanze come dicono le note della mappa); stallo al tetto (round o minuti) = uscita 0.
  `Tempi.per_round_s`; sezione `ConfrontoCompleta`; modalita' `[completa]` del workflow (vedi "Autoplay in CI"). Senza l'opzione il runner
  e' identico a prima (test `withoutTheOptionTheGameGoesOnToTheLastRoundAsBefore`).
- Test: `PartitaCompletaTest` (4, partite Hard vs Hard vere sulla mini-mappa `src/test/resources/minimap-vittoria.xml` = `minimap.xml` dei
  fixture con alleanze Axis/Allies, Total Victory spenta e un trigger di vittoria alleata vero dal round 3 senza messaggio nelle
  notifiche): la partita si ferma al **round 4** (condizione vera a fine R3 e R4), stallo con `--round 3`, comportamento normale senza
  l'opzione, sezione del riepilogo generale. Verifica a parte (test temporaneo non committato) sull'XML reale: condizioni false
  all'inizio, Asse vera con 8 VC alla Germania, Alleati veri con Berlino e Roma ai Britannici (con Berlino e Roma ai **Russi** a inizio
  partita e' falsa: la condizione e' "alleata della Francia" e la Russia non lo e' ancora; a partita avanzata si'). Partita reale locale
  (Hard vs Hard, seme 1101, `--round 2 --fino-a-vittoria si`): stallo al tetto, uscita 0, 31 + 20 s per round.
- `:proplus-autoplay:test` 35 (31 + 4), 0 falliti; `pmdMain`/`pmdTest` verdi; `spotlessCheck` del modulo segnala solo i 9 file preesistenti
  gia' noti (non toccati). Durata dei 4 test nuovi ~95 s.

#### Autoplay in CI (run 43099617725, commit 3a07e37, verde al 2° tentativo, 3 partite [completa] Hard vs Hard, semi 1101-1103, costo API 0)
Tentativo 1: build, test, release, 1101 e 1102 verdi; il job di 1103 non ha mai avuto un runner (nessuno step, annullato dopo 15 min
in coda) ⇒ "Re-run failed jobs" (tentativo 2, nessun push): 1103 verde, `autoplay-latest` ripubblicata con le 3 partite. 0 ERROR nei log.

| Seme | Esito | Round | Durata | s/round R1-R12 | R13-R24 | Condizione vera a fine round |
|---|---|---|---|---|---|---|
| 1101 | vittoria Alleati | 21 | 8 min 34 s | 28 | 19 | Alleati 20, 21 |
| 1102 | vittoria Asse | 16 | 4 min 48 s | 18 | 19 | Asse 12, 15, 16 |
| 1103 | vittoria Alleati | 22 | 11 min 06 s | 35 | 24 | Alleati 21, 22 |

- Nessuno stallo, nessuna partita oltre il round 22: i tetti 60 round / 120 minuti sono molto larghi. I round dopo il 12 sono piu' veloci
  (meno unita' dell'Asse). Media 8 min 09 s, massima 11 min 06 s.
- 1102: l'Asse aveva 8 VC a fine R12 (Mosca caduta al R8), li perde al R13 e li riprende: con la lettura dell'XML (un solo fine round)
  avrebbe vinto al R12 ⇒ alcune delle partite "nessuna entro 12 round" delle run precedenti erano vittorie non riconoscibili dal motore.
- 1101/1103: Alleati a 11 VC su 11; Roma cade al R20 (1101, Americans) e R17 (1103, British), Berlino al R18 (British) e R21 (Russians);
  Parigi passa di mano piu' volte tra R13 e R20.
- Stima CI per 60 partite complete (20 in parallelo, 3 ondate, +2 min di preparazione per job): 30-39 min, ~10 h di minuti runner.

### Step R4 STRATEGIA COMPLETA — assedio (Asse), snodo di Gibilterra e Africa (Alleati), reddito e bonus, partite complete (2026-11-11)

Vedi "Strategia completa (step R4)" e "Scelte di R4". Motivo: a 12 round nessuna versione batte Hard sui VC; l'Asse con impegno parte
meglio e poi cede (R5-R8); gli Alleati con R3a tolgono pressione (Roma mai presa, 101 attese per tenuta, SUD quasi mai aperto); una
partita finisce tra R16 e R21 ⇒ da ora la misura e' chi vince. File nuovi: `ai/proplus/ConfigR4`, `Reddito`, `Assedio`, `SnodoAlleati`,
test `StrategiaR4Test`; autoplay `MonitorR4`, `ConfrontoR4`, test `StrategiaR4AutoplayTest`. Modificati: `Impegno` (scelta R4, rinforzi,
bombardieri, spola italiana, Sudafrica, id del programma), `SbarcoCoordinato` (snodo R4, ondate con filtro, trasporti usati), `Programmi`
(porto/base italiani, spola USA solo programma, trasporti allo snodo), `ProPlusAi` (+bombardieri trattenuti), `ProPlusTuning` (+bombarda),
`RegistroDecisioni` (tipi e motivi), `StrategaInterruttori` (+2), `ProPurchaseOrders` (`Mandatory.factory` facoltativo), `ProPlanPurchase`
(fabbrica della categoria prima), `stratega-llm.json` (`impegno.r4`), autoplay `Vittoria` (due letture), `Monitor` (+`r4`),
`ConfrontoCompleta` (colonna XML, tabelle R4, niente sottosezioni oltre 12 partite), `ConfrontoImpegno` (non con partite complete),
`PartitaCompletaTest`, workflow `[completa]` (200 partite). Hard, Hard Beta e le altre voci invariate; interruttori spenti = R3a/R2c.
- Test locali (JDK 25, mirror Google solo in sandbox): `:game-core:test --tests 'games.strategy.triplea.ai.*'` **586, 0 falliti**
  (golden Hard, `NudoHardTest` con "Stratega senza chiave = Hard", `DiaryNeutralityTest`, `CampagnaOmbraTest` ombra accesa = spenta,
  `ImpegnoTest`, `SbarcoCoordinatoTest` = configurazioni c1/c2 di oggi, `StrategiaR4Test` 12), `:ai:test` 13, `:proplus-autoplay:test` 38,
  `:game-headed:test` 94: 0 falliti.
- Prove locali (Europa 1940, senza chiave): c1n seme 1143 (12 round): incursioni su Mosca dal R4 (13, 19, 19, 7 di danno; Mosca produce
  8, 5, 3, 3 unita'), 2 bombardieri persi al R7, Mosca presa al R8. c2n semi 1142/1144 (14 round): snodo Gibilterra, sbarchi Morocco R5,
  Libya/Tunisia R7, Sicily R9, Southern France R14 (15 unita'); attese per tenuta; Sudafrica 3 unita' a turno; Eastern Persia presa;
  sbarramento su Trans-Jordan. Prima versione: bombardieri usati e persi da Hard prima di arrivare alla base (corretto: tutti trattenuti
  con una base in portata) e trasporti USA che tornavano al porto lasciando 30+ unita' ferme a Gibilterra (corretto: restano allo snodo).

#### Autoplay in CI (run 43120375671, commit 60a628a, tutto verde: 202 job, 200 partite [completa] senza chiave, semi 1141-1180, costo API 0)
Tutte e 200 le partite finite con un vincitore (0 stalli, round 10-52), durata media 9-12 min. Vittorie del lato che cambia con IC 95% di
Wilson, test esatto di Fisher contro (a) e contro la versione attuale (senza l'interruttore R4):

| Configurazione | Asse / Alleati vince | Quota del lato | p da (a) | p nuova vs attuale | Round vittoria mediana (XML) |
|---|---|---|---|---|---|
| a Hard / Hard | 11 / 29 | - | - | - | 21 (20) |
| c1 Asse impegno (R2c) | 18 / 22 | Asse 45% [31; 60] | 0,162 | - | 19 (18) |
| c1n Asse impegno + assedio | **29 / 11** | Asse **73% [57; 84]** | **< 0,001** | **0,022** | 18 (16) |
| c2 Alleati impegno (R3a) | 26 / 14 | Alleati 35% [22; 50] | **0,002 (peggio)** | - | 23 (22) |
| c2n Alleati R3a + alleati_r4 | 22 / 18 | Alleati 45% [31; 60] | **0,022 (peggio)** | 0,494 | 23 (21) |

- **Assedio (A)**: unico blocco che migliora oltre il rumore. Mosca cade 31/40 (R9) contro 12/40 in (a) e 22/40 in c1; Stalingrado 36/40;
  esercito russo R12 18,7 contro 44,0; reddito tedesco R12 52,9 contro 39,8; VC Asse R12 6,2 contro 5,0. Raid: 54 PU a partita in
  bombardieri e scorta, danno medio 8,1 al R6, Mosca produce 6,9 (R6) / 3,2 (R8) contro 8,4 / 6,7; bombardieri persi 0,1-0,3 per round.
  Unita' nelle fabbriche catturate 57 a partita (a 38). Italia: territori dell'Asse in Africa R8 2,7 + 2,0 (a 1,6 + 1,1), 18 PU in navi a
  partita, obiettivi italiani attivi piu' a lungo (Mediterraneo 10,5 round contro 8,3). Costo: Parigi/Roma/Berlino cadono meno (15/13/11
  su 40 contro 31/30/29) perche' la partita finisce prima. Effetto separato dei tre pezzi (A1 bersagli, A3 raid, A4 Italia) NON misurato.
- **R3a in partite complete e' peggio di Hard** (c2: Alleati 35% contro 73%), cosa non visibile a 12 round. La Germania sta meglio (R12
  52,8 di reddito contro 39,8): le unita' alleate trattenute alla base e gli sbarchi coordinati tolgono pressione.
- **alleati_r4 (B)**: +4 vittorie su c2, non distinguibile (p 0,49), ancora sotto Hard. Snodo: AFRICA 119 round, FRANCIA 12, **MISTO 0**,
  attese **881**; sbarchi 127 ma 866 unita' sbarcate contro 2.380 di c2; Sudafrica 1.274 unita' comprate (31 a partita), neutrali presi 51,
  sbarramento attivo 511 turni. Nord Africa all'Asse a zero dal R9; unita' britanniche "altrove" (Africa/Medio Oriente) R16 42,7 contro 18,9
  di (a), in Europa occidentale 3,7 contro 20,6: il blocco B sposta l'esercito britannico fuori dall'Europa. Gibilterra all'Asse 12/40.
- Gibilterra dell'Asse (A4 facoltativo) non fatta. Lettura XML: vittoria in media 1-2 round prima delle note.

### Step R5 ALLEATI RIFATTI — interruttore alleati_r5 per USA e Regno Unito (2026-11-12)

Vedi "Alleati rifatti (step R5)" e "Scelte di R5". File nuovi: `ai/proplus/AlleatiR5`, test `AlleatiR5Test` (11); autoplay
`ConfrontoR5` + test (4). Modificati: `SbarcoCoordinato`, `Programmi`, `Impegno`, `SnodoAlleati`, `Reddito`, `RegistroDecisioni`,
`StrategaInterruttori`, `ProPlusAi`, `stratega-llm.json` (`impegno.r5`), autoplay `Monitor` (+`r5`), `ConfrontoCompleta`, workflow
`[completa]` (4 configurazioni x semi 1181-1220). Hard e le altre voci invariate (golden Hard, `NudoHardTest` con "Stratega senza
chiave = Hard", `DiaryNeutralityTest`, `CampagnaOmbraTest`, test R3a/R4 = interruttore spento: verdi).
- Test locali: AI di game-core + `:proplus-autoplay` **639, 0 falliti**; `:ai:test` 13. Prove locali: e1 seme 1181 10 round, e3 5 round
  fino a vittoria, nessuna eccezione.

#### Autoplay in CI (run 43180663316, commit 63642a1, verde, 160 partite [completa] senza chiave, semi 1181-1220, costo API 0)
160/160 concluse con un vincitore (0 stalli). Vittorie (IC 95% Wilson, Fisher sulle vittorie alleate):

| Configurazione | Asse / Alleati | Quota del lato | p | Round vittoria mediana |
|---|---|---|---|---|
| a Hard / Hard | 15 / 25 | Alleati 63% [47; 76] | - | 22 |
| e1 Hard / Alleati R5 | 30 / 10 | Alleati **25% [14; 40]** | **0,001 contro a (peggio)** | 24 |
| e2 Asse impegno+assedio / Hard | 27 / 13 | Asse 68% [52; 80] | - | 19 |
| e3 Asse assedio / Alleati R5 | 23 / 17 | Alleati 43% [29; 58] | 0,489 contro e2 | 21 |

- **alleati_r5 peggiora gli Alleati** (e1 −15 vittorie alleate contro Hard vs Hard). Sbarcate in Europa occidentale a partita 83 (a 130),
  Italia e isole 16 (27); Roma cade 14/40 (28/40), Berlino 10/40 (26/40), Mosca 31/40 (19/40); Russia R12 esercito 20,5 / reddito 7,2
  (43,2 / 15,9). Tedeschi in Europa occidentale R12 66,6 (62,6): nessuna pressione in piu'.
- Programma USA: quota PU 35%, UK 14%; sbarchi USA del registro 191 (presi 168, tenuti 1 round 81/166), logoramento UK 89 (presi 74,
  tenuti 1 round 13/74); attese dello snodo 20 a partita (manca carico 476, manca vittoria 190, nessun bersaglio 143 su 40 partite).
  **Spola senza controllo del contrattacco: 19,8 trasporti e 23 unita' del programma persi in mare a partita** (trasporti USA persi
  17,1 contro 6,8 di a). Gibilterra snodo dal R1 in 40/40, cade all'Asse in 20/40 (R19); battaglie navali decise 8, sotto soglia 23.
  UK: mare del porto non difeso in 339 turni (8,5 a partita: trasporti vietati). B5 falsa dal R3 (mediana), 19,4 unita' in Sudafrica.
- **Errore del calcolatore** in 13 partite R5 su 80 (1 per partita, 0 in a ed e2): `ConcurrentBattleCalculator` "Error while trying to
  set GameData" (ConcurrentModificationException / OptionalDataException nella copia in background: la partita veniva scritta mentre il
  calcolatore la copiava). Partite comunque concluse. Correzione: in `ProPlusAi` attesa della copia (`attendiCalcolatore`) prima delle
  scritture dell'acquisto e del piazzamento, solo con `alleati_r5` acceso (commit 739d86a). Secondo tentativo: run del commit successivo (stessa configurazione, semi 1181-1220).

#### Secondo tentativo (run 43202113717, commit ef05652, 159/160 partite, semi 1181-1220, costo API 0)
Stessi semi della prima run (il seme fissa solo i dadi: le partite sono diverse). 1 partita fallita (e3 seme 1181, dopo 80 s:
`IllegalStateException: Can't calculate while operation is still running!`, causata dall'attesa del calcolatore di 739d86a).

| Configurazione | Asse / Alleati (stalli) | Quota del lato | p | Unione delle due run |
|---|---|---|---|---|
| a Hard / Hard | 15 / 25 | Alleati 63% [47; 76] | - | Alleati 50/80 = 63% [52; 72] |
| e1 Hard / Alleati R5 | 22 / 17 (1) | Alleati 43% [29; 58] | 0,117 contro a | Alleati 27/80 = 34% [25; 45], **p 0,0005 contro a (peggio)** |
| e2 Asse impegno+assedio / Hard | 26 / 14 | Asse 65% [50; 78] | - | Asse 53/80 |
| e3 Asse assedio / Alleati R5 | 28 / 10 (2, uno e' la partita fallita) | Alleati 25% [14; 40] | 0,465 contro e2 | Alleati 27/80 contro 27/80 di e2, p 1,0 |

- **La correzione del calcolatore NON funziona**: errore "Error while trying to set GameData" in 7/40 partite e1 e 5/39 e3 (prima run
  13/80), 0 in a ed e2; in piu' 1 partita interrotta. Causa vera NON trovata: la copia avviene anche durante fasi non coperte dalle
  attese (le eccezioni sono ConcurrentModificationException e NoSuchElementException nella copia in background).
- e1: sbarcate in Europa occidentale 82 a partita (a 131), Italia e isole 23 (24); Roma cade 20/40 (28/40), Berlino 18/40 (25/40),
  Mosca 24/40 (17/40); trasporti USA persi 13,5 (a 6,2), del programma 17,3 + 22,5 unita' perse in mare; tedeschi in Europa
  occidentale R16 75 (a 59). Quota PU del programma USA 38%, UK 11%; attese dello snodo 19 a partita (manca carico 465).
- Verdetto (due tentativi, regola dello step): **alleati_r5 peggiora gli Alleati contro Hard** (unione p 0,0005) e non cambia nulla
  contro l'Asse con assedio. Resta spento di default.

### Step R6 SQUADRA DI GIBILTERRA — punto 0 (calcolatore), squadra USA, quota dell'Asse per configurazione (2026-11-12)

Vedi "Squadra di Gibilterra (step R6)" e "Scelte di R6". Motivo (80 partite di R5, run del commit ef05652): USA a Gibilterra con 2 unita'
a R5, un cacciatorpediniere ogni due round, carico bilanciato bloccato a round alterni, battaglia del mare valutata 35 volte e combattuta 1;
71 unita' sbarcate in Europa occidentale a partita contro 131 di Hard, Alleati 34% contro 63%.
- **Punto 0, causa trovata** (log delle partite R5 scaricati dalla release `autoplay-latest`): lo stato di Pro+ e' scritto nelle proprieta'
  della partita (`GameProperties.set`: una `HashMap` e la lista `ordering`, senza il lock della partita) mentre il calcolatore copia la
  partita in background (`ConcurrentBattleCalculator.setGameData`, asincrono, avviato da ogni `prepareData`) ⇒
  `ConcurrentModificationException` in `ArrayList.writeObject` o copia corrotta (`OptionalDataException`, `NoSuchElementException` alla
  lettura). Con una copia corrotta un worker fallisce, lo stream parallelo restituisce l'eccezione con gli altri worker ancora in corsa, il
  mutex si libera e la stima successiva trova un worker occupato ⇒ "Can't calculate while operation is still running!" (la partita e3 seme
  1181 della seconda run R5). L'attesa di 739d86a copriva solo alcuni punti. Correzione in `ProPlusAi` (nessuna classe di Hard toccata).
- File nuovi: `ai/proplus/SquadraGibilterra`, test `SquadraGibilterraTest` (12); autoplay `ConfrontoR6` + `ConfrontoR6Test` (4). Modificati:
  `ProPlusAi`, `ProPlusStrategaAi` (punto 0), `Impegno` (quota D, R6 negli acquisti e nel piazzamento, esclusione degli altri Alleati),
  `AlleatiR5` (R6, metodi resi visibili nel pacchetto, sostegno), `SbarcoCoordinato`, `Programmi`, `ProPlusTuning`, `RegistroDecisioni`
  (tipi, motivi, PRESA), `StrategaInterruttori`, `stratega-llm.json` (`impegno.r6`); autoplay `AutoplayOptions`, `AutoplayRunner`,
  `Riepilogo`, `RiepilogoGenerale` (etichetta quota), `ConfrontoCompleta`, `ConfrontoR5` (metodi visibili nel pacchetto), workflow
  `[completa]` (5 configurazioni x semi 1221-1260).
- Prove locali (Europa 1940, senza chiave): f1 seme 1221 12 round: costruzione R1-R4 (35, 35, 35, 60 PU; al R1 prima della correzione
  della soglia di guardia Hard prendeva 12 PU per Central United States), squadra completa e in guerra al R4 ⇒ partenza e arrivo al R4,
  sbarchi dallo snodo su Southern France (8 unita', R5) e Normandy Bordeaux (8 e 10), spola con scarico a Gibilterra. Visti e corretti
  prima del push: `PRESA` fuori elenco (vedi sopra), scorta della spola rimasta a Gibilterra (blocco a round alterni), trasporti vuoti
  della squadra fermi accanto al bersaglio, attaccanti della battaglia senza il bonus del porto all'acquisto.

- Test locali: AI di game-core 609 (1 saltato), `:ai:test` 13, `:proplus-autoplay:test` 46, `:game-headed:test` 94: 0 falliti (golden Hard,
  `NudoHardTest` con "Stratega senza chiave = Hard", `DiaryNeutralityTest`, `CampagnaOmbraTest` ombra accesa = spenta compresi).

#### Autoplay in CI (run 43264777394, commit a991cec, 200/200 partite [completa] senza chiave, semi 1221-1260, costo API 0)
Tentativo 1: `DiaryNeutralityTest` instabile (gia' noto) ⇒ rilancio dei job falliti; tentativo 2: 199 partite (il job a seme 1242 ha
perso il runner a meta' partita, nessun log); tentativo 3: rilanciato solo quel job, `autoplay-latest` ripubblicata con 200 partite.
**Errori del calcolatore ("Error while trying to set GameData" / "Can't calculate"): 0 in 200 partite** (R5: 12-13 su 80). Tutte
concluse: 198 con un vincitore, 2 stalli al tetto di 60 round (Hard vs Hard, semi 1226 e 1241).

| Configurazione | Asse / Alleati (stalli) | Quota del lato [IC 95%] | Confronto, p (Fisher) | Round vittoria mediana |
|---|---|---|---|---|
| a Hard / Hard | 15 / 23 (2) | Alleati 58% [42; 71] | - | 21 |
| f1 Hard / Alleati squadra | 16 / 24 | Alleati 60% [45; 74] | f1 contro a: 1,000 | 23 |
| e2 Asse impegno+assedio (70) / Hard | 25 / 15 | Asse 63% [47; 76] | - | 18 |
| f3 Asse assedio / Alleati squadra | 21 / 19 | Alleati 48% [33; 63] | f3 contro e2: 0,498 | 20 |
| g Asse assedio quota 80 / Hard | 30 / 10 | Asse 75% [60; 86] | g contro e2: 0,335 | 18 |

- Squadra: arrivata a Gibilterra al R4 in 75/80 partite (R3 3, R5 2), composizione tipica 1 portaerei + 1 caccia, incrociatore,
  cacciatorpediniere, sottomarino, 4 trasporti carichi (8 unita'); corazzata MAI comprata (147 PU spesi su ~165 nei R1-4: dopo i trasporti
  e le altre navi non restano 20 PU). Gibilterra alleata da R5 in tutte; battaglie navali della squadra ~0 (il mare 91/92 e' quasi sempre
  libero), sbarchi per prenderla 0,1-0,2 a partita.
- Sbarchi del programma: ~8,5 ondate a partita (6,9 unita' per ondata), Europa occidentale 48 e Italia/isole 12 unita' a partita (f1);
  totale alleato in Europa occidentale 129 contro 117 di Hard. Russia meglio (esercito R12 44 contro 32; Mosca cade 17/40 contro 20/40).
  Tedeschi in Europa occidentale R12 70 contro 64.
- **Costo**: trasporti USA persi 22,8 a partita (Hard 8,9), del programma 27,4 trasporti e 28,7 unita' perse in mare; spola 864 PU a
  partita, sostegno quasi mai (vittoria nemica sulla squadra < 1%). Il guadagno di sbarchi e' pagato dalle perdite della spola.
- Quota dell'Asse 80 contro 70: +5 vittorie (non distinguibile, p 0,34), reddito finale Asse 69 contro 60.

### Step R6b TRASPORTI PROTETTI — regola unica per i trasporti, scorta delle ondate, rientro, spola, pannello (2026-11-12)

Vedi "Trasporti protetti (step R6b)" e "Scelte di R6b". Motivo (80 partite di R6, run 43264777394): 30,5 trasporti del programma persi a
partita, quasi tutti vuoti nelle zone di sbarco del Mediterraneo; squadra ferma nella 91; vuoti mai rientrati (650 "gruppo fermo in 91"
in 40 partite f1); quota PU dal R5 al 60%.
- File nuovi: `ai/proplus/TrasportiProtetti`, test `TrasportiProtettiTest` (11); autoplay `ConfrontoR6b` + risorsa `r6-riferimento.json`.
  Modificati: `AlleatiR5`, `SbarcoCoordinato`, `SquadraGibilterra`, `Programmi`, `Impegno`, `RegistroDecisioni`, `Stratega`,
  `ProfiloStratega`, `stratega-llm.json` (`impegno.r6b`), `StrategistPanel` (game-headed); autoplay `Monitor` (`attacco_subito`),
  `ConfrontoCompleta`; workflow `[completa]` (semi 1261-1300).
- Test locali: game-core 3252 (1 saltato, 1 fallito: `ContentDownloaderIntegrationTest`, scarica da internet, bloccato dal proxy della
  sandbox, non toccato), `:ai:test` 13, `:game-headed:test` 94, `:proplus-autoplay:test` 46: verdi (golden Hard, `NudoHardTest` con
  "Stratega senza chiave = Hard", `DiaryNeutralityTest`, `CampagnaOmbraTest` compresi).
- Prove locali (Europa 1940, senza chiave, f1 seme 1261): prima prova 10 round, nessun errore, trovato e corretto il difetto della quota A3
  (scelta 1); seconda prova 14 round: 5 ondate (Southern France x2, Sardinia, Sicily, Normandy Bordeaux), 13 controlli SICURA, 2 RINUNCIA,
  1 ZONA_CAMBIATA (Normandy Bordeaux da 110), 8 rientri dei vuoti verso la 91 (6 arrivati), 1 rientro al porto; trasporti USA persi 0 fino
  al R13 (R14: 3 trasporti in 98, non del programma); spola 3 coppie a turno (42 PU su ~50 del programma: la quarta coppia non entra nei PU).
