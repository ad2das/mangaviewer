package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.data.library.SavedBookmark
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.source.SourceSeries

internal fun seriesSelectionKey(id: SeriesId): String =
    "series:${id.sourceId.value}:${id.remoteKey}"

internal fun bookmarkSelectionKey(bookmark: SavedBookmark): String =
    "bookmark:${bookmark.pageId.episodeId.seriesId.sourceId.value}:" +
        "${bookmark.pageId.episodeId.seriesId.remoteKey}:${bookmark.pageId.remoteKey}:" +
        "${bookmark.offsetInPageUnits}:${bookmark.createdAtEpochMillis}"

internal fun combinedSavedSeries(state: LibraryState): List<SavedSeries> {
    val recentIds = state.saved.recent.map { it.series.id }.toSet()
    val favoriteIds = state.saved.favorites.map { it.id }.toSet()
    val offlineIds = state.offlineEpisodes.map { it.series.id }.toSet()
    val bookmarksBySeries = state.saved.bookmarks.groupBy { it.pageId.episodeId.seriesId }
    val bookmarkOnlyIds = bookmarksBySeries.keys
        .filterNot { it in recentIds || it in favoriteIds || it in offlineIds }
    return state.saved.favorites +
        state.saved.recent.filterNot { it.series.id in favoriteIds }.map { it.series } +
        state.offlineEpisodes.map { it.series }.distinctBy { it.id }
            .filterNot { it.id in recentIds || it.id in favoriteIds }
            .map { item -> SavedSeries(item.id, item.title, item.thumbnailKey, false, 0L) } +
        bookmarkOnlyIds.map { id ->
            val marks = bookmarksBySeries.getValue(id)
            SavedSeries(id, marks.first().seriesTitle, null, false, marks.maxOf { it.createdAtEpochMillis })
        }
}

private fun savedSelectionKeys(state: LibraryState, query: String): List<String> {
    val matches = { title: String -> query.isEmpty() || title.contains(query, true) }
    return when (state.libraryTab) {
        SavedTab.ALL -> combinedSavedSeries(state).filter { matches(it.title) }.map { seriesSelectionKey(it.id) }
        SavedTab.RECENT -> state.saved.recent.filter { matches(it.series.title) }
            .map { seriesSelectionKey(it.series.id) }
        SavedTab.FAVORITES -> state.saved.favorites.filter { matches(it.title) }.map { seriesSelectionKey(it.id) }
        SavedTab.BOOKMARKS -> state.saved.bookmarks.filter { matches(it.seriesTitle) }
            .map { bookmarkSelectionKey(it) }
        SavedTab.OFFLINE -> state.offlineEpisodes.map { it.series }.distinctBy { it.id }
            .filter { matches(it.title) }.map { seriesSelectionKey(it.id) }
    }
}

internal fun savedSelectionRemoval(state: LibraryState, selection: Set<String>): LibraryIntent.RemoveSelected {
    val items = mutableListOf<SavedItemRemoval>()
    val bookmarks = mutableListOf<SavedBookmark>()
    fun sourceOf(series: SavedSeries) = SourceSeries(series.id, series.title, thumbnailKey = series.thumbnailKey)
    when (state.libraryTab) {
        SavedTab.ALL -> items.addAll(allSelectedRemovals(state, selection))
        SavedTab.RECENT -> state.saved.recent.forEach {
            if (seriesSelectionKey(it.series.id) in selection) {
                items += SavedItemRemoval(sourceOf(it.series), SavedTab.RECENT)
            }
        }
        SavedTab.FAVORITES -> state.saved.favorites.forEach {
            if (seriesSelectionKey(it.id) in selection) {
                items += SavedItemRemoval(sourceOf(it), SavedTab.FAVORITES)
            }
        }
        SavedTab.OFFLINE -> state.offlineEpisodes.map { it.series }.distinctBy { it.id }.forEach {
            if (seriesSelectionKey(it.id) in selection) items += SavedItemRemoval(it, SavedTab.OFFLINE)
        }
        SavedTab.BOOKMARKS -> state.saved.bookmarks.forEach {
            if (bookmarkSelectionKey(it) in selection) bookmarks += it
        }
    }
    return LibraryIntent.RemoveSelected(items, bookmarks)
}

@Composable
internal fun SelectionMark(selected: Boolean, colors: LibraryColors) {
    Box(
        Modifier.size(24.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent)
            .border(1.5.dp, if (selected) colors.accent else colors.muted, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) LibraryIconView(LibraryIcon.CHECK, Color.White, Modifier.size(14.dp))
    }
}

@Composable
internal fun SavedSelectionActions(
    state: LibraryState,
    query: String,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val selection = state.savedSelection
    val keys = savedSelectionKeys(state, query)
    val allSelected = keys.isNotEmpty() && keys.all { it in selection }
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectedCount(selection.size, colors) { accept(LibraryIntent.SavedSelectionCleared) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                    accept(
                        if (allSelected) LibraryIntent.SavedSelectionCleared
                        else LibraryIntent.SavedSelectionReplaced(keys),
                    )
                }.padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                BasicText(
                    if (allSelected) "전체 해제" else "전체 선택",
                    style = hintStyle(colors, 12).copy(color = colors.accent, fontWeight = FontWeight.Bold),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(colors.accentGradient)
                    .clickable { confirming = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                BasicText(
                    "삭제",
                    style = bodyStyle(colors, 13).copy(color = Color.White, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
    if (confirming) {
        SavedSelectionRemovalDialog(
            count = selection.size,
            tab = state.libraryTab,
            colors = colors,
            dismiss = { confirming = false },
            confirm = {
                confirming = false
                accept(savedSelectionRemoval(state, selection))
            },
        )
    }
}

private fun allSelectedRemovals(state: LibraryState, selection: Set<String>): List<SavedItemRemoval> {
    val items = mutableListOf<SavedItemRemoval>()
    fun sourceOf(series: SavedSeries) = SourceSeries(series.id, series.title, thumbnailKey = series.thumbnailKey)
    val recentIds = state.saved.recent.map { it.series.id }.toSet()
    val favoriteIds = state.saved.favorites.map { it.id }.toSet()
    val offlineIds = state.offlineEpisodes.map { it.series.id }.toSet()
    combinedSavedSeries(state).forEach { series ->
        if (seriesSelectionKey(series.id) !in selection) return@forEach
        val source = sourceOf(series)
        if (series.id in recentIds || series.id in favoriteIds) {
            items += SavedItemRemoval(source, SavedTab.ALL)
        }
        if (series.id in offlineIds) items += SavedItemRemoval(source, SavedTab.OFFLINE)
        if (series.id !in recentIds && series.id !in favoriteIds && series.id !in offlineIds) {
            items += SavedItemRemoval(source, SavedTab.BOOKMARKS)
        }
    }
    return items
}

@Composable
private fun SelectedCount(count: Int, colors: LibraryColors, clear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp)
                .semantics { contentDescription = "선택 취소" }
                .clip(CircleShape)
                .background(colors.mutedSurface)
                .clickable { clear() },
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.CLOSE, colors.secondary, Modifier.size(12.dp))
        }
        Spacer(Modifier.width(10.dp))
        BasicText(
            "${count}개 선택",
            style = labelStyle(colors, false).copy(fontWeight = FontWeight.Bold),
        )
    }
}
