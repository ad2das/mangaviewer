package ml.melun.mangaview.reader

/**
 * Immutable plan identity that permits strict preparation. It deliberately contains no surface
 * identity: authoritative source work is promoted by the response-bound manifest, not by a
 * window.
 */
internal data class NtkStrictPlanIdentity(
    val normalizedEpisodePath: String,
    val controllerGeneration: Int,
    val discoveryGeneration: Long,
    val documentPlanDigest: String,
    val imageRequestIdentityDigest: String,
    val responseBoundProofDigest: String,
    val pageCount: Int
) {
    init {
        require(
            NtkStripDigests.normalizeEpisodePath(normalizedEpisodePath) ==
                normalizedEpisodePath
        )
        require(controllerGeneration > 0)
        require(discoveryGeneration > 0L)
        require(NtkStripDigests.isSha256(documentPlanDigest))
        require(NtkStripDigests.isSha256(imageRequestIdentityDigest))
        require(NtkStripDigests.isSha256(responseBoundProofDigest))
        require(pageCount in 1..1_000)
    }

    val identityDigest: String
        get() = NtkStripDigests.sha256Tokens(
            "ntk-strict-plan-identity-v1",
            normalizedEpisodePath,
            controllerGeneration.toString(),
            discoveryGeneration.toString(),
            documentPlanDigest,
            imageRequestIdentityDigest,
            responseBoundProofDigest,
            pageCount.toString()
        )

    companion object {
        fun from(
            controllerGeneration: Int,
            plan: NtkProvisionalEpisodePlan
        ): NtkStrictPlanIdentity = NtkStrictPlanIdentity(
            normalizedEpisodePath = plan.proof.normalizedEpisodePath,
            controllerGeneration = controllerGeneration,
            discoveryGeneration = plan.proof.discoveryGeneration,
            documentPlanDigest = plan.proof.proofDigestSha256,
            imageRequestIdentityDigest = plan.proof.requestIdentity.identityDigestSha256,
            responseBoundProofDigest = plan.proof.responseIdentityDigestSha256,
            pageCount = plan.pageCount
        )
    }
}

internal data class NtkStrictDemandIdentity(
    val demandGeneration: Long,
    val controllerGeneration: Int,
    val documentPlanDigest: String
) {
    init {
        require(demandGeneration > 0L)
        require(controllerGeneration > 0)
        require(NtkStripDigests.isSha256(documentPlanDigest))
    }

    companion object {
        fun from(demand: NtkSurfaceDemandProtocol.Demand): NtkStrictDemandIdentity =
            NtkStrictDemandIdentity(
                demandGeneration = demand.generation,
                controllerGeneration = demand.planGeneration,
                documentPlanDigest = demand.planProofDigest
            )
    }
}

internal data class NtkStrictManifestIdentity(
    val plan: NtkStrictPlanIdentity,
    val manifestRevision: Long,
    val manifestDigest: String,
    val responseBoundProofDigest: String,
    val orderedCanonicalAssets: List<String>
) {
    val normalizedOrderedCanonicalAssets =
        orderedCanonicalAssets.map(NtkStripDigests::canonicalAsset)

    init {
        require(manifestRevision >= 0L)
        require(responseBoundProofDigest == plan.responseBoundProofDigest)
        require(normalizedOrderedCanonicalAssets.size == plan.pageCount)
        require(normalizedOrderedCanonicalAssets.none(String::isBlank))
        require(
            NtkEpisodeManifestSeal.computeDigestSha256(
                plan.normalizedEpisodePath,
                plan.pageCount,
                normalizedOrderedCanonicalAssets
            ) == manifestDigest
        )
    }

    val identityDigest: String
        get() = NtkStripDigests.sha256Tokens(buildList {
            add("ntk-strict-manifest-identity-v1")
            add(plan.identityDigest)
            add(manifestRevision.toString())
            add(manifestDigest)
            add(responseBoundProofDigest)
            addAll(normalizedOrderedCanonicalAssets)
        })

    companion object {
        fun from(
            plan: NtkStrictPlanIdentity,
            seal: NtkEpisodeManifestSeal
        ): NtkStrictManifestIdentity {
            require(seal.normalizedEpisodePath == plan.normalizedEpisodePath)
            require(seal.pageCount == plan.pageCount)
            return NtkStrictManifestIdentity(
                plan = plan,
                manifestRevision = seal.revision,
                manifestDigest = seal.digestSha256,
                responseBoundProofDigest = plan.responseBoundProofDigest,
                orderedCanonicalAssets = seal.normalizedCanonicalAssets
            )
        }
    }
}

internal data class NtkStrictDetachedEngineIdentity(
    val engineGeneration: Long,
    val demand: NtkStrictDemandIdentity
) {
    init {
        require(engineGeneration > 0L)
    }
}

