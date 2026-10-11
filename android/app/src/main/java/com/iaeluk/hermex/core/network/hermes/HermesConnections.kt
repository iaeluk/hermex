package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotConnection

/**
 * Gives every consumer of a configured server's saved Bot connection the same
 * [HermesConnection], and with it the same gateway socket. It keeps one entry, keyed by
 * server and connection UUID, so the connection lives only while some consumer holds it.
 * A request for another server or UUID, or for the same UUID with a new address,
 * account, password or headers (the connection form can keep a UUID across those),
 * retires the old connection first, so no cookie, sign-in, socket or late reply crosses
 * servers, accounts, credentials or headers.
 */
class HermesConnections {
    private val lock = Any()
    private var serverId: String? = null
    private var current: HermesConnection? = null

    /**
     * Told which configured server's saved username or password the host refused at the
     * login step, so the app can sign that server out.
     */
    var onSignInRejected: ((String) -> Unit)? = null

    /** Makes each new connection's gateway options; tests script the socket with it. */
    var gatewayOptions: () -> HermesGateway.Options = { HermesGateway.Options() }

    fun connectionFor(serverId: String, saved: BotConnection): HermesConnection = synchronized(lock) {
        val existing = current
        if (existing != null && this.serverId == serverId && existing.adopt(saved)) return existing
        existing?.retire()
        val fresh = HermesConnection(saved, gatewayOptions())
        fresh.onLoginRejected = { onSignInRejected?.invoke(serverId) }
        current = fresh
        this.serverId = serverId
        return fresh
    }

    /** Closes the current gateway socket silently, because the app went to the background. */
    fun closeForBackground() {
        synchronized(lock) { current }?.closeGatewayForBackground()
    }

    /**
     * Ends the server's gateway socket as lost because its dashboard restarted on an
     * update, so every Bot screen on it reconnects now. Nothing happens for another server.
     */
    fun reconnectGateway(serverId: String) {
        val existing = synchronized(lock) { current?.takeIf { this.serverId == serverId } } ?: return
        existing.dropGatewaySocket()
    }

    /**
     * Retires the server's connection now unless `saved`, its newly saved record, is
     * still that connection, so a sign-in in flight stores nothing and no request is
     * sent or resent after the change.
     */
    fun retire(serverId: String, unlessStill: BotConnection? = null) {
        val existing = synchronized(lock) {
            val current = current?.takeIf { this.serverId == serverId } ?: return
            if (unlessStill != null && current.adopt(unlessStill)) return
            this.current = null
            this.serverId = null
            current
        }
        existing.retire()
    }
}
