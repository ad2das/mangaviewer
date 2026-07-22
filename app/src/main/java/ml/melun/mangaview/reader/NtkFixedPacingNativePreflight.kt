package ml.melun.mangaview.reader

/** Fail-fast bridge for the production asynchronous NTK11 presentation contract. */
object NtkFixedPacingNativePreflight {
    private const val EXPECTED_SIZE = 16

    @JvmStatic
    fun violation(): String? = try {
        validate(NtkStripNativeBridge.nativeRunSurfaceControlSchema11SelfTest())
            ?: validateGpuSceneDigest(
                NtkStripNativeBridge.nativeGpuSceneDigestVectorForTesting()
            )
    } catch (failure: Throwable) {
        "native-fixed-preflight-exception:${failure.javaClass.name}:${failure.message}"
    }

    internal fun validate(evidence: LongArray): String? {
        if (evidence.size != EXPECTED_SIZE) {
            return "native-schema11-preflight-size:${evidence.size}:$EXPECTED_SIZE"
        }
        if (evidence[0] != 11L) return "native-schema11-preflight-schema:${evidence[0]}"
        if (evidence[1] != 8L) return "native-schema11-preflight-pool:${evidence[1]}"
        if (evidence.last() != 1L) {
            return "native-schema11-preflight-failed:${evidence.joinToString(",")}"
        }
        return null
    }

    internal fun validateGpuSceneDigest(actual: String): String? {
        val expected = NtkStripDigests.sha256Tokens(
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
        return if (actual == expected) null else
            "native-gpu-scene-digest-parity:$actual:$expected"
    }
}
