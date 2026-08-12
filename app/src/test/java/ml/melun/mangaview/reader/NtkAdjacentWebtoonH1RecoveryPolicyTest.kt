package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentWebtoonH1RecoveryPolicyTest {
    private fun eligible(
        directWifi: Boolean = true,
        cellular: Boolean = false,
        exactAdjacentProof: Boolean = true,
        adjacentGrant: Boolean = true,
        sameNetwork: Boolean = true,
        sameGeneration: Boolean = true,
        pageIndex: Int = 0,
        pageCount: Int = 68,
    ): Boolean = NtkWebtoonReplicaHeaderPolicy
        .shouldAttemptDirectWifiAdjacentH1Recovery(
            directWifi,
            cellular,
            exactAdjacentProof,
            adjacentGrant,
            sameNetwork,
            sameGeneration,
            pageIndex,
            pageCount,
        )

    @Test
    fun exactCompletionGatedRunwayCanRecoverAfterItsH2RingFails() {
        repeat(4) { pageIndex ->
            assertTrue(eligible(pageIndex = pageIndex))
        }
    }

    @Test
    fun laterAdjacentPagesCannotEnterTheRecoveryPool() {
        assertFalse(eligible(pageIndex = 4))
        assertFalse(eligible(pageIndex = 67, pageCount = 68))
    }

    @Test
    fun currentUnprovedOrStaleTransportCannotEnterTheRecoveryPool() {
        assertFalse(eligible(exactAdjacentProof = false))
        assertFalse(eligible(adjacentGrant = false))
        assertFalse(eligible(sameNetwork = false))
        assertFalse(eligible(sameGeneration = false))
    }

    @Test
    fun mobileAndSniTransportCannotEnterTheRecoveryPool() {
        assertFalse(eligible(directWifi = false))
        assertFalse(eligible(cellular = true))
    }

    private fun currentEligible(
        directWifi: Boolean = true,
        cellular: Boolean = false,
        emulator: Boolean = true,
        productionStrict: Boolean = true,
        currentForeground: Boolean = true,
        sameNetwork: Boolean = true,
        sameGeneration: Boolean = true,
    ): Boolean = NtkWebtoonReplicaHeaderPolicy
        .shouldAttemptDirectWifiCurrentH1Recovery(
            directWifi,
            cellular,
            emulator,
            productionStrict,
            currentForeground,
            sameNetwork,
            sameGeneration,
        )

    @Test
    fun exactCurrentEpisodeCanRecoverOnlyOnTheHostEmulator() {
        assertTrue(currentEligible())
        assertFalse(currentEligible(emulator = false))
        assertFalse(currentEligible(productionStrict = false))
        assertFalse(currentEligible(currentForeground = false))
    }

    @Test
    fun currentRecoveryRejectsMobileSniAndStaleOwnership() {
        assertFalse(currentEligible(directWifi = false))
        assertFalse(currentEligible(cellular = true))
        assertFalse(currentEligible(sameNetwork = false))
        assertFalse(currentEligible(sameGeneration = false))
    }

    @Test
    fun taggedCurrentResumeUsesOneCompleteH2RingBeforeH1Recovery() {
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.directWifiH2RecoveryCycles(
                logicalAttemptOrdinal = 1,
                currentHostEmulatorResumeRecovery = true,
            ) == 1
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.directWifiH2RecoveryCycles(
                logicalAttemptOrdinal = 1,
                currentHostEmulatorResumeRecovery = false,
            ) == NtkWebtoonReplicaHeaderPolicy.WIFI_DIRECT_H2_INITIAL_RECOVERY_CYCLES
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.directWifiH2RecoveryCycles(
                logicalAttemptOrdinal = 2,
                currentHostEmulatorResumeRecovery = true,
            ) == 1
        )
    }

    @Test
    fun exactHostEmulatorAdjacentRunwayUsesOneCompleteH2RingBeforeH1Recovery() {
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.directWifiH2RecoveryCycles(
                logicalAttemptOrdinal = 1,
                adjacentHostEmulatorRunwayRecovery = true,
            ) == 1
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.directWifiH2RecoveryCycles(
                logicalAttemptOrdinal = 1,
                adjacentHostEmulatorRunwayRecovery = false,
            ) == NtkWebtoonReplicaHeaderPolicy.WIFI_DIRECT_H2_INITIAL_RECOVERY_CYCLES
        )
    }

    @Test
    fun emulatorDefinitiveMissIsCallLocalAndTransportHealthIsStrictSessionScoped() {
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.isolateDefinitiveMissToLogicalCall(true)
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.isolateDefinitiveMissToLogicalCall(false)
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.hostEmulatorDirectWifiH2SourceSessionId(
                strictSessionId = 101L,
                quarantineSessionId = 102L,
            ) == 101L
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.hostEmulatorDirectWifiH2SourceSessionId(
                strictSessionId = null,
                quarantineSessionId = 102L,
            ) == 102L
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.hostEmulatorDirectWifiH2SourceSessionId(
                strictSessionId = null,
                quarantineSessionId = null,
            ) == null
        )
    }
}
