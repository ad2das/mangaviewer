package ml.melun.mangaview.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ml.melun.mangaview.app.SourceOption

@Composable
internal fun LibraryScreen(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    accept: (LibraryIntent) -> Unit,
    account: ml.melun.mangaview.account.AccountState = ml.melun.mangaview.account.AccountState(),
    updateAvailable: Boolean = false,
    onOpenCrashReport: () -> Unit = {},
) {
    val colors = rememberLibraryColors(state.saved.settings.darkTheme)
    val focus = LocalFocusManager.current
    val screenState = rememberSaveableStateHolder()
    LaunchedEffect(state.destination, state.activeSeries, state.settingsVisible, state.sourcePickerVisible) {
        focus.clearFocus()
    }
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
            state.preferencesVisible || state.downloadSelectionVisible || state.sourcePickerVisible ||
            state.savedSelection.isNotEmpty() || state.pendingOfflineRemoval != null ||
            state.destination != MainDestination.HOME,
    ) { accept(LibraryIntent.Back) }

    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        val layer = when {
            detailVisible -> LibraryLayer.Detail
            genreCatalogVisible -> LibraryLayer.Genre
            else -> LibraryLayer.Shell
        }
        AnimatedContent(
            targetState = layer,
            transitionSpec = { layerTransition(initialState, targetState) },
            label = "libraryLayer",
        ) { target ->
            when (target) {
                LibraryLayer.Detail -> SeriesDetailScreen(state, artworkLoader, colors, accept)
                LibraryLayer.Genre -> GenreCatalogScreen(state, artworkLoader, colors, genreScroll, accept)
                LibraryLayer.Shell -> MainShell(state, artworkLoader, colors, accept, updateAvailable, screenState)
            }
        }
        AnimatedVisibility(
            visible = state.seriesMenuVisible,
            enter = fadeIn(tween(LibraryMotion.Fast)) +
                slideInVertically(tween(LibraryMotion.Medium, easing = LibraryMotion.EaseOut)) { -it / 8 },
            exit = fadeOut(tween(LibraryMotion.Fast)) +
                slideOutVertically(tween(LibraryMotion.Fast)) { -it / 12 },
            label = "seriesMenu",
        ) { SeriesActionsOverlay(state, colors, accept) }
        AnimatedVisibility(
            visible = state.downloadSelectionVisible,
            enter = fadeIn(tween(LibraryMotion.Medium)) +
                slideInHorizontally(tween(LibraryMotion.Slow, easing = LibraryMotion.EaseOut)) { it },
            exit = fadeOut(tween(LibraryMotion.Fast)) +
                slideOutHorizontally(tween(LibraryMotion.Fast)) { it / 4 },
            label = "downloadSelection",
        ) { DownloadSelectionOverlay(state, colors, accept) }
        AnimatedVisibility(
            visible = state.pendingOfflineRemoval != null,
            enter = fadeIn(tween(LibraryMotion.Fast)) +
                scaleIn(tween(LibraryMotion.Medium, easing = LibraryMotion.EaseOut), initialScale = 0.94f),
            exit = fadeOut(tween(LibraryMotion.Fast)) +
                scaleOut(tween(LibraryMotion.Fast), targetScale = 0.98f),
            label = "offlineRemoval",
        ) { OfflineRemovalConfirmation(state, colors, accept) }
        AnimatedVisibility(
            visible = state.settingsVisible,
            enter = fadeIn(tween(LibraryMotion.Medium)) +
                slideInVertically(tween(LibraryMotion.Medium, easing = LibraryMotion.EaseOut)) { it / 4 },
            exit = fadeOut(tween(LibraryMotion.Fast)) +
                slideOutVertically(tween(LibraryMotion.Fast)) { it / 6 },
            label = "settings",
        ) { SettingsOverlay(colors, accept, account, updateAvailable) }
        AnimatedVisibility(
            visible = state.preferencesVisible,
            enter = fadeIn(tween(LibraryMotion.Medium)) +
                slideInHorizontally(tween(LibraryMotion.Slow, easing = LibraryMotion.EaseOut)) { it / 3 },
            exit = fadeOut(tween(LibraryMotion.Fast)) +
                slideOutHorizontally(tween(LibraryMotion.Fast)) { it / 4 },
            label = "preferences",
        ) { PreferencesOverlay(state, colors, accept, onOpenCrashReport) }
        AnimatedVisibility(
            visible = state.sourcePickerVisible,
            enter = fadeIn(tween(LibraryMotion.Fast)) +
                scaleIn(tween(LibraryMotion.Medium, easing = LibraryMotion.EaseOut), initialScale = 0.96f),
            exit = fadeOut(tween(LibraryMotion.Fast)) +
                scaleOut(tween(LibraryMotion.Fast), targetScale = 0.98f),
            label = "sourcePicker",
        ) { SourcePickerOverlay(state, colors, accept) }
    }
}

