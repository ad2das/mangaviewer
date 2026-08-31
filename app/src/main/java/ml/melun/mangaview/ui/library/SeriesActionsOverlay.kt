package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp

@Composable
internal fun SeriesActionsOverlay(
    state: LibraryState,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val series = state.activeSeries ?: return
    Box(
        Modifier.fillMaxSize().clickable { accept(LibraryIntent.ToggleSeriesMenu) },
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            Modifier.padding(top = 52.dp, end = 12.dp).width(210.dp)
                .shadow(6.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                .background(colors.card),
        ) {
            MenuRow("브라우저에서 열기", colors) {
                accept(LibraryIntent.OpenSeriesInBrowser(series))
            }
            MenuRow("공유", colors) { accept(LibraryIntent.ShareSeries(series)) }
            MenuRow("오프라인 저장", colors) {
                accept(LibraryIntent.ToggleDownloadSelection)
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, colors: LibraryColors, click: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 18.dp, vertical = 15.dp)) {
        BasicText(label, style = bodyStyle(colors, 14))
    }
}
