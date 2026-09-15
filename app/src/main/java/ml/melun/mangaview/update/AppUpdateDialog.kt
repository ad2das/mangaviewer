package ml.melun.mangaview.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import ml.melun.mangaview.ui.library.LibraryColors
import ml.melun.mangaview.ui.library.bodyStyle
import ml.melun.mangaview.ui.library.hintStyle
import ml.melun.mangaview.ui.library.titleStyle

@Composable
internal fun AppUpdateDialog(state: AppUpdateState, colors: LibraryColors, dismiss: () -> Unit, check: () -> Unit,
    download: () -> Unit, install: (File) -> Unit) {
    if (!state.visible) return
    Dialog(onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.card)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(20.dp))
                .padding(24.dp),
        ) {
            BasicText(state.phase.title(), style = titleStyle(colors, 20))
            Spacer(Modifier.height(18.dp))
            val description = when (state.phase) {
                UpdatePhase.DOWNLOADING -> state.percent?.let { "$it%" } ?: "파일을 받고 있습니다."
                UpdatePhase.READY -> state.message
                UpdatePhase.CHECKING -> "GitHub의 최신 배포를 확인하고 있습니다."
                else -> state.message
            }
            BasicText(description, style = hintStyle(colors, 15))
            Spacer(Modifier.height(22.dp))
            when (state.phase) {
                UpdatePhase.AVAILABLE -> UpdateButton("다운로드", colors, download)
                UpdatePhase.READY -> state.file?.let { file -> UpdateButton("설치", colors, { install(file) }) }
                UpdatePhase.FAILED -> UpdateButton("다시 확인", colors, check)
                else -> Unit
            }
            UpdateButton(if (state.phase == UpdatePhase.DOWNLOADING) "백그라운드로 받기" else "닫기", colors, dismiss)
        }
    }
}

private fun UpdatePhase.title(): String = when (this) {
    UpdatePhase.CHECKING -> "업데이트 확인 중"
    UpdatePhase.AVAILABLE -> "새 업데이트가 있습니다"
    UpdatePhase.CURRENT -> "최신 버전입니다"
    UpdatePhase.DOWNLOADING -> "업데이트 다운로드 중"
    UpdatePhase.READY -> "설치 준비 완료"
    UpdatePhase.FAILED -> "업데이트 실패"
    UpdatePhase.IDLE -> "앱 업데이트"
}

@Composable
private fun UpdateButton(label: String, colors: LibraryColors, action: () -> Unit) {
    BasicText(
        label,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = action).padding(vertical = 14.dp),
        bodyStyle(colors, 16).copy(color = colors.accent),
    )
}
