package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.session.SceneSnapshot

internal data class PackedOwnedScene(
    val contentHeightPx: Int,
    val viewportTopPx: Int,
    val count: Int,
    val entries: IntArray,
)

internal object OwnedScenePacker {
    private const val STRIDE = 7

    fun pack(scene: SceneSnapshot): PackedOwnedScene {
        val visible = scene.quads.filter { it.visualKey != null }
        val entries = IntArray(visible.size * STRIDE)
        visible.forEachIndexed { index, quad ->
            val key = requireNotNull(quad.visualKey).value
            val at = index * STRIDE
            entries[at] = key.toInt()
            entries[at + 1] = (key ushr 32).toInt()
            entries[at + 2] = quad.sourceTopPx
            entries[at + 3] = quad.sourceBottomPx
            entries[at + 4] = quad.sourceHeightPx
            entries[at + 5] = unitsToPixels(quad.top)
            entries[at + 6] = maxOf(
                entries[at + 5] + 1,
                unitsToPixels(quad.top + quad.height),
            )
        }
        return PackedOwnedScene(
            unitsToPixels(scene.contentHeight),
            unitsToPixels(scene.scrollOffset),
            visible.size,
            entries,
        )
    }

    private fun unitsToPixels(value: FixedPx): Int = Math.toIntExact(
        Math.floorDiv(value.units, FixedPx.UNITS_PER_PIXEL),
    )
}

internal data class SceneInstallation(val id: Long, val replacement: IntArray?)

/** GL-thread owned: only a successfully installed, exactly equal draw list may be reused. */
internal class OwnedSceneIdentity {
    private var nextId = 0L
    private var installedId = 0L
    private var installedEntries: IntArray? = null

    fun prepare(scene: PackedOwnedScene): SceneInstallation {
        if (installedEntries?.contentEquals(scene.entries) == true) {
            return SceneInstallation(installedId, null)
        }
        nextId = Math.incrementExact(nextId)
        return SceneInstallation(nextId, scene.entries)
    }

    fun acknowledge(installation: SceneInstallation) {
        val entries = installation.replacement ?: return
        installedEntries = entries.copyOf()
        installedId = installation.id
    }

    fun invalidate() {
        installedEntries = null
        installedId = 0L
    }
}
