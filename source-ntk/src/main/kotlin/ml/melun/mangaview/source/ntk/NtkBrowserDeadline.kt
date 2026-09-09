package ml.melun.mangaview.source.ntk

import java.io.IOException
import kotlinx.coroutines.withTimeoutOrNull

/** A silent subresource failure must become a visible load failure, not an endless IPC wait. */
internal suspend fun <T : Any> awaitNtkBrowserResult(action: suspend () -> T): T =
    withTimeoutOrNull(25_000) { action() }
        ?: throw IOException("NTK 만화 정보를 받지 못했습니다. 네트워크 연결을 확인하고 다시 열어 주세요")
