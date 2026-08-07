package com.anilili.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class DonghuaProviderLiveTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Verifies that article.bs > div.bsx > a card extraction works on animexin.dev search results.
     */
    @Test
    fun testSearchCardExtractionAnimexin() {
        val url = "https://animexin.dev/?s=Battle+Through+the+Heavens"
        val html = fetchHtml(url)
        assertNotNull("Should get HTTP 200 from animexin.dev", html)

        // Check that article.bs cards exist
        val cardMatches = Regex(
            """<article\b[^>]*class=["'][^"']*\bbs\b[^"']*["'][^>]*>[\s\S]*?<a\b([^>]*href=(["'])(https?://[^"']+)\2[^>]*)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        ).findAll(html!!).toList()

        println("Found ${cardMatches.size} article.bs cards on animexin.dev search page")
        cardMatches.take(5).forEach { cm ->
            val href = cm.groupValues[3]
            val titleAttr = NativeProviderParsers.attr(cm.groupValues[1], "title")
            println("  CARD -> href=$href | title=$titleAttr")
        }

        assertTrue("Should find at least 1 article.bs card", cardMatches.isNotEmpty())
    }

    /**
     * Verifies that the full catalog fetch chain works:
     *   search → series page → episode list extraction
     */
    @Test
    fun testFullCatalogChainAnimexin() {
        val url = "https://animexin.dev/?s=Battle+Through+the+Heavens"
        val html = fetchHtml(url) ?: error("Could not fetch search page")

        // Find best candidate URL using article.bs pattern
        val cardPattern = Regex(
            """<article\b[^>]*class=["'][^"']*\bbs\b[^"']*["'][^>]*>[\s\S]*?<a\b([^>]*href=(["'])(https?://[^"']+)\2[^>]*)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
        val cards = cardPattern.findAll(html).toList()
        println("Search result cards found: ${cards.size}")

        val firstSeriesUrl = cards.firstOrNull()?.groupValues?.get(3)
        assertNotNull("Should find at least 1 card with series URL", firstSeriesUrl)

        println("First series URL: $firstSeriesUrl")

        val seriesHtml = fetchHtml(firstSeriesUrl!!) ?: error("Could not fetch series page")
        println("Series page length: ${seriesHtml.length}")

        // Extract episode list
        val epMatches = Regex(
            """<a\b([^>]*href=(["'])(.*?)\2[^>]*)>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        ).findAll(seriesHtml).filter { m ->
            val href = m.groupValues[3]
            val text = NativeProviderParsers.stripTags(m.groupValues[4])
            href.contains("episode", ignoreCase = true) || text.contains(Regex("""episode\s+\d+""", RegexOption.IGNORE_CASE))
        }.take(10).toList()

        println("Episode links found: ${epMatches.size}")
        epMatches.take(5).forEach { m ->
            println("  EP -> href=${m.groupValues[3]} | text=${NativeProviderParsers.stripTags(m.groupValues[4]).take(50)}")
        }

        assertTrue("Should find at least 1 episode link on series page", epMatches.isNotEmpty())
    }

    /**
     * Verifies that Dailymotion metadata API works for extracting m3u8 URL.
     */
    @Test
    fun testDailymotionMetadataApi() {
        // Known Dailymotion video ID from logic.txt (animexin.dev episode example)
        val videoId = "k35J6SxI40Pg2uzfTPo"
        val metaUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        val req = Request.Builder()
            .url(metaUrl)
            .header("Referer", "https://geo.dailymotion.com/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        client.newCall(req).execute().use { resp ->
            println("DM Metadata HTTP Code: ${resp.code}")
            val body = resp.body?.string().orEmpty()
            println("DM Metadata Body Length: ${body.length}")
            val m3u8Match = Regex(""""url":\s*"(https[^"]+\.m3u8[^"]*)"""").find(body)
            if (m3u8Match != null) {
                println("DM M3U8 URL: ${m3u8Match.groupValues[1]}")
            }
            assertTrue("Should get Dailymotion metadata response", body.length > 100)
        }
    }

    private fun fetchHtml(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }
}
