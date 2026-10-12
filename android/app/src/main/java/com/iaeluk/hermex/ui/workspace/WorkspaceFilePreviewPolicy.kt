package com.iaeluk.hermex.ui.workspace

object WorkspaceFilePreviewPolicy {
    private val rasterImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "ico", "bmp")
    private val unsupportedBinaryExtensions = setOf(
        "7z",
        "a",
        "aiff",
        "avi",
        "bin",
        "bz2",
        "class",
        "db",
        "dmg",
        "doc",
        "docx",
        "dylib",
        "exe",
        "flac",
        "gz",
        "jar",
        "m4a",
        "mov",
        "mp3",
        "mp4",
        "o",
        "pdf",
        "pkg",
        "ppt",
        "pptx",
        "pyc",
        "rar",
        "sqlite",
        "svg",
        "tar",
        "tgz",
        "wav",
        "xls",
        "xlsx",
        "xz",
        "zip",
    )

    fun extension(path: String?): String =
        path.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()

    fun displayName(path: String?): String =
        path.orEmpty()
            .trim()
            .trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "Hermex File" }

    fun isRasterImage(path: String?): Boolean = extension(path) in rasterImageExtensions

    fun isMarkdown(path: String?): Boolean = extension(path) in setOf("md", "markdown", "mdown", "mkd")

    fun isKnownUnsupportedBinary(path: String?): Boolean = extension(path) in unsupportedBinaryExtensions

    fun shouldLoadRawPreview(path: String?): Boolean = isRasterImage(path)

    const val MAXIMUM_EXTERNAL_OPEN_BYTES = 25L * 1024 * 1024

    fun shouldOpenExternally(sizeBytes: Long): Boolean = sizeBytes > MAXIMUM_EXTERNAL_OPEN_BYTES

    fun canOpenExternally(sizeBytes: Long): Boolean = sizeBytes <= MAXIMUM_EXTERNAL_OPEN_BYTES

    fun codeLanguage(path: String?): String? = when (extension(path)) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js", "mjs", "cjs", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "rb" -> "ruby"
        "go" -> "go"
        "rs" -> "rust"
        "c", "h" -> "c"
        "cpp", "cc", "hpp" -> "cpp"
        "cs" -> "csharp"
        "swift" -> "swift"
        "css" -> "css"
        "sql" -> "sql"
        "json" -> "json"
        "xml", "html", "htm" -> "markup"
        "yaml", "yml" -> "yaml"
        "md", "markdown", "mdown", "mkd" -> "markdown"
        "sh" -> "bash"
        "toml", "ini", "gradle", "properties", "log", "txt", "diff", "patch" -> null
        else -> null
    }

    fun fencedSourceBlock(content: String, language: String?): String {
        var fence = "```"
        while (content.lines().any { it.trimStart().startsWith(fence) }) fence += "`"
        return "$fence${language.orEmpty()}\n$content\n$fence"
    }

    fun badgeLabel(path: String?, isDirectory: Boolean = false): String {
        if (isDirectory) return "DIR"
        return when (val extension = extension(path)) {
            "json" -> "JSON"
            "md", "markdown" -> "MD"
            "pdf" -> "PDF"
            "zip", "tar", "gz", "tgz", "7z", "rar" -> "ZIP"
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "svg" -> "IMG"
            "mp3", "m4a", "wav", "aac", "flac", "ogg", "opus" -> "AUD"
            "mp4", "mov", "m4v", "webm", "mkv", "avi" -> "VID"
            "kt", "kts", "swift", "java", "js", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c", "h", "cpp", "cs" -> "CODE"
            "html", "htm", "css", "xml", "yaml", "yml" -> "DOC"
            "txt", "log" -> "TXT"
            else -> extension.uppercase().take(4).ifBlank { "FILE" }
        }
    }

    fun kindLabel(path: String?, isDirectory: Boolean = false): String {
        if (isDirectory) return "Folder"
        return when (badgeLabel(path)) {
            "IMG" -> "Image"
            "AUD" -> "Audio"
            "VID" -> "Video"
            "ZIP" -> "Archive"
            "CODE" -> "Source code"
            "DOC" -> "Document"
            "FILE" -> "File"
            else -> badgeLabel(path)
        }
    }

    fun mimeType(path: String?, isText: Boolean = false): String =
        if (isText) {
            "text/plain"
        } else {
            when (extension(path)) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "ico" -> "image/x-icon"
                "pdf" -> "application/pdf"
                "svg" -> "image/svg+xml"
                "zip" -> "application/zip"
                "json" -> "application/json"
                "md" -> "text/markdown"
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js", "mjs", "cjs" -> "text/javascript"
                else -> "application/octet-stream"
            }
        }

    fun lineCount(content: String?): Int? {
        val text = content ?: return null
        if (text.isEmpty()) return 0
        return text.lineSequence().count()
    }
}
