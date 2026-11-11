# 一键构建脚本（Windows）：自动准备 JDK / Android SDK / Gradle 环境，免手敲环境变量
# 用法：
#   .\build.cmd                    等价于 assembleDebug
#   .\build.cmd testDebugUnitTest  只跑单元测试
#   .\build.cmd clean assembleDebug
#
# 工具链位置优先级：
#   1. 环境变量 BELLNOTE_TOOLCHAIN 指向的目录（目录内应有 jdk-17 / sdk / gradle-8.7）
#   2. 老默认路径 D:\deepseek-harness\.toolchain
#   3. 都没有则直接用系统的 JAVA_HOME / ANDROID_HOME + gradlew
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$toolchain = $env:BELLNOTE_TOOLCHAIN
if ([string]::IsNullOrWhiteSpace($toolchain)) { $toolchain = 'D:\deepseek-harness\.toolchain' }

$jdk = Join-Path $toolchain 'jdk-17'
$sdk = Join-Path $toolchain 'sdk'
# Gradle 可写缓存放在项目内，避免 workspace-write 沙箱越权。
$gradleHome = Join-Path $root '.gradle'
$gradleBin = Join-Path $toolchain 'gradle-8.7\bin\gradle.bat'
$wrapper = Join-Path $root 'gradlew.bat'

if ($Tasks -eq $null -or $Tasks.Count -eq 0) { $Tasks = @('assembleDebug') }

if (Test-Path $jdk) {
    $env:JAVA_HOME = $jdk
    if ($env:PATH -notlike "*$jdk\bin*") { $env:PATH = "$jdk\bin;$env:PATH" }
}
if (Test-Path $sdk) {
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
}
$env:GRADLE_USER_HOME = $gradleHome

Write-Host "JAVA_HOME        = $env:JAVA_HOME"
Write-Host "ANDROID_HOME     = $env:ANDROID_HOME"
Write-Host "GRADLE_USER_HOME = $env:GRADLE_USER_HOME"
Write-Host "任务             = $($Tasks -join ' ')"
Write-Host ''

Set-Location $root
if (Test-Path $gradleBin) {
    & $gradleBin @Tasks --console=plain
} elseif (Test-Path $wrapper) {
    Write-Host '没找到本地 Gradle 8.7，改用 gradlew（首次会下载 Gradle 发行包）'
    & $wrapper @Tasks --console=plain
} else {
    Write-Host '找不到 Gradle：请安装 JDK 17 + Android SDK，或设置 BELLNOTE_TOOLCHAIN 指向离线工具链目录'
    exit 1
}

$code = $LASTEXITCODE
if ($code -eq 0 -and ($Tasks -contains 'assembleDebug')) {
    $apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    if (Test-Path $apk) {
        Write-Host ''
        Write-Host ("APK: {0} ({1:N2} MB)" -f $apk, ((Get-Item $apk).Length / 1MB))
    }
}
exit $code
