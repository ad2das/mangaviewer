package ml.melun.mangaview.viewer

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.activity.ViewerActivity
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidencePacking
import ml.melun.mangaview.viewer.runtime.PresentedImageRegion
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.viewer.runtime.ViewerStartupTiming
import ml.melun.mangaview.viewer.runtime.ViewerCachedResumeDiagnostic
import ml.melun.mangaview.viewer.runtime.ViewerCachedResumeRoute
import org.junit.Test
import org.junit.runner.RunWith

/** One cold-process, no-readiness-wait startup trial for the frozen STARTUP comparison. */
@RunWith(AndroidJUnit4::class)
class StartupActivationBenchmarkTest {
    @Test
    fun captureSingleStartupTrial() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val pair = arguments.requiredInt(ARG_PAIR)
        val trialInPair = arguments.requiredInt(ARG_TRIAL)
        val mode = StartupActivationMode.parse(arguments.getString(ARG_MODE))
        check(mode == StartupActivationBenchmarkPolicy.expectedMode(pair, trialInPair)) {
            "Frozen AB/BA order mismatch: pair=$pair trial=$trialInPair mode=$mode " +
                "expected=${StartupActivationBenchmarkPolicy.expectedMode(pair, trialInPair)}"
        }
        val artifacts = StartupActivationBenchmarkArtifacts(
            context = instrumentation.targetContext,
            prefix = arguments.getString(ARG_ARTIFACT_PREFIX) ?: DEFAULT_ARTIFACT_PREFIX,
            pair = pair,
            trialInPair = trialInPair,
            mode = mode,
        )

