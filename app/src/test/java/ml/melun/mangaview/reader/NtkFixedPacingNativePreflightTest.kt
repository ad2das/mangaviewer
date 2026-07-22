package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkFixedPacingNativePreflightTest {
    private fun passing() = LongArray(16).also {
        it[0] = 11L
        it[1] = 8L
        it[it.lastIndex] = 1L
    }

    @Test
    fun exactSchema11SurfaceControlEvidencePasses() {
        assertNull(NtkFixedPacingNativePreflight.validate(passing()))
    }

    @Test
    fun wrongSizeOrFailedAggregateIsRejected() {
        val wrongSize = LongArray(15)
        assertEquals(
            "native-schema11-preflight-size:15:16",
            NtkFixedPacingNativePreflight.validate(wrongSize)
        )
        val failed = passing().also { it[it.lastIndex] = 0L }
        assertEquals(
            "native-schema11-preflight-failed:${failed.joinToString(",")}",
            NtkFixedPacingNativePreflight.validate(failed)
        )
    }

    @Test
    fun gpuSceneDigestParityIncludesPreGeometryRoot() {
        val exact = NtkStripDigests.sha256Tokens(
            listOf(
                "ntk-gpu-scene-v1",
                "a".repeat(64),
                "b".repeat(64),
                "RGBA8_UNORM",
                "2",
                "0", "0", "1080", "1024", "0", "1024", "4423680",
                "1", "0", "1080", "512", "1024", "1536", "2211840"
            )
        )
        assertNull(NtkFixedPacingNativePreflight.validateGpuSceneDigest(exact))
        val missingPreGeometryRoot = NtkStripDigests.sha256Tokens(
            listOf(
                "ntk-gpu-scene-v1",
                "a".repeat(64),
                "RGBA8_UNORM",
                "2",
                "0", "0", "1080", "1024", "0", "1024", "4423680",
                "1", "0", "1080", "512", "1024", "1536", "2211840"
            )
        )
        assertEquals(
            "native-gpu-scene-digest-parity:$missingPreGeometryRoot:$exact",
            NtkFixedPacingNativePreflight.validateGpuSceneDigest(missingPreGeometryRoot)
        )
    }
}