internal data class NtkStrictDetachedPreparationIdentity(
    val engineGeneration: Long,
    val preparationGeneration: Long,
    val manifest: NtkStrictManifestIdentity,
    val tokenNonce: Long
) {
    init {
        require(engineGeneration > 0L)
        require(preparationGeneration > 0L)
        require(tokenNonce > 0L)
    }

    val identityDigest: String
        get() = NtkStripDigests.sha256Tokens(
            "ntk-strict-detached-preparation-identity-v1",
            engineGeneration.toString(),
            preparationGeneration.toString(),
            manifest.identityDigest,
            tokenNonce.toString()
        )
}

internal data class NtkStrictPreparedInventoryIdentity(
    val preparation: NtkStrictDetachedPreparationIdentity,
    val preparedInventoryDigest: String,
    val preparedTileCount: Int
) {
    init {
        require(NtkStripDigests.isSha256(preparedInventoryDigest))
        require(preparedTileCount in 0..1_000_000)
    }
}

internal data class NtkStrictGeometrySeedIdentity(
    val preparationGeneration: Long,
    val geometryRevision: Long,
    val viewportWidth: Int,
    val geometryDigest: String,
    val geometryTileCount: Int
) {
    init {
        require(preparationGeneration > 0L)
        require(geometryRevision > 0L)
        require(viewportWidth > 0)
        require(NtkStripDigests.isSha256(geometryDigest))
        require(geometryTileCount > 0)
    }
}

internal data class NtkStrictPublishedSurfaceIdentity(
    val engineGeneration: Long,
    val demand: NtkStrictDemandIdentity,
    val attachGeneration: Long,
    val surfaceEpoch: Long,
    val geometryRevision: Long,
    val width: Int,
    val height: Int
) {
    init {
        require(engineGeneration > 0L)
        require(attachGeneration > 0L)
        require(surfaceEpoch > 0L)
        require(geometryRevision > 0L)
        require(width > 0 && height > 0)
    }

    companion object {
        fun from(
            demand: NtkStrictDemandIdentity,
            surface: NtkPublishedSurfaceIdentity
        ): NtkStrictPublishedSurfaceIdentity = NtkStrictPublishedSurfaceIdentity(
            engineGeneration = surface.engineGeneration,
            demand = demand,
            attachGeneration = surface.attachGeneration,
            surfaceEpoch = surface.surfaceEpoch,
            geometryRevision = surface.geometryRevision,
            width = surface.width,
            height = surface.height
        )
    }
}

internal data class NtkStrictSurfacePreparationJoinIdentity(
    val preparation: NtkStrictDetachedPreparationIdentity,
    val inventoryDigest: String,
    val preparedTileCount: Int,
    val surface: NtkStrictPublishedSurfaceIdentity,
    val geometry: NtkStrictGeometrySeedIdentity
) {
    init {
        require(NtkStripDigests.isSha256(inventoryDigest))
        require(preparedTileCount in 0..geometry.geometryTileCount)
        require(preparation.engineGeneration == surface.engineGeneration)
        require(preparation.preparationGeneration == geometry.preparationGeneration)
        require(surface.geometryRevision == geometry.geometryRevision)
        require(surface.width == geometry.viewportWidth)
    }

    val identityDigest: String
        get() = NtkStripDigests.sha256Tokens(
            "ntk-strict-surface-preparation-join-v1",
            preparation.identityDigest,
            inventoryDigest,
            preparedTileCount.toString(),
            surface.engineGeneration.toString(),
            surface.demand.demandGeneration.toString(),
            surface.attachGeneration.toString(),
            surface.surfaceEpoch.toString(),
            surface.geometryRevision.toString(),
            surface.width.toString(),
            surface.height.toString(),
            geometry.geometryDigest,
            geometry.geometryTileCount.toString()
        )
}

/**
 * Surface-independent strict preparation reducer.
 *
 * Exact manifest promotion starts source/decode preparation. Detached engine readiness only opens
 * the native upload destination. A positive published surface participates solely in the final,
 * exact-once adoption join.
 */
