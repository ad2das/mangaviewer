package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

@Composable
internal fun SeriesDetailScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val series = state.activeSeries ?: return
    Column(Modifier.fillMaxSize().background(colors.background)) {
        DetailToolbar(series, state.saved.favorites.any { it.id == series.id }, colors, accept)
        when (val content = state.content) {
            LibraryContent.Loading -> DetailLoading(series, artworkLoader, colors, accept)
            is LibraryContent.Episodes -> DetailBody(series, content.items, artworkLoader, colors, accept)
            is LibraryContent.Failure -> DetailFailure(series, content.message, artworkLoader, colors, accept)
            else -> DetailLoading(series, artworkLoader, colors, accept)
        }
    }
}

@Composable
private fun DetailToolbar(series: SourceSeries, favorite: Boolean, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(LibraryIcon.BACK, colors.secondary) { accept(LibraryIntent.Back) }
        Spacer(Modifier.weight(1f))
        IconButton(LibraryIcon.HEART, if (favorite) Color(0xFFEC4899) else colors.secondary) {
            accept(LibraryIntent.FavoriteToggled(series))
        }
        IconButton(LibraryIcon.DOWNLOAD, colors.secondary) { }
        IconButton(LibraryIcon.MORE, colors.secondary) { }
    }
}

@Composable
private fun IconButton(icon: LibraryIcon, color: Color, click: () -> Unit) {
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = click), contentAlignment = Alignment.Center) {
        LibraryIconView(icon, color, Modifier.size(26.dp))
    }
}

@Composable
private fun DetailLoading(series: SourceSeries, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader(series, null, loader, colors, accept)
        LibraryMessage("회차를 불러오는 중…", colors, Modifier.weight(1f))
    }
}

@Composable
private fun DetailFailure(series: SourceSeries, message: String, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader(series, null, loader, colors, accept)
        LibraryMessage(message, colors, Modifier.weight(1f))
    }
}

@Composable
private fun DetailBody(
    series: SourceSeries,
    episodes: List<SourceEpisode>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
        item { DetailHeader(series, episodes.firstOrNull(), loader, colors, accept) }
        item { DetailTabs(colors) }
        item {
            BasicText(
                "회차",
                Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(10.dp)).background(colors.accentSurface).padding(horizontal = 12.dp, vertical = 8.dp),
                sectionStyle(colors, 18),
            )
        }
        if (episodes.isEmpty()) {
            item { LibraryMessage("등록된 회차가 없습니다", colors, Modifier.height(220.dp)) }
        } else {
            items(episodes, key = { it.id.remoteKey }) { episode ->
                EpisodeCard(episode, colors) { accept(LibraryIntent.EpisodeSelected(episode.id)) }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    series: SourceSeries,
    firstEpisode: SourceEpisode?,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(210.dp), verticalAlignment = Alignment.CenterVertically) {
            SeriesArtwork(
                series,
                loader,
                colors,
                Modifier.width(132.dp).height(190.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(12.dp)),
            )
            Column(Modifier.weight(1f).padding(start = 20.dp)) {
                BasicText(series.title, style = titleStyle(colors, 22), maxLines = 4, overflow = TextOverflow.Ellipsis)
                series.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtitle.split(',', '/', '·').take(3).forEach { tag ->
                            if (tag.isNotBlank()) TagChip(tag.trim(), colors)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                BasicText("♥  0", style = hintStyle(colors, 14).copy(color = colors.accent))
            }
        }
        Row(
            Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(14.dp)).background(colors.card)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryAction("바로 읽기", colors, Modifier.weight(1f).height(54.dp)) {
                firstEpisode?.let { accept(LibraryIntent.EpisodeSelected(it.id)) }
            }
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(13.dp)).border(1.dp, colors.outline, RoundedCornerShape(13.dp))
                    .clickable { accept(LibraryIntent.FavoriteToggled(series)) },
                contentAlignment = Alignment.Center,
            ) {
                LibraryIconView(LibraryIcon.HEART, Color(0xFFEC4899), Modifier.size(27.dp))
            }
        }
    }
}

@Composable
private fun TagChip(label: String, colors: LibraryColors) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.card).border(1.dp, colors.outline, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
        BasicText(label, style = hintStyle(colors, 12))
    }
}

@Composable
private fun DetailTabs(colors: LibraryColors) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(54.dp)
            .clip(RoundedCornerShape(14.dp)).background(colors.card).border(1.dp, colors.outline, RoundedCornerShape(14.dp)),
    ) {
        listOf("소개", "회차", "정보").forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                BasicText(label, style = bodyStyle(colors, 14).copy(fontWeight = if (index == 1) FontWeight.Bold else FontWeight.Normal))
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(.7f).height(3.dp).background(if (index == 1) colors.accent else Color.Transparent))
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: SourceEpisode, colors: LibraryColors, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).height(94.dp)
            .clip(RoundedCornerShape(14.dp)).background(colors.card).clickable(onClick = click).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(colors.mutedSurface), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.muted))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            BasicText(episode.title, style = bodyStyle(colors, 15), maxLines = 2, overflow = TextOverflow.Ellipsis)
            episode.publishedAtEpochMillis?.let {
                Spacer(Modifier.height(5.dp))
                BasicText(formatDate(it), style = hintStyle(colors, 12))
            }
        }
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(colors.mutedSurface), contentAlignment = Alignment.Center) {
            LibraryIconView(LibraryIcon.DOWNLOAD, colors.accent, Modifier.size(26.dp))
        }
    }
}

private fun formatDate(epochMillis: Long): String = DATE_FORMAT.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd")
