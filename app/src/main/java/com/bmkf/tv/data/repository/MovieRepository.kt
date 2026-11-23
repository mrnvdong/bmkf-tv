package com.bmkf.tv.data.repository

import com.bmkf.tv.data.model.CatalogTab
import com.bmkf.tv.data.model.EpisodeItem
import com.bmkf.tv.data.model.GenreItem
import com.bmkf.tv.data.model.HomeSection
import com.bmkf.tv.data.model.MovieDetail
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.PagedMovies
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.model.StreamSource
import com.bmkf.tv.data.source.CoBePhimProvider
import com.bmkf.tv.data.source.K20StremioProvider
import com.bmkf.tv.data.source.KhoPhimProvider
import com.bmkf.tv.data.source.KkPhimProvider
import com.bmkf.tv.data.source.MovieSourceProvider
import com.bmkf.tv.data.source.ShowBoxProvider
import com.bmkf.tv.data.source.SportTvProvider
import com.bmkf.tv.data.source.VsMovProvider
import com.bmkf.tv.data.storage.LocalStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class MovieRepository(
    val storage: LocalStorage
) {
    private data class CacheEntry<T>(val value: T, val timestampMs: Long)

    private val k20Provider = K20StremioProvider(
        manifestUrlProvider = { storage.settings.value.k20ManifestUrl }
    )

    private val kkPhimProvider = KkPhimProvider(
        baseUrlProvider = { storage.settings.value.kkPhimBaseUrl },
        k20BaseUrlProvider = { k20Provider.baseUrl() }
    )

    private val vsMovProvider = VsMovProvider(
        baseUrlProvider = { storage.settings.value.vsMovBaseUrl },
        k20BaseUrlProvider = { k20Provider.baseUrl() }
    )

    private val coBePhimProvider = CoBePhimProvider(
        apiBaseUrlProvider = { storage.settings.value.coBeApiBaseUrl },
        webBaseUrlProvider = { storage.settings.value.coBeWebBaseUrl },
        k20BaseUrlProvider = { k20Provider.baseUrl() }
    )

    private val showBoxProvider = ShowBoxProvider(
        manifestUrlProvider = { storage.settings.value.showBoxManifestUrl },
        k20BaseUrlProvider = { k20Provider.baseUrl() }
    )

    private val khoPhimProvider = KhoPhimProvider(
        apiBaseUrlProvider = { storage.settings.value.khoPhimApiBaseUrl },
        apiKeyProvider = { storage.settings.value.khoPhimApiKey },
        webBaseUrlProvider = { storage.settings.value.khoPhimWebBaseUrl }
    )

    private val sportTvProvider = SportTvProvider(
        configUrlProvider = { storage.settings.value.sportTvUrl },
        highFlyUrlProvider = { storage.settings.value.highFlySportUrl },
        xoiLacUrlProvider = { storage.settings.value.xoiLacUrl },
        gaVangUrlProvider = { storage.settings.value.gaVangUrl },
        xoiCheUrlProvider = { storage.settings.value.xoiCheUrl },
        colaTvUrlProvider = { storage.settings.value.colaTvUrl }
    )

    private val providers: Map<SourceId, MovieSourceProvider> = mapOf(
        SourceId.KKPHIM to kkPhimProvider,
        SourceId.VSMOV to vsMovProvider,
        SourceId.COBEPHIM to coBePhimProvider,
        SourceId.K20 to k20Provider,
        SourceId.SHOWBOX to showBoxProvider,
        SourceId.KHOPHIM to khoPhimProvider,
        SourceId.SPORT_TV to sportTvProvider
    )

    // Bounded LRU TTL caches to make tab navigation instant while keeping RAM tiny
    private var cachedHomeKey: String? = null
    private var cachedHome: CacheEntry<List<HomeSection>>? = null
    private val tabsCache = LinkedHashMap<SourceId, CacheEntry<List<CatalogTab>>>(8, 0.75f, true)
    private val catalogCache = LinkedHashMap<String, CacheEntry<PagedMovies>>(24, 0.75f, true)
    private val detailCache = LinkedHashMap<String, CacheEntry<MovieDetail>>(24, 0.75f, true)

    fun provider(sourceId: SourceId): MovieSourceProvider = providers.getValue(sourceId)

    fun trimCaches() {
        synchronized(this) {
            catalogCache.clear()
            detailCache.clear()
        }
    }

    fun getCachedHomeIfValid(): List<HomeSection>? {
        val key = homeCacheKey()
        val entry = cachedHome ?: return null
        return if (cachedHomeKey == key && !isExpired(entry.timestampMs)) entry.value else null
    }

    /**
     * Progressively streams Home sections as each source finishes, avoiding a long blank spinner
     * AND avoiding CPU spikes from waiting on 17 simultaneous responses.
     * Each row is capped to 14 posters to minimize memory & layout work on TV.
     */
    fun streamUnifiedHome(forceRefresh: Boolean = false): Flow<List<HomeSection>> = channelFlow {
        val key = homeCacheKey()
        if (!forceRefresh) {
            getCachedHomeIfValid()?.let {
                send(it)
                return@channelFlow
            }
        }

        val enabled = storage.settings.value.enabledSources
        val ordered = listOf(
            SourceId.COBEPHIM,
            SourceId.KKPHIM,
            SourceId.KHOPHIM,
            SourceId.VSMOV,
            SourceId.SHOWBOX,
            SourceId.SPORT_TV,
            SourceId.K20
        ).filter { it in enabled }

        val collectedBySource = LinkedHashMap<SourceId, List<HomeSection>>()
        val lock = Any()

        coroutineScope {
            ordered.forEach { source ->
                launch {
                    val fetched = runCatching {
                        provider(source).getHomeSections()
                            .take(MAX_SECTIONS_PER_SOURCE_ON_HOME)
                            .map { sec -> sec.copy(movies = sec.movies.take(MAX_MOVIES_PER_HOME_ROW)) }
                    }.getOrDefault(emptyList())

                    if (fetched.isNotEmpty()) {
                        val snapshot = synchronized(lock) {
                            collectedBySource[source] = fetched
                            interleave(ordered, collectedBySource)
                        }
                        send(snapshot)
                    }
                }
            }
        }

        val finalList = synchronized(lock) { interleave(ordered, collectedBySource) }
        if (finalList.isNotEmpty()) {
            cachedHomeKey = key
            cachedHome = CacheEntry(finalList, System.currentTimeMillis())
        }
        send(finalList)
    }

    suspend fun getCatalogTabs(sourceId: SourceId): List<CatalogTab> {
        synchronized(this) {
            val cached = tabsCache[sourceId]
            if (cached != null && !isExpired(cached.timestampMs)) return cached.value
        }
        val tabs = provider(sourceId).getCatalogTabs()
        synchronized(this) {
            tabsCache[sourceId] = CacheEntry(tabs, System.currentTimeMillis())
        }
        return tabs
    }

    suspend fun getCatalog(
        sourceId: SourceId,
        catalogId: String,
        page: Int = 1,
        genre: String? = null,
        bypassCache: Boolean = false
    ): PagedMovies {
        val key = "${sourceId.name}|$catalogId|$page|${genre.orEmpty()}"
        val ttl = if (sourceId == SourceId.SPORT_TV) SPORT_CACHE_TTL_MS else CACHE_TTL_MS
        if (!bypassCache) {
            synchronized(this) {
                val cached = catalogCache[key]
                if (cached != null && !isExpired(cached.timestampMs, ttl)) return cached.value
            }
        }
        val paged = provider(sourceId).getCatalog(catalogId, page, genre)
        synchronized(this) {
            if (catalogCache.size >= MAX_CATALOG_CACHE) {
                catalogCache.entries.firstOrNull()?.let { catalogCache.remove(it.key) }
            }
            catalogCache[key] = CacheEntry(paged, System.currentTimeMillis())
        }
        return paged
    }

    suspend fun getGenres(sourceId: SourceId): List<GenreItem> {
        return provider(sourceId).getGenres()
    }

    suspend fun searchAll(
        query: String,
        filterSource: SourceId? = null
    ): Map<SourceId, List<MovieItem>> = coroutineScope {
        val targets = if (filterSource != null) {
            listOf(filterSource)
        } else {
            listOf(
                SourceId.KKPHIM,
                SourceId.KHOPHIM,
                SourceId.VSMOV,
                SourceId.COBEPHIM,
                SourceId.K20,
                SourceId.SHOWBOX,
                SourceId.SPORT_TV
            ).filter { it in storage.settings.value.enabledSources }
        }

        targets.map { source ->
            async {
                source to runCatching {
                    provider(source).search(query, 1).take(MAX_SEARCH_PER_SOURCE)
                }.getOrDefault(emptyList())
            }
        }.awaitAll().toMap()
    }

    suspend fun getMovieDetail(movie: MovieItem): MovieDetail {
        val key = movie.uniqueKey
        synchronized(this) {
            val cached = detailCache[key]
            if (cached != null && !isExpired(cached.timestampMs)) return cached.value
        }
        val rawDetail = provider(movie.source).getMovieDetail(movie.id, movie.mediaType)
        // Attach explicit ShowBox 4K & ShowBox 1080P servers on KKPhim/VSMOV/CoBePhim movies with IMDb ID
        val detail = if (movie.source != SourceId.SHOWBOX &&
            movie.source != SourceId.SPORT_TV &&
            SourceId.SHOWBOX in storage.settings.value.enabledSources &&
            rawDetail.imdbId.startsWith("tt")
        ) {
            val showBoxServers = showBoxProvider.buildCrossSourceServers(rawDetail)
            rawDetail.copy(servers = rawDetail.servers + showBoxServers)
        } else {
            rawDetail
        }

        synchronized(this) {
            if (detailCache.size >= MAX_DETAIL_CACHE) {
                detailCache.entries.firstOrNull()?.let { detailCache.remove(it.key) }
            }
            detailCache[key] = CacheEntry(detail, System.currentTimeMillis())
        }
        return detail
    }

    suspend fun resolveStreams(episode: EpisodeItem, detail: MovieDetail): List<StreamSource> {
        // Direct routing when user picks ShowBox 4K or ShowBox 1080P server on ANY source
        if (episode.slug == ShowBoxProvider.TIER_4K || episode.slug == ShowBoxProvider.TIER_1080P) {
            val stremioType = if (episode.k20VideoId.contains(":")) "series" else "movie"
            val videoId = episode.k20VideoId.ifBlank { detail.imdbId.ifBlank { detail.item.id } }
            return showBoxProvider.resolveForTier(
                stremioType = stremioType,
                videoId = videoId,
                preferredTier = episode.slug,
                allowK20Fallback = detail.item.source == SourceId.SHOWBOX
            )
        }
        return provider(detail.item.source).resolveStream(episode, detail)
    }

    private fun interleave(
        ordered: List<SourceId>,
        bySource: Map<SourceId, List<HomeSection>>
    ): List<HomeSection> {
        val lists = ordered.mapNotNull { bySource[it] }
        val result = mutableListOf<HomeSection>()
        val maxRows = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxRows) {
            for (list in lists) {
                if (i < list.size) result += list[i]
            }
        }
        return result
    }

    private fun homeCacheKey(): String {
        val s = storage.settings.value
        return "${s.enabledSources.joinToString(",")}|${s.k20ManifestUrl}|${s.showBoxManifestUrl}|${s.khoPhimApiBaseUrl}|${s.khoPhimApiKey}|${s.khoPhimWebBaseUrl}|${s.sportTvUrl}|${s.highFlySportUrl}|${s.xoiLacUrl}|${s.gaVangUrl}|${s.xoiCheUrl}|${s.colaTvUrl}|${s.kkPhimBaseUrl}|${s.vsMovBaseUrl}|${s.coBeApiBaseUrl}"
    }

    private fun isExpired(timestampMs: Long, ttlMs: Long = CACHE_TTL_MS): Boolean {
        return System.currentTimeMillis() - timestampMs > ttlMs
    }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val SPORT_CACHE_TTL_MS = 30 * 1000L
        private const val MAX_SECTIONS_PER_SOURCE_ON_HOME = 2
        private const val MAX_MOVIES_PER_HOME_ROW = 14
        private const val MAX_SEARCH_PER_SOURCE = 18
        private const val MAX_CATALOG_CACHE = 20
        private const val MAX_DETAIL_CACHE = 20
    }
}
