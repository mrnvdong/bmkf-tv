package com.bmkf.tv

import com.bmkf.tv.data.model.StreamSource
import com.bmkf.tv.player.VideoColorGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoColorGuardTest {

    @Test
    fun detectsPureDolbyVisionRiskVsHdr10Fallback() {
        assertTrue(VideoColorGuard.isPureDolbyVisionRisk("Dune.2160p.DV.WEB-DL.mkv"))
        assertTrue(VideoColorGuard.isPureDolbyVisionRisk("Movie.2026.DoVi.2160p.mp4"))
        assertFalse(VideoColorGuard.isPureDolbyVisionRisk("Dune.2160p.DV.HDR10.WEB-DL.mkv"))
        assertFalse(VideoColorGuard.isPureDolbyVisionRisk("Game.of.Thrones.2160p.HDR.mkv"))
        assertFalse(VideoColorGuard.isPureDolbyVisionRisk("Avengers.1080p.WEB-DL.mp4"))
    }

    @Test
    fun demotesDolbyVisionStreamsBelowSafeSdrAndHdr10OnNonDvTv() {
        val streams = listOf(
            StreamSource(name = "ShowBox • 4K • Movie.2160p.DV.mkv", url = "https://cdn/dv.mkv", isHls = false),
            StreamSource(name = "ShowBox • 4K • Movie.2160p.DV.HDR.mkv", url = "https://cdn/dvhdr.mkv", isHls = false),
            StreamSource(name = "ShowBox • 4K • Movie.2160p.HDR.mkv", url = "https://cdn/hdr.mkv", isHls = false)
        )

        val sorted = VideoColorGuard.sortStreamsForColorSafety(streams, deviceSupportsDv = false)
        assertEquals("https://cdn/hdr.mkv", sorted[0].url)
        assertEquals("https://cdn/dvhdr.mkv", sorted[1].url)
        assertEquals("https://cdn/dv.mkv", sorted[2].url)
    }
}
