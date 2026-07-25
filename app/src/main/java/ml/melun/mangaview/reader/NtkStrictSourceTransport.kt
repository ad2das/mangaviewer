package ml.melun.mangaview.reader

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
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

    override fun close() {
        val recycleNow = synchronized(lock) {
            if (closed) return
            closed = true
            abandoned.set(true)
            !transferred
        }
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

    /** Completes only at the exact no-call/no-lease source drain boundary. */
    fun requestPreparationDrain(
        episode: NtkEpisodeToken,
        completion: (NtkSourceDrainProof) -> Unit
    )
}

fun interface NtkStrictResidentBodyListener {
    fun onResidentBody(descriptor: NtkStrictBodyDescriptor)
}
