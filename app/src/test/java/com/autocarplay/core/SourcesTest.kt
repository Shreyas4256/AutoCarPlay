package com.autocarplay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {

    @Test
    fun youTubeLinksOpenInTheBrowserOnTheMobileSite() {
        val request = Sources.requestFor("https://youtu.be/dQw4w9WgXcQ?si=abc")!!
        assertEquals(SourceKind.WEB, request.kind)
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", request.uri)

        assertEquals("dQw4w9WgXcQ", Sources.youTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"))
        assertEquals("dQw4w9WgXcQ", Sources.youTubeVideoId("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Sources.youTubeVideoId("https://youtube.com/live/dQw4w9WgXcQ?feature=share"))
        assertNull(Sources.youTubeVideoId("https://example.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun youTubePagesWithoutAVideoStayAsTheyAre() {
        val request = Sources.requestFor("https://www.youtube.com/@SomeChannel")!!
        assertEquals(SourceKind.WEB, request.kind)
        assertEquals("https://www.youtube.com/@SomeChannel", request.uri)
    }

    @Test
    fun mediaFilesAndStreamsUseTheVideoPlayer() {
        assertEquals(SourceKind.VIDEO, Sources.classify("https://example.com/movies/trip.mp4"))
        assertEquals(SourceKind.VIDEO, Sources.classify("https://cdn.example.com/live/master.m3u8?token=1"))
        assertEquals(SourceKind.VIDEO, Sources.classify("https://cdn.example.com/stream?format=.mpd"))
        assertEquals(SourceKind.VIDEO, Sources.classify("content://media/external/video/media/12"))
        assertEquals(SourceKind.WEB, Sources.classify("https://news.example.com/article"))
    }

    @Test
    fun bareHostsGetHttps() {
        assertEquals("https://example.com/video.mp4", Sources.normalizeUrl("example.com/video.mp4"))
        assertNull(Sources.normalizeUrl("funny cats"))
        assertNull(Sources.normalizeUrl("   "))
    }

    @Test
    fun sharedTextWithALinkInside() {
        val shared = "Check this out! https://youtu.be/dQw4w9WgXcQ."
        assertEquals("https://youtu.be/dQw4w9WgXcQ", Sources.extractUrl(shared))
        val request = Sources.requestFor(shared, Sources.cleanSharedTitle("Watch \"Never Gonna Give You Up\" on YouTube"))!!
        assertEquals("Never Gonna Give You Up", request.title)
        assertEquals(SourceKind.WEB, request.kind)
    }

    @Test
    fun carKeyboardSearchesYouTubeOrOpensAddresses() {
        val search = Sources.requestForQuery("lofi hip hop", searchYouTube = true)!!
        assertEquals("https://m.youtube.com/results?search_query=lofi+hip+hop", search.uri)

        val web = Sources.requestForQuery("weather today", searchYouTube = false)!!
        assertEquals("https://www.google.com/search?q=weather+today", web.uri)

        val site = Sources.requestForQuery("wikipedia.org", searchYouTube = false)!!
        assertEquals("https://wikipedia.org", site.uri)
        assertNull(Sources.requestForQuery("  ", searchYouTube = true))
    }

    @Test
    fun onlyWebLinksCountAsWebUrls() {
        assertTrue(Sources.isWebUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(Sources.isWebUrl(" HTTP://example.com/video.mp4"))
        // Shared text from other apps must not reach local files or content providers.
        assertFalse(Sources.isWebUrl(Sources.requestFor("file:///data/data/com.autocarplay/x.mp4")!!.uri))
        assertFalse(Sources.isWebUrl(Sources.requestFor("content://media/external/video/media/12")!!.uri))
        assertTrue(Sources.isWebUrl(Sources.requestFor("example.com/video.mp4")!!.uri))
    }

    @Test
    fun titlesComeFromTheFileName() {
        assertEquals("My Trip.mp4", Sources.titleFor("https://example.com/videos/My%20Trip.mp4"))
        assertEquals("example.com", Sources.titleFor("https://example.com"))
        assertEquals("YouTube", Sources.titleFor("https://youtu.be/dQw4w9WgXcQ"))
    }
}
