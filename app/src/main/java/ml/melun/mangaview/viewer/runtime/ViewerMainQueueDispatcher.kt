package ml.melun.mangaview.viewer.runtime

import android.os.Handler
import android.os.Looper
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Delivers a resumed continuation to the main thread ahead of everything already queued.
 *
 * A tile is recorded resident by the owner thread inside the continuation that finishes its
 * subscription, and that completion is signalled from a worker pool, so the owner always waited
 * for the main queue behind a frame that had already been posted. Measured on the GPU AVD, the
 * tiles that met the read-ahead budget and the ones that missed it were identical stage for stage
 * except for that wait: UPLOAD_DONE->RESIDENT was 0.8ms on the first group and 1.8ms on the
 * second, against a 6.4ms budget for the source whose baseline is tightest.
 *
 * What resumes here is the owner's own bookkeeping — a map insert, two timestamps and a
 * work-result refresh, tens of microseconds — so putting it first in the queue cannot delay the
 * frame that follows it, and everything that draws still travels the ordinary main queue. While
 * already on the main thread the block runs inline, matching the immediate dispatcher the session
 * scope would otherwise supply.
 */
internal object ViewerMainQueueDispatcher : CoroutineDispatcher() {
    private val handler = Handler(Looper.getMainLooper())

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        Looper.myLooper() !== Looper.getMainLooper()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (Looper.myLooper() === Looper.getMainLooper()) block.run()
        else check(handler.postAtFrontOfQueue(block)) { "Main queue rejected resumed owner work" }
    }
}
