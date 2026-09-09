package ml.melun.mangaview.source.ntk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.io.Closeable

/** A scoped process-start binding. It sends no provider request or authorization message. */
class NtkEngineBrowserPreparation internal constructor(private val context: Context, private val userAgent: String) : Closeable {
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    @Synchronized internal fun bind() {
        check(!bound)
        val intent = Intent(context, NtkEngineBrowserService::class.java)
            .putExtra(NtkBrowserProtocol.KEY_USER_AGENT, userAgent)
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        check(bound) { "NTK engine browser preparation binding was rejected" }
    }

    @Synchronized override fun close() {
        if (!bound) return
        bound = false
        context.unbindService(connection)
    }
}
