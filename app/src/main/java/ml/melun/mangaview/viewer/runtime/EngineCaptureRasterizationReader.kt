package ml.melun.mangaview.viewer.runtime

/** Queried on the GL owner only when a natural capture ticket is bound. */
internal class EngineCaptureRasterizationReader(
    private val rendererEpoch: () -> Long,
    private val surfaceEpoch: () -> Long,
    private val query: () -> IntArray,
) : () -> EngineRasterizationInfo {
    private var cached: Triple<Long, Long, EngineRasterizationInfo>? = null

    override fun invoke(): EngineRasterizationInfo {
        val renderer = rendererEpoch()
        val surface = surfaceEpoch()
        cached?.let { if (it.first == renderer && it.second == surface) return it.third }
        val values = query()
        check(values.size == 3)
        val info = EngineRasterizationInfo(values[0], values[1], values[2])
        cached = Triple(renderer, surface, info)
        return info
    }
}
