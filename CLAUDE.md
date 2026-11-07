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
  modifica. `concurrency` di `proplus-release` spostata sul job `build` (un'altra release non aspetta
  le partite). **`workflow_run` e `workflow_dispatch` non funzionano**: GitHub li accetta solo per
  workflow presenti sul branch di default (`main` = upstream, non si tocca): verificato, `gh workflow
  run` ⇒ "HTTP 404: workflow proplus-autoplay.yml not found on the default branch".
  `workflow_dispatch` (input `round`, `partite` = prime N configurazioni di A/1, A/2, D/1, D/2, C/1,
  Hard, `semi` = semi per configurazione (2, max 4), `piani` = es. `A/1,B/2,hard`) resta nel file per quando/se il file sarà su `main`. Ripetere a mano oggi:
  Actions → run di "ProPlus Release" → "Re-run all jobs" (rifà anche la release).
- Job: `prepare` (installDist + XML della mappa + matrice, artefatto `autoplay-kit`), `play` (matrice,
  max-parallel 6, fail-fast off, timeout 60 min, `--minuti 52`, semi 1..N **uguali per ogni
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
- Download: `https://github.com/matteoposterli-netizen/triplea/releases/download/autoplay-latest/autoplay.zip`.

## Release Pro+ (GitHub Action)

`.github/workflows/proplus-release.yml` — su push a `proplus` (o manuale):
0. job `powershell-tests` (runner **windows-latest**, gate del job `build`): esegue
   `proplus/ci/test-aggiorna.ps1`, che testa la logica di `aggiorna.ps1` con un server HTTP
   locale (`System.Net.HttpListener`) che simula la GitHub API + download (nessuna rete reale);
   `Start-MockServer` attende il server con ping ogni 250 ms fino a 90 s e riavvia il job (max 3)
   se termina prima di rispondere (step AUTOPLAY: una volta era fallito "Mock server non pronto");
1. test: `:game-core:test :ai:test :game-headed:test :proplus-autoplay:test` (smoke-testing escluso: scarica salvataggi
   esterni ed è lento; `./verify` escluso: include PMD/spotless/check custom non necessari qui);
2. scrive la versione (`set-game-headed-build-number`), `:game-headed:shadowJar`;
3. zip `TripleA-ProPlus.zip` = `TripleA-ProPlus.jar` + `avvia.bat` + `aggiorna.ps1` +
   `build-info.txt` + `LEGGIMI.txt` + `.triplea-root` (marcatore vuoto cercato da
   `ClientFileSystemHelper.getRootFolder()`);
4. sposta il tag `proplus-latest` sul commit e sostituisce l'asset nella release (prerelease);
5. job `autoplay` (dopo la release, workflow riutilizzabile): partite AI vs AI, vedi "Autoplay in CI".

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
  `punti_critici` [{`descrizione`, `tipo`, `territori`, `zone`, `unita`, `nazione`}], `logistica`
  (step 3b, vedi "Logistica del piano"). Usati: `fasi` (rischio, obiettivi), `logistica`; `ruoli`
  appare nel diario; `tappe`/`firma` solo osservazione (step 3c); `punti_critici` letti ma non
  usati.
- Fronti Europe 1940: Ovest, Est, Mediterraneo-Africa, Mari (`zone_di_mare: true` = tutte le zone
  di mare), Altro. Piani: Asse A Barbarossa (Automatico), B Sea Lion, C Finta Sea Lion,
  D Sud/Mediterraneo, N Neutro (tutti 0, nessun obiettivo, nessuna logistica: per confronti); B
  dal R4 = fase R3+ di Barbarossa (step 3b); Alleati 1 Germania prima
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
