package com.bmkf.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import coil.size.Size

private val POSTER_SIZE = Size(220, 330)
private val THUMB_SIZE = Size(360, 202)
private val BACKDROP_SIZE = Size(854, 480)

@Composable
fun rememberPosterRequest(url: String): ImageRequest {
    val context = LocalContext.current
    return remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(POSTER_SIZE)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(true)
            .crossfade(false)
            .build()
    }
}

@Composable
fun rememberThumbRequest(url: String): ImageRequest {
    val context = LocalContext.current
    return remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(THUMB_SIZE)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(true)
            .crossfade(false)
            .build()
    }
}

@Composable
fun rememberBackdropRequest(url: String): ImageRequest {
    val context = LocalContext.current
    return remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(BACKDROP_SIZE)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(true)
            .crossfade(false)
            .build()
    }
}
