package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotConnection
import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotJson
import com.iaeluk.hermex.core.network.bots.flag
import com.iaeluk.hermex.core.network.bots.fields
import com.iaeluk.hermex.core.network.bots.get
import com.iaeluk.hermex.core.network.bots.list
import com.iaeluk.hermex.core.network.bots.parseBotJson
import com.iaeluk.hermex.core.network.bots.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * One direct-Hermes connection: one ephemeral cookie jar, one password sign-in, and the
 * path every Bot HTTP request and gateway upgrade is sent through.
 *
 * Sign-in is single-flight: it reads the public `/api/status`, refuses a release older
 * than [HermesCompatibility.minimumVersion] and checks the install identity before the
 * password goes out, then verifies the identity the host returns. Cancelling one waiting
 * consumer never cancels it. A signed-in request the host answers with 401 signs in
 * again once, sharing that sign-in with any other consumer's 401, and is resent: the auth
 * gate refuses before any handler runs, so the resend cannot repeat a write. Nothing else
 * is retried, and a transport failure or 5xx leaves the sign-in as it was. After
 * [retire], every call and every late reply throws [BotFailure.Stale].
 */
class HermesConnection(
    val connection: BotConnection,
) {
    /** 15 seconds per request and 30 overall, like the gateway's own calls; 120/180 for provisioning work. */
    enum class Deadline { STANDARD, PROVISIONING }

    sealed class State {
        object Connecting : State()
        data class Ready(val installID: String?) : State()
        object Stale : State()
    }

    /** The canonical dashboard root every request is built from. */
    val address: HttpUrl = connection.address.toHttpUrlOrNull() ?: throw BotFailure.InvalidAddress

    /** `version` from the last sign-in's `/api/status`, read before the auth gate; null when omitted. */
    var serverVersion: String? = null
        private set

    /** `install_id` from the same read; null when omitted. */
    var serverInstallID: String? = null
        private set

    var state: State = State.Connecting
        private set

    var isSignedIn = false
        private set

    var isRetired = false
        private set

    /**
     * Called when the login step answers 401, before the failure reaches any consumer,
     * including the re-login a signed-in 401 starts; nothing here remembers it.
     */
    var onLoginRejected: (() -> Unit)? = null

    private val headers = HermesHeaders.saved(connection.headers)
    private val cookieJar = EphemeralCookieJar()
    private val standardClient = buildClient(readWriteSeconds = 15, callSeconds = 30)
    private val provisioningClient = buildClient(readWriteSeconds = 120, callSeconds = 180)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signInMutex = Mutex()
    private var signInAttempt: Deferred<Unit>? = null
    private var epoch = 0

    /**
     * Signs in unless this connection already is. Concurrent callers share one attempt,
     * which runs on the deadline of the caller that started it.
     */
    suspend fun signIn(deadline: Deadline = Deadline.STANDARD) {
        checkCurrent()
        if (isSignedIn) return
        val attempt = signInMutex.withLock {
            checkCurrent()
            if (isSignedIn) return
            val existing = signInAttempt?.takeIf { !it.isCompleted }
            existing ?: scope.async { beginSignIn(deadline) }.also { signInAttempt = it }
        }
        try {
            attempt.await()
        } catch (error: Throwable) {
            checkCurrent()
            throw error
        }
        checkCurrent()
    }

    /**
     * Reads the public `/api/status` once, with this connection's headers and no
     * credentials, classified as sign-in classifies it: [BotFailure.NotDashboard],
     * [BotFailure.Blocked] or [BotFailure.Rejected]. The connect form reads it to tell a
     * Hermes dashboard from a webui. Only the dashboard's own Host-header refusal is
     * [BotFailure.Rejected] 400 here; any other 400 is [BotFailure.NotDashboard].
     */
    suspend fun status(): BotJson {
        checkCurrent()
        return publicStatus(standardClient, onlyHostRefusalIs400 = true)
    }

    /** One public `/api/status` read, without signing in: whether the host answers at all. */
    suspend fun answersStatus(): Boolean {
        if (isRetired) return false
        return try {
            publicStatus(standardClient)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * The body of one public route the host answers before its auth gate, sent with this
     * connection's headers and without signing in. Any status but 200 throws
     * [BotFailure.Rejected].
     */
    suspend fun publicData(rest: HermesRest): ByteArray {
        checkCurrent()
        val body = send(rest, standardClient)
        checkCurrent()
        return body
    }

    /**
     * Sends one signed-in request built from `rest` and returns the body of a reply whose
     * status is in [accepted]. Any other status throws [BotFailure.Rejected].
     */
    suspend fun data(
        rest: HermesRest,
        deadline: Deadline = Deadline.STANDARD,
        accepted: IntRange = 200..200,
        validateDispatch: (() -> Unit)? = null,
    ): ByteArray = authorized(rest.request(address), deadline, validateDispatch) { request, client ->
        sendBytes(request, client, accepted)
    }

    /**
     * Sends one signed-in request built from `rest` and returns its body and status,
     * whatever the status, for routes whose refusals carry the host's reason. A 401 still
     * signs in again and resends once, as [data] does.
     */
    suspend fun reply(
        rest: HermesRest,
        deadline: Deadline = Deadline.STANDARD,
        validateDispatch: (() -> Unit)? = null,
    ): Pair<ByteArray, Int> = authorized(rest.request(address), deadline, validateDispatch) { request, client ->
        val reply = exchange(request, client)
        if (reply.code == 401) throw BotFailure.Rejected(401)
        reply.body to reply.code
    }

    /** Mints one single-use ticket and returns the gateway upgrade that presents it. */
    suspend fun gatewayUpgrade(): Request {
        val body = data(HermesRest.Ticket)
        val token = parseBotJson(body.decodeToString())["ticket"].text?.takeIf { it.isNotEmpty() }
            ?: throw BotFailure.Unsupported
        return headers.applied(HermesRest.gatewayUpgrade(address, token), address)
    }

    /**
     * Whether `saved` is still this connection: the same UUID, address, account, password
     * and headers, and no conflicting install id. A newly backfilled install id is taken.
     */
    fun adopt(saved: BotConnection): Boolean {
        if (isRetired) return false
        if (saved.id != connection.id || saved.address != connection.address ||
            saved.username != connection.username || saved.password != connection.password ||
            saved.headers.orEmpty() != connection.headers.orEmpty()
        ) {
            return false
        }
        val live = connection.installID
        val stored = saved.installID
        if (live != null && stored != null && live != stored) return false
        if (connection.installID == null) connection.installID = saved.installID
        return true
    }

    /**
     * Ends this connection when its server or configuration is replaced: a sign-in in
     * flight stops and stores nothing, and every call, late reply and resend after this
     * throws [BotFailure.Stale].
     */
    fun retire() {
        if (isRetired) return
        isRetired = true
        isSignedIn = false
        state = State.Stale
        scope.cancel()
    }

    private suspend fun beginSignIn(deadline: Deadline) {
        val client = clientFor(deadline)
        var step = "status"
        try {
            val status = publicStatus(client)
            checkCurrent()
            serverVersion = status["version"].text
            serverInstallID = BotConnection.installIdIn(status)
            val version = serverVersion
            if (version != null && !HermesCompatibility.isSupported(version)) throw BotFailure.Outdated(version)
            connection.requireSameInstall(serverInstallID)
            if (status["auth_required"].flag != true) throw BotFailure.Unsupported
            if (status["auth_providers"].list?.any { it.text == "basic" } != true) throw BotFailure.BrowserSignIn
            step = "login"
            send(HermesRest.Login(connection.username, connection.password), client)
            checkCurrent()
            step = "identity"
            val identity = sendDecoded(HermesRest.Identity, client)
            checkCurrent()
            if (identity["provider"].text != "basic") throw BotFailure.WrongIdentity
            // Trust on first use, in memory only: a later sign-in here must reach the same install.
            if (connection.installID == null) connection.installID = serverInstallID
            isSignedIn = true
            epoch += 1
            state = State.Ready(serverInstallID)
        } catch (error: Throwable) {
            if (!isRetired && step == "login" && error == BotFailure.Rejected(401)) onLoginRejected?.invoke()
            throw error
        }
    }

    /**
     * The first sign-in read. `/api/status` is public on every dashboard, so a 404, a body
     * that is not JSON, or a 401 whose body is a JSON object (the webui's auth gate) means
     * the address is something else, such as the webui. Any other 401 comes from something
     * in front of Hermes, such as Cloudflare Access.
     */
    private suspend fun publicStatus(client: OkHttpClient, onlyHostRefusalIs400: Boolean = false): BotJson {
        val reply = exchange(headers.applied(HermesRest.Status.request(address), address), client)
        val body = parseBotJson(reply.body.decodeToString())
        return when (reply.code) {
            200 -> body ?: throw BotFailure.NotDashboard
            401 -> if (body.fields != null) throw BotFailure.NotDashboard else throw BotFailure.Blocked
            404 -> throw BotFailure.NotDashboard
            400 -> if (onlyHostRefusalIs400 && body["detail"].text?.startsWith("Invalid Host header") != true) {
                throw BotFailure.NotDashboard
            } else {
                throw BotFailure.Rejected(400)
            }
            else -> throw BotFailure.Rejected(reply.code)
        }
    }

    /**
     * Runs `perform` signed in, with `request` given this connection's headers. `perform`
     * sends it and throws [BotFailure.Rejected] 401 for an unauthenticated reply, which
     * alone signs in again and resends it, once. `validateDispatch` runs just before each
     * send, the resend included, once any sign-in it waited on is done.
     */
    private suspend fun <T> authorized(
        request: Request,
        deadline: Deadline,
        validateDispatch: (() -> Unit)?,
        perform: suspend (Request, OkHttpClient) -> T,
    ): T {
        val prepared = headers.applied(request, address)
        val client = clientFor(deadline)
        signIn(deadline)
        validateDispatch?.invoke()
        val sentEpoch = epoch
        try {
            val value = perform(prepared, client)
            checkCurrent()
            return value
        } catch (rejected: BotFailure.Rejected) {
            if (rejected.code != 401) throw rejected
            checkCurrent()
            // The first reply to show the session expired drops it; later ones join the same sign-in.
            if (epoch == sentEpoch) isSignedIn = false
            signIn(deadline)
            validateDispatch?.invoke()
            val value = perform(prepared, client)
            checkCurrent()
            return value
        }
    }

    private suspend fun send(rest: HermesRest, client: OkHttpClient, accepted: IntRange = 200..200): ByteArray =
        sendBytes(headers.applied(rest.request(address), address), client, accepted)

    private suspend fun sendBytes(request: Request, client: OkHttpClient, accepted: IntRange): ByteArray {
        val reply = exchange(request, client)
        if (reply.code !in accepted) throw BotFailure.Rejected(reply.code)
        return reply.body
    }

    /**
     * A 200 whose body is not JSON reads as a broken reply, retryable, like the iOS
     * port's DecodingError.
     */
    private suspend fun sendDecoded(rest: HermesRest, client: OkHttpClient): BotJson {
        val bytes = send(rest, client)
        return parseBotJson(bytes.decodeToString()) ?: throw BotFailure.Transport
    }

    /**
     * Sends `request` and returns the final reply, whatever its status. A reply from
     * another host means a redirect led to something in front of Hermes, such as an
     * access proxy's sign-in page, and throws [BotFailure.Blocked]. Only the host is
     * compared: a same-host redirect from http to https is not a proxy. Redirects are
     * followed manually (the clients don't), dropping this connection's headers from any
     * hop that leaves the origin.
     */
    private suspend fun exchange(request: Request, client: OkHttpClient): RawReply {
        val response = try {
            sendFollowing(request, client)
        } catch (error: IOException) {
            throw BotFailure.Transport
        }
        try {
            if (response.request.url.host.lowercase() != request.url.host.lowercase()) throw BotFailure.Blocked
            val body = try {
                response.body.bytes()
            } catch (error: IOException) {
                throw BotFailure.Transport
            }
            return RawReply(body, response.code)
        } finally {
            response.close()
        }
    }

    private suspend fun sendFollowing(request: Request, client: OkHttpClient): Response {
        var current = request
        var hops = 0
        while (true) {
            val response = executeAwait(current, client)
            val location = redirectLocation(response)
            if (location == null) return response
            response.close()
            if (hops++ >= MAX_REDIRECTS) throw IOException("Too many redirects")
            val next = current.url.resolve(location) ?: throw IOException("Unresolvable redirect")
            var builder = current.newBuilder().url(next)
            val keepsMethod = response.code == 307 || response.code == 308
            builder = if (keepsMethod) builder else builder.method("GET", null)
            if (!HermesHeaders.isSameOrigin(next, address)) {
                for (header in headers.values) builder = builder.removeHeader(header.name.trim())
            }
            current = builder.build()
        }
    }

    private fun redirectLocation(response: Response): String? =
        if (response.code in REDIRECT_CODES) response.header("Location") else null

    private suspend fun executeAwait(request: Request, client: OkHttpClient): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    private fun clientFor(deadline: Deadline): OkHttpClient =
        if (deadline == Deadline.PROVISIONING) provisioningClient else standardClient

    /**
     * 15/30 like the gateway's own calls, and 120/180 for provisioning work: installing a
     * plugin clones a repository on the host and a restart takes the gateway down and back
     * up, so 15 seconds would read as a failure while the host was still succeeding.
     */
    private fun buildClient(readWriteSeconds: Long, callSeconds: Long): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(readWriteSeconds, TimeUnit.SECONDS)
            .writeTimeout(readWriteSeconds, TimeUnit.SECONDS)
            .callTimeout(callSeconds, TimeUnit.SECONDS)
            .build()

    private fun checkCurrent() {
        if (isRetired) throw BotFailure.Stale
    }

    private data class RawReply(val body: ByteArray, val code: Int)

    private companion object {
        const val MAX_REDIRECTS = 10
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
