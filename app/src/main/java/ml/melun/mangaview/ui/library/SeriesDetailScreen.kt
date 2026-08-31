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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.data.offline.EpisodeDownloadState

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
            LibraryContent.Loading -> DetailLoading(state, artworkLoader, colors, accept)
            is LibraryContent.Episodes -> DetailBody(state, content.items, artworkLoader, colors, accept)
            is LibraryContent.Failure -> DetailFailure(state, content.message, artworkLoader, colors, accept)
            else -> DetailLoading(state, artworkLoader, colors, accept)
        }
    }
}

@Composable
private fun DetailToolbar(series: SourceSeries, favorite: Boolean, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(LibraryIcon.BACK, "뒤로", colors.secondary) { accept(LibraryIntent.Back) }
        Spacer(Modifier.weight(1f))
        IconButton(LibraryIcon.HEART, "좋아요", if (favorite) Color(0xFFEC4899) else colors.secondary) {
            accept(LibraryIntent.FavoriteToggled(series))
        }
        IconButton(LibraryIcon.DOWNLOAD, "오프라인 저장", colors.secondary) {
            accept(LibraryIntent.ToggleDownloadSelection)
        }
        IconButton(LibraryIcon.MORE, "더보기", colors.secondary) {
            accept(LibraryIntent.ToggleSeriesMenu)
        }
    }
}

@Composable
private fun IconButton(icon: LibraryIcon, label: String, color: Color, click: () -> Unit) {
    Box(
        Modifier.size(46.dp).semantics { contentDescription = label }
            .clip(RoundedCornerShape(14.dp)).clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        LibraryIconView(icon, color, Modifier.size(26.dp))
    }
}

@Composable
private fun DetailLoading(state: LibraryState, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    val series = state.activeSeries ?: return
    Column(Modifier.fillMaxSize()) {
        DetailHeader(series, null, isFavorite(state, series), loader, colors, accept)
        LibraryMessage("회차를 불러오는 중…", colors, Modifier.weight(1f))
    }
}

@Composable
private fun DetailFailure(state: LibraryState, message: String, loader: SeriesArtworkLoader, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    val series = state.activeSeries ?: return
    Column(Modifier.fillMaxSize()) {
        DetailHeader(series, null, isFavorite(state, series), loader, colors, accept)
        LibraryMessage(message, colors, Modifier.weight(1f))
    }
}

