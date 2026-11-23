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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.repository.MovieRepository
import com.bmkf.tv.ui.components.EmptyState
import com.bmkf.tv.ui.components.LoadingState
import com.bmkf.tv.ui.components.PosterMovieCard
import com.bmkf.tv.ui.components.SourceBadge
import com.bmkf.tv.ui.components.TvActionButton
import com.bmkf.tv.ui.components.TvFilterChip
import com.bmkf.tv.ui.theme.CinemaAccent
import com.bmkf.tv.ui.theme.CinemaBorderSubtle
import com.bmkf.tv.ui.theme.CinemaSurface
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary
import kotlinx.coroutines.launch

private val QUICK_SEARCH_TAGS = listOf(
    "Avengers", "One Piece", "Conan", "Harry Potter",
    "Doraemon", "Spider-Man", "Tây Du Ký", "Hàn Quốc", "4K"
)

@Composable
fun SearchScreen(
    repository: MovieRepository,
    initialQuery: String = "",
    onOpenDetail: (MovieItem) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var filterSource by remember { mutableStateOf<SourceId?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var resultsBySource by remember { mutableStateOf<Map<SourceId, List<MovieItem>>>(emptyMap()) }

    fun executeSearch(term: String, src: SourceId? = filterSource) {
        val clean = term.trim()
        if (clean.length < 2) return
        scope.launch {
            isSearching = true
            hasSearched = true
            resultsBySource = runCatching {
                repository.searchAll(clean, src)
            }.getOrDefault(emptyMap())
            isSearching = false
        }
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.trim().length >= 2) {
            executeSearch(initialQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 28.dp, top = 20.dp)
    ) {
        Text(
            text = "Tìm kiếm đa nguồn",
            color = CinemaTextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "Tìm song song trên KKPhim, VSMOV, CoBePhim, K20, ShowBox 4K & Sport TV",
            color = CinemaTextMuted,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(14.dp))

        // Search input + action buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Nhập tên phim (VD: Avengers, One Piece...)", color = CinemaTextMuted) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CinemaSurface,
                    unfocusedContainerColor = CinemaSurface,
                    focusedTextColor = CinemaTextPrimary,
                    unfocusedTextColor = CinemaTextPrimary,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = CinemaBorderSubtle,
                    cursorColor = CinemaAccent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { executeSearch(query) }),
                modifier = Modifier.weight(1f)
            )
            TvActionButton(
                label = "Tìm phim",
                icon = Icons.Default.Search,
                primary = true,
                onClick = { executeSearch(query) }
            )
            if (query.isNotEmpty()) {
                TvActionButton(
                    label = "Xóa",
                    icon = Icons.Default.Clear,
                    primary = false,
                    onClick = {
                        query = ""
                        resultsBySource = emptyMap()
                        hasSearched = false
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Source filter chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "src_all") {
                TvFilterChip(
                    label = "Tất cả 6 nguồn",
                    selected = filterSource == null,
                    onClick = {
                        filterSource = null
                        if (query.trim().length >= 2) executeSearch(query, null)
                    }
                )
            }
            items(SourceId.entries) { src ->
                TvFilterChip(
                    label = src.displayName,
                    selected = filterSource == src,
                    accentColor = Color(src.accentHex),
                    onClick = {
                        filterSource = src
                        if (query.trim().length >= 2) executeSearch(query, src)
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Quick suggestions for effortless TV remote testing
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(QUICK_SEARCH_TAGS) { tag ->
                TvFilterChip(
                    label = tag,
                    selected = query.equals(tag, ignoreCase = true),
                    onClick = {
                        query = tag
                        executeSearch(tag)
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        val nonEmptyGroups = resultsBySource.entries.filter { it.value.isNotEmpty() }

        when {
            isSearching -> LoadingState("Đang tìm \"$query\" trên các nguồn phim...")
            !hasSearched -> EmptyState(
                title = "Nhập từ khóa hoặc chọn gợi ý phía trên",
                subtitle = "Nhấn OK vào ô tìm kiếm hoặc chọn 1 từ khóa nhanh bằng điều khiển TV."
            )
            nonEmptyGroups.isEmpty() -> EmptyState(
                title = "Không tìm thấy kết quả cho \"$query\"",
                subtitle = "Thử rút gọn từ khóa hoặc chuyển sang tìm trên Tất cả 4 nguồn."
            )
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(bottom = 36.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(nonEmptyGroups, key = { it.key.name }) { (source, itemsList) ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SourceBadge(source = source, compact = false)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "${itemsList.size} kết quả",
                                    color = CinemaTextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                items(itemsList, key = { "search_${it.uniqueKey}" }) { movie ->
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
}
