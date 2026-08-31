package ml.melun.mangaview.data.reset

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LegacyResetReport(
    val alreadyComplete: Boolean,
    val deletedExternalTitleDirectories: Int,
    val deletedInternalDirectories: Int,
)

class LegacyDataResetter(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val externalCleaner: LegacyExternalCleaner = LegacyExternalCleaner(),
    private val resetVersion: Int = 1,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    suspend fun runOnce(): LegacyResetReport = mutex.withLock {
        withContext(ioDispatcher) { resetOnIoThread() }
    }

    private fun resetOnIoThread(): LegacyResetReport {
        val marker = MigrationMarker(appContext.noBackupFilesDir, resetVersion)
        if (marker.isComplete()) return LegacyResetReport(true, 0, 0)
        val legacyPreferences = appContext.getSharedPreferences(LEGACY_MAIN_PREFERENCES, Context.MODE_PRIVATE)
        val recordedHome = legacyPreferences.getString("homeDir", null)
        val externalCount = deleteRecordedExternalData(recordedHome)
        val internalCount = deleteInternalDirectories()
        LEGACY_DATABASES.forEach(appContext::deleteDatabase)
        LEGACY_PREFERENCES.forEach(appContext::deleteSharedPreferences)
        marker.complete()
        return LegacyResetReport(false, externalCount, internalCount)
    }

    private fun deleteRecordedExternalData(recordedHome: String?): Int {
        if (recordedHome.isNullOrBlank()) return 0
        val uri = Uri.parse(recordedHome)
        return if (uri.scheme.equals("content", ignoreCase = true)) {
            deleteSafMarkedChildren(uri)
        } else {
            val path = if (uri.scheme.equals("file", ignoreCase = true)) uri.path else recordedHome
            if (path.isNullOrBlank()) 0 else externalCleaner.deleteMarkedChildren(File(path))
        }
    }

    private fun deleteSafMarkedChildren(uri: Uri): Int {
        val root = DocumentFile.fromTreeUri(appContext, uri) ?: return 0
        if (!root.isDirectory) return 0
        var deleted = 0
        root.listFiles().forEach { child ->
            if (!child.isDirectory || child.findFile(LEGACY_TITLE_MARKER)?.isFile != true) return@forEach
            check(child.delete()) { "Unable to delete a marked legacy SAF directory" }
            deleted += 1
        }
        return deleted
    }

    private fun deleteInternalDirectories(): Int {
        val targets = listOf(
            appContext.cacheDir to "reader_image_cache_v1",
            appContext.noBackupFilesDir to "reader_strict_spool_v1",
            appContext.filesDir to "download_queue",
        )
        return targets.count { (parent, child) -> externalCleaner.deleteExactChild(parent, child) }
    }

    private companion object {
        const val LEGACY_MAIN_PREFERENCES = "mangaView"
        const val LEGACY_TITLE_MARKER = "title.gson"
        val LEGACY_DATABASES = listOf("mangaviewer_store.db")
        val LEGACY_PREFERENCES = listOf(
            LEGACY_MAIN_PREFERENCES,
            "ntk_viewer_browser_keys",
            "classificationDbUpdater",
            "appUpdate",
            "firebaseSyncMeta",
        )
    }
}
