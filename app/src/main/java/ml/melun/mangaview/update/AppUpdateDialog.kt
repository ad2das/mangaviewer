package ml.melun.mangaview.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import ml.melun.mangaview.ui.library.LibraryColors
import ml.melun.mangaview.ui.library.LibraryIcon
import ml.melun.mangaview.ui.library.LibraryIconView
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
                .clip(RoundedCornerShape(24.dp))
                .background(colors.card)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(24.dp))
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val failed = state.phase == UpdatePhase.FAILED
            val tint = if (failed) colors.error else colors.accent
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                LibraryIconView(
                    when (state.phase) {
                        UpdatePhase.FAILED -> LibraryIcon.ERROR
                        UpdatePhase.CURRENT, UpdatePhase.READY -> LibraryIcon.CHECK_CIRCLE
                        else -> LibraryIcon.DOWNLOAD
                    },
                    tint,
                    Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            BasicText(
                state.phase.title(),
                style = titleStyle(colors, 19).copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(8.dp))
            // A plain sentence first; the version numbers are supporting detail, set smaller.
            state.phase.lead()?.let { lead ->
                BasicText(lead, style = hintStyle(colors, 14).copy(textAlign = TextAlign.Center, lineHeight = 21.sp))
            }
            val detail = when (state.phase) {
                UpdatePhase.DOWNLOADING -> state.percent?.let { "$it%" } ?: "파일을 받고 있어요"
                UpdatePhase.CHECKING -> null
                else -> state.message.takeIf(String::isNotBlank)
            }
            if (state.phase == UpdatePhase.DOWNLOADING) {
                Spacer(Modifier.height(16.dp))
                DownloadProgressBar(state.percent, colors)
            }
            detail?.let {
                Spacer(Modifier.height(10.dp))
                BasicText(it, style = hintStyle(colors, 12).copy(color = colors.muted, textAlign = TextAlign.Center))
            }
            Spacer(Modifier.height(22.dp))
            when (state.phase) {
                UpdatePhase.AVAILABLE -> PrimaryUpdateButton("업데이트", colors, download)
                UpdatePhase.READY -> state.file?.let { file -> PrimaryUpdateButton("설치하기", colors) { install(file) } }
                UpdatePhase.FAILED -> PrimaryUpdateButton("다시 확인", colors, check)
                else -> Unit
            }
            UpdateButton(
                when (state.phase) {
                    UpdatePhase.DOWNLOADING -> "백그라운드로 받기"
                    UpdatePhase.AVAILABLE -> "나중에"
                    else -> "닫기"
                },
                colors,
                dismiss,
            )
        }
    }
}

private fun UpdatePhase.title(): String = when (this) {
    UpdatePhase.CHECKING -> "업데이트 확인 중"
    UpdatePhase.AVAILABLE -> "새 버전이 나왔어요"
    UpdatePhase.CURRENT -> "최신 버전이에요"
    UpdatePhase.DOWNLOADING -> "업데이트 받는 중"
    UpdatePhase.READY -> "설치 준비가 끝났어요"
    UpdatePhase.FAILED -> "업데이트하지 못했어요"
    UpdatePhase.IDLE -> "앱 업데이트"
}

private fun UpdatePhase.lead(): String? = when (this) {
    UpdatePhase.CHECKING -> "GitHub에서 최신 배포를 확인하고 있어요"
    UpdatePhase.AVAILABLE -> "업데이트하면 최신 개선 사항과 오류 수정이 적용돼요"
    UpdatePhase.CURRENT -> "지금 사용 중인 버전이 가장 최신이에요"
    UpdatePhase.DOWNLOADING -> "받는 동안 계속 읽으셔도 돼요"
    else -> null
}

@Composable
private fun DownloadProgressBar(percent: Int?, colors: LibraryColors) {
    val fraction = (percent ?: 0).coerceIn(0, 100) / 100f
    Box(
        Modifier.fillMaxWidth().height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.mutedSurface),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier.fillMaxWidth(fraction).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.accentGradient),
            )
        }
    }
}

@Composable
private fun PrimaryUpdateButton(label: String, colors: LibraryColors, action: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accentGradient)
            .clickable(onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = bodyStyle(colors, 15).copy(color = Color.White, fontWeight = FontWeight.Bold))
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun UpdateButton(label: String, colors: LibraryColors, action: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = hintStyle(colors, 14).copy(fontWeight = FontWeight.SemiBold))
    }
}
