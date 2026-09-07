package ml.melun.mangaview.source.ntk

import android.os.Bundle
import android.os.Message
import android.os.Messenger

internal object NtkBrowserIpcMessages {
    fun resolve(
        pending: BrowserRequest,
        callback: Messenger,
        userAgent: String,
        identity: NtkBrowserIdentity?,
    ): Message = Message.obtain(null, NtkBrowserProtocol.MSG_RESOLVE).apply {
        replyTo = callback
        data = Bundle().apply {
            putLong(NtkBrowserProtocol.KEY_REQUEST_ID, pending.requestId)
            putString(NtkBrowserProtocol.KEY_ORIGIN, pending.origin)
            putString(NtkBrowserProtocol.KEY_PATH, pending.path)
            putString(NtkBrowserProtocol.KEY_USER_AGENT, userAgent)
            putString(NtkBrowserProtocol.KEY_PREPARATION_INTENT, pending.intent.name)
            putIdentity(identity)
        }
    }

    fun warm(
        callback: Messenger,
        userAgent: String,
        origin: String,
        identity: NtkBrowserIdentity?,
    ): Message = Message.obtain(null, NtkBrowserProtocol.MSG_WARM).apply {
        replyTo = callback
        data = Bundle().apply {
            putString(NtkBrowserProtocol.KEY_USER_AGENT, userAgent)
            putString(NtkBrowserProtocol.KEY_ORIGIN, origin)
            putIdentity(identity)
        }
    }

    fun descriptor(pending: BrowserRequest, descriptor: BrowserDescriptor): Message =
        Message.obtain(null, NtkBrowserProtocol.MSG_DESCRIPTOR).apply {
            data = Bundle().apply {
                putLong(NtkBrowserProtocol.KEY_REQUEST_ID, pending.requestId)
                putString(NtkBrowserProtocol.KEY_WORK_ID, descriptor.workId)
                putString(NtkBrowserProtocol.KEY_EPISODE_ID, descriptor.episodeId)
                putString(NtkBrowserProtocol.KEY_TOKEN, descriptor.token)
                putString(NtkBrowserProtocol.KEY_API_PATH, descriptor.apiPath)
                putInt(
                    NtkBrowserProtocol.KEY_EXPECTED_COUNT,
                    descriptor.expectedPageCount ?: UNKNOWN_PAGE_COUNT,
                )
                requireNotNull(pending.document).writeTo(this)
            }
        }

    fun control(what: Int, requestId: Long, callback: Messenger? = null): Message = Message.obtain(null, what).apply {
        replyTo = callback
        data = Bundle().apply { putLong(NtkBrowserProtocol.KEY_REQUEST_ID, requestId) }
    }

    fun preflightAdjacent(pending: BrowserRequest, adjacentPath: String): Message =
        Message.obtain(null, NtkBrowserProtocol.MSG_PREFLIGHT_ADJACENT).apply {
            data = Bundle().apply {
                putLong(NtkBrowserProtocol.KEY_REQUEST_ID, pending.requestId)
                putString(NtkBrowserProtocol.KEY_ADJACENT_PATH, adjacentPath)
            }
        }

    private fun Bundle.putIdentity(identity: NtkBrowserIdentity?) {
        identity ?: return
        putString(NtkBrowserProtocol.KEY_FINGERPRINT, identity.fingerprint)
        putString(NtkBrowserProtocol.KEY_PERSISTENT_ID, identity.persistentId)
    }

    private const val UNKNOWN_PAGE_COUNT = -1
}
