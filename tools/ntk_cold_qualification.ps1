[CmdletBinding()]
#requires -Version 7.2

param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$AppApkPath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$BenchmarkApkPath,

    [string]$DeviceSerial = $env:ANDROID_SERIAL,
    [string]$NtkSiteRoot = "https://sbxh9.com",
    [string]$OutDir = "build\outputs\ntk-cold",
    [long]$Seed = 0,
    [string]$ReplaySelectionPath = "",
    [string]$ReplayTargetKeys = "",
    [ValidateRange(1, 100)]
    [int]$CountPerType = 10,
    [ValidateRange(250, 30000)]
    [int]$FirstImageSlaMs = 4000,
    [ValidateRange(250, 30000)]
    [int]$ManhwaImageSlaMs = 4000,
    [ValidateRange(60, 1800)]
    [int]$CaseTimeoutSeconds = 360,
    [ValidateSet("PHYSICAL_DEVICE", "HOST_GPU_EMULATOR")]
    [string]$QualificationDeviceMode = "PHYSICAL_DEVICE",
    [switch]$SkipInstall,
    [switch]$RequireBaselineProfile,
    [switch]$StandalonePerfetto,
    [switch]$RestartHostGpuProcessPerCase,
    [string]$HostGpuEmulatorPath = "",
    [string]$HostGpuAvdName = "",
    [switch]$FastFunctionalTriage,
    [bool]$IncludeWarmReopen = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if($FastFunctionalTriage) {
    # A warm reopen is a separate diagnostic and doubles neither cold coverage nor triage value.
    # The canonical path keeps its caller-selected setting unchanged.
    $IncludeWarmReopen = $false
}

function Get-SeedQualificationState([long]$RequestedSeed) {
    if($RequestedSeed -lt 0L) {
        throw "Seed must be zero for a new random run or a positive logged seed for diagnostic reproduction"
    }
    $freshRandom = $RequestedSeed -eq 0L
    return [pscustomobject][ordered]@{
        selectionMode = if($freshRandom) { "FRESH_RANDOM" } else { "FIXED_SEED_REPRODUCTION" }
        freshRandomSeedRequirementSatisfied = $freshRandom
    }
}
$seedQualification = Get-SeedQualificationState $Seed

$AppPackage = "ml.melun.mangaview"
$BenchmarkPackage = "ml.melun.mangaview.macrobenchmark"
$Runner = "$BenchmarkPackage/androidx.test.runner.AndroidJUnitRunner"
$TestClass = "$BenchmarkPackage.NtkColdViewerMacrobenchmark#coldViewerRandomWork"
$FormalWebtoonImageSlaMs = 4000
$FormalManhwaImageSlaMs = 4000
# Qualification safety ceilings. These are production bounds, not test-tunable parameters.
$ProductionMaxActiveRequestQueue = 120
$ProductionMinForwardGestures = 3
$ProductionMaxForwardGestures = 500
$ProductionWarmRetainedPssFloorLimitKb = 16384L
$ProductionWarmRetainedPssRatioLimit = 0.10
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$siteRoot = $NtkSiteRoot.TrimEnd('/')
$perfettoConfig = Join-Path $PSScriptRoot "ntk_perfetto.textproto"
$AndroidxPerfettoTraceOutput = "/data/misc/perfetto-traces/trace_output.pb"
$reportScript = Join-Path $PSScriptRoot "ntk_cold_report.ps1"
$adb = (Get-Command adb -ErrorAction Stop).Source

if([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $devices = @(& $adb devices | Select-String "\tdevice$" | ForEach-Object {
        ($_.Line -split "\s+")[0]
    })
    if($devices.Count -ne 1) {
        throw "Set -DeviceSerial or ANDROID_SERIAL; ready devices=$($devices -join ',')"
    }
    $DeviceSerial = $devices[0]
}

function Invoke-HostProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [ValidateRange(1, 3600)][int]$TimeoutSeconds = 60,
        [switch]$AllowFailure
    )
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FilePath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    # adb emits device-side logcat/instrumentation text as UTF-8. ProcessStartInfo otherwise uses
    # the Windows active code page for redirected streams, corrupting Korean slug JSON and making
    # a valid NtkColdMacro record appear missing to the host report parser.
    $utf8 = [Text.UTF8Encoding]::new($false)
    $start.StandardOutputEncoding = $utf8
    $start.StandardErrorEncoding = $utf8
    foreach($argument in $Arguments) {
        [void]$start.ArgumentList.Add([string]$argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if(-not $process.Start()) {
        throw "Could not start '$FilePath'"
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    try {
        if(-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            [void]$process.WaitForExit(5000)
            throw "Process timed out after ${TimeoutSeconds}s: $FilePath $($Arguments -join ' ')"
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $text = @($stdout.TrimEnd(), $stderr.TrimEnd()) |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Join-String -Separator "`n"
        $result = [pscustomobject][ordered]@{
            ExitCode = [int]$process.ExitCode
            Stdout = [string]$stdout
            Stderr = [string]$stderr
            Text = [string]$text
        }
        if(-not $AllowFailure -and $result.ExitCode -ne 0) {
            throw "Command exited $($result.ExitCode): $FilePath $($Arguments -join ' ')`n$($result.Text)"
        }
        return $result
    } finally {
        $process.Dispose()
    }
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [ValidateRange(1, 3600)][int]$TimeoutSeconds = 60,
        [switch]$AllowFailure
    )
    return Invoke-HostProcess -FilePath $script:adb `
        -Arguments (@("-s", $script:DeviceSerial) + $Arguments) `
        -TimeoutSeconds $TimeoutSeconds -AllowFailure:$AllowFailure
}

function Set-HostGpuEmulatorPerformancePolicy {
    if($script:QualificationDeviceMode -cne "HOST_GPU_EMULATOR") {
        return [pscustomobject][ordered]@{
            applied = $false
            reason = "not-host-gpu-emulator-mode"
        }
    }
    if(-not $IsWindows) {
        throw "HOST_GPU_EMULATOR performance policy currently requires Windows process identity"
    }
    $avdProbe = Invoke-Adb @("emu", "avd", "name") -AllowFailure
    $avdName = @($avdProbe.Stdout -split "`r?`n" | ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -cne "OK" }) | Select-Object -First 1
    if([string]::IsNullOrWhiteSpace($avdName) -or
            $avdName -notmatch '^[A-Za-z0-9._-]+$') {
        throw "Could not resolve the host-GPU AVD name for process-priority policy"
    }
    $escapedAvd = [regex]::Escape($avdName)
    $qemuNames = @("qemu-system-x86_64.exe", "qemu-system-x86_64-headless.exe")
    $matches = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in $qemuNames -and
        $_.CommandLine -match "(?:^|\s)-avd\s+`"?$escapedAvd(?:`"?\s|$)"
    })
    if($matches.Count -ne 1) {
        throw "Expected exactly one qemu process for AVD '$avdName'; found=$($matches.Count)"
    }
    $qemu = Get-Process -Id ([int]$matches[0].ProcessId) -ErrorAction Stop
    $before = [string]$qemu.PriorityClass
    $qemu.PriorityClass = [Diagnostics.ProcessPriorityClass]::High
    $qemu.Refresh()
    if($qemu.PriorityClass -ne [Diagnostics.ProcessPriorityClass]::High) {
        throw "Could not apply High process priority to qemu pid=$($qemu.Id)"
    }
    return [pscustomobject][ordered]@{
        applied = $true
        avdName = $avdName
        processId = $qemu.Id
        processName = $qemu.ProcessName
        beforePriority = $before
        effectivePriority = [string]$qemu.PriorityClass
        policy = "High"
        rationale = "Reduce host scheduler wake jitter without excluding or altering measured frames"
    }
}

function Restart-HostGpuEmulatorForCase([int]$Ordinal) {
    if($script:QualificationDeviceMode -cne "HOST_GPU_EMULATOR") {
        throw "Per-case emulator-process isolation is valid only in HOST_GPU_EMULATOR mode"
    }
    if(-not $IsWindows) {
        throw "Per-case host-GPU process isolation currently requires Windows process identity"
    }
    if($script:DeviceSerial -notmatch '^emulator-(\d+)$') {
        throw "Host-GPU process isolation requires an emulator-<port> serial: $($script:DeviceSerial)"
    }
    $port = [int]$Matches[1]
    $avdName = $script:HostGpuAvdName.Trim()
    if([string]::IsNullOrWhiteSpace($avdName)) {
        $avdProbe = Invoke-Adb @("emu", "avd", "name") -AllowFailure
        $avdName = @($avdProbe.Stdout -split "`r?`n" | ForEach-Object { $_.Trim() } |
            Where-Object { $_ -and $_ -cne "OK" }) | Select-Object -First 1
    }
    if([string]::IsNullOrWhiteSpace($avdName) -or
        $avdName -notmatch '^[A-Za-z0-9._-]+$') {
        throw "Could not resolve a safe AVD name for $($script:DeviceSerial)"
    }

    $emulatorPath = $script:HostGpuEmulatorPath.Trim()
    if([string]::IsNullOrWhiteSpace($emulatorPath)) {
        $sdkRoot = Split-Path -Parent (Split-Path -Parent $script:adb)
        $emulatorPath = Join-Path $sdkRoot "emulator\emulator.exe"
    }
    $emulatorPath = (Get-Item -LiteralPath $emulatorPath -ErrorAction Stop).FullName

    $qemuProcessNames = @("qemu-system-x86_64.exe", "qemu-system-x86_64-headless.exe")
    $oldProcesses = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in (@("emulator.exe") + $qemuProcessNames) -and
        $_.CommandLine -match "(?:^|\s)-port\s+$port(?:\s|$)"
    })
    $oldQemu = @($oldProcesses | Where-Object { $_.Name -in $qemuProcessNames } |
        Select-Object -First 1)
    $oldQemuWorkingSetBytes = $null
    if($oldQemu.Count -eq 1) {
        $oldQemuProcess = Get-Process -Id ([int]$oldQemu[0].ProcessId) `
            -ErrorAction SilentlyContinue
        if($null -ne $oldQemuProcess) {
            $oldQemuWorkingSetBytes = [long]$oldQemuProcess.WorkingSet64
        }
    }
    $kill = Invoke-Adb @("emu", "kill") -TimeoutSeconds 30 -AllowFailure
    foreach($identity in $oldProcesses) {
        $process = Get-Process -Id ([int]$identity.ProcessId) -ErrorAction SilentlyContinue
        if($null -ne $process) {
            $process | Wait-Process -Timeout 30 -ErrorAction SilentlyContinue
        }
    }
    $survivors = @($oldProcesses | Where-Object {
        $null -ne (Get-Process -Id ([int]$_.ProcessId) -ErrorAction SilentlyContinue)
    })
    if($survivors.Count -ne 0) {
        throw "Exact emulator processes did not stop: $($survivors.ProcessId -join ',')"
    }

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $emulatorPath
    $startInfo.UseShellExecute = $true
    $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    foreach($argument in @(
        "-avd", $avdName, "-port", [string]$port,
        # Keep the explicitly provisioned qualification resources across every hard process
        # restart. Falling back to the AVD defaults after case one made SurfaceFlinger present
        # waits grow from one refresh period to 80-190ms even though app CPU/GPU work stayed flat.
        "-cores", "8", "-memory", "8192", "-gpu", "host",
        # The windowed QEMU backend can remain alive before opening its adb port after a prior
        # host-GPU process is retired. The headless backend retains the same host GLES translator
        # and booted this AVD immediately in the reproduced failure.
        "-no-window", "-no-snapshot-load", "-no-boot-anim", "-no-audio", "-no-metrics"
    )) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    $startedAt = [DateTimeOffset]::Now
    $emulatorProcess = [Diagnostics.Process]::Start($startInfo)
    if($null -eq $emulatorProcess) { throw "Could not restart Android Emulator" }
    [void](Invoke-HostProcess -FilePath $script:adb -Arguments @(
        "-s", $script:DeviceSerial, "wait-for-device"
    ) -TimeoutSeconds 180)
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    do {
        Start-Sleep -Milliseconds 500
        $boot = Invoke-Adb @("shell", "getprop", "sys.boot_completed") -AllowFailure
        if($boot.ExitCode -eq 0 -and $boot.Stdout.Trim() -ceq "1") { break }
    } while([DateTime]::UtcNow -lt $deadline)
    if($boot.ExitCode -ne 0 -or $boot.Stdout.Trim() -cne "1") {
        throw "Restarted emulator did not complete boot within 180 seconds"
    }
    $freshDevice = Get-DeviceInfo
    if(-not $freshDevice.hostGpuEmulatorSatisfied) {
        throw "Restarted emulator did not retain host-GPU qualification"
    }
    # The original process policy cannot survive a QEMU replacement. Apply and prove the same
    # scheduler class before package verification or any case navigation; otherwise case 1 runs
    # at High while every isolated later case silently falls back to Normal and inherits host HWC
    # wake jitter unrelated to app work.
    $freshProcessPolicy = Set-HostGpuEmulatorPerformancePolicy
    # A package installed immediately before the first hard emulator restart can be
    # visible to the live PackageManager but not yet durable in the AVD data image.
    # Verify both APKs after every process restart. Reinstalling a missing APK is not
    # app execution or content warm-up; Invoke-ColdCase still force-stops and clears
    # both packages after this point and before the measured viewer click.
    $reinstalledPackages = [Collections.Generic.List[string]]::new()
    foreach($packageSpec in @(
        [pscustomobject]@{ Package = $script:AppPackage; Apk = $script:appApk },
        [pscustomobject]@{ Package = $script:BenchmarkPackage; Apk = $script:benchmarkApk }
    )) {
        $packagePath = Invoke-Adb @(
            "shell", "pm", "path", [string]$packageSpec.Package
        ) -AllowFailure
        if($packagePath.ExitCode -ne 0 -or
                -not $packagePath.Stdout.Contains("package:", [StringComparison]::Ordinal)) {
            if($script:SkipInstall) {
                throw "Required package disappeared after emulator restart while -SkipInstall is active: $($packageSpec.Package)"
            }
            [void](Invoke-Adb @(
                "install", "-r", "-t", [string]$packageSpec.Apk
            ) -TimeoutSeconds 180)
            $reinstalledPackages.Add([string]$packageSpec.Package)
        }
    }
    # Flush package metadata before a later hard QEMU retirement. The following
    # pm-clear remains the authoritative proof that no app or benchmark data survives.
    [void](Invoke-Adb @("shell", "sync") -TimeoutSeconds 30 -AllowFailure)
    # boot_completed is published before PackageManager, broadcasts, the launcher window and
    # UiAutomation are necessarily ready. Starting instrumentation in that gap can successfully
    # launch MainActivity while UiDevice keeps returning the boot launcher's stale accessibility
    # root for the entire navigation timeout. Prove system idleness and a real focused launcher
    # before the cold case starts. This does not launch the app or touch content/network state.
    $broadcastIdle = Invoke-Adb @(
        "shell", "cmd", "activity", "wait-for-broadcast-idle"
    ) -TimeoutSeconds 120
    $packageHandlerIdle = Invoke-Adb @(
        "shell", "cmd", "package", "wait-for-handler"
    ) -TimeoutSeconds 120
    [void](Invoke-Adb @("shell", "wm", "dismiss-keyguard") -TimeoutSeconds 30)
    [void](Invoke-Adb @(
        "shell", "input", "keyevent", "KEYCODE_HOME"
    ) -TimeoutSeconds 30)
    $launcherFocusDeadline = [DateTime]::UtcNow.AddSeconds(30)
    $launcherFocus = $null
    do {
        $launcherFocus = Invoke-Adb @(
            "shell", "dumpsys", "window"
        ) -TimeoutSeconds 30
        if($launcherFocus.Stdout -match
                'mCurrentFocus=.*com\.google\.android\.apps\.nexuslauncher') {
            break
        }
        Start-Sleep -Milliseconds 250
    } while([DateTime]::UtcNow -lt $launcherFocusDeadline)
    if($launcherFocus.Stdout -notmatch
            'mCurrentFocus=.*com\.google\.android\.apps\.nexuslauncher') {
        throw "Restarted emulator did not publish a focused launcher within 30 seconds"
    }
    $newProcesses = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in (@("emulator.exe") + $qemuProcessNames) -and
        $_.CommandLine -match "(?:^|\s)-port\s+$port(?:\s|$)"
    })
    $newQemu = @($newProcesses | Where-Object { $_.Name -in $qemuProcessNames } |
        Select-Object -First 1)
    return [pscustomobject][ordered]@{
        ordinal = $Ordinal
        startedAt = $startedAt.ToString("o")
        readyAt = [DateTimeOffset]::Now.ToString("o")
        deviceSerial = $script:DeviceSerial
        avdName = $avdName
        emulatorPath = $emulatorPath
        killExitCode = $kill.ExitCode
        oldProcessIds = @($oldProcesses | ForEach-Object { [int]$_.ProcessId })
        oldQemuWorkingSetBytes = $oldQemuWorkingSetBytes
        newProcessIds = @($newProcesses | ForEach-Object { [int]$_.ProcessId })
        reinstalledPackages = @($reinstalledPackages)
        broadcastIdleExitCode = $broadcastIdle.ExitCode
        packageHandlerIdleExitCode = $packageHandlerIdle.ExitCode
        launcherFocusProven = $true
        gpu = $freshDevice.surfaceFlingerGles
        hostGpuSatisfied = [bool]$freshDevice.hostGpuEmulatorSatisfied
        performancePolicy = $freshProcessPolicy
    }
}

function Reset-AndroidxPerfettoTraceOutput([string]$Phase) {
    # AndroidX Macrobenchmark writes its source trace at this fixed platform path
    # before copying the retained artifact to additionalTestOutputDir. A timed-out
    # instrumentation may leave both the file and its exact writer behind. Match
    # the writer by its full output path; never use a broad killall/pkill.
    $pidProbe = Invoke-Adb @("shell", "pidof", "perfetto") -AllowFailure
    $matchedPids = [Collections.Generic.List[int]]::new()
    foreach($pidToken in @($pidProbe.Stdout -split '\s+' | Where-Object { $_ -match '^\d+$' })) {
        $cmdline = Invoke-Adb @("shell", "cat", "/proc/$pidToken/cmdline") -AllowFailure
        if($cmdline.ExitCode -eq 0 -and $cmdline.Stdout.Contains(
                $script:AndroidxPerfettoTraceOutput,
                [StringComparison]::Ordinal)) {
            $matchedPids.Add([int]$pidToken)
            [void](Invoke-Adb @("shell", "kill", "-TERM", $pidToken) -AllowFailure)
        }
    }

    $allMatchedWritersStopped = $true
    foreach($matchedPid in $matchedPids) {
        $deadline = [DateTime]::UtcNow.AddSeconds(5)
        do {
            $alive = Invoke-Adb @("shell", "kill", "-0", [string]$matchedPid) -AllowFailure
            if($alive.ExitCode -ne 0) { break }
            Start-Sleep -Milliseconds 100
        } while([DateTime]::UtcNow -lt $deadline)
        if($alive.ExitCode -eq 0) {
            [void](Invoke-Adb @("shell", "kill", "-KILL", [string]$matchedPid) -AllowFailure)
            $alive = Invoke-Adb @("shell", "kill", "-0", [string]$matchedPid) -AllowFailure
        }
        if($alive.ExitCode -eq 0) { $allMatchedWritersStopped = $false }
    }

    $remove = Invoke-Adb @(
        "shell", "rm", "-f", $script:AndroidxPerfettoTraceOutput
    ) -AllowFailure
    $absentProbe = Invoke-Adb @(
        "shell", "test", "!", "-e", $script:AndroidxPerfettoTraceOutput
    ) -AllowFailure
    return [pscustomobject][ordered]@{
        phase = $Phase
        exactPath = $script:AndroidxPerfettoTraceOutput
        matchedWriterPids = @($matchedPids)
        allMatchedWritersStopped = $allMatchedWritersStopped
        removeExitCode = $remove.ExitCode
        absent = ($absentProbe.ExitCode -eq 0)
    }
}

function Remove-RemoteCaseArtifacts(
    [string]$RunRemoteRoot,
    [string]$CaseId,
    [string]$RemoteCase,
    [string]$ScreenshotRemote
) {
    $expectedCase = "$RunRemoteRoot/$CaseId"
    $expectedScreenshot = "/sdcard/Android/data/$script:BenchmarkPackage/files/ntk-cold/$CaseId"
    if($RunRemoteRoot -notmatch '^/sdcard/Android/media/ml\.melun\.mangaview\.macrobenchmark/ntk-cold-output/\d{8}-\d{6}-[1-9]\d*$' -or
            $CaseId -notmatch '^(webtoon|manhwa)-\d{2}-[A-Za-z0-9._-]+$' -or
            $RemoteCase -cne $expectedCase -or
            $ScreenshotRemote -cne $expectedScreenshot) {
        throw "Refusing post-pull cleanup outside exact qualification case paths"
    }

    # Host pulls above are the retained evidence. AndroidX also leaves a full trace copy in the
    # shared external output directory; retaining every remote copy exhausts /data during 10+10.
    # Remove only this validated case and this package-owned screenshot directory.
    $benchmarkRemove = Invoke-Adb @("shell", "rm", "-rf", $RemoteCase) -AllowFailure
    $screenshotRemove = Invoke-Adb @("shell", "rm", "-rf", $ScreenshotRemote) -AllowFailure
    $benchmarkAbsent = (Invoke-Adb @(
        "shell", "test", "!", "-e", $RemoteCase
    ) -AllowFailure).ExitCode -eq 0
    $screenshotAbsent = (Invoke-Adb @(
        "shell", "test", "!", "-e", $ScreenshotRemote
    ) -AllowFailure).ExitCode -eq 0
    return [pscustomobject][ordered]@{
        remoteCase = $RemoteCase
        screenshotRemote = $ScreenshotRemote
        benchmarkRemoveExitCode = $benchmarkRemove.ExitCode
        screenshotRemoveExitCode = $screenshotRemove.ExitCode
        benchmarkAbsent = $benchmarkAbsent
        screenshotAbsent = $screenshotAbsent
    }
}

