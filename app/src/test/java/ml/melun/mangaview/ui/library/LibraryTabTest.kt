package ml.melun.mangaview.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTabTest {
    @Test
    fun storedTabUsesStableOrderAndRejectsInvalidValues() {
        assertEquals(MainDestination.HOME, MainDestination.fromStored(-1))
        assertEquals(MainDestination.LIBRARY, MainDestination.fromStored(2))
        assertEquals(MainDestination.HOME, MainDestination.fromStored(100))
    }
}
