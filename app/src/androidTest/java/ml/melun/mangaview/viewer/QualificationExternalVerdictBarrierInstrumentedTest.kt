package ml.melun.mangaview.viewer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualificationExternalVerdictBarrierInstrumentedTest {
    @Test
    fun acceptsFreshExactHostVerdict() {
        val barrier = newBarrier()
        val sample = "sample-success"
        val writer = startHostWriter(barrier, sample)
        val accepted = barrier.await(sample)
        finish(writer)
        assertEquals(sha256(barrier.checkpointFile(sample).readBytes()), accepted.checkpointSha256)
    }

    @Test
    fun rejectsStaleNonce() {
        val barrier = newBarrier()
        val sample = "sample-stale-nonce"
        val writer = startHostWriter(barrier, sample) { verdict ->
            verdict.put("checkpointNonce", UUID.randomUUID().toString())
        }
        expectFailure { barrier.await(sample) }
        finish(writer)
    }

    @Test
    fun rejectsWrongCheckpointHash() {
        val barrier = newBarrier()
        val sample = "sample-wrong-hash"
        val writer = startHostWriter(barrier, sample) { verdict ->
            verdict.put("checkpointSha256", "f".repeat(64))
        }
        expectFailure { barrier.await(sample) }
        finish(writer)
    }

    @Test
    fun rejectsWrongPolicyHash() {
        val barrier = newBarrier()
        val sample = "sample-wrong-policy"
        val writer = startHostWriter(barrier, sample) { verdict ->
            verdict.put("policySha256", "b".repeat(64))
        }
        expectFailure { barrier.await(sample) }
        finish(writer)
    }

    @Test
    fun rejectsExplicitHostFailure() {
        val barrier = newBarrier()
        val sample = "sample-explicit-fail"
        val writer = startHostWriter(barrier, sample) { verdict -> verdict.put("passed", false) }
        expectFailure { barrier.await(sample) }
        finish(writer)
    }

    @Test
    fun rejectsFractionalSchema() {
        val barrier = newBarrier()
        val sample = "sample-fractional-schema"
        val writer = startHostWriter(barrier, sample) { verdict -> verdict.put("schema", 1.5) }
        expectFailure { barrier.await(sample) }
        finish(writer)
    }

    @Test
    fun rejectsCheckpointMutationWhileWaiting() {
        val barrier = newBarrier()
        val sample = "sample-checkpoint-mutation"
        val writer = startHostWriter(barrier, sample) {
            barrier.checkpointFile(sample).appendText(" ")
        }
        expectFailure { barrier.await(sample) }
        finish(writer)
    }

    @Test
    fun rejectsTimeoutWithoutHostVerdict() {
        val barrier = newBarrier(timeoutMillis = 150L)
        val started = SystemClock.elapsedRealtime()
        val failure = expectFailure { barrier.await("sample-timeout") }
        val elapsed = SystemClock.elapsedRealtime() - started
        assertNotNull(failure)
        assertTrue("timeout must remain bounded", elapsed < 2_000L)
    }

    @Test
    fun rejectsPreexistingCheckpoint() {
        val barrier = newBarrier()
        val sample = "sample-preexisting"
        barrier.checkpointFile(sample).writeText("stale", Charsets.UTF_8)
        expectFailure { barrier.await(sample) }
    }

    private fun newBarrier(timeoutMillis: Long = 2_000L): QualificationExternalVerdictBarrier {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir("qualification-verdict-barrier-tests"))
        return QualificationExternalVerdictBarrier(
            evidenceDirectory = directory,
            runId = "run-${UUID.randomUUID()}",
            corpusAttemptSha256 = "a".repeat(64),
            policySha256 = "c".repeat(64),
            timeoutMillis = timeoutMillis,
        )
    }

    private fun startHostWriter(
        barrier: QualificationExternalVerdictBarrier,
        sample: String,
        mutate: (JSONObject) -> Unit = {},
    ): WriterHandle {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                val checkpointFile = barrier.checkpointFile(sample)
                val checkpointBytes = awaitFile(checkpointFile)
                val checkpoint = JSONObject(String(checkpointBytes, StandardCharsets.UTF_8))
                val verdict = JSONObject()
                    .put("schema", 1)
                    .put("runId", checkpoint.getString("runId"))
                    .put("sampleKey", checkpoint.getString("sampleKey"))
                    .put("attemptSha256", checkpoint.getString("attemptSha256"))
                    .put("policySha256", checkpoint.getString("policySha256"))
                    .put("checkpointNonce", checkpoint.getString("checkpointNonce"))
                    .put("checkpointSha256", sha256(checkpointBytes))
                    .put("passed", true)
                mutate(verdict)
                writeAtomically(barrier.verdictFile(sample), verdict.toString().toByteArray(StandardCharsets.UTF_8))
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }.apply {
            name = "qualification-host-writer-$sample"
            isDaemon = true
            start()
        }
        return WriterHandle(thread, failure)
    }

    private fun awaitFile(file: File): ByteArray {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (!file.isFile) {
            if (SystemClock.elapsedRealtime() >= deadline) error("Checkpoint was not published: ${file.name}")
            Thread.sleep(10L)
        }
        return file.readBytes()
    }

    private fun finish(writer: WriterHandle) {
        writer.thread.join(2_000L)
        assertFalse("host writer did not finish", writer.thread.isAlive)
        assertTrue("host writer failed: ${writer.failure.get()}", writer.failure.get() == null)
    }

    private fun expectFailure(block: () -> Unit): Throwable {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull("Expected the external verdict barrier to fail", failure)
        return requireNotNull(failure)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File.createTempFile(".${target.name}-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class WriterHandle(
        val thread: Thread,
        val failure: AtomicReference<Throwable?>,
    )
}
