package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.data.library.RecentReading
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
        Spacer(Modifier.height(10.dp))
        SavedTabs(state.libraryTab, colors, accept)
        val count = savedCount(state)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(colors.accentGradient))
                Spacer(Modifier.width(6.dp))
                BasicText("${count}개 작품", style = labelStyle(colors, false).copy(fontWeight = FontWeight.Bold))
            }
            BasicText("최근 업데이트순", style = hintStyle(colors, 12))
        }
        when (state.libraryTab) {
            SavedTab.ALL -> AllSaved(state, query, artworkLoader, colors, accept)
            SavedTab.RECENT -> RecentSaved(state.saved.recent, query, artworkLoader, colors, accept)
            SavedTab.FAVORITES -> FavoriteSaved(state.saved.favorites, query, artworkLoader, colors, accept)
            SavedTab.OFFLINE -> OfflineSaved(state, query, artworkLoader, colors, accept)
        }
    }
}

@Composable
private fun SavedSearch(query: String, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(50.dp)
                .shadow(3.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.05f))
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryIconView(LibraryIcon.SEARCH, colors.secondary, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { accept(LibraryIntent.QueryChanged(it)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = bodyStyle(colors, 15),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) BasicText("보관함에서 검색", style = hintStyle(colors, 15))
                        field()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(colors.mutedSurface)
                        .clickable { accept(LibraryIntent.QueryChanged("")) },
                    contentAlignment = Alignment.Center,
                ) {
                    LibraryIconView(LibraryIcon.CLOSE, colors.secondary, Modifier.size(10.dp))
                }
            }
        }
        LibraryAction("검색", colors, Modifier.width(76.dp).height(50.dp)) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
}

