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
    [ValidateSet("PHYSICAL_DEVICE", "HOST_GPU_EMULATOR")]
    [string]$QualificationDeviceMode = "HOST_GPU_EMULATOR",
    [switch]$SkipInstall,
    [switch]$RequireBaselineProfile,
    [switch]$StandalonePerfetto
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if($Seed -ne 0L) {
    throw "Canonical final qualification requires Seed=0 so every run creates a fresh random selection; use ntk_cold_qualification.ps1 directly for diagnostic reproduction"
}

$runner = Join-Path $PSScriptRoot "ntk_cold_qualification.ps1"
if(-not (Test-Path -LiteralPath $runner -PathType Leaf)) {
    throw "Canonical NTK cold qualification runner is missing: $runner"
}

# Formal qualification deliberately fixes every acceptance-policy input. Seed zero is resolved
# by the runner to a fresh cryptographically random seed after the process starts.
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
    CaseTimeoutSeconds = $CaseTimeoutSeconds
    QualificationDeviceMode = $QualificationDeviceMode
    # The formal verdict is cold-only. A warm reopen remains available in the diagnostic runner,
    # but cannot delay or invalidate the requested 20 independent pm-clear cases.
    IncludeWarmReopen = $false
}
if($QualificationDeviceMode -ceq "HOST_GPU_EMULATOR") {
    # Long-lived gfxstream/host OpenGL processes developed reproducible 56-95ms queueBuffer
    # stalls even while app draw work stayed below 1ms. A no-snapshot QEMU restart per case is a
    # stronger cold boundary, not content warm-up: both packages are then verified and pm-cleared
    # before the measured click by the canonical runner.
    $arguments.RestartHostGpuProcessPerCase = $true
}
if($SkipInstall) { $arguments.SkipInstall = $true }
if($RequireBaselineProfile) { $arguments.RequireBaselineProfile = $true }
if($StandalonePerfetto) { $arguments.StandalonePerfetto = $true }

Write-Host "NTK canonical final qualification: random 10+10, cold, all images webtoon=4000ms/manhwa=4000ms, deviceMode=$QualificationDeviceMode"
& $runner @arguments
if(-not $?) {
    throw "Canonical NTK cold qualification failed"
}
