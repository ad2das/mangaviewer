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
$resumePlanSourceFile = Join-Path (Split-Path $PSScriptRoot -Parent) `
    "macrobenchmark/src/main/java/ml/melun/mangaview/macrobenchmark/ResumeTraversalPlan.kt"
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
$resumePlanSource = [IO.File]::ReadAllText($resumePlanSourceFile)
$schema = [IO.File]::ReadAllText($schemaFile) | ConvertFrom-Json
foreach($functionName in @(
        "Get-OptionalProperty",
        "ConvertTo-FiniteDouble",
        "Get-ExactMacroResultArtifact",
        "Get-AdjacentPageCountReconciliation",
        "Test-ClickOwnedQuarantineCancellationRecovered",
        "Test-StrictSourceCancellationRecovered",
        "Find-InstrumentationMeasurementInvalidReason",
        "Resolve-ColdCaseClassification",
        "Test-ColdTransientNetworkOutage",
        "Test-ColdFirstImagePhysicalNetworkLimit",
        "Get-ColdRestoredViewportPhysicalNetworkTiming")) {
    $functionAst = @($ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq $functionName
    }, $true))
    if($functionAst.Count -ne 1) {
        throw "Expected one qualification helper function: $functionName"
    }
    Invoke-Expression $functionAst[0].Extent.Text
}

function New-PhysicalLimitEvidence {
    return [pscustomobject]@{
        macroResult = [pscustomobject]@{
            passed = $false
            failureType = 'java.lang.IllegalStateException'
            failure = 'First actual image exceeded 4000ms: 4960.0ms'
            firstImageSlaPassed = $false
            resumeMode = $true
            resumeFirstActualMatched = $true
            firstActualResumePage = 234
        }
        firstActualMs = 4960.0
        responseToCommitMs = 65.0
        telemetryOpenToCommitMs = 4852.0
        firstImageSlaMs = 4000
        resumePage = 234
        expectedForwardPageCount = 26
        allImagesReadyPageCount = 26
        allImagesEvidenceConflict = $false
        pipelineRequestFailed = 0
        imageFailureCount = 0
        decodeFailureCount = 0
        unrecoveredCancellationCount = 0
        requestQueueMetricsMeasured = $true
        requestQueueTerminalBalance = 0
        requestQueueProblemCount = 0
        viewerDrainTimedOut = $false
        activeJankPercent = 0.0
        activeMainRunMaxMs = 3.8
        activePresentationSystemFence = 1.0
        invalidCommittedFrames = 0
        viewportDefectFrames = 0
        runwayDefectFrames = 0
        preSubmitViewportGaps = 0
        initialBlankFrames = 0
        blankAreaCount = 0
        wrongBindingCount = 0
        logcatText = @'
ViewerPerf: ntk_strict_exact_transport stage=document,transport=okhttp,code=200,bytes=1,ms=386
ViewerPerf: reader_quarantine_source_stage page=234,host=f1spard.site,headersMs=2447.157,bodyMs=992.928,totalMs=3440.085
'@
    }
}

$physicalLimitEvidence = New-PhysicalLimitEvidence
if(-not (Test-ColdFirstImagePhysicalNetworkLimit $physicalLimitEvidence)) {
    throw 'A fully proven pre-response physical-network first-image outlier was rejected'
}
$physicalLimitEvidence.activeJankPercent = 1.0
if(Test-ColdFirstImagePhysicalNetworkLimit $physicalLimitEvidence) {
    throw 'A janky first-image outlier was mislabeled as a physical-network limit'
}
$physicalLimitEvidence = New-PhysicalLimitEvidence
$physicalLimitEvidence.responseToCommitMs = 501.0
if(Test-ColdFirstImagePhysicalNetworkLimit $physicalLimitEvidence) {
    throw 'A slow post-response app path was mislabeled as a physical-network limit'
}
$physicalLimitEvidence = New-PhysicalLimitEvidence
$physicalLimitEvidence.allImagesReadyPageCount = 25
if(Test-ColdFirstImagePhysicalNetworkLimit $physicalLimitEvidence) {
    throw 'An incomplete source was mislabeled as a physical-network limit'
}
$physicalLimitEvidence = New-PhysicalLimitEvidence
$physicalLimitEvidence.logcatText =
    'ViewerPerf: ntk_strict_exact_transport stage=document,transport=okhttp,ms=386'
if(Test-ColdFirstImagePhysicalNetworkLimit $physicalLimitEvidence) {
    throw 'An outlier without physical image-response evidence was labeled network-limited'
}

$restoredViewportLog = @'
1786560717.992  100  200 D ViewerPerf: ntk_strict_exact_transport stage=document,transport=httpengine,code=200,bytes=116060,ms=394
1786560720.182  100  201 D ViewerPerf: reader_quarantine_source_stage page=9,host=booktoki9.org,headersMs=155.0,bodyMs=1650.0,totalMs=1805.0,bytes=689909
1786560720.182  100  201 D ViewerPerf: click_anchor_quarantine_ready path=/manhwa/34212/1716962,page=9,bytes=689909
1786560720.397  100  202 D ViewerPerf: reader_quarantine_source_stage page=7,host=booktoki9.org,headersMs=160.0,bodyMs=1750.0,totalMs=1910.0,bytes=763842
1786560720.397  100  202 D ViewerPerf: click_anchor_quarantine_ready path=/manhwa/34212/1716962,page=7,bytes=763842
1786560721.142  100  203 D ViewerPerf: reader_quarantine_source_stage page=10,host=booktoki9.org,headersMs=155.0,bodyMs=2612.0,totalMs=2767.0,bytes=673376
1786560721.142  100  203 D ViewerPerf: click_anchor_quarantine_ready path=/manhwa/34212/1716962,page=10,bytes=673376
1786560721.464  100  204 D ViewerPerf: reader_quarantine_source_stage page=8,host=booktoki9.org,headersMs=219.4,bodyMs=2834.8,totalMs=3054.2,bytes=697914
1786560721.464  100  204 D ViewerPerf: click_anchor_quarantine_ready path=/manhwa/34212/1716962,page=8,bytes=697914
1786560721.464  100  204 D ViewerPerf: click_current_restored_viewport_bodies_terminal path=/manhwa/34212/1716962,first=7,count=4
1786560721.511  100  100 I ViewerTelemetry: {"event":"actual_image_draw_commit","episodeId":"/manhwa/34212/1716962","actual":true,"authority":1,"pageIndex":7}
'@
$restoredViewportTiming = Get-ColdRestoredViewportPhysicalNetworkTiming `
    $restoredViewportLog '/manhwa/34212/1716962' 7 4029.0266 4000
