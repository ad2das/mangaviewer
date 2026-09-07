package ml.melun.mangaview.source.ntk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import ml.melun.mangaview.source.PreparationIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkEngineBrowserServiceTest {
    @Test fun retirementIsIdempotentButCannotRetireTheNextOwnedRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val replies = LinkedBlockingQueue<Pair<Int, Long>>()
        val thread = HandlerThread("engine-browser-service-test").apply { start() }
        val recipient = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(message: Message) {
                replies.offer(message.what to message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID))
            }
        })
        val connected = CountDownLatch(1)
        var remote: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = Messenger(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        fun expect(what: Int, id: Long) { assertEquals(what to id, replies.poll(10, TimeUnit.SECONDS)) }
        fun resolve(id: Long) {
            requireNotNull(remote).send(Message.obtain(null, NtkBrowserProtocol.MSG_RESOLVE).apply {
                replyTo = recipient
                data = Bundle().apply {
                    putLong(NtkBrowserProtocol.KEY_REQUEST_ID, id)
                    putString(NtkBrowserProtocol.KEY_ORIGIN, "https://provider.test")
                    putString(NtkBrowserProtocol.KEY_PATH, "/webtoon/work/episode")
                    putString(NtkBrowserProtocol.KEY_USER_AGENT, "test-agent")
                    putString(NtkBrowserProtocol.KEY_PREPARATION_INTENT, PreparationIntent.INITIAL_VIEW.name)
                }
            })
        }
        fun retire(id: Long) {
            requireNotNull(remote).send(NtkBrowserIpcMessages.control(NtkBrowserProtocol.MSG_RETIRE_DOCUMENT, id, recipient))
        }
        val bound = context.bindService(Intent(context, NtkEngineBrowserService::class.java), connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(bound)
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            resolve(501)
            expect(NtkBrowserProtocol.MSG_DOCUMENT_REQUEST_READY, 501)
            retire(501)
            expect(NtkBrowserProtocol.MSG_DOCUMENT_RETIRED, 501)
            retire(501)
            expect(NtkBrowserProtocol.MSG_DOCUMENT_RETIRED, 501)
            resolve(502)
            expect(NtkBrowserProtocol.MSG_DOCUMENT_REQUEST_READY, 502)
            retire(501)
            expect(NtkBrowserProtocol.MSG_ERROR, 501)
            retire(502)
            expect(NtkBrowserProtocol.MSG_DOCUMENT_RETIRED, 502)
        } finally {
            if (bound) context.unbindService(connection)
            thread.quitSafely()
        }
    }
}
