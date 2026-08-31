package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsOverlay(state: LibraryState, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .46f))
            .clickable { accept(LibraryIntent.ToggleSettings) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(colors.card).clickable(enabled = false) {}
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            BasicText("MangaView 설정", style = titleStyle(colors, 21))
            Spacer(Modifier.height(8.dp))
            BasicText("최근 기록, 좋아요, 책갈피와 이어보기 위치를 이 기기에 저장합니다.", style = hintStyle(colors))
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.mutedSurface)
                    .clickable {
                        accept(LibraryIntent.DarkThemeChanged(!state.saved.settings.darkTheme))
                    }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText("어두운 화면", style = bodyStyle(colors))
                BasicText(if (state.saved.settings.darkTheme) "켜짐" else "꺼짐", style = labelStyle(colors, state.saved.settings.darkTheme))
            }
            Spacer(Modifier.height(12.dp))
            LibraryAction("닫기", colors, Modifier.fillMaxWidth()) { accept(LibraryIntent.ToggleSettings) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
