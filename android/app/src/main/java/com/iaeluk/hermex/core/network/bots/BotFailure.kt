package com.iaeluk.hermex.core.network.bots

/**
 * The typed failure set of one direct-Hermes connection, kept apart from webui transport
 * errors so reconnect rules read only this. `Rejected` carries an HTTP status or a
 * JSON-RPC error code from the gateway.
 */
sealed class BotFailure : Exception() {
    /** After `retire()`: every call, late reply and resend throws this. */
    object Stale : BotFailure()

    object Unsupported : BotFailure()

    object MissingChat : BotFailure()

    object WrongIdentity : BotFailure()

    /** The host at this address reports a different install id than the one connected. */
    object DifferentHost : BotFailure()

    /** An HTTP or JSON-RPC refusal with its status or error code. */
    data class Rejected(val code: Int) : BotFailure()

    /** The transport failed before any status was read. */
    object Transport : BotFailure()

    object InvalidAddress : BotFailure()

    /** The address answered `/api/status` like something that is not a Hermes dashboard. */
    object NotDashboard : BotFailure()

    /** Something in front of Hermes wants its own sign-in, such as an access proxy. */
    object Blocked : BotFailure()

    /** The host requires sign-in but offers no `basic` provider, only a browser one. */
    object BrowserSignIn : BotFailure()

    /** The gateway upgrade was refused with this HTTP status after a good sign-in. */
    data class UpgradeRefused(val status: Int) : BotFailure()

    /** `/api/status` reported this release, older than the supported minimum. */
    data class Outdated(val version: String) : BotFailure()
}

/** What to check when a Bot connection fails, for the connection form, the inbox and a chat. */
object BotConnectionAdvice {
    /**
     * Whether a screen that lost its connection to [error] should reconnect on its
     * backoff. False for a refusal the user has to act on: sign-in, an unsupported host
     * or address, an address that now reaches a different host or is not a dashboard, an
     * access proxy's own sign-in, browser sign-in only, a refused gateway upgrade, an
     * outdated host, and any other permanent HTTP client error. Server errors, rate
     * limits and JSON-RPC faults other than "method missing" are the retry loop's problem.
     */
    fun isRetryable(error: Throwable): Boolean = when (error) {
        is BotFailure -> when (error) {
            is BotFailure.Unsupported, is BotFailure.WrongIdentity, is BotFailure.DifferentHost,
            is BotFailure.InvalidAddress, is BotFailure.NotDashboard, is BotFailure.Blocked,
            is BotFailure.BrowserSignIn, is BotFailure.UpgradeRefused, is BotFailure.Outdated -> false

            is BotFailure.Rejected -> when {
                error.code == -32601 || error.code == 4090 || error.code == 4130 -> false
                error.code == 408 || error.code == 429 -> true
                else -> error.code !in 400..499
            }

            else -> true
        }

        else -> true
    }
}
