package ml.melun.mangaview.data.reset

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyExternalCleanerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deletesOnlyMarkedChildrenAndNeverTheRecordedRoot() {
        val root = temporaryFolder.newFolder("recorded-root")
        val marked = File(root, "downloaded-title").also(File::mkdir)
        File(marked, "title.gson").writeText("{}")
        File(marked, "episode").also(File::mkdir)
        File(marked, "episode/page.jpg").writeText("image")
        val unrelated = File(root, "photos").also(File::mkdir)
        File(unrelated, "keep.txt").writeText("keep")

        LegacyExternalCleaner().deleteMarkedChildren(root)

        assertTrue(root.isDirectory)
        assertFalse(marked.exists())
        assertTrue(File(unrelated, "keep.txt").isFile)
    }
}