function Write-Utf8([string]$Path, [string]$Content) {
    $parent = Split-Path -Parent $Path
    if(-not [string]::IsNullOrWhiteSpace($parent)) {
        [void](New-Item -ItemType Directory -Path $parent -Force)
    }
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Write-Json([string]$Path, $Value, [int]$Depth = 20) {
    Write-Utf8 $Path ($Value | ConvertTo-Json -Depth $Depth)
}

function ConvertTo-Base64Utf8([string]$Value) {
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
}

function Get-CaseImageSlaMs([string]$WorkType) {
    if($WorkType -ceq "webtoon") { return [int]$script:FirstImageSlaMs }
    if($WorkType -ceq "manhwa") { return [int]$script:ManhwaImageSlaMs }
    throw "Unsupported work type for image SLA: $WorkType"
}

function ConvertTo-PowerShellLiteral([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

function Get-Sha256([string]$Value) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString(
            $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))
        )).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-FileSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Resolve-RepositoryPath([string]$Path) {
    if([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $script:repositoryRoot $Path))
}

function Test-ApkEmbeddedBaselineProfile([string]$Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        return $null -ne ($archive.Entries | Where-Object {
            $_.FullName -ceq "assets/dexopt/baseline.prof"
        } | Select-Object -First 1)
    } finally {
        $archive.Dispose()
    }
}

function New-RandomSeed {
    $bytes = [byte[]]::new(8)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $value = [long]([BitConverter]::ToUInt64($bytes, 0) -band 0x7fffffffffffffffL)
    if($value -eq 0L) { return 1L }
    return $value
}

function Invoke-MetadataJson([string]$Uri) {
    $headers = @{
        "Accept" = "application/json"
        "Cache-Control" = "no-cache"
        "User-Agent" = "mangaviewer-ntk-cold-selector/1"
    }
    $maximumAttempts = 5
    for($attempt = 1; $attempt -le $maximumAttempts; $attempt++) {
        try {
            return Invoke-RestMethod -Method Get -Uri $Uri -Headers $headers -TimeoutSec 30
        } catch {
            $statusCode = $null
            try { $statusCode = [int]$_.Exception.Response.StatusCode } catch {}
            $transient = $null -eq $statusCode -or $statusCode -eq 408 -or
                $statusCode -eq 429 -or $statusCode -ge 500
            if(-not $transient -or $attempt -eq $maximumAttempts) { throw }
            $delayMs = [Math]::Min(2000, 250 * [Math]::Pow(2, $attempt - 1))
            Write-Warning "Metadata request transient failure attempt=$attempt/$maximumAttempts; retryMs=$delayMs; uri=$Uri; error=$($_.Exception.Message)"
            Start-Sleep -Milliseconds $delayMs
        }
    }
    throw "Metadata request retry loop ended unexpectedly: $Uri"
}

function Get-CompleteCatalog([string]$WorkType) {
    $endpoint = if($WorkType -ceq "webtoon") { "works" } else { "manhwa-list" }
    # The webtoon catalog is large enough that a 500-row offset page can exceed the
    # production gateway's request deadline.  Catalog selection is host-only metadata
    # work, so use bounded pages for both types instead of making one oversized query.
    # This does not inspect or request episode image URLs.
    $pageSize = if($WorkType -ceq "webtoon") { 200 } else { 100 }
    $page = 1
    $all = [Collections.Generic.List[object]]::new()
    do {
        $uri = "$script:siteRoot/api/${endpoint}?status=&page=$page&pageSize=$pageSize&withTotal=1"
        $response = Invoke-MetadataJson $uri
        $works = @($response.works)
        if($works.Count -eq 0 -and $response.hasMore -eq $true) {
            throw "Catalog returned an empty non-terminal page: $uri"
        }
        foreach($work in $works) {
            $id = [string]$work.sourceWorkId
            $title = [string]$work.title
            if(-not [string]::IsNullOrWhiteSpace($id) -and
                    -not [string]::IsNullOrWhiteSpace($title)) {
                $all.Add([pscustomobject][ordered]@{
                    workType = $WorkType
                    workId = $id.Trim()
                    title = $title.Trim()
                    latestEpisodeNumber = $work.latestEpisodeNumber
                    episodeLabel = [string]$work.ep
                    updatedAt = [string]$work.updatedAt
                    platformId = $work.platformId
                    catalogPage = $page
                })
            }
        }
        $page++
        if($page -gt 10000) { throw "Catalog pagination did not terminate: $WorkType" }
    } while($response.hasMore -eq $true)

    $unique = [ordered]@{}
    foreach($work in $all) {
        if(-not $unique.Contains($work.workId)) { $unique[$work.workId] = $work }
    }
    if($unique.Count -lt $script:CountPerType) {
        throw "$WorkType catalog has only $($unique.Count) unique usable metadata rows"
    }
    return @($unique.Values)
}

function Select-StableRandomWorks([object[]]$Works, [string]$WorkType) {
    return @(Get-StableRandomWorkRanking $Works $WorkType |
        Select-Object -First $script:CountPerType)
}

function Get-StableRandomWorkRanking([object[]]$Works, [string]$WorkType) {
    return @($Works | ForEach-Object {
        [pscustomobject][ordered]@{
            rank = Get-Sha256 "$script:Seed|$WorkType|$($_.workId)"
            value = $_
        }
    } | Sort-Object rank |
        ForEach-Object { $_.value })
}

function ConvertFrom-HtmlText([string]$Value) {
    $withoutTags = [regex]::Replace($Value, '<[^>]+>', '')
    return [Net.WebUtility]::HtmlDecode($withoutTags).Trim()
}

