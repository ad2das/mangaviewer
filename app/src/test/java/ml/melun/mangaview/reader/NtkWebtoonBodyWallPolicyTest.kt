package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkWebtoonBodyWallPolicyTest {
    @Test
    fun wifiColdHeadersGetConnectionSetupTimeWhileCellularKeepsItsFastRing() {
        assertEquals(
            2_500L,
            NtkWebtoonReplicaHeaderPolicy.headerFailoverMs(
                cellularResilientTransport = false
            ),
        )
        assertEquals(
            2_500L,
            NtkWebtoonReplicaHeaderPolicy.headerFailoverMs(
                cellularResilientTransport = false,
                pageIndex = 0,
            ),
        )
        assertEquals(
            1_000L,
            NtkWebtoonReplicaHeaderPolicy.headerFailoverMs(
                cellularResilientTransport = true,
                pageIndex = 0,
            ),
        )
        assertEquals(
            2_500L,
            NtkWebtoonReplicaHeaderPolicy.headerFailoverMs(
                cellularResilientTransport = false,
                pageIndex = NtkWebtoonReplicaHeaderPolicy.WIFI_ENTRY_LAST_PAGE + 1,
            ),
        )
        assertEquals(
            1_000L,
            NtkWebtoonReplicaHeaderPolicy.headerFailoverMs(
                cellularResilientTransport = true
            ),
        )
    }

    @Test
    fun wifiUsesOneFastRingThenBoundedFocusedRecoveryOnly() {
        assertEquals(
            1,
            NtkWebtoonReplicaHeaderPolicy.replicaCycles(
                cellularResilientTransport = false
            ),
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.shouldAppendFocusedRecovery(
                cellularResilientTransport = false,
                webtoonReplica = true,
            )
        )
        assertEquals(2_500L, NtkWebtoonReplicaHeaderPolicy.WIFI_FOCUSED_RECOVERY_HEADER_MS)
        assertEquals(3, NtkWebtoonReplicaHeaderPolicy.WIFI_FOCUSED_RECOVERY_ATTEMPTS)
    }

    @Test
    fun cellularRetainsFourRingsAndNeverUsesWifiFocusedRecovery() {
        assertEquals(
            4,
            NtkWebtoonReplicaHeaderPolicy.replicaCycles(
                cellularResilientTransport = true
            ),
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAppendFocusedRecovery(
                cellularResilientTransport = true,
                webtoonReplica = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAppendFocusedRecovery(
                cellularResilientTransport = false,
                webtoonReplica = false,
            )
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptExhaustedExactQuicRecovery(
                cellularResilientTransport = false,
                webtoonReplica = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptExhaustedExactQuicRecovery(
                cellularResilientTransport = true,
                webtoonReplica = true,
            )
        )
    }

    @Test
    fun wifiUsesExactQuicPrimaryOnlyAfterTheEntryPages() {
        assertEquals(2, NtkWebtoonReplicaHeaderPolicy.WIFI_ENTRY_LAST_PAGE)
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptPrimaryExactQuic(
                wifiTransportActive = true,
                webtoonReplica = true,
                pageIndex = NtkWebtoonReplicaHeaderPolicy.WIFI_ENTRY_LAST_PAGE,
            )
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptPrimaryExactQuic(
                wifiTransportActive = true,
                webtoonReplica = true,
                pageIndex = NtkWebtoonReplicaHeaderPolicy.WIFI_ENTRY_LAST_PAGE + 1,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptPrimaryExactQuic(
                wifiTransportActive = false,
                webtoonReplica = true,
                pageIndex = 20,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptPrimaryExactQuic(
                wifiTransportActive = true,
                webtoonReplica = false,
                pageIndex = 20,
            )
        )
        assertEquals(
            1_800L,
            NtkWebtoonReplicaHeaderPolicy.primaryExactQuicTimeoutMs(
                NtkWebtoonReplicaHeaderPolicy.WIFI_SHORT_EPISODE_MAX_PAGES,
            ),
        )
        assertEquals(
            3_000L,
            NtkWebtoonReplicaHeaderPolicy.primaryExactQuicTimeoutMs(
                NtkWebtoonReplicaHeaderPolicy.WIFI_SHORT_EPISODE_MAX_PAGES + 1,
            ),
        )
        assertEquals(8, NtkWebtoonReplicaHeaderPolicy.WIFI_PROVISIONAL_RANGE_MAX_CONCURRENT)
        assertEquals(600L, NtkWebtoonReplicaHeaderPolicy.WIFI_PROVISIONAL_RANGE_ADMISSION_MS)
        assertEquals(1_200L, NtkWebtoonReplicaHeaderPolicy.WIFI_PROVISIONAL_RANGE_TOTAL_MS)
        assertEquals(
            100L,
            NtkWebtoonReplicaHeaderPolicy.provisionalRangeAdmissionMs(
                NtkWebtoonReplicaHeaderPolicy.WIFI_SHORT_EPISODE_MAX_PAGES,
            ),
        )
        assertEquals(
            700L,
            NtkWebtoonReplicaHeaderPolicy.provisionalRangeTotalMs(
                NtkWebtoonReplicaHeaderPolicy.WIFI_SHORT_EPISODE_MAX_PAGES,
            ),
        )
        assertEquals(
            600L,
            NtkWebtoonReplicaHeaderPolicy.provisionalRangeAttemptMs(
                NtkWebtoonReplicaHeaderPolicy.WIFI_SHORT_EPISODE_MAX_PAGES,
                preferredReplica = true,
            ),
        )
        assertEquals(
            50L,
            NtkWebtoonReplicaHeaderPolicy.provisionalRangeAttemptMs(
                NtkWebtoonReplicaHeaderPolicy.WIFI_SHORT_EPISODE_MAX_PAGES,
                preferredReplica = false,
            ),
        )
        assertEquals(
            1_200L,
            NtkWebtoonReplicaHeaderPolicy.provisionalRangeTotalMs(
                NtkWebtoonReplicaHeaderPolicy.WIFI_SHORT_EPISODE_MAX_PAGES + 1,
            ),
        )
        assertEquals(
            8_000L,
            NtkWebtoonReplicaHeaderPolicy.primaryExactQuicTimeoutMs(
                NtkWebtoonReplicaHeaderPolicy.WIFI_VERY_LARGE_EPISODE_PAGES,
            ),
        )
    }

    @Test
    fun timedOutWifiQuicKeepsOnlyAProvablyResumableImmutablePrefix() {
        val headers = mapOf(
            "Content-Length" to listOf("300000"),
            "Accept-Ranges" to listOf("bytes"),
            "ETag" to listOf("\"immutable-v1\""),
            "Content-Encoding" to listOf("identity"),
        )
        assertEquals(
            300_000L,
            NtkExactQuicPartialResumePolicy.expectedLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers,
            ),
        )
        assertEquals(
            300_000L,
            NtkExactQuicPartialResumePolicy.expectedLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 0,
                headers = headers,
            ),
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.expectedLength(
                logScope = "wifi_manhwa_primary",
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers,
            )
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.expectedLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.io.IOException("reset"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers,
            )
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.expectedLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind.FAILED,
                receivedBytes = 180_000,
                headers = headers,
            )
        )
        assertEquals(
            300_000L,
            NtkExactQuicPartialResumePolicy.expectedLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers - "Accept-Ranges",
            ),
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.expectedLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers + ("ETag" to listOf("W/\"weak\"")),
            )
        )
    }

    @Test
    fun timedOutWifiQuicRejectsTinyPrefixesButKeepsSubstantialSmallImagePrefixes() {
        assertFalse(
            NtkExactQuicPartialResumePolicy.isUsefulResumePrefix(
                receivedBytes = 6_500,
                expectedLength = 300_000L,
            )
        )
        assertTrue(
            NtkExactQuicPartialResumePolicy.isUsefulResumePrefix(
                receivedBytes = 32 * 1024,
                expectedLength = 300_000L,
            )
        )
        assertTrue(
            NtkExactQuicPartialResumePolicy.isUsefulResumePrefix(
                receivedBytes = 6_500,
                expectedLength = 20_000L,
            )
        )
        assertFalse(
            NtkExactQuicPartialResumePolicy.isUsefulResumePrefix(
                receivedBytes = 0,
                expectedLength = 20_000L,
            )
        )
    }

    @Test
    fun onlyDirectWifiWebtoonLimitsSerialRangeRecoveryToOneAttempt() {
        assertEquals(
            1,
            NtkReplicaRangeContinuationPolicy.maximumAttempts(
                webtoonReplica = true,
                wifiTransportActive = true,
                cellularResilientTransport = false,
                defaultMaximum = 3,
            )
        )
        assertEquals(
            3,
            NtkReplicaRangeContinuationPolicy.maximumAttempts(
                webtoonReplica = true,
                wifiTransportActive = true,
                cellularResilientTransport = true,
                defaultMaximum = 3,
            )
        )
        assertEquals(
            3,
            NtkReplicaRangeContinuationPolicy.maximumAttempts(
                webtoonReplica = false,
                wifiTransportActive = true,
                cellularResilientTransport = false,
                defaultMaximum = 3,
            )
        )
        assertEquals(
            3,
            NtkReplicaRangeContinuationPolicy.maximumAttempts(
                webtoonReplica = true,
                wifiTransportActive = false,
                cellularResilientTransport = false,
                defaultMaximum = 3,
            )
        )
    }

    @Test
    fun timedOutWifiQuicKeepsAnExactlyCompleteIdentityBodyWithoutRedownloading() {
        val headers = mapOf(
            "Content-Length" to listOf("180000"),
            "Content-Encoding" to listOf("identity"),
        )
        assertEquals(
            180_000L,
            NtkExactQuicPartialResumePolicy.completeBodyLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers,
            ),
        )
        assertEquals(
            180_000L,
            NtkExactQuicPartialResumePolicy.completeBodyLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind.SUCCEEDED,
                receivedBytes = 180_000,
                headers = headers - "Content-Length",
            ),
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.completeBodyLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers - "Content-Length",
            ),
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.completeBodyLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 179_999,
                headers = headers,
            )
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.completeBodyLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind.CANCELED,
                receivedBytes = 180_000,
                headers = headers,
            )
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.completeBodyLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers + ("Transfer-Encoding" to listOf("chunked")),
            )
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.completeBodyLength(
                logScope = "wifi_manhwa_primary",
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers,
            )
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.completeBodyLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers + ("Content-Encoding" to listOf("gzip")),
            )
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.completeBodyLength(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind.FAILED,
                receivedBytes = 180_000,
                headers = headers,
            )
        )
    }

    @Test
    fun decodedUnknownLengthWifiQuicPrefixRequiresStrongValidatorAndTerminalCancel() {
        val headers = mapOf(
            "Content-Encoding" to listOf("br"),
            "ETag" to listOf("\"immutable-v2\""),
        )
        val etagProof = NtkExactQuicPartialResumePolicy.provisionalUnknownLengthValidator(
            logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
            code = 200,
            error = java.net.SocketTimeoutException("timeout"),
            terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                .CANCELED_BY_INTERNAL_TIMEOUT,
            receivedBytes = 180_000,
            headers = headers,
        )
        assertEquals("ETag", etagProof?.headerName)
        assertEquals("\"immutable-v2\"", etagProof?.value)
        val lastModifiedProof =
            NtkExactQuicPartialResumePolicy.provisionalUnknownLengthValidator(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = (headers - "ETag") +
                    ("Last-Modified" to listOf("Wed, 21 Oct 2015 07:28:00 GMT")),
            )
        assertEquals("Last-Modified", lastModifiedProof?.headerName)
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", lastModifiedProof?.value)
        assertNull(
            NtkExactQuicPartialResumePolicy.provisionalUnknownLengthValidator(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind.FAILED,
                receivedBytes = 180_000,
                headers = headers,
            ),
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.provisionalUnknownLengthValidator(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers - "ETag",
            ),
        )
        assertNull(
            NtkExactQuicPartialResumePolicy.provisionalUnknownLengthValidator(
                logScope = NtkExactQuicPartialResumePolicy.WIFI_WEBTOON_PRIMARY_SCOPE,
                code = 200,
                error = java.net.SocketTimeoutException("timeout"),
                terminalKind = ml.melun.mangaview.activity.NtkQuicFetcher.TerminalKind
                    .CANCELED_BY_INTERNAL_TIMEOUT,
                receivedBytes = 180_000,
                headers = headers + ("Content-Length" to listOf("300000")),
            ),
        )
    }

    @Test
    fun wifiManhwaKeepsTheEntryPageOnTcpAndUsesExactQuicOnlyForTheTail() {
        assertFalse(
            NtkManhwaWifiTransportPolicy.shouldAttemptPrimaryExactQuic(
                wifiTransportActive = true,
                manhwaReplica = true,
                pageIndex = NtkManhwaWifiTransportPolicy.WIFI_ENTRY_LAST_PAGE,
            )
        )
        assertTrue(
            NtkManhwaWifiTransportPolicy.shouldAttemptPrimaryExactQuic(
                wifiTransportActive = true,
                manhwaReplica = true,
                pageIndex = NtkManhwaWifiTransportPolicy.WIFI_ENTRY_LAST_PAGE + 1,
                encodedPath = "/manhwa/1/2/p002.jpg",
            )
        )
        assertFalse(
            NtkManhwaWifiTransportPolicy.shouldAttemptPrimaryExactQuic(
                wifiTransportActive = true,
                manhwaReplica = true,
                pageIndex = 20,
                encodedPath = "/manhwa/1/2/p021.png",
            )
        )
        assertFalse(
            NtkManhwaWifiTransportPolicy.shouldAttemptPrimaryExactQuic(
                wifiTransportActive = false,
                manhwaReplica = true,
                pageIndex = 20,
                encodedPath = "/manhwa/1/2/p021.jpg",
            )
        )
        assertFalse(NtkManhwaWifiTransportPolicy.shouldTryExtensionFirst(1))
        assertTrue(NtkManhwaWifiTransportPolicy.shouldTryExtensionFirst(2))
    }

    @Test
    fun wifiManhwaInterleavesExtensionsBeforeTryingEveryReplicaOfOneSuffix() {
        assertEquals(
            listOf("gif-a", "webp-a", "png-a", "gif-b", "webp-b", "png-b"),
            NtkManhwaWifiTransportPolicy.interleaveReplicaRings(
                listOf(
                    listOf("gif-a", "gif-b"),
                    listOf("webp-a", "webp-b"),
                    listOf("png-a", "png-b"),
                )
            )
        )
    }

    @Test
    fun wifiReplicaPreferenceRequiresUsableHeadersAndExplicitMissesBeforeReordering() {
        val preference = NtkWebtoonReplicaPreference()
        val originalOrder = listOf("xiaomichina.com", "f1spard.site", "shaomoi.org")

        assertFalse(preference.recordUsableHeader("f1spard.site"))
        assertEquals(originalOrder, preference.orderHosts(originalOrder))
        assertFalse(preference.recordUsableHeader("F1SPARD.SITE"))
        assertFalse(preference.recordExplicitMiss("xiaomichina.com"))
        assertEquals(originalOrder, preference.orderHosts(originalOrder))
        assertTrue(preference.recordExplicitMiss("XIAOMICHINA.COM"))
        assertEquals(
            listOf("f1spard.site", "xiaomichina.com", "shaomoi.org"),
            preference.orderHosts(originalOrder),
        )
    }

    @Test
    fun wifiWebtoonDefinitivePrimaryMissGetsOneAlternateH3Origin() {
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptAlternateExactQuic(
                wifiTransportActive = true,
                webtoonReplica = true,
                pageIndex = NtkWebtoonReplicaHeaderPolicy.WIFI_ENTRY_LAST_PAGE + 1,
                primaryExplicitMisses = 1,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptAlternateExactQuic(
                wifiTransportActive = true,
                webtoonReplica = true,
                pageIndex = NtkWebtoonReplicaHeaderPolicy.WIFI_ENTRY_LAST_PAGE + 1,
                primaryExplicitMisses = 0,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptAlternateExactQuic(
                wifiTransportActive = false,
                webtoonReplica = true,
                pageIndex = NtkWebtoonReplicaHeaderPolicy.WIFI_ENTRY_LAST_PAGE + 1,
                primaryExplicitMisses = 1,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptAlternateExactQuic(
                wifiTransportActive = true,
                webtoonReplica = true,
                pageIndex = NtkWebtoonReplicaHeaderPolicy.WIFI_ENTRY_LAST_PAGE,
                primaryExplicitMisses = 1,
            )
        )
    }

    @Test
    fun responsiveBodyKeepsItsOriginalStream() {
        assertFalse(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.INITIAL_SEGMENT_WALL_MS - 1L,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
            )
        )
    }

    @Test
    fun wallBoundBodyMovesOnlyAUsefulUntouchedSuffix() {
        assertTrue(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.INITIAL_SEGMENT_WALL_MS,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
            )
        )
    }

    @Test
    fun nearlyCompleteBodyGetsTailGraceInsteadOfAReplicaRestart() {
        assertFalse(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.INITIAL_SEGMENT_WALL_MS,
                deliveredBytes = 300_000L - NtkWebtoonBodyWallPolicy.TAIL_GRACE_BYTES,
                expectedLength = 300_000L,
            )
        )
    }

    @Test
    fun noDeliveredPrefixCannotBecomeARangeContinuation() {
        assertFalse(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.INITIAL_SEGMENT_WALL_MS,
                deliveredBytes = 0L,
                expectedLength = 300_000L,
            )
        )
    }

    @Test
    fun onlyEntryViewportPagesUseTheShortExactSuffixWall() {
        assertEquals(
            NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS,
            NtkWebtoonBodyWallPolicy.segmentWallMs(0),
        )
        assertEquals(
            NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS,
            NtkWebtoonBodyWallPolicy.segmentWallMs(
                NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_LAST_PAGE
            ),
        )
        assertEquals(
            NtkWebtoonBodyWallPolicy.INITIAL_SEGMENT_WALL_MS,
            NtkWebtoonBodyWallPolicy.segmentWallMs(
                NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_LAST_PAGE + 1
            ),
        )
    }

    @Test
    fun entryViewportWallMovesOnlyTheExactUntouchedSuffix() {
        assertFalse(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS - 1L,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
                segmentWallMs = NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS,
            )
        )
        assertTrue(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
                segmentWallMs = NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS,
            )
        )
    }

}
