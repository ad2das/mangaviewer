package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceSeries

@Composable
internal fun HomeScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)) {
        item { HomeHeading(colors) }
        item { KindSelector(state.homeKind, colors, accept) }
        item { HomeTabs(state.homeTab, colors, accept) }
        when (val home = state.home) {
            HomeContent.Loading -> item { HomeLoading(colors) }
            is HomeContent.Failure -> item { HomeFailure(home.message, colors, accept) }
            is HomeContent.Ready -> when (state.homeTab) {
                HomeTab.HOME -> homeRows(home, artworkLoader, colors, accept)
                HomeTab.POPULAR -> seriesGrid(home.popular, artworkLoader, colors, accept)
                HomeTab.NEW -> seriesGrid(home.new, artworkLoader, colors, accept)
                HomeTab.GENRES -> genreRows(state.homeKind, home, artworkLoader, colors, accept)
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
    val hero = home.popular.firstOrNull() ?: home.latest.firstOrNull() ?: home.new.firstOrNull()
    if (hero != null) item { HeroCard(hero, loader, colors, accept) }
    if (home.popular.isNotEmpty()) {
        item { SectionHeader("이번 주 인기", "전체보기", colors) { accept(LibraryIntent.HomeTabSelected(HomeTab.POPULAR)) } }
        item { RankedRow(home.popular.take(10), loader, colors, accept) }
    }
    if (home.latest.isNotEmpty()) {
        item { SectionHeader("최신 업데이트", "${home.latest.size}개", colors) {} }
        item { CoverRow(home.latest.take(12), loader, colors, accept) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.seriesGrid(
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { item -> SeriesGridCard(item, loader, colors, Modifier.weight(1f), accept) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.genreRows(
    kind: SeriesKind,
    home: HomeContent.Ready,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    item {
        val genres = if (kind == SeriesKind.WEBTOON) WEBTOON_GENRES else COMIC_GENRES
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            BasicText("장르별 작품", style = sectionStyle(colors))
            Spacer(Modifier.height(12.dp))
            genres.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { genre ->
                        val selected = genre == home.genre
                        Box(
                            Modifier.weight(1f).padding(bottom = 8.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (selected) colors.accent else colors.card)
                                .border(1.dp, colors.outline, RoundedCornerShape(11.dp))
                                .clickable { accept(LibraryIntent.GenreSelected(genre)) }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(genre, style = labelStyle(colors, selected).copy(color = if (selected) Color.White else colors.secondary))
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (home.genre != null) seriesGrid(home.latest, loader, colors, accept)
}

@Composable
private fun HomeHeading(colors: LibraryColors) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
        BasicText("READER", style = labelStyle(colors, true).copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        BasicText("읽던 작품으로 바로 이동", style = titleStyle(colors, 23))
        Spacer(Modifier.height(5.dp))
        BasicText(
            "최근 기록, 인기 목록, 신작과 장르를 한 화면에서 정리했습니다.",
            style = hintStyle(colors, 12),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun KindSelector(selected: SeriesKind, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(50.dp)
            .clip(RoundedCornerShape(14.dp)).border(1.dp, colors.outline, RoundedCornerShape(14.dp))
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
        Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active) colors.accent else Color.Transparent)
            .clickable { accept(LibraryIntent.HomeKindSelected(kind)) },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = bodyStyle(colors, 14).copy(color = if (active) Color.White else colors.secondary, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun HomeTabs(selected: HomeTab, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp)) {
        HomeTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                Modifier.weight(1f).clickable { accept(LibraryIntent.HomeTabSelected(tab)) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Spacer(Modifier.weight(1f))
                BasicText(tab.label, style = bodyStyle(colors, 14).copy(color = if (active) colors.accent else colors.secondary, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(12.dp))
                Box(Modifier.width(26.dp).height(3.dp).clip(CircleShape).background(if (active) colors.accent else Color.Transparent))
            }
        }
    }
}

@Composable
private fun HeroCard(series: SourceSeries, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).aspectRatio(1.62f)
            .clip(RoundedCornerShape(2.dp)).clickable { accept(LibraryIntent.SeriesSelected(series)) },
    ) {
        SeriesArtwork(series, loader, colors, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .68f), Color.Transparent))))
        Column(Modifier.align(Alignment.CenterStart).padding(18.dp)) {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.accentSurface).padding(horizontal = 10.dp, vertical = 6.dp)) {
                BasicText("추천", style = labelStyle(colors, true).copy(color = Color(0xFF0F172A)))
            }
            Spacer(Modifier.height(14.dp))
            BasicText(series.title, style = titleStyle(colors, 23).copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            BasicText("지금 볼만한 추천 작품", style = bodyStyle(colors).copy(color = Color.White))
            Spacer(Modifier.height(18.dp))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.accent).padding(horizontal = 34.dp, vertical = 13.dp)) {
                BasicText("보러가기", style = bodyStyle(colors).copy(color = Color.White, fontWeight = FontWeight.Bold))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(5) { index ->
            Box(Modifier.padding(4.dp).size(if (index == 0) 10.dp else 7.dp).clip(CircleShape).background(if (index == 0) colors.accent else colors.muted))
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, colors: LibraryColors, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, Modifier.weight(1f), sectionStyle(colors))
        Box(Modifier.clip(RoundedCornerShape(11.dp)).background(colors.accentSurface).clickable(onClick = click).padding(horizontal = 12.dp, vertical = 8.dp)) {
            BasicText(action, style = labelStyle(colors, true))
        }
    }
}

@Composable
private fun RankedRow(items: List<SourceSeries>, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id.remoteKey }) { series ->
            Box(Modifier.width(132.dp).height(178.dp).clip(RoundedCornerShape(8.dp)).clickable { accept(LibraryIntent.SeriesSelected(series)) }) {
                SeriesArtwork(series, loader, colors, Modifier.matchParentSize())
                Box(Modifier.padding(6.dp).size(36.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                    BasicText("${items.indexOf(series) + 1}", style = bodyStyle(colors).copy(color = Color.White, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun CoverRow(items: List<SourceSeries>, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id.remoteKey }) { series ->
            SeriesGridCard(series, loader, colors, Modifier.width(142.dp), accept)
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
        modifier.height(232.dp).clip(RoundedCornerShape(12.dp)).background(colors.card)
            .border(1.dp, colors.outline, RoundedCornerShape(12.dp))
            .clickable { accept(LibraryIntent.SeriesSelected(series)) },
    ) {
        SeriesArtwork(series, loader, colors, Modifier.fillMaxWidth().height(168.dp))
        BasicText(series.title, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), bodyStyle(colors, 13), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeLoading(colors: LibraryColors) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.62f).clip(RoundedCornerShape(12.dp)).background(colors.mutedSurface))
        Spacer(Modifier.height(24.dp))
        BasicText("목록을 불러오는 중…", style = hintStyle(colors, 15))
    }
}

@Composable
private fun HomeFailure(message: String, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LibraryIconView(LibraryIcon.REFRESH, colors.muted, Modifier.size(54.dp))
        BasicText(message, Modifier.padding(vertical = 16.dp), hintStyle(colors, 14))
        LibraryAction("다시 시도", colors) { accept(LibraryIntent.RetryHome) }
    }
}

private val WEBTOON_GENRES = listOf("드라마", "판타지", "액션", "로맨스", "무협", "학원", "일상", "스릴러")
private val COMIC_GENRES = listOf("판타지", "러브코미디", "드라마", "액션", "학원", "이세계", "일상", "스포츠")
