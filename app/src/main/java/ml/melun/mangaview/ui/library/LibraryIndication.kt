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

/**
 * Minimal press feedback for a project without a Material dependency: draws one translucent
 * overlay while an interaction is pressed and nothing otherwise.
 */
internal fun libraryPressIndication(): IndicationNodeFactory = LibraryPressIndication

private object LibraryPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        LibraryPressNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class LibraryPressNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(color = Color.White.copy(alpha = 0.10f))
    }
}

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
