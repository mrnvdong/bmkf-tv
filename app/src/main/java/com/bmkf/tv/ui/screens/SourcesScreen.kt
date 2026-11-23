package com.bmkf.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmkf.tv.data.model.CatalogTab
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.repository.MovieRepository
import com.bmkf.tv.ui.components.EmptyState
import com.bmkf.tv.ui.components.ErrorRetryState
import com.bmkf.tv.ui.components.LoadingState
import com.bmkf.tv.ui.components.PosterMovieCard
import com.bmkf.tv.ui.components.TvActionButton
import com.bmkf.tv.ui.components.TvFilterChip
import com.bmkf.tv.ui.theme.CinemaAccent
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary
import kotlinx.coroutines.launch

@Composable
fun SourcesScreen(
    repository: MovieRepository,
    initialSource: SourceId,
    initialCatalogId: String?,
    onOpenDetail: (MovieItem) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedSource by remember(initialSource) { mutableStateOf(initialSource) }
    var catalogTabs by remember { mutableStateOf<List<CatalogTab>>(emptyList()) }
    var selectedCatalogId by remember(initialSource, initialCatalogId) { mutableStateOf(initialCatalogId) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }

    var movies by remember { mutableStateOf<List<MovieItem>>(emptyList()) }
    var currentPage by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Load catalog tabs when source changes
    LaunchedEffect(selectedSource) {
        isLoading = true
        errorMessage = null
        selectedGenre = null
        val tabs = runCatching { repository.getCatalogTabs(selectedSource) }.getOrDefault(emptyList())
        catalogTabs = tabs
        val validInitial = tabs.firstOrNull { it.id == selectedCatalogId }?.id
        selectedCatalogId = validInitial ?: tabs.firstOrNull()?.id
    }

    // Load movies when source, catalog, or genre changes
    LaunchedEffect(selectedSource, selectedCatalogId, selectedGenre) {
        val catId = selectedCatalogId ?: return@LaunchedEffect
        isLoading = true
        errorMessage = null
        currentPage = 1
        val result = runCatching {
            repository.getCatalog(selectedSource, catId, page = 1, genre = selectedGenre)
        }
        result.onSuccess { paged ->
            movies = paged.items
            hasMore = paged.hasMore
            isLoading = false
        }.onFailure { err ->
            movies = emptyList()
            hasMore = false
            isLoading = false
            errorMessage = "Lỗi tải dữ liệu từ ${selectedSource.displayName}: ${err.message.orEmpty()}"
        }
    }

    fun loadNextPage() {
        val catId = selectedCatalogId ?: return
        if (isLoadingMore || !hasMore) return
        scope.launch {
            isLoadingMore = true
            val nextPage = currentPage + 1
            runCatching {
                repository.getCatalog(selectedSource, catId, page = nextPage, genre = selectedGenre)
            }.onSuccess { paged ->
                val merged = (movies + paged.items).distinctBy { it.uniqueKey }
                movies = merged
                currentPage = nextPage
                hasMore = paged.hasMore && paged.items.isNotEmpty()
            }
            isLoadingMore = false
        }
    }

    val activeTab = catalogTabs.firstOrNull { it.id == selectedCatalogId }
    val genres = activeTab?.genres.orEmpty()
    val sourceAccent = Color(selectedSource.accentHex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp)
    ) {
        // Title & Source Selector Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Kho phim theo nguồn",
                    color = CinemaTextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = selectedSource.description,
                    color = CinemaTextMuted,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 4 Source Chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(SourceId.entries) { src ->
                TvFilterChip(
                    label = src.displayName,
                    selected = src == selectedSource,
                    accentColor = Color(src.accentHex),
                    onClick = {
                        if (selectedSource != src) {
                            selectedCatalogId = null
                            selectedGenre = null
                            selectedSource = src
                        }
                    }
                )
            }
        }

        // Catalog Tabs for selected source
        if (catalogTabs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(catalogTabs, key = { "${selectedSource.name}_${it.id}" }) { tab ->
                    TvFilterChip(
                        label = tab.title,
                        selected = tab.id == selectedCatalogId && selectedGenre == null,
                        accentColor = sourceAccent,
                        onClick = {
                            selectedGenre = null
                            selectedCatalogId = tab.id
                        }
                    )
                }
            }
        }

        // Genre Filters
        if (genres.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "genre_all") {
                    TvFilterChip(
                        label = "Tất cả thể loại",
                        selected = selectedGenre == null,
                        accentColor = sourceAccent,
                        onClick = { selectedGenre = null }
                    )
                }
                items(genres.take(24), key = { "${selectedSource.name}_g_${it.slug}" }) { g ->
                    TvFilterChip(
                        label = g.name,
                        selected = selectedGenre == g.slug,
                        accentColor = sourceAccent,
                        onClick = { selectedGenre = g.slug }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Grid Content
        when {
            isLoading -> LoadingState("Đang tải ${selectedSource.displayName}...")
            errorMessage != null -> ErrorRetryState(
                message = errorMessage!!,
                onRetry = {
                    val c = selectedCatalogId
                    selectedCatalogId = null
                    selectedCatalogId = c
                }
            )
            movies.isEmpty() -> EmptyState(
                title = "Không có phim trong mục này",
                subtitle = "Hãy thử chọn danh mục hoặc thể loại khác."
            )
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 154.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp, start = 4.dp, end = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(
                        items = movies,
                        key = { it.uniqueKey },
                        contentType = { "poster_card" }
                    ) { movie ->
                        PosterMovieCard(
                            movie = movie,
                            onClick = { onOpenDetail(movie) }
                        )
                    }

                    if (hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "load_more_footer") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(color = CinemaAccent)
                                } else {
                                    TvActionButton(
                                        label = "Tải thêm trang ${currentPage + 1}",
                                        primary = false,
                                        onClick = { loadNextPage() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
