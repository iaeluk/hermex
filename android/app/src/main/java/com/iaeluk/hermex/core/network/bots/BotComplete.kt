package com.iaeluk.hermex.core.network.bots

/**
 * Slash/path completion results and inbox/chat search. Shapes stay tolerant:
 * hosts return lists under several keys across releases.
 */
object BotComplete {
    /** One completion candidate the UI can insert. */
    data class Candidate(val insert: String, val display: String = insert)

    /** Candidates from a `complete.slash` / `complete.path` reply. */
    fun candidates(reply: BotJson?): List<Candidate> {
        val list = reply?.let { it["items"].list ?: it["candidates"].list ?: it["results"].list ?: it.list }
            .orEmpty()
        return list.mapNotNull { item ->
            val insert = item["insert"].text ?: item["value"].text ?: item["name"].text
                ?: item["text"].text ?: item.text
            val clean = insert?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val display = item["display"].text ?: item["label"].text ?: clean
            Candidate(clean, display.trim().takeIf { it.isNotEmpty() } ?: clean)
        }
    }
}

/** Case-insensitive substring filter for the inbox and transcript search. */
fun filterInbox(rows: List<BotInboxRow>, query: String): List<BotInboxRow> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return rows
    return rows.filter { row ->
        row.profile.name.lowercase().contains(q) ||
            row.profile.id.lowercase().contains(q) ||
            row.profile.title?.lowercase()?.contains(q) == true ||
            row.profile.preview?.lowercase()?.contains(q) == true
    }
}

fun filterMessages(messages: List<com.iaeluk.hermex.ui.bots.BotChatMessage>, query: String): List<com.iaeluk.hermex.ui.bots.BotChatMessage> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return messages
    return messages.filter { it.text.lowercase().contains(q) }
}
