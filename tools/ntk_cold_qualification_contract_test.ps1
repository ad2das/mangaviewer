#requires -Version 7.2

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$qualificationScript = Join-Path $PSScriptRoot "ntk_cold_qualification.ps1"
$finalQualificationScript = Join-Path $PSScriptRoot "ntk_final_qualification.ps1"
$hostGpuQualificationScript = Join-Path $PSScriptRoot "ntk_emulator_host_qualification.ps1"
$reportScript = Join-Path $PSScriptRoot "ntk_cold_report.ps1"
$schemaFile = Join-Path $PSScriptRoot "ntk_cold_result.schema.json"
$macroSourceFile = Join-Path (Split-Path $PSScriptRoot -Parent) `
    "macrobenchmark/src/main/java/ml/melun/mangaview/macrobenchmark/NtkColdViewerMacrobenchmark.kt"
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    $qualificationScript,
    [ref]$tokens,
    [ref]$parseErrors)
if($parseErrors.Count -ne 0) {
    throw "Qualification script has parser errors: $($parseErrors.Message -join '; ')"
}
$finalTokens = $null
$finalParseErrors = $null
[void][Management.Automation.Language.Parser]::ParseFile(
    $finalQualificationScript,
    [ref]$finalTokens,
    [ref]$finalParseErrors)
if($finalParseErrors.Count -ne 0) {
    throw "Final qualification script has parser errors: $($finalParseErrors.Message -join '; ')"
}
$hostTokens = $null
$hostParseErrors = $null
[void][Management.Automation.Language.Parser]::ParseFile(
    $hostGpuQualificationScript,
    [ref]$hostTokens,
    [ref]$hostParseErrors)
if($hostParseErrors.Count -ne 0) {
    throw "Host-GPU qualification script has parser errors: $($hostParseErrors.Message -join '; ')"
}

$source = [IO.File]::ReadAllText($qualificationScript)
$finalSource = [IO.File]::ReadAllText($finalQualificationScript)
$hostSource = [IO.File]::ReadAllText($hostGpuQualificationScript)
$reportSource = [IO.File]::ReadAllText($reportScript)
$macroSource = [IO.File]::ReadAllText($macroSourceFile)
$schema = [IO.File]::ReadAllText($schemaFile) | ConvertFrom-Json
function Assert-SourceContains([string]$Needle) {
    if(-not $source.Contains($Needle, [StringComparison]::Ordinal)) {
        throw "Qualification contract token missing: $Needle"
    }
}

function Assert-FinalSourceContains([string]$Needle) {
    if(-not $finalSource.Contains($Needle, [StringComparison]::Ordinal)) {
        throw "Final qualification contract token missing: $Needle"
    }
}

function Assert-HostSourceContains([string]$Needle) {
    if(-not $hostSource.Contains($Needle, [StringComparison]::Ordinal)) {
        throw "Host-GPU qualification contract token missing: $Needle"
    }
}

Assert-FinalSourceContains 'if($Seed -ne 0L)'
Assert-FinalSourceContains 'Canonical final qualification requires Seed=0'
Assert-HostSourceContains 'if($Seed -ne 0L)'
Assert-HostSourceContains 'CountPerType = 10'
Assert-HostSourceContains 'FirstImageSlaMs = 4000'
Assert-HostSourceContains 'ManhwaImageSlaMs = 4000'
Assert-HostSourceContains 'QualificationDeviceMode = "HOST_GPU_EMULATOR"'
Assert-HostSourceContains 'IncludeWarmReopen = $true'

foreach($formalGatePattern in @(
        '(?s)\$diagnosticOnly\s*=.*?\$freshRandomSeedRequirementSatisfied\)',
        '(?sm)^\s{4}provisionalPassed\s*=.*?\$freshRandomSeedRequirementSatisfied\)',
        '(?sm)^\s{4}passed\s*=.*?\$freshRandomSeedRequirementSatisfied\s+-and')) {
    if($source -notmatch $formalGatePattern) {
        throw "Positive-seed diagnostic gate missing: $formalGatePattern"
    }
}
if(-not (@($schema.required) -ccontains "seedSelectionMode") -or
        -not (@($schema.required) -ccontains "freshRandomSeedRequirementSatisfied")) {
    throw "Seed qualification provenance is not required by the result schema"
}
foreach($deviceField in @(
        "qualificationDeviceMode",
        "deviceRequirementSatisfied",
        "hostGpuEmulatorRequirementSatisfied")) {
    if(-not (@($schema.required) -ccontains $deviceField)) {
        throw "Selected device qualification provenance is not required: $deviceField"
    }
}
if(-not (@($schema.properties.qualificationDeviceMode.enum) -ccontains
            "HOST_GPU_EMULATOR")) {
    throw "Result schema does not support formal host-GPU emulator qualification"
}
if(-not (@($schema.properties.seedSelectionMode.enum) -ccontains "FRESH_RANDOM") -or
        -not (@($schema.properties.seedSelectionMode.enum) -ccontains "FIXED_SEED_REPRODUCTION")) {
    throw "Result schema does not distinguish fresh random selection from fixed-seed reproduction"
}
if(-not $reportSource.Contains(
        'freshRandomSeedRequirementSatisfied mismatch',
        [StringComparison]::Ordinal)) {
    throw "Report contract does not recompute the fresh-random seed requirement"
}
if(-not $macroSource.Contains('const val MAX_EDGE_GESTURES = 500', [StringComparison]::Ordinal)) {
    throw "Cold qualification cannot traverse the longest canonical episodes"
}
if(([regex]::Matches(
            $source,
            [regex]::Escape('} elseif($androidxBenchmark.valid) {'))).Count -ne 1) {
    throw "Isolated AndroidX trace-parser failures must not validate absent benchmarkData metrics"
}
if(-not $macroSource.Contains(
        'TraceSectionMetric("ViewerHwuiFrameCommit", TraceSectionMetric.Mode.Max)',
        [StringComparison]::Ordinal) -or
        -not $macroSource.Contains(
            'TraceSectionMetric("ViewerSurfaceControlLatch", TraceSectionMetric.Mode.Max)',
            [StringComparison]::Ordinal) -or
        $macroSource.Contains(
            'TraceSectionMetric("ViewerFramePresent",',
            [StringComparison]::Ordinal)) {
    throw "Renderer-specific frame-commit trace coverage regressed"
}
if(-not $macroSource.Contains(
        'TraceSectionMetric("ViewerAllImagesReady", TraceSectionMetric.Mode.First)',
        [StringComparison]::Ordinal) -or
        -not $source.Contains('ViewerAllImagesReadyFirstMs', [StringComparison]::Ordinal) -or
        -not $source.Contains('allImagesReadyPageCount -ne $authoritativePageCount',
            [StringComparison]::Ordinal)) {
    throw "All-canonical-images type SLA is not fail-closed across trace and manifest evidence"
}
if(-not $source.Contains('ViewerHwuiFrameCommitMaxMs', [StringComparison]::Ordinal) -or
        -not $source.Contains('ViewerSurfaceControlLatchMaxMs', [StringComparison]::Ordinal) -or
        $source.Contains('ViewerFramePresentMaxMs', [StringComparison]::Ordinal)) {
    throw "Qualification report does not accept the renderer-specific commit routes"
}
if($source.Contains('pre-first request escaped bounded runway', [StringComparison]::Ordinal)) {
    throw "All-image cold loading was incorrectly restricted to a two-page startup runway"
}
if($source.Contains('viewer exit retained excessive process memory', [StringComparison]::Ordinal)) {
    throw "Single-session allocator PSS retention was incorrectly treated as a leak"
}
if($source.Contains('Get-Sha256 "$script:Seed|$($Work.workType)|$($Work.workId)|$($_.episodeId)"',
        [StringComparison]::Ordinal) -or
        -not $source.Contains('$original = $episodes[0]', [StringComparison]::Ordinal) -or
        -not $source.Contains('([string]$original.episodeId) -notmatch $nativeEpisodePattern',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('originallySelectedEpisodeId = [string]$original.episodeId',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('accessReplacementReason = $accessReplacementReason',
            [StringComparison]::Ordinal)) {
    throw "Random work selection must retain canonical episode evidence and replace only a provider-gated non-native row"
}
if(([regex]::Matches($macroSource, '(?m)^\s*startActivityAndWait\(\)\s*$')).Count -ne 1) {
    throw "Cold qualification must launch the target exactly once"
}
foreach($nonForwardScenario in @(
        'resumeExistingTaskFromLauncher(',
        'setOrientationLeft()',
        'setOrientationNatural()',
        'unfreezeRotation()')) {
    if($macroSource.Contains($nonForwardScenario, [StringComparison]::Ordinal)) {
        throw "Macrobenchmark must end at the forward bottom edge: $nonForwardScenario"
    }
}
if(-not $macroSource.Contains('forwardTraversalGestureCount', [StringComparison]::Ordinal) -or
        $macroSource.Contains('reverse-top', [StringComparison]::Ordinal) -or
        $macroSource.Contains('DIRECTION_SWITCH_COUNT', [StringComparison]::Ordinal) -or
        $macroSource.Contains('MANGA_PAGE_SWITCH_COUNT', [StringComparison]::Ordinal)) {
    throw "Macrobenchmark must exercise only continuous forward reader traversal"
}

# These ceilings must remain production constants rather than caller-controlled SLA knobs.
$parameterNames = @($ast.ParamBlock.Parameters | ForEach-Object {
    $_.Name.VariablePath.UserPath
})
foreach($forbiddenParameter in @(
        "ProductionMaxActiveRequestQueue",
        "ProductionMinForwardGestures",
        "ProductionMaxForwardGestures",
        "ProductionWarmRetainedPssFloorLimitKb",
        "ProductionWarmRetainedPssRatioLimit")) {
    if($parameterNames -ccontains $forbiddenParameter) {
        throw "Production safety ceiling became caller controlled: $forbiddenParameter"
    }
}

foreach($requiredToken in @(
        'freshRandomSeedRequirementSatisfied = $freshRandom',
        'if($seedQualification.freshRandomSeedRequirementSatisfied) { $Seed = New-RandomSeed }',
        'seedSelectionMode = $seedQualification.selectionMode',
        '$freshRandomSeedRequirementSatisfied)',
        'goldfish|ranchu|generic',
        'sdk_gphone',
        'virtualDeviceDetected = $virtualDeviceDetected',
        'physicalIdentitySatisfied = ($positivePhysicalIdentity -and -not $virtualDeviceDetected)',
        'Android Emulator OpenGL ES Translator',
        'SwiftShader|llvmpipe|softpipe|software rasterizer',
        'hostGpuEmulatorSatisfied = $hostGpuEmulatorSatisfied',
        '$deviceRequirementSatisfied)',
        'hostGpuEmulatorRequirementSatisfied = $deviceInfo.hostGpuEmulatorSatisfied',
        'Get-PackageUid $packageBefore.Text',
        'target UID or package secondary process existed before launch',
        'target running service existed before viewer click',
        'target running job or WorkManager work existed before viewer click',
        '"--no-isolated-storage"',
        '"benchmark-pull.txt"',
        '$benchmarkPull.ExitCode -ne 0',
        '$nonEmptyMacroTraceFiles.Count -eq 0',
        '$AndroidxPerfettoTraceOutput = "/data/misc/perfetto-traces/trace_output.pb"',
        'Reset-AndroidxPerfettoTraceOutput "before-case"',
        'Reset-AndroidxPerfettoTraceOutput "after-instrumentation"',
        'never use a broad killall/pkill',
        'stale AndroidX Perfetto trace output could not be retired before case',
        'AndroidX Perfetto trace output could not be retired after instrumentation',
        'Remove-RemoteCaseArtifacts',
        'remote benchmark artifacts could not be retired after host pull',
        'remote screenshot artifacts could not be retired after host pull',
        'Refusing post-pull cleanup outside exact qualification case paths',
        '$screenshotPull.ExitCode -ne 0',
        '$nonEmptyVisualArtifactFiles.Count -eq 0',
        'warm reopen retained-PSS growth exceeded the production limit',
        '$requestQueueMetrics.peakActive -gt $script:ProductionMaxActiveRequestQueue',
        '$forwardTraversalGestureCount -ge $script:ProductionMinForwardGestures',
        '$forwardTraversalGestureCount -le $script:ProductionMaxForwardGestures',
        'continuous forward traversal metrics were missing or invalid',
        'image requests were cancelled during continuous forward reading')) {
    Assert-SourceContains $requiredToken
}
if($source.Contains('warm reopen retained-PSS growth was unmeasured',
        [StringComparison]::Ordinal)) {
    throw "Optional warm diagnostic availability must not gate the cold verdict"
}
Assert-SourceContains '$warmReopenRequirementSatisfied = $true'
if(-not $reportSource.Contains('$warmSatisfied = $true', [StringComparison]::Ordinal)) {
    throw "Report contract must keep warm reopen outside the cold verdict"
}

# Exercise the fail-closed text parsers without dot-sourcing the adb orchestrator.
$helperNames = @(
    "Get-SeedQualificationState",
    "Get-OptionalProperty",
    "Get-PackageUid",
    "Get-PreClickProcessState",
    "Get-PreClickServiceState",
    "Get-PreClickJobState",
    "Get-RequestQueueMetrics",
    "Get-MaxTelemetryBurst")
foreach($helperName in $helperNames) {
    $definition = $ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq $helperName
    }, $true) | Select-Object -First 1
    if($null -eq $definition) { throw "Helper definition missing: $helperName" }
    Invoke-Expression $definition.Extent.Text
}

$freshSeedState = Get-SeedQualificationState 0
if($freshSeedState.selectionMode -cne "FRESH_RANDOM" -or
        -not $freshSeedState.freshRandomSeedRequirementSatisfied) {
    throw "Seed zero was not classified as fresh-random qualification input"
}
$reproductionSeedState = Get-SeedQualificationState 42
if($reproductionSeedState.selectionMode -cne "FIXED_SEED_REPRODUCTION" -or
        $reproductionSeedState.freshRandomSeedRequirementSatisfied) {
    throw "Positive seed reproduction was not classified as diagnostic-only input"
}

$script:AppPackage = "ml.melun.mangaview"
$script:BenchmarkPackage = "ml.melun.mangaview.macrobenchmark"
$packageDump = "Packages:`n  Package [ml.melun.mangaview]:`n    userId=10123"
$uid = Get-PackageUid $packageDump
if($uid -ne 10123L) { throw "Package UID parser rejected a valid userId" }
$android15PackageDump = "Packages:`n  appId=10228`n  pkg=Package{abc ml.melun.mangaview}"
$android15Uid = Get-PackageUid $android15PackageDump
if($android15Uid -ne 10228L) { throw "Package UID parser rejected Android 15 appId" }
$processState = Get-PreClickProcessState ([pscustomobject]@{
    ExitCode = 0
    Text = @"
UID PID NAME ARGS
10123 11 ml.melun.mangaview:reader ml.melun.mangaview:reader
10123 12 com.example.sameuid com.example.sameuid
10124 13 ml.melun.mangaview.macrobenchmark ml.melun.mangaview.macrobenchmark
"@
}) $uid
if(-not $processState.measured -or $processState.count -ne 2) {
    throw "Target UID/secondary-process parser failed or counted the benchmark orchestrator"
}

$serviceState = Get-PreClickServiceState ([pscustomobject]@{
    ExitCode = 0
    Text = @"
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  * ServiceRecord{abc u0 ml.melun.mangaview/androidx.work.impl.background.systemjob.SystemJobService}
"@
})
if(-not $serviceState.measured -or $serviceState.count -ne 1 -or
        $serviceState.workCount -ne 1) {
    throw "Running service/WorkManager parser did not identify target work"
}

$jobState = Get-PreClickJobState ([pscustomobject]@{
    ExitCode = 0
    Text = @"
JOB SCHEDULER STATE (dumpsys jobscheduler)
Active jobs:
  running job: ml.melun.mangaview/androidx.work.impl.background.systemjob.SystemJobService
Registered jobs:
  JOB ml.melun.mangaview/.DormantJob
"@
})
if(-not $jobState.measured -or $jobState.count -ne 1 -or $jobState.workCount -ne 1) {
    throw "Running job parser included dormant work or missed active work"
}
$inactiveTimerState = Get-PreClickJobState ([pscustomobject]@{
    ExitCode = 0
    Text = @"
JOB SCHEDULER STATE (dumpsys jobscheduler)
Active jobs:
    TopAppTimer{<0>ml.melun.mangaview} NOT active
    Registered jobs:
      JOB ml.melun.mangaview/androidx.work.impl.background.systemjob.SystemJobService
"@
})
if(-not $inactiveTimerState.measured -or $inactiveTimerState.count -ne 0 -or
        $inactiveTimerState.workCount -ne 0) {
    throw "Inactive TopAppTimer or indented registered-job section was counted as running"
}

function New-RequestEvent([int]$Ordinal, [string]$Phase, [string]$Operation) {
    [pscustomobject]@{
        ordinal = $Ordinal
        value = [pscustomobject]@{
            event = "image_request"
            phase = $Phase
            operation = $Operation
        }
    }
}
$queue = Get-RequestQueueMetrics @(
    (New-RequestEvent 1 "start" "1"),
    (New-RequestEvent 2 "start" "2"),
    (New-RequestEvent 3 "cancel" "1"),
    (New-RequestEvent 4 "end" "2"))
if(-not $queue.measured -or $queue.peakActive -ne 2 -or $queue.terminalBalance -ne 0) {
    throw "Active request queue accounting failed"
}
$unbalanced = Get-RequestQueueMetrics @((New-RequestEvent 1 "start" "1"))
if($unbalanced.measured) { throw "Unbalanced request telemetry did not fail closed" }

Write-Host "NTK cold qualification contract: PASS"
