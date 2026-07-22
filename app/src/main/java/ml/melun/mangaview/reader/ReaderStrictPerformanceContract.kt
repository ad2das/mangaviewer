package ml.melun.mangaview.reader

/** Production acceptance values. Tests pin these so refactors cannot relax the gate. */
object ReaderStrictPerformanceContract {
    const val ACTIVATION_AHEAD_VIEWPORTS = 1.5f
    const val PRODUCTION_AHEAD_VIEWPORTS = 2.0f
    // ES 3.0 guarantees at least a 2048px texture dimension. Keeping a normal NTK page in one
    // immutable texture removes four separate gfxstream uploads at each forward page boundary;
    // genuinely long images remain losslessly split on this boundary.
    const val ORIGINAL_TILE_SOURCE_HEIGHT_PX = 2048
    const val POST_ACTIVATION_BYTE_RUNWAY_PAGES = 12
    const val ROLLING_PROOF_METADATA_PAGES = 6
}
