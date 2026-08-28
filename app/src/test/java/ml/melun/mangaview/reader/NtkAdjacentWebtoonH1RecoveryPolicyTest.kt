package ml.melun.mangaview.reader

import java.io.File
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
    fun currentHostEmulatorUsesAnIndependentBoundedH1RecoveryPool() {
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.WIFI_DIRECT_CURRENT_H1_RECOVERY_MAX_CONCURRENT == 16
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.WIFI_DIRECT_CURRENT_CALL_MAX_CONCURRENT == 8
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.WIFI_DIRECT_CURRENT_H1_RECOVERY_HEADER_MS == 2_500L
        )
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt"
        ).readText()
        val direct = source.substringAfter("private fun executeDirectWifiWebtoonH2(")
            .substringBefore("private fun executeSegmentedManhwa(")
        assertTrue(direct.contains("currentResumeRecoveryTag == null ||"))
        assertTrue(direct.contains("quarantineTag?.isValid == true"))
        assertTrue(direct.contains("clickOwnedDirectWifiCurrentWebtoonRecoveryPermits"))
        assertTrue(direct.contains("clickOwnedDirectWifiCurrentWebtoonRecoveryClient("))
        assertTrue(direct.contains("if (adjacentEligible)"))
        assertTrue(direct.contains("hostEmulatorDirectWifiCurrentFragmentedTlsPermits"))
        assertTrue(direct.contains("hostEmulatorDirectWifiCurrentOrdinaryWebtoonCallPermits"))
        assertTrue(direct.contains("wifiExactQuicSessionPool?.leaseOpeningSession("))
        assertTrue(source.contains("quicPool.preconnectOpeningSession("))
        val primaryMarker = direct.indexOf("val primaryFragmentedFailure =")
        val openingPrimaryQuic = direct.indexOf(
            "attemptQualifiedCurrentExactQuic()",
            startIndex = primaryMarker,
        )
        val fragmentedPrimary = direct.indexOf(
            "attemptHostEmulatorFragmentedTlsRecovery(",
            startIndex = primaryMarker,
        )
        assertTrue(primaryMarker >= 0)
        assertTrue(openingPrimaryQuic > primaryMarker)
        assertTrue(openingPrimaryQuic < fragmentedPrimary)
        assertTrue(fragmentedPrimary > primaryMarker)
        val ordinaryPermit = direct.indexOf("acquireCurrentOrdinaryCallPermitIfNeeded()")
        val fragmentedRecovery = direct.indexOf(
            "attemptHostEmulatorFragmentedTlsRecovery(",
            startIndex = ordinaryPermit,
        )
        assertTrue(ordinaryPermit >= 0)
        assertTrue(fragmentedRecovery > ordinaryPermit)
        assertTrue(direct.contains("ntkHostEmulatorWebtoonFragmentedTransportCreationLock"))
        assertTrue(source.contains(".callTimeout(0L, TimeUnit.MILLISECONDS)"))
        assertTrue(
            source.contains(
                "WIFI_DIRECT_CURRENT_FRAGMENTED_TLS_OPENING_PAGES"
            )
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

    @Test
    fun repeatedSocketFailureSuppressesOnlyAfterIndependentConfirmation() {
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy
                .shouldSuppressHostEmulatorSessionAfterSocketFailures(1)
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy
                .shouldSuppressHostEmulatorSessionAfterSocketFailures(2)
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy
                .shouldSuppressHostEmulatorSessionAfterSocketFailures(3)
        )
    }

    @Test
    fun exactQuicRecoveryOpensOnlyAfterEveryTcpReplicaIsIndependentlySuppressed() {
        val candidates = listOf("f1spard.site", "shaomoi.org", "xiaomichina.com")
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.areAllDirectWifiReplicaHostsSuppressed(
                candidates,
                setOf("f1spard.site", "shaomoi.org"),
            )
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.areAllDirectWifiReplicaHostsSuppressed(
                candidates,
                setOf("F1SPARD.SITE", "shaomoi.org", "xiaomichina.com"),
            )
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptHostEmulatorSocketExhaustedQuic(
                currentHostEmulatorRecovery = true,
                directWifiActive = true,
                allReplicaHostsSuppressed = true,
                sameNetwork = true,
                sameViewerGeneration = true,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptHostEmulatorSocketExhaustedQuic(
                currentHostEmulatorRecovery = true,
                directWifiActive = true,
                allReplicaHostsSuppressed = false,
                sameNetwork = true,
                sameViewerGeneration = true,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptHostEmulatorSocketExhaustedQuic(
                currentHostEmulatorRecovery = false,
                directWifiActive = true,
                allReplicaHostsSuppressed = true,
                sameNetwork = true,
                sameViewerGeneration = true,
                currentForegroundEpisode = true,
            )
        )
    }

    @Test
    fun fragmentedTlsRecoveryRequiresARepeatedSocketFailureAndExactOwnership() {
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptHostEmulatorFragmentedTlsRecovery(
                currentHostEmulatorRecovery = true,
                directWifiActive = true,
                repeatedSocketFailureConfirmed = true,
                sameNetwork = true,
                sameViewerGeneration = true,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptHostEmulatorFragmentedTlsRecovery(
                currentHostEmulatorRecovery = true,
                directWifiActive = true,
                repeatedSocketFailureConfirmed = false,
                sameNetwork = true,
                sameViewerGeneration = true,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptHostEmulatorFragmentedTlsRecovery(
                currentHostEmulatorRecovery = true,
                directWifiActive = true,
                repeatedSocketFailureConfirmed = true,
                sameNetwork = true,
                sameViewerGeneration = false,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptHostEmulatorFragmentedTlsRecovery(
                currentHostEmulatorRecovery = true,
                directWifiActive = true,
                repeatedSocketFailureConfirmed = true,
                sameNetwork = true,
                sameViewerGeneration = true,
                currentForegroundEpisode = false,
            )
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptHostEmulatorPrimaryFragmentedTls(
                currentHostEmulatorRecovery = true,
                directWifiActive = true,
                sameNetwork = true,
                sameViewerGeneration = true,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptHostEmulatorPrimaryFragmentedTls(
                currentHostEmulatorRecovery = true,
                directWifiActive = true,
                sameNetwork = true,
                sameViewerGeneration = false,
                currentForegroundEpisode = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptHostEmulatorPrimaryFragmentedTls(
                currentHostEmulatorRecovery = false,
                directWifiActive = true,
                sameNetwork = true,
                sameViewerGeneration = true,
                currentForegroundEpisode = true,
            )
        )
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt"
        ).readText()
        assertTrue(source.contains("exact-fragmented-tls-recovery"))
        assertTrue(source.contains("fragmentedTlsRecovery ->"))
        assertTrue(source.contains("NtkWebtoonBodyWallPolicy.directWifiSegmentWallMs("))
        assertTrue(source.contains("currentForegroundEpisode = currentForegroundEpisode"))
        assertFalse(source.contains("currentForegroundEpisode = true"))
        assertFalse(source.contains("fragmentedTlsRecovery -> 0L"))
    }
}
