#Requires -Version 7.2
param(
    [string]$Serial = 'emulator-5554',
    [string]$ExpectedAvd = 'MangaViewerApi35',
    [Parameter(Mandatory = $true)][string]$InstalledApk,
    [Parameter(Mandatory = $true)][string]$InstalledTestApk,
    [string]$OutputRoot = '.artifacts/qualification',
    [string]$PolicyPath,
    [string]$InstrumentationTestClass = 'ml.melun.mangaview.viewer.ViewerRandomFiveEpisodeCorpusTest',
    [switch]$RegressionsOnly,
    # Compatibility name retained: this budget starts only after READY, while readiness uses the
    # total instrumentation deadline below.
    [int]$ReadinessTimeoutSeconds = 120,
    [int]$InstrumentationTimeoutSeconds = 14400
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $taskRoot
$packageName = 'ml.melun.mangaview'
$testPackageName = "$packageName.test"
$testClass = $InstrumentationTestClass

function Invoke-AdbChecked {
    param([string[]]$Arguments)
    $result = & adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments[0]); $result" }
    return ($result -join "`n")
}

function Read-SnapshotHash {
    $paths = & git -c core.quotepath=false ls-files --cached --others --exclude-standard
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate qualification source snapshot' }
    $items = foreach ($path in ($paths | Sort-Object -Unique)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $digest = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
            "$path $digest"
        }
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($items -join "`n"))
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Read-InstalledBaseApk {
    param([string]$PackageName)
    $packagePath = Invoke-AdbChecked -Arguments @('shell', 'pm', 'path', $PackageName)
    $deviceApk = ($packagePath -split "`n" | Where-Object { $_ -match '/base\.apk$' } | Select-Object -First 1) -replace '^package:', ''
    if ($deviceApk -notmatch '^/data/app/[A-Za-z0-9_./=+~\-]+\.apk$') {
        throw "Cannot establish installed base APK identity for $PackageName"
    }
    $hash = ((Invoke-AdbChecked -Arguments @('shell', 'sha256sum', $deviceApk)) -split '\s+')[0].ToLowerInvariant()
    if ($hash -notmatch '^[a-f0-9]{64}$') { throw "Installed APK hash is malformed for $PackageName" }
    return [ordered]@{ path = $deviceApk; sha256 = $hash }
}

function Test-SampleSpecificDisplayFailure {
    param($Series)
    if ($null -eq $Series.violationCounts) { return $false }
    foreach ($property in $Series.violationCounts.PSObject.Properties) {
        if ($property.Name -ne 'calibration' -and [long]$property.Value -gt 0) { return $true }
    }
    return $false
}

function Get-LowerSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Start-QualificationTrace {
    param(
        [Parameter(Mandatory = $true)][string]$RemotePath,
        [Parameter(Mandatory = $true)][string]$TraceConfigurationPath
    )
    # The trace session is deliberately started through the host-owned adb command before the
    # instrumentation process.  --background-wait returns a device-side pid that this runner
    # records and interrupts later; no global tracing session is ever stopped.
    $configText = Get-Content -LiteralPath $TraceConfigurationPath -Raw
    $launchOutput = $configText | & adb -s $Serial shell perfetto -c - --txt --background-wait -o $RemotePath 2>&1
    $launchExit = $LASTEXITCODE
    if ($launchExit -ne 0) { throw "Perfetto did not start: $($launchOutput -join "`n")" }
    $pidMatch = [regex]::Match(($launchOutput -join "`n"), '(?m)^\s*(\d+)\s*$')
    if (-not $pidMatch.Success) {
        throw "Perfetto returned no independently owned session PID: $($launchOutput -join "`n")"
    }
    return [pscustomobject]@{
        RemotePath = $RemotePath
        DevicePid = [int]$pidMatch.Groups[1].Value
        Stopped = $false
    }
}

function Stop-QualificationTrace {
    param(
        [Parameter(Mandatory = $false)]$Session,
        [int]$TimeoutSeconds = 15
    )
    if ($null -eq $Session -or $Session.Stopped -or [int]$Session.DevicePid -le 0) { return }
    Invoke-AdbChecked -Arguments @('shell', 'kill', '-INT', "$($Session.DevicePid)") | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $alive = $true
    do {
        & adb -s $Serial shell kill -0 "$($Session.DevicePid)" 2>$null | Out-Null
        $alive = $LASTEXITCODE -eq 0
        if ($alive) { Start-Sleep -Milliseconds 200 }
    } while ($alive -and [DateTime]::UtcNow -lt $deadline)
    if ($alive) { throw "Owned Perfetto session $($Session.DevicePid) did not finish flushing" }
    $Session.Stopped = $true
}

function Start-OwnedRedirectedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$StandardOutputPath,
        [Parameter(Mandatory = $true)][string]$StandardErrorPath
    )
    # Start-Process gives the host a real local process handle whose lifetime can be bounded.
    return Start-Process -FilePath $FilePath -ArgumentList $Arguments -WindowStyle Hidden `
        -RedirectStandardOutput $StandardOutputPath -RedirectStandardError $StandardErrorPath -PassThru
}

function Stop-OwnedProcess {
    param(
        [Parameter(Mandatory = $false)]$Process,
        [int]$TimeoutMilliseconds = 5000
    )
    if ($null -eq $Process) { return }
    try {
        if (-not $Process.HasExited) {
            Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
            [void]$Process.WaitForExit($TimeoutMilliseconds)
        }
    } catch {
        # Cleanup is reported by the caller and never replaces the first qualification failure.
    }
}

function Invoke-BoundedPython {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$StandardOutputPath,
        [Parameter(Mandatory = $true)][string]$StandardErrorPath,
        [int]$TimeoutSeconds = 120,
        [int]$TimeoutMilliseconds = 0
    )
    $child = Start-Process -FilePath 'python' -ArgumentList $Arguments -WindowStyle Hidden `
        -RedirectStandardOutput $StandardOutputPath -RedirectStandardError $StandardErrorPath -PassThru
    $waitMilliseconds = if ($TimeoutMilliseconds -gt 0) { $TimeoutMilliseconds } else { $TimeoutSeconds * 1000 }
    if (-not $child.WaitForExit($waitMilliseconds)) {
        Stop-OwnedProcess -Process $child
        throw "Bounded Python helper timed out after $([Math]::Round($waitMilliseconds / 1000.0, 3)) seconds"
    }
    return [pscustomobject]@{ ExitCode = $child.ExitCode; Process = $child }
}

function Assert-HostVerdictDeadline {
    param([Parameter(Mandatory = $true)][DateTime]$Deadline)
    if ([DateTime]::UtcNow -ge $Deadline) {
        throw 'The configured post-READY host verdict budget expired'
    }
}

function Get-RemainingHostVerdictMilliseconds {
    param([Parameter(Mandatory = $true)][DateTime]$Deadline)
    Assert-HostVerdictDeadline -Deadline $Deadline
    $remaining = [Math]::Floor(($Deadline - [DateTime]::UtcNow).TotalMilliseconds)
    if ($remaining -lt 1) { throw 'The configured post-READY host verdict budget expired' }
    return [int][Math]::Min($remaining, [int]::MaxValue)
}

function Get-MeaningfulInstrumentationFailure {
    param([Parameter(Mandatory = $false)][string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $patterns = @(
        '(?m)^\s*INSTRUMENTATION_STATUS:\s*stack=(.+)$',
        '(?m)^\s*.*(?:Exception|Error|missing|disappeared|failed).*$',
        '(?m)^\s*(?:FAILURES!!!|INSTRUMENTATION_FAILED.*|INSTRUMENTATION_STATUS_CODE:\s*-[12].*)$'
    )
    foreach ($pattern in $patterns) {
        $match = [regex]::Match($Text, $pattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($match.Success -and -not [string]::IsNullOrWhiteSpace($match.Value)) {
            $value = $match.Value.Trim()
            if ($value.Length -gt 8192) { $value = $value.Substring(0, 8192) }
            return $value
        }
    }
    return $null
}

function Read-BarrierLogEvents {
    param(
        [Parameter(Mandatory = $true)]$State,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $events = [Collections.Generic.List[string]]::new()
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $events.ToArray() }
    $stream = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
            [IO.FileShare]::ReadWrite)
        [void]$stream.Seek([int64]$State.ByteOffset, [IO.SeekOrigin]::Begin)
        $buffer = [byte[]]::new(8192)
        while (($count = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $State.Buffer += [Text.Encoding]::UTF8.GetString($buffer, 0, $count)
            $State.ByteOffset = $stream.Position
        }
    } catch {
        # The redirect target can be opened while adb is rotating/closing it.  The next bounded
        # read retries; a readiness timeout remains the authoritative failure.
        return $events.ToArray()
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
    }
    while ($true) {
        $newline = $State.Buffer.IndexOf("`n")
        if ($newline -lt 0) { break }
        $line = $State.Buffer.Substring(0, $newline).TrimEnd("`r")
        $State.Buffer = $State.Buffer.Substring($newline + 1)
        $match = [regex]::Match($line,
            'QualificationBarrier\s*:\s*READY\s+(?<sampleKey>[A-Za-z0-9][A-Za-z0-9._-]*)')
        if ($match.Success) { $events.Add($match.Groups['sampleKey'].Value) }
    }
    return $events.ToArray()
}

