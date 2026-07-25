package ml.melun.mangaview.reader

/** Resource policy for the finite, post-click numeric-manhwa quarantine wave. */
internal object NtkClickOwnedManhwaWavePolicy {
    private val REPLICA_HOST_RING = listOf(
        "booktoki8.org",
        "mana.apihost93.com",
        "booktoki9.org",
    )
    const val REPLICA_STRIPE_SIZE = 3

    // Restore the r79 production wave: spread normal full-body attempts only across the three
    // validator-compatible Range origins. AWS remains a failure-only terminal replica inside
    // ReaderImageCache; making it own one quarter of the ordinary wave produced the 2.497 s header
    // tail in r103 and deadline failover made the full episode substantially worse in r106.
    // This CDN is throughput-limited per cold H2 connection: measured 112-page completion improved
    // from roughly 6.0 s at 8 pools to 4.743 s at 16 and 4.268 s at 24. Thirty-two regressed to
    // about 6.3 s from handshake saturation, while the r132 28-shard retry remained slower than
    // the 24-shard r129/r130 cohort. Keep the measured production balance at 24.
    const val CONNECTION_SHARDS = 24
    // Metadata probes begin at the committed click and overlap the independent episode document.
    // One lane per bounded page prevents a second extension-discovery turn; by the time document
    // authority releases bodies, these H2/TLS sessions are normally already established.
    const val PROBE_LANES = NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS
    // Admission now happens before Call creation, not after all page GETs have already opened
    // their response headers. One transfer per measured connection shard therefore bounds real
    // server/H2 pressure, and eight executor slots keep the ring full while completed bodies are
    // committed. The next canonical page starts immediately when a permit is returned.
    const val BODY_LANES = 32
    const val ACTIVE_BODY_TRANSFERS = CONNECTION_SHARDS
    // Keep the authority document's cold QUIC request alive: starting 32 bodies before exact-count
    // proof saturated the emulator and made that independent request time out at 3.5 seconds.
    // Eight bodies still cover the entry viewport; the bounded full-page wave is released after
    // authority and the first visible body, so this never drops a canonical page.
    const val SPECULATION_DEBT_LIMIT = 8
    // This is the protocol's finite production bound. Parallel metadata-only candidate races
    // start only after the committed viewer click; the fresh document cancels every page beyond
    // its exact count before any source can be published.
    const val PROBE_FRONTIER_PAGES = NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS

    fun replicaHost(pageIndex: Int, replicaOffset: Int = 0): String {
        require(pageIndex >= 0)
        require(replicaOffset >= 0)
        return REPLICA_HOST_RING[(pageIndex + replicaOffset) % REPLICA_HOST_RING.size]
    }

    fun replicaLocalPageIndex(pageIndex: Int): Int {
        require(pageIndex >= 0)
        val host = replicaHost(pageIndex)
        val fullStripes = pageIndex / REPLICA_STRIPE_SIZE
        val stripeOffset = pageIndex % REPLICA_STRIPE_SIZE
        val weight = REPLICA_HOST_RING.count { it == host }
        val occurrencesInPartialStripe = REPLICA_HOST_RING
            .take(stripeOffset + 1)
            .count { it == host }
        return fullStripes * weight + occurrencesInPartialStripe - 1
    }

    fun connectionShard(pageIndex: Int): Int =
        replicaLocalPageIndex(pageIndex) % CONNECTION_SHARDS

    /**
     * Click-owned bodies reserve one short-lived ownership session per page. The registry's lane
     * index is a bounded physical slot, not the canonical page number. Preserve the historical
     * one-to-one mapping for pages 0..119 and fold only the larger supported episode tail back
     * into that bounded namespace.
     */
    fun ownershipLane(pageIndex: Int): Int {
        require(pageIndex in 0 until NtkSourceLanePolicy.MAX_EPISODE_PAGES)
        return pageIndex % NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS
    }

    fun representativePageIndexForConnectionShard(shard: Int): Int {
        require(shard in 0 until CONNECTION_SHARDS)
        // booktoki9 occurs exactly once at index two of every stripe, so its local ordinal equals
        // the stripe ordinal and visits each client shard without constructing a network request.
        return shard * REPLICA_STRIPE_SIZE + 2
    }

    fun isReplicaHost(host: String): Boolean =
        REPLICA_HOST_RING.any { it.equals(host, ignoreCase = true) }

    fun replicaHosts(): List<String> = REPLICA_HOST_RING.distinct()

    /**
     * Numeric books commonly use one extension for the whole volume.  The bounded click-time
     * probe already resolves real candidates for the first transport frontier, so a sufficiently
     * strong result can route pages beyond that frontier directly to the same extension.  A miss
     * still enters the normal per-page resolver; this is only a preferred first attempt and can
     * never remove a mixed-format page.
     */
    fun dominantTailExtension(candidates: List<String?>): String? {
        val observed = candidates.mapNotNull { candidate ->
            candidate
                ?.substringBefore('?')
                ?.substringBefore('#')
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf(CANDIDATE_EXTENSIONS::contains)
        }
        if (observed.size < MIN_DOMINANT_EXTENSION_EVIDENCE) return null
        val winner = observed.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return winner.key.takeIf {
            winner.value * 100 >= observed.size * DOMINANT_EXTENSION_PERCENT
        }
    }

    // These are exact pNNN page candidates only. GIF is a real canonical book page format (not an
    // animation/advertisement fallback) and ReaderImageCache validates its bytes and dimensions.
    // GIF pages occur in production books and are cheap to identify by HEAD. Put them directly
    // behind JPG so a single mixed page cannot become the tail of an otherwise complete book.
    val CANDIDATE_EXTENSIONS = listOf("jpg", "gif", "webp", "png", "jpeg")

    private const val MIN_DOMINANT_EXTENSION_EVIDENCE = 6
    private const val DOMINANT_EXTENSION_PERCENT = 80
}
