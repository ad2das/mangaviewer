package ml.melun.mangaview.ntkack

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import ml.melun.mangaview.MainApplication
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Main-process owner for the isolated ACK service.
 *
 * A logical ACK flight is submitted at most once. Binder death, a service failure, or a deadline
 * is terminal for that flight; none of those conditions causes an automatic bind or RPC retry.
 */
class NtkAckBrowserClient private constructor(private val context: Context) {
    private enum class BindState { UNBOUND, BINDING, CONNECTED, DEAD }

    class FlightHandle internal constructor(
        private val owner: NtkAckBrowserClient,
        internal val pending: PendingFlight,
    ) {
        val request: NtkAckRequest get() = pending.request
        val identity: NtkAckFlightIdentity get() = pending.identity
        val isDone: Boolean get() = pending.terminal.get()

        @Throws(Exception::class)
        fun joinProof(): NtkAckProof = pending.proof.awaitUntil(
            request.deadlineElapsedRealtimeNanos,
            "ACK proof",
        )

        @Throws(Exception::class)
        fun quiesce(): NtkAckQuiescenceSeal = owner.quiesce(pending)

        @Throws(Exception::class)
        fun signExact(
            endpoint: String,
            requestIdentityDigestSha256: String,
            imagesTokenDigestSha256: String,
            bodyBytes: ByteArray,
        ): NtkAckSignature = owner.signExact(
            pending,
            endpoint,
            requestIdentityDigestSha256,
            imagesTokenDigestSha256,
            bodyBytes,
        )

        @Throws(Exception::class)
        fun executeExact(
            endpoint: String,
            requestIdentityDigestSha256: String,
            imagesTokenDigestSha256: String,
            bodyBytes: ByteArray,
            requestHeaders: Map<String, String>,
        ): NtkAckExactExchange = owner.executeExact(
            pending,
            endpoint,
            requestIdentityDigestSha256,
            imagesTokenDigestSha256,
            bodyBytes,
            requestHeaders,
        )

        fun cancel(reasonCode: Int = NtkAckProtocol.FAILURE_CANCELLED) {
            owner.cancel(pending, reasonCode)
        }

        /** Runs once ACK-critical transport is complete; it grants no image/display authority. */
        fun whenNetworkPrerequisitesReady(action: () -> Unit) {
            pending.networkPrerequisitesReady.thenRun {
                if (!pending.terminal.get()) action()
            }
        }
    }

