package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.PageId

internal data class ViewerStartupTiming(
    val presentedPageKey: String?,
    val openStartedAtNanos: Long,
    val manifestReadyAtNanos: Long?,
    val initialResponseStartedAtNanos: Long?,
    val initialVerifiedAtNanos: Long?,
    val initialDecodedAtNanos: Long?,
    val firstActualPresentedAtNanos: Long?,
) {
    init {
        require(openStartedAtNanos > 0L)
        val stages = listOfNotNull(
            manifestReadyAtNanos,
            initialResponseStartedAtNanos,
            initialVerifiedAtNanos,
            initialDecodedAtNanos,
            firstActualPresentedAtNanos,
        )
        require(stages.zipWithNext().all { (left, right) -> right >= left }) {
            "Viewer startup stages are out of order"
        }
    }
}

internal class ViewerStartupTracker {
    private data class MutablePageStages(
        var responseStarted: Long = 0L,
        var verified: Long = 0L,
        var decoded: Long = 0L,
    )

    private data class Presentation(val pageId: PageId, val atNanos: Long)

    private val lock = Any()
    private val pages = mutableMapOf<PageId, MutablePageStages>()
    private var openStarted = 0L
    private var manifestReady = 0L
    private var presentation: Presentation? = null

    fun markOpenStarted(atNanos: Long) = synchronized(lock) {
        openStarted = mark(openStarted, atNanos)
    }

    fun markManifestReady(atNanos: Long) = synchronized(lock) {
        manifestReady = mark(manifestReady, atNanos)
    }

    fun markResponseStarted(pageId: PageId, atNanos: Long) = synchronized(lock) {
        val stages = pages.getOrPut(pageId, ::MutablePageStages)
        stages.responseStarted = mark(stages.responseStarted, atNanos)
    }

    fun markVerified(pageId: PageId, atNanos: Long) = synchronized(lock) {
        val stages = pages.getOrPut(pageId, ::MutablePageStages)
        stages.verified = mark(stages.verified, atNanos)
    }

    fun markDecoded(pageId: PageId, atNanos: Long) = synchronized(lock) {
        val stages = pages.getOrPut(pageId, ::MutablePageStages)
        stages.decoded = mark(stages.decoded, atNanos)
    }

    fun markPresented(pageId: PageId, atNanos: Long) = synchronized(lock) {
        require(atNanos > 0L)
        if (presentation == null) presentation = Presentation(pageId, atNanos)
    }

    fun snapshot(): ViewerStartupTiming? = synchronized(lock) {
        val started = openStarted.takeIf { it > 0L } ?: return@synchronized null
        val shown = presentation
        val stages = shown?.let { pages[it.pageId] }
        return ViewerStartupTiming(
            presentedPageKey = shown?.pageId?.remoteKey,
            openStartedAtNanos = started,
            manifestReadyAtNanos = manifestReady.optional(),
            initialResponseStartedAtNanos = stages?.responseStarted.optional(),
            initialVerifiedAtNanos = stages?.verified.optional(),
            initialDecodedAtNanos = stages?.decoded.optional(),
            firstActualPresentedAtNanos = shown?.atNanos,
        )
    }

    private fun mark(current: Long, atNanos: Long): Long {
        require(atNanos > 0L)
        return if (current == 0L) atNanos else current
    }

    private fun Long?.optional(): Long? = this?.takeIf { it > 0L }
}
