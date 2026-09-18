package ml.melun.mangaview.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
    val artwork by key(series.id, series.thumbnailKey, loader) {
        produceState<ArtworkState>(ArtworkState.Loading) {
            snapshotFlow { maxOf(bounds.width, bounds.height) }
                .filter { it > 0 }
                .distinctUntilChanged()
                .collectLatest { edge ->
                    val loaded = loader.load(series, edge)
                    value = when {
                        loaded != null -> ArtworkState.Ready(loaded)
                        value is ArtworkState.Ready -> value
                        else -> ArtworkState.Missing
                    }
                    if (loaded == null && !series.thumbnailKey.isNullOrBlank()) {
                        retryArtworkLoad(ARTWORK_RETRY_ATTEMPTS, ARTWORK_RETRY_FIRST_DELAY_MS) {
                            loader.load(series, edge)
                        }?.let { value = ArtworkState.Ready(it) }
                    }
                }
        }
    }
    Box(modifier.onSizeChanged { bounds = it }) {
        when (val state = artwork) {
            is ArtworkState.Ready -> Image(state.image, series.title, Modifier.matchParentSize(), contentScale = contentScale)
            ArtworkState.Loading -> Box(Modifier.matchParentSize().background(colors.mutedSurface))
            ArtworkState.Missing -> MissingArtwork(series.title, colors)
        }
    }
}

/** Loading, decoded, or provably unavailable — a broken cover never masquerades as a pending one. */
private sealed interface ArtworkState {
    object Loading : ArtworkState
    object Missing : ArtworkState
    class Ready(val image: ImageBitmap) : ArtworkState
}

/** Placeholder initial for works whose provider has no usable thumbnail. */
@Composable
private fun MissingArtwork(title: String, colors: LibraryColors) {
    Box(
        Modifier.fillMaxSize().background(colors.mutedSurface),
        contentAlignment = Alignment.Center,
    ) {
        val label = artworkPlaceholderLabel(title)
        if (label.isNotEmpty()) {
            BasicText(
                text = label,
                style = TextStyle(color = colors.secondary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** First letter or digit of the title, skipping leading quotes and punctuation. */
internal fun artworkPlaceholderLabel(title: String): String =
    title.trim().firstOrNull { it.isLetterOrDigit() }?.toString().orEmpty()

/**
 * A failed cover fetch can be transient (flaky network, mirror hiccup). Retry with backoff
 * while the slot stays composed; the caller already shows the placeholder, so this never
 * blocks the UI. Gives up after a bounded number of attempts.
 */
internal suspend fun <T> retryArtworkLoad(attempts: Int, firstDelayMs: Long, load: suspend () -> T?): T? {
    var waitMs = firstDelayMs
    repeat(attempts) {
        delay(waitMs)
        waitMs *= 2
        load()?.let { return it }
    }
    return null
}

private const val ARTWORK_RETRY_ATTEMPTS = 4
private const val ARTWORK_RETRY_FIRST_DELAY_MS = 1_000L
