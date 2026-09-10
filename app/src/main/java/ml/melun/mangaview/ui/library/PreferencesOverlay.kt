package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PreferencesOverlay(state: LibraryState, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { accept(LibraryIntent.TogglePreferences) },
                contentAlignment = Alignment.Center,
            ) {
                LibraryIconView(LibraryIcon.BACK, colors.secondary, Modifier.size(24.dp))
            }
            Spacer(Modifier.width(6.dp))
            BasicText("설정", style = titleStyle(colors, 20).copy(fontWeight = FontWeight.Bold))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { PreferenceSection("기본 설정", colors) }
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.card)
                        .border(1.dp, colors.outline, RoundedCornerShape(18.dp)),
                ) {
                    val source = state.sources.firstOrNull { it.id == state.selectedSourceId }
                    PreferenceRow("사이트 변경", source?.label.orEmpty(), colors) {
                        nextSource(state)?.let { accept(LibraryIntent.SourceSelected(it.id)) }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline))
                    PreferenceRow(
                        "앱 시작시 탭 위치",
                        MainDestination.fromStored(state.saved.settings.startTab).label,
                        colors,
                    ) {
                        accept(LibraryIntent.StartTabChanged((state.saved.settings.startTab + 1) % MainDestination.entries.size))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline))
                    PreferenceSwitch("어두운 테마", state.saved.settings.darkTheme, colors) {
                        accept(LibraryIntent.DarkThemeChanged(!state.saved.settings.darkTheme))
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
            item { PreferenceSection("기타", colors) }
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.card)
                        .border(1.dp, colors.outline, RoundedCornerShape(18.dp)),
                ) {
                    PreferenceRow("오픈소스 라이선스", "", colors) { accept(LibraryIntent.OpenLicenses) }
                }
            }
        }
    }
}

@Composable
private fun PreferenceSection(label: String, colors: LibraryColors) {
    BasicText(
        label,
        Modifier.fillMaxWidth().padding(start = 6.dp, top = 10.dp, bottom = 6.dp),
        labelStyle(colors, true).copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
    )
}

@Composable
private fun PreferenceRow(label: String, value: String, colors: LibraryColors, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clickable(onClick = click).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(label, style = bodyStyle(colors, 15).copy(fontWeight = FontWeight.Medium))
        if (value.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    value,
                    style = labelStyle(colors, true).copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.width(6.dp))
                BasicText("›", style = hintStyle(colors, 16))
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(label: String, checked: Boolean, colors: LibraryColors, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clickable(onClick = click).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(label, style = bodyStyle(colors, 15).copy(fontWeight = FontWeight.Medium))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (checked) colors.accentSurface else colors.mutedSurface)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                BasicText(
                    if (checked) "켜짐" else "꺼짐",
                    style = labelStyle(colors, checked).copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

private fun nextSource(state: LibraryState): ml.melun.mangaview.app.SourceOption? {
    val index = state.sources.indexOfFirst { it.id == state.selectedSourceId }
    return state.sources.getOrNull((index + 1).mod(state.sources.size.coerceAtLeast(1)))
}
