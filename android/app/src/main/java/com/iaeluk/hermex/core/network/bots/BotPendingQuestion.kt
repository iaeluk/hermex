package com.iaeluk.hermex.core.network.bots

/**
 * One pending clarify request: single-question or batch shape. Batch locks one
 * answer per question id (`clarify.lock`); single answers free text or a choice.
 */
data class BotPendingQuestion(
    val requestId: String,
    val envelopeId: String,
    val questions: List<BotQuestion> = emptyList(),
) {
    companion object {
        fun fromEnvelope(envelope: BotJson?): BotPendingQuestion? {
            val id = envelope["id"].text?.takeIf { it.isNotEmpty() } ?: return null
            val params = envelope["params"] ?: return null
            val requestId = params["request_id"].text?.takeIf { it.isNotEmpty() } ?: id
            val locked = params["answers"]
            val rows = params["questions"].list
            if (!rows.isNullOrEmpty()) {
                val parsed = rows.mapNotNull { row ->
                    val qid = row["qid"].text ?: row["id"].text ?: return@mapNotNull null
                    if (qid.isEmpty()) return@mapNotNull null
                    val prompt = row["question"].text?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    BotQuestion(
                        wireId = qid,
                        prompt = prompt,
                        choices = choices(row["choices"]),
                        allowsMultiple = row["multi_select"].flag == true,
                        lockedAnswer = locked[qid].text,
                    )
                }
                if (parsed.isEmpty()) return null
                return BotPendingQuestion(requestId, id, parsed)
            }
            val prompt = params["question"].text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return BotPendingQuestion(
                requestId, id,
                listOf(
                    BotQuestion(
                        wireId = null, prompt = prompt,
                        choices = choices(params["choices"]),
                        allowsMultiple = params["multi_select"].flag == true,
                        lockedAnswer = null,
                    )
                ),
            )
        }

        private fun choices(json: BotJson?): List<BotQuestionChoice> =
            json.list.orEmpty().mapIndexedNotNull { index, row ->
                val label = row.text?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                BotQuestionChoice(index, label)
            }
    }

    val isBatch: Boolean get() = questions.firstOrNull()?.wireId != null
    val unansweredCount: Int get() = questions.count { it.lockedAnswer == null }
}

data class BotQuestion(
    val wireId: String?,
    val prompt: String,
    val choices: List<BotQuestionChoice> = emptyList(),
    val allowsMultiple: Boolean = false,
    val lockedAnswer: String? = null,
) {
    val isAnswered: Boolean get() = lockedAnswer != null
}

data class BotQuestionChoice(val id: Int, val wireLabel: String) {
    val isRecommended: Boolean get() =
        wireLabel.trim().lowercase().endsWith("(recommended)")
    val label: String get() =
        if (isRecommended) wireLabel.trim().dropLast("(Recommended)".length).trim()
        else wireLabel.trim()
}
