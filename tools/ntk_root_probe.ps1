param(
    [string]$DeviceSerial = "emulator-5556",
    [string]$OutDir = "build\ntk-root-probe",
    [string]$Roots = "https://sbxh6.com,https://toonflix.app,https://sbxh4.com",
    [int]$TimeoutMs = 3500,
    [int]$MaxRoots = 16,
    [switch]$IncludeResolvedRoots,
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
        } elseif($samples.Contains("뉴토끼 공식 주소안내") -or $samples.Contains("telegram")) {
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
$argsList = @(
    "-s", $DeviceSerial,
    "shell", "am", "instrument", "-w", "-r",
    "-e", "runLiveNetworkTests", "true",
    "-e", "ntkRoots", $Roots,
    "-e", "ntkRootProbeTimeoutMs", [string]$TimeoutMs,
    "-e", "ntkRootProbeMaxRoots", [string]$MaxRoots,
    "-e", "ntkIncludeResolvedRoots", $includeResolvedArg,
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

$summary = [ordered]@{
    runDir = $runDir
    exitCode = $exitCode
    roots = $Roots
    timeoutMs = $TimeoutMs
    maxRoots = $MaxRoots
    rootClasses = $rootClasses
    probes = Marker-Lines $logText "ntk_root_probe root="
    started = Marker-Lines $logText "ntk_root_probe_start" 4
    done = Marker-Lines $logText "ntk_root_probe_done" 4
    failure = Marker-Lines ($instrumentText + "`n" + $logText) "AssertionError" 2
}

$summaryPath = Join-Path $runDir "summary.json"
($summary | ConvertTo-Json -Depth 4) | Set-Content -Path $summaryPath -Encoding UTF8
$summary | Format-List | Out-String | Write-Host
if($rootClasses.Count -gt 0) {
    Write-Host "Root classes"
    $rootClasses | Format-Table -AutoSize | Out-String | Write-Host
}

foreach($packageName in @("ml.melun.mangaview", "ml.melun.mangaview.test")) {
    & adb -s $DeviceSerial shell am force-stop $packageName | Out-Null
}

if($exitCode -ne 0 -or $instrumentText -match "FAILURES!!!|Process crashed|INSTRUMENTATION_STATUS_CODE: -2") {
    throw "NTK root probe failed with exit code $exitCode. Summary: $summaryPath"
}
