#Requires -Version 5.1
<#
.SYNOPSIS
  Fetch the colab-outbox branch results into colab/results/<timestamp>/.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File colab\pull_results.ps1
#>
[CmdletBinding()]
param([switch]$ListOnly)

$ErrorActionPreference = "Continue"
$repoRoot = Split-Path -Parent $PSScriptRoot
$bus = Join-Path $PSScriptRoot "bus"
$outRoot = Join-Path $PSScriptRoot "results"
$outboxBranch = "colab-outbox"

if (-not (Test-Path (Join-Path $bus ".git"))) {
    $repoUrl = (& git -C $repoRoot remote get-url origin | Out-String).Trim()
    Write-Host "[results] cloning bus repo (first run)..."
    & git clone --depth 1 --quiet $repoUrl $bus
    if ($LASTEXITCODE -ne 0) { Write-Host "[results] bus clone failed"; exit 1 }
}

& git -C $bus fetch --depth 1 --quiet origin $outboxBranch 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[results] 아직 colab-outbox 브랜치가 없음."
    Write-Host "[results] 코랩 노트북에서 outbox push가 실행됐는지 확인하거나,"
    Write-Host "[results] 브라우저 다운로드/Drive 사본을 사용해라."
    exit 1
}
& git -C $bus checkout -q -B $outboxBranch FETCH_HEAD

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dest = Join-Path $outRoot $stamp
$extract = Join-Path $dest "extracted"
New-Item -ItemType Directory -Force -Path $extract | Out-Null

Copy-Item (Join-Path $bus "results.tar.gz") (Join-Path $dest "results.tar.gz") -Force
if (Test-Path (Join-Path $bus "SUMMARY.md")) {
    Copy-Item (Join-Path $bus "SUMMARY.md") (Join-Path $dest "SUMMARY.md") -Force
}
Write-Host "[results] saved: $dest"

if (-not $ListOnly) {
    $tar = Get-Command tar -ErrorAction SilentlyContinue
    if ($tar) {
        & tar -xzf (Join-Path $dest "results.tar.gz") -C $extract
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[results] extracted:"
            Get-ChildItem $extract -Recurse -File | Select-Object -First 40 | ForEach-Object {
                Write-Host ("  " + $_.FullName.Replace($extract, "").TrimStart("\"))
            }
        } else {
            Write-Host "[results] tar extract failed - results.tar.gz saved at $dest"
        }
    } else {
        Write-Host "[results] tar 없음 - results.tar.gz만 저장됨: $dest"
    }
}

if (Test-Path (Join-Path $dest "SUMMARY.md")) {
    Write-Host ""
    Write-Host "----- SUMMARY.md (head) -----"
    Get-Content (Join-Path $dest "SUMMARY.md") -TotalCount 40 -Encoding UTF8
}
