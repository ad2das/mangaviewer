package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictAdjacentPhysicalIntentPromotionArchitectureTest {
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()

    @Test
    fun speculativeOwnerIsPromotedWhenItsExactPageBecomesThePhysicalBlocker() {
        assertTrue(session.contains("val exactAdjacentPhysicalIntent: AtomicBoolean"))
        assertTrue(
            session.contains(
                "owner.exactAdjacentPhysicalIntent.compareAndSet(false, true)",
            ),
        )
        assertTrue(session.contains("scheduleStrictAdjacentExactRehydrate(owner, true)"))
        assertTrue(
            session.contains(
                "!flight.exactAdjacentPhysicalIntent.get() &&\n" +
                    "                    !flight.hostPressurePhysicalReentry.get()",
            ),
        )
    }
}
