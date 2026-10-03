package ml.melun.mangaview.ui.library

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween

/**
 * Press feedback for a project without a Material dependency: a theme-aware veil that fades in on
 * press and out on release, so a quick tap still reads on light cards as well as dark ones.
 */
internal fun libraryPressIndication(dark: Boolean): IndicationNodeFactory =
    if (dark) DarkPressIndication else LightPressIndication

private val DarkPressIndication = LibraryPressIndication(Color.White.copy(alpha = 0.10f))
private val LightPressIndication = LibraryPressIndication(Color.Black.copy(alpha = 0.07f))

private class LibraryPressIndication(private val veil: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        LibraryPressNode(interactionSource, veil)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class LibraryPressNode(
    private val interactionSource: InteractionSource,
    private val veil: Color,
) : Modifier.Node(), DrawModifierNode {
    private val strength = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> coroutineScope.launch {
                        strength.animateTo(1f, tween(PRESS_IN_MILLIS))
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> coroutineScope.launch {
                        // Let a fast tap reach full strength before fading, or it never shows.
                        strength.animateTo(1f, tween(PRESS_IN_MILLIS))
                        strength.animateTo(0f, tween(PRESS_OUT_MILLIS))
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val value = strength.value
        if (value > 0f) drawRect(color = veil.copy(alpha = veil.alpha * value))
    }
}

private const val PRESS_IN_MILLIS = 60
private const val PRESS_OUT_MILLIS = 220

/** Selection-style intents get a single light haptic tick; everything else stays silent. */
internal fun LibraryIntent.providesSelectionFeedback(): Boolean = when (this) {
    is LibraryIntent.DestinationSelected,
    is LibraryIntent.SavedTabSelected,
    is LibraryIntent.HomeTabSelected,
    is LibraryIntent.DetailTabSelected,
    is LibraryIntent.FavoriteToggled,
    -> true
    else -> false
}