function Wait-ForBarrierReady {
    param(
        [Parameter(Mandatory = $true)]$State,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)]$InstrumentationProcess,
        [Parameter(Mandatory = $true)][DateTime]$Deadline,
        [Parameter(Mandatory = $true)][string[]]$ExpectedSampleKeys
    )
    while ([DateTime]::UtcNow -lt $Deadline) {
        foreach ($sampleKey in @(Read-BarrierLogEvents -State $State -Path $LogPath)) {
            # The filtered logcat stream may contain buffered READY lines from an older run.  A
            # current run's exact sample-key set is the only accepted readiness source; a key
            # from that set is returned so the caller can enforce strict order.
            if ($ExpectedSampleKeys.Contains($sampleKey)) { return $sampleKey }
        }
        if ($InstrumentationProcess.HasExited) {
            # Drain one final local-log chunk before classifying an early process exit.
            foreach ($sampleKey in @(Read-BarrierLogEvents -State $State -Path $LogPath)) {
                if ($ExpectedSampleKeys.Contains($sampleKey)) { return $sampleKey }
            }
            return $null
        }
        Start-Sleep -Milliseconds 100
    }
    return $null
}

function Copy-DirectoryContents {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    foreach ($entry in @(Get-ChildItem -LiteralPath $Source -Force)) {
        Copy-Item -LiteralPath $entry.FullName -Destination $Destination -Recurse -Force
    }
}

function Pull-SampleWrapper {
    param(
        [Parameter(Mandatory = $true)][string]$RemoteWrapper,
        [Parameter(Mandatory = $true)][string]$SampleKey,
        [Parameter(Mandatory = $true)][int]$Sequence,
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$EvidenceRoot
    )
    $rawParent = Join-Path $RunDirectory 'raw-samples'
    $rawStage = Join-Path $rawParent ('{0:D4}' -f $Sequence)
    New-Item -ItemType Directory -Force -Path $rawStage | Out-Null
    $dotPullError = $null
    try {
        # Pull the wrapper contents into the compact sequence directory.  The result is verified
        # below instead of assuming adb's directory-target nesting semantics; the fallback keeps
        # the remote wrapper name one level below the same short staging directory.
        Invoke-AdbChecked -Arguments @('pull', "$RemoteWrapper/.", $rawStage) | Out-Null
    } catch {
        $dotPullError = $_.Exception.Message
    }
    $pulled = $null
    $sampleFiles = @()
    if ($null -eq $dotPullError) {
        $sampleFiles = @(Get-ChildItem -LiteralPath $rawStage -Recurse -Filter sample.json -File -ErrorAction SilentlyContinue)
        if ($sampleFiles.Count -eq 1) { $pulled = $sampleFiles[0].Directory.FullName }
    }
    if ($null -eq $pulled) {
        $fallbackStage = Join-Path $rawStage 'directory'
        New-Item -ItemType Directory -Force -Path $fallbackStage | Out-Null
        Invoke-AdbChecked -Arguments @('pull', $RemoteWrapper, $fallbackStage) | Out-Null
        $sampleFiles = @(Get-ChildItem -LiteralPath $fallbackStage -Recurse -Filter sample.json -File -ErrorAction SilentlyContinue)
        if ($sampleFiles.Count -eq 1) { $pulled = $sampleFiles[0].Directory.FullName }
    }
    if ($null -eq $pulled) {
        if ($dotPullError) { throw "Could not pull sample wrapper ${SampleKey}: $dotPullError" }
        throw "Pulled sample wrapper $SampleKey did not contain exactly one sample.json"
    }
    $sampleDocument = Get-Content -LiteralPath (Join-Path $pulled 'sample.json') -Raw | ConvertFrom-Json
    if ([string]$sampleDocument.sampleKey -ne $SampleKey) {
        throw "Pulled sample wrapper identity mismatch: expected $SampleKey, found $($sampleDocument.sampleKey)"
    }
    $localWrapper = Join-Path $EvidenceRoot ('{0:D4}' -f $Sequence)
    if (Test-Path -LiteralPath $localWrapper) {
        throw "Duplicate local wrapper staging for $SampleKey"
    }
    Copy-DirectoryContents -Source $pulled -Destination $localWrapper
    return [pscustomobject]@{ Local = $localWrapper; Raw = $pulled }
}

