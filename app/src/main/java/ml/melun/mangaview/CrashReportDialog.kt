package ml.melun.mangaview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ml.melun.mangaview.ui.library.LibraryColors
import ml.melun.mangaview.ui.library.LibraryIcon
import ml.melun.mangaview.ui.library.LibraryIconView
import ml.melun.mangaview.ui.library.LibraryMotion
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
    // The raw report is for the issue tracker, not for reading: it stays folded until asked for.
    var detailsShown by rememberSaveable { mutableStateOf(false) }
    val chevronTurn by animateFloatAsState(if (detailsShown) 90f else 0f, tween(LibraryMotion.Fast), label = "crashChevron")
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.card)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(24.dp))
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CrashDialogHeader(report, colors)
            CrashDetailsSection(detailsShown, chevronTurn, report, colors) { detailsShown = !detailsShown }
            Spacer(Modifier.height(18.dp))
            CrashPrimaryAction("GitHub에 오류 보고하기", colors, onGitHub)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                CrashTextAction("내용 복사", colors, onCopy, Modifier.weight(1f))
                CrashTextAction("닫기", colors, onDismiss, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CrashDialogHeader(report: String, colors: LibraryColors) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(colors.error.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        LibraryIconView(LibraryIcon.ERROR, colors.error, Modifier.size(28.dp))
    }
    Spacer(Modifier.height(16.dp))
    BasicText(
        CrashReportText.headline(report),
        style = titleStyle(colors, 19).copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
    )
    Spacer(Modifier.height(8.dp))
    BasicText(
        "불편을 드려 죄송해요. 오류 정보를 보내 주시면 같은 문제가 다시 생기지 않도록 고칠게요.",
        style = hintStyle(colors, 14).copy(textAlign = TextAlign.Center, lineHeight = 21.sp),
    )
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun CrashDetailsSection(
    detailsShown: Boolean,
    chevronTurn: Float,
    report: String,
    colors: LibraryColors,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            if (detailsShown) "오류 정보 접기" else "오류 정보 보기",
            style = hintStyle(colors, 13).copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.width(4.dp))
        LibraryIconView(LibraryIcon.CHEVRON, colors.muted, Modifier.size(14.dp).rotate(chevronTurn))
    }
    AnimatedVisibility(
        visible = detailsShown,
        enter = fadeIn(tween(LibraryMotion.Fast)) + expandVertically(tween(LibraryMotion.Medium, easing = LibraryMotion.EaseOut)),
        exit = fadeOut(tween(LibraryMotion.Fast)) + shrinkVertically(tween(LibraryMotion.Fast)),
        label = "crashDetails",
    ) {
        Box(
            Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = 200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.mutedSurface)
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
private fun CrashTextAction(label: String, colors: LibraryColors, click: () -> Unit, modifier: Modifier) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = hintStyle(colors, 14).copy(fontWeight = FontWeight.SemiBold))
    }
}
