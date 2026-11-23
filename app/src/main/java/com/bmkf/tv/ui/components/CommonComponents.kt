package com.bmkf.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.ui.theme.CinemaAccent
import com.bmkf.tv.ui.theme.CinemaBackground
import com.bmkf.tv.ui.theme.CinemaBorderSubtle
import com.bmkf.tv.ui.theme.CinemaGold
import com.bmkf.tv.ui.theme.CinemaSurface
import com.bmkf.tv.ui.theme.CinemaSurfaceElevated
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary

private val BadgeShape = RoundedCornerShape(6.dp)
private val ButtonShape = RoundedCornerShape(10.dp)
private val ChipShape = RoundedCornerShape(20.dp)
private val BadgeBg = CinemaBackground.copy(alpha = 0.86f)
private val SubtleWhiteBorder = Color.White.copy(alpha = 0.22f)

@Composable
fun SourceBadge(
    source: SourceId,
    compact: Boolean = true,
    modifier: Modifier = Modifier
) {
    val accent = Color(source.accentHex)
    Box(
        modifier = modifier
            .clip(BadgeShape)
            .background(BadgeBg)
            .border(1.dp, accent, BadgeShape)
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (compact) source.shortLabel else source.displayName,
            color = accent,
            fontSize = if (compact) 10.sp else 12.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

@Composable
fun TvActionButton(
    label: String,
    icon: ImageVector? = null,
    primary: Boolean = false,
    accentColor: Color = CinemaAccent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused && primary -> Color.White
        focused -> CinemaSurfaceElevated
        primary -> accentColor
        else -> CinemaSurface
    }
    val contentColor = if (focused && primary) CinemaBackground else CinemaTextPrimary
    val border = when {
        focused -> BorderStroke(2.5.dp, if (primary) CinemaGold else Color.White)
        primary -> BorderStroke(1.dp, accentColor)
        else -> BorderStroke(1.dp, SubtleWhiteBorder)
    }

    Surface(
        modifier = modifier
            .graphicsLayer {
                val s = if (focused) 1.05f else 1f
                scaleX = s
                scaleY = s
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
        shape = ButtonShape,
        color = bg,
        border = border
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(
                horizontal = if (label.isEmpty()) 12.dp else 20.dp,
                vertical = 12.dp
            )
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label.ifBlank { "Action" },
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                if (label.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                }
            }
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun TvFilterChip(
    label: String,
    selected: Boolean,
    accentColor: Color = CinemaAccent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> accentColor.copy(alpha = 0.28f)
        else -> CinemaSurface
    }
    val textColor = when {
        focused -> CinemaBackground
        selected -> Color.White
        else -> CinemaTextMuted
    }
    val borderColor = when {
        focused -> CinemaGold
        selected -> accentColor
        else -> CinemaBorderSubtle
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                val s = if (focused) 1.04f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(ChipShape)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, borderColor, ChipShape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LoadingState(
    message: String = "Đang tải dữ liệu phim...",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = CinemaAccent,
                strokeWidth = 4.dp,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(message, color = CinemaTextMuted, fontSize = 14.sp)
        }
    }
}

@Composable
fun ErrorRetryState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = message,
                color = CinemaTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(16.dp))
            TvActionButton(label = "Thử lại", primary = true, onClick = onRetry)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(title, color = CinemaTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = CinemaTextMuted, fontSize = 14.sp)
        }
    }
}
