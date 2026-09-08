package ml.melun.mangaview.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File

@Composable
internal fun AppUpdateDialog(state: AppUpdateState, dismiss: () -> Unit, check: () -> Unit,
    download: () -> Unit, install: (File) -> Unit) {
    if (!state.visible) return
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().background(Color(0xff172033), RoundedCornerShape(20.dp)).padding(24.dp)) {
            BasicText(state.phase.title(), style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(18.dp))
            val description = when (state.phase) {
                UpdatePhase.DOWNLOADING -> state.percent?.let { "$it%" } ?: "파일을 받고 있습니다."
                UpdatePhase.READY -> state.message
                UpdatePhase.CHECKING -> "GitHub의 최신 배포를 확인하고 있습니다."
                else -> state.message
            }
            BasicText(description, style = TextStyle(color = Color(0xffcbd5e1), fontSize = 15.sp))
            Spacer(Modifier.height(22.dp))
            when (state.phase) {
                UpdatePhase.AVAILABLE -> UpdateButton("다운로드", download)
                UpdatePhase.READY -> state.file?.let { file -> UpdateButton("설치", { install(file) }) }
                UpdatePhase.FAILED -> UpdateButton("다시 확인", check)
                else -> Unit
            }
            UpdateButton(if (state.phase == UpdatePhase.DOWNLOADING) "백그라운드로 받기" else "닫기", dismiss)
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
private fun UpdateButton(label: String, action: () -> Unit) {
    BasicText(label, Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 14.dp),
        TextStyle(color = Color(0xff93c5fd), fontSize = 16.sp, fontWeight = FontWeight.Medium))
}
