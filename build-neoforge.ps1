param(
    [ValidateSet('1.20.1', '1.21.1', '1.21.11', '26.1', '26.2', '26.3')]
    [string]$MinecraftVersion = '1.21.1'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildRoot = Join-Path (Split-Path -Parent $repoRoot) 'zstdnet-build'
$gradleUserHome = Join-Path $buildRoot 'cache\gradle-user'
$projectCacheDir = Join-Path $buildRoot "cache\project-cache\zstdnet-neoforge-$MinecraftVersion"
$projectDir = Join-Path $repoRoot "mods\$MinecraftVersion\zstdnet-neoforge"

# JDK 实际路径由 jdk-paths.ps1 统一解析（可用 ZSTDNET_JDK<版本> 环境变量覆盖）。
. (Join-Path $repoRoot 'jdk-paths.ps1')

# Per-MC-version JDK + Gradle distribution (26.1 is un-obfuscated: needs JDK 25 + Gradle 9.x)
switch ($MinecraftVersion) {
    '1.20.1' { $jdk = 17; $gradleDir = 'tools\gradle-8.8' }
    '1.21.1' { $jdk = 21; $gradleDir = 'tools\gradle-8.8' }
    '1.21.11' { $jdk = 21; $gradleDir = 'tools\gradle-8.8' }
    '26.1'   { $jdk = 25; $gradleDir = 'tools\gradle-9.4.1' }
    '26.2'   { $jdk = 25; $gradleDir = 'tools\gradle-9.4.1' }
    '26.3'   { $jdk = 25; $gradleDir = 'tools\gradle-9.4.1' }
    default  { throw "Unsupported NeoForge target: $MinecraftVersion" }
}
$javaHome = Resolve-ZstdnetJdk $jdk
$gradleHome = Join-Path $buildRoot $gradleDir

$gradleBat = Join-Path $gradleHome 'bin\gradle.bat'

if (-not (Test-Path $projectDir)) {
    throw "Project directory not found: $projectDir"
}

if (-not (Test-Path $javaHome)) {
    throw "Required JDK not found: $javaHome"
}

if (-not (Test-Path $gradleBat)) {
    throw "Gradle not found: $gradleBat"
}

New-Item -ItemType Directory -Force -Path $gradleUserHome, $projectCacheDir | Out-Null

$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome 'bin') + ';' + $env:Path
$env:GRADLE_USER_HOME = $gradleUserHome

Push-Location $projectDir
try {
    & $gradleBat '--project-cache-dir' $projectCacheDir 'build'
} finally {
    Pop-Location
}