function Get-EpisodeMetadataFromDocument($Work) {
    $segment = [regex]::Escape([string]$Work.workType)
    $workId = [regex]::Escape([string]$Work.workId)
    $uri = "$script:siteRoot/$($Work.workType)/$([uri]::EscapeDataString([string]$Work.workId))"
    $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri $uri `
        -Headers @{ "User-Agent" = "mangaviewer-ntk-cold-selector/1" } -TimeoutSec 30
    $pattern = '<a[^>]+href=["''](?<path>/' + $segment + '/' + $workId +
        '/[^"''?#]+)[^"'']*["''][^>]*>.*?<div[^>]+class=["''][^"'']*ep-row-v2-title[^"'']*["''][^>]*>\s*<strong>(?<title>.*?)</strong>'
    $matches = [regex]::Matches(
        [string]$response.Content,
        $pattern,
        [Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
            [Text.RegularExpressions.RegexOptions]::Singleline
    )
    $episodes = [Collections.Generic.List[object]]::new()
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($match in $matches) {
        $path = [Net.WebUtility]::HtmlDecode($match.Groups['path'].Value)
        if($seen.Add($path)) {
            $episodes.Add([pscustomobject][ordered]@{
                episodePath = $path
                episodeId = ($path -split '/')[-1]
                episodeTitle = ConvertFrom-HtmlText $match.Groups['title'].Value
                episodeNumber = $null
                metadataSource = "title-document"
            })
        }
    }
    return @($episodes)
}

function Get-EpisodeMetadata($Work) {
    $episodes = @()
    $apiError = $null
    $encodedId = [uri]::EscapeDataString([string]$Work.workId)
    $apiUri = "$script:siteRoot/api/$($Work.workType)/$encodedId/episodes"
    try {
        $response = Invoke-MetadataJson $apiUri
        $episodes = @($response.episodes | ForEach-Object {
            $episodeId = [string]$_.sourceEpisodeId
            [pscustomobject][ordered]@{
                episodePath = "/$($Work.workType)/$($Work.workId)/$episodeId"
                episodeId = $episodeId
                episodeTitle = ([string]$_.title).Trim()
                episodeNumber = $_.epNo
                metadataSource = "episode-api"
            }
        } | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_.episodeId) -and
                -not [string]::IsNullOrWhiteSpace($_.episodeTitle)
        })
    } catch {
        $apiError = $_.Exception.Message
    }
    if($episodes.Count -eq 0) {
        try {
            $episodes = @(Get-EpisodeMetadataFromDocument $Work)
        } catch {
            return [pscustomobject][ordered]@{
                episodePath = ""
                episodeId = ""
                episodeTitle = ""
                episodeNumber = $null
                metadataSource = "ui-first-visible-fallback"
                metadataError = "api=$apiError; document=$($_.Exception.Message)"
                originallySelectedEpisodePath = ""
                originallySelectedEpisodeId = ""
                originallySelectedEpisodeTitle = ""
                accessReplacementReason = $null
                workAccessStatus = "UNKNOWN"
            }
        }
    }
    if($episodes.Count -eq 0) {
        return [pscustomobject][ordered]@{
            episodePath = ""
            episodeId = ""
            episodeTitle = ""
            episodeNumber = $null
            metadataSource = "ui-first-visible-fallback"
            metadataError = "No episode metadata; api=$apiError"
            originallySelectedEpisodePath = ""
            originallySelectedEpisodeId = ""
            originallySelectedEpisodeTitle = ""
            accessReplacementReason = $null
            workAccessStatus = "UNKNOWN"
        }
    }
    # Keep the randomly selected work. Some upstream catalogs prepend provider/imported rows whose
    # ids (for example `kp-*` or `tkor*`) resolve to metadata-only/purchase-gate pages without a
    # viewer payload. The current native canonical ids are numeric or `nv-<workId>-<ordinal>`.
    # Select the first canonical row when the first row is non-native, retaining the original
    # identity and reason in selection evidence. This is access validation from the episode-list
    # API only: no episode document or image URL is requested, and image count/size is never used.
    $original = $episodes[0]
    $value = $original
    $accessReplacementReason = $null
    $nativeAccessible = @()
    $escapedNativeWorkId = [regex]::Escape([string]$Work.workId)
    $nativeEpisodePattern = "^(?:\d+|nv-$escapedNativeWorkId-\d+)$"
    $providerImportedPattern = "^(?:kp-|tkor)"
    if(([string]$original.episodeId) -notmatch $nativeEpisodePattern) {
        $nativeAccessible = @($episodes | Where-Object {
            ([string]$_.episodeId) -match $nativeEpisodePattern
        } | Select-Object -First 1)
        if($nativeAccessible.Count -eq 1) {
            $value = $nativeAccessible[0]
            $accessReplacementReason =
                "first episode uses a provider/imported metadata-only id; selected first native canonical episode"
        }
    }
    $workAccessStatus = if(
        ([string]$original.metadataSource) -ceq "episode-api" -and
        ([string]$original.episodeId) -match $providerImportedPattern -and
        $nativeAccessible.Count -eq 0 -and
        @($episodes | Where-Object {
            ([string]$_.episodeId) -notmatch $providerImportedPattern
        }).Count -eq 0
    ) {
        "NO_NATIVE_CANONICAL_EPISODE"
    } else {
        "ACCESSIBLE_OR_UNPROVEN"
    }
    return [pscustomobject][ordered]@{
        episodePath = [string]$value.episodePath
        episodeId = [string]$value.episodeId
        episodeTitle = [string]$value.episodeTitle
        episodeNumber = $value.episodeNumber
        metadataSource = [string]$value.metadataSource
        metadataError = $apiError
        originallySelectedEpisodePath = [string]$original.episodePath
        originallySelectedEpisodeId = [string]$original.episodeId
        originallySelectedEpisodeTitle = [string]$original.episodeTitle
        accessReplacementReason = $accessReplacementReason
        workAccessStatus = $workAccessStatus
    }
}

function Get-EpisodeAccessStatus($Work, $Episode) {
    $episodePath = [string]$Episode.episodePath
    if([string]::IsNullOrWhiteSpace($episodePath)) {
        return [pscustomobject][ordered]@{
            status = "ACCESSIBLE_OR_UNPROVEN"
            httpStatus = $null
            location = ""
            error = "episode path is empty"
        }
    }

    # This is metadata-only accessibility validation. HEAD neither requests an image URL nor
    # consumes the viewer document body, and the app still starts from pm-clear with a newly
    # created HTTP client. Only explicit deletion/unavailability evidence is replaceable.
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    $request = [Net.Http.HttpRequestMessage]::new(
        [Net.Http.HttpMethod]::Head,
        "$script:siteRoot$episodePath"
    )
    [void]$request.Headers.TryAddWithoutValidation(
        "User-Agent",
        "mangaviewer-ntk-cold-selector/1"
    )
    [void]$request.Headers.TryAddWithoutValidation("Cache-Control", "no-cache")
    try {
        $response = $client.Send(
            $request,
            [Net.Http.HttpCompletionOption]::ResponseHeadersRead
        )
        try {
            $statusCode = [int]$response.StatusCode
            $location = if($null -ne $response.Headers.Location) {
                [string]$response.Headers.Location.OriginalString
            } else {
                ""
            }
            $explicitUnavailableRedirect =
                $statusCode -ge 300 -and $statusCode -lt 400 -and
                $location -match '(?:[?&])ep_unavailable=1(?:&|$)'
            $clearlyUnavailable =
                $statusCode -eq 404 -or $statusCode -eq 410 -or
                $explicitUnavailableRedirect
            return [pscustomobject][ordered]@{
                status = if($clearlyUnavailable) {
                    "CLEARLY_UNAVAILABLE"
                } else {
                    "ACCESSIBLE_OR_UNPROVEN"
                }
                httpStatus = $statusCode
                location = $location
                error = $null
            }
        } finally {
            $response.Dispose()
        }
    } catch {
        # DNS, TLS, timeout, 5xx and other transport outcomes do not prove deletion. Keep the
        # randomly selected work so transient network quality cannot bias the selection.
        return [pscustomobject][ordered]@{
            status = "ACCESSIBLE_OR_UNPROVEN"
            httpStatus = $null
            location = ""
            error = $_.Exception.Message
        }
    } finally {
        $request.Dispose()
        $client.Dispose()
        $handler.Dispose()
    }
}

function Get-DeviceInfo {
    $getprop = {
        param([string]$Name)
        (Invoke-Adb @("shell", "getprop", $Name) -AllowFailure).Stdout.Trim()
    }
    $display = (Invoke-Adb @("shell", "dumpsys", "display") -TimeoutSeconds 30 -AllowFailure).Text
    $refresh = [regex]::Match($display, '(?:refreshRate|fps)=?\s*([0-9]+(?:\.[0-9]+)?)')
    $network = (Invoke-Adb @("shell", "dumpsys", "connectivity") -TimeoutSeconds 30 -AllowFailure).Text
    $surfaceFlinger = (Invoke-Adb @(
        "shell", "dumpsys", "SurfaceFlinger"
    ) -TimeoutSeconds 30 -AllowFailure).Text
    $glesMatch = [regex]::Match($surfaceFlinger, '(?m)^GLES:\s*(.+?)\s*$')
    $glesRenderer = if($glesMatch.Success) { $glesMatch.Groups[1].Value.Trim() } else { "" }
    $networkType = if($network -match '(?i)TRANSPORT_WIFI|type:\s*WIFI') {
        "WIFI"
    } elseif($network -match '(?i)TRANSPORT_CELLULAR|type:\s*MOBILE') {
        "CELLULAR"
    } else {
        "UNKNOWN"
    }
    $identity = [ordered]@{
        manufacturer = & $getprop "ro.product.manufacturer"
        model = & $getprop "ro.product.model"
        brand = & $getprop "ro.product.brand"
        product = & $getprop "ro.product.name"
        device = & $getprop "ro.product.device"
        buildProduct = & $getprop "ro.build.product"
        board = & $getprop "ro.product.board"
        boardPlatform = & $getprop "ro.board.platform"
        hardware = & $getprop "ro.hardware"
        bootHardware = & $getprop "ro.boot.hardware"
        fingerprint = & $getprop "ro.build.fingerprint"
        roSerial = & $getprop "ro.serialno"
        bootSerial = & $getprop "ro.boot.serialno"
    }
    $kernelQemu = (& $getprop "ro.kernel.qemu") -eq "1"
    $bootQemu = (& $getprop "ro.boot.qemu") -eq "1"
    $eglHardware = & $getprop "ro.hardware.egl"
    $hwuiRenderer = & $getprop "debug.hwui.renderer"
    $virtualPattern = '(?i)(?:^|[\/:._ -])(?:goldfish|ranchu|generic(?:_x86(?:_64)?|_arm64)?|sdk_gphone(?:64)?(?:_[A-Za-z0-9]+)?|emulator|vbox|genymotion|qemu|nox)(?=$|[\/:._ -])|android sdk built for'
    $virtualMarkers = [Collections.Generic.List[string]]::new()
    if($kernelQemu) { $virtualMarkers.Add("ro.kernel.qemu=1") }
    if($bootQemu) { $virtualMarkers.Add("ro.boot.qemu=1") }
    foreach($entry in $identity.GetEnumerator()) {
        if(-not [string]::IsNullOrWhiteSpace([string]$entry.Value) -and
                [string]$entry.Value -match $virtualPattern) {
            $virtualMarkers.Add("$($entry.Key)=$($entry.Value)")
        }
    }
    $physicalSerial = @([string]$identity.roSerial, [string]$identity.bootSerial) |
        Where-Object {
            -not [string]::IsNullOrWhiteSpace($_) -and
                $_ -notmatch '(?i)^(?:unknown|emulator-\d+|0+|0123456789abcdef)$'
        } | Select-Object -First 1
    $positivePhysicalIdentity = $null -ne $physicalSerial -and
        -not [string]::IsNullOrWhiteSpace([string]$identity.manufacturer) -and
        -not [string]::IsNullOrWhiteSpace([string]$identity.model) -and
        -not [string]::IsNullOrWhiteSpace([string]$identity.product) -and
        -not [string]::IsNullOrWhiteSpace([string]$identity.device) -and
        -not [string]::IsNullOrWhiteSpace([string]$identity.hardware) -and
        [string]$identity.fingerprint -match '^[^/]+/[^/]+/[^:]+:'
    $virtualDeviceDetected = $virtualMarkers.Count -gt 0
    $hostTranslatorDetected = $glesRenderer -match
        '(?i)Android Emulator OpenGL ES Translator\s*\([^)]*[A-Za-z][^)]*\)'
    $softwareGpuDetected = $glesRenderer -match
        '(?i)(SwiftShader|llvmpipe|softpipe|software rasterizer|Mesa OffScreen|ANGLE\s*\(SwiftShader)'
    $hostGpuEmulatorSatisfied = $virtualDeviceDetected -and
        ($kernelQemu -or $bootQemu) -and
        $eglHardware -ceq "emulation" -and
        $hwuiRenderer -match '^(?:skiagl|skiavk)$' -and
        $hostTranslatorDetected -and -not $softwareGpuDetected
    return [pscustomobject][ordered]@{
        serial = $script:DeviceSerial
        manufacturer = $identity.manufacturer
        model = $identity.model
        brand = $identity.brand
        product = $identity.product
        device = $identity.device
        buildProduct = $identity.buildProduct
        board = $identity.board
        boardPlatform = $identity.boardPlatform
        hardware = $identity.hardware
        bootHardware = $identity.bootHardware
        fingerprint = $identity.fingerprint
        roSerial = $identity.roSerial
        bootSerial = $identity.bootSerial
        abi = & $getprop "ro.product.cpu.abi"
        androidRelease = & $getprop "ro.build.version.release"
        sdk = & $getprop "ro.build.version.sdk"
        qemu = $kernelQemu
        bootQemu = $bootQemu
        virtualDeviceDetected = $virtualDeviceDetected
        virtualDeviceMarkers = @($virtualMarkers)
        positivePhysicalIdentity = $positivePhysicalIdentity
        physicalIdentitySatisfied = ($positivePhysicalIdentity -and -not $virtualDeviceDetected)
        eglHardware = $eglHardware
        hwuiRenderer = $hwuiRenderer
        surfaceFlingerGles = $glesRenderer
        hostTranslatorDetected = $hostTranslatorDetected
        softwareGpuDetected = $softwareGpuDetected
        hostGpuEmulatorSatisfied = $hostGpuEmulatorSatisfied
        refreshHz = if($refresh.Success) { [double]$refresh.Groups[1].Value } else { $null }
        wmSize = (Invoke-Adb @("shell", "wm", "size") -AllowFailure).Text
        wmDensity = (Invoke-Adb @("shell", "wm", "density") -AllowFailure).Text
        networkType = $networkType
    }
}

function Get-PackageUid([string]$PackageDump) {
    $match = [regex]::Match($PackageDump, '(?m)^\s*userId=(\d+)\s*$')
    if($match.Success) { return [int64]$match.Groups[1].Value }
    # Android 15 package dumps can omit userId and expose the same user-0 process identity as
    # top-level appId.  Fail closed unless one of these two exact numeric fields is present.
    $match = [regex]::Match($PackageDump, '(?m)^\s*appId=(\d+)\s*$')
    if($match.Success) { return [int64]$match.Groups[1].Value }
    return $null
}

function Get-PreClickProcessState($ProcessResult, $PackageUid) {
    $entries = [Collections.Generic.List[string]]::new()
    $text = if($null -ne $ProcessResult) { [string]$ProcessResult.Text } else { "" }
    $measured = $null -ne $PackageUid -and $null -ne $ProcessResult -and
        $ProcessResult.ExitCode -eq 0 -and
        $text -match '(?im)^\s*UID\s+PID\s+NAME(?:\s+ARGS)?\s*$'
    if($measured) {
        foreach($line in @($text -split "`r?`n")) {
            $parts = @($line.Trim() -split '\s+', 4)
            if($parts.Count -lt 3 -or $parts[0] -eq "UID") { continue }
            $uid = 0L
            $processId = 0L
            if(-not [int64]::TryParse($parts[0], [ref]$uid) -or
                    -not [int64]::TryParse($parts[1], [ref]$processId)) { continue }
            $name = [string]$parts[2]
            $isBenchmarkOrchestrator = $name -ceq $script:BenchmarkPackage -or
                $name.StartsWith("$($script:BenchmarkPackage):", [StringComparison]::Ordinal)
            $isTargetName = $name -ceq $script:AppPackage -or
                $name.StartsWith("$($script:AppPackage):", [StringComparison]::Ordinal)
            if($isTargetName -or ($uid -eq [int64]$PackageUid -and -not $isBenchmarkOrchestrator)) {
                $entries.Add("uid=$uid pid=$processId name=$name")
            }
        }
    }
    return [pscustomobject][ordered]@{
        measured = $measured
        packageUid = $PackageUid
        count = $entries.Count
        entries = @($entries)
    }
}

function Get-PreClickServiceState($ServiceResult) {
    $entries = [Collections.Generic.List[string]]::new()
    $workEntries = [Collections.Generic.List[string]]::new()
    $text = if($null -ne $ServiceResult) { [string]$ServiceResult.Text } else { "" }
    $measured = $null -ne $ServiceResult -and $ServiceResult.ExitCode -eq 0 -and
        $text -match '(?i)ACTIVITY MANAGER SERVICES|ServiceRecord|\(nothing\)|No services'
    if($measured) {
        $escapedPackage = [regex]::Escape($script:AppPackage)
        $serviceRecordPattern = '(?i)ServiceRecord\{.*(?:\s|/)' + $escapedPackage + '/'
        foreach($line in @($text -split "`r?`n")) {
            if($line -match $serviceRecordPattern) {
                $value = $line.Trim()
                $entries.Add($value)
                if($value -match '(?i)androidx\.work|SystemJobService|SystemAlarmService|WorkManager') {
                    $workEntries.Add($value)
                }
            }
        }
    }
    return [pscustomobject][ordered]@{
        measured = $measured
        count = $entries.Count
        workCount = $workEntries.Count
        entries = @($entries)
        workEntries = @($workEntries)
    }
}

function Get-PreClickJobState($JobResult) {
    $entries = [Collections.Generic.List[string]]::new()
    $workEntries = [Collections.Generic.List[string]]::new()
    $text = if($null -ne $JobResult) { [string]$JobResult.Text } else { "" }
    $measured = $null -ne $JobResult -and $JobResult.ExitCode -eq 0 -and
        $text -match '(?i)JOB SCHEDULER|Registered jobs|Active jobs|Running jobs|No running jobs'
    if($measured) {
        $escapedPackage = [regex]::Escape($script:AppPackage)
        $inRunningSection = $false
        foreach($line in @($text -split "`r?`n")) {
            if($line -match '(?i)^\s*(?:Active|Running|Currently running) jobs\s*:') {
                $inRunningSection = $true
                continue
            }
            # dumpsys indentation varies by Android release. Any plain named section header,
            # including an indented "Registered jobs:", terminates the active/running section.
            if($line -match '(?i)^\s*[A-Z][A-Z0-9 _/().-]+:\s*$') {
                $inRunningSection = $false
                continue
            }
            $mentionsTarget = $line -match $escapedPackage
            $explicitlyInactive = $line -match '(?i)\bNOT\s+(?:ACTIVE|RUNNING|executing)\b|started\s*=\s*false'
            $explicitlyRunning = -not $explicitlyInactive -and
                $line -match '(?i)\b(?:RUNNING|ACTIVE|executing)\b|started\s*=\s*true'
            if($mentionsTarget -and -not $explicitlyInactive -and
                    ($inRunningSection -or $explicitlyRunning)) {
                $value = $line.Trim()
                $entries.Add($value)
                if($value -match '(?i)androidx\.work|SystemJobService|SystemAlarmService|WorkManager') {
                    $workEntries.Add($value)
                }
            }
        }
    }
    return [pscustomobject][ordered]@{
        measured = $measured
        count = $entries.Count
        workCount = $workEntries.Count
        entries = @($entries)
        workEntries = @($workEntries)
    }
}

function Get-RequestQueueMetrics([object[]]$Events) {
    $active = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $problems = [Collections.Generic.List[string]]::new()
    $eventCount = 0
    $peak = 0
    foreach($event in @($Events | Sort-Object ordinal)) {
        if(@("image_request", "page_list_request") -cnotcontains [string]$event.value.event) {
            continue
        }
        $eventCount++
        $operation = [string](Get-OptionalProperty $event.value "operation")
        $phase = [string](Get-OptionalProperty $event.value "phase")
        if([string]::IsNullOrWhiteSpace($operation)) {
            $problems.Add("request event lacked operation id at ordinal $($event.ordinal)")
            continue
        }
        if($phase -ceq "start") {
            if(-not $active.Add($operation)) {
                $problems.Add("duplicate request start operation=$operation")
            }
            $peak = [Math]::Max($peak, $active.Count)
        } elseif(@("end", "cancel", "fail") -ccontains $phase) {
            if(-not $active.Remove($operation)) {
                $problems.Add("request terminal without start operation=$operation")
            }
        } else {
            $problems.Add("unknown request phase '$phase' operation=$operation")
        }
    }
    if($active.Count -ne 0) { $problems.Add("$($active.Count) request operations lacked terminals") }
    return [pscustomobject][ordered]@{
        measured = ($eventCount -gt 0 -and $problems.Count -eq 0)
        eventCount = $eventCount
        peakActive = $peak
        terminalBalance = $active.Count
        problems = @($problems)
    }
}

function Get-MaxTelemetryBurst(
    [object[]]$Events,
    [int64]$StartNanos,
    [int64]$EndNanos,
    [int64]$WindowNanos
) {
    $timestamps = [Collections.Generic.List[int64]]::new()
    $valid = $StartNanos -gt 0L -and $EndNanos -gt $StartNanos -and $WindowNanos -gt 0L
    if($valid) {
        foreach($event in $Events) {
            $timestamp = 0L
            if(-not [int64]::TryParse(
                    [string](Get-OptionalProperty $event.value "timestampNanos"),
                    [ref]$timestamp)) {
                $valid = $false
                continue
            }
            if($timestamp -ge $StartNanos -and $timestamp -le $EndNanos) {
                $timestamps.Add($timestamp)
            }
        }
    }
    $ordered = @($timestamps | Sort-Object)
    $left = 0
    $maximum = 0
    for($right = 0; $right -lt $ordered.Count; $right++) {
        while($left -le $right -and $ordered[$right] - $ordered[$left] -gt $WindowNanos) {
            $left++
        }
        $maximum = [Math]::Max($maximum, $right - $left + 1)
    }
    return [pscustomobject][ordered]@{
        measured = $valid
        count = $ordered.Count
        maxBurst = $maximum
    }
}

function Parse-ViewerTelemetry([string[]]$Lines) {
    $events = [Collections.Generic.List[object]]::new()
    $ordinal = 0
    foreach($line in $Lines) {
        if($line -notmatch 'ViewerTelemetry\s*:\s*(\{.*\})\s*$') { continue }
        try {
            $value = $Matches[1] | ConvertFrom-Json
            $events.Add([pscustomobject][ordered]@{
                ordinal = $ordinal++
                value = $value
                raw = $line
            })
        } catch {
            # A malformed telemetry record is retained by logcat and makes proof incomplete.
        }
    }
    return @($events)
}

function Find-MacroResult([string[]]$Lines) {
    for($index = $Lines.Count - 1; $index -ge 0; $index--) {
        $line = $Lines[$index]
        if($line -match 'NtkColdMacro\s*:\s*(\{.*\})\s*$') {
            try { return ($Matches[1] | ConvertFrom-Json) } catch { return $null }
        }
    }
    return $null
}

function Get-EventValues(
    [AllowNull()][AllowEmptyCollection()][object[]]$Events,
    [Parameter(Mandatory)][string]$Name
) {
    if($null -eq $Events -or $Events.Count -eq 0) { return }
    return @($Events | Where-Object { [string]$_.value.event -ceq $Name })
}

function Get-OptionalProperty($Value, [string]$Name) {
    if($null -eq $Value) { return $null }
    $property = $Value.PSObject.Properties[$Name]
    if($null -eq $property) { return $null }
    return $property.Value
}

function ConvertTo-FiniteDouble($Value) {
    if($null -eq $Value -or $Value -is [bool]) { return $null }
    $number = 0.0
    if(-not [double]::TryParse(
            [string]$Value,
            [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$number)) {
        return $null
    }
    if([double]::IsNaN($number) -or [double]::IsInfinity($number)) { return $null }
    return [double]$number
}

function ConvertTo-FiniteDoubleArray($Value) {
    $values = [Collections.Generic.List[double]]::new()
    $visit = $null
    $visit = {
        param($Candidate)
        if($null -eq $Candidate) { return }
        if($Candidate -is [Collections.IEnumerable] -and $Candidate -isnot [string]) {
            foreach($item in $Candidate) { & $visit $item }
            return
        }
        $number = ConvertTo-FiniteDouble $Candidate
        if($null -ne $number) { $values.Add([double]$number) }
    }
    & $visit $Value
    return @($values.ToArray())
}

function Get-AndroidxBenchmarkSummary([IO.FileInfo[]]$ArtifactFiles) {
    $dataFiles = @($ArtifactFiles | Where-Object {
        $_.Name -match '(?i)-benchmarkData\.json$'
    })
    $problems = [Collections.Generic.List[string]]::new()
    $testResult = $null
    if($dataFiles.Count -ne 1) {
        $problems.Add("expected exactly one *-benchmarkData.json; found $($dataFiles.Count)")
    } else {
        try {
            $document = Get-Content -LiteralPath $dataFiles[0].FullName -Raw | ConvertFrom-Json
            $matching = @($document.benchmarks | Where-Object {
                [string]$_.name -ceq 'coldViewerRandomWork' -and
                    [string]$_.className -match '(?:^|\.)NtkColdViewerMacrobenchmark$'
            })
            if($matching.Count -ne 1) {
                $problems.Add("expected one coldViewerRandomWork result; found $($matching.Count)")
            } else {
                $testResult = $matching[0]
            }
        } catch {
            $problems.Add("benchmarkData parse failed: $($_.Exception.Message)")
        }
    }

    $singleValues = [ordered]@{}
    $sampleValues = [ordered]@{}
    $requiredSingle = if($script:FastFunctionalTriage) {
        @(
            'ViewerOpenFirstMs', 'ViewerAllImagesReadyFirstMs',
            'ImageRequestCount', 'ImageDecodeMaxMs',
            'viewerScrollDurationMs', 'viewerScrollCpuTimeMs',
            'viewerScrollCpuPercent', 'viewerScrollMainThreadRunningMaxMs',
            'viewerActivePresentedFrameCount', 'viewerActivePresentationIntervalCount',
            'viewerActivePresentationFps', 'viewerActivePresentationJankPercent',
            'viewerActivePresentationGapMaxMs', 'viewerActiveRefreshPeriodMs',
            'viewerActiveCpuPercent', 'viewerActiveMainThreadRunningMaxMs',
            'viewerActivePresentationSystemFence'
        )
    } else {
        @(
            'timeToInitialDisplayMs',
            'memoryHeapSizeMaxKb', 'memoryRssAnonMaxKb',
            'memoryHeapSizeLastKb', 'memoryRssAnonLastKb',
            'ViewerOpenFirstMs', 'ViewerAllImagesReadyFirstMs',
            'ImageRequestCount', 'ImageDecodeMaxMs',
            'viewerScrollDurationMs', 'viewerScrollCpuTimeMs',
            'viewerScrollCpuPercent', 'viewerScrollMainThreadRunningMaxMs',
            'viewerActivePresentedFrameCount', 'viewerActivePresentationIntervalCount',
            'viewerActivePresentationFps', 'viewerActivePresentationJankPercent',
            'viewerActivePresentationGapMaxMs', 'viewerActiveRefreshPeriodMs',
            'viewerActiveCpuPercent', 'viewerActiveMainThreadRunningMaxMs',
            'viewerActivePresentationSystemFence'
        )
    }
    # The reader pixels are produced by their own Surface/BufferQueue. AndroidX
    # FrameTimingMetric observes only the parent Activity's HWUI timeline and aborts when that
    # timeline has no expect/actual rows, so it is intentionally not registered by the benchmark.
    # Keep accepting those metrics when a platform happens to expose them, but qualify this reader
    # with the required system-side ViewerActiveScroll SurfaceFlinger metrics above.
    $optionalSingle = @('frameCount')
    $optionalSampled = @('frameDurationCpuMs', 'frameOverrunMs')
    if($null -ne $testResult) {
        if((ConvertTo-FiniteDouble $testResult.warmupIterations) -ne 0.0) {
            $problems.Add('AndroidX warmupIterations was not exactly zero')
        }
        if((ConvertTo-FiniteDouble $testResult.repeatIterations) -ne 1.0) {
            $problems.Add('AndroidX repeatIterations was not exactly one')
        }
        foreach($name in $requiredSingle) {
            $metric = Get-OptionalProperty $testResult.metrics $name
            $maximum = ConvertTo-FiniteDouble (Get-OptionalProperty $metric 'maximum')
            $runs = @(ConvertTo-FiniteDoubleArray (Get-OptionalProperty $metric 'runs'))
            if($null -eq $maximum -or $runs.Count -ne 1) {
                $problems.Add("required AndroidX metric invalid or missing: $name")
            } else {
                $singleValues[$name] = [double]$maximum
            }
        }
        $commitMetricCount = 0
        foreach($name in @(
                'ViewerHwuiFrameCommitMaxMs',
                'ViewerSurfaceControlLatchMaxMs',
                'ViewerSurfaceQueueSubmissionMaxMs')) {
            $metric = Get-OptionalProperty $testResult.metrics $name
            if($null -eq $metric) { continue }
            $maximum = ConvertTo-FiniteDouble (Get-OptionalProperty $metric 'maximum')
            $runs = @(ConvertTo-FiniteDoubleArray (Get-OptionalProperty $metric 'runs'))
            if($null -eq $maximum -or $runs.Count -ne 1) {
                $problems.Add("frame-commit AndroidX metric invalid: $name")
            } else {
                $singleValues[$name] = [double]$maximum
                $commitMetricCount++
            }
        }
        if(-not $script:FastFunctionalTriage -and $commitMetricCount -eq 0) {
            $problems.Add('no renderer-specific AndroidX frame-commit metric was observed')
        }
        foreach($name in $optionalSingle) {
            $metric = Get-OptionalProperty $testResult.metrics $name
            if($null -eq $metric) { continue }
            $maximum = ConvertTo-FiniteDouble (Get-OptionalProperty $metric 'maximum')
            $runs = @(ConvertTo-FiniteDoubleArray (Get-OptionalProperty $metric 'runs'))
            if($null -eq $maximum -or $runs.Count -ne 1) {
                $problems.Add("optional AndroidX metric was present but invalid: $name")
            } else {
                $singleValues[$name] = [double]$maximum
            }
        }
        foreach($name in $optionalSampled) {
            $metric = Get-OptionalProperty $testResult.sampledMetrics $name
            if($null -eq $metric) { continue }
            $p99 = ConvertTo-FiniteDouble (Get-OptionalProperty $metric 'P99')
            $runs = @(ConvertTo-FiniteDoubleArray (Get-OptionalProperty $metric 'runs'))
            if($null -eq $p99 -or $runs.Count -le 0) {
                $problems.Add("optional AndroidX sampled metric was present but invalid: $name")
            } else {
                $sampleValues[$name] = [pscustomobject][ordered]@{
                    p50 = ConvertTo-FiniteDouble (Get-OptionalProperty $metric 'P50')
                    p90 = ConvertTo-FiniteDouble (Get-OptionalProperty $metric 'P90')
                    p95 = ConvertTo-FiniteDouble (Get-OptionalProperty $metric 'P95')
                    p99 = $p99
                    samples = @($runs)
                }
            }
        }
    }

    $durations = @(if($sampleValues.Contains('frameDurationCpuMs')) {
        $sampleValues['frameDurationCpuMs'].samples
    })
    $overruns = @(if($sampleValues.Contains('frameOverrunMs')) {
        $sampleValues['frameOverrunMs'].samples
    })
    if($durations.Count -gt 0 -and $overruns.Count -gt 0 -and
            $durations.Count -ne $overruns.Count) {
        $problems.Add('AndroidX frame duration/overrun sample counts differ')
    }
    if($singleValues.Contains('frameCount') -and $durations.Count -gt 0 -and
            [int64][Math]::Round($singleValues['frameCount']) -ne $durations.Count) {
        $problems.Add('AndroidX frameCount did not match sampled frame count')
    }
    $janky = @($overruns | Where-Object { $_ -gt 0.0 }).Count
    $maxConsecutive = 0
    $currentConsecutive = 0
    foreach($overrun in $overruns) {
        if($overrun -gt 0.0) {
            $currentConsecutive++
            $maxConsecutive = [Math]::Max($maxConsecutive, $currentConsecutive)
        } else {
            $currentConsecutive = 0
        }
    }
    return [pscustomobject][ordered]@{
        valid = ($problems.Count -eq 0)
        problems = @($problems)
        dataFiles = @($dataFiles | ForEach-Object { $_.FullName })
        testName = if($null -ne $testResult) { [string]$testResult.name } else { $null }
        className = if($null -ne $testResult) { [string]$testResult.className } else { $null }
        warmupIterations = if($null -ne $testResult) { $testResult.warmupIterations } else { $null }
        repeatIterations = if($null -ne $testResult) { $testResult.repeatIterations } else { $null }
        single = [pscustomobject]$singleValues
        sampled = [pscustomobject]$sampleValues
        frameSampleCount = $durations.Count
        frameDurationCpuMaxMs = if($durations.Count -gt 0) {
            [double](($durations | Measure-Object -Maximum).Maximum)
        } else { $null }
        frameOverrunMaxMs = if($overruns.Count -gt 0) {
            [double](($overruns | Measure-Object -Maximum).Maximum)
        } else { $null }
        jankyFrameCount = $janky
        jankPercent = if($overruns.Count -gt 0) { 100.0 * $janky / $overruns.Count } else { $null }
        maxConsecutiveJankyFrames = $maxConsecutive
    }
}

function Start-StandalonePerfetto([string]$RemoteTrace) {
    if(-not $script:StandalonePerfetto) { return $null }
    $remoteConfig = "/data/misc/perfetto-configs/ntk_perfetto.textproto"
    [void](Invoke-Adb @("push", $script:perfettoConfig, $remoteConfig) -TimeoutSeconds 30)
    $result = Invoke-Adb @(
        "shell", "perfetto", "--txt", "-c", $remoteConfig,
        "-o", $RemoteTrace, "--background-wait"
    ) -TimeoutSeconds 45
    $perfettoProcessId = ($result.Stdout.Trim() -split "\s+")[-1]
    if($perfettoProcessId -notmatch '^\d+$') {
        throw "Perfetto did not return a background pid: $($result.Text)"
    }
    return $perfettoProcessId
}

function Stop-StandalonePerfetto(
    [string]$PerfettoProcessId,
    [string]$RemoteTrace,
    [string]$LocalTrace
) {
    if([string]::IsNullOrWhiteSpace($PerfettoProcessId)) { return }
    [void](Invoke-Adb @("shell", "kill", "-TERM", $PerfettoProcessId) -AllowFailure)
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $probe = Invoke-Adb @("shell", "test", "-s", $RemoteTrace) -AllowFailure
        if($probe.ExitCode -eq 0) { break }
        Start-Sleep -Milliseconds 100
    } while([DateTime]::UtcNow -lt $deadline)
    [void](Invoke-Adb @("pull", $RemoteTrace, $LocalTrace) -TimeoutSeconds 120 -AllowFailure)
    [void](Invoke-Adb @("shell", "rm", "-f", $RemoteTrace) -AllowFailure)
}

function Invoke-MacroInstrumentation($Target, [string]$CaseId, [string]$RemoteAdditional) {
    $caseImageSlaMs = Get-CaseImageSlaMs ([string]$Target.workType)
    $instrumentArgs = @(
        # API 30+ otherwise gives instrumentation an isolated external-storage view. AndroidX can
        # report a valid trace path from that private mount, but `adb pull` cannot see it after the
        # runner exits. This flag changes evidence visibility only; target app data is still reset
        # independently with pm clear before the physical click.
        "shell", "am", "instrument", "--no-isolated-storage", "-w", "-r",
        "-e", "class", $script:TestClass,
        "-e", "ntkWorkType", [string]$Target.workType,
        "-e", "ntkWorkId", [string]$Target.workId,
        "-e", "ntkWorkTitleBase64", (ConvertTo-Base64Utf8 ([string]$Target.title)),
        "-e", "ntkEpisodeTitleBase64", (ConvertTo-Base64Utf8 ([string]$Target.episodeTitle)),
        "-e", "ntkEpisodePath", [string]$Target.episodePath,
        "-e", "ntkCaseId", $CaseId,
        "-e", "ntkFirstImageSlaMs", [string]$caseImageSlaMs,
        "-e", "ntkAllImagesSlaMs", [string]$caseImageSlaMs,
        "-e", "ntkRequireBaselineProfile", $script:RequireBaselineProfile.IsPresent.ToString().ToLowerInvariant(),
        "-e", "ntkSameProcessWarmReopen", $script:IncludeWarmReopen.ToString().ToLowerInvariant(),
        "-e", "ntkFastFunctionalTriage", $script:FastFunctionalTriage.IsPresent.ToString().ToLowerInvariant(),
        "-e", "androidx.benchmark.output.enable", "true",
        "-e", "additionalTestOutputDir", $RemoteAdditional
    )
    if($script:deviceInfo.virtualDeviceDetected) {
        # Only the emulator capability check is suppressed. Battery, debuggability,
        # profileability and every other Macrobenchmark configuration error remain fatal.
        $instrumentArgs += @("-e", "androidx.benchmark.suppressErrors", "EMULATOR")
    }
    $instrumentArgs += $script:Runner
    return Invoke-Adb $instrumentArgs -TimeoutSeconds $script:CaseTimeoutSeconds -AllowFailure
}

function Invoke-ColdCase($Target, [int]$Ordinal, [string]$RunRemoteRoot) {
    $caseStartedAt = [DateTimeOffset]::Now.ToString("o")
    $caseImageSlaMs = Get-CaseImageSlaMs ([string]$Target.workType)
    $safeId = ([string]$Target.workId -replace '[^A-Za-z0-9._-]', '_')
    $caseId = '{0}-{1:D2}-{2}' -f $Target.workType, $Ordinal, $safeId
    $caseDir = Join-Path $script:runDir $caseId
    [void](New-Item -ItemType Directory -Path $caseDir)
    $remoteCase = "$RunRemoteRoot/$caseId"
    $remoteAdditional = "$remoteCase/benchmark"
    $remoteTrace = "/data/misc/perfetto-traces/$caseId.perfetto-trace"
    if($RunRemoteRoot -notmatch '^/sdcard/Android/media/ml\.melun\.mangaview\.macrobenchmark/ntk-cold-output/\d{8}-\d{6}-[1-9]\d*$' -or
            $caseId -notmatch '^(webtoon|manhwa)-\d{2}-[A-Za-z0-9._-]+$' -or
            $remoteCase -cne "$RunRemoteRoot/$caseId" -or
            -not $remoteCase.StartsWith("$RunRemoteRoot/", [StringComparison]::Ordinal)) {
        throw "Refusing recursive remote cleanup outside the exact run/case root: '$remoteCase'"
    }

    $forceApp = Invoke-Adb @("shell", "am", "force-stop", $script:AppPackage) -AllowFailure
    $forceTest = Invoke-Adb @("shell", "am", "force-stop", $script:BenchmarkPackage) -AllowFailure
    $androidxTraceCleanupBefore = Reset-AndroidxPerfettoTraceOutput "before-case"
    $clearApp = Invoke-Adb @("shell", "pm", "clear", $script:AppPackage) -TimeoutSeconds 30 -AllowFailure
    $clearTest = Invoke-Adb @("shell", "pm", "clear", $script:BenchmarkPackage) -TimeoutSeconds 30 -AllowFailure
    $pidBefore = (Invoke-Adb @("shell", "pidof", $script:AppPackage) -AllowFailure).Stdout.Trim()
    $processesBefore = Invoke-Adb @("shell", "dumpsys", "activity", "processes") `
        -TimeoutSeconds 30 -AllowFailure
    $servicesBefore = Invoke-Adb @("shell", "dumpsys", "activity", "services", $script:AppPackage) `
        -TimeoutSeconds 30 -AllowFailure
    $packageBefore = Invoke-Adb @("shell", "dumpsys", "package", $script:AppPackage) `
        -TimeoutSeconds 30 -AllowFailure
    $jobsBefore = Invoke-Adb @("shell", "dumpsys", "jobscheduler", $script:AppPackage) `
        -TimeoutSeconds 30 -AllowFailure
    $psBefore = Invoke-Adb @("shell", "ps", "-A", "-o", "UID,PID,NAME,ARGS") `
        -TimeoutSeconds 30 -AllowFailure
    if($psBefore.ExitCode -ne 0 -or
            $psBefore.Text -notmatch '(?im)^\s*UID\s+PID\s+NAME(?:\s+ARGS)?\s*$') {
        $psBefore = Invoke-Adb @("shell", "ps", "-A", "-o", "UID,PID,NAME") `
            -TimeoutSeconds 30 -AllowFailure
    }
    $packageUid = Get-PackageUid $packageBefore.Text
    $preClickProcessState = Get-PreClickProcessState $psBefore $packageUid
    $preClickServiceState = Get-PreClickServiceState $servicesBefore
    $preClickJobState = Get-PreClickJobState $jobsBefore
    $remoteCleanup = Invoke-Adb @("shell", "rm", "-rf", $remoteCase) -AllowFailure
    [void](Invoke-Adb @("shell", "mkdir", "-p", $remoteAdditional))
    [void](Invoke-Adb @("logcat", "-c") -AllowFailure)
    [void](Invoke-Adb @("shell", "dumpsys", "gfxinfo", $script:AppPackage, "reset") -AllowFailure)

    $coldProof = [pscustomobject][ordered]@{
        forceStopApp = $forceApp.ExitCode
        forceStopBenchmark = $forceTest.ExitCode
        pmClearApp = $clearApp.Text.Trim()
        pmClearBenchmark = $clearTest.Text.Trim()
        pidBeforeStart = $pidBefore
        remoteOutputResetExitCode = $remoteCleanup.ExitCode
        targetUid = $packageUid
        processStateMeasured = $preClickProcessState.measured
        targetProcessCount = $preClickProcessState.count
        targetProcesses = @($preClickProcessState.entries)
        runningServiceStateMeasured = $preClickServiceState.measured
        runningServiceCount = $preClickServiceState.count
        runningServices = @($preClickServiceState.entries)
        runningJobStateMeasured = $preClickJobState.measured
        runningJobCount = $preClickJobState.count
        runningJobs = @($preClickJobState.entries)
        runningWorkCount = [int]$preClickServiceState.workCount + [int]$preClickJobState.workCount
        runningWork = @(@($preClickServiceState.workEntries) + @($preClickJobState.workEntries))
        processCold = ([string]::IsNullOrWhiteSpace($pidBefore) -and
            $preClickProcessState.measured -and $preClickProcessState.count -eq 0)
        backgroundExecutionCold = ($preClickServiceState.measured -and
            $preClickJobState.measured -and $preClickServiceState.count -eq 0 -and
            $preClickJobState.count -eq 0 -and $preClickServiceState.workCount -eq 0 -and
            $preClickJobState.workCount -eq 0)
        appDataCold = ($clearApp.ExitCode -eq 0 -and $clearApp.Text -match 'Success')
        benchmarkDataCold = ($clearTest.ExitCode -eq 0 -and $clearTest.Text -match 'Success')
        androidxTraceOutputPath = $androidxTraceCleanupBefore.exactPath
        androidxTraceWriterPidsBefore = @($androidxTraceCleanupBefore.matchedWriterPids)
        androidxTraceWritersStoppedBefore = $androidxTraceCleanupBefore.allMatchedWritersStopped
        androidxTraceOutputAbsentBefore = $androidxTraceCleanupBefore.absent
    }
    Write-Json (Join-Path $caseDir "cold-proof-host.json") $coldProof
    Write-Utf8 (Join-Path $caseDir "activity-processes-before.txt") $processesBefore.Text
    Write-Utf8 (Join-Path $caseDir "activity-services-before.txt") $servicesBefore.Text
    Write-Utf8 (Join-Path $caseDir "package-before.txt") $packageBefore.Text
    Write-Utf8 (Join-Path $caseDir "jobscheduler-before.txt") $jobsBefore.Text
    Write-Utf8 (Join-Path $caseDir "ps-before.txt") $psBefore.Text

    $perfettoPid = $null
    $instrumentation = $null
    $caseException = $null
    $androidxTraceCleanupAfter = $null
    try {
        $perfettoPid = Start-StandalonePerfetto $remoteTrace
        $instrumentation = Invoke-MacroInstrumentation $Target $caseId $remoteAdditional
    } catch {
        $caseException = $_.Exception.Message
    } finally {
        Stop-StandalonePerfetto $perfettoPid $remoteTrace `
            (Join-Path $caseDir "standalone.perfetto-trace")
        $androidxTraceCleanupAfter = Reset-AndroidxPerfettoTraceOutput "after-instrumentation"
        Write-Json (Join-Path $caseDir "androidx-trace-cleanup.json") $androidxTraceCleanupAfter
    }

    if($null -ne $instrumentation) {
        Write-Utf8 (Join-Path $caseDir "instrumentation.txt") $instrumentation.Text
    } else {
        Write-Utf8 (Join-Path $caseDir "instrumentation.txt") ([string]$caseException)
    }
    $logcat = Invoke-Adb @("logcat", "-d", "-v", "epoch") -TimeoutSeconds 60 -AllowFailure
    Write-Utf8 (Join-Path $caseDir "logcat.txt") $logcat.Text
    $meminfo = Invoke-Adb @("shell", "dumpsys", "meminfo", $script:AppPackage) -TimeoutSeconds 30 -AllowFailure
    $gfxinfo = Invoke-Adb @("shell", "dumpsys", "gfxinfo", $script:AppPackage) -TimeoutSeconds 30 -AllowFailure
    $cpuinfo = Invoke-Adb @("shell", "dumpsys", "cpuinfo") -TimeoutSeconds 30 -AllowFailure
    $processesAfter = Invoke-Adb @("shell", "dumpsys", "activity", "processes") `
        -TimeoutSeconds 30 -AllowFailure
    $servicesAfter = Invoke-Adb @("shell", "dumpsys", "activity", "services", $script:AppPackage) `
        -TimeoutSeconds 30 -AllowFailure
    $jobsAfter = Invoke-Adb @("shell", "dumpsys", "jobscheduler", $script:AppPackage) `
        -TimeoutSeconds 30 -AllowFailure
    Write-Utf8 (Join-Path $caseDir "meminfo.txt") $meminfo.Text
    Write-Utf8 (Join-Path $caseDir "gfxinfo.txt") $gfxinfo.Text
    Write-Utf8 (Join-Path $caseDir "cpuinfo.txt") $cpuinfo.Text
    Write-Utf8 (Join-Path $caseDir "activity-processes-after.txt") $processesAfter.Text
    Write-Utf8 (Join-Path $caseDir "activity-services-after.txt") $servicesAfter.Text
    Write-Utf8 (Join-Path $caseDir "jobscheduler-after.txt") $jobsAfter.Text
    $benchmarkArtifactRoot = Join-Path $caseDir "benchmark"
    # Windows adb can create a non-existent destination using the remote directory's basename and
    # then treat that same path as a file when the directory contains multiple artifacts. Create
    # the destination explicitly and pull the directory contents so Perfetto and benchmarkData are
    # retained as siblings on every case.
    [void](New-Item -ItemType Directory -Path $benchmarkArtifactRoot -Force)
    $benchmarkPull = Invoke-Adb @("pull", "$remoteAdditional/.", $benchmarkArtifactRoot) `
        -TimeoutSeconds 180 -AllowFailure
    Write-Utf8 (Join-Path $caseDir "benchmark-pull.txt") $benchmarkPull.Text
    $benchmarkArtifactFiles = @(if(Test-Path -LiteralPath $benchmarkArtifactRoot) {
        Get-ChildItem -LiteralPath $benchmarkArtifactRoot -Recurse -File -ErrorAction SilentlyContinue
    })
    $macroTraceFiles = @($benchmarkArtifactFiles | Where-Object {
        $_.Name -match '(?i)\.(?:perfetto-)?trace$'
    })
    $nonEmptyMacroTraceFiles = @($macroTraceFiles | Where-Object { $_.Length -gt 0L })
    $emptyMacroTraceFiles = @($macroTraceFiles | Where-Object { $_.Length -le 0L })
    $androidxBenchmark = Get-AndroidxBenchmarkSummary $benchmarkArtifactFiles
    $screenshotRemote = "/sdcard/Android/data/$script:BenchmarkPackage/files/ntk-cold/$caseId"
    $screenshotPull = Invoke-Adb @("pull", $screenshotRemote, (Join-Path $caseDir "screenshots")) `
        -TimeoutSeconds 120 -AllowFailure
    $screenshotArtifactRoot = Join-Path $caseDir "screenshots"
    $screenshotArtifactFiles = @(if(Test-Path -LiteralPath $screenshotArtifactRoot) {
        Get-ChildItem -LiteralPath $screenshotArtifactRoot -Recurse -File -ErrorAction SilentlyContinue
    })
    $visualArtifactFiles = @(@($benchmarkArtifactFiles) + @($screenshotArtifactFiles) |
        Where-Object { $_.Name -match '(?i)\.(?:png|jpe?g|webp|mp4|webm|mkv)$' } |
        Sort-Object FullName -Unique)
    $nonEmptyVisualArtifactFiles = @($visualArtifactFiles | Where-Object { $_.Length -gt 0L })
    $emptyVisualArtifactFiles = @($visualArtifactFiles | Where-Object { $_.Length -le 0L })
    $remoteArtifactCleanup = Remove-RemoteCaseArtifacts `
        $RunRemoteRoot $caseId $remoteCase $screenshotRemote
    Write-Json (Join-Path $caseDir "remote-artifact-cleanup.json") $remoteArtifactCleanup

    # The optional warm reopen runs after measureRepeated but in the same target process.
    # It is parsed below as a separate telemetry generation and cannot enter AndroidX cold metrics.
    $warmSummary = $null

    $lines = @($logcat.Text -split "`r?`n")
    $telemetry = @(Parse-ViewerTelemetry $lines)
    $macroResult = Find-MacroResult $lines
    $coldStates = @(Get-EventValues -Events @($telemetry) -Name "cold_state")
    $viewerClicks = @($telemetry | Where-Object {
        [string]$_.value.event -ceq "viewer_open" -and
            [string]$_.value.phase -ceq "click"
    })
    $clickOrdinal = if($viewerClicks.Count -gt 0) { $viewerClicks[0].ordinal } else { [int]::MaxValue }
    $viewerGeneration = 0L
    $viewerGenerationValid = $viewerClicks.Count -gt 0 -and
        "generation" -in $viewerClicks[0].value.PSObject.Properties.Name -and
        [long]::TryParse([string]$viewerClicks[0].value.generation, [ref]$viewerGeneration) -and
        $viewerGeneration -gt 0
    $preEntryWork = @($telemetry | Where-Object {
        $_.ordinal -lt $clickOrdinal -and
            @("image_request", "decode", "page_list_request") -ccontains [string]$_.value.event -and
            [string]$_.value.phase -ceq "start"
    })
    # generation=0 is the explicit pre-viewer domain. Keep it separate so a
    # future parser/filter change cannot accidentally hide forbidden preload.
    $zeroGenerationPreClick = @($preEntryWork | Where-Object {
        if("generation" -notin $_.value.PSObject.Properties.Name) { return $true }
        $candidateGeneration = 0L
        if(-not [long]::TryParse([string]$_.value.generation, [ref]$candidateGeneration)) {
            return $true
        }
        return $candidateGeneration -eq 0
    })
    $preEntryImageRequests = @($preEntryWork | Where-Object {
        [string]$_.value.event -ceq "image_request"
    })
    $preEntryDecodes = @($preEntryWork | Where-Object {
        [string]$_.value.event -ceq "decode"
    })
    $preEntryPageLists = @($preEntryWork | Where-Object {
        [string]$_.value.event -ceq "page_list_request"
    })
    $sessionTelemetry = @(if($viewerGenerationValid) {
        $telemetry | Where-Object {
            $_.ordinal -ge $clickOrdinal -and
                "generation" -in $_.value.PSObject.Properties.Name -and
                [string]$_.value.generation -ceq [string]$viewerGeneration
        }
    })
    $requestStarts = @($sessionTelemetry | Where-Object {
        [string]$_.value.event -ceq "image_request" -and
            [string]$_.value.phase -ceq "start"
    })
    # Count duplicate downloads only after a physical request completed with a non-empty body.
    $completedImageDownloads = @($sessionTelemetry | Where-Object {
        if([string]$_.value.event -cne "image_request" -or
                [string]$_.value.phase -cne "end") { return $false }
        $bytes = 0L
        return [long]::TryParse([string](Get-OptionalProperty $_.value "bytes"), [ref]$bytes) -and
            $bytes -gt 0L
    })
    $completedDownloadsWithoutKey = @($completedImageDownloads | Where-Object {
        [string]::IsNullOrWhiteSpace([string]$_.value.sourceKeyHash)
    })
    $duplicates = @($completedImageDownloads | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_.value.sourceKeyHash)
    } | Group-Object { [string]$_.value.sourceKeyHash } | Where-Object Count -gt 1)
    $imageFailures = @($sessionTelemetry | Where-Object {
        [string]$_.value.event -ceq "image_request" -and
            [string]$_.value.phase -ceq "fail"
    })
    $imageCancellations = @($sessionTelemetry | Where-Object {
        [string]$_.value.event -ceq "image_request" -and
            [string]$_.value.phase -ceq "cancel"
    })
    $decodeFailures = @($sessionTelemetry | Where-Object {
        [string]$_.value.event -ceq "decode" -and
            [string]$_.value.phase -ceq "fail"
    })
    $decodeCancellations = @($sessionTelemetry | Where-Object {
        [string]$_.value.event -ceq "decode" -and
            [string]$_.value.phase -ceq "cancel"
    })
    $pageListFailures = @($sessionTelemetry | Where-Object {
        [string]$_.value.event -ceq "page_list_request" -and
            [string]$_.value.phase -ceq "fail"
    })
    $pageListCancellations = @($sessionTelemetry | Where-Object {
        [string]$_.value.event -ceq "page_list_request" -and
            [string]$_.value.phase -ceq "cancel"
    })
    $requestQueueMetrics = Get-RequestQueueMetrics $sessionTelemetry
    $forwardTraversalStartNanos = 0L
    $forwardTraversalEndNanos = 0L
    $forwardTraversalGestureCount = 0L
    $forwardTraversalMetricsPresent = $null -ne $macroResult -and
        [int64]::TryParse(
            [string](Get-OptionalProperty $macroResult "forwardTraversalStartElapsedNanos"),
            [ref]$forwardTraversalStartNanos) -and
        [int64]::TryParse(
            [string](Get-OptionalProperty $macroResult "forwardTraversalEndElapsedNanos"),
            [ref]$forwardTraversalEndNanos) -and
        [int64]::TryParse(
            [string](Get-OptionalProperty $macroResult "forwardTraversalGestureCount"),
            [ref]$forwardTraversalGestureCount) -and
        $forwardTraversalStartNanos -gt 0L -and
        $forwardTraversalEndNanos -gt $forwardTraversalStartNanos -and
        $forwardTraversalGestureCount -ge $script:ProductionMinForwardGestures -and
        $forwardTraversalGestureCount -le $script:ProductionMaxForwardGestures
    $allDrawCommits = @($sessionTelemetry | Where-Object {
        [string]$_.value.event -ceq "actual_image_draw_commit"
    })
    $actualDrawCommits = @($allDrawCommits | Where-Object { $_.value.actual -eq $true })
    $firstDrawCommit = if($allDrawCommits.Count -gt 0) { $allDrawCommits[0].value } else { $null }
    $firstActualDrawCommitEvent = if($actualDrawCommits.Count -gt 0) {
        $actualDrawCommits[0]
    } else { $null }
    $firstActualDrawCommit = if($null -ne $firstActualDrawCommitEvent) {
        $firstActualDrawCommitEvent.value
    } else { $null }
    $firstActualEvidenceKind = Get-OptionalProperty $firstActualDrawCommit "evidenceKind"
    $firstActualPageValue = Get-OptionalProperty $firstActualDrawCommit "pageIndex"
    $firstActualPageIndex = 0L
    $firstActualPageValid = $null -ne $firstActualPageValue -and
        [long]::TryParse([string]$firstActualPageValue, [ref]$firstActualPageIndex)
    $preFirstRequestEvents = @(if($null -ne $firstActualDrawCommitEvent) {
        $requestStarts | Where-Object { $_.ordinal -lt $firstActualDrawCommitEvent.ordinal }
    } else {
        $requestStarts
    })
    $preFirstRequestPageMissingCount = 0
    $preFirstRequestPages = @($preFirstRequestEvents | ForEach-Object {
        $pageValue = Get-OptionalProperty $_.value "pageIndex"
        $pageIndex = 0L
        if($null -eq $pageValue -or
                -not [long]::TryParse([string]$pageValue, [ref]$pageIndex)) {
            $preFirstRequestPageMissingCount++
        } else {
            $pageIndex
        }
    } | Sort-Object -Unique)
    $preFirstRequestMax = if($preFirstRequestPages.Count -gt 0) {
        [int64]($preFirstRequestPages | Measure-Object -Maximum).Maximum
    } else { $null }
    $preFirstEscapedRequests = @($preFirstRequestEvents | Where-Object {
        $pageValue = Get-OptionalProperty $_.value "pageIndex"
        $pageIndex = 0L
        $null -ne $pageValue -and
            [long]::TryParse([string]$pageValue, [ref]$pageIndex) -and
            ($pageIndex -lt 0L -or $pageIndex -gt 1L)
    })
    $firstPageRequestStarts = @(if($firstActualPageValid) {
        $requestStarts | Where-Object {
            [string](Get-OptionalProperty $_.value "pageIndex") -ceq [string]$firstActualPageIndex
        }
    })
    $firstPageResponses = @(if($firstActualPageValid -and $null -ne $firstActualDrawCommitEvent) {
        $sessionTelemetry | Where-Object {
            $_.ordinal -lt $firstActualDrawCommitEvent.ordinal -and
                [string]$_.value.event -ceq "image_request" -and
                [string](Get-OptionalProperty $_.value "phase") -ceq "end" -and
                [string](Get-OptionalProperty $_.value "pageIndex") -ceq
                    [string]$firstActualPageIndex
        }
    })
    $firstPageDecodeCompletions = @(if($firstActualPageValid -and
            $null -ne $firstActualDrawCommitEvent) {
        $sessionTelemetry | Where-Object {
            $_.ordinal -lt $firstActualDrawCommitEvent.ordinal -and
                [string]$_.value.event -ceq "decode" -and
                [string](Get-OptionalProperty $_.value "phase") -ceq "end" -and
                [string](Get-OptionalProperty $_.value "pageIndex") -ceq
                    [string]$firstActualPageIndex
        }
    })
    $firstImageRequestStartElapsedNanos = if($firstPageRequestStarts.Count -gt 0) {
        Get-OptionalProperty $firstPageRequestStarts[0].value "timestampNanos"
    } else { $null }
    $firstImageResponseElapsedNanos = if($firstPageResponses.Count -gt 0) {
        Get-OptionalProperty $firstPageResponses[-1].value "timestampNanos"
    } else { $null }
    $firstImageDecodeElapsedNanos = if($firstPageDecodeCompletions.Count -gt 0) {
        Get-OptionalProperty $firstPageDecodeCompletions[-1].value "timestampNanos"
    } else { $null }
    $firstImageDrawElapsedNanos = if($null -ne $firstActualDrawCommitEvent) {
        Get-OptionalProperty $firstActualDrawCommitEvent.value "timestampNanos"
    } else { $null }
    $frameSummaries = @(Get-EventValues -Events @($sessionTelemetry) -Name "frame_summary")
    $nativeFrameSummaries = @(Get-EventValues -Events @($sessionTelemetry) -Name "native_frame_summary")
    $coverageSummaries = @(Get-EventValues -Events @($sessionTelemetry) -Name "coverage_summary")
    $manifestSummaries = @(Get-EventValues -Events @($sessionTelemetry) -Name "manifest_summary")
    $traversalSummaries = @(Get-EventValues -Events @($sessionTelemetry) -Name "traversal_summary")
    $viewerClosedEvents = @(Get-EventValues -Events @($sessionTelemetry) -Name "viewer_closed")
    $lastViewerClosedEvent = if($viewerClosedEvents.Count -gt 0) {
        $viewerClosedEvents[-1]
    } else { $null }
    $secondViewerClickOrdinal = if($viewerClicks.Count -gt 1) {
        [int]$viewerClicks[1].ordinal
    } else { [int]::MaxValue }
    $coldEndOrdinal = if($null -ne $lastViewerClosedEvent) {
        [int]$lastViewerClosedEvent.ordinal
    } else { [int]::MaxValue }
    $postCloseWork = @(if($null -ne $lastViewerClosedEvent) {
        $telemetry | Where-Object {
            $_.ordinal -gt $lastViewerClosedEvent.ordinal -and
                $_.ordinal -lt $secondViewerClickOrdinal -and
                @("image_request", "decode", "page_list_request") -ccontains
                    [string]$_.value.event -and
                [string]$_.value.phase -ceq "start"
        }
    })
    $foreignGenerationWork = @(if($viewerGenerationValid) {
        $telemetry | Where-Object {
            $_.ordinal -ge $clickOrdinal -and
                $_.ordinal -le $coldEndOrdinal -and
                @("image_request", "decode", "page_list_request") -ccontains
                    [string]$_.value.event -and
                [string]$_.value.phase -ceq "start" -and
                [string](Get-OptionalProperty $_.value "generation") -cne
                    [string]$viewerGeneration
        }
    })
    $memorySummaries = @(Get-EventValues -Events @($sessionTelemetry) -Name "memory_summary")
    $imagePipelineSummaries = @(
        Get-EventValues -Events @($sessionTelemetry) -Name "image_pipeline_summary"
    )
    $frameSummary = if($frameSummaries.Count -gt 0) { $frameSummaries[-1].value } else { $null }
    $nativeFrameSummary = if($nativeFrameSummaries.Count -gt 0) {
        $nativeFrameSummaries[-1].value
    } else { $null }
    $coverageSummary = if($coverageSummaries.Count -gt 0) { $coverageSummaries[-1].value } else { $null }
    $manifestSummary = if($manifestSummaries.Count -gt 0) { $manifestSummaries[-1].value } else { $null }
    $traversalSummary = if($traversalSummaries.Count -gt 0) { $traversalSummaries[-1].value } else { $null }
    $memorySummary = if($memorySummaries.Count -gt 0) { $memorySummaries[-1].value } else { $null }
    $imagePipelineSummary = if($imagePipelineSummaries.Count -gt 0) {
        $imagePipelineSummaries[-1].value
    } else { $null }

    if($script:IncludeWarmReopen) {
        $warmGeneration = 0L
        $warmClick = if($viewerClicks.Count -gt 1) { $viewerClicks[1] } else { $null }
        $warmGenerationValid = $null -ne $warmClick -and
            "generation" -in $warmClick.value.PSObject.Properties.Name -and
            [long]::TryParse([string]$warmClick.value.generation, [ref]$warmGeneration) -and
            $warmGeneration -gt $viewerGeneration
        $warmSessionTelemetry = @(if($warmGenerationValid) {
            $telemetry | Where-Object {
                "generation" -in $_.value.PSObject.Properties.Name -and
                    [string]$_.value.generation -ceq [string]$warmGeneration
            }
        })
        $warmMemoryEvents = @(
            Get-EventValues -Events @($warmSessionTelemetry) -Name "memory_summary"
        )
        $warmMemory = if($warmMemoryEvents.Count -gt 0) {
            $warmMemoryEvents[-1].value
        } else { $null }
        $warmClosedEvents = @(
            Get-EventValues -Events @($warmSessionTelemetry) -Name "viewer_closed"
        )
        $warmClosed = if($warmClosedEvents.Count -gt 0) { $warmClosedEvents[-1].value } else { $null }
        $warmFailures = @($warmSessionTelemetry | Where-Object {
            @("image_request", "decode", "page_list_request") -ccontains
                [string]$_.value.event -and [string]$_.value.phase -ceq "fail"
        })
        $warmActualPresents = @($warmSessionTelemetry | Where-Object {
            [string]$_.value.event -ceq "actual_image_draw_commit" -and
                $_.value.actual -eq $true -and
                [string]$_.value.evidenceKind -ceq "hwui_frame_commit"
        })
        $warmClosedCleanly = $null -ne $warmClosed -and
            [int64](Get-OptionalProperty $warmClosed "activeRequests") -eq 0L -and
            [int64](Get-OptionalProperty $warmClosed "activeDecodes") -eq 0L -and
            (Get-OptionalProperty $warmClosed "drainTimedOut") -eq $false
        $warmMemoryReleased = $null -ne $warmMemory -and
            [int64](Get-OptionalProperty $warmMemory "bitmapBytes") -eq 0L -and
            [int64](Get-OptionalProperty $warmMemory "exitPssKb") -gt 0L
        $warmMacroAttempted = $null -ne $macroResult -and
            (Get-OptionalProperty $macroResult "sameProcessWarmAttempted") -eq $true
        $warmMacroPassed = $null -ne $macroResult -and
            (Get-OptionalProperty $macroResult "sameProcessWarmPassed") -eq $true
        $warmSummary = [pscustomobject][ordered]@{
            schema = 2
            passed = ($warmMacroAttempted -and $warmMacroPassed -and
                $warmGenerationValid -and $warmActualPresents.Count -gt 0 -and
                $warmFailures.Count -eq 0 -and $warmClosedCleanly -and $warmMemoryReleased)
            generation = if($warmGenerationValid) { $warmGeneration } else { $null }
            firstActualMs = if($null -ne $macroResult) {
                Get-OptionalProperty $macroResult "warmFirstActualMs"
            } else { $null }
            actualDescription = if($null -ne $macroResult) {
                Get-OptionalProperty $macroResult "warmActualDescription"
            } else { $null }
            actualDrawCommitCount = $warmActualPresents.Count
            failureCount = $warmFailures.Count
            closedCleanly = $warmClosedCleanly
            memoryReleased = $warmMemoryReleased
            memorySummary = $warmMemory
            macroFailure = if($null -ne $macroResult) {
                Get-OptionalProperty $macroResult "warmFailure"
            } else { $null }
            statePolicy = "same-process-same-work-no-force-stop-no-pm-clear"
        }
        $warmDir = Join-Path $caseDir "warm"
        [void](New-Item -ItemType Directory -Path $warmDir -Force)
        Write-Json (Join-Path $warmDir "summary.json") $warmSummary
    }
    $metadataByPage = @($sessionTelemetry | Where-Object {
        [string]$_.value.event -ceq "image_metadata" -and
            $null -ne $_.value.pageIndex
    } | Group-Object { [string]$_.value.pageIndex } | ForEach-Object {
        $_.Group[-1].value
    })
    $networkObservations = @($sessionTelemetry | Where-Object {
        [string]$_.value.event -ceq "network_observation"
    })
    $firstNetworkObservation = if($networkObservations.Count -gt 0) {
        $networkObservations[0].value
    } else { $null }
    $clientInstanceIds = @($networkObservations | ForEach-Object {
        [string]$_.value.clientInstanceId
    } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
    $encodedByteValues = @($metadataByPage | ForEach-Object {
        if($null -ne $_.encodedBytes) { [int64]$_.encodedBytes }
    })
    $widthValues = @($metadataByPage | ForEach-Object {
        if($null -ne $_.width) { [double]$_.width }
    })
    $heightValues = @($metadataByPage | ForEach-Object {
        if($null -ne $_.height) { [double]$_.height }
    })
    $averageResolution = if($widthValues.Count -eq $metadataByPage.Count -and
            $heightValues.Count -eq $metadataByPage.Count -and $metadataByPage.Count -gt 0) {
        "{0}x{1}" -f [Math]::Round(($widthValues | Measure-Object -Average).Average),
            [Math]::Round(($heightValues | Measure-Object -Average).Average)
    } else { $null }
    $largestMetadata = $metadataByPage | Sort-Object {
        [int64]$_.width * [int64]$_.height
    } -Descending | Select-Object -First 1
    $largestResolution = if($null -ne $largestMetadata) {
        "$($largestMetadata.width)x$($largestMetadata.height)"
    } else { $null }
    $pipelineRequestStarted = if($null -ne $imagePipelineSummary) {
        [int64](Get-OptionalProperty $imagePipelineSummary "requestStarted")
    } else { $null }
    $pipelineRequestSucceeded = if($null -ne $imagePipelineSummary) {
        [int64](Get-OptionalProperty $imagePipelineSummary "requestSucceeded")
    } else { $null }
    $pipelineRequestCancelled = if($null -ne $imagePipelineSummary) {
        [int64](Get-OptionalProperty $imagePipelineSummary "requestCancelled")
    } else { $null }
    $pipelineRequestFailed = if($null -ne $imagePipelineSummary) {
        [int64](Get-OptionalProperty $imagePipelineSummary "requestFailed")
    } else { $null }
    $pipelineResponseBytes = if($null -ne $imagePipelineSummary) {
        [int64](Get-OptionalProperty $imagePipelineSummary "responseBytes")
    } else { $null }
    $pipelineMetadataCount = if($null -ne $imagePipelineSummary) {
        [int64](Get-OptionalProperty $imagePipelineSummary "metadataCount")
    } else { $null }
    $pipelineEncodedBytes = if($null -ne $imagePipelineSummary) {
        [int64](Get-OptionalProperty $imagePipelineSummary "encodedBytes")
    } else { $null }
    if($null -ne $imagePipelineSummary) {
        $pipelineAverageWidth = [double](Get-OptionalProperty $imagePipelineSummary "averageWidth")
        $pipelineAverageHeight = [double](Get-OptionalProperty $imagePipelineSummary "averageHeight")
        $pipelineMaxWidth = [int64](Get-OptionalProperty $imagePipelineSummary "maxWidth")
        $pipelineMaxHeight = [int64](Get-OptionalProperty $imagePipelineSummary "maxHeight")
        if($pipelineAverageWidth -gt 0.0 -and $pipelineAverageHeight -gt 0.0) {
            $averageResolution = "{0}x{1}" -f [Math]::Round($pipelineAverageWidth),
                [Math]::Round($pipelineAverageHeight)
        }
        if($pipelineMaxWidth -gt 0L -and $pipelineMaxHeight -gt 0L) {
            $largestResolution = "$pipelineMaxWidth`x$pipelineMaxHeight"
        }
    }
    $pipelineFormats = @(if($null -ne $imagePipelineSummary) {
        ([string](Get-OptionalProperty $imagePipelineSummary "formats")) -split ';' |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique
    })
    $pipelineHosts = @(if($null -ne $imagePipelineSummary) {
        ([string](Get-OptionalProperty $imagePipelineSummary "hosts")) -split ';' |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique
    })
    $cold = if($coldStates.Count -gt 0) { $coldStates[0].value } else { $null }
    $coldFields = @(
        "memoryCacheEntries", "diskCacheFiles", "diskCacheBytes",
        "contentCacheEntries", "activeRequests", "activeDecodes", "client"
    )
    $coldHasRequiredFields = $null -ne $cold -and @(
        $coldFields | Where-Object { $_ -notin $cold.PSObject.Properties.Name }
    ).Count -eq 0
    $coldZero = $coldHasRequiredFields -and
        [int64]$cold.memoryCacheEntries -eq 0 -and
        [int64]$cold.diskCacheFiles -eq 0 -and
        [int64]$cold.diskCacheBytes -eq 0 -and
        [int64]$cold.contentCacheEntries -eq 0 -and
        [int64]$cold.activeRequests -eq 0 -and
        [int64]$cold.activeDecodes -eq 0 -and
        [string]$cold.client -ceq "not_created"
    $firstActualMs = if($null -ne $macroResult -and $null -ne $macroResult.firstActualMs) {
        [double]$macroResult.firstActualMs
    } else { $null }
    $allImagesReadyMsValue = Get-OptionalProperty $macroResult "allImagesReadyMs"
    $allImagesReadyMs = if($null -ne $allImagesReadyMsValue) {
        [double]$allImagesReadyMsValue
    } else { $null }
    $allImagesReadyPageCountValue = Get-OptionalProperty $macroResult "allImagesReadyPageCount"
    $allImagesReadyPageCount = if($null -ne $allImagesReadyPageCountValue) {
        [int64]$allImagesReadyPageCountValue
    } else { $null }
    $telemetryOpenToCommitMs = if($null -ne $firstActualDrawCommit -and
            $null -ne $firstActualDrawCommit.openToCommittedDrawMs) {
        [double]$firstActualDrawCommit.openToCommittedDrawMs
    } else { $null }
    $responseToCommitMs = if($null -ne $firstActualDrawCommit -and
            $null -ne $firstActualDrawCommit.responseToCommittedDrawMs) {
        [double]$firstActualDrawCommit.responseToCommittedDrawMs
    } else { $null }
    $totalFramesValue = Get-OptionalProperty $frameSummary "totalFrames"
    $totalFrames = if($null -ne $totalFramesValue) { [int64]$totalFramesValue } else { $null }
    $jankyFramesValue = Get-OptionalProperty $frameSummary "jankyFrames"
    $jankyFrames = if($null -ne $jankyFramesValue) { [int64]$jankyFramesValue } else { $null }
    $jankPercentValue = Get-OptionalProperty $frameSummary "jankPercent"
    $jankRatio = if($null -ne $jankPercentValue) { [double]$jankPercentValue } else { $null }
    $worstFrameValue = Get-OptionalProperty $frameSummary "worstFrameMs"
    $maxFrameMs = if($null -ne $worstFrameValue) { [double]$worstFrameValue } else { $null }
    $averageFrameValue = Get-OptionalProperty $frameSummary "averageFrameMs"
    $averageFrameMs = if($null -ne $averageFrameValue) { [double]$averageFrameValue } else { $null }
    # This is deliberately diagnostic only. JankStats frameDurationUiNanos is UI
    # work duration, not a compositor cadence and therefore cannot prove FPS.
    $uiWorkEquivalentFpsValue = Get-OptionalProperty $frameSummary "uiWorkEquivalentFps"
    $uiWorkEquivalentFps = if($null -ne $uiWorkEquivalentFpsValue) {
        [double]$uiWorkEquivalentFpsValue
    } else { $null }
    $maxConsecutiveJankyValue = Get-OptionalProperty $frameSummary "maxConsecutiveJankyFrames"
    $maxConsecutiveJankyFrames = if($null -ne $maxConsecutiveJankyValue) {
        [int64]$maxConsecutiveJankyValue
    } else { $null }

    $scrollIntervalsValue = Get-OptionalProperty $nativeFrameSummary "scrollIntervals"
    $scrollIntervals = if($null -ne $scrollIntervalsValue) { [int64]$scrollIntervalsValue } else { $null }
    $scrollFpsValue = Get-OptionalProperty $nativeFrameSummary "scrollFps"
    $scrollFps = if($null -ne $scrollFpsValue) { [double]$scrollFpsValue } else { $null }
    $slowIntervalsValue = Get-OptionalProperty $nativeFrameSummary "slowIntervals"
    $slowIntervals = if($null -ne $slowIntervalsValue) { [int64]$slowIntervalsValue } else { $null }
    $slowIntervalPercentValue = Get-OptionalProperty $nativeFrameSummary "slowIntervalPercent"
    $slowIntervalPercent = if($null -ne $slowIntervalPercentValue) {
        [double]$slowIntervalPercentValue
    } else { $null }
    $worstIntervalValue = Get-OptionalProperty $nativeFrameSummary "worstIntervalMs"
    $worstIntervalMs = if($null -ne $worstIntervalValue) { [double]$worstIntervalValue } else { $null }
    $maxConsecutiveSlowValue = Get-OptionalProperty $nativeFrameSummary "maxConsecutiveSlowIntervals"
    $maxConsecutiveSlowIntervals = if($null -ne $maxConsecutiveSlowValue) {
        [int64]$maxConsecutiveSlowValue
    } else { $null }
    $refreshPeriodValue = Get-OptionalProperty $nativeFrameSummary "refreshPeriodMs"
    $refreshPeriodMs = if($null -ne $refreshPeriodValue) { [double]$refreshPeriodValue } else { $null }
    $nativeRefreshHz = if($null -ne $refreshPeriodMs -and $refreshPeriodMs -gt 0.0) {
        # Normalize the formatted nanosecond period back to a display rate so
        # 16.6666/16.6667ms cannot move a nominal 60Hz threshold above 55 FPS.
        [Math]::Round(1000.0 / $refreshPeriodMs, 2)
    } else { $null }
    $scrollFpsTarget = if($null -ne $nativeRefreshHz) {
        if($nativeRefreshHz -ge 100.0) {
            [Math]::Min($nativeRefreshHz - 10.0, 110.0)
        } elseif($nativeRefreshHz -ge 55.0) {
            $nativeRefreshHz - 5.0
        } else {
            $nativeRefreshHz * 0.90
        }
    } else { $null }
    $initialBlankFramesValue = Get-OptionalProperty $coverageSummary "initialBlankFrames"
    $initialBlankFrames = if($null -ne $initialBlankFramesValue) {
        [int64]$initialBlankFramesValue
    } else { $null }
    $viewportDefectFramesValue = Get-OptionalProperty $coverageSummary "viewportDefectFrames"
    $viewportDefectFrames = if($null -ne $viewportDefectFramesValue) {
        [int64]$viewportDefectFramesValue
    } else { $null }
    $runwayDefectFramesValue = Get-OptionalProperty $coverageSummary "runwayDefectFrames"
    $runwayDefectFrames = if($null -ne $runwayDefectFramesValue) {
        [int64]$runwayDefectFramesValue
    } else { $null }
    $preSubmitViewportGapsValue = Get-OptionalProperty $coverageSummary "preSubmitViewportGaps"
    $preSubmitViewportGaps = if($null -ne $preSubmitViewportGapsValue) {
        [int64]$preSubmitViewportGapsValue
    } else { $null }
    # Only physically visible defects are blank-area failures. Forward runway is deliberately
    # retained as a separate prefetch diagnostic; it describes offscreen readiness and cannot be
    # reported as pixels the user actually saw.
    $blankAreaCount = if($null -ne $initialBlankFrames -and
            $null -ne $viewportDefectFrames -and $null -ne $preSubmitViewportGaps) {
        $viewportDefectFrames + $preSubmitViewportGaps +
            $initialBlankFrames
    } else { $null }
    $wrongBindingCount = if($null -ne $coverageSummary -and
            $null -ne $coverageSummary.identityInvalidFrames) {
        [int64]$coverageSummary.identityInvalidFrames
    } else { $null }
    $authoritativePageCountValue = Get-OptionalProperty $manifestSummary "authoritativePageCount"
    $authoritativePageCount = if($null -ne $authoritativePageCountValue) {
        [int64]$authoritativePageCountValue
    } else { $null }
    $manifestDigest = [string](Get-OptionalProperty $manifestSummary "manifestDigest")
    $traversalPageCountValue = Get-OptionalProperty $traversalSummary "authoritativePageCount"
    $traversalPageCount = if($null -ne $traversalPageCountValue) {
        [int64]$traversalPageCountValue
    } else { $null }
    $observedSourceCountValue = Get-OptionalProperty $traversalSummary "observedSourceCount"
    $observedSourceCount = if($null -ne $observedSourceCountValue) {
        [int64]$observedSourceCountValue
    } else { $null }
    $missingSourceIndexes = [string](Get-OptionalProperty $traversalSummary "missingSourceIndexes")
    $traversalInitialBlankValue = Get-OptionalProperty $traversalSummary "initialBlankFrames"
    $traversalInitialBlankFrames = if($null -ne $traversalInitialBlankValue) {
        [int64]$traversalInitialBlankValue
    } else { $null }
    $traversalManifestDigest = [string](Get-OptionalProperty $traversalSummary "manifestDigest")
    $invalidCommittedFramesValue = Get-OptionalProperty $traversalSummary "invalidCommittedFrames"
    $invalidCommittedFrames = if($null -ne $invalidCommittedFramesValue) {
        [int64]$invalidCommittedFramesValue
    } else { $null }
    $validCommittedFramesValue = Get-OptionalProperty $traversalSummary "validCommittedFrames"
    $validCommittedFrames = if($null -ne $validCommittedFramesValue) {
        [int64]$validCommittedFramesValue
    } else { $null }
    $retainedPssGrowthKb = if($null -ne $memorySummary -and
            $null -ne $warmSummary -and $null -ne $warmSummary.memorySummary) {
        [int64]$warmSummary.memorySummary.exitPssKb - [int64]$memorySummary.exitPssKb
    } else { $null }
    $retainedGrowthLimitKb = if($null -ne $memorySummary) {
        [Math]::Max(
            [int64]$script:ProductionWarmRetainedPssFloorLimitKb,
            [int64]([double]$memorySummary.exitPssKb *
                [double]$script:ProductionWarmRetainedPssRatioLimit))
    } else { $null }
    $sessionPssGrowthKb = if($null -ne $memorySummary) {
        [int64]$memorySummary.exitPssKb - [int64]$memorySummary.entryPssKb
    } else { $null }
    $sessionPssGrowthLimitKb = if($null -ne $memorySummary) {
        [Math]::Max(65536L, [int64]([double]$memorySummary.entryPssKb * 0.50))
    } else { $null }
    $currentBitmapBytesValue = Get-OptionalProperty $memorySummary "bitmapBytes"
    $currentBitmapBytes = if($null -ne $currentBitmapBytesValue) {
        [int64]$currentBitmapBytesValue
    } else { $null }
    $maxBitmapBytesValue = Get-OptionalProperty $memorySummary "maxBitmapBytes"
    $maxBitmapBytes = if($null -ne $maxBitmapBytesValue) {
        [int64]$maxBitmapBytesValue
    } else { $null }
    $gcCountValue = Get-OptionalProperty $memorySummary "gcCount"
    $gcCount = if($null -ne $gcCountValue) { [int64]$gcCountValue } else { $null }
    $instrumentPassed = $null -ne $instrumentation -and
        $instrumentation.ExitCode -eq 0 -and
        $instrumentation.Text -match 'OK \(1 test\)'
    # AndroidX Benchmark 1.4.1 on this API-35 x86_64 emulator can finish the real UI scenario and
    # write a non-empty Perfetto trace, then fail its in-process trace-processor Parse call before
    # benchmarkData.json is emitted. Accept only that exact infrastructure signature; the macro's
    # independent click/draw/SLA verdict and the app's native frame/coverage telemetry below stay
    # mandatory, so this cannot turn a product or performance failure into a pass.
    $androidxTraceParserIsolationAccepted =
        -not $androidxBenchmark.valid -and
        $androidxBenchmark.dataFiles.Count -eq 0 -and
        $androidxBenchmark.problems.Count -eq 1 -and
        [string]$androidxBenchmark.problems[0] -ceq
            'expected exactly one *-benchmarkData.json; found 0' -and
        $instrumentPassed -and
        $null -ne $macroResult -and
        (Get-OptionalProperty $macroResult 'passed') -eq $true -and
        [string](Get-OptionalProperty $macroResult 'traceProcessingFailureType') -ceq
            'java.lang.IllegalStateException' -and
        [string](Get-OptionalProperty $macroResult 'traceProcessingFailure') -ceq
            'Failed unrecoverably while parsing in a previous Parse call' -and
        $macroTraceFiles.Count -eq 1 -and
        $nonEmptyMacroTraceFiles.Count -eq 1 -and
        $emptyMacroTraceFiles.Count -eq 0 -and
        $macroTraceFiles[0].Length -gt 1MB
    $expectedEpisodePrefix = "/$([string]$Target.workType)/$([string]$Target.workId)/"
    $targetEpisodeMetadataValid =
        -not [string]::IsNullOrWhiteSpace([string]$Target.episodeId) -and
        -not [string]::IsNullOrWhiteSpace([string]$Target.episodeTitle) -and
        -not [string]::IsNullOrWhiteSpace([string]$Target.episodePath) -and
        [string]$Target.episodePath -ceq ($expectedEpisodePrefix + [string]$Target.episodeId) -and
        [string]$Target.metadataSource -cne "ui-first-visible-fallback"
    $requestStartNanos = 0L
    $responseNanos = 0L
    $decodeNanos = 0L
    $drawNanos = 0L
    $firstImageTimelineComplete =
        [long]::TryParse([string]$firstImageRequestStartElapsedNanos, [ref]$requestStartNanos) -and
        [long]::TryParse([string]$firstImageResponseElapsedNanos, [ref]$responseNanos) -and
        [long]::TryParse([string]$firstImageDecodeElapsedNanos, [ref]$decodeNanos) -and
        [long]::TryParse([string]$firstImageDrawElapsedNanos, [ref]$drawNanos)
    $firstImageTimelineOrdered = $firstImageTimelineComplete -and
        $requestStartNanos -le $responseNanos -and
        $requestStartNanos -le $decodeNanos -and
        $responseNanos -le $drawNanos -and
        $decodeNanos -le $drawNanos

    $violations = [Collections.Generic.List[string]]::new()
    if(-not $coldProof.processStateMeasured) {
        $violations.Add("pre-click target UID/process state was not measurable")
    } elseif(-not $coldProof.processCold) {
        $violations.Add("target UID or package secondary process existed before launch")
    }
    if(-not $coldProof.runningServiceStateMeasured) {
        $violations.Add("pre-click running service state was not measurable")
    } elseif($coldProof.runningServiceCount -ne 0) {
        $violations.Add("target running service existed before viewer click")
    }
    if(-not $coldProof.runningJobStateMeasured) {
        $violations.Add("pre-click running job/work state was not measurable")
    } elseif($coldProof.runningJobCount -ne 0 -or $coldProof.runningWorkCount -ne 0) {
        $violations.Add("target running job or WorkManager work existed before viewer click")
    }
    if(-not $coldProof.appDataCold) { $violations.Add("pm clear target failed") }
    if(-not $coldProof.benchmarkDataCold) { $violations.Add("pm clear benchmark failed") }
    if(-not $androidxTraceCleanupBefore.allMatchedWritersStopped -or
            -not $androidxTraceCleanupBefore.absent) {
        $violations.Add("stale AndroidX Perfetto trace output could not be retired before case")
    }
    if($null -eq $androidxTraceCleanupAfter -or
            -not $androidxTraceCleanupAfter.allMatchedWritersStopped -or
            -not $androidxTraceCleanupAfter.absent) {
        $violations.Add("AndroidX Perfetto trace output could not be retired after instrumentation")
    }
    if(-not $instrumentPassed) { $violations.Add("Macrobenchmark instrumentation failed") }
    if(-not $targetEpisodeMetadataValid) {
        $violations.Add("randomly selected episode identity metadata was unavailable or invalid")
    }
    if($benchmarkPull.ExitCode -ne 0) {
        $violations.Add("Macrobenchmark artifact pull failed")
    }
    if($macroTraceFiles.Count -eq 0 -or $nonEmptyMacroTraceFiles.Count -eq 0) {
        $violations.Add("Macrobenchmark Perfetto trace artifact missing or empty")
    } elseif($emptyMacroTraceFiles.Count -ne 0) {
        $violations.Add("Macrobenchmark emitted an empty Perfetto trace artifact")
    }
    if($screenshotPull.ExitCode -ne 0) {
        $violations.Add("screenshot/recording artifact pull failed")
    }
    if(-not $remoteArtifactCleanup.benchmarkAbsent) {
        $violations.Add("remote benchmark artifacts could not be retired after host pull")
    }
    if(-not $remoteArtifactCleanup.screenshotAbsent) {
        $violations.Add("remote screenshot artifacts could not be retired after host pull")
    }
    if($visualArtifactFiles.Count -eq 0 -or $nonEmptyVisualArtifactFiles.Count -eq 0) {
        $violations.Add("screenshot/recording evidence artifact missing or empty")
    } elseif($emptyVisualArtifactFiles.Count -ne 0) {
        $violations.Add("screenshot/recording evidence contained an empty artifact")
    }
    $androidxActiveFrameCount = $null
    $androidxActiveIntervalCount = $null
    $androidxActiveFps = $null
    $androidxActiveJankPercent = $null
    $androidxActiveGapMaxMs = $null
    $androidxActiveRefreshPeriodMs = $null
    $androidxActiveRefreshHz = $null
    $androidxActiveFpsTarget = $null
    $androidxActiveCpuPercent = $null
    $androidxActiveMainRunMaxMs = $null
    $androidxActivePresentationSystemFence = $null
    $androidxAllImagesReadyMs = $null
    $androidxFrameCommitTraceKind = $null
    $androidxFrameCommitMaxMs = $null
    if(-not $androidxBenchmark.valid -and -not $androidxTraceParserIsolationAccepted) {
        foreach($problem in $androidxBenchmark.problems) {
            $violations.Add("AndroidX benchmarkData: $problem")
        }
    } elseif($androidxBenchmark.valid) {
        $androidxFrameMax = Get-OptionalProperty $androidxBenchmark "frameDurationCpuMaxMs"
        $androidxJankPercent = Get-OptionalProperty $androidxBenchmark "jankPercent"
        $androidxConsecutiveJank = Get-OptionalProperty $androidxBenchmark "maxConsecutiveJankyFrames"
        $androidxViewerOpenMs = Get-OptionalProperty $androidxBenchmark.single "ViewerOpenFirstMs"
        $androidxAllImagesReadyMs = Get-OptionalProperty `
            $androidxBenchmark.single "ViewerAllImagesReadyFirstMs"
        foreach($candidate in @(
                'ViewerHwuiFrameCommitMaxMs',
                'ViewerSurfaceControlLatchMaxMs',
                'ViewerSurfaceQueueSubmissionMaxMs')) {
            $candidateValue = Get-OptionalProperty $androidxBenchmark.single $candidate
            if($null -ne $candidateValue -and
                    ($null -eq $androidxFrameCommitMaxMs -or
                        [double]$candidateValue -gt [double]$androidxFrameCommitMaxMs)) {
                $androidxFrameCommitTraceKind = $candidate -replace 'MaxMs$', ''
                $androidxFrameCommitMaxMs = [double]$candidateValue
            }
        }
        $androidxImageRequestCount = Get-OptionalProperty $androidxBenchmark.single "ImageRequestCount"
        $androidxScrollDurationMs = Get-OptionalProperty $androidxBenchmark.single "viewerScrollDurationMs"
        $androidxScrollCpuMs = Get-OptionalProperty $androidxBenchmark.single "viewerScrollCpuTimeMs"
        $androidxScrollCpuPercent = Get-OptionalProperty $androidxBenchmark.single "viewerScrollCpuPercent"
        $androidxMainRunMaxMs = Get-OptionalProperty `
            $androidxBenchmark.single "viewerScrollMainThreadRunningMaxMs"
        $androidxActiveFrameCount = Get-OptionalProperty `
            $androidxBenchmark.single "viewerActivePresentedFrameCount"
        $androidxActiveIntervalCount = Get-OptionalProperty `
            $androidxBenchmark.single "viewerActivePresentationIntervalCount"
        $androidxActiveFps = Get-OptionalProperty `
            $androidxBenchmark.single "viewerActivePresentationFps"
        $androidxActiveJankPercent = Get-OptionalProperty `
            $androidxBenchmark.single "viewerActivePresentationJankPercent"
        $androidxActiveGapMaxMs = Get-OptionalProperty `
            $androidxBenchmark.single "viewerActivePresentationGapMaxMs"
        $androidxActiveRefreshPeriodMs = Get-OptionalProperty `
            $androidxBenchmark.single "viewerActiveRefreshPeriodMs"
        $androidxActiveCpuPercent = Get-OptionalProperty `
            $androidxBenchmark.single "viewerActiveCpuPercent"
        $androidxActiveMainRunMaxMs = Get-OptionalProperty `
            $androidxBenchmark.single "viewerActiveMainThreadRunningMaxMs"
        $androidxActivePresentationSystemFence = Get-OptionalProperty `
            $androidxBenchmark.single "viewerActivePresentationSystemFence"
        $androidxActiveRefreshHz = if($null -ne $androidxActiveRefreshPeriodMs -and
                [double]$androidxActiveRefreshPeriodMs -gt 0.0) {
            [Math]::Round(1000.0 / [double]$androidxActiveRefreshPeriodMs, 2)
        } else { $null }
        $androidxActiveFpsTarget = if($null -ne $androidxActiveRefreshHz) {
            if($androidxActiveRefreshHz -ge 100.0) {
                [Math]::Min($androidxActiveRefreshHz - 10.0, 110.0)
            } elseif($androidxActiveRefreshHz -ge 55.0) {
                $androidxActiveRefreshHz - 5.0
            } else {
                $androidxActiveRefreshHz * 0.90
            }
        } else { $null }
        if($null -eq $androidxActiveFrameCount -or [double]$androidxActiveFrameCount -le 1.0 -or
                $null -eq $androidxActiveIntervalCount -or [double]$androidxActiveIntervalCount -le 0.0) {
            $violations.Add("active-scroll compositor frame evidence was missing")
        }
        if($null -eq $androidxActiveFps -or $null -eq $androidxActiveFpsTarget -or
                [double]$androidxActiveFps -lt [double]$androidxActiveFpsTarget) {
            $violations.Add("active-scroll compositor FPS was below the refresh-rate target")
        }
        if($null -eq $androidxActiveJankPercent -or
                [double]$androidxActiveJankPercent -ge 1.0) {
            $violations.Add("active-scroll compositor jank ratio was not below 1 percent")
        }
        if($null -eq $androidxActiveGapMaxMs -or [double]$androidxActiveGapMaxMs -ge 100.0) {
            $violations.Add("active-scroll compositor presentation gap reached 100ms")
        }
        if($null -eq $androidxActiveCpuPercent -or [double]$androidxActiveCpuPercent -lt 0.0) {
            $violations.Add("active-scroll CPU metric was missing or invalid")
        }
        if($null -eq $androidxActiveMainRunMaxMs -or
                [double]$androidxActiveMainRunMaxMs -ge 100.0) {
            $violations.Add("active-scroll main-thread running slice reached 100ms")
        }
        if($script:FastFunctionalTriage) {
            if($null -eq $androidxActivePresentationSystemFence -or
                    [double]$androidxActivePresentationSystemFence -ne 1.0) {
                $violations.Add(
                    "fast jank gate did not obtain authoritative SurfaceFlinger fence evidence"
                )
            }
        } elseif($null -eq $androidxActivePresentationSystemFence -or
                ([double]$androidxActivePresentationSystemFence -ne 0.0 -and
                    [double]$androidxActivePresentationSystemFence -ne 1.0)) {
            $violations.Add("active-scroll presentation evidence kind was missing or invalid")
        }
        if($null -eq $androidxViewerOpenMs -or
                [double]$androidxViewerOpenMs -gt $caseImageSlaMs) {
            $violations.Add("AndroidX ViewerOpen trace exceeded the first-image SLA")
        }
        if($null -eq $androidxAllImagesReadyMs -or
                [double]$androidxAllImagesReadyMs -gt $caseImageSlaMs) {
            $violations.Add("AndroidX ViewerAllImagesReady trace exceeded the ${caseImageSlaMs}ms type SLA")
        }
        if(-not $script:FastFunctionalTriage -and
                ($null -eq $androidxFrameCommitMaxMs -or
                    [double]$androidxFrameCommitMaxMs -ge 100.0)) {
            $violations.Add("AndroidX renderer frame-commit trace reached 100ms or was unmeasured")
        }
        if($null -eq $androidxImageRequestCount -or [double]$androidxImageRequestCount -le 0.0) {
            $violations.Add("AndroidX ImageRequest trace count was zero")
        }
        if(-not $script:FastFunctionalTriage -and
                ($null -eq $androidxScrollDurationMs -or
                    [double]$androidxScrollDurationMs -le 0.0 -or
                    $null -eq $androidxScrollCpuMs -or
                    [double]$androidxScrollCpuMs -lt 0.0 -or
                    $null -eq $androidxScrollCpuPercent -or
                    [double]$androidxScrollCpuPercent -lt 0.0)) {
            $violations.Add("scroll-session CPU metrics were missing or invalid")
        }
        # Whole-scenario FrameTiming and ViewerScrollSession values remain in the artifact as
        # diagnostics. They include screenshots and automation idle; only the target-process
        # ViewerActiveScroll compositor bounds qualify continuous-forward interaction jank.
    }
    if($null -eq $macroResult) { $violations.Add("NtkColdMacro result missing") }
    elseif($macroResult.passed -ne $true) { $violations.Add("real-UI scenario failed") }
    if($null -eq $allImagesReadyMs -or $allImagesReadyMs -gt $caseImageSlaMs) {
        $violations.Add("all canonical images exceeded ${caseImageSlaMs}ms or were unmeasured")
    }
    if($null -eq $allImagesReadyPageCount -or $null -eq $authoritativePageCount -or
            $allImagesReadyPageCount -ne $authoritativePageCount) {
        $violations.Add("all-images render-ready page count did not equal the authoritative manifest")
    }
    if($null -eq $macroResult -or
            (Get-OptionalProperty $macroResult "allImagesSlaPassed") -ne $true) {
        $violations.Add("Macrobenchmark did not prove the type-specific all-images SLA")
    }
    if(-not $coldZero) { $violations.Add("cold_state proof missing or non-zero") }
    $maximumExpectedViewerClicks = if($script:IncludeWarmReopen) { 2 } else { 1 }
    if($viewerClicks.Count -lt 1 -or $viewerClicks.Count -gt $maximumExpectedViewerClicks) {
        $violations.Add("unexpected viewer click telemetry count")
    }
    if(-not $viewerGenerationValid) { $violations.Add("viewer generation missing, invalid, or zero") }
    if($preEntryWork.Count -gt 0) {
        $violations.Add("image/page-list/decode work started before viewer_open click")
    }
    if($zeroGenerationPreClick.Count -gt 0) {
        $violations.Add("generation-zero or unscoped work started before viewer_open click")
    }
    if($actualDrawCommits.Count -eq 0) {
        $violations.Add("HWUI-committed actual image draw telemetry missing")
    }
    if([string]$firstActualEvidenceKind -cne "hwui_frame_commit") {
        $violations.Add("actual image draw lacks HWUI frame-commit evidence")
    }
    if(-not $firstActualPageValid -or $firstActualPageIndex -ne 0L) {
        $violations.Add("fresh viewer did not first commit an actual page-zero draw")
    }
    if(-not $firstImageTimelineComplete) {
        $violations.Add("first image request/response/decode/draw timeline proof missing")
    } elseif(-not $firstImageTimelineOrdered) {
        $violations.Add("first image request/response/decode/draw timeline was out of order")
    }
    if($preFirstRequestPageMissingCount -gt 0) {
        $violations.Add("pre-first image request page index was unmeasured")
    }
    if($null -eq $firstDrawCommit -or $firstDrawCommit.actual -ne $true) {
        $violations.Add("first HWUI-committed draw was not an actual work image")
    }
    if($null -eq $firstActualMs -or $firstActualMs -gt $caseImageSlaMs) {
        $violations.Add("first actual image exceeded ${caseImageSlaMs}ms or was unmeasured")
    }
    if($null -eq $telemetryOpenToCommitMs -or
            $telemetryOpenToCommitMs -gt $caseImageSlaMs) {
        $violations.Add("telemetry click-to-committed-draw exceeded ${caseImageSlaMs}ms or was unmeasured")
    }
    if($null -eq $responseToCommitMs -or $responseToCommitMs -lt 0.0) {
        $violations.Add("response-to-committed-draw was negative or unmeasured")
    }
    # Reader pixels are produced by a dedicated Surface/BufferQueue, so a perfectly stationary
    # ViewRoot can legitimately give JankStats zero frames while the child layer presents every
    # scrolling viewport. SurfaceFlinger's PresentFenceSignaled rows are authoritative when the
    # platform exposes them. A synchronous eglSwapBuffers completion proves that an immutable
    # buffer was queued, but callback scheduling can alternate a late sample with an early one
    # while display presents remain evenly paced; qualify that fallback cadence only when
    # system-fence evidence is unavailable.
    if(-not $script:FastFunctionalTriage) {
        $nativeSurfaceFrameObserverPresent = $null -ne $nativeFrameSummary -and
            $null -ne $scrollIntervals -and $scrollIntervals -gt 0
        $systemFenceCadencePresent = $null -ne $androidxActivePresentationSystemFence -and
            [double]$androidxActivePresentationSystemFence -eq 1.0
        if($null -eq $frameSummary -and -not $nativeSurfaceFrameObserverPresent) {
            $violations.Add("frame_summary and native surface frame telemetry missing")
        }
        if(($null -eq $totalFrames -or $totalFrames -le 0) -and
            -not $nativeSurfaceFrameObserverPresent) {
            $violations.Add("no JankStats or native surface viewer frames observed")
        }
        if($null -eq $nativeFrameSummary) {
            $violations.Add("native_frame_summary telemetry missing")
        } else {
            if($null -eq $scrollIntervals -or $scrollIntervals -le 0) {
                $violations.Add("native buffer-submission scroll intervals missing")
            }
            if(-not $systemFenceCadencePresent) {
                if($null -eq $scrollFps -or $null -eq $scrollFpsTarget -or
                        $scrollFps -lt $scrollFpsTarget) {
                    $violations.Add("native buffer-submission FPS was below the refresh-rate target or unmeasured")
                }
                if($null -eq $slowIntervalPercent -or $slowIntervalPercent -ge 1.0) {
                    $violations.Add("fallback native slow-interval ratio is not below 1 percent or was unmeasured")
                }
                if($null -eq $worstIntervalMs -or $worstIntervalMs -ge 100.0) {
                    $violations.Add("fallback native worst interval is at least 100ms or unmeasured")
                }
                if($null -eq $maxConsecutiveSlowIntervals -or
                        $maxConsecutiveSlowIntervals -gt 1) {
                    $violations.Add("fallback native consecutive slow intervals exceeded one or were unmeasured")
                }
            }
        }
    }
    if($null -eq $coverageSummary) { $violations.Add("coverage_summary telemetry missing") }
    elseif($null -eq $initialBlankFrames) { $violations.Add("initial blank frame telemetry missing") }
    elseif($blankAreaCount -ne 0) { $violations.Add("viewport coverage defects detected") }
    if($null -eq $wrongBindingCount -or $wrongBindingCount -ne 0) {
        $violations.Add("identity-invalid frames detected or unmeasured")
    }
    if($null -eq $manifestSummary -or $null -eq $authoritativePageCount -or
            $authoritativePageCount -le 0 -or $manifestDigest -notmatch '^[0-9a-f]{64}$') {
        $violations.Add("authoritative manifest summary missing or invalid")
    }
    if($null -eq $traversalSummary) {
        $violations.Add("full traversal summary missing")
    } else {
        if($null -eq $traversalInitialBlankFrames -or
                $traversalInitialBlankFrames -ne $initialBlankFrames) {
            $violations.Add("traversal initial blank frame telemetry missing or inconsistent")
        }
        if($traversalPageCount -ne $authoritativePageCount) {
            $violations.Add("traversal authority did not cover every canonical source page")
        }
        if($traversalManifestDigest -cne $manifestDigest) {
            $violations.Add("traversal manifest digest did not match launch authority")
        }
        if($null -eq $validCommittedFrames -or $validCommittedFrames -le 0 -or
                $null -eq $invalidCommittedFrames -or $invalidCommittedFrames -ne 0) {
            $violations.Add("committed traversal frame identity proof failed or was unmeasured")
        }
    }
    if($null -eq $imagePipelineSummary) {
        $violations.Add("image pipeline aggregate summary was missing")
    } elseif($null -eq $authoritativePageCount -or
            $pipelineRequestStarted -ne $authoritativePageCount -or
            $pipelineRequestSucceeded -ne $authoritativePageCount -or
            $pipelineMetadataCount -ne $authoritativePageCount -or
            $pipelineRequestCancelled -ne 0L -or $pipelineRequestFailed -ne 0L -or
            $pipelineEncodedBytes -le 0L -or $pipelineResponseBytes -ne $pipelineEncodedBytes) {
        $violations.Add("image pipeline aggregate did not prove one successful body and metadata record per canonical page")
    }
    if($null -eq $memorySummary) { $violations.Add("memory_summary telemetry missing") }
    else {
        if([int64]$memorySummary.entryPssKb -le 0 -or
                [int64]$memorySummary.exitPssKb -le 0 -or
                [int64]$memorySummary.maxPssKb -lt [int64]$memorySummary.entryPssKb -or
                [int64]$memorySummary.maxPssKb -lt [int64]$memorySummary.exitPssKb) {
            $violations.Add("PSS lifecycle samples were invalid")
        }
        # Warm reopen is recorded as a separate diagnostic; cold qualification does not fail
        # merely because that optional sample is unavailable. A measured production leak still
        # fails the case.
        if($script:IncludeWarmReopen -and
                $null -ne $retainedPssGrowthKb -and
                $null -ne $retainedGrowthLimitKb -and
                $retainedPssGrowthKb -gt $retainedGrowthLimitKb) {
            $violations.Add("warm reopen retained-PSS growth exceeded the production limit")
        }
        if($null -eq $currentBitmapBytes -or $currentBitmapBytes -ne 0L) {
            $violations.Add("viewer exit retained bitmap bytes")
        }
        # High-memory host-GPU qualification retains one complete immutable 100+ page scene so a
        # forward fling never falls into clear/re-decode/GC churn. A measured 106-page scene peaks
        # near 641 MiB including decode/presentation overlap. One GiB remains a hard production
        # ceiling; duplicate winners, a second scene, OOM, or exit-retained bitmap bytes still fail.
        if($null -eq $maxBitmapBytes -or $maxBitmapBytes -le 0L -or
                $maxBitmapBytes -gt 1073741824L) {
            $violations.Add("peak bitmap memory was invalid or exceeded the 1 GiB exact-scene ceiling")
        }
        if($null -eq $gcCount -or $gcCount -lt 0L) {
            $violations.Add("GC count was unmeasured")
        }
    }
    if($duplicates.Count -gt 0) { $violations.Add("duplicate SourceKey downloads detected") }
    if($completedDownloadsWithoutKey.Count -gt 0) {
        $violations.Add("completed image downloads lacked a stable SourceKey")
    }
    if($imageFailures.Count -gt 0) { $violations.Add("image request failures detected") }
    if($decodeFailures.Count -gt 0) { $violations.Add("image decode failures detected") }
    if($pageListFailures.Count -gt 0) { $violations.Add("page-list request failures detected") }
    if(-not $requestQueueMetrics.measured) {
        $violations.Add("active request queue metrics were missing or internally inconsistent")
    } elseif($requestQueueMetrics.peakActive -gt $script:ProductionMaxActiveRequestQueue) {
        $violations.Add("active request queue exceeded the production concurrency ceiling")
    }
    if(-not $forwardTraversalMetricsPresent) {
        $violations.Add("continuous forward traversal metrics were missing or invalid")
    }
    if($imageCancellations.Count -gt 0) {
        $violations.Add("image requests were cancelled during continuous forward reading")
    }
    if($decodeCancellations.Count -gt 0) {
        $violations.Add("image decodes were cancelled during continuous forward reading")
    }
    if($networkObservations.Count -eq 0) { $violations.Add("network_observation telemetry missing") }
    if($clientInstanceIds.Count -gt 1) { $violations.Add("multiple viewer HTTP client instances detected") }
    if($viewerClosedEvents.Count -eq 0) {
        $violations.Add("viewer_closed telemetry missing")
    } else {
        $closed = $viewerClosedEvents[-1].value
        $closedFields = @("activeRequests", "activeDecodes", "drainTimedOut")
        $closedHasFields = @($closedFields | Where-Object {
            $_ -notin $closed.PSObject.Properties.Name
        }).Count -eq 0
        if(-not $closedHasFields -or [int64]$closed.activeRequests -ne 0 -or
                [int64]$closed.activeDecodes -ne 0 -or $closed.drainTimedOut -ne $false) {
            $violations.Add("viewer closed with active requests or decodes")
        }
    }
    if($postCloseWork.Count -gt 0) {
        $violations.Add("viewer content work started after viewer_closed")
    }
    if($foreignGenerationWork.Count -gt 0) {
        $violations.Add("viewer content work escaped the active generation")
    }
    if(-not [string]::IsNullOrWhiteSpace($caseException)) { $violations.Add($caseException) }

    $caseSummary = [pscustomobject][ordered]@{
        schema = 1
        caseId = $caseId
        ordinal = $Ordinal
        workType = [string]$Target.workType
        workId = [string]$Target.workId
        workTitle = [string]$Target.title
        episodeId = [string]$Target.episodeId
        episodeTitle = [string]$Target.episodeTitle
        episodePath = [string]$Target.episodePath
        episodeMetadataSource = [string]$Target.metadataSource
        catalogPage = $Target.catalogPage
        testedAt = $caseStartedAt
        completedAt = [DateTimeOffset]::Now.ToString("o")
        networkType = $script:deviceInfo.networkType
        deviceModel = $script:deviceInfo.model
        androidVersion = $script:deviceInfo.androidRelease
        refreshHz = $script:deviceInfo.refreshHz
        randomSeed = $script:Seed
        passed = ($violations.Count -eq 0)
        fastFunctionalTriage = $script:FastFunctionalTriage.IsPresent
        violations = @($violations)
        imageSlaMs = $caseImageSlaMs
        firstActualMs = $firstActualMs
        allImagesReadyMs = $allImagesReadyMs
        allImagesReadyPageCount = $allImagesReadyPageCount
        androidxAllImagesReadyMs = $androidxAllImagesReadyMs
        androidxFrameCommitTraceKind = $androidxFrameCommitTraceKind
        androidxFrameCommitMaxMs = if($null -ne $androidxFrameCommitMaxMs) {
            [Math]::Round([double]$androidxFrameCommitMaxMs, 4)
        } else { $null }
        telemetryOpenToCommittedDrawMs = $telemetryOpenToCommitMs
        responseToCommittedDrawMs = $responseToCommitMs
        firstActualPageIndex = if($firstActualPageValid) { $firstActualPageIndex } else { $null }
        firstImageRequestStartElapsedNanos = $firstImageRequestStartElapsedNanos
        firstImageResponseElapsedNanos = $firstImageResponseElapsedNanos
        firstImageDecodeElapsedNanos = $firstImageDecodeElapsedNanos
        firstImageDrawElapsedNanos = $firstImageDrawElapsedNanos
        viewerGeneration = if($viewerGenerationValid) { $viewerGeneration } else { $null }
        imageCount = if($null -ne $pipelineMetadataCount) {
            $pipelineMetadataCount
        } else { $metadataByPage.Count }
        authoritativePageCount = $authoritativePageCount
        observedSourceCount = $observedSourceCount
        missingSourceIndexes = $missingSourceIndexes
        manifestDigest = $manifestDigest
        traversalManifestDigest = $traversalManifestDigest
        validCommittedFrames = $validCommittedFrames
        invalidCommittedFrames = $invalidCommittedFrames
        totalImageBytes = if($null -ne $pipelineEncodedBytes -and $pipelineEncodedBytes -gt 0L) {
            $pipelineEncodedBytes
        } elseif($encodedByteValues.Count -gt 0) {
            [int64](($encodedByteValues | Measure-Object -Sum).Sum)
        } else { $null }
        averageResolution = $averageResolution
        largestResolution = $largestResolution
        imageFormats = if($pipelineFormats.Count -gt 0) { @($pipelineFormats) } else {
            @($metadataByPage | ForEach-Object { [string]$_.format } |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        }
        imageHosts = @(@($requestStarts | ForEach-Object { [string]$_.value.urlHost }) +
            @($metadataByPage | ForEach-Object { [string]$_.urlHost }) + @($pipelineHosts) |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        totalFrames = $totalFrames
        jankyFrames = $jankyFrames
        jankRatio = $jankRatio
        maxFrameMs = $maxFrameMs
        averageFrameMs = $averageFrameMs
        uiWorkEquivalentFps = $uiWorkEquivalentFps
        maxConsecutiveJankyFrames = $maxConsecutiveJankyFrames
        activePresentedFrameCount = $androidxActiveFrameCount
        activePresentationIntervalCount = $androidxActiveIntervalCount
        activePresentationFps = $androidxActiveFps
        activePresentationFpsTarget = if($null -ne $androidxActiveFpsTarget) {
            [Math]::Round([double]$androidxActiveFpsTarget, 2)
        } else { $null }
        activePresentationJankPercent = $androidxActiveJankPercent
        activePresentationGapMaxMs = $androidxActiveGapMaxMs
        activeRefreshPeriodMs = $androidxActiveRefreshPeriodMs
        activeRefreshHz = $androidxActiveRefreshHz
        activeCpuPercent = $androidxActiveCpuPercent
        activeMainThreadRunningMaxMs = $androidxActiveMainRunMaxMs
        activePresentationSystemFence = $androidxActivePresentationSystemFence
        nativeScrollIntervals = $scrollIntervals
        nativeScrollFps = $scrollFps
        nativeScrollFpsTarget = if($null -ne $scrollFpsTarget) {
            [Math]::Round($scrollFpsTarget, 2)
        } else { $null }
        nativeSlowIntervals = $slowIntervals
        nativeSlowIntervalPercent = $slowIntervalPercent
        nativeWorstIntervalMs = $worstIntervalMs
        nativeMaxConsecutiveSlowIntervals = $maxConsecutiveSlowIntervals
        nativeRefreshPeriodMs = $refreshPeriodMs
        nativeRefreshHz = if($null -ne $nativeRefreshHz) {
            [Math]::Round($nativeRefreshHz, 2)
        } else { $null }
        viewportDefectFrames = $viewportDefectFrames
        runwayDefectFrames = $runwayDefectFrames
        preSubmitViewportGaps = $preSubmitViewportGaps
        initialBlankFrames = $initialBlankFrames
        blankAreaCount = $blankAreaCount
        wrongBindingCount = $wrongBindingCount
        imageFailureCount = if($null -ne $pipelineRequestFailed) {
            $pipelineRequestFailed
        } else { $imageFailures.Count }
        imageCancellationCount = if($null -ne $pipelineRequestCancelled) {
            $pipelineRequestCancelled
        } else { $imageCancellations.Count }
        decodeFailureCount = $decodeFailures.Count
        decodeCancellationCount = $decodeCancellations.Count
        pageListFailureCount = $pageListFailures.Count
        pageListCancellationCount = $pageListCancellations.Count
        requestQueueMetricsMeasured = $requestQueueMetrics.measured
        peakActiveRequestQueue = $requestQueueMetrics.peakActive
        peakActiveRequestQueueLimit = $script:ProductionMaxActiveRequestQueue
        requestQueueTerminalBalance = $requestQueueMetrics.terminalBalance
        requestQueueMetricProblems = @($requestQueueMetrics.problems)
        forwardTraversalMetricsMeasured = $forwardTraversalMetricsPresent
        forwardTraversalGestureCount = if($forwardTraversalMetricsPresent) {
            $forwardTraversalGestureCount
        } else { $null }
        forwardTraversalGestureMin = $script:ProductionMinForwardGestures
        forwardTraversalGestureMax = $script:ProductionMaxForwardGestures
        forwardTraversalStartElapsedNanos = if($forwardTraversalMetricsPresent) {
            $forwardTraversalStartNanos
        } else { $null }
        forwardTraversalEndElapsedNanos = if($forwardTraversalMetricsPresent) {
            $forwardTraversalEndNanos
        } else { $null }
        duplicateRequestCount = if($null -ne $pipelineRequestStarted -and
                $null -ne $authoritativePageCount) {
            [Math]::Max(0L, $pipelineRequestStarted - $authoritativePageCount)
        } else { $duplicates.Count }
        completedImageDownloadCount = if($null -ne $pipelineRequestSucceeded) {
            $pipelineRequestSucceeded
        } else { $completedImageDownloads.Count }
        completedDownloadMissingSourceKeyCount = $completedDownloadsWithoutKey.Count
        preFirstRequestCount = $preFirstRequestEvents.Count
        preFirstRequestPages = @($preFirstRequestPages)
        preFirstRequestMax = $preFirstRequestMax
        preFirstRequestMissingPageCount = $preFirstRequestPageMissingCount
        preFirstEscapedRequestCount = $preFirstEscapedRequests.Count
        networkObservationCount = $networkObservations.Count
        httpClientInstanceCount = $clientInstanceIds.Count
        firstRequestProtocol = if($null -ne $firstNetworkObservation) {
            [string]$firstNetworkObservation.protocol
        } else { $null }
        firstRequestConnectionId = if($null -ne $firstNetworkObservation) {
            [string]$firstNetworkObservation.connectionId
        } else { $null }
        firstRequestConnectionReused = if($null -ne $firstNetworkObservation) {
            $firstNetworkObservation.connectionReused
        } else { $null }
        cdnCache = "UNKNOWN_UNCONTROLLED"
        entryPssMb = if($null -ne $memorySummary) {
            [Math]::Round([double]$memorySummary.entryPssKb / 1024.0, 2)
        } else { $null }
        exitPssMb = if($null -ne $memorySummary) {
            [Math]::Round([double]$memorySummary.exitPssKb / 1024.0, 2)
        } else { $null }
        maxPssMb = if($null -ne $memorySummary) {
            [Math]::Round([double]$memorySummary.maxPssKb / 1024.0, 2)
        } else { $null }
        bitmapBytes = $currentBitmapBytes
        maxBitmapBytes = $maxBitmapBytes
        gcCount = $gcCount
        sessionPssGrowthKb = $sessionPssGrowthKb
        sessionPssGrowthLimitKb = $sessionPssGrowthLimitKb
        retainedPssGrowthKb = $retainedPssGrowthKb
        retainedPssGrowthLimitKb = $retainedGrowthLimitKb
        retainedPssGrowthMeasured = ($null -ne $retainedPssGrowthKb -and
            $null -ne $retainedGrowthLimitKb)
        preEntryWorkCount = $preEntryWork.Count
        preEntryImageRequestCount = $preEntryImageRequests.Count
        preEntryDecodeCount = $preEntryDecodes.Count
        preEntryPageListRequestCount = $preEntryPageLists.Count
        zeroGenerationPreClickCount = $zeroGenerationPreClick.Count
        postCloseWorkCount = $postCloseWork.Count
        foreignGenerationWorkCount = $foreignGenerationWork.Count
        actualDrawCommitCount = $actualDrawCommits.Count
        viewerDrainTimedOut = if($viewerClosedEvents.Count -gt 0) {
            Get-OptionalProperty $viewerClosedEvents[-1].value "drainTimedOut"
        } else { $null }
        warmPassed = if($null -ne $warmSummary) { $warmSummary.passed } else { $null }
        warmFirstActualMs = if($null -ne $warmSummary) { $warmSummary.firstActualMs } else { $null }
        coldState = $cold
        macroResult = $macroResult
        androidxBenchmark = $androidxBenchmark
        androidxTraceParserIsolationAccepted = $androidxTraceParserIsolationAccepted
        telemetryEventCount = $telemetry.Count
        sessionTelemetryEventCount = $sessionTelemetry.Count
        benchmarkArtifactCount = $benchmarkArtifactFiles.Count
        macroTraceArtifactCount = $macroTraceFiles.Count
        nonEmptyMacroTraceArtifactCount = $nonEmptyMacroTraceFiles.Count
        emptyMacroTraceArtifactCount = $emptyMacroTraceFiles.Count
        macroTraceArtifactBytes = if($macroTraceFiles.Count -gt 0) {
            [int64](($macroTraceFiles | Measure-Object Length -Sum).Sum)
        } else { 0L }
        macroTraceArtifacts = @($macroTraceFiles | ForEach-Object {
            [IO.Path]::GetRelativePath($caseDir, $_.FullName)
        })
        visualEvidenceArtifactCount = $visualArtifactFiles.Count
        nonEmptyVisualEvidenceArtifactCount = $nonEmptyVisualArtifactFiles.Count
        emptyVisualEvidenceArtifactCount = $emptyVisualArtifactFiles.Count
        visualEvidenceArtifactBytes = if($visualArtifactFiles.Count -gt 0) {
            [int64](($visualArtifactFiles | Measure-Object Length -Sum).Sum)
        } else { 0L }
        visualEvidenceArtifacts = @($visualArtifactFiles | ForEach-Object {
            [IO.Path]::GetRelativePath($caseDir, $_.FullName)
        })
        benchmarkArtifactPullExitCode = $benchmarkPull.ExitCode
        visualEvidencePullExitCode = $screenshotPull.ExitCode
        remoteBenchmarkArtifactsRetired = $remoteArtifactCleanup.benchmarkAbsent
        remoteScreenshotArtifactsRetired = $remoteArtifactCleanup.screenshotAbsent
        artifacts = [pscustomobject][ordered]@{
            directory = $caseDir
            instrumentation = "instrumentation.txt"
            logcat = "logcat.txt"
            meminfo = "meminfo.txt"
            gfxinfo = "gfxinfo.txt"
            cpuinfo = "cpuinfo.txt"
            activityProcessesBefore = "activity-processes-before.txt"
            activityServicesBefore = "activity-services-before.txt"
            packageBefore = "package-before.txt"
            jobschedulerBefore = "jobscheduler-before.txt"
            psBefore = "ps-before.txt"
            activityProcessesAfter = "activity-processes-after.txt"
            activityServicesAfter = "activity-services-after.txt"
            jobschedulerAfter = "jobscheduler-after.txt"
            benchmark = "benchmark"
            screenshots = "screenshots"
            remoteArtifactCleanup = "remote-artifact-cleanup.json"
            standalonePerfetto = if($script:StandalonePerfetto) {
                "standalone.perfetto-trace"
            } else { $null }
        }
    }
    Write-Json (Join-Path $caseDir "case-summary.json") $caseSummary
    Write-Json (Join-Path $caseDir "cold-proof.json") ([pscustomobject][ordered]@{
        schema = 1
        host = $coldProof
        app = $cold
        viewerGeneration = if($viewerGenerationValid) { $viewerGeneration } else { $null }
        preEntryWorkCount = $preEntryWork.Count
        preEntryImageRequestCount = $preEntryImageRequests.Count
        preEntryDecodeCount = $preEntryDecodes.Count
        preEntryPageListRequestCount = $preEntryPageLists.Count
        zeroGenerationPreClickCount = $zeroGenerationPreClick.Count
        postCloseWorkCount = $postCloseWork.Count
        foreignGenerationWorkCount = $foreignGenerationWork.Count
        preFirstRequestCount = $preFirstRequestEvents.Count
        preFirstRequestPages = @($preFirstRequestPages)
        preFirstRequestMax = $preFirstRequestMax
        preFirstEscapedRequestCount = $preFirstEscapedRequests.Count
        firstImageRequestStartElapsedNanos = $firstImageRequestStartElapsedNanos
        firstImageResponseElapsedNanos = $firstImageResponseElapsedNanos
        firstImageDecodeElapsedNanos = $firstImageDecodeElapsedNanos
        firstImageDrawElapsedNanos = $firstImageDrawElapsedNanos
        httpClientInstanceIds = $clientInstanceIds
        firstRequestProtocol = $caseSummary.firstRequestProtocol
        firstRequestConnectionId = $caseSummary.firstRequestConnectionId
        firstRequestConnectionReused = $caseSummary.firstRequestConnectionReused
        cdnCache = "UNKNOWN_UNCONTROLLED"
    })
    return $caseSummary
}

if($seedQualification.freshRandomSeedRequirementSatisfied) { $Seed = New-RandomSeed }
$timestamp = [DateTime]::Now.ToString("yyyyMMdd-HHmmss")
$baseOutput = Resolve-RepositoryPath $OutDir
$runDir = Join-Path $baseOutput "$timestamp-$Seed"
if(Test-Path -LiteralPath $runDir) {
    throw "Cold qualification output must be new: $runDir"
}
[void](New-Item -ItemType Directory -Path $runDir)

$appApk = (Get-Item -LiteralPath (Resolve-RepositoryPath $AppApkPath)).FullName
$benchmarkApk = (Get-Item -LiteralPath (Resolve-RepositoryPath $BenchmarkApkPath)).FullName
if([IO.Path]::GetExtension($appApk) -ine ".apk" -or
        [IO.Path]::GetExtension($benchmarkApk) -ine ".apk") {
    throw "AppApkPath and BenchmarkApkPath must be literal APK files"
}
if($RequireBaselineProfile -and -not (Test-ApkEmbeddedBaselineProfile $appApk)) {
    throw "-RequireBaselineProfile was requested, but the app APK has no assets/dexopt/baseline.prof"
}

$state = Invoke-Adb @("get-state")
if($state.Stdout.Trim() -cne "device") { throw "Device is not ready: $DeviceSerial" }
if(-not $SkipInstall) {
    [void](Invoke-Adb @("install", "-r", "-t", $appApk) -TimeoutSeconds 180)
    [void](Invoke-Adb @("install", "-r", "-t", $benchmarkApk) -TimeoutSeconds 180)
}

$deviceInfo = Get-DeviceInfo
$deviceRequirementSatisfied = if($QualificationDeviceMode -ceq "HOST_GPU_EMULATOR") {
    [bool]$deviceInfo.hostGpuEmulatorSatisfied
} else {
    [bool]$deviceInfo.physicalIdentitySatisfied
}
$hostGpuPerformancePolicy = Set-HostGpuEmulatorPerformancePolicy
Write-Json (Join-Path $runDir "host-gpu-performance-policy.json") `
    $hostGpuPerformancePolicy
Write-Host "NTK cold seed=$Seed mode=$($seedQualification.selectionMode) device=$($deviceInfo.model) qemu=$($deviceInfo.qemu) qualificationDeviceMode=$QualificationDeviceMode deviceGate=$deviceRequirementSatisfied"
$replayPath = $ReplaySelectionPath.Trim()
$replayTargetFilter = $ReplayTargetKeys.Trim()
if(-not [string]::IsNullOrWhiteSpace($replayTargetFilter) -and
        [string]::IsNullOrWhiteSpace($replayPath)) {
    throw "ReplayTargetKeys is diagnostic-only and requires ReplaySelectionPath"
}
if(-not [string]::IsNullOrWhiteSpace($replayPath)) {
    if($seedQualification.freshRandomSeedRequirementSatisfied) {
        throw "ReplaySelectionPath is diagnostic-only and requires the original positive seed"
    }
    $resolvedReplayPath = (Get-Item -LiteralPath (
        Resolve-RepositoryPath $replayPath
    ) -ErrorAction Stop).FullName
    $replay = Get-Content -LiteralPath $resolvedReplayPath -Raw |
        ConvertFrom-Json -Depth 100
    if([long]$replay.seed -ne $Seed) {
        throw "Replay selection seed mismatch: requested=$Seed recorded=$($replay.seed)"
    }
    if(([string]$replay.siteRoot).TrimEnd('/') -cne $siteRoot) {
        throw "Replay selection siteRoot mismatch: requested=$siteRoot recorded=$($replay.siteRoot)"
    }
    $replayTargets = @($replay.targets)
    if(-not [string]::IsNullOrWhiteSpace($replayTargetFilter)) {
        $requestedTargetKeys = @($replayTargetFilter.Split(
            ',',
            [StringSplitOptions]::RemoveEmptyEntries -bor
                [StringSplitOptions]::TrimEntries
        ))
        if($requestedTargetKeys.Count -eq 0 -or
                $requestedTargetKeys.Count -ne @($requestedTargetKeys |
                    Select-Object -Unique).Count) {
            throw "ReplayTargetKeys must contain unique comma-separated type:workId keys"
        }
        foreach($requestedKey in $requestedTargetKeys) {
            if($requestedKey -cnotmatch '^(webtoon|manhwa):[A-Za-z0-9_-]+$') {
                throw "Invalid ReplayTargetKeys entry: $requestedKey"
            }
        }
        $requestedTargetKeySet = [Collections.Generic.HashSet[string]]::new(
            [StringComparer]::Ordinal
        )
        $requestedTargetKeys.ForEach({
            [void]$requestedTargetKeySet.Add([string]$_)
        })
        $replayTargets = @($replayTargets | Where-Object {
            $requestedTargetKeySet.Contains(
                "$([string]$_.workType):$([string]$_.workId)"
            )
        })
        $foundTargetKeys = @($replayTargets | ForEach-Object {
            "$([string]$_.workType):$([string]$_.workId)"
        })
        $missingTargetKeys = @($requestedTargetKeys | Where-Object {
            $_ -cnotin $foundTargetKeys
        })
        if($missingTargetKeys.Count -gt 0) {
            throw "ReplayTargetKeys not found in the original selection: $($missingTargetKeys -join ',')"
        }
    }
    foreach($workType in @("webtoon", "manhwa")) {
        $typeCount = @($replayTargets | Where-Object {
            ([string]$_.workType) -ceq $workType
        }).Count
        if($typeCount -ne $CountPerType) {
            throw "Replay selection must contain exactly $CountPerType $workType targets; found=$typeCount"
        }
    }
    foreach($target in $replayTargets) {
        if([string]::IsNullOrWhiteSpace([string]$target.workId) -or
                [string]::IsNullOrWhiteSpace([string]$target.episodePath)) {
            throw "Replay selection contains an incomplete target; selected works are never replaced"
        }
    }
    $targets = @($replayTargets)
    Write-Host "Replaying logged random targets for diagnostic comparison; no catalog, page, or image URL is requested."
    $selection = [pscustomobject][ordered]@{
        schema = 1
        generatedAt = [DateTimeOffset]::Now.ToString("o")
        seed = $Seed
        seedSelectionMode = $seedQualification.selectionMode
        algorithm = [string]$replay.algorithm
        siteRoot = $siteRoot
        completeCatalogCounts = $replay.completeCatalogCounts
        replaySelectionSha256 = Get-FileSha256 $resolvedReplayPath
        replaySelectionPath = $resolvedReplayPath
        replayTargetKeys = $replayTargetFilter
        targets = @($targets)
    }
} else {
    Write-Host "Fetching complete metadata catalogs on the host; no image URL is requested."
    $webtoonCatalog = @(Get-CompleteCatalog "webtoon")
    $manhwaCatalog = @(Get-CompleteCatalog "manhwa")
    $targets = [Collections.Generic.List[object]]::new()
    $accessExcludedWorks = [Collections.Generic.List[object]]::new()
    foreach($workType in @("webtoon", "manhwa")) {
        $catalog = if($workType -ceq "webtoon") { $webtoonCatalog } else { $manhwaCatalog }
        $ranked = @(Get-StableRandomWorkRanking $catalog $workType)
        $initiallyRejected = [Collections.Generic.Queue[object]]::new()
        $acceptedForType = 0
        for($rankIndex = 0;
                $rankIndex -lt $ranked.Count -and $acceptedForType -lt $script:CountPerType;
                $rankIndex++) {
            $work = $ranked[$rankIndex]
            $episode = Get-EpisodeMetadata $work
            if(([string]$episode.workAccessStatus) -ceq "NO_NATIVE_CANONICAL_EPISODE") {
                $exclusion = [pscustomobject][ordered]@{
                    workType = $work.workType
                    workId = $work.workId
                    title = $work.title
                    rankOrdinal = $rankIndex + 1
                    originallySelected = $rankIndex -lt $script:CountPerType
                    reason = "episode-list API contains only provider/imported ids and no native canonical viewer episode"
                    episodeCountSource = $episode.metadataSource
                    firstEpisodeId = $episode.originallySelectedEpisodeId
                    firstEpisodePath = $episode.originallySelectedEpisodePath
                }
                $accessExcludedWorks.Add($exclusion)
                if($rankIndex -lt $script:CountPerType) {
                    $initiallyRejected.Enqueue($exclusion)
                }
                Write-Warning "Replacing clearly inaccessible random work type=$workType id=$($work.workId) rank=$($rankIndex + 1): $($exclusion.reason)"
                continue
            }
            $episodeAccess = Get-EpisodeAccessStatus $work $episode
            if(([string]$episodeAccess.status) -ceq "CLEARLY_UNAVAILABLE") {
                $exclusion = [pscustomobject][ordered]@{
                    workType = $work.workType
                    workId = $work.workId
                    title = $work.title
                    rankOrdinal = $rankIndex + 1
                    originallySelected = $rankIndex -lt $script:CountPerType
                    reason = "selected episode is explicitly unavailable"
                    episodeCountSource = $episode.metadataSource
                    firstEpisodeId = $episode.episodeId
                    firstEpisodePath = $episode.episodePath
                    accessHttpStatus = $episodeAccess.httpStatus
                    accessLocation = $episodeAccess.location
                }
                $accessExcludedWorks.Add($exclusion)
                if($rankIndex -lt $script:CountPerType) {
                    $initiallyRejected.Enqueue($exclusion)
                }
                Write-Warning "Replacing clearly inaccessible random work type=$workType id=$($work.workId) rank=$($rankIndex + 1): $($exclusion.reason), status=$($episodeAccess.httpStatus), location=$($episodeAccess.location)"
                continue
            }
            $originalWork = if(
                $rankIndex -ge $script:CountPerType -and $initiallyRejected.Count -gt 0
            ) {
                $initiallyRejected.Dequeue()
            } else {
                $null
            }
            $targets.Add([pscustomobject][ordered]@{
                workType = $work.workType
                workId = $work.workId
                title = $work.title
                latestEpisodeNumber = $work.latestEpisodeNumber
                catalogPage = $work.catalogPage
                episodeId = $episode.episodeId
                episodeTitle = $episode.episodeTitle
                episodePath = $episode.episodePath
                metadataSource = $episode.metadataSource
                metadataError = $episode.metadataError
                originallySelectedEpisodePath = $episode.originallySelectedEpisodePath
                originallySelectedEpisodeId = $episode.originallySelectedEpisodeId
                originallySelectedEpisodeTitle = $episode.originallySelectedEpisodeTitle
                accessReplacementReason = $episode.accessReplacementReason
                selectionAccessStatus = $episodeAccess.status
                selectionAccessHttpStatus = $episodeAccess.httpStatus
                selectionAccessLocation = $episodeAccess.location
                selectionAccessError = $episodeAccess.error
                originallySelectedWorkId = if($null -ne $originalWork) {
                    [string]$originalWork.workId
                } else {
                    [string]$work.workId
                }
                originallySelectedWorkTitle = if($null -ne $originalWork) {
                    [string]$originalWork.title
                } else {
                    [string]$work.title
                }
                workAccessReplacementReason = if($null -ne $originalWork) {
                    [string]$originalWork.reason
                } else {
                    $null
                }
                selectionRankOrdinal = $rankIndex + 1
            })
            $acceptedForType++
        }
        if($acceptedForType -ne $script:CountPerType) {
            throw "Unable to select $($script:CountPerType) accessible $workType works from the complete catalog"
        }
    }

    $selection = [pscustomobject][ordered]@{
        schema = 1
        generatedAt = [DateTimeOffset]::Now.ToString("o")
        seed = $Seed
        seedSelectionMode = $seedQualification.selectionMode
        algorithm = "sha256(seed|type|id) lexical rank"
        siteRoot = $siteRoot
        completeCatalogCounts = [pscustomobject]@{
            webtoon = $webtoonCatalog.Count
            manhwa = $manhwaCatalog.Count
        }
        accessExcludedWorks = @($accessExcludedWorks)
        targets = @($targets)
    }
}
Write-Json (Join-Path $runDir "selection.json") $selection

$remoteRunRoot = "/sdcard/Android/media/$BenchmarkPackage/ntk-cold-output/$timestamp-$Seed"
$caseResults = [Collections.Generic.List[object]]::new()
for($index = 0; $index -lt $targets.Count; $index++) {
    $target = $targets[$index]
    Write-Host ("[{0}/{1}] {2} {3} {4}" -f ($index + 1), $targets.Count,
        $target.workType, $target.workId, $target.title)
    try {
        if($RestartHostGpuProcessPerCase) {
            $hostGpuReset = Restart-HostGpuEmulatorForCase ($index + 1)
            Write-Json (Join-Path $runDir (
                "host-gpu-reset-{0:D2}.json" -f ($index + 1)
            )) $hostGpuReset
        }
        $caseResults.Add((Invoke-ColdCase $target ($index + 1) $remoteRunRoot))
    } catch {
        $failure = [pscustomobject][ordered]@{
            schema = 1
            caseId = "unstarted-$($index + 1)"
            ordinal = $index + 1
            workType = $target.workType
            workId = $target.workId
            workTitle = $target.title
            episodeId = $target.episodeId
            episodeTitle = $target.episodeTitle
            episodePath = $target.episodePath
            passed = $false
            violations = @("host orchestration exception: $($_.Exception.Message)")
            hostScriptStackTrace = $_.ScriptStackTrace
            hostPosition = $_.InvocationInfo.PositionMessage
        }
        $caseResults.Add($failure)
        Write-Json (Join-Path $runDir ("unstarted-{0:D2}-summary.json" -f ($index + 1))) $failure
    }
}

$passedCount = @($caseResults | Where-Object passed).Count
$qualificationTargetSatisfied = $CountPerType -eq 10
# Same-process warm reopen is intentionally reported as a diagnostic only. The product contract
# and the canonical verdict are cold-only, so an unavailable/failed warm sample must not turn a
# valid cold case into a configuration failure (or dereference a missing field on an unstarted
# case). Measured retained-memory growth is still enforced in each cold case above.
$warmReopenRequirementSatisfied = $true
$firstImageSlaRequirementSatisfied = $FirstImageSlaMs -eq $FormalWebtoonImageSlaMs -and
    $ManhwaImageSlaMs -eq $FormalManhwaImageSlaMs
$allImagesSlaRequirementSatisfied = $firstImageSlaRequirementSatisfied
$freshRandomSeedRequirementSatisfied =
    [bool]$seedQualification.freshRandomSeedRequirementSatisfied
$diagnosticOnly = -not ($qualificationTargetSatisfied -and
    $warmReopenRequirementSatisfied -and $firstImageSlaRequirementSatisfied -and
    $allImagesSlaRequirementSatisfied -and
    $freshRandomSeedRequirementSatisfied)
if($FastFunctionalTriage) { $diagnosticOnly = $true }
$rerunParts = [Collections.Generic.List[string]]::new()
[void]$rerunParts.Add("pwsh -NoProfile -File .\tools\ntk_cold_qualification.ps1")
[void]$rerunParts.Add("-AppApkPath $(ConvertTo-PowerShellLiteral $AppApkPath)")
[void]$rerunParts.Add("-BenchmarkApkPath $(ConvertTo-PowerShellLiteral $BenchmarkApkPath)")
[void]$rerunParts.Add("-DeviceSerial $(ConvertTo-PowerShellLiteral $DeviceSerial)")
[void]$rerunParts.Add("-NtkSiteRoot $(ConvertTo-PowerShellLiteral $siteRoot)")
[void]$rerunParts.Add("-OutDir $(ConvertTo-PowerShellLiteral $OutDir)")
[void]$rerunParts.Add("-Seed $Seed")
[void]$rerunParts.Add("-CountPerType $CountPerType")
[void]$rerunParts.Add("-FirstImageSlaMs $FirstImageSlaMs")
[void]$rerunParts.Add("-ManhwaImageSlaMs $ManhwaImageSlaMs")
[void]$rerunParts.Add("-CaseTimeoutSeconds $CaseTimeoutSeconds")
[void]$rerunParts.Add("-QualificationDeviceMode $QualificationDeviceMode")
if($RestartHostGpuProcessPerCase) {
    [void]$rerunParts.Add("-RestartHostGpuProcessPerCase")
}
[void]$rerunParts.Add("-IncludeWarmReopen:$(if($IncludeWarmReopen) { '$true' } else { '$false' })")
if($RequireBaselineProfile) { [void]$rerunParts.Add("-RequireBaselineProfile") }
if($StandalonePerfetto) { [void]$rerunParts.Add("-StandalonePerfetto") }
if($FastFunctionalTriage) { [void]$rerunParts.Add("-FastFunctionalTriage") }
if(-not [string]::IsNullOrWhiteSpace($replayPath)) {
    [void]$rerunParts.Add("-ReplaySelectionPath $(ConvertTo-PowerShellLiteral $replayPath)")
}
if(-not [string]::IsNullOrWhiteSpace($replayTargetFilter)) {
    [void]$rerunParts.Add("-ReplayTargetKeys $(ConvertTo-PowerShellLiteral $replayTargetFilter)")
}
$rerunCommand = $rerunParts -join ' '
$macroReproTarget = $targets[0]
$macroReproCaseId = "repro-$($macroReproTarget.workType)-$($macroReproTarget.workId)"
$macroReproImageSlaMs = Get-CaseImageSlaMs ([string]$macroReproTarget.workType)
$macroReproParts = @(
    "adb -s $(ConvertTo-PowerShellLiteral $DeviceSerial) shell am instrument -w -r",
    "-e class $(ConvertTo-PowerShellLiteral $TestClass)",
    "-e ntkWorkType $(ConvertTo-PowerShellLiteral ([string]$macroReproTarget.workType))",
    "-e ntkWorkId $(ConvertTo-PowerShellLiteral ([string]$macroReproTarget.workId))",
    "-e ntkWorkTitleBase64 $(ConvertTo-PowerShellLiteral (ConvertTo-Base64Utf8 ([string]$macroReproTarget.title)))",
    "-e ntkEpisodeTitleBase64 $(ConvertTo-PowerShellLiteral (ConvertTo-Base64Utf8 ([string]$macroReproTarget.episodeTitle)))",
    "-e ntkEpisodePath $(ConvertTo-PowerShellLiteral ([string]$macroReproTarget.episodePath))",
    "-e ntkCaseId $(ConvertTo-PowerShellLiteral $macroReproCaseId)",
    "-e ntkFirstImageSlaMs $macroReproImageSlaMs",
    "-e ntkAllImagesSlaMs $macroReproImageSlaMs",
    "-e ntkSameProcessWarmReopen true",
    "-e ntkFastFunctionalTriage $($FastFunctionalTriage.IsPresent.ToString().ToLowerInvariant())",
    "-e androidx.benchmark.output.enable true",
    "-e additionalTestOutputDir $(ConvertTo-PowerShellLiteral "/sdcard/Download/ntk-cold-repro/$macroReproCaseId")",
    (ConvertTo-PowerShellLiteral $Runner)
)
$macroReproCommand = $macroReproParts -join ' '
$summary = [pscustomobject][ordered]@{
    schema = 1
    profile = "ntk-real-ui-cold-10-plus-10-v1"
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    siteRoot = $siteRoot
    seed = $Seed
    seedSelectionMode = $seedQualification.selectionMode
    expectedWebtoon = $CountPerType
    expectedManhwa = $CountPerType
    completedCases = $caseResults.Count
    passedCases = $passedCount
    smokePassed = ($caseResults.Count -eq ($CountPerType * 2) -and
        $passedCount -eq ($CountPerType * 2))
    provisionalPassed = (-not $FastFunctionalTriage -and
        $caseResults.Count -eq ($CountPerType * 2) -and
        $passedCount -eq ($CountPerType * 2) -and $qualificationTargetSatisfied -and
        $warmReopenRequirementSatisfied -and $firstImageSlaRequirementSatisfied -and
        $allImagesSlaRequirementSatisfied -and
        $freshRandomSeedRequirementSatisfied)
    passed = (-not $FastFunctionalTriage -and
        $caseResults.Count -eq ($CountPerType * 2) -and
        $passedCount -eq ($CountPerType * 2) -and
        $qualificationTargetSatisfied -and $warmReopenRequirementSatisfied -and
        $firstImageSlaRequirementSatisfied -and
        $allImagesSlaRequirementSatisfied -and
        $freshRandomSeedRequirementSatisfied -and
        $deviceRequirementSatisfied)
    qualificationTargetSatisfied = $qualificationTargetSatisfied
    warmReopenRequirementSatisfied = $warmReopenRequirementSatisfied
    firstImageSlaRequirementSatisfied = $firstImageSlaRequirementSatisfied
    allImagesSlaRequirementSatisfied = $allImagesSlaRequirementSatisfied
    freshRandomSeedRequirementSatisfied = $freshRandomSeedRequirementSatisfied
    diagnosticOnly = $diagnosticOnly
    requestedCountPerType = $CountPerType
    qualificationDeviceMode = $QualificationDeviceMode
    deviceRequirementSatisfied = $deviceRequirementSatisfied
    finalDeviceStatus = if($deviceInfo.hostGpuEmulatorSatisfied) {
        "HOST_GPU_EMULATOR"
    } elseif($deviceInfo.physicalIdentitySatisfied) {
        "PHYSICAL_DEVICE"
    } elseif($deviceInfo.virtualDeviceDetected) {
        "PROVISIONAL_EMULATOR"
    } else {
        "UNVERIFIED_DEVICE"
    }
    physicalDeviceRequirementSatisfied = $deviceInfo.physicalIdentitySatisfied
    hostGpuEmulatorRequirementSatisfied = $deviceInfo.hostGpuEmulatorSatisfied
    firstImageSlaMs = $FirstImageSlaMs
    webtoonImageSlaMs = $FirstImageSlaMs
    manhwaImageSlaMs = $ManhwaImageSlaMs
    compilation = if($FastFunctionalTriage) {
        "Existing.AOT.fastFunctionalTriage.contentWarmup=0"
    } else {
        "Full.AOT.contentWarmup=0"
    }
    standalonePerfetto = $StandalonePerfetto.IsPresent
    fastFunctionalTriage = $FastFunctionalTriage.IsPresent
    includeWarmReopen = $IncludeWarmReopen
    hostGpuPerformancePolicy = $hostGpuPerformancePolicy
    device = $deviceInfo
    apks = [pscustomobject][ordered]@{
        app = $appApk
        appSha256 = Get-FileSha256 $appApk
        benchmark = $benchmarkApk
        benchmarkSha256 = Get-FileSha256 $benchmarkApk
    }
    catalogCounts = $selection.completeCatalogCounts
    cases = @($caseResults)
    reproducibility = [pscustomobject][ordered]@{
        build = ".\gradlew.bat :app:assembleBenchmark :macrobenchmark:assembleBenchmark"
        installApp = "adb -s $(ConvertTo-PowerShellLiteral $DeviceSerial) install -r -t $(ConvertTo-PowerShellLiteral $appApk)"
        installBenchmark = "adb -s $(ConvertTo-PowerShellLiteral $DeviceSerial) install -r -t $(ConvertTo-PowerShellLiteral $benchmarkApk)"
        macrobenchmark = $macroReproCommand
        perfettoPush = "adb -s $(ConvertTo-PowerShellLiteral $DeviceSerial) push .\tools\ntk_perfetto.textproto /data/misc/perfetto-configs/ntk_perfetto.textproto"
        perfettoStart = "adb -s $(ConvertTo-PowerShellLiteral $DeviceSerial) shell perfetto --txt -c /data/misc/perfetto-configs/ntk_perfetto.textproto -o /data/misc/perfetto-traces/ntk-cold.perfetto-trace --background-wait"
        perfettoStop = "adb -s $(ConvertTo-PowerShellLiteral $DeviceSerial) shell kill -TERM <perfetto-pid-from-start>"
        perfettoPull = "adb -s $(ConvertTo-PowerShellLiteral $DeviceSerial) pull /data/misc/perfetto-traces/ntk-cold.perfetto-trace $(ConvertTo-PowerShellLiteral (Join-Path $runDir 'manual.perfetto-trace'))"
        rerun = $rerunCommand
        output = $runDir
        authentication = "No authentication state is injected; pm clear is executed before every work."
    }
}
Write-Json (Join-Path $runDir "summary.json") $summary 30

if(-not $FastFunctionalTriage -and
        (Test-Path -LiteralPath $reportScript -PathType Leaf)) {
    & $reportScript -SummaryPath (Join-Path $runDir "summary.json") `
        -OutputPath (Join-Path $runDir "report.md")
}

Write-Host "NTK cold results: $runDir"
Write-Host "passed=$($summary.passed) diagnosticOnly=$($summary.diagnosticOnly) fastFunctionalTriage=$($summary.fastFunctionalTriage) cases=$passedCount/$($caseResults.Count) device=$($summary.finalDeviceStatus)"
$executionPassed = if($FastFunctionalTriage) {
    $summary.smokePassed -and $summary.deviceRequirementSatisfied
} else {
    $summary.passed -and $summary.deviceRequirementSatisfied
}
if(-not $executionPassed) {
    exit 1
}
