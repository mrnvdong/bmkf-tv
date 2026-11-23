package com.bmkf.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.bmkf.tv.data.model.CatalogTab
import com.bmkf.tv.data.model.EpisodeItem
import com.bmkf.tv.data.model.EpisodeServer
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.PlayerLaunchPayload
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.repository.MovieRepository
import com.bmkf.tv.ui.components.EmptyState
import com.bmkf.tv.ui.components.ErrorRetryState
import com.bmkf.tv.ui.components.LoadingState
import com.bmkf.tv.ui.components.TvActionButton
import com.bmkf.tv.ui.components.TvFilterChip
import com.bmkf.tv.ui.components.rememberThumbRequest
import com.bmkf.tv.ui.theme.CinemaAccent
import com.bmkf.tv.ui.theme.CinemaBackground
import com.bmkf.tv.ui.theme.CinemaGold
import com.bmkf.tv.ui.theme.CinemaSurface
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary
import kotlinx.coroutines.launch

private val SportCardShape = RoundedCornerShape(12.dp)
private val SportBadgeShape = RoundedCornerShape(6.dp)
private val SportCardBorderUnfocused = Color.White.copy(alpha = 0.1f)
private val SportAccent = Color(SourceId.SPORT_TV.accentHex)
private val SportCardScrim = Brush.verticalGradient(
    listOf(Color.Transparent, CinemaBackground.copy(alpha = 0.90f))
)

