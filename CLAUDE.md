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
- File specifici Pro+ fuori dal codice di gioco: `proplus/` e
  `.github/workflows/proplus-release.yml`.
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
- `ai/proplus/strategia/` (step 3a) — stato maggiore: `Strategia` (modello + parsing del file
  JSON), `Strategie` (indice gioco→file, cache, avvisi), `StatoMaggiore` (scelta e stato salvati
  nelle proprietà della partita, `fix`), `PianoAttivo` (ordini per nazione/round: fase, rischio per
  fronte, obiettivi). Dati: `game-core/src/main/resources/proplus/strategia/indice.json` +
  `ww2eur40_2nd_edition.json`. Vedi "Stato maggiore e piani".
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

Non esiste un runner CLI. Il modo supportato è `AiGameTest` in `:smoke-testing`
(`GameTestUtils.setUpGameWithAis(xml)` mette `PlayerTypes.PRO_AI` su tutti i giocatori e chiama
`game.runNextStep()` fino a vittoria/100 round). Le XML di test stanno in
`game-app/smoke-testing/src/test/resources/map-xmls/`. Per WW2 Europe 1940 servirà aggiungere un
test analogo che carichi `ww2eur40_2nd_edition.xml` (va reperita la mappa: non è nel repo).

## Release Pro+ (GitHub Action)

`.github/workflows/proplus-release.yml` — su push a `proplus` (o manuale):
0. job `powershell-tests` (runner **windows-latest**, gate del job `build`): esegue
   `proplus/ci/test-aggiorna.ps1`, che testa la logica di `aggiorna.ps1` con un server HTTP
   locale (`System.Net.HttpListener`) che simula la GitHub API + download (nessuna rete reale);
1. test: `:game-core:test :ai:test :game-headed:test` (smoke-testing escluso: scarica salvataggi
   esterni ed è lento; `./verify` escluso: include PMD/spotless/check custom non necessari qui);
2. scrive la versione (`set-game-headed-build-number`), `:game-headed:shadowJar`;
3. zip `TripleA-ProPlus.zip` = `TripleA-ProPlus.jar` + `avvia.bat` + `aggiorna.ps1` +
   `build-info.txt` + `LEGGIMI.txt` + `.triplea-root` (marcatore vuoto cercato da
   `ClientFileSystemHelper.getRootFolder()`);
4. sposta il tag `proplus-latest` sul commit e sostituisce l'asset nella release (prerelease).

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
  compito}, `tappe` [{`territorio`, `entro_round`}], `firma` [{`indizio`, `tipo`, `territori`,
  `zone`, `unita`, `nazione`, `peso`}], `punti_critici` [{`descrizione`, `tipo`, `territori`,
  `zone`, `unita`, `nazione`}]. Nello step 3a sono usati solo `fasi` (rischio, obiettivi); `ruoli`
  appare nel diario; `tappe`/`firma`/`punti_critici` sono letti ma non usati.
- Fronti Europe 1940: Ovest, Est, Mediterraneo-Africa, Mari (`zone_di_mare: true` = tutte le zone
  di mare), Altro. Piani: Asse A Barbarossa (Automatico), B Sea Lion, C Finta Sea Lion,
  D Sud/Mediterraneo, N Neutro (tutti 0, nessun obiettivo: per confronti); Alleati 1 Germania prima
  (Automatico; "Est −1 fino al R4 poi 0" interpretato come R1-3 −1, R4+ 0, obiettivi di sbarco dal
  R4), 2 Italia prima, 3 Pressione economica (tutti 0).
- Persistenza (solo `String` JDK ⇒ salvataggio apribile dalla TripleA ufficiale):
  `"Pro+ piano: <alleanza>"` = id scelto o `auto`; `"Pro+ stato maggiore: <alleanza>"` =
  `{"piano":"A","round_inizio":1,"storico":[]}`. Il piano si **fissa** (`StatoMaggiore.fix`) al
  primo turno Pro+ dell'alleanza (`ProPlusAi.logPhase`, sui dati reali) e poi non cambia: il
  pannello mostra la tendina bloccata "fissato al round N". `storico` = per round, vuoto nello
  step 3a (avanzamento, ipotesi sull'avversario, postura negli step successivi). Fase = round di
  gioco assoluto (non relativo a `round_inizio`).
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
- Cache: `ProPlusTuning.plan` per (GameData, giocatore, round); il livello del cursore è letto a
  ogni uso. `ProPlusAi` svuota la cache dopo aver fissato il piano.

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
