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

    private val catalogs = ConcurrentHashMap<String, Catalog>()

    fun episodeAvailability(media: Media, siteKey: String = "donghua"): EpisodeAvailability {
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

    fun sources(media: Media, audio: String, episode: Int, siteKey: String = "donghua"): SourcesResult {
        val links = catalog(media, siteKey).episodes[episode]
            ?: error("Provider $siteKey episode $episode is not available in catalog")

        val rawStreams = mapParallel(links, 4, 8000L) { link ->
            runCatching { extractStreamsFromLink(link) }
                .onFailure { DiagnosticsLog.throwable("Donghua extract failed for ${link.siteName}", it) }
                .getOrNull()
        }.filterNotNull().flatten()

        if (rawStreams.isEmpty()) {
            error("Provider $siteKey episode $episode returned no playable streams")
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

    private fun catalog(media: Media, siteKey: String): Catalog {
        val cacheKey = "${media.id}_${siteKey.lowercase()}"
        catalogs[cacheKey]?.let { return it }

        val built = buildCatalog(media, siteKey)
        if (built == null || built.episodes.isEmpty()) {
            error("Donghua provider $siteKey has no entry for this title")
        }
        catalogs[cacheKey] = built
        return built
    }

    private fun buildCatalog(media: Media, siteKey: String): Catalog? {
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

        val site = siteKey.lowercase()
        val tasks = ArrayList<Callable<List<Pair<Int, EpisodeLink>>?>>()
        if (site == "animexin" || site == "donghua") {
            tasks.add(Callable { searchAnimeStreamSite("AnimeXin", "https://animexin.dev", titles) })
        }
        if (site == "luciferdonghua" || site == "donghua") {
            tasks.add(Callable { searchAnimeStreamSite("LuciferDonghua", "https://luciferdonghua.in", titles) })
        }
        if (site == "donghuastream" || site == "donghua") {
            tasks.add(Callable { searchAnimeStreamSite("DonghuaStream", "https://donghuastream.org", titles) })
        }
        if (site == "animecube" || site == "donghua") {
            tasks.add(Callable { searchAnimeCubeSite(titles) })
        }

        val executor = Executors.newFixedThreadPool(tasks.size.coerceAtLeast(1))
        try {
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
     * Finds the best candidate series URL from a search result page using Jsoup DOM selectors.
     */
    private fun findBestCandidateUrl(html: String, query: String, baseUrl: String): String? {
        val doc = org.jsoup.Jsoup.parse(html)

        // ── Primary: Jsoup card selection (article.bs div.bsx a, div.bsx a, div.animposx a) ──
        val cards = doc.select("article.bs div.bsx a, div.bsx a, div.animposx a")
        if (cards.isNotEmpty()) {
            var bestUrl: String? = null
            var maxScore = 0.0
            for (card in cards) {
                val href = card.attr("href")
                if (href.isBlank()) continue
                val titleAttr = card.attr("title")
                val text = card.text()
                val slugText = href.substringAfterLast("/").replace('-', ' ')
                val candidateText = listOf(titleAttr, text, slugText).filter { it.isNotBlank() }.joinToString(" ")
                val score = NativeProviderParsers.titleScore(query, candidateText)
                if (score > maxScore) {
                    maxScore = score
                    bestUrl = href
                }
            }
            if (bestUrl != null && maxScore >= 0.15) return bestUrl
            if (cards.size == 1) {
                val firstHref = cards[0].attr("href")
                if (firstHref.startsWith(baseUrl)) return firstHref
            }
        }

        // ── Fallback: Generic link scoring via Jsoup DOM ──
        var bestUrl: String? = null
        var maxScore = 0.0
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            val text = a.text()
            val title = a.attr("title")
            val candidateText = listOf(text, title, href.replace('-', ' ')).filter { it.isNotBlank() }.joinToString(" ")
            if (!href.contains(".js") && !href.contains(".css") &&
                href.startsWith(baseUrl) && !href.contains("page/") &&
                !href.contains("/genres/") && !href.contains("/network/") &&
                !href.contains("/studio/") && !href.contains("/country/")) {
                val score = NativeProviderParsers.titleScore(query, candidateText)
                if (score > maxScore && score >= 0.15) {
                    maxScore = score
                    bestUrl = NativeProviderParsers.absoluteUrl(baseUrl, href)
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
        val doc = org.jsoup.Jsoup.parse(html)

        // Target <div class="eplister"> or <ul id="episode_list"> specifically via Jsoup CSS selectors
        val anchors = doc.select("div.eplister ul li a, ul#episode_list li a, .eplister a")
            .ifEmpty { doc.select("a[href*=-episode-], a[href*=-ep-]") }

        for (anchor in anchors) {
            val href = anchor.attr("href")
            if (href.isBlank()) continue

            val innerText = anchor.text()
            val titleAttr = anchor.attr("title")
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
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
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
     * Extracts playable stream items from an episode watch link using Jsoup.
     */
    private fun extractStreamsFromLink(link: EpisodeLink): List<StreamItem> {
        val html = fetchHtml(link.url) ?: return emptyList()
        val doc = org.jsoup.Jsoup.parse(html)
        val streams = ArrayList<StreamItem>()

        // 1. Jsoup option selection for AnimeStream select.mirror or option elements
        for (option in doc.select("select.mirror option, option[value]")) {
            val rawValue = option.attr("value").trim()
            val serverName = option.text().trim()
            if (rawValue.isBlank() || serverName.contains("Select Video Server", ignoreCase = true)) continue

            val cleanValue = rawValue.replace("\n", "").replace("\r", "").replace(" ", "")

            val decodedHtml = runCatching {
                String(Base64.decode(cleanValue, Base64.DEFAULT), StandardCharsets.UTF_8)
            }.getOrNull() ?: runCatching {
                String(Base64.decode(cleanValue, Base64.URL_SAFE), StandardCharsets.UTF_8)
            }.getOrNull() ?: rawValue

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

        // 2. Direct iframe embeds via Jsoup doc.select("iframe[src]")
        if (streams.isEmpty()) {
            for (iframe in doc.select("iframe[src]")) {
                val iframeUrl = iframe.attr("src")
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

        // 1. StreamPlay (play.streamplay.co.in) — unpacked kaken API resolver (from donghuastream_fixed.dart)
        if (embedUrl.contains("streamplay.co.in")) {
            val videoId = embedUrl.substringAfter("/embed/").substringBefore("?").substringBefore("#")
            if (videoId.isNotBlank()) {
                val embedPageUrl = "https://play.streamplay.co.in/embed/$videoId"
                val html = fetchHtml(embedPageUrl, referer = "https://donghuastream.org/")
                if (html != null) {
                    val unpacked = unpackPacker(html)
                    val kakenValue = Regex("""window\.kaken\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                    if (!kakenValue.isNullOrBlank()) {
                        val apiUrl = "https://play.streamplay.co.in/api/?$kakenValue"
                        val apiJson = fetchHtml(apiUrl, referer = "https://play.streamplay.co.in/")
                        if (apiJson != null) {
                            val masterUrl = Regex(""""file":\s*"(https?[^"]+\.m3u8[^"]*)"""").find(apiJson)?.groupValues?.get(1)
                                ?.replace("\\/", "/")
                            if (!masterUrl.isNullOrBlank()) return masterUrl
                        }
                    }
                }
            }
        }

        // 2. StreamWish / SeekPlayer / WishFast / StrWish — JS Packer + AES-128-CBC + Regex (from streamwish.py & Extractors.kt)
        if (embedUrl.contains("seekplayer.") || embedUrl.contains("streamwish") ||
            embedUrl.contains("wishfast") || embedUrl.contains("strwish") ||
            embedUrl.contains("awish") || embedUrl.contains("wishembed") ||
            embedUrl.contains("filemoon") || embedUrl.contains("ahvsh.")) {

            val uri = runCatching { android.net.Uri.parse(embedUrl) }.getOrNull()
            val host = uri?.host ?: ""
            val scheme = uri?.scheme ?: "https"
            val baseUrl = "$scheme://$host"
            val playerId = Regex("""#([a-zA-Z0-9]+)$""").find(embedUrl)?.groupValues?.get(1) ?: ""

            if (playerId.isNotBlank()) {
                val apiRequest = Request.Builder()
                    .url("$baseUrl/api/v1/video?id=$playerId&w=1920&h=1080&r=")
                    .header("Referer", "$baseUrl/")
                    .header("User-Agent", USER_AGENT)
                    .build()
                val apiHex = runCatching {
                    client.newCall(apiRequest).execute().use { it.body?.string() }
                }.getOrNull()?.trim()

                if (!apiHex.isNullOrBlank()) {
                    val decrypted = decryptSeekplayerHex(apiHex, "kiemtienmua911ca", "1234567890oiuytr")
                    if (decrypted.isNotBlank()) {
                        val m3u8Url = Regex(""""cf":\s*"(https?[^"]+)"""").find(decrypted)?.groupValues?.get(1)
                            ?.replace("\\/", "/")
                            ?: Regex(""""source":\s*"(https?[^"]+)"""").find(decrypted)?.groupValues?.get(1)
                                ?.replace("\\/", "/")
                        if (!m3u8Url.isNullOrBlank()) return m3u8Url
                    }
                }
            }

            // Fallback: unpack JS Packer HTML
            val html = fetchHtml(embedUrl)
            if (html != null) {
                val unpacked = unpackPacker(html)
                val m3u8Url = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                    ?: NativeProviderParsers.hlsUrls(unpacked).firstOrNull()
                if (!m3u8Url.isNullOrBlank()) return m3u8Url.replace("\\/", "/")
            }
        }

        // 3. Vtbe (vtbe.to) — JS Packer (from Desktop Animexin Extractors.kt)
        if (embedUrl.contains("vtbe.to")) {
            val html = fetchHtml(embedUrl, referer = "https://vtbe.to/")
            if (html != null) {
                val unpacked = unpackPacker(html)
                val link = Regex("""sources:\[\{file:"(.*?)"\""").find(unpacked)?.groupValues?.get(1)
                if (!link.isNullOrBlank()) return link.replace("\\/", "/")
            }
        }

        // 4. Dailymotion — player metadata API (from animexin_extension.dart)
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
                val masterUrl = Regex(""""url":\s*"(https[^"]+\.m3u8[^"]*)"""").find(metaJson)?.groupValues?.get(1)
                    ?.replace("\\u0026", "&")
                if (!masterUrl.isNullOrBlank()) return masterUrl
            }
        }

        if (embedUrl.contains("ok.ru/videoembed/")) return embedUrl

        if (embedUrl.contains("sblona.") || embedUrl.contains("streamsb.")) {
            val html = fetchHtml(embedUrl) ?: return null
            val m3u8List = NativeProviderParsers.hlsUrls(html)
            if (m3u8List.isNotEmpty()) return m3u8List.first()
        }

        if (embedUrl.contains("dood.") || embedUrl.contains("dooo")) return embedUrl

        return null
    }

    private fun unpackPacker(js: String): String {
        val match = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\)\((.*?)\)""").find(js) ?: return js
        val argsStr = match.groupValues[1]
        val argsPattern = Regex("""'(.*?)',(.*?),(.*?),'(.*?)'\.split""")
        val m = argsPattern.find(argsStr) ?: return js
        var p = m.groupValues[1]
        val a = m.groupValues[2].toIntOrNull() ?: 10
        val c = m.groupValues[3].toIntOrNull() ?: 0
        val k = m.groupValues[4].split('|')
        for (i in c - 1 downTo 0) {
            val word = if (i < k.size && k[i].isNotBlank()) k[i] else i.toString(a)
            p = p.replace(Regex("""\b""" + i.toString(a) + """\b"""), word)
        }
        return p
    }

    private fun decryptSeekplayerHex(hexCipher: String, keyStr: String, ivStr: String): String {
        return runCatching {
            val key = javax.crypto.spec.SecretKeySpec(keyStr.toByteArray(StandardCharsets.UTF_8), "AES")
            val iv = javax.crypto.spec.IvParameterSpec(ivStr.toByteArray(StandardCharsets.UTF_8))
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, iv)
            val cipherBytes = hexToBytes(hexCipher)
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len - 1) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun fetchHtml(url: String, referer: String? = null): String? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
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
