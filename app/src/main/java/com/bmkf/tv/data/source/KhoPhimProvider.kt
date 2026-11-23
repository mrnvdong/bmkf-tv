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
 * Dual-mode provider for **KhoPhim** (`https://khophim.top/tai-lieu-api`):
 * 1. **Official Publisher API Mode** (`https://kp.streamhub.solutions/publisher` with `Authorization: Bearer pk_live_...`):
 *    Used automatically when the user configures their `pk_live_...` API Key in Settings.
 *    Calls `/blocks`, `/movies`, `/updated`, `/search`, `/categories`, `/category/{type}/{slug}`,
 *    `/movies/{slug}`, and `/movies/{raw_movie_id}/episodes`.
 * 2. **Public Web Mode** (`https://khophim.top`):
 *    Works immediately out-of-the-box without requiring an API key, loading public SSR pages
 *    (`/phim-moi`, `/danh-muc/...`, `/phim/{slug}`) and official signed `embedUrl` links.
 */
class KhoPhimProvider(
    private val apiBaseUrlProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val webBaseUrlProvider: () -> String
) : MovieSourceProvider {

    override val sourceId: SourceId = SourceId.KHOPHIM

    private var cachedGenres: List<GenreItem>? = null
    private var cachedPublicPool: List<MovieItem> = emptyList()

    private fun apiBase(): String =
        apiBaseUrlProvider().trim().ifBlank { "https://kp.streamhub.solutions/publisher" }.trimEnd('/')

    private fun webBase(): String =
        webBaseUrlProvider().trim().ifBlank { "https://khophim.top" }.trimEnd('/')

    private fun apiKey(): String = apiKeyProvider().trim()

    private fun hasApiKey(): Boolean = apiKey().isNotBlank()

    private fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${apiKey()}",
        "Accept" to "application/json"
    )

    override suspend fun getCatalogTabs(): List<CatalogTab> = listOf(
        CatalogTab("newest", "Mới cập nhật", MediaType.MOVIE),
        CatalogTab("hot", "Phim Hot", MediaType.MOVIE),
        CatalogTab("phim-bo", "Phim Bộ", MediaType.SERIES),
        CatalogTab("phim-le", "Phim Lẻ", MediaType.MOVIE),
        CatalogTab("hoat-hinh", "Hoạt Hình", MediaType.SERIES),
        CatalogTab("han-quoc", "Hàn Quốc", MediaType.SERIES),
        CatalogTab("trung-quoc", "Trung Quốc", MediaType.SERIES),
        CatalogTab("au-my", "Âu Mỹ", MediaType.MOVIE)
    )

    override suspend fun getHomeSections(): List<HomeSection> = coroutineScope {
        if (hasApiKey()) {
            val blocksRaw = runCatching {
                HttpClient.getString("${apiBase()}/blocks", headers = authHeaders())
            }.getOrNull()
            if (!blocksRaw.isNullOrBlank()) {
                val sections = parsePublisherBlocks(blocksRaw)
                if (sections.isNotEmpty()) return@coroutineScope sections.take(3)
            }
            val newest = async { runCatching { getCatalog("newest", 1).items }.getOrDefault(emptyList()) }
            val hot = async { runCatching { getCatalog("hot", 1).items }.getOrDefault(emptyList()) }
            return@coroutineScope listOf(
                HomeSection("kp_newest", "KhoPhim • Mới cập nhật", SourceId.KHOPHIM, "newest", newest.await()),
                HomeSection("kp_hot", "KhoPhim • Phim Hot", SourceId.KHOPHIM, "hot", hot.await())
            ).filter { it.movies.isNotEmpty() }
        }

        // Public Web mode out-of-the-box
        val newest = async { runCatching { getCatalog("newest", 1).items }.getOrDefault(emptyList()) }
        val movies = async { runCatching { getCatalog("phim-le", 1).items }.getOrDefault(emptyList()) }
        val newestList = newest.await()
        val moviesList = movies.await()
        cachedPublicPool = (newestList + moviesList).distinctBy { it.id }

        listOf(
            HomeSection("kp_newest", "KhoPhim • Mới cập nhật", SourceId.KHOPHIM, "newest", newestList),
            HomeSection("kp_movies", "KhoPhim • Phim Lẻ FHD", SourceId.KHOPHIM, "phim-le", moviesList)
        ).filter { it.movies.isNotEmpty() }
    }

    override suspend fun getCatalog(
        catalogId: String,
        page: Int,
        genre: String?
    ): PagedMovies {
        if (hasApiKey()) {
            val url = when {
                !genre.isNullOrBlank() -> "${apiBase()}/category/genre/$genre?sort=newest&page=$page&page_size=24"
                catalogId == "hot" -> "${apiBase()}/movies?sort=hot&page=$page&page_size=24"
                catalogId == "newest" -> "${apiBase()}/movies?sort=newest&page=$page&page_size=24"
                catalogId in setOf("phim-bo", "phim-le") ->
                    "${apiBase()}/category/movie_type/$catalogId?sort=newest&page=$page&page_size=24"
                catalogId == "hoat-hinh" ->
                    "${apiBase()}/category/genre/hoat-hinh?sort=newest&page=$page&page_size=24"
                catalogId in setOf("han-quoc", "trung-quoc", "au-my") ->
                    "${apiBase()}/category/country/$catalogId?sort=newest&page=$page&page_size=24"
                else -> "${apiBase()}/movies?sort=newest&page=$page&page_size=24"
            }
            val raw = HttpClient.getString(url, headers = authHeaders())
            return parsePublisherPagedMovies(raw, page)
        }

        val webUrl = when {
            !genre.isNullOrBlank() -> "${webBase()}/danh-muc/genre/$genre?page=$page"
            catalogId == "phim-bo" -> "${webBase()}/danh-muc/movie_type/phim-bo?page=$page"
            catalogId == "phim-le" -> "${webBase()}/danh-muc/movie_type/phim-le?page=$page"
            catalogId == "hoat-hinh" -> "${webBase()}/danh-muc/genre/hoat-hinh?page=$page"
            catalogId in setOf("han-quoc", "trung-quoc", "au-my") ->
                "${webBase()}/danh-muc/country/$catalogId?page=$page"
            else -> "${webBase()}/phim-moi?page=$page"
        }
        val html = HttpClient.getString(webUrl)
        val items = parsePublicListHtml(html)
        if (items.isNotEmpty() && cachedPublicPool.size < 180) {
            cachedPublicPool = (cachedPublicPool + items).distinctBy { it.id }
        }
        return PagedMovies(items = items, page = page, hasMore = items.size >= 24)
    }

    override suspend fun getGenres(): List<GenreItem> {
        cachedGenres?.let { return it }
        if (hasApiKey()) {
            val loaded = runCatching {
                val raw = HttpClient.getString("${apiBase()}/categories/genre", headers = authHeaders())
                parsePublisherGenres(raw)
            }.getOrDefault(emptyList())
            if (loaded.isNotEmpty()) {
                cachedGenres = loaded
                return loaded
            }
        }
        return DEFAULT_GENRES
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> = coroutineScope {
        val clean = query.trim()
        if (clean.length < 2) return@coroutineScope emptyList()

        if (hasApiKey()) {
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val raw = HttpClient.getString(
                "${apiBase()}/search?q=$encoded&page=$page&page_size=24",
                headers = authHeaders()
            )
            return@coroutineScope parsePublisherPagedMovies(raw, page).items
        }

        if (page > 1) return@coroutineScope emptyList()
        // Without a Publisher API key, search across freshly cached public listings (newest + series + movies)
        val pool = if (cachedPublicPool.size >= 50) {
            cachedPublicPool
        } else {
            val tasks = listOf(
                async { runCatching { getCatalog("newest", 1).items }.getOrDefault(emptyList()) },
                async { runCatching { getCatalog("phim-bo", 1).items }.getOrDefault(emptyList()) },
                async { runCatching { getCatalog("phim-le", 1).items }.getOrDefault(emptyList()) }
            )
            tasks.awaitAll().flatten().distinctBy { it.id }.also { cachedPublicPool = it }
        }

        val needle = normalizeForSearch(clean)
        pool.filter { item ->
            normalizeForSearch(item.title).contains(needle) ||
                normalizeForSearch(item.originTitle).contains(needle) ||
                item.id.lowercase().contains(needle.replace(' ', '-'))
        }
    }

    override suspend fun getMovieDetail(id: String, mediaType: MediaType): MovieDetail {
        val slug = id.removePrefix("/phim/").trim('/')
        if (hasApiKey()) {
            val detailRaw = HttpClient.getString("${apiBase()}/movies/$slug", headers = authHeaders())
            val (detail, rawMovieId) = parsePublisherDetail(detailRaw, slug)
            val servers = if (rawMovieId > 0) {
                val epRaw = runCatching {
                    HttpClient.getString("${apiBase()}/movies/$rawMovieId/episodes", headers = authHeaders())
                }.getOrNull()
                if (!epRaw.isNullOrBlank()) parsePublisherEpisodes(epRaw) else emptyList()
            } else emptyList()
            return detail.copy(servers = servers)
        }

        val html = HttpClient.getString("${webBase()}/phim/$slug")
        return parsePublicDetailHtml(html, slug)
    }

    override suspend fun resolveStream(episode: EpisodeItem, detail: MovieDetail): List<StreamSource> {
        val embed = episode.embedUrl.ifBlank { episode.streamUrl }.trim()
        if (embed.isBlank()) return emptyList()

        val referer = "${webBase()}/"
        val isDirectHls = embed.contains(".m3u8", ignoreCase = true)
        return listOf(
            StreamSource(
                name = "${episode.serverName.ifBlank { "KhoPhim" }} • ${episode.name}",
                url = embed,
                isHls = isDirectHls,
                isWebViewFallback = !isDirectHls,
                headers = mapOf(
                    "User-Agent" to HttpClient.DEFAULT_USER_AGENT,
                    "Referer" to referer
                )
            )
        )
    }

    // -------------------------------------------------------------------------
    // Official Publisher API JSON Parsers (`https://kp.streamhub.solutions/publisher`)
    // -------------------------------------------------------------------------

    internal fun parsePublisherPagedMovies(raw: String, fallbackPage: Int): PagedMovies {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val page = root["page"]?.jsonPrimitive?.intOrNull ?: fallbackPage
        val pageSize = root["page_size"]?.jsonPrimitive?.intOrNull ?: 24
        val total = root["total"]?.jsonPrimitive?.intOrNull ?: 0
        val arr = root["items"] as? JsonArray ?: JsonArray(emptyList())
        val items = arr.mapNotNull { elem -> (elem as? JsonObject)?.let(::parsePublisherMovieItem) }
        val hasMore = if (total > 0) page * pageSize < total else items.size >= pageSize
        return PagedMovies(items = items, page = page, hasMore = hasMore)
    }

    internal fun parsePublisherBlocks(raw: String): List<HomeSection> {
        val arr = HttpClient.json.parseToJsonElement(raw).jsonArray
        return arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val keyword = obj["keyword"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: keyword
            val itemsArr = obj["items"] as? JsonArray ?: return@mapNotNull null
            val movies = itemsArr.mapNotNull { m -> (m as? JsonObject)?.let(::parsePublisherMovieItem) }
            if (movies.isEmpty()) return@mapNotNull null
            HomeSection(
                id = "kp_block_$keyword",
                title = "KhoPhim • $title",
                source = SourceId.KHOPHIM,
                catalogId = "newest",
                movies = movies
            )
        }
    }

    internal fun parsePublisherGenres(raw: String): List<GenreItem> {
        val arr = HttpClient.json.parseToJsonElement(raw).jsonArray
        return arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: slug
            GenreItem(slug = slug, name = name)
        }
    }

    internal fun parsePublisherDetail(raw: String, fallbackSlug: String): Pair<MovieDetail, Int> {
        val obj = HttpClient.json.parseToJsonElement(raw).jsonObject
        val rawMovieId = obj["raw_movie_id"]?.jsonPrimitive?.intOrNull
            ?: obj["id"]?.jsonPrimitive?.intOrNull
            ?: 0
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: fallbackSlug
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: slug
        val originName = obj["origin_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val description = HttpClient.stripHtml(obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
        val poster = optimizeTmdbImage(obj["poster_url"]?.jsonPrimitive?.contentOrNull.orEmpty(), isBackdrop = false)
        val thumb = optimizeTmdbImage(obj["thumb_url"]?.jsonPrimitive?.contentOrNull.orEmpty(), isBackdrop = true)
        val year = obj["year"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val quality = obj["quality"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val epTotal = obj["episode_total"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val duration = obj["duration"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val genres = (obj["genres"] as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val countries = (obj["countries"] as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val directors = (obj["directors"] as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val cast = (obj["actors"] as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.orEmpty()

        val movieTypes = (obj["movie_types"] as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val mediaType = if (movieTypes.any { it.contains("Bộ", ignoreCase = true) } || (epTotal.toIntOrNull() ?: 1) > 1) {
            MediaType.SERIES
        } else {
            MediaType.MOVIE
        }

        val item = MovieItem(
            source = SourceId.KHOPHIM,
            id = slug,
            title = title,
            originTitle = originName,
            posterUrl = poster.ifBlank { thumb },
            backdropUrl = thumb.ifBlank { poster },
            year = year,
            quality = quality,
            mediaType = mediaType,
            description = description
        )
        val detail = MovieDetail(
            item = item,
            description = description,
            runtime = duration,
            status = status,
            episodeTotal = epTotal,
            genres = genres,
            countries = countries,
            directors = directors,
            cast = cast
        )
        return detail to rawMovieId
    }

    internal fun parsePublisherEpisodes(raw: String): List<EpisodeServer> {
        val arr = HttpClient.json.parseToJsonElement(raw).jsonArray
        val grouped = LinkedHashMap<String, MutableList<EpisodeItem>>()

        arr.forEachIndexed { idx, elem ->
            val obj = elem as? JsonObject ?: return@forEachIndexed
            val epId = obj["id"]?.jsonPrimitive?.contentOrNull ?: "ep_${idx + 1}"
            val label = obj["episode_label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "Tập ${idx + 1}"
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: "tap-${idx + 1}"
            val orderIdx = obj["order_index"]?.jsonPrimitive?.intOrNull ?: (idx + 1)
            val sources = obj["sources"] as? JsonArray ?: return@forEachIndexed

            sources.forEach { srcElem ->
                val s = srcElem as? JsonObject ?: return@forEach
                val embedUrl = s["embed_url"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: return@forEach
                val langName = s["language_name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "Vietsub"
                val serverName = "KhoPhim ($langName)"
                val list = grouped.getOrPut(serverName) { mutableListOf() }
                list += EpisodeItem(
                    id = "${epId}_${s["video_source_id"]?.jsonPrimitive?.contentOrNull.orEmpty()}",
                    name = label,
                    slug = slug,
                    episodeNumber = orderIdx,
                    serverName = serverName,
                    embedUrl = embedUrl
                )
            }
        }

        return grouped.entries.map { (name, eps) ->
            EpisodeServer(
                serverName = name,
                episodes = eps.sortedBy { it.episodeNumber }
            )
        }
    }

    private fun parsePublisherMovieItem(obj: JsonObject): MovieItem? {
        val itemType = obj["item_type"]?.jsonPrimitive?.contentOrNull
        if (!itemType.isNullOrBlank() && itemType != "movie") return null

        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { slug }
        val poster = optimizeTmdbImage(obj["poster_url"]?.jsonPrimitive?.contentOrNull.orEmpty(), isBackdrop = false)
        val thumb = optimizeTmdbImage(obj["thumb_url"]?.jsonPrimitive?.contentOrNull.orEmpty(), isBackdrop = true)
        val year = obj["year"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val quality = obj["quality"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val latestEp = obj["latest_episode"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val epTotal = obj["episode_total"]?.jsonPrimitive?.intOrNull ?: 1
        val desc = HttpClient.stripHtml(obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty())

        return MovieItem(
            source = SourceId.KHOPHIM,
            id = slug,
            title = title,
            posterUrl = poster.ifBlank { thumb },
            backdropUrl = thumb.ifBlank { poster },
            year = year,
            quality = quality,
            episodeCurrent = latestEp,
            mediaType = if (epTotal > 1) MediaType.SERIES else MediaType.MOVIE,
            description = desc
        )
    }

    // -------------------------------------------------------------------------
    // Public SSR HTML Parsers (`https://khophim.top`)
    // -------------------------------------------------------------------------

    internal fun parsePublicListHtml(html: String): List<MovieItem> {
        val seen = LinkedHashMap<String, MovieItem>()
        PUBLIC_LI_CARD_REGEX.findAll(html).forEach { m ->
            val slug = m.groupValues[1].trim('/')
            if (slug.isBlank() || seen.containsKey(slug)) return@forEach
            val body = m.groupValues[2]

            val rawImg = IMG_SRC_REGEX.find(body)?.groupValues?.getOrNull(1).orEmpty()
            val poster = optimizeTmdbImage(rawImg, isBackdrop = false)
            val titles = SPAN_TITLE_REGEX.findAll(body)
                .map { decodeHtmlEntities(it.groupValues[1]) }
                .filter { it.isNotBlank() }
                .toList()
            val title = titles.getOrNull(0) ?: slug.replace('-', ' ')
            val originTitle = titles.getOrNull(1).orEmpty()

            val year = YEAR_SPAN_REGEX.find(body)?.groupValues?.getOrNull(1).orEmpty()
            val isSeries = body.contains("Phim Bộ", ignoreCase = true)
            val quality = QUALITY_BADGE_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val epBadge = EP_BADGE_REGEX.find(body)?.groupValues?.getOrNull(1)?.let(::decodeHtmlEntities).orEmpty()

            seen[slug] = MovieItem(
                source = SourceId.KHOPHIM,
                id = slug,
                title = title,
                originTitle = originTitle,
                posterUrl = poster,
                backdropUrl = optimizeTmdbImage(rawImg, isBackdrop = true),
                year = year,
                quality = quality.ifBlank { "FHD" },
                episodeCurrent = epBadge,
                mediaType = if (isSeries) MediaType.SERIES else MediaType.MOVIE
            )
        }
        return seen.values.toList()
    }

    internal fun parsePublicDetailHtml(html: String, slug: String): MovieDetail {
        val title = H1_REGEX.find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtmlEntities)
            ?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
        val originTitle = extractMetaField(html, "Tên gốc")
        val year = extractMetaField(html, "Năm")
        val quality = extractMetaField(html, "Chất lượng").ifBlank { "FHD" }
        val status = extractMetaField(html, "Trạng thái")
        val epTotal = extractMetaField(html, "Số tập")
        val runtime = extractMetaField(html, "Thời lượng")
        val description = META_DESC_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.let(::decodeHtmlEntities).orEmpty()

        val images = MEDIA_IMG_REGEX.findAll(html).map { it.value }.distinct().toList()
        val backdrop = optimizeTmdbImage(images.getOrNull(0).orEmpty(), isBackdrop = true)
        val poster = optimizeTmdbImage(images.getOrNull(1) ?: images.getOrNull(0).orEmpty(), isBackdrop = false)

        // Extract episodes JSON from Next.js RSC payload
        val unescaped = html
            .replace("\\\"", "\"")
            .replace("\\\\u0026", "&")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        val episodes = mutableListOf<EpisodeItem>()
        val epsArrayMatch = RSC_EPISODES_REGEX.find(unescaped)
        if (epsArrayMatch != null) {
            runCatching {
                val arr = HttpClient.json.parseToJsonElement(epsArrayMatch.groupValues[1]).jsonArray
                arr.forEachIndexed { idx, elem ->
                    val obj = elem as? JsonObject ?: return@forEachIndexed
                    val epId = obj["id"]?.jsonPrimitive?.contentOrNull ?: "ep_${idx + 1}"
                    val label = obj["label"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: obj["shortLabel"]?.jsonPrimitive?.contentOrNull
                        ?: "Tập ${idx + 1}"
                    val embedUrl = obj["embedUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (embedUrl.startsWith("http://") || embedUrl.startsWith("https://")) {
                        episodes += EpisodeItem(
                            id = epId,
                            name = label,
                            slug = "tap-${idx + 1}",
                            episodeNumber = idx + 1,
                            serverName = "KhoPhim Player",
                            embedUrl = embedUrl
                        )
                    }
                }
            }
        }

        // Fallback to <iframe src="...embed/..."> if RSC format ever shifts
        if (episodes.isEmpty()) {
            IFRAME_EMBED_REGEX.find(unescaped)?.groupValues?.getOrNull(1)?.let { iframeUrl ->
                episodes += EpisodeItem(
                    id = "ep_1",
                    name = "Full",
                    slug = "full",
                    episodeNumber = 1,
                    serverName = "KhoPhim Player",
                    embedUrl = iframeUrl
                )
            }
        }

        val isSeries = episodes.size > 1 || (epTotal.toIntOrNull() ?: 1) > 1
        val item = MovieItem(
            source = SourceId.KHOPHIM,
            id = slug,
            title = title,
            originTitle = originTitle,
            posterUrl = poster,
            backdropUrl = backdrop.ifBlank { poster },
            year = year,
            quality = quality,
            episodeCurrent = episodes.lastOrNull()?.name.orEmpty(),
            mediaType = if (isSeries) MediaType.SERIES else MediaType.MOVIE,
            description = description
        )
        return MovieDetail(
            item = item,
            description = description,
            runtime = runtime,
            status = status,
            episodeTotal = epTotal,
            servers = if (episodes.isNotEmpty()) {
                listOf(EpisodeServer(serverName = "KhoPhim Player", episodes = episodes))
            } else {
                emptyList()
            }
        )
    }

    private fun extractMetaField(html: String, label: String): String {
        val regex = Regex("${Regex.escape(label)}</[^>]+>\\s*<[^>]+>([^<]+)<")
        return regex.find(html)?.groupValues?.getOrNull(1)?.let(::decodeHtmlEntities).orEmpty()
    }

    private fun optimizeTmdbImage(url: String, isBackdrop: Boolean): String {
        if (url.isBlank()) return ""
        val size = if (isBackdrop) "w780" else "w342"
        return url.replace("image.tmdb.org/t/p/original/", "image.tmdb.org/t/p/$size/")
    }

    private fun decodeHtmlEntities(raw: String): String {
        return HttpClient.stripHtml(raw)
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun normalizeForSearch(input: String): String {
        return java.text.Normalizer.normalize(input.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .replace('đ', 'd')
            .trim()
    }

    companion object {
        private val PUBLIC_LI_CARD_REGEX = Regex("""<li><a[^>]+href="/phim/([^"?#]+)"[^>]*>([\s\S]*?)</a></li>""")
        private val IMG_SRC_REGEX = Regex("""<img[^>]+src="(https?://[^"]+)"""")
        private val SPAN_TITLE_REGEX = Regex("""<span[^>]+title="([^"]+)"""")
        private val YEAR_SPAN_REGEX = Regex("""<span>((?:19|20)\d{2})</span>""")
        private val QUALITY_BADGE_REGEX = Regex(""">(4K|FHD|HD|SD|CAM|HDR)<""")
        private val EP_BADGE_REGEX = Regex("""<span class="truncate">((?:Thêm tập|Tập|Full|Hoàn tất)[^<]*)</span>""", RegexOption.IGNORE_CASE)

        private val H1_REGEX = Regex("""<h1[^>]*>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE)
        private val META_DESC_REGEX = Regex("""<meta\s+name="description"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val MEDIA_IMG_REGEX = Regex("""https?://(?:image\.tmdb\.org|phimimg\.com)[^\s"'<>\\]+""")
        private val RSC_EPISODES_REGEX = Regex(""""episodes"\s*:\s*(\[\{[\s\S]*?\}\])""")
        private val IFRAME_EMBED_REGEX = Regex("""<iframe[^>]+src="(https?://[^"]+/embed/[^"]+)"""", RegexOption.IGNORE_CASE)
        private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

        private val DEFAULT_GENRES = listOf(
            GenreItem("hanh-dong", "Hành Động"),
            GenreItem("chinh-kich", "Chính Kịch"),
            GenreItem("co-trang", "Cổ Trang"),
            GenreItem("bi-an", "Bí Ẩn"),
            GenreItem("kinh-di", "Kinh Dị"),
            GenreItem("hai-huoc", "Hài Hước"),
            GenreItem("tam-ly", "Tâm Lý"),
            GenreItem("gay-can", "Gây Cấn"),
            GenreItem("phieu-luu", "Phiêu Lưu"),
            GenreItem("phim-khoa-hoc-vien-tuong", "Viễn Tưởng"),
            GenreItem("gia-dinh", "Gia Đình"),
            GenreItem("lich-su", "Lịch Sử"),
            GenreItem("hoat-hinh", "Hoạt Hình")
        )
    }
}
