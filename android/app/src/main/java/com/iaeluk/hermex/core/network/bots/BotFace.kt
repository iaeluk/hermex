package com.iaeluk.hermex.core.network.bots

/** Bot faces: 8 shapes, 11 colors, 16 expressions, ported from iOS. */
object BotFace {
    val shapes = listOf("circle", "blob", "squircle", "pill", "triangle", "hexagon", "cloud", "drop")

    data class FaceColor(val hex: String, val name: String)
    val colors = listOf(
        FaceColor("#ef4444", "Red"), FaceColor("#f97316", "Orange"),
        FaceColor("#f59e0b", "Amber"), FaceColor("#22c55e", "Green"),
        FaceColor("#14b8a6", "Teal"), FaceColor("#38bdf8", "Blue"),
        FaceColor("#6366f1", "Indigo"), FaceColor("#8b5cf6", "Purple"),
        FaceColor("#ec4899", "Pink"), FaceColor("#8e8e93", "Gray"),
        FaceColor("#111827", "Black"),
    )

    val expressions = listOf(
        "neutral", "attentive", "surprised", "excited", "happy", "laughing", "angry", "sad",
        "scared", "wary", "confused", "curious", "proud", "shy", "bored", "sleepy",
    )

    /** Unknown or missing values read as neutral rather than failing. */
    fun resolveExpression(raw: String?): String =
        raw?.trim()?.lowercase()?.takeIf { it in expressions } ?: "neutral"

    fun isValidShape(raw: String?): Boolean = raw in shapes
    fun isValidColor(raw: String?): Boolean = colors.any { it.hex.equals(raw, ignoreCase = true) }
}

data class BotAppearance(
    val shape: String = "circle",
    val colorHex: String = "#38bdf8",
    val expression: String = "neutral",
) {
    fun validated(): BotAppearance = copy(
        shape = shape.takeIf { BotFace.isValidShape(it) } ?: "circle",
        colorHex = colorHex.takeIf { BotFace.isValidColor(it) } ?: "#38bdf8",
        expression = BotFace.resolveExpression(expression),
    )
}
