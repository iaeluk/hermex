package com.iaeluk.hermex.core.network.bots

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Direct Hermes payloads deliberately stay separate from webui endpoint models.
 * Accessors tolerate absent and future fields; required capabilities are checked at use.
 */
typealias BotJson = JsonElement

/** Parses one direct-Hermes reply body; malformed JSON answers null. */
fun parseBotJson(text: String): BotJson? =
    runCatching { BotJsonParser.parseToJsonElement(text) }.getOrNull()

private val BotJsonParser: Json = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

/** `json["key"]` reads objects and answers null for anything else, so chains never throw. */
operator fun BotJson?.get(key: String): BotJson? = (this as? JsonObject)?.get(key)

/** The string value, or null for any other type. */
val BotJson?.text: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** True/false only; a string that spells one does not count. */
val BotJson?.flag: Boolean? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** The number value, or null for any other type. */
val BotJson?.number: Double? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** The number as an exact integer, or null when fractional or another type. */
val BotJson?.integer: Int? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

val BotJson?.list: List<BotJson>? get() = this as? JsonArray

val BotJson?.fields: Map<String, BotJson>? get() = this as? JsonObject

/**
 * The public `GET /api/status` fields the connection screen shows. Every field is optional
 * because hosts add, omit and rename them between releases.
 */
data class BotHostStatus(
    val version: String? = null,
    val gatewayRunning: Boolean? = null,
    val gatewayState: String? = null,
    val gatewayExitReason: String? = null,
    val heartbeatStale: Double? = null,
    val platformsConnected: Int? = null,
    val platformsConfigured: Int? = null,
) {
    constructor(json: BotJson?) : this(
        version = json["version"].text,
        gatewayRunning = json["gateway_running"].flag,
        gatewayState = json["gateway_state"].text,
        gatewayExitReason = json["gateway_exit_reason"].text,
        heartbeatStale = json["gateway_heartbeat_stale_s"].number,
        platformsConnected = json["components"]["platforms"]["connected"].integer,
        platformsConfigured = json["components"]["platforms"]["configured"].integer,
    )
}
