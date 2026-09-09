package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ml.melun.mangaview.data.library.RecentReading
import ml.melun.mangaview.source.SourceSeries

/** Uses local reading history, independently of the selected catalog and its loading state. */
@Composable
internal fun HomeContinuations(
    recent: List<RecentReading>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    if (recent.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicText("이어서 읽기", Modifier.weight(1f), sectionStyle(colors, 17))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                accept(LibraryIntent.DestinationSelected(MainDestination.LIBRARY))
                accept(LibraryIntent.SavedTabSelected(SavedTab.RECENT))
            }.padding(12.dp)) {
                BasicText("전체보기", style = labelStyle(colors, true))
            }
        }
        LazyRow(modifier = Modifier.semantics { contentDescription = "홈 이어보기 목록" },
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(recent.take(5), key = { "${it.series.id.sourceId.value}:${it.series.id.remoteKey}" }) { item ->
                Row(Modifier.width(296.dp).clip(RoundedCornerShape(16.dp))
                    .background(colors.card).border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                    .semantics { contentDescription = "이어보기: ${item.series.title}" }
                    .clickable { accept(LibraryIntent.ResumeEpisode(item.episodeId)) }
                    .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeriesArtwork(SourceSeries(item.series.id, item.series.title, thumbnailKey = item.series.thumbnailKey),
                        loader, colors, Modifier.width(62.dp).height(86.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(item.series.title, style = titleStyle(colors, 15),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        BasicText("마지막으로 읽던 위치부터", style = hintStyle(colors, 11))
                        Spacer(Modifier.height(8.dp))
                        BasicText("이어보기  ›", style = labelStyle(colors, true))
                    }
                }
            }
        }
    }
}
