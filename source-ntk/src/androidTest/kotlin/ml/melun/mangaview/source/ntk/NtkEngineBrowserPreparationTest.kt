package ml.melun.mangaview.source.ntk

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkEngineBrowserPreparationTest {
    @Test fun preparationReturnsBeforeConnectionAndCloseReleasesItsExactBindingOnce() = runBlocking {
        val context = BindingContext()
        val preparation = client(context).prepareService()
        assertEquals(1, context.binds)
        assertEquals(0, context.unbinds)
        withContext(Dispatchers.Main.immediate) { preparation.close(); preparation.close() }
        assertEquals(1, context.unbinds)
    }

    @Test fun cancellationBeforeResultDeliveryReleasesTheAcceptedBinding() = runBlocking {
        val context = BindingContext()
        val owner = Job()
        context.afterBind = { owner.cancel() }
        var delivered = false
        val operation = launch(owner) {
            client(context).prepareService()
            delivered = true
        }
        operation.join()
        assertFalse(delivered)
        assertTrue(operation.isCancelled)
        assertEquals(1, context.binds)
        assertEquals(1, context.unbinds)
    }

    @Test fun rejectedBindingDoesNotUnbindAnUnownedConnection() = runBlocking {
        val context = BindingContext().apply { accepted = false }
        try {
            client(context).prepareService()
            fail("Rejected binding succeeded")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("rejected"))
        }
        assertEquals(1, context.binds)
        assertEquals(0, context.unbinds)
    }

    private fun client(context: Context) = NtkEngineBrowserClient(context, "test-agent",
        NtkBrowserIdentity("a".repeat(32), "b".repeat(32)))

    private class BindingContext : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        var binds = 0
        var unbinds = 0
        var accepted = true
        var afterBind: () -> Unit = {}
        private var owned: ServiceConnection? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(NtkEngineBrowserService::class.java.name, intent.component?.className)
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            assertNull(owned)
            binds++
            if (accepted) owned = connection
            afterBind()
            return accepted
        }
        override fun unbindService(connection: ServiceConnection) {
            assertSame(owned, connection)
            unbinds++
            owned = null
        }
    }
}
