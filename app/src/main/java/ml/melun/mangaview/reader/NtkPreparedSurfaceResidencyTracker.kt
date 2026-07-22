package ml.melun.mangaview.reader

/**
 * Projects successful native prepared-resident acknowledgements onto the currently bound
 * Surface authority. The tracker deliberately never creates state from an acknowledgement:
 * [open] is the only admission point, so a late callback after physical release cannot
 * resurrect drawable evidence.
 */
internal class NtkPreparedSurfaceResidencyTracker(
    private val currentPublishedTiles: MutableSet<NtkStripRenderEngine.TileKey>
) {
    private val lock = Any()
    private val residentsByPreparation =
        LinkedHashMap<NtkNativePreparationToken, MutableSet<NtkStripRenderEngine.TileKey>>()
    private var boundPreparation: NtkNativePreparationToken? = null

    fun open(token: NtkNativePreparationToken) = synchronized(lock) {
        residentsByPreparation.putIfAbsent(token, LinkedHashSet())
        Unit
    }

    fun record(
        token: NtkNativePreparationToken,
        expectedIdentity: NtkNativeInstallIdentity,
        ack: NtkPreparedTileResidentAck
    ): Boolean = synchronized(lock) {
        if (ack.identity != expectedIdentity ||
            expectedIdentity.admission.authority != token.authority ||
            expectedIdentity.preparationGeneration != token.preparationGeneration
        ) return@synchronized false
        val residents = residentsByPreparation[token] ?: return@synchronized false
        val sourceKey = expectedIdentity.admission.key
        val key = NtkStripRenderEngine.TileKey(
            token.authority,
            sourceKey.pageIndex,
            sourceKey.slotIndex
        )
        residents += key
        if (boundPreparation == token) currentPublishedTiles += key
        true
    }

    /** Makes the exact resident ACK set visible atomically with respect to later ACKs. */
    fun bind(token: NtkNativePreparationToken): Boolean = synchronized(lock) {
        val residents = residentsByPreparation[token] ?: return@synchronized false
        boundPreparation = token
        currentPublishedTiles.clear()
        currentPublishedTiles.addAll(residents)
        true
    }

    fun adoptDetached(
        token: NtkNativePreparationToken,
        keys: List<NtkStripTileKey>
    ): Boolean = synchronized(lock) {
        if (keys.distinct().size != keys.size ||
            keys.any { it.episode.value != token.authority }
        ) return@synchronized false
        val residents = residentsByPreparation.getOrPut(token) { LinkedHashSet() }
        residents.clear()
        keys.forEach { key ->
            residents += NtkStripRenderEngine.TileKey(
                token.authority,
                key.pageIndex,
                key.slotIndex
            )
        }
        boundPreparation = token
        currentPublishedTiles.clear()
        currentPublishedTiles.addAll(residents)
        true
    }

    fun release(authorityToken: NtkNativeAuthorityToken): Boolean = synchronized(lock) {
        val released = residentsByPreparation.keys.filter { token ->
            token.authority == authorityToken.authority &&
                token.manifestRevision == authorityToken.manifestRevision &&
                token.manifestDigest == authorityToken.manifestDigest
        }
        if (released.isEmpty()) return@synchronized false
        released.forEach(residentsByPreparation::remove)
        if (boundPreparation in released) {
            boundPreparation = null
            currentPublishedTiles.clear()
        }
        true
    }

    /** A legacy bind cannot consume callbacks from a previously opened prepared authority. */
    fun resetForLegacyBinding() = synchronized(lock) {
        residentsByPreparation.clear()
        boundPreparation = null
    }

    fun clear() = synchronized(lock) {
        residentsByPreparation.clear()
        boundPreparation = null
        currentPublishedTiles.clear()
    }

    internal fun residentKeys(token: NtkNativePreparationToken): Set<NtkStripRenderEngine.TileKey> =
        synchronized(lock) { residentsByPreparation[token]?.toSet().orEmpty() }
}
