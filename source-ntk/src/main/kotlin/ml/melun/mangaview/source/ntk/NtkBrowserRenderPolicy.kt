package ml.melun.mangaview.source.ntk

/** Keeps the authorization DOM functional without competing with the visible reader renderer. */
internal enum class NtkBrowserRenderPhase {
    ORIGIN_WARMUP,
    INITIAL_AUTHORIZATION,
    ADJACENT_AUTHORIZATION,
    PARKED,
}

internal data class NtkBrowserRenderPolicy(
    val visible: Boolean,
    val hardwareRaster: Boolean,
    val boundRenderer: Boolean,
)

internal fun NtkBrowserRenderPhase.renderPolicy(): NtkBrowserRenderPolicy = when (this) {
    NtkBrowserRenderPhase.INITIAL_AUTHORIZATION -> NtkBrowserRenderPolicy(
        visible = true,
        hardwareRaster = true,
        boundRenderer = true,
    )
    NtkBrowserRenderPhase.ADJACENT_AUTHORIZATION -> NtkBrowserRenderPolicy(
        visible = true,
        hardwareRaster = true,
        boundRenderer = false,
    )
    NtkBrowserRenderPhase.ORIGIN_WARMUP -> NtkBrowserRenderPolicy(
        visible = true,
        hardwareRaster = true,
        boundRenderer = false,
    )
    NtkBrowserRenderPhase.PARKED -> NtkBrowserRenderPolicy(
        visible = false,
        hardwareRaster = false,
        boundRenderer = false,
    )
}
