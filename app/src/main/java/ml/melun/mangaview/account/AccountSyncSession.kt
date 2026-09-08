package ml.melun.mangaview.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AccountSyncSession(
    private val uid: String,
    private val local: CloudLocalPort,
    private val checkpoint: AccountCheckpointPort,
    private val remote: CloudRemotePort,
    private val wake: Channel<Unit>,
    private val isCurrent: () -> Boolean,
    private val status: (String, Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val journalLock = Mutex()
    private var saved: AccountCheckpoint? = null

    suspend fun run() = coroutineScope {
        if (!loadOwner()) return@coroutineScope
        val unsubscribe = remote.observe { wake.trySend(Unit) }
        val watch = launch {
            local.changes.collect {
                try { captureChanges() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status("자동 저장 준비 중 오류가 발생했습니다. 다시 시도합니다", false) }
                wake.trySend(Unit)
            }
        }
        wake.trySend(Unit)
        try { exchangeLoop() } finally { watch.cancel(); unsubscribe() }
    }

    private fun loadOwner(): Boolean {
        try { saved = checkpoint.read() } catch (_: Exception) {
            status("저장된 동기화 기록을 읽지 못했습니다. 로컬 기록은 보존됩니다", false)
            return false
        }
        if (saved != null && saved!!.owner != uid) {
            status("기존 기록을 보호하려면 이전에 연결한 Google 계정으로 로그인해 주세요", false)
            return false
        }
        return true
    }

    private suspend fun captureChanges(): Pair<List<CloudLibraryRecord>, List<CloudLibraryRecord>> = journalLock.withLock {
        check(isCurrent())
        val before = local.snapshot()
        val pending = CloudLibraryRecords.localChanges(saved?.records.orEmpty(), before, now())
        save(pending)
        before to pending
    }

    private suspend fun exchangeLoop() {
        var retryDelay = 2_000L
        while (true) {
            if (remote.unresolvedRecords > 0) withTimeoutOrNull(60_000L) { wake.receive() }
            else wake.receive()
            delay(1_200L)
            while (wake.tryReceive().isSuccess) { /* use the latest database state */ }
            try {
                synchronize()
                retryDelay = 2_000L
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                status("연결을 기다리는 중 · 기록은 기기에 저장됩니다", false)
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
                wake.trySend(Unit)
            }
        }
    }

    private suspend fun synchronize() {
        currentCoroutineContext().ensureActive()
        val (before, pending) = captureChanges()
        status("기록 동기화 중", true)
        val downloaded = remote.exchange(pending)
        currentCoroutineContext().ensureActive()
        check(isCurrent())
        val merged = journalLock.withLock {
            val result = local.restore(CloudLibraryRecords.merge(downloaded, saved?.records.orEmpty()), before, now())
            save(result)
            result
        }
        status(if (remote.unresolvedRecords == 0) "자동 저장됨" else
            "자동 저장됨 · 이전 기록 ${remote.unresolvedRecords}개의 회차 확인 대기 중", false)
        if (merged != downloaded) wake.trySend(Unit)
    }

    private fun save(records: List<CloudLibraryRecord>) {
        val value = AccountCheckpoint(uid, records)
        checkpoint.write(value)
        saved = value
    }
}
