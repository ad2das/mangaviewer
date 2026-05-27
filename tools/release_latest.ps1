param(
    [string]$Repo = "ad2das/mangaviewer",
    [string]$ReleaseTag = "main-latest",
    [string]$TargetBranch = "",
    [string]$JavaHome = "",
    [string]$CommitMessage = "",
    [string]$PythonExe = "",
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

function Resolve-PythonExe() {
    if (-not [string]::IsNullOrWhiteSpace($PythonExe)) {
        return $PythonExe
    }
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:PYTHON)) {
        $candidates += $env:PYTHON
    }
    $candidates += @("python", "python3")
    $codexPython = "C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
    if (Test-Path $codexPython) {
        $candidates += $codexPython
    }
    foreach ($candidate in $candidates) {
        try {
            & $candidate --version *> $null
            if ($LASTEXITCODE -eq 0) {
                return $candidate
            }
        } catch {
        }
    }
    throw "Python not found. Pass -PythonExe or set PYTHON."
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

if ([string]::IsNullOrWhiteSpace($TargetBranch)) {
    $TargetBranch = git branch --show-current
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($TargetBranch)) {
        $TargetBranch = "main"
    }
}

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
    $ReleaseTag = "main-latest"
}
$apkName = "mangaViewer_${versionCode}-debug.apk"
$downloadUrl = "https://github.com/$Repo/releases/download/$ReleaseTag/$apkName"
$classificationManifestPath = "release/classification-manifest.json"
$classificationBasePath = "release/classification-base.sqlite.gz"

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
$releasesHtml = [regex]::Replace($releasesHtml, 'browser_download_url:\s*"[^"]*mangaViewer_\d+-debug\.apk"', "browser_download_url: `"$downloadUrl`"", 1)
Write-Utf8NoBom $releasesHtmlPath $releasesHtml

Write-Step "Building debug APK"
Invoke-Gradle -GradleArgs @("--build-cache", "--parallel", "-PreleasePatch=$nextPatch", "-PreleaseDateCode=$dateCodeText", ":app:assembleDebug")

if (-not $SkipTests) {
    Write-Step "Running unit tests"
    Invoke-Gradle -GradleArgs @("--build-cache", "--parallel", "-PreleasePatch=$nextPatch", "-PreleaseDateCode=$dateCodeText", "testDebugUnitTest")
}

$builtApk = "app/build/outputs/apk/debug/$apkName"
if (-not (Test-Path $builtApk)) {
    throw "Built APK not found: $builtApk"
}

Write-Step "Building classification SQLite release assets"
$python = Resolve-PythonExe
& $python -X utf8 tools/classification/build_sqlite_release.py `
    --version "$versionCode" `
    --output "release/classification-base.sqlite" `
    --gzip-output $classificationBasePath `
    --manifest-output $classificationManifestPath
if ($LASTEXITCODE -ne 0) {
    throw "classification SQLite build failed"
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
        Write-Step "Pushing $TargetBranch"
        git push origin "HEAD:$TargetBranch"
        if ($LASTEXITCODE -ne 0) {
            throw "git push failed"
        }
    }
}

if (-not $NoUpload) {
    gh release view $ReleaseTag --repo $Repo *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Step "Creating release $ReleaseTag"
        $createOutput = gh release create $ReleaseTag --repo $Repo --target $TargetBranch --title "Main Latest" --notes "Latest main branch debug APK." --latest 2>&1
        if ($LASTEXITCODE -ne 0) {
            $createText = $createOutput | Out-String
            if ($createText -match 'already exists') {
                Write-Host "Release $ReleaseTag already exists; reusing it."
            } else {
                Write-Host $createText
                throw "gh release create failed"
            }
        }
    }

    Write-Step "Uploading APK release asset"
    gh release upload $ReleaseTag $builtApk --clobber --repo $Repo
    if ($LASTEXITCODE -ne 0) {
        throw "gh release upload failed"
    }

    Write-Step "Uploading version metadata release asset"
    gh release upload $ReleaseTag $versionJsonPath --clobber --repo $Repo
    if ($LASTEXITCODE -ne 0) {
        throw "gh release upload version metadata failed"
    }

    Write-Step "Uploading classification DB release assets"
    gh release upload $ReleaseTag $classificationManifestPath $classificationBasePath --clobber --repo $Repo
    if ($LASTEXITCODE -ne 0) {
        throw "gh release upload classification DB assets failed"
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
Write-Host "apk=$builtApk"
Write-Host "url=$downloadUrl"