        var apkSha256: String? = null
        var episode: EpisodeId? = null
        var savedPosition: ReadingPosition? = null
        var cacheBefore: StartupCacheFingerprint? = null
        var cacheAfter: StartupCacheFingerprint? = null
        try {
            val application = instrumentation.targetContext.applicationContext as ViewerApplication
            val currentApkSha256 = sha256(File(application.applicationInfo.sourceDir))
            apkSha256 = currentApkSha256
            val selectedEpisode = episodeFrom(arguments)
            episode = selectedEpisode
            check(selectedEpisode.seriesId.sourceId == SourceId(NTK_SOURCE_ID)) {
                "Startup comparison is fixed to the NTK source"
            }

            val cacheFile = completeResumeSnapshotFile(application, selectedEpisode)
            val before = fingerprint(cacheFile)
            cacheBefore = before
            check(before.exists && before.sha256 != null) {
                "Missing normal-use complete NTK snapshot; no cache may be injected: $cacheFile"
            }

            val storedPosition = runBlocking(Dispatchers.IO) {
                application.graph.userLibrary.readingPosition(selectedEpisode)
            }
            val exactSavedPosition = requireExactSavedPosition(arguments, storedPosition)
            savedPosition = exactSavedPosition

            // This timestamp is the frozen primary-window boundary. It is deliberately sampled
            // before either condition so the eager hook cannot move its cost outside the metric.
            val entryRequestedAtNanos = System.nanoTime()
            // This is intentionally the only condition difference. The hook starts the same
            // DeferredContentSource used by the current graph, but does not await its work.
            if (mode == StartupActivationMode.EAGER) {
                application.graph.activateNtkForStartupBenchmarkOnly()
            }

            val scenario = ActivityScenario.launch<ViewerActivity>(
                launchIntent(selectedEpisode, exactSavedPosition),
            )
            val observation = try {
                awaitFirstActualSubmission(scenario, selectedEpisode, exactSavedPosition)
            } finally {
                scenario.close()
            }
            val after = fingerprint(cacheFile)
            cacheAfter = after
            check(after == before) {
                "Complete-resume snapshot changed during startup trial: before=$before after=$after"
            }

            val timing = requireNotNull(observation.timing) { "Viewer startup timing was unavailable" }
            check(timing.initialResponseStartedAtNanos == null) {
                "Trial left the complete-cache route and started a source response at " +
                    timing.initialResponseStartedAtNanos
            }
            check(observation.anchor == exactSavedPosition) {
                "First actual submission moved from the exact saved position: " +
                    "expected=$exactSavedPosition actual=${observation.anchor}"
            }
            check(observation.userInputRevision == 0L) {
                "Startup trial received input before measurement: ${observation.userInputRevision}"
            }
            val cachedResume = requireNotNull(observation.cachedResume)
            check(StartupActivationEvidencePolicy.completeLeaseMatches(
                cachedResume,
                selectedEpisode,
                EXPECTED_PAGE_COUNT,
            )) {
                "Startup trial did not use this session's complete cache lease: $cachedResume"
            }
            val candidate = requireNotNull(observation.candidate)
            val candidateRegion = requireNotNull(observation.candidateRegion)
            val result = StartupActivationTrialResult(
                pair = pair,
                trialInPair = trialInPair,
                mode = mode,
                apkSha256 = currentApkSha256,
                episode = selectedEpisode,
                savedPosition = exactSavedPosition,
                cacheBefore = before,
                cacheAfter = after,
                entryRequestedAtNanos = entryRequestedAtNanos,
                openStartedAtNanos = timing.openStartedAtNanos,
                manifestReadyAtNanos = timing.manifestReadyAtNanos,
                firstActualSubmittedAtNanos = candidate.submittedAtNanos,
                firstProxyTimestampNanos = candidate.presentedNanos.takeIf { it > 0L },
                firstProxyTimestampKind = candidate.timestampKind.name,
                trackerFirstActualSubmittedAtNanos = timing.firstActualSubmittedAtNanos,
                trackerFirstActualPresentedAtNanos = timing.firstActualPresentedAtNanos,
                initialResponseStartedAtNanos = timing.initialResponseStartedAtNanos,
                observedAnchor = observation.anchor,
                observedUserInputRevision = observation.userInputRevision,
                cachedResumeRoute = cachedResume.route,
                cachedResumeEpisode = cachedResume.episodeId,
                cachedResumePageCount = cachedResume.manifestPageCount,
                cachedResumeAtNanos = cachedResume.routeAtNanos,
                candidateToken = candidate.token,
                candidateRendererIdentity = candidate.rendererIdentity,
                candidateGeneration = candidate.generation,
                candidateAnchorOffsetUnits = candidate.anchorOffsetUnits,
                candidateBufferFrameId = candidate.bufferFrameId,
                candidateRegionPageId = candidateRegion.pageId.remoteKey,
                candidateRegionIdentityVerified = candidateRegion.imageIdentityVerified,
                candidateReadableActualContent = candidate.readableActualContent,
                candidateFullVisualCoverage = candidate.fullVisualCoverage,
                candidateFullActualCoverage = candidate.fullActualCoverage,
            )
            val report = artifacts.write(result, observation.evidence)
            check(report.isFile) { "Startup trial report was not written: $report" }
        } catch (failure: Throwable) {
            runCatching {
                artifacts.writeFailure(
                    pair = pair,
                    trialInPair = trialInPair,
                    mode = mode,
                    reason = failure.message ?: failure.javaClass.name,
                    apkSha256 = apkSha256,
                    episode = episode,
                    savedPosition = savedPosition,
                    cacheBefore = cacheBefore,
                    cacheAfter = cacheAfter,
                )
            }
            throw failure
        }
    }

    private fun awaitFirstActualSubmission(
        scenario: ActivityScenario<ViewerActivity>,
        expectedEpisode: EpisodeId,
        expectedPosition: ReadingPosition,
    ): StartupObservation {
        val deadline = SystemClock.elapsedRealtime() +
            StartupActivationBenchmarkPolicy.FIRST_SUBMISSION_TIMEOUT_MILLIS
        var latest: StartupObservation? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = readObservation(scenario, expectedPosition)
            latest = current
            current.failure?.let { throw AssertionError("Viewer failed during startup trial", it) }
            current.cachedResume?.let { cached ->
                if (cached.route == ViewerCachedResumeRoute.SOURCE_FALLBACK) {
                    error("Complete-resume lease failed and source fallback was selected: $cached")
                }
            }
            val candidate = current.candidate
            val timing = current.timing
            val anchor = current.anchor
            val cached = current.cachedResume
            if (candidate != null && timing?.manifestReadyAtNanos != null && anchor == expectedPosition &&
                StartupActivationEvidencePolicy.completeLeaseMatches(
                    cached,
                    expectedEpisode,
                    EXPECTED_PAGE_COUNT,
                ) &&
                current.candidateRegion != null && !current.regionHistoryDropped
            ) {
                return current
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        error(
            "First actual cached-image submission was unavailable after " +
                "${StartupActivationBenchmarkPolicy.FIRST_SUBMISSION_TIMEOUT_MILLIS}ms; " +
                "this is a missing condition, not a fabricated timing result. latest=$latest",
        )
    }

    private fun readObservation(
        scenario: ActivityScenario<ViewerActivity>,
        expectedPosition: ReadingPosition,
    ): StartupObservation {
        val reference = AtomicReference<StartupObservation>()
        scenario.onActivity { activity ->
            val evidence = NativePresentationEvidencePacking.decode(
                activity.presentationEvidenceSnapshot(),
            )
            val regionBatch = activity.presentedRegionsSince(0L)
            val regions = regionBatch.regions
            val firstSubmittedGeneration = evidence
                .asSequence()
                .filter { sample -> sample.submittedAtNanos > 0L }
                .minByOrNull { sample -> sample.submittedAtNanos }
                ?.generation
            val candidateBinding = evidence.asSequence().mapNotNull { sample ->
                regions.firstOrNull { region ->
                    StartupActivationEvidencePolicy.firstActualSubmissionMatches(
                        sample,
                        region,
                        expectedPosition,
                        firstSubmittedGeneration,
                    )
                }?.let { region -> CandidateBinding(sample, region) }
            }
                .minByOrNull { binding -> binding.evidence.submittedAtNanos }
            val telemetry = activity.viewerTelemetrySnapshot()
            reference.set(
                StartupObservation(
                    timing = activity.viewerStartupTimingSnapshot(),
                    evidence = evidence,
                    candidate = candidateBinding?.evidence,
                    candidateRegion = candidateBinding?.region,
                    regionHistoryDropped = regionBatch.dropped,
                    anchor = telemetry?.anchor,
                    userInputRevision = telemetry?.userInputRevision,
                    cachedResume = activity.viewerCachedResumeSnapshot(),
                    failure = activity.viewerFailureSnapshot(),
                ),
            )
        }
        return requireNotNull(reference.get())
    }

    private fun launchIntent(episode: EpisodeId, position: ReadingPosition) =
        android.content.Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ViewerActivity::class.java,
        ).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, episode.seriesId.sourceId.value)
            putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, episode.seriesId.remoteKey)
            putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, episode.remoteKey)
            putExtra(ViewerLaunchSpec.EXTRA_PAGE_KEY, position.pageId.remoteKey)
            putExtra(ViewerLaunchSpec.EXTRA_PAGE_OFFSET_UNITS, position.offsetInPageUnits)
        }

    private fun episodeFrom(arguments: android.os.Bundle): EpisodeId {
        val source = arguments.getString(ARG_SOURCE_ID) ?: NTK_SOURCE_ID
        val series = arguments.getString(ARG_SERIES_KEY) ?: DEFAULT_SERIES_KEY
        val episode = arguments.getString(ARG_EPISODE_KEY) ?: DEFAULT_EPISODE_KEY
        return EpisodeId(SeriesId(SourceId(source), series), episode)
    }

    private fun requireExactSavedPosition(
        arguments: android.os.Bundle,
        stored: ReadingPosition?,
    ): ReadingPosition {
        val actual = requireNotNull(stored) {
            "No existing saved position for the selected NTK episode; refusing to invent one"
        }
        val expectedPage = arguments.getString(ARG_SAVED_PAGE_KEY)
        val expectedOffset = arguments.getString(ARG_SAVED_OFFSET_UNITS)?.toLongOrNull()
        check((expectedPage == null) == (expectedOffset == null)) {
            "startupSavedPageKey and startupSavedOffsetUnits must be supplied together"
        }
        if (expectedPage != null) {
            val expected = ReadingPosition(
                PageId(actual.pageId.episodeId, expectedPage),
                requireNotNull(expectedOffset),
            )
            check(expected == actual) {
                "Existing saved position differs from the requested frozen position: " +
                    "expected=$expected actual=$actual"
            }
        }
        return actual
    }

    private fun completeResumeSnapshotFile(
        application: ViewerApplication,
        episode: EpisodeId,
    ): File {
        val key = PageCacheKey.of(PageId(episode, COMPLETE_RESUME_MANIFEST_KEY))
        return File(
            application.applicationInfo.dataDir,
            "app_complete_resume_v1/$key.snapshot",
        )
    }

    private fun fingerprint(file: File): StartupCacheFingerprint {
        if (!file.isFile) return StartupCacheFingerprint(file.absolutePath, false, 0L, null)
        return StartupCacheFingerprint(file.absolutePath, true, file.length(), sha256(file))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private data class StartupObservation(
        val timing: ViewerStartupTiming?,
        val evidence: List<NativePresentationEvidence>,
        val candidate: NativePresentationEvidence?,
        val candidateRegion: PresentedImageRegion?,
        val regionHistoryDropped: Boolean,
        val anchor: ReadingPosition?,
        val userInputRevision: Long?,
        val cachedResume: ViewerCachedResumeDiagnostic?,
        val failure: Throwable?,
    )

    private data class CandidateBinding(
        val evidence: NativePresentationEvidence,
        val region: PresentedImageRegion,
    )

    private companion object {
        const val ARG_MODE = "startupMode"
        const val ARG_PAIR = "startupPair"
        const val ARG_TRIAL = "startupTrial"
        const val ARG_SOURCE_ID = "startupSourceId"
        const val ARG_SERIES_KEY = "startupSeriesKey"
        const val ARG_EPISODE_KEY = "startupEpisodeKey"
        const val ARG_SAVED_PAGE_KEY = "startupSavedPageKey"
        const val ARG_SAVED_OFFSET_UNITS = "startupSavedOffsetUnits"
        const val ARG_ARTIFACT_PREFIX = "startupArtifactPrefix"
        const val NTK_SOURCE_ID = "ntk"
        const val DEFAULT_SERIES_KEY = "/webtoon/57451201"
        const val DEFAULT_EPISODE_KEY = "/webtoon/57451201/jjaptoon-1341148"
        const val DEFAULT_ARTIFACT_PREFIX = "startup-activation-comparison"
        const val COMPLETE_RESUME_MANIFEST_KEY = "complete-resume-manifest"
        const val EXPECTED_PAGE_COUNT = 132
        const val POLL_INTERVAL_MILLIS = 20L

        fun android.os.Bundle.requiredInt(key: String): Int =
            getString(key)?.toIntOrNull() ?: error("Missing integer instrumentation argument: $key")
    }
}
