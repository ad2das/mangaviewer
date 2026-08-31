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
import ml.melun.mangaview.source.SourceGenre
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
        if (state.homeTab == HomeTab.GENRES) {
            genreRows(state, artworkLoader, colors, accept)
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
    val hero = home.popular.firstOrNull() ?: home.latest.firstOrNull() ?: home.new.firstOrNull()
    if (hero != null) item { HeroCard(hero, loader, colors, accept) }
    if (home.popular.isNotEmpty()) {
        item { SectionHeader("이번 주 인기", "전체보기", colors) { accept(LibraryIntent.HomeTabSelected(HomeTab.POPULAR)) } }
        item { RankedRow(home.popular.take(10), loader, colors, accept) }
    }
    if (home.latest.isNotEmpty()) {
        item { SectionHeader("최신 업데이트", "${home.latest.size}개", colors, null) }
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
    state: LibraryState,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    item {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 6.dp)) {
            BasicText("장르 둘러보기", style = titleStyle(colors, 17))
            Spacer(Modifier.height(4.dp))
            BasicText("원하는 조건을 골라 바로 이동", style = hintStyle(colors, 12))
            Spacer(Modifier.height(12.dp))
            BasicText("장르별", style = bodyStyle(colors, 13).copy(fontWeight = FontWeight.Bold))
        }
    }
    when (val genres = state.genres) {
        GenreContent.Empty, GenreContent.Loading -> item { GenreMessage("장르를 불러오는 중…", colors) }
        is GenreContent.Failure -> item { GenreMessage(genres.message, colors) }
        is GenreContent.Ready -> items(genres.items.chunked(3), key = { row -> row.joinToString("|") { it.key } }) { row ->
            GenreRow(row, state.selectedGenre, colors, accept)
        }
    }
    if (state.selectedGenre != null) {
        when (val home = state.home) {
            HomeContent.Loading -> item { HomeLoading(colors) }
            is HomeContent.Failure -> item { HomeFailure(home.message, colors, accept) }
            is HomeContent.Ready -> seriesGrid(home.latest, loader, colors, accept)
        }
    }
}

@Composable
private fun GenreRow(
    row: List<SourceGenre>,
    selectedGenre: SourceGenre?,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        row.forEach { genre ->
            val selected = genre.key == selectedGenre?.key
            Box(
                Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.accent else colors.card)
                    .border(1.dp, colors.outline, RoundedCornerShape(10.dp))
                    .clickable { accept(LibraryIntent.GenreSelected(genre)) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    genre.label,
                    style = labelStyle(colors, selected).copy(
                        color = if (selected) Color.White else colors.secondary,
                    ),
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
    BasicText(message, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), hintStyle(colors, 14))
}

@Composable
private fun HomeHeading(colors: LibraryColors) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 18.dp)) {
        BasicText("READER", style = labelStyle(colors, true).copy(fontWeight = FontWeight.Bold, fontSize = 11.sp))
        Spacer(Modifier.height(6.dp))
        BasicText("읽던 작품으로 바로 이동", style = titleStyle(colors, 22))
        Spacer(Modifier.height(6.dp))
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
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(48.dp).clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, colors.outline, RoundedCornerShape(12.dp))
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
        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) colors.accent else Color.Transparent)
            .clickable { accept(LibraryIntent.HomeKindSelected(kind)) },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = bodyStyle(colors, 14).copy(color = if (active) Color.White else colors.secondary, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun HomeTabs(selected: HomeTab, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp)) {
        HomeTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                Modifier.weight(1f).clickable { accept(LibraryIntent.HomeTabSelected(tab)) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Spacer(Modifier.weight(1f))
                BasicText(tab.label, style = bodyStyle(colors, 14).copy(color = if (active) colors.accent else colors.secondary, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(9.dp))
                Box(Modifier.width(26.dp).height(3.dp).clip(CircleShape).background(if (active) colors.accent else Color.Transparent))
            }
        }
    }
}

@Composable
private fun HeroCard(series: SourceSeries, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(Modifier.fillMaxWidth().height(252.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .clickable { accept(LibraryIntent.SeriesSelected(series)) },
        ) {
            SeriesArtwork(series, loader, colors, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .68f), Color.Transparent))))
            Column(Modifier.matchParentSize().padding(16.dp)) {
                Box(
                    Modifier.height(26.dp).clip(RoundedCornerShape(8.dp)).background(colors.accentSurface)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("추천", style = labelStyle(colors, true).copy(color = Color(0xFF0F172A), fontSize = 11.sp))
                }
                Spacer(Modifier.height(12.dp))
                BasicText(series.title, style = titleStyle(colors, 25).copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                BasicText("지금 볼만한 추천 작품", style = bodyStyle(colors, 14).copy(color = Color.White))
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.width(128.dp).height(48.dp).clip(RoundedCornerShape(12.dp)).background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("보러가기", style = bodyStyle(colors, 14).copy(color = Color.White, fontWeight = FontWeight.Bold))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { index ->
                Box(Modifier.padding(horizontal = 4.dp).size(if (index == 0) 10.dp else 7.dp).clip(CircleShape).background(if (index == 0) colors.accent else colors.muted))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, colors: LibraryColors, click: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, Modifier.weight(1f), sectionStyle(colors, 19))
        val actionModifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.accentSurface)
            .then(if (click == null) Modifier else Modifier.clickable(onClick = click))
            .padding(horizontal = 12.dp, vertical = 8.dp)
        Box(actionModifier) {
            BasicText(action, style = labelStyle(colors, true))
        }
    }
}

@Composable
private fun RankedRow(items: List<SourceSeries>, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id.remoteKey }) { series ->
            Column(
                Modifier.width(150.dp).height(222.dp).clip(RoundedCornerShape(16.dp)).background(colors.card)
                    .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                    .clickable { accept(LibraryIntent.SeriesSelected(series)) }.padding(horizontal = 4.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(134.dp)) {
                    SeriesArtwork(series, loader, colors, Modifier.matchParentSize())
                    Box(Modifier.size(34.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                        BasicText("${items.indexOf(series) + 1}", style = bodyStyle(colors, 14).copy(color = Color.White, fontWeight = FontWeight.Bold))
                    }
                }
                BasicText(series.title, Modifier.padding(start = 6.dp, top = 10.dp, end = 6.dp), bodyStyle(colors, 14).copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                BasicText(series.subtitle.orEmpty(), Modifier.padding(horizontal = 6.dp, vertical = 8.dp), hintStyle(colors, 12), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        modifier.height(240.dp).padding(6.dp).clip(RoundedCornerShape(16.dp)).background(colors.card)
            .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
            .clickable { accept(LibraryIntent.SeriesSelected(series)) },
    ) {
        SeriesArtwork(series, loader, colors, Modifier.fillMaxWidth().height(160.dp))
        BasicText(series.title, Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp), bodyStyle(colors, 14).copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        BasicText(series.subtitle.orEmpty(), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), hintStyle(colors, 12), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
