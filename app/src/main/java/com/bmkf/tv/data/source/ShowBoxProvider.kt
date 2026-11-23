package com.bmkf.tv.data.source

import com.bmkf.tv.data.model.CatalogTab
import com.bmkf.tv.data.model.EpisodeItem
import com.bmkf.tv.data.model.EpisodeServer
import com.bmkf.tv.data.model.GenreItem
import com.bmkf.tv.data.model.HomeSection
import com.bmkf.tv.data.model.MediaType
import com.bmkf.tv.data.model.MovieDetail
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.PagedMovies
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.model.StreamSource
import com.bmkf.tv.data.model.SubtitleTrack
import com.bmkf.tv.data.network.HttpClient
import com.bmkf.tv.player.VideoColorGuard
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * ShowBox Stremio Addon source (direct 4K & 1080P HTTP MKV/MP4 streams via user's ShowBox manifest URL).
 *
 * Splits servers explicitly into **ShowBox 4K** and **ShowBox 1080P** so users can pick their preferred
 * quality tier, tags Dolby Vision tracks cleanly to prevent purple/green playback on SDR/HDR10 TVs,
 * and pairs with Cinemeta catalogs + automatic Vietnamese/English subtitles from OpenSubtitles v3.
 */
class ShowBoxProvider(
    private val manifestUrlProvider: () -> String,
    private val k20BaseUrlProvider: () -> String
) : MovieSourceProvider {

    override val sourceId: SourceId = SourceId.SHOWBOX

    fun addonBaseUrl(): String {
        val manifest = manifestUrlProvider().trim()
        return manifest.removeSuffix("/manifest.json").trimEnd('/')
    }

    override suspend fun getCatalogTabs(): List<CatalogTab> {
        val genres = getGenres()
        return listOf(
            CatalogTab("movie|top", "Phim lẻ Phổ biến", MediaType.MOVIE, genres),
            CatalogTab("series|top", "Phim bộ Phổ biến", MediaType.SERIES, genres),
            CatalogTab("movie|imdbRating", "Top IMDb Phim lẻ", MediaType.MOVIE, genres),
            CatalogTab("series|imdbRating", "Top IMDb Phim bộ", MediaType.SERIES, genres)
        )
    }

    override suspend fun getHomeSections(): List<HomeSection> = coroutineScope {
        val popMovies = async {
            runCatching { getCatalog("movie|top", 1).items }.getOrDefault(emptyList())
        }
        val popSeries = async {
            runCatching { getCatalog("series|top", 1).items }.getOrDefault(emptyList())
        }
        listOf(
            HomeSection("sb_movies", "ShowBox • Phim lẻ Quốc tế (4K / 1080P)", SourceId.SHOWBOX, "movie|top", popMovies.await()),
            HomeSection("sb_series", "ShowBox • Series Quốc tế (4K / 1080P)", SourceId.SHOWBOX, "series|top", popSeries.await())
        ).filter { it.movies.isNotEmpty() }
    }

    override suspend fun getCatalog(
        catalogId: String,
        page: Int,
        genre: String?
    ): PagedMovies {
        val parts = catalogId.split("|", limit = 2)
        val type = parts.getOrNull(0) ?: "movie"
        val id = parts.getOrNull(1) ?: "top"
        val skip = (page - 1) * PAGE_SIZE

        val extraParts = mutableListOf<String>()
        if (!genre.isNullOrBlank()) {
            extraParts += "genre=${encodePathSegment(genre)}"
        }
        if (skip > 0) {
            extraParts += "skip=$skip"
        }
        val extraSuffix = if (extraParts.isNotEmpty()) "/${extraParts.joinToString("&")}" else ""
        val url = "$CINEMETA_BASE/catalog/$type/$id$extraSuffix.json"
        val raw = HttpClient.getString(url)
        val items = parseCinemetaMetas(raw, parseMediaType(type))
        return PagedMovies(items = items, page = page, hasMore = items.size >= 15)
    }

    override suspend fun getGenres(): List<GenreItem> = CINEMETA_GENRES

    override suspend fun search(query: String, page: Int): List<MovieItem> = coroutineScope {
        if (page > 1) return@coroutineScope emptyList()
        val encoded = encodePathSegment(query.trim())
        val movieDeferred = async {
            runCatching {
                val raw = HttpClient.getString("$CINEMETA_BASE/catalog/movie/top/search=$encoded.json")
                parseCinemetaMetas(raw, MediaType.MOVIE)
            }.getOrDefault(emptyList())
        }
        val seriesDeferred = async {
            runCatching {
                val raw = HttpClient.getString("$CINEMETA_BASE/catalog/series/top/search=$encoded.json")
                parseCinemetaMetas(raw, MediaType.SERIES)
            }.getOrDefault(emptyList())
        }
        (movieDeferred.await() + seriesDeferred.await()).distinctBy { it.id }
    }

    override suspend fun getMovieDetail(id: String, mediaType: MediaType): MovieDetail {
        val stremioType = if (mediaType == MediaType.SERIES) "series" else "movie"
        val encodedId = encodePathSegment(id)
        val raw = HttpClient.getString("$CINEMETA_BASE/meta/$stremioType/$encodedId.json")
        return parseCinemetaDetail(raw, id, mediaType)
    }

    override suspend fun resolveStream(episode: EpisodeItem, detail: MovieDetail): List<StreamSource> {
        val stremioType = if (detail.item.mediaType == MediaType.SERIES) "series" else "movie"
        val videoId = episode.k20VideoId.ifBlank { detail.imdbId.ifBlank { detail.item.id } }
        return resolveForTier(
            stremioType = stremioType,
            videoId = videoId,
            preferredTier = episode.slug,
            allowK20Fallback = detail.item.source == SourceId.SHOWBOX
        )
    }

    suspend fun resolveForTier(
        stremioType: String,
        videoId: String,
        preferredTier: String,
        allowK20Fallback: Boolean = false
    ): List<StreamSource> = coroutineScope {
        val showBoxDeferred = async {
            runCatching { fetchShowBoxStreams(stremioType, videoId) }.getOrDefault(emptyList())
        }
        val subtitlesDeferred = async {
            withTimeoutOrNull(4_000L) {
                runCatching { fetchOpenSubtitles(stremioType, videoId) }.getOrDefault(emptyList())
            }.orEmpty()
        }

        val rawStreams = showBoxDeferred.await()
        val subs = subtitlesDeferred.await()

        val tiered = orderStreamsByTier(rawStreams, preferredTier).map { stream ->
            if (stream.subtitles.isEmpty() && subs.isNotEmpty()) {
                stream.copy(subtitles = subs)
            } else {
                stream
            }
        }

        if (tiered.isNotEmpty() || !allowK20Fallback) {
            tiered
        } else {
            runCatching {
                val k20Url = "${k20BaseUrlProvider().trimEnd('/')}/stream/$stremioType/${encodePathSegment(videoId)}.json"
                parseShowBoxStreamsResponse(HttpClient.getString(k20Url), sourcePrefix = "K20 Backup")
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Builds two explicit selectable servers (**ShowBox 4K** and **ShowBox 1080P**) for any movie
     * from KKPhim / VSMOV / CoBePhim that has a valid IMDb ID (`tt...`).
     */
    fun buildCrossSourceServers(detail: MovieDetail): List<EpisodeServer> {
        if (addonBaseUrl().isBlank()) return emptyList()
        val imdbId = detail.imdbId.trim()
        if (!imdbId.startsWith("tt")) return emptyList()

        val firstServerEps = detail.servers.firstOrNull()?.episodes.orEmpty()
        val isSeries = detail.item.mediaType == MediaType.SERIES || firstServerEps.size > 1

        fun makeEpisodes(tier: String, serverLabel: String): List<EpisodeItem> {
            return if (!isSeries) {
                listOf(
                    EpisodeItem(
                        id = "${tier}_$imdbId",
                        name = if (tier == TIER_4K) "Full (4K)" else "Full (1080P)",
                        slug = tier,
                        season = 1,
                        episodeNumber = 1,
                        serverName = serverLabel,
                        k20VideoId = imdbId
                    )
                )
            } else {
                val count = firstServerEps.size.coerceAtLeast(1)
                (1..count).map { epNum ->
                    val refEp = firstServerEps.getOrNull(epNum - 1)
                    val season = (refEp?.season ?: 1).coerceAtLeast(1)
                    val actualEpNum = (refEp?.episodeNumber ?: epNum).coerceAtLeast(1)
                    val vidId = "$imdbId:$season:$actualEpNum"
                    EpisodeItem(
                        id = "${tier}_$vidId",
                        name = refEp?.name ?: "Tập $actualEpNum",
                        slug = tier,
                        season = season,
                        episodeNumber = actualEpNum,
                        serverName = serverLabel,
                        k20VideoId = vidId
                    )
                }
            }
        }

        return listOf(
            EpisodeServer(
                serverName = "ShowBox 4K",
                season = 1,
                episodes = makeEpisodes(TIER_4K, "ShowBox 4K")
            ),
            EpisodeServer(
                serverName = "ShowBox 1080P",
                season = 1,
                episodes = makeEpisodes(TIER_1080P, "ShowBox 1080P")
            )
        )
    }

    internal fun orderStreamsByTier(
        streams: List<StreamSource>,
        preferredTier: String
    ): List<StreamSource> {
        if (streams.isEmpty()) return emptyList()

        val is4kStream: (StreamSource) -> Boolean = { s ->
            s.qualityLabel.contains("4k", ignoreCase = true) ||
                s.qualityLabel.contains("2160", ignoreCase = true) ||
                s.name.contains("2160p", ignoreCase = true) ||
                s.name.contains("• 4K", ignoreCase = true)
        }

        val fourK = streams.filter(is4kStream)
        val hd1080 = streams.filterNot(is4kStream)

        // Within each tier, put SDR/HDR10 streams ahead of Dolby Vision (prevents purple tint on non-DV TVs)
        val safe4k = VideoColorGuard.sortStreamsForColorSafety(fourK, deviceSupportsDv = false)
        val safe1080 = VideoColorGuard.sortStreamsForColorSafety(hd1080, deviceSupportsDv = false)

        return when (preferredTier) {
            TIER_4K -> safe4k + safe1080
            TIER_1080P -> safe1080 + safe4k
            else -> safe1080 + safe4k
        }
    }

    private suspend fun fetchShowBoxStreams(stremioType: String, videoId: String): List<StreamSource> {
        val base = addonBaseUrl()
        if (base.isBlank()) return emptyList()
        val encodedId = encodePathSegment(videoId)
        val raw = HttpClient.getString("$base/stream/$stremioType/$encodedId.json")
        return parseShowBoxStreamsResponse(raw)
    }

    private suspend fun fetchOpenSubtitles(stremioType: String, videoId: String): List<SubtitleTrack> {
        val encodedId = encodePathSegment(videoId)
        val raw = HttpClient.getString("$OPENSUBTITLES_BASE/subtitles/$stremioType/$encodedId.json")
        return parseOpenSubtitlesResponse(raw)
    }

    internal fun parseShowBoxStreamsResponse(
        raw: String,
        sourcePrefix: String = "ShowBox"
    ): List<StreamSource> {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val streams = root["streams"] as? JsonArray ?: return emptyList()
        return streams.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank()) return@mapNotNull null

            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { sourcePrefix }
            val quality = obj["quality"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val size = obj["size"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val rawTitle = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .replace("\u00A0", " ")
                .replace("\n", " • ")
                .trim()

            val dvTag = when {
                VideoColorGuard.isPureDolbyVisionRisk(rawTitle) || VideoColorGuard.isPureDolbyVisionRisk(url) -> " [⚠️ DV]"
                VideoColorGuard.isDolbyVisionTitle(rawTitle) || VideoColorGuard.isDolbyVisionTitle(url) -> " [DV+HDR]"
                rawTitle.contains("HDR", ignoreCase = true) -> " [HDR]"
                else -> ""
            }

            val displayLabel = buildString {
                append(name)
                if (quality.isNotBlank()) append(" • ").append(quality)
                append(dvTag)
                if (size.isNotBlank()) append(" (").append(size).append(")")
                if (rawTitle.isNotBlank()) {
                    val filePart = rawTitle.substringBefore("•").replace("📁", "").trim()
                    if (filePart.isNotBlank()) append(" — ").append(filePart.take(48))
                }
            }

            val headers = mutableMapOf<String, String>()
            val reqHeaders = obj["behaviorHints"]?.jsonObject
                ?.get("proxyHeaders")?.jsonObject
                ?.get("request") as? JsonObject
            reqHeaders?.forEach { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { headers[k] = it }
            }

            val bundledSubs = (obj["subtitles"] as? JsonArray)?.mapNotNull { sElem ->
                val sObj = sElem as? JsonObject ?: return@mapNotNull null
                val sUrl = sObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (sUrl.isBlank()) return@mapNotNull null
                val lang = sObj["lang"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                SubtitleTrack(
                    url = sUrl,
                    language = normalizeLang(lang),
                    label = langLabel(lang),
                    mimeType = if (sUrl.contains(".vtt", ignoreCase = true)) "text/vtt" else "application/x-subrip"
                )
            }.orEmpty()

            val isHls = url.contains(".m3u8", ignoreCase = true)

            StreamSource(
                name = displayLabel,
                url = url,
                isHls = isHls,
                isWebViewFallback = false,
                headers = headers,
                subtitles = bundledSubs,
                qualityLabel = quality
            )
        }
    }

    internal fun parseOpenSubtitlesResponse(raw: String): List<SubtitleTrack> {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val arr = root["subtitles"] as? JsonArray ?: return emptyList()
        val picked = mutableListOf<SubtitleTrack>()
        var viCount = 0
        var enCount = 0

        for (elem in arr) {
            val obj = elem as? JsonObject ?: continue
            val lang = obj["lang"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank()) continue

            when (lang) {
                "vie", "vi" -> if (viCount < 3) {
                    viCount++
                    picked += SubtitleTrack(
                        url = url,
                        language = "vi",
                        label = "Tiếng Việt #$viCount",
                        mimeType = "application/x-subrip"
                    )
                }
                "eng", "en" -> if (enCount < 2) {
                    enCount++
                    picked += SubtitleTrack(
                        url = url,
                        language = "en",
                        label = "English #$enCount",
                        mimeType = "application/x-subrip"
                    )
                }
            }
            if (viCount >= 3 && enCount >= 2) break
        }

        return picked.sortedBy { if (it.language == "vi") 0 else 1 }
    }

    internal fun parseCinemetaMetas(raw: String, fallbackType: MediaType): List<MovieItem> {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val metas = root["metas"] as? JsonArray ?: return emptyList()
        return metas.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: obj["imdb_id"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            if (!id.startsWith("tt")) return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
            val poster = obj["poster"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val bg = obj["background"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val year = obj["releaseInfo"]?.jsonPrimitive?.contentOrNull
                ?: obj["year"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val rating = obj["imdbRating"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull
            val desc = HttpClient.stripHtml(obj["description"]?.jsonPrimitive?.contentOrNull)

            MovieItem(
                source = SourceId.SHOWBOX,
                id = id,
                title = name,
                posterUrl = poster.ifBlank { bg },
                backdropUrl = bg.ifBlank { poster },
                year = year,
                quality = "4K/1080P",
                rating = rating,
                mediaType = typeStr?.let(::parseMediaType) ?: fallbackType,
                description = desc
            )
        }
    }

    internal fun parseCinemetaDetail(raw: String, fallbackId: String, fallbackType: MediaType): MovieDetail {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val meta = root["meta"] as? JsonObject ?: root
        val id = meta["id"]?.jsonPrimitive?.contentOrNull
            ?: meta["imdb_id"]?.jsonPrimitive?.contentOrNull
            ?: fallbackId
        val name = meta["name"]?.jsonPrimitive?.contentOrNull ?: id
        val poster = meta["poster"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val bg = meta["background"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val mediaType = meta["type"]?.jsonPrimitive?.contentOrNull?.let(::parseMediaType) ?: fallbackType
        val desc = HttpClient.stripHtml(meta["description"]?.jsonPrimitive?.contentOrNull)
        val year = meta["releaseInfo"]?.jsonPrimitive?.contentOrNull
            ?: meta["year"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val runtime = meta["runtime"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rating = meta["imdbRating"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val cast = (meta["cast"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val directors = (meta["director"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val genres = ((meta["genres"] as? JsonArray) ?: (meta["genre"] as? JsonArray))
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val countries = meta["country"]?.jsonPrimitive?.contentOrNull
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

        val videos = meta["videos"] as? JsonArray
        val servers = if (!videos.isNullOrEmpty()) {
            data class RawEp(val id: String, val name: String, val season: Int, val epNum: Int)

            val bySeason = LinkedHashMap<Int, MutableList<RawEp>>()
            videos.forEachIndexed { idx, vElem ->
                val vObj = vElem as? JsonObject ?: return@forEachIndexed
                val vidId = vObj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                val season = vObj["season"]?.jsonPrimitive?.intOrNull ?: 1
                val epNum = vObj["episode"]?.jsonPrimitive?.intOrNull
                    ?: vObj["number"]?.jsonPrimitive?.intOrNull
                    ?: (idx + 1)
                val title = vObj["name"]?.jsonPrimitive?.contentOrNull
                    ?: vObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val displayName = if (title.isNotBlank()) "Tập $epNum: $title" else "Tập $epNum"
                bySeason.getOrPut(season) { mutableListOf() } += RawEp(vidId, displayName, season, epNum)
            }

            // Place Season 1, 2, ... first (Specials Season 0 last), splitting EACH into ShowBox 1080P & ShowBox 4K!
            val sortedSeasons = bySeason.entries
                .sortedWith(compareBy({ if (it.key <= 0) 1 else 0 }, { it.key }))

            val resultServers = mutableListOf<EpisodeServer>()
            sortedSeasons.forEach { (season, rawEps) ->
                val sortedEps = rawEps.sortedBy { it.epNum }
                val seasonPrefix = if (season <= 0) "Đặc biệt" else "Phần $season"

                val label4k = "ShowBox 4K • $seasonPrefix"
                val label1080 = "ShowBox 1080P • $seasonPrefix"

                // Put 1080P and 4K clearly separated so user picks effortlessly
                resultServers += EpisodeServer(
                    serverName = label4k,
                    season = season,
                    episodes = sortedEps.map { ep ->
                        EpisodeItem(
                            id = "${TIER_4K}_${ep.id}",
                            name = ep.name,
                            slug = TIER_4K,
                            season = ep.season,
                            episodeNumber = ep.epNum,
                            serverName = label4k,
                            k20VideoId = ep.id
                        )
                    }
                )
                resultServers += EpisodeServer(
                    serverName = label1080,
                    season = season,
                    episodes = sortedEps.map { ep ->
                        EpisodeItem(
                            id = "${TIER_1080P}_${ep.id}",
                            name = ep.name,
                            slug = TIER_1080P,
                            season = ep.season,
                            episodeNumber = ep.epNum,
                            serverName = label1080,
                            k20VideoId = ep.id
                        )
                    }
                )
            }
            resultServers
        } else {
            // Standalone movie: explicit ShowBox 4K and ShowBox 1080P servers!
            listOf(
                EpisodeServer(
                    serverName = "ShowBox 4K",
                    season = 1,
                    episodes = listOf(
                        EpisodeItem(
                            id = "${TIER_4K}_$id",
                            name = "Full (4K)",
                            slug = TIER_4K,
                            season = 1,
                            episodeNumber = 1,
                            serverName = "ShowBox 4K",
                            k20VideoId = id
                        )
                    )
                ),
                EpisodeServer(
                    serverName = "ShowBox 1080P",
                    season = 1,
                    episodes = listOf(
                        EpisodeItem(
                            id = "${TIER_1080P}_$id",
                            name = "Full (1080P)",
                            slug = TIER_1080P,
                            season = 1,
                            episodeNumber = 1,
                            serverName = "ShowBox 1080P",
                            k20VideoId = id
                        )
                    )
                )
            )
        }

        val item = MovieItem(
            source = SourceId.SHOWBOX,
            id = id,
            title = name,
            posterUrl = poster.ifBlank { bg },
            backdropUrl = bg.ifBlank { poster },
            year = year,
            quality = "4K/1080P",
            rating = rating,
            mediaType = mediaType,
            description = desc
        )

        return MovieDetail(
            item = item,
            description = desc,
            runtime = runtime,
            status = meta["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            genres = genres,
            countries = countries,
            directors = directors,
            cast = cast,
            servers = servers,
            imdbId = id
        )
    }

    private fun parseMediaType(raw: String): MediaType = when (raw.lowercase()) {
        "series" -> MediaType.SERIES
        "tv" -> MediaType.TV
        else -> MediaType.MOVIE
    }

    private fun normalizeLang(raw: String): String = when (raw.lowercase()) {
        "vie", "vi" -> "vi"
        "eng", "en" -> "en"
        else -> raw.ifBlank { "vi" }
    }

    private fun langLabel(raw: String): String = when (raw.lowercase()) {
        "vie", "vi" -> "Tiếng Việt"
        "eng", "en" -> "English"
        else -> raw.uppercase().ifBlank { "Phụ đề" }
    }

    private fun encodePathSegment(raw: String): String {
        return URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
    }

    companion object {
        const val TIER_4K = "showbox_4k"
        const val TIER_1080P = "showbox_1080p"

        private const val CINEMETA_BASE = "https://v3-cinemeta.strem.io"
        private const val OPENSUBTITLES_BASE = "https://opensubtitles-v3.strem.io"
        private const val PAGE_SIZE = 20

        private val CINEMETA_GENRES = listOf(
            GenreItem("Action", "Hành Động"),
            GenreItem("Adventure", "Phiêu Lưu"),
            GenreItem("Animation", "Hoạt Hình"),
            GenreItem("Comedy", "Hài Hước"),
            GenreItem("Crime", "Hình Sự"),
            GenreItem("Drama", "Chính Kịch"),
            GenreItem("Fantasy", "Giả Tưởng"),
            GenreItem("Horror", "Kinh Dị"),
            GenreItem("Mystery", "Bí Ẩn"),
            GenreItem("Romance", "Lãng Mạn"),
            GenreItem("Sci-Fi", "Khoa Học Viễn Tưởng"),
            GenreItem("Thriller", "Giật Gân"),
            GenreItem("Documentary", "Tài Liệu"),
            GenreItem("Family", "Gia Đình")
        )
    }
}
