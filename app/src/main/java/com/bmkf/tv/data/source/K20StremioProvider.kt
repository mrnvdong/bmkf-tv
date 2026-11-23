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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Full Stremio Addon protocol client dynamically backed by https://sc.k-20.xyz/manifest.json
 * (or any custom Stremio manifest URL configured in Settings).
 */
class K20StremioProvider(
    private val manifestUrlProvider: () -> String
) : MovieSourceProvider {

    override val sourceId: SourceId = SourceId.K20

    internal data class StremioCatalogDef(
        val type: String,
        val id: String,
        val name: String,
        val genres: List<GenreItem>,
        val supportsSearch: Boolean
    )

    private var cachedManifestUrl: String? = null
    private var cachedCatalogDefs: List<StremioCatalogDef>? = null

    fun baseUrl(): String {
        val manifest = manifestUrlProvider().trim()
        return manifest.removeSuffix("/manifest.json").trimEnd('/')
    }

    private suspend fun ensureManifest(): List<StremioCatalogDef> {
        val url = manifestUrlProvider().trim()
        if (cachedManifestUrl == url && !cachedCatalogDefs.isNullOrEmpty()) {
            return cachedCatalogDefs!!
        }
        val raw = HttpClient.getString(url)
        val parsed = parseManifestCatalogs(raw)
        cachedManifestUrl = url
        cachedCatalogDefs = parsed
        return parsed
    }

    override suspend fun getCatalogTabs(): List<CatalogTab> {
        val defs = runCatching { ensureManifest() }.getOrDefault(defaultFallbackDefs())
        return defs.map { def ->
            CatalogTab(
                id = "${def.type}|${def.id}",
                title = def.name,
                mediaType = parseMediaType(def.type),
                genres = def.genres
            )
        }
    }

    override suspend fun getHomeSections(): List<HomeSection> = coroutineScope {
        val defs = runCatching { ensureManifest() }.getOrDefault(defaultFallbackDefs())
        val featuredIds = listOf("nguonc-movie", "vsmov-4k")
        val chosen = featuredIds.mapNotNull { id -> defs.firstOrNull { it.id == id } }
            .ifEmpty { defs.take(2) }

        chosen.map { def ->
            async {
                val movies = runCatching {
                    getCatalog("${def.type}|${def.id}", page = 1).items
                }.getOrDefault(emptyList())
                HomeSection(
                    id = "k20_${def.id}",
                    title = "K20 • ${def.name}",
                    source = SourceId.K20,
                    catalogId = "${def.type}|${def.id}",
                    movies = movies
                )
            }
        }.awaitAll().filter { it.movies.isNotEmpty() }
    }

    override suspend fun getCatalog(
        catalogId: String,
        page: Int,
        genre: String?
    ): PagedMovies {
        val (type, id) = splitCatalogKey(catalogId)
        val skip = (page - 1) * PAGE_SIZE
        val extraParts = mutableListOf<String>()
        if (!genre.isNullOrBlank()) {
            extraParts += "genre=${encodePathSegment(genre)}"
        }
        if (skip > 0) {
            extraParts += "skip=$skip"
        }
        val extraSuffix = if (extraParts.isNotEmpty()) "/${extraParts.joinToString("&")}" else ""
        val url = "${baseUrl()}/catalog/$type/$id$extraSuffix.json"
        val raw = HttpClient.getString(url)
        val items = parseCatalogMetas(raw, parseMediaType(type))
        return PagedMovies(items = items, page = page, hasMore = items.size >= 15)
    }

    override suspend fun getGenres(): List<GenreItem> {
        val defs = runCatching { ensureManifest() }.getOrDefault(emptyList())
        return defs.firstOrNull { it.genres.isNotEmpty() }?.genres.orEmpty()
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> = coroutineScope {
        if (page > 1) return@coroutineScope emptyList()
        val defs = runCatching { ensureManifest() }.getOrDefault(defaultFallbackDefs())
        val searchable = defs.filter { it.supportsSearch }.take(6)
        val encodedQuery = encodePathSegment(query.trim())

        val allResults = searchable.map { def ->
            async {
                runCatching {
                    val url = "${baseUrl()}/catalog/${def.type}/${def.id}/search=$encodedQuery.json"
                    val raw = HttpClient.getString(url)
                    parseCatalogMetas(raw, parseMediaType(def.type))
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()

        allResults.distinctBy { it.id }
    }

    override suspend fun getMovieDetail(id: String, mediaType: MediaType): MovieDetail {
        val stremioType = toStremioType(mediaType)
        val encodedId = encodePathSegment(id)
        val url = "${baseUrl()}/meta/$stremioType/$encodedId.json"
        val raw = HttpClient.getString(url)
        return parseMetaDetail(raw, id, mediaType)
    }

    override suspend fun resolveStream(episode: EpisodeItem, detail: MovieDetail): List<StreamSource> {
        val stremioType = toStremioType(detail.item.mediaType)
        val videoId = episode.k20VideoId.ifBlank { detail.item.id }
        val encodedId = encodePathSegment(videoId)
        val url = "${baseUrl()}/stream/$stremioType/$encodedId.json"
        val raw = HttpClient.getString(url)
        return parseStreamsResponse(raw)
    }

    internal fun parseManifestCatalogs(raw: String): List<StremioCatalogDef> {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val catalogs = root["catalogs"] as? JsonArray ?: return emptyList()
        return catalogs.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
            val extras = obj["extra"] as? JsonArray
            var supportsSearch = false
            val genres = mutableListOf<GenreItem>()
            extras?.forEach { extraElem ->
                val eObj = extraElem as? JsonObject ?: return@forEach
                val eName = eObj["name"]?.jsonPrimitive?.contentOrNull
                if (eName == "search") supportsSearch = true
                if (eName == "genre") {
                    (eObj["options"] as? JsonArray)?.forEach { opt ->
                        val label = opt.jsonPrimitive.contentOrNull.orEmpty()
                        if (label.isNotBlank()) {
                            genres += GenreItem(slug = label, name = label)
                        }
                    }
                }
            }
            StremioCatalogDef(type, id, name, genres, supportsSearch)
        }
    }

    internal fun parseCatalogMetas(raw: String, fallbackType: MediaType): List<MovieItem> {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val metas = root["metas"] as? JsonArray ?: return emptyList()
        return metas.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
            val poster = obj["poster"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val bg = obj["background"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val year = obj["releaseInfo"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val badge = id.substringBefore(':', "").uppercase()

            MovieItem(
                source = SourceId.K20,
                id = id,
                title = name,
                posterUrl = poster.ifBlank { bg },
                backdropUrl = bg.ifBlank { poster },
                year = year,
                quality = badge,
                episodeCurrent = desc.takeIf { it.length <= 42 }.orEmpty(),
                mediaType = typeStr?.let(::parseMediaType) ?: fallbackType,
                description = desc
            )
        }
    }

    internal fun parseMetaDetail(raw: String, fallbackId: String, fallbackType: MediaType): MovieDetail {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val meta = root["meta"] as? JsonObject ?: root
        val id = meta["id"]?.jsonPrimitive?.contentOrNull ?: fallbackId
        val name = meta["name"]?.jsonPrimitive?.contentOrNull ?: id
        val poster = meta["poster"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val bg = meta["background"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val mediaType = meta["type"]?.jsonPrimitive?.contentOrNull?.let(::parseMediaType) ?: fallbackType
        val desc = HttpClient.stripHtml(meta["description"]?.jsonPrimitive?.contentOrNull)
        val year = meta["releaseInfo"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val runtime = meta["runtime"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val cast = (meta["cast"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val directors = (meta["director"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val genres = (meta["genres"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

        val videos = meta["videos"] as? JsonArray
        val servers = if (!videos.isNullOrEmpty()) {
            val bySeason = LinkedHashMap<Int, MutableList<EpisodeItem>>()
            videos.forEachIndexed { idx, vElem ->
                val vObj = vElem as? JsonObject ?: return@forEachIndexed
                val vidId = vObj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                val rawTitle = vObj["title"]?.jsonPrimitive?.contentOrNull
                    ?: vObj["name"]?.jsonPrimitive?.contentOrNull
                    ?: "${idx + 1}"
                val season = vObj["season"]?.jsonPrimitive?.intOrNull ?: 1
                val epNum = vObj["episode"]?.jsonPrimitive?.intOrNull ?: (idx + 1)
                val displayName = if (rawTitle.all(Char::isDigit)) "Tập $rawTitle" else rawTitle

                bySeason.getOrPut(season) { mutableListOf() } += EpisodeItem(
                    id = vidId,
                    name = displayName,
                    season = season,
                    episodeNumber = epNum,
                    serverName = if (season > 1) "K20 • Phần $season" else "K20 Addon",
                    k20VideoId = vidId
                )
            }
            bySeason.entries.sortedBy { it.key }.map { (season, eps) ->
                EpisodeServer(
                    serverName = if (bySeason.size > 1) "Phần $season (K20)" else "Danh sách tập (K20)",
                    season = season,
                    episodes = eps.sortedBy { it.episodeNumber }
                )
            }
        } else {
            val label = if (mediaType == MediaType.TV) "Trực tiếp" else "Full"
            listOf(
                EpisodeServer(
                    serverName = "K20 Addon",
                    season = 1,
                    episodes = listOf(
                        EpisodeItem(
                            id = id,
                            name = label,
                            season = 1,
                            episodeNumber = 1,
                            serverName = "K20 Addon",
                            k20VideoId = id
                        )
                    )
                )
            )
        }

        val item = MovieItem(
            source = SourceId.K20,
            id = id,
            title = name,
            posterUrl = poster.ifBlank { bg },
            backdropUrl = bg.ifBlank { poster },
            year = year,
            quality = id.substringBefore(':', "K20").uppercase(),
            mediaType = mediaType,
            description = desc
        )

        return MovieDetail(
            item = item,
            description = desc,
            runtime = runtime,
            genres = genres,
            directors = directors,
            cast = cast,
            servers = servers
        )
    }

    internal fun parseStreamsResponse(raw: String): List<StreamSource> {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val streams = root["streams"] as? JsonArray ?: return emptyList()
        return streams.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val externalUrl = obj["externalUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank() && externalUrl.isBlank()) return@mapNotNull null

            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .replace("\n", " • ")
            val displayName = listOf(name, title).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "K20 Stream" }

            val headers = mutableMapOf<String, String>()
            val reqHeaders = obj["behaviorHints"]?.jsonObject
                ?.get("proxyHeaders")?.jsonObject
                ?.get("request") as? JsonObject
            reqHeaders?.forEach { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { headers[k] = it }
            }

            val subtitles = (obj["subtitles"] as? JsonArray)?.mapNotNull { sElem ->
                val sObj = sElem as? JsonObject ?: return@mapNotNull null
                val sUrl = sObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (sUrl.isBlank()) return@mapNotNull null
                val lang = sObj["lang"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                val label = when (lang) {
                    "vie", "vi" -> "Tiếng Việt"
                    "eng", "en" -> "English"
                    else -> lang.uppercase().ifBlank { "Phụ đề" }
                }
                SubtitleTrack(
                    url = sUrl,
                    language = if (lang == "vie") "vi" else if (lang == "eng") "en" else lang.ifBlank { "vi" },
                    label = label
                )
            }.orEmpty().sortedBy { if (it.language == "vi") 0 else 1 }

            val targetUrl = url.ifBlank { externalUrl }
            val isWebOnly = url.isBlank() && externalUrl.isNotBlank()

            StreamSource(
                name = displayName,
                url = targetUrl,
                isHls = !isWebOnly && targetUrl.contains(".m3u8", ignoreCase = true),
                isWebViewFallback = isWebOnly,
                headers = headers,
                subtitles = subtitles
            )
        }
    }

    private fun splitCatalogKey(key: String): Pair<String, String> {
        val parts = key.split("|", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else "movie" to key
    }

    private fun parseMediaType(raw: String): MediaType = when (raw.lowercase()) {
        "series" -> MediaType.SERIES
        "tv", "channel" -> MediaType.TV
        else -> MediaType.MOVIE
    }

    private fun toStremioType(mediaType: MediaType): String = when (mediaType) {
        MediaType.SERIES -> "series"
        MediaType.TV -> "tv"
        MediaType.MOVIE -> "movie"
    }

    private fun encodePathSegment(raw: String): String {
        return URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
    }

    private fun defaultFallbackDefs(): List<StremioCatalogDef> = listOf(
        StremioCatalogDef("movie", "nguonc-movie", "NguonC • Phim Lẻ", emptyList(), true),
        StremioCatalogDef("series", "nguonc-series", "NguonC • Phim Bộ", emptyList(), true),
        StremioCatalogDef("movie", "stp-movie", "STP • Phim Lẻ", emptyList(), true),
        StremioCatalogDef("series", "stp-series", "STP • Phim Bộ", emptyList(), true),
        StremioCatalogDef("movie", "clbpx-movie", "CLBPX • Phim Lẻ", emptyList(), true),
        StremioCatalogDef("movie", "vsmov-4k", "VSMOV • Phim 4K", emptyList(), true),
        StremioCatalogDef("tv", "sports-live", "K20 • Thể Thao Trực Tiếp", emptyList(), false)
    )

    companion object {
        private const val PAGE_SIZE = 20
    }
}
