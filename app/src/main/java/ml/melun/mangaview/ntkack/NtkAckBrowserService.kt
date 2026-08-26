package ml.melun.mangaview.ntkack

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.util.UUID

class NtkAckBrowserService : Service() {
    private lateinit var engine: NtkAckBrowserEngine
    private val mainHandler = Handler(Looper.getMainLooper())
    // Both protocols already domain-separate their signed bytes (the proof envelope starts with
    // PROOF_DOMAIN; browser request signatures start with "ntk-brsig-v1"). A second cold P-256
    // key generation provided no isolation because both private keys lived in this same one-shot
    // service process, but delayed the first WebView construction on emulator and low-end CPUs.
    private val proofKey = NtkAckRequestKeyStore.generateKeyPair()
    private val requestKeyStore = NtkAckRequestKeyStore(proofKey)
    private val serviceInstanceId = UUID.randomUUID().toString()

    override fun onCreate() {
        super.onCreate()
        // This process also proves adjacent episodes while the foreground reader is receiving
        // physical input.  Giving its WebView control looper display priority lets an offscreen
        // shell preempt that input.  Protocol deadlines provide liveness; background scheduling
        // preserves the actual foreground display contract.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        engine = NtkAckBrowserEngine(applicationContext, serviceInstanceId, proofKey, requestKeyStore)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (::engine.isInitialized) mainHandler.post { engine.destroyImmediately() }
        super.onDestroy()
    }

    private val binder = object : INtkAckBrowserService.Stub() {
        override fun warm(request: NtkAckWarmRequest?, callback: INtkAckBrowserCallback?) {
            if (!authorize(request?.protocolVersion, callback, request?.clientPid ?: 0, "warm")) return
            val target = callback!!
            mainHandler.post {
                // This RPC establishes and authenticates the isolated service, not a content
                // warm-up. A trusted server grant is entirely native and must not wait for a
                // Chromium renderer that it will never use. The engine creates its WebView on
                // demand only if the server returns the full JavaScript/WASM challenge branch.
                safeCallback(target) { it.onWarmReady(buildHello()) }
            }
        }

        override fun startAck(request: NtkAckRequest?, callback: INtkAckBrowserCallback?) {
            if (!authorize(request?.protocolVersion, callback, request?.clientPid ?: 0, "start_ack")) return
            val required = request!!
            val target = callback!!
            val identity = required.identity()
            if (!linkOwnerDeath(target, identity)) return
            mainHandler.post {
                engine.startAck(
                    required,
                    onNetworkPrerequisitesReady = {
                        safeCallback(target) { it.onNetworkPrerequisitesReady(identity) }
                    },
                ) { result ->
                    result.fold(
                        onSuccess = { proof -> safeCallback(target) { it.onAckProved(proof) } },
                        onFailure = { engineFailure(target, "start_ack", it, identity) },
                    )
                }
            }
        }

        override fun quiesce(identity: NtkAckFlightIdentity?, callback: INtkAckBrowserCallback?) {
            if (!authorize(identity?.protocolVersion, callback, 0, "quiesce")) return
            val required = identity!!
            val target = callback!!
            mainHandler.post {
                engine.quiesce(required) { result ->
                    result.fold(
                        onSuccess = { seal -> safeCallback(target) { it.onQuiesced(seal) } },
                        onFailure = { engineFailure(target, "quiesce", it, required) },
                    )
                }
            }
        }

        override fun signExactRequest(request: NtkAckSignRequest?, callback: INtkAckBrowserCallback?) {
            if (!authorize(request?.protocolVersion, callback, 0, "sign_exact")) return
            val required = request!!
            val target = callback!!
            mainHandler.post {
                runCatching { engine.signExact(required) }.fold(
                    onSuccess = { signature -> safeCallback(target) { it.onExactRequestSigned(signature) } },
                    onFailure = {
                        engineFailure(
                            target,
                            "sign_exact",
                            it,
                            NtkAckFlightIdentity(
                                required.protocolVersion,
                                required.flightId,
                                required.generation,
                                required.authEpoch,
                                required.origin,
                                required.episodePath,
                            ),
                            NtkAckProtocol.FAILURE_SIGN_CAPABILITY,
                        )
                    },
                )
            }
        }

        override fun executeExactRequest(request: NtkAckSignRequest?, callback: INtkAckBrowserCallback?) {
            if (!authorize(request?.protocolVersion, callback, 0, "execute_exact")) return
            val required = request!!
            val target = callback!!
            mainHandler.post {
                runCatching {
                    engine.executeExact(required) { result ->
                        result.fold(
                            onSuccess = { exchange ->
                                safeCallback(target) { it.onExactRequestExecuted(exchange) }
                            },
                            onFailure = {
                                engineFailure(
                                    target,
                                    "execute_exact",
                                    it,
                                    NtkAckFlightIdentity(
                                        required.protocolVersion,
                                        required.flightId,
                                        required.generation,
                                        required.authEpoch,
                                        required.origin,
                                        required.episodePath,
                                    ),
                                    NtkAckProtocol.FAILURE_SIGN_CAPABILITY,
                                )
                            },
                        )
                    }
                }.onFailure {
                    engineFailure(
                        target,
                        "execute_exact",
                        it,
                        NtkAckFlightIdentity(
                            required.protocolVersion,
                            required.flightId,
                            required.generation,
                            required.authEpoch,
                            required.origin,
                            required.episodePath,
                        ),
                        NtkAckProtocol.FAILURE_SIGN_CAPABILITY,
                    )
                }
            }
        }

        override fun cancel(identity: NtkAckFlightIdentity?, reasonCode: Int) {
            if (identity == null || Binder.getCallingUid() != applicationInfo.uid ||
                identity.protocolVersion != NtkAckProtocol.VERSION
            ) return
            mainHandler.post { engine.cancel(identity, reasonCode) }
        }

        override fun clearStrictState(request: NtkAckClearRequest?, callback: INtkAckBrowserCallback?) {
            if (!authorize(request?.protocolVersion, callback, 0, "clear")) return
            val required = request!!
            val target = callback!!
            mainHandler.post {
                engine.clearStrictState(required) { result ->
                    result.fold(
                        onSuccess = { safeCallback(target) { it.onWarmReady(buildHello()) } },
                        onFailure = { engineFailure(target, "clear", it, required.identity) },
                    )
                }
            }
        }
    }

