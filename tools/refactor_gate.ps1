param(
    [switch]$UnitOnly,
    [switch]$SkipBuild,
    [switch]$Connected
)

$ErrorActionPreference = "Stop"

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Command
    )

    Write-Host "==> $Name"
    & $Command
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

Invoke-Step "Unit tests" {
    .\gradlew :app:testDebugUnitTest
}

if (-not $UnitOnly -and -not $SkipBuild) {
    Invoke-Step "Debug APK build" {
        .\gradlew :app:assembleDebug
    }
}

if ($Connected) {
    Invoke-Step "Connected Android tests" {
        .\gradlew :app:connectedDebugAndroidTest
    }
}

Write-Host "Refactor gate completed."
