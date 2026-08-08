package com.anilili.data.remote

import android.util.Base64
import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Native Donghua Multi-Source Provider for Anilili.
 *
 * Scrapes and aggregates streams across major Donghua streaming platforms in parallel:
 * 1. animexin.dev
 * 2. luciferdonghua.in
 * 3. donghuastream.org
 * 4. animecube.live
 */
internal class DonghuaProvider(private val client: OkHttpClient) {

    private data class EpisodeLink(
        val siteName: String,
        val url: String,
        val title: String,
    )

    private data class Catalog(val episodes: Map<Int, List<EpisodeLink>>)

    private val catalogs = ConcurrentHashMap<Int, Catalog>()

    fun episodeAvailability(media: Media): EpisodeAvailability {
        // Fast, non-blocking episode count resolution so Donghua server always appears in the picker
        val count = when {
            media.status == "RELEASING" && media.nextAiringEpisode?.episode != null ->
                (media.nextAiringEpisode.episode - 1).coerceAtLeast(1)
            media.episodes != null && media.episodes > 0 -> media.episodes
            media.format == "MOVIE" -> 1
            else -> 100
        }
        val availableEpisodes = (1..count).toSet()
        return EpisodeAvailability(sub = availableEpisodes, dub = emptySet())
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val links = catalog(media).episodes[episode]
            ?: error("Donghua episode $episode is not available in catalog")

        val rawStreams = mapParallel(links, 4, 8000L) { link ->
            runCatching { extractStreamsFromLink(link) }
                .onFailure { DiagnosticsLog.throwable("Donghua extract failed for ${link.siteName}", it) }
                .getOrNull()
        }.filterNotNull().flatten()

        if (rawStreams.isEmpty()) {
            error("Donghua episode $episode returned no playable streams")
        }

        val streams = rawStreams.distinctBy { it.url }
        val activeStream = streams.firstOrNull { it.quality != null && it.quality.contains("StreamWish", ignoreCase = true) }
            ?: streams.firstOrNull { it.quality != null && it.quality.contains("Dailymotion", ignoreCase = true) }
            ?: streams.first()

        val updatedStreams = streams.map { stream ->
            stream.copy(isActive = stream.url == activeStream.url)
        }

        return SourcesResult(
            streams = updatedStreams,
            subtitles = emptyList(),
            skip = null,
            download = null,
        )
    }

    private fun catalog(media: Media): Catalog {
        catalogs[media.id]?.let { return it }

        val built = buildCatalog(media)
        if (built == null || built.episodes.isEmpty()) {
            error("Donghua provider has no entry for this title")
        }
        catalogs[media.id] = built
        return built
    }

    private fun buildCatalog(media: Media): Catalog? {
        val rawTitles = mutableListOf<String>()
        media.title.english?.let { rawTitles.add(it) }
        media.title.userPreferred?.let { rawTitles.add(it) }
        media.title.romaji?.let { rawTitles.add(it) }
        media.title.native?.let { rawTitles.add(it) }

        // Expand Pinyin / English aliases via DONGHUA_TITLE_MAP & season cleaning
        val expandedTitles = ArrayList<String>()
        val seasonPattern = Regex("""\b(?:\d+(?:st|nd|rd|th)?\s+Season|Season\s+\d+|Part\s+\d+|\(CN\)|\(Chinese\)|2nd|3rd|4th|5th)\b""", RegexOption.IGNORE_CASE)
        for (t in rawTitles) {
            if (t.isBlank()) continue
            expandedTitles.add(t)
            val cleaned = t.replace(seasonPattern, "").trim()
            if (cleaned.isNotBlank() && cleaned != t) {
                expandedTitles.add(cleaned)
            }
            val normalized = t.lowercase().trim()
            DONGHUA_TITLE_MAP[normalized]?.let { expandedTitles.add(it) }
            val cleanedNorm = cleaned.lowercase().trim()
            DONGHUA_TITLE_MAP[cleanedNorm]?.let { expandedTitles.add(it) }
        }

        val titles = expandedTitles.filter { it.isNotBlank() }.distinct()

        val episodeMap = HashMap<Int, MutableList<EpisodeLink>>()

        // Query the 4 providers in parallel
        val tasks = listOf(
            Callable { searchAnimeStreamSite("AnimeXin", "https://animexin.dev", titles) },
            Callable { searchAnimeStreamSite("LuciferDonghua", "https://luciferdonghua.in", titles) },
            Callable { searchAnimeStreamSite("DonghuaStream", "https://donghuastream.org", titles) },
            Callable { searchAnimeCubeSite(titles) },
        )

        val executor = Executors.newFixedThreadPool(4)
        try {
            // 25 seconds: each site needs 2 sequential HTTP calls (~5-8s each on mobile).
            // The outer coroutine withTimeoutOrNull(15s) in AnivexaClient is what the user sees;
            // we give enough runway here so at least 1-2 sites complete before that fires.
            val futures = executor.invokeAll(tasks, 25, TimeUnit.SECONDS)
            futures.forEach { future ->
                if (future.isDone && !future.isCancelled) {
                    runCatching {
                        val siteEpisodes = future.get() ?: emptyList()
                        siteEpisodes.forEach { (epNum, link) ->
                            if (epNum >= 1) {
                                episodeMap.getOrPut(epNum) { ArrayList() }.add(link)
                            }
                        }
                    }.onFailure { DiagnosticsLog.throwable("Donghua catalog future.get failed", it) }
                } else {
                    DiagnosticsLog.event("Donghua catalog task timed out or cancelled")
                }
            }
        } finally {
            executor.shutdownNow()
        }

        if (episodeMap.isEmpty()) return null
        return Catalog(episodes = episodeMap)
    }

