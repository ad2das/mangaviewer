param(
    [string]$DeviceSerial = "emulator-5556",
    [string]$OutDir = "build\ntk-ack-ux-probe",
    [string]$TargetEpisodePath = "/webtoon/17332/1515337",
    [string]$SiteRoot = "",
    [int]$MaxMs = 45000,
    [switch]$NoRequireAck,
    [switch]$NoStrictFresh,
    [switch]$NoWebViewCookieClear,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$ForceStopBeforeRun,
    [switch]$EnableWebViewDebuggingForDiagnostics,
    [switch]$DisableTurnstileAutomationForDiagnostics,
    [switch]$UseRawWebViewUserAgentForDiagnostics,
    [switch]$DisableRootBootstrapForDiagnostics,
    [switch]$RelaxWindowSettingsForDiagnostics,
    [switch]$UseChromeUaMetadataForDiagnostics,
    [switch]$StopOnRootStageTimeout,
    [switch]$StopOnRootTerminalFailure,
    [switch]$NoForceStopAfterRun
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
        [string]$LogPath,
        [int]$TimeoutMs = 0
    )
    Write-Host ("> {0} {1}" -f $FilePath, ($Arguments -join " "))
    if($TimeoutMs -gt 0) {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $FilePath
        foreach($arg in $Arguments) {
            [void]$psi.ArgumentList.Add($arg)
        }
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $psi
        [void]$process.Start()
        if(-not $process.WaitForExit($TimeoutMs)) {
            try { $process.Kill($true) } catch { try { $process.Kill() } catch {} }
            $stdout = $process.StandardOutput.ReadToEnd()
            $stderr = $process.StandardError.ReadToEnd()
            @($stdout, $stderr, "TIMED_OUT_AFTER_MS=$TimeoutMs") |
                Where-Object { $_ -ne $null -and $_.Length -gt 0 } |
                Set-Content -Path $LogPath -Encoding UTF8
            Get-Content -Path $LogPath | ForEach-Object { Write-Host $_ }
            return 124
        }
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        @($stdout, $stderr) |
            Where-Object { $_ -ne $null -and $_.Length -gt 0 } |
            Set-Content -Path $LogPath -Encoding UTF8
        Get-Content -Path $LogPath | ForEach-Object { Write-Host $_ }
        return $process.ExitCode
    }
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

