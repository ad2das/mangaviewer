package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ml.melun.mangaview.app.SourceOption

@Composable
internal fun LibraryScreen(state: LibraryState, accept: (LibraryIntent) -> Unit) {
    val colors = libraryColors(state.saved.settings.darkTheme)
    Column(
        Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().padding(18.dp),
    ) {
        BasicText("MangaViewer", style = titleStyle(colors))
        Spacer(Modifier.height(14.dp))
        LibraryTabs(state.selectedTab, colors, accept)
        Spacer(Modifier.height(14.dp))
        when (state.selectedTab) {
            LibraryTab.SEARCH -> SearchSection(state, colors, accept)
            LibraryTab.RECENT -> RecentSection(state.saved.recent, colors, accept)
            LibraryTab.FAVORITES -> FavoriteSection(state.saved.favorites, colors, accept)
            LibraryTab.BOOKMARKS -> BookmarkSection(state.saved.bookmarks, colors, accept)
            LibraryTab.SETTINGS -> SettingsSection(state.saved.settings, colors, accept)
        }
    }
}

@Composable
private fun LibraryTabs(
    selected: LibraryTab,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryTab.entries.forEach { tab ->
            val background = if (tab == selected) colors.accent else colors.surface
            BasicText(
                tab.label,
                Modifier.background(background).clickable { accept(LibraryIntent.TabSelected(tab)) }
                    .padding(horizontal = 13.dp, vertical = 9.dp),
                style = bodyStyle(colors),
            )
        }
    }
}

@Composable
private fun SearchSection(state: LibraryState, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    SourceSelector(state.sources, state.selectedSourceId.value, colors, accept)
    Spacer(Modifier.height(12.dp))
    SearchBar(state.query, colors, accept)
    Spacer(Modifier.height(14.dp))
    SearchResults(state.content, state.saved.favorites.mapTo(hashSetOf()) { it.id }, colors, accept)
}

@Composable
private fun SourceSelector(
    sources: List<SourceOption>,
    selectedId: String,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEach { option ->
            val background = if (option.id.value == selectedId) colors.accent else colors.surface
            BasicText(
                option.label,
                Modifier.background(background).clickable {
                    accept(LibraryIntent.SourceSelected(option.id))
                }.padding(horizontal = 14.dp, vertical = 9.dp),
                style = bodyStyle(colors),
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = { accept(LibraryIntent.QueryChanged(it)) },
            singleLine = true,
            textStyle = bodyStyle(colors),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { accept(LibraryIntent.Search) }),
            modifier = Modifier.weight(1f).background(colors.surface).padding(14.dp),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) BasicText("작품 검색", style = hintStyle(colors))
                    field()
                }
            },
        )
        LibraryAction("검색", colors) { accept(LibraryIntent.Search) }
    }
}

@Composable
internal fun LibraryAction(label: String, colors: LibraryColors, click: () -> Unit) {
    BasicText(
        label,
        Modifier.background(colors.accent).clickable(onClick = click).padding(14.dp),
        style = bodyStyle(colors),
    )
}

@Composable
internal fun LibraryMessage(value: String, colors: LibraryColors) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(value, style = hintStyle(colors))
    }
}

internal data class LibraryColors(
    val background: Color,
    val surface: Color,
    val divider: Color,
    val accent: Color,
    val text: Color,
    val hint: Color,
)

private fun libraryColors(dark: Boolean): LibraryColors = if (dark) {
    LibraryColors(Color(0xFF0E0E0E), Color(0xFF262626), Color(0xFF303030), Color(0xFF3977F6), Color.White, Color(0xFF9B9B9B))
} else {
    LibraryColors(Color(0xFFF7F7F7), Color.White, Color(0xFFE0E0E0), Color(0xFF2866E8), Color(0xFF161616), Color(0xFF666666))
}

internal fun titleStyle(colors: LibraryColors) = TextStyle(colors.text, fontSize = 28.sp)
internal fun sectionStyle(colors: LibraryColors) = TextStyle(colors.text, fontSize = 20.sp)
internal fun bodyStyle(colors: LibraryColors) = TextStyle(colors.text, fontSize = 16.sp)
internal fun hintStyle(colors: LibraryColors) = TextStyle(colors.hint, fontSize = 14.sp)
