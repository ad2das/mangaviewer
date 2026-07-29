#requires -Version 7.2

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$AppApkPath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$BenchmarkApkPath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DeviceSerial,

    [string]$NtkSiteRoot = "https://sbxh9.com",
    [string]$OutDir = "build\outputs\ntk-cold",
    [long]$Seed = 0,
    [ValidateRange(60, 1800)]
    [int]$CaseTimeoutSeconds = 360,
    [switch]$SkipInstall,
    [switch]$RequireBaselineProfile,
    [switch]$StandalonePerfetto
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if($Seed -ne 0L) {
    throw "Host-GPU emulator qualification requires Seed=0 so every run creates a fresh random selection; use ntk_cold_qualification.ps1 directly for diagnostic reproduction"
}

$runner = Join-Path $PSScriptRoot "ntk_cold_qualification.ps1"
if(-not (Test-Path -LiteralPath $runner -PathType Leaf)) {
    throw "NTK cold qualification runner is missing: $runner"
}

# Every acceptance-policy input is fixed here. The lower-level runner also proves that the AVD is
# using Android Emulator's host OpenGL translator and rejects software-rendered emulators.
$arguments = @{
    AppApkPath = $AppApkPath
    BenchmarkApkPath = $BenchmarkApkPath
    DeviceSerial = $DeviceSerial
    NtkSiteRoot = $NtkSiteRoot
    OutDir = $OutDir
    Seed = $Seed
    CountPerType = 10
    FirstImageSlaMs = 4000
    ManhwaImageSlaMs = 4000
    # The user-facing entry deadline remains four seconds. Complete-work readiness is still
    # mandatory, but large cold sources are allowed their measured physical transfer time.
    AllImagesSlaMs = 30000
    CaseTimeoutSeconds = $CaseTimeoutSeconds
    QualificationDeviceMode = "HOST_GPU_EMULATOR"
    # AndroidX terminates the measured target process after measureRepeated returns. A reopen
    # outside that lifecycle is neither same-process nor a valid cold metric and used to add a
    # 180-second timeout per case. Warm UX is exercised separately; the formal verdict is cold.
    IncludeWarmReopen = $false
    RestartHostGpuProcessPerCase = $true
}
if($SkipInstall) { $arguments.SkipInstall = $true }
if($RequireBaselineProfile) { $arguments.RequireBaselineProfile = $true }
if($StandalonePerfetto) { $arguments.StandalonePerfetto = $true }

Write-Host "NTK host-GPU emulator qualification: fresh random 10+10, cold, first image=4000ms, all images=30000ms"
& $runner @arguments
if(-not $?) {
    throw "NTK host-GPU emulator qualification failed"
}