internal class NtkStrictPreparationProtocol(
    initialPreparationGeneration: Long = 0L
) {
    data class Transition(
        val preparationGeneration: Long = 0L,
        val startPreparation: Boolean = false,
        val openDetachedPreparation: Boolean = false,
        val adoptDetachedPreparationToSurface: Boolean = false,
        val joinIdentity: NtkStrictSurfacePreparationJoinIdentity? = null,
        val failed: Boolean = false,
        val failureReason: String = ""
    )

    data class Snapshot(
        val plan: NtkStrictPlanIdentity?,
        val demand: NtkStrictDemandIdentity?,
        val manifest: NtkStrictManifestIdentity?,
        val detachedEngine: NtkStrictDetachedEngineIdentity?,
        val preparationGeneration: Long,
        val preparationStarted: Boolean,
        val detachedOpenDispatched: Boolean,
        val detachedPreparation: NtkStrictDetachedPreparationIdentity?,
        val geometry: NtkStrictGeometrySeedIdentity?,
        val surface: NtkStrictPublishedSurfaceIdentity?,
        val inventory: NtkStrictPreparedInventoryIdentity?,
        val adoptionDispatched: Boolean,
        val joined: NtkStrictSurfacePreparationJoinIdentity?,
        val failed: Boolean,
        val failureReason: String
    )

    private var nextPreparationGeneration = initialPreparationGeneration
    private var plan: NtkStrictPlanIdentity? = null
    private var demand: NtkStrictDemandIdentity? = null
    private var manifest: NtkStrictManifestIdentity? = null
    private var detachedEngine: NtkStrictDetachedEngineIdentity? = null
    private var preparationGeneration = 0L
    private var preparationStarted = false
    private var detachedOpenDispatched = false
    private var detachedPreparation: NtkStrictDetachedPreparationIdentity? = null
    private var geometry: NtkStrictGeometrySeedIdentity? = null
    private var surface: NtkStrictPublishedSurfaceIdentity? = null
    private var inventory: NtkStrictPreparedInventoryIdentity? = null
    private var adoptionDispatched = false
    private var joined: NtkStrictSurfacePreparationJoinIdentity? = null
    private var failed = false
    private var failureReason = ""

    init {
        require(initialPreparationGeneration >= 0L)
    }

    fun onPlanReserved(
        incomingPlan: NtkStrictPlanIdentity,
        incomingDemand: NtkStrictDemandIdentity
    ): Transition {
        if (failed) return failedTransition()
        val currentPlan = plan
        val currentDemand = demand
        if (currentPlan != null || currentDemand != null) {
            return if (currentPlan == incomingPlan && currentDemand == incomingDemand) {
                converge()
            } else {
                fail("plan-or-demand-generation-mismatch")
            }
        }
        if (!planMatchesDemand(incomingPlan, incomingDemand)) {
            return fail("plan-does-not-match-demand")
        }
        plan = incomingPlan
        demand = incomingDemand
        if (!allKnownIdentitiesMatch()) return fail("plan-conflicts-with-arrived-identity")
        return converge()
    }

    fun onManifestPromoted(incoming: NtkStrictManifestIdentity): Transition {
        if (failed) return failedTransition()
        val current = manifest
        if (current != null) {
            return if (current == incoming) converge() else fail("manifest-generation-mismatch")
        }
        manifest = incoming
        if (!allKnownIdentitiesMatch()) return fail("manifest-conflicts-with-current-plan")
        return converge()
    }

    fun onDetachedEngineReady(incoming: NtkStrictDetachedEngineIdentity): Transition {
        if (failed) return failedTransition()
        val current = detachedEngine
        if (current != null) {
            return if (current == incoming) converge() else fail("engine-generation-mismatch")
        }
        detachedEngine = incoming
        if (!allKnownIdentitiesMatch()) return fail("engine-conflicts-with-current-demand")
        return converge()
    }

    fun onDetachedPreparationOpened(
        incoming: NtkStrictDetachedPreparationIdentity
    ): Transition {
        if (failed) return failedTransition()
        if (!detachedOpenDispatched) return fail("detached-open-was-not-dispatched")
        val current = detachedPreparation
        if (current != null) {
            return if (current == incoming) converge() else fail("detached-token-mismatch")
        }
        detachedPreparation = incoming
        if (!allKnownIdentitiesMatch()) return fail("detached-token-conflicts-with-generation")
        return converge()
    }

    fun onGeometrySeedAvailable(incoming: NtkStrictGeometrySeedIdentity): Transition {
        if (failed) return failedTransition()
        val current = geometry
        if (current != null) {
            return if (current == incoming) converge() else fail("geometry-generation-mismatch")
        }
        geometry = incoming
        if (!allKnownIdentitiesMatch()) return fail("geometry-conflicts-with-preparation")
        return converge()
    }

    fun onSurfacePublished(incoming: NtkStrictPublishedSurfaceIdentity): Transition {
        if (failed) return failedTransition()
        val current = surface
        if (current != null) {
            return if (current == incoming) converge() else fail("surface-generation-mismatch")
        }
        surface = incoming
        if (!allKnownIdentitiesMatch()) return fail("surface-conflicts-with-engine-or-demand")
        return converge()
    }

    fun onPreparedInventory(incoming: NtkStrictPreparedInventoryIdentity): Transition {
        if (failed) return failedTransition()
        val current = inventory
        if (current != null) {
            return if (current == incoming) converge() else fail("prepared-inventory-mismatch")
        }
        inventory = incoming
        if (!allKnownIdentitiesMatch()) return fail("inventory-conflicts-with-preparation")
        return converge()
    }

    fun onSurfacePreparationBound(
        incoming: NtkStrictSurfacePreparationJoinIdentity
    ): Transition {
        if (failed) return failedTransition()
        if (!adoptionDispatched) return fail("surface-adoption-was-not-dispatched")
        val expected = exactJoinIdentity() ?: return fail("surface-adoption-lacks-exact-join")
        if (incoming != expected) return fail("surface-adoption-proof-mismatch")
        val current = joined
        if (current != null) {
            return if (current == incoming) Transition(
                preparationGeneration = preparationGeneration
            ) else fail("duplicate-surface-adoption-mismatch")
        }
        joined = incoming
        return Transition(preparationGeneration = preparationGeneration)
    }

    fun failClosed(reason: String): Transition = fail(reason.ifBlank { "external-failure" })

    fun snapshot(): Snapshot = Snapshot(
        plan = plan,
        demand = demand,
        manifest = manifest,
        detachedEngine = detachedEngine,
        preparationGeneration = preparationGeneration,
        preparationStarted = preparationStarted,
        detachedOpenDispatched = detachedOpenDispatched,
        detachedPreparation = detachedPreparation,
        geometry = geometry,
        surface = surface,
        inventory = inventory,
        adoptionDispatched = adoptionDispatched,
        joined = joined,
        failed = failed,
        failureReason = failureReason
    )

    private fun converge(): Transition {
        if (failed) return failedTransition()
        var start = false
        var open = false
        var adopt = false
        if (!preparationStarted && plan != null && demand != null && manifest != null) {
            nextPreparationGeneration++
            preparationGeneration = nextPreparationGeneration
            preparationStarted = true
            start = true
        }
        if (preparationStarted && !detachedOpenDispatched && detachedEngine != null) {
            detachedOpenDispatched = true
            open = true
        }
        val exactJoin = exactJoinIdentity()
        if (exactJoin != null && !adoptionDispatched && joined == null) {
            adoptionDispatched = true
            adopt = true
        }
        return Transition(
            preparationGeneration = preparationGeneration,
            startPreparation = start,
            openDetachedPreparation = open,
            adoptDetachedPreparationToSurface = adopt,
            joinIdentity = if (adopt) exactJoin else null
        )
    }

    private fun exactJoinIdentity(): NtkStrictSurfacePreparationJoinIdentity? {
        val currentDemand = demand ?: return null
        val currentPreparation = detachedPreparation ?: return null
        val currentInventory = inventory ?: return null
        val currentSurface = surface ?: return null
        val currentGeometry = geometry ?: return null
        if (currentSurface.demand != currentDemand ||
            currentInventory.preparation != currentPreparation ||
            currentInventory.preparedTileCount > currentGeometry.geometryTileCount
        ) return null
        return runCatching {
            NtkStrictSurfacePreparationJoinIdentity(
                preparation = currentPreparation,
                inventoryDigest = currentInventory.preparedInventoryDigest,
                preparedTileCount = currentInventory.preparedTileCount,
                surface = currentSurface,
                geometry = currentGeometry
            )
        }.getOrNull()
    }

    private fun allKnownIdentitiesMatch(): Boolean {
        val p = plan
        val d = demand
        val m = manifest
        val e = detachedEngine
        val opened = detachedPreparation
        val g = geometry
        val s = surface
        val r = inventory
        if (p != null && d != null && !planMatchesDemand(p, d)) return false
        if (p != null && m != null && m.plan != p) return false
        if (d != null && e != null && e.demand != d) return false
        if (d != null && s != null && s.demand != d) return false
        if (e != null && s != null && e.engineGeneration != s.engineGeneration) return false
        if (preparationStarted && g != null &&
            g.preparationGeneration != preparationGeneration
        ) return false
        if (e != null && opened != null &&
            opened.engineGeneration != e.engineGeneration
        ) return false
        if (preparationStarted && opened != null &&
            opened.preparationGeneration != preparationGeneration
        ) return false
        if (m != null && opened != null && opened.manifest != m) return false
        if (opened != null && r != null && r.preparation != opened) return false
        if (s != null && g != null &&
            (s.geometryRevision != g.geometryRevision || s.width != g.viewportWidth)
        ) return false
        return true
    }

    private fun planMatchesDemand(
        plan: NtkStrictPlanIdentity,
        demand: NtkStrictDemandIdentity
    ): Boolean = demand.controllerGeneration == plan.controllerGeneration &&
        demand.documentPlanDigest == plan.documentPlanDigest

    private fun fail(reason: String): Transition {
        failed = true
        failureReason = reason
        return failedTransition()
    }

    private fun failedTransition(): Transition = Transition(
        preparationGeneration = preparationGeneration,
        failed = true,
        failureReason = failureReason
    )
}
