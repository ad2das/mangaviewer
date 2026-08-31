package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun OfflineRemovalConfirmation(
    state: LibraryState,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val episodeId = state.pendingOfflineRemoval ?: return
    val title = state.offlineEpisodes.firstOrNull { it.episode.id == episodeId }?.episode?.title
        ?: episodeId.remoteKey
    Box(
        Modifier.fillMaxSize().background(Color(0x88000000))
            .clickable { accept(LibraryIntent.CancelOfflineRemoval) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp).fillMaxWidth().background(colors.card, RoundedCornerShape(16.dp)).padding(22.dp),
        ) {
            BasicText("$title 오프라인 저장을 삭제하시겠습니까?", style = bodyStyle(colors, 16))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ConfirmButton("아니오", false, colors) { accept(LibraryIntent.CancelOfflineRemoval) }
                Spacer(Modifier.width(10.dp))
                ConfirmButton("삭제", true, colors) { accept(LibraryIntent.ConfirmOfflineRemoval) }
            }
        }
    }
}

@Composable
private fun ConfirmButton(label: String, accent: Boolean, colors: LibraryColors, click: () -> Unit) {
    Box(
        Modifier.width(90.dp).height(48.dp).background(
            if (accent) colors.accent else colors.card,
            RoundedCornerShape(10.dp),
        ).border(1.dp, if (accent) colors.accent else colors.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = bodyStyle(colors, 14).copy(color = if (accent) Color.White else colors.text))
    }
}