@Composable
private fun DetailBody(
    state: LibraryState,
    episodes: List<SourceEpisode>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val series = state.activeSeries ?: return
    val quickRead = quickReadEpisode(state, series, episodes)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
        item { DetailHeader(series, quickRead, isFavorite(state, series), loader, colors, accept) }
        item { DetailTabs(state.detailTab, colors, accept) }
        if (state.detailTab != DetailTab.EPISODES) {
            item { DetailInformation(state.detailTab, series, episodes.size, colors) }
        }
        item {
            BasicText(
                "회차",
                Modifier.padding(start = 18.dp, top = 20.dp, end = 18.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(8.dp)).background(colors.accentSurface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                sectionStyle(colors, 17),
            )
        }
        if (episodes.isEmpty()) {
            item { LibraryMessage("등록된 회차가 없습니다", colors, Modifier.height(220.dp)) }
        } else {
            items(episodes, key = { it.id.remoteKey }) { episode ->
                EpisodeCard(
                    episode = episode,
                    saved = state.offlineEpisodes.any { it.episode.id == episode.id },
                    downloadState = state.downloadStates[episode.id],
                    colors = colors,
                    open = { accept(LibraryIntent.EpisodeSelected(episode.id)) },
                    storageAction = {
                        if (state.offlineEpisodes.any { it.episode.id == episode.id }) {
                            accept(LibraryIntent.RemoveOfflineEpisode(episode.id))
                        } else {
                            accept(LibraryIntent.DownloadEpisode(series, episode))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(
    series: SourceSeries,
    firstEpisode: SourceEpisode?,
    favorite: Boolean,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(178.dp), verticalAlignment = Alignment.Top) {
            SeriesArtwork(
                series,
                loader,
                colors,
                Modifier.width(132.dp).height(178.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(10.dp)),
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                BasicText(series.title, style = titleStyle(colors, 20), maxLines = 2, overflow = TextOverflow.Ellipsis)
                series.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Spacer(Modifier.height(8.dp))
                    BasicText(subtitle, style = hintStyle(colors, 12), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtitle.split(',', '/', '·').take(3).forEach { tag ->
                            if (tag.isNotBlank()) TagChip(tag.trim(), colors)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                BasicText("♥  0", style = hintStyle(colors, 12).copy(color = colors.accent))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(colors.card)
                .border(1.dp, colors.outline, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LibraryAction("바로 읽기", colors, Modifier.weight(1f).height(44.dp)) {
                firstEpisode?.let { accept(LibraryIntent.EpisodeSelected(it.id)) }
            }
            Box(
                Modifier.width(46.dp).height(44.dp).semantics { contentDescription = "좋아요" }
                    .clip(RoundedCornerShape(10.dp)).border(1.dp, colors.outline, RoundedCornerShape(10.dp))
                    .clickable { accept(LibraryIntent.FavoriteToggled(series)) },
                contentAlignment = Alignment.Center,
            ) {
                LibraryIconView(
                    LibraryIcon.HEART,
                    if (favorite) Color(0xFFEC4899) else colors.secondary,
                    Modifier.size(22.dp),
                )
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
private fun DetailTabs(selected: DetailTab, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(48.dp)
            .clip(RoundedCornerShape(10.dp)).background(colors.card).border(1.dp, colors.outline, RoundedCornerShape(10.dp)),
    ) {
        DetailTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                Modifier.weight(1f).clickable { accept(LibraryIntent.DetailTabSelected(tab)) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                BasicText(
                    tab.label,
                    style = hintStyle(colors, 12).copy(
                        color = if (active) colors.text else colors.secondary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) colors.accent else Color.Transparent))
            }
        }
    }
}

@Composable
private fun DetailInformation(tab: DetailTab, series: SourceSeries, episodeCount: Int, colors: LibraryColors) {
    val text = when (tab) {
        DetailTab.INTRO -> series.subtitle?.takeIf(String::isNotBlank) ?: "등록된 소개가 없습니다."
        DetailTab.INFO -> "출처: ${series.id.sourceId.value.uppercase()}\n회차: ${episodeCount}개"
        DetailTab.EPISODES -> return
    }
    Box(
        Modifier.fillMaxWidth().padding(start = 18.dp, top = 16.dp, end = 18.dp)
            .clip(RoundedCornerShape(10.dp)).background(colors.card)
            .border(1.dp, colors.outline, RoundedCornerShape(10.dp)).padding(16.dp),
    ) {
        BasicText(text, style = bodyStyle(colors, 14).copy(color = colors.secondary))
    }
}

@Composable
private fun EpisodeCard(
    episode: SourceEpisode,
    saved: Boolean,
    downloadState: EpisodeDownloadState?,
    colors: LibraryColors,
    open: () -> Unit,
    storageAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(96.dp)
            .clip(RoundedCornerShape(10.dp)).background(colors.card).clickable(onClick = open).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(colors.mutedSurface), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.muted))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            BasicText(
                episode.title,
                style = bodyStyle(colors, 15).copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episode.publishedAtEpochMillis?.let {
                Spacer(Modifier.height(5.dp))
                BasicText(formatDate(it), style = hintStyle(colors, 12))
            }
        }
        Box(
            Modifier.size(48.dp).semantics {
                contentDescription = if (saved) "${episode.title} 오프라인 저장 삭제" else "${episode.title} 다운로드"
            }.clip(RoundedCornerShape(12.dp)).background(colors.mutedSurface)
                .clickable(
                    enabled = saved || downloadState == null || downloadState is EpisodeDownloadState.Failed,
                    onClick = storageAction,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                saved || downloadState is EpisodeDownloadState.Complete ->
                    BasicText("✓", style = bodyStyle(colors, 20).copy(color = colors.accent, fontWeight = FontWeight.Bold))
                downloadState is EpisodeDownloadState.Running -> BasicText(
                    "${downloadState.completedPages}/${downloadState.totalPages}",
                    style = hintStyle(colors, 9).copy(color = colors.accent),
                )
                else -> LibraryIconView(LibraryIcon.DOWNLOAD, colors.accent, Modifier.size(26.dp))
            }
        }
    }
}

private fun isFavorite(state: LibraryState, series: SourceSeries): Boolean =
    state.saved.favorites.any { it.id == series.id }

internal fun quickReadEpisode(
    state: LibraryState,
    series: SourceSeries,
    episodes: List<SourceEpisode>,
): SourceEpisode? {
    val recent = state.saved.recent.firstOrNull { it.series.id == series.id }?.episodeId
    return episodes.firstOrNull { it.id == recent } ?: firstEpisode(episodes)
}

private fun formatDate(epochMillis: Long): String = DATE_FORMAT.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd")
