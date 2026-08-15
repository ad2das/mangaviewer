package ml.melun.mangaview.ntkack

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NtkAckBrowserProcessIsolationTest {
    @Test
    fun warmHandshakeUsesDedicatedProcessWithoutCreatingAnIdleWebView() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hello = AtomicReference<NtkAckServiceHello>()
        val failure = AtomicReference<NtkAckFailure>()
        val callbackLatch = CountDownLatch(1)
        val callback = object : INtkAckBrowserCallback.Stub() {
            override fun onWarmReady(value: NtkAckServiceHello?) {
                hello.set(value)
                callbackLatch.countDown()
            }

            override fun onNetworkPrerequisitesReady(value: NtkAckFlightIdentity?) = Unit
            override fun onAckProved(proof: NtkAckProof?) = Unit
            override fun onQuiesced(seal: NtkAckQuiescenceSeal?) = Unit
            override fun onExactRequestSigned(signature: NtkAckSignature?) = Unit
            override fun onExactRequestExecuted(exchange: NtkAckExactExchange?) = Unit
            override fun onFailure(value: NtkAckFailure?) {
                failure.set(value)
                callbackLatch.countDown()
            }
        }
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                INtkAckBrowserService.Stub.asInterface(binder).warm(
                    NtkAckWarmRequest(
                        NtkAckProtocol.VERSION,
                        1L,
                        "Mozilla/5.0 Android test",
                        NtkAckViewport(1080, 2340, 440),
                        Process.myPid(),
                    ),
                    callback,
                )
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(
            context.bindService(
                Intent(context, NtkAckBrowserService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            assertTrue(callbackLatch.await(15, TimeUnit.SECONDS))
            check(failure.get() == null) { "service failure=${failure.get()}" }
            val value = checkNotNull(hello.get())
            assertNotEquals(Process.myPid(), value.servicePid)
            // Warm authenticates the isolated signer only. Chromium is intentionally created on
            // demand if a server selects the JavaScript/WASM challenge branch; keeping an idle
            // WebView here wastes memory and made this old assertion contradict production.
            assertEquals(0, value.webViewCreatedPid)
            assertEquals(NtkAckProtocol.DATA_DIRECTORY_SUFFIX, value.dataDirectorySuffix)
            assertTrue(value.proofPublicKeyX509.isNotEmpty())
            NtkAckProofVerifier.verifyHelloOrThrow(value, value.servicePid)
        } finally {
            context.unbindService(connection)
        }
    }
}
