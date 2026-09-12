package ml.melun.mangaview.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LibraryScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    accept: (LibraryIntent) -> Unit,
    account: ml.melun.mangaview.account.AccountState = ml.melun.mangaview.account.AccountState(),
    updateAvailable: Boolean = false,
) {
    val colors = libraryColors(state.saved.settings.darkTheme)
    val focus = LocalFocusManager.current
    LaunchedEffect(state.settingsVisible) { if (state.settingsVisible) focus.clearFocus() }
    val genreScroll = androidx.compose.runtime.saveable.rememberSaveable(
        state.selectedSourceId, state.homeKind, state.selectedGenre, state.genreStatusFilter,
        saver = androidx.compose.foundation.lazy.LazyListState.Saver,
    ) {
        androidx.compose.foundation.lazy.LazyListState()
    }
    val detailVisible = state.activeSeries != null
    val genreCatalogVisible = state.selectedGenre != null
    BackHandler(
        enabled = detailVisible || genreCatalogVisible || state.settingsVisible ||
            state.preferencesVisible || state.downloadSelectionVisible,
    ) { accept(LibraryIntent.Back) }

    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        if (detailVisible) {
            SeriesDetailScreen(state, artworkLoader, colors, accept)
        } else if (genreCatalogVisible) {
            GenreCatalogScreen(state, artworkLoader, colors, genreScroll, accept)
        } else {
            MainShell(state, artworkLoader, colors, accept, updateAvailable)
        }
        if (state.seriesMenuVisible) SeriesActionsOverlay(state, colors, accept)
        if (state.downloadSelectionVisible) DownloadSelectionOverlay(state, colors, accept)
        if (state.pendingOfflineRemoval != null) OfflineRemovalConfirmation(state, colors, accept)
        if (state.settingsVisible) SettingsOverlay(colors, accept, account, updateAvailable)
        if (state.preferencesVisible) PreferencesOverlay(state, colors, accept)
    }
}

@Composable
private fun MainShell(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
    updateAvailable: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        MainTopBar(state, colors, accept, updateAvailable)
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
private fun MainTopBar(
    state: LibraryState,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
    updateAvailable: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.destination) {
            MainDestination.HOME -> {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        buildAnnotatedString {
                            append("Manga")
                            withStyle(SpanStyle(color = colors.accent)) {
                                append("View")
                            }
                        },
                        style = displayStyle(colors, 24).copy(fontWeight = FontWeight.Black),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(colors.accentSurface)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        BasicText(
                            "PLUS",
                            style = badgeStyle(colors, 10).copy(fontWeight = FontWeight.ExtraBold),
                        )
                    }
                }
            }
            MainDestination.SEARCH -> {
                BasicText(
                    "검색",
                    Modifier.weight(1f),
                    titleStyle(colors, 22).copy(fontWeight = FontWeight.Bold),
                )
            }
            MainDestination.LIBRARY -> {
                BasicText(
                    "내 보관함",
                    Modifier.weight(1f),
                    titleStyle(colors, 22).copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        val source = state.sources.firstOrNull { it.id == state.selectedSourceId }
        val next = state.sources.let { options ->
            val index = options.indexOfFirst { it.id == state.selectedSourceId }
            options.getOrNull((index + 1).mod(options.size.coerceAtLeast(1)))
        }

        // Provider Selector pill chip
        Row(
            Modifier.height(38.dp)
                .shadow(2.dp, RoundedCornerShape(19.dp), spotColor = Color.Black.copy(alpha = 0.05f))
                .clip(RoundedCornerShape(19.dp))
                .background(colors.card)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(19.dp))
                .semantics { contentDescription = source?.label ?: "" }
                .clickable { next?.let { accept(LibraryIntent.SourceSelected(it.id)) } }
                .padding(start = 7.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val art = LegacySiteArtwork.forSource(source?.id?.value)
            Image(art, null, Modifier.size(22.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(6.dp))
            BasicText(
                source?.label ?: "SOURCE",
                style = labelStyle(colors, true).copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
        }

        Spacer(Modifier.width(10.dp))

        // Profile / Account icon
        Box(
            Modifier.size(40.dp)
                .semantics { contentDescription = "계정" }
                .shadow(2.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.05f))
                .clip(CircleShape)
                .background(colors.card)
                .border(1.dp, colors.cardBorder, CircleShape)
                .clickable { accept(LibraryIntent.ToggleSettings) },
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(LibraryIcon.PROFILE, colors.accent, Modifier.size(22.dp))
            if (updateAvailable) {
                Box(
                    Modifier.size(9.dp)
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 2.dp)
                        .clip(CircleShape)
                        .background(colors.favoriteActive),
                )
            }
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
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(68.dp)
            .shadow(14.dp, RoundedCornerShape(28.dp), spotColor = Color.Black.copy(alpha = 0.20f))
            .clip(RoundedCornerShape(28.dp))
            .background(colors.card)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(28.dp)),
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
        Modifier.weight(1f).fillMaxHeight()
            .semantics { contentDescription = "하단 ${item.label}" }
            .clickable { accept(LibraryIntent.DestinationSelected(item)) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(width = 58.dp, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (active) colors.accentSurface else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(icon, if (active) colors.accent else colors.secondary, Modifier.size(22.dp))
        }
        Spacer(Modifier.height(3.dp))
        BasicText(
            item.label,
            style = labelStyle(colors, active).copy(
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            ),
        )
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
        modifier.shadow(4.dp, RoundedCornerShape(16.dp), spotColor = colors.accent.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(16.dp))
            .background(colors.accentGradient)
            .clickable(onClick = click)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = bodyStyle(colors).copy(color = Color.White, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
internal fun LibraryMessage(value: String, colors: LibraryColors, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(76.dp)
                    .clip(CircleShape)
                    .background(colors.mutedSurface),
                contentAlignment = Alignment.Center,
            ) {
                LibraryIconView(LibraryIcon.SEARCH, colors.muted, Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            BasicText(value, style = hintStyle(colors, 15).copy(fontWeight = FontWeight.Medium))
        }
    }
}