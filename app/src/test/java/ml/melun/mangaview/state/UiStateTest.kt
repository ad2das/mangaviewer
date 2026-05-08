package ml.melun.mangaview.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {
    @Test
    fun contentCarriesValue() {
        val state: UiState<String> = UiState.Content("loaded")
        assertTrue(state is UiState.Content)
        assertEquals("loaded", (state as UiState.Content).value)
    }

    @Test
    fun errorCarriesFailureType() {
        val failure = MangaFailure.NetworkError()
        val state: UiState<String> = UiState.Error(failure)
        assertTrue((state as UiState.Error).failure is MangaFailure.NetworkError)
    }
}
