package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ml.melun.mangaview.source.SeriesStatus

/** Compact 연재중/완결/휴재 marker shared by covers, search rows and the detail header. */
@Composable
internal fun SeriesStatusBadge(
    status: SeriesStatus,
    colors: LibraryColors,
    modifier: Modifier = Modifier,
    fontSize: Int = 10,
) {
    val (label, background, textColor) = when (status) {
        SeriesStatus.ONGOING -> Triple("연재중", Modifier.background(colors.newGradient), Color.White)
        SeriesStatus.COMPLETED -> Triple("완결", Modifier.background(colors.mutedSurface), colors.secondary)
        SeriesStatus.HIATUS -> Triple("휴재", Modifier.background(colors.mutedSurface), colors.gold)
    }
    Box(
        modifier.clip(RoundedCornerShape(7.dp))
            .then(background)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        BasicText(label, style = microBadgeStyle(colors, fontSize).copy(color = textColor))
    }
}
