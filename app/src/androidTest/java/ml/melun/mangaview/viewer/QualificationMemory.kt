package ml.melun.mangaview.viewer

import android.app.ActivityManager
import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

private const val SHELL_TIMEOUT_SECONDS = 5
private const val LIVE_ACTIVE_DRAIN_TIMEOUT_MILLIS = 30_000L
private const val LIVE_SAMPLER_TERMINATION_TIMEOUT_MILLIS = 2_000L

internal object QualificationMemoryPolicy {
    private const val MIB = 1_024L * 1_024L

    fun maximumRiseKib(totalRamBytes: Long?): Long =
        (totalRamBytes?.takeIf { it > 0L }?.div(16L)?.coerceAtMost(768L * MIB)
            ?: (256L * MIB)) / 1_024L

    fun violations(samples: List<OwnedPssSample>, totalRam: Long?): List<String> {
        val before = samples.firstOrNull { it.isMeasuredAt("before-viewer") }
        val startup = samples.firstOrNull { it.isMeasuredAt("before-catalog") } ?: before
        val active = samples.filter { it.isMeasuredAt("active") }
        val after = samples.lastOrNull { it.isMeasuredAt("after-viewer") }
        val errors = mutableListOf<String>()
        if (before == null) errors += "All-owned-process PSS baseline is missing"
        if (active.isEmpty()) errors += "Active all-owned-process PSS was not measured"
        val baseline = before?.totalKib ?: 0L
        val increase = maxOf(active.maxOfOrNull { it.totalKib } ?: baseline, baseline) - (startup?.totalKib ?: baseline)
        if (increase > maximumRiseKib(totalRam)) {
            errors += "Owned-process PSS rise ${increase}KiB exceeds adaptive ${maximumRiseKib(totalRam)}KiB"
        }
        if (after == null) errors += "Like-for-like post-close owned-process PSS is missing"
        else if (before != null && after.totalKib - before.totalKib > 64L * 1_024L) {
            errors += "Post-close owned-process residual ${after.totalKib - before.totalKib}KiB exceeds 65536KiB"
        }
        return errors
    }
}

internal data class OwnedPssSample(
    val stage: String,
    val requestedAtNanos: Long,
    val startedAtNanos: Long?,
    val finishedAtNanos: Long?,
    val processes: Map<Int, Long>,
    val actualStage: String = stage,
    val outcome: String = "measured",
    val reason: String? = null,
    val elapsedMillis: Long? = null,
    val processChurn: List<OwnedProcessChurn> = emptyList(),
) {
    // Retain the small value-oriented constructor used by the existing policy tests.
    constructor(stage: String, elapsedMillis: Long, processes: Map<Int, Long>) : this(
        stage = stage,
        requestedAtNanos = elapsedMillis * 1_000_000L,
        startedAtNanos = elapsedMillis * 1_000_000L,
        finishedAtNanos = elapsedMillis * 1_000_000L,
        processes = processes,
        actualStage = stage,
        outcome = "measured",
        elapsedMillis = elapsedMillis,
    )

    val totalKib: Long get() = processes.values.sum()

    fun isMeasuredAt(expectedStage: String): Boolean =
        stage == expectedStage && actualStage == expectedStage && outcome == "measured" &&
            startedAtNanos != null && finishedAtNanos != null
}

/** A ProcessRecord that changed or ended while its PSS was being read. */
internal data class OwnedProcessChurn(
    val pid: Int,
    val beforeIdentity: String,
    val afterIdentity: String?,
    val reason: String,
)

internal enum class OwnedPssMissingDisposition {
    PROCESS_EXITED,
    PID_REUSED,
    LIVE_PSS_MISSING,
    UNVERIFIED_PSS_MISSING,
}

internal fun interface OwnedPssMeasurementSource {
    /** Runs one complete ownership/PSS measurement; callers own the thread it executes on. */
    fun measure(sampleOrdinal: Int): Map<Int, Long>

    /** Returns and clears process lifecycle observations from the most recent measurement. */
    fun drainProcessChurn(): List<OwnedProcessChurn> = emptyList()

    /** Best-effort cancellation for a read currently owned by this source. */
    fun cancelInFlight() {}
}

