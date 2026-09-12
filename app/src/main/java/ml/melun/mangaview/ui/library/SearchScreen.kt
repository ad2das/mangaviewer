package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.source.SearchField
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceSeries

@Composable
internal fun SearchScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SearchControls(state, colors, accept)
        Spacer(Modifier.height(8.dp))
        when (val content = state.content) {
            LibraryContent.Empty -> SearchEmpty(colors, accept)
            LibraryContent.Loading -> LibraryMessage("작품을 찾는 중…", colors)
            is LibraryContent.Failure -> SearchFailure(content.message, colors) { accept(LibraryIntent.Search) }
            is LibraryContent.Series -> if (content.items.isEmpty()) {
                SearchNoResults(state.query, colors)
            } else {
                SearchSeriesList(
                    content.items,
                    state.saved.favorites.mapTo(hashSetOf()) { it.id },
                    artworkLoader,
                    colors,
                    accept,
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
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
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
                BasicTextField(
                    value = query,
                    onValueChange = { accept(LibraryIntent.QueryChanged(it)) },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = bodyStyle(colors, 15),
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
                        Modifier.size(24.dp).clip(CircleShape).background(colors.mutedSurface)
                            .clickable { accept(LibraryIntent.QueryChanged("")) },
                        contentAlignment = Alignment.Center,
                    ) {
                        LibraryIconView(LibraryIcon.CLOSE, colors.secondary, Modifier.size(10.dp))
                    }
                }
            }
            LibraryAction("검색", colors, Modifier.width(78.dp).fillMaxHeight()) {
                accept(LibraryIntent.Search)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KindFilter(state.searchKind, colors, Modifier.weight(1f), accept)
            FieldFilter(state.searchField, colors, Modifier.weight(1f), accept)
        }
    }
}

@Composable
private fun KindFilter(
    selected: SeriesKind?,
    colors: LibraryColors,
    modifier: Modifier,
    accept: (LibraryIntent) -> Unit,
) {
    val options = listOf(
        null to "전체",
        SeriesKind.WEBTOON to "웹툰",
        SeriesKind.COMIC to "만화",
    )
    val index = options.indexOfFirst { it.first == selected }
    val next = options[(index + 1).mod(options.size)]
    val label = options[index.coerceAtLeast(0)].second
    SearchFilterChip(label, "검색 범위", colors, modifier) {
        accept(LibraryIntent.SearchKindSelected(next.first))
    }
}

@Composable
private fun FieldFilter(
    selected: SearchField,
    colors: LibraryColors,
    modifier: Modifier,
    accept: (LibraryIntent) -> Unit,
) {
    val options = listOf(SearchField.TITLE to "제목", SearchField.AUTHOR to "작가")
    val index = options.indexOfFirst { it.first == selected }
    val next = options[(index + 1).mod(options.size)]
    val label = options[index.coerceAtLeast(0)].second
    SearchFilterChip(label, "검색 항목", colors, modifier) {
        accept(LibraryIntent.SearchFieldSelected(next.first))
    }
}

@Composable
private fun SearchFilterChip(
    label: String,
    description: String,
    colors: LibraryColors,
    modifier: Modifier,
    click: () -> Unit,
) {
    Box(
        modifier.height(42.dp)
            .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = Color.Black.copy(alpha = 0.05f))
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
            .semantics { contentDescription = "$description: $label" }
            .clickable(onClick = click)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, style = labelStyle(colors, true).copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(6.dp))
            BasicText("▾", style = hintStyle(colors, 11))
        }
    }
}

@Composable
private fun SearchEmpty(colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
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
            "검색어를 입력하면 작품을 찾아드립니다",
            style = titleStyle(colors, 17).copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            "원하는 웹툰 또는 만화 제목이나 작가를 입력해 보세요",
            style = hintStyle(colors, 13),
        )
        Spacer(Modifier.height(32.dp))
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(colors.accent))
                Spacer(Modifier.width(6.dp))
                BasicText(
                    "인기 추천 검색어",
                    style = labelStyle(colors, false).copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                )
            }
            Spacer(Modifier.height(14.dp))
            val popularKeywords = listOf("나 혼자만 레벨업", "전지적 독자 시점", "화산귀환", "원피스", "귀멸의 칼날", "주술회전")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                popularKeywords.take(3).forEach { kw ->
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(colors.card)
                            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                accept(LibraryIntent.QueryChanged(kw))
                                accept(LibraryIntent.Search)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        BasicText(kw, style = bodyStyle(colors, 12).copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                popularKeywords.drop(3).forEach { kw ->
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(colors.card)
                            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                accept(LibraryIntent.QueryChanged(kw))
                                accept(LibraryIntent.Search)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        BasicText(kw, style = bodyStyle(colors, 12).copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchNoResults(query: String, colors: LibraryColors) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
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
            style = hintStyle(colors, 13),
        )
    }
}

@Composable
private fun SearchFailure(message: String, colors: LibraryColors, retry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
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
        BasicText(message, style = hintStyle(colors, 13))
        Spacer(Modifier.height(24.dp))
        LibraryAction("다시 시도", colors) { retry() }
    }
}

@Composable
private fun SearchSeriesList(
    items: List<SourceSeries>,
    favorites: Set<SeriesId>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "검색 결과",
                    style = titleStyle(colors, 14).copy(fontWeight = FontWeight.ExtraBold),
                )
                Spacer(Modifier.width(6.dp))
                BasicText("${items.size}개", style = hintStyle(colors, 12))
            }
        }
        items(items, key = { it.id.remoteKey }) { series ->
            SearchSeriesCard(series, series.id in favorites, loader, colors, accept)
        }
    }
}

@Composable
private fun SearchSeriesCard(
    series: SourceSeries,
    favorite: Boolean,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .height(112.dp)
            .shadow(4.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.08f))
            .clip(RoundedCornerShape(18.dp))
            .background(colors.card)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp))
            .clickable { accept(LibraryIntent.SeriesSelected(series)) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(76.dp).fillMaxHeight()
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
            BasicText(
                series.subtitle.orEmpty().ifEmpty { "연재작" },
                style = hintStyle(colors, 12),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(colors.accentSurface)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    BasicText(
                        if (series.id.sourceId.value == "ntk") "만화" else "웹툰",
                        style = badgeStyle(colors, 10).copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
        Box(
            Modifier.size(42.dp)
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