param(
    [string]$PackageName = "ml.melun.mangaview",
    [string]$ActivityName = ".activity.MainActivity",
    [string]$ApkPath = "app\build\outputs\apk\debug\mangaViewer_2112260512-debug.apk",
    [int]$SampleSeconds = 1
)

$ErrorActionPreference = "Stop"

function Require-Command($Name) {
    if(-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Read-GfxMetric($Text, $Name) {
    $match = [regex]::Match($Text, "$([regex]::Escape($Name))\s*:?\s+([0-9]+)")
    if($match.Success) {
        return [int]$match.Groups[1].Value
    }
    return $null
}

Require-Command adb

if(Test-Path $ApkPath) {
    adb install -r $ApkPath | Out-Host
}

adb shell am force-stop $PackageName | Out-Null
adb shell cmd package compile -m speed -f $PackageName | Out-Host
adb shell dumpsys gfxinfo $PackageName reset | Out-Null

$component = "$PackageName/$ActivityName"
adb shell am start -W -n $component | Out-Host
Start-Sleep -Seconds $SampleSeconds

$gfx = adb shell dumpsys gfxinfo $PackageName
$summary = $gfx | Select-String -Pattern "Total frames rendered|Janky frames:|Number Slow UI thread|Number Slow bitmap uploads|Number Frame deadline missed|Total attached Views|50th percentile|90th percentile"
$summary | Out-Host

$text = $gfx -join "`n"
$frames = Read-GfxMetric $text "Total frames rendered"
$janky = Read-GfxMetric $text "Janky frames"

if($null -eq $frames -or $null -eq $janky) {
    Write-Error "Could not read gfxinfo frame metrics."
    exit 2
}

if($janky -ne 0) {
    Write-Error "Perf audit failed: expected 0 janky frames, got $janky / $frames."
    exit 1
}

Write-Host "Perf audit passed: 0 janky frames / $frames frames."