function Invoke-InstrumentationUntilMarker {
    param(
        [string[]]$Arguments,
        [string]$LogPath,
        [int]$TimeoutMs,
        [string]$DeviceSerial,
        [string[]]$StopMarkers
    )
    Write-Host ("> adb {0}" -f ($Arguments -join " "))
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "adb"
    foreach($arg in $Arguments) {
        [void]$psi.ArgumentList.Add($arg)
    }
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    [void]$process.Start()

    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
    $foundMarker = ""
    $timedOut = $false
    while(-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 1000
        $logSnapshot = (& adb -s $DeviceSerial logcat -d -v time 2>$null) -join "`n"
        foreach($marker in $StopMarkers) {
            if($marker -and $logSnapshot.Contains($marker)) {
                $foundMarker = $marker
                break
            }
        }
        if($foundMarker.Length -gt 0) {
            try { $process.Kill($true) } catch { try { $process.Kill() } catch {} }
            break
        }
    }
    if(-not $process.HasExited) {
        $timedOut = $true
        try { $process.Kill($true) } catch { try { $process.Kill() } catch {} }
    }
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $suffix = if($foundMarker.Length -gt 0) {
        "STOPPED_AFTER_MARKER=$foundMarker"
    } elseif($timedOut) {
        "TIMED_OUT_AFTER_MS=$TimeoutMs"
    } elseif($process.HasExited) {
        "EXITED_WITHOUT_MARKER_EXIT_CODE=$($process.ExitCode)"
    } else {
        "INSTRUMENTATION_ENDED_WITHOUT_CLASSIFICATION"
    }
    @($stdout, $stderr, $suffix) |
        Where-Object { $_ -ne $null -and $_.Length -gt 0 } |
        Set-Content -Path $LogPath -Encoding UTF8
    Get-Content -Path $LogPath | ForEach-Object { Write-Host $_ }
    if($foundMarker.Length -gt 0) {
        return 124
    }
    if($process.HasExited) {
        return $process.ExitCode
    }
    return 124
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

function Marker-Lines($LogText, $Marker, $Count = 4) {
    return (($LogText -split "`r?`n") |
        Where-Object { $_.Contains($Marker) } |
        Select-Object -Last $Count) -join "`n"
}

function Save-WebViewDebugTargets {
    param(
        [string]$DeviceSerial,
        [string]$OutDir,
        [int]$Port = 9230
    )
    try {
        $sockets = (& adb -s $DeviceSerial shell cat /proc/net/unix 2>$null |
            Select-String -Pattern "webview_devtools_remote") |
            ForEach-Object { $_.Line }
        $sockets | Set-Content -Path (Join-Path $OutDir "webview_debug_sockets.txt") -Encoding UTF8
        $socket = (($sockets | Select-String -Pattern "@webview_devtools_remote_\d+" |
            Select-Object -Last 1).Matches.Value).TrimStart("@")
        if(-not $socket) {
            "NO_WEBVIEW_DEBUG_SOCKET" | Set-Content -Path (Join-Path $OutDir "webview_debug_pages.json") -Encoding UTF8
            return
        }
        & adb -s $DeviceSerial forward --remove ("tcp:{0}" -f $Port) 2>$null | Out-Null
        & adb -s $DeviceSerial forward ("tcp:{0}" -f $Port) ("localabstract:{0}" -f $socket) | Out-Null
        try {
            (Invoke-WebRequest -UseBasicParsing ("http://127.0.0.1:{0}/json" -f $Port) -TimeoutSec 4).Content |
                Set-Content -Path (Join-Path $OutDir "webview_debug_pages.json") -Encoding UTF8
        } catch {
            ("WEBVIEW_DEBUG_HTTP_ERROR={0}" -f $_.Exception.Message) |
                Set-Content -Path (Join-Path $OutDir "webview_debug_pages.json") -Encoding UTF8
        }
    } catch {
        ("WEBVIEW_DEBUG_COLLECT_ERROR={0}" -f $_.Exception.Message) |
            Set-Content -Path (Join-Path $OutDir "webview_debug_pages.json") -Encoding UTF8
    }
}

function Save-DeviceState {
    param(
        [string]$DeviceSerial,
        [string]$OutDir
    )
    try {
        (& adb -s $DeviceSerial shell dumpsys window 2>$null) |
            Set-Content -Path (Join-Path $OutDir "window_focus.txt") -Encoding UTF8
    } catch {
        ("WINDOW_FOCUS_COLLECT_ERROR={0}" -f $_.Exception.Message) |
            Set-Content -Path (Join-Path $OutDir "window_focus.txt") -Encoding UTF8
    }
    try {
        (& adb -s $DeviceSerial shell dumpsys activity top 2>$null) |
            Set-Content -Path (Join-Path $OutDir "activity_top.txt") -Encoding UTF8
    } catch {
        ("ACTIVITY_TOP_COLLECT_ERROR={0}" -f $_.Exception.Message) |
            Set-Content -Path (Join-Path $OutDir "activity_top.txt") -Encoding UTF8
    }
    try {
        $remote = "/sdcard/ntk_ack_ux_probe_final.png"
        & adb -s $DeviceSerial shell screencap -p $remote | Out-Null
        & adb -s $DeviceSerial pull $remote (Join-Path $OutDir "screen_final.png") | Out-Null
        & adb -s $DeviceSerial shell rm $remote | Out-Null
    } catch {
        ("SCREENSHOT_COLLECT_ERROR={0}" -f $_.Exception.Message) |
            Set-Content -Path (Join-Path $OutDir "screen_final.error.txt") -Encoding UTF8
    }
}

Require-Command adb

$timestamp = "{0}_{1}" -f (Get-Date -Format "yyyyMMdd_HHmmss"), ([Guid]::NewGuid().ToString("N").Substring(0, 6))
if($SiteRoot -and $SiteRoot.Trim().Length -gt 0) {
    $hostToken = $SiteRoot.Trim().Replace("https://", "").Replace("http://", "").Replace("/", "_").Replace(".", "_")
    $timestamp = "{0}_{1}_{2}" -f (Get-Date -Format "yyyyMMdd_HHmmss"), $hostToken, ([Guid]::NewGuid().ToString("N").Substring(0, 6))
}
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
    foreach($packageName in @("ml.melun.mangaview", "ml.melun.mangaview.test")) {
        & adb -s $DeviceSerial shell am force-stop $packageName | Out-Null
    }
}

& adb -s $DeviceSerial logcat -c | Out-Null

