package ml.melun.mangaview.viewer.runtime

import kotlinx.coroutines.Job
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.EpisodeOperationToken
import ml.melun.mangaview.viewer.OperationToken
import ml.melun.mangaview.viewer.WorkKind

/** Serializes ownership changes with shutdown without putting I/O under a monitor. */
internal class ViewerWorkerOwnership {
    private data class PageSlot(val pageId: PageId, val kind: WorkKind)
    private data class OwnedPage(val token: OperationToken, val job: Job)
    private data class OwnedEpisode(val token: EpisodeOperationToken, val job: Job)

    private val lock = Any()
    private val pageJobs = mutableMapOf<PageSlot, OwnedPage>()
    private val episodeJobs = mutableMapOf<EpisodeId, OwnedEpisode>()
    private val trackedJobs = mutableSetOf<Job>()
    private var maintenanceJob: Job? = null
    private var decodeEnabled = true
    private var stopping = false

    fun registerPage(token: OperationToken, create: (Job?) -> Job): Job? = synchronized(lock) {
        if (stopping || token.kind == WorkKind.DECODE && !decodeEnabled) return@synchronized null
        val slot = PageSlot(token.pageId, token.kind)
        val incumbent = pageJobs[slot]
        if (incumbent?.token == token) return@synchronized null
        val job = create(incumbent?.job)
        pageJobs[slot] = OwnedPage(token, job)
        track(job) { pageJobs.remove(slot, OwnedPage(token, job)) }
        incumbent?.job?.cancel()
        job
    }

    fun registerEpisode(token: EpisodeOperationToken, create: (Job?) -> Job): Job? =
        synchronized(lock) {
            if (stopping) return@synchronized null
            val incumbent = episodeJobs[token.fromEpisodeId]
            if (incumbent?.token == token) return@synchronized null
            val job = create(incumbent?.job)
            episodeJobs[token.fromEpisodeId] = OwnedEpisode(token, job)
            track(job) {
                episodeJobs.remove(token.fromEpisodeId, OwnedEpisode(token, job))
            }
            incumbent?.job?.cancel()
            job
        }

    fun registerMaintenance(create: () -> Job): Job? = synchronized(lock) {
        if (stopping || maintenanceJob?.isCompleted == false) return@synchronized null
        val job = create()
        maintenanceJob = job
        track(job) {
            if (maintenanceJob === job) maintenanceJob = null
        }
        job
    }

    fun pauseDecodes() {
        val jobs = synchronized(lock) {
            decodeEnabled = false
            pageJobs.filterKeys { it.kind == WorkKind.DECODE }.values.map(OwnedPage::job)
        }
        jobs.forEach(Job::cancel)
    }

    fun resumeDecodes() {
        synchronized(lock) {
            if (!stopping) decodeEnabled = true
        }
    }

    fun cancel(token: OperationToken) {
        val job = synchronized(lock) {
            pageJobs[PageSlot(token.pageId, token.kind)]?.takeIf { it.token == token }?.job
        }
        job?.cancel()
    }

    fun cancelGeneration(generation: Long) {
        val jobs = synchronized(lock) {
            pageJobs.values.filter { it.token.generation == generation }.map(OwnedPage::job) +
                episodeJobs.values.filter { it.token.generation == generation }.map(OwnedEpisode::job)
        }
        jobs.forEach(Job::cancel)
    }

    fun beginShutdown(): List<Job> = synchronized(lock) {
        if (stopping) return@synchronized emptyList()
        stopping = true
        decodeEnabled = false
        trackedJobs.toList().also { jobs -> jobs.forEach(Job::cancel) }
    }

    fun finishShutdown() {
        synchronized(lock) {
            pageJobs.clear()
            episodeJobs.clear()
            trackedJobs.clear()
            maintenanceJob = null
        }
    }

    fun snapshot(): ViewerWorkerSnapshot = synchronized(lock) {
        ViewerWorkerSnapshot(
            activePageSlots = pageJobs.size,
            activeDecodeSlots = pageJobs.keys.count { it.kind == WorkKind.DECODE },
            activeEpisodeSlots = episodeJobs.size,
            trackedJobs = trackedJobs.size,
            decodeEnabled = decodeEnabled,
            stopping = stopping,
        )
    }

    private fun track(job: Job, removeOwner: () -> Unit) {
        trackedJobs += job
        job.invokeOnCompletion {
            synchronized(lock) {
                removeOwner()
                trackedJobs.remove(job)
            }
        }
    }
}

internal data class ViewerWorkerSnapshot(
    val activePageSlots: Int,
    val activeDecodeSlots: Int,
    val activeEpisodeSlots: Int,
    val trackedJobs: Int,
    val decodeEnabled: Boolean,
    val stopping: Boolean,
    val verifiedHandoffs: Int = 0,
)
