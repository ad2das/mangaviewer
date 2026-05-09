param(
    [string]$Repo = "ad2das/mangaviewer",
    [string]$Workflow = "build-apk-artifact.yml",
    [string]$ArtifactName = "mangaviewer-debug-apk",
    [string]$ReleaseTag = "codex/perf-stability-max-core-rewrite-v2112260512",
    [string]$BuildRunId = "",
    [string]$ArtifactDir = "",
    [string]$CommitMessage = "",
    [switch]$NoCommit,
    [switch]$NoPush,
    [switch]$NoUpload
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

function Invoke-Checked([scriptblock]$command, [string]$errorMessage) {
    & $command
    if ($LASTEXITCODE -ne 0) {
        throw $errorMessage
    }
}

function Read-Utf8($path) {
    return [System.IO.File]::ReadAllText((Resolve-Path $path), [System.Text.Encoding]::UTF8)
}

function Write-Utf8NoBom($path, $content) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Resolve-Path $path), $content, $utf8)
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

if ([string]::IsNullOrWhiteSpace($ArtifactDir)) {
    if ([string]::IsNullOrWhiteSpace($BuildRunId)) {
        Write-Step "Finding latest successful APK artifact build"
        $BuildRunId = gh run list `
            --repo $Repo `
            --workflow $Workflow `
            --branch main `
            --status success `
            --limit 1 `
            --json databaseId `
            --jq ".[0].databaseId"
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($BuildRunId)) {
            throw "Could not find a successful $Workflow run with an APK artifact"
        }
    }

    $tempRoot = if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
        Join-Path ([System.IO.Path]::GetTempPath()) "mangaviewer-release-artifact-$([System.Guid]::NewGuid().ToString('N'))"
    } else {
        Join-Path $env:RUNNER_TEMP "mangaviewer-release-artifact"
    }
    if (Test-Path $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $tempRoot | Out-Null

    Write-Step "Downloading APK artifact from run $BuildRunId"
    Invoke-Checked {
        gh run download $BuildRunId --repo $Repo --name $ArtifactName --dir $tempRoot
    } "Failed to download artifact $ArtifactName from run $BuildRunId"
    $ArtifactDir = $tempRoot
} else {
    $ArtifactDir = (Resolve-Path $ArtifactDir).Path
}

$metadataPath = Join-Path $ArtifactDir "release-metadata.json"
if (-not (Test-Path $metadataPath)) {
    throw "Missing artifact metadata: $metadataPath"
}

$metadata = Read-Utf8 $metadataPath | ConvertFrom-Json
$versionCode = [long]$metadata.versionCode
$apkName = [string]$metadata.apkName
if ($versionCode -le 0 -or [string]::IsNullOrWhiteSpace($apkName)) {
    throw "Invalid release metadata in $metadataPath"
}

$apkPath = Join-Path $ArtifactDir $apkName
if (-not (Test-Path $apkPath)) {
    throw "Missing APK artifact: $apkPath"
}

$downloadUrl = "https://github.com/$Repo/releases/download/$ReleaseTag/$apkName"

Write-Step "Promoting APK $apkName"
Write-Host "versionCode=$versionCode"
Write-Host "artifact=$apkPath"
Write-Host "url=$downloadUrl"

if (-not $NoUpload) {
    Write-Step "Uploading APK release asset"
    Invoke-Checked {
        gh release upload $ReleaseTag $apkPath --clobber --repo $Repo
    } "gh release upload failed"

    Write-Step "Deleting old APK release assets"
    $assetsJson = gh release view $ReleaseTag --repo $Repo --json assets
    if ($LASTEXITCODE -ne 0) {
        throw "gh release view failed"
    }
    $releaseInfo = $assetsJson | ConvertFrom-Json
    foreach ($asset in $releaseInfo.assets) {
        $assetName = [string]$asset.name
        if ($assetName -match '^mangaViewer_\d+-debug\.apk$' -and $assetName -ne $apkName) {
            Invoke-Checked {
                gh release delete-asset $ReleaseTag $assetName --repo $Repo -y
            } "Failed to delete old release asset $assetName"
        }
    }
}

$versionJsonPath = "version.json"
$releasesHtmlPath = "releases.html"

$versionJson = "{""version"":$versionCode,""link"":""$downloadUrl""}"
Write-Utf8NoBom $versionJsonPath $versionJson

$releasesHtml = Read-Utf8 $releasesHtmlPath
$releasesHtml = [regex]::Replace($releasesHtml, 'tag_name:\s*"\d+"', "tag_name: `"$versionCode`"", 1)
$releasesHtml = [regex]::Replace($releasesHtml, 'browser_download_url:\s*"[^"]*mangaViewer_\d+-debug\.apk"', "browser_download_url: `"$downloadUrl`"", 1)
Write-Utf8NoBom $releasesHtmlPath $releasesHtml

if (-not $NoCommit) {
    Write-Step "Committing release metadata"
    git config user.name "github-actions[bot]"
    git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
    git add -- $versionJsonPath $releasesHtmlPath
    if ($LASTEXITCODE -ne 0) {
        throw "git add failed"
    }

    git diff --cached --quiet
    $hasNoChanges = $LASTEXITCODE -eq 0
    if ($hasNoChanges) {
        Write-Host "No release metadata changes."
    } else {
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
            git push origin HEAD:main
            if ($LASTEXITCODE -ne 0) {
                throw "git push failed"
            }
        }
    }
}

Write-Step "Done"