/** Shell -> Genre -> Detail push direction; the reverse slides back out. */
private fun AnimatedContentTransitionScope<LibraryLayer>.layerTransition(
    from: LibraryLayer,
    to: LibraryLayer,
): ContentTransform {
    val forward = to.ordinal > from.ordinal
    val enter = slideInHorizontally(tween(LibraryMotion.Slow, easing = LibraryMotion.EaseOut)) { full ->
        if (forward) full / 5 else -full / 10
    } + fadeIn(tween(LibraryMotion.Medium, easing = LibraryMotion.EaseOut))
    val exit = slideOutHorizontally(tween(LibraryMotion.Slow, easing = LibraryMotion.EaseOut)) { full ->
        if (forward) -full / 10 else full / 5
    } + fadeOut(tween(LibraryMotion.Fast))
    return enter togetherWith exit
}

private enum class LibraryLayer { Shell, Genre, Detail }

/** Bottom tabs cross-fade with a small horizontal shift in tab order direction. */
private fun AnimatedContentTransitionScope<MainDestination>.tabTransition(
    from: MainDestination,
    to: MainDestination,
): ContentTransform {
    val forward = to.ordinal > from.ordinal
    val enter = slideInHorizontally(tween(LibraryMotion.Slow, easing = LibraryMotion.EaseOut)) { full ->
        if (forward) full / 14 else -full / 14
    } + fadeIn(tween(LibraryMotion.Medium, easing = LibraryMotion.EaseOut))
    val exit = slideOutHorizontally(tween(LibraryMotion.Medium, easing = LibraryMotion.EaseInOut)) { full ->
        if (forward) -full / 14 else full / 14
    } + fadeOut(tween(LibraryMotion.Fast))
    return enter togetherWith exit
}

@Composable
private fun MainShell(
    state: LibraryState,
    artworkLoader: SeriesArtworkLoader,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
    updateAvailable: Boolean,
    screenState: SaveableStateHolder,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        MainTopBar(state, colors, accept, updateAvailable)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = state.destination,
                transitionSpec = { tabTransition(initialState, targetState) },
                label = "mainTab",
            ) { destination ->
                screenState.SaveableStateProvider(destination) {
                    when (destination) {
                        MainDestination.HOME -> HomeScreen(state, artworkLoader, colors, accept)
                        MainDestination.SEARCH -> SearchScreen(state, artworkLoader, colors, accept)
                        MainDestination.LIBRARY -> SavedLibraryScreen(state, artworkLoader, colors, accept)
                    }
                }
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The brand title and the PLUS badge need room; on narrow phones the source chip
        // collapses to its logo so the title stays on one line instead of wrapping.
        val compact = maxWidth < 400.dp
        Row(
            Modifier.fillMaxWidth().height(64.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainDestinationTitle(state.destination, colors, Modifier.weight(1f), compact)
            val source = state.sources.firstOrNull { it.id == state.selectedSourceId }
            MainSourceChip(source, colors, accept, compact)
            Spacer(Modifier.width(10.dp))
            MainAccountButton(colors, accept, updateAvailable)
        }
    }
}

