package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourceSeries

@Composable
internal fun HomeScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { HomeHeading(colors) }
        item { HomeContinuations(state.saved.recent, artworkLoader, colors, accept) }
        item { KindSelector(state.homeKind, colors, accept) }
        item { Spacer(Modifier.height(16.dp)) }
        item { HomeTabs(state.homeTab, colors, accept) }
        item { Spacer(Modifier.height(12.dp)) }
        if (state.homeTab == HomeTab.GENRES) {
            genreRows(state, colors, accept)
        } else {
            when (val home = state.home) {
                HomeContent.Loading -> item { HomeLoading(colors) }
                is HomeContent.Failure -> item { HomeFailure(home.message, colors, accept) }
                is HomeContent.Ready -> when (state.homeTab) {
                    HomeTab.HOME -> homeRows(home, artworkLoader, colors, accept)
                    HomeTab.POPULAR -> seriesGrid(home.popular, artworkLoader, colors, accept)
                    HomeTab.NEW -> seriesGrid(home.new, artworkLoader, colors, accept)
                    HomeTab.GENRES -> Unit
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.homeRows(
    home: HomeContent.Ready,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val heroes = (home.popular.take(5).ifEmpty { home.latest.take(5) }).ifEmpty { home.new.take(5) }
    if (heroes.isNotEmpty()) {
        item { HeroCarousel(heroes, loader, colors, accept) }
    }
    if (home.popular.isNotEmpty()) {
        item {
            SectionHeader("이번 주 인기 TOP", "전체보기", colors) {
                accept(LibraryIntent.HomeTabSelected(HomeTab.POPULAR))
            }
        }
        item { RankedRow(home.popular.take(10), loader, colors, accept) }
    }
    if (home.latest.isNotEmpty()) {
        item {
            SectionHeader("최신 업데이트", "${home.latest.size}개", colors, null)
        }
        item { CoverRow(home.latest.take(12), loader, colors, accept) }
    }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.seriesGrid(
    series: List<SourceSeries>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    if (series.isEmpty()) {
        item { HomeFailure("표시할 작품이 없습니다", colors, accept) }
        return
    }
    items(series.chunked(2), key = { row -> row.joinToString("|") { it.id.remoteKey } }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEach { item -> SeriesGridCard(item, loader, colors, Modifier.weight(1f), accept) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.genreRows(
    state: LibraryState,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    item {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 12.dp)) {
            BasicText("장르 둘러보기", style = titleStyle(colors, 21))
            Spacer(Modifier.height(4.dp))
            BasicText("원하는 테마와 장르로 작품을 찾아보세요", style = hintStyle(colors, 13))
            Spacer(Modifier.height(14.dp))
            BasicText("장르별", style = bodyStyle(colors, 14).copy(fontWeight = FontWeight.Bold))
        }
    }
    when (val genres = state.genres) {
        GenreContent.Empty, GenreContent.Loading -> item { GenreMessage("장르를 불러오는 중…", colors) }
        is GenreContent.Failure -> item { GenreMessage(genres.message, colors) }
        is GenreContent.Ready -> items(genres.items.chunked(3), key = { row -> row.joinToString("|") { it.key } }) { row ->
            GenreRow(row, colors, accept)
        }
    }
}

@Composable
private fun GenreRow(
    row: List<SourceGenre>,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        row.forEach { genre ->
            Box(
                Modifier.weight(1f).height(46.dp)
                    .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = Color.Black.copy(alpha = 0.05f))
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.card)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                    .clickable { accept(LibraryIntent.GenreSelected(genre)) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    genre.label,
                    style = bodyStyle(colors, 13).copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun GenreMessage(message: String, colors: LibraryColors) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(message, style = hintStyle(colors, 14))
    }
}

@Composable
private fun HomeHeading(colors: LibraryColors) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(colors.accentSurface)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                BasicText(
                    "PREMIUM VIEWER",
                    style = badgeStyle(colors, 10).copy(fontWeight = FontWeight.ExtraBold),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicText("읽던 작품으로 바로 이동", style = displayStyle(colors, 23))
        Spacer(Modifier.height(5.dp))
        BasicText(
            "최근 기록, 실시간 인기 랭킹, 최신 연재작을 감상해보세요.",
            style = hintStyle(colors, 13),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun KindSelector(selected: SeriesKind, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(52.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.06f))
            .clip(RoundedCornerShape(16.dp))
            .background(colors.mutedSurface)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
            .padding(4.dp),
    ) {
        KindButton("웹툰", SeriesKind.WEBTOON, selected, colors, accept)
        KindButton("만화", SeriesKind.COMIC, selected, colors, accept)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KindButton(
    label: String,
    kind: SeriesKind,
    selected: SeriesKind,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val active = kind == selected
    Box(
        Modifier.weight(1f).fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) colors.accentGradient else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
            .then(if (active) Modifier.shadow(4.dp, RoundedCornerShape(12.dp), spotColor = colors.accent.copy(alpha = 0.35f)) else Modifier)
            .clickable { accept(LibraryIntent.HomeKindSelected(kind)) },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = bodyStyle(colors, 14).copy(
                color = if (active) Color.White else colors.secondary,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun HomeTabs(selected: HomeTab, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        HomeTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { accept(LibraryIntent.HomeTabSelected(tab)) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText(
                    tab.label,
                    style = bodyStyle(colors, 14).copy(
                        color = if (active) colors.accent else colors.secondary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.width(if (active) 32.dp else 0.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(if (active) colors.accentGradient else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))),
                )
            }
        }
    }
}

@Composable
private fun HeroCarousel(
    items: List<SourceSeries>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(280.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
        ) { page ->
            val series = items[page]
            Box(
                Modifier.fillMaxSize()
                    .shadow(8.dp, RoundedCornerShape(22.dp), spotColor = Color.Black.copy(alpha = 0.22f))
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(22.dp))
                    .clickable { accept(LibraryIntent.SeriesSelected(series)) },
            ) {
                SeriesArtwork(series, loader, colors, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(colors.heroOverlayGradient))
                Column(
                    Modifier.matchParentSize().padding(20.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(colors.accentSurface)
                                .border(0.5.dp, colors.cardBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            BasicText(
                                if (series.id.sourceId.value == "ntk") "만화" else "웹툰",
                                style = badgeStyle(colors, 10).copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        series.title,
                        style = titleStyle(colors, 22).copy(color = Color.White, fontWeight = FontWeight.Black),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    series.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                        Spacer(Modifier.height(4.dp))
                        BasicText(
                            subtitle,
                            style = bodyStyle(colors, 13).copy(color = Color.White.copy(alpha = 0.85f)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier.height(40.dp)
                            .shadow(4.dp, RoundedCornerShape(20.dp), spotColor = colors.accent.copy(alpha = 0.35f))
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.accentGradient)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LibraryIconView(LibraryIcon.PLAY, Color.White, Modifier.size(12.dp))
                            Spacer(Modifier.width(6.dp))
                            BasicText(
                                "보러가기",
                                style = bodyStyle(colors, 13).copy(color = Color.White, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
        if (items.size > 1) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().height(14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(items.size) { index ->
                    val active = pagerState.currentPage == index
                    Box(
                        Modifier.padding(horizontal = 3.dp)
                            .size(width = if (active) 22.dp else 6.dp, height = 6.dp)
                            .clip(CircleShape)
                            .background(if (active) colors.accent else colors.muted.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, colors: LibraryColors, click: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(colors.accentGradient))
            Spacer(Modifier.width(8.dp))
            BasicText(title, style = sectionStyle(colors, 19))
        }
        val actionModifier = Modifier.clip(RoundedCornerShape(12.dp))
            .background(colors.accentSurface)
            .then(if (click == null) Modifier else Modifier.clickable(onClick = click))
            .padding(horizontal = 12.dp, vertical = 6.dp)
        Box(actionModifier) {
            BasicText(action, style = labelStyle(colors, true).copy(fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun RankedRow(
    items: List<SourceSeries>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id.remoteKey }) { series ->
            val rank = items.indexOf(series) + 1
            Column(
                Modifier.width(152.dp)
                    .height(246.dp)
                    .shadow(3.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.06f))
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.card)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp))
                    .clickable { accept(LibraryIntent.SeriesSelected(series)) },
            ) {
                Box(Modifier.fillMaxWidth().height(162.dp)) {
                    SeriesArtwork(series, loader, colors, Modifier.matchParentSize())
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.50f)),
                                startY = 80f,
                            ),
                        ),
                    )
                    // Real rank medal badge (1, 2, 3 medals, 4..10 frosted number)
                    Box(
                        Modifier.padding(8.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                when (rank) {
                                    1 -> colors.goldGradient
                                    2 -> Brush.linearGradient(listOf(colors.silver, Color(0xFF94A3B8)))
                                    3 -> Brush.linearGradient(listOf(colors.bronze, Color(0xFFB45309)))
                                    else -> Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.70f), Color.Black.copy(alpha = 0.70f)))
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "$rank",
                            style = bodyStyle(colors, 13).copy(
                                color = if (rank == 1 || rank == 2) Color(0xFF0F172A) else Color.White,
                                fontWeight = FontWeight.ExtraBold,
                            ),
                        )
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                    BasicText(
                        series.title,
                        style = bodyStyle(colors, 13).copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        series.subtitle.orEmpty(),
                        style = hintStyle(colors, 11),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverRow(
    items: List<SourceSeries>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id.remoteKey }) { series ->
            SeriesGridCard(series, loader, colors, Modifier.width(152.dp), accept)
        }
    }
}

@Composable
private fun SeriesGridCard(
    series: SourceSeries,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    modifier: Modifier,
    accept: (LibraryIntent) -> Unit,
) {
    Column(
        modifier.height(248.dp)
            .shadow(3.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.06f))
            .clip(RoundedCornerShape(18.dp))
            .background(colors.card)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp))
            .semantics { contentDescription = "작품: ${series.title}" }
            .clickable { accept(LibraryIntent.SeriesSelected(series)) },
    ) {
        Box(Modifier.fillMaxWidth().height(166.dp)) {
            SeriesArtwork(series, loader, colors, Modifier.fillMaxSize())
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                        startY = 90f,
                    ),
                ),
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            BasicText(
                series.title,
                style = bodyStyle(colors, 13).copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                series.subtitle.orEmpty(),
                style = hintStyle(colors, 11),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeLoading(colors: LibraryColors) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.mutedSurface),
        )
        Spacer(Modifier.height(24.dp))
        BasicText("작품 목록을 불러오는 중…", style = hintStyle(colors, 15))
    }
}

@Composable
private fun HomeFailure(message: String, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(colors.mutedSurface),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.REFRESH, colors.muted, Modifier.size(30.dp))
        }
        BasicText(message, Modifier.padding(vertical = 16.dp), hintStyle(colors, 14))
        LibraryAction("다시 시도", colors) { accept(LibraryIntent.RetryHome) }
    }
}