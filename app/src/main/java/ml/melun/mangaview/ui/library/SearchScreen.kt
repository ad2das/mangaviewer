package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.source.SearchField
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceSeries
import kotlinx.coroutines.flow.distinctUntilChanged
import ml.melun.mangaview.app.SearchMode

private val SearchResultCardShape = RoundedCornerShape(18.dp)
private val SearchResultThumbShape = RoundedCornerShape(12.dp)
private val SearchResultBadgeShape = RoundedCornerShape(6.dp)

@Composable
internal fun SearchScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val searchAccept: (LibraryIntent) -> Unit = { intent ->
        if (intent == LibraryIntent.Search) { keyboard?.hide(); focus.clearFocus() }
        accept(intent)
    }
    val scroll = rememberSaveable(state.searchRevision, saver = LazyListState.Saver) { LazyListState() }
    Column(Modifier.fillMaxSize()) {
        SearchControls(state, colors, searchAccept)
        Spacer(Modifier.height(8.dp))
        when (val content = state.searchContent) {
            LibraryContent.Empty -> SearchEmpty(state, colors, searchAccept)
            LibraryContent.Loading -> SearchLoading(colors)
            is LibraryContent.Failure -> SearchFailure(content.message, colors) { searchAccept(LibraryIntent.Search) }
            is LibraryContent.Series -> if (content.items.isEmpty() && content.nextCursor == null && content.nextFailure == null) {
                SearchNoResults(state.submittedQuery, colors)
            } else {
                val favoriteIds = remember(state.saved.favorites) {
                    state.saved.favorites.mapTo(hashSetOf()) { it.id }
                }
                SearchSeriesList(
                    content,
                    favoriteIds,
                    artworkLoader,
                    colors,
                    searchAccept,
                    scroll,
                    state.submittedQuery,
                )
            }
            is LibraryContent.Episodes -> Unit
        }
    }
}

@Composable
private fun SearchControls(state: LibraryState, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    val query = state.query
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var requestedFocus by rememberSaveable { mutableStateOf(false) }
    // Re-entering a query-less search tab should open the keyboard; returning to an existing
    // result set must not cover it with the IME again.
    LaunchedEffect(Unit) {
        if (query.isEmpty() && !requestedFocus) {
            requestedFocus = true
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .shadow(3.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.06f))
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.card)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryIconView(LibraryIcon.SEARCH, colors.secondary, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                SearchQueryField(query, colors, focusRequester, Modifier.weight(1f), accept)
            }
            LibraryAction("검색", colors, Modifier.widthIn(min = 78.dp).fillMaxHeight()
                .semantics { contentDescription = "검색 실행" }, enabled = query.isNotBlank()) {
                accept(LibraryIntent.Search)
            }
        }
        Spacer(Modifier.height(10.dp))
        SearchFilters(state, colors, accept)
    }
}

@Composable
private fun SearchFilterOption(label: String, description: String, selected: Boolean, colors: LibraryColors, click: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentSurface else Color.Transparent)
            .semantics { contentDescription = "$description: $label" }
            .selectable(selected = selected, role = Role.RadioButton, onClick = click)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = labelStyle(colors, selected).copy(fontSize = 13.sp))
    }
}

@Composable
private fun SearchEmpty(state: LibraryState, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.size(80.dp)
                .shadow(8.dp, CircleShape, spotColor = colors.accent.copy(alpha = 0.30f))
                .clip(CircleShape)
                .background(colors.mutedSurface),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.SEARCH, colors.accent, Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        BasicText(
            "다음으로 읽을 작품을 찾아보세요",
            style = titleStyle(colors, 17).copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            "검색할 사이트와 범위를 선택하고 검색어를 입력하세요",
            style = hintStyle(colors, 13).copy(textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(32.dp))
        if (state.saved.settings.recentQueries.isNotEmpty()) {
            RecentSearchKeywords(state.saved.settings.recentQueries, colors, accept)
            Spacer(Modifier.height(26.dp))
        }
        PopularSearchKeywords(colors, accept)
    }
}

