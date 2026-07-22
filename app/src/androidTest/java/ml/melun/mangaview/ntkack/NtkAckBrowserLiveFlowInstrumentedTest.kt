package ml.melun.mangaview.ntkack

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One physical-service flight: no image API, retry, discard, or alternate path. */
@RunWith(AndroidJUnit4::class)
class NtkAckBrowserLiveFlowInstrumentedTest {
    @Test
    fun fixed31ProducesVerifiedProofThenQuiescesAndSignsExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val remoteRef = AtomicReference<INtkAckBrowserService>()
        val helloRef = AtomicReference<NtkAckServiceHello>()
        val proofRef = AtomicReference<NtkAckProof>()
        val sealRef = AtomicReference<NtkAckQuiescenceSeal>()
        val signatureRef = AtomicReference<NtkAckSignature>()
        val failureRef = AtomicReference<NtkAckFailure>()
        val connected = CountDownLatch(1)
        val warmed = CountDownLatch(1)
        val proved = CountDownLatch(1)
        val quiesced = CountDownLatch(1)
        val signed = CountDownLatch(1)

        val callback = object : INtkAckBrowserCallback.Stub() {
            override fun onWarmReady(value: NtkAckServiceHello?) {
                helloRef.set(value)
                warmed.countDown()
            }

            override fun onNetworkPrerequisitesReady(value: NtkAckFlightIdentity?) = Unit
            override fun onAckProved(value: NtkAckProof?) {
                proofRef.set(value)
                proved.countDown()
            }

            override fun onQuiesced(value: NtkAckQuiescenceSeal?) {
                sealRef.set(value)
                quiesced.countDown()
            }

            override fun onExactRequestSigned(value: NtkAckSignature?) {
                signatureRef.set(value)
                signed.countDown()
            }

            override fun onExactRequestExecuted(value: NtkAckExactExchange?) = Unit

            override fun onFailure(value: NtkAckFailure?) {
                failureRef.compareAndSet(null, value)
                warmed.countDown()
                proved.countDown()
                quiesced.countDown()
                signed.countDown()
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remoteRef.set(INtkAckBrowserService.Stub.asInterface(binder))
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
            assertTrue("service bind timeout", connected.await(10, TimeUnit.SECONDS))
            val remote = checkNotNull(remoteRef.get())
            val authEpoch = System.currentTimeMillis().coerceAtLeast(1L)
            val userAgent = USER_AGENT
            val viewport = NtkAckViewport(1080, 2340, 440)
            remote.warm(
                NtkAckWarmRequest(NtkAckProtocol.VERSION, authEpoch, userAgent, viewport, Process.myPid()),
                callback,
            )
            assertTrue("warm timeout", warmed.await(15, TimeUnit.SECONDS))
            assertNoFailure(failureRef)
            val hello = checkNotNull(helloRef.get())
            val verifiedService = NtkAckProofVerifier.verifyHelloOrThrow(hello)

            val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
            val seedCookies = listOf(
                NtkAckCookie("ntk_fp", NtkAckProofCodec.sha256Utf8("$userAgent|$ORIGIN$FIXED31_PATH").take(32), domain = "sbxh9.com"),
                NtkAckCookie("ntk_pid", randomHex(16), domain = "sbxh9.com"),
                NtkAckCookie("__vsid", UUID.randomUUID().toString(), domain = "sbxh9.com"),
            )
            val request = NtkAckRequest(
                NtkAckProtocol.VERSION,
                UUID.randomUUID().toString(),
                SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L),
                authEpoch,
                nonce,
                ORIGIN,
                FIXED31_PATH,
                userAgent,
                "{\"platform\":\"Android\",\"mobile\":true}",
                viewport,
                seedCookies,
                SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(20),
                Process.myPid(),
            )
            remote.startAck(request, callback)
            assertTrue("live ACK timeout", proved.await(25, TimeUnit.SECONDS))
            assertNoFailure(failureRef)
            val proof = checkNotNull(proofRef.get())
            NtkAckProofVerifier.verifyOrThrow(
                proof,
                verifiedService,
                request,
                context.packageName,
                signingCertificateDigest(context),
            )
            assertEquals(4, proof.requiredObservationCount)
            assertEquals(proof.requiredObservationCount, proof.observed2xxCount)

            val identity = NtkAckFlightIdentity(
                request.protocolVersion,
                request.flightId,
                request.generation,
                request.authEpoch,
                request.origin,
                request.episodePath,
            )
            remote.quiesce(identity, callback)
            assertTrue("quiescence timeout", quiesced.await(10, TimeUnit.SECONDS))
            assertNoFailure(failureRef)
            val seal = checkNotNull(sealRef.get())
            NtkAckProofVerifier.verifyQuiescenceOrThrow(seal, proof, verifiedService)

            val exactBody = "{\"path\":\"$FIXED31_PATH\",\"probe\":true}".toByteArray()
            remote.signExactRequest(
                NtkAckSignRequest(
                    NtkAckProtocol.VERSION,
                    proof.proofId,
                    request.flightId,
                    request.generation,
                    request.authEpoch,
                    request.origin,
                    request.episodePath,
                    "POST",
                    "/api/manhwa-images",
                    NtkAckProofCodec.sha256Utf8("fixed31-live-integration"),
                    NtkAckProofCodec.sha256Utf8("fixed31-images-token"),
                    exactBody,
                ),
                callback,
            )
            assertTrue("exact-sign timeout", signed.await(5, TimeUnit.SECONDS))
            assertNoFailure(failureRef)
            val signature = signatureRef.get()
            assertNotNull(signature)
            assertEquals(seal.envelopeDigestSha256, signature.quiescenceDigestSha256)
            assertEquals(NtkAckProofCodec.sha256Hex(exactBody), signature.bodyDigestSha256)
        } finally {
            context.unbindService(connection)
        }
    }

    private fun assertNoFailure(failure: AtomicReference<NtkAckFailure>) {
        check(failure.get() == null) { "ACK service failure=${failure.get()}" }
    }

    private fun signingCertificateDigest(context: Context): String {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION") info.signatures?.firstOrNull()
        } ?: error("signing certificate missing")
        return NtkAckProofCodec.sha256Hex(signature.toByteArray())
    }

    private fun randomHex(byteCount: Int): String = ByteArray(byteCount)
        .also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val ORIGIN = "https://sbxh9.com"
        private const val FIXED31_PATH = "/manhwa/33727/1692251"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
