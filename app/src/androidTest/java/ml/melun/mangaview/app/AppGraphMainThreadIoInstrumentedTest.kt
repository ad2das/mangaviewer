package ml.melun.mangaview.app

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppGraphMainThreadIoInstrumentedTest {
    @Test
    fun graphConstructionPerformsNoUnclassifiedMainThreadDiskIo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            instrumentation.runOnMainSync {
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder(previous)
                        .detectDiskReads()
                        .detectDiskWrites()
                        .penaltyDeath()
                        .build(),
                )
                try {
                    AppGraph(context, scope, Dispatchers.Default, Dispatchers.IO).close()
                } finally {
                    StrictMode.setThreadPolicy(previous)
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
