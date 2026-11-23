package com.bmkf.tv.data.source

import com.bmkf.tv.data.model.MediaType
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.model.StreamSource
import com.bmkf.tv.data.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder

/**
 * Client for Gà Vàng TV (`https://gavang69.com/` -> auto-follows 301/302 redirects to active mirror).
 * Extracts live & upcoming Vietnamese-commentary football + basketball (NBA) matches and resolves
 * direct ad-free Tencent/Alibaba CDN HLS (`.m3u8`) and HTTP-FLV (`.flv`) streams for ExoPlayer.
 */
class GaVangClient(
    private val entryUrlProvider: () -> String
) {
    data class ParsedMatch(
        val path: String,
        val title: String,
        val league: String,
        val kickoffTime: String,
        val statusText: String,
        val sport: String,
        val isLive: Boolean,
        val isFeatured: Boolean,
        val isHot: Boolean,
        val streamerCount: Int,
        val logoUrl: String
    ) {
        fun toMovieItem(): MovieItem {
            val badge = when {
                isLive -> "🐔 LIVE"
                isFeatured || isHot -> "🔥 HOT"
                else -> kickoffTime.ifBlank { sportLabel(sport) }
            }
            val blvLabel = if (streamerCount > 0) "$streamerCount BLV" else ""
            val timeOrStatus = if (isLive && statusText.isNotBlank()) statusText else kickoffTime
            val meta = listOf(league, timeOrStatus, blvLabel)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" • ")

            return MovieItem(
                source = SourceId.SPORT_TV,
                id = "$GV_ID_PREFIX$path",
                title = if (isLive) "🔴 $title" else title,
                originTitle = meta,
                posterUrl = logoUrl,
                backdropUrl = logoUrl,
                quality = badge,
                episodeCurrent = timeOrStatus,
                mediaType = MediaType.TV,
                description = "$title — $meta (Gà Vàng TV BLV Tiếng Việt)"
            )
        }
    }

    @Volatile
    private var lastResolvedOrigin: String? = null

    private var cachedTimestampMs: Long = 0L
    private var cachedMatches: List<ParsedMatch> = emptyList()

    suspend fun fetchMatches(
        filter: String,
        bypassCache: Boolean = false
    ): List<MovieItem> {
        val all = loadAllMatches(bypassCache)
        val filtered = when (filter) {
            "live" -> all.filter { it.isLive }.ifEmpty { all.filter { it.sport == "football" }.take(30) }
            "football" -> all.filter { it.sport == "football" }
            "basketball" -> all.filter { it.sport == "basketball" }
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

    suspend fun resolveMatchStreams(storedId: String): List<StreamSource> {
        val matchPath = storedId.removePrefix(GV_ID_PREFIX)
        val origin = lastResolvedOrigin ?: resolveActiveOrigin()
        val watchUrl = if (matchPath.startsWith("http://") || matchPath.startsWith("https://")) {
            matchPath
        } else {
            "${origin.trimEnd('/')}/${matchPath.trimStart('/')}"
        }

        val (finalWatchUrl, watchHtml) = fetchWithFinalUrl(watchUrl)
        val activeOrigin = extractOrigin(finalWatchUrl) ?: origin
        lastResolvedOrigin = activeOrigin

        return parseWatchPageStreams(watchHtml, activeOrigin)
    }

    private suspend fun loadAllMatches(bypassCache: Boolean): List<ParsedMatch> {
        val now = System.currentTimeMillis()
        if (!bypassCache && cachedMatches.isNotEmpty() && now - cachedTimestampMs < MATCH_CACHE_TTL_MS) {
            return cachedMatches
        }

        val entry = entryUrlProvider().trim().ifBlank { "https://gavang69.com/" }
        val (finalUrl, html) = fetchWithFinalUrl(entry)
        extractOrigin(finalUrl)?.let { lastResolvedOrigin = it }

        val parsed = parseHomepageMatches(html)
        if (parsed.isNotEmpty()) {
            cachedMatches = parsed
            cachedTimestampMs = System.currentTimeMillis()
        }
        return parsed
    }

    private suspend fun resolveActiveOrigin(): String {
        val entry = entryUrlProvider().trim().ifBlank { "https://gavang69.com/" }
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
        val cardStarts = MATCH_CARD_START_REGEX.findAll(html).toList()
        val rawMap = LinkedHashMap<String, ParsedMatch>()

        for (i in cardStarts.indices) {
            val start = cardStarts[i].range.first
            val end = if (i + 1 < cardStarts.size) {
                cardStarts[i + 1].range.first
            } else {
                (start + 7000).coerceAtMost(html.length)
            }
            val block = html.substring(start, end.coerceAtMost(start + 7000))

            val rawHref = HREF_REGEX.find(block)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val normalizedPath = normalizeMatchPath(rawHref)
            if (normalizedPath.isBlank()) continue
            if (rawMap.containsKey(normalizedPath)) continue

            val sport = when {
                normalizedPath.startsWith("/basketball/") -> "basketball"
                DATA_SPORT_REGEX.find(block)?.groupValues?.getOrNull(1).equals("basketball", ignoreCase = true) -> "basketball"
                else -> "football"
            }

            val headerChunk = block.take(360)
            val hasLiveClass = headerChunk.contains("bals-live-match")
            val livePillHidden = LIVE_PILL_HIDDEN_REGEX.containsMatchIn(block)
            val isLive = hasLiveClass && !livePillHidden

            val accent = DATA_ACCENT_REGEX.find(block)?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
            val isFeatured = accent == "featured"
            val isHot = accent == "hot"

            val streamerIdsAttr = DATA_STREAMER_IDS_REGEX.find(block)?.groupValues?.getOrNull(1).orEmpty()
            val streamerCount = STREAMER_HEX_ID_REGEX.findAll(streamerIdsAttr).count()

            val homeName = HOME_NAME_REGEX.find(block)?.groupValues?.getOrNull(1)?.let(::cleanHtmlText).orEmpty()
            val awayName = AWAY_NAME_REGEX.find(block)?.groupValues?.getOrNull(1)?.let(::cleanHtmlText).orEmpty()
            val dataTitle = DATA_TITLE_REGEX.find(block)?.groupValues?.getOrNull(1)?.let(::cleanHtmlText).orEmpty()

            val displayTitle = when {
                homeName.isNotBlank() && awayName.isNotBlank() -> "$homeName vs $awayName"
                dataTitle.isNotBlank() -> dataTitle.substringBefore(" lúc ").trim()
                else -> normalizedPath.trim('/').substringAfterLast('/').replace('-', ' ')
            }

            val league = LEAGUE_NAME_REGEX.find(block)?.groupValues?.getOrNull(1)?.let(::cleanHtmlText).orEmpty()

            val kickoffTime = extractKickoffTime(block, dataTitle)
            val statusName = STATUS_NAME_REGEX.find(block)?.groupValues?.getOrNull(1)?.let(::cleanHtmlText).orEmpty()
            val elapsed = ELAPSED_TIME_REGEX.find(block)?.groupValues?.getOrNull(1)?.let(::cleanHtmlText).orEmpty()
            val statusText = when {
                !isLive || statusName.equals("NS", ignoreCase = true) -> ""
                statusName.isNotBlank() && elapsed.isNotBlank() && elapsed != "-" -> "$statusName $elapsed"
                statusName.isNotBlank() -> statusName
                else -> ""
            }

            val logoUrl = TEAM_LOGO_REGEX.find(block)?.groupValues?.getOrNull(1)
                ?: COMP_LOGO_REGEX.find(block)?.groupValues?.getOrNull(1)
                ?: ""

            rawMap[normalizedPath] = ParsedMatch(
                path = normalizedPath,
                title = displayTitle,
                league = league,
                kickoffTime = kickoffTime,
                statusText = statusText,
                sport = sport,
                isLive = isLive,
                isFeatured = isFeatured,
                isHot = isHot,
                streamerCount = streamerCount,
                logoUrl = logoUrl
            )
        }

        val all = rawMap.values.toList()
        // Only show matches that have BLV livestreams (matches with 0 streamers only have 2D livescore trackers).
        // Keep full list as a safety fallback if HTML ever omits data-streamer-ids altogether.
        val playable = all.filter { it.streamerCount > 0 }.ifEmpty { all }

        return playable.sortedWith(
            compareByDescending<ParsedMatch> { it.isLive }
                .thenByDescending { it.isFeatured }
                .thenByDescending { it.isHot }
                .thenByDescending { it.streamerCount }
        )
    }

    internal fun parseWatchPageStreams(watchHtml: String, activeOrigin: String): List<StreamSource> {
        val hlsStreams = mutableListOf<StreamSource>()
        val flvStreams = mutableListOf<StreamSource>()
        val baseHeaders = mapOf(
            "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
            "Referer" to "${activeOrigin.trimEnd('/')}/",
            "Origin" to activeOrigin.trimEnd('/')
        )

        val cards = COMMENTATOR_CARD_REGEX.findAll(watchHtml).toList()
        cards.forEachIndexed { idx, match ->
            val tag = match.value
            val blvName = ATTR_STREAM_NAME.find(tag)?.groupValues?.getOrNull(1)
                ?.let(::cleanHtmlText)
                ?.takeIf { it.isNotBlank() }
                ?: "Server ${idx + 1}"
            val cdnRaw = ATTR_CDN.find(tag)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val cdnLabel = when (cdnRaw.lowercase()) {
                "tencent" -> "Tencent"
                "alibaba" -> "Alibaba"
                "" -> ""
                else -> cdnRaw.replaceFirstChar { it.uppercase() }
            }
            val suffix = if (cdnLabel.isNotBlank()) " • $cdnLabel" else ""

            val hlsUrl = ATTR_STREAM_URL.find(tag)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (hlsUrl.startsWith("http://") || hlsUrl.startsWith("https://")) {
                hlsStreams += StreamSource(
                    name = "🐔 BLV $blvName (HLS$suffix)",
                    url = hlsUrl,
                    isHls = true,
                    isWebViewFallback = false,
                    headers = baseHeaders
                )
            }

            val flvUrl = ATTR_STREAM_URL_FLV.find(tag)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (flvUrl.startsWith("http://") || flvUrl.startsWith("https://")) {
                flvStreams += StreamSource(
                    name = "🐔 BLV $blvName (FLV$suffix)",
                    url = flvUrl,
                    isHls = false,
                    isWebViewFallback = false,
                    headers = baseHeaders
                )
            }
        }

        // Put all HLS streams first, then FLV alternatives (StreamSpeedTester benchmarks both in parallel!)
        val primary = (hlsStreams + flvStreams).distinctBy { it.url }
        if (primary.isNotEmpty()) return primary

        // Fallback 1: URL-encoded hls= / flv= inside playyyz iframe URLs
        val fallback = mutableListOf<StreamSource>()
        ENCODED_MEDIA_PARAM_REGEX.findAll(watchHtml).forEachIndexed { idx, m ->
            val proto = m.groupValues[1].lowercase()
            val decoded = runCatching { URLDecoder.decode(m.groupValues[2], "UTF-8") }.getOrNull().orEmpty()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                val isHls = proto == "hls" || decoded.contains(".m3u8", ignoreCase = true)
                fallback += StreamSource(
                    name = "🐔 Gà Vàng ${if (isHls) "HLS" else "FLV"} #${idx + 1}",
                    url = decoded,
                    isHls = isHls,
                    isWebViewFallback = false,
                    headers = baseHeaders
                )
            }
        }
        if (fallback.isNotEmpty()) return fallback.distinctBy { it.url }

        // Fallback 2: any raw .m3u8 or .flv URL directly embedded in HTML
        RAW_MEDIA_URL_REGEX.findAll(watchHtml).forEachIndexed { idx, m ->
            val u = m.value.replace("\\/", "/")
            if (!u.contains("tvc-", ignoreCase = true) && !u.contains("ads", ignoreCase = true)) {
                val isHls = u.contains(".m3u8", ignoreCase = true)
                fallback += StreamSource(
                    name = "🐔 Gà Vàng Direct #${idx + 1}",
                    url = u,
                    isHls = isHls,
                    isWebViewFallback = false,
                    headers = baseHeaders
                )
            }
        }
        return fallback.distinctBy { it.url }
    }

    private fun extractKickoffTime(block: String, dataTitle: String): String {
        val timeMatch = MATCH_TIME_BLOCK_REGEX.find(block)
        if (timeMatch != null) {
            val hm = timeMatch.groupValues[1].trim()
            val dm = timeMatch.groupValues[2].trim()
            if (hm.isNotBlank() && dm.isNotBlank()) return "$hm • $dm"
            if (hm.isNotBlank()) return hm
        }
        val titleTime = TITLE_TIME_REGEX.find(dataTitle)?.groupValues?.getOrNull(1)?.trim()
        return titleTime.orEmpty()
    }

    private fun normalizeMatchPath(href: String): String {
        val path = if (href.startsWith("http://") || href.startsWith("https://")) {
            runCatching { URI(href).path.orEmpty() }.getOrDefault("")
        } else {
            href
        }
        return if (path.startsWith("/truc-tiep/") || path.startsWith("/basketball/")) path else ""
    }

    private fun extractOrigin(url: String): String? {
        return runCatching {
            val uri = URI(url)
            if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else null
        }.getOrNull()
    }

    private fun cleanHtmlText(raw: String): String {
        return HttpClient.stripHtml(raw)
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .trim()
    }

    companion object {
        const val GV_ID_PREFIX = "gv::"
        private const val MATCH_CACHE_TTL_MS = 30 * 1000L

        private val MATCH_CARD_START_REGEX = Regex("""<div[^>]+class="[^"]*\bmatch-card\b[^"]*"""")
        private val HREF_REGEX = Regex("""href="((?:https?://[^"]+)?/(?:truc-tiep|basketball)/[^"]+)"""")
        private val DATA_SPORT_REGEX = Regex("""data-sport="([^"]+)"""")
        private val DATA_ACCENT_REGEX = Regex("""data-accent="([^"]+)"""")
        private val DATA_STREAMER_IDS_REGEX = Regex("""data-streamer-ids="([^"]*)"""")
        private val STREAMER_HEX_ID_REGEX = Regex("""[0-9a-fA-F]{20,}""")
        private val DATA_TITLE_REGEX = Regex("""data-title="([^"]+)"""")
        private val LIVE_PILL_HIDDEN_REGEX = Regex("""bals-live-pill[^>]*style="[^"]*display\s*:\s*none""")

        private val LEAGUE_NAME_REGEX = Regex("""bals-competition-name[^>]*>([^<]+)<""")
        private val HOME_NAME_REGEX = Regex("""bals-home-team-name[^>]*>([\s\S]*?)</div>""")
        private val AWAY_NAME_REGEX = Regex("""bals-away-team-name[^>]*>([\s\S]*?)</div>""")
        private val STATUS_NAME_REGEX = Regex("""bals-status-name[^>]*>([^<]+)<""")
        private val ELAPSED_TIME_REGEX = Regex("""bals-elapsed-time[^>]*>([^<]+)<""")
        private val MATCH_TIME_BLOCK_REGEX = Regex(
            """bals-match-time[^>]*>[\s\S]*?<span>\s*([0-9]{1,2}:[0-9]{2})\s*</span>[\s\S]*?<span>\s*([0-9]{1,2}/[0-9]{1,2})\s*</span>"""
        )
        private val TITLE_TIME_REGEX = Regex("""lúc\s+([0-9]{1,2}:[0-9]{2})""")
        private val TEAM_LOGO_REGEX = Regex("""(https?://static\.sportsfeed\.pro/[^\s"'<>]+)""")
        private val COMP_LOGO_REGEX = Regex("""(https?://img\.thesports\.com/[^\s"'<>]+)""")

        private val COMMENTATOR_CARD_REGEX = Regex("""<div[^>]+class="[^"]*\bcommentator-card\b[^"]*"[^>]*>""")
        private val ATTR_STREAM_URL = Regex("""data-stream-url="([^"]+)"""")
        private val ATTR_STREAM_URL_FLV = Regex("""data-stream-url-flv="([^"]+)"""")
        private val ATTR_STREAM_NAME = Regex("""data-stream-name="([^"]+)"""")
        private val ATTR_CDN = Regex("""data-cdn="([^"]+)"""")
        private val ENCODED_MEDIA_PARAM_REGEX = Regex("""(hls|flv)=(https?%3A%2F%2F[^&"'<>\\]+)""", RegexOption.IGNORE_CASE)
        private val RAW_MEDIA_URL_REGEX = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|flv)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

        fun isGaVangId(id: String): Boolean = id.startsWith(GV_ID_PREFIX)

        private fun sportLabel(sport: String): String = when (sport.lowercase()) {
            "basketball" -> "Bóng rổ"
            else -> "Bóng đá"
        }
    }
}
