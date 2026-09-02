# Build the plugin and drop it where RuneLite will side-load it.
#
# This is the whole local test loop. No pull request, no fork of the client, no IDE:
# RuneLite loads any jar in .runelite/sideloaded-plugins when the client is started with
# --developer-mode, so the plugin runs inside the real client against the live game.
#
# Built with Gradle rather than Maven on purpose. build.gradle is what the plugin hub
# actually builds, and it resolves the client through Gradle against latest.release, so
# this jar is the same artifact the hub would publish. Building with pom.xml instead
# would test a classpath the hub never uses, which is the one failure a local loop is
# supposed to rule out. (pom.xml is kept for quick IDE compiles; it is not the source
# of truth for anything.)
#
# ONE-TIME SETUP, which also solves the Jagex account problem:
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

Write-Host '== building (same build the plugin hub runs) ==' -ForegroundColor Cyan
Push-Location $repo
try {
    & (Join-Path $repo 'gradlew.bat') build --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "gradle build failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}

$jar = Get-ChildItem (Join-Path $repo 'build\libs\*.jar') -File |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { throw 'no jar produced in build/libs/' }

if (-not (Test-Path $target)) {
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Write-Host "created $target"
}

# Clear our previous jars first, including the older Maven-named ones. Two copies of the
# same plugin both load, and the duplicate-descriptor failure is confusing to read.
Get-ChildItem $target -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match 'birdhouse' } |
    ForEach-Object {
        Remove-Item $_.FullName -Force
        Write-Host "removed old $($_.Name)"
    }

Copy-Item $jar.FullName $target -Force
Write-Host ''
Write-Host '== side-loaded ==' -ForegroundColor Green
Write-Host "  $($jar.Name)  ($([math]::Round($jar.Length / 1KB)) KB)"
Write-Host "  -> $target"
Write-Host ''
Write-Host 'Now restart RuneLite through the Jagex Launcher.'
Write-Host ''
Write-Host 'Uninstall the hub copy of The Birdhouse from the Plugin Hub panel first.' -ForegroundColor Yellow
Write-Host 'Two copies of the same plugin loaded at once conflict, and the resulting'
Write-Host 'error does not obviously say that is what happened. Delete this jar when you'
Write-Host 'are finished, then reinstall from the hub.' -ForegroundColor Yellow
