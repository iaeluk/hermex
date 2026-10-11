package com.iaeluk.hermex.core.network.bots

/** A value only the person can supply: sudo password or a named secret. */
data class BotPendingCredential(
    val kind: String,
    val requestId: String,
    val envelopeId: String,
    val envVar: String? = null,
    val prompt: String? = null,
) {
    companion object {
        val KINDS = setOf("sudo", "secret")

        fun fromEnvelope(method: String?, envelope: BotJson?): BotPendingCredential? {
            if (method !in KINDS) return null
            val id = envelope["id"].text?.takeIf { it.isNotEmpty() } ?: return null
            val params = envelope["params"] ?: return null
            val requestId = params["request_id"].text?.takeIf { it.isNotEmpty() } ?: id
            return BotPendingCredential(
                kind = method!!,
                requestId = requestId,
                envelopeId = id,
                envVar = params["env_var"].text?.trim()?.takeIf { it.isNotEmpty() },
                prompt = params["prompt"].text?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    val title: String get() =
        if (kind == "sudo") "Administrator password needed" else "Secret needed"
    val detail: String get() =
        if (kind == "sudo") "A command on this Mac needs an administrator password to run."
        else prompt ?: "This bot needs a secret value to carry on."
}