    private fun authorize(
        protocolVersion: Int?,
        callback: INtkAckBrowserCallback?,
        clientPid: Int,
        stage: String,
    ): Boolean {
        if (callback == null) return false
        if (Binder.getCallingUid() != applicationInfo.uid) {
            failure(callback, stage, NtkAckProtocol.FAILURE_INVALID_CALLER, SecurityException("caller UID"))
            return false
        }
        if (protocolVersion != NtkAckProtocol.VERSION) {
            failure(callback, stage, NtkAckProtocol.FAILURE_PROTOCOL_MISMATCH, IllegalArgumentException("protocol"))
            return false
        }
        if (clientPid > 0 && clientPid != Binder.getCallingPid()) {
            failure(callback, stage, NtkAckProtocol.FAILURE_INVALID_CALLER, SecurityException("caller PID"))
            return false
        }
        return true
    }

    private fun buildHello() = NtkAckServiceHello(
        NtkAckProtocol.VERSION,
        serviceInstanceId,
        Process.myPid(),
        proofKey.public.encoded,
        NtkAckProtocol.DATA_DIRECTORY_SUFFIX,
        engine.webViewCreatedPid,
        android.os.SystemClock.elapsedRealtimeNanos(),
    )

    private fun linkOwnerDeath(callback: INtkAckBrowserCallback, identity: NtkAckFlightIdentity): Boolean =
        runCatching {
            callback.asBinder().linkToDeath(
                { mainHandler.post { if (::engine.isInitialized) engine.cancel(identity, NtkAckProtocol.FAILURE_BINDER_DIED) } },
                0,
            )
        }.fold(
            onSuccess = { true },
            onFailure = {
                failure(
                    callback,
                    "callback_owner",
                    NtkAckProtocol.FAILURE_BINDER_DIED,
                    it,
                    identity.flightId,
                    identity.generation,
                )
                false
            },
        )

    private fun engineFailure(
        callback: INtkAckBrowserCallback,
        stage: String,
        error: Throwable,
        identity: NtkAckFlightIdentity? = null,
        fallbackReason: Int = NtkAckProtocol.FAILURE_INTERNAL,
    ) {
        val typed = error as? NtkAckException
        if (typed != null) {
            safeCallback(callback) { it.onFailure(typed.failure) }
            return
        }
        failure(
            callback,
            stage,
            fallbackReason,
            error,
            identity?.flightId.orEmpty(),
            identity?.generation ?: 0L,
        )
    }

    private fun failure(
        callback: INtkAckBrowserCallback,
        stage: String,
        reason: Int,
        error: Throwable,
        flightId: String = "",
        generation: Long = 0L,
    ) = safeCallback(callback) {
        it.onFailure(
            NtkAckFailure(
                NtkAckProtocol.VERSION,
                flightId,
                generation,
                reason,
                stage,
                true,
                error.javaClass.simpleName + ":" + error.message.orEmpty(),
                android.os.SystemClock.elapsedRealtimeNanos(),
            ),
        )
    }

    private fun safeCallback(callback: INtkAckBrowserCallback, action: (INtkAckBrowserCallback) -> Unit) {
        runCatching { action(callback) }
    }

    private fun NtkAckRequest.identity() = NtkAckFlightIdentity(
        protocolVersion,
        flightId,
        generation,
        authEpoch,
        origin,
        episodePath,
    )
}
