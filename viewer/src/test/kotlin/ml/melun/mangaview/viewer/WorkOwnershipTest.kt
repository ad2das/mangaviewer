package ml.melun.mangaview.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkOwnershipTest {
    @Test
    fun everyClaimConsumesAUniqueSequenceIndependentOfAttemptAndKind() {
        val pageId = ViewerFixtures.manifest(1).pages.single().id
        var ownership = WorkOwnership()
        val sequences = mutableSetOf<Long>()

        repeat(1_000) { index ->
            val kind = if (index % 2 == 0) WorkKind.FETCH else WorkKind.DECODE
            val claim = ownership.claim(
                generation = 7L,
                pageId = pageId,
                kind = kind,
                attempt = 1,
                priority = WorkPriority.HARD,
            )
            assertTrue(sequences.add(claim.token.operationSequence))
            assertEquals(index + 1L, claim.token.operationSequence)
            ownership = claim.ownership.release(claim.token)
        }

        assertEquals(1_001L, ownership.nextOperationSequence)
    }

    @Test
    fun releasingAndClearingOwnersNeverRewindsTheSequence() {
        val pages = ViewerFixtures.manifest(2).pages
        val first = WorkOwnership().claim(
            9L,
            pages[0].id,
            WorkKind.DECODE,
            1,
            WorkPriority.HARD,
        )
        val cleared = first.ownership.clearDecodes()
        val second = cleared.claim(
            9L,
            pages[0].id,
            WorkKind.DECODE,
            1,
            WorkPriority.HARD,
        )

        assertNotEquals(first.token, second.token)
        assertTrue(second.token.operationSequence > first.token.operationSequence)
    }
}
