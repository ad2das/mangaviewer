package ml.melun.mangaview.reader

/** Resource policy for the finite, post-click numeric-manhwa quarantine wave. */
internal object NtkClickOwnedManhwaWavePolicy {
    data class SizedReplicaBody(
        val pageIndex: Int,
        val byteCount: Long,
        val currentHost: String,
    ) {
        init {
            require(pageIndex >= 0)
            require(byteCount > 0L)
            require(currentHost.isNotBlank())
        }
    }

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
    // The formal 230-page/27.4 MiB cohort completed in 5.211 s with this 24-shard topology.
    // Smaller rings reduce handshakes but also lose the independent cold-CDN bandwidth needed by
    // long episodes.
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
    // Direct Wi-Fi ordinary JPEG bodies can use a Network-bound HTTP/1.1 pool instead of the
    // carrier H2/SNI topology. Keep its switch and bound Wi-Fi-only so transport A/B work cannot
    // change the forty-body carrier, VPN, mixed-format, or fallback admission ring.
    const val DIRECT_WIFI_ORDINARY_H1_ENABLED = true
    const val DIRECT_WIFI_ORDINARY_BODY_TRANSFERS = 40
    const val MIXED_UNCOMMON_BODY_TRANSFERS = 8
    // Keep the authority document's cold QUIC request alive: starting 32 bodies before exact-count
    // proof saturated the emulator and made that independent request time out at 3.5 seconds.
    // Eight bodies still cover the entry viewport; the bounded full-page wave is released after
    // authority and the first visible body, so this never drops a canonical page.
    const val SPECULATION_DEBT_LIMIT = 8
    // Wi-Fi can expose forty body streams without the cellular SNI path, but doing so before the
    // entry viewport reaches EOF lets one visible image compete with the whole volume. Twelve
    // images cover the measured active-fling prefix: after an eight-page seed, the first unprotected
    // p009 body started 220-300 ms later and its 1.50 s CDN read gap left eight consecutive producer
    // callbacks without a drawable. Admit p001-p012 at the click so an isolated body stall is
    // absorbed before the user reaches it, then restore the unchanged forty-wide production ring
    // after anchor EOF. Cellular deliberately retains SPECULATION_DEBT_LIMIT=8, so its request/SNI
    // schedule remains byte-for-byte unchanged.
    const val WIFI_ENTRY_SPECULATION_PAGES = 12
    const val WIFI_ENTRY_RELEASE_TIMEOUT_MS = 12_000L
    // Four metadata samples establish the volume extension without making every page repeat the
    // five-way HEAD race.
    const val DIRECT_EXTENSION_RACE_PAGES = 4
    // Only the anchor races an unproven JPG body. Entry peers use either their own fast sample or
    // the network-specific consensus below, avoiding a second request when one per-page HEAD stalls.
    const val DIRECT_BODY_RACE_PAGES = 1
    // Cellular retains its measured two-sample route. Direct Wi-Fi can wait for a third sample
    // while the exact document is in flight, avoiding a two-versus-two mixed-format misroute
    // without changing the carrier/SNI request schedule.
    const val PREFERRED_EXTENSION_EVIDENCE = 2
    const val WIFI_PREFERRED_EXTENSION_EVIDENCE = 3
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

    fun initialSpeculationPages(wifiTransport: Boolean): Int =
        if (wifiTransport) WIFI_ENTRY_SPECULATION_PAGES else SPECULATION_DEBT_LIMIT

    fun shouldUseWifiEntryFallbackLane(
        wifiTransport: Boolean,
        pageIndex: Int,
    ): Boolean {
        require(pageIndex >= 0)
        return wifiTransport && pageIndex in 1 until WIFI_ENTRY_SPECULATION_PAGES
    }

    /**
     * Reserves the visible-body lane for a cold continue that resumes beyond the click-time probe
     * frontier. Without this distinction the restored page queues behind the complete offscreen
     * body ring, so the user can wait several seconds before the one image they are looking at even
     * starts. The carrier/SNI path deliberately keeps its existing admission topology.
     */
    fun shouldUseDirectWifiRestoredViewportLane(
        wifiTransport: Boolean,
        pageIndex: Int,
        initialViewportPage: Int,
    ): Boolean {
        require(pageIndex >= 0)
        return wifiTransport &&
            initialViewportPage >= PROBE_FRONTIER_PAGES &&
            pageIndex == initialViewportPage
    }

    /**
     * Lets the bounded deep-continue runway use the captured direct-Wi-Fi H1 pool before the
     * document has classified the whole episode as ordinary. All four bodies remain private and
     * exact-manifest gated; this only changes transport for a physical JPG/JPEG candidate. Covering
     * the three replica hosts also leaves the just-finished current-tail connections available to
     * the completion-gated adjacent p0-p3 wave. Mobile/SNI and the offscreen current wave retain
     * their existing transport.
     */
    fun shouldUseRestoredAnchorOrdinaryDirectWifiTransport(
        directWifiAdjacentOwned: Boolean,
        pageIndex: Int,
        forwardFirstPage: Int,
        extension: String,
        liveWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        capturedNetworkHandle: Long?,
        liveNetworkHandle: Long?,
    ): Boolean {
        require(pageIndex >= 0)
        require(forwardFirstPage >= 0)
        val normalizedExtension = extension.trim().lowercase()
        return !directWifiAdjacentOwned &&
            pageIndex - forwardFirstPage in 0 until DIRECT_EXTENSION_RACE_PAGES &&
            forwardFirstPage >= WIFI_ENTRY_SPECULATION_PAGES &&
            (normalizedExtension == "jpg" || normalizedExtension == "jpeg") &&
            liveWifiTransport &&
            !cellularResilientTransport &&
            capturedNetworkHandle != null &&
            liveNetworkHandle == capturedNetworkHandle
    }