@Composable
private fun SavedTabs(selected: SavedTab, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp)
            .shadow(2.dp, RoundedCornerShape(15.dp), spotColor = Color.Black.copy(alpha = 0.04f))
            .clip(RoundedCornerShape(15.dp))
            .background(colors.mutedSurface)
            .padding(3.dp),
    ) {
        SavedTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) colors.card else Color.Transparent)
                    .then(if (active) Modifier.shadow(3.dp, RoundedCornerShape(12.dp), spotColor = Color.Black.copy(alpha = 0.10f)) else Modifier)
                    .clickable { accept(LibraryIntent.SavedTabSelected(tab)) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    tab.label,
                    style = bodyStyle(colors, 13).copy(
                        color = if (active) colors.text else colors.secondary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
private fun AllSaved(
    state: LibraryState,
    query: String,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val recentIds = state.saved.recent.map { it.series.id }.toSet()
    val favoriteIds = state.saved.favorites.map { it.id }.toSet()
    val combined = state.saved.favorites +
        state.saved.recent.filterNot { it.series.id in favoriteIds }.map { it.series } +
        state.offlineEpisodes.map { it.series }.distinctBy { it.id }
            .filterNot { it.id in recentIds || it.id in favoriteIds }
            .map { item -> SavedSeries(item.id, item.title, item.thumbnailKey, false, 0L) }
    FavoriteSaved(combined, query, loader, colors, accept, "최근 읽거나 보관하거나 저장한 작품이 없습니다", SavedTab.ALL)
}

@Composable
private fun RecentSaved(
    items: List<RecentReading>,
    query: String,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val filtered = items.filter { query.isEmpty() || it.series.title.contains(query, true) }
    if (filtered.isEmpty()) {
        EmptySaved("최근 읽은 작품이 없습니다", colors) { accept(LibraryIntent.DestinationSelected(MainDestination.HOME)) }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(filtered, key = { "${it.series.id.sourceId.value}:${it.series.id.remoteKey}" }) { item ->
            val sourceSeries = SourceSeries(item.series.id, item.series.title, thumbnailKey = item.series.thumbnailKey)
            SavedSourceSeriesCard(
                series = sourceSeries,
                subtitle = "이어보기 위치 저장됨",
                badge = "이어보기 ›",
                loader = loader,
                colors = colors,
                removalTab = SavedTab.RECENT,
                accept = accept,
                click = {
                    accept(LibraryIntent.SavedEpisodeSelected(ReadingPosition(item.pageId, item.offsetInPageUnits)))
                },
            )
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
    removalTab: SavedTab = SavedTab.FAVORITES,
) {
    val filtered = items.filter { query.isEmpty() || it.title.contains(query, true) }
    if (filtered.isEmpty()) {
        EmptySaved(empty, colors) { accept(LibraryIntent.DestinationSelected(MainDestination.HOME)) }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(filtered, key = { "${it.id.sourceId.value}:${it.id.remoteKey}" }) { item ->
            val series = SourceSeries(item.id, item.title, thumbnailKey = item.thumbnailKey)
            SavedSourceSeriesCard(
                series = series,
                subtitle = if (item.id.sourceId.value == "ntk") "만화" else "웹툰",
                badge = null,
                loader = loader,
                colors = colors,
                removalTab = removalTab,
                accept = accept,
                click = { accept(LibraryIntent.SavedSeriesSelected(item)) },
            )
        }
    }
}

@Composable
private fun OfflineSaved(
    state: LibraryState,
    query: String,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val series = state.offlineEpisodes.map { it.series }.distinctBy { it.id }
        .filter { query.isEmpty() || it.title.contains(query, true) }
    if (series.isEmpty()) {
        EmptySaved("오프라인 저장된 작품이 없습니다", colors) { accept(LibraryIntent.DestinationSelected(MainDestination.HOME)) }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(series, key = { "${it.id.sourceId.value}:${it.id.remoteKey}" }) { item ->
            val count = state.offlineEpisodes.count { it.series.id == item.id }
            SavedSourceSeriesCard(
                series = item,
                subtitle = "${count}개 회차 오프라인 저장",
                badge = "저장완료",
                loader = loader,
                colors = colors,
                removalTab = SavedTab.OFFLINE,
                accept = accept,
                click = { accept(LibraryIntent.OfflineSeriesSelected(item)) },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SavedSourceSeriesCard(
    series: SourceSeries,
    subtitle: String,
    badge: String?,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    removalTab: SavedTab,
    accept: (LibraryIntent) -> Unit,
    click: () -> Unit,
) {
    var removing by remember(series.id) { mutableStateOf(false) }
    if (removing) {
        SavedItemRemovalDialog(
            item = SavedItemRemoval(series, removalTab),
            colors = colors,
            dismiss = { removing = false },
            confirm = {
                removing = false
                accept(LibraryIntent.RemoveSavedItem(SavedItemRemoval(series, removalTab)))
            },
        )
    }
    Row(
        Modifier.fillMaxWidth()
            .height(110.dp)
            .shadow(4.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.08f))
            .clip(RoundedCornerShape(18.dp))
            .background(colors.card)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = click,
                onLongClickLabel = "삭제",
                onLongClick = { removing = true },
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(74.dp).fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp)),
        ) {
            SeriesArtwork(series, loader, colors, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                series.title,
                style = titleStyle(colors, 15).copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            BasicText(subtitle, style = hintStyle(colors, 12), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(colors.accentSurface)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    BasicText(badge, style = labelStyle(colors, true).copy(fontSize = 11.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
        BasicText("›", style = hintStyle(colors, 18).copy(fontWeight = FontWeight.Light))
    }
}

@Composable
private fun EmptySaved(message: String, colors: LibraryColors, onExplore: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Box(
            Modifier.size(80.dp)
                .shadow(8.dp, CircleShape, spotColor = colors.accent.copy(alpha = 0.30f))
                .clip(CircleShape)
                .background(colors.mutedSurface),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.LIBRARY, colors.accent, Modifier.size(38.dp))
        }
        Spacer(Modifier.height(20.dp))
        BasicText(message, style = titleStyle(colors, 17).copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        BasicText("홈에서 마음에 드는 작품을 찾아 보관해 보세요", style = hintStyle(colors, 13))
        Spacer(Modifier.height(24.dp))
        LibraryAction("작품 둘러보기", colors) { onExplore() }
    }
}

private fun savedCount(state: LibraryState): Int = when (state.libraryTab) {
    SavedTab.ALL -> state.saved.recent.size + state.saved.favorites.size + state.offlineEpisodes.map { it.series.id }.distinct().size
    SavedTab.RECENT -> state.saved.recent.size
    SavedTab.FAVORITES -> state.saved.favorites.size
    SavedTab.OFFLINE -> state.offlineEpisodes.map { it.series.id }.distinct().size
}