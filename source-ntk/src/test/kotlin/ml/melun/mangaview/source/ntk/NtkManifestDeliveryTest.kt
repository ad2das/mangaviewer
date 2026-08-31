package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkManifestDeliveryTest {
    @Test
    fun preservesCompleteJsonVerbatimAndAcceptsItOnce() {
        val delivery = NtkManifestDelivery()
        val exact = """
            {"ok":true,"images":[{"page":1,"src":"https://cdn.invalid/a?x=1&y=2"}]}
        """.trimIndent()

        assertEquals(exact, delivery.accept(exact))
        assertEquals(exact, delivery.completedPayload())
        assertNull(delivery.accept("{\"ok\":true,\"images\":[]}"))
        assertEquals(exact, delivery.completedPayload())
    }

    @Test
    fun incompleteBlankCompletionDoesNotConsumeTheSingleDelivery() {
        val delivery = NtkManifestDelivery()

        assertNull(delivery.accept("  \n"))
        assertEquals("{\"ok\":true}", delivery.accept("{\"ok\":true}"))
    }
}
