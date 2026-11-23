package com.bmkf.tv.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmkf.tv.BmkfTvApp
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.PlayerLaunchPayload
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.ui.screens.DetailScreen
import com.bmkf.tv.ui.screens.HomeScreen
import com.bmkf.tv.ui.screens.LibraryScreen
import com.bmkf.tv.ui.screens.SearchScreen
import com.bmkf.tv.ui.screens.SettingsScreen
import com.bmkf.tv.ui.screens.SourcesScreen
import com.bmkf.tv.ui.screens.SportTvScreen
import com.bmkf.tv.ui.theme.CinemaAccent
import com.bmkf.tv.ui.theme.CinemaBackground
import com.bmkf.tv.ui.theme.CinemaBorderSubtle
import com.bmkf.tv.ui.theme.CinemaGold
import com.bmkf.tv.ui.theme.CinemaSurface
import com.bmkf.tv.ui.theme.CinemaSurfaceElevated
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary
import kotlinx.coroutines.launch

enum class TvNavTab(
    val label: String,
    val icon: ImageVector
) {
    HOME("Trang chủ", Icons.Default.Home),
    SPORT_TV("Sport TV", Icons.Default.SportsSoccer),
    SOURCES("Kho nguồn", Icons.Default.LiveTv),
    SEARCH("Tìm kiếm", Icons.Default.Search),
    LIBRARY("Thư viện", Icons.Default.Bookmarks),
    SETTINGS("Cài đặt", Icons.Default.Settings)
}

