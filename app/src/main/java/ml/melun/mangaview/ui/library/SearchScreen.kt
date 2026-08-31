package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SearchField
import ml.melun.mangaview.source.SeriesKind

@Composable
internal fun SearchScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SearchControls(state, colors, accept)
        Spacer(Modifier.height(12.dp))
        when (val content = state.content) {
            LibraryContent.Empty -> SearchEmpty(colors)
            LibraryContent.Loading -> LibraryMessage("작품을 찾는 중…", colors)
            is LibraryContent.Failure -> LibraryMessage(content.message, colors)
            is LibraryContent.Series -> SearchSeriesList(
                content.items,
                state.saved.favorites.mapTo(hashSetOf()) { it.id },
                artworkLoader,
                colors,
                accept,
            )
            is LibraryContent.Episodes -> Unit
        }
    }
}

@Composable
private fun SearchControls(state: LibraryState, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    val query = state.query
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(colors.card)
                    .border(1.dp, colors.outline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryIconView(LibraryIcon.SEARCH, colors.secondary, Modifier.size(25.dp))
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { accept(LibraryIntent.QueryChanged(it)) },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = bodyStyle(colors, 16),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { accept(LibraryIntent.Search) }),
                    decorationBox = { field ->
                        Box {
                            if (query.isEmpty()) BasicText("전체 검색", style = hintStyle(colors, 16))
                            field()
                        }
                    },
                )
            }
            LibraryAction("검색", colors, Modifier.width(80.dp).height(52.dp)) { accept(LibraryIntent.Search) }
        }
        Row(
            Modifier.fillMaxWidth().height(50.dp).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterBox(searchKindLabel(state.searchKind), "검색 범위", colors, Modifier.weight(1f).fillMaxSize()) {
                accept(LibraryIntent.SearchKindSelected(nextSearchKind(state.searchKind)))
            }
            FilterBox(searchFieldLabel(state.searchField), "검색 항목", colors, Modifier.weight(1f).fillMaxSize()) {
                accept(LibraryIntent.SearchFieldSelected(nextSearchField(state.searchField)))
            }
        }
    }
}

@Composable
private fun FilterBox(
    label: String,
    description: String,
    colors: LibraryColors,
    modifier: Modifier,
    click: () -> Unit,
) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(colors.card).clickable(onClick = click)
            .semantics { contentDescription = "$description: $label" }
            .border(1.dp, colors.outline, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(label, style = bodyStyle(colors, 14))
    }
}

private fun searchKindLabel(kind: SeriesKind?): String = when (kind) {
    null -> "전체"
    SeriesKind.COMIC -> "만화"
    SeriesKind.WEBTOON -> "웹툰"
}

private fun nextSearchKind(kind: SeriesKind?): SeriesKind? = when (kind) {
    null -> SeriesKind.COMIC
    SeriesKind.COMIC -> SeriesKind.WEBTOON
    SeriesKind.WEBTOON -> null
}

private fun searchFieldLabel(field: SearchField): String = when (field) {
    SearchField.TITLE -> "제목"
    SearchField.AUTHOR -> "작가"
}

private fun nextSearchField(field: SearchField): SearchField = when (field) {
    SearchField.TITLE -> SearchField.AUTHOR
    SearchField.AUTHOR -> SearchField.TITLE
}

@Composable
private fun SearchEmpty(colors: LibraryColors) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.card)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp)).padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LibraryIconView(LibraryIcon.SEARCH, colors.muted, Modifier.size(58.dp))
            Spacer(Modifier.height(20.dp))
            BasicText("검색어를 입력하면 작품을 찾아드립니다", style = hintStyle(colors, 15))
        }
    }
}

@Composable
private fun SearchSeriesList(
    series: List<SourceSeries>,
    favorites: Set<SeriesId>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    if (series.isEmpty()) return LibraryMessage("검색 결과가 없습니다", colors)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        items(series, key = { "${it.id.sourceId.value}:${it.id.remoteKey}" }) { item ->
            SearchSeriesRow(item, item.id in favorites, loader, colors, accept)
        }
    }
}

@Composable
private fun SearchSeriesRow(
    series: SourceSeries,
    favorite: Boolean,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).height(104.dp).clip(RoundedCornerShape(14.dp))
            .background(colors.card).border(1.dp, colors.outline, RoundedCornerShape(14.dp))
            .clickable { accept(LibraryIntent.SeriesSelected(series)) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesArtwork(series, loader, colors, Modifier.width(66.dp).height(84.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            BasicText(series.title, style = bodyStyle(colors, 16), maxLines = 2, overflow = TextOverflow.Ellipsis)
            series.subtitle?.let {
                Spacer(Modifier.height(5.dp))
                BasicText(it, style = hintStyle(colors), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.mutedSurface)
                .clickable { accept(LibraryIntent.FavoriteToggled(series)) },
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.HEART, if (favorite) androidx.compose.ui.graphics.Color(0xFFEC4899) else colors.secondary, Modifier.size(26.dp))
        }
    }
}
