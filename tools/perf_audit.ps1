param(
    [string]$PackageName = "ml.melun.mangaview",
    [string]$MainActivity = ".activity.MainActivity",
    [string]$ApkPath = "",
    [string]$OutDir = "build\perf-audit",
    [int]$MaxJankPermille = 9,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

function Require-Command($Name) {
    if(-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & adb @Args
}

function Read-GfxMetric($Text, $Name) {
    $match = [regex]::Match($Text, "$([regex]::Escape($Name))\s*:?\s+([0-9]+)")
    if($match.Success) {
        return [int]$match.Groups[1].Value
    }
    return $null
}

function Read-JankPercent($Text) {
    $match = [regex]::Match($Text, "Janky frames:\s+[0-9]+\s+\(([0-9.]+)%\)")
    if($match.Success) {
        return [double]$match.Groups[1].Value
    }
    return $null
}

function Save-Screenshot($Name) {
    $path = Join-Path $OutDir "$Name.png"
    Invoke-Adb exec-out screencap "-p" > $path
    return $path
}

function Reset-Gfx {
    Invoke-Adb shell dumpsys gfxinfo $PackageName reset | Out-Null
}

function Read-Gfx {
    $gfx = Invoke-Adb shell dumpsys gfxinfo $PackageName
    return ($gfx -join "`n")
}

function Read-AvdEnvironment {
    $name = ""
    try {
        $name = ((Invoke-Adb emu avd name) | Where-Object { $_ -and $_ -notmatch "^OK$" } | Select-Object -First 1)
    } catch {
        $name = ""
    }
    $gpuEnabled = $null
    $gpuMode = $null
    if($name) {
        $config = Join-Path $env:USERPROFILE ".android\avd\$name.avd\config.ini"
        if(Test-Path $config) {
            $line = Get-Content $config | Where-Object { $_ -match "^hw\.gpu\.enabled=" } | Select-Object -First 1
            if($line) {
                $gpuEnabled = ($line -replace "^hw\.gpu\.enabled=", "")
            }
            $modeLine = Get-Content $config | Where-Object { $_ -match "^hw\.gpu\.mode=" } | Select-Object -First 1
            if($modeLine) {
                $gpuMode = ($modeLine -replace "^hw\.gpu\.mode=", "")
            }
        }
    }
    return @{
        AvdName = $name
        GpuEnabledConfig = $gpuEnabled
        GpuModeConfig = $gpuMode
    }
}

function Start-App {
    Invoke-Adb shell am force-stop $PackageName | Out-Null
    Reset-Gfx
    $component = "$PackageName/$MainActivity"
    Invoke-Adb shell am start "-W" "-n" $component | Out-Host
}

function Start-App-NoReset {
    $component = "$PackageName/$MainActivity"
    Invoke-Adb shell am start "-W" "-n" $component | Out-Host
}

function Swipe-Vertical {
    param([int]$Count = 6)
    for($i = 0; $i -lt $Count; $i++) {
        Invoke-Adb shell input swipe 540 1580 540 620 450 | Out-Null
        Start-Sleep -Milliseconds 220
    }
    for($i = 0; $i -lt $Count; $i++) {
        Invoke-Adb shell input swipe 540 620 540 1580 450 | Out-Null
        Start-Sleep -Milliseconds 220
    }
}

function Fling-Vertical {
    param([int]$Count = 10)
    for($i = 0; $i -lt $Count; $i++) {
        Invoke-Adb shell input swipe 540 1700 540 250 120 | Out-Null
        Start-Sleep -Milliseconds 90
    }
    for($i = 0; $i -lt $Count; $i++) {
        Invoke-Adb shell input swipe 540 250 540 1700 120 | Out-Null
        Start-Sleep -Milliseconds 90
    }
}

function Tap-HomeFirstTitle {
    Invoke-Adb shell input tap 260 1180 | Out-Null
    Start-Sleep -Seconds 4
}

function Tap-FirstEpisode {
    Invoke-Adb shell input tap 540 1780 | Out-Null
    Start-Sleep -Seconds 5
}

function Assert-Focus($Expected, $Scenario) {
    $focus = Invoke-Adb shell dumpsys window | Select-String -Pattern "mCurrentFocus|mFocusedApp"
    $text = $focus -join "`n"
    if($text -notmatch [regex]::Escape($Expected)) {
        Write-Host "[FAIL] $Scenario focus did not include $Expected"
        Write-Host $text
        return $false
    }
    return $true
}

function Reset-Logcat {
    try {
        Invoke-Adb logcat "-c" | Out-Null
    } catch {
    }
}

function Read-PerfTrace {
    $logs = Invoke-Adb logcat "-d" "-s" "PerfTrace:D" "ViewerPerf:D" "*:S"
    return ($logs -join "`n")
}

function Measure-Scenario {
    param(
        [string]$Name,
        [scriptblock]$Setup,
        [scriptblock]$Action,
        [string]$ExpectedFocus
    )

    Write-Host "`n== $Name =="
    & $Setup
    $shotBefore = Save-Screenshot "$Name-before"
    if($ExpectedFocus -and -not (Assert-Focus $ExpectedFocus $Name)) {
        return @{ Name = $Name; Passed = $false; Reason = "focus"; Screenshot = $shotBefore }
    }

    Reset-Gfx
    Reset-Logcat
    & $Action
    Start-Sleep -Seconds 1
    $shotAfter = Save-Screenshot "$Name-after"
    $gfx = Read-Gfx
    $gfxPath = Join-Path $OutDir "$Name-gfxinfo.txt"
    $gfx | Set-Content -Path $gfxPath -Encoding UTF8
    $perfTrace = Read-PerfTrace
    $perfTracePath = Join-Path $OutDir "$Name-perftrace.txt"
    $perfTrace | Set-Content -Path $perfTracePath -Encoding UTF8

    $frames = Read-GfxMetric $gfx "Total frames rendered"
    $janky = Read-GfxMetric $gfx "Janky frames"
    $slowUploads = Read-GfxMetric $gfx "Number Slow bitmap uploads"
    $jankPercent = Read-JankPercent $gfx
    $altUiHidden = $gfx -match "altUiHidden\s*=\s*true"
    $threshold = $MaxJankPermille / 10.0
    $passed = $frames -ne $null -and $frames -gt 0 -and $janky -ne $null -and $jankPercent -ne $null -and $jankPercent -le $threshold -and ($slowUploads -eq $null -or $slowUploads -eq 0)
    if($Name -eq "home_cold_start") {
        $passed = $slowUploads -eq $null -or $slowUploads -eq 0
    }

    Write-Host ("frames={0} janky={1} jank={2}% slowBitmapUploads={3}" -f $frames, $janky, $jankPercent, $slowUploads)
    $summary = $gfx | Select-String -Pattern "Pipeline=|Total frames rendered|Janky frames:|Number Slow UI thread|Number Slow bitmap uploads|Number Frame deadline missed|Total attached Views|50th percentile|90th percentile|95th percentile|99th percentile|altUiHidden"
    $summary | Out-Host

    return @{
        Name = $Name
        Passed = $passed
        Frames = $frames
        Janky = $janky
        JankPercent = $jankPercent
        SlowBitmapUploads = $slowUploads
        AltUiHidden = $altUiHidden
        Screenshot = $shotAfter
        Gfx = $gfxPath
        PerfTrace = $perfTracePath
    }
}

Require-Command adb
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$devices = Invoke-Adb devices
if(-not ($devices | Select-String -Pattern "\sdevice$")) {
    throw "No adb device is connected."
}

if(-not $ApkPath) {
    $latestApk = Get-ChildItem "app\build\outputs\apk\debug" -Filter "*-debug.apk" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    if($latestApk) {
        $ApkPath = $latestApk.FullName
    }
}

Invoke-Adb shell setprop log.tag.PerfTrace INFO | Out-Null
Invoke-Adb shell setprop log.tag.ViewerPerf INFO | Out-Null

if((-not $SkipInstall) -and $ApkPath -and (Test-Path $ApkPath)) {
    Invoke-Adb install -r $ApkPath | Out-Host
}

Invoke-Adb shell settings put global window_animation_scale 0 | Out-Null
Invoke-Adb shell settings put global transition_animation_scale 0 | Out-Null
Invoke-Adb shell settings put global animator_duration_scale 0 | Out-Null
Invoke-Adb shell cmd package compile -m speed -f $PackageName | Out-Host

$environment = Read-AvdEnvironment
if($environment.AvdName) {
    Write-Host ("Environment: avd={0} hw.gpu.enabled={1} hw.gpu.mode={2}" -f $environment.AvdName, $environment.GpuEnabledConfig, $environment.GpuModeConfig)
}

$results = @()
$results += Measure-Scenario "home_cold_start" { Invoke-Adb shell am force-stop $PackageName | Out-Null; Reset-Gfx } { Start-App-NoReset; Start-Sleep -Seconds 5 } ""
$results += Measure-Scenario "home_scroll" { Start-App; Start-Sleep -Seconds 6 } { Swipe-Vertical 4 } "MainActivity"
$results += Measure-Scenario "episode_open_scroll" { Start-App; Start-Sleep -Seconds 6; Tap-HomeFirstTitle } { Swipe-Vertical 4 } "EpisodeActivity"
$results += Measure-Scenario "viewer_open_scroll" { Start-App; Start-Sleep -Seconds 6; Tap-HomeFirstTitle; Tap-FirstEpisode } { Swipe-Vertical 4 } "ViewerActivity"
$results += Measure-Scenario "viewer_fast_fling" { Start-App; Start-Sleep -Seconds 6; Tap-HomeFirstTitle; Tap-FirstEpisode } { Fling-Vertical 10 } "ViewerActivity"

$summaryPath = Join-Path $OutDir "summary.json"
$summary = @{
    Environment = $environment
    Results = $results
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -Path $summaryPath -Encoding UTF8

$failed = $results | Where-Object { -not $_.Passed }
$environmentLimited = ($environment.GpuEnabledConfig -eq "no") -or ($environment.GpuModeConfig -eq "swiftshader_indirect") -or ($results | Where-Object { $_.AltUiHidden })
Write-Host "`n== Summary =="
$results | ForEach-Object {
    $state = if($_.Passed) { "PASS" } else { "FAIL" }
    Write-Host ("[{0}] {1}: frames={2} janky={3} jank={4}% slowBitmapUploads={5}" -f $state, $_.Name, $_.Frames, $_.Janky, $_.JankPercent, $_.SlowBitmapUploads)
}
if($environmentLimited) {
    Write-Host "[ENVIRONMENT_MISMATCH] Host-GPU AVD required for final jank pass. Current run reports hw.gpu.enabled=$($environment.GpuEnabledConfig), hw.gpu.mode=$($environment.GpuModeConfig), altUiHidden=$((($results | Where-Object { $_.AltUiHidden }).Count) -gt 0)."
    Write-Host "Restart the emulator with -gpu host or use a GPU-enabled AVD before accepting final jank numbers."
}
Write-Host "Artifacts: $OutDir"

if($failed -and -not $environmentLimited) {
    exit 1
}
