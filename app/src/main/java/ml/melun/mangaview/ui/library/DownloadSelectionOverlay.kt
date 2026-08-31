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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ml.melun.mangaview.source.SourceEpisode

@Composable
internal fun DownloadSelectionOverlay(
    state: LibraryState,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val series = state.activeSeries ?: return
    val episodes = (state.content as? LibraryContent.Episodes)?.items.orEmpty()
    var selected by remember(series.id) { mutableStateOf<Set<String>>(emptySet()) }
    var confirmation by remember(series.id) { mutableStateOf<List<SourceEpisode>?>(null) }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        DownloadToolbar(colors) { accept(LibraryIntent.ToggleDownloadSelection) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
            items(episodes, key = { it.id.remoteKey }) { episode ->
                val active = episode.id.remoteKey in selected
                DownloadChoice(episode, active, colors) {
                    selected = if (active) selected - episode.id.remoteKey else selected + episode.id.remoteKey
                }
            }
        }
        DownloadControls(colors,
            selectedCount = selected.size,
            onSelected = { confirmation = episodes.filter { it.id.remoteKey in selected } },
            onAll = { confirmation = episodes },
        )
    }
    confirmation?.let { chosen ->
        DownloadConfirmation(series.title, chosen.size, colors,
            confirm = {
                accept(LibraryIntent.DownloadEpisodes(series, chosen))
                confirmation = null
            },
            dismiss = { confirmation = null },
        )
    }
}

@Composable
private fun DownloadToolbar(colors: LibraryColors, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = back), contentAlignment = Alignment.Center) {
            LibraryIconView(LibraryIcon.BACK, colors.secondary, Modifier.size(26.dp))
        }
        BasicText("오프라인 저장", style = titleStyle(colors, 20))
    }
}

@Composable
private fun DownloadChoice(episode: SourceEpisode, selected: Boolean, colors: LibraryColors, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(76.dp)
            .clip(RoundedCornerShape(10.dp)).background(colors.card).clickable(onClick = click).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(if (selected) colors.accent else colors.mutedSurface)
                .border(1.dp, if (selected) colors.accent else colors.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) BasicText("✓", style = bodyStyle(colors, 14).copy(color = androidx.compose.ui.graphics.Color.White))
        }
        BasicText(
            episode.title,
            Modifier.weight(1f).padding(start = 16.dp),
            bodyStyle(colors, 15),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DownloadControls(
    colors: LibraryColors,
    selectedCount: Int,
    onSelected: () -> Unit,
    onAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(88.dp).background(colors.card).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryAction("선택 다운로드", colors, Modifier.weight(1f).height(52.dp)) { if (selectedCount > 0) onSelected() }
        OutlinedDownloadButton("${selectedCount}개 선택", colors, Modifier.weight(1f), null)
        OutlinedDownloadButton("모두 다운", colors, Modifier.weight(1f), onAll)
    }
}

@Composable
private fun OutlinedDownloadButton(label: String, colors: LibraryColors, modifier: Modifier, click: (() -> Unit)?) {
    val interaction = if (click == null) modifier else modifier.clickable(onClick = click)
    Box(
        interaction.height(52.dp).clip(RoundedCornerShape(10.dp)).background(colors.card)
            .border(1.dp, colors.outline, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = bodyStyle(colors, 12)) }
}

@Composable
private fun DownloadConfirmation(
    title: String,
    count: Int,
    colors: LibraryColors,
    confirm: () -> Unit,
    dismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x88000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(28.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.card)
                .padding(22.dp),
        ) {
            BasicText("$title 을(를) 다운로드 하시겠습니까?\n[ 총 ${count}화 ]", style = bodyStyle(colors, 16))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedDownloadButton("아니오", colors, Modifier.width(90.dp), dismiss)
                Spacer(Modifier.width(10.dp))
                LibraryAction("네", colors, Modifier.width(90.dp).height(52.dp), confirm)
            }
        }
    }
}
