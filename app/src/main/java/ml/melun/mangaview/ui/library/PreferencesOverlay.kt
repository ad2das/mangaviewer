package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PreferencesOverlay(state: LibraryState, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.height(48.dp).clickable { accept(LibraryIntent.TogglePreferences) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                LibraryIconView(LibraryIcon.BACK, colors.secondary, Modifier.size(26.dp))
            }
            BasicText("설정", style = titleStyle(colors, 20))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { PreferenceSection("기본 설정", colors) }
            item {
                val source = state.sources.firstOrNull { it.id == state.selectedSourceId }
                PreferenceRow("사이트 변경", source?.label.orEmpty(), colors) {
                    nextSource(state)?.let { accept(LibraryIntent.SourceSelected(it.id)) }
                }
            }
            item {
                PreferenceRow("앱 시작시 탭 위치", MainDestination.fromStored(state.saved.settings.startTab).label, colors) {
                    accept(LibraryIntent.StartTabChanged((state.saved.settings.startTab + 1) % MainDestination.entries.size))
                }
            }
            item {
                PreferenceSwitch("어두운 테마", state.saved.settings.darkTheme, colors) {
                    accept(LibraryIntent.DarkThemeChanged(!state.saved.settings.darkTheme))
                }
            }
            item { PreferenceSection("기타", colors) }
            item { PreferenceRow("오픈소스 라이선스", "", colors) { accept(LibraryIntent.OpenLicenses) } }
        }
    }
}

@Composable
private fun PreferenceSection(label: String, colors: LibraryColors) {
    BasicText(
        label,
        Modifier.fillMaxWidth().padding(start = 18.dp, top = 20.dp, bottom = 8.dp),
        labelStyle(colors, true),
    )
}

@Composable
private fun PreferenceRow(label: String, value: String, colors: LibraryColors, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).clickable(onClick = click).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(label, style = bodyStyle(colors, 15))
        if (value.isNotEmpty()) BasicText(value, style = labelStyle(colors, true))
    }
}

@Composable
private fun PreferenceSwitch(label: String, checked: Boolean, colors: LibraryColors, click: () -> Unit) {
    PreferenceRow(label, if (checked) "켜짐" else "꺼짐", colors, click)
}

private fun nextSource(state: LibraryState): ml.melun.mangaview.app.SourceOption? {
    val index = state.sources.indexOfFirst { it.id == state.selectedSourceId }
    return state.sources.getOrNull((index + 1).mod(state.sources.size.coerceAtLeast(1)))
}
