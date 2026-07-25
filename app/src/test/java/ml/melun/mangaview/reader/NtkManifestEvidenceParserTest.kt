package ml.melun.mangaview.reader

import ml.melun.mangaview.mangaview.CustomHttpClient
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class NtkManifestEvidenceParserTest {
    private val path = "/manhwa/33727/1692251"
    private val lease = NtkDiscoveryLease(path, NtkDiscoveryGeneration(41L))

    @Test
    fun documentAndApiAreParsedExactlyOnceIntoBoundImmutableEvidence() {
        NtkEpisodeDocumentPlanParser.resetInvocationCountForTest()
        NtkViewerImageApiAuthorityParser.resetInvocationCountForTest()
        val draft = parseDraft(component(pageCount = 3))
        val request = apiRequest(draft)
        val response = response(
            request,
            200,
            """
                {"ok":true,"images":[
                  {"page":1,"src":"https://img.example/cv/a.jpg"},
                  {"page":2,"src":"","srcCandidates":["https://img.example/cv/b.jpg"]},
                  {"page":3,"src":"https://img.example/cv/c.jpg"}
                ]}
            """.trimIndent()
        )

        val envelope = NtkViewerImageApiAuthorityParser.parse(draft, request, response)
        val plan = bind(draft, envelope, response.bodyBytes)

        assertEquals(3, plan.pageCount)
        assertEquals("/api/manhwa-images", plan.proof.requestIdentity.normalizedEndpointPath)
        assertEquals(3, envelope.orderedAssets.size)
        assertEquals("https://img.example/cv/b.jpg", envelope.orderedAssets[1])
        assertEquals(1L, NtkEpisodeDocumentPlanParser.invocationCount())
        assertEquals(1L, NtkViewerImageApiAuthorityParser.invocationCount())
        assertTrue(NtkStripDigests.isSha256(envelope.orderedAssetsDigestSha256))
    }

    @Test
    fun fixed31DocumentPlanParsesExactlyOnce() {
        NtkEpisodeDocumentPlanParser.resetInvocationCountForTest()
        val body =
            """<html><script type="application/json" id="theme-viewer-data">""" +
                themeComponent(pageCount = 31) +
                "</script></html>"
        val response = documentResponse(body)

        assertEquals(
            31,
            NtkEpisodeDocumentPlanParser.completeNumericPageCountHint(lease, path, response),
        )
        val draft = NtkEpisodeDocumentPlanParser.parse(lease, path, response)
        val assets = (1..31).map { "https://img.example/cv/$it.jpg" }
        val plan = draft.bind(
            NtkQuarantineAssetEvidence.create(
                path,
                lease.generationValue(),
                draft.requestIdentity.identityDigestSha256,
                assets,
                validApiBody(31).toByteArray()
            )
        )

        assertEquals(31, draft.pageCount)
        assertEquals((1..31).toList(), draft.orderedPages)
        assertEquals(1L, NtkEpisodeDocumentPlanParser.invocationCount())
        assertEquals(body.toByteArray().size, draft.responseBody.size)
        assertEquals(
            NtkStripDigests.sha256Bytes(body.toByteArray()),
            plan.proof.responseBodySha256
        )
        assertTrue(NtkStripDigests.isSha256(plan.proof.proofDigestSha256))
        assertTrue(NtkStripDigests.isSha256(plan.proof.requestIdentity.identityDigestSha256))
    }

    @Test
    fun fixed31ReactFlightStringIsDecodedStructurallyWithoutBlindQuoteReplacement() {
        NtkEpisodeDocumentPlanParser.resetInvocationCountForTest()
        val component = themeComponent(pageCount = 31)
        val flightText = "53:[\"${'$'}\",\"${'$'}L57\",null,$component]"
        val flightArray = JSONArray().put(1).put(flightText).toString()
        val body = "<html><script>self.__next_f.push($flightArray)</script></html>"

        val response = documentResponse(body)
        assertEquals(
            31,
            NtkEpisodeDocumentPlanParser.completeNumericPageCountHint(lease, path, response),
        )
        val draft = NtkEpisodeDocumentPlanParser.parse(lease, path, response)

        assertEquals(31, draft.pageCount)
        assertEquals("33727", draft.requestIdentity.normalizedSourceWorkId)
        assertEquals("1692251", draft.requestIdentity.normalizedEpisodeId)
        assertEquals((1..31).toList(), draft.orderedPages)
        assertEquals(1L, NtkEpisodeDocumentPlanParser.invocationCount())
    }

    @Test
    fun fixed31ApiBodyParsesExactlyOnce() {
        NtkViewerImageApiAuthorityParser.resetInvocationCountForTest()
        val draft = parseDraft(component(31))
        val request = apiRequest(draft)
        val body = validApiBody(31)
        val response = response(request, 200, body)

        val envelope = NtkViewerImageApiAuthorityParser.parse(draft, request, response)
        val plan = bind(draft, envelope, response.bodyBytes)

        assertEquals(31, envelope.orderedAssets.size)
        assertEquals(
            (1..31).map { "https://img.example/cv/$it.jpg" },
            envelope.orderedAssets
        )
        assertEquals(31, envelope.orderedAssets.toSet().size)
        assertEquals(1L, NtkViewerImageApiAuthorityParser.invocationCount())
        assertTrue(response.consumedToEof)
        assertEquals(body.toByteArray().size, response.bodyBytes.size)
        assertFalse(envelope.orderedAssetsDigestSha256.isBlank())
        assertEquals(plan.proof.proofDigestSha256, envelope.documentPlanProofDigestSha256)
        assertEquals(
            plan.proof.requestIdentity.identityDigestSha256,
            envelope.viewerImageRequestIdentityDigestSha256
        )
    }

    @Test
    fun apiKeepsOpeningViewportOnOnePoolThenBalancesExplicitReplicaCandidates() {
        val draft = parseDraft(component(3))
        val request = apiRequest(draft)
        val response = response(
            request,
            200,
            """{"ok":true,"images":[
                {"page":1,"src":"https://z.example/cv/1.jpg","srcCandidates":["https://a.example/cv/1.jpg","https://m.example/cv/1.jpg"]},
                {"page":2,"src":"https://z.example/cv/2.jpg","srcCandidates":["https://a.example/cv/2.jpg","https://m.example/cv/2.jpg"]},
                {"page":3,"src":"https://z.example/cv/3.jpg","srcCandidates":["https://a.example/cv/3.jpg","https://m.example/cv/3.jpg"]}
            ]}"""
        )

        val envelope = NtkViewerImageApiAuthorityParser.parse(draft, request, response)

        assertEquals(
            listOf(
                "https://a.example/cv/1.jpg",
                "https://a.example/cv/2.jpg",
                "https://z.example/cv/3.jpg"
            ),
            envelope.orderedAssets
        )
        assertEquals(
            "ntk-viewer-assets-balanced-replica-v2",
            envelope.orderedAssetSelectionPolicyVersion
        )
    }

    @Test
    fun signedApiAcceptsOrderedBoardUploadPageAssets() {
        val draft = parseDraft(component(2))
        val request = apiRequest(draft)
        val response = response(
            request,
            200,
            """{"ok":true,"images":[
                {"page":1,"src":"https://aws-cdn1.site/board_uploads/2026/07/18/page-a.jpg"},
                {"page":2,"src":"https://aws-cdn1.site/board_uploads/2026/07/18/page-b.png"}
            ]}"""
        )

        val envelope = NtkViewerImageApiAuthorityParser.parse(draft, request, response)

        assertEquals(
            listOf(
                "https://aws-cdn1.site/board_uploads/2026/07/18/page-a.jpg",
                "https://aws-cdn1.site/board_uploads/2026/07/18/page-b.png"
            ),
            envelope.orderedAssets
        )
    }

    @Test
    fun documentRejectsDuplicateComponentPayloads() {
        assertRejected {
            parseDraft(component(2) + component(2))
        }
    }

    @Test
    fun documentRejectsNonContiguousPagesAndConflictingCount() {
        assertRejected {
            parseDraft(component(3, pages = listOf(1, 3, 4)))
        }
        assertRejected {
            parseDraft(component(3, explicitCount = 4))
        }
    }

    @Test
    fun documentRejectsTokenOrPathIdentityMismatch() {
        assertRejected {
            parseDraft(component(2, tokenWorkId = "99999"))
        }
        assertRejected {
            val otherPath = "/manhwa/33727/1692252"
            NtkEpisodeDocumentPlanParser.parse(
                NtkDiscoveryLease(otherPath, NtkDiscoveryGeneration(42L)),
                otherPath,
                documentResponse(component(2))
            )
        }
    }

    @Test
    fun apiRejectsWrongRequestIdentityAndWrongPageSlots() {
        val plan = parseDraft(component(2))
        val wrongIdentity = apiRequest(
            plan,
            """{"workId":"33727","episodeId":"999","token":"${plan.imagesToken}"}"""
        )
        assertRejected {
            NtkViewerImageApiAuthorityParser.parse(
                plan,
                wrongIdentity,
                response(wrongIdentity, 200, validApiBody(2))
            )
        }
        val request = apiRequest(plan)
        assertRejected {
            NtkViewerImageApiAuthorityParser.parse(
                plan,
                request,
                response(
                    request,
                    200,
                    """{"ok":true,"images":[
                        {"page":1,"src":"https://img.example/cv/a.jpg"},
                        {"page":3,"src":"https://img.example/cv/b.jpg"}]}"""
                )
            )
        }
    }

    @Test
    fun apiRejectsCountErrorsGuardErrorsAndUntrustedAssets() {
        val plan = parseDraft(component(2))
        val request = apiRequest(plan)
        listOf(
            """{"ok":true,"images":[{"page":1,"src":"https://img.example/cv/a.jpg"}]}""",
            """{"ok":false,"ad_ack_required":true,"images":[]}""",
            """{"ok":true,"error":"bad","images":[]}""",
            """{"ok":true,"images":[
                {"page":1,"src":"http://img.example/cv/a.jpg"},
                {"page":2,"src":"https://img.example/cv/b.jpg"}]}""",
            """{"ok":true,"images":[
                {"page":1,"src":"https://img.example/api/a.jpg"},
                {"page":2,"src":"https://img.example/cv/b.jpg"}]}""",
            """{"ok":true,"images":[
                {"page":1,"src":"https://img.example/cv/a.jpg"},
                {"page":2,"src":"https://img.example/cv/a.jpg"}]}"""
        ).forEach { body ->
            assertRejected {
                NtkViewerImageApiAuthorityParser.parse(
                    plan,
                    request,
                    response(request, 200, body)
                )
            }
        }
    }

    private fun parseDraft(component: String): NtkEpisodeDocumentPlanDraft =
        NtkEpisodeDocumentPlanParser.parse(
            lease,
            path,
            documentResponse(
                "<html><script type=\"application/json\" id=\"theme-viewer-data\">" +
                    component +
                    "</script></html>"
            )
        )

    private fun bind(
        draft: NtkEpisodeDocumentPlanDraft,
        envelope: NtkExactViewerImageApiEnvelope,
        evidenceBody: ByteArray
    ): NtkProvisionalEpisodePlan = draft.bind(
        NtkQuarantineAssetEvidence.create(
            path,
            draft.discoveryGeneration,
            draft.requestIdentity.identityDigestSha256,
            envelope.orderedAssets,
            evidenceBody
        )
    )

    private fun documentResponse(body: String): CustomHttpClient.NtkBoundHttpResponse {
        val request = CustomHttpClient.NtkBoundHttpRequest(
            "GET",
            "https://newtoki.example$path",
            emptyMap(),
            byteArrayOf()
        )
        return response(request, 200, body, "https://newtoki.example$path")
    }

    private fun apiRequest(
        plan: NtkEpisodeDocumentPlanDraft,
        body: String = """{"workId":"33727","episodeId":"1692251","token":"${plan.imagesToken}"}"""
    ) = CustomHttpClient.NtkBoundHttpRequest(
        "POST",
        "https://newtoki.example/api/manhwa-images",
        mapOf("content-type" to "application/json"),
        body.toByteArray()
    )

    private fun response(
        request: CustomHttpClient.NtkBoundHttpRequest,
        status: Int,
        body: String,
        finalUrl: String = request.url
    ) = CustomHttpClient.NtkBoundHttpResponse(
        request,
        request.url,
        finalUrl,
        status,
        body.toByteArray(),
        mapOf("content-type" to listOf("application/json")),
        true
    )

    private fun component(
        pageCount: Int,
        pages: List<Int> = (1..pageCount).toList(),
        explicitCount: Int = pageCount,
        tokenWorkId: String = "33727"
    ): String {
        val token = token(tokenWorkId, "1692251", "manhwa")
        val metas = pages.joinToString(",") { """{"page":$it}""" }
        return """
            {"sourceWorkId":"33727","episodeId":"1692251","imageCount":$explicitCount,
             "imageMetas":[$metas],"imagesToken":"$token"}
        """.trimIndent()
    }

    private fun themeComponent(pageCount: Int): String {
        val value = token("33727", "1692251", "manhwa")
        val images = (1..pageCount).joinToString(",") {
            """{"width":null,"height":null,"page":$it}"""
        }
        return """
            {"sourceWorkId":"33727","episodeId":"1692251","token":"$value",
             "scopePath":"$path","imageApiPath":"/api/manhwa-images","images":[$images]}
        """.trimIndent().lineSequence().joinToString("") { it.trim() }
    }

    private fun token(workId: String, episodeId: String, segment: String): String {
        val payload = """{"w":"$workId","e":"$episodeId","t":"$segment"}"""
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8)) + ".signature"
    }

    private fun validApiBody(count: Int): String =
        """{"ok":true,"images":[${(1..count).joinToString(",") {
            """{"page":$it,"src":"https://img.example/cv/$it.jpg"}"""
        }}]}"""

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: NtkManifestEvidenceException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
