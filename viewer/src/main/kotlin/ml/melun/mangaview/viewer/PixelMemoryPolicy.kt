package ml.melun.mangaview.viewer

data class PixelMemoryPolicy(
    val maximumResidentBytes: Long = 96L * 1_024L * 1_024L,
    val warmAdmissionBytes: Long = 80L * 1_024L * 1_024L,
) {
    init {
        require(maximumResidentBytes > 0L) { "Resident pixel budget must be positive" }
        require(warmAdmissionBytes in 1 until maximumResidentBytes) {
            "Warm admission must leave retirement headroom"
        }
    }
}
