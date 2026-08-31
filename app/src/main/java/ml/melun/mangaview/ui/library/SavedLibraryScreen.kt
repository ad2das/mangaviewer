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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.data.library.RecentReading
import ml.melun.mangaview.data.library.SavedBookmark
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.source.SourceSeries

@Composable
internal fun SavedLibraryScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val query = state.query.trim()
    Column(Modifier.fillMaxSize()) {
        SavedSearch(state.query, colors, accept)
        SavedTabs(state.libraryTab, colors, accept)
        val count = savedCount(state)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("${count}개 작품", style = hintStyle(colors))
            BasicText("최근 업데이트", style = hintStyle(colors))
        }
        when (state.libraryTab) {
            SavedTab.ALL -> AllSaved(state, query, artworkLoader, colors, accept)
            SavedTab.RECENT -> RecentSaved(state.saved.recent, query, artworkLoader, colors, accept)
            SavedTab.FAVORITES -> FavoriteSaved(state.saved.favorites, query, artworkLoader, colors, accept)
            SavedTab.BOOKMARKS -> BookmarkSaved(state.saved.bookmarks, query, colors, accept)
        }
    }
}

@Composable
private fun SavedSearch(query: String, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(colors.card)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryIconView(LibraryIcon.SEARCH, colors.secondary, Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                query,
                { accept(LibraryIntent.QueryChanged(it)) },
                Modifier.weight(1f),
                singleLine = true,
                textStyle = bodyStyle(colors),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) BasicText("보관함에서 검색", style = hintStyle(colors, 15))
                        field()
                    }
                },
            )
        }
        LibraryAction("검색", colors, Modifier.height(52.dp)) { }
    }
}

@Composable
private fun SavedTabs(selected: SavedTab, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp)) {
        SavedTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                Modifier.weight(1f).fillMaxSize().clickable { accept(LibraryIntent.SavedTabSelected(tab)) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                BasicText(tab.label, style = labelStyle(colors, active))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(56.dp).height(3.dp).background(if (active) colors.accent else androidx.compose.ui.graphics.Color.Transparent))
            }
        }
    }
}

@Composable
private fun AllSaved(state: LibraryState, query: String, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    val recentIds = state.saved.recent.mapTo(hashSetOf()) { it.series.id }
    val combined = state.saved.recent.map { it.series } + state.saved.favorites.filterNot { it.id in recentIds }
    FavoriteSaved(combined, query, loader, colors, accept, "최근 읽거나 보관하거나 저장한 작품이 없습니다")
}

@Composable
private fun RecentSaved(items: List<RecentReading>, query: String, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    val filtered = items.filter { query.isEmpty() || it.series.title.contains(query, true) }
    if (filtered.isEmpty()) return SavedEmpty("최근 읽은 작품이 없습니다", colors)
    LazyColumn(Modifier.fillMaxSize()) {
        items(filtered, key = { "${it.series.id.sourceId.value}:${it.series.id.remoteKey}" }) { item ->
            SavedSeriesRow(item.series, item.episodeId.remoteKey, loader, colors) {
                accept(LibraryIntent.SavedEpisodeSelected(ReadingPosition(item.pageId, item.offsetInPageUnits)))
            }
        }
    }
}

@Composable
private fun FavoriteSaved(
    items: List<SavedSeries>,
    query: String,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
    empty: String = "좋아요한 작품이 없습니다",
) {
    val filtered = items.filter { query.isEmpty() || it.title.contains(query, true) }
    if (filtered.isEmpty()) return SavedEmpty(empty, colors)
    LazyColumn(Modifier.fillMaxSize()) {
        items(filtered, key = { "${it.id.sourceId.value}:${it.id.remoteKey}" }) { item ->
            SavedSeriesRow(item, item.id.sourceId.value.uppercase(), loader, colors) {
                accept(LibraryIntent.SavedSeriesSelected(item))
            }
        }
    }
}

@Composable
private fun BookmarkSaved(items: List<SavedBookmark>, query: String, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    val filtered = items.filter { query.isEmpty() || it.seriesTitle.contains(query, true) }
    if (filtered.isEmpty()) return SavedEmpty("저장한 책갈피가 없습니다", colors)
    LazyColumn(Modifier.fillMaxSize()) {
        items(filtered, key = { "${it.pageId.episodeId.remoteKey}:${it.pageId.remoteKey}" }) { item ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).height(80.dp)
                    .clip(RoundedCornerShape(14.dp)).background(colors.card)
                    .clickable { accept(LibraryIntent.SavedEpisodeSelected(ReadingPosition(item.pageId, item.offsetInPageUnits))) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryIconView(LibraryIcon.LIBRARY, colors.accent, Modifier.size(32.dp))
                Column(Modifier.padding(start = 14.dp)) {
                    BasicText(item.seriesTitle, style = bodyStyle(colors), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(item.pageId.episodeId.remoteKey, style = hintStyle(colors), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SavedSeriesRow(item: SavedSeries, subtitle: String, loader: SeriesArtworkLoader, colors: LibraryColors, click: () -> Unit) {
    val series = SourceSeries(item.id, item.title, thumbnailKey = item.thumbnailKey)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).height(92.dp)
            .clip(RoundedCornerShape(14.dp)).background(colors.card).clickable(onClick = click).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesArtwork(series, loader, colors, Modifier.width(58.dp).height(72.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.padding(start = 14.dp)) {
            BasicText(item.title, style = bodyStyle(colors, 15), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(subtitle, style = hintStyle(colors), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SavedEmpty(message: String, colors: LibraryColors) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.card)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp)).padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LibraryIconView(LibraryIcon.SEARCH, colors.muted, Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            BasicText(message, style = hintStyle(colors, 14))
        }
    }
}

private fun savedCount(state: LibraryState): Int = when (state.libraryTab) {
    SavedTab.ALL -> (state.saved.recent.map { it.series.id } + state.saved.favorites.map { it.id }).distinct().size
    SavedTab.RECENT -> state.saved.recent.size
    SavedTab.FAVORITES -> state.saved.favorites.size
    SavedTab.BOOKMARKS -> state.saved.bookmarks.size
}
