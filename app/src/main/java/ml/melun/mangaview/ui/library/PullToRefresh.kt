package ml.melun.mangaview.ui.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * Pull-to-refresh without a Material dependency. Overscroll at the top of the wrapped list pulls
 * a spinner down with resistance; crossing the threshold ticks once, and releasing past it calls
 * [onRefresh]. The spinner stays parked while [refreshing] is true, then slides back up.
 */
@Composable
internal fun PullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    colors: LibraryColors,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { TRIGGER.toPx() }
    val haptics = LocalHapticFeedback.current
    val state = remember { PullState() }
    val latestRefresh by rememberUpdatedState(onRefresh)
    // Only a refresh this component started parks the spinner; background loads stay silent.
    var requested by remember { mutableStateOf(false) }
    val parked = requested && refreshing
    LaunchedEffect(refreshing) { if (!refreshing) requested = false }
    LaunchedEffect(parked) {
        if (state.dragging) return@LaunchedEffect
        animate(state.distance, if (parked) threshold else 0f, animationSpec = tween(LibraryMotion.Medium)) { value, _ ->
            state.distance = value
        }
    }
    val connection = remember(threshold) {
        PullConnection(state, threshold, { refreshing && requested }, {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }) {
            requested = true
            latestRefresh()
        }
    }
    Box(modifier.nestedScroll(connection)) {
        content()
        if (state.distance > 0f || parked) {
            RefreshSpinner(state.distance / threshold, parked, colors, Modifier.align(Alignment.TopCenter))
        }
    }
}

private class PullState {
    var distance by mutableFloatStateOf(0f)
    var dragging = false
    var armed = false
}

private class PullConnection(
    private val state: PullState,
    private val threshold: Float,
    private val busy: () -> Boolean,
    private val tick: () -> Unit,
    private val refresh: () -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Scrolling back up first retracts the spinner before the list moves.
        if (source != NestedScrollSource.UserInput || available.y >= 0f || state.distance <= 0f || busy()) {
            return Offset.Zero
        }
        val consumed = maxOf(available.y, -state.distance / RESISTANCE)
        move(consumed * RESISTANCE)
        return Offset(0f, consumed)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f || busy()) return Offset.Zero
        move(available.y * RESISTANCE)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (state.distance <= 0f) return Velocity.Zero
        state.dragging = false
        val trigger = state.distance >= threshold && !busy()
        state.armed = false
        if (trigger) refresh()
        animate(state.distance, if (trigger) threshold else 0f, animationSpec = tween(LibraryMotion.Medium)) { value, _ ->
            state.distance = value
        }
        return available
    }

    private fun move(delta: Float) {
        state.dragging = true
        state.distance = (state.distance + delta).coerceIn(0f, threshold * MAX_STRETCH)
        val armed = state.distance >= threshold
        if (armed && !state.armed) tick()
        state.armed = armed
    }
}

@Composable
private fun RefreshSpinner(progress: Float, spinning: Boolean, colors: LibraryColors, modifier: Modifier) {
    val density = LocalDensity.current
    val spin = rememberInfiniteTransition(label = "refreshSpin")
    val turn by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(SPIN_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "refreshTurn",
    )
    val travel = with(density) { TRIGGER.toPx() }
    Box(
        modifier.graphicsLayer {
            translationY = progress.coerceAtMost(MAX_STRETCH) * travel - with(density) { SIZE.toPx() }
            alpha = progress.coerceIn(0f, 1f)
            val grow = 0.6f + 0.4f * progress.coerceIn(0f, 1f)
            scaleX = grow
            scaleY = grow
        }
            .size(SIZE)
            .semantics { contentDescription = if (spinning) "새로고침 중" else "당겨서 새로고침" }
            .shadow(6.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(CircleShape)
            .background(colors.card)
            .border(1.dp, colors.cardBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        LibraryIconView(
            LibraryIcon.REFRESH,
            if (progress >= 1f || spinning) colors.accent else colors.secondary,
            Modifier.size(22.dp).graphicsLayer { rotationZ = if (spinning) turn else progress * 270f },
        )
    }
}

private val TRIGGER = 80.dp
private val SIZE = 40.dp
private const val RESISTANCE = 0.5f
private const val MAX_STRETCH = 1.6f
private const val SPIN_MILLIS = 900
