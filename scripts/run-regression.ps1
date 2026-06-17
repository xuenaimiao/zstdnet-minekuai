param(
    [switch]$SkipBuild,
    [switch]$SkipDedicated,
    [switch]$SkipLan
)

$ErrorActionPreference = 'Stop'

function Invoke-GradleBuild {
    param(
        [hashtable]$Target,
        [string]$GradleBat,
        [string]$OriginalPath
    )

    if (-not (Test-Path $Target.javaHome)) {
        throw "Missing JDK: $($Target.javaHome)"
    }

    Write-Host "==> Building $($Target.name)"
    $env:JAVA_HOME = $Target.javaHome
    $env:Path = (Join-Path $Target.javaHome 'bin') + ';' + $OriginalPath

    Push-Location $Target.projectDir
    try {
        & $GradleBat '--no-daemon' '--project-cache-dir' $Target.projectCacheDir 'build'
        if ($LASTEXITCODE -ne 0) {
            throw "Build failed for $($Target.name)"
        }
        if ($Target.ContainsKey('jarSyncSource') -and $Target.ContainsKey('jarSyncTarget')) {
            Copy-Item -LiteralPath $Target.jarSyncSource -Destination $Target.jarSyncTarget -Force
        }
    } finally {
        Pop-Location
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildRoot = Join-Path (Split-Path -Parent $repoRoot) 'zstdnet-build'
# 默认 Gradle 8.8；26.1 变体需 Gradle 9.x（见各 target 的 gradleBat 覆盖）。
$gradleBat = Join-Path $buildRoot 'tools\gradle-8.8\bin\gradle.bat'
$gradleUserHome = Join-Path $buildRoot 'cache\gradle-user'

New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null
$env:GRADLE_USER_HOME = $gradleUserHome

$targets = @(
    @{
        name = 'forge-1.18.2'
        projectDir = Join-Path $repoRoot 'mods\1.18.2\zstdnet-forge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-forge-1.18.2-regression'
        javaHome = 'C:\Program Files\Java\jdk-17'
    },
    @{
        name = 'forge-1.19.2'
        projectDir = Join-Path $repoRoot 'mods\1.19.2\zstdnet-forge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-forge-1.19.2-regression'
        javaHome = 'C:\Program Files\Java\jdk-17'
    },
    @{
        name = 'forge-1.20.1'
        projectDir = Join-Path $repoRoot 'mods\1.20.1\zstdnet-forge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-forge-regression'
        javaHome = 'C:\Program Files\Java\jdk-17'
    },
    @{
        name = 'neoforge-1.20.1'
        projectDir = Join-Path $repoRoot 'mods\1.20.1\zstdnet-neoforge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-neoforge-1.20.1-regression'
        javaHome = 'C:\Program Files\Java\jdk-17'
    },
    @{
        name = 'neoforge-1.21.1'
        projectDir = Join-Path $repoRoot 'mods\1.21.1\zstdnet-neoforge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-neoforge-1.21.1-regression'
        javaHome = 'C:\Program Files\Java\jdk-21'
        jarSyncSource = Join-Path $buildRoot 'mods\1.21.1\zstdnet-neoforge\libs\zstdnet-1.21.1-neoforge-1.3.6.jar'
        jarSyncTarget = Join-Path $buildRoot 'mods\1.21.1\zstdnet-neoforge\libs\zstdnet-1.21.1-neoforge-1.3.6-all.jar'
    },
    @{
        name = 'fabric-1.20.1'
        projectDir = Join-Path $repoRoot 'mods\1.20.1\zstdnet-fabric'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-fabric-1.20.1-regression'
        javaHome = 'C:\Program Files\Java\jdk-17'
        jarSyncSource = Join-Path $buildRoot 'mods\1.20.1\zstdnet-fabric\libs\zstdnet-1.20.1-fabric-1.3.6.jar'
        jarSyncTarget = Join-Path $buildRoot 'mods\1.20.1\zstdnet-fabric\libs\zstdnet-1.20.1-fabric-1.3.6-all.jar'
    },
    @{
        name = 'fabric-1.21.1'
        projectDir = Join-Path $repoRoot 'mods\1.21.1\zstdnet-fabric'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-fabric-1.21.1-regression'
        javaHome = 'C:\Program Files\Java\jdk-21'
        jarSyncSource = Join-Path $buildRoot 'mods\1.21.1\zstdnet-fabric\libs\zstdnet-1.21.1-fabric-1.3.6.jar'
        jarSyncTarget = Join-Path $buildRoot 'mods\1.21.1\zstdnet-fabric\libs\zstdnet-1.21.1-fabric-1.3.6-all.jar'
    },
    @{
        name = 'neoforge-26.1'
        projectDir = Join-Path $repoRoot 'mods\26.1\zstdnet-neoforge'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-neoforge-26.1-regression'
        javaHome = 'C:\Users\78569\.jdks\liberica-25.0.3'
        gradleBat = Join-Path $buildRoot 'tools\gradle-9.4.1\bin\gradle.bat'
    },
    @{
        name = 'fabric-26.1'
        projectDir = Join-Path $repoRoot 'mods\26.1\zstdnet-fabric'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-fabric-26.1-regression'
        javaHome = 'C:\Users\78569\.jdks\liberica-25.0.3'
        gradleBat = Join-Path $buildRoot 'tools\gradle-9.4.1\bin\gradle.bat'
    },
    @{
        # 插件端（版本无关，Java 17 字节码）。仅构建+单元测试；专用服/LAN 校验只针对 mod 变体，不覆盖插件。
        name = 'bukkit'
        projectDir = Join-Path $repoRoot 'mods\bukkit\zstdnet-bukkit'
        projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-bukkit-regression'
        javaHome = 'C:\Program Files\Java\jdk-17'
    }
)

$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path

try {
    if (-not $SkipBuild) {
        foreach ($target in $targets) {
            $targetGradle = if ($target.ContainsKey('gradleBat')) { $target.gradleBat } else { $gradleBat }
            Invoke-GradleBuild -Target $target -GradleBat $targetGradle -OriginalPath $originalPath
        }
    }

    if (-not $SkipDedicated) {
        Write-Host "==> Dedicated startup verification"
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'verify-runtime-startup.ps1')
        if ($LASTEXITCODE -ne 0) {
            throw 'Dedicated startup verification failed.'
        }
    }

    if (-not $SkipLan) {
        Write-Host "==> LAN regression verification"
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'verify-lan-regression.ps1')
        if ($LASTEXITCODE -ne 0) {
            throw 'LAN regression verification failed.'
        }
    }
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:Path = $originalPath
}

Write-Host ''
Write-Host 'Regression suite passed.'
