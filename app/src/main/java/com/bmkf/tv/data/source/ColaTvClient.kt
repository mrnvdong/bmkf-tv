package com.bmkf.tv.data.source

import com.bmkf.tv.data.model.MediaType
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.model.StreamSource
import com.bmkf.tv.data.network.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Client for Cola TV (`https://colatv111.live/` -> JSON API `https://api.cltvlv.com`).
 * Fetches live & upcoming Football (`sportId=1`) and Basketball (`sportId=2`) matches, and resolves
 * fresh Vietnamese BLV (`anchorAppointmentVoList` HLS/FLV/multi-CDN) + official broadcast (`video_url`) streams.
 */
class ColaTvClient(
    private val entryUrlProvider: () -> String
) {
    data class ParsedMatch(
        val slug: String,
        val matchId: String,
        val title: String,
        val league: String,
        val sportId: Int,
        val matchStatus: Int,
        val matchTimeSec: Long,
        val scoreText: String,
        val blvNames: List<String>,
        val logoUrl: String,
        val streams: List<StreamSource>
    ) {
        val isLive: Boolean get() = matchStatus == 2

        fun toMovieItem(): MovieItem {
            val kickoff = formatEpoch(matchTimeSec)
            val badge = when {
                isLive -> "🥤 LIVE"
                blvNames.isNotEmpty() -> "🔥 BLV"
                else -> kickoff.ifBlank { if (sportId == 2) "Bóng rổ" else "Bóng đá" }
            }
            val displayTitle = when {
                isLive && scoreText.isNotBlank() -> "🔴 $title ($scoreText)"
                isLive -> "🔴 $title"
                else -> title
            }
            val blvLabel = blvNames.take(2).joinToString(", ")
            val meta = listOf(league, if (isLive) "Đang đấu" else kickoff, blvLabel)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" • ")

            return MovieItem(
                source = SourceId.SPORT_TV,
                id = "$CL_ID_PREFIX$slug",
                title = displayTitle,
                originTitle = meta,
                posterUrl = logoUrl,
                backdropUrl = logoUrl,
                quality = badge,
                episodeCurrent = kickoff,
                mediaType = MediaType.TV,
                description = "$title — $meta (Cola TV BLV Tiếng Việt)"
            )
        }
    }

    private var cachedTimestampMs: Long = 0L
    private var cachedMatches: List<ParsedMatch> = emptyList()

    private fun resolveDomainAndApi(): Pair<String, String> {
        val raw = entryUrlProvider().trim().ifBlank { "https://colatv111.live/" }
        val host = runCatching { URI(raw).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "colatv111.live"
        // If user puts a custom API domain directly (e.g. https://api.xxx.com), call it directly;
        // otherwise use Cola TV's backend API with X-Domain set to the configured site domain.
        val apiBase = if (host.startsWith("api.", ignoreCase = true)) {
            raw.trimEnd('/')
        } else {
            DEFAULT_API_BASE
        }
        return host to apiBase
    }

    private fun apiHeaders(domain: String): Map<String, String> {
        val origin = "https://$domain"
        return mapOf(
            "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
            "X-Domain" to domain,
            "Referer" to "$origin/",
            "Origin" to origin
        )
    }

    suspend fun fetchMatches(
        filter: String,
        bypassCache: Boolean = false
    ): List<MovieItem> {
        val all = loadAllMatches(bypassCache)
        val filtered = when (filter) {
            "live" -> all.filter { it.isLive }.ifEmpty { all.filter { it.streams.isNotEmpty() }.take(30) }
            "football" -> all.filter { it.sportId != 2 }
            "basketball" -> all.filter { it.sportId == 2 }
            else -> all
        }
        return filtered.map { it.toMovieItem() }
    }

    suspend fun searchMatches(query: String): List<MovieItem> {
        val clean = query.trim().lowercase()
        if (clean.length < 2) return emptyList()
        val all = loadAllMatches(bypassCache = false)
        return all.filter {
            it.title.lowercase().contains(clean) ||
                it.league.lowercase().contains(clean) ||
                it.blvNames.any { b -> b.lowercase().contains(clean) }
        }.map { it.toMovieItem() }
    }

    suspend fun resolveMatchStreams(storedId: String): List<StreamSource> {
        val slug = storedId.removePrefix(CL_ID_PREFIX).trim('/')
        val (domain, apiBase) = resolveDomainAndApi()
        val headers = apiHeaders(domain)

        // Fetch fresh match detail first so signed auth_key tokens on video_url & BLV streams are up to date
        val detailJson = runCatching {
            HttpClient.getString("$apiBase/api/match/$slug", headers = headers)
        }.getOrNull()

        if (!detailJson.isNullOrBlank()) {
            val fresh = parseMatchDetailStreams(detailJson, domain)
            if (fresh.isNotEmpty()) return fresh
        }

        // Fallback to cached list streams
        val list = loadAllMatches(bypassCache = false)
        return list.firstOrNull { it.slug == slug }?.streams.orEmpty()
    }

    private suspend fun loadAllMatches(bypassCache: Boolean): List<ParsedMatch> {
        val now = System.currentTimeMillis()
        if (!bypassCache && cachedMatches.isNotEmpty() && now - cachedTimestampMs < MATCH_CACHE_TTL_MS) {
            return cachedMatches
        }

        val (domain, apiBase) = resolveDomainAndApi()
        val raw = HttpClient.getString("$apiBase/api/matches", headers = apiHeaders(domain))
        val parsed = parseMatchesList(raw, domain)
        if (parsed.isNotEmpty()) {
            cachedMatches = parsed
            cachedTimestampMs = System.currentTimeMillis()
        }
        return parsed
    }

    internal fun parseMatchesList(rawJson: String, domain: String = "colatv111.live"): List<ParsedMatch> {
        val root = runCatching { HttpClient.json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return emptyList()
        val dataObj = root["data"] as? JsonObject ?: return emptyList()
        val streamHeaders = mapOf(
            "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
            "Referer" to "https://$domain/",
            "Origin" to "https://$domain"
        )

        val matches = dataObj.entries.mapNotNull { (slug, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val matchId = obj["matchId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val sportId = obj["sportId"]?.jsonPrimitive?.intOrNull ?: 1
            val matchStatus = obj["matchStatus"]?.jsonPrimitive?.intOrNull ?: 1
            val matchTimeSec = obj["matchTime"]?.jsonPrimitive?.longOrNull ?: 0L

            val homeName = obj["homeTeamName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val awayName = obj["awayTeamName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val title = if (homeName.isNotBlank() && awayName.isNotBlank()) {
                "$homeName vs $awayName"
            } else {
                slug.replace('-', ' ')
            }
            val league = obj["competitionName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val logoUrl = cleanLogoUrl(obj["homeTeamLogo"]?.jsonPrimitive?.contentOrNull.orEmpty())

            val homeScore = (obj["homeScore"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull
            val awayScore = (obj["awayScore"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull
            val scoreText = if (matchStatus == 2 && homeScore != null && awayScore != null) {
                "$homeScore-$awayScore"
            } else ""

            val (blvNames, streams) = extractStreamsFromMatchObj(obj, streamHeaders)

            ParsedMatch(
                slug = slug,
                matchId = matchId,
                title = title,
                league = league,
                sportId = sportId,
                matchStatus = matchStatus,
                matchTimeSec = matchTimeSec,
                scoreText = scoreText,
                blvNames = blvNames,
                logoUrl = logoUrl,
                streams = streams
            )
        }

        return matches.sortedWith(
            compareByDescending<ParsedMatch> { it.isLive }
                .thenByDescending { it.blvNames.isNotEmpty() }
                .thenByDescending { it.streams.isNotEmpty() }
                .thenBy { if (it.matchTimeSec > 0L) it.matchTimeSec else Long.MAX_VALUE }
        )
    }

    internal fun parseMatchDetailStreams(rawJson: String, domain: String = "colatv111.live"): List<StreamSource> {
        val root = runCatching { HttpClient.json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return emptyList()
        val matchObj = (root["match"] as? JsonObject) ?: (root["data"] as? JsonObject) ?: root
        val streamHeaders = mapOf(
            "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
            "Referer" to "https://$domain/",
            "Origin" to "https://$domain"
        )
        return extractStreamsFromMatchObj(matchObj, streamHeaders).second
    }

    private fun extractStreamsFromMatchObj(
        obj: JsonObject,
        streamHeaders: Map<String, String>
    ): Pair<List<String>, List<StreamSource>> {
        val blvNames = mutableListOf<String>()
        val hlsStreams = mutableListOf<StreamSource>()
        val flvStreams = mutableListOf<StreamSource>()

        val anchors = obj["anchorAppointmentVoList"] as? JsonArray
        anchors?.forEachIndexed { idx, elem ->
            val a = elem as? JsonObject ?: return@forEachIndexed
            val nick = a["nickName"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: "BLV ${idx + 1}"
            blvNames += nick

            // Primary HLS from BLV
            val hls2 = a["playStreamAddress2"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            addStreamIfValid(hls2, "🥤 $nick (HLS)", streamHeaders, hlsStreams, flvStreams)

            // Extra CDN servers[] from BLV
            val extraServers = a["servers"] as? JsonArray
            extraServers?.forEachIndexed { sIdx, sElem ->
                val sUrl = sElem.jsonPrimitive.contentOrNull?.trim().orEmpty()
                addStreamIfValid(sUrl, "🥤 $nick (CDN ${sIdx + 2})", streamHeaders, hlsStreams, flvStreams)
            }

            // HTTP-FLV low-latency stream from BLV
            val flv = a["playStreamAddress"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            addStreamIfValid(flv, "🥤 $nick (FLV)", streamHeaders, hlsStreams, flvStreams)
        }

        // Official broadcast HD feed (`video_url` in detail response, `videoUrl` in list response)
        val officialUrl = obj["video_url"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: obj["videoUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        addStreamIfValid(officialUrl, "🥤 Cola HD (Gốc không BLV)", streamHeaders, hlsStreams, flvStreams)

        return blvNames.distinct() to (hlsStreams + flvStreams).distinctBy { it.url }
    }

    private fun addStreamIfValid(
        url: String,
        label: String,
        headers: Map<String, String>,
        hlsOut: MutableList<StreamSource>,
        flvOut: MutableList<StreamSource>
    ) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val isHls = url.contains(".m3u8", ignoreCase = true)
        val source = StreamSource(
            name = label,
            url = url,
            isHls = isHls,
            isWebViewFallback = false,
            headers = headers
        )
        if (isHls) hlsOut += source else flvOut += source
    }

    private fun cleanLogoUrl(raw: String): String {
        if (raw.isBlank()) return ""
        // Handle occasional double-prefixed URL like "https://img.cltvap.cc/.../https://oss-..."
        val lastHttp = raw.lastIndexOf("https://")
        return if (lastHttp > 0) raw.substring(lastHttp) else raw
    }

    companion object {
        const val CL_ID_PREFIX = "cl::"
        private const val DEFAULT_API_BASE = "https://api.cltvlv.com"
        private const val MATCH_CACHE_TTL_MS = 30 * 1000L

        fun isColaTvId(id: String): Boolean = id.startsWith(CL_ID_PREFIX)

        private fun formatEpoch(epochSec: Long): String {
            if (epochSec <= 0L) return ""
            return runCatching {
                val fmt = SimpleDateFormat("HH:mm • dd/MM", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
                fmt.format(Date(epochSec * 1000L))
            }.getOrDefault("")
        }
    }
}
