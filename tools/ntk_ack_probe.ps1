param(
    [string]$DeviceSerial = "emulator-5556",
    [string]$OutDir = "build\ntk-ack-probe",
    [string]$TargetEpisodePath = "/manhwa/35212/1761599",
    [string]$SiteRoot = "",
    [int]$MaxMs = 12000,
    [int]$PrepareMaxMs = 4500,
    [switch]$NoPrepared,
    [switch]$NoRequireAck,
    [switch]$NoStrictFresh,
    [switch]$NoWebViewCookieClear,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$ForceStopBeforeRun
)

$ErrorActionPreference = "Stop"
if(Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Require-Command($Name) {
    if(-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Invoke-Logged {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$LogPath
    )
    Write-Host ("> {0} {1}" -f $FilePath, ($Arguments -join " "))
    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $FilePath @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }
    $output | Set-Content -Path $LogPath -Encoding UTF8
    $output | ForEach-Object { Write-Host $_ }
    return $code
}

function Latest-File($Pattern) {
    $file = Get-ChildItem $Pattern -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if(-not $file) {
        throw "No file found for pattern: $Pattern"
    }
    return $file.FullName
}

function First-RawLine($LogText, $Marker) {
    foreach($line in ($LogText -split "`r?`n")) {
        if($line.IndexOf($Marker) -ge 0) {
            return [string]$line
        }
    }
    return ""
}

function Last-RawLine($LogText, $Marker) {
    $last = ""
    foreach($line in ($LogText -split "`r?`n")) {
        if($line.IndexOf($Marker) -ge 0) {
            $last = [string]$line
        }
    }
    return $last
}

Require-Command adb

$timestamp = "{0}_{1}" -f (Get-Date -Format "yyyyMMdd_HHmmss"), ([Guid]::NewGuid().ToString("N").Substring(0, 6))
$runDir = Join-Path $OutDir $timestamp
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

if(-not $SkipBuild) {
    $buildLog = Join-Path $runDir "gradle_build.log"
    $code = Invoke-Logged ".\gradlew.bat" @(":app:assembleDebug", ":app:assembleDebugAndroidTest") $buildLog
    if($code -ne 0) {
        throw "Gradle build failed with exit code $code. Log: $buildLog"
    }
}

if(-not $SkipInstall) {
    $apk = Latest-File "app\build\outputs\apk\debug\*-debug.apk"
    $testApk = Latest-File "app\build\outputs\apk\androidTest\debug\*-debug-androidTest.apk"
    $installLog = Join-Path $runDir "install.log"
    $code = Invoke-Logged "adb" @("-s", $DeviceSerial, "install", "-r", $apk) $installLog
    if($code -ne 0) {
        throw "App install failed with exit code $code. Log: $installLog"
    }
    $code = Invoke-Logged "adb" @("-s", $DeviceSerial, "install", "-r", $testApk) $installLog
    if($code -ne 0) {
        throw "Test install failed with exit code $code. Log: $installLog"
    }
}

if($ForceStopBeforeRun) {
    $forceStopLog = Join-Path $runDir "force_stop.log"
    $forceStopLines = @()
    foreach($packageName in @("ml.melun.mangaview", "ml.melun.mangaview.test")) {
        $arguments = @("-s", $DeviceSerial, "shell", "am", "force-stop", $packageName)
        Write-Host ("> adb {0}" -f ($arguments -join " "))
        $output = & adb @arguments 2>&1
        $code = $LASTEXITCODE
        $forceStopLines += "> adb $($arguments -join ' ')"
        if($output) {
            $forceStopLines += $output
            $output | ForEach-Object { Write-Host $_ }
        }
        $forceStopLines += "exitCode=$code"
        if($code -ne 0) {
            $forceStopLines | Set-Content -Path $forceStopLog -Encoding UTF8
            throw "Force-stop failed for $packageName with exit code $code. Log: $forceStopLog"
        }
    }
    $forceStopLines | Set-Content -Path $forceStopLog -Encoding UTF8
}

& adb -s $DeviceSerial logcat -c | Out-Null

$strictFreshArg = if($NoStrictFresh) { "false" } else { "true" }
$clearWebViewCookiesArg = if($NoWebViewCookieClear) { "false" } else { "true" }
$preparedArg = if($NoPrepared) { "false" } else { "true" }
$requireAckArg = if($NoRequireAck) { "false" } else { "true" }
$argsList = @(
    "-s", $DeviceSerial,
    "shell", "am", "instrument", "-w", "-r",
    "-e", "runLiveNetworkTests", "true",
    "-e", "ntkNativeAckProbe", "true",
    "-e", "ntkCaptchaPath", $TargetEpisodePath,
    "-e", "ntkAckProbeMaxMs", [string]$MaxMs,
    "-e", "ntkAckPrepareMaxMs", [string]$PrepareMaxMs,
    "-e", "ntkAckUsePrepared", $preparedArg,
    "-e", "ntkRequireAck", $requireAckArg,
    "-e", "ntkCaptchaStrictFresh", $strictFreshArg,
    "-e", "ntkClearWebViewCookies", $clearWebViewCookiesArg
)
if($SiteRoot -and $SiteRoot.Trim().Length -gt 0) {
    $argsList += @("-e", "ntkSiteRoot", $SiteRoot.Trim())
}
$argsList += @(
    "-e", "class", "ml.melun.mangaview.activity.NtkCaptchaLiveInstrumentedTest#nativeAdAckProbeWhenRequested",
    "ml.melun.mangaview.test/androidx.test.runner.AndroidJUnitRunner"
)

$instrumentLog = Join-Path $runDir "instrumentation.txt"
$exitCode = Invoke-Logged "adb" $argsList $instrumentLog
$instrumentText = Get-Content -Path $instrumentLog -Raw

$logcatPath = Join-Path $runDir "logcat.txt"
& adb -s $DeviceSerial logcat -d -v time | Set-Content -Path $logcatPath -Encoding UTF8
$logText = Get-Content -Path $logcatPath -Raw

$summary = [ordered]@{
    runDir = $runDir
    exitCode = $exitCode
    path = $TargetEpisodePath
    probeDone = Last-RawLine $logText "ntk_native_ack_probe_done"
    challenge = Last-RawLine $logText "ntk_native_ack_challenge_code="
    preparedChallenge = Last-RawLine $logText "ntk_native_ack_prepare_challenge_code="
    impressions = Last-RawLine $logText "ntk_native_ack_imp_seen"
    ackCode = Last-RawLine $logText "ntk_native_ack_ack_code="
    ackBody = Last-RawLine $logText "ntk_native_ack_ack_body"
    final = Last-RawLine $logText "ntk_native_ack_final_success="
    serverProof = Last-RawLine $logText "ntk_server_ack_success_recorded"
    failure = First-RawLine ($instrumentText + "`n" + $logText) "AssertionError"
}

$summaryPath = Join-Path $runDir "summary.json"
($summary | ConvertTo-Json -Depth 3) | Set-Content -Path $summaryPath -Encoding UTF8
$summary | Format-List | Out-String | Write-Host

if($exitCode -ne 0 -or $instrumentText -match "FAILURES!!!|Process crashed|INSTRUMENTATION_STATUS_CODE: -2") {
    throw "Native ACK probe failed with exit code $exitCode. Summary: $summaryPath"
}
