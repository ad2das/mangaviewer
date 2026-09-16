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

internal fun savedRemovalScope(tab: SavedTab): String = when (tab) {
    SavedTab.ALL -> "최근 읽기 기록, 이어보기 위치, 좋아요, 다운로드가 함께 삭제됩니다. 책갈피는 유지됩니다."
    SavedTab.RECENT -> "최근 읽기 기록과 이어보기 위치가 삭제됩니다. 좋아요와 책갈피는 유지됩니다."
    SavedTab.FAVORITES -> "좋아요 목록에서 삭제됩니다. 읽던 위치는 유지됩니다."
    SavedTab.BOOKMARKS -> "이 작품의 책갈피가 삭제됩니다. 읽던 위치는 유지됩니다."
    SavedTab.OFFLINE -> "다운로드가 삭제되고 진행 중인 다운로드가 취소됩니다. 읽던 위치는 유지됩니다."
}

@Composable
internal fun SavedSelectionRemovalDialog(
    count: Int,
    tab: SavedTab,
    colors: LibraryColors,
    dismiss: () -> Unit,
    confirm: () -> Unit,
) {
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(16.dp)).padding(22.dp)) {
            BasicText("${count}개 삭제", style = titleStyle(colors, 18))
            Spacer(Modifier.height(12.dp))
            BasicText("선택한 항목을 삭제합니다. ${savedRemovalScope(tab)}", style = bodyStyle(colors, 14))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryAction("취소", colors, Modifier.weight(1f).height(48.dp), click = dismiss)
                LibraryAction("삭제", colors, Modifier.weight(1f).height(48.dp), click = confirm)
            }
        }
    }
}
