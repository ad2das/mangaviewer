param(
    [string]$DeviceSerial = "emulator-5556",
    [string]$OutDir = "build\ntk-vpn-acceptance",
    [string]$SiteRoot = "https://newtoki1.org",
    [string]$ComicPath = "/manhwa/36525/1807424",
    [string]$ComicEpisodeId = "1807424",
    [string]$ComicWorkId = "36525",
    [int]$ComicImageCount = 24,
    [string]$WebtoonPath = "/webtoon/17332/1515337",
    [string]$WebtoonEpisodeId = "1515337",
    [string]$WebtoonWorkId = "17332",
    [int]$WebtoonImageCount = 24,
    [int]$ScrollSteps = 10,
    [int]$AppendSteps = 0,
    [int]$ScreenshotEvery = 0,
    [int]$FirstDrawableMaxMs = 3500,
    [int]$HoldAfterFirstDrawableMs = 22000,
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

function Get-DeviceNetworkSnapshot {
    param(
        [string]$Serial,
        [string]$Label,
        [string]$RunDirectory
    )

    $connectivityPath = Join-Path $RunDirectory ("connectivity_{0}.txt" -f $Label)
    $packagesPath = Join-Path $RunDirectory ("vpn_packages_{0}.txt" -f $Label)
    $connectivityText = ""
    $packageText = ""
    try {
        $connectivityText = (& adb -s $Serial shell dumpsys connectivity 2>&1) -join "`n"
        $connectivityText | Set-Content -Path $connectivityPath -Encoding UTF8
    } catch {
        $connectivityText = "ERROR: $($_.Exception.Message)"
        $connectivityText | Set-Content -Path $connectivityPath -Encoding UTF8
    }
    try {
        $packageText = (& adb -s $Serial shell pm list packages 2>&1) -join "`n"
        $packageText | Set-Content -Path $packagesPath -Encoding UTF8
    } catch {
        $packageText = "ERROR: $($_.Exception.Message)"
        $packageText | Set-Content -Path $packagesPath -Encoding UTF8
    }

    $activeNetwork = ""
    $activeCapabilities = ""
    $activeTransports = ""
    $vpnActive = $false
    $validated = $false
    $internet = $false
    $notVpn = $false
    $activeMatch = [regex]::Match($connectivityText, "Active default network:\s*(\d+)")
    if($activeMatch.Success) {
        $activeNetwork = $activeMatch.Groups[1].Value
        $capMatch = [regex]::Match(
            $connectivityText,
            "NetworkAgentInfo\{network\{" + [regex]::Escape($activeNetwork) + "\}[\s\S]*?nc\{\[([^\]]+)\]"
        )
        if($capMatch.Success) {
            $activeCapabilities = $capMatch.Groups[1].Value.Trim()
            $transportMatch = [regex]::Match($activeCapabilities, "Transports:\s*([^ ]+(?:\|[^ ]+)*)")
            if($transportMatch.Success) {
                $activeTransports = $transportMatch.Groups[1].Value
            }
            $vpnActive = $activeTransports -match "(^|\|)VPN($|\|)"
            $validated = $activeCapabilities -match "(^|[&\s])VALIDATED($|[&\s])"
            $internet = $activeCapabilities -match "(^|[&\s])INTERNET($|[&\s])"
            $notVpn = $activeCapabilities -match "(^|[&\s])NOT_VPN($|[&\s])"
        }
    }

    $vpnPackages = @()
    foreach($line in ($packageText -split "`r?`n")) {
        $trimmed = ([string]$line).Trim()
        if($trimmed -match "(?i)(cloudflare|warp|vpn|onedot)") {
            $vpnPackages += $trimmed
        }
    }

    return [ordered]@{
        label = $Label
        activeNetwork = $activeNetwork
        activeTransports = $activeTransports
        activeCapabilities = $activeCapabilities
        vpnActive = $vpnActive
        validated = $validated
        internet = $internet
        notVpn = $notVpn
        vpnPackageMatches = $vpnPackages
        connectivityDump = $connectivityPath
        vpnPackageDump = $packagesPath
    }
}

function Read-Summary($Path) {
    if(-not (Test-Path $Path)) {
        return $null
    }
    return Get-Content -Path $Path -Raw | ConvertFrom-Json
}

function Test-RandomPerfPass($Summary) {
    if($null -eq $Summary) {
        return $false
    }
    if(-not [bool]$Summary.passed) {
        return $false
    }
    if($Summary.exitCode -ne 0) {
        return $false
    }
    if($Summary.failures -and $Summary.failures.Count -gt 0) {
        return $false
    }
    if(-not $Summary.ackChecks -or $Summary.ackChecks.Count -lt 1) {
        return $false
    }
    foreach($check in @($Summary.ackChecks)) {
        if(-not [bool]$check.passed) {
            return $false
        }
        if(-not [bool]$check.strictProof) {
            return $false
        }
        if(-not [bool]$check.nativeBridgeAck200) {
            return $false
        }
    }
    if(-not $Summary.firstDrawable -or $Summary.firstDrawable.Count -lt 1) {
        return $false
    }
    if(-not $Summary.scroll -or $Summary.scroll.Count -lt 1) {
        return $false
    }
    if($Summary.slowFrameSignals -and $Summary.slowFrameSignals.Count -gt 0) {
        return $false
    }
    if(-not $Summary.deviceNetworkBefore -or -not [bool]$Summary.deviceNetworkBefore.vpnActive) {
        return $false
    }
    if(-not $Summary.deviceNetworkAfter -or -not [bool]$Summary.deviceNetworkAfter.vpnActive) {
        return $false
    }
    return $true
}

function Invoke-RandomPerfCase {
    param(
        [string]$Name,
        [string]$Path,
        [string]$EpisodeId,
        [string]$WorkId,
        [int]$ImageCount,
        [string]$RunDirectory
    )

    $caseOutDir = Join-Path $RunDirectory $Name
    New-Item -ItemType Directory -Force -Path $caseOutDir | Out-Null
    $logPath = Join-Path $RunDirectory ("{0}_random_perf.log" -f $Name)
    $args = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", ".\tools\ntk_random_perf.ps1",
        "-DeviceSerial", $DeviceSerial,
        "-OutDir", $caseOutDir,
        "-Runs", "1",
        "-ScrollSteps", [string]$ScrollSteps,
        "-AppendSteps", [string]$AppendSteps,
        "-ScreenshotEvery", [string]$ScreenshotEvery,
        "-Mode", "native-ack",
        "-ScrollInputMode", "touch",
        "-ScrollPattern", "mixed",
        "-TargetEpisodePath", $Path,
        "-TargetImageEpisodeId", $EpisodeId,
        "-TargetImageWorkId", $WorkId,
        "-TargetImageCount", [string]$ImageCount,
        "-NtkSiteRoot", $SiteRoot,
        "-NtkLockSiteRoot",
        "-StrictFresh",
        "-ClearAck",
        "-ClearImageCache",
        "-NoAppendProbe",
        "-AssertSchedulerGap",
        "-FirstDrawableMaxMs", [string]$FirstDrawableMaxMs,
        "-HoldAfterFirstDrawableMs", [string]$HoldAfterFirstDrawableMs,
        "-MaxDroppedFrames", "0",
        "-MaxMissedFrames", "0",
        "-RenderFrameMaxMs", "16.67",
        "-SkipBuild",
        "-SkipInstall"
    )
    if($ForceStopBeforeRun) {
        $args += "-ForceStopBeforeRun"
    }

    $code = Invoke-Logged "powershell" $args $logPath
    $summaryFile = Get-ChildItem -Path $caseOutDir -Recurse -Filter summary.json -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    $summaryPath = if($summaryFile) { $summaryFile.FullName } else { "" }
    $summary = if($summaryPath) { Read-Summary $summaryPath } else { $null }
    return [ordered]@{
        name = $Name
        path = $Path
        exitCode = $code
        passed = (Test-RandomPerfPass $summary)
        summary = $summaryPath
        log = $logPath
        parsedSummary = $summary
    }
}

