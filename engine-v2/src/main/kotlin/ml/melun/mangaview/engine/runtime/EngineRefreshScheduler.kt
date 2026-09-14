package ml.melun.mangaview.engine.runtime

/**
 * Owner-thread, engine-pure delivery port for a coalesced refresh drain. [post] requests exactly
 * one later [EngineRenderRuntime.refreshOnFrame] call on the owner thread; repeated posts while a
 * delivery is pending are ignored. [cancel] is idempotent and a null scheduler keeps the legacy
 * immediate drain.
 */
interface EngineRefreshScheduler {
    fun post()
    fun cancel()
}
