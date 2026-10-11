package com.iaeluk.hermex.core.network.bots

/** A request only Hermes Desktop can answer; the phone reports the wait. */
data class BotPendingDesktop(
    val kind: String,
    val requestId: String,
    val envelopeId: String,
) {
    companion object {
        val KINDS = setOf("tour", "terminal.read", "window.read", "preview.read", "preview.act")

        fun fromEnvelope(method: String?, envelope: BotJson?): BotPendingDesktop? {
            if (method !in KINDS) return null
            val id = envelope["id"].text?.takeIf { it.isNotEmpty() } ?: return null
            val params = envelope["params"]
            val requestId = params?.get("request_id")?.text?.takeIf { it.isNotEmpty() } ?: id
            return BotPendingDesktop(method!!, requestId, id)
        }
    }

    val title: String get() = when (kind) {
        "terminal.read" -> "This bot is reading a terminal on the Mac."
        "window.read" -> "This bot is reading a window on the Mac."
        "preview.read" -> "This bot is reading the preview pane on the Mac."
        "preview.act" -> "This bot is using the preview pane on the Mac."
        else -> "This bot is running a tour in Hermes Desktop."
    }
}
