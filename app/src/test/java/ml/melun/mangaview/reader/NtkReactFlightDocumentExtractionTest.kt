package ml.melun.mangaview.reader

import ml.melun.mangaview.mangaview.CustomHttpClient
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Contract tests for the React Flight transport layer of the strict document parser.
 *
 * A type-1 `self.__next_f.push` argument is a fragment of one continuous Flight stream. It is not
 * necessarily aligned to a Flight record or JSON token. Conversely, raw `T<hex-length>,...` text
 * records and JavaScript source containing a push-shaped string are not structural model evidence.
 */
class NtkReactFlightDocumentExtractionTest {
    private val path = "/manhwa/33727/1692251"
    private val lease = NtkDiscoveryLease(path, NtkDiscoveryGeneration(73L))

    @Test
    fun liveViewerModelRowInsideOneType1PushParses() {
        val row = "53:[\"\u0024\",\"\u0024L57\",null,${component(31)}]\n"
        val draft = parse(flightHtml(row))

        assertEquals(31, draft.pageCount)
        assertEquals("33727", draft.requestIdentity.normalizedSourceWorkId)
        assertEquals("1692251", draft.requestIdentity.normalizedEpisodeId)
        assertEquals((1..31).toList(), draft.orderedPages)
    }

    @Test
    fun type1TransportFragmentsAreConcatenatedWithoutInsertedCharacters() {
        val row = "53:[\"\u0024\",\"\u0024L57\",null,${component(31)}]\n"
        val firstCut = row.indexOf("sourceWorkId") + 6
        val secondCut = row.indexOf("imagesToken") + 7
        check(firstCut in 1 until secondCut && secondCut in 1 until row.length)
        val body = buildString {
            append("<html><body>")
            append(push(row.substring(0, firstCut)))
            append(push(row.substring(firstCut, secondCut)))
            append(push(row.substring(secondCut)))
            append("</body></html>")
        }

        val draft = parse(body)

        assertEquals(31, draft.pageCount)
        assertEquals((1..31).toList(), draft.orderedPages)
    }

    @Test
    fun completeRawReactServerComponentStreamParsesWithoutHtmlBootstrap() {
        val stream = buildString {
            append("1:\"\u0024Sreact.fragment\"\n")
            append(":HL[\"https://cdn.example/font.woff2\",\"font\",{}]\n")
            append("53:[\"\u0024\",\"\u0024L57\",null,${component(31)}]\n")
        }

        val draft = NtkEpisodeDocumentPlanParser.parse(
            lease,
            path,
            documentResponse(stream, "text/x-component; charset=utf-8")
        )

        assertEquals(31, draft.pageCount)
        assertEquals("33727", draft.requestIdentity.normalizedSourceWorkId)
        assertEquals("1692251", draft.requestIdentity.normalizedEpisodeId)
    }

    @Test
    fun rawFlightLookingBodyWithoutRscContentTypeIsRejected() {
        val stream = "53:[\"\u0024\",\"\u0024L57\",null,${component(31)}]\n"

        assertRejected {
            NtkEpisodeDocumentPlanParser.parse(
                lease,
                path,
                documentResponse(stream, "text/plain; charset=utf-8")
            )
        }
    }

    @Test
    fun malformedRawReactServerComponentStreamFailsClosed() {
        val stream = "not-a-flight-row:${component(31)}\n"

        assertRejected {
            NtkEpisodeDocumentPlanParser.parse(
                lease,
                path,
                documentResponse(stream, "text/x-component")
            )
        }
    }

    @Test
    fun lengthDelimitedTextRecordCannotSupplyEpisodeEvidence() {
        val decoy = component(31)
        val textRecord = "13:T${decoy.toByteArray(StandardCharsets.UTF_8).size.toString(16)},$decoy"

        assertRejected { parse(flightHtml(textRecord)) }
    }