    /**
     * Searches a WordPress AnimeStream theme based website (animexin.dev, luciferdonghua.in, donghuastream.org)
     */
    private fun searchAnimeStreamSite(
        siteName: String,
        baseUrl: String,
        titles: List<String>,
    ): List<Pair<Int, EpisodeLink>>? {
        for (query in titles) {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val searchUrl = "$baseUrl/?s=$encodedQuery"

            val html = fetchHtml(searchUrl) ?: continue
            val candidateUrl = findBestCandidateUrl(html, query, baseUrl) ?: continue
            val seriesHtml = fetchHtml(candidateUrl) ?: continue

            val episodes = parseAnimeStreamEpisodes(siteName, baseUrl, seriesHtml)
            if (episodes.isNotEmpty()) return episodes
        }
        return null
    }

    /**
     * Finds the best candidate series URL from a search result page.
     *
     * AnimeStream WordPress sites (animexin.dev, luciferdonghua.in, donghuastream.org) use:
     *   article.bs > div.bsx > a[title="Series Name"][href="https://..."]
     *
     * We extract cards using this pattern specifically, then fall back to generic link scoring.
     */
    private fun findBestCandidateUrl(html: String, query: String, baseUrl: String): String? {
        // ── Primary: extract search result cards via article.bs > div.bsx > a ──
        // This matches what animexin_extension.dart & logic.txt confirm as the real selector
        val cardPattern = Regex(
            """<article\b[^>]*class=["'][^"']*\bbs\b[^"']*["'][^>]*>[\s\S]*?<a\b([^>]*href=(["'])(https?://[^"']+)\2[^>]*)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
        val cardMatches = cardPattern.findAll(html).toList()

        if (cardMatches.isNotEmpty()) {
            var bestUrl: String? = null
            var maxScore = 0.0
            for (cm in cardMatches) {
                val tagAttrs = cm.groupValues[1]
                val linkUrl = cm.groupValues[3]
                val titleAttr = NativeProviderParsers.attr(tagAttrs, "title")
                val slugText = linkUrl.substringAfterLast("/").replace('-', ' ')
                val candidateText = listOf(titleAttr, slugText).filter { it.isNotBlank() }.joinToString(" ")
                val score = NativeProviderParsers.titleScore(query, candidateText)
                if (score > maxScore) {
                    maxScore = score
                    bestUrl = linkUrl
                }
            }
            // Accept if score >= 0.15 (lower threshold since title attribute is reliable)
            if (bestUrl != null && maxScore >= 0.15) return bestUrl
            // Accept first card if no score threshold met (single result likely correct)
            if (cardMatches.size == 1) {
                val firstHref = cardMatches[0].groupValues[3]
                if (firstHref.startsWith(baseUrl)) return firstHref
            }
        }

        // ── Fallback: bsx anchor without enclosing article ──
        val bsxPattern = Regex(
            """<div\b[^>]*class=["'][^"']*\bbsx\b[^"']*["'][^>]*>[\s\S]{0,200}?<a\b([^>]*href=(["'])(https?://[^"']+)\2[^>]*)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
        val bsxMatches = bsxPattern.findAll(html).toList()
        if (bsxMatches.isNotEmpty()) {
            var bestUrl: String? = null
            var maxScore = 0.0
            for (bm in bsxMatches) {
                val tagAttrs = bm.groupValues[1]
                val linkUrl = bm.groupValues[3]
                val titleAttr = NativeProviderParsers.attr(tagAttrs, "title")
                val slugText = linkUrl.substringAfterLast("/").replace('-', ' ')
                val candidateText = listOf(titleAttr, slugText).filter { it.isNotBlank() }.joinToString(" ")
                val score = NativeProviderParsers.titleScore(query, candidateText)
                if (score > maxScore) {
                    maxScore = score
                    bestUrl = linkUrl
                }
            }
            if (bestUrl != null && maxScore >= 0.15) return bestUrl
        }

