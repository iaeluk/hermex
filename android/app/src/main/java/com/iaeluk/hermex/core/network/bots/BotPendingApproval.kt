package com.iaeluk.hermex.core.network.bots

/**
 * One pending dangerous-command approval. The host precomputes `choices` from
 * its own policy, so the phone offers exactly what the host offered.
 */
data class BotPendingApproval(
    /** The queue request id `approval.respond` answers. */
    val requestId: String,
    /** The server-request envelope id, for matching withdrawals. */
    val envelopeId: String,
    /** The command as the host redacted it. */
    val command: String? = null,
    /** Why the host flagged this action. */
    val consequence: String? = null,
    /** Server order, `once` first and `deny` last. Never empty. */
    val choices: List<String> = listOf("once", "deny"),
    /** What Allow session / Always allow would allowlist. */
    val patternKeys: List<String> = emptyList(),
    /** The gated tool, when the host names it. */
    val toolName: String? = null,
) {
    companion object {
        /** The host's wire vocabulary for `approval.respond`'s `choice`. */
        val CHOICES = setOf("once", "session", "always", "deny")

        /**
         * Parses one `approval` server-request envelope (id + method + params).
         * Nil when it carries no usable request id: without one the phone
         * cannot address `approval.respond` and must not guess FIFO order.
         */
        fun fromEnvelope(envelope: BotJson?): BotPendingApproval? {
            val id = envelope["id"].text?.takeIf { it.isNotEmpty() } ?: return null
            val params = envelope["params"] ?: return null
            val requestId = params["request_id"].text?.takeIf { it.isNotEmpty() } ?: return null
            val offered = params["choices"].list
                ?.mapNotNull { it.text?.takeIf { choice -> choice in CHOICES } }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("once", "deny")
            return BotPendingApproval(
                requestId = requestId,
                envelopeId = id,
                command = params["command"].text,
                consequence = params["description"].text ?: params["consequence"].text,
                choices = offered,
                patternKeys = params["pattern_keys"].list?.mapNotNull { it.text }
                    ?: params["pattern_key"].text?.let { listOf(it) }.orEmpty(),
                toolName = params["tool"].text ?: params["tool_name"].text,
            )
        }
    }

    fun choiceLabel(choice: String): String = when (choice) {
        "once" -> "Allow once"
        "session" -> "Allow session"
        "always" -> "Always allow"
        "deny" -> "Deny"
        else -> choice
    }
}

/** The host's wire vocabulary, shared by the RPC admission. */
object BotApprovalChoice {
    val values = setOf("once", "session", "always", "deny")
}
