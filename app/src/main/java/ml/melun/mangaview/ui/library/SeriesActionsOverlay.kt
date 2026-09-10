package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SeriesActionsOverlay(
    state: LibraryState,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val series = state.activeSeries ?: return
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { accept(LibraryIntent.ToggleSeriesMenu) },
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            Modifier.padding(top = 56.dp, end = 16.dp).width(220.dp)
                .shadow(12.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.2f))
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(vertical = 6.dp),
        ) {
            MenuRow("브라우저에서 열기", LibraryIcon.SITE, colors) {
                accept(LibraryIntent.OpenSeriesInBrowser(series))
            }
            MenuRow("공유", LibraryIcon.MORE, colors) {
                accept(LibraryIntent.ShareSeries(series))
            }
            MenuRow("오프라인 저장", LibraryIcon.DOWNLOAD, colors) {
                accept(LibraryIntent.ToggleDownloadSelection)
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: LibraryIcon, colors: LibraryColors, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = click)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryIconView(icon, colors.accent, Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        BasicText(
            label,
            style = bodyStyle(colors, 14).copy(fontWeight = FontWeight.Medium),
        )
    }
}