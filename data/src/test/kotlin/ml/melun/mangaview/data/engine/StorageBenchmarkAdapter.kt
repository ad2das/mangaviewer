package ml.melun.mangaview.data.engine

import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkLimits

/**
 * Swap point for [StorageAdmissionBenchmarkTest]'s before/after runs. Default (false) is the split
 * under review: lookups on STORAGE_READ, publishes on STORAGE_PUBLISH, bounded lanes. With
 * STORAGE_BENCH_LEGACY=1 in the environment the benchmark maps both operations back onto the old
 * single STORAGE lane (one permit), which reproduces the pre-change serialization without touching
 * production code: set the variable, run the benchmark, unset it, and paste both outputs in the
 * change report.
 */
internal object StorageBenchmarkAdapter {
    private val legacySingleLane = System.getenv("STORAGE_BENCH_LEGACY") == "1"

    /** True when this JVM reproduces the legacy single-lane mapping. */
    fun isLegacy(): Boolean = legacySingleLane

    fun lookupDomain(): WorkDomain =
        if (legacySingleLane) WorkDomain.STORAGE else WorkDomain.STORAGE_READ

    fun publishDomain(): WorkDomain =
        if (legacySingleLane) WorkDomain.STORAGE else WorkDomain.STORAGE_PUBLISH

    fun limits(storageRead: Int): WorkLimits =
        if (legacySingleLane) WorkLimits(storage = 1)
        else WorkLimits(storageRead = storageRead, storagePublish = 4)

    /** Concurrency the lookup lane can actually reach, for the benchmark's in-flight accounting. */
    fun lookupWidth(storageRead: Int): Int = if (legacySingleLane) 1 else storageRead
}
