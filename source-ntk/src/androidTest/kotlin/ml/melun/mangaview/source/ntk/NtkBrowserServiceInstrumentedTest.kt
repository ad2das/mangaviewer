package ml.melun.mangaview.source.ntk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkBrowserServiceInstrumentedTest {
    @Test
    fun manifestDeclaresPrivateRemoteProcess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, NtkBrowserService::class.java)
        val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)

        assertEquals("${context.packageName}:ntk_browser", info.processName)
        assertTrue(!info.exported)
    }

    @Test
    fun messengerRejectsMalformedRequestAndEchoesItsIdentity() {
        exchange(NtkBrowserProtocol.MSG_ERROR, REQUEST_ID) { callback ->
            Message.obtain(null, NtkBrowserProtocol.MSG_RESOLVE).apply {
                replyTo = callback
                data = Bundle().apply {
                    putLong(NtkBrowserProtocol.KEY_REQUEST_ID, REQUEST_ID)
                    putString(NtkBrowserProtocol.KEY_ORIGIN, "file:///invalid")
                    putString(NtkBrowserProtocol.KEY_PATH, "/episode")
                    putString(NtkBrowserProtocol.KEY_USER_AGENT, "test-agent")
                }
            }
        }
    }

    @Test
    fun cancellationOfAbsentRequestAcknowledgesOnlyThatRequest() {
        exchange(NtkBrowserProtocol.MSG_REQUEST_DETACHED, REQUEST_ID) { callback ->
            NtkBrowserIpcMessages.control(NtkBrowserProtocol.MSG_CANCEL, REQUEST_ID, callback)
        }
        exchange(NtkBrowserProtocol.MSG_REQUEST_DETACHED, REQUEST_ID) { callback ->
            NtkBrowserIpcMessages.control(NtkBrowserProtocol.MSG_QUIESCE, REQUEST_ID, callback)
        }
    }

    @Test
    fun invalidCancellationCannotReceiveSuccessfulDetachAcknowledgement() {
        exchange(NtkBrowserProtocol.MSG_ERROR, INVALID_REQUEST_ID) { callback ->
            NtkBrowserIpcMessages.control(NtkBrowserProtocol.MSG_CANCEL, INVALID_REQUEST_ID, callback)
        }
    }

    private fun exchange(expectedWhat: Int, expectedId: Long, request: (Messenger) -> Message) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connected = CountDownLatch(1)
        val replied = CountDownLatch(1)
        val responseWhat = AtomicInteger()
        val responseId = AtomicLong()
        val callbackThread = HandlerThread("ntk-browser-test-callback").apply { start() }
        val callback = Messenger(object : Handler(callbackThread.looper) {
            override fun handleMessage(message: Message) {
                responseWhat.set(message.what)
                responseId.set(message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID))
                replied.countDown()
            }
        })
        var remote: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = Messenger(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }

        val bound = context.bindService(
            Intent(context, NtkBrowserService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        try {
            assertTrue(bound)
            assertTrue(connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            requireNotNull(remote).send(request(callback))
            assertTrue(replied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(expectedWhat, responseWhat.get())
            assertEquals(expectedId, responseId.get())
        } finally {
            if (bound) context.unbindService(connection)
            callbackThread.quitSafely()
        }
    }

    private companion object {
        const val REQUEST_ID = 47L
        const val TIMEOUT_SECONDS = 5L
    }
}