@Composable
fun SportTvScreen(
    repository: MovieRepository,
    onPlayLive: (PlayerLaunchPayload) -> Unit
) {
    val scope = rememberCoroutineScope()

    var tabs by remember { mutableStateOf<List<CatalogTab>>(emptyList()) }
    var selectedTabId by remember { mutableStateOf<String?>(null) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }

    var items by remember { mutableStateOf<List<MovieItem>>(emptyList()) }
    var currentPage by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        if (tabs.isEmpty() || refreshTick > 0) {
            val loadedTabs = runCatching {
                repository.getCatalogTabs(SourceId.SPORT_TV)
            }.getOrDefault(emptyList())
            tabs = loadedTabs
            if (selectedTabId == null || loadedTabs.none { it.id == selectedTabId }) {
                selectedTabId = loadedTabs.firstOrNull()?.id
            }
        }
    }

    LaunchedEffect(selectedTabId, selectedGenre, refreshTick) {
        val tabId = selectedTabId ?: return@LaunchedEffect
        isLoading = true
        errorMessage = null
        currentPage = 1
        val result = runCatching {
            repository.getCatalog(
                sourceId = SourceId.SPORT_TV,
                catalogId = tabId,
                page = 1,
                genre = selectedGenre,
                bypassCache = refreshTick > 0
            )
        }
        result.onSuccess { paged ->
            items = paged.items
            hasMore = paged.hasMore
            isLoading = false
        }.onFailure { err ->
            items = emptyList()
            hasMore = false
            isLoading = false
            errorMessage = "Không tải được danh sách Sport TV: ${err.message.orEmpty()}"
        }
    }

    fun loadMore() {
        val tabId = selectedTabId ?: return
        if (isLoadingMore || !hasMore) return
        scope.launch {
            isLoadingMore = true
            val nextPage = currentPage + 1
            runCatching {
                repository.getCatalog(SourceId.SPORT_TV, tabId, page = nextPage, genre = selectedGenre)
            }.onSuccess { paged ->
                items = (items + paged.items).distinctBy { it.uniqueKey }
                currentPage = nextPage
                hasMore = paged.hasMore && paged.items.isNotEmpty()
            }
            isLoadingMore = false
        }
    }

    fun launchMatchDirectly(clickedIndex: Int) {
        val clicked = items.getOrNull(clickedIndex) ?: return
        // Build channel playlist from visible matches/channels so CH+/CH- zaps seamlessly on TV remote!
        val playlistWindow = items.take(60)
        val safeIdx = clickedIndex.coerceAtMost(playlistWindow.lastIndex).coerceAtLeast(0)
        val serverTitle = tabs.firstOrNull { it.id == selectedTabId }?.title ?: "Sport TV"
        val episodes = playlistWindow.mapIndexed { idx, match ->
            EpisodeItem(
                id = match.id,
                name = match.title,
                season = 1,
                episodeNumber = idx + 1,
                serverName = serverTitle,
                k20VideoId = match.id
            )
        }
        onPlayLive(
            PlayerLaunchPayload(
                movie = clicked,
                servers = listOf(EpisodeServer(serverName = serverTitle, season = 1, episodes = episodes)),
                initialServerIndex = 0,
                initialEpisodeIndex = safeIdx,
                resumePositionMs = 0L
            )
        )
    }

    val activeTab = tabs.firstOrNull { it.id == selectedTabId }
    val genres = activeTab?.genres.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Sport TV • Thể thao Trực tiếp",
                    color = CinemaTextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Bóng đá, MMA, Kênh thể thao 24/7 & Xem lại • Nhấn OK xem ngay, CH+/CH- chuyển kênh",
                    color = CinemaTextMuted,
                    fontSize = 12.sp
                )
            }
            TvActionButton(
                label = "Cập nhật",
                icon = Icons.Default.Refresh,
                primary = false,
                onClick = { refreshTick++ }
            )
        }

        Spacer(Modifier.height(12.dp))

        if (tabs.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    TvFilterChip(
                        label = tab.title,
                        selected = tab.id == selectedTabId,
                        accentColor = SportAccent,
                        onClick = {
                            if (selectedTabId != tab.id) {
                                selectedGenre = null
                                selectedTabId = tab.id
                            }
                        }
                    )
                }
            }
        }

        if (genres.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "sport_genre_all") {
                    TvFilterChip(
                        label = "Tất cả môn",
                        selected = selectedGenre == null,
                        accentColor = CinemaGold,
                        onClick = { selectedGenre = null }
                    )
                }
                items(genres, key = { "sg_${it.slug}" }) { g ->
                    TvFilterChip(
                        label = g.name,
                        selected = selectedGenre == g.slug,
                        accentColor = CinemaGold,
                        onClick = { selectedGenre = g.slug }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        when {
            isLoading -> LoadingState("Đang tải lịch thi đấu & kênh thể thao trực tiếp...")
            errorMessage != null -> ErrorRetryState(
                message = errorMessage!!,
                onRetry = { refreshTick++ }
            )
            items.isEmpty() -> EmptyState(
                title = "Hiện chưa có trận đấu trong mục này",
                subtitle = "Hãy thử chuyển sang Kênh TV 24/7 hoặc Xem lại (Replays)."
            )
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 36.dp, start = 4.dp, end = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.uniqueKey },
                        contentType = { _, _ -> "sport_card" }
                    ) { index, match ->
                        SportMatchCard(
                            match = match,
                            onClick = { launchMatchDirectly(index) }
                        )
                    }

                    if (hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "sport_load_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(color = SportAccent)
                                } else {
                                    TvActionButton(
                                        label = "Tải thêm (${items.size}+)",
                                        primary = false,
                                        onClick = { loadMore() }
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

@Composable
private fun SportMatchCard(
    match: MovieItem,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val imageUrl = match.backdropUrl.ifBlank { match.posterUrl }
    val request = rememberThumbRequest(imageUrl)

    val isLive = match.quality.contains("LIVE", ignoreCase = true) || match.title.contains("LIVE", ignoreCase = true)
    val badgeBg = when {
        isLive -> CinemaAccent
        match.quality == "24/7 TV" -> SportAccent
        else -> CinemaBackground.copy(alpha = 0.85f)
    }
    val isCrestLogo = imageUrl.contains("imgts.", ignoreCase = true) ||
        imageUrl.contains("static.sportsfeed.pro", ignoreCase = true) ||
        imageUrl.contains("img.thesports.com", ignoreCase = true) ||
        imageUrl.contains("img.cltvap.cc", ignoreCase = true) ||
        imageUrl.contains("apiv3.apifootball.com/badges", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                val s = if (focused) 1.05f else 1f
                scaleX = s
                scaleY = s
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(SportCardShape)
                .background(CinemaSurface)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) Color.White else SportCardBorderUnfocused,
                    shape = SportCardShape
                )
        ) {
            AsyncImage(
                model = request,
                contentDescription = match.title,
                contentScale = if (isCrestLogo) ContentScale.Fit else ContentScale.Crop,
                modifier = if (isCrestLogo) {
                    Modifier.fillMaxSize().padding(top = 12.dp, bottom = 48.dp, start = 44.dp, end = 44.dp)
                } else {
                    Modifier.fillMaxSize()
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SportCardScrim)
            )

            if (match.quality.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(SportBadgeShape)
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isLive && match.quality.equals("LIVE", ignoreCase = true)) "● LIVE" else match.quality,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                Text(
                    text = match.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                if (match.originTitle.isNotBlank()) {
                    Text(
                        text = match.originTitle,
                        color = CinemaGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