private class ShellOwnedPssMeasurementSource(
    private val instrumentation: Instrumentation,
    private val directory: File,
) : OwnedPssMeasurementSource {
    private val context = instrumentation.targetContext
    private val cancelRequested = AtomicBoolean(false)
    private val activeDescriptor = AtomicReference<ParcelFileDescriptor?>()
    private val processChurn = AtomicReference<List<OwnedProcessChurn>>(emptyList())

    override fun measure(sampleOrdinal: Int): Map<Int, Long> {
        cancelRequested.set(false)
        processChurn.set(emptyList())
        val ownership = shell("dumpsys activity processes")
        val owned = OwnedProcessParser.ownedProcessIdentities(ownership, context.packageName)
        check(android.os.Process.myPid() in owned.keys) {
            "Process ownership enumeration omitted application PID"
        }
        directory.resolve("memory-ownership-$sampleOrdinal.txt").writeText(ownership)
        val churn = mutableListOf<OwnedProcessChurn>()
        val measured = owned.mapNotNull { (pid, identity) ->
            val raw = shell("dumpsys meminfo $pid")
            directory.resolve("memory-$sampleOrdinal-$pid.txt").writeText(raw)
            val pss = OwnedProcessParser.totalPss(raw)
            if (pss != null) return@mapNotNull pid to pss

            // ActivityManager can retain a ProcessRecord after a short-lived isolated WebView
            // process exits. Capture the post-read identity before deciding whether the missing
            // PSS is a dead PID, PID reuse, or a still-live owned process with bad output.
            val afterOwnership = shell("dumpsys activity processes")
            directory.resolve("memory-ownership-$sampleOrdinal-after-$pid.txt")
                .writeText(afterOwnership)
            val afterIdentity = OwnedProcessParser.ownedProcessIdentities(
                afterOwnership, context.packageName,
            )[pid]
            when (OwnedProcessParser.classifyMissingPss(pid, identity, raw, afterIdentity)) {
                OwnedPssMissingDisposition.PID_REUSED -> {
                    churn += OwnedProcessChurn(pid, identity, afterIdentity, "pid-reused")
                    processChurn.set(churn.toList())
                    throw IllegalArgumentException(
                        "Owned PID $pid was reused between memory reads " +
                            "($identity -> $afterIdentity)",
                    )
                }
                OwnedPssMissingDisposition.PROCESS_EXITED -> {
                    // Do not insert zero: this process was gone by the PSS read. The raw meminfo
                    // and before/after ownership files preserve the reason and identity evidence.
                    churn += OwnedProcessChurn(pid, identity, afterIdentity, "process-exited-before-pss")
                    null
                }
                OwnedPssMissingDisposition.UNVERIFIED_PSS_MISSING -> {
                    processChurn.set(churn.toList())
                    throw IllegalArgumentException(
                        "PSS unavailable for owned PID $pid; post-read ownership disappeared " +
                            "without an exact No process found record",
                    )
                }
                OwnedPssMissingDisposition.LIVE_PSS_MISSING -> {
                    processChurn.set(churn.toList())
                    throw IllegalArgumentException(
                        "PSS unavailable for owned PID $pid: live owned process returned no PSS",
                    )
                }
            }
        }.toMap()
        processChurn.set(churn.toList())
        return measured
    }

    override fun drainProcessChurn(): List<OwnedProcessChurn> =
        processChurn.getAndSet(emptyList())

    override fun cancelInFlight() {
        cancelRequested.set(true)
        activeDescriptor.getAndSet(null)?.close()
    }

    private fun shell(command: String): String {
        val timedCommand = command.replaceFirst("dumpsys ", "dumpsys -t $SHELL_TIMEOUT_SECONDS ")
        val descriptor = instrumentation.uiAutomation.executeShellCommand(timedCommand)
        activeDescriptor.set(descriptor)
        if (cancelRequested.get() && activeDescriptor.compareAndSet(descriptor, null)) {
            descriptor.close()
            throw IllegalStateException("Memory shell read was cancelled before it started")
        }
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.bufferedReader().readText() }
        } finally {
            activeDescriptor.compareAndSet(descriptor, null)
        }
    }
}

