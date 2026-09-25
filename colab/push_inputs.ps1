#Requires -Version 5.1
<#
.SYNOPSIS
  Package the latest emulator probe outputs + local git state and push them to the
  colab-inbox branch, so the Colab notebook can build/test/analyze them on A100.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File colab\push_inputs.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File colab\push_inputs.ps1 -Objective "stuck work record 분석" -Questions "왜 안 은퇴하나?" -DryRun
#>
[CmdletBinding()]
param(
    [string]$Objective = "최신 온디바이스 프로브 결과(프레임 cadence, stuck work record, 메모리)를 분석하고 가장 방어 가능한 다음 개선을 제안해라.",
    [string[]]$Questions = @(),
    [string[]]$Focus = @(),
    [string[]]$Exclude = @(),
    [string]$Serial = "emulator-5554",
    [string]$AdbPath = "",
    [switch]$NoDevice,
    [switch]$DryRun
)

$ErrorActionPreference = "Continue"
$repoRoot = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stage = Join-Path ([IO.Path]::GetTempPath()) "mw-colab-inbox-$stamp"
$taskName = "probe-$stamp"
$taskDir = Join-Path $stage "tasks\$taskName"
New-Item -ItemType Directory -Force -Path $taskDir | Out-Null

function Info([string]$m) { Write-Host "[inbox] $m" }

# --- resolve adb ---
if (-not $AdbPath) {
    $candidates = @(
        (Join-Path $env:USERPROFILE "AndroidTools\sdk\platform-tools\adb.exe"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    )
    foreach ($c in $candidates) { if (Test-Path $c) { $AdbPath = $c; break } }
    if (-not $AdbPath) {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $AdbPath = $cmd.Source }
    }
}

# --- 1) latest probe outputs from the emulator ---
if (-not $NoDevice) {
    if ($AdbPath -and (Test-Path $AdbPath)) {
        $remoteRoot = "/sdcard/Android/data/ml.melun.mangaview/files"
        Info "listing probe dirs on $Serial"
        $listing = @(& $AdbPath -s $Serial shell "ls -t $remoteRoot/ 2>/dev/null" 2>$null)
        $probeDirs = @($listing | ForEach-Object { "$_".Trim() } | Where-Object { $_ -match "probe" })
        $probeDir = ""
        foreach ($candidate in $probeDirs) {
            $hasSamples = & $AdbPath -s $Serial shell "ls $remoteRoot/$candidate/samples.txt 2>/dev/null" 2>$null
            if ("$hasSamples".Trim()) { $probeDir = $candidate; break }
        }
        if (-not $probeDir -and $probeDirs.Count -gt 0) { $probeDir = $probeDirs[0] }
        if ($probeDir) {
            Info "probe dir: $probeDir"
            $files = @(& $AdbPath -s $Serial shell "ls $remoteRoot/$probeDir/ 2>/dev/null" 2>$null)
            foreach ($entry in $files) {
                $name = "$entry".Trim()
                if (-not $name -or $name -match "/") { continue }
                $sizeRaw = & $AdbPath -s $Serial shell "stat -c %s $remoteRoot/$probeDir/$name 2>/dev/null" 2>$null
                $size = 0
                try { $size = [int64]("$sizeRaw" -replace "[^0-9]", "") } catch { $size = 0 }
                if ($name -match "\.jsonl$" -and $size -gt 8MB) {
                    Info "skip large $name ($([math]::Round($size/1MB,1)) MB)"
                    continue
                }
                & $AdbPath -s $Serial pull "$remoteRoot/$probeDir/$name" (Join-Path $taskDir $name) 2>&1 | Out-Null
            }
            Set-Content -Path (Join-Path $taskDir "probe-dir.txt") -Value $probeDir -Encoding UTF8
        } else {
            Info "no probe dir found on device"
        }
    } else {
        Info "adb not found - skipping device pull (pass -NoDevice to silence)"
    }
}

# --- 2) local git state ---
Info "collecting git state"
& git -C $repoRoot log --oneline -8 | Set-Content (Join-Path $taskDir "git-log.txt") -Encoding UTF8
& git -C $repoRoot status --porcelain | Set-Content (Join-Path $taskDir "git-status.txt") -Encoding UTF8
$diff = @(& git -C $repoRoot diff) + @(& git -C $repoRoot diff --cached)
Set-Content -Path (Join-Path $taskDir "worktree.diff") -Value $diff -Encoding UTF8
$head = (& git -C $repoRoot rev-parse HEAD | Out-String).Trim()