function Write-ExternalVerdict {
    param(
        [Parameter(Mandatory = $true)]$Checkpoint,
        [Parameter(Mandatory = $true)][string]$CheckpointPath,
        [Parameter(Mandatory = $true)][bool]$Passed,
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$RemoteDirectory,
        [Parameter(Mandatory = $true)][string]$SampleKey,
        [DateTime]$Deadline = [DateTime]::MinValue
    )
    $checkpointHash = Get-LowerSha256 -Path $CheckpointPath
    $verdict = [ordered]@{
        schema = 1
        schemaVersion = 1
        runId = [string]$Checkpoint.runId
        sampleKey = [string]$Checkpoint.sampleKey
        attemptSha256 = [string]$Checkpoint.attemptSha256
        policySha256 = [string]$Checkpoint.policySha256
        checkpointNonce = [string]$Checkpoint.checkpointNonce
        checkpointSha256 = $checkpointHash
        passed = $Passed
    }
    if ($verdict.runId -ne $runId -or $verdict.sampleKey -ne $SampleKey) {
        throw "Refusing to write a verdict for a mismatched checkpoint: $SampleKey"
    }
    if ($Passed -and $Deadline -ne [DateTime]::MinValue) { Assert-HostVerdictDeadline -Deadline $Deadline }
    $localPath = Join-Path $RunDirectory ("verdict-$SampleKey.json")
    [IO.File]::WriteAllText($localPath, ($verdict | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    $remoteFinal = "$RemoteDirectory/verdict-$runId-$SampleKey.json"
    $remoteTemporary = "$RemoteDirectory/.verdict-$runId-$SampleKey.tmp.json"
    Invoke-AdbChecked -Arguments @('push', $localPath, $remoteTemporary) | Out-Null
    if ($Passed -and $Deadline -ne [DateTime]::MinValue) { Assert-HostVerdictDeadline -Deadline $Deadline }
    Invoke-AdbChecked -Arguments @('shell', 'mv', '-f', $remoteTemporary, $remoteFinal) | Out-Null
    if ($Passed -and $Deadline -ne [DateTime]::MinValue) { Assert-HostVerdictDeadline -Deadline $Deadline }
    return [pscustomobject]@{ Path = $localPath; Sha256 = (Get-LowerSha256 -Path $localPath); Passed = $Passed }
}

function Get-RemoteRootChildren {
    param(
        [Parameter(Mandatory = $true)][string]$RemoteDirectory,
        [Parameter(Mandatory = $true)][ValidateSet('f', 'd')][string]$Kind
    )
    $listing = Invoke-AdbChecked -Arguments @('shell', 'find', $RemoteDirectory,
        '-maxdepth', '1', '-type', $Kind, '-print')
    $prefix = "$RemoteDirectory/"
    $children = [Collections.Generic.List[string]]::new()
    foreach ($line in ($listing -split "`n")) {
        $candidate = $line.Trim()
        if ($candidate.Length -le $prefix.Length -or -not $candidate.StartsWith($prefix)) { continue }
        $relative = $candidate.Substring($prefix.Length)
        if ($relative.IndexOf('/') -lt 0) { $children.Add($candidate) }
    }
    return $children.ToArray()
}

function Merge-FinalRemoteRoot {
    param(
        [Parameter(Mandatory = $true)][string]$RemoteQualificationRoot,
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$EvidenceRoot,
        [Parameter(Mandatory = $true)][string[]]$ExpectedSampleKeys
    )
    # Never pull the qualification root as a directory: its remote sample-key and artifact names
    # can exceed Windows path limits when adb recreates the complete tree.  Pull each root file
    # into a short metadata directory and each missing sample into raw-samples/<sequence>.
    $rawFinalParent = Join-Path $RunDirectory 'raw-final-files'
    $rootFilesStage = Join-Path $rawFinalParent 'root'
    New-Item -ItemType Directory -Force -Path $rootFilesStage | Out-Null
    $rootFiles = @(Get-RemoteRootChildren -RemoteDirectory $RemoteQualificationRoot -Kind 'f')
    $rootFiles | ForEach-Object { $_ } | Set-Content -LiteralPath (Join-Path $rawFinalParent 'root-files.list.txt') -Encoding utf8
    foreach ($remoteFile in $rootFiles) {
        $name = Split-Path -Leaf $remoteFile
        $localFile = Join-Path $rootFilesStage $name
        Invoke-AdbChecked -Arguments @('pull', $remoteFile, $localFile) | Out-Null
    }

    foreach ($name in @('summary.json', 'outcomes.json', 'corpus.json', 'selection-events.jsonl')) {
        $source = Join-Path $rootFilesStage $name
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $EvidenceRoot $name) -Force
        }
    }

    $externalStage = Join-Path $rawFinalParent 'external-verdicts'
    New-Item -ItemType Directory -Force -Path $externalStage | Out-Null
    $remoteExternal = "$RemoteQualificationRoot/external-verdicts"
    try {
        $externalFiles = @(Get-RemoteRootChildren -RemoteDirectory $remoteExternal -Kind 'f')
        foreach ($remoteFile in $externalFiles) {
            $name = Split-Path -Leaf $remoteFile
            Invoke-AdbChecked -Arguments @('pull', $remoteFile, (Join-Path $externalStage $name)) | Out-Null
        }
    } catch {
        # A run that fails before the first checkpoint has no external-verdicts directory; the
        # root metadata and partial sample handling below remain authoritative in that case.
        Set-Content -LiteralPath (Join-Path $externalStage 'pull-error.txt') -Value $_.Exception.Message -Encoding utf8
    }

    $remoteDirectories = @(Get-RemoteRootChildren -RemoteDirectory $RemoteQualificationRoot -Kind 'd')
    $remoteDirectories | ForEach-Object { $_ } |
        Set-Content -LiteralPath (Join-Path $rawFinalParent 'root-directories.list.txt') -Encoding utf8
    $localSampleKeys = [Collections.Generic.HashSet[string]]::new()
    foreach ($wrapper in @(Get-ChildItem -LiteralPath $EvidenceRoot -Directory -Force)) {
        $samplePath = Join-Path $wrapper.FullName 'sample.json'
        if (-not (Test-Path -LiteralPath $samplePath -PathType Leaf)) { continue }
        try { [void]$localSampleKeys.Add([string](Get-Content -LiteralPath $samplePath -Raw | ConvertFrom-Json).sampleKey) } catch { }
    }
    $nextPartial = @(Get-ChildItem -LiteralPath $EvidenceRoot -Directory -Force |
        Where-Object { $_.Name -match '^\d{4}$' }).Count + 1
    foreach ($remoteDirectory in $remoteDirectories) {
        $name = Split-Path -Leaf $remoteDirectory
        if ($name -eq 'external-verdicts' -or -not $ExpectedSampleKeys.Contains($name)) { continue }
        if ($localSampleKeys.Contains($name)) { continue }
        # Pull only an uncollected/failed sample.  Pull-SampleWrapper verifies sample.json's
        # identity after trying the compact '/.' target and uses the directory fallback only if
        # that target did not produce a verifiable wrapper.
        Pull-SampleWrapper -RemoteWrapper $remoteDirectory -SampleKey $name -Sequence $nextPartial `
            -RunDirectory $RunDirectory -EvidenceRoot $EvidenceRoot | Out-Null
        [void]$localSampleKeys.Add($name)
        $nextPartial++
    }
    return $rawFinalParent
}

if ($Serial -ne 'emulator-5554') { throw 'Qualification is restricted to the designated emulator-5554' }
$devices = & adb devices
if ($LASTEXITCODE -ne 0 -or -not ($devices -match '^emulator-5554\s+device$')) {
    throw 'Designated emulator is unavailable'
}
$avd = Invoke-AdbChecked -Arguments @('emu', 'avd', 'name')
if (($avd -split "`n")[0].Trim() -ne $ExpectedAvd) { throw "Unexpected AVD: $avd" }
$adapters = @(Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and $_.HardwareInterface })
if ($adapters.Count -ne 1 -or $adapters[0].InterfaceDescription -notmatch 'Ethernet|GbE|GBE') {
    throw 'Qualification requires the sole active hardware network adapter to be wired Ethernet'
}
$routes = @(Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Where-Object { $_.State -eq 'Alive' })
if ($routes.Count -eq 0 -or ($routes | Where-Object { $_.InterfaceIndex -ne $adapters[0].InterfaceIndex })) {
    throw 'Default network route is not exclusively through the designated wired adapter'
}
$apkPath = (Resolve-Path -LiteralPath $InstalledApk).Path
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$testApkPath = (Resolve-Path -LiteralPath $InstalledTestApk).Path
$testApkHash = (Get-FileHash -LiteralPath $testApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$installedBase = Read-InstalledBaseApk $packageName
$installedTestBase = Read-InstalledBaseApk $testPackageName
if ($installedBase.sha256 -ne $apkHash) { throw 'Installed APK differs from the supplied qualification artifact' }
if ($installedTestBase.sha256 -ne $testApkHash) {
    throw 'Installed instrumentation APK differs from the supplied qualification artifact'
}
$qualifierPath = (Resolve-Path -LiteralPath $PSCommandPath).Path
$qualifierHash = (Get-FileHash -LiteralPath $qualifierPath -Algorithm SHA256).Hash.ToLowerInvariant()
$traceConfig = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'qualification_frames.cfg')).Path
$traceConfigHash = (Get-FileHash -LiteralPath $traceConfig -Algorithm SHA256).Hash.ToLowerInvariant()

