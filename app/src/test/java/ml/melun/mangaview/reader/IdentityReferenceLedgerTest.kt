package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityReferenceLedgerTest {
    @Test
    fun duplicateReferencesRetireOnlyAfterLastOwnerLeaves() {
        val ledger = IdentityReferenceLedger<Any>()
        val bitmapIdentity = Any()

        ledger.retain(bitmapIdentity)
        ledger.retain(bitmapIdentity)
        ledger.release(bitmapIdentity)

        assertTrue(ledger.references(bitmapIdentity))
        assertEquals(1, ledger.referenceCountForTest(bitmapIdentity))

        ledger.release(bitmapIdentity)
        assertFalse(ledger.references(bitmapIdentity))
    }

    @Test
    fun identityRatherThanEqualsDefinesOwnership() {
        data class EqualValue(val id: Int)
        val first = EqualValue(7)
        val second = EqualValue(7)
        val ledger = IdentityReferenceLedger<EqualValue>()

        ledger.retain(first)

        assertTrue(ledger.references(first))
        assertFalse(ledger.references(second))
    }

    @Test
    fun collectionReplacementPreservesSharedAndDuplicateCounts() {
        val ledger = IdentityReferenceLedger<Any>()
        val shared = Any()
        val outgoing = Any()
        val incoming = Any()
        ledger.retainAll(listOf(shared, shared, outgoing))

        ledger.replaceAll(
            listOf(shared, shared, outgoing),
            listOf(shared, incoming),
        )

        assertEquals(1, ledger.referenceCountForTest(shared))
        assertFalse(ledger.references(outgoing))
        assertTrue(ledger.references(incoming))
    }
}
