param(
    [string]$DeviceSerial = "emulator-5556",
    [string]$OutDir = "build\ntk-random-perf",
    [int]$Runs = 1,
    [int]$ScrollSteps = 4,
    [int]$AppendSteps = 12,
    [int]$ScreenshotEvery = 0,
    [string]$Mode = "native-ack",
    [string]$ScrollInputMode = "touch",
    [string]$ScrollPattern = "mixed",
    [string]$TargetEpisodePath = "",
    [long]$Seed = 0,
    [int]$FirstDrawableMaxMs = 3500,
    [int]$InitialContinuousPages = 0,
    [int]$InitialContinuousMaxMs = 3500,
    [int]$HoldAfterFirstDrawableMs = 0,
    [int]$PostStopDriftMs = 650,
    [switch]$EnsureAccessBefore,
    [int]$EnsureAccessMaxMs = 30000,
    [int]$MaxDroppedFrames = 0,
    [int]$MaxMissedFrames = 0,
    [double]$RenderFrameMaxMs = 16.67,
    [switch]$AssertSchedulerGap,
    [switch]$StrictFresh,
    [switch]$ClearAck,
    [switch]$ClearImageCache,
    [switch]$NoAckAssert,
    [switch]$NoDiversityAssert,
    [switch]$NoAppendProbe,
    [switch]$ChangeDeviceIdentityBeforeRun,
    [switch]$ResetDeviceIdentityBeforeRun,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$ForceStopBeforeRun
)

$ErrorActionPreference = "Stop"

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
    $output = & $FilePath @Arguments 2>&1
    $code = $LASTEXITCODE
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

function Parse-Kv($Text) {
    $result = [ordered]@{}
    if(-not $Text) {
        return $result
    }
    foreach($match in [regex]::Matches($Text, "([A-Za-z0-9_]+)=([^,\r\n]*)")) {
        $result[$match.Groups[1].Value] = $match.Groups[2].Value
    }
    return $result
}

function Read-MetricLines($LogText, $Marker) {
    $items = @()
    foreach($line in ($LogText -split "`r?`n")) {
        $idx = $line.IndexOf($Marker)
        if($idx -lt 0) {
            continue
        }
        $payload = $line.Substring($idx + $Marker.Length).Trim()
        $items += [pscustomobject](Parse-Kv $payload)
    }
    return $items
}

function First-Value($Items, $Key) {
    foreach($item in $Items) {
        if($item.PSObject.Properties.Name -contains $Key) {
            return [string]$item.$Key
        }
    }
    return ""
}

Require-Command adb

if($Seed -eq 0) {
    $Seed = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
}