$seedBytes = [byte[]]::new(8)
[Security.Cryptography.RandomNumberGenerator]::Fill($seedBytes)
$seed = [BitConverter]::ToInt64($seedBytes, 0) -band [long]::MaxValue
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$runDirectory = Join-Path $OutputRoot $runId
New-Item -ItemType Directory -Path $runDirectory | Out-Null
$runDirectory = (Resolve-Path -LiteralPath $runDirectory).Path
$snapshotHash = Read-SnapshotHash
$policy = if ($PolicyPath) { Get-Content -LiteralPath $PolicyPath -Raw } else { '{"exceptions":[]}' }
$parsedPolicy = $policy | ConvertFrom-Json
if ($null -eq $parsedPolicy.exceptions) { throw 'Policy must contain an explicit exceptions array' }
$policyProperties = @($parsedPolicy.PSObject.Properties.Name)
$hasAcceptanceMode = $policyProperties -contains 'acceptanceMode'
$hasPhysicalPresentationTiming = $policyProperties -contains 'physicalPresentationTiming'
if (($hasAcceptanceMode -or $hasPhysicalPresentationTiming) -and
    (-not $hasAcceptanceMode -or -not $hasPhysicalPresentationTiming -or
     $parsedPolicy.acceptanceMode -ne 'OBSERVABLE_RENDER_V1' -or
     $parsedPolicy.physicalPresentationTiming -ne 'UNAVAILABLE_REPORTED')) {
    throw 'Policy must use the exact authorized acceptanceMode/physicalPresentationTiming pair'
}
[IO.File]::WriteAllText((Join-Path $runDirectory 'policy.json'), $policy, [Text.UTF8Encoding]::new($false))
$fingerprint = Invoke-AdbChecked -Arguments @('shell', 'getprop', 'ro.build.fingerprint')
[ordered]@{
    runId = $runId; seed = $seed; serial = $Serial; avd = $ExpectedAvd
    sourceSnapshotSha256 = $snapshotHash; installedApkSha256 = $apkHash
    installedTestApkSha256 = $testApkHash; qualifierSha256 = $qualifierHash
    traceConfigSha256 = $traceConfigHash
    deviceFingerprint = $fingerprint; network = $adapters | Select-Object Name, InterfaceDescription, LinkSpeed, InterfaceIndex
    policySha256 = (Get-FileHash -LiteralPath (Join-Path $runDirectory 'policy.json') -Algorithm SHA256).Hash
    cacheManipulation = 'NONE'; inputMethod = 'Actual UiAutomator gestures and actual library UI taps'
    scope = if ($RegressionsOnly) { 'MANDATORY_REGRESSIONS_ONLY' } else { 'FINAL_200' }
    failurePolicy = if ($RegressionsOnly) {
        'Mandatory regression phase has no corpus credit; any failure resets this phase to zero'
    } else {
        'Entire count resets to zero; next invocation chooses a fresh seed and all 40 works again'
    }
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'attempt.json') -Encoding utf8
$attemptPath = Join-Path $runDirectory 'attempt.json'

$traceRemoteBase = "/data/misc/perfetto-traces/qualification-$runId"
$remotePolicy = "/sdcard/Android/data/$packageName/files/ux-evidence/policy-$runId.json"
$remoteQualificationRoot = "/sdcard/Android/data/$packageName/files/ux-evidence/qualification-$runId"
$remoteBarrierDirectory = "$remoteQualificationRoot/external-verdicts"
$remoteRegressions = "/sdcard/Android/data/$packageName/files/ux-evidence/qualification-prior-failures.json"
$remoteSingleRegressions = "/sdcard/Android/data/$packageName/files/ux-evidence/qualification-single-episode-regressions.json"
$evidenceRoot = Join-Path $runDirectory 'ux-evidence'
$segmentScript = Join-Path $PSScriptRoot 'qualification_segments.py'
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null

Invoke-AdbChecked -Arguments @('shell', 'mkdir', '-p', "/sdcard/Android/data/$packageName/files/ux-evidence") | Out-Null
Invoke-AdbChecked -Arguments @('push', (Join-Path $runDirectory 'policy.json'), $remotePolicy) | Out-Null
$existingRegressions = Join-Path $runDirectory 'existing-prior-failures.json'
$evidenceNames = Invoke-AdbChecked -Arguments @('shell', 'ls', '-1', "/sdcard/Android/data/$packageName/files/ux-evidence")
if (($evidenceNames -split "`n") -contains 'qualification-prior-failures.json') {
    Invoke-AdbChecked -Arguments @('pull', $remoteRegressions, $existingRegressions) | Out-Null
}
$importArgs = @('-B', (Join-Path $PSScriptRoot 'import_qualification_failures.py'),
    '--root', (Join-Path $taskRoot '.artifacts'), '--output', (Join-Path $runDirectory 'prior-failures.json'),
    '--single-episode-output', (Join-Path $runDirectory 'single-episode-regressions.json'),
    '--unresolved-output', (Join-Path $runDirectory 'unresolved-prior-failures.json'))
