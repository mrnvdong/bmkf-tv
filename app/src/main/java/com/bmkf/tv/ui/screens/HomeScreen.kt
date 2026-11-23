package com.bmkf.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmkf.tv.data.model.HomeSection
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.repository.MovieRepository
import com.bmkf.tv.ui.components.ContinueWatchingCard
import com.bmkf.tv.ui.components.ErrorRetryState
import com.bmkf.tv.ui.components.HeroSpotlight
import com.bmkf.tv.ui.components.LoadingState
import com.bmkf.tv.ui.components.PosterMovieCard
import com.bmkf.tv.ui.components.SourceBadge
import com.bmkf.tv.ui.components.TvActionButton
import com.bmkf.tv.ui.components.TvFilterChip
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary

@Composable
fun HomeScreen(
    repository: MovieRepository,
    onOpenDetail: (MovieItem) -> Unit,
    onQuickPlay: (MovieItem) -> Unit,
    onBrowseSource: (SourceId, String?) -> Unit
) {
    val history by repository.storage.history.collectAsState()
    val settings by repository.storage.settings.collectAsState()

    val cachedInitial = remember { repository.getCachedHomeIfValid().orEmpty() }
    var sections by remember { mutableStateOf(cachedInitial) }
    var isLoading by remember { mutableStateOf(cachedInitial.isEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(
        reloadKey,
        settings.enabledSources,
        settings.k20ManifestUrl,
        settings.showBoxManifestUrl,
        settings.khoPhimApiBaseUrl,
        settings.khoPhimApiKey,
        settings.khoPhimWebBaseUrl,
        settings.sportTvUrl,
        settings.highFlySportUrl,
        settings.xoiLacUrl,
        settings.gaVangUrl,
        settings.xoiCheUrl,
        settings.colaTvUrl
    ) {
        val force = reloadKey > 0
        if (force || sections.isEmpty()) {
            isLoading = true
        }
        errorMessage = null

        var latest: List<HomeSection> = sections
        runCatching {
            repository.streamUnifiedHome(forceRefresh = force).collect { progressive ->
                latest = progressive
                if (progressive.isNotEmpty()) {
                    sections = progressive
                    isLoading = false
                }
            }
        }
        isLoading = false
        if (latest.isEmpty()) {
            errorMessage = "Không thể tải danh sách phim. Hãy kiểm tra kết nối mạng hoặc cài đặt nguồn."
        }
    }

    when {
        isLoading && sections.isEmpty() -> {
            LoadingState("Đang tải phim từ các nguồn...")
        }

        errorMessage != null && sections.isEmpty() -> {
            ErrorRetryState(
                message = errorMessage!!,
                onRetry = { reloadKey++ }
            )
        }

        else -> {
            val featured = remember(sections) {
                sections
                    .flatMap { it.movies.take(2) }
                    .filter { it.backdropUrl.isNotBlank() || it.posterUrl.isNotBlank() }
                    .distinctBy { it.uniqueKey }
                    .take(6)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 28.dp, top = 18.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                item(key = "home_header", contentType = "header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Khám phá hôm nay",
                                color = CinemaTextPrimary,
                                fontSize = 23.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Tổng hợp nhanh từ ${settings.enabledSources.size} nguồn • Vào Kho nguồn để xem đầy đủ",
                                color = CinemaTextMuted,
                                fontSize = 12.sp
                            )
                        }
                        TvActionButton(
                            label = "Làm mới",
                            icon = Icons.Default.Refresh,
                            primary = false,
                            onClick = { reloadKey++ }
                        )
                    }
                }

                if (featured.isNotEmpty()) {
                    item(key = "hero_spotlight", contentType = "hero") {
                        HeroSpotlight(
                            featuredMovies = featured,
                            onWatchNow = onQuickPlay,
                            onOpenDetail = onOpenDetail
                        )
                    }
                }

                if (history.isNotEmpty()) {
                    item(key = "continue_watching", contentType = "continue_row") {
                        Column {
                            Text(
                                text = "Tiếp tục xem",
                                color = CinemaTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                items(
                                    items = history.take(10),
                                    key = { "cw_${it.movie.uniqueKey}" },
                                    contentType = { "cw_card" }
                                ) { entry ->
                                    ContinueWatchingCard(
                                        entry = entry,
                                        onClick = { onOpenDetail(entry.movie) }
                                    )
                                }
                            }
                        }
                    }
                }

                items(
                    items = sections,
                    key = { it.id },
                    contentType = { "movie_section" }
                ) { section ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SourceBadge(source = section.source, compact = true)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = section.title,
                                    color = CinemaTextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            TvFilterChip(
                                label = "Xem thêm ›",
                                selected = false,
                                onClick = { onBrowseSource(section.source, section.catalogId) }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            items(
                                items = section.movies,
                                key = { "${section.id}_${it.uniqueKey}" },
                                contentType = { "poster_card" }
                            ) { movie ->
                                PosterMovieCard(
                                    movie = movie,
                                    onClick = { onOpenDetail(movie) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
