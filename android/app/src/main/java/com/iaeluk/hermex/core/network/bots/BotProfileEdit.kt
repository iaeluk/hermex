package com.iaeluk.hermex.core.network.bots

/** Create/edit validation: names are required, everything else optional. */
object BotProfileEdit {
    fun validateName(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return "Give the bot a name."
        if (clean.length > 64) return "Keep the name under 64 characters."
        return null
    }

    fun cleanDescription(description: String): String? =
        description.trim().takeIf { it.isNotEmpty() }

    fun cleanSoul(soul: String): String? = soul.trim().takeIf { it.isNotEmpty() }
}
