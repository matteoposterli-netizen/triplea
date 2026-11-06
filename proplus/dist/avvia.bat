@echo off
rem ============================================================================
rem  TripleA Pro+ - avvio su Windows (lanciatore sottile)
rem
rem  1) Delega l'aggiornamento automatico ad aggiorna.ps1 (PowerShell di
rem     Windows, nessun tool esterno da installare). Se c'e' una build piu'
rem     recente nella release "proplus-latest" del fork, viene scaricata e
rem     applicata; se rete/API non rispondono si avvia la build locale.
rem  2) Mostra la versione in uso (commit + data della build).
rem  3) Cerca Java 25+ (JRE di TripleA, JAVA_HOME, PATH) e avvia il gioco.
rem
rem  Questo script LEGGE soltanto l'installazione ufficiale di TripleA: non la
rem  modifica ne' la sovrascrive. I dati utente (salvataggi, mappe,
rem  impostazioni) NON stanno in questa cartella e non vengono toccati.
rem ============================================================================
setlocal EnableExtensions EnableDelayedExpansion
title TripleA Pro+

set "HERE=%~dp0"
set "JAR=%HERE%TripleA-ProPlus.jar"
set "MIN_JAVA=25"
set "JAVA_EXE="
set "JAVA_SRC="
set "JAVA_VER="

rem Solo per i test CI: PROPLUS_TEST_NOLAUNCH=1 salta le pause, il riavvio del
rem lanciatore e l'avvio di Java. Senza la variabile nulla cambia per l'utente.
set "NOLAUNCH="
if "%PROPLUS_TEST_NOLAUNCH%"=="1" set "NOLAUNCH=1"

rem --- 0) Aggiornamento automatico -------------------------------------------
rem La cartella di installazione viene passata ESPLICITAMENTE: in Windows
rem PowerShell 5.1 il default $PSScriptRoot di aggiorna.ps1 puo' risultare vuoto
rem con -File. La variabile HERE finisce con "\" che, davanti alla virgoletta
rem di chiusura, la rovinerebbe: per questo si aggiunge ".". Esce con 10 se gli script di
rem avvio sono cambiati e vanno applicati con un riavvio (vedi sotto); in ogni
rem altro caso esce con 0.
if exist "%HERE%aggiorna.ps1" (
  where powershell.exe >nul 2>&1
  if !ERRORLEVEL! EQU 0 (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%HERE%aggiorna.ps1" -InstallDir "%HERE%."
    set "UPD=!ERRORLEVEL!"
  ) else (
    echo   [avviso] PowerShell non trovato: salto il controllo aggiornamenti.
    set "UPD=0"
  )
) else (
  set "UPD=0"
)

rem --- 0b) Se gli script di avvio sono stati aggiornati, applicali e riavvia --
rem avvia.bat e aggiorna.ps1 sono "in uso" mentre girano: aggiorna.ps1 ha messo
rem le versioni nuove in *.new. Un piccolo script temporaneo attende la chiusura
rem di questa finestra, sostituisce i file e rilancia avvia.bat. Cosi' avvia.bat
rem non si corrompe durante l'aggiornamento di se stesso.
if "!UPD!"=="10" if defined NOLAUNCH (
  echo [test] aggiornamento del lanciatore richiesto: riavvio saltato.
  exit /b 10
)
if "!UPD!"=="10" (
  set "SWAP=%TEMP%\triplea-proplus-swap-%RANDOM%%RANDOM%.cmd"
  (
    echo @echo off
    echo ping -n 3 127.0.0.1 ^>nul
    echo if exist "%HERE%avvia.bat.new" move /y "%HERE%avvia.bat.new" "%HERE%avvia.bat" ^>nul 2^>^&1
    echo if exist "%HERE%aggiorna.ps1.new" move /y "%HERE%aggiorna.ps1.new" "%HERE%aggiorna.ps1" ^>nul 2^>^&1
    echo start "" "%HERE%avvia.bat"
    echo del "%%~f0" ^>nul 2^>^&1
  ) > "!SWAP!"
  echo.
  echo Applico l'aggiornamento del lanciatore e riavvio...
  start "" cmd /c "!SWAP!"
  exit /b 0
)

rem --- 0c) Versione in uso ----------------------------------------------------
set "BUILD_COMMIT="
set "BUILD_DATE="
if exist "%HERE%build-info.txt" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%HERE%build-info.txt") do (
    if /i "%%A"=="commit" set "BUILD_COMMIT=%%B"
    if /i "%%A"=="date" set "BUILD_DATE=%%B"
  )
)
set "BUILD_COMMIT_SHORT=sconosciuto"
if defined BUILD_COMMIT (
  set "BUILD_COMMIT_SHORT=!BUILD_COMMIT:~0,7!"
  rem Build di sviluppo non passata dalla Action: placeholder non sostituito.
  if "!BUILD_COMMIT:~0,1!"=="@" set "BUILD_COMMIT_SHORT=sconosciuto"
)
if not defined BUILD_DATE set "BUILD_DATE=sconosciuta"
if "!BUILD_DATE:~0,1!"=="@" set "BUILD_DATE=sconosciuta"

echo.
echo ----------------------------------------------------------------------------
echo  Versione in uso : commit !BUILD_COMMIT_SHORT!   (build del !BUILD_DATE!)
echo ----------------------------------------------------------------------------

if not exist "!JAR!" (
  echo.
  echo [ERRORE] Non trovo TripleA-ProPlus.jar in questa cartella:
  echo          !HERE!
  echo          Scompatta tutto lo zip nella stessa cartella e riprova.
  echo.
  if not defined NOLAUNCH pause
  exit /b 1
)

