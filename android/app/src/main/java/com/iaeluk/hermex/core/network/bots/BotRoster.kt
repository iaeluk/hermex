package com.iaeluk.hermex.core.network.bots

/**
 * The inbox's roster models, ported from the iOS port's `BotProfile` and
 * `HermesSessionRow`: tolerant reads, every field but the ids optional.
 */

enum class BotLiveStatus {
    IDLE,
    WORKING,
    WAITING,
}

/** One bot on the roster: a profile with its canonical chat when it has one. */
data class BotProfile(
    val id: String,
    val name: String,
    val title: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val preview: String? = null,
    val lastActiveEpochSeconds: Double? = null,
    val canonicalID: String? = null,
    val canonicalTipID: String? = null,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val sectionID: String? = null,
    val sectionName: String? = null,
    val hasAvatar: Boolean = false,
    val unread: Boolean = false,
) {
    companion object {
        fun from(json: BotJson?): BotProfile? {
            val id = json["name"].text?.takeIf { it.isNotEmpty() }
                ?: json["id"].text?.takeIf { it.isNotEmpty() }
                ?: return null
            val name = json["name"].text?.takeIf { it.isNotEmpty() } ?: id
            return BotProfile(
                id = id,
                name = name,
                title = json["title"].text,
                displayName = json["display_name"].text,
                description = json["description"].text,
                preview = json["preview"].text,
                lastActiveEpochSeconds = json["last_active"].number,
                canonicalID = json["canonical_session"]?.let { it["id"].text }
                    ?: json["canonical_session_id"].text,
                canonicalTipID = json["resolved_id"].text,
                pinned = json["pinned"].flag == true,
                hidden = json["hidden"].flag == true,
                sectionID = json["section_id"].text,
                sectionName = json["section_name"].text,
                hasAvatar = json["has_avatar"].flag == true,
                unread = json["unread"].flag == true,
            )
        }

        fun listIn(json: BotJson?): List<BotProfile> =
            json["profiles"].list?.mapNotNull { from(it) }
                ?: json.list?.mapNotNull { from(it) }
                ?: emptyList()
    }

    /** The name the inbox row shows. */
    fun rowTitle(): String = displayName?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: name
}

/** One session row of a profile's list. Identity is the lineage root when it has one. */
data class BotSessionRow(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val lastActiveEpochSeconds: Double? = null,
    val startedAtEpochSeconds: Double? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val unread: Boolean? = null,
    val hidden: Boolean? = null,
    val model: String? = null,
    val messageCount: Int? = null,
    val profile: String? = null,
    val lineageRootID: String? = null,
) {
    val identity: String get() = lineageRootID ?: id

    /** A bot's canonical chat: hidden, under its exact title. */
    val isBotChat: Boolean get() = hidden == true && title == BOT_CHAT_TITLE

    companion object {
        const val BOT_CHAT_TITLE = "Bot Chat"

        fun from(json: BotJson?): BotSessionRow? {
            val id = json["id"].text?.takeIf { it.isNotEmpty() } ?: return null
            fun finite(value: Double?): Double? = value?.takeIf { it.isFinite() }
            val root = json["_lineage_root_id"].text?.takeIf { it.isNotEmpty() }
            return BotSessionRow(
                id = id,
                title = json["title"].text,
                preview = json["preview"].text,
                lastActiveEpochSeconds = finite(json["last_active"].number),
                startedAtEpochSeconds = finite(json["started_at"].number),
                pinned = json["pinned"].flag,
                archived = json["archived"].flag,
                unread = json["unread"].flag,
                hidden = json["hidden"].flag,
                model = json["model"].text,
                messageCount = json["message_count"].integer,
                profile = json["profile"].text,
                lineageRootID = root,
            )
        }

        fun listIn(json: BotJson?): List<BotSessionRow> =
            json["sessions"].list?.mapNotNull { from(it) } ?: emptyList()
    }
}

/** One row the inbox shows, in its display order. */
data class BotInboxRow(
    val profile: BotProfile,
    val status: BotLiveStatus = BotLiveStatus.IDLE,
    val unread: Boolean = false,
    val lastActiveEpochSeconds: Double? = null,
) {
    companion object {
        /**
         * The order inside every group: waiting, then working, then unread, then newest,
         * undated last and ties by id.
         */
        fun sorted(rows: List<BotInboxRow>): List<BotInboxRow> = rows.sortedWith(
            compareBy<BotInboxRow> {
                when (it.status) {
                    BotLiveStatus.WAITING -> 0
                    BotLiveStatus.WORKING -> 1
                    BotLiveStatus.IDLE -> 2
                }
            }.thenByDescending { it.unread }
                .thenByDescending { it.lastActiveEpochSeconds ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.profile.id },
        )
    }
}