internal class QualificationMemory private constructor(
    private val directory: File,
    private val measurementSource: OwnedPssMeasurementSource,
    private val totalRam: Long?,
    private val clockNanos: () -> Long,
    private val sampler: ExecutorService,
    private val activeDrainTimeoutMillis: Long,
    private val samplerTerminationTimeoutMillis: Long,
) {
    constructor(instrumentation: Instrumentation, directory: File) : this(
        directory = directory,
        measurementSource = ShellOwnedPssMeasurementSource(instrumentation, directory),
        totalRam = readTotalRam(instrumentation),
        clockNanos = { System.nanoTime() },
        sampler = newSampler(),
        activeDrainTimeoutMillis = LIVE_ACTIVE_DRAIN_TIMEOUT_MILLIS,
        samplerTerminationTimeoutMillis = LIVE_SAMPLER_TERMINATION_TIMEOUT_MILLIS,
    )

    internal constructor(
        directory: File,
        measurementSource: OwnedPssMeasurementSource,
        totalRamBytes: Long? = null,
        clockNanos: () -> Long = { System.nanoTime() },
        activeDrainTimeoutMillis: Long = LIVE_ACTIVE_DRAIN_TIMEOUT_MILLIS,
        samplerTerminationTimeoutMillis: Long = LIVE_SAMPLER_TERMINATION_TIMEOUT_MILLIS,
    ) : this(
        directory,
        measurementSource,
        totalRamBytes,
        clockNanos,
        newSampler(),
        activeDrainTimeoutMillis,
        samplerTerminationTimeoutMillis,
    )

    init {
        require(activeDrainTimeoutMillis > 0L) { "activeDrainTimeoutMillis must be positive" }
        require(samplerTerminationTimeoutMillis > 0L) {
            "samplerTerminationTimeoutMillis must be positive"
        }
    }

    private data class ActiveRequest(
        val requestedAtNanos: Long,
        val ordinal: Int,
        var startedAtNanos: Long? = null,
        var timeoutRecorded: Boolean = false,
    )

    private val qualificationStartedAtNanos = clockNanos()
    private val stateLock = Any()
    private val samples = mutableListOf<OwnedPssSample>()
    private var nextSampleOrdinal = 0
    private var activeInFlight = false
    private var activeFuture: Future<*>? = null
    private var activeRequest: ActiveRequest? = null
    private var boundaryInProgress = false
    private var finishing = false
    private var finished = false
    private var closeBoundaryAtNanos: Long? = null
    private var asynchronousFailure: Throwable? = null

    /** Active requests never execute measurement work on the caller thread. */
    fun capture(stage: String) {
        require(stage in setOf("active", "before-catalog", "before-viewer", "after-viewer")) {
            "Unsupported memory capture stage: $stage"
        }
        if (stage == "after-viewer") beginViewerClose()
        val requestedAtNanos = clockNanos()
        if (stage == "active") {
            requestActive(requestedAtNanos)
        } else {
            captureBoundary(stage, requestedAtNanos)
        }
    }

    /**
     * Nonblocking boundary hook for the runner immediately before closing the viewer.
     * The earliest call wins so an in-flight active sample cannot be credited after close.
     */
    internal fun beginViewerClose() {
        val boundaryAtNanos = clockNanos()
        synchronized(stateLock) {
            if (closeBoundaryAtNanos == null || boundaryAtNanos < closeBoundaryAtNanos!!) {
                closeBoundaryAtNanos = boundaryAtNanos
            }
        }
    }

    /** Drains the sampler, attempts bounded termination, and throws if closure is unverified. */
    fun finish(): List<String> {
        val requestedAtNanos = clockNanos()
        val future = synchronized(stateLock) {
            check(!finishing && !finished) { "QualificationMemory.finish() called more than once" }
            finishing = true
            if (closeBoundaryAtNanos == null) closeBoundaryAtNanos = requestedAtNanos
            activeFuture
        }

        var terminalFailure: Throwable? = null
        var samplerTerminated = false
        try {
            if (!await(future, activeDrainTimeoutMillis, onTimeout = {
                    val failure = activeDrainTimeout()
                    recordActiveTimeout(failure)
                    cancelActiveMeasurement()
                })) {
                terminalFailure = IllegalStateException("Timed out while draining active memory sampler")
            }
        } catch (failure: Throwable) {
            terminalFailure = failure
            synchronized(stateLock) {
                if (asynchronousFailure == null) asynchronousFailure = failure
            }
        } finally {
            try {
                samplerTerminated = shutdownSampler()
                if (!samplerTerminated && terminalFailure == null) {
                    terminalFailure = samplerTerminationTimeout()
                }
            } catch (failure: Throwable) {
                terminalFailure = terminalFailure ?: failure
                synchronized(stateLock) {
                    if (asynchronousFailure == null) asynchronousFailure = failure
                }
            } finally {
                synchronized(stateLock) {
                    finished = true
                    finishing = false
                    if (samplerTerminated) {
                        activeInFlight = false
                        activeFuture = null
                        activeRequest = null
                    }
                }
            }
        }

        val finalSamples = samplesSnapshot()
        var serializationFailure: Throwable? = null
        try {
            writeJson(finalSamples, samplerTerminated)
        } catch (failure: Throwable) {
            serializationFailure = failure
        }

        val failure = synchronized(stateLock) {
            asynchronousFailure ?: terminalFailure ?: serializationFailure ?:
                if (!samplerTerminated) IllegalStateException("Memory sampler termination is unverified") else null
        }
        if (failure != null) {
            throw IllegalStateException("Qualification memory sampling failed during finish", failure)
        }
        return QualificationMemoryPolicy.violations(finalSamples, totalRam)
    }

    internal fun samplesSnapshotForTest(): List<OwnedPssSample> = samplesSnapshot()

    internal fun isSamplerTerminatedForTest(): Boolean = sampler.isTerminated

    internal fun isFinishingForTest(): Boolean = synchronized(stateLock) { finishing }

    internal fun awaitActiveForTest() = awaitActive()

    internal fun closeBoundaryAtNanosForTest(): Long? = synchronized(stateLock) { closeBoundaryAtNanos }

    private fun requestActive(requestedAtNanos: Long) {
        synchronized(stateLock) {
            val skipReason = when {
                finished -> "collector-finished"
                closeBoundaryAtNanos != null -> "viewer-close-boundary"
                finishing -> "collector-closing"
                boundaryInProgress -> "boundary-capture-in-flight"
                activeInFlight -> "measurement-in-flight"
                else -> null
            }
            if (skipReason != null) {
                samples += OwnedPssSample(
                    stage = "active",
                    requestedAtNanos = requestedAtNanos,
                    startedAtNanos = null,
                    finishedAtNanos = null,
                    processes = emptyMap(),
                    actualStage = "active-skipped",
                    outcome = "skipped",
                    reason = skipReason,
                )
                return
            }

            val ordinal = nextSampleOrdinal++
            val request = ActiveRequest(requestedAtNanos, ordinal)
            activeInFlight = true
            activeRequest = request
            try {
                activeFuture = sampler.submit { measureActive(request) }
            } catch (failure: Throwable) {
                activeInFlight = false
                activeRequest = null
                asynchronousFailure = asynchronousFailure ?: failure
                samples += failedSample(
                    stage = "active",
                    requestedAtNanos = requestedAtNanos,
                    startedAtNanos = null,
                    finishedAtNanos = clockNanos(),
                    actualStage = "active-failed",
                    failure = failure,
                )
            }
        }
    }

    private fun captureBoundary(stage: String, requestedAtNanos: Long) {
        synchronized(stateLock) {
            check(!finishing && !finished) { "Cannot capture $stage after finish has started" }
            check(!boundaryInProgress) { "Concurrent boundary capture is not supported" }
            boundaryInProgress = true
        }
        var startedAtNanos: Long? = null
        try {
            awaitActive()
            startedAtNanos = clockNanos()
            val boundaryFuture = sampler.submit(Callable {
                measurementSource.measure(nextOrdinal())
            })
            if (!await(boundaryFuture, activeDrainTimeoutMillis, onTimeout = {
                    cancelSourceRead()
                    boundaryFuture.cancel(true)
                    Unit
                })) {
                throw IllegalStateException(
                    "$stage memory measurement exceeded ${activeDrainTimeoutMillis}ms deadline",
                )
            }
            val processes = boundaryFuture.get(0L, TimeUnit.NANOSECONDS)
            val processChurn = measurementSource.drainProcessChurn()
            val finishedAtNanos = clockNanos()
            synchronized(stateLock) {
                samples += measuredSample(
                    stage = stage,
                    requestedAtNanos = requestedAtNanos,
                    startedAtNanos = startedAtNanos,
                    finishedAtNanos = finishedAtNanos,
                    processes = processes,
                    processChurn = processChurn,
                )
            }
        } catch (failure: Throwable) {
            val finishedAtNanos = clockNanos()
            synchronized(stateLock) {
                samples += failedSample(
                    stage = stage,
                    requestedAtNanos = requestedAtNanos,
                    startedAtNanos = startedAtNanos,
                    finishedAtNanos = finishedAtNanos,
                    actualStage = stage,
                    failure = failure,
                    processChurn = measurementSource.drainProcessChurn(),
                )
            }
            throw failure
        } finally {
            synchronized(stateLock) { boundaryInProgress = false }
        }
    }

    private fun measureActive(request: ActiveRequest) {
        val startedAtNanos = clockNanos()
        synchronized(stateLock) { request.startedAtNanos = startedAtNanos }
        try {
            val processes = measurementSource.measure(request.ordinal)
            val processChurn = measurementSource.drainProcessChurn()
            val finishedAtNanos = clockNanos()
            synchronized(stateLock) {
                samples += measuredSample(
                    stage = "active",
                    requestedAtNanos = request.requestedAtNanos,
                    startedAtNanos = startedAtNanos,
                    finishedAtNanos = finishedAtNanos,
                    processes = processes,
                    actualStage = actualActiveStage(request, finishedAtNanos),
                    processChurn = processChurn,
                )
                clearActiveRequest(request)
            }
        } catch (failure: Throwable) {
            val finishedAtNanos = clockNanos()
            synchronized(stateLock) {
                samples += failedSample(
                    stage = "active",
                    requestedAtNanos = request.requestedAtNanos,
                    startedAtNanos = startedAtNanos,
                    finishedAtNanos = finishedAtNanos,
                    actualStage = actualActiveStage(request, finishedAtNanos) + "-failed",
                    failure = failure,
                    processChurn = measurementSource.drainProcessChurn(),
                )
                asynchronousFailure = asynchronousFailure ?: failure
                clearActiveRequest(request)
            }
        }
    }

    private fun actualActiveStage(request: ActiveRequest, finishedAtNanos: Long): String =
        when {
            request.timeoutRecorded -> "active-timeout-completed"
            closeBoundaryAtNanos?.let { finishedAtNanos >= it } == true -> "active-overlaps-close"
            else -> "active"
        }

    private fun clearActiveRequest(request: ActiveRequest) {
        if (activeRequest === request) {
            activeRequest = null
            activeInFlight = false
            activeFuture = null
        }
    }

    private fun recordActiveTimeout(failure: Throwable) {
        synchronized(stateLock) {
            val request = activeRequest ?: return
            if (request.timeoutRecorded) return
            request.timeoutRecorded = true
            val finishedAtNanos = clockNanos()
            samples += failedSample(
                stage = "active",
                requestedAtNanos = request.requestedAtNanos,
                startedAtNanos = request.startedAtNanos,
                finishedAtNanos = finishedAtNanos,
                actualStage = "active-timeout",
                failure = failure,
            )
            asynchronousFailure = asynchronousFailure ?: failure
        }
    }

    private fun cancelActiveMeasurement() {
        cancelSourceRead()
        synchronized(stateLock) { activeFuture }?.cancel(true)
    }

    private fun cancelSourceRead() {
        runCatching { measurementSource.cancelInFlight() }
            .onFailure { failure ->
                synchronized(stateLock) {
                    asynchronousFailure = asynchronousFailure ?: failure
                }
            }
    }

    private fun activeDrainTimeout(): IllegalStateException =
        IllegalStateException("Active memory measurement exceeded ${activeDrainTimeoutMillis}ms deadline")

    private fun samplerTerminationTimeout(): IllegalStateException =
        IllegalStateException("Memory sampler did not terminate within ${samplerTerminationTimeoutMillis}ms")

    private fun measuredSample(
        stage: String,
        requestedAtNanos: Long,
        startedAtNanos: Long,
        finishedAtNanos: Long,
        processes: Map<Int, Long>,
        actualStage: String = stage,
        processChurn: List<OwnedProcessChurn> = emptyList(),
    ): OwnedPssSample = OwnedPssSample(
        stage = stage,
        requestedAtNanos = requestedAtNanos,
        startedAtNanos = startedAtNanos,
        finishedAtNanos = finishedAtNanos,
        processes = processes,
        actualStage = actualStage,
        elapsedMillis = (finishedAtNanos - qualificationStartedAtNanos).coerceAtLeast(0L) / 1_000_000L,
        processChurn = processChurn,
    )

    private fun failedSample(
        stage: String,
        requestedAtNanos: Long,
        startedAtNanos: Long?,
        finishedAtNanos: Long?,
        actualStage: String,
        failure: Throwable,
        processChurn: List<OwnedProcessChurn> = emptyList(),
    ): OwnedPssSample = OwnedPssSample(
        stage = stage,
        requestedAtNanos = requestedAtNanos,
        startedAtNanos = startedAtNanos,
        finishedAtNanos = finishedAtNanos,
        processes = emptyMap(),
        actualStage = actualStage,
        outcome = "failed",
        reason = failureDescription(failure),
        elapsedMillis = finishedAtNanos?.let { end ->
            (end - qualificationStartedAtNanos).coerceAtLeast(0L) / 1_000_000L
        },
        processChurn = processChurn,
    )

    private fun nextOrdinal(): Int = synchronized(stateLock) { nextSampleOrdinal++ }

    private fun awaitActive() {
        val future = synchronized(stateLock) { activeFuture }
        if (!await(future, activeDrainTimeoutMillis, onTimeout = {
                val failure = activeDrainTimeout()
                recordActiveTimeout(failure)
                cancelActiveMeasurement()
            })) throw activeDrainTimeout()
    }

    private fun await(
        future: Future<*>?,
        timeoutMillis: Long,
        onTimeout: () -> Unit,
    ): Boolean {
        if (future == null) return true
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            return true
        } catch (_: TimeoutException) {
            onTimeout()
            return false
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while draining memory sampler", interrupted)
        } catch (cancelled: CancellationException) {
            throw IllegalStateException("Memory sampler measurement was cancelled", cancelled)
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        }
    }

    private fun shutdownSampler(): Boolean {
        sampler.shutdown()
        if (awaitSamplerTermination(samplerTerminationTimeoutMillis)) return true
        cancelActiveMeasurement()
        sampler.shutdownNow()
        return awaitSamplerTermination(samplerTerminationTimeoutMillis)
    }

    private fun awaitSamplerTermination(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var interrupted = false
        while (!sampler.isTerminated) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) break
            try {
                if (sampler.awaitTermination(remaining, TimeUnit.NANOSECONDS)) break
            } catch (_: InterruptedException) {
                interrupted = true
                sampler.shutdownNow()
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return sampler.isTerminated
    }

    private fun samplesSnapshot(): List<OwnedPssSample> = synchronized(stateLock) { samples.toList() }

    private fun writeJson(finalSamples: List<OwnedPssSample>, samplerTerminated: Boolean) {
        val jsonSamples = JSONArray()
        val closeBoundaryAt = synchronized(stateLock) { closeBoundaryAtNanos }
        finalSamples.forEach { sample ->
            val churn = JSONArray(sample.processChurn.map { event ->
                JSONObject()
                    .put("pid", event.pid)
                    .put("beforeIdentity", event.beforeIdentity)
                    .put("afterIdentity", event.afterIdentity ?: JSONObject.NULL)
                    .put("reason", event.reason)
            })
            jsonSamples.put(JSONObject()
                .put("stage", sample.stage)
                .put("actualStage", sample.actualStage)
                .put("outcome", sample.outcome)
                .put("reason", sample.reason ?: JSONObject.NULL)
                .put("requestedAtNanos", sample.requestedAtNanos)
                .put("startedAtNanos", sample.startedAtNanos ?: JSONObject.NULL)
                .put("finishedAtNanos", sample.finishedAtNanos ?: JSONObject.NULL)
                .put("elapsedMillis", sample.elapsedMillis ?: JSONObject.NULL)
                .put("totalPssKib", sample.totalKib)
                .put("processes", JSONObject(sample.processes.mapKeys { it.key.toString() }))
                .put("processChurn", churn))
        }
        directory.resolve("memory.json").writeText(JSONObject()
            .put("totalRamBytes", totalRam ?: JSONObject.NULL)
            .put("maximumRiseKib", QualificationMemoryPolicy.maximumRiseKib(totalRam))
            .put("postCloseResidualMaximumKib", 64 * 1_024)
            .put("postCloseResidualComparison", "Existing boundary-only 64MiB check; not same-cache proof")
            .put("closeBoundaryAtNanos", closeBoundaryAt ?: JSONObject.NULL)
            .put("activeDrainTimeoutMillis", activeDrainTimeoutMillis)
            .put("samplerTerminationTimeoutMillis", samplerTerminationTimeoutMillis)
            .put("ownership", "ActivityManager ProcessRecord packageList and hosting identity; " +
                "missing PSS is classified with post-read identity; no PID-name-only filter")
            .put("processChurnEvidence", "Best-effort before/after ownership snapshots around each PSS read; " +
                "snapshots can be time-skewed and incomplete")
            .put("allOwnedPeakConfirmed", false)
            .put("activeSampling", "single worker; one in-flight request; skipped active requests are recorded")
            .put("samplerTerminated", samplerTerminated)
            .put("samples", jsonSamples)
            .toString(2))
    }

    private companion object {
        fun newSampler(): ExecutorService {
            val sequence = AtomicInteger()
            val threadFactory = java.util.concurrent.ThreadFactory { runnable ->
                Thread(runnable, "qualification-memory-sampler-${sequence.incrementAndGet()}").apply {
                    isDaemon = false
                }
            }
            return ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue<Runnable>(1),
                threadFactory,
                ThreadPoolExecutor.AbortPolicy(),
            )
        }

        fun readTotalRam(instrumentation: Instrumentation): Long? = runCatching {
            ActivityManager.MemoryInfo().also {
                instrumentation.targetContext
                    .getSystemService(ActivityManager::class.java)
                    .getMemoryInfo(it)
            }.totalMem
        }.getOrNull()

        fun failureDescription(failure: Throwable): String =
            failure::class.java.name + ": " + (failure.message?.takeIf(String::isNotBlank) ?: "no message")
    }
}

