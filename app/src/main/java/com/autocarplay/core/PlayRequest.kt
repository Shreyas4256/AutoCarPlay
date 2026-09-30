package com.autocarplay.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** How a piece of content is shown on the car screen. */
enum class SourceKind {
    /** Played by the built-in video player (files, mp4/mkv/webm links, HLS, DASH). */
    VIDEO,

    /** Opened in the built-in web browser (YouTube and any other web page). */
    WEB,
}

data class PlayRequest(val kind: SourceKind, val uri: String, val title: String)

/**
 * Turns whatever the user typed or shared into something the car screen can open.
 * Pure JVM code so it can be unit tested without a device.
 */
object Sources {
    const val YOUTUBE_HOME = "https://m.youtube.com/"

    private val mediaExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "avi", "flv",
        "m3u8", "mpd", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac",
    )
    private val urlInText = Regex("""(?i)\bhttps?://[^\s<>"']+""")
    private val youTubeId = Regex("^[A-Za-z0-9_-]{6,}$")

    /** Finds the first URL inside shared text such as "Watch this https://youtu.be/abc". */
    fun extractUrl(text: String): String? =
        urlInText.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';')

    /** Accepts full URLs and bare hosts ("example.com/video.mp4"). Returns null for plain words. */
    fun normalizeUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        val knownSchemes = listOf("http://", "https://", "content://", "file://")
        return when {
            knownSchemes.any { lower.startsWith(it) } -> text
            !text.contains(' ') && text.contains('.') && !text.startsWith(".") -> "https://$text"
            else -> null
        }
    }

    /** True for http(s) links: the only kind accepted from other apps, never local files. */
    fun isWebUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    fun youTubeVideoId(url: String): String? {
        val uri = parse(url) ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.path.orEmpty()
        val id = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                path == "/watch" -> queryParam(uri.rawQuery, "v")
                path.startsWith("/shorts/") || path.startsWith("/live/") ||
                    path.startsWith("/embed/") -> path.split('/').getOrNull(2)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { youTubeId.matches(it) }
    }

    fun isYouTube(url: String): Boolean {
        val host = parse(url)?.host?.lowercase() ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    fun classify(url: String): SourceKind {
        val lower = url.lowercase()
        if (lower.startsWith("content://") || lower.startsWith("file://")) return SourceKind.VIDEO
        if (isYouTube(url)) return SourceKind.WEB
        val path = parse(url)?.path ?: lower.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (extension in mediaExtensions) return SourceKind.VIDEO
        // Streaming manifests often hide the extension behind query strings.
        if (".m3u8" in lower || ".mpd" in lower) return SourceKind.VIDEO
        return SourceKind.WEB
    }

    /** YouTube links are opened on the mobile site, which plays inside the car browser. */
    fun webUrlFor(url: String): String {
        val id = youTubeVideoId(url) ?: return url
        return "https://m.youtube.com/watch?v=$id"
    }

    fun youTubeSearchUrl(query: String): String =
        "https://m.youtube.com/results?search_query=" + URLEncoder.encode(query.trim(), "UTF-8")

    fun googleSearchUrl(query: String): String =
        "https://www.google.com/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")

    /** Builds a request from typed/shared text. Returns null if nothing usable was found. */
    fun requestFor(input: String, title: String? = null, forceWeb: Boolean = false): PlayRequest? {
        val url = normalizeUrl(input) ?: extractUrl(input) ?: return null
        val kind = if (forceWeb) SourceKind.WEB else classify(url)
        val target = if (kind == SourceKind.WEB) webUrlFor(url) else url
        val name = title?.takeIf { it.isNotBlank() } ?: titleFor(url)
        return PlayRequest(kind, target, name)
    }

    /** Text typed on the car keyboard: a URL opens directly, anything else searches YouTube. */
    fun requestForQuery(text: String, searchYouTube: Boolean): PlayRequest? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val looksLikeUrl = !trimmed.contains(' ') && trimmed.contains('.')
        if (looksLikeUrl) return requestFor(trimmed)
        val url = if (searchYouTube) youTubeSearchUrl(trimmed) else googleSearchUrl(trimmed)
        return PlayRequest(SourceKind.WEB, url, trimmed)
    }

    /** YouTube shares "Watch "Some title" on YouTube" as the subject; keep just the title. */
    fun cleanSharedTitle(subject: String?): String? {
        val text = subject?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return youTubeSubject.matchEntire(text)?.groupValues?.get(1) ?: text
    }

    private val youTubeSubject = Regex("""^Watch ["“](.+)["”] on YouTube$""")

    fun titleFor(url: String): String {
        if (isYouTube(url)) return "YouTube"
        val uri = parse(url)
        val lastSegment = uri?.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        if (lastSegment != null) {
            return try {
                URLDecoder.decode(lastSegment, "UTF-8")
            } catch (e: IllegalArgumentException) {
                lastSegment
            }
        }
        return uri?.host ?: url
    }

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (e: Exception) {
        null
    }

    private fun queryParam(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrEmpty()) return null
        for (pair in rawQuery.split('&')) {
            val key = pair.substringBefore('=')
            if (key == name) {
                return try {
                    URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }
        return null
    }
}
