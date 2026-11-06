@echo off
rem ============================================================================
rem  TripleA Pro+ - avvio su Windows
rem  Cerca Java in quest'ordine:
rem    1) JRE incluso nell'installazione ufficiale di TripleA (cartella "jre")
rem    2) %JAVA_HOME%
rem    3) java nel PATH
rem  Serve Java 25 o superiore. Questo script legge soltanto: non modifica
rem  ne' sovrascrive l'installazione ufficiale di TripleA.
rem ============================================================================
setlocal EnableExtensions EnableDelayedExpansion
title TripleA Pro+

set "HERE=%~dp0"
set "JAR=%HERE%TripleA-ProPlus.jar"
set "MIN_JAVA=25"
set "JAVA_EXE="
set "JAVA_SRC="
set "JAVA_VER="

if not exist "!JAR!" (
  echo.
  echo [ERRORE] Non trovo TripleA-ProPlus.jar in questa cartella:
  echo          !HERE!
  echo          Scompatta tutto lo zip nella stessa cartella e riprova.
  echo.
  pause
  exit /b 1
)

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
  pause
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
  pause
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