# --- 3) focus files (changed files by default) ---
$changed = @(
    & git -C $repoRoot status --porcelain |
        ForEach-Object { ("$_" -replace '^..\s+', '').Trim().Trim('"') } |
        Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $repoRoot $_)) }
)
$focusFiles = @(@($Focus) + @($changed)) | Select-Object -Unique
if ($Exclude.Count -gt 0) {
    $focusFiles = @($focusFiles | Where-Object {
        $rel = $_
        -not ($Exclude | Where-Object { $rel -eq $_ -or $rel -like $_ })
    })
}

# --- 4) copy focus file contents (the Colab clone only has committed state) ---
$filesDir = Join-Path $taskDir "files"
$copied = 0
foreach ($rel in $focusFiles) {
    if ($copied -ge 40) { break }
    $src = Join-Path $repoRoot $rel
    if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { continue }
    if ((Get-Item -LiteralPath $src).Length -gt 512KB) { continue }
    $dst = Join-Path $filesDir $rel
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
    Copy-Item -LiteralPath $src -Destination $dst -Force
    $copied++
}
Info "focus files copied: $copied / $($focusFiles.Count)"

# --- 5) task.json + manifest.json ---
$task = [ordered]@{
    id          = $taskName
    objective   = $Objective
    questions   = @($Questions)
    focus_files = @($focusFiles)
    created_at  = (Get-Date).ToUniversalTime().ToString("o")
    head_sha    = $head
}
$taskJson = $task | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText((Join-Path $taskDir "task.json"), $taskJson, (New-Object Text.UTF8Encoding($false)))

$manifest = [ordered]@{
    stamp     = $stamp
    head_sha  = $head
    probe_dir = "$(Get-Content (Join-Path $taskDir "probe-dir.txt") -ErrorAction SilentlyContinue)"
    files     = @(Get-ChildItem $taskDir -Recurse -File | ForEach-Object { $_.FullName.Substring($taskDir.Length + 1) })
}
$manifestJson = $manifest | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText((Join-Path $stage "manifest.json"), $manifestJson, (New-Object Text.UTF8Encoding($false)))

# --- 6) zip ---
$zipPath = Join-Path ([IO.Path]::GetTempPath()) "mw-inbox-$stamp.zip"
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zipPath -Force
Info "inbox zip: $zipPath ($([math]::Round((Get-Item $zipPath).Length/1KB,1)) KB)"

# --- 7) push (or keep local on -DryRun) ---
if ($DryRun) {
    $keep = Join-Path $PSScriptRoot "staging"
    New-Item -ItemType Directory -Force -Path $keep | Out-Null
    Copy-Item $zipPath (Join-Path $keep "inbox-$stamp.zip") -Force
    Copy-Item $stage (Join-Path $keep "stage-$stamp") -Recurse -Force
    Info "DryRun: kept under $keep (not pushed)"
    exit 0
}

$inboxBranch = "colab-inbox"
$bus = Join-Path $PSScriptRoot "bus"
$repoUrl = (& git -C $repoRoot remote get-url origin | Out-String).Trim()

if (-not (Test-Path (Join-Path $bus ".git"))) {
    Info "cloning bus repo..."
    & git clone --depth 1 --quiet $repoUrl $bus
    if ($LASTEXITCODE -ne 0) { Write-Host "[inbox] bus clone failed"; exit 1 }
}

& git -C $bus fetch --depth 1 --quiet origin $inboxBranch 2>$null
if ($LASTEXITCODE -eq 0) {
    & git -C $bus checkout -q -B $inboxBranch FETCH_HEAD
} else {
    & git -C $bus fetch --depth 1 --quiet origin main
    if ($LASTEXITCODE -ne 0) { Write-Host "[inbox] cannot reach origin"; exit 1 }
    & git -C $bus checkout -q -B $inboxBranch FETCH_HEAD
}

Copy-Item $zipPath (Join-Path $bus "inbox.zip") -Force
Copy-Item (Join-Path $stage "manifest.json") (Join-Path $bus "manifest.json") -Force
# -f is required: the bus clone inherits the repo .gitignore (*.zip, /*.json match these files)
& git -C $bus add -f inbox.zip manifest.json
& git -C $bus -c user.email="colab-bot@users.noreply.github.com" -c user.name="colab-bot" commit -q -m "colab inbox $stamp"
if ($LASTEXITCODE -ne 0) { Write-Host "[inbox] commit produced no change - aborting"; exit 1 }
& git -C $bus push --quiet origin $inboxBranch
if ($LASTEXITCODE -eq 0) {
    Info "pushed to $inboxBranch (head $head)"
    Info "next: run colab/mangaviewer_colab.ipynb on Colab (A100), then colab\pull_results.ps1"
} else {
    Write-Host "[inbox] push failed"
    exit 1
}

Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
