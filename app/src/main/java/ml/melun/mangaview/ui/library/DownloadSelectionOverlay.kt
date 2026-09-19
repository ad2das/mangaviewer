package ml.melun.mangaview.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(episodes, key = { it.id.remoteKey }) { episode ->
                val active = episode.id.remoteKey in selected
                DownloadChoice(episode, active, colors) {
                    selected = if (active) selected - episode.id.remoteKey else selected + episode.id.remoteKey
                }
            }
        }
        DownloadControls(
            colors,
            selectedCount = selected.size,
            onSelected = { confirmation = episodes.filter { it.id.remoteKey in selected } },
            onAll = { confirmation = episodes },
        )
    }
    confirmation?.let { chosen ->
        DownloadConfirmation(
            series.title,
            chosen.size,
            colors,
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
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp)
                .semantics { contentDescription = "뒤로" }
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = back),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.BACK, colors.secondary, Modifier.size(24.dp))
        }
        Spacer(Modifier.width(6.dp))
        BasicText("오프라인 저장", style = titleStyle(colors, 20).copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun DownloadChoice(episode: SourceEpisode, selected: Boolean, colors: LibraryColors, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp)
            .shadow(1.dp, RoundedCornerShape(14.dp), spotColor = Color.Black.copy(alpha = 0.04f))
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(1.dp, if (selected) colors.accent else colors.outline, RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.Checkbox, onClick = click)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp)
                .clip(CircleShape)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(1.5.dp, if (selected) colors.accent else colors.muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                BasicText("✓", style = bodyStyle(colors, 13).copy(color = Color.White, fontWeight = FontWeight.Black))
            }
        }
        Spacer(Modifier.width(14.dp))
        BasicText(
            episode.title,
            Modifier.weight(1f),
            bodyStyle(colors, 14).copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
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
        Modifier.fillMaxWidth().height(80.dp)
            .shadow(8.dp, spotColor = Color.Black.copy(alpha = 0.10f))
            .background(colors.card)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selectedCount > 0) colors.accent else colors.mutedSurface)
                .clickable { if (selectedCount > 0) onSelected() },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                "선택 저장",
                style = bodyStyle(colors, 14).copy(
                    color = if (selectedCount > 0) Color.White else colors.muted,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        Box(
            Modifier.weight(1f).height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                "${selectedCount}개 선택",
                style = bodyStyle(colors, 13).copy(color = colors.secondary, fontWeight = FontWeight.SemiBold),
            )
        }
        OutlinedDownloadButton("전체 저장", colors, Modifier.weight(1f), onAll)
    }
}

@Composable
private fun OutlinedDownloadButton(label: String, colors: LibraryColors, modifier: Modifier, click: (() -> Unit)?) {
    val interaction = if (click == null) modifier else modifier.clickable(onClick = click)
    Box(
        interaction.height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.mutedSurface)
            .border(1.dp, colors.outline, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = bodyStyle(colors, 13).copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun DownloadConfirmation(
    title: String,
    count: Int,
    colors: LibraryColors,
    confirm: () -> Unit,
    dismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { dismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp).fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { } }
                .shadow(16.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(colors.card)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(24.dp),
        ) {
            BasicText(title + " 을(를) 오프라인 저장하시겠습니까?\n[ 총 " + count + "화 ]", style = bodyStyle(colors, 16).copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedDownloadButton("취소", colors, Modifier.width(90.dp), dismiss)
                Spacer(Modifier.width(10.dp))
                LibraryAction("저장", colors, Modifier.width(90.dp).height(48.dp), click = confirm)
            }
        }
    }
}
