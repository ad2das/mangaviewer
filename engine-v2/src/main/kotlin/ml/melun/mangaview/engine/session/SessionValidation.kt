package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.engine.api.SourceAnchor

internal fun validateManifest(
    geometry: DocumentGeometry,
    manifest: EpisodeManifest,
    navigationKnown: Boolean,
) {
    require(manifest.id.seriesId == geometry.targetEpisodeId.seriesId) {
        "Manifest belongs to another source or series"
    }
    val existing = geometry.manifests[manifest.id]
    if (existing != null) {
        require(existing == manifest) { "Conflicting manifest for ${manifest.id}" }
        require(geometry.isNavigationKnown(manifest.id) == navigationKnown) {
            "Conflicting navigation state for ${manifest.id}"
        }
        return
    }
    if (manifest.id != geometry.targetEpisodeId) {
        val adjacent = geometry.manifests.values.any { loaded ->
            loaded.nextEpisodeId == manifest.id || loaded.previousEpisodeId == manifest.id ||
                manifest.nextEpisodeId == loaded.id || manifest.previousEpisodeId == loaded.id
        }
        require(adjacent) { "Manifest is not adjacent to the current document" }
    }
    validateKnownManifestLinks(geometry, manifest, navigationKnown)
}

internal fun validateNavigationResolution(
    geometry: DocumentGeometry,
    episodeId: EpisodeId,
    previousEpisodeId: EpisodeId?,
    nextEpisodeId: EpisodeId?,
) {
    require(episodeId.seriesId == geometry.targetEpisodeId.seriesId) {
        "Navigation belongs to another source or series"
    }
    require(geometry.manifests.containsKey(episodeId)) {
        "Navigation arrived for an unknown episode: $episodeId"
    }
    listOf(previousEpisodeId, nextEpisodeId).filterNotNull().forEach { neighbor ->
        require(neighbor.seriesId == episodeId.seriesId) {
            "Navigation neighbor belongs to another source or series"
        }
        require(neighbor != episodeId) { "Navigation cannot point to the current episode" }
    }
    if (geometry.isNavigationKnown(episodeId)) {
        val manifest = requireNotNull(geometry.manifests[episodeId])
        require(manifest.previousEpisodeId == previousEpisodeId &&
            manifest.nextEpisodeId == nextEpisodeId) {
            "Conflicting navigation for $episodeId"
        }
        return
    }
    validateKnownNeighbors(geometry, episodeId, previousEpisodeId, nextEpisodeId)
}

internal fun validateCurrentAnchor(geometry: DocumentGeometry) {
    val value = geometry.anchor ?: return
    if (!geometry.manifests.containsKey(value.pageId.episodeId)) return
    require(geometry.page(value.pageId) != null) { "Anchor points to an unknown page" }
}

internal fun validateAnchor(geometry: DocumentGeometry, anchor: SourceAnchor) {
    require(anchor.pageId.episodeId == geometry.targetEpisodeId) {
        "Anchor belongs to another episode"
    }
}

internal fun validateLegacy(geometry: DocumentGeometry, position: ReadingPosition) {
    require(position.pageId.episodeId == geometry.targetEpisodeId) {
        "Legacy position belongs to another episode"
    }
}

private fun validateKnownManifestLinks(
    geometry: DocumentGeometry,
    manifest: EpisodeManifest,
    navigationKnown: Boolean,
) {
    geometry.manifests.values.forEach { loaded ->
        if (loaded.nextEpisodeId == manifest.id &&
            geometry.isNavigationKnown(loaded.id) && navigationKnown
        ) {
            require(manifest.previousEpisodeId == loaded.id) {
                "Manifest has a conflicting reverse adjacency"
            }
        }
        if (loaded.previousEpisodeId == manifest.id &&
            geometry.isNavigationKnown(loaded.id) && navigationKnown
        ) {
            require(manifest.nextEpisodeId == loaded.id) {
                "Manifest has a conflicting reverse adjacency"
            }
        }
        if (manifest.nextEpisodeId == loaded.id &&
            navigationKnown && geometry.isNavigationKnown(loaded.id)
        ) {
            require(loaded.previousEpisodeId == manifest.id) {
                "Manifest has a conflicting next adjacency"
            }
        }
        if (manifest.previousEpisodeId == loaded.id &&
            navigationKnown && geometry.isNavigationKnown(loaded.id)
        ) {
            require(loaded.nextEpisodeId == manifest.id) {
                "Manifest has a conflicting previous adjacency"
            }
        }
    }
}

private fun validateKnownNeighbors(
    geometry: DocumentGeometry,
    episodeId: EpisodeId,
    previousEpisodeId: EpisodeId?,
    nextEpisodeId: EpisodeId?,
) {
    geometry.manifests.values.forEach { loaded ->
        if (loaded.nextEpisodeId == episodeId && geometry.isNavigationKnown(loaded.id)) {
            require(previousEpisodeId == loaded.id) {
                "Navigation conflicts with a loaded neighbor"
            }
        }
        if (loaded.previousEpisodeId == episodeId && geometry.isNavigationKnown(loaded.id)) {
            require(nextEpisodeId == loaded.id) {
                "Navigation conflicts with a loaded neighbor"
            }
        }
    }
    previousEpisodeId?.let { previous ->
        val manifest = geometry.manifests[previous]
        if (manifest != null && geometry.isNavigationKnown(previous)) {
            require(manifest.nextEpisodeId == episodeId) {
                "Navigation conflicts with the loaded previous episode"
            }
        }
    }
    nextEpisodeId?.let { next ->
        val manifest = geometry.manifests[next]
        if (manifest != null && geometry.isNavigationKnown(next)) {
            require(manifest.previousEpisodeId == episodeId) {
                "Navigation conflicts with the loaded next episode"
            }
        }
    }
}
