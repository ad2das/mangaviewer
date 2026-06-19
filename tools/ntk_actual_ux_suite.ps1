param(
    [string]$DeviceSerial = "emulator-5554",
    [string]$OutDir = "build\ntk-actual-ux-suite",
    [string[]]$Tests = @(
        "ml.melun.mangaview.EpisodeActivityNetworkTest#ntkCurrentWebtoonUxSelectionOpensReaderWithAck200",
        "ml.melun.mangaview.EpisodeActivityNetworkTest#ntkCurrentComicUxSelectionOpensReaderWithAck200",
        "ml.melun.mangaview.EpisodeActivityNetworkTest#ntkHomeContinueUxSelectionOpensReaderWithAck200"
    ),
    [int]$TimeoutMs = 180000,
    [int]$HardBlockFastFailCount = 6,
    [switch]$NoForceStopBeforeEach,
    [switch]$NoStopOnFailure
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

function Safe-Name($Value) {
    return ($Value -replace "[^A-Za-z0-9_.#-]", "_") -replace "#", "__"
}

function Invoke-LoggedProcess {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$LogPath,
        [int]$TimeoutMs,
        [string]$DeviceSerial,
        [int]$HardBlockFastFailCount
    )
    Write-Host ("> {0} {1}" -f $FilePath, ($Arguments -join " "))
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
    $timedOut = $false
    $hardBlocked = $false
    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
    while(-not $process.HasExited) {
        $remainingMs = [int][Math]::Max(0, ($deadline - [DateTime]::UtcNow).TotalMilliseconds)
        if($remainingMs -le 0) {
            $timedOut = $true
            break
        }
        [void]$process.WaitForExit([Math]::Min(1000, $remainingMs))
        if($process.HasExited) {
            break
        }
        if($HardBlockFastFailCount -gt 0 -and $DeviceSerial) {
            $snapshot = (& adb -s $DeviceSerial logcat -d -s ViewerPerf:D CustomHttpClient:D *:S 2>$null) -join "`n"
            if($snapshot -match "ntk_ack_proof=") {
                $hardBlockCount = ([regex]::Matches($snapshot, "block=cloudflare-html-403")).Count
                if($hardBlockCount -ge $HardBlockFastFailCount) {
                    $hardBlocked = $true
                    break
                }
            }
        }
    }
    if($timedOut -or $hardBlocked) {
        try { $process.Kill($true) } catch { try { $process.Kill() } catch {} }
        if($hardBlocked -and $DeviceSerial) {
            try { & adb -s $DeviceSerial shell am force-stop ml.melun.mangaview | Out-Null } catch {}
        }
    }
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $extra = if($hardBlocked) {
        "FAST_FAILED_ON_IMAGE_HARD_BLOCK count>=$HardBlockFastFailCount"
    } elseif($timedOut) {
        "TIMED_OUT_AFTER_MS=$TimeoutMs"
    } else {
        "EXIT_CODE=$($process.ExitCode)"
    }
    @($stdout, $stderr, $extra) |
        Where-Object { $_ -ne $null -and $_.Length -gt 0 } |
        Set-Content -Path $LogPath -Encoding UTF8
    Get-Content -Path $LogPath | ForEach-Object { Write-Host $_ }
    if($hardBlocked) {
        return 125
    }
    if($timedOut) {
        return 124
    }
    return $process.ExitCode
}

function First-Line($Text, $Pattern) {
    return (($Text -split "`r?`n") | Where-Object { $_ -match $Pattern } | Select-Object -First 1)
}

function Last-Line($Text, $Pattern) {
    return (($Text -split "`r?`n") | Where-Object { $_ -match $Pattern } | Select-Object -Last 1)
}

Require-Command "adb"
Require-Command ".\gradlew.bat"

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$runDir = Join-Path $OutDir $timestamp
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$deviceList = (& adb devices) -join "`n"
if($deviceList -notmatch [regex]::Escape($DeviceSerial)) {
    throw "Device not found: $DeviceSerial`n$deviceList"
}

$results = @()
foreach($test in $Tests) {
    $name = Safe-Name $test
    $testDir = Join-Path $runDir $name
    New-Item -ItemType Directory -Force -Path $testDir | Out-Null
    $gradleLog = Join-Path $testDir "gradle.log"
    $logcat = Join-Path $testDir "logcat.log"

    if(-not $NoForceStopBeforeEach) {
        & adb -s $DeviceSerial shell am force-stop ml.melun.mangaview | Out-Null
    }
    & adb -s $DeviceSerial logcat -c | Out-Null

    $args = @(
        "--no-daemon",
        ":app:connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.runLiveNetworkTests=true",
        "-Pandroid.testInstrumentationRunnerArguments.class=$test"
    )
    $exitCode = Invoke-LoggedProcess ".\gradlew.bat" $args $gradleLog $TimeoutMs $DeviceSerial $HardBlockFastFailCount
    & adb -s $DeviceSerial logcat -d > $logcat

    $logText = Get-Content -Raw -Path $logcat
    $successLine = Last-Line $logText "ntk_actual_(ux|home_continue)_.*success"
    $ackLine = Last-Line $logText "ntk_ack_proof="
    $coverageLine = Last-Line $logText "reader_visible_coverage .*missingPx=0 .*placeholderPx=0"
    $loadingLine = Last-Line $logText "reader_visible_loading=0"
    $firstDrawableLine = Last-Line $logText "reader_open_to_first_drawable"
    $hardBlockLine = Last-Line $logText "block=cloudflare-html-403"
    $failureLine = First-Line $logText "AssertionError|FAILURES!!!|INSTRUMENTATION_RESULT: shortMsg"
    $passed = ($exitCode -eq 0 -and $successLine -and $ackLine -and $coverageLine -and $loadingLine -and $firstDrawableLine)

    $results += [pscustomobject][ordered]@{
        test = $test
        exitCode = $exitCode
        passed = [bool]$passed
        successLine = [string]$successLine
        ackLine = [string]$ackLine
        firstDrawableLine = [string]$firstDrawableLine
        coverageLine = [string]$coverageLine
        loadingLine = [string]$loadingLine
        hardBlockLine = [string]$hardBlockLine
        failureLine = [string]$failureLine
        gradleLog = $gradleLog
        logcat = $logcat
    }
    if(-not $passed -and -not $NoStopOnFailure) {
        Write-Host "Stopping after first failed NTK actual UX test. Pass -NoStopOnFailure to collect every test."
        break
    }
}

$summary = [ordered]@{
    timestamp = $timestamp
    device = $DeviceSerial
    timeoutMs = $TimeoutMs
    runDir = $runDir
    passed = -not (@($results | Where-Object { -not $_.passed }).Count -gt 0)
    results = $results
}
$summaryPath = Join-Path $runDir "summary.json"
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host ""
Write-Host "NTK actual UX suite summary"
Write-Host ("  passed={0} tests={1} runDir={2}" -f $summary.passed, $results.Count, $runDir)
foreach($result in $results) {
    Write-Host ("  {0} passed={1} exit={2}" -f $result.test, $result.passed, $result.exitCode)
    if(-not $result.passed) {
        Write-Host ("    failure={0}" -f $result.failureLine)
        if($result.hardBlockLine) {
            Write-Host ("    hardBlock={0}" -f $result.hardBlockLine)
        }
        Write-Host ("    logcat={0}" -f $result.logcat)
    }
}
Write-Host "  summary=$summaryPath"

if(-not $summary.passed) {
    exit 1
}
