package ml.melun.mangaview.ui.library

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsOverlay(
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
    account: ml.melun.mangaview.account.AccountState = ml.melun.mangaview.account.AccountState(),
    updateAvailable: Boolean = false,
) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { accept(LibraryIntent.ToggleSettings) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(colors.card)
                .clickable(enabled = false) {}
                .padding(start = 22.dp, top = 14.dp, end = 22.dp, bottom = 28.dp),
        ) {
            Box(
                Modifier.size(width = 44.dp, height = 4.dp).align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(2.dp)).background(colors.muted.copy(alpha = 0.5f)),
            )
            Spacer(Modifier.height(20.dp))
            AccountHeading(colors)
            Spacer(Modifier.height(16.dp))
            BasicText(
                account.message,
                Modifier.clip(RoundedCornerShape(10.dp)).background(colors.accentSurface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                labelStyle(colors, true).copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(14.dp))
            BasicText(
                "동기화 항목: 최근 기록, 즐겨찾기, 책갈피, 이어보기 위치",
                style = bodyStyle(colors, 13).copy(color = colors.secondary),
            )
            Spacer(Modifier.height(20.dp))
            if (account.signedIn) {
                BasicText(account.displayName, style = bodyStyle(colors, 15).copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(10.dp))
                AccountOutlineAction("지금 동기화", colors) { accept(LibraryIntent.AccountRetry) }
                Spacer(Modifier.height(10.dp))
                AccountOutlineAction("로그아웃", colors) { accept(LibraryIntent.AccountSignOut) }
            } else {
                Box(
                    Modifier.fillMaxWidth().height(52.dp)
                        .shadow(3.dp, RoundedCornerShape(14.dp), spotColor = colors.accent.copy(alpha = 0.25f))
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.accentGradient)
                        .clickable { if (!account.busy) accept(LibraryIntent.AccountSignIn) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        if (account.busy) "로그인 중…" else "Google 계정으로 로그인",
                        style = bodyStyle(colors, 15).copy(color = Color.White, fontWeight = FontWeight.Bold),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AccountOutlineAction("설정 열기", colors) { accept(LibraryIntent.TogglePreferences) }
            Spacer(Modifier.height(10.dp))
            AccountOutlineAction(if (updateAvailable) "새 업데이트 있음" else "업데이트 확인", colors) {
                accept(LibraryIntent.CheckForUpdate)
            }
        }
    }
}

@Composable
private fun AccountHeading(colors: LibraryColors) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(54.dp)
                .shadow(4.dp, CircleShape, spotColor = colors.accent.copy(alpha = 0.25f))
                .clip(CircleShape)
                .background(colors.accentGradient),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.PROFILE, Color.White, Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            BasicText("계정으로 이어보기", style = titleStyle(colors, 18).copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
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
