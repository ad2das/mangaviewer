package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.offline.OfflineDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryUiActionsDismissTest {
    @Test fun pendingOfflineRemovalIsClearedFirstAndAlone() = runTest {
        val fixture = UiFixture(
            backgroundScope,
            baseState().copy(
                pendingOfflineRemoval = episode(),
                downloadSelectionVisible = true,
                sourcePickerVisible = true,
                preferencesVisible = true,
                settingsVisible = true,
                seriesMenuVisible = true,
            ),
        )

        assertTrue(fixture.actions.dismissOverlay())

        assertEquals(1, fixture.updateCount)
        assertNull(fixture.state.pendingOfflineRemoval)
        assertTrue(fixture.state.downloadSelectionVisible)
        assertTrue(fixture.state.sourcePickerVisible)
        assertTrue(fixture.state.preferencesVisible)
        assertTrue(fixture.state.settingsVisible)
        assertTrue(fixture.state.seriesMenuVisible)
    }

    @Test fun downloadSelectionIsClearedBeforeSourcePicker() = runTest {
        val fixture = UiFixture(
            backgroundScope,
            baseState().copy(
                downloadSelectionVisible = true,
                sourcePickerVisible = true,
                settingsVisible = true,
            ),
        )

        assertTrue(fixture.actions.dismissOverlay())
        assertFalse(fixture.state.downloadSelectionVisible)
        assertTrue(fixture.state.sourcePickerVisible)
        assertTrue(fixture.state.settingsVisible)

        assertTrue(fixture.actions.dismissOverlay())
        assertFalse(fixture.state.sourcePickerVisible)
        assertTrue(fixture.state.settingsVisible)

        assertEquals(2, fixture.updateCount)
    }

    @Test fun sourcePickerIsClearedWithoutClosingSettings() = runTest {
        val fixture = UiFixture(
            backgroundScope,
            baseState().copy(
                sourcePickerVisible = true,
                preferencesVisible = true,
                settingsVisible = true,
            ),
        )

        assertTrue(fixture.actions.dismissOverlay())

        assertFalse(fixture.state.sourcePickerVisible)
        assertTrue(fixture.state.preferencesVisible)
        assertTrue(fixture.state.settingsVisible)
        assertEquals(1, fixture.updateCount)
    }

    @Test fun dismissedPreferencesRestoreSettingsScreen() = runTest {
        val fixture = UiFixture(backgroundScope, baseState().copy(preferencesVisible = true))

        assertTrue(fixture.actions.dismissOverlay())

        assertFalse(fixture.state.preferencesVisible)
        assertTrue(fixture.state.settingsVisible)
        assertEquals(1, fixture.updateCount)
    }

    @Test fun settingsAndSeriesMenuCloseInOrder() = runTest {
        val fixture = UiFixture(
            backgroundScope,
            baseState().copy(settingsVisible = true, seriesMenuVisible = true),
        )

        assertTrue(fixture.actions.dismissOverlay())
        assertFalse(fixture.state.settingsVisible)
        assertTrue(fixture.state.seriesMenuVisible)

        assertTrue(fixture.actions.dismissOverlay())
        assertFalse(fixture.state.seriesMenuVisible)
        assertEquals(2, fixture.updateCount)
    }

    @Test fun nothingOpenLeavesStateUntouched() = runTest {
        val fixture = UiFixture(backgroundScope, baseState())

        assertFalse(fixture.actions.dismissOverlay())

        assertEquals(0, fixture.updateCount)
        assertEquals(baseState(), fixture.state)
    }

    private class UiFixture(scope: CoroutineScope, initial: LibraryState) {
        var state: LibraryState = initial
        var updateCount: Int = 0
        val actions = LibraryUiActions(
            scope = scope,
            actions = stub(LibraryActions::class.java),
            downloads = stub(OfflineDownloadManager::class.java),
            current = { state },
            update = { transform -> state = transform(state); updateCount++ },
            emit = {},
        )
    }

    private fun baseState(): LibraryState = LibraryState(
        query = "",
        sources = emptyList(),
        selectedSourceId = SourceId("test"),
    )

    private fun episode(): EpisodeId = EpisodeId(SeriesId(SourceId("test"), "series"), "1")
}

// Overlay dismissal never reads these collaborators, whose real constructors need Room/DataStore.
private fun <T> stub(type: Class<T>): T {
    val unsafe = Class.forName("sun.misc.Unsafe")
    val instance = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return type.cast(unsafe.getMethod("allocateInstance", Class::class.java).invoke(instance, type))
}
