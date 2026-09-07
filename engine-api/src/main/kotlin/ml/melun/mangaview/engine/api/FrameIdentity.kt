package ml.melun.mangaview.engine.api

/** Surface attachment identity changes even when a replacement has the same dimensions. */
data class FrameIdentity(
    val sessionId: Long,
    val rendererEpoch: Long,
    val surfaceEpoch: Long,
    val token: Long,
    val inputRevision: Long,
    val geometryRevision: Long,
) {
    init {
        require(sessionId > 0 && rendererEpoch > 0 && surfaceEpoch > 0 && token > 0)
        require(inputRevision >= 0 && geometryRevision >= 0)
    }
}

data class BufferIdentity(
    val frame: FrameIdentity,
    val eglFrameId: Long?,
    val producerFrameNumber: Long?,
    val layerId: Long?,
) {
    init {
        require(eglFrameId == null || eglFrameId > 0)
        require(producerFrameNumber == null || producerFrameNumber > 0)
        require(layerId == null || layerId >= 0)
    }
}
