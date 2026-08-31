package ml.melun.mangaview.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTabTest {
    @Test
    fun storedTabUsesStableOrderAndRejectsInvalidValues() {
        assertEquals(LibraryTab.SEARCH, LibraryTab.fromStored(-1))
        assertEquals(LibraryTab.FAVORITES, LibraryTab.fromStored(2))
        assertEquals(LibraryTab.SEARCH, LibraryTab.fromStored(100))
    }
}
