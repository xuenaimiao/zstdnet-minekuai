# jdk-paths.ps1 - 所有构建 / 回归脚本共用的 JDK 路径解析。
#
# 以前每个脚本各自写死一条绝对路径（如 C:\Program Files\Java\jdk-17），换机器或挪动 JDK
# 安装位置就全线报 "JAVA_HOME is set to an invalid directory"。这里改成「按大版本号在候选
# 路径里挑第一个真实存在的」，路径只维护这一份。
#
# 用法：
#   . (Join-Path $PSScriptRoot 'jdk-paths.ps1')      # scripts/ 下的脚本用 "$PSScriptRoot\..\jdk-paths.ps1"
#   $javaHome = Resolve-ZstdnetJdk 17
#
# 换机器 / 装在别处时，无需改本文件：设对应环境变量即可，它的优先级最高。
#   $env:ZSTDNET_JDK8 / ZSTDNET_JDK17 / ZSTDNET_JDK21 / ZSTDNET_JDK25 = <JDK 根目录>
#
# 候选项支持通配符（如 .jdks\liberica-17*），命中多个时取排序后的最后一个（通常是最新版本）。

function Resolve-ZstdnetJdk {
    <#
    .SYNOPSIS
        按 JDK 大版本号解析出本机可用的 JAVA_HOME。
    .PARAMETER Version
        JDK 大版本号：8 / 17 / 21 / 25。
    .OUTPUTS
        JDK 根目录的绝对路径（保证其下存在 bin\javac.exe）。找不到时抛出并列出尝试过的路径。
    #>
    param(
        [Parameter(Mandatory = $true, Position = 0)]
        [ValidateSet('8', '17', '21', '25')]
        [string]$Version
    )

    # $PSScriptRoot 在函数体内解析为「定义该函数的脚本文件」所在目录（= 仓库根），
    # 即使被别处点源后再调用也成立，故可据此定位同级的 ../zstdnet-build。
    $buildRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'zstdnet-build'
    $jdksHome = Join-Path $env:USERPROFILE '.jdks'

    $candidates = switch ($Version) {
        '8' {
            @(
                (Join-Path $jdksHome 'liberica-1.8.0_492'),
                (Join-Path $jdksHome '*1.8*'),
                'C:\Program Files\Java\jdk1.8.0',
                'C:\Program Files\Java\jdk-8',
                'C:\Program Files\Eclipse Adoptium\jdk-8*'
            )
        }
        '17' {
            @(
                'D:\JDK',
                'C:\Program Files\Java\jdk-17',
                (Join-Path $jdksHome '*-17*'),
                'C:\Program Files\Eclipse Adoptium\jdk-17*',
                'C:\Program Files\Microsoft\jdk-17*'
            )
        }
        '21' {
            @(
                (Join-Path $buildRoot 'tools\jdk-21.0.11+10'),
                (Join-Path $jdksHome 'liberica-21.0.11'),
                (Join-Path $jdksHome '*-21*'),
                'C:\Program Files\Java\jdk-21',
                'C:\Program Files\Eclipse Adoptium\jdk-21*',
                'C:\Program Files\Microsoft\jdk-21*'
            )
        }
        '25' {
            @(
                (Join-Path $jdksHome 'liberica-25.0.3'),
                (Join-Path $jdksHome '*-25*'),
                'C:\Program Files\Java\jdk-25',
                'C:\Program Files\Eclipse Adoptium\jdk-25*'
            )
        }
    }

    # 环境变量优先：显式指定了就只认它，指错了要立刻报错而不是悄悄回退到别的 JDK。
    $override = [Environment]::GetEnvironmentVariable("ZSTDNET_JDK$Version")
    if ($override) {
        if (Test-Path (Join-Path $override 'bin\javac.exe')) {
            return (Resolve-Path $override).Path
        }
        throw "ZSTDNET_JDK$Version points at '$override' but there is no bin\javac.exe under it."
    }

    foreach ($candidate in $candidates) {
        # 通配符候选可能命中多个，按名称排序取最后一个（版本号靠后 = 通常更新）。
        $matched = Get-Item -Path $candidate -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
            Sort-Object Name |
            Select-Object -Last 1
        if ($matched) {
            return $matched.FullName
        }
    }

    throw ("JDK $Version not found. Tried: " + ($candidates -join ', ') +
        ". Install it, or set `$env:ZSTDNET_JDK$Version to its root directory.")
}
