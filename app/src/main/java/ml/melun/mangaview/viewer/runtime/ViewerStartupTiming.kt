package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.PageId

internal data class ViewerStartupTiming(
    val presentedPageKey: String?,
    val openStartedAtNanos: Long,
    val manifestReadyAtNanos: Long?,
    val initialResponseStartedAtNanos: Long?,
    val initialVerifiedAtNanos: Long?,
    val initialDecodedAtNanos: Long?,
    val firstActualSubmittedAtNanos: Long?,
    val firstActualPresentedAtNanos: Long?,
) {
    init {
        require(openStartedAtNanos > 0L)
        requireAfter(openStartedAtNanos, manifestReadyAtNanos)
        requireAfter(manifestReadyAtNanos, initialResponseStartedAtNanos)
        requireAfter(initialResponseStartedAtNanos, initialVerifiedAtNanos)
        requireAfter(initialResponseStartedAtNanos, initialDecodedAtNanos)
        requireAfter(initialDecodedAtNanos, firstActualSubmittedAtNanos)
        requireAfter(firstActualSubmittedAtNanos, firstActualPresentedAtNanos)
    }

    private fun requireAfter(earlier: Long?, later: Long?) {
        if (earlier != null && later != null) {
            require(later >= earlier) {
                "Viewer startup stages are out of order: earlier=$earlier later=$later"
            }
        }
    }
}

internal class ViewerStartupTracker {
    private data class MutablePageStages(
        var responseStarted: Long = 0L,
        var verified: Long = 0L,
        var decoded: Long = 0L,
    )

    private data class Presentation(
        val pageId: PageId,
        val submittedAtNanos: Long,
        val presentedAtNanos: Long,
    )

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

    fun markPresented(
        pageId: PageId,
        submittedAtNanos: Long,
        presentedAtNanos: Long,
        timestampKind: PresentationTimestampKind,
    ) = synchronized(lock) {
        if (timestampKind != PresentationTimestampKind.DISPLAY_PRESENT) return@synchronized
        require(submittedAtNanos > 0L && presentedAtNanos >= submittedAtNanos)
        if (presentation == null) {
            presentation = Presentation(pageId, submittedAtNanos, presentedAtNanos)
        }
    }

    fun needsPresentation(): Boolean = synchronized(lock) { presentation == null }

    fun wasDecodedBy(pageId: PageId, atNanos: Long): Boolean = synchronized(lock) {
        val decoded = pages[pageId]?.decoded ?: return@synchronized false
        decoded > 0L && decoded <= atNanos
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
            firstActualSubmittedAtNanos = shown?.submittedAtNanos,
            firstActualPresentedAtNanos = shown?.presentedAtNanos,
        )
    }

    private fun mark(current: Long, atNanos: Long): Long {
        require(atNanos > 0L)
        return if (current == 0L) atNanos else current
    }

    private fun Long?.optional(): Long? = this?.takeIf { it > 0L }
}
