package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ml.melun.mangaview.source.SourceSeries

internal data class SavedItemRemoval(val series: SourceSeries, val tab: SavedTab)

@Composable
internal fun SavedItemRemovalDialog(item: SavedItemRemoval, colors: LibraryColors, dismiss: () -> Unit, confirm: () -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(16.dp)).padding(22.dp)) {
            BasicText("${item.series.title} 삭제", style = titleStyle(colors, 18))
            Spacer(Modifier.height(12.dp))
            BasicText(when (item.tab) {
                SavedTab.ALL -> "최근 읽기 기록과 이어보기 위치, 좋아요, 다운로드를 삭제합니다. 책갈피는 유지됩니다."
                SavedTab.RECENT -> "최근 읽기 기록과 이어보기 위치를 삭제합니다. 좋아요와 책갈피는 유지됩니다."
                SavedTab.FAVORITES -> "좋아요 목록에서 삭제합니다. 읽던 위치는 유지됩니다."
                SavedTab.OFFLINE -> "이 작품의 모든 다운로드를 삭제하고 진행 중인 다운로드를 취소합니다. 읽던 위치는 유지됩니다."
            }, style = bodyStyle(colors, 14))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryAction("취소", colors, Modifier.weight(1f).height(48.dp), dismiss)
                LibraryAction("삭제", colors, Modifier.weight(1f).height(48.dp), confirm)
            }
        }
    }
}
