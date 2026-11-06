#Requires -Version 5.1
<#
  Test della logica di aggiornamento di proplus/dist/aggiorna.ps1 su un runner
  Windows reale (eseguito dal job "powershell-tests" del workflow ProPlus Release).

  Nessuna dipendenza esterna (niente Pester): usa un piccolo server HTTP locale
  (System.Net.HttpListener) per simulare la GitHub API e il download dello zip,
  cosi' i test non toccano la rete reale e non servono token.

  Scenari coperti:
    1. Aggiornamento disponibile          -> scarica, verifica, sostituisce il jar
    2. Gia' aggiornato                     -> nessun download, usa la build locale
    3. API irraggiungibile (offline)       -> fallback: esce 0, build locale intatta
    4. Zip scaricato non valido            -> fallback: build locale intatta
    5. Lanciatore cambiato                 -> mette da parte avvia.bat.new, esce 10
    6. Avvio di un jar con timeout         -> verifica il meccanismo di avvio su Windows
    7. Invocazione REALE di aggiorna.ps1   -> powershell.exe -File SENZA -InstallDir,
                                              da un'altra working directory
    8. End-to-end avvia.bat via cmd.exe    -> installazione "vecchia" senza version.txt:
                                              scarica, aggiorna, scrive version.txt
    9. Percorso con spazi e parentesi      -> come 7 e 8 in "...\con spazi\TripleA Pro+ (1)"

  Gli scenari 7-9 riproducono l'uso reale (bug in produzione: con -File e senza
  -InstallDir il default $PSScriptRoot risultava vuoto in Windows PowerShell 5.1).
  avvia.bat gira con PROPLUS_TEST_NOLAUNCH=1 (niente pause/Java) e
  PROPLUS_TEST_APIBASE che punta al server finto.

  Lo script ritorna 0 se tutti i test passano, 1 altrimenti.
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$repoRoot    = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$aggiornaPs1 = Join-Path $repoRoot 'proplus/dist/aggiorna.ps1'
$avviaBat    = Join-Path $repoRoot 'proplus/dist/avvia.bat'
if (-not (Test-Path $aggiornaPs1)) { throw "aggiorna.ps1 non trovato in $aggiornaPs1" }

$script:pass = 0
$script:fail = 0
function Check {
  param([bool]$Condition, [string]$Message)
  if ($Condition) { $script:pass++; Write-Host "  [PASS] $Message" -ForegroundColor Green }
  else            { $script:fail++; Write-Host "  [FAIL] $Message" -ForegroundColor Red }
}

