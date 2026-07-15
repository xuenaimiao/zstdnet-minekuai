param(
    [ValidateSet('1.20.1', '1.21.1', '26.1', '26.2')]
    [string]$MinecraftVersion = '1.21.1'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildRoot = Join-Path (Split-Path -Parent $repoRoot) 'zstdnet-build'
$gradleUserHome = Join-Path $buildRoot 'cache\gradle-user'
$projectCacheDir = Join-Path $buildRoot "cache\project-cache\zstdnet-neoforge-$MinecraftVersion"
$projectDir = Join-Path $repoRoot "mods\$MinecraftVersion\zstdnet-neoforge"

# Per-MC-version JDK + Gradle distribution (26.1 is un-obfuscated: needs JDK 25 + Gradle 9.x)
switch ($MinecraftVersion) {
    '1.20.1' { $javaHome = 'C:\Program Files\Java\jdk-17'; $gradleDir = 'tools\gradle-8.8' }
    '1.21.1' { $javaHome = 'C:\Program Files\Java\jdk-21'; $gradleDir = 'tools\gradle-8.8' }
    '26.1'   { $javaHome = 'C:\Users\78569\.jdks\liberica-25.0.3'; $gradleDir = 'tools\gradle-9.4.1' }
    '26.2'   { $javaHome = 'C:\Users\78569\.jdks\liberica-25.0.3'; $gradleDir = 'tools\gradle-9.4.1' }
    default  { throw "Unsupported NeoForge target: $MinecraftVersion" }
}
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
