package ml.melun.mangaview.viewer.runtime

import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.FramePlan
import ml.melun.mangaview.viewer.PixelRef
import ml.melun.mangaview.viewer.RenderPort

/** View-local HWUI scene: ordinary scroll frames change only Y geometry. */
internal class CanvasRenderPort(
    tiles: HardwareTileStore,
    private val recycle: (PixelRef) -> Unit,
    private val presented: (NativePresentationEvidence) -> Unit,
    private val fatal: (String) -> Unit,
    private val packer: NativeFramePacker = NativeFramePacker(),
) : RenderPort {
    private data class PendingFrame(
        val token: Long,
        val rendererIdentity: Long,
        val contract: NativeFullFrameContract,
        val packedScene: PackedNativeFrame?,
    )

    val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    private val sceneBuilder = HwuiTileSceneBuilder(tiles)
    private val ledger = NativeSubmissionLedger(2_048)
    private val commitIdentities = mutableMapOf<Long, Long>()
    private val retired = mutableListOf<PixelRef>()
    private val retirementFrames = mutableMapOf<Long, List<PixelRef>>()
    private var view: ViewerCanvasView? = null
    private var pending: PendingFrame? = null
    private var current: PendingFrame? = null
    private var scene: HwuiTileScene? = null
    private var lastPlan: FramePlan? = null
    private var nextToken = 1L
    private var nextStructureEpoch = 1L
    private var rendererIdentity = 1L
    private var lastCommittedToken = 0L
    private var attached = false
    private var closed = false

    fun bind(target: ViewerCanvasView) {
        check(view == null)
        view = target
    }

    override fun submit(framePlan: FramePlan) {
        if (closed) return
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            view?.post { submit(framePlan) }
            return
        }
        lastPlan = framePlan
        if (!attached) return
        val plan = preservePendingTimeline(framePlan)
        val prior = pending?.contract ?: current?.contract
        if (submitScalar(plan, prior)) return
        if (submitInstalled(plan, prior)) return
        submitNewScene(plan, prior)
    }

    private fun submitScalar(plan: FramePlan, prior: NativeFullFrameContract?): Boolean {
        prior ?: return false
        val scalar = prior.scrollSubmission(plan) ?: return false
        enqueue(
            plan,
            prior.advancedTo(plan),
            pending?.packedScene,
            scalar.readableActualContent,
            scalar.fullVisualCoverage,
            scalar.fullActualCoverage,
        )
        return true
    }

    private fun submitInstalled(plan: FramePlan, prior: NativeFullFrameContract?): Boolean {
        prior ?: return false
        val installed = prior?.let { contract ->
            packer.packInstalled(plan, contract)?.takeIf { packed ->
                contract.sceneSignature != 0L && packed.sceneSignature == contract.sceneSignature
            }
        } ?: return false
        if (prior.plan.scrollOffset != plan.scrollOffset) {
            enqueue(
                plan,
                prior.advancedTo(plan),
                pending?.packedScene,
                installed.hasReadableVisibleContent(),
                installed.hasCompleteVisualCoverage(),
                installed.hasCompleteVisibleContent(),
            )
        } else {
            advanceWithoutVisualChange(plan)
        }
        return true
    }

    private fun submitNewScene(plan: FramePlan, prior: NativeFullFrameContract?) {
        val packed = prior?.let { packer.packInstalled(plan, it) } ?: packer.pack(plan) ?: run {
            fatal("Viewer geometry exceeds the HWUI coordinate range")
            return
        }
        val contract = NativeFullFrameContract(
            structureEpoch = issueStructureEpoch(),
            plan = plan,
            renderWidth = packed.width,
            renderHeight = packed.height,
            bandHeight = packed.bandHeight,
            coordinateOrigin = packed.coordinateOrigin,
            localTileRanges = packed.localTileRanges,
            localVisualRanges = packed.localVisualRanges,
            sceneSignature = packed.sceneSignature,
        )
        enqueue(
            plan,
            contract,
            packed.frozenForRender(),
            packed.hasReadableVisibleContent(),
            packed.hasCompleteVisualCoverage(),
            packed.hasCompleteVisibleContent(),
        )
    }

    fun attach() {
        attached = true
        rendererIdentity = increment(rendererIdentity)
        if (!enqueueRetainedScene()) lastPlan?.let(::submit)
        // onStart may run while the task transition is still consuming an ordinary invalidate.
        // One next-VSYNC request guarantees a commit for this attachment without polling.
        view?.postInvalidateOnAnimation()
    }

    fun detach() {
        attached = false
        clearPending()
        // HwuiTileScene contains immutable geometry and bitmap identities only. It owns no
        // attachment-local RenderNode or texture, so it can safely seed the first HOME-return
        // frame while draw() resolves every current bitmap again.
    }

    fun redraw() {
        lastPlan?.let(::submit)
    }

    fun retire(pixel: PixelRef) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            view?.post { retire(pixel) }
            return
        }
        retired += pixel
    }

    fun draw(canvas: Canvas, width: Int, height: Int): Long? {
        if (!attached || closed || width <= 0 || height <= 0) return null
        if (!canvas.isHardwareAccelerated) {
            fatal("Viewer HWUI acceleration is unavailable")
            return null
        }
        val incoming = pending
        val frame = incoming ?: current ?: return null
        if (incoming != null) {
            pending = null
            if (!installSceneIfNeeded(frame)) {
                ledger.clear(frame.token)
                commitIdentities.remove(frame.token)
                return null
            }
        }
        if (!drawScene(canvas, frame.contract, width, height)) {
            if (incoming != null) {
                ledger.clear(frame.token)
                commitIdentities.remove(frame.token)
            }
            fatal("Viewer HWUI scene was unavailable for draw")
            return null
        }
        if (incoming != null) current = frame
        if (incoming == null) return null
        ledger.finish(frame.token)
        retainUntilCommit(frame.token)
        return frame.token
    }

    fun frameCommitted(token: Long) {
        if (closed) return
        val identity = commitIdentities.remove(token) ?: return
        if (!attached || identity != rendererIdentity) {
            ledger.clear(token)
            releaseRetirement(token)
            return
        }
        if (token > lastCommittedToken) {
            lastCommittedToken = token
            ledger.complete(token, identity, System.nanoTime())?.let(presented)
        } else {
            ledger.clear(token)
        }
        releaseRetirement(token)
    }

    fun close(afterClose: () -> Unit = {}) {
        if (closed) return
        closed = true
        pending = null
        current = null
        scene = null
        recycleRetired()
        retirementFrames.values.flatten().forEach { pixel ->
            recycle(pixel)
        }
        retirementFrames.clear()
        commitIdentities.clear()
        afterClose()
    }

    private fun enqueue(
        plan: FramePlan,
        contract: NativeFullFrameContract,
        packedScene: PackedNativeFrame?,
        readable: Boolean,
        fullVisual: Boolean,
        fullActual: Boolean,
    ) {
        clearPending()
        val token = issueToken()
        ledger.record(token, plan, readable, fullVisual, fullActual)
        pending = PendingFrame(token, rendererIdentity, contract, packedScene)
        commitIdentities[token] = rendererIdentity
        // User motion is reduced from a Choreographer callback on this same thread. Posting
        // another animation callback would defer HWUI traversal by a full VSYNC and let the
        // following immutable state replace this frame before it can be drawn. invalidate()
        // coalesces normally while still joining the current traversal when it has not run yet.
        view?.invalidate()
    }

    private fun enqueueRetainedScene(): Boolean {
        val retained = current ?: return false
        val installedScene = scene ?: return false
        val plan = lastPlan ?: return false
        val installed = packer.packInstalled(plan, retained.contract) ?: return false
        if (retained.contract.sceneSignature == 0L ||
            installed.sceneSignature != retained.contract.sceneSignature ||
            installedScene.signature != retained.contract.sceneSignature
        ) return false
        enqueue(
            plan,
            retained.contract.advancedTo(plan),
            packedScene = null,
            readable = installed.hasReadableVisibleContent(),
            fullVisual = installed.hasCompleteVisualCoverage(),
            fullActual = installed.hasCompleteVisibleContent(),
        )
        return true
    }

    private fun preservePendingTimeline(plan: FramePlan): FramePlan {
        if (plan.expectedPresentationTimeNanos > 0L) return plan
        val pendingPlan = pending?.contract?.plan ?: return plan
        if (pendingPlan.expectedPresentationTimeNanos <= 0L ||
            pendingPlan.scrollOffset != plan.scrollOffset
        ) return plan
        return plan.copy(
            frameTimelineVsyncId = pendingPlan.frameTimelineVsyncId,
            expectedPresentationTimeNanos = pendingPlan.expectedPresentationTimeNanos,
        )
    }

    private fun clearPending() {
        pending?.let {
            ledger.clear(it.token)
            commitIdentities.remove(it.token)
        }
        pending = null
    }

    private fun advanceWithoutVisualChange(plan: FramePlan) {
        pending = pending?.let { frame ->
            frame.copy(contract = frame.contract.advancedTo(plan))
        }
        if (pending == null) {
            current = current?.let { frame ->
                frame.copy(contract = frame.contract.advancedTo(plan))
            }
        }
    }

    private fun installSceneIfNeeded(frame: PendingFrame): Boolean {
        val packed = frame.packedScene ?: return scene != null
        val built = sceneBuilder.build(packed) ?: run {
            fatal("Published viewer tile was unavailable to HWUI")
            return false
        }
        scene = built
        return true
    }

    private fun drawScene(
        canvas: Canvas,
        contract: NativeFullFrameContract,
        width: Int,
        height: Int,
    ): Boolean {
        val installed = scene ?: return false
        val scaleX = width.toFloat() / contract.renderWidth.toFloat()
        val scaleY = height.toFloat() / contract.renderHeight.toFloat()
        val localUnits = contract.plan.scrollOffset.units - contract.coordinateOrigin.units
        val packedScale = contract.renderWidth / contract.plan.viewport.width.toPixels()
        val viewportTop = localUnits.toDouble() / FixedPx.UNITS_PER_PIXEL * packedScale
        canvas.drawColor(Color.BLACK)
        val checkpoint = canvas.save()
        canvas.scale(scaleX, scaleY)
        canvas.translate(0f, -viewportTop.toFloat())
        val drawn = installed.draw(canvas)
        canvas.restoreToCount(checkpoint)
        return drawn
    }

    private fun retainUntilCommit(token: Long) {
        if (retired.isEmpty()) return
        retirementFrames[token] = retired.toList()
        retired.clear()
    }

    private fun releaseRetirement(token: Long) {
        retirementFrames.remove(token)?.forEach { pixel -> recycle(pixel) }
    }

    private fun recycleRetired() {
        retired.forEach { pixel ->
            recycle(pixel)
        }
        retired.clear()
    }

    private fun issueToken(): Long = nextToken.also { nextToken = increment(it) }

    private fun issueStructureEpoch(): Long = nextStructureEpoch.also {
        nextStructureEpoch = increment(it)
    }

    private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L

}
