package ml.melun.mangaview.ui.library

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.activity.MainActivity
import ml.melun.mangaview.core.*
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run with the external database backup/restore harness; only this fixture is removed by the UI. */
@RunWith(AndroidJUnit4::class)
class SavedLibraryRemovalDeviceTest {
    @Test fun longPressCancellationAndAllFourRemovalScopes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as ViewerApplication).graph
        check(!graph.account.state.value.signedIn) { "Fixture test requires a signed-out app" }
        val repository = graph.userLibrary
        val series = SourceSeries(SeriesId(SourceId("wfwf"), "comic:999991001"), "Long press removal fixture")
        val episode = SourceEpisode(EpisodeId(series.id, "999991002"), "Fixture episode")
        val page = PageId.at(episode.id, 0)
        val device = UiDevice.getInstance(instrumentation)
        val file = java.io.File(context.cacheDir, "library-removal-fixture.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        suspend fun seed() {
            repository.recordOpened(series.id, series.title, null, episode.id)
            repository.saveProgress(page, 123456)
            repository.setFavorite(series.id, series.title, null, true)
        }
        try {
            seed()
            repository.addBookmark(page, 123456)
            graph.offlineStore.save(series, episode, EpisodeManifest(episode.id, episode.title, listOf(PageSpec(page, 0))),
                listOf(CachedPage(page, file, 4, "fixture", "image/png", PageDimensions(1, 1))))
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            device.wait(Until.findObject(By.desc("하단 보관함")), 5000)!!.click()
            dismissAutomaticUpdateNotice(device)

            device.wait(Until.findObject(By.text("최근")), 5000)!!.click()
            longPress(device, series.title)
            device.wait(Until.findObject(By.text("취소")), 3000)!!.click()
            assertTrue(device.wait(Until.gone(By.text("${series.title} 삭제")), 3000))
            device.waitForIdle(1000)
            assertEquals(123456L, repository.readingPosition(episode.id)!!.offsetInPageUnits)
            longPress(device, series.title)
            device.wait(Until.findObject(By.text("삭제")), 3000)!!.click()
            val recentRemoved = withTimeout(5000) { repository.snapshot.first { it.recent.none { r -> r.series.id == series.id } } }
            assertTrue(recentRemoved.favorites.any { it.id == series.id })
            assertTrue(recentRemoved.bookmarks.any { it.pageId == page })
            assertNotNull(graph.offlineStore.manifest(episode.id))

            seed()
            device.wait(Until.findObject(By.text("좋아요")), 5000)!!.click()
            longPress(device, series.title)
            device.wait(Until.findObject(By.text("삭제")), 3000)!!.click()
            withTimeout(5000) { repository.snapshot.first { it.favorites.none { f -> f.id == series.id } } }
            assertEquals(123456L, repository.readingPosition(episode.id)!!.offsetInPageUnits)

            device.wait(Until.findObject(By.text("저장됨")), 5000)!!.click()
            longPress(device, series.title)
            device.wait(Until.findObject(By.text("삭제")), 3000)!!.click()
            assertTrue(device.wait(Until.gone(By.text(series.title)), 5000))
            assertNull(graph.offlineStore.manifest(episode.id))
            assertEquals(123456L, repository.readingPosition(episode.id)!!.offsetInPageUnits)

            seed()
            device.wait(Until.findObject(By.text("전체")), 5000)!!.click()
            longPress(device, series.title)
            device.wait(Until.findObject(By.text("삭제")), 3000)!!.click()
            val removed = withTimeout(5000) { repository.snapshot.first {
                it.recent.none { r -> r.series.id == series.id } && it.favorites.none { f -> f.id == series.id }
            } }
            assertTrue(removed.bookmarks.any { it.pageId == page })
            assertNull(repository.readingPosition(episode.id))
            assertTrue(device.wait(Until.gone(By.text(series.title)), 5000))
        } catch (failure: Throwable) {
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "removal-failure.png"))
            device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "removal-failure.xml"))
            throw failure
        } finally {
            graph.offlineDownloads.removeSeries(series.id)
            repository.removeHistory(series.id, true)
            repository.snapshot.first().bookmarks.filter { it.pageId == page }.forEach { repository.removeBookmark(it) }
            file.delete()
        }
    }

    private fun longPress(device: UiDevice, title: String) {
        val item = device.wait(Until.findObject(By.text(title)), 5000)
        assertNotNull("Saved fixture is missing", item)
        val center = item!!.visibleCenter
        device.executeShellCommand("input swipe ${center.x} ${center.y} ${center.x} ${center.y} 1000")
        val dialog = device.wait(Until.findObject(By.text("$title 삭제")), 3000)
        if (dialog == null) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "removal-failure.png"))
            device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "removal-failure.xml"))
        }
        assertNotNull("Long press did not open deletion confirmation", dialog)
    }
}
