package com.bmkf.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.bmkf.tv.data.model.VideoScaleMode
import com.bmkf.tv.player.VideoAspectController
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class VideoAspectControllerTest {

    @Test
    fun autoModeFillsStandard16x9AndAnamorphic1440x1080() {
        val standard = VideoAspectController.resolve(
            mode = VideoScaleMode.AUTO,
            videoWidth = 1920,
            videoHeight = 1080,
            pixelWidthHeightRatio = 1f,
            screenWidth = 1920,
            screenHeight = 1080
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, standard.resizeMode)

        val anamorphic = VideoAspectController.resolve(
            mode = VideoScaleMode.AUTO,
            videoWidth = 1440,
            videoHeight = 1080,
            pixelWidthHeightRatio = 1f,
            screenWidth = 1920,
            screenHeight = 1080
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, anamorphic.resizeMode)
    }

    @Test
    fun autoModeFixesBrokenSarOn16x9Video() {
        val brokenSar = VideoAspectController.resolve(
            mode = VideoScaleMode.AUTO,
            videoWidth = 1280,
            videoHeight = 720,
            pixelWidthHeightRatio = 0.75f,
            screenWidth = 1920,
            screenHeight = 1080
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, brokenSar.resizeMode)
    }

    @Test
    fun autoModeZoomsUltrawideCinemaScopeAndKeepsPortraitFit() {
        val cinema = VideoAspectController.resolve(
            mode = VideoScaleMode.AUTO,
            videoWidth = 1920,
            videoHeight = 800,
            pixelWidthHeightRatio = 1f,
            screenWidth = 1920,
            screenHeight = 1080
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, cinema.resizeMode)

        val portrait = VideoAspectController.resolve(
            mode = VideoScaleMode.AUTO,
            videoWidth = 720,
            videoHeight = 1280,
            pixelWidthHeightRatio = 1f,
            screenWidth = 1920,
            screenHeight = 1080
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, portrait.resizeMode)
    }

    @Test
    fun autoModeExpandsClassic4x3GentlyTo16x10() {
        val classic = VideoAspectController.resolve(
            mode = VideoScaleMode.AUTO,
            videoWidth = 640,
            videoHeight = 480,
            pixelWidthHeightRatio = 1f,
            screenWidth = 1920,
            screenHeight = 1080
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, classic.resizeMode)
        assertEquals(1.60f, classic.aspectRatioOverride ?: 0f, 0.01f)
    }
}
