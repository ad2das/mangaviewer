package ml.melun.mangaview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ml.melun.mangaview.ui.library.LibraryColors
import ml.melun.mangaview.ui.library.bodyStyle
import ml.melun.mangaview.ui.library.hintStyle
import ml.melun.mangaview.ui.library.titleStyle

/**
 * Offers the last crash for a GitHub issue or a clipboard copy. The full report is copied even
 * when the issue body has to be shortened, so a paste always carries everything.
 */
@Composable
internal fun CrashReportDialog(
    report: String,
    colors: LibraryColors,
    onCopy: () -> Unit,
    onGitHub: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.card)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(20.dp))
                .padding(24.dp),
        ) {
            BasicText("앱 오류 리포트", style = titleStyle(colors, 20))
            Spacer(Modifier.height(8.dp))
            BasicText(
                "앱이 예기치 않게 종료되었습니다. 아래 내용을 GitHub 이슈로 보내면 원인 파악에 도움이 됩니다.",
                style = hintStyle(colors, 13),
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.mutedSurface)
                    .border(1.dp, colors.outline, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                BasicText(
                    CrashReportText.preview(report),
                    style = bodyStyle(colors, 11).copy(
                        color = colors.secondary,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
            Spacer(Modifier.height(18.dp))
            CrashPrimaryAction("GitHub에 리포트", colors, onGitHub)
            Spacer(Modifier.height(10.dp))
            CrashOutlineAction("오류 내용 복사", colors, onCopy)
            Spacer(Modifier.height(4.dp))
            CrashTextAction("닫기", colors, onDismiss)
        }
    }
}

@Composable
private fun CrashPrimaryAction(label: String, colors: LibraryColors, click: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accentGradient)
            .clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = bodyStyle(colors, 15).copy(color = Color.White, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun CrashOutlineAction(label: String, colors: LibraryColors, click: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.mutedSurface)
            .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
            .clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = bodyStyle(colors, 14).copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun CrashTextAction(label: String, colors: LibraryColors, click: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = hintStyle(colors, 14).copy(fontWeight = FontWeight.SemiBold))
    }
}
