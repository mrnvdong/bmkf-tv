package com.bmkf.tv

import com.bmkf.tv.player.TsDisguiseStrippingDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsDisguiseStrippingTest {

    @Test
    fun detectsPngMagicAndFindsExactMpegTsOffset() {
        // Simulate VSMOV PNG-disguised MPEG-TS segment (452-byte PNG prefix + 3x188-byte TS packets)
        val prefixLen = 452
        val payload = ByteArray(prefixLen + 188 * 4)
        // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
        payload[0] = 0x89.toByte()
        payload[1] = 0x50
        payload[2] = 0x4E
        payload[3] = 0x47
        // Place MPEG-TS sync bytes at prefixLen, prefixLen+188, prefixLen+376, prefixLen+564
        for (pkt in 0 until 4) {
            payload[prefixLen + pkt * 188] = 0x47
        }

        assertTrue(TsDisguiseStrippingDataSource.looksLikeDisguisedImageHeader(payload, payload.size))
        assertEquals(prefixLen, TsDisguiseStrippingDataSource.findTsSyncOffset(payload, payload.size))
    }

    @Test
    fun normalMpegTsIsNotFlaggedAsDisguisedImage() {
        val ts = ByteArray(188 * 3)
        ts[0] = 0x47
        ts[1] = 0x40
        ts[2] = 0x00
        ts[3] = 0x10
        ts[188] = 0x47
        ts[376] = 0x47

        assertFalse(TsDisguiseStrippingDataSource.looksLikeDisguisedImageHeader(ts, ts.size))
    }

    @Test
    fun purePngWithoutTsReturnsNegativeOne() {
        val png = ByteArray(1024)
        png[0] = 0x89.toByte()
        png[1] = 0x50
        png[2] = 0x4E
        png[3] = 0x47
        assertTrue(TsDisguiseStrippingDataSource.looksLikeDisguisedImageHeader(png, png.size))
        assertEquals(-1, TsDisguiseStrippingDataSource.findTsSyncOffset(png, png.size))
    }
}
