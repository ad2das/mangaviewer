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
$reportTokens = $null
$reportParseErrors = $null
[void][Management.Automation.Language.Parser]::ParseFile(
    $reportScript,
    [ref]$reportTokens,
    [ref]$reportParseErrors)
if($reportParseErrors.Count -ne 0) {
    throw "Cold report script has parser errors: $($reportParseErrors.Message -join '; ')"
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
Assert-FinalSourceContains 'CountPerType = 20'
Assert-FinalSourceContains 'AllImagesSlaMs = 6000'
Assert-HostSourceContains 'if($Seed -ne 0L)'
Assert-HostSourceContains 'CountPerType = 20'
Assert-HostSourceContains 'FirstImageSlaMs = 4000'
Assert-HostSourceContains 'ManhwaImageSlaMs = 4000'
Assert-HostSourceContains 'AllImagesSlaMs = 6000'
Assert-HostSourceContains 'QualificationDeviceMode = "HOST_GPU_EMULATOR"'
Assert-HostSourceContains 'IncludeWarmReopen = $false'

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
foreach($selectionField in @("selectionAlgorithm", "selectedEpisodePairs")) {
    if(-not (@($schema.required) -ccontains $selectionField)) {
        throw "Exact episode-pair selection provenance is not required: $selectionField"
    }
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
if(-not $source.Contains('function Get-StableRandomEpisodePairRanking',
            [StringComparison]::Ordinal) -or
        -not $source.Contains(
            'sha256(seed|type|workId|currentEpisodeId|nextEpisodeId) lexical rank',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$rankedPairs = @(Get-StableRandomEpisodePairRanking',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$pair = $rankedPair.value',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$original = $episodes[0]', [StringComparison]::Ordinal) -or
        -not $source.Contains('([string]$original.episodeId) -notmatch $nativeEpisodePattern',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('originallySelectedEpisodeId = [string]$original.episodeId',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('accessReplacementReason = $accessReplacementReason',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('[Net.Http.HttpMethod]::Head',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$handler.AllowAutoRedirect = $false',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('[Net.Http.HttpCompletionOption]::ResponseHeadersRead',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('Get-EpisodePageCountFromDocumentContent',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('"imageMetas":\[(?<items>.*?)\],"imagesToken"',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$expectedAdjacentPageCount -lt 4',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$statusCode -eq 404 -or $statusCode -eq 410',
            [StringComparison]::Ordinal) -or
        -not $source.Contains("ep_unavailable=1",
            [StringComparison]::Ordinal) -or
        -not $source.Contains('"CLEARLY_UNAVAILABLE"',
            [StringComparison]::Ordinal)) {
    throw "Random work/pair selection must hash-rank exact current-next identities, retain canonical evidence, prove four adjacent pages, and avoid image bodies"
}
if(-not $source.Contains('Replay exact episode pair mismatch for',
        [StringComparison]::Ordinal) -or
        -not $source.Contains(
            '-ReplaySelectionPath $(ConvertTo-PowerShellLiteral $selectionOutputPath)',
            [StringComparison]::Ordinal) -or
        -not $reportSource.Contains(
            'case identity does not match its recorded exact current/next episode pair',
            [StringComparison]::Ordinal)) {
    throw "Exact current/next pair recording and replay are not fail-closed"
}
$selectedPairRequired = @($schema.'$defs'.selectedEpisodePair.required)
foreach($pairField in @(
        "seed",
        "workType",
        "workId",
        "currentEpisodeId",
        "currentEpisodePath",
        "nextEpisodeId",
        "nextEpisodePath",
        "nextEpisodePageCount",
        "pairSelectionHash",
        "pairRankOrdinal",
        "pairCandidateCount")) {
    if($selectedPairRequired -cnotcontains $pairField) {
        throw "Selected episode-pair schema field is optional: $pairField"
    }
}
$schemaFixture = [pscustomobject][ordered]@{
    schema = 1
    profile = "ntk-real-ui-cold-20-plus-20-v1"
    generatedAt = "2026-01-01T00:00:00Z"
    seed = 42
    seedSelectionMode = "FIXED_SEED_REPRODUCTION"
    selectionAlgorithm =
        "work: sha256(seed|type|id) lexical rank; episode-pair: sha256(seed|type|workId|currentEpisodeId|nextEpisodeId) lexical rank"
    selectedEpisodePairs = @([pscustomobject][ordered]@{
        ordinal = 1
        seed = 42
        workType = "webtoon"
        workId = "fixture-work"
        currentEpisodeId = "100"
        currentEpisodePath = "/webtoon/fixture-work/100"
        nextEpisodeId = "101"
        nextEpisodePath = "/webtoon/fixture-work/101"
        nextEpisodePageCount = 4
        pairSelectionHash = "0" * 64
        pairRankOrdinal = 1
        pairCandidateCount = 3
    })
    expectedWebtoon = 1
    expectedManhwa = 0
    completedCases = 1
    passedCases = 0
    smokePassed = $false
    provisionalPassed = $false
    passed = $false
    finalDeviceStatus = "UNVERIFIED_DEVICE"
    qualificationDeviceMode = "HOST_GPU_EMULATOR"
    deviceRequirementSatisfied = $false
    physicalDeviceRequirementSatisfied = $false
    hostGpuEmulatorRequirementSatisfied = $false
    qualificationTargetSatisfied = $false
    warmReopenRequirementSatisfied = $true
    firstImageSlaRequirementSatisfied = $false
    allImagesSlaRequirementSatisfied = $false
    freshRandomSeedRequirementSatisfied = $false
    diagnosticOnly = $true
    requestedCountPerType = 1
    firstImageSlaMs = 5000
    webtoonImageSlaMs = 5000
    manhwaImageSlaMs = 5000
    allImagesSlaMs = 7000
    includeWarmReopen = $false
    compilation = "fixture"
    device = [pscustomobject][ordered]@{
        serial = "fixture"
        manufacturer = "fixture"
        model = "fixture"
        androidRelease = "1"
        sdk = "1"
        abi = "fixture"
        qemu = $false
        bootQemu = $false
        virtualDeviceDetected = $false
        virtualDeviceMarkers = @()
        positivePhysicalIdentity = $false
        physicalIdentitySatisfied = $false
        eglHardware = ""
        hwuiRenderer = ""
        surfaceFlingerGles = ""
        hostTranslatorDetected = $false
        softwareGpuDetected = $false
        hostGpuEmulatorSatisfied = $false
        refreshHz = 60
        networkType = "fixture"
    }
    apks = [pscustomobject][ordered]@{
        app = "fixture-app.apk"
        appSha256 = "0" * 64
        benchmark = "fixture-benchmark.apk"
        benchmarkSha256 = "1" * 64
    }
    catalogCounts = [pscustomobject]@{ webtoon = 20; manhwa = 20 }
    cases = @([pscustomobject][ordered]@{
        schema = 1
        caseId = "fixture-1"
        ordinal = 1
        workType = "webtoon"
        workId = "fixture-work"
        episodeId = "100"
        episodePath = "/webtoon/fixture-work/100"
        expectedAdjacentEpisodePath = "/webtoon/fixture-work/101"
        expectedAdjacentPageCount = 4
        passed = $false
        violations = @("fixture")
    })
    reproducibility = [pscustomobject][ordered]@{
        build = "fixture"
        installApp = "fixture"
        installBenchmark = "fixture"
        macrobenchmark = "fixture"
        perfettoPush = "fixture"
        perfettoStart = "fixture"
        perfettoStop = "fixture"
        perfettoPull = "fixture"
        rerun = "fixture"
        selection = "selection.json"
        selectionSha256 = "2" * 64
        output = "fixture"
        authentication = "fixture"
    }
}
$schemaFixtureJson = $schemaFixture | ConvertTo-Json -Depth 20
if(-not (Test-Json -Json $schemaFixtureJson -SchemaFile $schemaFile -ErrorAction Stop)) {
    throw "Exact episode-pair schema fixture did not validate"
}
if(-not $macroSource.Contains(
        'require(expectedAdjacentEpisodePath.isNotBlank())',
        [StringComparison]::Ordinal) -or
        -not $macroSource.Contains(
            'require(expectedAdjacentPageCount >= ADJACENT_REQUIRED_RUNWAY_PAGES)',
            [StringComparison]::Ordinal) -or
        $macroSource.Contains('expectedEpisodePath.isBlank()', [StringComparison]::Ordinal) -or
        -not $macroSource.Contains('runwayReadyBeforeTail', [StringComparison]::Ordinal) -or
        -not $macroSource.Contains('allRequiredDrawablesObserved',
            [StringComparison]::Ordinal) -or
        -not $macroSource.Contains('ADJACENT_BOUNDARY_WAIT_SLA_MS = 500L',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$requiredAdjacentRunwayPages = 4',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$ProductionMaxAdjacentBoundaryWaitMs = 500.0',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('"adjacentObservedRunwayDrawableCount"',
            [StringComparison]::Ordinal)) {
    throw "Forward-adjacent qualification is not fail-closed on identity/count/runway/tail timing"
}
$formalPassContract = $schema.allOf[3].then.properties
if([int]$formalPassContract.expectedWebtoon.const -ne 20 -or
        [int]$formalPassContract.expectedManhwa.const -ne 20 -or
        [int]$formalPassContract.completedCases.const -ne 40 -or
        [int]$formalPassContract.passedCases.const -ne 40 -or
        [int]$formalPassContract.allImagesSlaMs.const -ne 6000) {
    throw "Formal result schema is not fixed to random 20+20 and the 6000ms all-images SLA"
}
$passedCaseRequired = @($schema.'$defs'.case.allOf[0].then.required)
$passedCaseProperties = $schema.'$defs'.case.allOf[0].then.properties
foreach($adjacentField in @(
        "expectedAdjacentEpisodePath",
        "expectedAdjacentPageCount",
        "adjacentRunwayTargetEpisode",
        "adjacentRunwayPageCount",
        "adjacentObservedRunwayDrawableCount",
        "adjacentTotalPageCount",
        "adjacentBoundaryWaitMs",
        "runwayReadyBeforeTail",
        "adjacentAttachMs")) {
    if($passedCaseRequired -cnotcontains $adjacentField) {
        throw "Passed-case schema does not require adjacent evidence: $adjacentField"
    }
}
foreach($frameField in @(
        "activePresentedFrameCount",
        "activePresentationIntervalCount",
        "activePresentationFps",
        "activePresentationFpsTarget",
        "activePresentationJankPercent",
        "activePresentationGapMaxMs",
        "activeRefreshPeriodMs",
        "activeRefreshHz",
        "activeCpuPercent",
        "activeMainThreadRunningMaxMs")) {
    if($passedCaseRequired -cnotcontains $frameField -or
            [string]$passedCaseProperties.$frameField.type -cne "number") {
        throw "Passed-case schema permits a missing/null presentation field: $frameField"
    }
}
if($passedCaseRequired -cnotcontains "activePresentationSystemFence" -or
        [double]$passedCaseProperties.activePresentationSystemFence.const -ne 1.0 -or
        [double]$passedCaseProperties.activePresentationJankPercent.exclusiveMaximum -ne 1.0 -or
        -not $source.Contains(
            'AndroidX trace parser failed before qualifying presentation-frame metrics were emitted',
            [StringComparison]::Ordinal) -or
        -not $source.Contains(
            '[double]$androidxActivePresentationSystemFence -ne 1.0',
            [StringComparison]::Ordinal) -or
        -not $reportSource.Contains(
            'forward-scroll frame field missing:',
            [StringComparison]::Ordinal) -or
        -not $reportSource.Contains(
            'forward-scroll evidence was not a SurfaceFlinger presentation fence',
            [StringComparison]::Ordinal)) {
    throw "Presentation jank qualification is not fail-closed on non-null system-fence frames"
}
if(-not $source.Contains(
        '"-e ntkExpectedAdjacentEpisodePath $(ConvertTo-PowerShellLiteral ([string]$macroReproTarget.expectedAdjacentEpisodePath))"',
        [StringComparison]::Ordinal) -or
        -not $source.Contains(
            '"-e ntkExpectedAdjacentPageCount $([int]$macroReproTarget.expectedAdjacentPageCount)"',
            [StringComparison]::Ordinal)) {
    throw "Generated Macrobenchmark reproduction command omitted mandatory adjacent identity proof"
}
if(-not $reportSource.Contains(
        'exact four-drawable forward-adjacent proof missing',
        [StringComparison]::Ordinal) -or
        -not $reportSource.Contains(
            'forward-adjacent tail transition timing proof failed',
            [StringComparison]::Ordinal)) {
    throw "Final report does not recompute the adjacent drawable/tail contract"
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
        'image requests were cancelled during continuous forward reading',
        '[Math]::Max(0L, $pipelineRequestSucceeded - $authoritativePageCount)',
        'retryAttemptCount = if($null -ne $pipelineRequestStarted',
        '$pipelineRequestStarted - $pipelineRequestSucceeded -',
        '$pipelineRequestCancelled - $pipelineRequestFailed')) {
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
    "Get-Sha256",
    "Get-StableRandomEpisodePairRanking",
    "Get-EpisodePageCountFromDocumentContent",
    "Get-OptionalProperty",
    "Get-PackageUid",
    "Get-PreClickProcessState",
        "Get-PreClickServiceState",
        "Get-PreClickJobState",
        "Get-RequestQueueMetrics",
        "Get-ImagePipelineRequestQueueMetrics",
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

$escapedAdjacentDocument = @'
self.__next_f.push([1,"x:{\"imageMetas\":[{\"page\":1},{\"page\":2},{\"page\":3},{\"page\":4}],\"imagesToken\":\"opaque\"}"])
'@
if((Get-EpisodePageCountFromDocumentContent $escapedAdjacentDocument) -ne 4) {
    throw "Escaped adjacent structural page count was not parsed exactly"
}
$plainAdjacentDocument = '{"imageMetas":[{"page":1},{"page":2},{"page":3},{"page":4},{"page":5}],"imagesToken":"opaque"}'
if((Get-EpisodePageCountFromDocumentContent $plainAdjacentDocument) -ne 5) {
    throw "Plain adjacent structural page count was not parsed exactly"
}
$nonContiguousRejected = $false
try {
    [void](Get-EpisodePageCountFromDocumentContent `
        '{"imageMetas":[{"page":1},{"page":3},{"page":4},{"page":5}],"imagesToken":"opaque"}')
} catch {
    $nonContiguousRejected = $true
}
if(-not $nonContiguousRejected) {
    throw "Non-contiguous adjacent structural page evidence did not fail closed"
}

$script:Seed = 424242L
$pairWork = [pscustomobject]@{ workType = "webtoon"; workId = "work-7" }
function New-Episode([string]$Id) {
    return [pscustomobject]@{ episodeId = $Id; episodePath = "/webtoon/work-7/$Id" }
}
$pairCandidates = @(
    [pscustomobject]@{
        currentEpisode = New-Episode "100"
        nextEpisode = New-Episode "101"
        sourceEpisodeIndex = 1
    },
    [pscustomobject]@{
        currentEpisode = New-Episode "200"
        nextEpisode = New-Episode "201"
        sourceEpisodeIndex = 2
    },
    [pscustomobject]@{
        currentEpisode = New-Episode "300"
        nextEpisode = New-Episode "301"
        sourceEpisodeIndex = 3
    }
)
$pairRanking = @(Get-StableRandomEpisodePairRanking $pairCandidates $pairWork)
$pairRankingReversedInput = @(
    Get-StableRandomEpisodePairRanking @($pairCandidates[2..0]) $pairWork
)
if($pairRanking.Count -ne 3 -or
        (@($pairRanking.pairSelectionHash) -join ',') -cne
            (@($pairRankingReversedInput.pairSelectionHash) -join ',')) {
    throw "Episode-pair hash ranking was not stable across input list order"
}
foreach($rankedPair in $pairRanking) {
    $expectedPairHash = Get-Sha256 (
        "$script:Seed|webtoon|work-7|$([string]$rankedPair.currentEpisodeId)|" +
            "$([string]$rankedPair.nextEpisodeId)"
    )
    if([string]$rankedPair.pairSelectionHash -cne $expectedPairHash) {
        throw "Episode-pair hash omitted seed/work/current/next identity"
    }
}
$firstSeedHashes = @($pairRanking.pairSelectionHash)
$script:Seed = 424243L
$secondSeedHashes = @(
    (Get-StableRandomEpisodePairRanking $pairCandidates $pairWork).pairSelectionHash
)
if((@($firstSeedHashes | Where-Object { $_ -in $secondSeedHashes })).Count -ne 0) {
    throw "Changing the seed did not change every episode-pair rank hash"
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

function New-RequestEvent(
    [int]$Ordinal,
    [string]$Phase,
    [string]$Operation,
    [int64]$TimestampNanos = 0L
) {
    [pscustomobject]@{
        ordinal = $Ordinal
        value = [pscustomobject]@{
            event = "image_request"
            phase = $Phase
            operation = $Operation
            timestampNanos = $TimestampNanos
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

$sampledFailure = Get-RequestQueueMetrics @(
    (New-RequestEvent 1 "fail" "sampled-terminal-without-start"))
$aggregateQueue = Get-ImagePipelineRequestQueueMetrics ([pscustomobject]@{
    requestStarted = 728L
    requestSucceeded = 230L
    requestCancelled = 0L
    requestFailed = 498L
    requestActive = 0L
    requestPeakActive = 40L
    requestTerminalBalance = 0L
}) $sampledFailure
if(-not $aggregateQueue.measured -or $aggregateQueue.peakActive -ne 40L -or
        $aggregateQueue.terminalBalance -ne 0L -or $aggregateQueue.problems.Count -ne 0) {
    throw "Authoritative image pipeline queue aggregate was not preferred over sampled JSON"
}
$malformedAggregateQueue = Get-ImagePipelineRequestQueueMetrics ([pscustomobject]@{
    requestStarted = 2L
    requestSucceeded = 1L
    requestCancelled = 0L
    requestFailed = 0L
    requestActive = 0L
    requestPeakActive = 2L
    requestTerminalBalance = 0L
}) $queue
if($malformedAggregateQueue.measured -or $malformedAggregateQueue.problems.Count -eq 0) {
    throw "Unbalanced image pipeline queue aggregate did not fail closed"
}
$legacyQueue = Get-ImagePipelineRequestQueueMetrics ([pscustomobject]@{
    requestStarted = 1L
    requestSucceeded = 1L
    requestCancelled = 0L
    requestFailed = 0L
}) $queue
if($legacyQueue -ne $queue) {
    throw "Legacy image pipeline summary did not retain sampled queue compatibility"
}

$teardownBalanced = Get-RequestQueueMetrics @(
    (New-RequestEvent 1 "start" "visible" 10L),
    (New-RequestEvent 2 "cancel" "visible" 30L),
    (New-RequestEvent 3 "cancel" "sampled-tail-without-start" 31L),
    (New-RequestEvent 4 "start" "next-episode-after-traversal" 32L),
    (New-RequestEvent 5 "cancel" "next-episode-after-traversal" 33L)
) 20L
if(-not $teardownBalanced.measured -or $teardownBalanced.peakActive -ne 1 -or
        $teardownBalanced.terminalBalance -ne 0 -or
        $teardownBalanced.problems.Count -ne 0) {
    throw "Post-traversal lifecycle cancellation corrupted active-reading queue accounting"
}

Write-Host "NTK cold qualification contract: PASS"
