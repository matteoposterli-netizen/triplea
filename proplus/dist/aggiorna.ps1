#Requires -Version 5.1
<#
  TripleA Pro+ - aggiornamento automatico (step 0bis)

  Viene richiamato da avvia.bat a ogni avvio. Interroga in modo ANONIMO la
  GitHub API per la release "proplus-latest" del fork, confronta l'asset
  TripleA-ProPlus.zip con quanto registrato in version.txt e, se c'e' una
  build piu' recente, la scarica, la verifica e sostituisce i file di
  programma (jar + file di supporto). I dati utente (salvataggi, mappe,
  impostazioni) NON stanno in questa cartella e non vengono mai toccati.

  Se rete o API falliscono (anche per rate limit), lo script stampa un
  avviso ed esce con codice 0: avvia.bat prosegue con la build locale.

  avvia.bat (in esecuzione in cmd.exe) e questo stesso script non vengono
  sovrascritti "a caldo": le versioni nuove vengono messe da parte come
  avvia.bat.new / aggiorna.ps1.new e applicate da avvia.bat al riavvio.

  Nessun token o segreto: la GitHub API viene usata in forma anonima.
#>
[CmdletBinding()]
param(
  # Cartella di installazione (dove stanno jar e script). Default: cartella dello script.
  [string]$InstallDir = $PSScriptRoot,
  # Fork da interrogare "owner/repo". Default: valore in build-info.txt, poi fallback.
  [string]$Repo = $null,
  # Base della GitHub API. Sovrascrivibile nei test CI con un server locale.
  [string]$ApiBase = 'https://api.github.com',
  # Tag della release da seguire.
  [string]$Tag = 'proplus-latest',
  # Nome dell'asset zip pubblicato dalla Action.
  [string]$AssetName = 'TripleA-ProPlus.zip',
  # Secondi di timeout per le richieste di rete.
  [int]$TimeoutSec = 20
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# TLS 1.2 per Windows PowerShell 5.1 (altrimenti la GitHub API rifiuta).
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$EXIT_OK = 0            # tutto a posto (aggiornato o gia' aggiornato o fallback offline)
$EXIT_RELAUNCH = 10     # i file di avvio sono cambiati: avvia.bat deve riavviarsi

function Write-Info  { param([string]$m) Write-Host "  $m" }
function Write-Warn  { param([string]$m) Write-Host "  [avviso] $m" -ForegroundColor Yellow }
function Write-OkMsg { param([string]$m) Write-Host "  $m" -ForegroundColor Green }

# --- file di supporto -------------------------------------------------------
$buildInfoPath = Join-Path $InstallDir 'build-info.txt'
$versionPath   = Join-Path $InstallDir 'version.txt'
$jarPath       = Join-Path $InstallDir 'TripleA-ProPlus.jar'

function Read-KeyValueFile {
  param([string]$Path)
  $h = @{}
  if (Test-Path -LiteralPath $Path) {
    foreach ($line in (Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue)) {
      if ($line -match '^\s*([^#=][^=]*)=(.*)$') {
        $h[$matches[1].Trim()] = $matches[2].Trim()
      }
    }
  }
  return $h
}

function Short-Commit {
  param([string]$sha)
  if ([string]::IsNullOrWhiteSpace($sha)) { return 'sconosciuto' }
  if ($sha.Length -gt 7) { return $sha.Substring(0, 7) }
  return $sha
}

$buildInfo    = Read-KeyValueFile $buildInfoPath
$localVersion = Read-KeyValueFile $versionPath

if ([string]::IsNullOrWhiteSpace($Repo)) {
  if ($buildInfo.ContainsKey('repo') -and $buildInfo['repo'] -notmatch '@REPO@') {
    $Repo = $buildInfo['repo']
  } else {
    $Repo = 'matteoposterli-netizen/triplea'
  }
}

$localCommit = if ($buildInfo.ContainsKey('commit') -and $buildInfo['commit'] -notmatch '@COMMIT@') { $buildInfo['commit'] } else { '' }

Write-Host ''
Write-Host 'Controllo aggiornamenti TripleA Pro+ ...'
Write-Info ("Fork      : {0}" -f $Repo)
Write-Info ("Locale    : commit {0}" -f (Short-Commit $localCommit))

# --- interrogazione della release ------------------------------------------
$release = $null
try {
  $url = "$ApiBase/repos/$Repo/releases/tags/$Tag"
  $headers = @{ 'User-Agent' = 'TripleA-ProPlus-Updater'; 'Accept' = 'application/vnd.github+json' }
  $release = Invoke-RestMethod -Uri $url -Headers $headers -TimeoutSec $TimeoutSec -UseBasicParsing
} catch {
  Write-Warn "impossibile contattare GitHub ($($_.Exception.Message))."
  Write-Warn "Avvio la build gia' presente in questa cartella."
  exit $EXIT_OK
}

# Individua l'asset zip nella release.
$asset = $null
if ($release -and $release.PSObject.Properties.Name -contains 'assets') {
  $asset = $release.assets | Where-Object { $_.name -eq $AssetName } | Select-Object -First 1
}
if (-not $asset) {
  Write-Warn "la release '$Tag' non contiene l'asset '$AssetName'. Avvio la build locale."
  exit $EXIT_OK
}

$remoteUpdatedAt = [string]$asset.updated_at
$remoteAssetId   = [string]$asset.id
$remoteSize      = [int64]$asset.size
$downloadUrl     = [string]$asset.browser_download_url

# Commit della release (dalle note), usato quando version.txt non esiste ancora.
$remoteCommit = ''
if ($release.PSObject.Properties.Name -contains 'body' -and $release.body -match 'commit\s+([0-9a-fA-F]{7,40})') {
  $remoteCommit = $matches[1]
}

# --- serve aggiornare? ------------------------------------------------------
$needUpdate = $false
$reason = ''
if (-not (Test-Path -LiteralPath $jarPath)) {
  $needUpdate = $true; $reason = 'jar locale assente'
} elseif ($localVersion.ContainsKey('updated_at') -and -not [string]::IsNullOrWhiteSpace($localVersion['updated_at'])) {
  # Stato noto: confronto preciso sull'asset pubblicato.
  if ($localVersion['updated_at'] -ne $remoteUpdatedAt -or
      ($localVersion.ContainsKey('asset_id') -and $localVersion['asset_id'] -ne $remoteAssetId)) {
    $needUpdate = $true; $reason = "asset piu' recente su GitHub"
  }
} else {
  # Primo avvio dopo scompattamento manuale: confronto per commit.
  if (-not [string]::IsNullOrWhiteSpace($remoteCommit) -and -not [string]::IsNullOrWhiteSpace($localCommit)) {
    if ($remoteCommit -notlike "$localCommit*" -and $localCommit -notlike "$remoteCommit*") {
      $needUpdate = $true; $reason = 'commit locale diverso da quello pubblicato'
    }
  } elseif ([string]::IsNullOrWhiteSpace($localCommit)) {
    $needUpdate = $true; $reason = 'versione locale sconosciuta'
  }
}

function Write-VersionFile {
  param([string]$AssetId, [string]$UpdatedAt, [string]$Commit)
  $content = @(
    '# Stato della build Pro+ installata (gestito da aggiorna.ps1, non modificare a mano).',
    "asset_id=$AssetId",
    "updated_at=$UpdatedAt",
    "commit=$Commit",
    "checked_at=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))"
  ) -join "`r`n"
  Set-Content -LiteralPath $versionPath -Value $content -Encoding ASCII
}

if (-not $needUpdate) {
  Write-OkMsg "Gia' aggiornato: uso la build locale."
  # Allinea version.txt se mancava (primo avvio con commit coincidente).
  if (-not $localVersion.ContainsKey('updated_at')) {
    try { Write-VersionFile -AssetId $remoteAssetId -UpdatedAt $remoteUpdatedAt -Commit $localCommit } catch {}
  }
  exit $EXIT_OK
}

Write-Info ("Trovata build piu' recente ($reason). Scarico l'aggiornamento...")

# --- download in cartella temporanea ----------------------------------------
$tmpRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("TripleA-ProPlus-update-" + [System.Guid]::NewGuid().ToString('N'))
$zipPath = Join-Path $tmpRoot $AssetName
$extractDir = Join-Path $tmpRoot 'extract'
try {
  New-Item -ItemType Directory -Path $tmpRoot -Force | Out-Null
  try {
    Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath -TimeoutSec ([Math]::Max($TimeoutSec, 120)) -UseBasicParsing `
      -Headers @{ 'User-Agent' = 'TripleA-ProPlus-Updater' }
  } catch {
    Write-Warn "download non riuscito ($($_.Exception.Message)). Avvio la build locale."
    exit $EXIT_OK
  }

  # Verifica 1: file scaricato non vuoto e, se noto, della dimensione attesa.
  $dl = Get-Item -LiteralPath $zipPath -ErrorAction SilentlyContinue
  if (-not $dl -or $dl.Length -le 0) {
    Write-Warn 'download vuoto o incompleto. Avvio la build locale.'; exit $EXIT_OK
  }
  if ($remoteSize -gt 0 -and $dl.Length -ne $remoteSize) {
    Write-Warn ("dimensione scaricata ($($dl.Length)) diversa dall'attesa ($remoteSize). Avvio la build locale.")
    exit $EXIT_OK
  }

  # Verifica 2: lo zip e' valido e contiene il jar.
  try {
    Expand-Archive -LiteralPath $zipPath -DestinationPath $extractDir -Force
  } catch {
    Write-Warn "zip non valido o non estraibile ($($_.Exception.Message)). Avvio la build locale."
    exit $EXIT_OK
  }
  $newJar = Join-Path $extractDir 'TripleA-ProPlus.jar'
  if (-not (Test-Path -LiteralPath $newJar) -or (Get-Item -LiteralPath $newJar).Length -le 0) {
    Write-Warn 'lo zip scaricato non contiene un jar valido. Avvio la build locale.'
    exit $EXIT_OK
  }

  # --- applica: sostituisci solo i file di programma noti --------------------
  # Il jar viene scritto su file temporaneo e poi spostato (operazione atomica),
  # cosi' non resta mai un jar a meta'.
  $jarTmp = "$jarPath.tmp"
  Copy-Item -LiteralPath $newJar -Destination $jarTmp -Force
  Move-Item -LiteralPath $jarTmp -Destination $jarPath -Force

  foreach ($name in @('LEGGIMI.txt', 'build-info.txt', '.triplea-root')) {
    $src = Join-Path $extractDir $name
    if (Test-Path -LiteralPath $src) {
      Copy-Item -LiteralPath $src -Destination (Join-Path $InstallDir $name) -Force
    }
  }

  # avvia.bat e aggiorna.ps1 sono "in uso": se cambiano, li metto da parte come
  # .new e lascio che avvia.bat li applichi al riavvio.
  $relaunch = $false
  foreach ($name in @('avvia.bat', 'aggiorna.ps1')) {
    $src = Join-Path $extractDir $name
    $dst = Join-Path $InstallDir $name
    if (Test-Path -LiteralPath $src) {
      $srcHash = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash
      $dstHash = if (Test-Path -LiteralPath $dst) { (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash } else { '' }
      if ($srcHash -ne $dstHash) {
        Copy-Item -LiteralPath $src -Destination "$dst.new" -Force
        $relaunch = $true
      } else {
        # eventuale .new rimasto da un tentativo precedente: rimuovilo
        if (Test-Path -LiteralPath "$dst.new") { Remove-Item -LiteralPath "$dst.new" -Force -ErrorAction SilentlyContinue }
      }
    }
  }

  # Rileggi il commit dalla nuova build per registrarlo in version.txt.
  $newBuildInfo = Read-KeyValueFile (Join-Path $InstallDir 'build-info.txt')
  $installedCommit = if ($newBuildInfo.ContainsKey('commit')) { $newBuildInfo['commit'] } else { $remoteCommit }
  Write-VersionFile -AssetId $remoteAssetId -UpdatedAt $remoteUpdatedAt -Commit $installedCommit

  Write-OkMsg ("Aggiornato alla build commit {0}." -f (Short-Commit $installedCommit))
  if ($relaunch) {
    Write-Info "Lo script di avvio e' cambiato: riavvio per applicarlo..."
    exit $EXIT_RELAUNCH
  }
  exit $EXIT_OK
}
catch {
  Write-Warn "errore durante l'aggiornamento ($($_.Exception.Message)). Avvio la build locale."
  exit $EXIT_OK
}
finally {
  if (Test-Path -LiteralPath $tmpRoot) {
    Remove-Item -LiteralPath $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
}
