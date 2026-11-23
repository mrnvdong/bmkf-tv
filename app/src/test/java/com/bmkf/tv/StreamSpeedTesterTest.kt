package com.bmkf.tv

import com.bmkf.tv.data.model.StreamSource
import com.bmkf.tv.data.network.StreamProbeResult
import com.bmkf.tv.data.network.StreamSpeedTester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSpeedTesterTest {

    @Test
    fun smoothnessScorePrefersFastLowBitrateStreamOverStarvedHighBitrateStream() {
        val starvedHighBitrate = StreamProbeResult(
            original = StreamSource(name = "TNT Sports 8.5Mbps", url = "https://a/1.m3u8"),
            reachable = true,
            latencyMs = 950L,
            throughputMbps = 2.08,
            requiredBitrateMbps = 8.56 // 0.24x real-time -> guaranteed lag!
        )
        val smoothMediumBitrate = StreamProbeResult(
            original = StreamSource(name = "Sky Sports 3Mbps", url = "https://b/2.m3u8"),
            reachable = true,
            latencyMs = 320L,
            throughputMbps = 5.4,
            requiredBitrateMbps = 3.0 // 1.8x real-time -> smooth zero lag!
        )
        val deadServer = StreamProbeResult(
            original = StreamSource(name = "Dead Server", url = "https://c/3.m3u8"),
            reachable = false
        )

        assertTrue(smoothMediumBitrate.smoothnessScore > starvedHighBitrate.smoothnessScore)
        assertTrue(starvedHighBitrate.smoothnessScore > deadServer.smoothnessScore)

        assertTrue(smoothMediumBitrate.toAnnotatedStream().name.startsWith("🟢"))
        assertTrue(starvedHighBitrate.toAnnotatedStream().name.startsWith("🟡"))
        assertTrue(deadServer.toAnnotatedStream().name.startsWith("❌"))
    }

    @Test
    fun parsesLowestBandwidthFromMasterM3u8() {
        val m3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=8560000,BANDWIDTH=10700000,RESOLUTION=1920x1080
            tracks-v1a1/mono.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3200000,RESOLUTION=1280x720
            tracks-v2a1/mono.m3u8
        """.trimIndent()
        assertEquals(3.2, StreamSpeedTester.parseLowestBandwidthMbps(m3u8), 0.01)
    }
}