@Composable
private fun MainDestinationTitle(
    destination: MainDestination,
    colors: LibraryColors,
    modifier: Modifier,
    compact: Boolean,
) {
    when (destination) {
        MainDestination.HOME -> {
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    buildAnnotatedString {
                        append("Manga")
                        withStyle(SpanStyle(color = colors.accent)) {
                            append("View")
                        }
                    },
                    modifier = Modifier.weight(1f, fill = false),
                    style = displayStyle(colors, if (compact) 22 else 24).copy(fontWeight = FontWeight.Black),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                        maxLines = 1,
                    )
                }
            }
        }
        MainDestination.SEARCH -> {
            BasicText(
                "검색",
                modifier,
                titleStyle(colors, 22).copy(fontWeight = FontWeight.Bold),
            )
        }
        MainDestination.LIBRARY -> {
            BasicText(
                "보관함",
                modifier,
                titleStyle(colors, 22).copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun MainSourceChip(
    source: SourceOption?,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
    compact: Boolean,
) {
    Row(
        Modifier.height(48.dp)
            .semantics { contentDescription = source?.let { "사이트: ${it.label}" } ?: "사이트 선택" }
            .padding(vertical = 5.dp)
            .shadow(2.dp, RoundedCornerShape(19.dp), spotColor = Color.Black.copy(alpha = 0.05f))
            .clip(RoundedCornerShape(19.dp))
            .background(colors.card)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(19.dp))
            .clickable { accept(LibraryIntent.ToggleSourcePicker) }
            .padding(start = 7.dp, end = if (compact) 9.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val art = LegacySiteArtwork.forSource(source?.id?.value)
        Image(art, null, Modifier.size(22.dp), contentScale = ContentScale.Fit)
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            BasicText(
                source?.label ?: "SOURCE",
                style = labelStyle(colors, true).copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MainAccountButton(colors: LibraryColors, accept: (LibraryIntent) -> Unit, updateAvailable: Boolean) {
    Box(
        Modifier.size(48.dp)
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
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
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
    val pill by animateColorAsState(
        targetValue = if (active) colors.accentSurface else Color.Transparent,
        animationSpec = tween(LibraryMotion.Fast),
        label = "navPill",
    )
    val iconColor by animateColorAsState(
        targetValue = if (active) colors.accent else colors.secondary,
        animationSpec = tween(LibraryMotion.Fast),
        label = "navIcon",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (active) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "navIconScale",
    )
    Column(
        Modifier.weight(1f).fillMaxHeight()
            .semantics { contentDescription = "하단 ${item.label}" }
            .selectable(selected = active, role = Role.Tab) { accept(LibraryIntent.DestinationSelected(item)) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(width = 58.dp, height = 32.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
                .clip(RoundedCornerShape(16.dp))
                .background(pill),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconView(icon, iconColor, Modifier.size(22.dp))
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
    enabled: Boolean = true,
    click: () -> Unit,
) {
    Box(
        modifier.shadow(4.dp, RoundedCornerShape(16.dp), spotColor = colors.accent.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) colors.accentGradient else androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(colors.mutedSurface, colors.mutedSurface)))
            .clickable(enabled = enabled, role = Role.Button, onClick = click)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = bodyStyle(colors).copy(color = if (enabled) Color.White else colors.secondary, fontWeight = FontWeight.Bold),
            maxLines = 1,
            softWrap = false,
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

@Composable
private fun SourcePickerOverlay(
    state: LibraryState,
    colors: LibraryColors,
    accept: (LibraryIntent) -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .semantics { contentDescription = "사이트 선택 닫기" }
            .clickable { accept(LibraryIntent.ToggleSourcePicker) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp).fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(22.dp), spotColor = Color.Black.copy(alpha = 0.2f))
                .clip(RoundedCornerShape(22.dp))
                .background(colors.card)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(22.dp))
                .clickable {}
                .padding(vertical = 10.dp),
        ) {
            BasicText(
                "사이트 선택",
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                labelStyle(colors, true).copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            state.sources.forEach { option ->
                SourcePickerRow(
                    option = option,
                    selected = option.id == state.selectedSourceId,
                    colors = colors,
                ) { accept(LibraryIntent.SourceSelected(option.id)) }
            }
        }
    }
}

@Composable
private fun SourcePickerRow(
    option: SourceOption,
    selected: Boolean,
    colors: LibraryColors,
    click: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) colors.accentSurface else Color.Transparent)
            .clickable(onClick = click)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            LegacySiteArtwork.forSource(option.id.value),
            null,
            Modifier.size(24.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(12.dp))
        BasicText(
            option.label,
            Modifier.weight(1f),
            bodyStyle(colors, 15).copy(
                color = if (selected) colors.accent else colors.text,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
        )
        if (selected) {
            LibraryIconView(LibraryIcon.CHECK, colors.accent, Modifier.size(18.dp))
        }
    }
}
