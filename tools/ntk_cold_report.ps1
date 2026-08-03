#requires -Version 7.2

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SummaryPath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Property($Object, [string]$Name) {
    if($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if($null -eq $property) { return $null }
    return $property.Value
}

function Assert-Contract([bool]$Condition, [string]$Message) {
    if(-not $Condition) { throw "NTK cold summary contract failed: $Message" }
}

function Show-Value($Value, [string]$Suffix = "") {
    if($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) { return "미수집" }
    return "$Value$Suffix"
}

function Escape-Table([string]$Value) {
    return $Value.Replace('|', '\|').Replace("`r", ' ').Replace("`n", ' ')
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

function Get-DeltaMs($Start, $End) {
    if($null -eq $Start -or $null -eq $End) { return $null }
    $startValue = 0L
    $endValue = 0L
    if(-not [long]::TryParse([string]$Start, [ref]$startValue) -or
            -not [long]::TryParse([string]$End, [ref]$endValue) -or
            $endValue -lt $startValue) {
        return $null
    }
    return [Math]::Round(($endValue - $startValue) / 1000000.0, 3)
}

$summaryFile = Get-Item -LiteralPath $SummaryPath -ErrorAction Stop
$summaryJson = Get-Content -LiteralPath $summaryFile.FullName -Raw
$schemaPath = Join-Path $PSScriptRoot "ntk_cold_result.schema.json"
if(-not (Test-Path -LiteralPath $schemaPath -PathType Leaf)) {
    throw "NTK cold result schema is missing: $schemaPath"
}
try {
    $schemaValid = Test-Json -Json $summaryJson -SchemaFile $schemaPath -ErrorAction Stop
} catch {
    throw "NTK cold summary JSON Schema validation failed: $($_.Exception.Message)"
}
if(-not $schemaValid) { throw "NTK cold summary did not satisfy $schemaPath" }
$summary = $summaryJson | ConvertFrom-Json

# JSON Schema validates shape. Recompute cross-field invariants so a hand-edited or stale summary
# cannot turn a diagnostic run, wrong renderer/device mode, or partial case set into a formal PASS.
$cases = @($summary.cases)
$selectedEpisodePairs = @($summary.selectedEpisodePairs)
$webtoonCases = @($cases | Where-Object { [string]$_.workType -ceq "webtoon" })
$manhwaCases = @($cases | Where-Object { [string]$_.workType -ceq "manhwa" })
$actualPassedCases = @($cases | Where-Object { $_.passed -eq $true }).Count
$expectedCaseCount = [int]$summary.expectedWebtoon + [int]$summary.expectedManhwa
$targetSatisfied = [int]$summary.requestedCountPerType -eq 20 -and
    [int]$summary.expectedWebtoon -eq 20 -and [int]$summary.expectedManhwa -eq 20
# Warm reopen is an out-of-verdict diagnostic. Cold qualification is never gated by whether that
# optional second opening was available; measured retained-memory growth is asserted per case.
$warmSatisfied = $true
$firstSlaSatisfied = [int]$summary.firstImageSlaMs -eq 4000 -and
    [int]$summary.webtoonImageSlaMs -eq 4000 -and
    [int]$summary.manhwaImageSlaMs -eq 4000
$allImagesSlaSatisfied = [int]$summary.allImagesSlaMs -eq 8000
$freshRandomSeedSatisfied = [string]$summary.seedSelectionMode -ceq "FRESH_RANDOM"
$formalConfiguration = $targetSatisfied -and $warmSatisfied -and $firstSlaSatisfied -and
    $allImagesSlaSatisfied -and
    $freshRandomSeedSatisfied
$smokePassed = $cases.Count -eq $expectedCaseCount -and
    $actualPassedCases -eq $expectedCaseCount
$provisionalPassed = $smokePassed -and $formalConfiguration
$virtualMarkers = @((Get-Property $summary.device "virtualDeviceMarkers"))
$derivedVirtualDevice = $summary.device.qemu -eq $true -or
    $summary.device.bootQemu -eq $true -or $virtualMarkers.Count -gt 0
$derivedPhysicalIdentity = $summary.device.positivePhysicalIdentity -eq $true -and
    -not $derivedVirtualDevice
$surfaceFlingerGles = [string](Get-Property $summary.device "surfaceFlingerGles")
$derivedHostTranslator = $surfaceFlingerGles -match
    '(?i)Android Emulator OpenGL ES Translator\s*\([^)]*[A-Za-z][^)]*\)'
$derivedSoftwareGpu = $surfaceFlingerGles -match
    '(?i)(SwiftShader|llvmpipe|softpipe|software rasterizer|Mesa OffScreen|ANGLE\s*\(SwiftShader)'
$derivedHostGpuEmulator = $derivedVirtualDevice -and
    ($summary.device.qemu -eq $true -or $summary.device.bootQemu -eq $true) -and
    [string](Get-Property $summary.device "eglHardware") -ceq "emulation" -and
    [string](Get-Property $summary.device "hwuiRenderer") -match '^(?:skiagl|skiavk)$' -and
    $derivedHostTranslator -and -not $derivedSoftwareGpu
$derivedDeviceRequirement = if(
    [string]$summary.qualificationDeviceMode -ceq "HOST_GPU_EMULATOR"
) { $derivedHostGpuEmulator } else { $derivedPhysicalIdentity }
$finalPassed = $provisionalPassed -and $derivedDeviceRequirement
$derivedDeviceStatus = if($derivedHostGpuEmulator) {
    "HOST_GPU_EMULATOR"
} elseif($derivedPhysicalIdentity) {
    "PHYSICAL_DEVICE"
} elseif($derivedVirtualDevice) {
    "PROVISIONAL_EMULATOR"
} else {
    "UNVERIFIED_DEVICE"
}

Assert-Contract ($cases.Count -eq [int]$summary.completedCases) "completedCases mismatch"
# A fail-fast run is deliberately a strict prefix of the recorded random selection. It can never
# become a PASS because smokePassed still requires the complete expected case count, but the first
# failed case must remain reportable instead of losing its diagnosis to a report-contract error.
Assert-Contract ($cases.Count -le $expectedCaseCount) "case count exceeded expected types"
Assert-Contract ($webtoonCases.Count -le [int]$summary.expectedWebtoon) "webtoon count exceeded selection"
Assert-Contract ($manhwaCases.Count -le [int]$summary.expectedManhwa) "manhwa count exceeded selection"
Assert-Contract ($actualPassedCases -eq [int]$summary.passedCases) "passedCases mismatch"
Assert-Contract ($summary.qualificationTargetSatisfied -eq $targetSatisfied) `
    "qualificationTargetSatisfied was not derived from exact 20+20"
Assert-Contract ($summary.warmReopenRequirementSatisfied -eq $warmSatisfied) `
    "warmReopenRequirementSatisfied mismatch"
Assert-Contract ($summary.firstImageSlaRequirementSatisfied -eq $firstSlaSatisfied) `
    "firstImageSlaRequirementSatisfied mismatch"
Assert-Contract ($summary.allImagesSlaRequirementSatisfied -eq $allImagesSlaSatisfied) `
    "allImagesSlaRequirementSatisfied mismatch"
Assert-Contract ($summary.freshRandomSeedRequirementSatisfied -eq $freshRandomSeedSatisfied) `
    "freshRandomSeedRequirementSatisfied mismatch"
Assert-Contract ($summary.diagnosticOnly -eq (-not $formalConfiguration)) `
    "diagnosticOnly mismatch"
Assert-Contract ($summary.smokePassed -eq $smokePassed) "smokePassed mismatch"
Assert-Contract ($summary.provisionalPassed -eq $provisionalPassed) "provisionalPassed mismatch"
Assert-Contract ($summary.passed -eq $finalPassed) "formal passed mismatch"
Assert-Contract ($summary.device.virtualDeviceDetected -eq $derivedVirtualDevice) `
    "virtual device identity derivation mismatch"
Assert-Contract ($summary.device.physicalIdentitySatisfied -eq $derivedPhysicalIdentity) `
    "physical device identity derivation mismatch"
Assert-Contract ($summary.physicalDeviceRequirementSatisfied -eq $derivedPhysicalIdentity) `
    "physical device requirement mismatch"
Assert-Contract ($summary.device.hostTranslatorDetected -eq $derivedHostTranslator) `
    "host GPU translator derivation mismatch"
Assert-Contract ($summary.device.softwareGpuDetected -eq $derivedSoftwareGpu) `
    "software GPU derivation mismatch"
Assert-Contract ($summary.device.hostGpuEmulatorSatisfied -eq $derivedHostGpuEmulator) `
    "host GPU emulator identity derivation mismatch"
Assert-Contract ($summary.hostGpuEmulatorRequirementSatisfied -eq $derivedHostGpuEmulator) `
    "host GPU emulator requirement mismatch"
Assert-Contract ($summary.deviceRequirementSatisfied -eq $derivedDeviceRequirement) `
    "selected device requirement mismatch"
Assert-Contract ([string]$summary.finalDeviceStatus -ceq $derivedDeviceStatus) `
    "final device status mismatch"
Assert-Contract ([string]$summary.selectionAlgorithm -ceq
        "work: sha256(seed|type|id) lexical rank; episode-pair: sha256(seed|type|workId|currentEpisodeId|nextEpisodeId) lexical rank") `
    "work/episode-pair selection algorithm mismatch"
Assert-Contract ($selectedEpisodePairs.Count -eq $expectedCaseCount) `
    "selected exact episode-pair count did not match expected selection"

$caseIds = @($cases | ForEach-Object { [string]$_.caseId })
$workKeys = @($cases | ForEach-Object { "$($_.workType)|$($_.workId)" })
Assert-Contract ((@($caseIds | Sort-Object -Unique)).Count -eq $caseIds.Count) `
    "duplicate caseId"
Assert-Contract ((@($workKeys | Sort-Object -Unique)).Count -eq $workKeys.Count) `
    "duplicate selected work within a run"
for($index = 0; $index -lt $cases.Count; $index++) {
    $case = $cases[$index]
    $pair = $selectedEpisodePairs[$index]
    Assert-Contract ([int]$case.ordinal -eq ($index + 1)) `
        "case ordinal mismatch at index $index"
    Assert-Contract ([int]$pair.ordinal -eq ($index + 1)) `
        "selected episode-pair ordinal mismatch at index $index"
    Assert-Contract ([int64]$pair.seed -eq [int64]$summary.seed) `
        "selected episode-pair seed mismatch at index $index"
    Assert-Contract ([string]$pair.workType -ceq [string]$case.workType -and
        [string]$pair.workId -ceq [string]$case.workId -and
        [string]$pair.currentEpisodeId -ceq [string]$case.episodeId -and
        [string]$pair.currentEpisodePath -ceq [string]$case.episodePath -and
        [string]$pair.nextEpisodePath -ceq
            [string](Get-Property $case "expectedAdjacentEpisodePath") -and
        [int]$pair.nextEpisodePageCount -eq
            [int](Get-Property $case "expectedAdjacentPageCount")) `
        "case identity does not match its recorded exact current/next episode pair at index $index"
    $expectedPairPrefix = "/$([string]$pair.workType)/$([string]$pair.workId)/"
    $expectedPairHash = Get-Sha256 (
        "$([int64]$summary.seed)|$([string]$pair.workType)|$([string]$pair.workId)|" +
            "$([string]$pair.currentEpisodeId)|$([string]$pair.nextEpisodeId)"
    )
    Assert-Contract ([string]$pair.currentEpisodePath -ceq
            ($expectedPairPrefix + [string]$pair.currentEpisodeId) -and
        [string]$pair.nextEpisodePath -ceq
            ($expectedPairPrefix + [string]$pair.nextEpisodeId) -and
        [string]$pair.currentEpisodePath -cne [string]$pair.nextEpisodePath -and
        [int]$pair.nextEpisodePageCount -ge 4 -and
        [string]$pair.pairSelectionHash -ceq $expectedPairHash -and
        [int]$pair.pairRankOrdinal -ge 1 -and
        [int]$pair.pairRankOrdinal -le [int]$pair.pairCandidateCount) `
        "selected episode-pair provenance was invalid at index $index"
    $violations = @((Get-Property $case "violations"))
    Assert-Contract (($case.passed -eq $true) -eq ($violations.Count -eq 0)) `
        "case pass/violation mismatch: $($case.caseId)"
    if($null -ne (Get-Property $case "randomSeed")) {
        Assert-Contract ([int64]$case.randomSeed -eq [int64]$summary.seed) `
            "case seed mismatch: $($case.caseId)"
    }

    if($case.passed -eq $true) {
        $caseImageSlaMs = if([string]$case.workType -ceq "webtoon") {
            [int]$summary.webtoonImageSlaMs
        } else {
            [int]$summary.manhwaImageSlaMs
        }
        Assert-Contract ([int]$case.imageSlaMs -eq $caseImageSlaMs) `
            "type-specific first-image SLA mismatch: $($case.caseId)"
        Assert-Contract ([int]$case.allImagesSlaMs -eq [int]$summary.allImagesSlaMs) `
            "all-images completion SLA mismatch: $($case.caseId)"
        Assert-Contract ([double]$case.firstActualMs -le $caseImageSlaMs -and
            [double]$case.allImagesReadyMs -le [int]$summary.allImagesSlaMs -and
            [double]$case.androidxAllImagesReadyMs -le [int]$summary.allImagesSlaMs) `
            "first/all-images timing exceeded their respective SLA: $($case.caseId)"
        Assert-Contract ([int64]$case.allImagesReadyPageCount -eq
            [int64]$case.authoritativePageCount) `
            "render-ready page count mismatch: $($case.caseId)"
        Assert-Contract (-not [string]::IsNullOrWhiteSpace(
                [string]$case.expectedAdjacentEpisodePath) -and
            [string]$case.adjacentRunwayTargetEpisode -ceq
                [string]$case.expectedAdjacentEpisodePath -and
            [int]$case.expectedAdjacentPageCount -ge 4 -and
            [int]$case.adjacentTotalPageCount -eq [int]$case.expectedAdjacentPageCount -and
            [int]$case.adjacentRunwayPageCount -eq 4 -and
            [int]$case.adjacentObservedRunwayDrawableCount -eq 4) `
            "exact four-drawable forward-adjacent proof missing: $($case.caseId)"
        Assert-Contract ($case.runwayReadyBeforeTail -eq $true -and
            [double]$case.adjacentBoundaryWaitMs -ge 0.0 -and
            [double]$case.adjacentBoundaryWaitMs -le 500.0 -and
            [double]$case.adjacentAttachMs -ge 0.0 -and
            [double]$case.adjacentAttachMs -le 200.0) `
            "forward-adjacent tail transition timing proof failed: $($case.caseId)"
        Assert-Contract ($case.macroResult.allImagesSlaPassed -eq $true) `
            "Macrobenchmark all-images SLA proof missing: $($case.caseId)"
        Assert-Contract (-not [string]::IsNullOrWhiteSpace(
                [string]$case.androidxFrameCommitTraceKind) -and
            [double]$case.androidxFrameCommitMaxMs -lt 100.0) `
            "renderer-specific frame commit exceeded 100ms or was unmeasured: $($case.caseId)"
        Assert-Contract ([int64]$case.firstActualPageIndex -eq 0L -and
            [int64]$case.validCommittedFrames -gt 0L -and
            [int64]$case.invalidCommittedFrames -eq 0L) `
            "committed frame identity proof failed: $($case.caseId)"
        Assert-Contract ([string]$case.manifestDigest -match '^[0-9a-f]{64}$' -and
            [string]$case.traversalManifestDigest -ceq [string]$case.manifestDigest) `
            "manifest/traversal authority mismatch: $($case.caseId)"
        Assert-Contract ([int64]$case.viewportDefectFrames -eq 0L -and
            [int64]$case.runwayDefectFrames -eq 0L -and
            [int64]$case.preSubmitViewportGaps -eq 0L -and
            [int64]$case.initialBlankFrames -eq 0L -and
            [int64]$case.blankAreaCount -eq 0L -and
            [int64]$case.wrongBindingCount -eq 0L) `
            "blank, pop-in, or wrong-binding evidence present: $($case.caseId)"
        Assert-Contract ([int64]$case.imageFailureCount -eq 0L -and
            [int64]$case.decodeFailureCount -eq 0L -and
            [int64]$case.pageListFailureCount -eq 0L -and
            [int64]$case.duplicateRequestCount -eq 0L) `
            "image pipeline failure or duplicate request present: $($case.caseId)"
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
                "activeMainThreadRunningMaxMs",
                "activePresentationSystemFence")) {
            Assert-Contract ($null -ne (Get-Property $case $frameField)) `
                "forward-scroll frame field missing: $frameField ($($case.caseId))"
        }
        Assert-Contract ([double]$case.activePresentationSystemFence -eq 1.0) `
            "forward-scroll evidence was not a SurfaceFlinger presentation fence: $($case.caseId)"
        Assert-Contract ([double]$case.activePresentedFrameCount -gt 1.0 -and
            [double]$case.activePresentationIntervalCount -gt 0.0 -and
            [double]$case.activePresentationFps -ge
                [double]$case.activePresentationFpsTarget -and
            [double]$case.activePresentationJankPercent -lt 1.0 -and
            [double]$case.activePresentationGapMaxMs -lt 100.0 -and
            [double]$case.activeMainThreadRunningMaxMs -lt 100.0) `
            "forward-scroll frame contract failed: $($case.caseId)"

        $requestProblems = @((Get-Property $case "requestQueueMetricProblems"))
        Assert-Contract ($case.requestQueueMetricsMeasured -eq $true) `
            "request queue metrics missing: $($case.caseId)"
        Assert-Contract ([int64]$case.peakActiveRequestQueueLimit -eq 120L) `
            "request queue production limit changed: $($case.caseId)"
        Assert-Contract ([int64]$case.peakActiveRequestQueue -le
            [int64]$case.peakActiveRequestQueueLimit) `
            "request queue exceeded limit: $($case.caseId)"
        Assert-Contract ([int64]$case.requestQueueTerminalBalance -eq 0L) `
            "request queue terminal imbalance: $($case.caseId)"
        Assert-Contract ($requestProblems.Count -eq 0) `
            "request queue metric problems present: $($case.caseId)"

        Assert-Contract ($case.forwardTraversalMetricsMeasured -eq $true) `
            "forward traversal metrics missing: $($case.caseId)"
        Assert-Contract ([int64]$case.forwardTraversalGestureMin -eq 1L -and
            [int64]$case.forwardTraversalGestureMax -eq 500L) `
            "forward traversal gesture bounds changed: $($case.caseId)"
        Assert-Contract ([int64]$case.forwardTraversalGestureCount -ge
            [int64]$case.forwardTraversalGestureMin -and
            [int64]$case.forwardTraversalGestureCount -le
                [int64]$case.forwardTraversalGestureMax) `
            "forward traversal gesture count invalid: $($case.caseId)"
        Assert-Contract ([int64]$case.forwardTraversalStartElapsedNanos -gt 0L -and
            [int64]$case.forwardTraversalEndElapsedNanos -gt
                [int64]$case.forwardTraversalStartElapsedNanos) `
            "forward traversal interval invalid: $($case.caseId)"
        Assert-Contract ([int64]$case.imageCancellationCount -eq 0L -and
            [int64]$case.decodeCancellationCount -eq 0L) `
            "forward traversal cancelled image work: $($case.caseId)"

        if($summary.includeWarmReopen -eq $true) {
            if($case.retainedPssGrowthMeasured -eq $true) {
                Assert-Contract ([double]$case.retainedPssGrowthKb -le
                    [double]$case.retainedPssGrowthLimitKb) `
                    "warm retained-PSS growth exceeded limit: $($case.caseId)"
            }
        }
        Assert-Contract ([int]$case.benchmarkArtifactPullExitCode -eq 0) `
            "Macrobenchmark artifact pull failed: $($case.caseId)"
        Assert-Contract ([int]$case.visualEvidencePullExitCode -eq 0) `
            "visual evidence pull failed: $($case.caseId)"

        $traceArtifacts = @((Get-Property $case "macroTraceArtifacts"))
        Assert-Contract ([int]$case.macroTraceArtifactCount -eq $traceArtifacts.Count -and
            [int]$case.nonEmptyMacroTraceArtifactCount -eq $traceArtifacts.Count -and
            [int]$case.emptyMacroTraceArtifactCount -eq 0 -and
            [int64]$case.macroTraceArtifactBytes -gt 0L) `
            "Macrobenchmark trace inventory inconsistent: $($case.caseId)"
        $visualArtifacts = @((Get-Property $case "visualEvidenceArtifacts"))
        Assert-Contract ([int]$case.visualEvidenceArtifactCount -eq $visualArtifacts.Count -and
            [int]$case.nonEmptyVisualEvidenceArtifactCount -eq $visualArtifacts.Count -and
            [int]$case.emptyVisualEvidenceArtifactCount -eq 0 -and
            [int64]$case.visualEvidenceArtifactBytes -gt 0L) `
            "visual evidence inventory inconsistent: $($case.caseId)"

        $artifactRoot = [string]$case.artifacts.directory
        Assert-Contract (Test-Path -LiteralPath $artifactRoot -PathType Container) `
            "artifact directory missing: $($case.caseId)"
        foreach($artifactName in @("instrumentation", "logcat", "meminfo", "gfxinfo", "cpuinfo")) {
            $relative = [string](Get-Property $case.artifacts $artifactName)
            $artifactPath = Join-Path $artifactRoot $relative
            Assert-Contract (Test-Path -LiteralPath $artifactPath -PathType Leaf) `
                "$artifactName artifact missing: $($case.caseId)"
            Assert-Contract ((Get-Item -LiteralPath $artifactPath).Length -gt 0) `
                "$artifactName artifact empty: $($case.caseId)"
        }
        $screenshotRoot = Join-Path $artifactRoot ([string]$case.artifacts.screenshots)
        $screenshots = @(if(Test-Path -LiteralPath $screenshotRoot -PathType Container) {
            Get-ChildItem -LiteralPath $screenshotRoot -Recurse -Filter *.png -File
        })
        Assert-Contract ($screenshots.Count -gt 0) "screenshots missing: $($case.caseId)"
        foreach($relativeTrace in $traceArtifacts) {
            $tracePath = Join-Path $artifactRoot ([string]$relativeTrace)
            Assert-Contract (Test-Path -LiteralPath $tracePath -PathType Leaf) `
                "Macrobenchmark trace missing: $($case.caseId)"
            Assert-Contract ((Get-Item -LiteralPath $tracePath).Length -gt 0) `
                "Macrobenchmark trace empty: $($case.caseId)"
        }
        foreach($relativeVisual in $visualArtifacts) {
            $visualPath = Join-Path $artifactRoot ([string]$relativeVisual)
            Assert-Contract (Test-Path -LiteralPath $visualPath -PathType Leaf) `
                "visual evidence missing: $($case.caseId)"
            Assert-Contract ((Get-Item -LiteralPath $visualPath).Length -gt 0) `
                "visual evidence empty: $($case.caseId)"
        }
    }
}

$lines = [Collections.Generic.List[string]]::new()
$lines.Add("# NTK 뷰어 콜드 20+20 결과")
$lines.Add("")
$lines.Add("- 실행 시각: $($summary.generatedAt)")
$lines.Add("- 랜덤 시드: $($summary.seed)")
$lines.Add("- 시드 선택 모드: $($summary.seedSelectionMode) (정식 자격 충족=$($summary.freshRandomSeedRequirementSatisfied))")
$lines.Add("- 작품/회차 pair 선택: $($summary.selectionAlgorithm)")
$lines.Add("- 기기: $($summary.device.manufacturer) $($summary.device.model), Android $($summary.device.androidRelease), $($summary.device.refreshHz)Hz")
$lines.Add("- 기기 자격 모드: $($summary.qualificationDeviceMode) (충족=$($summary.deviceRequirementSatisfied), 판정=$($summary.finalDeviceStatus))")
$lines.Add("- GPU: $($summary.device.surfaceFlingerGles); HWUI=$($summary.device.hwuiRenderer); software=$($summary.device.softwareGpuDetected)")
$lines.Add("- 네트워크: $($summary.device.networkType)")
$lines.Add("- 컴파일: $($summary.compilation)")
$lines.Add("- 첫 이미지 SLA: 웹툰 $($summary.webtoonImageSlaMs)ms / 만화 $($summary.manhwaImageSlaMs)ms (충족=$($summary.firstImageSlaRequirementSatisfied))")
$lines.Add("- 전체 이미지 완료 SLA: $($summary.allImagesSlaMs)ms (충족=$($summary.allImagesSlaRequirementSatisfied))")
$lines.Add("- 진단 전용: $($summary.diagnosticOnly)")
$resultLabel = if($summary.passed) {
    "PASS"
} elseif($summary.provisionalPassed) {
    "PROVISIONAL PASS"
} elseif($summary.smokePassed) {
    "DIAGNOSTIC ONLY"
} else {
    "FAIL"
}
$lines.Add("- 결과: **$resultLabel** ($($summary.passedCases)/$($summary.completedCases))")
if(-not $summary.deviceRequirementSatisfied) {
    $lines.Add("- 선택 기기 자격 판정: **FAIL** — $($summary.qualificationDeviceMode) 증거를 충족하지 않았다.")
}
if($summary.diagnosticOnly) {
    $lines.Add("- 형식 자격 판정: **DIAGNOSTIC ONLY** — 새 무작위 시드, 정확히 20+20, 첫 이미지 4000ms 및 전체 이미지 완료 8000ms SLA가 모두 필요하다. 웜 재개방은 선택 진단이며 콜드 판정에 포함되지 않는다.")
}
$lines.Add("- Schema 검증: ``$schemaPath`` 및 cross-field 재계산 PASS")
$lines.Add("")

$lines.Add("## 시드로 선정된 정확한 current → next 회차 pair")
$lines.Add("")
$lines.Add("순번 | seed | 유형 | 작품 ID | current 회차 | next 회차 | next 페이지 | pair rank | pair hash")
$lines.Add("---: | ---: | --- | --- | --- | --- | ---: | ---: | ---")
foreach($pair in $selectedEpisodePairs) {
    $lines.Add((@(
        [string]$pair.ordinal
        [string]$pair.seed
        Escape-Table ([string]$pair.workType)
        Escape-Table ([string]$pair.workId)
        Escape-Table ([string]$pair.currentEpisodePath)
        Escape-Table ([string]$pair.nextEpisodePath)
        [string]$pair.nextEpisodePageCount
        ("{0}/{1}" -f $pair.pairRankOrdinal, $pair.pairCandidateCount)
        Escape-Table ([string]$pair.pairSelectionHash)
    ) -join ' | '))
}
$lines.Add("")

$lines.Add("## 작품별 결과")
$lines.Add("")
$lines.Add("유형 | 작품 ID | 회차 ID | 이미지 수 | 첫 이미지 draw | 전체 이미지 ready | 첫/전체 SLA | Active jank | 최대 present gap | Active FPS | Native FPS | 빈 영역/Runway | 요청/디코드 오류 | 최대 PSS | 결과")
$lines.Add("--- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---")
foreach($case in $cases) {
    $lines.Add((@(
        Escape-Table ([string]$case.workType)
        Escape-Table ([string]$case.workId)
        Escape-Table ([string](Get-Property $case "episodeId"))
        ("{0}/{1}" -f
            (Show-Value (Get-Property $case "imageCount")),
            (Show-Value (Get-Property $case "authoritativePageCount")))
        Show-Value (Get-Property $case "firstActualMs") "ms"
        Show-Value (Get-Property $case "allImagesReadyMs") "ms"
        ("{0}/{1}" -f
            (Show-Value (Get-Property $case "imageSlaMs") "ms"),
            (Show-Value (Get-Property $case "allImagesSlaMs") "ms"))
        Show-Value (Get-Property $case "activePresentationJankPercent") "%"
        Show-Value (Get-Property $case "activePresentationGapMaxMs") "ms"
        Show-Value (Get-Property $case "activePresentationFps") "fps"
        Show-Value (Get-Property $case "nativeScrollFps") "fps"
        ("{0}/{1}" -f
            (Show-Value (Get-Property $case "blankAreaCount")),
            (Show-Value (Get-Property $case "runwayDefectFrames")))
        ("{0}/{1}/{2}" -f
            (Show-Value (Get-Property $case "imageFailureCount")),
            (Show-Value (Get-Property $case "decodeFailureCount")),
            (Show-Value (Get-Property $case "pageListFailureCount")))
        Show-Value (Get-Property $case "maxPssMb") "MB"
        $(if($case.passed) { "PASS" } else { "FAIL" })
    ) -join ' | '))
}
$lines.Add("")

$lines.Add("## 병목 분석")
$lines.Add("")
$lines.Add("case | request→response | response→decode | decode→draw | click→draw | scroll main-running max | 주요 지연 구간")
$lines.Add("--- | ---: | ---: | ---: | ---: | ---: | ---")
foreach($case in $cases) {
    $requestResponse = Get-DeltaMs `
        (Get-Property $case "firstImageRequestStartElapsedNanos") `
        (Get-Property $case "firstImageResponseElapsedNanos")
    $responseDecode = Get-DeltaMs `
        (Get-Property $case "firstImageResponseElapsedNanos") `
        (Get-Property $case "firstImageDecodeElapsedNanos")
    $decodeDraw = Get-DeltaMs `
        (Get-Property $case "firstImageDecodeElapsedNanos") `
        (Get-Property $case "firstImageDrawElapsedNanos")
    $segments = @(@(
        [pscustomobject]@{ label = "network"; value = $requestResponse }
        [pscustomobject]@{ label = "decode"; value = $responseDecode }
        [pscustomobject]@{ label = "draw delivery"; value = $decodeDraw }
    ) | Where-Object { $null -ne $_.value } | Sort-Object value -Descending)
    $major = if($segments.Count -gt 0) { $segments[0].label } else { "미수집" }
    $androidxSingle = Get-Property (Get-Property $case "androidxBenchmark") "single"
    $lines.Add((@(
        Escape-Table ([string]$case.caseId)
        Show-Value $requestResponse "ms"
        Show-Value $responseDecode "ms"
        Show-Value $decodeDraw "ms"
        Show-Value (Get-Property $case "firstActualMs") "ms"
        Show-Value (Get-Property $case "activeMainThreadRunningMaxMs") "ms"
        $major
    ) -join ' | '))
}
$lines.Add("")
$lines.Add("위 구간은 elapsed-realtime telemetry의 동일 세대 request/response/decode/실제 commit 시각으로 계산했다. 원인 확정은 각 case의 Macrobenchmark Perfetto trace와 logcat을 함께 확인해야 하며, 미수집 값을 추정하지 않았다.")
$lines.Add("")

$lines.Add("## 실패 및 콜드 증명")
$lines.Add("")
foreach($case in $cases) {
    $violations = @((Get-Property $case "violations"))
    $reason = if($violations.Count -eq 0) { "없음" } else { $violations -join '; ' }
    $cold = Get-Property $case "coldState"
    $coldText = if($null -eq $cold) {
        "cold_state 없음"
    } else {
        "memory=$($cold.memoryCacheEntries), disk=$($cold.diskCacheFiles)/$($cold.diskCacheBytes)B, content=$($cold.contentCacheEntries), requests=$($cold.activeRequests), decodes=$($cold.activeDecodes), client=$($cold.client)"
    }
    $lines.Add(("- ``{0}``: {1}; {2}; 클릭 전 작업={3} (image={4}, decode={5}, page-list={6}, generation-0={7}); 종료 후 작업={8}, 세대 이탈={9}; elapsed-ns request={10}, response={11}, decode={12}, draw={13}" -f
        $case.caseId,
        $reason,
        $coldText,
        (Get-Property $case "preEntryWorkCount"),
        (Get-Property $case "preEntryImageRequestCount"),
        (Get-Property $case "preEntryDecodeCount"),
        (Get-Property $case "preEntryPageListRequestCount"),
        (Get-Property $case "zeroGenerationPreClickCount"),
        (Get-Property $case "postCloseWorkCount"),
        (Get-Property $case "foreignGenerationWorkCount"),
        (Get-Property $case "firstImageRequestStartElapsedNanos"),
        (Get-Property $case "firstImageResponseElapsedNanos"),
        (Get-Property $case "firstImageDecodeElapsedNanos"),
        (Get-Property $case "firstImageDrawElapsedNanos")))
}
$lines.Add("")

$lines.Add("## 프레임·네트워크·메모리 증거")
$lines.Add("")
$lines.Add('`uiWorkEquivalentFps`와 `native_frame_summary`는 진단값이다. formal FPS/jank 판정은 Reader SurfaceView의 SurfaceFlinger `PresentFenceSignaled` 프레임만 사용한다.')
$lines.Add("")
foreach($case in $cases) {
    $androidx = Get-Property $case "androidxBenchmark"
    $androidxSingle = Get-Property $androidx "single"
    $lines.Add(("- ``{0}``: native FPS={1}/{2}, slow={3}%, worst={4}ms, max-consecutive={5}; JankStats={6}%/{7}ms; HTTP clients={8}, protocol={9}, socket={10}, reused={11}; cancel image/decode/page={12}/{13}/{14}; PSS entry/max/exit={15}/{16}/{17}MB; bitmap max={18}B, GC={19}" -f
        $case.caseId,
        (Show-Value (Get-Property $case "nativeScrollFps")),
        (Show-Value (Get-Property $case "nativeScrollFpsTarget")),
        (Show-Value (Get-Property $case "nativeSlowIntervalPercent")),
        (Show-Value (Get-Property $case "nativeWorstIntervalMs")),
        (Show-Value (Get-Property $case "nativeMaxConsecutiveSlowIntervals")),
        (Show-Value (Get-Property $case "jankRatio")),
        (Show-Value (Get-Property $case "maxFrameMs")),
        (Show-Value (Get-Property $case "httpClientInstanceCount")),
        (Show-Value (Get-Property $case "firstRequestProtocol")),
        (Show-Value (Get-Property $case "firstRequestConnectionId")),
        (Show-Value (Get-Property $case "firstRequestConnectionReused")),
        (Show-Value (Get-Property $case "imageCancellationCount")),
        (Show-Value (Get-Property $case "decodeCancellationCount")),
        (Show-Value (Get-Property $case "pageListCancellationCount")),
        (Show-Value (Get-Property $case "entryPssMb")),
        (Show-Value (Get-Property $case "maxPssMb")),
        (Show-Value (Get-Property $case "exitPssMb")),
        (Show-Value (Get-Property $case "maxBitmapBytes")),
        (Show-Value (Get-Property $case "gcCount"))))
    $lines.Add(("  - AndroidX: frames={0}, CPU-frame max={1}ms, overrun max={2}ms, jank={3}%, startup={4}ms, ViewerOpen={5}ms, cold scroll CPU={6}ms/{7}%, main-running max={8}ms" -f
        (Show-Value (Get-Property $androidx "frameSampleCount")),
        (Show-Value (Get-Property $androidx "frameDurationCpuMaxMs")),
        (Show-Value (Get-Property $androidx "frameOverrunMaxMs")),
        (Show-Value (Get-Property $androidx "jankPercent")),
        (Show-Value (Get-Property $androidxSingle "timeToInitialDisplayMs")),
        (Show-Value (Get-Property $androidxSingle "ViewerOpenFirstMs")),
        (Show-Value (Get-Property $androidxSingle "viewerScrollCpuTimeMs")),
        (Show-Value (Get-Property $androidxSingle "viewerScrollCpuPercent")),
        (Show-Value (Get-Property $androidxSingle "viewerScrollMainThreadRunningMaxMs"))))
    if($summary.includeWarmReopen -eq $true) {
        $lines.Add(("  - same-process warm (cold metrics 밖): passed={0}, first-actual={1}ms, retained-PSS={2}/{3}KB" -f
            (Show-Value (Get-Property $case "warmPassed")),
            (Show-Value (Get-Property $case "warmFirstActualMs")),
            (Show-Value (Get-Property $case "retainedPssGrowthKb")),
            (Show-Value (Get-Property $case "retainedPssGrowthLimitKb"))))
    }
    $lines.Add(("  - request queue peak={0}/{1}, terminal={2}, measured={3}; forward gestures={4} ({5}..{6}), interval={7}..{8}, image/decode cancels={9}/{10}" -f
        (Show-Value (Get-Property $case "peakActiveRequestQueue")),
        (Show-Value (Get-Property $case "peakActiveRequestQueueLimit")),
        (Show-Value (Get-Property $case "requestQueueTerminalBalance")),
        (Show-Value (Get-Property $case "requestQueueMetricsMeasured")),
        (Show-Value (Get-Property $case "forwardTraversalGestureCount")),
        (Show-Value (Get-Property $case "forwardTraversalGestureMin")),
        (Show-Value (Get-Property $case "forwardTraversalGestureMax")),
        (Show-Value (Get-Property $case "forwardTraversalStartElapsedNanos")),
        (Show-Value (Get-Property $case "forwardTraversalEndElapsedNanos")),
        (Show-Value (Get-Property $case "imageCancellationCount")),
        (Show-Value (Get-Property $case "decodeCancellationCount"))))
    $lines.Add(("  - evidence: Macrobenchmark traces={0}/{1}, empty={2}, bytes={3}, pull={4}; visual={5}/{6}, empty={7}, bytes={8}, pull={9}" -f
        (Show-Value (Get-Property $case "nonEmptyMacroTraceArtifactCount")),
        (Show-Value (Get-Property $case "macroTraceArtifactCount")),
        (Show-Value (Get-Property $case "emptyMacroTraceArtifactCount")),
        (Show-Value (Get-Property $case "macroTraceArtifactBytes")),
        (Show-Value (Get-Property $case "benchmarkArtifactPullExitCode")),
        (Show-Value (Get-Property $case "nonEmptyVisualEvidenceArtifactCount")),
        (Show-Value (Get-Property $case "visualEvidenceArtifactCount")),
        (Show-Value (Get-Property $case "emptyVisualEvidenceArtifactCount")),
        (Show-Value (Get-Property $case "visualEvidenceArtifactBytes")),
        (Show-Value (Get-Property $case "visualEvidencePullExitCode"))))
    $lines.Add(("  - traversal={0}/{1}, missing={2}, committed={3}/{4}, manifest={5}; Perfetto={6}" -f
        (Show-Value (Get-Property $case "observedSourceCount")),
        (Show-Value (Get-Property $case "authoritativePageCount")),
        (Show-Value (Get-Property $case "missingSourceIndexes")),
        (Show-Value (Get-Property $case "validCommittedFrames")),
        (Show-Value (Get-Property $case "invalidCommittedFrames")),
        (Show-Value (Get-Property $case "manifestDigest")),
        (@((Get-Property $case "macroTraceArtifacts")) -join ', ')))
}
$lines.Add("")

$lines.Add("## 코드 변경 및 판정 경계")
$lines.Add("")
$lines.Add("- 프로덕션 APK: ``$($summary.apks.app)`` (SHA-256 ``$($summary.apks.appSha256)``)")
$lines.Add("- 측정 APK: ``$($summary.apks.benchmark)`` (SHA-256 ``$($summary.apks.benchmarkSha256)``)")
$lines.Add("- 변경 전 자격 경로: 고정 작품·에뮬레이터·전체 페이지 선행 staging 경로가 존재했다.")
$lines.Add("- 변경 후 자격 경로: ``ntk_emulator_host_qualification.ps1`` → ``ntk_cold_qualification.ps1`` 한 경로만 사용하며, 실행 시 새 무작위 시드로 작품과 current→next 회차 pair를 각각 hash-rank하고 20+20·첫 이미지 4000ms·전체 이미지 완료 8000ms·Host GPU 에뮬레이터를 고정한다. 기록된 selection.json 고정 시드 재현과 선택적 웜 재개방은 진단 전용이다.")
$lines.Add("- 테스트용 변경: Macrobenchmark는 production ``#작품ID`` 검색 UI, 회차 행 탭, 순방향 스크롤을 수행한다. viewer Activity나 이미지 URL을 직접 실행하지 않는다.")
$lines.Add("- 프로덕션 변경: 이 측정 보고서는 APK에서 소스 diff를 역추정하지 않는다. 위 APK hash와 별도 VCS diff를 함께 보관해야 하며, 측정값이 없는 before/after 수치는 작성하지 않는다.")
$lines.Add("- 테스트 통과 전용 분기 확인: 특정 작품 ID 분기 없이 모든 사용자가 쓸 수 있는 production 정확 검색과 production UI를 사용한다. 클릭 전 image/page-list/decode 작업은 1건이라도 case FAIL이다.")
$lines.Add("")

$lines.Add("## 재현")
$lines.Add("")
$commands = [ordered]@{
    "빌드" = $summary.reproducibility.build
    "앱 APK 설치" = $summary.reproducibility.installApp
    "Macrobenchmark APK 설치" = $summary.reproducibility.installBenchmark
    "선정 첫 case 직접 Macrobenchmark" = $summary.reproducibility.macrobenchmark
    "기록된 exact pair 전체 재실행" = $summary.reproducibility.rerun
    "Perfetto config 전송" = $summary.reproducibility.perfettoPush
    "Perfetto 시작" = $summary.reproducibility.perfettoStart
    "Perfetto 종료" = $summary.reproducibility.perfettoStop
    "Perfetto 수집" = $summary.reproducibility.perfettoPull
}
foreach($entry in $commands.GetEnumerator()) {
    $lines.Add("### $($entry.Key)")
    $lines.Add("")
    $lines.Add('```powershell')
    $lines.Add([string]$entry.Value)
    $lines.Add('```')
    $lines.Add("")
}
$lines.Add("- 결과 디렉터리: ``$($summary.reproducibility.output)``")
$lines.Add("- exact pair selection: ``$($summary.reproducibility.selection)`` (SHA-256 ``$($summary.reproducibility.selectionSha256)``)")
$lines.Add("- 인증: $($summary.reproducibility.authentication)")
$lines.Add("- 필요 환경: PowerShell 7.2+, JDK/Android SDK, ``adb``와 Gradle wrapper, 연결된 실제 Android 기기. ``ANDROID_SERIAL`` 대신 보고서의 ``-DeviceSerial``을 명시한다.")
$lines.Add("- 작품별 instrumentation/logcat/meminfo/gfxinfo/cpuinfo, cold proof, Macrobenchmark JSON/Perfetto trace와 스크린샷은 각 case artifact directory에 있다.")
$lines.Add("")
$lines.Add('HTTP client 신규 생성과 socket 재사용은 telemetry로 기록한다. CDN·통신사·커널 페이지 캐시는 비루트 환경에서 통제 불가이며 formal cold gate는 앱 process/image/content cache에 적용한다.')

$parent = Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
if(-not (Test-Path -LiteralPath $parent)) {
    [void](New-Item -ItemType Directory -Path $parent -Force)
}
[IO.File]::WriteAllLines(
    [IO.Path]::GetFullPath($OutputPath),
    $lines,
    [Text.UTF8Encoding]::new($false)
)
Write-Host "NTK cold report: $([IO.Path]::GetFullPath($OutputPath))"