$strictFreshArg = if($NoStrictFresh) { "false" } else { "true" }
$clearWebViewCookiesArg = if($NoWebViewCookieClear) { "false" } else { "true" }
$requireAckArg = if($NoRequireAck) { "false" } else { "true" }
$argsList = @(
    "-s", $DeviceSerial,
    "shell", "am", "instrument", "-w", "-r",
    "-e", "runLiveNetworkTests", "true",
    "-e", "ntkAckUxProbe", "true",
    "-e", "ntkCaptchaPath", $TargetEpisodePath,
    "-e", "ntkAckProbeMaxMs", [string]$MaxMs,
    "-e", "ntkRequireAck", $requireAckArg,
    "-e", "ntkCaptchaStrictFresh", $strictFreshArg,
    "-e", "ntkClearWebViewCookies", $clearWebViewCookiesArg
)
if($EnableWebViewDebuggingForDiagnostics) {
    $argsList += @("-e", "ntkEnableWebViewDebuggingForDiagnostics", "true")
}
if($DisableTurnstileAutomationForDiagnostics) {
    $argsList += @("-e", "ntkDisableTurnstileAutomationForDiagnostics", "true")
}
if($UseRawWebViewUserAgentForDiagnostics) {
    $argsList += @("-e", "ntkUseRawWebViewUserAgentForDiagnostics", "true")
}
if($DisableRootBootstrapForDiagnostics) {
    $argsList += @("-e", "ntkDisableRootBootstrapForDiagnostics", "true")
}
if($RelaxWindowSettingsForDiagnostics) {
    $argsList += @("-e", "ntkRelaxWindowSettingsForDiagnostics", "true")
}
if($UseChromeUaMetadataForDiagnostics) {
    $argsList += @("-e", "ntkUseChromeUaMetadataForDiagnostics", "true")
}
if($SiteRoot -and $SiteRoot.Trim().Length -gt 0) {
    $argsList += @("-e", "ntkSiteRoot", $SiteRoot.Trim())
}
$argsList += @(
    "-e", "class", "ml.melun.mangaview.activity.NtkCaptchaLiveInstrumentedTest#captchaActivityAttemptsAdAckWhenRequested",
    "ml.melun.mangaview.test/androidx.test.runner.AndroidJUnitRunner"
)

$instrumentLog = Join-Path $runDir "instrumentation.txt"
$instrumentTimeoutMs = [Math]::Max($MaxMs + 45000, 45000)
$stopMarkers = @("ntk_captcha_ack_probe_done", "ntk_ack_proof", "ntk_native_ack_final_success=true")
if($NoRequireAck -or $StopOnRootStageTimeout) {
    $stopMarkers += @("NTK root bootstrap stage timeout")
}
if($StopOnRootTerminalFailure) {
    $stopMarkers += @(
        "NTK root bootstrap QUIC main-frame blocked",
        "Attention Required! | Cloudflare",
        "Sorry, you have been blocked",
        "ERR_CONNECTION_RESET",
        "ERR_CONNECTION_CLOSED"
    )
}
$exitCode = Invoke-InstrumentationUntilMarker $argsList $instrumentLog $instrumentTimeoutMs $DeviceSerial $stopMarkers
$instrumentText = Get-Content -Path $instrumentLog -Raw
if($EnableWebViewDebuggingForDiagnostics) {
    Save-WebViewDebugTargets $DeviceSerial $runDir
}
Save-DeviceState $DeviceSerial $runDir

$logcatPath = Join-Path $runDir "logcat.txt"
& adb -s $DeviceSerial logcat -d -v time | Set-Content -Path $logcatPath -Encoding UTF8
$logText = Get-Content -Path $logcatPath -Raw
$windowFocusPath = Join-Path $runDir "window_focus.txt"
$windowFocusText = if(Test-Path $windowFocusPath) { Get-Content -Path $windowFocusPath -Raw } else { "" }

