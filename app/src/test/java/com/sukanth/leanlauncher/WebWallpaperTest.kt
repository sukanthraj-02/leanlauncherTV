package com.sukanth.leanlauncher

import com.sukanth.leanlauncher.wallpaper.WebWallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebWallpaperTest {

    private val base = "https://gallery.example/albums/one"

    @Test
    fun `open graph image is preferred`() {
        val html = """
            <meta property="og:image" content="https://cdn.example/hero.jpg">
            <img src="/thumbnails/one.jpg">
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.example/hero.jpg", "https://gallery.example/thumbnails/one.jpg"),
            WebWallpaper.extractImageUrls(html, base),
        )
    }

    @Test
    fun `relative and protocol relative images resolve`() {
        val html = """
            <img src="/images/a.jpg">
            <img src="//cdn.example/b.png">
        """.trimIndent()

        assertEquals(
            listOf("https://gallery.example/images/a.jpg", "https://cdn.example/b.png"),
            WebWallpaper.extractImageUrls(html, base),
        )
    }

    @Test
    fun `srcset picks the widest candidate`() {
        val html = """<img srcset="/small.jpg 400w, /large.jpg 1600w">"""

        assertEquals(
            listOf("https://gallery.example/large.jpg"),
            WebWallpaper.extractImageUrls(html, base),
        )
    }

    @Test
    fun `lazy data source is used when src is a placeholder`() {
        val html = """<img src="data:image/gif;base64,R0lGODlhAQAB" data-src="/real.jpg">"""

        assertEquals(
            listOf("https://gallery.example/real.jpg"),
            WebWallpaper.extractImageUrls(html, base),
        )
    }

    @Test
    fun `logos and icons are ignored`() {
        val html = """
            <a href="/logo.png">logo</a>
            <a href="/site-icon.svg">icon</a>
            <a href="/wallpapers/wide.jpg">wide</a>
        """.trimIndent()

        assertEquals(
            listOf("https://gallery.example/wallpapers/wide.jpg"),
            WebWallpaper.extractImageUrls(html, base),
        )
    }

    @Test
    fun `background image urls are found`() {
        val html = """<div style="background-image: url('/bg/space.jpg')"></div>"""

        assertEquals(
            listOf("https://gallery.example/bg/space.jpg"),
            WebWallpaper.extractImageUrls(html, base),
        )
    }

    @Test
    fun `json escaped image addresses are found`() {
        val json = """
            {"data":[{"path":"https:\/\/cdn.example\/full\/aa.jpg",
            "thumbs":{"large":"https:\/\/cdn.example\/thumbs\/aa.jpg"}}]}
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.example/full/aa.jpg", "https://cdn.example/thumbs/aa.jpg"),
            WebWallpaper.extractImageUrls(json, base),
        )
    }

    @Test
    fun `html entities in query strings are decoded`() {
        val html = """<img src="https://cdn.example/a.jpg?w=1280&amp;h=720">"""

        assertEquals(
            listOf("https://cdn.example/a.jpg?w=1280&h=720"),
            WebWallpaper.extractImageUrls(html, base),
        )
    }

    @Test
    fun `duplicate addresses appear once`() {
        val html = """
            <img src="/a.jpg">
            <img src="/a.jpg">
            <a href="/a.jpg">a</a>
        """.trimIndent()

        assertEquals(
            listOf("https://gallery.example/a.jpg"),
            WebWallpaper.extractImageUrls(html, base),
        )
    }

    @Test
    fun `nothing is extracted from a page without images`() {
        assertTrue(WebWallpaper.extractImageUrls("<p>No photos here.</p>", base).isEmpty())
    }

    @Test
    fun `bare host gets a scheme`() {
        assertEquals(
            "https://gallery.example/wallpapers",
            WebWallpaper.normalizePageUrl("gallery.example/wallpapers"),
        )
    }

    @Test
    fun `non http addresses are rejected`() {
        assertEquals(null, WebWallpaper.normalizePageUrl("file:///sdcard/photo.jpg"))
        assertEquals(null, WebWallpaper.normalizePageUrl("javascript:alert(1)"))
        assertEquals(null, WebWallpaper.normalizePageUrl(""))
    }

    @Test
    fun `image content type detection is exact about the prefix`() {
        assertTrue(WebWallpaper.isImageContentType("image/jpeg"))
        assertTrue(WebWallpaper.isImageContentType("IMAGE/PNG; charset=binary"))
        assertFalse(WebWallpaper.isImageContentType("text/html"))
        assertFalse(WebWallpaper.isImageContentType(null))
    }

    @Test
    fun `image extension detection ignores query strings`() {
        assertTrue(WebWallpaper.looksLikeImageUrl("https://cdn.example/a.webp?w=1280"))
        assertTrue(WebWallpaper.looksLikeImageUrl("https://cdn.example/a.JPG#x"))
        assertFalse(WebWallpaper.looksLikeImageUrl("https://cdn.example/image.php?id=1"))
    }
}