Require-Command "adb"
Require-Command "powershell"

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$runDir = Join-Path $OutDir $timestamp
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$summaryPath = Join-Path $runDir "summary.json"

$networkBefore = Get-DeviceNetworkSnapshot $DeviceSerial "before" $runDir
$result = [ordered]@{
    timestamp = $timestamp
    device = $DeviceSerial
    siteRoot = $SiteRoot
    verdict = "not-run"
    passed = $false
    reason = ""
    deviceNetworkBefore = $networkBefore
    runs = @()
}

if(-not [bool]$networkBefore.vpnActive) {
    $result.verdict = "vpn-required"
    $result.reason = "Android active default network is not TRANSPORT_VPN. Enable WARP/VPN on the connected mobile device before final NTK acceptance."
    $result | ConvertTo-Json -Depth 12 | Set-Content -Path $summaryPath -Encoding UTF8
    Write-Host "NTK VPN acceptance summary"
    Write-Host ("  passed=False verdict={0}" -f $result.verdict)
    Write-Host ("  network before={0}/vpn={1}/notVpn={2}" -f $networkBefore.activeTransports, $networkBefore.vpnActive, $networkBefore.notVpn)
    Write-Host "  reason=$($result.reason)"
    Write-Host "  summary=$summaryPath"
    exit 1
}

