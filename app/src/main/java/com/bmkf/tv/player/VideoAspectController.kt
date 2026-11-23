package com.bmkf.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.bmkf.tv.data.model.VideoScaleMode

data class ResolvedAspectConfig(
    val resizeMode: Int,
    /** Optional custom aspect ratio override on AspectRatioFrameLayout (null = use video natural DAR). */
    val aspectRatioOverride: Float? = null,
    val statusBadge: String
)

/**
 * Automatically adjusts ExoPlayer resize mode and aspect ratio to fit 16:9 Android TV screens cleanly,
 * fixing common Vietnamese HLS quirks:
 * - Anamorphic HD/SD streams missing SAR (1440x1080, 1280x1080, 720x576, 720x480)
 * - Corrupt MediaCodec/container pixelWidthHeightRatio (SAR) on Realtek/Amlogic TV hardware
 * - CinemaScope ultrawide (2.35:1 / 2.39:1) appearing shrunk in the middle of the TV
 * - True 4:3 classic shows (moderately widened without chopping subtitles)
 */
@OptIn(UnstableApi::class)
object VideoAspectController {

    fun resolve(
        mode: VideoScaleMode,
        videoWidth: Int,
        videoHeight: Int,
        pixelWidthHeightRatio: Float,
        screenWidth: Int,
        screenHeight: Int
    ): ResolvedAspectConfig {
        val screenRatio = if (screenWidth > 0 && screenHeight > 0) {
            screenWidth.toFloat() / screenHeight.toFloat()
        } else {
            16f / 9f
        }

        return when (mode) {
            VideoScaleMode.FIT -> ResolvedAspectConfig(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                aspectRatioOverride = null,
                statusBadge = "Gốc"
            )

            VideoScaleMode.ZOOM -> ResolvedAspectConfig(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                aspectRatioOverride = null,
                statusBadge = "Zoom"
            )

            VideoScaleMode.FILL -> ResolvedAspectConfig(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
                aspectRatioOverride = null,
                statusBadge = "Đầy 16:9"
            )

            VideoScaleMode.FORCE_16_9 -> ResolvedAspectConfig(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                aspectRatioOverride = 16f / 9f,
                statusBadge = "Ép 16:9"
            )

            VideoScaleMode.AUTO -> resolveAuto(
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                pixelWidthHeightRatio = pixelWidthHeightRatio,
                screenRatio = screenRatio
            )
        }
    }

    fun resolve(
        mode: VideoScaleMode,
        videoSize: VideoSize,
        screenWidth: Int,
        screenHeight: Int
    ): ResolvedAspectConfig = resolve(
        mode = mode,
        videoWidth = videoSize.width,
        videoHeight = videoSize.height,
        pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
        screenWidth = screenWidth,
        screenHeight = screenHeight
    )

    private fun resolveAuto(
        videoWidth: Int,
        videoHeight: Int,
        pixelWidthHeightRatio: Float,
        screenRatio: Float
    ): ResolvedAspectConfig {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return ResolvedAspectConfig(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
                aspectRatioOverride = null,
                statusBadge = "Tự động"
            )
        }

        val storageRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val safeSar = if (pixelWidthHeightRatio.isFinite() && pixelWidthHeightRatio in 0.5f..2.5f) {
            pixelWidthHeightRatio
        } else {
            1f
        }
        val reportedDar = storageRatio * safeSar

        // 1. Vertical / portrait clip: preserve natural shape so it isn't stretched horribly
        if (storageRatio < 0.95f && reportedDar < 0.95f) {
            return ResolvedAspectConfig(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                aspectRatioOverride = null,
                statusBadge = "Tự động (Dọc)"
            )
        }

        // 2. Broken SAR metadata on standard 16:9 encoded frame (e.g. 1920x1080 or 1280x720 reporting SAR 0.75)
        if (storageRatio in 1.65f..1.86f && (safeSar < 0.92f || safeSar > 1.08f)) {
            return ResolvedAspectConfig(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
                aspectRatioOverride = screenRatio,
                statusBadge = "Tự động (Sửa tỉ lệ)"
            )
        }

        // 3. Common anamorphic HD/SD widescreen encodes missing SAR (1440x1080, 1280x1080, 720x576, 720x480)
        val isAnamorphicWidescreen =
            (videoWidth == 1440 && videoHeight == 1080) ||
                (videoWidth == 1280 && videoHeight == 1080) ||
                (videoWidth == 720 && (videoHeight == 576 || videoHeight == 480))
        if (isAnamorphicWidescreen) {
            return ResolvedAspectConfig(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
                aspectRatioOverride = screenRatio,
                statusBadge = "Tự động (16:9)"
            )
        }

        // 4. Standard / Near-16:9 (16:10, 16:9, 1.85:1 theatrical): fill TV cleanly edge-to-edge
        if (reportedDar in 1.54f..1.98f) {
            return ResolvedAspectConfig(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
                aspectRatioOverride = screenRatio,
                statusBadge = "Tự động (Đầy)"
            )
        }

        // 5. Ultrawide CinemaScope (2.00:1 .. 2.76:1, e.g. 1920x800):
        // Use proportional ZOOM so it fills the TV height nicely without squishing or looking tiny
        if (reportedDar > 1.98f) {
            return ResolvedAspectConfig(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                aspectRatioOverride = null,
                statusBadge = "Tự động (Zoom)"
            )
        }

        // 6. Classic 4:3 / 5:4 (e.g. 640x480, 960x720, TVB, old anime):
        // Gently expand to 16:10 (~1.60) inside FIT so it fills ~90% of TV width without chopping subtitles or over-stretching faces
        return ResolvedAspectConfig(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
            aspectRatioOverride = 1.60f.coerceAtMost(screenRatio),
            statusBadge = "Tự động (4:3→16:10)"
        )
    }
}
