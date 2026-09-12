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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ml.melun.mangaview.data.offline.EpisodeDownloadState
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceSeriesDetails

private val EpisodeCardShape = RoundedCornerShape(18.dp)
private val EpisodeIconShape = RoundedCornerShape(14.dp)
private val EpisodeActionShape = RoundedCornerShape(12.dp)

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
private fun DetailToolbar(
    series: SourceSeries,
    favorite: Boolean,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(LibraryIcon.BACK, "뒤로", colors.secondary) { accept(LibraryIntent.Back) }
        Spacer(Modifier.weight(1f))
        IconButton(
            LibraryIcon.HEART,
            "좋아요",
            if (favorite) colors.favoriteActive else colors.secondary,
        ) {
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
        Modifier.size(44.dp)
            .semantics { contentDescription = label }
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        LibraryIconView(icon, color, Modifier.size(24.dp))
    }
}

@Composable
private fun DetailLoading(
    state: LibraryState,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val series = state.activeSeries ?: return
    Column(Modifier.fillMaxSize()) {
        DetailHeader(series, null, isFavorite(state, series), state.activeSeriesDetails, loader, colors, accept)
        LibraryMessage("회차를 불러오는 중…", colors, Modifier.weight(1f))
    }
}

@Composable
private fun DetailFailure(
    state: LibraryState,
    message: String,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val series = state.activeSeries ?: return
    Column(Modifier.fillMaxSize()) {
        DetailHeader(series, null, isFavorite(state, series), state.activeSeriesDetails, loader, colors, accept)
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item { DetailHeader(series, quickRead, isFavorite(state, series), state.activeSeriesDetails, loader, colors, accept) }
        item { DetailTabs(state.detailTab, colors, accept) }
        if (state.detailTab != DetailTab.EPISODES) {
            item { DetailInformation(state.detailTab, series, episodes.size, state.activeSeriesDetails, colors) }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, top = 22.dp, end = 18.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(colors.accentSurface)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    BasicText("회차", style = sectionStyle(colors, 16))
                }
                Spacer(Modifier.width(8.dp))
                BasicText("${episodes.size}개", style = hintStyle(colors, 13))
            }
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
    details: SourceSeriesDetails?,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(192.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.width(138.dp).height(192.dp)
                    .shadow(10.dp, RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.22f))
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(20.dp)),
            ) {
                SeriesArtwork(series, loader, colors, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        details?.status?.let { status -> SeriesStatusBadge(status, colors) }
                        Box(
                            Modifier.clip(RoundedCornerShape(7.dp))
                                .background(colors.accentGradient)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            BasicText(
                                if (series.id.sourceId.value == "ntk") "만화" else "웹툰",
                                style = microBadgeStyle(colors, 10).copy(color = Color.White),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        series.title,
                        style = titleStyle(colors, 21).copy(fontWeight = FontWeight.Black),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    (details?.authors?.takeIf(String::isNotBlank) ?: series.subtitle?.takeIf(String::isNotBlank))
                        ?.let { subtitle ->
                            Spacer(Modifier.height(6.dp))
                            BasicText(
                                subtitle,
                                style = hintStyle(colors, 12),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                subtitle.split(",", "/", "·").take(2).forEach { tag ->
                                    if (tag.isNotBlank()) TagChip(tag.trim(), colors)
                                }
                            }
                        }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LibraryIconView(
                        LibraryIcon.HEART,
                        if (favorite) colors.favoriteActive else colors.muted,
                        Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        if (favorite) "관심 등록됨" else "관심 등록",
                        style = hintStyle(colors, 12).copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .shadow(6.dp, RoundedCornerShape(16.dp), spotColor = colors.accent.copy(alpha = 0.40f))
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.accentGradient)
                    .clickable { firstEpisode?.let { accept(LibraryIntent.EpisodeSelected(it.id)) } },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LibraryIconView(LibraryIcon.PLAY, Color.White, Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        "바로 읽기",
                        style = bodyStyle(colors, 15).copy(color = Color.White, fontWeight = FontWeight.Bold),
                    )
                }
            }
            Box(
                Modifier.width(52.dp).fillMaxHeight()
                    .semantics { contentDescription = "좋아요" }
                    .shadow(3.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.06f))
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.card)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                    .clickable { accept(LibraryIntent.FavoriteToggled(series)) },
                contentAlignment = Alignment.Center,
            ) {
                LibraryIconView(
                    LibraryIcon.HEART,
                    if (favorite) colors.favoriteActive else colors.secondary,
                    Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun TagChip(label: String, colors: LibraryColors) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(colors.mutedSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        BasicText(label, style = hintStyle(colors, 11).copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun DetailTabs(selected: DetailTab, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(46.dp)
            .shadow(3.dp, RoundedCornerShape(15.dp), spotColor = Color.Black.copy(alpha = 0.05f))
            .clip(RoundedCornerShape(15.dp))
            .background(colors.mutedSurface)
            .padding(3.dp),
    ) {
        DetailTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) colors.card else Color.Transparent)
                    .then(if (active) Modifier.shadow(3.dp, RoundedCornerShape(12.dp), spotColor = Color.Black.copy(alpha = 0.10f)) else Modifier)
                    .clickable { accept(LibraryIntent.DetailTabSelected(tab)) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    tab.label,
                    style = bodyStyle(colors, 13).copy(
                        color = if (active) colors.text else colors.secondary,
                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DetailInformation(
    tab: DetailTab,
    series: SourceSeries,
    episodeCount: Int,
    details: SourceSeriesDetails?,
    colors: LibraryColors,
) {
    val text = when (tab) {
        DetailTab.INTRO -> details?.description?.takeIf(String::isNotBlank)
            ?: series.subtitle?.takeIf(String::isNotBlank)
            ?: "등록된 소개가 없습니다."
        DetailTab.INFO -> buildString {
            append("출처: ${series.id.sourceId.value.uppercase()}")
            details?.status?.let { append("\n상태: ${it.label()}") }
            details?.authors?.takeIf(String::isNotBlank)?.let { append("\n작가: $it") }
            append("\n총 회차: ${episodeCount}개")
            append("\n원작 식별자: ${series.id.remoteKey}")
        }
        DetailTab.EPISODES -> return
    }
    Box(
        Modifier.fillMaxWidth().padding(start = 18.dp, top = 14.dp, end = 18.dp)
            .shadow(3.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.06f))
            .clip(RoundedCornerShape(18.dp))
            .background(colors.card)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp))
            .padding(18.dp),
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
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).height(88.dp)
            .clip(EpisodeCardShape)
            .background(colors.card)
            .border(1.dp, colors.cardBorder, EpisodeCardShape)
            .clickable(onClick = open)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp)
                .clip(EpisodeIconShape)
                .background(colors.mutedSurface),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.PLAY, colors.accent, Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                episode.title,
                style = bodyStyle(colors, 14).copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episode.publishedAtEpochMillis?.let {
                Spacer(Modifier.height(4.dp))
                BasicText(formatDate(it), style = hintStyle(colors, 11))
            }
        }
        Box(
            Modifier.size(42.dp)
                .semantics {
                    contentDescription = if (saved) "${episode.title} 오프라인 저장 삭제" else "${episode.title} 다운로드"
                }
                .clip(EpisodeActionShape)
                .background(colors.mutedSurface)
                .clickable(
                    enabled = saved || downloadState == null || downloadState is EpisodeDownloadState.Failed,
                    onClick = storageAction,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                saved || downloadState is EpisodeDownloadState.Complete ->
                    BasicText("✓", style = bodyStyle(colors, 18).copy(color = colors.accent, fontWeight = FontWeight.Bold))
                downloadState is EpisodeDownloadState.Running -> BasicText(
                    "${downloadState.completedPages}/${downloadState.totalPages}",
                    style = hintStyle(colors, 9).copy(color = colors.accent),
                )
                else -> LibraryIconView(LibraryIcon.DOWNLOAD, colors.secondary, Modifier.size(20.dp))
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

private fun SeriesStatus.label(): String = when (this) {
    SeriesStatus.ONGOING -> "연재중"
    SeriesStatus.COMPLETED -> "완결"
    SeriesStatus.HIATUS -> "휴재"
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd")