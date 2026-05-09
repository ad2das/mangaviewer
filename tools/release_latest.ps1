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
    [switch]$DeleteOldRepoApks,
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

function Invoke-Gradle([string[]]$GradleArgs) {
    $runningOnWindows = $PSVersionTable.PSVersion.Major -lt 6 -or $IsWindows
    if ($runningOnWindows) {
        & .\gradlew.bat @GradleArgs
    } else {
        & chmod +x ./gradlew
        & ./gradlew @GradleArgs
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed: $($GradleArgs -join ' ')"
    }
}

function Add-SkipCiToken([string]$message) {
    if ($message -match '\[(skip ci|ci skip|no ci|skip actions|actions skip)\]') {
        return $message
    }
    return "$message [skip ci]"
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
$downloadUrl = "https://raw.githubusercontent.com/$Repo/main/apk/$apkName"

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
Invoke-Gradle -GradleArgs @("--build-cache", "--parallel", "assembleDebug")

if (-not $SkipTests) {
    Write-Step "Running unit tests"
    Invoke-Gradle -GradleArgs @("--build-cache", "--parallel", "testDebugUnitTest")
}

$builtApk = "app/build/outputs/apk/debug/$apkName"
if (-not (Test-Path $builtApk)) {
    throw "Built APK not found: $builtApk"
}

Write-Step "Copying APK to apk/"
New-Item -ItemType Directory -Force -Path "apk" | Out-Null
Copy-Item -LiteralPath $builtApk -Destination $apkPath -Force

if ($DeleteOldRepoApks) {
    Write-Step "Deleting old repo APKs"
    Get-ChildItem -Path "apk" -Filter "mangaViewer_*-debug.apk" -File | ForEach-Object {
        if ($_.Name -ne $apkName) {
            Remove-Item -LiteralPath $_.FullName -Force
        }
    }
}

$changedFiles = @(
    $buildGradlePath,
    $versionJsonPath,
    $releasesHtmlPath,
    $apkPath
)

if (-not $NoCommit) {
    Write-Step "Committing release metadata and APK"
    if ($DeleteOldRepoApks) {
        git add -- $buildGradlePath $versionJsonPath $releasesHtmlPath apk
    } else {
        git add -- $changedFiles
    }
    if ([string]::IsNullOrWhiteSpace($CommitMessage)) {
        $CommitMessage = "Release debug APK $versionCode"
    }
    $CommitMessage = Add-SkipCiToken $CommitMessage
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
