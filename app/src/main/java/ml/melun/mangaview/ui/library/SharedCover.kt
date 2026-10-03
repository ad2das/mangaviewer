package ml.melun.mangaview.ui.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import ml.melun.mangaview.source.SourceSeries

/**
 * The cover a reader tapped flies into the detail header instead of the screen simply swapping.
 * Both scopes are absent in the two-pane layout and in previews, where the modifier is a no-op.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedCovers = compositionLocalOf<SharedTransitionScope?> { null }
internal val LocalLayerVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
private val CoverBounds = BoundsTransform { _, _ -> tween(LibraryMotion.Slow, easing = LibraryMotion.EaseOut) }

/**
 * Marks a cover as the shared element for [series]. Use it only where a series appears at most
 * once on screen (grids, search results, the saved list, the detail header): two visible
 * elements with one key would fight over the same flight.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedCover(series: SourceSeries): Modifier {
    val shared = LocalSharedCovers.current ?: return this
    val visibility = LocalLayerVisibility.current ?: return this
    return with(shared) {
        this@sharedCover.sharedElement(
            rememberSharedContentState("cover:${series.id.sourceId.value}:${series.id.remoteKey}"),
            visibility,
            boundsTransform = CoverBounds,
        )
    }
}
