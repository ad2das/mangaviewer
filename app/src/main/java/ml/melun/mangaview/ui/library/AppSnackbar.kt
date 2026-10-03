package ml.melun.mangaview.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class MessageTone { INFO, SUCCESS, ERROR }

internal data class AppMessage(
    val id: Long,
    val text: String,
    val tone: MessageTone = MessageTone.INFO,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    val long: Boolean = false,
)

/** One message at a time; a newer one replaces the visible one instead of queueing behind it. */
internal class AppMessages {
    private val mutable = MutableStateFlow<AppMessage?>(null)
    private var nextId = 0L
    val current: StateFlow<AppMessage?> = mutable.asStateFlow()

    fun show(
        text: String,
        tone: MessageTone = MessageTone.INFO,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
        long: Boolean = false,
    ) {
        mutable.value = AppMessage(++nextId, text, tone, actionLabel, action, long || action != null)
    }

    fun dismiss(id: Long) {
        if (mutable.value?.id == id) mutable.value = null
    }
}

/**
 * Floating message bar: rises from the bottom, announces itself to TalkBack, dismisses on a timer
 * or a sideways swipe, and carries at most one action.
 */
@Composable
internal fun AppSnackbarHost(messages: AppMessages, current: AppMessage?, colors: LibraryColors, modifier: Modifier) {
    AnimatedContent(
        targetState = current,
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            (slideInVertically(tween(LibraryMotion.Medium, easing = LibraryMotion.EaseOut)) { it / 2 } +
                fadeIn(tween(LibraryMotion.Fast))) togetherWith
                (slideOutVertically(tween(LibraryMotion.Fast)) { it / 3 } + fadeOut(tween(LibraryMotion.Fast)))
        },
        label = "snackbar",
    ) { message ->
        if (message == null) {
            Spacer(Modifier.fillMaxWidth())
        } else {
            LaunchedEffect(message.id) {
                delay(if (message.long) LONG_MILLIS else SHORT_MILLIS)
                messages.dismiss(message.id)
            }
            SnackbarCard(message, colors) { messages.dismiss(message.id) }
        }
    }
}

@Composable
private fun SnackbarCard(message: AppMessage, colors: LibraryColors, dismiss: () -> Unit) {
    val offset = remember(message.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(16.dp)
    val surface = if (colors.dark) Color(0xFF232A3B) else Color(0xFF1B2030)
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 52.dp)
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (abs(offset.value) / (size.width.coerceAtLeast(1f))).coerceIn(0f, 1f)
            }
            .draggable(
                rememberDraggableState { delta -> scope.launch { offset.snapTo(offset.value + delta) } },
                Orientation.Horizontal,
                onDragStopped = {
                    if (abs(offset.value) > SWIPE_DISMISS_PX) dismiss() else offset.animateTo(0f, tween(LibraryMotion.Fast))
                },
            )
            .shadow(12.dp, shape, spotColor = Color.Black.copy(alpha = 0.28f))
            .clip(shape)
            .background(surface)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 14.dp, end = if (message.actionLabel != null) 6.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToneMark(message.tone)
        Spacer(Modifier.width(10.dp))
        BasicText(
            message.text,
            Modifier.weight(1f).padding(vertical = 14.dp),
            style = bodyStyle(colors, 14).copy(color = Color.White, fontWeight = FontWeight.Medium),
        )
        if (message.actionLabel != null) {
            Box(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                    .clickable { message.action?.invoke(); dismiss() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    message.actionLabel,
                    style = bodyStyle(colors, 14).copy(color = Color(0xFFA997FF), fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

@Composable
private fun ToneMark(tone: MessageTone) {
    val (icon, tint) = when (tone) {
        MessageTone.INFO -> LibraryIcon.INFO to Color.White.copy(alpha = 0.72f)
        MessageTone.SUCCESS -> LibraryIcon.CHECK_CIRCLE to Color(0xFF34D399)
        MessageTone.ERROR -> LibraryIcon.ERROR to Color(0xFFFF6B81)
    }
    LibraryIconView(icon, tint, Modifier.size(20.dp))
}

private const val SHORT_MILLIS = 2_800L
private const val LONG_MILLIS = 4_500L
private const val SWIPE_DISMISS_PX = 160f
