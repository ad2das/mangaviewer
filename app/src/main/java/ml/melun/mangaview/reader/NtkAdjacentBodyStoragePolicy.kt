package ml.melun.mangaview.reader

/**
 * Keeps exact emulator chapter bodies out of ART's large-object heap.
 *
 * Physical devices keep the established memory-resident body path. On a host-GPU emulator, every
 * exact episode body with a canonical server path stays in its already-owned sealed file. This
 * must not depend on direct-Wi-Fi/carrier/SNI routing or whether the lifecycle currently calls the
 * episode current or adjacent: both classifications can change at the physical chapter boundary,
 * exactly where copying the same body back into ART can trigger a stop-the-world NativeAlloc GC.
 * EOF/SHA ownership and exact decode semantics are unchanged.
 */
internal object NtkAdjacentBodyStoragePolicy {
    /**
     * Pauses only an unbounded speculative tail during foreground motion.
     *
     * A demand-bounded strict suffix has already crossed the compositor's HARD/SOFT lookahead
     * gate. Parking that owned socket again until motion ends defeats the lookahead, lets TCP
     * flow-control go cold, and can turn the app-authored pause into a Range timeout. Such reads
     * keep using [NtkReaderTransferPacer]'s two-lane active-motion cadence instead. Legacy
     * speculative tails retain the full optional-byte pause.
     */
    fun deferOffscreenTailDuringPhysicalMotion(
        hostGpuEmulatorRuntime: Boolean,
        adjacentPrefetch: Boolean,
        pageIndex: Int,
        initialPageIndex: Int,
        adjacentInitialRunwayBodyCount: Int,
        viewportDemandBoundsSuffix: Boolean = false,
    ): Boolean = hostGpuEmulatorRuntime &&
        adjacentPrefetch &&
        !viewportDemandBoundsSuffix &&
        adjacentInitialRunwayBodyCount > 0 &&
        pageIndex - initialPageIndex >= adjacentInitialRunwayBodyCount

    @Suppress("UNUSED_PARAMETER")
    fun useFileBackedQuarantine(
        hostGpuEmulatorRuntime: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        adjacentPrefetch: Boolean,
        episodePath: String,
    ): Boolean = hostGpuEmulatorRuntime &&
        episodePath.startsWith("/", ignoreCase = true)

    /**
     * Every exact body is already required to cross the sealed-file ownership boundary. Keep the
     * compressed source there before and after the current/adjacent ownership handoff; the host
     * exact decoder consumes that file directly, so retaining a second large Java array has no
     * latency benefit.
     */
    fun useFileBackedStrictSource(
        hostGpuEmulatorRuntime: Boolean,
        directWifiTransport: Boolean,
        cellularResilientTransport: Boolean,
        adjacentPrefetch: Boolean,
        episodePath: String,
        pageIndex: Int,
        initialPageIndex: Int,
        adjacentInitialRunwayBodyCount: Int,
    ): Boolean =
        useFileBackedQuarantine(
            hostGpuEmulatorRuntime,
            directWifiTransport,
            cellularResilientTransport,
            adjacentPrefetch,
            episodePath,
        )

    /**
     * A verified body can be consumed by the renderer's exact host path from either its sealed file
     * or its authoritative encoded bytes. Building a private full-size Bitmap first duplicates
     * that decode and reports its entire pixel allocation to ART, which can stop every renderer
     * thread for NativeAlloc GC during physical scrolling. The exact body proof, not its transient
     * network route/current-adjacent label, is the authority. Other runtimes retain the private
     * predecode path and its existing latency profile.
     */
    @Suppress("UNUSED_PARAMETER")
    fun useNativeFileDecodeInsteadOfPrivateBitmap(
        hostGpuEmulatorRuntime: Boolean,
        directWifiAdjacentOwned: Boolean,
        encodedBytesAvailable: Boolean,
        sealedFileAvailable: Boolean,
    ): Boolean = hostGpuEmulatorRuntime &&
        (encodedBytesAvailable || sealedFileAvailable)
}
