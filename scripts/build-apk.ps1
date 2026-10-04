# 构建 DSH 安卓客户端的调试 APK。
#
# 依赖：JDK 17+、Android SDK（platform-tools / platforms;android-35 / build-tools;35.0.0）、Gradle 8.x。
# 下面三个路径按自己的环境改，或用同名环境变量覆盖。

$ErrorActionPreference = "Stop"

$JdkRoot    = if ($env:ANDROID_CLIENT_JDK)    { $env:ANDROID_CLIENT_JDK }    else { "C:\Android\jdk21" }
$SdkRoot    = if ($env:ANDROID_CLIENT_SDK)    { $env:ANDROID_CLIENT_SDK }    else { "C:\Android\sdk" }
$GradleRoot = if ($env:ANDROID_CLIENT_GRADLE) { $env:ANDROID_CLIENT_GRADLE } else { "C:\Android\gradle-8.11.1" }

$env:JAVA_HOME        = $JdkRoot
$env:ANDROID_HOME     = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:PATH = "$JdkRoot\bin;$SdkRoot\platform-tools;$GradleRoot\bin;$env:PATH"

# 项目根 = 本脚本所在目录的上一级
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $ScriptDir) { $ScriptDir = (Get-Location).Path }
$Project = Split-Path -Parent $ScriptDir
Set-Location $Project

Write-Output "JAVA_HOME   = $env:JAVA_HOME"
Write-Output "ANDROID_HOME= $env:ANDROID_HOME"
Write-Output "项目目录     = $Project"

Write-Output "== 构建 =="
# Gradle 会把「使用了过时 API」之类的提示写到 stderr；
# 在 $ErrorActionPreference='Stop' 下这会被当成错误。这里显式改判退出码。
$PrevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& "$GradleRoot\bin\gradle.bat" assembleRelease --no-daemon --console=plain 2>&1 | Out-String
$Code = $LASTEXITCODE
$ErrorActionPreference = $PrevEap

if ($Code -ne 0) { throw "Gradle 构建失败，退出码 $Code" }

$Apk = Join-Path $Project "app\build\outputs\apk\release\app-release.apk"
if (Test-Path $Apk) {
    Write-Output "APK BUILD OK: $Apk"
    Write-Output ("size = " + (Get-Item $Apk).Length + " bytes")
} else {
    Write-Output "APK BUILD FAILED"
    exit 1
}
