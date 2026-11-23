package com.bmkf.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bmkf.tv.data.model.MovieDetail
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.PlayerLaunchPayload
import com.bmkf.tv.data.repository.MovieRepository
import com.bmkf.tv.ui.components.ErrorRetryState
import com.bmkf.tv.ui.components.LoadingState
import com.bmkf.tv.ui.components.SourceBadge
import com.bmkf.tv.ui.components.TvActionButton
import com.bmkf.tv.ui.components.TvFilterChip
import com.bmkf.tv.ui.components.rememberBackdropRequest
import com.bmkf.tv.ui.components.rememberPosterRequest
import com.bmkf.tv.ui.theme.CinemaAccent
import com.bmkf.tv.ui.theme.CinemaBackground
import com.bmkf.tv.ui.theme.CinemaBorderSubtle
import com.bmkf.tv.ui.theme.CinemaGold
import com.bmkf.tv.ui.theme.CinemaSurface
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary
import com.bmkf.tv.ui.theme.CinemaTextSecondary

private const val EPISODE_CHUNK_SIZE = 40
private val PosterShape = RoundedCornerShape(14.dp)
private val MetaPillShape = RoundedCornerShape(6.dp)
private val EpisodeTileShape = RoundedCornerShape(10.dp)
private val DetailTopBackdropScrim = Brush.verticalGradient(
    0.0f to CinemaBackground.copy(alpha = 0.55f),
    0.65f to CinemaBackground.copy(alpha = 0.88f),
    1.0f to CinemaBackground
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    movie: MovieItem,
    repository: MovieRepository,
    onBack: () -> Unit,
    onPlay: (PlayerLaunchPayload) -> Unit,
    onSearchOtherSources: (String) -> Unit
) {
    val favorites by repository.storage.favorites.collectAsState()
    val history by repository.storage.history.collectAsState()

    val isFav = remember(favorites, movie) { favorites.any { it.uniqueKey == movie.uniqueKey } }
    val savedProgress = remember(history, movie) { history.firstOrNull { it.movie.uniqueKey == movie.uniqueKey } }

    var detail by remember(movie.uniqueKey) { mutableStateOf<MovieDetail?>(null) }
    var isLoading by remember(movie.uniqueKey) { mutableStateOf(true) }
    var errorMessage by remember(movie.uniqueKey) { mutableStateOf<String?>(null) }
    var selectedServerIdx by remember(movie.uniqueKey) { mutableIntStateOf(0) }
    var selectedChunkIdx by remember(movie.uniqueKey) { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }

    val watchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(movie.uniqueKey, reloadKey) {
        isLoading = true
        errorMessage = null
        runCatching { repository.getMovieDetail(movie) }
            .onSuccess { d ->
                detail = d
                val savedServerIdx = savedProgress?.let { prog ->
                    d.servers.indexOfFirst { s -> s.episodes.any { it.id == prog.episodeId } }
                }?.takeIf { it >= 0 } ?: 0
                selectedServerIdx = savedServerIdx
                val savedEpIdx = savedProgress?.let { prog ->
                    d.servers.getOrNull(savedServerIdx)?.episodes?.indexOfFirst { it.id == prog.episodeId }
                }?.takeIf { it >= 0 } ?: 0
                selectedChunkIdx = savedEpIdx / EPISODE_CHUNK_SIZE
                isLoading = false
            }
            .onFailure { err ->
                errorMessage = "Không tải được chi tiết phim: ${err.message.orEmpty()}"
                isLoading = false
            }
    }

    LaunchedEffect(detail) {
        if (detail != null) {
            runCatching { watchFocusRequester.requestFocus() }
        }
    }

    val backdropUrl = (detail?.item?.backdropUrl ?: movie.backdropUrl).ifBlank { movie.posterUrl }
    val backdropRequest = rememberBackdropRequest(backdropUrl)

    Box(modifier = Modifier.fillMaxSize().background(CinemaBackground)) {
        // Bounded top backdrop band (replaces 3x full-screen overdraw — huge GPU win on TV!)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
        ) {
            AsyncImage(
                model = backdropRequest,
                contentDescription = movie.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DetailTopBackdropScrim)
            )
        }

        when {
            isLoading -> LoadingState("Đang tải thông tin phim từ ${movie.source.displayName}...")
            errorMessage != null -> ErrorRetryState(
                message = errorMessage!!,
                onRetry = { reloadKey++ }
            )
            detail != null -> {
                val d = detail!!
                val servers = d.servers
                val activeServer = servers.getOrNull(selectedServerIdx)
                val episodes = activeServer?.episodes.orEmpty()
                val posterRequest = rememberPosterRequest(d.item.posterUrl.ifBlank { d.item.backdropUrl })

                val resumeEpIndex = remember(savedProgress, episodes) {
                    savedProgress?.let { prog ->
                        episodes.indexOfFirst { it.id == prog.episodeId }
                    }?.takeIf { it >= 0 } ?: 0
                }

                // Chunk episodes in slices of 40 so 1000-episode series never lag Compose layout!
                val totalChunks = ((episodes.size + EPISODE_CHUNK_SIZE - 1) / EPISODE_CHUNK_SIZE).coerceAtLeast(1)
                val safeChunkIdx = selectedChunkIdx.coerceIn(0, totalChunks - 1)
                val chunkStart = safeChunkIdx * EPISODE_CHUNK_SIZE
                val chunkEnd = (chunkStart + EPISODE_CHUNK_SIZE).coerceAtMost(episodes.size)
                val visibleEpisodes = remember(episodes, chunkStart, chunkEnd) {
                    if (episodes.isEmpty()) emptyList() else episodes.subList(chunkStart, chunkEnd)
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    item(key = "detail_back") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TvActionButton(
                                label = "Quay lại",
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                primary = false,
                                onClick = onBack
                            )
                            Spacer(Modifier.width(14.dp))
                            SourceBadge(source = d.item.source, compact = false)
                        }
                    }

                    item(key = "detail_hero") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            AsyncImage(
                                model = posterRequest,
                                contentDescription = d.item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(184.dp)
                                    .aspectRatio(2f / 3f)
                                    .clip(PosterShape)
                                    .background(CinemaSurface)
                                    .border(1.dp, Color.White.copy(alpha = 0.16f), PosterShape)
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = d.item.title,
                                    color = CinemaTextPrimary,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    lineHeight = 34.sp
                                )

                                if (d.item.originTitle.isNotBlank() && d.item.originTitle != d.item.title) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = d.item.originTitle,
                                        color = CinemaTextMuted,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(Modifier.height(10.dp))

                                val metaPieces = remember(d) {
                                    listOfNotNull(
                                        d.item.year.takeIf { it.isNotBlank() },
                                        d.runtime.takeIf { it.isNotBlank() },
                                        d.status.takeIf { it.isNotBlank() },
                                        d.item.quality.takeIf { it.isNotBlank() },
                                        d.item.lang.takeIf { it.isNotBlank() },
                                        d.item.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }
                                    )
                                }
                                if (metaPieces.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        metaPieces.forEach { piece ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(MetaPillShape)
                                                    .background(Color.White.copy(alpha = 0.12f))
                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = piece,
                                                    color = if (piece.startsWith("★")) CinemaGold else CinemaTextPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                if (d.genres.isNotEmpty() || d.countries.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    val line = listOf(
                                        d.genres.joinToString(", "),
                                        d.countries.joinToString(", ")
                                    ).filter { it.isNotBlank() }.joinToString("  •  ")
                                    Text(text = line, color = CinemaGold, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }

                                if (d.description.isNotBlank()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = d.description,
                                        color = CinemaTextSecondary,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (d.directors.isNotEmpty() || d.cast.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    if (d.directors.isNotEmpty()) {
                                        Text(
                                            text = "Đạo diễn: ${d.directors.take(3).joinToString(", ")}",
                                            color = CinemaTextMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                    if (d.cast.isNotEmpty()) {
                                        Text(
                                            text = "Diễn viên: ${d.cast.take(8).joinToString(", ")}",
                                            color = CinemaTextMuted,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(Modifier.height(16.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val watchLabel = if (savedProgress != null) {
                                        "Tiếp tục • ${savedProgress.episodeName}"
                                    } else if (episodes.isNotEmpty()) {
                                        "Xem ngay • ${episodes.first().name}"
                                    } else {
                                        "Chưa có tập"
                                    }

                                    TvActionButton(
                                        label = watchLabel,
                                        icon = Icons.Default.PlayArrow,
                                        primary = true,
                                        modifier = Modifier.focusRequester(watchFocusRequester),
                                        onClick = {
                                            if (servers.isNotEmpty()) {
                                                val resumeMs = if (savedProgress != null &&
                                                    episodes.getOrNull(resumeEpIndex)?.id == savedProgress.episodeId
                                                ) {
                                                    savedProgress.positionMs
                                                } else {
                                                    0L
                                                }
                                                onPlay(
                                                    PlayerLaunchPayload(
                                                        movie = d.item,
                                                        servers = servers,
                                                        initialServerIndex = selectedServerIdx,
                                                        initialEpisodeIndex = resumeEpIndex,
                                                        resumePositionMs = resumeMs,
                                                        imdbId = d.imdbId
                                                    )
                                                )
                                            }
                                        }
                                    )

                                    TvActionButton(
                                        label = if (isFav) "Đã yêu thích" else "Yêu thích",
                                        icon = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        primary = false,
                                        onClick = { repository.storage.toggleFavorite(d.item) }
                                    )

                                    TvActionButton(
                                        label = "Tìm nguồn khác",
                                        icon = Icons.Default.Search,
                                        primary = false,
                                        onClick = { onSearchOtherSources(d.item.title) }
                                    )
                                }
                            }
                        }
                    }

                    if (servers.isNotEmpty()) {
                        item(key = "detail_servers") {
                            Column {
                                Text(
                                    text = "Chọn Server / Bản dịch (${servers.size})",
                                    color = CinemaTextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(10.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    itemsIndexed(servers) { idx, srv ->
                                        TvFilterChip(
                                            label = "${srv.serverName} (${srv.episodes.size})",
                                            selected = idx == selectedServerIdx,
                                            accentColor = Color(d.item.source.accentHex),
                                            onClick = {
                                                selectedServerIdx = idx
                                                selectedChunkIdx = 0
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Episode range selector for long-running series (>40 episodes)
                        if (totalChunks > 1) {
                            item(key = "detail_ep_chunks") {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(totalChunks) { chunkIdx ->
                                        val startNum = chunkIdx * EPISODE_CHUNK_SIZE + 1
                                        val endNum = ((chunkIdx + 1) * EPISODE_CHUNK_SIZE).coerceAtMost(episodes.size)
                                        TvFilterChip(
                                            label = "Tập $startNum – $endNum",
                                            selected = chunkIdx == safeChunkIdx,
                                            accentColor = CinemaGold,
                                            onClick = { selectedChunkIdx = chunkIdx }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "detail_episodes") {
                            Column {
                                Text(
                                    text = "Danh sách tập (${episodes.size})",
                                    color = CinemaTextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(10.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    visibleEpisodes.forEachIndexed { localIdx, ep ->
                                        val globalIdx = chunkStart + localIdx
                                        val isLastWatched = savedProgress?.episodeId == ep.id
                                        EpisodeTile(
                                            label = ep.name,
                                            watched = isLastWatched,
                                            onClick = {
                                                onPlay(
                                                    PlayerLaunchPayload(
                                                        movie = d.item,
                                                        servers = servers,
                                                        initialServerIndex = selectedServerIdx,
                                                        initialEpisodeIndex = globalIdx,
                                                        resumePositionMs = if (isLastWatched) savedProgress?.positionMs ?: 0L else 0L,
                                                        imdbId = d.imdbId
                                                    )
                                                )
                                            }
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
}

@Composable
private fun EpisodeTile(
    label: String,
    watched: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        watched -> CinemaAccent.copy(alpha = 0.25f)
        else -> CinemaSurface
    }
    val textColor = when {
        focused -> CinemaBackground
        watched -> CinemaGold
        else -> CinemaTextPrimary
    }
    val border = when {
        focused -> CinemaGold
        watched -> CinemaAccent
        else -> CinemaBorderSubtle
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .graphicsLayer {
                val s = if (focused) 1.05f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(EpisodeTileShape)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, border, EpisodeTileShape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (focused || watched) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
