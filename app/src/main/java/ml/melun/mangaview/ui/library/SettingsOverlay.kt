package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsOverlay(colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .46f))
            .clickable { accept(LibraryIntent.ToggleSettings) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(colors.card).padding(start = 22.dp, top = 14.dp, end = 22.dp, bottom = 26.dp),
        ) {
            Box(
                Modifier.size(width = 42.dp, height = 4.dp).align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(3.dp)).background(colors.muted),
            )
            Spacer(Modifier.height(22.dp))
            AccountHeading(colors)
            Spacer(Modifier.height(18.dp))
            BasicText(
                "로그인 필요",
                Modifier.clip(RoundedCornerShape(12.dp)).background(colors.accentSurface)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                labelStyle(colors, true),
            )
            Spacer(Modifier.height(16.dp))
            BasicText(
                "동기화 항목: 최근 기록, 즐겨찾기, 책갈피, 이어보기 위치",
                style = bodyStyle(colors, 14).copy(color = colors.secondary),
            )
            Spacer(Modifier.height(22.dp))
            LibraryAction("Google 계정으로 로그인", colors, Modifier.fillMaxWidth().height(56.dp)) {
                accept(LibraryIntent.AccountSignIn)
            }
            Spacer(Modifier.height(10.dp))
            AccountOutlineAction("설정 열기", colors) { accept(LibraryIntent.TogglePreferences) }
            Spacer(Modifier.height(10.dp))
            AccountOutlineAction("업데이트 확인", colors) { accept(LibraryIntent.CheckForUpdate) }
        }
    }
}

@Composable
private fun AccountHeading(colors: LibraryColors) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            LibraryIconView(LibraryIcon.PROFILE, Color.White, Modifier.size(29.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            BasicText("계정으로 이어보기", style = titleStyle(colors, 18))
            Spacer(Modifier.height(5.dp))
            BasicText(
                "Google 계정에 연결하면 앱을 다시 설치해도 서재와 읽던 위치를 복구합니다.",
                style = hintStyle(colors, 12),
            )
        }
    }
}

@Composable
private fun AccountOutlineAction(label: String, colors: LibraryColors, click: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(colors.card)
            .border(1.dp, colors.outline, RoundedCornerShape(12.dp)).clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = bodyStyle(colors, 15)) }
}
