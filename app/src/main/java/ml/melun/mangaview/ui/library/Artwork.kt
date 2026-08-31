package ml.melun.mangaview.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import ml.melun.mangaview.source.SourceSeries

@Composable
internal fun SeriesArtwork(
    series: SourceSeries,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, series.id, series.thumbnailKey) {
        value = runCatching { loader.load(series) }.getOrNull()
    }
    val image = bitmap
    if (image == null) {
        Box(modifier.background(colors.mutedSurface))
    } else {
        Image(image, series.title, modifier, contentScale = contentScale)
    }
}
