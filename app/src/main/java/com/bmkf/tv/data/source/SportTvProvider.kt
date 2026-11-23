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
import com.bmkf.tv.data.network.StreamSpeedTester
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Unified SPORT TV provider combining ALL 6 live sports engines:
 * 1. **Gà Vàng TV** (`gavang69.com`) — Vietnamese BLV Football & NBA Basketball (Tencent/Alibaba CDN HLS + FLV)
 * 2. **Xôi Chè TV** (`xoiche.live`) — Vietnamese BLV Football & Multi-Sport REST API (HLS + FLV)
 * 3. **Cola TV** (`colatv111.live`) — Vietnamese BLV + Official HD Broadcast Football & Basketball (Multi-CDN HLS + FLV)
 * 4. **Xôi Lạc TV** (`xoilacz.ca`) — Vietnamese BLV Football & Multi-Sport (HTTP-FLV / HLS)
 * 5. **HighFly Sports** (`sports.highfly.dev/.../manifest.json`) — Direct FHD 1080P live sports HLS streams
 * 6. **Nuvio Live Sports** (`nuviosports.xyz/.../collections.json` + `manifest.json`) — Live matches, 24/7 TV & Replays
 *
 * Includes automatic proxy-unwrapping to Direct CDN, filtering of donate/tip/unavailable placeholders,
 * and parallel real-time speed benchmarking via [StreamSpeedTester] so non-lagging servers always play first.
 */
