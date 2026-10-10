package com.iaeluk.hermex.ui.kanban

import com.iaeluk.hermex.core.network.ApiError

internal fun isMissingKanbanCapability(error: Throwable): Boolean {
    val http = error as? ApiError.Http ?: return false
    if (http.statusCode == 405) return true
    if (http.statusCode != 404) return false

    val body = http.body?.lowercase().orEmpty()
    return "unknown kanban endpoint" in body ||
        "kanban endpoint not found" in body ||
        "unsupported kanban endpoint" in body
}
