package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
import ml.melun.mangaview.data.library.RecentReading
import ml.melun.mangaview.source.SourceSeries

private val ContinuationCardShape = RoundedCornerShape(20.dp)
private val ContinuationThumbShape = RoundedCornerShape(14.dp)
private val ContinuationBadgeShape = RoundedCornerShape(8.dp)

/** Uses local reading history, independently of the selected catalog and its loading state. */
@Composable
internal fun HomeContinuations(
    recent: List<RecentReading>,
    loader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    if (recent.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier.width(4.dp).height(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accentGradient),
                )
                Spacer(Modifier.width(8.dp))
                BasicText("이어서 읽기", style = sectionStyle(colors, 19))
            }
            Box(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(colors.accentSurface)
                    .clickable {
                        accept(LibraryIntent.DestinationSelected(MainDestination.LIBRARY))
                        accept(LibraryIntent.SavedTabSelected(SavedTab.RECENT))
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("전체보기", style = labelStyle(colors, true).copy(fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
        }
        LazyRow(
            modifier = Modifier.semantics { contentDescription = "홈 이어보기 목록" },
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(recent.take(6), key = { "${it.series.id.sourceId.value}:${it.series.id.remoteKey}" }) { item ->
                Row(
                    Modifier.width(320.dp).height(118.dp)
                        .clip(ContinuationCardShape)
                        .background(colors.card)
                        .border(1.dp, colors.cardBorder, ContinuationCardShape)
                        .semantics { contentDescription = "이어보기: ${item.series.title}" }
                        .clickable { accept(LibraryIntent.ResumeEpisode(item.episodeId)) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(78.dp).height(98.dp)
                            .clip(ContinuationThumbShape)
                            .border(0.5.dp, colors.cardBorder, ContinuationThumbShape),
                    ) {
                        SeriesArtwork(
                            SourceSeries(item.series.id, item.series.title, thumbnailKey = item.series.thumbnailKey),
                            loader,
                            colors,
                            Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier.size(26.dp).align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp)
                                .clip(CircleShape).background(Color.Black.copy(alpha = 0.72f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            LibraryIconView(LibraryIcon.PLAY, Color.White, Modifier.size(11.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            item.series.title,
                            style = titleStyle(colors, 15).copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        BasicText("마지막으로 읽던 위치부터", style = hintStyle(colors, 12))
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(
                                Modifier.clip(ContinuationBadgeShape)
                                    .background(colors.accentSurface)
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                            ) {
                                BasicText(
                                    "이어보기  ›",
                                    style = labelStyle(colors, true).copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold),
                                )
                            }
                            Box(
                                Modifier.width(60.dp).height(5.dp)
                                    .clip(CircleShape)
                                    .background(colors.mutedSurface),
                            ) {
                                Box(
                                    Modifier.width(42.dp).height(5.dp)
                                        .clip(CircleShape)
                                        .background(colors.accentGradient),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}