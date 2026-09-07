package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real host/ADB/trace handshake negative control; never a live-source or corpus pass. */
@RunWith(AndroidJUnit4::class)
class QualificationExternalGateFailureSmokeTest {
    @Test
    fun invalidFirstCollectionStopsBeforeSecondSample() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = requireNotNull(arguments.getString("corpusRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]+")))
        val root = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir("ux-evidence"))
        val directory = root.resolve("qualification-$runId")
        check(!directory.exists() && directory.mkdirs())
        val policyFile = java.io.File(requireNotNull(arguments.getString("corpusPolicyPath"))).canonicalFile
        check(policyFile.parentFile == root.canonicalFile)
        val policy = policyFile.readBytes()
        directory.resolve("policy.json").writeBytes(policy)
        val barrier = QualificationExternalVerdictBarrier(directory.resolve("external-verdicts"), runId,
            requireNotNull(arguments.getString("corpusAttemptSha256")), sha256(policy))
        val sampleKey = "single-$runId-1"
        val wrapper = directory.resolve(sampleKey)
        check(wrapper.mkdirs())
        wrapper.resolve("sample.json").writeText(JSONObject().put("sampleKey", sampleKey)
            .put("role", "INJECTED_INVALID_COLLECTION_NO_CORPUS_CREDIT").toString())
        val capture = wrapper.resolve("capture")
        check(capture.mkdir())
        // Deliberately omit image/row/ownership evidence. The real host verifier must reject it.
        capture.resolve("collection.json").writeText(JSONObject().put("sampleKey", sampleKey).toString())
        var secondSampleEntered = false
        val failure = runCatching {
            barrier.await(sampleKey)
            secondSampleEntered = true
        }.exceptionOrNull()
        try {
            assertTrue("Expected an explicit host rejection, not a timeout: $failure",
                failure?.message?.contains("Host verdict did not pass") == true)
            assertFalse(secondSampleEntered)
            assertFalse(JSONObject(barrier.verdictFile(sampleKey).readText()).getBoolean("passed"))
        } finally {
            directory.resolve("smoke-result.json").writeText(JSONObject()
                .put("mode", "INJECTED_FAILURE_NO_CORPUS_CREDIT")
                .put("explicitHostRejection", failure?.message?.contains("Host verdict did not pass") == true)
                .put("secondSampleEntered", secondSampleEntered).put("corpusCredit", 0).toString(2))
            directory.resolve("summary.json").writeText(JSONObject().put("runId", runId)
                .put("collectionCompleted", false).put("passed", false).put("consecutivePassed", 0).toString())
            directory.resolve("outcomes.json").writeText("[]")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
