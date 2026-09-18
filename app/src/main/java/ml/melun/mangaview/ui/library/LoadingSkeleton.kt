package ml.melun.mangaview.ui.library

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** One shared pulse so every placeholder on screen breathes in sync. */
@Composable
internal fun rememberShimmerOpacity(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val opacity by transition.animateFloat(
        initialValue = 0.38f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "skeletonOpacity",
    )
    return opacity
}

@Composable
internal fun SkeletonBlock(
    colors: LibraryColors,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    opacity: Float = rememberShimmerOpacity(),
) {
    Box(modifier.clip(shape).alpha(opacity).background(colors.outline))
}

/** Mirrors the hero + ranked card + cover row rhythm so content lands without a jump. */
@Composable
internal fun HomeSkeleton(colors: LibraryColors, modifier: Modifier = Modifier) {
    val opacity = rememberShimmerOpacity()
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        SkeletonBlock(
            colors,
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(280.dp),
            RoundedCornerShape(22.dp),
            opacity,
        )
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBlock(colors, Modifier.width(4.dp).height(18.dp), RoundedCornerShape(2.dp), opacity)
            Spacer(Modifier.width(8.dp))
            SkeletonBlock(colors, Modifier.width(150.dp).height(18.dp), RoundedCornerShape(6.dp), opacity)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(3) {
                Column(Modifier.weight(1f)) {
                    SkeletonBlock(colors, Modifier.fillMaxWidth().height(162.dp), RoundedCornerShape(18.dp), opacity)
                    Spacer(Modifier.height(10.dp))
                    SkeletonBlock(colors, Modifier.fillMaxWidth(0.9f).height(13.dp), RoundedCornerShape(5.dp), opacity)
                    Spacer(Modifier.height(6.dp))
                    SkeletonBlock(colors, Modifier.fillMaxWidth(0.55f).height(10.dp), RoundedCornerShape(5.dp), opacity)
                }
            }
        }
    }
}

/** Saved library placeholder: same card geometry as the real list rows. */
@Composable
internal fun SavedSkeleton(colors: LibraryColors, modifier: Modifier = Modifier) {
    val opacity = rememberShimmerOpacity()
    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(6) {
            Row(
                Modifier.fillMaxWidth().height(110.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.card)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(colors, Modifier.width(74.dp).height(90.dp), RoundedCornerShape(12.dp), opacity)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(colors, Modifier.fillMaxWidth(0.75f).height(15.dp), RoundedCornerShape(5.dp), opacity)
                    SkeletonBlock(colors, Modifier.fillMaxWidth(0.45f).height(11.dp), RoundedCornerShape(5.dp), opacity)
                    SkeletonBlock(colors, Modifier.width(64.dp).height(20.dp), RoundedCornerShape(6.dp), opacity)
                }
            }
        }
    }
}

/** Genre/search grid placeholder: same two-column card geometry as the catalog rows. */
@Composable
internal fun CatalogGridSkeleton(colors: LibraryColors, modifier: Modifier = Modifier) {
    val opacity = rememberShimmerOpacity()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(2) {
                    Column(Modifier.weight(1f)) {
                        SkeletonBlock(colors, Modifier.fillMaxWidth().height(166.dp), RoundedCornerShape(18.dp), opacity)
                        Spacer(Modifier.height(10.dp))
                        SkeletonBlock(colors, Modifier.fillMaxWidth(0.85f).height(13.dp), RoundedCornerShape(5.dp), opacity)
                        Spacer(Modifier.height(6.dp))
                        SkeletonBlock(colors, Modifier.fillMaxWidth(0.5f).height(10.dp), RoundedCornerShape(5.dp), opacity)
                    }
                }
            }
        }
    }
}

/** Detail placeholder: the episode card rows below the already-visible real header. */
@Composable
internal fun DetailEpisodeSkeleton(colors: LibraryColors, modifier: Modifier = Modifier) {
    val opacity = rememberShimmerOpacity()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(6) {
            Row(
                Modifier.fillMaxWidth().height(88.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.card)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(colors, Modifier.size(48.dp), RoundedCornerShape(14.dp), opacity)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(colors, Modifier.fillMaxWidth(0.8f).height(14.dp), RoundedCornerShape(5.dp), opacity)
                    SkeletonBlock(colors, Modifier.fillMaxWidth(0.35f).height(10.dp), RoundedCornerShape(5.dp), opacity)
                }
                SkeletonBlock(colors, Modifier.size(38.dp), RoundedCornerShape(12.dp), opacity)
            }
        }
    }
}
