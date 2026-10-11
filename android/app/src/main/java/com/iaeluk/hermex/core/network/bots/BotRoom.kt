package com.iaeluk.hermex.core.network.bots

/** Minimal room row: id, name, members, last sequence. */
data class BotRoom(
    val id: String,
    val name: String,
    val members: List<String> = emptyList(),
    val lastSeq: Int = 0,
) {
    companion object {
        fun validId(id: String): Boolean = id.trim().isNotEmpty()

        fun listIn(reply: BotJson?): List<BotRoom> {
            val list = reply?.let { it["rooms"].list ?: it["items"].list ?: it.list }.orEmpty()
            return list.mapNotNull { item ->
                val id = item["room_id"].text ?: item["id"].text ?: return@mapNotNull null
                if (id.isBlank()) return@mapNotNull null
                BotRoom(
                    id = id,
                    name = item["name"].text ?: id,
                    members = item["members"].list?.mapNotNull {
                        it["profile"].text ?: it["handle"].text ?: it.text
                    }.orEmpty(),
                    lastSeq = item["last_seq"].integer ?: item["seq"].integer ?: 0,
                )
            }
        }

        /** 2-6 members, like the iOS create sheet. */
        fun validateMembers(members: List<String>): String? {
            val clean = members.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (clean.size < 2) return "Pick at least 2 members."
            if (clean.size > 6) return "Rooms hold up to 6 members."
            return null
        }
    }
}

data class BotRoomMessage(
    val seq: Int,
    val author: String,
    val text: String,
) {
    companion object {
        fun listIn(reply: BotJson?): List<BotRoomMessage> {
            val list = reply?.let { it["events"].list ?: it["log"].list ?: it["items"].list ?: it.list }.orEmpty()
            return list.mapNotNull { item ->
                val text = item["payload"]?.get("text")?.text ?: item["text"].text ?: return@mapNotNull null
                BotRoomMessage(
                    seq = item["seq"].integer ?: 0,
                    author = item["author"].text ?: item["member_id"].text ?: "",
                    text = text,
                )
            }
        }
    }
}
