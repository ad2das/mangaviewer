package ml.melun.mangaview.reader

import android.util.Log
import ml.melun.mangaview.glide.ViewerWarmupManager
import ml.melun.mangaview.mangaview.Manga
import java.util.concurrent.atomic.AtomicLong

enum class NtkBootPhase {
    OPENING,
    ANCHOR_EXCLUSIVE,
    ANCHOR_BITMAP_DECODED,
    FIRST_DRAWABLE_COMMITTED,
    INTERACTIVE_NEAR_WARMUP,
    STEADY_STATE
}

enum class NtkImageLane {
    FIRST_IMAGE,
    FOLLOWING_VISIBLE,
    NEAR_WARMUP,
    FAR_PREFETCH
}

data class NtkImagePermit(
    val sessionEpoch: Long,
    val pageIndex: Int,
    val lane: NtkImageLane,
    val phaseAtGrant: NtkBootPhase,
    val permitId: String
)

internal class NtkEpisodeCoordinator(
    private val path: String,
    private val isNtkEpisode: Boolean,
    private val anchorPageIndex: Int
) {
    private val epoch = NEXT_EPOCH.incrementAndGet()
    private val createdAtMs = android.os.SystemClock.elapsedRealtime()
    @Volatile
    private var phase: NtkBootPhase = NtkBootPhase.OPENING

    init {
        if (isNtkEpisode) enterPhase(NtkBootPhase.ANCHOR_EXCLUSIVE, "init")
    }

    fun enterPhase(next: NtkBootPhase, reason: String) {
        if (!isNtkEpisode) return
        if (phase == next) return
        phase = next
        Log.d(TAG, "ntk_phase_enter phase=$next,reason=$reason,anchor=$anchorPageIndex,epoch=$epoch,path=$path")
        ViewerWarmupManager.logMetric("ntk_phase_${next.name.lowercase()}", 1L)
    }

    fun markAnchorBitmapDecoded(pageIndex: Int) {
        if (!isNtkEpisode) return
        if (phase.ordinal < NtkBootPhase.ANCHOR_BITMAP_DECODED.ordinal) {
            enterPhase(NtkBootPhase.ANCHOR_BITMAP_DECODED, "anchor_bitmap_decoded:$pageIndex")
        }
    }

    fun markFirstDrawableCommitted(pageIndex: Int) {
        if (!isNtkEpisode) return
        if (phase.ordinal < NtkBootPhase.FIRST_DRAWABLE_COMMITTED.ordinal) {
            enterPhase(NtkBootPhase.FIRST_DRAWABLE_COMMITTED, "first_drawable_committed:$pageIndex")
        }
    }

    fun imagePermit(pageIndex: Int, manga: Manga, image: String?, requestedLane: NtkImageLane, source: String): NtkImagePermit? {
        if (!isNtkEpisode || !manga.isOnline) return null
        val currentPhase = relaxedPhaseFor(pageIndex, image, source)
        if (currentPhase == NtkBootPhase.ANCHOR_EXCLUSIVE && pageIndex != anchorPageIndex) {
            Log.d(
                TAG,
                "ntk_image_work_deferred page=$pageIndex,anchor=$anchorPageIndex,lane=$requestedLane," +
                    "reason=ANCHOR_EXCLUSIVE,source=$source,image=${image?.substringAfterLast('/')},path=$path"
            )
            ViewerWarmupManager.logMetric("ntk_image_work_deferred_anchor_exclusive", pageIndex.toLong())
            return null
        }
        val lane = if (pageIndex == anchorPageIndex && currentPhase.ordinal <= NtkBootPhase.ANCHOR_BITMAP_DECODED.ordinal) {
            NtkImageLane.FIRST_IMAGE
        } else {
            requestedLane
        }
        val permit = NtkImagePermit(epoch, pageIndex, lane, currentPhase, "$epoch:$pageIndex:${lane.name}")
        Log.d(
            TAG,
            "ntk_image_permit_granted page=$pageIndex,anchor=$anchorPageIndex,lane=$lane," +
                "phase=$currentPhase,source=$source,permit=${permit.permitId},image=${image?.substringAfterLast('/')},path=$path"
        )
        ViewerWarmupManager.logMetric("ntk_image_permit_${lane.name.lowercase()}", pageIndex.toLong())
        return permit
    }

    fun assertForegroundStreamPermit(pageIndex: Int, permit: NtkImagePermit?, image: String?, source: String): Boolean {
        if (!isNtkEpisode) return true
        val currentPhase = relaxedPhaseFor(pageIndex, image, source)
        if (currentPhase == NtkBootPhase.ANCHOR_EXCLUSIVE && pageIndex != anchorPageIndex) {
            Log.d(
                TAG,
                "ntk_priority_violation_nonanchor_stream_before_first_bitmap page=$pageIndex," +
                    "anchor=$anchorPageIndex,source=$source,hasPermit=${permit != null}," +
                    "image=${image?.substringAfterLast('/')},path=$path"
            )
            ViewerWarmupManager.logMetric("ntk_priority_violation_nonanchor_stream_before_first_bitmap", pageIndex.toLong())
            return false
        }
        return true
    }

    fun allowsPreAnchorFallback(pageIndex: Int, image: String?, source: String): Boolean {
        if (!isNtkEpisode || pageIndex == anchorPageIndex) return true
        return relaxedPhaseFor(pageIndex, image, source) != NtkBootPhase.ANCHOR_EXCLUSIVE
    }

    fun preAnchorFallbackRetryDelayMs(pageIndex: Int, image: String?): Long {
        if (!isNtkEpisode || pageIndex == anchorPageIndex) return 0L
        val initialNearPage = isInitialNearPage(pageIndex, image)
        if (!initialNearPage) return ANCHOR_EXCLUSIVE_FALLBACK_MS
        val ageMs = android.os.SystemClock.elapsedRealtime() - createdAtMs
        return (ANCHOR_EXCLUSIVE_FALLBACK_MS - ageMs).coerceAtLeast(0L)
    }

    private fun relaxedPhaseFor(pageIndex: Int, image: String?, source: String): NtkBootPhase {
        val currentPhase = phase
        if (currentPhase != NtkBootPhase.ANCHOR_EXCLUSIVE || pageIndex == anchorPageIndex) return currentPhase
        val initialNearPage = isInitialNearPage(pageIndex, image)
        if (!initialNearPage) return currentPhase
        val ageMs = android.os.SystemClock.elapsedRealtime() - createdAtMs
        if (ageMs < ANCHOR_EXCLUSIVE_FALLBACK_MS) return currentPhase
        Log.d(
            TAG,
            "ntk_anchor_exclusive_fallback page=$pageIndex,anchor=$anchorPageIndex,ageMs=$ageMs," +
                "source=$source,image=${image.orEmpty().substringAfterLast('/')},path=$path"
        )
        ViewerWarmupManager.logMetric("ntk_anchor_exclusive_fallback", pageIndex.toLong())
        return NtkBootPhase.ANCHOR_BITMAP_DECODED
    }

    private fun isInitialNearPage(pageIndex: Int, image: String?): Boolean {
        if (image?.contains("/p00") != true) return false
        if (pageIndex == anchorPageIndex - 1) return true
        return pageIndex in (anchorPageIndex + 1)..(anchorPageIndex + INITIAL_NEAR_PAGE_FALLBACK_AHEAD)
    }

    companion object {
        private const val TAG = "ViewerPerf"
        private const val ANCHOR_EXCLUSIVE_FALLBACK_MS = 0L
        private const val INITIAL_NEAR_PAGE_FALLBACK_AHEAD = 18
        private val NEXT_EPOCH = AtomicLong()
    }
}
