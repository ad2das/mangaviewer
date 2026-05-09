param(
    [string]$Repo = "ad2das/mangaviewer",
    [string]$ReleaseTag = "codex/perf-stability-max-core-rewrite-v2112260512",
    [string]$JavaHome = "",
    [string]$CommitMessage = "",
    [int]$ReleasePatch = -1,
    [switch]$SkipTests,
    [switch]$NoCommit,
    [switch]$NoPush,
    [switch]$NoUpload,
    [switch]$DeleteOldReleaseApks
)

$ErrorActionPreference = "Stop"

function Write-Step($message) {
    Write-Host ""
    Write-Host "==> $message" -ForegroundColor Cyan
}

function Require-Command($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $name"
    }
}

function Read-Utf8($path) {
    return [System.IO.File]::ReadAllText((Resolve-Path $path), [System.Text.Encoding]::UTF8)
}

function Write-Utf8NoBom($path, $content) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Resolve-Path $path), $content, $utf8)
}

Set-Location (Resolve-Path (Join-Path $PSScriptRoot ".."))

Require-Command git
Require-Command gh

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $windowsJavaHome = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
    if (Test-Path $windowsJavaHome) {
        $JavaHome = $windowsJavaHome
    } elseif ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw "JAVA_HOME is not set. Pass -JavaHome or configure JAVA_HOME."
    }
}

if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    if (-not (Test-Path $JavaHome)) {
        throw "JAVA_HOME path does not exist: $JavaHome"
    }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$env:JAVA_HOME/bin;$env:JAVA_HOME\bin;$env:PATH"
}

$buildGradlePath = "app/build.gradle"
$versionJsonPath = "version.json"
$releasesHtmlPath = "releases.html"

$buildGradle = Read-Utf8 $buildGradlePath
$patchMatch = [regex]::Match($buildGradle, "def\s+releasePatch\s*=\s*(\d+)")
if (-not $patchMatch.Success) {
    throw "Could not find releasePatch in $buildGradlePath"
}

$currentPatch = [int]$patchMatch.Groups[1].Value
$nextPatch = if ($ReleasePatch -ge 0) { $ReleasePatch } else { $currentPatch + 1 }
$dateCode = [int](Get-Date -Format "yyMMdd")
$versionCode = 2112000000 + $dateCode + $nextPatch
$apkName = "mangaViewer_${versionCode}-debug.apk"
$apkPath = "apk/$apkName"
$downloadUrl = "https://github.com/$Repo/releases/download/$ReleaseTag/$apkName"

Write-Step "Preparing version $versionCode"
Write-Host "releasePatch: $currentPatch -> $nextPatch"
Write-Host "apk: $apkName"

$buildGradle = [regex]::Replace($buildGradle, "def\s+releasePatch\s*=\s*\d+", "def releasePatch = $nextPatch", 1)
Write-Utf8NoBom $buildGradlePath $buildGradle

$versionJson = @{
    version = $versionCode
    link = $downloadUrl
} | ConvertTo-Json -Compress
Write-Utf8NoBom $versionJsonPath $versionJson

$releasesHtml = Read-Utf8 $releasesHtmlPath
$releasesHtml = [regex]::Replace($releasesHtml, 'tag_name:\s*"\d+"', "tag_name: `"$versionCode`"", 1)
$releasesHtml = [regex]::Replace($releasesHtml, 'browser_download_url:\s*"[^"]*mangaViewer_\d+-debug\.apk"', "browser_download_url: `"$downloadUrl`"", 1)
Write-Utf8NoBom $releasesHtmlPath $releasesHtml

Write-Step "Building debug APK"
& .\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) {
    throw "assembleDebug failed"
}

if (-not $SkipTests) {
    Write-Step "Running unit tests"
    & .\gradlew.bat testDebugUnitTest
    if ($LASTEXITCODE -ne 0) {
        throw "testDebugUnitTest failed"
    }
}

$builtApk = "app/build/outputs/apk/debug/$apkName"
if (-not (Test-Path $builtApk)) {
    throw "Built APK not found: $builtApk"
}

Write-Step "Copying APK to apk/"
New-Item -ItemType Directory -Force -Path "apk" | Out-Null
Copy-Item -LiteralPath $builtApk -Destination $apkPath -Force

$changedFiles = @(
    $buildGradlePath,
    $versionJsonPath,
    $releasesHtmlPath,
    $apkPath
)

if (-not $NoCommit) {
    Write-Step "Committing release metadata and APK"
    git add -- $changedFiles
    if ([string]::IsNullOrWhiteSpace($CommitMessage)) {
        $CommitMessage = "Release debug APK $versionCode"
    }
    git commit -m $CommitMessage
    if ($LASTEXITCODE -ne 0) {
        throw "git commit failed"
    }

    if (-not $NoPush) {
        Write-Step "Pushing main"
        git push origin main
        if ($LASTEXITCODE -ne 0) {
            throw "git push failed"
        }
    }
}

if (-not $NoUpload) {
    Write-Step "Uploading APK release asset"
    gh release upload $ReleaseTag $apkPath --clobber --repo $Repo
    if ($LASTEXITCODE -ne 0) {
        throw "gh release upload failed"
    }

    if ($DeleteOldReleaseApks) {
        Write-Step "Deleting old APK release assets"
        $assetNames = gh release view $ReleaseTag --repo $Repo --json assets --jq ".assets[].name"
        foreach ($asset in $assetNames) {
            if ($asset -match '^mangaViewer_\d+-debug\.apk$' -and $asset -ne $apkName) {
                gh release delete-asset $ReleaseTag $asset --repo $Repo --yes
                if ($LASTEXITCODE -ne 0) {
                    throw "Failed to delete old release asset: $asset"
                }
            }
        }
    }

    Write-Step "Verifying release asset"
    gh release view $ReleaseTag --repo $Repo --json assets --jq ".assets[] | select(.name==`"$apkName`") | {name: .name, size: .size, updatedAt: .updatedAt, url: .url}"
}

Write-Step "Done"
Write-Host "versionCode=$versionCode"
Write-Host "apk=$apkPath"
Write-Host "url=$downloadUrl"
