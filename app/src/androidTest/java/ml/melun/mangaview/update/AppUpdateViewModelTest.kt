package ml.melun.mangaview.update

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateViewModelTest {
    private val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    @Suppress("DEPRECATION")
    private val installed = application.packageManager.getPackageInfo(application.packageName, 0).longVersionCode

    private fun model(version: Long, apk: ByteArray? = null): AppUpdateViewModel {
        val url = "https://github.com/ad2das/mangaviewer/releases/download/main-latest/mangaViewer_${version}-debug.apk"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val bytes = if (chain.request().url.encodedPath.endsWith(".apk")) requireNotNull(apk)
                else """{"version":$version,"link":"$url"}""".toByteArray()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(bytes.toResponseBody()).build()
        }.build()
        return AppUpdateViewModel(application, AppUpdateRepository(application) { client })
    }

    @Test fun automaticCheckOnlyPromptsForAnUpgradeAndDoesNotReopenAfterDismissal() = runBlocking {
        withContext(Dispatchers.Main) {
            val current = model(installed)
            current.checkAutomatically()
            assertEquals(UpdatePhase.CURRENT, current.state.value.phase)
            assertFalse(current.state.value.visible)
            val newer = model(installed + 1)
            newer.checkAutomatically()
            assertEquals(UpdatePhase.AVAILABLE, newer.state.value.phase)
            assertTrue(newer.state.value.visible)
            newer.dismiss()
            newer.checkAutomatically()
            assertFalse(newer.state.value.visible)
        }
    }

    @Test fun acceptedDownloadRequestsInstallationExactlyOnce() = runBlocking<Unit> {
        val path = InstrumentationRegistry.getArguments().getString("updateFixture")
        assumeNotNull(path)
        val bytes = File(requireNotNull(path)).readBytes()
        val vm = model(installed + 1, bytes)
        withContext(Dispatchers.Main) {
            vm.checkAutomatically()
            assertEquals(UpdatePhase.AVAILABLE, vm.state.value.phase)
            assertNull(vm.state.value.file)
            vm.download()
        }
        val result = withTimeout(30_000) { vm.state.first { it.phase == UpdatePhase.READY || it.phase == UpdatePhase.FAILED } }
        assertEquals(result.message, UpdatePhase.READY, result.phase)
        val file = requireNotNull(result.file)
        try {
            withContext(Dispatchers.Main) {
                assertFalse(vm.consumePendingInstall(File(application.cacheDir, "unrelated.apk")))
                assertTrue(vm.consumePendingInstall(file))
                assertFalse(vm.consumePendingInstall(file))
                vm.dismiss()
                assertFalse(vm.consumePendingInstall(file))
            }
        } finally { file.delete() }
    }
}