internal object OwnedProcessParser {
    private val record = Regex("(?m)^\\s*\\*APP\\*[^\\n]*ProcessRecord\\{[^ ]+\\s+(\\d+):([^/ }]+)[^\\n]*")
    private val uid = Regex("\\bUID\\s+(\\S+)")
    private val token = Regex("ProcessRecord\\{([^ ]+)\\s+")
    private val startSequence = Regex("\\bstartSeq=(\\d+)")
    private val noProcess = Regex("^No process found for:\\s*(\\d+)\\s*$")

    fun ownedPids(raw: String, packageName: String): Set<Int> =
        ownedProcessIdentities(raw, packageName).keys

    /** Stable ProcessRecord identity fields used to distinguish PID death from PID reuse. */
    fun ownedProcessIdentities(raw: String, packageName: String): Map<Int, String> {
        val records = record.findAll(raw).toList()
        return records.mapIndexedNotNull { index, match ->
            val block = raw.substring(match.range.first, records.getOrNull(index + 1)?.range?.first ?: raw.length)
            val packages = Regex("packageList=\\{([^}]*)\\}").find(block)?.groupValues?.get(1)
                ?.split(',')?.map(String::trim).orEmpty()
            val hosting = block.lineSequence().filter { it.contains("HostingRecord") }.joinToString()
            val process = match.groupValues[2]
            match.groupValues[1].toInt().takeIf {
                packageName in packages || process == packageName || process.startsWith("$packageName:") ||
                    hosting.contains("$packageName/")
            }?.let { pid -> pid to identity(match, block) }
        }.toMap()
    }

