package com.bmkf.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.data.model.WatchHistoryEntry
import com.bmkf.tv.ui.theme.CinemaAccent
import com.bmkf.tv.ui.theme.CinemaBackground
import com.bmkf.tv.ui.theme.CinemaGold
import com.bmkf.tv.ui.theme.CinemaSurface
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary

// Hoisted static shapes, colors, and brushes to avoid per-card allocations during TV D-pad scrolling
private val CardShape = RoundedCornerShape(12.dp)
private val PillShape = RoundedCornerShape(6.dp)
private val ProgressShape = RoundedCornerShape(2.dp)
private val CardBorderUnfocused = Color.White.copy(alpha = 0.08f)
private val PillBg = CinemaBackground.copy(alpha = 0.84f)
private val ProgressTrackBg = Color.White.copy(alpha = 0.25f)
private val CardBottomScrim = Brush.verticalGradient(
    listOf(Color.Transparent, CinemaBackground.copy(alpha = 0.88f))
)
private val ContinueScrim = Brush.verticalGradient(
    listOf(Color.Transparent, CinemaBackground.copy(alpha = 0.90f))
)

@Composable
fun PosterMovieCard(
    movie: MovieItem,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    cardWidth: Dp = 148.dp,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val imageUrl = movie.posterUrl.ifBlank { movie.backdropUrl }
    val request = rememberPosterRequest(imageUrl)

    val topRightText = remember(movie.rating, movie.quality) {
        when {
            movie.rating.isNotBlank() -> "★ ${movie.rating}"
            movie.quality.isNotBlank() -> movie.quality
            else -> ""
        }
    }
    val bottomTag = remember(movie.episodeCurrent, movie.lang, movie.year) {
        movie.episodeCurrent.ifBlank {
            listOf(movie.lang, movie.year).filter { it.isNotBlank() }.joinToString(" • ")
        }
    }

    Column(
        modifier = modifier
            .width(cardWidth)
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                // Pure GPU render-phase scale: zero Compose relayout on D-pad movement
                val s = if (focused) 1.07f else 1.0f
                scaleX = s
                scaleY = s
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(CardShape)
                .background(CinemaSurface)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) Color.White else CardBorderUnfocused,
                    shape = CardShape
                )
        ) {
            AsyncImage(
                model = request,
                contentDescription = movie.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .align(Alignment.BottomCenter)
                    .background(CardBottomScrim)
            )

            SourceBadge(
                source = movie.source,
                compact = true,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            )

            if (topRightText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(PillShape)
                        .background(PillBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = topRightText,
                        color = if (topRightText.startsWith("★")) CinemaGold else CinemaTextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            if (bottomTag.isNotBlank()) {
                Text(
                    text = bottomTag,
                    color = CinemaTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = movie.title,
            color = if (focused) Color.White else CinemaTextPrimary,
            fontSize = 13.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val subtitle = movie.originTitle.ifBlank { movie.year }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                color = CinemaTextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ContinueWatchingCard(
    entry: WatchHistoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val imageUrl = entry.movie.backdropUrl.ifBlank { entry.movie.posterUrl }
    val request = rememberThumbRequest(imageUrl)

    Column(
        modifier = modifier
            .width(244.dp)
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                val s = if (focused) 1.05f else 1.0f
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
                .clip(CardShape)
                .background(CinemaSurface)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) Color.White else CardBorderUnfocused,
                    shape = CardShape
                )
        ) {
            AsyncImage(
                model = request,
                contentDescription = entry.movie.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ContinueScrim)
            )

            SourceBadge(
                source = entry.movie.source,
                compact = true,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                Text(
                    text = entry.movie.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = entry.episodeName,
                        color = CinemaGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (entry.durationMs > 0L) {
                        val pct = (entry.progressFraction * 100).toInt()
                        Text(
                            text = "$pct%",
                            color = CinemaTextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(ProgressShape)
                        .background(ProgressTrackBg)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(entry.progressFraction.coerceAtLeast(0.05f))
                            .background(CinemaAccent)
                    )
                }
            }
        }
    }
}