if defined NOLAUNCH (
  echo.
  echo [test] PROPLUS_TEST_NOLAUNCH=1: Java non avviato.
  exit /b 0
)

echo.
echo Ricerca di Java %MIN_JAVA%+ in corso...
echo.

rem --- 1) JRE dell'installazione ufficiale di TripleA ------------------------
call :try "%ProgramFiles%\TripleA\jre\bin\java.exe" "JRE dell'installazione TripleA"
call :try "%ProgramW6432%\TripleA\jre\bin\java.exe" "JRE dell'installazione TripleA"
call :try "%ProgramFiles(x86)%\TripleA\jre\bin\java.exe" "JRE dell'installazione TripleA"
call :try "%LOCALAPPDATA%\Programs\TripleA\jre\bin\java.exe" "JRE dell'installazione TripleA"

rem Installazione in una cartella diversa: la cerca nel registro di Windows
rem (voce "Programmi e funzionalita'" scritta dall'installer di TripleA).
if not defined JAVA_EXE call :searchRegistry

rem Varianti con nome diverso (es. "C:\Program Files\TripleA_2026-11...").
if not defined JAVA_EXE for /d %%D in ("%ProgramFiles%\TripleA*") do call :try "%%~fD\jre\bin\java.exe" "JRE dell'installazione TripleA"
if not defined JAVA_EXE for /d %%D in ("%LOCALAPPDATA%\Programs\TripleA*") do call :try "%%~fD\jre\bin\java.exe" "JRE dell'installazione TripleA"

rem --- 2) JAVA_HOME -----------------------------------------------------------
if not defined JAVA_EXE if defined JAVA_HOME call :try "%JAVA_HOME%\bin\java.exe" "JAVA_HOME"

rem --- 3) java nel PATH -------------------------------------------------------
if not defined JAVA_EXE for /f "delims=" %%P in ('where java.exe 2^>nul') do call :try "%%~fP" "PATH"

if not defined JAVA_EXE (
  echo.
  echo [ERRORE] Non ho trovato Java %MIN_JAVA% o superiore.
  echo.
  echo  Ho cercato:
  echo   1. il JRE dell'installazione ufficiale di TripleA
  echo      ^(es. C:\Program Files\TripleA\jre\bin\java.exe^)
  echo   2. la variabile JAVA_HOME
  echo   3. il comando java nel PATH
  echo.
  echo  Soluzioni: installa TripleA ufficiale ^(include Java^), oppure installa
  echo  Java %MIN_JAVA% da https://adoptium.net e riavvia questo file.
  echo.
  if not defined NOLAUNCH pause
  exit /b 1
)

echo.
echo ============================================================================
echo  Java usato : !JAVA_EXE!
echo  Trovato in : !JAVA_SRC!
echo  Versione   :
"!JAVA_EXE!" -version 2>&1
echo ============================================================================
echo.
echo Avvio di TripleA Pro+ ...
echo (questa finestra mostra il log del gioco; si chiude quando chiudi il gioco)
echo.

rem Stesse opzioni JVM del launcher ufficiale (installer install4j: -Xss1250K).
"!JAVA_EXE!" -Xss1250K -jar "!JAR!" %*
set "EXITCODE=!ERRORLEVEL!"

if not "!EXITCODE!"=="0" (
  echo.
  echo [ATTENZIONE] TripleA Pro+ si e' chiuso con codice di errore !EXITCODE!.
  echo Copia il testo qui sopra se vuoi segnalare il problema.
  echo.
  if not defined NOLAUNCH pause
)
exit /b !EXITCODE!


rem ============================================================================
rem  :try "percorso\java.exe" "descrizione"
rem  Se il file esiste ed e' Java >= MIN_JAVA, lo sceglie.
rem ============================================================================
:try
if defined JAVA_EXE goto :eof
set "CAND=%~1"
if "%CAND%"=="" goto :eof
if not exist "%CAND%" goto :eof
set "VER="
for /f "tokens=2 delims==" %%V in ('call "%CAND%" -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.specification.version"') do set "VER=%%V"
if not defined VER (
  echo   [scartato] !CAND! - versione non leggibile
  goto :eof
)
set "VER=!VER: =!"
set "MAJOR="
for /f "tokens=1 delims=." %%M in ("!VER!") do set "MAJOR=%%M"
set /a MAJOR_NUM=MAJOR 2>nul
if !MAJOR_NUM! LSS %MIN_JAVA% (
  echo   [scartato] !CAND! - Java !VER!, serve %MIN_JAVA%+
  goto :eof
)
set "JAVA_EXE=!CAND!"
set "JAVA_SRC=%~2"
set "JAVA_VER=!VER!"
echo   [ok]       !CAND! - Java !VER!
goto :eof


rem ============================================================================
rem  :searchRegistry - cerca InstallLocation di TripleA tra i programmi installati
rem ============================================================================
:searchRegistry
for %%R in (
  "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall"
  "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall"
  "HKLM\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"
) do (
  for /f "delims=" %%K in ('reg query %%R /s /f "TripleA" /d 2^>nul ^| findstr /b /c:"HKEY_"') do (
    for /f "tokens=2,*" %%A in ('reg query "%%K" /v InstallLocation 2^>nul ^| findstr /i /c:"InstallLocation"') do (
      call :try "%%B\jre\bin\java.exe" "JRE dell'installazione TripleA (registro)"
    )
  )
)
goto :eof