if (Test-Path -LiteralPath $existingRegressions) { $importArgs += @('--existing', $existingRegressions) }
$importRun = Invoke-BoundedPython -Arguments $importArgs `
    -StandardOutputPath (Join-Path $runDirectory 'failure-import.stdout.txt') `
    -StandardErrorPath (Join-Path $runDirectory 'failure-import.stderr.txt') `
    -TimeoutSeconds 120
if ($importRun.ExitCode -ne 0) { throw 'Historical failed samples could not be identified; inspect unresolved-prior-failures.json' }
$singleEpisodePath = Join-Path $runDirectory 'single-episode-regressions.json'
$singleEpisodeHash = Get-LowerSha256 -Path $singleEpisodePath
$attempt = Get-Content -LiteralPath $attemptPath -Raw | ConvertFrom-Json
$attempt | Add-Member -NotePropertyName singleEpisodeRegressionsSha256 -NotePropertyValue $singleEpisodeHash
$attempt | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $attemptPath -Encoding utf8
$attemptArtifactHash = Get-LowerSha256 -Path $attemptPath
Invoke-AdbChecked -Arguments @('push', (Join-Path $runDirectory 'prior-failures.json'), $remoteRegressions) | Out-Null
Invoke-AdbChecked -Arguments @('push', $singleEpisodePath, $remoteSingleRegressions) | Out-Null
if (-not (Test-Path -LiteralPath $traceConfig)) { throw 'Missing streaming qualification trace configuration' }
if (-not (Test-Path -LiteralPath $segmentScript)) { throw 'Missing qualification segment helper' }
if ($ReadinessTimeoutSeconds -lt 1 -or $InstrumentationTimeoutSeconds -lt 1) {
    throw 'Readiness and instrumentation timeouts must be positive and bounded'
}
$policySha256 = Get-LowerSha256 -Path (Join-Path $runDirectory 'policy.json')

$singleCount = @((Get-Content -LiteralPath $singleEpisodePath -Raw | ConvertFrom-Json)).Count
$priorCount = @((Get-Content -LiteralPath (Join-Path $runDirectory 'prior-failures.json') -Raw | ConvertFrom-Json)).Count
if ($RegressionsOnly -and ($singleCount -lt 5 -or $priorCount -lt 20)) {
    throw "Mandatory regressions-only phase requires at least five single regressions and twenty full chains; found $singleCount/$priorCount"
}
$sampleKeys = [Collections.Generic.List[string]]::new()
for ($index = 1; $index -le $singleCount; $index++) { $sampleKeys.Add("single-$runId-$index") }
for ($index = 0; $index -lt $priorCount; $index++) { $sampleKeys.Add("regression-$index-$runId") }
if (-not $RegressionsOnly) {
    for ($index = 1; $index -le 40; $index++) { $sampleKeys.Add("corpus-$runId-$index") }
}
$expectedPhaseSampleCount = $singleCount + $priorCount + $(if ($RegressionsOnly) { 0 } else { 40 })
if ($sampleKeys.Count -ne $expectedPhaseSampleCount) { throw 'Qualification sample order could not be constructed' }

$instrumentationProcess = $null
$barrierLogProcess = $null
$traceSession = $null
$instrumentationStdout = Join-Path $runDirectory 'instrumentation.stdout.txt'
$instrumentationStderr = Join-Path $runDirectory 'instrumentation.stderr.txt'
$barrierLogPath = Join-Path $runDirectory 'qualification-barrier.logcat.txt'
$barrierLogErrorPath = Join-Path $runDirectory 'qualification-barrier.logcat.stderr.txt'
$logState = [pscustomobject]@{ ByteOffset = [int64]0; Buffer = '' }
$segmentManifests = [Collections.Generic.List[string]]::new()
$segmentRecords = [Collections.Generic.List[object]]::new()
$cleanupFailures = [Collections.Generic.List[string]]::new()
$primaryFailure = $null
$failureSampleKey = $null
$instrumentationTimedOut = $false
$instrumentationExit = -1
$instrumentationText = ''
$activeCheckpoint = $null
$activeCheckpointPath = $null
$activeSampleRawRoot = $null
$activeSampleKey = $null
$activeVerdictPublished = $false

try {
    # Logcat is the only readiness channel.  During real UI gestures the host does not poll adb;
    # it reads this locally redirected stream until the Android barrier emits READY.
    $barrierLogProcess = Start-OwnedRedirectedProcess -FilePath 'adb' `
        -Arguments @('-s', $Serial, 'logcat', '-v', 'threadtime', '-s', 'QualificationBarrier:I', '*:S') `
        -StandardOutputPath $barrierLogPath -StandardErrorPath $barrierLogErrorPath

    $testArguments = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $testClass,
        '-e', 'corpusSeed', "$seed", '-e', 'corpusRunId', $runId,
        '-e', 'corpusExternalDisplay', 'true', '-e', 'corpusAttemptSha256', $attemptArtifactHash,
        '-e', 'corpusPolicyPath', $remotePolicy, '-e', 'corpusRegressionsOnly',
        $(if ($RegressionsOnly) { 'true' } else { 'false' }),
        "$packageName.test/androidx.test.runner.AndroidJUnitRunner")

    # The first owned trace must be live before the instrumentation process can enter its first
    # sample.  Every subsequent trace is started before its successful verdict is published.
    $traceSession = Start-QualificationTrace -RemotePath "$traceRemoteBase-0001.pftrace" `
        -TraceConfigurationPath $traceConfig
    # This is intentionally the direct adb instrumentation command, with a real Start-Process
    # handle and separate redirected streams.  No test-runner wrapper owns its lifetime.
    $instrumentationProcess = Start-Process -FilePath 'adb' -ArgumentList $testArguments -WindowStyle Hidden `
        -RedirectStandardOutput $instrumentationStdout -RedirectStandardError $instrumentationStderr -PassThru
    $runDeadline = [DateTime]::UtcNow.AddSeconds($InstrumentationTimeoutSeconds)

    for ($sequence = 1; $sequence -le $sampleKeys.Count; $sequence++) {
        $expectedKey = $sampleKeys[$sequence - 1]
        # Live catalog discovery, real UI navigation, the per-sample viewer traversal and memory
        # closure all precede READY.  Readiness is bounded only by the total instrumentation
        # deadline; ReadinessTimeoutSeconds is reserved for the post-READY host verdict window.
        $readyDeadline = $runDeadline
        $readyKey = Wait-ForBarrierReady -State $logState -LogPath $barrierLogPath `
            -InstrumentationProcess $instrumentationProcess -Deadline $readyDeadline `
            -ExpectedSampleKeys $sampleKeys.ToArray()
        if ($null -eq $readyKey) {
            $failureSampleKey = $expectedKey
            if ($instrumentationProcess.HasExited) {
                throw "Instrumentation exited before QualificationBarrier READY $expectedKey"
            }
            $instrumentationTimedOut = $true
            throw "Timed out waiting for QualificationBarrier READY $expectedKey"
        }
        if ($readyKey -ne $expectedKey) {
            $failureSampleKey = $readyKey
            throw "QualificationBarrier READY out of order: expected $expectedKey, received $readyKey"
        }
        if ([DateTime]::UtcNow -ge $runDeadline) {
            $failureSampleKey = $expectedKey
            $instrumentationTimedOut = $true
            throw 'Instrumentation orchestration deadline expired'
        }
        $hostVerdictDeadline = [DateTime]::UtcNow.AddSeconds($ReadinessTimeoutSeconds)

        $failureSampleKey = $expectedKey
        $sampleRawRoot = Join-Path $runDirectory ('samples/{0:D4}' -f $sequence)
        New-Item -ItemType Directory -Force -Path $sampleRawRoot | Out-Null
        $remoteCheckpoint = "$remoteBarrierDirectory/checkpoint-$runId-$expectedKey.json"
        $checkpointPath = Join-Path $sampleRawRoot 'checkpoint.json'
        Assert-HostVerdictDeadline -Deadline $hostVerdictDeadline
        Invoke-AdbChecked -Arguments @('pull', $remoteCheckpoint, $checkpointPath) | Out-Null
        Assert-HostVerdictDeadline -Deadline $hostVerdictDeadline
        $checkpoint = Get-Content -LiteralPath $checkpointPath -Raw | ConvertFrom-Json
        if ($checkpoint.schemaVersion -ne 1 -and $checkpoint.schema -ne 1) { throw "Checkpoint schema is not 1 for $expectedKey" }
        foreach ($field in @('runId', 'sampleKey', 'attemptSha256', 'policySha256', 'checkpointNonce')) {
            if (-not ($checkpoint.PSObject.Properties.Name -contains $field)) { throw "Checkpoint lacks $field for $expectedKey" }
        }
        if ($checkpoint.runId -ne $runId -or $checkpoint.sampleKey -ne $expectedKey -or
            $checkpoint.attemptSha256.ToLowerInvariant() -ne $attemptArtifactHash -or
            $checkpoint.policySha256.ToLowerInvariant() -ne $policySha256) {
            throw "Checkpoint binding mismatch for $expectedKey"
        }
        $activeCheckpoint = $checkpoint
        $activeCheckpointPath = $checkpointPath
        $activeSampleRawRoot = $sampleRawRoot
        $activeSampleKey = $expectedKey
        $activeVerdictPublished = $false
        $checkpointHash = Get-LowerSha256 -Path $checkpointPath

        # Stop and flush only the trace this host created for this sample, then preserve both its
        # raw bytes and the exact remote sample wrapper before invoking the segment verifier.
        $currentTrace = $traceSession
        Assert-HostVerdictDeadline -Deadline $hostVerdictDeadline
        Stop-QualificationTrace -Session $currentTrace
        Assert-HostVerdictDeadline -Deadline $hostVerdictDeadline
        $tracePath = Join-Path $sampleRawRoot 'segment.pftrace'
        Invoke-AdbChecked -Arguments @('pull', $currentTrace.RemotePath, $tracePath) | Out-Null
        Assert-HostVerdictDeadline -Deadline $hostVerdictDeadline
        $wrapper = Pull-SampleWrapper -RemoteWrapper "$remoteQualificationRoot/$expectedKey" `
            -SampleKey $expectedKey -Sequence $sequence -RunDirectory $runDirectory -EvidenceRoot $evidenceRoot
        Assert-HostVerdictDeadline -Deadline $hostVerdictDeadline
        $segmentOutput = Join-Path $sampleRawRoot 'segment'
        New-Item -ItemType Directory -Force -Path $segmentOutput | Out-Null
        $segmentStdout = Join-Path $sampleRawRoot 'segment-helper.stdout.txt'
        $segmentStderr = Join-Path $sampleRawRoot 'segment-helper.stderr.txt'
        $segmentArguments = @('-B', $segmentScript, 'verify-segment',
            '--checkpoint', $checkpointPath, '--run-id', $runId,
            '--attempt-sha256', $attemptArtifactHash, '--policy-sha256', $policySha256,
            '--checkpoint-nonce', [string]$checkpoint.checkpointNonce,
            '--trace', $tracePath, '--evidence-directory', $wrapper.Local,
            '--policy', (Join-Path $runDirectory 'policy.json'), '--output-directory', $segmentOutput)
        $segmentRun = Invoke-BoundedPython -Arguments $segmentArguments -StandardOutputPath $segmentStdout `
            -StandardErrorPath $segmentStderr -TimeoutMilliseconds (Get-RemainingHostVerdictMilliseconds -Deadline $hostVerdictDeadline)
        $segmentExit = $segmentRun.ExitCode
        Assert-HostVerdictDeadline -Deadline $hostVerdictDeadline
        $manifestPath = Join-Path $segmentOutput 'segment-manifest.json'
        $segmentPassed = $false
        if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
            try { $segmentPassed = [bool](Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json).passed } catch { $segmentPassed = $false }
        }
        if ($segmentExit -ne 0 -or -not $segmentPassed -or -not (Test-Path -LiteralPath $manifestPath)) {
            $segmentRecords.Add([pscustomobject]@{ sampleKey = $expectedKey; passed = $false; manifest = $manifestPath; exitCode = $segmentExit })
            # A valid checkpoint lets the host release the Android barrier with an explicit
            # failure.  This preserves the original verifier failure while stopping before the
            # next sample can begin.
            Write-ExternalVerdict -Checkpoint $checkpoint -CheckpointPath $checkpointPath -Passed $false `
                -RunDirectory $sampleRawRoot -RemoteDirectory $remoteBarrierDirectory -SampleKey $expectedKey | Out-Null
            $activeVerdictPublished = $true
            if (-not $instrumentationProcess.HasExited) {
                [void]$instrumentationProcess.WaitForExit(10000)
            }
            throw "Qualification segment failed for $expectedKey (exit $segmentExit)"
        }
        $segmentManifests.Add($manifestPath)
        $segmentRecords.Add([pscustomobject]@{ sampleKey = $expectedKey; passed = $true; manifest = $manifestPath; exitCode = $segmentExit })

        $nextTrace = $null
        if ($sequence -lt $sampleKeys.Count) {
            Assert-HostVerdictDeadline -Deadline $hostVerdictDeadline
            $nextTrace = Start-QualificationTrace -RemotePath "$traceRemoteBase-$('{0:D4}' -f ($sequence + 1)).pftrace" `
                -TraceConfigurationPath $traceConfig
            # Publish ownership to the cleanup path before writing the verdict.  If the verdict
            # push fails, the newly started trace must still be the one that cleanup interrupts.
            $traceSession = $nextTrace
            Assert-HostVerdictDeadline -Deadline $hostVerdictDeadline
        }
        # Starting the next trace precedes the passing verdict, so the next actual UI sample can
        # never run in an untraced interval.
        Assert-HostVerdictDeadline -Deadline $hostVerdictDeadline
        Write-ExternalVerdict -Checkpoint $checkpoint -CheckpointPath $checkpointPath -Passed $true `
            -RunDirectory $sampleRawRoot -RemoteDirectory $remoteBarrierDirectory -SampleKey $expectedKey `
            -Deadline $hostVerdictDeadline | Out-Null
        $activeVerdictPublished = $true
    }

    $postCorpusDeadline = [DateTime]::UtcNow.AddSeconds([Math]::Min(120, $InstrumentationTimeoutSeconds))
    if ($postCorpusDeadline -gt $runDeadline) { $postCorpusDeadline = $runDeadline }
    while (-not $instrumentationProcess.HasExited -and [DateTime]::UtcNow -lt $postCorpusDeadline) {
        Start-Sleep -Milliseconds 100
    }
    if (-not $instrumentationProcess.HasExited) {
        $instrumentationTimedOut = $true
        throw 'Instrumentation did not exit after the final external verdict'
    }
    $instrumentationExit = $instrumentationProcess.ExitCode
} catch {
    $primaryFailure = $_.Exception
} finally {
    if ($null -ne $activeCheckpoint -and -not $activeVerdictPublished) {
        try {
            Write-ExternalVerdict -Checkpoint $activeCheckpoint -CheckpointPath $activeCheckpointPath -Passed $false `
                -RunDirectory $activeSampleRawRoot -RemoteDirectory $remoteBarrierDirectory -SampleKey $activeSampleKey | Out-Null
            $activeVerdictPublished = $true
            if ($null -ne $instrumentationProcess -and -not $instrumentationProcess.HasExited) {
                [void]$instrumentationProcess.WaitForExit(10000)
            }
        } catch { $cleanupFailures.Add("failed-sample verdict preservation: $($_.Exception.Message)") }
    }
    # Cleanup is individually bounded and only addresses processes/traces created above.  Each
    # error is retained separately so it cannot replace the first sample/verifier failure.
    if ($null -ne $instrumentationProcess) {
        try {
            if (-not $instrumentationProcess.HasExited) { Stop-OwnedProcess -Process $instrumentationProcess }
            if ($instrumentationProcess.HasExited) { $instrumentationExit = $instrumentationProcess.ExitCode }
        } catch { $cleanupFailures.Add("instrumentation cleanup: $($_.Exception.Message)") }
    }
    if ($null -ne $traceSession) {
        try {
            if (-not $traceSession.Stopped) {
                Stop-QualificationTrace -Session $traceSession
                $partialPath = Join-Path $runDirectory 'partial-active-trace.pftrace'
                Invoke-AdbChecked -Arguments @('pull', $traceSession.RemotePath, $partialPath) | Out-Null
            }
        } catch { $cleanupFailures.Add("trace cleanup: $($_.Exception.Message)") }
    }
    if ($null -ne $barrierLogProcess) {
        try { Stop-OwnedProcess -Process $barrierLogProcess } catch { $cleanupFailures.Add("logcat cleanup: $($_.Exception.Message)") }
    }
    try {
        if (Test-Path -LiteralPath $instrumentationStdout) {
            $stdoutText = Get-Content -LiteralPath $instrumentationStdout -Raw
        } else { $stdoutText = '' }
        if (Test-Path -LiteralPath $instrumentationStderr) {
            $stderrText = Get-Content -LiteralPath $instrumentationStderr -Raw
        } else { $stderrText = '' }
        $instrumentationText = $stdoutText + "`n" + $stderrText
        [IO.File]::WriteAllText((Join-Path $runDirectory 'instrumentation.txt'), $instrumentationText, [Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $barrierLogPath) {
            Copy-Item -LiteralPath $barrierLogPath -Destination (Join-Path $runDirectory 'logcat.txt') -Force
        }
    } catch { $cleanupFailures.Add("instrumentation artifact preservation: $($_.Exception.Message)") }
    try {
        $expectedArray = $sampleKeys.ToArray()
        Merge-FinalRemoteRoot -RemoteQualificationRoot $remoteQualificationRoot -RunDirectory $runDirectory `
            -EvidenceRoot $evidenceRoot -ExpectedSampleKeys $expectedArray | Out-Null
        $remotePolicyCopy = Join-Path $runDirectory 'raw-final-files/root/policy.json'
        if (Test-Path -LiteralPath $remotePolicyCopy -PathType Leaf) {
            Copy-Item -LiteralPath $remotePolicyCopy -Destination (Join-Path $runDirectory 'remote-root-policy.json') -Force
        }
        $remoteSingleCopy = Join-Path $runDirectory 'raw-final-files/root/single-episode-regressions.json'
        if (Test-Path -LiteralPath $remoteSingleCopy -PathType Leaf) {
            Copy-Item -LiteralPath $remoteSingleCopy -Destination (Join-Path $runDirectory 'remote-root-single-episode-regressions.json') -Force
        }
        $remotePriorCopy = Join-Path $runDirectory 'prior-failures.remote.json'
        & adb -s $Serial pull $remoteRegressions $remotePriorCopy 2>&1 |
            Set-Content -LiteralPath (Join-Path $runDirectory 'prior-failures-pull.txt') -Encoding utf8
    } catch { $cleanupFailures.Add("remote evidence preservation: $($_.Exception.Message)") }
}

if ($null -eq $primaryFailure -and $instrumentationExit -ne 0) {
    $primaryFailure = [InvalidOperationException]::new("Instrumentation exited with code $instrumentationExit")
}
if ($null -eq $primaryFailure -and $instrumentationTimedOut) {
    $primaryFailure = [TimeoutException]::new('Instrumentation timeout; qualification count resets to zero')
}

$summary = $null
$summaryPath = Join-Path $evidenceRoot 'summary.json'
$outcomesPath = Join-Path $evidenceRoot 'outcomes.json'
if (Test-Path -LiteralPath $summaryPath -PathType Leaf) {
    try { $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json } catch { if ($null -eq $primaryFailure) { $primaryFailure = $_.Exception } }
}
$orchestrationFailure = if ($primaryFailure) { $primaryFailure.Message } else { $null }
$instrumentationFailure = $null
if ($null -ne $summary -and $summary.runId -eq $runId -and
    $summary.failure -is [string] -and -not [string]::IsNullOrWhiteSpace($summary.failure)) {
    $instrumentationFailure = $summary.failure.Trim()
    if ($instrumentationFailure.Length -gt 8192) { $instrumentationFailure = $instrumentationFailure.Substring(0, 8192) }
} elseif ($instrumentationExit -ne 0 -or $orchestrationFailure -match 'Instrumentation exited|READY') {
    $instrumentationFailure = Get-MeaningfulInstrumentationFailure -Text $instrumentationText
}
$frameworkPassed = $instrumentationExit -eq 0 -and $instrumentationText -match 'OK \(\d+ tests?\)' -and
    $instrumentationText -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[12]'
$snapshotUnchanged = $false
$attemptUnchanged = $false
try { $snapshotUnchanged = (Read-SnapshotHash) -eq $snapshotHash } catch { $cleanupFailures.Add("snapshot verification: $($_.Exception.Message)") }
try { $attemptUnchanged = (Get-LowerSha256 -Path $attemptPath) -eq $attemptArtifactHash } catch { $cleanupFailures.Add("attempt verification: $($_.Exception.Message)") }
$summaryPhasePassed = $false
if ($null -ne $summary) {
    if ($RegressionsOnly) {
        $summaryPhasePassed = $summary.runId -eq $runId -and
            $summary.regressionPhaseCompleted -eq $true -and
            $summary.collectionCompleted -eq $false -and
            $summary.passed -eq $false -and
            $summary.consecutivePassed -eq 0 -and
            $summary.policySha256 -eq $policySha256
    } else {
        $summaryPhasePassed = $summary.runId -eq $runId -and
            $summary.collectionCompleted -eq $true -and
            $summary.passed -eq $false -and
            $summary.policySha256 -eq $policySha256
    }
}
$collected = $frameworkPassed -and $summaryPhasePassed -and $snapshotUnchanged -and $attemptUnchanged -and
    $segmentManifests.Count -eq $sampleKeys.Count

$displayPath = Join-Path $runDirectory 'display-verification.json'
$aggregateExit = -1
if ($null -eq $primaryFailure -and $collected) {
    $aggregateArgs = @('-B', $segmentScript, 'aggregate')
    foreach ($manifest in $segmentManifests) { $aggregateArgs += @('--manifest', $manifest) }
    $aggregateArgs += @('--output', $displayPath)
    $aggregateRun = Invoke-BoundedPython -Arguments $aggregateArgs `
        -StandardOutputPath (Join-Path $runDirectory 'segment-aggregate.stdout.txt') `
        -StandardErrorPath (Join-Path $runDirectory 'segment-aggregate.stderr.txt') `
        -TimeoutSeconds $ReadinessTimeoutSeconds
    $aggregateExit = $aggregateRun.ExitCode
    if ($aggregateExit -ne 0) { $primaryFailure = [InvalidOperationException]::new('Segment aggregate did not grant pass') }
}

$contractScript = Join-Path $PSScriptRoot 'qualification_runner_contract.py'
$contractExit = -1
if (-not $RegressionsOnly -and $null -eq $primaryFailure -and $collected -and $aggregateExit -eq 0 -and (Test-Path -LiteralPath $displayPath)) {
    $contractArguments = @('shape', '--summary', $summaryPath, '--outcomes', $outcomesPath,
        '--prior', (Join-Path $runDirectory 'prior-failures.json'), '--single', $singleEpisodePath,
        '--single-episode-sha256', $singleEpisodeHash, '--display', $displayPath,
        '--policy', (Join-Path $runDirectory 'policy.json'), '--evidence-root', $evidenceRoot,
        '--attempt', $attemptPath, '--installed-apk-sha256', $apkHash,
        '--installed-test-apk-sha256', $testApkHash, '--qualifier-sha256', $qualifierHash,
        '--trace-config-sha256', $traceConfigHash, '--run-id', $runId, '--seed', "$seed")
    $contractRun = Invoke-BoundedPython -Arguments (@('-B', $contractScript) + $contractArguments) `
        -StandardOutputPath (Join-Path $runDirectory 'contract-shape.stdout.txt') `
        -StandardErrorPath (Join-Path $runDirectory 'contract-shape.stderr.txt') `
        -TimeoutSeconds $ReadinessTimeoutSeconds
    $contractExit = $contractRun.ExitCode
    if ($contractExit -ne 0) { $primaryFailure = [InvalidOperationException]::new('Corpus artifact shape contract failed') }
}
if ($null -eq $primaryFailure -and $contractExit -eq 0) {
    $passArguments = $contractArguments.Clone()
    $passArguments[0] = 'pass'
    $passRun = Invoke-BoundedPython -Arguments (@('-B', $contractScript) + $passArguments) `
        -StandardOutputPath (Join-Path $runDirectory 'contract-pass.stdout.txt') `
        -StandardErrorPath (Join-Path $runDirectory 'contract-pass.stderr.txt') `
        -TimeoutSeconds $ReadinessTimeoutSeconds
    if ($passRun.ExitCode -ne 0) { $primaryFailure = [InvalidOperationException]::new('Complete corpus artifact contract did not pass') }
}

$normalPassed = $null -eq $primaryFailure -and $collected -and $aggregateExit -eq 0 -and $contractExit -eq 0
$regressionsPassed = $RegressionsOnly -and $null -eq $primaryFailure -and $collected -and
    $aggregateExit -eq 0 -and $cleanupFailures.Count -eq 0
$passed = if ($RegressionsOnly) { $false } else { $normalPassed }
$orchestrationFailure = if ($primaryFailure) { $primaryFailure.Message } else { $null }
$failureReason = if ($instrumentationFailure) { $instrumentationFailure } else { $orchestrationFailure }
$result = [ordered]@{
    passed = $passed
    regressionsPassed = if ($RegressionsOnly) { $regressionsPassed } else { $null }
    scope = if ($RegressionsOnly) { 'NO_CORPUS_CREDIT' } else { 'FINAL_200' }
    consecutivePassed = if ($RegressionsOnly) { 0 } elseif ($passed) { 200 } else { 0 }
    runId = $runId
    sourceSnapshotSha256 = $snapshotHash
    installedApkSha256 = $apkHash
    installedTestApkSha256 = $testApkHash
    qualifierSha256 = $qualifierHash
    traceConfigSha256 = $traceConfigHash
    policySha256 = $policySha256
    singleEpisodeRegressionsSha256 = $singleEpisodeHash
    candidateArtifactSha256 = $attemptArtifactHash
    expectedSampleCount = $sampleKeys.Count
    verifiedSegmentCount = $segmentManifests.Count
    mandatorySingleRegressionCount = $singleCount
    mandatoryFullChainCount = $priorCount
    summaryPhasePassed = $summaryPhasePassed
    instrumentationFailure = $instrumentationFailure
    orchestrationFailure = $orchestrationFailure
    failureSampleKey = $failureSampleKey
    reason = $failureReason
    cleanupFailures = @($cleanupFailures.ToArray())
    evidence = $runDirectory
}
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $runDirectory 'qualification.json') -Encoding utf8
if ($RegressionsOnly) {
    if (-not $regressionsPassed) {
        throw "Mandatory regressions-only phase failed; no corpus credit. Evidence: $runDirectory. $failureReason"
    }
    Write-Output "PASS mandatory regressions: $($sampleKeys.Count) samples, no corpus credit. $runDirectory"
    exit 0
}
if (-not $passed) { throw "Qualification failed; 0/200. Evidence: $runDirectory. $failureReason" }
Write-Output "PASS 200/200: $runDirectory"
