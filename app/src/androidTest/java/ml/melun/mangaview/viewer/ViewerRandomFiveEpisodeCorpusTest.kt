package ml.melun.mangaview.viewer

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import org.json.JSONArray
import org.json.JSONObject
import ml.melun.mangaview.source.SourceEpisode
import org.junit.Test
import org.junit.runner.RunWith

/** One invocation owns all 200 episodes. Any failure resets the complete attempt to zero. */
@RunWith(AndroidJUnit4::class)
class ViewerRandomFiveEpisodeCorpusTest {
    @Test
    fun tenRandomLiveSeriesScrollFiveConsecutiveEpisodesEach() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(!arguments.containsKey("corpusSource") && !arguments.containsKey("corpusKind")) {
            "Partial category invocations cannot qualify as a 200-episode attempt"
        }
        val seed = requireNotNull(arguments.getString("corpusSeed")).toLong()
        val runId = requireNotNull(arguments.getString("corpusRunId"))
        val externalDisplay = arguments.getString("corpusExternalDisplay") == "true"
        val regressionsOnly = arguments.getString("corpusRegressionsOnly") == "true"
        require(runId.matches(Regex("[A-Za-z0-9_-]+")))
        val root = requireNotNull(instrumentation.targetContext.getExternalFilesDir("ux-evidence"))
        val directory = root.resolve("qualification-$runId")
        check(!directory.exists() && directory.mkdirs()) { "Qualification attempt identifiers cannot be reused" }
        val priorFile = root.resolve("qualification-prior-failures.json")
        val prior = if (priorFile.exists()) JSONArray(priorFile.readText()) else JSONArray()
        val singleFile = root.resolve("qualification-single-episode-regressions.json")
        check(singleFile.isFile) { "Mandatory single-episode sidecar is missing" }
        val singleRegressions = JSONArray(singleFile.readText()).let { records ->
            (0 until records.length()).map { index ->
                SingleEpisodeRegression.fromJson(records.getJSONObject(index))
            }
        }
        check(singleRegressions.map { it.source to it.kind to it.seriesKey to it.episodeKey }
            .distinct().size == singleRegressions.size) {
            "Mandatory single-episode sidecar contains duplicate identities"
        }
        directory.resolve("single-episode-regressions.json").writeText(singleFile.readText())
        val outcomes = JSONArray()
        var completed = 0
        var regressionPhaseCompleted = false
        var failure: Throwable? = null
        val started = SystemClock.elapsedRealtime()
        val application = instrumentation.targetContext.applicationContext as ViewerApplication
        val policyText = arguments.getString("corpusPolicyPath")?.let { policyPath ->
            val file = File(policyPath).canonicalFile
            check(file.parentFile == root.canonicalFile) { "Policy must be an immutable evidence file under ux-evidence" }
            file.readText()
        } ?: "{\"exceptions\":[]}"
        directory.resolve("policy.json").writeText(policyText)
        try {
            val timingPolicy = QualificationTimingPolicy.fromJson(policyText, Build.FINGERPRINT)
            val externalBarrier = if (externalDisplay) QualificationExternalVerdictBarrier(
                directory.resolve("external-verdicts"), runId,
                requireNotNull(arguments.getString("corpusAttemptSha256")), sha256(policyText),
            ) else null
            val discovery = QualificationCorpus(application.graph.sources, directory)
            for ((index, regression) in singleRegressions.withIndex()) {
                val sampleKey = "single-$runId-${index + 1}"
                try {
                    val resolved = discovery.resolveSingleEpisode(regression)
                    runSingleSample(regression, resolved, sampleKey, directory, externalDisplay, timingPolicy)
                    externalBarrier?.await(sampleKey)
                    outcomes.put(singleOutcome(regression, sampleKey, true, null)
                        .put("collectionCompleted", true))
                } catch (caught: Throwable) {
                    val failureText = caught.message ?: caught.javaClass.name
                    outcomes.put(singleOutcome(regression, sampleKey, false, failureText))
                    directory.resolve("single-episode-failure.json").writeText(JSONObject()
                        .put("sampleKey", sampleKey)
                        .put("role", SingleEpisodeRegression.ROLE)
                        .put("singleEpisodeRegression", regression.toJson())
                        .put("failure", failureText)
                        .put("provenance", JSONArray(regression.provenance.map { JSONObject(it.toString()) }))
                        .toString(2))
                    throw caught
                } finally {
                    writeOutcomes(directory, outcomes)
                }
            }
            for (index in 0 until prior.length()) {
                val sample = discovery.refreshRegression(CorpusSeriesSample.fromJson(prior.getJSONObject(index).getJSONObject("sample")))
                val sampleKey = "regression-$index-$runId"
                runSample(sample, sampleKey, directory, externalDisplay, timingPolicy)
                externalBarrier?.await(sampleKey)
                outcomes.put(sample.toJson().put("sampleKey", sampleKey)
                    .put("role", "MANDATORY_REGRESSION_NO_CORPUS_CREDIT").put("passed", true)
                    .put("failure", JSONObject.NULL))
                writeOutcomes(directory, outcomes)
            }
            regressionPhaseCompleted = true
            if (regressionsOnly) return
            val corpus = discovery.discover(seed)
            for ((index, sample) in corpus.withIndex()) {
                val sampleKey = "corpus-$runId-${index + 1}"
                Log.i("Random200Corpus", "START ${index + 1}/40 key=${sample.key} completed=$completed/200")
                try {
                    runSample(sample, sampleKey, directory, externalDisplay, timingPolicy)
                    externalBarrier?.await(sampleKey)
                    completed += sample.chain.size
                    outcomes.put(sample.toJson().put("sampleKey", sampleKey).put("role", "CORPUS")
                        .put("passed", !externalDisplay)
                        .put("collectionCompleted", true).put("failure", JSONObject.NULL))
                } catch (caught: Throwable) {
                    val failureText = caught.message ?: caught.javaClass.name
                    outcomes.put(sample.toJson().put("sampleKey", sampleKey).put("role", "CORPUS")
                        .put("passed", false).put("failure", failureText))
                    prior.put(JSONObject().put("runId", runId).put("seed", seed)
                        .put("sample", sample.toJson()).put("failure", failureText))
                    priorFile.writeText(prior.toString(2))
                    throw caught
                } finally {
                    writeOutcomes(directory, outcomes)
                }
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            val collected = failure == null && completed == 200
            val passed = collected && !externalDisplay
            directory.resolve("summary.json").writeText(JSONObject()
                .put("schema", 2).put("runId", runId).put("seed", seed).put("passed", passed)
                .put("scope", if (regressionsOnly) "MANDATORY_REGRESSIONS_ONLY" else "FINAL_200")
                .put("regressionPhaseCompleted", regressionPhaseCompleted && failure == null)
                .put("collectionCompleted", collected).put("externalDisplayVerificationRequired", externalDisplay)
                .put("requiredEpisodes", 200).put("attemptedCompletedEpisodes", completed)
                .put("consecutivePassed", if (passed) 200 else 0)
                .put("failurePolicy", "ANY_FAILURE_RESETS_ENTIRE_200_TO_ZERO_AND_RERANDOMIZES_NEXT_ATTEMPT")
                .put("policySha256", sha256(policyText)).put("deviceFingerprint", Build.FINGERPRINT)
                .put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL).toString(2))
        }
        check(failure == null && completed == 200) {
            "200-episode record reset to zero: ${failure?.message}; evidence=${directory.absolutePath}"
        }
    }

    private fun runSample(sample: CorpusSeriesSample, prefix: String, directory: File, externalDisplay: Boolean,
        timingPolicy: QualificationTimingPolicy) = runEntry(
        episode = sample.chain.first(), expectedEpisodes = sample.chain.map { it.id }, prefix = prefix,
        directory = directory, externalDisplay = externalDisplay, timingPolicy = timingPolicy,
        wrapper = JSONObject().put("sampleKey", prefix).put("sample", sample.toJson()),
        prepare = { ui, before -> ui.prepare(sample, before) },
        open = { ui -> ui.open(sample) },
    )

    private fun runSingleSample(
        regression: SingleEpisodeRegression,
        resolved: ResolvedSingleEpisodeRegression,
        prefix: String,
        directory: File,
        externalDisplay: Boolean,
        timingPolicy: QualificationTimingPolicy,
    ) = runEntry(
        episode = resolved.episode,
        expectedEpisodes = listOf(resolved.episode.id),
        prefix = prefix,
        directory = directory,
        externalDisplay = externalDisplay,
        timingPolicy = timingPolicy,
        wrapper = JSONObject().put("sampleKey", prefix)
            .put("role", SingleEpisodeRegression.ROLE)
            .put("singleEpisodeRegression", regression.toJson())
            .put("resolvedSeries", JSONObject()
                .put("source", resolved.series.id.sourceId.value)
                .put("kind", regression.kind.name)
                .put("seriesKey", resolved.series.id.remoteKey)
                .put("title", resolved.series.title))
            .put("resolvedEpisode", JSONObject()
                .put("episodeKey", resolved.episode.id.remoteKey)
                .put("title", resolved.episode.title)),
        prepare = { ui, before -> ui.prepare(resolved, before) },
        open = { ui -> ui.open(resolved) },
    )

    private fun runEntry(
        episode: SourceEpisode,
        expectedEpisodes: List<EpisodeId>,
        prefix: String,
        directory: File,
        externalDisplay: Boolean,
        timingPolicy: QualificationTimingPolicy,
        wrapper: JSONObject,
        prepare: (CorpusUiEntry, () -> Unit) -> Unit,
        open: (CorpusUiEntry) -> ViewerUiLaunch,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sampleDirectory = directory.resolve(prefix).apply { check(mkdirs()) }
        sampleDirectory.resolve("sample.json").writeText(wrapper.toString(2))
        CorpusUiEntry(instrumentation).use { ui ->
            val memory = QualificationMemory(instrumentation, sampleDirectory)
            var failure: Throwable? = null
            try {
                prepare(ui) { memory.capture("before-viewer") }
                val launch = open(ui)
                val liveEpisode = episode.id
                ViewerTenEpisodeAutoAppendHarness(instrumentation, prefix, expectedEpisodes.size, expectedEpisodes,
                    checkpoint = { memory.capture("active") }, externalDisplay = externalDisplay,
                    artifactParent = sampleDirectory, timingPolicy = timingPolicy, diagnosticMode = false)
                    .run(LiveEpisode(liveEpisode.seriesId.sourceId.value, liveEpisode.seriesId.remoteKey,
                        liveEpisode.remoteKey), launch)
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                failure = CorpusCleanupContract.finish(failure, listOf(
                    { memory.beginViewerClose(); ui.closeViewer() },
                    { ui.returnToSearch() },
                    { memory.capture("after-viewer") },
                    {
                        val memoryFailures = memory.finish()
                        check(memoryFailures.isEmpty()) {
                            "${failure?.message.orEmpty()}; ${memoryFailures.joinToString()}"
                        }
                    },
                ))
            }
            failure?.let { throw it }
        }
    }

    private fun singleOutcome(
        regression: SingleEpisodeRegression,
        sampleKey: String,
        passed: Boolean,
        failure: String?,
    ): JSONObject = regression.toJson().put("sampleKey", sampleKey)
        .put("role", SingleEpisodeRegression.ROLE)
        .put("passed", passed)
        .put("failure", failure ?: JSONObject.NULL)

    private fun writeOutcomes(directory: File, outcomes: JSONArray) =
        directory.resolve("outcomes.json").writeText(outcomes.toString(2))

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Cleanup must finish every evidence action and retain the first sample failure. */
internal object CorpusCleanupContract {
    fun finish(primary: Throwable?, actions: List<() -> Unit>): Throwable? {
        var cleanupFailure: Throwable? = null
        actions.forEach { action ->
            try {
                action()
            } catch (caught: Throwable) {
                if (cleanupFailure == null) cleanupFailure = caught
                else cleanupFailure?.addSuppressed(caught)
            }
        }
        cleanupFailure?.let { cleanup ->
            if (primary != null) primary.addSuppressed(cleanup)
            else return cleanup
        }
        return primary
    }
}
