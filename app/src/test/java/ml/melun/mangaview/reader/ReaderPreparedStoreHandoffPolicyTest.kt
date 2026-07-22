package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPreparedStoreHandoffPolicyTest {
    @Test
    fun publishResultDistinguishesAcceptedAndPostHandoffRejection() {
        assertEquals("ACCEPTED", ReaderPreparedStore.PublishResult.ACCEPTED.name)
        assertEquals("REJECTED_HANDOFF", ReaderPreparedStore.PublishResult.REJECTED_HANDOFF.name)
    }
}
