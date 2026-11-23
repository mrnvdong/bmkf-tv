package com.bmkf.tv.data.source

import com.bmkf.tv.data.model.MediaType
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.model.StreamSource
import com.bmkf.tv.data.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URI

/**
 * Client for Xôi Lạc TV (`https://xoilacz.ca` -> auto-follows 301 to active mirror e.g. `xoilaczda.tv`).
 * Extracts live Vietnamese-commentary football & multi-sport matches and resolves clean ad-free
 * direct HTTP-FLV / HLS (`var urlStream`) streams for native ExoPlayer playback.
 */
class XoiLacClient(
    private val entryUrlProvider: () -> String
) {
    data class ParsedMatch(
        val path: String,
        val title: String,
        val league: String,
        val kickoffTime: String,
        val sport: String,
        val statusCode: Int,
        val isHot: Boolean,
        val logoUrl: String
    ) {
        val isLive: Boolean get() = statusCode in 2..7

        fun toMovieItem(): MovieItem {
            val badge = when {
                isLive -> "🇻🇳 LIVE"
                isHot -> "🔥 HOT"
                else -> kickoffTime.ifBlank { sportLabel(sport) }
            }
            val meta = listOf(league, kickoffTime, sportLabel(sport))
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" • ")

            return MovieItem(
                source = SourceId.SPORT_TV,
                id = "$XL_ID_PREFIX$path",
                title = if (isLive) "🔴 $title" else title,
                originTitle = meta,
                posterUrl = logoUrl,
                backdropUrl = logoUrl,
                quality = badge,
                episodeCurrent = kickoffTime,
                mediaType = MediaType.TV,
                description = "$title — $meta (Xôi Lạc TV BLV Tiếng Việt)"
            )
        }
    }

    @Volatile
    private var lastResolvedOrigin: String? = null

    private var cachedHtmlTimestampMs: Long = 0L
    private var cachedMatches: List<ParsedMatch> = emptyList()

    suspend fun fetchMatches(
        filter: String,
        bypassCache: Boolean = false
    ): List<MovieItem> {
        val all = loadAllMatches(bypassCache)
        val filtered = when (filter) {
            "live" -> all.filter { it.isLive }.ifEmpty { all.filter { it.sport == "football" }.take(30) }
            "football" -> all.filter { it.sport == "football" }
            "other" -> all.filter { it.sport != "football" }
            else -> all
        }
        return filtered.map { it.toMovieItem() }
    }

    suspend fun searchMatches(query: String): List<MovieItem> {
        val clean = query.trim().lowercase()
        if (clean.length < 2) return emptyList()
        val all = loadAllMatches(bypassCache = false)
        return all.filter {
            it.title.lowercase().contains(clean) || it.league.lowercase().contains(clean)
        }.map { it.toMovieItem() }
    }

    suspend fun resolveMatchStreams(storedId: String): List<StreamSource> = coroutineScope {
        val matchPath = storedId.removePrefix(XL_ID_PREFIX)
        val origin = lastResolvedOrigin ?: resolveActiveOrigin()
        val watchUrl = if (matchPath.startsWith("http")) {
            matchPath
        } else {
            "${origin.trimEnd('/')}/${matchPath.trimStart('/')}"
        }

        val (finalWatchUrl, watchHtml) = fetchWithFinalUrl(watchUrl)
        val activeOrigin = extractOrigin(finalWatchUrl) ?: origin
        lastResolvedOrigin = activeOrigin

        val serverLabels = parseServerLabels(watchHtml)
        val embedUrls = parseListStreamUrls(watchHtml)
        if (embedUrls.isEmpty()) return@coroutineScope emptyList()

        // Fetch up to 6 distinct Xôi Lạc server embed pages in parallel with required Referer header
        // (avoids hammering origin on big matches with 15+ duplicate entries and skips dead 5xx WebView pages)
        val resolvedStreams = embedUrls.take(6).mapIndexed { idx, embedUrl ->
            async {
                val label = serverLabels.getOrNull(idx) ?: "Xôi Lạc Server ${idx + 1}"
                val embedOrigin = extractOrigin(embedUrl) ?: activeOrigin
                val result = mutableListOf<StreamSource>()

                val embedHtml = runCatching {
                    HttpClient.getString(
                        embedUrl,
                        headers = mapOf(
                            "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
                            "Referer" to "$activeOrigin/"
                        )
                    )
                }.getOrNull()

                if (!embedHtml.isNullOrBlank()) {
                    val directUrl = extractUrlStream(embedHtml)
                    if (!directUrl.isNullOrBlank()) {
                        result += StreamSource(
                            name = "🇻🇳 $label (Xôi Lạc)",
                            url = directUrl,
                            isHls = directUrl.contains(".m3u8", ignoreCase = true),
                            isWebViewFallback = false,
                            headers = mapOf(
                                "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
                                "Referer" to "$embedOrigin/",
                                "Origin" to embedOrigin
                            )
                        )
                    } else if (!embedHtml.contains("error code: 5", ignoreCase = true)) {
                        // Only keep WebView backup when the embed page itself actually returned HTTP 200 HTML
                        result += StreamSource(
                            name = "🇻🇳 $label (Web)",
                            url = embedUrl,
                            isHls = false,
                            isWebViewFallback = true,
                            headers = mapOf("Referer" to "$activeOrigin/")
                        )
                    }
                }
                result
            }
        }.awaitAll().flatten()

        resolvedStreams.distinctBy { it.url to it.isWebViewFallback }
    }

    private suspend fun loadAllMatches(bypassCache: Boolean): List<ParsedMatch> {
        val now = System.currentTimeMillis()
        if (!bypassCache && cachedMatches.isNotEmpty() && now - cachedHtmlTimestampMs < MATCH_CACHE_TTL_MS) {
            return cachedMatches
        }

        val entry = entryUrlProvider().trim().ifBlank { "https://xoilacz.ca" }
        val (finalUrl, html) = fetchWithFinalUrl(entry)
        extractOrigin(finalUrl)?.let { lastResolvedOrigin = it }

        val parsed = parseHomepageMatches(html)
        if (parsed.isNotEmpty()) {
            cachedMatches = parsed
            cachedHtmlTimestampMs = System.currentTimeMillis()
        }
        return parsed
    }

    private suspend fun resolveActiveOrigin(): String {
        val entry = entryUrlProvider().trim().ifBlank { "https://xoilacz.ca" }
        return runCatching {
            val (finalUrl, _) = fetchWithFinalUrl(entry)
            extractOrigin(finalUrl) ?: entry.trimEnd('/')
        }.getOrDefault(entry.trimEnd('/'))
    }

    private suspend fun fetchWithFinalUrl(url: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClient.DEFAULT_USER_AGENT)
            .get()
            .build()
        HttpClient.okHttp.newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val body = resp.body?.string().orEmpty()
            finalUrl to body
        }
    }

    internal fun parseHomepageMatches(html: String): List<ParsedMatch> {
        val results = LinkedHashMap<String, ParsedMatch>()
        val blockStarts = MATCH_BLOCK_REGEX.findAll(html).toList()

        for (i in blockStarts.indices) {
            val start = blockStarts[i].range.first
            val end = if (i + 1 < blockStarts.size) {
                blockStarts[i + 1].range.first
            } else {
                (start + 6000).coerceAtMost(html.length)
            }
            val block = html.substring(start, end.coerceAtMost(start + 6000))

            val href = HREF_REGEX.find(block)?.groupValues?.getOrNull(1)?.trim() ?: continue
            if (!href.contains("/truc-tiep/") || href.contains("/link/")) continue
            if (results.containsKey(href)) continue

            val sport = DATA_SPORT_REGEX.find(block)?.groupValues?.getOrNull(1)?.lowercase() ?: "football"
            val statusCode = DATA_STATUS_REGEX.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val isHot = DATA_HOT_REGEX.find(block)?.groupValues?.getOrNull(1) == "1"

            val homeName = HOME_NAME_REGEX.find(block)?.groupValues?.getOrNull(1)?.let(HttpClient::stripHtml).orEmpty()
            val awayName = AWAY_NAME_REGEX.find(block)?.groupValues?.getOrNull(1)?.let(HttpClient::stripHtml).orEmpty()
            val rawTitle = TITLE_ATTR_REGEX.find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            val displayTitle = when {
                homeName.isNotBlank() && awayName.isNotBlank() -> "$homeName vs $awayName"
                rawTitle.isNotBlank() -> rawTitle.substringBefore(" lúc ").trim()
                else -> href.trim('/').substringAfterLast('/').replace('-', ' ')
            }

            val league = LEAGUE_NAME_REGEX.find(block)?.groupValues?.getOrNull(1)?.let(HttpClient::stripHtml).orEmpty()
            val kickoff = DATE_REGEX.find(block)?.groupValues?.getOrNull(1)?.let(HttpClient::stripHtml).orEmpty()
            val logoUrl = TEAM_LOGO_REGEX.find(block)?.groupValues?.getOrNull(1).orEmpty()

            results[href] = ParsedMatch(
                path = href,
                title = displayTitle,
                league = league,
                kickoffTime = kickoff,
                sport = sport,
                statusCode = statusCode,
                isHot = isHot,
                logoUrl = logoUrl
            )
        }

        // Order: LIVE first -> HOT next -> remaining in page order
        return results.values.sortedWith(
            compareByDescending<ParsedMatch> { it.isLive }
                .thenByDescending { it.isHot }
        )
    }

    internal fun parseListStreamUrls(watchHtml: String): List<String> {
        val rawArray = LIST_STREAM_REGEX.find(watchHtml)?.groupValues?.getOrNull(1) ?: return emptyList()
        val urls = mutableListOf<String>()
        runCatching {
            val outer = HttpClient.json.parseToJsonElement(rawArray).jsonArray
            outer.forEach { entry ->
                when (entry) {
                    is JsonArray -> entry.forEach { item ->
                        item.jsonPrimitive.contentOrNull
                            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                            ?.let { urls += it }
                    }
                    else -> entry.jsonPrimitive.contentOrNull
                        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?.let { urls += it }
                }
            }
        }
        return urls.distinct()
    }

    internal fun parseServerLabels(watchHtml: String): List<String> {
        return SERVER_LABEL_REGEX.findAll(watchHtml)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf { s -> s.isNotBlank() } }
            .toList()
    }

    internal fun extractUrlStream(embedHtml: String): String? {
        val explicit = URL_STREAM_VAR_REGEX.find(embedHtml)?.groupValues?.getOrNull(1)?.trim()
        if (!explicit.isNullOrBlank() && (explicit.startsWith("http://") || explicit.startsWith("https://"))) {
            return explicit.replace("\\/", "/")
        }
        // Fallback: find any .m3u8 or .flv URL that is NOT an ad video (tvc-)
        val fallback = MEDIA_URL_REGEX.findAll(embedHtml)
            .map { it.value.replace("\\/", "/") }
            .firstOrNull { !it.contains("tvc-", ignoreCase = true) && !it.contains("ads", ignoreCase = true) }
        return fallback
    }

    private fun extractOrigin(url: String): String? {
        return runCatching {
            val uri = URI(url)
            if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else null
        }.getOrNull()
    }

    companion object {
        const val XL_ID_PREFIX = "xl::"
        private const val MATCH_CACHE_TTL_MS = 30 * 1000L

        private val MATCH_BLOCK_REGEX = Regex("""<div[^>]+class="[^"]*grid-matches__item-match[^"]*"""")
        private val HREF_REGEX = Regex("""href="(/truc-tiep/[^"]+)"""")
        private val TITLE_ATTR_REGEX = Regex("""title="([^"]+)"""")
        private val DATA_SPORT_REGEX = Regex("""data-sport="([^"]+)"""")
        private val DATA_STATUS_REGEX = Regex("""data-status="(\d+)"""")
        private val DATA_HOT_REGEX = Regex("""data-hot="(\d+)"""")
        private val LEAGUE_NAME_REGEX = Regex("""class="grid-match__league"[\s\S]*?<span[^>]*>([\s\S]*?)</span>""")
        private val DATE_REGEX = Regex("""class="grid-match__date">([^<]+)<""")
        private val HOME_NAME_REGEX = Regex("""grid-match__team--home-name">([^<]+)<""")
        private val AWAY_NAME_REGEX = Regex("""grid-match__team--away-name">([^<]+)<""")
        private val TEAM_LOGO_REGEX = Regex("""(https?://imgts\.[^\s"'<>]+)""")

        private val LIST_STREAM_REGEX = Regex("""var\s+list_stream\s*=\s*(\[[\s\S]*?]);""")
        private val SERVER_LABEL_REGEX = Regex("""title="([^"]+)"\s+data-link="\d+"""")
        private val URL_STREAM_VAR_REGEX = Regex("""var\s+urlStream\s*=\s*["']([^"']+)["']""")
        private val MEDIA_URL_REGEX = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|flv)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

        fun isXoiLacId(id: String): Boolean = id.startsWith(XL_ID_PREFIX)

        private fun sportLabel(sport: String): String = when (sport.lowercase()) {
            "football" -> "Bóng đá"
            "basketball" -> "Bóng rổ"
            "volleyball" -> "Bóng chuyền"
            "badminton" -> "Cầu lông"
            "tennis" -> "Quần vợt"
            "dota2" -> "Dota 2"
            "lol" -> "LMHT"
            "csgo" -> "CS2"
            else -> "Thể thao"
        }
    }
}
