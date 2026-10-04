package ml.melun.mangaview.engine.api

/** Texture budgets split from one device allocation cap; see [DeviceMemoryBudget.textureBudgets]. */
data class EngineTextureBudgets(
    /** The native texture allocation limit; upload reservations may fill this for foreground work. */
    val allocationBytes: Long,
    /** The cap for speculative and retained tiles; strictly below [allocationBytes]. */
    val plannerBytes: Long,
    /** Bytes kept free of background reservations for foreground uploads and retirement lag. */
    val headroomBytes: Long,
)

/** Allocation accounting is separate from measured PSS; CPU/GPU shared pages are not added twice. */
data class DeviceMemoryBudget(
    val glResidentBytes: Long,
    val ownedPssIncreaseBytes: Long,
    val physicalRamKnown: Boolean,
) {
    init { require(glResidentBytes > 0L && ownedPssIncreaseBytes > 0L) }

    /**
     * Splits the GL texture allocation into the upload limit and the speculative tile budget.
     *
     * A texture dropped from the plan stays counted in the native `used` until its submitted frames
     * retire, and an upload already in flight lands on top of that, so uploaders keep
     * [EngineTextureBudgets.headroomBytes] free of background reservations while foreground work may
     * fill the full [EngineTextureBudgets.allocationBytes]. The headroom is the larger of:
     * - one eighth of the device's texture allocation, and
     * - two full tiles at [TARGET_TILE_HEIGHT_PX] and the display's larger side: one tile swapped out
     *   per scroll step plus the tile replacing it,
     * capped at half the allocation so a low-RAM device keeps a usable cache. Reading the larger
     * display side keeps the headroom sufficient under rotation and two-pane layouts.
     *
     * [EngineTextureBudgets.plannerBytes] caps only speculative and retained tiles; the planner
     * admits visible tiles against [EngineTextureBudgets.allocationBytes] itself, because a visible
     * viewport must never be dropped for headroom.
     */
    fun textureBudgets(displayWidthPx: Int, displayHeightPx: Int): EngineTextureBudgets {
        require(displayWidthPx > 0 && displayHeightPx > 0)
        val tileBytes = Math.multiplyExact(
            Math.multiplyExact(maxOf(displayWidthPx, displayHeightPx).toLong(), TARGET_TILE_HEIGHT_PX.toLong()),
            RGBA_BYTES,
        )
        val headroom = minOf(
            maxOf(glResidentBytes / 8L, Math.multiplyExact(2L, tileBytes)),
            glResidentBytes / 2L,
        )
        return EngineTextureBudgets(glResidentBytes, glResidentBytes - headroom, headroom)
    }

    companion object {
        private const val MIB = 1_048_576L
        private const val RGBA_BYTES = 4L

        /** Target tile height in device pixels; the planner's default and these budgets share it. */
        const val TARGET_TILE_HEIGHT_PX = 2048

        fun fromPhysicalRam(bytes: Long?): DeviceMemoryBudget =
            if (bytes == null || bytes < 32L) {
                DeviceMemoryBudget(128 * MIB, 256 * MIB, false)
            } else {
                DeviceMemoryBudget(minOf(bytes / 32L, 384 * MIB),
                    minOf(bytes / 16L, 768 * MIB), true)
            }
    }
}