if(-not $SkipBuild) {
    $buildLog = Join-Path $runDir "gradle_build.log"
    $code = Invoke-Logged ".\gradlew.bat" @(":app:assembleDebug", ":app:assembleDebugAndroidTest") $buildLog
    if($code -ne 0) {
        $result.verdict = "build-failed"
        $result.reason = "Gradle build failed with exit code $code. Log: $buildLog"
        $result | ConvertTo-Json -Depth 12 | Set-Content -Path $summaryPath -Encoding UTF8
        throw $result.reason
    }
}

if(-not $SkipInstall) {
    $apk = Latest-File "app\build\outputs\apk\debug\*-debug.apk"
    $testApk = Latest-File "app\build\outputs\apk\androidTest\debug\*-debug-androidTest.apk"
    $installLog = Join-Path $runDir "install.log"
    $code = Invoke-Logged "adb" @("-s", $DeviceSerial, "install", "-r", $apk) $installLog
    if($code -ne 0) {
        $result.verdict = "install-failed"
        $result.reason = "App install failed with exit code $code. Log: $installLog"
        $result | ConvertTo-Json -Depth 12 | Set-Content -Path $summaryPath -Encoding UTF8
        throw $result.reason
    }
    $code = Invoke-Logged "adb" @("-s", $DeviceSerial, "install", "-r", $testApk) $installLog
    if($code -ne 0) {
        $result.verdict = "install-failed"
        $result.reason = "Test install failed with exit code $code. Log: $installLog"
        $result | ConvertTo-Json -Depth 12 | Set-Content -Path $summaryPath -Encoding UTF8
        throw $result.reason
    }
}

$comic = Invoke-RandomPerfCase "comic" $ComicPath $ComicEpisodeId $ComicWorkId $ComicImageCount $runDir
$webtoon = Invoke-RandomPerfCase "webtoon" $WebtoonPath $WebtoonEpisodeId $WebtoonWorkId $WebtoonImageCount $runDir
$networkAfter = Get-DeviceNetworkSnapshot $DeviceSerial "after" $runDir

$result.deviceNetworkAfter = $networkAfter
$result.runs = @($comic, $webtoon)
$result.passed = ([bool]$comic.passed -and [bool]$webtoon.passed -and [bool]$networkAfter.vpnActive)
$result.verdict = if($result.passed) { "passed" } else { "failed" }
if(-not $result.passed) {
    $result.reason = "Comic and webtoon strict random perf runs must both pass with VPN active before and after."
}

$result | ConvertTo-Json -Depth 20 | Set-Content -Path $summaryPath -Encoding UTF8
Write-Host ""
Write-Host "NTK VPN acceptance summary"
Write-Host ("  passed={0} verdict={1}" -f $result.passed, $result.verdict)
Write-Host ("  network before={0}/vpn={1}/notVpn={2} after={3}/vpn={4}/notVpn={5}" -f `
    $networkBefore.activeTransports, `
    $networkBefore.vpnActive, `
    $networkBefore.notVpn, `
    $networkAfter.activeTransports, `
    $networkAfter.vpnActive, `
    $networkAfter.notVpn)
Write-Host ("  comic passed={0} summary={1}" -f $comic.passed, $comic.summary)
Write-Host ("  webtoon passed={0} summary={1}" -f $webtoon.passed, $webtoon.summary)
Write-Host "  summary=$summaryPath"

if(-not $result.passed) {
    exit 1
}
