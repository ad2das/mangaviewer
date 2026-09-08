package ml.melun.mangaview.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

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
        Modifier.fillMaxSize().semantics {
            contentDescription = "장르 목록: ${genre.label}"
        },
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { accept(LibraryIntent.Back) },
                contentAlignment = Alignment.Center,
            ) {
                LibraryIconView(LibraryIcon.BACK, colors.secondary, Modifier.size(26.dp))
            }
            BasicText(
                genre.label,
                Modifier.weight(1f).padding(horizontal = 8.dp),
                titleStyle(colors, 21),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (val catalog = state.genreCatalog) {
            LibraryContent.Empty, LibraryContent.Loading ->
                LibraryMessage("${genre.label} 작품을 불러오는 중…", colors, Modifier.weight(1f))
            is LibraryContent.Failure ->
                LibraryMessage(catalog.message, colors, Modifier.weight(1f))
            is LibraryContent.Series -> GenreSeriesList(catalog, artworkLoader, colors,
                Modifier.weight(1f), list, accept)
            is LibraryContent.Episodes -> Unit
        }
    }
}

@Composable
private fun GenreSeriesList(catalog: LibraryContent.Series, loader: SeriesArtworkLoader,
    colors: LibraryColors, modifier: Modifier, list: androidx.compose.foundation.lazy.LazyListState,
    accept: (LibraryIntent) -> Unit) {
    LaunchedEffect(list, catalog.items.size, catalog.nextCursor, catalog.loadingNext, catalog.nextFailure) {
        if (catalog.loadingNext || catalog.nextFailure != null || catalog.nextCursor == null) return@LaunchedEffect
        snapshotFlow {
            val layout = list.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 4
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) accept(LibraryIntent.LoadMoreGenre)
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
        if (catalog.items.isNotEmpty()) seriesGrid(catalog.items, loader, colors, accept)
        item(key = "catalog-status") {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val message = when {
                    catalog.loadingNext -> "다음 작품을 불러오는 중…"
                    catalog.nextFailure != null -> catalog.nextFailure
                    catalog.nextCursor != null -> "${catalog.items.size}개 불러옴"
                    catalog.items.isEmpty() -> "이 장르에 등록된 작품이 없습니다"
                    else -> "목록 끝 · ${catalog.items.size}개"
                }
                BasicText(message, style = hintStyle(colors, 14))
                if (catalog.nextFailure != null || (!catalog.loadingNext && catalog.nextCursor != null)) {
                    Spacer(Modifier.height(12.dp))
                    LibraryAction(if (catalog.nextFailure != null) "다시 시도" else "더 보기", colors) {
                        accept(LibraryIntent.LoadMoreGenre)
                    }
                }
            }
        }
    }
}
