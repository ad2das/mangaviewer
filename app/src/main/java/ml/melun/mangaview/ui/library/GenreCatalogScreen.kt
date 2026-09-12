package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import ml.melun.mangaview.source.SeriesStatus

@Composable
internal fun GenreCatalogScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    list: androidx.compose.foundation.lazy.LazyListState,
    accept: (LibraryIntent) -> Unit,
) {
    val genre = state.selectedGenre ?: return
    Column(
        Modifier.fillMaxSize().background(colors.background).semantics {
            contentDescription = "장르 목록: " + genre.label
        },
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { accept(LibraryIntent.Back) },
                contentAlignment = Alignment.Center,
            ) {
                LibraryIconView(LibraryIcon.BACK, colors.secondary, Modifier.size(24.dp))
            }
            Spacer(Modifier.width(6.dp))
            BasicText(
                genre.label,
                Modifier.weight(1f),
                titleStyle(colors, 20).copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusFilterChip("전체", state.genreStatusFilter == null, colors) {
                accept(LibraryIntent.GenreFilterSelected(null))
            }
            StatusFilterChip("연재중", state.genreStatusFilter == SeriesStatus.ONGOING, colors) {
                accept(LibraryIntent.GenreFilterSelected(SeriesStatus.ONGOING))
            }
            StatusFilterChip("완결", state.genreStatusFilter == SeriesStatus.COMPLETED, colors) {
                accept(LibraryIntent.GenreFilterSelected(SeriesStatus.COMPLETED))
            }
        }
        when (val catalog = state.genreCatalog) {
            LibraryContent.Empty, LibraryContent.Loading ->
                LibraryMessage(genre.label + " 작품을 불러오는 중…", colors, Modifier.weight(1f))
            is LibraryContent.Failure ->
                LibraryMessage(catalog.message, colors, Modifier.weight(1f))
            is LibraryContent.Series -> GenreSeriesList(
                catalog, artworkLoader, colors,
                Modifier.weight(1f), list, state.genreStatusFilter, accept,
            )
            is LibraryContent.Episodes -> Unit
        }
    }
}

@Composable
private fun StatusFilterChip(
    label: String,
    selected: Boolean,
    colors: LibraryColors,
    click: () -> Unit,
) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .then(if (selected) Modifier.background(colors.accentGradient) else Modifier.background(colors.card))
            .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = click)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = labelStyle(colors, selected).copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else colors.secondary,
            ),
        )
    }
}

private fun SeriesStatus?.accepts(status: SeriesStatus?): Boolean = when {
    this == null -> true
    status == null -> true
    this == SeriesStatus.ONGOING -> status != SeriesStatus.COMPLETED
    else -> status == this
}

@Composable
private fun GenreSeriesList(
    catalog: LibraryContent.Series,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    modifier: Modifier,
    list: androidx.compose.foundation.lazy.LazyListState,
    filter: SeriesStatus?,
    accept: (LibraryIntent) -> Unit,
) {
    val visible = catalog.items.filter { filter.accepts(it.status) }
    val rows = remember(visible) { visible.chunked(2) }
    LaunchedEffect(list, catalog.items.size, catalog.nextCursor, catalog.loadingNext, catalog.nextFailure) {
        if (catalog.loadingNext || catalog.nextFailure != null || catalog.nextCursor == null) return@LaunchedEffect
        snapshotFlow {
            val layout = list.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 4
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) accept(LibraryIntent.LoadMoreGenre)
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 20.dp)) {
        if (rows.isNotEmpty()) gridRows(rows, loader, colors, accept)
        item(key = "catalog-status") {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val message = when {
                    catalog.loadingNext -> "다음 작품을 불러오는 중…"
                    catalog.nextFailure != null -> catalog.nextFailure
                    catalog.nextCursor != null -> visible.size.toString() + "개 불러옴"
                    visible.isEmpty() -> "조건에 맞는 작품이 없습니다"
                    else -> "목록 끝 · " + visible.size.toString() + "개"
                }
                BasicText(message, style = hintStyle(colors, 14).copy(fontWeight = FontWeight.Medium))
                if (catalog.nextFailure != null || (!catalog.loadingNext && catalog.nextCursor != null)) {
                    Spacer(Modifier.height(14.dp))
                    LibraryAction(if (catalog.nextFailure != null) "다시 시도" else "더 보기", colors) {
                        accept(LibraryIntent.LoadMoreGenre)
                    }
                }
            }
        }
    }
}
