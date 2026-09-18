package ml.melun.mangaview.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkPlaceholderTest {
    @Test fun takesFirstLetterOrDigitAndSkipsLeadingPunctuation() {
        assertEquals("취", artworkPlaceholderLabel("취뽀도 없는 회귀"))
        assertEquals("N", artworkPlaceholderLabel("\"NON TUA\""))
        assertEquals("1", artworkPlaceholderLabel("  1인칭 시점"))
    }

    @Test fun emptyOrPunctuationOnlyTitlesProduceNoLabel() {
        assertEquals("", artworkPlaceholderLabel("   "))
        assertEquals("", artworkPlaceholderLabel("!!! ..."))
        assertEquals("", artworkPlaceholderLabel(""))
    }
}