@Composable
private fun RecentSearchKeywords(queries: List<String>, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(colors.accent))
            Spacer(Modifier.width(6.dp))
            BasicText(
                "최근 검색어",
                Modifier.weight(1f),
                style = labelStyle(colors, false).copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
            )
            Box(
                Modifier.heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .semantics { contentDescription = "최근 검색어 전체 삭제" }
                    .clickable { accept(LibraryIntent.ClearSearchHistory) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("전체 삭제", style = hintStyle(colors, 12))
            }
        }
        Spacer(Modifier.height(10.dp))
        queries.take(6).forEach { keyword ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.card)
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                        .clickable {
                            accept(LibraryIntent.QueryChanged(keyword))
                            accept(LibraryIntent.Search)
                        }
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    BasicText(keyword, style = bodyStyle(colors, 13).copy(fontWeight = FontWeight.Medium))
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(48.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "$keyword 삭제" }
                        .clickable { accept(LibraryIntent.RemoveSearchHistory(keyword)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(colors.mutedSurface),
                        contentAlignment = Alignment.Center,
                    ) {
                        LibraryIconView(LibraryIcon.CLOSE, colors.secondary, Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchNoResults(query: String, colors: LibraryColors) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.size(80.dp)
                .shadow(8.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.10f))
                .clip(CircleShape)
                .background(colors.mutedSurface),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.SEARCH, colors.muted, Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        BasicText(
            "검색 결과가 없습니다",
            style = titleStyle(colors, 17).copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            "\"$query\"에 해당하는 작품을 찾지 못했어요. 다른 검색어를 입력해 보세요",
            style = hintStyle(colors, 13).copy(textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun SearchFailure(message: String, colors: LibraryColors, retry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.size(80.dp)
                .shadow(8.dp, CircleShape, spotColor = colors.accent.copy(alpha = 0.30f))
                .clip(CircleShape)
                .background(colors.mutedSurface),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.REFRESH, colors.accent, Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        BasicText(
            "검색에 실패했습니다",
            style = titleStyle(colors, 17).copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(message, style = hintStyle(colors, 13).copy(textAlign = TextAlign.Center))
        Spacer(Modifier.height(24.dp))
        LibraryAction("다시 시도", colors) { retry() }
    }
}

@Composable
private fun SearchSeriesList(
    content: LibraryContent.Series,
    favorites: Set<SeriesId>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
    list: LazyListState,
    query: String,
) {
    LaunchedEffect(list, content.items.size, content.nextCursor, content.loadingNext, content.nextFailure) {
        if (content.loadingNext || content.nextFailure != null || content.nextCursor == null) return@LaunchedEffect
        snapshotFlow {
            val layout = list.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 4
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) accept(LibraryIntent.LoadMoreSearch)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SearchResultsHeader(query, content, colors) }
        items(
            content.items,
            key = { "${it.id.sourceId.value}:${it.id.remoteKey}" },
            contentType = { "search-series" },
        ) { series ->
            SearchSeriesCard(series, series.id in favorites, loader, colors, accept, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
        }
        item(key = "search-status") {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val message = when {
                    content.nextFailure != null -> content.nextFailure
                    content.loadingNext -> "다음 결과를 불러오는 중…"
                    content.nextCursor != null -> "${content.items.size}개 불러옴"
                    else -> "결과 끝 · ${content.items.size}개"
                }
                BasicText(message, style = hintStyle(colors, 13).copy(fontWeight = FontWeight.Medium))
                if (!content.loadingNext && (content.nextFailure != null || content.nextCursor != null)) {
                    Spacer(Modifier.height(10.dp))
                    LibraryAction(if (content.nextFailure != null) "다시 시도" else "더 보기", colors) {
                        accept(LibraryIntent.LoadMoreSearch)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultsHeader(query: String, content: LibraryContent.Series, colors: LibraryColors) {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("‘$query’ 검색 결과", Modifier.weight(1f), style = titleStyle(colors, 14),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        BasicText(if (content.nextCursor != null || content.loadingNext) "${content.items.size}개 불러옴" else "${content.items.size}개",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = hintStyle(colors, 12))
    }
}

@Composable
private fun SearchSeriesCard(
    series: SourceSeries,
    favorite: Boolean,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 116.dp)
            .clip(SearchResultCardShape)
            .background(colors.card)
            .border(1.dp, colors.cardBorder, SearchResultCardShape)
            .clickable { accept(LibraryIntent.SeriesSelected(series)) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 72.dp, height = 96.dp)
                .clip(SearchResultThumbShape)
                .border(0.5.dp, colors.cardBorder, SearchResultThumbShape),
        ) {
            SeriesArtwork(series, loader, colors, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        SearchSeriesDescription(series, colors, Modifier.weight(1f))
        Box(
            Modifier.size(48.dp)
                .semantics { contentDescription = "${series.title} " + if (favorite) "좋아요 해제" else "좋아요" }
                .clip(CircleShape)
                .clickable { accept(LibraryIntent.FavoriteToggled(series)) },
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(
                LibraryIcon.HEART,
                if (favorite) colors.favoriteActive else colors.muted,
                Modifier.size(20.dp),
            )
        }
    }
}
@Composable
private fun SearchFilters(state: LibraryState, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    val source = state.sources.firstOrNull { it.id == state.selectedSourceId }
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) {
            if (source?.distinguishesKinds != false) {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(null to "전체", SeriesKind.WEBTOON to "웹툰", SeriesKind.COMIC to "만화").forEach { (kind, label) ->
                        SearchFilterOption(label, "검색 범위", state.searchKind == kind, colors) {
                            accept(LibraryIntent.SearchKindSelected(kind))
                        }
                    }
                }
            }
            if (source?.searchMode == SearchMode.FIELDS) {
                Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(18.dp).background(colors.outline))
                Row(Modifier.selectableGroup()) {
                    listOf(SearchField.TITLE to "제목", SearchField.AUTHOR to "작가").forEach { (field, label) ->
                        SearchFilterOption(label, "검색 항목", state.searchField == field, colors) {
                            accept(LibraryIntent.SearchFieldSelected(field))
                        }
                    }
                }
            }
        }
        if (source?.searchMode != SearchMode.FIELDS) {
            BasicText(
                if (source?.searchMode == SearchMode.COMBINED) "사이트 통합 검색 · 제목, 작가 등" else "작품 제목으로 검색",
                Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp), style = hintStyle(colors, 12),
            )
        }
    }
}

@Composable
private fun PopularSearchKeywords(colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BasicText("추천 검색어", style = labelStyle(colors).copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        listOf("나 혼자만 레벨업", "전지적 독자 시점", "화산귀환", "원피스", "생존", "주술회전").chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { keyword ->
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .background(colors.card).border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                accept(LibraryIntent.QueryChanged(keyword))
                                accept(LibraryIntent.Search)
                            }.padding(horizontal = 12.dp, vertical = 13.dp),
                    ) {
                        BasicText(keyword, style = bodyStyle(colors, 12), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SearchSeriesDescription(series: SourceSeries, colors: LibraryColors, modifier: Modifier) {
    Column(modifier) {
        BasicText(
            series.title,
            style = titleStyle(colors, 15).copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            series.subtitle.orEmpty().ifEmpty { "작품 정보 보기" },
            style = hintStyle(colors, 12),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(SearchResultBadgeShape)
                    .background(colors.accentSurface)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                BasicText(
                    if (series.id.remoteKey.startsWith("/manhwa/") || series.id.remoteKey.startsWith("comic:")) "만화" else "웹툰",
                    style = badgeStyle(colors, 10).copy(fontWeight = FontWeight.Bold),
                )
            }
            series.status?.let { status ->
                Spacer(Modifier.width(6.dp))
                SeriesStatusBadge(status, colors)
            }
        }
    }
}

@Composable
private fun SearchQueryField(query: String, colors: LibraryColors, focusRequester: FocusRequester, modifier: Modifier, accept: (LibraryIntent) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = { accept(LibraryIntent.QueryChanged(it)) },
        modifier = modifier.heightIn(min = 44.dp).wrapContentHeight().focusRequester(focusRequester)
            .semantics { contentDescription = "작품 검색어" },
        singleLine = true,
        textStyle = bodyStyle(colors, 15),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { accept(LibraryIntent.Search) }),
        decorationBox = { field ->
            Box {
                if (query.isEmpty()) {
                    BasicText("전체 검색", style = hintStyle(colors, 15))
                }
                field()
            }
        },
    )
    if (query.isNotEmpty()) {
        Box(
            Modifier.size(48.dp)
                .semantics { contentDescription = "검색어 지우기" }
                .clip(CircleShape)
                .clickable { accept(LibraryIntent.QueryChanged("")) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(colors.mutedSurface),
                contentAlignment = Alignment.Center,
            ) {
                LibraryIconView(LibraryIcon.CLOSE, colors.secondary, Modifier.size(10.dp))
            }
        }
    }
}
