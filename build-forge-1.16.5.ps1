# ZstdNet — Forge 1.16.5 构建脚本（最老变体，独立工具链）。
#
# 1.16.5 与其余 Forge 变体的工具链根本不同，故单独成脚本（不并入 build-forge.ps1）：
#   - JDK 8（class 52）：1.16.5 跑 Java 8；且 ForgeGradle 5 的 ForgeFlower 反编译器跑在宿主 JVM，
#     JDK16+ 会崩，故整套构建（守护进程 + 反编译 + 编译）都必须在 JDK 8 上。
#   - Gradle 7.6.4 + ForgeGradle 5.1.+：FG5 需 Gradle 7.x（不是其它变体的 gradle-8.8）。
#   - MCP snapshot 20210309-1.16.5 映射（official 1.16.5 无类名）。
#   - 内嵌 zstd-jni 资源 jar（1.16.5 FML 无 jarJar），运行期经 ZstdCodecs 门面隔离加载。
# 详见仓库根 ADDING_A_VARIANT.md §13。

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildRoot = Join-Path (Split-Path -Parent $repoRoot) 'zstdnet-build'
$gradleHome = Join-Path $buildRoot 'tools\gradle-7.6.4'
$gradleUserHome = Join-Path $buildRoot 'cache\gradle-user'
$projectCacheDir = Join-Path $buildRoot 'cache\project-cache\zstdnet-forge-1165'
$projectDir = Join-Path $repoRoot 'mods\1.16.5\zstdnet-forge'
$gradleBat = Join-Path $gradleHome 'bin\gradle.bat'

# JDK 8：优先本机 liberica；留几个常见回退位。
$jdk8Candidates = @(
    'C:\Users\78569\.jdks\liberica-1.8.0_492',
    'C:\Program Files\Java\jdk1.8.0',
    'C:\Program Files\Eclipse Adoptium\jdk-8'
)
$javaHome = $jdk8Candidates | Where-Object { Test-Path (Join-Path $_ 'bin\javac.exe') } | Select-Object -First 1

if (-not (Test-Path $projectDir)) {
    throw "Project directory not found: $projectDir"
}
if (-not $javaHome) {
    throw "JDK 8 not found. Tried: $($jdk8Candidates -join ', '). Set one or edit this script."
}
if (-not (Test-Path $gradleBat)) {
    throw "Gradle 7.6.4 not found: $gradleBat (download gradle-7.6.4-bin and unzip to that path)."
}

Write-Host "Using JDK 8: $javaHome"

$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome 'bin') + ';' + $env:Path
$env:GRADLE_USER_HOME = $gradleUserHome

New-Item -ItemType Directory -Force -Path $gradleUserHome, $projectCacheDir | Out-Null

Push-Location $projectDir
try {
    & $gradleBat '--project-cache-dir' $projectCacheDir '--console=plain' 'build'
} finally {
    Pop-Location
}
