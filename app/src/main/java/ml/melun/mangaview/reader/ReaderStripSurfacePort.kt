package ml.melun.mangaview.reader

/** The Controller owns the only implementation and therefore remains the sole Surface writer. */
interface ReaderStripSurfacePort {
    fun bind(authority: Long, geometry: NtkStripGeometry): Boolean
    fun install(commands: List<NtkStripTileInstall>): ReaderSurfaceView.StripInstallResult
    fun coverage(): ReaderSurfaceView.StripResidentCoverageSnapshot?
    fun release(authority: Long): Boolean
}

class ReaderViewStripSurfacePort(
    private val view: ReaderSurfaceView
) : ReaderStripSurfacePort {
    override fun bind(authority: Long, geometry: NtkStripGeometry): Boolean =
        view.extendAuthoritativeStrip(authority, geometry) ||
            view.bindAuthoritativeStrip(authority, geometry)

    override fun install(commands: List<NtkStripTileInstall>) =
        view.installAuthoritativeStripTileDelta(commands)

    override fun coverage(): ReaderSurfaceView.StripResidentCoverageSnapshot? =
        view.authoritativeStripCoverageSnapshot()

    override fun release(authority: Long): Boolean = view.releaseAuthoritativeStrip(authority)
}
