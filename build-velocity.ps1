# 构建 Velocity 代理端插件变体（服务端专用，不涉及 MC 加载器）。
# 版本无关：针对 velocity-api 3.4.0 编译、产出 Java 17 字节码，同一个 jar 即可在 Velocity 3.4/3.5(JDK21) 与 4.x(JDK25) 上加载。
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildRoot = Join-Path (Split-Path -Parent $repoRoot) 'zstdnet-build'
$gradleHome = Join-Path $buildRoot 'tools\gradle-8.8'
$gradleUserHome = Join-Path $buildRoot 'cache\gradle-user'
$projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-velocity'
$projectDir = Join-Path $repoRoot 'mods\velocity\zstdnet-velocity'
$gradleBat = Join-Path $gradleHome 'bin\gradle.bat'

. (Join-Path $repoRoot 'jdk-paths.ps1')
$javaHome = Resolve-ZstdnetJdk 17

if (-not (Test-Path $projectDir)) {
    throw "Project directory not found: $projectDir"
}

if (-not (Test-Path $gradleBat)) {
    throw "Gradle 8.8 not found: $gradleBat"
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
