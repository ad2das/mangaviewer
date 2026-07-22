param(
    [string]$DeviceSerial = "emulator-5556",
    [string]$OutDir = "build\ntk-root-probe",
    [string]$Roots = "https://toki30.com,https://sbxh9.com,https://newtoki1.org",
    [int]$TimeoutMs = 3500,
    [int]$MaxRoots = 16,
    [string]$UserAgent = "",
    [switch]$IncludeResolvedRoots,
    [switch]$RequireApiJsonRoot,
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

function Marker-Lines($LogText, $Marker, $Count = 120) {
    return (($LogText -split "`r?`n") |
        Where-Object { $_.Contains($Marker) } |
        Select-Object -Last $Count) -join "`n"
}

function Parse-KvPayload($Payload) {
    $map = @{}
    if([string]::IsNullOrWhiteSpace($Payload)) {
        return $map
    }
    foreach($part in ($Payload -split ",")) {
        $idx = $part.IndexOf("=")
        if($idx -lt 0) {
            continue
        }
        $key = $part.Substring(0, $idx).Trim()
        $value = $part.Substring($idx + 1).Trim()
        if($key.Length -gt 0) {
            $map[$key] = $value
        }
    }
    return $map
}

function Read-RootProbeRecords($LogText) {
    $records = @()
    foreach($line in ($LogText -split "`r?`n")) {
        $idx = $line.IndexOf("ntk_root_probe root=")
        if($idx -lt 0) {
            continue
        }
        $payload = $line.Substring($idx + "ntk_root_probe ".Length)
        $kv = Parse-KvPayload $payload
        if($kv.ContainsKey("root") -and $kv.ContainsKey("name")) {
            $records += [pscustomobject]$kv
        }
    }
    return $records
}

function Classify-RootProbe($Records) {
    $groups = @($Records | Group-Object root)
    $out = @()
    foreach($group in $groups) {
        $items = @($group.Group)
        $codes = @($items | ForEach-Object { [string]$_.code })
        $samples = (($items | ForEach-Object { [string]$_.sample }) -join " ").ToLowerInvariant()
        $errors = (($items | ForEach-Object { [string]$_.error }) -join " ").ToLowerInvariant()
        $types = (($items | ForEach-Object { [string]$_.type }) -join " ").ToLowerInvariant()
        $locations = (($items | ForEach-Object { [string]$_.location }) -join " ").ToLowerInvariant()
        $challenge = @($items | Where-Object { [string]$_.name -match "challenge" })
        $challengeJsonOk = @($challenge | Where-Object {
            [string]$_.code -eq "200" -and [string]$_.type -match "json"
        }).Count -gt 0
        $apiJsonOk = @($items | Where-Object {
            [string]$_.name -eq "okhttp_api" -and [string]$_.code -match "^2\d\d$" -and [string]$_.type -match "json"
        }).Count -gt 0
        $kind = "unknown"
        if($challengeJsonOk -or $apiJsonOk) {
            $kind = "api-json-ok"
        } elseif(($samples.Contains("뉴토끼 공식 주소안내") -or $samples.Contains("telegram") `
                -or $locations.Contains("t.me/") -or $locations.Contains("telegram"))) {
            $kind = "address-guide"
        } elseif($codes -contains "403" -or $samples.Contains("just a moment") -or $samples.Contains("cloudflare")) {
            $kind = "cf-block"
        } elseif($errors.Contains("ssl_version_or_cipher_mismatch") -or $errors.Contains("handshake_failure")) {
            $kind = "tls-fail"
        } elseif($errors.Contains("connection reset")) {
            $kind = "reset"
        } elseif($errors.Contains("name_not_resolved") -or $errors.Contains("unknownhost")) {
            $kind = "dns-fail"
        }
        $bestChallenge = @($challenge | Where-Object { [string]$_.code -ne "0" } | Select-Object -First 1)
        $out += [pscustomobject][ordered]@{
            root = $group.Name
            class = $kind
            challengeCode = if($bestChallenge.Count -gt 0) { [string]$bestChallenge[0].code } else { "" }
            candidates = (($items | ForEach-Object { [string]$_.candidates } | Where-Object {
                $_ -and $_ -ne "-"
            } | Select-Object -First 1) -join "")
            errors = (($items | ForEach-Object { [string]$_.error } | Where-Object {
                $_ -and $_ -ne "-"
            } | Select-Object -First 2) -join " | ")
        }
    }
    return $out
}

function Classify-TransportProbeRecord($Record) {
    $name = [string]$Record.name
    $code = [string]$Record.code
    $type = ([string]$Record.type).ToLowerInvariant()
    $sample = ([string]$Record.sample).ToLowerInvariant()
    $error = ([string]$Record.error).ToLowerInvariant()
    $location = ([string]$Record.location).ToLowerInvariant()
    if($code -match "^2\d\d$" -and $type.Contains("json")) {
        return "api-json-ok"
    }
    if(($sample.Contains("뉴토끼 공식 주소안내") -or $sample.Contains("telegram") `
            -or $location.Contains("t.me/") -or $location.Contains("telegram"))) {
        return "address-guide"
    }
    if($code -match "^2\d\d$") {
        return "http-ok"
    }
    if($code -match "^30\d$") {
        return "redirect"
    }
    if($error.Contains("err_quic_protocol_error") -or $error.Contains("quicexceptionwrapper")) {
        return "quic-protocol-error"
    }
    if($error.Contains("err_connection_reset") -or $error.Contains("connection reset")) {
        return "connection-reset"
    }
    if($error.Contains("timed out") -or $error.Contains("timeout")) {
        return "timeout"
    }
    if($error.Contains("unknownhost") -or $error.Contains("name_not_resolved")) {
        return "dns-fail"
    }
    if($code -eq "403" -or $sample.Contains("cloudflare") -or $sample.Contains("just a moment")) {
        return "cf-block"
    }
    if($error.Length -gt 0) {
        return "error"
    }
    return "unknown"
}

function Build-TransportOutcomes($Records) {
    $out = @()
    foreach($record in $Records) {
        $out += [pscustomobject][ordered]@{
            root = [string]$record.root
            name = [string]$record.name
            outcome = Classify-TransportProbeRecord $record
            code = [string]$record.code
            ms = [string]$record.ms
            type = [string]$record.type
            location = [string]$record.location
            error = [string]$record.error
            sample = [string]$record.sample
        }
    }
    return $out
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
    foreach($packageName in @("ml.melun.mangaview", "ml.melun.mangaview.test")) {
        & adb -s $DeviceSerial shell am force-stop $packageName | Out-Null
    }
}

& adb -s $DeviceSerial logcat -c | Out-Null

$includeResolvedArg = if($IncludeResolvedRoots) { "true" } else { "false" }
$quotedUserAgent = ""
if(-not [string]::IsNullOrWhiteSpace($UserAgent)) {
    $quotedUserAgent = "'" + $UserAgent.Trim().Replace("'", "'\''") + "'"
}
$argsList = @(
    "-s", $DeviceSerial,
    "shell", "am", "instrument", "-w", "-r",
    "-e", "runLiveNetworkTests", "true",
    "-e", "ntkRoots", $Roots,
    "-e", "ntkRootProbeTimeoutMs", [string]$TimeoutMs,
    "-e", "ntkRootProbeMaxRoots", [string]$MaxRoots,
    "-e", "ntkIncludeResolvedRoots", $includeResolvedArg
)
if($quotedUserAgent.Length -gt 0) {
    $argsList += @("-e", "ntkProbeUserAgent", $quotedUserAgent)
}
$argsList += @(
    "-e", "class", "ml.melun.mangaview.mangaview.NtkRootTransportProbeInstrumentedTest#rootTransportVariantsReportStatusCodes",
    "ml.melun.mangaview.test/androidx.test.runner.AndroidJUnitRunner"
)

$instrumentLog = Join-Path $runDir "instrumentation.txt"
$exitCode = Invoke-Logged "adb" $argsList $instrumentLog
$instrumentText = Get-Content -Path $instrumentLog -Raw

$logcatPath = Join-Path $runDir "logcat.txt"
& adb -s $DeviceSerial logcat -d -v time | Set-Content -Path $logcatPath -Encoding UTF8
$logText = Get-Content -Path $logcatPath -Raw
$probeRecords = @(Read-RootProbeRecords $logText)
$rootClasses = @(Classify-RootProbe $probeRecords)
$transportOutcomes = @(Build-TransportOutcomes $probeRecords)
$transportOutcomeCounts = [ordered]@{}
foreach($transportGroup in @($transportOutcomes | Group-Object outcome)) {
    $transportOutcomeCounts[$transportGroup.Name] = $transportGroup.Count
}
$apiJsonRoots = @($rootClasses | Where-Object { $_.class -eq "api-json-ok" } | ForEach-Object { [string]$_.root })
$rootClassCounts = [ordered]@{}
foreach($classGroup in @($rootClasses | Group-Object class)) {
    $rootClassCounts[$classGroup.Name] = $classGroup.Count
}
$rootProbeVerdict = if($apiJsonRoots.Count -gt 0) {
    "live-api-root-available"
} else {
    "no-live-api-root"
}
$ackBlockedReason = if($apiJsonRoots.Count -gt 0) {
    ""
} elseif($rootClasses.Count -gt 0) {
    "No api-json-ok root. classes=" + (($rootClassCounts.GetEnumerator() | ForEach-Object {
        "$($_.Key):$($_.Value)"
    }) -join ",")
} else {
    "No ntk_root_probe records were captured."
}
$nextLiveRandomCommand = ""
if($apiJsonRoots.Count -gt 0) {
    $liveRoot = $apiJsonRoots[0]
    $nextLiveRandomCommand = ".\tools\ntk_physical_qualification.ps1 -AppApkPath <benchmark.apk> -TestApkPath <benchmark-androidTest.apk> -Scope all -DeviceSerial $DeviceSerial -NtkSiteRoot `"$liveRoot`""
}
$userAgentArg = if([string]::IsNullOrWhiteSpace($UserAgent)) { "" } else { " -UserAgent `"$($UserAgent.Trim())`"" }
$nextRootProbeCommand = ".\tools\ntk_root_probe.ps1 -DeviceSerial $DeviceSerial -Roots `"$Roots`" -TimeoutMs $TimeoutMs -MaxRoots $MaxRoots$userAgentArg -IncludeResolvedRoots -RequireApiJsonRoot -ForceStopBeforeRun -SkipBuild -SkipInstall"

$summary = [ordered]@{
    runDir = $runDir
    exitCode = $exitCode
    roots = $Roots
    timeoutMs = $TimeoutMs
    maxRoots = $MaxRoots
    requireApiJsonRoot = [bool]$RequireApiJsonRoot
    verdict = $rootProbeVerdict
    ackBlockedReason = $ackBlockedReason
    rootClassCounts = $rootClassCounts
    transportOutcomeCounts = $transportOutcomeCounts
    apiJsonRoots = $apiJsonRoots
    nextLiveRandomCommand = $nextLiveRandomCommand
    nextRootProbeCommand = $nextRootProbeCommand
    rootClasses = $rootClasses
    transportOutcomes = $transportOutcomes
    probes = Marker-Lines $logText "ntk_root_probe root="
    started = Marker-Lines $logText "ntk_root_probe_start" 4
    done = Marker-Lines $logText "ntk_root_probe_done" 4
    failure = Marker-Lines ($instrumentText + "`n" + $logText) "AssertionError" 2
}

$summaryPath = Join-Path $runDir "summary.json"
($summary | ConvertTo-Json -Depth 4) | Set-Content -Path $summaryPath -Encoding UTF8
[string]::Join("`n", $apiJsonRoots) | Set-Content -Path (Join-Path $runDir "api_json_roots.txt") -Encoding UTF8
[string]$nextLiveRandomCommand | Set-Content -Path (Join-Path $runDir "next_live_random_command.txt") -Encoding UTF8
[string]$nextRootProbeCommand | Set-Content -Path (Join-Path $runDir "next_root_probe_command.txt") -Encoding UTF8
$summary | Format-List | Out-String | Write-Host
if($rootClasses.Count -gt 0) {
    Write-Host "Root classes"
    $rootClasses | Format-Table -AutoSize | Out-String | Write-Host
}
Write-Host ("Root probe verdict: {0}" -f $rootProbeVerdict)
if($ackBlockedReason.Length -gt 0) {
    Write-Host ("ACK blocked reason: {0}" -f $ackBlockedReason)
}
if($nextLiveRandomCommand.Length -gt 0) {
    Write-Host ("Next live random command: {0}" -f $nextLiveRandomCommand)
}

foreach($packageName in @("ml.melun.mangaview", "ml.melun.mangaview.test")) {
    & adb -s $DeviceSerial shell am force-stop $packageName | Out-Null
}

if($exitCode -ne 0 -or $instrumentText -match "FAILURES!!!|Process crashed|INSTRUMENTATION_STATUS_CODE: -2") {
    throw "NTK root probe failed with exit code $exitCode. Summary: $summaryPath"
}
if($RequireApiJsonRoot -and $apiJsonRoots.Count -eq 0) {
    throw "No api-json-ok NTK root found. Summary: $summaryPath"
}