    @Test
    fun lengthDelimitedTextIsSkippedAndFollowingStructuralModelStillParses() {
        val decoy = component(31).replace("fixture", "가짜 스크립트 본문")
        val textRecord =
            "13:T${decoy.toByteArray(StandardCharsets.UTF_8).size.toString(16)},$decoy"
        val modelRow = "53:[\"\u0024\",\"\u0024L57\",null,${component(31)}]\n"

        val draft = parse(flightHtml(textRecord + modelRow))

        assertEquals(31, draft.pageCount)
        assertEquals("33727", draft.requestIdentity.normalizedSourceWorkId)
        assertEquals("1692251", draft.requestIdentity.normalizedEpisodeId)
    }

    @Test
    fun idLessResourceHintRowIsIgnoredBeforeStructuralModel() {
        val hint = ":HL[\"https://cdn.example/font.woff2\",\"font\",{}]\n"
        val modelRow = "53:[\"\u0024\",\"\u0024L57\",null,${component(31)}]\n"

        val draft = parse(flightHtml(hint + modelRow))

        assertEquals(31, draft.pageCount)
        assertEquals("33727", draft.requestIdentity.normalizedSourceWorkId)
    }

    @Test
    fun pushTextInsideJavascriptStringCannotSupplyEpisodeEvidence() {
        val row = "53:[\"\u0024\",\"\u0024L57\",null,${component(31)}]\n"
        val pushExpression = "self.__next_f.push(${JSONArray().put(1).put(row)})"
        check('\'' !in pushExpression)
        val body = "<html><script>const inert = '$pushExpression';</script></html>"

        assertRejected { parse(body) }
    }

    @Test
    fun malformedType1PushMakesOtherwiseValidDocumentAmbiguous() {
        val row = "53:[\"\u0024\",\"\u0024L57\",null,${component(31)}]\n"
        val malformed = "<script>self.__next_f.push([1,\"ignored\",7])</script>"
        val body = "<html><body>$malformed${push(row)}</body></html>"

        assertRejected { parse(body) }
    }

    @Test
    fun duplicateStructuralModelComponentsAreRejected() {
        val first = "53:[\"\u0024\",\"\u0024L57\",null,${component(31)}]\n"
        val second = "54:[\"\u0024\",\"\u0024L57\",null,${component(31)}]\n"

        assertRejected { parse(flightHtml(first + second)) }
    }

    private fun parse(body: String): NtkEpisodeDocumentPlanDraft =
        NtkEpisodeDocumentPlanParser.parse(lease, path, documentResponse(body))

    private fun flightHtml(stream: String): String =
        "<html><body>${push(stream)}</body></html>"

    private fun push(fragment: String): String =
        "<script>self.__next_f.push(${JSONArray().put(1).put(fragment)})</script>"

    private fun documentResponse(
        body: String,
        contentType: String = "text/html; charset=utf-8"
    ): CustomHttpClient.NtkBoundHttpResponse {
        val request = CustomHttpClient.NtkBoundHttpRequest(
            "GET",
            "https://newtoki.example$path",
            emptyMap(),
            byteArrayOf()
        )
        return CustomHttpClient.NtkBoundHttpResponse(
            request,
            request.url,
            request.url,
            200,
            body.toByteArray(StandardCharsets.UTF_8),
            mapOf("content-type" to listOf(contentType)),
            true
        )
    }

    private fun component(pageCount: Int): String {
        val claims = """{"w":"33727","e":"1692251","t":"manhwa"}"""
        val token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(claims.toByteArray(StandardCharsets.UTF_8)) + ".signature"
        val metas = (1..pageCount).joinToString(",") { page ->
            """{"page":$page,"width":null,"height":null}"""
        }
        return """
            {"workTitle":"fixture","sourceWorkId":"33727","episodeId":"1692251",
             "imageMetas":[$metas],"imagesToken":"$token"}
        """.trimIndent().lineSequence().joinToString("") { it.trim() }
    }

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: NtkManifestEvidenceException) {
            rejected = true
        }
        assertTrue("ambiguous/non-structural Flight evidence must fail closed", rejected)
    }
}
