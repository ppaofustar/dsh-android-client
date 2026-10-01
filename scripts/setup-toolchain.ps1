# 一次性安装 Android 构建工具链（JDK + cmdline-tools + Gradle），然后构建 APK。
#
# 用法：先把三个 zip 放到 $Downloads，再运行本脚本。
#   jdk21.zip          -> JDK 21（Adoptium / Temurin 均可）
#   cmdline-tools.zip  -> Android command line tools
#   gradle.zip         -> Gradle 8.x 二进制发行版
#
# 已经装好工具链的话，直接用 scripts/build-apk.ps1 就行。

$ErrorActionPreference = "Stop"

$Downloads  = if ($env:ANDROID_CLIENT_DOWNLOADS) { $env:ANDROID_CLIENT_DOWNLOADS } else { "C:\Android\downloads" }
$InstallTo  = "C:\Android"
$SdkRoot    = Join-Path $InstallTo "sdk"
$JdkRoot    = Join-Path $InstallTo "jdk21"
$GradleRoot = Join-Path $InstallTo "gradle-8.11.1"
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $ScriptDir) { $ScriptDir = (Get-Location).Path }
$Project    = Split-Path -Parent $ScriptDir

function Expand-Into($zip, $destRoot, $desiredName) {
    $tmp = Join-Path $InstallTo ("tmp-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    Write-Output "  解压 $zip ..."
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $inner  = Get-ChildItem $tmp -Directory | Select-Object -First 1
    $target = Join-Path $destRoot $desiredName
    New-Item -ItemType Directory -Path $destRoot -Force | Out-Null
    Remove-Item $target -Recurse -Force -ErrorAction SilentlyContinue
    Move-Item $inner.FullName $target
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output "== 1. JDK 21 =="
if (-not (Test-Path (Join-Path $JdkRoot "bin\java.exe"))) { Expand-Into "$Downloads\jdk21.zip" $InstallTo "jdk21" }
else { Write-Output "  已存在" }

Write-Output "== 2. Android cmdline-tools =="
$CmdlineLatest = Join-Path $SdkRoot "cmdline-tools\latest"
if (-not (Test-Path (Join-Path $CmdlineLatest "bin\sdkmanager.bat"))) {
    New-Item -ItemType Directory -Path (Join-Path $SdkRoot "cmdline-tools") -Force | Out-Null
    Expand-Into "$Downloads\cmdline-tools.zip" (Join-Path $SdkRoot "cmdline-tools") "latest"
} else { Write-Output "  已存在" }

Write-Output "== 3. Gradle =="
if (-not (Test-Path (Join-Path $GradleRoot "bin\gradle.bat"))) { Expand-Into "$Downloads\gradle.zip" $InstallTo "gradle-8.11.1" }
else { Write-Output "  已存在" }

Write-Output "== 4. 环境变量 =="
$env:JAVA_HOME        = $JdkRoot
$env:ANDROID_HOME     = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:PATH = "$JdkRoot\bin;$SdkRoot\platform-tools;$GradleRoot\bin;$env:PATH"
& (Join-Path $JdkRoot "bin\java.exe") -version 2>&1 | Out-String

Write-Output "== 5. 接受许可 =="
$SdkManager = Join-Path $CmdlineLatest "bin\sdkmanager.bat"
$yes = ((1..40) | ForEach-Object { "y" }) -join "`n"
$yes | & $SdkManager --licenses 2>&1 | Select-Object -Last 3 | Out-String

Write-Output "== 6. 安装 SDK 组件 =="
& $SdkManager "platform-tools" "platforms;android-35" "build-tools;35.0.0" 2>&1 | Select-Object -Last 8 | Out-String

Write-Output "== 7. 构建 APK =="
Set-Location $Project
# Gradle 的提示会写到 stderr，Stop 模式下会被误判为错误，这里显式改判退出码
$PrevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& (Join-Path $GradleRoot "bin\gradle.bat") assembleDebug --no-daemon --console=plain 2>&1 | Out-String
$Code = $LASTEXITCODE
$ErrorActionPreference = $PrevEap
if ($Code -ne 0) { throw "Gradle 构建失败，退出码 $Code" }

Write-Output "== 8. 产物 =="
Get-ChildItem (Join-Path $Project "app\build\outputs\apk\debug") -ErrorAction SilentlyContinue |
    Select-Object FullName, Length | Format-Table -AutoSize
Write-Output "BUILD SCRIPT FINISHED"
