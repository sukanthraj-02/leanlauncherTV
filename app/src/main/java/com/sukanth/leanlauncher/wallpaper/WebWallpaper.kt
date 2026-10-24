package com.sukanth.leanlauncher.wallpaper

import java.net.URI

/**
 * Finds image addresses in a user-supplied wallpaper page, feed or JSON response.
 *
 * There is deliberately no HTML parser dependency: the launcher only needs absolute
 * image URLs, and a tolerant scan of recognised tags plus quoted links covers common
 * gallery pages, RSS enclosures and public wallpaper APIs. Every resolved address is
 * restricted to http(s), so a page cannot make the app open a local file.
 */
internal object WebWallpaper {

    private const val MAX_URLS = 80

    private val IMAGE_EXTENSION =
        Regex("""\.(?:jpe?g|png|webp|bmp|gif|avif)(?:$|[?#])""", RegexOption.IGNORE_CASE)
    private val TAG_ATTRIBUTE = Regex(
        """([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
    )
    private val META_TAG =
        Regex("""<meta\b(?:[^>"']|"[^"]*"|'[^']*')*>""", RegexOption.IGNORE_CASE)
    private val IMG_TAG =
        Regex("""<img\b(?:[^>"']|"[^"]*"|'[^']*')*>""", RegexOption.IGNORE_CASE)
    private val SOURCE_TAG =
        Regex("""<source\b(?:[^>"']|"[^"]*"|'[^']*')*>""", RegexOption.IGNORE_CASE)
    private val ANCHOR_TAG =
        Regex("""<a\b(?:[^>"']|"[^"]*"|'[^']*')*>""", RegexOption.IGNORE_CASE)
    private val BACKGROUND_URL = Regex(
        """background-image\s*:\s*url\(\s*['"]?([^'")]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val QUOTED_IMAGE_URL = Regex(
        """["'](https?://[^"'<>\\\s]+?\.(?:jpe?g|png|webp|bmp|gif|avif)(?:\?[^"'<>\\\s]*)?)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val SRCSET_DESCRIPTOR = Regex("""^([0-9.]+)[wx]$""", RegexOption.IGNORE_CASE)
    private val EXCLUDED_PATH = Regex(
        """(?:^|[/_.-])(?:logo|icon|avatar|sprite|emoji|spacer|pixel|blank)(?:[/_.-]|$)""",
        RegexOption.IGNORE_CASE,
    )

    private const val RANK_META = 0
    private const val RANK_ANCHOR = 1
    private const val RANK_LAZY = 2
    private const val RANK_IMAGE = 3
    private const val RANK_BACKGROUND = 4
    private const val RANK_QUOTED = 5

    private val META_IMAGE_KEYS = setOf(
        "og:image",
        "og:image:url",
        "og:image:secure_url",
        "twitter:image",
        "twitter:image:src",
    )

    /** Accepts a bare host too, so a user does not have to type `https://` on a TV remote. */
    fun normalizePageUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> return null
            else -> "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme.replace(" ", "%20")) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        if (!uri.userInfo.isNullOrBlank()) return null
        return uri.toString()
    }

    /** Returns the best image addresses in page order, best first within each source. */
    fun extractImageUrls(content: String, baseUrl: String): List<String> {
        if (content.isEmpty()) return emptyList()
        // JSON APIs commonly escape forward slashes (`https:\/\/host\/image.jpg`).
        val text = content
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)

        val found = LinkedHashMap<String, Int>()

        fun add(raw: String?, rank: Int, trusted: Boolean) {
            val cleaned = raw?.let(::unescapeHtml)?.trim()?.trim('\'', '"') ?: return
            if (cleaned.isEmpty()) return
            if (cleaned.startsWith("data:", ignoreCase = true)) return
            if (cleaned.startsWith("javascript:", ignoreCase = true)) return
            val absolute = resolve(baseUrl, cleaned) ?: return
            if (!trusted && !looksLikeImageUrl(absolute)) return
            if (!trusted && EXCLUDED_PATH.containsMatchIn(absolute)) return
            val key = absolute.substringBefore('#')
            if (key.isEmpty()) return
            val existing = found[key]
            if (existing == null || rank < existing) found[key] = rank
        }

        for (tag in META_TAG.findAll(text)) {
            val attributes = attributes(tag.value)
            val key = (attributes["property"] ?: attributes["name"])?.lowercase()
            if (key in META_IMAGE_KEYS) add(attributes["content"], RANK_META, trusted = true)
        }

        for (tag in ANCHOR_TAG.findAll(text)) {
            add(attributes(tag.value)["href"], RANK_ANCHOR, trusted = false)
        }

        for (tag in IMG_TAG.findAll(text)) {
            val attributes = attributes(tag.value)
            val tiny = isTiny(attributes)
            val penalty = if (tiny) 2 else 0
            for (name in LAZY_ATTRIBUTES) {
                add(attributes[name], RANK_LAZY + penalty, trusted = true)
            }
            add(attributes["src"], RANK_IMAGE + penalty, trusted = true)
            add(bestFromSrcset(attributes["srcset"]), RANK_IMAGE + penalty, trusted = true)
            add(bestFromSrcset(attributes["data-srcset"]), RANK_LAZY + penalty, trusted = true)
        }

        for (tag in SOURCE_TAG.findAll(text)) {
            val attributes = attributes(tag.value)
            add(attributes["src"], RANK_IMAGE, trusted = true)
            add(bestFromSrcset(attributes["srcset"]), RANK_IMAGE, trusted = true)
        }

        for (match in BACKGROUND_URL.findAll(text)) {
            add(match.groupValues[1], RANK_BACKGROUND, trusted = false)
        }

        for (match in QUOTED_IMAGE_URL.findAll(text)) {
            add(match.groupValues[1], RANK_QUOTED, trusted = false)
        }

        return found.entries
            .sortedBy { it.value }
            .map { it.key }
            .take(MAX_URLS)
    }

