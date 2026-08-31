package ml.melun.mangaview.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    accept: (LibraryIntent) -> Unit,
) {
    val colors = libraryColors(state.saved.settings.darkTheme)
    val detailVisible = state.activeSeries != null
    BackHandler(
        enabled = detailVisible || state.settingsVisible || state.preferencesVisible || state.downloadSelectionVisible,
    ) { accept(LibraryIntent.Back) }
    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        if (detailVisible) {
            SeriesDetailScreen(state, artworkLoader, colors, accept)
        } else {
            MainShell(state, artworkLoader, colors, accept)
        }
        if (state.seriesMenuVisible) SeriesActionsOverlay(state, colors, accept)
        if (state.downloadSelectionVisible) DownloadSelectionOverlay(state, colors, accept)
        if (state.pendingOfflineRemoval != null) OfflineRemovalConfirmation(state, colors, accept)
        if (state.settingsVisible) SettingsOverlay(colors, accept)
        if (state.preferencesVisible) PreferencesOverlay(state, colors, accept)
    }
}

@Composable
private fun MainShell(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        MainTopBar(state, colors, accept)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.destination) {
                MainDestination.HOME -> HomeScreen(state, artworkLoader, colors, accept)
                MainDestination.SEARCH -> SearchScreen(state, artworkLoader, colors, accept)
                MainDestination.LIBRARY -> SavedLibraryScreen(state, artworkLoader, colors, accept)
            }
        }
        MainBottomNavigation(state.destination, colors, accept)
    }
}

@Composable
private fun MainTopBar(state: LibraryState, colors: LibraryColors, accept: (LibraryIntent) -> Unit) {
    val title = when (state.destination) {
        MainDestination.HOME -> "MangaView"
        MainDestination.SEARCH -> "검색"
        MainDestination.LIBRARY -> "내 보관함"
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            title,
            Modifier.weight(1f),
            titleStyle(colors, 22).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        )
        val source = state.sources.firstOrNull { it.id == state.selectedSourceId }
        val next = state.sources.let { options ->
            val index = options.indexOfFirst { it.id == state.selectedSourceId }
            options.getOrNull((index + 1).mod(options.size.coerceAtLeast(1)))
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable {
                next?.let { accept(LibraryIntent.SourceSelected(it.id)) }
            },
            contentAlignment = Alignment.Center,
        ) {
            val art = if (source?.id?.value == "ntk") LegacySiteArtwork.ntk else LegacySiteArtwork.wfwf
            Image(art, source?.label, Modifier.size(34.dp), contentScale = ContentScale.Fit)
        }
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier.size(40.dp).semantics { contentDescription = "계정" }
                .clip(CircleShape).clickable { accept(LibraryIntent.ToggleSettings) },
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.PROFILE, colors.accent, Modifier.size(30.dp))
        }
    }
}

@Composable
private fun MainBottomNavigation(
    selected: MainDestination,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)
            .height(64.dp).shadow(4.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
            .background(colors.card).border(1.dp, colors.outline, RoundedCornerShape(24.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavigationItem(MainDestination.HOME, LibraryIcon.HOME, selected, colors, accept)
        NavigationItem(MainDestination.SEARCH, LibraryIcon.SEARCH, selected, colors, accept)
        NavigationItem(MainDestination.LIBRARY, LibraryIcon.LIBRARY, selected, colors, accept)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavigationItem(
    item: MainDestination,
    icon: LibraryIcon,
    selected: MainDestination,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    val active = item == selected
    Column(
        Modifier.weight(1f).height(56.dp).semantics { contentDescription = "하단 ${item.label}" }
            .clickable { accept(LibraryIntent.DestinationSelected(item)) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(width = 64.dp, height = 32.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (active) colors.accentSurface else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(icon, if (active) colors.accent else colors.secondary, Modifier.size(25.dp))
        }
        BasicText(item.label, style = labelStyle(colors, active))
    }
}

@Composable
internal fun LibraryAction(
    label: String,
    colors: LibraryColors,
    modifier: Modifier = Modifier,
    click: () -> Unit,
) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(colors.accent).clickable(onClick = click)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = bodyStyle(colors).copy(color = androidx.compose.ui.graphics.Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
    }
}

@Composable
internal fun LibraryMessage(value: String, colors: LibraryColors, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LibraryIconView(LibraryIcon.SEARCH, colors.muted, Modifier.size(58.dp))
            Spacer(Modifier.height(18.dp))
            BasicText(value, style = hintStyle(colors, 15))
        }
    }
}
