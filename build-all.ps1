# build-all.ps1 - Build all 14 ZstdNet variants in one pass into ../zstdnet-build/dist/
#
# Two things build-*.ps1 does not do:
#   1. Uses the REAL local JDK paths (D:\JDK = JDK17, tools\jdk-21.0.11+10 = JDK21,
#      .jdks\liberica-25.0.3 = JDK25, .jdks\liberica-1.8.0_492 = JDK8);
#      build-forge/bukkit.ps1 hardcode C:\Program Files\Java\jdk-17 which does not exist here.
#   2. Runs every variant serially, never aborts on a single failure, prints a summary
#      and lists the jars produced in dist/.
#
# Usage:
#   .\build-all.ps1                                 # all 12
#   .\build-all.ps1 -Only bukkit                    # only the given key(s)
#   .\build-all.ps1 -Only forge-1.20.1,fabric-26.1
#   .\build-all.ps1 -DryRun                         # plan only, do not build

param(
    [string[]]$Only,
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'   # a failing variant must not abort the whole run

$repoRoot  = $PSScriptRoot
$buildRoot = Join-Path (Split-Path -Parent $repoRoot) 'zstdnet-build'
$distDir   = Join-Path $buildRoot 'dist'
$logDir    = Join-Path $buildRoot 'cache\build-logs'
$gradleUserHome = Join-Path $buildRoot 'cache\gradle-user'
New-Item -ItemType Directory -Force -Path $distDir, $logDir, $gradleUserHome | Out-Null

# Real local JDK paths (verified present)
$jdks = @{
    '8'  = 'C:\Users\78569\.jdks\liberica-1.8.0_492'
    '17' = 'D:\JDK'
    '21' = Join-Path $buildRoot 'tools\jdk-21.0.11+10'
    '25' = 'C:\Users\78569\.jdks\liberica-25.0.3'
}
$gradles = @{
    '7.6.4' = Join-Path $buildRoot 'tools\gradle-7.6.4\bin\gradle.bat'
    '8.8'   = Join-Path $buildRoot 'tools\gradle-8.8\bin\gradle.bat'
    '9.4.1' = Join-Path $buildRoot 'tools\gradle-9.4.1\bin\gradle.bat'
}

# Variant table: key / project path / JDK / Gradle / project-cache dir (reuses existing cache)
$variants = @(
    @{ key='forge-1.16.5';    proj='mods\1.16.5\zstdnet-forge';    jdk='8';  gradle='7.6.4'; cache='zstdnet-forge-1165' }
    @{ key='forge-1.18.2';    proj='mods\1.18.2\zstdnet-forge';    jdk='17'; gradle='8.8';   cache='zstdnet-forge-1.18.2' }
    @{ key='forge-1.19.2';    proj='mods\1.19.2\zstdnet-forge';    jdk='17'; gradle='8.8';   cache='zstdnet-forge-1.19.2' }
    @{ key='fabric-1.19.3';   proj='mods\1.19.3\zstdnet-fabric';   jdk='17'; gradle='8.8';   cache='zstdnet-fabric-1.19.3' }
    @{ key='forge-1.20.1';    proj='mods\1.20.1\zstdnet-forge';    jdk='17'; gradle='8.8';   cache='zstdnet-forge-1.20.1' }
    @{ key='neoforge-1.20.1'; proj='mods\1.20.1\zstdnet-neoforge'; jdk='17'; gradle='8.8';   cache='zstdnet-neoforge-1.20.1' }
    @{ key='fabric-1.20.1';   proj='mods\1.20.1\zstdnet-fabric';   jdk='17'; gradle='8.8';   cache='zstdnet-fabric-1.20.1' }
    @{ key='neoforge-1.21.1'; proj='mods\1.21.1\zstdnet-neoforge'; jdk='21'; gradle='8.8';   cache='zstdnet-neoforge-1.21.1' }
    @{ key='fabric-1.21.1';   proj='mods\1.21.1\zstdnet-fabric';   jdk='21'; gradle='8.8';   cache='zstdnet-fabric-1.21.1' }
    @{ key='neoforge-26.1';   proj='mods\26.1\zstdnet-neoforge';   jdk='25'; gradle='9.4.1'; cache='zstdnet-neoforge-26.1' }
    @{ key='fabric-26.1';     proj='mods\26.1\zstdnet-fabric';     jdk='25'; gradle='9.4.1'; cache='zstdnet-fabric-26.1' }
    @{ key='neoforge-26.2';   proj='mods\26.2\zstdnet-neoforge';   jdk='25'; gradle='9.4.1'; cache='zstdnet-neoforge-26.2' }
    @{ key='fabric-26.2';     proj='mods\26.2\zstdnet-fabric';     jdk='25'; gradle='9.4.1'; cache='zstdnet-fabric-26.2' }
    @{ key='bukkit';          proj='mods\bukkit\zstdnet-bukkit';   jdk='17'; gradle='8.8';   cache='zstdnet-bukkit' }
)

if ($Only) {
    $wanted = ($Only -join ',') -split ',' | ForEach-Object { $_.Trim() }
    $variants = $variants | Where-Object { $wanted -contains $_.key }
    if ($variants.Count -eq 0) { throw "No variant matched -Only $Only." }
}

Write-Host "Planning $($variants.Count) variant(s): $($variants.key -join ', ')" -ForegroundColor Cyan
if ($DryRun) { $variants | Format-Table key, jdk, gradle, cache -AutoSize; return }

$results = @()
$overallStart = Get-Date

foreach ($v in $variants) {
    $javaHome   = $jdks[$v.jdk]
    $gradleBat  = $gradles[$v.gradle]
    $projectDir = Join-Path $repoRoot $v.proj
    $cacheDir   = Join-Path $buildRoot "cache\project-cache\$($v.cache)"
    $logFile    = Join-Path $logDir "$($v.key).log"

    Write-Host ""
    Write-Host "==== [$($v.key)] JDK $($v.jdk) / Gradle $($v.gradle) ====" -ForegroundColor Cyan

    if (-not (Test-Path (Join-Path $javaHome 'bin\javac.exe'))) {
        Write-Host "  [SKIP] JDK $($v.jdk) not found: $javaHome" -ForegroundColor Red
        $results += [pscustomobject]@{ key=$v.key; ok=$false; secs=0; note="JDK missing" }
        continue
    }
    if (-not (Test-Path $gradleBat)) {
        Write-Host "  [SKIP] Gradle $($v.gradle) not found: $gradleBat" -ForegroundColor Red
        $results += [pscustomobject]@{ key=$v.key; ok=$false; secs=0; note="Gradle missing" }
        continue
    }
    if (-not (Test-Path $projectDir)) {
        Write-Host "  [SKIP] Project dir not found: $projectDir" -ForegroundColor Red
        $results += [pscustomobject]@{ key=$v.key; ok=$false; secs=0; note="Project missing" }
        continue
    }

    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null

    $env:JAVA_HOME        = $javaHome
    $env:Path             = (Join-Path $javaHome 'bin') + ';' + $env:Path
    $env:GRADLE_USER_HOME = $gradleUserHome

    $t0 = Get-Date
    Push-Location $projectDir
    try {
        # Wrap in cmd /c for 2>&1 merging; avoids PS5.1 NativeCommandError on native stderr.
        $cmdLine = "`"$gradleBat`" --project-cache-dir `"$cacheDir`" --console=plain build 2>&1"
        cmd /c $cmdLine | Tee-Object -FilePath $logFile | Out-Null
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $secs = [int]((Get-Date) - $t0).TotalSeconds

    if ($code -eq 0) {
        Write-Host "  [OK]   $($v.key)  (${secs}s)" -ForegroundColor Green
        $results += [pscustomobject]@{ key=$v.key; ok=$true; secs=$secs; note='' }
    } else {
        Write-Host "  [FAIL] $($v.key)  exit=$code (${secs}s)  log=$logFile" -ForegroundColor Red
        $results += [pscustomobject]@{ key=$v.key; ok=$false; secs=$secs; note="exit=$code" }
    }
}

$totalSecs = [int]((Get-Date) - $overallStart).TotalSeconds
Write-Host ""
Write-Host "================ SUMMARY (${totalSecs}s) ================" -ForegroundColor Yellow
$results | Format-Table -AutoSize
$okCount   = @($results | Where-Object { $_.ok }).Count
$failCount = @($results | Where-Object { -not $_.ok }).Count
Write-Host "OK=$okCount  FAIL=$failCount" -ForegroundColor Yellow

Write-Host ""
Write-Host "---- dist jars (../zstdnet-build/dist/) ----" -ForegroundColor Yellow
$jars = Get-ChildItem -Path $distDir -Filter '*.jar' -ErrorAction SilentlyContinue | Sort-Object Name
if ($jars) {
    $jars | ForEach-Object {
        $kb = [int]($_.Length / 1KB)
        Write-Host ("  {0,-50} {1,8} KB" -f $_.Name, $kb)
    }
} else {
    Write-Host "  (none - no jars in dist/)" -ForegroundColor Red
}

if ($failCount -gt 0) { exit 1 }
