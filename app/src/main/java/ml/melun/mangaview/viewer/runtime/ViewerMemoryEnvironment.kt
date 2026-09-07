package ml.melun.mangaview.viewer.runtime

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import java.io.Closeable

internal class ViewerMemoryEnvironment(context: Context, private val pressure: () -> Unit) :
    ComponentCallbacks2, Closeable {
    private val application = context.applicationContext
    val totalPhysicalBytes: Long? = runCatching {
        val manager = application.getSystemService(ActivityManager::class.java)
        ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem.takeIf { it > 0L }
    }.getOrNull()

    init { application.registerComponentCallbacks(this) }

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) pressure()
    }

    override fun onLowMemory() = pressure()
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun close() = application.unregisterComponentCallbacks(this)
}
