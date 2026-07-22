package ml.melun.mangaview.ntkack

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/** Process detection that never initializes WebView. */
object ProcessRole {
    @Volatile
    private var processName: String = ""

    @JvmStatic
    fun resolveProcessName(context: Context): String {
        processName.takeIf(String::isNotBlank)?.let { return it }
        val resolved = sequenceOf(
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else ""
            }.getOrDefault(""),
            runCatching {
                File("/proc/self/cmdline").inputStream().use { input ->
                    input.readBytes().takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.UTF_8)
                }
            }.getOrDefault(""),
            runCatching {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName.orEmpty()
            }.getOrDefault(""),
        ).firstOrNull(String::isNotBlank).orEmpty()
        processName = resolved
        return resolved
    }

    @JvmStatic
    fun isNtkAckProcess(processName: String?): Boolean =
        processName?.endsWith(NtkAckProtocol.PROCESS_SUFFIX) == true

    @JvmStatic
    fun isNtkAckProcess(context: Context): Boolean = isNtkAckProcess(resolveProcessName(context))

    @JvmStatic
    fun currentProcessName(): String = processName
}
