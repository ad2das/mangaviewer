package ml.melun.mangaview.engine.api

/** Allocation accounting is separate from measured PSS; CPU/GPU shared pages are not added twice. */
data class DeviceMemoryBudget(
    val glResidentBytes: Long,
    val ownedPssIncreaseBytes: Long,
    val physicalRamKnown: Boolean,
) {
    init { require(glResidentBytes > 0L && ownedPssIncreaseBytes > 0L) }

    companion object {
        private const val MIB = 1_048_576L

        fun fromPhysicalRam(bytes: Long?): DeviceMemoryBudget =
            if (bytes == null || bytes < 32L) {
                DeviceMemoryBudget(128 * MIB, 256 * MIB, false)
            } else {
                DeviceMemoryBudget(minOf(bytes / 32L, 384 * MIB),
                    minOf(bytes / 16L, 768 * MIB), true)
            }
    }
}
