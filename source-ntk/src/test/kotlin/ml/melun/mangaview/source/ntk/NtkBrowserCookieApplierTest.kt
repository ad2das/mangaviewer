package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkBrowserCookieApplierTest {
    @Test
    fun supersededBatchStopsBeforeNewIdentityWritesAndCannotReportReady() {
        val fixture = Fixture()
        var current = true
        fixture.applier.cookies(ORIGIN, listOf("old=1", "old=2"), isCurrent = { current },
            completed = fixture.results::add)
        current = false
        fixture.applier.cookies(ORIGIN, listOf("new=1"), completed = fixture.results::add)
        assertEquals(listOf("old=1"), fixture.values)
        fixture.callbacks[0](true)
        assertEquals(listOf("old=1", "new=1"), fixture.values)
        assertEquals(listOf(false), fixture.results)
        fixture.callbacks[1](true)
        assertEquals(listOf(false, true), fixture.results)
    }

    @Test
    fun alreadySupersededBatchCannotMutateCookies() {
        val fixture = Fixture()
        fixture.applier.cookies(ORIGIN, listOf("old=1"), isCurrent = { false },
            completed = fixture.results::add)
        assertTrue(fixture.values.isEmpty())
        assertEquals(listOf(false), fixture.results)
    }

    @Test
    fun completionWaitsForEveryAcceptedWrite() {
        val fixture = Fixture()
        fixture.applier.cookies(ORIGIN, listOf("a=1", "b=2"), completed = fixture.results::add)
        assertEquals(listOf("a=1"), fixture.values)
        assertTrue(fixture.results.isEmpty())
        fixture.callbacks[0](true)
        assertEquals(listOf("a=1", "b=2"), fixture.values)
        assertTrue(fixture.results.isEmpty())
        fixture.callbacks[1](true)
        assertEquals(listOf(true), fixture.results)
    }

    @Test
    fun rejectionStopsTheBatchWithoutReportingReady() {
        val fixture = Fixture()
        fixture.applier.cookies(ORIGIN, listOf("a=1", "b=2", "c=3"), completed = fixture.results::add)
        fixture.callbacks[0](true)
        fixture.callbacks[1](false)
        assertEquals(listOf("a=1", "b=2"), fixture.values)
        assertEquals(listOf(false), fixture.results)
    }

    @Test
    fun emptyCookiesNeedNoWrite() {
        val fixture = Fixture()
        fixture.applier.cookies(ORIGIN, emptyList(), completed = fixture.results::add)
        assertEquals(listOf(true), fixture.results)
        assertTrue(fixture.values.isEmpty())
    }

    @Test
    fun identityFailureIsNotDiscarded() {
        val fixture = Fixture()
        fixture.applier.identity(ORIGIN, NtkBrowserIdentity("a".repeat(32), "b".repeat(32)), fixture.results::add)
        fixture.callbacks[0](false)
        assertEquals(1, fixture.values.size)
        assertEquals(listOf(false), fixture.results)
    }

    private class Fixture {
        val values = mutableListOf<String>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val results = mutableListOf<Boolean>()
        val applier = NtkBrowserCookieApplier { origin, value, callback ->
            assertEquals(ORIGIN, origin)
            values += value
            callbacks += callback
        }
    }

    private companion object {
        const val ORIGIN = "https://provider.example"
    }
}
