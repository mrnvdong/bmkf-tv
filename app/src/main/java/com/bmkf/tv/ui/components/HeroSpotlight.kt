package com.bmkf.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bmkf.tv.data.model.MovieItem
import com.bmkf.tv.ui.theme.CinemaAccent
import com.bmkf.tv.ui.theme.CinemaBackground
import com.bmkf.tv.ui.theme.CinemaGold
import com.bmkf.tv.ui.theme.CinemaSurface
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary
import com.bmkf.tv.ui.theme.CinemaTextSecondary

// Hoisted single-pass scrim & shapes — zero per-frame allocations, zero background auto-rotation timer
private val HeroShape = RoundedCornerShape(16.dp)
private val MetaTagShape = RoundedCornerShape(6.dp)
private val HeroBorder = Color.White.copy(alpha = 0.1f)
private val HeroHorizontalScrim = Brush.horizontalGradient(
    0.0f to CinemaBackground.copy(alpha = 0.96f),
    0.55f to CinemaBackground.copy(alpha = 0.78f),
    1.0f to Color.Transparent
)

@Composable
fun HeroSpotlight(
    featuredMovies: List<MovieItem>,
    onWatchNow: (MovieItem) -> Unit,
    onOpenDetail: (MovieItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (featuredMovies.isEmpty()) return

    var activeIndex by remember(featuredMovies) { mutableIntStateOf(0) }
    val safeIndex = activeIndex % featuredMovies.size
    val movie = featuredMovies[safeIndex]
    val backdropUrl = movie.backdropUrl.ifBlank { movie.posterUrl }
    val backdropRequest = rememberBackdropRequest(backdropUrl)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(296.dp)
            .clip(HeroShape)
            .background(CinemaSurface)
            .border(1.dp, HeroBorder, HeroShape)
    ) {
        AsyncImage(
            model = backdropRequest,
            contentDescription = movie.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Single gradient pass (cuts GPU overdraw in half compared to double full-box gradients)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HeroHorizontalScrim)
        )

        Column(
            verticalArrangement = Arrangement.Bottom,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.66f)
                .padding(horizontal = 28.dp, vertical = 22.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SourceBadge(source = movie.source, compact = false)
                if (movie.year.isNotBlank()) MetaTag(movie.year)
                if (movie.quality.isNotBlank()) MetaTag(movie.quality, highlight = true)
                if (movie.rating.isNotBlank()) MetaTag("★ ${movie.rating}", gold = true)
                if (movie.episodeCurrent.isNotBlank()) MetaTag(movie.episodeCurrent)
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = movie.title,
                color = CinemaTextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 31.sp
            )

            if (movie.originTitle.isNotBlank() && movie.originTitle != movie.title) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = movie.originTitle,
                    color = CinemaTextMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (movie.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = movie.description,
                    color = CinemaTextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionButton(
                    label = "Xem ngay",
                    icon = Icons.Default.PlayArrow,
                    primary = true,
                    onClick = { onWatchNow(movie) }
                )
                TvActionButton(
                    label = "Chi tiết",
                    icon = Icons.Default.Info,
                    primary = false,
                    onClick = { onOpenDetail(movie) }
                )
                if (featuredMovies.size > 1) {
                    TvActionButton(
                        label = "",
                        icon = Icons.Default.ChevronLeft,
                        primary = false,
                        onClick = {
                            activeIndex = if (safeIndex == 0) featuredMovies.lastIndex else safeIndex - 1
                        }
                    )
                    TvActionButton(
                        label = "",
                        icon = Icons.Default.ChevronRight,
                        primary = false,
                        onClick = {
                            activeIndex = (safeIndex + 1) % featuredMovies.size
                        }
                    )
                }
            }
        }

        if (featuredMovies.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            ) {
                featuredMovies.indices.forEach { idx ->
                    val selected = idx == safeIndex
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (selected) 20.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (selected) CinemaAccent else Color.White.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaTag(
    text: String,
    highlight: Boolean = false,
    gold: Boolean = false
) {
    Box(
        modifier = Modifier
            .clip(MetaTagShape)
            .background(
                when {
                    gold -> CinemaGold.copy(alpha = 0.2f)
                    highlight -> CinemaAccent.copy(alpha = 0.25f)
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = if (gold) CinemaGold else CinemaTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
