package com.bmkf.tv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlin.math.min

/**
 * Wraps an upstream Media3 DataSource and transparently strips fake image headers
 * (e.g. PNG/JPEG/GIF/BMP/WebP wrappers used by Vietnamese HLS CDNs such as VSMOV)
 * prepended before 188-byte MPEG-TS segments.
 *
 * Reuses internal buffers across segments to avoid per-segment GC churn on low-RAM Android TVs.
 */
@OptIn(UnstableApi::class)
class TsDisguiseStrippingDataSource(
    private val upstream: DataSource
) : DataSource {

    // Reused small probe buffer (zero allocation on normal TS/fMP4 segments)
    private val probeBuffer = ByteArray(16)
    // Lazily allocated only if a disguised image segment is encountered
    private var scanBuffer: ByteArray? = null

    private var activeBuffer: ByteArray? = null
    private var activeOffset: Int = 0
    private var activeLength: Int = 0
    private var opened: Boolean = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val rawLength = upstream.open(dataSpec)
        opened = true
        activeBuffer = null
        activeOffset = 0
        activeLength = 0

        val path = dataSpec.uri.path.orEmpty()
        if (dataSpec.position != 0L || isMetadataPath(path)) {
            return rawLength
        }

        val initialRead = readFullyUpTo(probeBuffer, 0, probeBuffer.size)
        if (initialRead <= 0) {
            return rawLength
        }

        if (!looksLikeDisguisedImageHeader(probeBuffer, initialRead)) {
            activeBuffer = probeBuffer
            activeOffset = 0
            activeLength = initialRead
            return rawLength
        }

        // Disguised PNG/image header detected: scan up to 8KB (reuse buffer across segments)
        val scan = scanBuffer ?: ByteArray(MAX_SCAN_BYTES).also { scanBuffer = it }
        System.arraycopy(probeBuffer, 0, scan, 0, initialRead)
        val extraRead = readFullyUpTo(scan, initialRead, MAX_SCAN_BYTES - initialRead)
        val totalRead = initialRead + extraRead

        val tsOffset = findTsSyncOffset(scan, totalRead)
        return if (tsOffset > 0) {
            activeBuffer = scan
            activeOffset = tsOffset
            activeLength = totalRead
            if (rawLength != C.LENGTH_UNSET.toLong() && rawLength > tsOffset) {
                rawLength - tsOffset
            } else {
                C.LENGTH_UNSET.toLong()
            }
        } else {
            activeBuffer = scan
            activeOffset = 0
            activeLength = totalRead
            rawLength
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        val buf = activeBuffer
        if (buf != null && activeOffset < activeLength) {
            val available = activeLength - activeOffset
            val toCopy = min(available, length)
            System.arraycopy(buf, activeOffset, buffer, offset, toCopy)
            activeOffset += toCopy
            if (activeOffset >= activeLength) {
                activeBuffer = null
            }
            return toCopy
        }

        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        activeBuffer = null
        activeOffset = 0
        activeLength = 0
        if (opened) {
            opened = false
            upstream.close()
        }
    }

    private fun readFullyUpTo(target: ByteArray, startOffset: Int, maxLen: Int): Int {
        var total = 0
        while (total < maxLen) {
            val read = try {
                upstream.read(target, startOffset + total, maxLen - total)
            } catch (e: IOException) {
                if (total > 0) break else throw e
            }
            if (read == C.RESULT_END_OF_INPUT || read <= 0) break
            total += read
        }
        return total
    }

    private fun isMetadataPath(path: String): Boolean {
        return path.endsWith(".m3u8", ignoreCase = true) ||
            path.endsWith(".vtt", ignoreCase = true) ||
            path.endsWith(".srt", ignoreCase = true) ||
            path.endsWith(".json", ignoreCase = true) ||
            path.endsWith(".key", ignoreCase = true) ||
            path.endsWith(".mkv", ignoreCase = true) ||
            path.endsWith(".mp4", ignoreCase = true) ||
            path.endsWith(".flv", ignoreCase = true) ||
            path.endsWith(".m4v", ignoreCase = true) ||
            path.endsWith(".webm", ignoreCase = true) ||
            path.endsWith(".mov", ignoreCase = true)
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return TsDisguiseStrippingDataSource(upstreamFactory.createDataSource())
        }
    }

    companion object {
        private const val TS_PACKET_SIZE = 188
        private const val TS_SYNC_BYTE: Byte = 0x47
        private const val MAX_SCAN_BYTES = 8 * 1024

        fun looksLikeDisguisedImageHeader(bytes: ByteArray, length: Int): Boolean {
            if (length < 4) return false
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF

            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return true
            if (b0 == 0x42 && b1 == 0x4D) return true
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return true
            return false
        }

        fun findTsSyncOffset(bytes: ByteArray, length: Int): Int {
            val limit = length - (TS_PACKET_SIZE * 2) - 1
            if (limit <= 0) return -1
            for (i in 0..limit) {
                if (bytes[i] == TS_SYNC_BYTE &&
                    bytes[i + TS_PACKET_SIZE] == TS_SYNC_BYTE &&
                    bytes[i + TS_PACKET_SIZE * 2] == TS_SYNC_BYTE
                ) {
                    return i
                }
            }
            return -1
        }
    }
}
