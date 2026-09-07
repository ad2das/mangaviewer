package ml.melun.mangaview.source.ntk

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.source.PreparationIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkBrowserDocumentControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun documentCookiePublicationInvalidatesAChallengeFromTheEarlierCookieContext() = runBlocking {
        val fixture = Fixture()
        val request = requireNotNull(fixture.current)
        request.challengePreflightStarted = true
        request.challengePreflightResolved = true
        request.challengePayload = "earlier challenge"
        request.inheritedChallengeRequestIds += 41L
        payload().use { source ->
            try {
                fixture.controller.descriptor(message(source))
                requireNotNull(fixture.finishCookies).invoke(true)
                assertFalse(request.challengePreflightStarted)
                assertFalse(request.challengePreflightResolved)
                assertNull(request.challengePayload)
                assertTrue(request.inheritedChallengeRequestIds.isEmpty())
            } finally { request.document?.close() }
        }
    }

    @Test
    fun documentReadyCannotDeliverUntilTheIdentityCookieBarrierIsReady() = runBlocking {
        val fixture = Fixture()
        val request = requireNotNull(fixture.current)
        request.identityCookiesApplied = false
        request.browserDocumentStarted = true
        payload().use { source ->
            try {
                fixture.controller.descriptor(message(source))
                requireNotNull(fixture.finishCookies).invoke(true)
                assertEquals(0, fixture.deliveryCount)
                request.identityCookiesApplied = true
                fixture.controller.deliver(request)
                assertEquals(1, fixture.deliveryCount)
            } finally { request.document?.close() }
        }
    }

    @Test
    fun exactDocumentIsReadyOnlyAfterCookiesAndDuplicateDeliveryDoesNotRestartIt() = runBlocking {
        val fixture = Fixture()
        payload().use { source ->
            try {
                fixture.controller.descriptor(message(source))
                assertNotNull(fixture.current?.document)
                assertFalse(requireNotNull(fixture.current).documentCookiesApplied)
                assertEquals(0, fixture.readyCount)
                fixture.controller.descriptor(message(source))
                assertEquals(1, fixture.cookieCount)
                requireNotNull(fixture.finishCookies).invoke(true)
                assertEquals(1, fixture.readyCount)
                assertTrue(requireNotNull(fixture.current).documentCookiesApplied)
                assertEquals(0, fixture.deliveryCount)
                requireNotNull(fixture.current).browserDocumentStarted = true
                fixture.controller.deliver(requireNotNull(fixture.current))
                fixture.controller.deliver(requireNotNull(fixture.current))
                assertEquals(1, fixture.deliveryCount)
                assertTrue(fixture.failures.isEmpty())
            } finally {
                fixture.current?.document?.close()
            }
        }
    }

    @Test
    fun rejectedCookiesCannotStartAuthorizationEvenIfTheBrowserHasStarted() = runBlocking {
        val fixture = Fixture()
        payload().use { source ->
            try {
                fixture.controller.descriptor(message(source))
                val request = requireNotNull(fixture.current)
                request.browserDocumentStarted = true
                fixture.controller.deliver(request)
                assertEquals(0, fixture.deliveryCount)
                requireNotNull(fixture.finishCookies).invoke(false)
                fixture.controller.deliver(request)
                assertFalse(request.documentCookiesApplied)
                assertEquals(0, fixture.readyCount)
                assertEquals(0, fixture.authorizationCount)
                assertEquals(0, fixture.deliveryCount)
                assertEquals(listOf("NTK document cookies rejected"), fixture.failures)
            } finally {
                fixture.current?.document?.close()
            }
        }
    }

    @Test
    fun retiredDocumentCookieFailureCannotFailAnotherRequest() = runBlocking {
        val fixture = Fixture()
        payload().use { source ->
            fixture.controller.descriptor(message(source))
            fixture.current?.document?.close()
            fixture.current = null
            requireNotNull(fixture.finishCookies).invoke(false)
            assertEquals(0, fixture.readyCount)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun sameEpisodeWithDifferentTicketCannotInstallAStaleDocument() = runBlocking {
        val fixture = Fixture()
        payload().use { source ->
            fixture.controller.descriptor(message(source, requestId = 99L))
            assertNull(fixture.current?.document)
            assertEquals(0, fixture.cookieCount)
            assertEquals(0, fixture.readyCount)
        }
    }

    @Test
    fun documentForDifferentEpisodeIsRejectedWithoutNavigation() = runBlocking {
        val fixture = Fixture()
        payload(path = "/webtoon/work/other").use { source ->
            fixture.controller.descriptor(message(source))
            assertNull(fixture.current?.document)
            assertEquals(0, fixture.cookieCount)
            assertEquals(0, fixture.readyCount)
            assertEquals(1, fixture.failures.size)
        }
    }

    @Test
    fun cancellationBeforeCookieCompletionCannotResumeNavigation() = runBlocking {
        val fixture = Fixture()
        payload().use { source ->
            fixture.controller.descriptor(message(source))
            fixture.current?.document?.close()
            fixture.current = null
            requireNotNull(fixture.finishCookies).invoke(true)
            assertEquals(0, fixture.readyCount)
            assertNull(fixture.controller.intercept(Resource()))
        }
    }

    @Test
    fun onlyExactMainDocumentIsReplayedAndClosedDocumentsCannotRefetch() = runBlocking {
        val fixture = Fixture()
        payload().use { source ->
            fixture.controller.descriptor(message(source))
            try {
                requireNotNull(fixture.finishCookies).invoke(true)
                assertNull(fixture.controller.intercept(Resource(mainFrame = false)))
                assertNull(fixture.controller.intercept(Resource(address = "$ORIGIN/webtoon/work/other")))
                assertNull(fixture.controller.intercept(Resource(verb = "POST")))
                val response = requireNotNull(fixture.controller.intercept(Resource()))
                assertEquals(200, response.statusCode)
                assertEquals("<html>exact episode</html>", response.data.use { it.readBytes().toString(Charsets.UTF_8) })
                fixture.current?.document?.close()
                val closed = requireNotNull(fixture.controller.intercept(Resource()))
                assertEquals(410, closed.statusCode)
                closed.data.close()
            } finally {
                fixture.current?.document?.close()
            }
        }
    }

    private suspend fun payload(path: String = PATH): NtkBrowserDocumentPayload =
        NtkBrowserDocumentPayload.create(context.cacheDir, NtkEpisodeDocument(
            origin = ORIGIN, path = path, html = "<html>exact episode</html>",
            responseHeaders = mapOf("Set-Cookie" to listOf("provider=exact; Secure")),
        ))

    private fun message(payload: NtkBrowserDocumentPayload, requestId: Long = 42L): Message {
        val data = Bundle().apply {
            putLong(NtkBrowserProtocol.KEY_REQUEST_ID, requestId)
            putString(NtkBrowserProtocol.KEY_WORK_ID, "work")
            putString(NtkBrowserProtocol.KEY_EPISODE_ID, "episode")
            putString(NtkBrowserProtocol.KEY_API_PATH, "/api/webtoon-images")
            putString(NtkBrowserProtocol.KEY_TOKEN, "document-token")
            payload.writeTo(this)
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(data)
            parcel.setDataPosition(0)
            Message.obtain().apply { this.data = requireNotNull(parcel.readBundle()) }
        } finally {
            parcel.recycle()
        }
    }

    private class Fixture {
        var current: RemoteRequest? = RemoteRequest(
            requestId = 42L, key = ORIGIN + PATH, userAgent = "test",
            intent = PreparationIntent.INITIAL_VIEW, fingerprint = null, persistentId = null,
            startedAtMillis = 0L, recipients = linkedMapOf(42L to Messenger(Handler(Looper.getMainLooper()))),
            identityCookiesApplied = true,
        )
        var readyCount = 0
        var cookieCount = 0
        var deliveryCount = 0
        var authorizationCount = 0
        var finishCookies: ((Boolean) -> Unit)? = null
        val failures = mutableListOf<String>()
        val controller = NtkBrowserDocumentController(
            currentRequest = { current }, warmRunning = { false },
            applyCookies = { _, _, ready -> cookieCount++; finishCookies = ready },
            documentReady = { readyCount++ }, startAuthorization = { authorizationCount++ },
            installManifestDescriptor = { deliveryCount++ }, fail = { _, detail -> failures += detail },
        )
    }

    private class Resource(
        private val address: String = ORIGIN + PATH,
        private val mainFrame: Boolean = true,
        private val verb: String = "GET",
    ) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(address)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = verb
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private companion object {
        const val ORIGIN = "https://provider.example"
        const val PATH = "/webtoon/work/episode"
    }
}