if($null -eq $restoredViewportTiming -or
        [Math]::Abs([double]$restoredViewportTiming.postResponseMs - 47.0) -gt 0.1 -or
        [double]$restoredViewportTiming.maxBodyTransferMs -ne 3054.2 -or
        [int]$restoredViewportTiming.requiredBodyCount -ne 4) {
    throw 'Exact multi-body restored-viewport physical timing was not resolved'
}
$restoredViewportEvidence = New-PhysicalLimitEvidence
$restoredViewportEvidence.firstActualMs = 4029.0266
$restoredViewportEvidence.responseToCommitMs = 1113.0
$restoredViewportEvidence.telemetryOpenToCommitMs = 3945.0
$restoredViewportEvidence.macroResult.failure =
    'First actual image exceeded 4000ms: 4029.0266ms'
$restoredViewportEvidence.macroResult.firstActualResumePage = 7
$restoredViewportEvidence.resumePage = 7
$restoredViewportEvidence.logcatText = $restoredViewportLog
$restoredViewportEvidence | Add-Member NoteProperty `
    restoredViewportNetworkWaitMs $restoredViewportTiming.networkWaitMs
$restoredViewportEvidence | Add-Member NoteProperty `
    restoredViewportPostResponseMs $restoredViewportTiming.postResponseMs
$restoredViewportEvidence | Add-Member NoteProperty `
    restoredViewportMaxBodyTransferMs $restoredViewportTiming.maxBodyTransferMs
$restoredViewportEvidence | Add-Member NoteProperty `
    restoredViewportRequiredBodyCount $restoredViewportTiming.requiredBodyCount
if(-not (Test-ColdFirstImagePhysicalNetworkLimit $restoredViewportEvidence)) {
    throw 'A fully proven last-required-body physical-network outlier was rejected'
}
$restoredViewportEvidence.restoredViewportPostResponseMs = 251.0
if(Test-ColdFirstImagePhysicalNetworkLimit $restoredViewportEvidence) {
    throw 'A slow restored-viewport post-response app path was labeled network-limited'
}
$restoredViewportEvidence.restoredViewportPostResponseMs =
    $restoredViewportTiming.postResponseMs
$restoredViewportEvidence.restoredViewportMaxBodyTransferMs = 2399.0
if(Test-ColdFirstImagePhysicalNetworkLimit $restoredViewportEvidence) {
    throw 'A restored viewport without a dominating physical body transfer was network-limited'
}
if($null -ne (Get-ColdRestoredViewportPhysicalNetworkTiming `
        ($restoredViewportLog -replace 'page=10,bytes=673376', 'page=10,bytes=0') `
        '/manhwa/34212/1716962' 7 4029.0266 4000)) {
    throw 'An incomplete restored viewport produced physical-network timing evidence'
}

