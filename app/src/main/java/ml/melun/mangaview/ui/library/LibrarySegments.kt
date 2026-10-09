package ml.melun.mangaview.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Equal-width segmented control with one thumb that slides to the chosen segment, so a change of
 * tab reads as movement rather than one pill vanishing and another appearing. [accent] fills the
 * thumb with the brand gradient for a primary choice; otherwise it is a raised neutral surface.
 */
@Composable
internal fun <T> SlidingSegments(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    colors: LibraryColors,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    accent: Boolean = false,
    select: (T) -> Unit,
) {
    BoxWithConstraints(
        modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(15.dp))
            .background(colors.mutedSurface)
            .padding(3.dp),
    ) {
        val segment = maxWidth / items.size
        val index = items.indexOf(selected).coerceAtLeast(0)
        val thumbOffset by animateDpAsState(
            segment * index,
            tween(LibraryMotion.Medium, easing = LibraryMotion.EaseOut),
            label = "segmentThumb",
        )
        val thumbShape = RoundedCornerShape(12.dp)
        Box(
            Modifier.offset(x = thumbOffset).width(segment).fillMaxHeight()
                .shadow(
                    if (accent) 4.dp else 2.dp,
                    thumbShape,
                    spotColor = if (accent) colors.accent.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.10f),
                )
                .clip(thumbShape)
                .then(if (accent) Modifier.background(colors.accentGradient) else Modifier.background(colors.segmentThumb)),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEach { item ->
                val active = item == selected
                val labelColor by animateColorAsState(
                    targetValue = when {
                        !active -> colors.secondary
                        accent -> Color.White
                        else -> colors.text
                    },
                    animationSpec = tween(LibraryMotion.Fast),
                    label = "segmentLabel",
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(thumbShape)
                        .selectable(selected = active, role = Role.Tab) { select(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        label(item),
                        style = bodyStyle(colors, 13).copy(
                            color = labelColor,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