class SportTvProvider(
    private val configUrlProvider: () -> String,
    private val highFlyUrlProvider: () -> String = { "" },
    private val xoiLacUrlProvider: () -> String = { "" },
    private val gaVangUrlProvider: () -> String = { "" },
    private val xoiCheUrlProvider: () -> String = { "" },
    private val colaTvUrlProvider: () -> String = { "" }
) : MovieSourceProvider {

    override val sourceId: SourceId = SourceId.SPORT_TV

    private val xoiLacClient = XoiLacClient(entryUrlProvider = xoiLacUrlProvider)
    private val gaVangClient = GaVangClient(entryUrlProvider = gaVangUrlProvider)
    private val xoiCheClient = XoiCheClient(entryUrlProvider = xoiCheUrlProvider)
    private val colaTvClient = ColaTvClient(entryUrlProvider = colaTvUrlProvider)
    private var cachedConfigKey: String? = null
    private var cachedTabs: List<CatalogTab>? = null

    fun baseUrl(): String {
        val raw = configUrlProvider().trim()
        return raw
            .removeSuffix("/collections.json")
            .removeSuffix("/manifest.json")
            .trimEnd('/')
    }

    fun highFlyBaseUrl(): String {
        val raw = highFlyUrlProvider().trim()
        return raw
            .removeSuffix("/manifest.json")
            .trimEnd('/')
    }

    override suspend fun getCatalogTabs(): List<CatalogTab> = coroutineScope {
        val nuvioUrl = configUrlProvider().trim()
        val hfUrl = highFlyUrlProvider().trim()
        val xlUrl = xoiLacUrlProvider().trim()
        val gvUrl = gaVangUrlProvider().trim()
        val xcUrl = xoiCheUrlProvider().trim()
        val clUrl = colaTvUrlProvider().trim()
        val key = "$nuvioUrl|$hfUrl|$xlUrl|$gvUrl|$xcUrl|$clUrl"
        if (cachedConfigKey == key && !cachedTabs.isNullOrEmpty()) {
            return@coroutineScope cachedTabs!!
        }

        val nuvioBase = baseUrl()
        val hfBase = highFlyBaseUrl()

        val hfManifestDeferred = async {
            if (hfBase.isNotBlank()) runCatching { HttpClient.getString("$hfBase/manifest.json") }.getOrNull() else null
        }
        val nuvioManifestDeferred = async {
            if (nuvioBase.isNotBlank()) runCatching { HttpClient.getString("$nuvioBase/manifest.json") }.getOrNull() else null
        }
        val nuvioCollectionsDeferred = async {
            if (nuvioBase.isNotBlank()) {
                val colUrl = if (nuvioUrl.endsWith("/collections.json")) nuvioUrl else "$nuvioBase/collections.json"
                runCatching { HttpClient.getString(colUrl) }.getOrNull()
            } else null
        }

        val hfTabs = hfManifestDeferred.await()?.let(::parseHighFlyManifestTabs).orEmpty()
        val manifestTabs = nuvioManifestDeferred.await()?.let(::parseManifestTabs).orEmpty()
        val collectionTabs = nuvioCollectionsDeferred.await()?.let(::parseCollectionsTabs).orEmpty()

        val merged = LinkedHashMap<String, CatalogTab>()

        // 1. Gà Vàng TV (VN BLV + Tencent/Alibaba HLS & FLV)
        if (gvUrl.isNotBlank()) {
            merged["gv|football"] = CatalogTab("gv|football", "🐔 Gà Vàng • Bóng đá", MediaType.TV)
            merged["gv|live"] = CatalogTab("gv|live", "🐔 Gà Vàng • Đang đấu", MediaType.TV)
            merged["gv|basketball"] = CatalogTab("gv|basketball", "🐔 Gà Vàng • Bóng rổ NBA", MediaType.TV)
        }

        // 2. Xôi Chè TV (VN BLV REST JSON API)
        if (xcUrl.isNotBlank()) {
            merged["xc|all"] = CatalogTab("xc|all", "🍵 Xôi Chè • Trực tiếp & Lịch", MediaType.TV)
            merged["xc|live"] = CatalogTab("xc|live", "🍵 Xôi Chè • Đang đấu", MediaType.TV)
        }

        // 3. Cola TV (VN BLV + Official HD Broadcast Multi-CDN)
        if (clUrl.isNotBlank()) {
            merged["cl|football"] = CatalogTab("cl|football", "🥤 Cola TV • Bóng đá", MediaType.TV)
            merged["cl|live"] = CatalogTab("cl|live", "🥤 Cola TV • Đang đấu", MediaType.TV)
            merged["cl|basketball"] = CatalogTab("cl|basketball", "🥤 Cola TV • Bóng rổ", MediaType.TV)
        }

        // 4. Xôi Lạc TV (VN BLV + fastest 10+ Mbps local CDN)
        if (xlUrl.isNotBlank()) {
            merged["xl|football"] = CatalogTab("xl|football", "🇻🇳 Xôi Lạc • Bóng đá", MediaType.TV)
            merged["xl|live"] = CatalogTab("xl|live", "🇻🇳 Xôi Lạc • Đang đấu", MediaType.TV)
            merged["xl|other"] = CatalogTab("xl|other", "🇻🇳 Xôi Lạc • Môn khác", MediaType.TV)
        }

        // 5. HighFly FHD Live Sports tabs
        hfTabs.forEach { tab -> merged[tab.id] = tab }

        // 2. Priority Nuvio Live Sports tabs
        val priorityOrder = listOf(
            "tv|nuvio_sports_live",
            "tv|nuvio_sports_football",
            "tv|nuvio_sports_mma",
            "tv|nuvio_sports_networks",
            "tv|nuvio_sports_upcoming"
        )
        priorityOrder.forEach { k ->
            manifestTabs.firstOrNull { it.id == k }?.let { merged[it.id] = it }
        }
        manifestTabs.forEach { tab ->
            if (!merged.containsKey(tab.id)) merged[tab.id] = tab
        }
        collectionTabs.forEach { tab ->
            if (!merged.containsKey(tab.id)) merged[tab.id] = tab
        }

        val finalTabs = merged.values.toList().ifEmpty { defaultFallbackTabs() }
        cachedConfigKey = key
        cachedTabs = finalTabs
        finalTabs
    }

    override suspend fun getHomeSections(): List<HomeSection> = coroutineScope {
        val gvDeferred = async {
            if (gaVangUrlProvider().isNotBlank()) {
                runCatching { gaVangClient.fetchMatches("football") }.getOrDefault(emptyList())
            } else emptyList()
        }
        val clDeferred = async {
            if (colaTvUrlProvider().isNotBlank()) {
                runCatching { colaTvClient.fetchMatches("football") }.getOrDefault(emptyList())
            } else emptyList()
        }
        val xcDeferred = async {
            if (xoiCheUrlProvider().isNotBlank()) {
                runCatching { xoiCheClient.fetchMatches("all") }.getOrDefault(emptyList())
            } else emptyList()
        }
        val xlDeferred = async {
            if (xoiLacUrlProvider().isNotBlank()) {
                runCatching { xoiLacClient.fetchMatches("football") }.getOrDefault(emptyList())
            } else emptyList()
        }

        listOf(
            HomeSection("sport_gv_live", "Sport TV • 🐔 Gà Vàng Bóng đá BLV Việt", SourceId.SPORT_TV, "gv|football", gvDeferred.await()),
            HomeSection("sport_cl_live", "Sport TV • 🥤 Cola TV Bóng đá BLV Việt", SourceId.SPORT_TV, "cl|football", clDeferred.await()),
            HomeSection("sport_xc_live", "Sport TV • 🍵 Xôi Chè Bóng đá BLV Việt", SourceId.SPORT_TV, "xc|all", xcDeferred.await()),
            HomeSection("sport_xl_live", "Sport TV • 🇻🇳 Xôi Lạc Bóng đá BLV Việt", SourceId.SPORT_TV, "xl|football", xlDeferred.await())
        ).filter { it.movies.isNotEmpty() }
    }

    override suspend fun getCatalog(
        catalogId: String,
        page: Int,
        genre: String?
    ): PagedMovies {
        if (catalogId.startsWith("gv|")) {
            if (page > 1) return PagedMovies(emptyList(), page, false)
            val filter = catalogId.removePrefix("gv|")
            val items = gaVangClient.fetchMatches(filter, bypassCache = false)
            return PagedMovies(items = items, page = 1, hasMore = false)
        }

        if (catalogId.startsWith("xc|")) {
            if (page > 1) return PagedMovies(emptyList(), page, false)
            val filter = catalogId.removePrefix("xc|")
            val items = xoiCheClient.fetchMatches(filter, bypassCache = false)
            return PagedMovies(items = items, page = 1, hasMore = false)
        }

        if (catalogId.startsWith("cl|")) {
            if (page > 1) return PagedMovies(emptyList(), page, false)
            val filter = catalogId.removePrefix("cl|")
            val items = colaTvClient.fetchMatches(filter, bypassCache = false)
            return PagedMovies(items = items, page = 1, hasMore = false)
        }

        if (catalogId.startsWith("xl|")) {
            if (page > 1) return PagedMovies(emptyList(), page, false)
            val filter = catalogId.removePrefix("xl|")
            val items = xoiLacClient.fetchMatches(filter, bypassCache = false)
            return PagedMovies(items = items, page = 1, hasMore = false)
        }

        val isHighFly = catalogId.startsWith("hf|")
        val (type, id) = splitCatalogKey(catalogId)
        val skip = (page - 1) * PAGE_SIZE
        val extraParts = mutableListOf<String>()
        if (!genre.isNullOrBlank()) {
            extraParts += "genre=${encodePathSegment(genre)}"
        }
        if (skip > 0) {
            extraParts += "skip=$skip"
        }
        val suffix = if (extraParts.isNotEmpty()) "/${extraParts.joinToString("&")}" else ""
        val targetBase = if (isHighFly) highFlyBaseUrl() else baseUrl()
        if (targetBase.isBlank()) return PagedMovies(emptyList(), page, false)

        val url = "$targetBase/catalog/$type/$id$suffix.json"
        val raw = HttpClient.getString(url)
        val items = parseCatalogMetas(raw, isHighFly = isHighFly)
        return PagedMovies(items = items, page = page, hasMore = items.size >= 25)
    }

    override suspend fun getGenres(): List<GenreItem> = DEFAULT_SPORT_GENRES

    override suspend fun search(query: String, page: Int): List<MovieItem> = coroutineScope {
        if (page > 1) return@coroutineScope emptyList()
        val encoded = encodePathSegment(query.trim())

        val gvTask = if (gaVangUrlProvider().isNotBlank()) {
            listOf(async { runCatching { gaVangClient.searchMatches(query) }.getOrDefault(emptyList()) })
        } else emptyList()

        val xcTask = if (xoiCheUrlProvider().isNotBlank()) {
            listOf(async { runCatching { xoiCheClient.searchMatches(query) }.getOrDefault(emptyList()) })
        } else emptyList()

        val clTask = if (colaTvUrlProvider().isNotBlank()) {
            listOf(async { runCatching { colaTvClient.searchMatches(query) }.getOrDefault(emptyList()) })
        } else emptyList()

        val xlTask = if (xoiLacUrlProvider().isNotBlank()) {
            listOf(async { runCatching { xoiLacClient.searchMatches(query) }.getOrDefault(emptyList()) })
        } else emptyList()

        val hfTasks = if (highFlyBaseUrl().isNotBlank()) {
            listOf("sports_live", "sports_today", "sports_football").map { catId ->
                async {
                    runCatching {
                        val raw = HttpClient.getString("${highFlyBaseUrl()}/catalog/sport/$catId/search=$encoded.json")
                        parseCatalogMetas(raw, isHighFly = true)
                    }.getOrDefault(emptyList())
                }
            }
        } else emptyList()

        val nuvioTasks = listOf(
            "nuvio_sports_live",
            "nuvio_sports_networks",
            "nuvio_sports_football",
            "nuvio_sports_mma"
        ).map { catId ->
            async {
                runCatching {
                    val raw = HttpClient.getString("${baseUrl()}/catalog/tv/$catId/search=$encoded.json")
                    parseCatalogMetas(raw, isHighFly = false)
                }.getOrDefault(emptyList())
            }
        }

        (gvTask + xcTask + clTask + xlTask + hfTasks + nuvioTasks).awaitAll().flatten().distinctBy { it.id }
    }

    override suspend fun getMovieDetail(id: String, mediaType: MediaType): MovieDetail {
        if (GaVangClient.isGaVangId(id)) {
            val cleanTitle = id.removePrefix(GaVangClient.GV_ID_PREFIX)
                .trim('/').substringAfterLast('/').replace('-', ' ')
            return MovieDetail(
                item = MovieItem(
                    source = SourceId.SPORT_TV,
                    id = id,
                    title = cleanTitle,
                    quality = "🐔 LIVE",
                    mediaType = MediaType.TV
                ),
                servers = listOf(defaultLiveServer(id))
            )
        }

        if (XoiCheClient.isXoiCheId(id)) {
            val cleanTitle = id.removePrefix(XoiCheClient.XC_ID_PREFIX)
                .trim('/').replace('-', ' ')
            return MovieDetail(
                item = MovieItem(
                    source = SourceId.SPORT_TV,
                    id = id,
                    title = cleanTitle,
                    quality = "🍵 LIVE",
                    mediaType = MediaType.TV
                ),
                servers = listOf(defaultLiveServer(id))
            )
        }

        if (ColaTvClient.isColaTvId(id)) {
            val cleanTitle = id.removePrefix(ColaTvClient.CL_ID_PREFIX)
                .trim('/').replace('-', ' ')
            return MovieDetail(
                item = MovieItem(
                    source = SourceId.SPORT_TV,
                    id = id,
                    title = cleanTitle,
                    quality = "🥤 LIVE",
                    mediaType = MediaType.TV
                ),
                servers = listOf(defaultLiveServer(id))
            )
        }

        if (XoiLacClient.isXoiLacId(id)) {
            val cleanTitle = id.removePrefix(XoiLacClient.XL_ID_PREFIX)
                .trim('/').substringAfterLast('/').replace('-', ' ')
            return MovieDetail(
                item = MovieItem(
                    source = SourceId.SPORT_TV,
                    id = id,
                    title = cleanTitle,
                    quality = "🇻🇳 LIVE",
                    mediaType = MediaType.TV
                ),
                servers = listOf(defaultLiveServer(id))
            )
        }

        val isHighFly = isHighFlyItemId(id)
        val rawId = stripHighFlyPrefix(id)
        val encodedId = encodePathSegment(rawId)
        val metaUrl = if (isHighFly) {
            "${highFlyBaseUrl()}/meta/sport/$encodedId.json"
        } else {
            "${baseUrl()}/meta/tv/$encodedId.json"
        }
        val metaRaw = runCatching { HttpClient.getString(metaUrl) }.getOrNull()
        if (!metaRaw.isNullOrBlank()) {
            return parseMetaDetail(metaRaw, id, isHighFly)
        }

        val item = MovieItem(
            source = SourceId.SPORT_TV,
            id = id,
            title = rawId,
            quality = if (isHighFly) "FHD LIVE" else "LIVE",
            mediaType = MediaType.TV
        )
        return MovieDetail(
            item = item,
            servers = listOf(defaultLiveServer(id))
        )
    }

    override suspend fun resolveStream(episode: EpisodeItem, detail: MovieDetail): List<StreamSource> {
        val videoId = episode.k20VideoId.ifBlank { detail.item.id }
        if (GaVangClient.isGaVangId(videoId)) {
            val gvStreams = gaVangClient.resolveMatchStreams(videoId)
            return StreamSpeedTester.rankFastestStreams(gvStreams)
        }
        if (XoiCheClient.isXoiCheId(videoId)) {
            val xcStreams = xoiCheClient.resolveMatchStreams(videoId)
            return StreamSpeedTester.rankFastestStreams(xcStreams)
        }
        if (ColaTvClient.isColaTvId(videoId)) {
            val clStreams = colaTvClient.resolveMatchStreams(videoId)
            return StreamSpeedTester.rankFastestStreams(clStreams)
        }
        if (XoiLacClient.isXoiLacId(videoId)) {
            val xlStreams = xoiLacClient.resolveMatchStreams(videoId)
            return StreamSpeedTester.rankFastestStreams(xlStreams)
        }

        val isHighFly = isHighFlyItemId(videoId)
        val rawId = stripHighFlyPrefix(videoId)
        val encodedId = encodePathSegment(rawId)

        val streamUrl = if (isHighFly) {
            "${highFlyBaseUrl()}/stream/sport/$encodedId.json"
        } else {
            "${baseUrl()}/stream/tv/$encodedId.json"
        }
        val raw = HttpClient.getString(streamUrl)
        val parsed = parseStreamsResponse(raw)
        // Benchmark candidate direct servers in parallel from the TV's actual network so fastest server plays first
        return StreamSpeedTester.rankFastestStreams(parsed)
    }

    internal fun parseHighFlyManifestTabs(raw: String): List<CatalogTab> {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val catalogs = root["catalogs"] as? JsonArray ?: return emptyList()
        return catalogs.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "sport"
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawName = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
            val friendlyName = when (id) {
                "sports_live" -> "⚡ HighFly • Trực tiếp FHD"
                "sports_today" -> "⚡ HighFly • Hôm nay"
                "sports_football" -> "⚡ HighFly • Bóng đá"
                else -> "⚡ HighFly • $rawName"
            }
            CatalogTab(
                id = "hf|$type|$id",
                title = friendlyName,
                mediaType = MediaType.TV
            )
        }
    }

    internal fun parseManifestTabs(raw: String): List<CatalogTab> {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val catalogs = root["catalogs"] as? JsonArray ?: return emptyList()
        return catalogs.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "tv"
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawName = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
            val friendlyName = when (id) {
                "nuvio_sports_live" -> "🔴 Nuvio • Trực tiếp"
                "nuvio_sports_networks" -> "📺 Kênh TV 24/7"
                "nuvio_sports_football" -> "⚽ Bóng đá"
                "nuvio_sports_mma" -> "🥊 MMA / UFC"
                "nuvio_sports_upcoming" -> "⏱️ Sắp diễn ra"
                "nuvio_sports_replays" -> "⏪ Xem lại tất cả"
                "nuvio_sports_replays_football_today" -> "📅 Bóng đá hôm nay"
                else -> rawName
            }
            val genres = mutableListOf<GenreItem>()
            (obj["extra"] as? JsonArray)?.forEach { extraElem ->
                val eObj = extraElem as? JsonObject ?: return@forEach
                if (eObj["name"]?.jsonPrimitive?.contentOrNull == "genre") {
                    (eObj["options"] as? JsonArray)?.forEach { opt ->
                        val g = opt.jsonPrimitive.contentOrNull.orEmpty()
                        if (g.isNotBlank()) genres += GenreItem(slug = g, name = translateSportGenre(g))
                    }
                }
            }
            CatalogTab(
                id = "$type|$id",
                title = friendlyName,
                mediaType = MediaType.TV,
                genres = genres
            )
        }
    }

    internal fun parseCollectionsTabs(raw: String): List<CatalogTab> {
        val arr = HttpClient.json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        val tabs = mutableListOf<CatalogTab>()
        arr.forEach { colElem ->
            val colObj = colElem as? JsonObject ?: return@forEach
            val folders = colObj["folders"] as? JsonArray ?: return@forEach
            folders.forEach { folderElem ->
                val folderObj = folderElem as? JsonObject ?: return@forEach
                val folderTitle = folderObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val sources = folderObj["catalogSources"] as? JsonArray ?: return@forEach
                sources.forEach { srcElem ->
                    val sObj = srcElem as? JsonObject ?: return@forEach
                    val catId = sObj["catalogId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val type = sObj["type"]?.jsonPrimitive?.contentOrNull ?: "tv"
                    val catName = sObj["catalogName"]?.jsonPrimitive?.contentOrNull ?: catId
                    val keep = catId.endsWith("_today") ||
                        catId.endsWith("_premier_league") ||
                        catId.endsWith("_ucl") ||
                        catId.endsWith("_f1") ||
                        catId.endsWith("_nba") ||
                        catId.endsWith("_atp") ||
                        catId.endsWith("_nfl") ||
                        catId.endsWith("_nhl") ||
                        catId.endsWith("_mlb")
                    if (keep) {
                        val title = if (folderTitle.isNotBlank() && !catName.contains(folderTitle, ignoreCase = true)) {
                            "$folderTitle • $catName"
                        } else {
                            catName
                        }
                        tabs += CatalogTab(
                            id = "$type|$catId",
                            title = title,
                            mediaType = MediaType.TV
                        )
                    }
                }
            }
        }
        return tabs
    }

    internal fun parseCatalogMetas(raw: String, isHighFly: Boolean = false): List<MovieItem> {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val metas = root["metas"] as? JsonArray ?: return emptyList()
        return metas.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val rawId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val storedId = if (isHighFly) "$HF_ID_PREFIX$rawId" else rawId
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: rawId
            val poster = obj["poster"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val bg = obj["background"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val desc = HttpClient.stripHtml(obj["description"]?.jsonPrimitive?.contentOrNull)
            val releaseInfo = obj["releaseInfo"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val genres = (obj["genres"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            val badge = when {
                isHighFly && (releaseInfo.equals("LIVE", true) || desc.contains("LIVE NOW", true)) -> "⚡ FHD LIVE"
                isHighFly -> "⚡ HIGHFLY"
                name.contains("LIVE", ignoreCase = true) -> "LIVE"
                genres.any { it.equals("NETWORKS", true) } -> "24/7 TV"
                name.contains("⏪") -> "REPLAY"
                else -> genres.firstOrNull().orEmpty().ifBlank { "SPORT" }
            }

            MovieItem(
                source = SourceId.SPORT_TV,
                id = storedId,
                title = name,
                originTitle = genres.take(2).joinToString(" • "),
                posterUrl = poster.ifBlank { bg },
                backdropUrl = bg.ifBlank { poster },
                quality = badge,
                episodeCurrent = releaseInfo,
                mediaType = MediaType.TV,
                description = desc
            )
        }
    }

    internal fun parseMetaDetail(raw: String, fallbackId: String, isHighFly: Boolean = false): MovieDetail {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val meta = root["meta"] as? JsonObject ?: root
        val rawId = meta["id"]?.jsonPrimitive?.contentOrNull ?: stripHighFlyPrefix(fallbackId)
        val storedId = if (isHighFly) "$HF_ID_PREFIX$rawId" else rawId
        val name = meta["name"]?.jsonPrimitive?.contentOrNull ?: rawId
        val poster = meta["poster"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val bg = meta["background"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val desc = HttpClient.stripHtml(meta["description"]?.jsonPrimitive?.contentOrNull)
        val genres = (meta["genres"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

        val item = MovieItem(
            source = SourceId.SPORT_TV,
            id = storedId,
            title = name,
            originTitle = genres.take(2).joinToString(" • "),
            posterUrl = poster.ifBlank { bg },
            backdropUrl = bg.ifBlank { poster },
            quality = if (isHighFly) "⚡ FHD LIVE" else if (name.contains("LIVE", true)) "LIVE" else "SPORT TV",
            mediaType = MediaType.TV,
            description = desc
        )

        return MovieDetail(
            item = item,
            description = desc,
            genres = genres,
            status = if (name.contains("LIVE", true) || isHighFly) "Đang phát trực tiếp" else "Sport TV",
            servers = listOf(defaultLiveServer(storedId))
        )
    }

    internal fun parseStreamsResponse(raw: String): List<StreamSource> {
        val root = HttpClient.json.parseToJsonElement(raw).jsonObject
        val streams = root["streams"] as? JsonArray ?: return emptyList()

        data class ScoredStream(val score: Int, val source: StreamSource)

        val parsed = mutableListOf<ScoredStream>()

        streams.forEach { elem ->
            val obj = elem as? JsonObject ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val externalUrl = obj["externalUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ytId = obj["ytId"]?.jsonPrimitive?.contentOrNull.orEmpty()

            // Filter out donation promos, HighFly external-player tips, and unavailable placeholders
            if (isNonPlayablePlaceholder(name, url, externalUrl)) {
                return@forEach
            }

            val resolvedWebUrl = externalUrl.ifBlank {
                if (ytId.isNotBlank()) "https://www.youtube.com/watch?v=$ytId" else ""
            }
            val targetUrl = url.ifBlank { resolvedWebUrl }
            if (targetUrl.isBlank()) return@forEach

            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .replace("\n", " • ")
                .trim()
            val shortChannel = title.substringBefore("📺 Quality").trim().take(64)
            val res = obj["resolution"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val score = obj["score"]?.jsonPrimitive?.intOrNull ?: 50
            val isDirect = url.isNotBlank()

            val headers = mutableMapOf<String, String>()
            val reqHeaders = obj["behaviorHints"]?.jsonObject
                ?.get("proxyHeaders")?.jsonObject
                ?.get("request") as? JsonObject
            reqHeaders?.forEach { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { headers[k] = it }
            }

            // Unwrap overloaded `nuviosports.xyz/api/manifest.m3u8?url=...` proxy URLs to Direct CDN
            val unwrappedOrigin = if (isDirect) unwrapNuvioProxyUrl(targetUrl) else null

            if (unwrappedOrigin != null) {
                val directLabel = if (shortChannel.isNotBlank()) "$shortChannel (CDN)" else "CDN Trực tiếp"
                parsed += ScoredStream(
                    score = 20_000 + score,
                    source = StreamSource(
                        name = directLabel,
                        url = unwrappedOrigin,
                        isHls = unwrappedOrigin.contains(".m3u8", ignoreCase = true),
                        isWebViewFallback = false,
                        headers = headers,
                        qualityLabel = res
                    )
                )
                val proxyLabel = if (shortChannel.isNotBlank()) "$shortChannel (Proxy dự phòng)" else "Proxy dự phòng"
                parsed += ScoredStream(
                    score = 2_000 + score,
                    source = StreamSource(
                        name = proxyLabel,
                        url = targetUrl,
                        isHls = targetUrl.contains(".m3u8", ignoreCase = true),
                        isWebViewFallback = false,
                        headers = headers,
                        qualityLabel = res
                    )
                )
            } else {
                val displayName = buildString {
                    append(name.ifBlank { if (isDirect) "Direct Stream" else "Web Stream" })
                    if (shortChannel.isNotBlank()) append(" — ").append(shortChannel)
                }
                val isHls = isDirect && targetUrl.contains(".m3u8", ignoreCase = true)
                parsed += ScoredStream(
                    score = (if (isDirect) 15_000 else 0) + score,
                    source = StreamSource(
                        name = displayName,
                        url = targetUrl,
                        isHls = isHls,
                        isWebViewFallback = !isDirect,
                        headers = headers,
                        qualityLabel = res
                    )
                )
            }
        }

        return parsed
            .sortedByDescending { it.score }
            .map { it.source }
            .distinctBy { it.url to it.isWebViewFallback }
    }

    internal fun unwrapNuvioProxyUrl(url: String): String? {
        if (!url.contains("nuviosports.xyz/api/manifest.m3u8", ignoreCase = true)) return null
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return null
        val urlParam = query.split('&')
            .firstOrNull { it.startsWith("url=") }
            ?.substringAfter("url=")
            ?: return null
        val decoded = runCatching { URLDecoder.decode(urlParam, "UTF-8") }.getOrNull().orEmpty()
        return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun isNonPlayablePlaceholder(name: String, url: String, externalUrl: String): Boolean {
        val lowerName = name.lowercase()
        val lowerUrl = url.lowercase()
        val lowerExt = externalUrl.lowercase()
        return lowerExt.contains("/donate") ||
            lowerUrl.contains("/donate") ||
            lowerName.contains("support us") ||
            lowerUrl.endsWith("/health") ||
            lowerName.startsWith("tip:") ||
            lowerName.contains("streams unavailable") ||
            lowerUrl == "https://www.google.com" ||
            lowerUrl == "http://www.google.com"
    }

    private fun isHighFlyItemId(id: String): Boolean {
        return id.startsWith(HF_ID_PREFIX) ||
            id.startsWith("leaf:") ||
            id.startsWith("streamed:") ||
            id.startsWith("recap:")
    }

    private fun stripHighFlyPrefix(id: String): String = id.removePrefix(HF_ID_PREFIX)

    private fun defaultLiveServer(id: String): EpisodeServer = EpisodeServer(
        serverName = "Sport TV Trực tiếp",
        season = 1,
        episodes = listOf(
            EpisodeItem(
                id = id,
                name = "Xem trực tiếp",
                season = 1,
                episodeNumber = 1,
                serverName = "Sport TV",
                k20VideoId = id
            )
        )
    )

    private fun splitCatalogKey(key: String): Pair<String, String> {
        val normalized = key.removePrefix("hf|")
        val parts = normalized.split("|", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else "tv" to normalized
    }

    private fun encodePathSegment(raw: String): String {
        return URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
    }

    private fun translateSportGenre(raw: String): String = when (raw.lowercase()) {
        "football" -> "Bóng đá"
        "basketball" -> "Bóng rổ"
        "motorsport" -> "Đua xe / F1"
        "tennis" -> "Quần vợt"
        "mma" -> "MMA / UFC"
        "baseball" -> "Bóng chày"
        "hockey" -> "Khúc côn cầu"
        "rugby" -> "Bóng bầu dục"
        "american football" -> "NFL"
        "cricket" -> "Cricket"
        "golf" -> "Golf"
        "darts" -> "Phi tiêu"
        "other" -> "Khác"
        else -> raw
    }

    private fun defaultFallbackTabs(): List<CatalogTab> = listOf(
        CatalogTab("hf|sport|sports_live", "⚡ HighFly • Trực tiếp FHD", MediaType.TV),
        CatalogTab("hf|sport|sports_today", "⚡ HighFly • Hôm nay", MediaType.TV),
        CatalogTab("hf|sport|sports_football", "⚡ HighFly • Bóng đá", MediaType.TV),
        CatalogTab("tv|nuvio_sports_live", "🔴 Nuvio • Trực tiếp", MediaType.TV, DEFAULT_SPORT_GENRES),
        CatalogTab("tv|nuvio_sports_football", "⚽ Bóng đá", MediaType.TV),
        CatalogTab("tv|nuvio_sports_mma", "🥊 MMA / UFC", MediaType.TV),
        CatalogTab("tv|nuvio_sports_networks", "📺 Kênh TV 24/7", MediaType.TV)
    )

    companion object {
        private const val HF_ID_PREFIX = "hf::"
        private const val PAGE_SIZE = 25

        private val DEFAULT_SPORT_GENRES = listOf(
            GenreItem("Football", "Bóng đá"),
            GenreItem("MMA", "MMA / UFC"),
            GenreItem("Basketball", "Bóng rổ"),
            GenreItem("Tennis", "Quần vợt"),
            GenreItem("Motorsport", "Đua xe / F1"),
            GenreItem("Baseball", "Bóng chày"),
            GenreItem("Hockey", "Khúc côn cầu"),
            GenreItem("Rugby", "Bóng bầu dục"),
            GenreItem("American Football", "NFL"),
            GenreItem("Golf", "Golf")
        )
    }
}
