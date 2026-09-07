package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.session.SceneQuad

internal data class UploadedTextureIdentity(
    val pageId: PageId,
    val top: Int,
    val bottom: Int,
    val height: Int,
) {
    fun matches(quad: SceneQuad): Boolean = pageId == quad.pageId &&
        top == quad.sourceTopPx && bottom == quad.sourceBottomPx && height == quad.sourceHeightPx
}