$work = Join-Path ([IO.Path]::GetTempPath()) ("proplus-tests-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null

# --- helper -----------------------------------------------------------------

# Copia con fine riga CRLF, come avvia.bat/aggiorna.ps1 nello zip pubblicato
# (cmd.exe e' sensibile ai fine riga dei .bat). Senza BOM, byte per byte stabile.
function Copy-Crlf {
  param([string]$Source, [string]$Destination)
  $text = [IO.File]::ReadAllText($Source)
  $text = ($text -replace "`r?`n", "`r`n")
  [IO.File]::WriteAllText($Destination, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function New-Install {
  param([string]$Name, [string]$Commit, [string]$JarText, [string]$UpdatedAt)
  $dir = Join-Path $work $Name
  New-Item -ItemType Directory -Path $dir -Force | Out-Null
  Set-Content -LiteralPath (Join-Path $dir 'TripleA-ProPlus.jar') -Value $JarText -Encoding ASCII
  @(
    "version=2026-11.test.1",
    "commit=$Commit",
    "date=2026-01-01 00:00 UTC",
    "repo=octo/test"
  ) -join "`r`n" | Set-Content -LiteralPath (Join-Path $dir 'build-info.txt') -Encoding ASCII
  Copy-Crlf $aggiornaPs1 (Join-Path $dir 'aggiorna.ps1')
  Copy-Crlf $avviaBat    (Join-Path $dir 'avvia.bat')
  if ($UpdatedAt) {
    @(
      "asset_id=1000",
      "updated_at=$UpdatedAt",
      "commit=$Commit"
    ) -join "`r`n" | Set-Content -LiteralPath (Join-Path $dir 'version.txt') -Encoding ASCII
  }
  return $dir
}

function New-ReleaseZip {
  param([string]$Path, [string]$Commit, [string]$JarText, [string]$LauncherExtra)
  $stage = Join-Path $work ("zipstage-" + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $stage -Force | Out-Null
  Set-Content -LiteralPath (Join-Path $stage 'TripleA-ProPlus.jar') -Value $JarText -Encoding ASCII
  @(
    "version=2026-11.test.2",
    "commit=$Commit",
    "date=2026-02-02 12:00 UTC",
    "repo=octo/test"
  ) -join "`r`n" | Set-Content -LiteralPath (Join-Path $stage 'build-info.txt') -Encoding ASCII
  'LEGGIMI di prova' | Set-Content -LiteralPath (Join-Path $stage 'LEGGIMI.txt') -Encoding ASCII
  '' | Set-Content -LiteralPath (Join-Path $stage '.triplea-root') -Encoding ASCII
  Copy-Crlf $aggiornaPs1 (Join-Path $stage 'aggiorna.ps1')
  # avvia.bat: copia byte-identica all'installato (stesso metodo), salvo quando
  # LauncherExtra forza una differenza per il test di staging del lanciatore.
  $stageBat = Join-Path $stage 'avvia.bat'
  Copy-Crlf $avviaBat $stageBat
  if ($LauncherExtra) { Add-Content -LiteralPath $stageBat -Value "rem $LauncherExtra" }
  if (Test-Path $Path) { Remove-Item $Path -Force }
  Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $Path -Force
  return (Get-Item $Path).Length
}

# Un solo GetContextAsync pendente per iterazione, atteso via polling senza
# abbandonarlo: cosi' nessuna richiesta viene consegnata a un accept abbandonato
# (che non leggeremmo mai, lasciando il client in timeout). Il polling a 50ms
# mantiene il job interrompibile da Stop-Job e la deadline lo auto-termina.
$serverScript = {
  param($Port, $Json, $ZipPath, $MaxSeconds)
  $listener = New-Object System.Net.HttpListener
  $listener.Prefixes.Add("http://127.0.0.1:$Port/")
  $listener.Start()
  $deadline = (Get-Date).AddSeconds($MaxSeconds)
  try {
    while ((Get-Date) -lt $deadline) {
      $task = $listener.GetContextAsync()
      while (-not $task.IsCompleted -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 50 }
      if (-not $task.IsCompleted) { break }
      $ctx = $task.Result
      $res = $ctx.Response
      try {
        $path = $ctx.Request.Url.AbsolutePath
        if ($path -like '*/ping') {
          $bytes = [Text.Encoding]::ASCII.GetBytes('ok'); $res.ContentType = 'text/plain'
        } elseif ($path -like '*/releases/tags/*') {
          $bytes = [Text.Encoding]::UTF8.GetBytes($Json); $res.ContentType = 'application/json'
        } elseif ($path -like '*/download/*') {
          $bytes = [IO.File]::ReadAllBytes($ZipPath); $res.ContentType = 'application/zip'
        } else {
          $res.StatusCode = 404; $bytes = [Text.Encoding]::ASCII.GetBytes('nf')
        }
        $res.ContentLength64 = $bytes.Length
        $res.OutputStream.Write($bytes, 0, $bytes.Length)
      } catch { } finally { $res.OutputStream.Close() }
    }
  } finally { try { $listener.Stop() } catch { } }
}

function Start-MockServer {
  param([int]$Port, [string]$Json, [string]$ZipPath)
  $job = Start-Job -ScriptBlock $serverScript -ArgumentList $Port, $Json, $ZipPath, 60
  # Attendi che il server risponda prima di procedere.
  $ready = $false
  for ($i = 0; $i -lt 50 -and -not $ready; $i++) {
    Start-Sleep -Milliseconds 200
    try {
      $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/ping" -TimeoutSec 3 -UseBasicParsing
      if ($r.StatusCode -eq 200) { $ready = $true }
    } catch { }
  }
  if (-not $ready) {
    Receive-Job $job -ErrorAction SilentlyContinue | Out-Host
    Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
    throw "Mock server non pronto sulla porta $Port"
  }
  return $job
}

function Stop-MockServer {
  param($Job)
  if ($Job) { Stop-Job $Job -ErrorAction SilentlyContinue; Remove-Job $Job -Force -ErrorAction SilentlyContinue }
}

function New-ReleaseJson {
  param([int]$Port, [string]$UpdatedAt, [int]$AssetId, [string]$Commit, [long]$Size)
  $obj = [ordered]@{
    tag_name = 'proplus-latest'
    body     = "TripleA Pro+ build 2026-11.test - commit $Commit (branch proplus)"
    assets   = @(
      [ordered]@{
        name                 = 'TripleA-ProPlus.zip'
        id                   = $AssetId
        updated_at           = $UpdatedAt
        size                 = $Size
        browser_download_url = "http://127.0.0.1:$Port/download/TripleA-ProPlus.zip"
      }
    )
  }
  return ($obj | ConvertTo-Json -Depth 6)
}

function Invoke-Aggiorna {
  param([string]$Dir, [string]$ApiBase)
  $psArgs = @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $aggiornaPs1,
    '-InstallDir', $Dir, '-Repo', 'octo/test', '-ApiBase', $ApiBase, '-TimeoutSec', '30'
  )
  $p = Start-Process -FilePath 'powershell.exe' -ArgumentList $psArgs -Wait -PassThru -NoNewWindow
  return $p.ExitCode
}

# Esegue un comando come lo fa l'utente (processo separato, working directory
# diversa dall'installazione), con le variabili d'ambiente solo-test, e
# restituisce exit code, stdout e stderr.
function Invoke-Real {
  param([string]$FilePath, [string]$Arguments, [string]$ApiBase, [string]$Label)
  $wd  = Join-Path $work 'working-dir-altrove'
  New-Item -ItemType Directory -Path $wd -Force | Out-Null
  $out = Join-Path $work "$Label-stdout.txt"
  $err = Join-Path $work "$Label-stderr.txt"
  $env:PROPLUS_TEST_APIBASE  = $ApiBase
  $env:PROPLUS_TEST_NOLAUNCH = '1'
  try {
    $p = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $wd `
      -Wait -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError $err
  } finally {
    Remove-Item Env:PROPLUS_TEST_APIBASE, Env:PROPLUS_TEST_NOLAUNCH -ErrorAction SilentlyContinue
  }
  $r = [pscustomobject]@{ Code = $p.ExitCode; Out = (Read-Text $out); Err = (Read-Text $err); WorkDir = $wd }
  Write-Host "  --- output ($Label) ---"
  Write-Host $r.Out
  if ($r.Err) { Write-Host "  --- stderr ($Label) ---"; Write-Host $r.Err }
  return $r
}

# Uso reale di avvia.bat: powershell.exe -File <percorso>, nessun altro parametro.
function Invoke-AggiornaReal {
  param([string]$Dir, [string]$ApiBase, [string]$Label)
  $ps1 = Join-Path $Dir 'aggiorna.ps1'
  return Invoke-Real -FilePath 'powershell.exe' -Arguments "-NoProfile -ExecutionPolicy Bypass -File `"$ps1`"" -ApiBase $ApiBase -Label $Label
}

# Come il doppio click: cmd.exe esegue avvia.bat.
function Invoke-AvviaBat {
  param([string]$Dir, [string]$ApiBase, [string]$Label)
  $bat = Join-Path $Dir 'avvia.bat'
  return Invoke-Real -FilePath 'cmd.exe' -Arguments "/d /s /c `"`"$bat`"`"" -ApiBase $ApiBase -Label $Label
}

# Verifiche comuni: aggiornamento riuscito su installazione "vecchia" senza version.txt.
function Check-RealUpdate {
  param($Result, [string]$Dir, [string]$What)
  Check ($Result.Code -eq 0) "$What : esce con 0 (ricevuto $($Result.Code))"
  Check (-not ($Result.Err -match 'Cannot bind|ParameterArgumentValidation|FullyQualifiedErrorId|CategoryInfo')) "$What : nessun errore rosso di PowerShell"
  Check (-not ($Result.Out -match '\[avviso\]')) "$What : nessun avviso (aggiornamento non saltato)"
  Check ($Result.Out -match 'Aggiornato alla build commit bbbbbbb') "$What : messaggio di aggiornamento riuscito"
  Check ((Read-Text (Join-Path $Dir 'TripleA-ProPlus.jar')).Trim() -eq 'JAR-NEW') "$What : jar sostituito con la nuova build"
  Check ((Read-Text (Join-Path $Dir 'version.txt')) -match 'updated_at=2026-07-07T07:00:00Z') "$What : version.txt scritto nella cartella di installazione"
  Check (-not (Test-Path (Join-Path $Result.WorkDir 'version.txt'))) "$What : nulla scritto nella working directory"
  Check (-not (Test-Path (Join-Path $Dir 'avvia.bat.new')) -and -not (Test-Path (Join-Path $Dir 'aggiorna.ps1.new'))) "$What : nessun .new (lanciatore invariato)"
}

# Installazione "vecchia" come quella di Matteo (commit diverso, niente version.txt)
# + release finta piu' recente; esegue $Runner e verifica l'aggiornamento.
function Test-RealScenario {
  param([string]$Name, [string]$Label, [scriptblock]$Runner, [string]$What)
  $dir  = New-Install -Name $Name -Commit 'aaaaaaa0000' -JarText 'JAR-OLD' -UpdatedAt $null
  $zip  = Join-Path $work "$Label.zip"
  $size = New-ReleaseZip -Path $zip -Commit 'bbbbbbb1111' -JarText 'JAR-NEW'
  $port = Get-Port
  $json = New-ReleaseJson -Port $port -UpdatedAt '2026-07-07T07:00:00Z' -AssetId 5000 -Commit 'bbbbbbb1111' -Size $size
  $job  = Start-MockServer -Port $port -Json $json -ZipPath $zip
  try { $r = & $Runner $dir "http://127.0.0.1:$port" $Label } finally { Stop-MockServer $job }
  Check-RealUpdate -Result $r -Dir $dir -What $What
  return $r
}

function Get-Port { return (Get-Random -Minimum 49200 -Maximum 64800) }
function Read-Text { param([string]$Path) if (Test-Path $Path) { return (Get-Content -LiteralPath $Path -Raw) } else { return '' } }

# --- Scenario 1: aggiornamento disponibile ----------------------------------
Write-Host "`n== Scenario 1: aggiornamento disponibile =="
try {
  $dir  = New-Install -Name 's1' -Commit 'aaaaaaa0000' -JarText 'JAR-OLD' -UpdatedAt '2026-01-01T00:00:00Z'
  $zip  = Join-Path $work 's1.zip'
  $size = New-ReleaseZip -Path $zip -Commit 'bbbbbbb1111' -JarText 'JAR-NEW'
  $port = Get-Port
  $json = New-ReleaseJson -Port $port -UpdatedAt '2026-02-02T12:00:00Z' -AssetId 2000 -Commit 'bbbbbbb1111' -Size $size
  $job  = Start-MockServer -Port $port -Json $json -ZipPath $zip
  $code = Invoke-Aggiorna -Dir $dir -ApiBase "http://127.0.0.1:$port"
  Stop-MockServer $job
  Check ($code -eq 0) "esce con 0 (ricevuto $code)"
  Check ((Read-Text (Join-Path $dir 'TripleA-ProPlus.jar')).Trim() -eq 'JAR-NEW') "il jar e' stato sostituito con la nuova build"
  Check ((Read-Text (Join-Path $dir 'build-info.txt')) -match 'bbbbbbb1111') "build-info.txt aggiornato al nuovo commit"
  Check ((Read-Text (Join-Path $dir 'version.txt')) -match '2026-02-02T12:00:00Z') "version.txt registra il nuovo updated_at"
} catch { $script:fail++; Write-Host "  [FAIL] eccezione: $($_.Exception.Message)" -ForegroundColor Red }

# --- Scenario 2: gia' aggiornato --------------------------------------------
Write-Host "`n== Scenario 2: gia' aggiornato =="
try {
  $dir  = New-Install -Name 's2' -Commit 'ccccccc2222' -JarText 'JAR-KEEP' -UpdatedAt '2026-03-03T09:00:00Z'
  $zip  = Join-Path $work 's2.zip'
  $size = New-ReleaseZip -Path $zip -Commit 'ccccccc2222' -JarText 'JAR-REMOTE'
  $port = Get-Port
  $json = New-ReleaseJson -Port $port -UpdatedAt '2026-03-03T09:00:00Z' -AssetId 1000 -Commit 'ccccccc2222' -Size $size
  $job  = Start-MockServer -Port $port -Json $json -ZipPath $zip
  $code = Invoke-Aggiorna -Dir $dir -ApiBase "http://127.0.0.1:$port"
  Stop-MockServer $job
  Check ($code -eq 0) "esce con 0 (ricevuto $code)"
  Check ((Read-Text (Join-Path $dir 'TripleA-ProPlus.jar')).Trim() -eq 'JAR-KEEP') "nessun download: il jar locale resta invariato"
} catch { $script:fail++; Write-Host "  [FAIL] eccezione: $($_.Exception.Message)" -ForegroundColor Red }

# --- Scenario 3: API irraggiungibile (offline) ------------------------------
Write-Host "`n== Scenario 3: offline / API irraggiungibile =="
try {
  $dir  = New-Install -Name 's3' -Commit 'ddddddd3333' -JarText 'JAR-OFFLINE' -UpdatedAt '2026-01-01T00:00:00Z'
  $port = Get-Port   # nessun server in ascolto su questa porta
  $code = Invoke-Aggiorna -Dir $dir -ApiBase "http://127.0.0.1:$port"
  Check ($code -eq 0) "esce comunque con 0 in assenza di rete (ricevuto $code)"
  Check ((Read-Text (Join-Path $dir 'TripleA-ProPlus.jar')).Trim() -eq 'JAR-OFFLINE') "la build locale resta disponibile e intatta"
} catch { $script:fail++; Write-Host "  [FAIL] eccezione: $($_.Exception.Message)" -ForegroundColor Red }

# --- Scenario 4: zip scaricato non valido -----------------------------------
Write-Host "`n== Scenario 4: zip scaricato non valido =="
try {
  $dir  = New-Install -Name 's4' -Commit 'eeeeeee4444' -JarText 'JAR-GOOD' -UpdatedAt '2026-01-01T00:00:00Z'
  $bad  = Join-Path $work 's4-bad.zip'
  Set-Content -LiteralPath $bad -Value 'questo non e un file zip valido' -Encoding ASCII
  $size = (Get-Item $bad).Length
  $port = Get-Port
  $json = New-ReleaseJson -Port $port -UpdatedAt '2026-05-05T05:00:00Z' -AssetId 3000 -Commit 'fffffff5555' -Size $size
  $job  = Start-MockServer -Port $port -Json $json -ZipPath $bad
  $code = Invoke-Aggiorna -Dir $dir -ApiBase "http://127.0.0.1:$port"
  Stop-MockServer $job
  Check ($code -eq 0) "esce con 0 e non si blocca su uno zip corrotto (ricevuto $code)"
  Check ((Read-Text (Join-Path $dir 'TripleA-ProPlus.jar')).Trim() -eq 'JAR-GOOD') "il jar locale non viene toccato da uno zip non valido"
  Check (-not ((Read-Text (Join-Path $dir 'version.txt')) -match '2026-05-05')) "version.txt non viene aggiornato se l'aggiornamento fallisce"
} catch { $script:fail++; Write-Host "  [FAIL] eccezione: $($_.Exception.Message)" -ForegroundColor Red }

# --- Scenario 5: lanciatore cambiato -> staging + exit 10 -------------------
Write-Host "`n== Scenario 5: avvia.bat cambiato (staging) =="
try {
  $dir  = New-Install -Name 's5' -Commit 'aaaaaaa0000' -JarText 'JAR-OLD' -UpdatedAt '2026-01-01T00:00:00Z'
  $zip  = Join-Path $work 's5.zip'
  $size = New-ReleaseZip -Path $zip -Commit 'bbbbbbb1111' -JarText 'JAR-NEW' -LauncherExtra 'modifica-lanciatore-di-test'
  $port = Get-Port
  $json = New-ReleaseJson -Port $port -UpdatedAt '2026-06-06T06:00:00Z' -AssetId 4000 -Commit 'bbbbbbb1111' -Size $size
  $job  = Start-MockServer -Port $port -Json $json -ZipPath $zip
  $code = Invoke-Aggiorna -Dir $dir -ApiBase "http://127.0.0.1:$port"
  Stop-MockServer $job
  Check ($code -eq 10) "esce con 10 per richiedere il riavvio (ricevuto $code)"
  Check (Test-Path (Join-Path $dir 'avvia.bat.new')) "avvia.bat.new e' stato messo da parte per il riavvio"
  Check (-not (Test-Path (Join-Path $dir 'aggiorna.ps1.new'))) "aggiorna.ps1 invariato: nessun .new superfluo"
} catch { $script:fail++; Write-Host "  [FAIL] eccezione: $($_.Exception.Message)" -ForegroundColor Red }

# --- Scenario 6: avvio di un jar con timeout --------------------------------
Write-Host "`n== Scenario 6: avvio di un jar con timeout =="
try {
  $java = Get-Command java -ErrorAction SilentlyContinue
  $javac = Get-Command javac -ErrorAction SilentlyContinue
  if ($java -and $javac) {
    $jdir = Join-Path $work 'stubjar'
    New-Item -ItemType Directory -Path $jdir -Force | Out-Null
    @'
public class Main {
  public static void main(String[] a) {
    System.out.println("TripleA Pro+ stub avviato");
  }
}
'@ | Set-Content -LiteralPath (Join-Path $jdir 'Main.java') -Encoding ASCII
    Push-Location $jdir
    try {
      & javac Main.java
      & jar cfe stub.jar Main Main.class
    } finally { Pop-Location }
    $stub = Join-Path $jdir 'stub.jar'
    Check (Test-Path $stub) "jar di prova compilato"
    $p = Start-Process -FilePath $java.Source -ArgumentList @('-jar', $stub) -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $jdir 'out.txt')
    $exited = $p.WaitForExit(30000)
    if (-not $exited) { try { $p.Kill() } catch {}; }
    Check ($exited) "il jar termina entro il timeout (30s)"
    Check ($exited -and $p.ExitCode -eq 0) "il jar esce con codice 0"
    Check ((Read-Text (Join-Path $jdir 'out.txt')) -match 'stub avviato') "l'output del jar e' stato catturato"
  } else {
    Write-Host "  [SKIP] java/javac non disponibili: avvio del jar non testato" -ForegroundColor Yellow
  }
} catch {
  # Problemi di toolchain (compilazione/jar) non devono far fallire il gate:
  # questo scenario e' "best effort" (avvio di un jar stub, non del client reale).
  Write-Host "  [SKIP] avvio jar non verificato ($($_.Exception.Message))" -ForegroundColor Yellow
}

# --- Scenario 7: invocazione reale di aggiorna.ps1 (senza -InstallDir) --------
Write-Host "`n== Scenario 7: powershell.exe -File aggiorna.ps1 SENZA -InstallDir, da altra cartella =="
try {
  Test-RealScenario -Name 's7' -Label 's7' -What 'aggiorna.ps1 -File' -Runner {
    param($d, $api, $l) Invoke-AggiornaReal -Dir $d -ApiBase $api -Label $l
  } | Out-Null
} catch { $script:fail++; Write-Host "  [FAIL] eccezione: $($_.Exception.Message)" -ForegroundColor Red }

# --- Scenario 8: end-to-end avvia.bat via cmd.exe ---------------------------
Write-Host "`n== Scenario 8: end-to-end avvia.bat (cmd.exe) su installazione vecchia =="
try {
  $r = Test-RealScenario -Name 's8' -Label 's8' -What 'avvia.bat' -Runner {
    param($d, $api, $l) Invoke-AvviaBat -Dir $d -ApiBase $api -Label $l
  }
  Check ($r.Out -match 'Versione in uso : commit bbbbbbb') "avvia.bat : mostra la nuova versione in uso"
  Check ($r.Out -match 'PROPLUS_TEST_NOLAUNCH=1: Java non avviato') "avvia.bat : modalita' test, Java non avviato"
} catch { $script:fail++; Write-Host "  [FAIL] eccezione: $($_.Exception.Message)" -ForegroundColor Red }

# --- Scenario 9: percorso di installazione con spazi -------------------------
Write-Host "`n== Scenario 9: percorso con spazi e parentesi =="
try {
  Test-RealScenario -Name 'con spazi\c1\TripleA Pro+ (1)' -Label 's9a' -What 'spazi, aggiorna.ps1 -File' -Runner {
    param($d, $api, $l) Invoke-AggiornaReal -Dir $d -ApiBase $api -Label $l
  } | Out-Null
} catch { $script:fail++; Write-Host "  [FAIL] eccezione: $($_.Exception.Message)" -ForegroundColor Red }
try {
  $r = Test-RealScenario -Name 'con spazi\c2\TripleA Pro+ (1)' -Label 's9b' -What 'spazi, avvia.bat' -Runner {
    param($d, $api, $l) Invoke-AvviaBat -Dir $d -ApiBase $api -Label $l
  }
  Check ($r.Out -match 'Versione in uso : commit bbbbbbb') "spazi, avvia.bat : mostra la nuova versione in uso"
} catch { $script:fail++; Write-Host "  [FAIL] eccezione: $($_.Exception.Message)" -ForegroundColor Red }

# --- esito ------------------------------------------------------------------
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "`n============================================================"
Write-Host ("Risultato: {0} PASS, {1} FAIL" -f $script:pass, $script:fail)
Write-Host "============================================================"
if ($script:fail -gt 0) { exit 1 }
exit 0
