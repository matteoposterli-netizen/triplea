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
- Il tipo è risolto per **etichetta** (`PlayerTypes.fromLabel`): un nuovo tipo "Pro+" va aggiunto
  qui (probabile punto di aggancio per lo step successivo), con etichetta univoca.

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
  **solo** i file noti (jar via file `.tmp` + `Move-Item` atomico), riscrive `version.txt`.
  Qualsiasi errore di rete/API/zip ⇒ avviso + `exit 0` (fallback alla build locale).
- Self-update del lanciatore: `aggiorna.ps1` non sovrascrive a caldo `avvia.bat`/`aggiorna.ps1`
  (in uso); se cambiano li mette in `*.new` ed esce con **10**. `avvia.bat` genera uno script
  temporaneo in `%TEMP%` che attende la chiusura, applica i `*.new` e rilancia `avvia.bat`.
- Prima adozione: la build step-0 non ha `aggiorna.ps1`, quindi il primo passaggio richiede UN
  download manuale dello zip aggiornato; da lì in avanti è automatico.

Fatti verificati per avvia.bat / LEGGIMI:
- Installer ufficiale = install4j (`game-headed/build.install4j`), JRE Adoptium 25 incluso nella
  cartella `jre` dell'installazione (convenzione install4j), cartella default
  `C:\Program Files\TripleA`; unica opzione JVM aggiunta: `-Xss1250K` (nessun `-Xmx`).
- Dati utente condivisi con l'installazione ufficiale: `%USERPROFILE%\Documents\triplea` (o
  `%USERPROFILE%\triplea`) con `downloadedMaps`, `savedGames`, `savedGames\autoSave`;
  impostazioni in Java Preferences (registro HKCU\Software\JavaSoft\Prefs\games\strategy\...).

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
