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
    // The initial bodies protect the document path; after exact-count validation, admit the whole
    // finite network-operation frontier in one executor wave. The previous 64-thread executor left
    // 48 pages of a 112-page/30 MiB book in an unavoidable second turn and missed the four-second
    // cold deadline even though all 48 replica-local H2 pools were already healthy. Physical
    // connections remain bounded by CONNECTION_SHARDS; these lanes only remove the artificial
    // client-side stream queue and never open one socket per page.
    const val BODY_LANES = NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS
    const val ACTIVE_BODY_TRANSFERS = NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS
    // r98 admitted 32 forward bodies while the exact document was in flight.
    const val SPECULATION_DEBT_LIMIT = 32
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

    fun representativePageIndexForConnectionShard(shard: Int): Int {
        require(shard in 0 until CONNECTION_SHARDS)
        // booktoki9 occurs exactly once at index two of every stripe, so its local ordinal equals
        // the stripe ordinal and visits each client shard without constructing a network request.
        return shard * REPLICA_STRIPE_SIZE + 2
    }

    fun isReplicaHost(host: String): Boolean =
        REPLICA_HOST_RING.any { it.equals(host, ignoreCase = true) }

    fun replicaHosts(): List<String> = REPLICA_HOST_RING.distinct()

    // These are exact pNNN page candidates only. GIF is a real canonical book page format (not an
    // animation/advertisement fallback) and ReaderImageCache validates its bytes and dimensions.
    // GIF pages occur in production books and are cheap to identify by HEAD. Put them directly
    // behind JPG so a single mixed page cannot become the tail of an otherwise complete book.
    val CANDIDATE_EXTENSIONS = listOf("jpg", "gif", "webp", "png", "jpeg")
}