    private val lock = Any()
    private var state = BindState.UNBOUND
    private var service: INtkAckBrowserService? = null
    private var serviceBinder: IBinder? = null
    private var verifiedService: NtkAckProofVerifier.VerifiedService? = null
    private var warmBlocked = false
    private var bound = false
    private val flights = ConcurrentHashMap<String, PendingFlight>()
    private val signingCertificateDigest by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        signingCertificateDigest(context)
    }
    private val deathRecipient = IBinder.DeathRecipient { markDead("binder_death") }

    fun bindAndWarm() {
        check(!ProcessRole.isNtkAckProcess(context)) { "ACK client cannot run in service process" }
        synchronized(lock) {
            if (warmBlocked) return
            when (state) {
                BindState.BINDING -> return
                // onServiceConnected already submitted the independent hello/warm request.
                // Re-queueing it here would put WebView work ahead of this episode's startAck.
                BindState.CONNECTED -> return
                BindState.DEAD, BindState.UNBOUND -> {
                    state = BindState.BINDING
                    verifiedService = null
                }
            }
        }
        val didBind = context.bindService(
            Intent(context, NtkAckBrowserService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        synchronized(lock) {
            bound = didBind
            if (!didBind) state = BindState.DEAD
        }
        if (!didBind) markDead("bind_failed")
    }

    /** Creates or joins the one exact flight for this full authority identity without blocking. */
    fun startAck(
        origin: String,
        episodePath: String,
        generation: Long,
        userAgent: String,
        seedCookies: List<NtkAckCookie>,
    ): FlightHandle {
        check(!ProcessRole.isNtkAckProcess(context)) { "ACK flight cannot start in service process" }
        require(generation > 0L)
        require(userAgent.isNotBlank())
        val authEpoch = authEpoch()
        val request = NtkAckRequest(
            protocolVersion = NtkAckProtocol.VERSION,
            flightId = UUID.randomUUID().toString(),
            generation = generation,
            authEpoch = authEpoch,
            requestNonce = ByteArray(32).also(SecureRandom()::nextBytes),
            origin = origin,
            episodePath = episodePath,
            userAgent = userAgent,
            uaMetadata = uaMetadata(),
            viewport = viewport(),
            seedCookies = NtkAckCookieBoundary.validateSeeds(origin, episodePath, seedCookies),
            deadlineElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() +
                TimeUnit.SECONDS.toNanos(ACK_DEADLINE_SECONDS),
            clientPid = Process.myPid(),
        )
        val pending = PendingFlight(request).also { it.owner = this }
        val existing = flights.putIfAbsent(request.singleFlightKey, pending)
        if (existing != null) return FlightHandle(this, existing)
        bindAndWarm()
        dispatchAckIfReady(pending)
        return FlightHandle(this, pending)
    }

    fun blockWarmForNative(path: String, generation: Long) {
        require(path.isNotBlank() && generation > 0L)
        synchronized(lock) {
            warmBlocked = true
        }
    }

    fun allowWarmAfterNativeExit() {
        val snapshot = synchronized(lock) {
            warmBlocked = false
            val current = Triple(bound, serviceBinder, service)
            bound = false
            service = null
            serviceBinder = null
            verifiedService = null
            state = BindState.UNBOUND
            current
        }
        val terminal = NtkAckClientException("ACK client released after native viewer exit")
        flights.values.forEach { pending ->
            if (pending.terminal.compareAndSet(false, true)) {
                snapshot.third?.let { remote ->
                    runCatching { remote.cancel(pending.identity, NtkAckProtocol.FAILURE_CANCELLED) }
                }
                pending.failAll(terminal)
                flights.remove(pending.request.singleFlightKey, pending)
            }
        }
        runCatching { snapshot.second?.unlinkToDeath(deathRecipient, 0) }
        if (snapshot.first) {
            runCatching { context.unbindService(connection) }
                .onFailure { Log.d(TAG, "ack_service_unbind_after_native_exit", it) }
        }
        Log.d(TAG, "ack_service_quiet_after_native_exit")
    }

    fun verifiedHello(): NtkAckServiceHello? = synchronized(lock) { verifiedService?.hello }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = INtkAckBrowserService.Stub.asInterface(binder)
            if (remote == null || binder == null) {
                markDead("null_binder")
                return
            }
            val accepted = synchronized(lock) { state == BindState.BINDING }
            if (!accepted) {
                runCatching { context.unbindService(this) }
                return
            }
            val previousBinder = synchronized(lock) { serviceBinder }
            if (previousBinder !== binder) {
                runCatching { previousBinder?.unlinkToDeath(deathRecipient, 0) }
            }
            runCatching { binder.linkToDeath(deathRecipient, 0) }
                .onFailure {
                    markDead("link_to_death")
                    return
                }
            synchronized(lock) {
                service = remote
                serviceBinder = binder
                state = BindState.CONNECTED
            }
            // Start the click-owned ACK before the independent hello/warm RPC. Both service calls
            // are posted to its main looper; reversing this order makes cold WebView creation
            // serialize the challenge transport by almost a second. startAck starts network
            // prerequisites first and owns its own same-click WebView lifecycle.
            flights.values.forEach(::dispatchAckIfReady)
            requestWarm(remote)
        }

        override fun onServiceDisconnected(name: ComponentName?) = markDead("service_disconnected")
        override fun onBindingDied(name: ComponentName?) = markDead("binding_died")
        override fun onNullBinding(name: ComponentName?) = markDead("null_binding")
    }

    private fun requestWarm(remote: INtkAckBrowserService) {
        if (synchronized(lock) { warmBlocked }) return
        runCatching { remote.warm(buildWarmRequest(), warmCallback) }
            .onFailure { markDead("warm_binder_call") }
    }

    private val warmCallback = object : INtkAckBrowserCallback.Stub() {
        override fun onWarmReady(hello: NtkAckServiceHello?) {
            if (hello == null) return markDead("null_hello")
            runCatching { NtkAckProofVerifier.verifyHelloOrThrow(hello) }
                .onSuccess { verified ->
                    synchronized(lock) { verifiedService = verified }
                    Log.d(TAG, "ack_webview_warm_ready servicePid=${hello.servicePid}")
                    flights.values.forEach { pending ->
                        dispatchAckIfReady(pending)
                        verifyPendingProofIfReady(pending)
                    }
                }
                .onFailure { markDead("invalid_hello") }
        }

        override fun onAckProved(proof: NtkAckProof?) = Unit
        override fun onNetworkPrerequisitesReady(identity: NtkAckFlightIdentity?) = Unit
        override fun onQuiesced(seal: NtkAckQuiescenceSeal?) = Unit
        override fun onExactRequestSigned(signature: NtkAckSignature?) = Unit
        override fun onExactRequestExecuted(exchange: NtkAckExactExchange?) = Unit
        override fun onFailure(failure: NtkAckFailure?) = markDead(
            "warm_failure_${failure?.reasonCode ?: NtkAckProtocol.FAILURE_INTERNAL}",
            failure,
        )
    }

    private fun dispatchAckIfReady(pending: PendingFlight) {
        if (pending.terminal.get() || pending.ackSubmitted.get()) return
        val remote = synchronized(lock) { service } ?: return
        if (!pending.ackSubmitted.compareAndSet(false, true)) return
        runCatching { remote.startAck(pending.request, pending.callback) }
            .onFailure { fail(pending, "start_ack_binder", it) }
    }

    private fun quiesce(pending: PendingFlight): NtkAckQuiescenceSeal {
        pending.proof.awaitUntil(pending.request.deadlineElapsedRealtimeNanos, "ACK proof")
        check(!pending.terminal.get()) { "ACK flight is terminal" }
        if (pending.quiesceSubmitted.compareAndSet(false, true)) {
            val remote = synchronized(lock) { service }
                ?: throw failAndReturn(pending, "quiesce_no_service", IllegalStateException("ACK service unavailable"))
            runCatching { remote.quiesce(pending.identity, pending.callback) }
                .onFailure { fail(pending, "quiesce_binder", it) }
        }
        return pending.seal.awaitFor(QUIESCENCE_TIMEOUT_SECONDS, "ACK quiescence")
    }

    private fun signExact(
        pending: PendingFlight,
        endpoint: String,
        requestIdentityDigestSha256: String,
        imagesTokenDigestSha256: String,
        bodyBytes: ByteArray,
    ): NtkAckSignature {
        val proof = pending.proof.valueOrThrow()
        val seal = pending.seal.valueOrThrow()
        check(!pending.terminal.get()) { "ACK flight is terminal" }
        val request = buildSignRequest(
            proof,
            endpoint,
            requestIdentityDigestSha256,
            imagesTokenDigestSha256,
            bodyBytes,
        )
        check(pending.signSubmitted.compareAndSet(false, true)) {
            "Exact request signature capability is one-shot"
        }
        pending.signRequest = request
        val remote = synchronized(lock) { service }
            ?: throw failAndReturn(pending, "sign_no_service", IllegalStateException("ACK service unavailable"))
        runCatching { remote.signExactRequest(request, pending.callback) }
            .onFailure { fail(pending, "sign_binder", it) }
        val signature = pending.signature.awaitFor(SIGN_TIMEOUT_SECONDS, "exact request signature")
        verifySignatureOrThrow(signature, request, proof, seal)
        pending.terminal.set(true)
        flights.remove(pending.request.singleFlightKey, pending)
        return signature
    }

    private fun executeExact(
        pending: PendingFlight,
        endpoint: String,
        requestIdentityDigestSha256: String,
        imagesTokenDigestSha256: String,
        bodyBytes: ByteArray,
        requestHeaders: Map<String, String>,
    ): NtkAckExactExchange {
        val proof = pending.proof.valueOrThrow()
        val seal = pending.seal.valueOrThrow()
        check(!pending.terminal.get()) { "ACK flight is terminal" }
        val request = buildSignRequest(
            proof,
            endpoint,
            requestIdentityDigestSha256,
            imagesTokenDigestSha256,
            bodyBytes,
            requestHeaders,
        )
        check(pending.signSubmitted.compareAndSet(false, true)) {
            "Exact request capability is one-shot"
        }
        pending.signRequest = request
        val remote = synchronized(lock) { service }
            ?: throw failAndReturn(
                pending,
                "execute_no_service",
                IllegalStateException("ACK service unavailable"),
            )
        runCatching { remote.executeExactRequest(request, pending.callback) }
            .onFailure { fail(pending, "execute_binder", it) }
        val exchange = pending.exactExchange.awaitUntil(
            pending.request.deadlineElapsedRealtimeNanos,
            "exact image API",
        )
        verifyExactExchangeOrThrow(exchange, request, proof, seal)
        pending.terminal.set(true)
        flights.remove(pending.request.singleFlightKey, pending)
        return exchange
    }

    private fun buildSignRequest(
        proof: NtkAckProof,
        endpoint: String,
        requestIdentityDigestSha256: String,
        imagesTokenDigestSha256: String,
        bodyBytes: ByteArray,
        requestHeaders: Map<String, String> = emptyMap(),
    ) = NtkAckSignRequest(
        NtkAckProtocol.VERSION,
        proof.proofId,
        proof.flightId,
        proof.generation,
        proof.authEpoch,
        proof.origin,
        proof.episodePath,
        "POST",
        endpoint,
        requestIdentityDigestSha256,
        imagesTokenDigestSha256,
        bodyBytes.clone(),
        requestHeaders.entries.map { (name, value) ->
            NtkAckHeader(name, listOf(value))
        },
    )

    private fun cancel(pending: PendingFlight, reasonCode: Int) {
        if (!pending.terminal.compareAndSet(false, true)) return
        synchronized(lock) { service }?.let { remote ->
            runCatching { remote.cancel(pending.identity, reasonCode) }
        }
        val error = NtkAckClientException("ACK flight cancelled reason=$reasonCode")
        pending.failAll(error)
        flights.remove(pending.request.singleFlightKey, pending)
    }

    private fun buildWarmRequest() = NtkAckWarmRequest(
        NtkAckProtocol.VERSION,
        authEpoch(),
        configuredUserAgent(),
        viewport(),
        Process.myPid(),
        runCatching {
            MainApplication.getHttpClient().prepareNtkAckControlWarmSeeds()
        }.getOrElse { error ->
            Log.d(TAG, "ack_control_plane_seed_prepare_failed", error)
            emptyList()
        },
    )

    private fun authEpoch(): Long = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
        .getLong(AUTH_EPOCH_KEY, 1L)
        .coerceAtLeast(1L)

    private fun configuredUserAgent(): String {
        val preferences = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
        return preferences.getString("ntkDeviceIdentityUserAgent", null)
            ?.takeIf(String::isNotBlank)
            ?: preferences.getString("httpUserAgent", null)?.takeIf(String::isNotBlank)
            ?: DEFAULT_USER_AGENT
    }

    private fun viewport(): NtkAckViewport = context.resources.displayMetrics.let { metrics ->
        NtkAckViewport(
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1),
            metrics.densityDpi,
        )
    }

    private fun uaMetadata(): String =
        "{\"platform\":\"Android\",\"mobile\":true,\"sdk\":${Build.VERSION.SDK_INT}}"

    private fun markDead(reason: String, failure: NtkAckFailure? = null) {
        val binder = synchronized(lock) {
            val previous = serviceBinder
            service = null
            serviceBinder = null
            verifiedService = null
            state = BindState.DEAD
            previous
        }
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        val error = failure?.let(::NtkAckClientException)
            ?: NtkAckClientException("ACK service terminal: $reason")
        flights.values.forEach { pending ->
            if (pending.terminal.compareAndSet(false, true)) {
                pending.failAll(error)
                flights.remove(pending.request.singleFlightKey, pending)
            }
        }
        Log.e(TAG, "ack_service_terminal reason=$reason")
    }

    private fun fail(pending: PendingFlight, stage: String, error: Throwable) {
        if (!pending.terminal.compareAndSet(false, true)) return
        pending.failAll(error)
        flights.remove(pending.request.singleFlightKey, pending)
        Log.e(TAG, "ack_flight_terminal stage=$stage,path=${pending.request.episodePath}", error)
    }

    private fun failAndReturn(
        pending: PendingFlight,
        stage: String,
        error: Throwable,
    ): Throwable = error.also { fail(pending, stage, it) }

    private fun verifySignatureOrThrow(
        signature: NtkAckSignature,
        request: NtkAckSignRequest,
        proof: NtkAckProof,
        seal: NtkAckQuiescenceSeal,
    ) {
        require(signature.protocolVersion == NtkAckProtocol.VERSION)
        require(signature.proofId == proof.proofId)
        require(signature.flightId == request.flightId && signature.generation == request.generation)
        require(signature.authEpoch == request.authEpoch)
        require(signature.origin == request.origin && signature.episodePath == request.episodePath)
        require(signature.method == request.method && signature.endpoint == request.endpoint)
        require(signature.requestIdentityDigestSha256 == request.requestIdentityDigestSha256)
        require(signature.bodyDigestSha256 == NtkAckProofCodec.sha256Hex(request.bodyBytes))
        require(signature.requestKeyId == proof.requestKeyId && signature.requestKeyId.isNotBlank())
        require(signature.signatureFormat == "p1363" && signature.signatureValue.isNotBlank())
        require(signature.timestamp.isNotBlank() && signature.nonce.isNotBlank())
        require(signature.quiescenceDigestSha256 == seal.envelopeDigestSha256)
        require(signature.signedAtElapsedNanos >= seal.completedAtElapsedNanos)
    }

    private fun verifyExactExchangeOrThrow(
        exchange: NtkAckExactExchange,
        request: NtkAckSignRequest,
        proof: NtkAckProof,
        seal: NtkAckQuiescenceSeal,
    ) {
        require(exchange.protocolVersion == NtkAckProtocol.VERSION)
        verifySignatureOrThrow(exchange.signature, request, proof, seal)
        val exactUrl = proof.origin + request.endpoint
        require(exchange.requestUrl == exactUrl && exchange.finalUrl == exactUrl)
        require(exchange.status in 100..599)
        require(exchange.completedAtElapsedNanos >= exchange.signature.signedAtElapsedNanos)
        require(exchange.responseHeaders.all { header ->
            header.name.isNotBlank() && header.values.none(String::isBlank)
        })
    }

    internal class PendingFlight(val request: NtkAckRequest) {
        val identity = NtkAckFlightIdentity(
            request.protocolVersion,
            request.flightId,
            request.generation,
            request.authEpoch,
            request.origin,
            request.episodePath,
        )
        val ackSubmitted = AtomicBoolean(false)
        val quiesceSubmitted = AtomicBoolean(false)
        val signSubmitted = AtomicBoolean(false)
        val proofVerificationStarted = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val unverifiedProof = AtomicReference<NtkAckProof?>()
        val proof = ResultGate<NtkAckProof>()
        val seal = ResultGate<NtkAckQuiescenceSeal>()
        val signature = ResultGate<NtkAckSignature>()
        val exactExchange = ResultGate<NtkAckExactExchange>()
        val networkPrerequisitesReady = CompletableFuture<NtkAckFlightIdentity>()
        @Volatile var signRequest: NtkAckSignRequest? = null
        lateinit var owner: NtkAckBrowserClient

        val callback = object : INtkAckBrowserCallback.Stub() {
            override fun onWarmReady(hello: NtkAckServiceHello?) = Unit

            override fun onNetworkPrerequisitesReady(value: NtkAckFlightIdentity?) {
                owner.onNetworkPrerequisitesReady(this@PendingFlight, value)
            }

            override fun onAckProved(value: NtkAckProof?) {
                owner.onProof(this@PendingFlight, value)
            }

            override fun onQuiesced(value: NtkAckQuiescenceSeal?) {
                owner.onSeal(this@PendingFlight, value)
            }

            override fun onExactRequestSigned(value: NtkAckSignature?) {
                owner.onSignature(this@PendingFlight, value)
            }

            override fun onExactRequestExecuted(value: NtkAckExactExchange?) {
                owner.onExactExchange(this@PendingFlight, value)
            }

            override fun onFailure(failure: NtkAckFailure?) {
                owner.fail(
                    this@PendingFlight,
                    failure?.stage ?: "service_failure",
                    failure?.let(::NtkAckClientException)
                        ?: NtkAckClientException("ACK service returned null failure"),
                )
            }
        }

        fun failAll(error: Throwable) {
            networkPrerequisitesReady.completeExceptionally(error)
            proof.fail(error)
            seal.fail(error)
            signature.fail(error)
            exactExchange.fail(error)
        }
    }

    private fun onNetworkPrerequisitesReady(
        pending: PendingFlight,
        value: NtkAckFlightIdentity?,
    ) {
        if (pending.terminal.get()) return
        if (value != pending.identity) {
            fail(
                pending,
                "network_prerequisites_identity",
                IllegalStateException("ACK network-prerequisite identity mismatch"),
            )
            return
        }
        if (pending.networkPrerequisitesReady.complete(value)) {
            Log.d(TAG, "ack_network_prerequisites_client_ready path=${value.episodePath}")
        }
    }

    private fun onProof(pending: PendingFlight, value: NtkAckProof?) {
        if (pending.terminal.get()) return
        val received = value ?: return fail(
            pending,
            "verify_proof",
            IllegalStateException("ACK service returned null proof"),
        )
        if (!pending.unverifiedProof.compareAndSet(null, received)) return
        verifyPendingProofIfReady(pending)
    }

    private fun verifyPendingProofIfReady(pending: PendingFlight) {
        if (pending.terminal.get()) return
        val received = pending.unverifiedProof.get() ?: return
        val verified = synchronized(lock) { verifiedService } ?: return
        if (!pending.proofVerificationStarted.compareAndSet(false, true)) return
        runCatching {
            NtkAckProofVerifier.verifyOrThrow(
                received,
                verified,
                pending.request,
                context.packageName,
                signingCertificateDigest,
            )
        }.onSuccess(pending.proof::complete)
            .onFailure { fail(pending, "verify_proof", it) }
    }

    private fun onSeal(pending: PendingFlight, value: NtkAckQuiescenceSeal?) {
        if (pending.terminal.get()) return
        runCatching {
            NtkAckProofVerifier.verifyQuiescenceOrThrow(
                checkNotNull(value) { "ACK service returned null quiescence seal" },
                pending.proof.valueOrThrow(),
                checkNotNull(synchronized(lock) { verifiedService }) {
                    "ACK service hello is unavailable"
                },
            )
        }.onSuccess(pending.seal::complete)
            .onFailure { fail(pending, "verify_quiescence", it) }
    }

    private fun onSignature(pending: PendingFlight, value: NtkAckSignature?) {
        if (pending.terminal.get()) return
        runCatching {
            val signature = checkNotNull(value) { "ACK service returned null signature" }
            verifySignatureOrThrow(
                signature,
                checkNotNull(pending.signRequest) { "Exact sign request is unavailable" },
                pending.proof.valueOrThrow(),
                pending.seal.valueOrThrow(),
            )
            signature
        }.onSuccess(pending.signature::complete)
            .onFailure { fail(pending, "verify_signature", it) }
    }

    private fun onExactExchange(pending: PendingFlight, value: NtkAckExactExchange?) {
        if (pending.terminal.get()) return
        runCatching {
            checkNotNull(value) { "ACK service returned null exact exchange" }
        }.onSuccess(pending.exactExchange::complete)
            .onFailure { fail(pending, "verify_exact_exchange", it) }
    }

    internal class ResultGate<T> {
        private val latch = CountDownLatch(1)
        private val completed = AtomicBoolean(false)
        @Volatile private var value: T? = null
        @Volatile private var failure: Throwable? = null

        fun complete(result: T) {
            if (!completed.compareAndSet(false, true)) return
            value = result
            latch.countDown()
        }

        fun fail(error: Throwable) {
            if (!completed.compareAndSet(false, true)) return
            failure = error
            latch.countDown()
        }

        fun awaitUntil(deadlineElapsedNanos: Long, label: String): T {
            val remaining = deadlineElapsedNanos - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0L || !latch.await(remaining, TimeUnit.NANOSECONDS)) {
                throw TimeoutException("$label deadline expired")
            }
            return valueOrThrow()
        }

        fun awaitFor(seconds: Long, label: String): T {
            if (!latch.await(seconds, TimeUnit.SECONDS)) throw TimeoutException("$label timed out")
            return valueOrThrow()
        }

        fun valueOrThrow(): T {
            failure?.let { throw it }
            return checkNotNull(value) { "Result is not available" }
        }
    }

    class NtkAckClientException : Exception {
        val failure: NtkAckFailure?

        constructor(message: String) : super(message) {
            failure = null
        }

        constructor(failure: NtkAckFailure) : super(
            "ACK failure stage=${failure.stage},reason=${failure.reasonCode},message=${failure.message}",
        ) {
            this.failure = failure
        }
    }

    companion object {
        private const val TAG = "NtkAck"
        private const val AUTH_EPOCH_KEY = "ntkAckAuthEpochV2"
        private const val ACK_DEADLINE_SECONDS = 20L
        private const val QUIESCENCE_TIMEOUT_SECONDS = 10L
        private const val SIGN_TIMEOUT_SECONDS = 5L
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var instance: NtkAckBrowserClient? = null

        @JvmStatic
        fun get(context: Context): NtkAckBrowserClient = instance ?: synchronized(this) {
            instance ?: NtkAckBrowserClient(context.applicationContext).also { instance = it }
        }

        private fun signingCertificateDigest(context: Context): String {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            }
            val info = context.packageManager.getPackageInfo(context.packageName, flags)
            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION") info.signatures?.firstOrNull()
            } ?: error("Signing certificate is unavailable")
            return NtkAckProofCodec.sha256Hex(signature.toByteArray())
        }
    }
}