    /**
     * Lets only the completion-gated adjacent p0-p3 wave reuse the direct-Wi-Fi ordinary body
     * pool when the completed predecessor proved one physical JPEG suffix for every owned page.
     * The inherited suffix remains a request hint: target HEAD reconciliation and exact-manifest
     * quarantine adoption still reject a different target suffix before publication.
     */
    fun shouldUseInheritedOrdinaryDirectWifiTransport(
        directWifiAdjacentOwned: Boolean,
        runwayPageIndex: Int,
        inheritedExtension: String,
        liveWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        capturedNetworkHandle: Long?,
        liveNetworkHandle: Long?,
    ): Boolean {
        require(runwayPageIndex >= 0)
        val extension = inheritedExtension.trim().lowercase()
        return directWifiAdjacentOwned &&
            runwayPageIndex < DIRECT_EXTENSION_RACE_PAGES &&
            (extension == "jpg" || extension == "jpeg") &&
            liveWifiTransport &&
            !cellularResilientTransport &&
            capturedNetworkHandle != null &&
            liveNetworkHandle == capturedNetworkHandle
    }

    /**
     * Keeps the normal target stripe when that host was warmed by the completed deep-resume tail.
     * If a very short tail covered fewer than three hosts, place the missing runway stripe on the
     * least-loaded proven-warm host. The logical immutable path and strict replica validation do
     * not change; this selects only the first physical replica inside the existing finite ring.
     */
    fun preferredWarmAdjacentReplicaHost(
        runwayPageIndex: Int,
        predecessorWarmHosts: List<String>,
    ): String? {
        require(runwayPageIndex >= 0)
        val warmHosts = predecessorWarmHosts
            .map { it.trim().lowercase() }
            .filter(::isReplicaHost)
            .distinct()
        if (warmHosts.isEmpty()) return null
        val loads = warmHosts.associateWith { 0 }.toMutableMap()
        for (page in 0..runwayPageIndex) {
            val canonicalHost = replicaHost(page)
            val selectedHost = if (canonicalHost in loads) {
                canonicalHost
            } else {
                warmHosts.minWithOrNull(
                    compareBy<String> { loads.getValue(it) }
                        .thenBy { warmHosts.indexOf(it) },
                ) ?: return null
            }
            if (page == runwayPageIndex) return selectedHost
            loads[selectedHost] = loads.getValue(selectedHost) + 1
        }
        return null
    }

    fun previousWarmAdjacentReplicaPage(
        runwayPageIndex: Int,
        runwayPageCount: Int,
        predecessorWarmHosts: List<String>,
    ): Int? {
        require(runwayPageCount > 0)
        require(runwayPageIndex in 0 until runwayPageCount)
        val assignments = (0 until runwayPageCount).associateWith { page ->
            preferredWarmAdjacentReplicaHost(page, predecessorWarmHosts)
                ?: replicaHost(page)
        }
        val host = assignments.getValue(runwayPageIndex)
        val hostOrder = assignments.keys
            .filter { assignments.getValue(it) == host }
            .sortedWith(
                compareBy<Int> { page ->
                    replicaHost(page) != host
                }.thenBy { it },
            )
        val position = hostOrder.indexOf(runwayPageIndex)
        return if (position > 0) hostOrder[position - 1] else null
    }

    /**
     * A host-GPU emulator can reach a very short resumed tail before a same-host p3 follower gets
     * the p0 H1 connection back. Current bodies are already complete at this adjacent-only gate, so
     * admit that one existing GET on another pooled H1 connection instead of serializing the four
     * atomic runway bodies. This changes neither the candidate nor the number of physical requests.
     */
    fun shouldParallelizeHostGpuAdjacentFollower(
        hostGpuEmulatorRuntime: Boolean,
        directWifiAdjacentOwned: Boolean,
        runwayPageIndex: Int,
        runwayPageCount: Int,
        previousWarmPage: Int?,
    ): Boolean {
        require(runwayPageCount > 0)
        require(runwayPageIndex in 0 until runwayPageCount)
        return hostGpuEmulatorRuntime &&
            directWifiAdjacentOwned &&
            runwayPageIndex < DIRECT_EXTENSION_RACE_PAGES &&
            previousWarmPage != null &&
            previousWarmPage in 0 until runwayPageIndex
    }

