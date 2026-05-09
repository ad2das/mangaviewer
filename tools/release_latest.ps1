param(
    [string]$Repo = "ad2das/mangaviewer",
    [string]$ReleaseTag = "",
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
$patchMatch = [regex]::Match($buildGradle, "def\s+defaultReleasePatch\s*=\s*(\d+)")
if (-not $patchMatch.Success) {
    throw "Could not find defaultReleasePatch in $buildGradlePath"
}

$currentPatch = [int]$patchMatch.Groups[1].Value
$nextPatch = if ($ReleasePatch -ge 0) { $ReleasePatch } else { $currentPatch + 1 }
$dateCodeText = Get-Date -Format "yyMMdd"
$dateCode = [int]$dateCodeText
$versionCode = 2112000000 + $dateCode + $nextPatch
if ([string]::IsNullOrWhiteSpace($ReleaseTag)) {
    $ReleaseTag = "main-v$versionCode"
}
$apkName = "mangaViewer_${versionCode}-release.apk"
$downloadUrl = "https://github.com/$Repo/releases/download/$ReleaseTag/$apkName"

Write-Step "Preparing version $versionCode"
Write-Host "releasePatch: $currentPatch -> $nextPatch"
Write-Host "apk: $apkName"

$versionJson = @{
    version = $versionCode
    link = $downloadUrl
} | ConvertTo-Json -Compress
Write-Utf8NoBom $versionJsonPath $versionJson

$releasesHtml = Read-Utf8 $releasesHtmlPath
$releasesHtml = [regex]::Replace($releasesHtml, 'tag_name:\s*"\d+"', "tag_name: `"$versionCode`"", 1)
$releasesHtml = [regex]::Replace($releasesHtml, 'browser_download_url:\s*"[^"]*mangaViewer_\d+-(debug|release)\.apk"', "browser_download_url: `"$downloadUrl`"", 1)
Write-Utf8NoBom $releasesHtmlPath $releasesHtml

Write-Step "Building release APK"
Invoke-Gradle -GradleArgs @("--build-cache", "--parallel", "-PreleasePatch=$nextPatch", "-PreleaseDateCode=$dateCodeText", "assembleRelease")

if (-not $SkipTests) {
    Write-Step "Running unit tests"
    Invoke-Gradle -GradleArgs @("--build-cache", "--parallel", "-PreleasePatch=$nextPatch", "-PreleaseDateCode=$dateCodeText", "testReleaseUnitTest")
}

$builtApk = "app/build/outputs/apk/release/$apkName"
if (-not (Test-Path $builtApk)) {
    throw "Built APK not found: $builtApk"
}

$buildGradle = [regex]::Replace($buildGradle, "def\s+defaultReleasePatch\s*=\s*\d+", "def defaultReleasePatch = $nextPatch", 1)
Write-Utf8NoBom $buildGradlePath $buildGradle

$changedFiles = @(
    $buildGradlePath,
    $versionJsonPath,
    $releasesHtmlPath
)

if (-not $NoCommit) {
    Write-Step "Committing release metadata"
    git add -- $changedFiles
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
    gh release view $ReleaseTag --repo $Repo *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Step "Creating release $ReleaseTag"
        gh release create $ReleaseTag --repo $Repo --target main --title "Main $versionCode" --notes "Main branch signed release APK." --latest
        if ($LASTEXITCODE -ne 0) {
            throw "gh release create failed"
        }
    }

    Write-Step "Uploading APK release asset"
    gh release upload $ReleaseTag $builtApk --clobber --repo $Repo
    if ($LASTEXITCODE -ne 0) {
        throw "gh release upload failed"
    }

    if ($DeleteOldReleaseApks) {
        Write-Step "Deleting old APK release assets"
        $releaseTags = gh api "repos/$Repo/releases" --paginate --jq ".[].tag_name"
        foreach ($tag in $releaseTags) {
            $assetNames = gh release view $tag --repo $Repo --json assets --jq ".assets[].name"
            foreach ($asset in $assetNames) {
                $isCurrentAsset = $tag -eq $ReleaseTag -and $asset -eq $apkName
                if (-not $isCurrentAsset -and $asset -match '^mangaViewer_\d+-(debug|release)\.apk$') {
                    gh release delete-asset $tag $asset --repo $Repo --yes
                    if ($LASTEXITCODE -ne 0) {
                        throw "Failed to delete old release asset: $tag/$asset"
                    }
                }
            }
        }
    }

    Write-Step "Verifying release asset"
    gh release view $ReleaseTag --repo $Repo --json assets --jq ".assets[] | select(.name==`"$apkName`") | {name: .name, size: .size, updatedAt: .updatedAt, url: .url}"
}

Write-Step "Done"
Write-Host "versionCode=$versionCode"
Write-Host "apk=$builtApk"
Write-Host "url=$downloadUrl"
