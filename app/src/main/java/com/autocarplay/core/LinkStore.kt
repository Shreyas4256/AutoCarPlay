package com.autocarplay.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedLink(val id: Long, val title: String, val url: String)

/** Saved links and recent car-keyboard searches, kept in SharedPreferences. */
class LinkStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("links", Context.MODE_PRIVATE)

    fun links(): List<SavedLink> {
        val raw = prefs.getString(KEY_LINKS, null) ?: return defaultLinks
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SavedLink(o.getLong("id"), o.getString("title"), o.getString("url"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Adds a link to the top of the list (moving it there if it already exists). */
    fun add(title: String, url: String): SavedLink {
        val link = SavedLink(System.currentTimeMillis(), title.ifBlank { Sources.titleFor(url) }, url)
        val updated = listOf(link) + links().filter { it.url != url }
        saveLinks(updated.take(MAX_LINKS))
        return link
    }

    fun remove(id: Long) = saveLinks(links().filter { it.id != id })

    fun recentQueries(): List<String> {
        val raw = prefs.getString(KEY_QUERIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecentQuery(query: String) {
        val updated = (listOf(query) + recentQueries().filter { it != query }).take(MAX_QUERIES)
        prefs.edit().putString(KEY_QUERIES, JSONArray(updated).toString()).apply()
    }

    private fun saveLinks(links: List<SavedLink>) {
        val array = JSONArray()
        links.forEach {
            array.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url))
        }
        prefs.edit().putString(KEY_LINKS, array.toString()).apply()
    }

    companion object {
        private const val KEY_LINKS = "links"
        private const val KEY_QUERIES = "queries"
        private const val MAX_LINKS = 100
        private const val MAX_QUERIES = 5

        /** Shown until the user saves their own links, so the list is never empty on first run. */
        private val defaultLinks = listOf(
            SavedLink(1, "YouTube", Sources.YOUTUBE_HOME),
            SavedLink(2, "Big Buck Bunny (test video)", "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
            SavedLink(3, "Apple HLS test stream", "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"),
        )
    }
}
