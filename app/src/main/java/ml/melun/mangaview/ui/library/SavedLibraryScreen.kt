package ml.melun.mangaview.ui.library

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.library.RecentReading
import ml.melun.mangaview.data.library.SavedBookmark
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.source.SourceSeries

private val SavedCardShape = RoundedCornerShape(18.dp)
private val SavedThumbShape = RoundedCornerShape(12.dp)
private val SavedBadgeShape = RoundedCornerShape(6.dp)

@Composable
internal fun SavedLibraryScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val query = state.savedQuery.trim()
    Column(Modifier.fillMaxSize()) {
        SavedSearch(state.savedQuery, colors, accept)
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
        val sourceLabels = state.sources.associate { it.id to it.label }
        when (state.libraryTab) {
            SavedTab.ALL -> AllSaved(state, query, artworkLoader, colors, accept)
            SavedTab.RECENT -> RecentSaved(state.saved.recent, query, artworkLoader, colors, accept, sourceLabels)
            SavedTab.FAVORITES -> FavoriteSaved(state.saved.favorites, query, artworkLoader, colors, accept, sourceLabels)
            SavedTab.BOOKMARKS -> BookmarkSaved(state.saved.bookmarks, query, colors, accept, sourceLabels)
            SavedTab.OFFLINE -> OfflineSaved(state, query, artworkLoader, colors, accept, sourceLabels)
        }
    }
}

