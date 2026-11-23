package com.bmkf.tv.data.source

import com.bmkf.tv.data.model.MediaType
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.model.StreamSource
import com.bmkf.tv.data.network.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Client for Xôi Chè TV (`https://xoiche.live/`).
 * Uses its clean REST JSON API (`/api/matches/?status=live|scheduled&has_commentator=true&ordering=smart`)
 * with embedded Vietnamese BLV HLS (`.m3u8`) and FLV stream URLs, plus SSR fallback.
 */
class XoiCheClient(
    private val entryUrlProvider: () -> String
) {
    data class ParsedMatch(
        val slug: String,
        val title: String,
        val league: String,
        val sportSlug: String,
        val sportName: String,
        val isLive: Boolean,
        val isHot: Boolean,
        val matchTime: String,
        val kickoffTime: String,
        val scoreText: String,
        val blvNames: List<String>,
        val logoUrl: String,
        val streams: List<StreamSource>
    ) {
        fun toMovieItem(): MovieItem {
            val badge = when {
                isLive -> "🍵 LIVE"
                isHot -> "🔥 HOT"
                else -> kickoffTime.ifBlank { sportName.ifBlank { "Bóng đá" } }
            }
            val displayTitle = when {
                isLive && scoreText.isNotBlank() -> "🔴 $title ($scoreText)"
                isLive -> "🔴 $title"
                else -> title
            }
            val blvText = blvNames.take(2).joinToString(", ")
            val timeOrMinute = if (isLive && matchTime.isNotBlank()) matchTime else kickoffTime
            val meta = listOf(league, timeOrMinute, blvText)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" • ")

            return MovieItem(
                source = SourceId.SPORT_TV,
                id = "$XC_ID_PREFIX$slug",
                title = displayTitle,
                originTitle = meta,
                posterUrl = logoUrl,
                backdropUrl = logoUrl,
                quality = badge,
                episodeCurrent = timeOrMinute,
                mediaType = MediaType.TV,
                description = "$title — $meta (Xôi Chè TV BLV Tiếng Việt)"
            )
        }
    }

    private var cachedTimestampMs: Long = 0L
    private var cachedMatches: List<ParsedMatch> = emptyList()

    private fun baseUrl(): String {
        return entryUrlProvider().trim().ifBlank { "https://xoiche.live" }.trimEnd('/')
    }

    suspend fun fetchMatches(
        filter: String,
        bypassCache: Boolean = false
    ): List<MovieItem> {
        val all = loadAllMatches(bypassCache)
        val filtered = when (filter) {
            "live" -> all.filter { it.isLive }.ifEmpty { all }
            "football" -> all.filter { it.sportSlug == "football" || it.sportSlug.isBlank() }
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
        val slug = storedId.removePrefix(XC_ID_PREFIX).trim('/')
        // Refresh cached list first if empty or stale so tokens stay fresh
        val list = loadAllMatches(bypassCache = cachedMatches.none { it.slug == slug })
        val cached = list.firstOrNull { it.slug == slug }
        if (cached != null && cached.streams.isNotEmpty()) {
            return cached.streams
        }

        // Fallback: fetch SSR watch page /truc-tiep/{slug} and extract clean HLS/FLV URLs from __NUXT_DATA__
        val origin = baseUrl()
        val html = runCatching {
            HttpClient.getString(
                "$origin/truc-tiep/$slug",
                headers = mapOf("Referer" to "$origin/")
            )
        }.getOrNull().orEmpty()
        if (html.isBlank()) return emptyList()

        return extractFallbackWatchStreams(html, origin)
    }

    private suspend fun loadAllMatches(bypassCache: Boolean): List<ParsedMatch> = coroutineScope {
        val now = System.currentTimeMillis()
        if (!bypassCache && cachedMatches.isNotEmpty() && now - cachedTimestampMs < MATCH_CACHE_TTL_MS) {
            return@coroutineScope cachedMatches
        }

        val origin = baseUrl()
        val headers = mapOf("Referer" to "$origin/")

        val liveDeferred = async {
            runCatching {
                HttpClient.getString(
                    "$origin/api/matches/?status=live&has_commentator=true&page_size=60&ordering=smart",
                    headers = headers
                )
            }.getOrNull()
        }
        val scheduledDeferred = async {
            runCatching {
                HttpClient.getString(
                    "$origin/api/matches/?status=scheduled&has_commentator=true&page_size=60&ordering=smart",
                    headers = headers
                )
            }.getOrNull()
        }

        val liveMatches = liveDeferred.await()?.let { parseMatchesResponse(it, origin) }.orEmpty()
        val scheduledMatches = scheduledDeferred.await()?.let { parseMatchesResponse(it, origin) }.orEmpty()

        val merged = LinkedHashMap<String, ParsedMatch>()
        (liveMatches + scheduledMatches).forEach { m ->
            if (!merged.containsKey(m.slug)) {
                merged[m.slug] = m
            }
        }
        val sorted = merged.values.sortedWith(
            compareByDescending<ParsedMatch> { it.isLive }
                .thenByDescending { it.isHot }
                .thenByDescending { it.streams.isNotEmpty() }
        )
        if (sorted.isNotEmpty()) {
            cachedMatches = sorted
            cachedTimestampMs = System.currentTimeMillis()
        }
        sorted
    }

    internal fun parseMatchesResponse(rawJson: String, origin: String = "https://xoiche.live"): List<ParsedMatch> {
        val root = runCatching { HttpClient.json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        val baseHeaders = mapOf(
            "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
            "Referer" to "${origin.trimEnd('/')}/",
            "Origin" to origin.trimEnd('/')
        )

        return results.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val homeObj = obj["home_team"] as? JsonObject
            val awayObj = obj["away_team"] as? JsonObject
            val homeName = obj["home_team_name"]?.jsonPrimitive?.contentOrNull
                ?: homeObj?.get("name")?.jsonPrimitive?.contentOrNull
                ?: ""
            val awayName = obj["away_team_name"]?.jsonPrimitive?.contentOrNull
                ?: awayObj?.get("name")?.jsonPrimitive?.contentOrNull
                ?: ""
            val title = if (homeName.isNotBlank() && awayName.isNotBlank()) {
                "$homeName vs $awayName"
            } else {
                slug.replace('-', ' ')
            }

            val tournamentObj = obj["tournament"] as? JsonObject
            val league = obj["tournament_name"]?.jsonPrimitive?.contentOrNull
                ?: tournamentObj?.get("name")?.jsonPrimitive?.contentOrNull
                ?: ""

            val sportObj = obj["sport"] as? JsonObject
            val sportSlug = (obj["sport_slug"]?.jsonPrimitive?.contentOrNull
                ?: sportObj?.get("slug")?.jsonPrimitive?.contentOrNull
                ?: "football").lowercase()
            val sportName = obj["sport_name"]?.jsonPrimitive?.contentOrNull
                ?: sportObj?.get("name")?.jsonPrimitive?.contentOrNull
                ?: "Bóng đá"

            val status = obj["status"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val isLive = status == "live"
            val isHot = obj["is_hot"]?.jsonPrimitive?.booleanOrNull == true
            val matchTime = obj["match_time"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val kickoffTime = formatIsoTime(obj["start_time"]?.jsonPrimitive?.contentOrNull.orEmpty())

            val homeScore = obj["home_score"]?.jsonPrimitive?.intOrNull
            val awayScore = obj["away_score"]?.jsonPrimitive?.intOrNull
            val scoreText = if (isLive && homeScore != null && awayScore != null) "$homeScore-$awayScore" else ""

            val logoUrl = obj["home_team_logo"]?.jsonPrimitive?.contentOrNull
                ?: homeObj?.get("logo")?.jsonPrimitive?.contentOrNull
                ?: tournamentObj?.get("icon")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
                ?: ""

            val blvNames = mutableListOf<String>()
            val hlsList = mutableListOf<StreamSource>()
            val flvList = mutableListOf<StreamSource>()

            val commentatorStreams = obj["commentator_streams"] as? JsonArray
            commentatorStreams?.forEachIndexed { idx, csElem ->
                val cs = csElem as? JsonObject ?: return@forEachIndexed
                val comm = cs["commentator"] as? JsonObject
                val blvName = comm?.get("name")?.jsonPrimitive?.contentOrNull
                    ?.trim()?.takeIf { it.isNotBlank() } ?: "BLV ${idx + 1}"
                blvNames += blvName

                val primaryUrl = cs["stream_url"]?.jsonPrimitive?.contentOrNull
                    ?: comm?.get("stream_url")?.jsonPrimitive?.contentOrNull
                    ?: ""
                addIfHttpStream(primaryUrl, "🍵 $blvName (HLS)", baseHeaders, hlsList, flvList)

                val backupUrl = cs["backup_stream_url"]?.jsonPrimitive?.contentOrNull
                    ?: comm?.get("backup_stream_url")?.jsonPrimitive?.contentOrNull
                    ?: ""
                addIfHttpStream(backupUrl, "🍵 $blvName (Dự phòng)", baseHeaders, hlsList, flvList)

                val flvUrl = cs["flv_stream_url"]?.jsonPrimitive?.contentOrNull
                    ?: comm?.get("flv_stream_url")?.jsonPrimitive?.contentOrNull
                    ?: ""
                addIfHttpStream(flvUrl, "🍵 $blvName (FLV)", baseHeaders, hlsList, flvList)
            }

            // Also include top-level primary/backup URLs if present
            val topPrimary = obj["primary_stream_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            addIfHttpStream(topPrimary, "🍵 Xôi Chè (Chính)", baseHeaders, hlsList, flvList)
            val topBackup = obj["backup_stream_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            addIfHttpStream(topBackup, "🍵 Xôi Chè (Dự phòng)", baseHeaders, hlsList, flvList)

            ParsedMatch(
                slug = slug,
                title = title,
                league = league,
                sportSlug = sportSlug,
                sportName = sportName,
                isLive = isLive,
                isHot = isHot,
                matchTime = matchTime,
                kickoffTime = kickoffTime,
                scoreText = scoreText,
                blvNames = blvNames.distinct(),
                logoUrl = logoUrl,
                streams = (hlsList + flvList).distinctBy { it.url }
            )
        }
    }

    internal fun extractFallbackWatchStreams(watchHtml: String, origin: String): List<StreamSource> {
        val unescaped = watchHtml
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
        val baseHeaders = mapOf(
            "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
            "Referer" to "${origin.trimEnd('/')}/",
            "Origin" to origin.trimEnd('/')
        )
        val hlsList = mutableListOf<StreamSource>()
        val flvList = mutableListOf<StreamSource>()

        MEDIA_URL_REGEX.findAll(unescaped).forEachIndexed { idx, m ->
            val u = m.value
            if (!u.contains("tvc-", ignoreCase = true) && !u.contains("ads", ignoreCase = true)) {
                addIfHttpStream(u, "🍵 Xôi Chè #${idx + 1}", baseHeaders, hlsList, flvList)
            }
        }
        return (hlsList + flvList).distinctBy { it.url }
    }

    private fun addIfHttpStream(
        url: String,
        label: String,
        headers: Map<String, String>,
        hlsOut: MutableList<StreamSource>,
        flvOut: MutableList<StreamSource>
    ) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return
        val isHls = trimmed.contains(".m3u8", ignoreCase = true)
        val source = StreamSource(
            name = label,
            url = trimmed,
            isHls = isHls,
            isWebViewFallback = false,
            headers = headers
        )
        if (isHls) hlsOut += source else flvOut += source
    }

    private fun formatIsoTime(iso: String): String {
        if (iso.isBlank()) return ""
        return ISO_FALLBACK_REGEX.find(iso)?.let { m ->
            "${m.groupValues[3]} • ${m.groupValues[2]}/${m.groupValues[1]}"
        }.orEmpty()
    }

    companion object {
        const val XC_ID_PREFIX = "xc::"
        private const val MATCH_CACHE_TTL_MS = 30 * 1000L
        private val ISO_FALLBACK_REGEX = Regex("""\d{4}-(\d{2})-(\d{2})T(\d{2}:\d{2})""")
        private val MEDIA_URL_REGEX = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|flv)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

        fun isXoiCheId(id: String): Boolean = id.startsWith(XC_ID_PREFIX)
    }
}
