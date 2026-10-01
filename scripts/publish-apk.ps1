# 把构建好的 APK + 界面修正文件整理成一个「发布目录」，供手机下载与热更新。
#
# 产出目录结构：
#   <PublishDir>\dsh-chat.apk      手机安装包
#   <PublishDir>\index.html        下载页
#   <PublishDir>\dsh-ext.css       界面修正样式（热更新，改完不用重装）
#   <PublishDir>\dsh-ext.js        界面修正脚本（热更新）
#
# 之后用任意 HTTP 服务把这个目录发出去（局域网直连 / 反向代理 / 内网穿透都行）。
# 客户端里「界面修正」的来源地址 = 这个目录的网址。

$ErrorActionPreference = "Stop"

$PublishDir = if ($env:ANDROID_CLIENT_PUBLISH_DIR) { $env:ANDROID_CLIENT_PUBLISH_DIR } else { "$HOME\dsh-publish" }
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $ScriptDir) { $ScriptDir = (Get-Location).Path }
$Project    = Split-Path -Parent $ScriptDir
$SrcApk     = Join-Path $Project "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $SrcApk)) { throw "APK 不存在，请先运行 scripts\build-apk.ps1：$SrcApk" }

New-Item -ItemType Directory -Path $PublishDir -Force | Out-Null

Copy-Item $SrcApk (Join-Path $PublishDir "dsh-chat.apk") -Force
Write-Output "APK    -> $PublishDir\dsh-chat.apk  ($((Get-Item (Join-Path $PublishDir 'dsh-chat.apk')).Length) bytes)"

foreach ($f in @("dsh-ext.css", "dsh-ext.js")) {
    $src = Join-Path $Project "web-ext\$f"
    if (Test-Path $src) {
        Copy-Item $src (Join-Path $PublishDir $f) -Force
        Write-Output "热更新 -> $PublishDir\$f  ($((Get-Item (Join-Path $PublishDir $f)).Length) bytes)"
    }
}

$page = Join-Path $Project "web-ext\download-page.html"
if (Test-Path $page) {
    Copy-Item $page (Join-Path $PublishDir "index.html") -Force
    Write-Output "下载页 -> $PublishDir\index.html"
}

Write-Output ""
Write-Output "发布目录已就绪：$PublishDir"
Write-Output "接下来用任意静态服务器把它发出去，例如："
Write-Output "  python -m http.server 8099 --directory `"$PublishDir`""
