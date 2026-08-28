package ml.melun.mangaview.reader

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot ownership handoff for a full-quality bitmap decoded from a private post-click body.
 *
 * The quarantine owner may decode bytes before global manifest promotion, but it cannot publish
 * those pixels.  The exact source lease is the only consumer allowed to take the bitmap after the
 * body has been matched to the immutable page manifest.  Closing an unconsumed handoff recycles
 * the bitmap, including the case where decode finishes after the viewer has already retired.
 */
class NtkStrictPredecodedOriginal internal constructor(
    private val completion: CompletableFuture<Bitmap?>,
    private val abandoned: AtomicBoolean = AtomicBoolean(false),
    private val started: AtomicBoolean = AtomicBoolean(completion.isDone),
    private val releaseQueuedSource: (() -> Unit)? = null,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var transferred = false

    /**
     * Takes an already-complete speculative decode without ever parking an authoritative decoder.
     *
     * The encoded body in [NtkStrictBodyLease] is always sufficient to produce the final pixels.
     * This handoff is only an overlap optimization, so waiting for its executor would invert that
     * authority and can deadlock the whole decode pool behind a queued speculative task. If the
     * bitmap is not ready at this exact point, abandon it and let the caller decode the body now.
     */
    fun takeIfReadyOrAbandon(sourceWidth: Int, sourceHeight: Int): Bitmap? {
        val bitmap = if (completion.isDone) {
            runCatching { completion.getNow(null) }.getOrNull()
        } else {
            null
        }
        if (bitmap == null) {
            close()
            return null
        }
        val accepted = synchronized(lock) {
            if (closed || transferred || bitmap.isRecycled ||
                bitmap.width != sourceWidth || bitmap.height != sourceHeight
            ) {
                null
            } else {
                transferred = true
                bitmap
            }
        }
        if (accepted == null) close()
        return accepted
    }

    /**
     * Bounded counterpart used only by the dedicated host-emulator adjacent-p0 decoder.
     * A queued speculative task never parks the authoritative path; an already-running task gets
     * only a few milliseconds to avoid launching a second JPEG decode of the same one-tile page.
     */
    fun takeIfReadyOrAwaitStarted(
        sourceWidth: Int,
        sourceHeight: Int,
        waitMs: Long,
    ): Bitmap? {
        require(waitMs >= 0L)
        val bitmap = when {
            completion.isDone -> runCatching { completion.getNow(null) }.getOrNull()
            started.get() && waitMs > 0L -> try {
                completion.get(waitMs, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: Exception) {
                null
            }
            else -> null
        }
        if (bitmap == null) {
            close()
            return null
        }
        val accepted = synchronized(lock) {
            if (closed || transferred || bitmap.isRecycled ||
                bitmap.width != sourceWidth || bitmap.height != sourceHeight ||
                bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.isMutable
            ) {
                null
            } else {
                transferred = true
                bitmap
            }
        }
        if (accepted == null) close()
        return accepted
    }

    override fun close() {
        val recycleNow = synchronized(lock) {
            if (closed) return
            closed = true
            abandoned.set(true)
            !transferred
        }
        // The producer atomically takes its input before setting started. Calling this for every
        // close is therefore race-safe: it either closes a still-queued descriptor or observes
        // that the running producer already owns it and lets that producer's finally close it.
        releaseQueuedSource?.invoke()
        if (recycleNow) {
            completion.whenComplete { bitmap, _ ->
                bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        }
    }

    internal fun isAbandoned(): Boolean = abandoned.get()
}

data class NtkStrictBodyLease(
    val sourceKey: NtkStrictSourceKey,
    val file: File,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val metadata: NtkSourceMetadata,
    val proof: NtkEncodedOriginalProof,
    /** Verified post-click bytes. When present they are authoritative over the deferred file. */
    val encodedBytes: ByteArray? = null,
    /** Private post-click decode, consumable only after this exact lease has been opened. */
    val predecodedOriginal: NtkStrictPredecodedOriginal? = null,
    val release: () -> Unit
) {
    init {
        require(encodedBytes == null || encodedBytes.size.toLong() == proof.encodedLength)
        require(encodedBytes != null || file.isFile)
    }
}

data class NtkStrictBodyDescriptor(
    val descriptorId: Long,
    val sourceKey: NtkStrictSourceKey,
    val metadata: NtkSourceMetadata,
    val proof: NtkEncodedOriginalProof,
    val openLease: () -> NtkStrictBodyLease
) {
    init {
        require(descriptorId > 0L)
        require(sourceKey == metadata.strictSourceKey)
        require(sourceKey == proof.strictSourceKey)
        require(proof.metadataBindingDigest == metadata.metadataBindingDigest)
        require(proof.responseIdentityDigest == metadata.authority.responseIdentityDigest)
        require(proof.encodedLength == metadata.authority.encodedLength)
    }
}

/** Bulk strict-session boundary. Production must bind once instead of registering 87 pages. */
interface NtkStrictSourceTransport : NtkSourceEventTransport {
    /** Monotonic timestamp of the one exact production seal that created this transport. */
    val exactSealAtMs: Long
        get() = -1L

    /**
     * Immutable transport profile captured when this exact adjacent source session was created.
     * Reader publication must not reclassify the same manifest from the process-wide HTTP
     * client's later, mutable network state.
     */
    val directWifiAdjacentRunwayProfile: Boolean
        get() = false

    fun bindEpisode(
        episode: NtkEpisodeToken,
        manifestSeal: NtkEpisodeManifestSeal,
        initialPageIndex: Int,
        listener: NtkSourceEventListener
    ): Closeable

    /**
     * Binds the render-only resident-body channel. Bodies on this channel have already passed
     * response EOF, SHA-256, header and exact-manifest authority validation. Delivery is allowed
     * directly from a physical source lane so image decode is not serialized behind the source
     * accounting actor. The normal [bindEpisode] ledger remains the lifecycle authority.
     */
    fun bindResidentBodies(
        episode: NtkEpisodeToken,
        manifestSeal: NtkEpisodeManifestSeal,
        listener: NtkStrictResidentBodyListener
    ): Closeable

    fun onGeometrySealed(
        episode: NtkEpisodeToken,
        geometryDigest: String,
        exactStagePageIndexes: Set<Int>
    )

    /** Opens post-anchor bulk transfer only after an identity-valid real-pixel frame committed. */
    fun onFirstActualFramePresented(episode: NtkEpisodeToken)

    /** The restored tail anchor has an authoritative drawable ready for compositor submission. */
    fun onInitialDrawableCommitted(episode: NtkEpisodeToken) = Unit

    /**
     * Admits the encoded adjacent suffix after the predecessor is completely drawable. This does
     * not publish list structure or decode pixels; it only lets the resident-body channel finish
     * before the next physical scroll.
     */
    fun onAdjacentPredecessorComplete(episode: NtkEpisodeToken)

    /**
     * Reports that the already prepared adjacent episode has become the physical viewport.
     */
    fun onAdjacentViewportActivated(episode: NtkEpisodeToken)

    /**
     * Releases the rest of the bounded adjacent webtoon wave only after p0's exact leading
     * pixels are owned by the renderer. This is deliberately narrower than full-runway commit:
     * it never releases p4+ and is a no-op for current, cellular/SNI, manhwa, and generic work.
     */
    fun onAdjacentHeadPixelsInstalled(episode: NtkEpisodeToken) = Unit

    /** Reports that the bounded p0..p3 drawable runway has committed to the reader. */
    fun onAdjacentDrawableRunwayCommitted(episode: NtkEpisodeToken)

    /**
     * Completes the remaining immutable source table after this adjacent episode is physically
     * foreground and real input has become quiet. Implementations must keep active-motion demand
     * bounded; this is an idle-completion edge, not permission to widen speculative work while a
     * user is scrolling.
     */
    fun onForegroundIdleCompletionRequested(episode: NtkEpisodeToken) = Unit

    /**
     * Re-drives exactly one source whose absence has clamped a real physical forward gesture.
     * This is narrower than idle completion: implementations must not admit the remaining tail.
     */
    fun onPhysicalBlockedPageRequested(episode: NtkEpisodeToken, pageIndex: Int) = Unit

    /** Generation-owned exact bodies which have not reached a terminal future yet. */
    fun unresolvedStreamedExactBodyCount(): Int = 0

    /** Completes only at the exact no-call/no-lease source drain boundary. */
    fun requestPreparationDrain(
        episode: NtkEpisodeToken,
        completion: (NtkSourceDrainProof) -> Unit
    )
}

fun interface NtkStrictResidentBodyListener {
    fun onResidentBody(descriptor: NtkStrictBodyDescriptor)
}
