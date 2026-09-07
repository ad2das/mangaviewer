package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDeliveryRedrivePolicyTest {
    @Test
    fun onlyTheFirstExactIncompleteRedeliveryRestartsNavigation() {
        assertTrue(NtkDeliveryRedrivePolicy.shouldRedrive(
            true, false, 0, NtkAckPreparationState.COLD, authorizationProgressed = false,
        ))
        assertFalse(NtkDeliveryRedrivePolicy.shouldRedrive(
            false, false, 0, NtkAckPreparationState.COLD, authorizationProgressed = false,
        ))
        assertFalse(NtkDeliveryRedrivePolicy.shouldRedrive(
            true, true, 0, NtkAckPreparationState.COLD, authorizationProgressed = false,
        ))
        assertFalse(NtkDeliveryRedrivePolicy.shouldRedrive(
            true, false, 1, NtkAckPreparationState.COLD, authorizationProgressed = false,
        ))
        assertFalse(NtkDeliveryRedrivePolicy.shouldRedrive(
            true, false, 0, NtkAckPreparationState.CHALLENGE_READY,
            authorizationProgressed = false,
        ))
        assertFalse(NtkDeliveryRedrivePolicy.shouldRedrive(
            true, false, 0, NtkAckPreparationState.COLD,
            authorizationProgressed = true,
        ))
    }
}
