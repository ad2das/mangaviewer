package ml.melun.mangaview.viewer

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * Per-sample handoff from an instrumentation measurement to an independently written host
 * verdict. Callers use this only after the viewer and its memory accounting have been closed.
 */
internal class QualificationExternalVerdictBarrier(
    evidenceDirectory: File,
    val runId: String,
    val corpusAttemptSha256: String,
    val policySha256: String,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    val barrierDirectory: File = evidenceDirectory
    val attemptSha256: String get() = corpusAttemptSha256

    init {
        requireSafePath(runId, "runId")
        requireHash(corpusAttemptSha256, "corpusAttemptSha256")
        requireHash(policySha256, "policySha256")
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        check(barrierDirectory.mkdirs() || barrierDirectory.isDirectory) {
            "Could not create evidence directory: ${barrierDirectory.absolutePath}"
        }
    }

    fun checkpointFile(sampleKey: String): File = fileFor(sampleKey, "checkpoint")

    fun verdictFile(sampleKey: String): File = fileFor(sampleKey, "verdict")

    /**
     * Publishes one immutable checkpoint, then waits for the host verdict outside the measured
     * sample. Any existing checkpoint, malformed verdict, mismatch, explicit failure, timeout,
     * or interruption fails closed.
     */
    fun await(sampleKey: String): AcceptedVerdict {
        requireSafePath(sampleKey, "sampleKey")
        val checkpoint = checkpointFile(sampleKey)
        val verdict = verdictFile(sampleKey)
        check(!checkpoint.exists()) {
            "Duplicate checkpoint/sampleKey: ${checkpoint.name}"
        }

        val checkpointNonce = UUID.randomUUID().toString()
        val checkpointBytes = JSONObject()
            .put("schema", SCHEMA)
            .put("runId", runId)
            .put("sampleKey", sampleKey)
            .put("attemptSha256", corpusAttemptSha256)
            .put("policySha256", policySha256)
            .put("checkpointNonce", checkpointNonce)
            .put("status", READY_STATUS)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val checkpointSha256 = sha256(checkpointBytes)
        writeImmutable(checkpoint, checkpointBytes)
        check(checkpoint.readBytes().contentEquals(checkpointBytes)) {
            "Checkpoint changed after atomic publication: ${checkpoint.name}"
        }
        Log.i(LOG_TAG, "READY $sampleKey")

        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            check(!Thread.currentThread().isInterrupted) {
                "Interrupted waiting for host verdict"
            }
            if (verdict.exists()) {
                check(checkpoint.readBytes().contentEquals(checkpointBytes)) {
                    "Checkpoint changed while awaiting the host verdict"
                }
                val verdictBytes = try {
                    Files.readAllBytes(verdict.toPath())
                } catch (failure: Exception) {
                    throw IllegalStateException("Could not read host verdict: ${verdict.name}", failure)
                }
                return validateVerdict(
                    sampleKey,
                    checkpointNonce,
                    checkpointSha256,
                    verdictBytes,
                )
            }

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val remaining = timeoutMillis - elapsed
            if (remaining <= 0L) {
                error("Timed out waiting for host verdict: ${verdict.name}")
            }
            try {
                Thread.sleep(minOf(POLL_INTERVAL_MILLIS, remaining))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted waiting for host verdict", interrupted)
            }
        }
    }

    private fun validateVerdict(
        sampleKey: String,
        checkpointNonce: String,
        checkpointSha256: String,
        bytes: ByteArray,
    ): AcceptedVerdict {
        val json = try {
            JSONObject(String(bytes, StandardCharsets.UTF_8))
        } catch (failure: Exception) {
            throw IllegalStateException("Malformed host verdict for $sampleKey", failure)
        }
        requireSchema(json, "host verdict")
        requireString(json, "runId", runId)
        requireString(json, "sampleKey", sampleKey)
        requireString(json, "attemptSha256", corpusAttemptSha256)
        requireString(json, "policySha256", policySha256)
        requireString(json, "checkpointNonce", checkpointNonce)
        val suppliedHash = requiredString(json, "checkpointSha256")
        check(HASH64.matches(suppliedHash)) { "Host verdict checkpointSha256 is not 64 hex characters" }
        check(suppliedHash == checkpointSha256) { "Host verdict checkpointSha256 does not match checkpoint bytes" }
        val passed = json.opt("passed")
        check(passed is Boolean && passed) { "Host verdict did not pass the sample" }
        return AcceptedVerdict(checkpointSha256)
    }

    private fun fileFor(sampleKey: String, kind: String): File {
        requireSafePath(sampleKey, "sampleKey")
        return File(barrierDirectory, "$kind-$runId-$sampleKey.json")
    }

    private fun writeImmutable(target: File, bytes: ByteArray) {
        check(!target.exists()) { "Checkpoint already exists: ${target.name}" }
        val temporary = File.createTempFile(".${target.name}-", ".tmp", barrierDirectory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            throw IllegalStateException("Could not atomically publish checkpoint: ${target.name}", failure)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun requireSchema(json: JSONObject, label: String) {
        val schema = json.opt("schema")
        check(schema is Int && schema == SCHEMA) {
            "$label schema must be $SCHEMA"
        }
    }

    private fun requireString(json: JSONObject, key: String, expected: String) {
        check(requiredString(json, key) == expected) { "$key does not match checkpoint" }
    }

    private fun requiredString(json: JSONObject, key: String): String {
        val value = json.opt(key)
        check(value is String) { "Host verdict field $key is missing or not a string" }
        return value
    }

    private companion object {
        const val SCHEMA = 1
        const val READY_STATUS = "READY_FOR_EXTERNAL_VERDICT"
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        const val POLL_INTERVAL_MILLIS = 100L
        const val LOG_TAG = "QualificationBarrier"
        val SAFE_PATH = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        val HASH64 = Regex("[0-9a-fA-F]{64}")

        fun requireSafePath(value: String, label: String) {
            require(SAFE_PATH.matches(value)) { "$label contains unsafe path characters" }
        }

        fun requireHash(value: String, label: String) {
            require(HASH64.matches(value)) { "$label must be 64 hexadecimal characters" }
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    data class AcceptedVerdict(val checkpointSha256: String)
}
