package com.bmkf.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.storage.LocalStorage
import com.bmkf.tv.ui.components.ContinueWatchingCard
import com.bmkf.tv.ui.components.EmptyState
import com.bmkf.tv.ui.components.PosterMovieCard
import com.bmkf.tv.ui.components.TvActionButton
import com.bmkf.tv.ui.components.TvFilterChip
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary

@Composable
fun LibraryScreen(
    storage: LocalStorage,
    onOpenDetail: (MovieItem) -> Unit
) {
    val history by storage.history.collectAsState()
    val favorites by storage.favorites.collectAsState()
    var activeTab by remember { mutableIntStateOf(0) } // 0 = History, 1 = Favorites

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 28.dp, top = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Thư viện cá nhân",
                    color = CinemaTextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Tiếp tục xem đúng giây đã dừng & quản lý danh sách phim yêu thích",
                    color = CinemaTextMuted,
                    fontSize = 13.sp
                )
            }
            if (activeTab == 0 && history.isNotEmpty()) {
                TvActionButton(
                    label = "Xóa lịch sử",
                    icon = Icons.Default.DeleteOutline,
                    primary = false,
                    onClick = { storage.clearHistory() }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvFilterChip(
                label = "Lịch sử xem (${history.size})",
                selected = activeTab == 0,
                onClick = { activeTab = 0 }
            )
            TvFilterChip(
                label = "Phim yêu thích (${favorites.size})",
                selected = activeTab == 1,
                onClick = { activeTab = 1 }
            )
        }

        Spacer(Modifier.height(18.dp))

        if (activeTab == 0) {
            if (history.isEmpty()) {
                EmptyState(
                    title = "Chưa có lịch sử xem",
                    subtitle = "Các phim bạn đang xem sẽ tự động lưu lại vị trí phát tại đây."
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 240.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 36.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(history, key = { "hist_${it.movie.uniqueKey}" }) { entry ->
                        ContinueWatchingCard(
                            entry = entry,
                            onClick = { onOpenDetail(entry.movie) }
                        )
                    }
                }
            }
        } else {
            if (favorites.isEmpty()) {
                EmptyState(
                    title = "Chưa có phim yêu thích",
                    subtitle = "Mở chi tiết phim bất kỳ và nhấn 'Yêu thích' để lưu lại."
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 154.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 36.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(favorites, key = { "fav_${it.uniqueKey}" }) { movie ->
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
