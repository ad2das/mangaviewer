package ml.melun.mangaview.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import ml.melun.mangaview.source.SourceSeries

/**
 * Draws cover artwork decoded for the size it actually occupies. The bitmap is requested only
 * after the first layout so flings never decode full-resolution thumbnails for small slots.
 */
@Composable
internal fun SeriesArtwork(
    series: SourceSeries,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, series.id, series.thumbnailKey) {
        snapshotFlow { maxOf(bounds.width, bounds.height) }
            .filter { it > 0 }
            .distinctUntilChanged()
            .collectLatest { edge ->
                val loaded = runCatching { loader.load(series, edge) }.getOrNull()
                if (loaded != null) value = loaded
            }
    }
    Box(modifier.onSizeChanged { bounds = it }) {
        val image = bitmap
        if (image == null) {
            Box(Modifier.matchParentSize().background(colors.mutedSurface))
        } else {
            Image(image, series.title, Modifier.matchParentSize(), contentScale = contentScale)
        }
    }
}