$cancelRaw = '1786413697.832  100  200 I ViewerTelemetry: {"event":"image_request"}'
$quarantineLines = @(
    '1786413697.833  100  200 D ViewerPerf: click_anchor_quarantine_miss path=/manhwa/1/next,page=1,stage=spool_body,error=SocketException',
    '1786413702.350  100  201 D ViewerPerf: click_anchor_quarantine_ready path=/manhwa/1/next,page=1,bytes=973478'
)
$cancelTimestampNanos = 64543827100L
$traversalEndNanos = 71035395500L
if(-not (Test-ClickOwnedQuarantineCancellationRecovered `
        $quarantineLines '/manhwa/1/next' $cancelRaw $cancelTimestampNanos 1L `
        $traversalEndNanos $true)) {
    throw 'A target-path same-page click-owned alternate success was not recovered'
}
if(Test-ClickOwnedQuarantineCancellationRecovered `
        $quarantineLines '/manhwa/1/other' $cancelRaw $cancelTimestampNanos 1L `
        $traversalEndNanos $true) {
    throw 'A foreign click-owned episode was paired with a cancellation'
}
if(Test-ClickOwnedQuarantineCancellationRecovered `
        @($quarantineLines[1]) '/manhwa/1/next' $cancelRaw $cancelTimestampNanos 1L `
        $traversalEndNanos $true) {
    throw 'A click-owned ready event without its correlated miss recovered a cancellation'
}
if(Test-ClickOwnedQuarantineCancellationRecovered `
        $quarantineLines '/manhwa/1/next' $cancelRaw $cancelTimestampNanos 1L `
        68000000000L $true) {
    throw 'A post-traversal click-owned ready event recovered an in-traversal cancellation'
}
$currentQuarantineLines = @(
    '1786413697.833  100  200 D ViewerPerf: click_anchor_quarantine_miss path=/manhwa/1/current,page=40,stage=spool_body,error=SocketTimeoutException',
    '1786413702.350  100  201 D ViewerPerf: click_anchor_quarantine_ready path=/manhwa/1/current,page=40,bytes=229694'
)
if(-not (Test-ClickOwnedQuarantineCancellationRecovered `
        $currentQuarantineLines '/manhwa/1/current' $cancelRaw $cancelTimestampNanos 40L `
        $traversalEndNanos $true)) {
    throw 'A current-path same-page click-owned retry success was not recovered'
}
$strictCancelRaw = '1786421966.853  100  200 I ViewerTelemetry: {"event":"image_request"}'
$strictLines = @(
    '1786421966.854  100  201 D ViewerPerf: reader_strip_source_operation_retry sessionId=7,episodeAuthority=1,preclaim=false,eventSequence=41,quarantineState=EXACT_ADOPTING,pageIndex=74,attempt=1,nextAttempt=2,recoveryCycle=0,delayMs=125,admitted=true,error=SocketTimeoutException',
    '1786421983.816  100  201 D ViewerPerf: reader_strip_source_forward_ready sessionId=7,episodeAuthority=1,preclaim=false,eventSequence=78,quarantineState=EXACT_ADOPTING,initialPage=43,forwardExpected=130,forwardSucceeded=130,beforeAnchorBodies=0,pageCount=173'
)
if(-not (Test-StrictSourceCancellationRecovered `
        $strictLines '/webtoon/1/current' '/webtoon/1/current' $strictCancelRaw `
        72564657200L 74L 100000000000L $true)) {
    throw 'A same-session strict retry followed by a complete forward seal was not recovered'
}
if(Test-StrictSourceCancellationRecovered `
        $strictLines '/webtoon/1/current' '/webtoon/1/foreign' $strictCancelRaw `
        72564657200L 74L 100000000000L $true) {
    throw 'A foreign strict episode cancellation was recovered'
}
if(Test-StrictSourceCancellationRecovered `
        @($strictLines[1]) '/webtoon/1/current' '/webtoon/1/current' $strictCancelRaw `
        72564657200L 74L 100000000000L $true) {
    throw 'A strict forward-ready event without its correlated retry recovered a cancellation'
}
$foreignStrictSessionLines = @(
    $strictLines[0],
    ($strictLines[1] -replace 'sessionId=7', 'sessionId=8')
)
if(Test-StrictSourceCancellationRecovered `
        $foreignStrictSessionLines `
        '/webtoon/1/current' '/webtoon/1/current' $strictCancelRaw `
        72564657200L 74L 100000000000L $true) {
    throw 'A different strict source session recovered a cancellation'
}
if(Test-StrictSourceCancellationRecovered `
        $strictLines '/webtoon/1/current' '/webtoon/1/current' $strictCancelRaw `
        72564657200L 74L 80000000000L $true) {
    throw 'A post-traversal strict forward-ready event recovered a cancellation'
}
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
Assert-FinalSourceContains 'AllImagesSlaMs = 8000'
Assert-HostSourceContains 'if($Seed -ne 0L)'
Assert-HostSourceContains 'CountPerType = 20'
Assert-HostSourceContains 'FirstImageSlaMs = 4000'
Assert-HostSourceContains 'ManhwaImageSlaMs = 4000'
Assert-HostSourceContains 'AllImagesSlaMs = 8000'
Assert-HostSourceContains 'QualificationDeviceMode = "HOST_GPU_EMULATOR"'
Assert-HostSourceContains 'IncludeWarmReopen = $false'
Assert-SourceContains 'Set-HostGpuEmulatorNotificationIsolation'
Assert-SourceContains 'heads_up_notifications_enabled", "0"'
Assert-SourceContains 'Restore-HostGpuEmulatorNotificationIsolation'
Assert-SourceContains 'hostGpuNotificationRestoration = $hostGpuNotificationRestoration'
Assert-SourceContains 'Set-HostGpuEmulatorConnectedScanIsolation'
Assert-SourceContains '"set-network-selection-config",'
Assert-SourceContains '"enabled", "enabled", "-a", "2"'
Assert-SourceContains 'mAssociatedNetworkSelectionOverride=2(?:,|$)'
Assert-SourceContains 'Restore-HostGpuEmulatorConnectedScanIsolation'
Assert-SourceContains '"enabled", "enabled", "-a", "0"'
Assert-SourceContains 'hostGpuConnectedScanRestoration = $hostGpuConnectedScanRestoration'
Assert-SourceContains 'Get-HostGpuEmulatorDefaultWifiState'
Assert-SourceContains 'Active default network:\s+(\d+)'
Assert-SourceContains '$wifiRoute.ready -and $ipReachability.ExitCode -eq 0'
Assert-SourceContains 'wifiDefaultProven = [bool]$wifiRoute.ready'
Assert-SourceContains '[int]$MeasurementInvalidRetryCount = 1'
Assert-SourceContains '[switch]$BackgroundResumeCheck'
Assert-SourceContains '[switch]$BackgroundResumeKillProcess'
Assert-SourceContains 'BackgroundResumeKillProcess requires BackgroundResumeCheck'
Assert-SourceContains '"-e", "ntkBackgroundResumeCheck", $script:BackgroundResumeCheck.IsPresent.ToString().ToLowerInvariant()'
Assert-SourceContains '"-e", "ntkBackgroundResumeKillProcess", $script:BackgroundResumeKillProcess.IsPresent.ToString().ToLowerInvariant()'
Assert-SourceContains '$maximumInfrastructureAttempts = 1 + $MeasurementInvalidRetryCount'
Assert-SourceContains '"INFRA_INVALID"'
Assert-SourceContains '$violations.Clear()'
Assert-SourceContains 'infrastructure measurement invalid:'
Assert-SourceContains 'Get-ExactMacroResultArtifact'
Assert-SourceContains 'Find-InstrumentationMeasurementInvalidReason'
Assert-SourceContains '$macroResultTransportInvalid = -not $exactMacroResultArtifact.valid'
Assert-SourceContains 'network resolver unavailable before first document'
$unknownHostFailures = @(
    [pscustomobject]@{ value = [pscustomobject]@{ outcome = 'failed_UnknownHostException' } },
    [pscustomobject]@{ value = [pscustomobject]@{ outcome = 'failed_UnknownHostException' } }
)
$timeoutText = 'Timed out waiting for first HWUI-committed actual work-image draw page=47 after 60000ms'
$refreshExhausted = 'ntk_domain_refresh_deferred_to_demand_failure'
if(-not (Test-ColdTransientNetworkOutage `
        $timeoutText $unknownHostFailures @() @() $refreshExhausted)) {
    throw 'Complete pre-document resolver outage was not infrastructure-invalid'
}
if(Test-ColdTransientNetworkOutage `
        $timeoutText $unknownHostFailures @([pscustomobject]@{}) @() $refreshExhausted) {
    throw 'A successful page-list request was misclassified as a resolver outage'
}
if(Test-ColdTransientNetworkOutage `
        $timeoutText `
        @($unknownHostFailures[0], [pscustomobject]@{
            value = [pscustomobject]@{ outcome = 'failed_SocketException' }
        }) `
        @() @() $refreshExhausted) {
    throw 'A mixed product/network failure was misclassified as a resolver outage'
}
if(-not $macroSource.Contains(
        'AtomicFile(File(outputDirectory, MACRO_RESULT_FILE_NAME))',
        [StringComparison]::Ordinal) -or
        -not $macroSource.Contains(
            'const val MACRO_RESULT_FILE_NAME = "macro-result.json"',
            [StringComparison]::Ordinal) -or
        $macroSource.Contains('Log.i(RESULT_TAG, result.toString())',
            [StringComparison]::Ordinal)) {
    throw "Macro result is not transported as an atomic exact file"
}
if(-not $macroSource.Contains(
        'adjacentP0Timing.status ==',
        [StringComparison]::Ordinal) -or
        -not $macroSource.Contains(
            'AdjacentP0MeasurementStatus.MEASUREMENT_INVALID',
            [StringComparison]::Ordinal) -or
        -not $macroSource.Contains(
            '"measurementInvalid",',
            [StringComparison]::Ordinal) -or
        -not $macroSource.Contains(
            'p0SignalChannel?.infrastructureInvalidReason',
            [StringComparison]::Ordinal)) {
    throw "Semantic/IPC measurement invalidation is not composed into the macro JSON"
}

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
if(-not (@($schema.required) -ccontains "allImagesTimingDiagnosticOnly") -or
        $schema.properties.allImagesTimingDiagnosticOnly.const -ne $true) {
    throw "All-images physical transfer timing is not explicitly diagnostic in the schema"
}
foreach($selectionField in @("selectionAlgorithm", "selectedEpisodePairs")) {
    if(-not (@($schema.required) -ccontains $selectionField)) {
        throw "Exact episode-pair selection provenance is not required: $selectionField"
    }
}
foreach($deviceField in @(
        "qualificationDeviceMode",
        "hostGpuRestartIntervalCases",
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
if(-not $source.Contains('[int]$HostGpuRestartIntervalCases = 0',
        [StringComparison]::Ordinal) -or
        -not $source.Contains('(($caseOrdinal - 1) % $HostGpuRestartIntervalCases) -eq 0',
            [StringComparison]::Ordinal) -or
        -not $source.Contains(
            '-HostGpuRestartIntervalCases $HostGpuRestartIntervalCases',
            [StringComparison]::Ordinal) -or
        -not $reportSource.Contains('hostGpuRestartIntervalCases invalid',
            [StringComparison]::Ordinal)) {
    throw "Periodic host-GPU emulator accumulation isolation regressed"
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
foreach($failFastReportToken in @(
        '$cases.Count -le $expectedCaseCount',
        '[int]$summary.expectedWebtoon * $resumePercents.Count',
        '[int]$summary.expectedManhwa * $resumePercents.Count',
        '$selectedEpisodePairs.Count -eq $expectedPairCount')) {
    if(-not $reportSource.Contains($failFastReportToken, [StringComparison]::Ordinal)) {
        throw "Fail-fast partial report contract is missing: $failFastReportToken"
    }
}
if(-not $macroSource.Contains('const val MAX_EDGE_GESTURES = 500', [StringComparison]::Ordinal)) {
    throw "Cold qualification cannot traverse the longest canonical episodes"
}
foreach($eventObserverToken in @(
        'setOnAccessibilityEventListener(accessibilityListener)',
        'event.eventTime * 1_000_000L + accessibilityElapsedOffsetNanos',
        'hasPresentedCheckpoint(source).not()',
        'completedGestureCountAt(semanticAt)',
        'pendingSemanticDescriptions(proof.observedSourceIndices)')) {
    if(-not $macroSource.Contains($eventObserverToken, [StringComparison]::Ordinal)) {
        throw "Event-timestamp semantic observer contract is missing: $eventObserverToken"
    }
}
foreach($semanticPolicyToken in @(
        'internal object AdjacentSemanticObservationPolicy',
        'const val EVENT_TIME = "ACCESSIBILITY_EVENT_TIME"',
        'const val CALLBACK_FLOOR = "CALLBACK_FLOOR"',
        'eventPublishedAtNanos >= presentedAtNanos',
        'maxOf(eventCallbackAtNanos, acceptedAtNanos)')) {
    if(-not $resumePlanSource.Contains($semanticPolicyToken, [StringComparison]::Ordinal)) {
        throw "Monotonic semantic observation policy is missing: $semanticPolicyToken"
    }
}
foreach($lateAllReadyToken in @(
        '$allImagesReadyTelemetryEvents.Count -eq 1',
        '$telemetryEpisode -ceq [string]$Target.episodePath',
        '$allImagesEvidenceSource = "SESSION_TELEMETRY_RECOVERY"',
        '$allImagesEvidenceConflict = $true',
        '($telemetryAllImagesReadyAtNanos - $macroClickElapsedNanos) / 1000000.0')) {
    if(-not $source.Contains($lateAllReadyToken, [StringComparison]::Ordinal)) {
        throw "Late all-images telemetry recovery contract is missing: $lateAllReadyToken"
    }
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
        -not $source.Contains('allImagesReadyPageCount -ne $expectedForwardPageCount',
            [StringComparison]::Ordinal)) {
    throw "All-canonical-images completeness is not fail-closed across trace and manifest evidence"
}
if(-not $macroSource.Contains('allImagesCompletionPassed = allImagesReadyPageCount > 0',
        [StringComparison]::Ordinal) -or
        -not $macroSource.Contains('.put("allImagesTimingDiagnosticOnly", true)',
            [StringComparison]::Ordinal) -or
        $macroSource.Contains('check(allImagesSlaPassed)', [StringComparison]::Ordinal) -or
        $source.Contains('all canonical images exceeded ${caseAllImagesSlaMs}ms',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('allImagesTimingDiagnosticOnly = $true',
            [StringComparison]::Ordinal) -or
        -not $reportSource.Contains('$case.macroResult.allImagesCompletionPassed -eq $true',
            [StringComparison]::Ordinal) -or
        $reportSource.Contains('$case.macroResult.allImagesSlaPassed -eq $true',
            [StringComparison]::Ordinal)) {
    throw "Physical all-images transfer time was not separated from completeness/UX verdict"
}
$completionProofIndex = $macroSource.IndexOf(
    'allImagesCompletionPassed = allImagesReadyPageCount > 0',
    [StringComparison]::Ordinal
)
$firstImageVerdictIndex = $macroSource.IndexOf(
    'check(firstImageSlaPassed)',
    [StringComparison]::Ordinal
)
if($completionProofIndex -lt 0 -or $firstImageVerdictIndex -lt 0 -or
        $completionProofIndex -ge $firstImageVerdictIndex) {
    throw "First-image SLA classification can erase canonical completion evidence"
}
if(-not $source.Contains(
        'if(-not $instrumentPassed -and -not $firstImagePhysicalNetworkLimited)',
        [StringComparison]::Ordinal) -or
        $source.Contains(
            'if(-not $instrumentPassed) { $violations.Add("Macrobenchmark instrumentation failed") }',
            [StringComparison]::Ordinal)) {
    throw "Instrumentation failure is not waived only by strict physical-network proof"
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
        -not $source.Contains('$expectedAdjacentPageCount -lt 5',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$statusCode -eq 404 -or $statusCode -eq 410',
            [StringComparison]::Ordinal) -or
        -not $source.Contains("ep_unavailable=1",
            [StringComparison]::Ordinal) -or
        -not $source.Contains('"CLEARLY_UNAVAILABLE"',
            [StringComparison]::Ordinal)) {
    throw "Random work/pair selection must hash-rank exact current-next identities, retain canonical evidence, prove five adjacent pages, and avoid image bodies"
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
$fixturePairHashBytes = [Security.Cryptography.SHA256]::HashData(
    [Text.Encoding]::UTF8.GetBytes("42|webtoon|fixture-work|100|101"))
$fixturePairSelectionHash = [Convert]::ToHexString($fixturePairHashBytes).ToLowerInvariant()
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
        currentPageCount = 8
        nextEpisodeId = "101"
        nextEpisodePath = "/webtoon/fixture-work/101"
        nextEpisodePageCount = 5
        pairSelectionHash = $fixturePairSelectionHash
        pairRankOrdinal = 1
        pairCandidateCount = 3
    })
    resumePercents = @(90)
    resumeQualificationSatisfied = $false
    expectedWebtoon = 1
    expectedManhwa = 0
    completedCases = 1
    passedCases = 0
    smokePassed = $false
    provisionalPassed = $false
    passed = $false
    finalDeviceStatus = "UNVERIFIED_DEVICE"
    qualificationDeviceMode = "HOST_GPU_EMULATOR"
    hostGpuRestartIntervalCases = 10
    deviceRequirementSatisfied = $false
    physicalDeviceRequirementSatisfied = $false
    hostGpuEmulatorRequirementSatisfied = $false
    qualificationTargetSatisfied = $false
    warmReopenRequirementSatisfied = $true
    firstImageSlaRequirementSatisfied = $false
    allImagesSlaRequirementSatisfied = $false
    allImagesTimingDiagnosticOnly = $true
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
    # Diagnostic runs may intentionally select only one work type. Formal reports still
    # require 20 webtoon + 20 manhwa through their cross-field qualification contract.
    catalogCounts = [pscustomobject]@{ webtoon = 1; manhwa = 0 }
    cases = @([pscustomobject][ordered]@{
        schema = 1
        caseId = "fixture-1"
        baseCaseId = "fixture-1"
        infrastructureAttempt = 1
        classification = "PRODUCT_INVALID"
        ordinal = 1
        workType = "webtoon"
        workId = "fixture-work"
        currentPageCount = 8
        resumePercent = 90
        resumePercentBasis = "canonical_page_ordinal"
        resumePage = 7
        resumeOffset = -420
        expectedForwardPageCount = 1
        episodeId = "100"
        episodePath = "/webtoon/fixture-work/100"
        expectedAdjacentEpisodePath = "/webtoon/fixture-work/101"
        expectedAdjacentPageCount = 5
        passed = $false
        measurementInvalid = $false
        measurementInvalidReason = $null
        invalidMeasurementDiagnostics = @()
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
$infraFixture = $schemaFixtureJson | ConvertFrom-Json
$infraCase = $infraFixture.cases[0]
$infraCase.classification = "INFRA_INVALID"
$infraCase.measurementInvalid = $true
$infraCase.measurementInvalidReason =
    "MEASUREMENT_INVALID: source=0 semanticLag=786.0985ms>240ms"
$infraCase.invalidMeasurementDiagnostics = @(
    "forward-adjacent p0 seam exceeded the 250ms physical UX bound",
    "all canonical images exceeded 8000ms"
)
$infraCase.violations = @(
    "infrastructure measurement invalid: $($infraCase.measurementInvalidReason)"
)
$infraFixtureJson = $infraFixture | ConvertTo-Json -Depth 20
if(-not (Test-Json -Json $infraFixtureJson -SchemaFile $schemaFile -ErrorAction Stop)) {
    throw "Infrastructure-invalid case fixture did not validate"
}
$reportFixtureRoot = Join-Path ([IO.Path]::GetTempPath()) `
    ("ntk-report-classification-contract-" + [Guid]::NewGuid().ToString("N"))
[void](New-Item -ItemType Directory -Path $reportFixtureRoot)
try {
    foreach($reportFixture in @(
            [pscustomobject]@{ Name = "product"; Json = $schemaFixtureJson },
            [pscustomobject]@{ Name = "infra"; Json = $infraFixtureJson })) {
        $fixtureSummaryPath = Join-Path $reportFixtureRoot `
            "$($reportFixture.Name)-summary.json"
        $fixtureReportPath = Join-Path $reportFixtureRoot `
            "$($reportFixture.Name)-report.md"
        [IO.File]::WriteAllText(
            $fixtureSummaryPath,
            [string]$reportFixture.Json,
            [Text.UTF8Encoding]::new($false))
        $reportOutput = & pwsh -NoProfile -File $reportScript `
            -SummaryPath $fixtureSummaryPath -OutputPath $fixtureReportPath 2>&1
        if($LASTEXITCODE -ne 0 -or
                -not (Test-Path -LiteralPath $fixtureReportPath -PathType Leaf)) {
            throw "Classification report fixture failed ($($reportFixture.Name)): " +
                ($reportOutput -join [Environment]::NewLine)
        }
        if($reportFixture.Name -ceq "infra") {
            $renderedInfraReport = [IO.File]::ReadAllText($fixtureReportPath)
            if(-not $renderedInfraReport.Contains(
                    "MEASUREMENT_INVALID: source=0 semanticLag=786.0985ms>240ms",
                    [StringComparison]::Ordinal) -or
                    -not $renderedInfraReport.Contains(
                        "forward-adjacent p0 seam exceeded the 250ms physical UX bound",
                        [StringComparison]::Ordinal) -or
                    -not $renderedInfraReport.Contains(
                        "all canonical images exceeded 8000ms",
                        [StringComparison]::Ordinal)) {
                throw "Infrastructure-invalid report hid the seam/SLA diagnostics"
            }
        }
    }
} finally {
    if(Test-Path -LiteralPath $reportFixtureRoot) {
        Remove-Item -LiteralPath $reportFixtureRoot -Recurse -Force
    }
}
$classificationMismatchFixture = $infraFixtureJson | ConvertFrom-Json
$classificationMismatchFixture.cases[0].classification = "PRODUCT_INVALID"
if(Test-Json -Json ($classificationMismatchFixture | ConvertTo-Json -Depth 20) `
        -SchemaFile $schemaFile -ErrorAction SilentlyContinue) {
    throw "Schema accepted PRODUCT_INVALID with measurementInvalid=true"
}
$exactResultFixtureRoot = Join-Path ([IO.Path]::GetTempPath()) `
    ("ntk-macro-result-contract-" + [Guid]::NewGuid().ToString("N"))
[void](New-Item -ItemType Directory -Path $exactResultFixtureRoot)
try {
    $exactResultFixturePath = Join-Path $exactResultFixtureRoot "macro-result.json"
    $exactResultFixtureJson = [pscustomobject][ordered]@{
        schema = 1
        caseId = "fixture-case"
        payload = "x" * 5000
        measurementInvalid = $true
        measurementInvalidReason =
            "MEASUREMENT_INVALID: source=0 semanticLag=786.0985ms>240ms"
    } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText(
        $exactResultFixturePath,
        $exactResultFixtureJson,
        [Text.UTF8Encoding]::new($false))
    $exactArtifact = Get-ExactMacroResultArtifact `
        @([IO.FileInfo]::new($exactResultFixturePath)) "fixture-case"
    if(-not $exactArtifact.valid -or $exactArtifact.candidateCount -ne 1 -or
            $exactArtifact.bytes -le 4096L -or
            [string]$exactArtifact.result.caseId -cne "fixture-case" -or
            [string]$exactArtifact.sha256 -notmatch '^[0-9a-f]{64}$') {
        throw "Exact >4 KiB macro result artifact validation failed"
    }
    $wrongCaseArtifact = Get-ExactMacroResultArtifact `
        @([IO.FileInfo]::new($exactResultFixturePath)) "wrong-case"
    if($wrongCaseArtifact.valid -or $wrongCaseArtifact.problems.Count -eq 0) {
        throw "Exact macro result accepted the wrong case identity"
    }
} finally {
    if(Test-Path -LiteralPath $exactResultFixtureRoot) {
        Remove-Item -LiteralPath $exactResultFixtureRoot -Recurse -Force
    }
}
$instrumentationInvalidReason = Find-InstrumentationMeasurementInvalidReason @'
INSTRUMENTATION_STATUS: stack=ml.melun.mangaview.macrobenchmark.NtkColdViewerMacrobenchmark$MeasurementInvalidException: MEASUREMENT_INVALID: source=0 semanticLag=786.0985ms>240ms
    at benchmark.Source(Source.kt:1)
'@
if([string]$instrumentationInvalidReason -cne
        "MEASUREMENT_INVALID: source=0 semanticLag=786.0985ms>240ms") {
    throw "Instrumentation MeasurementInvalidException fallback parsing failed"
}
if($null -ne (Find-InstrumentationMeasurementInvalidReason `
        "java.lang.AssertionError: product seam exceeded")) {
    throw "Instrumentation fallback mislabeled a product assertion as infrastructure invalid"
}
if([string](Resolve-ColdCaseClassification $true 99) -cne "INFRA_INVALID" -or
        [string](Resolve-ColdCaseClassification $false 0) -cne "VALID" -or
        [string](Resolve-ColdCaseClassification $false 1) -cne "PRODUCT_INVALID") {
    throw "Cold case classification did not prioritize measurement invalidation"
}
$exactCountProof = Get-AdjacentPageCountReconciliation @() "/webtoon/1/2" 90 90
if($exactCountProof.matched -ne $true -or $exactCountProof.reconciled -ne $true -or
        [string]$exactCountProof.reason -cne "exact") {
    throw "Exact adjacent page counts were not accepted"
}
$explicitNonRenderableLine =
    "ViewerPerf: reader_image_api_excluded_nonrenderable_slots " +
    "path=/webtoon/13792/1305980,sourceSlots=91,renderable=90,sourcePages=17"
$reconciledCountProof = Get-AdjacentPageCountReconciliation `
    @($explicitNonRenderableLine) "/webtoon/13792/1305980" 91 90
if($reconciledCountProof.matched -ne $false -or
        $reconciledCountProof.reconciled -ne $true -or
        [int]$reconciledCountProof.sourceSlotCount -ne 91 -or
        [int]$reconciledCountProof.renderablePageCount -ne 90 -or
        @($reconciledCountProof.excludedSourcePages).Count -ne 1 -or
        [int]$reconciledCountProof.excludedSourcePages[0] -ne 17 -or
        [string]$reconciledCountProof.reason -cne
            "exact_api_explicit_nonrenderable_slots") {
    throw "Exact webtoon non-renderable slot evidence was not reconciled"
}
foreach($invalidCountProof in @(
        (Get-AdjacentPageCountReconciliation `
            @($explicitNonRenderableLine) "/webtoon/13792/wrong" 91 90),
        (Get-AdjacentPageCountReconciliation `
            @($explicitNonRenderableLine, $explicitNonRenderableLine) `
            "/webtoon/13792/1305980" 91 90),
        (Get-AdjacentPageCountReconciliation `
            @($explicitNonRenderableLine) "/manhwa/13792/1305980" 91 90),
        (Get-AdjacentPageCountReconciliation @() "/webtoon/1/2" 90 91))) {
    if($invalidCountProof.reconciled -eq $true) {
        throw "Unproven adjacent page-count drift was accepted"
    }
}
if(-not $macroSource.Contains(
        'require(expectedAdjacentEpisodePath.isNotBlank())',
        [StringComparison]::Ordinal) -or
        -not $macroSource.Contains(
            'require(expectedAdjacentPageCount >= ADJACENT_REQUIRED_RUNWAY_PAGES)',
            [StringComparison]::Ordinal) -or
        $macroSource.Contains('expectedEpisodePath.isBlank()', [StringComparison]::Ordinal) -or
        -not $resumePlanSource.Contains(
            'runwayDrawableCount == requiredRunwayPageCount',
            [StringComparison]::Ordinal) -or
        -not $macroSource.Contains('MAX_INPUT_INTER_GESTURE_GAP_MS = 64L',
            [StringComparison]::Ordinal) -or
        -not $macroSource.Contains(
            'requireReaderRateInput(inputMetrics, "resume-through-adjacent-p4")',
            [StringComparison]::Ordinal) -or
        -not $macroSource.Contains('inputMetrics.gestureCount > gesturesAtP0Signal',
            [StringComparison]::Ordinal) -or
        -not $macroSource.Contains('channel.requireCompleteCrossCheck(',
            [StringComparison]::Ordinal) -or
        -not $macroSource.Contains('adjacentProof.observedSourceIndices.joinToString(",")',
            [StringComparison]::Ordinal) -or
        -not $resumePlanSource.Contains('maxDetectionLagMs: Long = 240L',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$requiredAdjacentRunwayPages = 5',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$requiredAdjacentPhysicalPages = 5',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$ProductionMaxP0DetectionLagMs = 240.0',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$ProductionMaxInputInterGestureGapMs = 64L',
            [StringComparison]::Ordinal) -or
        $macroSource.Contains(
            'adjacentTotalPageCount == expectedAdjacentPageCount',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('function Get-AdjacentPageCountReconciliation',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('exact_api_explicit_nonrenderable_slots',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('$adjacentPageCountProof.reconciled -ne $true',
            [StringComparison]::Ordinal) -or
        -not $reportSource.Contains('$case.adjacentPageCountReconciled -eq $true',
            [StringComparison]::Ordinal) -or
        -not $source.Contains('"adjacentObservedRunwayDrawableCount"',
            [StringComparison]::Ordinal)) {
    throw "Forward-adjacent qualification is not fail-closed on unordered physical p0-p4/IPC/input timing"
}
if($macroSource.Contains('ADJACENT_BOUNDARY_WAIT_SLA_MS', [StringComparison]::Ordinal) -or
        $source.Contains('adjacentBoundaryWaitMs', [StringComparison]::Ordinal) -or
        $source.Contains('adjacentAttachMs', [StringComparison]::Ordinal) -or
        $reportSource.Contains('adjacentBoundaryWaitMs', [StringComparison]::Ordinal) -or
        $reportSource.Contains('adjacentAttachMs', [StringComparison]::Ordinal)) {
    throw "Historical fixed boundary-delay thresholds must not be restored"
}
if(-not $source.Contains('$runwayReadyBeforeTail = Get-OptionalProperty $macroResult "runwayReadyBeforeTail"',
        [StringComparison]::Ordinal) -or
        $source.Contains('if($runwayReadyBeforeTail -ne $true',
            [StringComparison]::Ordinal) -or
        $reportSource.Contains('$case.runwayReadyBeforeTail -eq $true',
            [StringComparison]::Ordinal)) {
    throw "Pre-tail p0-p4 timing must remain diagnostic instead of rejecting physical network delay"
}
if(-not $source.Contains('$terminalResumeInitialViewportP0 =',
        [StringComparison]::Ordinal) -or
        -not $source.Contains('$p0InputOrderValid =', [StringComparison]::Ordinal) -or
        -not $reportSource.Contains('$terminalResumeInitialViewportP0 =',
            [StringComparison]::Ordinal) -or
        -not $reportSource.Contains('$p0InputOrderValid =', [StringComparison]::Ordinal)) {
    throw "Forward-only Continue initial-viewport p0 is not consistently recognized"
}
$formalPassContract = $schema.allOf[3].then.properties
if([int]$formalPassContract.expectedWebtoon.const -ne 20 -or
        [int]$formalPassContract.expectedManhwa.const -ne 20 -or
        [int]$formalPassContract.completedCases.const -ne 120 -or
        [int]$formalPassContract.passedCases.const -ne 120 -or
        [int]$formalPassContract.allImagesSlaMs.const -ne 8000) {
    throw "Formal result schema is not fixed to random 20+20 and the 8000ms transfer diagnostic"
}
$finalPassContract = $schema.allOf[4].then.properties
if([int]$finalPassContract.completedCases.const -ne 120 -or
        [int]$finalPassContract.passedCases.const -ne 120) {
    throw "Final PASS schema must require all 20+20 x 25/50/90% cases"
}
$passedCaseRequired = @($schema.'$defs'.case.allOf[0].then.required)
$passedCaseProperties = $schema.'$defs'.case.allOf[0].then.properties
foreach($adjacentField in @(
        "baseCaseId",
        "infrastructureAttempt",
        "classification",
        "resumeMode",
        "homeContinueSeeded",
        "homeContinueColdForceStopped",
        "firstActualResumePage",
        "inputSampleCount",
        "inputStartElapsedNanos",
        "inputEndElapsedNanos",
        "allImagesReadyAtNanos",
        "allImagesEvidenceSource",
        "allImagesEvidenceConflict",
        "expectedAdjacentEpisodePath",
        "expectedAdjacentPageCount",
        "adjacentPageCountMatched",
        "adjacentPageCountReconciled",
        "adjacentPageCountReconciliationReason",
        "adjacentSourceSlotCount",
        "adjacentRenderablePageCount",
        "adjacentExcludedNonRenderableSourcePages",
        "adjacentRunwayTargetEpisode",
        "adjacentRunwayPageCount",
        "adjacentObservedRunwayDrawableCount",
        "adjacentTotalPageCount",
        "adjacentTraversalGestureCount",
        "adjacentP0TraversalGestureCount",
        "adjacentRunwayTraversalGestureCount",
        "adjacentLastSourceIndex",
        "adjacentPhysicallyObservedSources",
        "adjacentPhysicalRunwayPassed",
        "adjacentSourcePresentedAtNanos",
        "adjacentSourceIpcAcceptedAtNanos",
        "adjacentSourceGesturesAtPresentation",
        "adjacentSourceSemanticObservedAtNanos",
        "adjacentSourceSemanticEventPublishedAtNanos",
        "adjacentSourceSemanticEventLeadMs",
        "adjacentSourceSemanticCallbackAtNanos",
        "adjacentSourceSemanticObserverModes",
        "adjacentSourceGesturesAtSemanticProof",
        "adjacentSourceProgressPassed",
        "adjacentSourceProgressFailure",
        "adjacentWorkStartedAtNanos",
        "adjacentRunwayReadyAtNanos",
        "forwardBoundaryReachedAtNanos",
        "firstAdjacentActualAtNanos",
        "firstAdjacentActualEpisode",
        "adjacentP0SeamMs",
        "p0EmbeddedFirstAdjacentActualAtNanos",
        "p0HarnessObservedAtNanos",
        "p0GesturesAtObservation",
        "p0DetectionLagMs",
        "p0ActualToInputEndMs",
        "p0SemanticObservationStatus",
        "p0MeasurementStatus",
        "p0IpcAccepted",
        "p0IpcPresentedAtNanos",
        "p0IpcSenderAtNanos",
        "p0IpcReceivedAtNanos",
        "p0IpcAcceptedAtNanos",
        "p0IpcPresentedToSenderLagMs",
        "p0IpcSenderToReceiverLagMs",
        "p0IpcReceiverToAcceptanceLagMs",
        "p0IpcDeliveryLagMs",
        "p0IpcAcceptanceLagMs",
        "p0IpcGesturesAtSignal",
        "p0IpcGesturesAfterSignal",
        "p0IpcContinuousInputPreserved",
        "p0IpcEpisodePath",
        "p0IpcSourceIndex",
        "p0IpcViewerGeneration",
        "p0IpcRejectedSignalCount",
        "p0IpcFirstRejectReason",
        "p0IpcSemanticObservedAtNanos",
        "p0SemanticCallbackAtNanos",
        "p0SemanticEventPublishedAtNanos",
        "p0SemanticEventLeadMs",
        "p0SemanticObserverMode",
        "p0SemanticCallbackSchedulerLagMs",
        "p0IpcTimestampCrossCheckPassed",
        "measurementInvalid",
        "measurementInvalidReason")) {
    if($passedCaseRequired -cnotcontains $adjacentField) {
        throw "Passed-case schema does not require adjacent evidence: $adjacentField"
    }
    if(-not $source.Contains("$adjacentField =", [StringComparison]::Ordinal)) {
        throw "Qualification case summary does not copy adjacent evidence: $adjacentField"
    }
}
if($passedCaseProperties.adjacentP0SeamMs.PSObject.Properties.Name -contains "maximum" -or
        [string]$passedCaseProperties.classification.const -cne "VALID" -or
        [string]$passedCaseProperties.adjacentPhysicallyObservedSources.const -cne
            "0,1,2,3,4" -or
        [int]$passedCaseProperties.adjacentLastSourceIndex.maximum -ne 4 -or
        $passedCaseProperties.adjacentPhysicalRunwayPassed.const -ne $true -or
        [int]$passedCaseProperties.inputMaxInterGestureGapMs.maximum -ne 64 -or
        [double]$passedCaseProperties.p0DetectionLagMs.maximum -ne 240.0 -or
        [double]$passedCaseProperties.p0IpcAcceptanceLagMs.maximum -ne 240.0 -or
        [string]$passedCaseProperties.p0SemanticObservationStatus.const -cne "VALID" -or
        [string]$passedCaseProperties.p0MeasurementStatus.const -cne "VALID" -or
        $passedCaseProperties.p0IpcContinuousInputPreserved.const -ne $true -or
        [int]$passedCaseProperties.p0IpcRejectedSignalCount.const -ne 0 -or
        [string]$passedCaseProperties.p0IpcFirstRejectReason.const -cne "NONE" -or
        $passedCaseProperties.p0IpcTimestampCrossCheckPassed.const -ne $true -or
        $passedCaseProperties.adjacentSourceProgressPassed.const -ne $true -or
        [int]$passedCaseProperties.adjacentSourcePresentedAtNanos.minItems -ne 5 -or
        [int]$passedCaseProperties.adjacentSourcePresentedAtNanos.maxItems -ne 5 -or
        [int]$passedCaseProperties.adjacentSourceIpcAcceptedAtNanos.minItems -ne 5 -or
        [int]$passedCaseProperties.adjacentSourceIpcAcceptedAtNanos.maxItems -ne 5 -or
        [int]$passedCaseProperties.adjacentSourceSemanticObservedAtNanos.minItems -ne 5 -or
        [int]$passedCaseProperties.adjacentSourceSemanticObservedAtNanos.maxItems -ne 5 -or
        [int]$passedCaseProperties.adjacentSourceSemanticEventPublishedAtNanos.minItems -ne 5 -or
        [int]$passedCaseProperties.adjacentSourceSemanticEventPublishedAtNanos.maxItems -ne 5 -or
        [int]$passedCaseProperties.adjacentSourceSemanticCallbackAtNanos.minItems -ne 5 -or
        [int]$passedCaseProperties.adjacentSourceSemanticCallbackAtNanos.maxItems -ne 5 -or
        @($passedCaseProperties.adjacentSourceSemanticObserverModes.items.enum) -cnotcontains
            "ACCESSIBILITY_EVENT_TIME" -or
        @($passedCaseProperties.adjacentSourceSemanticObserverModes.items.enum) -cnotcontains
            "CALLBACK_FLOOR" -or
        @($passedCaseProperties.p0SemanticObserverMode.enum) -cnotcontains
            "ACCESSIBILITY_EVENT_TIME" -or
        @($passedCaseProperties.p0SemanticObserverMode.enum) -cnotcontains
            "CALLBACK_FLOOR" -or
        $passedCaseProperties.allImagesEvidenceConflict.const -ne $false -or
        $passedCaseProperties.allImagesCompletionPassed.const -ne $true -or
        $passedCaseProperties.allImagesTimingDiagnosticOnly.const -ne $true -or
        $passedCaseProperties.measurementInvalid.const -ne $false) {
    throw "Passed-case schema weakened the unordered physical p0-p4/IPC/input contract"
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
        'exact five-page forward-adjacent proof missing',
        [StringComparison]::Ordinal) -or
        -not $reportSource.Contains(
            'forward-adjacent p0-p4 physical proof missing',
            [StringComparison]::Ordinal) -or
        -not $reportSource.Contains(
            'forward-adjacent p0 seam timing proof failed',
            [StringComparison]::Ordinal) -or
        -not $reportSource.Contains(
            'adjacent p0 IPC identity or semantic measurement invalid',
            [StringComparison]::Ordinal) -or
        -not $reportSource.Contains(
            'resume-to-next physical input cadence contract failed',
            [StringComparison]::Ordinal)) {
    throw "Final report does not recompute the unordered physical p0-p4/IPC/input contract"
}
foreach($classificationContractToken in @(
        '"PRODUCT_INVALID" {',
        '"INFRA_INVALID" {',
        'INFRA_INVALID case classification mismatch:',
        'passed case lacked one exact atomic macro result:',
        'invalidMeasurementDiagnostics')) {
    if(-not $reportSource.Contains(
            $classificationContractToken,
            [StringComparison]::Ordinal)) {
        throw "Final report classification contract missing: $classificationContractToken"
    }
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
        "'^(webtoon|manhwa)-\d{2,3}-[A-Za-z0-9._-]+$'",
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
        '$strictSourceRetryAttemptCount = [int64]@($lines | Where-Object',
        'strictSourceRetryAttemptCount = $strictSourceRetryAttemptCount',
        'strictDirectWifiH2FailoverCount = $strictDirectWifiH2FailoverCount',
        '$pipelineRequestStarted - $pipelineRequestSucceeded -',
        '$cancellationTimestampNanos -le $pipelineSummaryTimestampNanos',
        '$pipelineRequestStarted - $pipelineRecoveredCancellationCount',
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
