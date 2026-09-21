package ml.melun.mangaview.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real clearance ladder in the app process so the managed challenge can be observed and
 * measured from the outside (CDP + logcat). Evidence is mirrored to external files.
 */
@RunWith(AndroidJUnit4::class)
class NewxtoonChallengeProbeTest {
    @Test
    fun solvesManagedChallengeInWebView() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as ViewerApplication
        val clearance = application.graph.newxtoonClearanceState
        val output = File(context.getExternalFilesDir(null), "newxtoon-challenge-probe").apply { mkdirs() }
        Log.i(TAG, "probe start persisted=${clearance.persistedClearancePresent} " +
            "verified=${clearance.clearanceVerified}")
        val solved = clearance.solveFresh()
        Log.i(TAG, "solveFresh=$solved verified=${clearance.clearanceVerified} " +
            "viewReady=${clearance.solvedViewReady}")
        val page = clearance.fetchPage(
            "https://newxtoon1.com/comics?page=1&sort=latest", emptyMap())
        val status = page?.statusCode ?: -1
        val mitigated = page?.header("cf-mitigated")
        val bytes = page?.body?.size ?: 0
        val head = page?.body?.let { String(it, 0, minOf(160, it.size), Charsets.UTF_8) }
        Log.i(TAG, "fetch status=$status mitigated=$mitigated bytes=$bytes head=${head?.replace('\n', ' ')}")
        output.resolve("result.txt").writeText(
            "solved=$solved verified=${clearance.clearanceVerified} status=$status " +
                "mitigated=$mitigated bytes=$bytes")
        assertTrue("challenge must serve a real document (status=$status mitigated=$mitigated)",
            solved && status == 200 && mitigated == null)
    }

    private companion object {
        const val TAG = "ChallengeProbe"
    }
}
