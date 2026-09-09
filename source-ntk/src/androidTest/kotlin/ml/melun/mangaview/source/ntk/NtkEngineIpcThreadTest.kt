package ml.melun.mangaview.source.ntk

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.SourceDocument
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkEngineIpcThreadTest {
    @Test fun callbackThreadIsJoinedAfterSuccessAndCancellation() = runBlocking {
        val completed = withNtkEngineIpc { Thread.currentThread() }
        assertFalse(completed.isAlive)
        val entered = CompletableDeferred<Thread>()
        val operation = launch { withNtkEngineIpc {
            entered.complete(Thread.currentThread())
            awaitCancellation()
        } }
        val cancelled = entered.await()
        operation.cancelAndJoin()
        assertFalse(cancelled.isAlive)
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun captureReceivesBothProofMessagesAndRetiresWhileMainLooperIsOccupied() = exerciseCapture(false)

    @Test @SdkSuppress(minSdkVersion = 29)
    fun cancelledCaptureRetiresBeforeUnbindingWhileMainLooperIsOccupied() = exerciseCapture(true)

    private fun exerciseCapture(cancelBeforeProof: Boolean) = runBlocking {
        val fakeServiceThread = HandlerThread("engine-test-service").apply { start() }
        val received = mutableListOf<Int>()
        val descriptorSeen = CompletableDeferred<Unit>()
        val fakeService = Messenger(object : Handler(fakeServiceThread.looper) {
            override fun handleMessage(message: Message) {
                received += message.what
                val id = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID)
                fun reply(what: Int) = message.replyTo.send(Message.obtain(null, what).apply {
                    data.putLong(NtkBrowserProtocol.KEY_REQUEST_ID, id)
                    if (what == NtkBrowserProtocol.MSG_PAYLOAD) data.putString(NtkBrowserProtocol.KEY_PAYLOAD, "test-payload")
                })
                when (message.what) {
                    NtkBrowserProtocol.MSG_RESOLVE -> reply(NtkBrowserProtocol.MSG_DOCUMENT_REQUEST_READY)
                    NtkBrowserProtocol.MSG_DESCRIPTOR -> {
                        descriptorSeen.complete(Unit)
                        if (!cancelBeforeProof) {
                            reply(NtkBrowserProtocol.MSG_ACK_READY)
                            reply(NtkBrowserProtocol.MSG_PAYLOAD)
                        }
                    }
                    NtkBrowserProtocol.MSG_RETIRE_DOCUMENT -> reply(NtkBrowserProtocol.MSG_DOCUMENT_RETIRED)
                }
            }
        })
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            var unbinds = 0
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, flags: Int, executor: Executor, connection: ServiceConnection): Boolean {
                executor.execute { connection.onServiceConnected(requireNotNull(intent.component), fakeService.binder) }
                return true
            }
            override fun unbindService(connection: ServiceConnection) { unbinds++ }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            val episode = EpisodeId(SeriesId(SourceId("ntk"), "/webtoon/work"), "/webtoon/work/current")
            val html = """<script>{"sourceWorkId":"work","episodeId":"current","imagesToken":"test-token",
                "imageApiPath":"/api/webtoon-images","imageCount":1,"prevEpId":null,"nextEpId":null}</script>"""
            val document = NtkAccessPlanner("test-agent").parseDocument(episode,
                SourceDocument(URI("https://ntk.test/webtoon/work/current"), html.toByteArray()), 2)
            Handler(Looper.getMainLooper()).post {
                entered.countDown()
                try { release.await(3, TimeUnit.SECONDS) } finally { finished.countDown() }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val operation = async {
                NtkEngineBrowserClient(context, "test-agent", NtkBrowserIdentity("a".repeat(32), "b".repeat(32)))
                    .capture(document)
            }
            withTimeout(1500) {
                descriptorSeen.await()
                if (cancelBeforeProof) {
                    operation.cancelAndJoin()
                    assertTrue(operation.isCancelled)
                } else {
                    val proof = operation.await()
                    assertEquals("test-payload", proof.payload)
                    assertEquals(document.sourceDocument.sha256, proof.documentSha256)
                    assertEquals(document.sourceDocument.replaySha256, proof.documentReplaySha256)
                    assertEquals(episode, proof.episodeId)
                    assertTrue(proof.documentRetiredNanos >= proof.manifestObservedNanos)
                    assertTrue(proof.documentRetiredNanos >= proof.ackObservedNanos)
                }
            }
            assertEquals(1, context.unbinds)
            assertEquals(listOf(NtkBrowserProtocol.MSG_RESOLVE, NtkBrowserProtocol.MSG_DESCRIPTOR,
                NtkBrowserProtocol.MSG_RETIRE_DOCUMENT), received)
        } finally {
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            fakeServiceThread.quitSafely()
            fakeServiceThread.join()
        }
    }
}
