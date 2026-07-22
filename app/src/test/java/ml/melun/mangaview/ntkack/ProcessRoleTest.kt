package ml.melun.mangaview.ntkack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRoleTest {
    @Test
    fun onlyDedicatedSuffixIsAckProcess() {
        assertTrue(ProcessRole.isNtkAckProcess("ml.melun.mangaview:ntk_ack"))
        assertFalse(ProcessRole.isNtkAckProcess("ml.melun.mangaview"))
        assertFalse(ProcessRole.isNtkAckProcess("ml.melun.mangaview:ntk_ack_extra"))
        assertFalse(ProcessRole.isNtkAckProcess(null))
    }
}
