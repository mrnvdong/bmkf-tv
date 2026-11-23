package com.bmkf.tv.data.source

import com.bmkf.tv.data.model.CatalogTab
import com.bmkf.tv.data.model.EpisodeItem
import com.bmkf.tv.data.model.GenreItem
import com.bmkf.tv.data.model.HomeSection
import com.bmkf.tv.data.model.MediaType
import com.bmkf.tv.data.model.MovieDetail
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.PagedMovies
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.model.StreamSource

interface MovieSourceProvider {
    val sourceId: SourceId

    suspend fun getCatalogTabs(): List<CatalogTab>

    suspend fun getHomeSections(): List<HomeSection>

    suspend fun getCatalog(
        catalogId: String,
        page: Int = 1,
        genre: String? = null
    ): PagedMovies

    suspend fun getGenres(): List<GenreItem>

    suspend fun search(query: String, page: Int = 1): List<MovieItem>

    suspend fun getMovieDetail(id: String, mediaType: MediaType): MovieDetail

    suspend fun resolveStream(episode: EpisodeItem, detail: MovieDetail): List<StreamSource>
}

internal fun extractEpisodeNumber(name: String, fallbackIndex: Int): Int {
    val match = Regex("""\d+""").find(name)
    return match?.value?.toIntOrNull() ?: fallbackIndex
}
