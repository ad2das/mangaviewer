package ml.melun.mangaview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashExitPolicyTest {
    @Test fun cachedReclaimIsNormalCleanup() {
        assertFalse(CrashExitPolicy.abnormal(CrashExitPolicy.REASON_LOW_MEMORY, CrashExitPolicy.IMPORTANCE_CACHED))
        assertFalse(CrashExitPolicy.abnormal(CrashExitPolicy.REASON_LOW_MEMORY, 1000))
        assertFalse(CrashExitPolicy.abnormal(CrashExitPolicy.REASON_OTHER, CrashExitPolicy.IMPORTANCE_CACHED))
    }

    @Test fun killOfAProcessTheUserCouldSeeStaysAbnormal() {
        assertTrue(CrashExitPolicy.abnormal(CrashExitPolicy.REASON_LOW_MEMORY, 200))
        assertTrue(CrashExitPolicy.abnormal(CrashExitPolicy.REASON_LOW_MEMORY, 300))
        assertTrue(CrashExitPolicy.abnormal(CrashExitPolicy.REASON_OTHER, 100))
    }

    @Test fun crashesAndAnrsStayAbnormalAtAnyImportance() {
        assertTrue(CrashExitPolicy.abnormal(4, CrashExitPolicy.IMPORTANCE_CACHED))
        assertTrue(CrashExitPolicy.abnormal(5, 1000))
        assertTrue(CrashExitPolicy.abnormal(6, CrashExitPolicy.IMPORTANCE_CACHED))
    }
}
