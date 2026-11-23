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
import com.bmkf.tv.data.network.HttpClient
import com.bmkf.tv.data.network.StreamResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Locale

class VsMovProvider(
    private val baseUrlProvider: () -> String,
    private val k20BaseUrlProvider: () -> String
) : MovieSourceProvider {

    override val sourceId: SourceId = SourceId.VSMOV

    private var cachedGenres: List<GenreItem>? = null

    private fun baseUrl(): String = baseUrlProvider().trimEnd('/')

    override suspend fun getCatalogTabs(): List<CatalogTab> {
        val genres = runCatching { getGenres() }.getOrDefault(emptyList())
        return listOf(
            CatalogTab("phim-moi-cap-nhat", "Mới cập nhật", MediaType.MOVIE, genres),
            CatalogTab("phim-le", "Phim lẻ", MediaType.MOVIE, genres),
            CatalogTab("phim-bo", "Phim bộ", MediaType.SERIES, genres),
            CatalogTab("hoat-hinh", "Hoạt hình", MediaType.SERIES, genres),
            CatalogTab("subteam", "Subteam", MediaType.MOVIE, genres)
        )
    }

    override suspend fun getHomeSections(): List<HomeSection> = coroutineScope {
        val newest = async { runCatching { getCatalog("phim-moi-cap-nhat", 1).items }.getOrDefault(emptyList()) }
        val movies = async { runCatching { getCatalog("phim-le", 1).items }.getOrDefault(emptyList()) }

        listOf(
            HomeSection("vs_newest", "VSMOV • Mới cập nhật", SourceId.VSMOV, "phim-moi-cap-nhat", newest.await()),
            HomeSection("vs_movies", "VSMOV • Phim lẻ HD", SourceId.VSMOV, "phim-le", movies.await())
        ).filter { it.movies.isNotEmpty() }
    }

    override suspend fun getCatalog(
        catalogId: String,
        page: Int,
        genre: String?
    ): PagedMovies {
        val url = if (!genre.isNullOrBlank()) {
            "${baseUrl()}/the-loai/$genre?page=$page&limit=24"
        } else {
            "${baseUrl()}/danh-sach/$catalogId?page=$page&limit=24"
        }
        val raw = HttpClient.getString(url)
        return parseMovieListResponse(raw, page)
    }

    override suspend fun getGenres(): List<GenreItem> {
        cachedGenres?.let { return it }
        val raw = HttpClient.getString("${baseUrl()}/the-loai")
        val root = HttpClient.json.parseToJsonElement(raw)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"]?.jsonObject?.get("items")?.jsonArray
                ?: root["items"]?.jsonArray
            else -> null
        } ?: return emptyList()

        val genres = array.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            GenreItem(slug = slug, name = name)
        }
        cachedGenres = genres
        return genres
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val raw = HttpClient.getString("${baseUrl()}/tim-kiem?keyword=$encoded&page=$page&limit=24")
        return parseMovieListResponse(raw, page).items
    }

    override suspend fun getMovieDetail(id: String, mediaType: MediaType): MovieDetail {
        val raw = HttpClient.getString("${baseUrl()}/phim/$id")
        return parseMovieDetailResponse(raw, id)
    }

    override suspend fun resolveStream(episode: EpisodeItem, detail: MovieDetail): List<StreamSource> {
        return StreamResolver.resolveDirectOrEmbed(
            label = episode.serverName.ifBlank { "VSMOV" },
            streamUrl = episode.streamUrl,
            embedUrl = episode.embedUrl,
            defaultHeaders = mapOf(
                "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
                "Referer" to "https://vsmov.com/"
            ),
            k20BaseUrl = k20BaseUrlProvider()
        )
    }

    internal fun parseMovieListResponse(raw: String, page: Int): PagedMovies {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val dataObj = root["data"] as? JsonObject
        val itemsArray = root["items"] as? JsonArray
            ?: dataObj?.get("items") as? JsonArray
            ?: JsonArray(emptyList())

        val items = itemsArray.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            parseMovieItem(obj)
        }

        val pagination = root["pagination"] as? JsonObject
            ?: dataObj?.get("params")?.jsonObject?.get("pagination") as? JsonObject
        val totalPages = pagination?.get("totalPages")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val hasMore = if (totalPages != null) page < totalPages else items.size >= 10

        return PagedMovies(items = items, page = page, hasMore = hasMore)
    }

    internal fun parseMovieDetailResponse(raw: String, fallbackSlug: String): MovieDetail {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val movieObj = root["movie"]?.jsonObject ?: root
        val item = parseMovieItem(movieObj)
            ?: MovieItem(source = SourceId.VSMOV, id = fallbackSlug, title = fallbackSlug)

        val genres = (movieObj["category"] as? JsonArray)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        val countries = (movieObj["country"] as? JsonArray)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        val directors = (movieObj["director"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val cast = (movieObj["actor"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val servers = (root["episodes"] as? JsonArray)?.mapNotNull { serverElem ->
            val serverObj = serverElem as? JsonObject ?: return@mapNotNull null
            val serverName = serverObj["server_name"]?.jsonPrimitive?.contentOrNull
                ?.replace(Regex("\\s+"), " ")?.trim().orEmpty().ifBlank { "VSMOV" }
            val serverData = serverObj["server_data"] as? JsonArray ?: return@mapNotNull null
            val eps = serverData.mapIndexedNotNull { index, epElem ->
                val epObj = epElem as? JsonObject ?: return@mapNotNull null
                val rawName = epObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val displayName = when {
                    rawName.isBlank() -> "Tập ${index + 1}"
                    rawName.all { it.isDigit() } -> "Tập $rawName"
                    else -> rawName
                }
                val slug = epObj["slug"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val m3u8 = epObj["link_m3u8"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val embed = epObj["link_embed"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (m3u8.isBlank() && embed.isBlank()) return@mapNotNull null
                EpisodeItem(
                    id = "${serverName}_${slug.ifBlank { index.toString() }}",
                    name = displayName,
                    slug = slug,
                    season = 1,
                    episodeNumber = extractEpisodeNumber(rawName, index + 1),
                    serverName = serverName,
                    streamUrl = m3u8,
                    embedUrl = embed
                )
            }
            if (eps.isEmpty()) null else EpisodeServer(serverName = serverName, season = 1, episodes = eps)
        }.orEmpty()

        val tmdbObj = movieObj["tmdb"] as? JsonObject
        val imdbObj = movieObj["imdb"] as? JsonObject

        return MovieDetail(
            item = item,
            description = HttpClient.stripHtml(movieObj["content"]?.jsonPrimitive?.contentOrNull),
            runtime = movieObj["time"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            status = movieObj["episode_current"]?.jsonPrimitive?.contentOrNull
                ?: movieObj["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            episodeTotal = movieObj["episode_total"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            genres = genres,
            countries = countries,
            directors = directors,
            cast = cast,
            servers = servers,
            imdbId = imdbObj?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
            tmdbId = tmdbObj?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }

    private fun parseMovieItem(obj: JsonObject): MovieItem? {
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: slug
        val origin = obj["origin_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val poster = normalizeUrl(obj["poster_url"]?.jsonPrimitive?.contentOrNull.orEmpty())
        val thumb = normalizeUrl(obj["thumb_url"]?.jsonPrimitive?.contentOrNull.orEmpty())
        val tmdbObj = obj["tmdb"] as? JsonObject
        val rating = tmdbObj?.get("vote_average")?.jsonPrimitive?.contentOrNull
            ?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
        val rawType = obj["type"]?.jsonPrimitive?.contentOrNull
            ?: tmdbObj?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val mediaType = if (rawType.equals("series", true) || rawType.equals("tv", true)) {
            MediaType.SERIES
        } else {
            MediaType.MOVIE
        }

        // VSMOV stores portrait cover in thumb_url or poster_url depending on entry; prefer thumb_url for portrait if poster is backdrop
        return MovieItem(
            source = SourceId.VSMOV,
            id = slug,
            title = name,
            originTitle = origin,
            posterUrl = thumb.ifBlank { poster },
            backdropUrl = poster.ifBlank { thumb },
            year = obj["year"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            quality = obj["quality"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            lang = obj["lang"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            episodeCurrent = obj["episode_current"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            rating = rating,
            mediaType = mediaType
        )
    }

    private fun normalizeUrl(raw: String): String {
        if (raw.isBlank()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return "https://vsmov.com/${raw.trimStart('/')}"
    }
}