    fun totalPss(raw: String): Long? = Regex("TOTAL PSS:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()
        ?: Regex("(?m)^\\s*TOTAL\\s+(\\d+)\\s").find(raw)?.groupValues?.get(1)?.toLongOrNull()

    fun isProcessGone(raw: String, expectedPid: Int? = null): Boolean {
        val match = noProcess.matchEntire(raw.trim()) ?: return false
        return expectedPid == null || match.groupValues[1].toIntOrNull() == expectedPid
    }

    fun classifyMissingPss(
        pid: Int,
        beforeIdentity: String,
        raw: String,
        afterIdentity: String?,
    ): OwnedPssMissingDisposition = when {
        afterIdentity != null && afterIdentity != beforeIdentity -> OwnedPssMissingDisposition.PID_REUSED
        isProcessGone(raw, pid) -> OwnedPssMissingDisposition.PROCESS_EXITED
        afterIdentity != null -> OwnedPssMissingDisposition.LIVE_PSS_MISSING
        else -> OwnedPssMissingDisposition.UNVERIFIED_PSS_MISSING
    }

    private fun identity(match: MatchResult, block: String): String {
        val processRecordToken = token.find(match.value)?.groupValues?.get(1).orEmpty()
        val processUid = uid.find(match.value)?.groupValues?.get(1).orEmpty()
        val processStartSequence = startSequence.find(block)?.groupValues?.get(1).orEmpty()
        return "$processUid|$processRecordToken|$processStartSequence|${match.groupValues[2]}"
    }
}
