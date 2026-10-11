package com.iaeluk.hermex.core.network.hermes

/**
 * The hermes-agent releases this app signs in to. [HermesConnection] refuses a host
 * older than [minimumVersion] before any password goes out; anything newer, tested or
 * not, connects without a warning.
 */
object HermesCompatibility {
    /** The oldest release that publishes the gateway contract this app is built on. */
    const val minimumVersion = "0.21.3"

    /** The release this app was validated against. Mirrors line 2 of HERMES_AGENT_TESTED_SHA. */
    const val testedVersion = "0.21.5"

    /**
     * Whether a host reporting [version] may sign in. A missing or unreadable version
     * counts as supported: the pin always reports one, so its absence means a proxy or
     * a fork, not an old host.
     */
    fun isSupported(version: String?): Boolean {
        val reported = release(version) ?: return true
        val minimum = release(minimumVersion) ?: return true
        return !reported.lexicographicallyPrecedes(minimum)
    }

    /**
     * The leading MAJOR.MINOR.PATCH of [version] as numbers, so a canary
     * ("0.21.4+canary...") reads as its base release. Null when missing, partial or
     * unreadable.
     */
    fun release(version: String?): List<Int>? {
        if (version == null) return null
        val prefix = version.takeWhile { it.isDigit() || it == '.' }
        val numbers = prefix.split('.').mapNotNull { it.toIntOrNull() }
        return if (numbers.size >= 3) numbers.take(3) else null
    }

    private fun List<Int>.lexicographicallyPrecedes(other: List<Int>): Boolean {
        for (index in 0 until minOf(size, other.size)) {
            if (this[index] != other[index]) return this[index] < other[index]
        }
        return size < other.size
    }
}
