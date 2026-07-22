#requires -Version 7.2

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [object[]]$LegacyArguments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$canonical = Join-Path $PSScriptRoot "ntk_final_qualification.ps1"
throw @"
tools/ntk_physical_qualification.ps1 is retired. Its emulator-only, fixed-content, all-pages-
before-input protocol is not valid cold qualification evidence.

Use the canonical physical-device runner instead:
  pwsh -NoProfile -File "$canonical" -AppApkPath <app-benchmark.apk> `
    -BenchmarkApkPath <macrobenchmark-benchmark.apk> -DeviceSerial <physical-serial>
"@
