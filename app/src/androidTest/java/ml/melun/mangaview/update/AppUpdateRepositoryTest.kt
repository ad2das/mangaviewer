package ml.melun.mangaview.update

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Suppress("DEPRECATION")
    private val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode + 1
    private val url = "https://github.com/ad2das/mangaviewer/releases/download/main-latest/mangaViewer_${version}-debug.apk"

    private fun client(bytes: ByteArray) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(bytes.toResponseBody()).build()
    }.build()

    @Test fun signedUpgradeDownloadsVerifiesAndCanBeReadThroughTheInstallUri() = runBlocking<Unit> {
        val path = InstrumentationRegistry.getArguments().getString("updateFixture")
        assumeNotNull(path)
        val fixture = File(requireNotNull(path))
        val bytes = fixture.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val repository = AppUpdateRepository(context) { client(bytes) }
        val release = UpdateRelease(version, url, sha256 = hash, size = bytes.size.toLong())
        val progress = mutableListOf<Int?>()
        val file = repository.download(release, progress::add)
        try {
            assertEquals(100, progress.last())
            repository.verifyPackage(file, version)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.updates", uri.authority)
            val streamed = context.contentResolver.openInputStream(uri)!!.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            assertEquals(hash, streamed)
            assertThrows(IllegalStateException::class.java) { repository.verifyPackage(file, version + 1) }
        } finally { file.delete() }
    }

    @Test fun wrongHashAndTruncatedDownloadsNeverBecomeInstallableFiles() = runBlocking {
        val bytes = "invalid apk fixture".toByteArray()
        val repository = AppUpdateRepository(context) { client(bytes) }
        for (release in listOf(UpdateRelease(version, url, sha256 = "0".repeat(64)),
            UpdateRelease(version, url, size = bytes.size + 1L))) {
            try { repository.download(release) {}; fail("Invalid download was accepted") }
            catch (expected: IllegalStateException) { }
            assertFalse(File(context.cacheDir, "app-updates/$version.apk.part").exists())
            assertFalse(File(context.cacheDir, "app-updates/$version.apk").exists())
        }
    }

    @Test fun cancellationRemovesPartialDownload() = runBlocking {
        val repository = AppUpdateRepository(context) { client(ByteArray(4 * 1024 * 1024)) }
        lateinit var task: kotlinx.coroutines.Deferred<File>
        task = async(start = CoroutineStart.LAZY) {
            repository.download(UpdateRelease(version, url)) { task.cancel() }
        }
        try { task.await(); fail("Cancellation was ignored") } catch (expected: CancellationException) { }
        task.join()
        assertFalse(File(context.cacheDir, "app-updates/$version.apk.part").exists())
    }

    @Test fun missingManifestFallsBackToPublishedReleaseRatherThanStaleBranchMetadata() = runBlocking {
        val requests = mutableListOf<String>()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val uri = chain.request().url.toString()
            requests += uri
            val api = uri.startsWith(UpdateRelease.RELEASE_API_URL)
            val body = if (api) """{"tag_name":"main-latest","assets":[{"name":"mangaViewer_$version-debug.apk","size":100,"browser_download_url":"$url"}]}""" else "missing"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (api) 200 else 404)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()
        assertEquals(version, AppUpdateRepository(context) { transport }.latest().version)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.startsWith(UpdateRelease.METADATA_URL) || it.startsWith(UpdateRelease.RELEASE_API_URL) })
    }
}