$summary = [ordered]@{
    runDir = $runDir
    exitCode = $exitCode
    path = $TargetEpisodePath
    siteRoot = $SiteRoot
    rootStart = Marker-Lines $logText "Starting NTK captcha via root bootstrap"
    rootError = Marker-Lines $logText "NTK root bootstrap main-frame error"
    quicFallback = Marker-Lines $logText "Retrying NTK captcha WebView with QUIC HTML fallback"
    quicUnusable = Marker-Lines $logText "NTK QUIC WebView intercept unusable"
    quicRootBlocked = Marker-Lines $logText "NTK root bootstrap QUIC main-frame blocked"
    quicRootSatisfied = Marker-Lines $logText "NTK root bootstrap main-frame satisfied through QUIC"
    rootNotFinished = Marker-Lines $logText "NTK root bootstrap not finished"
    rootStageTimeout = Marker-Lines $logText "NTK root bootstrap stage timeout"
    rootFinished = Marker-Lines $logText "NTK root bootstrap finished"
    quicIntercept = Marker-Lines $logText "Intercepted NTK WebView request through QUIC"
    webViewAckDone = Marker-Lines $logText "ntk_webview_ack_preflight_done"
    serverProof = Marker-Lines $logText "ntk_server_ack_success_recorded"
    strictServerProof = (($logText -split "`r?`n") |
        Where-Object { $_.Contains("ntk_server_ack_success_recorded") -and $_.Contains("strictAdAck=true") } |
        Select-Object -Last 4) -join "`n"
    ackProof = Marker-Lines $logText "ntk_ack_proof"
    probeDone = Marker-Lines $logText "ntk_captcha_ack_probe_done"
    captchaEnv = Marker-Lines $logText "ntk_captcha_env" 6
    turnstile = Marker-Lines $logText "Turnstile" 8
    postMessage = Marker-Lines $logText "postMessage" 4
    rawWebViewUa = Marker-Lines $logText "Using raw WebView UA for NTK diagnostics" 2
    rootBootstrapSkipped = Marker-Lines $logText "Skipping NTK root bootstrap for diagnostics" 4
    relaxedWindowSettings = Marker-Lines $logText "Relaxing WebView window settings for NTK diagnostics" 2
    chromeUaMetadata = Marker-Lines $logText "Chrome UA metadata" 4
    instrumentationTimedOut = $instrumentText.Contains("TIMED_OUT_AFTER_MS=")
    exitedWithoutMarker = $instrumentText.Contains("EXITED_WITHOUT_MARKER_EXIT_CODE=")
    instrumentationCrashed = $instrumentText.Contains("Process crashed")
    systemAnr = (($instrumentText + "`n" + $logText) -match "ANR|Application Not Responding|isn't responding")
    stoppedAfterMarker = Marker-Lines $instrumentText "STOPPED_AFTER_MARKER" 1
    directCfChallengePost = Marker-Lines $logText "NTK WebView request needs direct WebView transport" 8
    mainFrame403 = Marker-Lines $logText "NTK root bootstrap main-frame HTTP error" 4
    webViewDebugPages = if(Test-Path (Join-Path $runDir "webview_debug_pages.json")) {
        Get-Content -Path (Join-Path $runDir "webview_debug_pages.json") -Raw
    } else { "" }
    windowFocus = Marker-Lines $windowFocusText "mCurrentFocus" 2
    focusedApp = Marker-Lines $windowFocusText "mFocusedApp" 2
    screenFinal = if(Test-Path (Join-Path $runDir "screen_final.png")) { Join-Path $runDir "screen_final.png" } else { "" }
    connectionReset = Marker-Lines $logText "ERR_CONNECTION_RESET"
    connectionClosed = Marker-Lines $logText "ERR_CONNECTION_CLOSED"
    failure = Marker-Lines ($instrumentText + "`n" + $logText) "AssertionError" 2
}

$summaryPath = Join-Path $runDir "summary.json"
($summary | ConvertTo-Json -Depth 4) | Set-Content -Path $summaryPath -Encoding UTF8
$summary | Format-List | Out-String | Write-Host

if(-not $NoForceStopAfterRun) {
    foreach($packageName in @("ml.melun.mangaview", "ml.melun.mangaview.test")) {
        & adb -s $DeviceSerial shell am force-stop $packageName | Out-Null
    }
}

$hasUsefulClassification = $summary.probeDone `
    -or $summary.rootStageTimeout `
    -or $summary.quicRootBlocked `
    -or $summary.mainFrame403 `
    -or $summary.connectionReset `
    -or $summary.connectionClosed `
    -or $summary.captchaEnv `
    -or $summary.turnstile
if(((($exitCode -ne 0) -and (-not $hasUsefulClassification)) -or ($instrumentText -match "FAILURES!!!|Process crashed|INSTRUMENTATION_STATUS_CODE: -2"))) {
    throw "UX ACK probe failed with exit code $exitCode. Summary: $summaryPath"
}
$requiresStrictAckProof = -not $NoRequireAck
if($requiresStrictAckProof -and -not $summary.strictServerProof) {
    throw "UX ACK probe did not record strict server proof. Summary: $summaryPath"
}
