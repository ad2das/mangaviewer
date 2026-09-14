package ml.melun.mangaview.viewer.runtime

import android.view.Surface

/**
 * Debug-only source-tile geometry probe. Each tile is an immutable AHardwareBuffer uploaded once;
 * [present] applies a single geometry-only transaction (position/scale/visibility) per pose.
 * Calls are serial: create, upload, present, await, close. [status] is a readback for assertions:
 * `[uploads, presents, pending, presentFenceAvailable, lastPresentFenceFd, pendingReleaseFences]`.
 */
internal object SourceTileGeometryProbe {
    init { System.loadLibrary("viewer_native") }
    external fun create(surface: Surface, viewportWidth: Int, viewportHeight: Int): Long
    external fun upload(probe: Long, cpuTile: Long, width: Int, height: Int): Boolean
    external fun present(probe: Long, indices: IntArray, topUnits: IntArray, bottomUnits: IntArray,
        viewportTopUnits: Int, viewportWidth: Int, viewportHeight: Int): Boolean
    external fun await(probe: Long, timeoutMillis: Long): Boolean
    external fun status(probe: Long): LongArray?
    external fun close(probe: Long): Boolean
}
