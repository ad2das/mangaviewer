package ml.melun.mangaview.ntkack

import android.content.Context

/** Minimal remote-process bootstrap; no reader, database, preference, or HTTP singleton. */
object NtkAckProcessRuntime {
    @Volatile
    private var applicationContext: Context? = null

    @JvmStatic
    fun initialize(context: Context) {
        require(ProcessRole.isNtkAckProcess(context)) { "ACK runtime initialized outside :ntk_ack" }
        if (applicationContext == null) {
            synchronized(this) {
                if (applicationContext == null) applicationContext = context.applicationContext
            }
        }
    }

    fun context(): Context = checkNotNull(applicationContext) { "ACK runtime is not initialized" }
}