if($StrictFresh) {
    $ClearAck = $true
    $ClearImageCache = $true
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
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
if($ScreenshotEvery -gt 0) {
    & adb -s $DeviceSerial shell "rm -f /sdcard/Android/data/ml.melun.mangaview/cache/ntk-random-scroll-*.png" 2>$null | Out-Null
}

$appendProbe = if($NoAppendProbe) { "false" } else { "true" }
$clearAckArg = if($ClearAck) { "true" } else { "false" }
$clearImageCacheArg = if($ClearImageCache) { "true" } else { "false" }
$assertSchedulerGapArg = if($AssertSchedulerGap) { "true" } else { "false" }
$ensureAccessBeforeArg = if($EnsureAccessBefore) { "true" } else { "false" }
$changeDeviceIdentityArg = if($ChangeDeviceIdentityBeforeRun) { "true" } else { "false" }
$resetDeviceIdentityArg = if($ResetDeviceIdentityBeforeRun) { "true" } else { "false" }

$argsList = @(
    "-s", $DeviceSerial,
    "shell", "am", "instrument", "-w", "-r",
    "-e", "runLiveNetworkTests", "true",
    "-e", "ntkSafeNetwork", "true",
    "-e", "ntkRandomRuns", [string]$Runs,
    "-e", "ntkRandomSeed", [string]$Seed,
    "-e", "ntkScrollSteps", [string]$ScrollSteps,
    "-e", "ntkScreenshotEvery", [string]$ScreenshotEvery,
    "-e", "ntkAppendProbe", $appendProbe,
    "-e", "ntkAppendSteps", [string]$AppendSteps,
    "-e", "ntkFirstDrawableMaxMs", [string]$FirstDrawableMaxMs,
    "-e", "ntkInitialContinuousPages", [string]$InitialContinuousPages,
    "-e", "ntkInitialContinuousMaxMs", [string]$InitialContinuousMaxMs,
    "-e", "ntkHoldAfterFirstDrawableMs", [string]$HoldAfterFirstDrawableMs,
    "-e", "ntkPostStopDriftMs", [string]$PostStopDriftMs,
    "-e", "ntkEnsureAccessBefore", $ensureAccessBeforeArg,
    "-e", "ntkEnsureAccessMaxMs", [string]$EnsureAccessMaxMs,
    "-e", "ntkAssertNoJank", "true",
    "-e", "ntkAssertNoSchedulerGap", $assertSchedulerGapArg,
    "-e", "ntkMaxDroppedFrames", [string]$MaxDroppedFrames,
    "-e", "ntkMaxMissedFrames", [string]$MaxMissedFrames,
    "-e", "ntkRenderFrameMaxMs", ([string]::Format([Globalization.CultureInfo]::InvariantCulture, "{0:0.##}", $RenderFrameMaxMs)),
    "-e", "ntkScrollInputMode", $ScrollInputMode,
    "-e", "ntkScrollPattern", $ScrollPattern,
    "-e", "ntkClearAckBeforeRun", $clearAckArg,
    "-e", "ntkClearReaderImageCacheBeforeRun", $clearImageCacheArg,
    "-e", "ntkChangeDeviceIdentityBeforeRun", $changeDeviceIdentityArg,
    "-e", "ntkResetDeviceIdentityBeforeRun", $resetDeviceIdentityArg
)

if($Mode -and $Mode.Trim().Length -gt 0 -and $Mode -ne "mixed") {
    $argsList += @("-e", "ntkMode", $Mode.Trim())
}
if($TargetEpisodePath -and $TargetEpisodePath.Trim().Length -gt 0) {
    $argsList += @(
        "-e", "ntkTargetEpisodePath", $TargetEpisodePath.Trim(),
        "-e", "ntkDirectTargetEpisode", "true"
    )
}

$argsList += @(
    "-e", "class", "ml.melun.mangaview.reader.NtkRandomStressInstrumentedTest#randomNtkEpisodesOpenAndScroll",
    "ml.melun.mangaview.test/androidx.test.runner.AndroidJUnitRunner"
)

$instrumentLog = Join-Path $runDir "instrumentation.txt"
$exitCode = Invoke-Logged "adb" $argsList $instrumentLog

$logcatPath = Join-Path $runDir "logcat.txt"
& adb -s $DeviceSerial logcat -d -v time | Set-Content -Path $logcatPath -Encoding UTF8
$logText = Get-Content -Path $logcatPath -Raw
if(-not $NoAckAssert -and $Mode -eq "native-ack" -and
    $logText -match "reader_ntk_ack_preflight_start path=" -and
    $logText -notmatch "ntk_server_ack_success_recorded path=" -and
    $logText -notmatch "ntk_webview_ack_preflight_done path=.*success=(true|false)" -and
    $logText -notmatch "reader_ntk_ack_webview_preflight_done path=.*success=(true|false)") {
    $ackTailLog = Join-Path $runDir "ack_tail_wait.log"
    $ackTailLines = @("ACK preflight still active after instrumentation; collecting tail logcat.")
    for($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 1
        & adb -s $DeviceSerial logcat -d -v time | Set-Content -Path $logcatPath -Encoding UTF8
        $logText = Get-Content -Path $logcatPath -Raw
        if($logText -match "ntk_server_ack_success_recorded path=" -or
            $logText -match "ntk_webview_ack_preflight_done path=.*success=(true|false)" -or
            $logText -match "reader_ntk_ack_webview_preflight_done path=.*success=(true|false)") {
            $ackTailLines += "resolvedAfterSeconds=$($i + 1)"
            break
        }
    }
    $ackTailLines | Set-Content -Path $ackTailLog -Encoding UTF8
}

$screenshotFiles = @()
if($ScreenshotEvery -gt 0) {
    $screenshotDir = Join-Path $runDir "screenshots"
    New-Item -ItemType Directory -Force -Path $screenshotDir | Out-Null
    $remoteList = & adb -s $DeviceSerial shell "ls /sdcard/Android/data/ml.melun.mangaview/cache/ntk-random-scroll-*.png 2>/dev/null" 2>$null
    foreach($remote in @($remoteList)) {
        $remotePath = ([string]$remote).Trim()
        if([string]::IsNullOrWhiteSpace($remotePath) -or $remotePath.Contains("No such file")) {
            continue
        }
        $fileName = Split-Path $remotePath -Leaf
        $localPath = Join-Path $screenshotDir $fileName
        & adb -s $DeviceSerial pull $remotePath $localPath | Out-Null
        if(Test-Path $localPath) {
            $screenshotFiles += $localPath
        }
    }
}

$logText = Get-Content -Path $logcatPath -Raw
$instrumentText = Get-Content -Path $instrumentLog -Raw
$starts = @(Read-MetricLines $logText "ntk_true_random_start")
$cases = @(Read-MetricLines $logText "ntk_true_random_case_start")
$firstDrawable = @(Read-MetricLines $logText "ntk_true_random_first_drawable")
$scroll = @(Read-MetricLines $logText "ntk_true_random_scroll")
$appendNext = @(Read-MetricLines $logText "ntk_true_random_append_next")
$appendPrev = @(Read-MetricLines $logText "ntk_true_random_append_previous")
$slowFrames = ($logText -split "`r?`n") | Where-Object { $_ -match "reader_slow_frame|surface_jank_v3|reader_visible_gap|reader_visible_loading=true" }
$failureLines = (($instrumentText + "`n" + $logText) -split "`r?`n") |
    Where-Object { $_ -match "FAILURES!!!|AssertionError|INSTRUMENTATION_STATUS: stack|Process crashed|ntk_true_random_first_drawable_fast_fail|reader_scroll_jump" }
$casePaths = @($cases | ForEach-Object { [string]$_.path } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$uniqueCasePaths = @($casePaths | Select-Object -Unique)
$uniqueTitlePaths = @($casePaths | ForEach-Object {
    if($_ -match "^/(?:manhwa|webtoon)/\d+") { $Matches[0] }
} | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
if(-not $NoDiversityAssert -and [string]::IsNullOrWhiteSpace($TargetEpisodePath) -and $Runs -gt 1 -and $cases.Count -gt 1) {
    $requiredEpisodePaths = [Math]::Min([int]$Runs, 2)
    $requiredTitlePaths = if($Runs -ge 4) { 2 } else { 1 }
    if($uniqueCasePaths.Count -lt $requiredEpisodePaths -or $uniqueTitlePaths.Count -lt $requiredTitlePaths) {
        $failureLines += ("NTK_RANDOM_DIVERSITY_ASSERT uniqueEpisodePaths={0},requiredEpisodePaths={1},uniqueTitlePaths={2},requiredTitlePaths={3},paths={4}" -f `
                $uniqueCasePaths.Count, $requiredEpisodePaths, $uniqueTitlePaths.Count, $requiredTitlePaths, ($uniqueCasePaths -join "|"))
    }
}

$ackChecks = @()
$ackFailureLines = @()
if(-not $NoAckAssert) {
    $logLines = @($logText -split "`r?`n")
    $caseStarts = @()
    for($i = 0; $i -lt $logLines.Count; $i++) {
        if($logLines[$i].IndexOf("ntk_true_random_case_start") -lt 0) {
            continue
        }
        $payload = $logLines[$i].Substring($logLines[$i].IndexOf("ntk_true_random_case_start") + "ntk_true_random_case_start".Length).Trim()
        $kv = Parse-Kv $payload
        $caseStarts += [pscustomobject]@{
            index = $i
            run = [string]$kv.run
            mode = [string]$kv.mode
            path = [string]$kv.path
        }
    }
    for($ci = 0; $ci -lt $caseStarts.Count; $ci++) {
        $case = $caseStarts[$ci]
        if($case.mode -ne "native-ack") {
            continue
        }
        if([string]::IsNullOrWhiteSpace($case.path)) {
            continue
        }
        $end = if($ci + 1 -lt $caseStarts.Count) { [int]$caseStarts[$ci + 1].index } else { $logLines.Count }
        $pathRe = [regex]::Escape($case.path)
        $hasStart = $false
        $hasWebDone = $false
        $hasReaderDone = $false
        $hasStrictProof = $false
        $hasFalseDone = $false
        for($li = [int]$case.index; $li -lt $end; $li++) {
            $line = $logLines[$li]
            if($line -match "reader_ntk_ack_preflight_start path=$pathRe(\b|,|$)") {
                $hasStart = $true
            }
            if($line -match "ntk_webview_ack_preflight_done path=$pathRe,success=true(\b|,|$)") {
                $hasWebDone = $true
            }
            if($line -match "ntk_webview_ack_preflight_done path=$pathRe,success=false(\b|,|$)") {
                $hasFalseDone = $true
            }
            if($line -match "reader_ntk_ack_webview_preflight_done path=$pathRe,success=true(\b|,|$)") {
                $hasReaderDone = $true
            }
            if($line -match "reader_ntk_ack_webview_preflight_done path=$pathRe,success=false(\b|,|$)") {
                $hasFalseDone = $true
            }
            if($line -match "ntk_server_ack_success_recorded path=$pathRe,source=") {
                $hasStrictProof = $true
            }
        }
        $ok = $hasStart -and $hasStrictProof
        $ackChecks += [pscustomobject]@{
            run = $case.run
            path = $case.path
            started = $hasStart
            webViewDone = $hasWebDone
            readerDone = $hasReaderDone
            strictProof = $hasStrictProof
            falseDone = $hasFalseDone
            passed = $ok
        }
        if(-not $ok) {
            $ackFailureLines += "NTK_ACK_ASSERT run=$($case.run),path=$($case.path),started=$hasStart,webViewDone=$hasWebDone,readerDone=$hasReaderDone,strictProof=$hasStrictProof,falseDone=$hasFalseDone"
        }
    }
}
$failureLines = @($failureLines) + @($ackFailureLines)

$failurePath = ""
$failureMode = ""
foreach($line in $failureLines) {
    if(-not $failurePath -and ([string]$line) -match "path=([^,\s]+)") {
        $failurePath = $Matches[1]
    }
    if(-not $failureMode -and ([string]$line) -match "mode=([^,\s]+)") {
        $failureMode = $Matches[1]
    }
}

$firstPath = First-Value $cases "path"
$firstMode = First-Value $cases "mode"
$reproPath = if($failurePath) { $failurePath } elseif($firstPath) { $firstPath } else { $TargetEpisodePath }
$reproMode = if($failureMode) { $failureMode } elseif($firstMode) { $firstMode } else { $Mode }
$reproArgs = @(
    ".\tools\ntk_random_perf.ps1",
    "-DeviceSerial", $DeviceSerial,
    "-Runs", "1",
    "-ScrollSteps", [string]$ScrollSteps,
    "-AppendSteps", [string]$AppendSteps,
    "-ScreenshotEvery", [string]$ScreenshotEvery,
    "-Seed", [string]$Seed,
    "-Mode", $reproMode,
    "-ScrollInputMode", $ScrollInputMode,
    "-ScrollPattern", $ScrollPattern,
    "-HoldAfterFirstDrawableMs", [string]$HoldAfterFirstDrawableMs,
    "-TargetEpisodePath", $reproPath
)
if($StrictFresh -or ($ClearAck -and $ClearImageCache)) {
    $reproArgs += "-StrictFresh"
} else {
    if($ClearAck) { $reproArgs += "-ClearAck" }
    if($ClearImageCache) { $reproArgs += "-ClearImageCache" }
}
if($NoAppendProbe) {
    $reproArgs += "-NoAppendProbe"
}
if($AssertSchedulerGap) {
    $reproArgs += "-AssertSchedulerGap"
}
if($NoDiversityAssert) {
    $reproArgs += "-NoDiversityAssert"
}
if($ForceStopBeforeRun) {
    $reproArgs += "-ForceStopBeforeRun"
}
if($EnsureAccessBefore) {
    $reproArgs += "-EnsureAccessBefore"
    $reproArgs += "-EnsureAccessMaxMs"
    $reproArgs += [string]$EnsureAccessMaxMs
}

$summary = [ordered]@{
    timestamp = $timestamp
    device = $DeviceSerial
    exitCode = $exitCode
    passed = ($exitCode -eq 0 -and $failureLines.Count -eq 0)
    seed = $Seed
    requestedRuns = $Runs
    scrollSteps = $ScrollSteps
    screenshotEvery = $ScreenshotEvery
    appendProbe = (-not $NoAppendProbe)
    strictFresh = [bool]$StrictFresh
    clearAck = [bool]$ClearAck
    clearImageCache = [bool]$ClearImageCache
    forceStopBeforeRun = [bool]$ForceStopBeforeRun
    ensureAccessBefore = [bool]$EnsureAccessBefore
    ensureAccessMaxMs = $EnsureAccessMaxMs
    mode = $Mode
    scrollInputMode = $ScrollInputMode
    scrollPattern = $ScrollPattern
    assertSchedulerGap = [bool]$AssertSchedulerGap
    targetEpisodePath = $TargetEpisodePath
    uniqueEpisodePathCount = $uniqueCasePaths.Count
    uniqueEpisodePaths = $uniqueCasePaths
    uniqueTitlePathCount = $uniqueTitlePaths.Count
    uniqueTitlePaths = $uniqueTitlePaths
    failurePath = $failurePath
    failureMode = $failureMode
    started = $starts
    cases = $cases
    firstDrawable = $firstDrawable
    scroll = $scroll
    appendNext = $appendNext
    appendPrevious = $appendPrev
    slowFrameSignals = $slowFrames
    ackChecks = $ackChecks
    failures = $failureLines
    instrumentationLog = $instrumentLog
    logcat = $logcatPath
    screenshots = $screenshotFiles
    reproCommand = ($reproArgs -join " ")
}

$summaryPath = Join-Path $runDir "summary.json"
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryPath -Encoding UTF8
($reproArgs -join " ") | Set-Content -Path (Join-Path $runDir "repro_command.txt") -Encoding UTF8

Write-Host ""
Write-Host "NTK random perf summary"
Write-Host ("  passed={0} exitCode={1} seed={2}" -f $summary.passed, $exitCode, $Seed)
Write-Host ("  cases={0} firstDrawable={1} scroll={2} slowSignals={3} failures={4}" -f $cases.Count, $firstDrawable.Count, $scroll.Count, $slowFrames.Count, $failureLines.Count)
Write-Host ("  uniqueEpisodePaths={0} uniqueTitlePaths={1}" -f $uniqueCasePaths.Count, $uniqueTitlePaths.Count)
Write-Host "  summary=$summaryPath"
Write-Host "  logcat=$logcatPath"
if($screenshotFiles.Count -gt 0) {
    Write-Host "  screenshots=$($screenshotFiles -join ',')"
}
Write-Host "  repro=$($summary.reproCommand)"

if(-not $summary.passed) {
    exit 1
}