@Composable
private fun SavedSearch(query: String, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxWidth().height(50.dp)
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
                onValueChange = { accept(LibraryIntent.SavedQueryChanged(it)) },
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
                    Modifier.size(48.dp)
                        .semantics { contentDescription = "보관함 검색어 지우기" },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(colors.mutedSurface)
                            .clickable { accept(LibraryIntent.SavedQueryChanged("")) },
                        contentAlignment = Alignment.Center,
                    ) {
                        LibraryIconView(LibraryIcon.CLOSE, colors.secondary, Modifier.size(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedTabs(selected: SavedTab, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp)
            .shadow(2.dp, RoundedCornerShape(15.dp), spotColor = Color.Black.copy(alpha = 0.04f))
            .clip(RoundedCornerShape(15.dp))
            .background(colors.mutedSurface)
            .padding(3.dp),
    ) {
        SavedTab.entries.forEach { tab ->
            val active = tab == selected
            val surface by animateColorAsState(
                targetValue = if (active) colors.card else Color.Transparent,
                label = "savedTabSurface",
            )
            val labelColor by animateColorAsState(
                targetValue = if (active) colors.text else colors.secondary,
                label = "savedTabLabel",
            )
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(surface)
                    .then(if (active) Modifier.shadow(3.dp, RoundedCornerShape(12.dp), spotColor = Color.Black.copy(alpha = 0.10f)) else Modifier)
                    .clickable { accept(LibraryIntent.SavedTabSelected(tab)) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    tab.label,
                    style = bodyStyle(colors, 13).copy(
                        color = labelColor,
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
    val offlineIds = state.offlineEpisodes.map { it.series.id }.toSet()
    val bookmarksBySeries = state.saved.bookmarks.groupBy { it.pageId.episodeId.seriesId }
    val bookmarkOnlyIds = bookmarksBySeries.keys
        .filterNot { it in recentIds || it in favoriteIds || it in offlineIds }
    val combined = state.saved.favorites +
        state.saved.recent.filterNot { it.series.id in favoriteIds }.map { it.series } +
        state.offlineEpisodes.map { it.series }.distinctBy { it.id }
            .filterNot { it.id in recentIds || it.id in favoriteIds }
            .map { item -> SavedSeries(item.id, item.title, item.thumbnailKey, false, 0L) } +
        bookmarkOnlyIds.map { id ->
            val marks = bookmarksBySeries.getValue(id)
            SavedSeries(id, marks.first().seriesTitle, null, false, marks.maxOf { it.createdAtEpochMillis })
        }
    val removalTabs = buildMap {
        state.saved.favorites.forEach { put(it.id, SavedTab.FAVORITES) }
        state.saved.recent.forEach { if (it.series.id !in favoriteIds) put(it.series.id, SavedTab.RECENT) }
        state.offlineEpisodes.forEach { if (it.series.id !in recentIds && it.series.id !in favoriteIds) put(it.series.id, SavedTab.OFFLINE) }
        bookmarkOnlyIds.forEach { put(it, SavedTab.BOOKMARKS) }
    }
    FavoriteSaved(
        combined,
        query,
        loader,
        colors,
        accept,
        state.sources.associate { it.id to it.label },
        "최근 읽거나 보관하거나 저장한 작품이 없습니다",
        SavedTab.ALL,
        removalTabFor = { series -> removalTabs[series.id] ?: SavedTab.ALL },
    )
}

@Composable
private fun RecentSaved(
    items: List<RecentReading>,
    query: String,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
    sourceLabels: Map<SourceId, String>,
) {
    val filtered = items.filter { query.isEmpty() || it.series.title.contains(query, true) }
    if (filtered.isEmpty()) {
        if (query.isNotEmpty()) {
            EmptySaved(
                "검색 결과가 없습니다",
                colors,
                subtitle = "다른 검색어로 다시 찾아보세요",
                action = "검색어 지우기",
            ) { accept(LibraryIntent.SavedQueryChanged("")) }
        } else {
            EmptySaved("최근 읽은 작품이 없습니다", colors) { accept(LibraryIntent.DestinationSelected(MainDestination.HOME)) }
        }
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
                sourceLabels = sourceLabels,
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
    sourceLabels: Map<SourceId, String>,
    empty: String = "좋아요한 작품이 없습니다",
    removalTab: SavedTab = SavedTab.FAVORITES,
    removalTabFor: (SavedSeries) -> SavedTab = { removalTab },
) {
    val filtered = items.filter { query.isEmpty() || it.title.contains(query, true) }
    if (filtered.isEmpty()) {
        if (query.isNotEmpty()) {
            EmptySaved(
                "검색 결과가 없습니다",
                colors,
                subtitle = "다른 검색어로 다시 찾아보세요",
                action = "검색어 지우기",
            ) { accept(LibraryIntent.SavedQueryChanged("")) }
        } else {
            EmptySaved(empty, colors) { accept(LibraryIntent.DestinationSelected(MainDestination.HOME)) }
        }
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
                removalTab = removalTabFor(item),
                accept = accept,
                sourceLabels = sourceLabels,
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
    sourceLabels: Map<SourceId, String>,
) {
    val series = state.offlineEpisodes.map { it.series }.distinctBy { it.id }
        .filter { query.isEmpty() || it.title.contains(query, true) }
    if (series.isEmpty()) {
        if (query.isNotEmpty()) {
            EmptySaved(
                "검색 결과가 없습니다",
                colors,
                subtitle = "다른 검색어로 다시 찾아보세요",
                action = "검색어 지우기",
            ) { accept(LibraryIntent.SavedQueryChanged("")) }
        } else {
            EmptySaved("오프라인 저장된 작품이 없습니다", colors) { accept(LibraryIntent.DestinationSelected(MainDestination.HOME)) }
        }
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
                sourceLabels = sourceLabels,
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
    sourceLabels: Map<SourceId, String>,
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
            .clip(SavedCardShape)
            .background(colors.card)
            .border(1.dp, colors.cardBorder, SavedCardShape)
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
                .clip(SavedThumbShape)
                .border(0.5.dp, colors.cardBorder, SavedThumbShape),
        ) {
            SeriesArtwork(series, loader, colors, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        SavedSeriesDescription(
            series,
            subtitle,
            badge,
            colors,
            Modifier.weight(1f),
            sourceLabels[series.id.sourceId] ?: series.id.sourceId.value.uppercase(),
        )
        BasicText("›", style = hintStyle(colors, 18).copy(fontWeight = FontWeight.Light))
    }
}

@Composable
private fun BookmarkSaved(
    items: List<SavedBookmark>,
    query: String,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
    sourceLabels: Map<SourceId, String>,
) {
    var removing by remember { mutableStateOf<SavedBookmark?>(null) }
    removing?.let { target ->
        BookmarkRemovalDialog(
            bookmark = target,
            colors = colors,
            dismiss = { removing = null },
            confirm = {
                removing = null
                accept(LibraryIntent.RemoveBookmark(target))
            },
        )
    }
    val filtered = items.filter { query.isEmpty() || it.seriesTitle.contains(query, true) }
    if (filtered.isEmpty()) {
        if (query.isNotEmpty()) {
            EmptySaved(
                "검색 결과가 없습니다",
                colors,
                subtitle = "다른 검색어로 다시 찾아보세요",
                action = "검색어 지우기",
            ) { accept(LibraryIntent.SavedQueryChanged("")) }
        } else {
            EmptySaved("저장한 책갈피가 없습니다", colors) { accept(LibraryIntent.DestinationSelected(MainDestination.HOME)) }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            filtered,
            key = { "${it.pageId}@${it.offsetInPageUnits}@${it.createdAtEpochMillis}" },
        ) { item ->
            BookmarkCard(
                bookmark = item,
                colors = colors,
                sourceLabel = sourceLabels[item.pageId.episodeId.seriesId.sourceId]
                    ?: item.pageId.episodeId.seriesId.sourceId.value.uppercase(),
                click = {
                    accept(LibraryIntent.SavedEpisodeSelected(ReadingPosition(item.pageId, item.offsetInPageUnits)))
                },
                longClick = { removing = item },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookmarkCard(
    bookmark: SavedBookmark,
    colors: LibraryColors,
    sourceLabel: String,
    click: () -> Unit,
    longClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .height(96.dp)
            .clip(SavedCardShape)
            .background(colors.card)
            .border(1.dp, colors.cardBorder, SavedCardShape)
            .combinedClickable(
                onClick = click,
                onLongClickLabel = "책갈피 삭제",
                onLongClick = longClick,
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(64.dp).fillMaxHeight()
                .clip(SavedThumbShape)
                .background(colors.accentSurface),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.BOOKMARK, colors.accent, Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    bookmark.seriesTitle,
                    modifier = Modifier.weight(1f, fill = false),
                    style = titleStyle(colors, 15).copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                SourceLabelChip(bookmark.pageId.episodeId.seriesId.sourceId, sourceLabel, colors)
            }
            Spacer(Modifier.height(4.dp))
            BasicText(
                "${libraryDate(bookmark.createdAtEpochMillis)} 저장",
                style = hintStyle(colors, 12),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.clip(SavedBadgeShape)
                    .background(colors.accentSurface)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                BasicText(
                    "책갈피 위치로 이동",
                    style = labelStyle(colors, true).copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

@Composable
private fun BookmarkRemovalDialog(
    bookmark: SavedBookmark,
    colors: LibraryColors,
    dismiss: () -> Unit,
    confirm: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(16.dp)).padding(22.dp)) {
            BasicText("책갈피 삭제", style = titleStyle(colors, 18))
            Spacer(Modifier.height(10.dp))
            BasicText(
                "${bookmark.seriesTitle}\n${libraryDate(bookmark.createdAtEpochMillis)}에 저장한 위치를 삭제합니다.",
                style = bodyStyle(colors, 14),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryAction("취소", colors, Modifier.weight(1f).height(48.dp), dismiss)
                LibraryAction("삭제", colors, Modifier.weight(1f).height(48.dp), confirm)
            }
        }
    }
}

@Composable
private fun EmptySaved(
    message: String,
    colors: LibraryColors,
    subtitle: String = "홈에서 마음에 드는 작품을 찾아 보관해 보세요",
    action: String = "작품 둘러보기",
    onAction: () -> Unit,
) {
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
        BasicText(subtitle, style = hintStyle(colors, 13))
        Spacer(Modifier.height(24.dp))
        LibraryAction(action, colors) { onAction() }
    }
}

private fun savedCount(state: LibraryState): Int = when (state.libraryTab) {
    SavedTab.ALL -> (
        state.saved.recent.map { it.series.id } +
            state.saved.favorites.map { it.id } +
            state.offlineEpisodes.map { it.series.id } +
            state.saved.bookmarks.map { it.pageId.episodeId.seriesId }
        ).distinct().size
    SavedTab.RECENT -> state.saved.recent.size
    SavedTab.FAVORITES -> state.saved.favorites.size
    SavedTab.BOOKMARKS -> state.saved.bookmarks.size
    SavedTab.OFFLINE -> state.offlineEpisodes.map { it.series.id }.distinct().size
}
@Composable
private fun SavedSeriesDescription(
    series: SourceSeries,
    subtitle: String,
    badge: String?,
    colors: LibraryColors,
    modifier: Modifier,
    sourceLabel: String,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                series.title,
                modifier = Modifier.weight(1f, fill = false),
                style = titleStyle(colors, 15).copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            SourceLabelChip(series.id.sourceId, sourceLabel, colors)
        }
        Spacer(Modifier.height(4.dp))
        BasicText(subtitle, style = hintStyle(colors, 12), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.clip(SavedBadgeShape)
                    .background(colors.accentSurface)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                BasicText(badge, style = labelStyle(colors, true).copy(fontSize = 11.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun SourceLabelChip(sourceId: SourceId, label: String, colors: LibraryColors) {
    val tint = sourceTagTint(sourceId)
    val background = tint?.copy(alpha = if (colors.dark) 0.28f else 0.16f) ?: colors.mutedSurface
    val content = tint?.let {
        lerp(it, if (colors.dark) Color.White else Color.Black, if (colors.dark) 0.30f else 0.50f)
    } ?: colors.secondary
    Box(
        Modifier.clip(SavedBadgeShape)
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        BasicText(label, style = hintStyle(colors, 10).copy(color = content, fontWeight = FontWeight.Bold))
    }
}

/** Each provider keeps its site logo color so saved items read apart at a glance. */
private fun sourceTagTint(sourceId: SourceId): Color? = when (sourceId.value) {
    "ntk" -> Color(0xFFD77D1F)
    "wfwf" -> Color(0xFF5974FF)
    "newxtoon" -> Color(0xFFFB1976)
    "goodtoon" -> Color(0xFFE63946)
    else -> null
}