    /**
     * Prioritizes only an already HEAD-proven uncommon body needed by the current Wi-Fi viewport.
     *
     * This does not authorize another candidate race or body transfer. The caller merely chooses
     * which existing executor submits the one exact GET; the shared body permit and transport stay
     * unchanged. Re-checking the live network keeps a Wi-Fi-to-cellular handoff on the original
     * executor path.
     */
    fun shouldPrioritizeVerifiedDirectWifiEntryBody(
        pageIndex: Int,
        candidateExtension: String,
        currentEpisode: Boolean,
        wifiEntryPriorityMode: Boolean,
        liveWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        capturedNetworkHandle: Long?,
        liveNetworkHandle: Long?,
    ): Boolean {
        require(pageIndex >= 0)
        val extension = candidateExtension.trim().lowercase()
        return currentEpisode &&
            wifiEntryPriorityMode &&
            liveWifiTransport &&
            !cellularResilientTransport &&
            capturedNetworkHandle != null &&
            liveNetworkHandle == capturedNetworkHandle &&
            pageIndex in 1 until WIFI_ENTRY_SPECULATION_PAGES &&
            extension in CANDIDATE_EXTENSIONS &&
            extension != "jpg" &&
            extension != "jpeg"
    }

    fun shouldHoldExactPreFrameRunway(wifiTransport: Boolean, pageCount: Int): Boolean {
        require(pageCount > 0)
        return wifiTransport && pageCount > WIFI_ENTRY_SPECULATION_PAGES
    }

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

    /**
     * Opens the rest of an adjacent episode after its bounded runway becomes the real viewport.
     *
     * [exactBodyAdmissionOrder] assumes the current-episode entry policy already admitted the
     * complete forty-page forward ring.  An offscreen adjacent episode deliberately admits only
     * four bodies before the boundary, so reusing that order left pages 5..40 unresolved and
     * forced them through the slower missing-body fallback.  Once the adjacent viewport is
     * compositor-proven, those pages are current-episode work: admit the forward gap first, then
     * retain the unchanged finite-tail order.
     */
    fun adjacentExactBodyAdmissionOrder(
        pageCount: Int,
        admittedRunwayPages: Int,
    ): List<Int> {
        require(pageCount in 1..NtkSourceLanePolicy.MAX_EPISODE_PAGES)
        require(admittedRunwayPages in 0..minOf(EXACT_PRE_FRAME_RUNWAY_PAGES, pageCount))
        val forwardRingEnd = minOf(EXACT_PRE_FRAME_RUNWAY_PAGES, pageCount)
        return buildList(pageCount - admittedRunwayPages) {
            for (pageIndex in admittedRunwayPages until forwardRingEnd) add(pageIndex)
            addAll(exactBodyAdmissionOrder(pageCount))
        }
    }

    fun shouldFailoverTailHeaders(pageIndex: Int): Boolean {
        require(pageIndex >= 0)
        return pageIndex >= FORWARD_ADMISSION_RUNWAY_PAGES
    }

    fun headerFailoverMs(
        pageIndex: Int,
        directWifiOrdinaryJpeg: Boolean = false,
    ): Long {
        require(pageIndex >= 0)
        return when {
            pageIndex == 0 -> 0L
            pageIndex < DIRECT_EXTENSION_RACE_PAGES -> ENTRY_HEADER_FAILOVER_MS
            pageIndex < FORWARD_ADMISSION_RUNWAY_PAGES -> RUNWAY_HEADER_FAILOVER_MS
            directWifiOrdinaryJpeg -> RUNWAY_HEADER_FAILOVER_MS
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
     * Assigns known large bodies to the least byte-loaded exact replica using deterministic LPT.
     */
    fun sizeBalancedReplicaHosts(
        fixedBodies: List<SizedReplicaBody>,
        movableBodies: List<SizedReplicaBody>,
    ): Map<Int, String> {
        val hosts = replicaHosts()
        val loads = hosts.associateWith { 0L }.toMutableMap()
        fixedBodies.forEach { body ->
            val host = hosts.firstOrNull {
                it.equals(body.currentHost, ignoreCase = true)
            } ?: return@forEach
            loads[host] = checkNotNull(loads[host]) + body.byteCount
        }
        val assignments = LinkedHashMap<Int, String>()
        movableBodies
            .sortedWith(
                compareByDescending<SizedReplicaBody> { it.byteCount }
                    .thenBy { it.pageIndex }
            )
            .forEach { body ->
                val host = hosts.minWithOrNull(
                    compareBy<String> { checkNotNull(loads[it]) }
                        .thenBy { hosts.indexOf(it) }
                ) ?: return@forEach
                assignments[body.pageIndex] = host
                loads[host] = checkNotNull(loads[host]) + body.byteCount
            }
        return assignments
    }

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

    /**
     * Returns the exact extension set proved by the finite entry sample.
     *
     * A single extension keeps the cheap volume-wide route. Two or more extensions prove that
     * this volume cannot safely inherit one suffix, so direct Wi-Fi may resolve the remaining
     * exact pages with metadata-only races after document count authority is available.
     */
    fun observedSampleExtensions(candidates: List<String?>): List<String> {
        val observed = candidates.mapNotNull(::candidateExtension).toSet()
        return CANDIDATE_EXTENSIONS.filter(observed::contains)
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