@Composable
fun BmkfTvRoot(
    app: BmkfTvApp,
    searchShortcutTick: Int = 0,
    onLaunchPlayer: (PlayerLaunchPayload) -> Unit
) {
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(TvNavTab.HOME) }
    var activeDetailMovie by remember { mutableStateOf<MovieItem?>(null) }
    var sourcesInitialSource by remember { mutableStateOf(SourceId.KKPHIM) }
    var sourcesInitialCatalog by remember { mutableStateOf<String?>(null) }
    var searchInitialQuery by remember { mutableStateOf("") }
    var isQuickLoading by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(searchShortcutTick) {
        if (searchShortcutTick > 0) {
            activeDetailMovie = null
            currentTab = TvNavTab.SEARCH
        }
    }

    // Smart Android TV Back handling
    BackHandler(enabled = activeDetailMovie != null || currentTab != TvNavTab.HOME) {
        if (activeDetailMovie != null) {
            activeDetailMovie = null
        } else {
            currentTab = TvNavTab.HOME
        }
    }

    fun handleQuickPlay(movie: MovieItem) {
        scope.launch {
            isQuickLoading = true
            val detail = runCatching { app.repository.getMovieDetail(movie) }.getOrNull()
            isQuickLoading = false
            if (detail != null && detail.servers.isNotEmpty() && detail.servers.first().episodes.isNotEmpty()) {
                val saved = app.storage.getWatchProgress(movie.uniqueKey)
                val srvIdx = saved?.let { prog ->
                    detail.servers.indexOfFirst { s -> s.episodes.any { it.id == prog.episodeId } }
                }?.takeIf { it >= 0 } ?: 0
                val eps = detail.servers[srvIdx].episodes
                val epIdx = saved?.let { prog ->
                    eps.indexOfFirst { it.id == prog.episodeId }
                }?.takeIf { it >= 0 } ?: 0
                val resumeMs = if (saved != null && eps[epIdx].id == saved.episodeId) saved.positionMs else 0L

                onLaunchPlayer(
                    PlayerLaunchPayload(
                        movie = detail.item,
                        servers = detail.servers,
                        initialServerIndex = srvIdx,
                        initialEpisodeIndex = epIdx,
                        resumePositionMs = resumeMs,
                        imdbId = detail.imdbId
                    )
                )
            } else {
                activeDetailMovie = movie
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CinemaBackground)) {
        if (activeDetailMovie != null) {
            DetailScreen(
                movie = activeDetailMovie!!,
                repository = app.repository,
                onBack = { activeDetailMovie = null },
                onPlay = onLaunchPlayer,
                onSearchOtherSources = { title ->
                    activeDetailMovie = null
                    searchInitialQuery = title
                    currentTab = TvNavTab.SEARCH
                }
            )
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                TvNavigationSidebar(
                    currentTab = currentTab,
                    onSelectTab = { tab ->
                        if (tab != TvNavTab.SEARCH) {
                            searchInitialQuery = ""
                        }
                        currentTab = tab
                    }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    when (currentTab) {
                        TvNavTab.HOME -> HomeScreen(
                            repository = app.repository,
                            onOpenDetail = { movie ->
                                if (movie.source == SourceId.SPORT_TV) {
                                    handleQuickPlay(movie)
                                } else {
                                    activeDetailMovie = movie
                                }
                            },
                            onQuickPlay = ::handleQuickPlay,
                            onBrowseSource = { src, catId ->
                                if (src == SourceId.SPORT_TV) {
                                    currentTab = TvNavTab.SPORT_TV
                                } else {
                                    sourcesInitialSource = src
                                    sourcesInitialCatalog = catId
                                    currentTab = TvNavTab.SOURCES
                                }
                            }
                        )

                        TvNavTab.SPORT_TV -> SportTvScreen(
                            repository = app.repository,
                            onPlayLive = onLaunchPlayer
                        )

                        TvNavTab.SOURCES -> SourcesScreen(
                            repository = app.repository,
                            initialSource = sourcesInitialSource,
                            initialCatalogId = sourcesInitialCatalog,
                            onOpenDetail = { movie ->
                                if (movie.source == SourceId.SPORT_TV) {
                                    handleQuickPlay(movie)
                                } else {
                                    activeDetailMovie = movie
                                }
                            }
                        )

                        TvNavTab.SEARCH -> SearchScreen(
                            repository = app.repository,
                            initialQuery = searchInitialQuery,
                            onOpenDetail = { activeDetailMovie = it }
                        )

                        TvNavTab.LIBRARY -> LibraryScreen(
                            storage = app.storage,
                            onOpenDetail = { activeDetailMovie = it }
                        )

                        TvNavTab.SETTINGS -> SettingsScreen(
                            storage = app.storage
                        )
                    }
                }
            }
        }

        // Quick-play loading overlay
        if (isQuickLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CinemaBackground.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = CinemaAccent)
                    Spacer(Modifier.height(12.dp))
                    Text("Đang chuẩn bị phát phim...", color = CinemaTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun TvNavigationSidebar(
    currentTab: TvNavTab,
    onSelectTab: (TvNavTab) -> Unit
) {
    Column(
        modifier = Modifier
            .width(198.dp)
            .fillMaxHeight()
            .background(CinemaSurface)
            .border(width = 1.dp, color = CinemaBorderSubtle.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            // Brand Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(CinemaAccent)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "BMKF TV",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "BMKF TV",
                        color = CinemaTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = "6 NGUỒN • SPORT",
                        color = CinemaGold,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.0.sp
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvNavTab.entries.forEach { tab ->
                    SidebarNavItem(
                        tab = tab,
                        selected = tab == currentTab,
                        onClick = { onSelectTab(tab) }
                    )
                }
            }
        }

        // Source Legend Footer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CinemaBackground.copy(alpha = 0.6f))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "NGUỒN TÍCH HỢP",
                color = CinemaTextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SourceId.entries.chunked(3).forEach { rowSources ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowSources.forEach { src ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(src.accentHex))
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = src.shortLabel,
                                    color = CinemaTextMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarNavItem(
    tab: TvNavTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val bg = when {
        focused -> Color.White
        selected -> CinemaAccent.copy(alpha = 0.22f)
        else -> Color.Transparent
    }
    val contentColor = when {
        focused -> CinemaBackground
        selected -> Color.White
        else -> CinemaTextMuted
    }
    val borderColor = when {
        focused -> CinemaGold
        selected -> CinemaAccent
        else -> Color.Transparent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = if (focused) 1.03f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(11.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = contentColor,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = tab.label,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium
        )
    }
}
