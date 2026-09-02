# Build the plugin and drop it where RuneLite will side-load it.
#
# This is the whole local test loop. It needs no PR, no fork of the client, and no IDE:
# RuneLite loads any jar in .runelite/sideloaded-plugins when the client is started with
# --developer-mode, so the plugin runs inside the real client against the live game.
#
# One-time setup, which also solves the Jagex account problem:
#
#   1. Start menu -> "RuneLite (configure)"
#   2. Client arguments:  --developer-mode --debug
#      JVM arguments:     -ea
#   3. Save
#
# Then launch through the Jagex Launcher exactly as you normally do. The launcher still
# performs the login, so there is nothing to bypass and no credentials file to create —
# the saved arguments are applied to launcher-started clients too.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File sideload.ps1

$ErrorActionPreference = 'Stop'

$repo = $PSScriptRoot
$target = Join-Path $env:USERPROFILE '.runelite\sideloaded-plugins'
$mavenBin = 'C:\tools\apache-maven-3.9.6\bin'

if (Test-Path $mavenBin) { $env:PATH = "$mavenBin;$env:PATH" }

Write-Host '== building ==' -ForegroundColor Cyan
Push-Location $repo
try {
    # Tests skipped deliberately: BirdhousePluginTest is a scratch harness, not a suite,
    # and a failure there should not block getting a build in front of the game.
    & mvn -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "maven build failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}

$jar = Get-ChildItem (Join-Path $repo 'target\*.jar') -File |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { throw 'no jar produced in target/' }

if (-not (Test-Path $target)) {
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Write-Host "created $target"
}

# Clear our previous jars first. Two copies of the same plugin both load, and the
# duplicate @PluginDescriptor failure is confusing to read if you have not seen it.
Get-ChildItem $target -Filter '*birdhouse*.jar' -File -ErrorAction SilentlyContinue |
    ForEach-Object {
        Remove-Item $_.FullName -Force
        Write-Host "removed old $($_.Name)"
    }

Copy-Item $jar.FullName $target -Force
Write-Host ''
Write-Host "== side-loaded ==" -ForegroundColor Green
Write-Host "  $($jar.Name)  ($([math]::Round($jar.Length / 1KB)) KB)"
Write-Host "  -> $target"
Write-Host ''
Write-Host 'Now restart RuneLite through the Jagex Launcher. In the client, the plugin'
Write-Host 'appears under Plugins with a "sideloaded" marker rather than a hub entry.'
Write-Host ''
Write-Host 'Uninstall the hub copy of The Birdhouse from the Plugin Hub panel first.' -ForegroundColor Yellow
Write-Host 'Two copies of the same plugin loaded at once conflict, and the resulting'
Write-Host 'error does not obviously say that is what happened. Delete this jar when you'
Write-Host 'are finished, then reinstall from the hub.' -ForegroundColor Yellow
