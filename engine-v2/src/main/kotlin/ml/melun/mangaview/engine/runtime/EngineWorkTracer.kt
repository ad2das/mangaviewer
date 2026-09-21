package ml.melun.mangaview.engine.runtime

/**
 * Optional owner-thread timing sections around the engine's scene-refresh steps. The default
 * implementation performs no name formatting and no recording, so production frames pay nothing
 * unless the host installs a tracer.
 */
interface EngineWorkTracer {
    fun <T> section(name: String, work: () -> T): T
}

/** Inert tracer used when the host does not ask for section timing. */
object NoopEngineWorkTracer : EngineWorkTracer {
    override fun <T> section(name: String, work: () -> T): T = work()
}
