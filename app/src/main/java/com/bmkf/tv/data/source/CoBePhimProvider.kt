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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.util.Locale

class CoBePhimProvider(
    private val apiBaseUrlProvider: () -> String,
    private val webBaseUrlProvider: () -> String,
    private val k20BaseUrlProvider: () -> String
) : MovieSourceProvider {

    override val sourceId: SourceId = SourceId.COBEPHIM

    private var cachedGenres: List<GenreItem>? = null
    private var cachedCollections: Map<String, List<MovieItem>> = emptyMap()

    private fun apiBase(): String = apiBaseUrlProvider().trimEnd('/')
    private fun webBase(): String = webBaseUrlProvider().trimEnd('/')

    private fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
        "Origin" to webBase(),
        "Referer" to "${webBase()}/"
    )

    override suspend fun getCatalogTabs(): List<CatalogTab> {
        val genres = runCatching { getGenres() }.getOrDefault(emptyList())
        return listOf(
            CatalogTab("hot", "Phim Hot", MediaType.MOVIE, genres),
            CatalogTab("view-weekly", "Top Tuần", MediaType.MOVIE, genres),
            CatalogTab("phim-le", "Phim lẻ", MediaType.MOVIE, genres),
            CatalogTab("phim-bo", "Phim bộ", MediaType.SERIES, genres),
            CatalogTab("phim-song-ngu", "Song ngữ", MediaType.SERIES, genres),
            CatalogTab("phim-dien-anh-moi-coong", "Điện ảnh mới", MediaType.MOVIE, genres)
        )
    }

    override suspend fun getHomeSections(): List<HomeSection> = coroutineScope {
        val hotDeferred = async {
            runCatching { fetchApiList("${apiBase()}/movies/hot") }.getOrDefault(emptyList())
        }
        val weeklyDeferred = async {
            runCatching { fetchApiList("${apiBase()}/movies/view-weekly") }.getOrDefault(emptyList())
        }

        val sections = mutableListOf<HomeSection>()
        val hot = hotDeferred.await()
        if (hot.isNotEmpty()) {
            sections += HomeSection("cb_hot", "CoBePhim • Phim Hot nổi bật", SourceId.COBEPHIM, "hot", hot)
        }
        val weekly = weeklyDeferred.await()
        if (weekly.isNotEmpty()) {
            sections += HomeSection("cb_weekly", "CoBePhim • Xem nhiều trong tuần", SourceId.COBEPHIM, "view-weekly", weekly)
        }
        sections
    }

    override suspend fun getCatalog(
        catalogId: String,
        page: Int,
        genre: String?
    ): PagedMovies {
        if (!genre.isNullOrBlank()) {
            val url = "${webBase()}/the-loai/$genre?page=$page"
            val html = HttpClient.getString(url)
            val movies = parseMoviesFromNextRsc(html)
            return PagedMovies(items = movies, page = page, hasMore = movies.size >= 12)
        }

        return when (catalogId) {
            "hot" -> PagedMovies(fetchApiList("${apiBase()}/movies/hot"), page = 1, hasMore = false)
            "view-weekly" -> PagedMovies(fetchApiList("${apiBase()}/movies/view-weekly"), page = 1, hasMore = false)
            "phim-le", "phim-bo" -> {
                val html = HttpClient.getString("${webBase()}/$catalogId?page=$page")
                val movies = parseMoviesFromNextRsc(html)
                PagedMovies(items = movies, page = page, hasMore = movies.size >= 12)
            }
            else -> {
                if (cachedCollections.isEmpty()) {
                    runCatching { fetchHomepageCollections() }
                }
                val fromCache = cachedCollections[catalogId]
                if (!fromCache.isNullOrEmpty()) {
                    PagedMovies(fromCache, page = 1, hasMore = false)
                } else {
                    val html = HttpClient.getString("${webBase()}/$catalogId?page=$page")
                    val movies = parseMoviesFromNextRsc(html)
                    PagedMovies(items = movies, page = page, hasMore = movies.size >= 12)
                }
            }
        }
    }

    override suspend fun getGenres(): List<GenreItem> {
        cachedGenres?.let { return it }
        val raw = HttpClient.getString("${apiBase()}/categories", apiHeaders())
        val root = HttpClient.json.parseToJsonElement(raw)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray ?: root["result"] as? JsonArray
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
        return fetchApiList("${apiBase()}/movies/search?keyword=$encoded&page=$page&limit=24")
    }

    override suspend fun getMovieDetail(id: String, mediaType: MediaType): MovieDetail {
        val detailRaw = HttpClient.getString("${apiBase()}/movies/by-slug/$id", apiHeaders())
        val root = HttpClient.json.parseToJsonElement(detailRaw).jsonObject
        val movieObj = root["movie"]?.jsonObject ?: root
        val movieId = movieObj["id"]?.jsonPrimitive?.longOrNull ?: 0L
        val item = parseMovieObj(movieObj)
            ?: MovieItem(source = SourceId.COBEPHIM, id = id, title = id)

        val genres = (movieObj["categories"] as? JsonArray)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        val countries = (movieObj["regions"] as? JsonArray)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        val directors = (movieObj["directors"] as? JsonArray)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        val cast = (movieObj["actors"] as? JsonArray)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()

        val servers = if (movieId > 0L) {
            val epsRaw = runCatching {
                HttpClient.getString("${apiBase()}/episodes/by-idMovie/$movieId", apiHeaders())
            }.getOrNull()
            if (!epsRaw.isNullOrBlank()) parseEpisodesArray(epsRaw) else emptyList()
        } else {
            emptyList()
        }

        return MovieDetail(
            item = item,
            description = HttpClient.stripHtml(movieObj["description"]?.jsonPrimitive?.contentOrNull),
            runtime = movieObj["episode_time"]?.jsonPrimitive?.contentOrNull
                ?.let { if (it.all(Char::isDigit)) "$it phút" else it }.orEmpty(),
            status = movieObj["episode_current"]?.jsonPrimitive?.contentOrNull
                ?: movieObj["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            episodeTotal = movieObj["episode_total"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            genres = genres,
            countries = countries,
            directors = directors,
            cast = cast,
            servers = servers,
            imdbId = movieObj["imdb_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            tmdbId = movieObj["tmdb_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }

    override suspend fun resolveStream(episode: EpisodeItem, detail: MovieDetail): List<StreamSource> {
        val watchUrl = if (episode.coBeEpisodeId > 0L) {
            "${webBase()}/phim/${detail.item.id}/tap-${episode.coBeEpisodeId}"
        } else {
            "${webBase()}/xem-phim/${detail.item.id}"
        }

        val html = HttpClient.getString(watchUrl)
        val rsc = decodeNextRsc(html)
        val parsed = extractEpisodeStreamFromRsc(rsc)
        val results = mutableListOf<StreamSource>()

        if (parsed != null) {
            val (type, link) = parsed
            val isDirect = type.equals("m3u8", ignoreCase = true) || link.contains(".m3u8", ignoreCase = true)
            results += StreamResolver.resolveDirectOrEmbed(
                label = episode.serverName.ifBlank { "CoBePhim" },
                streamUrl = if (isDirect) link else "",
                embedUrl = if (isDirect) "" else link,
                defaultHeaders = apiHeaders(),
                k20BaseUrl = k20BaseUrlProvider()
            )
        }

        // Always allow falling back to the public watch page if a proprietary key placeholder prevented direct HLS
        if (results.none { !it.isWebViewFallback }) {
            results += StreamSource(
                name = "${episode.serverName} • Trình phát CoBePhim",
                url = watchUrl,
                isHls = false,
                isWebViewFallback = true,
                headers = apiHeaders()
            )
        }

        return results.distinctBy { it.url to it.isWebViewFallback }
    }

    private suspend fun fetchApiList(url: String): List<MovieItem> {
        val raw = HttpClient.getString(url, apiHeaders())
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val arr = root["result"] as? JsonArray ?: root["data"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            parseMovieObj(obj)
        }
    }

    private suspend fun fetchHomepageCollections(): List<Triple<String, String, List<MovieItem>>> {
        val raw = HttpClient.getString("${apiBase()}/lists/homepageLists", apiHeaders())
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val collections = root["result"]?.jsonObject?.get("collections") as? JsonArray ?: return emptyList()
        val parsed = mutableListOf<Triple<String, String, List<MovieItem>>>()
        val cache = mutableMapOf<String, List<MovieItem>>()

        collections.forEach { elem ->
            val obj = elem as? JsonObject ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: name
            val moviesArr = obj["movies"] as? JsonArray ?: return@forEach
            val movies = moviesArr.mapNotNull { m ->
                (m as? JsonObject)?.let { parseMovieObj(it) }
            }
            if (movies.isNotEmpty()) {
                parsed += Triple(slug, name, movies)
                cache[slug] = movies
            }
        }
        cachedCollections = cache
        return parsed
    }

    internal fun parseEpisodesArray(raw: String): List<EpisodeServer> {
        val arr = HttpClient.json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        val grouped = LinkedHashMap<Pair<Int, String>, MutableList<EpisodeItem>>()

        arr.forEachIndexed { idx, elem ->
            val obj = elem as? JsonObject ?: return@forEachIndexed
            val epId = obj["id"]?.jsonPrimitive?.longOrNull ?: return@forEachIndexed
            val rawName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val server = obj["server"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().ifBlank { "Server" }
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val season = obj["season_number"]?.jsonPrimitive?.intOrNull ?: 1
            val displayName = when {
                rawName.isBlank() -> "Tập ${idx + 1}"
                rawName.all(Char::isDigit) -> "Tập $rawName"
                else -> rawName
            }

            val key = season to server
            val list = grouped.getOrPut(key) { mutableListOf() }
            list += EpisodeItem(
                id = "cb_$epId",
                name = displayName,
                slug = slug,
                season = season,
                episodeNumber = extractEpisodeNumber(rawName, idx + 1),
                serverName = if (season > 1) "$server (P.$season)" else server,
                coBeEpisodeId = epId
            )
        }

        // Prioritize direct HLS servers (KK, VS, NC) slightly ahead of encrypted 4K if both exist, while keeping all servers selectable
        return grouped.entries.map { (key, eps) ->
            val (season, server) = key
            val sorted = eps.sortedWith(compareBy({ it.episodeNumber }, { it.id }))
            val label = if (grouped.keys.map { it.first }.toSet().size > 1) {
                "$server • Phần $season"
            } else {
                server
            }
            EpisodeServer(serverName = label, season = season, episodes = sorted)
        }.sortedBy { server ->
            when {
                server.serverName.contains("(KK)", ignoreCase = true) -> 0
                server.serverName.contains("(VS)", ignoreCase = true) -> 1
                server.serverName.contains("(NC)", ignoreCase = true) -> 2
                else -> 3
            }
        }
    }

    internal fun parseMovieObj(obj: JsonObject): MovieItem? {
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: slug
        val origin = obj["origin_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val thumb = obj["thumbnail"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val poster = obj["poster"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val typeRaw = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val mediaType = if (typeRaw.equals("series", true) || typeRaw.equals("tv", true)) {
            MediaType.SERIES
        } else {
            MediaType.MOVIE
        }
        val imdbRating = obj["imdb_rating"]?.jsonPrimitive?.contentOrNull
            ?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()

        return MovieItem(
            source = SourceId.COBEPHIM,
            id = slug,
            title = name,
            originTitle = origin,
            posterUrl = thumb.ifBlank { poster },
            backdropUrl = poster.ifBlank { thumb },
            year = obj["publish_year"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            quality = obj["quality"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            lang = obj["language"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            episodeCurrent = obj["episode_current"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            rating = imdbRating,
            mediaType = mediaType,
            description = HttpClient.stripHtml(obj["description"]?.jsonPrimitive?.contentOrNull)
        )
    }

    internal fun decodeNextRsc(html: String): String {
        val regex = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
        val sb = StringBuilder()
        regex.findAll(html).forEach { match ->
            val raw = match.groupValues[1]
            val decoded = runCatching {
                HttpClient.json.decodeFromString<String>("\"$raw\"")
            }.getOrDefault(raw)
            sb.append(decoded)
        }
        return sb.toString()
    }

    internal fun extractEpisodeStreamFromRsc(rsc: String): Pair<String, String>? {
        val regex = Regex(""""episode":\{[^}]*"type":"([^"]+)"[^}]*"link":"([^"]+)"[^}]*\}""")
        val match = regex.find(rsc)
        if (match != null) {
            return match.groupValues[1] to match.groupValues[2]
        }
        // Fallback if key order differs
        val linkRegex = Regex(""""episode":\{([^}]+)\}""")
        val epBlock = linkRegex.find(rsc)?.groupValues?.getOrNull(1) ?: return null
        val link = Regex(""""link":"([^"]+)"""").find(epBlock)?.groupValues?.getOrNull(1) ?: return null
        val type = Regex(""""type":"([^"]+)"""").find(epBlock)?.groupValues?.getOrNull(1) ?: "embed"
        return type to link
    }

    private fun parseMoviesFromNextRsc(html: String): List<MovieItem> {
        val rsc = decodeNextRsc(html)
        val movies = LinkedHashMap<String, MovieItem>()
        val itemRegex = Regex(
            """"id":\d+,"name":"([^"]+)","origin_name":"([^"]*)","slug":"([^"]+)"[^}]*?"thumbnail":"([^"]*)"(?:[^}]*?"poster":"([^"]*)")?"""
        )
        itemRegex.findAll(rsc).forEach { m ->
            val title = m.groupValues[1]
            val origin = m.groupValues[2]
            val slug = m.groupValues[3]
            val thumb = m.groupValues[4]
            val poster = m.groupValues[5]
            if (slug.isNotBlank() && !movies.containsKey(slug)) {
                movies[slug] = MovieItem(
                    source = SourceId.COBEPHIM,
                    id = slug,
                    title = title,
                    originTitle = origin,
                    posterUrl = thumb.ifBlank { poster },
                    backdropUrl = poster.ifBlank { thumb }
                )
            }
        }
        return movies.values.toList()
    }
}