    fun looksLikeImageUrl(url: String): Boolean = IMAGE_EXTENSION.containsMatchIn(url)

    fun isImageContentType(value: String?): Boolean =
        value?.trim()?.startsWith("image/", ignoreCase = true) == true

    fun isMarkupContentType(value: String?): Boolean {
        val type = value?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return type.startsWith("text/") ||
            "json" in type ||
            "xml" in type ||
            "rss" in type ||
            "atom" in type
    }

    private fun attributes(tag: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (match in TAG_ATTRIBUTE.findAll(tag)) {
            val value = match.groupValues[2]
                .ifEmpty { match.groupValues[3] }
                .ifEmpty { match.groupValues[4] }
            result[match.groupValues[1].lowercase()] = value
        }
        return result
    }

    private fun bestFromSrcset(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val entries = value.split(',').mapNotNull { part ->
            val pieces = part.trim().split(Regex("""\s+"""), limit = 2)
            val url = pieces.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val weight = pieces.getOrNull(1)
                ?.let { SRCSET_DESCRIPTOR.find(it.trim())?.groupValues?.get(1)?.toDoubleOrNull() }
                ?: 0.0
            weight to url
        }
        return entries.maxByOrNull { it.first }?.second
    }

    private fun isTiny(attributes: Map<String, String>): Boolean {
        val width = attributes["width"]?.trim()?.removeSuffix("px")?.toIntOrNull()
        val height = attributes["height"]?.trim()?.removeSuffix("px")?.toIntOrNull()
        return (width != null && width in 1..2) || (height != null && height in 1..2)
    }

    private fun resolve(baseUrl: String, raw: String): String? {
        val cleaned = raw.trim().replace(" ", "%20")
        val direct = runCatching { URI(cleaned) }.getOrNull() ?: return null
        if (direct.scheme != null) {
            if (direct.scheme.lowercase() !in setOf("http", "https")) return null
            if (direct.host.isNullOrBlank()) return null
            return direct.toString()
        }
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val resolved = runCatching { base.resolve(direct).toString() }.getOrNull() ?: return null
        return resolved.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&#38;", "&")
        .replace("&#x26;", "&", ignoreCase = true)
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&#x2F;", "/", ignoreCase = true)
        .replace("&#47;", "/")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private val LAZY_ATTRIBUTES = listOf(
        "data-src",
        "data-original",
        "data-lazy-src",
        "data-full",
        "data-image",
    )
}
