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
    // their response headers. One transfer per measured connection shard bounds real server/H2
    // pressure, while eight executor slots keep the ring full as completed bodies are committed.
    // A 119-page/30 MiB cold trace originally rejected 40 lanes because image-completion messages
    // could delay recurring producer-vsync registration and increased real SurfaceFlinger jank.
    // That renderer lifecycle bug is now removed: the successor Choreographer callback is reserved
    // directly on the producer thread before completion work can queue. With 32 lanes the measured
    // active CPU was only 28.9%, while a tail page did not start until the second transfer wave and
    // became the 4.60 s episode terminal. Forty cut that to 4.03 s with 0/279 presentation jank.
    // A 44-lane follow-up remained 4.03 s while increasing scroll CPU and main-thread time, so keep
    // the smaller measured ring and remove the anchor-release serialization instead.
    const val BODY_LANES = 40
    const val ACTIVE_BODY_TRANSFERS = BODY_LANES
    // Keep the authority document's cold QUIC request alive: starting 32 bodies before exact-count
    // proof saturated the emulator and made that independent request time out at 3.5 seconds.
    // Eight bodies still cover the entry viewport; the bounded full-page wave is released after
    // authority and the first visible body, so this never drops a canonical page.
    const val SPECULATION_DEBT_LIMIT = 8
    // Four metadata samples establish the volume extension without making every page repeat the
    // five-way HEAD race.
    const val DIRECT_EXTENSION_RACE_PAGES = 4
    // Only the anchor races an unproven JPG body. Entry peers use either their own fast sample or
    // the two-sample volume consensus, avoiding a second request when one per-page HEAD stalls.
    const val DIRECT_BODY_RACE_PAGES = 1
    // A single slow HEAD must not hold every page after the entry viewport. Two independently
    // proven sample pages are enough to select the volume hint; a genuinely mixed page still
    // falls through the per-page exhaustive resolver after its attempted body misses.
    const val PREFERRED_EXTENSION_EVIDENCE = 2
    // Once the complete click-owned document has proved the exact page count, fill the already
    // bounded body ring while the first physical frame is finishing. Page zero retains its
    // dedicated transfer permit/executor and the authority request has already completed, so this
    // cannot recreate the old 104-stream pre-document burst that starved both. Starting only four
    // additional bodies left twenty healthy connection shards idle for ~0.8 s on a 119-page cold
    // volume and made the final current-episode body miss the four-second target.
    const val EXACT_PRE_FRAME_RUNWAY_PAGES = 40
    // The initial eight pages are already in flight. Fill the remaining physical body ring by
    // alternating the next forward page with the finite tail. This retains roughly twenty pages
    // of immediate forward runway while preventing p047-p052 from all entering the under-filled
    // second wave. Reordering changes neither the finite request set nor physical concurrency.
    const val FORWARD_ADMISSION_RUNWAY_PAGES = EXACT_PRE_FRAME_RUNWAY_PAGES
    // The anchor keeps its dedicated segmented transport. Entry peers get generous cold-handshake
    // headroom, the forward runway gets a longer deadline than the offscreen tail, and the tail
    // retains the measured fast failover. This avoids the old all-page 700 ms reset storm while
    // preventing a single 5-20 second headerless request in a short 18-39 page book from becoming
    // the entire episode terminal. No response body exists before these deadlines; advancing the
    // same logical call cannot duplicate a successful image download.
    const val ENTRY_HEADER_FAILOVER_MS = 2_500L
    const val RUNWAY_HEADER_FAILOVER_MS = 1_800L
    const val TAIL_HEADER_FAILOVER_MS = 700L
    const val MAX_CONCURRENT_TAIL_HEADER_FAILOVERS = 2
    const val HEADER_FAILOVER_PERMIT_RECHECK_MS = 50L
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
     * Deterministic exact-count release order for the post-document body wave.
     *
     * The first eight pages already own the entry viewport. Subsequent forward pages and the
     * immutable exact tail alternate until the fixed forward ring is exhausted, then the remaining
     * tail stays reverse ordered. This overlaps CDN outliers at both ends while preserving a long
     * normal-reading runway, without opening another request or increasing [ACTIVE_BODY_TRANSFERS].
     */
    fun exactBodyAdmissionOrder(pageCount: Int): List<Int> {
        require(pageCount in 1..NtkSourceLanePolicy.MAX_EPISODE_PAGES)
        val alreadyAdmitted = minOf(EXACT_PRE_FRAME_RUNWAY_PAGES, pageCount)
        val forwardEnd = minOf(FORWARD_ADMISSION_RUNWAY_PAGES, pageCount)
        return buildList(pageCount - alreadyAdmitted) {
            var forwardPage = alreadyAdmitted
            var tailPage = pageCount - 1
            while (forwardPage < forwardEnd && tailPage >= forwardEnd) {
                add(forwardPage++)
                add(tailPage--)
            }
            while (forwardPage < forwardEnd) add(forwardPage++)
            while (tailPage >= forwardEnd) add(tailPage--)
        }
    }

    fun shouldFailoverTailHeaders(pageIndex: Int): Boolean {
        require(pageIndex >= 0)
        return pageIndex >= FORWARD_ADMISSION_RUNWAY_PAGES
    }

    fun headerFailoverMs(pageIndex: Int): Long {
        require(pageIndex >= 0)
        return when {
            pageIndex == 0 -> 0L
            pageIndex < DIRECT_EXTENSION_RACE_PAGES -> ENTRY_HEADER_FAILOVER_MS
            pageIndex < FORWARD_ADMISSION_RUNWAY_PAGES -> RUNWAY_HEADER_FAILOVER_MS
            else -> TAIL_HEADER_FAILOVER_MS
        }
    }

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
        val observed = candidates.mapNotNull(::candidateExtension)
        if (observed.size < MIN_DOMINANT_EXTENSION_EVIDENCE) return null
        val winner = observed.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return winner.key.takeIf {
            winner.value * 100 >= observed.size * DOMINANT_EXTENSION_PERCENT
        }
    }

    /**
     * Selects a deterministic click-time extension as soon as enough real HEAD samples agree.
     *
     * [candidates] is a completion snapshot, so unresolved/failed samples are represented by null.
     * The candidate list order remains the tie-breaker and therefore cannot depend on callback
     * scheduling. Callers use [PREFERRED_EXTENSION_EVIDENCE] while requests are still in flight,
     * then one item as the all-samples-complete fallback to preserve the old best-observed result.
     */
    fun preferredSampleExtension(
        candidates: List<String?>,
        minimumEvidence: Int = PREFERRED_EXTENSION_EVIDENCE,
    ): String? {
        require(minimumEvidence > 0)
        val counts = candidates
            .mapNotNull(::candidateExtension)
            .groupingBy { it }
            .eachCount()
        return CANDIDATE_EXTENSIONS
            .asSequence()
            .filter { (counts[it] ?: 0) >= minimumEvidence }
            .maxByOrNull { extension ->
                (counts[extension] ?: 0) * 100 - CANDIDATE_EXTENSIONS.indexOf(extension)
            }
    }

    private fun candidateExtension(candidate: String?): String? =
        candidate
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf(CANDIDATE_EXTENSIONS::contains)

    // These are exact pNNN page candidates only. GIF is a real canonical book page format (not an
    // animation/advertisement fallback) and ReaderImageCache validates its bytes and dimensions.
    // GIF pages occur in production books and are cheap to identify by HEAD. Put them directly
    // behind JPG so a single mixed page cannot become the tail of an otherwise complete book.
    val CANDIDATE_EXTENSIONS = listOf("jpg", "gif", "webp", "png", "jpeg")

    private const val MIN_DOMINANT_EXTENSION_EVIDENCE = 6
    private const val DOMINANT_EXTENSION_PERCENT = 80
}
