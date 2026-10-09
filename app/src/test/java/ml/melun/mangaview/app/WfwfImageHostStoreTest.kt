package ml.melun.mangaview.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfImageHostStoreTest {
    private class FakePersistence : WfwfImageHostPersistence {
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String) { values[key] = value }
    }

    @Test fun rememberedHostsRoundTripThroughAFreshStore() {
        val persistence = FakePersistence()
        WfwfImageHostStore(persistence).remember("wfwf", listOf("i1.imgcloud18.com", "img8cloud.net"))
        assertEquals(
            listOf("i1.imgcloud18.com", "img8cloud.net"),
            WfwfImageHostStore(persistence).hosts("wfwf"),
        )
    }

    @Test fun memoryKeepsOnlyTheTwoMostRecentDistinctHosts() {
        val persistence = FakePersistence()
        val store = WfwfImageHostStore(persistence)
        store.remember("wfwf", listOf("a.example", "b.example", "a.example", "c.example"))
        assertEquals(listOf("a.example", "b.example"), store.hosts("wfwf"))
        store.remember("wfwf", listOf("c.example", "a.example"))
        assertEquals(listOf("c.example", "a.example"), store.hosts("wfwf"))
    }

    @Test fun sourcesKeepIndependentHostMemories() {
        val persistence = FakePersistence()
        val store = WfwfImageHostStore(persistence)
        store.remember("wfwf", listOf("images.example"))
        store.remember("goodtoon", listOf("img.goodtoon.example"))
        assertEquals(listOf("images.example"), store.hosts("wfwf"))
        assertEquals(listOf("img.goodtoon.example"), store.hosts("goodtoon"))
    }

    @Test fun missingAndMalformedEntriesDecodeToHostsOnly() {
        val persistence = FakePersistence()
        val store = WfwfImageHostStore(persistence)
        assertTrue(store.hosts("wfwf").isEmpty())
        persistence.values["pageHosts.wfwf"] =
            "\n i1.imgcloud18.com \nsigned.example/path?signature=secret\n\n"
        assertEquals(listOf("i1.imgcloud18.com"), store.hosts("wfwf"))
    }
}
