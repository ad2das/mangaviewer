package ml.melun.mangaview.app

import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.ContentSource

internal data class SourceOption(
    val id: SourceId,
    val label: String,
)

internal data class SourceRegistration(
    val id: SourceId,
    val label: String,
    val create: () -> ContentSource,
)

internal class SourceRegistry(
    registrations: List<SourceRegistration>,
) {
    private val lock = Any()
    private val registrationsById = registrations.associateBy(SourceRegistration::id)
    private val instances = mutableMapOf<SourceId, ContentSource>()
    val options = registrations.map { SourceOption(it.id, it.label) }

    init {
        require(registrations.isNotEmpty()) { "At least one content source is required" }
        require(registrationsById.size == registrations.size) { "Content source ids must be unique" }
    }

    fun require(sourceId: SourceId): ContentSource = synchronized(lock) {
        instances[sourceId]?.let { return@synchronized it }
        val registration = requireNotNull(registrationsById[sourceId]) {
            "Unknown content source: ${sourceId.value}"
        }
        registration.create().also { source ->
            require(source.id == sourceId) {
                "Source factory returned ${source.id.value} for ${sourceId.value}"
            }
            instances[sourceId] = source
        }
    }
}
