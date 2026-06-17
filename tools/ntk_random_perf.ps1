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
    [string]$TargetImageEpisodeId = "",
    [string]$TargetImageWorkId = "",
    [int]$TargetImageCount = 0,
    [string]$NtkSiteRoot = "",
    [switch]$NtkLockSiteRoot,
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
    [switch]$RequireLiveRandom,
    [switch]$NoAppendProbe,
    [switch]$ChangeDeviceIdentityBeforeRun,
    [switch]$ResetDeviceIdentityBeforeRun,
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
        $kv = Parse-Kv $payload
        $kv["_raw"] = $payload
        $items += [pscustomobject]$kv
    }
    return $items
}

function First-MetricLine($LogText, $Marker) {
    $items = @(Read-MetricLines $LogText $Marker)
    if($items.Count -eq 0) {
        return $null
    }
    return $items[0]
}

function Last-MetricLine($LogText, $Marker) {
    $items = @(Read-MetricLines $LogText $Marker)
    if($items.Count -eq 0) {
        return $null
    }
    return $items[$items.Count - 1]
}

function Metric-Value($Item, $Key) {
    if($null -eq $Item) {
        return ""
    }
    if($Item.PSObject.Properties.Name -contains $Key) {
        $value = [string]$Item.$Key
        if(-not [string]::IsNullOrWhiteSpace($value) -and $value -notmatch "\s+[A-Za-z0-9_]+=") {
            return $value
        }
    }
    if($Item.PSObject.Properties.Name -contains "_raw") {
        $raw = [string]$Item._raw
        $match = [regex]::Match($raw, "(?:^|[,\s])$([regex]::Escape($Key))=([^,\s]+)")
        if($match.Success) {
            return $match.Groups[1].Value
        }
    }
    return ""
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

function RawLine-TimeMs($Line) {
    if([string]::IsNullOrWhiteSpace($Line)) {
        return $null
    }
    $match = [regex]::Match($Line, "^\d{2}-\d{2}\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})")
    if(-not $match.Success) {
        return $null
    }
    return ([int64]$match.Groups[1].Value * 3600000L) +
        ([int64]$match.Groups[2].Value * 60000L) +
        ([int64]$match.Groups[3].Value * 1000L) +
        [int64]$match.Groups[4].Value
}

function RawLine-DeltaMs($StartLine, $EndLine) {
    $startMs = RawLine-TimeMs $StartLine
    $endMs = RawLine-TimeMs $EndLine
    if($null -eq $startMs -or $null -eq $endMs) {
        return ""
    }
    $delta = [int64]$endMs - [int64]$startMs
    if($delta -lt 0) {
        $delta += 86400000L
    }
    return [string]$delta
}

function RawLine-Value($Line, $Key) {
    if([string]::IsNullOrWhiteSpace($Line)) {
        return ""
    }
    $escaped = [regex]::Escape($Key)
    $match = [regex]::Match($Line, "(?:^|[,\s])$escaped=([^,\s]+)")
    if($match.Success) {
        return $match.Groups[1].Value
    }
    $match = [regex]::Match($Line, '"' + $escaped + '"\s*:\s*"?([^",}\s]+)"?')
    if($match.Success) {
        return $match.Groups[1].Value
    }
    return ""
}

function Ack-Phase($Name, $Line, $BaseLine) {
    if([string]::IsNullOrWhiteSpace($Line)) {
        return $null
    }
    $snippet = $Line
    $marker = "ViewerPerf"
    $idx = $snippet.IndexOf($marker)
    if($idx -ge 0) {
        $snippet = $snippet.Substring($idx + $marker.Length).Trim()
    }
    return [pscustomobject][ordered]@{
        name = $Name
        sinceAckStartMs = RawLine-DeltaMs $BaseLine $Line
        ms = RawLine-Value $Line "ms"
        elapsedMs = RawLine-Value $Line "elapsedMs"
        code = RawLine-Value $Line "code"
        raw = $snippet
    }
}

function First-Value($Items, $Key) {
    foreach($item in $Items) {
        if($item.PSObject.Properties.Name -contains $Key) {
            return [string]$item.$Key
        }
    }
    return ""
}

function First-Matching-Case($Items, $Path) {
    if([string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }
    foreach($item in $Items) {
        if($item.PSObject.Properties.Name -contains "path" -and [string]$item.path -eq $Path) {
            return $item
        }
    }
    return $null
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
if($NtkSiteRoot -and $NtkSiteRoot.Trim().Length -gt 0) {
    $argsList += @("-e", "ntkSiteRoot", $NtkSiteRoot.Trim())
}
if($NtkLockSiteRoot) {
    $argsList += @("-e", "ntkLockSiteRoot", "true")
}
if($TargetEpisodePath -and $TargetEpisodePath.Trim().Length -gt 0) {
    $argsList += @(
        "-e", "ntkTargetEpisodePath", $TargetEpisodePath.Trim(),
        "-e", "ntkDirectTargetEpisode", "true"
    )
    if($TargetImageEpisodeId -and $TargetImageEpisodeId.Trim().Length -gt 0) {
        $argsList += @("-e", "ntkTargetImageEpisodeId", $TargetImageEpisodeId.Trim())
    }
    if($TargetImageWorkId -and $TargetImageWorkId.Trim().Length -gt 0) {
        $argsList += @("-e", "ntkTargetImageWorkId", $TargetImageWorkId.Trim())
    }
    if($TargetImageCount -gt 0) {
        $argsList += @("-e", "ntkTargetImageCount", [string]$TargetImageCount)
    }
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
$titleSourceApi = @(Read-MetricLines $logText "ntk_true_random_title baseMode=")
$titleSourceDb = @(Read-MetricLines $logText "ntk_true_random_title_db")
$titleSourceCurated = @(Read-MetricLines $logText "ntk_true_random_title_curated")
$titleSourceRsc = @(Read-MetricLines $logText "ntk_true_random_title_rsc")
$titleSourceNumericProbe = @(Read-MetricLines $logText "ntk_true_random_numeric_probe" | Where-Object {
    $episodesText = Metric-Value $_ "episodes"
    (Metric-Value $_ "result") -eq "0" -and $episodesText -match "^\d+$" -and [int]$episodesText -gt 0
})
$firstDrawable = @(Read-MetricLines $logText "ntk_true_random_first_drawable")
$scroll = @(Read-MetricLines $logText "ntk_true_random_scroll")
$appendNext = @(Read-MetricLines $logText "ntk_true_random_append_next")
$appendPrev = @(Read-MetricLines $logText "ntk_true_random_append_previous")
$pipeline = [ordered]@{
    webViewAckPreflightDone = Last-MetricLine $logText "ntk_webview_ack_preflight_done"
    imageApiAfterAckProof = Last-MetricLine $logText "ntk_images_api_after_ack_proof_success"
    firstUrlPartial = First-MetricLine $logText "ntk_images_api_first_url_partial"
    firstUrlEarly = First-MetricLine $logText "ntk_images_api_first_url_early"
    earlyUrlsReady = First-MetricLine $logText "reader_repository_stage stage=early_urls_ready"
    requestForeground = First-MetricLine $logText "reader_repository_stage stage=request_foreground"
    foregroundStreamStart = First-MetricLine $logText "foreground_stream_async_start"
    foregroundStreamJoin = First-MetricLine $logText "foreground_stream_join"
    foregroundRaceWin = First-MetricLine $logText "foreground_race_win"
    foregroundStreamDone = First-MetricLine $logText "foreground_stream_async_done"
    decodeReady = First-MetricLine $logText "stage=decode_ready"
    openToFirstDrawable = Last-MetricLine $logText "reader_open_to_first_drawable"
}
$ackStartLine = First-RawLine $logText "directAckProofFirstFast3Start"
$ackPhases = @(
    Ack-Phase "ackStart" $ackStartLine $ackStartLine
    Ack-Phase "nativeChallenge" (First-RawLine $logText "directAckProofFirstFast3NativeChallenge") $ackStartLine
    Ack-Phase "challengeSelected" (First-RawLine $logText "directAckProofFirstFast3Challenge") $ackStartLine
    Ack-Phase "guardFirstStart" (First-RawLine $logText "directAckProofFirstFast3GuardFirstStart") $ackStartLine
    Ack-Phase "preGuardCanaryStart" (First-RawLine $logText "guardNativePreGuardCanaryStart") $ackStartLine
    Ack-Phase "bridgeCanaryFirst" (First-RawLine $logText "ntk_viewer_ad_bridge_quic_first code=") $ackStartLine
    Ack-Phase "guardModule" (First-RawLine $logText "ackOnlySyncGuardModule") $ackStartLine
    Ack-Phase "guardInitSync" (First-RawLine $logText "ackOnlySyncGuardInitSync") $ackStartLine
    Ack-Phase "bridgeCanaryLast" (Last-RawLine $logText "ntk_viewer_ad_bridge_quic_first code=") $ackStartLine
    Ack-Phase "canaryBeforeAck" (First-RawLine $logText "ntk_viewer_ad_bridge_canary_before_ack") $ackStartLine
    Ack-Phase "nativeSubmit" (First-RawLine $logText "ntk_viewer_ad_bridge_native_submit") $ackStartLine
    Ack-Phase "guardFirstDone" (First-RawLine $logText "directAckProofFirstFast3GuardFirstDone") $ackStartLine
) | Where-Object { $null -ne $_ }
$ackPreflightStages = @(Read-MetricLines $logText "ntk_webview_ack_preflight_stage")
$slowFrames = ($logText -split "`r?`n") | Where-Object { $_ -match "reader_slow_frame|surface_jank_v3|reader_visible_gap|reader_visible_loading=true" }
$failureLines = (($instrumentText + "`n" + $logText) -split "`r?`n") |
    Where-Object { $_ -match "FAILURES!!!|AssertionError|INSTRUMENTATION_STATUS: stack|Process crashed|ntk_true_random_first_drawable_fast_fail|reader_scroll_jump" }
$casePaths = @($cases | ForEach-Object { [string]$_.path } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$uniqueCasePaths = @($casePaths | Select-Object -Unique)
$uniqueTitlePaths = @($casePaths | ForEach-Object {
    if($_ -match "^/(?:manhwa|webtoon)/\d+") { $Matches[0] }
} | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
$caseCuratedCount = @($cases | Where-Object { ([string]$_.title).StartsWith("ntk-curated-") }).Count
$caseTargetCount = @($cases | Where-Object {
    ([string]$_.title).StartsWith("ntk-target-") -or
        ([string]$_.title).StartsWith("ntk-direct-target")
}).Count
$caseSourceCoverage = if($cases.Count -eq 0) {
    "none"
} elseif(-not [string]::IsNullOrWhiteSpace($TargetEpisodePath)) {
    "target-repro"
} elseif($caseCuratedCount -eq $cases.Count) {
    "curated-only"
} elseif($caseCuratedCount -gt 0) {
    "mixed-with-curated"
} else {
    "live-random"
}
$titleSourceCounts = [ordered]@{
    api = $titleSourceApi.Count
    db = $titleSourceDb.Count
    rsc = $titleSourceRsc.Count
    numericProbe = $titleSourceNumericProbe.Count
    curated = $titleSourceCurated.Count
    caseCurated = $caseCuratedCount
    caseTarget = $caseTargetCount
    coverage = $caseSourceCoverage
}
if(-not $NoDiversityAssert -and [string]::IsNullOrWhiteSpace($TargetEpisodePath) -and $Runs -gt 1 -and $cases.Count -gt 1) {
    $requiredEpisodePaths = [Math]::Min([int]$Runs, 2)
    $requiredTitlePaths = if($Runs -ge 4) { 2 } else { 1 }
    if($uniqueCasePaths.Count -lt $requiredEpisodePaths -or $uniqueTitlePaths.Count -lt $requiredTitlePaths) {
        $failureLines += ("NTK_RANDOM_DIVERSITY_ASSERT uniqueEpisodePaths={0},requiredEpisodePaths={1},uniqueTitlePaths={2},requiredTitlePaths={3},paths={4}" -f `
                $uniqueCasePaths.Count, $requiredEpisodePaths, $uniqueTitlePaths.Count, $requiredTitlePaths, ($uniqueCasePaths -join "|"))
    }
}
if($RequireLiveRandom -and [string]::IsNullOrWhiteSpace($TargetEpisodePath) -and $caseSourceCoverage -ne "live-random") {
    $failureLines += ("NTK_LIVE_RANDOM_ASSERT coverage={0},api={1},db={2},rsc={3},numeric={4},curated={5},caseCurated={6}/{7}" -f `
            $caseSourceCoverage, `
            $titleSourceApi.Count, `
            $titleSourceDb.Count, `
            $titleSourceRsc.Count, `
            $titleSourceNumericProbe.Count, `
            $titleSourceCurated.Count, `
            $caseCuratedCount, `
            $cases.Count)
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
        $hasNativeBridgeAck200 = $false
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
            if($line -match "ntk_native_ack_bridge_submit code=200,path=$pathRe(\b|,|$)") {
                $hasNativeBridgeAck200 = $true
                $hasStrictProof = $true
            }
        }
        if($hasWebDone -or $hasReaderDone -or $hasNativeBridgeAck200) {
            $hasStart = $true
        }
        $ok = $hasStrictProof -and ($hasWebDone -or $hasReaderDone -or $hasNativeBridgeAck200)
        $ackChecks += [pscustomobject]@{
            run = $case.run
            path = $case.path
            started = $hasStart
            webViewDone = $hasWebDone
            readerDone = $hasReaderDone
            strictProof = $hasStrictProof
            nativeBridgeAck200 = $hasNativeBridgeAck200
            falseDone = $hasFalseDone
            passed = $ok
        }
        if(-not $ok) {
            $ackFailureLines += "NTK_ACK_ASSERT run=$($case.run),path=$($case.path),started=$hasStart,webViewDone=$hasWebDone,readerDone=$hasReaderDone,strictProof=$hasStrictProof,nativeBridgeAck200=$hasNativeBridgeAck200,falseDone=$hasFalseDone"
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
$reproCase = First-Matching-Case $cases $reproPath
$reproImageEpisodeId = if($reproCase) { Metric-Value $reproCase "imageEpisodeId" } else { "" }
$reproImageWorkId = if($reproCase) { Metric-Value $reproCase "imageWorkId" } else { "" }
$reproImageCount = if($reproCase) { Metric-Value $reproCase "imageCount" } else { "" }
if([string]::IsNullOrWhiteSpace($reproImageEpisodeId)) { $reproImageEpisodeId = $TargetImageEpisodeId }
if([string]::IsNullOrWhiteSpace($reproImageWorkId)) { $reproImageWorkId = $TargetImageWorkId }
if([string]::IsNullOrWhiteSpace($reproImageWorkId) -and $reproCase) { $reproImageWorkId = Metric-Value $reproCase "titleId" }
if([string]::IsNullOrWhiteSpace($reproImageCount) -or $reproImageCount -eq "0") {
    $reproImageCount = if($TargetImageCount -gt 0) { [string]$TargetImageCount } else { "" }
}
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
if($reproImageEpisodeId -and $reproImageEpisodeId.Trim().Length -gt 0) {
    $reproArgs += @("-TargetImageEpisodeId", $reproImageEpisodeId.Trim())
}
if($reproImageWorkId -and $reproImageWorkId.Trim().Length -gt 0) {
    $reproArgs += @("-TargetImageWorkId", $reproImageWorkId.Trim())
}
if($reproImageCount -and $reproImageCount.Trim().Length -gt 0 -and $reproImageCount -ne "0") {
    $reproArgs += @("-TargetImageCount", $reproImageCount.Trim())
}
if($NtkSiteRoot -and $NtkSiteRoot.Trim().Length -gt 0) {
    $reproArgs += @("-NtkSiteRoot", $NtkSiteRoot.Trim())
}
if($NtkLockSiteRoot) {
    $reproArgs += "-NtkLockSiteRoot"
}
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
if($NoAckAssert) {
    $reproArgs += "-NoAckAssert"
}
if($RequireLiveRandom) {
    $reproArgs += "-RequireLiveRandom"
}
if($ForceStopBeforeRun) {
    $reproArgs += "-ForceStopBeforeRun"
}
if($EnsureAccessBefore) {
    $reproArgs += "-EnsureAccessBefore"
    $reproArgs += "-EnsureAccessMaxMs"
    $reproArgs += [string]$EnsureAccessMaxMs
}

$liveRandomBlockedReason = ""
$nextRootProbeCommand = ""
if([string]::IsNullOrWhiteSpace($TargetEpisodePath) -and $caseSourceCoverage -ne "live-random") {
    $probeRoots = if($NtkSiteRoot -and $NtkSiteRoot.Trim().Length -gt 0) {
        $NtkSiteRoot.Trim()
    } else {
        "https://sbxh7.com,https://sbxh6.com,https://toonflix.app,https://sbxh5.com"
    }
    $liveRandomBlockedReason = ("caseSourceCoverage={0}; run root probe before claiming final live-random proof" -f $caseSourceCoverage)
    $nextRootProbeCommand = ".\tools\ntk_root_probe.ps1 -DeviceSerial $DeviceSerial -Roots `"$probeRoots`" -TimeoutMs 5000 -MaxRoots 12 -IncludeResolvedRoots -RequireApiJsonRoot -ForceStopBeforeRun -SkipBuild -SkipInstall"
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
    requireLiveRandom = [bool]$RequireLiveRandom
    targetEpisodePath = $TargetEpisodePath
    targetImageEpisodeId = $TargetImageEpisodeId
    targetImageWorkId = $TargetImageWorkId
    targetImageCount = $TargetImageCount
    ntkSiteRoot = $NtkSiteRoot
    ntkLockSiteRoot = [bool]$NtkLockSiteRoot
    uniqueEpisodePathCount = $uniqueCasePaths.Count
    uniqueEpisodePaths = $uniqueCasePaths
    uniqueTitlePathCount = $uniqueTitlePaths.Count
    uniqueTitlePaths = $uniqueTitlePaths
    titleSourceCounts = $titleSourceCounts
    liveRandomBlockedReason = $liveRandomBlockedReason
    nextRootProbeCommand = $nextRootProbeCommand
    failurePath = $failurePath
    failureMode = $failureMode
    started = $starts
    cases = $cases
    firstDrawable = $firstDrawable
    pipeline = $pipeline
    ackPhases = $ackPhases
    ackPreflightStages = $ackPreflightStages
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
[string]$nextRootProbeCommand | Set-Content -Path (Join-Path $runDir "next_root_probe_command.txt") -Encoding UTF8

Write-Host ""
Write-Host "NTK random perf summary"
Write-Host ("  passed={0} exitCode={1} seed={2}" -f $summary.passed, $exitCode, $Seed)
Write-Host ("  cases={0} firstDrawable={1} scroll={2} slowSignals={3} failures={4}" -f $cases.Count, $firstDrawable.Count, $scroll.Count, $slowFrames.Count, $failureLines.Count)
Write-Host ("  pipeline ackMs={0} apiMs={1} earlyUrlsMs={2} fgStart={3} join={4} fgMs={5} streamMs={6} decodeMs={7} drawableMs={8}" -f `
    (Metric-Value $pipeline.webViewAckPreflightDone "ms"), `
    (Metric-Value $pipeline.imageApiAfterAckProof "ms"), `
    (Metric-Value $pipeline.earlyUrlsReady "ms"), `
    (Metric-Value $pipeline.foregroundStreamStart "ms"), `
    (Metric-Value $pipeline.foregroundStreamJoin "ms"), `
    (Metric-Value $pipeline.foregroundRaceWin "ms"), `
    (Metric-Value $pipeline.foregroundStreamDone "ms"), `
    (Metric-Value $pipeline.decodeReady "ms"), `
    (Metric-Value $pipeline.openToFirstDrawable "ms"))
if($ackPhases.Count -gt 0) {
    $ackPhaseSummary = @($ackPhases | ForEach-Object {
        $extra = ""
        if(-not [string]::IsNullOrWhiteSpace([string]$_.ms)) {
            $extra = "/ms=$($_.ms)"
        } elseif(-not [string]::IsNullOrWhiteSpace([string]$_.elapsedMs)) {
            $extra = "/elapsed=$($_.elapsedMs)"
        } elseif(-not [string]::IsNullOrWhiteSpace([string]$_.code)) {
            $extra = "/code=$($_.code)"
        }
        "{0}@{1}{2}" -f $_.name, $_.sinceAckStartMs, $extra
    }) -join " "
    Write-Host "  ackPhases $ackPhaseSummary"
}
if($ackPreflightStages.Count -gt 0) {
    $stageSummary = @($ackPreflightStages | ForEach-Object {
        "{0}={1}/{2}" -f (Metric-Value $_ "stage"), (Metric-Value $_ "ms"), (Metric-Value $_ "totalMs")
    }) -join " "
    Write-Host "  ackPreflightStages $stageSummary"
}
Write-Host ("  uniqueEpisodePaths={0} uniqueTitlePaths={1}" -f $uniqueCasePaths.Count, $uniqueTitlePaths.Count)
Write-Host ("  titleSources api={0} db={1} rsc={2} numeric={3} curated={4} caseCurated={5}/{6} coverage={7}" -f `
    $titleSourceCounts.api, `
    $titleSourceCounts.db, `
    $titleSourceCounts.rsc, `
    $titleSourceCounts.numericProbe, `
    $titleSourceCounts.curated, `
    $titleSourceCounts.caseCurated, `
    $cases.Count, `
    $titleSourceCounts.coverage)
if($liveRandomBlockedReason.Length -gt 0) {
    Write-Host "  liveRandomBlockedReason=$liveRandomBlockedReason"
    Write-Host "  nextRootProbe=$nextRootProbeCommand"
}
Write-Host "  summary=$summaryPath"
Write-Host "  logcat=$logcatPath"
if($screenshotFiles.Count -gt 0) {
    Write-Host "  screenshots=$($screenshotFiles -join ',')"
}
Write-Host "  repro=$($summary.reproCommand)"

if(-not $summary.passed) {
    exit 1
}
