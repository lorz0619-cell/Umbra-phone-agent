param(
    [string]$Serial = "",
    [switch]$Clear,
    [switch]$Save
)

$adb = "adb.exe"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "找不到 ADB：$adb"
}

$targetArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $targetArgs = @("-s", $Serial)
}

$state = (& $adb @targetArgs get-state 2>$null).Trim()
if ($state -ne "device") {
    throw "手机未连接、未授权或存在多个设备；多设备时请使用 -Serial 指定。"
}

if ($Clear) {
    & $adb @targetArgs logcat -c
}

$filters = @(
    "BluewhaleMain:V"
    "BluewhaleVirtualDisplay:V"
    "BluewhaleShizuku:V"
    "SilentIme:V"
    "AndroidRuntime:E"
    "*:S"
)

$logcatArgs = $targetArgs + @(
    "logcat"
    "-v"
    "threadtime"
) + $filters

Write-Host "正在监控 Umbra phone-agent，按 Ctrl+C 停止。" -ForegroundColor Cyan

if ($Save) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $logFile = Join-Path $PWD "umbra-$timestamp.log"

    Write-Host "日志同时保存至：$logFile" -ForegroundColor Yellow
    & $adb @logcatArgs | Tee-Object -FilePath $logFile
} else {
    & $adb @logcatArgs
}
