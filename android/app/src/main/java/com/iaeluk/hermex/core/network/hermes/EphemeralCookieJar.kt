package com.iaeluk.hermex.core.network.hermes

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The per-connection cookie jar: in memory only, like the iOS port's ephemeral URL
 * session. Nothing survives the connection, and two connections to the same host never
 * share a session cookie. One jar backs both clients of a connection, so a
 * provisioning-deadline sign-in stores the same session the standard calls carry.
 */
class EphemeralCookieJar : CookieJar {
    private val lock = Any()
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            this.cookies.removeAll { stored ->
                cookies.any { it.name == stored.name && it.domain == stored.domain && it.path == stored.path }
            }
            this.cookies.addAll(cookies.filter { !it.persistent || it.expiresAt >= now })
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.persistent && it.expiresAt < now }
        cookies.filter { it.matches(url) }
    }

    fun clear() {
        synchronized(lock) { cookies.clear() }
    }
}
