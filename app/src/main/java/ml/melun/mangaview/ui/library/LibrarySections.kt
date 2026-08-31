package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.data.library.RecentReading
import ml.melun.mangaview.data.library.SavedBookmark
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.data.settings.ViewerSettings
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

@Composable
internal fun SearchResults(
    content: LibraryContent,
    favorites: Set<SeriesId>,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    when (content) {
        LibraryContent.Empty -> LibraryMessage("작품명을 입력하세요", colors)
        LibraryContent.Loading -> LibraryMessage("불러오는 중…", colors)
        is LibraryContent.Failure -> LibraryMessage(content.message, colors)
        is LibraryContent.Series -> ResultList(content.items, { it.id.remoteKey }, colors) { series ->
            SeriesRow(series, series.id in favorites, colors, accept)
        }
        is LibraryContent.Episodes -> EpisodeList(content, content.series.id in favorites, colors, accept)
    }
}

@Composable
private fun SeriesRow(
    series: SourceSeries,
    favorite: Boolean,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clickable { accept(LibraryIntent.SeriesSelected(series)) }.padding(14.dp),
        ) {
            BasicText(series.title, style = bodyStyle(colors))
            series.subtitle?.let { BasicText(it, style = hintStyle(colors)) }
        }
        FavoriteAction(favorite, colors) { accept(LibraryIntent.FavoriteToggled(series)) }
    }
}

@Composable
private fun EpisodeList(
    content: LibraryContent.Episodes,
    favorite: Boolean,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LibraryAction("뒤로", colors) { accept(LibraryIntent.Back) }
        BasicText(content.series.title, Modifier.weight(1f).padding(start = 12.dp), sectionStyle(colors))
        FavoriteAction(favorite, colors) { accept(LibraryIntent.FavoriteToggled(content.series)) }
    }
    ResultList(content.items, { it.id.remoteKey }, colors) { episode ->
        EpisodeRow(episode, colors) { accept(LibraryIntent.EpisodeSelected(episode.id)) }
    }
}

@Composable
private fun EpisodeRow(episode: SourceEpisode, colors: LibraryColors, select: () -> Unit) {
    BasicText(
        episode.title,
        Modifier.fillMaxWidth().clickable(onClick = select).padding(14.dp),
        style = bodyStyle(colors),
    )
}

@Composable
internal fun RecentSection(
    items: List<RecentReading>,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    if (items.isEmpty()) return LibraryMessage("최근 열람한 작품이 없습니다", colors)
    ResultList(items, { "${it.episodeId.seriesId.sourceId.value}:${it.episodeId.seriesId.remoteKey}" }, colors) { item ->
        SavedEpisodeRow(item.series.title, "${item.episodeId.remoteKey} · ${item.pageId.remoteKey}", colors) {
            accept(LibraryIntent.SavedEpisodeSelected(
                ReadingPosition(item.pageId, item.offsetInPageUnits),
            ))
        }
    }
}

@Composable
internal fun FavoriteSection(
    items: List<SavedSeries>,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    if (items.isEmpty()) return LibraryMessage("즐겨찾기한 작품이 없습니다", colors)
    ResultList(items, { "${it.id.sourceId.value}:${it.id.remoteKey}" }, colors) { series ->
        SavedEpisodeRow(series.title, series.id.sourceId.value.uppercase(), colors) {
            accept(LibraryIntent.SavedSeriesSelected(series))
        }
    }
}

@Composable
internal fun BookmarkSection(
    items: List<SavedBookmark>,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    if (items.isEmpty()) return LibraryMessage("저장한 책갈피가 없습니다", colors)
    ResultList(items, { "${it.pageId.episodeId.seriesId.sourceId.value}:${it.pageId.episodeId.seriesId.remoteKey}:${it.pageId.episodeId.remoteKey}:${it.pageId.remoteKey}" }, colors) { item ->
        SavedEpisodeRow(item.seriesTitle, "${item.pageId.episodeId.remoteKey} · ${item.pageId.remoteKey}", colors) {
            accept(LibraryIntent.SavedEpisodeSelected(
                ReadingPosition(item.pageId, item.offsetInPageUnits),
            ))
        }
    }
}

@Composable
private fun SavedEpisodeRow(title: String, subtitle: String, colors: LibraryColors, select: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = select).padding(14.dp)) {
        BasicText(title, style = bodyStyle(colors))
        BasicText(subtitle, style = hintStyle(colors))
    }
}

@Composable
internal fun SettingsSection(
    settings: ViewerSettings,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SettingRow("어두운 화면", settings.darkTheme, colors) {
            accept(LibraryIntent.DarkThemeChanged(!settings.darkTheme))
        }
        BasicText(
            "연속 세로 읽기는 원본 비율을 유지해 화면 폭에 맞춥니다.",
            Modifier.padding(14.dp),
            hintStyle(colors),
        )
    }
}

@Composable
private fun SettingRow(label: String, enabled: Boolean, colors: LibraryColors, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = click).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), bodyStyle(colors))
        BasicText(if (enabled) "켜짐" else "꺼짐", style = if (enabled) bodyStyle(colors) else hintStyle(colors))
    }
}

@Composable
private fun FavoriteAction(favorite: Boolean, colors: LibraryColors, click: () -> Unit) {
    BasicText(
        if (favorite) "★" else "☆",
        Modifier.clickable(onClick = click).padding(14.dp),
        style = sectionStyle(colors),
    )
}

@Composable
private fun <T> ResultList(
    items: List<T>,
    key: (T) -> String,
    colors: LibraryColors,
    row: @Composable (T) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = key) { item ->
            row(item)
            Spacer(Modifier.height(1.dp).fillMaxWidth().background(colors.divider))
        }
    }
}