        // ── Last resort: generic link scoring (original logic) ──
        val matches = Regex(
            """\<a\b([^>]*href=(["'])(.*?)\2[^>]*)>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE,
        ).findAll(html)

        var bestUrl: String? = null
        var maxScore = 0.0
        for (match in matches) {
            val tagAttrs = match.groupValues[1]
            val linkUrl = match.groupValues[3]
            val innerContent = NativeProviderParsers.stripTags(match.groupValues[4])
            val titleAttr = NativeProviderParsers.attr(tagAttrs, "title")
            val candidateText = listOf(innerContent, titleAttr, linkUrl.replace('-', ' '))
                .filter { it.isNotBlank() }.joinToString(" ")
            if (!linkUrl.contains(".js") && !linkUrl.contains(".css") &&
                linkUrl.startsWith(baseUrl) && !linkUrl.contains("page/") &&
                !linkUrl.contains("/genres/") && !linkUrl.contains("/network/") &&
                !linkUrl.contains("/studio/") && !linkUrl.contains("/country/")) {
                val score = NativeProviderParsers.titleScore(query, candidateText)
                if (score > maxScore && score >= 0.15) {
                    maxScore = score
                    bestUrl = NativeProviderParsers.absoluteUrl(baseUrl, linkUrl)
                }
            }
        }
        return bestUrl
    }

    private fun parseAnimeStreamEpisodes(
        siteName: String,
        baseUrl: String,
        html: String,
    ): List<Pair<Int, EpisodeLink>> {
        val result = ArrayList<Pair<Int, EpisodeLink>>()

        // Match episode links in <ul class="eplister"> or generic anchor tags
        val matches = Regex(
            """<a\b([^>]*href=(["'])(.*?)\2[^>]*)>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE,
        ).findAll(html)

        for (match in matches) {
            val tagAttrs = match.groupValues[1]
            val href = match.groupValues[3]
            val innerText = NativeProviderParsers.stripTags(match.groupValues[4])
            val titleAttr = NativeProviderParsers.attr(tagAttrs, "title")

            val combined = "$href $innerText $titleAttr"

            val epNum = Regex("""\b(?:episode|ep)[\s.-]*(\d+)""", RegexOption.IGNORE_CASE)
                .find(combined)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\b(\d+)\b""").find(innerText)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue

            if (epNum <= 0 || (epNum in 1900..2099 && !combined.contains("episode", true) && !combined.contains("ep", true))) {
                continue
            }

            val fullUrl = NativeProviderParsers.absoluteUrl(baseUrl, href)
            result.add(epNum to EpisodeLink(siteName = siteName, url = fullUrl, title = innerText.ifBlank { "Episode $epNum" }))
        }
        return result.distinctBy { it.first }
    }

    /**
     * Searches animecube.live via standard WordPress search endpoint first,
     * then falls back to slug-guessing the anime detail URL directly.
     */
    private fun searchAnimeCubeSite(titles: List<String>): List<Pair<Int, EpisodeLink>>? {
        val baseUrl = "https://animecube.live"
        for (query in titles) {
            // 1. Try WordPress search first
            val encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val searchHtml = fetchHtml("$baseUrl/?s=$encodedQuery")
            if (searchHtml != null) {
                val candidateUrl = findBestCandidateUrl(searchHtml, query, baseUrl)
                if (candidateUrl != null) {
                    val seriesHtml = fetchHtml(candidateUrl) ?: continue
                    val episodes = parseAnimeStreamEpisodes("AnimeCube", baseUrl, seriesHtml)
                    if (episodes.isNotEmpty()) return episodes
                }
            }
            // 2. Fallback: guess slug-based URL
            val slug = query.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            for (pattern in listOf("$baseUrl/anime/$slug", "$baseUrl/$slug")) {
                val html = fetchHtml(pattern) ?: continue
                if (html.length < 5000 && html.contains("404", ignoreCase = true)) continue
                val episodes = parseAnimeStreamEpisodes("AnimeCube", baseUrl, html)
                if (episodes.isNotEmpty()) return episodes
            }
        }
        return null
    }

    /**
     * Extracts playable stream items from an episode watch link.
     */
    private fun extractStreamsFromLink(link: EpisodeLink): List<StreamItem> {
        val html = fetchHtml(link.url) ?: return emptyList()
        val streams = ArrayList<StreamItem>()

        // 1. Check for AnimeStream Base64 option values inside select element
        for (option in Regex("""<option\b[^>]*value=(["'])(.*?)\1[^>]*>([\s\S]*?)</option>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val rawValue = option.groupValues[2].trim()
            val serverName = NativeProviderParsers.stripTags(option.groupValues[3]).trim()
            if (rawValue.isBlank() || serverName.contains("Select Video Server", ignoreCase = true)) continue

            // CRITICAL: Strip embedded newlines/CR before Base64 decode.
            // animexin.dev HTML-formats long base64 strings with line breaks inside the value attribute.
            // android.util.Base64.decode will fail silently on these without prior stripping.
            // (Same fix as in animexin_extension.dart line 480: valAttr.replaceAll('\n', '').trim())
            val cleanValue = rawValue.replace("\n", "").replace("\r", "").replace(" ", "")

            // Attempt Base64 decode: try DEFAULT first, then URL_SAFE fallback
            val decodedHtml = runCatching {
                String(Base64.decode(cleanValue, Base64.DEFAULT), StandardCharsets.UTF_8)
            }.getOrNull() ?: runCatching {
                String(Base64.decode(cleanValue, Base64.URL_SAFE), StandardCharsets.UTF_8)
            }.getOrNull() ?: run {
                DiagnosticsLog.event("Donghua Base64 decode failed for server: $serverName")
                rawValue  // fallback: use raw value as-is
            }

            val iframeSrc = Regex("""<iframe\b[^>]*src=(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
                .find(decodedHtml)?.groupValues?.get(2)
                ?: Regex("""<iframe\b[^>]*src=(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
                    .find(rawValue)?.groupValues?.get(2)

            if (iframeSrc != null) {
                val cleanIframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                val streamUrl = resolveEmbedToDirectStream(cleanIframeUrl) ?: cleanIframeUrl
                streams.add(
                    StreamItem(
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) "hls" else "embed",
                        quality = "${link.siteName} - $serverName",
                        audio = "sub",
                        referer = link.url,
                        isActive = false,
                        width = null,
                        height = null,
                    )
                )
            }
        }

        // 2. Direct iframe embeds on the page if options were empty
        if (streams.isEmpty()) {
            val directIframes = Regex("""<iframe\b[^>]*src=(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
                .findAll(html)
            for (match in directIframes) {
                val iframeUrl = match.groupValues[2]
                if (iframeUrl.contains("google") || iframeUrl.contains("facebook")) continue
                val cleanUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                val directStream = resolveEmbedToDirectStream(cleanUrl) ?: cleanUrl
                streams.add(
                    StreamItem(
                        url = directStream,
                        type = if (directStream.contains(".m3u8")) "hls" else "embed",
                        quality = "${link.siteName} Stream",
                        audio = "sub",
                        referer = link.url,
                        isActive = false,
                        width = null,
                        height = null,
                    )
                )
            }
        }

        return streams
    }

    private fun resolveEmbedToDirectStream(embedUrl: String): String? {
        if (embedUrl.contains(".m3u8")) return embedUrl

        // StreamWish / SeekPlayer / Filemoon: scrape page for HLS source
        if (embedUrl.contains("seekplayer.") || embedUrl.contains("streamwish") ||
            embedUrl.contains("wishfast") || embedUrl.contains("strwish") ||
            embedUrl.contains("awish") || embedUrl.contains("wishembed") ||
            embedUrl.contains("filemoon") || embedUrl.contains("ahvsh.")) {
            val html = fetchHtml(embedUrl) ?: return null
            val m3u8List = NativeProviderParsers.hlsUrls(html)
            if (m3u8List.isNotEmpty()) return m3u8List.first()
        }

        // Dailymotion: use player metadata API (same as Dart extension)
        if (embedUrl.contains("dailymotion.com")) {
            val videoId = Regex("[?&]video=([a-zA-Z0-9]+)").find(embedUrl)?.groupValues?.get(1)
                ?: Regex("/video/([a-zA-Z0-9]+)").find(embedUrl)?.groupValues?.get(1)
                ?: Regex("/embed/([a-zA-Z0-9]+)").find(embedUrl)?.groupValues?.get(1)
            if (!videoId.isNullOrBlank()) {
                val metaRequest = Request.Builder()
                    .url("https://www.dailymotion.com/player/metadata/video/$videoId")
                    .header("Referer", "https://geo.dailymotion.com/")
                    .header("User-Agent", USER_AGENT)
                    .build()
                val metaJson = runCatching {
                    client.newCall(metaRequest).execute().use { it.body?.string() }
                }.getOrNull() ?: return null
                // Extract master m3u8 from qualities.auto[0].url
                val masterUrl = Regex(""""url":\s*"(https[^"]+\.m3u8[^"]*)"""").find(metaJson)?.groupValues?.get(1)
                    ?.replace("\\u0026", "&")
                if (!masterUrl.isNullOrBlank()) return masterUrl
            }
        }

        // ok.ru embeds: pass through directly (player renders via embed)
        if (embedUrl.contains("ok.ru/videoembed/")) return embedUrl

        // StreamSB / SBLona
        if (embedUrl.contains("sblona.") || embedUrl.contains("streamsb.")) {
            val html = fetchHtml(embedUrl) ?: return null
            val m3u8List = NativeProviderParsers.hlsUrls(html)
            if (m3u8List.isNotEmpty()) return m3u8List.first()
        }

        // DoodStream: pass through directly
        if (embedUrl.contains("dood.") || embedUrl.contains("dooo")) return embedUrl

        return null
    }

    private fun fetchHtml(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
    }

    private inline fun <A, B> mapParallel(
        items: List<A>,
        concurrency: Int,
        timeoutMs: Long,
        crossinline block: (A) -> B,
    ): List<B> {
        if (items.isEmpty()) return emptyList()
        val executor = Executors.newFixedThreadPool(concurrency.coerceAtMost(items.size))
        return try {
            val tasks = items.map { item -> Callable { block(item) } }
            executor.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        /** Bi-directional map between Pinyin / Doujin titles and English titles */
        private val DONGHUA_TITLE_MAP = mapOf(
            "doupo cangqiong" to "Battle Through the Heavens",
            "battle through the heavens" to "Doupo Cangqiong",
            "btth" to "Battle Through the Heavens",
            "douluo dalu" to "Soul Land",
            "soul land" to "Douluo Dalu",
            "douluo dalu 2" to "Soul Land 2",
            "soul land 2" to "Douluo Dalu II",
            "wanmei shijie" to "Perfect World",
            "perfect world" to "Wanmei Shijie",
            "xing chen bian" to "Stellar Transformation",
            "stellar transformation" to "Xing Chen Bian",
            "fanren xiu xian chuan" to "A Record of a Mortal's Journey to Immortality",
            "a record of a mortal's journey to immortality" to "Fanren Xiu Xian Chuan",
            "tunshi xingkong" to "Swallowed Star",
            "swallowed star" to "Tunshi Xingkong",
            "yi nian yong heng" to "A Will Eternal",
            "yinian yongheng" to "A Will Eternal",
            "a will eternal" to "Yi Nian Yong Heng",
            "xian wu di zun" to "Legend of Xianwu",
            "legend of xianwu" to "Xian Wu Di Zun",
            "wu dong qiankun" to "Martial Universe",
            "martial universe" to "Wu Dong Qiankun",
            "zhetian" to "Shrouding the Heavens",
            "shrouding the heavens" to "Zhetian",
            "da zhu zai" to "The Great Ruler",
            "the great ruler" to "Da Zhu Zai",
            "zun shang" to "Supreme God Emperor",
            "supreme god emperor" to "Zun Shang",
            "againts the sky supreme" to "Against the Sky Supreme",
            "against sky supreme" to "Against the Sky Supreme",
            "ni tian zhi zun" to "Against the Sky Supreme",
            "bailian cheng shen" to "Apotheosis",
            "apotheosis" to "Bailian Cheng Shen",
            "xian ni" to "Renegade Immortal",
            "renegade immortal" to "Xian Ni",
            "yao shen ji" to "Tales of Demons and Gods",
            "tales of demons and gods" to "Yao Shen Ji",
            "shen yin wang zuo" to "Throne of Seal",
            "throne of seal" to "Shen Yin Wang Zuo",
            "zhu xian" to "Jade Dynasty",
            "jade dynasty" to "Zhu Xian",
            "yuan zun" to "Dragon Prince Yuan",
            "dragon prince yuan" to "Yuan Zun",
            "lian qi shi wan nian" to "100,000 Years of Body Refining",
            "100,000 years of body refining" to "Lian Qi Shi Wan Nian",
            "jue shi wu shen" to "Peerless Martial Spirit",
            "peerless martial spirit" to "Jue Shi Wu Shen",
            "xue ying ling zhu" to "Snow Eagle Lord",
            "snow eagle lord" to "Xue Ying Ling Zhu",
        )
    }
}
